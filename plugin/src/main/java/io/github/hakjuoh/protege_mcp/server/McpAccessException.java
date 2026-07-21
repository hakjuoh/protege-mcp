package io.github.hakjuoh.protege_mcp.server;

/**
 * Thrown when a model read/write could not be marshalled onto (or completed on) the Swing event
 * dispatch thread — for example the EDT was busy and the bounded wait timed out. Tool handlers
 * turn this into an MCP error result rather than letting it escape.
 */
public class McpAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final Outcome outcome;

    public enum Outcome {
        /** The queued body was cancelled before it could read or mutate the model. */
        EFFECTS_PREVENTED,
        /** The body started, so a mutation may have committed even though no result was returned. */
        OUTCOME_UNKNOWN
    }

    public McpAccessException(String message) {
        this(message, null, Outcome.OUTCOME_UNKNOWN);
    }

    public McpAccessException(Throwable cause) {
        this(cause == null ? null : cause.toString(), cause, Outcome.OUTCOME_UNKNOWN);
    }

    public McpAccessException(String message, Throwable cause) {
        this(message, cause, Outcome.OUTCOME_UNKNOWN);
    }

    public McpAccessException(String message, Throwable cause, Outcome outcome) {
        super(message, cause);
        this.outcome = outcome == null ? Outcome.OUTCOME_UNKNOWN : outcome;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean effectsPrevented() {
        return outcome == Outcome.EFFECTS_PREVENTED;
    }

    public static McpAccessException effectsPrevented(String message) {
        return new McpAccessException(message, null, Outcome.EFFECTS_PREVENTED);
    }
}
