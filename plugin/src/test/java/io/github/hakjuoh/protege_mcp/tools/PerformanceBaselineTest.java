package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;
import io.github.hakjuoh.protege_mcp.core.owl.VerifiedOntologyRoundTrip;
import io.github.hakjuoh.protege_mcp.core.qc.ShaclValidationService;

/** Opt-in release benchmark over versioned generated ontology fixtures. */
@EnabledIfSystemProperty(named = "protege.performance", matches = "true")
class PerformanceBaselineTest {

    private static final Path FIXTURES = Path.of("performance", "fixtures-v1.json");
    private static final Path BASELINE = Path.of("performance", "baseline-v1.json");
    private static final String SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            [] a sh:NodeShape ;
               sh:targetClass owl:Class ;
               sh:property [ sh:path rdfs:label ; sh:minCount 1 ] .
            """;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static volatile Object consumed;

    @Test
    void representativeWorkloadsStayWithinVersionedRegressionBudgets() throws Exception {
        JsonNode fixtures = JSON.readTree(Files.readAllBytes(FIXTURES));
        JsonNode baseline = JSON.readTree(Files.readAllBytes(BASELINE));
        assertEquals(1, fixtures.path("schema_version").asInt(), "unsupported fixture schema");
        assertEquals(1, baseline.path("schema_version").asInt(), "unsupported baseline schema");
        assertEquals(fixtures.path("schema_version").asInt(),
                baseline.path("fixture_schema_version").asInt(), "fixture/baseline mismatch");
        int warmups = positive(baseline, "warmup_iterations");
        int samples = positive(baseline, "sample_iterations");
        double maximumFactor = baseline.path("maximum_regression_factor").asDouble();
        assertTrue(maximumFactor >= 1.0 && Double.isFinite(maximumFactor),
                "maximum_regression_factor must be finite and at least 1");
        double minimumBudget = baseline.path("minimum_budget_ms").asDouble();
        assertTrue(minimumBudget > 0 && Double.isFinite(minimumBudget),
                "minimum_budget_ms must be finite and positive");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema_version", 1);
        output.put("fixture_schema_version", fixtures.path("schema_version").asInt());
        output.put("baseline", BASELINE.toString());
        output.put("enforced", Boolean.getBoolean("protege.performance.enforce"));
        output.put("environment", environment());
        Map<String, Object> fixtureResults = new LinkedHashMap<>();
        output.put("fixtures", fixtureResults);
        List<String> regressions = new ArrayList<>();

        JsonNode fixtureArray = fixtures.path("fixtures");
        assertTrue(fixtureArray.isArray() && fixtureArray.size() == 3,
                "fixtures must contain small, medium, and large entries");
        Set<String> seenFixtures = new LinkedHashSet<>();
        for (JsonNode node : fixtureArray) {
            Fixture fixture = Fixture.parse(node);
            assertTrue(seenFixtures.add(fixture.name()),
                    "duplicate fixture name: " + fixture.name());
            OWLOntology ontology = ontology(fixture);
            OWLOntology right = changedCopy(ontology, fixture.name());
            byte[] turtle = SparqlTools.toTurtleBytes(ontology);
            var modelManager = FakeModelManager.over(ontology);

            Map<String, TimedOperation> operations = new LinkedHashMap<>();
            operations.put("snapshot_capture", () ->
                    IsolatedValidationSnapshot.capture(modelManager));
            operations.put("reasoning", () -> classify(ontology));
            operations.put("sparql_cache_build", () -> {
                SparqlTools.Snapshot snapshot = SparqlTools.snapshot(ontology.getOntologyID(),
                        Set.of(ontology), Map.of(), null, false);
                return SparqlTools.toTurtleBytes(snapshot.ontology());
            });
            operations.put("shacl", () -> ShaclValidationService.validate(
                    turtle, SHAPES, null, 50, 120_000));
            operations.put("semantic_diff", () ->
                    SemanticDiffService.diff(ontology, right, false, 50));
            operations.put("verified_serialization", () -> VerifiedOntologyRoundTrip.serialize(
                    ontology, new FunctionalSyntaxDocumentFormat()));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("classes", fixture.classes());
            result.put("object_properties", fixture.objectProperties());
            result.put("individuals", fixture.individuals());
            result.put("axioms", ontology.getAxiomCount());
            Map<String, Object> metrics = new LinkedHashMap<>();
            result.put("metrics", metrics);
            fixtureResults.put(fixture.name(), result);
            JsonNode references = baseline.path("reference_ms").path(fixture.name());
            assertTrue(references.isObject(), "missing baseline fixture " + fixture.name());

            for (Map.Entry<String, TimedOperation> operation : operations.entrySet()) {
                Measurement measurement = measure(operation.getValue(), warmups, samples);
                double reference = references.path(operation.getKey()).asDouble(-1);
                assertTrue(reference > 0 && Double.isFinite(reference),
                        "missing positive reference for " + fixture.name() + "/" + operation.getKey());
                double budget = regressionBudget(reference, maximumFactor, minimumBudget);
                boolean passed = measurement.medianMs() <= budget;
                Map<String, Object> metric = new LinkedHashMap<>();
                metric.put("median_ms", measurement.medianMs());
                metric.put("samples_ms", measurement.samplesMs());
                metric.put("reference_ms", reference);
                metric.put("maximum_ms", round(budget));
                metric.put("ratio", round(measurement.medianMs() / reference));
                metric.put("status", passed ? "pass" : "regression");
                metrics.put(operation.getKey(), metric);
                if (!passed) {
                    regressions.add(fixture.name() + "/" + operation.getKey() + " median "
                            + measurement.medianMs() + " ms exceeds " + round(budget) + " ms");
                }
            }
        }
        assertEquals(Set.of("small", "medium", "large"), seenFixtures,
                "fixtures must contain each representative size exactly once");

        Path outputPath = Path.of(System.getProperty("protege.performance.output",
                "plugin/target/performance-results.json"));
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(outputPath, JSON.writeValueAsBytes(output));
        assertFalse(Boolean.getBoolean("protege.performance.enforce") && !regressions.isEmpty(),
                () -> "performance regressions: " + String.join("; ", regressions));
    }

    private static Object classify(OWLOntology ontology) {
        OWLReasoner reasoner = new org.semanticweb.HermiT.ReasonerFactory()
                .createReasoner(ontology);
        try {
            reasoner.precomputeInferences(
                    InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);
            return Map.of("consistent", reasoner.isConsistent(),
                    "unsatisfiable", reasoner.getUnsatisfiableClasses()
                            .getEntitiesMinusBottom().size());
        } finally {
            reasoner.dispose();
        }
    }

    private static Measurement measure(TimedOperation operation, int warmups, int samples)
            throws Exception {
        for (int i = 0; i < warmups; i++) consumed = operation.run();
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            long started = System.nanoTime();
            consumed = operation.run();
            values[i] = round((System.nanoTime() - started) / 1_000_000.0);
        }
        Arrays.sort(values);
        return new Measurement(values[values.length / 2], Arrays.stream(values).boxed().toList());
    }

    static OWLOntology ontology(Fixture fixture) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(
                "https://example.org/protege-mcp/performance/" + fixture.name()));
        var data = manager.getOWLDataFactory();
        String base = ontology.getOntologyID().getOntologyIRI().orNull() + "#";
        List<OWLClass> classes = new ArrayList<>(fixture.classes());
        for (int i = 0; i < fixture.classes(); i++) {
            OWLClass type = data.getOWLClass(IRI.create(base + "Class" + i));
            classes.add(type);
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(type));
            manager.addAxiom(ontology, data.getOWLAnnotationAssertionAxiom(data.getRDFSLabel(),
                    type.getIRI(), data.getOWLLiteral("Class " + i, "en")));
            if (i > 0) {
                manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(
                        type, classes.get((i - 1) / 4)));
            }
        }
        List<OWLObjectProperty> properties = new ArrayList<>(fixture.objectProperties());
        for (int i = 0; i < fixture.objectProperties(); i++) {
            OWLObjectProperty property = data.getOWLObjectProperty(IRI.create(base + "property" + i));
            properties.add(property);
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(property));
            if (i > 0) {
                manager.addAxiom(ontology, data.getOWLSubObjectPropertyOfAxiom(
                        property, properties.get((i - 1) / 4)));
            }
        }
        if (!properties.isEmpty()) {
            manager.addAxiom(ontology, data.getOWLTransitiveObjectPropertyAxiom(properties.get(0)));
        }
        if (properties.size() >= 3) {
            manager.addAxiom(ontology, data.getOWLInverseObjectPropertiesAxiom(
                    properties.get(1), properties.get(2)));
        }
        if (properties.size() >= 4) {
            manager.addAxiom(ontology, data.getOWLSymmetricObjectPropertyAxiom(properties.get(3)));
        }
        for (int i = 20; i < classes.size(); i += 20) {
            OWLClass type = classes.get(i);
            OWLClass parent = classes.get((i - 1) / 4);
            OWLObjectProperty property = properties.get(i % properties.size());
            manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(type,
                    data.getOWLObjectSomeValuesFrom(property, parent)));
        }
        for (int i = 100; i < classes.size(); i += 100) {
            OWLClass type = classes.get(i);
            OWLClass parent = classes.get((i - 1) / 4);
            OWLObjectProperty property = properties.get(i % properties.size());
            manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(Set.of(type,
                    data.getOWLObjectIntersectionOf(parent,
                            data.getOWLObjectSomeValuesFrom(property, classes.get(0))))));
        }
        for (int i = 1; i + 3 < classes.size(); i += 4) {
            manager.addAxiom(ontology, data.getOWLDisjointClassesAxiom(
                    new LinkedHashSet<>(classes.subList(i, i + 4))));
        }
        for (int i = 200; i < classes.size(); i += 200) {
            OWLObjectProperty property = properties.get(i % properties.size());
            manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(
                    data.getOWLObjectIntersectionOf(classes.get(i - 1),
                            data.getOWLObjectSomeValuesFrom(property, classes.get(0))),
                    classes.get(i)));
        }
        var score = data.getOWLDataProperty(IRI.create(base + "score"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(score));
        List<OWLNamedIndividual> individuals = new ArrayList<>(fixture.individuals());
        for (int i = 0; i < fixture.individuals(); i++) {
            OWLNamedIndividual individual = data.getOWLNamedIndividual(IRI.create(base + "item" + i));
            individuals.add(individual);
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(individual));
            manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(
                    classes.get(i % classes.size()), individual));
            manager.addAxiom(ontology, data.getOWLDataPropertyAssertionAxiom(
                    score, individual, data.getOWLLiteral("score-" + i)));
            if (i > 0 && !properties.isEmpty()) {
                manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(
                        properties.get(i % properties.size()), individual, individuals.get(i - 1)));
            }
        }
        return ontology;
    }

    private static OWLOntology changedCopy(OWLOntology source, String name) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology copy = manager.createOntology(source.getOntologyID());
        manager.addAxioms(copy, source.getAxioms());
        var data = manager.getOWLDataFactory();
        IRI added = IRI.create("https://example.org/protege-mcp/performance/" + name + "#Added");
        manager.addAxiom(copy, data.getOWLDeclarationAxiom(data.getOWLClass(added)));
        manager.addAxiom(copy, data.getOWLAnnotationAssertionAxiom(
                data.getRDFSLabel(), added, data.getOWLLiteral("Added", "en")));
        return copy;
    }

    private static int positive(JsonNode node, String field) {
        int value = node.path(field).asInt();
        assertTrue(value > 0 && value <= 20, field + " must be between 1 and 20");
        return value;
    }

    private static Map<String, Object> environment() {
        return Map.of(
                "java_version", System.getProperty("java.version"),
                "java_vm", System.getProperty("java.vm.name"),
                "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
                "arch", System.getProperty("os.arch"),
                "processors", Runtime.getRuntime().availableProcessors(),
                "max_heap_bytes", Runtime.getRuntime().maxMemory());
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    static double regressionBudget(double reference, double factor, double minimum) {
        double scaled = reference * factor;
        if (!(reference > 0) || !(factor >= 1) || !(minimum > 0)
                || !Double.isFinite(scaled) || !Double.isFinite(minimum)) {
            throw new IllegalArgumentException("performance budget inputs must be finite and positive");
        }
        return Math.max(minimum, scaled);
    }

    record Fixture(String name, int classes, int objectProperties, int individuals) {
        private static Fixture parse(JsonNode node) {
            String name = node.path("name").asText();
            assertTrue(Set.of("small", "medium", "large").contains(name),
                    "unexpected fixture name: " + name);
            int classes = bounded(node, "classes", 1, 100_000);
            int properties = bounded(node, "object_properties", 1, 10_000);
            int individuals = bounded(node, "individuals", 0, 100_000);
            return new Fixture(name, classes, properties, individuals);
        }

        private static int bounded(JsonNode node, String field, int minimum, int maximum) {
            int value = node.path(field).asInt(-1);
            assertTrue(value >= minimum && value <= maximum,
                    field + " must be between " + minimum + " and " + maximum);
            return value;
        }
    }

    private record Measurement(double medianMs, List<Double> samplesMs) { }

    @FunctionalInterface
    private interface TimedOperation {
        Object run() throws Exception;
    }
}
