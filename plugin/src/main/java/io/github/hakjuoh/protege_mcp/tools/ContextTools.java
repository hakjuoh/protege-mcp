package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.search.EntitySearcher;

import com.google.common.collect.Multimap;

import io.github.hakjuoh.protege_mcp.core.impact.SyntacticImpactService;

/**
 * Model-friendly context tools. {@code get_ontology_context} orients an assistant ("what am I
 * looking at, what should I look at first"); {@code get_entity_context} returns a single "entity
 * card" — type, annotations, asserted neighbourhood (parents/children/equivalents/domain/range/...)
 * — in one call, so the model can grab good context before reading individual axioms. Read-only;
 * everything is asserted structure (call run_reasoner / get_inferred_superclasses for inferences).
 */
public final class ContextTools {

    private ContextTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("get_ontology_context",
                (ex, req) -> {
                    int limit = Tools.optInt(Tools.args(req), "limit", 50);
                    return ctx.access().compute(mm -> ontologyContext(mm, ctx, limit));
                });
        tools.tool("get_entity_context",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String ref = Tools.reqString(a, "entity");
                    boolean includeImports = Tools.optBool(a, "include_imports", true);
                    int limit = Tools.optInt(a, "limit", 50);
                    return ctx.access().compute(mm -> {
                        Set<OWLEntity> matches = Tools.findEntities(mm, ref);
                        if (matches.isEmpty()) {
                            return Tools.error("No entity found for '" + ref + "'. Use search_entities "
                                    + "to discover IRIs.");
                        }
                        OWLOntology active = mm.getActiveOntology();
                        Set<OWLOntology> scope = includeImports
                                ? active.getImportsClosure()
                                : Collections.singleton(active);
                        List<Map<String, Object>> cards = new ArrayList<>();
                        for (OWLEntity e : matches) {
                            cards.add(entityCard(mm, e, active, scope, limit));
                        }
                        return Tools.json()
                                .put("query", ref)
                                .put("count", cards.size())
                                .put("include_imports", includeImports)
                                .put("entities", cards)
                                .putIfNotNull("note", cards.size() > 1
                                        ? "The IRI is punned across several entity types." : null)
                                .result();
                    });
                });
    }

    // ------------------------------------------------------------------ get_ontology_context

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult ontologyContext(
            OWLModelManager mm, ToolContext ctx, int limit) {
        OWLOntology o = mm.getActiveOntology();
        OWLOntologyID id = o.getOntologyID();

        Map<String, Object> active = new LinkedHashMap<>();
        active.put("ontology_iri", id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : null);
        active.put("version_iri", id.getVersionIRI().isPresent() ? id.getVersionIRI().get().toString() : null);
        active.put("anonymous", id.isAnonymous());

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("axioms", o.getAxiomCount());
        counts.put("logical_axioms", o.getLogicalAxiomCount());
        counts.put("classes", o.getClassesInSignature().size());
        counts.put("object_properties", o.getObjectPropertiesInSignature().size());
        counts.put("data_properties", o.getDataPropertiesInSignature().size());
        counts.put("annotation_properties", o.getAnnotationPropertiesInSignature().size());
        counts.put("individuals", o.getIndividualsInSignature().size());
        counts.put("datatypes", o.getDatatypesInSignature().size());

        List<String> imports = new ArrayList<>();
        o.getImportsDeclarations().forEach(d -> imports.add(d.getIRI().toString()));

        List<Map<String, Object>> ontologyAnnotations = new ArrayList<>();
        for (OWLAnnotation ann : o.getAnnotations()) {
            ontologyAnnotations.add(Tools.annotationJson(mm, ann));
        }

        return Tools.json()
                .put("active_ontology", active)
                .put("counts", counts)
                .put("imports", imports)
                .put("ontology_annotations", ontologyAnnotations)
                .put("root_classes", Tools.entityList(mm, rootClasses(o), limit))
                .put("object_properties", Tools.entityList(mm, o.getObjectPropertiesInSignature(), limit))
                .put("data_properties", Tools.entityList(mm, o.getDataPropertiesInSignature(), limit))
                .put("reasoner", reasonerState(mm))
                .put("prefixes", prefixes(mm, o))
                .put("write_protection", ctx.controller().isReadOnly() ? "read-only" : "writable")
                .put("note", "Asserted structure. Use get_entity_context for a term, validate_ontology "
                        + "for quality issues, run_reasoner for inferences.")
                .result();
    }

    /** Named classes whose only asserted superclass is owl:Thing (or none) — the asserted roots. */
    static Set<OWLClass> rootClasses(OWLOntology ont) {
        Set<OWLClass> roots = new TreeSet<>();
        for (OWLClass c : ont.getClassesInSignature()) {
            if (c.isOWLThing() || c.isOWLNothing() || c.isBuiltIn()) {
                continue;
            }
            boolean hasNamedSuper = false;
            for (OWLClassExpression sup : EntitySearcher.getSuperClasses(c, ont)) {
                if (!sup.isAnonymous() && !sup.asOWLClass().isOWLThing()) {
                    hasNamedSuper = true;
                    break;
                }
            }
            if (!hasNamedSuper) {
                roots.add(c);
            }
        }
        return roots;
    }

    private static Map<String, Object> reasonerState(OWLModelManager mm) {
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        ReasonerStatus status = rm.getReasonerStatus();
        Map<String, Object> r = new LinkedHashMap<>();
        String currentId = rm.getCurrentReasonerFactoryId();
        r.put("selected_id", currentId);
        r.put("selected_name", selectedReasonerName(rm, currentId));
        r.put("status", String.valueOf(status));
        // Results are trustworthy only when up to date with the asserted axioms. OUT_OF_SYNC also
        // satisfies isEnableStop() but holds STALE results — report it as not-current so the model
        // re-runs the reasoner rather than reading stale inferences.
        boolean current = status == ReasonerStatus.INITIALIZED || status == ReasonerStatus.INCONSISTENT;
        r.put("results_available", current);
        if (status == ReasonerStatus.OUT_OF_SYNC) {
            r.put("stale", true);
        }
        return r;
    }

    /**
     * Display name of the <em>selected</em> reasoner factory (matched by id, as list_reasoners does),
     * NOT {@link OWLReasonerManager#getCurrentReasonerName()} — that returns the live reasoner instance,
     * which is a NoOpReasoner ("Protégé Null Reasoner") until run_reasoner has classified. So before a
     * classification this now reports e.g. "HermiT" (the selected factory) instead of the placeholder.
     */
    private static String selectedReasonerName(OWLReasonerManager rm, String currentId) {
        if (currentId != null) {
            for (ProtegeOWLReasonerInfo info : rm.getInstalledReasonerFactories()) {
                if (currentId.equals(info.getReasonerId())) {
                    return info.getReasonerName();
                }
            }
        }
        try {
            return rm.getCurrentReasonerName();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, String> prefixes(OWLModelManager mm, OWLOntology o) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            OWLDocumentFormat fmt = mm.getOWLOntologyManager().getOntologyFormat(o);
            if (fmt != null && fmt.isPrefixOWLOntologyFormat()) {
                out.putAll(fmt.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap());
            }
        } catch (RuntimeException ignored) {
            // format may be unavailable; an empty prefix map is fine
        }
        return out;
    }

    // ------------------------------------------------------------------ get_entity_context

    private static Map<String, Object> entityCard(OWLModelManager mm, OWLEntity e, OWLOntology active,
            Set<OWLOntology> scope, int limit) {
        Map<String, Object> card = Tools.entityJson(mm, e);
        card.put("deprecated", isDeprecated(e, scope));
        card.put("annotations", annotations(mm, e, scope));
        card.put("referencing_axioms", active.getReferencingAxioms(e).size());

        if (e.isOWLClass()) {
            OWLClass c = e.asOWLClass();
            card.put("super_classes", members(mm, EntitySearcher.getSuperClasses(c, scope), limit));
            card.put("sub_classes", members(mm, EntitySearcher.getSubClasses(c, scope), limit));
            card.put("equivalent_classes", members(mm, EntitySearcher.getEquivalentClasses(c, scope), limit));
            card.put("disjoint_classes", members(mm, EntitySearcher.getDisjointClasses(c, scope), limit));
            card.put("instances", instances(mm, c, scope, limit));
        } else if (e.isOWLObjectProperty()) {
            OWLObjectProperty p = e.asOWLObjectProperty();
            card.put("domains", members(mm, EntitySearcher.getDomains(p, scope), limit));
            card.put("ranges", members(mm, EntitySearcher.getRanges(p, scope), limit));
            card.put("super_properties", members(mm, EntitySearcher.getSuperProperties(p, scope), limit));
            card.put("sub_properties", members(mm, EntitySearcher.getSubProperties(p, scope), limit));
            card.put("inverses", members(mm, EntitySearcher.getInverses(p, scope), limit));
            card.put("characteristics", objectCharacteristics(p, scope));
        } else if (e.isOWLDataProperty()) {
            OWLDataProperty p = e.asOWLDataProperty();
            card.put("domains", members(mm, EntitySearcher.getDomains(p, scope), limit));
            card.put("ranges", members(mm, EntitySearcher.getRanges(p, scope), limit));
            card.put("super_properties", members(mm, EntitySearcher.getSuperProperties(p, scope), limit));
            card.put("sub_properties", members(mm, EntitySearcher.getSubProperties(p, scope), limit));
            card.put("functional", has(scope, ont -> EntitySearcher.isFunctional(p, ont)));
        } else if (e.isOWLAnnotationProperty()) {
            OWLAnnotationProperty p = e.asOWLAnnotationProperty();
            card.put("super_properties", members(mm, EntitySearcher.getSuperProperties(p, scope), limit));
            card.put("sub_properties", members(mm, EntitySearcher.getSubProperties(p, scope), limit));
            List<String> domains = new ArrayList<>();
            EntitySearcher.getDomains(p, scope).forEach(iri -> domains.add(iri.toString()));
            List<String> ranges = new ArrayList<>();
            EntitySearcher.getRanges(p, scope).forEach(iri -> ranges.add(iri.toString()));
            card.put("domains", domains);
            card.put("ranges", ranges);
        } else if (e.isOWLNamedIndividual()) {
            OWLNamedIndividual ind = e.asOWLNamedIndividual();
            card.put("types", members(mm, EntitySearcher.getTypes(ind, scope), limit));
            card.put("object_property_values", objectPropertyValues(mm, ind, scope, limit));
            card.put("data_property_values", dataPropertyValues(mm, ind, scope, limit));
            card.put("same_as", members(mm, EntitySearcher.getSameIndividuals(ind, scope), limit));
            card.put("different_from", members(mm, EntitySearcher.getDifferentIndividuals(ind, scope), limit));
        }
        return card;
    }

    /** Asserted instances of a class via ClassAssertion axioms across the scope. */
    private static List<Map<String, Object>> instances(OWLModelManager mm, OWLClass c,
            Set<OWLOntology> scope, int limit) {
        Set<OWLNamedIndividual> set = new TreeSet<>();
        for (OWLOntology o : scope) {
            for (OWLIndividual i : EntitySearcher.getIndividuals(c, o)) {
                if (i.isNamed()) {
                    set.add(i.asOWLNamedIndividual());
                }
            }
        }
        return members(mm, set, limit);
    }

    private static List<Map<String, Object>> objectPropertyValues(OWLModelManager mm,
            OWLNamedIndividual ind, Set<OWLOntology> scope, int limit) {
        Map<OWLObjectPropertyExpression, Set<OWLIndividual>> merged = new LinkedHashMap<>();
        for (OWLOntology o : scope) {
            Multimap<OWLObjectPropertyExpression, OWLIndividual> values =
                    EntitySearcher.getObjectPropertyValues(ind, o);
            for (Map.Entry<OWLObjectPropertyExpression, OWLIndividual> en : values.entries()) {
                merged.computeIfAbsent(en.getKey(), k -> new TreeSet<>(byString(mm))).add(en.getValue());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<OWLObjectPropertyExpression, Set<OWLIndividual>> en : merged.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("property", Tools.renderObject(mm, en.getKey()));
            row.put("values", renderings(mm, en.getValue(), limit));
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> dataPropertyValues(OWLModelManager mm,
            OWLNamedIndividual ind, Set<OWLOntology> scope, int limit) {
        Map<OWLDataPropertyExpression, List<String>> merged = new LinkedHashMap<>();
        for (OWLOntology o : scope) {
            Multimap<OWLDataPropertyExpression, OWLLiteral> values =
                    EntitySearcher.getDataPropertyValues(ind, o);
            for (Map.Entry<OWLDataPropertyExpression, OWLLiteral> en : values.entries()) {
                merged.computeIfAbsent(en.getKey(), k -> new ArrayList<>()).add(literalString(en.getValue()));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<OWLDataPropertyExpression, List<String>> en : merged.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("property", Tools.renderObject(mm, en.getKey()));
            List<String> vals = en.getValue();
            row.put("values", vals.size() > limit ? new ArrayList<>(vals.subList(0, limit)) : vals);
            out.add(row);
        }
        return out;
    }

    private static List<String> objectCharacteristics(OWLObjectProperty p, Set<OWLOntology> scope) {
        List<String> chars = new ArrayList<>();
        if (has(scope, o -> EntitySearcher.isFunctional(p, o))) {
            chars.add("functional");
        }
        if (has(scope, o -> EntitySearcher.isInverseFunctional(p, o))) {
            chars.add("inverse_functional");
        }
        if (has(scope, o -> EntitySearcher.isTransitive(p, o))) {
            chars.add("transitive");
        }
        if (has(scope, o -> EntitySearcher.isSymmetric(p, o))) {
            chars.add("symmetric");
        }
        if (has(scope, o -> EntitySearcher.isAsymmetric(p, o))) {
            chars.add("asymmetric");
        }
        if (has(scope, o -> EntitySearcher.isReflexive(p, o))) {
            chars.add("reflexive");
        }
        if (has(scope, o -> EntitySearcher.isIrreflexive(p, o))) {
            chars.add("irreflexive");
        }
        return chars;
    }

    // ------------------------------------------------------------------ shared helpers

    /** True if {@code predicate} holds for any ontology in the scope. */
    private static boolean has(Set<OWLOntology> scope, Predicate<OWLOntology> predicate) {
        for (OWLOntology o : scope) {
            if (predicate.test(o)) {
                return true;
            }
        }
        return false;
    }

    static boolean isDeprecated(OWLEntity e, Set<OWLOntology> scope) {
        return SyntacticImpactService.isDeprecated(e, scope);
    }

    private static List<Map<String, Object>> annotations(OWLModelManager mm, OWLEntity e,
            Set<OWLOntology> scope) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OWLOntology o : scope) {
            for (OWLAnnotation ann : EntitySearcher.getAnnotations(e, o)) {
                out.add(Tools.annotationJson(mm, ann));
            }
        }
        return out;
    }

    private static String literalString(OWLLiteral lit) {
        if (lit.hasLang()) {
            return lit.getLiteral() + "@" + lit.getLang();
        }
        return lit.getLiteral();
    }

    /**
     * Sorted, capped structured neighbour list: a named entity becomes {@code {iri, display, type}};
     * an anonymous class/data-range expression becomes {@code {expression, anonymous:true}}. This lets
     * the model resolve a neighbour to its IRI directly (no second lookup) and tell named terms from
     * restriction/intersection superclasses.
     */
    private static List<Map<String, Object>> members(OWLModelManager mm,
            Collection<? extends OWLObject> objects, int limit) {
        List<OWLObject> sorted = new ArrayList<>(objects);
        sorted.sort((a, b) -> {
            String ar = Tools.renderObject(mm, a);
            String br = Tools.renderObject(mm, b);
            int byRendering = ar.compareToIgnoreCase(br);
            if (byRendering != 0) {
                return byRendering;
            }
            return memberTieBreak(a).compareTo(memberTieBreak(b));
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (OWLObject o : sorted) {
            if (out.size() >= Math.max(0, limit)) {
                break;
            }
            Map<String, Object> m;
            if (o instanceof OWLEntity) {
                m = Tools.entityJson(mm, (OWLEntity) o);
            } else {
                m = new LinkedHashMap<>();
                m.put("expression", Tools.renderObject(mm, o));
                m.put("anonymous", true);
            }
            out.add(m);
        }
        return out;
    }

    private static String memberTieBreak(OWLObject o) {
        if (o instanceof OWLEntity) {
            OWLEntity e = (OWLEntity) o;
            return e.getEntityType().getName() + " " + e.getIRI();
        }
        return o.toString();
    }

    /** Sorted, capped renderings of OWL objects (entities or class/data-range expressions). */
    private static List<String> renderings(OWLModelManager mm, Collection<? extends OWLObject> objects,
            int limit) {
        TreeSet<String> sorted = new TreeSet<>();
        for (OWLObject o : objects) {
            sorted.add(Tools.renderObject(mm, o));
        }
        List<String> out = new ArrayList<>();
        for (String s : sorted) {
            if (out.size() >= Math.max(0, limit)) {
                break;
            }
            out.add(s);
        }
        return out;
    }

    private static java.util.Comparator<OWLObject> byString(OWLModelManager mm) {
        return java.util.Comparator.comparing(o -> Tools.renderObject(mm, o));
    }
}
