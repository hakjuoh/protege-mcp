package io.github.hakjuoh.server;

/**
 * Thrown when a model read/write could not be marshalled onto (or completed on) the Swing event
 * dispatch thread — for example the EDT was busy and the bounded wait timed out. Tool handlers
 * turn this into an MCP error result rather than letting it escape.
 */
public class McpAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public McpAccessException(String message) {
        super(message);
    }

    public McpAccessException(Throwable cause) {
        super(cause);
    }

    public McpAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
