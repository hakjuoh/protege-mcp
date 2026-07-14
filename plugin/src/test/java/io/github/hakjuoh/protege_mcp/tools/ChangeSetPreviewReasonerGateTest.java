package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The no-policy preview gate's satisfiability contract: with a reasoner selected, an edit that makes a
 * class unsatisfiable must NOT preview as committable (the change-set counterpart of {@code
 * apply_changes verify=rollback} — this was the review repro where {@code A DisjointWith B} plus a new
 * {@code A SubClassOf B} yielded {@code gate=pass}), while a workspace with no reasoner keeps
 * previewing but must say so via {@code satisfiability_checked=false} instead of silently skipping the
 * check.
 */
class ChangeSetPreviewReasonerGateTest {

    private static final String NS = "https://example.org/gate#";

    private Preferences prefs;
    private String savedToken;
    private String savedReadOnly;
    private String savedConfirm;

    @BeforeEach
    void pinWritePrefs() {
        prefs = McpConfig.prefs();
        savedToken = prefs.getString(McpConfig.KEY_TOKEN, "");
        savedReadOnly = String.valueOf(prefs.getBoolean(McpConfig.KEY_READ_ONLY, false));
        savedConfirm = String.valueOf(prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restoreWritePrefs() {
        prefs.putString(McpConfig.KEY_TOKEN, savedToken);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, Boolean.parseBoolean(savedReadOnly));
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, Boolean.parseBoolean(savedConfirm));
    }

