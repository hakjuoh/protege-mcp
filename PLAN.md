# Protégé MCP Ontology Engineering Roadmap

> Status: proposal for post-`0.5.1` development
>
> Scope: ontology development, quality assurance, release automation, and governed operation
>
> Version labels in this document are tentative milestones, not release commitments.

## 1. Purpose

Protégé MCP already provides a broad interactive surface over the ontology currently open in Protégé:
structured OWL authoring, curation macros, reasoning, SPARQL, SHACL, competency questions, invariants,
module extraction, document management, and safe write controls. The next development stage is not mainly
about adding more isolated edit tools. It is about turning those capabilities into a reproducible ontology
engineering lifecycle:

1. A project records its modelling and governance policy as version-controlled data.
2. Every proposed change is evaluated against that policy before it reaches the live ontology.
3. Imports and serialized artifacts are reproducible.
4. Diffs describe semantic and curation impact, not just changed axiom strings.
5. The same checks run in Protégé, headlessly, and in CI.
6. Reviews, approvals, provenance, and releases leave an auditable trail.

This plan defines that target, the implementation sequence, compatibility constraints, acceptance criteria,
and the principal risks.

## 2. Baseline and problem statement

### 2.1 Capabilities to preserve

The `0.5.0` baseline has the following strengths and they are treated as compatibility requirements:

- Live access to the active Protégé ontology through `OWLModelManager`.
- GUI-visible, undoable axiom edits.
- Structured OWL axiom operands and Manchester class expressions.
- High-level curation operations such as term/property creation, moves, deprecation, rename, and deletion.
- Read-only mode, confirmation for every write, strict entity grounding, previews, and structured JSON results.
- Installed-reasoner discovery, classification, inferred queries, explanations, and inconsistency diagnosis.
- SPARQL query/schema/validation, SHACL validation, competency questions, invariants, and aggregate QC.
- Multi-ontology workspaces, imports, catalogs, document merge/load/save, and locality-module extraction.
- A local authenticated MCP endpoint, shared broker, and in-Protégé Ontology Assistant.

Existing tool names, argument meanings, and result fields remain stable unless a security or correctness defect
requires a breaking change. New fields should normally be additive.

### 2.2 Gaps this roadmap addresses

The current implementation is strong for interactive, single-user ontology development but incomplete as an
ontology operations platform:

- Governance rules, invariant sets, SHACL inputs, and QC requirements are not yet unified in a persistent,
  version-controlled project policy.
- `apply_changes verify=...` detects new inconsistency and unsatisfiable classes, but not governance, profile,
  CQ, SHACL, invariant, serialization, import, or lost-entailment regressions.
- Rollback uses the shared live undo history and can degrade to report-only semantics when a GUI edit
  interleaves with classification.
- `diff_ontologies` is an axiom-set diff; it does not classify rename candidates, inferred hierarchy changes,
  lifecycle changes, or compatibility impact.
- Missing imports can be tolerated and merely reported, which is convenient interactively but unsafe for a
  release gate.
- There is no import lock, artifact checksum manifest, atomic verified save, or first-class release bundle.
- Architecture Approach C, the headless/CI runner, is designed but not implemented.
- Search is primarily grounded in renderer/local name/`rdfs:label`; synonym-aware reuse and mapping workflows
  are limited.
- Authentication is appropriate for a single local user, but OAuth scopes are not yet enforced as per-tool
  authorization roles and there is no persistent change audit or approval workflow.
- Long-running classification, validation, extraction, and diff work has no common job/progress/cancellation
  model.
- Live Protégé/OSGi/reasoner/GUI integration remains substantially dependent on manual smoke testing.

## 3. Product principles

### 3.1 Semantics before automation

- OWL remains the source of logical meaning.
- SHACL and selected competency questions express closed-world data or project requirements.
- A property domain/range is never treated as a form-field constraint. It is an OWL inference-producing axiom.
- SKOS mapping relations are not silently promoted to OWL equivalence.
- An automated fix must identify which formalism and policy authorizes it.

### 3.2 Preflight before mutation

The preferred safe-authoring path is:

1. Capture a point-in-time ontology/project revision.
2. Resolve and normalize a proposed change set.
3. Apply it to an isolated snapshot.
4. Run the required gates on that snapshot.
5. Re-check that the live revision has not changed.
6. Commit the exact normalized changes to the live model as one undo unit.

Applying first and undoing after a failed gate is retained only as a compatibility fallback when a required
validator cannot run against an isolated snapshot.

### 3.3 Reproducibility over hidden state

- Release-critical policy lives beside the ontology and is committed to version control.
- The selected reasoner, import documents, checksums, validation assets, and serialization format are explicit.
- A release never passes because a required check did not run; that condition is an explicit `error`.
- A result records the policy version and ontology revision it evaluated.

### 3.4 One core, multiple surfaces

Protégé MCP, a future headless MCP server, a CLI, and CI must call the same ontology-engineering core and emit
the same result contracts. The GUI adapter owns EDT marshalling and undo; the headless adapter owns filesystem
transactions. Validation semantics must not fork by surface.

### 3.5 Local desktop security remains the default

The plugin continues to default to loopback, authenticated, single-user operation. Team workflows add audit,
roles, and approvals without turning the desktop plugin into a general multi-tenant ontology server. Remote
multi-user hosting, if pursued, should be a separate deployment profile with TLS and explicit threat modelling.

### 3.6 Non-goals

- Replacing Protégé's complete ontology-editing UI or plugin ecosystem.
- Becoming a general-purpose RDF database, SPARQL endpoint, or collaborative triple-store server.
- Automatically accepting LLM-authored axioms because a syntactic check passed.
- Treating SHACL conformance as a substitute for OWL consistency, or vice versa.
- Guaranteeing that every third-party reasoner supports every OWL/SWRL construct.
- Providing unrestricted remote multi-tenant access from the desktop plugin.
- Hiding source-ontology licenses, provenance, or mapping uncertainty during term reuse.

## 4. Target architecture

The target separates ontology semantics from delivery surfaces:

```text
MCP tools / guided prompts / Protégé UI / CLI / CI
                         |
                  application services
       policy | change sets | QC | diff | release | jobs
                         |
               ontology engineering core
      OWLAPI | reasoners | Jena | SHACL | CQ | mappings
                    /                 \
       Protégé workspace adapter    headless workspace adapter
       EDT + undo + renderer        files + atomic replace + stdio
```

Proposed package/module boundaries:

- `core-model`: policy, revisions, normalized changes, findings, reports, release manifests.
- `core-owl`: OWLAPI-only loading, changes, validation, diff, import, mapping, and serialization services.
- `protege-adapter`: `OWLModelManager`, renderer/entity finder, reasoner manager, EDT, undo, GUI confirmation.
- `headless-adapter`: OWLAPI manager, filesystem transaction, catalog/import resolution, reasoner factories.
- `mcp-plugin`: current MCP server/broker/chat/UI integration.
- `cli`: validation/diff/release commands and optional stdio MCP server.

The repository does not need to become multi-module immediately. Interfaces and pure services should be
extracted first; Maven modules should be introduced only when dependency boundaries are enforceable.

## 5. Milestone overview

