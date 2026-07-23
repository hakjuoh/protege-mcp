---
title: "Reasoning"
parent: "Tools"
nav_order: 8
---

# Reasoning
{: .no_toc }

Select and run the reasoner Protégé has installed, then read what it inferred — unsatisfiable classes, inferred class/individual relations, DL-query answers, entailment checks, minimal justifications (explanations), and a minimal explanation of an inconsistent ontology. Every result is a structured JSON object.

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## `get_reasoner_capabilities`

Reports capabilities for the reasoner currently selected in Protege without creating or running a
reasoner. The identity includes the factory id/class and class-file SHA-256, a digest and explicit list
of the reviewed runtime package scopes plus their class count, implementation version, configuration-class
SHA-256, complete configuration digest,
semantic configuration digest, configuration profile, buffering mode, and a stable profile key. Profile
selection requires one explicitly reviewed tuple of id, code digest/scopes/count, version, semantic
configuration, and effective buffering mode; matching independent allowlists, a name, or a version is
insufficient. The complete digest still records operational timeout/monitor changes, while
those changes do not falsely erase identical inference capabilities. Only the reviewed HermiT
`1.3.8.431`, OWLAPI structural reasoner `4.5.29`, and ELK `0.5.0` identities receive a `reviewed` profile;
all other versions or non-reviewed/uninspectable semantic configurations remain `unknown`.

*Read-only.* Also available through headless stdio for its fixed bundled reasoner.

**Arguments**

None.

**Returns**

- `identity`: exact non-secret reasoner and configuration identity.
  It also exposes timeout, progress-monitor class, fresh-entity policy, and individual-node policy
  separately from the digest. `timeout_ms: -1` is the OWLAPI/HermiT sentinel for no configured
  timeout; `-2` means an unrecognized configuration was deliberately not invoked or reflected.
- `reviewed_code_scopes`, `reviewed_code_class_count`, and `reviewed_code_digest` attest every class,
  including inner classes, in the reviewed reasoner, OWLAPI, and compatibility package scopes. Raw plugin
  and shaded CLI layouts are separate exact tuples. The pinned counts are below the 6,000-class cap
  (HermiT raw 2,635/modular replacement 2,722/shaded 3,118; structural 1,533; ELK modular
  4,848/official Protege bundle 4,871).
  Every HermiT tuple covers the inference-critical Apache Axiom XML/datatype implementation; both
  replacement layouts additionally cover their `net/automatalib/**` implementation.
  Multi-release entries and matching classes in mixed nested OSGi JARs are included as a candidate union,
  so changing either a base class, any versioned variant, or its active multi-release marker changes the
  pinned digest. Only entries in a
  container marked `Multi-Release: true` participate in JVM version selection, and the class-loader
  selected bytes must match the exact active candidate for the running JVM. Each effective
  class-loader resource must match one scanned class digest so split-package overrides fail closed.
  Protege Felix `bundle:` resources are resolved through the framework's trusted local-URL bridge and
  then subjected to the same regular-file, local-JAR, class-byte, and container-size checks; arbitrary
  URL handlers and remote protocols are never opened. `jar:file:` fields are parsed directly and read
  with `JarFile`, so a custom `jar:` handler is never invoked. File authorities and UNC paths are rejected
  before any filesystem probe. The
  scan allows at most 24 MiB of class bytes plus 24 MiB of effective-resource verification, 4 MiB per
  class, 64 MiB of nested-JAR bytes, a 128 MiB outer container, 150,000 entries, and five seconds. Any
  class, byte, entry, time, duplicate, override, or unsupported-location limit discards the entire scan
  as `unknown`; evidence is never truncated and accepted.
- `configuration_digest` records the complete bounded configuration; `semantic_configuration_digest`
  is the exact reviewed inference-semantics key. `configuration_binary_digest` separately attests the
  selected configuration class. ELK worker-count and evictor-builder values are captured in the complete
  digest but excluded from the semantic projection; unsupported-feature and incremental-mode settings
  remain semantic. Both official ELK buffering recommendations are exact reviewed tuples. For
  `configuration_profile: unrecognized`, both configuration digests
  are class-bound failure markers rather than claims of complete object identity, and no reviewed profile
  can match them.
