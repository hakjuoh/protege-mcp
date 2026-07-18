---
title: "Documents"
parent: "Tools"
nav_order: 6
---

# Documents
{: .no_toc }

Tools for loading whole OWL documents into the Protégé workspace, choosing the active edit target, minting new ontologies, inspecting the resolved import graph, writing an OASIS import catalog so a reconstructed module re-opens with its imports resolved offline, and extracting a signature-based locality module from the loaded ontologies.

As of 0.6.1, every caller-selected/implicit local path on these tools is authorized against the
request-scoped filesystem capabilities and effective project root. Remote sources require
`network:access` plus `network.default: allow`; import dereference uses the stricter `imports.network`
override and host allowlist. Existing local workspace/catalog mappings remain usable with network denied
when their resolved documents pass project filesystem authorization. Direct `file:` imports are confined
and canonicalized; nested `jar:` document/import sources are refused.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `load_ontology`

Loads an OWL ontology document from a local path or document IRI/URL into the current Protégé workspace and makes the loaded ontology active. Unlike `merge_ontology_document`, this keeps the document as its own loaded ontology instead of copying its contents into the previous active ontology. GitHub blob URLs are converted to `raw.githubusercontent.com` URLs automatically, and the document is fetched off the UI thread so a slow remote fetch does not freeze Protégé. A sibling `catalog-v001.xml` next to a local-file document first resolves its imports to local files (offline), like Protégé's own File ▸ Open.

Missing imports use the backward-compatible `warn` mode by default: parsing succeeds and the missing IRIs are returned. Set `missing_imports=error` for a strict project/release load; any missing import aborts before the parsed ontology is attached to the workspace. File/http documents already loaded in the workspace are reused as resolution hints, while a sibling catalog remains the reproducible project source of truth; untitled in-memory ontologies cannot satisfy a strict document load. `silent` is available only as an explicit interactive choice and continues without returning the missing IRIs. Reach for this tool to open an ontology, or (with `keep_active=true`) to bring an imported document into the workspace without changing your current edit target.

*Mutating (not undoable)* — a load is not an `applyChange` edit, so it does NOT join the shared undo stack and `undo_change` cannot revert it. It is still gated by the read-only / confirm-each-write preference switch.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `source` | string | yes | — | Local file path, `file:` IRI, http(s) document IRI, or GitHub blob URL. |
| `keep_active` | boolean | no | `false` | Keep the current active ontology instead of switching to the loaded document. Use this to resolve an unresolved import without losing your edit target. |
| `connection_timeout_ms` | integer | no | `15000` | Remote document connection timeout. |
| `missing_imports` | string | no | `warn` | `warn` reports and continues; `error` fails before changing the workspace; `silent` continues without reporting missing IRIs. |
| `network` | string | no | — | Request-level network control, composed most-restrictive-wins with the project policy: `deny` refuses every remote fetch this load would need (the root document and each import) with an explicit error attributed to `request network=deny`; `allow` abstains and never overrides a policy deny, an invalid policy, a missing `network:access` capability, or a restricted no-policy state. |
| `lock_mode` | string | no | `ignore` | Verify the TO-BE-LOADED closure's coordinates and SHA-256 hashes BEFORE the workspace is mutated. The lockfile resolves against the loaded document (beside-document `imports.lock.json`; the policy-declared lockfile when the loaded document is the project's resolved root artifact). `verify` skips cleanly with a note when no lockfile exists; `required` refuses the load in that state. A mismatch refuses the load and changes nothing. |

**Returns**