| Milestone | Priority | Outcome | Depends on |
| --- | --- | --- | --- |
| M0. Contracts and foundations | P0 | Stable policy/revision/finding contracts and compatibility rules | — |
| M1. Project policy and reproducible QC | P0 | Version-controlled policy and strict project/release gate | M0 |
| M2. Preflight change sets | P0 | Full-QC validation before one live commit | M0, M1 |
| M3. Import and serialization hardening | P0 | Locked imports and verified atomic artifacts | M0, M1 |
| M4. Semantic diff and release workflow | P0 | Impact-aware diffs and reproducible release bundles | M1, M3 |
| M5. Headless runner and CI | P0 | Same validation/release behavior without Protégé | M0–M4 |
| M6. Reuse, mappings, and lifecycle | P1 | Synonym-aware reuse, SSSOM, governed term lifecycle | M1, M2 |
| M7. Audit, approvals, and authorization | P1 | Traceable changes and enforced client capabilities | M2, M4 |
| M8. Rules, async jobs, performance, integration | P1 | Predictable long-running work and stronger live-runtime confidence | cross-cutting |

Milestones may overlap after their data contracts stabilize. M0–M5 form the minimum path from an interactive
assistant to an ontology DevOps workflow.

## 6. M0 — Contracts and foundations

### 6.1 Objectives

- Define shared, versioned representations before adding new orchestration.
- Prevent the plugin and future CLI from developing different semantics.
- Make every validation result machine-composable and release-reportable.

### 6.2 Core contracts

#### Project coordinates

```json
{
  "project_id": "example-product-ontology",
  "policy_version": 1,
  "root_ontology": "https://example.org/ontology",
  "modules": []
}
```

#### Model revision

Every revision is a required envelope with three independent coordinates:

```json
{
  "workspace_id": "8a15d9e8-...",
  "session_revision": 42,
  "semantic_fingerprint": "sha256:...",
  "document_fingerprint": "sha256:..."
}
```

- `workspace_id`: a random UUID minted by one per-window backend. It prevents tokens created in one Protégé
  window or instance from being replayed in another backend reached through the shared broker.
- `session_revision`: a monotonic counter for fast optimistic concurrency within that workspace. It changes on
  every observable workspace event relevant to a preview: ontology edits, target/import changes, load/reload,
  reasoner selection/classification, and policy reload.
- `semantic_fingerprint`: ontology IDs, imports, ontology annotations, and canonical axioms including axiom
  annotations. It excludes prefixes and document serialization.
- `document_fingerprint`: the semantic fingerprint plus the live prefix map, format, document IRI, and import
  lock digest.

The fingerprint contract must state whether and how it includes:

- Active ontology ID and version IRI.
- Direct import declarations.
- Ontology annotations.
- Axioms, including axiom annotations.
- Prefix declarations in the document fingerprint.
- Imported content or only import coordinates.

Canonicalization must be tested across insertion order and save/reload. Java object iteration order or renderer
strings must not affect a fingerprint. Anonymous individuals/blank nodes are a first-class part of this
contract: the canonicalization ADR must either define deterministic graph-isomorphism-safe relabelling or mark
the cross-restart fingerprint as unavailable/weaker when it cannot do so. Raw OWLAPI `NodeID` values must never
be presented as stable. A strict release gate errors rather than claiming a stable manifest fingerprint when an
ontology falls outside the supported canonicalization guarantees.

`commit_change_set.expected_revision` is the complete envelope returned by preview. Commit requires the same
`workspace_id`, `session_revision`, `semantic_fingerprint`, and `document_fingerprint`. A prefix-only change
therefore changes the document fingerprint and deliberately conflicts, even when the semantic fingerprint is
unchanged. This conservative rule ensures that committed results and release artifacts match what was reviewed.

#### Finding

Every validator should be convertible to a common finding shape:

```json
{
  "id": "missing-definition",
  "source": "structural",
  "severity": "warning",
  "message": "Class has no project definition annotation.",
  "focus_iri": "https://example.org/Widget",
  "axiom": null,
  "path": null,
  "rule_id": "annotation.definition.required",
  "waiver": null
}
```

The common shape must not erase validator-specific detail. Original payloads remain available under `details`.

#### Gate result

```json
{
  "gate": "fail",
  "policy_version": 1,
  "semantic_fingerprint": "sha256:...",
  "required_stages": ["reasoner", "profile", "governance"],
  "stages_ran": 3,
  "stages_skipped": 0,
  "findings": [
    {
      "id": "missing-definition",
      "source": "governance",
      "severity": "warning",
      "focus_iri": "https://example.org/Widget"
    }
  ],
  "artifacts": []
}
```

The overall `gate` values are `pass`, `fail`, and `error`; individual stage status additionally permits
`skipped`:

- `pass`: every required stage completed and no finding from a required stage reached its failure
  threshold; optional-stage findings remain reportable but do not control the gate.
- `fail`: a check ran and found a policy violation.
- `error`: a required check could not complete or its input was invalid.
- `skipped`: policy explicitly made the check optional or not applicable.

The current `run_qc_suite` may report `ran:false` when backing data is absent. In policy/project mode that legacy
result maps to `error` when the stage is required and to `skipped` only when the policy marks it optional.

### 6.3 Cross-cutting APIs

Introduce internal interfaces before new public tools:

- `OntologyWorkspace`: ontology access, revision, snapshot, target selection, and change application.
- `EntityNaming`: IRI/CURIE/display resolution and deterministic rendering.
- `ReasoningService`: capability discovery, classification, consistency, entailment, and explanations.
- `ValidationStage`: id, prerequisites, input assets, execution, findings, and severity mapping.
- `ArtifactStore`: project-relative reads/writes with atomic replacement and checksum support.
- `Clock` and `IdGenerator` seams for deterministic tests and audit records.

### 6.4 Acceptance criteria

- The current 66 tool registrations and existing required arguments remain unchanged.
- Existing JSON result fields remain present; common finding/gate fields are additive.
- Fingerprints are independent of collection ordering and stable across two load/save cycles for every ontology
  covered by the canonicalization contract; unsupported anonymous-individual cases are explicitly degraded or
  rejected and never reported as stable.
- Policy and release schemas carry explicit integer versions.
- Unknown future schema fields are either preserved or rejected according to a documented rule; they are never
  silently reinterpreted.
- Unit tests cover canonicalization, schema migration, status aggregation, and failure semantics.

## 7. M1 — Project policy and reproducible QC

### 7.1 Project policy file

The version 1 authoring format is YAML, validated after parsing against the versioned JSON Schema. API/tool
results use the equivalent JSON object. Add a version-controlled policy, discovered in this order:

1. Explicit `policy_path` argument.
2. `.protege-mcp/project.yaml` beside the root ontology or in a parent project root.
3. No policy: retain current interactive defaults and clearly report `policy_loaded=false`.

The project root defaults to the directory containing the loaded policy. An explicit `project_root` is resolved
relative to that directory and may name only that directory or a descendant unless a local-admin override is
used. Suggested version 1 shape:

