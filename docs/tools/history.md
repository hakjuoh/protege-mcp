---
title: "History & persistence"
parent: "Tools"
nav_order: 9
---

# History & persistence
{: .no_toc }

Move along Protégé's shared undo/redo stack and save the active ontology to disk. Because every model
edit made by the other tools goes through `OWLModelManager.applyChanges`, `undo_change` / `redo_change`
step the **same** history as the GUI's **Edit ▸ Undo / Redo**.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `undo_change`

Undo the last change on Protégé's shared undo stack. Reports the net axiom-count change so the caller
can see what the undo did. Fails if there is nothing to undo.

*Mutating (moves the shared history).*

**Arguments**

None.

**Returns**

- `undone`: boolean — always `true` on success.
- `message`: string — human-readable summary.
- `axioms_before`: integer — total axiom count before the undo.
- `axioms_after`: integer — total axiom count after the undo.
- `net_axiom_change`: integer — `axioms_after − axioms_before`.
- `can_undo`: boolean — whether more undo steps remain.
- `can_redo`: boolean — whether a redo is now available.

If the undo stack is empty, returns an error object: `{ "error": "Nothing to undo." }`.

**Example**

```json
{}
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
serialization format is inferred from the file extension (`.ttl`, `.owl` / `.rdf` / `.xml`, `.omn`,
`.ofn`, `.owx`) or falls back to the ontology's current format. After a save-as, the ontology is bound
to that file, so later argument-less saves go to the same place.

*Persistence (writes a file; not an undoable model change).*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `path` | string | no | — | File path to save to (save-as), e.g. `/tmp/pets.ttl`. Omit to write to the existing document. |

**Returns**

- `saved`: boolean — `true` on success.
- `path`: string — the absolute path written.
- `format`: string — the serialization format used (the format class's simple name).

Returns an error object if the ontology has never been saved and no `path` was given, or if the target
directory cannot be created.

**Example**

```json
{ "path": "/tmp/pets.ttl" }
```
