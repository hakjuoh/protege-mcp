package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Bounded adapter for the reviewed OntoPortal API dialect. */
public final class OntoPortalProvider implements ExternalTermProvider {

    public static final String PROFILE = "ontoportal";
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
            int pageNumber = cursor.page();
            Map<String, String> query = jsonQuery();
            query.put("q", request.query());
            if (!request.ontologies().isEmpty()) {
                query.put("ontologies", String.join(",", request.ontologies()).toUpperCase(Locale.ROOT));
            }
            query.put("page", Integer.toString(pageNumber));
            query.put("pagesize", Integer.toString(request.limit()));
            query.put("include_views", "false");
            query.put("include", "prefLabel,synonym,definition,obsolete,properties,links");

            ProviderResponse response = transport.get(new ProviderRequest("/search", query));
            return mapSearch(request, cursor, response);
        } catch (ProviderFailure typed) {
            throw typed;
        } catch (IllegalArgumentException invalid) {
            throw malformed("OntoPortal search response violates the provider contract");
        }
    }

    @Override
    public ProviderResult inspect(ProviderInspectRequest request, ProviderTransport transport)
            throws ProviderFailure {
        try {
            String encodedIri = encode(request.iri());
            String termPath = "/ontologies/" + request.ontology().toUpperCase(Locale.ROOT)
                    + "/classes/" + encodedIri;
            if (termPath.length() > ProviderRequest.MAX_PATH_LENGTH) {
                throw new ProviderFailure("provider_request_invalid",
                        "OntoPortal term IRI is too large for a bounded request", false);
            }
            Map<String, String> termQuery = jsonQuery();
            termQuery.put("include",
                    "prefLabel,synonym,definition,obsolete,properties,links");
            ProviderResponse termResponse = transport.get(new ProviderRequest(termPath, termQuery));
            Map<String, String> ontologyQuery = jsonQuery();
            ontologyQuery.put("include", "acronym,name,links");
            ProviderResponse ontologyResponse = transport.get(new ProviderRequest(
                    "/ontologies/" + request.ontology().toUpperCase(Locale.ROOT), ontologyQuery));
            Map<String, String> submissionQuery = jsonQuery();
            submissionQuery.put("include",
                    "submissionId,version,released,creationDate,hasLicense,uri,links");
            ProviderResponse submissionResponse = transport.get(new ProviderRequest(
                    "/ontologies/" + request.ontology().toUpperCase(Locale.ROOT)
                            + "/latest_submission",
                    submissionQuery));
            return mapInspect(request, termResponse, ontologyResponse, submissionResponse);
        } catch (ProviderFailure typed) {
            throw typed;
        } catch (IllegalArgumentException invalid) {
            throw malformed("OntoPortal inspection response violates the provider contract");
        }
    }

    private static Map<String, String> jsonQuery() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("format", "json");
        query.put("display_context", "false");
        query.put("display_links", "true");
        return query;
    }

    private ProviderPage mapSearch(ProviderSearchRequest request, Cursor cursor,
            ProviderResponse response) throws ProviderFailure {
        JsonNode root = parse(response);
        long total = integer(root, "totalCount", 0, Integer.MAX_VALUE);
        long returnedPage = integer(root, "page", 1, 1_000_000);
        long pageCount = integer(root, "pageCount", 0, 1_000_000);
        if (returnedPage != cursor.page()) {
            throw malformed("OntoPortal returned the wrong page number");
        }
        JsonNode collection = array(root, "collection");
        if (collection.size() > request.limit()) {
            throw malformed("OntoPortal returned too many results");
        }
        if ((total == 0) != (pageCount == 0) || total < collection.size()
                || pageCount > total
                || pageCount > 0 && returnedPage > pageCount) {
            throw malformed("OntoPortal pagination metadata is inconsistent");
        }

        Set<String> seen = new LinkedHashSet<>(cursor.seen());
        List<ProviderResult> items = new ArrayList<>();
        int startIndex = cursor.seen().size();

        for (int index = 0; index < collection.size(); index++) {
            JsonNode doc = collection.get(index);
            if (!doc.isObject()) throw malformed("OntoPortal search result is not an object");

            String iri = absolute(doc, "@id");
            String ontology = extractOntology(doc, request.ontologies());
            if (!request.ontologies().isEmpty()
                    && !request.ontologies().stream().anyMatch(o -> o.equalsIgnoreCase(ontology))) {
                throw malformed("OntoPortal result escaped the requested ontology filter");
            }

            String key = termDigest(ontology, iri);
            if (!seen.add(key)) continue;

            String label = required(doc, "prefLabel", 4_096);
            String type = first(optional(doc, "@type", 256), "class");
            if (type.contains("#")) {
                type = type.substring(type.lastIndexOf('#') + 1).toLowerCase(Locale.ROOT);
            }

            List<ProviderResult.LocalizedText> synonyms = localized(
                    strings(doc.get("synonym"), 512, 4_096), "und");
            List<String> definitions = strings(doc.get("definition"), 16, 8_192);

            int rank = startIndex + index;
            items.add(ProviderResult.create(request.providerId(), profile(), ontology, null,
                    iri, type, List.of(new ProviderResult.LocalizedText(label, "und")),
                    synonyms, definitions, null, "OntoPortal search result",
                    explanation(request.query(), label, synonyms, rank), score(rank), null,
                    response.receivedAt(), termUrl(response.sourceUrl(), ontology, iri),
                    response.retries(), bool(doc, "obsolete", false), null));
        }

        JsonNode nextPageNode = root.get("nextPage");
        boolean hasNext = returnedPage < pageCount;
        if (hasNext && (nextPageNode == null || !nextPageNode.isIntegralNumber()
                || nextPageNode.longValue() != returnedPage + 1)
                || !hasNext && nextPageNode != null && !nextPageNode.isNull()) {
            throw malformed("OntoPortal next-page metadata is inconsistent");
        }

        String continuation = hasNext
                ? encodeCursor(cursor.page() + 1, cursor.requestDigest(), seen) : null;
        return new ProviderPage(items, total, continuation, response.receivedAt(), response.retries());
    }

    private ProviderResult mapInspect(ProviderInspectRequest request,
            ProviderResponse termResponse, ProviderResponse ontologyResponse,
            ProviderResponse submissionResponse)
            throws ProviderFailure {
        JsonNode term = parse(termResponse);
        JsonNode ontology = parse(ontologyResponse);
        JsonNode submission = parse(submissionResponse);

        String acronym = optional(ontology, "acronym", 64);
        if (acronym == null || !request.ontology().equalsIgnoreCase(acronym)) {
            throw malformed("OntoPortal ontology metadata does not match the request");
        }

        String iri = absolute(term, "@id");
        if (!request.iri().equals(iri)) {
            throw malformed("OntoPortal term IRI does not match the request");
        }

        String label = required(term, "prefLabel", 4_096);
        String type = first(optional(term, "@type", 256), "class");
        if (type.contains("#")) {
            type = type.substring(type.lastIndexOf('#') + 1).toLowerCase(Locale.ROOT);
        }

        List<ProviderResult.LocalizedText> synonyms = localized(
                strings(term.get("synonym"), 512, 4_096), "und");
        List<String> definitions = strings(term.get("definition"), 16, 8_192);

        requireOntologyLink(submission, acronym);
        String version = optional(submission, "version", 512);
        String ontologyIri = optionalAbsolute(submission, "uri");
        String provenance = optional(ontology, "name", 4_096);
        String license = first(optional(submission, "hasLicense", 4_096),
                optional(submission, "license", 4_096));

        Instant timestamp = instant(first(optional(submission, "released", 128),
                optional(submission, "creationDate", 128)), termResponse.receivedAt());

        JsonNode properties = optionalObject(term, "properties");
        String replacedBy = extractReplacement(properties);

        return ProviderResult.create(request.providerId(), profile(), acronym.toLowerCase(Locale.ROOT),
                ontologyIri, iri, type,
                List.of(new ProviderResult.LocalizedText(label, "und")),
                synonyms, definitions, license, provenance, "direct_iri_inspection",
                1.0, version, timestamp, termResponse.sourceUrl(),
                termResponse.retries() + ontologyResponse.retries() + submissionResponse.retries(),
                bool(term, "obsolete", false), replacedBy);
    }

    private static void requireOntologyLink(JsonNode document, String acronym)
            throws ProviderFailure {
        JsonNode links = optionalObject(document, "links");
        String ontologyLink = optional(links, "ontology", 4_096);
        if (ontologyLink == null || !ontologyLink.toUpperCase(Locale.ROOT)
                .endsWith("/" + acronym.toUpperCase(Locale.ROOT))) {
            throw malformed("OntoPortal submission metadata does not match the request");
        }
    }

    private static String optionalAbsolute(JsonNode parent, String field) throws ProviderFailure {
        String value = optional(parent, field, 4_096);
        if (value == null) return null;
        try {
            if (!URI.create(value).isAbsolute()) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw malformed("OntoPortal field " + field + " is not an absolute IRI");
        }
    }

    private static String extractOntology(JsonNode doc, List<String> requestedOntologies)
            throws ProviderFailure {
        JsonNode links = doc.get("links");
        if (links != null && links.isObject()) {
            JsonNode ontologyLink = links.get("ontology");
            if (ontologyLink != null && ontologyLink.isTextual()) {
                String uri = ontologyLink.textValue();
                int lastSlash = uri.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < uri.length() - 1) {
                    return uri.substring(lastSlash + 1).toLowerCase(Locale.ROOT);
                }
            }
        }
        throw malformed("OntoPortal result does not specify an ontology");
    }

    private static String extractReplacement(JsonNode properties) throws ProviderFailure {
        if (properties == null || !properties.isObject()) return null;
        // Check common replacement annotation properties: IAO_0100001, replacedBy, term_replaced_by
        for (String key : List.of("http://purl.obolibrary.org/obo/IAO_0100001",
                "http://purl.org/dc/terms/isReplacedBy", "replacedBy", "term_replaced_by")) {
            JsonNode val = properties.get(key);
            if (val != null) {
                List<String> list = strings(val, 4, 4_096);
                if (!list.isEmpty()) return list.get(0);
            }
        }
        return null;
    }

    private static JsonNode parse(ProviderResponse response) throws ProviderFailure {
        byte[] body = response.body();
        try {
            try (JsonParser parser = JSON.getFactory().createParser(body)) {
                int tokens = 0;
                while (parser.nextToken() != null) {
                    if (++tokens > MAX_JSON_TOKENS) {
                        throw malformed("OntoPortal response contains too many JSON tokens");
                    }
                }
            }
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) throw malformed("OntoPortal response is not an object");
            return root;
        } catch (ProviderFailure typed) {
            throw typed;
        } catch (IOException | RuntimeException invalid) {
            throw new ProviderFailure("provider_response_invalid",
                    "OntoPortal returned a malformed bounded response", false);
        }
    }

    private Cursor cursor(String value, ProviderSearchRequest request)
            throws ProviderFailure {
        String expectedRequest = ProviderRequestIdentity.digest(request, profile());
        if (value == null) return new Cursor(1, expectedRequest, Set.of());
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            int page = Integer.parseInt(parts[1]);
            if (page < 1 || page > 1_000_000) throw new IllegalArgumentException();
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
            return new Cursor(page, expectedRequest, Set.copyOf(seen));
        } catch (RuntimeException invalid) {
            throw new ProviderFailure("provider_cursor_invalid",
                    "OntoPortal continuation is invalid", false);
        }
    }

    private static String encodeCursor(int page, String requestDigest, Set<String> seen)
            throws ProviderFailure {
        if (page > 1_000_000) {
            throw new ProviderFailure("provider_cursor_quota_exceeded",
                    "OntoPortal continuation exceeds the supported result window", false);
        }
        List<String> ordered = seen.stream().sorted(Comparator.naturalOrder()).toList();
        byte[] packed = new byte[Math.multiplyExact(ordered.size(), DIGEST_BYTES)];
        int offset = 0;
        for (String digest : ordered) {
            byte[] bytes = fromHex(digest);
            System.arraycopy(bytes, 0, packed, offset, bytes.length);
            offset += bytes.length;
        }
        String value = "v1." + page + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(fromHex(requestDigest))
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        if (value.codePointCount(0, value.length())
                > ProviderSearchRequest.MAX_CONTINUATION_LENGTH) {
            throw new ProviderFailure("provider_cursor_quota_exceeded",
                    "OntoPortal continuation exceeds the bounded cursor state", false);
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

    private record Cursor(int page, String requestDigest, Set<String> seen) { }

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
            if (!path.endsWith("/search")) throw new IllegalArgumentException();
            String base = path.substring(0, path.length() - "/search".length());
            String authority = source.getRawAuthority();
            String rawPath = base + "/ontologies/" + ontology.toUpperCase(Locale.ROOT)
                    + "/classes/" + encode(iri);
            return URI.create("https://" + authority + rawPath);
        } catch (Exception invalid) {
            throw malformed("OntoPortal source URL is invalid");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonNode optionalObject(JsonNode parent, String field) throws ProviderFailure {
        if (parent == null) return null;
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) throw malformed("OntoPortal field " + field + " is invalid");
        return value;
    }

    private static JsonNode array(JsonNode parent, String field) throws ProviderFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) throw malformed("OntoPortal field " + field + " is invalid");
        return value;
    }

    private static long integer(JsonNode parent, String field, long minimum, long maximum)
            throws ProviderFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw malformed("OntoPortal count is invalid");
        }
        long parsed = value.longValue();
        if (parsed < minimum || parsed > maximum) throw malformed("OntoPortal count is outside bounds");
        return parsed;
    }

    private static String required(JsonNode parent, String field, int maximum)
            throws ProviderFailure {
        String value = optional(parent, field, maximum);
        if (value == null) throw malformed("OntoPortal required field " + field + " is missing");
        return value;
    }

    private static String absolute(JsonNode parent, String field) throws ProviderFailure {
        String value = required(parent, field, 4_096);
        try {
            if (!URI.create(value).isAbsolute()) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw malformed("OntoPortal field " + field + " is not an absolute IRI");
        }
    }

    private static String optional(JsonNode parent, String field, int maximum)
            throws ProviderFailure {
        if (parent == null || !parent.isObject()) return null;
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()
                || value.textValue().codePointCount(0, value.textValue().length()) > maximum) {
            throw malformed("OntoPortal field " + field + " is invalid");
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
            if (value.size() > maximum) throw malformed("OntoPortal list exceeds its bound");
            for (JsonNode item : value) {
                if (!item.isTextual()) throw malformed("OntoPortal list contains a non-string");
                result.add(item.textValue());
            }
        } else {
            throw malformed("OntoPortal string list is invalid");
        }
        for (String item : result) {
            if (item.isBlank() || item.codePointCount(0, item.length()) > maxLength) {
                throw malformed("OntoPortal string exceeds its bound");
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
        if (!value.isBoolean()) throw malformed("OntoPortal boolean field " + field + " is invalid");
        return value.booleanValue();
    }

    private static Instant instant(String value, Instant fallback) throws ProviderFailure {
        if (value == null) return fallback;
        try {
            return Instant.parse(value);
        } catch (RuntimeException notInstant) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (RuntimeException notOffset) {
                try {
                    return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            .toInstant(ZoneOffset.UTC);
                } catch (RuntimeException invalid) {
                    throw malformed("OntoPortal timestamp is invalid");
                }
            }
        }
    }

    private static String first(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static ProviderFailure malformed(String message) {
        return new ProviderFailure("provider_response_invalid", message, false);
    }
}
