package io.github.hakjuoh.protege_mcp.prompts;

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
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Method-level tests for {@link Prompts}, the provider that registers every built-in prompt.
 *
 * <p>{@code Prompts} is a pure template/data builder with no OWLAPI, Protégé runtime, Swing, or I/O
 * dependencies, so every test here is deterministic and headless. The provider is exercised the way
 * {@link PromptCatalog#buildAll} drives it — registered into a fresh {@link PromptRegistry}; the
 * private helpers ({@code arg}, {@code str}) are exercised via reflection to hit their enumerated
 * edge cases directly, and also transitively through the rendered prompt handlers.
 */
class PromptsTest {

    /** Build the provider's specs the way the catalog does: register into a fresh registry. */
    private static List<SyncPromptSpecification> all() {
        PromptRegistry registry = new PromptRegistry();
        Prompts.register(registry);
        return registry.build();
    }

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
        for (SyncPromptSpecification s : all()) {
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
            "model_domain",
            "author_competency_question",
            "author_swrl_rule",
            "refactor_entity_safely",
            "bootstrap_ontology",
            "release_readiness_check");

    // ------------------------------------------------------------------ register(): structure

    @Test
    void registerContributesExactlyElevenPrompts() {
        assertEquals(11, all().size(), "expected 11 built-in prompts");
    }

    @Test
    void registerContributesExpectedNamesInOrder() {
        List<String> actual = new ArrayList<>();
        for (SyncPromptSpecification s : all()) {
            actual.add(s.prompt().name());
        }
        assertEquals(EXPECTED_NAMES, actual, "prompt names/order");
    }

    @Test
    void everyPromptHasNonEmptyName() {
        for (SyncPromptSpecification s : all()) {
            String n = s.prompt().name();
            assertNotNull(n, "name null");
            assertFalse(n.isBlank(), "name blank: " + n);
        }
    }

    @Test
    void everyPromptHasNonEmptyDescription() {
        for (SyncPromptSpecification s : all()) {
            String d = s.prompt().description();
            assertNotNull(d, "description null for " + s.prompt().name());
            assertFalse(d.isBlank(), "description blank for " + s.prompt().name());
        }
    }

    @Test
    void everyPromptHasAHandler() {
        for (SyncPromptSpecification s : all()) {
            assertNotNull(s.promptHandler(), "handler null for " + s.prompt().name());
        }
    }

    @Test
    void registerIsIdempotentAcrossFreshRegistries() {
        List<SyncPromptSpecification> a = all();
        List<SyncPromptSpecification> b = all();
        org.junit.jupiter.api.Assertions.assertNotSame(a, b, "each registration should build a fresh list");
        List<String> namesA = new ArrayList<>();
        List<String> namesB = new ArrayList<>();
        a.forEach(s -> namesA.add(s.prompt().name()));
        b.forEach(s -> namesB.add(s.prompt().name()));
        assertEquals(namesA, namesB, "same names across registrations");
    }

    // ------------------------------------------------------------------ register(): argument shapes

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
    void everyDeclaredArgumentIsFlaggedAndDescribed() {
        for (SyncPromptSpecification s : all()) {
            for (PromptArgument a : s.prompt().arguments()) {
                assertNotNull(a.required(),
                        "arg " + a.name() + " of " + s.prompt().name() + " should declare required");
                assertNotNull(a.description(),
                        "arg " + a.name() + " of " + s.prompt().name() + " should have a description");
                assertFalse(a.description().isBlank(),
                        "arg " + a.name() + " of " + s.prompt().name() + " description blank");
            }
        }
    }

    @Test
    void authorCompetencyQuestionHasRequiredQuestionAndOptionalExpected() {
        List<PromptArgument> a = specNamed("author_competency_question").prompt().arguments();
        assertEquals(2, a.size(), "arg count");
        assertEquals("question", a.get(0).name(), "first arg name");
        assertEquals(Boolean.TRUE, a.get(0).required(), "question required");
        assertEquals("expected", a.get(1).name(), "second arg name");
        assertEquals(Boolean.FALSE, a.get(1).required(), "expected optional");
    }

    @Test
    void authorSwrlRuleHasSingleRequiredRuleArgument() {
        List<PromptArgument> a = specNamed("author_swrl_rule").prompt().arguments();
        assertEquals(1, a.size(), "arg count");
        assertEquals("rule", a.get(0).name(), "arg name");
        assertEquals(Boolean.TRUE, a.get(0).required(), "arg required");
    }

    @Test
    void refactorEntitySafelyHasTwoRequiredArguments() {
        List<PromptArgument> a = specNamed("refactor_entity_safely").prompt().arguments();
        assertEquals(2, a.size(), "arg count");
        assertEquals("entity", a.get(0).name(), "first arg name");
        assertEquals("goal", a.get(1).name(), "second arg name");
        assertAll(
                () -> assertEquals(Boolean.TRUE, a.get(0).required(), "entity required"),
                () -> assertEquals(Boolean.TRUE, a.get(1).required(), "goal required"));
    }

    @Test
    void bootstrapOntologyHasRequiredIriAndOptionalPath() {
        List<PromptArgument> a = specNamed("bootstrap_ontology").prompt().arguments();
        assertEquals(2, a.size(), "arg count");
        assertEquals("ontology_iri", a.get(0).name(), "first arg name");
        assertEquals(Boolean.TRUE, a.get(0).required(), "ontology_iri required");
        assertEquals("path", a.get(1).name(), "second arg name");
        assertEquals(Boolean.FALSE, a.get(1).required(), "path optional");
    }

    @Test
    void releaseReadinessCheckHasTwoOptionalArguments() {
        List<PromptArgument> a = specNamed("release_readiness_check").prompt().arguments();
        assertEquals(2, a.size(), "arg count");
        assertEquals("profile", a.get(0).name(), "first arg name");
        assertEquals("namespace", a.get(1).name(), "second arg name");
        assertAll(
                () -> assertEquals(Boolean.FALSE, a.get(0).required(), "profile optional"),
                () -> assertEquals(Boolean.FALSE, a.get(1).required(), "namespace optional"));
    }

    // ------------------------------------------------------------------ register(): handler rendering

    @Test
    void everyHandlerReturnsUserRoleTextResultForNullArgs() {
        for (SyncPromptSpecification s : all()) {
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
        for (SyncPromptSpecification s : all()) {
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
                () -> assertTrue(text.contains("validate_governance"), "mentions validate_governance"),
                () -> assertTrue(text.contains("run_reasoner"), "mentions run_reasoner"),
                () -> assertTrue(text.contains("explain_inconsistency"), "branches to explain_inconsistency"),
                () -> assertTrue(text.contains("preview_changes"), "mentions preview_changes"),
                () -> assertTrue(text.contains("apply_changes"), "names the apply path"),
                () -> assertTrue(text.contains("verify=\"rollback\""), "uses verify=rollback"),
                () -> assertTrue(text.contains("DO NOT"), "warns not to modify without approval"));
    }

    @Test
    void findAndFixUnsatisfiableRenderMentionsExplanations() {
        String text = textOf(render(specNamed("find_and_fix_unsatisfiable"), Collections.emptyMap()));
        assertAll(
                () -> assertTrue(text.contains("get_unsatisfiable_classes"), "mentions unsatisfiable"),
                () -> assertTrue(text.contains("get_explanations"), "mentions explanations"),
                () -> assertTrue(text.contains("owl:Nothing"), "mentions owl:Nothing"),
                () -> assertTrue(text.contains("explain_inconsistency"), "covers the inconsistent branch"),
                () -> assertTrue(text.contains("apply_changes"), "applies via apply_changes"),
                () -> assertTrue(text.contains("verify=\"report\""), "uses verify=report"));
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
                () -> assertTrue(text.contains("apply_changes"), "applies via apply_changes"),
                () -> assertTrue(text.contains("verify=\"rollback\""), "uses verify=rollback"),
                () -> assertTrue(text.contains("set_reasoner"), "recovers by selecting a reasoner"),
                () -> assertTrue(text.contains("UNVERIFIED"), "labels the reasonerless fallback honestly"),
                () -> assertTrue(text.contains("add_subclass_of"), "keeps the no-reasoner fallback"));
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
                () -> assertTrue(text.contains("create_terms"), "routes classes through create_terms"),
                () -> assertTrue(text.contains("create_properties"), "routes properties through create_properties"),
                () -> assertTrue(text.contains("preview_changes"), "mentions preview_changes"),
                () -> assertTrue(text.contains("run_qc_suite"), "gates with run_qc_suite"));
    }

    @Test
    void modelDomainUsesFallbackWhenDescriptionMissing() {
        String text = textOf(render(specNamed("model_domain"), Collections.emptyMap()));
        assertTrue(text.contains("the described domain"),
                "missing description should fall back to \"the described domain\"");
    }

    @Test
    void authorSparqlQueryRenderMentionsDryRun() {
        String text = textOf(render(specNamed("author_sparql_query"), Collections.emptyMap()));
        assertTrue(text.contains("dry_run=true"),
                "sparql_validate's dry_run sample execution should be suggested");
    }

    @Test
    void authorCompetencyQuestionRenderContainsCqWorkflow() {
        Map<String, Object> args = new HashMap<>();
        args.put("question", "Every batch has a lot number");
        String text = textOf(render(specNamed("author_competency_question"), args));
        assertAll(
                () -> assertTrue(text.contains("Every batch has a lot number"), "interpolates question"),
                () -> assertTrue(text.contains("list_competency_questions"), "checks the existing store"),
                () -> assertTrue(text.contains("sparql_schema"), "discovers vocabulary"),
                () -> assertTrue(text.contains("sparql_validate"), "validates the draft"),
                () -> assertTrue(text.contains("add_competency_question"), "stores the CQ"),
                () -> assertTrue(text.contains("include_inferred=false"),
                        "warns that include_inferred defaults to true"),
                () -> assertTrue(text.contains("run_competency_questions"), "runs the new CQ"));
    }

    @Test
    void authorCompetencyQuestionRendersGivenExpectedCondition() {
        Map<String, Object> args = new HashMap<>();
        args.put("question", "q");
        args.put("expected", "count >= 3");
        String text = textOf(render(specNamed("author_competency_question"), args));
        assertTrue(text.contains("count >= 3"), "interpolates the given expected condition");
    }

    @Test
    void authorCompetencyQuestionExplainsConditionsWhenExpectedMissing() {
        Map<String, Object> args = new HashMap<>();
        args.put("question", "q");
        String text = textOf(render(specNamed("author_competency_question"), args));
        assertTrue(text.contains("nonEmpty | empty | 'count OP N'"),
                "missing expected arg should enumerate the pass-condition choices");
    }

    @Test
    void authorSwrlRuleRenderContainsCompatibilityChecks() {
        Map<String, Object> args = new HashMap<>();
        args.put("rule", "adjacent rooms share a wall");
        String text = textOf(render(specNamed("author_swrl_rule"), args));
        assertAll(
                () -> assertTrue(text.contains("adjacent rooms share a wall"), "interpolates rule"),
                () -> assertTrue(text.contains("list_rules"), "checks existing rules"),
                () -> assertTrue(text.contains("add_rule"), "adds via add_rule"),
                () -> assertTrue(text.contains("list_reasoners"), "checks reasoner compatibility"),
                () -> assertTrue(text.contains("ELK"), "warns ELK ignores rules"),
                () -> assertTrue(text.contains("swrlb:"), "warns about builtin atoms"),
                () -> assertTrue(text.contains("set_reasoner"), "offers a reasoner switch"),
                () -> assertTrue(text.contains("include_inferred=true"), "verifies an inference fires"));
    }

    @Test
    void refactorEntitySafelyRenderCoversTheFourOperations() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("entity", "ObsoleteThing");
        args.put("goal", "deprecate it");
        String text = textOf(render(specNamed("refactor_entity_safely"), args));
        assertAll(
                () -> assertTrue(text.contains("\"ObsoleteThing\""), "interpolates entity"),
                () -> assertTrue(text.contains("deprecate it"), "interpolates goal"),
                () -> assertTrue(text.contains("rename_entity"), "covers rename"),
                () -> assertTrue(text.contains("deprecate_entity"), "covers deprecate"),
                () -> assertTrue(text.contains("delete_entity"), "covers delete"),
                () -> assertTrue(text.contains("move_class"), "covers move"),
                () -> assertTrue(text.contains("preview=true"), "uses the dry-run previews"),
                () -> assertTrue(text.contains("new_iri_already_in_signature"), "flags the rename merge"),
                () -> assertTrue(text.contains("peek=true"), "mentions the undo peek"));
    }

    @Test
    void bootstrapOntologyRenderInterpolatesIriAndPath() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ontology_iri", "http://ex.org/onto");
        args.put("path", "/tmp/onto.rdf");
        String text = textOf(render(specNamed("bootstrap_ontology"), args));
        assertAll(
                () -> assertTrue(text.contains("http://ex.org/onto"), "interpolates ontology_iri"),
                () -> assertTrue(text.contains("path=\"/tmp/onto.rdf\""), "interpolates path"),
                () -> assertTrue(text.contains("create_ontology"), "creates the module"),
                () -> assertTrue(text.contains("set_prefix"), "sets prefixes"),
                () -> assertTrue(text.contains("add_ontology_annotation"), "adds metadata"),
                () -> assertTrue(text.contains("add_import"), "adds imports"),
                () -> assertTrue(text.contains("'resolved' flag"), "checks import resolution"),
                () -> assertTrue(text.contains("write_catalog"), "writes the catalog"));
    }

    @Test
    void bootstrapOntologySuggestsPathWhenPathMissing() {
        Map<String, Object> args = new HashMap<>();
        args.put("ontology_iri", "http://ex.org/onto");
        String text = textOf(render(specNamed("bootstrap_ontology"), args));
        assertTrue(text.contains("pass 'path' too"),
                "missing path should still steer toward binding a file");
    }

    @Test
    void releaseReadinessCheckRenderContainsTheGateSequence() {
        String text = textOf(render(specNamed("release_readiness_check"), Collections.emptyMap()));
        assertAll(
                () -> assertTrue(text.contains("list_ontologies"), "checks dirty ontologies"),
                () -> assertTrue(text.contains("run_reasoner"), "classifies first"),
                () -> assertTrue(text.contains("run_qc_suite"), "runs the QC gate"),
                () -> assertTrue(text.contains("owl_profile=\"DL\""), "defaults the profile to DL"),
                () -> assertTrue(text.contains("validate_governance"), "checks governance policy"),
                () -> assertTrue(text.contains("diff_ontologies"), "diffs against the saved document"),
                () -> assertTrue(text.contains("save_ontology"), "saves only after approval"),
                () -> assertTrue(text.contains("write_catalog"), "writes the catalog"),
                () -> assertTrue(text.contains("DO NOT save"), "gates saving on approval"));
    }

    @Test
    void releaseReadinessCheckInterpolatesProfileAndNamespace() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("profile", "EL");
        args.put("namespace", "http://ex.org/terms/");
        String text = textOf(render(specNamed("release_readiness_check"), args));
        assertAll(
                () -> assertTrue(text.contains("owl_profile=\"EL\""), "interpolates profile"),
                () -> assertTrue(text.contains("required_namespaces=[\"http://ex.org/terms/\"]"),
                        "interpolates namespace"));
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

    // ------------------------------------------------------------------ handler independence

    @Test
    void distinctPromptsHaveDistinctHandlerInstances() {
        List<SyncPromptSpecification> specs = all();
        BiFunction<?, ?, ?> h0 = specs.get(0).promptHandler();
        BiFunction<?, ?, ?> h1 = specs.get(1).promptHandler();
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
