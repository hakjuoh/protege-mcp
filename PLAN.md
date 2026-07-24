# Protégé MCP 0.8.0 Plan and Later Roadmap

> Status: 0.8.0 implementation plan. Delivered slices remain here as release-contract context and are
> marked below; shipped behavior also belongs in [`CHANGELOG.md`](CHANGELOG.md), the user manual, and
> [`DESIGN.md`](DESIGN.md).
>
> The milestone names below preserve the identifiers used before the 0.7.1 re-baseline. M6B external
> term reuse and SSSOM mapping plus all of M8N are committed to the 0.8.0 scope; the remaining milestone
> names identify deferred product tracks, not additional 0.8.0 commitments.
>
> Local release-candidate evidence for 0.8.0 is complete: the cross-surface contracts, enforced
> performance workloads, packaged plugin, and live Protégé/MCP smoke harness pass locally. Public
> release remains pending until the advertised GitHub asset is published and the environment-specific
> positive jobs/materialization smoke evidence is available.

## 1. Scope

The current product already covers the local Protégé plugin, the Ontology Assistant, project policy and
QC, safe change sets, import locking, semantic diff and release bundles, audit and capability enforcement,
and the project-confined headless CLI. Version 0.8.0 adds two product families:

1. External term providers, explicit reuse proposals, and SSSOM mapping management (the external-term and
   mapping portions of M6B).
2. Reasoner/rule capability reporting, materialization, and a public asynchronous job API (all of M8N).

The governed term-lifecycle state machine from M6B is deferred. Named-role and two-person approvals (M7N)
are not planned for the local product: Protégé remains a single-user authoring tool, while collaborative
roles and approvals belong to the external platforms that provide those workflows. Governed publication to
those platforms (M9) remains a later track and should consume their approval evidence rather than reproduce
their identity and role systems locally.

The 0.8.0 release is complete only when both selected families form coherent vertical slices including
their public contracts, policy changes, authorization, audit behavior, tests, and documentation; tool names
alone are not deliverables.

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

## 3. Release scope and later tracks

| Track | Disposition | Outcome | Primary dependencies |
| --- | --- | --- | --- |
| M6B external terms + mappings | **0.8.0** | External term providers, explicit reuse proposals, and SSSOM mapping management | Existing policy, search, change sets, QC, and audit |
| M6B governed lifecycle | Deferred | Policy-defined term lifecycle and deprecation migration | M6B mappings plus a demonstrated local-authoring need |
| M7N | Not planned for the local product | Named-role and two-person approvals remain the responsibility of collaborative external platforms | A future multi-user deployment profile and threat model would be required to reconsider this |
| M8N | **0.8.0** | Rule/reasoner capability model, materialization, and asynchronous jobs | Existing cancellation fences, isolated reasoners, release/QC services, and broker session pinning |
| M9 | Later release | Drift-safe external publication and read-back verification | Existing verified releases, capabilities, audit, headless workspace, and target-platform approval evidence where applicable |

Both 0.8.0 tracks must ship together; completing only M6B or only M8N does not complete 0.8.0. Later tracks
must reuse the released contracts where applicable. In particular, M9 must use the M8N public job model if
publication is asynchronous, and it must not invent a local substitute for a target platform's approval
workflow.

### 3.1 Surface and package ownership

| Capability | Live plugin | Headless stdio | One-shot CLI | Ownership |
| --- | --- | --- | --- | --- |
| External provider search/inspect/proposal | Yes | No | No | Provider-neutral contracts/SPI in `core`; credential, HTTPS, cache, and live-workspace adapters in `plugin` |
| SSSOM list/add/remove/import/export/validate | Yes | Yes | Import/export/validate commands | SSSOM records, parser, validator, and transactional store contract in `core`; EDT/revision capture in `plugin`; filesystem lock/guarded no-overwrite publication in headless adapters |
| Reasoner capabilities and rule validation | Yes | Yes | Validation command | Exact capability vocabulary and validation in `core`; installed-reasoner discovery/configuration capture in adapters |
| Inference materialization | Yes | Yes | Synchronous preview/file command | Snapshot computation and reports in `core`; one model-manager broadcast with one Undo unit for active-source axiom commits and a separate non-Undo ontology-creation lifecycle; atomic project-file publication in headless adapters |
| Asynchronous jobs | Yes | No in 0.8.0 | No | State machine/contracts in `core`; one per-window in-memory runtime in `plugin` |

