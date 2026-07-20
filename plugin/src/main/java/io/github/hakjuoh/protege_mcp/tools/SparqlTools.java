package io.github.hakjuoh.protege_mcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.github.hakjuoh.protege_mcp.core.qc.RdfQueryService;

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

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("sparql_query",
                (ex, req) -> query(ctx, Tools.args(req)));
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
        return snapshot(active.getOntologyID(), active.getImportsClosure(), prefixMap(mm, active), reasoner,
                includeInferred);
    }

    /**
     * Protégé-free snapshot core over an explicitly captured closure. Supplying the closure instead of
     * consulting {@link OWLOntology#getImportsClosure()} is important for isolated validation: its private
     * manager deliberately does not replay import declarations that could trigger network I/O.
     */
    static Snapshot snapshot(OWLOntologyID activeId, Set<OWLOntology> closure,
            Map<String, String> prefixes, OWLReasoner reasoner, boolean includeInferred) {
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(
                    activeId, closure, prefixes, reasoner, includeInferred);
            return new Snapshot(snapshot.ontology(), snapshot.prefixes(), snapshot.note());
        } catch (RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
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
        try {
            return RdfQueryService.buildSnapshotOntology(priv, activeId, closure);
        } catch (RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    static OWLReasoner requireReasoner(OWLModelManager mm) {
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        ReasonerStatus status = rm.getReasonerStatus();
        if (status == ReasonerStatus.NO_REASONER_FACTORY_CHOSEN) {
            throw new ToolArgException("include_inferred=true but no reasoner is selected in Protégé "
                    + "(Reasoner menu). Select one and run_reasoner, or query without inferences.");
        }
        if (!status.isEnableStop()) {
            throw new ToolArgException("include_inferred=true but the reasoner has no current results — "
                    + "run_reasoner was not called, or the last classification failed and reset to the "
                    + "Null reasoner (check ~/.Protege/logs/protege.log). Run run_reasoner first, or query "
                    + "without inferences.");
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
        try {
            return RdfQueryService.execute(ontology, prefixes, query, limit, timeoutMs);
        } catch (RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    /**
     * Like {@link #execute} but over a pre-serialised Turtle snapshot (the {@link SparqlSnapshotCache}
     * fast path): each call parses the immutable bytes into a FRESH in-memory Jena model, so the cached
     * snapshot is never a mutable graph shared across the multi-threaded transport threads.
     */
    static Map<String, Object> executeTurtle(byte[] turtle, Map<String, String> prefixes,
            String query, int limit, long timeoutMs) {
        try {
            return RdfQueryService.executeTurtle(turtle, prefixes, query, limit, timeoutMs);
        } catch (RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    /**
     * Serialise the snapshot ontology to Turtle bytes (OWL API only — no Jena). The bytes are cached by
     * {@link SparqlSnapshotCache} and re-parsed into a fresh Jena model per query in {@link #executeTurtle}.
     */
    static byte[] toTurtleBytes(OWLOntology ontology) {
        try {
            return RdfQueryService.toTurtleBytes(ontology);
        } catch (RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
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
        return RdfQueryService.withPrefixes(query, prefixes);
    }

}