- `capability_digest`: binds the complete vocabulary, statuses, evidence, incompatibilities, and identity.
- `profile_status`, `exact_profile_match`: whether the complete identity has a reviewed profile.
- `owl_capabilities`, `rule_capabilities`, `swrl_atom_capabilities`: complete closed capability rows with
  `supported`, `unsupported`, `unknown`, or `untested` status and evidence.
- `swrl_builtin_capabilities`: every predicate in the closed side-effect-free SWRLB allowlist, with
  exact-profile support evidence. A predicate absent from this allowlist is always unsupported, even if
  it uses the standard `swrlb:` namespace.
- `known_incompatibilities`: bounded reviewed caveats.
- `absence_means_supported`: always `false`; omitted capability evidence never implies support.

The report does not claim that an installed reasoner can execute a particular ontology or rule. Use
`validate_rules` for the rules currently in the project and `run_reasoner` to classify.

---

## `validate_rules`

Validates the current SWRL rules against the exact selected reasoner profile without executing any rule or
creating a reasoner. It parses every body/head atom, argument, variable, and built-in in an accepted,
preflight-bounded corpus before producing a bounded page. Unknown built-ins, including
filesystem/network/plugin-defined operations, are always `unsupported`. The report deliberately separates
the necessary body-variable binding check from DL-safety: OWLAPI rules do not identify a non-DL predicate
partition, so `dl_safety_status` is exact reasoner-profile engine evidence, not a syntactic proof that a
rule is formally DL-safe.

*Read-only.* Also available through headless stdio. The live default includes the imports closure.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `include_imports` | boolean | no | `true` | Validate the active ontology's imports closure; false limits capture to the active ontology. |
| `offset` | integer | no | `0` | Zero-based deterministic rule offset, at most 2,000. |
| `limit` | integer | no | `10` | Rule reports to return, from 1 through 10. |
| `snapshot_fingerprint` | SHA-256 string | required when `offset > 0` | none | Fingerprint returned by the first page; later pages fail if the selected source set, full ontology/version IDs, rule corpus, or capability evidence changed. |

**Returns**

- `executed_rules`: always `false`; `parsed_every_atom`: always `true`.
- `compatible`: true only when every rule and required exact-profile capability is supported and every
  variable passes the separate non-built-in body binding check.
- `coverage_complete`: false if any required capability remains `unknown` or `untested`, even when another
  unsupported atom already determines the rule verdict.
- `snapshot_fingerprint`, `fingerprint_stability`, and warnings bind pagination and disclose when anonymous
  identifiers make the token session-only.
- exact rule totals by support state plus `offset`, `returned`, and optional `next_offset`.
- `incompatible_rule_summaries` identifies every incompatible rule in the accepted corpus, including
  rules outside the current ten-row detail page.
- each rule's digest, complete source count plus truncation, support state, engine DL-safety evidence,
  body-variable safety, complete atom/finding counts, bounded samples, and truncation flags.

Capture fails before returning a partial report above 128 source ontologies, 4,096 characters in any
ontology/version identifier, 2,000 rule occurrences or
unique rules, 512 combined body/head atoms in one rule, 256 rule annotations, 20,000 total atoms,
100,000 total arguments, 262,144 characters/4,096 nodes/128 levels in one canonical object,
2,000,000 canonical UTF-8 bytes, or 10 seconds. Rule occurrence count is checked before rule axioms are
copied and checked again afterward. In the live adapter both checks and the copy share one uninterrupted
model-thread hop; headless operates on its already isolated workspace snapshot. The live model-thread hop
walks direct imports under the 128-source cap instead of first materializing an unbounded closure, then
captures the selected plugin recipe, bounded source identities, and immutable
OWLAPI rule axioms; runtime-code/configuration identity, canonicalization, and parsing run afterward, and
the resulting corpus is a detached DTO with no OWLAPI fields. A page contains at most 10 rules, 32 atom samples per rule, and 8
argument/variable samples per atom so headless output remains inside its 8 MiB transport ceiling.

