package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Test for the F4 fix: {@code apply_changes}' batch summary must report the entities the batch mints in
 * {@code summary.new_entities}. The aggregate was previously computed AFTER {@code mm.applyChanges}, by
 * which point the entities already existed in the closure and read as "not new" — leaving the aggregate
 * empty while the per-op rows (computed pre-apply) correctly listed them.
 */
class ApplyBatchNewEntitiesTest {

    private static final String NS = "http://example.org/f4#";

    @SuppressWarnings("unchecked")
    @Test
    void batchSummaryReportsMintedEntities() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/f4"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "C"))));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> op = new LinkedHashMap<>();
        op.put("axiom_type", "class_assertion");
        op.put("individual", NS + "ind1");   // brand-new individual
        op.put("class", NS + "C");           // existing class

        Map<String, Object> result = WriteTools.applyBatchData(mm, List.of(op), false);

        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, ((Number) summary.get("added")).intValue());
        Map<String, Object> newEntities = (Map<String, Object>) summary.get("new_entities");
        assertEquals(1, ((Number) newEntities.get("count")).intValue(),
                "summary.new_entities must list the minted individual (F4)");
        List<Map<String, Object>> items = (List<Map<String, Object>>) newEntities.get("items");
        assertTrue(items.stream().anyMatch(e -> (NS + "ind1").equals(e.get("iri"))),
                "the minted individual is named in the aggregate");
    }
}
