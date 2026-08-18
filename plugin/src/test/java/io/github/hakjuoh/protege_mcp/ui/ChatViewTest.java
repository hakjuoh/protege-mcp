package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.protege_mcp.chat.ChatUsage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Headless tests for the UI-only responsibilities left in {@link ChatView}. */
class ChatViewTest {

    @Test
    void formatUsageDelegatesToTheSharedChatFormatter() throws Exception {
        Method method = ChatView.class.getDeclaredMethod("formatUsage", ChatUsage.class);
        method.setAccessible(true);

        assertEquals(
                "tokens: 100 in / 40 out   ", method.invoke(null, new ChatUsage(100, 40, 0, null)));
        assertEquals("tokens: ? in / ? out   ", method.invoke(null, ChatUsage.unknown()));
        assertTrue(
                ((String) method.invoke(null, new ChatUsage(100, 40, 25, 0.12345)))
                        .contains("$0.1235"));
        assertFalse(
                ((String) method.invoke(null, new ChatUsage(100, 40, 0, null))).contains("cached"));
    }

    @Test
    void attachmentControllerWiringReportsErrorsInTranscript() throws Exception {
        onEdt(
                () -> {
                    ChatTranscriptPane transcript = new ChatTranscriptPane(() -> false);
                    ChatView view = bareInstance();
                    setField(view, "transcript", transcript);
                    ChatAttachmentController controller =
                            view.buildAttachmentController(new javax.swing.JTextArea());
                    try {
                        assertFalse(
                                controller.attachFiles(
                                        List.of(
                                                new java.io.File(
                                                        "missing-attachment-for-chat-view-test"))));
                        assertTrue(
                                transcript.getText().contains("Cannot attach non-file path:"));
                    } finally {
                        controller.dispose();
                    }
                    return null;
                });
    }

    @Test
    void turnControllerHostAdapterUpdatesSwingComponentsOnTheEdt() throws Exception {
        onEdt(
                () -> {
                    ChatView view = bareInstance();
                    ChatTranscriptPane transcript = new ChatTranscriptPane(() -> false);
                    javax.swing.JLabel usage = new javax.swing.JLabel();
                    javax.swing.JLabel working = new javax.swing.JLabel();
                    setField(view, "transcript", transcript);
                    setField(view, "usageLabel", usage);
                    setField(view, "workingLabel", working);

                    Method build = ChatView.class.getDeclaredMethod("buildTurnController");
                    build.setAccessible(true);
                    ChatTurnController controller = (ChatTurnController) build.invoke(view);
                    Field hostField = ChatTurnController.class.getDeclaredField("host");
                    hostField.setAccessible(true);
                    ChatTurnController.Host host =
                            (ChatTurnController.Host) hostField.get(controller);

                    host.append(ChatTranscriptPane.Kind.SYSTEM, "adapter\n");
                    host.showUsage(new ChatUsage(3, 2, 0, null), false);
                    host.showWorkingSeconds(4);

                    assertTrue(transcript.getText().contains("adapter"));
                    assertEquals("tokens: 3 in / 2 out   ", usage.getText());
                    assertTrue(working.getText().contains("4s"));
                    controller.dispose();
                    return null;
                });
    }

    private static ChatView bareInstance() throws Exception {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return (ChatView) allocate.invoke(unsafe, ChatView.class);
    }

    private static void setField(ChatView view, String name, Object value) throws Exception {
        Field field = ChatView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
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
}
