package io.github.hakjuoh.protege_mcp.prompts;

import java.util.ArrayList;
import java.util.List;

import io.github.hakjuoh.protege_mcp.catalog.McpCatalog;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;

/**
 * Collects the {@link SyncPromptSpecification}s contributed by the {@link PromptProvider}s during
 * catalog assembly — the prompt-side mirror of {@code ToolRegistry}. It replaces the per-provider
 * {@code new ArrayList<>()} / {@code add(...)} / {@code return} boilerplate with a single fluent
 * sink: each {@link #prompt} call is delegated to {@link PromptSpecs#of}, so the factory remains the
 * single point where a specification is constructed.
 *
 * <p>A registry is single-use per {@link PromptCatalog#buildAll}: the providers register into one
 * shared instance in declaration order and {@link #build()} yields the accumulated list.
 */
public final class PromptRegistry {

    private final List<SyncPromptSpecification> specs = new ArrayList<>();

    /**
     * Register a renderer by name, resolving its description and arguments from the shared JSON
     * catalog.
     */
    public PromptRegistry prompt(String name, PromptTemplate template) {
        McpCatalog.PromptDefinition definition = McpCatalog.get().prompt(name);
        return prompt(definition.name(), definition.description(), definition.arguments(), template);
    }

    /**
     * Register explicitly supplied metadata. Retained for focused factory tests and extensions that
     * are not part of the built-in catalog.
     */
    public PromptRegistry prompt(String name, String description,
            List<PromptArgument> arguments, PromptTemplate template) {
        specs.add(PromptSpecs.of(name, description, arguments, template));
        return this;
    }

    /** The specifications collected so far, in registration order (the registry's own mutable list). */
    public List<SyncPromptSpecification> build() {
        return specs;
    }
}
