---
title: Tools
nav_order: 5
has_children: true
permalink: /tools/
---

# Tools
{: .no_toc }

All **84 tools** the MCP server exposes, grouped by task. Each category page documents every tool with
its **arguments** and **returns**.
{: .fs-6 .fw-300 }

Version 0.7.1 adds explicit, redacted audit export to the 83-tool v0.7.0 surface while retaining its
deterministic preview, impact, release, project-boundary, and isolated-preflight guarantees.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## New tool in 0.7.1

| Task | New tool | What it adds |
| --- | --- | --- |
| Audit | [`export_audit_log`](quality.html#export_audit_log) | Deterministically merge the active project's rotated, owner-only streams into one bounded, re-redacted JSONL artifact. Dry-run is the default; a write requires a valid project policy, server administration plus project read/write capabilities, and the normal confirmation gate. |

## New tools in 0.7.0

Version 0.7.0 adds **5 tools** to the 78-tool v0.6.x surface. Existing tool arguments and default
interactive behavior remain compatible.

| Task | New tools | What they add |
| --- | --- | --- |
| Transactional editing | [`rebase_change_set`](editing.html#rebase_change_set) | Deterministically re-resolve a cached preview at the current revision; a changed resolution fails closed for human review. |
| Change review | [`analyze_change_impact`](context-validation.html#analyze_change_impact) | Syntactic impact analysis of a cached change set or asserted diff: affected entities and modules, referencing axioms, downstream terms, foreign re-axiomatization, deprecated terms in use, and validation assets naming changed IRIs. |
| Release | [`run_release_gate`](quality.html#run_release_gate), [`prepare_release`](quality.html#prepare_release) | Run the strict QC gate plus the release-only checks (import provenance, version IRI, verified serialization round trip, fingerprint stability, optional baseline diff) read-only, then produce the manifest, reports, and RO-Crate bundle — dry-run by default, written atomically into the policy output directory on confirmation. |
| Project policy & QC | [`write_project_policy_template`](quality.html#write_project_policy_template) | Scaffold a commented, schema-valid starter `.protege-mcp/project.yaml` from the active ontology — safe defaults filled in, asset-referencing blocks commented out, with a `validation_hint` for what to complete — to review and commit like source code. |

## New tools in 0.6.0

Version 0.6.0 adds **12 tools** to the 66-tool v0.5.x surface. Existing tool arguments and default
interactive behavior remain compatible.

| Task | New tools | What they add |
| --- | --- | --- |
| Project policy & QC | [`get_project_policy`](quality.html#get_project_policy), [`validate_project_policy`](quality.html#validate_project_policy), [`run_project_qc`](quality.html#run_project_qc) | Discover a checked-in policy, validate its files and settings, and run its required checks as one reproducible gate. |
| Revisions & release evidence | [`get_model_revision`](context-validation.html#get_model_revision), [`semantic_diff`](context-validation.html#semantic_diff) | Pin the exact workspace revision used by a change and classify asserted changes between ontology artifacts. |
| Transactional editing | [`preview_change_set`](editing.html#preview_change_set), [`commit_change_set`](editing.html#commit_change_set), [`discard_change_set`](editing.html#discard_change_set) | Preview an exact edit against an isolated QC snapshot, commit only if the revision still matches, or discard it. |
| Import integrity | [`inspect_imports`](documents.html#inspect_imports), [`write_import_lock`](documents.html#write_import_lock), [`verify_import_lock`](documents.html#verify_import_lock), [`validate_catalog`](documents.html#validate_catalog) | Inspect the loaded import graph, lock local dependencies by checksum, verify the lock offline, and validate an OASIS catalog. |

The [`save_ontology`](history.html#save_ontology), `load_ontology`, `merge_ontology_document`,
`create_terms`, `create_properties`, and `run_qc_suite` tools also gain optional v0.6.0 behavior; their
existing direct-call defaults are unchanged. See each tool's section for its current arguments and result
contract.

## Conventions

These hold for every tool:

- **Structured JSON output.** Every tool returns a structured JSON object, delivered as MCP
  `structuredContent` and mirrored as a JSON text block — so every client, and a human reading the
  transcript, see the same result.
- **Entities by IRI *or* name.** You may reference an entity by its full IRI or by its display name
  (the active renderer). Where noted, class/data-range operands also accept **Manchester syntax**
  expressions (e.g. `hasPart some Cell`).
- **Edits are GUI-visible and undoable.** Every model edit goes through `OWLModelManager.applyChanges`,
  so it appears in Protégé immediately and joins the shared **Edit ▸ Undo** stack. (Document
  open/save operations use Protégé's own load/save APIs and are **not** undoable.)
- **Safety gates.** Mutating tools obey the **read-only** and **confirm-each-write** preferences
  (Settings ▸ MCP). Every tool also checks the authenticated principal's declared ontology/release/
  filesystem/network capabilities before its handler runs. Read/query tools work in global read-only mode,
  but a caller-selected file still needs its explicit filesystem capability.
- **Minting signal.** Many create/add tools report **`new_entities`** (the entities the call
  introduced) and accept **`strict`** — set it to fail instead of minting a brand-new entity from an
  unrecognized IRI/name (a typo guard).
- **Active ontology + imports.** Tools act on the **active** ontology (the current edit target). Where a
  tool accepts **`include_imports`**, it can widen to the imports closure.

## A typical flow

A safe natural-language editing loop:

**Orient** (`get_ontology_context` / `get_entity_context`) → **ground** a name to an IRI, or confirm it
would mint a new one (`search_entities` — `would_mint` / `best_match`) → **preview** an edit
(`preview_changes`) → **apply & verify in one call** (`apply_changes` with `verify=rollback` prevents a
delta that fails the effective change-set gate) → **gate** with the requirements suite and invariants
(`run_competency_questions`, `verify_ontology`, or the umbrella `run_qc_suite`).

The top-level [Prompts](../prompts/) guide packages these flows for one-click use in an MCP client.

## Tool index

### [Explore & search](explore-search.html)
`list_ontologies` · `get_active_ontology` · `summarize_ontology` · `list_classes` · `search_entities` ·
`get_entity` · `get_axioms_for_entity`

### [Context & validation](context-validation.html)
`get_ontology_context` · `get_entity_context` · `get_model_revision` · `validate_ontology` ·
`validate_governance` · `diff_ontologies` · `semantic_diff` · `analyze_change_impact`

### [Safe authoring & QC](quality.html)
`get_project_policy` · `validate_project_policy` · `run_project_qc` · `write_project_policy_template` ·
`run_release_gate` · `prepare_release` · `export_audit_log` · `verify_ontology` · `run_qc_suite` · `shacl_validate` · `add_competency_question` ·
`list_competency_questions` · `remove_competency_question` · `run_competency_questions` *(plus
`apply_changes verify=` and `search_entities` grounding — see their category pages)*

### [Editing — entities & axioms](editing.html)
`preview_changes` · `apply_changes` · `preview_change_set` · `commit_change_set` ·
`discard_change_set` · `rebase_change_set` · `create_class` · `create_entity` · `create_term` ·
`create_terms` · `create_property` · `create_properties` · `add_subclass_of` · `add_annotation` · `set_label` · `add_axiom` ·
`remove_axiom` · `rename_entity` · `delete_entity` · `deprecate_entity` · `move_class`

### [Ontology metadata & imports](metadata-imports.html)
`set_ontology_id` · `set_prefix` · `add_import` · `remove_import` · `add_ontology_annotation` ·
`remove_ontology_annotation`

### [Documents](documents.html)
`load_ontology` · `set_active_ontology` · `merge_ontology_document` · `create_ontology` ·
`inspect_imports` · `write_import_lock` · `verify_import_lock` · `validate_catalog` ·
`write_catalog` · `extract_module`

### [Rules (SWRL)](rules.html)
`list_rules` · `add_rule` · `remove_rule`

### [Reasoning](reasoning.html)
`list_reasoners` · `set_reasoner` · `run_reasoner` · `get_unsatisfiable_classes` ·
`get_inferred_superclasses` · `execute_dl_query` · `explain_entailment` · `get_explanations` ·
`explain_inconsistency`

### [SPARQL](sparql.html)
`sparql_query` · `sparql_schema` · `sparql_validate`

### [History & persistence](history.html)
`undo_change` · `redo_change` · `save_ontology`

### Cross-cutting reference
- [**Axiom types**](axiom-types.html) — the structured `axiom_type` operand catalog used by
  `add_axiom`, `remove_axiom`, `preview_changes`, `apply_changes`, `explain_entailment`, and
  `get_explanations`.
- [**Prompts**](../prompts/) — 11 guided workflows that compose the tools into safe, repeatable tasks.
