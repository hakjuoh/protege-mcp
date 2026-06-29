package io.github.hakjuoh.chat;

/**
 * The loopback MCP endpoint a chat-provider CLI connects back to: the running Approach A server's
 * URL (e.g. {@code http://127.0.0.1:8123/mcp}) and its static bearer token. Resolved from the
 * window's {@code McpServerController} (see {@link io.github.hakjuoh.ui.ChatView}).
 */
public record McpEndpoint(String url, String token) {

    /** The MCP server name the CLIs see (so tools render as {@code mcp__protege__<tool>}). */
    public static final String SERVER_NAME = "protege";
}
