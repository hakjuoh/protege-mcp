---
title: "Explore & search"
parent: "Tools"
nav_order: 1
---

# Explore & search
{: .no_toc }

Read-only tools for getting your bearings in the currently loaded ontologies: list what is loaded, inspect the active ontology, summarize its signature and axioms, and find and resolve individual entities and the axioms that reference them.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `list_ontologies`

Lists every ontology currently loaded in Protégé — the active ontology together with its imports closure — and marks which one is active. Reach for it first to see what is available and to confirm which ontology your edits will target.

*Read-only.*

**Arguments**

None.

**Returns**

- `count`: integer — number of loaded ontologies.
- `active`: string — the active ontology's label (its ontology IRI, or `(anonymous ontology)`).
- `ontologies`: array — one row per loaded ontology, each `{id, anonymous, active, axioms, logical_axioms}` where `id` is the ontology label (string), `anonymous` and `active` are booleans, and `axioms`/`logical_axioms` are the total and logical axiom counts.
- `note`: string — a reminder that the active ontology is the target of edits.

**Example**

```json
{}
```

## `get_active_ontology`

Reports the details of the active ontology: its IRI and version IRI, axiom counts, direct imports, and whether the plugin is currently write-protected. Use it to confirm the edit target and its read-only state before making changes.

*Read-only.*

**Arguments**

None.

**Returns**

- `ontology_iri`: string or null — the ontology IRI (null if anonymous).
- `version_iri`: string or null — the version IRI (null if none).
- `anonymous`: boolean — whether the ontology ID is anonymous.
- `axioms`: integer — total axiom count.
- `logical_axioms`: integer — logical axiom count.
- `direct_imports`: array of strings — the IRIs of the ontology's direct import declarations.
- `write_protection`: string — either `"read-only (plugin setting)"` or `"writable"`.

**Example**

```json
{}
```

## `summarize_ontology`

Produces a signature-and-axiom overview of the active ontology: counts of entities by type, ontology annotations, import declarations, and axiom-type counts. Set `include_imports=true` to summarize the whole imports closure instead of just the active ontology. Reach for it to size up an ontology's shape before diving into individual entities. Acts over the imports closure when `include_imports` is set.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `include_imports` | boolean | no | `false` | Also summarize the imports closure. |
| `limit` | integer | no | `80` | Max axiom-type rows to return. |

**Returns**

- `scope`: string — `"imports_closure"` or `"active"`.
- `ontologies`: integer — number of ontologies covered by the scope.
- `axioms`: integer — total axioms in scope.
- `logical_axioms`: integer — logical axioms in scope.
- `ontology_annotations`: integer — count of ontology-level annotations.
- `import_declarations`: integer — count of import declarations.
- `signature_entities`: integer — number of distinct entities in the signature.
- `entity_types`: object — a map of entity-type name to count (sorted).
- `axiom_types`: object — a map of axiom-type name to count, capped at `limit` rows.
- `axiom_types_truncated`: integer — present only when the axiom-type map was capped; how many rows were omitted.

**Example**

```json
{ "include_imports": true, "limit": 40 }
```

## `list_classes`

Lists the named classes in the active ontology's signature, each with its display rendering and IRI. Use it for a quick enumeration of the classes actually declared in the active ontology.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `limit` | integer | no | `200` | Max classes to return. |

**Returns**

A capped, display-sorted entity list:

- `count`: integer — full number of classes found.
- `items`: array — each entry `{iri, display, type}`.
- `truncated`: integer — present only when the list was capped; how many entries were omitted.

**Example**

```json
{ "limit": 100 }
```

## `search_entities`

Searches entities by name or IRI fragment across the loaded ontologies. Filter by `type` (`class`, `object_property`, `data_property`, `annotation_property`, `individual`, `datatype`, or `all`). A plain fragment matches as a substring and `*` acts as a wildcard; an empty or wildcard-only `query` lists the active ontology's whole signature (narrowed to `type`). This is the general-purpose lookup when you know part of a name but not its exact rendering or IRI.

**Grounding-aware (0.4.0).** Results are **ranked**: each hit carries a `score` and a `match_kind`
(`exact` \| `prefix` \| `substring` \| `fuzzy` — the *exact* tier considers every `rdfs:label` language
variant and the IRI local name, case/whitespace/diacritic-folded). Two top-level fields help decide
whether to reuse a term or mint a new one: `best_match` is the IRI the query grounds to (or `null`), and
`would_mint` is true when a **single-term** query grounds to nothing — so using it as a `create_*` name
would introduce a NEW entity. A full-IRI, Manchester-expression, or multi-word query is never flagged.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `query` | string | yes | — | Search text (use `*` as a wildcard). |
| `type` | string | no | `all` | Entity type filter. |
| `limit` | integer | no | `50` | Max results. |

**Returns**

A capped, **ranked** entity list (by `score`, then display, then IRI — a stable tiebreak) plus the grounding
fields and echoed query:

- `count`: integer — full number of matches.
- `items`: array — each entry `{iri, display, type, score, match_kind}`.
- `truncated`: integer — present only when the list was capped; how many matches were omitted.
- `would_mint`: boolean — true when a single-term query resolves to no existing entity.
- `best_match`: string or null — the IRI the query grounds to.
- `query`: string — the query as submitted.
- `type`: string — the effective type filter (`"all"` when none was given).

**Example**

```json
{ "query": "neuron", "type": "class", "limit": 25 }
```

## `get_entity`

Looks up an entity by IRI or display name and returns its type(s), IRI, and rendering. Because an IRI can be "punned" across several entity types (e.g. a class and an individual sharing an IRI), the result can hold more than one match. Use it to resolve a reference to concrete entities and see how it renders.

*Read-only.* Returns an error object if no entity matches.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Entity IRI or display name. |

**Returns**

- `query`: string — the reference as submitted.
- `count`: integer — number of matching entities.
- `matches`: array — each entry `{iri, display, type}`.
- `note`: string — present only when `count > 1`; states that the IRI is punned across several entity types.

If nothing matches, the tool returns an error object of the form `{ "error": "No entity found for '<ref>'." }`.

**Example**

```json
{ "entity": "Person" }
```

## `get_axioms_for_entity`

Returns the axioms that reference a given entity. By default it searches only the active ontology; set `include_imports=true` to also pull in axioms from the imports closure — useful for seeing an imported term's declared domain/range and class restrictions. Reach for it to understand how an entity is actually used.

*Read-only.* Acts over the imports closure when `include_imports` is set. Returns an error object if no entity matches.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Entity IRI or display name. |
| `include_imports` | boolean | no | `false` | Also search the imports closure. |
| `limit` | integer | no | `100` | Max axioms to return. |

**Returns**

- `entity`: object — the resolved entity as `{iri, display, type}`.
- `include_imports`: boolean — the effective scope flag.
- `axioms`: object — a capped, sorted axiom list `{count, items, truncated?}`, where each `items` entry is `{axiom_type, rendering}` and `truncated` (when present) is how many axioms were omitted.

If the entity cannot be resolved, the tool returns an error object of the form `{ "error": "No entity found for '<ref>'." }`.

**Example**

```json
{ "entity": "hasOwner", "include_imports": true, "limit": 50 }
```
