---
title: "Editing — entities & axioms"
parent: "Tools"
nav_order: 3
---

# Editing — entities & axioms
{: .no_toc }

Tools that mint entities and edit axioms in the **active** ontology. Every change is applied through `OWLModelManager.applyChanges`, so edits appear immediately in the Protégé GUI and join the shared undo stack; they are gated by the read-only and confirm-each-write preferences. Preview first with `preview_changes`, then apply single or batched edits.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `preview_changes`

Dry-runs a batch of axiom add/remove operations **without applying anything** — the same `operations` array that `apply_changes` accepts. For each operation it reports the rendered axiom, whether it is already present (so an add is a no-op or a remove would actually take effect), and the new entities an add would introduce. Reach for it to turn a natural-language edit request into N concrete changes and show the diff before committing. Each item takes the same operands as `add_axiom`/`remove_axiom` (`axiom_type` + operands) — see the "Axiom types" page for the full `axiom_type` catalog and its operand sets.

*Read-only* — works even in read-only mode; computes new-entity introduction against the imports closure.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `operations` | array | yes | — | Axiom changes to preview; each item is an `add_axiom`/`remove_axiom` operand set (`axiom_type` + operands) plus optional `op` = `add` (default) or `remove`. |

**Returns**

- `operations`: array of per-op rows, each `{index, op, axiom}` plus, for adds, `already_present` (bool), `new_entities` (array of entity objects), and `effect` (`"would add"` / `"no-op (already present)"`); for removes, `present` (bool) and `effect` (`"would remove"` / `"no-op (not present)"`). A row that fails to build carries `error` instead.
- `summary`: `{operations, adds, removes, no_ops, errors, new_entities}` — the last being the aggregated list of entities all adds would introduce.
- `note`: reminder that nothing was applied.

**Example**

```json
{
  "operations": [
    { "axiom_type": "subclass_of", "sub": "Dog", "super": "Animal" },
    { "op": "remove", "axiom_type": "subclass_of", "sub": "Cat", "super": "Plant" }
  ]
}
```

---

## `apply_changes`

Applies a batch of axiom add/remove operations in ONE call — the same `operations` array as `preview_changes`, each item being an `axiom_type` + operands with an optional `op` = `add` (default) or `remove`. The whole batch commits as a **single undoable transaction**, so one `undo_change` reverts all of it at once. It reports, per operation, what was applied/removed and any new entities introduced, plus a summary. Run `preview_changes` first to dry-run. Because nothing is applied until the batch completes, an operation that references an entity introduced by an *earlier* operation in the same batch must refer to it by full IRI.

*Mutating (undoable)* — honours `strict` (skips any add that would mint a brand-new entity from an unrecognized IRI/name) and reports `new_entities`. Edits the active ontology; new-entity detection is against the imports closure.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `operations` | array | yes | — | Axiom changes to apply; each item is an `add_axiom`/`remove_axiom` operand set (`axiom_type` + operands) plus optional `op` = `add`/`remove`. |
| `strict` | boolean | no | `false` | If true, fail instead of minting a brand-new entity from an unrecognized absolute IRI / display name. |

**Returns**

- `operations`: array of per-op rows, each `{index, op, axiom}` plus, for adds, `applied` (bool), optional `note` (e.g. `"already present"`) and `new_entities` (array); for removes, `removed` (bool) and optional `note` (`"not present"`). A failed op carries `error` (including a strict-mint refusal).
- `summary`: `{operations, added, removed, no_ops, errors, single_undo, new_entities}` — `single_undo` is true when at least one change was applied; `new_entities` is the aggregated introduced-entity list.

**Example**

```json
{
  "operations": [
    { "axiom_type": "subclass_of", "sub": "Terrier", "super": "Dog" },
    { "axiom_type": "class_assertion", "class": "Dog", "individual": "Rex" }
  ],
  "strict": true
}
```

---

## `create_class`

Creates a named class. Give a full `iri`, or a `namespace` to mint the IRI in (IRI = `namespace` + `name` — useful when terms live in a shared namespace distinct from the ontology IRI), else the IRI is minted from `name` using Protégé's entity-creation settings. An `rdfs:label` (`label` or `name`, tagged with `label_lang`) is added unless `no_label`. Optionally attaches a `parent` superclass.

