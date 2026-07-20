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
import java.util.Objects;
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

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;
import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;
import io.github.hakjuoh.protege_mcp.core.owl.FormatCompatibility;
import io.github.hakjuoh.protege_mcp.core.owl.VerifiedOntologyRoundTrip;
import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseBundleService;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseGateService;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseManifest;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The shared read-only orchestrator behind {@code run_release_gate} and {@code prepare_release}. It runs
 * the strict project QC gate ({@link ProjectQcTools#run}), adds the release-only checks
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
    static final long MAX_BASELINE_MANIFEST_BYTES = 1_048_576L;
    static final int MAX_BASELINE_ARTIFACTS = 256;
    /** The primary baseline artifact is verified and parsed from ONE in-memory array (JVM array bound). */
    static final long MAX_BASELINE_PRIMARY_BYTES = Integer.MAX_VALUE - 8L;

    private ReleaseGate() {
    }

    /** Parsed request arguments shared by both tools. */
    record Args(String policyPath, String network, String lockMode, String baselineManifest,
            int limit) {
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
        final String network;
        final String lockMode;
        // Envelope pieces (all already JSON-shaped maps/lists).
        final List<Map<String, Object>> resolvedImports;
        final Map<String, Object> networkCaveat;      // nullable
        final String versionIri;                       // nullable
        final Map<String, Object> roundTrip;
        final Map<String, Object> baseline;
        // Manifest inputs.
        final String projectId;
        final String ontologyIri;                      // nullable
        final Object license;                          // reviewed project RO-Crate value
        final String policySha256;                     // nullable
        final String importLockSha256;                 // nullable
        final String semanticFingerprint;
        final boolean releaseStable;
        final boolean releaseInputsAligned;
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
            this.network = b.network;
            this.lockMode = b.lockMode;
            this.resolvedImports = b.resolvedImports;
            this.networkCaveat = b.networkCaveat;
            this.versionIri = b.versionIri;
            this.roundTrip = b.roundTrip;
            this.baseline = b.baseline;
            this.projectId = b.projectId;
            this.ontologyIri = b.ontologyIri;
            this.license = b.license;
            this.policySha256 = b.policySha256;
            this.importLockSha256 = b.importLockSha256;
            this.semanticFingerprint = b.semanticFingerprint;
            this.releaseStable = b.releaseStable;
            this.releaseInputsAligned = b.releaseInputsAligned;
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
            String network;
            String lockMode;
            List<Map<String, Object>> resolvedImports = List.of();
            Map<String, Object> networkCaveat;
            String versionIri;
            Map<String, Object> roundTrip;
            Map<String, Object> baseline;
            String projectId;
            String ontologyIri;
            Object license;
            String policySha256;
            String importLockSha256;
            String semanticFingerprint;
            boolean releaseStable;
            boolean releaseInputsAligned;
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
        // Release surfaces are deny-by-default even when the interactive project policy permits
        // network access (ADR 0005 decision 2). Only an explicit network=allow abstains from this
        // release-level denial, and it still cannot override policy/capability restrictions.
        String releaseNetwork = args.network() == null || args.network().isBlank()
                ? "deny" : args.network().trim().toLowerCase(Locale.ROOT);
        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, exchange, args.policyPath())
                .withRequestNetwork(releaseNetwork);
        ProjectPolicy policy = rules.policy();
        String preflightBefore = policy.loaded() ? RevisionTools.preflightDigest(policy) : null;
        String importLockBefore = RevisionTools.digestImportLock(policy);

        // Strict project QC (reuses the full policy validation + fail-closed import integrity path).
        Map<String, Object> qcArgs = new LinkedHashMap<>();
        if (args.policyPath() != null) {
            qcArgs.put("policy_path", args.policyPath());
        }
        qcArgs.put("limit", args.limit());
        if (args.lockMode() != null) {
            qcArgs.put("lock_mode", args.lockMode());
        }
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

        // Project QC and release capture are necessarily separate orchestration steps today. Prove
        // they observed the same ontology and the same policy/asset inputs before combining QC's
        // fingerprint with the serialized artifact. Otherwise a GUI edit or policy/shape replacement
        // between the steps could produce a self-contradictory manifest.
        String capturedFingerprint = OntologyFingerprints.compute(cap.snapshot.ontology())
                .semanticFingerprint();
        String qcFingerprint = str(qc.get("semantic_fingerprint"));
        Object releaseLicense = releaseLicense(policy);
        String preflightAfter = policy.loaded() ? RevisionTools.preflightDigest(policy) : null;
        String importLockAfter = RevisionTools.digestImportLock(policy);
        boolean releaseInputsAligned = Objects.equals(qcFingerprint, capturedFingerprint)
                && Objects.equals(policy.digest(), str(qc.get("policy_digest")))
                && Objects.equals(preflightBefore, preflightAfter)
                && Objects.equals(importLockBefore, importLockAfter);

        // Provenance (ADR 0005 decision 2): list every closure member with its document source.
        List<Map<String, Object>> resolvedImports = provenance(cap.importReport);
        boolean networkAllowed = rules.importNetworkRule().allowed();

        List<Finding> releaseFindings = new ArrayList<>();
        if (!releaseInputsAligned) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("qc_semantic_fingerprint", qcFingerprint);
            details.put("captured_semantic_fingerprint", capturedFingerprint);
            details.put("qc_policy_digest", qc.get("policy_digest"));
            details.put("captured_policy_digest", policy.digest());
            details.put("policy_assets_stable", Objects.equals(preflightBefore, preflightAfter));
            details.put("import_lock_stable", Objects.equals(importLockBefore, importLockAfter));
            releaseFindings.add(finding("release.snapshot_changed", "release",
                    FindingSeverity.ERROR,
                    "The ontology, project policy, or validation assets changed while the release gate "
                            + "was running; QC evidence and serialization were not combined. Retry the "
                            + "gate on a stable workspace.", null, details));
        }
        if (releaseLicense == null) {
            releaseFindings.add(finding("release.license_unavailable", "release",
                    FindingSeverity.ERROR,
                    "The validated project RO-Crate license could not be carried into the release "
                            + "crate; no license was invented. Restore valid interoperability metadata "
                            + "and retry.", null, Map.of()));
        }
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
        } else if (!releaseInputsAligned) {
            roundTrip.put("clean", false);
            roundTrip.put("skipped", true);
            roundTrip.put("reason", "release inputs changed between QC and serialization capture");
        } else {
            Serialized serialized = serialize(cap.snapshot);
            roundTrip.put("clean", serialized.clean());
            if (serialized.clean()) {
                ontologyBytes = serialized.bytes();
                // Distinguish byte-for-byte from axiom-identical reproducibility.
                roundTrip.put("round_trip_class", serialized.roundTripClass());
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

        // Format loss safeguards: the OBO compatibility report and the default lossy-format
        // warning surface here BEFORE any replacement. In release mode a predicted loss is a gate error
        // (strict) when require_clean_round_trip holds; otherwise a warning finding.
        OWLDocumentFormat releaseFormat = cap.snapshot.format();
        if (FormatCompatibility.isOboFormat(releaseFormat)) {
            roundTrip.put("obo_compatibility",
                    FormatCompatibility.oboCompatibility(cap.snapshot.ontology()).toJson());
        }
        FormatCompatibility.LossyWarning releaseLossy =
                FormatCompatibility.detectLoss(cap.snapshot.ontology(), releaseFormat);
        if (releaseLossy != null) {
            roundTrip.put("lossy_format", releaseLossy.toJson());
            FindingSeverity severity = requireCleanRoundTrip
                    ? FindingSeverity.ERROR : FindingSeverity.WARNING;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("format", formatName);
            details.put("reasons", releaseLossy.reasons());
            releaseFindings.add(finding("release.lossy_format", "release", severity,
                    "The release format '" + formatName + "' cannot represent the ontology without "
                            + "loss: " + String.join("; ", releaseLossy.reasons()), null, details));
        }

        // Optional baseline comparison (ASSERTED only; ADR 0004 defers inferred baseline diff).
        Map<String, Object> baseline = new LinkedHashMap<>();
        Map<String, Object> baselineDiff = null;
        if (args.baselineManifest() == null) {
            baseline.put("compared", false);
            baseline.put("status", "not_compared");
        } else {
            // Supplying a baseline is an explicit request to use it as release evidence. Once
            // requested, malformed or unverifiable evidence fails closed rather than silently
            // producing a release that omitted the requested comparison.
            BaselineResult br = compareBaseline(rules, args, cap);
            baseline = br.summary();
            baselineDiff = br.diff();
            if (!"compared".equals(baseline.get("status"))) {
                releaseFindings.add(finding("release.baseline_invalid", "release",
                        FindingSeverity.ERROR,
                        "The requested baseline release could not be verified and compared (status: "
                                + baseline.get("status") + ").", null,
                        Map.of("status", nz(baseline.get("status")))));
            }
        }

        // Adapt the QC result map into core stages + a synthetic required "release" stage, then
        // aggregate under strict semantics (error > fail > pass).
        GateResult gate = aggregate(qc, releaseFindings, qcGate);

        Outcome.Builder b = new Outcome.Builder();
        b.gate = gate;
        b.limit = args.limit();
        b.network = releaseNetwork;
        b.lockMode = args.lockMode() == null ? LockMode.IGNORE.value() : args.lockMode();
        b.resolvedImports = resolvedImports;
        b.networkCaveat = networkCaveat;
        b.versionIri = cap.versionIri;
        b.roundTrip = roundTrip;
        b.baseline = baseline;
        b.projectId = str(policy.effective().get("project_id"));
        b.ontologyIri = cap.ontologyIri;
        b.license = releaseLicense;
        b.policySha256 = policy.digest();
        b.importLockSha256 = importLockAfter;
        b.semanticFingerprint = str(qc.get("semantic_fingerprint"));
        b.releaseStable = releaseStable && releaseInputsAligned;
        b.releaseInputsAligned = releaseInputsAligned;
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

    /** Plugin compatibility seam over the Protégé-free release aggregation service. */
    static GateResult aggregate(Map<String, Object> qc, List<Finding> releaseFindings, String qcGate) {
        return ReleaseGateService.aggregate(qc, releaseFindings, qcGate);
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
            if (!Files.isRegularFile(manifestPath)) {
                summary.put("status", "manifest_missing");
                summary.put("error", "Baseline manifest is not a regular file: " + manifestPath);
                return new BaselineResult(summary, null);
            }
            // ONE bounded read: checking Files.size first and re-reading afterwards would let a
            // concurrently growing manifest slip past the cap between the two calls.
            byte[] manifestData;
            try (var in = Files.newInputStream(manifestPath)) {
                manifestData = in.readNBytes((int) MAX_BASELINE_MANIFEST_BYTES + 1);
            }
            if (manifestData.length > MAX_BASELINE_MANIFEST_BYTES) {
                summary.put("status", "manifest_too_large");
                summary.put("error", "Baseline manifest exceeds the maximum of "
                        + MAX_BASELINE_MANIFEST_BYTES + " bytes.");
                return new BaselineResult(summary, null);
            }
            Map<String, Object> manifest = ContractJson.mapper().readValue(manifestData,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            if (!(manifest.get("manifest_version") instanceof Integer version)
                    || version != ReleaseManifest.MANIFEST_VERSION) {
                summary.put("status", "manifest_version_unsupported");
                summary.put("error", "Baseline manifest_version must be "
                        + ReleaseManifest.MANIFEST_VERSION + ".");
                return new BaselineResult(summary, null);
            }
            summary.put("manifest", manifestPath.toString());
            summary.put("version_iri", manifest.get("version_iri"));
            Path manifestDir = manifestPath.toAbsolutePath().getParent();

            // Verify each recorded artifact digest against the file beside the manifest.
            // SECURITY: a manifest-supplied artifact path is untrusted input. Re-authorize EVERY path
            // through the same request rules (canonical containment + symlink pinning) BEFORE any disk
            // access, so a "/etc/hosts" or "../secret.ttl" entry can never turn the sha256 check into a
            // presence/hash oracle, nor the primary-artifact load into a content-exfiltration read.
            List<Map<String, Object>> artifactChecks = new ArrayList<>();
            boolean sawArtifact = false;
            Path primaryAuthorized = null;
            byte[] primaryBytes = null;
            boolean primarySeen = false;
            String primaryRefusal = null;
            boolean allArtifactsVerified = true;
            if (manifest.get("artifacts") instanceof List<?> artifacts) {
                if (artifacts.size() > MAX_BASELINE_ARTIFACTS) {
                    summary.put("status", "too_many_artifacts");
                    summary.put("error", "Baseline manifest lists " + artifacts.size()
                            + " artifacts; maximum is " + MAX_BASELINE_ARTIFACTS + ".");
                    return new BaselineResult(summary, null);
                }
                Set<String> seenPaths = new LinkedHashSet<>();
                for (Object entry : artifacts) {
                    if (!(entry instanceof Map<?, ?> map)) {
                        allArtifactsVerified = false;
                        continue;
                    }
                    Map<String, Object> artifact = (Map<String, Object>) map;
                    String path = str(artifact.get("path"));
                    sawArtifact = true;
                    if (path == null || path.isBlank() || !seenPaths.add(path)) {
                        allArtifactsVerified = false;
                        Map<String, Object> check = new LinkedHashMap<>();
                        check.put("path", path);
                        check.put("authorized", false);
                        String note = path == null || path.isBlank()
                                ? "artifact path is missing" : "duplicate artifact path";
                        check.put("note", note);
                        artifactChecks.add(check);
                        if (!primarySeen) {
                            primarySeen = true;
                            primaryRefusal = "The baseline primary artifact entry is invalid ("
                                    + note + ").";
                        }
                        continue;
                    }
                    boolean isPrimary = !primarySeen;
                    Path authorized = authorizeBaselineArtifact(rules, manifestDir, path);
                    if (isPrimary) {
                        primarySeen = true;
                        primaryAuthorized = authorized;
                        if (authorized == null) {
                            primaryRefusal = "The baseline primary artifact path is outside the "
                                    + "project and was not read.";
                        }
                    }
                    Map<String, Object> check = new LinkedHashMap<>();
                    check.put("path", path);
                    if (authorized == null) {
                        allArtifactsVerified = false;
                        // Refused before touching disk: no present/hash oracle for an escaping path.
                        check.put("authorized", false);
                        check.put("note", "artifact path is outside the project and was not read");
                        artifactChecks.add(check);
                        continue;
                    }
                    check.put("authorized", true);
                    if (Files.isRegularFile(authorized)) {
                        Object recordedBytes = artifact.get("bytes");
                        long recorded = recordedBytes instanceof Integer
                                || recordedBytes instanceof Long
                                        ? ((Number) recordedBytes).longValue() : -1L;
                        if (isPrimary && (recorded < 0 || recorded > MAX_BASELINE_PRIMARY_BYTES)) {
                            // Refuse before allocating: the primary is verified and parsed from ONE
                            // in-memory array, so an absent or array-exceeding recorded length must
                            // fail closed as unverifiable, never crash on the allocation.
                            check.put("present", true);
                            check.put("sha256_match", false);
                            check.put("bytes_match", false);
                            check.put("note", recorded < 0
                                    ? "recorded byte length is missing or not an integer"
                                    : "primary artifact exceeds the in-memory verification bound of "
                                            + MAX_BASELINE_PRIMARY_BYTES + " bytes");
                            allArtifactsVerified = false;
                            artifactChecks.add(check);
                            continue;
                        }
                        // The primary artifact is diffed after this check, so hash and measure the
                        // ONE byte array the diff will parse — hashing the path and re-reading it
                        // later would let a concurrent swap diff content the summary never attested.
                        // The read is bounded by the recorded length: one extra byte proves a longer
                        // file without buffering it.
                        final String actual;
                        final long actualBytes;
                        if (isPrimary) {
                            byte[] data;
                            try (var in = Files.newInputStream(authorized)) {
                                data = in.readNBytes((int) recorded + 1);
                            }
                            actual = ArtifactStore.sha256(data);
                            actualBytes = data.length;
                            if (actualBytes == recorded) {
                                primaryBytes = data;
                            }
                        } else {
                            actual = ArtifactStore.sha256(authorized);
                            actualBytes = Files.size(authorized);
                        }
                        boolean match = actual.equalsIgnoreCase(str(artifact.get("sha256")));
                        boolean bytesMatch = recorded >= 0 && recorded == actualBytes;
                        check.put("present", true);
                        check.put("sha256_match", match);
                        check.put("bytes_match", bytesMatch);
                        allArtifactsVerified &= match && bytesMatch;
                    } else {
                        check.put("present", false);
                        check.put("sha256_match", false);
                        check.put("bytes_match", false);
                        allArtifactsVerified = false;
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
                summary.put("note", primaryRefusal == null
                        ? "The baseline primary artifact was refused." : primaryRefusal);
                return new BaselineResult(summary, null);
            }
            if (!allArtifactsVerified) {
                summary.put("compared", false);
                summary.put("status", "artifact_verification_failed");
                summary.put("note", "At least one baseline artifact is missing or does not match its "
                        + "recorded sha256/byte length; no baseline content was diffed.");
                return new BaselineResult(summary, null);
            }
            if (primaryBytes == null) {
                summary.put("compared", false);
                summary.put("status", "primary_artifact_missing");
                summary.put("note", "Baseline artifact not found beside the manifest: "
                        + primaryAuthorized);
                return new BaselineResult(summary, null);
            }
            // Load the baseline document from the EXACT verified bytes with the network denied;
            // asserted diff only (ADR 0004).
            List<String> unresolved = new ArrayList<>();
            OWLOntology baselineOntology = DiffTools.loadDocument(primaryBytes, primaryAuthorized,
                    List.of(), unresolved,
                    new DirectAccessPolicy.NetworkRule(false, Set.of(), false, false));
            // Direction is baseline -> current release: additions/removals and compatibility must be
            // interpreted from the prior release to the artifact being prepared, never backwards.
            Map<String, Object> diff = SemanticDiffService.diff(baselineOntology,
                    cap.snapshot.ontology(), false, args.limit(), List.of());
            // The baseline is the LEFT side of this diff, so its offline-unresolvable imports do not
            // belong in the service's right_document key: disclose them under baseline-side names in
            // both the summary and the written reports/diff.json, or an incomplete baseline closure
            // would vanish from the release evidence.
            List<String> baselineUnresolved = unresolved.stream().distinct().sorted().toList();
            diff.put("left_document_unresolved_imports", baselineUnresolved);
            summary.put("compared", true);
            summary.put("status", "compared");
            summary.put("mode", "asserted");
            summary.put("note", "Asserted-axiom diff only; inferred baseline diff is deferred.");
            summary.put("unresolved_imports", baselineUnresolved);
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

    private record Serialized(byte[] bytes, String sha256, boolean clean, String error,
            String roundTripClass) {
    }

    /**
     * Serialize the Protégé-free snapshot and verify the exact isolated round trip through the shared
     * core service. Nothing lands in the workspace, so run_release_gate remains write-free.
     */
    private static Serialized serialize(VerifiedOntologyWriter.Snapshot snapshot) {
        try {
            VerifiedOntologyRoundTrip.Result result = VerifiedOntologyRoundTrip.serialize(
                    snapshot.ontology(), snapshot.format());
            return new Serialized(result.content(), result.sha256(), true, null,
                    result.roundTripClass());
        } catch (IOException | IllegalArgumentException e) {
            return new Serialized(null, null, false,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null);
        }
    }

    // ================================================================== deterministic artifact set

    /**
     * The complete, deterministic bundle for a passing release. Order is acyclic: the ontology and the
     * four reports (plus optional diff) are hashed first, the RO-Crate lists those files, and the
     * manifest indexes everything except itself. {@code createdAt} is the only non-reproducible input.
     */
    static List<Artifact> artifacts(Outcome outcome, String createdAt) {
        return bundle(outcome, createdAt).artifacts().stream()
                .map(file -> new Artifact(file.path(), file.mediaType(), file.content(),
                        file.sha256(), file.bytes()))
                .toList();
    }

    /** The manifest map preview (used by run_release_gate and prepare_release dry-run). */
    static Map<String, Object> manifestPreview(Outcome outcome, String createdAt) {
        return bundle(outcome, createdAt).manifest();
    }

    private static ReleaseBundleService.Bundle bundle(Outcome outcome, String createdAt) {
        return ReleaseBundleService.build(new ReleaseBundleService.Request(
                outcome.projectId, outcome.ontologyIri, outcome.versionIri, createdAt,
                outcome.license, outcome.policySha256, outcome.importLockSha256,
                outcome.semanticFingerprint, outcome.releaseStable, outcome.gate,
                outcome.ontologyBytes, outcome.ontologyArtifactPath, outcome.ontologyMediaType,
                outcome.baselineDiff, str(outcome.baseline.get("version_iri")), outcome.limit));
    }

    /** Copy the reviewed root Dataset license from the validated project RO-Crate. */
    private static Object releaseLicense(ProjectPolicy policy) {
        List<Path> manifests = policy.assets().getOrDefault("interoperability_manifest", List.of());
        if (manifests.size() != 1) {
            return null;
        }
        Path manifest = manifests.get(0);
        try {
            if (!Files.isRegularFile(manifest)
                    || Files.size(manifest)
                            > io.github.hakjuoh.protege_mcp.ro_crate.RoCrateProjectProfileValidator.MAX_BYTES) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        try (var in = Files.newInputStream(manifest)) {
            com.fasterxml.jackson.databind.JsonNode crate = ContractJson.mapper().readTree(in);
            com.fasterxml.jackson.databind.JsonNode graph = crate == null ? null : crate.get("@graph");
            if (graph == null || !graph.isArray()) {
                return null;
            }
            for (com.fasterxml.jackson.databind.JsonNode entity : graph) {
                if ("./".equals(entity.path("@id").asText())) {
                    com.fasterxml.jackson.databind.JsonNode license = entity.get("license");
                    return license == null || license.isNull() ? null
                            : ContractJson.mapper().convertValue(license, Object.class);
                }
            }
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
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