The headless stdio surface remains bounded and offline. It does not receive credentialed external-provider
networking or ephemeral job ids. One-shot CLI commands stay synchronous because their process lifetime cannot
honestly own a public asynchronous job. Every new built-in tool is declared in the shared catalog, central
capability map, documentation, and immutable compatibility snapshots for each applicable adapter.

### 3.2 Delivery and independent-review gates

Implementation proceeds in this order:

1. Freeze the 0.7.2 plugin/headless contracts and add typed 0.8 result/error schemas.
2. Add policy v2 with byte-for-byte v1 normalization/digest compatibility and explicit migration diagnostics.
3. Add the SSSOM parser/validator and read-only round trip, then transactional mapping mutations.
4. Add the provider SPI, one checked-in fake, the OLS4 REST profile, centralized egress/credential/cache
   enforcement, and the three provider tools.
5. **Delivered:** add exact reasoner/SWRL capabilities and rule validation.
6. **Delivered:** add materialization preview/artifacts, then explicit ontology/file commit paths.
7. **Delivered:** add the job contracts/runtime with classification, project QC, semantic diff, and
   materialization jobs.
8. **Delivered:** complete cross-surface, performance, packaged-plugin, and live Protégé evidence.

Each numbered implementation slice includes production code, adversarial and regression tests, docs, and
the applicable compatibility snapshots. Before its commit, three independent reviewers and `claude_review`
inspect the same diff. Findings are fixed and the review repeats until every reviewer scores the slice at
least 9.5/10. A slice is committed only after that convergence and its focused tests pass. After all slices,
the same process repeats over the complete 0.8.0 delta before the release-readiness checks run.

### 3.3 Contract and compatibility baseline

Before adding a tool, record immutable 0.7.2 baselines for the 85-tool plugin surface and eight-tool headless
surface (`get_headless_capabilities` plus seven project/audit/release tools). Version 0.8.0 adds JSON Schemas
for nested success results and a common error envelope with a stable
`code`, bounded `message`, optional non-secret `details`, and `retryable`; free-text exception messages never
form the public contract. Snapshots cover tool metadata, input/output/error schemas, policy v1/v2, job states,
and both adapters. Later versions may add optional fields and enum values only where the schema explicitly
declares extensibility; removal or semantic replacement requires a versioned contract.

## 4. M6B — External reuse and mappings (0.8.0)

### 4.1 External term providers

Define a provider SPI for services such as OLS, BioPortal, LOV, and project-specific registries.

Version 0.8.0 ships one supported network profile, OLS4 REST, plus an in-process fake used by the complete
contract suite. BioPortal, LOV, and custom network profiles remain SPI targets rather than claimed supported
adapters. Provider networking is plugin-only in 0.8.0.

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

All provider I/O crosses one centralized executor. It accepts HTTPS origins only, resolves and checks every
address against the existing private/link-local/loopback and endpoint policy before connection, rechecks DNS
results, does not inherit ambient proxy credentials, and disables credentialed redirects. An uncredentialed
redirect is followed only after the new origin is independently authorized. Provider code never receives a
raw HTTP client or raw credential.

Policy may request a `credential_id`, but only owner-controlled local configuration may bind that id to a
provider, exact HTTPS origin, authentication scheme, and optional project fingerprint. A project cannot
select an arbitrary endpoint for an existing credential. Secrets are header-only, stripped before redirects,
kept out of cache keys and exception text, and redacted by both key patterns and registered secret-value
canaries at every sink. Provider caches are owner-only and partitioned by provider/origin/project plus a
non-secret credential generation/scope fingerprint. Sensitive normalized queries are keyed with an
owner-local HMAC, not plain SHA-256. Every hit rechecks policy, endpoint, credential binding/generation, and
network authority; rotation/deletion invalidates its partition. Caches are bounded by entries/bytes/TTL,
never cache authorization headers or opaque signed URLs, and discard invalid or oversized responses atomically.