Budget failures use `rule_validation_budget_exceeded`; a missing continuation token uses
`rule_validation_snapshot_required`; a changed token uses retryable `rule_validation_snapshot_changed`.

HermiT `1.3.8.431` supports the reviewed DL-safe rule atom subset but not SWRL built-ins. OWLAPI structural
and ELK `0.5.0` do not execute SWRL. An unknown reasoner remains unknown only for a predicate on the closed
pure SWRLB allowlist; every other predicate fails closed as unsupported.

Dependency or plugin updates deliberately change the attested tuple to `unknown`. Restoring `reviewed`
requires rerunning the real reasoner fixtures and packaged smoke, reviewing every changed runtime scope and
configuration class, then updating the pinned digest/count/version tuple together; a version-only bump is
never sufficient.

---

## `list_reasoners`

Lists every reasoner factory installed in Protégé and marks the one currently selected. Factories remain separate even when they share a display name, so their ids can still be discovered and used to disambiguate `set_reasoner` before classifying with `run_reasoner`.

*Read-only.*

**Arguments**

None.

**Returns**

- `count`: integer — number of installed reasoner plugins.
- `reasoners`: array of rows sorted by name, each `{name, id, current}` where `current` is a boolean flagging the selected reasoner.
- `current_id`: string — the currently selected reasoner factory id; present only when non-null.

If no reasoner plugins are installed, the tool returns an error object `{error}`.

---

## `set_reasoner`

Selects the reasoner Protégé will use, resolving the given reference against the installed reasoners from `list_reasoners`. A full display name (e.g. `HermiT 1.4.3.456`) or factory id matches exactly (case-insensitive) and pins that version; a version-less name — the recommended convention, e.g. `HermiT` — matches when its whitespace tokens appear as a contiguous whole-token, case-insensitive run inside an installed display name, and is accepted only when exactly ONE installed reasoner matches. Partial versions (`HermiT 1.4`) never match. The same resolution rule governs the project policy's `reasoning.reasoner` (including `run_project_qc`'s required-reasoner comparison) and `semantic_diff`'s `reasoner` argument. This only selects; it does not classify — call `run_reasoner` afterwards.

*Mutating (undoable)* — routed through the write path, so it honours the read-only / confirm-each-write preference gates.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `reasoner` | string | yes | — | Reasoner reference (see `list_reasoners`): a factory id or full display name matches exactly (case-insensitive) and pins the version; a version-less name resolves as a whole-token run and must match exactly one installed reasoner. |

**Returns**

- `selected`: object `{name, id}` — the reasoner that was selected.
- `message`: string — a reminder to call `run_reasoner` to classify.

If no installed reasoner matches `reasoner`, the tool returns an error object `{error}` listing the available reasoners; a reference that matches more than one installed reasoner is likewise an error naming every candidate as `name [factory id]`. Use a full display name when that distinguishes the installs, or the factory id when display names collide.

**Example**

```json
{ "reasoner": "HermiT" }
```

---

## `run_reasoner`

Runs the reasoner selected in Protégé (classification) and blocks off-EDT until it signals completion or the timeout elapses. Reach for it after `set_reasoner` (or after choosing a reasoner from the Reasoner menu) and before any tool that reads inferences. Reports the resulting reasoner status and, when available, the unsatisfiable-class count — and **warns** when the ontology has SWRL rules the selected reasoner silently ignores (ELK).

*Read-only.* If no reasoner is selected, it returns an error object rather than classifying.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `timeout_ms` | integer | no | `60000` | Max wait in ms. |

**Returns**

On a run that could be started:

