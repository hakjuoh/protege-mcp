package io.github.hakjuoh.protege_mcp.chat;

import java.util.ArrayList;
import java.util.List;

/** Test double that accumulates every {@link ChatListener} callback for assertions. */
public final class RecordingChatListener implements ChatListener {

    public final StringBuilder text = new StringBuilder();
    public final StringBuilder thinking = new StringBuilder();
    public final List<String> tools = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final List<ChatUsage> liveUsages = new ArrayList<>();
    public String sessionId;
    public ChatUsage usage;
    public Integer exit;

    @Override
    public void onSessionId(String id) {
        sessionId = id;
    }

    @Override
    public void onAssistantText(String t) {
        text.append(t);
    }

    @Override
    public void onThinking(String t) {
        thinking.append(t);
    }

    @Override
    public void onToolActivity(String summary) {
        tools.add(summary);
    }

    @Override
    public void onUsage(ChatUsage u) {
        liveUsages.add(u);
    }

    @Override
    public void onResult(ChatUsage u) {
        usage = u;
    }

    @Override
    public void onError(String message) {
        errors.add(message);
    }

    @Override
    public void onComplete(int exitCode) {
        exit = exitCode;
    }
}
