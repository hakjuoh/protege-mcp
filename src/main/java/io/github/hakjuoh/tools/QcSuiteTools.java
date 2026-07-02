package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.profiles.Profiles;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code run_qc_suite} (F5): one umbrella gate over the already-shipping QC cores — the reasoner verdict,
 * OWL 2 profile conformance, the structural {@code validate_ontology} checks, the {@code verify_ontology}
 * invariants, and the competency-question suite — evaluated against ONE point-in-time snapshot and
 * collapsed into a single {@code gate}. It composes the plain-data cores directly (the established idiom:
 * {@code validate_ontology} composes {@code reasonerVerdict}, the curation macros compose {@code
 * WriteTools} cores), never the MCP tool lambdas.
 *
 * <p>Graceful-skip is the contract: a stage whose backing data is absent — no classified reasoner, no
 * invariants supplied, no competency questions stored, SHACL not built — returns {@code ran:false} with a
 * reason, never a thrown exception. The overall gate is the worst <em>ran</em> stage versus {@code fail_on}.
 */
public final class QcSuiteTools {

    private QcSuiteTools() {
    }

    private static final String REASONER = "reasoner";
    private static final String PROFILE = "profile";
    private static final String STRUCTURAL = "structural";
    private static final String INVARIANTS = "invariants";
    private static final String CQS = "cqs";
    private static final String SHACL = "shacl";

    private static final List<String> ALL_STAGES = Arrays.asList(
            REASONER, PROFILE, STRUCTURAL, INVARIANTS, CQS, SHACL);
    private static final List<String> DEFAULT_STAGES = Arrays.asList(REASONER, PROFILE, STRUCTURAL);

