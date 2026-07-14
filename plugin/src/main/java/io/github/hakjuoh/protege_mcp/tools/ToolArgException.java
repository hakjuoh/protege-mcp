package io.github.hakjuoh.protege_mcp.tools;

/**
 * Thrown when a tool argument is missing or invalid. Handlers turn it into a (non-fatal) MCP error
 * result with the message shown to the client.
 */
public class ToolArgException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolArgException(String message) {
        super(message);
    }
}
