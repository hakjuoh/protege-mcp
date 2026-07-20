---
title: "Context & validation"
parent: "Tools"
nav_order: 2
---

# Context & validation
{: .no_toc }

Tools that orient an assistant in the active ontology, surface a single term's neighbourhood, audit modelling quality and project governance, and diff two ontologies at the axiom level. All are read-only.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `get_ontology_context`

A one-call orientation overview of the active ontology: its id, signature counts, imports, ontology-level annotations, the asserted root classes (direct children of `owl:Thing`), sampled object/data properties, the reasoner state, and the prefix map. Reach for it first when you drop into an unfamiliar ontology, then use `get_entity_context` to drill into a specific term.

*Read-only.* Reports asserted structure only (use `run_reasoner` / `get_inferred_superclasses` for inferences).

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `limit` | integer | no | 50 | Max items per sampled list — roots, properties. |

**Returns**

- `active_ontology`: object `{ontology_iri, version_iri, anonymous}` (`ontology_iri`/`version_iri` may be null).
- `counts`: object of signature counts — `axioms`, `logical_axioms`, `classes`, `object_properties`, `data_properties`, `annotation_properties`, `individuals`, `datatypes`.
- `imports`: array of imported ontology IRI strings.
- `ontology_annotations`: array of annotation objects.
- `root_classes`: array of entity rows (asserted children of `owl:Thing`), capped at `limit`.
- `object_properties`: array of sampled object-property entity rows, capped at `limit`.
- `data_properties`: array of sampled data-property entity rows, capped at `limit`.
- `reasoner`: object `{selected_id, selected_name, status, results_available}` (plus `stale: true` when the reasoner is `OUT_OF_SYNC`).
- `prefixes`: object mapping prefix name to prefix IRI.
- `write_protection`: string, `"read-only"` or `"writable"`.
- `note`: string guidance pointer.

**Example**

```json
{ "limit": 25 }
```

## `get_entity_context`

An "entity card" for one term in a single call: its type(s), labels/annotations, whether it is deprecated, its number of referencing axioms, and its asserted neighbourhood. For a class that is super/sub/equivalent/disjoint classes and asserted instances; for an object property, domains, ranges, super/sub properties, inverses and characteristics; for a data property, domains/ranges/super/sub properties and whether it is functional; for an annotation property, super/sub properties plus domain/range IRIs; for a named individual, types, object/data property values, and same/different individuals. Resolves an IRI or display name; if the name is punned across several entity types, every match gets its own card.

*Read-only.* Asserted structure only. Acts over the imports closure by default (`include_imports`).

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Entity IRI or display name. |
| `include_imports` | boolean | no | true | Include the imports closure. |
| `limit` | integer | no | 50 | Max items per neighbourhood list. |

**Returns**

- `query`: string, the `entity` reference as passed.
- `count`: integer, number of cards (matches found).
- `include_imports`: boolean, echoing the effective scope.
- `entities`: array of entity-card objects. Each card has `iri`, `display`, `type`, `deprecated` (boolean), `annotations` (array), `referencing_axioms` (integer), plus type-specific neighbourhood keys — for a class: `super_classes`, `sub_classes`, `equivalent_classes`, `disjoint_classes`, `instances`; for an object property: `domains`, `ranges`, `super_properties`, `sub_properties`, `inverses`, `characteristics` (array of strings); for a data property: `domains`, `ranges`, `super_properties`, `sub_properties`, `functional` (boolean); for an annotation property: `super_properties`, `sub_properties`, `domains`, `ranges` (arrays of IRI strings); for a named individual: `types`, `object_property_values`, `data_property_values`, `same_as`, `different_from`. Neighbour entries are either `{iri, display, type}` or, for anonymous expressions, `{expression, anonymous: true}`; `object_property_values`/`data_property_values` are rows of `{property, values}`.
- `note`: present only when more than one card is returned ("The IRI is punned across several entity types.").

If no entity resolves, returns an error object (see the shared error shape) suggesting `search_entities`.

**Example**

```json
{ "entity": "Widget", "include_imports": true, "limit": 25 }
```

## `get_model_revision`

