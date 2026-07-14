package io.github.hakjuoh.protege_mcp.prompts;

import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;

/** Aggregates every prompt specification the server exposes — the prompt-side mirror of {@code ToolCatalog}. */
public final class PromptCatalog {

    private PromptCatalog() {
    }

    /**
     * Every prompt provider, in registration order — the single source of truth. A provider is
     * declared exactly once here; {@link #buildAll} lets each contribute into one shared
     * {@link PromptRegistry}. Package-private so the aggregation test iterates this same list rather
     * than re-listing the providers (which could silently drift).
     */
    static final List<PromptProvider> PROVIDERS = List.of(
            Prompts::register);

    public static List<SyncPromptSpecification> buildAll() {
        PromptRegistry registry = new PromptRegistry();
        for (PromptProvider provider : PROVIDERS) {
            provider.register(registry);
        }
        return registry.build();
    }
}
