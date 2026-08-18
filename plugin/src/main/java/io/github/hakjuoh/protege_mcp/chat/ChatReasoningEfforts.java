package io.github.hakjuoh.protege_mcp.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Validation, persistence, and picker normalization for per-model reasoning-effort values. */
public final class ChatReasoningEfforts {

    // Provider-specific variant ids are normally short, but keep this comfortably above any
    // foreseeable CLI value while still bounding what one Preferences entry and combo row can hold.
    private static final int MAX_VALUE_CHARS = 256;
    private static final int MAX_VALUES = 24;

    private ChatReasoningEfforts() {
    }

    /** Normalizes explicit values; the blank CLI-default choice is implicit and therefore omitted. */
    public static List<String> normalize(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (isAcceptable(value)) {
                    normalized.add(value.trim());
                    if (normalized.size() == MAX_VALUES) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(normalized);
    }

    /** Parses a comma-separated Preferences field. Invalid input is reported to the caller. */
    public static ParseResult parseEditorText(String text) {
        String source = text == null ? "" : text.trim();
        if (source.isEmpty()) {
            return new ParseResult(List.of(), "");
        }
        List<String> values = new ArrayList<>();
        for (String part : source.split(",", -1)) {
            String value = part.trim();
            if (value.isEmpty()) {
                return new ParseResult(List.of(), "Remove the empty reasoning effort between, "
                        + "before, or after commas.");
            }
            if (value.length() > MAX_VALUE_CHARS) {
                return new ParseResult(List.of(), "Each reasoning effort can contain at most "
                        + MAX_VALUE_CHARS + " characters.");
            }
            if (!hasAllowedCharacters(value)) {
                return new ParseResult(List.of(), "Reasoning efforts may contain only ASCII "
                        + "letters, numbers, '.', '_' and '-'. Separate values with commas.");
            }
            values.add(value);
        }
        if (new LinkedHashSet<>(values).size() > MAX_VALUES) {
            return new ParseResult(List.of(),
                    "A model can have at most " + MAX_VALUES + " reasoning efforts.");
        }
        List<String> normalized = normalize(values);
        return new ParseResult(normalized, "");
    }

    public static String editorText(List<String> values) {
        return String.join(", ", normalize(values));
    }

    public static String serialize(List<String> values) {
        return String.join("\n", normalize(values));
    }

    public static List<String> parseStored(String stored) {
        return stored == null || stored.isBlank()
                ? List.of()
                : normalize(List.of(stored.split("\\R", -1)));
    }

    /** Picker values always start with blank, meaning the client's default behavior. */
    public static List<String> withDefault(List<String> explicitValues) {
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(normalize(explicitValues));
        return List.copyOf(values);
    }

    public static boolean isAcceptable(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty() && trimmed.length() <= MAX_VALUE_CHARS
                && hasAllowedCharacters(trimmed);
    }

    private static boolean hasAllowedCharacters(String value) {
        return value.chars().allMatch(c -> c == '-' || c == '_' || c == '.'
                || c >= '0' && c <= '9'
                || c >= 'A' && c <= 'Z'
                || c >= 'a' && c <= 'z');
    }

    public static int maxValues() {
        return MAX_VALUES;
    }

    public record ParseResult(List<String> values, String error) {
        public boolean valid() {
            return error == null || error.isEmpty();
        }
    }
}
