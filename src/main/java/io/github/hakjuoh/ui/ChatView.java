package io.github.hakjuoh.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import io.github.hakjuoh.chat.ChatListener;
import io.github.hakjuoh.chat.ChatProcess;
import io.github.hakjuoh.chat.ChatProvider;
import io.github.hakjuoh.chat.ChatRequest;
import io.github.hakjuoh.chat.ChatUsage;
import io.github.hakjuoh.chat.McpEndpoint;
import io.github.hakjuoh.chat.Providers;
import io.github.hakjuoh.config.McpConfig;
import io.github.hakjuoh.server.McpServerController;
import io.github.hakjuoh.server.McpServerRegistry;

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

    private enum Kind { USER, ASSISTANT, TOOL, THINKING, ERROR, SYSTEM }

    private record Chunk(Kind kind, String text) {
    }

    private JTextPane transcript;
    private JTextArea input;
    private JButton sendButton;
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
        providerBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        providerBar.add(new JLabel("Provider:"));

        // Only locally-installed CLIs appear in the picker; if none, say so and leave the panel unusable.
        List<ChatProvider> available = Providers.available();
        if (available.isEmpty()) {
            JLabel none = new JLabel("none found — install Claude Code (claude) or Codex (codex), then reopen");
            none.setForeground(new Color(0xB00020));
            providerBar.add(none);
        } else {
            String savedProvider = McpConfig.prefs().getString(McpConfig.KEY_CHAT_PROVIDER, "");
            providerCombo = new JComboBox<>();
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
            providerBar.add(providerCombo);
        }

        providerBar.add(Box.createHorizontalStrut(8));
        providerBar.add(new JLabel("Model:"));
        modelCombo = new JComboBox<>();
        modelCombo.setEditable(true);
        modelCombo.addActionListener(e -> onModelChanged());
        providerBar.add(modelCombo);

        newChatButton = new JButton("New chat");
        newChatButton.addActionListener(e -> startNewConversation(true));
        providerBar.add(Box.createHorizontalStrut(8));
        providerBar.add(newChatButton);

        if (currentProvider != null) {
            populateModels(currentProvider);
        }

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

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        options.add(confirmEdits);
        options.add(showThinking);

        statusLabel = new JLabel(" ");
        usageLabel = new JLabel(" ");
        workingLabel = new JLabel(" ");
        workingLabel.setForeground(new Color(0x1A4F8B));
        workingLabel.setHorizontalAlignment(JLabel.CENTER);
        JPanel statusRow = new JPanel(new BorderLayout(8, 0));
        statusRow.add(statusLabel, BorderLayout.WEST);
        statusRow.add(workingLabel, BorderLayout.CENTER);
        statusRow.add(usageLabel, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(4, 4));
        JPanel rows = new JPanel(new BorderLayout(4, 4));
        rows.add(providerBar, BorderLayout.NORTH);
        rows.add(options, BorderLayout.SOUTH);
        top.add(rows, BorderLayout.NORTH);
        top.add(statusRow, BorderLayout.SOUTH);
        return top;
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

        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> send());
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stop());

        JPanel buttons = new JPanel();
        buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.Y_AXIS));
        buttons.add(sendButton);
        buttons.add(Box.createVerticalStrut(4));
        buttons.add(stopButton);

        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.add(new JScrollPane(input), BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);
        return bar;
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
        Object sel = modelCombo.getEditor().getItem();
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
            showIntro();
        }
    }

    // ------------------------------------------------------------------ send / stop

    private void send() {
        if (currentProcess != null && currentProcess.isAlive()) {
            return;
        }
        String prompt = input.getText().trim();
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
        if (!atTurnStartOfLine) {
            append(Kind.SYSTEM, "\n");
        }
        append(Kind.USER, "> " + prompt + "\n");

        completedExit = null;
        lastUsage = null;
        liveUsage = null;
        currentProcess = null;
        final int turn = ++turnSeq;   // identifies this turn so a late handle-publish can't bleed across turns
        activeTurn = turn;
        cancelRequested = false;
        usageLabel.setText("tokens: …");
        setInputEnabled(false);
        stopButton.setEnabled(true);
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
                ChatRequest req = new ChatRequest(model, prompt, resume, endpoint);
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
        if (McpConfig.prefs().getBoolean(McpConfig.KEY_CHAT_CONSENTED, false)) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "The chat runs your local '" + currentProvider.id() + "' CLI, which sends your prompts and "
                        + "the ontology content the assistant reads (entity names, labels, axioms) to the "
                        + "model provider.\n\nProtégé stores no API key — the CLI uses your existing login. "
                        + "Edits still obey the MCP server's read-only / confirm-write settings.\n\nContinue?",
                "Chat sends data to your model provider", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            McpConfig.prefs().putBoolean(McpConfig.KEY_CHAT_CONSENTED, true);
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
        stopButton.setEnabled(false);
        input.requestFocusInWindow();
    }

    private static String formatUsage(ChatUsage u) {
        StringBuilder sb = new StringBuilder("tokens: ");
        sb.append(u.inputTokens() < 0 ? "?" : u.inputTokens()).append(" in");
        if (u.cachedInputTokens() > 0) {
            sb.append(" (").append(u.cachedInputTokens()).append(" cached)");
        }
        sb.append(" / ").append(u.outputTokens() < 0 ? "?" : u.outputTokens()).append(" out");
        if (u.costUsd() != null) {
            sb.append(String.format("  ·  $%.4f", u.costUsd()));
        }
        return sb.toString() + "   ";
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
        String server;
        if (c == null) {
            server = "MCP server: not available in this window";
        } else if (c.isRunning()) {
            server = "MCP server: running";
        } else {
            server = "MCP server: stopped (will start on first message)";
        }
        String mode;
        if (c == null) {
            mode = "";
        } else if (c.isReadOnly()) {
            mode = "  ·  edits: READ-ONLY";
        } else if (c.isConfirmWrites()) {
            mode = "  ·  edits: confirm each";
        } else {
            mode = "  ·  edits: writable";
        }
        statusLabel.setText(server + mode + "  ·  prompts go to your model provider");
        if (confirmEdits != null && c != null) {
            boolean live = c.isConfirmWrites();
            if (confirmEdits.isSelected() != live) {
                confirmEdits.setSelected(live);
            }
        }
    }

    /** Enable/disable every control that mutates conversation state (everything but Stop). */
    private void setInputEnabled(boolean enabled) {
        for (Component comp : new Component[] {sendButton, input, newChatButton, providerCombo, modelCombo}) {
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
    }
}
