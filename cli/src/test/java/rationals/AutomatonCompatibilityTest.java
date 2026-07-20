package rationals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class AutomatonCompatibilityTest {

    @Test
    void storesGenericAndEpsilonTransitionsInAutomataLib() {
        Automaton automaton = new Automaton();
        State initial = automaton.addState(true, false);
        State middle = automaton.addState(false, false);
        State terminal = automaton.addState(false, true);

        assertTrue(automaton.addTransition(new Transition(initial, "role", middle), "invalid"));
        assertTrue(automaton.addTransition(new Transition(middle, null, terminal), "invalid"));

        assertEquals(Set.of(initial, middle, terminal), automaton.states());
        assertEquals(Set.of(initial), automaton.initials());
        assertEquals(Set.of(terminal), automaton.terminals());
        assertEquals(2, automaton.alphabet().size());
        assertTrue(automaton.alphabet().contains("role"));
        assertTrue(automaton.alphabet().contains(null));
        assertEquals(Set.of(new Transition(initial, "role", middle),
                new Transition(middle, null, terminal)), automaton.delta());
        assertEquals(Set.of(new Transition(middle, null, terminal)),
                automaton.deltaFrom(middle, terminal));
    }

    @Test
    void cloneOwnsIndependentStatesAndTransitions() {
        Automaton original = new Automaton();
        State start = original.addState(true, false);
        State end = original.addState(false, true);
        original.addTransition(new Transition(start, "role", end), "invalid");

        Automaton copy = (Automaton) original.clone();
        assertNotSame(original.initials().iterator().next(), copy.initials().iterator().next());
        assertEquals(original.states().size(), copy.states().size());
        assertEquals(original.delta().size(), copy.delta().size());

        copy.addState(false, false);
        assertEquals(2, original.states().size());
        assertEquals(3, copy.states().size());
    }

    @Test
    void rejectsStatesFromAnotherAutomaton() {
        Automaton first = new Automaton();
        Automaton second = new Automaton();
        State firstState = first.addState(true, false);
        State secondState = second.addState(false, true);
        Transition foreign = new Transition(firstState, "role", secondState);

        assertFalse(first.validTransition(foreign));
        assertFalse(first.addTransition(foreign, null));
        assertThrows(IllegalArgumentException.class,
                () -> first.addTransition(foreign, "foreign state"));
    }
}