```yaml
version: 1
project_id: example-product-ontology
root_ontology: https://example.org/ontology
project_root: .

filesystem:
  allow_external_paths: false

modules:
  - ontology_iri: https://example.org/ontology/core
    path: modules/core.ttl
    owned_namespaces:
      - https://example.org/ontology/

reasoning:
  reasoner: HermiT
  owl_profile: DL
  required: true
  timeout_ms: 120000

annotations:
  labels:
    properties: [rdfs:label]
    required_languages: [en]
    one_preferred_per_language: true
  definitions:
    properties: [skos:definition]
    required: true
  required:
    - dcterms:source
    - ex:curationStatus

iri_policy:
  required_namespaces:
    - https://example.org/ontology/
  pattern: '^https://example.org/ontology/[A-Z][A-Za-z0-9_]+$'

imports:
  mode: locked
  fail_on_missing: true
  lockfile: imports.lock.json

validation:
  required_stages:
    - reasoner
    - profile
    - governance
    - structural
    - invariants
    - cqs
    - shacl
  fail_on: warning
  structural:
    disabled: [property_missing_domain, property_missing_range]
    severity_overrides: {}
  invariants:
    paths: [quality/invariants/*.rq]
  shacl:
    paths: [quality/shapes.ttl]
  competency_questions:
    convention: robot-sparql-dir
    path: cqs/

release:
  format: turtle
  output_dir: dist/
  require_version_iri: true
  require_clean_round_trip: true
```

### 7.2 New or extended tools

- `get_project_policy`
  - Resolve and return the effective policy, discovery path, schema version, and warnings.
- `validate_project_policy`
  - Validate syntax, references, paths, prefixes, stage names, reasoner selection, and contradictory settings.
- `run_project_qc`
  - Execute the effective policy and return a strict gate result.
- Extend `run_qc_suite`
  - Add a `governance` stage.
  - Add `policy_path` and `required_stages` support.
  - Add `error_on_missing_required=true` for project/release mode. A legacy `ran:false` result for a required
    stage maps to `error`, not `skipped` or `fail`.
  - Keep current no-policy defaults backward compatible.

Policy writing should initially be file-oriented rather than a large `set_project_policy` mutation tool. A
`write_project_policy_template` tool may generate a commented starter file, but users should review and commit
it like source code.

### 7.3 Validation improvements

- Make every fixed structural check configurable by enabled state and severity.
- Keep domain/range absence informational by default; never auto-fix it without explicit policy.
- Add policy-driven checks for:
  - Preferred/alternate label properties and language coverage.
  - Empty/placeholder definitions.
  - Definition language and datatype.
  - Required provenance/source annotations.
  - Lifecycle status values and legal transitions.
  - Dangling `replaced_by` links and deprecated terms without migration guidance.
  - Multiple owned modules declaring the same IRI.
  - Import cycles and version conflicts.
- Add waiver support with rule id, focus IRI, reason, owner, and optional expiry.
- An expired waiver becomes a finding; an active waiver remains visible in the report.

### 7.4 Asset storage

- Keep CQs compatible with current `robot-sparql-dir`, sidecar manifest, and ontology annotations.
- Add a persisted invariant directory convention with ROBOT-compatible `.rq` files and metadata comments.
- Support one or more SHACL files referenced from policy, extending the existing per-call single-file
  `shapes_path`/`shacl_shapes_path` arguments.
- Resolve all relative paths against the policy file, never the process working directory.
- Reject traversal outside the project root unless `filesystem.allow_external_paths=true`, the authenticated
  principal has `filesystem:external`, and any required local write confirmation succeeds.

The same filesystem policy applies to direct existing tool arguments, not only policy assets. This includes
`save_ontology.path`, `shacl_validate.shapes_path`, module export, catalogs, ontology create/load/merge sources,
CQ/manifest sidecars, and future report paths:

- Project-root reads require `filesystem:project:read`; writes require `filesystem:project:write` plus the
  existing global read-only/confirm-write gate.
- Paths outside the project root require `filesystem:external` and the explicit policy opt-in above.
- URL/document fetches are governed separately by `network:access` and the import/network policy.
- For backward compatibility, a loopback static local-admin session with no project policy retains the current
  direct-path behavior, but the effective unrestricted mode is reported and can be disabled in preferences.
- A remote/read-only principal never gains arbitrary local-file access merely because a validator is read-only.

### 7.5 Acceptance criteria

- A checked-in policy produces the same gate result from two clean Protégé sessions.
- A required stage that cannot run makes `run_project_qc` return `error`; it is never reported as `fail`,
  `skipped`, or `pass`.
- The aggregate gate includes full governance, not only OWL profile checks.
- Invalid CURIEs, missing files, unknown stages, unavailable required reasoners, and malformed regexes are policy
  validation errors before ontology validation starts.
- Existing `run_qc_suite` calls without a policy retain their current behavior.
- Policy paths and validation assets work on macOS, Linux, and Windows path conventions.
- Direct tool path arguments obey the same project-root and capability policy as policy-referenced assets.
- Documentation includes a minimal policy, an OBO-oriented policy, and a general OWL project policy.

## 8. M2 — Preflight change sets

### 8.1 User-visible workflow

```text
get_model_revision
  -> preview_change_set
  -> review normalized changes + affected entities + policy gate
  -> commit_change_set(expected_revision, change_set_id)
  -> zero or one Protégé undo entry, reported explicitly
```

### 8.2 New tools

#### `get_model_revision`

Returns active ontology coordinates, the complete revision envelope (`workspace_id`, `session_revision`,
`semantic_fingerprint`, `document_fingerprint`), dirty state, reasoner state, and effective policy digest.

#### `preview_change_set`

Inputs:

- `operations`: the current `apply_changes` operation shape.
- Optional high-level operations normalized through the same curation cores.
- `policy_path`.
- `gates`: explicit override or policy defaults.
- `include_impact`: asserted, inferred, or both.

Returns:

- `change_set_id` and expiry.
- The complete `base_revision` envelope and policy digest.
- Canonical normalized add/remove changes.
- No-ops, errors, newly minted entities, and target modules.
- Affected entity/axiom counts.
- Preflight QC result.
- Semantic impact preview when requested.
- `committable` and reasons when false.

#### `commit_change_set`

Inputs:

- `change_set_id`.
- `expected_revision`: the complete base-revision envelope from preview; partial tokens are rejected.
- Optional `confirm_policy_digest`.

Behavior:

1. Resolve the cached preview and verify its `workspace_id`, expiry, policy digest, and completed gates.
2. Check the controller's global read-only setting; refuse before prompting when writes are disabled.
3. Run the configured write-confirmation UI outside the bounded EDT operation.
4. After confirmation, acquire the change-set commit mutex and, in the same EDT hop that will apply the change,
   re-check read-only state, policy digest, authorization context, and the complete live revision envelope. This
   final check closes the race in which a GUI edit, prefix/policy change, permission revocation, or mode change
   occurs while the confirmation dialog is open.
5. Refuse with `revision_conflict` if any workspace/session/semantic/document coordinate differs.
6. Apply the exact normalized change list once through one `OWLModelManager.applyChanges` broadcast.
7. Re-query the committed state, return the new revision, and compare `HistoryManager` log size before/after to
   report `undo_logged`. Undo grouping is a side effect of that single broadcast, not a separate API call;
   `ChangeListMinimizer` may reduce a no-op commit to zero logged entries.

#### `discard_change_set`

Explicitly releases a cached preview. Previews are memory-only, scoped to one per-window backend, size-bounded,
and expire after 15 minutes by default (configurable within a documented upper bound); expired previews are
swept automatically.

### 8.3 Isolated preflight

- Snapshot the active ontology and required imports at one revision.
- Apply normalized changes to a private OWLAPI manager.
- Run profile, structural, governance, invariants, CQ, SHACL, and serialization-safe checks there.
- Generalize the isolated reasoner-factory pattern already used by `get_explanations` and
  `explain_inconsistency`; preserve selected-reasoner configuration, buffering mode, fresh-entity policy, and
  reasoner-specific caveats where the Protégé API exposes them.
