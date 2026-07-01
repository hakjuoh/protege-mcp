package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Collects the {@link SyncPromptSpecification}s contributed during prompt assembly — the prompt-side
 * mirror of {@link ToolRegistry}. It removes the {@code new ArrayList<>()} / {@code add(...)} /
 * {@code return} boilerplate and is the single point where a prompt specification is built from a
 * name + description + arguments + a {@link Template} that renders the user-message text.
 */
public final class PromptRegistry {

    /** Renders a prompt's user-message text from its (possibly empty) arguments. */
    @FunctionalInterface
    public interface Template {
        String render(Map<String, Object> args);
    }

    private final List<SyncPromptSpecification> prompts = new ArrayList<>();

    /** Register one prompt: its {@code name}, {@code description}, {@code arguments} and text {@code template}. Fluent. */
    public PromptRegistry prompt(String name, String description,
            List<PromptArgument> arguments, Template template) {
        Prompt prompt = new Prompt(name, description, arguments);
        prompts.add(new SyncPromptSpecification(prompt, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String text = template.render(args);
            PromptMessage message = new PromptMessage(Role.USER, new TextContent(text));
            return new GetPromptResult(description, Collections.singletonList(message));
        }));
        return this;
    }

    /** The specifications collected so far, in registration order (the registry's own mutable list). */
    public List<SyncPromptSpecification> build() {
        return prompts;
    }
}
