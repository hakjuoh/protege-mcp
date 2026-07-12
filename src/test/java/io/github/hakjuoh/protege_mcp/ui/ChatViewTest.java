package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

/**
 * Method-level tests for the pure/testable helpers of {@link ChatView}.
 *
 * <p>{@link ChatView} extends a Swing/Protégé view component whose constructor and most methods need a
 * display and the live ProtégE runtime, so those are out of scope (headless surefire would throw
 * {@code HeadlessException}). This suite reaches the private static helpers by reflection and the small
 * set of instance methods that touch only plain collection fields ({@code queue},
 * {@code pendingAttachments}, and the streaming {@code volatile} fields). For those, an instance is
 * created WITHOUT running any Swing constructor (via {@code sun.misc.Unsafe.allocateInstance}), and only
 * the specific fields the method under test reads/writes are populated by reflection — no top-level AWT
 * component is ever constructed. (Peerless lightweights — {@code JTextPane}, {@code JButton} — are fine
 * headless and are used by the copy-affordance tests; only windows/toolkit resources are off-limits.)
 */
class ChatViewTest {

    // ------------------------------------------------------------------ reflection plumbing

    private static Method staticMethod(String name, Class<?>... params) throws Exception {
        Method m = ChatView.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    /** Allocate a ChatView without invoking any constructor (so no Swing/AWT init runs). */
    @SuppressWarnings("unchecked")
    private static ChatView bareInstance() throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return (ChatView) allocate.invoke(unsafe, ChatView.class);
    }

    private static void setField(ChatView v, String name, Object value) throws Exception {
        Field field = ChatView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(v, value);
    }

    private static Object getField(ChatView v, String name) throws Exception {
        Field field = ChatView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(v);
    }

    /** A ChatView with the collection fields used by enqueue/poll/uiListener/activeAttachments wired up. */
    private static ChatView wiredInstance() throws Exception {
        ChatView v = bareInstance();
        setField(v, "queue", new ArrayDeque<>());
        setField(v, "pendingAttachments", new ArrayList<ChatAttachment>());
        return v;
    }

    /** Minimal ChatProvider fake for id-based helpers; other methods are unused here. */
    private static ChatProvider provider(String id) {
        return new ChatProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return "Prov-" + id;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }

            @Override
            public String defaultModel() {
                return "";
            }

