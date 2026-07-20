package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;

class ValidationAssetLoaderTest {

    @Test
    void loadsInvariantHeadersAndRejectsDuplicateIds(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("first.rq");
        Path second = directory.resolve("second.rq");
        Files.writeString(first, "# id: no-person\n# severity: warning\n"
                + "# include_inferred: true\nASK { ?s a <https://example.org/Person> }\n");
        Files.writeString(second, "# id: no-person\nASK {}\n");

        InvariantQcService.Invariant invariant = ValidationAssetLoader.readInvariant(first);
        assertEquals("no-person", invariant.id());
        assertEquals("warn", invariant.severity());
        assertTrue(invariant.includeInferred());
        assertThrows(ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.loadInvariants(List.of(first, second)));
    }

    @Test
    void rejectsInvalidInvariantBooleansAndBoundedReads(@TempDir Path directory) throws Exception {
        Path invalid = directory.resolve("invalid.rq");
        Files.writeString(invalid, "# include_inferred: maybe\nASK {}\n");
        Path oversized = directory.resolve("oversized.rq");
        Files.write(oversized, new byte[1_048_577]);
        Path nonRegular = Files.createDirectory(directory.resolve("directory.rq"));

        assertThrows(ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.readInvariant(invalid));
        assertThrows(ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.readInvariant(oversized));
        assertThrows(ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.readInvariant(nonRegular));
    }

    @Test
    void loadsRobotQuestionsDeterministically(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("b.rq"), "# id: b\n# expected: count >= 2\n"
                + "# include_inferred: false\nSELECT * WHERE { ?s ?p ?o }\n");
        Files.writeString(directory.resolve("a.rq"), "ASK {}\n");

        List<CompetencyQuestionService.Question> questions = ValidationAssetLoader.loadQuestions(
                ValidationAssetLoader.ROBOT, List.of(directory), null);

        assertEquals(List.of("a", "b"), questions.stream()
                .map(CompetencyQuestionService.Question::id).toList());
        assertEquals(CompetencyQuestionService.ExpectationKind.COUNT,
                questions.get(1).expected().kind());
        assertFalse(questions.get(1).includeInferred());
    }

    @Test
    void rejectsRobotSymlinksEscapingTheSelectedDirectory(@TempDir Path directory)
            throws Exception {
        Path selected = Files.createDirectory(directory.resolve("cqs"));
        Path outside = directory.resolve("outside.rq");
        Files.writeString(outside, "ASK {}\n");
        Files.createSymbolicLink(selected.resolve("escape.rq"), outside);

        ValidationAssetLoader.AssetException error = assertThrows(
                ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.loadQuestions(
                        ValidationAssetLoader.ROBOT, List.of(selected), null));
        assertTrue(error.getMessage().contains("outside"));
    }

    @Test
    void rejectsNonRegularRobotEntriesBeforeOpeningThem(@TempDir Path directory)
            throws Exception {
        Files.createDirectory(directory.resolve("not-a-file.rq"));

        ValidationAssetLoader.AssetException error = assertThrows(
                ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.loadQuestions(
                        ValidationAssetLoader.ROBOT, List.of(directory), null));

        assertTrue(error.getMessage().contains("not a regular file"));
    }

    @Test
    void loadsManifestExactRowsAndRejectsFutureVersions(@TempDir Path directory) throws Exception {
        Path manifest = directory.resolve("cqs.json");
        Files.writeString(manifest, """
                {"version":1,"questions":[{"id":"cq-1","query":"SELECT ?s WHERE {}",
                "include_inferred":false,"expected":{"kind":"exactRows","rows":[
                {"s":"https://example.org/A"}]}}]}
                """);

        List<CompetencyQuestionService.Question> questions = ValidationAssetLoader.loadQuestions(
                ValidationAssetLoader.MANIFEST, List.of(manifest), null);
        Map<String, Object> cell = cast(questions.get(0).expected().rows().get(0).get("s"));
        assertEquals("uri", cell.get("type"));

        Files.writeString(manifest, "{\"version\":2,\"questions\":[]}");
        assertThrows(ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.loadQuestions(
                        ValidationAssetLoader.MANIFEST, List.of(manifest), null));
    }

    @Test
    void emptyManifestQueryLanguageKeepsTheSparqlDefault(@TempDir Path directory)
            throws Exception {
        Path manifest = directory.resolve("cqs.json");
        Files.writeString(manifest, "{\"version\":1,\"questions\":["
                + "{\"id\":\"cq\",\"query_lang\":\"\",\"query\":\"ASK {}\"}]}");

        List<CompetencyQuestionService.Question> questions = ValidationAssetLoader.loadQuestions(
                ValidationAssetLoader.MANIFEST, List.of(manifest), null);

        assertEquals("cq", questions.get(0).id());
    }

    @Test
    void loadsOntologyAnnotationQuestionsAndFailsClosedOnMalformedEntries() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/test"));
        var data = manager.getOWLDataFactory();
        var property = data.getOWLAnnotationProperty(
                IRI.create(ValidationAssetLoader.CQ_ANNOTATION_IRI));
        manager.applyChange(new AddOntologyAnnotation(ontology, data.getOWLAnnotation(property,
                data.getOWLLiteral("{\"id\":\"cq\",\"query\":\"ASK {}\"}"))));

        List<CompetencyQuestionService.Question> questions = ValidationAssetLoader.loadQuestions(
                ValidationAssetLoader.ANNOTATIONS, List.of(), ontology);
        assertEquals("cq", questions.get(0).id());

        manager.applyChange(new AddOntologyAnnotation(ontology, data.getOWLAnnotation(property,
                data.getOWLLiteral("not json"))));
        assertThrows(ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.loadQuestions(
                        ValidationAssetLoader.ANNOTATIONS, List.of(), ontology));
    }

    @Test
    void rejectsDuplicateQuestionIdsAcrossAssets(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("a.rq"), "# id: duplicate\nASK {}\n");
        Files.writeString(directory.resolve("b.rq"), "# id: duplicate\nASK {}\n");

        assertThrows(ValidationAssetLoader.AssetException.class,
                () -> ValidationAssetLoader.loadQuestions(
                        ValidationAssetLoader.ROBOT, List.of(directory), null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
