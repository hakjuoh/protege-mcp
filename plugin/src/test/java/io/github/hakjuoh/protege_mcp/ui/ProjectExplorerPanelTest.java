package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;

import java.awt.Component;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

class ProjectExplorerPanelTest {

    @Test
    void preservesCollapseStateAndExposesAccessibleKeyboardActions(@TempDir Path root)
            throws Exception {
        Files.createDirectory(root.resolve("directory"));
        Path filePath = Files.writeString(root.resolve("ontology.ttl"), "");
        Path inactivePath = Files.writeString(root.resolve("inactive.owl"), "");
        OWLOntology activeOntology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/active"));
        OWLOntology inactiveOntology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/inactive"));
        ProjectWorkspaceSnapshot.ProjectFile directory = new ProjectWorkspaceSnapshot.ProjectFile(
                root.resolve("directory"), Path.of("directory"),
                ProjectWorkspaceSnapshot.FileKind.DIRECTORY, false, false, null, List.of());
        ProjectWorkspaceSnapshot.ProjectFile file = new ProjectWorkspaceSnapshot.ProjectFile(
                filePath, Path.of("ontology.ttl"), ProjectWorkspaceSnapshot.FileKind.ONTOLOGY,
                true, false, activeOntology, List.of());
        ProjectWorkspaceSnapshot.ProjectFile inactiveFile =
                new ProjectWorkspaceSnapshot.ProjectFile(inactivePath, Path.of("inactive.owl"),
                        ProjectWorkspaceSnapshot.FileKind.ONTOLOGY, false, false,
                        inactiveOntology, List.of());
        Path externalPath = root.getParent().resolve("external.rdf");
        ProjectWorkspaceSnapshot.OntologyEntry localExternal =
                new ProjectWorkspaceSnapshot.OntologyEntry("ExternalNamespace",
                        "https://example.org/external",
                        ProjectWorkspaceSnapshot.OntologyLocation.EXTERNAL, null,
                        List.of(new ProjectWorkspaceSnapshot.OntologyDocument(
                                externalPath.toUri().toString(), externalPath, true)));
        ProjectWorkspaceSnapshot.OntologyEntry networkExternal =
                new ProjectWorkspaceSnapshot.OntologyEntry("Core",
                        "https://example.org/core",
                        ProjectWorkspaceSnapshot.OntologyLocation.EXTERNAL, inactiveOntology,
                        List.of(new ProjectWorkspaceSnapshot.OntologyDocument(
                                "https://example.org/core.owl", null, true)));
        ProjectWorkspaceSnapshot.OntologyEntry unavailableExternal =
                new ProjectWorkspaceSnapshot.OntologyEntry("MissingImport",
                        "https://example.org/missing",
                        ProjectWorkspaceSnapshot.OntologyLocation.UNRESOLVED, null,
                        List.of(new ProjectWorkspaceSnapshot.OntologyDocument(
                                "https://example.org/missing.owl", null, false)));
        ProjectWorkspaceSnapshot snapshot = new ProjectWorkspaceSnapshot(root, "project",
                List.of(directory, inactiveFile, file), false, true, activeOntology, List.of(),
                List.of(localExternal, networkExternal, unavailableExternal));
        AtomicInteger opened = new AtomicInteger();
        TestListener listener = new TestListener(opened);

        SwingUtilities.invokeAndWait(() -> {
            try {
                javax.swing.Icon fallbackOntologyIcon = new javax.swing.ImageIcon(
                        new java.awt.image.BufferedImage(8, 8,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB));
                javax.swing.Icon loadedOntologyIcon = new javax.swing.ImageIcon(
                        new java.awt.image.BufferedImage(9, 9,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB));
                ProjectExplorerPanel panel = new ProjectExplorerPanel(fallbackOntologyIcon,
                        ontology -> loadedOntologyIcon, listener);
                panel.showSnapshot(snapshot);
                panel.setSize(285, 400);
                panel.doLayout();
                assertEquals(1, panel.getComponentCount(),
                        "the tree starts at the panel top; its header lives in ChatView's top bar");
                assertEquals(0, panel.getComponent(0).getY());
                JTree tree = tree(panel);
                assertEquals("Project files and ontologies",
                        tree.getAccessibleContext().getAccessibleName());
                assertNotNull(tree.getActionMap().get("activate-node"));
                assertNotNull(tree.getActionMap().get("show-node-menu"));
                assertTrue(tree.isExpanded(projectRow(tree)));
                assertFalse(tree.isExpanded(row(tree, "directory")),
                        "initial expansion stops at the top-level sections");
                assertTrue(row(tree, "Live updates limited (10,000 directory watch limit)") > 0);
                int externalRow = row(tree, "External Ontologies");
                tree.expandRow(externalRow);
                tree.expandRow(row(tree, "File"));
                tree.expandRow(row(tree, "Memory"));
                tree.expandRow(row(tree, "Unavailable"));
                assertTrue(row(tree, "external.rdf") > 0);
                assertTrue(row(tree, "Core") > 0);
                assertTrue(row(tree, "MissingImport · import not resolved") > 0);
                assertFalse(hasRow(tree, "Ontologies"));

                tree.collapseRow(projectRow(tree));
                tree.collapseRow(row(tree, "External Ontologies"));
                panel.showSnapshot(snapshot);
                assertFalse(tree.isExpanded(projectRow(tree)),
                        "an intentionally collapsed tree stays collapsed after refresh");

                tree.expandRow(projectRow(tree));
                int fileRow = row(tree, "ontology.ttl");
                tree.setSelectionRow(fileRow);
                Rectangle rowBounds = tree.getRowBounds(fileRow);
                tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, rowBounds.x + 2,
                        rowBounds.y + rowBounds.height / 2, 2, false, MouseEvent.BUTTON1));
                assertEquals(1, opened.get());

                Component rendered = tree.getCellRenderer().getTreeCellRendererComponent(tree,
                        tree.getPathForRow(fileRow).getLastPathComponent(), false, false,
                        true, fileRow, false);
                assertEquals("ontology.ttl", ((javax.swing.JLabel) rendered).getText());
                assertEquals(loadedOntologyIcon, ((javax.swing.JLabel) rendered).getIcon(),
                        "loaded files use Protégé's ontology-specific icon provider");
                assertEquals(ProjectExplorerPanel.activeOntologyColor(tree.getBackground()),
                        ((javax.swing.JLabel) rendered).getForeground(),
                        "the active ontology is the only file rendered with the active color");
                assertTrue(((javax.swing.JLabel) rendered).getToolTipText()
                        .startsWith("Active ontology"));
                assertEquals("Active ontology: ontology.ttl",
                        ((javax.swing.JLabel) rendered).getAccessibleContext()
                                .getAccessibleDescription());
                assertTrue((((javax.swing.JLabel) rendered).getFont().getStyle()
                        & java.awt.Font.BOLD) != 0,
                        () -> "rendered font=" + ((javax.swing.JLabel) rendered).getFont());
                int inactiveRow = row(tree, "inactive.owl");
                Component inactiveRendered = tree.getCellRenderer().getTreeCellRendererComponent(
                        tree, tree.getPathForRow(inactiveRow).getLastPathComponent(), false, false,
                        true, inactiveRow, false);
                assertFalse(ProjectExplorerPanel.activeOntologyColor(tree.getBackground()).equals(
                        ((javax.swing.JLabel) inactiveRendered).getForeground()));
                assertFalse(((javax.swing.JLabel) inactiveRendered).getAccessibleContext()
                        .getAccessibleDescription().startsWith("Active ontology"));

                listener.membershipEditable = false;
                JPopupMenu menu = contextMenu(panel, tree.getSelectionPath());
                JMenuItem membership = (JMenuItem) menu.getComponent(2);
                assertFalse(membership.isEnabled());
                listener.membershipEditable = true;
                menu = contextMenu(panel, tree.getSelectionPath());
                assertTrue(((JMenuItem) menu.getComponent(2)).isEnabled());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    @Test
    void activeColorMaintainsContrastOnLightAndDarkThemes() {
        Color lightActive = ProjectExplorerPanel.activeOntologyColor(Color.WHITE);
        Color darkActive = ProjectExplorerPanel.activeOntologyColor(Color.BLACK);
        assertTrue(contrast(lightActive, Color.WHITE) >= 4.5);
        assertTrue(contrast(darkActive, Color.BLACK) >= 4.5);
        assertFalse(lightActive.equals(darkActive));
    }

    private static double contrast(Color first, Color second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen())
                + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static JTree tree(ProjectExplorerPanel panel) throws ReflectiveOperationException {
        Field field = ProjectExplorerPanel.class.getDeclaredField("tree");
        field.setAccessible(true);
        return (JTree) field.get(panel);
    }

    private static int projectRow(JTree tree) { return row(tree, "project"); }

    private static int row(JTree tree, String label) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            if (label.equals(String.valueOf(tree.getPathForRow(row).getLastPathComponent()))) return row;
        }
        throw new AssertionError("Missing tree row: " + label);
    }

