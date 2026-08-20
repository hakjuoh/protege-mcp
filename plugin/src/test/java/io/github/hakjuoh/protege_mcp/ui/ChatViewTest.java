package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.protege_mcp.chat.ChatUsage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hakjuoh.protege_mcp.tools.ProjectPolicyRegistrySync;

/** Headless tests for the UI-only responsibilities left in {@link ChatView}. */
class ChatViewTest {

    @Test
    void externalOntologySaveTargetCannotEscapeProject(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path temp) throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createDirectory(temp.resolve("project"));
        java.nio.file.Path outside = java.nio.file.Files.createDirectory(temp.resolve("outside"));
        assertEquals(root.toRealPath().resolve("nested/ontology.owl"), ChatView.secureProjectSavePath(
                root, root.resolve("nested/ontology.owl")));
        assertThrows(IllegalArgumentException.class,
                () -> ChatView.secureProjectSavePath(root, outside.resolve("ontology.owl")));

        java.nio.file.Path link = root.resolve("linked-outside");
        java.nio.file.Files.createSymbolicLink(link, outside);
        assertThrows(IllegalArgumentException.class,
                () -> ChatView.secureProjectSavePath(root, link.resolve("ontology.owl")));
    }

    @Test
    void formatUsageDelegatesToTheSharedChatFormatter() throws Exception {
        Method method = ChatView.class.getDeclaredMethod("formatUsage", ChatUsage.class);
        method.setAccessible(true);

        assertEquals(
                "tokens: 100 in / 40 out   ", method.invoke(null, new ChatUsage(100, 40, 0, null)));
        assertEquals("tokens: ? in / ? out   ", method.invoke(null, ChatUsage.unknown()));
        assertTrue(
                ((String) method.invoke(null, new ChatUsage(100, 40, 25, 0.12345)))
                        .contains("$0.1235"));
        assertFalse(
                ((String) method.invoke(null, new ChatUsage(100, 40, 0, null))).contains("cached"));
    }

    @Test
    void attachmentControllerWiringReportsErrorsInTranscript() throws Exception {
        onEdt(
                () -> {
                    ChatTranscriptPane transcript = new ChatTranscriptPane(() -> false);
                    ChatView view = bareInstance();
                    setField(view, "transcript", transcript);
                    ChatAttachmentController controller =
                            view.buildAttachmentController(new javax.swing.JTextArea());
                    try {
                        assertFalse(
                                controller.attachFiles(
                                        List.of(
                                                new java.io.File(
                                                        "missing-attachment-for-chat-view-test"))));
                        assertTrue(
                                transcript.getText().contains("Cannot attach non-file path:"));
                    } finally {
                        controller.dispose();
                    }
                    return null;
                });
    }

    @Test
    void turnControllerHostAdapterUpdatesSwingComponentsOnTheEdt() throws Exception {
        onEdt(
                () -> {
                    ChatView view = bareInstance();
                    ChatTranscriptPane transcript = new ChatTranscriptPane(() -> false);
                    javax.swing.JLabel usage = new javax.swing.JLabel();
                    javax.swing.JLabel working = new javax.swing.JLabel();
                    setField(view, "transcript", transcript);
                    setField(view, "usageLabel", usage);
                    setField(view, "workingLabel", working);

                    Method build = ChatView.class.getDeclaredMethod("buildTurnController");
                    build.setAccessible(true);
                    ChatTurnController controller = (ChatTurnController) build.invoke(view);
                    Field hostField = ChatTurnController.class.getDeclaredField("host");
                    hostField.setAccessible(true);
                    ChatTurnController.Host host =
                            (ChatTurnController.Host) hostField.get(controller);

                    host.append(ChatTranscriptPane.Kind.SYSTEM, "adapter\n");
                    host.showUsage(new ChatUsage(3, 2, 0, null), false);
                    host.showWorkingSeconds(4);

                    assertTrue(transcript.getText().contains("adapter"));
                    assertEquals("tokens: 3 in / 2 out   ", usage.getText());
                    assertTrue(working.getText().contains("4s"));
                    controller.dispose();
                    return null;
                });
    }

    @Test
    void formatOntologyLabelFormatsNamedAndAnonymousOntologies() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager man = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology named = man.createOntology(org.semanticweb.owlapi.model.IRI.create("https://example.org/my-ontology"));
        assertEquals("[Unsaved] my-ontology · In-memory", ChatView.formatOntologyLabel(null, named));

        org.semanticweb.owlapi.model.OWLOntology anon = man.createOntology();
        assertEquals("[Unsaved] Anonymous Ontology · In-memory", ChatView.formatOntologyLabel(null, anon));
        assertEquals("None", ChatView.formatOntologyLabel(null, null));
    }

    @Test
    void formatPolicyBadgeReportsNoneWhenNoPolicyLoaded() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager man = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology ont = man.createOntology(org.semanticweb.owlapi.model.IRI.create("https://example.org/test"));
        assertEquals("Policy: None", ChatView.formatPolicyBadge(null, ont));
        assertEquals("Policy: None", ChatView.formatPolicyBadge(
                (org.protege.editor.owl.model.OWLModelManager) null,
                (org.semanticweb.owlapi.model.OWLOntology) null));
    }

    @Test
    void escapeHtmlNeutralizesDynamicTooltipMarkup() {
        assertEquals("&lt;img src=&quot;x&quot;&gt;&amp;&#39;",
                ChatView.escapeHtml("<img src=\"x\">&'"));
    }

    @Test
    void registryPreviewExplainsReplacementAndSkippedBindings() {
        ProjectPolicyRegistrySync.Preview preview = new ProjectPolicyRegistrySync.Preview(
                true, 2, List.of(java.util.Map.of("id", "ebi")),
                List.of("bioportal (OntoPortal requires a credential binding)"));

        java.nio.file.Path target = java.nio.file.Path.of("/project/.protege-mcp/project.yaml");
        String message = ChatView.formatPolicySyncPreview(preview, target);
        assertTrue(message.contains("Current policy providers: 2"));
        assertTrue(message.contains("Configured providers to apply: 1"));
        assertTrue(message.contains("bioportal"));
        assertTrue(message.contains(target.toString()));
        assertTrue(message.contains("Apart from a v1/v2 upgrade's version and workspace fields"));
        assertTrue(message.contains("other policy sections are not changed"));
    }

    @Test
    void policySyncButtonIsDisabledUntilPolicySnapshotIsReady() throws Exception {
        onEdt(() -> {
            ChatView view = bareInstance();
            javax.swing.JButton button = new javax.swing.JButton();
            setField(view, "policySyncButton", button);

            Method update = ChatView.class.getDeclaredMethod("updatePolicySyncButton");
            update.setAccessible(true);
            update.invoke(view);

            assertFalse(button.isEnabled());
            assertEquals("", button.getText());
            assertNotNull(button.getIcon());
            return null;
        });
    }

    @Test
    void formatPolicyBadgeFormatsSingularAndPluralIssues(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path projectDir = tempDir.resolve("my-proj");
        java.nio.file.Files.createDirectories(projectDir.resolve(".protege-mcp"));
        java.nio.file.Path doc = projectDir.resolve("ontology.ttl");
        java.nio.file.Files.writeString(doc, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<https://example.org/ontology> a owl:Ontology .\n");

        // Write invalid policy with 1 issue (missing version)
        java.nio.file.Files.writeString(projectDir.resolve(".protege-mcp/project.yaml"), "external_terms:\n  providers: []\n");

        org.semanticweb.owlapi.model.OWLOntologyManager man = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology ont = man.loadOntologyFromOntologyDocument(doc.toFile());

        org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo info =
                (org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo) java.lang.reflect.Proxy.newProxyInstance(
                        ChatViewTest.class.getClassLoader(),
                        new Class<?>[] { org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getReasonerName" -> "HermiT";
                            case "getReasonerId" -> "HermiT";
                            default -> null;
                        });
        org.protege.editor.owl.model.inference.OWLReasonerManager rm = (org.protege.editor.owl.model.inference.OWLReasonerManager) java.lang.reflect.Proxy.newProxyInstance(
                org.protege.editor.owl.model.inference.OWLReasonerManager.class.getClassLoader(),
                new Class<?>[] { org.protege.editor.owl.model.inference.OWLReasonerManager.class },
                (proxy, method, args) -> {
                    if ("getInstalledReasonerFactories".equals(method.getName())) {
                        return java.util.Set.of(info);
                    }
                    return null;
                });

        org.protege.editor.owl.model.OWLModelManager mm = (org.protege.editor.owl.model.OWLModelManager) java.lang.reflect.Proxy.newProxyInstance(
                org.protege.editor.owl.model.OWLModelManager.class.getClassLoader(),
                new Class<?>[] { org.protege.editor.owl.model.OWLModelManager.class },
                (proxy, method, args) -> {
                    if ("getOWLOntologyManager".equals(method.getName())) {
                        return man;
                    }
                    if ("getActiveOntology".equals(method.getName())) {
                        return ont;
                    }
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return rm;
                    }
                    return null;
                });

        String badge = ChatView.formatPolicyBadge(mm, ont);
        assertEquals("Policy: Invalid (1 issue)", badge);

        // Write valid v1 policy fixture
        io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.writePolicy(
                projectDir.resolve(".protege-mcp/project.yaml"),
                io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.minimalPolicy("my-proj", "https://example.org/ontology")
                        + "reasoning:\n  reasoner: HermiT\n");
        assertEquals("Policy: v1 (Valid)", ChatView.formatPolicyBadge(mm, ont));
        io.github.hakjuoh.protege_mcp.policy.ProjectPolicy valid =
                io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                        null, doc, "https://example.org/ontology", List.of("HermiT"));
        assertEquals("Policy: v1 (Warning: 1)", ChatView.formatPolicyBadgeWithIssues(valid,
                List.of(new io.github.hakjuoh.protege_mcp.policy.PolicyIssue("warning",
                        "provider_origin_unbound", "external_terms.providers[0]",
                        "Registry binding is missing"))));

        onEdt(() -> {
            ChatView view = bareInstance();
            javax.swing.JButton button = new javax.swing.JButton();
            setField(view, "policySyncButton", button);
            setField(view, "policySnapshot", policySnapshot(valid, false));
            Method update = ChatView.class.getDeclaredMethod("updatePolicySyncButton");
            update.setAccessible(true);
            update.invoke(view);

            assertFalse(button.isEnabled());
            assertEquals("", button.getText());
            assertNotNull(button.getIcon());
            assertTrue(button.getToolTipText().contains("already matches"));

            setField(view, "policySnapshot", policySnapshot(valid, false,
                    List.of("private-ols (not applicable)")));
            update.invoke(view);
            assertFalse(button.isEnabled());
            assertTrue(button.getToolTipText().contains("1 saved Preference binding(s) were skipped"));

            setField(view, "policySnapshot", policySnapshot(valid, true));
            update.invoke(view);
            assertTrue(button.isEnabled());
            return null;
        });
    }

    @Test
    void policySnapshotGuardRejectsSelectionChangeAndDisposedView() throws Exception {
        ChatView view = bareInstance();
        java.util.concurrent.ExecutorService loader =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            setField(view, "policyLoader", loader);
            setField(view, "policyRefreshGeneration", new java.util.concurrent.atomic.AtomicLong());
            Object snapshot = policySnapshot(
                    io.github.hakjuoh.protege_mcp.policy.ProjectPolicy.notFound(), true);
            Class<?> snapshotType = snapshot.getClass();
            setField(view, "policySnapshot", snapshot);
            Method guard = ChatView.class.getDeclaredMethod(
                    "isCurrentPolicySnapshot", snapshotType);
            guard.setAccessible(true);

            assertTrue((Boolean) guard.invoke(view, snapshot));
            Method invalidate = ChatView.class.getDeclaredMethod("invalidatePolicySelection");
            invalidate.setAccessible(true);
            invalidate.invoke(view);
            assertFalse((Boolean) guard.invoke(view, snapshot));
            setField(view, "policySnapshot", snapshot);
            setField(view, "disposed", true);
            assertFalse((Boolean) guard.invoke(view, snapshot));
        } finally {
            loader.shutdownNow();
        }
    }

    @Test
    void responsiveTopBarWrapsToTwoRowsWhenNarrow() throws Exception {
        onEdt(() -> {
            javax.swing.JPanel left = new javax.swing.JPanel();
            left.setPreferredSize(new java.awt.Dimension(300, 24));
            javax.swing.JPanel right = new javax.swing.JPanel();
            right.setPreferredSize(new java.awt.Dimension(200, 24));

            ChatView.ResponsiveTopBar bar = new ChatView.ResponsiveTopBar(left, right);
            assertFalse(bar.isWrapped(600), "wide container does not wrap");
            assertEquals(24, bar.getPreferredSize().height, "1 row height when wide");

            bar.setBounds(0, 0, 600, 24);
            bar.doLayout();
            assertEquals(0, left.getX());
            assertEquals(400, right.getX(), "right component aligned to right edge");

            assertTrue(bar.isWrapped(450), "narrow container wraps");
            bar.setBounds(0, 0, 450, 52);
            bar.doLayout();
            assertEquals(0, left.getX());
            assertEquals(0, left.getY());
            assertEquals(0, right.getX());
            assertEquals(28, right.getY(), "right component on second row below left");
            return null;
        });
    }

    private static ChatView bareInstance() throws Exception {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return (ChatView) allocate.invoke(unsafe, ChatView.class);
    }

    private static Object policySnapshot(
            io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy,
            boolean synchronizationRequired) throws Exception {
        return policySnapshot(policy, synchronizationRequired, List.of());
    }

    private static Object policySnapshot(
            io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy,
            boolean synchronizationRequired, List<String> omittedBindings) throws Exception {
        Class<?> snapshotType = java.util.Arrays.stream(ChatView.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("PolicyBadgeSnapshot"))
                .findFirst().orElseThrow();
        var constructor = snapshotType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(java.nio.file.Path.of("ontology.ttl"),
                java.nio.file.Path.of("ontology.ttl"),
                "https://example.org/test", policy, List.of(), synchronizationRequired,
                omittedBindings, List.of(), "Policy", "tooltip");
    }

    private static void setField(ChatView view, String name, Object value) throws Exception {
        Field field = ChatView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        result.set(action.call());
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                });
        if (failure.get() instanceof Exception exception) {
            throw exception;
        }
        if (failure.get() instanceof Error error) {
            throw error;
        }
        return result.get();
    }
}
