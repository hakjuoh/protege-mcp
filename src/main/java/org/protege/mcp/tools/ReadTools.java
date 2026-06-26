package org.protege.mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;

import com.google.common.base.Optional;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/** Read-only tools: list/inspect ontologies, classes, entities and their axioms. */
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
                    StringBuilder sb = new StringBuilder("Loaded ontologies (").append(onts.size()).append("):\n");
                    for (OWLOntology o : onts) {
                        sb.append(o.equals(active) ? "* " : "  ").append(ontologyLabel(o)).append('\n');
                    }
                    sb.append("\n(* = active ontology — the target of edits)");
                    return Tools.text(sb.toString());
                }))));

        tools.add(ToolSpecs.of("get_active_ontology",
                "Details of the active ontology: IRI, axiom counts and direct imports.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> ctx.access().compute(mm -> {
                    OWLOntology o = mm.getActiveOntology();
                    StringBuilder sb = new StringBuilder();
                    sb.append("Active ontology: ").append(ontologyLabel(o)).append('\n');
                    sb.append("Axioms: ").append(o.getAxiomCount())
                            .append(" (logical: ").append(o.getLogicalAxiomCount()).append(")\n");
                    Set<OWLImportsDeclaration> imports = o.getImportsDeclarations();
                    sb.append("Direct imports: ").append(imports.size());
                    for (OWLImportsDeclaration d : imports) {
                        sb.append("\n  ").append(d.getIRI());
                    }
                    sb.append("\nWrite-protection: ")
                            .append(ctx.controller().isReadOnly() ? "READ-ONLY (plugin setting)" : "writable");
                    return Tools.text(sb.toString());
                }))));

        tools.add(ToolSpecs.of("list_classes",
                "List named classes in the active ontology's signature (rendering + IRI).",
                Tools.schema().integer("limit", "Max classes to return (default 200).").build(),
                (ex, req) -> Tools.guard(() -> {
                    int limit = Tools.optInt(Tools.args(req), "limit", 200);
                    return ctx.access().compute(mm -> {
                        Set<OWLClass> classes = mm.getActiveOntology().getClassesInSignature();
                        return Tools.text(renderEntities(mm, "Classes", classes, limit));
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
                        return Tools.text(renderEntities(mm, "Matches for '" + query + "'", matches, limit));
                    });
                })));

        tools.add(ToolSpecs.of("get_entity",
                "Look up an entity by IRI or display name; returns its type(s), IRI and rendering.",
                Tools.schema().strReq("entity", "Entity IRI or display name.").build(),
                (ex, req) -> Tools.guard(() -> {
                    String ref = Tools.reqString(Tools.args(req), "entity");
                    return ctx.access().compute(mm -> {
                        Set<OWLEntity> es = Tools.findEntities(mm, ref);
                        if (es.isEmpty()) {
                            return Tools.error("No entity found for '" + ref + "'.");
                        }
                        StringBuilder sb = new StringBuilder();
                        for (OWLEntity e : es) {
                            sb.append(e.getEntityType().getName()).append(": ")
                                    .append(Tools.renderEntity(mm, e)).append('\n');
                        }
                        return Tools.text(sb.toString().trim());
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
                        StringBuilder sb = new StringBuilder();
                        sb.append("Axioms referencing ").append(Tools.renderEntity(mm, e))
                                .append(includeImports ? " (incl. imports)" : "")
                                .append(" (").append(axioms.size()).append("):\n");
                        int n = 0;
                        for (OWLAxiom ax : axioms) {
                            if (n++ >= limit) {
                                sb.append("... (").append(axioms.size() - limit).append(" more)\n");
                                break;
                            }
                            sb.append("  ").append(Tools.renderAxiom(mm, ax)).append('\n');
                        }
                        return Tools.text(sb.toString().trim());
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

    private static String renderEntities(OWLModelManager mm, String heading,
            Set<? extends OWLEntity> entities, int limit) {
        TreeSet<String> lines = new TreeSet<>();
        for (OWLEntity e : entities) {
            lines.add(Tools.renderEntity(mm, e));
        }
        StringBuilder sb = new StringBuilder(heading).append(" (").append(entities.size()).append("):\n");
        int n = 0;
        for (String line : lines) {
            if (n++ >= limit) {
                sb.append("... (").append(entities.size() - limit).append(" more; raise 'limit')\n");
                break;
            }
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString().trim();
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
