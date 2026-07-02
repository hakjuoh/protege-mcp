---
title: "Rules (SWRL)"
parent: "Tools"
nav_order: 7
---

# Rules (SWRL)
{: .no_toc }

Structured read, add, and remove for SWRL rules (`swrl:Imp` axioms) in the active ontology. Because OWL API 4 has no standalone rule-text parser and the generic `add_axiom` tool has no rule type, these tools build rules from a structured body/head atom representation. That structured form is the faithful primitive for rule reconstruction: a rule variable is identified by its IRI (which the conventional `?x` text syntax cannot preserve), and rule-level annotations ride on the same `annotations` operand as the other write tools.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `list_rules`

Lists every SWRL rule (`swrl:Imp` axiom) in the active ontology as structured body/head atoms plus any rule annotations and a text rendering. Rules are returned in a deterministic order sorted by their rendered string, and variable arguments are emitted as `?<absolute IRI>` so a listed rule round-trips through `add_rule` unchanged (no namespace hint needed). Reach for this to survey the rules present, to grab a stable `index` for `remove_rule`, or to capture a rule's structured form for replay. Set `include_imports=true` to also pull in rules from the imports closure.

*Read-only.* Can widen to the imports closure via `include_imports`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `include_imports` | boolean | no | `false` | Include rules from imported ontologies too. |

**Returns**

