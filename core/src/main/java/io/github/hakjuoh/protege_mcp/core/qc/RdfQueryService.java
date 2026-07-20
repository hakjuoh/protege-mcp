package io.github.hakjuoh.protege_mcp.core.qc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
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
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.parameters.AxiomAnnotations;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.util.InferredAxiomGenerator;
import org.semanticweb.owlapi.util.InferredClassAssertionAxiomGenerator;
import org.semanticweb.owlapi.util.InferredEquivalentClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubDataPropertyAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubObjectPropertyAxiomGenerator;

/** Offline SPARQL snapshot and read-query execution shared by plugin and headless adapters. */
public final class RdfQueryService {

    private static final String SNAPSHOT_IRI = "urn:protege-mcp:sparql-snapshot";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";
    private static final long MAX_INFERENCE_REASONER_QUERIES = 250_000L;
    private static final long MAX_INFERRED_AXIOMS_PER_CATEGORY = 10_000_000L;

    private RdfQueryService() {
    }

    public record Snapshot(OWLOntology ontology, Map<String, String> prefixes, String note) {
        public Snapshot {
            prefixes = Collections.unmodifiableMap(new LinkedHashMap<>(prefixes));
        }
    }

    /** Runtime/configuration exception translated by each delivery adapter. */
    public static final class QueryException extends IllegalArgumentException {
        public QueryException(String message) {
            super(message);
        }
    }

    public static Snapshot snapshot(OWLOntologyID activeId, Set<OWLOntology> closure,
            Map<String, String> prefixes, OWLReasoner reasoner, boolean includeInferred) {
        return snapshot(activeId, closure, prefixes, reasoner, includeInferred,
                new MaterializationBudgets(MAX_INFERRED_AXIOMS_PER_CATEGORY,
                        MAX_INFERRED_AXIOMS_PER_CATEGORY,
                        MAX_INFERRED_AXIOMS_PER_CATEGORY,
                        MAX_INFERRED_AXIOMS_PER_CATEGORY));
    }

    static Snapshot snapshot(OWLOntologyID activeId, Set<OWLOntology> closure,
            Map<String, String> prefixes, OWLReasoner reasoner, boolean includeInferred,
            long propertyAssertionBudget) {
        return snapshot(activeId, closure, prefixes, reasoner, includeInferred,
                new MaterializationBudgets(MAX_INFERRED_AXIOMS_PER_CATEGORY,
                        MAX_INFERRED_AXIOMS_PER_CATEGORY,
                        MAX_INFERRED_AXIOMS_PER_CATEGORY,
                        propertyAssertionBudget));
    }

    static Snapshot snapshot(OWLOntologyID activeId, Set<OWLOntology> closure,
            Map<String, String> prefixes, OWLReasoner reasoner, boolean includeInferred,
            MaterializationBudgets budgets) {
        if (closure == null || prefixes == null || budgets == null) {
            throw new IllegalArgumentException("closure, prefixes and budgets must not be null");
        }
        if (includeInferred && reasoner == null) {
            throw new IllegalArgumentException(
                    "reasoner must be supplied when includeInferred is true");
        }
        OWLOntologyManager manager = createManager();
        OWLOntology isolated = buildSnapshotOntology(manager, activeId, closure);
        String note = null;
        if (includeInferred) {
            MaterializationPlan plan = materializationPlan(isolated);
            note = combineNotes(plan.note(), materializeInferredCategories(
                    manager, isolated, reasoner, plan, budgets));
        }
        return new Snapshot(isolated, prefixes, note);
    }

    public static OWLOntology buildSnapshotOntology(OWLOntologyManager manager,
            OWLOntologyID activeId, Set<OWLOntology> closure) {
        if (manager == null || closure == null) {
            throw new IllegalArgumentException("manager and closure must not be null");
        }
        final OWLOntology isolated;
        try {
            isolated = manager.createOntology(activeId != null && !activeId.isAnonymous()
                    ? activeId : new OWLOntologyID());
        } catch (OWLOntologyCreationException error) {
            throw new QueryException("Could not prepare an isolated ontology workspace: "
                    + message(error));
        }
        for (OWLOntology ontology : closure) {
            manager.addAxioms(isolated, ontology.getAxioms());
            for (OWLAnnotation annotation : ontology.getAnnotations()) {
                manager.applyChange(new AddOntologyAnnotation(isolated, annotation));
            }
        }
        return isolated;
    }