- `loaded_document`: string — the normalized source that was loaded.
- `loaded_ontology`: string — label of the loaded primary ontology (IRI plus optional version, or `(anonymous ontology)`).
- `loaded_axioms`: number — axiom count of the loaded primary ontology.
- `active_ontology`: string — label of the ontology that is active after the load.
- `kept_active`: boolean — whether the prior active ontology was retained as the edit target.
- `document_iri`: string (optional) — the primary ontology's document IRI, when known.
- `active`: object — counts for the now-active ontology: `{axioms, logical_axioms, direct_imports, ontology_annotations}`.
- `added_ontologies`: number — ontologies moved into the workspace by this load.
- `already_loaded`: number — ontologies in the parsed closure that were already open.
- `workspace_ontologies`: number — total ontologies now in the workspace.
- `missing_imports_mode`: string — effective `warn`, `error`, or `silent` policy (`error` only returns an error result when an import is missing).
- `resolved_imports`: array of import-edge objects identifying every declaration resolved while loading, including source/target ontology and document IRIs, depth, and local/remote source type.
- `unresolved_imports`: array of strings — import IRIs that could not load; empty in explicit `silent` mode.
- `import_lock_verification`: object (only with `lock_mode=verify|required`) — the pre-load lock comparison: on a match `{valid, path, sha256, entry_count, errors, missing_entries, extra_entries, mismatched_entries, lockfile_source}` (plus `lockfile_note` when `lockfile_source` is `beside_document`, stating that a non-policy-pinned lockfile attests accident-safety, not tamper-evidence); when `verify` found no lockfile, `{verified: false, skipped: true, path?, lockfile_source, note}`.
- `note`: string — reminder that loading is not on the undo stack.

On invalid input (unreadable source, parse failure) the call fails with an error object of the form `{error: "..."}`. A `lock_mode` mismatch — or `lock_mode=required` with no lockfile — refuses the load the same way, before anything is attached to the workspace.

**Example**

```json
{
  "source": "https://example.org/ontologies/upper.owl",
  "keep_active": true,
  "connection_timeout_ms": 30000,
  "missing_imports": "error"
}
```

## `set_active_ontology`

Selects which already-loaded ontology is the ACTIVE edit target (every edit tool writes to the active ontology), resolving by ontology IRI or version IRI (see `list_ontologies`). Unlike `load_ontology` it loads or fetches nothing — it just switches focus among ontologies already in the workspace, for example back to your module after `load_ontology` brought an imported ontology into the workspace.

*Mutating (not undoable)* — switches the active-ontology focus and fires an active-ontology-changed event; not an `applyChange` edit, so it is not on the undo stack.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `ontology_iri` | string | yes | — | Ontology IRI or version IRI of a loaded ontology (see `list_ontologies`). |

**Returns**

- `active_ontology`: string — label of the ontology now active.
- `changed`: boolean — whether the active ontology actually changed (false if it was already active).
- `axioms`: number — axiom count of the target ontology.
- `logical_axioms`: number — logical-axiom count of the target ontology.
- `direct_imports`: number — number of direct import declarations on the target ontology.

If no loaded ontology matches the reference, an error object `{error: "No loaded ontology matches '...'. See list_ontologies ..."}` is returned.

**Example**

```json
{
  "ontology_iri": "https://example.org/mymodule"
}
```

## `merge_ontology_document`

Loads an OWL ontology document from a local path or document IRI/URL and copies its axioms, direct import declarations, and ontology annotations into the active ontology. This preserves axiom annotations and axiom types that are not exposed by the structured `add_axiom` tool, which makes it the right tool for pulling an existing document's full content into the ontology you are editing. GitHub blob URLs are converted to `raw.githubusercontent.com` URLs automatically. Pass `preview=true` to fetch and parse the document but only REPORT what the merge would do, without changing anything.

*Mutating (undoable)* — the merge is applied via `applyChanges`, so it appears immediately in the GUI and joins the shared undo stack; it obeys the read-only / confirm-each-write gate. With `replace_active=true` it first removes all of the active ontology's axioms, imports, and ontology annotations. With `preview=true` nothing is applied (a read-only dry-run that works even in read-only mode; the document is still fetched and parsed).

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `source` | string | yes | — | Local file path, `file:` IRI, http(s) document IRI, or GitHub blob URL. |
| `replace_active` | boolean | no | `false` | Remove active ontology axioms/imports/ontology annotations first. |
| `copy_ontology_id` | boolean | no | value of `replace_active` | Copy source ontology IRI/version to the active ontology. |
| `preview` | boolean | no | `false` | Dry-run: report what the merge would copy/remove without applying anything (works in read-only mode). |
| `connection_timeout_ms` | integer | no | `15000` | Remote document connection timeout. |
| `missing_imports` | string | no | `warn` | `warn` reports and continues; `error` fails before changing the active ontology; `silent` continues without reporting missing IRIs. |
| `network` | string | no | — | Request-level network control, composed most-restrictive-wins with the project policy: `deny` refuses every remote fetch this merge would need (the source document and each import) with an explicit error attributed to `request network=deny`; `allow` abstains and never overrides a policy deny, an invalid policy, a missing `network:access` capability, or a restricted no-policy state. |
| `lock_mode` | string | no | `ignore` | Verify the parsed document's closure coordinates and SHA-256 hashes BEFORE anything is merged. The lockfile resolves against the loaded document (beside-document `imports.lock.json`; the policy-declared lockfile when the document is the project's resolved root artifact). `verify` skips cleanly with a note when no lockfile exists; `required` refuses the merge in that state. A mismatch refuses the merge and changes nothing. |

