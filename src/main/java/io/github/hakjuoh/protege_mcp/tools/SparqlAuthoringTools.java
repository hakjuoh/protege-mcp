package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpPath;
import org.apache.jena.sparql.algebra.op.OpService;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.path.P_NegPropSet;
import org.apache.jena.sparql.path.P_Path0;
import org.apache.jena.sparql.path.P_Path1;
import org.apache.jena.sparql.path.P_Path2;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.update.UpdateFactory;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.search.EntitySearcher;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * SPARQL <em>authoring</em> helpers that complement {@link SparqlTools#specs sparql_query} (which only
 * <em>executes</em> a finished query). {@code sparql_schema} surfaces the queryable vocabulary —
 * prefixes, classes, properties (with domains/ranges), individuals, datatypes, all with ready-to-paste
 * CURIEs — plus example queries grounded in the ontology's own terms. {@code sparql_validate} parses a
 * draft query (without running it) and reports syntax errors, whether sparql_query would accept it
 * (read form, no SERVICE), and any IRIs used in its graph patterns that are not in the ontology
 * signature (a typo / wrong-vocabulary detector). Both reuse {@link SparqlTools}'s snapshot and prefix
 * machinery so they describe and validate exactly what {@code sparql_query} would run.
 */
public final class SparqlAuthoringTools {

    private SparqlAuthoringTools() {
    }

    static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";

