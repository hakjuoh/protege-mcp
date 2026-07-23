package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.core.workspace.FilesystemProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceSnapshot;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceTransaction;
import io.github.hakjuoh.protege_mcp.jobs.JobArtifact;
import io.github.hakjuoh.protege_mcp.jobs.JobDigests;
import io.github.hakjuoh.protege_mcp.jobs.JobException;
import io.github.hakjuoh.protege_mcp.jobs.JobOwner;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Secure, checksum-guarded publication of a private job artifact into its project. */
final class JobArtifactExporter {
    private JobArtifactExporter() {
    }

    static CallToolResult export(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> arguments) {
        requireKeys(arguments, Set.of("job_id", "artifact_id", "destination", "confirm",
                "overwrite", "expected_target_digest", "policy_path"));
        if (!Boolean.TRUE.equals(arguments.get("confirm"))) {
            throw prevented("confirmation_required",
                    "Job artifact export requires confirm=true.", false);
        }
        boolean overwrite = Tools.optBool(arguments, "overwrite", false);
        String expected = Tools.optString(arguments, "expected_target_digest");
        if (overwrite != (expected != null)) {
            throw prevented("expected_target_digest_required",
                    "overwrite=true and expected_target_digest must be supplied together.",
                    false);
        }
        AuthenticatedPrincipal principal = principal(exchange);
        JobOwner owner = owner(context, principal);
        String jobId = Tools.reqString(arguments, "job_id");
        String artifactId = Tools.reqString(arguments, "artifact_id");
        call(() -> context.jobs().requireArtifact(owner, jobId, artifactId));
        String destination = Tools.reqString(arguments, "destination");
        String policyPath = Tools.optString(arguments, "policy_path");
        DirectAccessPolicy.Rules rules =
                DirectAccessPolicy.resolve(context, exchange, policyPath);
        Path target = rules.writePath(destination);
        boolean confirmationMode = context.controller().isConfirmWrites();
        CallToolResult denied = WriteTools.checkWriteAllowed(
                context, "export job artifact " + artifactId + " to " + target);
        if (denied != null) return denied;
        context.writeLock().lock();
        try {
            if (context.controller().isReadOnly()) return WriteTools.readOnlyDenied();
            if (confirmationMode != context.controller().isConfirmWrites()) {
                throw prevented("confirmation_state_changed",
                        "The write-confirmation preference changed before export.", true);
            }
            try (PrincipalExecutionGate.Lease ignored =
                    context.executions().acquire(principal)) {
                JobArtifact current = call(() ->
                        context.jobs().requireArtifact(owner, jobId, artifactId));
                DirectAccessPolicy.Rules currentRules =
                        DirectAccessPolicy.resolve(context, exchange, policyPath);
                Path currentTarget = currentRules.writePath(destination);
                Receipt receipt = publish(currentRules, currentTarget,
                        current.copyBytes(), overwrite, expected);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("exported", true);
                result.put("job_id", jobId);
                result.put("artifact_id", artifactId);
                result.put("path", receipt.path());
                result.put("sha256", receipt.sha256());
                result.put("bytes", receipt.bytes());
                result.put("overwritten", receipt.overwritten());
                result.put("backup_path", receipt.backupPath());
                result.put("interactive_write_confirmation", confirmationMode);
                return Tools.ok(result);
            }
        } finally {
            context.writeLock().unlock();
        }
    }

