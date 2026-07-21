package io.github.hakjuoh.protege_mcp.contracts;

import java.util.Set;

/** Exact tool-name allowlists whose 0.7.2 success result predates typed output schemas. */
public final class Legacy072ToolContracts {

    private static final Set<String> LIVE = Set.of(
            "list_ontologies", "get_active_ontology", "summarize_ontology", "list_classes",
            "search_entities", "get_entity", "get_axioms_for_entity", "get_ontology_context",
            "get_entity_context", "get_model_revision", "create_class", "create_entity",
            "add_subclass_of", "add_annotation", "add_axiom", "remove_axiom", "apply_changes",
            "set_label", "undo_change", "redo_change", "save_ontology", "preview_changes",
            "preview_change_set", "commit_change_set", "discard_change_set", "rebase_change_set",
            "create_term", "create_terms", "create_property", "create_properties",
            "deprecate_entity", "move_class", "rename_entity", "delete_entity", "set_ontology_id",
            "add_import", "set_prefix", "remove_prefix", "remove_import",
            "add_ontology_annotation", "remove_ontology_annotation", "load_ontology",
            "merge_ontology_document", "set_active_ontology", "create_ontology", "extract_module",
            "list_rules", "add_rule", "remove_rule", "inspect_imports", "write_import_lock",
            "verify_import_lock", "validate_catalog", "write_catalog", "diff_ontologies",
            "semantic_diff", "analyze_change_impact", "run_reasoner",
            "get_unsatisfiable_classes", "get_inferred_superclasses", "explain_entailment",
            "get_explanations", "explain_inconsistency", "execute_dl_query", "list_reasoners",
            "set_reasoner", "sparql_query", "sparql_schema", "sparql_validate",
            "validate_ontology", "validate_governance", "add_competency_question",
            "list_competency_questions", "remove_competency_question", "run_competency_questions",
            "verify_ontology", "shacl_validate", "get_project_policy", "validate_project_policy",
            "run_project_qc", "write_project_policy_template", "run_qc_suite", "run_release_gate",
            "prepare_release", "export_audit_log");

    private static final Set<String> HEADLESS = Set.of(
            "get_headless_capabilities", "validate_project_policy", "run_project_qc",
            "verify_import_lock", "write_import_lock", "run_release_gate", "prepare_release",
            "export_audit_log");

    private Legacy072ToolContracts() {
    }

    public static Set<String> liveToolNames() {
        return LIVE;
    }

    public static Set<String> headlessToolNames() {
        return HEADLESS;
    }
}
