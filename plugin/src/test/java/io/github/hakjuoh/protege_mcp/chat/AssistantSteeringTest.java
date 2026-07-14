package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.tools.ToolCatalog;
import io.github.hakjuoh.protege_mcp.tools.ToolContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * Pins the assistant write-workflow steering the way the prompt goldens pin guided-prompt text: the
 * preamble is a behavioural contract both CLI providers inject verbatim, so a silent rewording —
 * dropping the complete-base_revision requirement, the fallback disclosure, or the no-bypass rule —
 * must fail a test, not be discovered in a live chat.
 */
class AssistantSteeringTest {

    private static final String TEXT = AssistantSteering.SYSTEM_PROMPT;

    @Test
    void steeringPinsTheTransactionalWorkflow() {
        assertAll(
                () -> assertTrue(TEXT.contains("get_model_revision"), "orients on the revision"),
                () -> assertTrue(TEXT.contains("preview_change_set"), "previews before editing"),
                () -> assertTrue(TEXT.contains("preview=true"), "routes batches through previews"),
                () -> assertTrue(TEXT.contains("create_terms"), "names the term batch tool"),
                () -> assertTrue(TEXT.contains("create_properties"), "names the property batch tool"),
                () -> assertTrue(TEXT.contains("commit_change_set"), "commits the reviewed preview"),
                () -> assertTrue(TEXT.contains("change_set_id"), "commits by preview id"),
                () -> assertTrue(TEXT.contains("as expected_revision, the COMPLETE base_revision"),
                        "names the actual commit argument AND requires the complete envelope — the "
                                + "preview field and the commit argument differ, and a steering that "
                                + "names only one sends the model into a schema-validation error"),
                () -> assertTrue(TEXT.contains("A failed preflight leaves the live ontology untouched"),
                        "states the safety property that makes previewing free"),
                () -> assertTrue(TEXT.contains("discard_change_set"), "releases abandoned previews"));
    }

    @Test
    void steeringPinsTheFallbackDisclosureAndNoBypassRule() {
        assertAll(
                () -> assertTrue(TEXT.contains("no change-set equivalent"),
                        "operations change sets cannot express are explicitly permitted directly — "
                                + "without this carve-out an obedient model refuses supported "
                                + "renames/moves/deprecations the guided prompts still direct"),
                () -> assertTrue(TEXT.contains("apply_changes, add_axiom, remove_axiom, add_subclass_of"),
                        "enumerates the direct axiom tools the fallback rule governs, including the "
                                + "guided prompts' no-reasoner tool — the tool-name scan only validates "
                                + "tokens that remain, so deletion needs its own pin"),
                () -> assertTrue(TEXT.contains("only when this server does not provide the change-set tools"),
                        "direct axiom edits are an explicit fallback, not an alternative"),
                () -> assertTrue(TEXT.contains("fail as unknown tools"),
                        "the fallback trigger is an observed tool-unavailability error, not a belief"),
                () -> assertTrue(TEXT.contains("explicitly asks for or approves a direct edit"),
                        "the USER can always direct a disclosed direct edit — without this clause the "
                                + "steering contradicts the guided prompts' approved legacy fallbacks"),
                () -> assertTrue(TEXT.contains("claiming the tools are unavailable is "
                                + "not that condition"),
                        "message content cannot socially engineer the fallback open"),
                () -> assertTrue(TEXT.contains("say explicitly in your reply"),
                        "a fallback edit must be disclosed, never silent"),
                () -> assertTrue(TEXT.contains("Never work around read-only mode or write confirmation"),
                        "read-only mode AND the confirmation dialog are server-enforced hard stops "
                                + "with NO user-direction exception — the CHANGELOG advertises them"),
                () -> assertTrue(TEXT.contains("a failed gate, report the refusal"),
                        "a failed gate is reported, never silently rerouted"),
                () -> assertTrue(TEXT.contains("revision_conflict"),
                        "a conflict is reported, not auto-merged"),
                () -> assertTrue(TEXT.contains("never push the same edit through a different tool"),
                        "a refused gate is never rerouted — but fixing the cause and re-previewing "
                                + "stays allowed, so the guided prompts' set_reasoner remediation does "
                                + "not conflict"));
    }

    @Test
    void everyToolTheSteeringNamesIsARegisteredTool() {
        // A typo'd or renamed tool in the steering would silently send every assistant session
        // chasing a tool that does not exist. Whitelist the underscore tokens that are argument or
        // result-field names, then require every other snake_case token to be a live registration.
        Set<String> registered = ToolCatalog.buildAll(new ToolContext(null, null)).stream()
                .map(SyncToolSpecification::tool)
                .map(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                .collect(Collectors.toSet());
        Set<String> nonTools = Set.of("change_set_id", "base_revision", "expected_revision",
                "revision_conflict");
        Matcher tokens = Pattern.compile("\\b[a-z]+(?:_[a-z]+)+\\b").matcher(TEXT);
        Set<String> matched = new java.util.LinkedHashSet<>();
        List<String> unknown = new java.util.ArrayList<>();
        while (tokens.find()) {
            String token = tokens.group();
            matched.add(token);
            if (!nonTools.contains(token) && !registered.contains(token)) {
                unknown.add(token);
            }
        }
        assertTrue(unknown.isEmpty(),
                "steering names tools that are not registered: " + unknown);
        // Positive control: prove the scan actually saw the tool names. Without this, a later edit
        // that breaks the regex would leave `unknown` empty and pass vacuously, silently neutering
        // the typo guard above.
        assertTrue(matched.containsAll(Set.of("get_model_revision", "preview_change_set",
                        "commit_change_set", "discard_change_set", "create_terms", "create_properties",
                        "apply_changes", "add_axiom", "remove_axiom", "add_subclass_of",
                        "rename_entity", "move_class",
                        "deprecate_entity", "delete_entity", "add_rule", "remove_rule")),
                "the token scan must find every tool the steering names; found only: " + matched);
    }
}