- Never copy private mutable reasoner state back into Protégé.
- Mark a validator as `live_only` only when isolation is technically impossible, with a documented fallback.

The preflight result must state whether every stage evaluated the same point-in-time snapshot.

### 8.4 Compatibility path

- Keep `preview_changes` and `apply_changes` for small/manual workflows.
- Internally migrate `apply_changes verify=report|rollback` onto change-set services when behavior can be
  preserved.
- Deprecate live apply-then-undo verification only after change sets cover the same reasoner cases.
- High-level batch tools (`create_terms`, `create_properties`) should expose `preview=true` or produce a
  change-set preview without duplicating validation logic.
- Revise the affected guided prompts (`add_subclass_safely`, `refactor_entity_safely`, `model_domain`,
  `audit_ontology`, and `release_readiness_check`) to prefer project QC and preview/commit change sets while
  retaining legacy-tool guidance for clients connected to older servers.
- Route Ontology Assistant write workflows through the same preview/commit path when change sets are available;
  it must not receive a private bypass around global read-only or confirmation settings.

### 8.5 Conflict behavior

- A revision mismatch never auto-merges.
- Return changed ontology IDs and revision tokens, not a vague retry error.
- Offer a read-only `rebase_change_set` only after deterministic re-resolution is implemented.
- If a referenced display name now resolves to another IRI, rebase must fail for human review.
- Ontology events, another MCP client, active-ontology switches, imports, and reasoner selection/classification
  update the observable session dimension. Protégé prefix editing fires no reliable model event, so every
  revision read and final commit precondition recomputes the prefix component of `document_fingerprint` from the
  live document format, following the existing `SparqlSnapshotCache` defensive pattern.
- Change-set ids and revision envelopes are scoped by `workspace_id` to one per-window backend. A token created
  in another window/instance, a broker session that loses its pinned window, or a restarted backend returns
  `unknown_change_set`/`revision_conflict`; a change set is never silently moved or re-pinned.

### 8.6 Acceptance criteria

- A failed preflight leaves the live ontology and Undo stack unchanged.
- Read-only mode refuses `commit_change_set`, and every commit honors the configured write-confirmation policy.
- An edit made while confirmation is open is detected by the post-confirmation revision check and applies nothing.
- A successful effective commit applies the previewed normalized delta through one `applyChanges` broadcast,
  produces at most one Undo entry, and reports whether an entry was actually logged; a minimized no-op reports
  `undo_logged=false`.
- A GUI edit between preview and commit yields `revision_conflict` and applies nothing.
- Two concurrent commits against the same revision allow at most one success.
- All policy-required gates evaluate the same isolated ontology revision.
- Change-set cache entries are session-bound, size-bounded, expire, and never contain bearer tokens or prompts.
- Restarting Protégé invalidates in-memory change-set ids cleanly.
- Broker restart, pinned-window loss, and presenting a change-set id to another backend fail closed.

## 9. M3 — Import and serialization hardening

### 9.1 Import inspection and lock

Add tools:

- `inspect_imports`
  - Direct/transitive graph, resolved document, logical/version/document IRIs, source type, cycles, conflicts,
    missing imports, and remote/local status.
- `write_import_lock`
  - Create `imports.lock.json` with deterministic ordering and SHA-256 checksums.
- `verify_import_lock`
  - Confirm every required import resolves to the locked artifact and checksum.
- `validate_catalog`
  - Parse `catalog-v001.xml`, report broken/duplicate/unreachable mappings, and compare it with imports/lock.

Lock entry example:

```json
{
  "ontology_iri": "https://example.org/upper",
  "version_iri": "https://example.org/upper/2026-07-01",
  "document": "imports/upper.ttl",
  "sha256": "...",
  "direct": true
}
```

### 9.2 Loading modes

Extend document-loading operations with:

- `missing_imports=warn` as the compatibility default.
- `missing_imports=error` for project/release operation.
- `missing_imports=silent` only as an explicit interactive choice.
- `lock_mode=ignore|verify|required`.
- `network=deny|allow` with policy default; release mode should prefer local locked imports.

Every result must list the resolved imports actually used, not only unresolved ones.

### 9.3 Verified atomic save

Add a save pipeline:

1. Serialize to a temporary file in the target directory.
2. Reload with strict imports into a private manager.
3. Compare ontology ID/header, axioms including annotations, and import declarations.
4. Run optional format-specific checks.
5. Atomically replace the target when the filesystem supports it.
6. Optionally retain a timestamped backup according to policy.
7. Return artifact path, format, byte size, SHA-256, and round-trip result.

Extend `save_ontology` additively with `verify_round_trip`, `atomic`, and `backup` options. In release mode they
default to strict/atomic; existing interactive calls retain current defaults until a major version permits a
safer default change.

### 9.4 Format safeguards

- Warn or fail when the chosen format cannot represent the ontology without loss.
- OBO export must run an OBO compatibility report before replacement.
- Preserve prefix maps where the format supports them.
- Detect blank-node instability separately from semantic axiom loss.
- Clearly distinguish byte-for-byte, axiom-identical, and logically equivalent round trips.

### 9.5 Acceptance criteria

- Release mode cannot pass with an unresolved or checksum-mismatched required import.
- Lock files and catalogs are deterministic across repeated generation.
- A failed serialization/reload never replaces the previous artifact.
- Save verification includes ontology annotations, import declarations, and axiom annotations.
- Tests cover redirects, duplicate ontology IDs, versioned imports, malformed catalogs, cycles, offline mode,
  checksum mismatch, and cross-platform relative paths.

## 10. M4 — Semantic diff and release workflow

### 10.1 Semantic diff

Add `semantic_diff` while retaining `diff_ontologies` as the fast axiom/round-trip primitive.

Inputs:

- Left/right loaded ontology, document, or release manifest.
- Active-only or imports-closure scope.
- `mode=asserted|inferred|both`.
- Reasoner and timeout.
- Policy path.
- Result limits and optional full report path.

Output categories:

- Ontology ID, version IRI, import, and ontology-annotation changes.
- Added/removed entities by type.
- Rename/merge/split candidates, always labelled as candidates unless backed by an explicit mapping.
- Label, synonym, definition, provenance, lifecycle, and replacement changes.
- Added/removed asserted axioms grouped by affected entity and axiom type.
- Inferred superclass/equivalence/disjointness/type changes.
- Newly unsatisfiable classes or inconsistency.
- CQ, invariant, governance, and SHACL result deltas.
- Import/module ownership changes.
- Compatibility classification: breaking, potentially breaking, non-breaking, metadata-only, unknown.

Breaking-change classification must be policy-driven. For example, removing an asserted subclass may be breaking
for one downstream application but acceptable for another.

### 10.2 Impact analysis

Add `analyze_change_impact` over a change set or semantic diff:

- Directly affected entities and modules.
- Referencing axioms and downstream terms.
- Imported/foreign terms re-axiomatized locally.
- Deprecated terms still in use.
- Queries/CQs/shapes that reference changed IRIs.
- Public API terms defined by policy.
- Potentially affected external mappings.

Results must distinguish syntactic reachability from proven logical impact.

### 10.3 Release gate

Add `run_release_gate`:

1. Validate project policy.
2. Verify imports and lock.
3. Run all required QC stages, failing closed.
4. Compare against an optional baseline release.
5. Enforce version IRI and annotation policy.
6. Perform verified serialization.
7. Generate reports and release manifest.

