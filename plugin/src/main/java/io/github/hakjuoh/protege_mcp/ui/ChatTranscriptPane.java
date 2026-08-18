package io.github.hakjuoh.protege_mcp.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import io.github.hakjuoh.protege_mcp.chat.AssistantSegment;
import io.github.hakjuoh.protege_mcp.chat.ChatMarkdown;
import io.github.hakjuoh.protege_mcp.chat.TranscriptMessageSpacing;

/**
 * Owns the chat transcript's document state and interactions.
 *
 * <p>All methods that mutate the document are EDT-only. Assistant replies are accumulated as
 * Markdown and re-rendered in place while streaming. Plain transcript events retain their visual
 * message boundaries without changing renderer-owned Markdown paragraph attributes.
 */
final class ChatTranscriptPane extends JTextPane {

    private static final long serialVersionUID = 1L;
    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");

    enum Kind { USER, ASSISTANT_START, ASSISTANT, TOOL, THINKING, ERROR, SYSTEM }

    private record MessageMargins(float above, float below) { }

    private final BooleanSupplier turnActive;
    private final AssistantSegment assistantSegment = new AssistantSegment();

    private boolean atLineStart = true;
    private Kind lastRenderedKind;
    private int thinkingBlockStart = -1;
    private int thinkingBlockEnd = -1;
    private float thinkingBlockSpaceAbove = -1F;

    ChatTranscriptPane(BooleanSupplier turnActive) {
        this.turnActive = Objects.requireNonNull(turnActive);
        setEditable(false);
        installLinkHandlers();
        installContextMenu();
    }

    boolean isAtLineStart() {
        return atLineStart;
    }

    /** Clears the document and every offset or boundary derived from it. */
    void resetTranscript() {
        setText("");
        assistantSegment.close();
        atLineStart = true;
        lastRenderedKind = null;
        resetThinkingBlock();
    }

