package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.search.EntitySearcher;

import io.github.hakjuoh.protege_mcp.core.owl.OwlDocumentSignature;
import io.github.hakjuoh.protege_mcp.core.impact.SyntacticImpactService;

/** The shared eleven-rule modelling-quality stage used by live and headless project QC. */
public final class StructuralQcService {

    public static final List<String> CHECK_IDS = List.of(
            "missing_label", "missing_definition", "duplicate_label", "multiple_labels",
            "deprecated_in_use", "undeclared_entity", "property_missing_domain",
            "property_missing_range", "self_subclass", "subclass_cycle", "isolated_class");

    private static final String SKOS_DEFINITION =
            "http://www.w3.org/2004/02/skos/core#definition";
    private static final String[] RESERVED = {
            "http://www.w3.org/2002/07/owl#",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#",
            "http://www.w3.org/2001/XMLSchema#"};
    private static final String[] TOOL_INTERNAL = {
            "https://hakjuoh.github.io/protege-mcp/"};
    private static final String[] WELL_KNOWN = {
            "http://purl.org/dc/terms/", "http://purl.org/dc/elements/1.1/",
            "http://www.w3.org/2004/02/skos/core#", "http://xmlns.com/foaf/0.1/",
            "http://www.w3.org/ns/prov#", "http://www.geneontology.org/formats/oboInOwl#",
            "http://purl.obolibrary.org/obo/IAO_"};

    private StructuralQcService() {
    }

