package io.github.hakjuoh.protege_mcp.sssom;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One immutable SSSOM 1.0 row, including losslessly retained extension columns. */
public final class MappingRecord {

    public static final List<String> IDENTITY_COLUMNS = List.of(
            "subject_id", "predicate_id", "predicate_modifier", "object_id", "mapping_justification",
            "author_id", "mapping_source", "mapping_provider");
    private static final Set<String> REFERENCE_IDENTITY_COLUMNS = Set.of(
            "subject_id", "predicate_id", "object_id", "mapping_justification",
            "mapping_source", "mapping_provider");
    private static final Set<String> OPAQUE_IRI_SCHEMES = Set.of(
            "doi", "mailto", "sha256", "tag", "urn");

    private final Map<String, String> cells;

    public MappingRecord(Map<String, String> cells) {
        if (cells == null) throw new IllegalArgumentException("mapping cells are required");
        Map<String, String> copy = new LinkedHashMap<>();
        cells.forEach((column, value) -> {
            if (column == null || column.isBlank() || value == null) {
                throw new IllegalArgumentException("mapping columns and values must not be null");
            }
            copy.put(column, value);
        });
        this.cells = Collections.unmodifiableMap(copy);
    }

    public Map<String, String> cells() {
        return cells;
    }

    public String value(String column) {
        return cells.getOrDefault(column, "");
    }

    public String mappingId() {
        return value("mapping_id");
    }

    public String subjectId() {
        return value("subject_id");
    }

    public String predicateId() {
        return value("predicate_id");
    }

    public String objectId() {
        return value("object_id");
    }

    MappingRecord withGeneratedId(Map<String, Object> metadata, Set<String> rowDefinedColumns,
            Map<String, String> prefixMap) {
        if (!mappingId().isBlank()) return this;
        Map<String, String> copy = new LinkedHashMap<>(cells);
        copy.put("mapping_id", deterministicId(cells, metadata, rowDefinedColumns, prefixMap));
        return new MappingRecord(copy);
    }

    public MappingRecord withCells(Map<String, String> replacements) {
        Map<String, String> copy = new LinkedHashMap<>(cells);
        replacements.forEach((key, value) -> copy.put(key, value == null ? "" : value));
        return new MappingRecord(copy);
    }

    /** Stable id over the normalized identity fields named by the 0.8 contract. */
    public static String deterministicId(Map<String, String> cells) {
        return deterministicId(cells, Map.of(), Set.of(), Map.of());
    }

    static String deterministicId(Map<String, String> cells, Map<String, Object> metadata,
            Set<String> rowDefinedColumns, Map<String, String> prefixMap) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        Map<String, String> prefixes = new LinkedHashMap<>(SssomParser.BUILTIN_PREFIXES);
        if (prefixMap != null) prefixMap.forEach(prefixes::putIfAbsent);
        for (String column : IDENTITY_COLUMNS) {
            String value;
            if ("subject_id".equals(column) && literal(cells, metadata, rowDefinedColumns, "subject")) {
                value = "literal:" + normalized("subject_label",
                        cells == null ? null : cells.get("subject_label"));
            } else if ("subject_id".equals(column)) {
                value = "iri:" + normalized(column,
                        reference(effective(cells, metadata, rowDefinedColumns, column), prefixes));
            } else if ("object_id".equals(column)
                    && literal(cells, metadata, rowDefinedColumns, "object")) {
                value = "literal:" + normalized("object_label",
                        cells == null ? null : cells.get("object_label"));
            } else if ("object_id".equals(column)) {
                value = "iri:" + normalized(column,
                        reference(effective(cells, metadata, rowDefinedColumns, column), prefixes));
            } else if ("author_id".equals(column)) {
                value = canonicalReferences(
                        effective(cells, metadata, rowDefinedColumns, column), prefixes);
            } else if (REFERENCE_IDENTITY_COLUMNS.contains(column)) {
                value = normalized(column, reference(
                        effective(cells, metadata, rowDefinedColumns, column), prefixes));
            } else {
                value = normalized(column, effective(cells, metadata, rowDefinedColumns, column));
            }
            byte[] name = column.getBytes(StandardCharsets.UTF_8);
            byte[] content = value.getBytes(StandardCharsets.UTF_8);
            digest.update(intBytes(name.length));
            digest.update(name);
            digest.update(intBytes(content.length));
            digest.update(content);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return "sha256:" + hex;
    }

    private static boolean literal(Map<String, String> cells, Map<String, Object> metadata,
            Set<String> rowDefinedColumns, String side) {
        String type = effective(cells, metadata, rowDefinedColumns, side + "_type");
        return "rdfs literal".equalsIgnoreCase(type) || "rdfs:Literal".equals(type);
    }

    private static String effective(Map<String, String> cells, Map<String, Object> metadata,
            Set<String> rowDefinedColumns, String column) {
        String row = cells == null ? null : cells.get(column);
        if (row != null && !row.isBlank()) return row;
        if (!SssomDocument.PROPAGATABLE_COLUMNS.contains(column)
                || rowDefinedColumns.contains(column)) return "";
        Object inherited = metadata.get(column);
        return inherited instanceof String text ? text : "";
    }

    static String normalized(String column, String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if ("author_id".equals(column)) {
            return SssomListValues.canonical(normalized);
        }
        return normalized;
    }

    private static String canonicalReferences(String raw, Map<String, String> prefixes) {
        List<String> values = SssomListValues.decode(raw);
        StringBuilder canonical = new StringBuilder().append(values.size()).append(':');
        for (String value : values) {
            String normalized = reference(value, prefixes).trim();
            canonical.append(normalized.length()).append(':').append(normalized);
        }
        return canonical.toString();
    }

    private static String reference(String raw, Map<String, String> prefixes) {
        if (raw == null) return "";
        String value = raw.trim();
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return value;
        String scheme = value.substring(0, colon);
        String base = prefixes.get(scheme);
        if (base != null) return base + value.substring(colon + 1);
        boolean unambiguous = value.regionMatches(true, colon + 1, "//", 0, 2)
                || OPAQUE_IRI_SCHEMES.contains(scheme.toLowerCase(java.util.Locale.ROOT));
        if (!unambiguous) return value;
        try {
            URI.create(value);
            return value;
        } catch (IllegalArgumentException invalid) {
            return value;
        }
    }

    /** Canonicalize one SSSOM reference using the required built-in prefix map. */
    public static String canonicalReference(String raw) {
        return reference(raw, SssomParser.BUILTIN_PREFIXES);
    }

    private static byte[] intBytes(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MappingRecord record && cells.equals(record.cells);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cells);
    }

    @Override
    public String toString() {
        return "MappingRecord[" + mappingId() + "]";
    }
}
