---
title: CQ vocabulary
nav_order: 9
---

# Competency-question annotation vocabulary
{: .no_toc }

The tool-internal annotation vocabulary Protégé MCP uses when a competency question travels *inside*
the ontology artifact.
{: .fs-6 .fw-300 }

- **Namespace IRI:** `https://hakjuoh.github.io/protege-mcp/cq#`
- **Suggested prefix:** `cq`
- **Machine-readable:** [`cq.ttl`](cq.ttl) (Turtle)

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Why a tool namespace

Competency questions managed by the
[`add_competency_question` / `run_competency_questions` tools](tools/quality.html#add_competency_question)
are stored in one of three conventions. Two are files next to the ontology document
(`robot-sparql-dir`, the default writer, and `sidecar-manifest`); the third —
`ontology-annotations` — stores each CQ **inside the ontology itself** as an ontology-level
annotation, and is the fallback writer when the ontology has no saved document to place a file
next to.

That annotation property is *plugin bookkeeping*, not a project term, so it is minted in this
namespace — one the plugin controls — rather than in your ontology's namespace. A fixed, global
IRI is what lets any Protégé MCP instance (or any other tool aware of this vocabulary) rediscover
the CQs in an artifact regardless of the ontology's own IRI, and it keeps the plugin from
injecting terms into your terminological space. For the same reason, entities under
`https://hakjuoh.github.io/protege-mcp/` are exempt from `validate_ontology` /
`validate_governance` owned-term audits.

This is a *hash namespace*: this page is the namespace document, and each term is a fragment of
it. GitHub Pages cannot content-negotiate, so the HTML lives at the namespace IRI and the Turtle
is published alongside as [`cq.ttl`](cq.ttl).

---

## competencyQuestion
{: #competencyQuestion }

`https://hakjuoh.github.io/protege-mcp/cq#competencyQuestion`

An `owl:AnnotationProperty`. Each **ontology-level** annotation under this property holds **one**
competency question, serialised as a JSON object in an `xsd:string` literal — the same JSON shape
a `sidecar-manifest` entry uses:

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `id` | string | yes | Stable id *within this store* (e.g. `CQ-1`). Not globally unique. |
| `text` | string | no | The natural-language competency question. |
| `type` | string | no | Optional category, e.g. `Scoping` \| `Validating`. |
| `query_lang` | string | no | Defaults to `sparql` (the only supported value; a DL path is reserved). |
| `query` | string | yes | Executable SPARQL 1.1 `SELECT` or `ASK`. |
| `include_inferred` | boolean | no | Run over inferred triples too (default `true`). |
| `expected` | object | no | Pass condition (default `{"kind":"nonEmpty"}`), one of: `{"kind":"nonEmpty"}`, `{"kind":"empty"}`, `{"kind":"count","op":">=","value":N}` (`op` ∈ `>=`, `<=`, `==`, `>`, `<`), `{"kind":"exactRows","rows":[…]}`. On load a compact string is also accepted (`"nonEmpty"` \| `"empty"` \| `"count >= 3"`); the plugin always writes the object form. |
| `tags` | array | no | Free-form tags. |

An annotation whose literal does not parse as a JSON object describing a valid CQ — a non-empty
`id`, a `query`, a supported `query_lang`, a well-formed `expected` — is **skipped on load**
(reported under `skipped`, never fatal) — see
[`list_competency_questions`](tools/quality.html#list_competency_questions).

### Example

An ontology carrying one CQ under this convention:

```turtle
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix cq:  <https://hakjuoh.github.io/protege-mcp/cq#> .

<http://example.org/onto> a owl:Ontology ;
    cq:competencyQuestion """{"id":"CQ-1","text":"Does every process have a participant?","query_lang":"sparql","query":"SELECT ?p WHERE { ?p a <http://example.org/onto#Process> . FILTER NOT EXISTS { ?p <http://example.org/onto#hasParticipant> ?x } }","include_inferred":true,"expected":{"kind":"empty"}}""" .
```

Writes are ordinary undoable ontology changes (one transaction per CQ upsert), gated by the same
write-consent preference as every other write tool.

---

## Stability

The property IRI and the JSON field set above are a public contract from the moment they are
written into an artifact: existing fields are never repurposed, and additions stay
backwards-compatible. Fields this vocabulary does not define are ignored on load — and dropped
when the plugin rewrites the annotation — so do not park third-party data inside the literal.
