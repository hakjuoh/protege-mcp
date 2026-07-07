---
title: "SPARQL"
parent: "Tools"
nav_order: 9
---

# SPARQL
{: .no_toc }

Query the live ontology with SPARQL 1.1 through an embedded Apache Jena ARQ engine, and author those queries safely: discover the queryable vocabulary and validate a draft before running it. The active ontology's imports closure is snapshotted into a private throwaway model, so Protégé's live ontology is never mutated; only read query forms (SELECT/ASK/CONSTRUCT/DESCRIBE) are allowed, and SPARQL UPDATE and SERVICE are rejected (edits go through the write tools; no network access).

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `sparql_query`

Runs a SPARQL 1.1 read query over the active ontology and its imports closure using the embedded Jena ARQ engine. It supports SELECT, ASK, CONSTRUCT, and DESCRIBE (SPARQL UPDATE and SERVICE are rejected). Prefixes declared in the ontology — plus the standard `rdf`/`rdfs`/`owl`/`xsd` — are auto-prepended, so queries can use those CURIEs without their own PREFIX lines. By default the query sees the ASSERTED triples (like Protégé's SPARQL Query tab); set `include_inferred=true` to first materialise the active reasoner's inferences. Reach for it whenever you want ad-hoc, pattern-based analysis of the ontology graph that goes beyond the entity-oriented read tools.

**Snapshot cache (0.4.1).** Building the queryable graph — copying the whole imports closure, serialising it,
and re-parsing it into Jena — is now cached and keyed by an edit-versioned model-state counter. A repeated
query at the **same** model state reuses the cached snapshot and skips that rebuild; any edit (or reload,
reasoner classification, active-ontology switch, or a `set_prefix` change) transparently invalidates it, so a
query after any change rebuilds. The asserted and `include_inferred` snapshots are cached separately, and each
query still re-parses the cached immutable bytes into a fresh Jena model — nothing mutable is shared. There
are no new arguments and the results are unchanged; this only makes repeated queries faster.

*Read-only* — works even in read-only mode. Always acts over the imports closure of the active ontology. Does not honour `strict` or report `new_entities`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `query` | string | yes | — | A SPARQL 1.1 query (SELECT, ASK, CONSTRUCT, or DESCRIBE). |
| `include_inferred` | boolean | no | `false` | Materialise the active reasoner's inferred axioms before querying (requires a classified reasoner; runs on the UI thread). |
| `limit` | integer | no | `1000` | Max rows (SELECT) or triples (CONSTRUCT/DESCRIBE) to return. Values ≤ 0 fall back to 1000. |
| `timeout_ms` | integer | no | `120000` | Overall time budget in ms, covering both the snapshot/inference step and the query evaluation. Values ≤ 0 fall back to 120000. |

**Returns**

The shape depends on the query form. Common:

- `query_type`: string — one of `SELECT`, `ASK`, `CONSTRUCT`, `DESCRIBE`.
- `note`: string (only when a large-ABox inference skip occurred with `include_inferred=true`) — explains that inferred property assertions were skipped.

For **SELECT**:

- `vars`: array of the projected variable names.
- `count`: integer — number of rows returned (capped at `limit`).
- `bindings`: array of rows, each an object mapping variable name → an RDF-node object. A node is `{type: "uri", value}`, `{type: "bnode", value}`, or `{type: "literal", value, lang?}` / `{type: "literal", value, datatype?}` (`datatype` omitted for plain `xsd:string`).
- `truncated`: boolean `true` — present only when more rows exist beyond `limit`.

For **ASK**:

- `boolean`: boolean — the ASK result.

For **CONSTRUCT** / **DESCRIBE**:

- `count`: integer — total triples in the result graph.
- `turtle`: string — the result graph serialised as Turtle (capped at `limit` triples, sorted deterministically before the cut when over `limit`).
- `truncated`: boolean `true` and `shown`: integer — present only when the graph exceeded `limit`; `shown` equals `limit`.

On a syntax error, an unsupported query form, a SERVICE clause, or a timeout, the call returns a tool error object with a `message` (e.g. the parser message, or "SPARQL query exceeded the … ms time budget").

**Example**

```json
{
  "query": "SELECT ?type (COUNT(?x) AS ?count) WHERE { ?x a ?type } GROUP BY ?type ORDER BY DESC(?count)",
  "limit": 50
}
```

Trimmed result:

```json
{
  "query_type": "SELECT",
  "vars": ["type", "count"],
  "count": 2,
  "bindings": [
    { "type": { "type": "uri", "value": "https://spec.industrialontologies.org/ontology/core/Core/MaterialArtifact" },
      "count": { "type": "literal", "value": "12", "datatype": "http://www.w3.org/2001/XMLSchema#integer" } }
  ]
}
```

---

## `sparql_schema`

Discovers the queryable vocabulary for authoring a SPARQL query over the active ontology (its imports closure by default — the same graph `sparql_query` sees). It returns the prefix map plus a ready-to-paste PREFIX block, and capped, sorted lists of classes, object/data properties (with their domains and ranges), named individuals, and datatypes — each with a CURIE (where one applies) and a full IRI — along with example queries built from the ontology's own terms. Reach for it before writing a query, or use `keyword` to focus on a sub-topic. This reflects asserted structure; for reasoner-derived triples, query with `sparql_query`'s `include_inferred=true`.

*Read-only* — works even in read-only mode. Acts over the imports closure by default (widen/narrow with `include_imports`). Does not honour `strict` or report `new_entities`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `keyword` | string | no | — | Only include classes/properties/individuals whose label or IRI contains this text (case-insensitive). |
| `limit` | integer | no | `100` | Max items per list. Values ≤ 0 fall back to 100. |
| `include_imports` | boolean | no | `true` | Include terms from the imports closure (`sparql_query` queries the closure). |
| `include_individuals` | boolean | no | `true` | Include a sample of named individuals. |
| `include_examples` | boolean | no | `true` | Include example queries grounded in the ontology's own terms. |
| `timeout_ms` | integer | no | `120000` | Time budget in ms for gathering the vocabulary on the UI thread. Values ≤ 0 fall back to 120000. |

**Returns**

- `active_ontology`: object — `{ontology_iri, version_iri, scope}` where `scope` is `"imports_closure"` or `"active"` (IRIs may be `null`).
- `counts`: object — full-signature counts: `{classes, object_properties, data_properties, individuals, datatypes}`.
- `matched`: object (only when `keyword` is given) — how many terms matched the keyword, keyed the same as `counts` (`individuals` included only when `include_individuals`).
- `prefixes`: object — the prefix map (name→namespace, names ending in `:`).
- `prefix_lines`: string — a ready-to-paste PREFIX block for those prefixes.
- `classes`: array of `{curie?, iri, display}` refs.
- `object_properties`: array of refs, each also with `domains` and `ranges` (arrays of CURIE/`<IRI>`/expression strings, capped).
- `data_properties`: array of refs, each also with `domains` and `ranges`.
- `individuals`: array (present only when `include_individuals`) of refs, each also with `types`.
- `datatypes`: array of `{curie?, iri, display}` refs.
- `examples`: array (present only when `include_examples`) of `{title, query}` objects.
- `keyword`: string — echoed only when supplied.
- `truncated`: object — present only when some list was capped; maps a list name (e.g. `"classes"`) → number of omitted items.
- `note`: string — guidance on using CURIEs and `sparql_validate`.

**Example**

```json
{
  "keyword": "material",
  "limit": 25,
  "include_individuals": false
}
```

Trimmed result:

```json
{
  "active_ontology": { "ontology_iri": "https://example.org/onto", "version_iri": null, "scope": "imports_closure" },
  "counts": { "classes": 240, "object_properties": 60, "data_properties": 18, "individuals": 0, "datatypes": 3 },
  "matched": { "classes": 4, "object_properties": 1, "data_properties": 0, "datatypes": 0 },
  "prefix_lines": "PREFIX iof: <https://spec.industrialontologies.org/ontology/core/Core/>\n...",
  "classes": [ { "curie": "iof:MaterialArtifact", "iri": "https://spec.industrialontologies.org/ontology/core/Core/MaterialArtifact", "display": "Material Artifact" } ]
}
```

---

## `sparql_validate`

Checks a SPARQL query before running it: the query is parsed (not executed, unless `dry_run=true`) and the result reports whether it parses, the query form, the projected variables, and whether `sparql_query` would accept it (`executable` — a read query with no SERVICE). The ontology's prefixes are auto-prepended just as `sparql_query` does, so a CURIE-only query validates without its own PREFIX lines. `unknown_terms` lists IRIs used in the query (graph patterns, property paths, VALUES, CONSTRUCT template, DESCRIBE targets) that are not declared as entities in the ontology — usually a typo or a term from another vocabulary. A syntax error is reported as `valid=false` with the engine's message, not as a tool error. Reach for it to catch mistakes before spending a full query run.

*Read-only* — works even in read-only mode. Term resolution and any dry run consult the active ontology and its snapshot. Does not honour `strict` or report `new_entities`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `query` | string | yes | — | The SPARQL query to check. |
| `dry_run` | boolean | no | `false` | Also execute the query with a small LIMIT to confirm it runs and return a sample of results. |
| `sample_limit` | integer | no | `5` | Rows/triples to return when `dry_run=true`. Values ≤ 0 fall back to 5. |
| `timeout_ms` | integer | no | `120000` | Time budget for the dry run in ms. Values ≤ 0 fall back to 120000. |

**Returns**

- `valid`: boolean — whether the text parses as SPARQL (a valid UPDATE also yields `valid=true`).
- `query_type`: string — `SELECT`, `ASK`, `CONSTRUCT`, `DESCRIBE`, `UPDATE`, `UNKNOWN`, or `null` on a parse error.
- `executable`: boolean — whether `sparql_query` will accept it (a read form with no SERVICE).
- `vars`: array of projected variable names (populated for SELECT; empty otherwise).
- `uses_service`: boolean — whether the query uses a SERVICE clause.
- `parse_error`: string — present only when the text fails to parse; the engine's message.
- `unknown_terms`: array of `{curie?, iri}` objects — referenced IRIs not declared in the ontology (self ontology/version IRIs and rdf/rdfs/owl/xsd builtins are excluded).
- `issues`: array of human-readable strings describing problems found (does-not-parse, is-an-UPDATE, uses-SERVICE, unknown-terms).
- `sample`: object — present only when `dry_run=true` and the query is executable; a full `sparql_query`-shaped result run at `sample_limit`.
- `sample_error`: string — present only when a dry run was attempted but the execution failed.
- `note`: string — guidance on interpreting `executable` and `unknown_terms`.

**Example**

```json
{
  "query": "SELECT ?x WHERE { ?x a iof:MaterialArtefact }",
  "dry_run": true,
  "sample_limit": 5
}
```

Trimmed result (note the misspelled local name surfaced as an unknown term):

```json
{
  "valid": true,
  "query_type": "SELECT",
  "executable": true,
  "vars": ["x"],
  "uses_service": false,
  "unknown_terms": [
    { "curie": "iof:MaterialArtefact", "iri": "https://spec.industrialontologies.org/ontology/core/Core/MaterialArtefact" }
  ],
  "issues": ["1 referenced term(s) are not declared as a class/property/individual/datatype in the ontology (usually a typo or a term from another vocabulary) — see unknown_terms."],
  "sample": { "query_type": "SELECT", "vars": ["x"], "count": 0, "bindings": [] }
}
```
