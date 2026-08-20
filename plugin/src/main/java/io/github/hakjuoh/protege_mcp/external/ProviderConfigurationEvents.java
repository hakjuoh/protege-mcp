package io.github.hakjuoh.protege_mcp.external;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Notifies open views after the owner-local provider configuration is committed. */
public final class ProviderConfigurationEvents {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfigurationEvents.class);
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private ProviderConfigurationEvents() {
    }

    public static void addChangeListener(Runnable listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeChangeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    static void fireChanged() {
        for (Runnable listener : LISTENERS) {
            Runnable notification = () -> {
                if (!LISTENERS.contains(listener)) return;
                try {
                    listener.run();
                } catch (RuntimeException failure) {
                    log.warn("protege-mcp: a view failed to refresh provider configuration",
                            failure);
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                notification.run();
            } else {
                SwingUtilities.invokeLater(notification);
            }
        }
    }
}
