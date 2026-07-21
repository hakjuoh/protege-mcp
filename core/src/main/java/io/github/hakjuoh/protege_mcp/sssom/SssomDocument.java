package io.github.hakjuoh.protege_mcp.sssom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable SSSOM metadata, prefix map, header, and rows. */
public record SssomDocument(Map<String, Object> metadata, Map<String, String> prefixMap,
        List<String> columns, List<MappingRecord> records) {

    public static final List<String> STANDARD_COLUMNS = List.of(
            "mapping_id", "subject_id", "subject_label", "subject_category", "subject_type",
            "predicate_id", "predicate_label", "predicate_modifier", "object_id",
            "object_label", "object_category", "object_type", "mapping_justification", "confidence",
            "author_id", "author_label", "reviewer_id", "reviewer_label", "creator_id",
            "creator_label", "license", "subject_source", "subject_source_version", "object_source",
            "object_source_version", "mapping_provider", "mapping_source", "mapping_cardinality",
            "mapping_tool", "mapping_tool_version", "mapping_date", "publication_date",
            "curation_rule", "curation_rule_text", "subject_match_field", "object_match_field",
            "match_string", "subject_preprocessing", "object_preprocessing", "similarity_score",
            "similarity_measure", "see_also", "issue_tracker_item", "other", "comment");

    public static final List<String> REQUIRED_COLUMNS = List.of(
            "mapping_id", "predicate_id", "mapping_justification");

    /** SSSOM 1.0 mapping-set slots whose values may apply to every mapping row. */
    public static final Set<String> PROPAGATABLE_COLUMNS = Set.of(
            "subject_type", "subject_source", "subject_source_version",
            "object_type", "object_source", "object_source_version", "mapping_provider",
            "mapping_tool", "mapping_tool_version", "mapping_date", "subject_match_field",
            "object_match_field", "subject_preprocessing", "object_preprocessing");

    public SssomDocument {
        metadata = immutableMap(metadata);
        prefixMap = immutableStringMap(prefixMap);
        columns = columns == null ? List.of() : List.copyOf(columns);
        records = records == null ? List.of() : List.copyOf(records);
        Set<String> unique = new LinkedHashSet<>(columns);
        if (unique.size() != columns.size()) {
            throw new IllegalArgumentException("SSSOM header contains duplicate columns");
        }
        for (MappingRecord record : records) {
            if (!unique.containsAll(record.cells().keySet())) {
                throw new IllegalArgumentException("mapping row contains a column outside the header");
            }
        }
    }

    public static SssomDocument empty() {
        return new SssomDocument(Map.of(), Map.of(),
                REQUIRED_COLUMNS, List.of());
    }

    /** Add deterministic ids, deduplicate exact repeated rows, and establish canonical ordering. */
    public SssomDocument canonical() {
        return canonical(Map.of());
    }

    /** Canonicalize with additional policy-approved prefixes used for generated identities. */
    public SssomDocument canonical(Map<String, String> approvedPrefixes) {
        Map<String, String> identityPrefixes = new LinkedHashMap<>();
        if (approvedPrefixes != null) identityPrefixes.putAll(approvedPrefixes);
        prefixMap.forEach(identityPrefixes::put);
        Set<String> rowDefinedColumns = rowDefinedColumns();

        Set<String> present = new LinkedHashSet<>();
        present.addAll(columns);
        for (MappingRecord record : records) present.addAll(record.cells().keySet());
        present.addAll(REQUIRED_COLUMNS);
        List<String> header = new ArrayList<>();
        for (String standard : STANDARD_COLUMNS) if (present.contains(standard)) header.add(standard);
        present.stream().filter(column -> !STANDARD_COLUMNS.contains(column)).sorted()
                .forEach(header::add);

        Set<MappingRecord> unique = new LinkedHashSet<>();
        for (MappingRecord raw : records) {
            Map<String, String> aligned = new LinkedHashMap<>();
            for (String column : header) aligned.put(column, raw.value(column));
            unique.add(new MappingRecord(aligned)
                    .withGeneratedId(metadata, rowDefinedColumns, identityPrefixes));
        }
        List<MappingRecord> ordered = new ArrayList<>(unique);
        ordered.sort(Comparator.comparing(MappingRecord::mappingId)
                .thenComparing(MappingRecord::subjectId)
                .thenComparing(MappingRecord::predicateId)
                .thenComparing(MappingRecord::objectId)
                .thenComparing(SssomDocument::rowKey));

        return new SssomDocument(sorted(metadata), sortedStringMap(prefixMap), header, ordered);
    }

    Set<String> rowDefinedColumns() {
        Set<String> defined = new LinkedHashSet<>();
        for (MappingRecord record : records) {
            record.cells().forEach((column, value) -> {
                if (!value.isBlank()) defined.add(column);
            });
        }
        return Collections.unmodifiableSet(defined);
    }

    public String metadataText(String key) {
        Object value = metadata.get(key);
        return value instanceof String text ? text : "";
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("SSSOM metadata entries must be non-null");
            }
            copy.put(key, immutableValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("SSSOM prefixes must be non-null");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Object> sorted(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), sortedValue(entry.getValue())));
        return result;
    }

    private static Map<String, String> sortedStringMap(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String name) || name.isBlank() || item == null) {
                    throw new IllegalArgumentException("SSSOM metadata maps require string keys and values");
                }
                copy.put(name, immutableValue(item));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> {
                if (item == null) throw new IllegalArgumentException("SSSOM metadata lists reject nulls");
                return immutableValue(item);
            }).toList();
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        throw new IllegalArgumentException("Unsupported SSSOM metadata value: "
                + value.getClass().getSimpleName());
    }

    private static Object sortedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new LinkedHashMap<>();
            map.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> sorted.put(entry.getKey().toString(), sortedValue(entry.getValue())));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(SssomDocument::sortedValue).toList();
        return value;
    }

    private static String rowKey(MappingRecord record) {
        StringBuilder key = new StringBuilder();
        record.cells().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            key.append(entry.getKey().length()).append(':').append(entry.getKey());
            key.append(entry.getValue().length()).append(':').append(entry.getValue());
        });
        return key.toString();
    }
}
