package io.github.hakjuoh.protege_mcp.chat.antigravity;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;

/** Parses Antigravity CLI {@code --output-format stream-json} events. */
final class AntigravityEventParser implements Consumer<String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatListener listener;
    private final StringBuilder streamedResponse = new StringBuilder();
    /** Documented step indexes whose assistant response has emitted visible text. */
    private final Set<String> startedResponseSteps = new HashSet<>();
    /** Fallback state for older/malformed streams that omit the documented step_index. */
    private boolean anonymousResponseStarted;
    private boolean answered;
    private boolean errorReported;

    AntigravityEventParser(ChatListener listener) {
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
            // A diagnostic on stdout is not an event; stderr remains available to the exit handler.
        }
    }

    private void handle(JsonNode event) {
        switch (event.path("event").asText()) {
            case "init" -> emitSessionId(event.path("conversation_id").asText(""));
            case "step_update" -> handleStep(event.path("step_update"));
            case "result" -> handleResult(event.path("result"));
            default -> {
                // Forward-compatible: ignore event types added by newer CLI releases.
            }
        }
    }

    private void handleStep(JsonNode step) {
        String type = step.path("step_type").asText("");
        String text = step.path("text_delta").asText("");
        if ("agent_response".equals(type)) {
            emitAssistantStep(step, text);
            return;
        }
        anonymousResponseStarted = false;
        if ("reasoning".equals(type) || "thinking".equals(type)) {
            if (!text.isEmpty()) {
                listener.onThinking(text);
            }
            return;
        }
        if (type.contains("tool") && "DONE".equals(step.path("state").asText(""))) {
            String name = firstNonBlank(step.path("tool_name").asText(""),
                    step.path("name").asText(""), step.path("title").asText(""));
            listener.onToolActivity(name.isEmpty() ? "tool call" : name);
        }
    }

    private void emitAssistantStep(JsonNode step, String text) {
        if (text == null || text.isEmpty()) {
            if ("DONE".equals(step.path("state").asText(""))
                    && !step.hasNonNull("step_index")) {
                anonymousResponseStarted = false;
            }
            return;
        }
        JsonNode index = step.path("step_index");
        if (!index.isMissingNode() && !index.isNull()) {
            if (startedResponseSteps.add(index.asText())) {
                listener.onAssistantMessageStart();
            }
        } else if (!anonymousResponseStarted) {
            listener.onAssistantMessageStart();
            anonymousResponseStarted = true;
        }
        emitAssistant(text);
        if ("DONE".equals(step.path("state").asText(""))
                && !step.hasNonNull("step_index")) {
            anonymousResponseStarted = false;
        }
    }

    private void handleResult(JsonNode result) {
        emitSessionId(result.path("conversation_id").asText(""));
        String response = result.path("response").asText("");
        if (!response.isEmpty()) {
            String streamed = streamedResponse.toString();
            if (streamed.isEmpty()) {
                listener.onAssistantMessageStart();
                emitAssistant(response);
            } else if (response.startsWith(streamed) && response.length() > streamed.length()) {
                emitAssistant(response.substring(streamed.length()));
            }
        }

        JsonNode usage = result.path("usage");
        listener.onResult(new ChatUsage(
                usage.path("input_tokens").asInt(-1),
                usage.path("output_tokens").asInt(-1),
                usage.path("cache_read_tokens").asInt(-1),
                null));

        String status = result.path("status").asText("");
        if (!"SUCCESS".equals(status)) {
            String message = result.path("error").asText("");
            errorReported = true;
            listener.onError(message.isBlank()
                    ? "Antigravity ended the turn with status "
                            + (status.isBlank() ? "UNKNOWN" : status) + "."
                    : message);
        }
    }

    private void emitAssistant(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        streamedResponse.append(text);
        answered |= !text.isBlank();
        listener.onAssistantText(text);
    }

    private void emitSessionId(String id) {
        if (id != null && !id.isBlank()) {
            listener.onSessionId(id);
        }
    }

    boolean answered() {
        return answered;
    }

    boolean errorReported() {
        return errorReported;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
