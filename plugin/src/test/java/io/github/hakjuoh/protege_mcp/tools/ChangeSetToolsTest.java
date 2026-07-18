package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * End-to-end preview → commit tests over the headless Protégé adapter, focused on the commit-time
 * revalidation contract: the effective policy is re-resolved exactly as the preview resolved it
 * (discovery included) INSIDE the model-thread hop that applies the delta, and a hop failure never
 * claims the delta was certainly not applied.
 */
class ChangeSetToolsTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";

    private Preferences prefs;
    private String savedToken;
    private String savedReadOnly;
    private String savedConfirm;

    @BeforeEach
    void pinWritePrefs() {
        // Snapshot as strings (like the controller tests) so headless prefs are restored faithfully;
        // the controller constructor may mint and persist a token, and commit reads the write gates.
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
    void unchangedDiscoveredPolicyCommitsAndAppliesTheDelta(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeDiscoveredPolicy(temp, "root-policy");
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> preview = committablePreview(ctx);
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs(preview)));

        assertEquals(Boolean.TRUE, commit.get("committed"), commit::toString);
        assertTrue(ontology.containsAxiom(plannedAxiom(ontology)),
                "the committed delta must land in the live ontology");
    }

    @Test
    void shadowingPolicyCreatedBetweenPreviewAndCommitRefusesTheCommit(@TempDir Path temp)
            throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeDiscoveredPolicy(temp, "root-policy");
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> preview = committablePreview(ctx);
        // A policy nearer the ontology document now SHADOWS the one the preview discovered; a commit
        // that re-resolved only the pinned file would silently apply under the superseded policy.
        writeDiscoveredPolicy(docDir, "shadow-policy");
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs(preview)));

        assertEquals(Boolean.FALSE, commit.get("committed"), commit::toString);
        assertEquals("policy_conflict", commit.get("error_code"));
        assertTrue(String.valueOf(commit.get("effective_policy_path"))
                        .endsWith(Path.of("sub", ".protege-mcp", "project.yaml").toString()),
                commit::toString);
        assertFalse(ontology.containsAxiom(plannedAxiom(ontology)),
                "a refused commit must apply nothing");
    }

    @Test
    void policyAppearingAfterANoPolicyPreviewStillRefusesTheCommit(@TempDir Path temp)
            throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> preview = committablePreview(ctx);
        assertEquals(Boolean.FALSE, preview.get("policy_loaded"));
        writeDiscoveredPolicy(temp, "late-policy");
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs(preview)));

        assertEquals(Boolean.FALSE, commit.get("committed"), commit::toString);
        assertEquals("policy_conflict", commit.get("error_code"));
        assertFalse(ontology.containsAxiom(plannedAxiom(ontology)));
    }

    @Test
    void policyShadowingIsDetectedInsideTheCommitHopAtApplyTime(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeDiscoveredPolicy(temp, "root-policy");
        OWLOntology ontology = ontology(docDir);
        OWLModelManager base = FakeModelManager.withNoReasonerSelected(ontology);
        AtomicBoolean armed = new AtomicBoolean(false);
        AtomicBoolean swappedInsideHop = new AtomicBoolean(false);
        // Commit performs NO policy resolution before its model-thread hop, so its only
        // reasoner-manager read happens inside the hop body — after the revision-fingerprint
        // comparison, immediately before the in-hop policy load that directly precedes the apply.
        // Swapping the policy from this hook therefore lands after commit() was invoked AND after
        // the fingerprints were computed, yet is still refused — proving the shadowing decision is
        // made at apply time, in the last-check-before-apply position.
        OWLModelManager mm = (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetToolsTest.class.getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if (armed.get() && "getOWLReasonerManager".equals(method.getName())
                            && swappedInsideHop.compareAndSet(false, true)) {
                        writeDiscoveredPolicy(docDir, "shadow-policy");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        ToolContext ctx = context(mm);

        Map<String, Object> preview = committablePreview(ctx);
        armed.set(true);
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs(preview)));

        assertTrue(swappedInsideHop.get(), "the swap hook must have fired inside the commit hop");
        assertEquals(Boolean.FALSE, commit.get("committed"), commit::toString);
        assertEquals("policy_conflict", commit.get("error_code"));
        assertFalse(ontology.containsAxiom(plannedAxiom(ontology)),
                "an apply-time policy conflict must apply nothing");
    }

    @Test
    void invalidatedPreviewStillReportsSatisfiability(@TempDir Path temp) throws Exception {
        // A policy appearing between the captured hop and the final re-resolution invalidates the
        // preview. The invalidated shape must carry the same satisfiability honesty as a cached one —
        // review finding: it used to omit the field entirely.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(op));
        Map<String, Object> preview = structured(ChangeSetTools.previewPrepared(ctx, args, mm -> {
            // Runs inside the captured hop — AFTER the initial policy resolution, BEFORE the final
            // re-resolution — so the new policy digest mismatches and invalidates the preview.
            try {
                writeDiscoveredPolicy(temp, "mid-preview-policy");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return ChangePlanner.plan(mm, List.of(op), false);
        }));

        assertEquals(Boolean.TRUE, preview.get("preview_invalidated"), preview::toString);
        assertEquals("revision_changed_during_preview", preview.get("error_code"));
        assertEquals(Boolean.FALSE, preview.get("satisfiability_checked"),
                "the invalidated shape must keep the satisfiability honesty: " + preview);
        assertNotNull(preview.get("satisfiability_note"), preview::toString);
    }

    @Test
    void previewWhoseRetainedOperationRowsExceedTheEntryLimitIsRefused(@TempDir Path temp)
            throws Exception {
        // Review finding: an all-no-op preview normalized to ZERO axioms, so size accounting saw ~0
        // bytes while the cached operation rows alone exceeded the 2 MiB per-entry limit.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(docDir.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        String hugeSegment = "x".repeat(10_000);
        List<Map<String, Object>> operations = new java.util.ArrayList<>();
        OWLClass parent = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Parent"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(parent));
        for (int i = 0; i < 200; i++) {
            OWLClass cls = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#" + hugeSegment + i));
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(cls, parent));
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("op", "add");
            op.put("axiom_type", "subclass_of");
            op.put("sub", cls.getIRI().toString());
            op.put("super", parent.getIRI().toString());
            operations.add(op);
        }
        OWLModelManager base = FakeModelManager.withNoReasonerSelected(ontology);
        // Counts SELECTION CAPTURES (getCurrentReasonerFactory): the plan-hop probe performs exactly
        // one; the QC pass's phase-1 capture would perform a second. Other legitimate pre-QC reads
        // (policy validation listing installed factories) never call this method.
        java.util.concurrent.atomic.AtomicInteger selectionCaptures =
                new java.util.concurrent.atomic.AtomicInteger();
        OWLModelManager counting = (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetToolsTest.class.getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, margs) -> {
                    Object result;
                    try {
                        result = method.invoke(base, margs);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if ("getOWLReasonerManager".equals(method.getName())
                            && result instanceof org.protege.editor.owl.model.inference.OWLReasonerManager real) {
                        return Proxy.newProxyInstance(ChangeSetToolsTest.class.getClassLoader(),
                                new Class<?>[] {org.protege.editor.owl.model.inference.OWLReasonerManager.class},
                                (p2, m2, a2) -> {
                                    if ("getCurrentReasonerFactory".equals(m2.getName())) {
                                        selectionCaptures.incrementAndGet();
                                    }
                                    try {
                                        return m2.invoke(real, a2);
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    }
                    return result;
                });
        ToolContext ctx = context(counting);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", operations);

        var result = io.github.hakjuoh.protege_mcp.tools.Tools.guard(
                () -> ChangeSetTools.preview(ctx, args));

        assertEquals(Boolean.TRUE, result.isError(),
                () -> "an entry whose retained rows dwarf its (empty) normalized delta must be "
                        + "refused, not silently cached: " + result.structuredContent());
        assertTrue(String.valueOf(result.content()).contains("per-preview memory limit"),
                () -> String.valueOf(result.content()));
        // POSITION, not just outcome: the plan-hop probe captures the selection exactly once; a
        // rejection that only happened at put() after QC would have captured it a second time.
        assertEquals(1, selectionCaptures.get(),
                "the oversized preview must be rejected BEFORE the QC pass runs");
    }

    @Test
    void previewWithAGiantPolicyPathIsRefusedByTheEntryLimit(@TempDir Path temp) throws Exception {
        // Review finding: the caller-supplied policy_path is retained by the cache entry (and echoed
        // into the reasons), but size accounting ignored it — a ~2.1 MB path was cached under a
        // 2 MiB per-entry limit while reporting a few hundred accounted bytes.
        // POSITION pin: the guard runs before policy discovery and before ANY model hop, so a
        // model manager that fails every call proves the rejection point, not just the outcome.
        OWLModelManager untouchable = (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetToolsTest.class.getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, margs) -> switch (method.getName()) {
                    case "toString" -> "untouchable";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == margs[0];
                    default -> throw new AssertionError("the giant policy_path must be rejected "
                            + "before any model access, but " + method.getName() + " was called");
                });
        ToolContext ctx = context(untouchable);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(op));
        args.put("policy_path", "x".repeat(2_200_000));

        var result = io.github.hakjuoh.protege_mcp.tools.Tools.guard(
                () -> ChangeSetTools.preview(ctx, args));

        assertEquals(Boolean.TRUE, result.isError(),
                () -> String.valueOf(result.structuredContent()));
        assertTrue(String.valueOf(result.content()).contains("per-preview memory limit"),
                () -> String.valueOf(result.content()));
    }

    @Test
    void overflowingTtlSecondsIsRejectedBeforeAnyModelAccess() {
        // Number.intValue() wraps 2^32+1 to 1 — an absurd ttl must be rejected, not silently
        // converted into a valid one after burning a QC pass.
        OWLModelManager untouchable = (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetToolsTest.class.getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, margs) -> switch (method.getName()) {
                    case "toString" -> "untouchable";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == margs[0];
                    default -> throw new AssertionError("an overflowing ttl_seconds must be rejected "
                            + "before any model access, but " + method.getName() + " was called");
                });
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(op));
        // Every wrap-prone shape: int-wrapping long, long-wrapping BigInteger, fractional double.
        for (Object absurd : List.of(4_294_967_297L,
                new java.math.BigInteger("18446744073709551617"), 900.5d)) {
            args.put("ttl_seconds", absurd);
            var result = io.github.hakjuoh.protege_mcp.tools.Tools.guard(
                    () -> ChangeSetTools.preview(context(untouchable), args));
            assertEquals(Boolean.TRUE, result.isError(),
                    () -> absurd + ": " + result.structuredContent());
            assertTrue(String.valueOf(result.content()).contains("ttl_seconds must be"),
                    () -> absurd + ": " + result.content());
        }
    }

    @Test
    void previewRejectsMoreThanTheMaximumOperations(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        List<Map<String, Object>> operations = new java.util.ArrayList<>();
        for (int i = 0; i <= ChangePlanner.MAX_OPERATIONS; i++) {
            operations.add(op);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", operations);

        var result = io.github.hakjuoh.protege_mcp.tools.Tools.guard(
                () -> ChangeSetTools.preview(ctx, args));

        assertEquals(Boolean.TRUE, result.isError(),
                () -> String.valueOf(result.structuredContent()));
        assertTrue(String.valueOf(result.content())
                        .contains("operations exceeds the maximum of " + ChangePlanner.MAX_OPERATIONS),
                () -> String.valueOf(result.content()));
    }

    @Test
    void exactlyTheMaximumOperationsPassesThePlannerCap(@TempDir Path temp) throws Exception {
        // Pins the boundary direction (> not >=) AND the final-set no_ops semantics: 2,000 identical
        // adds normalize to ONE change and 1,999 no-ops.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        List<Map<String, Object>> operations = new java.util.ArrayList<>();
        for (int i = 0; i < ChangePlanner.MAX_OPERATIONS; i++) {
            operations.add(op);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", operations);
        args.put("include_impact", "none");

        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx, args));

        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertEquals(1, ((Number) preview.get("normalized_changes")).intValue(), preview::toString);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) preview.get("summary");
        assertEquals(ChangePlanner.MAX_OPERATIONS - 1, ((Number) summary.get("no_ops")).intValue(),
                preview::toString);
    }

    @Test
    void commitHopUsesTheLongDocumentWriteBound() {
        // Must stay in step with the document tools' save/merge bound (see the constant's doc): the
        // commit hop serializes four canonical fingerprints plus the applyChanges broadcast.
        assertEquals(120_000L, ChangeSetTools.COMMIT_TIMEOUT_MS);
    }

    @Test
    void applyChangesRollbackUsesChangeSetGateAndPreventsLiveMutation(@TempDir Path temp)
            throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeGovernedPolicy(temp);
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> operation = removeFooLabelOperation();

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "adversarial namespace change", List.of(operation), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(0, ((Number) summary.get("errors")).intValue(), result::toString);
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");

        assertEquals("change_set_preflight", verify.get("verification_path"), verify::toString);
        assertEquals("fail", verify.get("gate"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("regression"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
        assertTrue(ontology.containsAxiom(fooLabelAxiom(ontology)),
                "rollback mode must retain the live label removed only in the rejected snapshot");
        // The prevented payload must not claim the batch landed: the released row/summary fields
        // describe what actually applied, and here nothing did.
        assertEquals(Boolean.FALSE, summary.get("single_undo"), summary::toString);
        assertEquals(0, ((Number) summary.get("removed")).intValue(), summary::toString);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("operations");
        assertEquals(Boolean.FALSE, rows.get(0).get("removed"), rows::toString);
    }

    @Test
    void rollbackWithoutReasonerOrPolicyIsRefusedOutright(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        var refused = ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "unguarded rollback", List.of(removeFooLabelOperation()), false);

        assertEquals(Boolean.TRUE, refused.isError(), () -> String.valueOf(refused.content()));
        assertTrue(String.valueOf(refused.content()).contains("needs a reasoner"),
                () -> String.valueOf(refused.content()));
        assertTrue(ontology.containsAxiom(fooLabelAxiom(ontology)),
                "the refused batch must not touch the live ontology");
    }

    @Test
    void reportModeCommitsUnderALoadedButInvalidPolicy(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        Path dir = Files.createDirectories(temp.resolve(".protege-mcp"));
        ProjectPolicyFixtures.writePolicy(dir.resolve("project.yaml"),
                ProjectPolicyFixtures.minimalPolicy("governed", ONTOLOGY_IRI)
                        + "modules:\n  - ontology_iri: https://example.org/missing\n"
                        + "    path: missing.rdf\n");
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "report", 60_000,
                "review under an invalid policy", List.of(removeFooLabelOperation()), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");

        assertEquals("error", verify.get("gate"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("applied"), verify::toString);
        assertEquals(null, verify.get("error_code"),
                "report mode must commit under the same invalid policy verify=none accepts");
        assertFalse(ontology.containsAxiom(fooLabelAxiom(ontology)),
                "report mode commits the batch and surfaces the failing verdict");
    }

    @Test
    void rollbackUnderALoadedButInvalidPolicyStaysPrevented(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        Path dir = Files.createDirectories(temp.resolve(".protege-mcp"));
        ProjectPolicyFixtures.writePolicy(dir.resolve("project.yaml"),
                ProjectPolicyFixtures.minimalPolicy("governed", ONTOLOGY_IRI)
                        + "modules:\n  - ontology_iri: https://example.org/missing\n"
                        + "    path: missing.rdf\n");
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "rollback under an invalid policy", List.of(removeFooLabelOperation()), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");

        assertEquals("error", verify.get("gate"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertTrue(ontology.containsAxiom(fooLabelAxiom(ontology)),
                "an unproducible verdict must keep failing closed in rollback mode");
    }

    @Test
    void netZeroCancellationBatchLandsNothingThroughVerify(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        String property = ONTOLOGY_IRI + "#undeclaredNote";
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("op", "add");
        add.put("axiom_type", "annotation_assertion");
        add.put("subject", ONTOLOGY_IRI + "#Foo");
        add.put("property", property);
        add.put("value", "temporary");
        Map<String, Object> remove = new LinkedHashMap<>(add);
        remove.put("op", "remove");
        int before = ontology.getAxiomCount();

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "report", 60_000,
                "net-zero cancellation", List.of(add, remove), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");

        assertEquals(before, ontology.getAxiomCount(), "a cancelled batch must not land the "
                + "declaration side effects the isolated gate never evaluated");
        assertEquals("pass", verify.get("gate"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
        assertEquals(Boolean.FALSE, summary.get("single_undo"), summary::toString);
    }

    @Test
    void preflightConfigHonorsTheDocumentedTimeoutBudget(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        RevisionTools.PolicyState noPolicy = RevisionTools.resolvePolicy(ctx, null);
        assertFalse(noPolicy.policy().loaded());
        assertEquals(5_000, ChangeSetTools.preflightConfig(noPolicy,
                Map.<String, Object>of("timeout_ms", 5_000),
                ChangeSetTools.ReasonerSelection.NONE).timeout);

        writeGovernedPolicy(temp);
        RevisionTools.PolicyState governed = RevisionTools.resolvePolicy(ctx, null);
        assertTrue(governed.policy().valid(), () -> governed.policy().issues().toString());
        int policyBudget = ChangeSetTools.preflightConfig(governed, Map.of(),
                ChangeSetTools.ReasonerSelection.NONE).timeout;
        assertEquals(policyBudget, ChangeSetTools.preflightConfig(governed,
                Map.<String, Object>of("timeout_ms", policyBudget + 1_000_000),
                ChangeSetTools.ReasonerSelection.NONE).timeout,
                "the documented argument can only tighten the policy's reproducible budget");
        assertEquals(1_000, ChangeSetTools.preflightConfig(governed,
                Map.<String, Object>of("timeout_ms", 1_000),
                ChangeSetTools.ReasonerSelection.NONE).timeout);
    }

    @Test
    void applyChangesReportUsesSameGateButKeepsTheLiveMutation(@TempDir Path temp)
            throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeGovernedPolicy(temp);
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> operation = removeFooLabelOperation();

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "report", 60_000,
                "review namespace change", List.of(operation), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(0, ((Number) summary.get("errors")).intValue(), result::toString);
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");

        assertEquals("change_set_preflight", verify.get("verification_path"), verify::toString);
        assertEquals("fail", verify.get("gate"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("regression"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("applied"), verify::toString);
        assertFalse(ontology.containsAxiom(fooLabelAxiom(ontology)),
                "report mode retains the label removal after surfacing the gate regression");
    }

    @Test
    void commitHopTimeoutIsHonestAboutThePossiblyAppliedDelta(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeDiscoveredPolicy(temp, "root-policy");
        OWLOntology ontology = ontology(docDir);
        OWLModelManager base = FakeModelManager.withNoReasonerSelected(ontology);
        // Simulate the bounded EDT wait expiring mid-hop: OntologyAccess.compute throws exactly this
        // exception type while the queued body may keep running to completion on the UI thread.
        OWLModelManager timingOut = (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetToolsTest.class.getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if ("applyChanges".equals(method.getName())) {
                        throw new McpAccessException("Timed out after " + ChangeSetTools.COMMIT_TIMEOUT_MS
                                + " ms waiting for the Protégé UI thread (the application may be busy).");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        ToolContext ctx = context(timingOut);

        Map<String, Object> preview = committablePreview(ctx);
        String id = String.valueOf(preview.get("change_set_id"));
        McpAccessException failure = assertThrows(McpAccessException.class,
                () -> ChangeSetTools.commit(ctx, commitArgs(preview)));

        assertTrue(failure.getMessage().contains("may still complete"), failure.getMessage());
        assertTrue(failure.getMessage().contains("get_model_revision"), failure.getMessage());
        // The entry returns to READY so the caller can re-verify the revision and retry explicitly.
        assertNotNull(ctx.changeSets().claim(id).entry(),
                "a failed hop must release the preview for an informed retry");
    }

    // ------------------------------------------------------------------ fixtures

    private static ToolContext context(OWLModelManager mm) {
        // The controller only dereferences the access inside start(), which these tests never call.
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    /** Labelled Animal + Foo with no asserted hierarchy; the planned delta adds Foo ⊑ Animal. */
    private static OWLOntology ontology(Path documentDir) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(documentDir.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"));
        OWLClass foo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(animal));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(foo));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                animal.getIRI(), df.getOWLLiteral("Animal")));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                foo.getIRI(), df.getOWLLiteral("Foo")));
        return ontology;
    }

    private static org.semanticweb.owlapi.model.OWLAxiom plannedAxiom(OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        return df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo")),
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal")));
    }

    private static void writeDiscoveredPolicy(Path directory, String projectId) throws Exception {
        Path dir = Files.createDirectories(directory.resolve(".protege-mcp"));
        ProjectPolicyFixtures.writePolicy(dir.resolve("project.yaml"),
                ProjectPolicyFixtures.minimalPolicy(projectId, ONTOLOGY_IRI)
                + "validation:\n"
                + "  required_stages: [structural]\n"
                + "  fail_on: error\n");
    }

    private static void writeGovernedPolicy(Path directory) throws Exception {
        Path dir = Files.createDirectories(directory.resolve(".protege-mcp"));
        ProjectPolicyFixtures.writePolicy(dir.resolve("project.yaml"),
                ProjectPolicyFixtures.minimalPolicy("governed", ONTOLOGY_IRI)
                + "annotations:\n"
                + "  required: [http://www.w3.org/2000/01/rdf-schema#label]\n"
                + "validation:\n"
                + "  required_stages: [governance]\n"
                + "  fail_on: warning\n");
    }

    private static Map<String, Object> removeFooLabelOperation() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "remove");
        operation.put("axiom_type", "annotation_assertion");
        operation.put("subject", ONTOLOGY_IRI + "#Foo");
        operation.put("property", "http://www.w3.org/2000/01/rdf-schema#label");
        operation.put("value", "Foo");
        return operation;
    }

    private static org.semanticweb.owlapi.model.OWLAxiom fooLabelAxiom(OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        return df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                IRI.create(ONTOLOGY_IRI + "#Foo"), df.getOWLLiteral("Foo"));
    }

    private static Map<String, Object> committablePreview(ToolContext ctx) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(op));
        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx, args));
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        return preview;
    }

    private static Map<String, Object> commitArgs(Map<String, Object> preview) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("change_set_id", preview.get("change_set_id"));
        args.put("expected_revision", preview.get("base_revision"));
        return args;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
