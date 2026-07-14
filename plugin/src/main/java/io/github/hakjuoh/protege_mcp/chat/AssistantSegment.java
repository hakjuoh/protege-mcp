package io.github.hakjuoh.protege_mcp.chat;

import java.util.List;

import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyledDocument;

/**
 * State of the one in-flight assistant message inside the chat transcript, split out of ChatView (a
 * Swing component) so the segment invariants are unit-testable headless (a {@link StyledDocument} is
 * a plain model, not an AWT component). Single-threaded by design: the view only touches it on the
 * EDT.
 *
 * <p>Invariant: while open, the segment is always the <em>suffix</em> of the document — the view
 * closes it before appending any non-assistant kind — so a plain int start offset is stable (nothing
 * before it ever changes). Each {@link #appendAndRender} re-renders the whole message from its
 * accumulated Markdown source: unclosed markers render literally and converge when their closers
 * stream in. Rendering happens <em>before</em> the document is touched, so a rendering failure of
 * any kind leaves the transcript intact.
 *
 * <p>Rendering is lossy (a copied selection yields the styled plain text, not the markup), so
 * {@link #close(StyledDocument)} tags the finished message's rendered range with {@link #SOURCE_MD}
 * — a character attribute carrying the original Markdown source — and hands that source back. The
 * view uses it for its copy-message affordances; {@link #sourceAt} looks it up again later at any
 * document position.
 */
public final class AssistantSegment {

    /** Character-attribute key whose value is the original Markdown source of a closed message. */
    public static final String SOURCE_MD = "io.github.hakjuoh.protege_mcp.chat.source-md";

    private final StringBuilder markdown = new StringBuilder();
    private int start = -1;

    public boolean isOpen() {
        return start >= 0;
    }

    /**
     * Appends {@code text} to the message's Markdown source and re-renders the segment in place as
     * the suffix of {@code doc} (opening the segment at the current document end if none is open).
     *
     * @return whether the rendered segment now ends with a line break, or {@code null} when nothing
     *         was rendered (whitespace-only source) — the caller keeps its previous line state then.
     */
    public Boolean appendAndRender(StyledDocument doc, String text, int baseFontSize) {
        if (start < 0) {
            start = doc.getLength();
        }
        markdown.append(text);
        // Render first (never throws — ChatMarkdown falls back to a plain run), then swap the
        // segment in one go. The remove + inserts run inside one EDT event, so no intermediate
        // state is ever painted.
        List<ChatMarkdown.Run> runs = ChatMarkdown.render(markdown.toString(), baseFontSize);
        try {
            doc.remove(start, doc.getLength() - start);
            for (ChatMarkdown.Run run : runs) {
                doc.insertString(doc.getLength(), run.text(), run.attributes());
            }
        } catch (BadLocationException ex) {
            return null;
        }
        return runs.isEmpty() ? null : runs.get(runs.size() - 1).text().endsWith("\n");
    }

    /** Ends the in-flight message; the next assistant text starts a fresh Markdown context. */
    public void close() {
        start = -1;
        markdown.setLength(0);
    }

    /**
     * Ends the in-flight message and tags its rendered range in {@code doc} with {@link #SOURCE_MD}
     * so the original markup stays recoverable after the source buffer is reset.
     *
     * @return the message's Markdown source, or {@code null} when there was nothing to keep (no
     *         open segment, a whitespace-only source, or a document cleared out from under the
     *         segment)
     */
    public String close(StyledDocument doc) {
        if (start < 0) {
            return null;
        }
        String source = markdown.toString();
        int from = start;
        int end = doc.getLength();   // suffix invariant: the open segment always ends the document
        close();
        if (source.isBlank() || from >= end) {
            return null;
        }
        SimpleAttributeSet tag = new SimpleAttributeSet();
        tag.addAttribute(SOURCE_MD, source);
        doc.setCharacterAttributes(from, end - from, tag, false);
        return source;
    }

    /** The original Markdown source of the closed message rendered at {@code pos}, or {@code null}. */
    public static String sourceAt(StyledDocument doc, int pos) {
        if (pos < 0 || pos >= doc.getLength()) {
            return null;
        }
        Object source = doc.getCharacterElement(pos).getAttributes().getAttribute(SOURCE_MD);
        return source instanceof String s ? s : null;
    }
}
