package io.github.hakjuoh.protege_mcp.sssom;

import java.util.ArrayList;
import java.util.List;

/** SSSOM TSV pipe-list decoding with backslash escaping for pipes and backslashes. */
final class SssomListValues {

    private SssomListValues() {
    }

    static List<String> decode(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        int index = 0;
        while (index < raw.length()) {
            char current = raw.charAt(index);
            if (current == '|') {
                values.add(value.toString());
                value.setLength(0);
                index++;
            } else if (current == '\\' && index + 1 < raw.length()
                    && (raw.charAt(index + 1) == '\\' || raw.charAt(index + 1) == '|')) {
                value.append(raw.charAt(index + 1));
                index += 2;
            } else {
                value.append(current);
                index++;
            }
        }
        values.add(value.toString());
        return List.copyOf(values);
    }

    static String canonical(String raw) {
        List<String> values = decode(raw);
        StringBuilder canonical = new StringBuilder().append(values.size()).append(':');
        for (String value : values) {
            String normalized = value.trim();
            canonical.append(normalized.length()).append(':').append(normalized);
        }
        return canonical.toString();
    }
}
