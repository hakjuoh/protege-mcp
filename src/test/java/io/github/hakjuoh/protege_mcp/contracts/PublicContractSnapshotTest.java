package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.prompts.PromptCatalog;
import io.github.hakjuoh.protege_mcp.server.McpServerManager;
import io.github.hakjuoh.protege_mcp.tools.ToolCatalog;
import io.github.hakjuoh.protege_mcp.tools.ToolContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Historical public-contract snapshots and backward-compatibility checks for the 0.5.0 surface.
 *
 * <p>The MCP SDK does not expose result schemas for these tools, so the snapshot joins the runtime
 * registration (name, description and input schema) with the documented top-level result fields in
 * {@code docs/tools}. Prompt snapshots also render every template with deterministic sentinel arguments.
 * That makes the baseline reviewable without invoking Protégé or any tool handler.
 *
 * <p>To create a new historical baseline after intentionally changing a public contract, run:
 *
 * <pre>
 * mvn -Dtest=PublicContractSnapshotTest \
 *     -Dprotege.contract.snapshot.update=0.5.1 test
 * </pre>
 *
 * The generated files are still reviewed and committed like source. Normal test runs never write files.
 * The published baseline can never be regenerated; any other already-existing snapshot pair is only
 * rewritten when {@code -Dprotege.contract.snapshot.overwrite=true} is passed as well, so an unreleased
 * draft can iterate but a committed golden is never clobbered by a copy-pasted command.
 */
class PublicContractSnapshotTest {

    private static final String BASELINE = "0.5.0";
    private static final String UPDATE_PROPERTY = "protege.contract.snapshot.update";
    private static final String OVERWRITE_PROPERTY = "protege.contract.snapshot.overwrite";
    private static final Pattern RELEASE = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern TOOL_HEADING = Pattern.compile("^## `([^`]+)`$");
    private static final Pattern CODE_SPAN = Pattern.compile("`([a-z][a-z0-9_]*)`");
    private static final Path CONTRACT_DIR = Path.of("src", "test", "resources", "contracts");
    private static final Path TOOL_DOCS_DIR = Path.of("docs", "tools");
    private static final Path PROMPT_DOC = TOOL_DOCS_DIR.resolve("prompts.md");
    /** Explicit review point for a future release that intentionally changes tool guidance. */
    private static final Set<String> INTENTIONAL_TOOL_DESCRIPTION_CHANGES_SINCE_V050 = Set.of();
    /** Explicit review point for titles, output schemas, annotations, metadata, or icons. */
    private static final Set<String> INTENTIONAL_TOOL_METADATA_CHANGES_SINCE_V050 = Set.of();
    /** Explicit review point for a future release that intentionally rewrites workflow guidance. */
    private static final Set<String> INTENTIONAL_PROMPT_TEXT_CHANGES_SINCE_V050 = Set.of();
    /** Explicit review point for prompt titles, metadata, or icons. */
    private static final Set<String> INTENTIONAL_PROMPT_METADATA_CHANGES_SINCE_V050 = Set.of();

    @SuppressWarnings("deprecation")
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static Map<String, Object> currentTools;
    private static Map<String, Object> currentPrompts;
    private static Map<String, Object> baselineTools;
    private static Map<String, Object> baselinePrompts;

    @BeforeAll
    static void captureAndOptionallyWrite() throws IOException {
        currentTools = captureTools(McpServerManager.SERVER_VERSION);
        currentPrompts = capturePrompts(McpServerManager.SERVER_VERSION);

        String update = System.getProperty(UPDATE_PROPERTY);
        if (update != null && !update.isBlank()) {
            validateUpdateVersion(update);
            Files.createDirectories(CONTRACT_DIR);
            Files.writeString(toolSnapshot(update), canonical(captureTools(update)), StandardCharsets.UTF_8);
            Files.writeString(promptSnapshot(update), canonical(capturePrompts(update)), StandardCharsets.UTF_8);
        }

        baselineTools = read(toolSnapshot(BASELINE));
        baselinePrompts = read(promptSnapshot(BASELINE));
    }