`ProviderResult` contains provider/profile, source ontology, entity IRI/type, labels/synonyms with language,
license/provenance, match explanation/score, provider version/timestamp/source URL, and an opaque result
fingerprint. Results are deterministically ordered and deduplicated. Pagination uses bounded opaque cursors;
partial multi-provider success is never reported because 0.8.0 queries exactly one provider per request.
429 honors bounded `Retry-After`; retries apply only to idempotent reads and are disclosed.

Public 0.8.0 tools:

- `search_external_terms`
- `inspect_external_term`
- `propose_term_reuse`
- `accept_reuse_proposal`

`ReuseProposal` is read-only and memory-only: a 15-minute, principal/workspace-scoped immutable record bound
to the provider-result fingerprint, complete model/input identity, policy digest, requested action, and
normalized suggested operations. The supported actions are `reuse_iri`, `add_mapping`, and
`mint_local_with_mapping`. The proposal never edits the ontology or mapping store. Acceptance uses the explicit
`accept_reuse_proposal` tool and rechecks revision, policy, authorization, confirmation, and expiry.
`reuse_iri` returns a non-mutating receipt; `add_mapping` performs one mapping CAS.
`mint_local_with_mapping` is a two-step saga: the ontology commit returns its new revision and continuation,
then the mapping CAS binds to that receipt and original mapping revision. If step two fails, the minted term
remains and the result is `partial` with an exact retry/manual-recovery continuation; it never claims
cross-resource atomicity or silently rolls back a later edit. There is no hidden MIREOT/import or lifecycle
transition.

Provider cursors and proposals are owner/grant/workspace scoped. Cursors expire after five minutes and are
limited to 32 per principal, 256 per backend, and 256 KiB each. Proposals expire after 15 minutes and are
limited to 64 per principal, 256 per backend, and 256 KiB each. Revocation, window close, or restart deletes
both; exhaustion returns `cursor_quota_exceeded` or `proposal_quota_exceeded` without evicting an active
continuation.

### 4.2 SSSOM mapping management

Add import, export, editing, and validation for mappings without converting SKOS mapping predicates into OWL
equivalence.

Version 0.8.0 supports SSSOM 1.0 TSV. The canonical store is one policy-v2-declared, project-confined sidecar
(default `.protege-mcp/mappings.sssom.tsv`), never ontology axioms. Mapping writes therefore do not enter the
Protégé Undo stack or alter the ontology revision; they use a separate SHA-256 mapping revision included in
complete job/proposal input identity. Every mutation requires the expected mapping revision and uses the same
advisory project lock in both live and headless adapters plus temporary-file verification, guarded
no-overwrite hard-link publication,
and backup/recovery. Concurrent or stale
writes fail with `mapping_revision_conflict` and change nothing.

Public 0.8.0 tools:

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

`MappingRecord` has a stable `mapping_id`, subject, predicate, object, confidence, justification,
author/source, timestamps, and ordered extension columns. Imported rows without an id receive a deterministic
id over their normalized identity fields; exact duplicate ids/rows are idempotent, while an id with different
content is a conflict. Supported mapping predicates are the SSSOM 1.0 mapping-predicate vocabulary plus a
policy allowlist; SKOS mapping predicates remain annotations in the sidecar and never become OWL equivalence.
Unknown columns and their row affinity round-trip losslessly in the store. Canonical new output is UTF-8
without BOM, LF, deterministic header/row order, with explicit byte/row/column/cell limits. Spreadsheet
formula-looking cells are preserved for standards compliance but produce a validation warning; the explicit
`spreadsheet_safe` export mode prefixes them and is labelled non-lossless.

`import_sssom` is validate-then-replace/merge and all-or-nothing. `add_mapping`, `remove_mapping`, and import
are confirmation-gated filesystem mutations. Missing/deprecated entities and policy/license violations are
errors for mutations when policy marks the corresponding rule required; otherwise they are stable validation
findings. Conflicting exact mappings and malformed ids always block mutation. Symmetric predicates
(`exactMatch`, `closeMatch`, `relatedMatch`, OWL equivalence/same-as) are undirected: reverse duplicates and
self maps are not cycles. Directional broad/narrow predicates normalize to narrow-to-broad edges; an SCC of
two or more distinct terms is a cycle. Policy v2 chooses `allow|warning|error` per directional predicate
(default `error`). Many-to-one restrictions apply only to predicates and subject-ontology/provider/target
scopes explicitly named by policy; otherwise cardinality is not prohibited. CURIE expansion uses only the
SSSOM prefix map plus policy-approved prefixes and never guesses.

