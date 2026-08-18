package io.github.hakjuoh.protege_mcp.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans, simulates and commits multi-axiom writes as one undoable transaction. */
final class BatchWriteService {

    private BatchWriteService() {}

    /**
     * Total asserted axioms across all loaded ontologies — a simple "what changed" delta for
     * undo/redo.
     */
    static long totalAxioms(OWLModelManager mm) {
        long n = 0;
        for (OWLOntology o : mm.getOntologies()) {
            n += o.getAxiomCount();
        }
        return n;
    }

    /**
     * Apply each operation in {@code operations} (add/remove) against the active ontology as ONE
     * undoable transaction. A first pass builds + strict-checks each axiom and records
     * per-operation results against a simulated copy of the batch's effect; a single {@link
     * OWLModelManager#applyChanges} then commits every resulting change at once, so the whole batch
     * reverts in a single {@code undo_change}. Because nothing is applied until that final pass, an
     * operation referencing an entity introduced by an earlier operation in the same batch must
     * refer to it by full IRI.
     */
    static CallToolResult applyBatch(
            OWLModelManager mm, List<Map<String, Object>> operations, boolean strict) {
        return Tools.ok(applyBatchData(mm, operations, strict));
    }

    /**
     * The batch-apply core, returning the raw result map ({@code {operations, summary}}) rather
     * than a wrapped {@link CallToolResult}, so direct and change-set-verified paths share one live
     * commit implementation. See {@link #applyBatch} for the behaviour contract.
     */
    static Map<String, Object> applyBatchData(
            OWLModelManager mm, List<Map<String, Object>> operations, boolean strict) {
        return batchData(mm, operations, strict, true);
    }

    /** Build the released apply_changes payload against the live revision without mutating it. */
    static Map<String, Object> simulateBatchData(
            OWLModelManager mm, List<Map<String, Object>> operations, boolean strict) {
        return batchData(mm, operations, strict, false);
    }

    private static Map<String, Object> batchData(
            OWLModelManager mm,
            List<Map<String, Object>> operations,
            boolean strict,
            boolean apply) {
        OWLOntology ont = mm.getActiveOntology();
        Set<OWLOntology> closure = ont.getImportsClosure();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<OWLOntologyChange> toApply = new ArrayList<>();
        Set<OWLAxiom> simAdded = new LinkedHashSet<>(); // net-new axioms this batch plans to add
        Set<OWLAxiom> simRemoved =
                new LinkedHashSet<>(); // existing axioms this batch plans to remove
        int added = 0;
        int removed = 0;
        int noOps = 0;
        int errors = 0;
        for (int i = 0; i < operations.size(); i++) {
            Map<String, Object> item = operations.get(i);
            String opRaw = Tools.optString(item, "op");
            String op = opRaw == null ? "add" : opRaw.toLowerCase();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("op", op);
            if (!"add".equals(op) && !"remove".equals(op)) {
                row.put("error", "Unsupported op '" + op + "'. Use add or remove.");
                errors++;
                rows.add(row);
                continue;
            }
            try {
                OWLAxiom ax = Axioms.build(mm, item);
                row.put("axiom", Tools.axiomJson(mm, ax));
                // Present == in the ontology now, plus/minus what earlier ops in this batch plan.
                boolean present =
                        (ont.containsAxiom(ax) || simAdded.contains(ax))
                                && !simRemoved.contains(ax);
                if ("remove".equals(op)) {
                    if (present) {
                        toApply.add(new RemoveAxiom(ont, ax));
                        if (ont.containsAxiom(ax)) {
                            simRemoved.add(ax);
                        }
                        simAdded.remove(ax);
                        row.put("removed", true);
                        removed++;
                    } else {
                        row.put("removed", false);
                        row.put("note", "not present");
                        noOps++;
                    }
                } else {
                    Set<OWLEntity> minted = newEntitiesAfterSimulatedAdds(closure, simAdded, ax);
                    if (strict && !minted.isEmpty()) {
                        row.put(
                                "error",
                                "strict: would mint " + EntityRendering.renderMinted(mm, minted));
                        errors++;
                    } else if (present) {
                        row.put("applied", true);
                        row.put("note", "already present");
                        noOps++;
                    } else {
                        toApply.add(new AddAxiom(ont, ax));
                        simAdded.add(ax);
                        simRemoved.remove(ax);
                        row.put("applied", true);
                        added++;
                        if (!minted.isEmpty()) {
                            List<Map<String, Object>> ne = new ArrayList<>();
                            for (OWLEntity e : minted) {
                                ne.add(Tools.entityJson(mm, e));
                            }
                            row.put("new_entities", ne);
                        }
                    }
                }
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                row.put("error", msg == null ? e.getClass().getSimpleName() : msg);
                errors++;
            }
            rows.add(row);
        }
        // Compute net-new entities before applying changes. Afterwards they already exist in the
        // closure and would incorrectly disappear from summary.new_entities even though the
        // per-operation rows identified them.
        Set<OWLEntity> mintedAll = newEntitiesIntroducedByAxioms(closure, simAdded);
        EntityWriteService.declareMinted(
                mm.getOWLDataFactory(), ont, mintedAll, toApply); // declare side-effect entities
        EntityWriteService.declareUsedAnnotationProperties(mm.getOWLDataFactory(), ont, toApply);
        if (apply && !toApply.isEmpty()) {
            mm.applyChanges(toApply); // one broadcast → one Protégé undo entry for the whole batch
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operations", operations.size());
        summary.put("added", added);
        summary.put("removed", removed);
        summary.put("no_ops", noOps);
        summary.put("errors", errors);
        summary.put("single_undo", !toApply.isEmpty());
        summary.put("new_entities", Tools.entityList(mm, mintedAll, Integer.MAX_VALUE));
        return Tools.json().put("operations", rows).put("summary", summary).map();
    }

    /**
     * Entities in {@code ax} that are not already known now or by earlier net additions in the
     * batch.
     */
    static Set<OWLEntity> newEntitiesAfterSimulatedAdds(
            Set<OWLOntology> closure, Set<OWLAxiom> simAdded, OWLAxiom ax) {
        Set<OWLEntity> out = PreviewTools.newEntities(closure, ax);
        if (out.isEmpty() || simAdded.isEmpty()) {
            return out;
        }
        out.removeAll(newEntitiesIntroducedByAxioms(closure, simAdded));
        return out;
    }

    /** New entities introduced by the simulated net-add axiom set. */
    static Set<OWLEntity> newEntitiesIntroducedByAxioms(
            Set<OWLOntology> closure, Set<OWLAxiom> axioms) {
        Set<OWLEntity> out = new LinkedHashSet<>();
        for (OWLAxiom ax : axioms) {
            out.addAll(PreviewTools.newEntities(closure, ax));
        }
        return out;
    }

}
