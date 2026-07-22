package io.github.hakjuoh.protege_mcp.sssom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Adapter-neutral application service for the six public SSSOM mapping operations. */
public final class SssomToolService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_PAGE_JSON_BYTES = 1_048_576;
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final ObjectMapper JSON = new ObjectMapper();

    private SssomToolService() {
    }

    public static Map<String, Object> list(SssomMappingStore store,
            SssomValidationPolicy policy, SssomEntityIndex entities,
            int limit, String cursor) throws IOException {
        int pageSize = pageSize(limit);
        SssomMappingStore.Snapshot snapshot = store.read(policy, entities);
        int offset = cursorOffset(cursor, "mappings", snapshot.mappingRevision());
        List<MappingRecord> records = snapshot.validation().document().records();
        if (offset > records.size()) {
            throw failure("cursor_invalid", "mapping cursor offset exceeds the result set", true);
        }
        int requestedEnd = Math.min(records.size(), offset + pageSize);
        int end = offset;
        Map<String, Object> result = snapshotJson(snapshot);
        // Reserve the complete response envelope, including a maximum-size continuation cursor.
        // Replacing the empty array below adds only each serialized item plus its separators.
        result.put("items", List.of());
        result.put("returned", MAX_PAGE_SIZE);
        result.put("next_cursor", "x".repeat(MAX_CURSOR_LENGTH));
        // Keep the empty-array brackets in the reservation: the populated array retains them.
        int pageBytes = JSON.writeValueAsBytes(result).length;
        List<Map<String, Object>> items = new ArrayList<>();
        while (end < requestedEnd) {
            Map<String, Object> item = recordJson(records.get(end));
            int itemBytes = JSON.writeValueAsBytes(item).length;
            int separatorBytes = items.isEmpty() ? 0 : 1;
            if (items.isEmpty()
                    && pageBytes + separatorBytes + itemBytes > MAX_PAGE_JSON_BYTES) {
                throw failure("mapping_record_too_large",
                        "one mapping row exceeds the bounded MCP page; use export_sssom for the full artifact",
                        true);
            }
            if (pageBytes + separatorBytes + itemBytes > MAX_PAGE_JSON_BYTES) break;
            items.add(item);
            pageBytes += separatorBytes + itemBytes;
            end++;
        }
        result.put("items", List.copyOf(items));
        result.put("returned", items.size());
        if (end < records.size()) {
            result.put("next_cursor", cursor("mappings", snapshot.mappingRevision(), end));
        } else {
            result.remove("next_cursor");
        }
        return result;
    }

    public static Map<String, Object> validate(SssomMappingStore store,
            SssomValidationPolicy policy, SssomEntityIndex entities,
            int limit, String cursor) throws IOException {
        int pageSize = pageSize(limit);
        SssomMappingStore.Snapshot snapshot = store.read(policy, entities);
        String findingsRevision = findingsRevision(snapshot);
        int offset = cursorOffset(cursor, "findings", findingsRevision);
        List<SssomFinding> findings = snapshot.validation().findings();
        if (offset > findings.size()) {
            throw failure("cursor_invalid", "finding cursor offset exceeds the result set", true);
        }
        int end = Math.min(findings.size(), offset + pageSize);
        List<Map<String, Object>> items = findings.subList(offset, end).stream()
                .map(SssomToolService::findingJson).toList();
        Map<String, Object> result = snapshotJson(snapshot);
        result.put("findings", items);
        result.put("returned", items.size());
        if (end < findings.size()) {
            result.put("next_cursor", cursor("findings", findingsRevision, end));
        }
        return result;
    }

    public static Map<String, Object> add(SssomMappingStore store, String expectedRevision,
            Map<String, String> cells, Map<String, Object> initialMetadata,
            Map<String, String> initialPrefixes, SssomValidationPolicy policy,
            SssomEntityIndex entities, SssomMappingStore.MutationGuard guard) throws IOException {
        return add(store, expectedRevision, cells, initialMetadata, initialPrefixes,
                policy, entities, guard, null);
    }

    /** Add with an optional locked-baseline existence precondition. */
    public static Map<String, Object> add(SssomMappingStore store, String expectedRevision,
            Map<String, String> cells, Map<String, Object> initialMetadata,
            Map<String, String> initialPrefixes, SssomValidationPolicy policy,
            SssomEntityIndex entities, SssomMappingStore.MutationGuard guard,
            Boolean expectedExists) throws IOException {
        SssomMappingStore.Mutation mutation = store.add(expectedRevision,
                new MappingRecord(cells), initialMetadata, initialPrefixes,
                policy, entities, guard, expectedExists);
        return mutationJson(mutation);
    }

    public static Map<String, Object> remove(SssomMappingStore store, String expectedRevision,
            String mappingId, SssomValidationPolicy policy, SssomEntityIndex entities,
            SssomMappingStore.MutationGuard guard) throws IOException {
        return mutationJson(store.remove(expectedRevision, mappingId, policy, entities, guard));
    }

    public static Map<String, Object> importSssom(SssomMappingStore store,
            String expectedRevision, Path source, SssomMappingStore.ImportMode mode,
            SssomValidationPolicy policy, SssomEntityIndex entities,
            SssomMappingStore.MutationGuard guard) throws IOException {
        if (source == null) throw new IllegalArgumentException("import source is required");
        SssomMappingStore.FileImport imported = store.importFile(expectedRevision,
                source, mode, policy, entities, guard);
        Map<String, Object> result = mutationJson(imported.mutation());
        result.put("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        result.put("source_records", imported.sourceRecords());
        return result;
    }

    public static Map<String, Object> exportSssom(SssomMappingStore store,
            String expectedMappingRevision, Path destination, boolean overwrite,
            String expectedTargetDigest, boolean spreadsheetSafe,
            SssomValidationPolicy policy, SssomEntityIndex entities,
            SssomMappingStore.MutationGuard guard) throws IOException {
        SssomMappingStore.Export exported = store.export(expectedMappingRevision,
                destination, overwrite,
                expectedTargetDigest, spreadsheetSafe, policy, entities, guard);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("committed", exported.committed());
        result.put("path", exported.path().toString());
        result.put("mapping_revision", exported.mappingRevision());
        result.put("sha256", exported.sha256());
        result.put("bytes", exported.bytes());
        if (exported.backupPath() != null) {
            result.put("backup_path", exported.backupPath().toString());
        }
        result.put("spreadsheet_safe", exported.spreadsheetSafe());
        result.put("lossless", exported.lossless());
        return result;
    }

    public static Map<String, Object> mutationJson(SssomMappingStore.Mutation mutation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("committed", mutation.committed());
        result.put("path", mutation.path().toString());
        result.put("previous_mapping_revision", mutation.previousRevision());
        result.put("mapping_revision", mutation.mappingRevision());
        result.put("record_count", mutation.recordCount());
        result.put("bytes", mutation.bytes());
        if (mutation.backupPath() != null) {
            result.put("backup_path", mutation.backupPath().toString());
        }
        putValidation(result, mutation.validation());
        return result;
    }

    private static Map<String, Object> snapshotJson(SssomMappingStore.Snapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", snapshot.path().toString());
        result.put("exists", snapshot.exists());
        result.put("mapping_revision", snapshot.mappingRevision());
        result.put("canonical_bytes", snapshot.canonicalBytes());
        result.put("record_count", snapshot.recordCount());
        putValidation(result, snapshot.validation());
        return result;
    }

    private static void putValidation(Map<String, Object> result, SssomValidator.Report report) {
        result.put("valid", report.valid());
        result.put("error_count", report.errorCount());
        result.put("warning_count", report.warningCount());
        result.put("findings_truncated", report.findingsTruncated());
    }

    private static Map<String, Object> recordJson(MappingRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mapping_id", record.mappingId());
        result.put("subject_id", record.subjectId());
        result.put("predicate_id", record.predicateId());
        result.put("object_id", record.objectId());
        result.put("cells", record.cells());
        return result;
    }

    private static Map<String, Object> findingJson(SssomFinding finding) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", finding.severity());
        result.put("code", finding.code());
        if (finding.mappingId() != null) result.put("mapping_id", finding.mappingId());
        if (finding.column() != null) result.put("column", finding.column());
        result.put("message", finding.message());
        return result;
    }

    private static int pageSize(int requested) {
        if (requested < 1 || requested > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return requested;
    }

    private static int cursorOffset(String encoded, String kind, String revision) throws IOException {
        if (encoded == null || encoded.isBlank()) return 0;
        if (encoded.length() > MAX_CURSOR_LENGTH) {
            throw failure("cursor_invalid", "mapping cursor is too long", true);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\u0000", -1);
            if (fields.length != 3 || !kind.equals(fields[0])) {
                throw new IllegalArgumentException("cursor shape");
            }
            if (!revision.equals(fields[1])) {
                throw failure("cursor_revision_conflict",
                        "mapping or validation context changed after the cursor was issued", true);
            }
            int offset = Integer.parseInt(fields[2]);
            if (offset < 0) throw new IllegalArgumentException("negative cursor");
            return offset;
        } catch (SssomStoreException typed) {
            throw typed;
        } catch (IllegalArgumentException malformed) {
            throw failure("cursor_invalid", "mapping cursor is malformed", true);
        }
    }

    private static String cursor(String kind, String revision, int offset) {
        String value = kind + "\u0000" + revision + "\u0000" + offset;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Bind validation pagination to its complete deterministic result set, not just store bytes. */
    private static String findingsRevision(SssomMappingStore.Snapshot snapshot) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        update(digest, snapshot.mappingRevision());
        update(digest, Boolean.toString(snapshot.validation().valid()));
        update(digest, Long.toString(snapshot.validation().errorCount()));
        update(digest, Long.toString(snapshot.validation().warningCount()));
        update(digest, Boolean.toString(snapshot.validation().findingsTruncated()));
        for (SssomFinding finding : snapshot.validation().findings()) {
            update(digest, finding.severity());
            update(digest, finding.code());
            update(digest, finding.mappingId());
            update(digest, finding.column());
            update(digest, finding.message());
        }
        StringBuilder revision = new StringBuilder("sha256:");
        for (byte value : digest.digest()) {
            revision.append(Character.forDigit((value >>> 4) & 0xf, 16));
            revision.append(Character.forDigit(value & 0xf, 16));
        }
        return revision.toString();
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static SssomStoreException failure(String code, String message, boolean prevented) {
        return new SssomStoreException(code, message, prevented);
    }
}