- `started`: boolean — `true`.
- `completed`: boolean — whether classification signalled completion within the timeout.
- `reasoner`: string — the current reasoner name.
- `status`: string — the reasoner status.
- `inconsistent`: boolean — whether the ontology is inconsistent.
- `unsatisfiable_count`: integer — number of unsatisfiable classes; present only when the reasoner produced results and could answer.
- `message`: string — a human-readable summary (timeout note, reasoner, status, inconsistency/unsat details).
- `warning`: string — present only when the ontology (with imports) contains SWRL rules and the selected reasoner is **ELK**, which does not support SWRL and silently IGNORES rules: the results include no rule-derived inferences (use a rule-aware reasoner such as Pellet — or HermiT for rules without built-in atoms — when the rules matter).

If classification could not be started (no reasoner selected, or one already running) the result is the shorter `{started: false, message}`. If no reasoner is selected up front, an error object `{error}` is returned instead.

**Example**

```json
{ "timeout_ms": 120000 }
```

---

## `get_unsatisfiable_classes`

Lists the unsatisfiable classes (those the reasoner has found equivalent to `owl:Nothing`), excluding `owl:Nothing` itself. Use it after `run_reasoner` to see which class definitions are contradictory. If the whole ontology is INCONSISTENT, use `explain_inconsistency` instead.

*Read-only.* Requires a reasoner that has produced results.

**Arguments**

None.

**Returns**

- `count`: integer — number of unsatisfiable classes.
- `items`: array of entities, each `{iri, display, type}`, sorted by display name.
- `truncated`: integer — number omitted; present only if the list was capped (this tool passes no limit, so effectively absent).
- `coherent`: boolean — `true` when there are no unsatisfiable classes.

If no reasoner is selected, or it has not produced results yet, the tool returns an error object `{error}`. Over an **inconsistent** ontology it returns a pointed error directing to `explain_inconsistency` (an inconsistent ontology entails everything, and reasoners refuse such queries).

---

## `get_inferred_superclasses`

Reads an inferred relation for a class or individual from the reasoner: superclasses, subclasses, equivalent classes, the types of an individual, or the instances of a class. Reach for it to walk the inferred hierarchy after classification.

*Read-only.* Requires a reasoner that has produced results.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `entity` | string | yes | — | Class IRI/name (or individual for `types`). |
| `relation` | string | no | `superclasses` | One of `superclasses` \| `subclasses` \| `equivalent` \| `types` \| `instances`. |
| `direct` | boolean | no | `true` | Direct relations only. |

**Returns**

- `count`: integer — number of entities in the result.
- `items`: array of entities, each `{iri, display, type}`, sorted by display name.
- `truncated`: integer — number omitted; present only if capped.
- `relation`: string — the (lower-cased) relation that was computed.
- `entity`: string — the entity reference that was queried (echoed as given).
- `direct`: boolean — whether the query was limited to direct relations.

If no reasoner is selected, or it has not produced results yet, the tool returns an error object `{error}`. Over an **inconsistent** ontology it returns a pointed error directing to `explain_inconsistency`.

**Example**

```json
{ "entity": "Dog", "relation": "superclasses", "direct": true }
```

---

## `execute_dl_query`

Runs a Protégé DL Query: given a Manchester-syntax class expression, returns the reasoner's equivalent classes, subclasses, superclasses, and instances. Reach for it to ask ad-hoc "which classes/individuals satisfy this expression?" questions without adding an axiom. Call `run_reasoner` first.

*Read-only.* Requires a reasoner that has produced results.

