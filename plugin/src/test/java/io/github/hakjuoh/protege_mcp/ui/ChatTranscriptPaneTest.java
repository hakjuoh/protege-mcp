package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.util.List;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.chat.AssistantSegment;
import io.github.hakjuoh.protege_mcp.ui.ChatTranscriptPane.Kind;

class ChatTranscriptPaneTest {

    private static ChatTranscriptPane transcript() {
        return new ChatTranscriptPane(() -> false);
    }

    @Test
    void messageKindsKeepTheirExistingStyles() {
        SimpleAttributeSet user = ChatTranscriptPane.styleFor(Kind.USER);
        assertTrue(StyleConstants.isBold(user));
        assertEquals(new Color(0x1A4F8B), StyleConstants.getForeground(user));

        SimpleAttributeSet tool = ChatTranscriptPane.styleFor(Kind.TOOL);
        assertTrue(StyleConstants.isItalic(tool));
        assertEquals(new Color(0x507030), StyleConstants.getForeground(tool));

        SimpleAttributeSet thinking = ChatTranscriptPane.styleFor(Kind.THINKING);
        assertTrue(StyleConstants.isItalic(thinking));
        assertEquals(new Color(0x888888), StyleConstants.getForeground(thinking));
        assertEquals(new Color(0xB00020),
                StyleConstants.getForeground(ChatTranscriptPane.styleFor(Kind.ERROR)));
        assertEquals(new Color(0x666666),
                StyleConstants.getForeground(ChatTranscriptPane.styleFor(Kind.SYSTEM)));

        SimpleAttributeSet assistant = ChatTranscriptPane.styleFor(Kind.ASSISTANT);
        assertFalse(assistant.isDefined(StyleConstants.Foreground));
        assertFalse(StyleConstants.isBold(assistant));
    }

    @Test
    void reasoningAndToolRunsOwnLineBoundariesWithoutSplittingDeltas() {
        ChatTranscriptPane pane = transcript();
        pane.append(Kind.ASSISTANT, "answer");
        pane.append(Kind.THINKING, "first");
        pane.append(Kind.THINKING, " second");
        pane.append(Kind.TOOL, "tool\n");
        pane.append(Kind.ASSISTANT, "done");

        assertEquals("answer\nfirst second\ntool\ndone", pane.getText());
    }

    @Test
    void leadingPlainBreaksCollapseAtMessageBoundaries() {
        ChatTranscriptPane pane = transcript();
        pane.append(Kind.USER, "plain\n");
        pane.append(Kind.ERROR, "\n\nerror\n");
        assertEquals("plain\nerror\n", pane.getText());
    }

    @Test
    void plainMessagesOwnOneVisualBlankLineWithoutAddingTextLines() {
        ChatTranscriptPane pane = transcript();
        StyledDocument doc = pane.getStyledDocument();
        pane.append(Kind.USER, "> question\n");
        pane.append(Kind.ASSISTANT, "working");
        pane.append(Kind.TOOL, "  ⚙ first\n");
        pane.append(Kind.TOOL, "  ⚙ second\n");
        pane.append(Kind.ASSISTANT, "done");

        String rendered = pane.getText();
        assertEquals("> question\nworking\n  ⚙ first\n  ⚙ second\ndone", rendered);
        float lineHeight = pane.getFontMetrics(pane.getFont()).getHeight();
        assertParagraphSpacing(doc, rendered.indexOf("> question"), 0F, lineHeight);
        assertParagraphSpacing(doc, rendered.indexOf("working"), 0F, 0F);
        assertParagraphSpacing(doc, rendered.indexOf("⚙ first"), lineHeight, lineHeight);
        assertParagraphSpacing(doc, rendered.indexOf("⚙ second"), 0F, lineHeight);
        assertParagraphSpacing(doc, rendered.indexOf("done"), 0F, 0F);
    }

    @Test
    void streamedReasoningKeepsOnlyOuterMargins() {
        ChatTranscriptPane pane = transcript();
        StyledDocument doc = pane.getStyledDocument();
        pane.append(Kind.ASSISTANT, "answer");
        pane.append(Kind.THINKING, "first paragraph\n");
        pane.append(Kind.THINKING, "\n");
        pane.append(Kind.THINKING, "second paragraph");

        String rendered = pane.getText();
        int first = rendered.indexOf("first");
        int second = rendered.indexOf("second");
        float lineHeight = pane.getFontMetrics(pane.getFont()).getHeight();
        assertParagraphSpacing(doc, first, lineHeight, 0F);
        assertParagraphSpacing(doc, second, 0F, lineHeight);
    }

    @Test
    void messageGapMatchesAnActualBlankLineInSwingLayout() throws Exception {
        double[] actualAndExpected = new double[2];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 20);
                ChatTranscriptPane messages = transcript();
                messages.setFont(font);
                messages.append(Kind.USER, "first\n");
                messages.append(Kind.SYSTEM, "second");
                messages.setSize(400, 400);

