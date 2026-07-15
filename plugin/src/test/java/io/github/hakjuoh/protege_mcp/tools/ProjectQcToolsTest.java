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
        assertEquals(2, result.get("stages_ran"));
        assertEquals(0, result.get("stages_skipped"));
        assertEquals(true, result.get("snapshot_consistent"));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) result.get("validation_snapshot");
        assertEquals("isolated", snapshot.get("mode"));
        assertEquals(true, snapshot.get("same_snapshot"));
        assertEquals(List.of("governance", "structural"), snapshot.get("stages"));
        assertTrue(String.valueOf(result.get("semantic_fingerprint")).startsWith("sha256:"));
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
        assertEquals(3, result.get("stages_ran"));
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
        Files.writeString(mismatch, "version: 1\nproject_id: mismatch\n"
                + "root_ontology: https://example.org/wrong\n"
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
        Files.writeString(policy, "version: 1\nproject_id: default-threshold\n"
                + "root_ontology: " + ONTOLOGY_IRI + "\n"
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
    void registersThreePolicyToolsWithOptionalPaths() {
        ToolRegistry registry = new ToolRegistry();
        ProjectPolicyTools.register(registry, new ToolContext(null, null));
        assertEquals(List.of("get_project_policy", "validate_project_policy", "run_project_qc"),
                registry.build().stream().map(s -> s.tool().name()).toList());
        registry.build().forEach(spec -> assertFalse(
                ((List<?>) spec.tool().inputSchema().getOrDefault("required", List.of()))
                        .contains("policy_path")));
    }

    private static Path writePolicy(Path temp, String stages, String extra, String failOn) throws Exception {
        Path policy = temp.resolve("policy.yaml");
        Files.writeString(policy, "version: 1\n"
                + "project_id: test\n"
                + "root_ontology: " + ONTOLOGY_IRI + "\n"
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
