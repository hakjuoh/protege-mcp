package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.protege.editor.owl.model.OWLModelManager;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.server.McpSyncServerExchange;

/**
 * The competency-question suite (F3): a re-runnable requirements suite over the active ontology. CQs pair
 * an executable SPARQL query with an expected result (the pass condition); {@code run} re-checks them all
 * against one shared snapshot, so a curation edit that quietly breaks a requirement is caught the way a
 * unit test catches a regression.
 *
 * <p>CQs may already be stored under different conventions and there is no single standard format, so
 * {@code list} detects the convention(s), {@code add}/{@code remove} operate in a chosen one (see
 * {@link CqStores} for the selection precedence), and {@code run} is convention-agnostic. Every write is
 * gated by {@code checkWriteAllowed}; file conventions write next to the ontology document, and the
 * {@code ontology-annotations} fallback writes inside the ontology (one undoable change).
 */
public final class CompetencyQuestionTools {

    private CompetencyQuestionTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("add_competency_question",
                (ex, req) -> addCq(ctx, Tools.args(req), lazyRules(ctx, ex)));

        tools.tool("list_competency_questions",
                (ex, req) -> {
                    authorizeSidecars(ctx, lazyRules(ctx, ex), false);
                    return ctx.access().compute(CompetencyQuestionTools::listCq);
                });
        tools.tool("remove_competency_question",
                (ex, req) -> removeCq(ctx, Tools.args(req), lazyRules(ctx, ex)));

