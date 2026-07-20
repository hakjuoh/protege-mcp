package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.core.impact.SyntacticImpactService;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Read-only syntactic impact analysis over a change — either a cached change-set preview (analyzed
 * from its stored normalized delta) or an asserted diff between two ontology artifacts (the same
 * left/right/right_document machinery as {@code diff_ontologies}/{@code semantic_diff}). Every
 * category reports SYNTACTIC reachability over asserted axioms with exact counts and bounded
 * samples; nothing here proves logical impact — the result says so ({@code analysis: "syntactic"})
 * and points to {@code semantic_diff mode=inferred|both} for entailment-level evidence.
 */
public final class ImpactTools {

    private ImpactTools() {
    }

    /** Default cap on samples per category. */
    private static final int DEFAULT_LIMIT = 50;

    /** Documented cap on the downstream breadth-first sweep depth (hops from an affected term). */
    static final int DOWNSTREAM_DEPTH_CAP = SyntacticImpactService.DOWNSTREAM_DEPTH_CAP;

    /** Documented cap on how many downstream terms the sweep may discover before stopping. */
    static final int DOWNSTREAM_SIZE_CAP = SyntacticImpactService.DOWNSTREAM_SIZE_CAP;

    /** Per-file text-scan bound for validation assets (larger files are skipped with a reason). */
    static final long MAX_ASSET_TEXT_BYTES = 1_048_576L;

    /** Total validation-asset files scanned before the textual search stops (disclosed). */
    static final int MAX_ASSET_TEXT_FILES = 100;

    /** How many affected IRIs the textual asset search matches against (disclosed). */
    static final int MAX_SEARCHED_IRIS = 500;

