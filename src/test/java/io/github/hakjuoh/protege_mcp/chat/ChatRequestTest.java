package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatRequestTest {

    private static final McpEndpoint ENDPOINT = new McpEndpoint("http://127.0.0.1:8123/mcp", "tok");

    @Test
    void providerPromptExpandsAttachmentPlaceholders(@TempDir Path dir) throws Exception {
        Path image = Files.writeString(dir.resolve("mock.png"), "not really an image");
        ChatAttachment pasted = ChatAttachment.pastedText("Pasted content #1: 2,100 chars", "long body");
        ChatAttachment img = ChatAttachment.image("Image #1", image.toFile(), "image/png");

        ChatRequest req = new ChatRequest("", "please inspect [Pasted content #1: 2,100 chars] and [Image #1]",
                null, ENDPOINT, List.of(pasted, img));

        String prompt = req.providerPrompt();
        assertTrue(prompt.contains("please inspect"));
        assertTrue(prompt.contains("[Pasted content #1: 2,100 chars]"));
        assertTrue(prompt.contains("long body"));
        assertTrue(prompt.contains("[Image #1]"));
        assertTrue(prompt.contains(image.toFile().getAbsolutePath()));
        assertEquals(List.of(image.toFile().getAbsoluteFile()), req.imageFiles());
        assertEquals(List.of(dir.toFile().getAbsoluteFile()), req.attachmentDirectories());
    }

    @Test
    void providerPromptIsUnchangedWithoutAttachments() {
        ChatRequest req = new ChatRequest("", "plain prompt", null, ENDPOINT);
        assertEquals("plain prompt", req.providerPrompt());
    }

    @Test
    void fileAttachmentExpandsToPathAndDirectory(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("notes.txt"), "hello");
        ChatRequest req = new ChatRequest("", "see [File #1: notes.txt]", null, ENDPOINT,
                List.of(ChatAttachment.file("File #1: notes.txt", doc.toFile(), null)));

        String prompt = req.providerPrompt();
        assertTrue(prompt.contains("Attached file path: " + doc.toFile().getAbsolutePath()));
        assertTrue(req.imageFiles().isEmpty(), "a plain file is not a native image attachment");
        assertEquals(List.of(dir.toFile().getAbsoluteFile()), req.attachmentDirectories());
    }

    @Test
    void mixedAttachmentsKeepOrderDedupeDirsAndSplitImages(@TempDir Path a, @TempDir Path b) throws Exception {
        Path img = Files.writeString(a.resolve("shot.png"), "img");
        Path doc1 = Files.writeString(a.resolve("one.txt"), "1");   // same dir as the image -> deduped
        Path doc2 = Files.writeString(b.resolve("two.txt"), "2");
        ChatRequest req = new ChatRequest("", "[Image #1] [File #1: one.txt] [File #2: two.txt]", null, ENDPOINT,
                List.of(ChatAttachment.image("Image #1", img.toFile(), "image/png"),
                        ChatAttachment.file("File #1: one.txt", doc1.toFile(), null),
                        ChatAttachment.file("File #2: two.txt", doc2.toFile(), null)));

        assertEquals(List.of(img.toFile().getAbsoluteFile()), req.imageFiles(),
                "only the image is passed via a native image flag");
        assertEquals(List.of(a.toFile().getAbsoluteFile(), b.toFile().getAbsoluteFile()),
                req.attachmentDirectories(), "parent dirs are deduped, in prompt order");
    }

    @Test
    void sanitizeLabelStripsCharactersThatCouldForgeAPlaceholder() {
        // A filename like "notes-[Image #1].csv" must not embed another attachment's "[Image #1]" placeholder.
        assertEquals("notes-_Image _1_.csv", ChatAttachment.sanitizeLabel("notes-[Image #1].csv"));
        assertEquals("plain.txt", ChatAttachment.sanitizeLabel("plain.txt"));
        assertEquals("", ChatAttachment.sanitizeLabel(null));
    }

    @Test
    void largePastedTextIsReferencedByPathNotInlined(@TempDir Path dir) throws Exception {
        Path buf = Files.writeString(dir.resolve("pasted.txt"), "BODYTEXT");
        ChatRequest req = new ChatRequest("", "see [Pasted content #1: 9,000 chars]", null, ENDPOINT,
                List.of(ChatAttachment.pastedTextFile("Pasted content #1: 9,000 chars", "BODYTEXT",
                        buf.toFile())));

        String prompt = req.providerPrompt();
        assertTrue(prompt.contains("saved to local file: " + buf.toFile().getAbsolutePath()));
        assertFalse(prompt.contains("Full pasted text:\nBODYTEXT"), "the body must not be inlined on the argv");
        assertEquals(List.of(dir.toFile().getAbsoluteFile()), req.attachmentDirectories());
    }
}
