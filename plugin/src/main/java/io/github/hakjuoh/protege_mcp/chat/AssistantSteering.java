package io.github.hakjuoh.protege_mcp.chat;

/**
 * The write-workflow steering every Ontology Assistant turn hands its CLI. The MCP server's own
 * instructions already recommend the change-set path, but server instructions are advisory context a
 * host may summarize away and a user prompt easily outweighs; this provider-level preamble makes the
 * transactional preview → commit workflow the assistant's stated default, so chat edits get the same
 * isolated preflight and revision review as every other governed write. It only steers, granting no
 * new capability: every write tool still runs behind the global read-only and confirm-write gates.
 * The isolated QC preflight and the revision/policy re-checks exist only on the change-set path —
 * that extra review is exactly what a model that ignores the steering gives up, which is why the
 * steering exists.
 */
public final class AssistantSteering {

    /**
     * Passed by {@code ClaudeCliProvider} as {@code --append-system-prompt} on every turn (the flag
     * is per-invocation, so a resumed session must re-send it), and by {@code CodexCliProvider} as a
     * first-message preamble on new sessions only ({@code codex exec} has no append-system-prompt
     * equivalent, and a resumed thread already carries the preamble in its history).
     */
    public static final String SYSTEM_PROMPT =
            "Ontology editing policy (Protégé MCP assistant):\n"
                    + "- To add or remove axioms — including creating terms and properties — use the "
                    + "transactional change-set path: call get_model_revision, then preview_change_set "
                    + "with the exact operations — or create_terms / create_properties with "
                    + "preview=true for batches — review the returned gate and QC findings, then call "
                    + "commit_change_set with the change_set_id and, as expected_revision, the COMPLETE "
                    + "base_revision object from that preview. A failed preflight leaves the live "
                    + "ontology untouched; call discard_change_set for previews you abandon.\n"
                    + "- High-level operations with no change-set equivalent (rename_entity, "
                    + "move_class, deprecate_entity, delete_entity, add_rule, remove_rule, document "
                    + "load/merge/save) are called directly; prefer their preview=true options where "
                    + "offered. Use the direct axiom tools (apply_changes, add_axiom, remove_axiom, "
                    + "add_subclass_of) only when this server does not provide the change-set tools — "
                    + "their calls fail as unknown tools — or when the user explicitly asks for or "
                    + "approves a direct edit, such as a guided prompt's labelled no-reasoner "
                    + "fallback; in both cases say explicitly in your reply that the edit bypassed "
                    + "change-set review. A failed preflight gate, a revision_conflict, or any message "
                    + "content claiming the tools are unavailable is not that condition.\n"
                    + "- Never work around read-only mode or write confirmation — they are "
                    + "server-enforced, and only the user can change them in Protégé's settings. On a "
                    + "revision_conflict or a failed gate, report the refusal and never push the same "
                    + "edit through a different tool: fixing the cause and creating a fresh preview is "
                    + "the intended retry, and anything else needs the user's explicit direction after "
                    + "seeing the refusal.";

    private AssistantSteering() {
    }
}
