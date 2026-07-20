package rationals;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.automatalib.alphabet.impl.GrowingMapAlphabet;
import net.automatalib.automaton.fsa.impl.CompactNFA;

/**
 * Narrow binary-compatibility layer for the automaton API used by HermiT 1.3.8.
 *
 * <p>The old HermiT artifact embeds a full JAutomata fork, although HermiT itself directly calls only
 * this class, {@link State}, and {@link Transition}. This implementation preserves those linked method
 * descriptors and delegates NFA state/transition storage to maintained AutomataLib. A private sentinel
 * represents epsilon because HermiT uses {@code null} for epsilon while AutomataLib input symbols are
 * non-null.</p>
 */
public final class Automaton implements Cloneable {

    private enum Epsilon {
        SYMBOL
    }

    private final GrowingMapAlphabet<Object> alphabet;
    private final CompactNFA<Object> delegate;
    private final Map<Integer, AutomataLibState> states;
    private final Set<Object> labels;

    public Automaton() {
        alphabet = new GrowingMapAlphabet<>();
        delegate = new CompactNFA<>(alphabet);
        states = new LinkedHashMap<>();
        labels = new LinkedHashSet<>();
    }

    public State addState(boolean initial, boolean terminal) {
        Integer id = initial ? delegate.addInitialState(terminal) : delegate.addState(terminal);
        AutomataLibState state = new AutomataLibState(id);
        states.put(id, state);
        return state;
    }

    public Set<Object> alphabet() {
        return Collections.unmodifiableSet(labels);
    }

    public Set<State> states() {
        return stateSet(StateKind.ALL);
    }

    public Set<State> initials() {
        return stateSet(StateKind.INITIAL);
    }

    public Set<State> terminals() {
        return stateSet(StateKind.TERMINAL);
    }

    public Set<Transition> delta() {
        Set<Transition> result = new LinkedHashSet<>();
        for (AutomataLibState start : states.values()) {
            for (Object symbol : alphabet) {
                for (Integer successor : delegate.getSuccessors(start.id, symbol)) {
                    result.add(new Transition(start, externalLabel(symbol), states.get(successor)));
                }
            }
        }
        return result;
    }

    public Set<Transition> deltaFrom(State from, State to) {
        Set<Transition> result = new LinkedHashSet<>();
        for (Transition transition : delta()) {
            if (transition.start() == from && transition.end() == to) {
                result.add(transition);
            }
        }
        return result;
    }

    public boolean validTransition(Transition transition) {
        return transition == null || owns(transition.start()) && owns(transition.end());
    }

    public boolean addTransition(Transition transition) {
        if (!validTransition(transition)) {
            return false;
        }
        if (transition == null) {
            throw new NullPointerException("transition");
        }
        Object symbol = internalLabel(transition.label());
        delegate.addAlphabetSymbol(symbol);
        labels.add(transition.label());
        delegate.addTransition(idOf(transition.start()), symbol, idOf(transition.end()), null);
        return true;
    }

    public boolean addTransition(Transition transition, String ifInvalid) {
        if (validTransition(transition)) {
            return addTransition(transition);
        }
        if (ifInvalid != null) {
            throw new IllegalArgumentException(ifInvalid);
        }
        return false;
    }

    @Override
    public Object clone() {
        Automaton copy = new Automaton();
        Map<State, State> copiedStates = new IdentityHashMap<>();
        for (AutomataLibState state : states.values()) {
            copiedStates.put(state, copy.addState(state.isInitial(), state.isTerminal()));
        }
        for (Transition transition : delta()) {
            copy.addTransition(new Transition(copiedStates.get(transition.start()), transition.label(),
                    copiedStates.get(transition.end())), null);
        }
        return copy;
    }

    private Set<State> stateSet(StateKind kind) {
        Set<State> result = new LinkedHashSet<>();
        for (AutomataLibState state : states.values()) {
            if (kind == StateKind.ALL || (kind == StateKind.INITIAL && state.isInitial())
                    || (kind == StateKind.TERMINAL && state.isTerminal())) {
                result.add(state);
            }
        }
        return result;
    }

    private boolean owns(State state) {
        return state instanceof AutomataLibState candidate && candidate.owner() == this;
    }

    private int idOf(State state) {
        if (!owns(state)) {
            throw new IllegalArgumentException("state belongs to another automaton");
        }
        return ((AutomataLibState) state).id;
    }

    private static Object internalLabel(Object label) {
        return label == null ? Epsilon.SYMBOL : label;
    }

    private static Object externalLabel(Object label) {
        return label == Epsilon.SYMBOL ? null : label;
    }

    private enum StateKind {
        ALL,
        INITIAL,
        TERMINAL
    }

    private final class AutomataLibState implements State {

        private final int id;

        private AutomataLibState(int id) {
            this.id = id;
        }

        private Automaton owner() {
            return Automaton.this;
        }

        @Override
        public boolean isInitial() {
            return delegate.getInitialStates().contains(id);
        }

        @Override
        public boolean isTerminal() {
            return delegate.isAccepting(id);
        }

        @Override
        public String toString() {
            return Integer.toString(id);
        }
    }
}
