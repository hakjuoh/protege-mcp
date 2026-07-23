package io.github.hakjuoh.protege_mcp.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ToolContractSchemas;
import io.github.hakjuoh.protege_mcp.core.auth.ToolCapabilityCatalog;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolCatalog;

class ReasonerContractSnapshotTest {

    private static final String SNAPSHOT =
            "sha256:783984c8fa8262cef4dd0c4c1dc8ac015d94a0d3be6211c56a27430623886472";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Test
    void featureContractHasAnImmutable080Digest() throws Exception {
        Map<String, Object> contract = new LinkedHashMap<>();
        for (String name : new TreeSet<>(ReasonerToolSchemas.NAMES)) {
            contract.put(name, Map.of(
                    "description", ReasonerToolSchemas.description(name),
                    "input", ReasonerToolSchemas.input(name),
                    "output", ReasonerToolSchemas.output(name),
                    "error", ToolContractSchemas.errorSchema(),
                    "required_capabilities", ToolCapabilityCatalog.required(name).stream()
                            .sorted().toList()));
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsBytes(contract));
        StringBuilder actual = new StringBuilder("sha256:");
        for (byte value : digest) actual.append(String.format("%02x", value & 0xff));
        assertEquals(SNAPSHOT, actual.toString());
    }

    @Test
    void headlessAdapterPublishesTheExactFeatureSnapshot() {
        for (String name : ReasonerToolSchemas.NAMES) {
            HeadlessToolCatalog.Definition definition = HeadlessToolCatalog.definition(name);
            assertEquals(ReasonerToolSchemas.input(name), definition.inputSchema(), name);
            assertEquals(ReasonerToolSchemas.output(name), definition.outputSchema(), name);
        }
    }
}
