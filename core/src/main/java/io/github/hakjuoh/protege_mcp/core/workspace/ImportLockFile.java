package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/** Strict, deterministic parser/renderer for {@code imports.lock.json}. */
public final class ImportLockFile {

    public static final int VERSION = 1;
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("version", "imports");
    private static final Set<String> ENTRY_FIELDS =
            Set.of("ontology_iri", "version_iri", "document", "sha256", "direct");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private ImportLockFile() {
    }

    /** Read the lock in one bounded pass so a growing file cannot evade the size limit. */
    public static Document read(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        Path source = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("lock file is missing or exceeds " + MAX_BYTES + " bytes");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(source)) {
            bytes = input.readNBytes(Math.toIntExact(MAX_BYTES + 1));
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("lock file is missing or exceeds " + MAX_BYTES + " bytes");
        }
        try {
            return parse(bytes, source);
        } catch (IOException invalid) {
            throw new InvalidLockException(invalid.getMessage(), ArtifactStore.sha256(bytes), invalid);
        }
    }

    /** Parse already-captured bytes relative to the lock file's directory. */
    public static Document parse(byte[] bytes, Path source) throws IOException {
        if (bytes == null || source == null) {
            throw new IllegalArgumentException("bytes and source must not be null");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("lock file exceeds " + MAX_BYTES + " bytes");
        }
        Path normalized = source.toAbsolutePath().normalize();
        Path base = normalized.getParent();
        if (base == null) {
            throw new IOException("lock file has no parent directory");
        }
        final Map<?, ?> root;
        try {
            Object value = JSON.readValue(bytes, Object.class);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IOException("lock must be an object");
            }
            root = map;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("invalid lock JSON: " + message(e), e);
        }
        Set<String> keys = stringKeys(root, "top-level lock");
        Set<String> unknown = new LinkedHashSet<>(keys);
        unknown.removeAll(TOP_LEVEL_FIELDS);
        if (!unknown.isEmpty()) {
            throw new IOException("unknown top-level lock field(s): " + unknown);
        }
        if (!Integer.valueOf(VERSION).equals(root.get("version"))) {
            throw new IOException("lock version must be integer " + VERSION);
        }
        Object imports = root.get("imports");
        if (!(imports instanceof List<?> list)) {
            throw new IOException("imports must be an array");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> entryKeys = new LinkedHashSet<>();
        for (Object value : list) {
            Entry entry = parseEntry(value, base);
            if (!entryKeys.add(entry.key())) {
                throw new IOException("duplicate lock entry: " + entry.key());
            }
            entries.add(entry);
        }
        return new Document(normalized, ArtifactStore.sha256(bytes), entries);
    }

    /** Parse one entry with portable path containment and strict field validation. */
    public static Entry parseEntry(Object value, Path base) throws IOException {
        if (!(value instanceof Map<?, ?> row)) {
            throw new IOException("lock entry must be an object");
        }
        Set<String> keys = stringKeys(row, "lock entry");
        if (!ENTRY_FIELDS.containsAll(keys)) {
            throw new IOException("unknown lock entry field");
        }
        String document = string(row.get("document"));
        final boolean portableAbsolutePath;
        try {
            portableAbsolutePath = portableAbsolute(document);
        } catch (RuntimeException invalid) {
            throw new IOException("lock document is not a valid path", invalid);
        }
        if (document.isBlank() || portableAbsolutePath || document.indexOf('\\') >= 0) {
            throw new IOException("lock document must be a non-empty portable relative path");
        }
        final Path relative;
        try {
            relative = Path.of(document);
        } catch (RuntimeException invalid) {
            throw new IOException("lock document is not a valid path", invalid);
        }
        if (!relative.normalize().equals(relative)) {
            throw new IOException("lock document must be normalized");
        }
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path absolute = normalizedBase.resolve(relative).normalize();
        if (!absolute.startsWith(normalizedBase) || absolute.equals(normalizedBase)) {
            throw new IOException("lock document escapes lock directory");
        }
        String ontologyIri = string(row.get("ontology_iri"));
        if (ontologyIri.isBlank() || !isAbsoluteIri(ontologyIri)) {
            throw new IOException("ontology_iri must be an absolute IRI");
        }
        String versionIri = string(row.get("version_iri"));
        if (!versionIri.isBlank() && !isAbsoluteIri(versionIri)) {
            throw new IOException("version_iri must be null or an absolute IRI");
        }
        String sha256 = string(row.get("sha256"));
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("sha256 must be 64 lowercase hex digits");
        }
        if (!(row.get("direct") instanceof Boolean direct)) {
            throw new IOException("direct must be boolean");
        }
        return new Entry(ontologyIri, versionIri.isBlank() ? null : versionIri,
                document, sha256, direct, absolute);
    }

    /** Render entries in canonical identity-key order with a trailing newline. */
    public static byte[] render(List<Entry> entries) throws IOException {
        if (entries == null || entries.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("entries must not contain null");
        }
        List<Entry> ordered = entries.stream().sorted(Comparator.comparing(Entry::key)).toList();
        Set<String> keys = new LinkedHashSet<>();
        List<Map<String, Object>> imports = new ArrayList<>();
        for (Entry entry : ordered) {
            if (!keys.add(entry.key())) {
                throw new IOException("duplicate lock entry: " + entry.key());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ontology_iri", entry.ontologyIri());
            row.put("version_iri", entry.versionIri());
            row.put("document", entry.document());
            row.put("sha256", entry.sha256());
            row.put("direct", entry.direct());
            imports.add(row);
        }
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("version", VERSION);
        lock.put("imports", imports);
        return (JSON.writeValueAsString(lock) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Set<String> stringKeys(Map<?, ?> map, String context) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String value)) {
                throw new IOException(context + " field names must be strings");
            }
            keys.add(value);
        }
        return keys;
    }

    private static boolean portableAbsolute(String value) {
        return Path.of(value).isAbsolute() || WINDOWS_ABSOLUTE.matcher(value).matches()
                || value.startsWith("//");
    }

    private static boolean isAbsoluteIri(String value) {
        try {
            return URI.create(value).isAbsolute();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record Document(Path path, String sha256, List<Entry> entries) {
        public Document {
            path = path.toAbsolutePath().normalize();
            entries = List.copyOf(entries);
        }

        public Map<String, Entry> entriesByKey() {
            Map<String, Entry> result = new LinkedHashMap<>();
            entries.forEach(entry -> result.put(entry.key(), entry));
            return result;
        }
    }

    /** Parse failure after a complete bounded read; carries the digest of the rejected bytes. */
    public static final class InvalidLockException extends IOException {
        private final String sha256;

        private InvalidLockException(String message, String sha256, Throwable cause) {
            super(message, cause);
            this.sha256 = sha256;
        }

        public String sha256() {
            return sha256;
        }
    }

    public record Entry(String ontologyIri, String versionIri, String document, String sha256,
            boolean direct, Path absolute) {
        public Entry {
            if (ontologyIri == null || document == null || sha256 == null || absolute == null) {
                throw new IllegalArgumentException("lock entry fields must not be null");
            }
            if (!isAbsoluteIri(ontologyIri)
                    || versionIri != null && !versionIri.isBlank() && !isAbsoluteIri(versionIri)
                    || document.isBlank() || document.indexOf('\\') >= 0
                    || portableAbsolute(document) || !Path.of(document).normalize().equals(Path.of(document))
                    || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid lock entry fields");
            }
            absolute = absolute.toAbsolutePath().normalize();
        }

        public String key() {
            return versionIri == null || versionIri.isBlank()
                    ? ontologyIri : ontologyIri + " @ " + versionIri;
        }
    }
}
