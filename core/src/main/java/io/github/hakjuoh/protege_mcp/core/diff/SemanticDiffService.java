package io.github.hakjuoh.protege_mcp.core.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;

/** Deterministic asserted semantic categories over two OWLAPI ontology snapshots. */
public final class SemanticDiffService {

    private SemanticDiffService() {
    }

    public static Map<String, Object> diff(OWLOntology left, OWLOntology right, boolean includeImports,
            int limit) {
        return diff(left, right, includeImports, limit, List.of());
    }

    /**
     * @param rightUnresolvedImports import IRIs the right side's loader could not resolve. They are
     *        reported verbatim, and when {@code includeImports} is set they force the classification
     *        to {@code potentially_breaking}: the unloaded axioms are invisible to this diff, so a
     *        truncated right closure must fail closed rather than pass a review gate as
     *        {@code metadata_only} or identical.
     */
    public static Map<String, Object> diff(OWLOntology left, OWLOntology right, boolean includeImports,
            int limit, Collection<String> rightUnresolvedImports) {
        Set<OWLAxiom> leftAxioms = axioms(left, includeImports);
        Set<OWLAxiom> rightAxioms = axioms(right, includeImports);
        Set<OWLAxiom> removedAxioms = difference(leftAxioms, rightAxioms);
        Set<OWLAxiom> addedAxioms = difference(rightAxioms, leftAxioms);

        Set<EntityKey> leftEntities = entities(left, includeImports);
        Set<EntityKey> rightEntities = entities(right, includeImports);
        Set<EntityKey> removedEntities = difference(leftEntities, rightEntities);
        Set<EntityKey> addedEntities = difference(rightEntities, leftEntities);

        boolean idChanged = ontologyIdChanged(left, right);
        // Blank-node churn hides in ONTOLOGY-HEADER annotations too (e.g. dct:creator []): a
        // re-parsed document mints fresh NodeIDs, so such header values compare unequal exactly like
        // axiom-level anonymous individuals and deserve the same caveat.
        boolean anonymousChurn = hasAnonymousIndividual(addedAxioms) || hasAnonymousIndividual(removedAxioms)
                || hasAnonymousIndividual(difference(left.getAnnotations(), right.getAnnotations()))
                || hasAnonymousIndividual(difference(right.getAnnotations(), left.getAnnotations()));
        List<String> unresolvedImports = rightUnresolvedImports.stream().distinct().sorted().toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "asserted");
        result.put("include_imports", includeImports);
        result.put("right_document_unresolved_imports", unresolvedImports);
        // The `changed` flag uses the anonymous-aware comparison (two anonymous ids are not a change),
        // consistent with headerChanged/identical below; the raw id strings are still reported as-is.
        Map<String, Object> ontologyIdPair = pair(left.getOntologyID().toString(),
                right.getOntologyID().toString());
        ontologyIdPair.put("changed", idChanged);
        result.put("ontology_id", ontologyIdPair);
        result.put("imports", pair(strings(left.getImportsDeclarations()), strings(right.getImportsDeclarations())));
        result.put("ontology_annotations", pair(strings(left.getAnnotations()), strings(right.getAnnotations())));
        result.put("entities", Map.of(
                "added", entityGroups(addedEntities, limit),
                "removed", entityGroups(removedEntities, limit)));
        result.put("rename_candidates", renameCandidates(left, right, includeImports,
                removedEntities, addedEntities, limit));
        result.put("annotation_changes", annotationChanges(left, right, includeImports,
                leftEntities, rightEntities, limit));
        result.put("asserted_axioms", Map.of(
                "added", axiomGroups(addedAxioms, limit),
                "removed", axiomGroups(removedAxioms, limit)));