All six mapping tools are registered for v1, v2, and no-policy projects. V2 uses its declared/default store
and governance. V1 requires an explicit path confined to its canonical `project_root` and applies structural
SSSOM validation without v2-only governance. With no policy, an explicit path must remain under the active
document directory and the existing unrestricted local-admin compatibility preference must permit project
access; invalid policy or the restricted preference fails closed.

`export_sssom` writes a project-confined destination, requires project-write capability and confirmation,
defaults to no-clobber, and requires `overwrite=true` plus the expected target digest to replace. It takes the
project lock, rejects symlink escapes, verifies bytes by reparsing, and atomically replaces with backup. The
same no-clobber/expected-digest contract applies to `export_job_artifact`.

### 4.3 Governed lifecycle (deferred; not in 0.8.0)

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

### 4.4 0.8.0 completion conditions

- Provider failure never blocks unrelated editing, QC, or release work. It blocks only the provider request,
  a reuse/mint decision that explicitly requires fresh provider evidence, or a policy stage explicitly scoped
  to that evidence.
- Search results cannot cause automatic reuse or mint suppression based only on a fuzzy or synonym match.
- SSSOM round trips preserve all supported fields and follow the documented extension-column rule.
- Provider, reuse-proposal, and mapping operations satisfy the shared authorization, audit, redaction,
  bounds, and compatibility requirements.

The following conditions belong to the deferred governed-lifecycle slice rather than 0.8.0:

- An illegal lifecycle transition changes nothing.
- Project QC detects invalid transitions and deprecated terms that violate replacement or terminal-state
  policy.

## 5. M7N — Local approval workflow (not planned)

Protégé MCP is a loopback-first, single-user authoring product. Local MCP principals provide capability and
audit boundaries, but they are not an organizational identity directory: one local operator can control
multiple principals, so a local two-person rule would not provide trustworthy separation of duties.
Collaborative ontology and knowledge-graph platforms already own named roles, approval workflow, identity
proofing, revocation, and organizational audit.

For those reasons, M7N is not part of 0.8.0 and is not a planned standalone local subsystem. The existing
write confirmation, capabilities, revision-safe change sets, release gate, and audit trail remain the local
safety model. M9 may later verify target-platform approval evidence or call an official approval/promote API,
but it must not duplicate that platform's role directory or claim that locally created principals prove two
distinct human approvers.

Reconsider a local approval subsystem only if the product gains a separate supported multi-user deployment
profile and threat model. That future plan would first need trustworthy human identity, role authority, key
custody, rotation, revocation, and verification contracts; the current local principal model is not evidence
for any of those properties.

## 6. M8N — Rules, materialization, and asynchronous jobs (0.8.0)

### 6.1 Reasoner and SWRL capability model

Add:

- `get_reasoner_capabilities`
- `validate_rules`
- `materialize_inferences`
- `commit_materialization`

Capability results should name the exact reasoner and configuration and describe supported OWL constructs,
SWRL and built-ins, DL-safety, incremental reasoning, explanations, and known incompatibilities.

`ReasonerCapabilityReport` distinguishes `supported`, `unsupported`, `unknown`, and `untested` for each
capability; absence is never interpreted as support. The registry is keyed by exact factory id, version,
configuration digest, and buffering mode. Version 0.8.0 has reviewed profiles for HermiT `1.3.8.431`, OWLAPI
structural reasoner `4.5.29`, and ELK `0.5.0` with factory id
`org.semanticweb.elk.owlapi.ElkReasonerFactory`; every other reasoner/version produces an explicit unknown
profile while still reporting discoverable metadata. `validate_rules` parses every atom and built-in, reports
DL-safety and exact profile coverage per rule, and never executes the rule. Plugin-defined or otherwise
side-effecting filesystem/network built-ins are always `unsupported` for validation/materialization.

Materialization requires explicit inference types, destination ontology or project file, provenance, preview,
and size limits. Its result must distinguish requested, supported, produced, and skipped inference categories;
it must never imply that a partial materialization is complete.

