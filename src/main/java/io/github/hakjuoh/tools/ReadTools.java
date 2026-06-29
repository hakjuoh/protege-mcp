package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;

import com.google.common.base.Optional;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/** Read-only tools: list/inspect ontologies, classes, entities and their axioms. All return JSON. */
public final class ReadTools {

    private ReadTools() {
    }

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("list_ontologies",
                "List all loaded ontologies (the active ontology and its imports closure). "
                        + "Marks which one is active.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> ctx.access().compute(mm -> {
                    OWLOntology active = mm.getActiveOntology();
                    Set<OWLOntology> onts = mm.getOntologies();
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (OWLOntology o : onts) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("id", ontologyLabel(o));
                        entry.put("anonymous", o.getOntologyID().isAnonymous());
                        entry.put("active", o.equals(active));
                        entry.put("axioms", o.getAxiomCount());
                        entry.put("logical_axioms", o.getLogicalAxiomCount());
                        list.add(entry);
                    }
                    return Tools.json()
                            .put("count", onts.size())
                            .put("active", ontologyLabel(active))
                            .put("ontologies", list)
                            .put("note", "The active ontology is the target of edits.")
                            .result();
                }))));

        tools.add(ToolSpecs.of("get_active_ontology",
                "Details of the active ontology: IRI, axiom counts and direct imports.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> ctx.access().compute(mm -> {
                    OWLOntology o = mm.getActiveOntology();
                    OWLOntologyID id = o.getOntologyID();
                    List<String> imports = new ArrayList<>();
                    for (OWLImportsDeclaration d : o.getImportsDeclarations()) {
                        imports.add(d.getIRI().toString());
                    }
                    return Tools.json()
                            .put("ontology_iri", id.getOntologyIRI().isPresent()
                                    ? id.getOntologyIRI().get().toString() : null)
                            .put("version_iri", id.getVersionIRI().isPresent()
                                    ? id.getVersionIRI().get().toString() : null)
                            .put("anonymous", id.isAnonymous())
                            .put("axioms", o.getAxiomCount())
                            .put("logical_axioms", o.getLogicalAxiomCount())
                            .put("direct_imports", imports)
                            .put("write_protection", ctx.controller().isReadOnly()
                                    ? "read-only (plugin setting)" : "writable")
                            .result();
                }))));

        tools.add(ToolSpecs.of("summarize_ontology",
                "Summarize the active ontology: signature counts, ontology annotations, imports and "
                        + "axiom-type counts. Set include_imports=true to summarize the imports closure.",
                Tools.schema()
                        .bool("include_imports", "Also summarize the imports closure (default false).")
                        .integer("limit", "Max axiom-type rows to return (default 80).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    int limit = Tools.optInt(a, "limit", 80);
                    return ctx.access().compute(mm -> summarize(mm.getActiveOntology(), includeImports, limit));
                })));

        tools.add(ToolSpecs.of("list_classes",
                "List named classes in the active ontology's signature (rendering + IRI).",
                Tools.schema().integer("limit", "Max classes to return (default 200).").build(),
                (ex, req) -> Tools.guard(() -> {
                    int limit = Tools.optInt(Tools.args(req), "limit", 200);
                    return ctx.access().compute(mm -> {
                        Set<OWLClass> classes = mm.getActiveOntology().getClassesInSignature();
                        return Tools.ok(Tools.entityList(mm, classes, limit));
                    });
                })));

        tools.add(ToolSpecs.of("search_entities",
                "Search entities by name/IRI fragment across the loaded ontologies. 'type' is one of "
                        + "class, object_property, data_property, annotation_property, individual, "
                        + "datatype, all (default all). A plain fragment matches as a substring and '*' "
                        + "is a wildcard; an empty or wildcard-only query lists the active ontology's "
                        + "whole signature.",
                Tools.schema()
                        .strReq("query", "Search text (use '*' as a wildcard).")
                        .str("type", "Entity type filter (default 'all').")
                        .integer("limit", "Max results (default 50).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String query = Tools.reqString(a, "query");
                    String type = Tools.optString(a, "type");
                    int limit = Tools.optInt(a, "limit", 50);
                    return ctx.access().compute(mm -> {
                        Set<? extends OWLEntity> matches = search(mm, query, type);
                        Map<String, Object> result = Tools.entityList(mm, matches, limit);
                        result.put("query", query);
                        result.put("type", type == null ? "all" : type);
                        return Tools.ok(result);
                    });
                })));

        tools.add(ToolSpecs.of("get_entity",
                "Look up an entity by IRI or display name; returns its type(s), IRI and rendering. An "
                        + "IRI may be 'punned' across several entity types, so 'matches' can hold more "
                        + "than one entity.",
                Tools.schema().strReq("entity", "Entity IRI or display name.").build(),
                (ex, req) -> Tools.guard(() -> {
                    String ref = Tools.reqString(Tools.args(req), "entity");
                    return ctx.access().compute(mm -> {
                        Set<OWLEntity> es = Tools.findEntities(mm, ref);
                        if (es.isEmpty()) {
                            return Tools.error("No entity found for '" + ref + "'.");
                        }
                        List<Map<String, Object>> matches = new ArrayList<>();
                        for (OWLEntity e : es) {
                            matches.add(Tools.entityJson(mm, e));
                        }
                        return Tools.json()
                                .put("query", ref)
                                .put("count", matches.size())
                                .put("matches", matches)
                                .putIfNotNull("note", matches.size() > 1
                                        ? "The IRI is punned across several entity types." : null)
                                .result();
                    });
                })));

        tools.add(ToolSpecs.of("get_axioms_for_entity",
                "Axioms that reference the given entity. By default only the active ontology; set "
                        + "include_imports=true to also include axioms from the imports closure (e.g. an "
                        + "imported term's domain/range and class restrictions).",
                Tools.schema()
                        .strReq("entity", "Entity IRI or display name.")
                        .bool("include_imports", "Also search the imports closure (default false).")
                        .integer("limit", "Max axioms to return (default 100).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String ref = Tools.reqString(a, "entity");
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    int limit = Tools.optInt(a, "limit", 100);
                    return ctx.access().compute(mm -> {
                        OWLEntity e = Tools.findEntity(mm, ref);
                        if (e == null) {
                            return Tools.error("No entity found for '" + ref + "'.");
                        }
                        OWLOntology active = mm.getActiveOntology();
                        Set<OWLOntology> scope = includeImports
                                ? active.getImportsClosure()
                                : Collections.singleton(active);
                        Set<OWLAxiom> axioms = new LinkedHashSet<>();
                        for (OWLOntology o : scope) {
                            axioms.addAll(o.getReferencingAxioms(e));
                        }
                        return Tools.json()
                                .put("entity", Tools.entityJson(mm, e))
                                .put("include_imports", includeImports)
                                .put("axioms", Tools.axiomList(mm, axioms, limit))
                                .result();
                    });
                })));

        return tools;
    }

    private static Set<? extends OWLEntity> search(OWLModelManager mm, String query, String type) {
        String q = query == null ? "" : query.trim();
        // A blank or wildcard-only query enumerates the whole signature: Protégé's entity finder
        // returns nothing for a bare '*', so list the active ontology ourselves.
        if (q.isEmpty() || q.chars().allMatch(c -> c == '*')) {
            return signature(mm, type);
        }
        // A fragment with no explicit wildcard is wrapped so it matches as a substring; the finder
        // otherwise misses substrings (e.g. 'neuron' would not find ':..._neuron_042').
        String pattern = q.indexOf('*') < 0 ? "*" + q + "*" : q;
        org.protege.editor.owl.model.find.OWLEntityFinder f = mm.getOWLEntityFinder();
        String t = type == null ? "all" : type.toLowerCase();
        switch (t) {
            case "class":
            case "classes":
                return f.getMatchingOWLClasses(pattern);
            case "object_property":
            case "objectproperty":
                return f.getMatchingOWLObjectProperties(pattern);
            case "data_property":
            case "dataproperty":
                return f.getMatchingOWLDataProperties(pattern);
            case "annotation_property":
            case "annotationproperty":
                return f.getMatchingOWLAnnotationProperties(pattern);
            case "individual":
            case "individuals":
                return f.getMatchingOWLIndividuals(pattern);
            case "datatype":
            case "datatypes":
                return f.getMatchingOWLDatatypes(pattern);
            default:
                return f.getMatchingOWLEntities(pattern);
        }
    }

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult summarize(OWLOntology active,
            boolean includeImports, int limit) {
        Set<OWLOntology> scope = includeImports
                ? active.getImportsClosure()
                : Collections.singleton(active);
        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        Set<OWLEntity> signature = new LinkedHashSet<>();
        int ontologyAnnotations = 0;
        int importDeclarations = 0;
        for (OWLOntology o : scope) {
            axioms.addAll(o.getAxioms());
            signature.addAll(o.getSignature());
            ontologyAnnotations += o.getAnnotations().size();
            importDeclarations += o.getImportsDeclarations().size();
        }

        Map<String, Integer> entityTypes = new TreeMap<>();
        for (OWLEntity e : signature) {
            increment(entityTypes, e.getEntityType().getName());
        }

        Map<String, Integer> axiomTypes = new TreeMap<>();
        for (OWLAxiom ax : axioms) {
            increment(axiomTypes, ax.getAxiomType().getName());
        }

        // Apply the limit to the axiom-type rows (the largest map), reporting how many were dropped.
        Map<String, Integer> axiomTypesShown = new LinkedHashMap<>();
        int max = Math.max(0, limit);
        int shown = 0;
        for (Map.Entry<String, Integer> entry : axiomTypes.entrySet()) {
            if (shown >= max) {
                break;
            }
            axiomTypesShown.put(entry.getKey(), entry.getValue());
            shown++;
        }

        Tools.Json json = Tools.json()
                .put("scope", includeImports ? "imports_closure" : "active")
                .put("ontologies", scope.size())
                .put("axioms", axioms.size())
                .put("logical_axioms", logicalAxiomCount(axioms))
                .put("ontology_annotations", ontologyAnnotations)
                .put("import_declarations", importDeclarations)
                .put("signature_entities", signature.size())
                .put("entity_types", entityTypes)
                .put("axiom_types", axiomTypesShown);
        if (axiomTypes.size() > axiomTypesShown.size()) {
            json.put("axiom_types_truncated", axiomTypes.size() - axiomTypesShown.size());
        }
        return json.result();
    }

    private static int logicalAxiomCount(Set<OWLAxiom> axioms) {
        int count = 0;
        for (OWLAxiom ax : axioms) {
            if (ax.isLogicalAxiom()) {
                count++;
            }
        }
        return count;
    }

    private static void increment(Map<String, Integer> counts, String key) {
        Integer old = counts.get(key);
        counts.put(key, old == null ? 1 : old + 1);
    }

    /** The active ontology's signature, narrowed to one entity kind (used for match-all queries). */
    private static Set<? extends OWLEntity> signature(OWLModelManager mm, String type) {
        OWLOntology o = mm.getActiveOntology();
        String t = type == null ? "all" : type.toLowerCase();
        switch (t) {
            case "class":
            case "classes":
                return o.getClassesInSignature();
            case "object_property":
            case "objectproperty":
                return o.getObjectPropertiesInSignature();
            case "data_property":
            case "dataproperty":
                return o.getDataPropertiesInSignature();
            case "annotation_property":
            case "annotationproperty":
                return o.getAnnotationPropertiesInSignature();
            case "individual":
            case "individuals":
                return o.getIndividualsInSignature();
            case "datatype":
            case "datatypes":
                return o.getDatatypesInSignature();
            default:
                return o.getSignature();
        }
    }

    private static String ontologyLabel(OWLOntology o) {
        OWLOntologyID id = o.getOntologyID();
        if (id.isAnonymous()) {
            return "(anonymous ontology)";
        }
        Optional<org.semanticweb.owlapi.model.IRI> iri = id.getOntologyIRI();
        return iri.isPresent() ? iri.get().toString() : id.toString();
    }
}