Returns the optimistic-concurrency envelope for this Protégé backend/window. The workspace UUID and
monotonic session counter are combined with canonical semantic and live-document fingerprints. The
document fingerprint is recomputed, so a prefix-only GUI edit is visible even when Protégé emits no
ontology-change event. Use the returned `revision` unchanged with `commit_change_set`.

*Read-only.*

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `policy_path` | string | no | discovered | Explicit project policy; otherwise discover upward from the active document. |

**Returns**

- `revision`: object `{workspace_id, session_revision, semantic_fingerprint, document_fingerprint}`.
- `workspace_id`, `session_revision`, `semantic_fingerprint`, `document_fingerprint`: flattened copies of the revision coordinates.
- `ontology`: object `{ontology_iri, version_iri, document_iri}`.
- `dirty`: boolean; Protégé's saved-state flag, which remains true after Undo until the next save even
  when `semantic_fingerprint` returns to the loaded content. `dirty_semantics` states this explicitly.
  `reasoner`: selected-reasoner metadata.
- `fingerprint_stability`, `release_stable`, `fingerprint_warnings`: canonicalization guarantees/caveats.
- `policy_loaded`, `policy_valid`: booleans; optional `policy_path`, `policy_digest`, `policy_error`, and `import_lock_digest`.

**Example**

```json
{ "policy_path": "/workspace/.protege-mcp/project.yaml" }
```

## `validate_ontology`

Audits the active ontology for modelling-quality issues — not logical consistency. It runs structural checks and reports, per check, a count, sample offenders, a severity, and a fix suggestion. The checks (in report order) are: `missing_label`, `missing_definition`, `duplicate_label`, `multiple_labels`, `deprecated_in_use`, `undeclared_entity`, `property_missing_domain`, `property_missing_range`, `self_subclass`, `subclass_cycle`, `isolated_class`. Imported terms declared upstream are not flagged for missing label/definition/domain/range when auditing the active ontology alone; set `include_imports=true` to audit the whole closure as owned. A clean audit is NOT proof of logical consistency — pass `with_reasoner=true` to also fold in the reasoner's verdict.

*Read-only.* Can widen to the imports closure via `include_imports`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `include_imports` | boolean | no | false | Audit the imports closure too. |
| `checks` | string array | no | all | Subset of check ids to run. |
| `with_reasoner` | boolean | no | false | Also report the reasoner's consistency / unsatisfiable classes (uses the already-classified reasoner; run `run_reasoner` first for a current verdict). |
| `limit` | integer | no | 25 | Max sample offenders/details per check. |
| `timeout_ms` | integer | no | 60000 | Time budget in ms before the call returns a timeout error. Bounds the caller's wait, not the on-thread work itself. (Non-positive values are coerced back to 60000.) |

**Returns**

- `scope`: string, `"imports_closure"` or `"active"`.
- `total_issues`: integer, summed offender counts across the run checks.
- `checks`: array of per-check rows, each `{id, severity, title, count, suggestion, examples}` and, when present, `details` (array of human-readable lines, e.g. cycle descriptions). `examples` is a capped list of entity rows.
- `reasoner`: present only when `with_reasoner=true`; object `{status, results_available, ...}` — when results are current it adds `consistent` and, if consistent, `unsatisfiable_count` and `unsatisfiable_classes`; if inconsistent, a `note`; otherwise a `note` that no current reasoner results exist. May include an `error` string if the reasoner call throws.
- `reasoner_note`: string guidance pointer.

**Example**

```json
{ "include_imports": false, "checks": ["missing_label", "subclass_cycle"], "with_reasoner": true, "limit": 10 }
```

## `validate_governance`

Audits the active ontology against PROJECT GOVERNANCE rules — a configurable policy rather than universal smells, complementing `validate_ontology`'s generic quality checks and `run_reasoner`'s logic checks. Two checks run by default: `owl_profile` (OWL 2 profile conformance, default DL, or EL/QL/RL; `'none'`/`'Full'` skips) and `check_ownership` (the active module must not assert logical axioms about IMPORTED terms — an import-layering violation). Two more are opt-in: an IRI policy (`required_namespaces` and/or `iri_pattern`) and a `required_annotations` suite (every owned class/property must carry each listed annotation property). "Owned" means declared in the audited scope, not purely imported; set `include_imports=true` to treat the whole closure as owned.

