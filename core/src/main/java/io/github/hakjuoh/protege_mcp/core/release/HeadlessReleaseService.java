package io.github.hakjuoh.protege_mcp.core.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.core.workspace.FilesystemProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceBundleTransaction;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceSnapshot;

/** Complete offline release preparation and failure-atomic publication over one captured snapshot. */
public final class HeadlessReleaseService {

    private HeadlessReleaseService() {
    }

    public record Request(Path outputDirectory, Path baselineManifest, boolean dryRun,
            String createdAt, int limit) {
        public Request {
            if (limit < 0 || limit > 10_000) {
                throw new IllegalArgumentException("limit must be between 0 and 10000");
            }
        }
    }

    public record Publication(String outputDirectory, boolean previousExisted,
            String previousTreeSha256, String installedTreeSha256, long installedBytes,
            String backupPath) {
    }

    public record Result(GateResult gate, ReleaseBundleService.Bundle bundle,
            Map<String, Object> details, boolean dryRun, boolean prepared,
            String outputDirectory, Publication publication) {
        public Result {
            if (gate == null || details == null) {
                throw new IllegalArgumentException("release result gate and details are required");
            }
            details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
            if (prepared != (publication != null) || prepared && dryRun) {
                throw new IllegalArgumentException("release publication state is inconsistent");
            }
        }

        public boolean artifactsAvailable() {
            return bundle != null;
        }
    }

    /**
     * Evaluate and optionally publish. The supplied clock is read exactly once for a committing
     * request without an explicit timestamp; previews use the deterministic {@code PREVIEW} marker.
     */
    public static Result execute(FilesystemProjectWorkspace workspace,
            OWLReasonerFactory reasonerFactory, Request request, Clock clock) throws IOException {
        if (workspace == null || reasonerFactory == null || request == null || clock == null) {
            throw new IllegalArgumentException("release execution arguments must not be null");
        }
        if (!request.dryRun()
                && HeadlessReleaseGateService.PREVIEW.equals(request.createdAt())) {
            throw new IllegalArgumentException(
                    "PREVIEW is reserved for dry-run release timestamps");
        }
        Clock utc = clock.withZone(ZoneOffset.UTC);
        Instant now = utc.instant();
        String createdAt = request.createdAt() != null ? request.createdAt()
                : request.dryRun() ? HeadlessReleaseGateService.PREVIEW
                        : now.toString();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            Path target = requestedOutput(snapshot, request);
            Path authorized = null;
            if (target != null) {
                try {
                    authorized = workspace.resolveBundleTarget(snapshot, target);
                } catch (IOException invalidOutput) {
                    throw new IllegalArgumentException(
                            "invalid release output: " + invalidOutput.getMessage(), invalidOutput);
                }
            }
            String portableOutput = authorized == null ? null
                    : portable(snapshot.policy().projectRoot(), authorized);

            HeadlessReleaseGateService.Request gateRequest =
                    new HeadlessReleaseGateService.Request(request.baselineManifest(),
                            createdAt, request.limit());
            if (request.dryRun()) {
                HeadlessReleaseGateService.Result evaluated = HeadlessReleaseGateService.evaluate(
                        workspace, snapshot, reasonerFactory, gateRequest, today);
                return new Result(evaluated.gate(), evaluated.bundle(), evaluated.details(),
                        true, false, portableOutput, null);
            }

            Path output = authorized;
            AtomicReference<List<HeadlessReleaseGateService.SourcePin>> baselineSources =
                    new AtomicReference<>(List.of());
            WorkspaceBundleTransaction.Guard guard = () ->
                    verifyBaselineSources(baselineSources.get(), output);
            try (WorkspaceBundleTransaction transaction = workspace.beginBundleTransaction(
                    snapshot, output, guard)) {
                HeadlessReleaseGateService.Result evaluated = HeadlessReleaseGateService.evaluate(
                        workspace, snapshot, reasonerFactory, gateRequest, today);
                baselineSources.set(evaluated.baselineSources());
                if (!evaluated.passed() || evaluated.bundle() == null) {
                    return new Result(evaluated.gate(), evaluated.bundle(), evaluated.details(),
                            false, false, portableOutput, null);
                }
                transaction.stage(evaluated.bundle());
                WorkspaceBundleTransaction.Commit committed;
                try {
                    committed = transaction.commit();
                } catch (WorkspaceBundleTransaction.CommitAppliedException ambiguous) {
                    try {
                        transaction.recover();
                    } catch (IOException | RuntimeException recoveryFailure) {
                        ambiguous.addSuppressed(recoveryFailure);
                        throw ambiguous;
                    }
                    throw new IOException("release publication could not be verified; the previous "
                            + "output was restored", ambiguous);
                }
                Publication publication = new Publication(portableOutput,
                        committed.previousExisted(), committed.previousTreeSha256(),
                        committed.installedTreeSha256(), committed.installedBytes(),
                        committed.backupPath() == null ? null
                                : portable(snapshot.policy().projectRoot(), committed.backupPath()));
                return new Result(evaluated.gate(), evaluated.bundle(), evaluated.details(),
                        false, true, portableOutput, publication);
            }
        }
    }

    private static Path requestedOutput(WorkspaceSnapshot snapshot, Request request) {
        if (request.outputDirectory() != null) {
            return request.outputDirectory();
        }
        List<Path> configured = snapshot.policy().assets()
                .getOrDefault("release_output", List.of());
        if (configured.size() == 1) {
            return configured.get(0);
        }
        if (!request.dryRun()) {
            throw new IllegalArgumentException("no release output is configured; set "
                    + "release.output_dir or pass --output");
        }
        return null;
    }

    private static void verifyBaselineSources(
            List<HeadlessReleaseGateService.SourcePin> sources, Path output) throws IOException {
        for (HeadlessReleaseGateService.SourcePin source : sources) {
            if (source.current()) {
                continue;
            }
            // Replacing an existing output atomically moves that complete, identity-checked tree to
            // its backup before the final guard. A baseline inside that same tree is consequently
            // absent at its old path because of our own rename, not because an external writer won.
            if (output != null && source.path().startsWith(output)
                    && !Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            throw new IOException("baseline release source checksum changed; publication refused");
        }
    }

    private static String portable(Path projectRoot, Path path) throws IOException {
        Path root = projectRoot.toRealPath();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("release path escaped the project root");
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }
}
