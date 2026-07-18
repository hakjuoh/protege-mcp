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

In 0.6.1, caller-selected filesystem paths require request-scoped project read/write capabilities and
are confined by the effective policy; project/change-set gates also verify locked import content, module
namespace ownership, cycles, and loaded import identity conflicts automatically.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `verify_ontology`

Run project-defined SPARQL **invariants** over the active ontology — patterns that must *never* appear
(the ROBOT `verify` model). Each `queries[]` item is a SPARQL `SELECT` or `ASK` whose **results are
violations**: any returned row (or `ASK` true) flags it, at the item's severity. A graph-producing
`CONSTRUCT`/`DESCRIBE` is *not* a detector and is **rejected** (use [`sparql_query`](sparql.html) for those).
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
reasoner, CURIEs/regexes, referenced files, project-root confinement, and symlink escapes. With the
default preferences, no policy is a compatible interactive state: `policy_loaded=false` and
`path_mode=legacy_local_admin_unrestricted` are reported explicitly. When **Allow unrestricted
local-admin paths when no project policy is loaded** is disabled in Settings ▸ MCP, the same state
reports `path_mode=policy_required` and every caller-selected path or document URL is refused until a
policy loads (saves and sidecars derived from the already-open document still work). *Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `policy_path` | string | no | discovered | Explicit local `project.yaml`; no URL schemes. |

**Returns**

- `policy_loaded`: boolean; whether a file was selected.
- `valid`: boolean; schema, semantic, live-context, and path validation outcome.
- `discovery`: `explicit`, `discovered`, or `none`.
- `path_mode`: `policy_confined` (a policy governs paths), `legacy_local_admin_unrestricted` (no policy, compatibility preference on), or `policy_required` (no policy, compatibility preference off — caller-selected paths refused).
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
| `lock_mode` | string | no | `ignore` | Request-level import-lock verification. With `imports.mode: locked` the gate verification always runs, whatever this says (it can never weaken the policy). Otherwise `verify` compares the loaded closure against the lockfile resolved with the `verify_import_lock` rules — the policy-declared `imports.lockfile` when exactly one resolves, else the beside-active-document `imports.lock.json` — and skips cleanly with a reported note when that default file does not exist; `required` turns exactly that file-absent state into the `imports.lock_missing` error finding. The released refusal states (declared-but-unresolved lockfile, invalid policy, no local document folder) abort as a configuration error. |

**Returns**

- `gate`: `pass`, `fail`, or `error`.
- `policy_loaded`, `policy_version`, `policy_digest`, `project_id`, `policy_path`, `project_root`.
- `semantic_fingerprint`: fingerprint of the exact shared ontology snapshot.
- `rdf_dataset_fingerprint`, `rdf_dataset_identity`: W3C RDFC-1.0 + SHA-256 identity of the asserted
  root ontology RDF dataset, plus the selected RO-Crate/profile/canonicalization coordinates. This is
  distinct from the editor/revision-oriented `semantic_fingerprint`.
- `fingerprint_stability`, `release_stable`, `fingerprint_warnings`: cross-restart guarantee; anonymous
  individuals explicitly degrade the digest to a same-session token.
- `reasoner`: the reasoner selected at the shared-snapshot boundary.
- `required_stages`, `stages_ran`, `stages_skipped`, `fail_on`, and `stages` (each includes `required` and
  strict `status`). Gating stage details include `identity_digest`, a SHA-256 identity over the complete
  finding set used for baseline attribution even when public examples are capped.
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
- `import_lock_verification`: automatic full coordinate/checksum comparison when `imports.mode` is
  `locked`; any mismatch forces `gate=error` and an `imports.lock_mismatch` finding. Also present on
  explicit request (`lock_mode=verify|required`), where it additionally carries `lockfile_source`
  (`policy_declared` or `beside_document`) and — for a `beside_document` lockfile — a `lockfile_note`
  stating that the verification attests accident-safety, not tamper-evidence; a `verify` request whose
  resolved default lockfile does not exist reports `{verified: false, skipped: true, path,
  lockfile_source, note}` without gating, while `required` makes exactly that state an
  `imports.lock_missing` error finding.
- `surface`: `run_project_qc` (or `run_qc_suite` when invoked through its `policy_path` compatibility entry).
- `errors`: structured policy/configuration errors when validation cannot start.

## `run_release_gate`

