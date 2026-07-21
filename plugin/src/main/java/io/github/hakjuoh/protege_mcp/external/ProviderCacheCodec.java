package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Strict internal JSON codec that re-runs all provider model constructors on cache reads. */
final class ProviderCacheCodec {

    static final int MAX_PAYLOAD_BYTES = ProviderResponse.MAX_BODY_BYTES;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(65_536).maxNumberLength(128)
                    .maxDocumentLength(MAX_PAYLOAD_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private ProviderCacheCodec() { }

    static byte[] encodePage(ProviderPage page) throws IOException {
        if (page == null || page.continuation() != null) throw invalid();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("items", page.items().stream().map(ProviderResult::toJson).toList());
        value.put("total", page.total());
        value.put("fetched_at", page.fetchedAt().toString());
        value.put("retries", page.retries());
        return bounded(JSON.writeValueAsBytes(value));
    }

    static ProviderPage decodePage(byte[] payload) throws IOException {
        JsonNode root = object(payload);
        rejectUnknown(root, List.of("items", "total", "fetched_at", "retries"));
        JsonNode items = root.get("items");
        if (items == null || !items.isArray() || items.size() > 100) throw invalid();
        List<ProviderResult> results = new ArrayList<>();
        for (JsonNode item : items) results.add(result(item));
        try {
            return new ProviderPage(results, integer(root, "total", 0, Long.MAX_VALUE),
                    null,
                    Instant.parse(text(root, "fetched_at", 128)),
                    Math.toIntExact(integer(root, "retries", 0, ProviderResponse.MAX_RETRIES)));
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    static byte[] encodeResult(ProviderResult result) throws IOException {
        if (result == null) throw invalid();
        return bounded(JSON.writeValueAsBytes(result.toJson()));
    }

    static ProviderResult decodeResult(byte[] payload) throws IOException {
        return result(object(payload));
    }

    private static ProviderResult result(JsonNode node) throws IOException {
        if (node == null || !node.isObject()) throw invalid();
        rejectUnknown(node, List.of("provider_id", "profile", "source_ontology",
                "source_ontology_iri", "entity_iri", "entity_type", "labels", "synonyms",
                "descriptions", "license", "provenance", "match_explanation", "score",
                "provider_version", "provider_timestamp", "source_url", "retries",
                "deprecated", "replaced_by", "result_fingerprint"));
        try {
            return new ProviderResult(text(node, "provider_id", 64), text(node, "profile", 64),
                    text(node, "source_ontology", 64),
                    optional(node, "source_ontology_iri", 4_096),
                    text(node, "entity_iri", 4_096), text(node, "entity_type", 64),
                    localized(node, "labels", 16), localized(node, "synonyms", 512),
                    strings(node, "descriptions", 16, 8_192),
                    optional(node, "license", 4_096), optional(node, "provenance", 4_096),
                    text(node, "match_explanation", 1_024), decimal(node, "score"),
                    optional(node, "provider_version", 512),
                    Instant.parse(text(node, "provider_timestamp", 128)),
                    URI.create(text(node, "source_url", ProviderRequest.MAX_PATH_LENGTH)),
                    Math.toIntExact(integer(node, "retries", 0,
                            ProviderResponse.MAX_RETRIES * 2L)),
                    bool(node, "deprecated"), optional(node, "replaced_by", 4_096),
                    text(node, "result_fingerprint", 128));
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    private static List<ProviderResult.LocalizedText> localized(JsonNode parent, String field,
            int maximum) throws IOException {
        JsonNode values = parent.get(field);
        if (values == null || !values.isArray() || values.size() > maximum) throw invalid();
        List<ProviderResult.LocalizedText> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isObject()) throw invalid();
            rejectUnknown(value, List.of("value", "language"));
            result.add(new ProviderResult.LocalizedText(text(value, "value", 4_096),
                    text(value, "language", 64)));
        }
        return result;
    }

    private static List<String> strings(JsonNode parent, String field, int maximum,
            int maxLength) throws IOException {
        JsonNode values = parent.get(field);
        if (values == null || !values.isArray() || values.size() > maximum) throw invalid();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()
                    || value.textValue().length() > maxLength) throw invalid();
            result.add(value.textValue());
        }
        return result;
    }

    private static JsonNode object(byte[] payload) throws IOException {
        if (payload == null || payload.length < 2 || payload.length > MAX_PAYLOAD_BYTES) {
            throw invalid();
        }
        JsonNode root = JSON.readTree(payload);
        if (root == null || !root.isObject()) throw invalid();
        return root;
    }

    private static byte[] bounded(byte[] value) throws IOException {
        if (value.length > MAX_PAYLOAD_BYTES) throw invalid();
        return value;
    }

    private static String text(JsonNode object, String field, int maximum) throws IOException {
        String value = optional(object, field, maximum);
        if (value == null) throw invalid();
        return value;
    }

    private static String optional(JsonNode object, String field, int maximum) throws IOException {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) throw invalid();
        return value.textValue();
    }

    private static long integer(JsonNode object, String field, long minimum, long maximum)
            throws IOException {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        long result = value.longValue();
        if (result < minimum || result > maximum) throw invalid();
        return result;
    }

    private static double decimal(JsonNode object, String field) throws IOException {
        JsonNode value = object.get(field);
        if (value == null || !value.isNumber()) throw invalid();
        double result = value.doubleValue();
        if (!Double.isFinite(result)) throw invalid();
        return result;
    }

    private static boolean bool(JsonNode object, String field) throws IOException {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static void rejectUnknown(JsonNode object, List<String> allowed) throws IOException {
        var fields = object.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid();
    }

    private static IOException invalid() {
        return new IOException("provider cache payload is invalid");
    }
}
