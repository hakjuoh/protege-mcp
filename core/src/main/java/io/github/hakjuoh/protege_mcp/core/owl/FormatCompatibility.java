package io.github.hakjuoh.protege_mcp.core.owl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Format serialization safeguards (PLAN §8.3): predict, before a target is replaced, whether the chosen
 * serialization format can represent the ontology without loss.
 *
 * <p>The table is deliberately conservative and empirically grounded against the shipped OWLAPI 4.5.29
 * storers, NOT against the abstract capabilities of each syntax. Every construct that a genuine
 * <em>file</em> round trip preserves is treated as lossless:
 *
 * <ul>
 *   <li><b>RDF/XML, Turtle, Functional, OWL/XML</b> are treated as lossless for OWL 2 DL and never
 *       flagged.</li>
 *   <li><b>OBO</b> preserves arbitrary logical axioms — general class axioms, SWRL rules, keys, disjoint
 *       unions and datatype definitions — through its {@code owl-axioms:} escape hatch, so those are NOT
 *       flagged. Its one genuine limit is the frame model: it rejects more than one single-valued frame
 *       tag per term, throwing a {@code FrameStructureException} for a second {@code rdfs:label} (name),
 *       {@code rdfs:comment} (comment) or {@code IAO_0000115} (def).</li>
 *   <li><b>Manchester</b> round-trips general class axioms, SWRL rules, keys and datatype definitions in
 *       this OWLAPI version (verified by a real {@code .omn} file round trip), so it is not flagged.</li>
 * </ul>
 *
 * <p>Formats are matched by their document-format class simple name so this stays dependency-clean (no
 * hard reference to the OBO storer module from {@code core}). All checks read axioms only — no live edit
 * — so callers run them off the EDT on a snapshot / private manager, and every emitted list is sorted so
 * the reports feed reproducible, digest-stable release artifacts.
 */
public final class FormatCompatibility {

    private FormatCompatibility() {
    }

    /** Longest sample list a report emits; exact counts are always reported alongside. */
    private static final int MAX_ISSUE_SAMPLES = 25;

    private static final String RECOMMENDATION =
            "use verify_round_trip or a lossless format (Turtle, RDF/XML, Functional, OWL/XML)";

    /** OBO def annotation property (single-valued frame tag). */
    private static final IRI OBO_DEFINITION =
            IRI.create("http://purl.obolibrary.org/obo/IAO_0000115");
    private static final IRI RDFS_LABEL = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
    private static final IRI RDFS_COMMENT = IRI.create("http://www.w3.org/2000/01/rdf-schema#comment");

    /** Deterministic ordering so truncated samples and the digest-hashed reports are reproducible. */
    private static final Comparator<OboIssue> ISSUE_ORDER = Comparator
            .comparing(OboIssue::kind)
            .thenComparing(issue -> issue.focusIri() == null ? "" : issue.focusIri())
            .thenComparing(OboIssue::detail);

