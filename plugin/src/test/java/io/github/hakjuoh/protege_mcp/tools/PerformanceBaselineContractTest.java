package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.model.OWLAxiom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.owl.OwlDocumentSemantics;

class PerformanceBaselineContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> OPERATIONS = List.of(
            "snapshot_capture", "reasoning", "sparql_cache_build", "shacl",
            "semantic_diff", "verified_serialization");

    @Test
    void baselineAndFixtureVersionsStayCompleteAndInLockstep() throws Exception {
        JsonNode fixtures = JSON.readTree(Files.readAllBytes(
                Path.of("performance", "fixtures-v1.json")));
        JsonNode baseline = JSON.readTree(Files.readAllBytes(
                Path.of("performance", "baseline-v1.json")));

        assertEquals(1, fixtures.path("schema_version").asInt());
        assertEquals(fixtures.path("schema_version").asInt(),
                baseline.path("fixture_schema_version").asInt());
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode fixture : fixtures.path("fixtures")) {
            assertTrue(names.add(fixture.path("name").asText()), "fixture names must be unique");
        }
        assertEquals(Set.of("small", "medium", "large"), names);
        for (String name : names) {
            JsonNode references = baseline.path("reference_ms").path(name);
            assertTrue(references.isObject(), "missing references for " + name);
            for (String operation : OPERATIONS) {
                double reference = references.path(operation).asDouble(-1);
                assertTrue(reference > 0 && Double.isFinite(reference),
                        "missing finite reference for " + name + "/" + operation);
            }
            assertEquals(OPERATIONS.size(), references.size(),
                    "unexpected workload in " + name + " baseline");
        }
    }

    @Test
    void regressionBudgetUsesANoiseFloorAndRejectsOverflowOrInvalidInputs() {
        assertEquals(250.0, PerformanceBaselineTest.regressionBudget(20, 5, 250));
        assertEquals(1000.0, PerformanceBaselineTest.regressionBudget(200, 5, 250));
        assertThrows(IllegalArgumentException.class,
                () -> PerformanceBaselineTest.regressionBudget(Double.MAX_VALUE, 5, 250));
        assertThrows(IllegalArgumentException.class,
                () -> PerformanceBaselineTest.regressionBudget(20, 0, 250));
        assertThrows(IllegalArgumentException.class,
                () -> PerformanceBaselineTest.regressionBudget(20, 5, Double.NaN));
    }

    @Test
    void expressiveFixtureRoundTripsWithoutSemanticLoss() throws Exception {
        var ontology = PerformanceBaselineTest.ontology(
                new PerformanceBaselineTest.Fixture("contract", 250, 4, 25));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ontology.getOWLOntologyManager().saveOntology(
                ontology, new FunctionalSyntaxDocumentFormat(), bytes);
        var reloaded = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new ByteArrayInputStream(bytes.toByteArray()));
        Set<OWLAxiom> expected = OwlDocumentSemantics.normalizedAxioms(ontology);
        Set<OWLAxiom> actual = OwlDocumentSemantics.normalizedAxioms(reloaded);
        Set<OWLAxiom> removed = new LinkedHashSet<>(expected);
        removed.removeAll(actual);
        Set<OWLAxiom> added = new LinkedHashSet<>(actual);
        added.removeAll(expected);

        assertEquals(expected, actual, () -> "fixture round-trip changed axioms; removed="
                + removed.stream().limit(5).toList() + ", added="
                + added.stream().limit(5).toList());
    }
}
