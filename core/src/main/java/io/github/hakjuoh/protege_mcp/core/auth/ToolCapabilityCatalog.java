package io.github.hakjuoh.protege_mcp.core.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Complete capability declaration for the public in-Protégé MCP tool catalog. */
public final class ToolCapabilityCatalog {

    private static final Map<String, Set<String>> REQUIRED = declarations();

    private ToolCapabilityCatalog() {
    }

    public static Set<String> names() {
        return REQUIRED.keySet();
    }

    public static Set<String> required(String toolName) {
        Set<String> required = REQUIRED.get(toolName);
        if (required == null) throw new IllegalArgumentException("Unknown built-in tool: " + toolName);
        return required;
    }

    private static Set<String> access(Capability... capabilities) {
        Set<String> values = new LinkedHashSet<>();
        for (Capability capability : capabilities) values.add(capability.value());
        return Collections.unmodifiableSet(values);
    }

    private static void declare(Map<String, Set<String>> map, Set<String> access,
            String... names) {
        for (String name : names) {
            if (map.putIfAbsent(name, access) != null) {
                throw new IllegalStateException("duplicate capability declaration for " + name);
            }
        }
    }

    private static Map<String, Set<String>> declarations() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        Set<String> read = access(Capability.ONTOLOGY_READ);
        Set<String> curate = access(Capability.ONTOLOGY_CURATE);
        Set<String> admin = access(Capability.ONTOLOGY_ADMIN);
        Set<String> readProject = access(Capability.ONTOLOGY_READ,
                Capability.FILESYSTEM_PROJECT_READ);
        Set<String> adminProjectRead = access(Capability.ONTOLOGY_ADMIN,
                Capability.FILESYSTEM_PROJECT_READ);
        Set<String> adminProjectWrite = access(Capability.ONTOLOGY_ADMIN,
                Capability.FILESYSTEM_PROJECT_WRITE);
        Set<String> adminProjectReadWrite = access(Capability.ONTOLOGY_ADMIN,
                Capability.FILESYSTEM_PROJECT_READ, Capability.FILESYSTEM_PROJECT_WRITE);
        Set<String> releaseRead = access(Capability.ONTOLOGY_RELEASE,
                Capability.FILESYSTEM_PROJECT_READ);
        Set<String> releaseWrite = access(Capability.ONTOLOGY_RELEASE,
                Capability.FILESYSTEM_PROJECT_READ, Capability.FILESYSTEM_PROJECT_WRITE);
        Set<String> auditExport = access(Capability.SERVER_ADMIN,
                Capability.FILESYSTEM_PROJECT_READ, Capability.FILESYSTEM_PROJECT_WRITE);

        declare(map, read,
                "list_ontologies", "get_active_ontology", "summarize_ontology", "list_classes",
                "search_entities", "get_entity", "get_axioms_for_entity", "get_ontology_context",
                "get_entity_context", "preview_changes", "list_rules",
                "inspect_imports", "diff_ontologies", "semantic_diff", "analyze_change_impact",
                "run_reasoner", "get_unsatisfiable_classes", "get_inferred_superclasses",
                "explain_entailment", "get_explanations", "explain_inconsistency",
                "execute_dl_query", "list_reasoners", "sparql_query", "sparql_schema",
                "sparql_validate", "validate_ontology", "validate_governance",
                "list_competency_questions", "run_competency_questions", "verify_ontology",
                "shacl_validate", "run_qc_suite");
        declare(map, readProject, "get_model_revision", "verify_import_lock", "validate_catalog",
                "get_project_policy", "validate_project_policy", "run_project_qc");
        declare(map, curate,
                "create_class", "create_entity", "add_subclass_of", "add_annotation", "add_axiom",
                "remove_axiom", "apply_changes", "set_label", "undo_change", "redo_change",
                "preview_change_set", "commit_change_set", "discard_change_set", "rebase_change_set",
                "create_term", "create_terms", "create_property", "create_properties",
                "deprecate_entity", "move_class", "rename_entity", "delete_entity", "add_rule",
                "remove_rule", "add_competency_question", "remove_competency_question");
        declare(map, admin,
                "set_ontology_id", "add_import", "set_prefix", "remove_prefix", "remove_import",
                "add_ontology_annotation", "remove_ontology_annotation", "set_active_ontology",
                "create_ontology", "extract_module", "set_reasoner");
        declare(map, adminProjectRead, "load_ontology", "merge_ontology_document");
        declare(map, adminProjectWrite, "save_ontology", "write_catalog",
                "write_project_policy_template");
        declare(map, adminProjectReadWrite, "write_import_lock");
        declare(map, releaseRead, "run_release_gate");
        declare(map, releaseWrite, "prepare_release");
        declare(map, auditExport, "export_audit_log");
        return Collections.unmodifiableMap(map);
    }
}
