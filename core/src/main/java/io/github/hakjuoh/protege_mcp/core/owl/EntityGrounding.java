package io.github.hakjuoh.protege_mcp.core.owl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.semanticweb.owlapi.model.IRI;

/** Surface-neutral, deterministic normalization of full-IRI and policy-prefix entity operands. */
public final class EntityGrounding {

    private EntityGrounding() {
    }

    /**
     * Expand a registered CURIE before interpreting the input as an absolute IRI. Prefix keys may
     * carry a trailing colon (OWLAPI format maps) or omit it (project-policy maps).
     */
    public static IRI iri(String reference, Map<String, String> prefixes) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("entity reference must not be blank");
        }
        IRI expanded = expandCurie(reference, prefixes);
        if (expanded != null) return expanded;
        try {
            URI candidate = new URI(reference);
            if (candidate.isAbsolute()) return IRI.create(reference);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("invalid entity IRI: " + reference, invalid);
        }
        throw new IllegalArgumentException(
                "entity reference is neither a registered CURIE nor an absolute IRI: " + reference);
    }

    /** Return the expansion of a registered CURIE, or {@code null} for every other operand. */
    public static IRI expandCurie(String reference, Map<String, String> prefixes) {
        if (reference == null) return null;
        int colon = reference.indexOf(':');
        if (colon < 0 || colon == reference.length() - 1) return null;
        String local = reference.substring(colon + 1);
        if (local.startsWith("//") || local.codePoints().anyMatch(Character::isWhitespace)) {
            return null;
        }
        Map<String, String> normalized = normalizePrefixes(prefixes);
        String namespace = normalized.get(reference.substring(0, colon));
        return namespace == null ? null : IRI.create(namespace + local);
    }

    private static Map<String, String> normalizePrefixes(Map<String, String> prefixes) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (prefixes == null) return normalized;
        prefixes.forEach((key, value) -> {
            if (key == null || value == null || key.isBlank() && !":".equals(key)
                    || value.isBlank()) return;
            String name = key.endsWith(":") ? key.substring(0, key.length() - 1) : key;
            String prior = normalized.putIfAbsent(name, value);
            if (prior != null && !prior.equals(value)) {
                throw new IllegalArgumentException("ambiguous prefix declaration: " + name);
            }
        });
        return normalized;
    }
}