    /** A predicted representational loss for a target serialization format. */
    public record LossyWarning(String format, List<String> reasons, String recommendation) {
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("format", format);
            json.put("reasons", reasons);
            json.put("recommendation", recommendation);
            return json;
        }
    }

    /** One OBO frame-model incompatibility, with a bounded focus. */
    public record OboIssue(String kind, String focusIri, String detail) {
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("kind", kind);
            if (focusIri != null) {
                json.put("focus_iri", focusIri);
            }
            json.put("detail", detail);
            return json;
        }
    }

    /** The OBO compatibility report: overall verdict, bounded issue samples, and exact counts. */
    public record OboCompatibility(boolean compatible, List<OboIssue> issues,
            Map<String, Object> counts) {
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("compatible", compatible);
            List<Map<String, Object>> issueJson = new ArrayList<>();
            for (OboIssue issue : issues) {
                issueJson.add(issue.toJson());
            }
            json.put("issues", issueJson);
            json.put("counts", counts);
            return json;
        }
    }

    /** True for the OBO document format (matched by class simple name, dependency-clean). */
    public static boolean isOboFormat(OWLDocumentFormat format) {
        return format != null && "OBODocumentFormat".equals(format.getClass().getSimpleName());
    }

    /**
     * Predict serialization loss for {@code ontology} into {@code format}. Returns {@code null} when the
     * format is lossless for OWL 2 DL, or when OBO can nonetheless represent this particular ontology
     * without loss (no false positives on clean content). Only the OBO frame model has a genuine,
     * structurally-detectable limit in this OWLAPI version.
     */
    public static LossyWarning detectLoss(OWLOntology ontology, OWLDocumentFormat format) {
        if (!isOboFormat(format)) {
            return null;
        }
        List<OboIssue> issues = collectOboIssues(ontology);
        if (issues.isEmpty()) {
            return null;
        }
        return new LossyWarning("OBODocumentFormat", oboReasons(issues), RECOMMENDATION);
    }

    /**
     * Report which entities the OBO frame model cannot represent (more than one single-valued frame tag
     * per term). Compatible content — including general class axioms, SWRL rules, keys and datatype
     * definitions, which survive via the {@code owl-axioms:} block — yields {@code compatible=true} with
     * no issues.
     */
    public static OboCompatibility oboCompatibility(OWLOntology ontology) {
        List<OboIssue> all = collectOboIssues(ontology);
        int total = all.size();
        List<OboIssue> issues = total > MAX_ISSUE_SAMPLES
                ? new ArrayList<>(all.subList(0, MAX_ISSUE_SAMPLES)) : all;
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total_issues", total);
        counts.put("multiple_valued_frame_tags", total);
        return new OboCompatibility(total == 0, issues, counts);
    }

    /** The full, deterministically-ordered OBO incompatibility list (sorted before any truncation). */
    private static List<OboIssue> collectOboIssues(OWLOntology ontology) {
        // Count single-valued frame tags per subject entity: >1 makes the OBO writer throw
        // FrameStructureException ('multiple <tag> tags not allowed').
        Map<IRI, Map<IRI, Integer>> tagCounts = new LinkedHashMap<>();
        for (OWLAnnotationAssertionAxiom ax : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            IRI property = ax.getProperty().getIRI();
            if (!isSingleValuedFrameTag(property) || !ax.getValue().asLiteral().isPresent()) {
                continue;
            }
            if (!(ax.getSubject() instanceof IRI subject)) {
                continue;   // anonymous subjects are not OBO term frames
            }
            tagCounts.computeIfAbsent(subject, k -> new LinkedHashMap<>())
                    .merge(property, 1, Integer::sum);
        }
        List<OboIssue> issues = new ArrayList<>();
        for (Map.Entry<IRI, Map<IRI, Integer>> entry : tagCounts.entrySet()) {
            for (Map.Entry<IRI, Integer> tag : entry.getValue().entrySet()) {
                if (tag.getValue() <= 1) {
                    continue;
                }
                issues.add(new OboIssue("multiple_" + frameTagName(tag.getKey()),
                        entry.getKey().toString(),
                        "Entity has " + tag.getValue() + " " + shortForm(tag.getKey())
                                + " values; the OBO frame model allows a single "
                                + frameTag(tag.getKey()) + " tag per term."));
            }
        }
        // Deterministic order independent of OWLAPI's per-JVM randomized axiom iteration (PLAN §3.3),
        // so truncated samples and the digest-hashed release reports stay reproducible.
        issues.sort(ISSUE_ORDER);
        return issues;
    }

    /** Distinct issue kinds, sorted, as human-readable reasons (deterministic). */
    private static List<String> oboReasons(List<OboIssue> issues) {
        Set<String> kinds = new TreeSet<>();
        for (OboIssue issue : issues) {
            kinds.add(issue.kind());
        }
        List<String> reasons = new ArrayList<>();
        for (String kind : kinds) {
            reasons.add(kind + ": not representable in the OBO frame model");
        }
        return reasons;
    }

    private static boolean isSingleValuedFrameTag(IRI property) {
        return RDFS_LABEL.equals(property) || RDFS_COMMENT.equals(property)
                || OBO_DEFINITION.equals(property);
    }

    /** The OBO issue-kind suffix ('labels' / 'comments' / 'definitions'). */
    private static String frameTagName(IRI property) {
        if (RDFS_LABEL.equals(property)) {
            return "labels";
        }
        if (RDFS_COMMENT.equals(property)) {
            return "comments";
        }
        return "definitions";
    }

    /** The OBO frame tag keyword ('name' / 'comment' / 'def'). */
    private static String frameTag(IRI property) {
        if (RDFS_LABEL.equals(property)) {
            return "'name'";
        }
        if (RDFS_COMMENT.equals(property)) {
            return "'comment'";
        }
        return "'def'";
    }

    private static String shortForm(IRI property) {
        if (RDFS_LABEL.equals(property)) {
            return "rdfs:label";
        }
        if (RDFS_COMMENT.equals(property)) {
            return "rdfs:comment";
        }
        return "IAO_0000115 (definition)";
    }
}
