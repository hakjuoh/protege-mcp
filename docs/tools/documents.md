---
title: "Documents"
parent: "Tools"
nav_order: 6
---

# Documents
{: .no_toc }

Tools for loading whole OWL documents into the Protégé workspace, choosing the active edit target, minting new ontologies, writing an OASIS import catalog so a reconstructed module re-opens with its imports resolved offline, and extracting a signature-based locality module from the loaded ontologies.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `load_ontology`

Loads an OWL ontology document from a local path or document IRI/URL into the current Protégé workspace and makes the loaded ontology active. Unlike `merge_ontology_document`, this keeps the document as its own loaded ontology instead of copying its contents into the previous active ontology. GitHub blob URLs are converted to `raw.githubusercontent.com` URLs automatically, the document is fetched off the UI thread so a slow remote fetch does not freeze Protégé, and missing imports are skipped silently — though a sibling `catalog-v001.xml` next to a local-file document first resolves its imports to local files (offline), like Protégé's own File ▸ Open. Reach for it to open an ontology, or (with `keep_active=true`) to bring an imported document into the workspace to resolve an import without changing your current edit target.

*Mutating (not undoable)* — a load is not an `applyChange` edit, so it does NOT join the shared undo stack and `undo_change` cannot revert it. It is still gated by the read-only / confirm-each-write preference switch.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `source` | string | yes | — | Local file path, `file:` IRI, http(s) document IRI, or GitHub blob URL. |
| `keep_active` | boolean | no | `false` | Keep the current active ontology instead of switching to the loaded document. Use this to resolve an unresolved import without losing your edit target. |
| `connection_timeout_ms` | integer | no | `15000` | Remote document connection timeout. |

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
- `unresolved_imports`: array of strings — import IRIs that were silently skipped.
- `note`: string — reminder that loading is not on the undo stack.

On invalid input (unreadable source, parse failure) the call fails with an error object of the form `{error: "..."}`.

**Example**

```json
{
  "source": "https://example.org/ontologies/upper.owl",
  "keep_active": true,
  "connection_timeout_ms": 30000
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

Loads an OWL ontology document from a local path or document IRI/URL and copies its axioms, direct import declarations, and ontology annotations into the active ontology. This preserves axiom annotations and axiom types that are not exposed by the structured `add_axiom` tool, which makes it the right tool for pulling an existing document's full content into the ontology you are editing. GitHub blob URLs are converted to `raw.githubusercontent.com` URLs automatically.

*Mutating (undoable)* — the merge is applied via `applyChanges`, so it appears immediately in the GUI and joins the shared undo stack; it obeys the read-only / confirm-each-write gate. With `replace_active=true` it first removes all of the active ontology's axioms, imports, and ontology annotations.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `source` | string | yes | — | Local file path, `file:` IRI, http(s) document IRI, or GitHub blob URL. |
| `replace_active` | boolean | no | `false` | Remove active ontology axioms/imports/ontology annotations first. |
| `copy_ontology_id` | boolean | no | value of `replace_active` | Copy source ontology IRI/version to the active ontology. |
| `connection_timeout_ms` | integer | no | `15000` | Remote document connection timeout. |

**Returns**

- `merged_document`: string — the normalized source that was merged.
- `source_ontology`: string — label of the source ontology (IRI plus optional version).
- `replace_active`: boolean — echo of whether the active ontology was cleared first.
- `removed`: object (only present when `replace_active` is true) — `{axioms, imports, ontology_annotations}` counts removed.
- `copied`: object — `{axioms, imports, ontology_annotations, ontology_id}`, where `ontology_id` is a boolean noting whether the source id was copied.
- `skipped_ontology_id`: string (optional) — present when the id copy was skipped because of a collision; the collision message.
- `unresolved_imports`: array of strings — import IRIs that were silently skipped during load.
- `active`: object — counts for the active ontology after the merge: `{axioms, logical_axioms, direct_imports, ontology_annotations}`.

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
