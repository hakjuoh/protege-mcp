# Protégé MCP Roadmap after 0.7.1

> Status: only work that is not implemented is tracked here. Delivered behavior belongs in
> [`CHANGELOG.md`](CHANGELOG.md), the user manual, and [`DESIGN.md`](DESIGN.md).
>
> The milestone names below preserve the identifiers used before the 0.7.1 re-baseline. They are
> candidate product tracks, not release or version commitments.

## 1. Scope

The current product already covers the local Protégé plugin, the Ontology Assistant, project policy and
QC, safe change sets, import locking, semantic diff and release bundles, audit and capability enforcement,
and the project-confined headless CLI. The remaining roadmap is limited to four new product families:

1. External term reuse, mapping management, and governed term lifecycle (M6B).
2. Role-based and two-person approvals (M7N).
3. Reasoner/rule capability reporting, materialization, and a public asynchronous job API (M8N).
4. Governed publication to external ontology and knowledge-graph platforms (M9).

Select a coherent vertical slice before assigning a version. A slice must include its public contracts,
policy changes, authorization, audit behavior, tests, and documentation; a tool name alone is not a
deliverable.

## 2. Shared product constraints

All future work must preserve these boundaries:

- OWL is the source of logical meaning. SHACL, SPARQL, competency questions, and project policy add
  closed-world or governance requirements; none silently replaces another.
- A write is resolved and checked against an isolated snapshot, then committed only after the live
  workspace revision, policy digest, authorization, and confirmation are rechecked.
- Release-critical inputs and evidence are explicit and reproducible. A required check that cannot run is
  an error, not a pass or an unexplained skip.
- Shared semantics live in `core`; the Protégé adapter owns EDT/Undo behavior and the headless adapter owns
  filesystem transactions. New delivery adapters do not receive a live `OWLOntology` to mutate.
- The desktop product remains loopback-first and single-user by default. Remote multi-user hosting needs a
  separate deployment profile and threat model.
- Credentials are referenced by identifier and stored outside policy, ontology documents, manifests,
  logs, audit exports, and MCP results.
- Large results are bounded, pageable, or artifact-backed. Cancellation never permits a stale result to
  mutate the workspace or become current evidence.

## 3. Candidate tracks

| Track | Outcome | Primary dependencies |
| --- | --- | --- |
| M6B | External term providers, SSSOM mappings, and lifecycle workflow | Existing policy, search, change sets, QC, and audit |
| M7N | Named-role and two-person approvals | Existing principals, revision envelopes, change sets, release gate, and audit |
| M8N | Rule/reasoner capability model, materialization, and asynchronous jobs | Existing cancellation fences, isolated reasoners, release/QC services, and broker session pinning |
| M9 | Drift-safe external publication and read-back verification | Existing verified releases, capabilities, audit, and headless workspace |

These tracks may be delivered independently except where a slice explicitly adopts another track's public
contract. For example, M9 must not invent a private job model if publication is made asynchronous; it should
either remain synchronous and bounded or wait for M8N.

## 4. M6B — External reuse, mappings, and lifecycle

### 4.1 External term providers

Define a provider SPI for services such as OLS, BioPortal, LOV, and project-specific registries.

Required behavior:

- Providers are disabled by default and every network request follows the project endpoint/egress policy.
- Queries support provider-specific ontology or vocabulary filters, language, pagination, and bounded
  caching with provider timestamp and source URL.
- Results retain the source ontology, provider, license/provenance, match explanation, and provider version
  where available.
- Search and mutation remain separate. A provider result is only a reuse candidate until a user or policy
  authorizes an explicit import, mapping, or minting proposal.
- Policy may name a provider and a `credential_id`; the secret is resolved from an OS keychain or an
  owner-only local store and is sent in headers when the provider supports that.
- Credentials and sensitive query content are redacted from cache keys, URLs, errors, logs, and audit
  events.

Provisional tools:

- `search_external_terms`
- `inspect_external_term`
- `propose_term_reuse`

### 4.2 SSSOM mapping management

Add import, export, editing, and validation for mappings without converting SKOS mapping predicates into OWL
equivalence.

Provisional tools:

- `list_mappings`
- `add_mapping`
- `remove_mapping`
- `import_sssom`
- `export_sssom`
- `validate_mappings`

Mapping records should preserve subject, predicate, object, confidence, mapping justification, author/source,
and timestamps where present. Validation should find at least:

- Missing or deprecated source/target entities.
- Predicate or project-policy incompatibility.
- Conflicting exact mappings, prohibited many-to-one mappings, and mapping cycles.
- Unapproved or unlicensed external sources.

Before implementation, decide the supported SSSOM version and the lossless round-trip rule for unknown
extension columns.

### 4.3 Governed lifecycle

