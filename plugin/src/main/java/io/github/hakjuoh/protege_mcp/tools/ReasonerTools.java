package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import io.github.hakjuoh.protege_mcp.policy.ReasonerNames;
import io.github.hakjuoh.protege_mcp.server.EmbeddedClassificationWaiter;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;

import com.clarkparsia.owlapi.explanation.DefaultExplanationGenerator;
import com.clarkparsia.owlapi.explanation.util.SilentExplanationProgressMonitor;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Reasoning tools: select the reasoner, run classification, list unsatisfiable classes, read
 * inferred relations, run DL queries, check entailment, and generate justifications (explanations).
 * All results are JSON objects.
 */
public final class ReasonerTools {

    private ReasonerTools() {
    }

    /**
     * The {@code axiom_type}s {@code get_explanations} can actually justify: the clarkparsia
     * {@code SatisfiabilityConverter}/{@code AxiomConverter} only converts these axiom kinds; every
     * other kind throws "Not implemented" inside the generator, so we reject them up front with a
     * clear message rather than surfacing the raw exception.
     */
    private static final Set<String> EXPLAINABLE_AXIOM_TYPES = new LinkedHashSet<>(Arrays.asList(
            "subclass_of", "equivalent_classes", "disjoint_classes", "class_assertion",
            "object_property_assertion", "data_property_assertion",
            "negative_object_property_assertion", "negative_data_property_assertion",
            "same_individual", "different_individuals",
            "object_property_domain", "object_property_range",
            "data_property_domain", "data_property_range"));

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("run_reasoner",
                (ex, req) -> {
                    int timeout = Tools.optInt(Tools.args(req), "timeout_ms", 60_000);
                    Map<String, Object> pre = ctx.access().compute(mm -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("no_reasoner", mm.getOWLReasonerManager()
                                .getReasonerStatus() == ReasonerStatus.NO_REASONER_FACTORY_CHOSEN);
                        m.put("swrl_warning", swrlIgnoredWarning(mm));
                        return m;
                    });
                    if (Boolean.TRUE.equals(pre.get("no_reasoner"))) {
                        return Tools.error("No reasoner is selected in Protégé. Choose one from the "
                                + "Reasoner menu, then retry.");
                    }
                    Map<String, Object> res = EmbeddedClassificationWaiter.runAndWait(ctx.access(), timeout);
                    // Surface a hard failure as an error instead of a benign-looking success envelope: a
                    // classification that could not start, or that completed only by resetting to the Null
                    // reasoner (classification_failed — e.g. HermiT rejecting a SWRL built-in atom).
                    if (Boolean.FALSE.equals(res.get("started"))
                            || Boolean.TRUE.equals(res.get("classification_failed"))) {
                        return Tools.error(String.valueOf(res.get("message")));
                    }
                    if (pre.get("swrl_warning") != null) {
                        res.put("warning", pre.get("swrl_warning"));
                    }
                    return Tools.ok(res);
                });
        tools.tool("get_unsatisfiable_classes",
                (ex, req) -> guardInconsistency("get_unsatisfiable_classes",
                        () -> ctx.access().compute(mm -> {
                            return Tools.ok(unsatisfiableClasses(mm, requireReasoner(mm)));
                        })));

        tools.tool("get_inferred_superclasses",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String entity = Tools.reqString(a, "entity");
                    String relation = Tools.optString(a, "relation");
                    boolean direct = Tools.optBool(a, "direct", true);
                    String rel = relation == null ? "superclasses" : relation.toLowerCase();
                    return guardInconsistency("get_inferred_superclasses",
                            () -> ctx.access().compute(mm ->
                                    Tools.ok(inferredRelation(mm, requireReasoner(mm), entity, rel, direct))));
                });
        tools.tool("explain_entailment",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    return guardInconsistency("explain_entailment",
                            () -> ctx.access().compute(mm ->
                                    Tools.ok(explainEntailment(mm, requireReasoner(mm), Axioms.build(mm, a)))));
                });
        tools.tool("get_explanations",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String axiomType = Tools.reqString(a, "axiom_type").toLowerCase();
                    if (!EXPLAINABLE_AXIOM_TYPES.contains(axiomType)) {
                        int fallbackTimeout = Tools.optInt(a, "timeout_ms", 60_000);
                        return guardInconsistency("get_explanations",
                                () -> ctx.access().compute(mm -> {
                                    OWLReasoner r = requireReasoner(mm);
                                    OWLAxiom ax = Axioms.build(mm, a);
                                    return Tools.ok(structuralExplanation(mm, ax, axiomType,
                                            r.isEntailed(ax)));
                                }, fallbackTimeout));
                    }
                    int max = Tools.optInt(a, "max", 3);
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    return guardInconsistency("get_explanations", () -> {
                        Object[] snap = ctx.access().compute(mm -> {
                            OWLReasonerManager rm = mm.getOWLReasonerManager();
                            IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(rm);
                            if (spec == null) {
                                return null;
                            }
                            OWLAxiom ax = Axioms.build(mm, a);
                            // Isolate the search from Protégé's live model: the multi-justification (HST)
                            // search transiently removes/re-adds real axioms via the ontology's manager,
                            // which would otherwise land on Protégé's undo stack and dirty-state and leak a
                            // throwaway debugging ontology into the live manager. Run over a private copy of
                            // the active ontology's imports closure instead — all churn and the temporary
                            // reasoners then live in a manager we discard.
                            OWLOntology working = isolatedClosure(mm.getActiveOntology());
                            return new Object[] {spec, ax, working};
                        });
                        if (snap == null) {
                            return Tools.error("No reasoner is selected in Protégé. Use set_reasoner "
                                    + "(or the Reasoner menu) to choose one first.");
                        }
                        IsolatedReasonerSpec spec = (IsolatedReasonerSpec) snap[0];
                        OWLAxiom ax = (OWLAxiom) snap[1];
                        OWLOntology working = (OWLOntology) snap[2];
                        TrackingReasonerFactory tracking = new TrackingReasonerFactory(
                                spec.configuredFactory());
                        FutureTask<Set<Set<OWLAxiom>>> task = new FutureTask<>(() -> {
                            try {
                                DefaultExplanationGenerator gen = new DefaultExplanationGenerator(
                                        working.getOWLOntologyManager(), tracking, working,
                                        new SilentExplanationProgressMonitor());
                                return max > 0 ? gen.getExplanations(ax, max)
                                        : gen.getExplanations(ax);
                            } finally {
                                tracking.disposeAll();
                            }
                        });
                        Thread worker = new Thread(task, "protege-mcp-isolated-explanation");
                        worker.setDaemon(true);
                        worker.start();
                        Set<Set<OWLAxiom>> explanations;
                        try {
                            explanations = task.get(Math.max(timeout, 1), TimeUnit.MILLISECONDS);
                        } catch (TimeoutException e) {
                            task.cancel(true);
                            tracking.cancel();
                            return Tools.error("Explanation search timed out after " + timeout
                                    + " ms; the private search was cancelled — its reasoners were "
                                    + "interrupted and further reasoner creation refused — and no "
                                    + "live state was changed.");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            task.cancel(true);
                            tracking.cancel();
                            return Tools.error("Explanation search was interrupted; the private "
                                    + "search was cancelled and no live state was changed.");
                        } catch (ExecutionException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof RuntimeException) {
                                throw (RuntimeException) cause;
                            }
                            throw new ToolArgException("Explanation search failed: "
                                    + (cause == null || cause.getMessage() == null
                                            ? (cause == null ? e.getClass().getSimpleName()
                                                    : cause.getClass().getSimpleName())
                                            : cause.getMessage()));
                        }
                        return ctx.access().compute(mm -> {
                            Map<String, Object> result = explanationsJson(mm, ax, explanations);
                            Map<String, Object> configuration = spec.metadata(
                                    org.semanticweb.owlapi.reasoner.BufferingMode.NON_BUFFERING);
                            if (spec.selectedBuffering()
                                    != org.semanticweb.owlapi.reasoner.BufferingMode.NON_BUFFERING) {
                                configuration.put("buffering_caveat", "The OWLAPI explanation algorithm "
                                        + "requires a non-buffering reasoner while it adds/removes axioms; "
                                        + "the selected plugin configuration is preserved, but its recommended "
                                        + "buffering mode cannot be used for this operation.");
                            }
                            result.put("reasoner_configuration", configuration);
                            return Tools.ok(result);
                        }, timeout);
                    });
                });
        tools.tool("explain_inconsistency",
                (ex, req) -> {
                    int timeout = Tools.optInt(Tools.args(req), "timeout_ms", 60_000);
                    // Phase 1 (EDT): capture the selected factory + a private closure snapshot.
                    Object[] snap = ctx.access().compute(mm -> {
                        OWLReasonerManager rm = mm.getOWLReasonerManager();
                        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(rm);
                        if (spec == null) {
                            return null;
                        }
                        return new Object[] {
                                spec,
                                isolatedClosure(mm.getActiveOntology())
                        };
                    });
                    if (snap == null) {
                        return Tools.error("No reasoner is selected in Protégé. Use set_reasoner "
                                + "(or the Reasoner menu) to choose one first.");
                    }
                    IsolatedReasonerSpec spec = (IsolatedReasonerSpec) snap[0];
                    OWLOntology working = (OWLOntology) snap[1];
                    // Phase 2 (off the EDT): the contraction search — many fresh reasoners over
                    // throwaway subsets; would freeze the UI if run in a compute.
                    InconsistencySearch s = findInconsistentSubset(working, spec, timeout);
                    // Phase 3 (EDT): render with Protégé's (non-thread-safe) entity renderer.
                    return ctx.access().compute(mm -> {
                        Map<String, Object> result = inconsistencyJson(mm, s, spec.reasonerName());
                        result.put("reasoner_configuration", spec.metadata(spec.selectedBuffering()));
                        return Tools.ok(result);
                    });
                });
        tools.tool("execute_dl_query",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String query = Tools.reqString(a, "query");
                    String relation = Tools.optString(a, "relation");
                    String rel = relation == null ? "all" : relation.toLowerCase();
                    boolean direct = Tools.optBool(a, "direct", true);
                    boolean complete = Tools.optBool(a, "complete", false);
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    return guardInconsistency("execute_dl_query",
                            () -> ctx.access().compute(mm -> {
                                OWLReasoner r = requireReasoner(mm);
                                OWLClassExpression ce = Tools.resolveClassExpression(mm, query);
                                return Tools.ok(dlQuery(mm, r, query, ce, rel, direct, complete));
                            }, timeout));
                });
        tools.tool("list_reasoners",
                (ex, req) -> ctx.access().compute(mm -> {
                    OWLReasonerManager rm = mm.getOWLReasonerManager();
                    String currentId = rm.getCurrentReasonerFactoryId();
                    List<ProtegeOWLReasonerInfo> infos =
                            new ArrayList<>(rm.getInstalledReasonerFactories());
                    infos.sort(Comparator
                            .comparing(ProtegeOWLReasonerInfo::getReasonerName,
                                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                            .thenComparing(ProtegeOWLReasonerInfo::getReasonerId,
                                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
                    List<Map<String, Object>> reasoners = new ArrayList<>();
                    for (ProtegeOWLReasonerInfo info : infos) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", info.getReasonerName());
                        entry.put("id", info.getReasonerId());
                        entry.put("current", Objects.equals(info.getReasonerId(), currentId));
                        reasoners.add(entry);
                    }
                    if (reasoners.isEmpty()) {
                        return Tools.error("No reasoner plugins are installed in Protégé.");
                    }
                    return Tools.json()
                            .put("count", reasoners.size())
                            .put("reasoners", reasoners)
                            .putIfNotNull("current_id", currentId)
                            .result();
                }));

        tools.tool("set_reasoner",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String ref = Tools.reqString(a, "reasoner");
                    return WriteTools.write(ctx, "set reasoner to " + ref, mm -> {
                        OWLReasonerManager rm = mm.getOWLReasonerManager();
                        TreeSet<String> available = new TreeSet<>();
                        // Candidates and infos stay index-parallel: the winning candidate maps back
                        // to ITS factory even when several factories share one display name (the
                        // name alone is not a key — only an exact id reference resolves then).
                        List<ReasonerNames.Candidate> installed = new ArrayList<>();
                        List<ProtegeOWLReasonerInfo> infos = new ArrayList<>();
                        for (ProtegeOWLReasonerInfo info : rm.getInstalledReasonerFactories()) {
                            available.add(info.getReasonerName() + " [" + info.getReasonerId() + "]");
                            installed.add(new ReasonerNames.Candidate(
                                    info.getReasonerName(), info.getReasonerId()));
                            infos.add(info);
                        }
                        ReasonerNames.Resolution resolution = ReasonerNames.resolve(ref, installed);
                        if (resolution.ambiguous()) {
                            return Tools.error("Reasoner reference '" + ref
                                    + "' matches more than one installed reasoner ("
                                    + String.join(", ", resolution.candidateLabels())
                                    + "). Use a full display name or factory id that identifies one"
                                    + " installed reasoner.");
                        }
                        if (!resolution.unique()) {
                            return Tools.error("No reasoner matches '" + ref + "'. Available: "
                                    + String.join(", ", available) + ".");
                        }
                        ProtegeOWLReasonerInfo match =
                                infos.get(installed.indexOf(resolution.match()));
                        rm.setCurrentReasonerFactoryId(match.getReasonerId());
                        Map<String, Object> selected = new LinkedHashMap<>();
                        selected.put("name", match.getReasonerName());
                        selected.put("id", match.getReasonerId());
                        return Tools.json()
                                .put("selected", selected)
                                .put("message", "Selected reasoner. Call run_reasoner to classify.")
                                .result();
                    });
                });
    }

    /**
     * A standalone ontology, in a private throwaway manager, holding the union of the active
     * ontology's imports-closure axioms. The explanation generator mutates this copy (not Protégé's
     * live model) during its hitting-set search, and the whole private manager — copy and any
     * temporary reasoners — is discarded when the call returns.
     */
    private static OWLOntology isolatedClosure(OWLOntology active) {
        Set<OWLAxiom> axioms = new HashSet<>();
        for (OWLOntology o : active.getImportsClosure()) {
            axioms.addAll(o.getAxioms());
        }
        OWLOntologyManager priv = OwlManagers.create();
        try {
            return priv.createOntology(axioms);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not prepare an explanation workspace: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static Map<String, Object> explanationsJson(OWLModelManager mm, OWLAxiom ax,
            Set<Set<OWLAxiom>> explanations) {
        List<Map<String, Object>> justifications = new ArrayList<>();
        for (Set<OWLAxiom> justification : explanations) {
            List<Map<String, Object>> axioms = new ArrayList<>();
            for (OWLAxiom jx : justification) {
                axioms.add(Tools.axiomJson(mm, jx));
            }
            axioms.sort(java.util.Comparator.comparing(a -> String.valueOf(a.get("rendering"))));
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("size", justification.size());
            j.put("axioms", axioms);
            justifications.add(j);
        }
        return Tools.json()
                .put("axiom", Tools.axiomJson(mm, ax))
                .put("entailed", !explanations.isEmpty())
                .put("justification_count", explanations.size())
                .put("justifications", justifications)
                .map();
    }

    /**
     * The fallback for an axiom_type the justification generator cannot handle: report whether the
     * reasoner entails the axiom and, when it does, the asserted logical axioms in the imports closure
     * that mention the same entities — a structural neighbourhood to inspect, explicitly NOT a minimal
     * justification. (The clarkparsia SatisfiabilityConverter only converts the {@link
     * #EXPLAINABLE_AXIOM_TYPES}; other entailments would otherwise get no answer at all.)
     */
    static Map<String, Object> structuralExplanation(OWLModelManager mm, OWLAxiom ax,
            String axiomType, boolean entailed) {
        Tools.Json json = Tools.json()
                .put("axiom", Tools.axiomJson(mm, ax))
                .put("entailed", entailed)
                .put("justification_available", false)
                .put("note", "No minimal justification is available for axiom_type '" + axiomType
                        + "' (the explanation generator supports only: "
                        + String.join(", ", EXPLAINABLE_AXIOM_TYPES) + "). "
                        + (entailed
                                ? "The axiom is entailed; 'related_axioms' lists the asserted axioms "
                                        + "that mention the same entities — a structural neighbourhood to "
                                        + "inspect, not a minimal justification."
                                : "The axiom is NOT entailed, so there is nothing to justify."));
        if (entailed) {
            Set<OWLAxiom> related = relatedAssertedAxioms(mm.getActiveOntology(), ax);
            json.put("related_axioms", Tools.axiomList(mm, related, RELATED_AXIOM_SAMPLE));
        }
        return json.map();
    }

    /** Structural-context sample sizes: how many referencing axioms to collect / to render. */
    private static final int RELATED_AXIOM_SAMPLE = 50;
    private static final int RELATED_AXIOM_CAP = 500;

    /**
     * Asserted logical axioms across the imports closure that reference any entity in {@code ax}, capped
     * at {@link #RELATED_AXIOM_CAP}. The cap is on the COLLECTION (not just the rendered sample) so a
     * heavily-referenced entity in a large closure cannot make the on-EDT rendering cost proportional to
     * its full referencing set — this is a best-effort neighbourhood, not an exhaustive listing.
     */
    static Set<OWLAxiom> relatedAssertedAxioms(OWLOntology active, OWLAxiom ax) {
        Set<OWLAxiom> out = new LinkedHashSet<>();
        for (OWLEntity e : ax.getSignature()) {
            if (e.isBuiltIn()) {
                continue;
            }
            for (OWLOntology o : active.getImportsClosure()) {
                for (OWLAxiom ref : o.getReferencingAxioms(e)) {
                    if (ref.isLogicalAxiom()) {
                        out.add(ref);
                        if (out.size() >= RELATED_AXIOM_CAP) {
                            return out;
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---- Reasoner-backed cores, split out with the reasoner injected so they can be exercised
    // ---- headless against an OWL API StructuralReasoner (see the *_internal tests). Each returns the
    // ---- raw result map; the tool bodies wrap it with Tools.ok(...) exactly as Json.result() would.

    /** Unsatisfiable (≡ owl:Nothing) classes as {@code {count, items, coherent}}. */
    static Map<String, Object> unsatisfiableClasses(OWLModelManager mm, OWLReasoner r) {
        Set<OWLClass> unsat = r.getUnsatisfiableClasses().getEntitiesMinusBottom();
        Map<String, Object> result = Tools.entityList(mm, unsat, Integer.MAX_VALUE);
        result.put("coherent", unsat.isEmpty());
        return result;
    }

    /** Inferred {@code superclasses|subclasses|equivalent|types|instances} for {@code entity}. */
    static Map<String, Object> inferredRelation(OWLModelManager mm, OWLReasoner r, String entity,
            String rel, boolean direct) {
        Set<? extends OWLEntity> result;
        switch (rel) {
            case "subclasses":
                result = r.getSubClasses(Tools.resolveClass(mm, entity), direct).getFlattened();
                break;
            case "equivalent":
                result = r.getEquivalentClasses(Tools.resolveClass(mm, entity)).getEntities();
                break;
            case "types":
                OWLNamedIndividual ind = Tools.resolveIndividual(mm, entity);
                result = r.getTypes(ind, direct).getFlattened();
                break;
            case "instances":
                result = r.getInstances(Tools.resolveClass(mm, entity), direct).getFlattened();
                break;
            case "superclasses":
            default:
                result = r.getSuperClasses(Tools.resolveClass(mm, entity), direct).getFlattened();
                break;
        }
        Map<String, Object> out = Tools.entityList(mm, result, Integer.MAX_VALUE);
        out.put("relation", rel);
        out.put("entity", entity);
        out.put("direct", direct);
        return out;
    }

    /** Whether the reasoner entails {@code ax}, as {@code {entailed, axiom}}. */
    static Map<String, Object> explainEntailment(OWLModelManager mm, OWLReasoner r, OWLAxiom ax) {
        return Tools.json()
                .put("entailed", r.isEntailed(ax))
                .put("axiom", Tools.axiomJson(mm, ax))
                .map();
    }

    /** DL-query results (equivalent/super/sub/instances) for the resolved class expression {@code ce}. */
    static Map<String, Object> dlQuery(OWLModelManager mm, OWLReasoner r, String query,
            OWLClassExpression ce, String rel, boolean direct, boolean complete) {
        boolean all = "all".equals(rel);
        boolean subOrSuper = all || "subclasses".equals(rel) || "superclasses".equals(rel);
        // The combination where a reasoner (notably ELK) can under-report a COMPLEX expression:
        // subclass/superclass of an anonymous class expression, non-direct.
        boolean gap = ce.isAnonymous() && !direct && subOrSuper;
        boolean completed = complete && gap;
        Tools.Json json = Tools.json().put("query", query).put("direct", direct);
        if (all || "equivalent".equals(rel)) {
            json.put("equivalent",
                    Tools.entityList(mm, r.getEquivalentClasses(ce).getEntities(), Integer.MAX_VALUE));
        }
        if (all || "superclasses".equals(rel)) {
            Set<OWLClass> sup = completed
                    ? completeSuperClasses(r, ce)
                    : r.getSuperClasses(ce, direct).getFlattened();
            json.put("superclasses", Tools.entityList(mm, sup, Integer.MAX_VALUE));
        }
        if (all || "subclasses".equals(rel)) {
            Set<OWLClass> sub = completed
                    ? completeSubClasses(r, ce)
                    : r.getSubClasses(ce, direct).getFlattened();
            json.put("subclasses", Tools.entityList(mm, sub, Integer.MAX_VALUE));
        }
        if (all || "instances".equals(rel)) {
            json.put("instances",
                    Tools.entityList(mm, r.getInstances(ce, direct).getFlattened(), Integer.MAX_VALUE));
        }
        if (completed) {
            json.put("completed", true);
            json.put("note", "Complex-expression sub/superclasses were reconstructed as the reasoner's "
                    + "direct results unioned with each direct class's transitive descent, so the set is "
                    + "exhaustive but goes beyond a single raw reasoner call (and beyond what Protégé's DL "
                    + "Query tab shows for this reasoner).");
        } else if (gap && isElkReasoner(r)) {
            json.put("warning", "The active reasoner (ELK) can return an INCOMPLETE set of sub/superclasses "
                    + "for a complex (anonymous) class expression with direct=false — the DIRECT level may "
                    + "be missing (Protégé's DL Query tab shows the same). Re-run with direct=true to see the "
                    + "direct level, set complete=true to get the exhaustive set, or classify with a DL "
                    + "reasoner such as HermiT.");
        }
        return json.map();
    }

    /** Whether {@code r} is the ELK reasoner (which under-reports complex-expression sub/superclasses). */
    private static boolean isElkReasoner(OWLReasoner r) {
        return isElkName(r.getReasonerName());
    }

    private static boolean isElkName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("elk");
    }

    /**
     * Advisory for run_reasoner / run_qc_suite when the SELECTED reasoner silently ignores SWRL
     * rules: ELK has no SWRL support, so classification succeeds but every rule-derived inference
     * is missing — invisible unless surfaced (HermiT, by contrast, fails loudly on unsupported
     * built-in atoms, which classification_failed already reports). Returns null when there is
     * nothing to warn about. Matches the selected FACTORY (name and id), not the live reasoner
     * instance — the instance reads "Protégé Null Reasoner" before classification and after a
     * failed one, exactly when this warning matters.
     */
    static String swrlIgnoredWarning(OWLModelManager mm) {
        Set<OWLOntology> closure = mm.getActiveOntology().getImportsClosure();
        int rules = closure.stream().mapToInt(o -> o.getAxiomCount(AxiomType.SWRL_RULE)).sum();
        if (rules == 0) {
            return null;
        }
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        String id = rm.getCurrentReasonerFactoryId();
        String name = null;
        if (id != null) {
            for (ProtegeOWLReasonerInfo info : rm.getInstalledReasonerFactories()) {
                if (id.equals(info.getReasonerId())) {
                    name = info.getReasonerName();
                    break;
                }
            }
        }
        return swrlIgnoredWarning(rules, name, id);
    }

    /** Snapshot-safe counterpart used after the live model boundary has been released. */
    static String swrlIgnoredWarning(Set<OWLOntology> closure, String name, String id) {
        int rules = closure.stream().mapToInt(o -> o.getAxiomCount(AxiomType.SWRL_RULE)).sum();
        return swrlIgnoredWarning(rules, name, id);
    }

    private static String swrlIgnoredWarning(int rules, String name, String id) {
        if (rules == 0 || (!isElkName(name) && !isElkName(id))) {
            return null;
        }
        return swrlIgnoredWarning(rules, name != null ? name : id);
    }

    /** The warning text itself; parameterized so tests need no live reasoner manager. */
    static String swrlIgnoredWarning(int rules, String reasonerName) {
        return "The ontology (with imports) contains " + rules + " SWRL rule"
                + (rules == 1 ? "" : "s") + ", but the selected reasoner (" + reasonerName
                + ") does not support SWRL and silently IGNORES rules — these results include no "
                + "rule-derived inferences. Use a rule-aware reasoner (e.g. Pellet, or HermiT for "
                + "rules without built-in atoms) when the rules matter.";
    }

    /**
     * Exhaustive subclasses of a (possibly anonymous) class expression, robust to reasoners that
     * under-report complex-expression queries: the reasoner's own non-direct and direct results, plus
     * the transitive descent of each direct subclass (getSubClasses of a NAMED class is reliable even
     * for ELK). owl:Nothing is a subclass of everything, so it is carried through but not descended.
     */
    private static Set<OWLClass> completeSubClasses(OWLReasoner r, OWLClassExpression ce) {
        Set<OWLClass> out = new HashSet<>(r.getSubClasses(ce, false).getFlattened());
        for (OWLClass direct : r.getSubClasses(ce, true).getFlattened()) {
            out.add(direct);
            if (!direct.isOWLNothing()) {
                out.addAll(r.getSubClasses(direct, false).getFlattened());
            }
        }
        return out;
    }

    /** Exhaustive superclasses of {@code ce}; the {@link #completeSubClasses} counterpart (ascends). */
    private static Set<OWLClass> completeSuperClasses(OWLReasoner r, OWLClassExpression ce) {
        Set<OWLClass> out = new HashSet<>(r.getSuperClasses(ce, false).getFlattened());
        for (OWLClass direct : r.getSuperClasses(ce, true).getFlattened()) {
            out.add(direct);
            if (!direct.isOWLThing()) {
                out.addAll(r.getSuperClasses(direct, false).getFlattened());
            }
        }
        return out;
    }

    /**
     * Run {@code body} and convert the {@link InconsistentOntologyException} reasoners throw for
     * queries over an inconsistent ontology into a pointed, actionable error ({@code what} names
     * the failing tool). Without this, callers get the raw one-line exception and no way forward.
     */
    private static CallToolResult guardInconsistency(String what,
            Supplier<CallToolResult> body) {
        try {
            return body.get();
        } catch (InconsistentOntologyException e) {
            return Tools.error("The ontology is INCONSISTENT, so " + what + " cannot run (an "
                    + "inconsistent ontology entails everything, and reasoners refuse such "
                    + "queries). Run explain_inconsistency to find a minimal set of axioms "
                    + "causing the contradiction.");
        }
    }

    /** Outcome of {@link #findInconsistentSubset}: a still-inconsistent axiom set, or 'consistent'. */
    static final class InconsistencySearch {
        boolean consistent;
        boolean minimal;
        int checks;
        List<OWLAxiom> axioms = new ArrayList<>();
    }

    /** Fast-pruning window count: candidates are dropped in chunks of size/N before one-by-one. */
    private static final int FAST_PRUNE_WINDOWS = 20;
    /** How many justification axioms to render (a MINIMAL set is far smaller in practice). */
    private static final int INCONSISTENCY_RENDER_CAP = 100;

    /**
     * Contraction-only search for a minimal inconsistent subset of {@code working}'s logical
     * axioms. Uses {@code OWLReasoner.isConsistent()} as the sole oracle — the one query reasoners
     * answer over an inconsistent ontology without throwing {@link InconsistentOntologyException}
     * (the clarkparsia black-box generator cannot be used here: its very first satisfiability
     * probe throws). Correctness rests on monotonicity — every subset of a consistent set is
     * consistent — so an axiom verified droppable/necessary against the current candidate set
     * stays so for every later, smaller set; one fast window pass plus one one-by-one pass
     * therefore yields a genuinely minimal set. Time-bounded: on expiry the current (still
     * jointly inconsistent) candidates are returned with {@code minimal=false}.
     */
    static InconsistencySearch findInconsistentSubset(OWLOntology working,
            OWLReasonerFactory factory, long budgetMs) {
        return findInconsistentSubset(working,
                ontology -> factory.createNonBufferingReasoner(ontology), budgetMs);
    }

    /** Configuration-equivalent production path for isolated inconsistency probes. */
    static InconsistencySearch findInconsistentSubset(OWLOntology working,
            IsolatedReasonerSpec spec, long budgetMs) {
        return findInconsistentSubset(working, spec::create, budgetMs);
    }

    private static InconsistencySearch findInconsistentSubset(OWLOntology working,
            ReasonerCreator creator, long budgetMs) {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        OWLOntologyManager mgr = working.getOWLOntologyManager();
        InconsistencySearch s = new InconsistencySearch();
        List<OWLAxiom> current = new ArrayList<>(working.getLogicalAxioms());
        // The INITIAL full-set probe must not swallow a reasoner failure: answering "consistent —
        // nothing to explain" about an ontology the reasoner could not even evaluate is a wrong
        // verdict (e.g. HermiT throwing on a SWRL built-in atom). Inside the prune loops a throw
        // only means "keep the chunk" — conservative and correct — so those stay swallowed.
        if (!subsetInconsistent(mgr, creator, current, s, true)) {
            s.consistent = true;
            return s;
        }
        // Fast pruning: drop window-sized chunks whose removal keeps the rest inconsistent.
        int window = Math.max(current.size() / FAST_PRUNE_WINDOWS, 1);
        if (window > 1) {
            for (int i = 0; i < current.size();) {
                if (System.nanoTime() > deadline) {
                    s.axioms = current;
                    return s;
                }
                int end = Math.min(i + window, current.size());
                List<OWLAxiom> rest = new ArrayList<>(current.subList(0, i));
                rest.addAll(current.subList(end, current.size()));
                if (subsetInconsistent(mgr, creator, rest, s, false)) {
                    current = rest;            // chunk dropped; next chunk now starts at i
                } else {
                    i = end;                   // chunk (partly) needed; keep and move on
                }
            }
        }
        // Slow pruning: drop axioms one by one; what survives a full pass is minimal.
        for (int i = 0; i < current.size();) {
            if (System.nanoTime() > deadline) {
                s.axioms = current;
                return s;
            }
            List<OWLAxiom> rest = new ArrayList<>(current);
            rest.remove(i);
            if (subsetInconsistent(mgr, creator, rest, s, false)) {
                current = rest;
            } else {
                i++;
            }
        }
        s.minimal = true;
        s.axioms = current;
        return s;
    }

    /**
     * Whether {@code axioms} alone are inconsistent under a fresh reasoner over a throwaway
     * ontology. On a reasoner failure: with {@code rethrowFailure} the error surfaces as a
     * {@link ToolArgException} (the initial full-set probe, where a swallow would misreport
     * "consistent"); without it the failure counts as consistent — conservative: the caller then
     * keeps the axioms it tried to drop, preserving the candidate set's inconsistency invariant.
     */
    private static boolean subsetInconsistent(OWLOntologyManager mgr, ReasonerCreator creator,
            Collection<OWLAxiom> axioms, InconsistencySearch s, boolean rethrowFailure) {
        s.checks++;
        OWLOntology probe = null;
        OWLReasoner r = null;
        try {
            probe = mgr.createOntology(new LinkedHashSet<>(axioms));
            r = creator.create(probe);
            return !r.isConsistent();
        } catch (OWLOntologyCreationException | RuntimeException e) {
            if (rethrowFailure) {
                throw new ToolArgException("The selected reasoner could not evaluate the ontology: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        + ". Choose another reasoner (set_reasoner) and re-run "
                        + "explain_inconsistency.");
            }
            return false;
        } finally {
            if (r != null) {
                try {
                    r.dispose();
                } catch (RuntimeException ignored) {
                    // a broken reasoner must not mask the probe result
                }
            }
            if (probe != null) {
                mgr.removeOntology(probe);
            }
        }
    }

    @FunctionalInterface
    private interface ReasonerCreator {
        OWLReasoner create(OWLOntology ontology);
    }

    /**
     * Tracks reasoners hidden inside the OWLAPI explanation engine so timeout cleanup is possible.
     * Interrupting the already-created instances is not enough on its own: the black-box search
     * creates a FRESH reasoner per satisfiability probe and never checks the thread's interrupt
     * flag, so an abandoned search would keep grinding on new reasoners indefinitely — a runaway
     * daemon thread per timed-out call. {@link #cancel()} therefore also latches the factory shut:
     * every subsequent create call fails fast with {@link ReasonerInterruptedException}, an
     * unchecked exception the clarkparsia generators do not catch (their probe swallows only
     * {@code IllegalArgumentException}), so the abandoned search dies at its next probe. A create
     * that raced past the latch is interrupted, disposed, and dropped rather than returned, so
     * nothing accumulates after cancellation.
     */
    static final class TrackingReasonerFactory implements OWLReasonerFactory {
        private final OWLReasonerFactory delegate;
        private final Set<OWLReasoner> instances = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile boolean cancelled;

        TrackingReasonerFactory(OWLReasonerFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getReasonerName() {
            return delegate.getReasonerName();
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            failIfCancelled();
            return track(delegate.createReasoner(ontology));
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            failIfCancelled();
            return track(delegate.createNonBufferingReasoner(ontology));
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology,
                org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration configuration) {
            failIfCancelled();
            return track(delegate.createReasoner(ontology, configuration));
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology,
                org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration configuration) {
            failIfCancelled();
            return track(delegate.createNonBufferingReasoner(ontology, configuration));
        }

        /** Once cancelled the abandoned search must die at its next reasoner request. */
        private void failIfCancelled() {
            if (cancelled) {
                throw new ReasonerInterruptedException("The private explanation search was "
                        + "cancelled; it may not create further reasoners.");
            }
        }

        private OWLReasoner track(OWLReasoner reasoner) {
            instances.add(reasoner);
            // Re-check AFTER registering: a create already in flight when cancel() ran passed the
            // fail-fast check and may also have registered after the interrupt sweep. Contain it
            // here — interrupt, dispose, drop — instead of handing it to the abandoned search.
            if (cancelled) {
                instances.remove(reasoner);
                interruptQuietly(reasoner);
                disposeQuietly(reasoner);
                failIfCancelled();
            }
            return reasoner;
        }

        /**
         * Cancel the private search: interrupt every reasoner created so far and refuse all
         * further creation. Disposal of the swept instances stays with the worker's {@code
         * finally} ({@link #disposeAll()}) — disposing here could race a probe still inside a
         * reasoner call; interrupt is the reasoner API's sanctioned cross-thread signal.
         */
        void cancel() {
            cancelled = true;
            for (OWLReasoner reasoner : instances) {
                interruptQuietly(reasoner);
            }
        }

        void disposeAll() {
            for (OWLReasoner reasoner : instances) {
                disposeQuietly(reasoner);
            }
            instances.clear();
        }

        private static void interruptQuietly(OWLReasoner reasoner) {
            try {
                reasoner.interrupt();
            } catch (RuntimeException ignored) {
                // A non-interruptible reasoner remains confined to the daemon/private workspace.
            }
        }

        private static void disposeQuietly(OWLReasoner reasoner) {
            try {
                reasoner.dispose();
            } catch (RuntimeException ignored) {
                // Cleanup must not replace the explanation result.
            }
        }
    }

    /** Render the search outcome (on the EDT — the entity renderer is not thread-safe). */
    static Map<String, Object> inconsistencyJson(OWLModelManager mm, InconsistencySearch s,
            String reasonerName) {
        if (s.consistent) {
            return Tools.json()
                    .put("inconsistent", false)
                    .put("reasoner", reasonerName)
                    .put("note", "The ontology (imports closure) is consistent under " + reasonerName
                            + " — there is no inconsistency to explain.")
                    .map();
        }
        // Cap the COLLECTION before rendering, not just the rendered window: axiomList renders
        // every input axiom to build its sort key, and a budget-expired result can still hold
        // thousands of axioms — rendering them all would stall the EDT (mirrors
        // relatedAssertedAxioms' collection cap). The true size is reported as axiom_count.
        List<OWLAxiom> capped = s.axioms.size() > INCONSISTENCY_RENDER_CAP
                ? s.axioms.subList(0, INCONSISTENCY_RENDER_CAP)
                : s.axioms;
        return Tools.json()
                .put("inconsistent", true)
                .put("reasoner", reasonerName)
                .put("minimal", s.minimal)
                .put("consistency_checks", s.checks)
                .put("axiom_count", s.axioms.size())
                .put("justification", Tools.axiomList(mm,
                        new LinkedHashSet<>(capped), INCONSISTENCY_RENDER_CAP))
                .put("note", s.minimal
                        ? "A minimal set of asserted logical axioms that are jointly inconsistent: "
                                + "removing any one of them breaks THIS contradiction (others may "
                                + "remain — fix and re-run). Note a reasoner that ignores axioms it "
                                + "does not support (e.g. ELK) minimizes only what it sees."
                        : "The time budget expired before full minimization: the listed axioms are "
                                + "still jointly inconsistent but not necessarily all needed. "
                                + "Re-run with a larger timeout_ms, or extract_module around the "
                                + "suspect terms and diagnose the smaller module.")
                .map();
    }

    private static OWLReasoner requireReasoner(OWLModelManager mm) {
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        ReasonerStatus status = rm.getReasonerStatus();
        if (status == ReasonerStatus.NO_REASONER_FACTORY_CHOSEN) {
            throw new ToolArgException("No reasoner is selected in Protégé (Reasoner menu).");
        }
        if (!status.isEnableStop()) {
            throw new ToolArgException("The reasoner has no current results — run_reasoner was not called, "
                    + "or the last classification failed and reset to the Null reasoner (check "
                    + "~/.Protege/logs/protege.log). Run run_reasoner first.");
        }
        return mm.getReasoner();
    }
}
