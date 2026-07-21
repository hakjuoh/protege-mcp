---
title: "SSSOM mappings"
parent: "Tools"
nav_order: 11
---

# SSSOM mappings
{: .no_toc }

Create and exchange governed [SSSOM 1.0](https://mapping-commons.github.io/sssom/) mappings without
leaving the ontology project. Policy v2 fixes the canonical path through `mappings.path`; an explicit
`path` may only repeat that same path. Policy v1 requires `path`. With no loaded policy, the live
Protégé server requires an explicit path below the active ontology document directory and its existing
local-admin compatibility permission. Headless stdio always requires a valid project policy.

The canonical file is a bounded TSV with a YAML comment header. Every write uses a caller-supplied
`expected_mapping_revision`, an inter-process project lock, validation before commit, atomic replacement,
and a verified backup when replacing an existing file. Mapping revisions are SHA-256 digests of canonical
bytes. Read cursors and exports are bound to that revision, so a concurrent edit returns a conflict instead
of mixing pages or exporting a source state different from the one the caller reviewed.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `list_mappings`

List deterministic mapping rows from the canonical store. An absent store is a valid empty state with
its own revision. Pagination order is canonical and a `cursor` cannot be reused after the store changes.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path` | string | policy v1/no policy only | policy v2 `mappings.path` | Canonical mapping-store path. |
| `policy_path` | string | no | discovered policy | Live Protégé only: explicitly select an already-authorized project policy. |
| `limit` | integer (1–200) | no | `50` | Maximum rows returned; the 1 MiB JSON page budget may end a page earlier. |
| `cursor` | string | no | — | Opaque revision-bound continuation cursor. |

**Returns**

- `path`, `exists`, `mapping_revision`, `canonical_bytes`, `record_count`: store identity and size.
- `valid`, `error_count`, `warning_count`, `findings_truncated`: validation summary for this revision.
- `items`: rows with `mapping_id`, `subject_id`, `predicate_id`, `object_id`, and losslessly retained `cells`.
- `returned`: number of rows in this page.
- `next_cursor`: optional cursor for the next page.

**Example**

```json
{ "limit": 50 }
```

## `add_mapping`

Validate and add one mapping. If `mapping_id` is absent or empty, the tool derives a stable SHA-256 ID
from the normalized SSSOM identity fields. The first add to an absent store also requires the mapping-set
metadata. The write is rejected if the new document has validation errors.

*Persistence (atomic project-file mutation; not an ontology undo step).*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `expected_mapping_revision` | SHA-256 revision | yes | — | Revision returned by the latest list/validate/write. |
| `mapping` | string object | yes | — | SSSOM row cells; at least endpoints, predicate, and `mapping_justification` are normally required. |
| `mapping_set_id` | absolute IRI | first add | — | Mapping-set identifier used to initialize an absent store. |
| `license` | absolute IRI | first add | — | Mapping-set license used to initialize an absent store. |
| `prefix_map` | string object | no | — | Initial CURIE prefixes for an absent store. |
| `path`, `policy_path` | string | conditional | — | Path selection as described above. |
| `confirm` | boolean | yes | — | Must be `true`; the live server's configured human confirmation gate still applies. |

**Returns**

- `committed`, `path`, `previous_mapping_revision`, `mapping_revision`, `record_count`, `bytes`: commit result.
- `backup_path`: optional verified backup of the prior canonical store.
- `valid`, `error_count`, `warning_count`, `findings_truncated`: committed-document validation summary.

**Example**

```json
{
  "expected_mapping_revision": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "mapping": {
    "subject_id": "https://example.org/source#A",
    "predicate_id": "skos:exactMatch",
    "object_id": "https://example.org/target#A",
    "mapping_justification": "semapv:ManualMappingCuration"
  },
  "mapping_set_id": "https://example.org/mappings",
  "license": "https://creativecommons.org/licenses/by/4.0/",
  "confirm": true
}
```

## `remove_mapping`

Remove exactly one row by its stable `mapping_id`. The expected revision prevents deleting from a state
different from the one the caller reviewed.

*Persistence (atomic project-file mutation; not an ontology undo step).*

**Arguments**

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `expected_mapping_revision` | SHA-256 revision | yes | Current canonical-store revision. |
| `mapping_id` | string | yes | Exact stable ID returned by `list_mappings`. |
| `path`, `policy_path` | string | conditional | Path selection as described above. |
| `confirm` | boolean | yes | Must be `true`; normal live confirmation also applies. |

**Returns**

- `committed`, `path`, `previous_mapping_revision`, `mapping_revision`, `record_count`, `bytes`: commit result.
- `backup_path`: verified prior-store backup.
- `valid`, `error_count`, `warning_count`, `findings_truncated`: committed-document validation summary.

**Example**

```json
{ "expected_mapping_revision": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "mapping_id": "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", "confirm": true }
```

## `import_sssom`

Read a project-confined SSSOM 1.0 TSV through a stable, bounded snapshot, validate it, then atomically
`replace` the canonical store or `merge` it by stable mapping ID. Merge preserves canonical metadata,
prefixes, and extension definitions only when they are compatible; conflicts fail without committing.

*Persistence (atomic project-file mutation; not an ontology undo step).*

**Arguments**

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `expected_mapping_revision` | SHA-256 revision | yes | Current canonical-store revision. |
| `source` | string | yes | Existing project-confined SSSOM TSV source. |
| `mode` | `replace` or `merge` | yes | Import behavior. |
| `path`, `policy_path` | string | conditional | Path selection as described above. |
| `confirm` | boolean | yes | Must be `true`; normal live confirmation also applies. |

**Returns**

- `committed`, `path`, `previous_mapping_revision`, `mapping_revision`, `record_count`, `bytes`: commit result.
- `backup_path`: optional verified prior-store backup.
- `valid`, `error_count`, `warning_count`, `findings_truncated`: committed-document validation summary.
- `mode`, `source_records`: applied mode and number of source rows read.

**Example**

```json
{ "expected_mapping_revision": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "source": "imports/review.tsv", "mode": "merge", "confirm": true }
```

## `export_sssom`

Atomically export canonical SSSOM bytes to a project-confined destination. Existing files are protected
by default. With `overwrite=true`, provide `expected_target_digest` to prove which target was reviewed.
`spreadsheet_safe=true` prefixes formula-like cells for spreadsheet viewing and therefore reports a
non-lossless export; the canonical store itself is never changed.

*Persistence (atomic destination-file mutation; not an ontology undo step).*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `expected_mapping_revision` | SHA-256 revision | yes | — | Canonical source revision the caller reviewed. |
| `destination` | string | yes | — | Project-confined export target. Its parent must already exist. |
| `overwrite` | boolean | no | `false` | Allow replacement of an existing target. |
| `expected_target_digest` | SHA-256 digest | on overwrite | — | Digest of the reviewed existing target. |
| `spreadsheet_safe` | boolean | no | `false` | Neutralize formula-like cells in the exported copy. |
| `path`, `policy_path` | string | conditional | — | Canonical-store path selection as described above. |
| `confirm` | boolean | yes | — | Must be `true`; normal live confirmation also applies. |

**Returns**

- `committed`, `path`, `mapping_revision`, `sha256`, `bytes`: source revision and exported artifact identity.
- `backup_path`: optional verified backup of an overwritten target.
- `spreadsheet_safe`, `lossless`: export-mode evidence.

**Example**

```json
{ "expected_mapping_revision": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "destination": "exports/mappings.tsv", "confirm": true }
```

## `validate_mappings`

Validate the canonical document against SSSOM structure, project predicate/source/license rules,
captured ontology entities, exact-match conflicts, scoped many-to-one rules, and directional cycles.
Findings are deterministically ordered and paged against one mapping revision.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path`, `policy_path` | string | conditional | — | Path selection as described above. |
| `limit` | integer (1–200) | no | `50` | Maximum findings returned. |
| `cursor` | string | no | — | Opaque revision-bound continuation cursor. |

**Returns**

- `path`, `exists`, `mapping_revision`, `canonical_bytes`, `record_count`: store identity and size.
- `valid`, `error_count`, `warning_count`, `findings_truncated`: complete validation summary.
- `findings`: page of `severity`, `code`, optional `mapping_id`/`column`, and `message` objects.
- `returned`: number of findings in this page.
- `next_cursor`: optional cursor for the next page.

**Example**

```json
{ "limit": 100 }
```
