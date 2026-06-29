package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses one line of Codex's {@code codex exec --json} output (JSONL) and drives a {@link ChatListener}.
 * Event shapes are taken from a real captured run:
 * <ul>
 *   <li>{@code {"type":"thread.started","thread_id":...}} — session id (used to resume)</li>
 *   <li>{@code {"type":"item.started|updated|completed","item":{...}}} — items of type
 *       {@code agent_message} (assistant text), {@code reasoning}, {@code mcp_tool_call},
 *       {@code command_execution}, {@code error}, ...</li>
 *   <li>{@code {"type":"turn.completed","usage":{input_tokens,cached_input_tokens,output_tokens,...}}}</li>
 * </ul>
 * Codex emits an {@code agent_message} item as a whole (no token deltas in the captured run), but this
 * parser tracks per-item emitted length so it streams correctly whether updates arrive incrementally or
 * only on completion. Unknown event/item types are ignored.
 */
final class CodexEventParser implements Consumer<String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatListener listener;
    /** Per agent_message item id: how many chars already emitted (avoids re-printing on completion). */
    private final Map<String, Integer> emitted = new HashMap<>();

    CodexEventParser(ChatListener listener) {
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
        String type = node.path("type").asText();
        switch (type) {
            case "thread.started" -> {
                String id = node.path("thread_id").asText(null);
                if (id != null && !id.isEmpty()) {
                    listener.onSessionId(id);
                }
            }
            case "item.started", "item.updated", "item.completed" ->
                    handleItem(node.path("item"), type.endsWith(".completed"));
            case "turn.completed" -> {
                JsonNode usage = node.path("usage");
                listener.onResult(new ChatUsage(
                        usage.path("input_tokens").asInt(-1),
                        usage.path("output_tokens").asInt(-1),
                        usage.path("cached_input_tokens").asInt(-1),
                        null));
            }
            case "turn.failed", "error" -> {
                String msg = firstNonEmpty(
                        node.path("error").path("message").asText(""),
                        node.path("message").asText(""),
                        node.path("error").asText(""));
                if (!msg.isEmpty()) {
                    listener.onError(msg);
                }
            }
            default -> {
                // turn.started and any future event types
            }
        }
    }

    private void handleItem(JsonNode item, boolean completed) {
        String itemType = item.path("type").asText();
        String id = item.path("id").asText("");
        switch (itemType) {
            case "agent_message" -> {
                if (id.isEmpty()) {
                    // No id to dedup against; emit the whole message once, on completion.
                    if (completed) {
                        String t = item.path("text").asText("");
                        if (!t.isEmpty()) {
                            listener.onAssistantText(t);
                        }
                    }
                } else {
                    emitDelta(id, item.path("text").asText(""));
                }
            }
            case "reasoning" -> {
                if (completed) {
                    String t = firstNonEmpty(item.path("text").asText(""), item.path("summary").asText(""));
                    if (!t.isEmpty()) {
                        listener.onThinking(t);
                    }
                }
            }
            case "mcp_tool_call" -> {
                if (completed) {
                    String tool = firstNonEmpty(item.path("tool").asText(""),
                            item.path("tool_name").asText(""), item.path("name").asText(""));
                    listener.onToolActivity(tool.isEmpty() ? "tool call" : tool);
                }
            }
            case "command_execution" -> {
                if (completed) {
                    String cmd = item.path("command").asText("");
                    listener.onToolActivity(cmd.isEmpty() ? "command" : "$ " + cmd);
                }
            }
            case "file_change" -> {
                if (completed) {
                    listener.onToolActivity("file change");
                }
            }
            case "web_search" -> {
                if (completed) {
                    listener.onToolActivity("web search");
                }
            }
            case "error" -> {
                String msg = item.path("message").asText("");
                if (!msg.isEmpty()) {
                    listener.onError(msg);
                }
            }
            default -> {
                // ignore unknown item types
            }
        }
    }

    /** Emit only the not-yet-seen suffix of {@code fullText} for item {@code id}. */
    private void emitDelta(String id, String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        int prev = emitted.getOrDefault(id, 0);
        if (fullText.length() > prev) {
            listener.onAssistantText(fullText.substring(prev));
            emitted.put(id, fullText.length());
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