Read-only release gate. It runs the full `run_project_qc` gate and then the release-only checks, and
reports exactly what `prepare_release` *would* produce — writing nothing. On top of QC it verifies:
**import provenance** — under `network=deny` (the default) every closure member must be backed by an
authorized local file, otherwise an `imports.remote_backed` error is raised (`network=allow` keeps the
members but records a `network_caveat` instead, and never overrides a policy network deny); the **version
IRI** when `release.require_version_iri` is set; a **verified serialization round trip** in
`release.format` (the same round-trip machinery as `save_ontology verify_round_trip=true`) when
`release.require_clean_round_trip` is set; **fingerprint stability** (a `session_only` fingerprint blocks
a reproducible manifest); and an optional **baseline** comparison (asserted-axiom diff only — inferred
baseline diff is deferred). The gate is `error > fail > pass`. Timestamps read `PREVIEW`, never the wall
clock; `prepare_release` stamps the real `created_at`. *Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `policy_path` | string | no | discovered | Explicit local policy path. |
| `network` | string | no | `deny` | Provenance posture: `deny` (a non-local closure member is an `imports.remote_backed` error) or `allow` (kept as a recorded `network_caveat`). |
| `baseline_manifest` | string | no | none | Path to a prior release `manifest.json` to compare against (asserted diff only). Read with project containment. |
| `limit` | integer | no | 50 | Samples/findings per stage and in the report excerpt (0–10000). |

**Returns**

- `gate`: `pass`, `fail`, or `error` (`error > fail > pass`).
- `semantic_fingerprint`: fingerprint of the exact shared ontology snapshot.
- `required_stages`, `stages`, `findings`: the aggregated core gate — the QC stages plus a synthetic
  required `release` stage carrying the release-specific findings (`imports.remote_backed`,
  `release.version_iri_missing`, `release.round_trip_failed`, `release.fingerprint_unstable`).
- `resolved_imports`: always present — every closure member as `{ontology_iri, version_iri, document,
  source_type, backed_by}`, where `backed_by` is `local_file` for a `file:` document and otherwise the
  source type (`remote`, `memory`, …).
- `network_caveat`: present only when remote-backed members were downgraded under `network=allow`.
- `version_iri`: the active ontology's version IRI (or `null`).
- `round_trip`: `{clean, format}` (plus `skipped`/`reason`/`error` when the round trip did not run or
  was not exact).
- `baseline`: `{compared, status, …}` — `not_compared` when no baseline was given; otherwise the
  per-artifact digest checks and the asserted diff's `compatibility` classification.
- `manifest_preview`: the would-be `manifest.json` map (`ReleaseManifest.build` inputs rendered), or
  `{manifest_available: false, reason}` when the fingerprint is unstable or the serialization failed.
- `reports_preview`: `{markdown (bounded excerpt), formats: [json, markdown, junit, sarif]}`.

## `prepare_release`

Produce the release bundle once `run_release_gate` passes. It re-runs the full release gate and, only on
`gate=pass`, computes the verified ontology serialization, the `manifest.json`, the four reports
(`reports/qc.json`, `reports/qc.md`, `reports/qc.xml`, `reports/qc.sarif`), the RO-Crate
`ro-crate-metadata.json`, and `reports/diff.json` when a baseline is given. A gate that does not pass is
refused with structured findings and **no writes**. **Dry run by default:** `dry_run=true` returns the
manifest, reports, and crate content inline (bounded) and writes nothing. `dry_run=false` honors
Protégé's **read-only** mode and the **confirm-writes** gate, then writes every artifact atomically into
the output directory via the containment-enforcing artifact store, each with its `sha256` recorded in the
manifest. Output is deterministic except for the declared `created_at`. **No network access is ever
performed.**

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `policy_path` | string | no | discovered | Explicit local policy path. |
| `network` | string | no | `deny` | Provenance posture (see `run_release_gate`). |
| `baseline_manifest` | string | no | none | Prior release `manifest.json` to diff against; written as `reports/diff.json`. |
| `dry_run` | boolean | no | `true` | When `true`, compute and return every artifact inline but write nothing. |
| `created_at` | string | no | — | ISO-8601 UTC timestamp stamped into the manifest and crate. When omitted and `dry_run=false`, the system clock is read once; a dry run uses a `PREVIEW` placeholder. |
| `output_dir` | string | no | policy `release.output_dir` | Release output directory, resolved with project containment — artifacts can never escape it. |
| `limit` | integer | no | 50 | Samples/findings per stage and in the report excerpt (0–10000). |

**Returns**