*Mutating (undoable)* — the declaration, optional label, and optional superclass axiom apply as one undoable transaction. Edits the active ontology.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `name` | string | yes | — | Short name — the IRI local part when minting, and the default `rdfs:label`. |
| `iri` | string | no | — | Full IRI to use (overrides `namespace`). |
| `namespace` | string | no | — | Namespace to mint the IRI in: IRI becomes `namespace` + `name`. |
| `label` | string | no | `name` | `rdfs:label` text. |
| `label_lang` | string | no | — | Language tag for the `rdfs:label`, e.g. `en-US`. |
| `no_label` | boolean | no | `false` | Do not add any `rdfs:label`. |
| `parent` | string | no | — | Superclass: IRI, name or Manchester class expression. |

**Returns**

- `created`: the created entity as a JSON object (IRI, rendering, type, …).
- `parent`: the parent operand, present only if one was given.
- `present`: whether the class ended up in the active ontology's signature.

**Example**

```json
{ "name": "Terrier", "parent": "Dog", "label": "Terrier", "label_lang": "en" }
```

---

## `create_entity`

Creates a named entity of a given type: `class`, `object_property`, `data_property`, `annotation_property`, `individual` or `datatype`. IRI handling matches `create_class` — a full `iri`, a `namespace` to mint in (IRI = `namespace` + `name`), or minting from `name`. An `rdfs:label` (`label` or `name`, tagged with `label_lang`) is added unless `no_label`. Use it when you need a non-class entity or want to pick the type explicitly.

