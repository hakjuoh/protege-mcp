package io.github.hakjuoh.ui;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import io.github.hakjuoh.tools.WriteConfirmer;

/**
 * The runtime {@link WriteConfirmer}: a modal Swing confirmation shown on the EDT with no timeout.
 * Lives in the {@code ui} layer so the {@code tools} layer stays free of Swing/AWT; the composition
 * root ({@code McpServerHook}) injects one instance into each {@code McpServerController}.
 */
public final class SwingWriteConfirmer implements WriteConfirmer {

    @Override
    public boolean confirm(String summary) {
        final boolean[] approved = {false};
        Runnable prompt = () -> {
            int choice = JOptionPane.showConfirmDialog(null,
                    "An MCP client requests this action:\n\n" + summary,
                    "protege-mcp — confirm write", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            approved[0] = choice == JOptionPane.OK_OPTION;
        };
        if (SwingUtilities.isEventDispatchThread()) {
            prompt.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(prompt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.lang.reflect.InvocationTargetException e) {
                return false;
            }
        }
        return approved[0];
    }
}
