---
title: "Guided workflows (prompts)"
parent: "Tools"
nav_order: 12
---

# Guided workflows (prompts)
{: .no_toc }

MCP *prompts* are reusable, guided ontology workflows you pick in your MCP client (Claude Code, Codex, VS Code, Claude Desktop). Each one expands to a single user message that tells the model which Protégé MCP tools to call and in what safe order — orient with context first, preview destructive edits, confirm before writing, and verify with the reasoner. Prompts are pure templates: they carry no model access and cause no side effects; all real work happens through the tools they instruct.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `audit_ontology`

Audits the ontology currently open in Protégé for modelling-quality, governance, and logical problems and proposes fixes, without changing anything until you approve. Reach for it when you want a full health check of the active ontology before doing further work.

**Arguments**

None.

**Workflow**

1. Call `get_ontology_context` to orient (size, roots, reasoner state).
2. Call `validate_ontology` to collect modelling-quality findings, and `validate_governance` for OWL 2 profile conformance and import-layering/ownership issues that `validate_ontology` does not check.
3. Call `run_reasoner` for logical problems. If it reports the ontology INCONSISTENT, call `explain_inconsistency` to find the contradicting axioms; otherwise call `get_unsatisfiable_classes`.
4. For the most important findings, call `get_entity_context` on the offending terms to understand them before suggesting changes.
5. Summarise the issues by severity and propose concrete fixes; use `preview_changes` to show the exact axioms before applying anything, and do not modify the ontology until you approve. After approval the batch is applied with `apply_changes` (the same operations array, one undoable transaction) with `verify=rollback`, so a fix that breaks satisfiability is undone automatically — or `verify=report` when repairing an already-inconsistent ontology, since rollback only detects new regressions (`run_reasoner` re-confirms the repair instead).

---

## `explain_class`

Explains a single class in plain language — its definition, its neighbourhood in the hierarchy, and what the reasoner infers about it beyond the asserted axioms. Reach for it to understand an unfamiliar or imported term before working with it.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `class` | yes | Class IRI or display name. |

**Workflow**

1. Call `get_entity_context` with `entity` set to the class for its labels, annotations, asserted parents/children/equivalents and disjoints.
2. Call `get_axioms_for_entity` for the exact axioms (`include_imports=true` if it is an imported term).
3. Call `run_reasoner`, then `get_inferred_superclasses` with `entity` set to the class, to see what is inferred beyond what is asserted.
4. Summarise what the class means, where it sits in the hierarchy, its key restrictions, and anything surprising the reasoner derived.

---

## `add_subclass_safely`

Adds a subclass relationship safely: it confirms both terms exist (to avoid minting a new entity from a typo), previews the change, then applies it atomically with automatic rollback if the edit breaks satisfiability. Reach for it when you want to assert `child SubClassOf parent` with full guardrails.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `child` | yes | Subclass — IRI, name, or Manchester class expression. |
| `parent` | yes | Superclass — IRI, name, or Manchester class expression. |

**Workflow**

1. Confirm both terms with `get_entity` (or `search_entities` if a name is ambiguous) — resolve to exact IRIs and avoid creating an unintended new entity from a typo.
2. Call `preview_changes` with one operation `{op:add, axiom_type:subclass_of, sub:child, super:parent}` and show the diff and any new entities it would introduce.
3. After you approve, apply the same operations array with `apply_changes` and `verify=rollback` — one undoable transaction that re-classifies the reasoner and automatically undoes the edit if it makes any class newly unsatisfiable or the ontology inconsistent — and report the verify verdict.
4. If no reasoner is selected (`verify=rollback` refuses before applying anything), `set_reasoner` (`list_reasoners` shows the choices) and step 3 is retried; only if no reasoner is available at all is the edit applied with `add_subclass_of` and reported as unverified — the reasoner checks cannot run in that state.

---

## `find_and_fix_unsatisfiable`

Finds unsatisfiable classes, explains why each one is unsatisfiable using minimal justifications, and proposes the smallest set of changes to restore satisfiability — including the wholly-inconsistent case, where it switches to `explain_inconsistency`. Reach for it to debug logical contradictions in the active ontology.

**Arguments**

None.

**Workflow**

1. Call `run_reasoner`, then `get_unsatisfiable_classes`. If the ontology is wholly INCONSISTENT the per-class tools refuse to run — call `explain_inconsistency` instead for a set of contradicting axioms (its `minimal` flag reports whether the set was fully minimised; `undo_change` with `peek=true` shows whether the last edit is the likely culprit).
2. For each unsatisfiable class C, call `get_explanations` (`axiom_type=subclass_of`, `sub=C`, `super="owl:Nothing"`) to get the minimal justifications.
3. For terms in the justifications, call `get_entity_context` to understand them.
4. Propose the smallest set of axiom removals/changes that restores satisfiability; use `preview_changes` (`op:remove`) to show exactly what would be removed, and only after you approve apply the batch with `apply_changes` (the same operations array — one undoable transaction) with `verify=report`, which re-classifies and flags anything the fix makes newly unsatisfiable or inconsistent. `get_unsatisfiable_classes` confirms the classes are fixed; a single `undo_change` reverts the whole batch if not.

