package io.github.hakjuoh.protege_mcp.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral conversation history plus each CLI provider's resume position.
 *
 * <p>Claude and Codex cannot share a native session id. Instead, each provider keeps its own id and
 * a cursor into this common history. Before a provider's next turn, {@link #handoffFor(String)}
 * supplies the user/assistant turns that happened since that provider last completed a turn. This
 * lets a resumed native session catch up without splitting the visible conversation into branches.
 */
public final class ChatHistory {

    static final int MAX_HANDOFF_CHARS = 64_000;

    public enum Role { USER, ASSISTANT }

    public record Entry(Role role, String providerId, String text) {
        public Entry {
            providerId = providerId == null ? "" : providerId;
            text = text == null ? "" : text;
        }
    }

    private static final class ProviderState {
        private String sessionId;
        private int syncedEntries;
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, ProviderState> providers = new HashMap<>();

    public synchronized String sessionId(String providerId) {
        ProviderState state = providers.get(key(providerId));
        return state == null ? null : state.sessionId;
    }

    public synchronized void setSessionId(String providerId, String sessionId) {
        state(providerId).sessionId = sessionId;
    }

    public synchronized void addUser(String providerId, String text) {
        entries.add(new Entry(Role.USER, key(providerId), text));
    }

    public synchronized void addAssistant(String providerId, String text) {
        if (text != null && !text.isBlank()) {
            entries.add(new Entry(Role.ASSISTANT, key(providerId), text));
        }
    }

    /** Mark every current entry as already known by this provider's native CLI session. */
    public synchronized void markSynced(String providerId) {
        state(providerId).syncedEntries = entries.size();
    }

    /**
     * Render the turns this provider has not seen. The newest content wins when the safety cap is
     * reached, mirroring context compaction while keeping the command line comfortably bounded.
     */
    public synchronized String handoffFor(String providerId) {
        ProviderState state = state(providerId);
        int from = Math.max(0, Math.min(state.syncedEntries, entries.size()));
        if (from == entries.size()) {
            return "";
        }

        StringBuilder transcript = new StringBuilder();
        for (int i = from; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (transcript.length() > 0) {
                transcript.append("\n\n");
            }
            if (entry.role() == Role.USER) {
                transcript.append("User");
            } else {
                transcript.append("Assistant");
                if (!entry.providerId().isBlank()) {
                    transcript.append(" (").append(entry.providerId()).append(')');
                }
            }
            transcript.append(":\n").append(entry.text());
        }

        boolean truncated = transcript.length() > MAX_HANDOFF_CHARS;
        String body = truncated
                ? transcript.substring(transcript.length() - MAX_HANDOFF_CHARS)
                : transcript.toString();
        return "The visible conversation continued while this CLI session was not active. "
                + "Incorporate the missing transcript below into the existing conversation and answer the "
                + "user's new message normally. Do not repeat or summarize the transcript unless asked.\n\n"
                + "<provider-handoff>\n"
                + (truncated ? "[Earlier handoff content was compacted; the newest content follows.]\n" : "")
                + body
                + "\n</provider-handoff>";
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized void clear() {
        entries.clear();
        providers.clear();
    }

    private ProviderState state(String providerId) {
        return providers.computeIfAbsent(key(providerId), ignored -> new ProviderState());
    }

    private static String key(String providerId) {
        return providerId == null ? "" : providerId;
    }
}
