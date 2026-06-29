package io.github.hakjuoh.protege_mcp.chat;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One user turn to run through a {@link ChatProvider}.
 *
 * @param model     provider model id/alias, or blank to use the CLI's own default model
 * @param prompt    the user's visible message (attachment placeholders, not large pasted bodies)
 * @param sessionId the provider session/thread id to resume, or {@code null} to start a new one
 * @param endpoint  the loopback MCP server the CLI should attach to
 * @param attachments hidden prompt context represented by placeholders in {@code prompt}
 */
public record ChatRequest(String model, String prompt, String sessionId, McpEndpoint endpoint,
        List<ChatAttachment> attachments) {

    public ChatRequest(String model, String prompt, String sessionId, McpEndpoint endpoint) {
        this(model, prompt, sessionId, endpoint, List.of());
    }

    public ChatRequest {
        prompt = prompt == null ? "" : prompt;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /**
     * The actual prompt passed to the provider CLI. The visible prompt stays compact in the transcript,
     * while large pasted text and attached file paths are appended here so the model can resolve the
     * placeholders it sees in the user's message.
     */
    public String providerPrompt() {
        if (attachments.isEmpty()) {
            return prompt;
        }
        StringBuilder sb = new StringBuilder(prompt);
        sb.append("\n\nAttached context follows. The user's message above may refer to these placeholders; ")
                .append("use the full pasted text or local file paths below when answering.\n");
        for (ChatAttachment a : attachments) {
            sb.append("\n").append(a.placeholder()).append("\n");
            switch (a.kind()) {
                case PASTED_TEXT -> {
                    if (a.file() != null) {
                        sb.append("Full pasted text saved to local file: ")
                                .append(a.file().getAbsolutePath())
                                .append(" — read it for the complete content.\n");
                    } else {
                        sb.append("Full pasted text:\n")
                                .append(a.text())
                                .append("\nEnd pasted text.\n");
                    }
                }
                case IMAGE -> sb.append("Image file path: ")
                        .append(a.file().getAbsolutePath())
                        .append(mediaSuffix(a))
                        .append("\n");
                case FILE -> sb.append("Attached file path: ")
                        .append(a.file().getAbsolutePath())
                        .append(mediaSuffix(a))
                        .append("\n");
                default -> {
                }
            }
        }
        return sb.toString();
    }

    /** Image files that providers with native image flags can pass outside the text prompt. */
    public List<File> imageFiles() {
        return attachments.stream()
                .filter(a -> a.kind() == ChatAttachment.Kind.IMAGE)
                .map(ChatAttachment::file)
                .toList();
    }

    /** Parent folders for local file attachments, deduplicated in prompt order. */
    public List<File> attachmentDirectories() {
        Set<File> dirs = new LinkedHashSet<>();
        for (ChatAttachment a : attachments) {
            File file = a.file();
            if (file != null) {
                File parent = file.getAbsoluteFile().getParentFile();
                if (parent != null) {
                    dirs.add(parent);
                }
            }
        }
        return List.copyOf(dirs);
    }

    private static String mediaSuffix(ChatAttachment a) {
        return a.mediaType() == null ? "" : " (" + a.mediaType() + ")";
    }
}