**Returns**

- `merged_document`: string — the normalized source that was merged.
- `source_ontology`: string — label of the source ontology (IRI plus optional version).
- `replace_active`: boolean — echo of whether the active ontology was cleared first.
- `removed`: object (only present when `replace_active` is true) — `{axioms, imports, ontology_annotations}` counts removed.
- `copied`: object — `{axioms, imports, ontology_annotations, ontology_id}`, where `ontology_id` is a boolean noting whether the source id was copied.
- `skipped_ontology_id`: string (optional) — present when the id copy was skipped because of a collision; the collision message.
- `missing_imports_mode`: string — effective `warn`, `error`, or `silent` policy.
- `resolved_imports`: array of import-edge objects identifying the imports actually resolved while parsing the source document.
- `unresolved_imports`: array of strings — import IRIs that could not load; empty in explicit `silent` mode.
- `import_lock_verification`: object (only with `lock_mode=verify|required`) — the pre-merge lock comparison with its `lockfile_source` label (`policy_declared` or `beside_document`, the latter with a `lockfile_note` stating the verification attests accident-safety, not tamper-evidence); when `verify` found no lockfile, a `{verified: false, skipped: true, ...}` note. A mismatch, or `required` with no lockfile, refuses the merge with an error before anything is applied.
- `active`: object — counts for the active ontology after the merge: `{axioms, logical_axioms, direct_imports, ontology_annotations}`.

With `preview=true` (nothing is applied):

- `preview`: `true`, plus `merged_document` / `source_ontology` / `replace_active` / `skipped_ontology_id` / `unresolved_imports` as above.
- `would_copy`: object — the `{axioms, imports, ontology_annotations, ontology_id}` counts the merge would copy (in place of `copied`).
- `would_remove`: object (only when `replace_active` is true) — the `{axioms, imports, ontology_annotations}` counts that would be removed first (in place of `removed`).
- `already_present_axioms`: integer (plain merge only) — how many of the document's axioms the active ontology already asserts (those adds would be no-ops).
- `total_changes`: integer — total ontology changes the merge would apply.
- `note`: reminder that nothing was changed and the merge would apply as one undoable change.

On an unreadable source or parse failure the call fails with an error object `{error: "..."}`.

**Example**

```json
{
  "source": "/tmp/ontology/upper.owl",
  "replace_active": false,
  "copy_ontology_id": false
}
```

## `create_ontology`

Creates a brand-new empty ontology in the workspace and makes it the active edit target (so `add_axiom` / `create_*` write into it). Give its `ontology_iri` and optionally a `version_iri`. Pass `path` to bind it to a file now (so an argument-less `save_ontology` writes there, and `write_catalog` has a folder); otherwise it stays untitled until you `save_ontology` with a path. Use this to start a new module before adding imports/axioms — `set_ontology_id` changes an existing ontology's id, whereas this mints a new one.

*Mutating (not undoable)* — creating an ontology is not on the undo stack (`undo_change` cannot revert it). It is gated by the read-only / confirm-each-write switch.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `ontology_iri` | string | yes | — | IRI of the new ontology. |
| `version_iri` | string | no | — | Optional version IRI. |
| `path` | string | no | — | Optional file path to bind the new ontology to (e.g. `/tmp/mymodule/MyModule.rdf`). |
| `keep_active` | boolean | no | `false` | Keep the current active ontology instead of switching to the new one. |