`MaterializationReport` names the complete input identity, exact reasoner/configuration, requested/supported/
produced/skipped categories, per-category counts and truncation, provenance, destination plan, and verified
artifact digest. The closed 0.8.0 categories are subclass/equivalent-class axioms, class assertions, property
hierarchy axioms, and object/data property assertions. Any requested `unsupported`/`unknown` category makes
the preview an error; a category that crosses its bound is discarded atomically and makes the preview an
error, never a partial success.

Preview is mandatory and writes only a private immutable artifact. `materialize_inferences` is read-only;
`commit_materialization` is the only mutating tool and consumes an artifact produced by synchronous preview or
the materialization job through the same owner-scoped contract. A preview expires after 30 minutes and shares
the job artifact quotas and cleanup. Commit consumes its
fingerprint and rechecks the complete input identity, policy, dynamic authorization, confirmation, cancellation,
and destination revision under one commit permit. The default destination is a new ontology/file; the active
source ontology is rejected unless `allow_source=true` is explicit and policy permits it. Plugin axiom changes
use one model-manager broadcast; active-source commits are one Undo unit, while a newly created ontology is
reported separately because ontology creation itself is not an Undo-stack operation. File output uses project lock, verified serialization, temporary-file digest, atomic
replacement, and backup. Generated axioms use a stable provenance IRI derived from source, reasoner, category,
and content digests. Per-run timestamps and job/preview ids live in report/audit rather than axiom identity.
Recommitting the same preview or stable materialization is a no-op for an existing logical axiom plus
provenance-id pair when no alternate form remains; explicit merge may no-op while retaining alternates.
A different provenance digest is a collision requiring explicit merge/replace. All
asserted/inferred collisions are reported before commit.

### 6.2 Common job model

Add a public model for long-running work:

- `start_job`
- `get_job`
- `cancel_job`
- `list_jobs`
- `export_job_artifact`

The exact 0.8.0 job types are `classification`, `project_qc`, `semantic_diff`, and
`inference_materialization`. Existing synchronous tools remain supported and unchanged. Explanation,
standalone SHACL/SPARQL suites, impact analysis, module extraction, verified serialization, and release
preparation remain synchronous in 0.8.0 and may adopt the public job model later without adding private job
contracts.

Every job result includes:

- Job id, `workspace_id`, type, state, and created/started/completed timestamps.
- Base workspace revision and policy digest.
- Current phase, bounded progress message, and cancellation requested/effective state.
- Structured result/error and immutable artifact references.

`JobDescriptor` includes the exact owner principal/client/grant fingerprint, idempotency key, required
capabilities, `JobInputIdentity`, progress sequence, and typed result discriminator. `JobInputIdentity`
contains the complete model revision, closure/import-lock/mapping/policy/preflight-asset digests, exact
reasoner/configuration digest, normalized request digest, and immutable byte/provenance digests for every
secondary semantic-diff input captured once without later refetch. `start_job` requires an idempotency key.
For 15 minutes from acceptance, the same owner/workspace/type/key with identical input identity returns the
existing job; the same key with different identity returns `idempotency_conflict`; a different key starts a
new job even for identical content. Cancelled, failed, and succeeded jobs follow the same rule until expiry.

The closed state machine is defined by this table; the diagram is illustrative only:

| From | To | Cause |
| --- | --- | --- |
| `queued` | `running` | Worker claim |
| `queued` | `cancelled` | Cancel/revoke/expiry/shutdown before claim |
| `queued` | `failed` | Executor rejection or pre-start capture failure |
| `running` | `succeeded` | Verified commit/publication completes |
| `running` | `failed` | Computation fails before cancellation |
| `running` | `cancel_pending` | Tombstone set before work stops |
| `running` | `cancelled` | Cooperative cancellation stops immediately |
| `cancel_pending` | `cancelled` | Work stops or late output is discarded |

No other transition is legal; a failure observed after `cancel_pending` is diagnostic and terminal state is
still `cancelled`.

```text
queued -> running -> succeeded | failed
   |         |  \
   |         |   -> cancel_pending -> cancelled
   |         -> cancelled
   -> cancelled | failed
```

