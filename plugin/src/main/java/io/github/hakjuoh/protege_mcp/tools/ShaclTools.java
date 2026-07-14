package io.github.hakjuoh.protege_mcp.tools;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/**
 * {@code shacl_validate}: validate the active ontology's RDF against a SHACL shapes graph using the embedded
 * Apache Jena SHACL engine. This is the constraint-validation leg the tool set previously lacked (only the
 * {@code run_qc_suite} {@code shacl} stage referenced it, reserved-and-skipped); {@code verify_ontology}'s
 * SPARQL invariants and {@code validate_governance} are the nearest prior substitutes.
 *
 * <p>The data graph is the same imports-closure RDF snapshot the SPARQL tools query (asserted by default, or
 * the reasoner's inferences when {@code include_inferred}), captured on the model thread and validated off it.
 * Shapes are supplied inline as Turtle or loaded from a LOCAL file — never fetched from the network, matching
 * the offline guarantee of {@code sparql_query}. The engine core is a pure static function over a serialised
 * data snapshot + a shapes graph, so it is unit-tested headless without Protégé.
 */
public final class ShaclTools {

    private ShaclTools() {
    }

    /** Base IRI used only to resolve any relative IRIs while parsing the inline/serialised Turtle. */
    private static final String DATA_BASE = "urn:protege-mcp:shacl-data";
    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final long MAX_POLICY_SHAPE_BYTES = 16_777_216L;
    private static final long MAX_POLICY_SHAPES_TOTAL_BYTES = 67_108_864L;

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("shacl_validate",
                "Validate the active ontology (its imports-closure RDF) against a SHACL shapes graph using "
                        + "the embedded Apache Jena SHACL engine. Provide the shapes inline as Turtle in "
                        + "'shapes', or a LOCAL file path in 'shapes_path' (remote fetch is disabled for "
                        + "offline safety). By default validation runs over the ASSERTED triples (like "
                        + "sparql_query); set include_inferred=true to first materialise the active "
                        + "reasoner's inferences (run_reasoner first). Reports 'conforms' plus, per "
                        + "validation result, the focus node, result path, value, severity "
                        + "(Violation/Warning/Info), source shape, constraint component and message. This is "
                        + "the SHACL counterpart to verify_ontology's SPARQL invariants; use it for "
                        + "shape-based data-quality checks (cardinality, datatype, value/class constraints).",
                Tools.schema()
                        .str("shapes", "SHACL shapes graph as Turtle text (inline). Either this or "
                                + "'shapes_path' is required.")
                        .str("shapes_path", "Path to a LOCAL SHACL shapes document (Turtle/RDF-XML/etc.; "
                                + "format inferred from the extension). Alternative to 'shapes'.")
                        .bool("include_inferred", "Validate over the active reasoner's inferred triples "
                                + "(default false; requires a classified reasoner — run_reasoner first).")
                        .integer("limit", "Max validation results to return (default 1000).")
                        .integer("timeout_ms", "Time budget in ms, applied to BOTH the data snapshot (on the "
                                + "model thread) and the SHACL validation itself (default 120000).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String shapesText = Tools.optString(a, "shapes");
                    String shapesPath = Tools.optString(a, "shapes_path");
                    boolean includeInferred = Tools.optBool(a, "include_inferred", false);
                    int limit = Tools.optInt(a, "limit", 1000);
                    int timeout = Tools.optInt(a, "timeout_ms", 120_000);
                    if (timeout <= 0) {
                        timeout = 120_000;
                    }
                    if (isBlank(shapesText) && isBlank(shapesPath)) {
                        return Tools.error("Provide a SHACL shapes graph: pass 'shapes' (inline Turtle) or "
                                + "'shapes_path' (a local file).");
                    }
                    final int snapTimeout = timeout;
                    // Capture the data snapshot on the model thread; validate it off the EDT.
                    byte[] dataTurtle = ctx.access().compute(mm ->
                            SparqlTools.toTurtleBytes(SparqlTools.snapshot(mm, includeInferred).ontology()),
                            snapTimeout);
                    return Tools.ok(validate(dataTurtle, shapesText, shapesPath, limit, snapTimeout));
                }));
    }

    /**
     * Validate a pre-serialised data snapshot (Turtle bytes) against a shapes graph (inline Turtle text or a
     * local file). Pure Jena, no Protégé types — safe off the model thread and unit-tested directly. Sets the
     * bundle's own classloader as the thread-context classloader while Jena runs, mirroring
     * {@link SparqlTools#executeTurtle} (bnd inlines Jena, so its {@code ServiceLoader} lookups need the
     * bundle TCCL).
     */
    static Map<String, Object> validate(byte[] dataTurtle, String shapesText, String shapesPath, int limit) {
        return validate(dataTurtle, shapesText, shapesPath, limit, 0L);
    }

    /**
     * Validate with an optional overall time budget. When {@code timeoutMs > 0} the whole parse+validate runs
     * in a bounded daemon worker so a pathological shapes/data graph cannot pin the calling MCP thread
     * indefinitely — it bounds the caller's wait (the worker is cancel-interrupted on timeout, mirroring how
     * the other tools' {@code timeout_ms} bounds the caller, not the on-thread work).
     */
    static Map<String, Object> validate(byte[] dataTurtle, String shapesText, String shapesPath, int limit,
            long timeoutMs) {
        Callable<Map<String, Object>> task = () -> validateOnThread(dataTurtle, shapesText, shapesPath, limit);
        return bounded(task, timeoutMs);
    }

    /** Validate one shared data snapshot against the union of policy-referenced local shape graphs. */
    static Map<String, Object> validate(byte[] dataTurtle, List<Path> shapesPaths, int limit,
            long timeoutMs) {
        if (shapesPaths == null || shapesPaths.isEmpty()) {
            throw new ToolArgException("No policy SHACL shape files were resolved.");
        }
        List<Path> copy = List.copyOf(shapesPaths);
        return bounded(() -> validateOnThread(dataTurtle, copy, limit), timeoutMs);
    }

    private static Map<String, Object> bounded(Callable<Map<String, Object>> task, long timeoutMs) {
        if (timeoutMs <= 0) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolArgException("SHACL validation failed: " + msg(e));
            }
        }
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "shacl-validate");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Map<String, Object>> f = exec.submit(task);
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                throw new ToolArgException("SHACL validation exceeded the " + timeoutMs + " ms budget — "
                        + "reduce the shapes/data size or raise timeout_ms.");
            } catch (ExecutionException ee) {
                Throwable c = ee.getCause();
                if (c instanceof ToolArgException) {
                    throw (ToolArgException) c;
                }
                if (c instanceof RuntimeException) {
                    throw (RuntimeException) c;
                }
                throw new ToolArgException("SHACL validation failed: " + msg(c));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ToolArgException("SHACL validation was interrupted.");
            }
        } finally {
            exec.shutdownNow();
        }
    }

    /** The actual validation body (TCCL-scoped Jena work); runs inline or on the bounded worker thread. */
    private static Map<String, Object> validateOnThread(byte[] dataTurtle, String shapesText,
            String shapesPath, int limit) {
        return validateOnThread(dataTurtle, loadShapesTask(shapesText, shapesPath), limit);
    }

    private static Map<String, Object> validateOnThread(byte[] dataTurtle, List<Path> shapesPaths,
            int limit) {
        return validateOnThread(dataTurtle, () -> loadShapes(shapesPaths), limit);
    }

    private static Map<String, Object> validateOnThread(byte[] dataTurtle,
            Callable<Model> shapesLoader, int limit) {
        ClassLoader previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ShaclTools.class.getClassLoader());
        try {
            JenaSystem.init();
            Graph dataGraph = parseTurtle(dataTurtle).getGraph();
            Model shapesModel;
            try {
                shapesModel = shapesLoader.call();
            } catch (ToolArgException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolArgException("Could not load SHACL shapes: " + msg(e));
            }
            ValidationReport report;
            try {
                report = ShaclValidator.get().validate(shapesModel.getGraph(), dataGraph);
            } catch (ToolArgException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ToolArgException("SHACL validation failed: " + msg(e));
            }
            return reportJson(report, limit);
        } finally {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
    }

    private static Callable<Model> loadShapesTask(String shapesText, String shapesPath) {
        return () -> loadShapes(shapesText, shapesPath);
    }

    /** Parse the data snapshot Turtle into a fresh in-memory model. */
    private static Model parseTurtle(byte[] turtle) {
        Model m = ModelFactory.createDefaultModel();
        RDFDataMgr.read(m, new ByteArrayInputStream(turtle), DATA_BASE, Lang.TURTLE);
        return m;
    }

    /** Load the shapes graph from inline Turtle (preferred) or a local file; never over the network. */
    private static Model loadShapes(String shapesText, String shapesPath) {
        Model shapes = ModelFactory.createDefaultModel();
        try {
            if (!isBlank(shapesText)) {
                RDFDataMgr.read(shapes, new ByteArrayInputStream(shapesText.getBytes(StandardCharsets.UTF_8)),
                        DATA_BASE, Lang.TURTLE);
            } else {
                // Open ONLY a real local file via the filesystem — never hand the raw string to Jena's
                // location resolver, which could otherwise dereference a file:/ftp:/http:/classpath: scheme.
                // Lang is inferred from the filename (default Turtle).
                Path path = localShapesPath(shapesPath);
                Lang lang = RDFLanguages.filenameToLang(path.getFileName().toString(), Lang.TURTLE);
                try (InputStream in = Files.newInputStream(path)) {
                    RDFDataMgr.read(shapes, in, DATA_BASE, lang);
                } catch (IOException io) {
                    throw new ToolArgException("Could not read shapes_path '" + shapesPath + "': " + msg(io));
                }
            }
        } catch (RiotException e) {
            throw new ToolArgException("Could not parse the SHACL shapes graph: " + msg(e));
        }
        return shapes;
    }

    /** Load and union already-confined policy paths; never invokes Jena's URL/location resolver. */
    private static Model loadShapes(List<Path> paths) {
        Model shapes = ModelFactory.createDefaultModel();
        long total = 0L;
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) {
                throw new ToolArgException("Policy SHACL path is not a readable file: " + path);
            }
            try {
                long size = Files.size(path);
                total = Math.addExact(total, size);
                if (size > MAX_POLICY_SHAPE_BYTES || total > MAX_POLICY_SHAPES_TOTAL_BYTES) {
                    throw new ToolArgException("Policy SHACL assets exceed the size limit (16 MiB per file, "
                            + "64 MiB total): " + path);
                }
            } catch (IOException e) {
                throw new ToolArgException("Could not inspect SHACL shapes file " + path + ": " + msg(e));
            } catch (ArithmeticException e) {
                throw new ToolArgException("Policy SHACL asset sizes overflowed the validation limit.");
            }
            Lang lang = RDFLanguages.filenameToLang(path.getFileName().toString(), Lang.TURTLE);
            try (InputStream in = Files.newInputStream(path)) {
                RDFDataMgr.read(shapes, in, DATA_BASE, lang);
            } catch (RiotException e) {
                throw new ToolArgException("Could not parse SHACL shapes file " + path + ": " + msg(e));
            } catch (IOException e) {
                throw new ToolArgException("Could not read SHACL shapes file " + path + ": " + msg(e));
            }
        }
        return shapes;
    }

    /** Resolve {@code shapesPath} to an existing regular local file, rejecting any URL scheme / non-file. */
    private static Path localShapesPath(String shapesPath) {
        String p = shapesPath.trim();
        if (p.toLowerCase(Locale.ROOT).contains("://")) {
            throw new ToolArgException("shapes_path must be a LOCAL file path — a URL scheme (e.g. file://, "
                    + "http://) is not allowed; remote SHACL fetch is disabled for offline safety. Paste the "
                    + "shapes into 'shapes' instead.");
        }
        Path path;
        try {
            path = Paths.get(p);
        } catch (InvalidPathException e) {
            throw new ToolArgException("shapes_path is not a valid file path: " + msg(e));
        }
        if (!Files.isRegularFile(path)) {
            throw new ToolArgException("shapes_path is not a readable local file: " + shapesPath);
        }
        return path;
    }

    /**
     * Render the validation report. Extracts each {@code sh:ValidationResult} by querying the report's RDF
     * (robust across jena-shacl minor API changes) rather than the {@code ReportEntry} accessors.
     */
    private static Map<String, Object> reportJson(ValidationReport report, int limit) {
        boolean conforms = report.conforms();
        Model reportModel = ModelFactory.createModelForGraph(report.getGraph());
        // Group by the validation-result node so a result with several sh:resultMessage values (e.g. a
        // multilingual sh:message) is ONE row, not N — otherwise the OPTIONAL join multiplies both the
        // result rows and the severity tallies. Every other sh:* term is single-valued per SHACL, so SAMPLE
        // is exact; the messages are folded with GROUP_CONCAT.
        String q = "PREFIX sh: <" + SH + ">\n"
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
                + "}\n"
                + "GROUP BY ?r";
        List<Map<String, Object>> results = new ArrayList<>();
        int violations = 0;
        int warnings = 0;
        int infos = 0;
        int cap = Math.max(0, limit);
        try (QueryExecution qe = QueryExecutionFactory.create(q, reportModel)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                String severity = localName(str(s.get("severity")));
                if ("Violation".equals(severity)) {
                    violations++;
                } else if ("Warning".equals(severity)) {
                    warnings++;
                } else if ("Info".equals(severity)) {
                    infos++;
                }
                if (results.size() < cap) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    putIfNotNull(row, "focus_node", str(s.get("focus")));
                    putIfNotNull(row, "result_path", str(s.get("path")));
                    putIfNotNull(row, "value", str(s.get("value")));
                    putIfNotNull(row, "severity", severity);
                    putIfNotNull(row, "constraint_component", localName(str(s.get("component"))));
                    putIfNotNull(row, "source_shape", str(s.get("shape")));
                    // GROUP_CONCAT yields "" (not unbound) when a result has no message — treat as absent.
                    String message = str(s.get("message"));
                    putIfNotNull(row, "message", message == null || message.isEmpty() ? null : message);
                    results.add(row);
                }
            }
        }
        int total = violations + warnings + infos;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conforms", conforms);
        m.put("total_results", total);
        m.put("violations", violations);
        m.put("warnings", warnings);
        m.put("infos", infos);
        String worst = violations > 0 ? "Violation" : warnings > 0 ? "Warning" : infos > 0 ? "Info" : null;
        if (worst != null) {
            m.put("worst_severity", worst);
        }
        m.put("results", results);
        if (total > results.size()) {
            m.put("truncated", total - results.size());
        }
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void putIfNotNull(Map<String, Object> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    /** The lexical form of a literal, the URI of a resource, else the node's string (blank node). */
    private static String str(RDFNode n) {
        if (n == null) {
            return null;
        }
        if (n.isLiteral()) {
            return n.asLiteral().getLexicalForm();
        }
        if (n.isURIResource()) {
            return n.asResource().getURI();
        }
        return n.toString();
    }

    /** The local name of an IRI (after the last '#' or '/'), or the input unchanged. */
    private static String localName(String iri) {
        if (iri == null) {
            return null;
        }
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }

    private static String msg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
