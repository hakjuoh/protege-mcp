package io.github.hakjuoh.protege_mcp.sssom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class SssomListValuesTest {

    @Test
    void decodesEscapedPipesAndBackslashesWithoutEatingOrdinaryBackslashes() {
        assertEquals(List.of("Alice|Bob", "Charlie"),
                SssomListValues.decode("Alice\\|Bob|Charlie"));
        assertEquals(List.of("Alice\\Bob", "Charlie\\", "David\\|Eve\\"),
                SssomListValues.decode("Alice\\Bob|Charlie\\\\|David\\\\\\|Eve\\"));
        assertEquals(List.of("", "middle", ""), SssomListValues.decode("|middle|"));
    }

    @Test
    void canonicalFormUsesDecodedListSemantics() {
        assertEquals(SssomListValues.canonical("A\\\\B|C"),
                SssomListValues.canonical("A\\B|C"));
    }
}
