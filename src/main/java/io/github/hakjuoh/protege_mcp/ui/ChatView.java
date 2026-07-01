package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;
import io.github.hakjuoh.protege_mcp.chat.Providers;
import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.McpServerRegistry;

/**
 * In-Protégé chat assistant (Architecture Approach B). The user converses with a locally-installed
 * coding-agent CLI (selected from a provider drop-down) that is spawned, per turn, configured to
 * connect back to this window's running MCP server — so the assistant reads and edits the
 * <em>live</em> ontology through the existing tool layer, with GUI reflection, the shared undo stack,
 * and the read-only / confirm-write gates all inherited unchanged.
 *
 * <p>All subprocess I/O runs on a daemon worker; streamed output is coalesced onto the EDT via a queue
 * drained by a Swing {@link Timer}. The plugin stores no provider API key — each CLI uses the user's
 * existing login.
 */
public class ChatView extends AbstractOWLViewComponent {

    private static final long serialVersionUID = 1L;

    private static final String MODEL_DEFAULT_LABEL = "(default)";
    private static final String INTRO = "Ask about the active ontology, or ask for edits. The assistant "
            + "runs your local CLI and edits through Protégé's MCP server (changes appear in the GUI and "
            + "can be undone).\n";
    private static final String NO_CLI = "No coding-agent CLI found. Install Claude Code (`claude`) or "
            + "Codex (`codex`) and log in, then reopen this view. You can also set the CLI path in "
            + "Preferences ▸ Ontology Assistant.\n";
    private static final int PASTED_TEXT_ATTACHMENT_THRESHOLD = 2000;
    private static final int PASTED_TEXT_LINE_THRESHOLD = 50;
    /** A many-line paste is compacted only when it is also at least this large, so short multi-line
     *  pastes (lists, short stack traces) stay visible inline instead of vanishing behind a placeholder. */
    private static final int PASTED_TEXT_LINE_MIN_CHARS = 1500;
    /** Pasted bodies larger than this are buffered to a temp file and referenced by path, so a huge paste
     *  cannot overflow the single-argv command line (ARG_MAX). */
    private static final int PASTED_TEXT_INLINE_MAX = 8000;
    /** Files larger than this are refused (avoids copying huge files and oversized provider arguments). */
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private enum Kind { USER, ASSISTANT, TOOL, THINKING, ERROR, SYSTEM }

    private record Chunk(Kind kind, String text) {
    }

    private JTextPane transcript;
    private JTextArea input;
    private JButton sendButton;
    private JButton attachButton;
    private JButton stopButton;
    private JButton newChatButton;
    private JComboBox<ChatProvider> providerCombo;
    private JComboBox<String> modelCombo;
    private JCheckBox confirmEdits;
    private JCheckBox showThinking;
    private JLabel statusLabel;
    private JLabel usageLabel;
    private JLabel workingLabel;
    private JPanel providerBar;

    private ChatProvider currentProvider;

    private volatile ChatProcess currentProcess;
    private volatile String sessionId;
    private volatile Integer completedExit;
    private volatile ChatUsage lastUsage;
    private volatile ChatUsage liveUsage;
    private boolean atTurnStartOfLine = true;

    // Turn bookkeeping (EDT-only). The CLI handle is spawned off-EDT and published back on the EDT, so a
    // per-turn id lets a late publish tell whether its turn is still in flight, and a Stop pressed before
    // the handle exists is remembered until it can be honoured.
    private int turnSeq;
    private int activeTurn;            // id of the turn in flight, 0 when idle
    private boolean cancelRequested;   // Stop pressed during the launch window, before a handle exists

    private long turnStartMillis;

    private final Deque<Chunk> queue = new ArrayDeque<>();
    private Timer flushTimer;
    private Timer statusTimer;
    private Timer workingTimer;

    private final List<ChatAttachment> pendingAttachments = new ArrayList<>();
    // Each file-backed attachment lives alone in its own scratch subdir (so Claude's --add-dir grants access
    // to exactly that one file, never the user's real folder). Tracked here for prompt-time / turn-end cleanup.
    private final List<File> sessionTempDirs = new ArrayList<>();
    private List<ChatAttachment> inFlightAttachments = List.of();
    // Bumped whenever the conversation is reset/closed, so an in-flight clipboard-image worker can tell its
    // result belongs to a conversation that no longer exists and discard it instead of injecting a stale
    // attachment (whose scratch file was already reclaimed).
    private int attachGeneration;
    private int nextScratchSeq = 1;
    private int nextPastedTextIndex = 1;
    private int nextImageIndex = 1;
    private int nextFileIndex = 1;

    @Override
    protected void initialiseOWLView() throws Exception {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildControlBar(), BorderLayout.NORTH);

        transcript = new JTextPane();
        transcript.setEditable(false);
        JScrollPane scroll = new JScrollPane(transcript);
        scroll.setPreferredSize(new Dimension(560, 360));
        add(scroll, BorderLayout.CENTER);

        add(buildInputBar(), BorderLayout.SOUTH);

        flushTimer = new Timer(40, e -> drainQueue());
        workingTimer = new Timer(1000, e -> tickWorking());
        statusTimer = new Timer(1500, e -> refreshStatus());
        statusTimer.start();

