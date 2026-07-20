package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The {@code lock_mode} gate matrix (ADR 0005 decision 4) through {@code run_project_qc} /
 * {@code run_qc_suite(policy_path)}: a locked policy always verifies whatever the request says;
 * an unlocked policy gains opt-in verification with the released tool resolution rules, a clean
 * reported skip when the default lockfile is absent, {@code imports.lock_missing} exactly in the
 * {@code required} file-absent state, honest {@code lockfile_source} trust labeling, and the
 * loaded-content attestation on the request path (it stays gate-only).
 */
class ProjectQcLockModeTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";
    private static final String IMPORTED_IRI = "https://example.org/imported";

    @Test
    void lockedPolicyVerifiesForEveryLockModeValueAndIgnoreCannotWeakenIt(@TempDir Path temp)
            throws Exception {
        Path policy = lockedPolicy(temp);
        ToolContext ctx = importingContext(temp);

        // Clean project: every lock_mode value passes; the released (ignore/absent) result shape
        // carries NO lockfile_source, while an explicit request adds the policy_declared label.
        Map<String, Object> released = run(ctx, policy, null);
        assertEquals("pass", released.get("gate"), released::toString);
        Map<?, ?> releasedVerification = (Map<?, ?>) released.get("import_lock_verification");
        assertEquals(true, releasedVerification.get("valid"));
        assertNull(releasedVerification.get("lockfile_source"),
                "the released locked-gate result shape must not change without a request");
        for (String mode : List.of("ignore", "verify", "required")) {
            Map<String, Object> result = run(ctx, policy, mode);
            assertEquals("pass", result.get("gate"), result::toString);
            Map<?, ?> verification = (Map<?, ?>) result.get("import_lock_verification");
            assertEquals(true, verification.get("valid"));
            if (!"ignore".equals(mode)) {
                assertEquals(ImportLockTools.SOURCE_POLICY_DECLARED,
                        verification.get("lockfile_source"),
                        "a locked gate only ever verifies the policy-pinned lockfile");
            }
        }

        // A tampered import fails the gate for EVERY value — lock_mode=ignore cannot weaken the
        // policy-mandated verification.
        Files.writeString(temp.resolve("imported.ttl"),
                Files.readString(temp.resolve("imported.ttl")) + "# tampered\n");
        for (String mode : List.of("ignore", "verify", "required")) {
            Map<String, Object> rejected = run(ctx, policy, mode);
            assertEquals("error", rejected.get("gate"), rejected::toString);
            assertTrue(String.valueOf(rejected.get("findings")).contains("imports.lock_mismatch"),
                    rejected::toString);
        }
    }

    @Test
    void unlockedVerifyComparesTheBesideDocumentLockWithHonestTrustLabels(@TempDir Path temp)
            throws Exception {
        writeImportedAndLock(temp);
        Path policy = unlockedPolicy(temp);
        ToolContext ctx = importingContext(temp);

        // Without a request the unlocked gate performs no lock verification at all.
        Map<String, Object> released = run(ctx, policy, null);
        assertEquals("pass", released.get("gate"), released::toString);
        assertNull(released.get("import_lock_verification"),
                "an unlocked policy must not verify without a request");

        Map<String, Object> verified = run(ctx, policy, "verify");
        assertEquals("pass", verified.get("gate"), verified::toString);
        Map<?, ?> verification = (Map<?, ?>) verified.get("import_lock_verification");
        assertEquals(true, verification.get("valid"), verification::toString);
        assertEquals(true, verification.get("loaded_content_verified"),
                "the gate-only loaded-content attestation must run on the request path too");
        assertEquals(ImportLockTools.SOURCE_BESIDE_DOCUMENT, verification.get("lockfile_source"));
        assertTrue(String.valueOf(verification.get("lockfile_note"))
                        .contains("accident-safety"),
                "a beside-document lockfile must be labeled as accident-safety, not tamper-evidence");

        // A swapped import document fails run_project_qc without any explicit verify_import_lock
        // call under the import-lock contract, reusing the pinned finding code.
        Files.writeString(temp.resolve("imported.ttl"),
                Files.readString(temp.resolve("imported.ttl")) + "# tampered\n");
        Map<String, Object> rejected = run(ctx, policy, "verify");
        assertEquals("error", rejected.get("gate"), rejected::toString);
        assertTrue(String.valueOf(rejected.get("findings")).contains("imports.lock_mismatch"),
                rejected::toString);
        // ... and lock_mode=ignore still ignores the tamper for an unlocked policy (released
        // behavior unchanged when the argument is absent).
        assertEquals("pass", run(ctx, policy, null).get("gate"));
    }

    @Test
    void unlockedVerifyFailsClosedOnAnUnsavedInMemoryEditOfALockedImport(@TempDir Path temp)
            throws Exception {
        writeImportedAndLock(temp);
        Path policy = unlockedPolicy(temp);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology imported = manager.loadOntologyFromOntologyDocument(
                temp.resolve("imported.ttl").toFile());
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        manager.applyChange(new AddImport(active, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(imported.getOntologyID().getOntologyIRI().get())));
        ToolContext ctx = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        assertEquals("pass", run(ctx, policy, "verify").get("gate"));
        var unsaved = manager.getOWLDataFactory().getOWLDeclarationAxiom(manager.getOWLDataFactory()
                .getOWLClass(IRI.create(IMPORTED_IRI + "#UnsavedEdit")));
        manager.addAxiom(imported, unsaved);
        Map<String, Object> rejected = run(ctx, policy, "verify");
        assertEquals("error", rejected.get("gate"), rejected::toString);
        assertTrue(String.valueOf(rejected.get("findings"))
                        .contains("imports.loaded_content_divergence"), rejected::toString);
    }

    @Test
    void verifySkipsCleanlyWhenTheDefaultLockIsAbsentAndRequiredMakesItAnError(@TempDir Path temp)
            throws Exception {
        // Imported document loaded, but NO imports.lock.json anywhere.
        Files.writeString(temp.resolve("imported.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        Path policy = unlockedPolicy(temp);
        ToolContext ctx = importingContext(temp);

        Map<String, Object> skipped = run(ctx, policy, "verify");
        assertEquals("pass", skipped.get("gate"), skipped::toString);
        Map<?, ?> verification = (Map<?, ?>) skipped.get("import_lock_verification");
        assertEquals(true, verification.get("skipped"), verification::toString);
        assertEquals(ImportLockTools.SOURCE_BESIDE_DOCUMENT, verification.get("lockfile_source"));
        assertTrue(String.valueOf(verification.get("note")).contains("skips cleanly"),
                verification::toString);
        assertFalse(String.valueOf(skipped.get("findings")).contains("imports.lock_missing"),
                "a clean verify skip must not gate");

        Map<String, Object> required = run(ctx, policy, "required");
        assertEquals("error", required.get("gate"), required::toString);
        List<?> findings = (List<?>) required.get("findings");
        long lockMissing = findings.stream()
                .filter(row -> "imports.lock_missing".equals(((Map<?, ?>) row).get("id")))
                .count();
        assertEquals(1, lockMissing,
                "exactly the file-absent state produces exactly one imports.lock_missing finding");
    }

    @Test
    void refusalStatesAbortAsConfigurationErrorsNotFindings(@TempDir Path temp) throws Exception {
        // No local document folder: the active ontology has no file-backed document IRI, so the
        // beside-document default cannot resolve — the request aborts through the released
        // qc_configuration_invalid idiom instead of emitting a lock finding.
        Path policy = unlockedPolicy(temp);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        ToolContext ctx = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        Map<String, Object> refused = run(ctx, policy, "verify");
        assertEquals("error", refused.get("gate"), refused::toString);
        String findings = String.valueOf(refused.get("findings"));
        assertTrue(findings.contains("qc_configuration_invalid"), findings);
        assertTrue(findings.contains("no local document folder")
                        || findings.contains("beside-document"), findings);
        assertFalse(findings.contains("imports.lock_missing"),
                "a refusal state must abort, not report a lock finding");
    }

    @Test
    void invalidLockModeValuesAreRejected(@TempDir Path temp) throws Exception {
        writeImportedAndLock(temp);
        Path policy = unlockedPolicy(temp);
        ToolContext ctx = importingContext(temp);

        Map<String, Object> rejected = run(ctx, policy, "banana");
        assertEquals("error", rejected.get("gate"), rejected::toString);
        assertTrue(String.valueOf(rejected.get("findings")).contains("qc_configuration_invalid"),
                rejected::toString);
        assertTrue(String.valueOf(rejected.get("errors")).contains("Invalid lock_mode"),
                rejected::toString);
    }

    @Test
    void unresolvedImportsUnderRequestVerifyReuseThePinnedUnresolvedCode(@TempDir Path temp)
            throws Exception {
        writeImportedAndLock(temp);
        Path policy = unlockedPolicy(temp);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        manager.applyChange(new AddImport(active, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(IRI.create("https://example.org/never-resolved"))));
        ToolContext ctx = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        // Sanity: the unlocked policy alone tolerates the unresolved import (no fail_on_missing).
        assertEquals("pass", run(ctx, policy, null).get("gate"));
        Map<String, Object> rejected = run(ctx, policy, "verify");
        assertEquals("error", rejected.get("gate"), rejected::toString);
        String findings = String.valueOf(rejected.get("findings"));
        assertTrue(findings.contains("imports.unresolved"), findings);
        assertTrue(findings.contains("request lock_mode=verify"), findings);
    }

    @Test
    void theQcSuitePolicyBranchHonorsLockModeIdentically(@TempDir Path temp) throws Exception {
        // Imported document present, no lockfile: the run_qc_suite policy branch (dedicatedTool
        // false) must produce the same required-state error as run_project_qc.
        Files.writeString(temp.resolve("imported.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        Path policy = unlockedPolicy(temp);
        ToolContext ctx = importingContext(temp);

        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("policy_path", policy.toString());
        args.put("lock_mode", "required");
        Map<String, Object> result = structured(ProjectQcTools.run(ctx, args, false, rules(policy)));
        assertEquals("run_qc_suite", result.get("surface"));
        assertEquals("error", result.get("gate"), result::toString);
        assertTrue(String.valueOf(result.get("findings")).contains("imports.lock_missing"),
                result::toString);
    }

    @Test
    void theLegacySuiteRefusesLockModeExplicitlyAndRejectsInvalidValues() {
        // The server registers tools with input validation off, so the catalog enum is advisory:
        // the legacy (no policy_path) run_qc_suite branch must itself reject an invalid value and
        // must refuse verify/required explicitly — it performs no lock verification, and a silent
        // pass would read as "the lock was checked".
        ToolContext ctx = new ToolContext(null, null);
        for (String mode : List.of("verify", "required")) {
            io.modelcontextprotocol.spec.McpSchema.CallToolResult refused =
                    callSuite(ctx, Map.of("lock_mode", mode));
            assertEquals(Boolean.TRUE, refused.isError(), () -> String.valueOf(refused.content()));
            assertTrue(String.valueOf(refused.content()).contains("policy_path"),
                    () -> String.valueOf(refused.content()));
            assertTrue(String.valueOf(refused.content()).contains("run_project_qc"),
                    () -> String.valueOf(refused.content()));
        }
        io.modelcontextprotocol.spec.McpSchema.CallToolResult invalid =
                callSuite(ctx, Map.of("lock_mode", "banana"));
        assertEquals(Boolean.TRUE, invalid.isError(), () -> String.valueOf(invalid.content()));
        assertTrue(String.valueOf(invalid.content()).contains("Invalid lock_mode"),
                () -> String.valueOf(invalid.content()));
    }

    /** Invoke run_qc_suite exactly as the server would (null-exchange test seam). */
    private static CallToolResult callSuite(ToolContext ctx, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        QcSuiteTools.register(registry, ctx);
        for (io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec
                : registry.build()) {
            if (spec.tool().name().equals("run_qc_suite")) {
                return spec.callHandler().apply(ToolTestExchange.localAdmin(),
                        new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                                "run_qc_suite", args));
            }
        }
        throw new AssertionError("no tool named run_qc_suite");
    }

    @Test
    void aSymlinkedBesideDocumentLockEscapingTheProjectIsRefusedUnread(@TempDir Path temp)
            throws Exception {
        // A symlinked imports.lock.json pointing OUTSIDE project_root under a confining policy
        // (allow_external_paths absent = false): the request-level verification must refuse the
        // read through the same canonical containment verify_import_lock uses — never hash-compare
        // (or echo) out-of-project content.
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("imports.lock.json"),
                "{\"version\":1,\"imports\":[]}\n");
        try {
            Files.createSymbolicLink(project.resolve("imports.lock.json"),
                    outside.resolve("imports.lock.json"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + e.getMessage());
        }
        Files.writeString(project.resolve("imported.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        Path policy = project.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("lock-mode-symlink", ONTOLOGY_IRI)
                        + "validation:\n  required_stages: [structural]\n  fail_on: error\n");

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology imported = manager.loadOntologyFromOntologyDocument(
                project.resolve("imported.ttl").toFile());
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(project.resolve("ontology.ttl").toUri()));
        manager.applyChange(new AddImport(active, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(imported.getOntologyID().getOntologyIRI().get())));
        ToolContext ctx = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        Map<String, Object> refused = run(ctx, policy, "verify");
        assertEquals("error", refused.get("gate"), refused::toString);
        String findings = String.valueOf(refused.get("findings"));
        assertTrue(findings.contains("qc_configuration_invalid"), findings);
        assertTrue(findings.contains("outside project_root"), findings);
        assertFalse(findings.contains("imports.lock_mismatch"),
                "the escaping lock must be refused unread, never compared: " + findings);
        assertNull(refused.get("import_lock_verification"),
                "no verification map may be built from an unauthorized lockfile");
    }

    @Test
    void failOnMissingAndRequestVerifyEmitExactlyOneUnresolvedFindingPerImport(@TempDir Path temp)
            throws Exception {
        // imports.fail_on_missing=true already reports each unresolved import; a request-level
        // verify over the same closure must not duplicate the imports.unresolved findings.
        writeImportedAndLock(temp);
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("lock-mode-dedupe", ONTOLOGY_IRI)
                        + "imports:\n  fail_on_missing: true\n"
                        + "validation:\n  required_stages: [structural]\n  fail_on: error\n");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        manager.applyChange(new AddImport(active, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(IRI.create("https://example.org/never-resolved"))));
        ToolContext ctx = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        Map<String, Object> rejected = run(ctx, policy, "verify");
        assertEquals("error", rejected.get("gate"), rejected::toString);
        List<?> findings = (List<?>) rejected.get("findings");
        long unresolved = findings.stream()
                .filter(row -> "imports.unresolved".equals(((Map<?, ?>) row).get("id")))
                .count();
        assertEquals(1, unresolved,
                "fail_on_missing + lock_mode must report each missing import exactly once: "
                        + findings);
        assertTrue(String.valueOf(findings).contains("imports.lock_mismatch"),
                "the failed comparison itself still gates: " + findings);
    }

    @Test
    void declaredButUnresolvedLockfileAbortsTheRequestResolution(@TempDir Path temp)
            throws Exception {
        // A policy declaring imports.lockfile whose file does not exist is loaded-but-INVALID
        // (asset_not_file), so the gate surfaces short-circuit with their structured invalid-policy
        // results BEFORE enforceImportIntegrity ever runs — the declared-but-unresolved abort is
        // therefore pinned at the resolution choke point directly, plus the upstream refusal.
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("lock-mode-declared", ONTOLOGY_IRI)
                        + "imports:\n  lockfile: declared.lock.json\n"
                        + "validation:\n  required_stages: [structural]\n  fail_on: error\n");
        io.github.hakjuoh.protege_mcp.policy.ProjectPolicy loaded =
                io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(policy, null);
        assertTrue(loaded.loaded(), "fixture must load");
        assertFalse(loaded.valid(), "a declared-but-missing lockfile invalidates the policy");

        ToolArgException refused = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class,
                () -> ImportLockTools.resolveRequestGateLock(loaded, null, null));
        assertTrue(refused.getMessage().contains("declares imports.lockfile 'declared.lock.json'"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("did not resolve to a file"),
                refused.getMessage());

        // End to end, the same state never reaches the request resolution: the gate refuses the
        // invalid policy structurally first.
        Files.writeString(temp.resolve("imported.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        Map<String, Object> gate = run(importingContext(temp), policy, "verify");
        assertEquals("error", gate.get("gate"), gate::toString);
        assertTrue(String.valueOf(gate.get("findings")).contains("asset_missing"),
                gate::toString);
    }

    // ------------------------------------------------------------------ fixtures

    /** Write {@code imported.ttl} and a matching beside-active-document {@code imports.lock.json}. */
    private static void writeImportedAndLock(Path temp) throws Exception {
        Path importedPath = temp.resolve("imported.ttl");
        Files.writeString(importedPath, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        String sha = RevisionTools.sha256File(importedPath).substring("sha256:".length());
        Files.writeString(temp.resolve("imports.lock.json"), "{\"version\":1,\"imports\":[{"
                + "\"ontology_iri\":\"" + IMPORTED_IRI + "\","
                + "\"version_iri\":null,\"document\":\"imported.ttl\","
                + "\"sha256\":\"" + sha + "\",\"direct\":true}]}\n");
    }

    /** A valid policy WITHOUT imports.mode=locked and WITHOUT a declared lockfile. */
    private static Path unlockedPolicy(Path temp) throws Exception {
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("lock-mode", ONTOLOGY_IRI)
                        + "validation:\n  required_stages: [structural]\n  fail_on: error\n");
        return policy;
    }

    /** The locked-policy fixture: declared lockfile pinning {@code imported.ttl}. */
    private static Path lockedPolicy(Path temp) throws Exception {
        writeImportedAndLock(temp);
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("lock-mode-locked", ONTOLOGY_IRI)
                        + "imports:\n  mode: locked\n  fail_on_missing: true\n"
                        + "  lockfile: imports.lock.json\n  network: deny\n"
                        + "validation:\n  required_stages: [structural]\n  fail_on: error\n");
        return policy;
    }

    /** An active ontology at {@code temp/ontology.ttl} importing the loaded {@code imported.ttl}. */
    private static ToolContext importingContext(Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology imported = manager.loadOntologyFromOntologyDocument(
                temp.resolve("imported.ttl").toFile());
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        manager.applyChange(new AddImport(active, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(imported.getOntologyID().getOntologyIRI().get())));
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);
    }

    private static Map<String, Object> run(ToolContext ctx, Path policy, String lockMode) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("policy_path", policy.toString());
        if (lockMode != null) {
            args.put("lock_mode", lockMode);
        }
        return structured(ProjectQcTools.run(ctx, args, true, rules(policy)));
    }

    /** Request-scoped rules over the SAME effective policy, as the run_project_qc handler builds. */
    private static DirectAccessPolicy.Rules rules(Path policyPath) {
        io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy =
                io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(policyPath, null);
        return new DirectAccessPolicy.Rules(policy,
                new io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal(1, "test",
                        "test-client", "Test",
                        java.util.Set.of(DirectAccessPolicy.PROJECT_READ), null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
