package io.github.hakjuoh.protege_mcp.ui;

import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Window;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Bounded, resizable policy-details window; long paths and issue messages wrap inside a scroll pane. */
final class PolicyDetailsDialog {

    private PolicyDetailsDialog() { }

    static void show(Component owner, ProjectPolicy policy, Path documentPath,
            List<PolicyIssue> issues) {
        Window window = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, "Project Policy Details",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        dialog.add(detailsPane(text(policy, documentPath, issues)), BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(event -> dialog.dispose());
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        actions.add(close);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(close);

        Dimension size = boundedSize(owner);
        dialog.setSize(size);
        dialog.setMinimumSize(new Dimension(Math.min(420, size.width), Math.min(260, size.height)));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    static JScrollPane detailsPane(String text) {
        JTextArea details = new JTextArea(text);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setCaretPosition(0);
        details.setBackground(new JLabel().getBackground());
        details.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        details.getAccessibleContext().setAccessibleName("Project Policy details");
        return new JScrollPane(details);
    }

    static Dimension boundedSize(Component owner) {
        GraphicsConfiguration configuration = owner == null ? null : owner.getGraphicsConfiguration();
        Rectangle bounds = configuration == null
                ? new Rectangle(0, 0, 1024, 768) : configuration.getBounds();
        int width = Math.max(1, Math.min(760, bounds.width - Math.min(80, bounds.width - 1)));
        int height = Math.max(1, Math.min(480, bounds.height - Math.min(100, bounds.height - 1)));
        return new Dimension(width, height);
    }

    static String text(ProjectPolicy policy, Path documentPath, List<PolicyIssue> issues) {
        StringBuilder out = new StringBuilder();
        if (!policy.loaded()) {
            out.append("No Project Policy Found\n\n");
            if (documentPath == null) {
                out.append("The active ontology has no local document.\n");
            } else {
                out.append("Searched from: ").append(documentPath).append("\n");
            }
            out.append("\nCreate .protege-mcp/project.yaml from Project Explorer.");
            return out.toString();
        }

        out.append("Policy File: ").append(policy.path()).append("\n");
        out.append("Project Root: ").append(policy.projectRoot()).append("\n");
        out.append("Status: ").append(policy.valid() ? issues.isEmpty() ? "Valid" : "Warning" : "Invalid")
                .append(issues.isEmpty() ? "" : " (" + issues.size()
                        + (issues.size() == 1 ? " issue" : " issues") + ")")
                .append("\n");
        if (!issues.isEmpty()) {
            out.append("\nValidation Issues:\n");
            for (PolicyIssue issue : issues) {
                out.append("• [").append(issue.code()).append("] ");
                if (issue.path() != null && !issue.path().isBlank()) {
                    out.append(issue.path()).append(": ");
                }
                out.append(issue.message()).append("\n");
            }
            out.append("\nEdit project.yaml or use Sync policy… for Preference-backed settings.");
        }
        return out.toString();
    }
}
