package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ChatReasoningEffortsTest {

    @Test
    void editorValuesAreTrimmedDeduplicatedAndRoundTripThroughPreferences() {
        ChatReasoningEfforts.ParseResult parsed =
                ChatReasoningEfforts.parseEditorText(" low, high, low ");

        assertTrue(parsed.valid());
        assertEquals(List.of("low", "high"), parsed.values());
        assertEquals("low, high", ChatReasoningEfforts.editorText(parsed.values()));
        assertEquals(parsed.values(), ChatReasoningEfforts.parseStored(
                ChatReasoningEfforts.serialize(parsed.values())));
        assertEquals(List.of("", "low", "high"),
                ChatReasoningEfforts.withDefault(parsed.values()));
    }

    @Test
    void blankMeansOnlyTheImplicitClientDefault() {
        ChatReasoningEfforts.ParseResult parsed = ChatReasoningEfforts.parseEditorText("  ");

        assertTrue(parsed.valid());
        assertEquals(List.of(), parsed.values());
        assertEquals(List.of(""), ChatReasoningEfforts.withDefault(parsed.values()));
    }

    @Test
    void invalidTokensAndUnboundedListsAreRejected() {
        assertEquals("Reasoning efforts may contain only ASCII letters, numbers, '.', '_' and '-'. "
                        + "Separate values with commas.",
                ChatReasoningEfforts.parseEditorText("low effort").error());
        assertEquals("Remove the empty reasoning effort between, before, or after commas.",
                ChatReasoningEfforts.parseEditorText("low,").error());
        assertEquals("Remove the empty reasoning effort between, before, or after commas.",
                ChatReasoningEfforts.parseEditorText("low,,high").error());
        assertTrue(ChatReasoningEfforts.parseEditorText("x".repeat(256)).valid());
        assertEquals("Each reasoning effort can contain at most 256 characters.",
                ChatReasoningEfforts.parseEditorText("x".repeat(257)).error());
        for (String invalid : List.of("low;high", "high!", "héroïque", "low/high")) {
            assertFalse(ChatReasoningEfforts.parseEditorText(invalid).valid(), invalid);
        }
        String tooMany = String.join(",", IntStream.rangeClosed(
                0, ChatReasoningEfforts.maxValues()).mapToObj(value -> "e" + value).toList());
        assertFalse(ChatReasoningEfforts.parseEditorText(tooMany).valid());
    }

    @Test
    void futureClientSpecificIdentifierTokensRemainEditable() {
        ChatReasoningEfforts.ParseResult parsed =
                ChatReasoningEfforts.parseEditorText("turbo-2.5_x, ULTRA_2");

        assertTrue(parsed.valid());
        assertEquals(List.of("turbo-2.5_x", "ULTRA_2"), parsed.values());
    }
}
