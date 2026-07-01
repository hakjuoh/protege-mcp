package io.github.hakjuoh.tools;

/**
 * A group of related MCP tools — the unified type that represents every {@code *Tools} class.
 *
 * <p>Each provider contributes its tool specifications into a shared {@link ToolRegistry} during
 * catalog assembly (see {@link ToolCatalog}). The single abstract method matches the signature of
 * every {@code *Tools.register} <em>static</em> method, so the providers stay stateless static
 * utilities and are referenced as method references ({@code ReadTools::register}) — no instances,
 * no per-provider {@code List} plumbing.
 */
@FunctionalInterface
public interface ToolProvider {

    /** Contribute this provider's tool specifications into {@code tools} using {@code ctx}. */
    void register(ToolRegistry tools, ToolContext ctx);
}
