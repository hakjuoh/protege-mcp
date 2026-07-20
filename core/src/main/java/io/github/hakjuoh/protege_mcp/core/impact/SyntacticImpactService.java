package io.github.hakjuoh.protege_mcp.core.impact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLNaryPropertyAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLUnaryPropertyAxiom;

/** Protégé-free asserted/syntactic change-impact analysis. */
public final class SyntacticImpactService {

    public static final int DOWNSTREAM_DEPTH_CAP = 3;
    public static final int DOWNSTREAM_SIZE_CAP = 1_000;

    private SyntacticImpactService() {
    }

    /** Compare two ontology snapshots and analyze their asserted delta. */
    public static Result compare(OWLOntology left, OWLOntology right, boolean includeImports) {
        requireOntologies(left, right);
        Set<OWLAxiom> leftAxioms = collect(left, includeImports);
        Set<OWLAxiom> rightAxioms = collect(right, includeImports);
        Set<OWLAxiom> removed = new LinkedHashSet<>(leftAxioms);
        removed.removeAll(rightAxioms);
        Set<OWLAxiom> added = new LinkedHashSet<>(rightAxioms);
        added.removeAll(leftAxioms);
        return analyze(left, right, added, removed, includeImports);
    }

    /** Analyze an already-normalized asserted delta against its before/after ontology contexts. */
    public static Result analyze(OWLOntology left, OWLOntology right,
            Collection<? extends OWLAxiom> addedAxioms,
            Collection<? extends OWLAxiom> removedAxioms, boolean includeImports) {
        requireOntologies(left, right);
        if (addedAxioms == null || removedAxioms == null) {
            throw new IllegalArgumentException("addedAxioms and removedAxioms must not be null");
        }
        Set<OWLAxiom> added = copyAxioms(addedAxioms, "addedAxioms");
        Set<OWLAxiom> removed = copyAxioms(removedAxioms, "removedAxioms");

        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.addAll(includeImports ? left.getImportsClosure() : Set.of(left));
        scope.addAll(includeImports ? right.getImportsClosure() : Set.of(right));
        Set<OWLOntology> closure = new LinkedHashSet<>(left.getImportsClosure());
        closure.addAll(right.getImportsClosure());

        TreeMap<String, int[]> counts = new TreeMap<>();
        for (OWLAxiom axiom : added) {
            for (String iri : affectedIris(axiom)) {
                counts.computeIfAbsent(iri, ignored -> new int[2])[0]++;
            }
        }
        for (OWLAxiom axiom : removed) {
            for (String iri : affectedIris(axiom)) {
                counts.computeIfAbsent(iri, ignored -> new int[2])[1]++;
            }
        }
        List<String> affectedIris = List.copyOf(counts.keySet());
        List<AffectedTerm> affected = counts.entrySet().stream()
                .map(entry -> new AffectedTerm(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingInt(AffectedTerm::total).reversed()
                        .thenComparing(AffectedTerm::iri))
                .toList();

        Set<OWLAxiom> delta = new LinkedHashSet<>(added);
        delta.addAll(removed);
        Set<String> affectedSet = counts.keySet();
        Set<OWLAxiom> referencing = new LinkedHashSet<>();
        for (String iri : affectedIris) {
            IRI value = IRI.create(iri);
            for (OWLOntology ontology : scope) {
                for (OWLEntity entity : ontology.getEntitiesInSignature(value)) {
                    referencing.addAll(ontology.getReferencingAxioms(entity));
                }
                referencing.addAll(ontology.getAnnotationAssertionAxioms(value));
            }
        }
        for (OWLOntology ontology : scope) {
            for (OWLAnnotationAssertionAxiom annotation
                    : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
                if (annotation.getValue() instanceof IRI value
                        && affectedSet.contains(value.toString())) {
                    referencing.add(annotation);
                }
            }
        }
        referencing.removeAll(delta);

        Downstream downstream = downstream(scope, affectedIris);

        Set<OWLEntity> foreign = new LinkedHashSet<>(foreignDeclaredEntities(left,
                left.getImportsClosure()));
        foreign.addAll(foreignDeclaredEntities(right, right.getImportsClosure()));
        List<ForeignImpact> foreignImpacts = new ArrayList<>();
        collectForeign(added, "add", foreign, foreignImpacts);
        collectForeign(removed, "remove", foreign, foreignImpacts);
        foreignImpacts.sort(Comparator.comparing(ForeignImpact::subjectIri)
                .thenComparing(impact -> impact.axiom().toString())
                .thenComparing(ForeignImpact::operation));

        Set<OWLEntity> candidates = new LinkedHashSet<>();
        for (OWLAxiom axiom : delta) {
            addReferencedEntities(axiom, scope, candidates);
        }
        for (OWLAxiom axiom : referencing) {
            addReferencedEntities(axiom, scope, candidates);
        }
        List<OWLEntity> deprecated = candidates.stream()
                .filter(entity -> isDeprecated(entity, closure))
                .sorted(Comparator.comparing((OWLEntity entity) -> entity.getIRI().toString())
                        .thenComparing(entity -> entity.getEntityType().getName()))
                .toList();

        List<OWLAxiom> orderedReferences = referencing.stream()
                .sorted(Comparator.comparing(OWLAxiom::toString)).toList();
        return new Result(added.size(), removed.size(), affected, affectedIris,
                orderedReferences, downstream, foreignImpacts, deprecated);
    }

    private static void requireOntologies(OWLOntology left, OWLOntology right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("left and right ontologies must not be null");
        }
    }