Add `prepare_release` for the mutating/artifact-producing step. It must honor read-only/confirm-write in Protégé
and support dry-run.

Release manifest example:

```json
{
  "manifest_version": 1,
  "project_id": "example-product-ontology",
  "ontology_iri": "https://example.org/ontology",
  "version_iri": "https://example.org/ontology/1.4.0",
  "created_at": "2026-07-12T00:00:00Z",
  "policy_sha256": "...",
  "import_lock_sha256": "...",
  "semantic_fingerprint": "...",
  "artifacts": [
    {"path": "ontology.ttl", "sha256": "...", "bytes": 12345}
  ],
  "qc": {"gate": "pass", "report": "reports/qc.json"},
  "baseline": {"version_iri": "...", "report": "reports/diff.json"}
}
```

### 10.4 Report formats

Produce the same underlying result in:

- Structured JSON for MCP/API composition.
- Human-readable Markdown/HTML summary.
- JUnit XML for CI checks.
- SARIF for code-host annotations where a finding maps to a project file/asset.
- Optional ROBOT-compatible query outputs for projects already using ROBOT.

### 10.5 Acceptance criteria

- An asserted-axiom-only change and an inferred-taxonomy change are reported separately.
- Rename candidates never silently rewrite IRIs.
- Required missing baseline/import/policy data fails a strict release gate.
- Release output is deterministic except for declared timestamps/identifiers.
- A manifest can be verified later without opening Protégé.
- Large reports are written to files with bounded MCP summaries and stable pagination.

## 11. M5 — Headless runner and CI

### 11.1 Refactoring sequence

1. Inventory tool cores that already operate on pure OWLAPI/Jena values.
2. Introduce `OntologyWorkspace` and adapter contracts from M0.
3. Move policy, import, validation, diff, and release orchestration out of Protégé-specific classes.
4. Keep thin existing tool handlers that marshal to the EDT and render results.
5. Add the headless workspace with an OWLAPI manager and filesystem transaction.
6. Introduce the minimum Maven module split required to keep Protégé APIs out of the headless runtime and to
   package OWLAPI as a CLI runtime dependency.
7. Add cross-adapter conformance tests and published-artifact smoke tests.

### 11.2 CLI packaging and publication

The current `protege-mcp-<version>.jar` is an OSGi bundle and relies on Protégé-provided OWLAPI, Guava, SLF4J,
and editor APIs. It cannot be reused as a standalone CLI artifact. M5 therefore delivers two separately tested
release assets:

- `protege-mcp-<version>.jar`: the existing OSGi plugin artifact, retaining its current install/update identity.
- `protege-mcp-cli-<version>-all.jar`: an executable Java 17 shaded JAR containing the headless core, OWLAPI,
  Jena, CLI/MCP transport dependencies, and the explicitly supported baseline reasoner(s), but no Protégé APIs.

The build should use a Maven parent with at least core, plugin, and CLI modules while preserving the plugin's
published filename and OSGi metadata. `java -jar protege-mcp-cli-<version>-all.jar ...` is the baseline launcher;
small POSIX/Windows launch scripts may be additional assets, not the only execution path. Before distribution,
the included reasoners and transitive dependencies receive license/notice review.

The release workflow must build both artifacts on Java 17, run their independent smoke tests, publish both with
SHA-256 checksums and notices, and verify that the CLI starts without a Protégé installation or local Maven
repository. Native images/installers are explicitly deferred until the shaded-JAR contract is stable.

### 11.3 CLI surface

Proposed commands:

```bash
protege-mcp validate --project .protege-mcp/project.yaml
protege-mcp diff --left releases/1.3.0/manifest.json --right ontology.ttl
protege-mcp release --project .protege-mcp/project.yaml --dry-run
protege-mcp release --project .protege-mcp/project.yaml
protege-mcp imports lock --project .protege-mcp/project.yaml
protege-mcp serve --transport stdio --project .protege-mcp/project.yaml
```

Command exit codes:

- `0`: gate passed.
- `1`: validation/release gate failed.
- `2`: configuration or usage error.
- `3`: execution/infrastructure error.

### 11.4 Headless mutation safety

- Never edit the only copy in place before validation.
- Work in a temporary project workspace.
- Commit with atomic replacement after all required gates pass.
- Detect source file revision/checksum changes before replacement.
- Provide `--dry-run`, `--output`, and `--no-network` consistently.
- No GUI and no shared Undo are promised; filesystem backups and manifests are the recovery mechanism.

### 11.5 CI integration

Provide:

- A documented generic shell workflow.
- A reusable GitHub Actions workflow or composite action.
- PR annotations from JUnit/SARIF.
- Cached Maven dependencies/import artifacts without weakening checksum verification.
- Example workflows for general OWL and OBO/ROBOT-compatible projects.

CI security requirements:

- Treat every checked-out PR branch, ontology, policy, shape, query, and catalog as untrusted input.
- For pull requests, evaluate the candidate ontology with the trusted base-branch policy and workflow-enforced
  overrides. Validate a PR-proposed policy separately as a proposed change, but never let it enable
  `allow_external_paths`, network access, privileged providers, or relaxed required stages for the gate judging
  that same PR.
- Default PR validation to `--no-network`, a fixed project root, no external paths, and no provider credentials.
- Ship explicit least-privilege `permissions` blocks. The untrusted `pull_request` job gets read-only repository
  access and no PR/security-events write permission.
- Never use `pull_request_target` to checkout or execute fork-controlled code, policy, or artifacts.
- For fork-safe annotations, let the untrusted job upload schema-validated JSON/JUnit/SARIF artifacts. A separate
  trusted `workflow_run` may download and parse those files as data, verify their producer/run/digest and size,
  and then post comments or upload SARIF; it must never execute strings or paths supplied by the artifact.
- Pin third-party Actions to reviewed immutable revisions and document token/secret exposure for every job.

CI should compare the PR ontology against the target branch baseline and attach:

- QC verdict.
- Semantic diff summary.
- Breaking-change candidates.
- Generated artifact checksums.

### 11.6 Plugin/CLI conformance

Given the same ontology snapshot, policy, reasoner, and validation assets:

- Stage pass/fail/error/skip status must match.
- Finding ids, focus IRIs, severities, and counts must match.
- Axiom normalization and fingerprints must match.
- For validation/gate outputs, renderer differences affect presentation only, never identity or gate outcome.
- Cross-surface conformance fixtures use IRI/CURIE operands. The headless surface resolves optional display names
  only through a deterministic policy-declared lexical order (IRI, CURIE, configured preferred-label properties
  and languages) and rejects ambiguity; Protégé renderer-based grounding is a documented GUI-surface extension
  whose canonical normalized operation must still contain IRIs.

### 11.7 Acceptance criteria

- A clean checkout can run the release gate without launching Protégé.
- Offline CI succeeds when all locked inputs are present and fails when one is missing.
- CLI and plugin conformance fixtures produce equivalent machine results.
- Stdio MCP exposes the supported headless subset and clearly marks unavailable live-GUI operations.
- Architecture Approach C documentation moves from “designed” to “delivered” only after artifact publication,
  CI coverage, and release documentation are complete.
- The published shaded CLI runs on a clean Java 17 host without Protégé and without resolving dependencies from
  a local Maven cache.
- An untrusted fork PR cannot relax its own trusted gate, enable network/external paths, or receive a write-scoped
  token; annotations follow the artifact-plus-`workflow_run` pattern above.

## 12. M6 — Term reuse, mappings, and lifecycle

