package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.protege.editor.owl.model.IOListenerManager;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.entity.OWLEntityCreationException;
import org.protege.editor.owl.model.entity.OWLEntityCreationSet;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.history.HistoryManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
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

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("create_class",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    return write(ctx, "create_class " + summaryName(a), mm -> {
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
                });
        tools.tool("create_entity",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String type = Tools.reqString(a, "entity_type");
                    return write(ctx, "create_entity " + type + " " + summaryName(a), mm -> {
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        OWLEntity e = createEntity(mm, type, a, changes);
                        mm.applyChanges(changes);
                        return Tools.json().put("created", Tools.entityJson(mm, e)).result();
                    });
                });
        tools.tool("add_subclass_of",
                (ex, req) -> {
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
                });
        tools.tool("add_annotation",
                (ex, req) -> {
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
                });
        tools.tool("add_axiom",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean strict = Tools.optBool(a, "strict", false);
                    return write(ctx, "add_axiom " + Tools.optString(a, "axiom_type"), mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLAxiom ax = Axioms.build(mm, a);
                        return applyAxiom(mm, ont, ax, strict);
                    });
                });
        tools.tool("remove_axiom",
                (ex, req) -> {
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
                });
        tools.tool("apply_changes",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> operations = Tools.objList(a, "operations");
                    if (operations.isEmpty()) {
                        return Tools.error("Provide at least one operation in 'operations' "
                                + "(each: axiom_type + operands, optional op=add|remove).");
                    }
                    boolean strict = Tools.optBool(a, "strict", false);
                    String verify = ApplyVerify.normalizeMode(Tools.optString(a, "verify"));
                    String summary = "apply " + operations.size() + " change(s)"
                            + (ApplyVerify.MODE_NONE.equals(verify) ? "" : " (verify=" + verify + ")");
                    if (ApplyVerify.MODE_NONE.equals(verify)) {
                        return write(ctx, summary, mm -> applyBatch(mm, operations, strict));
                    }
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    if (timeout <= 0) {
                        timeout = 60_000;
                    }
                    DirectAccessPolicy.requireCapability(ex, DirectAccessPolicy.PROJECT_READ);
                    return ChangeSetApplyVerify.apply(ctx, verify, timeout, summary, operations, strict);
                });
        tools.tool("set_label",
                (ex, req) -> {
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
                });
        tools.tool("undo_change",
                (ex, req) -> {
                    if (Tools.optBool(Tools.args(req), "peek", false)) {
                        return ctx.access().compute(WriteTools::peekUndo);
                    }
                    return write(ctx, "undo last change", mm -> {
                        HistoryManager hm = mm.getHistoryManager();
                        if (!hm.canUndo()) {
                            return Tools.error("Nothing to undo.");
                        }
                        long before = totalAxioms(mm);
                        hm.undo();
                        long after = totalAxioms(mm);
                        boolean dirty = mm.getDirtyOntologies().contains(mm.getActiveOntology());
                        return Tools.json().put("undone", true)
                                .put("message", "Undid the last change.")
                                .put("axioms_before", before)
                                .put("axioms_after", after)
                                .put("net_axiom_change", after - before)
                                .put("undo_depth", hm.getLoggedChanges().size())
                                .put("can_undo", hm.canUndo()).put("can_redo", hm.canRedo())
                                .put("dirty", dirty)
                                .put("dirty_note", "Protégé keeps the ontology marked dirty after "
                                        + "Undo until the next save, even when content returns to its "
                                        + "loaded fingerprint.")
                                .result();
                    });
                });
        tools.tool("redo_change",
                (ex, req) -> write(ctx, "redo last change", mm -> {
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
                            .put("undo_depth", hm.getLoggedChanges().size())
                            .put("can_undo", hm.canUndo()).put("can_redo", hm.canRedo()).result();
                }));

        tools.tool("save_ontology",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String configuredPath = Tools.optString(a, "path");
                    boolean all = Tools.optBool(a, "all", false);
                    boolean verifyRoundTrip = Tools.optBool(a, "verify_round_trip", false);
                    boolean atomic = Tools.optBool(a, "atomic", false);
                    boolean backup = Tools.optBool(a, "backup", false);
                    if (all && configuredPath != null) {
                        return Tools.error("'all' saves every dirty ontology to its own existing "
                                + "document and cannot be combined with 'path' (a save-as targets "
                                + "only the active ontology).");
                    }
                    DirectAccessPolicy.Rules accessRules = DirectAccessPolicy.resolve(ctx, ex);
                    final String path;
                    if (configuredPath != null) {
                        path = accessRules.writePath(configuredPath).toString();
                    } else {
                        // Argument-less saves and all=true write back to the documents already open
                        // in Protégé — derived targets, not caller-selected paths. Authorize each one
                        // before confirmation or serialization begins.
                        List<String> targets = ctx.access().compute(mm -> saveTargets(mm, all));
                        for (String target : targets) {
                            accessRules.implicitPath(java.nio.file.Path.of(target), true);
                        }
                        path = null;
                    }
                    if (all) {
                        if (verifyRoundTrip || atomic || backup) {
                            return Tools.error("Verified/atomic/backup save currently targets one active "
                                    + "ontology; it cannot be combined with all=true.");
                        }
                        return write(ctx, "save all modified ontologies to disk",
                                WriteTools::saveAllDirty, SAVE_TIMEOUT_MS);
                    }
                    String summary = path != null
                            ? "save the active ontology to " + path
                            : "save the active ontology to disk";
                    if (verifyRoundTrip || atomic || backup) {
                        return verifiedSave(ctx, summary, path, atomic, backup);
                    }
                    return write(ctx, summary, mm -> saveOntology(mm, path), SAVE_TIMEOUT_MS);
                });
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Serializing a big ontology — worse, EVERY dirty ontology under all=true — can legitimately
     * outlive the default EDT wait, and a "timed out" report while the files keep being written is
     * a false failure. Same rationale (and value) as the document tools' merge/load bound.
     */
    private static final long SAVE_TIMEOUT_MS = 120_000L;

    private static List<String> saveTargets(OWLModelManager mm, boolean all) {
        List<String> targets = new ArrayList<>();
        Collection<OWLOntology> ontologies = all
                ? mm.getDirtyOntologies() : List.of(mm.getActiveOntology());
        for (OWLOntology ontology : ontologies) {
            IRI document = mm.getOWLOntologyManager().getOntologyDocumentIRI(ontology);
            if (isFileDocument(document)) {
                targets.add(new File(document.toURI()).getAbsolutePath());
            }
        }
        return targets;
    }

    /** Apply the read-only + confirmation gates, then run {@code body} on the EDT. */
    static CallToolResult write(ToolContext ctx, String summary,
            Function<OWLModelManager, CallToolResult> body) {
        CallToolResult denied = checkWriteAllowed(ctx, summary);
        if (denied != null) {
            return denied;
        }
        return ctx.access().compute(body);
    }

    /** {@link #write(ToolContext, String, Function)} with an explicit EDT wait bound. */
    static CallToolResult write(ToolContext ctx, String summary,
            Function<OWLModelManager, CallToolResult> body, long boundMillis) {
        CallToolResult denied = checkWriteAllowed(ctx, summary);
        if (denied != null) {
            return denied;
        }
        return ctx.access().compute(body, boundMillis);
    }

    static CallToolResult checkWriteAllowed(ToolContext ctx, String summary) {
        if (ctx.controller().isReadOnly()) {
            return readOnlyDenied();
        }
        // The confirmation is an unbounded human interaction, so it runs via the injected WriteConfirmer
        // (the Swing dialog at runtime) OUTSIDE the bounded OntologyAccess.compute below. Doing it inside
        // would let a slow click trip the 30s EDT timeout — telling the client the write failed while the
        // dialog stays open and then applies the edit anyway. We confirm first, then marshal the mutation
        // only if approved. Fail closed: if confirmation is required but no confirmer is wired, decline.
        if (ctx.controller().isConfirmWrites()) {
            WriteConfirmer confirmer = ctx.confirmer();
            if (confirmer == null || !confirmer.confirm(summary)) {
                return Tools.error("Write declined by the user.");
            }
        }
        return null;
    }

    /** The uniform read-only refusal every write gate returns. */
    static CallToolResult readOnlyDenied() {
        return Tools.error("Server is in read-only mode; writes are disabled "
                + "(toggle in Protégé ▸ Preferences ▸ MCP).");
    }

    /**
     * Apply an add-axiom change, reporting any entities it introduces into the ontology that were not
     * already declared anywhere in the imports closure (the silent-minting signal). When {@code strict}
     * is set and the change would introduce such entities, nothing is applied and an error is returned.
     */
    /**
     * Queue a Declaration for each minted entity, matching how {@link #createEntity} declares its
     * primary entity. Entities first introduced as an operand side effect — an annotation property
     * referenced by a definition/annotation, an individual named in a class assertion, a class named
     * in a subclass axiom — otherwise stay used-but-undeclared, which leaves the ontology short of
     * OWL 2 DL (undeclared annotation properties are a profile violation) and makes a save/reload
     * round-trip non-identical (the serializer re-adds the type triples as declarations). Skips
     * built-ins and any declaration already present, and appends to the SAME change list so the
     * declarations ride along in one undo unit.
     */
    static void declareMinted(OWLDataFactory df, OWLOntology ont, Set<OWLEntity> minted,
            List<OWLOntologyChange> out) {
        if (minted == null) {
            return;
        }
        for (OWLEntity e : minted) {
            if (e.isBuiltIn()) {
                continue;
            }
            OWLAxiom decl = df.getOWLDeclarationAxiom(e);
            if (!ont.containsAxiom(decl)) {
                out.add(new AddAxiom(ont, decl));
            }
        }
    }

    /** A human label for the write summary: 'name', else the 'iri' local part, else "entity". */
    static String summaryName(Map<String, Object> a) {
        String name = Tools.optString(a, "name");
        if (name != null) {
            return name;
        }
        String iri = Tools.optString(a, "iri");
        return iri != null ? localName(iri) : "entity";
    }

    /**
     * OWL 2 DL requires a Declaration for every non-built-in annotation property used in an annotation.
     * The create_* and annotation write paths inject annotation properties (a definition property such as
     * skos:definition, dcterms:*, a project av:* property) that are commonly used-but-never-declared
     * across the imports closure — e.g. an imported ontology uses skos:definition without declaring it —
     * so {@link PreviewTools#newEntities} / {@link #declareMinted} (which treat an entity already present
     * in the closure signature as "known") never declare them and the active ontology silently leaves
     * OWL 2 DL. Declare, in the active ontology, each annotation property referenced by the AddAxioms in
     * {@code changes} that is not OWL-built-in and that NO ontology in the imports closure declares.
     * Keyed on isDeclared (not containsEntityInSignature), so a used-but-undeclared upstream annotation
     * property is still declared locally. Appends to the SAME change list (one undo unit).
     */
    static void declareUsedAnnotationProperties(OWLDataFactory df, OWLOntology ont,
            List<OWLOntologyChange> changes) {
        Set<OWLAnnotationProperty> used = new LinkedHashSet<>();
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom) {
                used.addAll(((AddAxiom) ch).getAxiom().getAnnotationPropertiesInSignature());
            }
        }
        declareAnnotationProperties(df, ont, used, changes);
    }

    /**
     * Declare, in {@code ont}, each annotation property in {@code used} that is not OWL-built-in and is
     * not declared anywhere in the imports closure (appending to {@code changes}). Shared by the
     * axiom-path {@link #declareUsedAnnotationProperties} and the ontology-annotation write path (whose
     * property is not carried by any axiom).
     */
    static void declareAnnotationProperties(OWLDataFactory df, OWLOntology ont,
            Set<OWLAnnotationProperty> used, List<OWLOntologyChange> changes) {
        if (used.isEmpty()) {
            return;
        }
        Set<OWLOntology> closure = ont.getImportsClosure();
        for (OWLAnnotationProperty ap : used) {
            if (ap.isBuiltIn() || isDeclaredInClosure(closure, ap)) {
                continue;
            }
            OWLAxiom decl = df.getOWLDeclarationAxiom(ap);
            if (!ont.containsAxiom(decl) && !isAlreadyAdded(changes, decl)) {
                changes.add(new AddAxiom(ont, decl));
            }
        }
    }

    private static boolean isDeclaredInClosure(Set<OWLOntology> closure, OWLEntity e) {
        for (OWLOntology o : closure) {
            if (o.isDeclared(e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAlreadyAdded(List<OWLOntologyChange> changes, OWLAxiom decl) {
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom && ((AddAxiom) ch).getAxiom().equals(decl)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience over {@link #declareMinted}: compute the entities the AddAxioms in {@code changes}
     * introduce into the closure (minus {@code exclude}, typically an entity already declared by its
     * own create step) and queue their declarations into the SAME {@code changes} list. Used by the
     * direct-apply curation macros (deprecate_entity, move_class) that don't go through applyCuration.
     */
    static void declareMintedFromChanges(OWLModelManager mm, OWLOntology ont,
            List<OWLOntologyChange> changes, OWLEntity exclude) {
        Set<OWLOntology> closure = ont.getImportsClosure();
        Set<OWLEntity> minted = new LinkedHashSet<>();
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom) {
                minted.addAll(PreviewTools.newEntities(closure, ((AddAxiom) ch).getAxiom()));
            }
        }
        if (exclude != null) {
            minted.remove(exclude);
        }
        declareMinted(mm.getOWLDataFactory(), ont, minted, changes);
        declareUsedAnnotationProperties(mm.getOWLDataFactory(), ont, changes);
    }

    private static CallToolResult applyAxiom(OWLModelManager mm, OWLOntology ont, OWLAxiom ax,
            boolean strict) {
        Set<OWLEntity> minted = PreviewTools.newEntities(ont.getImportsClosure(), ax);
        if (strict && !minted.isEmpty()) {
            return mintError(mm, minted);
        }
        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ont, ax));
        declareMinted(mm.getOWLDataFactory(), ont, minted, changes);
        declareUsedAnnotationProperties(mm.getOWLDataFactory(), ont, changes);
        mm.applyChanges(changes);  // axiom + any declarations for side-effect entities, one undo unit
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
        return Tools.error("Refusing to apply (strict): the reference(s) "
                + EntityRendering.renderMinted(mm, minted)
                + " are not declared anywhere in the imports closure and would be created as new, empty "
                + "entities — likely a typo'd IRI/name. Fix the reference, create the entity first, or "
                + "set strict=false to allow minting.");
    }

    /** Total asserted axioms across all loaded ontologies — a simple "what changed" delta for undo/redo. */
    private static long totalAxioms(OWLModelManager mm) {
        long n = 0;
        for (OWLOntology o : mm.getOntologies()) {
            n += o.getAxiomCount();
        }
        return n;
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
        return Tools.ok(applyBatchData(mm, operations, strict));
    }

    /**
     * The batch-apply core, returning the raw result map ({@code {operations, summary}}) rather than a
     * wrapped {@link CallToolResult}, so direct and change-set-verified paths share one live commit
     * implementation. See {@link #applyBatch} for the behaviour contract.
     */
    static Map<String, Object> applyBatchData(OWLModelManager mm, List<Map<String, Object>> operations,
            boolean strict) {
        return batchData(mm, operations, strict, true);
    }

    /** Build the released apply_changes payload against the live revision without mutating it. */
    static Map<String, Object> simulateBatchData(OWLModelManager mm,
            List<Map<String, Object>> operations, boolean strict) {
        return batchData(mm, operations, strict, false);
    }

    private static Map<String, Object> batchData(OWLModelManager mm,
            List<Map<String, Object>> operations, boolean strict, boolean apply) {
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
                        row.put("error", "strict: would mint " + EntityRendering.renderMinted(mm, minted));
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
        // The batch's net-new entities MUST be computed before the changes are applied: once committed the
        // entities exist in the closure and read as "not new", which left summary.new_entities empty even
        // though the per-op rows (computed pre-apply) correctly listed the minted entities.
        Set<OWLEntity> mintedAll = newEntitiesIntroducedByAxioms(closure, simAdded);
        declareMinted(mm.getOWLDataFactory(), ont, mintedAll, toApply);  // declare side-effect entities
        declareUsedAnnotationProperties(mm.getOWLDataFactory(), ont, toApply);
        if (apply && !toApply.isEmpty()) {
            mm.applyChanges(toApply);  // one broadcast → one Protégé undo entry for the whole batch
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operations", operations.size());
        summary.put("added", added);
        summary.put("removed", removed);
        summary.put("no_ops", noOps);
        summary.put("errors", errors);
        summary.put("single_undo", !toApply.isEmpty());
        summary.put("new_entities", Tools.entityList(mm, mintedAll, Integer.MAX_VALUE));
        return Tools.json().put("operations", rows).put("summary", summary).map();
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
            OWLDocumentFormat current = om.getOntologyFormat(ont);
            // Resolve the format FIRST: it can reject the extension, and a rejected save-as must
            // not leave side effects (created directories, rebound format/document IRI).
            OWLDocumentFormat format = formatForPath(path, current);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return Tools.error("Cannot create directory: " + dir);
            }
            // A save-as picks a fresh format whose prefix map is empty. Carry the ontology's
            // registered prefixes (custom prefixes + default/base) into it so the save does not
            // drop them on disk AND in memory — replacing the format with an empty one would
            // otherwise break every CURIE resolution afterwards (get_entity_context, sparql, ...).
            if (format != current
                    && format.isPrefixOWLOntologyFormat()
                    && current != null && current.isPrefixOWLOntologyFormat()) {
                format.asPrefixOWLOntologyFormat().copyPrefixesFrom(current.asPrefixOWLOntologyFormat());
            }
            IRI previousDoc = om.getOntologyDocumentIRI(ont);
            om.setOntologyFormat(ont, format);
            om.setOntologyDocumentIRI(ont, IRI.create(file));
            try {
                saveOrThrow(mm, ont);
            } catch (RuntimeException e) {
                // A failed save-as (realistic with OBO, whose writer validates frame structure —
                // e.g. two rdfs:labels on one entity) must not leave the ontology bound to the
                // target that just failed: every later argument-less save AND the GUI's own
                // File ▸ Save would silently retry the broken format/path. Restore the binding.
                if (current != null) {
                    om.setOntologyFormat(ont, current);
                }
                om.setOntologyDocumentIRI(ont, previousDoc);
                throw e;
            }
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

    private static CallToolResult verifiedSave(ToolContext ctx, String summary, String path,
            boolean atomic, boolean backup) {
        CallToolResult denied = checkWriteAllowed(ctx, summary);
        if (denied != null) {
            return denied;
        }
        RevisionTools.PolicyState policy = RevisionTools.resolvePolicy(ctx, null);
        String lockDigest = RevisionTools.digestImportLock(policy.policy());
        // The capture hop copies every axiom into a snapshot and computes two canonical-serialization
        // fingerprints — exactly the big-ontology work SAVE_TIMEOUT_MS exists for, so it must not be
        // cut short by the default EDT bound like the install hop below.
        VerifiedSaveCapture captured = ctx.access().compute(mm -> captureVerifiedSave(ctx, mm, path,
                lockDigest, policy.policy().digest()), SAVE_TIMEOUT_MS);
        try (VerifiedOntologyWriter.Prepared prepared = VerifiedOntologyWriter.prepare(
                captured.snapshot, captured.target)) {
            ctx.writeLock().lock();
            try {
                return ctx.access().compute(mm -> {
                    // Read-only can be switched on while the confirmation dialog is open or during a
                    // long prepare; re-check on the model thread before the disk is touched, like
                    // commit_change_set does after its own confirmation gate.
                    if (ctx.controller().isReadOnly()) {
                        return readOnlyDenied();
                    }
                    var live = ctx.revisions().current(mm, lockDigest, policy.policy().digest()).revision();
                    if (!captured.revision.equals(live)
                            || mm.getActiveOntology() != captured.activeIdentity) {
                        return Tools.json().put("saved", false)
                                .put("error_code", "revision_conflict")
                                .put("base_revision", RevisionTools.revisionJson(captured.revision))
                                .put("current_revision", RevisionTools.revisionJson(live))
                                .put("path", captured.target.toString())
                                .result();
                    }
                    // Mirror OWLModelManagerImpl.save's notification order so save-aware listeners
                    // (workspace modified indicator, VCS presenters, save hooks) treat a verified
                    // save exactly like File ▸ Save. IOListeners heard before-save in the CAPTURE
                    // hop (before the snapshot, so listener edits are in the installed artifact);
                    // here the ontology goes clean, then ONTOLOGY_SAVED and after-save fire. The
                    // IOListener legs are only reachable through the impl-side IOListenerManager
                    // interface — OWLModelManager itself exposes just add/removeIOListener — so a
                    // model manager that does not implement it simply skips them.
                    OWLOntology active = mm.getActiveOntology();
                    VerifiedOntologyWriter.Install install = prepared.install(atomic, backup);
                    OWLOntologyManager manager = mm.getOWLOntologyManager();
                    manager.setOntologyFormat(active, captured.format);
                    manager.setOntologyDocumentIRI(active, IRI.create(captured.target.toFile()));
                    mm.setClean(active);
                    mm.fireEvent(EventType.ONTOLOGY_SAVED);
                    if (mm instanceof IOListenerManager) {
                        ((IOListenerManager) mm).fireAfterSaveEvent(active.getOntologyID(),
                                captured.target.toFile().toURI());
                    }
                    ctx.revisions().invalidate();
                    var revision = ctx.revisions().current(mm, lockDigest,
                            policy.policy().digest()).revision();
                    return Tools.json()
                            .put("saved", true)
                            .put("verified", true)
                            .put("path", captured.target.toString())
                            .put("format", captured.format.getClass().getSimpleName())
                            .put("bytes", prepared.bytes)
                            .put("sha256", prepared.sha256)
                            .put("round_trip", prepared.verification.toJson())
                            .put("atomic", install.atomic())
                            .putIfNotNull("backup_path", install.backupPath() == null
                                    ? null : install.backupPath().toString())
                            .put("revision", RevisionTools.revisionJson(revision))
                            .result();
                }, SAVE_TIMEOUT_MS);
            } finally {
                ctx.writeLock().unlock();
            }
        }
    }

    private static VerifiedSaveCapture captureVerifiedSave(ToolContext ctx, OWLModelManager mm,
            String configuredPath, String lockDigest, String policyDigest) {
        OWLOntology active = mm.getActiveOntology();
        OWLOntologyManager manager = mm.getOWLOntologyManager();
        File target;
        OWLDocumentFormat current = manager.getOntologyFormat(active);
        OWLDocumentFormat format;
        if (configuredPath != null) {
            target = new File(configuredPath).getAbsoluteFile();
            format = formatForPath(configuredPath, current);
        } else {
            IRI document = manager.getOntologyDocumentIRI(active);
            if (!isFileDocument(document)) {
                throw new ToolArgException("This ontology has not been saved to a file yet; pass path.");
            }
            target = new File(document.toURI()).getAbsoluteFile();
            format = current != null ? current : formatForPath(target.toString(), null);
        }
        if (format != current && format.isPrefixOWLOntologyFormat()
                && current != null && current.isPrefixOWLOntologyFormat()) {
            format.asPrefixOWLOntologyFormat().copyPrefixesFrom(current.asPrefixOWLOntologyFormat());
        }
        // Fire before-save BEFORE the revision fingerprint and the axiom snapshot, mirroring plain
        // save (which fires it before serializing): a beforeSave IOListener that mutates the
        // ontology — e.g. stamps modification metadata — must have its edits snapshotted,
        // serialized, verified, and covered by the final revision check (a listener mutating AGAIN
        // later correctly yields revision_conflict). Consequently beforeSave can fire for a save
        // that later refuses (revision conflict / read-only flip / verify failure) — the same
        // tolerance plain save requires of listeners when the write itself throws.
        if (mm instanceof IOListenerManager) {
            ((IOListenerManager) mm).fireBeforeSaveEvent(active.getOntologyID(), target.toURI());
        }
        var revision = ctx.revisions().current(mm, lockDigest, policyDigest).revision();
        return new VerifiedSaveCapture(active, target.toPath(), format,
                VerifiedOntologyWriter.snapshot(active, format), revision);
    }

    private record VerifiedSaveCapture(OWLOntology activeIdentity, java.nio.file.Path target,
            OWLDocumentFormat format, VerifiedOntologyWriter.Snapshot snapshot,
            io.github.hakjuoh.protege_mcp.contracts.ModelRevision revision) { }

    /** How many of a transaction's changes {@link #peekUndo} renders. */
    private static final int UNDO_PEEK_SAMPLE_LIMIT = 20;

    /**
     * Read-only view of the next-undo transaction: Protégé's {@code HistoryManager} keeps the undo
     * stack as forward change lists ({@code getLoggedChanges()}, oldest first), so the LAST entry
     * is what {@code undo()} would revert — adds get removed, removes re-added. The redo stack has
     * no public accessor, so redo stays a boolean.
     */
    static CallToolResult peekUndo(OWLModelManager mm) {
        HistoryManager hm = mm.getHistoryManager();
        List<List<OWLOntologyChange>> logged = hm.getLoggedChanges();
        Tools.Json json = Tools.json()
                .put("peek", true)
                .put("can_undo", hm.canUndo())
                .put("can_redo", hm.canRedo())
                .put("undo_depth", logged.size());
        if (logged.isEmpty()) {
            return json.put("note", "The undo stack is empty — nothing to undo.").result();
        }
        List<OWLOntologyChange> next = logged.get(logged.size() - 1);
        Map<String, Object> nextUndo = new LinkedHashMap<>();
        nextUndo.put("changes", next.size());
        List<Map<String, Object>> sample = new ArrayList<>();
        int other = 0;
        for (OWLOntologyChange c : next) {
            if (!c.isAxiomChange()) {
                other++;   // import / ontology-annotation / ontology-id changes — counted, not rendered
                continue;
            }
            if (sample.size() >= UNDO_PEEK_SAMPLE_LIMIT) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("op", c.isAddAxiom() ? "add" : "remove");
            row.put("axiom", Tools.axiomJson(mm, c.getAxiom()));
            sample.add(row);
        }
        nextUndo.put("sample", sample);
        if (other > 0) {
            nextUndo.put("non_axiom_changes", other);
        }
        return json.put("next_undo", nextUndo)
                .put("note", "Nothing was undone. 'next_undo' is the transaction undo_change would "
                        + "revert — each listed 'add' would be removed and each 'remove' re-added.")
                .result();
    }

    /**
     * Save every dirty (modified since its last save) ontology to its existing document. An
     * ontology without a file document (never saved, or loaded straight from the web) has nowhere
     * safe to be written implicitly, so it is reported under 'skipped' instead — make it active
     * and use save_ontology with 'path'. One failed save does not abort the rest.
     */
    private static CallToolResult saveAllDirty(OWLModelManager mm) {
        List<OWLOntology> dirty = new ArrayList<>(mm.getDirtyOntologies());
        dirty.sort(Comparator.comparing(ReadTools::ontologyLabel));
        OWLOntologyManager om = mm.getOWLOntologyManager();
        List<Map<String, Object>> saved = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (OWLOntology o : dirty) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ontology", ReadTools.ontologyLabel(o));
            IRI doc = om.getOntologyDocumentIRI(o);
            if (!isFileDocument(doc)) {
                row.put("reason", "no file document to save to (current document: " + doc
                        + ") — set it active and use save_ontology with 'path'");
                skipped.add(row);
                continue;
            }
            try {
                mm.save(o);
                row.put("path", new File(doc.toURI()).toString());
                saved.add(row);
            } catch (OWLOntologyStorageException | RuntimeException e) {
                // RuntimeException too: a storer's unchecked IO wrapper must not abort the rest
                row.put("reason", "save failed: " + e.getMessage());
                skipped.add(row);
            }
        }
        return Tools.json()
                .put("saved", saved)
                .put("skipped", skipped)
                .put("message", dirty.isEmpty()
                        ? "Nothing to save — no ontology has unsaved changes."
                        : saved.size() + " of " + dirty.size() + " modified "
                                + (dirty.size() == 1 ? "ontology" : "ontologies") + " saved.")
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

    /**
     * Pick a serialization format from the file extension. A path with no extension keeps the
     * ontology's current format (or RDF/XML when there is none, e.g. a fresh module); an extension
     * we do not recognize is an error rather than a silent fallback — writing pets.obo as RDF/XML
     * because .obo was unmapped surprises the caller far more than a hard stop.
     */
    static OWLDocumentFormat formatForPath(String path, OWLDocumentFormat current) {
        String p = path.toLowerCase(Locale.ROOT);
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
        if (p.endsWith(".obo")) {
            return new OBODocumentFormat();
        }
        if (p.endsWith(".owl") || p.endsWith(".rdf") || p.endsWith(".xml")) {
            return new RDFXMLDocumentFormat();
        }
        String ext = extension(p);
        if (ext != null) {
            throw new ToolArgException("Unrecognized ontology file extension '." + ext + "'. "
                    + "Supported: .ttl/.turtle (Turtle), .owl/.rdf/.xml (RDF/XML), .owx (OWL/XML), "
                    + ".omn (Manchester), .ofn/.fss (Functional), .obo (OBO). Use one of these, or "
                    + "a path without an extension to keep the current format.");
        }
        return current != null ? current : new RDFXMLDocumentFormat();
    }

    /** The extension of the path's file name, or null if there is none (dotfiles do not count). */
    private static String extension(String lowerPath) {
        int slash = Math.max(lowerPath.lastIndexOf('/'), lowerPath.lastIndexOf('\\'));
        String name = lowerPath.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : null;
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
        String iri = Tools.optString(a, "iri");
        String namespace = Tools.optString(a, "namespace");
        String name = Tools.optString(a, "name");
        if (name == null) {
            // 'name' is optional when a full 'iri' pins the entity: derive its local part as the
            // default label so create_* need not repeat the fragment. A 'namespace' still needs a
            // 'name' (it supplies only the local part), and with neither we cannot mint an IRI.
            if (iri != null) {
                name = localName(iri);
            } else {
                throw new ToolArgException("Provide 'name' (the IRI local part and default label), "
                        + "or a full 'iri'.");
            }
        }
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

    /**
     * The local part of an IRI, for use as a default name/label: the fragment after '#', else the
     * segment after the last '/', else (for an opaque IRI such as {@code urn:example:Dog}) the part
     * after the last ':'. A trailing separator is dropped first so a namespace IRI
     * ({@code http://ex/vocab#}) still yields its last segment rather than the whole IRI; falls back
     * to the full IRI only when nothing else remains.
     */
    static String localName(String iri) {
        String s = iri;
        while (s.endsWith("#") || s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int cut = Math.max(s.lastIndexOf('#'), s.lastIndexOf('/'));
        if (cut < 0) {
            cut = s.lastIndexOf(':');
        }
        String local = (cut >= 0 && cut < s.length() - 1) ? s.substring(cut + 1) : s;
        return local.isEmpty() ? iri : local;
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