**Returns**

- `created_ontology`: string — label of the newly minted ontology (IRI plus optional version).
- `active_ontology`: string — label of the ontology that is active after the call.
- `kept_active`: boolean — whether the prior active ontology was retained as the edit target.
- `document_iri`: string (optional) — the bound document IRI, present only when a `path` was given.
- `workspace_ontologies`: number — total ontologies now in the workspace.
- `note`: string — reminder that the new ontology is not on the undo stack.

If the new id collides with a loaded ontology, or the target directory cannot be created, an error object `{error: "..."}` is returned.

**Example**

```json
{
  "ontology_iri": "https://example.org/mymodule",
  "version_iri": "https://example.org/mymodule/1.0.0",
  "path": "/tmp/mymodule/MyModule.rdf"
}
```

## `inspect_imports`

Inspects the active ontology's complete, currently loaded import graph without fetching documents or changing the workspace. It distinguishes direct from transitive declarations, records what each declaration resolves to, classifies target documents as local/remote/memory/other, and reports missing imports, strongly connected import-cycle components, multiple loaded versions of one logical ontology IRI, reused version IRIs, and reused document IRIs. Rows are deterministically ordered so repeated inspection of unchanged state produces stable JSON.

`import_integrity_ok` is an import-integrity signal, not a complete release verdict: it is `false` when an import is missing or the loaded graph has an identity/version/document conflict. `missing_imports_clear` and `conflicts_clear` expose those two conditions separately. Cycles remain visible but do not by themselves make the aggregate false. Use `run_project_qc` for the complete policy gate.

*Read-only.* No arguments. It only inspects the active ontology and already-loaded imports; it never performs network or filesystem I/O.

**Returns**

- `root`: object — the active ontology descriptor `{ref, ontology_iri, version_iri, document_iri, source_type, anonymous, depth, role}`.
- `summary`: object — counts for ontologies, declarations, direct/transitive/resolved/missing imports, local/remote documents, cycles, and conflicts.
- `ontologies`: array — deterministic root/direct/transitive ontology descriptors.
- `imports`: array — every import declaration edge, including source coordinates, `import_iri`, `direct`, `depth`, `resolved`, and resolved target coordinates/source type when available.
- `resolved_imports`: array — the resolved subset of `imports`.
- `missing_imports`: array — the unresolved subset of `imports`.
- `cycles`: array — strongly connected cyclic components as `{ontologies, size, self_loop}`.
- `conflicts`: array — identity conflicts as `{type, field, value, ontologies}`; types are `multiple_versions`, `version_iri_collision`, and `document_iri_collision`.
- `missing_imports_clear`: boolean — true when every declaration resolves.
- `conflicts_clear`: boolean — true when no loaded identity/version/document conflict is present.
- `import_integrity_ok`: boolean — true only when both preceding conditions are true.

**Example**

```json
{}
```

## `write_import_lock`

Writes a deterministic `imports.lock.json` for every currently resolved local import in the active
ontology's loaded closure. Entries are sorted and record logical/version IRI, a lock-relative document
path, SHA-256, and direct/transitive status. Missing, remote, or conflicted imports fail closed. The
lock file is replaced atomically.

Without `path` the policy decides the target — and it must decide cleanly: a policy that fails to load
or validate (including a bad explicit `policy_path`), or one whose declared `imports.lockfile` does not
resolve to a file (e.g. it does not exist yet), is refused with an error instead of silently writing
beside the active document. To bootstrap a declared lockfile, pass `path` pointing at the declared
location (or create an empty placeholder file there). Only a policy that declares no lockfile — or no
policy at all — defaults to `imports.lock.json` beside the active document.

