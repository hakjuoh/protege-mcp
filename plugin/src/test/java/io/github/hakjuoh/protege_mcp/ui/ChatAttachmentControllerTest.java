package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.TransferHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;

@ExtendWith(EdtTestExtension.class)
class ChatAttachmentControllerTest {

    @Test
    void inputActionsInstallOnceAndHandleInlineAndLargeTextPaste() {
        JTextArea input = new JTextArea();
        List<String> errors = new ArrayList<>();
        ChatAttachmentController controller = controller(input, errors);
        try {
            controller.installInputActions();
            TransferHandler installed = input.getTransferHandler();
            controller.installInputActions();

            assertSame(installed, input.getTransferHandler());
            assertTrue(input.getActionMap().get("smart-backspace") != null);

            assertTrue(installed.importData(new TransferHandler.TransferSupport(
                    input, new StringSelection("short paste"))));
            assertEquals("short paste", input.getText());

            input.setText("");
            assertTrue(installed.importData(new TransferHandler.TransferSupport(
                    input, new StringSelection(repeat('x', 2000)))));
            assertTrue(input.getText().startsWith("[Pasted content #1:"));
            assertEquals(1, controller.pendingAttachments().size());
            assertTrue(errors.isEmpty());
        } finally {
            controller.dispose();
        }
    }

