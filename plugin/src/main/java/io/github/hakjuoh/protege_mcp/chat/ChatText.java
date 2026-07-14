package io.github.hakjuoh.protege_mcp.chat;

import java.util.Locale;

/**
 * Pure formatting/heuristic helpers for the chat UI, split out of {@code ChatView} (a Swing component)
 * so they can be unit-tested headless. Public because the view lives in a different package; behavior is
 * identical to the private helpers these replaced.
 */
public final class ChatText {

    private ChatText() {
    }

    /** Human-readable token-usage summary line for the status label (trailing padding preserved). */
    public static String formatUsage(ChatUsage u) {
        StringBuilder sb = new StringBuilder("tokens: ");
        sb.append(u.inputTokens() < 0 ? "?" : u.inputTokens()).append(" in");
        if (u.cachedInputTokens() > 0) {
            sb.append(" (").append(u.cachedInputTokens()).append(" cached)");
        }
        sb.append(" / ").append(u.outputTokens() < 0 ? "?" : u.outputTokens()).append(" out");
        if (u.costUsd() != null) {
            sb.append(String.format("  ·  $%.4f", u.costUsd()));
        }
        return sb.toString() + "   ";
    }

    /**
     * Whether a pasted string should be compacted into an attachment rather than left inline: true when
     * it is at least {@code attachThreshold} chars, or has at least {@code lineThreshold} lines while
     * being at least {@code lineMinChars} chars (so a short multi-line snippet is not needlessly hidden).
     */
    public static boolean shouldAttachPastedText(String text, int attachThreshold, int lineThreshold,
            int lineMinChars) {
        if (text == null) {
            return false;
        }
        if (text.length() >= attachThreshold) {
            return true;
        }
        if (text.length() < lineMinChars) {
            return false;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++lines >= lineThreshold) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code fileName} has a known raster-image extension (case-insensitive). */
    public static boolean isImageFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp")
                || name.endsWith(".tif") || name.endsWith(".tiff");
    }

    /** The image media type for {@code fileName}'s extension, or {@code "image/*"} if unknown/null. */
    public static String imageMediaType(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (name.endsWith(".tif") || name.endsWith(".tiff")) {
            return "image/tiff";
        }
        return "image/*";
    }
}
