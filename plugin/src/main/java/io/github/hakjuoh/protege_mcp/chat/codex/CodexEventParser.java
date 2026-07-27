package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
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
    /** Enough of the stream's errors to classify them, without buffering a runaway error loop. */
    private static final int MAX_ERROR_CHARS = 4000;
    /** How many distinct failures to report per turn; past that the transcript says so, once. */
    static final int MAX_DISTINCT_ERRORS = 32;
    /** What replaces the rest of a runaway error loop, so the omission is visible rather than silent. */
    static final String FURTHER_ERRORS_NOTE =
            "… further errors from this turn are not shown (there were more than "
                    + MAX_DISTINCT_ERRORS + ").";
    /** Enough of the newest failure to classify it after the kept text has filled up. */
    private static final int MAX_LAST_ERROR_CHARS = 1000;
    /**
     * Joins an item id to its message for the seen-errors key. NUL cannot occur in either part, so no
     * pair of them can collide. Written as an escape: as a raw byte it makes this whole file binary to
     * grep and every other line-oriented tool, which silently hides it from review.
     */
    private static final char KEY_SEPARATOR = '\0';

    private final ChatListener listener;
    /** Per agent_message item id: how many chars already emitted (avoids re-printing on completion). */
    private final Map<String, Integer> emitted = new HashMap<>();
    /**
     * Whether any assistant text reached the listener. Codex surfaces the errors of a retry loop as it
     * goes, so a turn can report a failure and still answer; the completion handler needs to know which
     * happened before it describes the turn to the user.
     */
    private boolean answered;

    // Whether the stream itself surfaced an error (a turn.failed/error event or an error item).
    // Read by the provider's completion handler to decide if the generic exit-code line would be a
    // duplicate. Plain field: line parsing and process completion run on the same worker thread.
    private boolean errorReported;
    /** The error text itself, so the completion handler can tell what kind of failure it was. */
    private final StringBuilder errors = new StringBuilder();
    /** Failures already shown, keyed by item id and text, so one item redelivered is reported once. */
    private final Set<String> reportedItems = new HashSet<>();
    /** Texts already shown, so a turn-level event echoing an item's message is reported once. */
    private final Set<String> reportedTexts = new HashSet<>();
    /** How many distinct failures have been shown (bounds both sets above). */
    private int reportedCount;
    /** Whether the "further errors" line has been shown for this turn. */
    private boolean furtherErrorsNoted;
    /** The newest distinct failure, bounded - the kept text may have truncated it away. */
    private String lastError = "";
    /** Whether the kept text holds that newest failure whole, so classifying it again adds nothing. */
    private boolean lastErrorKept;

    CodexEventParser(ChatListener listener) {
        this.listener = listener;
    }

    @Override
    public void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        // One line is normally one event, but nothing in the pipe guarantees a newline was flushed between
        // two of them. Reading only the first value would silently drop whatever followed it on the line -
        // a turn.completed among them takes the turn's usage with it, an error item its failure - so every
        // value is handled in order, and a malformed remainder still leaves the values before it delivered
        // rather than discarding the line whole.
        try (MappingIterator<JsonNode> events = MAPPER.readerFor(JsonNode.class).readValues(line)) {
            while (events.hasNextValue()) {
                handleEvent(events.nextValue());
            }
        } catch (java.io.IOException notJson) {
            // Not JSON, or not any more: the rest of the line is dropped.
        }
    }

    private void handleEvent(JsonNode node) {
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
                reportError(node.path("id").asText(""), msg);
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
                            emitAssistant(t);
                        }
                    }
                } else {
                    emitDelta(id, item.path("text").asText(""));
                }
            }
            case "reasoning" -> {
                if (completed) {
                    String t = firstNonEmpty(item.path("text").asText(""),
                            summaryText(item.path("summary")));
                    if (!t.isEmpty()) {
                        // Each completed item is a whole reasoning block (codex itself joins a
                        // block's sections), so terminate the line: two blocks rendered back to
                        // back — possible when a parser-ignored item sits between them — must not
                        // glue mid-sentence. Claude's within-block deltas stay unterminated.
                        listener.onThinking(t + "\n");
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
            case "error" -> reportError(id, item.path("message").asText(""));
            default -> {
                // ignore unknown item types
            }
        }
    }

    /**
     * Surfaces one stream error and keeps a bounded copy for the completion handler to classify.
     *
     * <p>One failure can arrive several times: an {@code error} item is delivered again on every
     * {@code item.updated}/{@code item.completed} for that item, and a {@code turn.failed} event
     * usually repeats the message the item already carried. A repeat is not new information, so it is
     * reported once - otherwise the transcript reads as though the turn failed twice. Suppression is
     * therefore keyed by the item's identity, not by the text alone: two different things going wrong
     * with the same generic message are two failures and are both shown. An event that carries no item
     * id (a turn-level failure) is the one case matched on text, because that is all it shares with the
     * item it is echoing.
     *
     * <p>Past {@link #MAX_DISTINCT_ERRORS} distinct failures the turn is in a loop; one line says so
     * and the rest are dropped, so a runaway CLI cannot flood the transcript. The newest text is still
     * remembered for classification even then.
     */
    private void reportError(String itemId, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        errorReported = true;
        boolean identified = itemId != null && !itemId.isEmpty();
        String key = identified ? itemId + KEY_SEPARATOR + message : message;
        if (identified ? reportedItems.contains(key) : reportedTexts.contains(message)) {
            return;
        }
        lastError = boundedForClassification(message);
        lastErrorKept = false;
        if (reportedCount >= MAX_DISTINCT_ERRORS) {
            if (!furtherErrorsNoted) {
                furtherErrorsNoted = true;
                keepBounded(FURTHER_ERRORS_NOTE);
                listener.onError(FURTHER_ERRORS_NOTE);
            }
            return;
        }
        reportedCount++;
        if (identified) {
            reportedItems.add(key);
        }
        reportedTexts.add(message);
        lastErrorKept = keepBounded(message);
        listener.onError(message);
    }

    /**
     * The newest failure cut down to {@link #MAX_LAST_ERROR_CHARS} for classification, keeping both ends
     * of it. Which end carries the answer depends on the refuser: a Codex parser rejecting the setting
     * says so in its first words, while an API rejecting the value buries "Invalid value: 'max'. Supported
     * values are …" behind a status line and a JSON envelope. Keeping only the head loses the second, and
     * a diagnostic long enough to need cutting is one whose middle is a payload dump. What is shown to the
     * user is never cut.
     */
    private static String boundedForClassification(String message) {
        if (message.length() <= MAX_LAST_ERROR_CHARS) {
            return message;
        }
        int half = MAX_LAST_ERROR_CHARS / 2;
        return message.substring(0, half) + '…' + message.substring(message.length() - half);
    }

    /**
     * Appends as much of one error as the kept-text budget still allows, so a single huge diagnostic
     * cannot grow {@link #errorText()} past {@link #MAX_ERROR_CHARS} (plus the elision mark). The cap
     * bounds what is kept for classification, never what the user is shown. Returns whether the message
     * was kept whole, which is what {@link #classifiableErrorText()} needs to know.
     */
    private boolean keepBounded(String message) {
        int room = MAX_ERROR_CHARS - errors.length();
        if (room <= 0) {
            return false;
        }
        String text = errors.length() > 0 ? "\n" + message : message;
        if (text.length() <= room) {
            errors.append(text);
            return true;
        }
        errors.append(text, 0, room).append('…');
        return false;
    }

    /** Whether an error already reached the listener via the stream (see {@link #errorReported}). */
    boolean errorReported() {
        return errorReported;
    }

    /** Whether the turn produced a reply, whatever else it reported (see {@link #answered}). */
    boolean answered() {
        return answered;
    }

    /** The stream's error text, joined by newlines and bounded; empty when the stream reported none. */
    String errorText() {
        return errors.toString();
    }

    /**
     * What the completion handler classifies: the kept text, plus the newest failure whenever the budget
     * did not keep that failure whole. Without this, one huge early diagnostic could bury the very
     * message that says what was refused - the failure the user needs named is usually the last one, not
     * the first.
     *
     * <p>Whether it was kept is recorded when it is kept, not searched for afterwards. Searching the kept
     * text for the remembered failure looks equivalent and is not: the remembered copy is itself bounded
     * to {@link #MAX_LAST_ERROR_CHARS}, so a retry loop reprinting a preamble that long would reduce it to
     * text the earlier attempt already left in the kept text. The search would succeed on that and drop
     * the tail that says which value was refused.
     */
    String classifiableErrorText() {
        String kept = errors.toString();
        if (lastErrorKept || lastError.isEmpty()) {
            return kept;
        }
        return kept.isEmpty() ? lastError : kept + "\n" + lastError;
    }

    /** Emit only the not-yet-seen suffix of {@code fullText} for item {@code id}. */
    private void emitDelta(String id, String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return;
        }
        int prev = emitted.getOrDefault(id, 0);
        if (fullText.length() > prev) {
            emitAssistant(fullText.substring(prev));
            emitted.put(id, fullText.length());
        }
    }

    /**
     * Emit assistant text, recording that this turn answered. The one path that may set that flag.
     *
     * <p>Whitespace alone does not count. The view decides the same question the same way when it keeps a
     * turn as conversation history, and the two must agree: a turn whose whole reply was blank is a turn
     * with nothing in it, so calling it answered would describe a reply the user cannot see. Deltas that
     * are only the space between two words still stream - the words carry the flag.
     */
    private void emitAssistant(String text) {
        answered |= !text.isBlank();
        listener.onAssistantText(text);
    }

    /**
     * A reasoning item's {@code summary} as text. The captured run carries a plain string, but the
     * Responses API this rides on also shapes summaries as an array of {@code summary_text} parts
     * (or a single object) — {@code asText("")} would silently swallow those, so each shape is read
     * explicitly and array parts are joined with blank lines.
     */
    static String summaryText(JsonNode summary) {
        if (summary.isTextual()) {
            return summary.asText();
        }
        if (summary.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : summary) {
                String t = part.isTextual() ? part.asText() : part.path("text").asText("");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        if (summary.isObject()) {
            return summary.path("text").asText("");
        }
        return "";
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
