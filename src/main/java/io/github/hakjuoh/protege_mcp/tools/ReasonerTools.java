package io.github.hakjuoh.protege_mcp.tools;

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
import io.github.hakjuoh.protege_mcp.server.EmbeddedClassificationWaiter;
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
                    return Tools.text(EmbeddedClassificationWaiter.runAndWait(ctx.access(), timeout));
                })));

        tools.add(ToolSpecs.of("get_unsatisfiable_classes",
                "List unsatisfiable (equivalent to owl:Nothing) classes from the active reasoner.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> ctx.access().compute(mm -> {
                    OWLReasoner r = requireReasoner(mm);
                    Set<OWLClass> unsat = r.getUnsatisfiableClasses().getEntitiesMinusBottom();
                    if (unsat.isEmpty()) {
                        return Tools.text("No unsatisfiable classes — the ontology is coherent.");
                    }
                    return Tools.text(render(mm, "Unsatisfiable classes", unsat));
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
                        String heading;
                        switch (rel) {
                            case "subclasses":
                                result = r.getSubClasses(Tools.resolveClass(mm, entity), direct).getFlattened();
                                heading = "Inferred subclasses";
                                break;
                            case "equivalent":
                                result = r.getEquivalentClasses(Tools.resolveClass(mm, entity)).getEntities();
                                heading = "Inferred equivalent classes";
                                break;
                            case "types":
                                OWLNamedIndividual ind = Tools.resolveIndividual(mm, entity);
                                result = r.getTypes(ind, direct).getFlattened();
                                heading = "Inferred types";
                                break;
                            case "instances":
                                result = r.getInstances(Tools.resolveClass(mm, entity), direct).getFlattened();
                                heading = "Inferred instances";
                                break;
                            case "superclasses":
                            default:
                                result = r.getSuperClasses(Tools.resolveClass(mm, entity), direct).getFlattened();
                                heading = "Inferred superclasses";
                                break;
                        }
                        return Tools.text(render(mm, heading + " of " + entity
                                + (direct ? " (direct)" : " (all)"), result));
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
                        return Tools.text("Entailed: " + entailed + "\n  " + Tools.renderAxiom(mm, ax));
                    });
                })));

        tools.add(ToolSpecs.of("get_explanations",
                "Explain WHY a structured axiom is entailed: return one or more justifications "
                        + "(minimal sets of asserted axioms that together entail it). Supported "
                        + "axiom_type values: " + String.join(", ", EXPLAINABLE_AXIOM_TYPES) + ". To "
                        + "explain why a class C is unsatisfiable, use axiom_type=class_assertion with "
                        + "class=C and any individual, or subclass_of with sub=C, super=\"owl:Nothing\". "
                        + "Requires a reasoner to be selected (see list_reasoners / set_reasoner).",
                explanationsSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String axiomType = Tools.reqString(a, "axiom_type").toLowerCase();
                    if (!EXPLAINABLE_AXIOM_TYPES.contains(axiomType)) {
                        return Tools.error("get_explanations supports these axiom types only: "
                                + String.join(", ", EXPLAINABLE_AXIOM_TYPES)
                                + ". For other entailments use explain_entailment (true/false).");
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
                        return Tools.text(renderExplanations(mm, ax, explanations));
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
                        StringBuilder sb = new StringBuilder("DL Query: ").append(query).append('\n');
                        boolean all = "all".equals(rel);
                        if (all || "equivalent".equals(rel)) {
                            sb.append(render(mm, "Equivalent classes",
                                    r.getEquivalentClasses(ce).getEntities())).append("\n\n");
                        }
                        if (all || "superclasses".equals(rel)) {
                            sb.append(render(mm, "Superclasses" + (direct ? " (direct)" : " (all)"),
                                    r.getSuperClasses(ce, direct).getFlattened())).append("\n\n");
                        }
                        if (all || "subclasses".equals(rel)) {
                            sb.append(render(mm, "Subclasses" + (direct ? " (direct)" : " (all)"),
                                    r.getSubClasses(ce, direct).getFlattened())).append("\n\n");
                        }
                        if (all || "instances".equals(rel)) {
                            sb.append(render(mm, "Instances" + (direct ? " (direct)" : " (all)"),
                                    r.getInstances(ce, direct).getFlattened())).append("\n\n");
                        }
                        return Tools.text(sb.toString().trim());
                    }, timeout);
                })));

        tools.add(ToolSpecs.of("list_reasoners",
                "List the reasoner plugins installed in Protégé and mark the one currently selected. "
                        + "Use set_reasoner to change it, then run_reasoner to classify.",
                Tools.emptySchema(),
                (ex, req) -> Tools.guard(() -> ctx.access().compute(mm -> {
                    OWLReasonerManager rm = mm.getOWLReasonerManager();
                    String currentId = rm.getCurrentReasonerFactoryId();
                    TreeMap<String, String> byName = new TreeMap<>();
                    for (ProtegeOWLReasonerInfo info : rm.getInstalledReasonerFactories()) {
                        boolean current = info.getReasonerId().equals(currentId);
                        byName.put(info.getReasonerName(), info.getReasonerName() + "  [" + info.getReasonerId()
                                + "]" + (current ? "  (current)" : ""));
                    }
                    if (byName.isEmpty()) {
                        return Tools.error("No reasoner plugins are installed in Protégé.");
                    }
                    StringBuilder sb = new StringBuilder("Installed reasoners (").append(byName.size())
                            .append("):\n");
                    for (String line : byName.values()) {
                        sb.append("  ").append(line).append('\n');
                    }
                    return Tools.text(sb.toString().trim());
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
                        return Tools.text("Selected reasoner: " + match.getReasonerName() + " ["
                                + match.getReasonerId() + "]. Call run_reasoner to classify.");
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

    private static String renderExplanations(OWLModelManager mm, OWLAxiom ax, Set<Set<OWLAxiom>> explanations) {
        if (explanations.isEmpty()) {
            return "Not entailed by the active ontology (no justification): " + Tools.renderAxiom(mm, ax);
        }
        StringBuilder sb = new StringBuilder("Axiom: ").append(Tools.renderAxiom(mm, ax)).append('\n');
        sb.append(explanations.size()).append(" justification(s):\n");
        int i = 1;
        for (Set<OWLAxiom> justification : explanations) {
            sb.append("\nJustification ").append(i++).append(" (").append(justification.size())
                    .append(" axioms):\n");
            TreeSet<String> lines = new TreeSet<>();
            for (OWLAxiom jx : justification) {
                lines.add("    " + Tools.renderAxiom(mm, jx));
            }
            for (String line : lines) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
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

    private static String render(OWLModelManager mm, String heading, Set<? extends OWLEntity> entities) {
        TreeSet<String> lines = new TreeSet<>();
        for (OWLEntity e : entities) {
            lines.add(Tools.renderEntity(mm, e));
        }
        StringBuilder sb = new StringBuilder(heading).append(" (").append(entities.size()).append("):\n");
        for (String line : lines) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString().trim();
    }
}
