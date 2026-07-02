package io.github.hakjuoh.tools;

/**
 * A yes/no gate the write tools consult before applying a mutation when the "confirm writes" preference
 * is on. Kept as a toolkit-agnostic seam so the {@code tools} layer stays free of Swing/AWT (and thus
 * headless-testable): the live Swing dialog implementation lives in the {@code ui} layer and is injected
 * through {@link ToolContext}. A {@code null} confirmer with confirmation enabled fails closed — the
 * write is declined rather than silently allowed.
 */
@FunctionalInterface
public interface WriteConfirmer {

    /**
     * @param summary a one-line, human-readable description of the requested action
     * @return {@code true} to allow the write, {@code false} to decline it
     */
    boolean confirm(String summary);
}