Allow policy to define states and legal transitions, for example:

```text
proposed -> reviewed -> approved -> released -> deprecated
                    \-> rejected
```

Provisional tools:

- `get_term_lifecycle`
- `transition_term_status`
- `list_terms_by_status`
- `validate_lifecycle`
- `plan_deprecation_migration`

Every transition records the principal, reason, issue reference, timestamp, prior/new status, and workspace
revision. Illegal transitions fail before mutation. Deprecation planning reports ontology references,
mappings, CQ/SHACL/SPARQL occurrences, and replacement completeness.

### 4.4 Completion conditions

- Provider failure does not block local editing unless valid project policy makes that provider required.
- Search results cannot cause automatic reuse or mint suppression based only on a fuzzy or synonym match.
- SSSOM round trips preserve all supported fields and follow the documented extension-column rule.
- An illegal lifecycle transition changes nothing.
- Project QC detects invalid transitions and deprecated terms that violate replacement or terminal-state
  policy.

## 5. M7N — Approval workflow

Add approvals independently of the existing per-write confirmation dialog. Policy should be able to require:

- Human approval for selected destructive actions.
- Approval for release preparation or external publication.
- Two distinct principals for a public release.
- Approval by a named project role.

An approval must bind to the normalized operation or change-set fingerprint, complete base revision envelope,
policy digest, requested capability, and expiry. A rebase, operation change, policy change, or revision drift
invalidates it.

The first storage format may be a hashed, owner-controlled sidecar. Do not claim cryptographic signing until
principal identity, key custody, rotation, revocation, and verification have a complete design.

### Completion conditions

- An approval cannot be replayed against different operations, revisions, policies, projects, or targets.
- Two-person policy cannot be satisfied twice by the same effective principal or grant.
- Approval checks run again immediately before mutation and are present in the audit trail without exposing
  secrets or ontology content.
- Expired, revoked, malformed, or unverifiable approvals fail closed.
- Existing single-user projects that do not enable approval policy keep their current behavior.

## 6. M8N — Rules, materialization, and asynchronous jobs

### 6.1 Reasoner and SWRL capability model

Add:

- `get_reasoner_capabilities`
- `validate_rules`
- `materialize_inferences`

Capability results should name the exact reasoner and configuration and describe supported OWL constructs,
SWRL and built-ins, DL-safety, incremental reasoning, explanations, and known incompatibilities.

Materialization requires explicit inference types, destination ontology or project file, provenance, preview,
and size limits. Its result must distinguish requested, supported, produced, and skipped inference categories;
it must never imply that a partial materialization is complete.

### 6.2 Common job model

Add a public model for long-running work:

- `start_job`
- `get_job`
- `cancel_job`
- `list_jobs`

Initial job candidates are classification/explanation, project or release QC, SHACL/SPARQL suites, semantic
diff and impact analysis, module extraction, verified serialization, release preparation, and inference
materialization.

Every job result includes:

- Job id, `workspace_id`, type, state, and created/started/completed timestamps.
- Base workspace revision and policy digest.
- Current phase, bounded progress message, and cancellation requested/effective state.
- Structured result/error and immutable artifact references.

Jobs remain owned by one backend. The id binds to its `workspace_id`; a closed window, lost broker pin,
broker restart, or request to another instance returns `unknown_job` rather than adopting mutable job state.
Completed read-only artifacts may be copied or exported explicitly.

Cancellation must be honest. If a third-party reasoner cannot stop immediately, report `cancel_pending`,
discard its late result, and prevent any commit, cache update, or release artifact from being published.

### 6.3 Completion conditions

- Unsupported reasoner/SWRL combinations are reported before or during validation with no false claim of
  coverage.
- Materialization is previewable, bounded, attributable, and never writes to the source ontology by default.
- Job status is monotonic and terminal results are immutable.
- Cancellation and principal revocation fence every mutation and artifact publication point.
- Broker routing cannot expose or control a job owned by another workspace.

## 7. M9 — External platform interoperability

M9 turns the documented manual exchange boundary in
[`docs/commercial-platforms.md`](docs/commercial-platforms.md) into a governed delivery surface. It does not
make Protégé MCP a triple-store server, reproduce a vendor authoring UI, or claim distributed transactions
with another MCP server.

### 7.1 Adapter contracts

Place vendor-neutral contracts outside the ontology-engineering core:

- `TargetCapabilities`: serialization, repository/graph addressing, transaction and optimistic-lock support,
  request limits, import behavior, reasoning/SHACL modes, staging/promotion, read-back, and replace/delete
  semantics.
- `PublishPlan`: immutable release-manifest digest, target identity, base remote revision, operation mode,
  bounded change summary, warnings, and required capabilities.
- `PublishReceipt`: product/profile/API version, target coordinates, principal/request ids, before/after remote
  revision, uploaded digest, read-back verification, and rollback or staging references.
