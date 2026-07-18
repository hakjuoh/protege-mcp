package io.github.hakjuoh.protege_mcp.core.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * The {@code inferred-diff-v1} entailment set (ADR 0004): a finite, deterministic, per-category
 * fail-closed inferred comparison between two ontology sides, each classified by a caller-supplied
 * reasoner over a caller-flattened single ontology.
 *
 * <p>Call sequence, preserving the caller's one-live-reasoner-at-a-time discipline (ADR 0004
 * decision 1 — the captured reasoner configuration object is shared and may be mutated in place by
 * factories):
 *
 * <ol>
 *   <li>{@code left = evaluate(leftOntology, leftReasoner, sigma, budget)} — then dispose the left
 *       reasoner <em>unless</em> disjointness verdicts are wanted, in which case first compute
 *       {@code candidates = disjointnessCandidates(left, rightEvaluationPlaceholder…)} — because the
 *       candidate set needs both sides, the practical order is: evaluate left, evaluate right (the
 *       right reasoner alive), compute candidates, take the right side's verdicts, dispose right,
 *       re-create the left reasoner is NOT allowed — so callers wanting disjointness evaluate left,
 *       capture left verdicts for the candidate superset of the LEFT side's own asserted pairs via
 *       {@link #disjointnessVerdicts}, dispose, then right likewise; {@link #compare} intersects the
 *       verdict maps with the final capped candidate list and marks the category errored when a
 *       needed verdict is missing on either side. Simpler callers pass {@code null} verdict maps and
 *       accept an errored disjointness category.</li>
 *   <li>{@code compare(left, right, leftVerdicts, rightVerdicts, limit, candidateCap)}.</li>
 * </ol>
 *
 * <p>Every category fails closed: a reasoner exception or budget expiry marks that category with an
 * error naming the failed operation — never a silently empty result (the stock OWLAPI
 * {@code InferredOntologyGenerator} fail-open behavior is explicitly rejected). Fixed category
 * order: consistency, satisfiability, subsumption, equivalence, types, disjointness (last, so a
 * candidate-matrix overrun errors only itself).
 */
public final class InferredDiffService {

    /** The grammar identifier; changing category membership or semantics requires a new one. */
    public static final String ENTAILMENT_SET = "inferred-diff-v1";

    /** Machine-readable exclusions (ADR 0004 decision 2): never implicit. */
    public static final List<String> EXCLUDED_CATEGORIES = List.of(
            "property_hierarchies", "property_characteristics", "property_assertions");

    private static final String OWL_THING = "http://www.w3.org/2002/07/owl#Thing";
    private static final String OWL_NOTHING = "http://www.w3.org/2002/07/owl#Nothing";

    private InferredDiffService() {
    }

    /** Caller-supplied deadline hook, checked between reasoner interactions; throw to expire. */
    @FunctionalInterface
    public interface BudgetGuard {
        void check();
    }

    /**
     * The shared named signature Σ, TYPED per entity kind: an IRI punned as a class on one side and
     * an individual on the other must not enter the class categories (or disjointness candidates,
     * whose fresh-entity exclusion depends on both sides KNOWING the class), and vice versa.
     */
    public record Sigma(Set<String> classIris, Set<String> individualIris) {
        public Sigma {
            classIris = Collections.unmodifiableSet(new TreeSet<>(classIris));
            individualIris = Collections.unmodifiableSet(new TreeSet<>(individualIris));
        }
    }

    /**
     * Everything one side contributes, as plain data, so the side's reasoner can be disposed before
     * the other side's is created. {@code categoryErrors} carries per-category fail-closed error
     * text keyed by the category name used in {@link #compare}'s result.
     */
    public record Evaluation(
            boolean consistent,
            Sigma sigma,
            Set<String> unsatisfiable,
            Map<String, Set<String>> directSupers,
            Map<String, Set<String>> equivalents,
            Map<String, Set<String>> directTypes,
            Set<List<String>> assertedDisjointPairs,
            Map<String, String> categoryErrors) {

        public Evaluation {
            unsatisfiable = Collections.unmodifiableSet(new TreeSet<>(unsatisfiable));
            directSupers = deepCopy(directSupers);
            equivalents = deepCopy(equivalents);
            directTypes = deepCopy(directTypes);
            TreeSet<List<String>> pairs = new TreeSet<>(pairComparator());
            pairs.addAll(assertedDisjointPairs);
            assertedDisjointPairs = Collections.unmodifiableSet(pairs);
            categoryErrors = Collections.unmodifiableMap(new TreeMap<>(categoryErrors));
        }

        private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> source) {
            TreeMap<String, Set<String>> copy = new TreeMap<>();
            source.forEach((key, value) -> copy.put(key,
                    Collections.unmodifiableSet(new TreeSet<>(value))));
            return Collections.unmodifiableMap(copy);
        }
    }

    private static java.util.Comparator<List<String>> pairComparator() {
        return java.util.Comparator.<List<String>, String>comparing(pair -> pair.get(0))
                .thenComparing(pair -> pair.get(1));
    }

    /**
     * Evaluate one side. {@code flattened} must be the single, caller-flattened ontology the
     * reasoner classified; {@code sigmaIris} is the shared named signature Σ for the hierarchy,
     * equivalence, and type categories (satisfiability deliberately ignores Σ and uses the side's
     * full signature — ADR 0004 decision 2). An inconsistent side records only that fact; the
     * member-level categories are left empty and {@link #compare} suppresses them with a caveat.
     */
    public static Evaluation evaluate(OWLOntology flattened, OWLReasoner reasoner,
            Sigma sigma, BudgetGuard budget) {
        Map<String, String> errors = new TreeMap<>();
        boolean consistent = true;
        try {
            budget.check();
            consistent = reasoner.isConsistent();
        } catch (RuntimeException e) {
            errors.put("consistency", "isConsistent: " + message(e));
            // Without a consistency verdict no other category is trustworthy on this side.
            errors.putIfAbsent("satisfiability", "skipped: consistency unavailable");
            errors.putIfAbsent("subsumption", "skipped: consistency unavailable");
            errors.putIfAbsent("equivalence", "skipped: consistency unavailable");
            errors.putIfAbsent("types", "skipped: consistency unavailable");
            return new Evaluation(true, sigma, Set.of(), Map.of(), Map.of(), Map.of(),
                    assertedDisjointPairs(flattened, sigma.classIris()), errors);
        }
        if (!consistent) {
            return new Evaluation(false, sigma, Set.of(), Map.of(), Map.of(), Map.of(),
                    assertedDisjointPairs(flattened, sigma.classIris()), errors);
        }

        Set<String> unsatisfiable = new TreeSet<>();
        try {
            budget.check();
            for (OWLClass cls : reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                unsatisfiable.add(cls.getIRI().toString());
            }
        } catch (RuntimeException e) {
            errors.put("satisfiability", "getUnsatisfiableClasses: " + message(e));
        }

        // The taxonomy is captured over the side's FULL named signature and only restricted to Σ
        // when comparing: filtering direct supers to Σ here would lose entailments whose witness
        // path runs through a non-Σ intermediate class — exactly the closure-invariance ADR 0004
        // decision 2 requires.
        Map<String, Set<String>> directSupers = new TreeMap<>();
        Map<String, Set<String>> equivalents = new TreeMap<>();
        try {
            for (OWLClass cls : flattened.getClassesInSignature()) {
                String iri = cls.getIRI().toString();
                if (unsatisfiable.contains(iri)
                        || OWL_THING.equals(iri) || OWL_NOTHING.equals(iri)) {
                    continue;
                }
                budget.check();
                Set<String> supers = new TreeSet<>();
                for (OWLClass sup : reasoner.getSuperClasses(cls, true).getFlattened()) {
                    String supIri = sup.getIRI().toString();
                    if (!OWL_THING.equals(supIri) && !OWL_NOTHING.equals(supIri)) {
                        supers.add(supIri);
                    }
                }
                Set<String> node = new TreeSet<>();
                for (OWLClass eq : reasoner.getEquivalentClasses(cls).getEntities()) {
                    String eqIri = eq.getIRI().toString();
                    if (!eqIri.equals(iri)
                            && !OWL_THING.equals(eqIri) && !OWL_NOTHING.equals(eqIri)) {
                        node.add(eqIri);
                    }
                }
                directSupers.put(iri, supers);
                equivalents.put(iri, node);
            }
        } catch (RuntimeException e) {
            errors.put("subsumption", "getSuperClasses/getEquivalentClasses: " + message(e));
            errors.putIfAbsent("equivalence", "getEquivalentClasses: " + message(e));
            directSupers.clear();
            equivalents.clear();
        }

        Map<String, Set<String>> directTypes = new TreeMap<>();
        try {
            for (OWLNamedIndividual individual : flattened.getIndividualsInSignature()) {
                String iri = individual.getIRI().toString();
                if (!sigma.individualIris().contains(iri)) {
                    continue;
                }
                budget.check();
                Set<String> types = new TreeSet<>();
                for (OWLClass type : reasoner.getTypes(individual, true).getFlattened()) {
                    String typeIri = type.getIRI().toString();
                    // Full-taxonomy capture; Σ restriction happens at comparison time.
                    if (!OWL_THING.equals(typeIri) && !OWL_NOTHING.equals(typeIri)) {
                        types.add(typeIri);
                    }
                }
                directTypes.put(iri, types);
            }
        } catch (RuntimeException e) {
            errors.put("types", "getTypes: " + message(e));
            directTypes.clear();
        }

        return new Evaluation(true, sigma, unsatisfiable, directSupers, equivalents,
                directTypes, assertedDisjointPairs(flattened, sigma.classIris()), errors);
    }

    /**
     * Pre-reasoner scan of a side's asserted candidate pairs, for orchestrators that must bound the
     * per-side verdict superset BEFORE either evaluation exists (verdicts need each side's live
     * reasoner, and only one instance may be alive at a time).
     */
    public static Set<List<String>> assertedCandidatePairs(OWLOntology flattened,
            Sigma sigma) {
        return assertedDisjointPairs(flattened, sigma.classIris());
    }

    /** Asserted candidate pairs (DisjointClasses/DisjointUnion), Σ-filtered, canonically ordered. */
    private static Set<List<String>> assertedDisjointPairs(OWLOntology flattened,
            Set<String> sigmaIris) {
        TreeSet<List<String>> pairs = new TreeSet<>(pairComparator());
        for (OWLDisjointClassesAxiom axiom : flattened.getAxioms(AxiomType.DISJOINT_CLASSES)) {
            collectPairs(axiom.getClassExpressions(), sigmaIris, pairs);
        }
        for (OWLDisjointUnionAxiom axiom : flattened.getAxioms(AxiomType.DISJOINT_UNION)) {
            collectPairs(axiom.getClassExpressions(), sigmaIris, pairs);
        }
        return pairs;
    }

    private static void collectPairs(Set<OWLClassExpression> expressions, Set<String> sigmaIris,
            TreeSet<List<String>> pairs) {
        List<String> named = new ArrayList<>();
        for (OWLClassExpression expression : expressions) {
            if (!expression.isAnonymous()) {
                String iri = expression.asOWLClass().getIRI().toString();
                if (sigmaIris.contains(iri) && !OWL_THING.equals(iri) && !OWL_NOTHING.equals(iri)) {
                    named.add(iri);
                }
            }
        }
        Collections.sort(named);
        for (int i = 0; i < named.size(); i++) {
            for (int j = i + 1; j < named.size(); j++) {
                pairs.add(List.of(named.get(i), named.get(j)));
            }
        }
    }

    /** The final candidate list both sides must supply verdicts for. */
    public record Candidates(List<List<String>> pairs, int total, boolean truncated) { }

    /**
     * Union of both sides' asserted pairs, minus pairs touching either side's unsatisfiable
     * classes (trivially disjoint), deterministically ordered and capped with disclosure.
     */
    public static Candidates disjointnessCandidates(Evaluation left, Evaluation right, int cap) {
        TreeSet<List<String>> union = new TreeSet<>(pairComparator());
        union.addAll(left.assertedDisjointPairs());
        union.addAll(right.assertedDisjointPairs());
        union.removeIf(pair -> left.unsatisfiable().contains(pair.get(0))
                || left.unsatisfiable().contains(pair.get(1))
                || right.unsatisfiable().contains(pair.get(0))
                || right.unsatisfiable().contains(pair.get(1)));
        int total = union.size();
        List<List<String>> pairs = new ArrayList<>();
        for (List<String> pair : union) {
            if (pairs.size() >= cap) {
                break;
            }
            pairs.add(pair);
        }
        return new Candidates(List.copyOf(pairs), total, total > pairs.size());
    }

    /**
     * Per-side disjointness verdicts over {@code candidates.pairs()} while that side's reasoner is
     * still alive: {@code true} = the pair is entailed disjoint (the intersection is
     * unsatisfiable). Callers convert an exception into a {@code null} verdict map, which
     * {@link #compare} reports as an errored category.
     */
    public static Map<String, Boolean> disjointnessVerdicts(OWLOntology flattened,
            OWLReasoner reasoner, Candidates candidates, BudgetGuard budget) {
        OWLDataFactory df = flattened.getOWLOntologyManager().getOWLDataFactory();
        Map<String, Boolean> verdicts = new TreeMap<>();
        for (List<String> pair : candidates.pairs()) {
            budget.check();
            OWLClassExpression intersection = df.getOWLObjectIntersectionOf(
                    df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(pair.get(0))),
                    df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(pair.get(1))));
            verdicts.put(pairKey(pair), !reasoner.isSatisfiable(intersection));
        }
        return verdicts;
    }

    private static String pairKey(List<String> pair) {
        return pair.get(0) + "|" + pair.get(1);
    }

    /**
     * Compare two evaluations. {@code leftDisjointness}/{@code rightDisjointness} may be
     * {@code null} (or missing entries) — the disjointness category is then errored, never
     * silently empty. Result keys are stable and sorted; sample lists obey {@code limit} with
     * exact counts (the shared bounded-samples discipline).
     */
    public static Map<String, Object> compare(Evaluation left, Evaluation right,
            Map<String, Boolean> leftDisjointness, Map<String, Boolean> rightDisjointness,
            Candidates candidates, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entailment_set", ENTAILMENT_SET);
        result.put("excluded_categories", EXCLUDED_CATEGORIES);

        Map<String, Object> consistency = new LinkedHashMap<>();
        String consistencyError = firstError(left, right, "consistency");
        if (consistencyError != null) {
            // The verdict never materialized: fabricating left/right/changed booleans would
            // serialize unfounded claims — say only what is known.
            consistency.put("verdict_available", false);
            consistency.put("error", consistencyError);
        } else {
            consistency.put("left", left.consistent());
            consistency.put("right", right.consistent());
            consistency.put("changed", left.consistent() != right.consistent());
        }
        boolean suppressed = consistencyError == null
                && (!left.consistent() || !right.consistent());
        if (suppressed) {
            consistency.put("caveat", "An inconsistent ontology entails everything; member-level "
                    + "inferred categories are suppressed for this comparison.");
        }
        result.put("consistency", consistency);
        result.put("categories_suppressed", suppressed);
        if (suppressed || consistencyError != null) {
            // Name every category whose evidence is missing, so the fail-closed classification
            // caveat can enumerate them (ADR 0004 decision 8).
            TreeSet<String> errored = new TreeSet<>();
            if (consistencyError != null) {
                errored.add("consistency");
                errored.addAll(left.categoryErrors().keySet());
                errored.addAll(right.categoryErrors().keySet());
            }
            result.put("errored_categories", List.copyOf(errored));
            return result;
        }

        Map<String, Object> satisfiability = new LinkedHashMap<>();
        String satisfiabilityError = firstError(left, right, "satisfiability");
        if (satisfiabilityError != null) {
            satisfiability.put("error", satisfiabilityError);
        } else {
            satisfiability.put("newly_unsatisfiable",
                    bounded(minus(right.unsatisfiable(), left.unsatisfiable()), limit));
            satisfiability.put("no_longer_unsatisfiable",
                    bounded(minus(left.unsatisfiable(), right.unsatisfiable()), limit));
        }
        result.put("satisfiability", satisfiability);

        Map<String, Set<String>> leftReach = reachability(left);
        Map<String, Set<String>> rightReach = reachability(right);
        Set<String> sigma = left.sigma().classIris();

        Map<String, Object> subsumption = new LinkedHashMap<>();
        String subsumptionError = firstError(left, right, "subsumption");
        if (subsumptionError != null) {
            subsumption.put("error", subsumptionError);
        } else {
            subsumption.put("gained", edgeDelta(rightReach, leftReach, right, sigma, limit));
            subsumption.put("lost", edgeDelta(leftReach, rightReach, left, sigma, limit));
        }
        result.put("subsumption", subsumption);

        Map<String, Object> equivalence = new LinkedHashMap<>();
        String equivalenceError = firstError(left, right, "equivalence");
        if (equivalenceError != null) {
            equivalence.put("error", equivalenceError);
        } else {
            Set<List<String>> leftPairs = equivalencePairs(left);
            Set<List<String>> rightPairs = equivalencePairs(right);
            equivalence.put("gained_pairs", boundedPairs(minusPairs(rightPairs, leftPairs), limit));
            equivalence.put("lost_pairs", boundedPairs(minusPairs(leftPairs, rightPairs), limit));
        }
        result.put("equivalence", equivalence);

        Map<String, Object> types = new LinkedHashMap<>();
        String typesError = firstError(left, right, "types");
        if (typesError != null) {
            types.put("error", typesError);
        } else {
            Set<List<String>> leftTypes = entailedTypes(left, leftReach);
            Set<List<String>> rightTypes = entailedTypes(right, rightReach);
            types.put("gained", boundedTypeRows(minusPairs(rightTypes, leftTypes), right, limit));
            types.put("lost", boundedTypeRows(minusPairs(leftTypes, rightTypes), left, limit));
        }
        result.put("types", types);

        Map<String, Object> disjointness = new LinkedHashMap<>();
        disjointness.put("disjointness_scope", "asserted_candidates");
        disjointness.put("total_candidates", candidates.total());
        disjointness.put("checked_candidates", candidates.pairs().size());
        if (candidates.truncated()) {
            disjointness.put("candidates_truncated", true);
        }
        if (leftDisjointness == null || rightDisjointness == null) {
            disjointness.put("error", "disjointness: per-side verdicts unavailable ("
                    + (leftDisjointness == null ? "left" : "right") + " side)");
        } else {
            TreeSet<String> gained = new TreeSet<>();
            TreeSet<String> lost = new TreeSet<>();
            String missing = null;
            for (List<String> pair : candidates.pairs()) {
                Boolean l = leftDisjointness.get(pairKey(pair));
                Boolean r = rightDisjointness.get(pairKey(pair));
                if (l == null || r == null) {
                    missing = pairKey(pair);
                    break;
                }
                if (!l && r) {
                    gained.add(pairKey(pair));
                } else if (l && !r) {
                    lost.add(pairKey(pair));
                }
            }
            if (missing != null) {
                disjointness.put("error", "disjointness: missing verdict for candidate " + missing);
            } else {
                disjointness.put("gained", bounded(gained, limit));
                disjointness.put("lost", bounded(lost, limit));
            }
        }
        result.put("disjointness", disjointness);

        List<String> erroredCategories = new ArrayList<>();
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> category && category.containsKey("error")) {
                erroredCategories.add(entry.getKey());
            }
        }
        result.put("errored_categories", erroredCategories);
        return result;
    }

    private static String firstError(Evaluation left, Evaluation right, String category) {
        if (left.categoryErrors().containsKey(category)) {
            return "left: " + left.categoryErrors().get(category);
        }
        if (right.categoryErrors().containsKey(category)) {
            return "right: " + right.categoryErrors().get(category);
        }
        return null;
    }

    /**
     * Reachability closure over direct-super edges plus bidirectional equivalence edges: the
     * entailed strict named subsumption relation is fully determined by the direct taxonomy, so no
     * further reasoner calls are needed (ADR 0004 decision 2). Reflexive members are removed.
     */
    private static Map<String, Set<String>> reachability(Evaluation side) {
        Map<String, Set<String>> edges = new TreeMap<>();
        side.directSupers().forEach((cls, supers) -> edges
                .computeIfAbsent(cls, key -> new TreeSet<>()).addAll(supers));
        side.equivalents().forEach((cls, eqs) -> {
            edges.computeIfAbsent(cls, key -> new TreeSet<>()).addAll(eqs);
            for (String eq : eqs) {
                edges.computeIfAbsent(eq, key -> new TreeSet<>()).add(cls);
            }
        });
        Map<String, Set<String>> reach = new TreeMap<>();
        for (String start : edges.keySet()) {
            Set<String> visited = new TreeSet<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(edges.get(start));
            while (!queue.isEmpty()) {
                String next = queue.poll();
                if (visited.add(next)) {
                    queue.addAll(edges.getOrDefault(next, Set.of()));
                }
            }
            visited.remove(start);
            reach.put(start, visited);
        }
        return reach;
    }

    /**
     * Σ-restricted delta edges present in {@code has} but not {@code lacks}, transitively reduced,
     * labeled. The closures themselves cover the full named taxonomy; only pair MEMBERSHIP is
     * restricted here, so an entailment surviving through a non-Σ intermediate never diffs.
     */
    private static Map<String, Object> edgeDelta(Map<String, Set<String>> has,
            Map<String, Set<String>> lacks, Evaluation labelSide, Set<String> sigma, int limit) {
        TreeSet<List<String>> delta = new TreeSet<>(pairComparator());
        has.forEach((sub, supers) -> {
            if (!sigma.contains(sub)) {
                return;
            }
            Set<String> other = lacks.getOrDefault(sub, Set.of());
            for (String sup : supers) {
                if (sigma.contains(sup) && !other.contains(sup) && !sub.equals(sup)) {
                    // Equivalence-pair membership belongs to the equivalence category, not here.
                    if (!has.getOrDefault(sup, Set.of()).contains(sub)) {
                        delta.add(List.of(sub, sup));
                    }
                }
            }
        });
        // Transitive reduction for presentation, with witnesses restricted to Σ: a two-hop witness
        // path made of Σ pairs necessarily contains a Σ delta hop that is itself reported (hops
        // common to both sides would place the edge in both closures), so the dropped edge stays
        // derivable from the reported delta plus the unchanged closure. A NON-Σ witness (a
        // one-side-only intermediate class) must never reduce: its hops are Σ-excluded from the
        // report, and dropping the edge would silently erase a genuine Σ entailment change.
        TreeSet<List<String>> reduced = new TreeSet<>(pairComparator());
        for (List<String> edge : delta) {
            boolean redundant = false;
            for (String via : has.getOrDefault(edge.get(0), Set.of())) {
                if (!sigma.contains(via)) {
                    continue;
                }
                if (!via.equals(edge.get(0)) && !via.equals(edge.get(1))
                        && has.getOrDefault(via, Set.of()).contains(edge.get(1))
                        // Strictly between: a witness equivalent to either end would let two
                        // equivalent delta edges mark each other redundant and vanish together.
                        && !has.getOrDefault(via, Set.of()).contains(edge.get(0))
                        && !has.getOrDefault(edge.get(1), Set.of()).contains(via)) {
                    redundant = true;
                    break;
                }
            }
            if (!redundant) {
                reduced.add(edge);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> edge : reduced) {
            if (rows.size() >= limit) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sub", edge.get(0));
            row.put("super", edge.get(1));
            row.put("label", labelSide.directSupers()
                    .getOrDefault(edge.get(0), Set.of()).contains(edge.get(1))
                    ? "direct" : "indirect");
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", reduced.size());
        out.put("edges", rows);
        if (reduced.size() > rows.size()) {
            out.put("truncated", true);
        }
        return out;
    }

    private static Set<List<String>> equivalencePairs(Evaluation side) {
        TreeSet<List<String>> pairs = new TreeSet<>(pairComparator());
        side.equivalents().forEach((cls, eqs) -> {
            if (!side.sigma().classIris().contains(cls)) {
                return;
            }
            for (String eq : eqs) {
                if (side.sigma().classIris().contains(eq)) {
                    pairs.add(cls.compareTo(eq) < 0 ? List.of(cls, eq) : List.of(eq, cls));
                }
            }
        });
        return pairs;
    }

    private static Set<List<String>> entailedTypes(Evaluation side, Map<String, Set<String>> reach) {
        TreeSet<List<String>> rows = new TreeSet<>(pairComparator());
        side.directTypes().forEach((individual, direct) -> {
            for (String type : direct) {
                if (side.sigma().classIris().contains(type)) {
                    rows.add(List.of(individual, type));
                }
                for (String ancestor : reach.getOrDefault(type, Set.of())) {
                    if (side.sigma().classIris().contains(ancestor)) {
                        rows.add(List.of(individual, ancestor));
                    }
                }
            }
        });
        return rows;
    }

    private static Map<String, Object> boundedTypeRows(Set<List<String>> delta, Evaluation labelSide,
            int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> pair : delta) {
            if (rows.size() >= limit) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("individual", pair.get(0));
            row.put("type", pair.get(1));
            row.put("label", labelSide.directTypes()
                    .getOrDefault(pair.get(0), Set.of()).contains(pair.get(1))
                    ? "direct" : "indirect");
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", delta.size());
        out.put("rows", rows);
        if (delta.size() > rows.size()) {
            out.put("truncated", true);
        }
        return out;
    }

    private static Set<List<String>> minusPairs(Set<List<String>> base, Set<List<String>> remove) {
        TreeSet<List<String>> out = new TreeSet<>(pairComparator());
        out.addAll(base);
        out.removeAll(remove);
        return out;
    }

    private static Set<String> minus(Set<String> base, Set<String> remove) {
        TreeSet<String> out = new TreeSet<>(base);
        out.removeAll(remove);
        return out;
    }

    private static Map<String, Object> bounded(Set<String> values, int limit) {
        List<String> items = new ArrayList<>();
        for (String value : values) {
            if (items.size() >= limit) {
                break;
            }
            items.add(value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", values.size());
        out.put("items", items);
        if (values.size() > items.size()) {
            out.put("truncated", true);
        }
        return out;
    }

    private static Map<String, Object> boundedPairs(Set<List<String>> values, int limit) {
        List<List<String>> items = new ArrayList<>();
        for (List<String> value : values) {
            if (items.size() >= limit) {
                break;
            }
            items.add(value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", values.size());
        out.put("items", items);
        if (values.size() > items.size()) {
            out.put("truncated", true);
        }
        return out;
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