*Read-only.* Can widen to the imports closure via `include_imports`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `include_imports` | boolean | no | false | Treat the whole imports closure as owned and audit it too. |
| `owl_profile` | string | no | DL | OWL 2 profile to check against: DL, EL, QL, RL. Pass `'none'` or `'Full'` to skip the profile check. |
| `required_namespaces` | string array | no | — | Owned entity IRIs must start with one of these namespace prefixes. |
| `iri_pattern` | string | no | — | Owned entity IRIs must match this Java regular expression (applied to the full IRI). |
| `required_annotations` | string array | no | — | Annotation properties every owned class/property must carry: an IRI/CURIE/name, or the specials `'label'` (rdfs:label) and `'definition'`. |
| `check_ownership` | boolean | no | true | Flag logical axioms in the active ontology whose subject is an imported (upstream) term. |
| `limit` | integer | no | 25 | Max sample offenders/details per check. |
| `timeout_ms` | integer | no | 60000 | Time budget in ms before the call returns a timeout error. Bounds the caller's wait, not the work. (Non-positive values are coerced back to 60000.) |

**Returns**

- `scope`: string, `"imports_closure"` or `"active"`.
- `profile`: string, the effective profile name (e.g. `"DL"`, `"EL"`, or `"none"`).
- `total_violations`: integer, summed `count` across all included checks.
- `checks`: array of per-check rows. Every row includes `identity_digest`, a SHA-256 identity of the complete finding set (not just capped examples). The profile check is `{id: "owl_profile", severity: "error", title, in_profile, owned_in_profile, count, identity_digest, suggestion, examples}` plus, when present, `imported_violations` and `truncated` (the number of violations beyond `examples`). `in_profile` covers the whole audited closure while `count`/`owned_in_profile` cover only the violations attributable to the audited scope — its own axioms, and for the ontology-header violations OWLAPI reports without a backing axiom (an undeclared property/entity used in a header annotation, a reserved or relative ontology IRI), the scope's own header; header violations attributable only to an import stay in `imported_violations`, and an unattributable kind fails closed into the owned count. Each governance finding is `{id, severity, title, count, identity_digest, suggestion}` plus, when present, `examples` (entity rows), `axioms` (axiom rows — used by the `import_layering` finding), and `details` (human-readable lines). Finding ids include `iri_policy`, `required_annotations`, and `import_layering`.
- `notes`: present only when phase 1 collected config problems (e.g. a `required_annotations` reference that could not be resolved); array of strings.
- `note`: string guidance pointer.

If `iri_pattern` is not a valid regular expression, or `owl_profile` is unknown, the call returns an error object.

**Example**

```json
{
  "owl_profile": "EL",
  "required_namespaces": ["https://example.org/myproject/"],
  "required_annotations": ["label", "definition"],
  "check_ownership": true,
  "limit": 15
}
```

## `diff_ontologies`

Diffs two ontologies at the axiom level — the round-trip safety net for multi-module reconstruction. The `left` side (default the active ontology) is compared against a `right` loaded ontology, or against a `right_document` loaded purely for comparison (path/URL/IRI, never added to the workspace). Reports counts and capped samples of axioms only-in-left and only-in-right, with `identical=true` when the two axiom sets match (a faithful round-trip). Use `include_imports` to compare imports closures and `logical_only` to ignore declarations and annotation assertions.

*Read-only.* Pure set arithmetic over axiom sets; nothing is loaded into the workspace when comparing against a document. Can widen to imports closures via `include_imports`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `left` | string | no | active | Ontology IRI/version of a loaded ontology. |
| `right` | string | no | — | Ontology IRI/version of a loaded ontology to compare against. |
| `right_document` | string | no | — | Path/URL/IRI of a document to load and compare against (alternative to `right`; not added to the workspace). |
| `include_imports` | boolean | no | false | Compare full imports closures instead of just the two ontologies. |
| `logical_only` | boolean | no | false | Compare logical axioms only, ignoring declarations and annotation assertions. |
| `limit` | integer | no | 50 | Max axioms to list per side. |
| `network` | string | no | — | Request-level network control for loading `right_document`, composed most-restrictive-wins with the project policy: `deny` refuses every remote fetch with an explicit error attributed to `request network=deny`; `allow` abstains and never overrides a policy deny, an invalid policy, a missing `network:access` capability, or a restricted no-policy state. |

