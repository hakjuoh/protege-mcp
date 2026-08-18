package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;
import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.tools.ToolArgException;
import io.github.hakjuoh.protege_mcp.tools.ToolContext;
import io.github.hakjuoh.protege_mcp.ui.ChatTranscriptPane.Kind;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** State-transition tests for the turn lifecycle extracted from {@link ChatView}. */
class ChatTurnControllerTest {

    @Test
    void chatIdentityIsOpaqueStableAndTurnBoundWhenNew() {
        String first = ChatTurnController.chatIdentity("codex", "provider-secret-session", 1);
        String resumed = ChatTurnController.chatIdentity("codex", "provider-secret-session", 99);
        assertEquals(first, resumed);
        assertTrue(first.startsWith("session-"));
        assertFalse(first.contains("provider-secret-session"));
        assertEquals("turn-7", ChatTurnController.chatIdentity("claude", null, 7));
    }

    @Test
    void onlyACompletedTurnThatAnsweredBecomesConversationHistory() {
        assertTrue(ChatTurnController.isConversationHistory(0, false, true));
        assertFalse(ChatTurnController.isConversationHistory(0, false, false));
        assertFalse(ChatTurnController.isConversationHistory(1, false, true));
        assertFalse(ChatTurnController.isConversationHistory(0, true, true));
    }

    @Test
    void providerCallbacksOwnStreamingSessionUsageAndCompletionState() throws Exception {
        RecordingHost host = new RecordingHost();
        ChatTurnController controller = new ChatTurnController(host);
        setField(controller, "activeProviderId", "codex");
        setField(controller, "activeTurn", 1);
        ChatListener listener = listener(controller, "codex");
        ChatUsage live = new ChatUsage(5, 6, 0, null);
        ChatUsage result = new ChatUsage(7, 8, 0, null);

        listener.onSessionId("session-123");
        listener.onAssistantMessageStart();
        listener.onAssistantText("one.");
        listener.onAssistantMessageStart();
        listener.onAssistantText("Two.");
        listener.onUsage(live);
        listener.onResult(result);
        listener.onComplete(0);
        drain(controller);

        assertEquals("session-123", controller.sessionId("codex"));
        assertEquals("one.\n\nTwo.", host.renderedAssistantConversation());
        assertSame(result, host.lastUsage);
        assertFalse(controller.isRunning());
        assertTrue(host.finishedAttachments);
        assertTrue(host.focused);
    }

    @Test
    void hiddenReasoningCannotEraseAssistantMessageBoundaries() throws Exception {
        RecordingHost host = new RecordingHost();
        ChatTurnController controller = new ChatTurnController(host);
        setField(controller, "showReasoning", false);
        ChatListener listener = listener(controller, "codex");
        listener.onAssistantMessageStart();
        listener.onAssistantText("one.");
        listener.onThinking("hidden");
        listener.onAssistantMessageStart();
        listener.onAssistantText("Two.");

        drain(controller);

        assertEquals("one.\n\nTwo.", host.renderedAssistantConversation());
        assertEquals("", host.rendered(Kind.THINKING));
    }

    @Test
    void toolErrorsAndNoticesKeepTheirRenderingSemantics() throws Exception {
        RecordingHost host = new RecordingHost();
        ChatTurnController controller = new ChatTurnController(host);
        ChatListener listener = listener(controller, "codex");
        listener.onToolActivity("create_class");
        listener.onError("boom");
        listener.onNotice("[note] effort refused");

        drain(controller);
        invoke(controller, "renderPendingNotice");

        assertEquals("  ⚙ create_class\n", host.rendered(Kind.TOOL));
        assertEquals("\nboom\n", host.rendered(Kind.ERROR));
        assertEquals("[note] effort refused\n", host.rendered(Kind.SYSTEM));
        assertNull(getField(controller, "pendingNotice"));
    }

    @Test
    void stopBeforeProcessPublicationRecordsCancellationIntent() throws Exception {
        RecordingHost host = new RecordingHost();
        ChatTurnController controller = new ChatTurnController(host);
        setField(controller, "activeTurn", 1);

        onEdt(
                () -> {
                    controller.stop();
                    return null;
                });
        drain(controller);

        assertTrue((boolean) getField(controller, "cancelRequested"));
        assertTrue((boolean) getField(controller, "stopped"));
        assertEquals("\n[stopping…]\n", host.rendered(Kind.SYSTEM));
    }

    @Test
    void aStaleProcessIsCancelledInsteadOfBeingPublished() throws Exception {
        RecordingHost host = new RecordingHost();
        ChatTurnController controller = new ChatTurnController(host);
        setField(controller, "activeTurn", 2);
        StubOsProcess osProcess = new StubOsProcess();
        ChatProcess process = chatProcess(osProcess);

        Method publish =
                ChatTurnController.class.getDeclaredMethod(
                        "publishProcess",
                        int.class,
                        ChatProcess.class,
                        McpServerController.class,
                        McpServerController.AssistantCredential.class);
        publish.setAccessible(true);
        onEdt(
                () -> {
                    publish.invoke(controller, 1, process, null, null);
                    return null;
                });

        assertTrue(osProcess.destroyed);
        assertNull(getField(controller, "process"));
    }

