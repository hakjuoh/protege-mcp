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
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.SetOntologyID;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

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
                "Add an owl:imports declaration to the active ontology (declaration only; Protégé "
                        + "resolves/loads the imported document as usual).",
                Tools.schema().strReq("iri", "Imported ontology IRI.").build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String iri = Tools.reqString(a, "iri");
                    return WriteTools.write(ctx, "add import " + iri, mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLImportsDeclaration decl = mm.getOWLDataFactory()
                                .getOWLImportsDeclaration(IRI.create(iri));
                        boolean alreadyPresent = ont.getImportsDeclarations().contains(decl);
                        mm.applyChange(new AddImport(ont, decl));
                        return Tools.json()
                                .put("added", !alreadyPresent)
                                .put("iri", iri)
                                .put("already_present", alreadyPresent)
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
