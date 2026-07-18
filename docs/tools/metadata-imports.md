---
title: "Ontology metadata & imports"
parent: "Tools"
nav_order: 5
---

# Ontology metadata & imports
{: .no_toc }

Tools that edit the ontology header rather than its axioms: the ontology IRI/version, the prefix map, `owl:imports` declarations, and ontology-level annotations. These changes are `OWLOntologyChange`s (not `OWLAxiom`s), so they cannot be expressed through `add_axiom`; together with `add_axiom`/`add_annotation` they let an ontology header be reconstructed incrementally.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `set_ontology_id`

Sets the active ontology's IRI and, optionally, its version IRI. Reach for this when you are stamping a fresh ontology with its permanent identifier or cutting a new version IRI. If the requested id is already bound to a *different* ontology loaded in Protégé (for example one of its imports), the call returns an error instead of throwing a rename exception.

*Mutating (undoable)* — applied through `OWLModelManager.applyChange`, so it is GUI-visible and joins the undo stack; gated by the read-only/confirm-each-write preferences. Acts on the active ontology only.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `ontology_iri` | string | yes | — | New ontology IRI. |
| `version_iri` | string | no | — | Optional version IRI. |

**Returns**

- `ontology_iri`: string — the new ontology IRI (or `null` if anonymous).
- `version_iri`: string — the new version IRI; present only when a version was set.
- `message`: string — human-readable confirmation, e.g. `Set ontology id to <iri> version <iri>.`

On an id collision, an error object is returned instead: `{ "error": "Ontology id ... is already in use by another ontology loaded in Protégé ..." }`.

**Example**

```json
{
  "ontology_iri": "https://example.org/ont/demo",
  "version_iri": "https://example.org/ont/demo/1.0.0"
}
```

## `set_prefix`

Registers or updates a prefix in the active ontology's prefix map (for example binding `ex-av` → `https://example.org/ont/annotation/`) so that CURIEs like `ex-av:maturity` render and parse and the document serializes with the prefix. The prefix map lives in the ontology's document format, so this changes no axioms.

*Mutating (not undoable)* — it edits the document format's prefix map directly and is **not** on the undo stack. Still gated by the read-only/confirm-each-write preferences. Errors if the active ontology's document format has no prefix map.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `prefix` | string | yes | — | Prefix name, with or without a trailing `':'` (e.g. `'ex-av'` or `'ex-av:'`); use `':'` for the default namespace. |
| `namespace` | string | yes | — | Namespace IRI the prefix expands to. |

**Returns**

- `prefix`: string — the normalized prefix name (always ends with `':'`).
- `namespace`: string — the namespace the prefix now expands to.
- `prefixes`: object — the full prefix-name → namespace map after the change.

On failure, an error object is returned: `{ "error": "The active ontology's document format has no prefix map (format: ...)." }`.

**Example**

```json
{
  "prefix": "ex-av",
  "namespace": "https://example.org/ont/annotation/"
}
```

## `add_import`

Adds an `owl:imports` declaration to the active ontology. The result's `resolved` flag reports whether the imported document actually loaded into the workspace — an unresolved import is a dangling declaration whose terms stay invisible to lookups and reasoning until it is loaded. Pass `document` (a path/URL/IRI) to resolve the import immediately by loading that document *without* changing your active edit target.

*Mutating (undoable)* — the declaration is applied through `OWLModelManager.applyChange` (GUI-visible, undoable) and gated by the read-only/confirm-each-write preferences. If `document` is supplied and the declaration did not already resolve, the document is loaded (keeping the active ontology unchanged).

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `iri` | string | yes | — | Imported ontology IRI. |
| `document` | string | no | — | Optional path/URL/IRI of the import's document to load now so the import resolves (keeps the active ontology unchanged). |
| `connection_timeout_ms` | integer | no | `15000` | Document connection timeout when `document` is given. |
| `network` | string | no | — | Request-level network control for loading `document`, composed most-restrictive-wins with the project policy: `deny` refuses every remote fetch with an explicit error attributed to `request network=deny`; `allow` abstains and never overrides a policy deny, an invalid policy, a missing `network:access` capability, or a restricted no-policy state. |

**Returns**

