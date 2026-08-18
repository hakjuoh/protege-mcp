package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.history.HistoryManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
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
import org.semanticweb.owlapi.model.RemoveAxiom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Mutating tools. Every change is applied via {@link OWLModelManager#applyChanges} so it flows
 * through Protégé's {@code HistoryManager} (the shared, GUI-visible undo stack) exactly like a
 * manual edit. Writes are gated by a live read-only switch and an optional write-confirmation
 * dialog; {@code applyChanges} may drop/merge changes (ChangeListMinimizer), so each tool
 * re-queries the resulting state to report what actually took effect.
 */
public final class WriteTools {

    private WriteTools() {}

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool(
                "create_class",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    return write(
                            ctx,
                            "create_class " + summaryName(a),
                            mm -> {
                                OWLDataFactory df = mm.getOWLDataFactory();
                                OWLOntology ont = mm.getActiveOntology();
                                List<OWLOntologyChange> changes = new ArrayList<>();
                                OWLClass cls = (OWLClass) createEntity(mm, "class", a, changes);
                                String parent = Tools.optString(a, "parent");
                                if (parent != null) {
                                    OWLClassExpression sup =
                                            Tools.resolveClassExpression(mm, parent);
                                    changes.add(
                                            new AddAxiom(ont, df.getOWLSubClassOfAxiom(cls, sup)));
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
        tools.tool(
                "create_entity",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String type = Tools.reqString(a, "entity_type");
                    return write(
                            ctx,
                            "create_entity " + type + " " + summaryName(a),
                            mm -> {
                                List<OWLOntologyChange> changes = new ArrayList<>();
                                OWLEntity e = createEntity(mm, type, a, changes);
                                mm.applyChanges(changes);
                                return Tools.json()
                                        .put("created", Tools.entityJson(mm, e))
                                        .result();
                            });
                });
        tools.tool(
                "add_subclass_of",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String child = Tools.reqString(a, "child");
                    String parent = Tools.reqString(a, "parent");
                    boolean strict = Tools.optBool(a, "strict", false);
                    return write(
                            ctx,
                            child + " ⊑ " + parent,
                            mm -> {
                                OWLDataFactory df = mm.getOWLDataFactory();
                                OWLOntology ont = mm.getActiveOntology();
                                OWLAxiom ax =
                                        df.getOWLSubClassOfAxiom(
                                                Tools.resolveClassExpression(mm, child),
                                                Tools.resolveClassExpression(mm, parent));
                                return applyAxiom(mm, ont, ax, strict);
                            });
                });
        tools.tool(
                "add_annotation",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    boolean strict = Tools.optBool(a, "strict", false);
                    return write(
                            ctx,
                            "annotate " + entityRef,
                            mm -> {
                                OWLDataFactory df = mm.getOWLDataFactory();
                                OWLOntology ont = mm.getActiveOntology();
                                IRI subject = Tools.annotationSubject(mm, entityRef);
                                OWLAnnotationProperty prop =
                                        Tools.annotationProperty(
                                                mm, Tools.optString(a, "property"));
                                OWLAxiom ax =
                                        df.getOWLAnnotationAssertionAxiom(
                                                prop,
                                                subject,
                                                Tools.annotationValue(mm, a),
                                                Tools.annotationSet(mm, a, "annotations"));
                                return applyAxiom(mm, ont, ax, strict);
                            });
                });
        tools.tool(
                "add_axiom",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean strict = Tools.optBool(a, "strict", false);
                    return write(
                            ctx,
                            "add_axiom " + Tools.optString(a, "axiom_type"),
                            mm -> {
                                OWLOntology ont = mm.getActiveOntology();
                                OWLAxiom ax = Axioms.build(mm, a);
                                return applyAxiom(mm, ont, ax, strict);
                            });
                });
        tools.tool(
                "remove_axiom",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    return write(
                            ctx,
                            "remove_axiom " + Tools.optString(a, "axiom_type"),
                            mm -> {
                                OWLOntology ont = mm.getActiveOntology();
                                OWLAxiom ax = Axioms.build(mm, a);
                                if (!ont.containsAxiom(ax)) {
                                    return Tools.error(
                                            "Axiom not present in the active ontology: "
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
        tools.tool(
                "apply_changes",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> operations = Tools.objList(a, "operations");
                    if (operations.isEmpty()) {
                        return Tools.error(
                                "Provide at least one operation in 'operations' "
                                        + "(each: axiom_type + operands, optional op=add|remove).");
                    }
                    boolean strict = Tools.optBool(a, "strict", false);
                    String verify = ApplyVerify.normalizeMode(Tools.optString(a, "verify"));
                    String summary =
                            "apply "
                                    + operations.size()
                                    + " change(s)"
                                    + (ApplyVerify.MODE_NONE.equals(verify)
                                            ? ""
                                            : " (verify=" + verify + ")");
                    if (ApplyVerify.MODE_NONE.equals(verify)) {
                        return write(ctx, summary, mm -> applyBatch(mm, operations, strict));
                    }
                    Integer requestedTimeout = ChangeSetTools.requestedTimeout(a);
                    int timeout = requestedTimeout == null ? 60_000 : requestedTimeout;
                    DirectAccessPolicy.requireCapability(ex, DirectAccessPolicy.PROJECT_READ);
                    return ChangeSetApplyVerify.apply(
                            ctx, verify, timeout, summary, operations, strict);
                });
        tools.tool(
                "set_label",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    String value = Tools.reqString(a, "value");
                    String lang = Tools.optString(a, "lang");
                    return write(
                            ctx,
                            "set label of " + entityRef,
                            mm -> {
                                OWLDataFactory df = mm.getOWLDataFactory();
                                OWLOntology ont = mm.getActiveOntology();
                                IRI subject = Tools.annotationSubject(mm, entityRef);
                                List<OWLOntologyChange> changes = new ArrayList<>();
                                int removed = 0;
                                for (OWLAnnotationAssertionAxiom ax :
                                        ont.getAnnotationAssertionAxioms(subject)) {
                                    if (!ax.getProperty().isLabel()
                                            || !ax.getValue().asLiteral().isPresent()) {
                                        continue;
                                    }
                                    OWLLiteral lit = ax.getValue().asLiteral().get();
                                    boolean sameLang =
                                            lang == null
                                                    ? !lit.hasLang()
                                                    : lang.equalsIgnoreCase(lit.getLang());
                                    if (sameLang) {
                                        changes.add(new RemoveAxiom(ont, ax));
                                        removed++;
                                    }
                                }
                                OWLLiteral newLit =
                                        lang != null
                                                ? df.getOWLLiteral(value, lang)
                                                : df.getOWLLiteral(value);
                                changes.add(
                                        new AddAxiom(
                                                ont,
                                                df.getOWLAnnotationAssertionAxiom(
                                                        df.getRDFSLabel(), subject, newLit)));
                                mm.applyChanges(changes);
                                return Tools.json()
                                        .put("entity", subject.toString())
                                        .put("label", value)
                                        .putIfNotNull("lang", lang)
                                        .put("removed_previous", removed)
                                        .result();
                            });
                });
        tools.tool(
                "undo_change",
                (ex, req) -> {
                    if (Tools.optBool(Tools.args(req), "peek", false)) {
                        return ctx.access().compute(WriteTools::peekUndo);
                    }
                    return write(
                            ctx,
                            "undo last change",
                            mm -> {
                                HistoryManager hm = mm.getHistoryManager();
                                if (!hm.canUndo()) {
                                    return Tools.error("Nothing to undo.");
                                }
                                long before = totalAxioms(mm);
                                hm.undo();
                                long after = totalAxioms(mm);
                                boolean dirty =
                                        mm.getDirtyOntologies().contains(mm.getActiveOntology());
                                return Tools.json()
                                        .put("undone", true)
                                        .put("message", "Undid the last change.")
                                        .put("axioms_before", before)
                                        .put("axioms_after", after)
                                        .put("net_axiom_change", after - before)
                                        .put("undo_depth", hm.getLoggedChanges().size())
                                        .put("can_undo", hm.canUndo())
                                        .put("can_redo", hm.canRedo())
                                        .put("dirty", dirty)
                                        .put(
                                                "dirty_note",
                                                "Protégé keeps the ontology marked dirty after Undo"
                                                    + " until the next save, even when content"
                                                    + " returns to its loaded fingerprint.")
                                        .result();
                            });
                });
        tools.tool(
                "redo_change",
                (ex, req) ->
                        write(
                                ctx,
                                "redo last change",
                                mm -> {
                                    HistoryManager hm = mm.getHistoryManager();
                                    if (!hm.canRedo()) {
                                        return Tools.error("Nothing to redo.");
                                    }
                                    long before = totalAxioms(mm);
                                    hm.redo();
                                    long after = totalAxioms(mm);
                                    return Tools.json()
                                            .put("redone", true)
                                            .put("message", "Redid the last undone change.")
                                            .put("axioms_before", before)
                                            .put("axioms_after", after)
                                            .put("net_axiom_change", after - before)
                                            .put("undo_depth", hm.getLoggedChanges().size())
                                            .put("can_undo", hm.canUndo())
                                            .put("can_redo", hm.canRedo())
                                            .result();
                                }));

        tools.tool(
                "save_ontology",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String configuredPath = Tools.optString(a, "path");
                    boolean all = Tools.optBool(a, "all", false);
                    boolean verifyRoundTrip = Tools.optBool(a, "verify_round_trip", false);
                    boolean atomic = Tools.optBool(a, "atomic", false);
                    boolean backup = Tools.optBool(a, "backup", false);
                    boolean policyBootstrap = Tools.optBool(a, "policy_bootstrap", false);
                    String onLossy = normalizeOnLossy(Tools.optString(a, "on_lossy"));
                    if (onLossy == null) {
                        return Tools.error("'on_lossy' must be 'warn' (default) or 'fail'.");
                    }
                    if (all && configuredPath != null) {
                        return Tools.error(
                                "'all' saves every dirty ontology to its own existing document and"
                                    + " cannot be combined with 'path' (a save-as targets only the"
                                    + " active ontology).");
                    }
                    if (policyBootstrap && configuredPath == null) {
                        return Tools.error(
                                "'policy_bootstrap' requires an explicit 'path': it exists to"
                                    + " create a policy-referenced artifact (e.g. the"
                                    + " root_artifact) inside the invalid policy's project root.");
                    }
                    DirectAccessPolicy.Rules accessRules;
                    ProjectPolicy invalidDiscovered = null;
                    try {
                        accessRules = DirectAccessPolicy.resolve(ctx, ex);
                    } catch (ToolArgException refusal) {
                        // Older DirectAccessPolicy revisions reject a loaded-but-invalid discovered
                        // policy at resolve() time. Only the explicit bootstrap request shape may
                        // continue, and only after the invalid-policy cause is independently
                        // re-verified — any other refusal is never downgraded.
                        if (!policyBootstrap) throw refusal;
                        accessRules = null;
                        invalidDiscovered =
                                DirectAccessPolicy.verifiedInvalidDiscovery(ctx, ex, refusal);
                    }
                    final String path;
                    Map<String, Object> bootstrapNote = null;
                    if (configuredPath != null) {
                        ProjectPolicy discovered =
                                accessRules != null ? accessRules.policy() : invalidDiscovered;
                        boolean invalidState =
                                discovered != null && discovered.loaded() && !discovered.valid();
                        if (policyBootstrap && invalidState) {
                            path =
                                    DirectAccessPolicy.bootstrapExplicitPath(
                                                    discovered, ex, configuredPath, true, "save")
                                            .toString();
                            bootstrapNote = bootstrapNote(discovered);
                        } else {
                            try {
                                path = accessRules.writePath(configuredPath).toString();
                            } catch (ToolArgException refusal) {
                                // Hint only on the invalid-policy refusal itself — never on a
                                // capability or containment refusal, which the bootstrap would not
                                // lift either.
                                if (!policyBootstrap
                                        && invalidState
                                        && refusal.getMessage() != null
                                        && refusal.getMessage()
                                                .startsWith(
                                                        "The effective project policy is"
                                                            + " invalid")) {
                                    throw new ToolArgException(
                                            refusal.getMessage()
                                                    + " To create a policy-referenced artifact"
                                                    + " (e.g. the root_artifact) at an explicit"
                                                    + " path inside the project root while the"
                                                    + " policy is still invalid, pass"
                                                    + " policy_bootstrap=true.");
                                }
                                throw refusal;
                            }
                            if (policyBootstrap) {
                                bootstrapNote = new LinkedHashMap<>();
                                bootstrapNote.put("used", false);
                                bootstrapNote.put(
                                        "reason",
                                        discovered != null && discovered.loaded()
                                                ? "the effective project policy is valid; normal "
                                                        + "authorization applied"
                                                : "no project policy is loaded; normal"
                                                      + " authorization applied");
                            }
                        }
                    } else {
                        // Argument-less saves and all=true write back to documents already open in
                        // Protégé: derived targets, not caller-selected paths. Authorize each one
                        // before confirmation or serialization.
                        List<String> targets = ctx.access().compute(mm -> saveTargets(mm, all));
                        for (String target : targets) {
                            accessRules.implicitPath(java.nio.file.Path.of(target), true);
                        }
                        path = null;
                    }
                    if (all) {
                        if (verifyRoundTrip || atomic || backup) {
                            return Tools.error(
                                    "Verified/atomic/backup save currently targets one active "
                                            + "ontology; it cannot be combined with all=true.");
                        }
                        return write(
                                ctx,
                                "save all modified ontologies to disk",
                                WriteTools::saveAllDirty,
                                SAVE_TIMEOUT_MS);
                    }
                    String summary =
                            path != null
                                    ? "save the active ontology to " + path
                                    : "save the active ontology to disk";
                    if (verifyRoundTrip || atomic || backup) {
                        return verifiedSave(
                                ctx, summary, path, atomic, backup, onLossy, bootstrapNote);
                    }
                    final String lossyMode = onLossy;
                    final Map<String, Object> bootstrapInfo = bootstrapNote;
                    return write(
                            ctx,
                            summary,
                            mm -> saveOntology(mm, path, lossyMode, bootstrapInfo),
                            SAVE_TIMEOUT_MS);
                });
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Serializing a big ontology — worse, EVERY dirty ontology under all=true — can legitimately
     * outlive the default EDT wait, and a "timed out" report while the files keep being written is
     * a false failure. Same rationale (and value) as the document tools' merge/load bound.
     */
    private static final long SAVE_TIMEOUT_MS = 120_000L;

    /**
     * {@code on_lossy} values: warn (default, additive warning) or fail (refuse before writing).
     */
    static final String ON_LOSSY_WARN = OntologySaveService.ON_LOSSY_WARN;

    static final String ON_LOSSY_FAIL = OntologySaveService.ON_LOSSY_FAIL;

    static String normalizeOnLossy(String raw) {
        return OntologySaveService.normalizeOnLossy(raw);
    }

    private static List<String> saveTargets(OWLModelManager mm, boolean all) {
        return OntologySaveService.saveTargets(mm, all);
    }

    /** Apply the read-only + confirmation gates, then run {@code body} on the EDT. */
    static CallToolResult write(
            ToolContext ctx, String summary, Function<OWLModelManager, CallToolResult> body) {
        CallToolResult denied = checkWriteAllowed(ctx, summary);
        if (denied != null) {
            return denied;
        }
        return ctx.access().compute(body);
    }

    /** {@link #write(ToolContext, String, Function)} with an explicit EDT wait bound. */
    static CallToolResult write(
            ToolContext ctx,
            String summary,
            Function<OWLModelManager, CallToolResult> body,
            long boundMillis) {
        CallToolResult denied = checkWriteAllowed(ctx, summary);
        if (denied != null) {
            return denied;
        }
        return ctx.access().compute(body, boundMillis);
    }

    static CallToolResult checkWriteAllowed(ToolContext ctx, String summary) {
        return WriteAccessPolicy.check(ctx, summary);
    }

    /** The uniform read-only refusal every write gate returns. */
    static CallToolResult readOnlyDenied() {
        return WriteAccessPolicy.readOnlyDenied();
    }

    static void declareMinted(
            OWLDataFactory df,
            OWLOntology ont,
            Set<OWLEntity> minted,
            List<OWLOntologyChange> out) {
        EntityWriteService.declareMinted(df, ont, minted, out);
    }

    static String summaryName(Map<String, Object> arguments) {
        return EntityWriteService.summaryName(arguments);
    }

    static void declareUsedAnnotationProperties(
            OWLDataFactory df, OWLOntology ont, List<OWLOntologyChange> changes) {
        EntityWriteService.declareUsedAnnotationProperties(df, ont, changes);
    }

    static void declareAnnotationProperties(
            OWLDataFactory df,
            OWLOntology ont,
            Set<OWLAnnotationProperty> used,
            List<OWLOntologyChange> changes) {
        EntityWriteService.declareAnnotationProperties(df, ont, used, changes);
    }

    static void declareMintedFromChanges(
            OWLModelManager mm,
            OWLOntology ont,
            List<OWLOntologyChange> changes,
            OWLEntity exclude) {
        EntityWriteService.declareMintedFromChanges(mm, ont, changes, exclude);
    }

    private static CallToolResult applyAxiom(
            OWLModelManager mm, OWLOntology ont, OWLAxiom ax, boolean strict) {
        Set<OWLEntity> minted = PreviewTools.newEntities(ont.getImportsClosure(), ax);
        if (strict && !minted.isEmpty()) {
            return mintError(mm, minted);
        }
        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ont, ax));
        declareMinted(mm.getOWLDataFactory(), ont, minted, changes);
        declareUsedAnnotationProperties(mm.getOWLDataFactory(), ont, changes);
        mm.applyChanges(
                changes); // axiom + any declarations for side-effect entities, one undo unit
        return applied(mm, ont, ax, minted);
    }

    private static CallToolResult applied(
            OWLModelManager mm, OWLOntology ont, OWLAxiom ax, Set<OWLEntity> minted) {
        boolean present = ont.containsAxiom(ax);
        Tools.Json json =
                Tools.json().put("applied", present).put("axiom", Tools.axiomJson(mm, ax));
        if (minted != null && !minted.isEmpty()) {
            List<Map<String, Object>> ne = new ArrayList<>();
            for (OWLEntity e : minted) {
                ne.add(Tools.entityJson(mm, e));
            }
            json.put("new_entities", ne);
        }
        return json.putIfNotNull(
                        "note", present ? null : "No effect — already present or minimized away.")
                .result();
    }

    private static CallToolResult mintError(OWLModelManager mm, Set<OWLEntity> minted) {
        return Tools.error(
                "Refusing to apply (strict): the reference(s) "
                        + EntityRendering.renderMinted(mm, minted)
                        + " are not declared anywhere in the imports closure and would be created"
                        + " as new, empty entities — likely a typo'd IRI/name. Fix the reference,"
                        + " create the entity first, or set strict=false to allow minting.");
    }

    private static long totalAxioms(OWLModelManager mm) {
        return BatchWriteService.totalAxioms(mm);
    }

    private static CallToolResult applyBatch(
            OWLModelManager mm, List<Map<String, Object>> operations, boolean strict) {
        return BatchWriteService.applyBatch(mm, operations, strict);
    }

    static Map<String, Object> applyBatchData(
            OWLModelManager mm, List<Map<String, Object>> operations, boolean strict) {
        return BatchWriteService.applyBatchData(mm, operations, strict);
    }

    static Map<String, Object> simulateBatchData(
            OWLModelManager mm, List<Map<String, Object>> operations, boolean strict) {
        return BatchWriteService.simulateBatchData(mm, operations, strict);
    }

    private static Set<OWLEntity> newEntitiesAfterSimulatedAdds(
            Set<OWLOntology> closure, Set<OWLAxiom> simulatedAdds, OWLAxiom axiom) {
        return BatchWriteService.newEntitiesAfterSimulatedAdds(closure, simulatedAdds, axiom);
    }

    private static Set<OWLEntity> newEntitiesIntroducedByAxioms(
            Set<OWLOntology> closure, Set<OWLAxiom> axioms) {
        return BatchWriteService.newEntitiesIntroducedByAxioms(closure, axioms);
    }

    private static CallToolResult saveOntology(OWLModelManager mm, String path) {
        return OntologySaveService.saveOntology(mm, path);
    }

    private static Map<String, Object> bootstrapNote(ProjectPolicy discovered) {
        return OntologySaveService.bootstrapNote(discovered);
    }

    private static CallToolResult saveOntology(
            OWLModelManager mm, String path, String onLossy, Map<String, Object> bootstrapInfo) {
        return OntologySaveService.saveOntology(mm, path, onLossy, bootstrapInfo);
    }

    private static CallToolResult verifiedSave(
            ToolContext ctx,
            String summary,
            String path,
            boolean atomic,
            boolean backup,
            String onLossy,
            Map<String, Object> bootstrapInfo) {
        return OntologySaveService.verifiedSave(
                ctx, summary, path, atomic, backup, onLossy, bootstrapInfo);
    }

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
        Tools.Json json =
                Tools.json()
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
                // Import, ontology-annotation and ontology-id changes are counted, not rendered.
                other++;
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
                .put(
                        "note",
                        "Nothing was undone. 'next_undo' is the transaction undo_change would"
                            + " revert — each listed 'add' would be removed and each 'remove'"
                            + " re-added.")
                .result();
    }

    private static CallToolResult saveAllDirty(OWLModelManager mm) {
        return OntologySaveService.saveAllDirty(mm);
    }

    private static boolean isFileDocument(IRI iri) {
        return OntologySaveService.isFileDocument(iri);
    }

    static OWLDocumentFormat formatForPath(String path, OWLDocumentFormat current) {
        return OntologySaveService.formatForPath(path, current);
    }

    static OWLEntity createEntity(
            OWLModelManager mm,
            String type,
            Map<String, Object> arguments,
            List<OWLOntologyChange> changes) {
        return EntityWriteService.createEntity(mm, type, arguments, changes);
    }

    private static boolean isLabelChangeFor(OWLOntologyChange change, OWLEntity entity) {
        return EntityWriteService.isLabelChangeFor(change, entity);
    }

    private static OWLLiteral label(OWLDataFactory df, String text, String lang) {
        return EntityWriteService.label(df, text, lang);
    }

    private static String joinNamespace(String namespace, String name) {
        return EntityWriteService.joinNamespace(namespace, name);
    }

    static String localName(String iri) {
        return EntityWriteService.localName(iri);
    }

    private static OWLEntity entityAtIri(OWLDataFactory df, String type, IRI iri) {
        return EntityWriteService.entityAtIri(df, type, iri);
    }
}
