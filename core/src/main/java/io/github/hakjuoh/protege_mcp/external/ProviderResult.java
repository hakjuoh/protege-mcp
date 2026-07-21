package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable provider-neutral term evidence retained by search, inspect, and proposal flows. */
public record ProviderResult(String providerId, String profile, String sourceOntology,
        String sourceOntologyIri, String entityIri, String entityType,
        List<LocalizedText> labels, List<LocalizedText> synonyms, List<String> descriptions,
        String license, String provenance, String matchExplanation, double score,
        String providerVersion, Instant providerTimestamp, URI sourceUrl,
        int retries, boolean deprecated, String replacedBy, String resultFingerprint) {

    public ProviderResult {
        providerId = ProviderSearchRequest.identifier(providerId, "provider_id");
        profile = ProviderSearchRequest.identifier(profile, "profile");
        sourceOntology = ProviderSearchRequest.identifier(sourceOntology, "source_ontology");
        sourceOntologyIri = optionalAbsolute(sourceOntologyIri, "source_ontology_iri");
        entityIri = absolute(entityIri, "entity_iri");
        entityType = ProviderSearchRequest.identifier(entityType, "entity_type");
        labels = texts(labels, 16);
        if (labels.isEmpty()) throw new IllegalArgumentException("provider result requires a label");
        synonyms = texts(synonyms, 512);
        descriptions = strings(descriptions, 16, 8_192);
        license = optional(license, 4_096);
        provenance = optional(provenance, 4_096);
        matchExplanation = ProviderFailure.requireText(matchExplanation,
                "match_explanation", 1_024);
        if (!Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("provider score must be between 0 and 1");
        }
        providerVersion = optional(providerVersion, 512);
        if (providerTimestamp == null || sourceUrl == null
                || !"https".equalsIgnoreCase(sourceUrl.getScheme())
                || sourceUrl.isOpaque() || sourceUrl.getHost() == null
                || sourceUrl.getUserInfo() != null || sourceUrl.getRawQuery() != null
                || sourceUrl.getRawFragment() != null
                || sourceUrl.toASCIIString().length() > ProviderRequest.MAX_PATH_LENGTH
                || retries < 0 || retries > ProviderResponse.MAX_RETRIES * 2) {
            throw new IllegalArgumentException("provider evidence metadata is invalid");
        }
        replacedBy = optionalAbsolute(replacedBy, "replaced_by");
        String computed = fingerprint(providerId, profile, sourceOntology, sourceOntologyIri,
                entityIri, entityType, labels, synonyms, descriptions, license, provenance,
                matchExplanation, score, providerVersion, providerTimestamp, sourceUrl,
                retries, deprecated, replacedBy);
        if (resultFingerprint == null) resultFingerprint = computed;
        if (!computed.equals(resultFingerprint)) {
            throw new IllegalArgumentException("provider result fingerprint does not match its evidence");
        }
    }

    public static ProviderResult create(String providerId, String profile, String sourceOntology,
            String sourceOntologyIri, String entityIri, String entityType,
            List<LocalizedText> labels, List<LocalizedText> synonyms, List<String> descriptions,
            String license, String provenance, String matchExplanation, double score,
            String providerVersion, Instant providerTimestamp, URI sourceUrl,
            int retries, boolean deprecated, String replacedBy) {
        return new ProviderResult(providerId, profile, sourceOntology, sourceOntologyIri,
                entityIri, entityType, labels, synonyms, descriptions, license, provenance,
                matchExplanation, score, providerVersion, providerTimestamp, sourceUrl,
                retries, deprecated, replacedBy, null);
    }

    public record LocalizedText(String value, String language) {
        public LocalizedText {
            value = ProviderFailure.requireText(value, "localized text", 4_096);
            language = language == null || language.isBlank() ? "und"
                    : ProviderSearchRequest.identifier(language, "language");
        }
    }

    private static List<LocalizedText> texts(List<LocalizedText> values, int maximum) {
        if (values == null) return List.of();
        if (values.size() > maximum) throw new IllegalArgumentException("too many localized texts");
        return values.stream().distinct()
                .sorted(Comparator.comparing(LocalizedText::language)
                        .thenComparing(LocalizedText::value))
                .toList();
    }

    private static List<String> strings(List<String> values, int maximum, int maxLength) {
        if (values == null) return List.of();
        if (values.size() > maximum) throw new IllegalArgumentException("too many provider strings");
        List<String> copy = new ArrayList<>();
        for (String value : values) copy.add(ProviderFailure.requireText(value, "provider text", maxLength));
        return copy.stream().distinct().sorted().toList();
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > maximum) throw new IllegalArgumentException("provider field exceeds bound");
        return value;
    }

    private static String absolute(String value, String field) {
        ProviderFailure.requireText(value, field, 4_096);
        URI iri = URI.create(value);
        if (!iri.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        return value;
    }

    private static String optionalAbsolute(String value, String field) {
        return value == null || value.isBlank() ? null : absolute(value, field);
    }

    private static String fingerprint(Object... evidence) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        for (Object value : evidence) updateEvidence(digest, value);
        StringBuilder result = new StringBuilder("sha256:");
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }

    private static void updateEvidence(MessageDigest digest, Object value) {
        if (value == null) {
            update(digest, "null", new byte[0]);
        } else if (value instanceof LocalizedText text) {
            update(digest, "localized", new byte[0]);
            updateEvidence(digest, text.language);
            updateEvidence(digest, text.value);
        } else if (value instanceof List<?> list) {
            update(digest, "list", ByteBuffer.allocate(Integer.BYTES).putInt(list.size()).array());
            list.forEach(item -> updateEvidence(digest, item));
        } else {
            update(digest, value.getClass().getName(),
                    String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void update(MessageDigest digest, String type, byte[] bytes) {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(typeBytes.length).array());
        digest.update(typeBytes);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", providerId);
        result.put("profile", profile);
        result.put("source_ontology", sourceOntology);
        if (sourceOntologyIri != null) result.put("source_ontology_iri", sourceOntologyIri);
        result.put("entity_iri", entityIri);
        result.put("entity_type", entityType);
        result.put("labels", labels.stream().map(text -> Map.of(
                "value", text.value, "language", text.language)).toList());
        result.put("synonyms", synonyms.stream().map(text -> Map.of(
                "value", text.value, "language", text.language)).toList());
        result.put("descriptions", descriptions);
        if (license != null) result.put("license", license);
        if (provenance != null) result.put("provenance", provenance);
        result.put("match_explanation", matchExplanation);
        result.put("score", score);
        if (providerVersion != null) result.put("provider_version", providerVersion);
        result.put("provider_timestamp", providerTimestamp.toString());
        result.put("source_url", sourceUrl.toString());
        result.put("retries", retries);
        result.put("deprecated", deprecated);
        if (replacedBy != null) result.put("replaced_by", replacedBy);
        result.put("result_fingerprint", resultFingerprint);
        return Collections.unmodifiableMap(result);
    }
}
