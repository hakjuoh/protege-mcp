package io.github.hakjuoh.protege_mcp.ui;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.Action;
import javax.swing.JFileChooser;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;

import io.github.hakjuoh.protege_mcp.chat.AttachmentFileManager;
import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatComposer;
import io.github.hakjuoh.protege_mcp.chat.ChatText;

/**
 * Owns the chat composer's attachment UI, numbering, and scratch-file lifecycle.
 *
 * <p>All methods are EDT-only. Clipboard image encoding runs in a {@link SwingWorker}; its result is
 * admitted on the EDT only if the conversation generation is still current. Errors are returned to
 * the containing view through a narrow text callback, leaving transcript presentation outside this
 * controller.
 */
final class ChatAttachmentController {

    private static final int PASTED_TEXT_ATTACHMENT_THRESHOLD = 2000;
    private static final int PASTED_TEXT_LINE_THRESHOLD = 50;
    /** Short multi-line pastes remain visible instead of disappearing behind a placeholder. */
    private static final int PASTED_TEXT_LINE_MIN_CHARS = 1500;
    /** Larger pasted bodies use a file path, avoiding an oversized provider command line. */
    private static final int PASTED_TEXT_INLINE_MAX = 8000;
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    record TurnAttachments(List<ChatAttachment> attachments, int droppedCount) {
        TurnAttachments {
            attachments = List.copyOf(attachments);
        }
    }

    private final JTextArea input;
    private final Component dialogParent;
    private final Consumer<String> errorSink;
    private final AttachmentFileManager files;
    private final List<ChatAttachment> pending = new ArrayList<>();

    private List<ChatAttachment> inFlight = List.of();
    private int generation;
    private int nextPastedTextIndex = 1;
    private int nextImageIndex = 1;
    private int nextFileIndex = 1;
    private int activeImageWorkers;
    private boolean inputActionsInstalled;

    ChatAttachmentController(JTextArea input, Component dialogParent, Consumer<String> errorSink) {
        this.input = Objects.requireNonNull(input);
        this.dialogParent = dialogParent;
        this.errorSink = Objects.requireNonNull(errorSink);
        this.files = new AttachmentFileManager();
    }

    /** Installs paste/drop import and whole-placeholder Backspace behavior exactly once. */
    void installInputActions() {
        if (inputActionsInstalled) {
            return;
        }
        inputActionsInstalled = true;
        installSmartBackspace();
        input.setTransferHandler(new AttachmentTransferHandler(input.getTransferHandler()));
    }

