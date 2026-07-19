package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** End-to-end strict policy/QC tests over the headless Protégé adapter. */
class ProjectQcToolsTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";

    @Test
    void minimalRequiredGovernanceAndStructuralStagesPass(@TempDir Path temp) throws Exception {
        Path policy = writePolicy(temp, "[governance, structural]", "", "warning");
        Map<String, Object> result = run(temp, policy, false);
        assertEquals("pass", result.get("gate"), () -> result.toString());
        assertEquals(true, result.get("policy_loaded"));
        assertEquals(3, result.get("stages_ran"));
        assertEquals(0, result.get("stages_skipped"));
        assertEquals(true, result.get("snapshot_consistent"));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) result.get("validation_snapshot");
        assertEquals("isolated", snapshot.get("mode"));
        assertEquals(true, snapshot.get("same_snapshot"));
        assertEquals(List.of("interoperability", "governance", "structural"), snapshot.get("stages"));
        assertTrue(String.valueOf(result.get("semantic_fingerprint")).startsWith("sha256:"));
        assertTrue(String.valueOf(result.get("rdf_dataset_fingerprint")).startsWith("sha256:"));
    }

    @Test
    void persistedInvariantViolationFailsButMalformedOrMissingAssetsError(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("quality"));
        Files.writeString(temp.resolve("quality/no-classes.rq"),
                "# id: no-classes\n# severity: error\nASK { ?c a owl:Class }\n");
        String invariantBlock = "  invariants:\n    paths: [quality/*.rq]\n";
        Path policy = writePolicy(temp, "[invariants]", invariantBlock, "error");
        assertEquals("fail", run(temp, policy, true).get("gate"));

        Files.writeString(temp.resolve("quality/no-classes.rq"),
                "# id: inferred-only\n# include_inferred: true\nASK { ?c a owl:Class }\n");
        Map<String, Object> noInference = run(temp, policy, true);
        assertEquals("error", noInference.get("gate"),
                "a required inferred invariant cannot degrade to an asserted pass/fail");

        Files.writeString(temp.resolve("quality/no-classes.rq"), "# id: broken\n");
        Map<String, Object> malformed = run(temp, policy, true);
        assertEquals("error", malformed.get("gate"));
        assertTrue(String.valueOf(malformed.get("findings")).contains("qc_configuration_invalid"));

        Path missing = writePolicy(temp, "[invariants]",
                "  invariants:\n    paths: [quality/missing.rq]\n", "error");
        Map<String, Object> missingResult = run(temp, missing, true);
        assertEquals("error", missingResult.get("gate"));
        assertTrue(String.valueOf(missingResult.get("errors")).contains("asset_missing"));
    }

    @Test
    void persistedInvariantCqAndShaclAssetsRunTogetherThroughProjectQc(@TempDir Path temp)
            throws Exception {
        Files.createDirectories(temp.resolve("quality/invariants"));
        Files.createDirectories(temp.resolve("cqs"));
        Files.writeString(temp.resolve("quality/invariants/no-nothing.rq"),
                "# id: no-nothing\n# severity: error\nASK { ?s a owl:Nothing }\n");
        Files.writeString(temp.resolve("cqs/CQ-1.rq"), "# id: CQ-1\n"
                + "# expected: nonEmpty\n# include_inferred: false\n\n"
                + "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                + "SELECT ?c WHERE { ?c a owl:Class }\n");
        Files.writeString(temp.resolve("quality/shapes.ttl"), """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                [] a sh:NodeShape ;
                   sh:targetClass owl:Class ;
                   sh:property [ sh:path rdfs:label ; sh:minCount 1 ] .
                """);
        Path policy = writePolicy(temp, "[invariants, cqs, shacl]", """
                  invariants:
                    paths: [quality/invariants/*.rq]
                  competency_questions:
                    convention: robot-sparql-dir
                    path: cqs
                  shacl:
                    paths: [quality/shapes.ttl]
                """, "error");

        Map<String, Object> result = run(temp, policy, true);

        assertEquals("fail", result.get("gate"), () -> result.toString());
        assertEquals(4, result.get("stages_ran"));
        assertEquals(0, result.get("stages_skipped"));
        assertTrue(String.valueOf(result.get("resolved_assets")).contains("shapes.ttl"));
        assertTrue(String.valueOf(result.get("findings")).contains("shacl"));
    }

    @Test
    void requiredInferredCompetencyQuestionCannotDegradeToAssertedData(@TempDir Path temp) throws Exception {
        Path cqs = temp.resolve("cqs");
        Files.createDirectories(cqs);
        Files.writeString(cqs.resolve("CQ-1.rq"), "# id: CQ-1\n"
                + "# expected: nonEmpty\n# include_inferred: true\n\n"
                + "SELECT ?c WHERE { ?c a owl:Class }\n");
        Path policy = writePolicy(temp, "[cqs]", "  competency_questions:\n"
                + "    convention: robot-sparql-dir\n    path: cqs\n", "error");
        Map<String, Object> result = run(temp, policy, true);
        assertEquals("error", result.get("gate"));
        assertTrue(String.valueOf(result.get("findings")).contains("requested data"));
    }

    @Test
    void noPolicyAndRootMismatchAreGateErrorsNotMcpTransportErrors(@TempDir Path temp) throws Exception {
        Map<String, Object> noPolicy = structured(ProjectQcTools.run(context(temp, false), Map.of(), true));
        assertEquals("error", noPolicy.get("gate"));
        assertTrue(String.valueOf(noPolicy.get("findings")).contains("policy_not_found"));
        assertTrue(String.valueOf(noPolicy.get("validation_snapshot")).contains("mode=none"));
        @SuppressWarnings("unchecked")
        Map<String, Object> noSnapshot = (Map<String, Object>) noPolicy.get("validation_snapshot");
        assertTrue(noSnapshot.containsKey("closure_fingerprint"));
        assertNull(noSnapshot.get("closure_fingerprint"));

        Path mismatch = temp.resolve("mismatch.yaml");
        ProjectPolicyFixtures.writePolicy(mismatch, "version: 1\nproject_id: mismatch\n"
                + "root_ontology: https://example.org/wrong\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1")
                + "validation:\n  required_stages: [structural]\n");
        Map<String, Object> result = structured(ProjectQcTools.run(context(temp, false),
                Map.of("policy_path", mismatch.toString()), true));
        assertEquals("error", result.get("gate"));
        assertTrue(String.valueOf(result.get("errors")).contains("root_ontology_mismatch"));
    }

    @Test
    void policyFailOnInfoCanFailAnInformationalStructuralFinding(@TempDir Path temp) throws Exception {
        Path policy = writePolicy(temp, "[structural]", "", "info");
        // A property without domain/range is informational by default.
        ToolContext context = contextWithInfoFinding(temp);
        Map<String, Object> args = Map.of("policy_path", policy.toString());
        Map<String, Object> result = structured(ProjectQcTools.run(context, args, true));
        assertEquals("fail", result.get("gate"));
    }

    @Test
    void defaultPolicyThresholdFailsGovernanceWarnings(@TempDir Path temp) throws Exception {
        Path policy = temp.resolve("default-threshold.yaml");
        ProjectPolicyFixtures.writePolicy(policy, "version: 1\nproject_id: default-threshold\n"
                + "root_ontology: " + ONTOLOGY_IRI + "\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1")
                + "iri_policy:\n  required_namespaces: [https://example.org/allowed/]\n"
                + "validation:\n  required_stages: [governance]\n");
        Map<String, Object> result = structured(ProjectQcTools.run(context(temp, true),
                Map.of("policy_path", policy.toString()), true));
        assertEquals("fail", result.get("gate"),
                "the effective default fail_on=warning must gate governance findings");
        assertEquals("warn", result.get("fail_on"));
    }

    @Test
    void anonymousIndividualsSurfaceSessionOnlyFingerprintGuarantee(@TempDir Path temp) throws Exception {
        Path policy = writePolicy(temp, "[structural]", "", "error");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Thing")), df.getOWLAnonymousIndividual("x")));
        ToolContext context = new ToolContext(HeadlessAccess.over(FakeModelManager.over(ontology)), null);
        Map<String, Object> result = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));
        assertEquals("session_only", result.get("fingerprint_stability"));
        assertEquals(false, result.get("release_stable"));
        assertFalse(((List<?>) result.get("fingerprint_warnings")).isEmpty());
        assertTrue(String.valueOf(result.get("findings")).contains("fingerprint.session_only"));
    }

    @Test
    void requiredImportClosureFailsClosedOnAnUnresolvedImport(@TempDir Path temp) throws Exception {
        // imports.fail_on_missing: true must fail the gate on an unresolved import even when every
        // configured stage passes — reasoning over a truncated closure must never report pass.
        Path policy = writePolicy(temp, "[structural]", "imports:\n  fail_on_missing: true\n", "error");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                df.getOWLImportsDeclaration(IRI.create("https://example.org/missing"))));
        ToolContext context = new ToolContext(HeadlessAccess.over(FakeModelManager.over(ontology)), null);

        Map<String, Object> result = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));
        assertEquals("error", result.get("gate"),
                "fail_on_missing must fail closed on an unresolved import");
        assertTrue(String.valueOf(result.get("findings")).contains("imports.unresolved"),
                "the gate error must name the unresolved import");
    }

    @Test
    void lockedPolicyAutomaticallyRejectsATamperedImport(@TempDir Path temp) throws Exception {
        Path importedPath = temp.resolve("imported.ttl");
        Files.writeString(importedPath, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/imported> a owl:Ontology .\n");
        String sha = RevisionTools.sha256File(importedPath).substring("sha256:".length());
        Path lock = temp.resolve("imports.lock.json");
        Files.writeString(lock, "{\"version\":1,\"imports\":[{"
                + "\"ontology_iri\":\"https://example.org/imported\","
                + "\"version_iri\":null,\"document\":\"imported.ttl\","
                + "\"sha256\":\"" + sha + "\",\"direct\":true}]}\n");
        Path policy = writePolicy(temp, "[structural]",
                "imports:\n  mode: locked\n  fail_on_missing: true\n"
                        + "  lockfile: imports.lock.json\n  network: deny\n", "error");

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology imported = manager.loadOntologyFromOntologyDocument(importedPath.toFile());
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                manager.getOWLDataFactory().getOWLImportsDeclaration(
                        imported.getOntologyID().getOntologyIRI().get())));
        ToolContext context = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        Map<String, Object> pass = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));
        assertEquals("pass", pass.get("gate"), () -> pass.toString());
        Files.writeString(importedPath, Files.readString(importedPath) + "# tampered\n");
        Map<String, Object> rejected = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));
        assertEquals("error", rejected.get("gate"));
        assertTrue(String.valueOf(rejected.get("findings")).contains("imports.lock_mismatch"));
        assertTrue(String.valueOf(rejected.get("import_lock_verification"))
                .contains("mismatched_entries"));
    }

    @Test
    void lockedGateFailsClosedOnAnUnsavedInMemoryEditOfALockedImport(@TempDir Path temp) throws Exception {
        // The lock pins on-disk bytes while QC validates the LOADED closure. An unsaved in-memory
        // edit of an imported ontology keeps every coordinate and disk hash intact, so a gate that
        // only re-hashes files reports pass while reasoning over axioms matching no lockfile content.
        // The gate must attest the loaded content itself, fail closed on the divergence with its own
        // finding, and recover once the edit is undone.
        Path importedPath = temp.resolve("imported.ttl");
        Files.writeString(importedPath, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/imported> a owl:Ontology .\n");
        String sha = RevisionTools.sha256File(importedPath).substring("sha256:".length());
        Files.writeString(temp.resolve("imports.lock.json"), "{\"version\":1,\"imports\":[{"
                + "\"ontology_iri\":\"https://example.org/imported\","
                + "\"version_iri\":null,\"document\":\"imported.ttl\","
                + "\"sha256\":\"" + sha + "\",\"direct\":true}]}\n");
        Path policy = writePolicy(temp, "[structural]",
                "imports:\n  mode: locked\n  fail_on_missing: true\n"
                        + "  lockfile: imports.lock.json\n  network: deny\n", "error");

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology imported = manager.loadOntologyFromOntologyDocument(importedPath.toFile());
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                manager.getOWLDataFactory().getOWLImportsDeclaration(
                        imported.getOntologyID().getOntologyIRI().get())));
        ToolContext context = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        Map<String, Object> pass = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));
        assertEquals("pass", pass.get("gate"), () -> pass.toString());
        assertEquals(true, ((Map<?, ?>) pass.get("import_lock_verification"))
                        .get("loaded_content_verified"),
                "a clean locked run must attest the loaded content, not only the disk hashes");

        var unsaved = manager.getOWLDataFactory().getOWLDeclarationAxiom(manager.getOWLDataFactory()
                .getOWLClass(IRI.create("https://example.org/imported#UnsavedEdit")));
        manager.addAxiom(imported, unsaved);
        Map<String, Object> rejected = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));
        assertEquals("error", rejected.get("gate"),
                "coordinates and disk hashes still match; only loaded-content attestation catches this");
        assertTrue(String.valueOf(rejected.get("findings")).contains("imports.loaded_content_divergence"),
                () -> rejected.toString());
        Map<?, ?> verification = (Map<?, ?>) rejected.get("import_lock_verification");
        assertEquals(false, verification.get("valid"));
        assertEquals(false, verification.get("loaded_content_verified"));
        assertEquals(List.of(), verification.get("mismatched_entries"),
                "the on-disk bytes still match the lock; the divergence is in the loaded content");

        manager.removeAxiom(imported, unsaved);
        Map<String, Object> recovered = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));
        assertEquals("pass", recovered.get("gate"), () -> recovered.toString());
    }

    @Test
    void moduleOwnedNamespaceViolationAloneFailsTheProjectGate(@TempDir Path temp) throws Exception {
        // Wiring pin, not a unit re-test: ModulePolicyGovernance.moduleChecks reaches the gate only
        // through ProjectQcTools.config → QcRunConfig.projectGovernanceChecks → the governance-stage
        // merge. This policy's ONLY violation is a foreign-namespace definition, so replacing that
        // wiring with an empty list flips this end-to-end gate back to pass.
        writeModuleDocument(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        writeModuleDocument(temp.resolve("foreign.ttl"), "https://example.org/foreign",
                "<https://example.org/ns/Hijacked> a owl:Class .",
                "<https://example.org/ns/Hijacked> rdfs:label \"hijacked\" .");
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("modules", ONTOLOGY_IRI)
                        + "modules:\n"
                        + "  - ontology_iri: https://example.org/owner\n"
                        + "    path: owner.ttl\n"
                        + "    owned_namespaces: ['https://example.org/ns/']\n"
                        + "  - ontology_iri: https://example.org/foreign\n"
                        + "    path: foreign.ttl\n"
                        + "validation:\n  required_stages: [governance]\n  fail_on: error\n");

        Map<String, Object> result = run(temp, policy, false);

        assertEquals("fail", result.get("gate"), () -> result.toString());
        assertTrue(String.valueOf(result.get("findings")).contains("module_owned_namespace"),
                "the module-ownership violation must surface in the gate findings: " + result);
    }

    @Test
    void loadedImportIdentityConflictAloneGatesTheProjectQc(@TempDir Path temp) throws Exception {
        // Wiring pin for the other governance merge: ModulePolicyGovernance.importChecks reaches
        // the gate only through the QC suite's governance-stage merge over the phase-1 import
        // report. Two loaded versions of one logical import are this run's ONLY violation.
        Path policy = writePolicy(temp, "[governance]", "", "error");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        String logical = "https://example.org/upstream";
        manager.createOntology(new org.semanticweb.owlapi.model.OWLOntologyID(
                IRI.create(logical), IRI.create(logical + "/1")));
        manager.createOntology(new org.semanticweb.owlapi.model.OWLOntologyID(
                IRI.create(logical), IRI.create(logical + "/2")));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(IRI.create(logical + "/1"))));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(IRI.create(logical + "/2"))));
        ToolContext context = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        Map<String, Object> result = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));

        // The error-severity check fails the ran governance stage, so the gate verdict is "fail"
        // (a stage that RAN and found violations, unlike the "error" of an unproducible verdict).
        assertEquals("fail", result.get("gate"), () -> result.toString());
        assertTrue(String.valueOf(result.get("findings")).contains("import_identity_conflict"),
                "the loaded import-identity conflict must surface in the gate findings: " + result);
    }

    @Test
    void versionlessPolicyReasonerPassesThePreGateAgainstAVersionedSelection(@TempDir Path temp)
            throws Exception {
        // The pre-gate uses the shared unique-or-fail resolution, not literal name equality: a
        // policy naming 'HermiT' runs against the selected install 'HermiT 1.4.3.456'.
        Path policy = writePolicy(temp, "[structural]",
                "reasoning:\n  reasoner: HermiT\n  required: true\n", "warning");
        ToolContext context = reasonerSelectionContext(temp);

        Map<String, Object> result = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));

        assertFalse(String.valueOf(result.get("findings")).contains("Select the policy reasoner"),
                () -> result.toString());
        org.junit.jupiter.api.Assertions.assertNotEquals("error", result.get("gate"),
                () -> result.toString());
    }

    @Test
    void aSelectionOtherThanThePolicyReasonerStillTripsThePreGate(@TempDir Path temp)
            throws Exception {
        // ELK is installed (so the policy validates) but HermiT 1.4.3.456 is selected.
        Path policy = writePolicy(temp, "[structural]",
                "reasoning:\n  reasoner: ELK\n  required: true\n", "warning");
        ToolContext context = reasonerSelectionContext(temp);

        Map<String, Object> result = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));

        assertEquals("error", result.get("gate"), () -> result.toString());
        String findings = String.valueOf(result.get("findings"));
        assertTrue(findings.contains("qc_configuration_invalid"), findings);
        assertTrue(findings.contains("Select the policy reasoner"), findings);
    }

    @Test
    void registersPolicyToolsWithOptionalPaths() {
        ToolRegistry registry = new ToolRegistry();
        ProjectPolicyTools.register(registry, new ToolContext(null, null));
        assertEquals(List.of("get_project_policy", "validate_project_policy", "run_project_qc",
                        "write_project_policy_template"),
                registry.build().stream().map(s -> s.tool().name()).toList());
        registry.build().forEach(spec -> assertFalse(
                ((List<?>) spec.tool().inputSchema().getOrDefault("required", List.of()))
                        .contains("policy_path")));
    }

    /** A standalone Turtle module document, as in {@link ModulePolicyGovernanceTest}. */
    private static void writeModuleDocument(Path path, String ontology, String... turtle)
            throws Exception {
        StringBuilder document = new StringBuilder(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                        + "<" + ontology + "> a owl:Ontology .\n");
        for (String line : turtle) {
            document.append(line).append('\n');
        }
        Files.writeString(path, document.toString());
    }

    private static Path writePolicy(Path temp, String stages, String extra, String failOn) throws Exception {
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("test", ONTOLOGY_IRI)
                + "validation:\n"
                + "  required_stages: " + stages + "\n"
                + "  fail_on: " + failOn + "\n"
                + extra);
        return policy;
    }

    private static Map<String, Object> run(Path temp, Path policy, boolean withClass) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("policy_path", policy.toString());
        return structured(ProjectQcTools.run(context(temp, withClass), args, true));
    }

    private static ToolContext context(Path temp, boolean withClass) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        if (withClass) {
            OWLDataFactory df = manager.getOWLDataFactory();
            OWLClass cls = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Thing"));
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
        }
        OWLModelManager fake = FakeModelManager.over(ontology);
        return new ToolContext(HeadlessAccess.over(fake), null);
    }

    /**
     * The empty-ontology context plus a live reasoner selection seam: installed inventory
     * ['ELK 0.6.0', 'HermiT 1.4.3.456'] (so both version-less policy names validate) with
     * 'HermiT 1.4.3.456' selected and backed by a real HermiT factory.
     */
    private static ToolContext reasonerSelectionContext(Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo elk =
                reasonerInfo("ELK 0.6.0", "org.semanticweb.elk");
        org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo hermit =
                reasonerInfo("HermiT 1.4.3.456", "org.semanticweb.HermiT");
        java.util.Set<org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo> installed =
                new java.util.LinkedHashSet<>(List.of(elk, hermit));
        var reasoners = (org.protege.editor.owl.model.inference.OWLReasonerManager)
                java.lang.reflect.Proxy.newProxyInstance(ProjectQcToolsTest.class.getClassLoader(),
                new Class<?>[] {org.protege.editor.owl.model.inference.OWLReasonerManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getReasonerStatus" ->
                            org.protege.editor.owl.model.inference.ReasonerStatus
                                    .REASONER_NOT_INITIALIZED;
                    case "getCurrentReasonerFactory" -> hermit;
                    case "getCurrentReasonerFactoryId" -> "org.semanticweb.HermiT";
                    case "getCurrentReasonerName" -> "HermiT 1.4.3.456";
                    case "getInstalledReasonerFactories" -> installed;
                    case "toString" -> "QcPreGateReasonerManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OWLModelManager base = FakeModelManager.over(ontology);
        OWLModelManager mm = (OWLModelManager) java.lang.reflect.Proxy.newProxyInstance(
                ProjectQcToolsTest.class.getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return reasoners;
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new ToolContext(HeadlessAccess.over(mm), null);
    }

    /** A HermiT-backed reasoner plugin descriptor with the given display name and factory id. */
    private static org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo reasonerInfo(
            String name, String id) {
        var factory = new org.semanticweb.HermiT.ReasonerFactory();
        var configuration = new org.semanticweb.owlapi.reasoner.SimpleConfiguration();
        return (org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo)
                java.lang.reflect.Proxy.newProxyInstance(ProjectQcToolsTest.class.getClassLoader(),
                new Class<?>[] {org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getReasonerId" -> id;
                    case "getReasonerName" -> name;
                    case "getReasonerFactory" -> factory;
                    case "getRecommendedBuffering" ->
                            org.semanticweb.owlapi.reasoner.BufferingMode.NON_BUFFERING;
                    case "getConfiguration" -> configuration;
                    case "toString" -> name + " test info";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ToolContext contextWithInfoFinding(Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        var property = df.getOWLObjectProperty(IRI.create(ONTOLOGY_IRI + "#hasPart"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(property));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                property.getIRI(), df.getOWLLiteral("has part")));
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(ontology)), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
