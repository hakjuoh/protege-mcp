package io.github.hakjuoh.protege_mcp.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Bounded metadata I/O, validation, and provider defaults shared by model parsers and catalog. */
final class ChatModelMetadataSupport {
    private static final String BYTE_ORDER_MARK = "\uFEFF";
    private static final long MAX_METADATA_BYTES = 5L * 1024 * 1024;
    private static final int MAX_MODEL_ID_CHARS = 100;
    private static final int MAX_MODELS = 50;
    private static final int UNICODE_LINE_SEPARATOR = 0x2028;
    private static final int UNICODE_PARAGRAPH_SEPARATOR = 0x2029;

    private ChatModelMetadataSupport() {}

    static List<String> codexReasoningEfforts() {
        return List.of("", "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    }

    static void addDiscoveredModel(Set<String> models, String model) {
        if (isAcceptableModelId(model) && models.size() < MAX_MODELS) {
            models.add(model.trim());
        }
    }

    static boolean isAcceptableModelId(String model) {
        if (model == null) {
            return false;
        }
        String trimmed = model.trim();
        return !trimmed.isEmpty()
                && !trimmed.equals("(default)")
                && trimmed.length() <= MAX_MODEL_ID_CHARS
                && trimmed.codePoints()
                        .noneMatch(
                                codePoint ->
                                        Character.isISOControl(codePoint)
                                                || isLineSeparator(codePoint));
    }

    static int maxModelIdChars() {
        return MAX_MODEL_ID_CHARS;
    }

    static int maxModels() {
        return MAX_MODELS;
    }

    static String readMetadata(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_METADATA_BYTES) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8).replace(BYTE_ORDER_MARK, "");
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static boolean isLineSeparator(int codePoint) {
        return codePoint == UNICODE_LINE_SEPARATOR || codePoint == UNICODE_PARAGRAPH_SEPARATOR;
    }
}
