package io.github.hakjuoh.protege_mcp.core.audit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/** Deterministically merge owner-only streams into one explicitly requested, re-redacted artifact. */
public final class AuditExportService {

    public static final String DEFAULT_OUTPUT = ".protege-mcp/audit-export.jsonl";
    private static final long MAX_SOURCE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_SOURCES = 1000;
    private static final int MAX_EVENTS = 20_000;
    private static final Pattern STREAM = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.jsonl(?:\\.[1-9][0-9]*)?$");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern EXPORT_FILE = Pattern.compile(
            "^audit-export[A-Za-z0-9._-]*\\.jsonl$");
    private static final Set<PosixFilePermission> NON_OWNER_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public record Result(String path, String sha256, long bytes, int eventCount, int sourceCount) {
    }

    private AuditExportService() {
    }

    public static Result export(Path projectAuditDirectory, Path projectRoot, String output) {
        if (projectAuditDirectory == null || projectRoot == null) {
            throw new IllegalArgumentException("audit export paths are required");
        }
        Path directory = projectAuditDirectory.toAbsolutePath().normalize();
        Path root = directory.getParent();
        String projectHash = directory.getFileName().toString();
        if (root == null || !projectHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("audit export source is not a project audit directory");
        }
        String relative = previewOutput(projectRoot, output);
        return AuditFileMutex.withLock(root, projectHash,
                () -> exportLocked(directory, projectRoot, relative));
    }

    /** Validate and normalize the dedicated project-relative export target without writing. */
    public static String previewOutput(Path projectRoot, String output) {
        if (projectRoot == null) throw new IllegalArgumentException("audit export project is required");
        String configured = output == null || output.isBlank() ? DEFAULT_OUTPUT : output;
        if (configured.length() > 4096) {
            throw new IllegalArgumentException("audit export output exceeds 4096 characters");
        }
        final Path relative;
        try {
            relative = Path.of(configured).normalize();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("audit export output is not a valid path", invalid);
        }
        if (relative.isAbsolute() || relative.getNameCount() != 2
                || !".protege-mcp".equals(relative.getName(0).toString())
                || !EXPORT_FILE.matcher(relative.getFileName().toString()).matches()) {
            throw new IllegalArgumentException("audit export output must match "
                    + ".protege-mcp/audit-export*.jsonl");
        }
        try {
            projectRoot.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("could not identify audit export project", e);
        }
        return relative.toString().replace('\\', '/');
    }

    private static Result exportLocked(Path projectAuditDirectory, Path projectRoot, String relative) {
        List<Map<String, Object>> events = new ArrayList<>();
        List<Path> sources = sources(projectAuditDirectory);
        long bytes = 0;
        for (Path source : sources) {
            try {
                bytes = Math.addExact(bytes, read(source, events, MAX_SOURCE_BYTES - bytes));
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("audit export source size overflow", overflow);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read audit stream for export", e);
            }
        }
        events.sort(Comparator.<Map<String, Object>, Instant>comparing(
                event -> Instant.parse(timestamp(event)))
                .thenComparing(AuditExportService::workspaceId)
                .thenComparingLong(AuditExportService::sequence)
                .thenComparing(AuditExportService::eventId));
        StringBuilder jsonl = new StringBuilder();
        try {
            for (Map<String, Object> event : events) {
                jsonl.append(JSON.writeValueAsString(AuditRedactor.redact(event))).append('\n');
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("could not encode audit export", impossible);
        }
        ArtifactStore.Written written = new ArtifactStore(projectRoot).writeText(relative, jsonl.toString());
        return new Result(written.path(), written.sha256(), written.bytes(), events.size(), sources.size());
    }

    private static List<Path> sources(Path directory) {
        try {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("audit source directory is missing or unsafe");
            }
            List<Path> sources = new ArrayList<>();
            try (var entries = Files.list(directory)) {
                entries.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .filter(path -> STREAM.matcher(path.getFileName().toString()).matches())
                        .forEach(path -> {
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    || Files.isSymbolicLink(path)) {
                                throw new IllegalArgumentException("audit export refuses non-regular streams");
                            }
                            requireOwnerOnly(path);
                            sources.add(path);
                            if (sources.size() > MAX_SOURCES) {
                                throw new IllegalArgumentException(
                                        "audit export contains more than " + MAX_SOURCES + " streams");
                            }
                        });
            }
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException("could not enumerate audit streams", e);
        }
    }

    private static long read(Path source, List<Map<String, Object>> events, long remainingBytes)
            throws IOException {
        if (remainingBytes < 0) throw new IllegalArgumentException("audit export sources exceed 16 MiB");
        long bytes = 0;
        ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS); var ignored = channel.lock(0, Long.MAX_VALUE, true)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int read;
            while ((read = channel.read(buffer)) != -1) {
                if (read == 0) continue;
                bytes += read;
                if (bytes > remainingBytes) {
                    throw new IllegalArgumentException("audit export sources exceed 16 MiB");
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte value = buffer.get();
                    if (value == '\n') {
                        parseLine(line.toByteArray(), events);
                        line.reset();
                    } else {
                        if (line.size() >= AuditLog.MAX_EVENT_BYTES) {
                            throw new IllegalArgumentException("audit export contains an oversized event");
                        }
                        line.write(value);
                    }
                }
                buffer.clear();
            }
        }
        if (line.size() != 0) parseLine(line.toByteArray(), events);
        return bytes;
    }

    private static void parseLine(byte[] bytes, List<Map<String, Object>> events) throws IOException {
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') length--;
        if (length == 0) return;
        Map<String, Object> event = JSON.readValue(bytes, 0, length,
                new TypeReference<LinkedHashMap<String, Object>>() { });
        String timestamp = timestamp(event);
        String eventId = eventId(event);
        String workspaceId = workspaceId(event);
        try {
            Instant.parse(timestamp);
        } catch (RuntimeException invalidTimestamp) {
            throw new IllegalArgumentException("audit export contains an invalid timestamp",
                    invalidTimestamp);
        }
        if (!Integer.valueOf(AuditLog.SCHEMA_VERSION).equals(event.get("schema_version"))
                || !SAFE_ID.matcher(eventId).matches() || !SAFE_ID.matcher(workspaceId).matches()
                || sequence(event) < 1) {
            throw new IllegalArgumentException("audit export contains an invalid event envelope");
        }
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("audit export contains more than " + MAX_EVENTS + " events");
        }
    }

    private static void requireOwnerOnly(Path path) {
        try {
            if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return;
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(NON_OWNER_PERMISSIONS::contains)) {
                throw new IllegalArgumentException("audit export refuses a non-owner-only stream");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not verify audit stream permissions", e);
        }
    }

    private static String timestamp(Map<String, Object> event) {
        return string(event.get("timestamp"));
    }

    private static String eventId(Map<String, Object> event) {
        return string(event.get("event_id"));
    }

    private static String workspaceId(Map<String, Object> event) {
        return string(event.get("workspace_id"));
    }

    private static long sequence(Map<String, Object> event) {
        Object value = event.get("sequence");
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) return -1;
        return number.longValue();
    }

    private static String string(Object value) {
        return value instanceof String text ? text : "";
    }
}