Terminal states are immutable. Cancellation is idempotent. Cancel/revoke/expiry/shutdown first CAS a monotonic
tombstone without waiting for interactive work. A worker completes blocking confirmation outside the permit,
then enters a short exclusive section, rechecks every guard/tombstone, and CASes `commit_started` immediately
before the first irreversible mutation/publication. Cancellation before `commit_started` wins; after it,
cancellation returns `commit_in_progress` and never claims success. The commit section covers mutation,
artifact export, or cache publication through terminal state.
Non-interruptible reasoners remain `cancel_pending` until their result is discarded, then become `cancelled`.
Executor rejection becomes `failed` with `job_queue_full`; it is never left queued.

Jobs are in-memory and non-persistent in 0.8.0. A window close or backend/broker restart requests cancellation,
disposes workers, deletes unexported private artifacts, and makes every prior id `unknown_job`. Jobs are visible
and controllable only by the exact owning principal/grant in the owning workspace; every mismatch also returns
`unknown_job`. Revocation of that owner cancels the job. The runtime uses two workers, a 32-entry backend queue,
at most eight active and 32 retained jobs per principal, 128 retained jobs per backend, one-hour terminal
retention, bounded progress text, and cursor-paged newest-first lists of at most 100 rows. One job may own four
artifacts, 64 MiB each/128 MiB total, 1 MiB result JSON, and 128 MiB staging; backend staging is capped at
512 MiB, progress text at 1 KiB, and cancellation grace at five seconds. Async reasoner jobs accept only exact
profiles whose live cancellation test proves stop/disposal within that grace; other profiles fail up front
with `job_reasoner_not_cancellable` instead of holding a worker indefinitely.

Token refresh within the same grant preserves ownership. Re-consent/re-grant creates a new fingerprint and
cannot observe old jobs; revocation of the old grant cancels them. `start_job` resolves capabilities dynamically
from job type and destination; `ontology:read` is insufficient for network, curate, filesystem-write, or
release work. Authorization/revocation guards apply to every publication; interactive confirmation applies
only to ontology/filesystem mutation and explicit artifact export, never read-only result/cache publication.
Audit emits accepted, started, progress-summary, cancel-requested, cancel-effective,
publication, and terminal events with no ontology/query/secret content. `export_job_artifact` copies a private,
content-addressed artifact to an authorized project path after ownership, digest, retention, capability,
confirmation, no-clobber/expected-target-digest, and atomic-write checks; artifact ids never expose filesystem
paths. A failed pre-commit audit-intent prevents mutation. If terminal audit fails after a real commit, state
remains `succeeded` with `audit_incomplete=true`; the operation is never exposed as retryable failed.

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
- Principal/grant mismatch is indistinguishable from an absent job, and revocation fences detached work.
- Queue, retention, artifact, result, and progress limits remain bounded under overload and shutdown.

## 7. M9 — External platform interoperability (later release)

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
5. Recheck local digests, authorization, confirmation, target-platform approval evidence if applicable,
   endpoint policy, and remote drift.
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
4. Native target-platform approval, promote, and rollback integration only where an official maintainable
   API exists.

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

Create short ADRs for these 0.8.0 choices before opening a public surface:

1. [`0.8 external-provider security and cache`](docs/adr/0.8-external-provider-security.md).
2. [`0.8 SSSOM mapping store and reuse proposals`](docs/adr/0.8-sssom-mapping-store.md).
3. [`0.8 asynchronous job runtime`](docs/adr/0.8-job-runtime.md).
4. [`0.8 reasoner capabilities and materialization`](docs/adr/0.8-reasoner-capabilities-materialization.md).
5. [`0.8 contracts, policy v2, and compatibility`](docs/adr/0.8-contracts-policy-v2.md).

Before a deferred track begins, create its applicable ADRs:

1. Governed-lifecycle state representation, transition authority, and audit/provenance model.
2. Minimum remote concurrency primitive for a writable target profile.
3. Mapping of ontology/release identity to repository, named graph, and vendor project coordinates.
4. Whether the first reverse-flow slice stops at immutable `pull_snapshot` or includes governed local apply.
5. Target-platform approval evidence and promote/rollback integration, limited to official maintainable APIs.

## 9. Testing requirements for 0.8.0 and later tracks

- Unit and property tests cover state machines, canonical fingerprints, authorization, redaction, bounds,
  ordering, and fail-closed aggregation.
- Cross-component tests use the same fixtures through the shared core and every applicable adapter.
- Filesystem and network tests cover guarded file publication, atomic directory replacement, symlinks,
  redirects, DNS/address changes, timeouts,
  retries, checksum mismatch, oversized bodies, secret-bearing errors, and remote drift.