    public static Result evaluate(OWLOntology root, Set<OWLOntology> closure,
            Set<String> disabled, Map<String, String> severityOverrides,
            boolean surfaceInfo) {
        if (root == null || closure == null || !closure.contains(root)) {
            throw new IllegalArgumentException("closure must contain the root ontology");
        }
        if (disabled == null || severityOverrides == null) {
            throw new IllegalArgumentException("structural configuration must not be null");
        }
        List<Check> findings = analyze(Set.of(root), closure);
        int total = 0;
        int warnings = 0;
        int errors = 0;
        Set<String> gatingIdentities = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Check finding : findings) {
            if (disabled.contains(finding.id)) {
                continue;
            }
            String severity = normalizeSeverity(
                    severityOverrides.getOrDefault(finding.id, finding.severity));
            total += finding.entities.size();
            if (finding.entities.isEmpty()) {
                continue;
            }
            List<String> entityIdentities = finding.entities.stream()
                    .map(QcFindingIdentity::entity).sorted().toList();
            List<String> digestIdentities = new ArrayList<>(entityIdentities);
            finding.details.forEach(detail -> digestIdentities.add("detail\u0000" + detail));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", finding.id);
            row.put("severity", severity);
            row.put("count", finding.entities.size());
            row.put("identity_digest", QcFindingIdentity.digest(digestIdentities));
            rows.add(row);
            if ("error".equals(severity)) {
                errors += finding.entities.size();
            } else if ("warning".equals(severity)) {
                warnings += finding.entities.size();
            }
            if ("error".equals(severity) || "warning".equals(severity)) {
                entityIdentities.forEach(value -> gatingIdentities.add(finding.id + "|" + value));
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_issues", total);
        summary.put("warnings", warnings);
        summary.put("errors", errors);
        summary.put("checks", rows);
        QcStageVerdict verdict = errors > 0 ? QcStageVerdict.FAIL
                : warnings > 0 ? QcStageVerdict.WARNING
                : surfaceInfo && total > 0 ? QcStageVerdict.INFO : QcStageVerdict.PASS;
        return new Result(new QcStageExecution("structural", verdict, null, summary),
                gatingIdentities);
    }

    private static List<Check> analyze(Set<OWLOntology> scope, Set<OWLOntology> closure) {
        Signature signature = Signature.of(scope, closure);
        return List.of(
                missingLabel(scope, signature), missingDefinition(scope, signature),
                duplicateLabel(scope, signature), multipleLabels(scope, signature),
                deprecatedInUse(scope, signature), undeclaredEntity(signature),
                propertyMissingDomain(scope, signature), propertyMissingRange(scope, signature),
                selfSubclass(scope, signature), subclassCycle(scope, signature),
                isolatedClass(scope, signature));
    }

    private static Check missingLabel(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("missing_label", "warning", "Entities with no rdfs:label",
                "Add an rdfs:label.");
        signature.labelable.stream().filter(entity -> labels(entity, scope).isEmpty())
                .forEach(check.entities::add);
        return check;
    }

    private static Check missingDefinition(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("missing_definition", "info",
                "Classes/properties with no definition annotation", "Add a definition annotation.");
        signature.definable.stream().filter(entity -> !hasDefinition(entity, scope))
                .forEach(check.entities::add);
        return check;
    }

    private static Check duplicateLabel(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("duplicate_label", "warning",
                "A label shared by more than one entity", "Make labels unique within a language.");
        Map<String, Set<OWLEntity>> byLabel = new TreeMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (OWLEntity entity : signature.labelBearing) {
            for (OWLOntology ontology : scope) {
                for (OWLAnnotation annotation : EntitySearcher.getAnnotations(entity, ontology)) {
                    if (annotation.getProperty().isLabel()
                            && annotation.getValue().asLiteral().isPresent()) {
                        var literal = annotation.getValue().asLiteral().get();
                        String language = literal.getLang() == null ? "" : literal.getLang();
                        String key = literal.getLiteral() + "\u0001" + language;
                        byLabel.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(entity);
                        display.put(key, "\"" + literal.getLiteral() + "\""
                                + (language.isEmpty() ? "" : "@" + language));
                    }
                }
            }
        }
        byLabel.forEach((key, entities) -> {
            if (entities.size() > 1) {
                check.entities.addAll(entities);
                check.details.add("label " + display.get(key) + " used by " + entities.size()
                        + " entities");
            }
        });
        return check;
    }

    private static Check multipleLabels(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("multiple_labels", "warning",
                "An entity with more than one label in the same language",
                "Keep one preferred label per language.");
        for (OWLEntity entity : signature.labelBearing) {
            Map<String, Integer> byLanguage = new LinkedHashMap<>();
            for (OWLOntology ontology : scope) {
                for (OWLAnnotation annotation : EntitySearcher.getAnnotations(entity, ontology)) {
                    if (annotation.getProperty().isLabel()
                            && annotation.getValue().asLiteral().isPresent()) {
                        String language = annotation.getValue().asLiteral().get().getLang();
                        byLanguage.merge(language == null ? "" : language, 1, Integer::sum);
                    }
                }
            }
            if (byLanguage.values().stream().anyMatch(count -> count > 1)) {
                check.entities.add(entity);
            }
        }
        return check;
    }

    private static Check deprecatedInUse(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("deprecated_in_use", "warning",
                "Deprecated entities referenced by logical axioms", "Replace deprecated usages.");
        signature.all.stream().filter(entity -> !reserved(entity))
                .filter(entity -> SyntacticImpactService.isDeprecated(entity, scope))
                .filter(entity -> referencedByLogicalAxiom(entity, scope))
                .forEach(check.entities::add);
        return check;
    }

    private static Check undeclaredEntity(Signature signature) {
        Check check = new Check("undeclared_entity", "info",
                "Entities used but never declared", "Add a declaration or fix the IRI.");
        signature.all.stream().filter(entity -> !reserved(entity))
                .filter(entity -> !signature.declaredInClosure.contains(entity))
                .forEach(check.entities::add);
        return check;
    }

    private static Check propertyMissingDomain(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("property_missing_domain", "info",
                "Object/data properties with no asserted domain", "Add a property domain.");
        signature.objectProperties.stream().filter(value -> EntitySearcher.getDomains(value, scope).isEmpty())
                .forEach(check.entities::add);
        signature.dataProperties.stream().filter(value -> EntitySearcher.getDomains(value, scope).isEmpty())
                .forEach(check.entities::add);
        return check;
    }

    private static Check propertyMissingRange(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("property_missing_range", "info",
                "Object/data properties with no asserted range", "Add a property range.");
        signature.objectProperties.stream().filter(value -> EntitySearcher.getRanges(value, scope).isEmpty())
                .forEach(check.entities::add);
        signature.dataProperties.stream().filter(value -> EntitySearcher.getRanges(value, scope).isEmpty())
                .forEach(check.entities::add);
        return check;
    }

    private static Check selfSubclass(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("self_subclass", "warning",
                "Classes asserted as subclasses of themselves", "Remove the redundant axiom.");
        for (OWLClass clazz : signature.classes) {
            for (OWLClassExpression parent : EntitySearcher.getSuperClasses(clazz, scope)) {
                if (!parent.isAnonymous() && parent.asOWLClass().equals(clazz)) {
                    check.entities.add(clazz);
                    break;
                }
            }
        }
        return check;
    }

    private static Check subclassCycle(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("subclass_cycle", "warning", "Classes in an asserted subclass cycle",
                "Break the cycle or assert equivalence explicitly.");
        Map<OWLClass, List<OWLClass>> graph = new LinkedHashMap<>();
        for (OWLClass clazz : signature.classes) {
            List<OWLClass> parents = new ArrayList<>();
            for (OWLClassExpression parent : EntitySearcher.getSuperClasses(clazz, scope)) {
                if (!parent.isAnonymous() && !parent.asOWLClass().equals(clazz)
                        && signature.classes.contains(parent.asOWLClass())) {
                    parents.add(parent.asOWLClass());
                }
            }
            graph.put(clazz, parents);
        }
        for (List<OWLClass> component : stronglyConnected(graph)) {
            if (component.size() > 1) {
                check.entities.addAll(component);
                List<String> names = component.stream()
                        .map(value -> value.getIRI().getShortForm()).toList();
                check.details.add("cycle of " + component.size() + ": "
                        + String.join(" → ", names));
            }
        }
        return check;
    }

    private static Check isolatedClass(Set<OWLOntology> scope, Signature signature) {
        Check check = new Check("isolated_class", "info",
                "Classes with no asserted parent, child, or logical usage",
                "Place the class in the hierarchy or remove it.");
        for (OWLClass clazz : signature.classes) {
            boolean parent = EntitySearcher.getSuperClasses(clazz, scope).stream()
                    .anyMatch(value -> !value.isAnonymous() && !value.asOWLClass().isOWLThing());
            boolean child = !EntitySearcher.getSubClasses(clazz, scope).isEmpty();
            if (!parent && !child && !referencedByLogicalAxiom(clazz, scope)) {
                check.entities.add(clazz);
            }
        }
        return check;
    }

    private static List<String> labels(OWLEntity entity, Set<OWLOntology> scope) {
        List<String> values = new ArrayList<>();
        for (OWLOntology ontology : scope) {
            for (OWLAnnotation annotation : EntitySearcher.getAnnotations(entity, ontology)) {
                if (annotation.getProperty().isLabel()
                        && annotation.getValue().asLiteral().isPresent()) {
                    values.add(annotation.getValue().asLiteral().get().getLiteral());
                }
            }
        }
        return values;
    }

    private static boolean hasDefinition(OWLEntity entity, Set<OWLOntology> scope) {
        for (OWLOntology ontology : scope) {
            for (OWLAnnotation annotation : EntitySearcher.getAnnotations(entity, ontology)) {
                if (definitionProperty(annotation.getProperty())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean definitionProperty(OWLAnnotationProperty property) {
        String shortForm = property.getIRI().getShortForm();
        return property.isComment() || SKOS_DEFINITION.equals(property.getIRI().toString())
                || shortForm != null && shortForm.toLowerCase(java.util.Locale.ROOT)
                        .endsWith("definition");
    }

    private static boolean referencedByLogicalAxiom(OWLEntity entity, Set<OWLOntology> scope) {
        for (OWLOntology ontology : scope) {
            for (OWLAxiom axiom : ontology.getReferencingAxioms(entity)) {
                if (axiom.isLogicalAxiom()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean reserved(OWLEntity entity) {
        if (entity.isBuiltIn()) {
            return true;
        }
        String iri = entity.getIRI().toString();
        for (String prefix : RESERVED) if (iri.startsWith(prefix)) return true;
        for (String prefix : TOOL_INTERNAL) if (iri.startsWith(prefix)) return true;
        if (entity.isOWLAnnotationProperty()) {
            for (String prefix : WELL_KNOWN) if (iri.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String normalizeSeverity(String value) {
        if (value == null) return "info";
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "error" -> "error";
            case "warn", "warning" -> "warning";
            case "info" -> "info";
            default -> throw new IllegalArgumentException("unknown structural severity: " + value);
        };
    }

    private static List<List<OWLClass>> stronglyConnected(Map<OWLClass, List<OWLClass>> graph) {
        Map<OWLClass, Integer> index = new LinkedHashMap<>();
        Map<OWLClass, Integer> low = new LinkedHashMap<>();
        Set<OWLClass> onStack = new LinkedHashSet<>();
        Deque<OWLClass> stack = new ArrayDeque<>();
        List<List<OWLClass>> result = new ArrayList<>();
        int[] counter = {0};
        for (OWLClass start : graph.keySet()) {
            if (index.containsKey(start)) continue;
            Deque<int[]> positions = new ArrayDeque<>();
            Deque<OWLClass> nodes = new ArrayDeque<>();
            index.put(start, counter[0]);
            low.put(start, counter[0]++);
            stack.push(start);
            onStack.add(start);
            nodes.push(start);
            positions.push(new int[]{0});
            while (!nodes.isEmpty()) {
                OWLClass value = nodes.peek();
                int[] position = positions.peek();
                List<OWLClass> successors = graph.getOrDefault(value, Collections.emptyList());
                if (position[0] < successors.size()) {
                    OWLClass successor = successors.get(position[0]++);
                    if (!index.containsKey(successor)) {
                        index.put(successor, counter[0]);
                        low.put(successor, counter[0]++);
                        stack.push(successor);
                        onStack.add(successor);
                        nodes.push(successor);
                        positions.push(new int[]{0});
                    } else if (onStack.contains(successor)) {
                        low.put(value, Math.min(low.get(value), index.get(successor)));
                    }
                } else {
                    if (low.get(value).equals(index.get(value))) {
                        List<OWLClass> component = new ArrayList<>();
                        OWLClass member;
                        do {
                            member = stack.pop();
                            onStack.remove(member);
                            component.add(member);
                        } while (!member.equals(value));
                        result.add(component);
                    }
                    nodes.pop();
                    positions.pop();
                    if (!nodes.isEmpty()) {
                        OWLClass parent = nodes.peek();
                        low.put(parent, Math.min(low.get(parent), low.get(value)));
                    }
                }
            }
        }
        return result;
    }

    public record Result(QcStageExecution execution, Set<String> gatingIdentities) {
        public Result {
            if (execution == null || gatingIdentities == null) {
                throw new IllegalArgumentException("structural result fields must not be null");
            }
            gatingIdentities = Set.copyOf(gatingIdentities);
        }
    }

    private static final class Check {
        final String id;
        final String severity;
        final String title;
        final String suggestion;
        final Set<OWLEntity> entities = new LinkedHashSet<>();
        final List<String> details = new ArrayList<>();

        Check(String id, String severity, String title, String suggestion) {
            this.id = id;
            this.severity = severity;
            this.title = title;
            this.suggestion = suggestion;
        }
    }

    private static final class Signature {
        final Set<OWLEntity> all = new LinkedHashSet<>();
        final Set<OWLClass> classes = new LinkedHashSet<>();
        final Set<OWLObjectProperty> objectProperties = new LinkedHashSet<>();
        final Set<OWLDataProperty> dataProperties = new LinkedHashSet<>();
        final Set<OWLEntity> labelBearing = new LinkedHashSet<>();
        final Set<OWLEntity> labelable = new LinkedHashSet<>();
        final Set<OWLEntity> definable = new LinkedHashSet<>();
        final Set<OWLEntity> declaredInClosure = new LinkedHashSet<>();

        static Signature of(Set<OWLOntology> scope, Set<OWLOntology> closure) {
            Signature signature = new Signature();
            scope.forEach(ontology -> signature.all.addAll(OwlDocumentSignature.of(ontology)));
            for (OWLOntology ontology : scope) {
                ontology.getClassesInSignature().stream().filter(value -> !reserved(value))
                        .forEach(signature.classes::add);
            }
            Set<OWLEntity> declaredInScope = declarations(scope);
            Set<OWLEntity> declaredInClosure = declarations(closure);
            signature.declaredInClosure.addAll(declaredInClosure);
            for (OWLEntity entity : signature.all) {
                if (reserved(entity)) continue;
                if (entity.isOWLClass() || entity.isOWLObjectProperty() || entity.isOWLDataProperty()
                        || entity.isOWLNamedIndividual()) {
                    signature.labelBearing.add(entity);
                }
                if (!declaredInScope.contains(entity) && declaredInClosure.contains(entity)) continue;
                if (entity.isOWLClass()) {
                    signature.labelable.add(entity);
                    signature.definable.add(entity);
                } else if (entity.isOWLObjectProperty()) {
                    signature.objectProperties.add(entity.asOWLObjectProperty());
                    signature.labelable.add(entity);
                    signature.definable.add(entity);
                } else if (entity.isOWLDataProperty()) {
                    signature.dataProperties.add(entity.asOWLDataProperty());
                    signature.labelable.add(entity);
                    signature.definable.add(entity);
                } else if (entity.isOWLNamedIndividual()) {
                    signature.labelable.add(entity);
                } else if (entity.isOWLAnnotationProperty()) {
                    signature.definable.add(entity);
                }
            }
            return signature;
        }

        private static Set<OWLEntity> declarations(Set<OWLOntology> ontologies) {
            Set<OWLEntity> declared = new LinkedHashSet<>();
            for (OWLOntology ontology : ontologies) {
                for (OWLDeclarationAxiom axiom : ontology.getAxioms(AxiomType.DECLARATION)) {
                    declared.add(axiom.getEntity());
                }
            }
            return declared;
        }
    }
}