One of `right` or `right_document` must be supplied.

**Returns**

- `left`: string label of the left ontology (its ontology IRI, plus version if present, or `"(anonymous ontology)"`).
- `right`: string label of the right side (the `right_document` source when given, otherwise the `right` reference).
- `include_imports`: boolean, echoing the effective scope.
- `logical_only`: boolean, echoing the mode.
- `identical`: boolean, true when neither side has exclusive axioms.
- `left_axioms`: integer, total axioms collected on the left.
- `right_axioms`: integer, total axioms collected on the right.
- `common`: integer, count of axioms shared by both sides.
- `only_in_left`: array of axiom rows present only in `left`, capped at `limit`.
- `only_in_right`: array of axiom rows present only in `right`, capped at `limit`.
- `right_document_unresolved_imports`: array of import IRIs the right side's loader could not
  resolve; present only when `right_document` was given (empty when everything resolved).
- `caveat`: string warning that the right side's imports closure is truncated and the verdict may be
  affected; present only when `include_imports=true` and `right_document_unresolved_imports` is
  non-empty.

If neither `right` nor `right_document` is provided, if `left`/`right` names no loaded ontology, or if the comparison document cannot be loaded, the call returns an error object.

**Example**

```json
{
  "right_document": "/Users/me/ontologies/mymodule.ttl",
  "logical_only": true,
  "include_imports": false
}
```

## `semantic_diff`

Classifies an ontology diff into release-oriented categories while retaining
`diff_ontologies` as the fast exact-axiom primitive. `mode=asserted` (the default) reports
header/import changes, entity adds and removals by type, conservative unique exact-label rename
candidates, annotation/lifecycle/replacement deltas, and asserted axioms grouped by type and affected
IRI. Rename rows are evidence, never automatic rewrite instructions. Import IRIs the `right_document`
loader could not resolve are reported, and with `include_imports=true` they force
`potentially_breaking`: a truncated right closure fails closed instead of passing a review gate as
`metadata_only` or identical.

`mode=inferred|both` additionally evaluates the supported `inferred-diff-v1` entailment set: both
sides are flattened and classified sequentially by one explicitly recorded reasoner (the current
Protégé selection, or `reasoner`), reporting consistency, class satisfiability, named-class
subsumption and equivalence closure deltas, named individual types, and candidate-bounded
disjointness — the candidates are only the class pairs named together in an asserted
`DisjointClasses`/`DisjointUnion` axiom on either side, and the scope label discloses this. Property
hierarchies, characteristics, and assertions are machine-readably excluded, and every category fails
closed: an operation the reasoner or budget could not answer becomes an errored category, never a
silently empty one. `mode=both` keeps the asserted sections byte-identical to `mode=asserted` apart
from the single result-level `compatibility` block. With `policy_path`, two policy-driven categories
are added beside the entailment set: `module_ownership` (terms whose owning module per
`modules[].owned_namespaces` differs between sides) and member-level `stage_deltas` for the policy's
required CQ/invariant/SHACL/governance stages.

*Read-only.* A `right_document` is loaded privately — resolving its imports through the workspace's
known logical-to-document mappings and any sibling `catalog-v001.xml` — and is never attached to the
workspace. Inferred-mode classification runs on isolated flattened copies; no live reasoner state is
touched.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `left` | string | no | active | Loaded ontology IRI/version. |
| `right` | string | conditional | — | Loaded ontology IRI/version; exactly one of this and `right_document`. |
| `right_document` | string | conditional | — | Document loaded privately; exactly one of this and `right`. |
| `include_imports` | boolean | no | false | Include each side's loaded imports closure. |
| `mode` | string | no | asserted | `asserted`, `inferred`, or `both`. |
| `reasoner` | string | no | current selection | Installed Protégé reasoner name or id used to classify both sides in `mode=inferred\|both` (see `list_reasoners`). Resolved with the shared reasoner-reference rule (`set_reasoner`, policy `reasoning.reasoner`): a factory id or full display name matches exactly (case-insensitive); a version-less name (`HermiT`) must match exactly one installed reasoner. Ambiguity is an error listing every candidate as `name [factory id]`, so duplicate display names can be resolved by id. |
| `timeout_ms` | integer | no | 120000 | Total `mode=inferred\|both` budget in ms covering all reasoner interaction and stage-delta evaluation. (Non-positive values are coerced back to 120000.) |
| `policy_path` | string | no | — | Project policy file driving `module_ownership` and `stage_deltas` in `mode=inferred\|both`; authorized like other `policy_path` tools. Refused (never silently ignored) with `mode=asserted`; an unresolvable or invalid policy errors both categories fail-closed. |
| `limit` | integer | no | 50 | Maximum samples per category. |
| `network` | string | no | — | Request-level network control for loading `right_document`, composed most-restrictive-wins with the project policy: `deny` refuses every remote fetch with an explicit error attributed to `request network=deny`; `allow` abstains and never overrides a policy deny, an invalid policy, a missing `network:access` capability, or a restricted no-policy state. |