    @Test
    void pasteCompactionKeepsShortMultilineAndCompactsLargeMultiline() {
        ChatAttachmentController controller = controller(new JTextArea(), new ArrayList<>());
        try {
            assertFalse(controller.shouldAttachPastedText(null));
            assertFalse(controller.shouldAttachPastedText(""));
            assertTrue(controller.shouldAttachPastedText(repeat('x', 2000)));
            assertFalse(controller.shouldAttachPastedText(repeat('\n', 60)));

            StringBuilder manyLines = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                manyLines.append(repeat('a', 25)).append('\n');
            }
            assertTrue(controller.shouldAttachPastedText(manyLines.toString()));

            StringBuilder fewLines = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                fewLines.append(repeat('a', 160)).append('\n');
            }
            assertFalse(controller.shouldAttachPastedText(fewLines.toString()));
        } finally {
            controller.dispose();
        }
    }

    @Test
    void placeholderReplacesSelectionWithBoundarySpacingAndBackspaceRemovesIt() {
        JTextArea input = new JTextArea("beforeAFTER");
        ChatAttachmentController controller = controller(input, new ArrayList<>());
        try {
            input.select(6, 7);
            controller.attachPastedText("body");
            ChatAttachment attachment = controller.pendingAttachments().get(0);

            assertEquals("before " + attachment.placeholder() + " FTER", input.getText());
            input.setCaretPosition(7 + attachment.placeholder().length());

            assertTrue(controller.deletePlaceholderBefore());
            assertEquals("before  FTER", input.getText());
            assertTrue(controller.pendingAttachments().isEmpty());
            assertFalse(controller.deletePlaceholderBefore());
        } finally {
            controller.dispose();
        }
    }

    @Test
    void beginTurnKeepsReferencedAttachmentsAndCountsEditedAwayOnes() {
        JTextArea input = new JTextArea();
        ChatAttachmentController controller = controller(input, new ArrayList<>());
        try {
            controller.attachPastedText("first");
            controller.attachPastedText("second");
            List<ChatAttachment> pending = controller.pendingAttachments();

            ChatAttachmentController.TurnAttachments turn =
                    controller.beginTurn("use " + pending.get(0).placeholder());

            assertEquals(List.of(pending.get(0)), turn.attachments());
            assertEquals(1, turn.droppedCount());
            assertTrue(controller.pendingAttachments().isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> turn.attachments().add(pending.get(1)));
        } finally {
            controller.dispose();
        }
    }

    @Test
    void largePasteUsesScratchFileAndFinishTurnReclaimsIt() throws IOException {
        JTextArea input = new JTextArea();
        List<String> errors = new ArrayList<>();
        ChatAttachmentController controller = controller(input, errors);
        try {
            String text = repeat('z', 8001);
            controller.attachPastedText(text);
            ChatAttachment attachment = controller.pendingAttachments().get(0);
            File scratchFile = attachment.file();

            assertTrue(scratchFile.isFile());
            assertEquals(text, Files.readString(scratchFile.toPath()));
            ChatAttachmentController.TurnAttachments turn =
                    controller.beginTurn(attachment.placeholder());
            assertEquals(List.of(attachment), turn.attachments());

            controller.finishTurn();

            assertFalse(scratchFile.getParentFile().exists());
            assertTrue(errors.isEmpty());
        } finally {
            controller.dispose();
        }
    }

    @Test
    void editedAwayLargePasteIsReclaimedWhenTurnBegins() {
        ChatAttachmentController controller = controller(new JTextArea(), new ArrayList<>());
        try {
            controller.attachPastedText(repeat('z', 8001));
            File scratchDir = controller.pendingAttachments().get(0).file().getParentFile();

            ChatAttachmentController.TurnAttachments turn = controller.beginTurn("no placeholder");

            assertTrue(turn.attachments().isEmpty());
            assertEquals(1, turn.droppedCount());
            assertFalse(scratchDir.exists());
        } finally {
            controller.dispose();
        }
    }

    @Test
    void fileAttachmentsUseIsolatedCopiesAndPreserveOriginals(@TempDir Path temp) throws IOException {
        File textFile = temp.resolve("notes.txt").toFile();
        File imageFile = temp.resolve("diagram.png").toFile();
        Files.writeString(textFile.toPath(), "notes");
        Files.write(imageFile.toPath(), new byte[] { 1, 2, 3 });
        JTextArea input = new JTextArea();
        List<String> errors = new ArrayList<>();
        ChatAttachmentController controller = controller(input, errors);
        try {
            assertTrue(controller.attachFiles(List.of(textFile, imageFile)));
            List<ChatAttachment> pending = controller.pendingAttachments();

            assertEquals(2, pending.size());
            assertTrue(pending.get(0).label().startsWith("File #1:"));
            assertEquals("Image #1", pending.get(1).label());
            assertFalse(pending.get(0).file().equals(textFile));
            assertFalse(pending.get(1).file().equals(imageFile));
            assertTrue(pending.get(0).file().isFile());
            assertTrue(pending.get(1).file().isFile());
            assertTrue(textFile.isFile());
            assertTrue(imageFile.isFile());
            assertTrue(errors.isEmpty());
        } finally {
            controller.dispose();
        }
        assertTrue(textFile.isFile());
        assertTrue(imageFile.isFile());
    }

    @Test
    void deletingFilePlaceholderReclaimsItsIsolatedCopy(@TempDir Path temp) throws IOException {
        File source = temp.resolve("notes.txt").toFile();
        Files.writeString(source.toPath(), "notes");
        JTextArea input = new JTextArea();
        ChatAttachmentController controller = controller(input, new ArrayList<>());
        try {
            assertTrue(controller.attachFiles(List.of(source)));
            ChatAttachment attachment = controller.pendingAttachments().get(0);
            File scratchDir = attachment.file().getParentFile();
            input.setCaretPosition(input.getText().length());

            assertTrue(controller.deletePlaceholderBefore());

            assertTrue(controller.pendingAttachments().isEmpty());
            assertFalse(scratchDir.exists());
            assertTrue(source.isFile());
        } finally {
            controller.dispose();
        }
    }

    @Test
    void invalidAndOversizedFilesAreRejectedWithVisibleErrors(@TempDir Path temp)
            throws IOException {
        File directory = temp.resolve("folder").toFile();
        assertTrue(directory.mkdir());
        File oversized = temp.resolve("large.bin").toFile();
        try (RandomAccessFile file = new RandomAccessFile(oversized, "rw")) {
            file.setLength(26L * 1024 * 1024);
        }
        List<String> errors = new ArrayList<>();
        ChatAttachmentController controller = controller(new JTextArea(), errors);
        try {
            assertFalse(controller.attachFiles(null));
            assertFalse(controller.attachFiles(List.of()));
            assertFalse(controller.attachFiles(List.of(directory, oversized)));
            assertTrue(controller.pendingAttachments().isEmpty());
            assertTrue(errors.stream().anyMatch(message -> message.contains("non-file path")));
            assertTrue(errors.stream().anyMatch(message -> message.contains("Attachment too large")));
        } finally {
            controller.dispose();
        }
    }

    @Test
    void resetClearsPendingAttachmentsAndRestartsLabels() {
        JTextArea input = new JTextArea();
        ChatAttachmentController controller = controller(input, new ArrayList<>());
        try {
            controller.attachPastedText("before");
            assertTrue(controller.pendingAttachments().get(0).label().contains("#1"));

            controller.resetConversation();
            input.setText("");
            controller.attachPastedText("after");

            assertEquals(1, controller.pendingAttachments().size());
            assertTrue(controller.pendingAttachments().get(0).label().contains("#1"));
        } finally {
            controller.dispose();
        }
    }

    @Test
    void clipboardImageCompletesOffEdtAndIsReclaimedOnDispose() throws Exception {
        JTextArea input = new JTextArea();
        List<String> errors = new ArrayList<>();
        ChatAttachmentController controller = controller(input, errors);
        File scratchFile = null;
        try {
            assertFalse(controller.attachClipboardImage(null));
            BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
            assertTrue(controller.attachClipboardImage(image));
            await(() -> controller.pendingAttachments().size() == 1, 3000);
            ChatAttachment attachment = controller.pendingAttachments().get(0);
            scratchFile = attachment.file();

            assertEquals("Image #1", attachment.label());
            assertTrue(scratchFile.isFile());
            assertTrue(input.getText().contains(attachment.placeholder()));
            assertTrue(errors.isEmpty());
        } finally {
            controller.dispose();
        }
        assertTrue(scratchFile != null);
        assertFalse(scratchFile.getParentFile().exists());
    }

    @Test
    void unreadableClipboardImageReportsFailureAndReclaimsScratchDirectory() throws Exception {
        List<String> scratchBefore = scratchDirectories();
        List<String> errors = new ArrayList<>();
        ChatAttachmentController controller = controller(new JTextArea(), errors);
        try {
            assertTrue(controller.attachClipboardImage(
                    Toolkit.getDefaultToolkit().createImage(new byte[0])));

            await(() -> !controller.hasActiveImageWork(), 3000);

            assertTrue(controller.pendingAttachments().isEmpty());
            assertTrue(errors.stream().anyMatch(
                    message -> message.contains("clipboard image has no readable size")));
            assertEquals(scratchBefore, scratchDirectories());
        } finally {
            controller.dispose();
        }
    }

    @Test
    void resetInvalidatesClipboardImageStillBeingEncoded() throws Exception {
        ChatAttachmentController controller = controller(new JTextArea(), new ArrayList<>());
        try {
            assertTrue(controller.attachClipboardImage(
                    new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)));
            controller.resetConversation();

            await(() -> !controller.hasActiveImageWork(), 3000);

            assertTrue(controller.pendingAttachments().isEmpty());
        } finally {
            controller.dispose();
        }
    }

    @Test
    void bufferedImageConversionReusesAnExistingBufferedImage() throws IOException {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        assertSame(image, ChatAttachmentController.toBufferedImage(image));
    }

    @Test
    void errorMessagesPreferCausesAndFallBackToClassNames() {
        assertEquals("inner cause", ChatAttachmentController.message(
                new RuntimeException("outer", new IllegalStateException("inner cause"))));
        assertEquals("io failure", ChatAttachmentController.message(new IOException("io failure")));
        assertEquals("IllegalStateException",
                ChatAttachmentController.message(new IllegalStateException()));
        assertEquals("NullPointerException", ChatAttachmentController.message(
                new RuntimeException("outer", new NullPointerException())));
    }

    private static ChatAttachmentController controller(JTextArea input, List<String> errors) {
        return new ChatAttachmentController(input, null, errors::add);
    }

    private static String repeat(char character, int count) {
        return String.valueOf(character).repeat(count);
    }

    private static List<String> scratchDirectories() {
        File root = new File(CliSupport.neutralWorkingDir(), "attachments");
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null) {
            return List.of();
        }
        return Arrays.stream(directories)
                .map(File::getAbsolutePath)
                .sorted()
                .toList();
    }

    private static void await(BooleanSupplier condition, int timeoutMillis) {
        if (condition.getAsBoolean()) {
            return;
        }
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        AtomicBoolean timedOut = new AtomicBoolean();
        Timer poll = new Timer(10, event -> {
            if (condition.getAsBoolean()) {
                ((Timer) event.getSource()).stop();
                loop.exit();
            }
        });
        Timer timeout = new Timer(timeoutMillis, event -> {
            timedOut.set(true);
            ((Timer) event.getSource()).stop();
            poll.stop();
            loop.exit();
        });
        timeout.setRepeats(false);
        poll.start();
        timeout.start();
        loop.enter();
        timeout.stop();
        assertFalse(timedOut.get(), "timed out waiting for SwingWorker completion");
    }

}
