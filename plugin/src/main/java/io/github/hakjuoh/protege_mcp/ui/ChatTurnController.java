package io.github.hakjuoh.protege_mcp.ui;

import io.github.hakjuoh.protege_mcp.broker.McpBoot;
import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatHistory;
import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.ui.ChatTranscriptPane.Kind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Owns the complete lifecycle of one assistant turn.
 *
 * <p>Provider callbacks may arrive off the EDT. They update thread-safe history, the single-turn
 * assistant buffer, guarded render queue, or volatile completion metadata; they never touch Swing.
 * A Swing timer drains that state on the EDT, where every {@link Host} callback runs. Keeping
 * process publication, cancellation, credential renewal, streaming and finalization in one owner
 * makes the turn state transitions explicit and prevents the view from becoming a second lifecycle
 * authority. Construction and all package-visible lifecycle/query methods are EDT-confined; only
 * the provider-facing listener callbacks cross threads, and they publish into the guarded state
 * described above.
 */
final class ChatTurnController {

    interface Host {
        void append(Kind kind, String text);

        void closeAssistantSegment(boolean offerCopy);

        boolean isTranscriptAtLineStart();

        void showUsage(ChatUsage usage, boolean pending);

        void showWorkingSeconds(long seconds);

        void setTurnRunning(boolean running);

        void finishTurnAttachments();

        void focusComposer();
    }

    record StartRequest(
            ChatProvider provider,
            McpServerController server,
            String prompt,
            String model,
            String reasoningEffort,
            boolean showReasoning,
            boolean assistantWrites,
            List<ChatAttachment> attachments,
            int droppedAttachments) {
        StartRequest {
            attachments = List.copyOf(attachments == null ? List.of() : attachments);
        }
    }

    private record Chunk(Kind kind, String text) {}

    private final Host host;
    private final ChatHistory history = new ChatHistory();
    private final Deque<Chunk> queue = new ArrayDeque<>();
    private final StringBuilder assistantText = new StringBuilder();
    private final Timer flushTimer;
    private final Timer workingTimer;

    private volatile ChatProcess process;
    private volatile Integer completedExit;
    private volatile ChatUsage finalUsage;
    private volatile ChatUsage liveUsage;
    private volatile String pendingNotice;

    private int turnSequence;
    private int activeTurn;
    private boolean cancelRequested;
    private boolean stopped;
    private boolean showReasoning;
    private String activeProviderId;
    private long turnStartedAt;

    private McpServerController.AssistantCredential credential;
    private McpServerController credentialServer;
    private long nextCredentialRenewal;

    ChatTurnController(Host host) {
        this.host = java.util.Objects.requireNonNull(host, "host");
        flushTimer = new Timer(40, ignored -> drainQueue());
        workingTimer = new Timer(1_000, ignored -> tickWorking());
    }

    boolean isRunning() {
        return activeTurn != 0;
    }

    String sessionId(String providerId) {
        return history.sessionId(providerId);
    }

    void clearConversation() {
        if (!isRunning()) {
            history.clear();
        }
    }

    /** Start a turn from the EDT; provider launch and streaming continue on daemon workers. */
    void start(StartRequest request) {
        if (isRunning()) {
            return;
        }

        String providerId = request.provider().id();
        String handoff = history.handoffFor(providerId);
        String resume = history.sessionId(providerId);
        history.addUser(providerId, request.prompt());

        if (!host.isTranscriptAtLineStart()) {
            host.append(Kind.SYSTEM, "\n");
        }
        host.append(Kind.USER, "> " + request.prompt() + "\n");
        if (request.droppedAttachments() > 0) {
            host.append(
                    Kind.SYSTEM,
                    "[note] "
                            + request.droppedAttachments()
                            + " attachment(s) were not referenced in your message and were not"
                            + " sent.\n");
        }

        completedExit = null;
        finalUsage = null;
        liveUsage = null;
        pendingNotice = null;
        process = null;
        int turn = ++turnSequence;
        activeTurn = turn;
        activeProviderId = providerId;
        assistantText.setLength(0);
        cancelRequested = false;
        stopped = false;
        showReasoning = request.showReasoning();
        turnStartedAt = System.currentTimeMillis();
        host.showUsage(null, true);
        host.showWorkingSeconds(0);
        host.setTurnRunning(true);
        flushTimer.start();
        workingTimer.start();

        String identity = chatIdentity(providerId, resume, turn);
        Thread launcher =
                new Thread(
                        () -> launch(request, turn, resume, handoff, identity),
                        "protege-chat-launch");
        launcher.setDaemon(true);
        launcher.start();
    }

