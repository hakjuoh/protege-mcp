---
title: "Safe authoring & QC"
parent: "Tools"
nav_order: 9
---

# Safe authoring & QC
{: .no_toc }

The *safe, testable authoring* tools added in **0.4.0**: run project-defined SPARQL **invariants**, a
re-runnable **competency-question** requirements suite, and one aggregate **quality-control gate**. They
close the **propose → ground → verify → confirm** loop — pair them with `apply_changes verify=`
([Editing](editing.html)) and `search_entities` grounding ([Explore & search](explore-search.html)).
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `verify_ontology`

Run project-defined SPARQL **invariants** over the active ontology — patterns that must *never* appear
(the ROBOT `verify` model). Each `queries[]` item is a SPARQL `SELECT` or `ASK` whose **results are
violations**: any returned row (or `ASK` true) flags it, at the item's severity. A graph-producing
`CONSTRUCT`/`DESCRIBE` is *not* a detector and is **rejected** (use [`sparql_query`](sparql.md) for those).
The overall `gate` fails when a violation reaches `fail_on`. Queries run over a shared snapshot (asserted,
or the reasoner's inferences for items with `include_inferred=true`); `UPDATE`/`SERVICE` are rejected, and
violation rows are reported as raw SPARQL bindings (never rendered through the UI thread). A check that
**cannot run** — a malformed/timed-out query, an `include_inferred` invariant with no classified reasoner,
or a rejected non-`SELECT`/`ASK` form — fails **fail-closed**: it never silently degrades to the asserted
triples and reports a false pass.

*Read-only.* A persisted invariant `profile` is reserved for a later release; use inline `queries[]`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `queries` | array | yes | — | The invariants. Each: `{ sparql (required), id?, message?, severity?, include_inferred? }`. |
| `queries[].sparql` | string | yes | — | A SPARQL `SELECT`/`ASK` whose results are violations (`CONSTRUCT`/`DESCRIBE` are rejected). |
| `queries[].id` | string | no | `invariant-N` | Stable id. |
| `queries[].message` | string | no | — | Human message describing the violation. |
| `queries[].severity` | string | no | `error` | `error` \| `warn` \| `info`. |
| `queries[].include_inferred` | boolean | no | false | Run over the reasoner's inferred triples. |
| `fail_on` | string | no | `error` | Gate severity: `none` \| `info` \| `warn` \| `error`. |
| `limit` | integer | no | 1000 | Max violation rows per invariant. |
| `timeout_ms` | integer | no | 120000 | Per-invariant time budget. |

**Returns**

- `checked`: integer, number of invariants run.
- `violations`: integer, number of invariants that matched (a forbidden pattern present).
- `errors`: integer, number of invariants that could not run (each also fails the gate fail-closed).
- `fail_on`: string, the effective gate severity.
- `gate`: string, `"pass"` or `"fail"` (fails when the worst violation/error reaches `fail_on`).
- `invariants`: array, per-invariant `{id, severity, message, violated}`; for a violation also
  `violation_count` and `violations` (the raw `{type,value,lang?,datatype?}` bindings), or `error` when
  it could not run (a query error, an `include_inferred` invariant with no classified reasoner, or a
  rejected non-`SELECT`/`ASK` form). A `caveats` entry flags a *soft* qualifier such as truncated
  inferences.

**Example**

```json
{
  "queries": [
    { "id": "no-orphan-class",
      "message": "Every class must have a parent or be owl:Thing.",
      "severity": "error",
      "sparql": "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:subClassOf ?p } FILTER(?c != owl:Thing) }" }
  ],
  "fail_on": "error"
}
```

## `run_qc_suite`

One aggregate quality-control gate that composes the shipping cores over **one shared snapshot** and
collapses them to a single verdict. Stages (default `reasoner` + `profile` + `structural`): `reasoner`
(consistency + no unsatisfiable classes), `profile` (OWL 2 profile conformance), `structural`
(`validate_ontology`'s modelling-quality checks — only *warning*-severity smells gate), `invariants`
(`verify_ontology`-style SPARQL invariants — pass them in `invariants`), `cqs` (the competency-question
suite), and a reserved `shacl`. A stage whose backing data is absent (no classified reasoner, no
invariants, no CQs, no SHACL) is **skipped with a reason, never an error**; the overall `gate` is the
worst *ran* stage versus `fail_on`.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `stages` | array | no | `["reasoner","profile","structural"]` | Subset of `reasoner`, `profile`, `structural`, `invariants`, `cqs`, `shacl`. |
| `owl_profile` | string | no | `DL` | OWL 2 profile for the `profile` stage: `DL` \| `EL` \| `QL` \| `RL`. |
| `invariants` | array | no | — | Invariants for the `invariants` stage (same shape as `verify_ontology`'s `queries[]`). |
| `fail_on` | string | no | `error` | Gate severity: `none` \| `warn` \| `error`. |
| `limit` | integer | no | 25 | Max samples per check. |
| `timeout_ms` | integer | no | 120000 | Time budget for the SPARQL stages. |

**Returns**

- `gate`: string, `"pass"` or `"fail"` (the worst *ran* stage vs `fail_on`).
- `fail_on`: string, the effective gate severity.
- `stages_ran`: integer, how many stages actually ran.
- `stages`: array, per-stage `{stage, ran, verdict?, findings_summary?, reason?}` (`verdict` is
  `pass`/`warn`/`fail` when it ran; `reason` explains a skip).

**Example**

```json
{ "stages": ["reasoner", "profile", "structural", "cqs"], "owl_profile": "EL", "fail_on": "warn" }
```

## `add_competency_question`

Add or update (by id) a **competency question** — a SPARQL query plus an expected result that
`run_competency_questions` later re-checks (a requirement test for the ontology). CQs are stored in one of
three conventions; omit `convention` to follow an existing store, else it defaults to `robot-sparql-dir`
(or `ontology-annotations` when the ontology is unsaved). This writes a file (or an ontology annotation)
and echoes where. Gated by the write-consent preferences.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `query` | string | yes | — | Executable SPARQL 1.1 query — `SELECT` or `ASK`. |
| `id` | string | no | `CQ-N` | Stable id within the store (auto-minted if omitted). |
| `text` | string | no | — | The natural-language competency question (recommended). |
| `type` | string | no | — | Optional category, e.g. `Scoping` \| `Validating`. |
| `query_lang` | string | no | `sparql` | Only `sparql` in 0.4.0. |
| `include_inferred` | boolean | no | true | Run over inferred triples when the suite runs. |
| `expected` | string | no | `nonEmpty` | Pass condition: `nonEmpty` \| `empty` \| `count OP N` (`OP` ∈ `>=,<=,==,>,<`). `exactRows` is authored in a `sidecar-manifest`. |
| `tags` | array | no | — | Optional tags. |
| `convention` | string | no | *(auto)* | `robot-sparql-dir` \| `sidecar-manifest` \| `ontology-annotations`. |

**Returns**

- `added`: object, the stored CQ (id, query, expected, convention, …).
- `convention`: string, the store written to.
- `target`: string, the file path (or "the active ontology (annotations)") written.

**Example**

```json
{ "text": "Does every measurement process have a participant?",
  "query": "SELECT ?p WHERE { ?p a :MeasurementProcess . FILTER NOT EXISTS { ?p :hasParticipant ?x } }",
  "expected": "empty", "type": "Validating" }
```

## `list_competency_questions`

List every competency question found across all storage conventions, each tagged with its `convention`.
Reports `conventions_found` and any `skipped` entries that could not be parsed (malformed input is
isolated, never fatal). *Read-only; no arguments.*

**Returns**

- `count`: integer, total CQs loaded.
- `conventions_found`: array of convention ids present.
- `competency_questions`: array of CQ objects (each with its `convention`).
- `skipped`: array of `{source, reason}` for entries that could not be loaded.

## `remove_competency_question`

Remove a competency question by id. Pass `convention` to disambiguate when the same id exists in more than
one store; otherwise the single store holding it is used. Gated by the write-consent preferences.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `id` | string | yes | — | The competency question id to remove. |
| `convention` | string | no | *(auto)* | Required only when the id exists in several stores. |

**Returns**

- `removed`: boolean.
- `id`: string, the id.
- `convention`: string, the store removed from.

## `run_competency_questions`

Re-run the competency-question suite against the active ontology — a **regression check** for curation
edits. Each CQ's query runs over a shared point-in-time snapshot (asserted triples, plus the reasoner's
inferences for CQs with `include_inferred=true` when a classified reasoner is available), judged by its
expected result. Filter with `ids` or `convention`; `fail_on=any` makes the overall `gate` fail if any CQ
fails. Mandatory caveats (open-world `empty`, truncated results/inferences) are surfaced, never silent.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `ids` | array | no | *(all)* | Only run these CQ ids. |
| `convention` | string | no | *(all detected)* | Only run CQs from this convention. |
| `limit` | integer | no | 1000 | Max rows per query. |
| `timeout_ms` | integer | no | 120000 | Per-query time budget. |
| `fail_on` | string | no | `none` | Gate policy: `none` \| `any`. |

**Returns**

- `total` / `passed` / `failed`: integers.
- `gate`: string, `"pass"` or `"fail"` (honours `fail_on`).
- `questions`: array, per-CQ `{id, text?, expected, convention, pass, actual_summary, ms, caveats?}` (or
  `error` when the query could not run).
- `skipped`: array of `{source, reason}` for CQ entries that could not be loaded.

**Example**

```json
{ "fail_on": "any" }
```