    private static boolean hasRow(JTree tree, String label) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            if (label.equals(String.valueOf(tree.getPathForRow(row).getLastPathComponent()))) {
                return true;
            }
        }
        return false;
    }

    private static JPopupMenu contextMenu(ProjectExplorerPanel panel, TreePath path)
            throws ReflectiveOperationException {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Method method = java.util.Arrays.stream(ProjectExplorerPanel.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("contextMenu"))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        return (JPopupMenu) method.invoke(panel, node.getUserObject());
    }

    private static final class TestListener implements ProjectExplorerPanel.Listener {
        private final AtomicInteger opened;
        private boolean membershipEditable;

        private TestListener(AtomicInteger opened) { this.opened = opened; }
        @Override public void closeRequested() { }
        @Override public void activateOntology(OWLOntology ontology) { opened.incrementAndGet(); }
        @Override public void saveOntologyIntoProject(OWLOntology ontology, Path projectRoot) { }
        @Override public void openFile(ProjectWorkspaceSnapshot.ProjectFile file) {
            opened.incrementAndGet();
        }
        @Override public void addToPolicy(ProjectWorkspaceSnapshot.ProjectFile file) { }
        @Override public void removeFromPolicy(ProjectWorkspaceSnapshot.ProjectFile file) { }
        @Override public boolean canOpenFile(ProjectWorkspaceSnapshot.ProjectFile file) { return true; }
        @Override public boolean canEditMembership() { return membershipEditable; }
    }
}
