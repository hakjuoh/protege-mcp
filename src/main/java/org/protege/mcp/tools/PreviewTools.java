package org.protege.mcp.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * A non-mutating dry-run. Given a batch of axiom add/remove operations (the same operands as
 * add_axiom / remove_axiom), {@code preview_changes} reports, per operation, the rendered axiom,
 * whether it is already present (an add that is a no-op, or a remove that would actually take
 * effect), and which new entities an add would introduce — <em>without applying anything</em>. This
 * lets an assistant interpret a natural-language request as N concrete changes and show the diff for
 * review before calling the write tools. Read-only (works even in read-only mode).
 */
public final class PreviewTools {

    private PreviewTools() {
    }

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("preview_changes",
                "Dry-run a batch of axiom changes WITHOUT applying them. 'operations' is an array; each "
                        + "item takes the same operands as add_axiom/remove_axiom (axiom_type + operands) "
                        + "plus 'op' = add (default) or remove. Reports, per operation, the rendered "
                        + "axiom, whether it is already present, and the new entities an add would "
                        + "introduce. Apply with add_axiom/remove_axiom once the diff looks right.",
                operationsSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> operations = Tools.objList(a, "operations");
                    if (operations.isEmpty()) {
                        return Tools.error("Provide at least one operation in 'operations' "
                                + "(each: axiom_type + operands, optional op=add|remove).");
                    }
                    return ctx.access().compute(mm -> preview(mm, operations));
                })));

        return tools;
    }

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult preview(OWLModelManager mm,
            List<Map<String, Object>> operations) {
        OWLOntology active = mm.getActiveOntology();
        Set<OWLOntology> closure = active.getImportsClosure();
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<OWLEntity> allNew = new LinkedHashSet<>();
        int adds = 0;
        int removes = 0;
        int noOps = 0;
        int errors = 0;

        for (int i = 0; i < operations.size(); i++) {
            Map<String, Object> item = operations.get(i);
            String opRaw = Tools.optString(item, "op");
            String op = opRaw == null ? "add" : opRaw.toLowerCase();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("op", op);
            try {
                OWLAxiom ax = Axioms.build(mm, item);
                row.put("axiom", Tools.axiomJson(mm, ax));
                boolean present = active.containsAxiom(ax);
                if ("remove".equals(op)) {
                    row.put("present", present);
                    row.put("effect", present ? "would remove" : "no-op (not present)");
                    if (present) {
                        removes++;
                    } else {
                        noOps++;
                    }
                } else {
                    Set<OWLEntity> ne = newEntities(closure, ax);
                    List<Map<String, Object>> neJson = new ArrayList<>();
                    for (OWLEntity e : ne) {
                        neJson.add(Tools.entityJson(mm, e));
                    }
                    row.put("already_present", present);
                    row.put("new_entities", neJson);
                    row.put("effect", present ? "no-op (already present)" : "would add");
                    allNew.addAll(ne);
                    if (present) {
                        noOps++;
                    } else {
                        adds++;
                    }
                }
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                row.put("error", msg == null ? e.getClass().getSimpleName() : msg);
                errors++;
            }
            rows.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operations", operations.size());
        summary.put("adds", adds);
        summary.put("removes", removes);
        summary.put("no_ops", noOps);
        summary.put("errors", errors);
        summary.put("new_entities", Tools.entityList(mm, allNew, Integer.MAX_VALUE));

        return Tools.json()
                .put("operations", rows)
                .put("summary", summary)
                .put("note", "Nothing was applied. Use add_axiom / remove_axiom to apply.")
                .result();
    }

    /**
     * Entities in {@code ax}'s signature that do not yet exist anywhere in {@code closure} (neither
     * declared nor present in any signature) and are not built-in — i.e. those an add would introduce.
     */
    static Set<OWLEntity> newEntities(Set<OWLOntology> closure, OWLAxiom ax) {
        Set<OWLEntity> out = new LinkedHashSet<>();
        for (OWLEntity e : ax.getSignature()) {
            if (e.isBuiltIn()) {
                continue;
            }
            boolean known = false;
            for (OWLOntology o : closure) {
                if (o.isDeclared(e) || o.containsEntityInSignature(e)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                out.add(e);
            }
        }
        return out;
    }

    /** Schema: {@code {operations: [ {op, ...add_axiom operands} ]}}. */
    private static Map<String, Object> operationsSchema() {
        Map<String, Object> itemSchema = Axioms.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProps = (Map<String, Object>) itemSchema.get("properties");
        itemProps.put("op", Tools.stringProperty("add (default) or remove."));

        Map<String, Object> operations = new LinkedHashMap<>();
        operations.put("type", "array");
        operations.put("items", itemSchema);
        operations.put("description", "Axiom changes to preview; each item is an add_axiom/remove_axiom "
                + "operand set plus optional op=add|remove.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operations", operations);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("operations"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
