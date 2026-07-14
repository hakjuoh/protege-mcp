package io.github.hakjuoh.protege_mcp.prompts;

import java.util.Map;

/**
 * Renders a prompt's user-message text from its (possibly empty) request arguments — the prompt-side
 * analogue of a tool's call handler. Implementations are pure functions of their arguments: no model
 * access, no side effects.
 */
@FunctionalInterface
public interface PromptTemplate {

    /** Render the message text; {@code args} may be {@code null} or missing declared keys. */
    String render(Map<String, Object> args);
}