    public static Map<String, Object> execute(OWLOntology ontology,
            Map<String, String> prefixes, String query, int limit, long timeoutMs) {
        return executeTurtle(toTurtleBytes(ontology), prefixes, query, limit, timeoutMs);
    }

    public static Map<String, Object> executeTurtle(byte[] turtle,
            Map<String, String> prefixes, String query, int limit, long timeoutMs) {
        if (turtle == null || query == null) {
            throw new IllegalArgumentException("turtle and query must not be null");
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(RdfQueryService.class.getClassLoader());
        try {
            JenaSystem.init();
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, new ByteArrayInputStream(turtle), SNAPSHOT_IRI, Lang.TURTLE);
            Query parsed = parse(withPrefixes(query, prefixes));
            rejectService(parsed);
            try (QueryExecution execution = QueryExecutionFactory.create(parsed, model)) {
                execution.getContext().set(ARQ.httpServiceAllowed, false);
                if (timeoutMs > 0) {
                    execution.setTimeout(timeoutMs, TimeUnit.MILLISECONDS,
                            timeoutMs, TimeUnit.MILLISECONDS);
                }
                try {
                    if (parsed.isSelectType()) return selectJson(execution, limit);
                    if (parsed.isAskType()) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("query_type", "ASK");
                        out.put("boolean", execution.execAsk());
                        return out;
                    }
                    if (parsed.isConstructType()) {
                        return graphJson("CONSTRUCT", execution.execConstruct(), model, limit);
                    }
                    if (parsed.isDescribeType()) {
                        return graphJson("DESCRIBE", execution.execDescribe(), model, limit);
                    }
                    throw new QueryException("Unsupported query form. Use SELECT, ASK, CONSTRUCT, or "
                            + "DESCRIBE (SPARQL UPDATE is not allowed — use the write tools to edit).");
                } catch (QueryCancelledException timeout) {
                    throw new QueryException("SPARQL query exceeded the " + timeoutMs + " ms time "
                            + "budget — add a LIMIT, constrain the pattern, or raise timeout_ms.");
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public static byte[] toTurtleBytes(OWLOntology ontology) {
        if (ontology == null) throw new IllegalArgumentException("ontology must not be null");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ontology.getOWLOntologyManager().saveOntology(
                    ontology, new TurtleDocumentFormat(), out);
        } catch (OWLOntologyStorageException error) {
            throw new QueryException("Could not render the ontology to RDF for SPARQL: "
                    + message(error));
        }
        return out.toByteArray();
    }

    public static String withPrefixes(String query, Map<String, String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return query;
        StringBuilder prologue = new StringBuilder();
        for (Map.Entry<String, String> entry : prefixes.entrySet()) {
            String name = entry.getKey();
            String namespace = entry.getValue();
            if (name == null || namespace == null || namespace.isEmpty()) continue;
            prologue.append("PREFIX ").append(name).append(" <")
                    .append(namespace).append(">\n");
        }
        return prologue.length() == 0 ? query : prologue + query;
    }

    private static Query parse(String query) {
        try {
            return QueryFactory.create(query);
        } catch (QueryParseException error) {
            throw new QueryException("SPARQL query error: " + error.getMessage()
                    + " (sparql_query accepts only read queries: SELECT, ASK, CONSTRUCT, or DESCRIBE.)");
        }
    }

    private static void rejectService(Query query) {
        boolean[] service = {false};
        try {
            Op operation = Algebra.compile(query);
            OpWalker.walk(operation, new OpVisitorBase() {
                @Override
                public void visit(OpService ignored) {
                    service[0] = true;
                }
            });
        } catch (RuntimeException ignored) {
            // The ARQ httpServiceAllowed=false execution context remains the load-bearing guard.
        }
        if (service[0]) {
            throw new QueryException("SPARQL SERVICE is not allowed — sparql_query runs only over the "
                    + "local ontology and never reaches the network.");
        }
    }

    private static Map<String, Object> selectJson(QueryExecution execution, int limit) {
        ResultSet results = execution.execSelect();
        List<String> variables = results.getResultVars();
        List<Map<String, Object>> bindings = new ArrayList<>();
        boolean truncated = false;
        while (results.hasNext()) {
            if (bindings.size() >= limit) {
                truncated = true;
                break;
            }
            QuerySolution solution = results.next();
            Map<String, Object> row = new LinkedHashMap<>();
            for (String variable : variables) {
                RDFNode node = solution.get(variable);
                if (node != null) row.put(variable, nodeJson(node));
            }
            bindings.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query_type", "SELECT");
        out.put("vars", variables);
        out.put("count", bindings.size());
        out.put("bindings", bindings);
        if (truncated) out.put("truncated", true);
        return out;
    }

    private static Map<String, Object> nodeJson(RDFNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node.isURIResource()) {
            out.put("type", "uri");
            out.put("value", node.asResource().getURI());
        } else if (node.isAnon()) {
            out.put("type", "bnode");
            out.put("value", node.asResource().getId().getLabelString());
        } else {
            Literal literal = node.asLiteral();
            out.put("type", "literal");
            out.put("value", literal.getLexicalForm());
            String language = literal.getLanguage();
            if (language != null && !language.isEmpty()) out.put("lang", language);
            else {
                String datatype = literal.getDatatypeURI();
                if (datatype != null && !datatype.equals(XSD_STRING)) {
                    out.put("datatype", datatype);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> graphJson(String type, Model result,
            Model source, int limit) {
        result.setNsPrefixes(source.getNsPrefixMap());
        long total = result.size();
        Model output = result;
        boolean truncated = false;
        if (total > limit) {
            List<Statement> statements = result.listStatements().toList();
            statements.sort(Comparator.comparing(statement -> statement.asTriple().toString()));
            output = ModelFactory.createDefaultModel();
            output.setNsPrefixes(result.getNsPrefixMap());
            for (int index = 0; index < limit && index < statements.size(); index++) {
                output.add(statements.get(index));
            }
            truncated = true;
        }
        StringWriter turtle = new StringWriter();
        output.write(turtle, "TURTLE");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query_type", type);
        out.put("count", total);
        out.put("turtle", turtle.toString());
        if (truncated) {
            out.put("truncated", true);
            out.put("shown", limit);
        }
        return out;
    }

    /** Conservative preflight used before asking a reasoner to enumerate inferred axioms. */
    static MaterializationPlan materializationPlan(OWLOntology ontology) {
        return materializationPlan(
                ontology.getClassesInSignature().size(),
                ontology.getIndividualsInSignature().size(),
                ontology.getObjectPropertiesInSignature().size(),
                ontology.getDataPropertiesInSignature().size());
    }

    static MaterializationPlan materializationPlan(long classes, long individuals,
            long objectProperties, long dataProperties) {
        if (classes < 0 || individuals < 0 || objectProperties < 0 || dataProperties < 0) {
            throw new IllegalArgumentException("materialization cardinalities must not be negative");
        }
        long properties = saturatedAdd(objectProperties, dataProperties);
        // OWLAPI's hierarchy/type generators query direct results once per signature subject; they do
        // not enumerate the Cartesian product. Property assertions ask for every individual/property
        // pair, so their reasoner work is that product. Result sizes are data-dependent and cannot be
        // estimated soundly up front (a theoretical n²·p worst case would reject ordinary sparse
        // ABoxes 0.7.0 materialized). Every admitted category therefore receives a separate actual
        // result budget during materialization rather than relying on a theoretical worst case.
        long classHierarchyEstimate = saturatedMultiply(classes, 2);
        long classAssertionEstimate = individuals;
        long propertyHierarchyEstimate = properties;
        long propertyQueryEstimate = saturatedMultiply(individuals, properties);

        boolean classHierarchy = classHierarchyEstimate <= MAX_INFERENCE_REASONER_QUERIES;
        boolean classAssertions = classAssertionEstimate <= MAX_INFERENCE_REASONER_QUERIES;
        boolean propertyHierarchy = propertyHierarchyEstimate <= MAX_INFERENCE_REASONER_QUERIES;
        boolean propertyAssertions = propertyQueryEstimate <= MAX_INFERENCE_REASONER_QUERIES;
        List<String> skipped = new ArrayList<>();
        if (!classHierarchy) skipped.add("subclass/equivalent-class hierarchy (query estimate "
                + estimate(classHierarchyEstimate) + ")");
        if (!classAssertions) skipped.add("class assertions (query estimate "
                + estimate(classAssertionEstimate) + ")");
        if (!propertyHierarchy) skipped.add("property hierarchy (query estimate "
                + estimate(propertyHierarchyEstimate) + ")");
        if (!propertyAssertions) skipped.add("property assertions (query estimate "
                + estimate(propertyQueryEstimate) + ")");
        String note = skipped.isEmpty() ? null
                : "Some inferred categories were skipped before materialization because conservative "
                        + "cardinality estimates exceeded memory budgets: "
                        + String.join("; ", skipped) + ". Targeted reasoner tools remain available.";
        return new MaterializationPlan(classHierarchy, classAssertions,
                propertyHierarchy, propertyAssertions, note);
    }

    record MaterializationBudgets(long classHierarchy, long classAssertions,
            long propertyHierarchy, long propertyAssertions) {
        MaterializationBudgets {
            if (classHierarchy < 0 || classAssertions < 0
                    || propertyHierarchy < 0 || propertyAssertions < 0) {
                throw new IllegalArgumentException("materialization budgets must not be negative");
            }
        }
    }

    private static String materializeInferredCategories(OWLOntologyManager manager,
            OWLOntology isolated, OWLReasoner reasoner, MaterializationPlan plan,
            MaterializationBudgets budgets) {
        List<String> notes = new ArrayList<>();
        if (plan.classHierarchy()) {
            ResultBudget budget = new ResultBudget(budgets.classHierarchy());
            addNote(notes, materializeCategory(manager, isolated, reasoner,
                    "subclass/equivalent-class hierarchy", budgets.classHierarchy(), List.of(
                            new BudgetedSubClassGenerator(budget),
                            new BudgetedEquivalentClassGenerator(budget))));
        }
        if (plan.classAssertions()) {
            ResultBudget budget = new ResultBudget(budgets.classAssertions());
            addNote(notes, materializeCategory(manager, isolated, reasoner,
                    "class assertions", budgets.classAssertions(),
                    List.of(new BudgetedClassAssertionGenerator(budget))));
        }
        if (plan.propertyHierarchy()) {
            ResultBudget budget = new ResultBudget(budgets.propertyHierarchy());
            addNote(notes, materializeCategory(manager, isolated, reasoner,
                    "property hierarchy", budgets.propertyHierarchy(), List.of(
                            new BudgetedSubObjectPropertyGenerator(budget),
                            new BudgetedSubDataPropertyGenerator(budget))));
        }
        if (plan.propertyAssertions()) {
            addNote(notes, materializePropertyAssertions(manager, isolated, reasoner,
                    budgets.propertyAssertions()));
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    private static String materializeCategory(OWLOntologyManager manager,
            OWLOntology isolated, OWLReasoner reasoner, String category, long budget,
            List<InferredAxiomGenerator<? extends OWLAxiom>> generators) {
        Set<OWLAxiom> results = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (InferredAxiomGenerator<? extends OWLAxiom> generator : generators) {
            try {
                results.addAll(generator.createAxioms(manager.getOWLDataFactory(), reasoner));
            } catch (ResultBudgetExceeded exceeded) {
                // The budget bounds the whole category: drop it atomically rather than keep a
                // partial graph whose missing remainder is unpredictable.
                return categoryBudgetNote(category, budget);
            } catch (Exception failure) {
                // Match OWLAPI fillOntology's per-generator containment: one unanswerable
                // enumeration (e.g. ELK's data-property hierarchy) must not discard the sibling
                // generator's answered axioms, which 0.7.0 kept.
                failures.add(generator.getLabel() + " — " + failure.getClass().getSimpleName()
                        + ": " + message(failure));
            }
        }
        addReferenceCompatibleAxioms(manager, isolated, results);
        return failures.isEmpty() ? null : categoryFailureNote(category, failures);
    }

    private static void addNote(List<String> notes, String note) {
        if (note != null) notes.add(note);
    }

    /**
     * Materializes inferred property assertions exactly like OWLAPI's
     * {@code InferredPropertyAssertionGenerator}, but drops the whole category and reports a
     * truncation note once the ACTUAL number of inferred assertions exceeds
     * {@link #MAX_INFERRED_AXIOMS_PER_CATEGORY}. Result sizes are data-dependent, so this is the
     * earliest point at which a sound memory bound can be enforced without rejecting ordinary
     * sparse ABoxes whose theoretical worst case is large.
     *
     * @return a truncation note when the category was dropped, otherwise {@code null}
     */
    static String materializePropertyAssertions(OWLOntologyManager manager,
            OWLOntology isolated, OWLReasoner reasoner, long budget) {
        OWLDataFactory dataFactory = manager.getOWLDataFactory();
        OWLOntology root = reasoner.getRootOntology();
        Set<OWLNamedIndividual> processed = new HashSet<>();
        Set<OWLAxiom> results = new BoundedAxiomSet<>(new ResultBudget(budget));
        try {
            for (OWLOntology ontology : root.getImportsClosure()) {
                for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
                    if (!processed.add(individual)) continue;
                    for (OWLObjectProperty property
                            : root.getObjectPropertiesInSignature(Imports.INCLUDED)) {
                        for (OWLNamedIndividual value : reasoner
                                .getObjectPropertyValues(individual, property).getFlattened()) {
                            results.add(dataFactory.getOWLObjectPropertyAssertionAxiom(
                                    property, individual, value));
                        }
                    }
                    for (OWLDataProperty property
                            : root.getDataPropertiesInSignature(Imports.INCLUDED)) {
                        for (OWLLiteral value
                                : reasoner.getDataPropertyValues(individual, property)) {
                            results.add(dataFactory.getOWLDataPropertyAssertionAxiom(
                                    property, individual, value));
                        }
                    }
                }
            }
        } catch (ResultBudgetExceeded exceeded) {
            return propertyAssertionTruncationNote(budget);
        } catch (Exception failure) {
            // OWLAPI's InferredOntologyGenerator.fillOntology contains each generator's failure so
            // one unsupported query (e.g. ELK's getObjectPropertyValues) or an inconsistent
            // ontology cannot take down the other inferred categories. Match that containment, but
            // report the dropped category instead of 0.7.0's silent skip.
            return "Some inferred categories were dropped during materialization because the "
                    + "active reasoner could not enumerate them: property assertions ("
                    + failure.getClass().getSimpleName() + ": " + message(failure)
                    + "). Targeted reasoner tools remain available.";
        }
        addReferenceCompatibleAxioms(manager, isolated, results);
        return null;
    }

    /** Match OWLAPI fillOntology's annotation-insensitive already-present check. */
    private static void addReferenceCompatibleAxioms(OWLOntologyManager manager,
            OWLOntology isolated, Set<? extends OWLAxiom> results) {
        Set<OWLAxiom> additions = new HashSet<>();
        for (OWLAxiom axiom : results) {
            if (!isolated.containsAxiom(axiom, Imports.INCLUDED,
                    AxiomAnnotations.IGNORE_AXIOM_ANNOTATIONS)) {
                additions.add(axiom);
            }
        }
        manager.addAxioms(isolated, additions);
    }

    private static String propertyAssertionTruncationNote(long budget) {
        return "Some inferred categories were dropped during materialization because they exceeded "
                + "memory budgets: property assertions (more than " + budget
                + " inferred assertions). Targeted reasoner tools remain available.";
    }

    private static String categoryBudgetNote(String category, long budget) {
        return "Some inferred categories were dropped during materialization because they exceeded "
                + "memory budgets: " + category + " (more than " + budget
                + " inferred axioms). Targeted reasoner tools remain available.";
    }

    private static String categoryFailureNote(String category, List<String> failures) {
        return "Some inferred categories were dropped during materialization because the "
                + "active reasoner could not fully enumerate them: " + category + " ("
                + String.join("; ", failures)
                + "); axioms the reasoner did answer were kept. "
                + "Targeted reasoner tools remain available.";
    }

    private static String combineNotes(String first, String second) {
        if (first == null) return second;
        if (second == null) return first;
        return first + " " + second;
    }

    record MaterializationPlan(boolean classHierarchy, boolean classAssertions,
            boolean propertyHierarchy, boolean propertyAssertions, String note) { }

    @FunctionalInterface
    private interface EntitySource<E extends OWLEntity> {
        Set<E> entities(OWLOntology ontology);
    }

    @FunctionalInterface
    private interface EntityAxiomAdder<E extends OWLEntity, A extends OWLAxiom> {
        void add(E entity, Set<A> results);
    }

    private static <E extends OWLEntity, A extends OWLAxiom> Set<A> createBudgetedAxioms(
            OWLReasoner reasoner, ResultBudget budget, EntitySource<E> source,
            EntityAxiomAdder<E, A> adder) {
        long checkpoint = budget.checkpoint();
        Set<E> processed = new HashSet<>();
        Set<A> results = new BoundedAxiomSet<>(budget);
        try {
            for (OWLOntology ontology : reasoner.getRootOntology().getImportsClosure()) {
                for (E entity : source.entities(ontology)) {
                    if (processed.add(entity)) adder.add(entity, results);
                }
            }
            return results;
        } catch (ResultBudgetExceeded exceeded) {
            // The caller drops the whole category, so no later generator may reuse this budget.
            throw exceeded;
        } catch (RuntimeException | Error failure) {
            // createAxioms discards this generator's partial set when it fails. Its tentative
            // claims must be discarded with it or a later successful sibling can spuriously
            // overflow even though the category's retained result set is within budget.
            budget.restore(checkpoint);
            throw failure;
        }
    }

    private static final class BudgetedSubClassGenerator
            extends InferredSubClassAxiomGenerator {
        private final ResultBudget budget;

        private BudgetedSubClassGenerator(ResultBudget budget) {
            this.budget = budget;
        }

        @Override
        public Set<OWLSubClassOfAxiom> createAxioms(OWLDataFactory dataFactory,
                OWLReasoner reasoner) {
            return createBudgetedAxioms(reasoner, budget, ontology -> getEntities(ontology),
                    (entity, results) -> addAxioms(entity, reasoner, dataFactory, results));
        }
    }

    private static final class BudgetedEquivalentClassGenerator
            extends InferredEquivalentClassAxiomGenerator {
        private final ResultBudget budget;

        private BudgetedEquivalentClassGenerator(ResultBudget budget) {
            this.budget = budget;
        }

        @Override
        public Set<OWLEquivalentClassesAxiom> createAxioms(OWLDataFactory dataFactory,
                OWLReasoner reasoner) {
            return createBudgetedAxioms(reasoner, budget, ontology -> getEntities(ontology),
                    (entity, results) -> addAxioms(entity, reasoner, dataFactory, results));
        }
    }

    private static final class BudgetedClassAssertionGenerator
            extends InferredClassAssertionAxiomGenerator {
        private final ResultBudget budget;

        private BudgetedClassAssertionGenerator(ResultBudget budget) {
            this.budget = budget;
        }

        @Override
        public Set<OWLClassAssertionAxiom> createAxioms(OWLDataFactory dataFactory,
                OWLReasoner reasoner) {
            return createBudgetedAxioms(reasoner, budget, ontology -> getEntities(ontology),
                    (entity, results) -> addAxioms(entity, reasoner, dataFactory, results));
        }
    }

    private static final class BudgetedSubObjectPropertyGenerator
            extends InferredSubObjectPropertyAxiomGenerator {
        private final ResultBudget budget;

        private BudgetedSubObjectPropertyGenerator(ResultBudget budget) {
            this.budget = budget;
        }

        @Override
        public Set<OWLSubObjectPropertyOfAxiom> createAxioms(OWLDataFactory dataFactory,
                OWLReasoner reasoner) {
            return createBudgetedAxioms(reasoner, budget, ontology -> getEntities(ontology),
                    (entity, results) -> addAxioms(entity, reasoner, dataFactory, results));
        }
    }

    private static final class BudgetedSubDataPropertyGenerator
            extends InferredSubDataPropertyAxiomGenerator {
        private final ResultBudget budget;

        private BudgetedSubDataPropertyGenerator(ResultBudget budget) {
            this.budget = budget;
        }

        @Override
        public Set<OWLSubDataPropertyOfAxiom> createAxioms(OWLDataFactory dataFactory,
                OWLReasoner reasoner) {
            return createBudgetedAxioms(reasoner, budget, ontology -> getEntities(ontology),
                    (entity, results) -> addAxioms(entity, reasoner, dataFactory, results));
        }
    }

    private static final class ResultBudget {
        private final long limit;
        private long used;

        private ResultBudget(long limit) {
            if (limit < 0) {
                throw new IllegalArgumentException("materialization budget must not be negative");
            }
            this.limit = limit;
        }

        private void claim() {
            if (used >= limit) throw new ResultBudgetExceeded();
            used++;
        }

        private long checkpoint() {
            return used;
        }

        private void restore(long checkpoint) {
            if (checkpoint < 0 || checkpoint > used) {
                throw new IllegalArgumentException("invalid materialization budget checkpoint");
            }
            used = checkpoint;
        }
    }

    private static final class ResultBudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class BoundedAxiomSet<A extends OWLAxiom> extends AbstractSet<A> {
        private final Set<A> delegate = new HashSet<>();
        private final ResultBudget budget;

        private BoundedAxiomSet(ResultBudget budget) {
            this.budget = budget;
        }

        @Override
        public boolean add(A axiom) {
            if (delegate.contains(axiom)) return false;
            budget.claim();
            return delegate.add(axiom);
        }

        @Override
        public Iterator<A> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0 || right == 0) return 0;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static String estimate(long value) {
        return value == Long.MAX_VALUE ? ">=" + Long.MAX_VALUE : Long.toString(value);
    }

    private static OWLOntologyManager createManager() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(OWLManager.class.getClassLoader());
            return OWLManager.createOWLOntologyManager();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
