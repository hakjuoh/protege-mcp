package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import io.github.hakjuoh.server.EmbeddedClassificationWaiter;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import com.clarkparsia.owlapi.explanation.DefaultExplanationGenerator;
import com.clarkparsia.owlapi.explanation.util.SilentExplanationProgressMonitor;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

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

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("run_reasoner",
                "Run the reasoner selected in Protégé (classify) and wait for completion. Reports the "
                        + "resulting status and unsatisfiable-class count.",
                Tools.schema().integer("timeout_ms", "Max wait in ms (default 60000).").build(),
                (ex, req) -> Tools.guard(() -> {
                    int timeout = Tools.optInt(Tools.args(req), "timeout_ms", 60_000);
                    String pre = ctx.access().compute(mm -> mm.getOWLReasonerManager()
                            .getReasonerStatus() == ReasonerStatus.NO_REASONER_FACTORY_CHOSEN ? "none" : null);
                    if ("none".equals(pre)) {
                        return Tools.error("No reasoner is selected in Protégé. Choose one from the "
                                + "Reasoner menu, then retry.");
                    }
                    return Tools.ok(EmbeddedClassificationWaiter.runAndWait(ctx.access(), timeout));
                })));

        tools.add(ToolSpecs.of("get_unsatisfiable_classes",
                "List unsatisfiable (equivalent to owl:Nothing) classes from the active reasoner.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> ctx.access().compute(mm -> {
                    OWLReasoner r = requireReasoner(mm);
                    Set<OWLClass> unsat = r.getUnsatisfiableClasses().getEntitiesMinusBottom();
                    Map<String, Object> result = Tools.entityList(mm, unsat, Integer.MAX_VALUE);
                    result.put("coherent", unsat.isEmpty());
                    return Tools.ok(result);
                }))));

        tools.add(ToolSpecs.of("get_inferred_superclasses",
                "Read inferred relations from the reasoner. 'relation' is one of superclasses "
                        + "(default), subclasses, equivalent, types (for an individual), instances "
                        + "(of a class). 'direct' limits to direct relations (default true).",
                Tools.schema()
                        .strReq("entity", "Class IRI/name (or individual for 'types').")
                        .str("relation", "superclasses | subclasses | equivalent | types | instances")
                        .bool("direct", "Direct relations only (default true).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String entity = Tools.reqString(a, "entity");
                    String relation = Tools.optString(a, "relation");
                    boolean direct = Tools.optBool(a, "direct", true);
                    String rel = relation == null ? "superclasses" : relation.toLowerCase();
                    return ctx.access().compute(mm -> {
                        OWLReasoner r = requireReasoner(mm);
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
                        return Tools.ok(out);
                    });
                })));

        tools.add(ToolSpecs.of("explain_entailment",
                "Check whether a structured axiom is entailed by the active reasoner (true/false). "
                        + "axiom_type is one of: " + Axioms.SUPPORTED + ". Use get_explanations for "
                        + "the justifications behind an entailment.",
                Axioms.schema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    return ctx.access().compute(mm -> {
                        OWLReasoner r = requireReasoner(mm);
                        OWLAxiom ax = Axioms.build(mm, a);
                        boolean entailed = r.isEntailed(ax);
                        return Tools.json()
                                .put("entailed", entailed)
                                .put("axiom", Tools.axiomJson(mm, ax))
                                .result();
                    });
                })));

        tools.add(ToolSpecs.of("get_explanations",
                "Explain WHY a structured axiom is entailed: return one or more justifications "
                        + "(minimal sets of asserted axioms that together entail it). Minimal "
                        + "justifications are computed for these axiom_type values: "
                        + String.join(", ", EXPLAINABLE_AXIOM_TYPES) + ". For any OTHER axiom_type "
                        + "(e.g. a property-hierarchy or property-characteristic entailment) it falls "
                        + "back to confirming whether the axiom is entailed and returning the asserted "
                        + "axioms that mention the same entities as structural context (not a minimal "
                        + "justification). To explain why a class C is unsatisfiable, use "
                        + "axiom_type=class_assertion with class=C and any individual, or subclass_of "
                        + "with sub=C, super=\"owl:Nothing\". Requires a reasoner (see list_reasoners / "
                        + "set_reasoner).",
                explanationsSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String axiomType = Tools.reqString(a, "axiom_type").toLowerCase();
                    if (!EXPLAINABLE_AXIOM_TYPES.contains(axiomType)) {
                        int fallbackTimeout = Tools.optInt(a, "timeout_ms", 60_000);
                        return ctx.access().compute(mm -> {
                            OWLReasoner r = requireReasoner(mm);
                            OWLAxiom ax = Axioms.build(mm, a);
                            return Tools.ok(structuralExplanation(mm, ax, axiomType, r.isEntailed(ax)));
                        }, fallbackTimeout);
                    }
                    int max = Tools.optInt(a, "max", 3);
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    return ctx.access().compute(mm -> {
                        OWLReasonerManager rm = mm.getOWLReasonerManager();
                        if (rm.getReasonerStatus() == ReasonerStatus.NO_REASONER_FACTORY_CHOSEN) {
                            return Tools.error("No reasoner is selected in Protégé. Use set_reasoner "
                                    + "(or the Reasoner menu) to choose one first.");
                        }
                        OWLReasonerFactory factory = rm.getCurrentReasonerFactory().getReasonerFactory();
                        OWLAxiom ax = Axioms.build(mm, a);
                        // Isolate the search from Protégé's live model: the multi-justification (HST)
                        // search transiently removes/re-adds real axioms via the ontology's manager,
                        // which would otherwise land on Protégé's undo stack and dirty-state and leak a
                        // throwaway debugging ontology into the live manager. Run over a private copy of
                        // the active ontology's imports closure instead — all churn and the temporary
                        // reasoners then live in a manager we discard.
                        OWLOntology working = isolatedClosure(mm.getActiveOntology());
                        DefaultExplanationGenerator gen = new DefaultExplanationGenerator(
                                working.getOWLOntologyManager(), factory, working,
                                new SilentExplanationProgressMonitor());
                        Set<Set<OWLAxiom>> explanations = max > 0
                                ? gen.getExplanations(ax, max)
                                : gen.getExplanations(ax);
                        return Tools.ok(explanationsJson(mm, ax, explanations));
                    }, timeout);
                })));

        tools.add(ToolSpecs.of("execute_dl_query",
                "Run a Protégé DL Query: given a Manchester-syntax class expression, return the "
                        + "reasoner's equivalent classes, subclasses, superclasses, and instances. "
                        + "'relation' limits the result (equivalent | subclasses | superclasses | "
                        + "instances | all, default all); 'direct' limits sub/super/instance results "
                        + "to direct ones (default true). Call run_reasoner first.",
                Tools.schema()
                        .strReq("query", "Manchester-syntax class expression, e.g. "
                                + "\"hasOwner some Person\" or \"Animal and (hasOwner some Person)\".")
                        .str("relation", "equivalent | subclasses | superclasses | instances | all "
                                + "(default all).")
                        .bool("direct", "Direct results only for sub/super/instances (default true).")
                        .integer("timeout_ms", "Max wait in ms for the query (default 60000).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String query = Tools.reqString(a, "query");
                    String relation = Tools.optString(a, "relation");
                    String rel = relation == null ? "all" : relation.toLowerCase();
                    boolean direct = Tools.optBool(a, "direct", true);
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    return ctx.access().compute(mm -> {
                        OWLReasoner r = requireReasoner(mm);
                        OWLClassExpression ce = Tools.resolveClassExpression(mm, query);
                        boolean all = "all".equals(rel);
                        Tools.Json json = Tools.json().put("query", query).put("direct", direct);
                        if (all || "equivalent".equals(rel)) {
                            json.put("equivalent",
                                    Tools.entityList(mm, r.getEquivalentClasses(ce).getEntities(), Integer.MAX_VALUE));
                        }
                        if (all || "superclasses".equals(rel)) {
                            json.put("superclasses",
                                    Tools.entityList(mm, r.getSuperClasses(ce, direct).getFlattened(), Integer.MAX_VALUE));
                        }
                        if (all || "subclasses".equals(rel)) {
                            json.put("subclasses",
                                    Tools.entityList(mm, r.getSubClasses(ce, direct).getFlattened(), Integer.MAX_VALUE));
                        }
                        if (all || "instances".equals(rel)) {
                            json.put("instances",
                                    Tools.entityList(mm, r.getInstances(ce, direct).getFlattened(), Integer.MAX_VALUE));
                        }
                        return json.result();
                    }, timeout);
                })));

        tools.add(ToolSpecs.of("list_reasoners",
                "List the reasoner plugins installed in Protégé and mark the one currently selected. "
                        + "Use set_reasoner to change it, then run_reasoner to classify.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> ctx.access().compute(mm -> {
                    OWLReasonerManager rm = mm.getOWLReasonerManager();
                    String currentId = rm.getCurrentReasonerFactoryId();
                    TreeMap<String, Map<String, Object>> byName = new TreeMap<>();
                    for (ProtegeOWLReasonerInfo info : rm.getInstalledReasonerFactories()) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", info.getReasonerName());
                        entry.put("id", info.getReasonerId());
                        entry.put("current", info.getReasonerId().equals(currentId));
                        byName.put(info.getReasonerName(), entry);
                    }
                    if (byName.isEmpty()) {
                        return Tools.error("No reasoner plugins are installed in Protégé.");
                    }
                    return Tools.json()
                            .put("count", byName.size())
                            .put("reasoners", new ArrayList<>(byName.values()))
                            .putIfNotNull("current_id", currentId)
                            .result();
                }))));

        tools.add(ToolSpecs.of("set_reasoner",
                "Select the reasoner Protégé will use, by id or name (see list_reasoners). This does "
                        + "not classify — call run_reasoner afterwards.",
                Tools.schema().strReq("reasoner", "Reasoner id or name (see list_reasoners).").build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String ref = Tools.reqString(a, "reasoner");
                    return WriteTools.write(ctx, "set reasoner to " + ref, mm -> {
                        OWLReasonerManager rm = mm.getOWLReasonerManager();
                        ProtegeOWLReasonerInfo match = null;
                        TreeSet<String> available = new TreeSet<>();
                        for (ProtegeOWLReasonerInfo info : rm.getInstalledReasonerFactories()) {
                            available.add(info.getReasonerName() + " [" + info.getReasonerId() + "]");
                            if (info.getReasonerId().equalsIgnoreCase(ref)
                                    || info.getReasonerName().equalsIgnoreCase(ref)) {
                                match = info;
                            }
                        }
                        if (match == null) {
                            return Tools.error("No reasoner matches '" + ref + "'. Available: "
                                    + String.join(", ", available) + ".");
                        }
                        rm.setCurrentReasonerFactoryId(match.getReasonerId());
                        Map<String, Object> selected = new LinkedHashMap<>();
                        selected.put("name", match.getReasonerName());
                        selected.put("id", match.getReasonerId());
                        return Tools.json()
                                .put("selected", selected)
                                .put("message", "Selected reasoner. Call run_reasoner to classify.")
                                .result();
                    });
                })));

        return tools;
    }

    /** Axiom schema (same operands as add_axiom) plus the explanation-search knobs. */
    private static Map<String, Object> explanationsSchema() {
        Map<String, Object> schema = Axioms.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        props.put("max", intProp("Maximum number of justifications to compute (default 3; 0 = all)."));
        props.put("timeout_ms", intProp("Max wait in ms for the explanation search (default 60000)."));
        return schema;
    }

    private static Map<String, Object> intProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", desc);
        return p;
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
        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
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
    private static Map<String, Object> structuralExplanation(OWLModelManager mm, OWLAxiom ax,
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
    private static Set<OWLAxiom> relatedAssertedAxioms(OWLOntology active, OWLAxiom ax) {
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

    private static OWLReasoner requireReasoner(OWLModelManager mm) {
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        ReasonerStatus status = rm.getReasonerStatus();
        if (status == ReasonerStatus.NO_REASONER_FACTORY_CHOSEN) {
            throw new ToolArgException("No reasoner is selected in Protégé (Reasoner menu).");
        }
        if (!status.isEnableStop()) {
            throw new ToolArgException("The reasoner has not produced results yet — call run_reasoner first.");
        }
        return mm.getReasoner();
    }
}