        tools.tool("run_competency_questions",
                (ex, req) -> {
                    authorizeSidecars(ctx, lazyRules(ctx, ex), false);
                    return runCq(ctx, Tools.args(req));
                });
    }

    // ================================================================== add

    private static CallToolResult addCq(ToolContext ctx, Map<String, Object> a,
            Supplier<DirectAccessPolicy.Rules> rules) {
        String query = Tools.reqString(a, "query");
        String convention = Tools.optString(a, "convention");
        // Compute #1 (read-only): resolve which store + path so the consent prompt can name it.
        String[] resolved = ctx.access().compute(mm -> {
            CqContext c = CqContext.of(mm);
            CqStore store = CqStores.selectForWrite(c, convention);
            return new String[] {store.conventionId(), describeTarget(store, c)};
        });
        String conv = resolved[0];
        String target = resolved[1];
        authorizeConventionTarget(ctx, rules, conv, true);
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx,
                "add competency question to " + target + " (" + conv + ")");
        if (denied != null) {
            return denied;
        }
        // Compute #2: build + write, using the SAME store resolved above.
        return ctx.access().compute(mm -> {
            CqContext c = CqContext.of(mm);
            CqStore store = CqStores.byId(conv);
            CompetencyQuestion cq = build(a, query, store, c);
            CompetencyQuestion.validate(cq);
            store.upsert(c, cq);
            return Tools.json()
                    .put("added", cq.toReportJson())
                    .put("convention", conv)
                    .put("target", describeTarget(store, c))
                    .result();
        });
    }

    private static CompetencyQuestion build(Map<String, Object> a, String query, CqStore store,
            CqContext c) {
        CompetencyQuestion cq = new CompetencyQuestion();
        cq.query = query;
        cq.text = Tools.optString(a, "text");
        cq.type = Tools.optString(a, "type");
        String lang = Tools.optString(a, "query_lang");
        cq.queryLang = lang == null ? "sparql" : lang;
        cq.includeInferred = Tools.optBool(a, "include_inferred", true);
        cq.expected = Expectation.fromObject(a.get("expected"));
        cq.tags = Tools.stringList(a, "tags");
        cq.id = Tools.optString(a, "id");
        if (cq.id == null) {
            cq.id = nextId(store, c);
        }
        cq.convention = store.conventionId();
        return cq;
    }

    /** Mint "CQ-N" using the next free numeric suffix among the store's existing CQ ids. */
    private static String nextId(CqStore store, CqContext c) {
        int max = 0;
        for (CompetencyQuestion cq : store.load(c).ok) {
            if (cq.id != null && cq.id.startsWith("CQ-")) {
                try {
                    max = Math.max(max, Integer.parseInt(cq.id.substring(3)));
                } catch (NumberFormatException ignored) {
                    // non-numeric suffix — ignore for auto-numbering
                }
            }
        }
        return "CQ-" + (max + 1);
    }

    // ================================================================== list

    private static CallToolResult listCq(OWLModelManager mm) {
        CqContext c = CqContext.of(mm);
        List<Map<String, Object>> questions = new ArrayList<>();
        List<String> conventionsFound = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (CqStore store : CqStores.all()) {
            if (!store.detect(c)) {
                continue;
            }
            conventionsFound.add(store.conventionId());
            CqStore.LoadResult lr = store.load(c);
            for (CompetencyQuestion cq : lr.ok) {
                questions.add(cq.toReportJson());
            }
            for (CqStore.LoadWarning w : lr.skipped) {
                skipped.add(w.toJson());
            }
        }
        return Tools.json()
                .put("count", questions.size())
                .put("conventions_found", conventionsFound)
                .put("competency_questions", questions)
                .put("skipped", skipped)
                .result();
    }

    // ================================================================== remove

    private static CallToolResult removeCq(ToolContext ctx, Map<String, Object> a,
            Supplier<DirectAccessPolicy.Rules> rules) {
        String id = Tools.reqString(a, "id");
        String convention = Tools.optString(a, "convention");
        authorizeSidecars(ctx, rules, false);
        // Compute #1: find which store(s) hold this id.
        List<String> holders = ctx.access().compute(mm -> {
            CqContext c = CqContext.of(mm);
            List<String> out = new ArrayList<>();
            for (CqStore store : CqStores.all()) {
                if (convention != null && !store.conventionId().equalsIgnoreCase(convention)) {
                    continue;
                }
                if (!store.detect(c)) {
                    continue;
                }
                for (CompetencyQuestion cq : store.load(c).ok) {
                    if (id.equals(cq.id)) {
                        out.add(store.conventionId());
                        break;
                    }
                }
            }
            return out;
        });
        if (holders.isEmpty()) {
            return Tools.error("No competency question '" + id + "' found"
                    + (convention == null ? "." : " in convention " + convention + "."));
        }
        if (holders.size() > 1) {
            return Tools.error("Competency question '" + id + "' exists in several conventions ("
                    + String.join(", ", holders) + "). Pass convention= to choose which to remove from.");
        }
        String conv = holders.get(0);
        authorizeConventionTarget(ctx, rules, conv, true);
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx,
                "remove competency question '" + id + "' from " + conv);
        if (denied != null) {
            return denied;
        }
        return ctx.access().compute(mm -> {
            CqContext c = CqContext.of(mm);
            boolean removed = CqStores.byId(conv).remove(c, id);
            return Tools.json()
                    .put("removed", removed)
                    .put("id", id)
                    .put("convention", conv)
                    .result();
        });
    }

    /** Backward-compatible method-level test seam. */
    @SuppressWarnings("unused")
    private static CallToolResult removeCq(ToolContext ctx, Map<String, Object> a) {
        return removeCq(ctx, a, lazyRules(ctx, null));
    }

    // ================================================================== run

    private static CallToolResult runCq(ToolContext ctx, Map<String, Object> a) {
        List<String> ids = Tools.stringList(a, "ids");
        String convention = Tools.optString(a, "convention");
        int limit = Tools.optInt(a, "limit", 1000);
        if (limit <= 0) {
            limit = 1000;
        }
        int timeout = Tools.optInt(a, "timeout_ms", 120_000);
        if (timeout <= 0) {
            timeout = 120_000;
        }
        String failOn = CqRunner.normalizeFailOn(Tools.optString(a, "fail_on"));
        final int rowLimit = limit;
        // Compute #1: load matching CQs + build one shared snapshot at a single instant.
        RunLoad load = ctx.access().compute(mm -> loadForRun(mm, ids, convention));
        if (load.cqs.isEmpty()) {
            return Tools.json()
                    .put("total", 0)
                    .put("passed", 0)
                    .put("failed", 0)
                    .put("gate", "pass")
                    .put("questions", new ArrayList<>())
                    .put("skipped", load.skipped)
                    .put("note", "No competency questions matched — add some with add_competency_question.")
                    .result();
        }
        // Off the EDT: evaluate every CQ against the pre-built snapshot.
        Map<String, Object> result = CqRunner.run(load.snapshot, load.cqs, rowLimit, timeout, failOn);
        result.put("skipped", load.skipped);
        return Tools.ok(result);
    }

    private static RunLoad loadForRun(OWLModelManager mm, List<String> ids, String convention) {
        CqContext c = CqContext.of(mm);
        List<CompetencyQuestion> cqs = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (CqStore store : CqStores.all()) {
            if (convention != null && !store.conventionId().equalsIgnoreCase(convention)) {
                continue;
            }
            if (!store.detect(c)) {
                continue;
            }
            CqStore.LoadResult lr = store.load(c);
            for (CompetencyQuestion cq : lr.ok) {
                if (ids.isEmpty() || ids.contains(cq.id)) {
                    cqs.add(cq);
                }
            }
            for (CqStore.LoadWarning w : lr.skipped) {
                skipped.add(w.toJson());
            }
        }
        boolean needInferred = cqs.stream().anyMatch(cq -> cq.includeInferred);
        SuiteSnapshot snapshot = SuiteSnapshot.capture(mm, needInferred);
        return new RunLoad(cqs, snapshot, skipped);
    }

    // ================================================================== helpers

    private static String describeTarget(CqStore store, CqContext c) {
        switch (store.conventionId()) {
            case Cq.CONV_ROBOT:
                return c.cqsDir() == null ? "cqs/" : c.cqsDir().toString();
            case Cq.CONV_MANIFEST:
                return c.manifestFile() == null ? "sidecar manifest" : c.manifestFile().toString();
            case Cq.CONV_ANNOTATIONS:
            default:
                return "the active ontology (annotations)";
        }
    }

    private static void authorizeSidecars(ToolContext ctx,
            Supplier<DirectAccessPolicy.Rules> rules,
            boolean write) {
        List<java.nio.file.Path> paths = ctx.access().compute(mm -> {
            CqContext c = CqContext.of(mm);
            List<java.nio.file.Path> out = new ArrayList<>();
            if (c.cqsDir() != null) out.add(c.cqsDir().toPath());
            if (c.manifestFile() != null) out.add(c.manifestFile().toPath());
            return out;
        });
        for (java.nio.file.Path path : paths) {
            rules.get().implicitPath(path, write);
        }
    }

    private static void authorizeConventionTarget(ToolContext ctx,
            Supplier<DirectAccessPolicy.Rules> rules,
            String convention, boolean write) {
        java.nio.file.Path target = ctx.access().compute(mm -> {
            CqContext c = CqContext.of(mm);
            if (Cq.CONV_ROBOT.equals(convention) && c.cqsDir() != null) {
                return c.cqsDir().toPath();
            }
            if (Cq.CONV_MANIFEST.equals(convention) && c.manifestFile() != null) {
                return c.manifestFile().toPath();
            }
            return null;
        });
        if (target != null) rules.get().implicitPath(target, write);
    }

    /** Resolve filesystem policy at most once, and only if a file-backed CQ store is touched. */
    private static Supplier<DirectAccessPolicy.Rules> lazyRules(ToolContext ctx,
            McpSyncServerExchange exchange) {
        return new Supplier<>() {
            private DirectAccessPolicy.Rules value;

            @Override
            public DirectAccessPolicy.Rules get() {
                if (value == null) value = DirectAccessPolicy.resolve(ctx, exchange);
                return value;
            }
        };
    }

    /** The loaded suite for one run: matching CQs, the shared snapshot, and load-skip warnings. */
    private static final class RunLoad {
        final List<CompetencyQuestion> cqs;
        final SuiteSnapshot snapshot;
        final List<Map<String, Object>> skipped;

        RunLoad(List<CompetencyQuestion> cqs, SuiteSnapshot snapshot, List<Map<String, Object>> skipped) {
            this.cqs = cqs;
            this.snapshot = snapshot;
            this.skipped = skipped;
        }
    }
}