- `count`: integer — number of rules returned.
- `rules`: array of rule objects, each `{index, body, head, variables, annotations?, rendering}`, where:
  - `index`: integer — 0-based position in the rendering-sorted order (usable as `remove_rule`'s `index` when called without `include_imports`).
  - `body` / `head`: arrays of atom objects. Each atom is `{type, ...}`; e.g. a `class` atom carries `predicate`, `predicate_iri` (when not anonymous), and `arg1`; `object_property` adds `arg2`; `data_property` carries `arg1` plus `value`(+`lang`|`datatype`) or `arg2`; `same_as`/`different_from` carry `arg1`, `arg2`; `builtin` carries `builtin` and `args` (each a `?variable`/plain-literal string or a `{value, datatype|lang}` object).
  - `variables`: array of strings — the rule's variable IRIs.
  - `annotations`: array of annotation objects — present only when the rule has rule-level annotations.
  - `rendering`: string — the axiom's text rendering under the active renderer.

**Example**

```json
{ "include_imports": true }
```

Trimmed result:

```json
{
  "count": 1,
  "rules": [
    {
      "index": 0,
      "body": [
        { "type": "class", "predicate": "Person", "predicate_iri": "http://ex/Person", "arg1": "?http://ex#x" }
      ],
      "head": [
        { "type": "class", "predicate": "Adult", "predicate_iri": "http://ex/Adult", "arg1": "?http://ex#x" }
      ],
      "variables": ["http://ex#x"],
      "rendering": "Person(?x) -> Adult(?x)"
    }
  ]
}
```

## `add_rule`

Adds a SWRL rule (`swrl:Imp`) to the active ontology, built from structured `body` and `head` atoms. Each atom is `{type, ...}`: `class` `{predicate, arg1}`; `object_property` `{predicate, arg1, arg2}`; `data_property` `{predicate, arg1, arg2 | value[+lang|datatype]}`; `same_as`/`different_from` `{arg1, arg2}`; `builtin` `{builtin, args[]}`. An argument starting with `?` is a variable (`?name` expands to `variable_namespace + name`; `?<IRI>` uses that IRI exactly); otherwise an i-argument is an individual and a d-argument is a literal. Optional `annotations` attach rule-level `rdfs:label`/`comment`/etc. The rule is the implication body → head: the body may be empty, but the head must have at least one atom. Reach for this to reconstruct a rule from a `list_rules` capture or to author a new one atom by atom.

*Mutating (undoable)* — applies through `OWLModelManager`, so it joins the shared undo stack and obeys the read-only / confirm-each-write gates. Reports whether the rule was newly added versus already present. Acts on the active ontology only.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `body` | array of atom objects | no | — | Body atoms (the rule's premise; may be empty). |
| `head` | array of atom objects | yes | — | Head atoms (the rule's conclusion; at least one). |
| `variable_namespace` | string | no | `urn:swrl#` | Namespace for `?name` variables. `?<absolute IRI>` ignores this. |
| `annotations` | array of annotation objects | no | — | Optional rule-level annotations (`rdfs:label`, `dcterms:description`, …). |

Each atom object accepts `type`, `predicate`, `predicate_iri`, `arg1`, `arg2`, `value`, `lang`, `datatype`, `builtin`, and `args` (see `list_rules` return shape and the per-type combinations above). Each annotation object accepts `property` (default `rdfs:label`), `value`, `value_iri`, `lang`, `datatype`.

**Returns**

- `added`: boolean — true if the rule was newly added (false if it already existed).
- `already_present`: boolean — true if the axiom was already in the ontology.
- `rule`: object — the rule as structured JSON (`body`, `head`, `variables`, optional `annotations`, `rendering`); no `index` in this context.

On invalid input the tool returns an error object (e.g. an empty `head` yields `A SWRL rule needs at least one head atom (body may be empty).`).

**Example**

```json
{
  "body": [
    { "type": "class", "predicate": "Person", "arg1": "?x" },
    { "type": "data_property", "predicate": "hasAge", "arg1": "?x", "arg2": "?age" },
    { "type": "builtin", "builtin": "swrlb:greaterThanOrEqual", "args": ["?age", { "value": "18", "datatype": "xsd:int" }] }
  ],
  "head": [
    { "type": "class", "predicate": "Adult", "arg1": "?x" }
  ],
  "annotations": [
    { "property": "rdfs:label", "value": "adults are people 18 or older" }
  ]
}
```

## `remove_rule`

Removes a SWRL rule from the active ontology. Identify the target by `index` (into the same rendering-sorted order `list_rules` returns, called without `include_imports`) and/or `label` (its `rdfs:label`), or by passing the same structured `body`/`head`/`annotations` you would give `add_rule` to remove that exact rule. When `head` is supplied, the exact-match path is used and a body/head that matches no axiom in the ontology is an error; when identifying by `index` an out-of-range value is an error, and a `label` that matches no rule is an error. You must supply at least one identifier (`index`, `label`, or a non-empty structured `body`/`head`).

*Mutating (undoable)* — applies through `OWLModelManager`, so it joins the shared undo stack and obeys the read-only / confirm-each-write gates. Acts on the active ontology only. Note: a `label` can match more than one rule, in which case all matching rules are removed.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `body` | array of atom objects | no | — | Body atoms of the exact rule to remove (with `head`). |
| `head` | array of atom objects | no | — | Head atoms of the exact rule to remove (with `body`). |
| `variable_namespace` | string | no | `urn:swrl#` | Namespace for `?name` variables. `?<absolute IRI>` ignores this. |
| `annotations` | array of annotation objects | no | — | Rule-level annotations, matched as part of the exact rule. |
| `index` | integer | no | — | 0-based index into the active ontology's rules in the same rendering-sorted order `list_rules` returns (call `list_rules` without `include_imports`). |
| `label` | string | no | — | Remove rule(s) whose `rdfs:label` equals this. |

**Returns**

Exact-match path (when `head` is supplied):

- `removed`: integer — number of rules removed (`1` on success).
- `rule`: object — the removed rule as structured JSON.

Index/label path:

- `removed`: integer — number of rules removed.
- `rules`: array of the removed rules as structured JSON objects.

Returns an error object when no identifier is given, when the exact rule is not present, when `index` is out of range, or when no rule has the requested `label`.

**Example**

```json
{ "index": 0 }
```

Or by exact structure:

```json
{
  "body": [ { "type": "class", "predicate": "Person", "arg1": "?x" } ],
  "head": [ { "type": "class", "predicate": "Adult", "arg1": "?x" } ]
}
```
