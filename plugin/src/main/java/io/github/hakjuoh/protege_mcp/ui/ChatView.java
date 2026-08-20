package io.github.hakjuoh.protege_mcp.ui;

import static io.github.hakjuoh.protege_mcp.ui.ChatIcons.icon;
import static io.github.hakjuoh.protege_mcp.ui.ChatIcons.iconButton;

import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatUsage;
import io.github.hakjuoh.protege_mcp.chat.Providers;
import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.external.ProviderConfigurationEvents;
import io.github.hakjuoh.protege_mcp.external.ProviderConfigurationStore;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig;
import io.github.hakjuoh.protege_mcp.external.ProviderPolicyBindings;
import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.McpServerRegistry;
import io.github.hakjuoh.protege_mcp.tools.SidecarPaths;
import io.github.hakjuoh.protege_mcp.tools.ProjectPolicyRegistrySync;
import io.github.hakjuoh.protege_mcp.tools.ProjectPolicyMembershipService;
import io.github.hakjuoh.protege_mcp.tools.WriteTools;
import io.github.hakjuoh.protege_mcp.ui.ChatIcons.Glyph;
import io.github.hakjuoh.protege_mcp.ui.ChatTranscriptPane.Kind;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.protege.editor.owl.ui.OWLIcons;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
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
    private JButton projectExplorerButton;
    private JButton policyStatusBadge;
    private JButton policySyncButton;
    private JPanel projectHeaderBar;
    private ProjectExplorerPanel projectExplorer;
    private JSplitPane workspaceSplit;
    private volatile RecursiveProjectWatcher projectWatcher;
    private Path watchedProjectRoot;
    private volatile Path governingPolicyPath;
    private OWLModelManagerListener modelManagerListener;
    private ActiveOntologyEvents activeOntologyEvents;
    private ExecutorService policyLoader;
    private final AtomicLong policyRefreshGeneration = new AtomicLong();
    private volatile PolicyBadgeSnapshot policySnapshot;
    private boolean policySyncBusy;
    private boolean disposed;
    private final Runnable providerConfigurationListener = this::requestPolicyRefresh;
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
        disposed = false;
        policyLoader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "protege-policy-badge-loader");
            thread.setDaemon(true);
            return thread;
        });
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
        JPanel assistant = new JPanel(new BorderLayout(0, 8));
        assistant.add(scroll, BorderLayout.CENTER);
        assistant.add(buildInputBar(), BorderLayout.SOUTH);

        projectExplorer = new ProjectExplorerPanel(OWLIcons.getIcon("ontology.png"),
                ontology -> getOWLEditorKit().getWorkspace().getOWLIconProvider().getIcon(ontology),
                new ProjectExplorerPanel.Listener() {
                    @Override public void closeRequested() { setProjectExplorerVisible(false); }
                    @Override public void activateOntology(OWLOntology ontology) {
                        activateOntologyFromExplorer(ontology);
                    }
                    @Override public void saveOntologyIntoProject(OWLOntology ontology,
                            Path projectRoot) {
                        saveOntologyIntoProject(ontology, projectRoot);
                    }
                    @Override public void openFile(ProjectWorkspaceSnapshot.ProjectFile file) {
                        openProjectFile(file);
                    }
                    @Override public void addToPolicy(ProjectWorkspaceSnapshot.ProjectFile file) {
                        changePolicyMembership(file, true);
                    }
                    @Override public void removeFromPolicy(ProjectWorkspaceSnapshot.ProjectFile file) {
                        changePolicyMembership(file, false);
                    }
                    @Override public boolean canOpenFile(
                            ProjectWorkspaceSnapshot.ProjectFile file) {
                        return canOpenProjectFile(file);
                    }
                    @Override public boolean canEditMembership() {
                        PolicyBadgeSnapshot snapshot = policySnapshot;
                        return !policySyncBusy && isCurrentPolicySnapshot(snapshot)
                                && snapshot.policy().valid() && snapshot.policy().version() >= 3;
                    }
                });
        workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, projectExplorer, assistant);
        workspaceSplit.setContinuousLayout(true);
        workspaceSplit.setResizeWeight(0.0);
        workspaceSplit.setDividerLocation(285);
        workspaceSplit.setDividerSize(6);
        workspaceSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                event -> resizeProjectHeader(workspaceSplit.getDividerLocation()));
        add(workspaceSplit, BorderLayout.CENTER);
        setProjectExplorerVisible(McpConfig.prefs().getBoolean(
                "chat.project_explorer.visible", true));
        turnController = buildTurnController();

        statusTimer = new Timer(1500, e -> refreshStatus());
        statusTimer.start();

        activeOntologyEvents = new ActiveOntologyEvents(getOWLModelManager() == null
                ? null : getOWLModelManager().getActiveOntology());
        modelManagerListener = event -> {
            EventType type = event.getType();
            if (type == EventType.ACTIVE_ONTOLOGY_CHANGED) {
                announceActiveOntologyChange();
            }
            if (type == EventType.ACTIVE_ONTOLOGY_CHANGED
                    || type == EventType.ONTOLOGY_LOADED
                    || type == EventType.ONTOLOGY_RELOADED
                    || type == EventType.ONTOLOGY_CREATED
                    || type == EventType.ONTOLOGY_SAVED) {
                invalidatePolicySelection();
                SwingUtilities.invokeLater(this::refreshOntologySelectorAndPolicy);
            }
        };
        if (getOWLModelManager() != null) {
            getOWLModelManager().addListener(modelManagerListener);
        }
        refreshOntologySelectorAndPolicy();
        ProviderConfigurationEvents.addChangeListener(providerConfigurationListener);

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

        JPanel optionsBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        optionsBar.setOpaque(false);
        if (available.isEmpty()) {
            JLabel none =
                    new JLabel(
                            "No coding-agent CLI found — install a configured client, then reopen");
            none.setForeground(new Color(0xB00020));
            optionsBar.add(none);
        }
        optionsBar.add(newChatButton);
        optionsBar.add(confirmEdits);
        optionsBar.add(showThinking);

        Font small = new JLabel().getFont().deriveFont(Font.PLAIN, 11f);
        Font smallBold = new JLabel().getFont().deriveFont(Font.BOLD, 11f);
        Color muted = new Color(0x666666);

        // The project filesystem and loaded ontology namespaces live in the left explorer.  The
        // top bar only needs a compact affordance for reopening it after the user closes it.
        JPanel projectBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        projectBar.setOpaque(false);
        projectExplorerButton = new JButton("Project Explorer",
                ChatIcons.icon(Glyph.FOLDER, 18, new Color(0x555555), null));
        projectExplorerButton.setFont(smallBold);
        projectExplorerButton.setToolTipText("Open Project Explorer");
        projectExplorerButton.addActionListener(event -> setProjectExplorerVisible(true));

        policyStatusBadge = iconButton(
                icon(Glyph.WARNING, 17, new Color(0x795548), null),
                "Click for project policy details");
        policyStatusBadge.setMargin(new Insets(0, 0, 0, 0));
        policyStatusBadge.setFocusPainted(true);
        policyStatusBadge.getAccessibleContext().setAccessibleName("Project policy status");
        policyStatusBadge.addActionListener(e -> onPolicyBadgeClicked());

        policySyncButton = iconButton(
                icon(Glyph.CREATE, 18, new Color(0x1A4F8B), null),
                "Create or synchronize project policy from saved Preferences");
        policySyncButton.setMargin(new Insets(0, 0, 0, 0));
        policySyncButton.getAccessibleContext().setAccessibleName("Create project policy");
        policySyncButton.addActionListener(e -> onSyncPolicyClicked());

        JLabel explorerTitle = new JLabel("Project Explorer");
        explorerTitle.setFont(smallBold.deriveFont(12f));
        JPanel explorerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
        explorerActions.setOpaque(false);
        explorerActions.add(policyStatusBadge);
        explorerActions.add(policySyncButton);
        JButton closeExplorer = iconButton(
                icon(Glyph.CLOSE, 15, new Color(0x444444), null),
                "Close Project Explorer");
        closeExplorer.setMargin(new Insets(0, 0, 0, 0));
        closeExplorer.setBorder(BorderFactory.createEmptyBorder());
        closeExplorer.getAccessibleContext().setAccessibleName("Close Project Explorer");
        closeExplorer.addActionListener(event -> setProjectExplorerVisible(false));
        explorerActions.add(closeExplorer);

        projectHeaderBar = new JPanel(new BorderLayout(4, 0));
        projectHeaderBar.setOpaque(false);
        projectHeaderBar.add(explorerTitle, BorderLayout.CENTER);
        projectHeaderBar.add(explorerActions, BorderLayout.EAST);
        projectHeaderBar.setPreferredSize(new Dimension(285,
                Math.max(newChatButton.getPreferredSize().height,
                        explorerActions.getPreferredSize().height)));
        projectBar.add(projectHeaderBar);
        projectBar.add(projectExplorerButton);
        projectExplorerButton.setVisible(false);

        // The live status strip is created here but laid out inside the composer's bottom row, so
        // the otherwise empty middle surfaces useful server, working, and token state.
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

        return new ResponsiveTopBar(projectBar, optionsBar);
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
                        if (!running) {
                            requestPolicyRefresh();
                        }
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
        boolean assistantExternalTerms = McpConfig.prefs().getBoolean(
                McpConfig.KEY_CHAT_ALLOW_EXTERNAL_TERMS, true);
        turnController.start(
                new ChatTurnController.StartRequest(
                        provider,
                        controller,
                        prompt,
                        providerControls.selectedModel(),
                        providerControls.selectedReasoningEffort(),
                        reasoningOn,
                        assistantWrites,
                        assistantExternalTerms,
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

    private void setProjectExplorerVisible(boolean visible) {
        if (projectExplorer == null || workspaceSplit == null) return;
        projectExplorer.setVisible(visible);
        workspaceSplit.setDividerSize(visible ? 6 : 0);
        if (visible) {
            int preferred = Math.max(210, projectExplorer.getPreferredSize().width);
            workspaceSplit.setDividerLocation(preferred);
            resizeProjectHeader(preferred);
        } else {
            workspaceSplit.setDividerLocation(0);
        }
        if (projectExplorerButton != null) projectExplorerButton.setVisible(!visible);
        if (projectHeaderBar != null) projectHeaderBar.setVisible(visible);
        McpConfig.prefs().putBoolean("chat.project_explorer.visible", visible);
        revalidate();
        repaint();
    }

    private void resizeProjectHeader(int width) {
        if (projectHeaderBar == null || width <= 0) return;
        Dimension current = projectHeaderBar.getPreferredSize();
        projectHeaderBar.setPreferredSize(new Dimension(Math.max(210, width), current.height));
        projectHeaderBar.revalidate();
    }

    private void openProjectFile(ProjectWorkspaceSnapshot.ProjectFile file) {
        if (file.kind() == ProjectWorkspaceSnapshot.FileKind.ONTOLOGY) {
            try {
                OWLModelManager mm = getOWLModelManager();
                OWLOntology ontology = mm.getOWLOntologyManager()
                        .loadOntologyFromOntologyDocument(file.path().toFile());
                mm.setActiveOntology(ontology);
                mm.fireEvent(EventType.ONTOLOGY_LOADED);
                return;
            } catch (Exception failure) {
                JOptionPane.showMessageDialog(this,
                        "Could not load ontology document:\n" + file.path() + "\n\n"
                                + failure.getMessage(),
                        "Open Ontology", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        try {
            if (canOpenProjectFile(file)) {
                Desktop.getDesktop().open(file.path().toFile());
            } else {
                JOptionPane.showMessageDialog(this,
                        "Opening files is not supported by this desktop environment:\n" + file.path(),
                        "Open Project File", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IOException failure) {
            JOptionPane.showMessageDialog(this,
                    "Could not open file:\n" + file.path() + "\n\n" + failure.getMessage(),
                    "Open Project File", JOptionPane.ERROR_MESSAGE);
        }
    }

    static boolean canOpenProjectFile(ProjectWorkspaceSnapshot.ProjectFile file) {
        if (file.kind() == ProjectWorkspaceSnapshot.FileKind.SYMLINK) return false;
        if (file.kind() == ProjectWorkspaceSnapshot.FileKind.ONTOLOGY) return true;
        return Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
    }

    private void saveOntologyIntoProject(OWLOntology ontology, Path projectRoot) {
        if (ontology == null || projectRoot == null) return;
        JFileChooser chooser = new JFileChooser(projectRoot.toFile());
        chooser.setDialogTitle("Save ontology into project");
        chooser.setSelectedFile(projectRoot.resolve(suggestedOntologyFileName(ontology)).toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path selected;
        try {
            selected = secureProjectSavePath(projectRoot, chooser.getSelectedFile().toPath());
        } catch (IOException | IllegalArgumentException unsafePath) {
            JOptionPane.showMessageDialog(this,
                    "This action saves external ontologies inside the current project:\n"
                            + projectRoot.toAbsolutePath().normalize() + "\n\n"
                            + unsafePath.getMessage(),
                    "Save Ontology", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (Files.exists(selected)) {
            int overwrite = JOptionPane.showConfirmDialog(this,
                    "Replace existing file?\n" + selected, "Save Ontology",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.OK_OPTION) return;
        }
        try {
            if (selected.getParent() != null) Files.createDirectories(selected.getParent());
            selected = secureProjectSavePath(projectRoot, selected);
            IRI documentIri = IRI.create(selected.toUri());
            getOWLModelManager().getOWLOntologyManager().saveOntology(ontology, documentIri);
            getOWLModelManager().getOWLOntologyManager().setOntologyDocumentIRI(
                    ontology, documentIri);
            getOWLModelManager().fireEvent(EventType.ONTOLOGY_SAVED);
            requestPolicyRefresh();
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this,
                    "Could not save ontology into the project:\n" + selected + "\n\n"
                            + failure.getMessage(),
                    "Save Ontology", JOptionPane.ERROR_MESSAGE);
        }
    }

    static Path secureProjectSavePath(Path projectRoot, Path selected) throws IOException {
        Path realRoot = projectRoot.toRealPath();
        Path candidate = selected.toAbsolutePath().normalize();
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (candidate.startsWith(normalizedRoot)) {
            candidate = realRoot.resolve(normalizedRoot.relativize(candidate)).normalize();
        } else {
            throw new IllegalArgumentException("The selected file is outside the project.");
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException("A symbolic-link target cannot be replaced.");
        }
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.toRealPath().startsWith(realRoot)) {
            throw new IllegalArgumentException("The selected path resolves outside the project.");
        }
        return candidate;
    }

    private static String suggestedOntologyFileName(OWLOntology ontology) {
        String iri = ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get().toString() : "ontology";
        String local = WriteTools.localName(iri);
        if (local == null || local.isBlank()) local = "ontology";
        return local.replaceAll("[^A-Za-z0-9._-]", "_") + ".owl";
    }

    private void changePolicyMembership(ProjectWorkspaceSnapshot.ProjectFile file,
            boolean included) {
        PolicyBadgeSnapshot snapshot = policySnapshot;
        if (!isCurrentPolicySnapshot(snapshot)) {
            requestPolicyRefresh();
            return;
        }
        if (!snapshot.policy().loaded() || !snapshot.policy().valid()
                || snapshot.policy().version() < 3) {
            JOptionPane.showMessageDialog(this,
                    "File membership requires a valid Project Policy v3. Create or update the "
                            + "project policy first.",
                    "Project Policy", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String ontologyIri = file.loadedOntology() != null
                && file.loadedOntology().getOntologyID().getOntologyIRI().isPresent()
                ? file.loadedOntology().getOntologyID().getOntologyIRI().get().toString() : null;
        setPolicySyncBusy(true, included ? "Adding…" : "Removing…");
        policyLoader.execute(() -> {
            try {
                ProjectPolicyMembershipService.setMembership(snapshot.policy(), file.path(),
                        ontologyIri, included);
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    setPolicySyncBusy(false, null);
                    requestPolicyRefresh();
                });
            } catch (IOException | IllegalArgumentException failure) {
                SwingUtilities.invokeLater(() -> {
                    if (!disposed) finishPolicySyncFailure(failure.getMessage());
                });
            }
        });
    }

    private void onPolicyBadgeClicked() {
        PolicyBadgeSnapshot snapshot = policySnapshot;
        if (snapshot == null) {
            requestPolicyRefresh();
            JOptionPane.showMessageDialog(this,
                    "Project policy status is still loading.",
                    "Project Policy Details",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ProjectPolicy policy = snapshot.policy();
        Path docPath = snapshot.activeDocument();
        List<PolicyIssue> allIssues = combinedIssues(policy, snapshot.ownerIssues());

        PolicyDetailsDialog.show(this, policy, docPath, allIssues);
    }

    private void onSyncPolicyClicked() {
        PolicyBadgeSnapshot snapshot = policySnapshot;
        if (!isCurrentPolicySnapshot(snapshot)) {
            requestPolicyRefresh();
            return;
        }
        if (!snapshot.synchronizationRequired()) return;
        if (snapshot.policyAnchorDocument() == null) {
            JOptionPane.showMessageDialog(this,
                    "Save the active ontology to disk before creating a project policy.",
                    "Project Policy", JOptionPane.WARNING_MESSAGE);
            return;
        }
        OWLModelManager mm = getOWLModelManager();
        List<String> reasoners = List.copyOf(getInstalledReasoners(mm));
        setPolicySyncBusy(true, "Checking…");
        policyLoader.execute(() -> {
            try {
                ProviderOwnerConfig owner = new ProviderConfigurationStore().load();
                ProjectPolicyRegistrySync.Preview preview =
                        ProjectPolicyRegistrySync.preview(snapshot.policyAnchorDocument(),
                                snapshot.ontologyIri(), reasoners, snapshot.policy(), owner,
                                snapshot.workspaceDocuments());
                SwingUtilities.invokeLater(() -> {
                    if (!isCurrentPolicySnapshot(snapshot)) {
                        if (!disposed) setPolicySyncBusy(false, null);
                        return;
                    }
                    if (!preview.synchronizationRequired()) {
                        setPolicySyncBusy(false, null);
                        requestPolicyRefresh();
                        return;
                    }
                    confirmPolicySync(snapshot, reasoners, owner, preview);
                });
            } catch (IOException | IllegalArgumentException failure) {
                SwingUtilities.invokeLater(() -> {
                    if (isCurrentPolicySnapshot(snapshot)) {
                        finishPolicySyncFailure(failure.getMessage());
                    } else if (!disposed) {
                        setPolicySyncBusy(false, null);
                    }
                });
            }
        });
    }

    private void confirmPolicySync(PolicyBadgeSnapshot snapshot, List<String> reasoners,
            ProviderOwnerConfig owner, ProjectPolicyRegistrySync.Preview preview) {
        if (!isCurrentPolicySnapshot(snapshot)) {
            if (!disposed) setPolicySyncBusy(false, null);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                formatPolicySyncPreview(preview, preview.target()),
                preview.policyExists() ? "Synchronize Project Policy"
                        : "Create Project Policy",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            setPolicySyncBusy(false, null);
            return;
        }
        if (!isCurrentPolicySnapshot(snapshot)) {
            setPolicySyncBusy(false, null);
            requestPolicyRefresh();
            return;
        }
        setPolicySyncBusy(true, preview.policyExists() ? "Syncing…" : "Creating…");
        policyLoader.execute(() -> {
            try {
                ProjectPolicyRegistrySync.Result result = ProjectPolicyRegistrySync.apply(
                        snapshot.policyAnchorDocument(), snapshot.ontologyIri(), reasoners,
                        preview, owner);
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    requestPolicyRefresh();
                    setPolicySyncBusy(false, null);
                    String action = result.created() ? "Created" : "Updated";
                    String note = result.omitted().isEmpty() ? ""
                            : "\n\nSkipped:\n • " + String.join("\n • ", result.omitted());
                    JOptionPane.showMessageDialog(this,
                            action + " project policy:\n" + result.path() + note,
                            "Project Policy", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (IOException | IllegalArgumentException failure) {
                SwingUtilities.invokeLater(() -> {
                    if (!disposed) finishPolicySyncFailure(failure.getMessage());
                });
            }
        });
    }

    static String formatPolicySyncPreview(ProjectPolicyRegistrySync.Preview preview, Path target) {
        StringBuilder message = new StringBuilder();
        if (preview.policyExists()) {
            if (preview.sourceVersion() < 3) {
                message.append("Upgrade Project Policy v").append(preview.sourceVersion())
                        .append(" to v3, initialize its workspace from the ontologies currently ")
                        .append("loaded in Protégé, and apply saved Preference bindings?\n\n")
                        .append("Loaded ontology documents considered: ")
                        .append(preview.workspaceDocuments().size()).append("\n");
            } else {
                message.append("Replace the policy's external_terms.providers with the saved ")
                        .append("Preference bindings?\n\n");
            }
            message.append("Current policy providers: ").append(preview.currentProviderCount())
                    .append("\nConfigured providers to apply: ")
                    .append(preview.providers().size());
        } else {
            message.append("Create a valid Project Policy v3, include the saved ontology files ")
                    .append("currently loaded in Protégé, and apply the saved terminology ")
                    .append("registry bindings?\n\nLoaded ontology documents considered: ")
                    .append(preview.workspaceDocuments().size())
                    .append("\nConfigured providers: ")
                    .append(preview.providers().size());
        }
        if (!preview.omitted().isEmpty()) {
            message.append("\n\nSkipped bindings:\n • ")
                    .append(String.join("\n • ", preview.omitted()));
        }
        message.append("\n\nTarget policy:\n").append(target);
        message.append("\n\nMatching provider restrictions are preserved. Apart from a v1/v2 ")
                .append("upgrade's version and workspace fields, other policy sections are not changed.");
        return message.toString();
    }

    private boolean isCurrentPolicySnapshot(PolicyBadgeSnapshot expected) {
        return !disposed && expected != null && expected == policySnapshot
                && policyLoader != null && !policyLoader.isShutdown();
    }

    private void finishPolicySyncFailure(String message) {
        setPolicySyncBusy(false, null);
        JOptionPane.showMessageDialog(this,
                message == null || message.isBlank() ? "The project policy could not be updated."
                        : message,
                "Project Policy", JOptionPane.ERROR_MESSAGE);
    }

    private void setPolicySyncBusy(boolean busy, String label) {
        if (policySyncButton == null) return;
        policySyncBusy = busy;
        if (label != null) policySyncButton.setToolTipText(label);
        updatePolicySyncButton();
    }

    private void updatePolicySyncButton() {
        if (policySyncButton == null) return;
        PolicyBadgeSnapshot snapshot = policySnapshot;
        boolean policyExists = snapshot != null && snapshot.policy().loaded();
        if (policyStatusBadge != null) policyStatusBadge.setVisible(policyExists);
        policySyncButton.setEnabled(!disposed && !policySyncBusy && snapshot != null
                && snapshot.synchronizationRequired());
        policySyncButton.setIcon(icon(policyExists ? Glyph.SYNC : Glyph.CREATE, 18,
                policySyncButton.isEnabled() ? new Color(0x1A4F8B) : new Color(0x888888), null));
        policySyncButton.getAccessibleContext().setAccessibleName(policyExists
                ? "Synchronize project policy" : "Create project policy");
        if (!policySyncBusy) {
            if (snapshot != null && !snapshot.synchronizationRequired()) {
                policySyncButton.setToolTipText(snapshot.omittedBindings().isEmpty()
                        ? "Project policy already matches saved Preferences"
                        : "No applicable policy changes; " + snapshot.omittedBindings().size()
                                + " saved Preference binding(s) were skipped");
            } else {
                policySyncButton.setToolTipText(
                        "Create or synchronize project policy from saved Preferences");
            }
        }
    }

    private void activateOntologyFromExplorer(OWLOntology ontology) {
        if (ontology == null) return;
        OWLModelManager mm = getOWLModelManager();
        if (mm == null) return;
        ActiveOntologyEvents.activate(mm, ontology);
    }

    private void announceActiveOntologyChange() {
        OWLModelManager manager = getOWLModelManager();
        OWLOntology active = manager == null ? null : manager.getActiveOntology();
        if (activeOntologyEvents == null) activeOntologyEvents = new ActiveOntologyEvents(null);
        if (!activeOntologyEvents.observe(active)) return;
        append(Kind.SYSTEM, "Active ontology switched to: "
                + formatOntologyLabel(manager, active) + "\n");
    }

    void refreshOntologySelectorAndPolicy() {
        requestPolicyRefresh();
    }

    /**
     * Capture the small amount of OWL UI state needed for policy resolution, then perform all
     * filesystem discovery and validation on a single daemon worker. Results are generation-gated
     * so a slower load for a previously active ontology can never overwrite the current badge.
     */
    private void requestPolicyRefresh() {
        OWLModelManager mm = getOWLModelManager();
        if (mm == null || policyStatusBadge == null || policyLoader == null
                || policyLoader.isShutdown()) {
            return;
        }
        OWLOntology active = mm.getActiveOntology();
        IRI docIri = active != null && mm.getOWLOntologyManager() != null
                ? mm.getOWLOntologyManager().getOntologyDocumentIRI(active) : null;
        File docFile = SidecarPaths.toFile(docIri);
        Path docPath = docFile != null ? docFile.toPath() : null;
        String ontologyIri = active != null && active.getOntologyID().getOntologyIRI().isPresent()
                ? active.getOntologyID().getOntologyIRI().get().toString() : null;
        List<ProjectWorkspaceService.LoadedOntology> loadedOntologies = captureLoadedOntologies(mm);
        List<String> installedReasoners = List.copyOf(getInstalledReasoners(mm));
        Path previousPolicyPath = governingPolicyPath;
        long generation = policyRefreshGeneration.incrementAndGet();
        policySnapshot = null;
        updatePolicySyncButton();
        applyPolicyBadge("Policy: checking…", null);

        policyLoader.execute(() -> {
            ProjectPolicy policy = previousPolicyPath != null
                    && (docPath == null || !Files.isRegularFile(docPath))
                    ? ProjectPolicyLoader.load(previousPolicyPath, docPath, null, installedReasoners)
                    : ProjectPolicyLoader.load(null, docPath, null, installedReasoners);
            if (!policy.loaded() && previousPolicyPath != null && Files.isRegularFile(previousPolicyPath)) {
                policy = ProjectPolicyLoader.load(previousPolicyPath, docPath, null, installedReasoners);
            }
            Path policyAnchorDocument = policyAnchorDocument(policy, docPath);
            ProjectWorkspaceSnapshot builtWorkspace = new ProjectWorkspaceService().build(
                    policy, policyAnchorDocument, loadedOntologies, active);
            RecursiveProjectWatcher watcher = projectWatcher;
            ProjectWorkspaceSnapshot workspace = builtWorkspace.withWatcherTruncated(
                    watchesRoot(watcher, builtWorkspace.projectRoot())
                            && watcher.registrationTruncated());
            OwnerPolicyState ownerState = ownerPolicyState(policy);
            List<PolicyIssue> ownerIssues = ownerState.issues();
            PolicyBadgeSnapshot snapshot = new PolicyBadgeSnapshot(
                    docPath, policyAnchorDocument, ontologyIri, policy, ownerIssues,
                    ownerState.synchronizationRequired(), ownerState.omittedBindings(),
                    workspaceDocuments(loadedOntologies),
                    formatPolicyBadgeWithIssues(policy, ownerIssues),
                    buildPolicyTooltip(docPath, ontologyIri, policy, ownerIssues));
            SwingUtilities.invokeLater(() -> {
                if (generation != policyRefreshGeneration.get() || policyLoader == null
                        || policyLoader.isShutdown()) {
                    return;
                }
                governingPolicyPath = snapshot.policy().loaded() ? snapshot.policy().path() : null;
                policySnapshot = snapshot;
                applyPolicyBadge(snapshot.badge(), snapshot.tooltip());
                updatePolicySyncButton();
                if (projectExplorer != null) projectExplorer.showSnapshot(workspace);
                followProjectRoot(workspace.projectRoot());
            });
        });
    }

    private static Path policyAnchorDocument(ProjectPolicy policy, Path activeDocument) {
        if (policy == null || !policy.loaded() || policy.projectRoot() == null) return activeDocument;
        if (activeDocument != null && activeDocument.toAbsolutePath().normalize()
                .startsWith(policy.projectRoot())) return activeDocument;
        List<Path> roots = policy.assets().get("root_artifact");
        return roots == null || roots.isEmpty() ? activeDocument : roots.get(0);
    }

    private static boolean watchesRoot(RecursiveProjectWatcher watcher, Path root) {
        if (watcher == null || root == null) return false;
        try {
            return watcher.root().equals(root.toRealPath());
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static List<ProjectWorkspaceService.LoadedOntology> captureLoadedOntologies(
            OWLModelManager mm) {
        if (mm == null || mm.getOWLOntologyManager() == null) return List.of();
        List<ProjectWorkspaceService.LoadedOntology> result = new ArrayList<>();
        for (OWLOntology ontology : mm.getOntologies()) {
            IRI documentIri = mm.getOWLOntologyManager().getOntologyDocumentIRI(ontology);
            File local = SidecarPaths.toFile(documentIri);
            Path path = local == null ? null : local.toPath().toAbsolutePath().normalize();
            String iri = ontology.getOntologyID().getOntologyIRI().isPresent()
                    ? ontology.getOntologyID().getOntologyIRI().get().toString() : null;
            result.add(new ProjectWorkspaceService.LoadedOntology(ontology, iri,
                    documentIri == null ? null : documentIri.toString(), path,
                    path != null && Files.isRegularFile(path), ontology.getAxiomCount()));
        }
        return List.copyOf(result);
    }

    private static List<ProjectPolicyRegistrySync.WorkspaceDocument> workspaceDocuments(
            List<ProjectWorkspaceService.LoadedOntology> loaded) {
        return loaded.stream()
                .filter(item -> item.localPath() != null && item.documentExists())
                .map(item -> new ProjectPolicyRegistrySync.WorkspaceDocument(
                        item.ontologyIri(), item.localPath()))
                .toList();
    }

    private void followProjectRoot(Path root) {
        Path normalized = root == null ? null : root.toAbsolutePath().normalize();
        if (java.util.Objects.equals(normalized, watchedProjectRoot)) return;
        if (projectWatcher != null) {
            projectWatcher.close();
            projectWatcher = null;
        }
        watchedProjectRoot = normalized;
        if (normalized == null || !Files.isDirectory(normalized)) return;
        try {
            projectWatcher = new RecursiveProjectWatcher(normalized,
                    () -> SwingUtilities.invokeLater(() -> {
                        if (!disposed) requestPolicyRefresh();
                    }));
        } catch (IOException ignored) {
            watchedProjectRoot = null;
        }
    }

    private void applyPolicyBadge(String badge, String tooltip) {
        if (badge.contains("Valid")) {
            policyStatusBadge.setIcon(icon(Glyph.CHECK, 17, new Color(0x1B5E20), null));
        } else if (badge.contains("Warning") || badge.contains("None")
                || badge.contains("checking")) {
            policyStatusBadge.setIcon(icon(Glyph.WARNING, 17, new Color(0xB26A00), null));
        } else {
            policyStatusBadge.setIcon(icon(Glyph.WARNING, 17, new Color(0xB71C1C), null));
        }
        policyStatusBadge.getAccessibleContext().setAccessibleName(badge);
        policyStatusBadge.setToolTipText(tooltip != null
                ? tooltip : "Project policy status is loading in the background");
    }

    private void invalidatePolicySelection() {
        policySnapshot = null;
        policyRefreshGeneration.incrementAndGet();
        if (SwingUtilities.isEventDispatchThread()) {
            updatePolicySyncButton();
        } else {
            SwingUtilities.invokeLater(() -> {
                if (!disposed && policySnapshot == null) updatePolicySyncButton();
            });
        }
    }

    static String buildPolicyTooltip(Path docPath, String ontologyIri, ProjectPolicy policy) {
        return buildPolicyTooltip(docPath, ontologyIri, policy, List.of());
    }

    static String buildPolicyTooltip(Path docPath, String ontologyIri, ProjectPolicy policy,
            List<PolicyIssue> ownerIssues) {
        StringBuilder tip = new StringBuilder("<html>");
        tip.append("<b>Active Project / Ontology:</b> ")
                .append(escapeHtml(ontologyIri != null ? ontologyIri : "(anonymous)"))
                .append("<br>");
        tip.append("<b>Document Location:</b> ");
        if (docPath != null) {
            tip.append(escapeHtml(docPath));
        } else {
            tip.append("<i>Unsaved (In-memory)</i>");
        }
        tip.append("<br>");
        if (docPath != null && docPath.getParent() != null) {
            tip.append("<b>Project Directory:</b> ")
                    .append(escapeHtml(docPath.getParent())).append("<br>");
        }
        String badge = formatPolicyBadgeWithIssues(policy, ownerIssues);
        tip.append("<b>Policy Status:</b> ").append(escapeHtml(badge)).append("<br>");
        if (policy.loaded()) {
            tip.append("<b>Policy Path:</b> ").append(escapeHtml(policy.path())).append("<br>");
            tip.append("<b>Project Root:</b> ").append(escapeHtml(policy.projectRoot()))
                    .append("<br>");
            List<PolicyIssue> issues = combinedIssues(policy, ownerIssues);
            if (!issues.isEmpty()) {
                int count = issues.size();
                tip.append("<b>Issues:</b> ").append(count).append(count == 1 ? " issue" : " issues").append("<br>");
                for (PolicyIssue issue : issues) {
                    tip.append("&nbsp;&nbsp;• [").append(escapeHtml(issue.code())).append("] ");
                    if (issue.path() != null && !issue.path().isEmpty()) {
                        tip.append("<code>").append(escapeHtml(issue.path())).append("</code>: ");
                    }
                    tip.append(escapeHtml(issue.message())).append("<br>");
                }
            }
        } else {
            tip.append("<i>No .protege-mcp/project.yaml found for this project.</i><br>");
            tip.append("Save ontology to disk and create a Project Policy v3.");
        }
        tip.append("<i>Click for full details.</i>");
        tip.append("</html>");
        return tip.toString();
    }

    private static OwnerPolicyState ownerPolicyState(ProjectPolicy policy) {
        try {
            ProviderOwnerConfig owner = new ProviderConfigurationStore().load();
            ProjectPolicyRegistrySync.SynchronizationStatus status =
                    ProjectPolicyRegistrySync.synchronizationStatus(policy, owner);
            return new OwnerPolicyState(ProviderPolicyBindings.warnings(policy, owner),
                    status.required(), status.omitted());
        } catch (ProviderFailure failure) {
            List<PolicyIssue> issues = hasProviderRows(policy)
                    ? List.of(new PolicyIssue("warning", "provider_configuration_unavailable",
                            "external_terms.providers",
                            "Owner terminology registry settings could not be read ("
                                    + failure.code()
                                    + "). Review Preferences ▸ MCP ▸ Externals."))
                    : List.of();
            return new OwnerPolicyState(issues, true, List.of());
        } catch (IllegalArgumentException invalid) {
            return new OwnerPolicyState(List.of(new PolicyIssue("warning",
                    "provider_configuration_ambiguous", "external_terms.providers",
                    invalid.getMessage())), true, List.of());
        }
    }

    private static boolean hasProviderRows(ProjectPolicy policy) {
        if (policy == null || !policy.loaded() || policy.version() < 2) return false;
        Object external = policy.effective().get("external_terms");
        if (!(external instanceof java.util.Map<?, ?> map)) return false;
        Object providers = map.get("providers");
        return providers instanceof List<?> list && !list.isEmpty();
    }

    private static List<PolicyIssue> combinedIssues(ProjectPolicy policy,
            List<PolicyIssue> ownerIssues) {
        List<PolicyIssue> result = new ArrayList<>(policy.issues());
        result.addAll(ownerIssues);
        return List.copyOf(result);
    }

    static String escapeHtml(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static List<String> getInstalledReasoners(OWLModelManager mm) {
        if (mm == null) {
            return Collections.emptyList();
        }
        List<String> reasoners = new ArrayList<>();
        try {
            if (mm.getOWLReasonerManager() != null) {
                for (var info : mm.getOWLReasonerManager().getInstalledReasonerFactories()) {
                    if (info != null && info.getReasonerName() != null) {
                        reasoners.add(info.getReasonerName());
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return reasoners;
    }

    static String formatOntologyLabel(OWLModelManager mm, OWLOntology o) {
        if (o == null) {
            return "None";
        }
        OWLOntologyID id = o.getOntologyID();
        String ontologyIri = !id.isAnonymous() && id.getOntologyIRI().isPresent()
                ? id.getOntologyIRI().get().toString() : null;
        String local = ontologyIri != null ? WriteTools.localName(ontologyIri) : "";

        File docFile = null;
        if (mm != null && mm.getOWLOntologyManager() != null) {
            IRI docIri = mm.getOWLOntologyManager().getOntologyDocumentIRI(o);
            if (docIri != null) {
                docFile = SidecarPaths.toFile(docIri);
            }
        }

        if (docFile != null && docFile.exists()) {
            String fileName = docFile.getName();
            File parent = docFile.getParentFile();
            String parentDir = parent != null ? parent.getName() : "";
            String location = parentDir.isEmpty() ? fileName : parentDir + " / " + fileName;
            if (ontologyIri != null) {
                String shortName = !local.isEmpty() ? local : ontologyIri;
                return location + " (" + shortName + ")";
            }
            return location + " (anonymous)";
        }

        // Unsaved / In-memory ontology
        String name = ontologyIri != null ? (!local.isEmpty() ? local : ontologyIri) : "Anonymous Ontology";
        return "[Unsaved] " + name + " · In-memory";
    }

    static String formatPolicyBadge(OWLModelManager mm, OWLOntology active) {
        if (mm == null || active == null) {
            return "Policy: None";
        }
        IRI docIri = mm.getOWLOntologyManager() != null
                ? mm.getOWLOntologyManager().getOntologyDocumentIRI(active) : null;
        File docFile = SidecarPaths.toFile(docIri);
        Path docPath = docFile != null ? docFile.toPath() : null;
        String ontologyIri = active.getOntologyID().getOntologyIRI().isPresent()
                ? active.getOntologyID().getOntologyIRI().get().toString() : null;
        List<String> installedReasoners = getInstalledReasoners(mm);
        ProjectPolicy policy = ProjectPolicyLoader.load(null, docPath, ontologyIri, installedReasoners);
        return formatPolicyBadge(policy);
    }

    private static String formatPolicyBadge(ProjectPolicy policy) {
        return formatPolicyBadgeWithIssues(policy, List.of());
    }

    static String formatPolicyBadgeWithIssues(ProjectPolicy policy,
            List<PolicyIssue> ownerIssues) {
        if (!policy.loaded()) {
            return "Policy: None";
        }
        if (policy.valid()) {
            int warnings = (int) combinedIssues(policy, ownerIssues).stream()
                    .filter(issue -> "warning".equals(issue.severity())).count();
            if (warnings > 0) {
                return "Policy: v" + (policy.version() > 0 ? policy.version() : "1")
                        + " (Warning: " + warnings + ")";
            }
            return "Policy: v" + (policy.version() > 0 ? policy.version() : "1") + " (Valid)";
        }
        int count = combinedIssues(policy, ownerIssues).size();
        String issueSuffix = count == 1 ? "1 issue" : count + " issues";
        return "Policy: Invalid (" + issueSuffix + ")";
    }

    private record PolicyBadgeSnapshot(
            Path activeDocument,
            Path policyAnchorDocument,
            String ontologyIri,
            ProjectPolicy policy,
            List<PolicyIssue> ownerIssues,
            boolean synchronizationRequired,
            List<String> omittedBindings,
            List<ProjectPolicyRegistrySync.WorkspaceDocument> workspaceDocuments,
            String badge,
            String tooltip) {}

    private record OwnerPolicyState(
            List<PolicyIssue> issues,
            boolean synchronizationRequired,
            List<String> omittedBindings) {}

    static final class ResponsiveTopBar extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JComponent left;
        private final JComponent right;

        ResponsiveTopBar(JComponent left, JComponent right) {
            this.left = left;
            this.right = right;
            setLayout(null);
            setOpaque(false);
            add(left);
            add(right);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                private boolean lastWrapped = false;

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    boolean wrapped = isWrapped(getWidth());
                    if (wrapped != lastWrapped) {
                        lastWrapped = wrapped;
                        revalidate();
                        repaint();
                    }
                }
            });
        }

        boolean isWrapped(int width) {
            int leftW = left.getPreferredSize().width;
            int rightW = right.getPreferredSize().width;
            return width > 0 && width < (leftW + rightW + 8);
        }

        @Override
        public Dimension getPreferredSize() {
            int leftW = left.getPreferredSize().width;
            int rightW = right.getPreferredSize().width;
            int leftH = left.getPreferredSize().height;
            int rightH = right.getPreferredSize().height;
            int rowH = Math.max(leftH, rightH);
            int parentW = getWidth();
            if (parentW == 0 && getParent() != null) {
                parentW = getParent().getWidth();
            }
            if (isWrapped(parentW)) {
                return new Dimension(Math.max(leftW, rightW), rowH * 2 + 4);
            }
            return new Dimension(leftW + rightW + 8, rowH);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public void doLayout() {
            Insets insets = getInsets();
            int availW = getWidth() - insets.left - insets.right;
            int leftW = left.getPreferredSize().width;
            int rightW = right.getPreferredSize().width;
            int leftH = left.getPreferredSize().height;
            int rightH = right.getPreferredSize().height;
            int rowH = Math.max(leftH, rightH);

            if (isWrapped(availW)) {
                left.setBounds(insets.left, insets.top, Math.min(leftW, availW), leftH);
                right.setBounds(insets.left, insets.top + rowH + 4, Math.min(rightW, availW), rightH);
            } else {
                left.setBounds(insets.left, insets.top + (rowH - leftH) / 2, leftW, leftH);
                int rightX = insets.left + availW - rightW;
                right.setBounds(Math.max(insets.left + leftW + 6, rightX), insets.top + (rowH - rightH) / 2, rightW, rightH);
            }
        }
    }

    @Override
    protected void disposeOWLView() {
        disposed = true;
        if (projectWatcher != null) {
            projectWatcher.close();
            projectWatcher = null;
        }
        watchedProjectRoot = null;
        ProviderConfigurationEvents.removeChangeListener(providerConfigurationListener);
        policyRefreshGeneration.incrementAndGet();
        policySnapshot = null;
        if (policyLoader != null) {
            policyLoader.shutdownNow();
            policyLoader = null;
        }
        if (modelManagerListener != null && getOWLModelManager() != null) {
            getOWLModelManager().removeListener(modelManagerListener);
            modelManagerListener = null;
        }
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
