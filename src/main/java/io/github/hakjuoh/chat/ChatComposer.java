package io.github.hakjuoh.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure chat-composer decision logic extracted from {@code ChatView}: matching an attachment placeholder
 * token immediately before the caret (for atomic backspace) and filtering the attachments still referenced
 * by a prompt. No Swing — the view reads the caret/text and performs the document edit; this decides what.
 */
public final class ChatComposer {

    private ChatComposer() {
    }

    /** A matched attachment-placeholder token {@code [ … ]} spanning {@code [start, end)} in the input. */
    public record PlaceholderMatch(int start, int end, ChatAttachment attachment) {
    }

    /**
     * Find the attachment placeholder token {@code [ … ]} that ends exactly at {@code caret} and matches one
     * of {@code pending} by its {@link ChatAttachment#placeholder()}. Returns {@code null} when the caret is
     * not immediately after such a token (a literal {@code [ … ]} the user typed, or no token at all) — the
     * caller should then apply an ordinary one-character backspace. Pure: the caller performs the edit.
     */
    public static PlaceholderMatch matchPlaceholderBefore(String text, int caret,
            List<ChatAttachment> pending) {
        if (text == null || caret <= 0 || caret > text.length() || text.charAt(caret - 1) != ']') {
            return null;
        }
        int open = text.lastIndexOf('[', caret - 1);
        if (open < 0) {
            return null;
        }
        String token = text.substring(open, caret);
        for (ChatAttachment a : pending) {
            if (a.placeholder().equals(token)) {
                return new PlaceholderMatch(open, caret, a);
            }
        }
        return null;
    }

    /**
     * The subset of {@code pending} whose placeholder still appears in {@code displayPrompt} (an edited-away
     * placeholder means its attachment must not be sent). Returns an immutable list.
     */
    public static List<ChatAttachment> activeAttachments(List<ChatAttachment> pending, String displayPrompt) {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<ChatAttachment> active = new ArrayList<>();
        for (ChatAttachment attachment : pending) {
            if (displayPrompt.contains(attachment.placeholder())) {
                active.add(attachment);
            }
        }
        return List.copyOf(active);
    }
}
