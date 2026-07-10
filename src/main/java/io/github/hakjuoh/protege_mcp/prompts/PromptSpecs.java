package io.github.hakjuoh.protege_mcp.prompts;

import java.util.Collections;
import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Factory for {@link SyncPromptSpecification}s (a name + description + arguments {@link Prompt} plus
 * the {@link PromptTemplate} that renders its single user message) — the prompt-side mirror of
 * {@code ToolSpecs}.
 */
public final class PromptSpecs {

    private PromptSpecs() {
    }

    public static SyncPromptSpecification of(String name, String description,
            List<PromptArgument> arguments, PromptTemplate template) {
        Prompt prompt = new Prompt(name, description, arguments);
        return new SyncPromptSpecification(prompt, (exchange, request) -> {
            String text = template.render(request.arguments());
            PromptMessage message = new PromptMessage(Role.USER, new TextContent(text));
            return new GetPromptResult(description, Collections.singletonList(message));
        });
    }
}
