package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.core.workspace.FilesystemProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.ProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceSnapshot;

class HeadlessProjectQcServiceTest {

    private static final String ROOT_IRI = "https://example.org/headless";

    @TempDir
    Path temp;

    @Test
    void executesEveryPolicyStageAgainstOneOfflineSnapshot() throws Exception {
        writeRoot();
        Files.writeString(temp.resolve("invariant.rq"), "# id: no-impossible\n"
                + "# include_inferred: false\nASK { FILTER(false) }\n");
        Path cqs = Files.createDirectories(temp.resolve("cqs"));
        Files.writeString(cqs.resolve("class-exists.rq"),
                "# id: class-exists\n# expected: non-empty\n"
                        + "ASK { ex:Thing a owl:Class }\n");
        Files.writeString(temp.resolve("shapes.ttl"),
                "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                        + "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                        + "[] a sh:NodeShape ; sh:targetClass owl:Class ;\n"
                        + "   sh:property [ sh:path rdfs:label ; sh:minCount 1 ] .\n");
        Path policy = writePolicy("[interoperability, reasoner, profile, governance, structural, "
                + "invariants, cqs, shacl]", "  invariants:\n"
                + "    paths: [invariant.rq]\n"
                + "  competency_questions:\n"
                + "    convention: robot-sparql-dir\n"
                + "    path: cqs\n"
                + "  shacl:\n"
                + "    paths: [shapes.ttl]\n");

        HeadlessProjectQcService.Result result = HeadlessProjectQcService.run(
                new FilesystemProjectWorkspace(policy), new StructuralReasonerFactory(),
                25, LocalDate.of(2026, 7, 19));

        assertEquals(GateStatus.PASS, result.report().gate(), result.output()::toString);
        assertEquals(List.of("interoperability", "reasoner", "profile", "governance",
                        "structural", "invariants", "cqs", "shacl"),
                result.report().stages().stream().map(stage -> stage.execution().stage()).toList());
        assertEquals(8, result.report().stagesRan());
        assertEquals(Boolean.TRUE, result.output().get("snapshot_consistent"));
        assertEquals("headless", result.output().get("surface"));
        assertTrue(String.valueOf(result.output().get("rdf_dataset_fingerprint"))
                .startsWith("sha256:"));
        assertEquals(QcStageVerdict.PASS, stage(result, "cqs").verdict());
    }

    @Test
    void malformedRequiredAssetsBecomeAStageAndGateError() throws Exception {
        writeRoot();
        Files.writeString(temp.resolve("broken.rq"), "# headers but no query\n");
        Path policy = writePolicy("[invariants]", "  invariants:\n"
                + "    paths: [broken.rq]\n");

        HeadlessProjectQcService.Result result = HeadlessProjectQcService.run(
                new FilesystemProjectWorkspace(policy), new StructuralReasonerFactory(),
                25, LocalDate.of(2026, 7, 19));

        assertEquals(GateStatus.ERROR, result.report().gate());
        assertEquals(QcStageVerdict.ERROR, stage(result, "invariants").verdict());
        assertTrue(stage(result, "invariants").message().contains("could not load invariants"));
    }

    @Test
    void sourceDriftAfterCaptureFailsTheAggregateGateClosed() throws Exception {
        writeRoot();
        Path policy = writePolicy("[structural]", "");
        FilesystemProjectWorkspace delegate = new FilesystemProjectWorkspace(policy);
        ProjectWorkspace stale = new ProjectWorkspace() {
            @Override
            public String workspaceId() {
                return delegate.workspaceId();
            }

            @Override
            public WorkspaceSnapshot capture() throws IOException {
                return delegate.capture();
            }

            @Override
            public boolean isCurrent(WorkspaceSnapshot snapshot) {
                return false;
            }
        };

        HeadlessProjectQcService.Result result = HeadlessProjectQcService.run(stale,
                new StructuralReasonerFactory(), 25, LocalDate.of(2026, 7, 19));

        assertEquals(GateStatus.ERROR, result.report().gate());
        assertEquals(Boolean.FALSE, result.output().get("snapshot_consistent"));
        assertTrue(result.report().findings().stream()
                .anyMatch(finding -> "snapshot.changed".equals(finding.id())));
    }

    @Test
    void aRequiredMissingReasonerIsNotAWarningOrPass() throws Exception {
        writeRoot();
        Path policy = writePolicy("[reasoner]", "");

        HeadlessProjectQcService.Result result = HeadlessProjectQcService.run(
                new FilesystemProjectWorkspace(policy), null, 25,
                LocalDate.of(2026, 7, 19));

        assertEquals(GateStatus.ERROR, result.report().gate());
        assertEquals(QcStageVerdict.SKIPPED, stage(result, "reasoner").verdict());
        assertFalse(result.report().missingRequiredStages().isEmpty());
    }

    @Test
    void zeroDisplayLimitCannotHideASelectInvariantViolation() throws Exception {
        writeRoot();
        Files.writeString(temp.resolve("violated.rq"),
                "SELECT ?entity WHERE { ?entity a <http://www.w3.org/2002/07/owl#Class> }\n");
        Path policy = writePolicy("[invariants]", "  invariants:\n"
                + "    paths: [violated.rq]\n");

        HeadlessProjectQcService.Result result = HeadlessProjectQcService.run(
                new FilesystemProjectWorkspace(policy), new StructuralReasonerFactory(),
                0, LocalDate.of(2026, 7, 19));

        assertEquals(GateStatus.FAIL, result.report().gate());
        assertEquals(QcStageVerdict.FAIL, stage(result, "invariants").verdict());
    }

