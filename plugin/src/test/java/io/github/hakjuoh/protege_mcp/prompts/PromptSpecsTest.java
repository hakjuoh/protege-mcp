package io.github.hakjuoh.protege_mcp.prompts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Method-level tests for the {@link PromptSpecs} factory — the prompt-side mirror of
 * {@code ToolSpecsTest}. {@code PromptSpecs.of} is a thin, pure wrapper that builds the SDK
 * {@link Prompt} and wraps the {@link PromptTemplate} in a handler producing a single USER
 * {@link TextContent} message, so these tests assert the wiring: name/description/arguments flow
 * through, the handler delegates to the template with the request's arguments, and the result
 * echoes the prompt description.
 */
class PromptSpecsTest {

    private static final List<PromptArgument> ARGS = Collections.singletonList(
            new PromptArgument("x", "an x", Boolean.TRUE));

    // ---- of(): prompt wiring ------------------------------------------------------------------

    @Test
    void ofBuildsPromptWithNameDescriptionAndArguments() {
        SyncPromptSpecification spec = PromptSpecs.of("custom", "a custom prompt", ARGS, a -> "t");
        Prompt p = spec.prompt();
        assertAll(
                () -> assertEquals("custom", p.name()),
                () -> assertEquals("a custom prompt", p.description()),
                () -> assertEquals(1, p.arguments().size()),
                () -> assertEquals("x", p.arguments().get(0).name()));
    }

    @Test
    void ofWithEmptyArgumentListProducesNoArguments() {
        SyncPromptSpecification spec = PromptSpecs.of("empty", "no-arg prompt",
                Collections.emptyList(), a -> "t");
        assertTrue(spec.prompt().arguments().isEmpty(), "no arguments expected");
    }

    @Test
    void ofProducesANonNullHandler() {
        assertNotNull(PromptSpecs.of("n", "d", Collections.emptyList(), a -> "t").promptHandler());
    }

    // ---- handler: delegation to the template --------------------------------------------------

    @Test
    void handlerDelegatesToTemplateWithRequestArguments() {
        SyncPromptSpecification spec = PromptSpecs.of("custom", "a custom prompt", ARGS,
                a -> "rendered:" + (a == null ? "null" : a.get("x")));
        Map<String, Object> reqArgs = new HashMap<>();
        reqArgs.put("x", "hi");
        GetPromptResult result = spec.promptHandler().apply(null, new GetPromptRequest("custom", reqArgs));
        assertEquals("a custom prompt", result.description(),
                "handler result description echoes the prompt description");
        assertEquals(1, result.messages().size());
        PromptMessage m = result.messages().get(0);
        assertEquals(Role.USER, m.role());
        assertInstanceOf(TextContent.class, m.content());
        assertEquals("rendered:hi", ((TextContent) m.content()).text(),
                "handler delegates to template.render with the request arguments");
    }

    @Test
    void handlerPassesNullArgumentsThroughToTemplate() {
        SyncPromptSpecification spec = PromptSpecs.of("n", "d", Collections.emptyList(),
                a -> a == null ? "was-null" : "was-map");
        GetPromptResult result = spec.promptHandler().apply(null, new GetPromptRequest("n", null));
        assertEquals("was-null", ((TextContent) result.messages().get(0).content()).text(),
                "a null request argument map reaches the template as null (templates handle it)");
    }

    @Test
    void handlerProducesExactlyOneUserMessage() {
        SyncPromptSpecification spec = PromptSpecs.of("n", "d", Collections.emptyList(), a -> "t");
        GetPromptResult result = spec.promptHandler().apply(null, new GetPromptRequest("n", null));
        assertEquals(1, result.messages().size(), "one message");
        assertEquals(Role.USER, result.messages().get(0).role(), "USER role");
    }

    @Test
    void handlerPreservesTheExactTemplateOutput() {
        String text = "line1\nline2 with \"quotes\" and unicode — Protégé";
        SyncPromptSpecification spec = PromptSpecs.of("n", "d", Collections.emptyList(), a -> text);
        GetPromptResult result = spec.promptHandler().apply(null, new GetPromptRequest("n", null));
        assertSame(text, ((TextContent) result.messages().get(0).content()).text(),
                "the template's rendered text is used verbatim");
    }

    @Test
    void handlerPropagatesTemplateExceptions() {
        SyncPromptSpecification spec = PromptSpecs.of("n", "d", Collections.emptyList(),
                a -> { throw new IllegalStateException("boom"); });
        assertThrows(IllegalStateException.class,
                () -> spec.promptHandler().apply(null, new GetPromptRequest("n", null)),
                "template failures surface to the SDK rather than being swallowed");
    }

    // ---- of(): validation via the SDK constructors --------------------------------------------

    @Test
    void ofPassesThroughNullDescription() {
        SyncPromptSpecification spec = PromptSpecs.of("n", null, Collections.emptyList(), a -> "t");
        assertNull(spec.prompt().description(), "a null description is passed through");
    }

    // ---- utility-class shape ------------------------------------------------------------------

    @Test
    void classIsFinalUtility() {
        assertTrue(Modifier.isFinal(PromptSpecs.class.getModifiers()),
                "PromptSpecs is a utility class and must be final");
    }

    @Test
    void privateConstructorIsInaccessibleButInvocableViaReflection() throws Exception {
        Constructor<PromptSpecs> ctor = PromptSpecs.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                "the no-arg constructor must be private (utility-class pattern)");
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance(), "reflective construction still yields an instance");
    }
}
