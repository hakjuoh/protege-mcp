package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.SetOntologyID;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Ontology-level metadata writes — the ontology IRI/version, import declarations, and ontology
 * annotations. These are {@link org.semanticweb.owlapi.model.OWLOntologyChange}s, not
 * {@link org.semanticweb.owlapi.model.OWLAxiom}s, so they are not expressible via add_axiom; together
 * with add_axiom/add_annotation they let an ontology header be reconstructed incrementally. Every
 * change flows through {@link OWLModelManager#applyChange} (GUI-visible, undoable) and is gated by
 * the same read-only/confirmation switches as the other write tools.
 */
public final class OntologyMetadataTools {

    private OntologyMetadataTools() {
    }

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("set_ontology_id",
                "Set the active ontology's IRI and (optionally) version IRI.",
                Tools.schema()
                        .strReq("ontology_iri", "New ontology IRI.")
                        .str("version_iri", "Optional version IRI.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String ontologyIri = Tools.reqString(a, "ontology_iri");
                    String versionIri = Tools.optString(a, "version_iri");
                    return WriteTools.write(ctx, "set ontology id " + ontologyIri, mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLOntologyID newId = versionIri != null
                                ? new OWLOntologyID(IRI.create(ontologyIri), IRI.create(versionIri))
                                : new OWLOntologyID(IRI.create(ontologyIri));
                        String collision = idCollision(mm, ont, newId);
                        if (collision != null) {
                            return Tools.error(collision);
                        }
                        mm.applyChange(new SetOntologyID(ont, newId));
                        return Tools.json()
                                .put("ontology_iri", newId.getOntologyIRI().isPresent()
                                        ? newId.getOntologyIRI().get().toString() : null)
                                .putIfNotNull("version_iri", newId.getVersionIRI().isPresent()
                                        ? newId.getVersionIRI().get().toString() : null)
                                .put("message", "Set ontology id to " + label(newId) + ".")
                                .result();
                    });
                })));

        tools.add(ToolSpecs.of("add_import",
                "Add an owl:imports declaration to the active ontology. The result's 'resolved' flag "
                        + "reports whether the document actually loaded into the workspace — an "
                        + "unresolved import is a dangling declaration whose terms stay invisible to "
                        + "lookups and reasoning until it is loaded. Pass 'document' (a path/URL/IRI) to "
                        + "resolve the import NOW by loading that document WITHOUT changing your active "
                        + "edit target.",
                Tools.schema()
                        .strReq("iri", "Imported ontology IRI.")
                        .str("document", "Optional path/URL/IRI of the import's document to load now so "
                                + "the import resolves (keeps the active ontology unchanged).")
                        .integer("connection_timeout_ms", "Document connection timeout when 'document' is "
                                + "given (default 15000).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String iri = Tools.reqString(a, "iri");
                    String document = Tools.optString(a, "document");
                    int timeoutMs = Tools.optInt(a, "connection_timeout_ms", 15_000);
                    CallToolResult denied = WriteTools.checkWriteAllowed(ctx, "add import " + iri);
                    if (denied != null) {
                        return denied;
                    }
                    OWLImportsDeclaration decl = ctx.access().compute(mm ->
                            mm.getOWLDataFactory().getOWLImportsDeclaration(IRI.create(iri)));
                    boolean[] alreadyPresent = {false};
                    boolean resolved = ctx.access().compute(mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        alreadyPresent[0] = ont.getImportsDeclarations().contains(decl);
                        mm.applyChange(new AddImport(ont, decl));
                        // Did Protégé resolve the declaration to a loaded ontology? In a catalog-less
                        // workspace a remote/unmapped IRI stays unresolved.
                        return mm.getOWLOntologyManager().getImportedOntology(decl) != null;
                    });
                    if (!resolved && document != null) {
                        CallToolResult loadResult = OntologyDocumentTools.doLoad(ctx, document, timeoutMs, true);
                        if (Boolean.TRUE.equals(loadResult.isError())) {
                            return loadResult;
                        }
                        resolved = ctx.access().compute(mm ->
                                mm.getOWLOntologyManager().getImportedOntology(decl) != null);
                    }
                    Tools.Json json = Tools.json()
                            .put("added", !alreadyPresent[0])
                            .put("iri", iri)
                            .put("already_present", alreadyPresent[0])
                            .put("resolved", resolved);
                    if (document != null) {
                        json.put("document", document);
                    }
                    if (!resolved) {
                        json.put("note", "Import declaration added, but the document is not in the "
                                + "workspace, so imported terms won't appear in lookups or reasoning "
                                + "until it is loaded — pass 'document', or use load_ontology with "
                                + "keep_active=true, or add a catalog mapping for the IRI.");
                    }
                    return json.result();
                })));

        tools.add(ToolSpecs.of("set_prefix",
                "Register or update a prefix in the active ontology's prefix map (e.g. prefix 'iof-av' "
                        + "→ 'https://spec.industrialontologies.org/ontology/annotation/'), so CURIEs "
                        + "like 'iof-av:maturity' render and parse and the document serializes with the "
                        + "prefix. The prefix map lives in the ontology's document format — this changes "
                        + "no axioms and is NOT on the undo stack.",
                Tools.schema()
                        .strReq("prefix", "Prefix name, with or without a trailing ':' (e.g. 'iof-av' or "
                                + "'iof-av:'); use ':' for the default namespace.")
                        .strReq("namespace", "Namespace IRI the prefix expands to.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String prefix = Tools.reqString(a, "prefix");
                    String namespace = Tools.reqString(a, "namespace");
                    CallToolResult denied = WriteTools.checkWriteAllowed(ctx, "set prefix " + prefix);
                    if (denied != null) {
                        return denied;
                    }
                    return ctx.access().compute(mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLDocumentFormat fmt = mm.getOWLOntologyManager().getOntologyFormat(ont);
                        if (fmt == null || !fmt.isPrefixOWLOntologyFormat()) {
                            return Tools.error("The active ontology's document format has no prefix map "
                                    + "(format: " + (fmt == null ? "none" : fmt.getClass().getSimpleName())
                                    + ").");
                        }
                        String name = prefix.endsWith(":") ? prefix : prefix + ":";
                        fmt.asPrefixOWLOntologyFormat().setPrefix(name, namespace);
                        return Tools.json()
                                .put("prefix", name)
                                .put("namespace", namespace)
                                .put("prefixes", fmt.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap())
                                .result();
                    });
                })));

        tools.add(ToolSpecs.of("remove_import",
                "Remove an owl:imports declaration from the active ontology.",
                Tools.schema().strReq("iri", "Imported ontology IRI to remove.").build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String iri = Tools.reqString(a, "iri");
                    return WriteTools.write(ctx, "remove import " + iri, mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLImportsDeclaration decl = mm.getOWLDataFactory()
                                .getOWLImportsDeclaration(IRI.create(iri));
                        if (!ont.getImportsDeclarations().contains(decl)) {
                            return Tools.error("Import not present in the active ontology: " + iri);
                        }
                        mm.applyChange(new RemoveImport(ont, decl));
                        return Tools.json().put("removed", true).put("iri", iri).result();
                    });
                })));

        tools.add(ToolSpecs.of("add_ontology_annotation",
                "Add an ontology-level annotation (e.g. dcterms:title, owl:versionInfo). The value is a "
                        + "literal (optionally typed with 'datatype' or tagged with 'lang') or, with "
                        + "'value_iri', an IRI.",
                Tools.schema()
                        .str("property", "Annotation property: 'rdfs:label', 'rdfs:comment', or an IRI/name "
                                + "(default rdfs:label).")
                        .str("value", "Literal text value (omit if value_iri is given).")
                        .str("value_iri", "IRI-valued annotation: an entity name/IRI or absolute IRI.")
                        .str("lang", "Optional language tag for a literal value, e.g. 'en'.")
                        .str("datatype", "Optional datatype IRI/name for a typed literal value.")
                        .annotationArray("annotations", "Optional nested annotations on this annotation.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    return WriteTools.write(ctx, "add ontology annotation", mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLAnnotation annotation = df.getOWLAnnotation(
                                Tools.annotationProperty(mm, Tools.optString(a, "property")),
                                Tools.annotationValue(mm, a),
                                Tools.annotationSet(mm, a, "annotations"));
                        boolean alreadyPresent = ont.getAnnotations().contains(annotation);
                        mm.applyChange(new AddOntologyAnnotation(ont, annotation));
                        return Tools.json()
                                .put("added", !alreadyPresent)
                                .put("annotation", Tools.annotationJson(mm, annotation))
                                .put("already_present", alreadyPresent)
                                .result();
                    });
                })));

        tools.add(ToolSpecs.of("remove_ontology_annotation",
                "Remove an ontology-level annotation (same arguments as add_ontology_annotation).",
                Tools.schema()
                        .str("property", "Annotation property: 'rdfs:label', 'rdfs:comment', or an IRI/name "
                                + "(default rdfs:label).")
                        .str("value", "Literal text value (omit if value_iri is given).")
                        .str("value_iri", "IRI-valued annotation: an entity name/IRI or absolute IRI.")
                        .str("lang", "Optional language tag for a literal value, e.g. 'en'.")
                        .str("datatype", "Optional datatype IRI/name for a typed literal value.")
                        .annotationArray("annotations", "Optional nested annotations on this annotation.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    return WriteTools.write(ctx, "remove ontology annotation", mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLAnnotation annotation = df.getOWLAnnotation(
                                Tools.annotationProperty(mm, Tools.optString(a, "property")),
                                Tools.annotationValue(mm, a),
                                Tools.annotationSet(mm, a, "annotations"));
                        if (!ont.getAnnotations().contains(annotation)) {
                            return Tools.error("Ontology annotation not present: " + render(mm, annotation));
                        }
                        mm.applyChange(new RemoveOntologyAnnotation(ont, annotation));
                        return Tools.json()
                                .put("removed", true)
                                .put("annotation", Tools.annotationJson(mm, annotation))
                                .result();
                    });
                })));

        return tools;
    }

    /**
     * If {@code newId} is already bound to a <em>different</em> ontology loaded in Protégé's shared
     * manager, return a friendly error message; otherwise {@code null}. This avoids the raw
     * {@code OWLOntologyRenameException} that {@link SetOntologyID} throws on such a collision.
     */
    static String idCollision(OWLModelManager mm, OWLOntology active, OWLOntologyID newId) {
        if (newId.isAnonymous()) {
            return null;
        }
        OWLOntologyManager om = mm.getOWLOntologyManager();
        if (om.contains(newId) && !active.equals(om.getOntology(newId))) {
            return "Ontology id " + label(newId) + " is already in use by another ontology loaded in "
                    + "Protégé (e.g. an import). Choose a different IRI/version.";
        }
        return null;
    }

    private static String label(OWLOntologyID id) {
        StringBuilder sb = new StringBuilder();
        sb.append(id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : "(anonymous)");
        if (id.getVersionIRI().isPresent()) {
            sb.append(" version ").append(id.getVersionIRI().get());
        }
        return sb.toString();
    }

    private static String render(OWLModelManager mm, OWLAnnotation annotation) {
        return mm.getRendering(annotation.getProperty()) + " = " + annotation.getValue();
    }
}
