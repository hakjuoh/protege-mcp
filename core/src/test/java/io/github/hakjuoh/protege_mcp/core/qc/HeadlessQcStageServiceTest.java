package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

class HeadlessQcStageServiceTest {

    private static final String ROOT_IRI = "https://example.org/qc";

    @TempDir
    Path temp;

    @Test
    void computesPolicyPinnedInteroperabilityIdentity() throws Exception {
        OWLOntology ontology = ontology();
        ProjectPolicy policy = policy();

        QcStageExecution result = HeadlessQcStageService.interoperability(ontology, policy);

        assertEquals(QcStageVerdict.PASS, result.verdict());
        assertTrue(String.valueOf(result.details().get("rdf_dataset_fingerprint"))
                .startsWith("sha256:"));
        assertEquals("RDFC-1.0", result.details().get("canonicalization_algorithm"));
    }

    @Test
    void checksOwnedProfileAndSupportsAnExplicitNoneProfile() throws Exception {
        OWLOntology ontology = ontology();
        var data = ontology.getOWLOntologyManager().getOWLDataFactory();
        var property = data.getOWLDataProperty(IRI.create(ROOT_IRI + "#value"));
        var clazz = data.getOWLClass(IRI.create(ROOT_IRI + "#Measured"));
        ontology.getOWLOntologyManager().addAxiom(ontology,
                data.getOWLDeclarationAxiom(property));
        ontology.getOWLOntologyManager().addAxiom(ontology,
                data.getOWLDeclarationAxiom(clazz));
        ontology.getOWLOntologyManager().addAxiom(ontology,
                data.getOWLSubClassOfAxiom(clazz,
                        data.getOWLDataExactCardinality(2, property)));

        QcStageExecution el = HeadlessQcStageService.profile(ontology, "EL", 10);
        QcStageExecution none = HeadlessQcStageService.profile(ontology, "NONE", 10);
        QcStageExecution prefixed = HeadlessQcStageService.profile(ontology, "OWL2_DL", 10);

        assertEquals(QcStageVerdict.FAIL, el.verdict());
        assertFalse(Boolean.TRUE.equals(el.details().get("owned_in_profile")));
        assertEquals(QcStageVerdict.SKIPPED, none.verdict());
        assertEquals(QcStageVerdict.PASS, prefixed.verdict());
    }