    @Test
    void publishedBaselineCannotBeOverwrittenByTheUpdateSwitch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validateUpdateVersion(BASELINE));
        assertTrue(error.getMessage().contains("immutable"));
    }

    @Test
    void existingDraftSnapshotsRequireAnExplicitOverwriteFlagAndTheBaselineStaysImmutable(
            @TempDir Path contractDir) throws IOException {
        Files.writeString(toolSnapshot(contractDir, "9.9.9"), "{}\n", StandardCharsets.UTF_8);
        // The suite itself may run with -Dprotege.contract.snapshot.overwrite=true (that is the
        // documented draft-regeneration flow), so pin the property for both assertions and put the
        // caller's value back afterwards.
        String callerFlag = System.getProperty(OVERWRITE_PROPERTY);
        try {
            System.clearProperty(OVERWRITE_PROPERTY);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validateUpdateVersion("9.9.9", contractDir));
            assertTrue(error.getMessage().contains(OVERWRITE_PROPERTY));

            System.setProperty(OVERWRITE_PROPERTY, "true");
            validateUpdateVersion("9.9.9", contractDir);
            IllegalArgumentException baseline = assertThrows(IllegalArgumentException.class,
                    () -> validateUpdateVersion(BASELINE, contractDir));
            assertTrue(baseline.getMessage().contains("immutable"),
                    "the overwrite flag must never unlock the published baseline");
        } finally {
            if (callerFlag == null) {
                System.clearProperty(OVERWRITE_PROPERTY);
            } else {
                System.setProperty(OVERWRITE_PROPERTY, callerFlag);
            }
        }
    }

    @Test
    void v050GoldensAreCanonicalAndHaveThePublishedCardinality() throws IOException {
        assertEquals(66, entries(baselineTools, "tools").size(), "0.5.0 published 66 tools");
        assertEquals(11, entries(baselinePrompts, "prompts").size(), "0.5.0 published 11 prompts");
        assertEquals(canonical(baselineTools), Files.readString(toolSnapshot(BASELINE)),
                "tool golden must use the canonical formatter");
        assertEquals(canonical(baselinePrompts), Files.readString(promptSnapshot(BASELINE)),
                "prompt golden must use the canonical formatter");
        assertUnique(entries(baselineTools, "tools"));
        assertUnique(entries(baselinePrompts, "prompts"));
    }

    @Test
    void allRuntimeToolsRetainTheV050InputContractAndDocumentedResults() {
        Map<String, Map<String, Object>> current = byName(entries(currentTools, "tools"));
        for (Map<String, Object> oldTool : entries(baselineTools, "tools")) {
            String name = string(oldTool, "name");
            Map<String, Object> now = current.get(name);
            assertNotNull(now, () -> "0.5.0 tool was removed: " + name);
            assertInputSchemaBackwardCompatible(name,
                    node(oldTool.get("input_schema")), node(now.get("input_schema")));
            if (!INTENTIONAL_TOOL_DESCRIPTION_CHANGES_SINCE_V050.contains(name)) {
                assertEquals(oldTool.get("description"), now.get("description"),
                        () -> name + " changed its public tool description without review");
            }
            if (!INTENTIONAL_TOOL_METADATA_CHANGES_SINCE_V050.contains(name)) {
                for (String field : List.of("title", "output_schema", "annotations", "meta", "icons")) {
                    // The baseline side is parsed JSON while the live side holds SDK records;
                    // normalize both to trees so a future non-null value compares structurally.
                    assertEquals(node(oldTool.get(field)), node(now.get(field)),
                            () -> name + " changed public tool field " + field + " without review");
                }
            }

            Set<String> oldFields = strings(oldTool.get("documented_result_fields"));
            Set<String> nowFields = strings(now.get("documented_result_fields"));
            assertTrue(nowFields.containsAll(oldFields), () -> name
                    + " documentation dropped result fields: " + difference(oldFields, nowFields));
        }
    }

    @Test
    void allRuntimePromptsRetainTheV050ArgumentContract() {
        Map<String, Map<String, Object>> current = byName(entries(currentPrompts, "prompts"));
        for (Map<String, Object> oldPrompt : entries(baselinePrompts, "prompts")) {
            String name = string(oldPrompt, "name");
            Map<String, Object> now = current.get(name);
            assertNotNull(now, () -> "0.5.0 prompt was removed: " + name);

            Map<String, Map<String, Object>> nowArgs = byName(objects(now.get("arguments")));
            for (Map<String, Object> oldArg : objects(oldPrompt.get("arguments"))) {
                String argName = string(oldArg, "name");
                Map<String, Object> nowArg = nowArgs.get(argName);
                assertNotNull(nowArg, () -> name + " removed argument " + argName);
                assertEquals(oldArg.get("required"), nowArg.get("required"),
                        () -> name + " changed requiredness of " + argName);
                assertEquals(oldArg.get("description"), nowArg.get("description"),
                        () -> name + " silently changed the meaning of " + argName);
            }
            for (Map<String, Object> nowArg : objects(now.get("arguments"))) {
                if (!byName(objects(oldPrompt.get("arguments"))).containsKey(string(nowArg, "name"))) {
                    assertFalse(Boolean.TRUE.equals(nowArg.get("required")),
                            () -> name + " added a new required argument: " + string(nowArg, "name"));
                }
            }
            if (!INTENTIONAL_PROMPT_TEXT_CHANGES_SINCE_V050.contains(name)) {
                assertEquals(oldPrompt.get("description"), now.get("description"),
                        () -> name + " changed its public prompt description without review");
                assertEquals(oldPrompt.get("rendered_description"), now.get("rendered_description"),
                        () -> name + " changed its rendered description without review");
                assertEquals(oldPrompt.get("rendered_messages"), now.get("rendered_messages"),
                        () -> name + " changed its workflow instructions without review");
            }
            if (!INTENTIONAL_PROMPT_METADATA_CHANGES_SINCE_V050.contains(name)) {
                for (String field : List.of("title", "meta", "icons")) {
                    assertEquals(node(oldPrompt.get(field)), node(now.get(field)),
                            () -> name + " changed public prompt field " + field + " without review");
                }
            }
        }
    }

    @Test
    void everyCurrentToolHasAUniqueDocumentationSectionWithResultFields() {
        List<Map<String, Object>> tools = entries(currentTools, "tools");
        assertUnique(tools);
        for (Map<String, Object> tool : tools) {
            assertFalse(strings(tool.get("documented_result_fields")).isEmpty(),
                    () -> string(tool, "name") + " has no machine-captured **Returns** contract");
        }
    }

    @Test
    void everyCurrentPromptHasExactlyOneDocumentationSection() throws IOException {
        Set<String> runtime = entries(currentPrompts, "prompts").stream()
                .map(prompt -> string(prompt, "name"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> documented = documentedPromptNames();
        assertEquals(runtime, documented, () -> "prompt documentation mismatch; missing="
                + difference(runtime, documented) + ", extra=" + difference(documented, runtime));
    }

    private static Map<String, Object> captureTools(String productVersion) throws IOException {
        List<SyncToolSpecification> specs = ToolCatalog.buildAll(new ToolContext(null, null));
        Set<String> names = specs.stream().map(s -> s.tool().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<String>> resultFields = documentedResultFields(names);

        List<Map<String, Object>> tools = new ArrayList<>();
        for (SyncToolSpecification spec : specs) {
            var tool = spec.tool();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", tool.name());
            row.put("title", tool.title());
            row.put("description", tool.description());
            row.put("input_schema", tool.inputSchema());
            row.put("output_schema", tool.outputSchema());
            row.put("annotations", tool.annotations());
            row.put("meta", tool.meta());
            row.put("icons", tool.icons());
            row.put("documented_result_fields", resultFields.get(tool.name()));
            tools.add(row);
        }
        return root(productVersion, "tools", tools);
    }

    private static Map<String, Object> capturePrompts(String productVersion) {
        List<Map<String, Object>> prompts = new ArrayList<>();
        for (SyncPromptSpecification spec : PromptCatalog.buildAll()) {
            var prompt = spec.prompt();
            List<Map<String, Object>> arguments = new ArrayList<>();
            Map<String, Object> sampleArguments = new LinkedHashMap<>();
            if (prompt.arguments() != null) {
                for (PromptArgument argument : prompt.arguments()) {
                    Map<String, Object> arg = new LinkedHashMap<>();
                    arg.put("name", argument.name());
                    arg.put("title", argument.title());
                    arg.put("description", argument.description());
                    arg.put("required", argument.required());
                    arguments.add(arg);
                    sampleArguments.put(argument.name(), "<" + argument.name() + ">");
                }
            }
            GetPromptResult rendered = spec.promptHandler().apply(null,
                    new GetPromptRequest(prompt.name(), sampleArguments));
            List<Map<String, Object>> messages = new ArrayList<>();
            for (PromptMessage message : rendered.messages()) {
                assertTrue(message.content() instanceof TextContent,
                        () -> prompt.name() + " snapshot only supports text prompt messages");
                TextContent content = (TextContent) message.content();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", message.role().name().toLowerCase());
                item.put("type", content.type());
                item.put("text", content.text());
                messages.add(item);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", prompt.name());
            row.put("title", prompt.title());
            row.put("description", prompt.description());
            row.put("arguments", arguments);
            row.put("meta", prompt.meta());
            row.put("icons", prompt.icons());
            row.put("sample_arguments", sampleArguments);
            row.put("rendered_description", rendered.description());
            row.put("rendered_messages", messages);
            prompts.add(row);
        }
        return root(productVersion, "prompts", prompts);
    }

    private static Map<String, Object> root(String productVersion, String key, Object value) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("snapshot_version", 1);
        root.put("product_version", productVersion);
        root.put(key, value);
        return root;
    }

    /** Extract each tool's documented result-field names from its unique Markdown section. */
    private static Map<String, List<String>> documentedResultFields(Set<String> toolNames)
            throws IOException {
        assertTrue(Files.isDirectory(TOOL_DOCS_DIR),
                () -> "tool documentation directory not found: " + TOOL_DOCS_DIR.toAbsolutePath());
        Map<String, LinkedHashSet<String>> found = new LinkedHashMap<>();
        Set<String> duplicateSections = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(TOOL_DOCS_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString)).collect(Collectors.toList())) {
                String current = null;
                boolean returns = false;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Matcher heading = TOOL_HEADING.matcher(line);
                    if (heading.matches()) {
                        current = toolNames.contains(heading.group(1)) ? heading.group(1) : null;
                        returns = false;
                        if (current != null && found.putIfAbsent(current, new LinkedHashSet<>()) != null) {
                            duplicateSections.add(current);
                        }
                        continue;
                    }
                    if (current == null) {
                        continue;
                    }
                    if (line.startsWith("**Returns**")) {
                        returns = true;
                        collectCodeSpans(line.substring("**Returns**".length()), found.get(current));
                        continue;
                    }
                    if (returns && (line.startsWith("## ") || line.startsWith("**Example**"))) {
                        returns = false;
                    }
                    if (returns && line.startsWith("- ")) {
                        int colon = line.indexOf(':');
                        collectCodeSpans(colon >= 0 ? line.substring(0, colon) : line, found.get(current));
                    }
                }
            }
        }
        assertTrue(duplicateSections.isEmpty(), () -> "duplicate tool documentation: " + duplicateSections);
        assertEquals(toolNames, found.keySet(), () -> "tool documentation mismatch; missing="
                + difference(toolNames, found.keySet()) + ", extra=" + difference(found.keySet(), toolNames));

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String name : toolNames) {
            result.put(name, new ArrayList<>(found.get(name)));
        }
        return result;
    }

    private static void collectCodeSpans(String text, Set<String> target) {
        Matcher matcher = CODE_SPAN.matcher(text);
        while (matcher.find()) {
            target.add(matcher.group(1));
        }
    }

    private static Set<String> documentedPromptNames() throws IOException {
        assertTrue(Files.isRegularFile(PROMPT_DOC),
                () -> "prompt documentation not found: " + PROMPT_DOC.toAbsolutePath());
        Set<String> names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(PROMPT_DOC, StandardCharsets.UTF_8)) {
            Matcher heading = TOOL_HEADING.matcher(line);
            if (heading.matches()) {
                assertTrue(names.add(heading.group(1)),
                        () -> "duplicate prompt documentation: " + heading.group(1));
            }
        }
        return names;
    }

    private static void validateUpdateVersion(String update) {
        validateUpdateVersion(update, CONTRACT_DIR);
    }

    private static void validateUpdateVersion(String update, Path contractDir) {
        if (!RELEASE.matcher(update).matches()) {
            throw new IllegalArgumentException(UPDATE_PROPERTY + " must be a major.minor.patch version");
        }
        if (BASELINE.equals(update)) {
            throw new IllegalArgumentException("Published baseline " + BASELINE + " is immutable");
        }
        if ((Files.exists(toolSnapshot(contractDir, update)) || Files.exists(promptSnapshot(contractDir, update)))
                && !Boolean.getBoolean(OVERWRITE_PROPERTY)) {
            throw new IllegalArgumentException("Snapshots for " + update + " already exist; pass -D"
                    + OVERWRITE_PROPERTY + "=true only to regenerate an unpublished draft");
        }
    }

    private static void assertInputSchemaBackwardCompatible(String tool, JsonNode oldSchema,
            JsonNode currentSchema) {
        assertNotNull(currentSchema, () -> tool + " lost its input schema");
        assertEquals(oldSchema.path("type"), currentSchema.path("type"), () -> tool + " changed schema type");
        assertEquals(oldSchema.path("required"), currentSchema.path("required"),
                () -> tool + " changed its required arguments");
        assertEquals(oldSchema.path("additionalProperties"), currentSchema.path("additionalProperties"),
                () -> tool + " changed unknown-argument handling");

        JsonNode oldProperties = oldSchema.path("properties");
        JsonNode currentProperties = currentSchema.path("properties");
        Iterator<String> fields = oldProperties.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            assertTrue(currentProperties.has(field), () -> tool + " removed argument " + field);
            assertEquals(oldProperties.get(field), currentProperties.get(field),
                    () -> tool + " changed the schema/meaning of argument " + field);
        }

        Set<String> ignored = Set.of("properties", "required");
        Set<String> oldRootFields = new LinkedHashSet<>();
        oldSchema.fieldNames().forEachRemaining(oldRootFields::add);
        Set<String> currentRootFields = new LinkedHashSet<>();
        currentSchema.fieldNames().forEachRemaining(currentRootFields::add);
        oldRootFields.remove("properties");
        currentRootFields.remove("properties");
        assertEquals(oldRootFields, currentRootFields,
                () -> tool + " added or removed a root schema constraint");

        Iterator<String> rootFields = oldSchema.fieldNames();
        while (rootFields.hasNext()) {
            String field = rootFields.next();
            if (!ignored.contains(field)) {
                assertEquals(oldSchema.get(field), currentSchema.get(field),
                        () -> tool + " changed input-schema field " + field);
            }
        }
    }

    private static Path toolSnapshot(String version) {
        return toolSnapshot(CONTRACT_DIR, version);
    }

    private static Path toolSnapshot(Path contractDir, String version) {
        return contractDir.resolve("v" + version + "-tools.json");
    }

    private static Path promptSnapshot(String version) {
        return promptSnapshot(CONTRACT_DIR, version);
    }

    private static Path promptSnapshot(Path contractDir, String version) {
        return contractDir.resolve("v" + version + "-prompts.json");
    }

    private static Map<String, Object> read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), () -> "missing contract golden: " + path);
        return JSON.readValue(Files.readString(path), new TypeReference<>() { });
    }

    private static String canonical(Object value) throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        return JSON.writer(printer).writeValueAsString(value) + "\n";
    }

    private static JsonNode node(Object value) {
        // Round-trip through text instead of valueToTree: both the parsed-golden and live-SDK
        // sides must yield identical numeric node types (IntNode and LongNode are never equal).
        try {
            return JSON.readTree(JSON.writeValueAsString(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> root, String key) {
        Object value = root.get(key);
        assertTrue(value instanceof List, () -> key + " snapshot entry must be an array");
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return value == null ? Collections.emptyList() : (List<Map<String, Object>>) value;
    }

    private static Map<String, Map<String, Object>> byName(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            result.put(string(entry, "name"), entry);
        }
        return result;
    }

    private static void assertUnique(List<Map<String, Object>> entries) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> entry : entries) {
            assertTrue(names.add(string(entry, "name")),
                    () -> "duplicate public contract name: " + string(entry, "name"));
        }
    }

    private static String string(Map<String, Object> map, String key) {
        return String.valueOf(map.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> strings(Object value) {
        return value == null ? Collections.emptySet() : new LinkedHashSet<>((List<String>) value);
    }

    private static <T> Set<T> difference(Set<T> left, Set<T> right) {
        Set<T> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }
}
