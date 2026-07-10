package io.github.hakjuoh.protege_mcp.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

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
                "Diff two ontologies at the axiom level. 'left' (default the active ontology) and "
                        + "'right' name loaded ontologies by ontology IRI or version IRI (see "
                        + "list_ontologies); alternatively 'right_document' loads a document (path/URL/IRI) "
                        + "purely to compare against — without adding it to the workspace. Reports counts "
                        + "and capped samples of axioms only-in-left and only-in-right, and "
                        + "identical=true when the two axiom sets match (a faithful round-trip). Use "
                        + "include_imports to compare imports closures, logical_only to ignore "
                        + "declarations/annotations. Read-only.",
                Tools.schema()
                        .str("left", "Ontology IRI/version of a loaded ontology (default: active).")
                        .str("right", "Ontology IRI/version of a loaded ontology to compare against.")
                        .str("right_document", "Path/URL/IRI of a document to load and compare against "
                                + "(alternative to 'right'; not added to the workspace).")
                        .bool("include_imports", "Compare full imports closures instead of just the two "
                                + "ontologies (default false).")
                        .bool("logical_only", "Compare logical axioms only, ignoring declarations and "
                                + "annotation assertions (default false).")
                        .integer("limit", "Max axioms to list per side (default " + DEFAULT_LIMIT + ").")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String leftRef = Tools.optString(a, "left");
                    String rightRef = Tools.optString(a, "right");
                    String rightDoc = Tools.optString(a, "right_document");
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    boolean logicalOnly = Tools.optBool(a, "logical_only", false);
                    int limit = Tools.optInt(a, "limit", DEFAULT_LIMIT);
                    if (rightRef == null && rightDoc == null) {
                        return Tools.error("Provide 'right' (a loaded ontology IRI/version) or "
                                + "'right_document' (a document to load and compare against).");
                    }
                    // Load the comparison document OFF the EDT (network/parse), then diff on the EDT.
                    Set<OWLAxiom> rightAxioms = rightDoc != null
                            ? loadDocumentAxioms(rightDoc, includeImports, logicalOnly)
                            : null;
                    String rightLabel = rightDoc != null ? rightDoc : rightRef;
                    return ctx.access().compute(mm -> diff(mm, leftRef, rightRef, rightAxioms, rightLabel,
                            includeImports, logicalOnly, limit));
                }));
    }

    private static CallToolResult diff(OWLModelManager mm, String leftRef, String rightRef,
            Set<OWLAxiom> preloadedRight, String rightLabel, boolean includeImports, boolean logicalOnly,
            int limit) {
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
        return Tools.json()
                .put("left", labelOf(left.getOntologyID()))
                .put("right", rightLabel)
                .put("include_imports", includeImports)
                .put("logical_only", logicalOnly)
                .put("identical", identical)
                .put("left_axioms", leftAxioms.size())
                .put("right_axioms", rightAxioms.size())
                .put("common", leftAxioms.size() - d.onlyLeft.size())
                .put("only_in_left", Tools.axiomList(mm, d.onlyLeft, limit))
                .put("only_in_right", Tools.axiomList(mm, d.onlyRight, limit))
                .result();
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
        String normalized = OntologyDocumentTools.normalizeSource(source);
        OWLOntologyManager manager = OwlManagers.create();
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setFollowRedirects(true);
        OntologyDocumentTools.addFolderCatalogMapper(manager, normalized);
        try {
            OWLOntology o = manager.loadOntologyFromOntologyDocument(
                    OntologyDocumentTools.documentSource(normalized), config);
            return collect(o, closure, logicalOnly);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not load comparison document '" + normalized + "': "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static String labelOf(OWLOntologyID id) {
        return OntologyDocumentTools.ontologyLabel(id);
    }
}
