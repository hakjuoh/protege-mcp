package io.github.hakjuoh.protege_mcp.core.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ToolContractSchemas;
import io.github.hakjuoh.protege_mcp.contracts.Legacy072ToolContracts;

class HeadlessContractSnapshotTest {

    private static final String BASELINE = "0.7.2";
    private static final String UPDATE = "protege.headless.contract.snapshot.update";
    private static final Path SNAPSHOT = Path.of("core", "src", "test", "resources",
            "contracts", "v0.7.2-headless-tools.json");

    @SuppressWarnings("deprecation")
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static Map<String, Object> baseline;
    private static Map<String, Object> current;

    @BeforeAll
    static void capture() throws IOException {
        String update = System.getProperty(UPDATE);
        if (update != null) {
            if (!BASELINE.equals(update) || Files.exists(SNAPSHOT)) {
                throw new IllegalArgumentException("Published headless baseline is immutable");
            }
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, canonical(capture(true)), StandardCharsets.UTF_8);
        }
        try (InputStream resource = HeadlessContractSnapshotTest.class.getResourceAsStream(
                "/contracts/v0.7.2-headless-tools.json")) {
            assertNotNull(resource, "missing classpath headless golden");
            baseline = JSON.readValue(resource, new TypeReference<>() { });
        }
        current = capture(false);
    }

    @Test
    void baselineIsCanonicalAndFreezesEightTools() throws IOException {
        List<Map<String, Object>> tools = tools(baseline);
        assertEquals(8, tools.size());
        assertEquals(canonical(baseline), Files.readString(SNAPSHOT));
        Set<String> names = new LinkedHashSet<>();
        tools.forEach(tool -> assertTrue(names.add(String.valueOf(tool.get("name")))));
        assertTrue(names.contains(HeadlessToolCatalog.SURFACE_TOOL));
        assertEquals(Legacy072ToolContracts.headlessToolNames(), names);
    }

    @Test
    void currentSurfaceRetains072AndPublishesTypedContracts() {
        Map<String, Map<String, Object>> currentByName = byName(tools(current));
        for (Map<String, Object> old : tools(baseline)) {
            String name = String.valueOf(old.get("name"));
            Map<String, Object> now = currentByName.get(name);
            assertNotNull(now, () -> "0.7.2 headless tool was removed: " + name);
            for (String field : List.of("description", "input_schema", "required_capabilities")) {
                assertEquals(old.get(field), now.get(field),
                        () -> name + " changed 0.7.2 field " + field);
            }
            assertNotNull(now.get("output_schema"));
            assertEquals(ToolContractSchemas.wireOutputSchema(
                    ToolContractSchemas.legacySuccessSchema()), now.get("output_schema"));
            assertEquals(ToolContractSchemas.errorSchema(), now.get("error_schema"));
        }
    }

    @Test
    void schemasAndDefinitionsAreImmutable() {
        HeadlessToolCatalog.Definition definition = HeadlessToolCatalog.definitions().get(0);
        assertThrows(UnsupportedOperationException.class,
                () -> definition.outputSchema().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> definition.errorSchema().put("x", "y"));
        assertFalse(definition.requiredCapabilities().contains(null));
    }

    @Test
    void compatibilityCheckDetectsAHeadlessMutation() {
        Map<String, Object> old = tools(baseline).get(0);
        Map<String, Object> now = new LinkedHashMap<>(byName(tools(current))
                .get(String.valueOf(old.get("name"))));
        now.put("description", "silently changed");
        assertThrows(AssertionError.class, () -> {
            for (String field : List.of("description", "input_schema", "required_capabilities")) {
                assertEquals(old.get(field), now.get(field));
            }
        });
    }

    @Test
    void compatibilityCheckDetectsNestedHeadlessSchemaMutation() {
        Map<String, Object> old = byName(tools(baseline)).get("run_project_qc");
        Map<String, Object> now = new LinkedHashMap<>(byName(tools(current))
                .get("run_project_qc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> input = new LinkedHashMap<>((Map<String, Object>)
                now.get("input_schema"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>)
                input.get("properties"));
        properties.remove("limit");
        input.put("properties", properties);
        now.put("input_schema", input);
        assertThrows(AssertionError.class, () -> assertEquals(
                old.get("input_schema"), now.get("input_schema")));
    }

    @Test
    void newHeadlessDefinitionDeepCopiesSchemasInsideCompositionLists() {
        Map<String, Object> branch = new LinkedHashMap<>(Map.of("type", "string"));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "object");
        output.put("properties", Map.of("value", Map.of("anyOf",
                new ArrayList<>(List.of(branch)))));
        output.put("additionalProperties", false);
        HeadlessToolCatalog.Definition definition = new HeadlessToolCatalog.Definition(
                "new_headless_test", "test", Map.of("type", "object",
                        "additionalProperties", false), output,
                ToolContractSchemas.errorSchema(), Set.of("server:admin"));
        branch.put("type", "integer");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) ((Map<?, ?>)
                definition.outputSchema().get("properties")).get("value");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) value.get("anyOf");
        assertEquals("string", choices.get(0).get("type"));
        assertThrows(UnsupportedOperationException.class,
                () -> choices.get(0).put("type", "number"));
    }

    private static Map<String, Object> capture(boolean legacy) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (HeadlessToolCatalog.Definition definition : HeadlessToolCatalog.definitions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", definition.name());
            row.put("description", definition.description());
            row.put("input_schema", definition.inputSchema());
            row.put("output_schema", legacy ? null
                    : ToolContractSchemas.wireOutputSchema(definition.outputSchema()));
            row.put("error_schema", legacy ? null : definition.errorSchema());
            row.put("required_capabilities", definition.requiredCapabilities().stream().sorted().toList());
            tools.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("snapshot_version", 1);
        root.put("product_version", BASELINE);
        root.put("tools", tools);
        return root;
    }

    private static Map<String, Map<String, Object>> byName(List<Map<String, Object>> tools) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        tools.forEach(tool -> byName.put(String.valueOf(tool.get("name")), tool));
        return byName;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tools(Map<String, Object> root) {
        return (List<Map<String, Object>>) root.get("tools");
    }

    private static String canonical(Object value) throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        return JSON.writer(printer).writeValueAsString(value) + "\n";
    }
}
