package io.github.hakjuoh.protege_mcp.core.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/** Owner-only, append-only JSON Lines stream for one backend workspace. */
public final class AuditLog {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_EVENT_BYTES = 64 * 1024;
    private static final int ORPHAN_RETENTION_DAYS = AuditSettings.MAX_RETENTION_DAYS;
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern PROJECT_HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern STREAM_FILE = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.jsonl(?:\\.[1-9][0-9]*)?$");
    private static final Set<PosixFilePermission> DIR_PERMS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS =
            PosixFilePermissions.fromString("rw-------");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @FunctionalInterface
    public interface IdGenerator {
        String nextId();
    }

    public record Receipt(String eventId, String timestamp, String workspaceId) {
    }

    private final Path auditRoot;
    private final Path projectDirectory;
    private final Path stream;
    private final String projectHash;
    private final String workspaceId;
    private final AuditSettings settings;
    private final Clock clock;
    private final IdGenerator ids;
    private Instant lastOrphanSweep = Instant.MIN;

    public static AuditLog create(Path projectRoot, AuditSettings settings) {
        String workspace = "ws-" + UUID.randomUUID();
        return new AuditLog(defaultRoot(), projectRoot, workspace, settings,
                Clock.systemUTC(), () -> "evt-" + UUID.randomUUID());
    }

    /** Create a default-root stream with a caller-stable backend workspace identity. */
    public static AuditLog create(Path projectRoot, String workspaceId, AuditSettings settings) {
        return new AuditLog(defaultRoot(), projectRoot, workspaceId, settings,
                Clock.systemUTC(), () -> "evt-" + UUID.randomUUID());
    }

    public AuditLog(Path auditRoot, Path projectRoot, String workspaceId,
            AuditSettings settings, Clock clock, IdGenerator ids) {
        if (auditRoot == null || projectRoot == null || settings == null || clock == null || ids == null) {
            throw new IllegalArgumentException("audit log arguments must not be null");
        }
        if (!SAFE_ID.matcher(workspaceId == null ? "" : workspaceId).matches()) {
            throw new IllegalArgumentException("unsafe audit workspace id");
        }
        try {
            Path canonicalProject = projectRoot.toRealPath();
            this.projectHash = ArtifactStore.sha256(
                    canonicalProject.toString().getBytes(StandardCharsets.UTF_8)).substring(7);
        } catch (IOException e) {
            throw new UncheckedIOException("could not identify audit project", e);
        }
        this.auditRoot = auditRoot.toAbsolutePath().normalize();
        this.projectDirectory = this.auditRoot.resolve(projectHash);
        this.workspaceId = workspaceId;
        this.stream = projectDirectory.resolve(workspaceId + ".jsonl");
        this.settings = settings;
        this.clock = clock;
        this.ids = ids;
        ensureOwnerOnlyDirectories();
    }

