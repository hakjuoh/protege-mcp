package io.github.hakjuoh.protege_mcp.chat.opencode;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;

/** Parses the raw JSON events emitted by {@code opencode run --format json}. */
final class OpenCodeEventParser implements Consumer<String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatListener listener;
    private final Set<String> completedParts = new HashSet<>();
    private long inputTokens;
    private long outputTokens;
    private long cachedInputTokens;
    private double costUsd;
    private boolean sawInputTokens;
    private boolean sawOutputTokens;
    private boolean sawCachedInputTokens;
    private boolean sawCost;
    private boolean answered;
    private boolean errorReported;

    OpenCodeEventParser(ChatListener listener) {
        this.listener = listener;
    }

    @Override
    public void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try (MappingIterator<JsonNode> events = MAPPER.readerFor(JsonNode.class).readValues(line)) {
            while (events.hasNextValue()) {
                handle(events.nextValue());
            }
        } catch (IOException ignored) {
            // Ignore non-event stdout; failures are also captured from stderr/exit status.
        }
    }

    private void handle(JsonNode event) {
        emitSessionId(event.path("sessionID").asText(""));
        JsonNode part = event.path("part");
        switch (event.path("type").asText()) {
            case "text" -> emitPart(part, false);
            case "reasoning" -> emitPart(part, true);
            case "tool_use" -> handleTool(part);
            case "step_finish" -> handleUsage(part);
            case "error" -> reportError(errorMessage(event.path("error")));
            default -> {
                // step_start and future raw event types are structural.
            }
        }
    }

    private void emitPart(JsonNode part, boolean reasoning) {
        String id = part.path("id").asText("");
        if (!id.isEmpty() && !completedParts.add(id)) {
            return;
        }
        String text = part.path("text").asText("");
        if (text.isEmpty()) {
            return;
        }
        if (reasoning) {
            listener.onThinking(text + (text.endsWith("\n") ? "" : "\n"));
        } else {
            answered |= !text.isBlank();
            listener.onAssistantText(text);
        }
    }

    private void handleTool(JsonNode part) {
        String id = part.path("id").asText("");
        if (!id.isEmpty() && !completedParts.add(id)) {
            return;
        }
        String tool = part.path("tool").asText("");
        listener.onToolActivity(tool.isEmpty() ? "tool call" : tool);
        JsonNode state = part.path("state");
        if ("error".equals(state.path("status").asText(""))) {
            reportError(state.path("error").asText("OpenCode tool call failed."));
        }
    }

    private void handleUsage(JsonNode part) {
        JsonNode tokens = part.path("tokens");
        if (nonNegativeInteger(tokens.path("input"))) {
            inputTokens = saturatedAdd(inputTokens, tokens.path("input").asLong());
            sawInputTokens = true;
        }
        if (nonNegativeInteger(tokens.path("output"))) {
            outputTokens = saturatedAdd(outputTokens, tokens.path("output").asLong());
            sawOutputTokens = true;
        }
        JsonNode cacheRead = tokens.path("cache").path("read");
        if (nonNegativeInteger(cacheRead)) {
            cachedInputTokens = saturatedAdd(cachedInputTokens, cacheRead.asLong());
            sawCachedInputTokens = true;
        }
        if (part.has("cost") && part.get("cost").isNumber()) {
            costUsd += part.get("cost").asDouble();
            sawCost = true;
        }
        listener.onResult(new ChatUsage(
                sawInputTokens ? saturatedInt(inputTokens) : -1,
                sawOutputTokens ? saturatedInt(outputTokens) : -1,
                sawCachedInputTokens ? saturatedInt(cachedInputTokens) : -1,
                sawCost ? costUsd : null));
    }

    private static int saturatedInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static boolean nonNegativeInteger(JsonNode value) {
        return value.isIntegralNumber() && value.asLong() >= 0;
    }

    private static long saturatedAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    private void reportError(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        errorReported = true;
        listener.onError(message);
    }

    private static String errorMessage(JsonNode error) {
        String message = error.path("data").path("message").asText("");
        if (!message.isBlank()) {
            return message;
        }
        message = error.path("message").asText("");
        if (!message.isBlank()) {
            return message;
        }
        return error.path("name").asText("OpenCode reported an error.");
    }

    private void emitSessionId(String id) {
        if (!id.isBlank()) {
            listener.onSessionId(id);
        }
    }

    boolean answered() {
        return answered;
    }

    boolean errorReported() {
        return errorReported;
    }
}
