package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Fail-closed default lock-path resolution for {@code write_import_lock}/{@code verify_import_lock}.
 * With no explicit {@code path} the policy decides the target, and it must decide cleanly: a policy
 * reference that failed to resolve, a loaded-but-invalid policy, or a declared imports.lockfile that
 * did not resolve to a file all refuse — the lock must never silently land beside the active document
 * when the policy declares a different file (verify would otherwise report valid=true for the wrong
 * lock). Driven end-to-end through the real tool entry points over the headless Protégé adapter; the
 * write gate runs against a real controller with the read-only/confirm-writes preferences pinned off
 * and restored afterwards (the same snapshot/restore discipline as McpConfigTest).
 */
class ImportLockToolsLockPathTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";

    private Preferences prefs;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void pinWritablePreferences() {
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

    @Test
    void writeRefusesWhenDeclaredLockfileDidNotResolve(@TempDir Path temp) throws Exception {
        Path policy = writePolicy(temp, "imports:\n  lockfile: config/imports.lock.json\n");
        ToolContext ctx = context(temp);
        ToolArgException refusal = assertThrows(ToolArgException.class,
                () -> ImportLockTools.write(ctx, Map.of("policy_path", policy.toString())));
        assertTrue(refusal.getMessage().contains("config/imports.lock.json"),
                "the refusal must name the declared lockfile: " + refusal.getMessage());
        assertFalse(Files.exists(temp.resolve("imports.lock.json")),
                "no lock may appear beside the active document");
        assertFalse(Files.exists(temp.resolve("config/imports.lock.json")),
                "the declared location must not be silently created either");
    }

    @Test
    void writeRefusesWhenPolicyIsLoadedButInvalid(@TempDir Path temp) throws Exception {
        Path policy = temp.resolve("policy.yaml");
        Files.writeString(policy, "version: 1\n"
                + "project_id: lock-path\n"
                + "root_ontology: https://example.org/other\n"
                + "validation:\n"
                + "  required_stages: [structural]\n");
        ToolContext ctx = context(temp);
        ToolArgException refusal = assertThrows(ToolArgException.class,
                () -> ImportLockTools.write(ctx, Map.of("policy_path", policy.toString())));
        assertTrue(refusal.getMessage().contains("root_ontology_mismatch"),
                "the refusal must name the policy error cause: " + refusal.getMessage());
        assertFalse(Files.exists(temp.resolve("imports.lock.json")),
                "an invalid policy must not fall back beside the active document");
    }

    @Test
    void writeGivesGenericRefusalWhenPolicyBreaksBeforeAssetResolution(@TempDir Path temp) throws Exception {
        // A schema-broken policy still carries a raw imports.lockfile value, but that value never went
        // through asset resolution — the refusal must name the schema failure, not claim the declared
        // lockfile does not exist.
        Path policy = temp.resolve("policy.yaml");
        Files.writeString(policy, "version: 2\n"
                + "project_id: lock-path\n"
                + "root_ontology: " + ONTOLOGY_IRI + "\n"
                + "imports:\n"
                + "  lockfile: config/imports.lock.json\n");
        ToolContext ctx = context(temp);
        ToolArgException refusal = assertThrows(ToolArgException.class,
                () -> ImportLockTools.write(ctx, Map.of("policy_path", policy.toString())));
        assertTrue(refusal.getMessage().contains("schema_invalid"),
                "the refusal must name the schema failure: " + refusal.getMessage());
        assertFalse(refusal.getMessage().contains("did not resolve to a file"),
                "a pre-asset-resolution failure must not claim the lockfile is missing");
        assertFalse(Files.exists(temp.resolve("imports.lock.json")),
                "no lock may appear beside the active document");
    }

    @Test
    void writeRefusesWhenExplicitPolicyPathDoesNotExist(@TempDir Path temp) throws Exception {
        ToolContext ctx = context(temp);
        ToolArgException refusal = assertThrows(ToolArgException.class,
                () -> ImportLockTools.write(ctx,
                        Map.of("policy_path", temp.resolve("missing.yaml").toString())));
        assertTrue(refusal.getMessage().contains("policy_not_found"),
                "a bad explicit policy path must refuse, not fall back: " + refusal.getMessage());
        assertFalse(Files.exists(temp.resolve("imports.lock.json")),
                "no lock may appear beside the active document");
    }

    @Test
    void explicitPathIsHonoredEvenWhenThePolicyCannotDecide(@TempDir Path temp) throws Exception {
        Path policy = writePolicy(temp, "imports:\n  lockfile: config/imports.lock.json\n");
        Path target = temp.resolve("config/imports.lock.json");
        Map<String, Object> result = structured(ImportLockTools.write(context(temp),
                Map.of("policy_path", policy.toString(), "path", target.toString())));
        assertEquals(true, result.get("written"), () -> result.toString());
        assertEquals(target.toString(), result.get("path"));
        assertTrue(Files.isRegularFile(target),
                "an explicit path bootstraps the declared lockfile location");
    }

    @Test
    void policyWithoutLockfileStillDefaultsBesideTheActiveDocument(@TempDir Path temp) throws Exception {
        Path policy = writePolicy(temp, "");
        Map<String, Object> result = structured(ImportLockTools.write(context(temp),
                Map.of("policy_path", policy.toString())));
        assertEquals(true, result.get("written"), () -> result.toString());
        assertEquals(temp.resolve("imports.lock.json").toString(), result.get("path"),
                "a policy that declares no lockfile keeps the beside-document default");
        assertTrue(Files.isRegularFile(temp.resolve("imports.lock.json")));
    }

    @Test
    void verifyRefusesDeclaredButMissingLockfileInsteadOfValidatingTheFallback(@TempDir Path temp)
            throws Exception {
        // A valid empty lock beside the document: a silent fallback would verify THIS file and report
        // valid=true even though the policy declares config/imports.lock.json. Prove the fallback file
        // verifies on its own (no policy), then that the declaring policy refuses instead of using it.
        Files.writeString(temp.resolve("imports.lock.json"), "{\"version\": 1, \"imports\": []}\n");
        Map<String, Object> fallback = structured(ImportLockTools.verify(context(temp), Map.of()));
        assertEquals(true, fallback.get("valid"), () -> fallback.toString());

        Path policy = writePolicy(temp, "imports:\n  lockfile: config/imports.lock.json\n");
        ToolContext ctx = context(temp);
        ToolArgException refusal = assertThrows(ToolArgException.class,
                () -> ImportLockTools.verify(ctx, Map.of("policy_path", policy.toString())));
        assertTrue(refusal.getMessage().contains("config/imports.lock.json"),
                "verify must refuse rather than validate a different file: " + refusal.getMessage());
    }

    @Test
    void verifyRefusesWhenPolicyPathIsNotAValidPath(@TempDir Path temp) throws Exception {
        ToolContext ctx = context(temp);
        // A NUL character is rejected by Path.of on every platform, so this exercises the policy
        // RESOLUTION error branch (PolicyState.error) rather than a loaded-but-invalid policy.
        ToolArgException refusal = assertThrows(ToolArgException.class,
                () -> ImportLockTools.verify(ctx, Map.of("policy_path", "\0")));
        assertTrue(refusal.getMessage().contains("Invalid policy_path"),
                "an unresolvable policy reference must refuse defaulting: " + refusal.getMessage());
    }

    @Test
    void writeRefusesWhenTheOntologyChangesDuringOffThreadCapture(@TempDir Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        ToolContext ctx = new ToolContext(HeadlessAccess.over(FakeModelManager.over(ontology)),
                new McpServerController(new OntologyAccess(null)));
        Path target = temp.resolve("imports.lock.json");

        Map<String, Object> result = structured(ImportLockTools.write(ctx,
                Map.of("path", target.toString()), () -> {
                    var cls = manager.getOWLDataFactory().getOWLClass(
                            IRI.create(ONTOLOGY_IRI + "#ChangedDuringCapture"));
                    manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(cls));
                }));

        assertEquals(false, result.get("written"), () -> result.toString());
        assertEquals("revision_conflict", result.get("error_code"));
        assertFalse(Files.exists(target), "a stale capture must never replace the import lock");
    }

    @Test
    void writeRechecksReadOnlyImmediatelyBeforeInstalling(@TempDir Path temp) throws Exception {
        Path target = temp.resolve("imports.lock.json");

        CallToolResult result = ImportLockTools.write(context(temp),
                Map.of("path", target.toString()),
                () -> prefs.putBoolean(McpConfig.KEY_READ_ONLY, true));

        assertTrue(Boolean.TRUE.equals(result.isError()), "the final read-only gate must fail closed");
        assertFalse(Files.exists(target), "read-only mode must prevent the delayed filesystem write");
    }

    @Test
    void writeRefusesWhenAProjectPolicyAppearsDuringOffThreadCapture(@TempDir Path temp)
            throws Exception {
        Path originalTarget = temp.resolve("imports.lock.json");
        Path policyTarget = temp.resolve("config/imports.lock.json");

        Map<String, Object> result = structured(ImportLockTools.write(context(temp), Map.of(), () -> {
            try {
                Files.createDirectories(temp.resolve(".protege-mcp"));
                Files.createDirectories(policyTarget.getParent());
                Files.writeString(policyTarget, "policy-selected placeholder\n");
                Files.writeString(temp.resolve(".protege-mcp/project.yaml"), "version: 1\n"
                        + "project_id: lock-path\n"
                        + "root_ontology: " + ONTOLOGY_IRI + "\n"
                        + "validation:\n"
                        + "  required_stages: [structural]\n"
                        + "imports:\n"
                        + "  lockfile: config/imports.lock.json\n");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }));

        assertEquals(false, result.get("written"), () -> result.toString());
        assertEquals("policy_conflict", result.get("error_code"));
        assertFalse(Files.exists(originalTarget),
                "a capture for the obsolete default path must never be installed");
        assertEquals("policy-selected placeholder\n", Files.readString(policyTarget),
                "the newly effective policy target must not be overwritten either");
    }

    @Test
    void writeRefusesWhenAnAppearingPolicyLeavesTheTargetUndecidable(@TempDir Path temp)
            throws Exception {
        // Same appearing-policy race as above, but the newly declared lockfile does NOT exist, so the
        // install-time re-discovery cannot resolve a target at all. The refusal must still be the
        // structured policy_conflict, not a raw lock-path resolution error escaping the install guard.
        Path originalTarget = temp.resolve("imports.lock.json");
        Path policyTarget = temp.resolve("config/imports.lock.json");

        Map<String, Object> result = structured(ImportLockTools.write(context(temp), Map.of(), () -> {
            try {
                Files.createDirectories(temp.resolve(".protege-mcp"));
                Files.writeString(temp.resolve(".protege-mcp/project.yaml"), "version: 1\n"
                        + "project_id: lock-path\n"
                        + "root_ontology: " + ONTOLOGY_IRI + "\n"
                        + "validation:\n"
                        + "  required_stages: [structural]\n"
                        + "imports:\n"
                        + "  lockfile: config/imports.lock.json\n");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }));

        assertEquals(false, result.get("written"), () -> result.toString());
        assertEquals("policy_conflict", result.get("error_code"));
        assertTrue(String.valueOf(result.get("message")).contains("config/imports.lock.json"),
                () -> "the conflict must name the undecidable declared lockfile: " + result);
        assertFalse(Files.exists(originalTarget),
                "a capture for the obsolete default path must never be installed");
        assertFalse(Files.exists(policyTarget),
                "the undecidable declared location must not be created either");
    }

    @Test
    void writeRefusesWhenTheEffectivePolicyIsEditedInPlaceDuringCapture(@TempDir Path temp)
            throws Exception {
        // The pinned policy IDENTITY — not just the resolved target path — must still hold at install
        // time: an in-place edit of the effective project.yaml that keeps the SAME declared lockfile
        // changes the policy digest, and the stale capture must fail closed instead of installing
        // under rules that no longer exist.
        Files.createDirectories(temp.resolve(".protege-mcp"));
        Files.createDirectories(temp.resolve("config"));
        Path declaredTarget = temp.resolve("config/imports.lock.json");
        Files.writeString(declaredTarget, "declared placeholder\n");
        Path project = temp.resolve(".protege-mcp/project.yaml");
        Files.writeString(project, discoveredPolicy("first-rules"));

        Map<String, Object> result = structured(ImportLockTools.write(context(temp), Map.of(), () -> {
            try {
                Files.writeString(project, discoveredPolicy("second-rules"));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }));

        assertEquals(false, result.get("written"), () -> result.toString());
        assertEquals("policy_conflict", result.get("error_code"));
        assertEquals("declared placeholder\n", Files.readString(declaredTarget),
                "the still-declared target must not be overwritten by the stale capture");
    }

    @Test
    void writeHopsUseTheLongDocumentWriteBound() {
        // Each hop runs a two-render revision fingerprint that legitimately outlives the default
        // 30 s EDT wait on a large ontology; the bound must stay equal to the change-set commit
        // bound, which exists for the same slow-but-succeeding reason (see the constant's doc).
        assertEquals(120_000L, ImportLockTools.WRITE_HOP_TIMEOUT_MS);
        assertEquals(ChangeSetTools.COMMIT_TIMEOUT_MS, ImportLockTools.WRITE_HOP_TIMEOUT_MS);
    }

    @Test
    void bothWriteHopsPassTheirExplicitLongBoundToTheEdtWait(@TempDir Path temp) throws Exception {
        // write() performs exactly three EDT dispatches: the policy-resolution read, the capture
        // hop, and the install hop. Dispatches 2 and 3 start their bodies only after a stall that
        // exceeds the access's DEFAULT wait bound, so this write succeeds only because both hops
        // hand WRITE_HOP_TIMEOUT_MS to the bounded compute. A hop regressing to the default-bound
        // compute(fn) expires before its stalled body starts, the queued body is then skipped, and
        // the write fails instead of installing the lock.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        AtomicInteger dispatches = new AtomicInteger();
        ToolContext ctx = new ToolContext(
                HeadlessAccess.overStalledDispatches(FakeModelManager.over(ontology),
                        150L, 1_000L, 2, dispatches),
                new McpServerController(new OntologyAccess(null)));
        Path target = temp.resolve("imports.lock.json");

        Map<String, Object> result = structured(
                ImportLockTools.write(ctx, Map.of("path", target.toString())));

        assertEquals(true, result.get("written"), () -> result.toString());
        assertTrue(Files.isRegularFile(target), "the lock lands through the long-bounded install hop");
        assertEquals(3, dispatches.get(), "policy resolution + capture + install; update the"
                + " stalled-dispatch script if write()'s hop structure changes");
    }

    @Test
    void installHopTimeoutIsHonestAboutThePossiblyInstalledLock(@TempDir Path temp) throws Exception {
        // Simulate the bounded EDT wait expiring mid-install: OntologyAccess.compute throws exactly
        // this exception type while the queued body may keep running to completion on the UI
        // thread. Arming the proxy from the afterCapture seam confines the simulated expiry to the
        // INSTALL hop — the capture hop must succeed first, exactly like a real mid-write stall.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        OWLModelManager base = FakeModelManager.over(ontology);
        AtomicBoolean armed = new AtomicBoolean(false);
        OWLModelManager timingOut = (OWLModelManager) Proxy.newProxyInstance(
                ImportLockToolsLockPathTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if (armed.get() && "getActiveOntology".equals(method.getName())) {
                        throw new McpAccessException("Timed out after "
                                + ImportLockTools.WRITE_HOP_TIMEOUT_MS
                                + " ms waiting for the Protégé UI thread (the application may be busy).");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        ToolContext ctx = new ToolContext(HeadlessAccess.over(timingOut),
                new McpServerController(new OntologyAccess(null)));
        Path target = temp.resolve("imports.lock.json");

        McpAccessException failure = assertThrows(McpAccessException.class,
                () -> ImportLockTools.write(ctx, Map.of("path", target.toString()),
                        () -> armed.set(true)));

        assertTrue(failure.getMessage().contains("may still complete"), failure.getMessage());
        assertTrue(failure.getMessage().contains("verify_import_lock"), failure.getMessage());
        assertFalse(Files.exists(target),
                "in this simulation the install body never ran, so no lock may exist");
    }

    // ------------------------------------------------------------------ fixtures

    /** A discovered-policy body that declares config/imports.lock.json; the id varies the digest. */
    private static String discoveredPolicy(String projectId) {
        return "version: 1\n"
                + "project_id: " + projectId + "\n"
                + "root_ontology: " + ONTOLOGY_IRI + "\n"
                + "validation:\n"
                + "  required_stages: [structural]\n"
                + "imports:\n"
                + "  lockfile: config/imports.lock.json\n";
    }

    /** A minimal valid policy (matching root, structural-only stages) plus the given extra block. */
    private static Path writePolicy(Path temp, String extra) throws Exception {
        Path policy = temp.resolve("policy.yaml");
        Files.writeString(policy, "version: 1\n"
                + "project_id: lock-path\n"
                + "root_ontology: " + ONTOLOGY_IRI + "\n"
                + "validation:\n"
                + "  required_stages: [structural]\n"
                + extra);
        return policy;
    }

    /**
     * A headless context whose active ontology is saved (by document IRI) in {@code temp}, with a real
     * controller so {@code write} runs through the actual write gate.
     */
    private static ToolContext context(Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(ontology)),
                new McpServerController(new OntologyAccess(null)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
