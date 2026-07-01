package io.github.hakjuoh.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.chat.ChatAttachment.Kind;
import java.io.File;
import org.junit.jupiter.api.Test;

/** Method-level tests for the {@link ChatAttachment} record. */
class ChatAttachmentTest {

    // ---- compact constructor: null / blank label -------------------------------------------

    @Test
    void constructorNullKindThrowsNpeWithMessageKind() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ChatAttachment(null, "l", "t", null, "text/plain"),
                "null kind must throw NPE");
        assertEquals("kind", ex.getMessage(), "NPE message must name the kind field");
    }

    @Test
    void constructorNullLabelThrowsNpeWithMessageLabel() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ChatAttachment(Kind.PASTED_TEXT, null, "t", null, "text/plain"),
                "null label must throw NPE");
        assertEquals("label", ex.getMessage(), "NPE message must name the label field");
    }

    @Test
    void constructorTrimsLeadingAndTrailingWhitespaceFromLabel() {
        ChatAttachment a = new ChatAttachment(Kind.PASTED_TEXT, "  hello  ", "t", null, "text/plain");
        assertEquals("hello", a.label(), "label should be trimmed");
    }

    @Test
    void constructorBlankAfterTrimThrowsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ChatAttachment(Kind.PASTED_TEXT, "   ", "t", null, "text/plain"),
                "whitespace-only label must be rejected");
        assertEquals("label must not be blank", ex.getMessage(), "message must match source");
    }

    @Test
    void constructorEmptyLabelThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChatAttachment(Kind.PASTED_TEXT, "", "t", null, "text/plain"),
                "empty label must be rejected");
    }

    // ---- compact constructor: PASTED_TEXT branch -------------------------------------------

    @Test
    void pastedTextRequiresNonNullTextThrowsNpe() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ChatAttachment(Kind.PASTED_TEXT, "l", null, null, "text/plain"),
                "PASTED_TEXT requires non-null text");
        assertEquals("text", ex.getMessage(), "NPE message must name the text field");
    }

    @Test
    void pastedTextWithNullFileLeavesFileNull() {
        ChatAttachment a = new ChatAttachment(Kind.PASTED_TEXT, "l", "body", null, "text/plain");
        assertNull(a.file(), "small pasted body keeps file == null");
        assertEquals("body", a.text(), "text should be preserved");
    }

    @Test
    void pastedTextWithRelativeFileConvertedToAbsolute() {
        File relative = new File("some-relative-buffer.txt");
        assertTrue(!relative.isAbsolute(), "precondition: relative path");
        ChatAttachment a = new ChatAttachment(Kind.PASTED_TEXT, "l", "body", relative, "text/plain");
        assertTrue(a.file().isAbsolute(), "buffer file must be made absolute");
        assertEquals(relative.getAbsoluteFile(), a.file(), "absolute form must match");
    }

    // ---- compact constructor: IMAGE branch -------------------------------------------------

    @Test
    void imageRequiresNonNullFileThrowsNpe() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ChatAttachment(Kind.IMAGE, "l", null, null, "image/png"),
                "IMAGE requires non-null file");
        assertEquals("file", ex.getMessage(), "NPE message must name the file field");
    }

    @Test
    void imageWithRelativeFileConvertedToAbsolute() {
        File relative = new File("pic.png");
        ChatAttachment a = new ChatAttachment(Kind.IMAGE, "l", null, relative, "image/png");
        assertTrue(a.file().isAbsolute(), "image file must be made absolute");
        assertEquals(relative.getAbsoluteFile(), a.file(), "absolute form must match");
    }

    @Test
    void imageWithNonNullTextIsNullifiedInConstructor() {
        ChatAttachment a = new ChatAttachment(Kind.IMAGE, "l", "ignored", new File("pic.png"), "image/png");
        assertNull(a.text(), "text must be nullified for non-PASTED_TEXT kinds");
    }

    // ---- compact constructor: FILE branch --------------------------------------------------

    @Test
    void fileRequiresNonNullFileThrowsNpe() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ChatAttachment(Kind.FILE, "l", null, null, "application/pdf"),
                "FILE requires non-null file");
        assertEquals("file", ex.getMessage(), "NPE message must name the file field");
    }

    @Test
    void fileWithRelativeFileConvertedToAbsolute() {
        File relative = new File("doc.pdf");
        ChatAttachment a = new ChatAttachment(Kind.FILE, "l", null, relative, "application/pdf");
        assertTrue(a.file().isAbsolute(), "file must be made absolute");
        assertEquals(relative.getAbsoluteFile(), a.file(), "absolute form must match");
    }

    @Test
    void fileWithNonNullTextIsNullifiedInConstructor() {
        ChatAttachment a = new ChatAttachment(Kind.FILE, "l", "ignored", new File("doc.pdf"), "application/pdf");
        assertNull(a.text(), "text must be nullified for non-PASTED_TEXT kinds");
    }

    // ---- compact constructor: mediaType normalization --------------------------------------

    @Test
    void constructorNullMediaTypeRemainsNull() {
        ChatAttachment a = new ChatAttachment(Kind.FILE, "l", null, new File("doc.pdf"), null);
        assertNull(a.mediaType(), "null mediaType must remain null");
    }

    @Test
    void constructorBlankMediaTypeBecomesNull() {
        ChatAttachment a = new ChatAttachment(Kind.FILE, "l", null, new File("doc.pdf"), "   ");
        assertNull(a.mediaType(), "blank mediaType must be normalized to null");
    }

    @Test
    void constructorEmptyMediaTypeBecomesNull() {
        ChatAttachment a = new ChatAttachment(Kind.FILE, "l", null, new File("doc.pdf"), "");
        assertNull(a.mediaType(), "empty mediaType must be normalized to null");
    }

    @Test
    void constructorNonBlankMediaTypePreserved() {
        ChatAttachment a = new ChatAttachment(Kind.FILE, "l", null, new File("doc.pdf"), "application/json");
        assertEquals("application/json", a.mediaType(), "non-blank mediaType must be preserved");
    }

    // ---- factory: pastedText ---------------------------------------------------------------

    @Test
    void pastedTextFactorySetsKindMediaTypeAndNullFile() {
        ChatAttachment a = ChatAttachment.pastedText("label", "the body");
        assertEquals(Kind.PASTED_TEXT, a.kind(), "kind must be PASTED_TEXT");
        assertEquals("text/plain", a.mediaType(), "mediaType must default to text/plain");
        assertNull(a.file(), "pastedText keeps file null");
        assertEquals("the body", a.text(), "text must be passed through");
        assertEquals("label", a.label(), "label must be passed through");
    }

    @Test
    void pastedTextFactoryPropagatesNullLabelValidation() {
        assertThrows(NullPointerException.class,
                () -> ChatAttachment.pastedText(null, "body"),
                "null label must propagate to constructor validation");
    }

    @Test
    void pastedTextFactoryPropagatesNullTextValidation() {
        assertThrows(NullPointerException.class,
                () -> ChatAttachment.pastedText("label", null),
                "null text must propagate to constructor validation");
    }

    // ---- factory: pastedTextFile -----------------------------------------------------------

    @Test
    void pastedTextFileFactorySetsKindAndMediaType() {
        ChatAttachment a = ChatAttachment.pastedTextFile("label", "body", new File("buf.txt"));
        assertEquals(Kind.PASTED_TEXT, a.kind(), "kind must be PASTED_TEXT");
        assertEquals("text/plain", a.mediaType(), "mediaType must be text/plain");
    }

    @Test
    void pastedTextFileFactoryNullFileThrowsNpeWithMessageFile() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> ChatAttachment.pastedTextFile("label", "body", null),
                "null file must be rejected by the factory");
        assertEquals("file", ex.getMessage(), "NPE message must name the file field");
    }

    @Test
    void pastedTextFileFactoryMakesFileAbsolute() {
        File relative = new File("buf.txt");
        ChatAttachment a = ChatAttachment.pastedTextFile("label", "body", relative);
        assertTrue(a.file().isAbsolute(), "buffer file must be made absolute");
        assertEquals(relative.getAbsoluteFile(), a.file(), "absolute form must match");
        assertEquals("body", a.text(), "text must be preserved alongside the buffer file");
    }

    // ---- factory: image --------------------------------------------------------------------

    @Test
    void imageFactorySetsKindImage() {
        ChatAttachment a = ChatAttachment.image("label", new File("pic.png"), "image/png");
        assertEquals(Kind.IMAGE, a.kind(), "kind must be IMAGE");
        assertNull(a.text(), "image has no text");
    }

    @Test
    void imageFactoryNullMediaTypeDefaultsToImageWildcard() {
        ChatAttachment a = ChatAttachment.image("label", new File("pic.png"), null);
        assertEquals("image/*", a.mediaType(), "null mediaType must default to image/*");
    }

    @Test
    void imageFactoryBlankMediaTypeDefaultsToImageWildcard() {
        ChatAttachment a = ChatAttachment.image("label", new File("pic.png"), "   ");
        assertEquals("image/*", a.mediaType(), "blank mediaType must default to image/*");
    }

    @Test
    void imageFactoryNonBlankMediaTypePreserved() {
        ChatAttachment a = ChatAttachment.image("label", new File("pic.png"), "image/jpeg");
        assertEquals("image/jpeg", a.mediaType(), "explicit mediaType must be preserved");
    }

    @Test
    void imageFactoryMakesFileAbsolute() {
        File relative = new File("pic.png");
        ChatAttachment a = ChatAttachment.image("label", relative, "image/png");
        assertEquals(relative.getAbsoluteFile(), a.file(), "image file must be made absolute");
    }

    @Test
    void imageFactoryPropagatesNullFileValidation() {
        assertThrows(NullPointerException.class,
                () -> ChatAttachment.image("label", null, "image/png"),
                "null file must propagate to constructor validation");
    }

    // ---- factory: file ---------------------------------------------------------------------

    @Test
    void fileFactorySetsKindFile() {
        ChatAttachment a = ChatAttachment.file("label", new File("doc.pdf"), "application/pdf");
        assertEquals(Kind.FILE, a.kind(), "kind must be FILE");
        assertNull(a.text(), "file kind has no text");
    }

    @Test
    void fileFactoryNullMediaTypePreservedAsNull() {
        ChatAttachment a = ChatAttachment.file("label", new File("doc.pdf"), null);
        assertNull(a.mediaType(), "file() does not default null mediaType (unlike image())");
    }

    @Test
    void fileFactoryBlankMediaTypeNormalizedToNullByConstructor() {
        // file() passes mediaType through as-is; the compact constructor normalizes blank -> null.
        ChatAttachment a = ChatAttachment.file("label", new File("doc.pdf"), "  ");
        assertNull(a.mediaType(), "blank mediaType is normalized to null by the constructor");
    }

    @Test
    void fileFactoryNonBlankMediaTypePreserved() {
        ChatAttachment a = ChatAttachment.file("label", new File("doc.pdf"), "application/pdf");
        assertEquals("application/pdf", a.mediaType(), "explicit mediaType must be preserved");
    }

    @Test
    void fileFactoryMakesFileAbsolute() {
        File relative = new File("doc.pdf");
        ChatAttachment a = ChatAttachment.file("label", relative, "application/pdf");
        assertEquals(relative.getAbsoluteFile(), a.file(), "file must be made absolute");
    }

    @Test
    void fileFactoryPropagatesNullFileValidation() {
        assertThrows(NullPointerException.class,
                () -> ChatAttachment.file("label", null, "application/pdf"),
                "null file must propagate to constructor validation");
    }

    // ---- placeholder() ---------------------------------------------------------------------

    @Test
    void placeholderWrapsLabelInBrackets() {
        ChatAttachment a = ChatAttachment.pastedText("Pasted content #1", "body");
        // label passed through unchanged (no sanitization on the raw factory input)
        assertEquals("[Pasted content #1]", a.placeholder(), "placeholder wraps label in brackets");
    }

    @Test
    void placeholderUsesTrimmedLabel() {
        ChatAttachment a = new ChatAttachment(Kind.PASTED_TEXT, "  Image 1  ", "b", null, "text/plain");
        assertEquals("[Image 1]", a.placeholder(), "placeholder uses the trimmed label");
    }

    // ---- sanitizeLabel() -------------------------------------------------------------------

    @Test
    void sanitizeLabelNullReturnsEmpty() {
        assertEquals("", ChatAttachment.sanitizeLabel(null), "null name yields empty string");
    }

    @Test
    void sanitizeLabelEmptyReturnsEmpty() {
        assertEquals("", ChatAttachment.sanitizeLabel(""), "empty name yields empty string");
    }

    @Test
    void sanitizeLabelReplacesOpenBracket() {
        assertEquals("a_b", ChatAttachment.sanitizeLabel("a[b"), "'[' replaced with '_'");
    }

    @Test
    void sanitizeLabelReplacesCloseBracket() {
        assertEquals("a_b", ChatAttachment.sanitizeLabel("a]b"), "']' replaced with '_'");
    }

    @Test
    void sanitizeLabelReplacesHash() {
        assertEquals("a_b", ChatAttachment.sanitizeLabel("a#b"), "'#' replaced with '_'");
    }

    @Test
    void sanitizeLabelReplacesAllProblematicChars() {
        assertEquals("___", ChatAttachment.sanitizeLabel("[]#"), "all unsafe chars become underscores");
    }

    @Test
    void sanitizeLabelLeavesSafeCharsUnchanged() {
        assertEquals("photo 2024.png", ChatAttachment.sanitizeLabel("photo 2024.png"),
                "safe characters must pass through unchanged");
    }

    @Test
    void sanitizeLabelReplacesOnlyUnsafeInMixedInput() {
        assertEquals("file_1_.txt", ChatAttachment.sanitizeLabel("file[1].txt"),
                "only the bracket chars are replaced");
    }

    // ---- record accessors: text/file per kind ----------------------------------------------

    @Test
    void textAccessorReturnsNullForImageAndFile() {
        assertNull(ChatAttachment.image("l", new File("p.png"), "image/png").text(),
                "image text must be null");
        assertNull(ChatAttachment.file("l", new File("d.pdf"), "application/pdf").text(),
                "file text must be null");
    }

    @Test
    void fileAccessorReturnsNullForInlinePastedText() {
        assertNull(ChatAttachment.pastedText("l", "body").file(),
                "inline pasted text has no buffer file");
    }

    // ---- record contract: equals / hashCode / toString -------------------------------------

    @Test
    void equalAttachmentsAreEqualAndShareHashCode() {
        ChatAttachment a = ChatAttachment.pastedText("l", "body");
        ChatAttachment b = ChatAttachment.pastedText("l", "body");
        assertEquals(a, b, "records with equal components must be equal");
        assertEquals(a.hashCode(), b.hashCode(), "equal records must share hashCode");
    }

    @Test
    void attachmentsDifferingInTextAreNotEqual() {
        ChatAttachment a = ChatAttachment.pastedText("l", "body1");
        ChatAttachment b = ChatAttachment.pastedText("l", "body2");
        assertNotEquals(a, b, "records differing in a component must not be equal");
    }

    @Test
    void attachmentsDifferingInKindAreNotEqual() {
        ChatAttachment a = ChatAttachment.image("l", new File("f"), "image/png");
        ChatAttachment b = ChatAttachment.file("l", new File("f"), "image/png");
        assertNotEquals(a, b, "different kinds must not be equal");
    }

    @Test
    void toStringContainsComponentValues() {
        ChatAttachment a = ChatAttachment.pastedText("myLabel", "body");
        String s = a.toString();
        assertTrue(s.contains("myLabel"), "toString should include the label component");
        assertTrue(s.contains("PASTED_TEXT"), "toString should include the kind component");
    }

    // ---- Kind enum -------------------------------------------------------------------------

    @Test
    void kindEnumHasExpectedConstants() {
        assertSame(Kind.PASTED_TEXT, Kind.valueOf("PASTED_TEXT"), "PASTED_TEXT must exist");
        assertSame(Kind.IMAGE, Kind.valueOf("IMAGE"), "IMAGE must exist");
        assertSame(Kind.FILE, Kind.valueOf("FILE"), "FILE must exist");
        assertEquals(3, Kind.values().length, "Kind must have exactly three constants");
    }
}
