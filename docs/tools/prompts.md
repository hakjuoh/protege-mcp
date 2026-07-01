---
title: "Guided workflows (prompts)"
parent: "Tools"
nav_order: 11
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

Audits the ontology currently open in Protégé for modelling-quality and logical problems and proposes fixes, without changing anything until you approve. Reach for it when you want a full health check of the active ontology before doing further work.

**Arguments**

None.

**Workflow**

1. Call `get_ontology_context` to orient (size, roots, reasoner state).
2. Call `validate_ontology` to collect modelling-quality findings.
3. Call `run_reasoner`, then `get_unsatisfiable_classes`, for logical problems.
4. For the most important findings, call `get_entity_context` on the offending terms to understand them before suggesting changes.
5. Summarise the issues by severity and propose concrete fixes; use `preview_changes` to show the exact axioms before applying anything, and do not modify the ontology until you approve.

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

Adds a subclass relationship safely: it confirms both terms exist (to avoid minting a new entity from a typo), previews the change, applies it, and re-checks satisfiability. Reach for it when you want to assert `child SubClassOf parent` with full guardrails.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `child` | yes | Subclass — IRI, name, or Manchester class expression. |
| `parent` | yes | Superclass — IRI, name, or Manchester class expression. |

**Workflow**

1. Confirm both terms with `get_entity` (or `search_entities` if a name is ambiguous) — resolve to exact IRIs and avoid creating an unintended new entity from a typo.
2. Call `preview_changes` with one operation `{op:add, axiom_type:subclass_of, sub:child, super:parent}` and show the diff and any new entities it would introduce.
3. After you approve, apply it with `add_subclass_of`.
4. Call `run_reasoner` and `get_unsatisfiable_classes` to confirm the edit did not make any class unsatisfiable.

---

## `find_and_fix_unsatisfiable`

Finds unsatisfiable classes, explains why each one is unsatisfiable using minimal justifications, and proposes the smallest set of changes to restore satisfiability. Reach for it to debug logical contradictions in the active ontology.

**Arguments**

None.

**Workflow**

1. Call `run_reasoner`, then `get_unsatisfiable_classes`.
2. For each unsatisfiable class C, call `get_explanations` (`axiom_type=subclass_of`, `sub=C`, `super="owl:Nothing"`) to get the minimal justifications.
3. For terms in the justifications, call `get_entity_context` to understand them.
4. Propose the smallest set of axiom removals/changes that restores satisfiability; use `preview_changes` (`op:remove`) to show exactly what would be removed, apply with `remove_axiom` only after you approve, and re-run the reasoner to confirm.

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
3. Call `sparql_validate` on the draft; fix anything in `unknown_terms` (a typo or wrong vocabulary) and any `parse_error` until `executable=true`.
4. Run it with `sparql_query`; if you need triples the reasoner derives (e.g. inferred types or subclasses), set `include_inferred=true` (run `run_reasoner` first). Refine the pattern and repeat if results are empty or too broad.
5. Summarise the answer and show the final query used.

---

## `model_domain`

Models a described domain incrementally: it proposes a small set of terms that fit what already exists, previews the additions, applies them with confirmation, and validates the result. Reach for it to grow the active ontology in small, reviewable batches from a plain-language description.

**Arguments**

| Name | Required | Description |
| --- | --- | --- |
| `description` | yes | What to model (the domain in plain words). |

**Workflow**

1. Call `get_ontology_context` to see what already exists (reuse terms, match the naming and IRI style, don't duplicate).
2. Propose a small set of classes, properties (with domains/ranges) and any individuals, explaining the modelling choices.
3. Express the additions as `preview_changes` operations and show the diff and the new entities before applying.
4. After you approve, apply with `create_class` / `create_entity` / `add_axiom` / `add_subclass_of`, then call `validate_ontology` and `run_reasoner` to check the result. Work in small, reviewable batches.