    private void launch(
            StartRequest request, int turn, String resume, String handoff, String identity) {
        McpServerController.AssistantCredential issued = null;
        try {
            McpBoot.ensureStarted(request.server());
            issued =
                    request.server()
                            .issueAssistantCredential(
                                    request.provider().id(), identity, request.assistantWrites());
            McpEndpoint endpoint =
                    new McpEndpoint(request.server().getEndpointUrl(), issued.token());
            ChatRequest chatRequest =
                    new ChatRequest(
                            request.model(),
                            request.prompt(),
                            resume,
                            endpoint,
                            request.attachments(),
                            request.showReasoning(),
                            handoff,
                            request.reasoningEffort());
            ChatProcess started =
                    request.provider()
                            .startTurn(chatRequest, providerListener(request.provider().id()));
            McpServerController.AssistantCredential published = issued;
            SwingUtilities.invokeLater(
                    () -> publishProcess(turn, started, request.server(), published));
            issued = null;
        } catch (Exception ex) {
            if (issued != null) {
                revokeCredentialAsync(request.server(), issued);
            }
            String message = ex.getMessage();
            enqueue(
                    Kind.ERROR,
                    "Could not start "
                            + request.provider().displayName()
                            + ": "
                            + (message == null ? ex.getClass().getSimpleName() : message)
                            + "\n");
            completedExit = -1;
        }
    }

    /** EDT: adopts the process only while the turn that launched it is still active. */
    private void publishProcess(
            int turn,
            ChatProcess started,
            McpServerController server,
            McpServerController.AssistantCredential issued) {
        if (turn != activeTurn) {
            started.cancel();
            revokeCredentialAsync(server, issued);
            return;
        }
        process = started;
        if (cancelRequested) {
            started.cancel();
            revokeCredentialAsync(server, issued);
        } else {
            credential = issued;
            credentialServer = server;
            nextCredentialRenewal = System.currentTimeMillis() + 60_000L;
        }
    }

    /** Request cancellation from the EDT without blocking it on provider shutdown. */
    void stop() {
        if (!isRunning()) {
            return;
        }
        stopped = true;
        revokeActiveCredential();
        ChatProcess current = process;
        if (current == null) {
            cancelRequested = true;
            enqueue(Kind.SYSTEM, "\n[stopping…]\n");
        } else {
            enqueue(Kind.SYSTEM, "\n[stopped]\n");
            current.cancel();
        }
    }

    private void tickWorking() {
        long now = System.currentTimeMillis();
        host.showWorkingSeconds((now - turnStartedAt) / 1_000L);
        McpServerController.AssistantCredential current = credential;
        if (current == null || now < nextCredentialRenewal) {
            return;
        }
        McpServerController server = credentialServer;
        if (server != null && server.renewAssistantCredential(current.token())) {
            nextCredentialRenewal = now + 60_000L;
            return;
        }

        revokeActiveCredential();
        stopped = true;
        enqueue(
                Kind.ERROR,
                "\nThe Assistant MCP credential expired or its server restarted; "
                        + "start a new turn.\n");
        ChatProcess currentProcess = process;
        if (currentProcess != null) {
            currentProcess.cancel();
        }
    }

    private ChatListener providerListener(String providerId) {
        return new ChatListener() {
            @Override
            public void onSessionId(String id) {
                history.setSessionId(providerId, id);
            }

            @Override
            public void onAssistantMessageStart() {
                if (assistantText.length() > 0) {
                    assistantText.append("\n\n");
                }
                enqueue(Kind.ASSISTANT_START, "");
            }

            @Override
            public void onAssistantText(String text) {
                assistantText.append(text);
                enqueue(Kind.ASSISTANT, text);
            }

            @Override
            public void onThinking(String text) {
                enqueue(Kind.THINKING, text);
            }

            @Override
            public void onToolActivity(String summary) {
                enqueue(Kind.TOOL, "  ⚙ " + summary + "\n");
            }

            @Override
            public void onUsage(ChatUsage usage) {
                liveUsage = usage;
            }

            @Override
            public void onResult(ChatUsage usage) {
                finalUsage = usage;
            }

            @Override
            public void onError(String message) {
                enqueue(Kind.ERROR, "\n" + message + "\n");
            }

            @Override
            public void onNotice(String message) {
                pendingNotice = message;
            }

            @Override
            public void onComplete(int exitCode) {
                completedExit = exitCode;
            }
        };
    }

