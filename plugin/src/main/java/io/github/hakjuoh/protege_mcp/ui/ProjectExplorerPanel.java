package io.github.hakjuoh.protege_mcp.ui;

import org.semanticweb.owlapi.model.OWLOntology;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/** Collapsible project filesystem and ontology navigator shown beside the assistant. */
final class ProjectExplorerPanel extends JPanel {

    interface Listener {
        void closeRequested();
        void activateOntology(OWLOntology ontology);
        void saveOntologyIntoProject(OWLOntology ontology, Path projectRoot);
        void openFile(ProjectWorkspaceSnapshot.ProjectFile file);
        void addToPolicy(ProjectWorkspaceSnapshot.ProjectFile file);
        void removeFromPolicy(ProjectWorkspaceSnapshot.ProjectFile file);
        boolean canOpenFile(ProjectWorkspaceSnapshot.ProjectFile file);
        boolean canEditMembership();
    }

    private final JTree tree = new JTree(new DefaultMutableTreeNode("Loading…"));
    private final Listener listener;
    private final ExplorerRenderer renderer;
    private ProjectWorkspaceSnapshot snapshot = ProjectWorkspaceSnapshot.empty("No saved project");
    private boolean initialized;

    ProjectExplorerPanel(Icon ontologyIcon, Listener listener) {
        this(ontologyIcon, ignored -> ontologyIcon, listener);
    }

