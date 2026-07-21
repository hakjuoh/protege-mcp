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
        language = language == null || language.isBlank() ? "en"
                : identifier(language.toLowerCase(Locale.ROOT), "language");
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

    private static String normalizeQuery(String value) {
        ProviderFailure.requireText(value, "query", 512);
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) throw new IllegalArgumentException("query is blank");
        return normalized;
    }
}
