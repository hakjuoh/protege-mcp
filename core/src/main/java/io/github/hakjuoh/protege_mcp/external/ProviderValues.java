package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;

/** Shared strict validation for provider-controlled text and IRIs. */
final class ProviderValues {

    private ProviderValues() { }

    static String absoluteIri(String value, String field, int maximum) {
        value = wellFormed(ProviderFailure.requireText(value, field, maximum), field);
        try {
            URI iri = URI.create(value);
            if (!iri.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be an absolute IRI", invalid);
        }
    }

    static String wellFormed(String value, String field) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException(field + " contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " contains invalid Unicode");
            }
        }
        return value;
    }
}
