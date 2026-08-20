package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.tools.ToolCatalog;
import io.github.hakjuoh.protege_mcp.tools.ToolContext;
import io.github.hakjuoh.protege_mcp.catalog.McpCatalog;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/** Verifies that MCP tool contracts are self-contained without an Assistant system prompt. */
class ToolContractGuidanceTest {

    @Test
    void everyToolPublishesCompleteStandardBehaviorHints() {
        for (Tool tool : ToolCatalog.buildAll(new ToolContext(null, null)).stream()
                .map(spec -> spec.tool()).toList()) {
            assertNotNull(tool.annotations(), () -> tool.name() + " annotations");
            assertNotNull(tool.annotations().title(), () -> tool.name() + " title");
            assertNotNull(tool.annotations().readOnlyHint(), () -> tool.name() + " readOnlyHint");
            assertNotNull(tool.annotations().destructiveHint(),
                    () -> tool.name() + " destructiveHint");
            assertNotNull(tool.annotations().idempotentHint(),
                    () -> tool.name() + " idempotentHint");
            assertNotNull(tool.annotations().openWorldHint(),
                    () -> tool.name() + " openWorldHint");
            assertEquals(McpCatalog.get().tool(tool.name()).annotations(), tool.annotations(),
                    () -> tool.name() + " annotations must come from mcp-catalog.json");
        }
    }

    @Test
    void standardHintsClassifyBehaviorWhileDescriptionsCarryWorkflow() {
        Map<String, Tool> tools = tools();
        Tool getPolicy = tools.get("get_project_policy");
        Tool writePolicy = tools.get("write_project_policy");
        Tool search = tools.get("search_external_terms");
        Tool createClass = tools.get("create_class");

        assertAll(
                () -> assertEquals(true, getPolicy.annotations().readOnlyHint()),
                () -> assertEquals(false, getPolicy.annotations().destructiveHint()),
                () -> assertEquals(true, getPolicy.annotations().idempotentHint()),
                () -> assertEquals(true, getPolicy.annotations().openWorldHint()),
                () -> assertEquals(false, writePolicy.annotations().readOnlyHint()),
                () -> assertEquals(true, writePolicy.annotations().destructiveHint()),
                () -> assertEquals(false, writePolicy.annotations().idempotentHint()),
                () -> assertEquals(true, writePolicy.annotations().openWorldHint()),
                () -> assertEquals(true, search.annotations().readOnlyHint()),
                () -> assertEquals(true, search.annotations().openWorldHint()),
                () -> assertEquals(false, createClass.annotations().readOnlyHint()),
                () -> assertEquals(false, createClass.annotations().destructiveHint()),
                () -> assertEquals(false, createClass.annotations().openWorldHint()));
    }

    @Test
    void policyAndExternalTermWorkflowIsToolLocalAndSchemaExact() {
        Map<String, Tool> tools = tools();
        String policy = tools.get("get_project_policy").description();
        String write = tools.get("write_project_policy").description();
        String validate = tools.get("validate_project_policy").description();
        String search = tools.get("search_external_terms").description();
        String inspect = tools.get("inspect_external_term").description();
        @SuppressWarnings("unchecked")
        Map<String, Object> searchProperties = (Map<String, Object>) tools
                .get("search_external_terms").inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> providerId = (Map<String, Object>) searchProperties.get("provider_id");
        Map<String, Object> writeSchema = tools.get("write_project_policy").inputSchema();
        ToolSchemaValidator.Compiled writeArguments = ToolSchemaValidator.compile(writeSchema);
        Map<String, Object> incompleteOntoPortal = Map.of("patch", Map.of("external_terms",
                Map.of("providers", java.util.List.of(Map.of(
                        "id", "bioportal", "profile", "ontoportal", "enabled", true,
                        "origin_alias", "bioportal")))));
        Map<String, Object> completePresets = Map.of("patch", Map.of("external_terms",
                Map.of("providers", java.util.List.of(
                        Map.of("id", "bioportal", "profile", "ontoportal", "enabled", true,
                                "origin_alias", "bioportal", "credential_id", "bioportal"),
                        Map.of("id", "argoportal", "profile", "ontoportal", "enabled", true,
                                "origin_alias", "argoportal", "credential_id", "argoportal"),
                        Map.of("id", "embl-ebi", "profile", "ols4", "enabled", true,
                                "origin_alias", "embl-ebi")))));

        assertAll(
                () -> assertTrue(policy.contains("write_project_policy patch={...}")),
                () -> assertTrue(policy.contains("user-visible progress message")),
                () -> assertTrue(write.contains("no policy exists")),
                () -> assertTrue(write.contains("user-visible progress message")),
                () -> assertTrue(write.contains("never silently retry")),
                () -> assertTrue(write.contains("id, profile, enabled, origin_alias")),
                () -> assertTrue(write.contains("bioportal/ontoportal/bioportal/credential bioportal")),
                () -> assertFalse(writeArguments.violations(incompleteOntoPortal).isEmpty()),
                () -> assertTrue(writeArguments.violations(completePresets).isEmpty()),
                () -> assertTrue(validate.contains("structured findings")),
                () -> assertTrue(validate.contains("user-visible progress message")),
                () -> assertTrue(validate.contains("do not search the filesystem")),
                () -> assertTrue(search.contains("id (not provider_id)")),
                () -> assertTrue(search.contains("providers array is replaced as a whole")),
                () -> assertTrue(search.contains("BioPortal and ArgoPortal/AgroPortal")),
                () -> assertTrue(search.contains("user-visible progress message")),
                () -> assertTrue(search.contains("reasoning-only output")),
                () -> assertTrue(String.valueOf(providerId.get("description"))
                        .contains("policy row field is id (not provider_id)")),
                () -> assertTrue(String.valueOf(providerId.get("description"))
                        .contains("do not infer endpoints, credentials")),
                () -> assertTrue(inspect.contains("exact provider_id, ontology id, and IRI")));
    }

    private static Map<String, Tool> tools() {
        return ToolCatalog.buildAll(new ToolContext(null, null)).stream()
                .map(spec -> spec.tool()).collect(Collectors.toMap(Tool::name, Function.identity()));
    }
}
