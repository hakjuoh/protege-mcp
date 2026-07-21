package io.github.hakjuoh.protege_mcp.sssom;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.hakjuoh.protege_mcp.core.workspace.ProjectFileLock;

/**
 * Project-confined, optimistic-concurrency SSSOM sidecar store shared by live and headless adapters.
 */
public final class SssomMappingStore {

    private static final Pattern REVISION = Pattern.compile("sha256:[0-9a-f]{64}");

    private final Path projectRoot;
    private final Path target;
    private final Path stateRoot;
    private final TransactionHook beforeReplace;
    private final TransactionHook afterReplace;
    private final AtomicMover mover;

    public SssomMappingStore(Path projectRoot, Path target) throws IOException {
        this(projectRoot, target, ProjectFileLock.defaultStateRoot());
    }

    public SssomMappingStore(Path projectRoot, Path target, Path stateRoot) throws IOException {
        this(projectRoot, target, stateRoot, () -> { }, () -> { },
                SssomMappingStore::atomicMove);
    }

    SssomMappingStore(Path projectRoot, Path target, Path stateRoot,
            TransactionHook beforeReplace, TransactionHook afterReplace, AtomicMover mover)
            throws IOException {
        if (projectRoot == null || target == null || stateRoot == null
                || beforeReplace == null || afterReplace == null || mover == null) {
            throw new IllegalArgumentException("mapping-store arguments must not be null");
        }
        this.projectRoot = requireProjectRoot(projectRoot);
        this.target = confined(target, this.projectRoot);
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        this.beforeReplace = beforeReplace;
        this.afterReplace = afterReplace;
        this.mover = mover;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path target() {
        return target;
    }

    /** Read one stable snapshot. Invalid content is returned with findings and is never normalized. */
    public Snapshot read(SssomValidationPolicy policy, SssomEntityIndex entities) throws IOException {
        Loaded loaded = load(target, policy, entities);
        return loaded.snapshot();
    }

    public Mutation add(String expectedRevision, MappingRecord record,
            SssomValidationPolicy policy, SssomEntityIndex entities, MutationGuard guard)
            throws IOException {
        return add(expectedRevision, record, Map.of(), Map.of(), policy, entities, guard);
    }

    /**
     * Add one row, using the supplied mapping-set metadata/prefixes only when creating an absent store.
     */
    public Mutation add(String expectedRevision, MappingRecord record,
            Map<String, Object> initialMetadata, Map<String, String> initialPrefixMap,
            SssomValidationPolicy policy, SssomEntityIndex entities, MutationGuard guard)
            throws IOException {
        if (record == null) throw new IllegalArgumentException("mapping record is required");
        Map<String, Object> metadata = initialMetadata == null ? Map.of() : Map.copyOf(initialMetadata);
        Map<String, String> prefixes = initialPrefixMap == null ? Map.of() : Map.copyOf(initialPrefixMap);
        return mutate(expectedRevision, policy, entities, guard, false, (current, exists) -> {
            Set<String> columns = new LinkedHashSet<>(current.columns());
            columns.addAll(record.cells().keySet());
            List<MappingRecord> records = new ArrayList<>(current.records());
            records.add(record);
            boolean absent = !exists;
            return new SssomDocument(absent ? metadata : current.metadata(),
                    absent ? prefixes : current.prefixMap(),
                    List.copyOf(columns), records);
        });
    }

    public Mutation remove(String expectedRevision, String mappingId,
            SssomValidationPolicy policy, SssomEntityIndex entities, MutationGuard guard)
            throws IOException {
        if (mappingId == null || mappingId.isBlank()) {
            throw new IllegalArgumentException("mapping_id is required");
        }
        return mutate(expectedRevision, policy, entities, guard, false, (current, exists) -> {
            List<MappingRecord> records = current.records().stream()
                    .filter(record -> !mappingId.equals(record.mappingId())).toList();
            if (records.size() == current.records().size()) {
                throw new SssomStoreException("mapping_not_found",
                        "No mapping has mapping_id " + mappingId, true);
            }
            return new SssomDocument(current.metadata(), current.prefixMap(),
                    current.columns(), records);
        });
    }

    public Mutation importDocument(String expectedRevision, SssomDocument incoming,
            ImportMode mode, SssomValidationPolicy policy, SssomEntityIndex entities,
            MutationGuard guard) throws IOException {
        if (incoming == null || mode == null) {
            throw new IllegalArgumentException("incoming document and import mode are required");
        }
        return mutate(expectedRevision, policy, entities, guard, mode == ImportMode.REPLACE,
                (current, exists) -> mode == ImportMode.REPLACE ? incoming : merge(current, incoming));
    }

    /**
     * Capture a project-confined import source and commit it under one project-lock hold. This is
     * the public file-import boundary: adapter path checks are advisory preflight only, while this
     * method re-resolves containment after taking the same lock used by the mapping CAS.
     */
    public FileImport importFile(String expectedRevision, Path source, ImportMode mode,
            SssomValidationPolicy policy, SssomEntityIndex entities, MutationGuard guard)
            throws IOException {
        if (source == null || mode == null) {
            throw new IllegalArgumentException("import source and mode are required");
        }
        requireRevision(expectedRevision);
        MutationGuard checkedGuard = guard == null ? MutationGuard.none() : guard;
        try {
            return ProjectFileLock.withLock(stateRoot, projectRoot, () -> {
                checkGuard(checkedGuard);
                Path confinedSource = confined(source, projectRoot);
                if (!Files.isRegularFile(confinedSource, LinkOption.NOFOLLOW_LINKS)) {
                    throw new SssomStoreException("mapping_import_source_invalid",
                            "SSSOM import source must be a project-confined regular file", true);
                }
                final SssomDocument incoming;
                try {
                    incoming = SssomParser.readStable(confinedSource).document();
                } catch (IOException invalid) {
                    throw new SssomStoreException("sssom_import_invalid",
                            "SSSOM import source could not be read as bounded version 1.0 TSV",
                            true, false, List.of(), invalid);
                }
                Mutation mutation = mutateLocked(expectedRevision, policy, entities,
                        checkedGuard, mode == ImportMode.REPLACE,
                        (current, exists) -> mode == ImportMode.REPLACE
                                ? incoming : merge(current, incoming));
                return new FileImport(mutation, incoming.records().size());
            });
        } catch (ProjectFileLock.UnavailableException held) {
            throw new SssomStoreException("mapping_revision_conflict",
                    "another mapping transaction is in progress; re-read mapping_revision", true,
                    false, List.of(), held);
        }
    }

    /**
     * Export the current canonical store to another project-confined path using no-clobber by default.
     */
    public Export export(String expectedRevision, Path destination, boolean overwrite,
            String expectedTargetDigest,
            boolean spreadsheetSafe, SssomValidationPolicy policy, SssomEntityIndex entities,
            MutationGuard guard) throws IOException {
        requireRevision(expectedRevision);
        Path output = confined(destination, projectRoot);
        if (sameLocation(target, output)) {
            throw new SssomStoreException("export_target_is_store",
                    "export destination must differ from the canonical mapping store", true);
        }
        if (expectedTargetDigest != null && !REVISION.matcher(expectedTargetDigest).matches()) {
            throw new IllegalArgumentException("expected_target_digest must be sha256:<64 lowercase hex>");
        }
        MutationGuard checkedGuard = guard == null ? MutationGuard.none() : guard;
        try {
            return ProjectFileLock.withLock(stateRoot, projectRoot, () -> {
                checkGuard(checkedGuard);
                Loaded source = load(target, policy, entities);
                if (!source.snapshot.mappingRevision.equals(expectedRevision)) {
                    throw new SssomStoreException("mapping_revision_conflict",
                            "Expected mapping revision does not match the canonical export source",
                            true);
                }
                if (!source.snapshot.exists) {
                    throw new SssomStoreException("mapping_store_absent",
                            "the canonical mapping store does not exist", true);
                }
                if (!source.report.valid()) {
                    throw validationFailure(source.report);
                }
                final byte[] bytes;
                try {
                    bytes = SssomParser.render(spreadsheetSafe
                            ? spreadsheetSafe(source.report.document()) : source.report.document());
                } catch (IOException renderFailure) {
                    throw new SssomStoreException("mapping_export_failed",
                            "mapping export could not be rendered within the format bounds", true,
                            false, List.of(), renderFailure);
                }
                Baseline destinationBaseline = baseline(output);
                if (destinationBaseline.exists && !overwrite) {
                    throw new SssomStoreException("target_exists",
                            "export destination exists and overwrite=false", true);
                }
                if (destinationBaseline.exists) {
                    if (expectedTargetDigest == null) {
                        throw new SssomStoreException("expected_target_digest_required",
                                "overwrite requires expected_target_digest", true);
                    }
                    if (!expectedTargetDigest.equals(destinationBaseline.raw.sha256)) {
                        throw new SssomStoreException("target_digest_conflict",
                                "export destination digest changed", true);
                    }
                } else if (expectedTargetDigest != null) {
                    throw new SssomStoreException("target_digest_conflict",
                            "export destination is absent but an expected digest was supplied", true);
                }
                ensureParent(output);
                checkGuard(checkedGuard);
                WriteResult written = install(output, destinationBaseline, bytes,
                        policy, entities, checkedGuard, false);
                return new Export(output, written.committed, source.snapshot.mappingRevision,
                        written.installedSha256, bytes.length, written.backupPath,
                        spreadsheetSafe, !spreadsheetSafe);
            });
        } catch (ProjectFileLock.UnavailableException held) {
            throw new SssomStoreException("project_lock_unavailable",
                    "another project filesystem transaction is in progress", true, false,
                    List.of(), held);
        }
    }

    private Mutation mutate(String expectedRevision, SssomValidationPolicy policy,
            SssomEntityIndex entities, MutationGuard guard, boolean allowInvalidCurrent,
            Candidate candidate) throws IOException {
        requireRevision(expectedRevision);
        MutationGuard checkedGuard = guard == null ? MutationGuard.none() : guard;
        try {
            return ProjectFileLock.withLock(stateRoot, projectRoot,
                    () -> mutateLocked(expectedRevision, policy, entities, checkedGuard,
                            allowInvalidCurrent, candidate));
        } catch (ProjectFileLock.UnavailableException held) {
            throw new SssomStoreException("mapping_revision_conflict",
                    "another mapping transaction is in progress; re-read mapping_revision", true,
                    false, List.of(), held);
        }
    }

    private Mutation mutateLocked(String expectedRevision, SssomValidationPolicy policy,
            SssomEntityIndex entities, MutationGuard guard, boolean allowInvalidCurrent,
            Candidate candidate) throws IOException {
        checkGuard(guard);
        Loaded current = load(target, policy, entities);
        if (!current.snapshot.mappingRevision.equals(expectedRevision)) {
            throw new SssomStoreException("mapping_revision_conflict",
                    "Expected mapping revision does not match the current canonical store", true);
        }
        if (!allowInvalidCurrent && !current.report.valid()) {
            throw validationFailure(current.report);
        }
        SssomDocument proposed = candidate.build(current.report.document(),
                current.baseline.exists);
        ensureDocumentBounds(proposed);
        SssomValidator.Report report = SssomValidator.validate(proposed, policy, entities);
        if (!report.valid()) throw validationFailure(report);
        byte[] bytes = SssomParser.render(report.document());
        if (current.baseline.exists && Arrays.equals(bytes, current.rawBytes)) {
            return new Mutation(false, target, expectedRevision, expectedRevision,
                    report.document().records().size(), bytes.length, null, report);
        }
        ensureParent(target);
        checkGuard(guard);
        WriteResult written = install(target, current.baseline, bytes,
                policy, entities, guard, true);
        return new Mutation(written.committed, target, expectedRevision,
                sha256(bytes), report.document().records().size(), bytes.length,
                written.backupPath, report);
    }

    private WriteResult install(Path output, Baseline baseline, byte[] bytes,
            SssomValidationPolicy policy, SssomEntityIndex entities,
            MutationGuard guard, boolean validate) throws IOException {
        Path stage = null;
        Path backupTemp = null;
        Path previousBackupTemp = null;
        Path backupPath = null;
        Baseline previousBackup = Baseline.absent();
        boolean installed = false;
        boolean replacementAttempted = false;
        String installedSha = sha256(bytes);
        DirectoryIdentity parent = directoryIdentity(output.getParent());
        try {
            verifyBaseline(output, baseline);
            stage = createSibling(output, "stage");
            writeForced(stage, bytes);
            verifyCandidate(stage, bytes, policy, entities, validate);
            verifyDirectory(output.getParent(), parent);
            verifyBaseline(output, baseline);

            if (baseline.exists) {
                backupPath = backupPath(output);
                if (backupPath.equals(output)) {
                    throw new SssomStoreException("backup_path_collision",
                            "transaction target collides with its recovery path", true);
                }
                previousBackup = baseline(backupPath);
                if (previousBackup.exists) {
                    previousBackupTemp = createSibling(output, "previous-backup");
                    copyBounded(backupPath, previousBackupTemp);
                    if (!previousBackup.raw.sameContent(rawIdentity(previousBackupTemp))) {
                        throw new SssomStoreException("backup_verification_failed",
                                "previous stable backup changed during capture", true);
                    }
                }
                backupTemp = createSibling(output, "backup");
                copyBounded(output, backupTemp);
                if (!baseline.raw.sameContent(rawIdentity(backupTemp))) {
                    throw new SssomStoreException("backup_verification_failed",
                            "backup copy differs from the transaction baseline", true);
                }
                // Make the private recovery copy durable before the target rename. It is published to
                // the single stable backup path only after the candidate verifies successfully.
                forceDirectory(output.getParent());
                verifyDirectory(output.getParent(), parent);
                verifyBaseline(backupPath, previousBackup);
                verifyBaseline(output, baseline);
            }

            beforeReplace.run();
            checkGuard(guard);
            verifyDirectory(output.getParent(), parent);
            verifyBaseline(output, baseline);
            if (!installedSha.equals(rawIdentity(stage).sha256)) {
                throw new SssomStoreException("staged_candidate_changed",
                        "staged mapping candidate changed before replacement", true);
            }
            replacementAttempted = true;
            mover.move(stage, output);
            stage = null;
            installed = true;
            afterReplace.run();
            // Close the final guard-to-rename race. If model/policy/authorization state changed
            // around replacement, classify and recover through the same proven-state path below.
            checkGuard(guard);
            verifyDirectory(output.getParent(), parent);
            if (!installedSha.equals(rawIdentity(output).sha256)) {
                throw new IOException("installed bytes differ from the staged candidate");
            }
            verifyCandidate(output, bytes, policy, entities, validate);
            forceDirectory(output.getParent());
            if (backupTemp != null) {
                verifyBaseline(backupPath, previousBackup);
                mover.move(backupTemp, backupPath);
                backupTemp = null;
                if (!baseline.raw.sameContent(rawIdentity(backupPath))) {
                    throw new SssomStoreException("backup_verification_failed",
                            "published backup differs from the transaction baseline", false);
                }
                forceDirectory(output.getParent());
            }
            return new WriteResult(true, installedSha, backupPath);
        } catch (IOException | RuntimeException failure) {
            throw classifyFailure(output, baseline, installedSha, backupTemp, backupPath,
                    previousBackupTemp, previousBackup, parent, installed,
                    replacementAttempted, failure);
        } finally {
            deleteQuietly(stage);
            deleteQuietly(backupTemp);
            deleteQuietly(previousBackupTemp);
        }
    }

    private SssomStoreException classifyFailure(Path output, Baseline baseline,
            String installedSha, Path backupTemp, Path backupPath, Path previousBackupTemp,
            Baseline previousBackup, DirectoryIdentity parent,
            boolean installed, boolean replacementAttempted, Throwable failure) {
        if (!installed && !replacementAttempted) {
            if (failure instanceof SssomStoreException typed) return typed;
            return new SssomStoreException("mapping_write_failed",
                    "mapping transaction failed before replacement", true, false,
                    List.of(), failure);
        }
        Reconciliation state = installed ? Reconciliation.INSTALLED
                : reconcile(output, baseline, installedSha);
        if (state == Reconciliation.BASELINE) {
            if (failure instanceof SssomStoreException typed) return typed;
            String code = failure instanceof AtomicMoveNotSupportedException
                    ? "atomic_replace_unsupported" : "mapping_write_failed";
            String message = failure instanceof AtomicMoveNotSupportedException
                    ? "filesystem does not support atomic replacement"
                    : "mapping transaction failed before replacement";
            return new SssomStoreException(code, message, true, false, List.of(), failure);
        }
        if (state == Reconciliation.INSTALLED) {
            boolean targetRecovered = recover(output, baseline, installedSha,
                    backupTemp, backupPath, parent);
            boolean backupRecovered = targetRecovered && restorePreviousBackup(backupPath,
                    baseline, previousBackupTemp, previousBackup, parent);
            boolean recovered = targetRecovered && backupRecovered;
            List<SssomFinding> findings = failure instanceof SssomStoreException typed
                    ? typed.findings() : List.of();
            return new SssomStoreException(recovered
                    ? "post_commit_verification_failed_recovered"
                    : "mapping_write_outcome_unknown",
                    recovered
                    ? "replacement verification failed and the previous store was restored"
                    : "replacement occurred but its final state could not be proven",
                    recovered, !recovered, findings, failure);
        }
        return new SssomStoreException("mapping_write_outcome_unknown",
                "transaction failed and the target no longer matches either proven state",
                false, true, List.of(), failure);
    }

    private boolean restorePreviousBackup(Path backupPath, Baseline transactionBaseline,
            Path previousBackupTemp, Baseline previousBackup, DirectoryIdentity parent) {
        if (backupPath == null) return true;
        try {
            verifyDirectory(backupPath.getParent(), parent);
            Baseline current = baseline(backupPath);
            if (previousBackup.exists && current.exists
                    && previousBackup.raw.sameContent(current.raw)) return true;
            if (!previousBackup.exists && !current.exists) return true;
            if (!current.exists || !transactionBaseline.raw.sameContent(current.raw)) return false;
            if (!previousBackup.exists) {
                Files.delete(backupPath);
                forceDirectory(backupPath.getParent());
                return !Files.exists(backupPath, LinkOption.NOFOLLOW_LINKS);
            }
            if (previousBackupTemp == null
                    || !previousBackup.raw.sameContent(rawIdentity(previousBackupTemp))) return false;
            mover.move(previousBackupTemp, backupPath);
            forceDirectory(backupPath.getParent());
            return previousBackup.raw.sameContent(rawIdentity(backupPath));
        } catch (IOException | RuntimeException restoreFailure) {
            return false;
        }
    }

    private Reconciliation reconcile(Path output, Baseline baseline, String installedSha) {
        try {
            Baseline current = baseline(output);
            if (baseline.equals(current)) return Reconciliation.BASELINE;
            if (current.exists && installedSha.equals(current.raw.sha256)) {
                return Reconciliation.INSTALLED;
            }
            return Reconciliation.UNKNOWN;
        } catch (IOException | RuntimeException unavailable) {
            return Reconciliation.UNKNOWN;
        }
    }

    private boolean recover(Path output, Baseline baseline, String installedSha,
            Path backupTemp, Path backupPath, DirectoryIdentity parent) {
        Path recovery = null;
        try {
            verifyDirectory(output.getParent(), parent);
            RawIdentity current = rawIdentity(output);
            if (!installedSha.equals(current.sha256)) return false;
            if (!baseline.exists) {
                Files.delete(output);
                forceDirectory(output.getParent());
                return !Files.exists(output, LinkOption.NOFOLLOW_LINKS);
            }
            Path source = backupTemp != null && Files.exists(backupTemp, LinkOption.NOFOLLOW_LINKS)
                    && baseline.raw.sameContent(rawIdentity(backupTemp)) ? backupTemp : backupPath;
            if (source == null || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                    || !baseline.raw.sameContent(rawIdentity(source))) return false;
            recovery = createSibling(output, "recovery");
            copyBounded(source, recovery);
            if (!baseline.raw.sameContent(rawIdentity(recovery))) return false;
            if (!installedSha.equals(rawIdentity(output).sha256)) return false;
            verifyDirectory(output.getParent(), parent);
            mover.move(recovery, output);
            recovery = null;
            forceDirectory(output.getParent());
            return baseline.raw.sameContent(rawIdentity(output));
        } catch (IOException | RuntimeException recoveryFailed) {
            return false;
        } finally {
            deleteQuietly(recovery);
        }
    }

    private Loaded load(Path path, SssomValidationPolicy policy, SssomEntityIndex entities)
            throws IOException {
        Baseline before = baseline(path);
        SssomParser.StableRead stable = before.exists ? SssomParser.readStable(path) : null;
        SssomDocument document = stable == null ? SssomDocument.empty() : stable.document();
        Baseline after = baseline(path);
        if (!before.equals(after)) {
            throw new SssomStoreException("mapping_store_changed_during_read",
                    "mapping store changed while it was being read", true);
        }
        SssomValidator.Report report = before.exists
                ? SssomValidator.validate(document, policy, entities)
                : new SssomValidator.Report(document.canonical(policy == null
                        ? Map.of() : policy.approvedPrefixes()), List.of(), false);
        byte[] canonical = SssomParser.render(report.document());
        byte[] raw = stable == null ? new byte[0] : stable.bytes();
        if (before.exists && !before.raw.sha256.equals(sha256(raw))) {
            throw new SssomStoreException("mapping_store_changed_during_read",
                    "mapping store bytes do not match the captured baseline", true);
        }
        if (!before.equals(baseline(path))) {
            throw new SssomStoreException("mapping_store_changed_during_read",
                    "mapping store changed while it was being read", true);
        }
        Snapshot snapshot = new Snapshot(path, before.exists, sha256(canonical),
                canonical.length, report.document().records().size(), report);
        return new Loaded(snapshot, report, before, raw);
    }

    private static SssomDocument merge(SssomDocument current, SssomDocument incoming)
            throws SssomStoreException {
        long rows = (long) current.records().size() + incoming.records().size();
        if (rows > SssomParser.MAX_ROWS) {
            throw new SssomStoreException("mapping_input_limit_exceeded",
                    "merged mapping row count exceeds the format limit", true);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(current.metadata());
        for (Map.Entry<String, Object> entry : incoming.metadata().entrySet()) {
            Object previous = metadata.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                throw new SssomStoreException("mapping_metadata_conflict",
                        "merge input conflicts on metadata key " + entry.getKey(), true);
            }
        }
        Map<String, String> prefixes = new LinkedHashMap<>(current.prefixMap());
        for (Map.Entry<String, String> entry : incoming.prefixMap().entrySet()) {
            String previous = prefixes.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                throw new SssomStoreException("mapping_prefix_conflict",
                        "merge input conflicts on prefix " + entry.getKey(), true);
            }
        }
        Set<String> columns = new LinkedHashSet<>(current.columns());
        columns.addAll(incoming.columns());
        List<MappingRecord> records = new ArrayList<>(current.records());
        records.addAll(incoming.records());
        return new SssomDocument(metadata, prefixes, List.copyOf(columns), records);
    }