---

## `author_sparql_query`

Authors a SPARQL query that answers a plain-language question: it discovers the available vocabulary, drafts a query using only real terms, validates it, and runs it — refining until the results are right. Reach for it to query the active ontology without hand-writing prefixes or guessing CURIEs.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `question` | yes | The question to answer in plain words. |

**Workflow**

1. Call `sparql_schema` (pass `keyword=` a key term from the question to focus it) to get the prefixes and the exact classes, properties (with their domains/ranges) and individuals to use — note the CURIEs and example queries.
2. Draft a SELECT/ASK/CONSTRUCT/DESCRIBE query using ONLY those CURIEs/IRIs (do not invent term names); the ontology's prefixes are auto-prepended, so CURIEs work without PREFIX lines.
3. Call `sparql_validate` on the draft; fix anything in `unknown_terms` (a typo or wrong vocabulary) and any `parse_error` until `executable=true`, and set `dry_run=true` to also run it with a small LIMIT and sanity-check sample results.
4. Run it with `sparql_query`; if you need triples the reasoner derives (e.g. inferred types or subclasses), set `include_inferred=true` (run `run_reasoner` first). Refine the pattern and repeat if results are empty or too broad.
5. Summarise the answer and show the final query used.

---

## `model_domain`

Models a described domain incrementally: it proposes a small set of terms that fit what already exists, shows the exact batches before applying, applies them atomically with verification, and gates the result with the QC suite. Reach for it to grow the active ontology in small, reviewable batches from a plain-language description.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `description` | yes | What to model (the domain in plain words). |

**Workflow**

