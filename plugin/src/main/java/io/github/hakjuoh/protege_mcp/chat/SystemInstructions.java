package io.github.hakjuoh.protege_mcp.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the global ontology development system instruction provided to chat assistants.
 */
public final class SystemInstructions {

    static final String RESOURCE = "/io/github/hakjuoh/protege_mcp/chat/system-instruction.md";

    private SystemInstructions() {}

    /**
     * Returns the global system instruction text framing the assistant's role across the
     * ontology development lifecycle and mapping user tasks to Protégé MCP tools.
     */
    public static String get() {
        return Holder.INSTANCE;
    }

    static String load(InputStream input) {
        if (input == null) {
            throw new IllegalStateException("System instruction resource is missing: " + RESOURCE);
        }
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read system instruction resource " + RESOURCE, e);
        }
    }

    private static final class Holder {
        private static final String INSTANCE = loadResource();

        private static String loadResource() {
            return load(SystemInstructions.class.getResourceAsStream(RESOURCE));
        }
    }
}
