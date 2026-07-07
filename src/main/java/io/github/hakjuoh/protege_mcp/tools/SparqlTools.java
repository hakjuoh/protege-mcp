package io.github.hakjuoh.protege_mcp.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryCancelledException;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sys.JenaSystem;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.util.InferredAxiomGenerator;
import org.semanticweb.owlapi.util.InferredClassAssertionAxiomGenerator;
import org.semanticweb.owlapi.util.InferredEquivalentClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredOntologyGenerator;
import org.semanticweb.owlapi.util.InferredPropertyAssertionGenerator;
import org.semanticweb.owlapi.util.InferredSubClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubDataPropertyAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubObjectPropertyAxiomGenerator;

/**
 * SPARQL querying over the active ontology, using an embedded Apache Jena ARQ engine.
 *
 * <p>The active ontology's imports closure is snapshotted into a private, throwaway OWL ontology on
 * the EDT (so Protégé's live model is never mutated and is not held for the whole query), optionally
 * with the active reasoner's inferences materialised into it, then serialised to RDF and loaded into
 * an in-memory Jena model that ARQ queries off the EDT. Read query forms only (SELECT/ASK/CONSTRUCT/
 * DESCRIBE); SPARQL UPDATE is rejected by the parser since edits go through the write tools, and the
 * SERVICE clause is rejected so a query can never reach the network.
 *
 * <p>{@link #execute} pins the thread-context classloader to this class's loader for the duration of
 * the query. This is not required for Jena 4.10.0's own init/query (its ServiceLoader uses the
 * defining class's loader, and query-time RIOT/ARQ make no TCCL calls) — RIOT/ARQ init is kept intact
 * by the hand-merged {@code META-INF/services/org.apache.jena.sys.JenaSubsystemLifecycle} resource
 * loaded via this bundle's classloader — but the pin is cheap defence-in-depth that mirrors how the
 * MCP server itself is built ({@code McpServerController.start}) and covers any future TCCL-first
 * ServiceLoader path (e.g. JSON-LD / jakarta.json).
 */
public final class SparqlTools {

    private SparqlTools() {
    }