    private static void ensureDocumentBounds(SssomDocument document) throws SssomStoreException {
        int rows = document.records().size();
        int columns = document.columns().size();
        if (rows > SssomParser.MAX_ROWS || columns > SssomParser.MAX_COLUMNS
                || (long) rows * columns > SssomParser.MAX_CELLS) {
            throw new SssomStoreException("mapping_input_limit_exceeded",
                    "mapping candidate exceeds row, column, or cell limits", true);
        }
        long bytes = 0;
        for (String column : document.columns()) bytes += utf8Length(column) + 1;
        for (MappingRecord record : document.records()) {
            for (String column : document.columns()) {
                String value = record.value(column);
                long cell = utf8Length(value);
                if (cell > SssomParser.MAX_CELL_BYTES) {
                    throw new SssomStoreException("mapping_input_limit_exceeded",
                            "mapping candidate contains a cell above the byte limit", true);
                }
                bytes += cell + 3;
                if (bytes > SssomParser.MAX_BYTES) {
                    throw new SssomStoreException("mapping_input_limit_exceeded",
                            "mapping candidate exceeds the byte limit", true);
                }
            }
        }
        ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(document.metadata());
        long nodes = 0;
        while (!pending.isEmpty()) {
            Object value = pending.removeLast();
            if (value instanceof Map<?, ?> map) {
                nodes += map.size();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    bytes += utf8Length(String.valueOf(entry.getKey()));
                    if (entry.getValue() != null) pending.add(entry.getValue());
                }
            } else if (value instanceof Collection<?> collection) {
                nodes += collection.size();
                for (Object item : collection) if (item != null) pending.add(item);
            } else {
                nodes++;
                bytes += utf8Length(String.valueOf(value));
            }
            if (nodes > SssomParser.MAX_METADATA_ENTRIES || bytes > SssomParser.MAX_BYTES) {
                throw new SssomStoreException("mapping_input_limit_exceeded",
                        "mapping candidate metadata exceeds its structural or byte limit", true);
            }
        }
        for (Map.Entry<String, String> prefix : document.prefixMap().entrySet()) {
            bytes += utf8Length(prefix.getKey()) + utf8Length(prefix.getValue());
        }
        if (bytes > SssomParser.MAX_BYTES) {
            throw new SssomStoreException("mapping_input_limit_exceeded",
                    "mapping candidate exceeds the byte limit", true);
        }
    }

    private static long utf8Length(CharSequence value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) bytes++;
            else if (current <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else bytes += 3;
        }
        return bytes;
    }

    private static SssomDocument spreadsheetSafe(SssomDocument source) {
        List<MappingRecord> records = source.records().stream().map(record -> {
            Map<String, String> cells = new LinkedHashMap<>(record.cells());
            cells.replaceAll((column, value) -> formula(value) ? "'" + value : value);
            return new MappingRecord(cells);
        }).toList();
        return new SssomDocument(source.metadata(), source.prefixMap(), source.columns(), records);
    }

    private static boolean formula(String value) {
        if (value == null || value.isEmpty()) return false;
        String candidate = value.stripLeading();
        if (candidate.isEmpty()) return false;
        return candidate.charAt(0) == '=' || candidate.charAt(0) == '+'
                || candidate.charAt(0) == '-' || candidate.charAt(0) == '@';
    }

    private static void verifyCandidate(Path path, byte[] expected,
            SssomValidationPolicy policy, SssomEntityIndex entities, boolean validate)
            throws IOException {
        SssomDocument parsed = SssomParser.parse(path);
        SssomDocument canonical = parsed.canonical(policy == null
                ? Map.of() : policy.approvedPrefixes());
        if (validate) {
            SssomValidator.Report report = SssomValidator.validate(canonical, policy, entities);
            if (!report.valid()) throw validationFailure(report);
            canonical = report.document();
        }
        if (!Arrays.equals(expected, SssomParser.render(canonical))) {
            throw new IOException("staged SSSOM does not round-trip to the candidate bytes");
        }
    }

    private static SssomStoreException validationFailure(SssomValidator.Report report) {
        return new SssomStoreException("mapping_validation_failed",
                "mapping candidate has validation errors", true, false,
                report.findings(), null);
    }

    private static void checkGuard(MutationGuard guard) throws IOException {
        try {
            guard.check();
        } catch (SssomStoreException typed) {
            throw typed;
        } catch (IOException | RuntimeException failure) {
            throw new SssomStoreException("mutation_guard_failed",
                    "project or authorization state changed during the transaction", true,
                    false, List.of(), failure);
        }
    }

    private static void requireRevision(String revision) {
        if (revision == null || !REVISION.matcher(revision).matches()) {
            throw new IllegalArgumentException(
                    "expected_mapping_revision must be sha256:<64 lowercase hex>");
        }
    }

    private Baseline baseline(Path path) throws IOException {
        Path current = confined(path, projectRoot);
        if (!current.equals(path)) {
            throw new SssomStoreException("path_identity_changed",
                    "mapping transaction path no longer resolves to its authorized location", true);
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Baseline.absent();
        return new Baseline(true, rawIdentity(path));
    }

    private RawIdentity rawIdentity(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new SssomStoreException("symlink_forbidden",
                    "mapping transaction paths must not be symbolic links", true);
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new SssomStoreException("regular_file_required",
                    "mapping transaction path is not a regular file", true);
        }
        if (attributes.size() > SssomParser.MAX_BYTES) {
            throw new SssomStoreException("mapping_file_too_large",
                    "mapping transaction file exceeds the 64 MiB limit", true);
        }
        String digest;
        try (InputStream raw = Files.newInputStream(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
                DigestInputStream input = new DigestInputStream(raw, digest())) {
            byte[] buffer = new byte[8_192];
            long read = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                read += count;
                if (read > SssomParser.MAX_BYTES) {
                    throw new SssomStoreException("mapping_file_too_large",
                            "mapping transaction file exceeds the 64 MiB limit", true);
                }
            }
            digest = hex(input.getMessageDigest().digest());
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!java.util.Objects.equals(attributes.fileKey(), after.fileKey())
                || attributes.size() != after.size()
                || !attributes.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new SssomStoreException("mapping_store_changed_during_read",
                    "mapping transaction file changed during digest", true);
        }
        return new RawIdentity(attributes.fileKey(), attributes.size(),
                attributes.lastModifiedTime(), digest);
    }

    private void verifyBaseline(Path path, Baseline expected) throws IOException {
        if (!expected.equals(baseline(path))) {
            throw new SssomStoreException(path.equals(target)
                    ? "mapping_revision_conflict" : "target_digest_conflict",
                    "transaction target changed after its baseline was captured", true);
        }
    }

    private void ensureParent(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent == null || !parent.normalize().startsWith(projectRoot)) {
            throw new SssomStoreException("path_outside_project",
                    "mapping transaction path escapes the project", true);
        }
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new SssomStoreException("parent_directory_missing",
                    "mapping transaction parent directory must already exist", true);
        }
        directoryIdentity(parent);
    }

    private static Path requireProjectRoot(Path requested) throws IOException {
        Path root = requested.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new IOException("project root must not be a symbolic link");
        }
        Path real = root.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("project root is not a directory");
        }
        return real;
    }

    private static Path confined(Path requested, Path root) throws IOException {
        if (requested == null) throw new IllegalArgumentException("mapping path is required");
        Path candidate = requested.isAbsolute() ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).normalize();
        List<Path> suffix = new ArrayList<>();
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            suffix.add(0, existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null || Files.isSymbolicLink(existing)) {
            throw new SssomStoreException("symlink_forbidden",
                    "mapping transaction path has no safe existing ancestor", true);
        }
        Path canonical = existing.toRealPath();
        for (Path element : suffix) canonical = canonical.resolve(element);
        canonical = canonical.normalize();
        if (!canonical.startsWith(root) || canonical.equals(root)) {
            throw new SssomStoreException("path_outside_project",
                    "mapping transaction path escapes the project", true);
        }
        if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(canonical)
                || !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS))) {
            throw new SssomStoreException("regular_file_required",
                    "mapping transaction target must be a regular file", true);
        }
        return canonical;
    }

    private static boolean sameLocation(Path first, Path second) {
        if (first.equals(second)) return true;
        if (!Files.exists(first, LinkOption.NOFOLLOW_LINKS)
                || !Files.exists(second, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            return Files.isSameFile(first, second);
        } catch (IOException cannotProve) {
            return true;
        }
    }

    private static Path createSibling(Path output, String purpose) throws IOException {
        Path created = Files.createTempFile(output.getParent(),
                "." + output.getFileName() + ".protege-mcp-" + purpose + "-", ".tmp");
        setOwnerOnly(created, false);
        return created.toRealPath();
    }

    private static Path backupPath(Path output) {
        String targetKey = sha256(output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .substring("sha256:".length());
        return output.getParent().resolve(".protege-mcp-backup-"
                + targetKey + ".bak");
    }

    private DirectoryIdentity directoryIdentity(Path directory) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory)) {
            throw new SssomStoreException("symlink_forbidden",
                    "mapping transaction parent must not be a symbolic link", true);
        }
        BasicFileAttributes attributes = Files.readAttributes(directory,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new SssomStoreException("directory_required",
                    "mapping transaction parent is not a directory", true);
        }
        Path real = directory.toRealPath();
        if (!real.startsWith(projectRoot)) {
            throw new SssomStoreException("path_outside_project",
                    "mapping transaction parent escapes the project", true);
        }
        return new DirectoryIdentity(attributes.fileKey(), real);
    }

    private void verifyDirectory(Path directory, DirectoryIdentity expected) throws IOException {
        if (!expected.equals(directoryIdentity(directory))) {
            throw new SssomStoreException("parent_directory_changed",
                    "mapping transaction parent changed during the transaction", true);
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void copyBounded(Path source, Path destination) throws IOException {
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
                FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] bytes = new byte[8_192];
            long copied = 0;
            int count;
            while ((count = input.read(bytes)) >= 0) {
                copied += count;
                if (copied > SssomParser.MAX_BYTES) {
                    throw new SssomStoreException("mapping_file_too_large",
                            "mapping transaction copy exceeds the 64 MiB limit", true);
                }
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes, 0, count);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException ignored) {
            // The provider explicitly reports that directory fsync is unavailable.
        } catch (IOException failure) {
            if (!directoryFsyncUnavailable(System.getProperty("os.name", ""), failure)) {
                throw failure;
            }
        }
    }

    static boolean directoryFsyncUnavailable(String osName, IOException failure) {
        if (osName == null || !osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            return false;
        }
        if (failure instanceof java.nio.file.AccessDeniedException) return true;
        if (failure instanceof java.nio.file.FileSystemException filesystem) {
            String reason = filesystem.getReason();
            if (reason == null) return false;
            String normalized = reason.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("not supported")
                    || normalized.contains("incorrect function");
        }
        return false;
    }

    private static void atomicMove(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void setOwnerOnly(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions
                    .fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // The platform does not expose POSIX permissions.
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Owner-only abandoned staging file; later cleanup may remove it.
        }
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = digest();
        return hex(digest.digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (byte item : value) {
            result.append(Character.forDigit((item >>> 4) & 0xf, 16));
            result.append(Character.forDigit(item & 0xf, 16));
        }
        return result.toString();
    }

    public enum ImportMode {
        REPLACE, MERGE
    }

    public record Snapshot(Path path, boolean exists, String mappingRevision,
            long canonicalBytes, int recordCount, SssomValidator.Report validation) {
    }

    public record Mutation(boolean committed, Path path, String previousRevision,
            String mappingRevision, int recordCount, long bytes, Path backupPath,
            SssomValidator.Report validation) {
    }

    public record FileImport(Mutation mutation, int sourceRecords) {
        public FileImport {
            if (mutation == null || sourceRecords < 0) {
                throw new IllegalArgumentException("file import result is incomplete");
            }
        }
    }

    public record Export(Path path, boolean committed, String mappingRevision,
            String sha256, long bytes,
            Path backupPath, boolean spreadsheetSafe, boolean lossless) {
    }

    @FunctionalInterface
    public interface MutationGuard {
        void check() throws IOException;

        static MutationGuard none() {
            return () -> { };
        }
    }

    @FunctionalInterface
    private interface Candidate {
        SssomDocument build(SssomDocument current, boolean exists) throws IOException;
    }

    @FunctionalInterface
    interface TransactionHook {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path destination) throws IOException;
    }

    private record RawIdentity(Object fileKey, long bytes,
            java.nio.file.attribute.FileTime modified, String sha256) {
        boolean sameContent(RawIdentity other) {
            return other != null && bytes == other.bytes && sha256.equals(other.sha256);
        }
    }

    private record Baseline(boolean exists, RawIdentity raw) {
        static Baseline absent() {
            return new Baseline(false, null);
        }
    }

    private record Loaded(Snapshot snapshot, SssomValidator.Report report,
            Baseline baseline, byte[] rawBytes) {
    }

    private record WriteResult(boolean committed, String installedSha256, Path backupPath) {
    }

    private record DirectoryIdentity(Object fileKey, Path realPath) {
    }

    private enum Reconciliation {
        BASELINE, INSTALLED, UNKNOWN
    }
}
