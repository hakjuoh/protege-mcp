# Protégé MCP Ontology Engineering Roadmap

> Status: active; re-baselined after the `0.6.0` delivery. The implemented `0.6.0` scope — executable
> project policy and strict project QC, canonical fingerprints and revision envelopes, transactional
> change sets, import inspection and locks, verified atomic saves, the asserted `semantic_diff`
> prototype, the `core`/`plugin`/`cli` reactor with a standalone CLI, and the trusted broker-principal
> prototype — is recorded in `CHANGELOG.md` and the tool/ADR documentation, not here. This document
> contains only the remaining work.
>
> Scope: ontology development, quality assurance, release automation, and governed operation
>
> Version labels in this document are tentative milestones, not release commitments.

## 1. Purpose

Protégé MCP provides a broad interactive surface over the ontology currently open in Protégé, and since
`0.6.0` the first tranche of a reproducible ontology engineering lifecycle: version-controlled project
policy evaluated as a strict gate, preflighted transactional change sets, locked imports, verified
artifacts, and a headless core with a first CLI. The destination is unchanged:

1. A project records its modelling and governance policy as version-controlled data.
2. Every proposed change is evaluated against that policy before it reaches the live ontology.
3. Imports and serialized artifacts are reproducible.
4. Diffs describe semantic and curation impact, not just changed axiom strings.
5. The same checks run in Protégé, headlessly, and in CI.
6. Reviews, approvals, provenance, and releases leave an auditable trail.
7. A verified release can be published to an external ontology or knowledge-graph platform without
   confusing that platform's revision, inference, or approval semantics with Protégé MCP's contracts.

Goals 1–3 are substantially delivered for interactive use. This plan defines the remaining path: closing
the policy-coverage and change-set gaps, impact-aware diffs, a real release workflow, full headless/CI
operation, governed collaboration, and standards-first external-platform delivery — with the compatibility
constraints, acceptance criteria, and principal risks for each.

## 2. Baseline and remaining gaps

### 2.1 Capabilities to preserve

The `0.6.0` baseline (78 tools, 11 guided prompts) has the following strengths and they are treated as
compatibility requirements:

- Live access to the active Protégé ontology through `OWLModelManager`.
- GUI-visible, undoable axiom edits; structured OWL operands and Manchester class expressions.
- High-level curation operations such as term/property creation, moves, deprecation, rename, and deletion.
- Read-only mode, confirmation for every write, strict entity grounding, previews, and structured JSON results.
- Installed-reasoner discovery, classification, inferred queries, explanations, and inconsistency diagnosis.
- SPARQL query/schema/validation, SHACL validation, competency questions, invariants, and aggregate QC.
- Multi-ontology workspaces, imports, catalogs, document merge/load/save, and locality-module extraction.
- A local authenticated MCP endpoint, shared broker, and in-Protégé Ontology Assistant.
- Version-controlled project policy (`get_project_policy`, `validate_project_policy`) and the strict
  `run_project_qc` gate with the additive `governance` stage and missing-required-to-`error` semantics.
- Canonical fingerprint v2 (ADR 0001), workspace-scoped revision envelopes, and the
  `preview_change_set` / `commit_change_set` / `discard_change_set` transactional path, including
  `preview=true` on the batch curation tools and the Ontology Assistant's provider-level steering onto
  that path.
- `inspect_imports`, `missing_imports=warn|error|silent` loading, `write_import_lock` /
  `verify_import_lock` / `validate_catalog`, and verified atomic `save_ontology`.
- The asserted `semantic_diff` prototype alongside the legacy `diff_ontologies` primitive.
- The `core`/`plugin`/`cli` Maven reactor, the published executable CLI (policy validation and asserted
  diff), and the isolated validation snapshot / reasoner-parity machinery (ADR 0002).
- The trusted broker-principal prototype: secret-free principal propagation behind the per-window broker
  secret, client/grant session pinning, and internal list/revoke/session-invalidation APIs.

Existing tool names, argument meanings, and result fields remain stable unless a security or correctness
defect requires a breaking change. New fields should normally be additive.

### 2.2 Remaining gaps

The following statements were verified against the `0.6.0` code and are the gaps this roadmap still has to
close:

- Legacy direct path arguments (`save_ontology.path`, SHACL `shapes_path`, module export, catalogs,
  ontology create/load/merge sources, CQ/manifest sidecars) are not yet governed by the project
  filesystem/capability policy that already confines policy-referenced assets.
- Project/release gates automatically enforce import-closure completeness under `imports.mode: locked` /
  `fail_on_missing: true` (in `run_project_qc` and `preview_change_set`), and a locked policy requires an
  existing lockfile — but no gate verifies lock **content**: checksum/coordinate comparison exists only
  behind the explicit `verify_import_lock` tool, and the planned `lock_mode=ignore|verify|required` and
  `network=deny|allow` loading controls are unimplemented.
- Module policy is partial: duplicate module IRI/path declarations are policy errors and the governance
  stage enforces `iri_policy` namespaces plus an active-module import-layering ownership check, but
  `modules[].owned_namespaces` is schema-reserved and never enforced, declarations are not cross-checked
  against actual module file content, and import cycles/version conflicts remain `inspect_imports`
  findings rather than a policy gate.
- `apply_changes verify=...` detects new inconsistency and unsatisfiable classes, but not governance,
  profile, CQ, SHACL, invariant, serialization, import, or lost-entailment regressions; rollback uses the
  shared live undo history and can degrade to report-only semantics.
- `semantic_diff` is asserted-only (it hard-rejects `mode=inferred|both`); there is no impact analysis,
  release gate, release manifest/bundle, or report-format (Markdown/JUnit/SARIF) output.
- The CLI supports only `validate-policy` and asserted `diff`; full project QC, imports lock, release, and
  stdio MCP operation without Protégé remain open, as does every CI integration deliverable.
- The Ontology Assistant now carries provider-level change-set steering (with an explicit, disclosed
  fallback) and shares every server-side gate, but it still connects with an attribution-less static
  token; the per-window assistant principal remains M7R work.
- Search is primarily grounded in renderer/local name/`rdfs:label`; synonym-aware reuse and mapping
  workflows are limited.
- The propagated broker principal is not yet consumed as per-tool authorization: OAuth scopes are not
  enforced as capabilities at tool execution, and there is no persistent change audit or approval
  workflow. Revocation blocks new requests and invalidates pinned sessions but cannot terminate an
  already-streaming SSE response (`in_flight_termination:false`).
- Long-running classification, validation, extraction, and diff work has no common
  job/progress/cancellation model.
- Live Protégé/OSGi/reasoner/GUI integration remains substantially dependent on manual smoke testing.
- No commercial-platform adapter is present. OWL/RDF, SHACL, and SPARQL assets can be exchanged manually,
  but `.protege-mcp/project.yaml`, fingerprint v2, import locks, workspace revisions, and change sets have
  no automatic mapping to TopBraid EDG, metaphactory, PoolParty, GraphDB, Stardog, or Semaphore. The
  current product matrix and evidence boundary are documented in `docs/commercial-platforms.md`.