    void append(Kind kind, String text) {
        if (kind == Kind.ASSISTANT_START) {
            startAssistantMessage();
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        if (kind == Kind.ASSISTANT) {
            appendAssistant(text);
            return;
        }
        if (kind != Kind.THINKING) {
            text = normalizeLeadingBoundaryBreaks(text, atLineStart);
            if (text.isEmpty()) {
                return;
            }
        }
        boolean continuingThinking = kind == Kind.THINKING
                && lastRenderedKind == Kind.THINKING && thinkingBlockSpaceAbove >= 0F;
        if (!continuingThinking) {
            resetThinkingBlock();
        }
        closeAssistantSegment(false);
        if (needsTranscriptLineBreak(kind, text)) {
            text = "\n" + text;
        }
        StyledDocument doc = getStyledDocument();
        int insertionStart = doc.getLength();
        SimpleAttributeSet attributes = styleFor(kind);
        MessageMargins margins = plainMessageMargins();
        if (kind == Kind.THINKING) {
            if (!continuingThinking) {
                thinkingBlockSpaceAbove = margins.above();
            } else {
                margins = new MessageMargins(thinkingBlockSpaceAbove, margins.below());
            }
        }
        try {
            if (continuingThinking && thinkingBlockEnd > thinkingBlockStart) {
                TranscriptMessageSpacing.apply(doc, thinkingBlockStart, thinkingBlockEnd, 0F);
            }
            doc.insertString(insertionStart, text, attributes);
            int firstContent = firstContentOffset(text);
            int lastContent = lastContentOffset(text);
            if (kind == Kind.THINKING) {
                if (firstContent < lastContent) {
                    if (thinkingBlockStart < 0) {
                        thinkingBlockStart = insertionStart + firstContent;
                    }
                    thinkingBlockEnd = insertionStart + lastContent;
                }
                if (thinkingBlockEnd > thinkingBlockStart) {
                    TranscriptMessageSpacing.apply(doc, thinkingBlockStart,
                            thinkingBlockEnd, margins.above(), margins.below());
                }
            } else if (firstContent < lastContent) {
                TranscriptMessageSpacing.apply(doc, insertionStart + firstContent,
                        insertionStart + lastContent, margins.above(), margins.below());
            }
        } catch (BadLocationException ignored) {
            return;
        }
        atLineStart = text.endsWith("\n");
        lastRenderedKind = kind;
        setCaretPosition(doc.getLength());
    }

    /** Ends the current assistant segment and optionally adds its copy-as-Markdown button. */
    String closeAssistantSegment(boolean offerCopy) {
        String source = assistantSegment.close(getStyledDocument());
        if (offerCopy && source != null) {
            insertCopyAffordance(source);
        }
        return source;
    }

    private void startAssistantMessage() {
        StyledDocument doc = getStyledDocument();
        String previous = closeAssistantSegment(false);
        if (previous == null) {
            return;
        }
        try {
            doc.insertString(doc.getLength(), atLineStart ? "\n" : "\n\n", null);
            atLineStart = true;
            setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {
            // The source segments remain distinct even if their visual separator could not be added.
        }
    }

    boolean needsTranscriptLineBreak(Kind kind, String text) {
        if (atLineStart || text.startsWith("\n")) {
            return false;
        }
        return kind == Kind.TOOL
                || (kind == Kind.THINKING) != (lastRenderedKind == Kind.THINKING);
    }

    static int firstContentOffset(String text) {
        int offset = 0;
        while (offset < text.length() && (text.charAt(offset) == '\n' || text.charAt(offset) == '\r')) {
            offset++;
        }
        return offset;
    }

    static int lastContentOffset(String text) {
        int offset = text.length();
        while (offset > 0 && (text.charAt(offset - 1) == '\n' || text.charAt(offset - 1) == '\r')) {
            offset--;
        }
        return offset;
    }

    static String normalizeLeadingBoundaryBreaks(String text, boolean atLineStart) {
        int content = firstContentOffset(text);
        if (content == 0) {
            return text;
        }
        return (atLineStart ? "" : "\n") + text.substring(content);
    }

    private void appendAssistant(String text) {
        StyledDocument doc = getStyledDocument();
        if (lastRenderedKind == Kind.THINKING && !atLineStart) {
            try {
                doc.insertString(doc.getLength(), "\n", null);
                atLineStart = true;
            } catch (BadLocationException ignored) {
                // Worst case, the reply begins on the reasoning line.
            }
        }
        resetThinkingBlock();
        Boolean endsWithBreak = assistantSegment.appendAndRender(doc, text, transcriptFontSize());
        if (endsWithBreak != null) {
            atLineStart = endsWithBreak;
        }
        lastRenderedKind = Kind.ASSISTANT;
        setCaretPosition(doc.getLength());
    }

    private void resetThinkingBlock() {
        thinkingBlockStart = -1;
        thinkingBlockEnd = -1;
        thinkingBlockSpaceAbove = -1F;
    }

    private void insertCopyAffordance(String markdown) {
        StyledDocument doc = getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setComponent(attrs, copyMessageButton(markdown));
        attrs.addAttribute(AssistantSegment.SOURCE_MD, markdown);
        SimpleAttributeSet sourceOnly = new SimpleAttributeSet();
        sourceOnly.addAttribute(AssistantSegment.SOURCE_MD, markdown);
        try {
            if (!atLineStart) {
                doc.insertString(doc.getLength(), "\n", sourceOnly);
            }
            doc.insertString(doc.getLength(), " ", attrs);
            doc.insertString(doc.getLength(), "\n", sourceOnly);
        } catch (BadLocationException ignored) {
            return;
        }
        atLineStart = true;
        setCaretPosition(doc.getLength());
    }

    JButton copyMessageButton(String markdown) {
        Icon copyIcon = ChatIcons.icon(ChatIcons.Glyph.COPY, 16, new Color(0x777777), null);
        Icon copiedIcon = ChatIcons.icon(ChatIcons.Glyph.CHECK, 16, new Color(0x1E8E3E), null);
        String tooltip = "Copy message (original Markdown)";
        JButton button = ChatIcons.iconButton(copyIcon, tooltip);
        button.setFocusable(false);
        button.setAlignmentY(0.8f);
        Timer revert = new Timer(1500, event -> {
            button.setIcon(copyIcon);
            button.setToolTipText(tooltip);
        });
        revert.setRepeats(false);
        button.addActionListener(event -> {
            if (!copyToClipboard(markdown)) {
                return;
            }
            button.setIcon(copiedIcon);
            button.setToolTipText("Copied");
            revert.restart();
        });
        return button;
    }

    private boolean copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            return true;
        } catch (IllegalStateException ex) {
            reportTransientUiError("\nCould not access the system clipboard — try again.\n");
            return false;
        }
    }

