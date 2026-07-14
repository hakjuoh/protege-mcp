package io.github.hakjuoh.protege_mcp.prompts;

/**
 * A group of related MCP prompts — the prompt-side mirror of {@code ToolProvider}.
 *
 * <p>Each provider contributes its prompt specifications into a shared {@link PromptRegistry} during
 * catalog assembly (see {@link PromptCatalog}). The single abstract method matches the signature of
 * every {@code *Prompts.register} <em>static</em> method, so the providers stay stateless static
 * utilities and are referenced as method references ({@code Prompts::register}) — no instances, no
 * per-provider {@code List} plumbing. Prompts are pure templates, so unlike tools there is no
 * context parameter.
 */
@FunctionalInterface
public interface PromptProvider {

    /** Contribute this provider's prompt specifications into {@code prompts}. */
    void register(PromptRegistry prompts);
}
