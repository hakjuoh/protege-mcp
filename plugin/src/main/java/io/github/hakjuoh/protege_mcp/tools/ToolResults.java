package io.github.hakjuoh.protege_mcp.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Construction of MCP {@link CallToolResult}s (success/error/message), the single JSON serializer, and
 * the exception-to-error {@link #guard}. Split out of {@link Tools} as a focused, testable unit; the
 * fluent {@code Tools.json()} builder and its {@code Tools.Json} type deliberately stay nested in
 * {@link Tools} (they are referenced as a type across the codebase). {@code Tools} keeps thin delegators.
 */
public final class ToolResults {

    private static final Logger log = LoggerFactory.getLogger(ToolResults.class);

    private ToolResults() {
    }

    /**
     * The single {@link ObjectMapper}; results are carried both as MCP {@code structuredContent} and,
     * serialized here, as the text content, so every client sees the same JSON.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Build a {@link CallToolResult} from a result object (success). */
    public static CallToolResult ok(Map<String, Object> data) {
        Map<String, Object> body = data == null ? new LinkedHashMap<>() : data;
        return CallToolResult.builder()
                .structuredContent(body)
                .addTextContent(serialize(body))
                .isError(false)
                .build();
    }

    /**
     * A plain confirmation/message result: {@code {"message": s}}. Kept so trivial confirmations stay
     * one-liners and any not-yet-restructured handler still emits valid JSON.
     */
    public static CallToolResult text(String s) {
        return Tools.json().put("message", s == null ? "" : s).result();
    }

    /** An error result: {@code {"error": message}} with {@code isError=true}. */
    public static CallToolResult error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null ? "error" : message);
        return CallToolResult.builder()
                .structuredContent(body)
                .addTextContent(serialize(body))
                .isError(true)
                .build();
    }

    /** Serialize a result object to pretty JSON; never throws (falls back to {@code toString}). */
    public static String serialize(Object data) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(data);
        }
    }

    /** Run a handler body, converting expected exceptions into a non-fatal MCP error result. */
    public static CallToolResult guard(Supplier<CallToolResult> body) {
        try {
            return body.get();
        } catch (ToolArgException e) {
            return error(e.getMessage());
        } catch (McpAccessException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            // Unexpected (not a typed ToolArg/McpAccess) failure — a handler bug. Leave a server-side
            // stack trace so it can be diagnosed from a field report, while still returning the terse
            // client message (the tools/ layer otherwise logs nothing).
            log.warn("protege-mcp: unexpected error in tool handler", e);
            String msg = e.getMessage();
            return error(e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
        }
    }
}