    void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(dialogParent) == JFileChooser.APPROVE_OPTION) {
            attachFiles(Arrays.asList(chooser.getSelectedFiles()));
        }
    }

    /** Moves referenced attachments into the active turn and reclaims edited-away placeholders. */
    TurnAttachments beginTurn(String prompt) {
        List<ChatAttachment> active = ChatComposer.activeAttachments(pending, prompt);
        List<ChatAttachment> dropped = new ArrayList<>();
        for (ChatAttachment attachment : pending) {
            if (!active.contains(attachment)) {
                dropped.add(attachment);
            }
        }
        pending.clear();
        inFlight = active;
        files.deleteScratchFor(dropped);
        return new TurnAttachments(active, dropped.size());
    }

    /** Reclaims files after the provider process has finished reading the active turn. */
    void finishTurn() {
        files.deleteScratchFor(inFlight);
        inFlight = List.of();
    }

    /** Invalidates asynchronous work and restores attachment numbering for a new conversation. */
    void resetConversation() {
        generation++;
        if (activeImageWorkers == 0) {
            files.reset();
        } else {
            // An encoder owns a tracked scratch dir that it will remove when it observes the stale
            // generation. Reclaim only attachments already visible to this conversation for now.
            files.deleteScratchFor(pending);
            files.deleteScratchFor(inFlight);
        }
        pending.clear();
        inFlight = List.of();
        nextPastedTextIndex = 1;
        nextImageIndex = 1;
        nextFileIndex = 1;
    }

    /** Invalidates asynchronous work and reclaims every scratch file retained by this controller. */
    void dispose() {
        generation++;
        if (activeImageWorkers == 0) {
            files.deleteAllScratch();
        } else {
            files.deleteScratchFor(pending);
            files.deleteScratchFor(inFlight);
        }
        pending.clear();
        inFlight = List.of();
    }

    boolean shouldAttachPastedText(String text) {
        return ChatText.shouldAttachPastedText(text, PASTED_TEXT_ATTACHMENT_THRESHOLD,
                PASTED_TEXT_LINE_THRESHOLD, PASTED_TEXT_LINE_MIN_CHARS);
    }

    void attachPastedText(String text) {
        String label = "Pasted content #" + nextPastedTextIndex++ + ": "
                + String.format("%,d", text.length()) + " chars";
        ChatAttachment attachment;
        if (text.length() > PASTED_TEXT_INLINE_MAX) {
            File dir = null;
            try {
                dir = files.newScratchDir();
                File file = new File(dir, "pasted-" + System.currentTimeMillis() + ".txt");
                Files.writeString(file.toPath(), text);
                AttachmentFileManager.restrict(file.toPath(), false);
                attachment = ChatAttachment.pastedTextFile(label, text, file);
            } catch (IOException ex) {
                reportError("\nCould not buffer large pasted text; left it in the input box instead: "
                        + message(ex) + "\n");
                files.deleteScratchDir(dir);
                nextPastedTextIndex--;
                input.replaceSelection(text);
                return;
            }
        } else {
            attachment = ChatAttachment.pastedText(label, text);
        }
        pending.add(attachment);
        insertPlaceholder(attachment);
    }

    boolean attachClipboardImage(Image image) throws IOException {
        if (image == null) {
            return false;
        }
        int index = nextImageIndex++;
        String label = "Image #" + index;
        File dir = files.newScratchDir();
        File file = new File(dir,
                "image-" + System.currentTimeMillis() + "-" + index + ".png");
        int attachmentGeneration = generation;
        activeImageWorkers++;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (!ImageIO.write(toBufferedImage(image), "png", file)) {
                    throw new IOException("no PNG encoder is available");
                }
                AttachmentFileManager.restrict(file.toPath(), false);
                return null;
            }

            @Override
            protected void done() {
                try {
                    if (attachmentGeneration != generation) {
                        files.deleteScratchDir(dir);
                        return;
                    }
                    get();
                    ChatAttachment attachment = ChatAttachment.image(label, file, "image/png");
                    pending.add(attachment);
                    insertPlaceholder(attachment);
                } catch (Exception ex) {
                    reportError("\nCould not attach pasted image: " + message(ex) + "\n");
                    files.deleteScratchDir(dir);
                } finally {
                    activeImageWorkers--;
                }
            }
        }.execute();
        return true;
    }

    boolean attachFiles(List<File> selectedFiles) {
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            return false;
        }
        boolean attached = false;
        for (File selected : selectedFiles) {
            if (selected == null) {
                continue;
            }
            File source = selected.getAbsoluteFile();
            if (!source.isFile()) {
                reportError("\nCannot attach non-file path: " + source + "\n");
                continue;
            }
            if (source.length() > MAX_ATTACHMENT_BYTES) {
                reportError("\nAttachment too large (" + (source.length() / (1024 * 1024))
                        + " MB, max " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + " MB): "
                        + source.getName() + "\n");
                continue;
            }
            File dir = null;
            try {
                dir = files.newScratchDir();
                File copy = new File(dir, source.getName());
                Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
                AttachmentFileManager.restrict(copy.toPath(), false);
                ChatAttachment attachment;
                if (ChatText.isImageFileName(source.getName())) {
                    attachment = ChatAttachment.image(
                            "Image #" + nextImageIndex++, copy,
                            ChatText.imageMediaType(source.getName()));
                } else {
                    attachment = ChatAttachment.file(
                            "File #" + nextFileIndex++ + ": "
                                    + ChatAttachment.sanitizeLabel(source.getName()),
                            copy, null);
                }
                pending.add(attachment);
                insertPlaceholder(attachment);
                attached = true;
            } catch (IOException ex) {
                reportError("\nCould not attach " + source.getName() + ": " + message(ex) + "\n");
                files.deleteScratchDir(dir);
            }
        }
        return attached;
    }

    boolean deletePlaceholderBefore() {
        if (input.getSelectionStart() != input.getSelectionEnd()) {
            return false;
        }
        ChatComposer.PlaceholderMatch match = ChatComposer.matchPlaceholderBefore(
                input.getText(), input.getCaretPosition(), pending);
        if (match == null) {
            return false;
        }
        try {
            input.getDocument().remove(match.start(), match.end() - match.start());
        } catch (BadLocationException ex) {
            return false;
        }
        ChatAttachment attachment = match.attachment();
        pending.remove(attachment);
        if (attachment.file() != null) {
            files.deleteScratchDir(attachment.file().getParentFile());
        }
        return true;
    }

    List<ChatAttachment> pendingAttachments() {
        return List.copyOf(pending);
    }

    boolean hasActiveImageWork() {
        return activeImageWorkers > 0;
    }

    private void installSmartBackspace() {
        Action deletePrevious = input.getActionMap().get(DefaultEditorKit.deletePrevCharAction);
        input.getInputMap().put(KeyStroke.getKeyStroke("BACK_SPACE"), "smart-backspace");
        input.getActionMap().put("smart-backspace", new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!deletePlaceholderBefore() && deletePrevious != null) {
                    deletePrevious.actionPerformed(event);
                }
            }
        });
    }

    private final class AttachmentTransferHandler extends TransferHandler {
        private static final long serialVersionUID = 1L;

        private final TransferHandler delegate;

        private AttachmentTransferHandler(TransferHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    || support.isDataFlavorSupported(DataFlavor.imageFlavor)
                    || support.isDataFlavorSupported(DataFlavor.stringFlavor)
                    || (delegate != null && delegate.canImport(support));
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                moveCaretToDropLocation(support);
                Transferable transferable = support.getTransferable();
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @SuppressWarnings("unchecked")
                    List<File> selectedFiles = (List<File>) transferable.getTransferData(
                            DataFlavor.javaFileListFlavor);
                    return attachFiles(selectedFiles);
                }
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    return attachClipboardImage(
                            (Image) transferable.getTransferData(DataFlavor.imageFlavor));
                }
                if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    String text = (String) transferable.getTransferData(DataFlavor.stringFlavor);
                    if (shouldAttachPastedText(text)) {
                        attachPastedText(text);
                    } else {
                        input.replaceSelection(text == null ? "" : text);
                    }
                    return true;
                }
            } catch (UnsupportedFlavorException | IOException | RuntimeException ex) {
                reportError("\nCould not attach pasted or dropped content: " + message(ex) + "\n");
                return false;
            }
            return delegate != null && delegate.importData(support);
        }
    }

    private void moveCaretToDropLocation(TransferHandler.TransferSupport support) {
        if (!support.isDrop()) {
            return;
        }
        TransferHandler.DropLocation location = support.getDropLocation();
        if (location instanceof JTextComponent.DropLocation textLocation) {
            input.setCaretPosition(textLocation.getIndex());
        }
    }

    private void insertPlaceholder(ChatAttachment attachment) {
        String current = input.getText();
        int start = Math.max(0, Math.min(input.getSelectionStart(), current.length()));
        int end = Math.max(start, Math.min(input.getSelectionEnd(), current.length()));
        boolean leadingSpace = start > 0 && !Character.isWhitespace(current.charAt(start - 1));
        boolean trailingSpace = end < current.length() && !Character.isWhitespace(current.charAt(end));
        input.replaceSelection((leadingSpace ? " " : "") + attachment.placeholder()
                + (trailingSpace ? " " : ""));
    }

    static BufferedImage toBufferedImage(Image image) throws IOException {
        if (image instanceof BufferedImage buffered) {
            return buffered;
        }
        javax.swing.ImageIcon icon = new javax.swing.ImageIcon(image);
        int width = icon.getIconWidth();
        int height = icon.getIconHeight();
        if (width <= 0 || height <= 0) {
            throw new IOException("clipboard image has no readable size");
        }
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return buffered;
    }

    private void reportError(String message) {
        errorSink.accept(message);
    }

    static String message(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