    @Test
    void aFailedRenewalRevokesAndFencesTheTurnsGrant() throws Exception {
        McpServerController server = new McpServerController(new OntologyAccess(null));
        OAuthStore store = new OAuthStore(() -> null, () -> null, ignored -> {});
        ToolContext context = new ToolContext(null, server);
        setControllerField(server, "oauthStore", store);
        setControllerField(server, "toolContext", context);
        setControllerRunning(server, true);
        McpServerController.AssistantCredential credential =
                server.issueAssistantCredential("codex", "turn-1", true);
        setControllerRunning(server, false);

        ChatTurnController controller = new ChatTurnController(new RecordingHost());
        setField(controller, "turnStartedAt", System.currentTimeMillis());
        setField(controller, "credential", credential);
        setField(controller, "credentialServer", server);
        setField(controller, "nextCredentialRenewal", 0L);
        invoke(controller, "tickWorking");

        assertNull(getField(controller, "credential"));
        assertTrue((boolean) getField(controller, "stopped"));
        boolean fenced = false;
        for (int attempt = 0; attempt < 200 && !fenced; attempt++) {
            try {
                context.executions().acquire(credential.principal()).close();
                Thread.sleep(10);
            } catch (ToolArgException refused) {
                fenced = true;
            }
        }
        assertTrue(fenced);
    }

    private static ChatListener listener(ChatTurnController controller, String provider)
            throws Exception {
        Method method =
                ChatTurnController.class.getDeclaredMethod("providerListener", String.class);
        method.setAccessible(true);
        return (ChatListener) method.invoke(controller, provider);
    }

    private static void drain(ChatTurnController controller) throws Exception {
        invoke(controller, "drainQueue");
    }

    private static Object invoke(ChatTurnController controller, String methodName)
            throws Exception {
        Method method = ChatTurnController.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return onEdt(() -> method.invoke(controller));
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        result.set(action.call());
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                });
        if (failure.get() instanceof Exception exception) {
            throw exception;
        }
        if (failure.get() instanceof Error error) {
            throw error;
        }
        return result.get();
    }

    private static void setField(ChatTurnController controller, String name, Object value)
            throws Exception {
        Field field = ChatTurnController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private static Object getField(ChatTurnController controller, String name) throws Exception {
        Field field = ChatTurnController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private static void setControllerField(
            McpServerController controller, String name, Object value) throws Exception {
        Field field = McpServerController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private static void setControllerRunning(McpServerController controller, boolean running)
            throws Exception {
        Field field = McpServerController.class.getDeclaredField("running");
        field.setAccessible(true);
        field.setBoolean(controller, running);
    }

    private static ChatProcess chatProcess(Process process) throws Exception {
        Constructor<ChatProcess> constructor =
                ChatProcess.class.getDeclaredConstructor(Process.class, Thread.class);
        constructor.setAccessible(true);
        return constructor.newInstance(process, null);
    }

    private static final class StubOsProcess extends Process {
        private boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    private static final class RecordingHost implements ChatTurnController.Host {
        private final List<Rendered> output = new ArrayList<>();
        private ChatUsage lastUsage;
        private boolean finishedAttachments;
        private boolean focused;

        @Override
        public void append(Kind kind, String text) {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            output.add(new Rendered(kind, text));
        }

        @Override
        public void closeAssistantSegment(boolean offerCopy) {
            assertEdt();
        }

        @Override
        public boolean isTranscriptAtLineStart() {
            assertEdt();
            return true;
        }

        @Override
        public void showUsage(ChatUsage usage, boolean pending) {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            if (!pending) {
                lastUsage = usage;
            }
        }

        @Override
        public void showWorkingSeconds(long seconds) {
            assertEdt();
        }

        @Override
        public void setTurnRunning(boolean running) {
            assertEdt();
        }

        @Override
        public void finishTurnAttachments() {
            assertEdt();
            finishedAttachments = true;
        }

        @Override
        public void focusComposer() {
            assertEdt();
            focused = true;
        }

        private static void assertEdt() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
        }

        String rendered(Kind kind) {
            return output.stream()
                    .filter(item -> item.kind() == kind)
                    .map(Rendered::text)
                    .reduce("", String::concat);
        }

        String renderedAssistantConversation() {
            StringBuilder rendered = new StringBuilder();
            boolean hasAssistantText = false;
            for (Rendered item : output) {
                if (item.kind() == Kind.ASSISTANT_START && hasAssistantText) {
                    rendered.append("\n\n");
                } else if (item.kind() == Kind.ASSISTANT) {
                    rendered.append(item.text());
                    hasAssistantText = true;
                }
            }
            return rendered.toString();
        }
    }

    private record Rendered(Kind kind, String text) {}
}
