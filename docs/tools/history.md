---
title: "History & persistence"
parent: "Tools"
nav_order: 10
---

# History & persistence
{: .no_toc }

Move along Protégé's shared undo/redo stack and save the active ontology (or every modified ontology)
to disk. Because every model edit made by the other tools goes through `OWLModelManager.applyChanges`,
`undo_change` / `redo_change` step the **same** history as the GUI's **Edit ▸ Undo / Redo**.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `undo_change`

Undo the last change on Protégé's shared undo stack. Reports the net axiom-count change so the caller
can see what the undo did. Fails if there is nothing to undo. Pass `peek=true` to instead REPORT what
the next undo would revert — the transaction's change count and a sample of its axioms — without
undoing anything.

*Mutating (moves the shared history)* — except with `peek=true`, which is a read-only inspection and
works even in read-only mode.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `peek` | boolean | no | `false` | Inspect the next-undo transaction without undoing it (works in read-only mode). |

**Returns**

- `undone`: boolean — always `true` on success.
- `message`: string — human-readable summary.
- `axioms_before`: integer — total axiom count before the undo.
- `axioms_after`: integer — total axiom count after the undo.
- `net_axiom_change`: integer — `axioms_after − axioms_before`.
- `undo_depth`: integer — how many undoable transactions remain on the stack after the undo.
- `can_undo`: boolean — whether more undo steps remain.
- `can_redo`: boolean — whether a redo is now available.

With `peek=true` (nothing is undone):

- `peek`: boolean — `true`.
- `can_undo` / `can_redo`: booleans — as above.
- `undo_depth`: integer — how many undoable transactions are on the stack.
- `next_undo`: object — the transaction `undo_change` would revert: `{changes, sample, non_axiom_changes?}`,
  where `changes` is the transaction's total change count, `sample` is up to 20 rows of `{op, axiom}`
  (each listed `add` would be removed and each `remove` re-added), and `non_axiom_changes` counts
  import / ontology-annotation / ontology-id changes (counted, not rendered; present only when non-zero).
  Absent when the stack is empty (the `note` says so).
- `note`: string — how to read the report.

If the undo stack is empty, a plain undo returns an error object: `{ "error": "Nothing to undo." }`
(a peek reports it in `note` instead).

**Example**

```json
{}
```

Inspecting without undoing:

```json
{ "peek": true }
```

## `redo_change`

Redo the last undone change on Protégé's shared undo stack (also reports the net axiom-count change).
Fails if there is nothing to redo.

*Mutating (moves the shared history).*

**Arguments**

None.

**Returns**

- `redone`: boolean — always `true` on success.
- `message`: string — human-readable summary.
- `axioms_before`: integer — total axiom count before the redo.
- `axioms_after`: integer — total axiom count after the redo.
- `net_axiom_change`: integer — `axioms_after − axioms_before`.
- `undo_depth`: integer — how many undoable transactions are on the stack after the redo.
- `can_undo`: boolean — whether an undo is available.
- `can_redo`: boolean — whether more redo steps remain.

If there is nothing to redo, returns an error object: `{ "error": "Nothing to redo." }`.

**Example**

```json
{}
```

## `save_ontology`

Save the active ontology to disk. With no arguments it writes to the ontology's existing document; a
never-saved (untitled) ontology has no file yet, so pass `path` to choose one (save-as). The
serialization format is inferred from the file extension (`.ttl` / `.turtle`, `.owl` / `.rdf` / `.xml`,
`.omn`, `.ofn` / `.fss`, `.owx`, `.obo` — anything else is an **error**, not a silent fallback); a path
without an extension keeps the ontology's current format. After a save-as, the ontology is bound to
that file, so later argument-less saves go to the same place. Pass `all=true` to instead save EVERY
ontology with unsaved changes to its own existing document (`list_ontologies` shows which are dirty).

*Persistence (writes a file; not an undoable model change).*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path` | string | no | — | File path to save to (save-as), e.g. `/tmp/pets.ttl`. Omit to write to the existing document. |
| `all` | boolean | no | `false` | Save every dirty ontology to its existing document instead of just the active one. Cannot be combined with `path` (a save-as targets only the active ontology); ontologies without a file are reported as skipped. |
| `verify_round_trip` | boolean | no | `false` | Serialize beside the target, strict-reload, and compare ontology id, imports, ontology annotations, exact axioms, and axiom annotations before replacement. |
| `atomic` | boolean | no | `false` | Require atomic replacement; implies `verify_round_trip`. |
| `backup` | boolean | no | `false` | Preserve an existing target as `<path>.bak`; implies `verify_round_trip`. |

**Returns**

- `saved`: boolean — `true` on success.
- `path`: string — the absolute path written.
- `format`: string — the serialization format used (the format class's simple name); present only on a save-as.

With verified save (`verify_round_trip`, `atomic`, or `backup`):

- `verified`: boolean round-trip verdict.
- `bytes`: artifact size.
- `sha256`: artifact digest.
- `round_trip`: exact comparison details for ontology id, imports, ontology annotations, and annotated axioms.
- `atomic`: whether atomic installation was used.
- `backup_path`: the retained backup, when requested.
- `revision`: the new complete model revision envelope after successful installation.
- `error_code`: `revision_conflict` when a concurrent edit intervened (returned with
  `saved=false`); the prior target remains untouched.
- `base_revision`, `current_revision`: the conflicting revision envelopes on that refusal.

With `all=true`:

- `saved`: array — one `{ontology, path}` row per dirty ontology written to its existing document.
- `skipped`: array — one `{ontology, reason}` row per dirty ontology that could not be saved (no file
  document to save to — e.g. never saved, or loaded straight from the web — or a failed save; one
  failure does not abort the rest).
- `message`: string — e.g. `"2 of 3 modified ontologies saved."`, or that nothing had unsaved changes.

Returns an error object if the ontology has never been saved and no `path` was given, if the target
directory cannot be created, if the `path` extension is unrecognized, or if `all` is combined with
`path`.

**Example**

```json
{ "path": "/tmp/pets.ttl" }
```

Saving every modified ontology:

```json
{ "all": true }
```

Verified atomic save with backup:

```json
{ "path": "/workspace/release.ttl", "verify_round_trip": true, "atomic": true, "backup": true }
```
