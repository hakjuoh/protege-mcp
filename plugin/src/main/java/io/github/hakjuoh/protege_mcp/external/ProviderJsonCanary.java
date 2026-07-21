package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.util.function.Predicate;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;

/** Bounded streaming scan of decoded JSON names and values, independent of wire escaping. */
final class ProviderJsonCanary {

    private static final int MAX_JSON_TOKENS = 250_000;
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(65_536).maxNumberLength(128)
                    .maxDocumentLength(ProviderResponse.MAX_BODY_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private ProviderJsonCanary() { }

    static boolean anyTextMatches(byte[] payload, Predicate<String> predicate)
            throws IOException {
        if (payload == null || payload.length < 1
                || payload.length > ProviderResponse.MAX_BODY_BYTES || predicate == null) {
            throw new IOException("provider JSON scan input is invalid");
        }
        try (JsonParser parser = JSON.createParser(payload)) {
            int tokens = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (++tokens > MAX_JSON_TOKENS) throw new ScanLimitException();
                if (token == JsonToken.FIELD_NAME || token.isScalarValue()) {
                    if (predicate.test(parser.getText())) return true;
                }
            }
            if (tokens == 0) throw new IOException("provider JSON is invalid");
            return false;
        } catch (StreamConstraintsException constrained) {
            throw new ScanLimitException();
        }
    }

    static final class ScanLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
