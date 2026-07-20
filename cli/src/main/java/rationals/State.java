package rationals;

/**
 * Binary-compatible state view used by HermiT's object-property automata.
 *
 * <p>HermiT 1.3.8 links these two methods by name. The implementation is owned by {@link Automaton}
 * and backed by AutomataLib rather than the retired JAutomata implementation.</p>
 */
public interface State {

    boolean isInitial();

    boolean isTerminal();
}
