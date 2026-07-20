package rationals;

import java.util.Objects;

/** A binary-compatible immutable transition used by HermiT. */
public final class Transition {

    private final State start;
    private final Object label;
    private final State end;

    public Transition(State start, Object label, State end) {
        this.start = start;
        this.label = label;
        this.end = end;
    }

    public State start() {
        return start;
    }

    public Object label() {
        return label;
    }

    public State end() {
        return end;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transition transition)) {
            return false;
        }
        return start == transition.start && end == transition.end
                && Objects.equals(label, transition.label);
    }

    @Override
    public int hashCode() {
        int result = System.identityHashCode(start);
        result = 31 * result + Objects.hashCode(label);
        return 31 * result + System.identityHashCode(end);
    }

    @Override
    public String toString() {
        return "(" + start + " , " + (label == null ? "1" : label) + " , " + end + ")";
    }
}
