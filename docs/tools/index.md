---
title: Tools
nav_order: 5
has_children: true
permalink: /tools/
---

# Tools
{: .no_toc }

All **55 tools** the MCP server exposes, grouped by task. Each category page documents every tool with
its **arguments** and **returns**.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

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
  (Settings ▸ MCP). Read/query tools work even in read-only mode.
- **Minting signal.** Many create/add tools report **`new_entities`** (the entities the call
  introduced) and accept **`strict`** — set it to fail instead of minting a brand-new entity from an
  unrecognized IRI/name (a typo guard).
- **Active ontology + imports.** Tools act on the **active** ontology (the current edit target). Where a
  tool accepts **`include_imports`**, it can widen to the imports closure.

## A typical flow

A safe natural-language editing loop:

**Orient** (`get_ontology_context` / `get_entity_context`) → **resolve** names to IRIs
(`search_entities` / `get_entity`) → **preview** an edit (`preview_changes`) → **apply** (an edit tool
or `apply_changes`) → **verify** (`run_reasoner`, `validate_ontology`).

The [guided prompts](prompts.html) package these flows for one-click use in an MCP client.

## Tool index

### [Explore & search](explore-search.html)
`list_ontologies` · `get_active_ontology` · `summarize_ontology` · `list_classes` · `search_entities` ·
`get_entity` · `get_axioms_for_entity`

### [Context & validation](context-validation.html)
`get_ontology_context` · `get_entity_context` · `validate_ontology` · `validate_governance` ·
`diff_ontologies`

### [Editing — entities & axioms](editing.html)
`preview_changes` · `apply_changes` · `create_class` · `create_entity` · `create_term` ·
`create_property` · `add_subclass_of` · `add_annotation` · `set_label` · `add_axiom` · `remove_axiom` ·
`rename_entity` · `delete_entity` · `deprecate_entity` · `move_class`

### [Ontology metadata & imports](metadata-imports.html)
`set_ontology_id` · `set_prefix` · `add_import` · `remove_import` · `add_ontology_annotation` ·
`remove_ontology_annotation`

### [Documents](documents.html)
`load_ontology` · `set_active_ontology` · `merge_ontology_document` · `create_ontology` ·
`write_catalog`

### [Rules (SWRL)](rules.html)
`list_rules` · `add_rule` · `remove_rule`

### [Reasoning](reasoning.html)
`list_reasoners` · `set_reasoner` · `run_reasoner` · `get_unsatisfiable_classes` ·
`get_inferred_superclasses` · `execute_dl_query` · `explain_entailment` · `get_explanations`

### [SPARQL](sparql.html)
`sparql_query` · `sparql_schema` · `sparql_validate`

### [History & persistence](history.html)
`undo_change` · `redo_change` · `save_ontology`

### Cross-cutting reference
- [**Axiom types**](axiom-types.html) — the structured `axiom_type` operand catalog used by
  `add_axiom`, `remove_axiom`, `preview_changes`, `apply_changes`, `explain_entailment`, and
  `get_explanations`.
- [**Guided workflows (prompts)**](prompts.html) — the 6 MCP prompts.
