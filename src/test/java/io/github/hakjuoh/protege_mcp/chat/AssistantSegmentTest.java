package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link AssistantSegment}: the suffix-segment invariant behind the chat's
 * streaming Markdown re-render. Uses a bare {@link DefaultStyledDocument} (a model, not an AWT
 * component), so the invariants ChatView relies on are pinned without a display.
 */
class AssistantSegmentTest {

    private static final int BASE = 13;

    private static StyledDocument docWith(String prefix) throws BadLocationException {
        StyledDocument doc = new DefaultStyledDocument();
        doc.insertString(0, prefix, new SimpleAttributeSet());
        return doc;
    }

    private static String text(StyledDocument doc) throws BadLocationException {
        return doc.getText(0, doc.getLength());
    }

    private static boolean boldAt(StyledDocument doc, int offset) {
        return StyleConstants.isBold(doc.getCharacterElement(offset).getAttributes());
    }

    @Test
    void initiallyClosedAndCloseIsIdempotent() {
        AssistantSegment seg = new AssistantSegment();
        assertFalse(seg.isOpen());
        seg.close();
        assertFalse(seg.isOpen());
    }

    @Test
    void opensAtDocumentEndAndRendersMarkdown() throws Exception {
        StyledDocument doc = docWith("intro\n");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "**b**", BASE);
        assertTrue(seg.isOpen());
        assertEquals("intro\nb", text(doc));
        assertTrue(boldAt(doc, 6));
        assertFalse(boldAt(doc, 0), "content before the segment is untouched");
    }

    @Test
    void reRenderReplacesOnlyTheSuffixAndConverges() throws Exception {
        StyledDocument doc = docWith("intro\n");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "**bo", BASE);
        assertEquals("intro\n**bo", text(doc), "unclosed marker renders literally mid-stream");
        assertFalse(boldAt(doc, 8));
        seg.appendAndRender(doc, "ld**", BASE);
        assertEquals("intro\nbold", text(doc), "the late closer restyles the whole span");
        assertTrue(boldAt(doc, 6));
        assertEquals("intro\n", doc.getText(0, 6), "prefix survives every re-render");
    }

    @Test
    void closeStartsAFreshMarkdownContext() throws Exception {
        StyledDocument doc = docWith("");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "**a", BASE);
        seg.close();
        assertFalse(seg.isOpen());
        seg.appendAndRender(doc, "b**", BASE);
        // The closer arriving in a NEW segment must not reach back into the closed one.
        assertEquals("**ab**", text(doc));
        assertFalse(boldAt(doc, 0));
        assertFalse(boldAt(doc, 3));
    }

    @Test
    void interleavedForeignAppendScenario() throws Exception {
        // What ChatView does when a tool line interrupts: close, append other text at the end,
        // then assistant text opens a NEW suffix segment after it.
        StyledDocument doc = docWith("");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "before", BASE);
        seg.close();
        doc.insertString(doc.getLength(), "\n  ⚙ tool\n", new SimpleAttributeSet());
        seg.appendAndRender(doc, "**after**", BASE);
        assertEquals("before\n  ⚙ tool\nafter", text(doc));
        assertTrue(boldAt(doc, doc.getLength() - 1));
    }

    @Test
    void whitespaceOnlySourceRendersNothingAndReportsNull() throws Exception {
        StyledDocument doc = docWith("x");
        AssistantSegment seg = new AssistantSegment();
        assertNull(seg.appendAndRender(doc, "\n\n", BASE));
        assertEquals("x", text(doc));
        assertTrue(seg.isOpen(), "the segment is open and converges once visible text arrives");
        seg.appendAndRender(doc, "y", BASE);
        assertEquals("xy", text(doc));
    }

    @Test
    void typicalMessageReportsNoTrailingBreak() throws Exception {
        StyledDocument doc = docWith("");
        AssistantSegment seg = new AssistantSegment();
        assertEquals(Boolean.FALSE, seg.appendAndRender(doc, "done", BASE));
    }

    @Test
    void pathologicalNestingNeverWipesTheTranscript() throws Exception {
        StyledDocument doc = docWith("intro\n");
        AssistantSegment seg = new AssistantSegment();
        String nasty = ">".repeat(4000) + " x";
        assertDoesNotThrow(() -> seg.appendAndRender(doc, nasty, BASE));
        assertTrue(text(doc).startsWith("intro\n"), "prior transcript survives");
        assertTrue(text(doc).contains("x"), "the reply text is not lost");
    }

    // ------------------------------------------------------------------ close(doc): source tagging

    @Test
    void closeWithDocTagsTheRenderedRangeAndReturnsTheSource() throws Exception {
        StyledDocument doc = docWith("intro\n");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "**bold** and `code`", BASE);
        String source = seg.close(doc);
        assertEquals("**bold** and `code`", source, "close hands back the original markup");
        assertFalse(seg.isOpen());
        assertEquals(source, AssistantSegment.sourceAt(doc, 6), "first rendered char is tagged");
        assertEquals(source, AssistantSegment.sourceAt(doc, doc.getLength() - 1),
                "last rendered char is tagged");
        assertNull(AssistantSegment.sourceAt(doc, 0), "the prefix before the message is untagged");
        assertNull(AssistantSegment.sourceAt(doc, 5),
                "the character immediately before the message is untagged (exact start boundary)");
    }

    @Test
    void closeWithDocMergesTheTagWithoutStrippingStyling() throws Exception {
        StyledDocument doc = docWith("");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "**bold**", BASE);
        assertTrue(boldAt(doc, 0), "precondition: rendered bold");
        seg.close(doc);
        assertTrue(boldAt(doc, 0), "the tag merges into existing attributes; styling survives");
        assertEquals("**bold**", AssistantSegment.sourceAt(doc, 0));
    }

    @Test
    void closeWithDocOnUnopenedSegmentReturnsNull() throws Exception {
        StyledDocument doc = docWith("x");
        AssistantSegment seg = new AssistantSegment();
        assertNull(seg.close(doc));
        assertNull(seg.close(doc), "idempotent: a second close still reports nothing to keep");
    }

    @Test
    void closeWithDocWhitespaceOnlySourceTagsNothing() throws Exception {
        StyledDocument doc = docWith("x");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "\n\n", BASE);
        assertNull(seg.close(doc), "a whitespace-only message has no copyable source");
        assertNull(AssistantSegment.sourceAt(doc, 0));
        assertFalse(seg.isOpen());
    }

    @Test
    void closeWithDocSurvivesADocumentClearedUnderneath() throws Exception {
        // ChatView's New chat clears the document BEFORE closing the segment; the stale start
        // offset must not corrupt or throw.
        StyledDocument doc = docWith("intro\n");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "reply", BASE);
        doc.remove(0, doc.getLength());
        assertNull(assertDoesNotThrow(() -> seg.close(doc)),
                "nothing to keep once the document is gone");
        assertFalse(seg.isOpen());
    }

    @Test
    void textAppendedAfterCloseIsNotTagged() throws Exception {
        StyledDocument doc = docWith("");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "message", BASE);
        seg.close(doc);
        int end = doc.getLength();
        doc.insertString(end, "\n  ⚙ tool\n", new SimpleAttributeSet());
        assertNull(AssistantSegment.sourceAt(doc, end + 3), "later foreign text carries no source");
    }

    @Test
    void consecutiveMessagesCarryTheirOwnSources() throws Exception {
        StyledDocument doc = docWith("");
        AssistantSegment seg = new AssistantSegment();
        seg.appendAndRender(doc, "*first*", BASE);
        seg.close(doc);
        int secondStart = doc.getLength();
        seg.appendAndRender(doc, "**second**", BASE);
        seg.close(doc);
        assertEquals("*first*", AssistantSegment.sourceAt(doc, 0));
        assertEquals("**second**", AssistantSegment.sourceAt(doc, secondStart));
    }

    @Test
    void sourceAtOutOfRangeIsNull() throws Exception {
        StyledDocument doc = docWith("abc");
        assertNull(AssistantSegment.sourceAt(doc, -1));
        assertNull(AssistantSegment.sourceAt(doc, doc.getLength()), "end offset is out of range");
    }
}
