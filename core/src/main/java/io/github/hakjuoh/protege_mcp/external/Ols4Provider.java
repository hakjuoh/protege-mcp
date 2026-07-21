package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Supported OLS4 REST adapter. It cannot select an origin or access credentials directly. */
public final class Ols4Provider implements ExternalTermProvider {

    public static final String PROFILE = "ols4";
    private static final int MAX_JSON_TOKENS = 250_000;
    private static final int DIGEST_BYTES = 32;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(65_536).maxNumberLength(128)
                    .maxDocumentLength(ProviderResponse.MAX_BODY_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public String profile() {
        return PROFILE;
    }

    @Override
    public ProviderPage search(ProviderSearchRequest request, ProviderTransport transport)
            throws ProviderFailure {
        try {
            Cursor cursor = cursor(request.continuation(), request);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("q", request.query());
            if (!request.ontologies().isEmpty()) {
                query.put("ontology", String.join(",", request.ontologies()));
            }
            query.put("lang", request.language());
            query.put("start", Integer.toString(cursor.start()));
            query.put("rows", Integer.toString(request.limit()));
            query.put("fieldList", "iri,ontology_name,ontology_prefix,short_form,description,"
                    + "label,obo_id,type,is_obsolete,synonym");
            ProviderResponse response = transport.get(new ProviderRequest("/api/search", query));
            return mapSearch(request, cursor, response);
        } catch (ProviderFailure typed) {
            throw typed;
        } catch (IllegalArgumentException invalid) {
            throw malformed("OLS4 search response violates the provider contract");
        }
    }

    @Override
    public ProviderResult inspect(ProviderInspectRequest request, ProviderTransport transport)
            throws ProviderFailure {
        try {
            String termPath = "/api/ontologies/" + request.ontology() + "/terms/"
                    + doubleEncode(request.iri());
            if (termPath.length() > ProviderRequest.MAX_PATH_LENGTH) {
                throw new ProviderFailure("provider_request_invalid",
                        "OLS4 term IRI is too large for a bounded request", false);
            }
            ProviderResponse termResponse = transport.get(new ProviderRequest(termPath,
                    Map.of("lang", request.language())));
            ProviderResponse ontologyResponse = transport.get(new ProviderRequest(
                    "/api/ontologies/" + request.ontology(), Map.of("lang", request.language())));
            return mapInspect(request, termResponse, ontologyResponse);
        } catch (ProviderFailure typed) {
            throw typed;
        } catch (IllegalArgumentException invalid) {
            throw malformed("OLS4 inspection response violates the provider contract");
        }
    }

    private static ProviderPage mapSearch(ProviderSearchRequest request, Cursor cursor,
            ProviderResponse response) throws ProviderFailure {
        JsonNode root = parse(response);
        JsonNode result = object(root, "response");
        long total = integer(result, "numFound", 0, Integer.MAX_VALUE);
        long returnedStart = integer(result, "start", 0, 1_000_000);
        if (returnedStart != cursor.start()) throw malformed("OLS4 returned the wrong page offset");
        JsonNode docs = array(result, "docs");
        if (docs.size() > request.limit()) throw malformed("OLS4 returned too many results");
        long next = (long) cursor.start() + docs.size();
        if (cursor.start() > total || next > total || (next < total && docs.isEmpty())) {
            throw malformed("OLS4 pagination metadata is inconsistent");
        }
        Set<String> seen = new LinkedHashSet<>(cursor.seen());
        List<ProviderResult> items = new ArrayList<>();
        for (int index = 0; index < docs.size(); index++) {
            JsonNode doc = docs.get(index);
            if (!doc.isObject()) throw malformed("OLS4 search result is not an object");
            String ontology = required(doc, "ontology_name", 64).toLowerCase(Locale.ROOT);
            if (!request.ontologies().isEmpty() && !request.ontologies().contains(ontology)) {
                throw malformed("OLS4 result escaped the requested ontology filter");
            }
            String iri = absolute(doc, "iri");
            String key = termDigest(ontology, iri);
            if (!seen.add(key)) continue;
            String label = required(doc, "label", 4_096);
            String type = first(optional(doc, "type", 64), "class");
            List<ProviderResult.LocalizedText> synonyms = localized(
                    strings(doc.get("synonym"), 512, 4_096), request.language());
            int rank = cursor.start() + index;
            items.add(ProviderResult.create(request.providerId(), PROFILE, ontology, null,
                    iri, type, List.of(new ProviderResult.LocalizedText(label, request.language())),
                    synonyms, strings(doc.get("description"), 16, 8_192), null,
                    "OLS4 search result", explanation(request.query(), label, synonyms, rank),
                    score(rank), null, response.receivedAt(),
                    termUrl(response.sourceUrl(), ontology, iri), response.retries(),
                    bool(doc, "is_obsolete", false), null));
        }
        String continuation = next < total
                ? encodeCursor(Math.toIntExact(next), cursor.requestDigest(), seen) : null;
        return new ProviderPage(items, total, continuation, response.receivedAt(), response.retries());
    }

    private static ProviderResult mapInspect(ProviderInspectRequest request,
            ProviderResponse termResponse, ProviderResponse ontologyResponse)
            throws ProviderFailure {
        JsonNode term = parse(termResponse);
        JsonNode ontology = parse(ontologyResponse);
        JsonNode config = optionalObject(ontology, "config");
        JsonNode annotations = optionalObject(config, "annotations");
        String ontologyId = optional(term, "ontology_name", 64);
        if (ontologyId == null || !request.ontology().equalsIgnoreCase(ontologyId)) {
            throw malformed("OLS4 term ontology does not match the request");
        }
        String metadataId = required(ontology, "ontologyId", 64);
        if (!request.ontology().equalsIgnoreCase(metadataId)) {
            throw malformed("OLS4 ontology metadata does not match the request");
        }
        String iri = absolute(term, "iri");
        if (!request.iri().equals(iri)) throw malformed("OLS4 term IRI does not match the request");
        String language = first(optional(term, "lang", 64), request.language());
        String version = first(optional(ontology, "version", 512),
                optional(config, "version", 512));
        String ontologyIri = optional(term, "ontology_iri", 4_096);
        String provenance = optional(config, "versionIri", 4_096);
        String license = first(optional(config, "license", 4_096),
                annotation(annotations, "license"));
        Instant timestamp = instant(first(optional(ontology, "updated", 128),
                optional(ontology, "loaded", 128)), termResponse.receivedAt());
        return ProviderResult.create(request.providerId(), PROFILE, ontologyId, ontologyIri,
                iri, first(optional(term, "type", 64), "class"),
                localized(List.of(required(term, "label", 4_096)), language),
                localized(strings(term.get("synonyms"), 512, 4_096), language),
                strings(term.get("description"), 16, 8_192), license, provenance,
                "direct_iri_inspection", 1.0, version, timestamp, termResponse.sourceUrl(),
                termResponse.retries() + ontologyResponse.retries(),
                bool(term, "is_obsolete", false), replacement(term.get("term_replaced_by")));
    }

    private static JsonNode parse(ProviderResponse response) throws ProviderFailure {
        byte[] body = response.body();
        try {
            try (JsonParser parser = JSON.getFactory().createParser(body)) {
                int tokens = 0;
                while (parser.nextToken() != null) {
                    if (++tokens > MAX_JSON_TOKENS) {
                        throw malformed("OLS4 response contains too many JSON tokens");
                    }
                }
            }
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) throw malformed("OLS4 response is not an object");
            return root;
        } catch (ProviderFailure typed) {
            throw typed;
        } catch (IOException | RuntimeException invalid) {
            throw new ProviderFailure("provider_response_invalid",
                    "OLS4 returned a malformed bounded response", false);
        }
    }

