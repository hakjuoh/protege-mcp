package io.github.hakjuoh.protege_mcp.core.qc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.jena.graph.Graph;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.sys.JenaSystem;

/** Offline SHACL validation and QC-stage projection shared by plugin and headless adapters. */
public final class ShaclValidationService {

    private static final String DATA_BASE = "urn:protege-mcp:shacl-data";
    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final long MAX_POLICY_SHAPE_BYTES = 16_777_216L;
    private static final long MAX_POLICY_SHAPES_TOTAL_BYTES = 67_108_864L;

    private ShaclValidationService() {
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    /** Report plus the complete set of distinct gating identities; the digest retains raw row multiplicity. */
    public record Validation(Map<String, Object> report, Set<String> gatingIdentities) {
        public Validation {
            report = Collections.unmodifiableMap(new LinkedHashMap<>(report));
            gatingIdentities = Collections.unmodifiableSet(new LinkedHashSet<>(gatingIdentities));
        }
    }

    public record Result(QcStageExecution execution, Set<String> gatingIdentities) {
        public Result {
            gatingIdentities = Set.copyOf(gatingIdentities);
        }
    }

    public static Validation validate(byte[] dataTurtle, String shapesText, String shapesPath,
            int limit, long timeoutMs) {
        return bounded(() -> validateOnThread(dataTurtle,
                () -> loadShapes(shapesText, shapesPath), limit), timeoutMs);
    }

    public static Validation validate(byte[] dataTurtle, List<Path> shapesPaths,
            int limit, long timeoutMs) {
        if (shapesPaths == null || shapesPaths.isEmpty()) {
            throw new ValidationException("No policy SHACL shape files were resolved.");
        }
        List<Path> copy = List.copyOf(shapesPaths);
        return bounded(() -> validateOnThread(dataTurtle, () -> loadShapes(copy), limit), timeoutMs);
    }

    /** Interactive suite semantics: missing data is skipped because capture is optional. */
    public static Result evaluate(byte[] dataTurtle, String shapesText, String shapesPath,
            int limit, long timeoutMs) {
        if (isBlank(shapesText) && isBlank(shapesPath)) {
            return skipped("no SHACL shapes supplied — pass 'shacl_shapes' (inline Turtle) or "
                    + "'shacl_shapes_path' (a local file).");
        }
        if (dataTurtle == null) {
            return skipped("SHACL data snapshot was not captured.");
        }
        try {
            return stage(validate(dataTurtle, shapesText, shapesPath, limit, timeoutMs), null);
        } catch (RuntimeException error) {
            return errored(message(error));
        }
    }

    /** Strict project semantics: configured shapes require a captured data snapshot. */
    public static Result evaluatePolicy(byte[] dataTurtle, List<Path> shapesPaths,
            int limit, long timeoutMs) {
        if (shapesPaths == null || shapesPaths.isEmpty()) {
            return skipped("no policy SHACL shapes were resolved.");
        }
        if (dataTurtle == null) {
            return errored("SHACL data snapshot was not captured.");
        }
        try {
            return stage(validate(dataTurtle, shapesPaths, limit, timeoutMs),
                    shapesPaths.stream().map(Path::toString).toList());
        } catch (RuntimeException error) {
            return errored(message(error));
        }
    }

    private static Result stage(Validation validation, List<String> shapeFiles) {
        Map<String, Object> report = validation.report;
        int violations = number(report.get("violations"));
        int warnings = number(report.get("warnings"));
        int infos = number(report.get("infos"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("conforms", report.get("conforms"));
        summary.put("violations", violations);
        summary.put("warnings", warnings);
        summary.put("infos", report.get("infos"));
        summary.put("identity_digest", report.get("identity_digest"));
        if (shapeFiles != null) summary.put("shape_files", shapeFiles);
        QcStageVerdict verdict = violations > 0 ? QcStageVerdict.FAIL
                : warnings > 0 ? QcStageVerdict.WARNING
                : infos > 0 ? QcStageVerdict.INFO : QcStageVerdict.PASS;
        return new Result(new QcStageExecution("shacl", verdict, null, summary),
                validation.gatingIdentities);
    }

    private static Result skipped(String message) {
        return new Result(QcStageExecution.skipped("shacl", message), Set.of());
    }

    private static Result errored(String message) {
        return new Result(QcStageExecution.error("shacl", message, Map.of("error", message)),
                Set.of());
    }

    private static Validation bounded(Callable<Validation> task, long timeoutMs) {
        if (timeoutMs <= 0) {
            try {
                return task.call();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new ValidationException("SHACL validation failed: " + message(error));
            }
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shacl-validate");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Validation> future = executor.submit(task);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                future.cancel(true);
                throw new ValidationException("SHACL validation exceeded the " + timeoutMs
                        + " ms budget — reduce the shapes/data size or raise timeout_ms.");
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof ValidationException validation) throw validation;
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new ValidationException("SHACL validation failed: " + message(cause));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ValidationException("SHACL validation was interrupted.");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Validation validateOnThread(byte[] dataTurtle, Callable<Model> shapesLoader,
            int limit) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ShaclValidationService.class.getClassLoader());
        try {
            JenaSystem.init();
            Graph dataGraph = parseTurtle(dataTurtle).getGraph();
            final Model shapes;
            try {
                shapes = shapesLoader.call();
            } catch (ValidationException error) {
                throw error;
            } catch (Exception error) {
                throw new ValidationException("Could not load SHACL shapes: " + message(error));
            }
            final ValidationReport report;
            try {
                report = ShaclValidator.get().validate(shapes.getGraph(), dataGraph);
            } catch (ValidationException error) {
                throw error;
            } catch (RuntimeException error) {
                throw new ValidationException("SHACL validation failed: " + message(error));
            }
            return reportJson(report, limit);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Model parseTurtle(byte[] turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(turtle), DATA_BASE, Lang.TURTLE);
        return model;
    }

    private static Model loadShapes(String shapesText, String shapesPath) {
        Model shapes = ModelFactory.createDefaultModel();
        try {
            if (!isBlank(shapesText)) {
                RDFDataMgr.read(shapes, new ByteArrayInputStream(
                        shapesText.getBytes(StandardCharsets.UTF_8)), DATA_BASE, Lang.TURTLE);
            } else {
                Path path = localShapesPath(shapesPath);
                Lang lang = RDFLanguages.filenameToLang(path.getFileName().toString(), Lang.TURTLE);
                try (InputStream input = Files.newInputStream(path)) {
                    RDFDataMgr.read(shapes, input, DATA_BASE, lang);
                } catch (IOException error) {
                    throw new ValidationException("Could not read shapes_path '" + shapesPath
                            + "': " + message(error));
                }
            }
        } catch (RiotException error) {
            throw new ValidationException("Could not parse the SHACL shapes graph: "
                    + message(error));
        }
        return shapes;
    }

    private static Model loadShapes(List<Path> paths) {
        Model shapes = ModelFactory.createDefaultModel();
        long total = 0L;
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                throw new ValidationException("Policy SHACL path is not a readable file: " + path);
            }
            try {
                long size = Files.size(path);
                total = Math.addExact(total, size);
                if (size > MAX_POLICY_SHAPE_BYTES || total > MAX_POLICY_SHAPES_TOTAL_BYTES) {
                    throw new ValidationException("Policy SHACL assets exceed the size limit "
                            + "(16 MiB per file, 64 MiB total): " + path);
                }
            } catch (IOException error) {
                throw new ValidationException("Could not inspect SHACL shapes file " + path
                        + ": " + message(error));
            } catch (ArithmeticException error) {
                throw new ValidationException(
                        "Policy SHACL asset sizes overflowed the validation limit.");
            }
            Lang lang = RDFLanguages.filenameToLang(path.getFileName().toString(), Lang.TURTLE);
            try (InputStream input = Files.newInputStream(path)) {
                RDFDataMgr.read(shapes, input, DATA_BASE, lang);
            } catch (RiotException error) {
                throw new ValidationException("Could not parse SHACL shapes file " + path
                        + ": " + message(error));
            } catch (IOException error) {
                throw new ValidationException("Could not read SHACL shapes file " + path
                        + ": " + message(error));
            }
        }
        return shapes;
    }

    private static Path localShapesPath(String shapesPath) {
        String value = shapesPath.trim();
        if (value.toLowerCase(Locale.ROOT).contains("://")) {
            throw new ValidationException("shapes_path must be a LOCAL file path — a URL scheme "
                    + "(e.g. file://, http://) is not allowed; remote SHACL fetch is disabled for "
                    + "offline safety. Paste the shapes into 'shapes' instead.");
        }
        final Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException error) {
            throw new ValidationException("shapes_path is not a valid file path: " + message(error));
        }
        if (!Files.isRegularFile(path)) {
            throw new ValidationException("shapes_path is not a readable local file: " + shapesPath);
        }
        return path;
    }