- `added`: boolean — `true` if the declaration was newly added (`false` if it was already present).
- `iri`: string — the imported ontology IRI.
- `already_present`: boolean — whether the declaration was already on the ontology.
- `resolved`: boolean — whether the imported document is loaded in the workspace.
- `document`: string — echoed only when `document` was supplied.
- `note`: string — present only when unresolved; explains that imported terms will not appear in lookups/reasoning until loaded, and suggests passing `document`, using `load_ontology` with `keep_active=true`, or adding a catalog mapping.

If the write is denied or the document fails to load, an error object is returned.

**Example**

```json
{
  "iri": "https://example.org/ontologies/upper",
  "document": "https://example.org/ontologies/upper.owl"
}
```

## `remove_import`

Removes an `owl:imports` declaration from the active ontology. Use it to drop an import you no longer depend on. Errors if the declaration is not present on the active ontology.

*Mutating (undoable)* — applied through `OWLModelManager.applyChange` (GUI-visible, undoable); gated by the read-only/confirm-each-write preferences. Acts on the active ontology only.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `iri` | string | yes | — | Imported ontology IRI to remove. |

**Returns**

- `removed`: boolean — `true` when the declaration was removed.
- `iri`: string — the imported ontology IRI that was removed.

If the import is not present, an error object is returned: `{ "error": "Import not present in the active ontology: <iri>" }`.

**Example**

```json
{
  "iri": "https://example.org/ontologies/upper"
}
```

## `add_ontology_annotation`

Adds an ontology-level annotation such as `dcterms:title` or `owl:versionInfo` to the active ontology. The value is a literal (optionally typed with `datatype` or tagged with `lang`) or, with `value_iri`, an IRI. Nested annotations on the annotation itself are supported via `annotations`.

*Mutating (undoable)* — applied through `OWLModelManager.applyChange` (GUI-visible, undoable); gated by the read-only/confirm-each-write preferences. Acts on the active ontology only.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `property` | string | no | `rdfs:label` | Annotation property: `'rdfs:label'`, `'rdfs:comment'`, or an IRI/name. |
| `value` | string | no | — | Literal text value (omit if `value_iri` is given). |
| `value_iri` | string | no | — | IRI-valued annotation: an entity name/IRI or absolute IRI. |
| `lang` | string | no | — | Optional language tag for a literal value, e.g. `'en'`. |
| `datatype` | string | no | — | Optional datatype IRI/name for a typed literal value. |
| `annotations` | array | no | — | Optional nested annotations on this annotation. |

**Returns**

- `added`: boolean — `true` if the annotation was newly added (`false` if it was already present).
- `annotation`: object — the resulting annotation rendered as JSON (property plus literal/IRI value and any nested annotations).
- `already_present`: boolean — whether the ontology already carried this annotation.

**Example**

```json
{
  "property": "dcterms:title",
  "value": "Demo Ontology",
  "lang": "en"
}
```

## `remove_ontology_annotation`

Removes an ontology-level annotation from the active ontology. Takes the same arguments as `add_ontology_annotation`; the annotation is reconstructed from those arguments and must match one already present, or the call errors.

*Mutating (undoable)* — applied through `OWLModelManager.applyChange` (GUI-visible, undoable); gated by the read-only/confirm-each-write preferences. Acts on the active ontology only.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `property` | string | no | `rdfs:label` | Annotation property: `'rdfs:label'`, `'rdfs:comment'`, or an IRI/name. |
| `value` | string | no | — | Literal text value (omit if `value_iri` is given). |
| `value_iri` | string | no | — | IRI-valued annotation: an entity name/IRI or absolute IRI. |
| `lang` | string | no | — | Optional language tag for a literal value, e.g. `'en'`. |
| `datatype` | string | no | — | Optional datatype IRI/name for a typed literal value. |
| `annotations` | array | no | — | Optional nested annotations on this annotation. |

**Returns**

- `removed`: boolean — `true` when the annotation was removed.
- `annotation`: object — the removed annotation rendered as JSON.

If no matching annotation exists, an error object is returned: `{ "error": "Ontology annotation not present: <property> = <value>" }`.

**Example**

```json
{
  "property": "dcterms:title",
  "value": "Demo Ontology",
  "lang": "en"
}
```
