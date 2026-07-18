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
                    String mode = Tools.optString(a, "mode");
                    if (mode != null && !"asserted".equalsIgnoreCase(mode)) {
                        return Tools.error("semantic_diff 0.6 prototype supports mode=asserted only; "
                                + "inferred/both were not evaluated.");
                    }
                    String leftRef = Tools.optString(a, "left");
                    String rightRef = Tools.optString(a, "right");
                    String rightDocument = Tools.optString(a, "right_document");
                    if ((rightRef == null) == (rightDocument == null)) {
                        return Tools.error("Provide exactly one of right or right_document.");
                    }
                    int limit = Tools.optInt(a, "limit", DEFAULT_LIMIT);
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    // Validate strictly even when no document is loaded: an invalid enum value is
                    // never silently ignored.
                    String network = Tools.optString(a, "network");
                    DirectAccessPolicy.requestNetworkDenies(network);
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
                    return ctx.access().compute(mm -> {
                        OWLOntology left = leftRef == null ? mm.getActiveOntology()
                                : OntologyDocumentTools.findLoadedOntology(mm, leftRef);
                        OWLOntology right = loaded != null ? loaded
                                : OntologyDocumentTools.findLoadedOntology(mm, rightRef);
                        if (left == null || right == null) {
                            return Tools.error("Could not resolve both semantic_diff ontologies.");
                        }
                        return Tools.ok(SemanticDiffService.diff(left, right, includeImports, limit,
                                unresolvedImports));
                    });
                });
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
            OWLOntology loaded = manager.loadOntologyFromOntologyDocument(
                    OntologyDocumentTools.documentSource(normalized), config);
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
