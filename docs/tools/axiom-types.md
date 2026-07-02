---
title: "Axiom types"
parent: "Tools"
nav_order: 11
---

# Axiom types
{: .no_toc }

`add_axiom`, `remove_axiom`, `preview_changes`, `apply_changes`, `explain_entailment`, and `get_explanations` all take the same structured **axiom operand set**: an `axiom_type` string plus the operands that type consumes. This page documents that shared vocabulary — the operand fields, the full `axiom_type` enum, and the optional reified `owl:Axiom` annotation pattern.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

An axiom operand set is a single JSON object. It always contains `axiom_type` (a string from the enum below), and then the operands that that type needs — no more, no less. Entity operands are given as an **IRI or display name** (the active renderer); class operands may additionally be a **Manchester-syntax class expression** (e.g. `Animal and (hasOwner some Person)`), and `range` may be a Manchester-syntax data range (e.g. `xsd:integer[>= 0]` or `{1, 2, 3}`).

Every `axiom_type` may additionally carry an optional `annotations` array (the reified `owl:Axiom` pattern): when present, the built axiom is wrapped with those annotations via `OWLAxiom.getAnnotatedAxiom`, so reified `owl:Axiom` blocks round-trip.

Because `add_axiom` and `remove_axiom` share this exact builder, an add and a remove of the same operand set are perfectly symmetric.

If `axiom_type` is missing from the enum, an error is raised naming the supported types. Operand-level problems (e.g. a list with too few members, a missing required operand, an unknown `entity_type`) also raise argument errors.

## Common operand fields

Only the operand keys below appear in the builder. Which ones a given `axiom_type` requires is spelled out in the table that follows.

| Field | Type | Meaning |
| --- | --- | --- |
| `axiom_type` | string | Selects the axiom to build; one of the enum values below. Always required. |
| `sub` | string | `subclass_of`: the subclass — name, IRI, or Manchester class expression. |
| `super` | string | `subclass_of`: the superclass — name, IRI, or Manchester class expression. |
| `classes` | string[] | `equivalent_classes` / `disjoint_classes` / `disjoint_union`: the class list (≥ 2 members) — names, IRIs, or Manchester class expressions. |
| `class` | string | `class_assertion` / `disjoint_union` / `has_key`: a single class — name, IRI, or Manchester class expression. |
| `individual` | string | `class_assertion`: the individual — IRI/name. |
| `individuals` | string[] | `same_individual` / `different_individuals`: individual IRI/name list (≥ 2 members). |
| `property` | string | `*_property_assertion`, property-characteristic axioms, `sub_*_property_of`, `*_property_domain`/`range`, `annotation_*`, `inverse_object_properties`: the (seed) property — IRI/name. |
| `properties` | string[] | `equivalent_`/`disjoint_object`/`data_properties`: property IRI/name list (≥ 2 members); `has_key`: key property IRI/name list (≥ 1 member). |
| `super_property` | string | `sub_object_property_of` / `sub_data_property_of` / `sub_property_chain_of` / `sub_annotation_property_of`: the super-property — IRI/name. |
| `chain` | string[] | `sub_property_chain_of`: ordered object-property IRI/name list (≥ 2 members). |
| `inverse_property` | string | `inverse_object_properties`: the inverse object property — IRI/name. |
| `subject` | string | `*_property_assertion` / `annotation_assertion`: the subject — IRI/name. |
| `object` | string | `object_property_assertion` / `negative_object_property_assertion`: the object individual — IRI/name. |
| `value` | string | `data_property_assertion` / `annotation_assertion`: the literal text value. |
| `value_iri` | string | `annotation_assertion`: an IRI-valued annotation (entity name/IRI or absolute IRI) — alternative to `value`. |
| `lang` | string | `data_property_assertion` / `annotation_assertion`: optional language tag (e.g. `en`) for a literal value. |
| `datatype` | string | `data_property_assertion` / `annotation_assertion`: optional datatype IRI/name for a typed literal; `datatype_definition`: the defined datatype IRI/name. |
| `entity` | string | `declaration`: the entity — IRI/name. |
| `entity_type` | string | `declaration`: `class` \| `object_property` \| `data_property` \| `annotation_property` \| `individual` \| `datatype`. |
| `domain` | string | `object_property_domain` / `data_property_domain`: domain class expression; `annotation_property_domain`: domain IRI. |
| `range` | string | `object_property_range`: range class expression; `data_property_range` / `datatype_definition`: datatype IRI/name or a Manchester-syntax data range; `annotation_property_range`: range IRI. |
| `annotations` | object[] | Optional axiom annotations (reified `owl:Axiom` pattern); element shape documented below. |

## Axiom types

Required operands are shown in **bold**; optional/contextual operands (including the always-optional `annotations`) are in plain text. Every row also accepts an optional `annotations` array.