    private static Cursor cursor(String value, ProviderSearchRequest request)
            throws ProviderFailure {
        String expectedRequest = ProviderRequestIdentity.digest(request, PROFILE);
        if (value == null) return new Cursor(0, expectedRequest, Set.of());
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            int start = Integer.parseInt(parts[1]);
            if (start < 0 || start > 1_000_000) throw new IllegalArgumentException();
            byte[] requestBytes = Base64.getUrlDecoder().decode(parts[2]);
            if (requestBytes.length != DIGEST_BYTES
                    || !hex(requestBytes, 0, requestBytes.length).equals(expectedRequest)) {
                throw new IllegalArgumentException();
            }
            byte[] packed = parts[3].isEmpty() ? new byte[0]
                    : Base64.getUrlDecoder().decode(parts[3]);
            if (packed.length % DIGEST_BYTES != 0) throw new IllegalArgumentException();
            Set<String> seen = new LinkedHashSet<>();
            for (int offset = 0; offset < packed.length; offset += DIGEST_BYTES) {
                seen.add(hex(packed, offset, DIGEST_BYTES));
            }
            if (seen.size() * DIGEST_BYTES != packed.length) throw new IllegalArgumentException();
            return new Cursor(start, expectedRequest, Set.copyOf(seen));
        } catch (RuntimeException invalid) {
            throw new ProviderFailure("provider_cursor_invalid",
                    "OLS4 continuation is invalid", false);
        }
    }

    private static String encodeCursor(int start, String requestDigest, Set<String> seen)
            throws ProviderFailure {
        if (start > 1_000_000) {
            throw new ProviderFailure("provider_cursor_quota_exceeded",
                    "OLS4 continuation exceeds the supported result window", false);
        }
        List<String> ordered = seen.stream().sorted(Comparator.naturalOrder()).toList();
        byte[] packed = new byte[Math.multiplyExact(ordered.size(), DIGEST_BYTES)];
        int offset = 0;
        for (String digest : ordered) {
            byte[] bytes = fromHex(digest);
            System.arraycopy(bytes, 0, packed, offset, bytes.length);
            offset += bytes.length;
        }
        String value = "v1." + start + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(fromHex(requestDigest))
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        if (value.length() > ProviderSearchRequest.MAX_CONTINUATION_LENGTH) {
            throw new ProviderFailure("provider_cursor_quota_exceeded",
                    "OLS4 continuation exceeds the bounded cursor state", false);
        }
        return value;
    }

    private static byte[] fromHex(String value) {
        if (value == null || value.length() != DIGEST_BYTES * 2) {
            throw new IllegalArgumentException("invalid digest");
        }
        byte[] result = new byte[DIGEST_BYTES];
        for (int index = 0; index < value.length(); index += 2) {
            result[index / 2] = (byte) Integer.parseInt(value.substring(index, index + 2), 16);
        }
        return result;
    }

    private static String termDigest(String ontology, String iri) {
        return ProviderRequestIdentity.digest(List.of(ontology, iri));
    }

    private static String hex(byte[] value, int offset, int length) {
        StringBuilder result = new StringBuilder(length * 2);
        for (int index = offset; index < offset + length; index++) {
            int unsigned = value[index] & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }

    private record Cursor(int start, String requestDigest, Set<String> seen) { }

    private static String explanation(String query, String label,
            List<ProviderResult.LocalizedText> synonyms, int rank) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (label.equalsIgnoreCase(query)) return "exact_label";
        if (synonyms.stream().anyMatch(value -> value.value().equalsIgnoreCase(query))) {
            return "exact_synonym";
        }
        if (label.toLowerCase(Locale.ROOT).contains(normalized)) return "label_contains_query";
        return "provider_rank:" + rank;
    }

    private static double score(int rank) {
        return 1.0 / (rank + 1.0);
    }

    private static URI termUrl(URI source, String ontology, String iri) throws ProviderFailure {
        try {
            String path = source.getRawPath();
            int api = path.indexOf("/api/");
            String base = api < 0 ? "" : path.substring(0, api);
            String authority = source.getRawAuthority();
            String rawPath = base + "/api/ontologies/" + ontology
                    + "/terms/" + doubleEncode(iri);
            return URI.create("https://" + authority + rawPath);
        } catch (Exception invalid) {
            throw malformed("OLS4 source URL is invalid");
        }
    }

    private static String doubleEncode(String value) {
        return encode(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonNode object(JsonNode parent, String field) throws ProviderFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw malformed("OLS4 field " + field + " is invalid");
        return value;
    }

    private static JsonNode optionalObject(JsonNode parent, String field) throws ProviderFailure {
        if (parent == null) return null;
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) throw malformed("OLS4 field " + field + " is invalid");
        return value;
    }

    private static JsonNode array(JsonNode parent, String field) throws ProviderFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) throw malformed("OLS4 field " + field + " is invalid");
        return value;
    }

    private static long integer(JsonNode parent, String field, long minimum, long maximum)
            throws ProviderFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw malformed("OLS4 count is invalid");
        }
        long parsed = value.longValue();
        if (parsed < minimum || parsed > maximum) throw malformed("OLS4 count is outside bounds");
        return parsed;
    }

    private static String required(JsonNode parent, String field, int maximum)
            throws ProviderFailure {
        String value = optional(parent, field, maximum);
        if (value == null) throw malformed("OLS4 required field " + field + " is missing");
        return value;
    }

    private static String absolute(JsonNode parent, String field) throws ProviderFailure {
        String value = required(parent, field, 4_096);
        try {
            if (!URI.create(value).isAbsolute()) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw malformed("OLS4 field " + field + " is not an absolute IRI");
        }
    }

    private static String optional(JsonNode parent, String field, int maximum)
            throws ProviderFailure {
        if (parent == null || !parent.isObject()) return null;
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw malformed("OLS4 field " + field + " is invalid");
        }
        return value.textValue();
    }

    private static List<String> strings(JsonNode value, int maximum, int maxLength)
            throws ProviderFailure {
        if (value == null || value.isNull()) return List.of();
        List<String> result = new ArrayList<>();
        if (value.isTextual()) {
            result.add(value.textValue());
        } else if (value.isArray()) {
            if (value.size() > maximum) throw malformed("OLS4 list exceeds its bound");
            for (JsonNode item : value) {
                if (!item.isTextual()) throw malformed("OLS4 list contains a non-string");
                result.add(item.textValue());
            }
        } else {
            throw malformed("OLS4 string list is invalid");
        }
        for (String item : result) {
            if (item.isBlank() || item.length() > maxLength) {
                throw malformed("OLS4 string exceeds its bound");
            }
        }
        return result.stream().distinct().limit(maximum).toList();
    }

    private static List<ProviderResult.LocalizedText> localized(List<String> values, String language) {
        return values.stream().map(value -> new ProviderResult.LocalizedText(value, language)).toList();
    }

    private static boolean bool(JsonNode parent, String field, boolean fallback)
            throws ProviderFailure {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isBoolean()) throw malformed("OLS4 boolean field " + field + " is invalid");
        return value.booleanValue();
    }

    private static String replacement(JsonNode value) throws ProviderFailure {
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return replacementText(value.textValue());
        if (value.isArray() && value.size() == 1 && value.get(0).isTextual()) {
            return replacementText(value.get(0).textValue());
        }
        throw malformed("OLS4 replacement field is invalid");
    }

    private static String replacementText(String value) throws ProviderFailure {
        if (value.isBlank() || value.length() > 4_096) {
            throw malformed("OLS4 replacement field is invalid");
        }
        return value;
    }

    private static String annotation(JsonNode annotations, String field) throws ProviderFailure {
        if (annotations == null || !annotations.isObject()) return null;
        JsonNode value = annotations.get(field);
        if (value == null || value.isNull()) return null;
        List<String> values = strings(value, 4, 4_096);
        return values.isEmpty() ? null : values.get(0);
    }

    private static Instant instant(String value, Instant fallback) throws ProviderFailure {
        if (value == null) return fallback;
        try {
            return Instant.parse(value.endsWith("Z") ? value : value + "Z");
        } catch (RuntimeException invalid) {
            throw malformed("OLS4 timestamp is invalid");
        }
    }

    private static String first(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static ProviderFailure malformed(String message) {
        return new ProviderFailure("provider_response_invalid", message, false);
    }
}
