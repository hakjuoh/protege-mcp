package io.github.hakjuoh.chat.claude;

import io.github.hakjuoh.chat.ChatListener;
import io.github.hakjuoh.chat.ChatUsage;

import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses one line of Claude Code's {@code --output-format stream-json --include-partial-messages}
 * output (line-delimited JSON) and drives a {@link ChatListener}. Event shapes are taken from a real
 * captured run:
 * <ul>
 *   <li>{@code {"type":"system","subtype":"init","session_id":...}} — session id</li>
 *   <li>{@code {"type":"stream_event","event":{...}}} — Anthropic SSE deltas: {@code content_block_delta}
 *       carries {@code text_delta}/{@code thinking_delta}; {@code content_block_start} announces a
 *       {@code tool_use} block</li>
 *   <li>{@code {"type":"result", ...}} — final {@code session_id}, {@code usage}, {@code total_cost_usd},
 *       {@code is_error}</li>
 * </ul>
 * Unknown event types are ignored, so a newer CLI emitting extra events degrades gracefully.
 */
final class ClaudeEventParser implements Consumer<String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatListener listener;

    // Running token counts carried across stream events for the live (in-flight) usage readout.
    private int liveInput = -1;
    private int liveCacheRead = -1;
    private int liveOutput = -1;

    ClaudeEventParser(ChatListener listener) {
        this.listener = listener;
    }

    @Override
    public void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (java.io.IOException e) {
            return;
        }
        switch (node.path("type").asText()) {
            case "system" -> {
                if ("init".equals(node.path("subtype").asText())) {
                    emitSessionId(node.path("session_id").asText(null));
                }
            }
            case "stream_event" -> handleStreamEvent(node.path("event"));
            case "result" -> handleResult(node);
            default -> {
                // assistant/user/other roll-up events: text + tools already streamed via stream_event
            }
        }
    }

    private void handleStreamEvent(JsonNode event) {
        switch (event.path("type").asText()) {
            case "content_block_delta" -> {
                JsonNode delta = event.path("delta");
                switch (delta.path("type").asText()) {
                    case "text_delta" -> {
                        String t = delta.path("text").asText("");
                        if (!t.isEmpty()) {
                            listener.onAssistantText(t);
                        }
                    }
                    case "thinking_delta" -> {
                        String t = delta.path("thinking").asText("");
                        if (!t.isEmpty()) {
                            listener.onThinking(t);
                        }
                    }
                    default -> {
                        // signature_delta / input_json_delta: nothing user-visible
                    }
                }
            }
            case "content_block_start" -> {
                JsonNode block = event.path("content_block");
                if ("tool_use".equals(block.path("type").asText())) {
                    listener.onToolActivity(stripToolPrefix(block.path("name").asText("tool")));
                }
            }
            case "message_start" -> updateLiveUsage(event.path("message").path("usage"));
            case "message_delta" -> updateLiveUsage(event.path("usage"));
            default -> {
                // message_stop / content_block_stop: structural
            }
        }
    }

    /** Fold whatever usage fields a stream event reports into the running count and emit it live. */
    private void updateLiveUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || !usage.isObject()) {
            return;
        }
        // Counts only ever climb within a turn; clamp so a malformed/out-of-order event can't make the
        // live readout visibly regress (the final onResult is still authoritative).
        if (usage.has("input_tokens")) {
            liveInput = Math.max(liveInput, usage.get("input_tokens").asInt(liveInput));
        }
        if (usage.has("cache_read_input_tokens")) {
            liveCacheRead = Math.max(liveCacheRead, usage.get("cache_read_input_tokens").asInt(liveCacheRead));
        }
        if (usage.has("output_tokens")) {
            liveOutput = Math.max(liveOutput, usage.get("output_tokens").asInt(liveOutput));
        }
        listener.onUsage(new ChatUsage(liveInput, liveOutput, liveCacheRead, null));
    }

    private void handleResult(JsonNode node) {
        emitSessionId(node.path("session_id").asText(null));
        JsonNode usage = node.path("usage");
        // Cost (total_cost_usd) is intentionally not surfaced — kept null so both providers show tokens only.
        listener.onResult(new ChatUsage(
                usage.path("input_tokens").asInt(-1),
                usage.path("output_tokens").asInt(-1),
                usage.path("cache_read_input_tokens").asInt(-1),
                null));
        if (node.path("is_error").asBoolean(false)) {
            String msg = node.path("result").asText("");
            listener.onError(msg.isEmpty() ? "Claude reported an error." : msg);
        }
    }

    private void emitSessionId(String id) {
        if (id != null && !id.isEmpty()) {
            listener.onSessionId(id);
        }
    }

    /** {@code mcp__protege__create_class} -> {@code create_class}; leaves plain names untouched. */
    static String stripToolPrefix(String name) {
        if (name == null) {
            return "tool";
        }
        String[] parts = name.split("__");
        if (parts.length >= 3 && "mcp".equals(parts[0])) {
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) {
                    sb.append("__");
                }
                sb.append(parts[i]);
            }
            return sb.toString();
        }
        return name;
    }
}