        boolean headerChanged = idChanged
                || !left.getImportsDeclarations().equals(right.getImportsDeclarations());
        boolean logicalRemoved = removedAxioms.stream().anyMatch(OWLAxiom::isLogicalAxiom);
        boolean logicalAdded = addedAxioms.stream().anyMatch(OWLAxiom::isLogicalAxiom);
        boolean truncatedRightClosure = includeImports && !unresolvedImports.isEmpty();
        // OWL is monotonic, so an ADDED logical axiom can break existing deployments just like a
        // removal (a new DisjointClasses over classes sharing an individual makes the ontology
        // inconsistent): every logical change is potentially breaking. Only growth carrying nothing
        // but declarations/annotations is non-breaking, and a no-new-entity, no-logical-change diff
        // is metadata-only.
        String compatibility;
        if (truncatedRightClosure || headerChanged || !removedEntities.isEmpty()
                || logicalRemoved || logicalAdded) {
            compatibility = "potentially_breaking";
        } else if (addedEntities.isEmpty()) {
            compatibility = "metadata_only";
        } else {
            compatibility = "non_breaking";
        }
        String caveat = "Prototype classification; project-specific breaking-change policy was not applied.";
        if (truncatedRightClosure) {
            // Fail closed for review gates: an unknown share of the right closure never loaded, so no
            // content-based classification can be trusted. Name the IRIs so the gap is actionable.
            caveat += " The right side's imports closure is truncated - unresolved imports: "
                    + String.join(", ", unresolvedImports) + ". Their axioms are invisible to this "
                    + "diff, so the classification was forced to potentially_breaking.";
        }
        if (anonymousChurn) {
            // Anonymous-individual (blank node) NodeIDs are parser-local: a re-parsed document mints
            // fresh ones, so blank-node axioms or ontology-header annotation values appear changed on
            // both sides. Flag it rather than silently reporting a phantom breaking change.
            caveat += " Blank-node (anonymous individual) values differ in axioms or ontology-header "
                    + "annotations; their NodeIDs are not stable across parses, so added/removed "
                    + "churn touching them may be spurious.";
        }
        result.put("compatibility", Map.of(
                "classification", compatibility,
                "policy_driven", false,
                "anonymous_individual_churn", anonymousChurn,
                "caveat", caveat));
        result.put("identical", removedAxioms.isEmpty() && addedAxioms.isEmpty()
                && !idChanged
                && left.getImportsDeclarations().equals(right.getImportsDeclarations())
                && left.getAnnotations().equals(right.getAnnotations()));
        return result;
    }

    /**
     * Two <em>anonymous</em> ontology IDs are treated as unchanged: OWLAPI assigns each a session-local
     * {@code Anonymous-N} id, so raw equality would always report a header change (and thus a phantom
     * breaking change) even for byte-identical headerless documents. A change from/to a named ID, or
     * between two different named IDs, is a genuine header change.
     */
    private static boolean ontologyIdChanged(OWLOntology left, OWLOntology right) {
        boolean leftAnon = left.getOntologyID().isAnonymous();
        boolean rightAnon = right.getOntologyID().isAnonymous();
        if (leftAnon || rightAnon) {
            return leftAnon != rightAnon;
        }
        return !left.getOntologyID().equals(right.getOntologyID());
    }

    /** True when any object (axiom or ontology annotation) references an anonymous individual. */
    private static boolean hasAnonymousIndividual(Set<? extends OWLObject> objects) {
        return objects.stream().anyMatch(object -> !object.getAnonymousIndividuals().isEmpty());
    }

    private static Map<String, Object> pair(Object left, Object right) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("changed", !java.util.Objects.equals(left, right));
        pair.put("left", left);
        pair.put("right", right);
        return pair;
    }

    private static Set<OWLAxiom> axioms(OWLOntology ontology, boolean closure) {
        Set<OWLAxiom> result = new LinkedHashSet<>();
        Collection<OWLOntology> ontologies = closure ? ontology.getImportsClosure() : Set.of(ontology);
        ontologies.forEach(item -> result.addAll(item.getAxioms()));
        return result;
    }

    private static Set<EntityKey> entities(OWLOntology ontology, boolean closure) {
        Set<EntityKey> result = new TreeSet<>();
        Collection<OWLOntology> ontologies = closure ? ontology.getImportsClosure() : Set.of(ontology);
        for (OWLOntology item : ontologies) {
            item.getSignature().stream().filter(entity -> !entity.isBuiltIn())
                    .forEach(entity -> result.add(EntityKey.of(entity)));
        }
        return result;
    }

    private static Map<String, Object> entityGroups(Set<EntityKey> entities, int limit) {
        Map<String, List<String>> grouped = new TreeMap<>();
        for (EntityKey entity : entities) {
            grouped.computeIfAbsent(entity.type, ignored -> new ArrayList<>()).add(entity.iri);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        grouped.forEach((type, iris) -> result.put(type, bounded(iris, limit)));
        result.put("count", entities.size());
        return result;
    }

    private static Map<String, Object> axiomGroups(Set<OWLAxiom> axioms, int limit) {
        // Render each axiom exactly once and sort by the cached string: sorting directly with
        // Comparator.comparing(OWLAxiom::toString) re-renders both comparands on every comparison,
        // which turns the ordering pass into minutes of work at release-scale diffs — and this runs
        // on the Swing EDT.
        List<Map.Entry<String, OWLAxiom>> rendered = new ArrayList<>(axioms.size());
        for (OWLAxiom axiom : axioms) {
            rendered.add(Map.entry(axiom.toString(), axiom));
        }
        rendered.sort(Map.Entry.comparingByKey());
        Map<String, List<Map<String, Object>>> grouped = new TreeMap<>();
        int listed = 0;
        for (Map.Entry<String, OWLAxiom> entry : rendered) {
            if (listed >= Math.max(limit, 0)) {
                break;
            }
            OWLAxiom axiom = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("axiom", entry.getKey());
            row.put("affected_iris", axiom.getSignature().stream().map(entity -> entity.getIRI().toString())
                    .distinct().sorted().toList());
            grouped.computeIfAbsent(axiom.getAxiomType().getName(), ignored -> new ArrayList<>()).add(row);
            listed++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", axioms.size());
        result.put("groups", grouped);
        result.put("truncated", axioms.size() > Math.max(limit, 0));
        return result;
    }

    /**
     * Unique exact-rdfs:label rename candidates. Both sides are indexed once by (entity type, label)
     * so pairing stays near-linear in the removed/added label counts — a pairwise removed x added
     * scan is quadratic and freezes the EDT for minutes on a namespace-scale migration. A removed
     * entity is a candidate iff exactly one added entity of the same type shares at least one of its
     * labels, and that added entity in turn shares labels with exactly one removed entity (the same
     * uniqueness rule applied in both directions).
     */
    private static List<Map<String, Object>> renameCandidates(OWLOntology left, OWLOntology right,
            boolean includeImports, Set<EntityKey> removed, Set<EntityKey> added, int limit) {
        int bounded = Math.max(limit, 0);
        if (bounded == 0) {
            return List.of();
        }
        Map<EntityKey, Set<String>> leftLabels = labels(left, includeImports, removed);
        Map<EntityKey, Set<String>> rightLabels = labels(right, includeImports, added);
        Map<TypeLabel, List<EntityKey>> removedByLabel = indexByLabel(removed, leftLabels);
        Map<TypeLabel, List<EntityKey>> addedByLabel = indexByLabel(added, rightLabels);
        List<Map<String, Object>> candidates = new ArrayList<>();
        // Iterate in the same deterministic order the formerly-unbounded result used after its final
        // sort, so we can stop as soon as the requested sample count is reached instead of retaining
        // every candidate in memory. Type is the stable tie-breaker for a punned IRI.
        List<EntityKey> orderedRemoved = removed.stream()
                .sorted(Comparator.comparing((EntityKey key) -> key.iri)
                        .thenComparing(key -> key.type))
                .toList();
        for (EntityKey oldKey : orderedRemoved) {
            EntityKey replacement = uniqueLabelMatch(oldKey.type,
                    leftLabels.getOrDefault(oldKey, Set.of()), addedByLabel);
            if (replacement == null) continue;
            // The reverse direction: oldKey shares a label with replacement, so a unique reverse
            // match can only be oldKey itself; anything else means the pairing is ambiguous.
            EntityKey reverse = uniqueLabelMatch(replacement.type,
                    rightLabels.getOrDefault(replacement, Set.of()), removedByLabel);
            if (reverse != null) {
                candidates.add(Map.of("from", oldKey.iri, "to", replacement.iri,
                        "entity_type", oldKey.type, "evidence", "unique_exact_rdfs_label"));
                if (candidates.size() >= bounded) {
                    break;
                }
            }
        }
        return candidates;
    }

    private static Map<TypeLabel, List<EntityKey>> indexByLabel(Set<EntityKey> entities,
            Map<EntityKey, Set<String>> labels) {
        Map<TypeLabel, List<EntityKey>> index = new LinkedHashMap<>();
        for (EntityKey entity : entities) {
            for (String label : labels.getOrDefault(entity, Set.of())) {
                index.computeIfAbsent(new TypeLabel(entity.type, label), ignored -> new ArrayList<>())
                        .add(entity);
            }
        }
        return index;
    }

    /**
     * The single entity of {@code type} carrying any of {@code labels}, or {@code null} when none or
     * more than one does. Bails out on the second distinct entity, so even a degenerate shared label
     * ("Same" on thousands of entities) costs a couple of index probes, not a full scan.
     */
    private static EntityKey uniqueLabelMatch(String type, Set<String> labels,
            Map<TypeLabel, List<EntityKey>> index) {
        EntityKey match = null;
        for (String label : labels) {
            for (EntityKey candidate : index.getOrDefault(new TypeLabel(type, label), List.of())) {
                if (match == null) {
                    match = candidate;
                } else if (!match.equals(candidate)) {
                    return null;
                }
            }
        }
        return match;
    }

    /** Labels of the {@code scope} entities only — rename pairing never reads any other entity's. */
    private static Map<EntityKey, Set<String>> labels(OWLOntology ontology, boolean closure,
            Set<EntityKey> scope) {
        Map<EntityKey, Set<String>> result = new LinkedHashMap<>();
        Collection<OWLOntology> ontologies = closure ? ontology.getImportsClosure() : Set.of(ontology);
        for (EntityKey entity : scope) {
            for (OWLOntology item : ontologies) {
                for (OWLAnnotationAssertionAxiom axiom : item.getAnnotationAssertionAxioms(
                        IRI.create(entity.iri))) {
                    if (!axiom.getProperty().isLabel() || !axiom.getValue().asLiteral().isPresent()) continue;
                    OWLLiteral literal = axiom.getValue().asLiteral().get();
                    result.computeIfAbsent(entity, ignored -> new TreeSet<>())
                            .add(literal.getLiteral() + "@" + literal.getLang().toLowerCase());
                }
            }
        }
        return result;
    }

    private static List<Map<String, Object>> annotationChanges(OWLOntology left, OWLOntology right,
            boolean includeImports, Set<EntityKey> leftEntities, Set<EntityKey> rightEntities, int limit) {
        Set<String> commonIris = new TreeSet<>();
        leftEntities.stream().filter(rightEntities::contains).forEach(key -> commonIris.add(key.iri));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String iri : commonIris) {
            Set<String> before = annotationStrings(left, includeImports, IRI.create(iri));
            Set<String> after = annotationStrings(right, includeImports, IRI.create(iri));
            if (before.equals(after)) continue;
            Set<String> removed = difference(before, after);
            Set<String> added = difference(after, before);
            rows.add(Map.of("focus_iri", iri, "added", new ArrayList<>(added),
                    "removed", new ArrayList<>(removed), "categories", annotationCategories(added, removed)));
            if (rows.size() >= Math.max(limit, 0)) break;
        }
        return rows;
    }

    private static Set<String> annotationStrings(OWLOntology ontology, boolean closure, IRI subject) {
        Set<String> result = new TreeSet<>();
        Collection<OWLOntology> ontologies = closure ? ontology.getImportsClosure() : Set.of(ontology);
        for (OWLOntology item : ontologies) {
            item.getAnnotationAssertionAxioms(subject).forEach(axiom -> result.add(
                    axiom.getProperty().getIRI() + "=" + axiom.getValue()));
        }
        return result;
    }

    private static List<String> annotationCategories(Set<String> added, Set<String> removed) {
        String joined = String.join("\n", added) + "\n" + String.join("\n", removed);
        List<String> categories = new ArrayList<>();
        if (joined.contains("label") || joined.contains("prefLabel") || joined.contains("altLabel"))
            categories.add("label_or_synonym");
        if (joined.contains("definition") || joined.contains("comment")) categories.add("definition");
        if (joined.contains("source") || joined.contains("provenance")) categories.add("provenance");
        if (joined.contains("deprecated") || joined.contains("status")) categories.add("lifecycle");
        if (joined.contains("replaced") || joined.contains("IAO_0100001")) categories.add("replacement");
        if (categories.isEmpty()) categories.add("other_annotation");
        return categories;
    }

    private static List<String> bounded(List<String> values, int limit) {
        List<String> sorted = values.stream().sorted().toList();
        return sorted.subList(0, Math.min(sorted.size(), Math.max(limit, 0)));
    }

    private static Set<String> strings(Collection<?> values) {
        Set<String> result = new TreeSet<>();
        values.forEach(value -> result.add(String.valueOf(value)));
        return result;
    }

    private static <T> Set<T> difference(Set<T> left, Set<T> right) {
        Set<T> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    /** Rename-index key: entities may only pair within the same entity type. */
    private record TypeLabel(String type, String label) {
    }

    private record EntityKey(String type, String iri) implements Comparable<EntityKey> {
        static EntityKey of(OWLEntity entity) {
            return new EntityKey(entity.getEntityType().getName().toLowerCase(), entity.getIRI().toString());
        }

        @Override
        public int compareTo(EntityKey other) {
            int byType = type.compareTo(other.type);
            return byType != 0 ? byType : iri.compareTo(other.iri);
        }
    }
}
