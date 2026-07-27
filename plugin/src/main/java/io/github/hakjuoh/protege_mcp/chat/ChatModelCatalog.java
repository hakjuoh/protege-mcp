package io.github.hakjuoh.protege_mcp.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import org.protege.editor.core.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.config.McpConfig;

/**
 * Owns the model ids shown by the Ontology Assistant. When a user has not saved a catalog yet, the
 * catalog is bootstrapped from the model already selected plus the locally installed CLI's own metadata.
 * Metadata that cannot be used contributes nothing, leaving just that selected model — and the picker
 * shows the CLI-default entry alone only when nothing was selected either. Once saved,
 * including as an empty list, the user's catalog is the source of truth. The empty list is
 * intentional: it means the CLI must choose its configured default.
 */
public final class ChatModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ChatModelCatalog.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** The spellings of the {@code model} key TOML accepts: a bare key, or the same key quoted. */
    private static final List<String> CODEX_MODEL_KEYS = List.of("model", "\"model\"", "'model'");
    /** The same spellings of the {@code models} key, whose entries are keyed by model ids. */
    private static final List<String> CODEX_MODELS_KEYS = List.of("models", "\"models\"", "'models'");
    /** The shape a key has to have to be read as a model id: what Codex's own ids look like. */
    private static final String CODEX_MODEL_ID_PREFIX = "gpt-";
    /**
     * The same model id keying a table of its own, {@code [models."gpt-…"]}, which is how TOML spells the
     * key form above when the settings under it run to several lines. Only a header whose first segment
     * is {@code models} counts: every other table Codex keys by a quoted name keys it by something that is
     * not a model — {@code [projects."…"]} by a path, {@code [model_providers."…"]} by a provider — and a
     * path that happens to contain {@code gpt-} would otherwise be offered as an id no CLI can run. The id
     * may be written bare, as TOML allows for any key without a dot in it, and many ids have none; the
     * header has to end there either way, so {@code [models.gpt-5.5-codex]} — which names a table under an
     * id rather than one — seeds nothing. Blanks are matched as spaces and
     * tabs, never {@code \s}: TOML puts a header on one line, so nothing here should cross
     * a line boundary — and a {@code \s} anchored at {@code ^} under {@link Pattern#MULTILINE} retries
     * every line start in a run of blank lines against the whole rest of the run, which turns a long file
     * of them into a quadratic scan on the event thread.
     */
    private static final Pattern CODEX_MODEL_TABLE = Pattern.compile(
            "^[ \\t]*\\[[ \\t]*models[ \\t]*\\.[ \\t]*"
                    + "(?:\\\"(gpt-[^\\\"]+)\\\"|(gpt-[A-Za-z0-9_-]+))[ \\t]*\\][ \\t]*$",
            Pattern.MULTILINE);
    /** The escapes a TOML basic string defines: the named ones plus its two code-point forms. */
    private static final Pattern BASIC_STRING_ESCAPE =
            Pattern.compile("\\\\(u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8}|.)");
    /** A UTF-8 byte-order mark, which an editor may leave on any of these metadata files. */
    private static final String BYTE_ORDER_MARK = "\uFEFF";
    /**
     * A top-level {@code profile = …} makes some other table, not these keys, the effective config. What
     * the value looks like does not matter — the key being assigned at all is what hands the decision
     * away, so no spelling of the value ({@code "work"}, {@code 'work'}, {@code """work"""}) can be
     * missed. TOML also lets a bare key be written quoted, and {@code "profile" = …} is the same key.
     * Anything narrower here narrows the effort picker against a {@code model} the profile overrides.
     * Blanks are spaces and tabs for the same reason as {@link #CODEX_MODEL_TABLE}; a key left at the end of
     * its line counts as well, so the one spelling those blanks used to reach across — a {@code profile}
     * whose {@code =} is on the next line — still hands the decision away rather than being narrowed
     * against.
     */
    private static final Pattern CODEX_CONFIG_PROFILE = Pattern.compile(
            "^[ \\t]*(?:profile|\\\"profile\\\"|'profile')[ \\t]*(?:=|$)", Pattern.MULTILINE);
    private static final long MAX_METADATA_BYTES = 5L * 1024 * 1024;
    /**
     * Catalog bounds. Protégé's Java-backed preferences reject a value over 8 KiB, and a catalog is
     * one preference string, so cap both the id length and the entry count well inside that limit
     * rather than letting a hostile or runaway metadata file make the whole panel unsaveable.
     */
    private static final int MAX_MODEL_ID_CHARS = 100;
    /** U+2028 / U+2029: not ISO control characters, but Java's {@code \R} splits on them. */
    private static final int UNICODE_LINE_SEPARATOR = 0x2028;
    private static final int UNICODE_PARAGRAPH_SEPARATOR = 0x2029;
    private static final int MAX_MODELS = 50;
    /**
     * Effort bounds. A {@code model_reasoning_effort} value is a bare config token the CLI parses as one
     * word, so the levels a cache names are short identifiers; the picker is a combo box the user chooses
     * from, not a place to render whatever a metadata file happens to hold. A cache entry that lists a
     * thousand levels, or one whose "level" is a paragraph of prose, describes nothing the CLI would
     * accept - and offering it would put a value in the picker that fails at the API on every send.
     */
    private static final int MAX_EFFORT_CHARS = 32;
    private static final int MAX_EFFORTS = 24;
    /**
     * Views that want to know a saved catalog changed. Copy-on-write because the Preferences panel
     * fires from the event thread while a view may be disposing on that same thread.
     */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private ChatModelCatalog() {
    }

    /** Returns the configured model ids, with the local CLI metadata used only before first save. */
    public static List<String> load(Preferences preferences, String providerId) {
        return load(preferences, providerId, home());
    }

    /** Loads a catalog from the supplied CLI metadata root; package-private for deterministic tests. */
    static List<String> load(Preferences preferences, String providerId, Path metadataHome) {
        String stored = preferences.getString(modelPrefKey(providerId), null);
        return stored == null
                ? bootstrapModels(preferences, providerId, metadataHome)
                : parseStoredModels(stored);
    }

    /**
     * The catalog offered before the user has saved one: the model they already had selected, then
     * whatever the CLI's own metadata names. Carrying the remembered selection over matters for an
     * upgrade — earlier releases offered a hard-coded alias list, and without this the id that is
     * still in preferences would silently drop out of the picker and the next turn would quietly run
     * on a different model.
     */
    private static List<String> bootstrapModels(Preferences preferences, String providerId,
            Path metadataHome) {
        List<String> models = new ArrayList<>();
        models.add(preferences.getString(ChatModels.modelPrefKey(providerId), ""));
        models.addAll(discoverModels(providerId, metadataHome));
        return normalize(models);
    }

    /** Persists a normalized, ordered, duplicate-free model catalog. */
    public static void save(Preferences preferences, String providerId, List<String> models) {
        preferences.putString(modelPrefKey(providerId), serializeModels(models));
    }

    /**
     * Registers a listener notified after a catalog edit is saved. Notifications always arrive on the
     * event dispatch thread, so a listener may touch Swing state directly.
     */
    public static void addChangeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    /** Unregisters a listener; a view must call this as it is disposed. */
    public static void removeChangeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Announces that a saved catalog changed. Each listener is re-checked when its notification runs:
     * a view disposed between the save and the queued notification must not be called. One listener
     * that throws is contained: the remaining views still refresh, and the exception never escapes
     * into the Preferences dialog's OK handling, which has already saved the catalog by this point.
     */
    public static void fireChanged() {
        for (Runnable listener : LISTENERS) {
            Runnable notify = () -> {
                if (!LISTENERS.contains(listener)) {
                    return;
                }
                try {
                    listener.run();
                } catch (RuntimeException failed) {
                    log.warn("protege-mcp: a chat view failed to pick up the edited model catalog",
                            failed);
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                notify.run();
            } else {
                SwingUtilities.invokeLater(notify);
            }
        }
    }

    /** The preference key for a provider's editable model catalog. */
    public static String modelPrefKey(String providerId) {
        return "codex".equals(providerId)
                ? McpConfig.KEY_CHAT_MODELS_CODEX
                : McpConfig.KEY_CHAT_MODELS_CLAUDE;
    }

    /** Pure parser used by the preference store and headless tests. */
    static List<String> parseStoredModels(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return normalize(List.of(stored.split("\\R", -1)));
    }

    /** Pure serializer; model ids cannot contain a line break because the CLI receives one argv value. */
    static String serializeModels(List<String> models) {
        return String.join("\n", normalize(models));
    }

    /** Returns a catalog with one item moved one position, or the same order at a boundary. */
    public static List<String> moveModel(List<String> models, int index, int direction) {
        List<String> moved = new ArrayList<>(normalize(models));
        int target = index + direction;
        if (index < 0 || index >= moved.size() || (direction != -1 && direction != 1)
                || target < 0 || target >= moved.size()) {
            return List.copyOf(moved);
        }
        String value = moved.get(index);
        moved.set(index, moved.get(target));
        moved.set(target, value);
        return List.copyOf(moved);
    }

    /** Returns the model picker values, including the blank CLI-default entry first. */
    public static List<String> pickerModels(Preferences preferences, String providerId) {
        return pickerModels(preferences, providerId, home());
    }

    /** Builds picker values from the supplied CLI metadata root. */
    static List<String> pickerModels(Preferences preferences, String providerId, Path metadataHome) {
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(load(preferences, providerId, metadataHome));
        return List.copyOf(values);
    }

    /** Claude's effort values accepted by current Claude Code releases. */
    public static List<String> claudeReasoningEfforts() {
        return List.of("", "low", "medium", "high", "xhigh", "max");
    }

    /**
     * Codex effort values, in the CLI's own ascending order, for a model the local metadata does not
     * describe. This is the set the {@code codex} binary itself will accept for
     * {@code model_reasoning_effort}; whether the model behind it supports one is the API's answer, and
     * a refusal is reported as such. Offering fewer than the CLI accepts would hide a level that works.
     */
    public static List<String> codexReasoningEfforts() {
        return List.of("", "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    }

    private static List<String> discoverModels(String providerId, Path metadataHome) {
        return "codex".equals(providerId)
                ? discoverCodexModels(metadataHome)
                : discoverClaudeModels(metadataHome);
    }

    private static List<String> discoverClaudeModels(Path metadataHome) {
        Set<String> models = new LinkedHashSet<>();
        Path claude = metadataHome.resolve(".claude");
        addJsonField(models, claude.resolve("settings.json"), "model");
        addJsonField(models, claude.resolve("settings.local.json"), "model");
        return List.copyOf(models);
    }

    private static List<String> discoverCodexModels(Path metadataHome) {
        Set<String> models = new LinkedHashSet<>();
        Path codex = metadataHome.resolve(".codex");
        Path config = codex.resolve("config.toml");
        addConfigModels(models, readMetadata(config));
        addCodexCacheModels(models, codex.resolve("models_cache.json"));
        return List.copyOf(models);
    }

    /** Returns Codex effort values for a model, using the local cache when it describes that model. */
    public static List<String> codexReasoningEfforts(String model) {
        return codexReasoningEfforts(model, home());
    }

    /**
     * Narrows the effort values from the supplied CLI metadata root; package-private for tests.
     *
     * <p>Only {@code models_cache.json} is consulted, because that is the only file Codex itself
     * reads. A stale sibling backup would otherwise be able to narrow the picker down to values the
     * running CLI no longer accepts, which fails at the API rather than in the panel.
     */
    static List<String> codexReasoningEfforts(String model, Path metadataHome) {
        String target = model == null || model.isBlank()
                ? configuredCodexModel(metadataHome)
                : model.trim();
        if (target != null && !target.isBlank()) {
            Set<String> efforts = new LinkedHashSet<>();
            efforts.add("");
            addCodexCacheEfforts(efforts,
                    metadataHome.resolve(".codex").resolve("models_cache.json"), target);
            if (efforts.size() > 1) {
                return List.copyOf(efforts);
            }
        }
        return codexReasoningEfforts();
    }

    /** Parses Claude settings metadata without touching the filesystem; package-private for tests. */
    static List<String> parseClaudeModelMetadata(String json) {
        Set<String> models = new LinkedHashSet<>();
        try {
            addClaudeJsonModels(models, MAPPER.readTree(json == null ? "" : json));
        } catch (IOException | RuntimeException ignored) {
            // Malformed optional metadata is treated as unavailable.
        }
        return List.copyOf(models);
    }

    /** Parses one Codex cache payload without touching the filesystem; package-private for tests. */
    static List<String> parseCodexReasoningEfforts(String json, String target) {
        Set<String> efforts = new LinkedHashSet<>();
        efforts.add("");
        try {
            addCodexCacheEfforts(efforts, MAPPER.readTree(json == null ? "" : json), target);
        } catch (IOException | RuntimeException ignored) {
            // Malformed optional metadata is treated as unavailable.
        }
        return List.copyOf(efforts);
    }

    private static void addJsonField(Set<String> models, Path path, String field) {
        try {
            if (!Files.isRegularFile(path)) {
                return;
            }
            JsonNode root = MAPPER.readTree(readMetadata(path));
            addClaudeJsonModels(models, root);
        } catch (IOException | RuntimeException ignored) {
            // Optional CLI metadata is best effort; an unavailable catalog stays empty.
        }
    }

    private static void addCodexCacheModels(Set<String> models, Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return;
            }
            JsonNode root = MAPPER.readTree(readMetadata(path));
            JsonNode entries = root.get("models");
            if (entries == null || !entries.isArray()) {
                return;
            }
            for (JsonNode entry : entries) {
                if (isOfferedCodexModel(entry)) {
                    addModel(models, textual(entry, "slug"));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Optional CLI metadata is best effort; an unavailable catalog stays empty.
        }
    }

    private static void addCodexCacheEfforts(Set<String> efforts, Path path, String target) {
        try {
            if (!Files.isRegularFile(path)) {
                return;
            }
            JsonNode root = MAPPER.readTree(readMetadata(path));
            addCodexCacheEfforts(efforts, root, target);
        } catch (IOException | RuntimeException ignored) {
            // Optional CLI metadata is best effort; the conservative effort fallback remains usable.
        }
    }

    private static void addCodexCacheEfforts(Set<String> efforts, JsonNode root, String target) {
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode entries = root.get("models");
        if (entries == null || !entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            // A hidden or non-API entry is not a model the picker offers, so its level list must not
            // narrow anything either: the cache can carry a same-slug entry the CLI will not run.
            if (!target.equals(textual(entry, "slug")) || !isOfferedCodexModel(entry)) {
                continue;
            }
            JsonNode levels = entry.get("supported_reasoning_levels");
            if (levels != null && levels.isArray()) {
                for (JsonNode level : levels) {
                    addEffort(efforts, textual(level, "effort"));
                }
            }
        }
    }

    private static void addClaudeJsonModels(Set<String> models, JsonNode root) {
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode model = root.get("model");
        if (model != null && model.isTextual()) {
            addModel(models, model.asText());
        }
        JsonNode env = root.get("env");
        if (env != null && env.isObject()) {
            env.fields().forEachRemaining(entry -> {
                if (isClaudeModelKey(entry.getKey()) && entry.getValue().isTextual()) {
                    addModel(models, entry.getValue().asText());
                }
            });
        }
    }

    /**
     * A string field's value, or {@code ""} when the field is absent or holds anything else. A model id
     * and an effort level are strings in every metadata format these files use, and Jackson will happily
     * render a number or a boolean as one: {@code "slug": 123} would put {@code 123} in the model picker,
     * and {@code "effort": 456} would offer it as a reasoning level — a value no CLI accepts, presented
     * as if its own metadata had named it. Metadata that is malformed contributes nothing instead.
     */
    private static String textual(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static void addEffort(Set<String> efforts, String effort) {
        if (isAcceptableEffortLevel(effort) && efforts.size() < MAX_EFFORTS) {
            efforts.add(effort.trim());
        }
    }

    /**
     * Whether a level the local cache names can be offered. The value is sent as
     * {@code -c model_reasoning_effort="…"}, so what the CLI can read is a single bare token: letters,
     * digits and the separators an identifier uses. Nothing is checked against the levels this build
     * knows, because a level Codex adds tomorrow is one the user can run today and hiding it would be the
     * worse error - but a value that is not a token at all is not a level the CLI would take, and putting
     * it in the picker would offer a send that cannot work as if the CLI's own metadata had named it.
     */
    static boolean isAcceptableEffortLevel(String effort) {
        if (effort == null) {
            return false;
        }
        String trimmed = effort.trim();
        return !trimmed.isEmpty() && trimmed.length() <= MAX_EFFORT_CHARS
                && trimmed.chars().allMatch(c -> c == '-' || c == '_' || c == '.'
                        || (c < 0x80 && Character.isLetterOrDigit(c)));
    }

    /** Whether a cache entry describes a model the CLI will actually run for us. */
    private static boolean isOfferedCodexModel(JsonNode entry) {
        return entry != null
                && !saysUnlisted(entry.path("visibility"))
                && !saysUnavailable(entry.path("supported_in_api"));
    }

    /**
     * Whether the cache says this model is not listed. A field only says something when it is the kind of
     * value it is supposed to be: a visibility that is not a string, like a {@code supported_in_api} that
     * is not a boolean, is a cache written to a schema this build does not know, and it has said nothing
     * about this model. Reading a coerced value would decide the question by guessing in both directions -
     * a numeric {@code 0} read as false hides a model the user can run, a {@code 1} read as true offers one
     * the API refuses - and of those the hidden model is the worse error, because no error message would
     * ever explain it. So an entry that says nothing is offered, exactly as one with no such field is.
     */
    private static boolean saysUnlisted(JsonNode visibility) {
        return visibility.isTextual() && !"list".equals(visibility.textValue());
    }

    /** Whether the cache says the API will not serve this model, on the same terms. */
    private static boolean saysUnavailable(JsonNode supported) {
        return supported.isBoolean() && !supported.booleanValue();
    }

    /**
     * The model a bare {@code codex exec} would run, from {@code config.toml}, or {@code ""} when that
     * cannot be read off the file. Only the top-level table counts: a {@code model} key under
     * {@code [profiles.something]} configures a profile that is not in effect, and narrowing the
     * effort picker against it would delete levels the model actually running does support. A
     * top-level {@code profile = "…"} hands the decision to a table this does not resolve, so it
     * answers "unknown" rather than guessing — the picker then offers every level, which the CLI
     * itself will reject if wrong, instead of hiding a level that would have worked.
     */
    private static String configuredCodexModel(Path metadataHome) {
        String topLevel = topLevelTable(
                readMetadata(metadataHome.resolve(".codex").resolve("config.toml")));
        if (CODEX_CONFIG_PROFILE.matcher(topLevel).find()) {
            return "";
        }
        for (String line : topLevel.split("\\R")) {
            String model = modelAssignment(line);
            if (model != null) {
                return model.trim();
            }
        }
        return "";
    }

    /**
     * The part of a TOML document that assigns top-level keys: everything before the first table
     * header, with comments and the bodies of multi-line strings left out. A line-by-line scan rather
     * than one regex, because {@code model = "…"} inside a {@code """ … """} value or after a table
     * header is not a top-level key, and reading it as one would narrow the effort picker against a
     * model that is not going to run.
     *
     * <p>Brackets left open by a value are counted, because an array written over several lines has
     * element lines of its own ({@code ["PATH", "HOME"]}) that are indistinguishable from a table header
     * read alone. Inside such a value nothing is a header and nothing is a top-level key, so those lines
     * are skipped. A miscount cannot invent a top-level key either way: too low ends the scan early at an
     * element line, too high skips the rest of the file — both give up rather than read some table's
     * {@code model} as the one in effect. Giving up is still a wrong answer, so the count is kept honest
     * past the delimiter that ends a value: in a multi-line array the bracket closing the array can share
     * the line that closes a string element, and leaving that bracket uncounted would make every later
     * key look like one more element — so the top-level {@code model} right after such an array narrowed
     * nothing.
     */
    private static String topLevelTable(String toml) {
        if (toml == null) {
            return "";
        }
        StringBuilder topLevel = new StringBuilder();
        String closing = null;
        int openBrackets = 0;
        for (String line : toml.split("\\R")) {
            String rest = line;
            if (closing != null) {
                int close = indexOfUnescaped(rest, closing, 0);
                if (close < 0) {
                    continue;
                }
                rest = rest.substring(close + closing.length());
                closing = null;
            }
            TomlLine scanned = scanTomlLine(rest);
            if (openBrackets == 0) {
                if (isTableHeader(scanned.code().strip())) {
                    break;
                }
                if (scanned.opensMultiline() == null) {
                    topLevel.append(rest).append('\n');
                } else {
                    // A line that opens a multi-line value still assigns a key, and which key it is can
                    // decide whether any of these keys are in effect: TOML trims the newline after the
                    // opening delimiter, so profile = """ … """ written over two lines selects a profile
                    // exactly like the one-line spelling. Only this line's own text is taken, never the
                    // body that follows it, and a value still open cannot read as a finished
                    // model = "…" assignment.
                    topLevel.append(scanned.code()).append('\n');
                }
            }
            openBrackets = Math.max(0, openBrackets + scanned.bracketDelta());
            closing = scanned.opensMultiline();
        }
        return topLevel.toString();
    }

    /**
     * One scanned line: its code (comment removed), the multi-line string it leaves open, and how many
     * brackets it opens beyond those it closes — counted outside strings, so a {@code [} in a value or a
     * comment is text.
     */
    private record TomlLine(String code, String opensMultiline, int bracketDelta) { }

    /**
     * Scans one line into the part that is TOML code — everything up to a {@code #} that is not inside a
     * string — and the multi-line delimiter it opens without closing. Quoting has to be tracked to find
     * that {@code #}, and tracking it is what keeps a {@code '''} or a bracket <em>inside a comment or a
     * string value</em> from being read as syntax: a trailing {@code # '''} would otherwise swallow every
     * following line as a string body.
     */
    private static TomlLine scanTomlLine(String line) {
        StringBuilder code = new StringBuilder();
        int index = 0;
        int brackets = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (c == '#') {
                break;
            }
            if (c == '"' || c == '\'') {
                String multiline = String.valueOf(c).repeat(3);
                boolean opensMultiline = line.startsWith(multiline, index);
                int close = opensMultiline
                        ? indexOfUnescaped(line, multiline, index + multiline.length())
                        : closeOfQuoted(line, index);
                if (close < 0) {
                    // Unterminated on this line: the rest of it is string body, delimiters and all.
                    code.append(line, index, line.length());
                    return new TomlLine(code.toString(), opensMultiline ? multiline : null, brackets);
                }
                int end = close + (opensMultiline ? multiline.length() : 1);
                code.append(line, index, end);
                index = end;
                continue;
            }
            if (c == '[') {
                brackets++;
            } else if (c == ']') {
                brackets--;
            }
            code.append(c);
            index++;
        }
        return new TomlLine(code.toString(), null, brackets);
    }

    /**
     * Index of the first {@code delimiter} at or after {@code from} that the value does not escape, or
     * {@code -1}. Only a basic ({@code """}) string escapes with a backslash; a literal ({@code '''}) one
     * has none, so there every occurrence ends it. TOML writes three quotes inside a basic multi-line
     * string by escaping one of them, and reading that {@code \"""} as the end of the value would put the
     * rest of its body back in scope — a {@code model = "…"} line written inside a string would then be
     * read as the top-level key and narrow the effort picker against a model nothing is going to run.
     */
    private static int indexOfUnescaped(String line, String delimiter, int from) {
        boolean escapes = delimiter.charAt(0) == '"';
        for (int index = from; index + delimiter.length() <= line.length(); index++) {
            if (escapes && line.charAt(index) == '\\') {
                index++;   // whatever it escapes cannot start the delimiter
                continue;
            }
            if (line.startsWith(delimiter, index)) {
                return index;
            }
        }
        return -1;
    }

    /** Index of the quote closing the single-line string starting at {@code start}, or {@code -1}. */
    private static int closeOfQuoted(String line, int start) {
        char quote = line.charAt(start);
        for (int index = start + 1; index < line.length(); index++) {
            char c = line.charAt(index);
            if (quote == '"' && c == '\\') {
                index++;   // a basic string escapes its own quote; a literal string has no escapes
                continue;
            }
            if (c == quote) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Whether this line is a TOML table header, {@code [table]} or {@code [[array of tables]]}: nothing
     * after it is a top-level key. The name is scanned quote-aware, because Codex's own sections carry
     * quoted keys full of punctuation ({@code [projects."/Users/me/repo"]}) — including brackets. The
     * closing bracket must end the line, so an array element that merely starts with one
     * ({@code   [1, 2],}) is not read as a header.
     *
     * <p>Where the two readings are genuinely ambiguous, erring towards "this is a header" is the safe
     * error: stopping too early means no configured model is found and every effort level stays on
     * offer, while missing a header would read some table's {@code model} as the top-level one and
     * narrow the picker against a model that is not going to run.
     */
    private static boolean isTableHeader(String line) {
        if (!line.startsWith("[")) {
            return false;
        }
        int index = 1;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (c == '"' || c == '\'') {
                int close = closeOfQuoted(line, index);
                if (close < 0) {
                    return false;
                }
                index = close + 1;
                continue;
            }
            if (c == ']') {
                break;
            }
            index++;
        }
        return index < line.length()
                && line.substring(index).chars().allMatch(c -> c == ']' || Character.isWhitespace(c));
    }

    /**
     * Every model id a Codex {@code config.toml} names: assigned to {@code model}, or keyed by one.
     *
     * <p>A key that is a model id is only one under {@code models}. Every other table Codex keys by a quoted
     * name keys it by something that is not a model, and some of them are keyed by ids that merely look like
     * one — {@code [tui.model_availability_nux]} counts how often each model was mentioned to the user, so
     * its keys are model ids the CLI may have no configuration for at all. Which table a key belongs to is
     * therefore tracked as the lines go by, exactly as {@link #CODEX_MODEL_TABLE} insists on its first
     * segment. A model assigned to {@code model} is read from any table, because that one is a model
     * wherever it appears: a profile's is an id the user does run.
     *
     * <p>TOML spells that one mapping four ways, and the picker reads all four: a key under a
     * {@code [models]} table, a {@code [models."gpt-…"]} header, a dotted {@code models."gpt-…" = …} at the
     * top level, and an inline {@code models = { "gpt-…" = … }} there. Dropping a spelling would hide a
     * model the file configures as plainly as any other, with nothing in the panel to explain the absence.
     */
    private static void addConfigModels(Set<String> models, String text) {
        String config = assignments(text);
        boolean underModels = false;
        boolean topLevel = true;
        for (String line : config.split("\\R")) {
            String header = line.strip();
            if (isTableHeader(header)) {
                underModels = isModelsTable(header);
                topLevel = false;
            }
            String assigned = modelAssignment(line);
            addModel(models, assigned != null ? assigned : dottedModelAssignment(line));
            addInlineTableModels(models, line);
            if (underModels) {
                addModel(models, keyedModelId(line, 0));
            }
            if (topLevel) {
                addModel(models, dottedKeyedModelId(line));
                addInlineKeyedModelIds(models, line);
            }
        }
        Matcher tabled = CODEX_MODEL_TABLE.matcher(config);
        while (tabled.find()) {
            String quoted = tabled.group(1);
            addModel(models, quoted != null ? decodeBasicString(quoted) : tabled.group(2));
        }
    }

    /**
     * The model id the key at {@code from} names, or {@code null} where that key is not one. The entries of
     * the {@code models} table are keyed by ids, so a key there is read as one when it has the shape of one;
     * TOML lets it be written quoted or, for an id with no dot in it, bare.
     *
     * <p>What may follow is either the {@code =} that assigns this entry or the {@code .} that dots into it,
     * because {@code "gpt-…".reasoning_effort = "high"} names the id as plainly as a table of settings does —
     * but only for the quoted spelling, where the whole id is inside the quotes. A bare key stops at a dot,
     * so a dot after one means the id ended earlier than whoever wrote it meant ({@code gpt-5.5-codex} is
     * two keys, not one), and offering the part before it would offer a model they did not configure.
     */
    private static String keyedModelId(String line, int from) {
        int index = skipBlanks(line, from);
        if (index >= line.length()) {
            return null;
        }
        String id;
        boolean quoted = line.charAt(index) == '"' || line.charAt(index) == '\'';
        if (quoted) {
            int close = closeOfQuoted(line, index);
            if (close < 0) {
                return null;
            }
            String body = line.substring(index + 1, close);
            id = line.charAt(index) == '"' ? decodeBasicString(body) : body;
            index = close + 1;
        } else {
            int end = index;
            while (end < line.length() && isBareKeyCharacter(line.charAt(end))) {
                end++;
            }
            id = line.substring(index, end);
            index = end;
        }
        index = skipBlanks(line, index);
        if (!id.startsWith(CODEX_MODEL_ID_PREFIX) || index >= line.length()) {
            return null;
        }
        char next = line.charAt(index);
        return next == '=' || quoted && next == '.' ? id : null;
    }

    /** The characters TOML allows in a bare key: ASCII letters and digits, underscore and dash. */
    private static boolean isBareKeyCharacter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || c == '_' || c == '-';
    }

    /**
     * The model id a dotted key at the top level names, or {@code null}. {@code models."gpt-…" = { … }} is
     * the same entry as that id keyed under a {@code [models]} table, exactly as
     * {@code profiles.work.model = "…"} is the same as {@code model} under {@code [profiles.work]}. Only the
     * top level counts, because a dotted key under some other table dots into that one instead: what
     * {@code models."gpt-…"} names under {@code [tui]} is a key of {@code tui}, not a model.
     */
    private static String dottedKeyedModelId(String line) {
        int after = afterKey(line, skipBlanks(line, 0), CODEX_MODELS_KEYS);
        if (after < 0) {
            return null;
        }
        int index = skipBlanks(line, after);
        if (index >= line.length() || line.charAt(index) != '.') {
            return null;
        }
        return keyedModelId(line, index + 1);
    }

    /**
     * Every model id an inline {@code models = { … }} at the top level keys, which is the one-line spelling
     * of the whole table. Only the entries of that table are ids: what a table nested inside one of them
     * holds is that model's settings, so the walk reads keys at the first depth alone.
     */
    private static void addInlineKeyedModelIds(Set<String> models, String line) {
        int after = afterKey(line, skipBlanks(line, 0), CODEX_MODELS_KEYS);
        if (after < 0) {
            return;
        }
        int index = skipBlanks(line, after);
        if (index >= line.length() || line.charAt(index) != '=') {
            return;
        }
        index = skipBlanks(line, index + 1);
        if (index >= line.length() || line.charAt(index) != '{') {
            return;
        }
        int depth = 0;
        boolean atEntryStart = false;
        while (index < line.length()) {
            index = skipBlanks(line, index);
            if (index >= line.length()) {
                return;
            }
            if (atEntryStart && depth == 1) {
                atEntryStart = false;
                addModel(models, keyedModelId(line, index));
            }
            char c = line.charAt(index);
            if (c == '"' || c == '\'') {
                int close = closeOfQuoted(line, index);
                if (close < 0) {
                    // Unterminated: what follows is string body, so no entry starts in it.
                    return;
                }
                index = close + 1;
                continue;
            }
            if (c == '{') {
                depth++;
                atEntryStart = true;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',') {
                atEntryStart = depth > 0;
            }
            index++;
        }
    }

    /**
     * Whether this header opens the {@code models} table itself, whose entries are keyed by model ids. The
     * name may be written bare or quoted, and it has to be the whole of it: the keys of a table <em>under</em>
     * {@code models} are that model's settings ({@code [models."gpt-…"]} holds a {@code reasoning_effort}),
     * so the id there is in the header, which {@link #CODEX_MODEL_TABLE} reads. The {@code ]} must follow the
     * name for the same reason — a line that only reads as a header because an array element happens to end
     * in one ({@code ["models", "other"]}) opens no table, and reading it as this one would put whatever keys
     * follow it in the picker.
     *
     * <p>An array of tables is not this table. {@code [[models]]} appends an element to an array named
     * {@code models}, whose own keys are that element's fields rather than model ids — Codex configures no
     * such array, so a file that has one is written to something this release does not know and its keys are
     * nothing to offer.
     *
     * <p>Answering {@code false} where the two readings are ambiguous is the safe error, the same way round
     * as {@link #isTableHeader}: a model the file does name is left out of the picker, which the user can
     * add by hand, where offering a key that was never a model puts an id in the picker that fails at the
     * CLI on every send.
     */
    private static boolean isModelsTable(String header) {
        if (header.startsWith("[[")) {
            return false;
        }
        int index = skipBlanks(header, 1);
        if (index >= header.length()) {
            return false;
        }
        String first;
        char quote = header.charAt(index);
        if (quote == '"' || quote == '\'') {
            int close = closeOfQuoted(header, index);
            if (close < 0) {
                return false;
            }
            String body = header.substring(index + 1, close);
            first = quote == '"' ? decodeBasicString(body) : body;
            index = close + 1;
        } else {
            int end = index;
            while (end < header.length() && header.charAt(end) != '.' && header.charAt(end) != ']'
                    && header.charAt(end) != ' ' && header.charAt(end) != '\t') {
                end++;
            }
            first = header.substring(index, end);
            index = end;
        }
        index = skipBlanks(header, index);
        return "models".equals(first) && index < header.length() && header.charAt(index) == ']';
    }

    /**
     * Every model id an inline table on this line assigns: {@code profiles.work = { model = "…" }} is the
     * third way TOML spells a profile's model, and it is an id the user does run, so the picker offers it
     * like the other two. TOML keeps an inline table on one line, so one line is the whole of one.
     *
     * <p>Only a key at the start of an entry is read, and the value it assigns is skipped as a value: a
     * {@code model} written inside a string ({@code note = "{ model = \"x\" }"}) assigns nothing, and
     * offering the text of somebody's note as a model id is the thing this scan exists to avoid. A key that
     * merely starts the same way ({@code model_reasoning_effort}) fails on its next character, exactly as
     * it does at the top level.
     */
    private static void addInlineTableModels(Set<String> models, String line) {
        int index = 0;
        int depth = 0;
        boolean atEntryStart = false;
        while (index < line.length()) {
            index = skipBlanks(line, index);
            if (index >= line.length()) {
                return;
            }
            if (atEntryStart && depth > 0) {
                atEntryStart = false;
                int after = afterModelKey(line, index);
                int equals = after < 0 ? -1 : skipBlanks(line, after);
                if (equals >= 0 && equals < line.length() && line.charAt(equals) == '=') {
                    addModel(models, assignedString(line, equals + 1));
                }
            }
            char c = line.charAt(index);
            if (c == '"' || c == '\'') {
                int close = closeOfQuoted(line, index);
                if (close < 0) {
                    // Unterminated: what follows is string body, so no entry starts in it.
                    return;
                }
                index = close + 1;
                continue;
            }
            if (c == '{') {
                depth++;
                atEntryStart = true;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',') {
                atEntryStart = depth > 0;
            }
            index++;
        }
    }

    /**
     * The lines of a TOML document that assign keys: every line's code, from every table, with comments
     * and the bodies of multi-line strings left out. The catalog wants every model id the file names, a
     * profile's included, because those are ids the user does run — but a line inside a {@code """ … """}
     * value assigns nothing, so a {@code model = "…"} written in one is prose, and offering it would put a
     * line of somebody's instructions in the model picker.
     *
     * <p>Scanning resumes after the delimiter that closed a value rather than at the next line, because a
     * multi-line array is where two such values can meet: {@code ["""…} closed and reopened on one line
     * leaves the body after it open, and treating that whole line as spent would read the body's own lines
     * as assignments — the one thing this pass exists to prevent.
     */
    private static String assignments(String toml) {
        StringBuilder code = new StringBuilder();
        String closing = null;
        for (String line : (toml == null ? "" : toml).split("\\R")) {
            String rest = line;
            if (closing != null) {
                int close = indexOfUnescaped(rest, closing, 0);
                if (close < 0) {
                    continue;
                }
                rest = rest.substring(close + closing.length());
                closing = null;
            }
            TomlLine scanned = scanTomlLine(rest);
            code.append(scanned.code()).append('\n');
            closing = scanned.opensMultiline();
        }
        return code.toString();
    }

    /**
     * The model id one line assigns, or {@code null} when it assigns some other key — or leaves this one's
     * value open. TOML lets the key be written bare or quoted ({@code "model"}, {@code 'model'}), and the
     * value in any of its four string syntaxes, so the delimiter decides how the value reads: a basic
     * string resolves its escapes because that is the id Codex ends up running ({@code model = "gpt-\\u0035"}
     * names gpt-5), and a literal string defines none at all.
     *
     * <p>The value is scanned rather than matched, because what ends a basic string is the first quote it
     * does not escape: stopping at the escaped one in {@code model = "gpt-\\"5"} would offer a truncated id
     * and narrow the effort levels against it. A multi-line value counts only when it also ends on this
     * line — a delimiter left open assigns a value the line does not carry yet, and reading the quotes
     * themselves as that value would offer punctuation as a model id.
     */
    private static String modelAssignment(String line) {
        int index = afterModelKey(line, skipBlanks(line, 0));
        if (index < 0) {
            return null;
        }
        index = skipBlanks(line, index);
        if (index >= line.length() || line.charAt(index) != '=') {
            return null;
        }
        return assignedString(line, index + 1);
    }

    /**
     * The model id a dotted key assigns, or {@code null} when the line assigns some other key. TOML lets a
     * table's key be written either way, and {@code profiles.work.model = "…"} is the same assignment as
     * {@code [profiles.work]} followed by {@code model = "…"} — the same id the user does run, so the
     * picker offers it for both spellings rather than only the one this scan happens to find easiest.
     *
     * <p>Only the last segment is read as the key; which table the earlier segments name is not resolved.
     * That is why this is used for the catalog alone: {@link #configuredCodexModel} keeps the strict
     * top-level test, so a dotted profile model still never narrows the effort picker.
     */
    private static String dottedModelAssignment(String line) {
        int index = skipBlanks(line, 0);
        int segment = index;
        boolean dotted = false;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (c == '"' || c == '\'') {
                int close = closeOfQuoted(line, index);
                if (close < 0) {
                    return null;
                }
                index = close + 1;
                continue;
            }
            if (c == '=') {
                break;
            }
            if (c == '.') {
                dotted = true;
                segment = index + 1;
            }
            index++;
        }
        if (index >= line.length() || !dotted) {
            return null;
        }
        if (!CODEX_MODEL_KEYS.contains(line.substring(segment, index).strip())) {
            return null;
        }
        return assignedString(line, index + 1);
    }

    /** The string value a line assigns, read from just past its {@code =}, or {@code null}. */
    private static String assignedString(String line, int from) {
        int index = skipBlanks(line, from);
        if (index >= line.length()) {
            return null;
        }
        char quote = line.charAt(index);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        String multiline = String.valueOf(quote).repeat(3);
        boolean multi = line.startsWith(multiline, index);
        int start = index + (multi ? multiline.length() : 1);
        int close = multi
                ? indexOfUnescaped(line, multiline, start)
                : closeOfQuoted(line, index);
        if (close < 0) {
            return null;
        }
        String value = line.substring(start, close);
        return quote == '"' ? decodeBasicString(value) : value;
    }

    /**
     * The index just past a {@code model} key at {@code from}, or {@code -1}. Only the key itself is
     * matched here: whether what follows is this key being assigned is the caller's question, so
     * {@code model_reasoning_effort = "high"} and {@code model.foo = "x"} — a key that merely starts the
     * same way, and a dotted key that assigns into a table — are left to fail on their next character.
     */
    private static int afterModelKey(String line, int from) {
        return afterKey(line, from, CODEX_MODEL_KEYS);
    }

    /** The index just past whichever of {@code keys} is written at {@code from}, or {@code -1}. */
    private static int afterKey(String line, int from, List<String> keys) {
        for (String key : keys) {
            if (line.startsWith(key, from)) {
                return from + key.length();
            }
        }
        return -1;
    }

    /** The index of the first character at or after {@code from} that is not a space or a tab. */
    private static int skipBlanks(String line, int from) {
        int index = from;
        while (index < line.length() && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    /** Resolves the escapes of a TOML basic string; a sequence it does not define stays as written. */
    private static String decodeBasicString(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        Matcher escape = BASIC_STRING_ESCAPE.matcher(value);
        StringBuilder decoded = new StringBuilder();
        int copied = 0;
        while (escape.find()) {
            decoded.append(value, copied, escape.start());
            appendEscape(decoded, escape.group(), escape.group(1));
            copied = escape.end();
        }
        return decoded.append(value.substring(copied)).toString();
    }

    private static void appendEscape(StringBuilder decoded, String written, String escape) {
        switch (escape.charAt(0)) {
            case 'b' -> decoded.append('\b');
            case 't' -> decoded.append('\t');
            case 'n' -> decoded.append('\n');
            case 'f' -> decoded.append('\f');
            case 'r' -> decoded.append('\r');
            case '"' -> decoded.append('"');
            case '\\' -> decoded.append('\\');
            case 'u', 'U' -> appendCodePoint(decoded, written, escape);
            // Not an escape TOML defines. Leaving it as written keeps a malformed line from quietly
            // naming some other model, and the id it yields simply matches nothing.
            default -> decoded.append(written);
        }
    }

    /**
     * Appends a {@code \\uXXXX} / {@code \\UXXXXXXXX} escape. One that names no code point — too few
     * digits, or a value outside Unicode — is not an escape either, so it stays exactly as written rather
     * than turning into some other character.
     */
    private static void appendCodePoint(StringBuilder decoded, String written, String escape) {
        int codePoint = escape.length() == 5 || escape.length() == 9
                ? Integer.parseUnsignedInt(escape.substring(1), 16)
                : -1;
        if (Character.isValidCodePoint(codePoint)) {
            decoded.appendCodePoint(codePoint);
        } else {
            decoded.append(written);
        }
    }

    private static void addModel(Set<String> models, String model) {
        if (isAcceptableModelId(model) && models.size() < MAX_MODELS) {
            models.add(model.trim());
        }
    }

    /**
     * Whether a model id can be offered. A control character would either break the one-value-per-id
     * storage line or make the CLI launch fail at argv assembly, and an absurdly long id only exists
     * to overflow the preference value, so neither is worth carrying into the picker. Unicode's own
     * separators are rejected for the same reason: the stored catalog is split on {@code \R}, which
     * treats them as line breaks even though they are not ISO control characters.
     */
    public static boolean isAcceptableModelId(String model) {
        if (model == null) {
            return false;
        }
        String trimmed = model.trim();
        return !trimmed.isEmpty() && !trimmed.equals("(default)")
                && trimmed.length() <= MAX_MODEL_ID_CHARS
                && trimmed.codePoints().noneMatch(
                        codePoint -> Character.isISOControl(codePoint) || isLineSeparator(codePoint));
    }

    private static boolean isLineSeparator(int codePoint) {
        return codePoint == UNICODE_LINE_SEPARATOR || codePoint == UNICODE_PARAGRAPH_SEPARATOR;
    }

    /** The longest model id the catalog stores. */
    public static int maxModelIdChars() {
        return MAX_MODEL_ID_CHARS;
    }

    /** The most model ids one provider's catalog stores. */
    public static int maxModels() {
        return MAX_MODELS;
    }

    private static boolean isClaudeModelKey(String key) {
        return key.equals("ANTHROPIC_MODEL")
                || key.equals("ANTHROPIC_SMALL_FAST_MODEL")
                || (key.startsWith("ANTHROPIC_DEFAULT_") && key.endsWith("_MODEL"));
    }

    /**
     * Reads optional CLI metadata with a bounded size; package-private for headless tests. UTF-8
     * byte-order marks are dropped wherever they appear, not only at the start: editors write one there
     * and it is not part of the document — left in place it glues onto the first key or makes the JSON
     * unparseable, so the file would silently contribute nothing. One that turns up later is no more
     * content than the first, and it is worse than useless: a mark before {@code [profiles.work]} or
     * before a {@code profile =} line hides the very syntax that decides which model runs, which would
     * read a profile's model as the top-level one and narrow the effort picker against it.
     */
    static String readMetadata(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_METADATA_BYTES) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8).replace(BYTE_ORDER_MARK, "");
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static Path home() {
        String userHome = System.getProperty("user.home", "");
        return Path.of(userHome.isBlank() ? "." : userHome);
    }

    private static List<String> normalize(List<String> models) {
        Set<String> unique = new LinkedHashSet<>();
        if (models != null) {
            for (String model : models) {
                addModel(unique, model);
            }
        }
        return List.copyOf(unique);
    }
}
