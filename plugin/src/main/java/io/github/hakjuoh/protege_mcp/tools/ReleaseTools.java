package io.github.hakjuoh.protege_mcp.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.StageResult;
import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseReports;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The release workflow tools (PLAN §9.3-§9.5): {@code run_release_gate} (read-only preview that writes
 * nothing) and {@code prepare_release} (the mutating/artifact-producing step, dry-run by default). Both
 * delegate the whole gate to {@link ReleaseGate}; this class only shapes the MCP envelopes and, for
 * {@code prepare_release}, applies the read-only/confirm-write gates and the {@link ArtifactStore}
 * writes.
 */
public final class ReleaseTools {

    /** Longest inline report/crate excerpt returned by a dry run before it is truncated. */
    private static final int INLINE_LIMIT = 8_000;

    private ReleaseTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("run_release_gate", (ex, req) -> {
            ReleaseGate.Args args = args(Tools.args(req));
            ReleaseGate.Outcome outcome = ReleaseGate.evaluate(ctx, ex, args);
            return Tools.ok(gateEnvelope(outcome));
        });

        tools.tool("prepare_release", (ex, req) -> {
            Map<String, Object> a = Tools.args(req);
            ReleaseGate.Args args = args(a);
            boolean dryRun = Tools.optBool(a, "dry_run", true);
            String createdAtArg = validateCreatedAt(Tools.optString(a, "created_at"));
            String outputDirArg = Tools.optString(a, "output_dir");

            ReleaseGate.Outcome outcome = ReleaseGate.evaluate(ctx, ex, args);
            if (!outcome.passed()) {
                return Tools.ok(refusal(outcome, dryRun));
            }
            if (!outcome.artifactsAvailable()) {
                // Defensive: a passing gate always has stable, serializable artifact material.
                return Tools.error("Release artifacts are unavailable despite a passing gate; "
                        + "re-run run_release_gate.");
            }
            if (dryRun) {
                String createdAt = createdAtArg != null ? createdAtArg : ReleaseGate.PREVIEW;
                return Tools.ok(dryRunResult(outcome, createdAt));
            }

            // Committing write: honor read-only and confirm-writes exactly like save_ontology.
            Path outputDir = outputDir(outcome, outputDirArg);
            String summary = "write release artifacts to " + outputDir;
            CallToolResult denied = WriteTools.checkWriteAllowed(ctx, summary);
            if (denied != null) {
                return denied;
            }
            // Read-only can flip while the confirmation dialog is open; re-check before any write.
            if (ctx.controller().isReadOnly()) {
                return WriteTools.readOnlyDenied();
            }
            String createdAt = createdAtArg != null ? createdAtArg : Instant.now().toString();
            List<ReleaseGate.Artifact> artifacts = ReleaseGate.artifacts(outcome, createdAt);
            ArtifactStore store = new ArtifactStore(outputDir);
            List<Map<String, Object>> written = new ArrayList<>();
            for (ReleaseGate.Artifact artifact : artifacts) {
                ArtifactStore.Written record = store.writeBytes(artifact.path(), artifact.content());
                written.add(writtenJson(record));
            }
            return Tools.json()
                    .put("prepared", true)
                    .put("dry_run", false)
                    .put("gate", outcome.status.json())
                    .put("network", outcome.network)
                    .put("lock_mode", outcome.lockMode)
                    .put("output_dir", outputDir.toString())
                    .put("created_at", createdAt)
                    .put("version_iri", outcome.versionIri)
                    .put("artifacts", written)
                    .result();
        });
    }

    // ================================================================== run_release_gate envelope

    private static Map<String, Object> gateEnvelope(ReleaseGate.Outcome outcome) {
        Tools.Json json = Tools.json()
                .put("gate", outcome.status.json())
                .put("semantic_fingerprint", outcome.semanticFingerprint)
                .put("required_stages", outcome.gate.requiredStages())
                .put("stages", stageMaps(outcome))
                .put("findings", findingMaps(outcome.gate.findings()))
                .put("network", outcome.network)
                .put("lock_mode", outcome.lockMode)
                .put("resolved_imports", outcome.resolvedImports)
                .putIfNotNull("network_caveat", outcome.networkCaveat)
                .put("version_iri", outcome.versionIri)
                .put("round_trip", outcome.roundTrip)
                .put("baseline", outcome.baseline);

        if (outcome.artifactsAvailable()) {
            json.put("manifest_preview", ReleaseGate.manifestPreview(outcome, ReleaseGate.PREVIEW));
        } else {
            Map<String, Object> unavailable = new LinkedHashMap<>();
            unavailable.put("manifest_available", false);
            unavailable.put("reason", manifestUnavailableReason(outcome));
            json.put("manifest_preview", unavailable);
        }

        Map<String, Object> reports = new LinkedHashMap<>();
        reports.put("markdown", bounded(ReleaseReports.markdown(outcome.gate, outcome.versionIri,
                outcome.limit)));
        reports.put("formats", List.of("json", "markdown", "junit", "sarif"));
        reports.put("note", "run_release_gate is a read-only preview; timestamps read PREVIEW. "
                + "prepare_release stamps the real created_at.");
        json.put("reports_preview", reports);
        return json.map();
    }

    private static String manifestUnavailableReason(ReleaseGate.Outcome outcome) {
        if (!outcome.releaseInputsAligned) {
            return "the ontology, policy, or validation assets changed while the gate was running";
        }
        if (!outcome.releaseStable) {
            return "the release fingerprint is not stable, so a reproducible manifest cannot be built";
        }
        if (outcome.ontologyBytes == null) {
            return "the verified serialization round trip failed or was skipped";
        }
        return "the release material could not be produced";
    }

    // ================================================================== prepare_release results

    private static Map<String, Object> refusal(ReleaseGate.Outcome outcome, boolean dryRun) {
        return Tools.json()
                .put("prepared", false)
                .put("dry_run", dryRun)
                .put("gate", outcome.status.json())
                .put("reason", "the release gate did not pass; no artifacts were prepared or written")
                .put("required_stages", outcome.gate.requiredStages())
                .put("stages", stageMaps(outcome))
                .put("findings", findingMaps(outcome.gate.findings()))
                .put("network", outcome.network)
                .put("lock_mode", outcome.lockMode)
                .put("resolved_imports", outcome.resolvedImports)
                .putIfNotNull("network_caveat", outcome.networkCaveat)
                .put("version_iri", outcome.versionIri)
                .put("round_trip", outcome.roundTrip)
                .put("baseline", outcome.baseline)
                .put("artifacts", Collections.emptyList())
                .map();
    }

    private static Map<String, Object> dryRunResult(ReleaseGate.Outcome outcome, String createdAt) {
        List<ReleaseGate.Artifact> artifacts = ReleaseGate.artifacts(outcome, createdAt);
        List<Map<String, Object>> artifactList = new ArrayList<>();
        Map<String, ReleaseGate.Artifact> byPath = new LinkedHashMap<>();
        for (ReleaseGate.Artifact artifact : artifacts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", artifact.path());
            row.put("sha256", artifact.sha256());
            row.put("bytes", artifact.bytes());
            artifactList.add(row);
            byPath.put(artifact.path(), artifact);
        }
        Map<String, Object> reports = new LinkedHashMap<>();
        for (String path : List.of("reports/qc.json", "reports/qc.md", "reports/qc.xml",
                "reports/qc.sarif", "reports/diff.json")) {
            ReleaseGate.Artifact artifact = byPath.get(path);
            if (artifact != null) {
                reports.put(path, bounded(new String(artifact.content(), StandardCharsets.UTF_8)));
            }
        }
        return Tools.json()
                .put("prepared", false)
                .put("dry_run", true)
                .put("gate", outcome.status.json())
                .put("created_at", createdAt)
                .put("version_iri", outcome.versionIri)
                .put("network", outcome.network)
                .put("lock_mode", outcome.lockMode)
                .put("artifacts", artifactList)
                .put("manifest", ReleaseGate.manifestPreview(outcome, createdAt))
                .put("reports", reports)
                .put("ro_crate", bounded(content(byPath, "ro-crate-metadata.json")))
                .put("note", "dry_run=true computed every artifact but wrote nothing; pass "
                        + "dry_run=false (with confirm-writes/read-only honored) to commit them.")
                .map();
    }

    // ================================================================== output-dir resolution

    private static Path outputDir(ReleaseGate.Outcome outcome, String outputDirArg) {
        if (outputDirArg != null) {
            return outcome.rules.writePath(outputDirArg);
        }
        List<Path> configured = outcome.policy.assets()
                .getOrDefault("release_output", List.of());
        if (configured.size() == 1) {
            // Re-authorize the policy-resolved directory as a write target (PROJECT_WRITE + containment).
            return outcome.rules.writePath(configured.get(0).toString());
        }
        throw new ToolArgException("No release output directory is configured; set release.output_dir "
                + "in the project policy or pass output_dir.");
    }

    // ================================================================== shaping helpers

    private static List<Map<String, Object>> stageMaps(ReleaseGate.Outcome outcome) {
        List<Map<String, Object>> stages = new ArrayList<>();
        for (StageResult stage : outcome.gate.stages()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.stage());
            row.put("status", stage.status().json());
            row.put("message", stage.message());
            row.put("findings", findingMaps(stage.findings()));
            row.put("details", stage.details());
            stages.add(row);
        }
        return stages;
    }

    private static List<Map<String, Object>> findingMaps(List<Finding> findings) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.id());
            row.put("source", f.source());
            row.put("severity", f.severity().json());
            row.put("message", f.message());
            row.put("focus_iri", f.focusIri());
            row.put("axiom", f.axiom());
            row.put("path", f.path());
            row.put("rule_id", f.ruleId());
            row.put("waiver", f.waiver());
            row.put("details", f.details());
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> writtenJson(ArtifactStore.Written record) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", record.path());
        row.put("sha256", record.sha256());
        row.put("bytes", record.bytes());
        return row;
    }

    private static String content(Map<String, ReleaseGate.Artifact> byPath, String path) {
        ReleaseGate.Artifact artifact = byPath.get(path);
        return artifact == null ? "" : new String(artifact.content(), StandardCharsets.UTF_8);
    }

    /** Bound an inline string, flagging truncation so a large report never bloats an MCP payload. */
    private static Map<String, Object> bounded(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        int full = text.length();
        boolean truncated = full > INLINE_LIMIT;
        out.put("content", truncated ? text.substring(0, INLINE_LIMIT) : text);
        out.put("truncated", truncated);
        out.put("length", full);
        return out;
    }

    private static ReleaseGate.Args args(Map<String, Object> a) {
        int limit = Tools.optInt(a, "limit", 50);
        if (limit < 0 || limit > 10_000) {
            throw new ToolArgException("limit must be between 0 and 10000.");
        }
        String lockMode = Tools.optString(a, "lock_mode");
        lockMode = LockMode.parse(lockMode).value(); // strict + canonical runtime value.
        return new ReleaseGate.Args(Tools.optString(a, "policy_path"), Tools.optString(a, "network"),
                lockMode, Tools.optString(a, "baseline_manifest"), limit);
    }

    /** Validate an explicitly declared reproducibility timestamp before running the expensive gate. */
    private static String validateCreatedAt(String value) {
        if (value == null) {
            return null;
        }
        if (!value.endsWith("Z")) {
            throw new ToolArgException("created_at must be an ISO-8601 UTC instant ending in Z.");
        }
        try {
            Instant.parse(value);
            return value;
        } catch (java.time.format.DateTimeParseException e) {
            throw new ToolArgException("created_at must be an ISO-8601 UTC instant: " + value);
        }
    }
}
