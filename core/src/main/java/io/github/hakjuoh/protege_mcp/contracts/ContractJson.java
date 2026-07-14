package io.github.hakjuoh.protege_mcp.contracts;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Creates the strict Jackson mapper required when reading the versioned contract records. */
public final class ContractJson {

    private ContractJson() {
    }

    /**
     * Contract ingest must fail closed: a corrupted document is rejected, never silently repaired.
     * Besides unknown and duplicate fields, that means no scalar coercion — {@code 7.9} must not
     * truncate to a revision {@code 7}, {@code "1"} must not become the integer {@code 1}, an
     * explicit {@code null} must not zero a primitive — and nothing may trail the document.
     */
    public static ObjectMapper mapper() {
        return JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }
}
