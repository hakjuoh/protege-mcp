package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

/**
 * ADR 0004 decision 3: the CQ, invariant, SHACL, and rule-driven governance result cores publish
 * their COMPLETE per-row finding identities through the non-serialized
 * {@code QcStageResult.attributionIdentities} side channel, without changing any released JSON
 * result field (the reserved in-map key never leaks into a stage summary).
 */
class QcStageAttributionIdentitiesTest {

    private static final String NS = "http://example.org/attr#";

    @Test
    void invariantsStageExposesTheViolatedRowIdentities() throws Exception {
        SuiteSnapshot snap = snapshot();
        List<Invariants.Invariant> invariants = List.of(new Invariants.Invariant(
                "no-unlabelled", "classes need labels", "error",
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?l } }",
                false));

        QcStageResult stage = QcSuiteTools.invariantsStage(snap, invariants, 1000, 30_000);

        assertEquals("fail", stage.verdict);
        assertNotNull(stage.attributionIdentities(), "side channel must be populated");
        assertEquals(1, stage.attributionIdentities().size());
        assertTrue(stage.attributionIdentities().iterator().next().contains("no-unlabelled"));
    }

    @Test
    void cqsStageExposesTheFailedRowIdentities() throws Exception {
        SuiteSnapshot snap = snapshot();
        CompetencyQuestion failing = new CompetencyQuestion();
        failing.id = "has-labels";
        failing.query = "SELECT ?l WHERE { ?s rdfs:label ?l }";
        failing.expected = Expectation.nonEmpty();
        failing.includeInferred = false;
        failing.convention = Cq.CONV_MANIFEST;
        CompetencyQuestion passing = new CompetencyQuestion();
        passing.id = "has-classes";
        passing.query = "SELECT ?c WHERE { ?c a owl:Class }";
        passing.expected = Expectation.nonEmpty();
        passing.includeInferred = false;
        passing.convention = Cq.CONV_MANIFEST;

        QcStageResult stage = QcSuiteTools.cqsStage(snap, List.of(failing, passing), 1000, 30_000);

        assertEquals("fail", stage.verdict);
        assertNotNull(stage.attributionIdentities());
        assertEquals(1, stage.attributionIdentities().size(),
                "only the FAILED row feeds attribution");
        assertTrue(stage.attributionIdentities().iterator().next().contains("has-labels"));
    }

    @Test
    void shaclStageExposesTheGatingResultIdentities() throws Exception {
        SuiteSnapshot snap = snapshot();
        String shapes = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                + "@prefix ex: <" + NS + "> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "ex:UnlabelledShape a sh:NodeShape ; sh:targetNode ex:Unlabelled ;\n"
                + "  sh:property [ sh:path rdfs:label ; sh:minCount 1 ] .\n";

        QcStageResult stage = QcSuiteTools.shaclStage(snap.assertedTurtle(), shapes, null,
                1000, 30_000);

        assertEquals("fail", stage.verdict);
        assertNotNull(stage.attributionIdentities());
        assertEquals(1, stage.attributionIdentities().size());
        assertTrue(stage.attributionIdentities().iterator().next().contains(NS + "Unlabelled"));
    }

    @Test
    void governanceStageExposesRuleAndModuleRowIdentitiesWithoutLeakingTheSideChannel(
            @TempDir Path temp) throws Exception {
        // Rule-driven PolicyGovernance rows: a declared class without the required definition.
        PolicyGovernance.Rules rules = new PolicyGovernance.Rules(List.of(), Set.of(), false,
                List.of(IRI.create("http://www.w3.org/2004/02/skos/core#definition")), true,
                Set.of(), null, Set.of(), Set.of(), List.of(), true, List.of());
        // Module project checks: a term defined outside its owning module.
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        writeModule(temp.resolve("foreign.ttl"), "https://example.org/foreign",
                "<https://example.org/ns/Hijacked> a owl:Class .",
                "<https://example.org/ns/Hijacked> rdfs:label \"hijacked\" .");
        Path policyPath = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("attr", "https://example.org/owner")
                        + "modules:\n"
                        + "  - ontology_iri: https://example.org/owner\n"
                        + "    path: owner.ttl\n"
                        + "    owned_namespaces: ['https://example.org/ns/']\n"
                        + "  - ontology_iri: https://example.org/foreign\n"
                        + "    path: foreign.ttl\n"
                        + "validation:\n  required_stages: [governance]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        List<Map<String, Object>> projectChecks = ModulePolicyGovernance.moduleChecks(policy, 25);
        assertFalse(projectChecks.isEmpty());

        IsolatedValidationSnapshot snapshot =
                IsolatedValidationSnapshot.capture(FakeModelManager.over(ontology()));
        QcStageResult stage = QcSuiteTools.governanceStage(snapshot, null, List.of(), List.of(),
                true, rules, projectChecks, 25);

        assertNotNull(stage.attributionIdentities());
        assertTrue(stage.attributionIdentities().stream()
                        .anyMatch(id -> id.startsWith("annotation.definition.required|")),
                stage.attributionIdentities()::toString);
        assertTrue(stage.attributionIdentities().stream()
                        .anyMatch(id -> id.startsWith("module_owned_namespace|")),
                stage.attributionIdentities()::toString);
        // The reserved side-channel key never reaches the serialized stage summary...
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> serialized =
                (List<Map<String, Object>>) stage.summary.get("checks");
        assertTrue(serialized.stream()
                        .noneMatch(check -> check.containsKey(ModulePolicyGovernance.ATTRIBUTION_KEY)),
                "the reserved side-channel key must never be serialized");
        // ...and the config-owned module check maps stay intact for the baseline re-run.
        assertTrue(projectChecks.stream()
                        .anyMatch(check -> check.containsKey(ModulePolicyGovernance.ATTRIBUTION_KEY)),
                "governanceStage must not drain the caller's maps");
    }

    // ------------------------------------------------------------------ fixtures

    /** One ontology with a single unlabelled class. */
    private static SuiteSnapshot snapshot() throws Exception {
        OWLOntology ontology = ontology();
        return SuiteSnapshot.captureIsolated(ontology, Set.of(ontology), Map.of(
                "owl:", "http://www.w3.org/2002/07/owl#",
                "rdfs:", "http://www.w3.org/2000/01/rdf-schema#"));
    }

    private static OWLOntology ontology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "attr"));
        OWLDataFactory df = manager.getOWLDataFactory();
        var unlabelled = df.getOWLClass(IRI.create(NS + "Unlabelled"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(unlabelled));
        return ontology;
    }

    private static void writeModule(Path path, String ontologyIri, String... turtle)
            throws Exception {
        StringBuilder document = new StringBuilder(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                        + "<" + ontologyIri + "> a owl:Ontology .\n");
        for (String line : turtle) {
            document.append(line).append('\n');
        }
        Files.writeString(path, document.toString());
    }
}
