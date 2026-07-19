package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.core.owl.OwlParsingErrors;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;

/**
 * Axiom-level semantic diff between two ontologies — the round-trip safety net for multi-module
 * reconstruction. Compares a {@code left} ontology (default the active one) against a {@code right}
 * one (another loaded ontology, or a freshly-loaded document) and reports the axioms present on only
 * one side. Used to verify a tool-rebuilt module against its original source (or a saved-then-reloaded
 * document), where {@code identical=true} means the reconstruction is axiom-for-axiom faithful. Pure
 * read-only set arithmetic on {@link OWLOntology#getAxioms()} (or {@code getLogicalAxioms()}); nothing
 * is loaded into the workspace when comparing against a document.
 */
public final class DiffTools {

    private DiffTools() {
    }

    /** Default cap on how many only-on-one-side axioms are listed per side. */
    private static final int DEFAULT_LIMIT = 50;

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("diff_ontologies",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String leftRef = Tools.optString(a, "left");
                    String rightRef = Tools.optString(a, "right");
                    String rightDoc = Tools.optString(a, "right_document");
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    boolean logicalOnly = Tools.optBool(a, "logical_only", false);
                    int limit = Tools.optInt(a, "limit", DEFAULT_LIMIT);
                    // Validate strictly even when no document is loaded: an invalid enum value is
                    // never silently ignored.
                    String network = Tools.optString(a, "network");
                    DirectAccessPolicy.requestNetworkDenies(network);
                    if (rightRef == null && rightDoc == null) {
                        return Tools.error("Provide 'right' (a loaded ontology IRI/version) or "
                                + "'right_document' (a document to load and compare against).");
                    }
                    DirectAccessPolicy.NetworkRule importNetwork = null;
                    if (rightDoc != null) {
                        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex)
                                .withRequestNetwork(network);
                        rightDoc = rules.authorizeSource(rightDoc).value();
                        importNetwork = rules.importNetworkRule();
                    }
                    // Load the comparison document OFF the EDT (network/parse), then diff on the EDT.
                    List<String> unresolvedRight = rightDoc != null ? new ArrayList<>() : null;
                    Set<OWLAxiom> rightAxioms = rightDoc != null
                            ? loadDocumentAxioms(rightDoc, includeImports, logicalOnly, importNetwork,
                                    unresolvedRight)
                            : null;
                    String rightLabel = rightDoc != null ? rightDoc : rightRef;
                    return ctx.access().compute(mm -> diff(mm, leftRef, rightRef, rightAxioms, rightLabel,
                            includeImports, logicalOnly, limit, unresolvedRight));
                });
        tools.tool("semantic_diff",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String mode = normalizeMode(Tools.optString(a, "mode"));
                    String leftRef = Tools.optString(a, "left");
                    String rightRef = Tools.optString(a, "right");
                    String rightDocument = Tools.optString(a, "right_document");
                    if ((rightRef == null) == (rightDocument == null)) {
                        return Tools.error("Provide exactly one of right or right_document.");
                    }
                    int limit = Tools.optInt(a, "limit", DEFAULT_LIMIT);
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    String reasonerName = Tools.optString(a, "reasoner");
                    int timeoutMs = Tools.optInt(a, "timeout_ms",
                            InferredDiffOrchestrator.DEFAULT_TIMEOUT_MS);
                    if (timeoutMs <= 0) {
                        timeoutMs = InferredDiffOrchestrator.DEFAULT_TIMEOUT_MS;
                    }
                    // Validate strictly even when no document is loaded: an invalid enum value is
                    // never silently ignored.
                    String network = Tools.optString(a, "network");
                    DirectAccessPolicy.requestNetworkDenies(network);
                    String policyPath = Tools.optString(a, "policy_path");
                    if (policyPath != null && "asserted".equals(mode)) {
                        // Never silently ignored: the policy-driven categories (module_ownership,
                        // stage_deltas) are part of the inferred work only; the released asserted
                        // result is unchanged.
                        return Tools.error("policy_path drives the module_ownership and "
                                + "stage_deltas categories of mode=inferred|both; mode=asserted "
                                + "does not evaluate it, so the request is refused rather than "
                                + "silently ignored.");
                    }
                    // Authorized like every other policy_path surface: resolve against the
                    // discovered project first, rewrite the argument to the exact canonical path
                    // that was authorized, then load that policy. A syntactically invalid path
                    // keeps flowing so the structured invalid-path diagnostic surfaces below.
                    RevisionTools.PolicyState policyState = null;
                    if (policyPath != null) {
                        DirectAccessPolicy.Rules policyRules =
                                DirectAccessPolicy.resolve(ctx, ex, policyPath);
                        policyState = RevisionTools.resolvePolicy(ctx, Tools.optString(
                                policyRules.authorizedPolicyArguments(a), "policy_path"));
                    }
                    List<String> unresolvedImports = new ArrayList<>();
                    final OWLOntology loaded;
                    DirectAccessPolicy.NetworkRule importNetwork = null;
                    if (rightDocument == null) {
                        loaded = null;
                    } else {
                        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex)
                                .withRequestNetwork(network);
                        rightDocument = rules.authorizeSource(rightDocument).value();
                        importNetwork = rules.importNetworkRule();
                        // Resolve the comparison document's imports the same way load_ontology does:
                        // snapshot the workspace's immutable logical->document IRI pairs on the EDT,
                        // then fetch and parse off it (with the sibling catalog still winning).
                        List<OntologyDocumentTools.ImportMapping> mappings = ctx.access()
                                .compute(OntologyDocumentTools::workspaceImportMappings);
                        loaded = loadDocument(rightDocument, mappings, unresolvedImports, importNetwork);
                    }
                    boolean wantsAsserted = !"inferred".equals(mode);
                    boolean wantsInferred = !"asserted".equals(mode);
                    // ONE model hop captures the asserted diff and, for inferred modes, the flattened
                    // sides + reasoner recipe; classification then runs OFF the EDT.
                    record Hop(Map<String, Object> asserted,
                            InferredDiffOrchestrator.Captured captured) { }
                    final String finalMode = mode;
                    Hop hop = ctx.access().compute(mm -> {
                        OWLOntology left = leftRef == null ? mm.getActiveOntology()
                                : OntologyDocumentTools.findLoadedOntology(mm, leftRef);
                        OWLOntology right = loaded != null ? loaded
                                : OntologyDocumentTools.findLoadedOntology(mm, rightRef);
                        if (left == null || right == null) {
                            throw new ToolArgException("Could not resolve both semantic_diff ontologies.");
                        }
                        Map<String, Object> asserted = wantsAsserted || wantsInferred
                                ? SemanticDiffService.diff(left, right, includeImports, limit,
                                        unresolvedImports)
                                : null;
                        InferredDiffOrchestrator.Captured captured = wantsInferred
                                ? InferredDiffOrchestrator.capture(mm, left, right, includeImports,
                                        reasonerName)
                                : null;
                        return new Hop(asserted, captured);
                    });
                    if (!wantsInferred) {
                        return Tools.ok(hop.asserted());
                    }
                    PolicyCategories policyCategories = policyCategories(policyState,
                            hop.captured(), limit);
                    Map<String, Object> evaluated = InferredDiffOrchestrator.run(hop.captured(),
                            timeoutMs, limit, policyCategories.plan());
                    Map<String, Object> stageDeltas = evaluated.get("stage_deltas")
                            instanceof Map<?, ?> evaluatedDeltas
                                    ? castMap(evaluatedDeltas) : policyCategories.stageDeltas();
                    return Tools.ok(assembleInferred(finalMode, hop.asserted(), evaluated,
                            includeImports, unresolvedImports, rightDocument != null,
                            policyCategories.moduleOwnership(), stageDeltas));
                });
    }

    /** The two policy-driven categories' state, resolved BEFORE the budgeted evaluation. */
    record PolicyCategories(Map<String, Object> moduleOwnership,
            Map<String, Object> stageDeltas, InferredDiffOrchestrator.StagePlan plan) { }

    /**
     * Resolve the policy-driven categories (ADR 0004 decisions 3-4). Without {@code policy_path}
     * both are present-but-unavailable with a reason; a policy that failed to resolve, load, or
     * validate errors BOTH categories fail-closed (never silently absent) — the errored stage
     * deltas then force {@code potentially_breaking} through the merged compatibility block. Only
     * a loaded, valid policy yields a live ownership map and an executable stage plan.
     */
    @SuppressWarnings("unchecked")
    static PolicyCategories policyCategories(RevisionTools.PolicyState policyState,
            InferredDiffOrchestrator.Captured captured, int limit) {
        if (policyState == null) {
            return new PolicyCategories(
                    InferredDiffOrchestrator.unavailable(
                            "no project policy with modules[].owned_namespaces was loaded"),
                    InferredDiffOrchestrator.unavailable(
                            "pass policy_path to evaluate policy-required stage deltas"),
                    null);
        }
        String error = null;
        if (policyState.error() != null) {
            error = policyState.error();
        } else if (!policyState.policy().loaded()) {
            error = "no project policy could be loaded from policy_path";
        } else if (!policyState.policy().valid()) {
            error = "the loaded project policy is invalid (see validate_project_policy); "
                    + "policy-driven diff categories fail closed";
        }
        if (error != null) {
            Map<String, Object> errored = new java.util.LinkedHashMap<>();
            errored.put("error", error);
            return new PolicyCategories(errored, new java.util.LinkedHashMap<>(errored), null);
        }
        Object modules = policyState.policy().effective().get("modules");
        Map<String, Object> ownership = InferredDiffOrchestrator.moduleOwnership(
                captured.leftSignature(), captured.rightSignature(),
                modules instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(),
                limit);
        InferredDiffOrchestrator.StagePlan plan =
                InferredDiffOrchestrator.stagePlan(policyState.policy());
        Map<String, Object> stageDeltas = plan != null ? null
                : InferredDiffOrchestrator.unavailable("the loaded policy requires none of the "
                        + "cqs, invariants, shacl, or governance stages");
        return new PolicyCategories(ownership, stageDeltas, plan);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String normalizeMode(String mode) {
        String normalized = mode == null ? "asserted" : mode.toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("asserted", "inferred", "both").contains(normalized)) {
            throw new ToolArgException("mode must be asserted, inferred, or both.");
        }
        return normalized;
    }

    /**
     * Assemble the inferred/both result. {@code mode=both} keeps every asserted section
     * byte-identical to {@code mode=asserted} except the {@code mode} field itself and the single
     * result-level {@code compatibility} block, which incorporates the inferred inputs (ADR 0004
     * decisions 7-8); {@code mode=inferred} carries only the envelope plus the inferred sections.
     * {@code module_ownership} and {@code stage_deltas} are the policy-driven categories (ADR 0004
     * decisions 3-4) — top-level beside {@code inferred}, never entailment-grammar members.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> assembleInferred(String mode, Map<String, Object> asserted,
            Map<String, Object> evaluated, boolean includeImports, List<String> unresolvedImports,
            boolean rightIsDocument, Map<String, Object> moduleOwnership,
            Map<String, Object> stageDeltas) {
        Map<String, Object> assertedCompatibility =
                (Map<String, Object>) asserted.get("compatibility");
        Map<String, Object> inferred = (Map<String, Object>) evaluated.get("inferred");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if ("both".equals(mode)) {
            result.putAll(asserted);
            result.put("mode", "both");
            result.remove("compatibility");
        } else {
            result.put("mode", "inferred");
            result.put("include_imports", includeImports);
            if (rightIsDocument) {
                result.put("right_document_unresolved_imports",
                        unresolvedImports.stream().distinct().sorted().toList());
            }
        }
        result.put("inferred", inferred);
        if (evaluated.get("reasoner") != null) {
            result.put("reasoner", evaluated.get("reasoner"));
        }
        result.put("module_ownership", moduleOwnership);
        result.put("stage_deltas", stageDeltas);
        result.put("compatibility", InferredDiffOrchestrator.mergedCompatibility(
                assertedCompatibility, inferred, stageDeltas));
        return result;
    }

    private static CallToolResult diff(OWLModelManager mm, String leftRef, String rightRef,
            Set<OWLAxiom> preloadedRight, String rightLabel, boolean includeImports, boolean logicalOnly,
            int limit, List<String> unresolvedRightImports) {
        OWLOntology left = leftRef == null ? mm.getActiveOntology()
                : OntologyDocumentTools.findLoadedOntology(mm, leftRef);
        if (left == null) {
            return Tools.error("No loaded ontology matches left='" + leftRef + "' (see list_ontologies).");
        }
        Set<OWLAxiom> leftAxioms = collect(left, includeImports, logicalOnly);

        Set<OWLAxiom> rightAxioms;
        if (preloadedRight != null) {
            rightAxioms = preloadedRight;
        } else {
            OWLOntology right = OntologyDocumentTools.findLoadedOntology(mm, rightRef);
            if (right == null) {
                return Tools.error("No loaded ontology matches right='" + rightRef
                        + "' (see list_ontologies).");
            }
            rightAxioms = collect(right, includeImports, logicalOnly);
        }

        Diff d = diff(leftAxioms, rightAxioms);
        boolean identical = d.onlyLeft.isEmpty() && d.onlyRight.isEmpty();
        Tools.Json out = Tools.json()
                .put("left", labelOf(left.getOntologyID()))
                .put("right", rightLabel)
                .put("include_imports", includeImports)
                .put("logical_only", logicalOnly)
                .put("identical", identical)
                .put("left_axioms", leftAxioms.size())
                .put("right_axioms", rightAxioms.size())
                .put("common", leftAxioms.size() - d.onlyLeft.size())
                .put("only_in_left", Tools.axiomList(mm, d.onlyLeft, limit))
                .put("only_in_right", Tools.axiomList(mm, d.onlyRight, limit));
        if (unresolvedRightImports != null) {
            // Mirror semantic_diff: a truncated right closure must be visible on the verdict, or a
            // dropped import could flip identical/only_in_left silently.
            List<String> unresolved = unresolvedRightImports.stream().distinct().sorted().toList();
            out.put("right_document_unresolved_imports", unresolved);
            if (includeImports && !unresolved.isEmpty()) {
                out.put("caveat", "The right side's imports closure is truncated - unresolved imports: "
                        + String.join(", ", unresolved)
                        + ". Their axioms are invisible to this diff and can flip its verdict in "
                        + "either direction.");
            }
        }
        return out.result();
    }

    /** The two one-sided differences of the axiom sets. Package-visible and pure for unit testing. */
    static Diff diff(Set<OWLAxiom> left, Set<OWLAxiom> right) {
        Set<OWLAxiom> onlyLeft = new LinkedHashSet<>(left);
        onlyLeft.removeAll(right);
        Set<OWLAxiom> onlyRight = new LinkedHashSet<>(right);
        onlyRight.removeAll(left);
        return new Diff(onlyLeft, onlyRight);
    }

    static final class Diff {
        final Set<OWLAxiom> onlyLeft;
        final Set<OWLAxiom> onlyRight;

        Diff(Set<OWLAxiom> onlyLeft, Set<OWLAxiom> onlyRight) {
            this.onlyLeft = onlyLeft;
            this.onlyRight = onlyRight;
        }
    }

    private static Set<OWLAxiom> collect(OWLOntology o, boolean closure, boolean logicalOnly) {
        Set<OWLAxiom> out = new LinkedHashSet<>();
        Collection<OWLOntology> onts = closure ? o.getImportsClosure() : Collections.singleton(o);
        for (OWLOntology x : onts) {
            out.addAll(logicalOnly ? x.getLogicalAxioms() : x.getAxioms());
        }
        return out;
    }

    /**
     * Load a comparison document into a throwaway manager (missing imports SILENT) and collect its
     * axioms. Reuses {@link OntologyDocumentTools} for source normalization, sibling-catalog
     * registration, and document-source selection, so an {@code include_imports} diff resolves the
     * right document's imports the same way the active (left) ontology's were — via a sibling
     * catalog-v001.xml — instead of silently fetching remotely or dropping them.
     */
    private static Set<OWLAxiom> loadDocumentAxioms(String source, boolean closure, boolean logicalOnly) {
        return collect(loadDocument(source, List.of(), new ArrayList<>()), closure, logicalOnly);
    }

    private static Set<OWLAxiom> loadDocumentAxioms(String source, boolean closure,
            boolean logicalOnly, DirectAccessPolicy.NetworkRule networkRule,
            Collection<String> unresolvedOut) {
        return collect(loadDocument(source, List.of(), unresolvedOut, networkRule),
                closure, logicalOnly);
    }

    /**
     * Package-visible for tests. Registers the supplied workspace import mappings before the sibling
     * catalog (OWLAPI gives the most recently added mapper priority, and the version-controlled
     * catalog must win) and reports every import IRI that failed to resolve into
     * {@code unresolvedOut}: a silently truncated closure is invisible to the diff and could flip its
     * classification in either direction.
     */
    static OWLOntology loadDocument(String source, List<OntologyDocumentTools.ImportMapping> mappings,
            Collection<String> unresolvedOut) {
        return loadDocument(source, mappings, unresolvedOut,
                new DirectAccessPolicy.NetworkRule(true, Set.of(), true, true));
    }

    static OWLOntology loadDocument(String source, List<OntologyDocumentTools.ImportMapping> mappings,
            Collection<String> unresolvedOut, DirectAccessPolicy.NetworkRule networkRule) {
        String normalized = OntologyDocumentTools.normalizeSource(source);
        return loadDocument(OntologyDocumentTools.documentSource(normalized), normalized, mappings,
                unresolvedOut, networkRule);
    }

    /**
     * Load a comparison document from ALREADY-READ bytes. TOCTOU discipline: when the caller has
     * verified a digest over exactly these bytes, the parse must consume them — never a re-read of
     * the path, which a concurrent writer could swap between verification and load. The path still
     * anchors the sibling-catalog lookup and relative import resolution.
     */
    static OWLOntology loadDocument(byte[] documentBytes, java.nio.file.Path documentPath,
            List<OntologyDocumentTools.ImportMapping> mappings, Collection<String> unresolvedOut,
            DirectAccessPolicy.NetworkRule networkRule) {
        return loadDocument(new org.semanticweb.owlapi.io.StreamDocumentSource(
                        new java.io.ByteArrayInputStream(documentBytes),
                        org.semanticweb.owlapi.model.IRI.create(documentPath.toUri())),
                documentPath.toString(), mappings, unresolvedOut, networkRule);
    }

    private static OWLOntology loadDocument(
            org.semanticweb.owlapi.io.OWLOntologyDocumentSource documentSource, String normalized,
            List<OntologyDocumentTools.ImportMapping> mappings, Collection<String> unresolvedOut,
            DirectAccessPolicy.NetworkRule networkRule) {
        OWLOntologyManager manager = OwlManagers.create();
        manager.addMissingImportListener(
                event -> unresolvedOut.add(event.getImportedOntologyURI().toString()));
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setFollowRedirects(networkRule.followRedirects());
        try (OntologyDocumentTools.NetworkImportBlocker blocker =
                OntologyDocumentTools.NetworkImportBlocker.install(manager, networkRule);
                OntologyDocumentTools.UnsupportedImportFallback fallback =
                        OntologyDocumentTools.UnsupportedImportFallback.install(manager)) {
            OntologyDocumentTools.addWorkspaceImportMappers(manager, mappings, blocker);
            OntologyDocumentTools.addFolderCatalogMapper(manager, normalized, blocker);
            OWLOntology loaded = manager.loadOntologyFromOntologyDocument(documentSource, config);
            // Same closing steps as load_ontology/merge: restore fallback-satisfied IRIs (an
            // unsupported scheme such as a URN) to the caller's unresolved list, then apply the
            // policy verdict — so all three document loaders report the same structured diagnostic
            // instead of diff leaking a raw OWLAPI factory error.
            OntologyDocumentTools.finishUnsupportedImports(loaded, fallback, unresolvedOut,
                    MissingImportsMode.SILENT, normalized);
            blocker.failIfBlocked(normalized, unresolvedOut);
            return loaded;
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not load comparison document '" + normalized + "': "
                    + OwlParsingErrors.conciseMessage(e));
        }
    }

    private static String labelOf(OWLOntologyID id) {
        return OntologyDocumentTools.ontologyLabel(id);
    }
}