### 12.1 Synonym-aware local discovery

Extend entity search with policy-configured lexical properties:

- `rdfs:label`, `skos:prefLabel`, `skos:altLabel`, OBO exact/related synonyms, and project properties.
- Preferred and fallback languages.
- Match source, language, normalization, and score explanation in every hit.
- Collision detection across preferred labels, synonyms, and IRI local names.
- A `reuse_candidate` result distinct from a guaranteed exact ground.

No fuzzy/synonym match should automatically suppress minting. It should trigger review.

### 12.2 External term providers

Define a provider SPI for OLS, BioPortal, LOV, or project-specific registries:

- Disabled by default and explicit about network egress.
- Query, ontology/vocabulary filters, language, license/source, and result pagination.
- Cached responses with provider timestamp and source URL.
- Import/reuse actions remain separate from search.
- A project allowlist controls which source ontologies may be reused.
- Version-controlled policy names a provider and a local `credential_id` only; API keys/tokens are stored in the
  OS keychain where available or owner-only local preferences/secret files, never in the policy or ontology.
- Credentials are sent in headers rather than query URLs where the provider permits it and are redacted from
  result payloads, cached URLs/bodies, logs, error text, and the M7 audit stream.

Proposed tools:

- `search_external_terms`
- `inspect_external_term`
- `propose_term_reuse`

### 12.3 Mapping management

Add SSSOM-compatible mapping workflows:

- `list_mappings`
- `add_mapping`
- `remove_mapping`
- `import_sssom`
- `export_sssom`
- `validate_mappings`

Mapping records should include subject, predicate, object, confidence, mapping justification, author/source, and
timestamps where available. Validation must flag:

- Missing mapped entities.
- Deprecated source/target terms.
- Predicate/policy incompatibility.
- Conflicting `exactMatch` mappings.
- Mapping cycles or many-to-one mappings when disallowed.
- Unlicensed or unapproved external sources according to project policy.

### 12.4 Lifecycle workflow

Policy defines states and transitions, for example:

```text
proposed -> reviewed -> approved -> released -> deprecated
                    \-> rejected
```

Add:

- `get_term_lifecycle`
- `transition_term_status`
- `list_terms_by_status`
- `validate_lifecycle`
- `plan_deprecation_migration`

Each transition records actor/client, reason, issue reference, timestamp, prior/new status, and revision. Illegal
transitions fail before mutation. Deprecation planning reports references, mappings, CQ/shape/query occurrences,
and replacement completeness.

### 12.5 Acceptance criteria

- Search explains whether a hit came from a preferred label, synonym, local name, IRI, or fuzzy match.
- External provider failures never block local editing unless policy makes a provider required.
- SSSOM round trips preserve supported fields and unknown extension columns according to a documented rule.
- An illegal lifecycle transition changes nothing.
- Deprecated terms without an allowed terminal state/replacement policy are found by project QC.

## 13. M7 — Audit, approvals, and authorization

### 13.1 Audit log

Create append-only owner-only audit streams, preferably JSON Lines plus optional PROV-O export. The default
location is outside the project/VCS tree, under `~/.protege-mcp/audit/<project-hash>/<workspace_id>.jsonl`, with
an owner-only parent directory and file permissions (`0700`/`0600` where supported). Each backend writes only
its own stream, eliminating concurrent appends when several Protégé instances open the same project. A report or
release may merge per-workspace streams deterministically by timestamp/event id. A separate explicit,
redaction-aware `export_audit_log` operation may place a review artifact inside the project.

Configure retention, maximum file size/count, rotation, and best-effort cleanup. Generated defaults and templates
must add local audit paths to VCS ignore guidance; the committed project policy contains retention settings, not
audit events or secrets.

Record:

- Event/schema version.
- UTC timestamp.
- Client id/name and provider where available.
- Tool/action category, not raw secrets.
- Change-set id, base/new revision, and policy digest.
- Target ontology/module.
- Normalized change summary and gate result.
- Human confirmation/approval references.
- Release manifest link where applicable.

Prompts, bearer tokens, OAuth/provider tokens, attached file bodies, and ontology content not required for
attribution must not be copied into the audit log.

### 13.2 Authorization capabilities

Define tool capability categories:

- `ontology:read`
- `ontology:curate`
- `ontology:admin`
- `ontology:release`
- `server:admin`
- `filesystem:project:read`
- `filesystem:project:write`
- `filesystem:external`
- `network:access`

OAuth scopes must be validated at tool execution, not only accepted and stored during OAuth. For backward
compatibility, existing OAuth clients and the static fallback token initially receive a documented local-admin
capability profile. Preferences may reduce the static token to read-only, but an upgrade never tightens existing
credentials silently; the UI shows effective capabilities and supports an explicit migration/re-authorization.

Global controller read-only remains a hard ceiling regardless of token capabilities. Filesystem operations also
obey §7.4: `ontology:read` alone is not authority to read arbitrary local files, and outside-root access requires
both `filesystem:external` and explicit policy opt-in.

Tool metadata should declare required capabilities once in `ToolRegistry`; authorization must not be repeated
ad hoc in every handler.

#### Shared-broker principal propagation

The shared broker is the authentication point in the default deployment, while tools execute in a per-window
backend. M7 must make that boundary explicit:

1. The broker resolves every accepted token to an `AuthenticatedPrincipal` containing principal type, client id,
   display name, effective capabilities, grant id, and revocation generation.
2. `McpProxyServlet` strips all client-supplied principal/capability headers, then forwards the broker-generated
   principal in a trusted header or request context alongside the per-window broker secret.
3. The backend honors that principal only when the broker secret is valid. The verified principal is attached to
   the MCP request/exchange context and consumed by `ToolRegistry` authorization and audit; it must not rely on an
   unsafe global or on a `ThreadLocal` unless the transport proves request-thread affinity.
4. Standalone/direct mode derives the same principal structure from its local OAuth/static-token store.
5. Broker internal APIs add authenticated list-clients, effective-capabilities, revoke-client/grant, and
   terminate-session operations. `BrokerClient` and the MCP Server view expose them so broker-managed clients can
   be inspected and revoked in-product.
6. Broker session pins record principal/grant identity. Revocation blocks the next request and actively closes or
   invalidates that principal's pinned MCP sessions and proxy SSE streams; implementations unable to interrupt a
   particular in-flight operation must report that limitation and prevent any subsequent request/result commit.

Ontology Assistant currently connects with an attribution-less static token. Replace that path with a short-lived
per-window assistant principal/token that records provider and chat/session identity. Its configured capability
profile is separate from the static fallback token, but it can never exceed global read-only/confirm-write
settings. When the assistant profile is read-only, chat remains usable for reads and edit attempts return an
actionable permission result rather than silently bypassing or disabling the whole assistant.

### 13.3 Approval workflow

Support policy requirements such as:

- Human confirmation for all writes.
- Approval only for destructive or release actions.
- Two-person approval for public release.
- Approval by named project role.

An approval binds explicitly to the change-set operation fingerprint, full base-revision envelope, and policy
digest. Any rebase or changed operation invalidates it. Approval storage may begin as a signed/hashed sidecar;
cryptographic signing is a later option and should not be implied before a key-management design exists.

### 13.4 Network posture

- Preserve the `0.5.0` loopback default and the existing prominent non-loopback plain-HTTP/bearer-token warning.
- Document a TLS reverse-proxy deployment if remote access is needed.
- Treat a true multi-user server as a separate security profile with threat model, rate limits, isolated
  projects, and credential storage review.

