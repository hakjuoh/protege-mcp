package io.github.hakjuoh.protege_mcp.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Method-level tests for {@link PromptRegistry}, the fluent single-use sink the providers register
 * into. {@code prompt()} must delegate construction to {@link PromptSpecs#of} (same wiring as calling
 * the factory directly) and {@code build()} must expose the accumulated list in registration order.
 */
class PromptRegistryTest {

    @Test
    void promptReturnsTheSameRegistryForChaining() {
        PromptRegistry registry = new PromptRegistry();
        assertSame(registry,
                registry.prompt("a", "d", Collections.emptyList(), args -> "t"),
                "prompt() must return this so calls can chain");
    }

    @Test
    void resourceBackedPromptResolvesMetadataByName() {
        PromptRegistry registry = new PromptRegistry();
        assertSame(registry, registry.prompt("audit_ontology", args -> "rendered"));
        SyncPromptSpecification spec = registry.build().get(0);
        assertEquals("audit_ontology", spec.prompt().name());
        assertTrue(spec.prompt().description().startsWith("Audit the active ontology"));
    }

    @Test
    void buildStartsEmptyAndAccumulatesInRegistrationOrder() {
        PromptRegistry registry = new PromptRegistry();
        assertTrue(registry.build().isEmpty(), "a fresh registry holds no specs");
        registry.prompt("first", "d1", Collections.emptyList(), args -> "t1")
                .prompt("second", "d2", Collections.emptyList(), args -> "t2");
        List<SyncPromptSpecification> specs = registry.build();
        assertEquals(2, specs.size(), "one spec per prompt() call");
        assertEquals("first", specs.get(0).prompt().name(), "registration order preserved");
        assertEquals("second", specs.get(1).prompt().name(), "registration order preserved");
    }

    @Test
    void buildReturnsTheRegistrysOwnLiveList() {
        PromptRegistry registry = new PromptRegistry();
        List<SyncPromptSpecification> before = registry.build();
        registry.prompt("late", "d", Collections.emptyList(), args -> "t");
        assertSame(before, registry.build(), "build() exposes the registry's own mutable list");
        assertEquals(1, before.size(), "later registrations are visible through it");
    }

    @Test
    void promptDelegatesWiringToPromptSpecs() {
        PromptRegistry registry = new PromptRegistry();
        List<PromptArgument> argList = Collections.singletonList(
                new PromptArgument("x", "an x", Boolean.TRUE));
        registry.prompt("custom", "a custom prompt", argList,
                args -> "rendered:" + (args == null ? "null" : args.get("x")));
        SyncPromptSpecification spec = registry.build().get(0);

        assertEquals("custom", spec.prompt().name());
        assertEquals("a custom prompt", spec.prompt().description());
        assertEquals(1, spec.prompt().arguments().size());

        Map<String, Object> reqArgs = new HashMap<>();
        reqArgs.put("x", "hi");
        GetPromptResult result = spec.promptHandler().apply(null, new GetPromptRequest("custom", reqArgs));
        assertEquals("a custom prompt", result.description(),
                "the handler echoes the prompt description, as PromptSpecs.of wires it");
        assertEquals("rendered:hi", ((TextContent) result.messages().get(0).content()).text(),
                "the handler renders through the registered template");
    }
}