    private static Receipt publish(DirectAccessPolicy.Rules rules, Path target,
            byte[] bytes, boolean overwrite, String expected) {
        String intended = ArtifactStore.sha256(bytes);
        Path policyPath = rules.policy().path();
        if (policyPath == null) {
            throw prevented("job_policy_required",
                    "A canonical project policy is required for artifact export.", false);
        }
        FilesystemProjectWorkspace workspace =
                new FilesystemProjectWorkspace(policyPath);
        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            WorkspaceTransaction opened =
                    workspace.beginTransaction(snapshot, target, overwrite,
                            io.github.hakjuoh.protege_mcp.jobs.JobService.MAX_ARTIFACT_BYTES);
            try (WorkspaceTransaction transaction = opened) {
                boolean existed = transaction.targetExisted();
                if (existed && !overwrite) {
                    transaction.verifyBaseline();
                    throw prevented("job_artifact_target_exists",
                            "The artifact destination already exists; no file was changed.", false);
                }
                if (!existed && overwrite) {
                    transaction.verifyBaseline();
                    throw prevented("expected_target_missing",
                            "expected_target_digest was supplied but the destination does not exist.",
                            true);
                }
                if (overwrite && !expected.equals(transaction.baselineSha256())) {
                    transaction.verifyBaseline();
                    throw prevented("target_digest_mismatch",
                            "The artifact destination changed; no file was replaced.", true);
                }
                WorkspaceTransaction.Stage staged = transaction.stageBytes(bytes);
                if (!intended.equals(staged.sha256())
                        || staged.bytes() != bytes.length) {
                    throw prevented("job_artifact_write_verification_failed",
                            "The staged artifact digest did not verify.", true);
                }
                try {
                    WorkspaceTransaction.Commit committed = transaction.commit();
                    if (!intended.equals(committed.installedSha256())) {
                        throw outcomeUnknown(
                                "The installed artifact digest could not be verified.",
                                Map.of("replacement_applied", true,
                                        "intended_sha256", intended));
                    }
                    return new Receipt(committed.target().toString(),
                            committed.installedSha256(), committed.installedBytes(),
                            committed.previousExisted(),
                            committed.backupPath() == null
                                    ? null : committed.backupPath().toString());
                } catch (WorkspaceTransaction.CommitAppliedException applied) {
                    throw outcomeUnknown(
                            "The artifact replacement completed but final verification failed.",
                            Map.of("replacement_applied", true,
                                    "intended_sha256", applied.commit().installedSha256(),
                                    "backup_path", path(applied.commit().backupPath())));
                } catch (WorkspaceTransaction.GuardedReplacementException guarded) {
                    WorkspaceTransaction.GuardedReplacementSideEffect receipt =
                            guarded.receipt();
                    throw outcomeUnknown(
                            "The guarded artifact replacement did not reach a fully verified state.",
                            Map.ofEntries(
                                    Map.entry("publication_applied",
                                            receipt.publicationApplied()),
                                    Map.entry("publication_verified",
                                            receipt.publicationVerified()),
                                    Map.entry("target_state_known",
                                            receipt.targetStateKnown()),
                                    Map.entry("target_sha256",
                                            text(receipt.targetSha256())),
                                    Map.entry("intended_sha256",
                                            text(receipt.intendedSha256())),
                                    Map.entry("backup_path",
                                            path(receipt.backupPath()))));
                } catch (WorkspaceTransaction.BackupAppliedException applied) {
                    WorkspaceTransaction.BackupSideEffect receipt = applied.receipt();
                    throw sideEffect(
                            "job_artifact_backup_applied",
                            "A verified backup was published before target replacement failed.",
                            Map.of("backup_path", path(receipt.backupPath()),
                                    "backup_verified", receipt.backupVerified(),
                                    "target_preserved", receipt.targetPreserved(),
                                    "target_state_known", receipt.targetStateKnown()));
                }
            }
        } catch (ToolArgException known) {
            throw known;
        } catch (WorkspaceTransaction.OrphanRecoveryAppliedException applied) {
            throw sideEffect("job_artifact_recovery_applied",
                    "Locked recovery changed transaction state; inspect the target before retrying.",
                    Map.of("recovery_state_known",
                            applied.receipt().recoveryStateKnown(),
                            "target_state_known",
                            applied.receipt().targetStateKnown(),
                            "target_sha256",
                            text(applied.receipt().targetSha256())));
        } catch (WorkspaceTransaction.AmbiguousRecoveryException ambiguous) {
            throw new ToolArgException("job_artifact_recovery_ambiguous",
                    "Workspace recovery evidence requires operator inspection.",
                    Map.of("effects_prevented", true,
                            "evidence_count", ambiguous.receipt().evidenceCount()),
                    false);
        } catch (WorkspaceTransaction.ExistingTargetSizeException exceeded) {
            throw prevented("job_artifact_target_too_large",
                    "The existing artifact destination exceeds the verified replacement bound.",
                    false);
        } catch (IOException prevented) {
            throw new ToolArgException("job_artifact_export_failed",
                    "The secure artifact transaction failed before publication.",
                    Map.of("effects_prevented", true), true);
        }
    }

    private static ToolArgException outcomeUnknown(
            String message, Map<String, Object> details) {
        Map<String, Object> evidence = new LinkedHashMap<>(details);
        evidence.put("outcome_unknown", true);
        evidence.put("retry_requires_state_check", true);
        return new ToolArgException(
                "job_artifact_export_outcome_unknown", message, evidence, false);
    }

    private static ToolArgException sideEffect(
            String code, String message, Map<String, Object> details) {
        Map<String, Object> evidence = new LinkedHashMap<>(details);
        evidence.put("outcome_unknown", false);
        evidence.put("effects_prevented", false);
        return new ToolArgException(code, message, evidence, false);
    }

    private static String path(Path value) {
        return value == null ? "" : value.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static AuthenticatedPrincipal principal(
            McpSyncServerExchange exchange) {
        if (exchange == null) return AuthenticatedPrincipal.staticAdmin();
        Object value = exchange.transportContext() == null ? null
                : exchange.transportContext().get(
                        AuthenticatedPrincipal.CONTEXT_KEY);
        return value instanceof AuthenticatedPrincipal principal ? principal : null;
    }

    private static JobOwner owner(
            ToolContext context, AuthenticatedPrincipal principal) {
        AuthenticatedPrincipal effective = principal == null
                ? AuthenticatedPrincipal.staticAdmin() : principal;
        return new JobOwner(context.revisions().workspaceId(),
                JobDigests.digest(effective.type(), effective.clientId()),
                JobDigests.digest(effective.clientId()),
                JobDigests.digest(effective.grantId()));
    }

    private static <T> T call(java.util.concurrent.Callable<T> action) {
        try {
            return action.call();
        } catch (JobException failure) {
            throw new ToolArgException(failure.error().code(),
                    failure.error().message(), failure.error().details(),
                    failure.error().retryable());
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireKeys(
            Map<String, Object> arguments, Set<String> allowed) {
        Set<String> unknown = new java.util.TreeSet<>(arguments.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw prevented("invalid_request",
                    "Unknown argument(s): " + String.join(", ", unknown), false);
        }
    }

    private static ToolArgException prevented(
            String code, String message, boolean retryable) {
        return new ToolArgException(code, message,
                Map.of("effects_prevented", true), retryable);
    }

    private record Receipt(
            String path, String sha256, long bytes,
            boolean overwritten, String backupPath) { }
}
