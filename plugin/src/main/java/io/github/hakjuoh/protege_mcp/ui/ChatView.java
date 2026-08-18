package io.github.hakjuoh.protege_mcp.ui;

import static io.github.hakjuoh.protege_mcp.ui.ChatIcons.icon;
import static io.github.hakjuoh.protege_mcp.ui.ChatIcons.iconButton;

import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;
import io.github.hakjuoh.protege_mcp.chat.Providers;
import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.McpServerRegistry;
import io.github.hakjuoh.protege_mcp.ui.ChatIcons.Glyph;
import io.github.hakjuoh.protege_mcp.ui.ChatTranscriptPane.Kind;

import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Timer;

/**
 * In-Protégé chat assistant (Architecture Approach B). The user converses with a locally-installed
 * coding-agent CLI (selected from a provider drop-down) that is spawned, per turn, configured to
 * connect back to this window's running MCP server — so the assistant reads and edits the
 * <em>live</em> ontology through the existing tool layer, with GUI reflection, the shared undo
 * stack, and the read-only / confirm-write gates all inherited unchanged.
 *
 * <p>All subprocess I/O runs on a daemon worker; streamed output is coalesced onto the EDT via a
 * queue drained by a Swing {@link Timer}. {@link ChatTranscriptPane} owns Markdown streaming,
 * message spacing, links, and copy-as-Markdown interactions. The plugin stores no provider API key
 * — each CLI uses the user's existing login.
 */
public class ChatView extends AbstractOWLViewComponent {

    private static final long serialVersionUID = 1L;

    private static final String INTRO =
            "Ask about the active ontology, or ask for edits. The assistant runs your local CLI and"
                    + " edits through Protégé's MCP server (changes appear in the GUI and can be"
                    + " undone).\n";
    private static final String NO_CLI =
            "No coding-agent CLI found. Install Claude Code (`claude`), Codex (`codex`),"
                    + " Antigravity (`agy`), or OpenCode (`opencode`) and log in, then reopen this"
                    + " view. You can also set the CLI path in Preferences ▸ Ontology Assistant.\n";
    private ChatTranscriptPane transcript;
    private JTextArea input;
    private JButton sendButton;
    private JButton attachButton;
    private JButton stopButton;
    private JButton newChatButton;
    private ChatProviderControls providerControls;
    private ChatAttachmentController attachmentController;
    private ChatTurnController turnController;
    private JCheckBox confirmEdits;
    private JCheckBox showThinking;
    private JLabel statusLabel;
    private JLabel usageLabel;
    private JLabel workingLabel;
    private JPanel providerBar;

    private Timer statusTimer;

    @Override
    protected void initialiseOWLView() throws Exception {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildControlBar(), BorderLayout.NORTH);

        transcript =
                new ChatTranscriptPane(() -> turnController != null && turnController.isRunning());
        // Auto-scroll to the bottom of the stream is driven by setCaretPosition(end) after each
        // batch, under the caret's default update policy (same as before Markdown rendering). The
        // streaming re-render's remove + insert run inside one EDT event, so no intermediate caret
        // position or partial render is ever painted.
        JScrollPane scroll = new JScrollPane(transcript);
        scroll.setPreferredSize(new Dimension(560, 360));
        add(scroll, BorderLayout.CENTER);

        add(buildInputBar(), BorderLayout.SOUTH);
        turnController = buildTurnController();

        statusTimer = new Timer(1500, e -> refreshStatus());
        statusTimer.start();

        showIntro();
        if (Providers.available().isEmpty()) {
            setInputEnabled(false);
        }
        refreshStatus();