*Filesystem mutation, not undoable.* Subject to read-only and confirm-each-write gates.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path` | string | no | policy `imports.lockfile`; beside active document only when the policy declares none | Target lock path. Without it, an unloadable/invalid policy or an unresolved declared lockfile refuses (fail closed). |
| `policy_path` | string | no | discovered | Explicit project policy. A path that fails to load refuses lock-path defaulting rather than falling back. |

**Returns**

- `written`: whether the lock was installed.
- `valid`: integrity outcome (present on a refused capture).
- `path`: the target lock path.
- `entry_count`: locked import count.
- `sha256`: digest of the written lock.
- `lock`: the lock content (`{version: 1, imports: [...]}`).
- `errors`: capture failures; the target is not replaced.
- `error_code`: on a refused installation — `revision_conflict` (a concurrent workspace edit
  between capture and install) or `policy_conflict` (the effective policy or lock target changed).
  Nothing is written.
- `base_revision`, `current_revision`: the conflicting envelopes on a `revision_conflict`.
- `message`: the explanation on a `policy_conflict`.

**Example**

```json
{ "path": "/workspace/imports.lock.json" }
```

## `verify_import_lock`

Verifies a version-1 import lock without fetching anything. It strictly parses duplicate/unknown fields,
confines documents below the lock directory, recomputes every loaded local artifact checksum, and compares
the complete deterministic import coordinate set.

The default lock path follows the same fail-closed rule as `write_import_lock`: without `path`, a policy
that fails to load or validate, or whose declared `imports.lockfile` does not resolve to a file, is
refused with an error — verification never silently targets a different file than the one the policy
declares. Only a policy that declares no lockfile (or no policy at all) verifies `imports.lock.json`
beside the active document.

*Read-only and no-network.*

When the effective policy uses `imports.mode: locked`, this same content comparison runs automatically
inside `run_project_qc` and `preview_change_set`/verified `apply_changes`; an explicit call remains useful
for diagnostics and CI-style standalone inspection.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path` | string | no | policy `imports.lockfile`; beside active document only when the policy declares none | Lock to verify. Without it, an unloadable/invalid policy or an unresolved declared lockfile refuses (fail closed). |
| `policy_path` | string | no | discovered | Explicit project policy. A path that fails to load refuses lock-path defaulting rather than falling back. |

**Returns**

- `valid`, `path`, `entry_count`: aggregate result and lock identity.
- `sha256`: digest of the lock file when readable.
- `errors`: parse, resolution, or hashing errors.
- `missing_entries`, `extra_entries`, `mismatched_entries`: deterministic coordinate-key lists.

**Example**

```json
{ "policy_path": "/workspace/.protege-mcp/project.yaml" }
```

## `validate_catalog`

Parses an OASIS `catalog-v001.xml` with DTD/schema/external-entity access disabled. It reports malformed,
duplicate, remote, and missing local URI mappings and can require every direct active import declaration
to have a catalog entry.

`<uri>` targets are resolved honoring `xml:base` per the OASIS spec — the nearest base on the entry, its
enclosing `<group>`, or the catalog root, each relative base resolved against the next outer one and
ultimately against the catalog file's URI — matching how Protégé itself resolves the catalog when loading.
`<nextCatalog>` delegations are reported in `next_catalogs` but never followed (no chained parsing). With
`compare_imports`, an import that has no local mapping while delegations exist is reported in
`delegated_imports` (unverified) instead of failing as unmapped; without any delegation it remains a hard
`unmapped_imports` failure.

*Read-only and no-network.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path` | string | no | beside active document | Catalog file. |
| `compare_imports` | boolean | no | false | Require active import declarations to be mapped. |

**Returns**

- `valid`: aggregate result.
- `catalog`: absolute catalog path.
- `entries`: array `{name, uri, status}` where status is `local_ok`, `local_missing`, `remote_forbidden`, or `invalid_uri`.
- `next_catalogs`: resolved `<nextCatalog>` targets — reported, never followed.
- `errors`: parse/mapping failures.
- `unmapped_imports`: active declarations absent from the catalog (no delegation present).
- `delegated_imports`: with `compare_imports`, declarations unmapped locally while `next_catalogs` exist — possibly mapped down the chain, not verified here.

**Example**

```json
{ "path": "/workspace/catalog-v001.xml", "compare_imports": true }
```

## `write_catalog`

Writes an OASIS `catalog-v001.xml` next to the active ontology that maps each imported ontology IRI (and version IRI) to the local file it loaded from, so the module re-opens in Protégé with imports resolved offline. This is the piece that lets a tool-reconstructed multi-module ontology be re-opened with its imports resolving offline — catalog files live outside the OWL axiom model, so no other tool can produce them. By default the catalog is written in the folder of the active ontology's saved document and covers its whole imports closure; pass `path` to choose a target folder or file, and `direct_imports_only=true` to map just the directly-imported ontologies. Imports that are unresolved or not backed by a local file are reported as skipped.

*Mutating (not undoable)* — this is a filesystem write, not an `applyChange`; it is gated by the same read-only / confirm-each-write switch as the other write tools but is NOT on the undo stack.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path` | string | no | active ontology's document folder (file `catalog-v001.xml`) | Target folder, or a catalog file path. |
| `direct_imports_only` | boolean | no | `false` | Map only directly-imported ontologies instead of the full imports closure. |

