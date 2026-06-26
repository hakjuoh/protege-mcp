package org.protege.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.protege.mcp.server.EmbeddedClassificationWaiter;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/** Reasoning tools: run classification, list unsatisfiable classes, read inferred relations. */
public final class ReasonerTools {

    private ReasonerTools() {
    }

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
                "Check whether a structured axiom is entailed by the active reasoner. axiom_type is "
                        + "one of: " + Axioms.SUPPORTED + ". (Reports entailment only; full "
                        + "explanations require Protégé's explanation workbench.)",
                Tools.schema()
                        .strReq("axiom_type", Axioms.SUPPORTED)
                        .str("sub", "subclass_of: subclass")
                        .str("super", "subclass_of: superclass")
                        .strArray("classes", "equivalent_classes / disjoint_classes")
                        .str("class", "class_assertion: class")
                        .str("individual", "class_assertion: individual")
                        .str("property", "*_property_assertion: property")
                        .str("subject", "*_property_assertion: subject")
                        .str("object", "object_property_assertion: object")
                        .str("value", "data_property_assertion: literal value")
                        .str("lang", "data_property_assertion: language tag")
                        .str("datatype", "data_property_assertion: datatype")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    return ctx.access().compute(mm -> {
                        OWLReasoner r = requireReasoner(mm);
                        org.semanticweb.owlapi.model.OWLAxiom ax = Axioms.build(mm, a);
                        boolean entailed = r.isEntailed(ax);
                        return Tools.text("Entailed: " + entailed + "\n  " + Tools.renderAxiom(mm, ax));
                    });
                })));

        return tools;
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