    ProjectExplorerPanel(Icon ontologyIcon, Function<OWLOntology, Icon> ontologyIconProvider,
            Listener listener) {
        this.listener = listener;
        this.renderer = new ExplorerRenderer(ontologyIcon, ontologyIconProvider);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder());
        setMinimumSize(new Dimension(210, 120));
        setPreferredSize(new Dimension(285, 420));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getAccessibleContext().setAccessibleName("Project files and ontologies");
        tree.getAccessibleContext().setAccessibleDescription(
                "Project filesystem and external file-backed or in-memory ontologies");
        tree.setCellRenderer(renderer);
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addMouseListener(new MouseHandler());
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate-node");
        tree.getActionMap().put("activate-node", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                activateSelected();
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F10,
                java.awt.event.InputEvent.SHIFT_DOWN_MASK), "show-node-menu");
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0),
                "show-node-menu");
        tree.getActionMap().put("show-node-menu", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                showContextMenuForSelection();
            }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName("Project Explorer");
    }

    /** Compatibility seam for older wiring tests; policy controls now live in the shared top bar. */
    ProjectExplorerPanel(JButton ignoredStatus, JButton ignoredSync, Listener listener) {
        this((Icon) null, listener);
    }

    void showSnapshot(ProjectWorkspaceSnapshot snapshot) {
        this.snapshot = snapshot;
        renderer.setActiveOntology(snapshot.activeOntology());
        Set<String> expanded = initialized ? expandedNodeIds() : Set.of();
        String selected = selectedNodeId();
        DefaultMutableTreeNode hiddenRoot = node("root", "root", NodeType.ROOT, null);

        DefaultMutableTreeNode files = node("files", snapshot.projectName(), NodeType.PROJECT, null);
        for (ProjectWorkspaceSnapshot.ProjectFile entry : snapshot.files()) {
            files.add(fileNode(entry));
        }
        if (snapshot.filesTruncated()) {
            files.add(node("files:truncated", "More files omitted (20,000 entry limit)",
                    NodeType.NOTICE, null));
        }
        if (snapshot.watcherTruncated()) {
            files.add(node("watcher:truncated",
                    "Live updates limited (10,000 directory watch limit)",
                    NodeType.NOTICE, null));
        }
        hiddenRoot.add(files);

        DefaultMutableTreeNode external = node("external", "External Ontologies", NodeType.SECTION, null);
        DefaultMutableTreeNode externalFiles = node("external:file", "File", NodeType.SECTION, null);
        DefaultMutableTreeNode memory = node("external:memory", "Memory", NodeType.SECTION, null);
        DefaultMutableTreeNode unavailable = node("external:unavailable", "Unavailable",
                NodeType.SECTION, null);
        snapshot.externalOntologies().forEach(entry -> {
            if (entry.hasLocalDocument()) externalFiles.add(ontologyNode(entry, true));
            else if (entry.ontology() != null) memory.add(ontologyNode(entry, false));
            else unavailable.add(ontologyNode(entry, false));
        });
        external.add(externalFiles);
        external.add(memory);
        if (unavailable.getChildCount() > 0) external.add(unavailable);
        hiddenRoot.add(external);

        tree.setModel(new DefaultTreeModel(hiddenRoot));
        if (initialized) restoreExpanded(expanded);
        else expandTopLevel(hiddenRoot);
        initialized = true;
        restoreSelection(selected);
    }

    private void expandTopLevel(DefaultMutableTreeNode hiddenRoot) {
        for (int index = 0; index < hiddenRoot.getChildCount(); index++) {
            tree.expandPath(new TreePath(new Object[] {hiddenRoot, hiddenRoot.getChildAt(index)}));
        }
    }

    private DefaultMutableTreeNode fileNode(ProjectWorkspaceSnapshot.ProjectFile entry) {
        DefaultMutableTreeNode node = node("file:" + entry.path(), entry.name(),
                entry.directory() ? NodeType.DIRECTORY : NodeType.FILE, entry);
        entry.children().forEach(child -> node.add(fileNode(child)));
        return node;
    }

    private DefaultMutableTreeNode ontologyNode(ProjectWorkspaceSnapshot.OntologyEntry entry,
            boolean localExternal) {
        String suffix = switch (entry.location()) {
            case UNRESOLVED -> " · import not resolved";
            case IN_MEMORY -> " · in memory";
            default -> "";
        };
        DefaultMutableTreeNode node = node("ontology:" + entry.ontologyIri() + ":"
                + System.identityHashCode(entry.ontology()),
                (localExternal ? entry.localDisplayName() : entry.displayName()) + suffix,
                NodeType.ONTOLOGY, entry);
        if (entry.documents().size() > 1) {
            entry.documents().forEach(document -> node.add(node(
                    "document:" + document.location(), document.location(), NodeType.DOCUMENT,
                    document)));
        }
        return node;
    }

    private void activateSelected() {
        ExplorerNode selected = selectedNode();
        if (selected == null) return;
        if (selected.type() == NodeType.PROJECT || selected.type() == NodeType.SECTION
                || selected.type() == NodeType.DIRECTORY) {
            TreePath path = tree.getSelectionPath();
            if (tree.isExpanded(path)) tree.collapsePath(path); else tree.expandPath(path);
            return;
        }
        if (selected.payload() instanceof ProjectWorkspaceSnapshot.OntologyEntry ontology) {
            listener.activateOntology(ontology.ontology());
        } else if (selected.payload() instanceof ProjectWorkspaceSnapshot.ProjectFile file) {
            if (file.loadedOntology() != null) listener.activateOntology(file.loadedOntology());
            else if (!file.directory() && listener.canOpenFile(file)) listener.openFile(file);
            else if (!file.directory()) java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    private void showContextMenu(MouseEvent event) {
        TreePath path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null) return;
        tree.setSelectionPath(path);
        ExplorerNode selected = selectedNode();
        if (selected == null) return;
        JPopupMenu menu = contextMenu(selected);
        if (menu.getComponentCount() > 0) menu.show(tree, event.getX(), event.getY());
    }

    private JPopupMenu contextMenu(ExplorerNode selected) {
        JPopupMenu menu = new JPopupMenu();
        if (selected.payload() instanceof ProjectWorkspaceSnapshot.OntologyEntry ontology) {
            JMenuItem activate = new JMenuItem("Activate ontology");
            activate.setEnabled(ontology.ontology() != null);
            activate.addActionListener(ignored -> activateSelected());
            menu.add(activate);
            if ((ontology.location() == ProjectWorkspaceSnapshot.OntologyLocation.EXTERNAL
                    || ontology.location() == ProjectWorkspaceSnapshot.OntologyLocation.IN_MEMORY)
                    && ontology.ontology() != null && snapshot.projectRoot() != null) {
                JMenuItem save = new JMenuItem("Save into project…");
                save.addActionListener(ignored -> listener.saveOntologyIntoProject(
                        ontology.ontology(), snapshot.projectRoot()));
                menu.add(save);
            }
            return menu;
        }
        if (!(selected.payload() instanceof ProjectWorkspaceSnapshot.ProjectFile file)
                || file.directory()) return menu;
        JMenuItem activate = new JMenuItem(file.loadedOntology() == null ? "Open file" : "Activate ontology");
        activate.setEnabled(file.loadedOntology() != null || listener.canOpenFile(file));
        activate.addActionListener(ignored -> activateSelected());
        menu.add(activate);
        menu.addSeparator();
        JMenuItem membership = new JMenuItem(file.workspaceMember()
                ? "Remove from policy" : "Add to policy");
        membership.setEnabled(file.kind() != ProjectWorkspaceSnapshot.FileKind.SYMLINK
                && listener.canEditMembership());
        if (!membership.isEnabled()) {
            membership.setToolTipText("A ready, valid Project Policy v3 is required");
        }
        membership.addActionListener(ignored -> {
            if (file.workspaceMember()) listener.removeFromPolicy(file);
            else listener.addToPolicy(file);
        });
        menu.add(membership);
        return menu;
    }

    private void showContextMenuForSelection() {
        TreePath path = tree.getSelectionPath();
        ExplorerNode selected = value(path);
        if (selected == null) return;
        JPopupMenu menu = contextMenu(selected);
        if (menu.getComponentCount() == 0) return;
        java.awt.Rectangle bounds = tree.getPathBounds(path);
        int x = bounds == null ? 0 : bounds.x + 12;
        int y = bounds == null ? 0 : bounds.y + bounds.height;
        menu.show(tree, x, y);
    }

    private Set<String> expandedNodeIds() {
        Set<String> ids = new HashSet<>();
        for (int row = 0; row < tree.getRowCount(); row++) {
            if (tree.isExpanded(row)) {
                TreePath path = tree.getPathForRow(row);
                ExplorerNode node = value(path);
                if (node != null) ids.add(node.id());
            }
        }
        return ids;
    }

    private String selectedNodeId() {
        ExplorerNode node = value(tree.getSelectionPath());
        return node == null ? null : node.id();
    }

    private void restoreExpanded(Set<String> ids) {
        walkPaths(path -> {
            ExplorerNode node = value(path);
            if (node != null && ids.contains(node.id())) tree.expandPath(path);
        });
    }

    private void restoreSelection(String id) {
        if (id == null) return;
        walkPaths(path -> {
            ExplorerNode node = value(path);
            if (node != null && id.equals(node.id())) tree.setSelectionPath(path);
        });
    }

    private void walkPaths(java.util.function.Consumer<TreePath> consumer) {
        Object root = tree.getModel().getRoot();
        walk(new TreePath(root), consumer);
    }

    private void walk(TreePath path, java.util.function.Consumer<TreePath> consumer) {
        consumer.accept(path);
        Object node = path.getLastPathComponent();
        int count = tree.getModel().getChildCount(node);
        for (int i = 0; i < count; i++) {
            walk(path.pathByAddingChild(tree.getModel().getChild(node, i)), consumer);
        }
    }

    private ExplorerNode selectedNode() { return value(tree.getSelectionPath()); }

    private static ExplorerNode value(TreePath path) {
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof ExplorerNode value)) return null;
        return value;
    }

    private static DefaultMutableTreeNode node(String id, String label, NodeType type, Object payload) {
        return new DefaultMutableTreeNode(new ExplorerNode(id, label, type, payload));
    }

    /** Returns a theme-derived accent that maintains WCAG AA contrast with the tree. */
    static Color activeOntologyColor(Color background) {
        Color actualBackground = background == null ? Color.WHITE : background;
        Color themeAccent = UIManager.getColor("Component.accentColor");
        if (themeAccent != null && contrast(themeAccent, actualBackground) >= 4.5) {
            return themeAccent;
        }
        Color fallback = luminance(actualBackground) > 0.45
                ? new Color(0x0057B7) : new Color(0x73B9FF);
        if (contrast(fallback, actualBackground) >= 4.5) return fallback;
        return contrast(Color.BLACK, actualBackground) >= contrast(Color.WHITE, actualBackground)
                ? Color.BLACK : Color.WHITE;
    }

    private static double contrast(Color first, Color second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed())
                + 0.7152 * linear(color.getGreen())
                + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double normalized = channel / 255.0;
        return normalized <= 0.04045 ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }

    private enum NodeType { ROOT, PROJECT, SECTION, DIRECTORY, FILE, ONTOLOGY, DOCUMENT, NOTICE }
    private record ExplorerNode(String id, String label, NodeType type, Object payload) {
        @Override public String toString() { return label; }
    }

    private final class MouseHandler extends MouseAdapter {
        @Override public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                activateSelected();
            }
        }
        @Override public void mousePressed(MouseEvent event) { if (event.isPopupTrigger()) showContextMenu(event); }
        @Override public void mouseReleased(MouseEvent event) { if (event.isPopupTrigger()) showContextMenu(event); }
    }

    private static final class ExplorerRenderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;
        private final Icon fileIcon = UIManager.getIcon("FileView.fileIcon");
        private final Icon directoryIcon = UIManager.getIcon("Tree.closedIcon");
        private final Icon fallbackOntologyIcon;
        private final Function<OWLOntology, Icon> ontologyIconProvider;
        private OWLOntology activeOntology;

        private ExplorerRenderer(Icon ontologyIcon,
                Function<OWLOntology, Icon> ontologyIconProvider) {
            this.fallbackOntologyIcon = ontologyIcon != null ? ontologyIcon : fileIcon;
            this.ontologyIconProvider = ontologyIconProvider;
        }

        private void setActiveOntology(OWLOntology activeOntology) {
            this.activeOntology = activeOntology;
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            if (!(value instanceof DefaultMutableTreeNode treeNode)
                    || !(treeNode.getUserObject() instanceof ExplorerNode node)) return this;
            setText(node.label());
            setToolTipText(tooltip(node));
            if (node.type() == NodeType.DIRECTORY || node.type() == NodeType.PROJECT
                    || node.type() == NodeType.SECTION) setIcon(directoryIcon);
            else if (node.type() == NodeType.ONTOLOGY
                    || node.payload() instanceof ProjectWorkspaceSnapshot.ProjectFile file
                            && file.kind() == ProjectWorkspaceSnapshot.FileKind.ONTOLOGY) {
                setIcon(ontologyIcon(node));
            } else {
                setIcon(fileIcon);
            }
            Font base = tree.getFont();
            if (node.payload() instanceof ProjectWorkspaceSnapshot.ProjectFile file
                    && file.workspaceMember()) {
                setFont(new Font(base.getName(), Font.BOLD, base.getSize()));
            } else {
                setFont(new Font(base.getName(), Font.PLAIN, base.getSize()));
            }
            boolean active = isActiveOntology(node);
            if (!selected && active) setForeground(activeOntologyColor(tree.getBackground()));
            getAccessibleContext().setAccessibleDescription(active
                    ? "Active ontology: " + node.label() : null);
            if (active) setToolTipText(activeTooltip(node));
            return this;
        }

        private static String activeTooltip(ExplorerNode node) {
            String detail = tooltip(node);
            if (detail == null || detail.isBlank()) return "Active ontology";
            if (detail.startsWith("<html>")) {
                return "<html><b>Active ontology</b><br>" + detail.substring(6);
            }
            return "Active ontology — " + detail;
        }

        private Icon ontologyIcon(ExplorerNode node) {
            OWLOntology ontology = ontology(node);
            Icon icon = ontology == null ? null : ontologyIconProvider.apply(ontology);
            return icon != null ? icon : fallbackOntologyIcon;
        }

        private boolean isActiveOntology(ExplorerNode node) {
            OWLOntology ontology = ontology(node);
            return activeOntology != null && activeOntology.equals(ontology);
        }

        private static OWLOntology ontology(ExplorerNode node) {
            if (node.payload() instanceof ProjectWorkspaceSnapshot.ProjectFile file) {
                return file.loadedOntology();
            }
            if (node.payload() instanceof ProjectWorkspaceSnapshot.OntologyEntry entry) {
                return entry.ontology();
            }
            return null;
        }

        private static String tooltip(ExplorerNode node) {
            if (node.payload() instanceof ProjectWorkspaceSnapshot.ProjectFile file) {
                if (file.kind() == ProjectWorkspaceSnapshot.FileKind.SYMLINK) {
                    return file.path() + " — symbolic links are visible but not actionable";
                }
                return file.path().toString() + (file.workspaceMember()
                        ? " — listed in policy workspace" : " — not listed in policy workspace");
            }
            if (node.payload() instanceof ProjectWorkspaceSnapshot.OntologyEntry ontology) {
                String iri = ontology.ontologyIri() == null ? "Anonymous ontology" : ontology.ontologyIri();
                if (ontology.documents().isEmpty()) return iri;
                return "<html>" + ChatView.escapeHtml(iri) + "<br>Documents:<br>"
                        + ontology.documents().stream()
                                .map(document -> "&nbsp;• " + ChatView.escapeHtml(document.location()))
                                .collect(java.util.stream.Collectors.joining("<br>")) + "</html>";
            }
            return null;
        }
    }
}
