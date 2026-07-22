package io.github.hakjuoh.protege_mcp.external;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Provider-neutral normalized search input. Continuation is adapter-private, never public. */
public record ProviderSearchRequest(String providerId, String query, List<String> ontologies,
        String language, int limit, String continuation) {

    public static final int MAX_CONTINUATION_LENGTH = 65_536;

    public ProviderSearchRequest {
        providerId = identifier(providerId, "provider_id");
        query = normalizeQuery(query);
        if (ontologies == null) ontologies = List.of();
        if (ontologies.size() > 16) throw new IllegalArgumentException("too many ontology filters");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String ontology : ontologies) unique.add(identifier(ontology, "ontology"));
        ontologies = unique.stream().sorted().toList();
        language = language(language);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        if (continuation != null && (continuation.isBlank()
                || continuation.length() > MAX_CONTINUATION_LENGTH)) {
            throw new IllegalArgumentException("provider continuation is invalid");
        }
    }

    static String identifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    static String language(String value) {
        String normalized = value == null || value.isBlank()
                ? "en" : value.toLowerCase(Locale.ROOT);
        if (normalized.length() > 64
                || !normalized.matches("[a-z]{2,8}(?:-[a-z0-9]{1,8})*")) {
            throw new IllegalArgumentException("language is invalid");
        }
        return normalized;
    }

    private static String normalizeQuery(String value) {
        value = ProviderValues.wellFormed(
                ProviderFailure.requireText(value, "query", 512), "query");
        String normalized = ProviderValues.wellFormed(
                Normalizer.normalize(value, Normalizer.Form.NFKC), "query")
                .trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) throw new IllegalArgumentException("query is blank");
        if (normalized.codePointCount(0, normalized.length()) > 512) {
            throw new IllegalArgumentException("query exceeds 512 characters after normalization");
        }
        return normalized;
    }
}
