package io.github.hakjuoh.protege_mcp.chat;

/**
 * One user turn to run through a {@link ChatProvider}.
 *
 * @param model     provider model id/alias, or blank to use the CLI's own default model
 * @param prompt    the user's message
 * @param sessionId the provider session/thread id to resume, or {@code null} to start a new one
 * @param endpoint  the loopback MCP server the CLI should attach to
 */
public record ChatRequest(String model, String prompt, String sessionId, McpEndpoint endpoint) {
}
