package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.hakjuoh.protege_mcp.contracts.ToolError;
import io.github.hakjuoh.protege_mcp.contracts.ContractRedactor;
import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;
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
        Map<String, Object> body = immutableBody(data == null ? Map.of() : data);
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

    /** A stable structured error result retaining the legacy {@code error} alias. */
    public static CallToolResult error(String message) {
        return error(ToolError.of("operation_failed",
                message == null || message.isBlank() ? "error" : message, false));
    }

    public static CallToolResult error(ToolError failure) {
        Map<String, Object> body = immutableBody(failure.toJson());
        return CallToolResult.builder()
                .structuredContent(body)
                .addTextContent(serialize(body))
                .isError(true)
                .build();
    }

    /** Canonicalize both MCP representations around one immutable JSON snapshot. */
    static CallToolResult immutableSnapshot(CallToolResult result) {
        if (result == null || !(result.structuredContent() instanceof Map<?, ?> raw)) return result;
        Map<String, Object> body = immutableBody(raw);
        return CallToolResult.builder().structuredContent(body)
                .addTextContent(serialize(body)).isError(result.isError()).build();
    }

    private static Map<String, Object> immutableBody(Map<?, ?> data) {
        Map<String, Object> normalized = JSON.convertValue(data,
                new TypeReference<Map<String, Object>>() { });
        return ImmutableJson.resultMap(normalized);
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
            try {
                return error(ToolError.of(e.code(), e.getMessage(), e.details(), e.retryable(),
                        e.secretCanaries()));
            } catch (RuntimeException invalidTypedError) {
                log.warn("protege-mcp: invalid typed error at tool boundary; type={}",
                        invalidTypedError.getClass().getName());
                return error(ToolError.of("internal_error", "Unexpected tool failure.", false));
            }
        } catch (McpAccessException e) {
            boolean prevented = e.effectsPrevented();
            return error(ToolError.of(prevented ? "model_access_prevented"
                    : "model_access_outcome_unknown",
                    e.getMessage() == null ? "Model access failed." : e.getMessage(),
                    Map.of("effects_prevented", prevented, "outcome_unknown", !prevented),
                    prevented));
        } catch (RuntimeException e) {
            // Unexpected (not a typed ToolArg/McpAccess) failure — a handler bug. Record only its
            // class server-side so exception messages, paths, and provider payloads cannot cross the
            // redaction boundary; the client receives the stable terse error below.
            log.warn("protege-mcp: unexpected error in tool handler; type={}",
                    ContractRedactor.sanitize(e.getClass().getName()));
            return error(ToolError.of("internal_error", "Unexpected tool failure.", false));
        }
    }
}