    private static Validation reportJson(ValidationReport report, int limit) {
        boolean conforms = report.conforms();
        Model reportModel = ModelFactory.createModelForGraph(report.getGraph());
        String query = "PREFIX sh: <" + SH + ">\n"
                + "SELECT ?r (SAMPLE(?focus0) AS ?focus) (SAMPLE(?path0) AS ?path) "
                + "(SAMPLE(?value0) AS ?value) (SAMPLE(?severity0) AS ?severity) "
                + "(SAMPLE(?component0) AS ?component) (SAMPLE(?shape0) AS ?shape) "
                + "(GROUP_CONCAT(DISTINCT ?message0; SEPARATOR=\" | \") AS ?message) WHERE {\n"
                + "  ?r a sh:ValidationResult .\n"
                + "  OPTIONAL { ?r sh:focusNode ?focus0 }\n"
                + "  OPTIONAL { ?r sh:resultPath ?path0 }\n"
                + "  OPTIONAL { ?r sh:value ?value0 }\n"
                + "  OPTIONAL { ?r sh:resultSeverity ?severity0 }\n"
                + "  OPTIONAL { ?r sh:sourceConstraintComponent ?component0 }\n"
                + "  OPTIONAL { ?r sh:sourceShape ?shape0 }\n"
                + "  OPTIONAL { ?r sh:resultMessage ?message0 }\n"
                + "}\nGROUP BY ?r";
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> gatingIdentities = new ArrayList<>();
        int violations = 0;
        int warnings = 0;
        int infos = 0;
        int cap = Math.max(0, limit);
        try (QueryExecution execution = QueryExecutionFactory.create(query, reportModel)) {
            ResultSet rows = execution.execSelect();
            while (rows.hasNext()) {
                QuerySolution row = rows.next();
                String severity = localName(string(row.get("severity")));
                if ("Violation".equals(severity)) violations++;
                else if ("Warning".equals(severity)) warnings++;
                else if ("Info".equals(severity)) infos++;
                RDFNode shape = row.get("shape");
                Map<String, Object> identity = new LinkedHashMap<>();
                put(identity, "focus_node", string(row.get("focus")));
                put(identity, "result_path", string(row.get("path")));
                put(identity, "value", string(row.get("value")));
                put(identity, "severity", severity);
                put(identity, "constraint_component", localName(string(row.get("component"))));
                put(identity, "source_shape", string(shape));
                String message = string(row.get("message"));
                put(identity, "message", message == null || message.isEmpty() ? null : message);
                if ("Violation".equals(severity) || "Warning".equals(severity)) {
                    Map<String, Object> stable = new LinkedHashMap<>(identity);
                    if (shape != null && !shape.isURIResource()) stable.remove("source_shape");
                    gatingIdentities.add(QcFindingIdentity.object(stable));
                }
                if (results.size() < cap) results.add(identity);
            }
        }
        int total = violations + warnings + infos;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conforms", conforms);
        out.put("total_results", total);
        out.put("violations", violations);
        out.put("warnings", warnings);
        out.put("infos", infos);
        out.put("identity_digest", QcFindingIdentity.digest(gatingIdentities));
        String worst = violations > 0 ? "Violation"
                : warnings > 0 ? "Warning" : infos > 0 ? "Info" : null;
        if (worst != null) out.put("worst_severity", worst);
        out.put("results", results);
        if (total > results.size()) out.put("truncated", total - results.size());
        return new Validation(out, new LinkedHashSet<>(gatingIdentities));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    private static String string(RDFNode node) {
        if (node == null) return null;
        if (node.isLiteral()) return node.asLiteral().getLexicalForm();
        if (node.isURIResource()) return node.asResource().getURI();
        return node.toString();
    }

    private static String localName(String iri) {
        if (iri == null) return null;
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }

    private static int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