1. Call `get_ontology_context` to see what already exists (reuse terms, match the naming and IRI style, don't duplicate).
2. Propose a small set of classes, properties (with domains/ranges) and any individuals, explaining the modelling choices.
3. Show the additions before applying: the new classes as a `create_terms` batch (name/label/definition/parents per item) and the new properties as a `create_properties` batch (domain/range/characteristics per item) — each batch is atomic and one undoable transaction — plus `preview_changes` for any remaining axioms (individuals, extra restrictions) as an operations array.
4. After you approve, apply the batches with `strict=true` (refuses typo-minted entities) and `verify=report`, and the remaining operations array with `apply_changes`; then call `run_qc_suite` for a one-call quality gate (reasoner + profile + structural). Work in small, reviewable batches.

---

## `author_competency_question`

Turns a plain-language requirement into a stored, executable competency question: it discovers the vocabulary, drafts and validates the query, stores it following the project's existing convention, and runs it once to confirm. Reach for it to grow the CQ regression suite one requirement at a time.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `question` | yes | The requirement/competency question in plain words. |
| `expected` | no | Pass condition: `nonEmpty` (default) \| `empty` \| `count OP N`. |

**Workflow**

1. Call `list_competency_questions` to see the existing CQ ids and which storage convention the project already uses — and follow it (ontology-annotations is the fallback when the ontology is unsaved; robot-sparql-dir writes `*.rq` files for ROBOT/CI interop).
2. Call `sparql_schema` (`keyword=` a key term from the question) to get the exact classes/properties and CURIEs; use only those.
3. Draft a SELECT or ASK query that answers the question, and choose the pass condition — the one under which a silent regression would FAIL the suite.
4. Call `sparql_validate` on the draft; fix anything in `unknown_terms` and any `parse_error` until `executable=true`.
5. Show the query, the expected condition and the target store; after you approve, call `add_competency_question` with the query, `text=` the requirement and the expected condition (omit `convention` to follow the existing store; note `include_inferred` **defaults to true** — set `include_inferred=false` unless the check should also hold over reasoner-derived triples).
6. Call `run_competency_questions` with `ids=[the new id]` to confirm it passes now (an `include_inferred` CQ needs a classified reasoner — `run_reasoner` first). If it fails, either refine the query or report the real ontology gap it exposed.

---

## `author_swrl_rule`

Authors a SWRL rule from a plain-language description, with the two reasoner-compatibility footguns checked up front: ELK silently ignores SWRL rules, and `swrlb:` built-in atoms make some DL reasoners fail classification. Reach for it to add a rule that actually fires instead of a silent no-op.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `rule` | yes | The rule to express, in plain words (if X then Y). |

**Workflow**

1. Resolve every class, property and individual the rule mentions with `search_entities` / `get_entity` and collect their exact IRIs — used as the atoms' `predicate` values so a typo cannot mint a new entity.
2. Call `list_rules` to match the existing rules' style and avoid duplicating one.
3. Build the structured body/head atoms for `add_rule` (atom types: `class {predicate, arg1}`, `object_property {predicate, arg1, arg2}`, `data_property {predicate, arg1, arg2|value}`, `same_as`/`different_from {arg1, arg2}`, `builtin {builtin, args[]}`; an argument starting with `?` is a variable), show them with a readable body → head rendering, and include an `rdfs:label` via `annotations`.
4. Check reasoner compatibility BEFORE applying (`list_reasoners`): ELK silently ignores SWRL rules (`run_reasoner` attaches a warning), and `swrlb:` built-in atoms make some DL reasoners (e.g. HermiT) fail classification — surfaced as an error. If the current reasoner cannot honour the rule, a `set_reasoner` switch is proposed for your approval.
5. After you approve the rule and the reasoner choice, call `add_rule` with the body/head (and annotations).
6. Verify the rule fires: `run_reasoner` (heed any warning/error), then confirm one expected inference with `get_inferred_superclasses` or `sparql_query` with `include_inferred=true`; if nothing is inferred, the reason is explained instead of reporting success.

---

## `refactor_entity_safely`

Renames, deprecates, deletes or moves a term the safe way: blast radius first, the right tool for the intent (rename rewrites references, deprecate keeps them, delete removes them, move reparents), dry-run previews, your confirmation, then verification. Reach for it before any high-blast-radius change to a term.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `entity` | yes | The term to refactor: IRI or display name. |
| `goal` | yes | What to achieve: rename \| deprecate \| delete \| move (plain words are fine). |

**Workflow**

1. Call `get_entity_context` and `get_axioms_for_entity` to establish the blast radius (how much references the term).
2. Pick the right tool and confirm the semantics first: `rename_entity` rewrites every reference (if `new_iri` already exists in the signature the two entities MERGE); `deprecate_entity` keeps the term and its axioms, adds `owl:deprecated` plus an optional `replaced_by` pointer; `delete_entity` removes its declaration and every referencing axiom; `move_class` replaces its asserted named parents (unless `keep_other_parents=true`) and the subtree follows.
3. Preview: `rename_entity` and `delete_entity` take `preview=true` — review the change count, the sample axioms, and (rename) the `new_iri_already_in_signature` flag; `move_class` and `deprecate_entity` have no preview, so the exact intended arguments are shown instead.
4. Apply only after you approve; each operation is a single undo transaction, and `undo_change` with `peek=true` shows what an undo would revert.
5. Verify with `run_reasoner` and `get_unsatisfiable_classes` (plus `validate_ontology` for a broad change), and report exactly what changed.

---

## `bootstrap_ontology`

Starts a new ontology module correctly: creates it bound to a file, sets prefixes and metadata, adds imports that actually resolve, saves, and writes the catalog so the module re-opens offline. Reach for it to begin a new module without the classic dangling-import and missing-catalog mistakes.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `ontology_iri` | yes | IRI of the new ontology. |
| `path` | no | File path to bind the ontology to (optional but recommended). |

**Workflow**

1. Confirm the plan first: ontology IRI (+ version IRI?), the term namespace, the file location, and which upstream ontologies to import.
2. Call `create_ontology` with the `ontology_iri` (and `path` — binding a path now means an argument-less `save_ontology` works and `write_catalog` has a folder). It becomes the active edit target; note it is not undoable.
3. Call `set_prefix` for the term namespace (and each upstream namespace) so CURIEs render/parse and the saved file is readable.
4. Add metadata with `add_ontology_annotation` (e.g. `dcterms:title`, `rdfs:comment`, `owl:versionInfo`).
5. For each upstream ontology call `add_import` with its IRI, passing `document` (a path/URL) so the import resolves now — then check the result's `resolved` flag: an unresolved import's terms stay invisible to lookups and reasoning until its document is loaded.
6. Call `save_ontology` (passing `path` here if the ontology is still untitled — an argument-less save has nowhere to write), then `write_catalog` so the local imports re-open offline in Protégé.
7. Verify with `get_ontology_context` (imports resolved, prefixes right), and once the first terms exist run `validate_governance` with `required_namespaces=[the term namespace]` to catch IRIs minted outside it.

---

## `release_readiness_check`

Runs the full quality gate over the active ontology — reasoner, OWL 2 profile, structural checks, competency questions, governance policy, and a diff against the last saved document — and only saves the artifacts after your approval. Reach for it before shipping a version.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `profile` | no | OWL 2 profile the project must stay in: DL (default), EL, QL or RL. |
| `namespace` | no | The project's required term namespace, for the governance IRI policy. |

**Workflow**

1. Call `get_ontology_context`, and `list_ontologies` to see which loaded ontologies have unsaved changes.
2. Call `run_reasoner`; an error (failed classification) or warning (e.g. ELK ignoring SWRL rules) is a release finding, not a side note.
3. Call `run_qc_suite` with `stages=["reasoner","profile","structural","cqs"]` and the project's `owl_profile`. A stage skipped for missing backing data is fine — the skip reason is reported. If the project has a SHACL shapes file, the `shacl` stage is added with `shacl_shapes_path`.
4. Call `validate_governance` with the project's `owl_profile`, `required_annotations=["label","definition"]` and (when known) `required_namespaces` — policy violations are reported per check (profile leaks, wrong-namespace IRIs, missing annotations, import-layering).
5. If the active ontology has a saved document, call `diff_ontologies` with `right_document=` that file to summarise exactly what changed since the last save.
6. Summarise every gate and finding by severity with a clear ship / do-not-ship verdict. Nothing is saved until you approve; after approval, `save_ontology` (`all=true` if several ontologies are dirty) and — when there are locally-loaded imports — `write_catalog` so the module re-opens offline.