    static final String PASS = "pass";
    static final String WARN = "warn";
    static final String FAIL = "fail";

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("run_qc_suite",
                "Run an aggregate quality-control gate over the active ontology and collapse it to ONE "
                        + "verdict. Composable stages (default reasoner + profile + structural): 'reasoner' "
                        + "(consistency + no unsatisfiable classes), 'profile' (OWL 2 profile conformance, "
                        + "owl_profile default DL), 'structural' (validate_ontology's modelling-quality "
                        + "checks), 'invariants' (verify_ontology-style SPARQL invariants — pass them in "
                        + "'invariants'), 'cqs' (the competency-question suite), and 'shacl' (reserved). "
                        + "Every stage runs against one shared snapshot; a stage whose backing data is "
                        + "absent (no classified reasoner, no invariants, no CQs, no SHACL) is skipped with "
                        + "a reason, never an error. The overall 'gate' fails when the worst stage reaches "
                        + "'fail_on' (none | warn | error, default error).",
                suiteSchema(),
                (ex, req) -> Tools.guard(() -> runSuite(ctx, Tools.args(req))));
    }

    // ================================================================== orchestration

    private static CallToolResult runSuite(ToolContext ctx, Map<String, Object> a) {
        Set<String> stages = normalizeStages(Tools.stringList(a, "stages"));
        String failOn = normalizeFailOn(Tools.optString(a, "fail_on"));
        String profileName = GovernanceTools.normalizeProfile(Tools.optString(a, "owl_profile"));
        int limit = Tools.optInt(a, "limit", 25);
        int timeout = Tools.optInt(a, "timeout_ms", 120_000);
        if (timeout <= 0) {
            timeout = 120_000;
        }
        final int rowLimit = Math.max(limit, 1000);   // SPARQL stages want a generous row cap
        // Invariants are validated up front (a structurally bad invariant is an argument error).
        List<Invariants.Invariant> invariants = stages.contains(INVARIANTS)
                ? Invariants.parse(Tools.objList(a, "invariants")) : Collections.emptyList();

        // Phase 1 (EDT): everything that needs the live model — reasoner/structural verdicts, the profile
        // flatten snapshot, the CQ load, and the one shared SPARQL snapshot.
        Phase1 p1 = ctx.access().compute(mm -> phase1(mm, stages, profileName, limit, invariants));

        // Phase 2 (off the EDT): the SPARQL / profile stages over the pre-built snapshots.
        List<StageResult> results = new ArrayList<>();
        if (stages.contains(REASONER)) {
            results.add(p1.reasoner);
        }
        if (stages.contains(PROFILE)) {
            results.add(profileStage(p1, profileName, limit));
        }
        if (stages.contains(STRUCTURAL)) {
            results.add(p1.structural);
        }
        if (stages.contains(INVARIANTS)) {
            results.add(invariantsStage(p1.snapshot, invariants, rowLimit, timeout));
        }
        if (stages.contains(CQS)) {
            results.add(cqsStage(p1.snapshot, p1.cqs, rowLimit, timeout));
        }
        if (stages.contains(SHACL)) {
            results.add(StageResult.skipped(SHACL, "SHACL validation is not available in this build "
                    + "(deferred to a later release)."));
        }
        return aggregate(results, failOn);
    }

    /** Everything phase 1 gathers on the model thread. */
    private static final class Phase1 {
        StageResult reasoner;
        StageResult structural;
        String profileName;
        OWLOntology profileSnapshot;
        List<CompetencyQuestion> cqs;
        SuiteSnapshot snapshot;
    }

    private static Phase1 phase1(OWLModelManager mm, Set<String> stages, String profileName, int limit,
            List<Invariants.Invariant> invariants) {
        Phase1 p1 = new Phase1();
        if (stages.contains(REASONER)) {
            p1.reasoner = reasonerStage(mm, limit);
        }
        if (stages.contains(STRUCTURAL)) {
            p1.structural = structuralStage(mm);
        }
        if (stages.contains(PROFILE)) {
            Profiles profile = GovernanceTools.profileFor(profileName);
            p1.profileName = profileName;
            p1.profileSnapshot = profile == null ? null
                    : GovernanceTools.flatten(mm.getActiveOntology().getImportsClosure());
        }
        if (stages.contains(CQS)) {
            p1.cqs = loadCqs(mm);
        }
        boolean needSnap = stages.contains(INVARIANTS) || stages.contains(CQS);
        if (needSnap) {
            boolean needInferred = (p1.cqs != null && p1.cqs.stream().anyMatch(cq -> cq.includeInferred))
                    || invariants.stream().anyMatch(i -> i.includeInferred);
            p1.snapshot = SuiteSnapshot.capture(mm, needInferred);
        }
        return p1;
    }

    // ================================================================== stages

    /** Reasoner consistency + no unsatisfiable classes (graceful when there are no current results). */
    static StageResult reasonerStage(OWLModelManager mm, int limit) {
        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, limit);
        if (!Boolean.TRUE.equals(v.get("results_available"))) {
            return StageResult.skipped(REASONER, "no current reasoner results — run run_reasoner first "
                    + "(status: " + v.get("status") + ").");
        }
        Object consistent = v.get("consistent");
        int unsat = v.get("unsatisfiable_count") instanceof Number
                ? ((Number) v.get("unsatisfiable_count")).intValue() : 0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", v.get("status"));
        summary.put("consistent", consistent);
        summary.put("unsatisfiable_count", unsat);
        String verdict = (Boolean.TRUE.equals(consistent) && unsat == 0) ? PASS : FAIL;
        return new StageResult(REASONER, true, verdict, summary, null);
    }

    static StageResult structuralStage(OWLModelManager mm) {
        Set<OWLOntology> scope = Collections.singleton(mm.getActiveOntology());
        List<ValidationTools.Finding> findings = ValidationTools.analyze(scope);
        int total = 0;
        int warnings = 0;   // only warning-severity smells gate; info findings are reported, not gated
        List<Map<String, Object>> checks = new ArrayList<>();
        for (ValidationTools.Finding f : findings) {
            total += f.count();
            if (f.count() > 0) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", f.id);
                c.put("severity", f.severity);
                c.put("count", f.count());
                checks.add(c);
                if ("warning".equals(f.severity)) {
                    warnings += f.count();
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_issues", total);
        summary.put("warnings", warnings);
        summary.put("checks", checks);
        return new StageResult(STRUCTURAL, true, warnings > 0 ? WARN : PASS, summary, null);
    }

    private static StageResult profileStage(Phase1 p1, String profileName, int limit) {
        Profiles profile = GovernanceTools.profileFor(profileName);
        if (profile == null || p1.profileSnapshot == null) {
            return StageResult.skipped(PROFILE, "profile check skipped (owl_profile=" + profileName + ").");
        }
        Map<String, Object> check = GovernanceTools.profileCheck(profile, profileName, p1.profileSnapshot,
                limit);
        boolean inProfile = Boolean.TRUE.equals(check.get("in_profile"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("profile", profileName);
        summary.put("in_profile", inProfile);
        summary.put("violations", check.get("count"));
        return new StageResult(PROFILE, true, inProfile ? PASS : FAIL, summary, null);
    }

    static StageResult invariantsStage(SuiteSnapshot snap, List<Invariants.Invariant> invariants,
            int limit, long timeout) {
        if (invariants.isEmpty()) {
            return StageResult.skipped(INVARIANTS, "no invariants supplied — pass an 'invariants' array "
                    + "(same shape as verify_ontology's queries[]).");
        }
        // Run at an "error" gate purely to read the worst severity as a 3-level stage verdict; the SUITE's
        // own fail_on is applied by aggregate() over that verdict's level (never passed down here).
        Map<String, Object> run = Invariants.run(snap, invariants, limit, timeout, "error");
        int violations = ((Number) run.get("violations")).intValue();
        int errors = ((Number) run.get("errors")).intValue();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checked", run.get("checked"));
        summary.put("violations", violations);
        summary.put("errors", errors);   // checks that could not run / were rejected — fail-closed
        // run.gate=="fail" ⇔ an ERROR-severity issue is present (a violation OR a fail-closed errored check —
        // Invariants.run's worst folds both) → the stage FAILs. A sub-error issue (a warn/info violation OR
        // a warn/info errored check) must still surface as WARN so the suite's fail_on=warn can trip: a
        // check that could not run is NEVER silently dropped to PASS. aggregate() maps this level vs fail_on.
        String verdict = FAIL.equals(run.get("gate")) ? FAIL
                : (violations > 0 || errors > 0) ? WARN : PASS;
        return new StageResult(INVARIANTS, true, verdict, summary, null);
    }

    static StageResult cqsStage(SuiteSnapshot snap, List<CompetencyQuestion> cqs, int limit,
            long timeout) {
        if (cqs == null || cqs.isEmpty()) {
            return StageResult.skipped(CQS, "no competency questions found — add some with "
                    + "add_competency_question.");
        }
        Map<String, Object> run = CqRunner.run(snap, cqs, limit, timeout, CqRunner.FAIL_ON_ANY);
        int failed = ((Number) run.get("failed")).intValue();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", run.get("total"));
        summary.put("passed", run.get("passed"));
        summary.put("failed", failed);
        // Surface (never gate on) per-CQ caveats — e.g. an include_inferred CQ that degraded to the asserted
        // triples because no reasoner was classified — so the aggregate gate is not silently reassuring.
        int caveated = countCaveated(run.get("questions"));
        if (caveated > 0) {
            summary.put("caveats", caveated);
        }
        return new StageResult(CQS, true, failed > 0 ? FAIL : PASS, summary, null);
    }

    /** Count the per-CQ result maps that carry a {@code caveats} entry (a soft degradation qualifier). */
    @SuppressWarnings("unchecked")
    private static int countCaveated(Object questions) {
        if (!(questions instanceof List)) {
            return 0;
        }
        int n = 0;
        for (Object q : (List<Object>) questions) {
            if (q instanceof Map && ((Map<String, Object>) q).containsKey("caveats")) {
                n++;
            }
        }
        return n;
    }

    private static List<CompetencyQuestion> loadCqs(OWLModelManager mm) {
        CqContext c = CqContext.of(mm);
        List<CompetencyQuestion> out = new ArrayList<>();
        for (CqStore store : CqStores.all()) {
            if (store.detect(c)) {
                out.addAll(store.load(c).ok);
            }
        }
        return out;
    }

    // ================================================================== aggregation (pure)

    /** Collapse the stage results into one gate verdict. Package-private for headless testing. */
    static CallToolResult aggregate(List<StageResult> results, String failOn) {
        List<Map<String, Object>> stagesJson = new ArrayList<>();
        int worst = 0;
        int ran = 0;
        for (StageResult r : results) {
            stagesJson.add(r.toJson());
            if (r.ran) {
                ran++;
                worst = Math.max(worst, level(r.verdict));
            }
        }
        String gate = worst >= threshold(failOn) ? FAIL : PASS;
        return Tools.json()
                .put("gate", gate)
                .put("fail_on", failOn)
                .put("stages_ran", ran)
                .put("stages", stagesJson)
                .result();
    }

    /** One stage's outcome. */
    static final class StageResult {
        final String stage;
        final boolean ran;
        final String verdict;              // pass | warn | fail (only meaningful when ran)
        final Map<String, Object> summary; // nullable
        final String reason;               // why it was skipped (when !ran)

        StageResult(String stage, boolean ran, String verdict, Map<String, Object> summary,
                String reason) {
            this.stage = stage;
            this.ran = ran;
            this.verdict = verdict;
            this.summary = summary;
            this.reason = reason;
        }

        static StageResult skipped(String stage, String reason) {
            return new StageResult(stage, false, null, null, reason);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", stage);
            m.put("ran", ran);
            if (ran) {
                m.put("verdict", verdict);
                if (summary != null) {
                    m.put("findings_summary", summary);
                }
            } else {
                m.put("reason", reason);
            }
            return m;
        }
    }

    // ================================================================== stage / gate helpers

    static Set<String> normalizeStages(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return new LinkedHashSet<>(DEFAULT_STAGES);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : requested) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (!ALL_STAGES.contains(v)) {
                throw new ToolArgException("Unknown stage '" + s + "'. Use any of: "
                        + String.join(", ", ALL_STAGES) + ".");
            }
            out.add(v);
        }
        return out;
    }

    static String normalizeFailOn(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "error";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "none":
            case "warn":
            case "error":
                return s;
            default:
                throw new ToolArgException("fail_on must be none, warn, or error (not '" + raw + "').");
        }
    }

    static int level(String verdict) {
        if (FAIL.equals(verdict)) {
            return 3;
        }
        if (WARN.equals(verdict)) {
            return 2;
        }
        return 0;
    }

    static int threshold(String failOn) {
        switch (failOn) {
            case "warn":
                return 2;
            case "error":
                return 3;
            case "none":
            default:
                return Integer.MAX_VALUE;
        }
    }

    /** Hand-assembled schema: stages[] + owl_profile + fail_on + limit + timeout_ms + invariants[]. */
    private static Map<String, Object> suiteSchema() {
        Map<String, Object> schema = Tools.schema()
                .strArray("stages", "Subset of: reasoner, profile, structural, invariants, cqs, shacl "
                        + "(default reasoner, profile, structural).")
                .str("owl_profile", "OWL 2 profile for the profile stage: DL (default), EL, QL, RL.")
                .str("fail_on", "Gate severity: none | warn | error (default error).")
                .integer("limit", "Max samples per check (default 25).")
                .integer("timeout_ms", "Time budget in ms for the SPARQL stages (default 120000).")
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");

        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("sparql", Tools.stringProperty("A SPARQL SELECT/ASK whose results are violations."));
        itemProps.put("id", Tools.stringProperty("Optional stable id."));
        itemProps.put("message", Tools.stringProperty("Human message for a violation."));
        itemProps.put("severity", Tools.stringProperty("error (default) | warn | info."));
        itemProps.put("include_inferred", Tools.boolProperty("Run over inferred triples (default false)."));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("required", Arrays.asList("sparql"));
        item.put("additionalProperties", false);
        Map<String, Object> invariants = new LinkedHashMap<>();
        invariants.put("type", "array");
        invariants.put("items", item);
        invariants.put("description", "Invariants for the 'invariants' stage (verify_ontology queries[]).");
        props.put("invariants", invariants);
        return schema;
    }
}
