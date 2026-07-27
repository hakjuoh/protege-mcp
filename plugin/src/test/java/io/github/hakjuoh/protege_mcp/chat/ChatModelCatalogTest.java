package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import javax.swing.SwingUtilities;

import io.github.hakjuoh.protege_mcp.testing.TestPreferences;


/** Headless coverage for model catalog persistence rules and provider effort capabilities. */
class ChatModelCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void storedModelsAreTrimmedDeduplicatedAndOrdered() {
        assertEquals(List.of("gpt-5.6-sol", "gpt-5.6-luna"),
                ChatModelCatalog.parseStoredModels(" gpt-5.6-sol\n\ngpt-5.6-luna\ngpt-5.6-sol "));
    }

    @Test
    void emptyStoredModelsRemainEmptyToSelectTheCliDefault() {
        assertTrue(ChatModelCatalog.parseStoredModels("").isEmpty());
        assertTrue(ChatModelCatalog.parseStoredModels("\n ").isEmpty());
        assertEquals("", ChatModelCatalog.serializeModels(List.of()));
    }

    @Test
    void defaultSentinelIsNotPersistedAsAModelId() {
        assertTrue(ChatModelCatalog.parseStoredModels("(default)\ngpt-5.6-luna").equals(
                List.of("gpt-5.6-luna")));
    }

    @Test
    void claudeMetadataKeepsOnlyModelEnvironmentKeys() {
        String json = "{\"model\":\"opus\",\"env\":{"
                + "\"ANTHROPIC_DEFAULT_SONNET_MODEL\":\"claude-sonnet-5\","
                + "\"ANTHROPIC_API_KEY\":\"secret\","
                + "\"ANTHROPIC_BASE_URL\":\"https://example.test\"}}";
        assertEquals(List.of("opus", "claude-sonnet-5"),
                ChatModelCatalog.parseClaudeModelMetadata(json));
    }

    @Test
    void codexMetadataExposesTheEffortsAdvertisedForThatModel() {
        String json = "{\"models\":[{\"slug\":\"gpt-test\",\"supported_reasoning_levels\":["
                + "{\"effort\":\"low\"},{\"effort\":\"max\"},{\"effort\":\"ultra\"}]}]}";
        assertEquals(List.of("", "low", "max", "ultra"),
                ChatModelCatalog.parseCodexReasoningEfforts(json, "gpt-test"));
    }

    @Test
    void catalogSaveAndLoadPreserveAnExplicitEmptyCatalog() {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of());
        assertTrue(ChatModelCatalog.load(preferences, "codex").isEmpty());
        assertEquals(List.of(""), ChatModelCatalog.pickerModels(preferences, "codex"));
    }

    @Test
    void failedDiscoveryShowsOnlyTheCliDefault() {
        Preferences preferences = TestPreferences.cleared();
        assertEquals(List.of(""), ChatModelCatalog.pickerModels(preferences, "claude", tempDir));
        assertEquals(List.of(""), ChatModelCatalog.pickerModels(preferences, "codex", tempDir));
    }

    @Test
    void discoveredModelsComeOnlyFromTheLocalMetadataRoot() throws Exception {
        Path claude = Files.createDirectories(tempDir.resolve(".claude"));
        Files.writeString(claude.resolve("settings.json"), "{\"env\":{"
                + "\"ANTHROPIC_DEFAULT_SONNET_MODEL\":\"claude-local\"}} ");
        Path codex = Files.createDirectories(tempDir.resolve(".codex"));
        Files.writeString(codex.resolve("config.toml"), "model = \"gpt-local\"\n");

        Preferences preferences = TestPreferences.cleared();
        assertEquals(List.of("", "claude-local"),
                ChatModelCatalog.pickerModels(preferences, "claude", tempDir));
        assertEquals(List.of("", "gpt-local"),
                ChatModelCatalog.pickerModels(preferences, "codex", tempDir));
    }

    @Test
    void savedModelsTakePrecedenceOverLocalMetadata() throws Exception {
        Path codex = Files.createDirectories(tempDir.resolve(".codex"));
        Files.writeString(codex.resolve("config.toml"), "model = \"gpt-local\"\n");
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("gpt-custom"));
        assertEquals(List.of("", "gpt-custom"),
                ChatModelCatalog.pickerModels(preferences, "codex", tempDir));
    }

    @Test
    void oversizedMetadataIsIgnored() throws Exception {
        Path metadata = tempDir.resolve("models.json");
        Files.writeString(metadata, "x".repeat(5 * 1024 * 1024 + 1));
        assertEquals("", ChatModelCatalog.readMetadata(metadata));
    }

    @Test
    void serializedModelsRoundTripWithoutChangingOrder() {
        List<String> models = List.of("claude-sonnet-5", "claude-fable-5");
        assertEquals(models, ChatModelCatalog.parseStoredModels(ChatModelCatalog.serializeModels(models)));
    }

    @Test
    void movingModelsPreservesOrderAndHandlesBoundaries() {
        List<String> models = List.of("first", "second", "third");
        assertEquals(List.of("second", "first", "third"),
                ChatModelCatalog.moveModel(models, 1, -1));
        assertEquals(List.of("first", "third", "second"),
                ChatModelCatalog.moveModel(models, 1, 1));
        assertEquals(models, ChatModelCatalog.moveModel(models, 0, -1));
        assertEquals(models, ChatModelCatalog.moveModel(models, 2, 1));
    }

    @Test
    void savedPickerOrderIsPreservedExactly() {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("third", "first", "second"));
        assertEquals(List.of("", "third", "first", "second"),
                ChatModelCatalog.pickerModels(preferences, "codex", tempDir));
    }

    @Test
    void reasoningEffortPickersStartWithTheCliDefault() {
        assertFalse(ChatModelCatalog.claudeReasoningEfforts().isEmpty());
        assertEquals("", ChatModelCatalog.claudeReasoningEfforts().get(0));
        assertEquals("", ChatModelCatalog.codexReasoningEfforts().get(0));
    }

    @Test
    void codexEffortsNarrowOnlyFromTheCacheTheCliActuallyReads() throws Exception {
        // The .bak beside the cache is the snapshot from before the last refresh, so it names models
        // the installed CLI has stopped offering and levels the current one no longer advertises.
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-current\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"high\"}]}]}");
        writeCodexMetadata("models_cache.json.bak", "{\"models\":[{\"slug\":\"syn-current\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"medium\"}]},"
                + "{\"slug\":\"syn-dropped\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(List.of("", "low", "high"),
                ChatModelCatalog.codexReasoningEfforts("syn-current", tempDir));
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("syn-dropped", tempDir));
    }

    @Test
    void onlyALevelTheCliCouldTakeIsOfferedAsOne() throws Exception {
        // The value is sent as -c model_reasoning_effort="…", so a level is a bare token. A cache entry
        // whose level is a sentence, a line of its own, or longer than any identifier names nothing the CLI
        // would accept, and offering it would put a send that cannot work in the picker as if the CLI's own
        // metadata had promised it. A token this build has never heard of is offered: a level Codex adds
        // tomorrow is one the user can run today, and hiding it is the worse error.
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-shapes\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"turbo-2.5_x\"},"
                + "{\"effort\":\"not a level but prose\"},{\"effort\":\"high\\nlow\"},"
                + "{\"effort\":\"" + "x".repeat(33) + "\"},{\"effort\":\"  medium  \"},"
                + "{\"effort\":\"héroïque\"},{\"effort\":\"\"},{\"effort\":\"   \"}]}]}");

        assertEquals(List.of("", "low", "turbo-2.5_x", "medium"),
                ChatModelCatalog.codexReasoningEfforts("syn-shapes", tempDir),
                "every token the CLI could parse, and nothing that is not one");

        // Bounded like the model catalog: a cache that lists a thousand levels is not a picker.
        StringBuilder many = new StringBuilder("{\"models\":[{\"slug\":\"syn-many\","
                + "\"supported_reasoning_levels\":[");
        for (int i = 0; i < 200; i++) {
            many.append(i == 0 ? "" : ",").append("{\"effort\":\"level").append(i).append("\"}");
        }
        writeCodexMetadata("models_cache.json", many.append("]}]}").toString());

        List<String> bounded = ChatModelCatalog.codexReasoningEfforts("syn-many", tempDir);
        assertEquals(24, bounded.size(), "the level list is capped: " + bounded);
        assertEquals("", bounded.get(0), "and the CLI's own default is still the first choice");
    }

    @Test
    void aStaleBackupCacheNeverStandsInForAMissingOrUnreadableOne() throws Exception {
        writeCodexMetadata("models_cache.json.bak", "{\"models\":[{\"slug\":\"syn-dropped\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("syn-dropped", tempDir));

        writeCodexMetadata("models_cache.json", "{\"models\":[]} junk");
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("syn-dropped", tempDir));
    }

    @Test
    void aModelCodexHidesIsNeitherOfferedNorUsedToNarrowTheEfforts() throws Exception {
        writeCodexMetadata("models_cache.json", "{\"models\":["
                + "{\"slug\":\"syn-listed\",\"visibility\":\"list\",\"supported_in_api\":true,"
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]},"
                + "{\"slug\":\"syn-hidden\",\"visibility\":\"hide\",\"supported_in_api\":true,"
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]},"
                + "{\"slug\":\"syn-offline\",\"visibility\":\"list\",\"supported_in_api\":false,"
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");
        Preferences preferences = TestPreferences.cleared();

        assertEquals(List.of("", "syn-listed"),
                ChatModelCatalog.pickerModels(preferences, "codex", tempDir));
        assertEquals(List.of("", "low"),
                ChatModelCatalog.codexReasoningEfforts("syn-listed", tempDir));
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("syn-hidden", tempDir));
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("syn-offline", tempDir));
    }

    @Test
    void aCacheFieldOnlySaysSomethingWhenItIsTheValueItShouldBe() throws Exception {
        // Reading a coerced field would answer by guessing: a numeric 0 read as "not served by the API"
        // hides a model the user can run today, with no error message that could ever explain the
        // absence, and a 1 read as "served" offers one the API refuses. A cache written to a schema this
        // build does not know has said nothing about the model, and an entry that says nothing is offered
        // exactly as one with no such field is - while a field that does say it stays decisive.
        writeCodexMetadata("models_cache.json", "{\"models\":["
                + "{\"slug\":\"syn-odd-visibility\",\"visibility\":1,\"supported_in_api\":true,"
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]},"
                + "{\"slug\":\"syn-odd-support\",\"visibility\":\"list\",\"supported_in_api\":0,"
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]},"
                + "{\"slug\":\"syn-null-fields\",\"visibility\":null,\"supported_in_api\":null,"
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]},"
                + "{\"slug\":\"syn-said-hidden\",\"visibility\":\"hide\",\"supported_in_api\":true},"
                + "{\"slug\":\"syn-said-offline\",\"visibility\":\"list\",\"supported_in_api\":false}]}");
        Preferences preferences = TestPreferences.cleared();

        assertEquals(List.of("", "syn-odd-visibility", "syn-odd-support", "syn-null-fields"),
                ChatModelCatalog.pickerModels(preferences, "codex", tempDir),
                "a field of the wrong kind hides nothing; one that says it still does");
        assertEquals(List.of("", "low"),
                ChatModelCatalog.codexReasoningEfforts("syn-odd-visibility", tempDir),
                "and the entry narrows the levels like any other offered model");
        assertEquals(List.of("", "low"),
                ChatModelCatalog.codexReasoningEfforts("syn-odd-support", tempDir));
        assertEquals(List.of("", "low"),
                ChatModelCatalog.codexReasoningEfforts("syn-null-fields", tempDir));
    }

    @Test
    void aBlankModelNarrowsFromTheModelCodexIsConfiguredToUse() throws Exception {
        writeCodexMetadata("config.toml", "model = \"syn-configured\"\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-configured\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"max\"}]}]}");

        assertEquals(List.of("", "low", "max"),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
        assertEquals(List.of("", "low", "max"),
                ChatModelCatalog.codexReasoningEfforts(null, tempDir));
    }

    @Test
    void aModelUnderAnInactiveProfileNeverNarrowsThePicker() throws Exception {
        // codex reads the top-level model; a [profiles.x] table configures a profile that is not in
        // effect. Narrowing against it would delete levels the model actually running does support -
        // and a level missing from the picker is a level the user cannot get back.
        writeCodexMetadata("config.toml", """
                model_provider = "openai"

                [profiles.work]
                model = "syn-profile-only"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-profile-only\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "no top-level model is configured, so nothing is known to narrow against");
    }

    @Test
    void aTopLevelModelIsStillReadWhenProfileTablesFollowIt() throws Exception {
        writeCodexMetadata("config.toml", """
                model = "syn-top-level"

                [profiles.work]
                model = "syn-profile-only"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":["
                + "{\"slug\":\"syn-top-level\",\"supported_reasoning_levels\":[{\"effort\":\"high\"}]},"
                + "{\"slug\":\"syn-profile-only\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(List.of("", "high"), ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "the first match in the file is not necessarily the top-level one");
    }

    @Test
    void aSelectedProfileStopsTheNarrowingRatherThanGuessing() throws Exception {
        // With profile = "work" the effective model comes from that table, which this does not
        // resolve. Offering every level is the safe answer: a wrong one fails loudly at the CLI, a
        // missing one just cannot be picked.
        writeCodexMetadata("config.toml", """
                model = "syn-top-level"
                profile = "work"

                [profiles.work]
                model = "syn-profile-only"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":["
                + "{\"slug\":\"syn-top-level\",\"supported_reasoning_levels\":[{\"effort\":\"high\"}]},"
                + "{\"slug\":\"syn-profile-only\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aCommentedOutModelConfiguresNothing() throws Exception {
        writeCodexMetadata("config.toml", "# model = \"syn-configured\"\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-configured\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aModelInsideAMultiLineStringValueConfiguresNothing() throws Exception {
        // TOML """…""" and '''…''' values hold arbitrary text, and instructions to a model routinely
        // contain lines that read exactly like a key. Treating one as configuration would narrow the
        // picker against a model that is not going to run.
        writeCodexMetadata("config.toml", """
                instructions = \"\"\"
                model = "syn-in-a-string"
                \"\"\"
                notify = '''
                model = 'syn-in-a-literal'
                '''
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":["
                + "{\"slug\":\"syn-in-a-string\",\"supported_reasoning_levels\":[{\"effort\":\"low\"}]},"
                + "{\"slug\":\"syn-in-a-literal\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "the body of a multi-line string is text, not top-level keys");
    }

    @Test
    void anArrayValueSpanningLinesDoesNotEndTheTopLevelTable() throws Exception {
        // A continuation line of an inline array can begin with '[' - only a whole-line [table] header
        // ends the top-level keys. Stopping at the array line would lose the model that follows it.
        writeCodexMetadata("config.toml", """
                shell_environment_policy = [
                  ["PATH", "HOME"],
                  ["TERM"],
                ]
                model = "syn-after-an-array"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-after-an-array\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"xhigh\"}]}]}");

        assertEquals(List.of("", "xhigh"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void anArrayClosedOnTheLineThatClosedAStringElementStillEndsTheArray() throws Exception {
        // Legal TOML, and the one place a bracket hides: the ] that ends the array shares the line that
        // ends a multi-line string element. Not counting that bracket leaves every later key looking like
        // another element, so the top-level model right below it narrows nothing - the picker offers every
        // level for a model whose supported levels the metadata states exactly.
        writeCodexMetadata("config.toml", """
                notes = [\"""
                first\"""]
                model = "syn-after-a-closed-array"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-after-a-closed-array\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(List.of("", "low"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aModelIdOrEffortThatIsNotAStringComesFromNoMetadataAtAll() throws Exception {
        // Jackson renders a number as text on request, so an entry whose slug is 123 would put 123 in the
        // model picker and an effort of 456 would be offered as a reasoning level - a value no CLI accepts,
        // presented as if the CLI's own metadata had named it. Malformed metadata contributes nothing.
        writeCodexMetadata("models_cache.json", "{\"models\":["
                + "{\"slug\":123,\"supported_reasoning_levels\":[{\"effort\":\"low\"}]},"
                + "{\"slug\":\"syn-real\",\"supported_reasoning_levels\":["
                + "{\"effort\":456},{\"effort\":\"high\"}]}]}");
        Files.writeString(Files.createDirectories(tempDir.resolve(".claude")).resolve("settings.json"),
                "{\"env\":{\"ANTHROPIC_MODEL\":7}}");

        assertEquals(List.of("", "syn-real"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "a slug that is not a string names no model");
        assertEquals(List.of("", "high"),
                ChatModelCatalog.codexReasoningEfforts("syn-real", tempDir),
                "a level that is not a string is not a level");
        assertEquals(List.of(""),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "claude", tempDir),
                "an environment model that is not a string names no model either");
    }

    @Test
    void aTableHeaderWithAQuotedPunctuatedKeyStillEndsTheTopLevelTable() throws Exception {
        // Codex writes its own per-directory trust sections as [projects."/Users/me/repo"], and profile
        // names are free text. A header the scan does not recognise is worse than one it invents: it
        // would read the following table's model as the top-level one and narrow the picker against a
        // model that is not going to run.
        writeCodexMetadata("config.toml", """
                [projects."/Users/me/repo"]
                trust_level = "trusted"

                [profiles."work/main"]
                model = "syn-profile-only"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-profile-only\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aTableHeaderWhoseQuotedNameContainsABracketStillEndsTheTopLevelTable() throws Exception {
        // A ']' inside the quoted name is data, not the end of the header. Scanning for the first ']'
        // leaves a trailing '"]' that no longer looks like a header, so the table's own model would be
        // read as the top-level one and the picker narrowed against a model that is not going to run.
        writeCodexMetadata("config.toml", """
                [profiles."work]main"]
                model = "syn-profile-only"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-profile-only\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aMultiLineDelimiterInsideACommentDoesNotSwallowTheKeysAfterIt() throws Exception {
        // A comment is text. Reading ''' there as the start of a multi-line value discards every line
        // up to the next one - including the model - and the picker then offers levels the configured
        // model may not support.
        writeCodexMetadata("config.toml", """
                notify = "ok" # ''' and " and \"\"\"
                model = "syn-after-a-comment"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-after-a-comment\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"medium\"}]}]}");

        assertEquals(List.of("", "medium"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aByteOrderMarkedConfigAndCacheAreStillRead() throws Exception {
        // An editor that saves UTF-8 with a BOM leaves it on the first line. Glued to the first key it
        // is no longer "model", and in front of a '{' Jackson refuses the whole document - so both the
        // configured model and every level it supports would vanish with nothing to explain it.
        writeCodexMetadata("config.toml", "\uFEFFmodel = \"syn-configured\"\n");
        writeCodexMetadata("models_cache.json", "\uFEFF{\"models\":[{\"slug\":\"syn-configured\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"max\"}]}]}");

        assertEquals(List.of("", "low", "max"),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aByteOrderMarkBeforeALaterHeaderStillEndsTheTopLevelTable() throws Exception {
        // A mark anywhere but the first line is stranger still, and it is the dangerous one: it detaches
        // the '[' from the start of the line, so the profile table would never be seen and its model
        // would be read as the one a bare `codex exec` runs. Narrowing against that is narrowing against
        // a model that is not going to run.
        writeCodexMetadata("config.toml", "notify = \"ok\"\n\uFEFF[profiles.work]\n"
                + "model = \"syn-profile\"\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-profile\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "a profile's model is not the top-level one, whatever a stray mark does to the header");
    }

    @Test
    void aByteOrderMarkBeforeAProfileKeyDoesNotHideThatAProfileIsSelected() throws Exception {
        // Same mark, on the key that hands the decision to a table this does not resolve. Missing it
        // would narrow against the top-level model while Codex runs the profile's.
        writeCodexMetadata("config.toml", "model = \"syn-top-level\"\n\uFEFFprofile = \"work\"\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-top-level\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "a selected profile means the model is unknown, so every level stays on offer");
    }

    @Test
    void aMultiLineArrayValueDoesNotEndTheTopLevelTable() throws Exception {
        // An array written over several lines has element lines of its own, and one of them read alone
        // is exactly a table header. Stopping there would lose the top-level model that follows it, and
        // the picker would offer levels the configured model does not support.
        writeCodexMetadata("config.toml", """
                shell_environment_policy = [
                  ["PATH", "HOME"],
                  ["TERM"]
                ]
                model = "syn-after-array"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-after-array\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"medium\"}]}]}");

        assertEquals(List.of("", "medium"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aTableHeaderAfterAMultiLineArrayStillEndsTheTopLevelTable() throws Exception {
        // The counterpart: once the array closes, the next header must still be one.
        writeCodexMetadata("config.toml", """
                shell_environment_policy = [
                  ["PATH"]
                ]
                [profiles.work]
                model = "syn-profile"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-profile\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "the model under a profile table is still not the top-level one");
    }

    @Test
    void aBracketInsideAStringValueDoesNotSwallowTheKeysAfterIt() throws Exception {
        // Brackets are counted outside strings only: a value holding one would otherwise leave the scan
        // thinking it is inside an array forever, and the configured model after it would be lost.
        writeCodexMetadata("config.toml", """
                notify = "["
                model = "syn-after-bracket-text"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-after-bracket-text\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"high\"}]}]}");

        assertEquals(List.of("", "high"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void anEscapedTripleQuoteDoesNotEndAMultiLineValue() throws Exception {
        // TOML writes three quotes inside a basic multi-line string by escaping one of them, so an
        // escaped delimiter is body, not the end of the value. Reading it as the end puts the rest of the
        // body back in scope, and a model key written inside a string is then narrowed against.
        writeCodexMetadata("config.toml", """
                notify = \"""prefix
                [not actually a table]
                \\\"""
                model = "syn-in-string"
                \"""
                [profiles.work]
                model = "syn-profile-only"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-in-string\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]},"
                + "{\"slug\":\"syn-profile-only\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"high\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "neither model key is top-level, so no level may be taken off the picker");
    }

    @Test
    void anEscapedDelimiterOnTheOpeningLineDoesNotCloseItEither() throws Exception {
        // The same escape on the line that opens the value: the scan must not decide the string ended
        // there and read the lines after it, which are still its body, as top-level keys.
        writeCodexMetadata("config.toml", """
                notify = \"""starts and \\\""" does not end
                model = "syn-still-inside"
                \"""
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-still-inside\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"medium\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "a key inside a string body is not the configured model");
    }

    @Test
    void aLiteralMultiLineValueTakesNoEscapesSoEveryDelimiterEndsIt() throws Exception {
        // The counterpart: a literal ''' string has no escapes, so a backslash before the delimiter is
        // text and the value ends there. Treating it as an escape would swallow the rest of the file and
        // lose the configured model, leaving levels on offer that this model does not support.
        writeCodexMetadata("config.toml", """
                notify = '''body ends with a backslash \\'''
                model = "syn-after-literal"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-after-literal\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"xhigh\"}]}]}");

        assertEquals(List.of("", "xhigh"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aQuotedProfileKeyStillHandsTheDecisionToAProfile() throws Exception {
        // TOML lets a bare key be written quoted, and "profile" is the same key as profile. Missing that
        // spelling narrows the picker against the top-level model the profile overrides.
        writeCodexMetadata("config.toml", """
                "profile" = "work"
                model = "syn-overridden"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-overridden\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "which model that profile runs is unknown, so every level stays on offer");
    }

    @Test
    void aMultiLineProfileValueStillHandsTheDecisionToAProfile() throws Exception {
        // What the value looks like is not the point: profile = """work""" selects a profile exactly like
        // profile = "work" does, so the model key below it is not the one Codex will run and narrowing
        // against it would delete levels the model actually running does support.
        writeCodexMetadata("config.toml", """
                profile = \"""work\"""
                model = "syn-overridden"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-overridden\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "which model that profile runs is unknown, so every level stays on offer");
    }

    @Test
    void aProfileWrittenOverTwoLinesIsStillASelectedProfile() throws Exception {
        // TOML trims the newline right after the opening delimiter, so this assigns "work" as surely as
        // the one-line spelling. Skipping every line that opens a string would miss the line naming the
        // key and read the model after it as the one in effect.
        writeCodexMetadata("config.toml", """
                profile = \"""
                work\"""
                model = "syn-overridden"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-overridden\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "the key was assigned on that line, whatever line its value ends on");
    }

    @Test
    void aProfileKeyLeftAtTheEndOfItsLineStillHandsTheDecisionAway() throws Exception {
        // TOML tolerates the newline between a key and its '=', and Codex's own parser takes this file.
        // Only the key being assigned decides whether these keys are in effect, so a spelling that puts
        // the '=' out of reach of the key's line must still give up rather than narrow the picker against
        // a model the profile overrides.
        writeCodexMetadata("config.toml", """
                profile
                = "work"
                model = "syn-overridden"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-overridden\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "a profile is selected here, so which model runs is unknown");
    }

    @Test
    void aDottedProfileKeyNamesAModelTheUserRuns() throws Exception {
        // profiles.work.model = "…" is the same assignment as [profiles.work] followed by model = "…",
        // and Codex runs that id under --profile work. Reading only the table spelling would leave the
        // picker without a model the user has configured, for no reason a reader could see.
        writeCodexMetadata("config.toml", """
                profiles.work.model = "syn-dotted"
                'profiles'."odd key".model = "syn-dotted-quoted"
                """);

        assertEquals(List.of("", "syn-dotted", "syn-dotted-quoted"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "both dotted spellings name models the user runs");
    }

    @Test
    void aDottedModelKeyNeverNarrowsTheEffortPicker() throws Exception {
        // The catalog offers a profile's model; the effort picker must still not be narrowed against one.
        // Which table the earlier segments name is not resolved here, so treating the last segment as the
        // key in effect would narrow the levels against a model a bare `codex exec` does not run.
        writeCodexMetadata("config.toml", "profiles.work.model = \"syn-dotted\"\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-dotted\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "a dotted profile model is no more in effect than a tabled one");
    }

    @Test
    void aDottedKeyThatIsNotAModelContributesNothing() throws Exception {
        // Only the last segment being `model` makes this an id. A key that merely ends near it, or a
        // dotted key whose value is not a string, would otherwise put settings text in the picker.
        writeCodexMetadata("config.toml", """
                profiles.work.model_reasoning_effort = "high"
                profiles.work.notify = "syn-not-a-model"
                profiles.work.model = 12345
                tools.web_search = true
                """);

        assertEquals(List.of(""),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "nothing here assigns a model id");
    }

    @Test
    void aLongConfigOfBlankLinesIsReadOnceThroughItsLines() throws Exception {
        // The key patterns are anchored per line under MULTILINE. Spelling their leading blanks as \\s let
        // a run of blank lines match across line boundaries, so every line start retried against the whole
        // rest of the run: a file of them took tens of seconds on the event thread, which is the thread the
        // Preferences panel and the model picker are built on. A linear scan of this file is milliseconds.
        writeCodexMetadata("config.toml", "\n".repeat(50_000) + "model = \"syn-eventually\"\n");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertEquals(List.of("", "syn-eventually"),
                        ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                        "the assignment after the blank run is still read"));
    }

    @Test
    void anInlineProfileTableNamesAModelTheUserRunsToo() throws Exception {
        // TOML's third spelling of the same assignment. `codex --profile work` runs this id exactly as it
        // runs the dotted and tabled forms, so a picker that offered two of the three would be missing a
        // model the user has configured for a reason no reader could see.
        writeCodexMetadata("config.toml", """
                profiles.work = { model = "syn-inline", model_reasoning_effort = "high" }
                profiles.other = { "model" = "syn-inline-quoted", approval_policy = "never" }
                profiles.none = { model_reasoning_effort = "low", notify = "syn-not-a-model" }
                note = "an inline table in a string assigns nothing: { model = \\"syn-prose\\" }"
                """);

        assertEquals(List.of("", "syn-inline", "syn-inline-quoted"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "every inline model assignment, and nothing that only looks like one");
    }

    @Test
    void aModelKeyingItsOwnTableIsOfferedLikeTheInlineForm() throws Exception {
        // [models."gpt-…"] is how TOML spells `"gpt-…" = { … }` once the settings under it run to several
        // lines. Reading only the inline form would drop an id the file names as plainly as the other.
        // A table keyed by something that is not a model must stay out of the picker: a project path is
        // not an id any CLI can run, however much of one it contains.
        writeCodexMetadata("config.toml", """
                [models."gpt-5-syn"]
                reasoning_effort = "high"

                [ models . "gpt-5-syn-spaced" ]
                reasoning_effort = "low"

                [projects."/Users/me/gpt-5-syn-notes"]
                trust_level = "trusted"

                [model_providers."gpt-5-syn-provider"]
                name = "local"
                """);

        assertEquals(List.of("", "gpt-5-syn", "gpt-5-syn-spaced"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "only a table under `models` is keyed by a model id");
    }

    @Test
    void onlyAKeyUnderModelsIsReadAsAModelId() throws Exception {
        // The inline form of the same thing: entries of a [models] table are keyed by model ids, and a
        // quoted key anywhere else is keyed by whatever that table is keyed by. Codex writes tables that
        // are keyed by ids that look exactly like models and are not configuration for one -
        // model_availability_nux counts how many times each was mentioned to the user - so reading a key
        // by its shape alone puts ids in the picker the CLI has no configuration for at all, and, when the
        // count outlives the model, ids it will refuse.
        writeCodexMetadata("config.toml", """
                [models]
                "gpt-5-syn-keyed" = { reasoning_effort = "high" }

                [tui.model_availability_nux]
                "gpt-5-syn-nudged" = 4

                [projects."/Users/me/repo"]
                "gpt-5-syn-project" = "trusted"
                """);

        assertEquals(List.of("", "gpt-5-syn-keyed"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "the entry of the models table, and nothing keyed by one of those other things");
    }

    @Test
    void aKeyedModelIsReadAgainWhenTheModelsTableComesBack() throws Exception {
        // A TOML file may open the same table more than once, and a subtable ends it: what decides whether
        // a key is a model id is the table it is under, not whether some other table appeared earlier in
        // the file. Tracking that as one latch, set once and never cleared, would offer every quoted key
        // after the first [models] table; reading it as "only until the next header" would drop the ones
        // the file names after coming back to it.
        writeCodexMetadata("config.toml", """
                [models]
                "gpt-5-syn-first" = { reasoning_effort = "high" }

                [models.overrides]
                "gpt-5-syn-subtable" = 1

                [tui]
                notifications = false

                [models]
                "gpt-5-syn-again" = { reasoning_effort = "low" }
                """);

        assertEquals(List.of("", "gpt-5-syn-first", "gpt-5-syn-again"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "both entries of the models table itself, and nothing from a table under it");
    }

    @Test
    void everyTomlSpellingOfAnEntryUnderModelsNamesItsModel() throws Exception {
        // One mapping, four spellings, all of them configuring the model the CLI runs: a key under a
        // [models] table (bare, as TOML allows for an id with no dot in it, or quoted and dotted into), a
        // header keyed by the id, and a dotted key naming the same entry at the top level. A picker that
        // read some of them would drop a model this file configures as plainly as the others, and nothing
        // in the panel would explain why the id the user just wrote down is not offered.
        writeCodexMetadata("config.toml", """
                models."gpt-5-syn-dotted" = { reasoning_effort = "high" }

                [models]
                gpt-5-syn-bare = { reasoning_effort = "low" }
                "gpt-5-syn-quoted".reasoning_effort = "high"

                [models.gpt-5-syn-tabled]
                reasoning_effort = "low"
                """);

        assertEquals(List.of("", "gpt-5-syn-dotted", "gpt-5-syn-bare", "gpt-5-syn-quoted",
                        "gpt-5-syn-tabled"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "every spelling of a models entry names a model to offer");
    }

    @Test
    void anInlineModelsTableKeysItsModelsLikeTheTableItSpells() throws Exception {
        // models = { … } on one line is the whole [models] table, so its entries are keyed by ids just the
        // same. Only the entries are: what an inline table nested inside one of them holds is that model's
        // settings, and a key there is a setting name however much of an id it looks like.
        writeCodexMetadata("config.toml", """
                models = { "gpt-5-syn-inline" = { reasoning_effort = "high", \
                "gpt-5-syn-nested" = 1 }, gpt-5-syn-inline-bare = { verbosity = "low" } }
                """);

        assertEquals(List.of("", "gpt-5-syn-inline", "gpt-5-syn-inline-bare"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "the entries of the inline models table, and nothing keyed inside one of them");
    }

    @Test
    void whatOnlyLooksLikeAnEntryOfTheModelsTableSeedsNothing() throws Exception {
        // The same shapes read against something that is not that table. [[models]] appends to an array
        // whose keys are an element's fields; a key or header that dots past the id names a table under it
        // rather than one, because a bare key stops at the dot - the id ended earlier than whoever wrote
        // `gpt-5.5-codex` unquoted meant, and the part before it is a model they never configured; and a
        // dotted key under another table dots into that table instead. Each would put an id in the picker
        // that fails at the CLI on every send.
        writeCodexMetadata("config.toml", """
                [[models]]
                "gpt-5-syn-array" = { reasoning_effort = "high" }

                [models]
                gpt-5-syn.5-codex = { reasoning_effort = "high" }

                [models.gpt-5-syn.5-codex]
                reasoning_effort = "high"

                [tui]
                models."gpt-5-syn-elsewhere" = { reasoning_effort = "high" }
                """);

        assertEquals(List.of(""),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "nothing here keys an entry of the models table");
    }

    @Test
    void anUnterminatedValueSeedsNothingWithoutEmptyingThePicker() throws Exception {
        // There is no TOML parser here on purpose: the file is read line by line, so a value nobody closed
        // costs the picker that line and nothing else. Failing the whole file closed would let one stray
        // bracket in a config the user is halfway through editing empty a picker that had their models in
        // it - and the ids around it are as real as they ever were.
        writeCodexMetadata("config.toml", """
                model = "syn-before"
                broken = [
                """);

        assertEquals(List.of("", "syn-before"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "the assignment that is whole is still read");
    }

    @Test
    void theEffortFallbackOffersEveryValueTheCodexCliAccepts() {
        // The fallback is what a model with no local metadata gets. Omitting a value the CLI accepts
        // makes a working level unreachable from the picker, which no error message would explain.
        assertEquals(List.of("", "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
                ChatModelCatalog.codexReasoningEfforts());
    }

    @Test
    void anExplicitlyPickedModelIsNarrowedWhateverTheConfigSays() throws Exception {
        // The config only decides what "(default)" means; a model chosen in the picker is the model.
        writeCodexMetadata("config.toml", "profile = \"work\"\nmodel = \"syn-top-level\"\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"syn-picked\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"medium\"}]}]}");
        assertEquals(List.of("", "medium"),
                ChatModelCatalog.codexReasoningEfforts("syn-picked", tempDir));
    }

    @Test
    void withoutLocalCodexMetadataEveryEffortValueStaysOffered() {
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("syn-unknown", tempDir));
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aModelChosenBeforeTheCatalogExistedStaysSelectable() {
        // Upgrade path: earlier releases offered a hard-coded alias list, so the remembered id can be
        // one no local metadata names. Dropping it would silently move the next turn to another model.
        Preferences preferences = TestPreferences.cleared();
        preferences.putString(ChatModels.modelPrefKey("claude"), "opus");
        assertEquals(List.of("", "opus"),
                ChatModelCatalog.pickerModels(preferences, "claude", tempDir));
    }

    @Test
    void theRememberedModelLeadsTheDiscoveredOnesWithoutDuplicating() throws Exception {
        Path claude = Files.createDirectories(tempDir.resolve(".claude"));
        Files.writeString(claude.resolve("settings.json"),
                "{\"model\":\"claude-local\",\"env\":{\"ANTHROPIC_MODEL\":\"opus\"}}");
        Preferences preferences = TestPreferences.cleared();
        preferences.putString(ChatModels.modelPrefKey("claude"), "opus");
        assertEquals(List.of("", "opus", "claude-local"),
                ChatModelCatalog.pickerModels(preferences, "claude", tempDir));
    }

    @Test
    void anExplicitEmptyCatalogIsNotRepopulatedFromTheRememberedModel() {
        Preferences preferences = TestPreferences.cleared();
        preferences.putString(ChatModels.modelPrefKey("codex"), "gpt-5.5");
        ChatModelCatalog.save(preferences, "codex", List.of());
        assertEquals(List.of(""), ChatModelCatalog.pickerModels(preferences, "codex", tempDir));
    }

    @Test
    void codexConfigLiteralStringsAreAlsoRead() throws Exception {
        // TOML accepts 'single quotes' for a literal string; only reading "double quotes" would drop
        // a perfectly valid model line.
        Path codex = Files.createDirectories(tempDir.resolve(".codex"));
        Files.writeString(codex.resolve("config.toml"), "model = 'gpt-literal'\n");
        Preferences preferences = TestPreferences.cleared();
        assertEquals(List.of("", "gpt-literal"),
                ChatModelCatalog.pickerModels(preferences, "codex", tempDir));
    }

    @Test
    void aQuotedModelKeyIsTheSameModelKey() throws Exception {
        // TOML lets a bare key be written quoted, and "model" is the key model. Not reading that spelling
        // keeps the model the CLI is configured to run out of the picker and leaves the effort levels
        // narrowed against nothing, in a config codex itself runs perfectly well.
        writeCodexMetadata("config.toml", """
                "model" = "gpt-quoted"
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"gpt-quoted\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"high\"}]}]}");

        assertEquals(List.of("", "gpt-quoted"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir));
        assertEquals(List.of("", "high"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aLiteralQuotedModelKeyIsTheSameModelKeyToo() throws Exception {
        writeCodexMetadata("config.toml", """
                'model' = 'gpt-literal-key'
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"gpt-literal-key\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(List.of("", "gpt-literal-key"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir));
        assertEquals(List.of("", "low"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aMultiLineModelValueOnOneLineIsStillTheModelThatRuns() throws Exception {
        // model = """gpt-multiline""" is the same assignment as model = "gpt-multiline"; the id is the
        // body between the delimiters, not the delimiters.
        writeCodexMetadata("config.toml", """
                model = \"""gpt-multiline\"""
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"gpt-multiline\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"xhigh\"}]}]}");

        assertEquals(List.of("", "gpt-multiline"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir));
        assertEquals(List.of("", "xhigh"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aLiteralMultiLineModelValueOnOneLineIsAlsoRead() throws Exception {
        writeCodexMetadata("config.toml", """
                model = '''gpt-literal-block'''
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"gpt-literal-block\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"medium\"}]}]}");

        assertEquals(List.of("", "gpt-literal-block"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir));
        assertEquals(List.of("", "medium"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aBasicStringEscapeNamesTheModelCodexResolvesItTo() throws Exception {
        // A basic string resolves its escapes, so this line runs gpt-5. Offering the id as it is spelled
        // would put a model no API accepts in the picker and narrow the levels against that, while the
        // model actually running is described in the cache under its resolved name.
        writeCodexMetadata("config.toml", "model = \"gpt-\\u0035\"\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"gpt-5\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"minimal\"},{\"effort\":\"high\"}]}]}");

        assertEquals(List.of("", "gpt-5"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir));
        assertEquals(List.of("", "minimal", "high"),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aLiteralStringResolvesNoEscapesBecauseTomlDefinesNone() throws Exception {
        // The same spelling in a literal string is the id, backslash and all - which is not a model any
        // cache describes, so every effort level stays on offer.
        writeCodexMetadata("config.toml", "model = 'gpt-\\u0035'\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"gpt-5\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"minimal\"}]}]}");

        assertEquals(List.of("", "gpt-\\u0035", "gpt-5"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir));
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void anEscapedQuoteInAModelValueIsPartOfTheIdNotTheEndOfIt() throws Exception {
        // What ends a basic string is the first quote it does not escape. Stopping at the escaped one reads
        // gpt-\ as the id: a model nothing runs, offered in the picker, and narrowed against instead of the
        // one this line does configure - which the cache describes under the id TOML defines here.
        writeCodexMetadata("config.toml", "model = \"gpt-\\\"5\"\n");
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"gpt-\\\"5\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"high\"}]}]}");

        assertEquals(List.of("", "gpt-\"5"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "the escaped quote is a character of the id, not the end of the value");
        assertEquals(List.of("", "high"), ChatModelCatalog.codexReasoningEfforts("", tempDir));
    }

    @Test
    void aModelWrittenInsideAMultiLineStringIsNotOfferedAsOne() throws Exception {
        // The other half of the rule that keeps such a line from narrowing the effort levels: a line inside
        // a """ … """ value assigns nothing, so it names no model to offer either. Instructions to a model
        // routinely contain lines that read exactly like a key, and a sentence out of somebody's prose is
        // not a model id - picking it would fail every turn until the user noticed where it came from.
        writeCodexMetadata("config.toml", """
                instructions = \"""
                model = "syn-in-a-string"
                \"""
                notify = '''
                model = 'syn-in-a-literal'
                '''
                model = "syn-real"
                """);

        assertEquals(List.of("", "syn-real"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "only the assignment outside the string bodies names a model");
    }

    @Test
    void aStringBodyReopenedOnTheLineThatClosedTheLastOneIsStillNotConfiguration() throws Exception {
        // Legal TOML: an array may run over several lines, and two multi-line strings in one can meet on a
        // single line - the first ending and the second beginning after it. Reading that line as spent would
        // put the second body back in scope and offer a line of prose as a model id.
        writeCodexMetadata("config.toml", """
                notes = [\"""
                first\""", \"""
                model = "syn-in-the-second-body"
                \"""]
                model = "syn-real"
                """);

        assertEquals(List.of("", "syn-real"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "the body reopened on the closing line is prose like any other");
    }

    @Test
    void aModelValueWhoseBodyCarriesOnToTheNextLineNarrowsNothing() throws Exception {
        // TOML does assign "gpt-open" here - the newline after the delimiter is trimmed - and this reads
        // no model from it: the value is not on the line that names the key, and nothing is guessed about
        // the continuation. Every level stays on offer, the same answer a selected profile gets, which is
        // the safe direction; reading the delimiters themselves as the value would instead offer a quote
        // character as a model and narrow the levels against it.
        writeCodexMetadata("config.toml", """
                model = \"""
                gpt-open\"""
                """);
        writeCodexMetadata("models_cache.json", "{\"models\":[{\"slug\":\"gpt-open\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]}");

        assertEquals(List.of("", "gpt-open"),
                ChatModelCatalog.pickerModels(TestPreferences.cleared(), "codex", tempDir),
                "the cache names the model; the spanning config line contributes nothing");
        assertEquals(ChatModelCatalog.codexReasoningEfforts(),
                ChatModelCatalog.codexReasoningEfforts("", tempDir),
                "a value the assignment's own line does not carry is not narrowed against");
    }

    @Test
    void metadataWithTrailingGarbageIsTreatedAsUnreadable() {
        assertTrue(ChatModelCatalog.parseClaudeModelMetadata("{\"model\":\"safe\"} junk").isEmpty());
        assertEquals(List.of(""),
                ChatModelCatalog.parseCodexReasoningEfforts(
                        "{\"models\":[{\"slug\":\"g\",\"supported_reasoning_levels\":[{\"effort\":\"low\"}]}]} junk",
                        "g"));
    }

    @Test
    void controlCharactersAndOverlongIdsNeverEnterTheCatalog() {
        // A NUL would fail process startup, U+2028/U+2029 are not ISO control characters yet the
        // stored catalog is split on \R, and an absurdly long id only exists to overflow the single
        // preference string the catalog lives in.
        assertFalse(ChatModelCatalog.isAcceptableModelId("gpt\0evil"));
        assertFalse(ChatModelCatalog.isAcceptableModelId("gpt\u2028evil"));
        assertFalse(ChatModelCatalog.isAcceptableModelId("gpt\u2029evil"));
        assertFalse(ChatModelCatalog.isAcceptableModelId("x".repeat(
                ChatModelCatalog.maxModelIdChars() + 1)));
        assertTrue(ChatModelCatalog.isAcceptableModelId("x".repeat(
                ChatModelCatalog.maxModelIdChars())));
        assertTrue(ChatModelCatalog.parseStoredModels("gpt\0evil").isEmpty());
        // A separator that somehow reached the store is a row break, never part of an id.
        assertEquals(List.of("gpt", "evil"), ChatModelCatalog.parseStoredModels("gpt\u2028evil"));
        assertTrue(ChatModelCatalog.parseClaudeModelMetadata(
                "{\"model\":\"gpt\\u0000evil\"}").isEmpty());
    }

    @Test
    void theCatalogIsCappedSoItAlwaysFitsOneStoredPreferenceValue() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < ChatModelCatalog.maxModels() + 25; i++) {
            many.add("model-" + i);
        }
        List<String> stored = ChatModelCatalog.parseStoredModels(
                ChatModelCatalog.serializeModels(many));
        assertEquals(ChatModelCatalog.maxModels(), stored.size());
        assertEquals("model-0", stored.get(0));
        // Protégé's Java-backed preferences reject a value longer than 8 KiB.
        assertTrue(ChatModelCatalog.serializeModels(many).length() < 8192);
    }

    @Test
    void catalogListenersRunOnTheEventDispatchThreadUntilTheyAreRemoved() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        Runnable listener = () -> threads.add(
                SwingUtilities.isEventDispatchThread() ? "edt" : Thread.currentThread().getName());
        ChatModelCatalog.addChangeListener(listener);
        try {
            ChatModelCatalog.fireChanged();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(List.of("edt"), threads);
        } finally {
            ChatModelCatalog.removeChangeListener(listener);
        }
        ChatModelCatalog.fireChanged();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(List.of("edt"), threads);
    }

    @Test
    void aListenerRemovedBeforeAQueuedNotificationArrivesIsNotCalled() throws Exception {
        // A view can be disposed between the preferences OK and the queued delivery. Reading the
        // listener list at delivery time is what keeps that view from being touched after teardown.
        List<String> calls = new CopyOnWriteArrayList<>();
        Runnable listener = () -> calls.add("called");
        CountDownLatch holdEventQueue = new CountDownLatch(1);
        ChatModelCatalog.addChangeListener(listener);
        SwingUtilities.invokeLater(() -> {
            try {
                holdEventQueue.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            ChatModelCatalog.fireChanged();
            ChatModelCatalog.removeChangeListener(listener);
        } finally {
            holdEventQueue.countDown();
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(calls.isEmpty());
    }

    @Test
    void oneFailingListenerDoesNotStopTheRest() throws Exception {
        // Two Assistant views open, the first one broken. Delivering on the event thread ran the
        // listeners inline, so its exception unwound the loop and the second view kept showing the
        // model list the user had just deleted from - and the throw landed in the OK handler.
        List<String> calls = new CopyOnWriteArrayList<>();
        Runnable broken = () -> {
            calls.add("broken");
            throw new IllegalStateException("this view is already gone");
        };
        Runnable healthy = () -> calls.add("healthy");
        ChatModelCatalog.addChangeListener(broken);
        ChatModelCatalog.addChangeListener(healthy);
        try {
            SwingUtilities.invokeAndWait(ChatModelCatalog::fireChanged);
            assertEquals(List.of("broken", "healthy"), calls,
                    "the view after the broken one must still be refreshed");
        } finally {
            ChatModelCatalog.removeChangeListener(broken);
            ChatModelCatalog.removeChangeListener(healthy);
        }
    }

    private void writeCodexMetadata(String filename, String content) throws IOException {
        Files.writeString(Files.createDirectories(tempDir.resolve(".codex")).resolve(filename),
                content);
    }
}