                javax.swing.JTextPane blankLine = new javax.swing.JTextPane();
                blankLine.setFont(font);
                blankLine.setText("first\n\nsecond");
                blankLine.setSize(400, 400);

                actualAndExpected[0] = messages.modelToView2D(6).getY()
                        - messages.modelToView2D(0).getY();
                actualAndExpected[1] = blankLine.modelToView2D(7).getY()
                        - blankLine.modelToView2D(0).getY();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        assertEquals(actualAndExpected[1], actualAndExpected[0]);
    }

    @Test
    void markdownKeepsRendererOwnedParagraphSpacing() {
        ChatTranscriptPane pane = transcript();
        pane.append(Kind.ASSISTANT, "first\n\n- one\n- two\n\nlast");

        StyledDocument doc = pane.getStyledDocument();
        String rendered = pane.getText();
        for (String text : List.of("first", "• one", "• two", "last")) {
            assertParagraphSpacing(doc, rendered.indexOf(text), 0F, 0F);
        }
    }

    @Test
    void adjacentProviderMessagesAreDistinctMarkdownSegments() {
        ChatTranscriptPane pane = transcript();
        pane.append(Kind.ASSISTANT_START, "");
        pane.append(Kind.ASSISTANT, "**one.**");
        pane.append(Kind.ASSISTANT_START, "");
        pane.append(Kind.ASSISTANT, "_Two._");
        pane.closeAssistantSegment(false);

        String rendered = pane.getText();
        assertEquals("one.\n\nTwo.", rendered);
        assertEquals("**one.**", AssistantSegment.sourceAt(
                pane.getStyledDocument(), rendered.indexOf("one.")));
        assertEquals("_Two._", AssistantSegment.sourceAt(
                pane.getStyledDocument(), rendered.indexOf("Two.")));
    }

    @Test
    void visibleToolAlreadySeparatesAdjacentAssistantMessages() {
        ChatTranscriptPane pane = transcript();
        pane.append(Kind.ASSISTANT, "first");
        pane.append(Kind.TOOL, "tool\n");
        pane.append(Kind.ASSISTANT_START, "");
        pane.append(Kind.ASSISTANT, "second");
        assertEquals("first\ntool\nsecond", pane.getText());
    }

    @Test
    void finalReplyCloseAddsATaggedCopyButtonLine() {
        ChatTranscriptPane pane = transcript();
        pane.append(Kind.ASSISTANT, "**reply**");
        pane.closeAssistantSegment(true);

        StyledDocument doc = pane.getStyledDocument();
        String text = pane.getText();
        assertTrue(text.startsWith("reply\n"));
        assertTrue(text.endsWith("\n"));
        int buttonPosition = text.length() - 2;
        assertTrue(StyleConstants.getComponent(
                doc.getCharacterElement(buttonPosition).getAttributes()) instanceof JButton);
        assertEquals("**reply**", AssistantSegment.sourceAt(doc, buttonPosition));
        assertTrue(pane.isAtLineStart());
    }

    @Test
    void interimCloseTagsSourceWithoutAddingAButton() {
        ChatTranscriptPane pane = transcript();
        pane.append(Kind.ASSISTANT, "interim");
        pane.closeAssistantSegment(false);

        assertEquals("interim", pane.getText());
        assertEquals("interim", AssistantSegment.sourceAt(pane.getStyledDocument(), 0));
    }

    @Test
    void emptyCloseChangesNothing() {
        ChatTranscriptPane pane = transcript();
        assertNull(pane.closeAssistantSegment(true));
        assertEquals("", pane.getText());
    }

    @Test
    void copyButtonDoesNotStealFocus() {
        JButton button = transcript().copyMessageButton("# md");
        assertFalse(button.isFocusable());
        assertNotNull(button.getIcon());
        assertTrue(button.getToolTipText().contains("Markdown"));
    }

    @Test
    void resetClearsDocumentSegmentAndBoundaryState() {
        ChatTranscriptPane pane = transcript();
        pane.append(Kind.ASSISTANT, "answer");
        pane.append(Kind.THINKING, "reasoning");

        pane.resetTranscript();
        pane.append(Kind.SYSTEM, "fresh");

        assertEquals("fresh", pane.getText());
        assertFalse(pane.isAtLineStart());
        assertNull(AssistantSegment.sourceAt(pane.getStyledDocument(), 0));
    }

    private static void assertParagraphSpacing(StyledDocument doc, int offset,
            float expectedAbove, float expectedBelow) {
        assertEquals(expectedAbove, StyleConstants.getSpaceAbove(
                doc.getParagraphElement(offset).getAttributes()));
        assertEquals(expectedBelow, StyleConstants.getSpaceBelow(
                doc.getParagraphElement(offset).getAttributes()));
    }
}
