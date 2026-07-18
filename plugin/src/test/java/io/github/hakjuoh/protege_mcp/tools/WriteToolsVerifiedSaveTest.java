package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.IOListenerManager;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * End-to-end tests for {@code save_ontology}'s verified path ({@code verifiedSave}): capture hop →
 * temporary prepare → final install hop. The controller is a real {@link McpServerController}, so the
 * read-only / confirm-writes gates read live Protégé preferences — saved and restored around each test.
 * The model manager is a recording proxy (also implementing {@link IOListenerManager}) over a real
 * in-memory ontology, so the workspace notifications the save must emit are observable headless.
 */
class WriteToolsVerifiedSaveTest {

    @TempDir
    Path temp;

    private Preferences prefs;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void savePreferences() {
        prefs = McpConfig.prefs();
        savedReadOnly = prefs.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restorePreferences() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
    }

    // ------------------------------------------------------------------ fixtures

    /** What the verified-save final hop told the workspace, in call order. */
    private static final class Recorded {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        volatile OWLOntology cleaned;
    }

    /**
     * A manager proxy that behaves like {@link FakeModelManager} for the model reads the save pipeline
     * needs, records the workspace notifications ({@code setClean}, {@code fireEvent}, the
     * {@link IOListenerManager} save events), optionally acts as a beforeSave IOListener (the
     * ontology-mutating plugin pattern), and optionally runs a probe when the policy-capture hop asks
     * for the (unavailable-headless) reasoner manager — the one call unique to that hop.
     */
    private static OWLModelManager recordingManager(OWLOntology ontology, Recorded recorded,
            Runnable reasonerManagerProbe, Runnable beforeSaveListener) {
        OWLModelManager fake = FakeModelManager.over(ontology);
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setClean":
                    recorded.cleaned = (OWLOntology) args[0];
                    recorded.events.add("setClean");
                    return null;
                case "fireEvent":
                    recorded.events.add("fireEvent:" + args[0]);
                    return null;
                case "fireBeforeSaveEvent":
                    recorded.events.add("beforeSave:" + args[1]);
                    if (beforeSaveListener != null) {
                        beforeSaveListener.run();
                    }
                    return null;
                case "fireAfterSaveEvent":
                    recorded.events.add("afterSave:" + args[1]);
                    return null;
                case "getOWLReasonerManager":
                    if (reasonerManagerProbe != null) {
                        reasonerManagerProbe.run();
                    }
                    throw new UnsupportedOperationException("headless: no reasoner manager");
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    try {
                        return method.invoke(fake, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
            }
        };
        return (OWLModelManager) Proxy.newProxyInstance(
                WriteToolsVerifiedSaveTest.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class, IOListenerManager.class },
                handler);
    }

    private static OWLOntology ontology() throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/verified-save"));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/verified-save#A"))));
        return ontology;
    }

    /** Invoke the private {@code verifiedSave(ToolContext, String, String, boolean, boolean, String)}. */
    private static CallToolResult verifiedSave(ToolContext ctx, String path) throws Throwable {
        Method m = WriteTools.class.getDeclaredMethod("verifiedSave", ToolContext.class, String.class,
                String.class, boolean.class, boolean.class, String.class);
        m.setAccessible(true);
        try {
            return (CallToolResult) m.invoke(null, ctx, "save the active ontology to " + path, path,
                    false, false, "warn");
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult r) {
        return (Map<String, Object>) r.structuredContent();
    }

    // ------------------------------------------------------------------ save notifications

    @Test
    void successfulVerifiedSaveFiresTheSaveNotificationsInProtegeOrder() throws Throwable {
        OWLOntology ontology = ontology();
        Recorded recorded = new Recorded();
        // A beforeSave IOListener that stamps the ontology (a real plugin pattern). It fires before
        // the snapshot/fingerprint, so the stamp must be serialized into the installed artifact and
        // covered by the revision check — not silently dropped from a stale snapshot.
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLAxiom stamp = df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/verified-save#StampedByListener")));
        Runnable stampOnBeforeSave = () -> ontology.getOWLOntologyManager().addAxiom(ontology, stamp);
        OWLModelManager mm = recordingManager(ontology, recorded, null, stampOnBeforeSave);
        ToolContext ctx = new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
        Path target = temp.resolve("saved.ttl");

        CallToolResult result = verifiedSave(ctx, target.toString());

        Map<String, Object> m = structured(result);
        assertEquals(Boolean.TRUE, m.get("saved"), "verified save succeeds: " + m);
        assertTrue(Files.readString(target).contains("http://example.org/verified-save"));
        assertTrue(Files.readString(target).contains("StampedByListener"),
                "a beforeSave listener mutation is snapshotted, verified and installed");
        assertSame(ontology, recorded.cleaned, "the active ontology is marked clean");
        String uri = target.toFile().toURI().toString();
        assertEquals(List.of("beforeSave:" + uri, "setClean", "fireEvent:ONTOLOGY_SAVED",
                        "afterSave:" + uri), recorded.events,
                "notification order mirrors Protégé's own save path");
    }

    // ------------------------------------------------------------------ read-only re-check

    @Test
    void readOnlyEnabledWhileTheConfirmationDialogIsOpenRefusesBeforeTouchingTheDisk() throws Throwable {
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, true);
        OWLOntology ontology = ontology();
        Recorded recorded = new Recorded();
        OWLModelManager mm = recordingManager(ontology, recorded, null, null);
        Path target = temp.resolve("locked.ttl");
        Files.writeString(target, "previous-release");
        // The dialog is an unbounded human interaction: read-only can be switched on while it is
        // open. Approving the write AFTER the flip must still be refused by the final hop.
        WriteConfirmer flipsReadOnlyThenApproves = summary -> {
            prefs.putBoolean(McpConfig.KEY_READ_ONLY, true);
            return true;
        };
        ToolContext ctx = new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)), flipsReadOnlyThenApproves);

        CallToolResult result = verifiedSave(ctx, target.toString());

        assertEquals(Boolean.TRUE, result.isError(), "flipping read-only mid-save refuses the write");
        assertTrue(String.valueOf(structured(result).get("error")).toLowerCase(Locale.ROOT)
                .contains("read-only"), "the refusal names read-only mode");
        assertEquals("previous-release", Files.readString(target), "the artifact was not replaced");
        assertNull(recorded.cleaned, "the ontology must not be marked clean");
        // beforeSave fires in the capture hop — before the refusal is knowable — mirroring plain
        // save, where listeners hear beforeSave even when the write then throws. What must NOT
        // fire after a refusal: clean, ONTOLOGY_SAVED, afterSave.
        assertEquals(List.of("beforeSave:" + target.toFile().toURI()), recorded.events,
                "a refused save emits no clean/saved/afterSave notifications");
        try (var files = Files.list(temp)) {
            assertEquals(Set.of("locked.ttl"),
                    files.map(p -> p.getFileName().toString()).collect(Collectors.toSet()),
                    "no temporary artifact leaks after the refusal");
        }
    }

    // ------------------------------------------------------------------ capture-hop wait bound

    @Test
    void captureHopUsesTheSaveBoundNotTheDefaultEdtBound() throws Throwable {
        OWLOntology ontology = ontology();
        Recorded recorded = new Recorded();
        // The reasoner-manager probe fires on the EDT during the policy hop — strictly before the
        // capture hop is enqueued — so the sleeper it posts lands between the two on the FIFO event
        // queue, stalling the capture hop past the default bound but well inside the save bound.
        AtomicBoolean posted = new AtomicBoolean();
        Runnable postSleeperOnce = () -> {
            if (posted.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        Thread.sleep(3_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        };
        OWLModelManager mm = recordingManager(ontology, recorded, postSleeperOnce, null);
        // Real Swing marshalling with a deliberately tiny DEFAULT bound: only hops given the explicit
        // save bound survive the busy spell.
        OntologyAccess edt = new OntologyAccess(HeadlessAccess.over(mm).getEditorKit(), 500L);
        ToolContext ctx = new ToolContext(edt, new McpServerController(new OntologyAccess(null)));
        // Warm up the EDT and the policy-capture classes so the one remaining default-bound hop
        // (the policy capture itself) stays far under its 500 ms bound even on a cold JVM.
        SwingUtilities.invokeAndWait(() -> { });
        ProjectPolicyTools.capture(FakeModelManager.over(ontology));
        Path target = temp.resolve("big.ttl");

        CallToolResult result = verifiedSave(ctx, target.toString());

        Map<String, Object> m = structured(result);
        assertEquals(Boolean.TRUE, m.get("saved"),
                "the capture hop must wait out an EDT busy spell longer than the default bound: " + m);
        assertTrue(Files.readString(target).contains("http://example.org/verified-save"));
    }
}
