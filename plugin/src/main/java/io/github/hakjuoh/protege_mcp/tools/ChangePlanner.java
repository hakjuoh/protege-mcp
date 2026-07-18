package io.github.hakjuoh.protege_mcp.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

/** Pure planning half of the low-level {@code apply_changes} operation shape. */
final class ChangePlanner {

    static final int MAX_OPERATIONS = 2_000;

    private ChangePlanner() {
    }

    static Plan plan(OWLModelManager mm, List<Map<String, Object>> operations, boolean strict) {
        return plan(mm, operations, strict, false);
    }

    /** Plan the valid subset retained by released apply_changes partial-error semantics. */
    static Plan planForApply(OWLModelManager mm, List<Map<String, Object>> operations,
            boolean strict) {
        return plan(mm, operations, strict, true);
    }

    private static Plan plan(OWLModelManager mm, List<Map<String, Object>> operations,
            boolean strict, boolean retainValidSubsetOnError) {
        if (operations.isEmpty()) {
            throw new ToolArgException("Provide at least one operation in 'operations'.");
        }
        // The cap protects the preview STORE's retained entries. A verified apply retains nothing,
        // and apply_changes accepted arbitrarily sized batches before the 0.6.1 migration — capping
        // only its verify= modes would break released calls.
        if (!retainValidSubsetOnError && operations.size() > MAX_OPERATIONS) {
            throw new ToolArgException("operations exceeds the maximum of " + MAX_OPERATIONS + ".");
        }
        OWLOntology active = mm.getActiveOntology();
        Set<OWLOntology> closure = active.getImportsClosure();
        Set<OWLAxiom> initial = new LinkedHashSet<>(active.getAxioms());
        Set<OWLAxiom> simulated = new LinkedHashSet<>(initial);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String[] resolved = new String[operations.size()];

        for (int i = 0; i < operations.size(); i++) {
            Map<String, Object> item = operations.get(i);
            String raw = Tools.optString(item, "op");
            String op = raw == null ? "add" : raw.toLowerCase(java.util.Locale.ROOT);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("op", op);
            try {
                if (!"add".equals(op) && !"remove".equals(op)) {
                    throw new ToolArgException("Unsupported op '" + op + "'. Use add or remove.");
                }
                OWLAxiom axiom = Axioms.build(mm, item);
                // PRE-simulation resolution identity: rebase compares this per-request signature
                // against a later re-plan, so a no-op absorbed by final-set semantics still proves
                // the operand resolved to the same axiom.
                resolved[i] = op + RESOLVED_SEPARATOR + axiom;
                row.put("axiom", Tools.axiomJson(mm, axiom));
                boolean present = simulated.contains(axiom);
                if ("add".equals(op)) {
                    Set<OWLEntity> minted = newEntitiesAfterSimulation(closure, simulated, initial, axiom);
                    if (strict && !minted.isEmpty()) {
                        throw new ToolArgException("strict: would mint "
                                + EntityRendering.renderMinted(mm, minted));
                    }
                    if (present) {
                        row.put("effect", "no-op (already present)");
                    } else {
                        simulated.add(axiom);
                        row.put("effect", "add");
                    }
                } else if (!present) {
                    row.put("effect", "no-op (not present)");
                } else {
                    simulated.remove(axiom);
                    row.put("effect", "remove");
                }
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                row.put("error", message);
                errors.add("operation " + i + ": " + message);
                // The message text is renderer/state-dependent; the signature records only THAT the
                // position failed, so error→error at one position compares stable while
                // error→resolved (or the reverse) is a resolution change. Overwrites the resolved
                // entry when the failure (e.g. a strict minting refusal) happened after the operand
                // itself resolved.
                resolved[i] = RESOLVED_ERROR;
            }
            rows.add(row);
        }

        Set<OWLAxiom> adds = new LinkedHashSet<>(simulated);
        adds.removeAll(initial);
        Set<OWLAxiom> removes = new LinkedHashSet<>(initial);
        removes.removeAll(simulated);
        // FINAL-SET no_ops, aligned with prepared()/the direct batch path: every successful operation
        // that does not survive in the operation-derived delta is a no-op — this counts BOTH halves of
        // an add→remove (or remove→add) cancellation, which the per-operation effect labels above
        // deliberately do not (each row still reports its point-in-sequence effect). Computed before
        // the declaration injection below so injected declarations never join the reconciliation.
        int summaryNoOps = (operations.size() - errors.size()) - adds.size() - removes.size();

        if (errors.isEmpty() || retainValidSubsetOnError) {
            Set<OWLEntity> minted = new LinkedHashSet<>();
            for (OWLAxiom axiom : adds) {
                minted.addAll(PreviewTools.newEntities(closure, axiom));
            }
            List<OWLOntologyChange> declarationPlan = new ArrayList<>();
            for (OWLAxiom axiom : adds) {
                declarationPlan.add(new AddAxiom(active, axiom));
            }
            WriteTools.declareMinted(mm.getOWLDataFactory(), active, minted, declarationPlan);
            WriteTools.declareUsedAnnotationProperties(mm.getOWLDataFactory(), active, declarationPlan);
            for (OWLOntologyChange change : declarationPlan) {
                if (change.isAddAxiom()) {
                    adds.add(change.getAxiom());
                }
            }
        }

        Comparator<OWLAxiom> order = Comparator.comparing(Object::toString);
        List<NormalizedChange> normalized = new ArrayList<>();
        removes.stream().sorted(order).forEach(ax -> normalized.add(
                new NormalizedChange(NormalizedChange.Operation.REMOVE, ax)));
        adds.stream().sorted(order).forEach(ax -> normalized.add(
                new NormalizedChange(NormalizedChange.Operation.ADD, ax)));

        long estimatedBytes = 0;
        for (NormalizedChange change : normalized) {
            estimatedBytes += change.axiom().toString().getBytes(StandardCharsets.UTF_8).length + 16L;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requested_operations", operations.size());
        summary.put("normalized_changes", normalized.size());
        summary.put("adds", adds.size());
        summary.put("removes", removes.size());
        summary.put("no_ops", summaryNoOps);
        summary.put("errors", errors.size());
        return new Plan(List.copyOf(normalized), List.copyOf(rows), summary, List.copyOf(errors),
                estimatedBytes, List.of(resolved));
    }

    /**
     * Build a cache-safe plan from an already validated curation macro's axiom changes, running the
     * same set-simulation {@link #plan} uses: a change repeated within the batch or already asserted
     * in the active ontology becomes a {@code no_op}, never a normalized change. Without this, a
     * duplicated label or a re-created existing term would inflate {@code normalized_changes} and the
     * commit's {@code effective_changes} beyond the axioms that actually land (OWL set semantics make
     * the surplus applies invisible in the ontology itself).
     */
    static Plan prepared(OWLModelManager mm, List<OWLOntologyChange> changes,
            List<? extends OWLEntity> created, String kind) {
        return prepared(mm, changes, created, kind, null);
    }

    /**
     * @param resolutionOverride the pre-filter resolution entries collected while BUILDING the
     *        changes (see CurationTools.CurationChanges) — the curation change list itself is
     *        state-dependent (already-asserted axioms are dropped, declarations are injected), so
     *        signing it would make a rebase conflict on unrelated concurrent edits; {@code null}
     *        derives the signature from the raw change list instead.
     */
    static Plan prepared(OWLModelManager mm, List<OWLOntologyChange> changes,
            List<? extends OWLEntity> created, String kind, List<String> resolutionOverride) {
        OWLOntology active = mm.getActiveOntology();
        Set<OWLAxiom> initial = new LinkedHashSet<>(active.getAxioms());
        Set<OWLAxiom> simulated = new LinkedHashSet<>(initial);
        List<String> resolved = new ArrayList<>(created.size() + changes.size());
        // The curation resolution product is the minted entity list plus the operand resolution
        // sequence: a spec whose name/expression re-resolves differently, or whose minted IRI is
        // not deterministic, changes this signature and fails a rebase closed.
        for (OWLEntity entity : created) {
            resolved.add("created" + RESOLVED_SEPARATOR + entity.getIRI());
        }
        if (resolutionOverride != null) {
            resolved.addAll(resolutionOverride);
        }
        for (OWLOntologyChange change : changes) {
            if (!change.isAxiomChange()) {
                throw new ToolArgException("Change-set preflight supports axiom changes only.");
            }
            if (resolutionOverride == null) {
                resolved.add((change.isAddAxiom() ? "add" : "remove")
                        + RESOLVED_SEPARATOR + change.getAxiom());
            }
            if (change.isAddAxiom()) {
                simulated.add(change.getAxiom());
            } else {
                simulated.remove(change.getAxiom());
            }
        }
        Set<OWLAxiom> adds = new LinkedHashSet<>(simulated);
        adds.removeAll(initial);
        Set<OWLAxiom> removes = new LinkedHashSet<>(initial);
        removes.removeAll(simulated);
        // Bound the FINAL delta, never the raw batch: repeated operands legitimately make the raw
        // change list several times larger than what survives normalization (e.g. shared parents on
        // every spec), and the O(n) set simulation above is the cheap part. Validating here still
        // precedes the per-axiom rendering sort and byte estimate, which the bound exists to protect.
        ChangeSetStore.validateEntryBounds(adds.size() + removes.size(), 0L);
        // Final-set semantics: every requested operand that does not survive in the normalized
        // delta is a no-op. This counts both halves of add→remove / remove→add cancellation, matching
        // the direct batch path instead of reporting zero no-ops for a net-zero preview.
        int noOps = changes.size() - adds.size() - removes.size();
        Comparator<OWLAxiom> order = Comparator.comparing(Object::toString);
        List<NormalizedChange> normalized = new ArrayList<>();
        removes.stream().sorted(order).forEach(ax -> normalized.add(
                new NormalizedChange(NormalizedChange.Operation.REMOVE, ax)));
        adds.stream().sorted(order).forEach(ax -> normalized.add(
                new NormalizedChange(NormalizedChange.Operation.ADD, ax)));
        long bytes = 0;
        for (NormalizedChange item : normalized) {
            bytes += item.axiom().toString().getBytes(StandardCharsets.UTF_8).length + 16L;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < created.size(); i++) {
            OWLEntity entity = created.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("kind", kind);
            row.put("entity_type", entity.getEntityType().getName().toLowerCase(java.util.Locale.ROOT));
            row.put("iri", entity.getIRI().toString());
            rows.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requested_" + kind, created.size());
        summary.put("normalized_changes", normalized.size());
        summary.put("adds", adds.size());
        summary.put("removes", removes.size());
        summary.put("no_ops", noOps);
        summary.put("errors", 0);
        return new Plan(List.copyOf(normalized), List.copyOf(rows), summary, List.of(), bytes,
                List.copyOf(resolved));
    }

    private static Set<OWLEntity> newEntitiesAfterSimulation(Set<OWLOntology> closure,
            Set<OWLAxiom> simulated, Set<OWLAxiom> initial, OWLAxiom candidate) {
        Set<OWLEntity> minted = PreviewTools.newEntities(closure, candidate);
        if (minted.isEmpty()) {
            return minted;
        }
        Set<OWLAxiom> priorAdds = new LinkedHashSet<>(simulated);
        priorAdds.removeAll(initial);
        for (OWLAxiom axiom : priorAdds) {
            minted.removeAll(axiom.getSignature());
        }
        return minted;
    }

    record Plan(List<NormalizedChange> changes, List<Map<String, Object>> operations,
            Map<String, Object> summary, List<String> errors, long estimatedBytes,
            List<String> resolved) {
        boolean committable() {
            return errors.isEmpty();
        }
    }

    /**
     * Separator inside {@link Plan#resolved} signature entries. Entries are compared as whole
     * strings (never parsed back), and the leading token comes from a fixed vocabulary
     * (add/remove/created/error), so a plain space stays unambiguous and keeps the entry
     * human-readable when a conflict result echoes it back for review.
     */
    static final String RESOLVED_SEPARATOR = " ";
    /** Signature marker for a request position that failed to resolve/build. */
    static final String RESOLVED_ERROR = "error";
}
