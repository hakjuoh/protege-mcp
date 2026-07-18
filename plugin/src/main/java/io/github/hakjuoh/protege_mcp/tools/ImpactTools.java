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
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

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
    static final int DOWNSTREAM_DEPTH_CAP = 3;

    /** Documented cap on how many downstream terms the sweep may discover before stopping. */
    static final int DOWNSTREAM_SIZE_CAP = 1_000;

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
        Set<OWLAxiom> rightDocAxioms = null;
        if (rightDocument != null) {
            DirectAccessPolicy.Rules docRules = DirectAccessPolicy.resolve(ctx, ex)
                    .withRequestNetwork(network);
            rightDocument = docRules.authorizeSource(rightDocument).value();
            List<OntologyDocumentTools.ImportMapping> mappings = ctx.access()
                    .compute(OntologyDocumentTools::workspaceImportMappings);
            OWLOntology loaded = DiffTools.loadDocument(rightDocument, mappings, unresolved,
                    docRules.importNetworkRule());
            rightDocAxioms = collect(loaded, includeImports);
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
            final Set<OWLAxiom> finalRightDocAxioms = rightDocAxioms;
            final String finalRightDocument = rightDocument;
            Workspace ws = ctx.access().compute(mm -> analyzeWorkspace(mm, finalEntry, finalLeftRef,
                    finalRightRef, finalRightDocAxioms, finalRightDocument, includeImports, limit,
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
                    "mapping management ships with the M6 milestone"));
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
            String leftRef, String rightRef, Set<OWLAxiom> rightDocAxioms, String rightDocLabel,
            boolean includeImports, int limit, boolean gatherWorkspaceCqs) {
        OWLOntology active = mm.getActiveOntology();
        Set<OWLAxiom> added;
        Set<OWLAxiom> removed;
        String leftLabel = null;
        String rightLabel = null;
        if (entry != null) {
            added = new LinkedHashSet<>();
            removed = new LinkedHashSet<>();
            for (NormalizedChange change : entry.changes) {
                (change.operation() == NormalizedChange.Operation.ADD ? added : removed)
                        .add(change.axiom());
            }
        } else {
            OWLOntology left = leftRef == null ? active
                    : OntologyDocumentTools.findLoadedOntology(mm, leftRef);
            if (left == null) {
                throw new ToolArgException("No loaded ontology matches left='" + leftRef
                        + "' (see list_ontologies).");
            }
            Set<OWLAxiom> leftAxioms = collect(left, includeImports);
            Set<OWLAxiom> rightAxioms;
            if (rightDocAxioms != null) {
                rightAxioms = rightDocAxioms;
                rightLabel = rightDocLabel;
            } else {
                OWLOntology right = OntologyDocumentTools.findLoadedOntology(mm, rightRef);
                if (right == null) {
                    throw new ToolArgException("No loaded ontology matches right='" + rightRef
                            + "' (see list_ontologies).");
                }
                rightAxioms = collect(right, includeImports);
                rightLabel = OntologyDocumentTools.ontologyLabel(right.getOntologyID());
            }
            leftLabel = OntologyDocumentTools.ontologyLabel(left.getOntologyID());
            DiffTools.Diff diff = DiffTools.diff(leftAxioms, rightAxioms);
            removed = diff.onlyLeft;
            added = diff.onlyRight;
        }
        Set<OWLOntology> scope = includeImports ? active.getImportsClosure()
                : java.util.Collections.singleton(active);
        Set<OWLOntology> closure = active.getImportsClosure();

        // 1. directly_affected: every IRI in the delta axioms' signatures (including annotation
        // subjects and IRI annotation values, which OWLAPI keeps out of getSignature), with exact
        // per-IRI added/removed axiom counts. TreeMap => IRI-sorted, deterministic.
        TreeMap<String, int[]> counts = new TreeMap<>();
        for (OWLAxiom ax : added) {
            for (String iri : affectedIris(ax)) {
                counts.computeIfAbsent(iri, key -> new int[2])[0]++;
            }
        }
        for (OWLAxiom ax : removed) {
            for (String iri : affectedIris(ax)) {
                counts.computeIfAbsent(iri, key -> new int[2])[1]++;
            }
        }
        List<String> affected = List.copyOf(counts.keySet());
        List<Map<String, Object>> affectedRows = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(
                        e -> -(e.getValue()[0] + e.getValue()[1]))
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.max(0, limit))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("iri", e.getKey());
                    row.put("added", e.getValue()[0]);
                    row.put("removed", e.getValue()[1]);
                    affectedRows.add(row);
                });
        Map<String, Object> directly = new LinkedHashMap<>();
        directly.put("count", counts.size());
        directly.put("items", affectedRows);
        if (counts.size() > affectedRows.size()) {
            directly.put("truncated", counts.size() - affectedRows.size());
        }

        // 2. referencing_axioms: workspace axioms mentioning an affected IRI that are NOT part of
        // the delta itself. Annotation assertions are collected explicitly for BOTH positions
        // OWLAPI keeps out of axiom signatures: assertions ON an affected IRI (subject) and
        // assertions whose VALUE is an affected IRI — the exact symmetric of affectedIris()'s
        // subject/value promotion, so a value-only reference is never invisible.
        Set<OWLAxiom> deltaAxioms = new LinkedHashSet<>(added);
        deltaAxioms.addAll(removed);
        Set<String> affectedSet = counts.keySet();
        Set<OWLAxiom> referencing = new LinkedHashSet<>();
        for (String iri : affected) {
            IRI i = IRI.create(iri);
            for (OWLOntology o : scope) {
                for (OWLEntity e : o.getEntitiesInSignature(i)) {
                    referencing.addAll(o.getReferencingAxioms(e));
                }
                referencing.addAll(o.getAnnotationAssertionAxioms(i));
            }
        }
        for (OWLOntology o : scope) {
            for (OWLAnnotationAssertionAxiom ann
                    : o.getAxioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)) {
                if (ann.getValue() instanceof IRI value && affectedSet.contains(value.toString())) {
                    referencing.add(ann);
                }
            }
        }
        referencing.removeAll(deltaAxioms);
        Map<String, Object> referencingJson = Tools.axiomList(mm, referencing, limit);

        // 3. downstream_terms: bounded signature-co-occurrence BFS (syntactic reachability).
        Map<String, Object> downstreamJson = downstream(scope, affected, limit);

        // 4. foreign_reaxiomatization: delta axioms whose subject entity is upstream-owned
        // (declared in an imported closure member, not in the active ontology) — the same
        // import-layering notion validate_governance's ownership check enforces.
        Set<OWLEntity> foreign = GovernanceTools.foreignDeclaredEntities(active, closure);
        List<Map<String, Object>> foreignRows = new ArrayList<>();
        collectForeignRows(mm, added, "add", foreign, foreignRows);
        collectForeignRows(mm, removed, "remove", foreign, foreignRows);
        foreignRows.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("subject")))
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

        // 5. deprecated_terms_in_use: owl:deprecated=true terms (anywhere in the closure)
        // referenced by the delta or by the referencing axioms above.
        Set<OWLEntity> candidates = new LinkedHashSet<>();
        for (OWLAxiom ax : deltaAxioms) {
            addReferencedEntities(ax, scope, candidates);
        }
        for (OWLAxiom ax : referencing) {
            addReferencedEntities(ax, scope, candidates);
        }
        Set<OWLEntity> deprecated = new LinkedHashSet<>();
        for (OWLEntity e : candidates) {
            if (ContextTools.isDeprecated(e, closure)) {
                deprecated.add(e);
            }
        }
        Map<String, Object> deprecatedJson = Tools.entityList(mm, deprecated, limit);

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

        return new Workspace(leftLabel, rightLabel, added.size(), removed.size(), directly,
                affected, referencingJson, downstreamJson, foreignJson, deprecatedJson, cqs,
                cqError);
    }

    /**
     * The IRIs an axiom syntactically touches: its signature entities plus — because OWLAPI keeps
     * them out of {@code getSignature()} — the subject and IRI value of an annotation assertion.
     */
    private static Set<String> affectedIris(OWLAxiom ax) {
        Set<String> out = new LinkedHashSet<>();
        for (OWLEntity e : ax.getSignature()) {
            out.add(e.getIRI().toString());
        }
        if (ax instanceof OWLAnnotationAssertionAxiom ann) {
            if (ann.getSubject() instanceof IRI subject) {
                out.add(subject.toString());
            }
            if (ann.getValue() instanceof IRI value) {
                out.add(value.toString());
            }
        }
        return out;
    }

    /** Signature entities plus workspace entities behind annotation subject/value IRIs. */
    private static void addReferencedEntities(OWLAxiom ax, Set<OWLOntology> scope,
            Set<OWLEntity> out) {
        out.addAll(ax.getSignature());
        if (ax instanceof OWLAnnotationAssertionAxiom ann) {
            if (ann.getSubject() instanceof IRI subject) {
                for (OWLOntology o : scope) {
                    out.addAll(o.getEntitiesInSignature(subject));
                }
            }
            if (ann.getValue() instanceof IRI value) {
                for (OWLOntology o : scope) {
                    out.addAll(o.getEntitiesInSignature(value));
                }
            }
        }
    }

    private static void collectForeignRows(OWLModelManager mm, Set<OWLAxiom> axioms,
            String operation, Set<OWLEntity> foreign, List<Map<String, Object>> rows) {
        for (OWLAxiom ax : axioms) {
            for (OWLEntity subject : GovernanceTools.subjectEntities(ax)) {
                if (foreign.contains(subject)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("operation", operation);
                    row.put("subject", subject.getIRI().toString());
                    row.putAll(Tools.axiomJson(mm, ax));
                    rows.add(row);
                    break;
                }
            }
        }
    }

    /**
     * Bounded breadth-first sweep of entities syntactically reachable from the affected entities:
     * every workspace axiom whose signature contains a frontier entity contributes its other
     * signature entities at the next depth. Deliberately an over-approximation (labelled
     * {@code analysis: "syntactic"}): co-occurrence in an asserted axiom proves reachability, not
     * logical impact. Depth/size caps are disclosed; after the last in-cap level one probe
     * expansion decides whether anything deeper exists, so {@code search_truncated} is never a
     * guess.
     */
    private static Map<String, Object> downstream(Set<OWLOntology> scope,
            Collection<String> affected, int limit) {
        LinkedHashMap<String, Integer> found = new LinkedHashMap<>();
        Set<String> visited = new java.util.HashSet<>(affected);
        List<String> frontier = new ArrayList<>(new TreeSet<>(affected));
        boolean searchTruncated = false;
        for (int depth = 1; depth <= DOWNSTREAM_DEPTH_CAP + 1 && !frontier.isEmpty(); depth++) {
            TreeSet<String> next = new TreeSet<>();
            for (String iri : frontier) {
                IRI i = IRI.create(iri);
                for (OWLOntology o : scope) {
                    for (OWLEntity e : o.getEntitiesInSignature(i)) {
                        for (OWLAxiom ax : o.getReferencingAxioms(e)) {
                            for (OWLEntity s : ax.getSignature()) {
                                String candidate = s.getIRI().toString();
                                if (!visited.contains(candidate)) {
                                    next.add(candidate);
                                }
                            }
                        }
                    }
                }
            }
            if (depth > DOWNSTREAM_DEPTH_CAP) {
                // Probe round: the depth cap stopped the sweep while more terms were reachable.
                if (!next.isEmpty()) {
                    searchTruncated = true;
                }
                break;
            }
            List<String> level = new ArrayList<>();
            for (String iri : next) {
                if (found.size() >= DOWNSTREAM_SIZE_CAP) {
                    searchTruncated = true;
                    break;
                }
                visited.add(iri);
                found.put(iri, depth);
                level.add(iri);
            }
            if (searchTruncated) {
                break;
            }
            frontier = level;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            if (items.size() >= Math.max(0, limit)) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("iri", e.getKey());
            row.put("depth", e.getValue());
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analysis", "syntactic");
        out.put("depth_cap", DOWNSTREAM_DEPTH_CAP);
        out.put("size_cap", DOWNSTREAM_SIZE_CAP);
        out.put("count", found.size());
        out.put("items", items);
        if (found.size() > items.size()) {
            out.put("truncated", found.size() - items.size());
        }
        if (searchTruncated) {
            out.put("search_truncated", true);
            out.put("search_note", "The breadth-first sweep stopped at its depth/size cap; "
                    + "syntactically reachable terms beyond the cap are neither listed nor counted.");
        }
        return out;
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

    private static Set<OWLAxiom> collect(OWLOntology o, boolean closure) {
        Set<OWLAxiom> out = new LinkedHashSet<>();
        Collection<OWLOntology> onts = closure ? o.getImportsClosure()
                : java.util.Collections.singleton(o);
        for (OWLOntology x : onts) {
            out.addAll(x.getAxioms());
        }
        return out;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