        showIntro();
        if (Providers.available().isEmpty()) {
            setInputEnabled(false);
        }
        refreshStatus();
    }

    private JComponent buildControlBar() {
        // Only locally-installed CLIs appear in the picker. The Provider + Model pickers are created here but
        // laid out in the composer (just left of Send) — see buildInputBar(); the top bar keeps New chat and
        // the edit/reasoning toggles.
        List<ChatProvider> available = Providers.available();
        if (!available.isEmpty()) {
            String savedProvider = McpConfig.prefs().getString(McpConfig.KEY_CHAT_PROVIDER, "");
            providerCombo = new JComboBox<>();
            providerCombo.setToolTipText("Provider");
            providerCombo.setRenderer(new DefaultListCellRenderer() {
                private static final long serialVersionUID = 1L;

                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof ChatProvider p) {
                        setText(p.displayName());
                    }
                    return this;
                }
            });
            for (ChatProvider p : available) {
                providerCombo.addItem(p);
                if (currentProvider == null && (p.id().equals(savedProvider) || savedProvider.isEmpty())) {
                    currentProvider = p;
                }
            }
            if (currentProvider != null) {
                providerCombo.setSelectedItem(currentProvider);
            }
            // Fires on user change only (the initial selection above equals currentProvider -> no-op).
            providerCombo.addActionListener(e -> {
                Object sel = providerCombo.getSelectedItem();
                if (sel instanceof ChatProvider p && p != currentProvider) {
                    selectProvider(p);
                }
            });
            Dimension pc = providerCombo.getPreferredSize();
            providerCombo.setPreferredSize(new Dimension(96, pc.height));
        }

        modelCombo = new JComboBox<>();
        // Non-editable to match the Provider picker (an editable combo renders as a different, taller widget on
        // macOS Aqua, which is what made the two look mismatched and misaligned). Pick from the provider's list.
        modelCombo.setToolTipText("Model ((default) = the CLI's own default)");
        modelCombo.addActionListener(e -> onModelChanged());
        if (currentProvider != null) {
            populateModels(currentProvider);
        }
        Dimension mc = modelCombo.getPreferredSize();
        modelCombo.setPreferredSize(new Dimension(132, mc.height));

        newChatButton = new JButton("New chat");
        newChatButton.addActionListener(e -> startNewConversation(true));

        confirmEdits = new JCheckBox("Confirm each edit",
                McpConfig.prefs().getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));
        confirmEdits.setToolTipText("Require a confirmation dialog before the assistant applies any edit "
                + "(this is the MCP server's confirm-writes setting).");
        confirmEdits.addActionListener(e -> {
            McpConfig.prefs().putBoolean(McpConfig.KEY_CONFIRM_WRITES, confirmEdits.isSelected());
            refreshStatus();
        });

        showThinking = new JCheckBox("Show reasoning",
                McpConfig.prefs().getBoolean(McpConfig.KEY_CHAT_SHOW_THINKING, false));
        showThinking.addActionListener(e ->
                McpConfig.prefs().putBoolean(McpConfig.KEY_CHAT_SHOW_THINKING, showThinking.isSelected()));

        providerBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        if (available.isEmpty()) {
            JLabel none = new JLabel("No coding-agent CLI found — install Claude Code (claude) or Codex "
                    + "(codex), then reopen");
            none.setForeground(new Color(0xB00020));
            providerBar.add(none);
        }
        providerBar.add(newChatButton);
        providerBar.add(confirmEdits);
        providerBar.add(showThinking);

        // The live status strip (server/edits state, working indicator, token count) is created here but laid
        // out inside the composer's bottom row (between "+" and Send) — see buildInputBar() — so the otherwise
        // empty middle of the composer surfaces useful live info instead of wasting space.
        Font small = new JLabel().getFont().deriveFont(Font.PLAIN, 11f);
        Color muted = new Color(0x666666);
        statusLabel = new JLabel(" ");
        statusLabel.setFont(small);
        statusLabel.setForeground(muted);
        usageLabel = new JLabel(" ");
        usageLabel.setFont(small);
        usageLabel.setForeground(muted);
        workingLabel = new JLabel(" ");
        workingLabel.setFont(small);
        workingLabel.setForeground(new Color(0x1A4F8B));
        workingLabel.setHorizontalAlignment(JLabel.CENTER);

        return providerBar;
    }

    private JComponent buildInputBar() {
        input = new JTextArea(3, 40);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        // Enter sends; Shift+Enter inserts a newline.
        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send");
        input.getActionMap().put("send", new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                send();
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
        // Backspace next to a `[Image #N]`/`[File …]`/`[Pasted …]` placeholder removes the whole token (and
        // its attachment) at once, instead of nibbling it one bracket at a time.
        installSmartBackspace();
        // Replace the transfer handler so paste/drop can become attachments. We intentionally do NOT call
        // setDragEnabled(true): that only makes the field a drag *source*, and since this handler does not
        // implement export it would break the built-in drag-to-move-text. Drop import works regardless.
        input.setTransferHandler(new AttachmentTransferHandler(input.getTransferHandler()));
        input.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        // Composer layout: a "+" attaches at the bottom-left; the send button sits at the bottom-right and is
        // swapped in place for a stop button while a turn streams, so the two share one slot.
        Color accent = new Color(0x1A4F8B);
        attachButton = iconButton(icon(Glyph.PLUS, 22, new Color(0x555555), null), "Attach files or images");
        attachButton.addActionListener(e -> attachFilesFromChooser());
        sendButton = iconButton(icon(Glyph.SEND, 26, Color.WHITE, accent), "Send (Enter)");
        sendButton.addActionListener(e -> send());
        stopButton = iconButton(icon(Glyph.STOP, 26, Color.WHITE, new Color(0xD93025)), "Stop");
        stopButton.addActionListener(e -> stop());
        stopButton.setVisible(false);   // only shown (in the send slot) while a turn is running

        JPanel sendSlot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        sendSlot.setOpaque(false);
        sendSlot.add(sendButton);
        sendSlot.add(stopButton);

        // Fill the gap between "+" and Send with the live status strip (server/edits state · working · tokens).
        // Status text and the "● running Ns" indicator group on the LEFT (the indicator just after the status);
        // the token readout stays on the right. GridBagLayout (not FlowLayout) so the short labels are centered
        // vertically in the row — FlowLayout pins its row to the top, which made the strip sit too high.
        JPanel leftStatus = new JPanel(new java.awt.GridBagLayout());
        leftStatus.setOpaque(false);
        java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
        gbc.gridy = 0;
        gbc.anchor = java.awt.GridBagConstraints.CENTER;
        gbc.insets = new Insets(0, 0, 0, 12);
        leftStatus.add(statusLabel, gbc);
        gbc.insets = new Insets(0, 0, 0, 0);
        leftStatus.add(workingLabel, gbc);

        JPanel middleInfo = new JPanel(new BorderLayout(8, 0));
        middleInfo.setOpaque(false);
        middleInfo.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        middleInfo.add(leftStatus, BorderLayout.WEST);
        middleInfo.add(usageLabel, BorderLayout.EAST);

        // Right cluster: the Provider + Model pickers sit just to the LEFT of the send/stop button.
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        east.setOpaque(false);
        if (providerCombo != null) {
            east.add(providerCombo);
        }
        east.add(modelCombo);
        east.add(sendSlot);

        JPanel controlRow = new JPanel(new BorderLayout());
        controlRow.setOpaque(false);
        controlRow.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        controlRow.add(attachButton, BorderLayout.WEST);
        controlRow.add(middleInfo, BorderLayout.CENTER);
        controlRow.add(east, BorderLayout.EAST);

        JScrollPane inputScroll = new JScrollPane(input,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel inputPanel = new JPanel(new BorderLayout(0, 2));
        inputPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xC8C8C8)),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        inputPanel.add(inputScroll, BorderLayout.CENTER);
        inputPanel.add(controlRow, BorderLayout.SOUTH);

        JPanel bar = new JPanel(new BorderLayout());
        bar.add(inputPanel, BorderLayout.CENTER);
        return bar;
    }

    /** Show the stop button in the send slot while a turn runs; restore send when idle. */
    private void showStop(boolean running) {
        if (sendButton != null) {
            sendButton.setVisible(!running);
        }
        if (stopButton != null) {
            stopButton.setVisible(running);
            stopButton.setEnabled(running);
            Component slot = stopButton.getParent();
            if (slot != null) {
                slot.revalidate();
                slot.repaint();
            }
        }
    }

    private static JButton iconButton(Icon icon, String tooltip) {
        JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setMargin(new Insets(2, 2, 2, 2));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private enum Glyph { PLUS, SEND, STOP }

    /** A small flat-drawn composer icon: a plus, an up-arrow send (filled circle), or a stop square. */
    private static Icon icon(Glyph glyph, int size, Color fg, Color bg) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.translate(x, y);
                    float s = size;
                    if (bg != null) {
                        g2.setColor(bg);
                        g2.fill(new Ellipse2D.Float(0, 0, s, s));
                    }
                    g2.setColor(fg);
                    switch (glyph) {
                        case PLUS -> {
                            g2.setStroke(new BasicStroke(Math.max(1.6f, s * 0.11f),
                                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            float m = s * 0.24f;
                            g2.draw(new Line2D.Float(s / 2, m, s / 2, s - m));
                            g2.draw(new Line2D.Float(m, s / 2, s - m, s / 2));
                        }
                        case SEND -> {
                            g2.setStroke(new BasicStroke(Math.max(1.7f, s * 0.10f),
                                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            float cx = s / 2;
                            float top = s * 0.30f;
                            float bot = s * 0.72f;
                            float head = s * 0.17f;
                            g2.draw(new Line2D.Float(cx, top, cx, bot));
                            g2.draw(new Line2D.Float(cx, top, cx - head, top + head));
                            g2.draw(new Line2D.Float(cx, top, cx + head, top + head));
                        }
                        case STOP -> {
                            float m = s * 0.34f;
                            g2.fill(new RoundRectangle2D.Float(m, m, s - 2 * m, s - 2 * m, s * 0.08f, s * 0.08f));
                        }
                        default -> {
                        }
                    }
                } finally {
                    g2.dispose();
                }
            }
        };
    }

    private void installSmartBackspace() {
        Action deletePrev = input.getActionMap().get(javax.swing.text.DefaultEditorKit.deletePrevCharAction);
        input.getInputMap().put(KeyStroke.getKeyStroke("BACK_SPACE"), "smart-backspace");
        input.getActionMap().put("smart-backspace", new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!deletePlaceholderBefore() && deletePrev != null) {
                    deletePrev.actionPerformed(e);
                }
            }
        });
    }

    /**
     * If the caret sits immediately after an attachment placeholder (and there is no selection), delete the
     * whole {@code [ … ]} token and drop its attachment in one keystroke. Returns false to fall back to a
     * normal one-character backspace.
     */
    private boolean deletePlaceholderBefore() {
        if (input.getSelectionStart() != input.getSelectionEnd()) {
            return false;
        }
        int caret = input.getCaretPosition();
        String text = input.getText();
        if (caret <= 0 || caret > text.length() || text.charAt(caret - 1) != ']') {
            return false;
        }
        int open = text.lastIndexOf('[', caret - 1);
        if (open < 0) {
            return false;
        }
        String token = text.substring(open, caret);
        ChatAttachment match = null;
        for (ChatAttachment a : pendingAttachments) {
            if (a.placeholder().equals(token)) {
                match = a;
                break;
            }
        }
        if (match == null) {
            return false;   // a literal "[…]" the user typed, not an attachment — ordinary backspace
        }
        try {
            input.getDocument().remove(open, caret - open);
        } catch (BadLocationException ex) {
            return false;
        }
        pendingAttachments.remove(match);
        if (match.file() != null) {
            deleteScratchDir(match.file().getParentFile());
        }
        return true;
    }

    private void attachFilesFromChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        int choice = chooser.showOpenDialog(this);
        if (choice == JFileChooser.APPROVE_OPTION) {
            attachFiles(Arrays.asList(chooser.getSelectedFiles()));
        }
    }

    private final class AttachmentTransferHandler extends TransferHandler {
        private static final long serialVersionUID = 1L;

        private final TransferHandler delegate;

        AttachmentTransferHandler(TransferHandler delegate) {
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
                Transferable t = support.getTransferable();
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    return attachFiles(files);
                }
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    Image image = (Image) t.getTransferData(DataFlavor.imageFlavor);
                    return attachClipboardImage(image);
                }
                if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    String text = (String) t.getTransferData(DataFlavor.stringFlavor);
                    if (shouldAttachPastedText(text)) {
                        attachPastedText(text);
                    } else {
                        input.replaceSelection(text == null ? "" : text);
                    }
                    return true;
                }
            } catch (UnsupportedFlavorException | IOException | RuntimeException ex) {
                append(Kind.ERROR, "\n[error] Could not attach pasted or dropped content: "
                        + ex.getMessage() + "\n");
                return false;
            }
            return delegate != null && delegate.importData(support);
        }
    }

    private void moveCaretToDropLocation(TransferHandler.TransferSupport support) {
        if (!support.isDrop()) {
            return;
        }
        TransferHandler.DropLocation loc = support.getDropLocation();
        if (loc instanceof JTextComponent.DropLocation textLoc) {
            input.setCaretPosition(textLoc.getIndex());
        }
    }

    private boolean shouldAttachPastedText(String text) {
        return io.github.hakjuoh.protege_mcp.chat.ChatText.shouldAttachPastedText(text,
                PASTED_TEXT_ATTACHMENT_THRESHOLD, PASTED_TEXT_LINE_THRESHOLD, PASTED_TEXT_LINE_MIN_CHARS);
    }

    private void attachPastedText(String text) {
        String label = "Pasted content #" + nextPastedTextIndex++ + ": " + String.format("%,d", text.length())
                + " chars";
        ChatAttachment attachment;
        if (text.length() > PASTED_TEXT_INLINE_MAX) {
            // Too large to inline on the command line — buffer it to an isolated temp file and pass the path,
            // so the provider prompt stays bounded regardless of paste size.
            File dir = null;
            try {
                dir = newScratchDir();
                File file = new File(dir, "pasted-" + System.currentTimeMillis() + ".txt");
                Files.writeString(file.toPath(), text);
                restrict(file.toPath(), false);
                attachment = ChatAttachment.pastedTextFile(label, text, file);
            } catch (IOException ex) {
                // Re-inlining a body this large would just reintroduce the command-line overflow the temp file
                // exists to prevent, so drop it back into the visible input for the user to see and trim.
                append(Kind.ERROR, "\n[error] Could not buffer large pasted text; left it in the input box "
                        + "instead: " + ex.getMessage() + "\n");
                deleteScratchDir(dir);
                nextPastedTextIndex--;
                input.replaceSelection(text);
                return;
            }
        } else {
            attachment = ChatAttachment.pastedText(label, text);
        }
        pendingAttachments.add(attachment);
        insertAttachmentPlaceholder(attachment);
    }

    private boolean attachClipboardImage(Image image) throws IOException {
        if (image == null) {
            return false;
        }
        // Reserve the label/index and create the (cheap) scratch dir on the EDT; do the potentially heavy
        // encode + PNG write off the EDT so a large screenshot can't freeze the UI.
        final int idx = nextImageIndex++;
        final String label = "Image #" + idx;
        final File dir = newScratchDir();
        final File file = new File(dir, "image-" + System.currentTimeMillis() + "-" + idx + ".png");
        final int generation = attachGeneration;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                ImageIO.write(toBufferedImage(image), "png", file);
                restrict(file.toPath(), false);
                return null;
            }

            @Override
            protected void done() {
                if (generation != attachGeneration) {
                    // The conversation was reset/closed while we encoded — discard rather than inject a stale
                    // attachment whose scratch file deleteAllScratch() may already have reclaimed.
                    deleteScratchDir(dir);
                    return;
                }
                try {
                    get();
                    ChatAttachment attachment = ChatAttachment.image(label, file, "image/png");
                    pendingAttachments.add(attachment);
                    insertAttachmentPlaceholder(attachment);
                } catch (Exception ex) {
                    append(Kind.ERROR, "\n[error] Could not attach pasted image: " + causeMessage(ex) + "\n");
                    deleteScratchDir(dir);
                }
            }
        }.execute();
        return true;
    }

    private boolean attachFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return false;
        }
        boolean attached = false;
        for (File f : files) {
            if (f == null) {
                continue;
            }
            File source = f.getAbsoluteFile();
            if (!source.isFile()) {
                append(Kind.ERROR, "\n[error] Cannot attach non-file path: " + source + "\n");
                continue;
            }
            if (source.length() > MAX_ATTACHMENT_BYTES) {
                append(Kind.ERROR, "\n[error] Attachment too large (" + (source.length() / (1024 * 1024))
                        + " MB, max " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + " MB): " + source.getName()
                        + "\n");
                continue;
            }
            // Copy the user's file into its own isolated scratch dir and reference the copy, so granting the
            // provider read access to that dir never exposes the user's real folder contents.
            File dir = null;
            try {
                dir = newScratchDir();
                File copy = new File(dir, source.getName());
                Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
                restrict(copy.toPath(), false);
                ChatAttachment attachment;
                if (isImageFile(source)) {
                    attachment = ChatAttachment.image("Image #" + nextImageIndex++, copy, imageMediaType(source));
                } else {
                    attachment = ChatAttachment.file("File #" + nextFileIndex++ + ": "
                            + ChatAttachment.sanitizeLabel(source.getName()), copy, null);
                }
                pendingAttachments.add(attachment);
                insertAttachmentPlaceholder(attachment);
                attached = true;
            } catch (IOException ex) {
                append(Kind.ERROR, "\n[error] Could not attach " + source.getName() + ": "
                        + ex.getMessage() + "\n");
                deleteScratchDir(dir);   // null-safe: reclaim the just-created dir on a failed copy
            }
        }
        return attached;
    }

    private void insertAttachmentPlaceholder(ChatAttachment attachment) {
        String placeholder = attachment.placeholder();
        String current = input.getText();
        // replaceSelection() inserts at the selection START and deletes the selected range, so base the spacing
        // decision on the selection bounds — not getCaretPosition(), which is the selection END after a drag.
        int start = Math.max(0, Math.min(input.getSelectionStart(), current.length()));
        int end = Math.max(start, Math.min(input.getSelectionEnd(), current.length()));
        boolean addLeadingSpace = start > 0 && !Character.isWhitespace(current.charAt(start - 1));
        boolean addTrailingSpace = end < current.length() && !Character.isWhitespace(current.charAt(end));
        input.replaceSelection((addLeadingSpace ? " " : "") + placeholder + (addTrailingSpace ? " " : ""));
    }

    private static BufferedImage toBufferedImage(Image image) throws IOException {
        if (image instanceof BufferedImage b) {
            return b;
        }
        javax.swing.ImageIcon icon = new javax.swing.ImageIcon(image);
        int width = icon.getIconWidth();
        int height = icon.getIconHeight();
        if (width <= 0 || height <= 0) {
            throw new IOException("clipboard image has no readable size");
        }
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffered.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return buffered;
    }

    /** A fresh, owner-only scratch subdir holding exactly one attachment file; tracked for later cleanup. */
    private File newScratchDir() throws IOException {
        File root = new File(CliSupport.neutralWorkingDir(), "attachments");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("could not create attachment directory: " + root);
        }
        restrict(root.toPath(), true);
        File dir = new File(root, "att-" + System.currentTimeMillis() + "-" + (nextScratchSeq++));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create attachment directory: " + dir);
        }
        restrict(dir.toPath(), true);
        dir.deleteOnExit();
        sessionTempDirs.add(dir);
        return dir;
    }

    /** Best-effort owner-only permissions on POSIX filesystems (no-op on Windows / non-POSIX). */
    private static void restrict(java.nio.file.Path path, boolean directory) {
        Set<PosixFilePermission> perms = directory
                ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE)
                : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem or transient failure — fall back to the platform's default ACLs
        }
    }

    /** Delete one of our scratch dirs (and its single file). Guarded so only tracked dirs are removed. */
    private void deleteScratchDir(File dir) {
        if (dir != null && sessionTempDirs.remove(dir)) {
            deleteRecursively(dir);
        }
    }

    /** Delete the scratch dirs backing the given attachments (no-op for inline pasted text). */
    private void deleteScratchFor(Collection<ChatAttachment> attachments) {
        for (ChatAttachment a : attachments) {
            File f = a.file();
            if (f != null) {
                deleteScratchDir(f.getParentFile());
            }
        }
    }

    /** Remove every scratch dir created this conversation (New Chat / view close). */
    private void deleteAllScratch() {
        for (File dir : new ArrayList<>(sessionTempDirs)) {
            deleteRecursively(dir);
        }
        sessionTempDirs.clear();
    }

    private static void deleteRecursively(File f) {
        if (f == null) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }

    private static String causeMessage(Exception ex) {
        Throwable t = ex.getCause() != null ? ex.getCause() : ex;
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static boolean isImageFile(File file) {
        return io.github.hakjuoh.protege_mcp.chat.ChatText.isImageFileName(file.getName());
    }

    private static String imageMediaType(File file) {
        return io.github.hakjuoh.protege_mcp.chat.ChatText.imageMediaType(file.getName());
    }

    private List<ChatAttachment> activeAttachments(String displayPrompt) {
        if (pendingAttachments.isEmpty()) {
            return List.of();
        }
        List<ChatAttachment> active = new ArrayList<>();
        for (ChatAttachment attachment : pendingAttachments) {
            if (displayPrompt.contains(attachment.placeholder())) {
                active.add(attachment);
            }
        }
        return List.copyOf(active);
    }

    /** The opening transcript line: the usage hint, or the install hint when no CLI is present. */
    private void showIntro() {
        append(Kind.SYSTEM, Providers.available().isEmpty() ? NO_CLI : INTRO);
    }

    // ------------------------------------------------------------------ provider / model

    private void selectProvider(ChatProvider p) {
        currentProvider = p;
        McpConfig.prefs().putString(McpConfig.KEY_CHAT_PROVIDER, p.id());
        populateModels(p);
        startNewConversation(false);
        append(Kind.SYSTEM, "Provider: " + p.displayName() + ".\n");
    }

    private void populateModels(ChatProvider p) {
        modelCombo.removeAllItems();
        for (String m : p.listModels()) {
            modelCombo.addItem(m.isEmpty() ? MODEL_DEFAULT_LABEL : m);
        }
        String saved = McpConfig.prefs().getString(modelPrefKey(p), "");
        modelCombo.setSelectedItem(saved.isEmpty() ? MODEL_DEFAULT_LABEL : saved);
    }

    private void onModelChanged() {
        if (currentProvider == null) {
            return;
        }
        McpConfig.prefs().putString(modelPrefKey(currentProvider), selectedModel());
        // A model switch starts a fresh provider session (caches/threads are per model).
        sessionId = null;
    }

    private static String modelPrefKey(ChatProvider p) {
        return "codex".equals(p.id()) ? McpConfig.KEY_CHAT_MODEL_CODEX : McpConfig.KEY_CHAT_MODEL_CLAUDE;
    }

    /** The model id to pass to the provider ("" = the CLI's own default). */
    private String selectedModel() {
        Object sel = modelCombo.getSelectedItem();
        String s = sel == null ? "" : sel.toString().trim();
        return (s.isEmpty() || MODEL_DEFAULT_LABEL.equals(s)) ? "" : s;
    }

    private void startNewConversation(boolean clearTranscript) {
        // Don't reset/clear underneath a streaming turn (the controls are disabled then, but guard anyway).
        if (currentProcess != null && currentProcess.isAlive()) {
            return;
        }
        sessionId = null;
        if (clearTranscript) {
            transcript.setText("");
            atTurnStartOfLine = true;
            liveUsage = null;
            usageLabel.setText(" ");
            pendingAttachments.clear();
            attachGeneration++;   // invalidate any in-flight clipboard-image worker for the old conversation
            deleteAllScratch();
            nextScratchSeq = 1;
            nextPastedTextIndex = 1;
            nextImageIndex = 1;
            nextFileIndex = 1;
            showIntro();
        }
    }

    // ------------------------------------------------------------------ send / stop

    private void send() {
        if (currentProcess != null && currentProcess.isAlive()) {
            return;
        }
        String prompt = input.getText().trim();
        List<ChatAttachment> turnAttachments = activeAttachments(prompt);
        if (prompt.isEmpty()) {
            return;
        }
        if (currentProvider == null) {
            append(Kind.ERROR, "Select a provider first.\n");
            return;
        }
        if (!confirmEgress()) {
            return;
        }
        McpServerController controller = controller();
        if (controller == null) {
            append(Kind.ERROR, "MCP server is not available in this window; cannot reach the ontology.\n");
            return;
        }

        input.setText("");
        // Any pending attachment whose placeholder the user edited away is NOT sent — surface that and reclaim
        // its temp files instead of silently dropping it while the transcript still shows the mangled text.
        List<ChatAttachment> dropped = new ArrayList<>();
        for (ChatAttachment a : pendingAttachments) {
            if (!turnAttachments.contains(a)) {
                dropped.add(a);
            }
        }
        pendingAttachments.clear();
        inFlightAttachments = turnAttachments;
        if (!atTurnStartOfLine) {
            append(Kind.SYSTEM, "\n");
        }
        append(Kind.USER, "> " + prompt + "\n");
        if (!dropped.isEmpty()) {
            append(Kind.SYSTEM, "[note] " + dropped.size() + " attachment(s) were not referenced in your "
                    + "message and were not sent.\n");
            deleteScratchFor(dropped);
        }

        completedExit = null;
        lastUsage = null;
        liveUsage = null;
        currentProcess = null;
        final int turn = ++turnSeq;   // identifies this turn so a late handle-publish can't bleed across turns
        activeTurn = turn;
        cancelRequested = false;
        usageLabel.setText("tokens: …");
        setInputEnabled(false);
        showStop(true);
        startWorking();
        flushTimer.start();

        ChatProvider provider = currentProvider;
        String model = selectedModel();
        String resume = sessionId;

        Thread launcher = new Thread(() -> {
            try {
                if (!controller.isRunning()) {
                    controller.start();
                }
                McpEndpoint endpoint = new McpEndpoint(controller.getEndpointUrl(), controller.getToken());
                ChatRequest req = new ChatRequest(model, prompt, resume, endpoint, turnAttachments);
                ChatProcess proc = provider.startTurn(req, uiListener());
                // Publish on the EDT: if this turn already finalized (a fast turn) or the user hit Stop during
                // the launch window, publishProcess cancels the freshly-spawned process instead of leaking it.
                SwingUtilities.invokeLater(() -> publishProcess(turn, proc));
            } catch (Exception ex) {
                String msg = ex.getMessage();
                enqueue(Kind.ERROR, "Could not start " + provider.displayName() + ": "
                        + (msg == null ? ex.getClass().getSimpleName() : msg) + "\n");
                completedExit = -1;
            }
        }, "protege-chat-launch");
        launcher.setDaemon(true);
        launcher.start();
    }

    /** EDT: adopt the just-spawned process for the in-flight turn, or cancel it if the turn is already over. */
    private void publishProcess(int turn, ChatProcess proc) {
        if (turn != activeTurn) {
            // The turn finalized (or was superseded / the view disposed) before the handle arrived — don't
            // leave a stale handle or an orphan process running.
            proc.cancel();
            return;
        }
        currentProcess = proc;
        if (cancelRequested) {
            // Stop was pressed during the launch window, before a handle existed — honour it now.
            proc.cancel();
        }
    }

    private void stop() {
        if (activeTurn == 0) {
            return;   // nothing in flight
        }
        ChatProcess p = currentProcess;
        if (p != null) {
            enqueue(Kind.SYSTEM, "\n[stopped]\n");
            p.cancel();
        } else {
            // Still launching: no handle yet. Remember the request so publishProcess cancels on arrival.
            cancelRequested = true;
            enqueue(Kind.SYSTEM, "\n[stopping…]\n");
        }
    }

    private boolean confirmEgress() {
        if (McpConfig.prefs().getBoolean(McpConfig.KEY_CHAT_CONSENTED_V2, false)) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "The chat runs your local '" + currentProvider.id() + "' CLI, which sends your prompts, any "
                        + "attachments or pasted content you include, and the ontology content the assistant "
                        + "reads (entity names, labels, axioms) to the model provider.\n\nAttached files and "
                        + "images are made available to the CLI; for Claude, it is granted read access to a "
                        + "per-attachment temporary copy of each one.\n\nProtégé stores no API key — the CLI "
                        + "uses your existing login. Edits still obey the MCP server's read-only / confirm-write "
                        + "settings.\n\nContinue?",
                "Chat sends data to your model provider", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            McpConfig.prefs().putBoolean(McpConfig.KEY_CHAT_CONSENTED_V2, true);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ working indicator (EDT timer)

    private void startWorking() {
        turnStartMillis = System.currentTimeMillis();
        workingLabel.setText("● running   0s");
        workingTimer.start();
    }

    private void stopWorking() {
        if (workingTimer != null) {
            workingTimer.stop();
        }
        if (workingLabel != null) {
            workingLabel.setText(" ");
        }
    }

    /** Only the elapsed-seconds number changes; the rest of the indicator stays put. */
    private void tickWorking() {
        long secs = (System.currentTimeMillis() - turnStartMillis) / 1000;
        workingLabel.setText("● running   " + secs + "s");
    }

    // ------------------------------------------------------------------ streaming listener (off-EDT)

    private ChatListener uiListener() {
        return new ChatListener() {
            @Override
            public void onSessionId(String id) {
                sessionId = id;
            }

            @Override
            public void onAssistantText(String text) {
                enqueue(Kind.ASSISTANT, text);
            }

            @Override
            public void onThinking(String text) {
                // Always enqueue off-EDT; whether to render is decided on the EDT in append()
                // (reading the showThinking checkbox here would touch Swing off-EDT).
                enqueue(Kind.THINKING, text);
            }

            @Override
            public void onToolActivity(String summary) {
                enqueue(Kind.TOOL, "\n  ⚙ " + summary + "\n");
            }

            @Override
            public void onUsage(ChatUsage usage) {
                // Live token count: the EDT flush timer renders this each tick (no Swing touch here).
                liveUsage = usage;
            }

            @Override
            public void onResult(ChatUsage usage) {
                lastUsage = usage;
            }

            @Override
            public void onError(String message) {
                enqueue(Kind.ERROR, "\n[error] " + message + "\n");
            }

            @Override
            public void onComplete(int exitCode) {
                completedExit = exitCode;
            }
        };
    }

    private void enqueue(Kind kind, String text) {
        synchronized (queue) {
            queue.add(new Chunk(kind, text));
        }
    }

    /** EDT: drain queued chunks, refresh the live token count, then finalize once the process exits. */
    private void drainQueue() {
        Chunk c;
        while ((c = poll()) != null) {
            append(c.kind(), c.text());
        }
        ChatUsage lu = liveUsage;
        if (lu != null) {
            usageLabel.setText(formatUsage(lu));
        }
        Integer exit = completedExit;
        if (exit != null && isQueueEmpty()) {
            completedExit = null;   // clear before finalize so a later tick can't finalize twice
            finalizeTurn(exit);
        }
    }

    private Chunk poll() {
        synchronized (queue) {
            return queue.poll();
        }
    }

    private boolean isQueueEmpty() {
        synchronized (queue) {
            return queue.isEmpty();
        }
    }

    private void finalizeTurn(int exit) {
        currentProcess = null;
        activeTurn = 0;
        cancelRequested = false;
        // The CLI has exited, so it is done reading any attachment files — reclaim this turn's scratch dirs.
        if (!inFlightAttachments.isEmpty()) {
            deleteScratchFor(inFlightAttachments);
            inFlightAttachments = List.of();
        }
        if (flushTimer != null) {
            flushTimer.stop();
        }
        stopWorking();
        if (!atTurnStartOfLine) {
            append(Kind.SYSTEM, "\n");
        }
        ChatUsage u = lastUsage;
        if (u != null) {
            usageLabel.setText(formatUsage(u));
        }
        setInputEnabled(true);
        showStop(false);
        input.requestFocusInWindow();
    }

    private static String formatUsage(ChatUsage u) {
        return io.github.hakjuoh.protege_mcp.chat.ChatText.formatUsage(u);
    }

    // ------------------------------------------------------------------ transcript rendering (EDT)

    private void append(Kind kind, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Reasoning is rendered only when the (EDT-owned) toggle is on — checked here, on the EDT.
        if (kind == Kind.THINKING && (showThinking == null || !showThinking.isSelected())) {
            return;
        }
        StyledDocument doc = transcript.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), text, styleFor(kind));
        } catch (BadLocationException ignored) {
            return;
        }
        atTurnStartOfLine = text.endsWith("\n");
        transcript.setCaretPosition(doc.getLength());
    }

    private static SimpleAttributeSet styleFor(Kind kind) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        switch (kind) {
            case USER -> {
                StyleConstants.setBold(a, true);
                StyleConstants.setForeground(a, new Color(0x1A4F8B));
            }
            case ASSISTANT -> StyleConstants.setForeground(a, Color.BLACK);
            case TOOL -> {
                StyleConstants.setItalic(a, true);
                StyleConstants.setForeground(a, new Color(0x507030));
            }
            case THINKING -> {
                StyleConstants.setItalic(a, true);
                StyleConstants.setForeground(a, new Color(0x888888));
            }
            case ERROR -> StyleConstants.setForeground(a, new Color(0xB00020));
            case SYSTEM -> StyleConstants.setForeground(a, new Color(0x666666));
            default -> {
            }
        }
        return a;
    }

    // ------------------------------------------------------------------ status / lifecycle

    private McpServerController controller() {
        return McpServerRegistry.get(getOWLEditorKit());
    }

    private void refreshStatus() {
        McpServerController c = controller();
        // Kept compact: the strip now shares the composer's bottom row with the Provider/Model pickers. The
        // egress note lives in the one-time consent banner + Preferences, so it isn't repeated here.
        String server;
        if (c == null) {
            server = "server: n/a";
        } else if (c.isRunning()) {
            server = "server: running";
        } else {
            server = "server: stopped";
        }
        String mode;
        if (c == null) {
            mode = "";
        } else if (c.isReadOnly()) {
            mode = "  ·  read-only";
        } else if (c.isConfirmWrites()) {
            mode = "  ·  confirm-each";
        } else {
            mode = "  ·  writable";
        }
        statusLabel.setText(server + mode);
        statusLabel.setToolTipText("MCP server status and edit mode · prompts/attachments go to your model "
                + "provider via the CLI");
        if (confirmEdits != null && c != null) {
            boolean live = c.isConfirmWrites();
            if (confirmEdits.isSelected() != live) {
                confirmEdits.setSelected(live);
            }
        }
    }

    /** Enable/disable every control that mutates conversation state (everything but Stop). */
    private void setInputEnabled(boolean enabled) {
        for (Component comp : new Component[] {
                sendButton, attachButton, input, newChatButton, providerCombo, modelCombo
        }) {
            if (comp != null) {
                comp.setEnabled(enabled);
            }
        }
    }

    @Override
    protected void disposeOWLView() {
        if (statusTimer != null) {
            statusTimer.stop();
            statusTimer = null;
        }
        if (workingTimer != null) {
            workingTimer.stop();
            workingTimer = null;
        }
        if (flushTimer != null) {
            drainQueue();   // flush any buffered transcript before tearing down
            flushTimer.stop();
            flushTimer = null;
        }
        activeTurn = 0;     // so a handle still being spawned is cancelled (not adopted) when it publishes
        ChatProcess p = currentProcess;
        if (p != null) {
            p.cancel();     // non-blocking; the kill escalation runs off the EDT
            currentProcess = null;
        }
        attachGeneration++;   // invalidate any in-flight clipboard-image worker before tearing down
        deleteAllScratch();   // reclaim any attachment temp files this view created
    }
}
