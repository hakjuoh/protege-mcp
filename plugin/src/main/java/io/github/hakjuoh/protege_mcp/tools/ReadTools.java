package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

import io.github.hakjuoh.protege_mcp.core.owl.OwlDocumentSignature;

/** Read-only tools: list/inspect ontologies, classes, entities and their axioms. All return JSON. */
public final class ReadTools {

    private ReadTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("list_ontologies",
                (ex, req) -> ctx.access().compute(mm -> {
                    OWLOntology active = mm.getActiveOntology();
                    Set<OWLOntology> onts = mm.getOntologies();
                    Set<OWLOntology> dirty = mm.getDirtyOntologies();
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (OWLOntology o : onts) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("id", ontologyLabel(o));
                        entry.put("anonymous", o.getOntologyID().isAnonymous());
                        entry.put("active", o.equals(active));
                        entry.put("dirty", dirty.contains(o));
                        entry.put("axioms", o.getAxiomCount());
                        entry.put("logical_axioms", o.getLogicalAxiomCount());
                        entry.put("document", String.valueOf(
                                mm.getOWLOntologyManager().getOntologyDocumentIRI(o)));
                        list.add(entry);
                    }
                    return Tools.json()
                            .put("count", onts.size())
                            .put("active", ontologyLabel(active))
                            .put("dirty_count", dirty.size())
                            .put("ontologies", list)
                            .put("note", "The active ontology is the target of edits. 'dirty' = "
                                    + "unsaved changes (save_ontology writes the active ontology; "
                                    + "all=true saves every dirty one).")
                            .result();
                }));

        tools.tool("get_active_ontology",
                (ex, req) -> ctx.access().compute(mm -> {
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
                }));

        tools.tool("summarize_ontology",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    int limit = Tools.optInt(a, "limit", 80);
                    return ctx.access().compute(mm -> summarize(mm.getActiveOntology(), includeImports, limit));
                });
        tools.tool("list_classes",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    int limit = Tools.optInt(a, "limit", 200);
                    int offset = Tools.optInt(a, "offset", 0);
                    return ctx.access().compute(mm -> {
                        Set<OWLClass> classes = mm.getActiveOntology().getClassesInSignature();
                        return Tools.ok(Tools.entityPage(mm, classes, offset, limit));
                    });
                });
        tools.tool("search_entities",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String query = Tools.reqString(a, "query");
                    String type = Tools.optString(a, "type");
                    int limit = Tools.optInt(a, "limit", 50);
                    int offset = Tools.optInt(a, "offset", 0);
                    ProjectPolicyTools.PolicyContext live =
                            ctx.access().compute(ProjectPolicyTools::capture);
                    EntitySearch.Settings settings = ctx.entitySearchPolicy().resolve(live);
                    return ctx.access().compute(mm -> {
                        ctx.revisions().installIfNeeded(mm);
                        EntitySearch.Settings effectiveSettings = sameWorkspace(live, mm)
                                ? settings : EntitySearch.Settings.defaults();
                        Set<? extends OWLEntity> matches = search(mm, query, type);
                        Map<String, Object> result =
                                EntitySearch.enrichedSearch(mm, query, matches, offset, limit, type,
                                        effectiveSettings, ctx.entitySearchCache(),
                                        ctx.revisions().version());
                        result.put("query", query);
                        result.put("type", type == null ? "all" : type);
                        return Tools.ok(result);
                    });
                });
        tools.tool("get_entity",
                (ex, req) -> {
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
                });
        tools.tool("get_axioms_for_entity",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String ref = Tools.reqString(a, "entity");
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    int limit = Tools.optInt(a, "limit", 100);
                    int offset = Tools.optInt(a, "offset", 0);
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
                                .put("axioms", Tools.axiomPage(mm, axioms, offset, limit))
                                .result();
                    });
                });
    }

    private static boolean sameWorkspace(ProjectPolicyTools.PolicyContext captured,
            OWLModelManager current) {
        OWLOntology active = current.getActiveOntology();
        String ontologyIri = active.getOntologyID().getOntologyIRI().orNull() == null ? null
                : active.getOntologyID().getOntologyIRI().orNull().toString();
        java.io.File document = SidecarPaths.toFile(
                current.getOWLOntologyManager().getOntologyDocumentIRI(active));
        return Objects.equals(captured.documentPath(), document == null ? null : document.toPath())
                && Objects.equals(captured.activeOntologyIri(), ontologyIri);
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
            // Not o.getSignature(): OWLAPI 4's cached signature can hold loaded-import entities even for
            // an active-only scope, which would contradict the active-only axiom counts in this response.
            signature.addAll(OwlDocumentSignature.of(o));
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
                // The per-type accessors above build fresh sets, but getSignature() answers from a cache
                // that a document load pollutes with imported entities — derive the all-kinds signature
                // from the active document's own content instead.
                return OwlDocumentSignature.of(o);
        }
    }

    /** Display label for an ontology: its IRI, or a placeholder when anonymous. */
    static String ontologyLabel(OWLOntology o) {
        OWLOntologyID id = o.getOntologyID();
        if (id.isAnonymous()) {
            return "(anonymous ontology)";
        }
        Optional<org.semanticweb.owlapi.model.IRI> iri = id.getOntologyIRI();
        return iri.isPresent() ? iri.get().toString() : id.toString();
    }
}
