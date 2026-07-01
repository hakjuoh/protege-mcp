package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
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
                "Create a named class. Give a full 'iri', or a 'namespace' to mint the IRI in (IRI = "
                        + "namespace + name — useful when terms live in a shared namespace distinct from "
                        + "the ontology IRI), else the IRI is minted from 'name' using Protégé's "
                        + "entity-creation settings. An rdfs:label ('label' or 'name', tagged with "
                        + "'label_lang') is added unless 'no_label'. Optionally set a 'parent' superclass.",
                Tools.schema()
                        .strReq("name", "Short name for the class — the IRI local part when minting, and "
                                + "the default rdfs:label.")
                        .str("iri", "Full IRI to use (optional; overrides 'namespace').")
                        .str("namespace", "Namespace to mint the IRI in: IRI becomes namespace + name "
                                + "(optional).")
                        .str("label", "rdfs:label text (default: 'name').")
                        .str("label_lang", "Language tag for the rdfs:label, e.g. 'en-US' (default: none).")
                        .bool("no_label", "Do not add any rdfs:label (default false).")
                        .str("parent", "Superclass: IRI, name or Manchester class expression (optional).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String name = Tools.reqString(a, "name");
                    return write(ctx, "create_class " + name, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        OWLClass cls = (OWLClass) createEntity(mm, "class", a, changes);
                        String parent = Tools.optString(a, "parent");
                        if (parent != null) {
                            OWLClassExpression sup = Tools.resolveClassExpression(mm, parent);
                            changes.add(new AddAxiom(ont, df.getOWLSubClassOfAxiom(cls, sup)));
                        }
                        mm.applyChanges(changes);
                        boolean present = ont.containsEntityInSignature(cls);
                        return Tools.json()
                                .put("created", Tools.entityJson(mm, cls))
                                .putIfNotNull("parent", parent)
                                .put("present", present)
                                .result();
                    });
                })));

        tools.add(ToolSpecs.of("create_entity",
                "Create a named entity of a given type: class, object_property, data_property, "
                        + "annotation_property, individual or datatype. Give a full 'iri', or a "
                        + "'namespace' to mint it in (IRI = namespace + name), else the IRI is minted "
                        + "from 'name'. An rdfs:label ('label' or 'name', tagged with 'label_lang') is "
                        + "added unless 'no_label'.",
                Tools.schema()
                        .strReq("entity_type", "class | object_property | data_property | "
                                + "annotation_property | individual | datatype")
                        .strReq("name", "Short name — the IRI local part when minting, and the default "
                                + "rdfs:label.")
                        .str("iri", "Full IRI to use (optional; overrides 'namespace').")
                        .str("namespace", "Namespace to mint the IRI in: IRI becomes namespace + name "
                                + "(optional).")
                        .str("label", "rdfs:label text (default: 'name').")
                        .str("label_lang", "Language tag for the rdfs:label, e.g. 'en-US' (default: none).")
                        .bool("no_label", "Do not add any rdfs:label (default false).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String type = Tools.reqString(a, "entity_type");
                    String name = Tools.reqString(a, "name");
                    return write(ctx, "create_entity " + type + " " + name, mm -> {
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        OWLEntity e = createEntity(mm, type, a, changes);
                        mm.applyChanges(changes);
                        return Tools.json().put("created", Tools.entityJson(mm, e)).result();
                    });
                })));

        tools.add(ToolSpecs.of("add_subclass_of",
                "Assert that 'child' is a subclass of 'parent'. Each may be a class name, a full IRI, "
                        + "or a Manchester-syntax class expression (e.g. 'hasOwner some Person'). Any "
                        + "entities introduced as a side effect are reported as 'new_entities'.",
                Tools.schema()
                        .strReq("child", "Subclass: IRI, name or class expression.")
                        .strReq("parent", "Superclass: IRI, name or class expression.")
                        .bool("strict", STRICT_DESC)
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String child = Tools.reqString(a, "child");
                    String parent = Tools.reqString(a, "parent");
                    boolean strict = Tools.optBool(a, "strict", false);
                    return write(ctx, child + " ⊑ " + parent, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        OWLAxiom ax = df.getOWLSubClassOfAxiom(
                                Tools.resolveClassExpression(mm, child),
                                Tools.resolveClassExpression(mm, parent));
                        return applyAxiom(mm, ont, ax, strict);
                    });
                })));

        tools.add(ToolSpecs.of("add_annotation",
                "Add an annotation assertion to an entity (default property rdfs:label). The value is a "
                        + "literal (optionally typed with 'datatype' or tagged with 'lang') or, with "
                        + "'value_iri', an IRI; pass 'annotations' to attach axiom annotations (reified "
                        + "owl:Axiom). For a non-entity subject or full OWL 2 symmetry use add_axiom "
                        + "with axiom_type=annotation_assertion.",
                Tools.schema()
                        .strReq("entity", "Target subject: entity IRI/name or any absolute IRI.")
                        .str("property", "Annotation property: 'rdfs:label', 'rdfs:comment', or an IRI/name "
                                + "(default rdfs:label).")
                        .str("value", "Literal text value (omit if value_iri is given).")
                        .str("value_iri", "IRI-valued annotation: an entity name/IRI or absolute IRI "
                                + "(alternative to value).")
                        .str("lang", "Optional language tag for a literal value, e.g. 'en'.")
                        .str("datatype", "Optional datatype IRI/name for a typed literal value.")
                        .annotationArray("annotations", "Optional axiom annotations on this assertion "
                                + "(array of {property, value | value_iri, lang, datatype}).")
                        .bool("strict", STRICT_DESC)
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    boolean strict = Tools.optBool(a, "strict", false);
                    return write(ctx, "annotate " + entityRef, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        IRI subject = Tools.annotationSubject(mm, entityRef);
                        OWLAnnotationProperty prop = Tools.annotationProperty(mm, Tools.optString(a, "property"));
                        OWLAxiom ax = df.getOWLAnnotationAssertionAxiom(prop, subject,
                                Tools.annotationValue(mm, a), Tools.annotationSet(mm, a, "annotations"));
                        return applyAxiom(mm, ont, ax, strict);
                    });
                })));

        tools.add(ToolSpecs.of("add_axiom",
                "Add a structured axiom. axiom_type is one of: " + Axioms.SUPPORTED + ". Provide the "
                        + "operands the chosen type needs (sub/super, classes[], class/individual, "
                        + "property/subject/object, property/subject/value[+lang|datatype], "
                        + "property/domain, property/range). Any class operand may be a named class, a "
                        + "full IRI, or a Manchester-syntax class expression such as "
                        + "\"Animal and (hasOwner some Person)\" — so defined classes and restrictions "
                        + "are expressible via equivalent_classes / subclass_of. Entities introduced as "
                        + "a side effect are reported as 'new_entities'.",
                withStrict(Axioms.schema()),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean strict = Tools.optBool(a, "strict", false);
                    return write(ctx, "add_axiom " + Tools.optString(a, "axiom_type"), mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLAxiom ax = Axioms.build(mm, a);
                        return applyAxiom(mm, ont, ax, strict);
                    });
                })));

        tools.add(ToolSpecs.of("remove_axiom",
                "Remove a structured axiom (same arguments as add_axiom). axiom_type is one of: "
                        + Axioms.SUPPORTED + ".",
                Axioms.schema(),
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
                        return Tools.json()
                                .put("removed", gone)
                                .put("axiom", Tools.axiomJson(mm, ax))
                                .result();
                    });
                })));

        tools.add(ToolSpecs.of("apply_changes",
                "Apply a batch of axiom add/remove operations in ONE call — the same 'operations' array "
                        + "as preview_changes (each item: axiom_type + operands, optional op=add|remove, "
                        + "default add). Closes the gap where preview batches but the write tools apply "
                        + "one axiom per call. The whole batch is applied as a SINGLE undoable "
                        + "transaction, so one undo_change reverts all of it at once (like create_class). "
                        + "Reports, per operation, what was applied/removed and any new entities "
                        + "introduced, plus a summary. Run preview_changes first to dry-run; set "
                        + "strict=true to skip any add that would mint a brand-new entity from an "
                        + "unrecognized IRI/name. Note: because nothing is applied until the batch "
                        + "completes, an operation that references an entity introduced by an EARLIER "
                        + "operation in the same batch must refer to it by full IRI.",
                applyChangesSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> operations = Tools.objList(a, "operations");
                    if (operations.isEmpty()) {
                        return Tools.error("Provide at least one operation in 'operations' "
                                + "(each: axiom_type + operands, optional op=add|remove).");
                    }
                    boolean strict = Tools.optBool(a, "strict", false);
                    return write(ctx, "apply " + operations.size() + " change(s)",
                            mm -> applyBatch(mm, operations, strict));
                })));

        tools.add(ToolSpecs.of("set_label",
                "Set (upsert) an entity's rdfs:label: removes any existing rdfs:label on the entity in "
                        + "the SAME language and adds the new one. Use this to fix a label without "
                        + "hand-removing the old axiom — rename_entity changes the IRI, not the label.",
                Tools.schema()
                        .strReq("entity", "Target entity: IRI or display name.")
                        .strReq("value", "New rdfs:label text.")
                        .str("lang", "Language tag, e.g. 'en-US' (default none). Only labels in the same "
                                + "language are replaced.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    String value = Tools.reqString(a, "value");
                    String lang = Tools.optString(a, "lang");
                    return write(ctx, "set label of " + entityRef, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        IRI subject = Tools.annotationSubject(mm, entityRef);
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        int removed = 0;
                        for (OWLAnnotationAssertionAxiom ax : ont.getAnnotationAssertionAxioms(subject)) {
                            if (!ax.getProperty().isLabel() || !ax.getValue().asLiteral().isPresent()) {
                                continue;
                            }
                            OWLLiteral lit = ax.getValue().asLiteral().get();
                            boolean sameLang = lang == null ? !lit.hasLang() : lang.equalsIgnoreCase(lit.getLang());
                            if (sameLang) {
                                changes.add(new RemoveAxiom(ont, ax));
                                removed++;
                            }
                        }
                        OWLLiteral newLit = lang != null ? df.getOWLLiteral(value, lang) : df.getOWLLiteral(value);
                        changes.add(new AddAxiom(ont,
                                df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), subject, newLit)));
                        mm.applyChanges(changes);
                        return Tools.json()
                                .put("entity", subject.toString())
                                .put("label", value)
                                .putIfNotNull("lang", lang)
                                .put("removed_previous", removed)
                                .result();
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
                    long before = totalAxioms(mm);
                    hm.undo();
                    long after = totalAxioms(mm);
                    return Tools.json().put("undone", true)
                            .put("message", "Undid the last change.")
                            .put("axioms_before", before)
                            .put("axioms_after", after)
                            .put("net_axiom_change", after - before)
                            .put("can_undo", hm.canUndo()).put("can_redo", hm.canRedo()).result();
                }))));

        tools.add(ToolSpecs.of("redo_change",
                "Redo the last undone change on the shared Protégé undo stack.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> write(ctx, "redo last change", mm -> {
                    HistoryManager hm = mm.getHistoryManager();
                    if (!hm.canRedo()) {
                        return Tools.error("Nothing to redo.");
                    }
                    long before = totalAxioms(mm);
                    hm.redo();
                    long after = totalAxioms(mm);
                    return Tools.json().put("redone", true)
                            .put("message", "Redid the last undone change.")
                            .put("axioms_before", before)
                            .put("axioms_after", after)
                            .put("net_axiom_change", after - before)
                            .put("can_undo", hm.canUndo()).put("can_redo", hm.canRedo()).result();
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
    static CallToolResult write(ToolContext ctx, String summary,
            Function<OWLModelManager, CallToolResult> body) {
        CallToolResult denied = checkWriteAllowed(ctx, summary);
        if (denied != null) {
            return denied;
        }
        return ctx.access().compute(body);
    }

    static CallToolResult checkWriteAllowed(ToolContext ctx, String summary) {
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
        return null;
    }

    /** Show the modal confirmation on the EDT with no timeout; returns true if the user approves. */
    private static boolean confirmOnEdt(String summary) {
        final boolean[] approved = {false};
        Runnable prompt = () -> {
            int choice = JOptionPane.showConfirmDialog(null,
                    "An MCP client requests this action:\n\n" + summary,
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

    static final String STRICT_DESC = "If true, fail instead of minting a brand-new entity from an "
            + "unrecognized absolute IRI / display name (guards against typo'd references). Default false.";

    /**
     * Apply an add-axiom change, reporting any entities it introduces into the ontology that were not
     * already declared anywhere in the imports closure (the silent-minting signal). When {@code strict}
     * is set and the change would introduce such entities, nothing is applied and an error is returned.
     */
    private static CallToolResult applyAxiom(OWLModelManager mm, OWLOntology ont, OWLAxiom ax,
            boolean strict) {
        Set<OWLEntity> minted = PreviewTools.newEntities(ont.getImportsClosure(), ax);
        if (strict && !minted.isEmpty()) {
            return mintError(mm, minted);
        }
        mm.applyChange(new AddAxiom(ont, ax));
        return applied(mm, ont, ax, minted);
    }

    private static CallToolResult applied(OWLModelManager mm, OWLOntology ont, OWLAxiom ax,
            Set<OWLEntity> minted) {
        boolean present = ont.containsAxiom(ax);
        Tools.Json json = Tools.json()
                .put("applied", present)
                .put("axiom", Tools.axiomJson(mm, ax));
        if (minted != null && !minted.isEmpty()) {
            List<Map<String, Object>> ne = new ArrayList<>();
            for (OWLEntity e : minted) {
                ne.add(Tools.entityJson(mm, e));
            }
            json.put("new_entities", ne);
        }
        return json.putIfNotNull("note", present ? null
                : "No effect — already present or minimized away.").result();
    }

    private static CallToolResult mintError(OWLModelManager mm, Set<OWLEntity> minted) {
        return Tools.error("Refusing to apply (strict): the reference(s) " + renderMinted(mm, minted)
                + " are not declared anywhere in the imports closure and would be created as new, empty "
                + "entities — likely a typo'd IRI/name. Fix the reference, create the entity first, or "
                + "set strict=false to allow minting.");
    }

    private static String renderMinted(OWLModelManager mm, Set<OWLEntity> minted) {
        List<String> parts = new ArrayList<>();
        for (OWLEntity e : minted) {
            parts.add(mm.getRendering(e) + " <" + e.getIRI() + ">");
        }
        return String.join(", ", parts);
    }

    /** Total asserted axioms across all loaded ontologies — a simple "what changed" delta for undo/redo. */
    private static long totalAxioms(OWLModelManager mm) {
        long n = 0;
        for (OWLOntology o : mm.getOntologies()) {
            n += o.getAxiomCount();
        }
        return n;
    }

    /** add_axiom's schema is Axioms.schema() plus the optional 'strict' typo-guard flag. */
    private static Map<String, Object> withStrict(Map<String, Object> schema) {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        props.put("strict", Tools.boolProperty(STRICT_DESC));
        return schema;
    }

    /** apply_changes' schema is preview_changes' operations[] plus the optional 'strict' flag. */
    private static Map<String, Object> applyChangesSchema() {
        Map<String, Object> schema = PreviewTools.operationsSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        props.put("strict", Tools.boolProperty(STRICT_DESC));
        return schema;
    }

    /**
     * Apply each operation in {@code operations} (add/remove) against the active ontology as ONE
     * undoable transaction. A first pass builds + strict-checks each axiom and records per-operation
     * results against a simulated copy of the batch's effect; a single {@link OWLModelManager#applyChanges}
     * then commits every resulting change at once, so the whole batch reverts in a single
     * {@code undo_change}. Because nothing is applied until that final pass, an operation referencing an
     * entity introduced by an earlier operation in the same batch must refer to it by full IRI.
     */
    private static CallToolResult applyBatch(OWLModelManager mm, List<Map<String, Object>> operations,
            boolean strict) {
        OWLOntology ont = mm.getActiveOntology();
        Set<OWLOntology> closure = ont.getImportsClosure();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<OWLOntologyChange> toApply = new ArrayList<>();
        Set<OWLAxiom> simAdded = new LinkedHashSet<>();    // net-new axioms this batch plans to add
        Set<OWLAxiom> simRemoved = new LinkedHashSet<>();  // existing axioms this batch plans to remove
        int added = 0;
        int removed = 0;
        int noOps = 0;
        int errors = 0;
        for (int i = 0; i < operations.size(); i++) {
            Map<String, Object> item = operations.get(i);
            String opRaw = Tools.optString(item, "op");
            String op = opRaw == null ? "add" : opRaw.toLowerCase();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("op", op);
            if (!"add".equals(op) && !"remove".equals(op)) {
                row.put("error", "Unsupported op '" + op + "'. Use add or remove.");
                errors++;
                rows.add(row);
                continue;
            }
            try {
                OWLAxiom ax = Axioms.build(mm, item);
                row.put("axiom", Tools.axiomJson(mm, ax));
                // Present == in the ontology now, plus/minus what earlier ops in this batch plan.
                boolean present = (ont.containsAxiom(ax) || simAdded.contains(ax))
                        && !simRemoved.contains(ax);
                if ("remove".equals(op)) {
                    if (present) {
                        toApply.add(new RemoveAxiom(ont, ax));
                        if (ont.containsAxiom(ax)) {
                            simRemoved.add(ax);
                        }
                        simAdded.remove(ax);
                        row.put("removed", true);
                        removed++;
                    } else {
                        row.put("removed", false);
                        row.put("note", "not present");
                        noOps++;
                    }
                } else {
                    Set<OWLEntity> minted = newEntitiesAfterSimulatedAdds(closure, simAdded, ax);
                    if (strict && !minted.isEmpty()) {
                        row.put("error", "strict: would mint " + renderMinted(mm, minted));
                        errors++;
                    } else if (present) {
                        row.put("applied", true);
                        row.put("note", "already present");
                        noOps++;
                    } else {
                        toApply.add(new AddAxiom(ont, ax));
                        simAdded.add(ax);
                        simRemoved.remove(ax);
                        row.put("applied", true);
                        added++;
                        if (!minted.isEmpty()) {
                            List<Map<String, Object>> ne = new ArrayList<>();
                            for (OWLEntity e : minted) {
                                ne.add(Tools.entityJson(mm, e));
                            }
                            row.put("new_entities", ne);
                        }
                    }
                }
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                row.put("error", msg == null ? e.getClass().getSimpleName() : msg);
                errors++;
            }
            rows.add(row);
        }
        if (!toApply.isEmpty()) {
            mm.applyChanges(toApply);  // one broadcast → one Protégé undo entry for the whole batch
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operations", operations.size());
        summary.put("added", added);
        summary.put("removed", removed);
        summary.put("no_ops", noOps);
        summary.put("errors", errors);
        summary.put("single_undo", !toApply.isEmpty());
        summary.put("new_entities", Tools.entityList(mm,
                newEntitiesIntroducedByAxioms(closure, simAdded), Integer.MAX_VALUE));
        return Tools.json().put("operations", rows).put("summary", summary).result();
    }

    /** Entities in {@code ax} that are not already known now or by earlier net additions in the batch. */
    private static Set<OWLEntity> newEntitiesAfterSimulatedAdds(Set<OWLOntology> closure,
            Set<OWLAxiom> simAdded, OWLAxiom ax) {
        Set<OWLEntity> out = PreviewTools.newEntities(closure, ax);
        if (out.isEmpty() || simAdded.isEmpty()) {
            return out;
        }
        out.removeAll(newEntitiesIntroducedByAxioms(closure, simAdded));
        return out;
    }

    /** New entities introduced by the simulated net-add axiom set. */
    private static Set<OWLEntity> newEntitiesIntroducedByAxioms(Set<OWLOntology> closure,
            Set<OWLAxiom> axioms) {
        Set<OWLEntity> out = new LinkedHashSet<>();
        for (OWLAxiom ax : axioms) {
            out.addAll(PreviewTools.newEntities(closure, ax));
        }
        return out;
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
            return Tools.json()
                    .put("saved", true)
                    .put("path", file.toString())
                    .put("format", format.getClass().getSimpleName())
                    .result();
        }
        IRI doc = om.getOntologyDocumentIRI(ont);
        if (!isFileDocument(doc)) {
            return Tools.error("This ontology has not been saved to a file yet (current document: "
                    + doc + "). Pass 'path' to choose where to write it, e.g. \"/path/to/ontology.ttl\".");
        }
        saveOrThrow(mm, ont);
        return Tools.json()
                .put("saved", true)
                .put("path", new File(doc.toURI()).toString())
                .result();
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
     * Create an entity of {@code type} from the create_* arguments {@code a} (name, iri, namespace,
     * label, label_lang, no_label). With an explicit {@code iri} (or {@code namespace} + name) the
     * entity is declared at that exact IRI; otherwise Protégé's entity factory mints the IRI from
     * {@code name}. Unless {@code no_label}, an rdfs:label ('label' or 'name', tagged with
     * {@code label_lang}) is added. Accumulates the resulting changes into {@code changes}.
     */
    static OWLEntity createEntity(OWLModelManager mm, String type, Map<String, Object> a,
            List<OWLOntologyChange> changes) {
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLOntology ont = mm.getActiveOntology();
        String t = type.toLowerCase();
        String name = Tools.reqString(a, "name");
        String iri = Tools.optString(a, "iri");
        String namespace = Tools.optString(a, "namespace");
        if (iri == null && namespace != null) {
            iri = joinNamespace(namespace, name);
        }
        boolean noLabel = Tools.optBool(a, "no_label", false);
        String labelText = Tools.optString(a, "label");
        String labelLang = Tools.optString(a, "label_lang");
        if (iri != null) {
            OWLEntity e = entityAtIri(df, t, IRI.create(iri));
            changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(e)));
            if (!noLabel) {
                changes.add(new AddAxiom(ont, df.getOWLAnnotationAssertionAxiom(
                        df.getRDFSLabel(), e.getIRI(), label(df, labelText != null ? labelText : name, labelLang))));
            }
            return e;
        }
        try {
            OWLEntityCreationSet<? extends OWLEntity> set = createViaFactory(mm, t, name);
            OWLEntity e = set.getOWLEntity();
            boolean customLabel = labelText != null || labelLang != null;
            for (OWLOntologyChange change : set.getOntologyChanges()) {
                if ((noLabel || customLabel) && isLabelChangeFor(change, e)) {
                    continue;
                }
                changes.add(change);
            }
            if (!noLabel && customLabel) {
                changes.add(new AddAxiom(ont, df.getOWLAnnotationAssertionAxiom(
                        df.getRDFSLabel(), e.getIRI(), label(df, labelText != null ? labelText : name, labelLang))));
            }
            return e;
        } catch (OWLEntityCreationException e) {
            throw new ToolArgException("Could not create " + t + ": " + e.getMessage());
        }
    }

    /** True for Protégé entity-factory auto-label changes for the entity being created. */
    private static boolean isLabelChangeFor(OWLOntologyChange change, OWLEntity e) {
        if (!(change instanceof AddAxiom)) {
            return false;
        }
        OWLAxiom ax = ((AddAxiom) change).getAxiom();
        if (!(ax instanceof OWLAnnotationAssertionAxiom)) {
            return false;
        }
        OWLAnnotationAssertionAxiom ann = (OWLAnnotationAssertionAxiom) ax;
        return ann.getProperty().isLabel() && ann.getSubject().equals(e.getIRI());
    }

    private static OWLLiteral label(OWLDataFactory df, String text, String lang) {
        return lang != null ? df.getOWLLiteral(text, lang) : df.getOWLLiteral(text);
    }

    /** Join a namespace and a local name (insert '/' unless the namespace already ends in /, # or :). */
    private static String joinNamespace(String namespace, String name) {
        String local = name.trim().replace(" ", "");
        if (namespace.isEmpty()) {
            return local;
        }
        char last = namespace.charAt(namespace.length() - 1);
        return (last == '/' || last == '#' || last == ':') ? namespace + local : namespace + "/" + local;
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

}