*Mutating (undoable)* — declaration plus optional label apply as one undoable transaction. Edits the active ontology.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity_type` | string | yes | — | `class` \| `object_property` \| `data_property` \| `annotation_property` \| `individual` \| `datatype`. |
| `name` | string | yes | — | Short name — the IRI local part when minting, and the default `rdfs:label`. |
| `iri` | string | no | — | Full IRI to use (overrides `namespace`). |
| `namespace` | string | no | — | Namespace to mint the IRI in: IRI becomes `namespace` + `name`. |
| `label` | string | no | `name` | `rdfs:label` text. |
| `label_lang` | string | no | — | Language tag for the `rdfs:label`, e.g. `en-US`. |
| `no_label` | boolean | no | `false` | Do not add any `rdfs:label`. |

**Returns**

- `created`: the created entity as a JSON object.

**Example**

```json
{ "entity_type": "object_property", "name": "hasOwner", "namespace": "http://ex.org/pets#" }
```

---

## `create_term`

Creates a class WITH its curation suite in one undoable step (versus `create_class` + several `add_axiom` calls). It mints the class (same `iri`/`namespace`/`label`/`label_lang`/`no_label` options as `create_class`), adds a definition (`definition`, default property `rdfs:comment` — override via `definition_property`, e.g. `skos:definition`), any extra `annotations`, one or more `parents` (each a class name, IRI or Manchester restriction such as `hasPart some Cell`), and optional `equivalent_to` class expressions for a defined class. Reach for it when authoring a fully-annotated term in a single move.

*Mutating (undoable)* — the whole term applies as one undoable transaction; honours `strict` (refuses to mint an unrecognised operand) and reports `new_entities`. New-entity detection is against the imports closure.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `name` | string | yes | — | Short name — the IRI local part when minting, and the default label. |
| `iri` | string | no | — | Full IRI to use (overrides `namespace`). |
| `namespace` | string | no | — | Namespace to mint the IRI in: IRI = `namespace` + `name`. |
| `label` | string | no | `name` | `rdfs:label` text. |
| `label_lang` | string | no | — | Language tag for the `rdfs:label`, e.g. `en`. |
| `no_label` | boolean | no | `false` | Do not add any `rdfs:label`. |
| `definition` | string | no | — | Definition text. |
| `definition_property` | string | no | `rdfs:comment` | Annotation property for the definition (e.g. `skos:definition` or an IOF *Definition property). |
| `definition_lang` | string | no | — | Language tag for the definition literal. |
| `parents` | array of string | no | — | Superclasses: each a class name, IRI or Manchester class expression. |
| `equivalent_to` | array of string | no | — | Equivalent class expressions for a defined class (each a name, IRI or Manchester class expression). |
| `annotations` | array | no | — | Extra annotations (array of `{property, value | value_iri, lang, datatype}`). |
| `strict` | boolean | no | `false` | If true, fail instead of minting an unrecognised operand. |

**Returns**

- `created`: the created class as a JSON object.
- `new_entities`: array of entities the operands introduced (present only when non-empty).
- `applied`: number of changes committed.

**Example**

```json
{
  "name": "WorkingDog",
  "label": "working dog",
  "definition": "A dog trained to perform tasks.",
  "definition_property": "skos:definition",
  "parents": ["Dog", "hasRole some WorkRole"]
}
```

---

## `create_property`

Creates an object or data property WITH its axioms in one undoable step. It mints the property (`property_type` `object`|`data`, default `object`; same `iri`/`namespace`/`label`/`label_lang`/`no_label` options), and optionally adds a `definition`, extra `annotations`, a `domain` (class expression), a `range` (a class expression for object; a datatype / Manchester data range such as `xsd:integer[>= 0]` for data), `super_properties`, `characteristics` (object: `functional`, `inverse_functional`, `transitive`, `symmetric`, `asymmetric`, `reflexive`, `irreflexive`; data: `functional`) and an `inverse_of` (object only). Reach for it to declare a fully-specified property in one call.

*Mutating (undoable)* — applies as one undoable transaction; honours `strict` (refuses to mint an unrecognised operand) and reports `new_entities`. New-entity detection is against the imports closure. Note: `inverse_of` on a data property is an error.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `name` | string | yes | — | Short name — the IRI local part when minting, and the default label. |
| `property_type` | string | no | `object` | `object` \| `data`. |
| `iri` | string | no | — | Full IRI to use (overrides `namespace`). |
| `namespace` | string | no | — | Namespace to mint the IRI in: IRI = `namespace` + `name`. |
| `label` | string | no | `name` | `rdfs:label` text. |
| `label_lang` | string | no | — | Language tag for the `rdfs:label`. |
| `no_label` | boolean | no | `false` | Do not add any `rdfs:label`. |
| `definition` | string | no | — | Definition text. |
| `definition_property` | string | no | `rdfs:comment` | Annotation property for the definition. |
| `definition_lang` | string | no | — | Language tag for the definition literal. |
| `domain` | string | no | — | Domain class expression (name, IRI or Manchester). |
| `range` | string | no | — | Range: a class expression (object) or a datatype / Manchester data range (data). |
| `super_properties` | array of string | no | — | Super-properties this is a subproperty of. |
| `characteristics` | array of string | no | — | Property characteristics to assert. |
| `inverse_of` | string | no | — | Inverse object property (object properties only). |
| `annotations` | array | no | — | Extra annotations (array of `{property, value | value_iri, lang, datatype}`). |
| `strict` | boolean | no | `false` | If true, fail instead of minting an unrecognised operand. |

**Returns**

- `created`: the created property as a JSON object.
- `new_entities`: array of entities the operands introduced (present only when non-empty).
- `applied`: number of changes committed.

**Example**

```json
{
  "name": "hasParent",
  "property_type": "object",
  "domain": "Person",
  "range": "Person",
  "characteristics": ["transitive", "irreflexive"],
  "inverse_of": "hasChild"
}
```

---

## `add_subclass_of`

Asserts that `child` is a subclass of `parent`. Each may be a class name, a full IRI, or a Manchester-syntax class expression (e.g. `hasOwner some Person`). A focused shortcut for the most common axiom; for anything else reach for `add_axiom`.

*Mutating (undoable)* — honours `strict` and reports `new_entities` (entities introduced as a side effect). New-entity detection is against the imports closure; edits the active ontology.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `child` | string | yes | — | Subclass: IRI, name or class expression. |
| `parent` | string | yes | — | Superclass: IRI, name or class expression. |
| `strict` | boolean | no | `false` | If true, fail instead of minting a brand-new entity from an unrecognized IRI/name. |

**Returns**

- `applied`: whether the axiom is now present.
- `axiom`: the resulting axiom as a JSON object.
- `new_entities`: array of introduced entities (present only when non-empty).
- `note`: present only when the add had no effect (already present or minimized away).

**Example**

```json
{ "child": "Puppy", "parent": "Dog" }
```

---

## `add_annotation`

Adds an annotation assertion to an entity (default property `rdfs:label`). The value is a literal (optionally typed with `datatype` or tagged with `lang`) or, with `value_iri`, an IRI. Pass `annotations` to attach axiom annotations (a reified `owl:Axiom`). For a non-entity subject or full OWL 2 symmetry, use `add_axiom` with `axiom_type=annotation_assertion`.

*Mutating (undoable)* — honours `strict` and reports `new_entities`. New-entity detection is against the imports closure; edits the active ontology.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Target subject: entity IRI/name or any absolute IRI. |
| `property` | string | no | `rdfs:label` | Annotation property: `rdfs:label`, `rdfs:comment`, or an IRI/name. |
| `value` | string | no | — | Literal text value (omit if `value_iri` is given). |
| `value_iri` | string | no | — | IRI-valued annotation: an entity name/IRI or absolute IRI (alternative to `value`). |
| `lang` | string | no | — | Language tag for a literal value, e.g. `en`. |
| `datatype` | string | no | — | Datatype IRI/name for a typed literal value. |
| `annotations` | array | no | — | Axiom annotations on this assertion (array of `{property, value | value_iri, lang, datatype}`). |
| `strict` | boolean | no | `false` | If true, fail instead of minting a brand-new entity from an unrecognized IRI/name. |

**Returns**

- `applied`: whether the annotation axiom is now present.
- `axiom`: the resulting axiom as a JSON object.
- `new_entities`: array of introduced entities (present only when non-empty).
- `note`: present only when the add had no effect.

**Example**

```json
{ "entity": "Dog", "property": "rdfs:comment", "value": "A domestic canine.", "lang": "en" }
```

---

## `set_label`

Sets (upserts) an entity's `rdfs:label`: removes any existing `rdfs:label` on the entity in the SAME language and adds the new one. Use it to fix a label without hand-removing the old axiom — `rename_entity` changes the IRI, not the label. Only labels matching the given `lang` (or all untagged labels when `lang` is omitted) are replaced.

*Mutating (undoable)* — the removals plus the new label apply as one undoable transaction. Edits the active ontology.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Target entity: IRI or display name. |
| `value` | string | yes | — | New `rdfs:label` text. |
| `lang` | string | no | — | Language tag, e.g. `en-US`. Only labels in the same language are replaced. |

**Returns**

- `entity`: the subject IRI (string).
- `label`: the new label text.
- `lang`: the language tag, present only if one was given.
- `removed_previous`: number of prior same-language labels removed.

**Example**

```json
{ "entity": "Dog", "value": "domestic dog", "lang": "en" }
```

---

## `add_axiom`

Adds a structured axiom selected by `axiom_type`, supplying the operands the chosen type needs (e.g. sub/super, `classes[]`, class/individual, property/subject/object, property/subject/value with optional `lang`/`datatype`, property/domain, property/range). Any class operand may be a named class, a full IRI, or a Manchester-syntax class expression such as `Animal and (hasOwner some Person)` — so defined classes and restrictions are expressible via `equivalent_classes` / `subclass_of`. The set of valid `axiom_type` values and their exact operand sets are documented on the dedicated "Axiom types" page rather than reproduced here.

*Mutating (undoable)* — honours `strict` and reports `new_entities` (entities introduced as a side effect). New-entity detection is against the imports closure; edits the active ontology.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `axiom_type` | string | yes | — | The axiom kind to build; see the "Axiom types" page for the full catalog. |
| *(operands)* | varies | varies | — | Type-specific operands (e.g. `sub`/`super`, `classes`, `class`/`individual`, `property`/`subject`/`object`/`value`, `domain`, `range`). See "Axiom types". |
| `strict` | boolean | no | `false` | If true, fail instead of minting a brand-new entity from an unrecognized IRI/name. |

**Returns**

- `applied`: whether the axiom is now present.
- `axiom`: the resulting axiom as a JSON object.
- `new_entities`: array of introduced entities (present only when non-empty).
- `note`: present only when the add had no effect (already present or minimized away).

**Example**

```json
{ "axiom_type": "equivalent_classes", "classes": ["Parent", "Person and (hasChild some Person)"] }
```

---

## `remove_axiom`

Removes a structured axiom — the same `axiom_type` + operands as `add_axiom`. It builds the axiom and, if the active ontology asserts it, removes it; otherwise it returns an error saying the axiom is not present. See the "Axiom types" page for the operand catalog.

*Mutating (undoable)* — edits the active ontology. Returns an error object (`{ "error": ... }`) when the axiom is not present in the active ontology.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `axiom_type` | string | yes | — | The axiom kind to build (same as `add_axiom`); see the "Axiom types" page. |
| *(operands)* | varies | varies | — | Type-specific operands matching the chosen `axiom_type`. See "Axiom types". |

**Returns**

- `removed`: whether the axiom is now gone.
- `axiom`: the targeted axiom as a JSON object.
- On failure, an error object whose message names the axiom not present in the active ontology.

**Example**

```json
{ "axiom_type": "subclass_of", "sub": "Puppy", "super": "Dog" }
```

---

## `rename_entity`

Changes an entity's IRI throughout the active ontology — every axiom and annotation that references the old IRI is rewritten to the new one. If the IRI is punned across several entity types, all of them are renamed. The `new_iri` must be a full absolute IRI. Use it to repoint references, not to change a display label (see `set_label` for that).

*Mutating (undoable)* — operates on the active ontology only (imported ontologies are not edited). Returns an error object if the entity is not found, `new_iri` is not absolute, the IRI is unchanged, or the old IRI is not referenced.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Entity to rename: an IRI or display name. |
| `new_iri` | string | yes | — | New full IRI for the entity. |

**Returns**

- `renamed`: `true`.
- `old_iri`: the previous IRI (string).
- `new_iri`: the new IRI (string).
- `changes`: number of ontology changes applied.

**Example**

```json
{ "entity": "Dog", "new_iri": "http://ex.org/pets#Canine" }
```

---

## `delete_entity`

Deletes an entity from the active ontology: removes its declaration and every axiom that references it. If the IRI is punned across several entity types, all are removed unless `entity_type` narrows it to one. Reach for it to fully retract a term; contrast with `deprecate_entity`, which keeps the term.

*Mutating (undoable)* — operates on the active ontology only. Returns an error object if no matching entity is found, or if the target is not referenced in the active ontology.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Entity to delete: an IRI or display name. |
| `entity_type` | string | no | — | `class` \| `object_property` \| `data_property` \| `annotation_property` \| `individual` \| `datatype` (narrows a punned IRI). |

**Returns**

- `deleted`: array of the removed entities as JSON objects.
- `removed_axioms`: number of axioms removed.

**Example**

```json
{ "entity": "Dog", "entity_type": "class" }
```

---

## `deprecate_entity`

Deprecates a term using the standard obsolescence pattern in one undoable step: asserts `owl:deprecated true`, and — when `replaced_by` is given — a "term replaced by" pointer (`IAO_0100001` by default; override with `replaced_by_property`) to the replacement term, plus any extra `annotations` (e.g. a `skos:changeNote`). The term and its axioms are kept — existing usages are NOT rewritten (repoint them with `rename_entity`, or edit each axiom, for a full merge). Re-deprecating with the same annotations is a no-op.

*Mutating (undoable)* — each annotation is added only if not already present; edits the active ontology. Returns an error object if the entity is not found.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Term to deprecate: an IRI or display name. |
| `replaced_by` | string | no | — | Replacement term: an IRI or display name. |
| `replaced_by_property` | string | no | `IAO_0100001` ("term replaced by") | Annotation property for the replacement pointer. |
| `annotations` | array | no | — | Extra annotations to add (e.g. a change note; array of `{property, value | value_iri, lang, datatype}`). |

**Returns**

- `deprecated`: the deprecated entity as a JSON object.
- `replaced_by`: the replacement IRI string, present only if one was given.
- `applied`: number of changes committed.
- `note`: present only when the term was already deprecated with the requested annotations (with `applied` 0).

**Example**

```json
{ "entity": "OldDog", "replaced_by": "Dog" }
```

---

## `move_class`

Reparents a class — its subtree follows automatically, since subclasses point at it. It removes the class's asserted NAMED superclass axioms and asserts `SubClassOf(class, new_parent)`. Anonymous restriction superclasses are left untouched, and an existing named parent equal to `new_parent` is kept. Pass `keep_other_parents=true` to ADD the new parent without removing the existing ones; omit `new_parent` to detach the class to a root.

*Mutating (undoable)* — applies as one undoable transaction; edits the active ontology. Returns an error object if `new_parent` is omitted while `keep_other_parents=true` (nothing to do).

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Class to move: an IRI or display name. |
| `new_parent` | string | no | — | New superclass: a class name, IRI or Manchester class expression (omit to detach to a root). |
| `keep_other_parents` | boolean | no | `false` | Add `new_parent` without removing existing named superclasses (default: replace them). |

**Returns**

- `moved`: the moved class as a JSON object.
- `new_parent`: the rendered new parent, present only when one was given.
- `removed_parents`: number of prior named superclass axioms removed.
- `applied`: number of changes committed.
- `note`: present only when there was no change (with `removed_parents`/`applied` 0).

**Example**

```json
{ "entity": "Terrier", "new_parent": "WorkingDog", "keep_other_parents": false }
```
