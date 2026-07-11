package io.github.hakjuoh.protege_mcp.chat;

import java.util.List;

import javax.swing.text.BadLocationException;
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
 */
public final class AssistantSegment {

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
}