## 3. Product principles

### 3.1 Semantics before automation

- OWL remains the source of logical meaning.
- SHACL and selected competency questions express closed-world data or project requirements.
- A property domain/range is never treated as a form-field constraint. It is an OWL inference-producing axiom.
- SKOS mapping relations are not silently promoted to OWL equivalence.
- An automated fix must identify which formalism and policy authorizes it.

### 3.2 Preflight before mutation

The safe-authoring path shipped in `0.6.0` is the preferred default for all remaining work:

1. Capture a point-in-time ontology/project revision.
2. Resolve and normalize a proposed change set.
3. Apply it to an isolated snapshot.
4. Run the required gates on that snapshot.
5. Re-check that the live revision has not changed.
6. Commit the exact normalized changes to the live model as one undo unit.

Applying first and undoing after a failed gate is retained only as a compatibility fallback when a required
validator cannot run against an isolated snapshot. New write surfaces (release preparation, headless
mutation) must adopt the preflight path rather than the fallback; the Assistant steering already prefers
it for axiom edits and confines its disclosed direct-axiom fallback to servers without the change-set
tools or an explicit user direction.

### 3.3 Reproducibility over hidden state

- Release-critical policy lives beside the ontology and is committed to version control.
- The selected reasoner, import documents, checksums, validation assets, and serialization format are explicit.
- A release never passes because a required check did not run; that condition is an explicit `error`.
- A result records the policy version and ontology revision it evaluated.

### 3.4 One core, multiple surfaces

Protégé MCP, the headless core, the CLI, and CI must call the same ontology-engineering core and emit the
same result contracts. The GUI adapter owns EDT marshalling and undo; the headless adapter owns filesystem
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
                                             |
                                  external delivery adapters
                              standard RDF protocols / vendor APIs