**Returns**

- `mode`, `include_imports`, `identical`: effective comparison mode/scope and exact aggregate equality.
- `right_document_unresolved_imports`: array of import IRIs the right side's loader could not resolve (empty when everything resolved, or when `right` named an already-loaded ontology). With `include_imports=true` any entry forces `potentially_breaking` and is named in the caveat.
- `ontology_id`, `imports`, `ontology_annotations`: `{changed, left, right}` pairs.
- `entities`: object with typed `added` and `removed` groups and counts.
- `rename_candidates`: array `{from, to, entity_type, evidence}`; emitted only for unambiguous exact-label pairs.
- `annotation_changes`: array `{focus_iri, added, removed, categories}`.
- `asserted_axioms`: `added`/`removed` objects with `count`, `groups`, and `truncated`.
- `inferred`: present in `mode=inferred|both`; the `inferred-diff-v1` categories `{entailment_set, excluded_categories, consistency, satisfiability, subsumption, equivalence, types, disjointness, errored_categories}` over the shared named signature Σ (consistency/satisfiability use each side's full named signature), with bounded samples, exact counts, per-side `direct`/`indirect` labels, transitively reduced subsumption deltas, and the `asserted_candidates` disjointness scope label (with disclosed truncation at the candidate cap). An inconsistent side suppresses the member-level categories with a caveat (`categories_suppressed: true`); a category that could not be answered carries an `error` and is listed in `errored_categories`. On overall expiry the whole section degrades to `{entailment_set, error}`.
- `reasoner`: configuration-parity metadata of the recorded reasoner that classified both sides; a told-only reasoner is disclosed here. Inferred results are relative to this reasoner, never presented as reasoner-independent OWL truth.
- `module_ownership`: present in `mode=inferred|both`; the `modules[].owned_namespaces` policy delta. With a loaded valid policy declaring owned namespaces: `{available: true, count, changes, truncated?}`, each change row `{iri, was_module, now_module}` (a side's owner is `null` when the term is absent from that side's named signature or unowned; co-owned namespaces list the owning module IRIs comma-joined), sorted with bounded samples and the exact `count`. Without such a policy: `{available: false, reason}`. A policy that failed to resolve, load, or validate: `{error}` — fail-closed, never silently absent.
- `stage_deltas`: present in `mode=inferred|both`; member-level finding-identity deltas for the policy's required `cqs`/`invariants`/`shacl`/`governance` stages evaluated against both sides — `{available: true}` plus one object per evaluated stage, each either `{entered: {count, items, truncated?}, left: {count, items, truncated?}}` (complete finding identities present on only the right/left side, sorted with bounded samples and exact counts) or `{error}` when that stage could not be evaluated on a side (for example an inference-dependent CQ or invariant fails closed rather than running incompletely). The governance delta carries `scope: "policy_rules_only"`: it covers only the policy's rule-driven annotation/lifecycle/waiver checks evaluated per side — NOT the intrinsic `run_project_qc` governance checks (`iri_policy`, required namespaces/annotations, ownership, import layering) or the module/import checks, which need live workspace context a foreign right side does not have. Without `policy_path`: `{available: false, reason}`; an unresolvable/invalid policy or an abandoned evaluation: `{error}`.
- `compatibility`: object `{classification, policy_driven, anonymous_individual_churn, caveat}`, emitted once per result. The asserted classification is conservative: `potentially_breaking` when the header changed (ontology id or imports declarations), any entity or logical axiom was removed, any logical axiom was added (OWL is monotonic — a new axiom such as a `DisjointClasses` can make previously consistent data inconsistent), or the right closure was truncated under `include_imports=true`; `metadata_only` when there is no logical change and no entity was added; `non_breaking` otherwise (new entities carrying only declarations and annotations). In `mode=inferred|both` the block additionally fails closed on inferred and policy evidence: a consistency transition, newly unsatisfiable classes, an errored inferred section or category, suppressed member-level categories, or an errored stage delta forces `potentially_breaking` with a caveat naming the missing evidence; asserted caveat strings are preserved verbatim. `anonymous_individual_churn` flags blank-node values — in axioms or ontology-header annotations — whose parse-local NodeIDs can make a re-parsed document look changed; the caveat then explains that such churn may be spurious.

**Example**

```json
{ "right_document": "/workspace/releases/next.ttl", "mode": "both", "reasoner": "HermiT", "policy_path": ".protege-mcp/project.yaml", "limit": 100 }
```

## `analyze_change_impact`

Read-only **syntactic** impact analysis of a change. Analyzes exactly one
input form: a cached change-set preview (`change_set_id` from `preview_change_set`, or `create_terms` /
`create_properties` with `preview=true` — its stored normalized delta, with the preview's
`base_revision` echoed), or an asserted diff pair (`left`, default the active ontology, against a loaded
`right` ontology or a privately loaded `right_document`, the same machinery as `diff_ontologies`). Every
category reports exact counts with bounded samples (`limit`), and the whole result is labelled
`analysis: "syntactic"`: co-occurrence in an asserted axiom proves syntactic reachability, never logical
impact — run `semantic_diff` `mode=inferred|both` for entailment-level evidence. Impact driven by an
inferred (reasoner-computed) delta is deliberately deferred: this tool analyzes the **asserted
projection** of a change only, and the result's `note` points to `semantic_diff mode=inferred|both`
for proven logical impact.

*Read-only.* A `right_document` is loaded privately (workspace import mappings plus any sibling
`catalog-v001.xml`, like `diff_ontologies`) and never attached to the workspace. A change-set entry is
claimed for the duration of the (read-only) analysis and remains cached afterwards; validation-asset
text is read off the UI thread. **Scan scope follows the input form:** the change-set form evaluates
the reference scans (referencing axioms, downstream sweep, deprecated candidates) over the active
ontology, while the pair form evaluates them over the **compared `left`/`right` ontologies' union** —
independent of whatever happens to be active. `include_imports` widens both the pair diff scope and
that reference scan to the corresponding imports closures.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `change_set_id` | string | conditional | — | Cached preview to analyze (exactly one input form: this or `right`/`right_document`). Unknown or in-flight ids are refused with `error_code`. |
| `left` | string | no | active | Loaded ontology IRI/version (pair form only). |
| `right` | string | conditional | — | Loaded ontology IRI/version to diff against; exactly one of this and `right_document`. |
| `right_document` | string | conditional | — | Document loaded privately for comparison; exactly one of this and `right`. |
| `include_imports` | boolean | no | false | Widen the pair diff and the reference scan to the corresponding imports closures. |
| `policy_path` | string | no | — | Project policy driving the `modules` attribution (`modules[].owned_namespaces`) and the `validation_references` asset search; authorized like other `policy_path` tools. An unresolvable or invalid policy errors both categories fail-closed. |
| `limit` | integer | no | 50 | Maximum samples per category; exact counts are always reported. |
| `network` | string | no | — | Request-level network control for loading `right_document`, composed most-restrictive-wins with the project policy: `deny` refuses every remote fetch with an explicit error; `allow` abstains and never overrides a policy deny, an invalid policy, a missing `network:access` capability, or a restricted no-policy state. |

**Returns**

- `analysis`: the literal `"syntactic"` — this tool computes syntactic/asserted analysis only.
- `note`: string pointing to `semantic_diff mode=inferred|both` for proven logical impact.
- `change_set_id`, `base_revision`: echoed for the change-set input form (the entry's complete stored revision envelope); the refusal shape for an unknown/in-flight id is `{change_set_id, analyzed: false, error_code}` with `unknown_change_set` or `change_set_in_progress`.
- `left`, `right`: side labels for the pair input form (the ontology IRI/version, or the authorized `right_document` source).
- `right_document_unresolved_imports`: import IRIs the right document's loader could not resolve (pair form with `right_document` only; empty when everything resolved).
- `caveat`: string warning that the right side's imports closure is truncated and the analysis may be incomplete in either direction; present only when `include_imports=true` and `right_document_unresolved_imports` is non-empty.
- `include_imports`: boolean, echoing the effective scope.
- `delta`: `{added_axioms, removed_axioms}` — the analyzed change's exact axiom counts.
- `directly_affected`: `{count, items, truncated?, modules}` — every IRI in the delta axioms' signatures (including annotation subjects and IRI annotation values, which OWLAPI keeps out of axiom signatures), each item `{iri, added, removed}` with exact per-IRI axiom counts, sorted most-affected first. `modules` attributes the affected IRIs to owning modules via the policy's `modules[].owned_namespaces` most-specific matcher: `{available: true, count, items: [{module, terms: {count, items, truncated?}}], truncated?, unowned: {count, items, truncated?}}` with a loaded valid policy declaring owned namespaces, `{available: false, reason}` without one, and `{error}` (fail-closed, never silently absent) when a supplied policy failed to resolve, load, or validate. When several modules co-own a namespace, `module` is their owning module IRIs comma-joined into one label — the same convention as `semantic_diff`'s `module_ownership` owners.
- `referencing_axioms`: `{count, items, truncated?}` — in-scope axioms (change-set form: the active ontology; pair form: the compared ontologies' union; the corresponding closures when `include_imports`) that reference a directly-affected entity, including annotation assertions on an affected IRI, but are **not** themselves part of the delta; rendered axiom rows.
- `downstream_terms`: `{analysis: "syntactic", depth_cap, size_cap, count, items, truncated?, search_truncated?, search_note?}` — a bounded breadth-first co-occurrence sweep from the affected entities over the same scan scope: each item `{iri, depth}`. The sweep stops at 3 hops or 1,000 discovered terms; `search_truncated: true` (decided by one probe expansion, never guessed) discloses that reachable terms beyond the caps are neither listed nor counted.
- `foreign_reaxiomatization`: `{count, items, truncated?}` — delta axioms whose **subject** entity is declared in an imported closure member rather than an analyzed root ontology (the import-layering notion of `validate_governance`; in the pair form both compared sides' closures contribute); each item `{operation, subject, axiom_type, rendering}`.
- `deprecated_terms_in_use`: `{count, items, truncated?}` — entities carrying `owl:deprecated true` anywhere in the analyzed closures that are referenced by the delta or by the referencing axioms.
- `validation_references`: textual occurrences of affected IRIs in validation assets. With a loaded valid policy: `{available: true, match: "textual", count, items, truncated?, files_scanned, files_skipped?, scan_truncated?, scan_note?, searched_iris, workspace_cq_error?}` — plain substring matching (`match: "textual"`, no parsing) of up to 500 affected IRIs against the policy-resolved `invariants`/`shacl`/`cqs` asset files (up to 100 files, 1 MiB each; oversized or unreadable files are skipped with a reason) and the in-workspace CQ stores; each item `{source, ref, iris}` where `source` is `invariants`, `shacl`, `cqs`, or `workspace_cq` (workspace rows additionally carry `convention`). `files_scanned` counts only files whose text was actually read and searched — a skipped file is accounted in `files_skipped` instead, never in both; `files_skipped` is `{count, items, truncated?}` with the first 10 skip reasons sampled. Without `policy_path`: `{available: false, reason}`; an unresolvable/invalid policy: `{error}`.
- `public_api_terms`: `{available: false, reason: "policy v1 does not yet declare public API terms"}` — policy v1 declares no public-API term set yet.
- `external_mappings`: `{available: false, reason: "mapping management is not yet available"}` — the SSSOM mapping store does not exist yet.

**Example**

```json
{ "change_set_id": "0b1c2d3e-…", "policy_path": ".protege-mcp/project.yaml", "limit": 100 }
```