    /** Base IRI used when re-parsing the snapshot's Turtle into Jena (only resolves any relative IRIs;
     *  the snapshot itself keeps the active ontology's real IRI — see {@link #buildSnapshotOntology}). */
    private static final String SNAPSHOT_IRI = "urn:protege-mcp:sparql-snapshot";

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    /**
     * Cap on {@code |individuals| × |properties|} above which the (potentially quadratic) inferred
     * property-assertion generator is skipped so {@code include_inferred} cannot freeze the UI on a
     * large ABox. The cheaper class/type/sub-property inferences are still materialised, and the skip
     * is reported back to the caller in a {@code note}.
     */
    private static final long MAX_INFERRED_PROPERTY_PRODUCT = 250_000L;

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("sparql_query",
                "Run a SPARQL 1.1 query over the active ontology and its imports closure, using an "
                        + "embedded Jena ARQ engine. Supports the read query forms SELECT, ASK, CONSTRUCT "
                        + "and DESCRIBE; SPARQL UPDATE and SERVICE are rejected (edits go through the "
                        + "write tools; no network access). Prefixes declared in the ontology (plus "
                        + "rdf/rdfs/owl/xsd) are auto-prepended, so queries can use them without their "
                        + "own PREFIX lines. By default the query sees the ASSERTED triples (like "
                        + "Protégé's SPARQL Query tab); set include_inferred=true to first materialise "
                        + "the active reasoner's inferences (run_reasoner first — this runs on the UI "
                        + "thread and can be slow on a large ABox). 'limit' caps the rows (SELECT) or "
                        + "triples (CONSTRUCT/DESCRIBE) returned (default 1000).",
                Tools.schema()
                        .strReq("query", "A SPARQL 1.1 query (SELECT, ASK, CONSTRUCT, or DESCRIBE).")
                        .bool("include_inferred", "Materialise the active reasoner's inferred axioms "
                                + "before querying (default false; requires a classified reasoner; runs "
                                + "on the UI thread).")
                        .integer("limit", "Max rows (SELECT) or triples (CONSTRUCT/DESCRIBE) to return "
                                + "(default 1000).")
                        .integer("timeout_ms", "Overall time budget in ms, covering both the snapshot/"
                                + "inference step and the query evaluation (default 120000).")
                        .build(),
                (ex, req) -> Tools.guard(() -> query(ctx, Tools.args(req))));
    }

    /**
     * Run the {@code sparql_query} tool over the active ontology's cached RDF snapshot. Extracted from the
     * handler lambda so the whole cache-aware path (miss build, hit reuse, prefix-staleness revalidation)
     * is driven end-to-end in a headless test. Reads only {@link ToolContext#access()} /
     * {@link ToolContext#sparqlCache()} — never the controller — so it runs without a live server.
     */
    static Map<String, Object> queryResult(ToolContext ctx, Map<String, Object> a) {
        String queryText = Tools.reqString(a, "query");
        boolean inferred = Tools.optBool(a, "include_inferred", false);
        int limit = Tools.optInt(a, "limit", 1000);
        int timeout = Tools.optInt(a, "timeout_ms", 120_000);
        if (limit <= 0) {
            limit = 1000;
        }
        if (timeout <= 0) {
            timeout = 120_000;
        }
        final int snapshotTimeout = timeout;
        SparqlSnapshotCache cache = ctx.sparqlCache();
        // Fast path: reuse the cached Turtle snapshot when the model is unchanged (its stamp still equals
        // the live version). A GUI-side prefix edit (Active ontology ▸ Prefixes) mutates the document
        // format directly and fires NO listener, so on a hit re-read the live prefix map — a cheap EDT
        // read, far cheaper than the closure copy + serialisation a rebuild does — and drop the entry if
        // the prefixes changed, so a query using a just-added/redefined prefix is never served the stale
        // map. (An MCP set_prefix already invalidates the cache directly; this covers the GUI path.)
        SparqlSnapshotCache.Entry entry = cache.get(inferred);
        if (entry != null) {
            final SparqlSnapshotCache.Entry hit = entry;
            boolean prefixesChanged = ctx.access().compute(
                    mm -> !prefixMap(mm, mm.getActiveOntology()).equals(hit.prefixes), snapshotTimeout);
            if (prefixesChanged) {
                cache.invalidate();
                entry = null;
            }
        }
        if (entry == null) {
            // Miss: build + stamp the snapshot inside ONE EDT task, so no change can interleave between
            // reading the version and taking the snapshot (listeners fire on the EDT).
            entry = ctx.access().compute(mm -> {
                cache.installIfNeeded(mm);
                long v = cache.version();
                Snapshot snap = snapshot(mm, inferred);
                byte[] turtle = toTurtleBytes(snap.ontology());
                return cache.store(inferred, v, turtle, snap.prefixes(), snap.note());
            }, snapshotTimeout);
        }
        Map<String, Object> result =
                executeTurtle(entry.turtle, entry.prefixes, queryText, limit, timeout);
        if (entry.note != null) {
            result.put("note", entry.note);
        }
        return result;
    }

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult query(ToolContext ctx,
            Map<String, Object> a) {
        return Tools.ok(queryResult(ctx, a));
    }

    /** The imports-closure RDF snapshot, the prefixes to expose to queries, and an optional note. */
    record Snapshot(OWLOntology ontology, Map<String, String> prefixes, String note) {
    }

    /**
     * Snapshot the active ontology's imports-closure axioms into a private ontology (optionally with
     * the active reasoner's inferences materialised), and capture the prefixes to expose to queries.
     * Runs on the EDT via {@link OntologyAccess}; the returned ontology lives in a private manager and
     * is safe to read off the EDT.
     */
    static Snapshot snapshot(OWLModelManager mm, boolean includeInferred) {
        // requireReasoner touches Protégé's reasoner manager, so it stays on this Protégé-facing path;
        // the actual snapshot/inference work is delegated to the reasoner-injected overload below so it
        // can be exercised headless with an OWL API StructuralReasoner.
        OWLReasoner reasoner = includeInferred ? requireReasoner(mm) : null;
        return snapshot(mm, reasoner, includeInferred);
    }

    /** Reasoner-injected core of {@link #snapshot(OWLModelManager, boolean)} (headless-testable). */
    static Snapshot snapshot(OWLModelManager mm, OWLReasoner reasoner, boolean includeInferred) {
        OWLOntology active = mm.getActiveOntology();
        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = buildSnapshotOntology(priv, active.getOntologyID(), active.getImportsClosure());
        String note = null;
        if (includeInferred) {
            long individuals = iso.getIndividualsInSignature().size();
            long properties = iso.getObjectPropertiesInSignature().size()
                    + iso.getDataPropertiesInSignature().size();
            boolean withPropertyAssertions = individuals * properties <= MAX_INFERRED_PROPERTY_PRODUCT;
            if (!withPropertyAssertions) {
                note = "Inferred property assertions were skipped because the ABox is large ("
                        + individuals + " individuals × " + properties + " properties); inferred "
                        + "class, type and sub-property axioms are still included. Query without "
                        + "include_inferred, or use get_inferred_superclasses / execute_dl_query for "
                        + "targeted property inferences.";
            }
            new InferredOntologyGenerator(reasoner, inferredGenerators(withPropertyAssertions))
                    .fillOntology(priv.getOWLDataFactory(), iso);
        }
        return new Snapshot(iso, prefixMap(mm, active), note);
    }

    /**
     * Merge an imports closure into one private throwaway ontology for querying. The snapshot keeps
     * the active ontology's real {@link OWLOntologyID} (so SPARQL sees the true ontology IRI/version,
     * not a synthetic one) and copies every closure ontology's <em>ontology-level annotations</em> —
     * which {@code getAxioms()} omits — so header metadata (versionInfo, titles, …) is queryable, not
     * just the entity axioms. Static and Protégé-free so it can be unit-tested against plain ontologies.
     */
    static OWLOntology buildSnapshotOntology(OWLOntologyManager priv, OWLOntologyID activeId,
            Set<OWLOntology> closure) {
        OWLOntology iso;
        try {
            iso = priv.createOntology(activeId != null && !activeId.isAnonymous()
                    ? activeId : new OWLOntologyID());
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not prepare a SPARQL workspace: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        for (OWLOntology o : closure) {
            priv.addAxioms(iso, o.getAxioms());
            for (OWLAnnotation ann : o.getAnnotations()) {
                priv.applyChange(new AddOntologyAnnotation(iso, ann));
            }
        }
        return iso;
    }

    /**
     * The inferred-axiom generators materialised when {@code include_inferred} is set: the class
     * hierarchy (subclass / equivalent), individual types, and the property hierarchies — the axioms
     * that turn into the {@code rdf:type}, {@code rdfs:subClassOf} and {@code rdfs:subPropertyOf}
     * triples a SPARQL query most often expects to reflect reasoning. Inferred property assertions are
     * added only when {@code withPropertyAssertions} is set, since that generator is the one quadratic
     * outlier ({@code O(individuals × properties)} reasoner calls).
     */
    private static List<InferredAxiomGenerator<? extends OWLAxiom>> inferredGenerators(
            boolean withPropertyAssertions) {
        List<InferredAxiomGenerator<? extends OWLAxiom>> gens = new ArrayList<>();
        gens.add(new InferredSubClassAxiomGenerator());
        gens.add(new InferredEquivalentClassAxiomGenerator());
        gens.add(new InferredClassAssertionAxiomGenerator());
        gens.add(new InferredSubObjectPropertyAxiomGenerator());
        gens.add(new InferredSubDataPropertyAxiomGenerator());
        if (withPropertyAssertions) {
            gens.add(new InferredPropertyAssertionGenerator());
        }
        return gens;
    }

    private static OWLReasoner requireReasoner(OWLModelManager mm) {
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        ReasonerStatus status = rm.getReasonerStatus();
        if (status == ReasonerStatus.NO_REASONER_FACTORY_CHOSEN) {
            throw new ToolArgException("include_inferred=true but no reasoner is selected in Protégé "
                    + "(Reasoner menu). Select one and run_reasoner, or query without inferences.");
        }
        if (!status.isEnableStop()) {
            throw new ToolArgException("include_inferred=true but the reasoner has not produced results "
                    + "yet — call run_reasoner first, or query without inferences.");
        }
        return mm.getReasoner();
    }

    /** The prefixes to expose to queries: the active ontology's, plus the four standard ones. */
    static Map<String, String> prefixMap(OWLModelManager mm, OWLOntology active) {
        Map<String, String> out = new LinkedHashMap<>();
        OWLDocumentFormat fmt = mm.getOWLOntologyManager().getOntologyFormat(active);
        if (fmt != null && fmt.isPrefixOWLOntologyFormat()) {
            out.putAll(fmt.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap());
        }
        out.putIfAbsent("rdf:", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        out.putIfAbsent("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        out.putIfAbsent("owl:", "http://www.w3.org/2002/07/owl#");
        out.putIfAbsent("xsd:", XSD_STRING.substring(0, XSD_STRING.lastIndexOf('#') + 1));
        return out;
    }

    // ---------------------------------------------------------------- query execution (testable)

    /**
     * Execute {@code query} over {@code ontology}'s RDF rendering and return the result as a JSON map.
     * {@code prefixes} (name→namespace, names ending in {@code ':'}) are prepended to the query so its
     * own PREFIX lines are optional. {@code limit} caps SELECT rows / CONSTRUCT|DESCRIBE triples;
     * {@code timeoutMs} (when &gt; 0) bounds the ARQ evaluation. SPARQL SERVICE is rejected so the
     * query cannot reach the network.
     *
     * <p>Static and free of Protégé types so it can be unit-tested against a plain OWL ontology.
     */
    static Map<String, Object> execute(OWLOntology ontology, Map<String, String> prefixes,
            String query, int limit, long timeoutMs) {
        return executeTurtle(toTurtleBytes(ontology), prefixes, query, limit, timeoutMs);
    }

    /**
     * Like {@link #execute} but over a pre-serialised Turtle snapshot (the {@link SparqlSnapshotCache}
     * fast path): each call parses the immutable bytes into a FRESH in-memory Jena model, so the cached
     * snapshot is never a mutable graph shared across the multi-threaded transport threads.
     */
    static Map<String, Object> executeTurtle(byte[] turtle, Map<String, String> prefixes,
            String query, int limit, long timeoutMs) {
        ClassLoader previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(SparqlTools.class.getClassLoader());
        try {
            JenaSystem.init();
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, new ByteArrayInputStream(turtle), SNAPSHOT_IRI, Lang.TURTLE);
            Query q = parse(withPrefixes(query, prefixes));
            rejectService(q);
            try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
                // Defence-in-depth: even if a SERVICE op slipped past rejectService, ARQ now refuses
                // to execute it (throws QueryDeniedException) instead of reaching the network.
                qe.getContext().set(ARQ.httpServiceAllowed, false);
                if (timeoutMs > 0) {
                    qe.setTimeout(timeoutMs, TimeUnit.MILLISECONDS, timeoutMs, TimeUnit.MILLISECONDS);
                }
                try {
                    if (q.isSelectType()) {
                        return selectJson(qe, limit);
                    }
                    if (q.isAskType()) {
                        return Tools.json().put("query_type", "ASK").put("boolean", qe.execAsk()).map();
                    }
                    if (q.isConstructType()) {
                        return graphJson("CONSTRUCT", qe.execConstruct(), model, limit);
                    }
                    if (q.isDescribeType()) {
                        return graphJson("DESCRIBE", qe.execDescribe(), model, limit);
                    }
                    throw new ToolArgException("Unsupported query form. Use SELECT, ASK, CONSTRUCT, or "
                            + "DESCRIBE (SPARQL UPDATE is not allowed — use the write tools to edit).");
                } catch (QueryCancelledException e) {
                    throw new ToolArgException("SPARQL query exceeded the " + timeoutMs + " ms time "
                            + "budget — add a LIMIT, constrain the pattern, or raise timeout_ms.");
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
    }

    private static Query parse(String query) {
        try {
            return QueryFactory.create(query);
        } catch (QueryParseException e) {
            // Covers both syntax errors and ARQ validation errors (e.g. a bad GROUP BY projection),
            // so lead with the engine's own message and state the read-only constraint as a neutral
            // capability note rather than implying the query failed because it was an UPDATE.
            throw new ToolArgException("SPARQL query error: " + e.getMessage()
                    + " (sparql_query accepts only read queries: SELECT, ASK, CONSTRUCT, or DESCRIBE.)");
        }
    }

    /** Reject a query that uses SERVICE so it can never reach a remote endpoint. */
    private static void rejectService(Query q) {
        final boolean[] hasService = {false};
        try {
            Op op = Algebra.compile(q);
            OpWalker.walk(op, new OpVisitorBase() {
                @Override
                public void visit(OpService opService) {
                    hasService[0] = true;
                }
            });
        } catch (RuntimeException ignored) {
            // If algebra compilation fails for an exotic query, the httpServiceAllowed=false context
            // flag set in execute() is the load-bearing guard; nothing reaches the network either way.
        }
        if (hasService[0]) {
            throw new ToolArgException("SPARQL SERVICE is not allowed — sparql_query runs only over the "
                    + "local ontology and never reaches the network.");
        }
    }

    /**
     * Serialise the snapshot ontology to Turtle bytes (OWL API only — no Jena). The bytes are cached by
     * {@link SparqlSnapshotCache} and re-parsed into a fresh Jena model per query in {@link #executeTurtle}.
     */
    static byte[] toTurtleBytes(OWLOntology ontology) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ontology.getOWLOntologyManager().saveOntology(ontology, new TurtleDocumentFormat(), out);
        } catch (OWLOntologyStorageException e) {
            throw new ToolArgException("Could not render the ontology to RDF for SPARQL: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        return out.toByteArray();
    }

    /**
     * Prepend a {@code PREFIX} declaration for every supplied namespace. Prepended declarations come
     * before the query body, so a query that declares the same prefix name itself overrides the
     * prepended one (ARQ takes the last declaration), and a duplicate same-namespace declaration is
     * harmless. This is deliberately additive rather than trying to detect existing declarations — a
     * textual scan can't reliably tell a real {@code PREFIX} from one inside a comment or string
     * literal, and SPARQL prefix names are case-sensitive.
     */
    static String withPrefixes(String query, Map<String, String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return query;
        }
        StringBuilder prologue = new StringBuilder();
        for (Map.Entry<String, String> e : prefixes.entrySet()) {
            String name = e.getKey();
            String ns = e.getValue();
            if (name == null || ns == null || ns.isEmpty()) {
                continue;
            }
            prologue.append("PREFIX ").append(name).append(" <").append(ns).append(">\n");
        }
        return prologue.length() == 0 ? query : prologue + query;
    }

    private static Map<String, Object> selectJson(QueryExecution qe, int limit) {
        ResultSet rs = qe.execSelect();
        List<String> vars = rs.getResultVars();
        List<Map<String, Object>> bindings = new ArrayList<>();
        boolean truncated = false;
        while (rs.hasNext()) {
            if (bindings.size() >= limit) {
                truncated = true;
                break;
            }
            QuerySolution sol = rs.next();
            Map<String, Object> row = new LinkedHashMap<>();
            for (String var : vars) {
                RDFNode node = sol.get(var);
                if (node != null) {
                    row.put(var, nodeJson(node));
                }
            }
            bindings.add(row);
        }
        // count is the number of rows returned (capped at limit); a SELECT result is streamed, so the
        // true total is not enumerated. 'truncated' signals more rows exist — use LIMIT for an exact total.
        Tools.Json json = Tools.json()
                .put("query_type", "SELECT")
                .put("vars", vars)
                .put("count", bindings.size())
                .put("bindings", bindings);
        if (truncated) {
            json.put("truncated", true);
        }
        return json.map();
    }

    private static Map<String, Object> nodeJson(RDFNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node.isURIResource()) {
            m.put("type", "uri");
            m.put("value", node.asResource().getURI());
        } else if (node.isAnon()) {
            m.put("type", "bnode");
            m.put("value", node.asResource().getId().getLabelString());
        } else {
            Literal lit = node.asLiteral();
            m.put("type", "literal");
            m.put("value", lit.getLexicalForm());
            String lang = lit.getLanguage();
            if (lang != null && !lang.isEmpty()) {
                m.put("lang", lang);
            } else {
                String datatype = lit.getDatatypeURI();
                if (datatype != null && !datatype.equals(XSD_STRING)) {
                    m.put("datatype", datatype);
                }
            }
        }
        return m;
    }

    /**
     * Serialise a CONSTRUCT/DESCRIBE result graph to Turtle, capped at {@code limit} triples. When the
     * graph is over {@code limit}, statements are sorted (subject, predicate, object) before the cut so
     * the shown subset is deterministic run-to-run.
     */
    private static Map<String, Object> graphJson(String type, Model result, Model source, int limit) {
        result.setNsPrefixes(source.getNsPrefixMap());
        long total = result.size();
        Model toWrite = result;
        boolean truncated = false;
        if (total > limit) {
            List<Statement> statements = result.listStatements().toList();
            statements.sort(Comparator.comparing(st -> st.asTriple().toString()));
            toWrite = ModelFactory.createDefaultModel();
            toWrite.setNsPrefixes(result.getNsPrefixMap());
            for (int i = 0; i < limit && i < statements.size(); i++) {
                toWrite.add(statements.get(i));
            }
            truncated = true;
        }
        StringWriter sw = new StringWriter();
        toWrite.write(sw, "TURTLE");
        Tools.Json json = Tools.json()
                .put("query_type", type)
                .put("count", total)
                .put("turtle", sw.toString());
        if (truncated) {
            json.put("truncated", true).put("shown", limit);
        }
        return json.map();
    }
}
