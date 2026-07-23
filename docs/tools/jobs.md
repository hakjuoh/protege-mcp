---
title: "Asynchronous jobs"
parent: "Tools"
nav_order: 14
---

# Asynchronous jobs
{: .no_toc }

Run bounded work against immutable captures without holding an MCP request open. Jobs are available only
from the live Protégé plugin and belong to one window and the exact principal/client/grant that started
them. They are intentionally in-memory: closing the window or restarting the backend cancels the work,
deletes private artifacts, and makes old job IDs unavailable.

The 0.8.0 job types are `classification`, `project_qc`, `semantic_diff`, and
`inference_materialization`. Reasoner jobs require an exact reviewed profile whose five-second live
cancellation probe succeeds. A completed structural classification reports unsupported consistency and
satisfiability evidence instead of presenting structural traversal as a semantic consistency verdict.
Materialization likewise fails when its exact profile cannot prove every requested capability.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `start_job`

Capture all ontology, policy, reasoner, and secondary-file inputs once and submit one job. For 15 minutes,
the same owner, workspace, type, `idempotency_key`, and input identity returns the existing job. Reusing
the key with different inputs returns `idempotency_conflict`.

**Arguments**

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `type` | enum | yes | `classification`, `project_qc`, `semantic_diff`, or `inference_materialization`. |
| `idempotency_key` | string | yes | Caller-chosen key, 1–128 characters. |
| `request` | object | yes | Closed type-specific request described below. |

Classification accepts `limit` (0–10,000) and `policy_path`. Project QC accepts `limit`,
`lock_mode` (`ignore`, `verify`, or `required`), and `policy_path`. Semantic diff requires a
project-confined `right_document`; it also accepts `limit`, `network`, `include_imports`, and
`policy_path`. In 0.8.0 the asynchronous adapter requires `network=deny` and
`include_imports=false`, because it captures exactly one local secondary root.

Inference materialization requires `categories`, a `destination` (`new_ontology` or
`active_source` plus identifier), `provenance` (`generator` and `purpose`), and `limits`
(`max_axioms_per_category`, `max_axioms_total`, `max_bytes`, and `timeout_ms`); `policy_path` is
optional. Live jobs retain the same 500-axiom preview ceiling as the synchronous live adapter.

**Returns**

- `job`: the complete owner-scoped job descriptor at acceptance or idempotent recovery.
- `reused`: whether an existing idempotency record was returned.

**Example**

```json
{
  "type": "semantic_diff",
  "idempotency_key": "compare-release-candidate-4",
  "request": {
    "right_document": "baselines/release-candidate.ttl",
    "include_imports": false,
    "network": "deny"
  }
}
```

## `get_job`

Return the latest immutable snapshot of one owned job. A missing ID, another workspace, or a different
principal/client/grant all produce the same `unknown_job` error.

**Arguments**

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `job_id` | UUID | yes | Opaque ID returned by `start_job`. |

**Returns**

- `job`: descriptor containing identity, timestamps, state, progress, cancellation flags, typed
  `result` or `error`, and private artifact references.

**Example**

```json
{ "job_id": "00000000-0000-4000-8000-000000000001" }
```

## `cancel_job`

Set the monotonic cancellation tombstone without waiting for blocking computation. Queued work becomes
cancelled immediately. Running work may report `cancel_pending` until its private computation stops and
late output is discarded. Once publication or commit has started, cancellation reports
`commit_in_progress`.

**Arguments**

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `job_id` | UUID | yes | Exact owned job to cancel. |

**Returns**

- `job`: descriptor observed after the cancellation request.
- `outcome`: `cancelled`, `cancel_requested`, `already_terminal`, or `commit_in_progress`.

**Example**

```json
{ "job_id": "00000000-0000-4000-8000-000000000001" }
```

## `list_jobs`

List only the exact owner's jobs, newest first. The opaque cursor is stable for its owner and anchor; it
cannot be transferred to another principal.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `limit` | integer (1–100) | no | `50` | Maximum descriptors returned. |
| `cursor` | string | no | — | Opaque continuation cursor. |

**Returns**

- `jobs`: bounded page of owned job descriptors.
- `next_cursor`: optional cursor for the next page.

**Example**

```json
{ "limit": 25 }
```

## `export_job_artifact`

Copy one retained private artifact to an authorized project path. Export verifies ownership and content,
requires explicit confirmation, and publishes through a temporary file plus atomic move. Existing targets
are protected unless `overwrite=true` and `expected_target_digest` identifies the exact reviewed file.

*Persistence (atomic destination-file mutation; not an ontology undo step).*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `job_id` | UUID | yes | — | Succeeded job that owns the artifact. |
| `artifact_id` | UUID | yes | — | Opaque artifact reference from the job result. |
| `destination` | string | yes | — | Authorized project-confined output path. |
| `confirm` | boolean (`true`) | yes | — | Explicit export confirmation. |
| `overwrite` | boolean | no | `false` | Permit replacement of an existing target. |
| `expected_target_digest` | SHA-256 digest | on overwrite | — | Digest of the reviewed target being replaced. |
| `policy_path` | string | no | discovered policy | Explicitly select an authorized project policy. |

**Returns**

- `exported`, `job_id`, `artifact_id`: export outcome and source identity.
- `path`, `sha256`, `bytes`: verified destination and content identity.
- `overwritten`: whether an existing target was replaced.
- `interactive_write_confirmation`: whether the configured interactive confirmation mode was active.

**Example**

```json
{
  "job_id": "00000000-0000-4000-8000-000000000001",
  "artifact_id": "00000000-0000-4000-8000-000000000002",
  "destination": "reports/job-result.json",
  "confirm": true
}
```

The destination's parent directory must already exist. Export never creates
directories as a side effect; this lets the guarded transaction pin the parent
directory before staging or publication.
