package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

import org.yaml.snakeyaml.LoaderOptions;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

/** Applies recursive merge patches while leaving every unaffected YAML byte untouched. */
final class ProjectPolicyPatcher {

    private static final ObjectMapper YAML = yamlMapper();

    private ProjectPolicyPatcher() {
    }

    static String apply(String yaml, Object rawPatch) throws IOException {
        Map<String, Object> patch = stringKeyMap(rawPatch, "patch");
        if (patch.isEmpty()) {
            throw new IllegalArgumentException("'patch' must contain at least one policy field.");
        }
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ProjectPolicyLoader.MAX_POLICY_BYTES) {
            throw new IOException("Project policy is too large");
        }
        rejectAliases(bytes);
        Map<String, Object> parsed = YAML.readValue(bytes,
                new TypeReference<LinkedHashMap<String, Object>>() { });
        Map<String, Object> authored = parsed == null ? new LinkedHashMap<>() : parsed;
        String patched = yaml;
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object merged = merge(authored.get(key), entry.getValue(), "patch." + key);
            String replacement = null;
            if (merged == Removed.INSTANCE) {
                authored.remove(key);
            } else {
                authored.put(key, merged);
                replacement = renderTopLevel(key, merged);
            }
            patched = replaceTopLevelSection(patched, key, replacement);
        }
        return patched;
    }

    private static Object merge(Object existing, Object patch, String path) {
        if (patch == null) {
            return Removed.INSTANCE;
        }
        if (!(patch instanceof Map<?, ?>)) {
            return patch;
        }
        Map<String, Object> patchMap = stringKeyMap(patch, path);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing instanceof Map<?, ?>) {
            merged.putAll(stringKeyMap(existing, path));
        }
        for (Map.Entry<String, Object> entry : patchMap.entrySet()) {
            Object value = merge(merged.get(entry.getKey()), entry.getValue(),
                    path + "." + entry.getKey());
            if (value == Removed.INSTANCE) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), value);
            }
        }
        return merged;
    }

    private static Map<String, Object> stringKeyMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("'" + path + "' must be an object.");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("'" + path
                        + "' contains a blank or non-string field name.");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static String renderTopLevel(String key, Object value) throws IOException {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put(key, value);
        return YAML.writeValueAsString(section);
    }

    private static String replaceTopLevelSection(String yaml, String key, String replacement) {
        List<Line> lines = lines(yaml);
        int startLine = topLevelKey(yaml, lines, key);
        String separator = lineSeparator(yaml, lines, startLine);
        String rendered = replacement == null ? "" : replacement.replace("\n", separator);
        if (startLine < 0) {
            if (rendered.isEmpty()) {
                return yaml;
            }
            String boundary = yaml.isEmpty() || endsWithLineBreak(yaml) ? "" : separator;
            return yaml + boundary + rendered;
        }

        int endLine = nextTopLevelKey(yaml, lines, startLine + 1);
        while (endLine > startLine + 1
                && isBlankOrTopLevelComment(lines.get(endLine - 1).content(yaml))) {
            endLine--;
        }
        int start = lines.get(startLine).start();
        int end = endLine < lines.size() ? lines.get(endLine).start() : yaml.length();
        return yaml.substring(0, start) + rendered + yaml.substring(end);
    }

    private static List<Line> lines(String yaml) {
        List<Line> lines = new ArrayList<>();
        int cursor = 0;
        while (cursor < yaml.length()) {
            int start = cursor;
            while (cursor < yaml.length() && yaml.charAt(cursor) != '\n'
                    && yaml.charAt(cursor) != '\r') {
                cursor++;
            }
            int contentEnd = cursor;
            if (cursor < yaml.length() && yaml.charAt(cursor) == '\r'
                    && cursor + 1 < yaml.length() && yaml.charAt(cursor + 1) == '\n') {
                cursor += 2;
            } else if (cursor < yaml.length()) {
                cursor++;
            }
            lines.add(new Line(start, contentEnd, cursor));
        }
        return lines;
    }

    private static int topLevelKey(String yaml, List<Line> lines, String key) {
        List<String> prefixes = List.of(key + ":", "'" + key + "':", "\"" + key + "\":");
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).content(yaml);
            for (String prefix : prefixes) {
                if (line.startsWith(prefix)
                        && (line.length() == prefix.length()
                                || Character.isWhitespace(line.charAt(prefix.length())))) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static int nextTopLevelKey(String yaml, List<Line> lines, int from) {
        for (int index = from; index < lines.size(); index++) {
            String line = lines.get(index).content(yaml);
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))
                    && !line.startsWith("#")) {
                return index;
            }
        }
        return lines.size();
    }

    private static String lineSeparator(String yaml, List<Line> lines, int preferredLine) {
        if (preferredLine >= 0 && lines.get(preferredLine).hasSeparator()) {
            return lines.get(preferredLine).separator(yaml);
        }
        for (Line line : lines) {
            if (line.hasSeparator()) {
                return line.separator(yaml);
            }
        }
        return "\n";
    }

    private static boolean endsWithLineBreak(String value) {
        return value.endsWith("\n") || value.endsWith("\r");
    }

    private static boolean isBlankOrTopLevelComment(String line) {
        return line.isBlank() || line.startsWith("#");
    }

    private static ObjectMapper yamlMapper() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setNestingDepthLimit(100);
        loaderOptions.setCodePointLimit((int) ProjectPolicyLoader.MAX_POLICY_BYTES);
        YAMLFactory factory = YAMLFactory.builder()
                .loaderOptions(loaderOptions)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100)
                        .maxStringLength((int) ProjectPolicyLoader.MAX_POLICY_BYTES).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                .build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    private static void rejectAliases(byte[] bytes) throws IOException {
        try (JsonParser parser = YAML.getFactory().createParser(bytes)) {
            while (parser.nextToken() != null) {
                if (parser instanceof YAMLParser yaml && yaml.isCurrentAlias()) {
                    throw new IOException("YAML aliases are not supported in project policy patches");
                }
            }
        }
    }

    private record Line(int start, int contentEnd, int end) {
        String content(String source) {
            return source.substring(start, contentEnd);
        }

        boolean hasSeparator() {
            return end > contentEnd;
        }

        String separator(String source) {
            return source.substring(contentEnd, end);
        }
    }

    private enum Removed {
        INSTANCE
    }
}
