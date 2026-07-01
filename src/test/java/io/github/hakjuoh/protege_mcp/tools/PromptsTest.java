package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Method-level tests for {@link Prompts}.
 *
 * <p>{@code Prompts} is a pure template/data builder with no OWLAPI, Protégé runtime, Swing, or I/O
 * dependencies, so every test here is deterministic and headless. The public {@link Prompts#all()}
 * API is exercised directly; the private helpers ({@code prompt}, {@code arg}, {@code str}) are
 * exercised via reflection to hit their enumerated edge cases directly, and also transitively
 * through the rendered prompt handlers.
 */
class PromptsTest {

    // ------------------------------------------------------------------ helpers (reflection)

    /** Invoke the private {@code Prompts.str(Map, String, String)} helper. */
    private static String invokeStr(Map<String, Object> args, String key, String fallback) {
        try {
            Method m = Prompts.class.getDeclaredMethod("str", Map.class, String.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, args, key, fallback);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Invoke the private {@code Prompts.arg(String, String, boolean)} helper. */
    private static PromptArgument invokeArg(String name, String description, boolean required) {
        try {
            Method m = Prompts.class.getDeclaredMethod("arg", String.class, String.class, boolean.class);
            m.setAccessible(true);
            return (PromptArgument) m.invoke(null, name, description, required);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Rethrow a checked/unchecked cause preserving its runtime type for assertThrows. */
    private static RuntimeException sneaky(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new RuntimeException(cause);
    }

    /** Find the spec whose prompt name equals {@code name}, or fail. */
    private static SyncPromptSpecification specNamed(String name) {
        for (SyncPromptSpecification s : Prompts.all()) {
            if (name.equals(s.prompt().name())) {
                return s;
            }
        }
        throw new AssertionError("no prompt named " + name);
    }

    /** Render a spec's handler with the given argument map; exchange is unused so pass null. */
    private static GetPromptResult render(SyncPromptSpecification spec, Map<String, Object> args) {
        BiFunction<McpSyncServerExchange, GetPromptRequest, GetPromptResult> h = spec.promptHandler();
        GetPromptRequest req = new GetPromptRequest(spec.prompt().name(), args);
        return h.apply(null, req);
    }

    /** Extract the single message's text from a result. */
    private static String textOf(GetPromptResult result) {
        assertEquals(1, result.messages().size(), "expected exactly one message");
        PromptMessage msg = result.messages().get(0);
        Content c = msg.content();
        assertInstanceOf(TextContent.class, c, "message content should be TextContent");
        return ((TextContent) c).text();
    }

    private static final List<String> EXPECTED_NAMES = List.of(
            "audit_ontology",
            "explain_class",
            "add_subclass_safely",
            "find_and_fix_unsatisfiable",
            "author_sparql_query",
            "model_domain");

    // ------------------------------------------------------------------ all(): structure

    @Test
    void allReturnsNonNullList() {
        assertNotNull(Prompts.all(), "all() must never return null");
    }

    @Test
    void allReturnsExactlySixPrompts() {
        assertEquals(6, Prompts.all().size(), "expected 6 built-in prompts");
    }

    @Test
    void allReturnsExpectedNamesInOrder() {
        List<String> actual = new ArrayList<>();
        for (SyncPromptSpecification s : Prompts.all()) {
            actual.add(s.prompt().name());
        }
        assertEquals(EXPECTED_NAMES, actual, "prompt names/order");
    }

    @Test
    void everyPromptHasNonEmptyName() {
        for (SyncPromptSpecification s : Prompts.all()) {
            String n = s.prompt().name();
            assertNotNull(n, "name null");
            assertFalse(n.isBlank(), "name blank: " + n);
        }
    }

    @Test
    void everyPromptHasNonEmptyDescription() {
        for (SyncPromptSpecification s : Prompts.all()) {
            String d = s.prompt().description();
            assertNotNull(d, "description null for " + s.prompt().name());
            assertFalse(d.isBlank(), "description blank for " + s.prompt().name());
        }
    }

    @Test
    void everyPromptHasAHandler() {
        for (SyncPromptSpecification s : Prompts.all()) {
            assertNotNull(s.promptHandler(), "handler null for " + s.prompt().name());
        }
    }

    @Test
    void allIsIdempotentAndReturnsFreshList() {
        List<SyncPromptSpecification> a = Prompts.all();
        List<SyncPromptSpecification> b = Prompts.all();
        org.junit.jupiter.api.Assertions.assertNotSame(a, b, "each call should build a fresh list");
        List<String> namesA = new ArrayList<>();
        List<String> namesB = new ArrayList<>();
        a.forEach(s -> namesA.add(s.prompt().name()));
        b.forEach(s -> namesB.add(s.prompt().name()));
        assertEquals(namesA, namesB, "same names across calls");
    }

    // ------------------------------------------------------------------ all(): argument shapes

    @Test
    void auditOntologyHasZeroArguments() {
        assertEquals(0, specNamed("audit_ontology").prompt().arguments().size());
    }

    @Test
    void findAndFixUnsatisfiableHasZeroArguments() {
        assertEquals(0, specNamed("find_and_fix_unsatisfiable").prompt().arguments().size());
    }

    @Test
    void explainClassHasSingleRequiredClassArgument() {
        List<PromptArgument> a = specNamed("explain_class").prompt().arguments();
        assertEquals(1, a.size(), "arg count");
        assertEquals("class", a.get(0).name(), "arg name");
        assertEquals(Boolean.TRUE, a.get(0).required(), "arg required");
        assertNotNull(a.get(0).description(), "arg description");
    }

    @Test
    void addSubclassSafelyHasTwoRequiredArguments() {
        List<PromptArgument> a = specNamed("add_subclass_safely").prompt().arguments();
        assertEquals(2, a.size(), "arg count");
        assertEquals("child", a.get(0).name(), "first arg name");
        assertEquals("parent", a.get(1).name(), "second arg name");
        assertAll(
                () -> assertEquals(Boolean.TRUE, a.get(0).required(), "child required"),
                () -> assertEquals(Boolean.TRUE, a.get(1).required(), "parent required"));
    }

    @Test
    void authorSparqlQueryHasSingleRequiredQuestionArgument() {
        List<PromptArgument> a = specNamed("author_sparql_query").prompt().arguments();
        assertEquals(1, a.size(), "arg count");
        assertEquals("question", a.get(0).name(), "arg name");
        assertEquals(Boolean.TRUE, a.get(0).required(), "arg required");
    }

    @Test
    void modelDomainHasSingleRequiredDescriptionArgument() {
        List<PromptArgument> a = specNamed("model_domain").prompt().arguments();
        assertEquals(1, a.size(), "arg count");
        assertEquals("description", a.get(0).name(), "arg name");
        assertEquals(Boolean.TRUE, a.get(0).required(), "arg required");
    }

    @Test
    void everyDeclaredArgumentIsRequiredAndDescribed() {
        for (SyncPromptSpecification s : Prompts.all()) {
            for (PromptArgument a : s.prompt().arguments()) {
                assertEquals(Boolean.TRUE, a.required(),
                        "arg " + a.name() + " of " + s.prompt().name() + " should be required");
                assertNotNull(a.description(),
                        "arg " + a.name() + " of " + s.prompt().name() + " should have a description");
                assertFalse(a.description().isBlank(),
                        "arg " + a.name() + " of " + s.prompt().name() + " description blank");
            }
        }
    }

    // ------------------------------------------------------------------ all(): handler rendering

    @Test
    void everyHandlerReturnsUserRoleTextResultForNullArgs() {
        for (SyncPromptSpecification s : Prompts.all()) {
            GetPromptRequest req = new GetPromptRequest(s.prompt().name(), null);
            GetPromptResult r = s.promptHandler().apply(null, req);
            assertNotNull(r, "result null for " + s.prompt().name());
            assertEquals(s.prompt().description(), r.description(),
                    "result description should echo the prompt description for " + s.prompt().name());
            assertEquals(1, r.messages().size(), "one message for " + s.prompt().name());
            assertEquals(Role.USER, r.messages().get(0).role(), "USER role for " + s.prompt().name());
            String text = textOf(r);
            assertFalse(text.isBlank(), "rendered text blank for " + s.prompt().name());
        }
    }

    @Test
    void everyHandlerRendersWithEmptyArgsMap() {
        for (SyncPromptSpecification s : Prompts.all()) {
            GetPromptResult r = render(s, new HashMap<>());
            assertFalse(textOf(r).isBlank(), "empty-args render blank for " + s.prompt().name());
        }
    }

    @Test
    void auditOntologyRenderContainsGuidedSteps() {
        String text = textOf(render(specNamed("audit_ontology"), Collections.emptyMap()));
        assertAll(
                () -> assertTrue(text.contains("get_ontology_context"), "mentions get_ontology_context"),
                () -> assertTrue(text.contains("validate_ontology"), "mentions validate_ontology"),
                () -> assertTrue(text.contains("run_reasoner"), "mentions run_reasoner"),
                () -> assertTrue(text.contains("preview_changes"), "mentions preview_changes"),
                () -> assertTrue(text.contains("DO NOT"), "warns not to modify without approval"));
    }

    @Test
    void findAndFixUnsatisfiableRenderMentionsExplanations() {
        String text = textOf(render(specNamed("find_and_fix_unsatisfiable"), Collections.emptyMap()));
        assertAll(
                () -> assertTrue(text.contains("get_unsatisfiable_classes"), "mentions unsatisfiable"),
                () -> assertTrue(text.contains("get_explanations"), "mentions explanations"),
                () -> assertTrue(text.contains("owl:Nothing"), "mentions owl:Nothing"));
    }

    @Test
    void explainClassInterpolatesClassValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("class", "http://ex.org/Foo");
        String text = textOf(render(specNamed("explain_class"), args));
        assertTrue(text.contains("\"http://ex.org/Foo\""),
                "the class value should be interpolated (quoted) into the template");
        assertTrue(text.contains("get_entity_context"), "mentions get_entity_context");
    }

    @Test
    void explainClassUsesFallbackWhenClassMissing() {
        String text = textOf(render(specNamed("explain_class"), Collections.emptyMap()));
        assertTrue(text.contains("\"the class\""),
                "missing class arg should fall back to the literal \"the class\"");
    }

    @Test
    void addSubclassSafelyInterpolatesChildAndParent() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("child", "Puppy");
        args.put("parent", "Dog");
        String text = textOf(render(specNamed("add_subclass_safely"), args));
        assertAll(
                () -> assertTrue(text.contains("\"Puppy\""), "interpolates child"),
                () -> assertTrue(text.contains("\"Dog\""), "interpolates parent"),
                () -> assertTrue(text.contains("subclass_of"), "mentions the subclass axiom type"),
                () -> assertTrue(text.contains("add_subclass_of"), "mentions the apply tool"));
    }

    @Test
    void addSubclassSafelyUsesFallbacksWhenArgsMissing() {
        String text = textOf(render(specNamed("add_subclass_safely"), Collections.emptyMap()));
        assertAll(
                () -> assertTrue(text.contains("\"the child class\""), "child fallback"),
                () -> assertTrue(text.contains("\"the parent class\""), "parent fallback"));
    }

    @Test
    void authorSparqlQueryInterpolatesQuestion() {
        Map<String, Object> args = new HashMap<>();
        args.put("question", "How many enzymes are there?");
        String text = textOf(render(specNamed("author_sparql_query"), args));
        assertAll(
                () -> assertTrue(text.contains("How many enzymes are there?"), "interpolates question"),
                () -> assertTrue(text.contains("sparql_schema"), "mentions sparql_schema"),
                () -> assertTrue(text.contains("sparql_validate"), "mentions sparql_validate"),
                () -> assertTrue(text.contains("sparql_query"), "mentions sparql_query"));
    }

    @Test
    void authorSparqlQueryUsesFallbackWhenQuestionMissing() {
        String text = textOf(render(specNamed("author_sparql_query"), Collections.emptyMap()));
        assertTrue(text.contains("the question"),
                "missing question arg should fall back to \"the question\"");
    }

    @Test
    void modelDomainInterpolatesDescription() {
        Map<String, Object> args = new HashMap<>();
        args.put("description", "a coffee shop menu");
        String text = textOf(render(specNamed("model_domain"), args));
        assertAll(
                () -> assertTrue(text.contains("a coffee shop menu"), "interpolates description"),
                () -> assertTrue(text.contains("get_ontology_context"), "mentions get_ontology_context"),
                () -> assertTrue(text.contains("preview_changes"), "mentions preview_changes"));
    }

    @Test
    void modelDomainUsesFallbackWhenDescriptionMissing() {
        String text = textOf(render(specNamed("model_domain"), Collections.emptyMap()));
        assertTrue(text.contains("the described domain"),
                "missing description should fall back to \"the described domain\"");
    }

    @Test
    void handlerTrimsSurroundingWhitespaceOfInterpolatedValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("class", "   Trimmed   ");
        String text = textOf(render(specNamed("explain_class"), args));
        assertTrue(text.contains("\"Trimmed\""), "value should be trimmed at the ends");
        assertFalse(text.contains("\"   Trimmed"), "leading whitespace should not survive");
    }

    @Test
    void handlerUsesFallbackWhenValueIsWhitespaceOnly() {
        Map<String, Object> args = new HashMap<>();
        args.put("class", "     ");
        String text = textOf(render(specNamed("explain_class"), args));
        assertTrue(text.contains("\"the class\""),
                "whitespace-only value collapses to empty and should use the fallback");
    }

    @Test
    void handlerAcceptsNonStringArgValueViaToString() {
        Map<String, Object> args = new HashMap<>();
        args.put("class", Integer.valueOf(42));
        String text = textOf(render(specNamed("explain_class"), args));
        assertTrue(text.contains("\"42\""), "non-String value should be stringified");
    }

    // ------------------------------------------------------------------ private str() edge cases

    @Test
    void strReturnsFallbackWhenArgsNull() {
        assertEquals("fb", invokeStr(null, "k", "fb"));
    }

    @Test
    void strReturnsFallbackWhenKeyAbsent() {
        assertEquals("fb", invokeStr(new HashMap<>(), "missing", "fb"));
    }

    @Test
    void strReturnsFallbackWhenValueNull() {
        Map<String, Object> args = new HashMap<>();
        args.put("k", null);
        assertEquals("fb", invokeStr(args, "k", "fb"));
    }

    @Test
    void strReturnsFallbackWhenValueBlankAfterTrim() {
        Map<String, Object> args = new HashMap<>();
        args.put("k", "   \t  ");
        assertEquals("fb", invokeStr(args, "k", "fb"));
    }

    @Test
    void strTrimsSurroundingWhitespaceButKeepsInterior() {
        Map<String, Object> args = new HashMap<>();
        args.put("k", "  a b  c  ");
        assertEquals("a b  c", invokeStr(args, "k", "fb"),
                "only leading/trailing whitespace is trimmed; interior preserved");
    }

    @Test
    void strStringifiesNonStringValues() {
        Map<String, Object> args = new HashMap<>();
        args.put("i", Integer.valueOf(7));
        args.put("b", Boolean.TRUE);
        assertAll(
                () -> assertEquals("7", invokeStr(args, "i", "fb")),
                () -> assertEquals("true", invokeStr(args, "b", "fb")));
    }

    @Test
    void strReturnsNullWhenFallbackNullAndValueMissing() {
        assertNull(invokeStr(new HashMap<>(), "k", null),
                "a null fallback is passed through when the value is absent");
    }

    @Test
    void strReturnsEmptyFallbackWhenValueMissing() {
        assertEquals("", invokeStr(new HashMap<>(), "k", ""),
                "an empty fallback is returned verbatim");
    }

    @Test
    void strReturnsPresentValueOverFallback() {
        Map<String, Object> args = new HashMap<>();
        args.put("k", "real");
        assertEquals("real", invokeStr(args, "k", "fb"));
    }

    // ------------------------------------------------------------------ private arg() edge cases

    @Test
    void argRoundTripsNameDescriptionAndRequiredTrue() {
        PromptArgument a = invokeArg("child", "the child", true);
        assertAll(
                () -> assertEquals("child", a.name()),
                () -> assertEquals("the child", a.description()),
                () -> assertEquals(Boolean.TRUE, a.required()));
    }

    @Test
    void argRoundTripsRequiredFalse() {
        PromptArgument a = invokeArg("opt", "optional", false);
        assertEquals(Boolean.FALSE, a.required());
    }

    @Test
    void argRejectsNullNameViaConstructorValidation() {
        // The MCP SDK's PromptArgument (an Identifier) requires a non-empty name, so arg() propagates.
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> invokeArg(null, "d", true));
        assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("name"),
                "message should mention the offending name");
    }

    @Test
    void argRejectsEmptyNameViaConstructorValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> invokeArg("", "d", true));
    }

    @Test
    void argPassesThroughNullDescription() {
        PromptArgument a = invokeArg("n", null, false);
        assertNull(a.description());
    }

    @Test
    void argPassesThroughEmptyDescription() {
        PromptArgument a = invokeArg("n", "", false);
        assertEquals("", a.description());
    }

    // ------------------------------------------------------------------ private prompt() via reflection

    @Test
    void promptBuildsSpecWithNameDescriptionAndArguments() throws Exception {
        Class<?> tmpl = Class.forName("io.github.hakjuoh.protege_mcp.tools.Prompts$Template");
        Method promptMethod = Prompts.class.getDeclaredMethod(
                "prompt", String.class, String.class, List.class, tmpl);
        promptMethod.setAccessible(true);

        Object template = java.lang.reflect.Proxy.newProxyInstance(
                tmpl.getClassLoader(),
                new Class<?>[] { tmpl },
                (proxy, method, methodArgs) -> {
                    if ("render".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> a = (Map<String, Object>) methodArgs[0];
                        return "rendered:" + (a == null ? "null" : a.get("x"));
                    }
                    if ("toString".equals(method.getName())) {
                        return "template";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == methodArgs[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        List<PromptArgument> argList = Collections.singletonList(
                new PromptArgument("x", "an x", Boolean.TRUE));
        SyncPromptSpecification spec = (SyncPromptSpecification) promptMethod.invoke(
                null, "custom", "a custom prompt", argList, template);

        Prompt p = spec.prompt();
        assertAll(
                () -> assertEquals("custom", p.name()),
                () -> assertEquals("a custom prompt", p.description()),
                () -> assertEquals(1, p.arguments().size()),
                () -> assertEquals("x", p.arguments().get(0).name()));

        Map<String, Object> reqArgs = new HashMap<>();
        reqArgs.put("x", "hi");
        GetPromptResult result = spec.promptHandler().apply(
                null, new GetPromptRequest("custom", reqArgs));
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
    void promptWithEmptyArgumentListProducesNoArguments() throws Exception {
        Class<?> tmpl = Class.forName("io.github.hakjuoh.protege_mcp.tools.Prompts$Template");
        Method promptMethod = Prompts.class.getDeclaredMethod(
                "prompt", String.class, String.class, List.class, tmpl);
        promptMethod.setAccessible(true);

        Object template = java.lang.reflect.Proxy.newProxyInstance(
                tmpl.getClassLoader(),
                new Class<?>[] { tmpl },
                (proxy, method, methodArgs) -> {
                    switch (method.getName()) {
                        case "render":
                            return "x";
                        case "toString":
                            return "t";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == methodArgs[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });

        SyncPromptSpecification spec = (SyncPromptSpecification) promptMethod.invoke(
                null, "empty", "no-arg prompt", Collections.emptyList(), template);
        assertTrue(spec.prompt().arguments().isEmpty(), "no arguments expected");
    }

    // ------------------------------------------------------------------ handler independence

    @Test
    void distinctPromptsHaveDistinctHandlerInstances() {
        List<SyncPromptSpecification> all = Prompts.all();
        BiFunction<?, ?, ?> h0 = all.get(0).promptHandler();
        BiFunction<?, ?, ?> h1 = all.get(1).promptHandler();
        org.junit.jupiter.api.Assertions.assertNotSame(h0, h1,
                "each prompt should carry its own handler");
    }

    @Test
    void sameHandlerRenderedTwiceIsStableForSameArgs() {
        SyncPromptSpecification spec = specNamed("model_domain");
        Map<String, Object> args = new HashMap<>();
        args.put("description", "widgets");
        String a = textOf(render(spec, args));
        String b = textOf(render(spec, args));
        assertEquals(a, b, "rendering is a pure function of its arguments");
    }
}