> **Complex-expression caveat.** For a **complex (anonymous)** class expression with `direct=false`, some reasoners — notably **ELK** — return an *incomplete* set of sub/superclasses, omitting the **direct** level (Protégé's own DL Query tab shows the same). When that combination is detected under ELK the response carries a `warning`. Set `complete=true` to reconstruct the exhaustive set (the reasoner's direct results unioned with each direct class's transitive descent, which every reasoner computes reliably for named classes), or re-run with `direct=true`, or classify with a DL reasoner such as HermiT.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `query` | string | yes | — | Manchester-syntax class expression, e.g. `"hasOwner some Person"` or `"Animal and (hasOwner some Person)"`. |
| `relation` | string | no | `all` | Limits the result: `equivalent` \| `subclasses` \| `superclasses` \| `instances` \| `all`. |
| `direct` | boolean | no | `true` | Direct results only for sub/super/instances. |
| `complete` | boolean | no | `false` | For a complex (anonymous) expression with `direct=false`, reconstruct the exhaustive sub/superclass set (direct + named-class descent) instead of the raw reasoner call — working around reasoners (e.g. ELK) that under-report complex-expression queries. |
| `timeout_ms` | integer | no | `60000` | Max wait in ms for the query. |

**Returns**

- `query`: string — the query expression, echoed.
- `direct`: boolean — whether direct-only was requested.
- `equivalent`: entity list `{count, items:[{iri, display, type}...], truncated?}`; present when `relation` is `all` or `equivalent`.
- `superclasses`: entity list; present when `relation` is `all` or `superclasses`.
- `subclasses`: entity list; present when `relation` is `all` or `subclasses`.
- `instances`: entity list; present when `relation` is `all` or `instances`.
- `warning`: string — present when the ELK complex-expression / `direct=false` incompleteness is detected and `complete` was not set.
- `completed`: boolean, `note`: string — present when `complete=true` reconstructed the exhaustive set (which goes beyond a single raw reasoner call and beyond what Protégé's DL Query tab shows for that reasoner).

If no reasoner is selected, or it has not produced results yet, the tool returns an error object `{error}`. Over an **inconsistent** ontology it returns a pointed error directing to `explain_inconsistency` (an inconsistent ontology entails everything, and reasoners refuse such queries).

**Example**

```json
{ "query": "Animal and (hasOwner some Person)", "relation": "instances", "direct": false }
```

---

## `explain_entailment`

Checks whether a structured axiom is entailed by the active reasoner and returns a plain `true`/`false`. Reach for it to confirm a specific inference; use `get_explanations` to see the justifications behind it. The axiom is built from the same structured `axiom_type` + operand arguments as `add_axiom`.

*Read-only.* Requires a reasoner that has produced results.

**Arguments**

The full axiom schema (same operands as `add_axiom`). `axiom_type` is required; the remaining operands depend on which type you choose. Class operands accept names, IRIs, or Manchester-syntax class expressions.

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `axiom_type` | string | yes | — | One of the supported axiom types (e.g. `subclass_of`, `class_assertion`, `object_property_assertion`, …). |
| `sub` | string | no | — | `subclass_of`: subclass — name, IRI or Manchester class expression. |
| `super` | string | no | — | `subclass_of`: superclass — name, IRI or Manchester class expression. |
| `classes` | string[] | no | — | `equivalent_classes` / `disjoint_classes` / `disjoint_union`: classes. |
| `class` | string | no | — | `class_assertion` / `disjoint_union` / `has_key`: class. |
| `individual` | string | no | — | `class_assertion`: individual IRI/name. |
| `individuals` | string[] | no | — | `same_individual` / `different_individuals`: individual IRI/name list. |
| `property` | string | no | — | `*_property_assertion` / property characteristic / `sub_*_property_of` / `*_property_domain`\|`range` / `annotation_*` / `has_key` seed: property IRI/name. |
| `properties` | string[] | no | — | `equivalent_`/`disjoint_object`\|`data_properties`: property list; `has_key`: object/data property list. |
| `super_property` | string | no | — | `sub_object_property_of` / `sub_data_property_of` / `sub_property_chain_of` / `sub_annotation_property_of`: super property IRI/name. |
| `chain` | string[] | no | — | `sub_property_chain_of`: ordered object property IRI/name list. |
| `inverse_property` | string | no | — | `inverse_object_properties`: inverse object property IRI/name. |
| `subject` | string | no | — | `*_property_assertion` / `annotation_assertion`: subject IRI/name. |
| `object` | string | no | — | `object_property_assertion` / `negative_object_property_assertion`: object individual IRI/name. |
| `value` | string | no | — | `data_property_assertion` / `annotation_assertion`: literal value. |
| `value_iri` | string | no | — | `annotation_assertion`: IRI-valued annotation (alternative to `value`). |
| `lang` | string | no | — | `data_property_assertion` / `annotation_assertion`: optional language tag. |
| `datatype` | string | no | — | `data_property_assertion` / `annotation_assertion`: optional datatype IRI/name; `datatype_definition`: defined datatype. |
| `entity` | string | no | — | `declaration`: entity IRI/name. |
| `entity_type` | string | no | — | `declaration`: `class` \| `object_property` \| `data_property` \| `annotation_property` \| `individual` \| `datatype`. |
| `domain` | string | no | — | `object_property_domain` / `data_property_domain`: domain class expression; `annotation_property_domain`: domain IRI. |
| `range` | string | no | — | `object_property_range`: range class expression; `data_property_range` / `datatype_definition`: datatype IRI/name or Manchester data range; `annotation_property_range`: range IRI. |
| `annotations` | array | no | — | Optional axiom annotations (array of `{property, value \| value_iri, lang, datatype}`). |

**Returns**

- `entailed`: boolean — whether the reasoner entails the axiom.
- `axiom`: object `{axiom_type, rendering}` — the axiom that was checked.

If no reasoner is selected, or it has not produced results yet, the tool returns an error object `{error}`. Over an **inconsistent** ontology it returns a pointed error directing to `explain_inconsistency` (an inconsistent ontology entails everything, and reasoners refuse such queries).

**Example**

```json
{ "axiom_type": "subclass_of", "sub": "Dog", "super": "Animal" }
```

---

## `get_explanations`

Explains WHY a structured axiom is entailed by returning one or more justifications — minimal sets of asserted axioms that together entail it. Minimal justifications are computed for the class/individual/property-assertion/domain-range axiom types listed below; for any other `axiom_type` (e.g. a property-hierarchy or property-characteristic entailment) it falls back to confirming entailment and returning asserted axioms that mention the same entities as structural context (not a minimal justification). To explain why a class `C` is unsatisfiable, use `axiom_type=class_assertion` with `class=C` and any individual, or `subclass_of` with `sub=C`, `super="owl:Nothing"`. The multi-justification search runs over a private copy of the active ontology's imports closure, so it never touches Protégé's undo stack or live model. Requires a reasoner (see `list_reasoners` / `set_reasoner`).

*Read-only.* Requires a selected reasoner.

Axiom types with minimal justifications: `subclass_of`, `equivalent_classes`, `disjoint_classes`, `class_assertion`, `object_property_assertion`, `data_property_assertion`, `negative_object_property_assertion`, `negative_data_property_assertion`, `same_individual`, `different_individuals`, `object_property_domain`, `object_property_range`, `data_property_domain`, `data_property_range`.

**Arguments**

The full axiom schema (same operands as `explain_entailment` / `add_axiom` — see that table), plus the two explanation-search knobs below:

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `axiom_type` | string | yes | — | The axiom type to explain (plus the type-specific operands from the axiom schema). |
| `max` | integer | no | `3` | Maximum number of justifications to compute (`0` = all). Used only for the justification path. |
| `timeout_ms` | integer | no | `60000` | Max wait in ms for the explanation search. |

**Returns**

For an explainable `axiom_type` (justification path):

- `axiom`: object `{axiom_type, rendering}` — the axiom being explained.
- `entailed`: boolean — `true` when at least one justification was found.
- `justification_count`: integer — number of justifications returned.
- `justifications`: array of rows, each `{size, axioms}` where `axioms` is an array of `{axiom_type, rendering}` sorted by rendering.

For any other `axiom_type` (structural-context fallback):

- `axiom`: object `{axiom_type, rendering}`.
- `entailed`: boolean — whether the reasoner entails the axiom.
- `justification_available`: boolean — always `false` on this path.
- `note`: string — explains that no minimal justification is available for this axiom type.
- `related_axioms`: axiom list `{count, items:[{axiom_type, rendering}...], truncated?}` — asserted logical axioms in the imports closure mentioning the same entities (a structural neighbourhood, not a minimal justification); present only when the axiom is entailed.

The justification path also returns `reasoner_configuration`: selected reasoner/factory/configuration class,
requested timeout/fresh-entity/individual-node policies, selected and actual buffering modes, and parity fields.
OWLAPI's explanation algorithm requires non-buffering while it removes/restores private axioms; when the
plugin recommends buffering this deliberate override appears as `buffering_caveat`. The exact plugin
configuration object is still passed. Timeout interrupts the explanation engine's hidden private reasoners;
no late result is accepted.

If no reasoner is selected, the tool returns an error object `{error}`. Over an **inconsistent** ontology it returns a pointed error directing to `explain_inconsistency` (an inconsistent ontology entails everything, and reasoners refuse such queries).

**Example**

```json
{ "axiom_type": "subclass_of", "sub": "Dog", "super": "Animal", "max": 3 }
```

Explaining an unsatisfiable class `C`:

```json
{ "axiom_type": "subclass_of", "sub": "C", "super": "owl:Nothing" }
```

---

## `explain_inconsistency`

Explains WHY the ontology is INCONSISTENT: finds a set of asserted logical axioms that together cause the contradiction. The result's `minimal` flag reports whether the set was fully minimized within the time budget (`true` means removing any one of them breaks *this* contradiction; `false` means still jointly inconsistent but reduced-not-minimal). The contraction search runs the selected reasoner over a **private copy** of the active ontology's imports closure, off the UI thread, so the live reasoner state, Protégé's undo stack, and the GUI stay untouched. If the ontology is consistent it says so. Use it after `run_reasoner` reports INCONSISTENT — the other explanation/query tools cannot run over an inconsistent ontology (they return a pointed error directing here).

*Read-only.* Requires a reasoner selected in Protégé. Time-bounded: on expiry the current still-inconsistent axiom set is returned with `minimal=false`.

**Arguments**

| Name | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `timeout_ms` | integer | no | `60000` | Time budget in ms for the whole search. On expiry the current still-inconsistent axiom set is returned with `minimal=false`. |

**Returns**

When the ontology is inconsistent:

- `inconsistent`: boolean — `true`.
- `reasoner`: string — the reasoner used for the consistency checks.
- `minimal`: boolean — `true` when the set is genuinely minimal; `false` when the time budget expired first (the listed axioms are still jointly inconsistent but not necessarily all needed).
- `consistency_checks`: integer — how many consistency probes the search ran.
- `axiom_count`: integer — the true size of the jointly inconsistent set (can exceed the rendered `justification` when the budget expired before minimization).
- `justification`: axiom list `{count, items:[{axiom_type, rendering}...], truncated?}` — the jointly inconsistent asserted logical axioms, rendered up to a cap of 100.
- `note`: string — how to read the set. When minimal: removing any one axiom breaks THIS contradiction (others may remain — fix and re-run), and a reasoner that ignores axioms it does not support (e.g. ELK) minimizes only what it sees. On a timeout: re-run with a larger `timeout_ms`, or `extract_module` around the suspect terms and diagnose the smaller module.
- `reasoner_configuration`: the captured plugin configuration/buffering metadata used for every private probe.

When the ontology is consistent: `{inconsistent: false, reasoner, note}` — there is no inconsistency to explain.

If no reasoner is selected, the tool returns an error object `{error}`. If the selected reasoner cannot evaluate the ontology at all (e.g. HermiT rejecting a SWRL built-in atom), the tool returns an error naming the reasoner exception rather than misreporting the ontology as consistent — choose another reasoner via `set_reasoner` and re-run.

**Example**

```json
{ "timeout_ms": 120000 }
```