### 13.5 Acceptance criteria

- A read-scoped OAuth client cannot invoke any mutating, artifact-writing, release, or server-admin tool.
- A read-scoped client cannot read caller-selected local files; project-root file reads/writes and outside-root
  access require the explicit filesystem capabilities and policy conditions defined above.
- In broker and standalone modes, revoking a client immediately rejects new requests and terminates/invalidates
  its pinned sessions/streams as specified above; behavior for an already executing non-interruptible operation
  is explicit and its result cannot commit state after revocation.
- Audit entries can reconstruct who committed a change set and which gates passed without exposing secrets.
- An approval cannot be replayed against different operations, revisions, or policies.
- Existing single-user loopback setups continue to work with documented defaults.
- Broker-managed client listing/revocation works from the product UI, and the backend audit sees the propagated
  client identity/capabilities rather than only the broker secret.
- Multiple Protégé instances write separate owner-only audit streams without corrupting each other; rotation and
  retention bounds are enforced.

## 14. M8 — Rules, asynchronous jobs, performance, and integration

### 14.1 Reasoner and SWRL capability model

Add `get_reasoner_capabilities` and `validate_rules`:

- Supported OWL profile/construct caveats.
- SWRL support, built-in support, and DL-safety checks.
- Incremental reasoning availability.
- Explanation support.
- Known incompatibilities surfaced before classification.

Add optional `materialize_inferences` with explicit inference types, target ontology/file, provenance, preview,
and size limits. It must never imply that all reasoner inferences were materialized when expensive categories
were skipped.

### 14.2 Common job model

Long-running operations should support:

- `start_job`
- `get_job`
- `cancel_job`
- `list_jobs`

Initial job types:

- Classification and explanations.
- Project/release QC.
- SHACL/SPARQL suites.
- Semantic diff/impact analysis.
- Module extraction.
- Verified serialization and release preparation.

Job result fields:

- id, `workspace_id`, type, state, created/started/completed timestamps.
- Base ontology revision and policy digest.
- Progress phase and bounded human message.
- Cancellation requested/effective status.
- Result/error and artifact references.

Cancellation must be honest: if a third-party reasoner cannot be interrupted, report `cancel_pending` and prevent
its stale result from being committed or cached as current.

Jobs live in one per-window backend. Job ids embed/bind the `workspace_id`; shared-broker session pinning routes
follow-up calls to that backend, and a lost pin, closed window, broker restart, or request to another instance
fails with `unknown_job` rather than adopting the job. Read-only result artifacts may be copied/exported
explicitly after completion, but mutable job state never migrates between instances.

### 14.3 Performance work

- Establish representative small, medium, and large fixtures before hard thresholds are chosen.
- Benchmark snapshot capture, reasoning, SPARQL cache construction, SHACL, semantic diff, and serialization.
- Minimize work on the EDT; capture immutable data quickly and process it off-thread.
- Stream or page large findings instead of building unbounded MCP results.
- Add memory limits and cardinality estimates before inference materialization.
- Key caches by semantic/document revision and release resources promptly when the server stops.

Performance budgets should be recorded against a reference environment and checked for regression rather than
promising hardware-independent absolute times.

### 14.4 Live integration automation

Build a release-level harness that covers:

- Launching Protégé with an explicit Java 17+ runtime (`PROTEGE_JAVA_HOME`/`JAVA_HOME`) rather than trusting a
  bundled JVM. The first assertion checks the bundle reaches RESOLVED/ACTIVE state and the endpoint becomes live
  within a bounded deadline, so an `osgi.ee` Java-version resolution failure is reported directly instead of as
  a generic server timeout.
- Loading the OSGi bundle in a supported Protégé runtime.
- Server/broker startup and shutdown.
- OAuth/static-token connection.
- EDT write visibility and one-step Undo.
- A real reasoner classification and explanation.
- Multi-window session pinning.
- Ontology Assistant CLI subprocess parsing where feasible.

Use a virtual display on CI where supported, but retain a short manual checklist for platform-specific packaging
and macOS/Windows behavior.

### 14.5 Acceptance criteria

- Unsupported SWRL/reasoner combinations are identified before or during the reasoner stage with an actionable
  error, not a misleading pass.
- Job cancellation never applies a stale mutation or release artifact.
- A timeout/cancelled result can be distinguished from a validation failure.
- Performance fixtures and baselines are versioned and run on scheduled/release CI.
- The automated live harness executes the critical load/read/write/reason/undo flow against the built bundle.

## 15. Delivery sequence and tentative releases

The following grouping minimizes half-built public workflows. Exact version numbers may change.

### Tentative `0.6.x`: project-level quality

- M0 contracts and fingerprints.
- M1 project policy, persisted validation assets, governance QC stage, strict required-stage behavior.
- Initial import inspection and strict missing-import mode from M3.
- Documentation for adopting policy-referenced invariants/SHACL while preserving existing inline/single-path calls.

Exit condition: a project can commit one policy and reproduce the same strict QC result in clean Protégé
sessions.

### Tentative `0.7.x`: safe changes and impact

- M2 model revisions and preflight change sets.
- Initial semantic diff and impact analysis from M4.
- Change-set-aware audit records.
- Batch curation tools integrated with preview/commit.

Exit condition: a full-policy failure cannot alter the live ontology, and a concurrent GUI edit cannot be
overwritten.

### Tentative `0.8.x`: release and automation

- Complete M3 import lock and verified atomic save.
- Complete M4 release gate, reports, and manifests.
- M5 Maven core/plugin/CLI packaging split, published executable shaded CLI, stdio MCP subset, and CI examples.

Exit condition: a clean checkout can validate and build a verifiable ontology release without Protégé.

### Tentative `0.9.x`: governed collaboration

- M6 synonym/reuse, mappings, and lifecycle.
- M7 capabilities, approvals, and full audit.
- M8 rule validation, job model, performance gates, and live integration automation.

Exit condition: project roles and lifecycle rules are enforced consistently in interactive and automated
workflows, with a traceable release history.

## 16. Testing strategy

### 16.1 Unit and property tests

- Policy parsing, validation, migrations, and path resolution.
- Canonical fingerprints independent of ordering.
- Change normalization and inverse/preflight behavior.
- Gate aggregation including optional skips and missing/failed required-stage errors.
- Import graph, lock, catalog, and checksum logic.
- Semantic diff classification and rename heuristics.
- Lifecycle transition state machine.
- Authorization capability mapping.
- Audit redaction and deterministic report generation.

Use generated ontologies/property-style fixtures for ordering, punning, annotations, blank nodes, cycles,
language tags, and format round trips.

### 16.2 Cross-component pipeline tests

Extend `ToolPipelineTest` or introduce project/release pipeline tests:

```text
load project -> resolve policy/import lock -> preview change -> full QC
-> commit -> semantic diff -> verified save -> release manifest -> verify manifest
```

Run the same fixture through the Protégé fake adapter and headless adapter where possible.

### 16.3 Real reasoner tests

- Satisfiable, newly unsatisfiable, and inconsistent cases.
- Lost and gained entailments.
- Unsupported SWRL built-ins.
- Explanation timeout and cancellation.
- Profile-specific reasoner behavior.

Reasoner-specific expectations must be tagged and must not silently become general OWL semantics.

### 16.4 Filesystem and network tests

