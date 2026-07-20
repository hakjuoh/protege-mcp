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

Lists every ontology currently loaded in the Protégé workspace — the active ontology, its imports closure, and any other loaded ontologies — and marks which one is active, which have **unsaved changes** (dirty), and where each is saved (its document). Reach for it first to see what is available, to confirm which ontology your edits will target, and to check what still needs saving before `save_ontology`.

*Read-only.*

**Arguments**

None.

**Returns**

- `count`: integer — number of loaded ontologies.
- `active`: string — the active ontology's label (its ontology IRI, or `(anonymous ontology)`).
- `dirty_count`: integer — number of loaded ontologies with unsaved changes.
- `ontologies`: array — one row per loaded ontology, each `{id, anonymous, active, dirty, axioms, logical_axioms, document}` where `id` is the ontology label (string), `anonymous`/`active`/`dirty` are booleans (`dirty` = unsaved changes), `axioms`/`logical_axioms` are the total and logical axiom counts, and `document` is the document IRI it was loaded from / saves to.
- `note`: string — a reminder that the active ontology is the target of edits and that `save_ontology` writes the active ontology (`all=true` saves every dirty one).

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
- `signature_entities`: integer — number of distinct entities the scope's own axioms and ontology
  annotations reference. Loaded-but-out-of-scope imports never count, and neither do the implicit
  datatypes of plain literals (OWLAPI's cached signature reports both).
- `entity_types`: object — a map of entity-type name to count (sorted).
- `axiom_types`: object — a map of axiom-type name to count, capped at `limit` rows.
- `axiom_types_truncated`: integer — present only when the axiom-type map was capped; how many rows were omitted.

**Example**

```json
{ "include_imports": true, "limit": 40 }
```

## `list_classes`

Lists the named classes in the active ontology's signature, each with its display rendering and IRI. Use it for a quick enumeration of the classes actually declared in the active ontology.

**Paginated (0.4.1).** Pass `offset` (0-based) alongside `limit` to page through a signature larger than one
page. The result carries `count` (the total), `offset`, `returned`, `items`, and — when more remain —
`next_offset`; pass a returned `next_offset` straight back as `offset` to page forward. The sort is
**totally ordered** (display, then IRI), so paging never drops or repeats a class across a page boundary.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `limit` | integer | no | `200` | Max classes to return in this page. |
| `offset` | integer | no | `0` | 0-based index of the first class to return (for paging). |

**Returns**

A totally-ordered (display, then IRI) page of the class list:

- `count`: integer — full number of classes found.
- `offset`: integer — the 0-based offset this page started at.
- `returned`: integer — number of classes in `items` for this page.
- `items`: array — each entry `{iri, display, type}`.
- `next_offset`: integer — present only when more classes remain; pass it back as `offset` to fetch the next page.

**Example**

```json
{ "limit": 100, "offset": 100 }
```

## `search_entities`

Searches entities by name or IRI fragment across the loaded ontologies. Filter by `type` (`class`, `object_property`, `data_property`, `annotation_property`, `individual`, `datatype`, or `all`). A plain fragment matches as a substring and `*` acts as a wildcard; an empty or wildcard-only `query` lists the active ontology's own signature (narrowed to `type`; loaded
imports are not listed). One asymmetry: a wildcard `type=datatype` query reads OWLAPI's per-type
accessor and so still lists the implicit datatypes of plain literals (e.g. `xsd:string`), which the
`type=all` listing derives from the document's own content and therefore omits. This is the general-purpose lookup when you know part of a name but not its exact rendering or IRI.

**Grounding- and synonym-aware.** Results are **ranked** across renderer text, IRI/local name, preferred
labels, and synonyms. Runtime defaults include `rdfs:label`, SKOS preferred/alternative labels, and OBO
exact/related synonyms; a locally valid `entity_search` policy block can replace those properties and prioritize up to four
preferred and four fallback languages. Matching folds case, whitespace, and diacritics. Every hit explains
its source, matched value/property, language, normalization, score adjustments, collisions, and whether
review is required.

