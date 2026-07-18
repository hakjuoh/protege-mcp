package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.contracts.StageResult;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;
import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;
import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseCrate;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseManifest;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseReports;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The shared read-only orchestrator behind {@code run_release_gate} and {@code prepare_release} (PLAN
 * §9.3). It runs the strict project QC gate ({@link ProjectQcTools#run}), adds the release-only checks
 * (import provenance under ADR 0005 decision 2, version IRI, verified serialization round trip,
 * fingerprint stability, optional baseline comparison), aggregates every finding into ONE core
 * {@link GateResult}, and produces the deterministic manifest/report/crate <em>inputs</em>. It writes
 * nothing itself — {@code prepare_release} commits the material through {@link ArtifactStore}.
 *
 * <p>The clock is a seam: previews stamp a fixed {@link #PREVIEW} marker, never the wall clock; only a
 * committing {@code prepare_release} supplies a real {@code created_at}.
 */
final class ReleaseGate {

    /** Placeholder timestamp used everywhere a real {@code created_at} is not (yet) supplied. */
    static final String PREVIEW = "PREVIEW";

    private ReleaseGate() {
    }

    /** Parsed request arguments shared by both tools. */
    record Args(String policyPath, String network, String baselineManifest, int limit) {
    }

    /** One resolved import/closure member and how its document is backed. */
    private static final String LOCAL_FILE = "local_file";

    /**
     * The complete outcome: the aggregated core gate, the release envelope pieces, the deterministic
     * artifact material, and everything {@code prepare_release} needs to write and re-authorize.
     */
    static final class Outcome {
        final GateResult gate;
        final GateStatus status;
        final int limit;
        // Envelope pieces (all already JSON-shaped maps/lists).
        final List<Map<String, Object>> resolvedImports;
        final Map<String, Object> networkCaveat;      // nullable
        final String versionIri;                       // nullable
        final Map<String, Object> roundTrip;
        final Map<String, Object> baseline;
        // Manifest inputs.
        final String projectId;
        final String ontologyIri;                      // nullable
        final String policySha256;                     // nullable
        final String importLockSha256;                 // nullable
        final String semanticFingerprint;
        final boolean releaseStable;
        final String qcGate;
        // Serialized artifact material (null when the ontology could not be serialized cleanly).
        final byte[] ontologyBytes;
        final String ontologyArtifactPath;
        final String ontologyMediaType;
        final Map<String, Object> baselineDiff;        // nullable, becomes reports/diff.json
        // Authorization carried for prepare_release's output-dir write.
        final DirectAccessPolicy.Rules rules;
        final ProjectPolicy policy;

        private Outcome(Builder b) {
            this.gate = b.gate;
            this.status = b.gate.gate();
            this.limit = b.limit;
            this.resolvedImports = b.resolvedImports;
            this.networkCaveat = b.networkCaveat;
            this.versionIri = b.versionIri;
            this.roundTrip = b.roundTrip;
            this.baseline = b.baseline;
            this.projectId = b.projectId;
            this.ontologyIri = b.ontologyIri;
            this.policySha256 = b.policySha256;
            this.importLockSha256 = b.importLockSha256;
            this.semanticFingerprint = b.semanticFingerprint;
            this.releaseStable = b.releaseStable;
            this.qcGate = b.qcGate;
            this.ontologyBytes = b.ontologyBytes;
            this.ontologyArtifactPath = b.ontologyArtifactPath;
            this.ontologyMediaType = b.ontologyMediaType;
            this.baselineDiff = b.baselineDiff;
            this.rules = b.rules;
            this.policy = b.policy;
        }

        boolean passed() {
            return status == GateStatus.PASS;
        }

        /** Whether the manifest/reports/crate can be produced (stable fingerprint + a clean artifact). */
        boolean artifactsAvailable() {
            return releaseStable && ontologyBytes != null;
        }

        private static final class Builder {
            GateResult gate;
            int limit;
            List<Map<String, Object>> resolvedImports = List.of();
            Map<String, Object> networkCaveat;
            String versionIri;
            Map<String, Object> roundTrip;
            Map<String, Object> baseline;
            String projectId;
            String ontologyIri;
            String policySha256;
            String importLockSha256;
            String semanticFingerprint;
            boolean releaseStable;
            String qcGate;
            byte[] ontologyBytes;
            String ontologyArtifactPath;
            String ontologyMediaType;
            Map<String, Object> baselineDiff;
            DirectAccessPolicy.Rules rules;
            ProjectPolicy policy;

            Outcome build() {
                return new Outcome(this);
            }
        }
    }

    /** One deterministic bundled artifact: its release-relative path, media type, bytes and digest. */
    record Artifact(String path, String mediaType, byte[] content, String sha256, long bytes) {
    }

    // ================================================================== orchestration

    static Outcome evaluate(ToolContext ctx, McpSyncServerExchange exchange, Args args) {
        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, exchange, args.policyPath())
                .withRequestNetwork(args.network());
        ProjectPolicy policy = rules.policy();

        // Strict project QC (reuses the full policy validation + fail-closed import integrity path).
        Map<String, Object> qcArgs = new LinkedHashMap<>();
        if (args.policyPath() != null) {
            qcArgs.put("policy_path", args.policyPath());
        }
        qcArgs.put("limit", args.limit());
        qcArgs = rules.authorizedPolicyArguments(qcArgs);
        Map<String, Object> qc = structured(ProjectQcTools.run(ctx, qcArgs, true, rules));

        boolean qcRan = qc.get("stages") instanceof List<?> stages && !stages.isEmpty();
        String qcGate = str(qc.get("gate"));
        boolean releaseStable = Boolean.TRUE.equals(qc.get("release_stable"));

        // release.* policy knobs (read with defaults; NO schema change, so the policy digest is stable).
        Map<String, Object> release = object(policy.effective(), "release");
        String formatName = str(release.get("format"));
        if (formatName == null) {
            formatName = "turtle";
        }
        boolean requireVersionIri = !Boolean.FALSE.equals(release.get("require_version_iri"));
        boolean requireCleanRoundTrip = !Boolean.FALSE.equals(release.get("require_clean_round_trip"));
        FormatSpec formatSpec = formatSpec(formatName);

        // One model hop: version IRI, ontology IRI, the import closure, and the Protégé-free snapshot.
        Capture cap = ctx.access().compute(mm -> capture(mm, formatSpec.format()));

        // Provenance (ADR 0005 decision 2): list every closure member with its document source.
        List<Map<String, Object>> resolvedImports = provenance(cap.importReport);
        boolean networkAllowed = rules.importNetworkRule().allowed();

        List<Finding> releaseFindings = new ArrayList<>();
        Map<String, Object> networkCaveat = null;
        List<Map<String, Object>> remoteBacked = new ArrayList<>();
        for (Map<String, Object> member : resolvedImports) {
            if (!LOCAL_FILE.equals(member.get("backed_by"))) {
                remoteBacked.add(member);
            }
        }
        if (!remoteBacked.isEmpty()) {
            if (networkAllowed) {
                // network=allow abstains from denying: keep the members, record a caveat, no finding.
                List<String> members = new ArrayList<>();
                remoteBacked.forEach(m -> members.add(str(m.get("ontology_iri")) == null
                        ? str(m.get("document")) : str(m.get("ontology_iri"))));
                networkCaveat = new LinkedHashMap<>();
                networkCaveat.put("message", remoteBacked.size() + " closure member(s) are not backed "
                        + "by a local file; network=allow recorded this as a caveat rather than a gate "
                        + "error.");
                networkCaveat.put("members", members);
            } else {
                for (Map<String, Object> member : remoteBacked) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("document", member.get("document"));
                    details.put("source_type", member.get("source_type"));
                    releaseFindings.add(finding("imports.remote_backed", "imports",
                            FindingSeverity.ERROR,
                            "Closure member is not backed by an authorized local file: "
                                    + orAnon(member) + " (network=deny; every release closure member "
                                    + "must resolve to a local document).",
                            absoluteOrNull(str(member.get("ontology_iri"))), details));
                }
            }
        }

        // Version IRI policy.
        if (requireVersionIri && cap.versionIri == null) {
            releaseFindings.add(finding("release.version_iri_missing", "release", FindingSeverity.ERROR,
                    "The active ontology has no version IRI, but release.require_version_iri is true.",
                    null, Map.of()));
        }

        // Fingerprint stability blocks a stable manifest (ReleaseManifest also refuses it).
        if (!releaseStable) {
            releaseFindings.add(finding("release.fingerprint_unstable", "release", FindingSeverity.ERROR,
                    "The release fingerprint is not stable (" + str(qc.get("fingerprint_stability"))
                            + "); a reproducible manifest cannot be produced.", null,
                    Map.of("fingerprint_stability", nz(qc.get("fingerprint_stability")))));
        }

        // Verified serialization DRY-RUN (only meaningful with a stable, serializable ontology).
        Map<String, Object> roundTrip = new LinkedHashMap<>();
        roundTrip.put("format", formatName);
        byte[] ontologyBytes = null;
        if (!qcRan) {
            roundTrip.put("clean", false);
            roundTrip.put("skipped", true);
            roundTrip.put("reason", "project QC did not run (policy invalid or not loaded)");
        } else if (!releaseStable) {
            roundTrip.put("clean", false);
            roundTrip.put("skipped", true);
            roundTrip.put("reason", "fingerprint is not release-stable");
        } else {
            Serialized serialized = serialize(cap.snapshot, formatSpec.extension());
            roundTrip.put("clean", serialized.clean());
            if (serialized.clean()) {
                ontologyBytes = serialized.bytes();
            } else {
                roundTrip.put("error", serialized.error());
                if (requireCleanRoundTrip) {
                    releaseFindings.add(finding("release.round_trip_failed", "release",
                            FindingSeverity.ERROR,
                            "Verified serialization round trip was not exact: " + serialized.error(),
                            null, Map.of()));
                }
            }
        }

        // Optional baseline comparison (ASSERTED only; ADR 0004 defers inferred baseline diff).
        Map<String, Object> baseline = new LinkedHashMap<>();
        Map<String, Object> baselineDiff = null;
        if (args.baselineManifest() == null) {
            baseline.put("compared", false);
            baseline.put("status", "not_compared");
        } else {
            // v1 policy never REQUIRES a baseline, so a missing/mismatched baseline artifact stays
            // informational and adds no gate findings (ADR 0004: asserted-only, note-carrying).
            BaselineResult br = compareBaseline(rules, args, cap);
            baseline = br.summary();
            baselineDiff = br.diff();
        }

        // Adapt the QC result map into core stages + a synthetic required "release" stage, then
        // aggregate under strict semantics (error > fail > pass).
        GateResult gate = aggregate(qc, releaseFindings, qcGate);

        Outcome.Builder b = new Outcome.Builder();
        b.gate = gate;
        b.limit = args.limit();
        b.resolvedImports = resolvedImports;
        b.networkCaveat = networkCaveat;
        b.versionIri = cap.versionIri;
        b.roundTrip = roundTrip;
        b.baseline = baseline;
        b.projectId = str(policy.effective().get("project_id"));
        b.ontologyIri = cap.ontologyIri;
        b.policySha256 = policy.digest();
        b.importLockSha256 = RevisionTools.digestImportLock(policy);
        b.semanticFingerprint = str(qc.get("semantic_fingerprint"));
        b.releaseStable = releaseStable;
        b.qcGate = qcGate;
        b.ontologyBytes = ontologyBytes;
        b.ontologyArtifactPath = "ontology" + formatSpec.extension();
        b.ontologyMediaType = formatSpec.mediaType();
        b.baselineDiff = baselineDiff;
        b.rules = rules;
        b.policy = policy;
        return b.build();
    }

    // ================================================================== QC → core GateResult adapter

    /**
     * Adapt the {@code run_project_qc} result map into one core {@link GateResult}: every QC stage
     * becomes a {@link StageResult}, top-level orchestrator/fingerprint findings not attributable to a
     * QC stage plus the release-specific findings are collected into a synthetic required {@code
     * release} stage, and {@link GateResult#aggregate} recomputes the verdict. The QC gate is
     * faithfully reproduced (pinned by test); a release-blocking error forces the {@code release} stage
     * to {@link StageStatus#ERROR}, so it outranks any QC {@code fail}/{@code pass}.
     */
    @SuppressWarnings("unchecked")
    static GateResult aggregate(Map<String, Object> qc, List<Finding> releaseFindings, String qcGate) {
        int policyVersion = qc.get("policy_version") instanceof Number n ? n.intValue() : 1;
        String fingerprint = str(qc.get("semantic_fingerprint"));
        FindingSeverity failOn = failOn(qc);

        List<StageResult> stages = new ArrayList<>();
        List<Map<String, Object>> stageMaps = qc.get("stages") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        List<Finding> stageFindings = new ArrayList<>();
        boolean anyQcStageError = false;
        for (Map<String, Object> stage : stageMaps) {
            String name = str(stage.get("stage"));
            StageStatus status = StageStatus.fromJson(str(stage.get("status")));
            anyQcStageError |= status == StageStatus.ERROR;
            List<Finding> findings = new ArrayList<>();
            if (stage.get("findings") instanceof List<?> fs) {
                for (Object f : fs) {
                    if (f instanceof Map<?, ?> map) {
                        Finding finding = toFinding((Map<String, Object>) map);
                        findings.add(finding);
                        stageFindings.add(finding);
                    }
                }
            }
            String message = str(stage.get("message"));
            if ((status == StageStatus.ERROR || status == StageStatus.SKIPPED)
                    && (message == null || message.isBlank())) {
                message = name + " " + status.json();
            }
            stages.add(new StageResult(name, status, message,
                    findings, object(stage.get("details"))));
        }

        // Orphan QC findings: top-level findings not present inside any stage (snapshot/precondition/
        // fingerprint). Fold them into the release stage so nothing is lost from the reports.
        List<Finding> releaseStageFindings = new ArrayList<>();
        if (qc.get("findings") instanceof List<?> topFindings) {
            for (Object f : topFindings) {
                if (f instanceof Map<?, ?> map) {
                    Finding finding = toFinding((Map<String, Object>) map);
                    if (!containsFinding(stageFindings, finding)) {
                        releaseStageFindings.add(finding);
                    }
                }
            }
        }
        releaseStageFindings.addAll(releaseFindings);

        boolean releaseError = "error".equals(qcGate)
                || releaseStageFindings.stream().anyMatch(f -> f.severity() == FindingSeverity.ERROR);
        StageResult releaseStage;
        if (releaseError) {
            releaseStage = new StageResult("release", StageStatus.ERROR,
                    "release checks reported a blocking error", releaseStageFindings, Map.of());
        } else {
            releaseStage = new StageResult("release", StageStatus.PASS, null,
                    releaseStageFindings, Map.of());
        }
        stages.add(releaseStage);

        Set<String> required = new LinkedHashSet<>();
        if (qc.get("required_stages") instanceof List<?> req) {
            req.forEach(s -> required.add(String.valueOf(s)));
        }
        required.add("release");
        return GateResult.aggregate(policyVersion, fingerprint, new ArrayList<>(required), stages,
                failOn);
    }

    private static FindingSeverity failOn(Map<String, Object> qc) {
        Object details = qc.get("details");
        String value = details instanceof Map<?, ?> map ? str(((Map<String, Object>) map).get("fail_on"))
                : str(qc.get("fail_on"));
        if (value == null || "none".equals(value)) {
            // A release must gate at least at error; a policy with fail_on=none still blocks on the
            // release-specific error findings.
            return FindingSeverity.ERROR;
        }
        if ("warn".equals(value)) {
            return FindingSeverity.WARNING;
        }
        return FindingSeverity.fromJson(value);
    }

    private static Finding toFinding(Map<String, Object> f) {
        return new Finding(str(f.get("id")), str(f.get("source")),
                FindingSeverity.fromJson(str(f.get("severity"))), str(f.get("message")),
                absoluteOrNull(str(f.get("focus_iri"))), str(f.get("axiom")), str(f.get("path")),
                str(f.get("rule_id")), object(f.get("waiver")).isEmpty() ? null : object(f.get("waiver")),
                object(f.get("details")));
    }

    private static boolean containsFinding(List<Finding> haystack, Finding needle) {
        for (Finding f : haystack) {
            if (f.id().equals(needle.id()) && f.message().equals(needle.message())
                    && java.util.Objects.equals(f.focusIri(), needle.focusIri())) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== provenance + baseline

    static List<Map<String, Object>> provenance(ImportTools.ImportReport report) {
        List<Map<String, Object>> members = new ArrayList<>();
        if (report == null) {
            return members;
        }
        for (Map<String, Object> ontology : report.ontologies) {
            if ("root".equals(ontology.get("role"))) {
                continue;
            }
            String sourceType = str(ontology.get("source_type"));
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("ontology_iri", ontology.get("ontology_iri"));
            member.put("version_iri", ontology.get("version_iri"));
            member.put("document", ontology.get("document_iri"));
            member.put("source_type", sourceType);
            member.put("backed_by", "local".equals(sourceType) ? LOCAL_FILE : sourceType);
            members.add(member);
        }
        return members;
    }

    private record BaselineResult(Map<String, Object> summary, Map<String, Object> diff) {
    }

    @SuppressWarnings("unchecked")
    private static BaselineResult compareBaseline(DirectAccessPolicy.Rules rules, Args args,
            Capture cap) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("compared", false);
        try {
            Path manifestPath = rules.readPath(args.baselineManifest());
            Map<String, Object> manifest = ContractJson.mapper().readValue(
                    Files.readAllBytes(manifestPath),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            summary.put("manifest", manifestPath.toString());
            summary.put("version_iri", manifest.get("version_iri"));
            Path manifestDir = manifestPath.toAbsolutePath().getParent();

            // Verify each recorded artifact digest against the file beside the manifest (informational:
            // v1 policy does not require a baseline, so a missing/mismatched entry never gates).
            // SECURITY: a manifest-supplied artifact path is untrusted input. Re-authorize EVERY path
            // through the same request rules (canonical containment + symlink pinning) BEFORE any disk
            // access, so a "/etc/hosts" or "../secret.ttl" entry can never turn the sha256 check into a
            // presence/hash oracle, nor the primary-artifact load into a content-exfiltration read.
            List<Map<String, Object>> artifactChecks = new ArrayList<>();
            boolean sawArtifact = false;
            Path primaryAuthorized = null;
            boolean primarySeen = false;
            if (manifest.get("artifacts") instanceof List<?> artifacts) {
                for (Object entry : artifacts) {
                    if (!(entry instanceof Map<?, ?> map)) {
                        continue;
                    }
                    Map<String, Object> artifact = (Map<String, Object>) map;
                    String path = str(artifact.get("path"));
                    sawArtifact = true;
                    Path authorized = authorizeBaselineArtifact(rules, manifestDir, path);
                    if (!primarySeen) {
                        primarySeen = true;
                        primaryAuthorized = authorized;
                    }
                    Map<String, Object> check = new LinkedHashMap<>();
                    check.put("path", path);
                    if (authorized == null) {
                        // Refused before touching disk: no present/hash oracle for an escaping path.
                        check.put("authorized", false);
                        check.put("note", "artifact path is outside the project and was not read");
                        artifactChecks.add(check);
                        continue;
                    }
                    check.put("authorized", true);
                    if (Files.isRegularFile(authorized)) {
                        String actual = ArtifactStore.sha256(authorized);
                        boolean match = actual.equalsIgnoreCase(str(artifact.get("sha256")));
                        check.put("present", true);
                        check.put("sha256_match", match);
                    } else {
                        check.put("present", false);
                        check.put("sha256_match", false);
                    }
                    artifactChecks.add(check);
                }
            }
            summary.put("artifact_checks", artifactChecks);

            if (!sawArtifact || manifestDir == null) {
                summary.put("compared", false);
                summary.put("status", "no_primary_artifact");
                summary.put("note", "The baseline manifest lists no artifact to diff against.");
                return new BaselineResult(summary, null);
            }
            if (primaryAuthorized == null) {
                summary.put("compared", false);
                summary.put("status", "primary_artifact_refused");
                summary.put("note", "The baseline primary artifact path is outside the project and "
                        + "was not read.");
                return new BaselineResult(summary, null);
            }
            Path primary = primaryAuthorized;
            if (!Files.isRegularFile(primary)) {
                summary.put("compared", false);
                summary.put("status", "primary_artifact_missing");
                summary.put("note", "Baseline artifact not found beside the manifest: " + primary);
                return new BaselineResult(summary, null);
            }
            // Load the baseline document with the network denied; asserted diff only (ADR 0004).
            List<String> unresolved = new ArrayList<>();
            OWLOntology baselineOntology = DiffTools.loadDocument(primary.toString(), List.of(),
                    unresolved, new DirectAccessPolicy.NetworkRule(false, Set.of(), false, false));
            Map<String, Object> diff = SemanticDiffService.diff(cap.snapshot.ontology(),
                    baselineOntology, false, args.limit(), unresolved);
            summary.put("compared", true);
            summary.put("status", "compared");
            summary.put("mode", "asserted");
            summary.put("note", "Asserted-axiom diff only; inferred baseline diff is deferred (ADR 0004).");
            summary.put("compatibility", diff.get("compatibility"));
            summary.put("identical", diff.get("identical"));
            return new BaselineResult(summary, diff);
        } catch (ToolArgException e) {
            summary.put("status", "error");
            summary.put("error", e.getMessage());
            return new BaselineResult(summary, null);
        } catch (IOException | RuntimeException e) {
            summary.put("status", "error");
            summary.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return new BaselineResult(summary, null);
        }
    }

    /**
     * Authorize an untrusted, manifest-supplied artifact path: resolve it against the (already
     * contained) manifest directory, then run it back through the request rules — which enforce
     * canonical containment under {@code project_root} and pin symlinks. Returns the authorized
     * canonical path, or {@code null} when the entry escapes the project (absolute, {@code ..}, or a
     * symlinked child) so the caller refuses it WITHOUT ever touching disk.
     */
    private static Path authorizeBaselineArtifact(DirectAccessPolicy.Rules rules, Path manifestDir,
            String path) {
        if (path == null || path.isBlank() || manifestDir == null) {
            return null;
        }
        try {
            Path resolved = manifestDir.resolve(path).normalize();
            return rules.readPath(resolved.toString());
        } catch (java.nio.file.InvalidPathException | ToolArgException refused) {
            return null;
        }
    }

    // ================================================================== model-thread capture

    private static final class Capture {
        final String ontologyIri;
        final String versionIri;
        final ImportTools.ImportReport importReport;
        final VerifiedOntologyWriter.Snapshot snapshot;

        Capture(String ontologyIri, String versionIri, ImportTools.ImportReport importReport,
                VerifiedOntologyWriter.Snapshot snapshot) {
            this.ontologyIri = ontologyIri;
            this.versionIri = versionIri;
            this.importReport = importReport;
            this.snapshot = snapshot;
        }
    }

    private static Capture capture(OWLModelManager mm, OWLDocumentFormat format) {
        OWLOntology active = mm.getActiveOntology();
        OWLOntologyID id = active.getOntologyID();
        String ontologyIri = id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : null;
        String versionIri = id.getVersionIRI().isPresent() ? id.getVersionIRI().get().toString() : null;
        OWLDocumentFormat current = mm.getOWLOntologyManager().getOntologyFormat(active);
        if (format.isPrefixOWLOntologyFormat() && current != null
                && current.isPrefixOWLOntologyFormat()) {
            format.asPrefixOWLOntologyFormat().copyPrefixesFrom(current.asPrefixOWLOntologyFormat());
        }
        ImportTools.ImportReport report = ImportTools.analyze(active);
        VerifiedOntologyWriter.Snapshot snapshot = VerifiedOntologyWriter.snapshot(active, format);
        return new Capture(ontologyIri, versionIri, report, snapshot);
    }

    // ================================================================== verified serialization

    private record Serialized(byte[] bytes, String sha256, boolean clean, String error) {
    }

    /**
     * Serialize the Protégé-free snapshot to a throwaway temp directory and verify the exact round trip
     * using the shipped {@link VerifiedOntologyWriter}. Nothing lands in the workspace: the temp
     * directory is deleted regardless of outcome, so run_release_gate remains write-free.
     */
    private static Serialized serialize(VerifiedOntologyWriter.Snapshot snapshot, String extension) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("protege-mcp-release-");
            Path target = dir.resolve("ontology" + extension);
            try (VerifiedOntologyWriter.Prepared prepared =
                    VerifiedOntologyWriter.prepare(snapshot, target)) {
                prepared.install(false, false);
                byte[] bytes = Files.readAllBytes(target);
                return new Serialized(bytes, ArtifactStore.sha256(bytes), true, null);
            }
        } catch (ToolArgException e) {
            return new Serialized(null, null, false, e.getMessage());
        } catch (IOException e) {
            return new Serialized(null, null, false,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: an owner-only OS temp entry is not a release artifact.
                }
            });
        } catch (IOException ignored) {
            // The temp tree may already be gone.
        }
    }

    // ================================================================== deterministic artifact set

    /**
     * The complete, deterministic bundle for a passing release. Order is acyclic: the ontology and the
     * four reports (plus optional diff) are hashed first, the RO-Crate lists those files, and the
     * manifest indexes everything except itself. {@code createdAt} is the only non-reproducible input.
     */
    static List<Artifact> artifacts(Outcome outcome, String createdAt) {
        List<Artifact> files = new ArrayList<>();
        files.add(artifact(outcome.ontologyArtifactPath, outcome.ontologyMediaType,
                outcome.ontologyBytes));
        files.add(textArtifact("reports/qc.json", "application/json",
                ReleaseReports.json(outcome.gate)));
        files.add(textArtifact("reports/qc.md", "text/markdown",
                ReleaseReports.markdown(outcome.gate, outcome.versionIri, outcome.limit)));
        files.add(textArtifact("reports/qc.xml", "application/xml",
                ReleaseReports.junit(outcome.gate, createdAt)));
        // SARIF URIs are repo-root-relative: pass the release ontology artifact's RELATIVE name as the
        // fallback (never the absolute policy path, which would leak the local directory layout).
        files.add(textArtifact("reports/qc.sarif", "application/sarif+json",
                ReleaseReports.sarifJson(outcome.gate, outcome.ontologyArtifactPath)));
        if (outcome.baselineDiff != null) {
            files.add(textArtifact("reports/diff.json", "application/json",
                    toJson(outcome.baselineDiff)));
        }

        List<ReleaseCrate.CrateFile> crateFiles = new ArrayList<>();
        for (Artifact a : files) {
            crateFiles.add(new ReleaseCrate.CrateFile(a.path(), a.mediaType(), a.sha256(), a.bytes()));
        }
        String crateJson = ReleaseCrate.toJson(ReleaseCrate.build(outcome.projectId, null,
                outcome.versionIri, createdAt, outcome.ontologyArtifactPath, crateFiles));
        files.add(textArtifact("ro-crate-metadata.json", "application/ld+json", crateJson));

        List<ReleaseManifest.Artifact> manifestArtifacts = new ArrayList<>();
        for (Artifact a : files) {
            manifestArtifacts.add(new ReleaseManifest.Artifact(a.path(), a.sha256(), a.bytes()));
        }
        ReleaseManifest.Baseline baseline = outcome.baselineDiff != null
                ? new ReleaseManifest.Baseline(str(outcome.baseline.get("version_iri")),
                        "reports/diff.json")
                : null;
        Map<String, Object> manifest = ReleaseManifest.build(outcome.projectId, outcome.ontologyIri,
                outcome.versionIri, createdAt, outcome.policySha256, outcome.importLockSha256,
                outcome.semanticFingerprint, outcome.releaseStable, manifestArtifacts,
                outcome.gate.gate().json(), "reports/qc.json", baseline);
        files.add(textArtifact("manifest.json", "application/json", ReleaseManifest.toJson(manifest)));
        return files;
    }

    /** The manifest map preview (used by run_release_gate and prepare_release dry-run). */
    static Map<String, Object> manifestPreview(Outcome outcome, String createdAt) {
        List<ReleaseManifest.Artifact> manifestArtifacts = new ArrayList<>();
        for (Artifact a : artifacts(outcome, createdAt)) {
            if (!"manifest.json".equals(a.path())) {
                manifestArtifacts.add(new ReleaseManifest.Artifact(a.path(), a.sha256(), a.bytes()));
            }
        }
        ReleaseManifest.Baseline baseline = outcome.baselineDiff != null
                ? new ReleaseManifest.Baseline(str(outcome.baseline.get("version_iri")),
                        "reports/diff.json")
                : null;
        return ReleaseManifest.build(outcome.projectId, outcome.ontologyIri, outcome.versionIri,
                createdAt, outcome.policySha256, outcome.importLockSha256, outcome.semanticFingerprint,
                outcome.releaseStable, manifestArtifacts, outcome.gate.gate().json(),
                "reports/qc.json", baseline);
    }

    private static Artifact artifact(String path, String mediaType, byte[] content) {
        return new Artifact(path, mediaType, content, ArtifactStore.sha256(content), content.length);
    }

    private static Artifact textArtifact(String path, String mediaType, String content) {
        return artifact(path, mediaType,
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ================================================================== formats + helpers

    private record FormatSpec(OWLDocumentFormat format, String extension, String mediaType) {
    }

    private static FormatSpec formatSpec(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "turtle" -> new FormatSpec(new TurtleDocumentFormat(), ".ttl", "text/turtle");
            case "rdfxml" -> new FormatSpec(new RDFXMLDocumentFormat(), ".owl", "application/rdf+xml");
            case "functional" -> new FormatSpec(new FunctionalSyntaxDocumentFormat(), ".ofn",
                    "application/owl-functional");
            case "owlxml" -> new FormatSpec(new OWLXMLDocumentFormat(), ".owx", "application/owl+xml");
            case "obo" -> new FormatSpec(new OBODocumentFormat(), ".obo", "text/obo");
            default -> throw new ToolArgException("Unsupported release.format '" + name + "'.");
        };
    }

    private static Finding finding(String id, String source, FindingSeverity severity, String message,
            String focusIri, Map<String, Object> details) {
        return new Finding(id, source, severity, message, focusIri, null, null, id, null,
                details == null ? Map.of() : details);
    }

    static String toJson(Map<String, Object> map) {
        try {
            return ContractJson.mapper().copy()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render release diff report", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> structured(CallToolResult result) {
        Object content = result.structuredContent();
        return content instanceof Map ? (Map<String, Object>) content : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nz(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String orAnon(Map<String, Object> member) {
        String iri = str(member.get("ontology_iri"));
        if (iri != null) {
            return iri;
        }
        String document = str(member.get("document"));
        return document == null ? "(anonymous ontology)" : document;
    }

    private static String absoluteOrNull(String iri) {
        if (iri == null) {
            return null;
        }
        return Tools.asIri(iri) == null ? null : iri;
    }
}