    public synchronized Receipt append(AuditEvent event) {
        if (event == null) throw new IllegalArgumentException("audit event is required");
        Instant now = clock.instant();
        String eventId = checkedId(ids.nextId(), "event");
        Receipt receipt = AuditFileMutex.withLock(auditRoot, projectHash, () -> {
            // A sibling project's conservative sweeper can remove an idle directory between calls;
            // create/re-check it only after holding the same project shard lock.
            ensureOwnerOnlyDirectories();
            cleanupExpired(now);
            cleanupExcessOwnRotations();
            SequenceState sequence = nextSequence();
            Map<String, Object> record = record(eventId, sequence, now, event);
            byte[] line = (JSON.writeValueAsString(record) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            if (line.length > MAX_EVENT_BYTES) {
                throw new IllegalArgumentException(
                        "audit event exceeds " + MAX_EVENT_BYTES + " bytes");
            }
            rotateIfNeeded(line.length);
            appendLine(line);
            return new Receipt(eventId, now.toString(), workspaceId);
        });
        if (Duration.between(lastOrphanSweep, now).abs().compareTo(Duration.ofDays(1)) >= 0) {
            cleanupOrphanProjects(now);
            lastOrphanSweep = now;
        }
        return receipt;
    }

    public String workspaceId() {
        return workspaceId;
    }

    public String projectHash() {
        return projectHash;
    }

    public Path streamPath() {
        return stream;
    }

    public Path projectAuditDirectory() {
        return projectDirectory;
    }

    private Map<String, Object> record(String eventId, SequenceState sequence, Instant now,
            AuditEvent event) {
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("capabilities", event.actor().capabilities().stream().sorted().toList());
        actor.put("client_id", event.actor().clientId());
        actor.put("display_name", event.actor().displayName());
        actor.put("provider", event.actor().provider());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("module", event.targetModule());
        target.put("ontology", event.targetOntology());
        target.put("project_hash", projectHash);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("committed", event.committed());
        result.put("gate", event.gate());
        result.put("outcome", event.outcome().json());

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("actor", actor);
        record.put("confirmation_references", event.confirmationReferences());
        record.put("event_id", eventId);
        record.put("operation", event.operation());
        record.put("release_manifest", event.releaseManifest());
        record.put("result", result);
        record.put("schema_version", SCHEMA_VERSION);
        record.put("sequence", sequence.next());
        if (sequence.recoveredBytes() > 0 || sequence.addedTerminator()) {
            record.put("stream_recovery", Map.of(
                    "added_missing_terminator", sequence.addedTerminator(),
                    "discarded_torn_bytes", sequence.recoveredBytes()));
        }
        record.put("summary", AuditRedactor.redact(event.summary()));
        record.put("target", target);
        record.put("timestamp", now.toString());
        record.put("workspace_id", workspaceId);
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) AuditRedactor.redact(record);
        return redacted;
    }

    private void appendLine(byte[] line) {
        try {
            if (Files.exists(stream, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(stream)) {
                throw new IllegalStateException("audit stream must not be a symbolic link");
            }
            if (!Files.exists(stream, LinkOption.NOFOLLOW_LINKS)) createOwnerOnlyFile(stream);
            restrictFile(stream);
            OpenOption[] options = {StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS};
            try (FileChannel channel = FileChannel.open(stream, options)) {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not append owner-only audit event", e);
        }
    }

    private void rotateIfNeeded(int nextBytes) {
        int maxFiles = destructiveMaxFiles();
        long maxFileBytes = destructiveMaxFileBytes();
        try {
            if (!Files.exists(stream, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(stream) == 0
                    || Files.size(stream) + nextBytes <= maxFileBytes) return;
            Files.deleteIfExists(rotated(maxFiles - 1));
            for (int i = maxFiles - 2; i >= 1; i--) {
                Path from = rotated(i);
                if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) move(from, rotated(i + 1));
            }
            move(stream, rotated(1));
        } catch (IOException e) {
            throw new UncheckedIOException("could not rotate audit stream", e);
        }
    }

    private void cleanupExpired(Instant now) {
        // Every stream in the active project directory is subject to this project's retention
        // setting, not just the current workspace's: each plugin/stdio session appends under a
        // fresh workspace id and never touches its files again, so an owner-only sweep would let
        // finished-session streams accumulate until the export caps are permanently exceeded.
        // The project shard lock held here excludes sibling appends, and a live sibling's stream
        // is only removed once every one of its events has itself expired (mtime before cutoff);
        // that sibling then restarts exactly as it would after its own retention sweep. Sibling
        // sweeping requires policy-derived settings: a session running on fallback defaults
        // (invalid or unreadable policy) must not delete history that the authored policy retains
        // for longer, so it sweeps only its own files.
        Instant cutoff = now.minus(destructiveRetentionDays(), ChronoUnit.DAYS);
        try (var entries = Files.list(projectDirectory)) {
            entries.filter(this::isSweepable).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()
                            .isBefore(cutoff)) Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Retention is best-effort; append/rotation remains fail-closed below.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup must never prevent a fresh attributable event.
        }
    }

    /** Enforce a reduced max-files policy against rotations created by an older setting. */
    private void cleanupExcessOwnRotations() {
        // Only an authored (policy-derived) max_files may destroy rotations: a session running on
        // fallback defaults (invalid or unreadable policy, maxFiles=10) must not delete rotations
        // an authored max_files of up to 100 retains — the same invariant cleanupExpired enforces
        // for the time-based sweep.
        if (!settings.policyDerived()) return;
        String prefix = workspaceId + ".jsonl.";
        try (var entries = Files.list(projectDirectory)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith(prefix)) continue;
                String suffix = name.substring(prefix.length());
                if (!suffix.matches("[1-9][0-9]*") || !isExcessRotation(suffix)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(path)) {
                    throw new IOException("excess audit rotation is not a regular owner file");
                }
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("could not enforce the audit rotation count", failure);
        }
    }