    @Test
    void swrlBlindInferenceOnlyReasonerAddsAGatingErrorStage() throws Exception {
        writeRuleRoot();
        Path cqs = Files.createDirectories(temp.resolve("cqs"));
        Files.writeString(cqs.resolve("anything.rq"), "ASK { ?s ?p ?o }\n");
        Path policy = writePolicy("[cqs]", "  competency_questions:\n"
                + "    convention: robot-sparql-dir\n"
                + "    path: cqs\n");

        HeadlessProjectQcService.Result result = HeadlessProjectQcService.run(
                new FilesystemProjectWorkspace(policy), new StructuralReasonerFactory(),
                25, LocalDate.of(2026, 7, 19));

        assertEquals(GateStatus.ERROR, result.report().gate());
        assertEquals(QcStageVerdict.ERROR, stage(result, "reasoner").verdict());
        assertTrue(stage(result, "reasoner").message().contains("IGNORES rules"));
        assertFalse(result.report().request().requiredStages().contains("reasoner"),
                "the error must surface even when only inferred CQ data scheduled reasoning");
    }

    private static QcStageExecution stage(HeadlessProjectQcService.Result result, String name) {
        return result.report().stages().stream()
                .map(ProjectQcStageReport::execution)
                .filter(execution -> name.equals(execution.stage()))
                .findFirst().orElseThrow();
    }

    private void writeRoot() throws IOException {
        Files.writeString(temp.resolve("root.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                        + "@prefix ex: <" + ROOT_IRI + "#> .\n"
                        + "<" + ROOT_IRI + "> a owl:Ontology .\n"
                        + "ex:Thing a owl:Class ; rdfs:label \"Thing\"@en .\n"
                        + "ex:item a ex:Thing .\n");
        writeCrate();
    }

    private void writeRuleRoot() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create(ROOT_IRI));
        var data = manager.getOWLDataFactory();
        var source = data.getOWLClass(IRI.create(ROOT_IRI + "#Source"));
        var target = data.getOWLClass(IRI.create(ROOT_IRI + "#Target"));
        var variable = data.getSWRLVariable(IRI.create("urn:swrl:var#x"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(source));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(target));
        manager.addAxiom(ontology, data.getSWRLRule(
                java.util.Set.of(data.getSWRLClassAtom(source, variable)),
                java.util.Set.of(data.getSWRLClassAtom(target, variable))));
        manager.saveOntology(ontology, new FunctionalSyntaxDocumentFormat(),
                IRI.create(temp.resolve("root.ttl").toUri()));
        writeCrate();
    }

    private Path writePolicy(String stages, String validationExtra) throws IOException {
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, "version: 1\n"
                + "project_id: headless-qc-test\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + "project_root: .\n"
                + "interoperability:\n"
                + "  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/\n"
                + "  additional_profiles: []\n"
                + "  root_artifact: root.ttl\n"
                + "  metadata:\n"
                + "    path: ro-crate-metadata.json\n"
                + "    format: ro-crate-1.1\n"
                + "  canonicalization:\n"
                + "    algorithm: RDFC-1.0\n"
                + "    hash: SHA-256\n"
                + "    scope: root-ontology\n"
                + "    timeout_ms: 120000\n"
                + "prefixes:\n"
                + "  ex: " + ROOT_IRI + "#\n"
                + "reasoning:\n"
                + "  reasoner: Structural Reasoner\n"
                + "  owl_profile: DL\n"
                + "  timeout_ms: 10000\n"
                + "validation:\n"
                + "  required_stages: " + stages + "\n"
                + "  fail_on: error\n"
                + validationExtra);
        return policy;
    }

    private void writeCrate() throws IOException {
        String profile = "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
        List<Object> graph = new ArrayList<>();
        graph.add(entity("ro-crate-metadata.json", "CreativeWork", "about", ref("./"),
                "conformsTo", ref("https://w3id.org/ro/crate/1.1")));
        graph.add(entity("./", "Dataset", "name", "Headless QC test",
                "description", "Headless QC test project", "datePublished", "2026-07-19",
                "license", "https://www.apache.org/licenses/LICENSE-2.0", "identifier",
                "headless-qc-test", "conformsTo", List.of(ref(profile)),
                "mainEntity", ref("root.ttl"), "hasPart", ref("root.ttl")));
        graph.add(entity(profile, "CreativeWork", "name", "Project profile"));
        graph.add(entity("root.ttl", "File", "encodingFormat", "text/turtle",
                "about", ref(ROOT_IRI)));
        graph.add(entity(ROOT_IRI, "Dataset", "conformsTo",
                ref("https://www.w3.org/TR/owl2-overview/")));
        new ObjectMapper().writeValue(temp.resolve("ro-crate-metadata.json").toFile(),
                Map.of("@context", "https://w3id.org/ro/crate/1.1/context", "@graph", graph));
    }

    private static Map<String, Object> entity(String id, Object type, Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("@id", id);
        result.put("@type", type);
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static Map<String, String> ref(String id) {
        return Map.of("@id", id);
    }
}