`best_match` is reserved for an exact IRI/renderer/local-name/preferred-label ground. A fuzzy or synonym
hit is returned separately as `reuse_candidate` and never changes `would_mint`; review it before minting.
If several IRIs share an exact preferred name, no winner is chosen, `mint_blocked_by_collision=true`, and
`would_mint=false`. Full-IRI, Manchester-expression, multi-word, blank, wildcard-only, and normalization-empty
queries are never mint candidates.

**Paginated (0.4.1).** Pass `offset` (0-based) alongside `limit` to page through a large result set. The
result carries `count` (the total), `offset`, `returned`, `items`, and — when more remain — `next_offset`;
pass a returned `next_offset` straight back as `offset` to page forward. The ranking (score, then display,
then IRI — a stable tiebreak) is a **total order**, so paging never drops or repeats a hit across a page
boundary. The grounding fields (`best_match` / `would_mint`) reflect the whole result set, not just the page.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `query` | string | yes | — | Search text (use `*` as a wildcard). |
| `type` | string | no | `all` | Entity type filter. |
| `limit` | integer | no | `50` | Max results in this page. |
| `offset` | integer | no | `0` | 0-based index of the first match to return (for paging). |

**Returns**

A totally-ordered, **ranked** page (by `score`, then display, then IRI — a stable tiebreak) plus the grounding
fields and echoed query:

- `count`: integer — full number of matches.
- `offset`: integer — the 0-based offset this page started at.
- `returned`: integer — number of matches in `items` for this page.
- `items`: array — each entry includes `iri`, `display`, `type`, `score`, `match_kind`, `match_source`,
  `matched_value`, nullable `matched_property`/`language`, `normalization`, `score_explanation`, `collision`,
  optional `collision_iris`, and `needs_review`.
- `next_offset`: integer — present only when more matches remain; pass it back as `offset` to fetch the next page.
- `would_mint`: boolean — true when a single-term query resolves to no existing entity.
- `best_match`: string or null — the IRI the query grounds to.
- `mint_blocked_by_collision`: boolean — true when distinct IRIs share an exact preferred name.
- `reuse_candidate`: object or null — the highest review-only approximate/synonym candidate.
- `collisions`: array — exact normalized-name collisions for the submitted query.
- `lexical_policy`: object — effective preferred/synonym property IRIs and language priorities.
- `lexical_match_count` / `lexical_candidates_truncated`: integers — present when more than 1,000 lexical
  candidates matched; report the total and how many were omitted before full rendering/ranking.
- `query`: string — the query as submitted.
- `type`: string — the effective type filter (`"all"` when none was given).

**Example**

```json
{ "query": "neuron", "type": "class", "limit": 25, "offset": 0 }
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

**Paginated (0.4.1).** Pass `offset` (0-based) alongside `limit` to page through an entity's referencing
axioms rather than getting a single truncated blob. The `axioms` block carries `count` (the total),
`offset`, `returned`, `items`, and — when more remain — `next_offset`; pass a returned `next_offset` back as
`offset` to page forward. The sort is **totally ordered** (by rendering, then the axiom's own natural order),
so paging never drops or repeats an axiom across a page boundary.

*Read-only.* Acts over the imports closure when `include_imports` is set. Returns an error object if no entity matches.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Entity IRI or display name. |
| `include_imports` | boolean | no | `false` | Also search the imports closure. |
| `limit` | integer | no | `100` | Max axioms to return in this page. |
| `offset` | integer | no | `0` | 0-based index of the first axiom to return (for paging). |

**Returns**

- `entity`: object — the resolved entity as `{iri, display, type}`.
- `include_imports`: boolean — the effective scope flag.
- `axioms`: object — a totally-ordered (by rendering, then the axiom's natural order) page `{count, offset, returned, items, next_offset?}`, where each `items` entry is `{axiom_type, rendering}`, `returned` is the page size, and `next_offset` (present only when more remain) is passed back as `offset` to fetch the next page.

If the entity cannot be resolved, the tool returns an error object of the form `{ "error": "No entity found for '<ref>'." }`.

**Example**

```json
{ "entity": "hasOwner", "include_imports": true, "limit": 50, "offset": 0 }
```