- `prepared`: `true` on a committed write, `false` on a dry run or a refused gate.
- `dry_run`: echoes the requested mode.
- `gate`: `pass`, `fail`, or `error`; a non-`pass` gate is refused with no writes.
- `output_dir`: the resolved release output directory (committed writes only).
- `created_at`: the timestamp stamped into the manifest and crate (committed writes only).
- `artifacts`: each artifact as `{path, sha256, bytes}` — the would-be set on a dry run, the written set
  on a commit, empty on a refusal.
- `manifest`, `reports`, `ro_crate`: the manifest map, bounded report content, and RO-Crate content
  returned inline on a dry run.
- `findings`, `stages`: the aggregated release gate findings and stages, surfaced on a refusal.

## `write_project_policy_template`

Generate a commented, schema-valid **starter** `.protege-mcp/project.yaml` to review and commit like
source code. This scaffolds a **new** policy file — it never mutates the ontology or an existing policy in
place. The required blocks (`version`, `project_id`, `root_ontology`, `interoperability`) are populated
from the active ontology; safe defaults are filled in (filesystem/network **deny**, unlocked imports, a
named reasoner, the base QC stages); and every asset-referencing optional block (prefixes, modules,
annotations, `iri_policy`, lifecycle, invariants/shacl/competency-questions, the imports lockfile,
release) is commented out with guidance. The template also names a `root_artifact` and an RO-Crate
metadata file you must still create, so **it is not valid on its own**: the result carries a
`validation_hint` listing what to complete and never claims `valid=true`. The write honors Protégé's
**read-only** mode and the **confirm-write** gate, requires the `filesystem:project:write` capability,
resolves the target under `project_root` (default `.protege-mcp/project.yaml` beside the active
document), and lands atomically via a temp-file rename. `overwrite=false` refuses an existing file with
`error_code: policy_exists` and writes nothing.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path` | string | no | `.protege-mcp/project.yaml` beside the document | Explicit project-relative or absolute policy path to write. |
| `project_id` | string | no | derived from the ontology IRI's last segment, else `my-project` | Project identifier written into the template. |
| `profile` | string | no | `general` | Which starter to emit: `general` (OWL/Turtle, HermiT/DL) or `obo` (OBO edit file, ELK/EL). |
| `overwrite` | boolean | no | `false` | Replace an existing file; otherwise an existing target is refused with `policy_exists`. |

**Returns**

- `written`: `true` when the template landed, `false` on a refusal.
- `path`: the canonical path the template was written to.
- `project_id`: the identifier written into the template.
- `profile`: the emitted profile (`general` or `obo`).
- `bytes`: the size in bytes of the written file.
- `sha256`: the SHA-256 of the written bytes.
- `validation_hint`: the ordered list of files to create and edits to make before the policy validates.
- `note`: a reminder to review and commit the file and complete the `validation_hint` items.
- `error_code`: `policy_exists` when a file already exists and `overwrite` is false (with `written: false`).

## `run_qc_suite`

One aggregate quality-control gate that composes every stage over **one isolated shared snapshot** and
collapses them to a single verdict. The selected reasoner's exact Protégé plugin configuration and buffering
mode are captured with the ontology; QC does not classify or query the live reasoner.
Stages (default `reasoner` + `profile` + `structural`): `interoperability` (policy mode only: validated
RO-Crate plus the W3C RDFC-1.0 root-dataset identity), `reasoner`
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
| `stages` | array | no | `["reasoner","profile","structural"]` | Subset of `interoperability`, `reasoner`, `profile`, `governance`, `structural`, `invariants`, `cqs`, `shacl`. `interoperability` needs a validated `policy_path`. |
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
| `lock_mode` | string | no | `ignore` | Request-level import-lock verification, honored on the strict policy branch (with `policy_path`) with exactly the `run_project_qc` semantics. The legacy inline branch performs no lock verification, so it refuses `verify`/`required` with an explicit error directing the caller to `policy_path`/`run_project_qc` — never a silent pass — and rejects invalid values. |

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

**Returns** `conforms` (boolean), `total_results`, the `violations` / `warnings` / `infos` counts, `worst_severity`, and `identity_digest` (SHA-256 over all violation/warning identities), plus `results[]` — each `{focus_node, result_path, value, severity, constraint_component, source_shape, message}` (capped at `limit`, with `truncated` when more remain).

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
| `expected` | string | no | `nonEmpty` | Pass condition: `nonEmpty` \| `empty` \| `count OP N` (`OP` ∈ `>=,<=,==,>,<`). `exactRows` is authored in a `sidecar-manifest` and compares an order- and duplicate-insensitive row set. |
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
