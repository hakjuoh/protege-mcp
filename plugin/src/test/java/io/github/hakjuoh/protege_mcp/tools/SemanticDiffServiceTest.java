package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;

class SemanticDiffServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsUniqueRenameLifecycleAndPotentiallyBreakingRemoval() throws Exception {
        var leftManager = OWLManager.createOWLOntologyManager();
        var rightManager = OWLManager.createOWLOntologyManager();
        var left = leftManager.createOntology(IRI.create("http://example.org/diff"));
        var right = rightManager.createOntology(IRI.create("http://example.org/diff"));
        var ldf = leftManager.getOWLDataFactory();
        var rdf = rightManager.getOWLDataFactory();
        var oldClass = ldf.getOWLClass(IRI.create("http://example.org/Old"));
        var newClass = rdf.getOWLClass(IRI.create("http://example.org/New"));
        var commonLeft = ldf.getOWLClass(IRI.create("http://example.org/Common"));
        var commonRight = rdf.getOWLClass(IRI.create("http://example.org/Common"));
        leftManager.addAxiom(left, ldf.getOWLDeclarationAxiom(oldClass));
        leftManager.addAxiom(left, ldf.getOWLDeclarationAxiom(commonLeft));
        leftManager.addAxiom(left, ldf.getOWLAnnotationAssertionAxiom(ldf.getRDFSLabel(),
                oldClass.getIRI(), ldf.getOWLLiteral("Widget", "en")));
        leftManager.addAxiom(left, ldf.getOWLSubClassOfAxiom(oldClass, commonLeft));
        rightManager.addAxiom(right, rdf.getOWLDeclarationAxiom(newClass));
        rightManager.addAxiom(right, rdf.getOWLDeclarationAxiom(commonRight));
        rightManager.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSLabel(),
                newClass.getIRI(), rdf.getOWLLiteral("Widget", "en")));
        rightManager.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getOWLDeprecated(),
                commonRight.getIRI(), rdf.getOWLLiteral(true)));

        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) diff.get("rename_candidates");
        assertEquals(1, candidates.size());
        assertEquals("http://example.org/Old", candidates.get(0).get("from"));
        assertEquals("http://example.org/New", candidates.get(0).get("to"));
        List<Map<String, Object>> annotationChanges =
                (List<Map<String, Object>>) diff.get("annotation_changes");
        assertTrue(annotationChanges.stream().anyMatch(row ->
                ((List<String>) row.get("categories")).contains("lifecycle")));
        assertEquals("potentially_breaking",
                ((Map<String, Object>) diff.get("compatibility")).get("classification"));
        assertFalse((Boolean) diff.get("identical"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ambiguousSharedLabelsDoNotProduceRenameCandidates() throws Exception {
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology(IRI.create("http://example.org/ambiguous"));
        var right = rm.createOntology(IRI.create("http://example.org/ambiguous"));
        var ldf = lm.getOWLDataFactory();
        var rdf = rm.getOWLDataFactory();
        var old = ldf.getOWLClass(IRI.create("http://example.org/Old"));
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(old));
        lm.addAxiom(left, ldf.getOWLAnnotationAssertionAxiom(ldf.getRDFSLabel(), old.getIRI(),
                ldf.getOWLLiteral("Same")));
        for (String name : List.of("New1", "New2")) {
            var cls = rdf.getOWLClass(IRI.create("http://example.org/" + name));
            rm.addAxiom(right, rdf.getOWLDeclarationAxiom(cls));
            rm.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSLabel(), cls.getIRI(),
                    rdf.getOWLLiteral("Same")));
        }
        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);
        assertTrue(((List<?>) diff.get("rename_candidates")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void identicalAnonymousOntologiesAreNotPhantomBreaking() throws Exception {
        // Two separately parsed ANONYMOUS ontologies each get a session-local Anonymous-N id, so raw
        // OWLOntologyID equality would always report a header change → phantom potentially_breaking.
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology();  // anonymous
        var right = rm.createOntology(); // anonymous
        var ldf = lm.getOWLDataFactory();
        var rdf = rm.getOWLDataFactory();
        var c = IRI.create("http://example.org/C");
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(ldf.getOWLClass(c)));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(rdf.getOWLClass(c)));

        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);
        assertEquals("metadata_only",
                ((Map<String, Object>) diff.get("compatibility")).get("classification"),
                "two anonymous ontologies with identical content are not a breaking change");
        assertTrue((Boolean) diff.get("identical"),
                "identical anonymous ontologies must compare identical");
        assertEquals(false, ((Map<String, Object>) diff.get("ontology_id")).get("changed"),
                "ontology_id.changed must agree with identical for two anonymous ids");
    }

    @Test
    @SuppressWarnings("unchecked")
    void annotationOnlyChangeIsMetadataOnlyAndInsertionOrderDoesNotChangeCounts() throws Exception {
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology(IRI.create("http://example.org/meta"));
        var right = rm.createOntology(IRI.create("http://example.org/meta"));
        var ldf = lm.getOWLDataFactory();
        var rdf = rm.getOWLDataFactory();
        var lc = ldf.getOWLClass(IRI.create("http://example.org/C"));
        var rc = rdf.getOWLClass(IRI.create("http://example.org/C"));
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(lc));
        rm.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSComment(), rc.getIRI(),
                rdf.getOWLLiteral("definition")));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(rc));
        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);
        assertEquals("metadata_only",
                ((Map<String, Object>) diff.get("compatibility")).get("classification"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addedLogicalAxiomIsPotentiallyBreaking() throws Exception {
        // OWL is monotonic: adding DisjointClasses(A B) where an individual belongs to both makes the
        // ontology inconsistent, so a logical ADDITION can never pass as non_breaking.
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology(IRI.create("http://example.org/monotonic"));
        var right = rm.createOntology(IRI.create("http://example.org/monotonic"));
        var ldf = lm.getOWLDataFactory();
        var rdf = rm.getOWLDataFactory();
        var la = ldf.getOWLClass(IRI.create("http://example.org/monotonic#A"));
        var lb = ldf.getOWLClass(IRI.create("http://example.org/monotonic#B"));
        var ra = rdf.getOWLClass(IRI.create("http://example.org/monotonic#A"));
        var rb = rdf.getOWLClass(IRI.create("http://example.org/monotonic#B"));
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(la));
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(lb));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(ra));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(rb));
        rm.addAxiom(right, rdf.getOWLDisjointClassesAxiom(ra, rb));

        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);
        assertEquals("potentially_breaking",
                ((Map<String, Object>) diff.get("compatibility")).get("classification"),
                "an added logical axiom (DisjointClasses) must classify potentially_breaking");
    }

    @Test
    @SuppressWarnings("unchecked")
    void newDeclaredAndLabelledEntityIsNonBreaking() throws Exception {
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology(IRI.create("http://example.org/growth"));
        var right = rm.createOntology(IRI.create("http://example.org/growth"));
        var ldf = lm.getOWLDataFactory();
        var rdf = rm.getOWLDataFactory();
        var la = ldf.getOWLClass(IRI.create("http://example.org/growth#A"));
        var ra = rdf.getOWLClass(IRI.create("http://example.org/growth#A"));
        var rb = rdf.getOWLClass(IRI.create("http://example.org/growth#B"));
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(la));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(ra));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(rb));
        rm.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSLabel(), rb.getIRI(),
                rdf.getOWLLiteral("New thing", "en")));

        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);
        assertEquals("non_breaking",
                ((Map<String, Object>) diff.get("compatibility")).get("classification"),
                "a new entity carrying only its declaration and a label is non_breaking");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unresolvedRightImportsFailClosedOnlyUnderIncludeImports() throws Exception {
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology(IRI.create("http://example.org/truncation"));
        var right = rm.createOntology(IRI.create("http://example.org/truncation"));
        var ldf = lm.getOWLDataFactory();
        var rdf = rm.getOWLDataFactory();
        var c = IRI.create("http://example.org/truncation#C");
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(ldf.getOWLClass(c)));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(rdf.getOWLClass(c)));
        List<String> unresolved = List.of("http://example.org/unresolved-import");

        Map<String, Object> closed = SemanticDiffService.diff(left, right, true, 50, unresolved);
        Map<String, Object> compatibility = (Map<String, Object>) closed.get("compatibility");
        assertEquals("potentially_breaking", compatibility.get("classification"),
                "a truncated right closure must fail closed under include_imports even for equal content");
        assertTrue(String.valueOf(compatibility.get("caveat"))
                .contains("http://example.org/unresolved-import"),
                "the caveat names the unresolved import IRI");
        assertEquals(unresolved, closed.get("right_document_unresolved_imports"));

        Map<String, Object> asserted = SemanticDiffService.diff(left, right, false, 50, unresolved);
        assertEquals("metadata_only",
                ((Map<String, Object>) asserted.get("compatibility")).get("classification"),
                "an asserted-only comparison keeps the content-based classification");
        assertEquals(unresolved, asserted.get("right_document_unresolved_imports"),
                "unresolved imports are still reported without include_imports");
    }

    @Test
    void rightDocumentLoaderCollectsUnresolvedImportsWithoutAborting(@TempDir Path temp) throws Exception {
        // The import IRI points at a file that was never created, so resolution fails locally —
        // deterministic and offline-safe.
        String missingIri = temp.resolve("never-created-import.ofn").toUri().toString();
        Path document = temp.resolve("with-missing-import.ofn");
        Files.writeString(document, "Ontology(<https://example.org/truncated>\n"
                + "Import(<" + missingIri + ">)\n"
                + "Declaration(Class(<https://example.org/truncated#A>))\n)\n");

        List<String> unresolved = new ArrayList<>();
        var loaded = DiffTools.loadDocument(document.toString(), List.of(), unresolved);
        assertEquals(List.of(missingIri), unresolved,
                "the unresolvable local import is reported, not swallowed");
        assertFalse(loaded.getAxioms().isEmpty(), "the document itself still parses");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankNodeHeaderAnnotationChurnRaisesCaveatOnReparse() throws Exception {
        String document = """
                Prefix(dct:=<http://purl.org/dc/terms/>)
                Ontology(<https://example.org/anon-header>
                Annotation(dct:creator _:creator1)
                Declaration(Class(<https://example.org/anon-header#A>))
                )
                """;
        var left = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(document));
        var right = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(document));

        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);
        Map<String, Object> compatibility = (Map<String, Object>) diff.get("compatibility");
        assertEquals(true, ((Map<String, Object>) diff.get("ontology_annotations")).get("changed"),
                "each parse mints fresh blank-node ids, so the headers compare unequal (the phantom)");
        assertEquals(Boolean.TRUE, compatibility.get("anonymous_individual_churn"),
                "blank-node churn in ONTOLOGY-HEADER annotations must raise the churn flag");
        assertTrue(String.valueOf(compatibility.get("caveat")).toLowerCase().contains("blank-node"),
                "the caveat explains that blank-node ids are parse-local");
    }

    @Test
    @SuppressWarnings("unchecked")
    void renameCandidatesMatchAnySharedLabelButRequireUniquenessBothWays() throws Exception {
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology(IRI.create("http://example.org/renames"));
        var right = rm.createOntology(IRI.create("http://example.org/renames"));
        var ldf = lm.getOWLDataFactory();
        var rdf = rm.getOWLDataFactory();

        // Old1 shares only its SECOND label with New1 — still a unique pair in both directions.
        var old1 = ldf.getOWLClass(IRI.create("http://example.org/renames#Old1"));
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(old1));
        lm.addAxiom(left, ldf.getOWLAnnotationAssertionAxiom(ldf.getRDFSLabel(), old1.getIRI(),
                ldf.getOWLLiteral("Alpha")));
        lm.addAxiom(left, ldf.getOWLAnnotationAssertionAxiom(ldf.getRDFSLabel(), old1.getIRI(),
                ldf.getOWLLiteral("Kept")));
        var new1 = rdf.getOWLClass(IRI.create("http://example.org/renames#New1"));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(new1));
        rm.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSLabel(), new1.getIRI(),
                rdf.getOWLLiteral("Kept")));
        rm.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSLabel(), new1.getIRI(),
                rdf.getOWLLiteral("Zeta")));

        // Two removed classes share "Twin" with ONE added class: reverse-ambiguous, so no candidate.
        for (String name : List.of("OldTwin1", "OldTwin2")) {
            var cls = ldf.getOWLClass(IRI.create("http://example.org/renames#" + name));
            lm.addAxiom(left, ldf.getOWLDeclarationAxiom(cls));
            lm.addAxiom(left, ldf.getOWLAnnotationAssertionAxiom(ldf.getRDFSLabel(), cls.getIRI(),
                    ldf.getOWLLiteral("Twin")));
        }
        var newTwin = rdf.getOWLClass(IRI.create("http://example.org/renames#NewTwin"));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(newTwin));
        rm.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSLabel(), newTwin.getIRI(),
                rdf.getOWLLiteral("Twin")));

        // A shared label across DIFFERENT entity types never pairs.
        var oldTyped = ldf.getOWLClass(IRI.create("http://example.org/renames#OldTyped"));
        lm.addAxiom(left, ldf.getOWLDeclarationAxiom(oldTyped));
        lm.addAxiom(left, ldf.getOWLAnnotationAssertionAxiom(ldf.getRDFSLabel(), oldTyped.getIRI(),
                ldf.getOWLLiteral("Cross")));
        var newTyped = rdf.getOWLObjectProperty(IRI.create("http://example.org/renames#newTyped"));
        rm.addAxiom(right, rdf.getOWLDeclarationAxiom(newTyped));
        rm.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSLabel(), newTyped.getIRI(),
                rdf.getOWLLiteral("Cross")));

        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) diff.get("rename_candidates");
        assertEquals(1, candidates.size(), "only the unambiguous exact-label pair survives");
        assertEquals("http://example.org/renames#Old1", candidates.get(0).get("from"));
        assertEquals("http://example.org/renames#New1", candidates.get(0).get("to"));
        assertEquals("class", candidates.get(0).get("entity_type"));
        assertEquals("unique_exact_rdfs_label", candidates.get(0).get("evidence"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void renameCandidateSamplesRespectLimit() throws Exception {
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology(IRI.create("http://example.org/capped-renames"));
        var right = rm.createOntology(IRI.create("http://example.org/capped-renames"));
        var ldf = lm.getOWLDataFactory();
        var rdf = rm.getOWLDataFactory();
        for (int i = 0; i < 3; i++) {
            var oldEntity = ldf.getOWLClass(IRI.create("http://example.org/capped-renames#Old" + i));
            var newEntity = rdf.getOWLClass(IRI.create("http://example.org/capped-renames#New" + i));
            lm.addAxiom(left, ldf.getOWLDeclarationAxiom(oldEntity));
            lm.addAxiom(left, ldf.getOWLAnnotationAssertionAxiom(ldf.getRDFSLabel(), oldEntity.getIRI(),
                    ldf.getOWLLiteral("Unique " + i)));
            rm.addAxiom(right, rdf.getOWLDeclarationAxiom(newEntity));
            rm.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSLabel(), newEntity.getIRI(),
                    rdf.getOWLLiteral("Unique " + i)));
        }

        Map<String, Object> capped = SemanticDiffService.diff(left, right, false, 2);
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) capped.get("rename_candidates");
        assertEquals(2, candidates.size(), "rename samples must honor the shared output limit");
        assertEquals("http://example.org/capped-renames#Old0", candidates.get(0).get("from"));
        assertEquals("http://example.org/capped-renames#Old1", candidates.get(1).get("from"));

        Map<String, Object> none = SemanticDiffService.diff(left, right, false, 0);
        assertTrue(((List<?>) none.get("rename_candidates")).isEmpty(),
                "limit=0 must suppress rename samples too");
    }

    @Test
    @SuppressWarnings("unchecked")
    void axiomSamplesAreSortedByRenderedFormAndCappedAtLimit() throws Exception {
        var lm = OWLManager.createOWLOntologyManager();
        var rm = OWLManager.createOWLOntologyManager();
        var left = lm.createOntology(IRI.create("http://example.org/order"));
        var right = rm.createOntology(IRI.create("http://example.org/order"));
        var rdf = rm.getOWLDataFactory();
        var a = rdf.getOWLClass(IRI.create("http://example.org/order#A"));
        // Insert out of alphabetical order so the listing order must come from the rendered form.
        for (String name : List.of("Zebra", "Apple", "Mango")) {
            rm.addAxiom(right, rdf.getOWLSubClassOfAxiom(a,
                    rdf.getOWLClass(IRI.create("http://example.org/order#" + name))));
        }

        Map<String, Object> diff = SemanticDiffService.diff(left, right, false, 50);
        Map<String, Object> added = (Map<String, Object>)
                ((Map<String, Object>) diff.get("asserted_axioms")).get("added");
        List<Map<String, Object>> rows = ((Map<String, List<Map<String, Object>>>)
                added.get("groups")).get("SubClassOf");
        List<String> renderings = rows.stream().map(row -> String.valueOf(row.get("axiom"))).toList();
        assertEquals(3, renderings.size());
        assertEquals(renderings.stream().sorted().toList(), renderings,
                "axiom samples are listed in rendered (toString) order");

        Map<String, Object> capped = SemanticDiffService.diff(left, right, false, 2);
        Map<String, Object> cappedAdded = (Map<String, Object>)
                ((Map<String, Object>) capped.get("asserted_axioms")).get("added");
        assertEquals(3, cappedAdded.get("count"), "count reports the full added-set size");
        assertEquals(Boolean.TRUE, cappedAdded.get("truncated"));
        assertEquals(2, ((Map<String, List<?>>) cappedAdded.get("groups")).get("SubClassOf").size(),
                "listing stops at the limit");
    }
}
