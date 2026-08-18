package io.github.hakjuoh.protege_mcp.ui;

import java.awt.Desktop;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import io.github.hakjuoh.protege_mcp.chat.ChatClientInstallGuide;

/** Selectable installation command and official documentation action for one client tab. */
final class ClientInstallationGuidePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final ChatClientInstallGuide guide;
    private final JTextField commandField;
    private final JLabel feedback = new JLabel(" ");
    private final JButton openGuideButton = new JButton("Open official guide");

    ClientInstallationGuidePanel(ChatClientInstallGuide guide) {
        this(guide, System.getProperty("os.name", ""));
    }

    ClientInstallationGuidePanel(ChatClientInstallGuide guide, String osName) {
        super(new GridBagLayout());
        this.guide = guide;
        ChatClientInstallGuide.InstallCommand install = guide.commandFor(osName);
        commandField = new JTextField(install.command(), 34);
        commandField.setEditable(false);
        commandField.setCaretPosition(0);

        JLabel commandLabel = new JLabel(install.label() + ":");
        commandLabel.setLabelFor(commandField);
        JButton copy = new JButton("Copy");
        copy.addActionListener(event -> copyCommand());
        openGuideButton.addActionListener(event -> openDocumentation());

        add(commandLabel, constraints(0, 0, 0, GridBagConstraints.NONE));
        add(commandField, constraints(1, 0, 1, GridBagConstraints.HORIZONTAL));
        add(copy, constraints(2, 0, 0, GridBagConstraints.NONE));
        // Align the documentation action with the command field rather than the outer group label or
        // the platform label. Both actionable rows now share one clear left edge.
        add(openGuideButton, constraints(1, 1, 0, GridBagConstraints.NONE));
        add(feedback, constraints(2, 1, 0, GridBagConstraints.HORIZONTAL));
    }

    private static GridBagConstraints constraints(int x, int y, double weightX, int fill) {
        int left = x == 0 ? 0 : 6;
        return new GridBagConstraints(x, y, 1, 1, weightX, 0,
                GridBagConstraints.WEST, fill, new Insets(y == 0 ? 0 : 4, left, 0, 0), 0, 0);
    }

    private void copyCommand() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(commandField.getText()), null);
            feedback.setText("Copied.");
        } catch (RuntimeException exception) {
            feedback.setText("Could not access the clipboard; select and copy the command above.");
        }
    }

    private void openDocumentation() {
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                feedback.setText("Browser launch is unavailable on this system.");
                return;
            }
            Desktop.getDesktop().browse(guide.documentationUri());
            feedback.setText("Opened the official guide.");
        } catch (Exception exception) {
            feedback.setText("Could not open the browser: " + guide.documentationUri());
        }
    }
}
