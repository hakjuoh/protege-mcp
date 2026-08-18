package io.github.hakjuoh.protege_mcp.chat;

import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Applies CSS-like outer spacing to a rendered message without changing its inner paragraphs. */
public final class TranscriptMessageSpacing {

    private TranscriptMessageSpacing() {
    }

    /** Applies the same margin above and below a plain-message range. */
    public static void apply(StyledDocument doc, int start, int end, float marginPoints) {
        apply(doc, start, end, marginPoints, marginPoints);
    }

    /**
     * Adds independently sized margins above the first paragraph and below the last paragraph
     * intersecting {@code [start, end)}; paragraphs inside the range remain untouched.
     */
    public static void apply(StyledDocument doc, int start, int end,
            float spaceAbove, float spaceBelow) {
        if (start < 0 || end <= start || end > doc.getLength()) {
            throw new IllegalArgumentException("Invalid message range: " + start + ".." + end);
        }
        if (!Float.isFinite(spaceAbove) || spaceAbove < 0F
                || !Float.isFinite(spaceBelow) || spaceBelow < 0F) {
            throw new IllegalArgumentException("Message margins must be finite and non-negative");
        }

        SimpleAttributeSet top = new SimpleAttributeSet();
        StyleConstants.setSpaceAbove(top, spaceAbove);
        doc.setParagraphAttributes(start, 1, top, false);

        SimpleAttributeSet bottom = new SimpleAttributeSet();
        StyleConstants.setSpaceBelow(bottom, spaceBelow);
        doc.setParagraphAttributes(end - 1, 1, bottom, false);
    }
}
