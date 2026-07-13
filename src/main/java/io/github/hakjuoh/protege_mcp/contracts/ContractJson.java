package io.github.hakjuoh.protege_mcp.contracts;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Creates the strict Jackson mapper required when reading the versioned contract records. */
public final class ContractJson {

    private ContractJson() {
    }

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
}
