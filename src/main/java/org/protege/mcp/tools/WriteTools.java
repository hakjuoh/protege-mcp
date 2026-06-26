package org.protege.mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.entity.OWLEntityCreationException;
import org.protege.editor.owl.model.entity.OWLEntityCreationSet;
import org.protege.editor.owl.model.history.HistoryManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.RemoveAxiom;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Mutating tools. Every change is applied via {@link OWLModelManager#applyChanges} so it flows
 * through Protégé's {@code HistoryManager} (the shared, GUI-visible undo stack) exactly like a
 * manual edit. Writes are gated by a live read-only switch and an optional write-confirmation
 * dialog; {@code applyChanges} may drop/merge changes (ChangeListMinimizer), so each tool re-queries
 * the resulting state to report what actually took effect.
 */
public final class WriteTools {

    private WriteTools() {
    }

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("create_class",
                "Create a named class. Optionally give a full 'iri' (else one is minted from 'name' "
                        + "using Protégé's entity-creation settings) and a 'parent' superclass.",
                Tools.schema()
                        .strReq("name", "Short name / label for the new class.")
                        .str("iri", "Full IRI to use (optional).")
                        .str("parent", "Superclass: IRI, name or Manchester class expression (optional).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String name = Tools.reqString(a, "name");
                    return write(ctx, "create_class " + name, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        OWLClass cls = (OWLClass) createEntity(mm, "class", name, Tools.optString(a, "iri"), changes);
                        String parent = Tools.optString(a, "parent");
                        if (parent != null) {
                            OWLClassExpression sup = Tools.resolveClassExpression(mm, parent);
                            changes.add(new AddAxiom(ont, df.getOWLSubClassOfAxiom(cls, sup)));
                        }
                        mm.applyChanges(changes);
                        boolean present = ont.containsEntityInSignature(cls);
                        return Tools.text("Created class " + Tools.renderEntity(mm, cls)
                                + (parent != null ? " ⊑ " + parent : "")
                                + (present ? "" : " (warning: not present after apply)") + ".");
                    });
                })));

        tools.add(ToolSpecs.of("create_entity",
                "Create a named entity of a given type: class, object_property, data_property, "
                        + "annotation_property, individual or datatype.",
                Tools.schema()
                        .strReq("entity_type", "class | object_property | data_property | "
                                + "annotation_property | individual | datatype")
                        .strReq("name", "Short name / label.")
                        .str("iri", "Full IRI to use (optional).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String type = Tools.reqString(a, "entity_type");
                    String name = Tools.reqString(a, "name");
                    return write(ctx, "create_entity " + type + " " + name, mm -> {
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        OWLEntity e = createEntity(mm, type, name, Tools.optString(a, "iri"), changes);
                        mm.applyChanges(changes);
                        return Tools.text("Created " + e.getEntityType().getName() + " "
                                + Tools.renderEntity(mm, e) + ".");
                    });
                })));

        tools.add(ToolSpecs.of("add_subclass_of",
                "Assert that 'child' is a subclass of 'parent'. Each may be a class name, a full IRI, "
                        + "or a Manchester-syntax class expression (e.g. 'hasOwner some Person').",
                Tools.schema()
                        .strReq("child", "Subclass: IRI, name or class expression.")
                        .strReq("parent", "Superclass: IRI, name or class expression.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String child = Tools.reqString(a, "child");
                    String parent = Tools.reqString(a, "parent");
                    return write(ctx, child + " ⊑ " + parent, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        OWLAxiom ax = df.getOWLSubClassOfAxiom(
                                Tools.resolveClassExpression(mm, child),
                                Tools.resolveClassExpression(mm, parent));
                        mm.applyChange(new AddAxiom(ont, ax));
                        return applied(mm, ont, ax, "Added");
                    });
                })));

        tools.add(ToolSpecs.of("add_annotation",
                "Add an annotation assertion to an entity (default property rdfs:label).",
                Tools.schema()
                        .strReq("entity", "Target entity IRI or name.")
                        .str("property", "Annotation property: 'rdfs:label', 'rdfs:comment', or an IRI/name "
                                + "(default rdfs:label).")
                        .strReq("value", "Literal text value.")
                        .str("lang", "Optional language tag, e.g. 'en'.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    String value = Tools.reqString(a, "value");
                    return write(ctx, "annotate " + entityRef, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        IRI subject = annotationSubject(mm, entityRef);
                        OWLAnnotationProperty prop = annotationProperty(mm, Tools.optString(a, "property"));
                        String lang = Tools.optString(a, "lang");
                        OWLLiteral lit = lang != null ? df.getOWLLiteral(value, lang) : df.getOWLLiteral(value);
                        OWLAxiom ax = df.getOWLAnnotationAssertionAxiom(prop, subject, lit);
                        mm.applyChange(new AddAxiom(ont, ax));
                        return applied(mm, ont, ax, "Added annotation");
                    });
                })));

        tools.add(ToolSpecs.of("add_axiom",
                "Add a structured axiom. axiom_type is one of: " + Axioms.SUPPORTED + ". Provide the "
                        + "operands the chosen type needs (sub/super, classes[], class/individual, "
                        + "property/subject/object, property/subject/value[+lang|datatype], "
                        + "property/domain, property/range). Any class operand may be a named class, a "
                        + "full IRI, or a Manchester-syntax class expression such as "
                        + "\"Animal and (hasOwner some Person)\" — so defined classes and restrictions "
                        + "are expressible via equivalent_classes / subclass_of.",
                axiomSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    return write(ctx, "add_axiom " + Tools.optString(a, "axiom_type"), mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLAxiom ax = Axioms.build(mm, a);
                        mm.applyChange(new AddAxiom(ont, ax));
                        return applied(mm, ont, ax, "Added");
                    });
                })));

        tools.add(ToolSpecs.of("remove_axiom",
                "Remove a structured axiom (same arguments as add_axiom). axiom_type is one of: "
                        + Axioms.SUPPORTED + ".",
                axiomSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    return write(ctx, "remove_axiom " + Tools.optString(a, "axiom_type"), mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLAxiom ax = Axioms.build(mm, a);
                        if (!ont.containsAxiom(ax)) {
                            return Tools.error("Axiom not present in the active ontology: "
                                    + Tools.renderAxiom(mm, ax));
                        }
                        mm.applyChange(new RemoveAxiom(ont, ax));
                        boolean gone = !ont.containsAxiom(ax);
                        return Tools.text((gone ? "Removed: " : "Remove had no effect: ")
                                + Tools.renderAxiom(mm, ax));
                    });
                })));

        tools.add(ToolSpecs.of("undo_change",
                "Undo the last change on the shared Protégé undo stack.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> write(ctx, "undo last change", mm -> {
                    HistoryManager hm = mm.getHistoryManager();
                    if (!hm.canUndo()) {
                        return Tools.error("Nothing to undo.");
                    }
                    hm.undo();
                    return Tools.text("Undid the last change.");
                }))));

        tools.add(ToolSpecs.of("redo_change",
                "Redo the last undone change on the shared Protégé undo stack.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> write(ctx, "redo last change", mm -> {
                    HistoryManager hm = mm.getHistoryManager();
                    if (!hm.canRedo()) {
                        return Tools.error("Nothing to redo.");
                    }
                    hm.redo();
                    return Tools.text("Redid the last undone change.");
                }))));

        tools.add(ToolSpecs.of("save_ontology",
                "Save the active ontology to disk. With no arguments it writes to the ontology's "
                        + "existing document; a never-saved (untitled) ontology has no file yet, so "
                        + "pass 'path' to choose one (save-as). The serialization format is inferred "
                        + "from the file extension (.ttl, .owl/.rdf/.xml, .omn, .ofn, .owx) or falls "
                        + "back to the ontology's current format. After a save-as the ontology is "
                        + "bound to that file, so later argument-less saves go to the same place.",
                Tools.schema()
                        .str("path", "Optional file path to save to (save-as), e.g. /tmp/pets.ttl.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String path = Tools.optString(a, "path");
                    String summary = path != null
                            ? "save the active ontology to " + path
                            : "save the active ontology to disk";
                    return write(ctx, summary, mm -> saveOntology(mm, path));
                })));

        return tools;
    }

    // ------------------------------------------------------------------ shared helpers

    /** Apply the read-only + confirmation gates, then run {@code body} on the EDT. */
    private static CallToolResult write(ToolContext ctx, String summary,
            Function<OWLModelManager, CallToolResult> body) {
        if (ctx.controller().isReadOnly()) {
            return Tools.error("Server is in read-only mode; writes are disabled "
                    + "(toggle in Protégé ▸ Preferences ▸ MCP).");
        }
        // The confirmation is an unbounded human interaction, so it runs OUTSIDE the bounded
        // OntologyAccess.compute below. Doing it inside would let a slow click trip the 30s EDT
        // timeout — telling the client the write failed while the dialog stays open and then
        // applies the edit anyway. We confirm first, then marshal the mutation only if approved.
        if (ctx.controller().isConfirmWrites() && !confirmOnEdt(summary)) {
            return Tools.error("Write declined by the user.");
        }
        return ctx.access().compute(body);
    }

    /** Show the modal confirmation on the EDT with no timeout; returns true if the user approves. */
    private static boolean confirmOnEdt(String summary) {
        final boolean[] approved = {false};
        Runnable prompt = () -> {
            int choice = JOptionPane.showConfirmDialog(null,
                    "An MCP client requests this edit:\n\n" + summary,
                    "protege-mcp — confirm write", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            approved[0] = choice == JOptionPane.OK_OPTION;
        };
        if (SwingUtilities.isEventDispatchThread()) {
            prompt.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(prompt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.lang.reflect.InvocationTargetException e) {
                return false;
            }
        }
        return approved[0];
    }

    private static CallToolResult applied(OWLModelManager mm, OWLOntology ont, OWLAxiom ax, String verb) {
        boolean present = ont.containsAxiom(ax);
        return Tools.text(verb + (present ? ": " : " (no effect — already present or minimized): ")
                + Tools.renderAxiom(mm, ax));
    }

    /**
     * Save the active ontology. With {@code path} set this is a save-as: the chosen format and a
     * {@code file:} document IRI are bound to the ontology before {@link OWLModelManager#save} writes
     * it, so subsequent argument-less saves persist to the same file too. Without {@code path} we
     * refuse up front (instead of letting a non-file document IRI fail deep in the OWL API) when the
     * ontology has never been saved.
     */
    private static CallToolResult saveOntology(OWLModelManager mm, String path) {
        OWLOntology ont = mm.getActiveOntology();
        OWLOntologyManager om = mm.getOWLOntologyManager();
        if (path != null) {
            File file = new File(path).getAbsoluteFile();
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return Tools.error("Cannot create directory: " + dir);
            }
            OWLDocumentFormat format = formatForPath(path, om.getOntologyFormat(ont));
            om.setOntologyFormat(ont, format);
            om.setOntologyDocumentIRI(ont, IRI.create(file));
            saveOrThrow(mm, ont);
            return Tools.text("Saved the active ontology to " + file + " ("
                    + format.getClass().getSimpleName() + ").");
        }
        IRI doc = om.getOntologyDocumentIRI(ont);
        if (!isFileDocument(doc)) {
            return Tools.error("This ontology has not been saved to a file yet (current document: "
                    + doc + "). Pass 'path' to choose where to write it, e.g. \"/path/to/ontology.ttl\".");
        }
        saveOrThrow(mm, ont);
        return Tools.text("Saved the active ontology to " + new File(doc.toURI()) + ".");
    }

    private static void saveOrThrow(OWLModelManager mm, OWLOntology ont) {
        try {
            mm.save(ont);
        } catch (OWLOntologyStorageException e) {
            throw new ToolArgException("Save failed: " + e.getMessage());
        }
    }

    private static boolean isFileDocument(IRI iri) {
        if (iri == null) {
            return false;
        }
        try {
            return "file".equalsIgnoreCase(iri.toURI().getScheme());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Pick a serialization format from the file extension, falling back to the current format. */
    private static OWLDocumentFormat formatForPath(String path, OWLDocumentFormat current) {
        String p = path.toLowerCase();
        if (p.endsWith(".ttl") || p.endsWith(".turtle")) {
            return new TurtleDocumentFormat();
        }
        if (p.endsWith(".omn")) {
            return new ManchesterSyntaxDocumentFormat();
        }
        if (p.endsWith(".ofn") || p.endsWith(".fss")) {
            return new FunctionalSyntaxDocumentFormat();
        }
        if (p.endsWith(".owx")) {
            return new OWLXMLDocumentFormat();
        }
        if (p.endsWith(".owl") || p.endsWith(".rdf") || p.endsWith(".xml")) {
            return new RDFXMLDocumentFormat();
        }
        return current != null ? current : new RDFXMLDocumentFormat();
    }

    /**
     * Create an entity of {@code type}. If {@code iri} is given the entity is declared at that exact
     * IRI (plus an rdfs:label = name); otherwise Protégé's entity factory mints the IRI from
     * {@code name}. Accumulates the resulting changes into {@code changes}.
     */
    private static OWLEntity createEntity(OWLModelManager mm, String type, String name, String iri,
            List<OWLOntologyChange> changes) {
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLOntology ont = mm.getActiveOntology();
        String t = type.toLowerCase();
        if (iri != null) {
            OWLEntity e = entityAtIri(df, t, IRI.create(iri));
            changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(e)));
            if (name != null) {
                changes.add(new AddAxiom(ont, df.getOWLAnnotationAssertionAxiom(
                        df.getRDFSLabel(), e.getIRI(), df.getOWLLiteral(name))));
            }
            return e;
        }
        try {
            OWLEntityCreationSet<? extends OWLEntity> set = createViaFactory(mm, t, name);
            changes.addAll(set.getOntologyChanges());
            return set.getOWLEntity();
        } catch (OWLEntityCreationException e) {
            throw new ToolArgException("Could not create " + t + ": " + e.getMessage());
        }
    }

    private static OWLEntity entityAtIri(OWLDataFactory df, String type, IRI iri) {
        switch (type) {
            case "class":
                return df.getOWLClass(iri);
            case "object_property":
                return df.getOWLObjectProperty(iri);
            case "data_property":
                return df.getOWLDataProperty(iri);
            case "annotation_property":
                return df.getOWLAnnotationProperty(iri);
            case "individual":
                return df.getOWLNamedIndividual(iri);
            case "datatype":
                return df.getOWLDatatype(iri);
            default:
                throw new ToolArgException("Unknown entity_type '" + type + "'.");
        }
    }

    private static OWLEntityCreationSet<? extends OWLEntity> createViaFactory(OWLModelManager mm,
            String type, String name) throws OWLEntityCreationException {
        org.protege.editor.owl.model.entity.OWLEntityFactory f = mm.getOWLEntityFactory();
        switch (type) {
            case "class":
                return f.createOWLClass(name, null);
            case "object_property":
                return f.createOWLObjectProperty(name, null);
            case "data_property":
                return f.createOWLDataProperty(name, null);
            case "annotation_property":
                return f.createOWLAnnotationProperty(name, null);
            case "individual":
                return f.createOWLIndividual(name, null);
            case "datatype":
                return f.createOWLDatatype(name, null);
            default:
                throw new ToolArgException("Unknown entity_type '" + type
                        + "'. Use class, object_property, data_property, annotation_property, "
                        + "individual or datatype.");
        }
    }

    private static IRI annotationSubject(OWLModelManager mm, String ref) {
        OWLEntity e = Tools.findEntity(mm, ref);
        if (e != null) {
            return e.getIRI();
        }
        IRI iri = Tools.asIri(ref);
        if (iri != null) {
            return iri;
        }
        throw new ToolArgException("Entity not found: '" + ref + "'. Pass a full IRI to annotate it.");
    }

    private static OWLAnnotationProperty annotationProperty(OWLModelManager mm, String ref) {
        OWLDataFactory df = mm.getOWLDataFactory();
        if (ref == null || "rdfs:label".equalsIgnoreCase(ref) || "label".equalsIgnoreCase(ref)) {
            return df.getRDFSLabel();
        }
        if ("rdfs:comment".equalsIgnoreCase(ref) || "comment".equalsIgnoreCase(ref)) {
            return df.getRDFSComment();
        }
        return Tools.resolveAnnotationProperty(mm, ref);
    }

    private static Map<String, Object> axiomSchema() {
        return Tools.schema()
                .strReq("axiom_type", Axioms.SUPPORTED)
                .str("sub", "subclass_of: subclass — name, IRI or Manchester class expression")
                .str("super", "subclass_of: superclass — name, IRI or Manchester class expression")
                .strArray("classes", "equivalent_classes / disjoint_classes: classes — names, IRIs or "
                        + "Manchester class expressions")
                .str("class", "class_assertion: class — name, IRI or Manchester class expression")
                .str("individual", "class_assertion: individual IRI/name")
                .str("property", "*_property_assertion / *_property_domain|range: property IRI/name")
                .str("subject", "*_property_assertion: subject individual IRI/name")
                .str("object", "object_property_assertion: object individual IRI/name")
                .str("value", "data_property_assertion: literal value")
                .str("lang", "data_property_assertion: optional language tag")
                .str("datatype", "data_property_assertion: optional datatype IRI/name")
                .str("domain", "object_property_domain / data_property_domain: domain class expression")
                .str("range", "object_property_range: range class expression; "
                        + "data_property_range: datatype IRI/name")
                .build();
    }
}
