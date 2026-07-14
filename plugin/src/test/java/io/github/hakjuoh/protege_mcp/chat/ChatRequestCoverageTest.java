package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Supplementary coverage for {@link ChatRequest}, filling the gaps left by {@link ChatRequestTest}:
 * the compact-constructor null-normalization / defensive-copy contract, the 4-arg convenience
 * constructor, plain accessor pass-through (including nulls), the empty-attachments and null-mediaType
 * edge cases, and the record's equals/hashCode/toString contract.
 */
class ChatRequestCoverageTest {

    private static final McpEndpoint ENDPOINT = new McpEndpoint("http://127.0.0.1:8123/mcp", "tok");

    // ---- compact constructor: normalization ----------------------------------------------------

    @Test
    void compactConstructorNormalizesNullPromptToEmptyString() {
        ChatRequest req = new ChatRequest("gpt", null, "s1", ENDPOINT, List.of());
        assertEquals("", req.prompt(), "null prompt must be normalized to an empty string");
    }

    @Test
    void compactConstructorNormalizesNullAttachmentsToEmptyList() {
        ChatRequest req = new ChatRequest("gpt", "hi", "s1", ENDPOINT, null);
        assertTrue(req.attachments().isEmpty(), "null attachments must become an empty list");
    }

    @Test
    void compactConstructorKeepsNonNullPromptUnchanged() {
        ChatRequest req = new ChatRequest("gpt", "  keep me  ", "s1", ENDPOINT, List.of());
        assertEquals("  keep me  ", req.prompt(), "a non-null prompt must not be trimmed or altered");
    }

    @Test
    void compactConstructorCopiesAttachmentsDefensively(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("a.txt"), "x");
        List<ChatAttachment> mutable = new ArrayList<>();
        mutable.add(ChatAttachment.file("File #1: a.txt", doc.toFile(), null));

        ChatRequest req = new ChatRequest("", "p", null, ENDPOINT, mutable);

