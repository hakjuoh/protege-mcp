---
title: "Safe authoring & QC"
parent: "Tools"
nav_order: 3
---

# Safe authoring & QC
{: .no_toc }

The safe, testable authoring tools: run project-defined SPARQL **invariants**, a re-runnable
**competency-question** requirements suite, discover a version-controlled **project policy**, and execute
either an interactive or strict reproducible **quality-control gate**. They
close the **propose → ground → verify → confirm** loop — pair them with `apply_changes verify=`
([Editing](editing.html)) and `search_entities` grounding ([Explore & search](explore-search.html)).

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
**cannot run** — a malformed/timed-out query, an `include_inferred` invariant with no usable reasoner,
or a rejected non-`SELECT`/`ASK` form — fails **fail-closed**: it never silently degrades to the asserted
triples and reports a false pass.

*Read-only.* Inline `queries[]` remain supported. A project policy can additionally reference persisted,
ROBOT-compatible `.rq` files for [`run_project_qc`](#run_project_qc); their leading metadata comments are
`# id:`, `# message:`, `# severity:`, and `# include_inferred:`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `queries` | array | yes | — | The invariants. Each: `{ sparql (required), id?, message?, severity?, include_inferred? }`. |
| `queries[].sparql` | string | yes | — | A SPARQL `SELECT`/`ASK` whose results are violations (`CONSTRUCT`/`DESCRIBE` are rejected). |
| `queries[].id` | string | no | `invariant-N` | Stable id. |
| `queries[].message` | string | no | `Invariant '<id>' violated.` | Human message describing the violation. |
| `queries[].severity` | string | no | `error` | `error` \| `warn` \| `info`. |
| `queries[].include_inferred` | boolean | no | false | Run over the reasoner's inferred triples. |
| `fail_on` | string | no | `error` | Gate severity: `none` \| `info` \| `warn` \| `error`. |
| `limit` | integer | no | 1000 | Max violation rows per invariant. |
| `timeout_ms` | integer | no | 120000 | Per-invariant time budget. |

**Returns**

- `checked`: integer, total number of invariants supplied (= those that ran + `errors`).
- `violations`: integer, number of invariants that matched (a forbidden pattern present).
- `errors`: integer, number of invariants that could not run (each also fails the gate fail-closed).
- `fail_on`: string, the effective gate severity.
- `gate`: string, `"pass"` or `"fail"` (fails when the worst violation/error reaches `fail_on`).
- `invariants`: array, per-invariant `{id, severity, message, violated}`; for a **`SELECT`** violation
  also `violation_count`, `violations` (the raw `{type,value,lang?,datatype?}` bindings), and
  `truncated: true` when those rows were capped at `limit`; an **`ASK`**-true violation reports just
  `violation_count: 1` (no `violations`); or `error` when it could not run (a query error, an
  `include_inferred` invariant with no usable reasoner, or a rejected non-`SELECT`/`ASK` form).
  A `caveats` entry flags a *soft* qualifier such as truncated inferences.

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

## `get_project_policy`

Discover and return the effective project policy. An explicit local `policy_path` wins; otherwise the tool
walks from the active ontology document upward for `.protege-mcp/project.yaml`. It strictly parses YAML,
validates schema v1, applies deterministic defaults, and checks the active ontology IRI, installed required
reasoner, CURIEs/regexes, referenced files, project-root confinement, and symlink escapes. No policy is a
compatible interactive state: `policy_loaded=false` and `path_mode=legacy_local_admin_unrestricted` are
reported explicitly. *Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `policy_path` | string | no | discovered | Explicit local `project.yaml`; no URL schemes. |

**Returns**

- `policy_loaded`: boolean; whether a file was selected.
- `valid`: boolean; schema, semantic, live-context, and path validation outcome.
- `discovery`: `explicit`, `discovered`, or `none`.
- `path_mode`: `policy_confined` or the reported legacy compatibility mode.
- `active_ontology_iri`: active ontology IRI, or null for an anonymous ontology.
- `policy_path` / `project_root`: resolved local paths when loaded.
- `policy_digest`: SHA-256 over canonical effective policy data (comments/key order do not change it).
- `schema_version`: integer policy schema version.
- `policy`: effective policy object with deterministic defaults.
- `resolved_assets`: paths grouped as modules/import lock/invariants/SHACL/CQs/release output.
- `errors` / `warnings`: structured `{severity, code, path?, message}` validation issues.

## `validate_project_policy`

Run the same strict discovery, schema, semantic, reasoner, and filesystem validation as
`get_project_policy`, but require a policy: no discovered/explicit file makes `valid=false` with a structured
`policy_not_found` error. It never edits the YAML or ontology. Arguments and return fields are identical to
`get_project_policy`.

**Returns**

- `policy_loaded`: whether a policy file was found and read.
- `valid`: the validation verdict.
- `discovery`: how the policy was located.
- `path_mode`: explicit or discovered.
- `active_ontology_iri`: the ontology the policy was resolved against.
- `policy_path`: the resolved policy file.
- `project_root`: the effective project root.
- `policy_digest`: canonical digest of the effective policy.
- `schema_version`: its schema version.
- `policy`: the effective (defaulted) policy object.
- `resolved_assets`: the validation assets the policy references.
- `errors`: structured validation errors.
- `warnings`: non-fatal issues.

## `run_project_qc`

Execute the effective policy as a strict gate. Policy-referenced invariant globs are expanded in sorted,
deduplicated order; multiple SHACL graphs are unioned without using Jena's URL resolver; the configured CQ
convention/path is loaded fail-closed. Every `validation.required_stages` entry must run against the one
captured ontology revision. Every stage reads one private, no-network copy; one configuration-equivalent
private reasoner supplies the logical verdict and any requested inferred query graph.
The governance stage also evaluates policy label-language/preferred cardinality, definition
presence/language/datatype/placeholder, lifecycle status/replacement, and waiver rules against that snapshot.
A live waiver suppresses only its matching rule/focus and remains visible; an expired waiver is itself a finding.
A completed policy violation is `gate=fail`; invalid configuration, wrong/
unavailable reasoner, classification failure/timeout, malformed asset, inference degradation, or a skipped/
errored required stage is `gate=error` and takes precedence over a separate failure. *Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `policy_path` | string | no | discovered | Explicit local policy path. |
| `limit` | integer | no | 25 | Samples per stage (0–10000). |

**Returns**

- `gate`: `pass`, `fail`, or `error`.
- `policy_loaded`, `policy_version`, `policy_digest`, `project_id`, `policy_path`, `project_root`.
- `semantic_fingerprint`: fingerprint of the exact shared ontology snapshot.
- `fingerprint_stability`, `release_stable`, `fingerprint_warnings`: cross-restart guarantee; anonymous
  individuals explicitly degrade the digest to a same-session token.
- `reasoner`: the reasoner selected at the shared-snapshot boundary.
- `required_stages`, `stages_ran`, `stages_skipped`, `fail_on`, and `stages` (each includes `required` and
  strict `status`).
- `details`: normalized gate metadata — `{fail_on, missing_required_stages?}`. A completed reasoner stage's details include `reasoner_configuration` with requested and
  runtime-exposed parity/caveat fields.
- `findings`: common finding objects with `id`, `source`, `severity`, `message`, `focus_iri`, `axiom`, `path`,
  `rule_id`, `waiver`, and optional validator `details`.
- `artifacts`: checksum artifact references (empty until release/report artifacts are requested).
- `snapshot_consistent`: `true` when the complete gate used the captured revision; validation no longer has a
  live-classification gap requiring a second ontology read.
- `validation_snapshot`: `{mode, same_snapshot, semantic_fingerprint, closure_fingerprint, stages}`. `mode` is
  `isolated`; `stages` names every stage (including `reasoner`) that completed on that copy. A policy or asset
  error detected before capture reports `mode=none`, `same_snapshot=false`, and
  `closure_fingerprint=null`.
- `resolved_assets`: exact policy assets used.
- `surface`: `run_project_qc` (or `run_qc_suite` when invoked through its `policy_path` compatibility entry).
- `errors`: structured policy/configuration errors when validation cannot start.

## `run_qc_suite`

One aggregate quality-control gate that composes every stage over **one isolated shared snapshot** and
collapses them to a single verdict. The selected reasoner's exact Protégé plugin configuration and buffering
mode are captured with the ontology; QC does not classify or query the live reasoner.
Stages (default `reasoner` + `profile` + `structural`): `reasoner`
(consistency + no unsatisfiable classes), `profile` (OWL 2 profile conformance — gated on the violations
the active ontology OWNS, including ontology-header violations OWLAPI reports without a backing axiom;
violations inherited from imports are surfaced as `imported_violations` context only), `structural`
(`validate_ontology`'s modelling-quality checks — only *warning*-severity smells gate), `invariants`
(`verify_ontology`-style SPARQL invariants — pass them in `invariants`), `cqs` (the competency-question
suite), `governance` (IRI/annotation/import-layering policy), and `shacl` (validate against the SHACL shapes in `shacl_shapes` / `shacl_shapes_path`). A stage
whose backing data is absent (no selected reasoner, no invariants, no CQs, no SHACL shapes) is
**skipped with a reason, never an error**; the overall `gate` is the
worst *ran* stage versus `fail_on`. Legacy calls retain that behavior. Supplying `policy_path` delegates to
the strict `run_project_qc` semantics; `required_stages` / `error_on_missing_required` also opt an inline
call into missing-required-to-error behavior.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `stages` | array | no | `["reasoner","profile","structural"]` | Subset of `reasoner`, `profile`, `governance`, `structural`, `invariants`, `cqs`, `shacl`. |
| `required_stages` | array | no | — | Stages that must complete; they are scheduled even when absent from `stages`. |
| `error_on_missing_required` | boolean | no | false | Treat all requested stages (or `required_stages`) as required and return `gate=error` if one cannot run. |
| `policy_path` | string | no | — | Use strict project-policy discovery/assets/QC. |
| `owl_profile` | string | no | `DL` | OWL 2 profile for the `profile` stage: `DL` \| `EL` \| `QL` \| `RL`. Pass `none` or `Full` to skip the `profile` stage (reported as a skipped stage, not an error). |
| `invariants` | array | no | — | Invariants for the `invariants` stage (same shape as `verify_ontology`'s `queries[]`). |
| `shacl_shapes` | string | no | — | SHACL shapes graph as Turtle (inline) for the `shacl` stage. |
| `shacl_shapes_path` | string | no | — | Local file path to a SHACL shapes document for the `shacl` stage. |
| `required_namespaces` / `iri_pattern` / `required_annotations` / `check_ownership` | mixed | no | — | Inline `governance` stage controls. |
| `fail_on` | string | no | `error` | Gate severity: `none` \| `warn` \| `error`. |
| `limit` | integer | no | 25 | Max samples per check. |
| `timeout_ms` | integer | no | 120000 | Time budget for isolated reasoning, SPARQL, and SHACL stages. |

**Returns**

- `gate`: string, `"pass"` or `"fail"` (the worst *ran* stage vs `fail_on`).
- `fail_on`: string, the effective gate severity.
- `stages_ran`: integer, how many stages actually ran.
- `stages`: array, per-stage `{stage, ran, verdict?, findings_summary?, reason?}` (`verdict` is
  `pass`/`info`/`warn`/`fail` when it ran; `reason` explains a skip). Strict mode additively returns
  `policy_loaded`, `semantic_fingerprint`, `required_stages`, `stages_skipped`, `findings`, `artifacts`,
  `fingerprint_stability`, `release_stable`, `reasoner`, `snapshot_consistent`, `validation_snapshot`, and `details`, and each stage gains
  `required` + `status`. The `reasoner` stage's
  `findings_summary` may carry a `warning` when the ontology has SWRL rules the selected reasoner
  silently ignores (ELK) — surfaced, deliberately **not** gated (an ELK + SWRL setup can be
  intentional, but a pass must never read as "the rules were checked").

**Example**

```json
{ "stages": ["reasoner", "profile", "structural", "cqs"], "owl_profile": "EL", "fail_on": "warn" }
```

## `shacl_validate`

Validate the active ontology's imports-closure RDF against a **SHACL shapes graph** using the embedded Apache Jena SHACL engine — the constraint-validation counterpart to `verify_ontology`'s SPARQL invariants. Provide the shapes **inline** as Turtle in `shapes`, or a **local file** path in `shapes_path` (a URL scheme is refused — remote SHACL fetch is disabled for offline safety, matching `sparql_query`). By default it runs over the **asserted** triples; set `include_inferred=true` to first materialise the active reasoner's inferences (`run_reasoner` first). Read-only.

| Argument | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `shapes` | string | one of | — | SHACL shapes graph as Turtle text (inline). |
| `shapes_path` | string | one of | — | Path to a **local** SHACL shapes document (format from the extension). |
| `include_inferred` | boolean | no | `false` | Validate over the reasoner's inferred triples (requires a classified reasoner). |
| `limit` | integer | no | `1000` | Max validation results to return. |
| `timeout_ms` | integer | no | `120000` | Time budget for **both** the data snapshot and the validation. |

**Returns** `conforms` (boolean), `total_results`, the `violations` / `warnings` / `infos` counts and `worst_severity`, plus `results[]` — each `{focus_node, result_path, value, severity, constraint_component, source_shape, message}` (capped at `limit`, with `truncated` when more remain).

## `add_competency_question`

Add or update (by id) a **competency question** — a SPARQL query plus an expected result that
`run_competency_questions` later re-checks (a requirement test for the ontology). CQs are stored in one of
three conventions; omit `convention` to follow an existing store, else it defaults to `robot-sparql-dir`
(or `ontology-annotations` when the ontology is unsaved). This writes a file (or an ontology annotation
under the [`cq:competencyQuestion`](../cq.html#competencyQuestion) property — see the
[CQ vocabulary](../cq.html)) and echoes where. Gated by the write-consent preferences.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `query` | string | yes | — | Executable SPARQL 1.1 query — `SELECT` or `ASK`. |
| `id` | string | no | `CQ-N` | Stable id within the store (auto-minted if omitted). |
| `text` | string | no | — | The natural-language competency question (recommended). |
| `type` | string | no | — | Optional category, e.g. `Scoping` \| `Validating`. |
| `query_lang` | string | no | `sparql` | Only `sparql` is supported. |
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
