package io.github.hakjuoh.chat;

import java.io.File;
import java.util.Objects;

/** One non-text chat input item represented in the prompt by a small placeholder. */
public record ChatAttachment(Kind kind, String label, String text, File file, String mediaType) {

    public enum Kind {
        PASTED_TEXT,
        IMAGE,
        FILE
    }

    public ChatAttachment {
        kind = Objects.requireNonNull(kind, "kind");
        label = Objects.requireNonNull(label, "label").trim();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (kind == Kind.PASTED_TEXT) {
            text = Objects.requireNonNull(text, "text");
            // A large pasted body may be externalized to a temp file (referenced by path instead of inlined,
            // so it can never overflow the command line); a small body keeps file == null and is inlined.
            file = file == null ? null : file.getAbsoluteFile();
        } else {
            file = Objects.requireNonNull(file, "file").getAbsoluteFile();
            text = null;
        }
        if (mediaType != null && mediaType.isBlank()) {
            mediaType = null;
        }
    }

    public static ChatAttachment pastedText(String label, String text) {
        return new ChatAttachment(Kind.PASTED_TEXT, label, text, null, "text/plain");
    }

    /** A pasted body buffered to {@code file}; {@link ChatRequest#providerPrompt()} references the path. */
    public static ChatAttachment pastedTextFile(String label, String text, File file) {
        return new ChatAttachment(Kind.PASTED_TEXT, label, text,
                Objects.requireNonNull(file, "file"), "text/plain");
    }

    public static ChatAttachment image(String label, File file, String mediaType) {
        return new ChatAttachment(Kind.IMAGE, label, null, file,
                mediaType == null || mediaType.isBlank() ? "image/*" : mediaType);
    }

    public static ChatAttachment file(String label, File file, String mediaType) {
        return new ChatAttachment(Kind.FILE, label, null, file, mediaType);
    }

    public String placeholder() {
        return "[" + label + "]";
    }

    /**
     * Strip the characters a free-text name could use to forge or embed another attachment's bracketed
     * placeholder ({@code [}, {@code ]}, {@code #}), so a filename can never collide with the substring match
     * that pairs a placeholder back to its attachment.
     */
    public static String sanitizeLabel(String name) {
        return name == null ? "" : name.replaceAll("[\\[\\]#]", "_");
    }
}
