# Protégé MCP Post-0.8.0 Roadmap

> Status: roadmap for work after 0.8.0. Shipped behavior belongs in [`CHANGELOG.md`](CHANGELOG.md),
> the user manual, and [`DESIGN.md`](DESIGN.md).

## 1. Shared product constraints

All future work must preserve these boundaries:

- OWL is the source of logical meaning. SHACL, SPARQL, competency questions, and project policy add
  closed-world or governance requirements; none silently replaces another.
- A write is resolved and checked against an isolated snapshot, then committed only after the live
  workspace revision, policy digest, authorization, and confirmation are rechecked.
- Release-critical inputs and evidence are explicit and reproducible. A required check that cannot run is
  an error, not a pass or an unexplained skip.
- Shared semantics live in `core`; delivery adapters own their environment-specific transaction behavior.
- The desktop product remains loopback-first and single-user by default. Remote multi-user hosting needs a
  separate deployment profile and threat model.
- Credentials are referenced by identifier and stored outside policy, ontology documents, manifests, logs,
  audit exports, and MCP results.
- Large results are bounded, pageable, or artifact-backed. Cancellation never permits a stale result to
  mutate the workspace or become current evidence.

## 2. M6B — Governed term lifecycle

Use the released external-term and mapping contracts to add a policy-defined term lifecycle:

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

Every transition records the principal, reason, issue reference, timestamp, prior/new status, and
workspace revision. Illegal transitions fail before mutation. Deprecation planning reports ontology
references, mappings, CQ/SHACL/SPARQL occurrences, and replacement completeness.

Required completion conditions:

- An illegal lifecycle transition changes nothing.
- Project QC detects invalid transitions and deprecated terms that violate replacement or terminal-state
  policy.
- Lifecycle state, policy changes, authorization, audit, migration behavior, and user documentation have
  stable public contracts and compatibility coverage.

## 3. M9 — External platform interoperability

M9 turns the documented manual exchange boundary in
[`docs/commercial-platforms.md`](docs/commercial-platforms.md) into a governed delivery surface. It does
not make Protégé MCP a triple-store server, reproduce a vendor authoring UI, or claim distributed
transactions with another MCP server.

### 3.1 Adapter contracts

Place vendor-neutral contracts outside the ontology-engineering core:

- `TargetCapabilities`: serialization, repository/graph addressing, transaction and optimistic-lock support,
  request limits, import behavior, reasoning/SHACL modes, staging/promotion, read-back, and replace/delete
  semantics.
- `PublishPlan`: immutable release-manifest digest, target identity, base remote revision, operation mode,
  bounded change summary, warnings, and required capabilities.
- `PublishReceipt`: product/profile/API version, target coordinates, principal/request ids, before/after
  remote revision, uploaded digest, read-back verification, and rollback or staging references.
- `TargetProfile`: supported operations and semantic caveats for an exact detected product/API version.
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

### 3.2 Governed publication

The first workflow publishes a verified release artifact rather than issuing arbitrary remote triple edits:

1. Run the local release gate and produce a verified artifact plus manifest.
2. Discover exact target capabilities and version without mutation.
3. Capture the strongest honest remote concurrency token.
4. Produce a bounded plan that exposes replacement, inference inclusion, and serialization loss.
5. Recheck local digests, authorization, confirmation, target-platform approval evidence where applicable,
   endpoint policy, and remote drift.
6. Upload to staging when supported, read back the asserted snapshot, and compare it semantically.
7. Promote only with a documented target atomic operation; otherwise leave staging for manual promotion.
8. Audit the redacted plan, receipt, and verification result.

A successful HTTP response is transport success, not semantic verification. A target without optimistic
concurrency or atomic promotion is initially create-only or staging-only; never emulate atomic replacement
with delete-then-add.

`pull_snapshot` remains read-only. Applying a remote snapshot to the live Protégé workspace still requires
local grounding, change-set preview, project QC, an exact expected revision, and explicit commit.

### 3.3 Delivery order

1. Repeatable verified file/bundle exchange and read-back fixtures.
2. A vendor-neutral RDF repository profile with create/stage/read-back behavior.
3. Named, versioned product profiles validated in licensed test environments.
4. Native target-platform approval, promote, and rollback integration only where an official maintainable
   API exists.

Product ordering is not an endorsement. Edition, API stability, licensing, and access to an isolated test
environment are go/no-go inputs.

### 3.4 Completion conditions

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

## 4. Decisions required before future implementation

Before a deferred track begins, create its applicable ADRs:

1. Governed-lifecycle state representation, transition authority, and audit/provenance model.
2. Minimum remote concurrency primitive for a writable target profile.
3. Mapping of ontology/release identity to repository, named graph, and vendor project coordinates.
4. Whether the first reverse-flow slice stops at immutable `pull_snapshot` or includes governed local apply.
5. Target-platform approval evidence and promote/rollback integration, limited to official maintainable APIs.

## 5. Testing and definition of done

- Unit and property tests cover state machines, canonical fingerprints, authorization, redaction, bounds,
  ordering, and fail-closed aggregation.
- Cross-component tests use the same fixtures through shared core contracts and every applicable adapter.
- Filesystem and network tests cover guarded publication, symlinks, redirects, DNS/address changes,
  timeouts, retries, checksum mismatch, oversized bodies, secret-bearing errors, and remote drift.
- Public tool, prompt, policy, and result changes pass the compatibility snapshot harness.
- The ordinary `mvn clean verify` path remains offline-capable.
- Every regression fix demonstrates pre-fix-red or an equivalent targeted mutant.
- Public contracts, policy/schema changes, failure semantics, and migration behavior are documented.
- Mutations are revision-safe, failure-atomic, and protected against cancellation or revocation races.
- Results and artifacts are deterministic where promised and bounded everywhere.
- User documentation, architecture documentation, and changelog are updated in the same change.
- `mvn clean verify`, version consistency, performance gates, and applicable live integration checks pass.
