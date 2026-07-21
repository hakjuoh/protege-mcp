package io.github.hakjuoh.protege_mcp.external;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One deterministic provider page. {@code total} is the provider-reported pre-dedup hit count;
 * continuation remains private to the provider runtime.
 */
public record ProviderPage(List<ProviderResult> items, long total, String continuation,
        Instant fetchedAt, int retries) {

    public ProviderPage {
        if (items == null || items.size() > 100 || total < items.size()
                || fetchedAt == null || retries < 0 || retries > ProviderResponse.MAX_RETRIES) {
            throw new IllegalArgumentException("provider page is invalid");
        }
        items = normalized(items);
        if (continuation != null && (continuation.isBlank()
                || continuation.length() > ProviderSearchRequest.MAX_CONTINUATION_LENGTH)) {
            throw new IllegalArgumentException("provider continuation is invalid");
        }
    }

    static List<ProviderResult> normalized(List<ProviderResult> values) {
        Map<TermKey, ProviderResult> unique = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparingDouble(ProviderResult::score).reversed()
                        .thenComparing(ProviderResult::sourceOntology)
                        .thenComparing(ProviderResult::entityIri)
                        .thenComparing(ProviderResult::resultFingerprint))
                .forEach(result -> unique.putIfAbsent(
                        new TermKey(result.sourceOntology(), result.entityIri()), result));
        return List.copyOf(unique.values());
    }

    private record TermKey(String ontology, String iri) { }
}
