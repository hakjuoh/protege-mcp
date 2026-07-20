package io.github.hakjuoh.protege_mcp.core.qc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLOntology;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.qc.CompetencyQuestionService.Expectation;
import io.github.hakjuoh.protege_mcp.core.qc.CompetencyQuestionService.ExpectationKind;
import io.github.hakjuoh.protege_mcp.core.qc.CompetencyQuestionService.Question;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Bounded, deterministic loader for policy-referenced invariant and competency-question assets. */
public final class ValidationAssetLoader {

    public static final String ROBOT = "robot-sparql-dir";
    public static final String MANIFEST = "sidecar-manifest";
    public static final String ANNOTATIONS = "ontology-annotations";
    public static final String CQ_ANNOTATION_IRI =
            "https://hakjuoh.github.io/protege-mcp/cq#competencyQuestion";

    private static final long MAX_QUERY_BYTES = 1_048_576L;
    private static final long MAX_MANIFEST_BYTES = 8_388_608L;
    public static final int MANIFEST_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ValidationAssetLoader() {
    }

    public static final class AssetException extends RuntimeException {
        public AssetException(String message) {
            super(message);
        }
    }

    public static List<InvariantQcService.Invariant> loadInvariants(List<Path> paths) {
        if (paths == null) throw new IllegalArgumentException("paths must not be null");
        List<InvariantQcService.Invariant> invariants = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Path path : paths) {
            InvariantQcService.Invariant invariant = readInvariant(path);
            if (!ids.add(invariant.id())) {
                throw new AssetException("Duplicate invariant id '" + invariant.id()
                        + "' in policy assets; ids must be unique across every .rq file.");
            }
            invariants.add(invariant);
        }
        return List.copyOf(invariants);
    }

    public static InvariantQcService.Invariant readInvariant(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new AssetException("Invariant path is not a regular file: " + path);
            }
            String content = readBounded(path, MAX_QUERY_BYTES,
                    "Invariant file exceeds " + MAX_QUERY_BYTES + " bytes: " + path);
            String id = stem(path);
            String message = null;
            String severity = "error";
            boolean inferred = false;
            boolean inHeader = true;
            StringBuilder query = new StringBuilder();
            for (String line : normalizedLines(content)) {
                String trimmed = line.trim();
                if (inHeader && (trimmed.isEmpty() || trimmed.startsWith("#"))) {
                    if (trimmed.startsWith("#")) {
                        String header = trimmed.substring(1).trim();
                        int colon = header.indexOf(':');
                        if (colon > 0) {
                            String key = header.substring(0, colon).trim()
                                    .toLowerCase(Locale.ROOT);
                            String value = header.substring(colon + 1).trim();
                            switch (key) {
                                case "id" -> { if (!value.isBlank()) id = value; }
                                case "message" -> message = value;
                                case "severity" -> severity = normalizeSeverity(value);
                                case "include_inferred" -> {
                                    if (!"true".equalsIgnoreCase(value)
                                            && !"false".equalsIgnoreCase(value)) {
                                        throw new AssetException("Invalid include_inferred header in "
                                                + path + ": use true or false.");
                                    }
                                    inferred = Boolean.parseBoolean(value);
                                }
                                default -> { }
                            }
                        }
                    }
                    continue;
                }
                inHeader = false;
                query.append(line).append('\n');
            }
            String sparql = query.toString().trim();
            if (sparql.isEmpty()) {
                throw new AssetException("Invariant file contains no SPARQL query: " + path);
            }
            if (id == null || id.isBlank()) {
                throw new AssetException("Invariant id is blank in " + path);
            }
            return new InvariantQcService.Invariant(id,
                    message == null || message.isBlank()
                            ? "Invariant '" + id + "' violated." : message,
                    severity, sparql, inferred);
        } catch (IOException error) {
            throw new AssetException("Could not read invariant file " + path + ": "
                    + message(error));
        }
    }

    /** Load the policy-selected CQ convention from snapshot-remapped assets and ontology state. */
    public static List<Question> loadQuestions(ProjectPolicy policy,
            Map<String, List<Path>> assets, OWLOntology ontology) {
        if (policy == null || assets == null) {
            throw new IllegalArgumentException("policy and assets must not be null");
        }
        Map<String, Object> validation = object(policy.effective().get("validation"));
        Map<String, Object> configured = object(validation.get("competency_questions"));
        String convention = string(configured.get("convention"));
        return loadQuestions(convention, assets.getOrDefault("cqs", List.of()), ontology);
    }

    public static List<Question> loadQuestions(String convention, List<Path> paths,
            OWLOntology ontology) {
        if (paths == null) throw new IllegalArgumentException("paths must not be null");
        List<Question> questions;
        if (ANNOTATIONS.equals(convention)) {
            if (ontology == null) {
                throw new AssetException("ontology-annotations competency questions require an "
                        + "ontology snapshot.");
            }
            questions = annotationQuestions(ontology);
        } else {
            if (paths.size() != 1) {
                throw new AssetException("Exactly one competency-question path must resolve for "
                        + "convention " + convention + ".");
            }
            Path path = paths.get(0);
            if (ROBOT.equals(convention)) questions = robotQuestions(path);
            else if (MANIFEST.equals(convention)) questions = manifestQuestions(path);
            else throw new AssetException("Unsupported policy CQ convention: " + convention);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Question question : questions) {
            if (!ids.add(question.id())) {
                throw new AssetException("Duplicate competency-question id in policy assets: "
                        + question.id());
            }
        }
        return List.copyOf(questions);
    }

    private static List<Question> robotQuestions(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new AssetException("robot-sparql-dir competency question path must be a directory: "
                    + directory);
        }
        final Path base;
        try {
            base = directory.toRealPath();
        } catch (IOException error) {
            throw new AssetException("Competency-question assets contain unreadable entries: "
                    + directory.getFileName() + ": " + message(error));
        }
        List<Question> questions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            for (Path path : stream.filter(value -> value.getFileName().toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".rq"))
                    .sorted(Comparator.comparing(value -> value.getFileName().toString())).toList()) {
                try {
                    if (!path.toRealPath().startsWith(base)) {
                        throw new AssetException(
                                "competency-question file resolves outside the cqs directory");
                    }
                    questions.add(readRobotQuestion(path));
                } catch (RuntimeException | IOException error) {
                    errors.add(path.getFileName() + ": " + message(error));
                }
            }
        } catch (IOException error) {
            errors.add(directory.getFileName() + ": " + message(error));
        }
        failUnreadable(errors);
        return questions;
    }

    private static Question readRobotQuestion(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("CQ path is not a regular file");
        }
        String content = readBounded(path, MAX_QUERY_BYTES,
                "CQ file exceeds " + MAX_QUERY_BYTES + " bytes");
        String id = stem(path);
        String text = null;
        Expectation expected = expectationNonEmpty();
        boolean includeInferred = true;
        StringBuilder query = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                String header = trimmed.substring(1).trim();
                int colon = header.indexOf(':');
                if (colon < 0) continue;
                String key = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = header.substring(colon + 1).trim();
                switch (key) {
                    case "id" -> { if (!value.isEmpty()) id = value; }
                    case "text" -> text = value;
                    case "expected" -> expected = expectationFromHeader(value);
                    case "include_inferred" -> includeInferred = Boolean.parseBoolean(value);
                    default -> { }
                }
            } else {
                query.append(line).append('\n');
            }
        }
        return question(id, text, expected, ROBOT, query.toString().trim(), includeInferred);
    }

    private static List<Question> manifestQuestions(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new AssetException("sidecar-manifest competency question path must be a JSON file: "
                    + path);
        }
        List<String> errors = new ArrayList<>();
        List<Question> questions = new ArrayList<>();
        try {
            String content = readBounded(path, MAX_MANIFEST_BYTES,
                    "manifest exceeds " + MAX_MANIFEST_BYTES + " bytes");
            Object parsed;
            try {
                parsed = JSON.readValue(content, Object.class);
            } catch (IOException invalidJson) {
                parsed = null;
            }
            if (!(parsed instanceof Map<?, ?> raw)) {
                errors.add(path.getFileName() + ": manifest is not a JSON object");
            } else {
                Map<String, Object> root = castMap(raw);
                int version = integer(root.get("version"), 1);
                if (version > MANIFEST_VERSION) {
                    errors.add(path.getFileName() + ": manifest version " + version
                            + " is newer than this build supports (" + MANIFEST_VERSION
                            + ") — upgrade protege-mcp to read it");
                } else {
                    for (Map<String, Object> entry : objects(root.get("questions"))) {
                        try {
                            questions.add(questionFromMap(entry, MANIFEST));
                        } catch (RuntimeException error) {
                            errors.add(path.getFileName() + "#" + entry.get("id") + ": "
                                    + message(error));
                        }
                    }
                }
            }
        } catch (RuntimeException | IOException error) {
            errors.add(path.getFileName() + ": unreadable manifest: " + message(error));
        }
        failUnreadable(errors);
        return questions;
    }

    private static List<Question> annotationQuestions(OWLOntology ontology) {
        List<Question> questions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int index = 0;
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (!CQ_ANNOTATION_IRI.equals(annotation.getProperty().getIRI().toString())) continue;
            String source = "ontology-annotation#" + index++;
            if (!annotation.getValue().asLiteral().isPresent()) {
                errors.add(source + ": CQ annotation value is not a literal");
                continue;
            }
            try {
                Object parsed;
                try {
                    parsed = JSON.readValue(
                            annotation.getValue().asLiteral().get().getLiteral(), Object.class);
                } catch (IOException invalidJson) {
                    parsed = null;
                }
                if (!(parsed instanceof Map<?, ?> map)) {
                    errors.add(source + ": CQ annotation value is not a JSON object");
                    continue;
                }
                questions.add(questionFromMap(castMap(map), ANNOTATIONS));
            } catch (RuntimeException error) {
                errors.add(source + ": " + message(error));
            }
        }
        failUnreadable(errors);
        return questions;
    }

    private static Question questionFromMap(Map<String, Object> map, String convention) {
        String id = nullableString(map.get("id"));
        String text = nullableString(map.get("text"));
        String queryLanguage = map.get("query_lang") == null
                ? "sparql" : nullableString(map.get("query_lang"));
        if (queryLanguage == null || queryLanguage.isBlank()) queryLanguage = "sparql";
        String query = nullableString(map.get("query"));
        boolean inferred = map.get("include_inferred") == null
                || Boolean.parseBoolean(String.valueOf(map.get("include_inferred")));
        if (id == null || id.trim().isEmpty()) {
            throw new AssetException("A competency question needs a non-empty 'id'.");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new AssetException("Competency question '" + id + "' has no 'query'.");
        }
        if (!"sparql".equals(queryLanguage.toLowerCase(Locale.ROOT))) {
            throw new AssetException("query_lang '" + queryLanguage + "' is not supported "
                    + "(only 'sparql'; a DL path is reserved for a later release).");
        }
        return new Question(id, text, expectationFromObject(map.get("expected")),
                convention, query, inferred);
    }

    private static Question question(String id, String text, Expectation expected,
            String convention, String query, boolean inferred) {
        if (id == null || id.trim().isEmpty()) {
            throw new AssetException("A competency question needs a non-empty 'id'.");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new AssetException("Competency question '" + id + "' has no 'query'.");
        }
        return new Question(id, text, expected, convention, query, inferred);
    }

    private static Expectation expectationFromObject(Object value) {
        if (value == null) return expectationNonEmpty();
        if (value instanceof Map<?, ?> map) return expectationFromMap(castMap(map));
        return expectationFromString(String.valueOf(value));
    }

    private static Expectation expectationFromHeader(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("exactrows")) {
            String json = trimmed.substring("exactrows".length()).trim();
            try {
                Object parsed = JSON.readValue(json, Object.class);
                return exactRows(parsed);
            } catch (IOException error) {
                return exactRows(List.of());
            }
        }
        return expectationFromString(trimmed);
    }

    private static Expectation expectationFromString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return expectationNonEmpty();
        String value = raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (Set.of("nonempty", "non_empty", "non-empty").contains(lower)) {
            return expectationNonEmpty();
        }
        if ("empty".equals(lower)) {
            return new Expectation(ExpectationKind.EMPTY, null, null, List.of());
        }
        if (lower.startsWith("count")) {
            String rest = value.substring("count".length()).trim();
            for (String operator : List.of(">=", "<=", "==", "=", ">", "<")) {
                if (rest.startsWith(operator)) {
                    String normalized = "=".equals(operator) ? "==" : operator;
                    try {
                        return new Expectation(ExpectationKind.COUNT, normalized,
                                Integer.parseInt(rest.substring(operator.length()).trim()), List.of());
                    } catch (NumberFormatException error) {
                        throw new AssetException("Malformed 'expected' spec '" + value + "': '"
                                + rest.substring(operator.length()).trim() + "' is not an integer.");
                    }
                }
            }
            throw new AssetException("Malformed 'expected' count spec: '" + raw + "'. Use e.g. "
                    + "'count >= 3' (operators: >=, <=, ==, >, <).");
        }
        throw new AssetException("Unknown 'expected' spec: '" + raw
                + "'. Use nonEmpty, empty, or 'count OP N' (e.g. 'count >= 3').");
    }

    private static Expectation expectationFromMap(Map<String, Object> map) {
        String raw = String.valueOf(map.getOrDefault("kind", "nonEmpty")).trim();
        return switch (raw.toLowerCase(Locale.ROOT).replace("_", "")) {
            case "nonempty" -> expectationNonEmpty();
            case "empty" -> new Expectation(ExpectationKind.EMPTY, null, null, List.of());
            case "count" -> {
                Object count = map.get("value");
                if (!(count instanceof Number number)) {
                    throw new AssetException("'expected' count needs a numeric 'value'.");
                }
                String operator = String.valueOf(map.getOrDefault("op", ">="));
                if ("=".equals(operator)) operator = "==";
                if (!Set.of(">=", "<=", "==", ">", "<").contains(operator)) {
                    throw new AssetException("Unknown count operator '" + operator
                            + "'. Use one of >=, <=, ==, >, <.");
                }
                yield new Expectation(ExpectationKind.COUNT, operator, number.intValue(), List.of());
            }
            case "exactrows" -> exactRows(map.get("rows"));
            default -> throw new AssetException("Unknown 'expected.kind': '" + raw
                    + "'. Use nonEmpty, empty, count, or exactRows.");
        };
    }

    private static Expectation exactRows(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> map) rows.add(canonicalRow(castMap(map)));
            }
        }
        return new Expectation(ExpectationKind.EXACT_ROWS, null, null, rows);
    }

    private static Map<String, Object> canonicalRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((key, value) -> out.put(key, value instanceof Map<?, ?> map
                ? canonicalCell(castMap(map)) : scalarCell(String.valueOf(value))));
        return out;
    }

    private static Map<String, Object> canonicalCell(Map<String, Object> cell) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", cell.get("type") == null ? "literal" : String.valueOf(cell.get("type")));
        out.put("value", cell.get("value") == null ? "" : String.valueOf(cell.get("value")));
        if (cell.get("lang") != null) out.put("lang", String.valueOf(cell.get("lang")));
        if (cell.get("datatype") != null) out.put("datatype", String.valueOf(cell.get("datatype")));
        return out;
    }

    private static Map<String, Object> scalarCell(String value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", absoluteIri(value) ? "uri" : "literal");
        out.put("value", value);
        return out;
    }

    private static boolean absoluteIri(String value) {
        try {
            return new URI(value).isAbsolute();
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static Expectation expectationNonEmpty() {
        return new Expectation(ExpectationKind.NON_EMPTY, null, null, List.of());
    }

    private static String normalizeSeverity(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "error";
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "error" -> "error";
            case "warn", "warning" -> "warn";
            case "info" -> "info";
            default -> throw new AssetException("Unknown severity '" + raw
                    + "'. Use error, warn, or info.");
        };
    }

    private static String readBounded(Path path, long maximum, String oversized)
            throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if ((long) output.size() + read > maximum) throw new AssetException(oversized);
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static void failUnreadable(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new AssetException("Competency-question assets contain unreadable entries: "
                    + String.join("; ", errors));
        }
    }

    private static List<String> normalizedLines(String content) {
        return List.of(content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".rq")
                ? name.substring(0, name.length() - 3) : name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) out.add(castMap(map));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String nullableString(Object value) {
        if (value == null) return null;
        String string = String.valueOf(value);
        return string.isEmpty() ? null : string;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