    private void enqueue(Kind kind, String text) {
        synchronized (queue) {
            queue.add(new Chunk(kind, text));
        }
    }

    /** EDT: batches assistant deltas and finalizes only after every queued event is rendered. */
    private void drainQueue() {
        StringBuilder assistantBatch = null;
        Chunk chunk;
        while ((chunk = poll()) != null) {
            if (chunk.kind() == Kind.ASSISTANT) {
                if (assistantBatch == null) {
                    assistantBatch = new StringBuilder();
                }
                assistantBatch.append(chunk.text());
                continue;
            }
            if (chunk.kind() == Kind.THINKING && !showReasoning) {
                continue;
            }
            if (assistantBatch != null) {
                host.append(Kind.ASSISTANT, assistantBatch.toString());
                assistantBatch = null;
            }
            host.append(chunk.kind(), chunk.text());
        }
        if (assistantBatch != null) {
            host.append(Kind.ASSISTANT, assistantBatch.toString());
        }
        ChatUsage usage = liveUsage;
        if (usage != null) {
            host.showUsage(usage, false);
        }
        Integer exit = completedExit;
        if (exit != null && isQueueEmpty()) {
            completedExit = null;
            finalizeTurn(exit);
        }
    }

    private Chunk poll() {
        synchronized (queue) {
            return queue.poll();
        }
    }

    private boolean isQueueEmpty() {
        synchronized (queue) {
            return queue.isEmpty();
        }
    }

    private void finalizeTurn(int exit) {
        host.closeAssistantSegment(!stopped);
        String reply = assistantText.toString();
        if (isConversationHistory(exit, stopped, !reply.isBlank()) && activeProviderId != null) {
            history.addAssistant(activeProviderId, reply);
            history.markSynced(activeProviderId);
        }
        activeProviderId = null;
        assistantText.setLength(0);
        revokeActiveCredential();
        process = null;
        activeTurn = 0;
        cancelRequested = false;
        host.finishTurnAttachments();
        flushTimer.stop();
        workingTimer.stop();
        host.showWorkingSeconds(-1);
        if (!host.isTranscriptAtLineStart()) {
            host.append(Kind.SYSTEM, "\n");
        }
        renderPendingNotice();
        if (finalUsage != null) {
            host.showUsage(finalUsage, false);
        }
        host.setTurnRunning(false);
        host.focusComposer();
    }

    private void renderPendingNotice() {
        String notice = pendingNotice;
        pendingNotice = null;
        if (notice != null && !notice.isBlank()) {
            host.append(Kind.SYSTEM, notice.strip() + "\n");
        }
    }

    /** Stop timers, revoke credentials and cancel the active process; must run on the EDT. */
    void dispose() {
        if (flushTimer.isRunning()) {
            drainQueue();
        }
        flushTimer.stop();
        workingTimer.stop();
        activeTurn = 0;
        revokeActiveCredential();
        ChatProcess current = process;
        if (current != null) {
            current.cancel();
            process = null;
        }
    }

    private void revokeActiveCredential() {
        McpServerController.AssistantCredential current = credential;
        McpServerController server = credentialServer;
        credential = null;
        credentialServer = null;
        nextCredentialRenewal = 0L;
        if (current != null) {
            revokeCredentialAsync(server, current);
        }
    }

    private static void revokeCredentialAsync(
            McpServerController server, McpServerController.AssistantCredential credential) {
        if (server == null || credential == null) {
            return;
        }
        Thread revoker =
                new Thread(
                        () -> server.revokeAssistantCredential(credential),
                        "protege-chat-revoke-credential");
        revoker.setDaemon(true);
        revoker.start();
    }

    static String chatIdentity(String provider, String session, int turn) {
        if (session == null || session.isBlank()) {
            return "turn-" + turn;
        }
        UUID digest =
                UUID.nameUUIDFromBytes(
                        (provider + "\0" + session).getBytes(StandardCharsets.UTF_8));
        return "session-" + digest;
    }

    static boolean isConversationHistory(int exit, boolean stopped, boolean replied) {
        return exit == 0 && !stopped && replied;
    }
}