- Atomic replace and rollback after failure.
- Locked imports in offline mode.
- Redirects, timeouts, corrupted downloads, and checksum mismatch.
- Windows/macOS/Linux path and file-lock behavior.
- Catalog and lock disagreement.

Network tests should use local controlled servers; normal unit tests remain offline-capable.

### 16.5 Compatibility tests

- Build a golden-snapshot harness and commit the `0.5.0` baseline for all 66 tool schemas, required result fields,
  and 11 guided prompt contracts before changing public registrations.
- Verify current calls without policy continue to work.
- Verify policy schema migrations from every released schema version.
- Ensure plugin and headless outputs agree on identity, counts, and gate outcome.

## 17. Documentation and migration

Every milestone must update:

- `README.md` capability summary and tool/prompt counts.
- `DESIGN.md` architecture and delivered/deferred status.
- Tool reference arguments, return fields, and examples.
- `docs/smoke-test.md` live acceptance flow.
- `TESTING.md` current test counts and integration boundaries.
- `CHANGELOG.md` compatibility and migration notes.
- Guided prompts affected by the milestone. In particular, revise `add_subclass_safely`,
  `refactor_entity_safely`, `model_domain`, `audit_ontology`, and `release_readiness_check` for project QC,
  change sets, and release gates; add prompt contract/golden tests so older `apply_changes verify=` recipes do not
  remain the default after M2.

Migration rules:

- No policy file means current interactive behavior, with a recommendation to initialize one.
- Policy schema changes require explicit version migration and a previewable rewrite.
- Existing CQs remain readable in all current conventions.
- Existing inline invariants/SHACL arguments remain supported after policy-backed alternatives ship.
- Existing OAuth clients and the static fallback token receive documented default capabilities; capability
  tightening must not occur silently. The initial compatibility profile is local-admin, with an explicit opt-in
  read-only static-token preference and a visible migration/re-authorization path.
- Release mode may be stricter than interactive mode, but the mode and policy source must always be explicit in
  results.

## 18. Definition of done for every milestone

A milestone is complete only when:

- Public contracts and failure semantics are documented.
- Pure decision logic has unit tests, including adversarial cases.
- Cross-tool pipeline tests cover the new workflow.
- Relevant JaCoCo gates remain green or are intentionally adjusted with rationale.
- Live Protégé smoke steps cover EDT/undo/reasoner/UI behavior that headless tests cannot.
- Security review covers new files, network calls, authorization, secrets, and path handling.
- Large results are bounded, pageable, or artifact-backed.
- Backward compatibility is tested.
- User documentation and changelog are updated in the same change.
- `mvn clean verify` and the version-consistency check pass.

## 19. Principal risks and mitigations

| Risk | Consequence | Mitigation |
| --- | --- | --- |
| Plugin and headless semantics diverge | CI passes while Protégé fails, or vice versa | One core, adapter conformance fixtures, shared contracts |
| Canonical fingerprint is unstable, especially for anonymous individuals | False conflicts and unverifiable manifests | Formal blank-node-aware canonicalization contract, explicit degraded guarantees, and cross-format/restart tests |
| Isolated reasoner differs from Protégé reasoner | Preflight/live verdict mismatch | Use same factory/config where possible; record versions/capabilities; optional post-commit verification |
| Policy becomes too complex | Projects avoid it or misconfigure checks | Versioned minimal defaults, generated templates, validation with actionable errors |
| Required checks cannot run | False release pass | Map missing/failed required stages to error in project/release mode; reserve skipped for explicit optionality |
| Semantic diff is too expensive | UI freezes or clients time out | Job model, cached snapshots, bounded summaries, staged asserted/inferred modes |
| Import locking blocks legitimate updates | Stale dependencies | Explicit lock update workflow with diff and approval |
| External term services leak project queries | Privacy/compliance issue | Disabled by default, provider allowlist, egress disclosure, local cache policy |
| Audit log exposes sensitive content or is corrupted by multiple instances | Security/privacy and loss of attribution | Owner-only per-workspace streams outside VCS, redaction, rotation, and deterministic export/merge |
| OAuth scope changes break clients | Connection failures | Capability migration plan, compatibility defaults, UI showing effective permissions |
| Broker drops authenticated identity before backend execution | Authorization and audit cannot work in the default deployment | Trusted broker principal propagation, backend enforcement, broker-aware revocation UI/session termination |
| Untrusted PR policy weakens its own gate | False CI pass or credential exposure | Base-branch trusted policy, no-network/external-path overrides, least privilege, artifact-plus-workflow_run annotations |
| Multi-user expectations exceed desktop design | Unsafe remote operation | Keep loopback default; separate remote-server profile and threat model |

## 20. Open design decisions

Resolve these with short architecture decision records before implementation:

1. Exact canonical form used for semantic/document fingerprints, including deterministic anonymous-individual
   handling, graph-isomorphism cycles, and when only session-local guarantees can be claimed.
2. Whether policy and validation assets may live inside ontology annotations, and which parts must remain sidecars.
3. How the shipped isolated-reasoner-factory pattern is generalized while preserving Protégé configuration,
   buffering mode, fresh-entity policy, and reasoner-specific behavior.
4. Which semantic entailment set is sufficiently useful and tractable for inferred diff.
5. Default import network policy for interactive versus release mode.
6. Supported SSSOM version and unknown-column round-trip rule.
7. Audit rotation/retention defaults and whether PROV-O/project export is required in the first release; default
   runtime storage remains owner-only and outside VCS.
8. Final Maven module boundaries beyond the mandatory minimal core/plugin/CLI split.
9. Reference performance fixtures and environments used for regression gates.
10. Trusted broker-principal encoding/integrity, its MCP transport/exchange integration, and the mechanism used to
    terminate revoked pinned sessions and SSE streams.

## 21. Immediate next issues

The first implementation cycle should create small, independently reviewable issues in this order:

1. Decide fingerprint canonicalization for anonymous individuals, then implement semantic/document prototypes and
   adversarial load/save/order tests.
2. Add policy discovery, parsing, validation, and `get_project_policy`.
3. Add persisted invariant and multi-SHACL path loading relative to policy, extending existing path arguments.
4. Add `governance` and missing-required-to-error behavior to the QC orchestrator.
5. Add `run_project_qc` and update the audit/release-readiness guided prompts.
6. Implement `inspect_imports` and `missing_imports=error` without changing compatibility defaults.
7. Prototype isolated snapshot validation with the profile, structural, governance, invariants, cqs, and shacl
    stages.
8. Generalize the shipped isolated-reasoner factory pattern; write the configuration-parity ADR and tests.
9. Implement workspace-scoped revision envelopes, live-prefix fingerprint revalidation, and broker/window conflict
    tests.
10. Implement memory-only, TTL/size-bounded `preview_change_set`/`commit_change_set` with read-only,
    confirmation-race, revision, and Undo-log tests.
11. Extend the change-set path to `create_terms`, `create_properties`, Ontology Assistant, and affected prompts.
12. Add verified temporary serialization/reload and atomic replacement.
13. Prototype semantic diff categories over a curated fixture suite.
14. Create the minimal Maven core/plugin/CLI module split and publish a locally smoke-tested executable shaded CLI
    artifact without Protégé-provided dependencies.
15. Prototype trusted broker-principal propagation plus broker list/revoke/session-termination APIs before M7
    authorization work depends on them.

These issues establish the contracts and safe mutation path on which headless CI, releases, mappings, audit, and
collaboration can build without duplicating logic.