    /** Max domain/range/type refs listed per property/individual (kept short for readability). */
    private static final int MEMBER_CAP = 10;

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("sparql_schema",
                "Discover the queryable vocabulary for AUTHORING a SPARQL query over the active ontology "
                        + "(its imports closure by default — the same graph sparql_query sees). Returns the "
                        + "prefix map (plus a ready-to-paste PREFIX block), and capped, sorted lists of "
                        + "classes, object/data properties (with their domains and ranges), named "
                        + "individuals and datatypes — each with a CURIE (e.g. iof:MaterialArtifact) and "
                        + "full IRI — and a set of ready-to-run example queries built from the ontology's "
                        + "own terms. Use 'keyword' to focus on a sub-topic. sparql_query auto-prepends "
                        + "these prefixes, so you can use the CURIEs directly. This is asserted structure; "
                        + "for reasoner-derived triples query with include_inferred=true.",
                Tools.schema()
                        .str("keyword", "Only include classes/properties/individuals whose label or IRI "
                                + "contains this text (case-insensitive). Use to focus on a sub-topic.")
                        .integer("limit", "Max items per list (default 100).")
                        .bool("include_imports", "Include terms from the imports closure (default true; "
                                + "sparql_query queries the closure).")
                        .bool("include_individuals", "Include a sample of named individuals (default true).")
                        .bool("include_examples", "Include example queries grounded in the ontology's own "
                                + "terms (default true).")
                        .integer("timeout_ms", "Time budget in ms for gathering the vocabulary on the UI "
                                + "thread (default 120000; raise for very large imports closures).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String keyword = Tools.optString(a, "keyword");
                    int limit = Tools.optInt(a, "limit", 100);
                    if (limit <= 0) {
                        limit = 100;
                    }
                    boolean includeImports = Tools.optBool(a, "include_imports", true);
                    boolean includeIndividuals = Tools.optBool(a, "include_individuals", true);
                    boolean includeExamples = Tools.optBool(a, "include_examples", true);
                    int timeout = Tools.optInt(a, "timeout_ms", 120_000);
                    if (timeout <= 0) {
                        timeout = 120_000;
                    }
                    final int cap = limit;
                    return ctx.access().compute(mm ->
                            schema(mm, keyword, cap, includeImports, includeIndividuals, includeExamples),
                            timeout);
                }));

        tools.tool("sparql_validate",
                "Check a SPARQL query BEFORE running it (it is parsed, not executed, unless dry_run=true). "
                        + "Reports whether it parses, the query form, the projected variables, and whether "
                        + "sparql_query would accept it ('executable' — a read query with no SERVICE). The "
                        + "ontology's prefixes are auto-prepended just as sparql_query does, so a query that "
                        + "uses declared CURIEs validates without its own PREFIX lines. 'unknown_terms' lists "
                        + "IRIs used in the query (graph patterns, property paths, VALUES, CONSTRUCT "
                        + "template, DESCRIBE targets) that are NOT declared in the ontology — usually a "
                        + "typo or a term from another vocabulary; catch these before running. A "
                        + "syntax error is reported as valid=false with the engine's message, not as a tool "
                        + "error. Set dry_run=true to also run it with a small LIMIT and return a sample.",
                Tools.schema()
                        .strReq("query", "The SPARQL query to check.")
                        .bool("dry_run", "Also execute the query with a small LIMIT to confirm it runs and "
                                + "return a sample of results (default false).")
                        .integer("sample_limit", "Rows/triples to return when dry_run=true (default 5).")
                        .integer("timeout_ms", "Time budget for the dry run in ms (default 120000).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String query = Tools.reqString(a, "query");
                    boolean dryRun = Tools.optBool(a, "dry_run", false);
                    int sampleLimit = Tools.optInt(a, "sample_limit", 5);
                    if (sampleLimit <= 0) {
                        sampleLimit = 5;
                    }
                    int timeout = Tools.optInt(a, "timeout_ms", 120_000);
                    if (timeout <= 0) {
                        timeout = 120_000;
                    }
                    return validate(ctx, query, dryRun, sampleLimit, timeout);
                }));
    }

    // ================================================================ sparql_schema

    private static CallToolResult schema(OWLModelManager mm, String keyword, int limit,
            boolean includeImports, boolean includeIndividuals, boolean includeExamples) {
        OWLOntology active = mm.getActiveOntology();
        Set<OWLOntology> scope = includeImports
                ? active.getImportsClosure()
                : Collections.singleton(active);
        Map<String, String> prefixes = SparqlTools.prefixMap(mm, active);

        Set<OWLClass> classes = new LinkedHashSet<>();
        Set<OWLObjectProperty> objectProperties = new LinkedHashSet<>();
        Set<OWLDataProperty> dataProperties = new LinkedHashSet<>();
        Set<OWLNamedIndividual> individuals = new LinkedHashSet<>();
        Set<OWLDatatype> datatypes = new LinkedHashSet<>();
        for (OWLOntology o : scope) {
            classes.addAll(o.getClassesInSignature());
            objectProperties.addAll(o.getObjectPropertiesInSignature());
            dataProperties.addAll(o.getDataPropertiesInSignature());
            individuals.addAll(o.getIndividualsInSignature());
            datatypes.addAll(o.getDatatypesInSignature());
        }

        // Render each entity's display name at most once and reuse it for both filtering and the sort
        // key — Comparator.comparing does not memoise, so without this getRendering would be called
        // O(n log n) times on the (possibly huge) imports-closure signature, all on the EDT.
        Map<OWLEntity, String> displayCache = new HashMap<>();
        Function<OWLEntity, String> displayLower =
                e -> displayCache.computeIfAbsent(e, x -> mm.getRendering(x).toLowerCase());
        String kw = keyword == null ? null : keyword.toLowerCase();
        Predicate<OWLEntity> match = e -> kw == null
                || displayLower.apply(e).contains(kw)
                || e.getIRI().toString().toLowerCase().contains(kw);
        Comparator<OWLEntity> byDisplay = Comparator.comparing(displayLower)
                .thenComparing(e -> e.getIRI().toString());

        Map<String, Object> truncated = new LinkedHashMap<>();

        List<OWLClass> classList = filterSort(classes, match, byDisplay, c -> !c.isBuiltIn());
        ListResult classRes = simpleRefs(mm, classList, limit, prefixes);
        ListResult objRes = propertyRefs(mm, filterSort(objectProperties, match, byDisplay, p -> true),
                scope, limit, prefixes, true);
        ListResult dataRes = propertyRefs(mm, filterSort(dataProperties, match, byDisplay, p -> true),
                scope, limit, prefixes, false);
        ListResult dtRes = simpleRefs(mm, filterSort(datatypes, match, byDisplay, d -> !d.isBuiltIn()),
                limit, prefixes);
        recordTruncation(truncated, "classes", classRes);
        recordTruncation(truncated, "object_properties", objRes);
        recordTruncation(truncated, "data_properties", dataRes);
        recordTruncation(truncated, "datatypes", dtRes);

        ListResult indRes = null;
        if (includeIndividuals) {
            indRes = individualRefs(mm, filterSort(individuals, match, byDisplay, i -> true),
                    scope, limit, prefixes);
            recordTruncation(truncated, "individuals", indRes);
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("classes", classes.size());
        counts.put("object_properties", objectProperties.size());
        counts.put("data_properties", dataProperties.size());
        counts.put("individuals", individuals.size());
        counts.put("datatypes", datatypes.size());

        // 'counts' is the full signature; when a keyword filters the lists, also report how many terms
        // matched it (so shown + truncated == matched <= counts is self-evident, not two denominators).
        Map<String, Object> matched = null;
        if (keyword != null) {
            matched = new LinkedHashMap<>();
            matched.put("classes", classRes.total);
            matched.put("object_properties", objRes.total);
            matched.put("data_properties", dataRes.total);
            matched.put("datatypes", dtRes.total);
            if (indRes != null) {
                matched.put("individuals", indRes.total);
            }
        }

        OWLOntologyID id = active.getOntologyID();
        Map<String, Object> activeJson = new LinkedHashMap<>();
        activeJson.put("ontology_iri",
                id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : null);
        activeJson.put("version_iri",
                id.getVersionIRI().isPresent() ? id.getVersionIRI().get().toString() : null);
        activeJson.put("scope", includeImports ? "imports_closure" : "active");

        Tools.Json json = Tools.json()
                .put("active_ontology", activeJson)
                .put("counts", counts)
                .putIfNotNull("matched", matched)
                .put("prefixes", prefixes)
                .put("prefix_lines", SparqlTools.withPrefixes("", prefixes))
                .put("classes", classRes.items)
                .put("object_properties", objRes.items)
                .put("data_properties", dataRes.items);
        if (indRes != null) {
            json.put("individuals", indRes.items);
        }
        json.put("datatypes", dtRes.items);
        if (includeExamples) {
            json.put("examples", exampleQueries(
                    firstRef(classRes), firstRef(objRes), firstRef(dataRes)));
        }
        json.putIfNotNull("keyword", keyword);
        json.putIfNotNull("truncated", truncated.isEmpty() ? null : truncated);
        json.put("note", "Vocabulary for composing a SPARQL query. Use a CURIE directly (sparql_query "
                + "auto-prepends these prefixes) or a full <IRI> when no curie is shown. 'counts' is the "
                + "full signature; 'matched' (present when a keyword is given) is how many matched the "
                + "keyword. Asserted structure only — query with include_inferred=true for reasoner-derived "
                + "triples. Validate a draft with sparql_validate before running it.");
        return json.result();
    }

    /** Filter by {@code keep} + {@code match}, then sort. */
    private static <T extends OWLEntity> List<T> filterSort(Collection<T> entities,
            Predicate<OWLEntity> match, Comparator<OWLEntity> cmp, Predicate<T> keep) {
        List<T> out = new ArrayList<>();
        for (T e : entities) {
            if (keep.test(e) && match.test(e)) {
                out.add(e);
            }
        }
        out.sort(cmp);
        return out;
    }

    /** A capped list of {@code {curie?, iri, display}} refs plus the pre-cap total. */
    private static ListResult simpleRefs(OWLModelManager mm, List<? extends OWLEntity> entities,
            int limit, Map<String, String> prefixes) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < entities.size() && i < limit; i++) {
            items.add(ref(mm, entities.get(i), prefixes));
        }
        return new ListResult(items, entities.size());
    }

    private static ListResult propertyRefs(OWLModelManager mm, List<? extends OWLEntity> properties,
            Set<OWLOntology> scope, int limit, Map<String, String> prefixes, boolean object) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < properties.size() && i < limit; i++) {
            OWLEntity p = properties.get(i);
            Map<String, Object> m = ref(mm, p, prefixes);
            if (object) {
                OWLObjectProperty op = (OWLObjectProperty) p;
                m.put("domains", termRefs(mm, EntitySearcher.getDomains(op, scope), prefixes));
                m.put("ranges", termRefs(mm, EntitySearcher.getRanges(op, scope), prefixes));
            } else {
                OWLDataProperty dp = (OWLDataProperty) p;
                m.put("domains", termRefs(mm, EntitySearcher.getDomains(dp, scope), prefixes));
                m.put("ranges", termRefs(mm, EntitySearcher.getRanges(dp, scope), prefixes));
            }
            items.add(m);
        }
        return new ListResult(items, properties.size());
    }

    private static ListResult individualRefs(OWLModelManager mm, List<OWLNamedIndividual> individuals,
            Set<OWLOntology> scope, int limit, Map<String, String> prefixes) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < individuals.size() && i < limit; i++) {
            OWLNamedIndividual ind = individuals.get(i);
            Map<String, Object> m = ref(mm, ind, prefixes);
            m.put("types", termRefs(mm, EntitySearcher.getTypes(ind, scope), prefixes));
            items.add(m);
        }
        return new ListResult(items, individuals.size());
    }

    /** The standard {@code {curie?, iri, display}} ref for a named entity. */
    private static Map<String, Object> ref(OWLModelManager mm, OWLEntity e, Map<String, String> prefixes) {
        Map<String, Object> m = new LinkedHashMap<>();
        String iri = e.getIRI().toString();
        String curie = curie(iri, prefixes);
        if (curie != null) {
            m.put("curie", curie);
        }
        m.put("iri", iri);
        m.put("display", mm.getRendering(e));
        return m;
    }

    /** Render a (capped, de-duplicated) list of domain/range/type members as CURIEs/IRIs/expressions. */
    private static List<String> termRefs(OWLModelManager mm, Collection<? extends OWLObject> objects,
            Map<String, String> prefixes) {
        Set<String> out = new LinkedHashSet<>();
        for (OWLObject o : objects) {
            if (out.size() >= MEMBER_CAP) {
                break;
            }
            out.add(termRef(mm, o, prefixes));
        }
        return new ArrayList<>(out);
    }

    /** A named class/datatype renders as its CURIE/&lt;IRI&gt;; anything anonymous as its rendering. */
    private static String termRef(OWLModelManager mm, OWLObject o, Map<String, String> prefixes) {
        if (o instanceof OWLClass && !((OWLClass) o).isAnonymous()) {
            return curieOrIri(((OWLClass) o).getIRI().toString(), prefixes);
        }
        if (o instanceof OWLDatatype) {
            return curieOrIri(((OWLDatatype) o).getIRI().toString(), prefixes);
        }
        return Tools.renderObject(mm, o);
    }

    private static void recordTruncation(Map<String, Object> truncated, String key, ListResult res) {
        int omitted = res.total - res.items.size();
        if (omitted > 0) {
            truncated.put(key, omitted);
        }
    }

    /** The CURIE/&lt;IRI&gt; ref string of the first item in a list, or null if empty. */
    private static String firstRef(ListResult res) {
        if (res == null || res.items.isEmpty()) {
            return null;
        }
        Map<String, Object> first = res.items.get(0);
        Object curie = first.get("curie");
        if (curie != null) {
            return String.valueOf(curie);
        }
        return "<" + first.get("iri") + ">";
    }

    /** A capped list of refs and the total count before capping. */
    private static final class ListResult {
        final List<Map<String, Object>> items;
        final int total;

        ListResult(List<Map<String, Object>> items, int total) {
            this.items = items;
            this.total = total;
        }
    }

    /**
     * Example queries grounded in the ontology's own terms. The class/property refs are CURIEs (or
     * {@code <IRI>}s); the generic examples lean on rdf/rdfs/owl prefixes, which sparql_query
     * auto-prepends, so every example runs as-is. Static and pure so it can be unit-tested.
     */
    static List<Map<String, Object>> exampleQueries(String classRef, String objPropRef,
            String dataPropRef) {
        List<Map<String, Object>> examples = new ArrayList<>();
        examples.add(example("List all named classes",
                "SELECT ?class WHERE { ?class a owl:Class . FILTER(isIRI(?class)) }\nORDER BY ?class"));
        examples.add(example("Count instances per class",
                "SELECT ?type (COUNT(?x) AS ?count) WHERE { ?x a ?type }\n"
                        + "GROUP BY ?type ORDER BY DESC(?count)"));
        examples.add(example("Asserted subclass links",
                "SELECT ?sub ?super WHERE { ?sub rdfs:subClassOf ?super . FILTER(isIRI(?super)) }"));
        if (classRef != null) {
            examples.add(example("Instances of " + classRef,
                    "SELECT ?x WHERE { ?x a " + classRef + " }"));
        }
        if (objPropRef != null) {
            examples.add(example("Subject/object pairs of " + objPropRef,
                    "SELECT ?s ?o WHERE { ?s " + objPropRef + " ?o }\nLIMIT 25"));
        }
        if (dataPropRef != null) {
            examples.add(example("Values of " + dataPropRef,
                    "SELECT ?s ?value WHERE { ?s " + dataPropRef + " ?value }\nLIMIT 25"));
        }
        examples.add(example("Sample of all triples",
                "SELECT ?s ?p ?o WHERE { ?s ?p ?o }\nLIMIT 25"));
        return examples;
    }

    private static Map<String, Object> example(String title, String query) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("query", query);
        return m;
    }

    // ================================================================ sparql_validate

    private static CallToolResult validate(ToolContext ctx, String query, boolean dryRun,
            int sampleLimit, int timeout) {
        // 1) Prefixes first (the auto-prepend mirrors sparql_query), so a CURIE-only query parses.
        Map<String, String> prefixes = ctx.access().compute(
                mm -> SparqlTools.prefixMap(mm, mm.getActiveOntology()));
        // 2) Parse + inspect off the EDT (pure Jena).
        QueryInspection ins = inspect(query, prefixes);
        // 3) Back on the EDT: resolve which referenced IRIs are unknown, and (for a dry run of an
        //    executable query) build the asserted snapshot to run against.
        boolean wantSnapshot = dryRun && ins.executable;
        Resolution res = ctx.access().compute(mm -> {
            OWLEntityFinder finder = mm.getOWLEntityFinder();
            // The snapshot keeps the active ontology's own IRI (and version IRI) as a real subject so
            // header triples are queryable, but those IRIs are not OWL entities — don't flag them.
            OWLOntologyID oid = mm.getActiveOntology().getOntologyID();
            Set<String> selfIris = new LinkedHashSet<>();
            if (oid.getOntologyIRI().isPresent()) {
                selfIris.add(oid.getOntologyIRI().get().toString());
            }
            if (oid.getVersionIRI().isPresent()) {
                selfIris.add(oid.getVersionIRI().get().toString());
            }
            List<String> unknown = new ArrayList<>();
            for (String iri : ins.referencedIris) {
                if (selfIris.contains(iri)) {
                    continue;
                }
                Set<OWLEntity> es = finder.getEntities(IRI.create(iri));
                if (es == null || es.isEmpty()) {
                    unknown.add(iri);
                }
            }
            SparqlTools.Snapshot snap = wantSnapshot ? SparqlTools.snapshot(mm, false) : null;
            return new Resolution(unknown, snap);
        }, timeout);
        // 4) Dry-run execution off the EDT (so a heavy query never blocks the UI).
        Map<String, Object> sample = null;
        String sampleError = null;
        if (wantSnapshot && res.snapshot != null) {
            try {
                sample = SparqlTools.execute(res.snapshot.ontology(), res.snapshot.prefixes(),
                        query, sampleLimit, timeout);
            } catch (ToolArgException e) {
                sampleError = e.getMessage();
            } catch (RuntimeException e) {
                // The dry run is best-effort: a Jena evaluation/serialisation error (e.g. a type error
                // in an expression) must surface as sample_error, not turn the whole (successful)
                // validation into a tool error. Let Error propagate; only soak up RuntimeException.
                String msg = e.getMessage();
                sampleError = e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
            }
        }
        return buildValidateResult(ins, prefixes, res.unknown, sample, sampleError);
    }

    private static CallToolResult buildValidateResult(QueryInspection ins, Map<String, String> prefixes,
            List<String> unknownIris, Map<String, Object> sample, String sampleError) {
        List<String> issues = new ArrayList<>();
        List<Map<String, Object>> unknownTerms = new ArrayList<>();
        for (String iri : unknownIris) {
            Map<String, Object> m = new LinkedHashMap<>();
            String curie = curie(iri, prefixes);
            if (curie != null) {
                m.put("curie", curie);
            }
            m.put("iri", iri);
            unknownTerms.add(m);
        }

        if (!ins.valid) {
            issues.add("Does not parse as SPARQL — see parse_error.");
        } else if (ins.isUpdate) {
            issues.add("This is a SPARQL UPDATE; sparql_query runs only read queries (SELECT/ASK/"
                    + "CONSTRUCT/DESCRIBE). Use the write tools to edit the ontology.");
        }
        if (ins.usesService) {
            issues.add("Uses SERVICE, which sparql_query rejects — it runs only over the local "
                    + "ontology and never reaches the network.");
        }
        if (!unknownTerms.isEmpty()) {
            issues.add(unknownTerms.size() + " referenced term(s) are not declared as a class/property/"
                    + "individual/datatype in the ontology (usually a typo or a term from another "
                    + "vocabulary) — see unknown_terms.");
        }

        Tools.Json json = Tools.json()
                .put("valid", ins.valid)
                .put("query_type", ins.queryType)
                .put("executable", ins.executable)
                .put("vars", ins.vars)
                .put("uses_service", ins.usesService);
        json.putIfNotNull("parse_error", ins.parseError);
        json.put("unknown_terms", unknownTerms);
        json.put("issues", issues);
        if (sample != null) {
            json.put("sample", sample);
        }
        json.putIfNotNull("sample_error", sampleError);
        json.put("note", "executable=true means sparql_query will accept this query. unknown_terms are "
                + "IRIs used in the query (graph patterns, property paths, VALUES, and the CONSTRUCT "
                + "template / DESCRIBE targets) that are not declared as entities in the ontology — "
                + "usually a typo; an IRI used only as an annotation value also appears here. Fix or "
                + "confirm them before running. Checks asserted structure, so a term that only the "
                + "reasoner derives is not flagged. Set dry_run=true to run it with a small LIMIT.");
        return json.result();
    }

    /** The unknown-IRI set and (optional) snapshot resolved on the EDT for {@code sparql_validate}. */
    private static final class Resolution {
        final List<String> unknown;
        final SparqlTools.Snapshot snapshot;

        Resolution(List<String> unknown, SparqlTools.Snapshot snapshot) {
            this.unknown = unknown;
            this.snapshot = snapshot;
        }
    }

    /** Static, Protégé-free parse result of a SPARQL query (for unit testing). */
    static final class QueryInspection {
        boolean valid;
        boolean isUpdate;
        boolean usesService;
        boolean executable;
        String queryType;
        String parseError;
        List<String> vars = Collections.emptyList();
        List<String> referencedIris = Collections.emptyList();
    }

    /**
     * Parse {@code rawQuery} (with the ontology {@code prefixes} auto-prepended, as sparql_query does)
     * and report its form, projected variables, SERVICE use, and the non-built-in IRIs it references in
     * its graph patterns. A parse failure yields {@code valid=false} (or {@code isUpdate=true} when the
     * text is a valid SPARQL UPDATE) rather than throwing — validation never errors on a bad query.
     * Static and free of Protégé types so it can be unit-tested.
     */
    static QueryInspection inspect(String rawQuery, Map<String, String> prefixes) {
        return withJena(() -> {
            String text = SparqlTools.withPrefixes(rawQuery, prefixes);
            QueryInspection ins = new QueryInspection();
            Query query;
            try {
                query = QueryFactory.create(text);
            } catch (QueryParseException parseError) {
                boolean update = parsesAsUpdate(text);
                ins.valid = update;
                ins.isUpdate = update;
                ins.queryType = update ? "UPDATE" : null;
                ins.parseError = update ? null : parseError.getMessage();
                return ins;
            }
            ins.valid = true;
            ins.queryType = queryType(query);
            if (query.isSelectType()) {
                ins.vars = new ArrayList<>(query.getResultVars());
            }
            collectFromAlgebra(query, ins);
            boolean readForm = query.isSelectType() || query.isAskType()
                    || query.isConstructType() || query.isDescribeType();
            ins.executable = readForm && !ins.usesService;
            return ins;
        });
    }

    private static boolean parsesAsUpdate(String text) {
        try {
            // An empty / prefix-only / comment-only body parses as a valid UpdateRequest with zero
            // operations; that is not really an UPDATE, so report it as a parse error instead.
            return !UpdateFactory.create(text).getOperations().isEmpty();
        } catch (RuntimeException notAnUpdate) {
            return false;
        }
    }

    private static String queryType(Query query) {
        if (query.isSelectType()) {
            return "SELECT";
        }
        if (query.isAskType()) {
            return "ASK";
        }
        if (query.isConstructType()) {
            return "CONSTRUCT";
        }
        if (query.isDescribeType()) {
            return "DESCRIBE";
        }
        return "UNKNOWN";
    }

    /** Walk the query algebra to flag SERVICE and collect the non-built-in IRIs in its graph patterns. */
    private static void collectFromAlgebra(Query query, QueryInspection ins) {
        Set<String> raw = new LinkedHashSet<>();
        boolean[] service = {false};
        try {
            Op op = Algebra.compile(query);
            OpWalker.walk(op, new OpVisitorBase() {
                @Override
                public void visit(OpBGP opBGP) {
                    for (Triple t : opBGP.getPattern()) {
                        addUri(raw, t.getSubject());
                        addUri(raw, t.getPredicate());
                        addUri(raw, t.getObject());
                    }
                }

                @Override
                public void visit(OpPath opPath) {
                    TriplePath tp = opPath.getTriplePath();
                    addUri(raw, tp.getSubject());
                    addUri(raw, tp.getObject());
                    if (tp.getPath() != null) {
                        collectPathLinks(tp.getPath(), raw);
                    }
                }

                @Override
                public void visit(OpTable opTable) {
                    // A VALUES block is inline graph data — check the IRIs it binds too.
                    Iterator<Binding> rows = opTable.getTable().rows();
                    while (rows.hasNext()) {
                        Binding row = rows.next();
                        Iterator<Var> vars = row.vars();
                        while (vars.hasNext()) {
                            addUri(raw, row.get(vars.next()));
                        }
                    }
                }

                @Override
                public void visit(OpService opService) {
                    service[0] = true;
                }
            });
        } catch (RuntimeException degraded) {
            // An exotic query whose algebra cannot be compiled: leave the collected terms as-is and let
            // sparql_query's own SERVICE/parse guards be the load-bearing checks at execution time.
        }
        // DESCRIBE targets and the CONSTRUCT template live outside the WHERE-clause algebra (a bare
        // 'DESCRIBE ex:Thing' compiles to no graph pattern at all), so fold them in from the query.
        try {
            if (query.isDescribeType() && query.getResultURIs() != null) {
                for (Node n : query.getResultURIs()) {
                    addUri(raw, n);
                }
            }
            if (query.isConstructType() && query.getConstructTemplate() != null) {
                for (Triple t : query.getConstructTemplate().getTriples()) {
                    addUri(raw, t.getSubject());
                    addUri(raw, t.getPredicate());
                    addUri(raw, t.getObject());
                }
            }
        } catch (RuntimeException degraded) {
            // best effort: a malformed template/DESCRIBE list just means those terms are unchecked
        }
        List<String> referenced = new ArrayList<>();
        for (String iri : raw) {
            if (!isBuiltin(iri)) {
                referenced.add(iri);
            }
        }
        ins.usesService = service[0];
        ins.referencedIris = referenced;
    }

    private static void addUri(Set<String> out, Node node) {
        if (node != null && node.isURI()) {
            out.add(node.getURI());
        }
    }

    /** Collect the predicate IRIs out of a property path (best effort; never throws). */
    private static void collectPathLinks(Path path, Set<String> out) {
        try {
            if (path instanceof P_Path0) {
                addUri(out, ((P_Path0) path).getNode());
            } else if (path instanceof P_Path1) {
                collectPathLinks(((P_Path1) path).getSubPath(), out);
            } else if (path instanceof P_Path2) {
                collectPathLinks(((P_Path2) path).getLeft(), out);
                collectPathLinks(((P_Path2) path).getRight(), out);
            } else if (path instanceof P_NegPropSet) {
                for (P_Path0 n : ((P_NegPropSet) path).getNodes()) {
                    collectPathLinks(n, out);
                }
            }
        } catch (RuntimeException degraded) {
            // unknown path shape: skip its predicates rather than fail validation
        }
    }

    static boolean isBuiltin(String iri) {
        return iri.startsWith(RDF_NS) || iri.startsWith(RDFS_NS)
                || iri.startsWith(OWL_NS) || iri.startsWith(XSD_NS);
    }

    // ================================================================ shared helpers

    /**
     * The CURIE for {@code iri} given {@code prefixes} (names already end in {@code ':'}), using the
     * longest matching namespace, or null if no prefix matches or the local part is not CURIE-safe (so
     * the caller falls back to a full {@code <IRI>}). Static and pure for unit testing.
     */
    static String curie(String iri, Map<String, String> prefixes) {
        if (iri == null || prefixes == null) {
            return null;
        }
        String bestName = null;
        String bestNs = null;
        for (Map.Entry<String, String> e : prefixes.entrySet()) {
            String name = e.getKey();
            String ns = e.getValue();
            if (name == null || ns == null || ns.isEmpty()) {
                continue;
            }
            if (iri.startsWith(ns) && (bestNs == null || ns.length() > bestNs.length())) {
                bestNs = ns;
                bestName = name;
            }
        }
        if (bestNs == null) {
            return null;
        }
        String local = iri.substring(bestNs.length());
        if (!isSafeLocal(local)) {
            return null;
        }
        return bestName + local;
    }

    /** {@link #curie} when a prefix matches, else the full IRI in angle brackets (always usable). */
    static String curieOrIri(String iri, Map<String, String> prefixes) {
        String curie = curie(iri, prefixes);
        return curie != null ? curie : "<" + iri + ">";
    }

    /**
     * A conservative SPARQL local-name check: non-empty, only {@code [A-Za-z0-9_-.]}, and not starting
     * with a dot or dash nor ending with a dot (PN_LOCAL forbids a leading '-' and a boundary '.').
     * Anything else (a slash, hash, space, …) means the IRI does not split cleanly on this namespace,
     * so the caller uses the full {@code <IRI>} instead of risking an invalid CURIE.
     */
    private static boolean isSafeLocal(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        char first = s.charAt(0);
        return first != '.' && first != '-' && s.charAt(s.length() - 1) != '.';
    }

    /** Run {@code body} with the bundle classloader pinned and Jena initialised (mirrors execute). */
    private static <T> T withJena(Supplier<T> body) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(SparqlAuthoringTools.class.getClassLoader());
        try {
            JenaSystem.init();
            return body.get();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