    private void reportTransientUiError(String message) {
        if (turnActive.getAsBoolean()) {
            Toolkit.getDefaultToolkit().beep();
        } else {
            append(Kind.ERROR, message);
        }
    }

    private int transcriptFontSize() {
        Font font = getFont();
        return font != null ? font.getSize() : 13;
    }

    MessageMargins plainMessageMargins() {
        Font font = getFont();
        int lineHeight = font == null ? 13 : getFontMetrics(font).getHeight();
        return lastRenderedKind == Kind.ASSISTANT
                ? new MessageMargins(lineHeight, lineHeight)
                : new MessageMargins(0F, lineHeight);
    }

    private void installLinkHandlers() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                boolean macPopupGesture = event.isControlDown() && IS_MAC;
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1
                        && !macPopupGesture) {
                    String url = linkAt(event.getPoint());
                    if (url != null) {
                        openLink(url);
                    }
                }
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                String url = linkAt(event.getPoint());
                setToolTipText(url);
                setCursor(Cursor.getPredefinedCursor(
                        url != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        });
    }

    String linkAt(Point point) {
        int position = viewToModel2D(point);
        StyledDocument doc = getStyledDocument();
        if (position < 0 || position >= doc.getLength()) {
            return null;
        }
        Element element = doc.getCharacterElement(position);
        Object url = element.getAttributes().getAttribute(ChatMarkdown.LINK_URL);
        if (!(url instanceof String value)) {
            return null;
        }
        try {
            Rectangle2D bounds = modelToView2D(position);
            Rectangle2D next = modelToView2D(position + 1);
            if (bounds == null || next == null) {
                return null;
            }
            double x1 = Math.min(bounds.getX(), next.getX()) - 1;
            double x2 = Math.max(bounds.getX(), next.getX()) + 1;
            if (point.getY() < bounds.getY() || point.getY() > bounds.getY() + bounds.getHeight()
                    || point.getX() < x1 || point.getX() > x2) {
                return null;
            }
        } catch (BadLocationException ex) {
            return null;
        }
        return value;
    }

    private void installContextMenu() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                maybeShowContextMenu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShowContextMenu(event);
            }
        });
    }

    private void maybeShowContextMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        String selected = getSelectedText();
        JMenuItem copySelection = new JMenuItem("Copy");
        copySelection.setEnabled(selected != null && !selected.isEmpty());
        copySelection.addActionListener(ignored -> {
            if (selected != null && !selected.isEmpty()) {
                copyToClipboard(selected);
            }
        });
        menu.add(copySelection);

        int position = viewToModel2D(event.getPoint());
        String atPosition = AssistantSegment.sourceAt(getStyledDocument(), position);
        String source = atPosition != null ? atPosition
                : AssistantSegment.sourceAt(getStyledDocument(), position - 1);
        JMenuItem copyMessage = new JMenuItem("Copy message as Markdown");
        copyMessage.setEnabled(source != null);
        copyMessage.addActionListener(ignored -> {
            if (source != null) {
                copyToClipboard(source);
            }
        });
        menu.add(copyMessage);
        menu.show(this, event.getX(), event.getY());
    }

    private void openLink(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return;
        }
        String shown = url.length() > 300 ? url.substring(0, 300) + "…" : url;
        int choice = JOptionPane.showConfirmDialog(this,
                "Open this link in your browser?\n\n" + shown,
                "Open link", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ex) {
            reportTransientUiError("\nCould not open link: " + url + "\n");
        }
    }

    static SimpleAttributeSet styleFor(Kind kind) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        switch (kind) {
            case USER -> {
                StyleConstants.setBold(attributes, true);
                StyleConstants.setForeground(attributes, new Color(0x1A4F8B));
            }
            case TOOL -> {
                StyleConstants.setItalic(attributes, true);
                StyleConstants.setForeground(attributes, new Color(0x507030));
            }
            case THINKING -> {
                StyleConstants.setItalic(attributes, true);
                StyleConstants.setForeground(attributes, new Color(0x888888));
            }
            case ERROR -> StyleConstants.setForeground(attributes, new Color(0xB00020));
            case SYSTEM -> StyleConstants.setForeground(attributes, new Color(0x666666));
            default -> {
            }
        }
        return attributes;
    }
}