    private boolean isExcessRotation(String suffix) {
        // maxFiles is at most 100, so any longer well-formed suffix is necessarily stale.
        if (suffix.length() > 3) return true;
        return Integer.parseInt(suffix) >= settings.maxFiles();
    }

    private SequenceState nextSequence() throws IOException {
        long tornBytes = 0;
        for (int index = 0; index < destructiveMaxFiles(); index++) {
            Path candidate = index == 0 ? stream : rotated(index);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate)) {
                throw new IOException("audit sequence source is not a regular owner file");
            }
            long size = Files.size(candidate);
            if (size == 0) continue;
            int length = (int) Math.min(size, 2L * MAX_EVENT_BYTES + 2L);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                channel.position(size - length);
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                    // Fill the bounded tail.
                }
            }
            byte[] tail = buffer.array();
            int available = buffer.position();
            long tailOffset = size - available;
            int cursor = available;
            boolean newest = true;
            while (cursor > 0) {
                int contentEnd = cursor;
                boolean terminated = tail[contentEnd - 1] == '\n';
                if (terminated) contentEnd--;
                if (contentEnd > 0 && tail[contentEnd - 1] == '\r') contentEnd--;
                int precedingNewline = -1;
                for (int i = contentEnd - 1; i >= 0; i--) {
                    if (tail[i] == '\n') {
                        precedingNewline = i;
                        break;
                    }
                }
                int contentStart = precedingNewline + 1;
                if (tailOffset > 0 && contentStart == 0) break;
                try {
                    var node = JSON.readTree(tail, contentStart, contentEnd - contentStart);
                    if (node != null && node.path("sequence").canConvertToLong()
                            && node.path("sequence").asLong() >= 1) {
                        long next = Math.addExact(node.path("sequence").asLong(), 1L);
                        if (newest && !terminated) {
                            appendMissingTerminator(candidate);
                            return new SequenceState(next, tornBytes, true);
                        }
                        long validEnd = tailOffset + cursor;
                        long discarded = size - validEnd;
                        if (discarded > 0) truncate(candidate, validEnd);
                        return new SequenceState(next, discarded + tornBytes, false);
                    }
                } catch (RuntimeException | IOException invalidTail) {
                    // A crash may leave only the final line torn. Walk back to the previous complete
                    // bounded event and truncate the torn suffix while holding the project lock.
                }
                newest = false;
                cursor = contentStart;
            }
            if (tailOffset == 0) {
                // The whole candidate is one torn line. Discard it, but keep consulting the rotated
                // predecessors: they may still hold committed sequences that must not be reissued.
                truncate(candidate, 0);
                tornBytes += size;
                continue;
            }
            throw new IOException("audit tail contains no recoverable bounded event");
        }
        return new SequenceState(1, tornBytes, false);
    }

    private int destructiveRetentionDays() {
        return settings.policyDerived()
                ? settings.retentionDays() : AuditSettings.MAX_RETENTION_DAYS;
    }

    private long destructiveMaxFileBytes() {
        return settings.policyDerived()
                ? settings.maxFileBytes() : AuditSettings.MAX_FILE_BYTES;
    }

    private int destructiveMaxFiles() {
        return settings.policyDerived() ? settings.maxFiles() : AuditSettings.MAX_FILES;
    }

    private static void appendMissingTerminator(Path candidate) throws IOException {
        try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
            channel.force(true);
        }
    }

    private static void truncate(Path candidate, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(candidate, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            channel.truncate(size);
            channel.force(true);
        }
    }

    private void cleanupOrphanProjects(Instant now) {
        // A project hash is one-way, so its original policy cannot be recovered here. Use the
        // schema's maximum retention for sibling sweeps; active projects enforce their own shorter
        // setting on every append without risking cross-project data loss.
        Instant cutoff = now.minus(ORPHAN_RETENTION_DAYS, ChronoUnit.DAYS);
        try (var entries = Files.list(auditRoot)) {
            entries.filter(path -> !path.equals(projectDirectory))
                    .filter(path -> PROJECT_HASH.matcher(path.getFileName().toString()).matches())
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(path))
                    .limit(10_000)
                    .forEach(path -> AuditFileMutex.withLock(auditRoot,
                            path.getFileName().toString(), () -> {
                                deleteIfEntirelyExpired(path, cutoff);
                                return null;
                            }));
        } catch (IOException | RuntimeException ignored) {
            // Root-wide retention is best-effort and must not invalidate the event just committed.
        }
    }

    private static void deleteIfEntirelyExpired(Path directory, Instant cutoff) throws IOException {
        List<Path> files;
        try (var entries = Files.list(directory)) {
            files = entries.toList();
        }
        if (files.isEmpty()) {
            if (Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant()
                    .isBefore(cutoff)) Files.deleteIfExists(directory);
            return;
        }
        for (Path file : files) {
            if (!isStreamFile(file)
                    || !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant()
                            .isBefore(cutoff)) return;
        }
        for (Path file : files) Files.deleteIfExists(file);
        Files.deleteIfExists(directory);
    }

    private static boolean isStreamFile(Path path) {
        return STREAM_FILE.matcher(path.getFileName().toString()).matches()
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    private boolean isSweepable(Path path) {
        if (!isStreamFile(path)) return false;
        if (settings.policyDerived()) return true;
        // Exact own-file match: SAFE_ID allows dots, so a prefix test could adopt a sibling
        // workspace whose id extends this one with ".jsonl".
        String name = path.getFileName().toString();
        return name.equals(workspaceId + ".jsonl")
                || name.matches(Pattern.quote(workspaceId) + "\\.jsonl\\.[1-9][0-9]*");
    }

    private Path rotated(int index) {
        return projectDirectory.resolve(workspaceId + ".jsonl." + index);
    }

    private static void move(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(target)) {
            throw new IOException("audit rotation refuses symbolic links");
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictFile(target);
    }

    private void ensureOwnerOnlyDirectories() {
        try {
            Path parent = auditRoot.getParent();
            if (parent != null && Files.isSymbolicLink(parent)) {
                throw new IllegalStateException("audit home must not be a symbolic link");
            }
            if (Files.exists(auditRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(auditRoot)) {
                throw new IllegalStateException("audit root must not be a symbolic link");
            }
            Files.createDirectories(auditRoot);
            restrictDirectory(auditRoot);
            if (Files.exists(projectDirectory, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(projectDirectory)) {
                throw new IllegalStateException("audit project directory must not be a symbolic link");
            }
            Files.createDirectories(projectDirectory);
            restrictDirectory(projectDirectory);
            if (!projectDirectory.toRealPath().startsWith(auditRoot.toRealPath())) {
                throw new IllegalStateException("audit project directory escaped its owner-only root");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not prepare owner-only audit directory", e);
        }
    }

    private static void createOwnerOnlyFile(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_PERMS));
        } catch (UnsupportedOperationException nonPosix) {
            Files.createFile(path);
            restrictFile(path);
        }
    }

    static void restrictDirectory(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, DIR_PERMS);
        } catch (UnsupportedOperationException nonPosix) {
            // Platform ACLs are the available owner boundary.
        }
    }

    static void restrictFile(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMS);
        } catch (UnsupportedOperationException nonPosix) {
            // Platform ACLs are the available owner boundary.
        }
    }

    private static String checkedId(String id, String label) {
        if (!SAFE_ID.matcher(id == null ? "" : id).matches()) {
            throw new IllegalArgumentException("unsafe audit " + label + " id");
        }
        return id;
    }

    private static Path defaultRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home is required for the local audit store");
        }
        return Path.of(home, ".protege-mcp", "audit");
    }

    private record SequenceState(long next, long recoveredBytes, boolean addedTerminator) {
    }
}