- Reasoner-specific expectations name the engine/configuration and never become general OWL claims.
- External adapter tests use in-process fakes by default; licensed/vendor suites are opt-in, scheduled, or
  release-gated with isolated targets and least-privilege ephemeral credentials.
- Public tool, prompt, policy, and result changes pass the compatibility snapshot harness.
- The ordinary `mvn clean verify` path remains offline-capable.

The 0.8.0 release evidence additionally requires:

- Checked-in OLS4/fake-provider fixtures covering filters/languages/pagination/order/deduplication, 429/5xx,
  timeout/retry, malformed/oversized bodies, redirects, DNS/address change, credential stripping, secret-bearing
  vendor errors, TTL/eviction, and cross-project cache isolation. Tests never call a live provider; scheduled
  canaries verify only adapter/API shape, not volatile rankings.
- SSSOM fixtures covering minimal/full 1.0 rows, all supported fields, extensions, BOM/CRLF/Unicode/quoted
  cells, CURIE maps, duplicates/conflicts/cycles, deprecated/missing terms, license policy, formula warnings,
  limits, stale checksums, atomic failure, and lossless round trip.
- Fake-clock/executor job tests with barriers at compute, staging, commit permit, artifact publication, cache
  publication, and shutdown; races include cancel/revoke/expiry/window close/broker restart, two principals,
  two workspaces, executor rejection, non-interruptible-profile rejection, delayed-but-bounded reasoner,
  late result, retention, and every idempotency-key case.
- Materialization fixtures for every category and support state, inference explosion, whole-category discard,
  source refusal, preview/commit drift, one-Undo active-source commit, non-Undo new-ontology creation, atomic headless output, provenance, idempotence,
  and serialization/fingerprint verification.
- Immutable 0.7.2 and 0.8.0 tool/prompt/input/output/error/policy/job snapshots and cross-surface conformance.
  New `core` and `plugin` packages require at least 80% line/75% branch coverage and new `cli` adapters 75%/70%;
  each module's verified ratio may not regress more than 0.5 percentage points from its 0.7.2 baseline.
- Versioned performance fixtures use an OLS page set of 1,000 results, SSSOM files through 100,000 rows/64 MiB,
  a saturated 32-job queue, and materializations through 50,000 produced axioms. Once baselined, regression is
  capped at 2.0x with a 250 ms noise floor; incremental heap is capped at 512 MiB, model-thread/EDT stalls at
  100 ms, and cancellation effectiveness at five seconds. Offline verification runs in network-isolated CI
  after dependency priming.
- The live Protégé harness adds a loopback fake provider, start/poll/cancel and late-result fencing, two-window
  job isolation, close/restart behavior, materialization preview/commit/Undo, atomic project-file output, and
  tool/schema counts. The OLS4 shape canary must pass within seven days of release or block the release.
  Packaged checks run Protégé 5.6.6 on macOS 14 ARM64 and Windows 11 23H2 x64 with Temurin 17.0.19+7; they cover
  credential create/use/rotate/delete, owner-only permissions, install/upgrade, broker/worker cleanup, and
  cancellation. Structured logs/results are retained as CI artifacts for 30 days. Unsupported credential
  backends fail closed and are documented.
- Every regression fix demonstrates pre-fix-red or an equivalent targeted mutant. Each slice and the final
  aggregate receive three independent adversarial reviews plus `claude_review`, all at least 9.5/10.

## 10. Definition of done

Version 0.8.0 is complete only when both selected product families meet every applicable condition below.
A later slice is complete only when it meets the same applicable conditions:

- Public contracts, policy/schema changes, failure semantics, and migration behavior are documented.
- Authorization, confirmation, audit, secret storage, egress, and path handling are reviewed; a later M9
  slice also reviews target-platform approval evidence where applicable.
- Mutations are revision-safe, failure-atomic, and protected against cancellation or revocation races.
- Results and artifacts are deterministic where promised and bounded everywhere.
- Unit, adversarial, cross-adapter, and applicable live Protégé tests pass.
- User documentation, architecture documentation, and changelog are updated in the same change.
- `mvn clean verify`, version consistency, performance gates, and applicable live integration checks pass.