| `axiom_type` | Operands (required in **bold**) | Meaning |
| --- | --- | --- |
| `subclass_of` | **`sub`**, **`super`** | `sub` ⊑ `super` (SubClassOf). |
| `equivalent_classes` | **`classes`** (≥ 2) | The listed classes are mutually equivalent. |
| `disjoint_classes` | **`classes`** (≥ 2) | The listed classes are pairwise disjoint. |
| `disjoint_union` | **`class`**, **`classes`** (≥ 2) | `class` is the disjoint union of `classes`. |
| `class_assertion` | **`class`**, **`individual`** | `individual` is an instance of `class`. |
| `object_property_assertion` | **`property`**, **`subject`**, **`object`** | `subject` `property` `object` (object-property fact). |
| `data_property_assertion` | **`property`**, **`subject`**, **`value`**, `datatype`, `lang` | `subject` `property` literal(`value`), typed by `datatype` or tagged by `lang` if given. |
| `negative_object_property_assertion` | **`property`**, **`subject`**, **`object`** | Asserts `subject` `property` `object` does **not** hold. |
| `negative_data_property_assertion` | **`property`**, **`subject`**, **`value`**, `datatype`, `lang` | Asserts the data-property fact does **not** hold. |
| `same_individual` | **`individuals`** (≥ 2) | The listed individuals denote the same thing. |
| `different_individuals` | **`individuals`** (≥ 2) | The listed individuals are pairwise distinct. |
| `sub_object_property_of` | **`property`**, **`super_property`** | `property` ⊑ `super_property` (object properties). |
| `sub_data_property_of` | **`property`**, **`super_property`** | `property` ⊑ `super_property` (data properties). |
| `sub_property_chain_of` | **`chain`** (≥ 2), **`super_property`** | The object-property chain implies `super_property`. |
| `equivalent_object_properties` | **`properties`** (≥ 2) | The listed object properties are equivalent. |
| `disjoint_object_properties` | **`properties`** (≥ 2) | The listed object properties are pairwise disjoint. |
| `equivalent_data_properties` | **`properties`** (≥ 2) | The listed data properties are equivalent. |
| `disjoint_data_properties` | **`properties`** (≥ 2) | The listed data properties are pairwise disjoint. |
| `inverse_object_properties` | **`property`**, **`inverse_property`** | `property` and `inverse_property` are inverses. |
| `transitive_object_property` | **`property`** | `property` is transitive. |
| `functional_object_property` | **`property`** | `property` is functional. |
| `inverse_functional_object_property` | **`property`** | `property` is inverse-functional. |
| `symmetric_object_property` | **`property`** | `property` is symmetric. |
| `asymmetric_object_property` | **`property`** | `property` is asymmetric. |
| `reflexive_object_property` | **`property`** | `property` is reflexive. |
| `irreflexive_object_property` | **`property`** | `property` is irreflexive. |
| `functional_data_property` | **`property`** | `property` is functional (data property). |
| `has_key` | **`class`**, **`properties`** (≥ 1) | Instances of `class` are keyed by the listed properties (each resolved as an object property, falling back to a data property). |
| `object_property_domain` | **`property`**, **`domain`** | Domain of `property` is the class expression `domain`. |
| `object_property_range` | **`property`**, **`range`** | Range of `property` is the class expression `range`. |
| `data_property_domain` | **`property`**, **`domain`** | Domain of the data property `property` is `domain`. |
| `data_property_range` | **`property`**, **`range`** | Range of the data property `property` is the data range `range`. |
| `annotation_assertion` | `property` (default `rdfs:label`), **`subject`**, **`value`** or **`value_iri`**, `datatype`, `lang` | Annotates `subject` with a literal (`value`, optionally typed/tagged) or an IRI (`value_iri`). |
| `sub_annotation_property_of` | **`property`**, **`super_property`** | `property` ⊑ `super_property` (annotation properties). |
| `annotation_property_domain` | **`property`**, **`domain`** | Domain IRI of the annotation property `property`. |
| `annotation_property_range` | **`property`**, **`range`** | Range IRI of the annotation property `property`. |
| `declaration` | **`entity`**, **`entity_type`** | Declares `entity` of kind `entity_type`. |
| `datatype_definition` | **`datatype`**, **`range`** | Defines `datatype` as the data range `range`. |

Notes:
- For `annotation_assertion`, `property` is optional and defaults to `rdfs:label`; supply exactly one of `value` (a literal, optionally with `datatype` or `lang`) or `value_iri` (an IRI-valued annotation). `value_iri` takes precedence when both are present.
- For `data_property_assertion` / `negative_data_property_assertion`, `datatype` and `lang` are optional; if `datatype` is set it wins, else `lang`, else a plain literal is built.

## The `annotations` operand

`annotations` is an optional array on **every** axiom type. Each element is an object with these fields (mirroring a single annotation value):

| Field | Type | Meaning |
| --- | --- | --- |
| `property` | string | Annotation property: `rdfs:label`, `rdfs:comment`, or an IRI/name. Defaults to `rdfs:label` when omitted. |
| `value` | string | Literal text value (omit if `value_iri` is given). |
| `value_iri` | string | IRI-valued annotation: an entity name/IRI or absolute IRI (alternative to `value`; takes precedence when both are present). |
| `lang` | string | Optional language tag for a literal value, e.g. `en`. |
| `datatype` | string | Optional datatype IRI/name for a typed literal value. |

When `annotations` is non-empty, the base axiom is re-emitted as an annotated axiom, reconstructing reified `owl:Axiom` blocks.

## Examples

A simple `subclass_of`:

```json
{
  "axiom_type": "subclass_of",
  "sub": "Dog",
  "super": "Animal and (hasOwner some Person)"
}
```

An `object_property_assertion` carrying a reified `owl:Axiom` annotation (a dated provenance note):

```json
{
  "axiom_type": "object_property_assertion",
  "property": "hasOwner",
  "subject": "Rex",
  "object": "Alice",
  "annotations": [
    {
      "property": "rdfs:comment",
      "value": "Confirmed by shelter intake form",
      "lang": "en"
    },
    {
      "property": "dcterms:source",
      "value_iri": "http://example.org/records/intake-2026"
    }
  ]
}
```

A `declaration` of a data property, and a `data_property_assertion` with an explicit datatype:

```json
{ "axiom_type": "declaration", "entity": "hasAge", "entity_type": "data_property" }
```

```json
{
  "axiom_type": "data_property_assertion",
  "property": "hasAge",
  "subject": "Rex",
  "value": "5",
  "datatype": "xsd:integer"
}
```
