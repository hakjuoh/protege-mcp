package io.github.hakjuoh.protege_mcp.external;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic in-process provider used by the complete provider contract suite. */
public final class FakeTermProvider implements ExternalTermProvider {

    public static final String PROFILE = "fake";
    private final List<ProviderResult> values;

    public FakeTermProvider(List<ProviderResult> values) {
        if (values == null || values.size() > 1_000) {
            throw new IllegalArgumentException("fake provider fixture exceeds its bound");
        }
        this.values = List.copyOf(values);
    }

    @Override
    public String profile() {
        return PROFILE;
    }

    @Override
    public ProviderPage search(ProviderSearchRequest request, ProviderTransport ignored)
            throws ProviderFailure {
        int start = start(request.continuation(), request);
        String query = request.query().toLowerCase(Locale.ROOT);
        List<ProviderResult> matches = ProviderPage.normalized(values.stream()
                .filter(value -> request.ontologies().isEmpty()
                        || request.ontologies().contains(value.sourceOntology()))
                .filter(value -> value.labels().stream().anyMatch(
                                text -> matches(text, query, request.language()))
                        || value.synonyms().stream().anyMatch(
                                text -> matches(text, query, request.language())))
                .toList());
        int end = Math.min(matches.size(), start + request.limit());
        if (start > matches.size()) {
            throw new ProviderFailure("provider_cursor_invalid",
                    "fake provider continuation exceeds the result set", false);
        }
        List<ProviderResult> page = new ArrayList<>(matches.subList(start, end));
        String next = end < matches.size() ? cursor(end, request) : null;
        java.time.Instant fetched = page.isEmpty() ? java.time.Instant.EPOCH
                : page.stream().map(ProviderResult::providerTimestamp).max(
                        java.util.Comparator.naturalOrder()).orElse(java.time.Instant.EPOCH);
        return new ProviderPage(page, matches.size(), next, fetched, 0);
    }

    @Override
    public ProviderResult inspect(ProviderInspectRequest request, ProviderTransport ignored)
            throws ProviderFailure {
        return values.stream().filter(value -> request.ontology().equals(value.sourceOntology())
                        && request.iri().equals(value.entityIri()))
                .findFirst().orElseThrow(() -> new ProviderFailure("provider_term_not_found",
                        "fake provider has no matching term", false));
    }

    private static int start(String continuation, ProviderSearchRequest request)
            throws ProviderFailure {
        if (continuation == null) return 0;
        try {
            String[] parts = continuation.split("\\.", -1);
            if (parts.length != 3 || !parts[0].equals("v1")
                    || !parts[2].equals(ProviderRequestIdentity.digest(request, PROFILE))) {
                throw new IllegalArgumentException();
            }
            int value = Integer.parseInt(parts[1]);
            if (value < 0 || value > 1_000) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw new ProviderFailure("provider_cursor_invalid",
                    "fake provider continuation is invalid", false);
        }
    }

    private static String cursor(int start, ProviderSearchRequest request) {
        return "v1." + start + "." + ProviderRequestIdentity.digest(request, PROFILE);
    }

    private static boolean matches(ProviderResult.LocalizedText text, String normalizedQuery,
            String language) {
        return (text.language().equals(language) || text.language().equals("und"))
                && text.value().toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }
}