**Returns**

- `catalog`: string — absolute path of the catalog file that was written.
- `entries`: array of per-mapping rows, each `{name, uri}` (the mapped IRI and the relative local-file URI).
- `entry_count`: number — number of entries written.
- `skipped`: array of skip rows, each `{import, reason}` (the import IRI and why it was skipped, e.g. unresolved or no local file document).

If no catalog folder can be determined (the active ontology has no saved file document and no `path` was given), or the folder cannot be created, or the save fails, an error object `{error: "..."}` is returned.

**Example**

```json
{
  "path": "/tmp/mymodule/",
  "direct_imports_only": false
}
```

## `extract_module`

Extracts a signature-based **locality module** from the loaded ontologies — the interactive analogue of `robot extract`, using the OWL API's `SyntacticLocalityModuleExtractor`. Give a seed `signature` (entity names or full IRIs; a punned IRI brings every sense) and a `module_type`: **STAR** (default — the smallest, "nested" module, both directions), **BOT** (⊥ — what the seeds *use*: their superclasses and definitions), or **TOP** (⊤ — what *uses* the seeds: their subtree). It runs over `source` = `imports_closure` (default) or `active`. By default the extracted module is loaded as a NEW ontology in the workspace (name it with `iri`); pass `path` to instead save it to a file (the serialisation format is chosen from the extension). The STAR fixpoint runs off the UI thread — only seed resolution and the closure snapshot happen on the model thread, bounded by `timeout_ms`.

*Mutating (not undoable)* — extraction is not an `applyChange` edit, so it does NOT join the shared undo stack. Both delivery modes are gated as writes: the read-only and confirm-each-write preferences apply to loading the module into the workspace AND to exporting it to a file.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `signature` | array of string | yes | — | Seed entity names or full IRIs (a punned IRI brings every sense). |
| `module_type` | string | no | `STAR` | `STAR` (smallest, both directions) \| `BOT` (⊥ — what the seeds use) \| `TOP` (⊤ — what uses the seeds). |
| `source` | string | no | `imports_closure` | `imports_closure` \| `active` — which graph to extract from. |
| `iri` | string | no | — | IRI to name the extracted module ontology. |
| `path` | string | no | — | File to save the module to (format from the extension). Omit to load it as a new workspace ontology instead. |
| `timeout_ms` | integer | no | `60000` | Time budget in ms bounding the on-model-thread phases (seed resolution + closure snapshot). |

**Returns**

- `module`: object — `{iri, anonymous}` describing the extracted module ontology.
- `module_type`: string — the module type used (`STAR` / `BOT` / `TOP`).
- `source`: string — the graph extracted from (`imports_closure` / `active`).
- `seeds`: object — `{requested, resolved, unresolved}`: the counts of signature entries requested, resolved to entities, and left unresolved.
- `axioms`: integer — number of axioms in the extracted module.
- `entities`: integer — number of entities in the module's signature.
- When loaded into the workspace: `loaded`: `"workspace"` and `note` — a reminder that the load is not on the undo stack.
- When saved to a file: `saved`: `true`, `path`: string — the absolute file written, and `format`: string — the serialisation format used.

On an unreadable signature (no seed resolves), a save failure, or a timeout, the call fails with an error object `{error: "..."}`.

**Example**

```json
{
  "signature": ["Widget", "https://example.org/ont/Product"],
  "module_type": "STAR",
  "source": "imports_closure",
  "iri": "https://example.org/modules/product-module"
}
```