        // Registered last: everything the callback rebuilds now exists, so a catalog saved while
        // this view is open cannot arrive before the pickers it refreshes.
        providerControls.followCatalogEdits();
    }

    private JComponent buildControlBar() {
        // Only locally-installed CLIs appear in the picker. The Provider and Model pickers are
        // created here but laid out in the composer (just left of Send); see buildInputBar(). The
        // top bar keeps New chat and the edit/reasoning toggles.
        List<ChatProvider> available = Providers.available();
        providerControls = buildProviderControls(available);

        newChatButton = new JButton("New chat");
        newChatButton.addActionListener(e -> startNewConversation(true));

        confirmEdits =
                new JCheckBox(
                        "Confirm each edit",
                        McpConfig.prefs().getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));
        confirmEdits.setToolTipText(
                "Require a confirmation dialog before the assistant applies any edit "
                        + "(this is the MCP server's confirm-writes setting).");
        confirmEdits.addActionListener(
                e -> {
                    McpConfig.prefs()
                            .putBoolean(McpConfig.KEY_CONFIRM_WRITES, confirmEdits.isSelected());
                    refreshStatus();
                });

        showThinking =
                new JCheckBox(
                        "Show reasoning",
                        McpConfig.prefs().getBoolean(McpConfig.KEY_CHAT_SHOW_THINKING, false));
        showThinking.setToolTipText(
                "Ask the CLI for the model's reasoning and show it in the transcript "
                        + "(gray italics). Takes effect from the next message.");
        showThinking.addActionListener(
                e ->
                        McpConfig.prefs()
                                .putBoolean(
                                        McpConfig.KEY_CHAT_SHOW_THINKING,
                                        showThinking.isSelected()));

        providerBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        if (available.isEmpty()) {
            JLabel none =
                    new JLabel(
                            "No coding-agent CLI found — install a configured client, then reopen");
            none.setForeground(new Color(0xB00020));
            providerBar.add(none);
        }
        providerBar.add(newChatButton);
        providerBar.add(confirmEdits);
        providerBar.add(showThinking);

        // The live status strip is created here but laid out inside the composer's bottom row, so
        // the otherwise empty middle surfaces useful server, working, and token state.
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

    /**
     * Builds the picker group with conversation callbacks; package-private for headless wiring
     * tests.
     */
    ChatProviderControls buildProviderControls(List<ChatProvider> available) {
        return new ChatProviderControls(
                available,
                new ChatProviderControls.Listener() {
                    @Override
                    public void providerChanged(ChatProvider provider) {
                        onProviderChanged(provider);
                    }

                    @Override
                    public void modelChanged(String displayName) {
                        onModelChanged(displayName);
                    }

                    @Override
                    public void reasoningEffortChanged(String displayName) {
                        onReasoningEffortChanged(displayName);
                    }
                });
    }

    private JComponent buildInputBar() {
        input = new JTextArea(3, 40);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        // Enter sends; Shift+Enter inserts a newline.
        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send");
        input.getActionMap()
                .put(
                        "send",
                        new javax.swing.AbstractAction() {
                            private static final long serialVersionUID = 1L;

                            @Override
                            public void actionPerformed(java.awt.event.ActionEvent e) {
                                send();
                            }
                        });
        input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
        attachmentController = buildAttachmentController(input);
        attachmentController.installInputActions();
        input.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        // Composer layout: a "+" attaches at the bottom-left; the send button sits at the
        // bottom-right and is swapped in place for a stop button while a turn streams, so the two
        // share one slot.
        Color accent = new Color(0x1A4F8B);
        attachButton =
                iconButton(
                        icon(Glyph.PLUS, 22, new Color(0x555555), null), "Attach files or images");
        attachButton.addActionListener(e -> attachmentController.chooseFiles());
        sendButton = iconButton(icon(Glyph.SEND, 26, Color.WHITE, accent), "Send (Enter)");
        sendButton.addActionListener(e -> send());
        stopButton = iconButton(icon(Glyph.STOP, 26, Color.WHITE, new Color(0xD93025)), "Stop");
        stopButton.addActionListener(e -> stop());
        stopButton.setVisible(false); // only shown (in the send slot) while a turn is running

        JPanel sendSlot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        sendSlot.setOpaque(false);
        sendSlot.add(sendButton);
        sendSlot.add(stopButton);

        // Fill the gap between "+" and Send with status and working state on the left and tokens on
        // the right. GridBagLayout keeps the short labels vertically centered in the row.
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
        east.add(providerControls);
        east.add(sendSlot);

        JPanel controlRow = new JPanel(new BorderLayout());
        controlRow.setOpaque(false);
        controlRow.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        controlRow.add(attachButton, BorderLayout.WEST);
        controlRow.add(middleInfo, BorderLayout.CENTER);
        controlRow.add(east, BorderLayout.EAST);

        JScrollPane inputScroll =
                new JScrollPane(
                        input,
                        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel inputPanel = new JPanel(new BorderLayout(0, 2));
        inputPanel.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(0xC8C8C8)),
                        BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        inputPanel.add(inputScroll, BorderLayout.CENTER);
        inputPanel.add(controlRow, BorderLayout.SOUTH);

        JPanel bar = new JPanel(new BorderLayout());
        bar.add(inputPanel, BorderLayout.CENTER);
        return bar;
    }

    /** Connects attachment errors to this transcript; package-private for headless wiring tests. */
    ChatAttachmentController buildAttachmentController(JTextArea composer) {
        return new ChatAttachmentController(composer, this, message -> append(Kind.ERROR, message));
    }

    private ChatTurnController buildTurnController() {
        return new ChatTurnController(
                new ChatTurnController.Host() {
                    @Override
                    public void append(Kind kind, String text) {
                        ChatView.this.append(kind, text);
                    }

                    @Override
                    public void closeAssistantSegment(boolean offerCopy) {
                        transcript.closeAssistantSegment(offerCopy);
                    }

                    @Override
                    public boolean isTranscriptAtLineStart() {
                        return transcript.isAtLineStart();
                    }

                    @Override
                    public void showUsage(ChatUsage usage, boolean pending) {
                        usageLabel.setText(
                                pending ? "tokens: …" : usage == null ? " " : formatUsage(usage));
                    }

                    @Override
                    public void showWorkingSeconds(long seconds) {
                        workingLabel.setText(seconds < 0 ? " " : "● running   " + seconds + "s");
                    }

                    @Override
                    public void setTurnRunning(boolean running) {
                        setInputEnabled(!running);
                        showStop(running);
                    }

                    @Override
                    public void finishTurnAttachments() {
                        attachmentController.finishTurn();
                    }

                    @Override
                    public void focusComposer() {
                        input.requestFocusInWindow();
                    }
                });
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

    /** The opening transcript line: the usage hint, or the install hint when no CLI is present. */
    private void showIntro() {
        append(Kind.SYSTEM, Providers.available().isEmpty() ? NO_CLI : INTRO);
    }

    // ------------------------------------------------------------------ provider / model

    private void onProviderChanged(ChatProvider provider) {
        String sessionId = turnController.sessionId(provider.id());
        append(
                Kind.SYSTEM,
                "Provider: "
                        + provider.displayName()
                        + (sessionId != null && !sessionId.isBlank()
                                ? " — its CLI session will resume; missed turns will be handed"
                                        + " off.\n"
                                : " — joining the shared conversation; prior turns will be handed"
                                        + " off.\n"));
    }

    private void onModelChanged(String displayName) {
        String sessionId = selectedSessionId();
        append(
                Kind.SYSTEM,
                "Model: "
                        + displayName
                        + (sessionId == null || sessionId.isBlank()
                                ? ".\n"
                                : " — continuing this provider's conversation.\n"));
    }

    private void onReasoningEffortChanged(String displayName) {
        String sessionId = selectedSessionId();
        append(
                Kind.SYSTEM,
                "Reasoning effort: "
                        + displayName
                        + (sessionId == null || sessionId.isBlank()
                                ? ".\n"
                                : " — continuing this provider's conversation.\n"));
    }

    private ChatProvider currentProvider() {
        return providerControls == null ? null : providerControls.selectedProvider();
    }

    private String selectedSessionId() {
        ChatProvider provider = currentProvider();
        return provider == null || turnController == null
                ? null
                : turnController.sessionId(provider.id());
    }

    private void startNewConversation(boolean clearTranscript) {
        // Don't reset/clear underneath a streaming turn (the controls are disabled then, but guard
        // anyway).
        if (turnController.isRunning()) {
            return;
        }
        turnController.clearConversation();
        if (clearTranscript) {
            transcript.resetTranscript();
            usageLabel.setText(" ");
            attachmentController.resetConversation();
            showIntro();
            ChatProvider provider = currentProvider();
            if (provider != null) {
                append(
                        Kind.SYSTEM,
                        "Provider: " + provider.displayName() + " — new shared conversation.\n");
            }
        }
    }

    // ------------------------------------------------------------------ send / stop

    private void send() {
        if (turnController.isRunning()) {
            return;
        }
        String prompt = input.getText().trim();
        if (prompt.isEmpty()) {
            return;
        }
        ChatProvider provider = currentProvider();
        if (provider == null) {
            append(Kind.ERROR, "Select a provider first.\n");
            return;
        }
        McpServerController controller = controller();
        if (controller == null) {
            append(
                    Kind.ERROR,
                    "MCP server is not available in this window; cannot reach the ontology.\n");
            return;
        }
        if (!controller.isRunning() && controller.isUserStopped()) {
            // The user pressed Stop in the MCP Server view: the lazy start below must not override
            // that, so refuse here — before the prompt is consumed — with the way back spelled out.
            // (McpBoot.ensureStarted enforces the same latch for a Stop racing this check.)
            append(
                    Kind.ERROR,
                    "The MCP server in this window is stopped. "
                            + "Press Start in the MCP Server view to use the assistant again.\n");
            return;
        }

        ChatAttachmentController.TurnAttachments preparedAttachments =
                attachmentController.beginTurn(prompt);
        input.setText("");
        boolean reasoningOn = showThinking != null && showThinking.isSelected();
        boolean assistantWrites =
                McpConfig.prefs().getBoolean(McpConfig.KEY_CHAT_ALLOW_WRITES, true);
        turnController.start(
                new ChatTurnController.StartRequest(
                        provider,
                        controller,
                        prompt,
                        providerControls.selectedModel(),
                        providerControls.selectedReasoningEffort(),
                        reasoningOn,
                        assistantWrites,
                        preparedAttachments.attachments(),
                        preparedAttachments.droppedCount()));
    }

    private void stop() {
        turnController.stop();
    }

    private static String formatUsage(ChatUsage u) {
        return io.github.hakjuoh.protege_mcp.chat.ChatText.formatUsage(u);
    }

    // ------------------------------------------------------------------ transcript rendering (EDT)

    private void append(Kind kind, String text) {
        transcript.append(kind, text);
    }

    // ------------------------------------------------------------------ status / lifecycle

    private McpServerController controller() {
        return McpServerRegistry.get(getOWLEditorKit());
    }

    private void refreshStatus() {
        McpServerController c = controller();
        // Kept compact: the strip shares the composer's bottom row with the Provider/Model pickers.
        // Provider data-egress details live in Preferences and the manual rather than a blocking
        // dialog.
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
        statusLabel.setToolTipText(
                "MCP server status and edit mode · prompts/attachments go to your model "
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
        for (Component comp : new Component[] {sendButton, attachButton, input, newChatButton}) {
            if (comp != null) {
                comp.setEnabled(enabled);
            }
        }
        if (providerControls != null) {
            providerControls.setControlsEnabled(enabled);
        }
    }

    @Override
    protected void disposeOWLView() {
        // First, so a catalog saved during teardown cannot rebuild pickers being dismantled.
        if (providerControls != null) {
            providerControls.stopFollowingCatalogEdits();
        }
        if (statusTimer != null) {
            statusTimer.stop();
            statusTimer = null;
        }
        if (turnController != null) {
            turnController.dispose();
        }
        if (attachmentController != null) {
            attachmentController.dispose();
        }
    }
}