- `TargetProfile`: the supported operations and semantic caveats for an exact detected product/API version.
  Unknown versions fail closed for writes.

Provisional tools, subject to a contract ADR:

- `inspect_target`
- `plan_publish`
- `publish_release`
- `verify_publish`
- `pull_snapshot`

Configuration stores endpoint aliases, target coordinates, profile, and secret references. Inspect, plan,
pull, publish, replace, and administration need separate capabilities; the local-admin compatibility profile
does not automatically gain remote-write access.

### 7.2 Governed publication

The first workflow publishes a verified release artifact rather than issuing arbitrary remote triple edits:

1. Run the local release gate and produce a verified artifact plus manifest.
2. Discover the exact target capabilities and version without mutation.
3. Capture the strongest honest remote concurrency token.
4. Produce a bounded plan that exposes replacement, inference inclusion, and serialization loss.
5. Recheck local digests, authorization, approval if configured, endpoint policy, and remote drift.
6. Upload to staging when supported, read back the asserted snapshot, and compare it semantically.
7. Promote only with a documented target atomic operation; otherwise leave staging for manual promotion.
8. Audit the redacted plan, receipt, and verification result.

A successful HTTP response is transport success, not semantic verification. A target without optimistic
concurrency or atomic promotion is initially create-only or staging-only; never emulate atomic replacement
with delete-then-add.

`pull_snapshot` remains read-only. Applying a remote snapshot to the live Protégé workspace still requires
local grounding, change-set preview, project QC, an exact expected revision, and explicit commit.

### 7.3 Delivery order

1. Repeatable verified file/bundle exchange and read-back fixtures.
2. A vendor-neutral RDF repository profile with create/stage/read-back behavior.
3. Named, versioned product profiles validated in licensed test environments.
4. Native approval, promote, and rollback integration only where an official maintainable API exists.

Product ordering is not an endorsement. Edition, API stability, licensing, and access to an isolated test
environment are go/no-go inputs.

### 7.4 Completion conditions

- Contract tests prove that no adapter can mutate before plan, authorization/confirmation, local digest
  recheck, and remote-drift recheck succeed.
- Each supported profile publishes and reads back a checked-in fixture; every asserted loss is zero or a
  reviewed machine-readable limitation.
- Reasoning and SHACL parity name both exact configurations and remain separate from asserted graph identity.
- Credentials are absent from policy, artifacts, receipts, logs, exceptions, and MCP results, including
  redirect and vendor-error paths.
- Remote drift cannot yield a falsely successful replacement.
- Documentation never claims atomicity, shared authorization, or shared audit with a separate platform MCP
  server.

## 8. Decisions required before implementation

Create short ADRs for the applicable choices before opening a public surface:

1. Supported SSSOM version and unknown-column round-trip behavior.
2. External-provider credential backend, cache lifetime, and query-redaction policy.
3. Approval role source, sidecar format, expiry/revocation rules, and whether signing is in scope.
4. Job persistence, retention, quotas, progress semantics, and behavior across window or process restart.
5. Minimum remote concurrency primitive for a writable target profile.
6. Mapping of ontology/release identity to repository, named graph, and vendor project coordinates.
7. Whether the first reverse-flow slice stops at immutable `pull_snapshot` or includes governed local apply.

## 9. Testing requirements for future tracks

- Unit and property tests cover state machines, canonical fingerprints, authorization, redaction, bounds,
  ordering, and fail-closed aggregation.
- Cross-component tests use the same fixtures through the shared core and every applicable adapter.
- Filesystem and network tests cover atomic replacement, symlinks, redirects, DNS/address changes, timeouts,
  retries, checksum mismatch, oversized bodies, secret-bearing errors, and remote drift.
- Reasoner-specific expectations name the engine/configuration and never become general OWL claims.
- External adapter tests use in-process fakes by default; licensed/vendor suites are opt-in, scheduled, or
  release-gated with isolated targets and least-privilege ephemeral credentials.
- Public tool, prompt, policy, and result changes pass the compatibility snapshot harness.
- The ordinary `mvn clean verify` path remains offline-capable.

## 10. Definition of done

A selected slice is complete only when:

- Public contracts, policy/schema changes, failure semantics, and migration behavior are documented.
- Authorization, confirmation/approval, audit, secret storage, egress, and path handling are reviewed.
- Mutations are revision-safe, failure-atomic, and protected against cancellation or revocation races.
- Results and artifacts are deterministic where promised and bounded everywhere.
- Unit, adversarial, cross-adapter, and applicable live Protégé tests pass.
- User documentation, architecture documentation, and changelog are updated in the same change.
- `mvn clean verify`, version consistency, performance gates, and applicable live integration checks pass.