        // Mutating the source list after construction must not affect the request.
        mutable.clear();
        assertEquals(1, req.attachments().size(), "attachments must be copied, not aliased");
    }

    @Test
    void attachmentsListIsImmutable(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("a.txt"), "x");
        ChatRequest req = new ChatRequest("", "p", null, ENDPOINT,
                List.of(ChatAttachment.file("File #1: a.txt", doc.toFile(), null)));
        assertThrows(UnsupportedOperationException.class,
                () -> req.attachments().add(ChatAttachment.pastedText("L", "t")),
                "the accessor must return an unmodifiable list");
    }

    // ---- accessors pass through ----------------------------------------------------------------

    @Test
    void accessorsReturnRecordFieldsIncludingNullsUnchanged() {
        ChatRequest req = new ChatRequest("my-model", "p", null, ENDPOINT, List.of());
        assertEquals("my-model", req.model(), "model() must pass through unchanged");
        assertNull(req.sessionId(), "sessionId() must pass through a null unchanged");
        assertSame(ENDPOINT, req.endpoint(), "endpoint() must return the same reference");
    }

    @Test
    void modelMayBeNullAndIsPassedThrough() {
        ChatRequest req = new ChatRequest(null, "p", "s", ENDPOINT, List.of());
        assertNull(req.model(), "model() must pass through a null unchanged");
    }

    @Test
    void nonNullSessionIdIsPreserved() {
        ChatRequest req = new ChatRequest("m", "p", "session-42", ENDPOINT, List.of());
        assertEquals("session-42", req.sessionId());
    }

    // ---- 4-arg convenience constructor ---------------------------------------------------------

    @Test
    void fourArgConstructorDelegatesWithEmptyAttachments() {
        ChatRequest req = new ChatRequest("m", "p", "s", ENDPOINT);
        assertTrue(req.attachments().isEmpty(), "4-arg constructor must default attachments to empty");
        assertEquals("m", req.model());
        assertEquals("p", req.prompt());
        assertEquals("s", req.sessionId());
        assertSame(ENDPOINT, req.endpoint());
    }

    @Test
    void fourArgConstructorAttachmentsListIsImmutable() {
        ChatRequest req = new ChatRequest("m", "p", "s", ENDPOINT);
        assertThrows(UnsupportedOperationException.class,
                () -> req.attachments().add(ChatAttachment.pastedText("L", "t")),
                "the default empty list must be immutable");
    }

    @Test
    void fourArgConstructorNormalizesNullPrompt() {
        ChatRequest req = new ChatRequest("m", null, "s", ENDPOINT);
        assertEquals("", req.prompt(), "4-arg path also runs the compact-constructor normalization");
    }

    // ---- providerPrompt edge cases -------------------------------------------------------------

    @Test
    void providerPromptWithEmptyAttachmentsReturnsNormalizedEmptyPrompt() {
        ChatRequest req = new ChatRequest("m", null, "s", ENDPOINT, List.of());
        assertEquals("", req.providerPrompt(),
                "with no attachments providerPrompt returns the (normalized) prompt as-is");
    }

    @Test
    void providerPromptPlacesHiddenHandoffBeforeTheVisiblePrompt() {
        ChatRequest req = new ChatRequest("m", "new question", "s", ENDPOINT, List.of(), false,
                "missing cross-provider turns");
        assertEquals("missing cross-provider turns\n\nnew question", req.providerPrompt());
        assertEquals("new question", req.prompt(), "the visible prompt remains unchanged");
    }

    @Test
    void nullHandoffNormalizesToEmpty() {
        ChatRequest req = new ChatRequest("m", "question", "s", ENDPOINT, List.of(), false, null);
        assertEquals("", req.handoffContext());
        assertEquals("question", req.providerPrompt());
    }

    @Test
    void providerPromptImageWithNullMediaTypeAppendsNoSuffix(@TempDir Path dir) throws Exception {
        Path image = Files.writeString(dir.resolve("m.png"), "img");
        // image(...) coerces a null mediaType to "image/*"; construct the record directly to get a true null.
        ChatAttachment img = new ChatAttachment(ChatAttachment.Kind.IMAGE, "Image #1", null, image.toFile(), null);
        ChatRequest req = new ChatRequest("", "[Image #1]", null, ENDPOINT, List.of(img));

        String prompt = req.providerPrompt();
        assertTrue(prompt.contains("Image file path: " + image.toFile().getAbsolutePath()),
                "the image path must be present");
        assertFalse(prompt.contains("("), "a null mediaType must produce no parenthesized suffix");
    }

    @Test
    void providerPromptFileWithMediaTypeAppendsParenthesizedSuffix(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("d.csv"), "a,b");
        ChatRequest req = new ChatRequest("", "[File #1]", null, ENDPOINT,
                List.of(ChatAttachment.file("File #1", doc.toFile(), "text/csv")));
        assertTrue(req.providerPrompt().contains("Attached file path: " + doc.toFile().getAbsolutePath()
                + " (text/csv)"), "a non-null mediaType must be appended in parentheses");
    }

    @Test
    void providerPromptSmallPastedTextIsInlined() {
        ChatRequest req = new ChatRequest("", "[Pasted content #1]", null, ENDPOINT,
                List.of(ChatAttachment.pastedText("Pasted content #1", "INLINE_BODY")));
        String prompt = req.providerPrompt();
        assertTrue(prompt.contains("Full pasted text:\nINLINE_BODY\nEnd pasted text."),
                "a small pasted body (no file) must be inlined");
    }

    // ---- imageFiles ----------------------------------------------------------------------------

    @Test
    void imageFilesEmptyWhenNoAttachments() {
        ChatRequest req = new ChatRequest("m", "p", "s", ENDPOINT);
        assertTrue(req.imageFiles().isEmpty(), "no attachments => no image files");
    }

    // ---- attachmentDirectories -----------------------------------------------------------------

    @Test
    void attachmentDirectoriesEmptyWhenNoAttachments() {
        ChatRequest req = new ChatRequest("m", "p", "s", ENDPOINT);
        assertTrue(req.attachmentDirectories().isEmpty(),
                "no attachments => no attachment directories (empty, not null)");
    }

    @Test
    void attachmentDirectoriesSkipsNullFilePastedText() {
        // A small pasted-text attachment has file == null, so it contributes no directory.
        ChatRequest req = new ChatRequest("", "[Pasted content #1]", null, ENDPOINT,
                List.of(ChatAttachment.pastedText("Pasted content #1", "body")));
        assertTrue(req.attachmentDirectories().isEmpty(),
                "an attachment with a null file must be skipped");
    }

    @Test
    void attachmentDirectoriesSkipsRootFileWithNoParent() {
        // A filesystem-root file has no parent; it must be skipped rather than throw or add null.
        File root = File.listRoots()[0];
        assertNull(root.getAbsoluteFile().getParentFile(),
                "precondition: a root file has no parent directory");
        ChatAttachment atRoot = ChatAttachment.file("File #root", root, null);
        ChatRequest req = new ChatRequest("", "[File #root]", null, ENDPOINT, List.of(atRoot));
        assertTrue(req.attachmentDirectories().isEmpty(),
                "a root file (null parent) must not contribute a directory");
    }

    @Test
    void attachmentDirectoriesIsImmutable(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("d.txt"), "x");
        ChatRequest req = new ChatRequest("", "[File #1]", null, ENDPOINT,
                List.of(ChatAttachment.file("File #1", doc.toFile(), null)));
        List<File> dirs = req.attachmentDirectories();
        assertThrows(UnsupportedOperationException.class, () -> dirs.add(new File("/tmp")),
                "attachmentDirectories must return an unmodifiable list");
    }

    // ---- record contract -----------------------------------------------------------------------

    @Test
    void equalHashAndValueForTwoIdenticalRequests() {
        ChatRequest a = new ChatRequest("m", "p", "s", ENDPOINT, List.of());
        ChatRequest b = new ChatRequest("m", "p", "s", ENDPOINT, List.of());
        assertEquals(a, b, "records with equal components must be equal");
        assertEquals(a.hashCode(), b.hashCode(), "equal records must have equal hash codes");
    }

    @Test
    void unequalWhenAComponentDiffers() {
        ChatRequest base = new ChatRequest("m", "p", "s", ENDPOINT, List.of());
        assertNotEquals(base, new ChatRequest("other", "p", "s", ENDPOINT, List.of()),
                "differing model must break equality");
        assertNotEquals(base, new ChatRequest("m", "different", "s", ENDPOINT, List.of()),
                "differing prompt must break equality");
    }

    @Test
    void nullVsEmptyPromptRequestsAreEqualAfterNormalization() {
        ChatRequest fromNull = new ChatRequest("m", null, "s", ENDPOINT, List.of());
        ChatRequest fromEmpty = new ChatRequest("m", "", "s", ENDPOINT, List.of());
        assertEquals(fromNull, fromEmpty,
                "a null prompt normalizes to \"\", so both requests must be equal");
    }

    @Test
    void toStringMentionsComponentValues() {
        ChatRequest req = new ChatRequest("mdl", "hello", "sess", ENDPOINT, List.of());
        String s = req.toString();
        assertTrue(s.contains("mdl"), "toString should include the model");
        assertTrue(s.contains("hello"), "toString should include the prompt");
        assertTrue(s.contains("sess"), "toString should include the sessionId");
    }
}
