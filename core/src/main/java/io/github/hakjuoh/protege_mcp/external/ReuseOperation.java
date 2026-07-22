package io.github.hakjuoh.protege_mcp.external;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import io.github.hakjuoh.protege_mcp.sssom.MappingRecord;
import io.github.hakjuoh.protege_mcp.sssom.SssomDocument;
import io.github.hakjuoh.protege_mcp.sssom.SssomEntityIndex;
import io.github.hakjuoh.protege_mcp.sssom.SssomParser;
import io.github.hakjuoh.protege_mcp.sssom.SssomValidationPolicy;
import io.github.hakjuoh.protege_mcp.sssom.SssomValidator;

/** Typed normalized operation accepted by the later proposal-acceptance service. */
public sealed interface ReuseOperation permits ReuseOperation.ReuseIri,
        ReuseOperation.AddMapping, ReuseOperation.MintLocalWithMapping {

    ReuseAction action();

    Map<String, Object> toJson();

    void validateRequestedEntityIri(String entityIri);

    void validateAgainst(ProviderResult result);

    record ReuseIri(String entityIri) implements ReuseOperation {
        public ReuseIri {
            entityIri = ProviderValues.absoluteIri(entityIri, "entity IRI", 4_096);
        }

        @Override public ReuseAction action() { return ReuseAction.REUSE_IRI; }

        @Override public Map<String, Object> toJson() { return Map.of("entity_iri", entityIri); }

        @Override
        public void validateRequestedEntityIri(String requestedEntityIri) {
            String requested = ProviderValues.absoluteIri(
                    requestedEntityIri, "requested entity IRI", 4_096);
            if (!entityIri.equals(requested)) {
                throw new IllegalArgumentException("reuse IRI does not match requested term");
            }
        }

        @Override
        public void validateAgainst(ProviderResult result) {
            requireResult(result);
            validateRequestedEntityIri(result.entityIri());
        }
    }

    record AddMapping(Map<String, String> mappingCells) implements ReuseOperation {
        public AddMapping {
            mappingCells = mapping(mappingCells);
            requireDistinctEndpoints(mappingCells);
        }

        @Override public ReuseAction action() { return ReuseAction.ADD_MAPPING; }

        @Override public Map<String, Object> toJson() { return Map.of("mapping", mappingCells); }

        @Override
        public void validateRequestedEntityIri(String entityIri) {
            requireReferences(mappingCells, entityIri);
        }

        @Override
        public void validateAgainst(ProviderResult result) {
            requireResult(result);
            validateRequestedEntityIri(result.entityIri());
        }
    }

    record MintLocalWithMapping(String localEntityIri, MintedEntityType entityType,
            List<ProviderResult.LocalizedText> labels,
            Map<String, String> mappingCells) implements ReuseOperation {
        public MintLocalWithMapping {
            localEntityIri = ProviderValues.absoluteIri(localEntityIri,
                    "local entity IRI", 4_096);
            if (entityType == null) throw new IllegalArgumentException("minted entity type is required");
            labels = ReuseOperation.labels(labels);
            mappingCells = mapping(mappingCells);
            requireReferences(mappingCells, localEntityIri);
            requireMintSize(localEntityIri, entityType, labels, mappingCells);
        }

        @Override public ReuseAction action() { return ReuseAction.MINT_LOCAL_WITH_MAPPING; }

        @Override
        public Map<String, Object> toJson() {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("iri", localEntityIri);
            entity.put("type", entityType.wire());
            entity.put("labels", labels.stream().map(label -> Map.of(
                    "value", label.value(), "language", label.language())).toList());
            return Map.of("entity", Collections.unmodifiableMap(entity), "mapping", mappingCells);
        }

        @Override
        public void validateRequestedEntityIri(String entityIri) {
            String requested = ProviderValues.absoluteIri(
                    entityIri, "requested entity IRI", 4_096);
            if (localEntityIri.equals(requested)) {
                throw new IllegalArgumentException("minted and external IRIs must differ");
            }
            requireReferences(mappingCells, requested);
        }

        @Override
        public void validateAgainst(ProviderResult result) {
            requireResult(result);
            validateRequestedEntityIri(result.entityIri());
            if (!entityType.matches(result.entityType())) {
                throw new IllegalArgumentException("minted entity type does not match provider evidence");
            }
        }
    }

    enum MintedEntityType {
        CLASS,
        OBJECT_PROPERTY,
        DATA_PROPERTY,
        ANNOTATION_PROPERTY,
        NAMED_INDIVIDUAL,
        DATATYPE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean matches(String externalType) {
            if (externalType == null) return false;
            String normalized = externalType.toLowerCase(Locale.ROOT).replace('-', '_');
            return wire().equals(normalized)
                    || this == NAMED_INDIVIDUAL && "individual".equals(normalized);
        }

        public static MintedEntityType parse(String value) {
            if (value == null) throw new IllegalArgumentException("minted entity type is required");
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("unsupported minted entity type", invalid);
            }
        }
    }

    private static Map<String, String> mapping(Map<String, String> values) {
        if (values == null || values.isEmpty() || values.size() > SssomParser.MAX_COLUMNS) {
            throw new IllegalArgumentException("mapping operation is missing or has too many columns");
        }
        Map<String, String> sorted = new TreeMap<>();
        long bytes = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String column = entry.getKey();
            String rawValue = entry.getValue();
            if (column == null || !ReuseOperationLimits.COLUMN.matcher(column).matches()
                    || rawValue == null) {
                throw new IllegalArgumentException("mapping operation column is invalid");
            }
            // Bound the authored input before normalization so padding cannot evade quotas.
            if (rawValue.length() > SssomParser.MAX_CELL_BYTES) {
                throw new IllegalArgumentException("mapping operation cell is too large");
            }
            int keyBytes = column.getBytes(StandardCharsets.UTF_8).length;
            int valueBytes = rawValue.getBytes(StandardCharsets.UTF_8).length;
            if (valueBytes > SssomParser.MAX_CELL_BYTES) {
                throw new IllegalArgumentException("mapping operation cell is too large");
            }
            bytes += keyBytes + valueBytes;
            if (bytes > ReuseOperationLimits.MAX_UTF8_BYTES) {
                throw new IllegalArgumentException("mapping operation is too large");
            }
            String value = rawValue;
            if (ReuseOperationLimits.NORMALIZED_REFERENCE_CELLS.contains(column)) {
                value = value.trim();
            }
            ProviderValues.wellFormed(value, "mapping operation cell");
            sorted.put(column, value);
        }
        for (String required : List.of("subject_id", "predicate_id", "object_id")) {
            if (sorted.getOrDefault(required, "").isBlank()) {
                throw new IllegalArgumentException("mapping operation requires " + required);
            }
        }
        requireEntityEndpoints(sorted);
        validateSssom(sorted);
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static void validateSssom(Map<String, String> cells) {
        Map<String, Object> metadata = Map.of(
                "mapping_set_id", "https://w3id.org/protege-mcp/proposal-validation",
                "license", "https://spdx.org/licenses/CC0-1.0");
        SssomDocument document = new SssomDocument(metadata, Map.of(),
                List.copyOf(cells.keySet()), List.of(new MappingRecord(cells)));
        SssomValidator.Report report = SssomValidator.validate(document,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        if (!report.valid()) {
            List<String> codes = report.findings().stream().map(finding -> finding.code())
                    .distinct().sorted().toList();
            throw new IllegalArgumentException(
                    "mapping operation is not structurally valid SSSOM: " + codes);
        }
    }

    private static List<ProviderResult.LocalizedText> labels(
            List<ProviderResult.LocalizedText> values) {
        if (values == null || values.isEmpty() || values.size() > 16) {
            throw new IllegalArgumentException("mint operation requires 1..16 labels");
        }
        List<ProviderResult.LocalizedText> copy = new ArrayList<>();
        for (ProviderResult.LocalizedText value : values) {
            if (value == null) throw new IllegalArgumentException("mint labels must not contain null");
            ProviderValues.wellFormed(value.value(), "mint label");
            copy.add(value);
        }
        return copy.stream().distinct().sorted(Comparator
                .comparing(ProviderResult.LocalizedText::language)
                .thenComparing(ProviderResult.LocalizedText::value)).toList();
    }

    private static void requireReferences(Map<String, String> mapping, String iri) {
        requireDistinctEndpoints(mapping);
        String required = MappingRecord.canonicalReference(iri);
        String subject = MappingRecord.canonicalReference(mapping.get("subject_id"));
        String object = MappingRecord.canonicalReference(mapping.get("object_id"));
        if (!required.equals(subject) && !required.equals(object)) {
            throw new IllegalArgumentException("mapping operation does not reference required IRI");
        }
    }

    private static void requireEntityEndpoints(Map<String, String> mapping) {
        for (String column : List.of("subject_id", "object_id")) {
            String endpoint = MappingRecord.canonicalReference(mapping.get(column));
            if (ReuseOperationLimits.NO_TERM_FOUND.equals(endpoint)) {
                throw new IllegalArgumentException(
                        "reuse mapping endpoints must identify ontology entities");
            }
        }
        for (String column : List.of("subject_type", "object_type")) {
            String type = mapping.get(column);
            if (type != null && ("rdfs literal".equalsIgnoreCase(type.trim())
                    || "rdfs:Literal".equals(type.trim()))) {
                throw new IllegalArgumentException(
                        "reuse mapping endpoints must identify ontology entities");
            }
        }
    }

    private static void requireDistinctEndpoints(Map<String, String> mapping) {
        String subject = MappingRecord.canonicalReference(mapping.get("subject_id"));
        String object = MappingRecord.canonicalReference(mapping.get("object_id"));
        if (subject.equals(object)) {
            throw new IllegalArgumentException("mapping operation endpoints must be distinct");
        }
    }

    private static void requireResult(ProviderResult result) {
        if (result == null) throw new IllegalArgumentException("provider result is required");
    }

    private static void requireMintSize(String iri, MintedEntityType type,
            List<ProviderResult.LocalizedText> labels, Map<String, String> mapping) {
        long bytes = utf8Bytes(iri) + utf8Bytes(type.wire());
        for (ProviderResult.LocalizedText label : labels) {
            bytes += utf8Bytes(label.value()) + utf8Bytes(label.language());
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            bytes += utf8Bytes(entry.getKey()) + utf8Bytes(entry.getValue());
        }
        if (bytes > ReuseOperationLimits.MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("mint operation is too large");
        }
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}

final class ReuseOperationLimits {
    static final Pattern COLUMN = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,127}");
    static final int MAX_UTF8_BYTES = 256 * 1_024;
    static final Set<String> NORMALIZED_REFERENCE_CELLS = Set.of(
            "subject_id", "predicate_id", "object_id", "mapping_justification");
    static final String NO_TERM_FOUND = "https://w3id.org/sssom/NoTermFound";

    private ReuseOperationLimits() { }
}
