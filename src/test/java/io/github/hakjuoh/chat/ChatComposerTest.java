package io.github.hakjuoh.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Headless tests for {@link ChatComposer}, the pure composer logic extracted from {@code ChatView}. */
class ChatComposerTest {

    private static ChatAttachment pasted(String label) {
        return ChatAttachment.pastedText(label, "body");
    }

    private static ChatAttachment image(String label) {
        return ChatAttachment.image(label, new File("/tmp/" + label + ".png"), "image/png");
    }

    // ---------------------------------------------------------------- matchPlaceholderBefore

    @Test
    void matchNullTextReturnsNull() {
        assertNull(ChatComposer.matchPlaceholderBefore(null, 0, List.of()));
    }

    @Test
    void matchCaretAtZeroReturnsNull() {
        ChatAttachment a = pasted("Pasted content #1");
        assertNull(ChatComposer.matchPlaceholderBefore(a.placeholder(), 0, List.of(a)));
    }

    @Test
    void matchCaretPastEndReturnsNull() {
        ChatAttachment a = pasted("Pasted content #1");
        String text = a.placeholder();
        assertNull(ChatComposer.matchPlaceholderBefore(text, text.length() + 1, List.of(a)));
    }

    @Test
    void matchWhenCharBeforeCaretIsNotClosingBracketReturnsNull() {
        ChatAttachment a = pasted("Pasted content #1");
        String text = a.placeholder() + " x"; // caret at end -> char before is 'x'
        assertNull(ChatComposer.matchPlaceholderBefore(text, text.length(), List.of(a)));
    }

    @Test
    void matchNoOpeningBracketReturnsNull() {
        assertNull(ChatComposer.matchPlaceholderBefore("abc]", 4, List.of(pasted("X"))));
    }

    @Test
    void matchLiteralBracketTextWithNoMatchingAttachmentReturnsNull() {
        // The user typed "[not an attachment]" — no pending attachment owns that placeholder.
        String text = "[not an attachment]";
        assertNull(ChatComposer.matchPlaceholderBefore(text, text.length(), List.of(pasted("Image #1"))));
    }

    @Test
    void matchReturnsSpanAndAttachmentWhenTokenMatches() {
        ChatAttachment a = pasted("Pasted content #1");
        String text = "hello " + a.placeholder();
        int caret = text.length(); // just after the ']'
        ChatComposer.PlaceholderMatch m = ChatComposer.matchPlaceholderBefore(text, caret, List.of(a));
        assertEquals(6, m.start(), "token starts at the '['");
        assertEquals(caret, m.end(), "token ends at the caret");
        assertSame(a, m.attachment());
        assertEquals(a.placeholder(), text.substring(m.start(), m.end()));
    }

    @Test
    void matchPicksTheTokenEndingAtCaretAmongSeveral() {
        ChatAttachment a1 = pasted("Pasted content #1");
        ChatAttachment a2 = image("Image #1");
        String text = a1.placeholder() + a2.placeholder(); // caret right after a2's ']'
        ChatComposer.PlaceholderMatch m =
                ChatComposer.matchPlaceholderBefore(text, text.length(), List.of(a1, a2));
        assertSame(a2, m.attachment(), "matches the token ending at the caret, not the first pending");
        assertEquals(a1.placeholder().length(), m.start());
    }

    @Test
    void matchWorksWithTextAfterTheTokenWhenCaretSitsRightAfterIt() {
        ChatAttachment a = image("Image #2");
        String prefix = a.placeholder();
        String text = prefix + " trailing";
        int caret = prefix.length(); // caret right after ']' with text following
        ChatComposer.PlaceholderMatch m = ChatComposer.matchPlaceholderBefore(text, caret, List.of(a));
        assertSame(a, m.attachment());
        assertEquals(0, m.start());
        assertEquals(caret, m.end());
    }

    // ---------------------------------------------------------------- activeAttachments

    @Test
    void activeEmptyPendingReturnsEmpty() {
        assertTrue(ChatComposer.activeAttachments(List.of(), "anything").isEmpty());
    }

    @Test
    void activeIncludesOnlyAttachmentsStillReferencedInPrompt() {
        ChatAttachment kept = pasted("Pasted content #1");
        ChatAttachment removed = image("Image #1");
        String prompt = "see " + kept.placeholder() + " only"; // removed's placeholder was edited away
        assertEquals(List.of(kept), ChatComposer.activeAttachments(List.of(kept, removed), prompt));
    }

    @Test
    void activeReturnsImmutableList() {
        ChatAttachment a = pasted("Pasted content #1");
        List<ChatAttachment> active = ChatComposer.activeAttachments(List.of(a), a.placeholder());
        assertThrows(UnsupportedOperationException.class, () -> active.add(a));
    }
}