```

As of `0.6.0` the repository is a Maven reactor with `core` (headless, Protégé-API-free), `plugin` (the
OSGi bundle with its published filename and metadata), and `cli` (executable shaded JAR). Remaining
architectural work:

- Move release, impact, and remaining validation orchestration into `core` as it is built, so the CLI and
  plugin never fork semantics.
- Complete the headless workspace adapter (filesystem transactions, catalog/import resolution, reasoner
  factories) for mutating headless commands.
- Introduce a shared project-relative `ArtifactStore` (atomic replacement, checksums) for report, release,
  and audit writes, plus `Clock`/`IdGenerator` seams so audit records and manifests are deterministically
  testable.
- Define external delivery behind a narrow adapter SPI. Vendor SDKs, credentials, remote revisions, and
  API-specific models stay outside the ontology-engineering core; the core consumes only capability,
  publish-plan, receipt, and verification contracts.
- Introduce finer module boundaries (`core-model`, `core-owl`, adapters) only when a dependency boundary
  becomes enforceable; the three-module split is the mandatory minimum, not the end state.

The foundational contracts shipped in `0.6.0` (revision envelope, finding, gate result, policy schema versioning)
continue to govern all new work: unknown future schema fields are preserved or rejected according to a
documented rule, never silently reinterpreted, and fingerprints outside the canonicalization guarantees are
explicitly degraded (`session_only`), never reported as stable.

## 5. Milestone overview

| Milestone | Priority | Remaining outcome | Depends on |
| --- | --- | --- | --- |
| M1R. Policy coverage completion | P0 | Direct path arguments and module/namespace declarations governed by policy | — |
| M2R. Change-set completion | P0 | `verify=` migration onto change sets, `rebase_change_set` | — |
| M3R. Import and serialization completion | P0 | Lock-verified gates, network modes, format safeguards | M1R |
| M4R. Semantic diff completion and release workflow | P0 | Inferred/impact-aware diffs and reproducible release bundles | M3R (release-gate half) |
| M5R. Headless runner and CI | P0 | Same validation/release behavior without Protégé, stdio MCP, CI | M3R, M4R |
| M6. Reuse, mappings, and lifecycle | P1 | Synonym-aware reuse, SSSOM, governed term lifecycle | M1R, M2R |
| M7R. Audit, approvals, and authorization | P1 | Capability enforcement, audit trail, approvals, revocation termination | M2R, M4R (audit slice independent) |
| M8. Rules, async jobs, performance, integration | P1 | Predictable long-running work and stronger live-runtime confidence | cross-cutting |
| M9. Commercial platform interoperability | P2 | Verified, drift-safe publication and round-trip verification through standards-first target profiles | M3R, M4R, M5R; M7R for remote writes |

The `R` suffix marks milestones whose first tranche shipped in `0.6.0`; their sections below list only what
remains. Milestones may overlap after their data contracts stabilize, and dependencies bind only the halves
that need them: the M4R diff/impact half and the first M7R audit slice are dependency-free and ship earlier
(§15). M1R–M5R form the remaining minimum path from an interactive assistant to an ontology DevOps workflow.

## 6. M1R — Policy coverage completion

### 6.1 Filesystem and capability policy for direct path arguments

The project filesystem policy that already confines policy-referenced assets must apply to direct tool
path arguments as well. This includes `save_ontology.path`, `shacl_validate.shapes_path`, module export,
catalogs, ontology create/load/merge sources, CQ/manifest sidecars, and future report paths:

- Project-root reads require `filesystem:project:read`; writes require `filesystem:project:write` plus the
  existing global read-only/confirm-write gate.
- Paths outside the project root require `filesystem:external`, `filesystem.allow_external_paths=true` in
  policy, and any required local write confirmation.
- URL/document fetches are governed separately by `network:access` and the import/network policy.
- For backward compatibility, a loopback static local-admin session with no project policy retains the
  current direct-path behavior, but the effective unrestricted mode is reported and can be disabled in
  preferences.
- A remote/read-only principal never gains arbitrary local-file access merely because a validator is
  read-only.

The capability names align with M7R so enforcement is implemented once.

### 6.2 Module and namespace policy gates

Complete the module policy whose declaration-level checks (duplicate module IRI/path) already fail closed:

- Enforce `modules[].owned_namespaces` at runtime: terms in an owned namespace defined outside the owning
  module become policy findings.
- Cross-check policy module declarations against actual module file content (declared `ontology_iri`
  versus the IRI in the file), not just file existence.
- Elevate import cycles and identity/version/document conflicts from descriptive `inspect_imports`
  findings to policy-configurable gate failures.

### 6.3 Optional authoring aids

- A `write_project_policy_template` tool may generate a commented starter policy file; users review and
  commit it like source code. Policy writing stays file-oriented; no large `set_project_policy` mutation
  tool.

### 6.4 Acceptance criteria

- Direct tool path arguments obey the same project-root and capability policy as policy-referenced assets,
  with the documented no-policy compatibility mode.
- A module file whose ontology IRI differs from its policy declaration is a policy validation error.
- With the corresponding policy enabled, an import cycle or owned-namespace violation fails
  `run_project_qc`; without it, behavior is unchanged.
- Policy paths and validation assets keep working on macOS, Linux, and Windows path conventions.

## 7. M2R — Change-set completion

The Ontology Assistant steering, the batch `preview=true` routing tests, and the prompt-wording goldens
shipped with `0.6.0`; what remains of M2 is the verification migration and rebase.

### 7.1 `apply_changes verify=` migration

- Internally migrate `apply_changes verify=report|rollback` onto the change-set services when behavior can
  be preserved, extending detection beyond inconsistency/unsatisfiability to the policy-required gates.
- Deprecate live apply-then-undo verification only after change sets cover the same reasoner cases.

### 7.2 `rebase_change_set`

- Offer a read-only `rebase_change_set` only after deterministic re-resolution is implemented.
- If a referenced display name now resolves to another IRI, rebase must fail for human review.
- A revision mismatch never auto-merges; conflict results keep returning changed ontology IDs and complete
  revision envelopes.

### 7.3 Acceptance criteria

- `apply_changes verify=` and the change-set path report the same verdict for the same change and gates.
- A rebase across a concurrent rename fails closed with a human-review result.

## 8. M3R — Import and serialization completion

### 8.1 Gate-time lock verification

Extend the shipped closure-completeness enforcement so gates verify lock **content**:

- `lock_mode=ignore|verify|required` on project/release gates and document-loading operations: `verify`
  compares every required import's resolved document and SHA-256 against `imports.lock.json` (the same
  comparison `verify_import_lock` performs today); `required` additionally errors when the lock is absent.
- `run_project_qc`, `preview_change_set`, and the future release gate consume the same verification, so a
  tampered locked import can never yield `gate=pass`.
- The CLI performs identical verification headlessly.

### 8.2 Network loading modes

- `network=deny|allow` on document-loading operations with the policy default (`network.default` is
  already `deny` in effective policy but is not yet consulted by loading paths).
- Release mode prefers local locked imports; every result lists the resolved imports actually used.

### 8.3 Format safeguards

Verified round-trip comparison shipped; the remaining safeguards:

- Warn or fail when the chosen format cannot represent the ontology without loss, by default rather than
  only when `verify_round_trip` is requested.
- OBO export runs an OBO compatibility report before replacement.
- Keep blank-node instability distinct from semantic axiom loss; verified save rejects such
  parser-local identities before writing while fingerprints remain explicit `session_only` tokens.
- Clearly distinguish byte-for-byte, axiom-identical, and logically equivalent round trips.
- In release mode, verification/atomic/backup default to strict; existing interactive calls retain current
  defaults until a major version permits a safer default change.

### 8.4 Acceptance criteria

- Release mode cannot pass with an unresolved or checksum-mismatched required import.
- With `lock_mode=verify`, a swapped import document fails `run_project_qc` and `preview_change_set`
  without any explicit `verify_import_lock` call.
- `network=deny` produces an explicit error, not a silent partial load, when a remote fetch would be
  required.
- Tests cover redirects, versioned imports, offline mode, checksum mismatch, and cross-platform relative
  paths for the new gate-time paths.

## 9. M4R — Semantic diff completion and release workflow

### 9.1 Inferred semantic diff

Complete `semantic_diff` (asserted-only today; it rejects `mode=inferred|both`) while retaining
`diff_ontologies` as the fast axiom/round-trip primitive:

- Inputs: left/right loaded ontology, document, or release manifest; active-only or imports-closure scope;
  `mode=asserted|inferred|both`; reasoner and timeout; policy path; result limits and optional full report
  path.
- New output categories on top of the shipped asserted ones: inferred
  superclass/equivalence/disjointness/type changes; newly unsatisfiable classes or inconsistency; CQ,
  invariant, governance, and SHACL result deltas; module ownership changes (terms whose owning module
  changed, per the M1R `owned_namespaces` policy).
- Breaking-change classification must be policy-driven. For example, removing an asserted subclass may be
  breaking for one downstream application but acceptable for another.
- Rename/merge/split candidates stay labelled as candidates unless backed by an explicit mapping.

The entailment set for inferred diff is an open design decision (§20) and needs a short ADR first.

### 9.2 Impact analysis

Add `analyze_change_impact` over a change set or semantic diff:

- Directly affected entities and modules.
- Referencing axioms and downstream terms.
- Imported/foreign terms re-axiomatized locally.
- Deprecated terms still in use.
- Queries/CQs/shapes that reference changed IRIs.
- Public API terms defined by policy.
- Potentially affected external mappings.

Results must distinguish syntactic reachability from proven logical impact.

### 9.3 Release gate

Add `run_release_gate`:

1. Validate project policy.
2. Verify imports and lock (the §8.1 content verification).
3. Run all required QC stages, failing closed.
4. Compare against an optional baseline release.
5. Enforce version IRI and annotation policy.
6. Perform verified serialization.
7. Generate reports and release manifest.

Add `prepare_release` for the mutating/artifact-producing step. It must honor read-only/confirm-write in
Protégé and support dry-run.

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

A strict release gate errors rather than claiming a stable manifest fingerprint when an ontology falls
outside the supported canonicalization guarantees (anonymous individuals degrade to `session_only`).

### 9.4 Report formats

Produce the same underlying result in:

- Structured JSON for MCP/API composition.
- Human-readable Markdown/HTML summary.
- JUnit XML for CI checks.
- SARIF for code-host annotations where a finding maps to a project file/asset.
- Optional ROBOT-compatible query outputs for projects already using ROBOT.

### 9.5 Acceptance criteria

- An asserted-axiom-only change and an inferred-taxonomy change are reported separately.
- Rename candidates never silently rewrite IRIs.
- Required missing baseline/import/policy data fails a strict release gate.
- Release output is deterministic except for declared timestamps/identifiers.
- A manifest can be verified later without opening Protégé.
- Large reports are written to files with bounded MCP summaries and stable pagination.

## 10. M5R — Headless runner and CI

### 10.1 Remaining refactoring

The reactor split, shaded CLI packaging, and dual-artifact release automation shipped. Remaining:

- Move release, impact, and remaining validation orchestration into `core` as M4R/M3R land.
- Add the headless workspace adapter with filesystem transactions for mutating commands.
- Add cross-adapter conformance tests (§10.5) and keep the published-artifact smoke tests green as the CLI
  surface grows.

### 10.2 CLI surface

Shipped: `validate-policy --project` and asserted `diff --left --right`. Proposed completion:

```bash
protege-mcp validate --project .protege-mcp/project.yaml     # full project QC gate
protege-mcp diff --left releases/1.3.0/manifest.json --right ontology.ttl
protege-mcp release --project .protege-mcp/project.yaml --dry-run
protege-mcp release --project .protege-mcp/project.yaml
protege-mcp imports lock --project .protege-mcp/project.yaml
protege-mcp serve --transport stdio --project .protege-mcp/project.yaml
```

Full-QC and release commands require the shaded JAR to bundle the explicitly supported baseline
reasoner(s) — still without Protégé APIs; the current CLI ships none, so a policy-required reasoner stage
cannot run headlessly yet. Newly included reasoners and their transitive dependencies receive
license/notice review before distribution. `java -jar` remains the baseline launcher; small POSIX/Windows
launch scripts may be additional assets, not the only execution path, and native images/installers stay
deferred until the shaded-JAR contract is stable.

Target command exit codes — adopting them is a documented behavior change for existing scripts, since the
shipped `validate-policy` maps an invalid policy to exit `2`:

- `0`: gate passed.
- `1`: validation/release gate failed.
- `2`: configuration or usage error.
- `3`: execution/infrastructure error.

### 10.3 Headless mutation safety

- Never edit the only copy in place before validation.
- Work in a temporary project workspace.
- Commit with atomic replacement after all required gates pass.
- Detect source file revision/checksum changes before replacement.
- Provide `--dry-run`, `--output`, and `--no-network` consistently.
- No GUI and no shared Undo are promised; filesystem backups and manifests are the recovery mechanism.

### 10.4 CI integration

Provide:

- A documented generic shell workflow.
- A reusable GitHub Actions workflow or composite action.
- PR annotations from JUnit/SARIF.
- Cached Maven dependencies/import artifacts without weakening checksum verification.
- Example workflows for general OWL and OBO/ROBOT-compatible projects.

CI security requirements:

- Treat every checked-out PR branch, ontology, policy, shape, query, and catalog as untrusted input.
- For pull requests, evaluate the candidate ontology with the trusted base-branch policy and
  workflow-enforced overrides. Validate a PR-proposed policy separately as a proposed change, but never let
  it enable `allow_external_paths`, network access, privileged providers, or relaxed required stages for
  the gate judging that same PR.
- Default PR validation to `--no-network`, a fixed project root, no external paths, and no provider
  credentials.
- Ship explicit least-privilege `permissions` blocks. The untrusted `pull_request` job gets read-only
  repository access and no PR/security-events write permission.
- Never use `pull_request_target` to checkout or execute fork-controlled code, policy, or artifacts.
- For fork-safe annotations, let the untrusted job upload schema-validated JSON/JUnit/SARIF artifacts. A
  separate trusted `workflow_run` may download and parse those files as data, verify their
  producer/run/digest and size, and then post comments or upload SARIF; it must never execute strings or
  paths supplied by the artifact.
- Pin third-party Actions to reviewed immutable revisions and document token/secret exposure for every job.

CI should compare the PR ontology against the target branch baseline and attach:

- QC verdict.
- Semantic diff summary.
- Breaking-change candidates.
- Generated artifact checksums.

### 10.5 Plugin/CLI conformance

Given the same ontology snapshot, policy, reasoner, and validation assets:

- Stage pass/fail/error/skip status must match.
- Finding ids, focus IRIs, severities, and counts must match.
- Axiom normalization and fingerprints must match.
- For validation/gate outputs, renderer differences affect presentation only, never identity or gate
  outcome.
- Cross-surface conformance fixtures use IRI/CURIE operands. The headless surface resolves optional display
  names only through a deterministic policy-declared lexical order (IRI, CURIE, configured preferred-label
  properties and languages) and rejects ambiguity; Protégé renderer-based grounding is a documented
  GUI-surface extension whose canonical normalized operation must still contain IRIs.

### 10.6 Acceptance criteria

- A clean checkout can run the release gate without launching Protégé.
- Offline CI succeeds when all locked inputs are present and fails when one is missing.
- CLI and plugin conformance fixtures produce equivalent machine results.
- Stdio MCP exposes the supported headless subset and clearly marks unavailable live-GUI operations.
- Architecture Approach C documentation moves from “designed” to “delivered” only after artifact
  publication, CI coverage, and release documentation are complete.
- An untrusted fork PR cannot relax its own trusted gate, enable network/external paths, or receive a
  write-scoped token; annotations follow the artifact-plus-`workflow_run` pattern above.

## 11. M6 — Term reuse, mappings, and lifecycle

### 11.1 Synonym-aware local discovery

Extend entity search with policy-configured lexical properties:

- `rdfs:label`, `skos:prefLabel`, `skos:altLabel`, OBO exact/related synonyms, and project properties.
- Preferred and fallback languages.
- Match source, language, normalization, and score explanation in every hit.
- Collision detection across preferred labels, synonyms, and IRI local names.
- A `reuse_candidate` result distinct from a guaranteed exact ground.

No fuzzy/synonym match should automatically suppress minting. It should trigger review.

### 11.2 External term providers

Define a provider SPI for OLS, BioPortal, LOV, or project-specific registries:

- Disabled by default and explicit about network egress.
- Query, ontology/vocabulary filters, language, license/source, and result pagination.
- Cached responses with provider timestamp and source URL.
- Import/reuse actions remain separate from search.
- A project allowlist controls which source ontologies may be reused.
- Version-controlled policy names a provider and a local `credential_id` only; API keys/tokens are stored in
  the OS keychain where available or owner-only local preferences/secret files, never in the policy or
  ontology.
- Credentials are sent in headers rather than query URLs where the provider permits it and are redacted from
  result payloads, cached URLs/bodies, logs, error text, and the M7R audit stream.

Proposed tools:

- `search_external_terms`
- `inspect_external_term`
- `propose_term_reuse`

### 11.3 Mapping management

Add SSSOM-compatible mapping workflows:

- `list_mappings`
- `add_mapping`
- `remove_mapping`
- `import_sssom`
- `export_sssom`
- `validate_mappings`

Mapping records should include subject, predicate, object, confidence, mapping justification, author/source,
and timestamps where available. Validation must flag:

- Missing mapped entities.
- Deprecated source/target terms.
- Predicate/policy incompatibility.
- Conflicting `exactMatch` mappings.
- Mapping cycles or many-to-one mappings when disallowed.
- Unlicensed or unapproved external sources according to project policy.

### 11.4 Lifecycle workflow

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

Each transition records actor/client, reason, issue reference, timestamp, prior/new status, and revision.
Illegal transitions fail before mutation. Deprecation planning reports references, mappings, CQ/shape/query
occurrences, and replacement completeness.

### 11.5 Acceptance criteria

- Search explains whether a hit came from a preferred label, synonym, local name, IRI, or fuzzy match.
- External provider failures never block local editing unless policy makes a provider required.
- SSSOM round trips preserve supported fields and unknown extension columns according to a documented rule.
- An illegal lifecycle transition changes nothing.
- Deprecated terms without an allowed terminal state/replacement policy are found by project QC.

## 12. M7R — Audit, approvals, and authorization

### 12.1 Audit log

Create append-only owner-only audit streams, preferably JSON Lines plus optional PROV-O export. The default
location is outside the project/VCS tree, under `~/.protege-mcp/audit/<project-hash>/<workspace_id>.jsonl`,
with an owner-only parent directory and file permissions (`0700`/`0600` where supported). Each backend
writes only its own stream, eliminating concurrent appends when several Protégé instances open the same
project. A report or release may merge per-workspace streams deterministically by timestamp/event id. A
separate explicit, redaction-aware `export_audit_log` operation may place a review artifact inside the
project.

Configure retention, maximum file size/count, rotation, and best-effort cleanup. Generated defaults and
templates must add local audit paths to VCS ignore guidance; the committed project policy contains retention
settings, not audit events or secrets.

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

### 12.2 Authorization capabilities

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
compatibility, existing OAuth clients and the static fallback token initially receive a documented
local-admin capability profile. Preferences may reduce the static token to read-only, but an upgrade never
tightens existing credentials silently; the UI shows effective capabilities and supports an explicit
migration/re-authorization.

Global controller read-only remains a hard ceiling regardless of token capabilities. Filesystem operations
also obey §6.1: `ontology:read` alone is not authority to read arbitrary local files, and outside-root
access requires both `filesystem:external` and explicit policy opt-in.

Tool metadata should declare required capabilities once in `ToolRegistry`; authorization must not be
repeated ad hoc in every handler.

#### Consuming the shipped broker principal

The `0.6.0` prototype already resolves broker tokens to a versioned secret-free `AuthenticatedPrincipal`,
strips client-supplied principal headers, forwards the principal only behind the per-window broker secret,
attaches it to the MCP request/exchange context (without an unsafe global or `ThreadLocal`), derives the
same principal structure in standalone/direct mode from the local OAuth/static-token store, pins sessions
to client/grant, and exposes internal list/revoke/session-invalidation APIs. Remaining work:

1. `ToolRegistry` authorization and the audit stream consume the principal already present in the MCP
   transport context — today it is attached but has no consumers.
2. Capability enforcement, once added, evaluates that principal identically with and without the broker.
3. Revocation additionally terminates in-flight proxy SSE streams. The shipped behavior blocks the next
   request, drops pins, and reports `in_flight_termination:false`; implementations unable to interrupt a
   particular in-flight operation must report that limitation and prevent any subsequent request/result
   commit.
4. `BrokerClient` and the MCP Server view expose the list/revoke/effective-capability APIs so
   broker-managed clients can be inspected and revoked in-product.

Ontology Assistant currently connects with an attribution-less static token. Replace that path with a
short-lived per-window assistant principal/token that records provider and chat/session identity. Its
configured capability profile is separate from the static fallback token, but it can never exceed global
read-only/confirm-write settings. When the assistant profile is read-only, chat remains usable for reads and
edit attempts return an actionable permission result rather than silently bypassing or disabling the whole
assistant.

### 12.3 Approval workflow

Support policy requirements such as:

- Human confirmation for all writes.
- Approval only for destructive or release actions.
- Two-person approval for public release.
- Approval by named project role.

An approval binds explicitly to the change-set operation fingerprint, full base-revision envelope, and
policy digest. Any rebase or changed operation invalidates it. Approval storage may begin as a signed/hashed
sidecar; cryptographic signing is a later option and should not be implied before a key-management design
exists.

### 12.4 Network posture

- Preserve the loopback default and the existing prominent non-loopback plain-HTTP/bearer-token warning.
- Document a TLS reverse-proxy deployment if remote access is needed.
- Treat a true multi-user server as a separate security profile with threat model, rate limits, isolated
  projects, and credential storage review.

### 12.5 Acceptance criteria

- A read-scoped OAuth client cannot invoke any mutating, artifact-writing, release, or server-admin tool.
- A read-scoped client cannot read caller-selected local files; project-root file reads/writes and
  outside-root access require the explicit filesystem capabilities and policy conditions defined above.
- In broker and standalone modes, revoking a client immediately rejects new requests and
  terminates/invalidates its pinned sessions/streams as specified above; behavior for an already executing
  non-interruptible operation is explicit and its result cannot commit state after revocation.
- Audit entries can reconstruct who committed a change set and which gates passed without exposing secrets.
- An approval cannot be replayed against different operations, revisions, or policies.
- Existing single-user loopback setups continue to work with documented defaults.
- Broker-managed client listing/revocation works from the product UI, and the backend audit sees the
  propagated client identity/capabilities rather than only the broker secret.
- Multiple Protégé instances write separate owner-only audit streams without corrupting each other; rotation
  and retention bounds are enforced.

## 13. M8 — Rules, asynchronous jobs, performance, and integration

### 13.1 Reasoner and SWRL capability model

Add `get_reasoner_capabilities` and `validate_rules`:

- Supported OWL profile/construct caveats.
- SWRL support, built-in support, and DL-safety checks.
- Incremental reasoning availability.
- Explanation support.
- Known incompatibilities surfaced before classification.

Add optional `materialize_inferences` with explicit inference types, target ontology/file, provenance,
preview, and size limits. It must never imply that all reasoner inferences were materialized when expensive
categories were skipped.

### 13.2 Common job model

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

Cancellation must be honest: if a third-party reasoner cannot be interrupted, report `cancel_pending` and
prevent its stale result from being committed or cached as current.

Jobs live in one per-window backend. Job ids embed/bind the `workspace_id`; shared-broker session pinning
routes follow-up calls to that backend, and a lost pin, closed window, broker restart, or request to another
instance fails with `unknown_job` rather than adopting the job. Read-only result artifacts may be
copied/exported explicitly after completion, but mutable job state never migrates between instances.

### 13.3 Performance work

- Establish representative small, medium, and large fixtures before hard thresholds are chosen.
- Benchmark snapshot capture, reasoning, SPARQL cache construction, SHACL, semantic diff, and serialization.
- Minimize work on the EDT; capture immutable data quickly and process it off-thread.
- Stream or page large findings instead of building unbounded MCP results.
- Add memory limits and cardinality estimates before inference materialization.
- Key caches by semantic/document revision and release resources promptly when the server stops.

Performance budgets should be recorded against a reference environment and checked for regression rather
than promising hardware-independent absolute times.

### 13.4 Live integration automation

Build a release-level harness that covers:

- Launching Protégé with an explicit Java 17+ runtime (`PROTEGE_JAVA_HOME`/`JAVA_HOME`) rather than trusting
  a bundled JVM. The first assertion checks the bundle reaches RESOLVED/ACTIVE state and the endpoint
  becomes live within a bounded deadline, so an `osgi.ee` Java-version resolution failure is reported
  directly instead of as a generic server timeout.
- Loading the OSGi bundle in a supported Protégé runtime.
- Server/broker startup and shutdown.
- OAuth/static-token connection.
- EDT write visibility and one-step Undo.
- A real reasoner classification and explanation.
- Multi-window session pinning.
- Ontology Assistant CLI subprocess parsing where feasible.

Use a virtual display on CI where supported, but retain a short manual checklist for platform-specific
packaging and macOS/Windows behavior.

### 13.5 Acceptance criteria

- Unsupported SWRL/reasoner combinations are identified before or during the reasoner stage with an
  actionable error, not a misleading pass.
- Job cancellation never applies a stale mutation or release artifact.
- A timeout/cancelled result can be distinguished from a validation failure.
- Performance fixtures and baselines are versioned and run on scheduled/release CI.
- The automated live harness executes the critical load/read/write/reason/undo flow against the built
  bundle.

## 14. M9 — Commercial platform interoperability

The product landscape, current 0.6.0 boundary, primary vendor sources, and feature-by-feature portability
matrix are maintained in `docs/commercial-platforms.md`. M9 turns that documented manual boundary into a
governed delivery surface. It does not make Protégé MCP a triple-store server, reproduce each vendor's
authoring UI, or claim a distributed transaction across independent MCP servers.

### 14.0 Standards foundation established in policy v1 (0.6.0)

- `project.yaml` v1 requires a portable RO-Crate project profile and supports Recommendations 1.0–1.3
  with exact version-specific contexts, descriptor filenames, and profile rules. RO-Crate 1.1 is the
  broad-compatibility default; 1.2/1.3 may be selected explicitly or inferred from an existing crate's
  unambiguous normative context.
- W3C RDFC-1.0 + SHA-256 identifies the asserted root-ontology RDF dataset. This standard digest is
  separate from fingerprint v2, which remains the local OWL/editor revision token.
- RO-Crate version/profile parsing is isolated in core's dependency-clean `ro_crate` package behind public,
  host-independent request/result types. The module bans Protégé, OWLAPI, MCP, and core dependencies so
  it can move into a separate Git project without moving the project-policy loader or adapters.
- Future target adapters consume the portable RO-Crate/RDFC layer first. The YAML execution overlay and
  fingerprint v2 may accompany a release as evidence but are never presented as vendor-neutral formats.

### 14.1 Adapter boundary and capability profiles

Start with vendor-neutral contracts in an adapter module outside the ontology-engineering core:

- `TargetCapabilities`: supported RDF/OWL serializations, graph/repository addressing, transaction and
  optimistic-lock support, maximum request sizes, import behavior, reasoning/SHACL modes, staging/promote,
  read-back/export, and delete/replace semantics.
- `PublishPlan`: immutable release-manifest digest, target identity, base remote revision, operation mode
  (`create`, `replace`, or an explicitly supported atomic promotion), bounded change/count summary,
  warnings, and required capability.
- `PublishReceipt`: target product/profile/API version, repository/graph, principal id, request id,
  before/after remote revision, uploaded artifact digest, read-back verification, and rollback/staging
  references.
- `TargetProfile`: declarative mapping from a detected capability set to supported operations and known
  semantic caveats. Unknown product/API versions fail closed for writes but may allow bounded inspection.

Initial user-facing names are provisional and require a short contract ADR before implementation:
`inspect_target`, `plan_publish`, `publish_release`, `verify_publish`, and `pull_snapshot`. Read and write
operations remain separate tools/capabilities. No connector receives an `OWLOntology` to mutate directly;
it receives a verified release artifact plus manifest and returns immutable evidence.

Configuration stores endpoint aliases, repository/graph coordinates, profile, and secret **references**.
Credentials never appear in project policy, ontology annotations, release manifests, logs, or MCP results.

### 14.2 Governed publication workflow

The first supported workflow is release publication, not arbitrary remote triple editing:

1. Run the M4R/M5R release gate and produce a verified artifact and release manifest.
2. Discover the target's capabilities and exact product/API version without mutation.
3. Capture a target revision, ETag, snapshot digest, or the strongest honest concurrency token the target
   provides. If none exists, report that limitation and restrict the initial profile to create-only/staged
   publication rather than pretending replacement is race-free.
4. Produce a bounded publish plan. Destructive replacement, inferred/materialized graph inclusion, or a
   lossy serialization is visible and requires explicit policy plus confirmation.
5. Re-check local artifact/manifest digests, authorization, endpoint policy, and remote revision immediately
   before mutation.
6. Upload to a staging graph/repository where the target supports it, read back an asserted snapshot, and
   run semantic comparison. Evaluate inference and SHACL parity separately with the target's exact settings.
7. Promote atomically only through a documented target operation. Otherwise retain staging and return a
   receipt that says promotion is manual; never emulate atomicity with a delete-then-add sequence.
8. Append the redacted publish plan, receipt, and verification result to the M7R audit stream.

Publication cannot weaken or replace the local release gate. A successful HTTP response is transport
success, not semantic verification.

### 14.3 Delivery sequence by platform class

Implement and validate the least product-specific path first:

1. **Verified file/bundle exchange** — keep the 0.6.0 manual workflow documented, add repeatable export and
   read-back recipes, and pin cross-format fixtures. This path covers every product without claiming API
   compatibility.
2. **Vendor-neutral RDF repository adapter** — target a standards-based graph/repository protocol with
   create/stage/read-back behavior, then validate named GraphDB Enterprise and Stardog profiles against
   licensed test environments. Product-specific administration remains outside the generic protocol.
3. **Authoring/governance product profiles** — validate file/API round trips for TopBraid EDG and
   metaphactory, and taxonomy/ontology profiles for PoolParty and Progress Semaphore. A profile records
   supported constructs and losses; it does not silently coerce expressive OWL into SKOS or vendor models.
4. **Native workflow adapters** — add revision, approval, promote, and rollback integration only where an
   official, licensed API and a maintainable automated test environment exist.

Products and ordering are not endorsements. Availability, edition, API stability, licensing, and access to
non-production test environments are explicit go/no-go inputs for each profile.

### 14.4 Reverse flow and multi-MCP use

`pull_snapshot` is read-only: it exports one immutable remote asserted snapshot, records target coordinates
and revision evidence, and hands the file to the existing semantic-diff/release machinery. Applying remote
changes to the live Protégé workspace still requires local entity grounding, a change-set preview, project
QC, an exact expected revision, and an explicit commit.

An AI client may connect to Protégé MCP and a platform-provided MCP server such as GraphDB's, but the two
servers retain independent authentication, authorization, sessions, revisions, and audit trails. The client
may orchestrate a workflow; Protégé MCP must not call it atomic or synchronized unless a later coordinator
protocol supplies prepare/commit/abort semantics accepted by both servers.

### 14.5 Security and network posture

- Reuse the M1R/M3R/M7R filesystem, endpoint allowlist, network, and capability policies; remote publishing
  is never enabled merely by loading a project policy from an untrusted branch.
- Require TLS except for an explicit loopback test profile. Pin the effective destination after redirects
  and prevent DNS rebinding/private-address escalation according to the network-policy ADR.
- Separate `target:inspect`, `target:pull`, `target:plan`, `target:publish`, `target:replace`, and
  `target:admin`. The desktop local-admin compatibility profile does not automatically gain remote-write
  authority.
- Bound uploads, downloads, redirects, response bodies, retries, and total deadlines. Retry only operations
  with documented idempotency or an explicit idempotency key.
- Redact authorization headers, tokens, vendor error bodies, query content, and ontology fragments according
  to policy while retaining actionable status, request ids, and target coordinates.
- Keep vendor/network tests opt-in, scheduled, or release-gated. Normal unit tests and `mvn clean verify`
  remain offline-capable.

### 14.6 Acceptance criteria

- The adapter SPI has contract tests proving that an implementation cannot mutate before a successful
  publish plan, confirmation/capability check, local digest recheck, and remote-drift recheck.
- Each supported target/profile publishes a checked-in interoperability fixture and reads it back; asserted
  differences are zero or are enumerated as reviewed, machine-readable capability losses.
- Ontology/version IRIs, imports, annotated axioms, language tags, lifecycle annotations, SHACL assets, and
  one construct near the target's OWL boundary are covered by every applicable round-trip fixture.
- Reasoner and SHACL parity results name both engines/configurations and never infer parity from asserted
  graph identity.
- Credentials are absent from policy, artifacts, logs, exceptions, receipts, and MCP results, with tests for
  common vendor error/redirect paths.
- Remote drift or an unavailable concurrency primitive cannot yield a falsely successful replace. Profiles
  without safe replacement remain create-only or staging-only.
- A platform-provided MCP server remains explicitly independent; documentation and results never claim
  distributed atomicity or shared authorization.
- The public compatibility matrix and primary-source review date are updated whenever a profile or supported
  vendor API version changes.

## 15. Delivery sequence and tentative releases

`0.6.0` pulled forward prototypes originally scheduled later (change sets, import locks, verified saves,
the asserted semantic-diff prototype, the CLI split, broker principals), so the remaining grouping is
re-balanced. Exact version numbers may change.

### Tentative `0.7.x`: safe changes everywhere, and impact

- M2R: `verify=` migration onto change-set services and `rebase_change_set`.
- M1R: direct-path filesystem/capability policy and module/namespace policy gates.
- M4R first half: inferred semantic diff and `analyze_change_impact`.
- Change-set-aware audit records (first M7R slice).

Exit condition: `apply_changes verify=` verdicts match the change-set gates for the same change, a
concurrent rename fails a rebase closed, and the semantic/inferred impact of a proposed change is
reportable.

### Tentative `0.8.x`: release and automation

- M3R: gate-time lock verification, network loading modes, format safeguards.
- M4R second half: `run_release_gate`, `prepare_release`, manifests, report formats.
- M5R: full headless QC/release/imports-lock/stdio CLI commands, headless mutation safety, CI workflows,
  plugin/CLI conformance fixtures.

Exit condition: a clean checkout can validate and build a verifiable ontology release without Protégé.

### Tentative `0.9.x`: governed collaboration

- M6 synonym/reuse, mappings, and lifecycle.
- M7R capabilities enforcement, approvals, full audit, revocation stream termination, assistant principal.
- M8 rule validation, job model, performance gates, and live integration automation.

Exit condition: project roles and lifecycle rules are enforced consistently in interactive and automated
workflows, with a traceable release history.

### Tentative post-`0.9.x`: external platform delivery

- M9 first half: adapter contracts, verified exchange fixtures, and a vendor-neutral RDF repository
  publication path.
- Validated GraphDB Enterprise and Stardog target profiles, subject to licensed test environments.
- File/API capability profiles for TopBraid EDG, metaphactory, PoolParty, and Progress Semaphore; native
  workflow adapters only where official API access and repeatable tests permit support.

Exit condition: a release that passed Protégé MCP's gate can be planned, published to staging, read back,
semantically verified, and audited without exposing credentials or claiming unsupported remote atomicity.

## 16. Testing strategy

### 16.1 Unit and property tests

- Policy migrations and the new M1R path/ownership resolution.
- Change normalization for rebase and `verify=` migration.
- Gate aggregation for the new lock/network/release stages.
- Lock-content gate verification, catalog disagreement, and checksum logic.
- Inferred diff classification and rename heuristics.
- Lifecycle transition state machine.
- Authorization capability mapping.
- Audit redaction and deterministic report generation.

Use generated ontologies/property-style fixtures for ordering, punning, annotations, blank nodes, cycles,
language tags, and format round trips.

### 16.2 Cross-component pipeline tests

Extend the pipeline tests toward the full project/release flow:

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

- The committed `0.5.0` golden-snapshot harness (tool schemas, result fields, prompt contracts) stays
  authoritative; commit a `0.6.0` baseline when it is released, and route every intentional public-surface
  change through the reviewed-drift whitelist.
- Verify current calls without policy continue to work.
- Verify policy schema migrations from every released schema version.
- Ensure plugin and headless outputs agree on identity, counts, and gate outcome.

### 16.6 External platform adapter tests

- Run pure contract tests against an in-process fake target that models revisions, drift, staging,
  idempotency, read-back corruption, partial failure, redirects, and secret-bearing error bodies.
- Keep one vendor-neutral RDF repository fixture usable by every target profile and compare the read-back
  asserted graph with `semantic_diff`.
- Run licensed/vendor network suites only in opt-in, scheduled, or release workflows with least-privilege
  ephemeral credentials and isolated test repositories.
- Record target product, edition, API version, and capability snapshot with every result so an API upgrade
  cannot silently inherit an older compatibility claim.

## 17. Documentation and migration

Every milestone must update:

- `README.md` capability summary and tool/prompt counts.
- `DESIGN.md` architecture and delivered/deferred status.
- Tool reference arguments, return fields, and examples.
- `docs/smoke-test.md` live acceptance flow.
- `TESTING.md` current test counts and integration boundaries.
- `CHANGELOG.md` compatibility and migration notes.
- `docs/commercial-platforms.md` capability matrix, primary-source review date, tested product/API versions,
  and known semantic or operational losses when an external profile changes.
- Guided prompts affected by the milestone, with prompt contract/golden tests pinning the new guidance.

Migration rules:

- No policy file means current interactive behavior, with a recommendation to initialize one.
- Policy schema changes require explicit version migration and a previewable rewrite.
- Existing CQs remain readable in all current conventions.
- Existing inline invariants/SHACL arguments remain supported alongside the policy-backed alternatives.
- Existing OAuth clients and the static fallback token receive documented default capabilities; capability
  tightening must not occur silently. The initial compatibility profile is local-admin, with an explicit
  opt-in read-only static-token preference and a visible migration/re-authorization path.
- Release mode may be stricter than interactive mode, but the mode and policy source must always be
  explicit in results.

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
| Release manifests meet ontologies outside the canonicalization guarantees | Unverifiable or falsely stable manifests | The shipped `session_only` degradation stays load-bearing: a strict release gate errors rather than claiming stability |
| Isolated reasoner differs from Protégé reasoner in inferred diff/materialization | Preflight/live verdict mismatch | Extend the shipped configuration-parity path (ADR 0002); record versions/capabilities; optional post-commit verification |
| Policy becomes too complex | Projects avoid it or misconfigure checks | Versioned minimal defaults, generated templates, validation with actionable errors |
| Required checks cannot run in release mode | False release pass | Map missing/failed required stages to error, as project QC already does; reserve skipped for explicit optionality |
| Semantic diff is too expensive | UI freezes or clients time out | Job model, cached snapshots, bounded summaries, staged asserted/inferred modes |
| Import locking blocks legitimate updates | Stale dependencies | Explicit lock update workflow with diff and approval |
| External term services leak project queries | Privacy/compliance issue | Disabled by default, provider allowlist, egress disclosure, local cache policy |
| Audit log exposes sensitive content or is corrupted by multiple instances | Security/privacy and loss of attribution | Owner-only per-workspace streams outside VCS, redaction, rotation, and deterministic export/merge |
| OAuth scope changes break clients | Connection failures | Capability migration plan, compatibility defaults, UI showing effective permissions |
| Revocation cannot stop an in-flight operation | Revoked client's work still lands | Terminate pinned SSE streams; where interruption is impossible, report it and block the result from committing |
| Untrusted PR policy weakens its own gate | False CI pass or credential exposure | Base-branch trusted policy, no-network/external-path overrides, least privilege, artifact-plus-workflow_run annotations |
| Multi-user expectations exceed desktop design | Unsafe remote operation | Keep loopback default; separate remote-server profile and threat model |
| Vendor API or edition changes | A connector mutates with stale assumptions or stops preserving semantics | Versioned capability profiles, unknown-version write refusal, official-source review dates, licensed conformance suites |
| Remote reasoning/SHACL differs from the local release gate | A byte-identical upload behaves differently in production | Record both configurations; separate asserted read-back identity from inference/SHACL parity; fail required parity closed |
| Target lacks optimistic concurrency or atomic promotion | A concurrent remote edit is overwritten or a partial release is exposed | Create/staging-only profile, explicit limitation, product-supported promotion only; never emulate atomicity |
| Connector leaks credentials or ontology content | Security/privacy incident | Secret references, endpoint allowlists, redaction tests, bounded error bodies, least-privilege ephemeral credentials |

## 20. Open design decisions

Resolve these with short architecture decision records before implementation:

1. Whether policy and validation assets may live inside ontology annotations, and which parts must remain
   sidecars.
2. Which reasoner-specific capability/caveat registry should supplement the configuration-parity boundary
   accepted in ADR 0002 when Protégé/OWLAPI exposes no machine-readable plugin metadata.
3. Which semantic entailment set is sufficiently useful and tractable for inferred diff.
4. Default import network policy for interactive versus release mode.
5. Supported SSSOM version and unknown-column round-trip rule.
6. Audit rotation/retention defaults and whether PROV-O/project export is required in the first release;
   default runtime storage remains owner-only and outside VCS.
7. Final Maven module boundaries beyond the shipped minimal `core`/`plugin`/`cli` split.
8. Reference performance fixtures and environments used for regression gates.
9. The mechanism used to terminate revoked pinned sessions and in-flight SSE streams (the principal
   encoding/integrity itself was decided and shipped in `0.6.0`).
10. The vendor-neutral publication protocol and minimum concurrency primitive required for a writable
    profile; decide whether standards-based Graph Store/RDF4J behavior belongs in one generic adapter or
    separate profiles.
11. The canonical mapping of ontology/release identity to repository, named graph, and vendor project
    coordinates, including whether imports are published separately or remain release artifacts only.
12. Whether governed reverse synchronization is part of the first M9 delivery or remains read-only snapshot
    acquisition until rebase, release, and audit contracts have field experience.

## 21. Immediate next issues

The next implementation cycle should create small, independently reviewable issues in this order:

1. Apply the project filesystem/capability policy to legacy direct path arguments with the documented
   no-policy compatibility mode.
2. Add gate-time import-lock content verification (`lock_mode=ignore|verify|required`) to
   `run_project_qc`, `preview_change_set`, and document loading, extending the shipped closure-completeness
   enforcement.
3. Add `network=deny|allow` loading controls consuming the existing `network.default` policy value.
4. Complete module policy: enforce `owned_namespaces` at runtime, cross-check declarations against module
   file content, and make import cycles/conflicts policy-gateable.
5. Migrate `apply_changes verify=` onto the change-set services, then add read-only `rebase_change_set`
   with deterministic re-resolution.
6. Write the inferred-diff entailment-set ADR, then implement `semantic_diff mode=inferred|both`.
7. Implement `analyze_change_impact` over change sets and diffs.
8. Implement `run_release_gate`, `prepare_release`, the release manifest, and the report formats.
9. Add the remaining format safeguards: default lossy-format warnings, the OBO compatibility report, and
   the round-trip class distinctions.
10. Extend the CLI to full project QC, imports lock, release, and `serve --transport stdio`, with headless
    mutation safety and plugin/CLI conformance fixtures.
11. Ship the reusable CI workflow with the fork-safe annotation pipeline.
12. Consume the propagated broker principal for per-tool capability enforcement, add the audit stream
    (its change-set-aware audit-records portion is the first slice, targeted at `0.7.x`), the short-lived
    assistant principal, and active termination of revoked SSE streams.
13. After the release manifest contract stabilizes, write the M9 adapter-contract ADR and implement the
    fake-target conformance kit (`TargetCapabilities`, plan, receipt, read-back verification, drift).
14. Implement the vendor-neutral create/stage/read-back RDF repository adapter, then validate explicit
    GraphDB Enterprise and Stardog profiles in licensed, isolated test environments.
15. Run evidence/API/licensing spikes for TopBraid EDG, metaphactory, PoolParty, and Progress Semaphore;
    publish file/API capability profiles before accepting any native workflow-adapter commitment.

Issues 1–5 harden what `0.6.0` shipped; issues 6–7 deliver the impact half of M4R; issues 8–11 build the
release/headless workflow on top of it; issue 12 opens the governed-collaboration track; issues 13–15 begin
M9 only after the release evidence boundary is stable. The order is thematic, not chronological — it
deliberately does not track the tentative release boundaries in §15.