    @Test
    void doesNotFalseFailRootAxiomsThatUseEntitiesDeclaredByAnImport() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create(ROOT_IRI));
        IRI importedIri = IRI.create("https://example.org/imported");
        OWLOntology imported = manager.createOntology(importedIri);
        var data = manager.getOWLDataFactory();
        var local = data.getOWLClass(IRI.create(ROOT_IRI + "#Local"));
        var upstream = data.getOWLClass(IRI.create(importedIri + "#Upstream"));
        manager.addAxiom(root, data.getOWLDeclarationAxiom(local));
        manager.addAxiom(imported, data.getOWLDeclarationAxiom(upstream));
        manager.addAxiom(root, data.getOWLSubClassOfAxiom(local, upstream));
        manager.applyChange(new AddImport(root, data.getOWLImportsDeclaration(importedIri)));

        QcStageExecution result = HeadlessQcStageService.profile(root, "DL", 10);

        assertEquals(QcStageVerdict.PASS, result.verdict(), () -> result.details().toString());
        assertEquals(Boolean.TRUE, result.details().get("owned_in_profile"));
    }

    @Test
    void runsAnInjectedReasonerAndRejectsARequiredNameMismatch() throws Exception {
        OWLOntology ontology = ontology();
        OWLReasonerFactory factory = new StructuralReasonerFactory();

        QcStageExecution pass = HeadlessQcStageService.reasoner(
                ontology, factory, null, 5_000, 10);
        QcStageExecution mismatch = HeadlessQcStageService.reasoner(
                ontology, factory, "HermiT", 5_000, 10);

        assertEquals(QcStageVerdict.PASS, pass.verdict());
        assertEquals(Boolean.TRUE, pass.details().get("consistent"));
        assertEquals(QcStageVerdict.ERROR, mismatch.verdict());
        assertTrue(mismatch.message().contains("requires reasoner"));
    }

    @Test
    void boundsAReasonerFactoryThatIgnoresTheOuterThreadUntilCreation() throws Exception {
        OWLOntology ontology = ontology();

        QcStageExecution timedOut = HeadlessQcStageService.reasoner(
                ontology, new SleepingFactory(200), null, 10, 10);

        assertEquals(QcStageVerdict.ERROR, timedOut.verdict());
        assertTrue(timedOut.message().contains("timed out"), timedOut::message);
    }

    @Test
    void materializesQueryInferencesWithTheSamePrivateReasoner() throws Exception {
        OWLOntology ontology = ontology();
        var data = ontology.getOWLOntologyManager().getOWLDataFactory();
        var narrower = data.getOWLClass(IRI.create(ROOT_IRI + "#Narrower"));
        var broader = data.getOWLClass(IRI.create(ROOT_IRI + "#Broader"));
        var item = data.getOWLNamedIndividual(IRI.create(ROOT_IRI + "#item"));
        ontology.getOWLOntologyManager().addAxiom(ontology,
                data.getOWLSubClassOfAxiom(narrower, broader));
        ontology.getOWLOntologyManager().addAxiom(ontology,
                data.getOWLClassAssertionAxiom(narrower, item));
        QuerySnapshot queries = QuerySnapshot.capture(ontology, ontology.getImportsClosure(),
                Map.of());

        HeadlessQcStageService.ReasoningOutcome outcome = HeadlessQcStageService.reason(
                ontology, new StructuralReasonerFactory(), null, 5_000, 10, queries, true);

        assertEquals(QcStageVerdict.PASS, outcome.execution().verdict());
        assertTrue(outcome.querySnapshot().inferredAvailable());
        Map<String, Object> result = outcome.querySnapshot().execute(
                "ASK { <" + item.getIRI() + "> a <" + broader.getIRI() + "> }", true,
                10, 5_000);
        assertEquals(Boolean.TRUE, result.get("boolean"));
    }

    @Test
    void failedReasonerSelectionMarksRequestedInferenceUnavailable() throws Exception {
        OWLOntology ontology = ontology();
        QuerySnapshot queries = QuerySnapshot.capture(ontology, ontology.getImportsClosure(),
                Map.of());

        HeadlessQcStageService.ReasoningOutcome outcome = HeadlessQcStageService.reason(
                ontology, new StructuralReasonerFactory(), "HermiT", 5_000, 10,
                queries, true);

        assertEquals(QcStageVerdict.ERROR, outcome.execution().verdict());
        assertFalse(outcome.querySnapshot().inferredAvailable());
        assertTrue(outcome.querySnapshot().inferredError().contains("requires reasoner"));
    }

    @Test
    void preservesClassificationWhenOnlyInferenceMaterializationFails() throws Exception {
        OWLOntology ontology = ontology();
        QuerySnapshot queries = QuerySnapshot.capture(ontology, ontology.getImportsClosure(),
                Map.of());

        HeadlessQcStageService.ReasoningOutcome outcome = HeadlessQcStageService.reason(
                ontology, new MaterializationFailureFactory(), null, 5_000, 10,
                queries, true);

        assertEquals(QcStageVerdict.PASS, outcome.execution().verdict());
        assertFalse(outcome.querySnapshot().inferredAvailable());
        assertTrue(outcome.querySnapshot().inferredError()
                .contains("materialization failed after classification completed"));
    }

    @Test
    void rejectsAQuerySnapshotCapturedFromAnotherClosure() throws Exception {
        OWLOntology root = ontology();
        OWLOntology other = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/other"));
        QuerySnapshot wrong = QuerySnapshot.capture(other, other.getImportsClosure(), Map.of());

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> HeadlessQcStageService.reason(
                        root, new StructuralReasonerFactory(), null, 5_000, 10, wrong, true));

        assertTrue(error.getMessage().contains("current root import closure"));
    }

    @Test
    void materializationTimeoutMarksInferenceUnavailable() throws Exception {
        OWLOntology ontology = ontology();
        QuerySnapshot queries = QuerySnapshot.capture(ontology, ontology.getImportsClosure(),
                Map.of());

        HeadlessQcStageService.ReasoningOutcome outcome = HeadlessQcStageService.reason(
                ontology, new SleepingFactory(200), null, 10, 10, queries, true);

        assertEquals(QcStageVerdict.ERROR, outcome.execution().verdict());
        assertFalse(outcome.querySnapshot().inferredAvailable());
        assertTrue(outcome.querySnapshot().inferredError().contains("timed out"));
    }

    private OWLOntology ontology() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ROOT_IRI));
        var data = manager.getOWLDataFactory();
        var clazz = data.getOWLClass(IRI.create(ROOT_IRI + "#Thing"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(clazz));
        manager.addAxiom(ontology, data.getOWLAnnotationAssertionAxiom(
                data.getRDFSLabel(), clazz.getIRI(), data.getOWLLiteral("Thing", "en")));
        return ontology;
    }

    private ProjectPolicy policy() throws Exception {
        Path root = temp.resolve("root.ttl");
        Files.writeString(root, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + ROOT_IRI + "> a owl:Ontology .\n");
        String profile = "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
        List<Object> graph = List.of(
                Map.of("@id", "ro-crate-metadata.json", "@type", "CreativeWork",
                        "about", Map.of("@id", "./"), "conformsTo",
                        Map.of("@id", "https://w3id.org/ro/crate/1.1")),
                Map.ofEntries(Map.entry("@id", "./"), Map.entry("@type", "Dataset"),
                        Map.entry("name", "QC test"), Map.entry("description", "QC test project"),
                        Map.entry("datePublished", "2026-07-19"),
                        Map.entry("license", "https://www.apache.org/licenses/LICENSE-2.0"),
                        Map.entry("identifier", "qc-test"),
                        Map.entry("conformsTo", List.of(Map.of("@id", profile))),
                        Map.entry("mainEntity", Map.of("@id", "root.ttl")),
                        Map.entry("hasPart", Map.of("@id", "root.ttl"))),
                Map.of("@id", profile, "@type", "CreativeWork", "name", "Project profile"),
                Map.of("@id", "root.ttl", "@type", "File", "encodingFormat", "text/turtle",
                        "about", Map.of("@id", ROOT_IRI)),
                Map.of("@id", ROOT_IRI, "@type", "Dataset", "conformsTo",
                        Map.of("@id", "https://www.w3.org/TR/owl2-overview/")));
        new ObjectMapper().writeValue(temp.resolve("ro-crate-metadata.json").toFile(),
                Map.of("@context", "https://w3id.org/ro/crate/1.1/context", "@graph", graph));
        Path path = temp.resolve("project.yaml");
        Files.writeString(path, "version: 1\n"
                + "project_id: qc-test\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + "interoperability:\n"
                + "  profile: " + profile + "\n"
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
                + "validation:\n"
                + "  required_stages: [interoperability]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return policy;
    }

    private static final class SleepingFactory implements OWLReasonerFactory {
        private final long delayMs;
        private final OWLReasonerFactory delegate = new StructuralReasonerFactory();

        SleepingFactory(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public String getReasonerName() {
            return "Sleeping Structural";
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            long deadline = System.nanoTime() + delayMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return delegate.createReasoner(ontology, configuration);
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            return delegate.createNonBufferingReasoner(ontology, configuration);
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            return delegate.createReasoner(ontology);
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            return delegate.createNonBufferingReasoner(ontology);
        }
    }

    private static final class MaterializationFailureFactory implements OWLReasonerFactory {
        private final OWLReasonerFactory delegate = new StructuralReasonerFactory();

        @Override
        public String getReasonerName() {
            return "Materialization failure test";
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            return failAfterClassification(delegate.createReasoner(ontology, configuration));
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            return createReasoner(ontology, configuration);
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            return failAfterClassification(delegate.createReasoner(ontology));
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            return createReasoner(ontology);
        }

        private static OWLReasoner failAfterClassification(OWLReasoner reasoner) {
            java.util.concurrent.atomic.AtomicBoolean classified =
                    new java.util.concurrent.atomic.AtomicBoolean();
            return (OWLReasoner) Proxy.newProxyInstance(
                    OWLReasoner.class.getClassLoader(), new Class<?>[] {OWLReasoner.class},
                    (proxy, method, arguments) -> {
                        if (classified.get() && !"dispose".equals(method.getName())) {
                            throw new IllegalStateException("synthetic materialization failure");
                        }
                        try {
                            Object result = method.invoke(reasoner, arguments);
                            if ("getUnsatisfiableClasses".equals(method.getName())) {
                                classified.set(true);
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