            @Override
            public ChatProcess startTurn(ChatRequest request, ChatListener listener) throws IOException {
                throw new UnsupportedOperationException();
            }
        };
    }

    // ------------------------------------------------------------------ isImageFile

    @Test
    void isImageFileTrueForEveryKnownExtension() throws Exception {
        Method m = staticMethod("isImageFile", File.class);
        for (String ext : new String[] { "png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff" }) {
            assertTrue((boolean) m.invoke(null, new File("pic." + ext)),
                    "extension ." + ext + " should be an image file");
        }
    }

    @Test
    void isImageFileIsCaseInsensitive() throws Exception {
        Method m = staticMethod("isImageFile", File.class);
        assertTrue((boolean) m.invoke(null, new File("PIC.PNG")), "uppercase .PNG should match");
        assertTrue((boolean) m.invoke(null, new File("Photo.JpEg")), "mixed-case .JpEg should match");
    }

    @Test
    void isImageFileFalseForNonImageExtensions() throws Exception {
        Method m = staticMethod("isImageFile", File.class);
        assertFalse((boolean) m.invoke(null, new File("notes.txt")), ".txt is not an image");
        assertFalse((boolean) m.invoke(null, new File("doc.pdf")), ".pdf is not an image");
        assertFalse((boolean) m.invoke(null, new File("noextension")), "no extension is not an image");
    }

    // ------------------------------------------------------------------ imageMediaType

    @Test
    void imageMediaTypeMapsEachExtension() throws Exception {
        Method m = staticMethod("imageMediaType", File.class);
        assertEquals("image/png", m.invoke(null, new File("a.png")));
        assertEquals("image/jpeg", m.invoke(null, new File("a.jpg")));
        assertEquals("image/jpeg", m.invoke(null, new File("a.jpeg")));
        assertEquals("image/gif", m.invoke(null, new File("a.gif")));
        assertEquals("image/webp", m.invoke(null, new File("a.webp")));
        assertEquals("image/bmp", m.invoke(null, new File("a.bmp")));
        assertEquals("image/tiff", m.invoke(null, new File("a.tif")));
        assertEquals("image/tiff", m.invoke(null, new File("a.tiff")));
    }

    @Test
    void imageMediaTypeFallsBackToWildcardForUnknown() throws Exception {
        Method m = staticMethod("imageMediaType", File.class);
        assertEquals("image/*", m.invoke(null, new File("a.xyz")), "unknown extension -> image/*");
        assertEquals("image/*", m.invoke(null, new File("noext")), "no extension -> image/*");
    }

    @Test
    void imageMediaTypeIsCaseInsensitive() throws Exception {
        Method m = staticMethod("imageMediaType", File.class);
        assertEquals("image/png", m.invoke(null, new File("A.PNG")), "uppercase .PNG -> image/png");
    }

    // ------------------------------------------------------------------ causeMessage

    @Test
    void causeMessagePrefersCauseMessage() throws Exception {
        Method m = staticMethod("causeMessage", Exception.class);
        Exception ex = new RuntimeException("outer", new IllegalStateException("inner cause"));
        assertEquals("inner cause", m.invoke(null, ex), "should use the cause's message when present");
    }

    @Test
    void causeMessageUsesOwnMessageWhenNoCause() throws Exception {
        Method m = staticMethod("causeMessage", Exception.class);
        Exception ex = new IOException("io failure");
        assertEquals("io failure", m.invoke(null, ex), "no cause -> own message");
    }

    @Test
    void causeMessageFallsBackToClassNameWhenMessageNull() throws Exception {
        Method m = staticMethod("causeMessage", Exception.class);
        Exception ex = new IllegalStateException();   // null message, no cause
        assertEquals("IllegalStateException", m.invoke(null, ex),
                "null message and no cause -> simple class name");
    }

    @Test
    void causeMessageFallsBackToCauseClassNameWhenCauseMessageNull() throws Exception {
        Method m = staticMethod("causeMessage", Exception.class);
        Exception ex = new RuntimeException("outer", new NullPointerException());
        assertEquals("NullPointerException", m.invoke(null, ex),
                "cause present but its message is null -> cause simple class name");
    }

    // ------------------------------------------------------------------ formatUsage

    @Test
    void formatUsageRendersBasicCounts() throws Exception {
        Method m = staticMethod("formatUsage", ChatUsage.class);
        String s = (String) m.invoke(null, new ChatUsage(100, 40, 0, null));
        assertEquals("tokens: 100 in / 40 out   ", s, "basic in/out with trailing 3 spaces");
    }

    @Test
    void formatUsageShowsQuestionMarkForNegativeTokens() throws Exception {
        Method m = staticMethod("formatUsage", ChatUsage.class);
        String s = (String) m.invoke(null, ChatUsage.unknown());
        assertEquals("tokens: ? in / ? out   ", s, "negative in/out render as '?'");
    }

    @Test
    void formatUsageAppendsCachedWhenPositive() throws Exception {
        Method m = staticMethod("formatUsage", ChatUsage.class);
        String s = (String) m.invoke(null, new ChatUsage(100, 40, 25, null));
        assertEquals("tokens: 100 in (25 cached) / 40 out   ", s, "positive cached tokens are shown");
    }

    @Test
    void formatUsageOmitsCachedWhenZeroOrNegative() throws Exception {
        Method m = staticMethod("formatUsage", ChatUsage.class);
        assertFalse(((String) m.invoke(null, new ChatUsage(100, 40, 0, null))).contains("cached"),
                "zero cached is omitted");
        assertFalse(((String) m.invoke(null, new ChatUsage(100, 40, -1, null))).contains("cached"),
                "negative cached is omitted");
    }

    @Test
    void formatUsageAppendsCostWithFourDecimals() throws Exception {
        Method m = staticMethod("formatUsage", ChatUsage.class);
        String s = (String) m.invoke(null, new ChatUsage(100, 40, 0, 0.12345));
        assertTrue(s.contains("$0.1235"), "cost is formatted to 4 decimals (rounded), got: " + s);
        assertTrue(s.endsWith("   "), "still ends with three trailing spaces");
    }

    @Test
    void formatUsageOmitsCostWhenNull() throws Exception {
        Method m = staticMethod("formatUsage", ChatUsage.class);
        String s = (String) m.invoke(null, new ChatUsage(100, 40, 0, null));
        assertFalse(s.contains("$"), "null cost omits the dollar segment");
    }

    // ------------------------------------------------------------------ styleFor

    private Object kind(String name) throws Exception {
        Class<?> kindClass = Class.forName("io.github.hakjuoh.protege_mcp.ui.ChatView$Kind");
        for (Object c : kindClass.getEnumConstants()) {
            if (c.toString().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no Kind " + name);
    }

    private SimpleAttributeSet styleFor(String kindName) throws Exception {
        Class<?> kindClass = Class.forName("io.github.hakjuoh.protege_mcp.ui.ChatView$Kind");
        Method m = ChatView.class.getDeclaredMethod("styleFor", kindClass);
        m.setAccessible(true);
        return (SimpleAttributeSet) m.invoke(null, kind(kindName));
    }

    @Test
    void styleForUserIsBoldBlue() throws Exception {
        SimpleAttributeSet a = styleFor("USER");
        assertTrue(StyleConstants.isBold(a), "USER is bold");
        assertEquals(new Color(0x1A4F8B), StyleConstants.getForeground(a), "USER is blue");
    }

    @Test
    void styleForAssistantHasNoOverrides() throws Exception {
        // ASSISTANT text never reaches styleFor: append() routes it to appendAssistant(), and
        // ChatMarkdown owns the assistant styling (pinned in ChatMarkdownTest). The switch case was
        // removed as dead code; this pins that it stays gone.
        SimpleAttributeSet a = styleFor("ASSISTANT");
        assertFalse(a.isDefined(StyleConstants.Foreground), "ASSISTANT styling lives in ChatMarkdown");
        assertFalse(StyleConstants.isBold(a), "ASSISTANT is not bold");
    }

    @Test
    void styleForToolIsItalicGreen() throws Exception {
        SimpleAttributeSet a = styleFor("TOOL");
        assertTrue(StyleConstants.isItalic(a), "TOOL is italic");
        assertEquals(new Color(0x507030), StyleConstants.getForeground(a), "TOOL is green");
    }

    @Test
    void styleForThinkingIsItalicGray() throws Exception {
        SimpleAttributeSet a = styleFor("THINKING");
        assertTrue(StyleConstants.isItalic(a), "THINKING is italic");
        assertEquals(new Color(0x888888), StyleConstants.getForeground(a), "THINKING is gray");
    }

    @Test
    void styleForErrorIsRed() throws Exception {
        SimpleAttributeSet a = styleFor("ERROR");
        assertEquals(new Color(0xB00020), StyleConstants.getForeground(a), "ERROR is red");
    }

    @Test
    void styleForSystemIsMutedGray() throws Exception {
        SimpleAttributeSet a = styleFor("SYSTEM");
        assertEquals(new Color(0x666666), StyleConstants.getForeground(a), "SYSTEM is muted gray");
    }

    // ------------------------------------------------------------------ modelPrefKey

    @Test
    void modelPrefKeyForCodexProvider() throws Exception {
        Method m = staticMethod("modelPrefKey", ChatProvider.class);
        assertEquals(McpConfig.KEY_CHAT_MODEL_CODEX, m.invoke(null, provider("codex")),
                "codex id -> codex model key");
    }

    @Test
    void modelPrefKeyForNonCodexProviderIsClaude() throws Exception {
        Method m = staticMethod("modelPrefKey", ChatProvider.class);
        assertEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE, m.invoke(null, provider("claude")),
                "non-codex id -> claude model key");
        assertEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE, m.invoke(null, provider("something-else")),
                "any other id -> claude model key");
    }

    // ------------------------------------------------------------------ shouldAttachPastedText

    private boolean shouldAttach(String text) throws Exception {
        ChatView v = bareInstance();   // reads only constants; no fields needed
        Method m = ChatView.class.getDeclaredMethod("shouldAttachPastedText", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(v, text);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    void shouldAttachPastedTextNullIsFalse() throws Exception {
        assertFalse(shouldAttach(null), "null text is never attached");
    }

    @Test
    void shouldAttachPastedTextEmptyIsFalse() throws Exception {
        assertFalse(shouldAttach(""), "empty text is never attached");
    }

    @Test
    void shouldAttachPastedTextAtOrAboveLengthThresholdIsTrue() throws Exception {
        // PASTED_TEXT_ATTACHMENT_THRESHOLD == 2000; a single long line still attaches.
        assertTrue(shouldAttach(repeat('x', 2000)), "length >= 2000 attaches regardless of line count");
        assertTrue(shouldAttach(repeat('x', 5000)), "well above the length threshold attaches");
    }

    @Test
    void shouldAttachPastedTextBelowLineMinCharsIsFalseEvenWithManyLines() throws Exception {
        // 60 newlines but under PASTED_TEXT_LINE_MIN_CHARS (1500) -> stays inline.
        String manyShortLines = repeat('\n', 60);
        assertTrue(manyShortLines.length() < 1500, "precondition: under the line-min-chars floor");
        assertFalse(shouldAttach(manyShortLines), "many lines but too small stays inline");
    }

    @Test
    void shouldAttachPastedTextManyLinesAndLargeIsTrue() throws Exception {
        // >= 1500 chars AND >= 50 newlines -> compacted. Build 50 lines each 40 chars = ~2050 chars,
        // but keep under 2000? 50*41=2050 > 2000 would also trip the length rule; use padded lines that
        // exceed 1500 yet stay under 2000 while carrying >= 50 newlines.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append(repeat('a', 25)).append('\n');   // 60 * 26 = 1560 chars, 60 newlines
        }
        String text = sb.toString();
        assertTrue(text.length() >= 1500 && text.length() < 2000,
                "precondition: between line-min-chars and length threshold, got " + text.length());
        assertTrue(shouldAttach(text), "large multi-line paste is compacted via the newline rule");
    }

    @Test
    void shouldAttachPastedTextLargeButFewLinesIsFalse() throws Exception {
        // Between 1500 and 2000 chars but fewer than 50 newlines -> not attached (neither rule fires).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(repeat('a', 160)).append('\n');   // ~1610 chars, only 10 newlines
        }
        String text = sb.toString();
        assertTrue(text.length() >= 1500 && text.length() < 2000, "precondition on length");
        assertFalse(shouldAttach(text), "large but few-line paste is not compacted");
    }

    // ------------------------------------------------------------------ enqueue / poll / isQueueEmpty

    @Test
    void enqueueAlwaysAddsAChunkEvenForEmptyText() throws Exception {
        // enqueue() has no null/empty guard (that filtering is done later in append(), on the EDT); every
        // call adds a Chunk to the queue.
        ChatView v = wiredInstance();
        Method enqueue = ChatView.class.getDeclaredMethod("enqueue",
                Class.forName("io.github.hakjuoh.protege_mcp.ui.ChatView$Kind"), String.class);
        enqueue.setAccessible(true);
        enqueue.invoke(v, kind("SYSTEM"), "");
        enqueue.invoke(v, kind("SYSTEM"), "x");
        assertEquals(2, queueOf(v).size(), "enqueue adds a chunk unconditionally, including empty text");
    }

    @Test
    void enqueueThenPollRoundTripsFifo() throws Exception {
        ChatView v = wiredInstance();
        Class<?> kindClass = Class.forName("io.github.hakjuoh.protege_mcp.ui.ChatView$Kind");
        Method enqueue = ChatView.class.getDeclaredMethod("enqueue", kindClass, String.class);
        enqueue.setAccessible(true);
        Method poll = ChatView.class.getDeclaredMethod("poll");
        poll.setAccessible(true);
        Method isEmpty = ChatView.class.getDeclaredMethod("isQueueEmpty");
        isEmpty.setAccessible(true);

        enqueue.invoke(v, kind("ASSISTANT"), "first");
        enqueue.invoke(v, kind("ERROR"), "second");
        assertFalse((boolean) isEmpty.invoke(v), "queue is non-empty after two enqueues");

        Object c1 = poll.invoke(v);
        Object c2 = poll.invoke(v);
        assertNotNull(c1, "first poll returns a chunk");
        assertNotNull(c2, "second poll returns a chunk");
        // Chunk is a record with text() accessor.
        Method text = c1.getClass().getDeclaredMethod("text");
        text.setAccessible(true);
        assertEquals("first", text.invoke(c1), "FIFO order: first enqueued polls first");
        assertEquals("second", text.invoke(c2), "FIFO order: second enqueued polls second");
        assertTrue((boolean) isEmpty.invoke(v), "queue drained after two polls");
    }

    @Test
    void pollReturnsNullWhenQueueEmpty() throws Exception {
        ChatView v = wiredInstance();
        Method poll = ChatView.class.getDeclaredMethod("poll");
        poll.setAccessible(true);
        assertNull(poll.invoke(v), "poll on an empty queue returns null");
    }

    // ------------------------------------------------------------------ uiListener

    @SuppressWarnings("unchecked")
    private static Deque<Object> queueOf(ChatView v) throws Exception {
        return (Deque<Object>) getField(v, "queue");
    }

    @Test
    void uiListenerOnSessionIdSetsVolatileField() throws Exception {
        ChatView v = wiredInstance();
        ChatListener l = invokeUiListener(v);
        l.onSessionId("sess-123");
        assertEquals("sess-123", getField(v, "sessionId"), "onSessionId stores the id");
    }

    @Test
    void uiListenerOnUsageAndOnResultSetDistinctFields() throws Exception {
        ChatView v = wiredInstance();
        ChatListener l = invokeUiListener(v);
        ChatUsage live = new ChatUsage(5, 6, 0, null);
        ChatUsage last = new ChatUsage(7, 8, 0, null);
        l.onUsage(live);
        l.onResult(last);
        assertSame(live, getField(v, "liveUsage"), "onUsage -> liveUsage");
        assertSame(last, getField(v, "lastUsage"), "onResult -> lastUsage");
    }

    @Test
    void uiListenerOnCompleteSetsCompletedExit() throws Exception {
        ChatView v = wiredInstance();
        ChatListener l = invokeUiListener(v);
        l.onComplete(0);
        assertEquals(0, getField(v, "completedExit"), "onComplete stores exit code 0");
        l.onComplete(-1);
        assertEquals(-1, getField(v, "completedExit"), "onComplete overwrites with -1");
    }

    @Test
    void uiListenerOnAssistantTextEnqueuesAssistantChunk() throws Exception {
        ChatView v = wiredInstance();
        ChatListener l = invokeUiListener(v);
        l.onAssistantText("hello");
        Object chunk = queueOf(v).peek();
        assertNotNull(chunk, "assistant text is enqueued");
        assertEquals("hello", chunkText(chunk), "assistant chunk carries the text verbatim");
        assertEquals("ASSISTANT", chunkKind(chunk), "assistant chunk uses Kind.ASSISTANT");
    }

    @Test
    void uiListenerOnToolActivityFormatsSummary() throws Exception {
        ChatView v = wiredInstance();
        ChatListener l = invokeUiListener(v);
        l.onToolActivity("create_class");
        Object chunk = queueOf(v).peek();
        assertNotNull(chunk, "tool activity is enqueued");
        assertEquals("TOOL", chunkKind(chunk), "tool chunk uses Kind.TOOL");
        String t = chunkText(chunk);
        assertTrue(t.contains("create_class"), "summary is embedded, got: " + t);
        assertTrue(t.startsWith("\n"), "tool line starts on a fresh line");
        assertTrue(t.endsWith("\n"), "tool line ends with a newline");
    }

    @Test
    void uiListenerOnErrorFormatsMessage() throws Exception {
        ChatView v = wiredInstance();
        ChatListener l = invokeUiListener(v);
        l.onError("boom");
        Object chunk = queueOf(v).peek();
        assertNotNull(chunk, "error is enqueued");
        assertEquals("ERROR", chunkKind(chunk), "error chunk uses Kind.ERROR");
        String t = chunkText(chunk);
        assertTrue(t.contains("boom"), "error message is carried, got: " + t);
        assertFalse(t.contains("[error]"), "no [error] prefix: the ERROR kind's color already marks it");
        assertTrue(t.startsWith("\n"), "error starts on a fresh line");
        assertTrue(t.endsWith("\n"), "error ends with a newline");
    }

    @Test
    void uiListenerOnThinkingEnqueuesThinkingChunk() throws Exception {
        ChatView v = wiredInstance();
        ChatListener l = invokeUiListener(v);
        l.onThinking("reasoning");
        Object chunk = queueOf(v).peek();
        assertNotNull(chunk, "thinking is always enqueued off-EDT (render decided later)");
        assertEquals("THINKING", chunkKind(chunk), "thinking chunk uses Kind.THINKING");
        assertEquals("reasoning", chunkText(chunk), "thinking carries verbatim text");
    }

    private static ChatListener invokeUiListener(ChatView v) throws Exception {
        Method m = ChatView.class.getDeclaredMethod("uiListener");
        m.setAccessible(true);
        return (ChatListener) m.invoke(v);
    }

    private static String chunkText(Object chunk) throws Exception {
        Method text = chunk.getClass().getDeclaredMethod("text");
        text.setAccessible(true);
        return (String) text.invoke(chunk);
    }

    private static String chunkKind(Object chunk) throws Exception {
        Method k = chunk.getClass().getDeclaredMethod("kind");
        k.setAccessible(true);
        return k.invoke(chunk).toString();
    }

    // ------------------------------------------------------------------ activeAttachments

    @SuppressWarnings("unchecked")
    private static List<ChatAttachment> activeAttachments(ChatView v, String prompt) throws Exception {
        Method m = ChatView.class.getDeclaredMethod("activeAttachments", String.class);
        m.setAccessible(true);
        return (List<ChatAttachment>) m.invoke(v, prompt);
    }

    @Test
    void activeAttachmentsEmptyWhenNonePending() throws Exception {
        ChatView v = wiredInstance();
        List<ChatAttachment> result = activeAttachments(v, "anything at all");
        assertTrue(result.isEmpty(), "no pending attachments -> empty result");
    }

    @Test
    void activeAttachmentsFiltersByPlaceholderPresence() throws Exception {
        ChatView v = wiredInstance();
        ChatAttachment a = ChatAttachment.pastedText("Pasted content #1: 10 chars", "0123456789");
        ChatAttachment b = ChatAttachment.pastedText("Pasted content #2: 5 chars", "abcde");
        @SuppressWarnings("unchecked")
        List<ChatAttachment> pending = (List<ChatAttachment>) getField(v, "pendingAttachments");
        pending.add(a);
        pending.add(b);
        String prompt = "look at " + a.placeholder() + " please";
        List<ChatAttachment> result = activeAttachments(v, prompt);
        assertEquals(1, result.size(), "only the referenced placeholder is active");
        assertSame(a, result.get(0), "the referenced attachment is returned");
    }

    @Test
    void activeAttachmentsReturnsUnmodifiableCopy() throws Exception {
        ChatView v = wiredInstance();
        ChatAttachment a = ChatAttachment.pastedText("Pasted content #1: 4 chars", "data");
        @SuppressWarnings("unchecked")
        List<ChatAttachment> pending = (List<ChatAttachment>) getField(v, "pendingAttachments");
        pending.add(a);
        List<ChatAttachment> result = activeAttachments(v, a.placeholder());
        assertThrows(UnsupportedOperationException.class, () -> result.add(a),
                "result is an unmodifiable copy");
    }

    @Test
    void activeAttachmentsPlaceholderMatchIsCaseSensitive() throws Exception {
        ChatView v = wiredInstance();
        ChatAttachment a = ChatAttachment.pastedText("Pasted content #1: 4 chars", "data");
        @SuppressWarnings("unchecked")
        List<ChatAttachment> pending = (List<ChatAttachment>) getField(v, "pendingAttachments");
        pending.add(a);
        // Upper-cased placeholder text does not substring-match the real placeholder.
        List<ChatAttachment> result = activeAttachments(v, a.placeholder().toUpperCase(java.util.Locale.ROOT));
        assertTrue(result.isEmpty(), "placeholder matching is case-sensitive");
    }

    // ------------------------------------------------------------------ restrict

    @Test
    void restrictAppliesOwnerOnlyFilePermissions() throws Exception {
        Method m = staticMethod("restrict", Path.class, boolean.class);
        Path file = Files.createTempFile("cvperm", ".dat");
        try {
            m.invoke(null, file, false);
            Set<PosixFilePermission> perms;
            try {
                perms = Files.getPosixFilePermissions(file);
            } catch (UnsupportedOperationException nonPosix) {
                return;   // non-POSIX FS: restrict() is a documented no-op; nothing to assert
            }
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ), "owner read set");
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE), "owner write set");
            assertFalse(perms.contains(PosixFilePermission.OWNER_EXECUTE),
                    "file (directory=false) is not executable");
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ), "no group read on a file");
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ), "no others read on a file");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void restrictAppliesOwnerExecuteForDirectory() throws Exception {
        Method m = staticMethod("restrict", Path.class, boolean.class);
        Path dir = Files.createTempDirectory("cvpermdir");
        try {
            m.invoke(null, dir, true);
            Set<PosixFilePermission> perms;
            try {
                perms = Files.getPosixFilePermissions(dir);
            } catch (UnsupportedOperationException nonPosix) {
                return;   // non-POSIX FS: no-op
            }
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ), "owner read set");
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE), "owner write set");
            assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE),
                    "directory (directory=true) is owner-executable (traversable)");
            assertFalse(perms.contains(PosixFilePermission.GROUP_EXECUTE), "no group access on the dir");
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void restrictSwallowsErrorsForMissingPath() throws Exception {
        Method m = staticMethod("restrict", Path.class, boolean.class);
        Path missing = Files.createTempDirectory("cvmissing").resolve("does-not-exist");
        // setPosixFilePermissions on a missing path throws IOException, which restrict() must swallow.
        m.invoke(null, missing, false);   // must not throw
    }

    // ------------------------------------------------------------------ reasoning boundary breaks

    /** A bare view with only the two render-state fields the boundary decision reads. */
    private ChatView renderState(boolean atLineStart, String lastKind) throws Exception {
        ChatView v = bareInstance();
        setField(v, "atTurnStartOfLine", atLineStart);
        setField(v, "lastRenderedKind", lastKind == null ? null : kind(lastKind));
        return v;
    }

    private boolean boundaryBreak(ChatView v, String kindName, String text) throws Exception {
        Class<?> k = Class.forName("io.github.hakjuoh.protege_mcp.ui.ChatView$Kind");
        Method m = ChatView.class.getDeclaredMethod("needsReasoningBoundaryBreak", k, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(v, kind(kindName), text);
    }

    @Test
    void reasoningEnteringMidLineGetsABreak() throws Exception {
        assertTrue(boundaryBreak(renderState(false, "ASSISTANT"), "THINKING", "pondering"));
        assertTrue(boundaryBreak(renderState(false, null), "THINKING", "pondering"));
    }

    @Test
    void reasoningLeavingMidLineGetsABreak() throws Exception {
        assertTrue(boundaryBreak(renderState(false, "THINKING"), "SYSTEM", "[note] x"));
    }

    @Test
    void deltasInsideOneReasoningRunGetNoBreak() throws Exception {
        // Claude streams a block as many small deltas; a break between them would shred the text.
        assertFalse(boundaryBreak(renderState(false, "THINKING"), "THINKING", "more of the same"));
    }

    @Test
    void lineStartOrOwnLeadingNewlineNeedsNoBreak() throws Exception {
        assertFalse(boundaryBreak(renderState(true, "ASSISTANT"), "THINKING", "pondering"));
        assertFalse(boundaryBreak(renderState(false, "THINKING"), "TOOL", "\n  tool line\n"));
    }

    @Test
    void nonReasoningTransitionsGetNoBreak() throws Exception {
        assertFalse(boundaryBreak(renderState(false, "USER"), "SYSTEM", "x"));
        assertFalse(boundaryBreak(renderState(false, "SYSTEM"), "ERROR", "boom"));
    }

    @Test
    void reasoningVisibilityIsFrozenForTheActiveTurn() throws Exception {
        ChatView v = bareInstance();
        javax.swing.JCheckBox toggle = new javax.swing.JCheckBox();
        setField(v, "showThinking", toggle);
        setField(v, "activeTurn", 1);
        Method visible = ChatView.class.getDeclaredMethod("shouldShowReasoning");
        visible.setAccessible(true);

        setField(v, "showReasoningForTurn", true);
        toggle.setSelected(false);
        assertTrue((boolean) visible.invoke(v),
                "unticking mid-turn must not drop reasoning already requested from the CLI");

        setField(v, "showReasoningForTurn", false);
        toggle.setSelected(true);
        assertFalse((boolean) visible.invoke(v),
                "ticking mid-turn applies to the next message, not the current one");

        setField(v, "activeTurn", 0);
        assertTrue((boolean) visible.invoke(v), "between turns the live checkbox is authoritative");
    }

    // ------------------------------------------------------------------ copy-as-Markdown affordance

    /** A bare view wired with just the transcript + segment state the affordance path touches. */
    private static ChatView affordanceInstance(javax.swing.JTextPane pane,
            io.github.hakjuoh.protege_mcp.chat.AssistantSegment segment) throws Exception {
        ChatView v = bareInstance();
        setField(v, "transcript", pane);
        setField(v, "assistantSegment", segment);
        setField(v, "atTurnStartOfLine", false);
        return v;
    }

    private static void closeSegment(ChatView v, boolean offerCopy) throws Exception {
        Method m = ChatView.class.getDeclaredMethod("closeAssistantSegment", boolean.class);
        m.setAccessible(true);
        m.invoke(v, offerCopy);
    }

    @Test
    void finalReplyCloseInsertsTheCopyButtonLine() throws Exception {
        javax.swing.JTextPane pane = new javax.swing.JTextPane();
        io.github.hakjuoh.protege_mcp.chat.AssistantSegment segment =
                new io.github.hakjuoh.protege_mcp.chat.AssistantSegment();
        ChatView v = affordanceInstance(pane, segment);
        segment.appendAndRender(pane.getStyledDocument(), "**reply**", 13);

        closeSegment(v, true);

        javax.swing.text.StyledDocument doc = pane.getStyledDocument();
        String text = doc.getText(0, doc.getLength());
        assertTrue(text.startsWith("reply\n"), "the button sits on its own line, got: " + text);
        assertTrue(text.endsWith("\n"), "the affordance line ends with a newline");
        int buttonPos = text.length() - 2;   // the embedded component's single character
        Object component = StyleConstants.getComponent(
                doc.getCharacterElement(buttonPos).getAttributes());
        assertTrue(component instanceof javax.swing.JButton,
                "the affordance char embeds the copy button");
        assertEquals("**reply**", io.github.hakjuoh.protege_mcp.chat.AssistantSegment.sourceAt(
                doc, buttonPos), "the affordance line itself answers copy-message-as-Markdown");
        assertEquals(Boolean.TRUE, getField(v, "atTurnStartOfLine"),
                "bookkeeping: the affordance leaves the transcript at a line start");
    }

    @Test
    void midTurnCloseTagsTheSourceButAddsNoButton() throws Exception {
        javax.swing.JTextPane pane = new javax.swing.JTextPane();
        io.github.hakjuoh.protege_mcp.chat.AssistantSegment segment =
                new io.github.hakjuoh.protege_mcp.chat.AssistantSegment();
        ChatView v = affordanceInstance(pane, segment);
        segment.appendAndRender(pane.getStyledDocument(), "interim", 13);

        closeSegment(v, false);

        javax.swing.text.StyledDocument doc = pane.getStyledDocument();
        assertEquals("interim", doc.getText(0, doc.getLength()),
                "no button row for a tool-interrupted interim message");
        assertEquals("interim", io.github.hakjuoh.protege_mcp.chat.AssistantSegment.sourceAt(doc, 0),
                "…but its source is still tagged for the context menu");
    }

    @Test
    void closeWithNothingToKeepInsertsNothing() throws Exception {
        javax.swing.JTextPane pane = new javax.swing.JTextPane();
        io.github.hakjuoh.protege_mcp.chat.AssistantSegment segment =
                new io.github.hakjuoh.protege_mcp.chat.AssistantSegment();
        ChatView v = affordanceInstance(pane, segment);

        closeSegment(v, true);   // no open segment at all

        assertEquals(0, pane.getStyledDocument().getLength(),
                "offerCopy with no message leaves the transcript untouched");
    }

    @Test
    void copyMessageButtonIsANonFocusStealingIconButton() throws Exception {
        ChatView v = bareInstance();   // construction touches no instance fields until clicked
        Method m = ChatView.class.getDeclaredMethod("copyMessageButton", String.class);
        m.setAccessible(true);
        javax.swing.JButton b = (javax.swing.JButton) m.invoke(v, "# md");
        assertFalse(b.isFocusable(), "the button must not steal focus from the transcript");
        assertNotNull(b.getIcon(), "flat icon button");
        assertTrue(b.getToolTipText().contains("Markdown"),
                "the tooltip says what is copied, got: " + b.getToolTipText());
    }
}
