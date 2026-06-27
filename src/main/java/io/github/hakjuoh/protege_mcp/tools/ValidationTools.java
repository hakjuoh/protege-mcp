package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.search.EntitySearcher;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * A modelling-quality audit (the layer that the reasoner-centric tools don't cover): missing/duplicate
 * labels, missing definitions, deprecated-but-still-used terms, undeclared entities, properties with no
 * domain/range, and asserted subclass cycles / self-subclassing / isolated classes. Every check is a
 * pure function over a set of ontologies (so it is unit-tested directly on a hand-built ontology); the
 * tool handler renders the findings as JSON. Logical (satisfiability) checks stay with the reasoner —
 * see run_reasoner / get_unsatisfiable_classes.
 */
public final class ValidationTools {

    private ValidationTools() {
    }

    static final String SKOS_DEFINITION = "http://www.w3.org/2004/02/skos/core#definition";

    /** Reserved vocabularies whose entities are never flagged. */
    private static final String[] RESERVED = {
            "http://www.w3.org/2002/07/owl#",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#",
            "http://www.w3.org/2001/XMLSchema#"};

    /** The check ids, in report order. */
    static final List<String> CHECK_IDS = Collections.unmodifiableList(java.util.Arrays.asList(
            "missing_label", "missing_definition", "duplicate_label", "multiple_labels",
            "deprecated_in_use", "undeclared_entity", "property_missing_domain",
            "property_missing_range", "self_subclass", "subclass_cycle", "isolated_class"));

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("validate_ontology",
                "Audit the active ontology for modelling-quality issues (not logical consistency — use "
                        + "run_reasoner for that). Runs structural checks and reports, per check, a "
                        + "count, sample offenders, and a fix suggestion. Checks: "
                        + String.join(", ", CHECK_IDS) + ". Set include_imports=true to audit the "
                        + "whole imports closure; pass 'checks' to run a subset.",
                Tools.schema()
                        .bool("include_imports", "Audit the imports closure too (default false).")
                        .strArray("checks", "Subset of check ids to run (default all).")
                        .integer("limit", "Max sample offenders/details per check (default 25).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    List<String> requested = Tools.stringList(a, "checks");
                    int limit = Tools.optInt(a, "limit", 25);
                    return ctx.access().compute(mm -> {
                        OWLOntology active = mm.getActiveOntology();
                        Set<OWLOntology> scope = includeImports
                                ? active.getImportsClosure()
                                : Collections.singleton(active);
                        List<Finding> findings = analyze(scope);
                        List<Map<String, Object>> checksJson = new ArrayList<>();
                        int totalIssues = 0;
                        for (Finding f : findings) {
                            if (!requested.isEmpty() && !requested.contains(f.id)) {
                                continue;
                            }
                            totalIssues += f.count();
                            checksJson.add(f.toJson(mm, limit));
                        }
                        return Tools.json()
                                .put("scope", includeImports ? "imports_closure" : "active")
                                .put("total_issues", totalIssues)
                                .put("checks", checksJson)
                                .put("reasoner_note", "These are modelling-quality checks. For logical "
                                        + "consistency/satisfiability run run_reasoner then "
                                        + "get_unsatisfiable_classes.")
                                .result();
                    });
                })));

        return tools;
    }

    // ================================================================== analysis core (pure)

    /** Run every check over {@code scope} and return findings in {@link #CHECK_IDS} order. */
    static List<Finding> analyze(Set<OWLOntology> scope) {
        Signature sig = Signature.of(scope);
        List<Finding> out = new ArrayList<>();
        out.add(missingLabel(scope, sig));
        out.add(missingDefinition(scope, sig));
        out.add(duplicateLabel(scope, sig));
        out.add(multipleLabels(scope, sig));
        out.add(deprecatedInUse(scope, sig));
        out.add(undeclaredEntity(scope, sig));
        out.add(propertyMissingDomain(scope, sig));
        out.add(propertyMissingRange(scope, sig));
        out.add(selfSubclass(scope, sig));
        out.add(subclassCycle(scope, sig));
        out.add(isolatedClass(scope, sig));
        return out;
    }

    private static Finding missingLabel(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("missing_label", "warning", "Entities with no rdfs:label",
                "Add an rdfs:label with add_annotation.");
        for (OWLEntity e : sig.labelable) {
            if (labels(e, scope).isEmpty()) {
                f.entities.add(e);
            }
        }
        return f;
    }

    private static Finding missingDefinition(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("missing_definition", "info",
                "Classes/properties (incl. annotation properties) with no definition annotation "
                        + "(rdfs:comment, skos:definition, or a *Definition annotation property)",
                "Add an rdfs:comment, skos:definition, or e.g. iof-av:naturalLanguageDefinition "
                        + "with add_annotation.");
        for (OWLEntity e : sig.definable) {
            if (!hasDefinition(e, scope)) {
                f.entities.add(e);
            }
        }
        return f;
    }

    private static Finding duplicateLabel(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("duplicate_label", "warning",
                "An rdfs:label (same text AND language) shared by more than one entity",
                "Make labels unique within a language, or merge the duplicate entities "
                        + "(rename_entity / delete_entity). Labels in different languages are NOT duplicates.");
        // Key on the full (lexical form, language) pair: "Bank"@en and "Bank"@fr are distinct labels
        // on legitimately distinct entities, not duplicates.
        Map<String, Set<OWLEntity>> byLabel = new TreeMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (OWLEntity e : sig.labelable) {
            for (OWLOntology o : scope) {
                for (OWLAnnotation ann : EntitySearcher.getAnnotations(e, o)) {
                    if (ann.getProperty().isLabel() && ann.getValue().asLiteral().isPresent()) {
                        String lex = ann.getValue().asLiteral().get().getLiteral();
                        String lang = ann.getValue().asLiteral().get().getLang();
                        lang = lang == null ? "" : lang;
                        String key = lex + "" + lang;
                        byLabel.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(e);
                        display.put(key, "\"" + lex + "\"" + (lang.isEmpty() ? "" : "@" + lang));
                    }
                }
            }
        }
        for (Map.Entry<String, Set<OWLEntity>> en : byLabel.entrySet()) {
            if (en.getValue().size() > 1) {
                f.entities.addAll(en.getValue());
                f.details.add("label " + display.get(en.getKey()) + " used by "
                        + en.getValue().size() + " entities");
            }
        }
        return f;
    }

    private static Finding multipleLabels(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("multiple_labels", "warning",
                "An entity with more than one rdfs:label in the same language",
                "Keep one label per language; move the rest to skos:altLabel or rdfs:comment.");
        for (OWLEntity e : sig.labelable) {
            Map<String, Integer> byLang = new LinkedHashMap<>();
            for (OWLOntology o : scope) {
                for (OWLAnnotation ann : EntitySearcher.getAnnotations(e, o)) {
                    if (ann.getProperty().isLabel() && ann.getValue().asLiteral().isPresent()) {
                        String lang = ann.getValue().asLiteral().get().getLang();
                        byLang.merge(lang == null ? "" : lang, 1, Integer::sum);
                    }
                }
            }
            if (byLang.values().stream().anyMatch(n -> n > 1)) {
                f.entities.add(e);
            }
        }
        return f;
    }

    private static Finding deprecatedInUse(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("deprecated_in_use", "warning",
                "Deprecated entities still referenced by logical axioms",
                "Replace usages of the deprecated term, or remove the owl:deprecated marker.");
        // Any entity kind can be deprecated (incl. annotation properties / datatypes), so scan the
        // full signature rather than just the labelable subset.
        for (OWLEntity e : sig.all) {
            if (isReserved(e)) {
                continue;
            }
            if (ContextTools.isDeprecated(e, scope) && referencedByLogicalAxiom(e, scope)) {
                f.entities.add(e);
            }
        }
        return f;
    }

    private static Finding undeclaredEntity(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("undeclared_entity", "info",
                "Entities used in axioms but never declared",
                "Add a declaration (create_entity or add_axiom axiom_type=declaration), or fix a typo'd IRI.");
        for (OWLEntity e : sig.all) {
            if (isReserved(e)) {
                continue;
            }
            // Resolve declarations against each scope ontology's IMPORTS CLOSURE, not just the
            // audited ontology: a term declared upstream (e.g. an imported BFO/IOF class) but merely
            // referenced locally is declared, not undeclared — even when the audit scope is the
            // active ontology alone (include_imports=false).
            boolean declared = false;
            for (OWLOntology o : scope) {
                if (o.isDeclared(e, Imports.INCLUDED)) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                f.entities.add(e);
            }
        }
        return f;
    }

    private static Finding propertyMissingDomain(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("property_missing_domain", "info",
                "Object/data properties with no asserted domain",
                "Add a domain with add_axiom axiom_type=object_property_domain / data_property_domain.");
        for (OWLObjectProperty p : sig.objectProperties) {
            if (EntitySearcher.getDomains(p, scope).isEmpty()) {
                f.entities.add(p);
            }
        }
        for (OWLDataProperty p : sig.dataProperties) {
            if (EntitySearcher.getDomains(p, scope).isEmpty()) {
                f.entities.add(p);
            }
        }
        return f;
    }

    private static Finding propertyMissingRange(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("property_missing_range", "info",
                "Object/data properties with no asserted range",
                "Add a range with add_axiom axiom_type=object_property_range / data_property_range.");
        for (OWLObjectProperty p : sig.objectProperties) {
            if (EntitySearcher.getRanges(p, scope).isEmpty()) {
                f.entities.add(p);
            }
        }
        for (OWLDataProperty p : sig.dataProperties) {
            if (EntitySearcher.getRanges(p, scope).isEmpty()) {
                f.entities.add(p);
            }
        }
        return f;
    }

    private static Finding selfSubclass(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("self_subclass", "warning", "Classes asserted to be a subclass of themselves",
                "Remove the redundant SubClassOf(C, C) axiom with remove_axiom.");
        for (OWLClass c : sig.classes) {
            for (OWLClassExpression sup : EntitySearcher.getSuperClasses(c, scope)) {
                if (!sup.isAnonymous() && sup.asOWLClass().equals(c)) {
                    f.entities.add(c);
                    break;
                }
            }
        }
        return f;
    }

    private static Finding subclassCycle(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("subclass_cycle", "warning",
                "Classes in an asserted subclass cycle (mutually equivalent by entailment)",
                "Break the cycle by removing a SubClassOf axiom, or assert EquivalentClasses if intended.");
        // Build the named asserted-superclass graph, then collect non-trivial SCCs (Tarjan, iterative).
        Map<OWLClass, List<OWLClass>> graph = new LinkedHashMap<>();
        for (OWLClass c : sig.classes) {
            List<OWLClass> supers = new ArrayList<>();
            for (OWLClassExpression sup : EntitySearcher.getSuperClasses(c, scope)) {
                if (!sup.isAnonymous() && !sup.asOWLClass().equals(c) && sig.classes.contains(sup.asOWLClass())) {
                    supers.add(sup.asOWLClass());
                }
            }
            graph.put(c, supers);
        }
        for (List<OWLClass> component : Tarjan.stronglyConnected(graph)) {
            if (component.size() > 1) {
                f.entities.addAll(component);
                List<String> iris = new ArrayList<>();
                for (OWLClass c : component) {
                    iris.add(c.getIRI().getShortForm());
                }
                f.details.add("cycle of " + component.size() + ": " + String.join(" → ", iris));
            }
        }
        return f;
    }

    private static Finding isolatedClass(Set<OWLOntology> scope, Signature sig) {
        Finding f = new Finding("isolated_class", "info",
                "Named classes with no asserted parent, child, or other logical usage",
                "Place the class in the hierarchy (add_subclass_of) or remove it if unused.");
        for (OWLClass c : sig.classes) {
            boolean hasNamedSuper = false;
            for (OWLClassExpression sup : EntitySearcher.getSuperClasses(c, scope)) {
                if (!sup.isAnonymous() && !sup.asOWLClass().isOWLThing()) {
                    hasNamedSuper = true;
                    break;
                }
            }
            boolean hasSub = !EntitySearcher.getSubClasses(c, scope).isEmpty();
            if (hasNamedSuper || hasSub) {
                continue;
            }
            if (!referencedByLogicalAxiom(c, scope)) {
                f.entities.add(c);
            }
        }
        return f;
    }

    // ------------------------------------------------------------------ shared analysis helpers

    /** Lexical forms of an entity's rdfs:label literals across the scope. */
    static List<String> labels(OWLEntity e, Set<OWLOntology> scope) {
        List<String> out = new ArrayList<>();
        for (OWLOntology o : scope) {
            for (OWLAnnotation ann : EntitySearcher.getAnnotations(e, o)) {
                if (ann.getProperty().isLabel() && ann.getValue().asLiteral().isPresent()) {
                    out.add(ann.getValue().asLiteral().get().getLiteral());
                }
            }
        }
        return out;
    }

    private static boolean hasDefinition(OWLEntity e, Set<OWLOntology> scope) {
        for (OWLOntology o : scope) {
            for (OWLAnnotation ann : EntitySearcher.getAnnotations(e, o)) {
                if (isDefinitionProperty(ann.getProperty())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Recognise a definition-bearing annotation property: rdfs:comment, skos:definition, or any
     * property whose local name ends with "definition" (case-insensitive). The suffix rule covers the
     * IOF Annotation Vocabulary's naturalLanguageDefinition / semiFormalNaturalLanguageDefinition /
     * firstOrderLogicDefinition / logicDefinition (and the same convention in other ontologies), which
     * the old rdfs:comment-or-skos:definition test treated as "missing a definition".
     */
    static boolean isDefinitionProperty(OWLAnnotationProperty p) {
        if (p.isComment()) {
            return true;
        }
        if (p.getIRI().toString().equals(SKOS_DEFINITION)) {
            return true;
        }
        String shortForm = p.getIRI().getShortForm();
        return shortForm != null
                && shortForm.toLowerCase(java.util.Locale.ROOT).endsWith("definition");
    }

    /** True if some logical (non-declaration, non-annotation) axiom in scope references {@code e}. */
    private static boolean referencedByLogicalAxiom(OWLEntity e, Set<OWLOntology> scope) {
        for (OWLOntology o : scope) {
            for (OWLAxiom ax : o.getReferencingAxioms(e)) {
                if (ax.isLogicalAxiom()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isReserved(OWLEntity e) {
        if (e.isBuiltIn()) {
            return true;
        }
        String iri = e.getIRI().toString();
        for (String ns : RESERVED) {
            if (iri.startsWith(ns)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ types

    /** A single check's result: offending entities plus optional human-readable detail lines. */
    static final class Finding {
        final String id;
        final String severity;
        final String title;
        final String suggestion;
        final Set<OWLEntity> entities = new LinkedHashSet<>();
        final List<String> details = new ArrayList<>();

        Finding(String id, String severity, String title, String suggestion) {
            this.id = id;
            this.severity = severity;
            this.title = title;
            this.suggestion = suggestion;
        }

        int count() {
            return entities.size();
        }

        Map<String, Object> toJson(OWLModelManager mm, int limit) {
            Tools.Json json = Tools.json()
                    .put("id", id)
                    .put("severity", severity)
                    .put("title", title)
                    .put("count", entities.size())
                    .put("suggestion", suggestion)
                    .put("examples", Tools.entityList(mm, entities, limit));
            if (!details.isEmpty()) {
                json.put("details", details.size() > limit
                        ? new ArrayList<>(details.subList(0, limit)) : details);
            }
            return json.map();
        }
    }

    /** The audited signature, partitioned once for every check. Entities in reserved vocabularies are dropped. */
    static final class Signature {
        final Set<OWLEntity> all = new LinkedHashSet<>();
        final Set<OWLClass> classes = new LinkedHashSet<>();
        final Set<OWLObjectProperty> objectProperties = new LinkedHashSet<>();
        final Set<OWLDataProperty> dataProperties = new LinkedHashSet<>();
        /** Entities expected to carry a label: classes, properties, named individuals. */
        final Set<OWLEntity> labelable = new LinkedHashSet<>();
        /** Entities expected to carry a definition: classes and properties. */
        final Set<OWLEntity> definable = new LinkedHashSet<>();

        static Signature of(Set<OWLOntology> scope) {
            Signature s = new Signature();
            for (OWLOntology o : scope) {
                s.all.addAll(o.getSignature());
            }
            for (OWLOntology o : scope) {
                collect(s, o);
            }
            return s;
        }

        private static void collect(Signature s, OWLOntology o) {
            for (OWLClass c : o.getClassesInSignature()) {
                if (!reserved(c)) {
                    s.classes.add(c);
                    s.labelable.add(c);
                    s.definable.add(c);
                }
            }
            for (OWLObjectProperty p : o.getObjectPropertiesInSignature()) {
                if (!reserved(p)) {
                    s.objectProperties.add(p);
                    s.labelable.add(p);
                    s.definable.add(p);
                }
            }
            for (OWLDataProperty p : o.getDataPropertiesInSignature()) {
                if (!reserved(p)) {
                    s.dataProperties.add(p);
                    s.labelable.add(p);
                    s.definable.add(p);
                }
            }
            o.getIndividualsInSignature().forEach(i -> {
                if (!reserved(i)) {
                    s.labelable.add(i);
                }
            });
            o.getAnnotationPropertiesInSignature().forEach(p -> {
                if (!reserved(p)) {
                    s.definable.add(p);
                }
            });
        }

        private static boolean reserved(OWLEntity e) {
            if (e.isBuiltIn()) {
                return true;
            }
            String iri = e.getIRI().toString();
            for (String ns : RESERVED) {
                if (iri.startsWith(ns)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Iterative Tarjan strongly-connected-components over an adjacency map. */
    static final class Tarjan {
        private Tarjan() {
        }

        static List<List<OWLClass>> stronglyConnected(Map<OWLClass, List<OWLClass>> graph) {
            Map<OWLClass, Integer> index = new LinkedHashMap<>();
            Map<OWLClass, Integer> low = new LinkedHashMap<>();
            Set<OWLClass> onStack = new LinkedHashSet<>();
            java.util.Deque<OWLClass> stack = new java.util.ArrayDeque<>();
            List<List<OWLClass>> result = new ArrayList<>();
            int[] counter = {0};
            for (OWLClass start : graph.keySet()) {
                if (index.containsKey(start)) {
                    continue;
                }
                // Iterative DFS: frames hold the node and the position in its successor list.
                java.util.Deque<int[]> iter = new java.util.ArrayDeque<>();
                java.util.Deque<OWLClass> nodes = new java.util.ArrayDeque<>();
                index.put(start, counter[0]);
                low.put(start, counter[0]);
                counter[0]++;
                stack.push(start);
                onStack.add(start);
                nodes.push(start);
                iter.push(new int[]{0});
                while (!nodes.isEmpty()) {
                    OWLClass v = nodes.peek();
                    int[] pos = iter.peek();
                    List<OWLClass> succ = graph.getOrDefault(v, Collections.emptyList());
                    if (pos[0] < succ.size()) {
                        OWLClass w = succ.get(pos[0]++);
                        if (!index.containsKey(w)) {
                            index.put(w, counter[0]);
                            low.put(w, counter[0]);
                            counter[0]++;
                            stack.push(w);
                            onStack.add(w);
                            nodes.push(w);
                            iter.push(new int[]{0});
                        } else if (onStack.contains(w)) {
                            low.put(v, Math.min(low.get(v), index.get(w)));
                        }
                    } else {
                        if (low.get(v).equals(index.get(v))) {
                            List<OWLClass> component = new ArrayList<>();
                            OWLClass w;
                            do {
                                w = stack.pop();
                                onStack.remove(w);
                                component.add(w);
                            } while (!w.equals(v));
                            result.add(component);
                        }
                        nodes.pop();
                        iter.pop();
                        if (!nodes.isEmpty()) {
                            OWLClass parent = nodes.peek();
                            low.put(parent, Math.min(low.get(parent), low.get(v)));
                        }
                    }
                }
            }
            return result;
        }
    }
}