    private static Set<OWLAxiom> copyAxioms(Collection<? extends OWLAxiom> values, String name) {
        Set<OWLAxiom> result = new LinkedHashSet<>();
        for (OWLAxiom value : values) {
            if (value == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            result.add(value);
        }
        return result;
    }

    private static Set<OWLAxiom> collect(OWLOntology ontology, boolean includeImports) {
        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        Set<OWLOntology> ontologies = includeImports ? ontology.getImportsClosure() : Set.of(ontology);
        for (OWLOntology member : ontologies) {
            axioms.addAll(member.getAxioms());
        }
        return axioms;
    }

    private static Set<String> affectedIris(OWLAxiom axiom) {
        Set<String> result = new LinkedHashSet<>();
        for (OWLEntity entity : axiom.getSignature()) {
            result.add(entity.getIRI().toString());
        }
        if (axiom instanceof OWLAnnotationAssertionAxiom annotation) {
            if (annotation.getSubject() instanceof IRI subject) {
                result.add(subject.toString());
            }
            if (annotation.getValue() instanceof IRI value) {
                result.add(value.toString());
            }
        }
        return result;
    }

    private static void addReferencedEntities(OWLAxiom axiom, Set<OWLOntology> scope,
            Set<OWLEntity> result) {
        result.addAll(axiom.getSignature());
        if (axiom instanceof OWLAnnotationAssertionAxiom annotation) {
            if (annotation.getSubject() instanceof IRI subject) {
                for (OWLOntology ontology : scope) {
                    result.addAll(ontology.getEntitiesInSignature(subject));
                }
            }
            if (annotation.getValue() instanceof IRI value) {
                for (OWLOntology ontology : scope) {
                    result.addAll(ontology.getEntitiesInSignature(value));
                }
            }
        }
    }

    private static Downstream downstream(Set<OWLOntology> scope, Collection<String> affected) {
        LinkedHashMap<String, Integer> found = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>(affected);
        List<String> frontier = new ArrayList<>(new TreeSet<>(affected));
        boolean truncated = false;
        for (int depth = 1; depth <= DOWNSTREAM_DEPTH_CAP + 1 && !frontier.isEmpty(); depth++) {
            TreeSet<String> next = new TreeSet<>();
            for (String iri : frontier) {
                IRI value = IRI.create(iri);
                for (OWLOntology ontology : scope) {
                    for (OWLEntity entity : ontology.getEntitiesInSignature(value)) {
                        for (OWLAxiom axiom : ontology.getReferencingAxioms(entity)) {
                            for (OWLEntity signature : axiom.getSignature()) {
                                String candidate = signature.getIRI().toString();
                                if (!visited.contains(candidate)) {
                                    next.add(candidate);
                                }
                            }
                        }
                    }
                }
            }
            if (depth > DOWNSTREAM_DEPTH_CAP) {
                truncated = !next.isEmpty();
                break;
            }
            List<String> level = new ArrayList<>();
            for (String iri : next) {
                if (found.size() >= DOWNSTREAM_SIZE_CAP) {
                    truncated = true;
                    break;
                }
                visited.add(iri);
                found.put(iri, depth);
                level.add(iri);
            }
            if (truncated) {
                break;
            }
            frontier = level;
        }
        List<DownstreamTerm> terms = found.entrySet().stream()
                .map(entry -> new DownstreamTerm(entry.getKey(), entry.getValue())).toList();
        return new Downstream(terms, truncated);
    }

    /** Entities declared in imported closure members but not declared by the active ontology. */
    public static Set<OWLEntity> foreignDeclaredEntities(OWLOntology active,
            Set<OWLOntology> closure) {
        if (active == null || closure == null || closure.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("active and closure members must not be null");
        }
        Set<OWLEntity> activeDeclared = new HashSet<>();
        for (OWLDeclarationAxiom declaration : active.getAxioms(AxiomType.DECLARATION)) {
            activeDeclared.add(declaration.getEntity());
        }
        Set<OWLEntity> foreign = new LinkedHashSet<>();
        for (OWLOntology ontology : closure) {
            if (ontology.equals(active)) {
                continue;
            }
            for (OWLDeclarationAxiom declaration : ontology.getAxioms(AxiomType.DECLARATION)) {
                if (!activeDeclared.contains(declaration.getEntity())) {
                    foreign.add(declaration.getEntity());
                }
            }
        }
        return foreign;
    }

    private static void collectForeign(Collection<? extends OWLAxiom> axioms, String operation,
            Set<OWLEntity> foreign, List<ForeignImpact> result) {
        for (OWLAxiom axiom : axioms) {
            for (OWLEntity subject : subjectEntities(axiom)) {
                if (foreign.contains(subject)) {
                    result.add(new ForeignImpact(operation, subject.getIRI().toString(), axiom));
                    break;
                }
            }
        }
    }

    /** Named entities characterized in the subject position of a TBox/RBox axiom. */
    public static Set<OWLEntity> subjectEntities(OWLAxiom axiom) {
        if (axiom == null) {
            throw new IllegalArgumentException("axiom must not be null");
        }
        Set<OWLEntity> result = new LinkedHashSet<>();
        if (axiom instanceof OWLSubClassOfAxiom subClass) {
            addNamedClass(result, subClass.getSubClass());
        } else if (axiom instanceof OWLNaryClassAxiom nary) {
            for (OWLClassExpression expression : nary.getClassExpressions()) {
                addNamedClass(result, expression);
            }
        } else if (axiom instanceof OWLDisjointUnionAxiom disjointUnion) {
            result.add(disjointUnion.getOWLClass());
        } else if (axiom instanceof OWLHasKeyAxiom hasKey) {
            addNamedClass(result, hasKey.getClassExpression());
        } else if (axiom instanceof OWLPropertyDomainAxiom<?> domain) {
            addNamedProperty(result, domain.getProperty());
        } else if (axiom instanceof OWLPropertyRangeAxiom<?, ?> range) {
            addNamedProperty(result, range.getProperty());
        } else if (axiom instanceof OWLUnaryPropertyAxiom<?> unary) {
            addNamedProperty(result, unary.getProperty());
        } else if (axiom instanceof OWLSubPropertyChainOfAxiom chain) {
            addNamedProperty(result, chain.getSuperProperty());
        } else if (axiom instanceof OWLSubObjectPropertyOfAxiom subObject) {
            addNamedProperty(result, subObject.getSubProperty());
        } else if (axiom instanceof OWLSubPropertyAxiom<?> subProperty) {
            addNamedProperty(result, subProperty.getSubProperty());
        } else if (axiom instanceof OWLNaryPropertyAxiom<?> naryProperty) {
            for (Object property : naryProperty.getProperties()) {
                addNamedProperty(result, property);
            }
        } else if (axiom instanceof OWLInverseObjectPropertiesAxiom inverse) {
            addNamedProperty(result, inverse.getFirstProperty());
            addNamedProperty(result, inverse.getSecondProperty());
        }
        return result;
    }

    private static void addNamedClass(Set<OWLEntity> result, OWLClassExpression expression) {
        if (expression != null && !expression.isAnonymous()) {
            result.add(expression.asOWLClass());
        }
    }

    private static void addNamedProperty(Set<OWLEntity> result, Object property) {
        if (property instanceof OWLObjectPropertyExpression objectProperty
                && !objectProperty.isAnonymous()) {
            result.add(objectProperty.asOWLObjectProperty());
        } else if (property instanceof OWLDataPropertyExpression dataProperty
                && !dataProperty.isAnonymous()) {
            result.add(dataProperty.asOWLDataProperty());
        }
    }

    /** Whether any ontology in the supplied scope asserts {@code owl:deprecated true}. */
    public static boolean isDeprecated(OWLEntity entity, Set<OWLOntology> scope) {
        if (entity == null || scope == null || scope.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("entity and scope members must not be null");
        }
        for (OWLOntology ontology : scope) {
            for (OWLAnnotationAssertionAxiom annotation
                    : ontology.getAnnotationAssertionAxioms(entity.getIRI())) {
                if (!annotation.getProperty().isDeprecated()
                        || !(annotation.getValue() instanceof OWLLiteral literal)) {
                    continue;
                }
                try {
                    if (literal.parseBoolean()) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // A non-boolean owl:deprecated value does not deprecate the entity.
                }
            }
        }
        return false;
    }

    public record AffectedTerm(String iri, int added, int removed) {
        public AffectedTerm {
            if (iri == null || iri.isBlank() || added < 0 || removed < 0 || added + removed == 0) {
                throw new IllegalArgumentException("affected term requires an IRI and a positive count");
            }
        }

        public int total() {
            return added + removed;
        }
    }

    public record DownstreamTerm(String iri, int depth) {
        public DownstreamTerm {
            if (iri == null || iri.isBlank() || depth < 1 || depth > DOWNSTREAM_DEPTH_CAP) {
                throw new IllegalArgumentException("invalid downstream term");
            }
        }
    }

    public record Downstream(List<DownstreamTerm> terms, boolean searchTruncated) {
        public Downstream {
            terms = List.copyOf(terms);
        }

        public Map<String, Object> toMap(int limit) {
            int cap = Math.max(0, limit);
            List<Map<String, Object>> items = new ArrayList<>();
            for (DownstreamTerm term : terms.stream().limit(cap).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("iri", term.iri());
                row.put("depth", term.depth());
                items.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("analysis", "syntactic");
            result.put("depth_cap", DOWNSTREAM_DEPTH_CAP);
            result.put("size_cap", DOWNSTREAM_SIZE_CAP);
            result.put("count", terms.size());
            result.put("items", items);
            if (terms.size() > items.size()) {
                result.put("truncated", terms.size() - items.size());
            }
            if (searchTruncated) {
                result.put("search_truncated", true);
                result.put("search_note", "The breadth-first sweep stopped at its depth/size cap; "
                        + "syntactically reachable terms beyond the cap are neither listed nor counted.");
            }
            return result;
        }
    }

    public record ForeignImpact(String operation, String subjectIri, OWLAxiom axiom) {
        public ForeignImpact {
            if (!Set.of("add", "remove").contains(operation) || subjectIri == null || axiom == null) {
                throw new IllegalArgumentException("invalid foreign impact");
            }
        }
    }

    public record Result(int addedCount, int removedCount, List<AffectedTerm> affectedTerms,
            List<String> affectedIris, List<OWLAxiom> referencingAxioms, Downstream downstream,
            List<ForeignImpact> foreignReaxiomatization, List<OWLEntity> deprecatedTerms) {
        public Result {
            if (addedCount < 0 || removedCount < 0 || affectedTerms == null || affectedIris == null
                    || referencingAxioms == null || downstream == null
                    || foreignReaxiomatization == null || deprecatedTerms == null) {
                throw new IllegalArgumentException("impact result fields must not be null or negative");
            }
            affectedTerms = List.copyOf(affectedTerms);
            affectedIris = List.copyOf(affectedIris);
            referencingAxioms = List.copyOf(referencingAxioms);
            foreignReaxiomatization = List.copyOf(foreignReaxiomatization);
            deprecatedTerms = List.copyOf(deprecatedTerms);
        }

        public Map<String, Object> directlyAffectedMap(int limit) {
            int cap = Math.max(0, limit);
            List<Map<String, Object>> items = new ArrayList<>();
            for (AffectedTerm term : affectedTerms.stream().limit(cap).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("iri", term.iri());
                row.put("added", term.added());
                row.put("removed", term.removed());
                items.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", affectedTerms.size());
            result.put("items", items);
            if (affectedTerms.size() > items.size()) {
                result.put("truncated", affectedTerms.size() - items.size());
            }
            return result;
        }
    }
}
