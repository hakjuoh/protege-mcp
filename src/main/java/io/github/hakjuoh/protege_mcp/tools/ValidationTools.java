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
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
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
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.search.EntitySearcher;

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

    /** Tool-internal namespaces this plugin mints for bookkeeping (never project terms) — the CQ
     * ontology-annotations store's competencyQuestion property lives here; it must not be audited as an
     * owned term. */
    private static final String[] TOOL_INTERNAL = {
            "https://hakjuoh.github.io/protege-mcp/"};

    /** Well-known external metadata vocabularies whose ANNOTATION PROPERTIES the project never owns (so a
     * stray dcterms:title used as an ontology annotation is not demanded to carry isDefinedBy/definition or
     * sit in the project namespace). Scoped to annotation properties only, so imported IAO/BFO CLASSES are
     * still audited under include_imports. */
    private static final String[] WELL_KNOWN = {
            "http://purl.org/dc/terms/",
            "http://purl.org/dc/elements/1.1/",
            "http://www.w3.org/2004/02/skos/core#",
            "http://xmlns.com/foaf/0.1/",
            "http://www.w3.org/ns/prov#",
            "http://www.geneontology.org/formats/oboInOwl#",
            "http://purl.obolibrary.org/obo/IAO_"};

    /** The check ids, in report order. */
    static final List<String> CHECK_IDS = Collections.unmodifiableList(java.util.Arrays.asList(
            "missing_label", "missing_definition", "duplicate_label", "multiple_labels",
            "deprecated_in_use", "undeclared_entity", "property_missing_domain",
            "property_missing_range", "self_subclass", "subclass_cycle", "isolated_class"));

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("validate_ontology",
                "Audit the active ontology for modelling-quality issues (not logical consistency — use "
                        + "run_reasoner for that). Runs structural checks and reports, per check, a "
                        + "count, sample offenders, and a fix suggestion. Checks: "
                        + String.join(", ", CHECK_IDS) + ". Imported terms (declared upstream) are not "
                        + "flagged for missing label/definition/domain/range when auditing the active "
                        + "ontology alone; set include_imports=true to audit the whole imports closure. "
                        + "Pass 'checks' to run a subset. These are modelling-quality checks only; set "
                        + "with_reasoner=true to also include the reasoner's consistency / "
                        + "unsatisfiable-class verdict (a clean audit is NOT proof of logical "
                        + "consistency).",
                Tools.schema()
                        .bool("include_imports", "Audit the imports closure too (default false).")
                        .strArray("checks", "Subset of check ids to run (default all).")
                        .bool("with_reasoner", "Also report the reasoner's consistency / unsatisfiable "
                                + "classes (uses the already-classified reasoner; run_reasoner first for "
                                + "a current verdict). Default false.")
                        .integer("limit", "Max sample offenders/details per check (default 25).")
                        .integer("timeout_ms", "Time budget in ms before the call returns a timeout "
                                + "error (default 60000). The structural checks run on the model thread "
                                + "and are not interrupted mid-run, so this bounds the caller's wait, "
                                + "not the on-thread work itself.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    boolean withReasoner = Tools.optBool(a, "with_reasoner", false);
                    List<String> requested = Tools.stringList(a, "checks");
                    int limit = Tools.optInt(a, "limit", 25);
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    if (timeout <= 0) {
                        timeout = 60_000;
                    }
                    final int timeoutMs = timeout;
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
                        Tools.Json json = Tools.json()
                                .put("scope", includeImports ? "imports_closure" : "active")
                                .put("total_issues", totalIssues)
                                .put("checks", checksJson);
                        if (withReasoner) {
                            json.put("reasoner", reasonerVerdict(mm, limit));
                        }
                        return json
                                .put("reasoner_note", "These are modelling-quality checks. For logical "
                                        + "consistency/satisfiability run run_reasoner then "
                                        + "get_unsatisfiable_classes (or pass with_reasoner=true).")
                                .result();
                    }, timeoutMs);
                }));
    }

    /**
     * The reasoner's logical verdict to complement the structural checks: status, whether results are
     * current, consistency, and (when consistent) the unsatisfiable named classes. Uses the
     * already-selected/classified reasoner — it does not run a classification itself (run_reasoner
     * does), so when results are stale/absent it says so rather than blocking.
     *
     * <p>Package-private (0.4.0): {@code run_qc_suite}'s reasoner stage composes this so a suite over an
     * unclassified ontology degrades to {@code ran:false} instead of throwing.
     */
    static Map<String, Object> reasonerVerdict(OWLModelManager mm, int limit) {
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        ReasonerStatus status = rm.getReasonerStatus();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", String.valueOf(status));
        boolean current = status == ReasonerStatus.INITIALIZED || status == ReasonerStatus.INCONSISTENT;
        m.put("results_available", current);
        if (!current) {
            m.put("note", "No current reasoner results — run run_reasoner first for a logical verdict.");
            return m;
        }
        m.putAll(reasonerVerdict(mm, rm.getCurrentReasoner(), limit));
        return m;
    }

    /**
     * The reasoner's consistency / unsatisfiable-class verdict, split out with the reasoner injected so
     * it can be exercised headless against an OWL API StructuralReasoner. Returns the {@code consistent
     * / unsatisfiable_* / note / error} portion; {@link #reasonerVerdict(OWLModelManager, int)} prepends
     * the status/results-availability keys and merges this in.
     */
    static Map<String, Object> reasonerVerdict(OWLModelManager mm, OWLReasoner reasoner, int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            boolean consistent = reasoner.isConsistent();
            m.put("consistent", consistent);
            if (consistent) {
                Set<OWLClass> unsat = reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom();
                m.put("unsatisfiable_count", unsat.size());
                m.put("unsatisfiable_classes", Tools.entityList(mm, unsat, limit));
            } else {
                m.put("note", "Ontology is INCONSISTENT — everything is entailed. Run "
                        + "explain_inconsistency to find a minimal set of axioms causing the "
                        + "contradiction.");
            }
        } catch (RuntimeException e) {
            m.put("error", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        return m;
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
                "Add an rdfs:comment, skos:definition, or your ontology's own *Definition "
                        + "annotation property with add_annotation.");
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
        // Iterate labelBearing (the full signature), not the owned subset: a collision between two
        // labels asserted within scope is a real local issue even if one subject is an imported IRI
        // that the active ontology annotated locally.
        Map<String, Set<OWLEntity>> byLabel = new TreeMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (OWLEntity e : sig.labelBearing) {
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
        // Like duplicate_label, this is about label assertions present in scope, so it runs over the
        // full labelBearing signature: 2+ same-language labels asserted locally are a smell even when
        // the subject IRI is imported.
        for (OWLEntity e : sig.labelBearing) {
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
        for (String ns : TOOL_INTERNAL) {
            if (iri.startsWith(ns)) {
                return true;
            }
        }
        if (e.isOWLAnnotationProperty()) {
            for (String ns : WELL_KNOWN) {
                if (iri.startsWith(ns)) {
                    return true;
                }
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

    /**
     * The audited signature, partitioned once for every check. Entities in reserved vocabularies are dropped.
     *
     * <p>Three partitions with deliberately different scopes:
     * <ul>
     *   <li>{@link #all} and {@link #classes} hold the full <em>referenced</em> signature of the audited
     *       ontologies — used by the axiom-structural checks (undeclared, deprecated, self/cycle/isolated),
     *       which reason about axioms asserted in scope and may legitimately mention imported entities.</li>
     *   <li>{@link #labelBearing} holds every label-capable entity in the referenced signature. The
     *       label-collision checks (duplicate_label, multiple_labels) run over it because a collision
     *       between labels <em>asserted within scope</em> is a real local issue regardless of whether a
     *       subject IRI happens to be imported (the active ontology may annotate an imported IRI locally).</li>
     *   <li>{@link #labelable}, {@link #definable}, {@link #objectProperties} and {@link #dataProperties}
     *       hold only the entities the audited scope is <em>responsible for</em> — i.e. not purely imported.
     *       The "missing X" quality checks (missing label/definition/domain/range) run over these, so an
     *       imported term whose label/definition/domain/range lives in its home ontology is not a false
     *       positive when only the active ontology is audited (include_imports=false). Audit the imports
     *       closure (include_imports=true) and the upstream ontologies enter scope, so their terms become
     *       "owned" and are audited normally.</li>
     * </ul>
     */
    static final class Signature {
        final Set<OWLEntity> all = new LinkedHashSet<>();
        final Set<OWLClass> classes = new LinkedHashSet<>();
        final Set<OWLObjectProperty> objectProperties = new LinkedHashSet<>();
        final Set<OWLDataProperty> dataProperties = new LinkedHashSet<>();
        /** Every label-capable entity in the referenced signature (collision checks; ownership-agnostic). */
        final Set<OWLEntity> labelBearing = new LinkedHashSet<>();
        /** Owned-by-scope entities expected to carry a label: classes, properties, named individuals. */
        final Set<OWLEntity> labelable = new LinkedHashSet<>();
        /** Owned-by-scope entities expected to carry a definition: classes and properties. */
        final Set<OWLEntity> definable = new LinkedHashSet<>();

        static Signature of(Set<OWLOntology> scope) {
            Signature s = new Signature();
            // Full referenced signature (declared OR merely used) for the axiom-structural checks.
            for (OWLOntology o : scope) {
                s.all.addAll(o.getSignature());
            }
            for (OWLOntology o : scope) {
                for (OWLClass c : o.getClassesInSignature()) {
                    if (!isReserved(c)) {
                        s.classes.add(c);
                    }
                }
            }
            // Gather declarations once: which entities the scope itself declares, and which the whole
            // imports closure declares. ownedByScope() is then two O(1) lookups rather than a per-entity
            // isDeclared() scan over every ontology in scope.
            Set<OWLEntity> declaredInScope = new LinkedHashSet<>();
            Set<OWLOntology> closure = new LinkedHashSet<>();
            for (OWLOntology o : scope) {
                for (OWLDeclarationAxiom ax : o.getAxioms(AxiomType.DECLARATION)) {
                    declaredInScope.add(ax.getEntity());
                }
                closure.addAll(o.getImportsClosure());
            }
            Set<OWLEntity> declaredInClosure = new LinkedHashSet<>(declaredInScope);
            for (OWLOntology o : closure) {
                for (OWLDeclarationAxiom ax : o.getAxioms(AxiomType.DECLARATION)) {
                    declaredInClosure.add(ax.getEntity());
                }
            }
            // Partition the per-entity check inputs. labelBearing sees the full signature; the owned
            // sets drop purely-imported terms so their upstream label/definition/domain/range are not
            // reported as locally missing.
            for (OWLEntity e : s.all) {
                if (isReserved(e)) {
                    continue;
                }
                if (e.isOWLClass() || e.isOWLObjectProperty() || e.isOWLDataProperty()
                        || e.isOWLNamedIndividual()) {
                    s.labelBearing.add(e);
                }
                if (!ownedByScope(e, declaredInScope, declaredInClosure)) {
                    continue;
                }
                if (e.isOWLClass()) {
                    s.labelable.add(e);
                    s.definable.add(e);
                } else if (e.isOWLObjectProperty()) {
                    s.objectProperties.add(e.asOWLObjectProperty());
                    s.labelable.add(e);
                    s.definable.add(e);
                } else if (e.isOWLDataProperty()) {
                    s.dataProperties.add(e.asOWLDataProperty());
                    s.labelable.add(e);
                    s.definable.add(e);
                } else if (e.isOWLNamedIndividual()) {
                    s.labelable.add(e);
                } else if (e.isOWLAnnotationProperty()) {
                    s.definable.add(e);
                }
            }
            return s;
        }

        /**
         * True unless {@code e} is purely imported — declared in the imports closure but not in the audited
         * scope itself. Declared in scope → owned; declared only upstream → not owned; declared nowhere (a
         * typo'd reference or a declaration-by-usage local term) → still audited (and, if truly undeclared,
         * separately flagged by undeclared_entity).
         */
        private static boolean ownedByScope(OWLEntity e, Set<OWLEntity> declaredInScope,
                Set<OWLEntity> declaredInClosure) {
            return declaredInScope.contains(e) || !declaredInClosure.contains(e);
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