    private static final String NOTE = "Syntactic/asserted analysis only: every category reports "
            + "syntactic reachability over asserted axioms, never proven logical impact. For proven "
            + "logical impact (entailment deltas, satisfiability, policy stage deltas), run "
            + "semantic_diff mode=inferred|both.";

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("analyze_change_impact", (ex, req) -> analyze(ctx, ex, Tools.args(req)));
    }

    static CallToolResult analyze(ToolContext ctx, McpSyncServerExchange ex, Map<String, Object> a) {
        String changeSetId = Tools.optString(a, "change_set_id");
        String leftRef = Tools.optString(a, "left");
        String rightRef = Tools.optString(a, "right");
        String rightDocument = Tools.optString(a, "right_document");
        boolean includeImports = Tools.optBool(a, "include_imports", false);
        int limit = Tools.optInt(a, "limit", DEFAULT_LIMIT);
        // Validate strictly even when no document is loaded: an invalid enum value is never
        // silently ignored (mirrors diff_ontologies/semantic_diff).
        String network = Tools.optString(a, "network");
        DirectAccessPolicy.requestNetworkDenies(network);
        if (changeSetId != null && (leftRef != null || rightRef != null || rightDocument != null)) {
            return Tools.error("Provide exactly one input form: change_set_id (a cached preview), "
                    + "or a left/right|right_document comparison pair — not both.");
        }
        if (changeSetId == null && rightRef == null && rightDocument == null) {
            return Tools.error("Provide exactly one input form: change_set_id (a cached preview), "
                    + "or a comparison pair via right or right_document.");
        }
        if (rightRef != null && rightDocument != null) {
            return Tools.error("Provide exactly one of right or right_document.");
        }
        // Capability gate (PROJECT_READ effective) plus policy_path authorization, exactly like
        // preview_change_set: resolve against the discovered project first, then rewrite the
        // argument to the canonical authorized path before loading that policy. A syntactically
        // invalid path keeps flowing so the policy-driven categories fail closed with the
        // structured invalid-path diagnostic instead of a bare gate error.
        String policyPath = Tools.optString(a, "policy_path");
        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex, policyPath);
        RevisionTools.PolicyState policyState = null;
        if (policyPath != null) {
            policyState = RevisionTools.resolvePolicy(ctx,
                    Tools.optString(rules.authorizedPolicyArguments(a), "policy_path"));
        }
        boolean gatherWorkspaceCqs = policyState != null && policyError(policyState) == null;

        // Load the comparison document OFF the EDT (network/parse), like diff_ontologies.
        List<String> unresolved = new ArrayList<>();
        OWLOntology rightDocOntology = null;
        if (rightDocument != null) {
            // The explicit/discovered policy resolved above governs the WHOLE request. Re-resolving
            // here without policy_path could authorize the document under a different, weaker
            // discovered policy while policy-driven impact categories reported the explicit one.
            DirectAccessPolicy.Rules docRules = rules.withRequestNetwork(network);
            rightDocument = docRules.authorizeSource(rightDocument).value();
            List<OntologyDocumentTools.ImportMapping> mappings = ctx.access()
                    .compute(OntologyDocumentTools::workspaceImportMappings);
            OWLOntology loaded = DiffTools.loadDocument(rightDocument, mappings, unresolved,
                    docRules.importNetworkRule());
            rightDocOntology = loaded;
        }

        ChangeSetStore.Entry entry = null;
        if (changeSetId != null) {
            ChangeSetStore.Lookup lookup = ctx.changeSets().claim(changeSetId);
            if (lookup.entry() == null) {
                Map<String, Object> refused = new LinkedHashMap<>();
                refused.put("change_set_id", changeSetId);
                refused.put("analyzed", false);
                refused.put("error_code", lookup.error());
                return Tools.ok(refused);
            }
            entry = lookup.entry();
        }
        // The claim is held for the whole (read-only) analysis and released in the finally: a
        // claimed entry cannot be committed, discarded, swept, or evicted mid-read, and the entry
        // stays available afterwards — analysis never consumes it (mirrors rebase_change_set).
        try {
            final ChangeSetStore.Entry finalEntry = entry;
            final String finalLeftRef = leftRef;
            final String finalRightRef = rightRef;
            final OWLOntology finalRightDocOntology = rightDocOntology;
            final String finalRightDocument = rightDocument;
            Workspace ws = ctx.access().compute(mm -> analyzeWorkspace(mm, finalEntry, finalLeftRef,
                    finalRightRef, finalRightDocOntology, finalRightDocument, includeImports, limit,
                    gatherWorkspaceCqs));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("analysis", "syntactic");
            result.put("note", NOTE);
            if (entry != null) {
                result.put("change_set_id", changeSetId);
                result.put("base_revision", RevisionTools.revisionJson(entry.baseRevision));
            } else {
                result.put("left", ws.leftLabel());
                result.put("right", ws.rightLabel());
                if (rightDocument != null) {
                    List<String> distinct = unresolved.stream().distinct().sorted().toList();
                    result.put("right_document_unresolved_imports", distinct);
                    if (includeImports && !distinct.isEmpty()) {
                        result.put("caveat", "The right side's imports closure is truncated - "
                                + "unresolved imports: " + String.join(", ", distinct)
                                + ". Axioms from those imports are invisible to this analysis and "
                                + "can add or remove impact in either direction.");
                    }
                }
            }
            result.put("include_imports", includeImports);
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("added_axioms", ws.addedCount());
            delta.put("removed_axioms", ws.removedCount());
            result.put("delta", delta);
            Map<String, Object> directly = ws.directlyAffected();
            directly.put("modules", moduleAttribution(policyState, ws.affectedIris(), limit));
            result.put("directly_affected", directly);
            result.put("referencing_axioms", ws.referencingAxioms());
            result.put("downstream_terms", ws.downstreamTerms());
            result.put("foreign_reaxiomatization", ws.foreignReaxiomatization());
            result.put("deprecated_terms_in_use", ws.deprecatedInUse());
            // Asset text is read here, OFF the EDT (the workspace hop above gathered only the
            // in-memory CQ texts the stores could resolve on the model thread).
            result.put("validation_references", validationReferences(policyState, ws.affectedIris(),
                    ws.workspaceCqs(), ws.workspaceCqError(), limit));
            result.put("public_api_terms", InferredDiffOrchestrator.unavailable(
                    "policy v1 does not yet declare public API terms"));
            result.put("external_mappings", InferredDiffOrchestrator.unavailable(
                    "mapping management is not available in this release"));
            return Tools.ok(result);
        } finally {
            if (entry != null) {
                ctx.changeSets().release(entry);
            }
        }
    }

    // ------------------------------------------------------------------ workspace analysis (one hop)

    private record Workspace(String leftLabel, String rightLabel, int addedCount, int removedCount,
            Map<String, Object> directlyAffected, List<String> affectedIris,
            Map<String, Object> referencingAxioms, Map<String, Object> downstreamTerms,
            Map<String, Object> foreignReaxiomatization, Map<String, Object> deprecatedInUse,
            List<WorkspaceCq> workspaceCqs, String workspaceCqError) { }

    record WorkspaceCq(String id, String convention, String text) { }

    private static Workspace analyzeWorkspace(OWLModelManager mm, ChangeSetStore.Entry entry,
            String leftRef, String rightRef, OWLOntology rightDocOntology, String rightDocLabel,
            boolean includeImports, int limit, boolean gatherWorkspaceCqs) {
        OWLOntology active = mm.getActiveOntology();
        String leftLabel = null;
        String rightLabel = null;
        SyntacticImpactService.Result impact;
        if (entry != null) {
            Set<OWLAxiom> added = new LinkedHashSet<>();
            Set<OWLAxiom> removed = new LinkedHashSet<>();
            for (NormalizedChange change : entry.changes) {
                (change.operation() == NormalizedChange.Operation.ADD ? added : removed)
                        .add(change.axiom());
            }
            impact = SyntacticImpactService.analyze(active, active, added, removed,
                    includeImports);
        } else {
            OWLOntology left = leftRef == null ? active
                    : OntologyDocumentTools.findLoadedOntology(mm, leftRef);
            if (left == null) {
                throw new ToolArgException("No loaded ontology matches left='" + leftRef
                        + "' (see list_ontologies).");
            }
            OWLOntology right;
            if (rightDocOntology != null) {
                right = rightDocOntology;
                rightLabel = rightDocLabel;
            } else {
                right = OntologyDocumentTools.findLoadedOntology(mm, rightRef);
                if (right == null) {
                    throw new ToolArgException("No loaded ontology matches right='" + rightRef
                            + "' (see list_ontologies).");
                }
                rightLabel = OntologyDocumentTools.ontologyLabel(right.getOntologyID());
            }
            leftLabel = OntologyDocumentTools.ontologyLabel(left.getOntologyID());
            impact = SyntacticImpactService.compare(left, right, includeImports);
        }
        Map<String, Object> directly = impact.directlyAffectedMap(limit);
        Map<String, Object> referencingJson = Tools.axiomList(mm, impact.referencingAxioms(), limit);
        Map<String, Object> downstreamJson = impact.downstream().toMap(limit);

        List<Map<String, Object>> foreignRows = new ArrayList<>();
        for (SyntacticImpactService.ForeignImpact foreign : impact.foreignReaxiomatization()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation", foreign.operation());
            row.put("subject", foreign.subjectIri());
            row.putAll(Tools.axiomJson(mm, foreign.axiom()));
            foreignRows.add(row);
        }
        // Renderer strings are presentation-only but continue to determine the released display
        // order. The core service already fixed semantic membership and subject attribution.
        foreignRows.sort(Comparator.comparing((Map<String, Object> row) -> String.valueOf(row.get("subject")))
                .thenComparing(row -> String.valueOf(row.get("rendering")))
                .thenComparing(row -> String.valueOf(row.get("operation"))));
        Map<String, Object> foreignJson = new LinkedHashMap<>();
        foreignJson.put("count", foreignRows.size());
        List<Map<String, Object>> foreignSample = foreignRows.size() > Math.max(0, limit)
                ? List.copyOf(foreignRows.subList(0, Math.max(0, limit))) : foreignRows;
        foreignJson.put("items", foreignSample);
        if (foreignRows.size() > foreignSample.size()) {
            foreignJson.put("truncated", foreignRows.size() - foreignSample.size());
        }

        Map<String, Object> deprecatedJson = Tools.entityList(mm, impact.deprecatedTerms(), limit);

        // In-workspace CQ text (id/question/query) for the textual validation search. The stores
        // must run on the model thread (they read model + sidecar context), matching
        // list_competency_questions; the substring matching itself happens off the hop.
        List<WorkspaceCq> cqs = new ArrayList<>();
        String cqError = null;
        if (gatherWorkspaceCqs) {
            try {
                CqContext c = CqContext.of(mm);
                for (CqStore store : CqStores.all()) {
                    if (!store.detect(c)) {
                        continue;
                    }
                    for (CompetencyQuestion cq : store.load(c).ok) {
                        cqs.add(new WorkspaceCq(cq.id, cq.convention,
                                nullToEmpty(cq.id) + "\n" + nullToEmpty(cq.text) + "\n"
                                        + nullToEmpty(cq.query)));
                    }
                }
            } catch (RuntimeException e) {
                cqError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }

        return new Workspace(leftLabel, rightLabel, impact.addedCount(), impact.removedCount(),
                directly, impact.affectedIris(), referencingJson, downstreamJson, foreignJson,
                deprecatedJson, cqs, cqError);
    }

    // ------------------------------------------------------------------ policy-driven categories

    /** Fail-closed policy triage shared by the two policy-driven categories (mirrors semantic_diff). */
    private static String policyError(RevisionTools.PolicyState state) {
        if (state.error() != null) {
            return state.error();
        }
        if (!state.policy().loaded()) {
            return "no project policy could be loaded from policy_path";
        }
        if (!state.policy().valid()) {
            return "the loaded project policy is invalid (see validate_project_policy); "
                    + "policy-driven impact categories fail closed";
        }
        return null;
    }

    private static Map<String, Object> errored(String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        return out;
    }

    /**
     * Owning-module attribution for the directly-affected IRIs, using the released boundary-aware
     * most-specific {@code modules[].owned_namespaces} matcher (the same seam the inferred diff's
     * module_ownership category uses). No policy => present-but-unavailable; a policy that failed
     * to resolve, load, or validate => an error object, never a silently absent category.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> moduleAttribution(RevisionTools.PolicyState state,
            Collection<String> affectedIris, int limit) {
        if (state == null) {
            return InferredDiffOrchestrator.unavailable(
                    "pass policy_path to attribute affected terms to owning modules");
        }
        String error = policyError(state);
        if (error != null) {
            return errored(error);
        }
        Object modulesValue = state.policy().effective().get("modules");
        List<Map<String, Object>> modules = modulesValue instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        Map<String, TreeSet<String>> owners = new LinkedHashMap<>();
        for (Map<String, Object> module : modules) {
            Object label = module.get("ontology_iri");
            Object namespaces = module.get("owned_namespaces");
            if (!(label instanceof String) || !(namespaces instanceof List<?>)) {
                continue;
            }
            for (Object namespace : (List<?>) namespaces) {
                if (namespace instanceof String ns) {
                    owners.computeIfAbsent(ns, key -> new TreeSet<>()).add((String) label);
                }
            }
        }
        if (owners.isEmpty()) {
            return InferredDiffOrchestrator.unavailable(
                    "no project policy with modules[].owned_namespaces was loaded");
        }
        TreeMap<String, TreeSet<String>> byModule = new TreeMap<>();
        TreeSet<String> unowned = new TreeSet<>();
        for (String iri : affectedIris) {
            String namespace = ModulePolicyGovernance.mostSpecificOwnedNamespace(iri,
                    owners.keySet());
            if (namespace == null) {
                unowned.add(iri);
            } else {
                byModule.computeIfAbsent(String.join(", ", owners.get(namespace)),
                        key -> new TreeSet<>()).add(iri);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> e : byModule.entrySet()) {
            if (items.size() >= Math.max(0, limit)) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("module", e.getKey());
            row.put("terms", boundedStrings(e.getValue(), limit));
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("count", byModule.size());
        out.put("items", items);
        if (byModule.size() > items.size()) {
            out.put("truncated", byModule.size() - items.size());
        }
        out.put("unowned", boundedStrings(unowned, limit));
        return out;
    }

    /**
     * Textual search of the policy-resolved validation assets (invariants, SHACL shapes, CQ files)
     * and the in-workspace CQ stores for occurrences of affected IRIs. Plain substring matching on
     * the raw asset text — labelled {@code match: "textual"} — with disclosed file/byte/IRI budgets;
     * unreadable or oversized files are skipped with a reason, never silently.
     */
    static Map<String, Object> validationReferences(RevisionTools.PolicyState state,
            List<String> affectedIris, List<WorkspaceCq> workspaceCqs, String workspaceCqError,
            int limit) {
        if (state == null) {
            return InferredDiffOrchestrator.unavailable("pass policy_path to search the "
                    + "policy-resolved validation assets (invariants, SHACL shapes, competency "
                    + "questions) for changed IRIs");
        }
        String error = policyError(state);
        if (error != null) {
            return errored(error);
        }
        ProjectPolicy policy = state.policy();
        List<String> searched = affectedIris.size() > MAX_SEARCHED_IRIS
                ? affectedIris.subList(0, MAX_SEARCHED_IRIS) : affectedIris;
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        int[] scanned = {0};
        boolean scanTruncated = false;
        for (String key : List.of("invariants", "shacl", "cqs")) {
            for (Path path : policy.assets().getOrDefault(key, List.of())) {
                scanTruncated |= scanAsset(key, path, searched, limit, rows, skipped, scanned);
            }
        }
        for (WorkspaceCq cq : workspaceCqs) {
            List<String> matched = searched.stream().filter(cq.text()::contains).toList();
            if (!matched.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "workspace_cq");
                row.put("ref", cq.id());
                row.put("convention", cq.convention());
                row.put("iris", boundedStrings(new TreeSet<>(matched), limit));
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("match", "textual");
        out.put("count", rows.size());
        List<Map<String, Object>> sample = rows.size() > Math.max(0, limit)
                ? List.copyOf(rows.subList(0, Math.max(0, limit))) : rows;
        out.put("items", sample);
        if (rows.size() > sample.size()) {
            out.put("truncated", rows.size() - sample.size());
        }
        out.put("files_scanned", scanned[0]);
        if (!skipped.isEmpty()) {
            Map<String, Object> skippedJson = new LinkedHashMap<>();
            skippedJson.put("count", skipped.size());
            List<Map<String, Object>> skippedSample = skipped.size() > 10
                    ? List.copyOf(skipped.subList(0, 10)) : skipped;
            skippedJson.put("items", skippedSample);
            if (skipped.size() > skippedSample.size()) {
                skippedJson.put("truncated", skipped.size() - skippedSample.size());
            }
            out.put("files_skipped", skippedJson);
        }
        if (scanTruncated) {
            out.put("scan_truncated", true);
            out.put("scan_note", "The textual search stopped after " + MAX_ASSET_TEXT_FILES
                    + " asset files; remaining assets were not searched.");
        }
        Map<String, Object> searchedJson = new LinkedHashMap<>();
        searchedJson.put("count", affectedIris.size());
        searchedJson.put("searched", searched.size());
        if (affectedIris.size() > searched.size()) {
            searchedJson.put("truncated", affectedIris.size() - searched.size());
        }
        out.put("searched_iris", searchedJson);
        if (workspaceCqError != null) {
            out.put("workspace_cq_error", workspaceCqError);
        }
        return out;
    }

    /** Scan one resolved asset (file or directory); returns true when the file budget stopped it. */
    private static boolean scanAsset(String key, Path path, List<String> searched, int limit,
            List<Map<String, Object>> rows, List<Map<String, Object>> skipped, int[] scanned) {
        List<Path> files = new ArrayList<>();
        try {
            if (Files.isDirectory(path)) {
                // Confinement mirrors the preflight digest: only regular files whose real path
                // stays under the asset directory's real path are read, so a symlinked child
                // cannot pull external file content into the search.
                Path base = path.toRealPath();
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile).sorted().forEach(candidate -> {
                        try {
                            if (candidate.toRealPath().startsWith(base)) {
                                files.add(candidate);
                            } else {
                                skipped.add(skipRow(candidate, "outside the asset directory"));
                            }
                        } catch (IOException e) {
                            skipped.add(skipRow(candidate, "unreadable: " + e.getMessage()));
                        }
                    });
                }
            } else {
                files.add(path);
            }
        } catch (IOException e) {
            skipped.add(skipRow(path, "unreadable: " + e.getMessage()));
            return false;
        }
        for (Path file : files) {
            if (scanned[0] >= MAX_ASSET_TEXT_FILES) {
                return true;
            }
            String text;
            try {
                if (Files.size(file) > MAX_ASSET_TEXT_BYTES) {
                    skipped.add(skipRow(file, "exceeds the " + MAX_ASSET_TEXT_BYTES
                            + "-byte text-scan limit"));
                    continue;
                }
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                skipped.add(skipRow(file, "unreadable: " + e.getMessage()));
                continue;
            }
            // files_scanned counts only files whose text was actually read and searched; a
            // skipped file is accounted in files_skipped instead, never in both.
            scanned[0]++;
            List<String> matched = searched.stream().filter(text::contains).toList();
            if (!matched.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", key);
                row.put("ref", file.toString());
                row.put("iris", boundedStrings(new TreeSet<>(matched), limit));
                rows.add(row);
            }
        }
        return false;
    }

    private static Map<String, Object> skipRow(Path path, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path.toString());
        row.put("reason", reason);
        return row;
    }

    /** The shared {@code {count, items, truncated?}} shape for a sorted string collection. */
    private static Map<String, Object> boundedStrings(TreeSet<String> values, int limit) {
        List<String> items = new ArrayList<>();
        for (String value : values) {
            if (items.size() >= Math.max(0, limit)) {
                break;
            }
            items.add(value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", values.size());
        out.put("items", items);
        if (values.size() > items.size()) {
            out.put("truncated", values.size() - items.size());
        }
        return out;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