    @Test
    void unsatisfiabilityIntroducingEditIsNotCommittableUnderTheDefaultGate() throws Exception {
        OWLOntology ontology = disjointFixture();
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx,
                subclassOperation(NS + "A", NS + "B")));

        assertEquals(Boolean.FALSE, preview.get("committable"), preview::toString);
        assertEquals(Boolean.TRUE, preview.get("satisfiability_checked"), preview::toString);
        assertTrue(String.valueOf(preview.get("committable_reasons")).contains("preflight_fail"),
                preview::toString);
        assertEquals("fail", gate(preview), preview::toString);
        assertEquals("fail", reasonerStageStatus(preview), preview::toString);

        // The cached entry must also refuse a commit outright — a client replaying the id anyway
        // cannot land the unsatisfiability.
        Map<String, Object> commitArgs = new LinkedHashMap<>();
        commitArgs.put("change_set_id", preview.get("change_set_id"));
        commitArgs.put("expected_revision", preview.get("base_revision"));
        CallToolResult commit = ChangeSetTools.commit(ctx, commitArgs);
        @SuppressWarnings("unchecked")
        Map<String, Object> commitJson = (Map<String, Object>) commit.structuredContent();
        assertEquals(Boolean.FALSE, commitJson.get("committed"), commitJson::toString);
        assertEquals("change_set_not_committable", commitJson.get("error_code"));
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        assertFalse(ontology.containsAxiom(df.getOWLSubClassOfAxiom(
                        df.getOWLClass(IRI.create(NS + "A")), df.getOWLClass(IRI.create(NS + "B")))),
                "the refused edit must not reach the live ontology");
    }

    @Test
    void harmlessEditPassesTheReasonerVerifiedDefaultGate() throws Exception {
        OWLOntology ontology = disjointFixture();
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx,
                subclassOperation(NS + "C", NS + "A")));

        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertEquals(Boolean.TRUE, preview.get("satisfiability_checked"), preview::toString);
        assertEquals("pass", reasonerStageStatus(preview), preview::toString);
    }

    @Test
    void noReasonerWorkspaceStillPreviewsButReportsSatisfiabilityUnchecked() throws Exception {
        // A working reasoner manager with no current factory is Protégé's explicit None selection —
        // the default gate must not hard-error, but it must never imply satisfiability was verified.
        OWLOntology ontology = disjointFixture();
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx,
                subclassOperation(NS + "A", NS + "B")));

        assertEquals(Boolean.TRUE, preview.get("committable"),
                "without a reasoner the legacy stages still gate; satisfiability is not silently "
                        + "pretended: " + preview);
        assertEquals(Boolean.FALSE, preview.get("satisfiability_checked"), preview::toString);
        assertTrue(String.valueOf(preview.get("satisfiability_note")).contains("NOT checked"),
                preview::toString);
        // The stage is always SCHEDULED (so a reasoner selected after the probe still gates — the
        // inverse race), but with no selection anywhere it skips visibly instead of erroring —
        // which requires it to stay OPTIONAL for a genuine None selection.
        assertEquals("skipped", reasonerStageStatus(preview), preview::toString);
        assertFalse(requiredStages(preview).contains("reasoner"),
                "a genuine None selection must not require the stage: " + preview);
    }

    @Test
    void reasonerSelectedAfterTheProbeStillGatesTheUnsatisfiableEdit() throws Exception {
        // Inverse TOCTOU: nothing selected when the preview probed, HermiT selected by the time the
        // QC snapshot captured — the verdict must still gate.
        OWLOntology ontology = disjointFixture();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ProtegeOWLReasonerInfo real = hermitInfo();
        ToolContext ctx = context(managerWith(ontology, () ->
                calls.incrementAndGet() == 1 ? null : real));

        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx,
                subclassOperation(NS + "A", NS + "B")));

        assertEquals(Boolean.FALSE, preview.get("committable"),
                "a reasoner selected mid-preview must still veto the unsatisfiability: " + preview);
        assertEquals(Boolean.TRUE, preview.get("satisfiability_checked"), preview::toString);
        assertEquals("fail", reasonerStageStatus(preview), preview::toString);
    }

    @Test
    void brokenSelectedReasonerFailsTheGateClosed() throws Exception {
        // A selection EXISTS but its plugin metadata is unusable. Review repro: this used to be
        // silently treated like "no reasoner selected" (gate=pass, committable=true) — the one
        // verdict the user configured a reasoner for was dropped without an error.
        OWLOntology ontology = disjointFixture();
        ToolContext ctx = context(managerWith(ontology, brokenInfo()));

        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx,
                subclassOperation(NS + "C", NS + "A")));

        assertEquals(Boolean.FALSE, preview.get("committable"),
                "a broken selected reasoner must fail closed: " + preview);
        assertEquals(Boolean.FALSE, preview.get("satisfiability_checked"), preview::toString);
        assertEquals("error", gate(preview), preview::toString);
        assertEquals("error", reasonerStageStatus(preview), preview::toString);
        // The OBSERVED-selection contract, not just the outcome: a BROKEN probe must make the stage
        // REQUIRED — reclassifying it as "none selected" would leave the stage optional and depend
        // on the QC-time error alone.
        assertTrue(requiredStages(preview).contains("reasoner"), preview::toString);
    }

    @Test
    void reasonerVanishingBetweenProbeAndSnapshotFailsClosed() throws Exception {
        // TOCTOU: selected when the preview probed, deselected before the QC snapshot captured. The
        // optional-stage skip would silently pass; once a selection was observed the stage is
        // REQUIRED, so the vanish surfaces as a gate error instead.
        OWLOntology ontology = disjointFixture();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ProtegeOWLReasonerInfo real = hermitInfo();
        ToolContext ctx = context(managerWith(ontology, () ->
                calls.incrementAndGet() == 1 ? real : null));

        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx,
                subclassOperation(NS + "C", NS + "A")));

        assertTrue(calls.get() >= 2, "the QC pass must have re-read the selection");
        assertEquals(Boolean.FALSE, preview.get("committable"),
                "a selection that vanished mid-preview must fail closed: " + preview);
        assertEquals(Boolean.FALSE, preview.get("satisfiability_checked"), preview::toString);
        assertEquals("error", gate(preview), preview::toString);
        assertTrue(requiredStages(preview).contains("reasoner"),
                "the observed selection must have made the stage required: " + preview);
    }

    @Test
    void reasonerManagerLookupFailuresFailTheGateClosed() throws Exception {
        // The purpose-built no-selection double returns a working manager with no current factory. A
        // live registry throwing UnsupportedOperationException is still a failure, not a sentinel:
        // treating that subtype as "none selected" silently drops the configured safety verdict.
        OWLOntology ontology = disjointFixture();
        ToolContext ctx = context(managerWhoseReasonerLookupFails(ontology));

        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx,
                subclassOperation(NS + "C", NS + "A")));

        assertEquals(Boolean.FALSE, preview.get("committable"),
                "a broken live reasoner-manager lookup must not degrade to an unchecked pass: "
                        + preview);
        assertEquals(Boolean.FALSE, preview.get("satisfiability_checked"), preview::toString);
        assertEquals("error", gate(preview), preview::toString);
        assertEquals("error", reasonerStageStatus(preview), preview::toString);
        assertTrue(String.valueOf(preview.get("satisfiability_note")).contains("failed"),
                "the note must not misdiagnose a runtime failure as no selection: " + preview);
        assertTrue(requiredStages(preview).contains("reasoner"),
                "a throwing manager lookup is BROKEN and must require the stage: " + preview);

        OWLOntology nullOntology = disjointFixture();
        Map<String, Object> nullPreview = structured(ChangeSetTools.preview(
                context(managerWhoseReasonerLookupReturnsNull(nullOntology)),
                subclassOperation(NS + "C", NS + "A")));
        assertEquals(Boolean.FALSE, nullPreview.get("committable"), nullPreview::toString);
        assertEquals("error", gate(nullPreview), nullPreview::toString);
        assertEquals("error", reasonerStageStatus(nullPreview), nullPreview::toString);
        assertTrue(requiredStages(nullPreview).contains("reasoner"),
                "a null manager is BROKEN and must require the stage: " + nullPreview);
    }

    // ------------------------------------------------------------------ fixtures

    /** Declared, labelled A ⊥ B plus a bystander C; adding A ⊑ B makes A unsatisfiable. */
    private static OWLOntology disjointFixture() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/gate"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        for (OWLClass cls : List.of(a, b, c)) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    cls.getIRI(), df.getOWLLiteral(cls.getIRI().getShortForm())));
        }
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(a, b));
        return ontology;
    }

    private static Map<String, Object> subclassOperation(String sub, String sup) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", sub);
        op.put("super", sup);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(op));
        return args;
    }

    private static ToolContext context(OWLModelManager mm) {
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    /** {@link FakeModelManager} plus a HermiT-backed reasoner manager, as the isolated-QC tests do. */
    private static OWLModelManager reasonerBackedManager(OWLOntology ontology) {
        return managerWith(ontology, hermitInfo());
    }

    private static ProtegeOWLReasonerInfo hermitInfo() {
        OWLReasonerFactory factory = new org.semanticweb.HermiT.ReasonerFactory();
        OWLReasonerConfiguration configuration = new SimpleConfiguration();
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                ChangeSetPreviewReasonerGateTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getReasonerId" -> "org.semanticweb.HermiT";
                            case "getReasonerName" -> "HermiT";
                            case "getReasonerFactory" -> factory;
                            case "getRecommendedBuffering" -> BufferingMode.NON_BUFFERING;
                            case "getConfiguration" -> configuration;
                            case "toString" -> "HermiT gate-test info";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    /** A selection whose plugin metadata is unusable — every capture attempt throws. */
    private static ProtegeOWLReasonerInfo brokenInfo() {
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                ChangeSetPreviewReasonerGateTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getReasonerId" -> "broken.reasoner";
                            case "getReasonerName" -> "Broken";
                            case "toString" -> "broken gate-test info";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new IllegalStateException(
                                    "broken reasoner plugin: " + method.getName());
                        });
    }

    private static OWLModelManager managerWith(OWLOntology ontology, ProtegeOWLReasonerInfo info) {
        return managerWith(ontology, () -> info);
    }

    private static OWLModelManager managerWith(OWLOntology ontology,
            java.util.function.Supplier<ProtegeOWLReasonerInfo> currentFactory) {
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                ChangeSetPreviewReasonerGateTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                            case "getCurrentReasonerFactory" -> currentFactory.get();
                            case "getCurrentReasonerFactoryId" -> "org.semanticweb.HermiT";
                            case "getCurrentReasonerName" -> "HermiT";
                            case "getInstalledReasonerFactories" -> Set.of();
                            case "getCurrentReasoner", "classifyAsynchronously" ->
                                    throw new AssertionError(
                                            "the preview must never consult the live reasoner");
                            case "toString" -> "PreviewGateReasonerManager";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        OWLModelManager base = FakeModelManager.over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetPreviewReasonerGateTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return reasoners;
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static OWLModelManager managerWhoseReasonerLookupFails(OWLOntology ontology) {
        OWLModelManager base = FakeModelManager.over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetPreviewReasonerGateTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        throw new UnsupportedOperationException(
                                "live reasoner registry is unavailable");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static OWLModelManager managerWhoseReasonerLookupReturnsNull(OWLOntology ontology) {
        OWLModelManager base = FakeModelManager.over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetPreviewReasonerGateTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return null;
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** The preflight's required_stages list — pins the probe's required-ness decision. */
    private static String requiredStages(Map<String, Object> preview) {
        Object preflight = preview.get("preflight");
        if (!(preflight instanceof Map<?, ?> gateJson)) {
            return "absent";
        }
        return String.valueOf(gateJson.get("required_stages"));
    }

    private static String gate(Map<String, Object> preview) {
        Object preflight = preview.get("preflight");
        assertNotNull(preflight, preview::toString);
        return String.valueOf(((Map<?, ?>) preflight).get("gate"));
    }

    /** The reasoner stage's status inside the preflight, or "absent" when it was never scheduled. */
    private static String reasonerStageStatus(Map<String, Object> preview) {
        Object preflight = preview.get("preflight");
        if (!(preflight instanceof Map<?, ?> gateJson)
                || !(gateJson.get("stages") instanceof List<?> rows)) {
            return "absent";
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> stage && "reasoner".equals(stage.get("stage"))) {
                return String.valueOf(stage.get("status"));
            }
        }
        return "absent";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
