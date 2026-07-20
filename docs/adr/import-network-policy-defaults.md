---
title: "Import network policy defaults and request controls"
published: false
nav_exclude: true
---

# Import network policy defaults and request controls

- Status: accepted
- Date: 2026-07-17

## Context

Since `0.6.1`, policy-aware document loading consumes the released policy fields: `network.default`
(schema default `deny`), `network.allowed_hosts`, and the `imports.network` override (defaulted by the
loader to the effective `network.default`). A denied remote import is stopped before dereference with an
attribution string (precise except for the invalid-policy blocker case corrected in decision 3), host
allowlists disable unchecked redirects, and an invalid loaded policy
fail-closes network authorization — thrown as an invalid-policy refusal on the direct document-loading
path, and consulted non-exceptionally (deny without throwing) by the import blockers. Without a project
policy, the documented local-admin compatibility profile leaves loading unrestricted, and a preference
can disable that compatibility mode.

Two request-level controls are planned for `0.7.0` (`network=deny|allow` on document-loading operations
and gates, `lock_mode=ignore|verify|required` on project/release gates and document-loading operations),
and the release workflow (`run_release_gate`, `prepare_release`) needs a defined network posture. The
open question this decision resolves is the default import network policy for interactive versus release
mode, and how the request-level controls compose with the released policy semantics.

The relevant released precedents are strictly may-only-tighten: `apply_changes timeout_ms` can only
tighten a policy's `reasoning.timeout_ms`, and `preview_change_set gates` is honored only when no policy
is loaded — a valid policy's required stages are absolute. There is no request-level loosening path
anywhere in the released surface. Offline import resolution continues to rely on OASIS XML Catalogs and
workspace mappings, both already hardened against pre-authorization dereference.

Two released facts constrain the design and are easy to get wrong. First, gates never fetch: QC
evaluates the already-loaded workspace closure, so a gate-time "network off" switch alone proves nothing
about where the loaded content came from. Second, there is no single released lockfile-resolution rule:
the locked gate resolves only the policy-declared `imports.lockfile` asset, while the
`verify_import_lock` tool falls back to the beside-active-document `imports.lock.json` only when no
lockfile is declared or no policy is loaded, and aborts (rather than reporting "no lockfile") in its
refusal states, including a loaded-but-invalid policy.

## Decision

1. **Interactive defaults are unchanged.** With a valid policy, the effective network posture remains
   `network.default` (schema default `deny`) with `imports.network` and `network.allowed_hosts` layered
   as released. Without a policy, the local-admin compatibility profile remains unrestricted, subject to
   the existing opt-out preference. `0.7.0` does not change any released default.
2. **Release-surface `network=deny` is a provenance requirement, not a fetch switch.** The release gate
   and release preparation default to `network=deny`. Because gates never fetch, `deny` is enforced
   against the closure's provenance: every import in the evaluated closure must be backed by an
   authorized local file, and a remote-backed member (a document that did not resolve to an authorized
   local file) is an error finding (`imports.remote_backed`). An explicit `network=allow` — still
   subject to policy and capability, never exceeding them — downgrades those findings to a recorded
   reproducibility caveat. Every release result, on every path, lists the resolved imports actually
   used with their document sources; with `imports.mode: locked`, gate-time lock verification applies
   regardless of the network setting.
3. **Request controls compose most-restrictive-wins, with denial-source attribution.** The effective
   network permission is the conjunction of: the request argument (`deny` denies; `allow` merely
   abstains from denying), the policy (`network.default` / `imports.network` / `allowed_hosts`), the
   principal's `network:access` capability, and — with no policy — the local-admin profile together
   with the compatibility preference. `network=allow` therefore never overrides a policy `deny`, an
   invalid-policy fail-closed posture, a missing capability, or a restricted no-policy state; under an
   unrestricted no-policy profile it is a no-op affirmation. The composed rule must carry a
   denial-source discriminator so every denial is attributed to its actual source — request
   (`request network=deny`), policy, host allowlist, capability, invalid policy, or compatibility
   preference. This corrects the released import-blocker path that misattributes an invalid-policy
   denial to a missing capability; the request-level work introduces the discriminator once for all
   sources rather than adding another ambiguous string.
4. **`lock_mode` never weakens policy, and its lockfile resolution is explicit.** With
   `imports.mode: locked`, gate-time lock-content verification always runs, whatever the request says;
   `lock_mode=ignore` (the absent-argument default) expresses no request-level opinion and cannot
   disable a policy-mandated check. Otherwise:
   - *Resolution.* `lock_mode=verify|required` resolve the lockfile with the released tool rules: the
     policy-declared `imports.lockfile` asset when exactly one resolves; the beside-active-document
     `imports.lock.json` default only when no lockfile is declared or no policy is loaded; the released
     refusal states (declared-but-unresolved lockfile, invalid policy, no local document folder) abort
     the request rather than emitting a finding.
   - *Semantics.* `verify` runs the same coordinate/SHA-256 comparison the locked gate uses and skips
     cleanly, with a reported note, when the resolved default file does not exist; `required` turns
     exactly that file-absent state into an error finding (`imports.lock_missing`). Mismatches reuse
     the pinned codes (`imports.lock_mismatch`, `imports.unresolved`). The loaded-content attestation
     (`imports.loaded_content_divergence`) remains gate-only: it requires the closure fingerprint
     captured in the same model-thread hop as the QC snapshot, which loading operations do not have.
     The gate verification entry point gains an externally resolved lock path for this; it must not
     silently widen the locked-policy resolution rule.
   - *Trust labeling.* Results state the lockfile source (`policy_declared` or `beside_document`). A
     lockfile that is not policy-pinned is co-located with — and writable by — whoever supplied the
     ontology folder, so its verification attests accident-safety (unchanged since lock time), not
     tamper-evidence; the result says so.
   - *Document-loading operations.* `lock_mode=verify|required` on a loading operation verifies the
     to-be-loaded closure's coordinates and hashes before the workspace is mutated, resolving the
     lockfile against the loaded document (beside-document default; the policy-declared lockfile when
     the loaded document is the project's root artifact). A mismatch, or `required` with no lockfile,
     refuses the load with a structured error and mutates nothing; loading verification reports through
     the tool's structured result, not QC findings.
5. **Redirect and catalog safeguards are posture-independent.** A non-empty host allowlist continues to
   disable redirect following regardless of the request argument, and the confining-policy catalog
   presence gate keys off the effective composed posture, so a request-level `deny` also engages the
   catalog gate for an otherwise-open policy. Catalog-refusal and blocker attribution strings branch on
   the decision-3 denial source, so a request-caused refusal never tells the user to loosen the
   reviewed policy.
6. **Headless parity without a fail-open compatibility profile.** Headless commands have no local-admin
   compatibility substrate. With a valid policy, headless QC follows the policy network posture exactly
   like `run_project_qc`, preserving plugin/CLI conformance; with no policy or an invalid policy, the
   headless default is `deny`. Release and PR/CI surfaces default to `deny` on every path. The CLI's
   `--no-network` forces `network=deny` on every document-loading and gate command (a redundant
   affirmation where `deny` already holds), an explicit `network=allow` is the only loosening and
   remains subject to policy, and `serve --transport stdio` follows the same rule — a missing
   controller/preference source fails closed instead of inheriting a fail-open default.

## Consequences

- Issue 7 adds request arguments only; no policy-schema field changes, so existing policy digests are
  unaffected.
- Interactive behavior is unchanged when the new arguments are absent: `lock_mode` defaults to `ignore`
  and `network` defaults to the released policy/compatibility outcome. The new `deny` defaults apply
  only to surfaces that do not exist yet (release gate, release preparation, headless commands).
- A release over remote-backed, unlocked imports is still possible, but only by explicit request, and
  every release result names each import's document source either way — the reviewer sees the
  provenance whether or not the caller asked for the caveat.
- Locked projects cannot bypass lock verification by any request combination; unlocked projects gain
  opt-in verification whose trust level is honestly labeled rather than borrowing the locked gate's
  authority.
- The `network=allow` no-op affirmation under an unrestricted no-policy profile is deliberate: making
  it an error would break scripted callers that pass a constant argument across projects with and
  without policies.
- The denial-source discriminator is a prerequisite for the request controls, not an optional polish:
  without it, request-level denials would ship with the released policy-blaming strings and train users
  to weaken reviewed policies.

## Verification

- A composition matrix pins the effective permission and the attribution for every combination of
  request (`unset|allow|deny`), policy (`none|allow|deny|invalid`), principal profile (local-admin vs
  `network:access`-only), and the no-policy compatibility preference — including the thrown
  invalid-policy refusal on the direct path, the non-exceptional deny on the blocker path, and the
  corrected invalid-policy attribution.
- Redirect-coupling and catalog presence-gate tests confirm a request-level `deny` engages both
  safeguards under an otherwise-open policy, and that the catalog refusal names the request, not the
  policy, as the denial source.
- Lock-mode matrix tests pin: locked policy × every `lock_mode` value (verification always runs);
  unlocked/absent policy × `verify` (comparison when the lockfile resolves, clean reported skip when
  the default file is absent, abort in the released refusal states) and × `required`
  (`imports.lock_missing` exactly in the file-absent state); the `lockfile_source` label on every
  verification result; and the gate-only scope of `imports.loaded_content_divergence`.
- Loading-operation tests pin that `lock_mode=verify|required` verifies before mutation and refuses
  with a structured error without changing the workspace.
- Release-surface tests pin `imports.remote_backed` findings for a remote-backed closure member under
  the default, the caveat downgrade under explicit `network=allow`, and the unconditional
  resolved-imports listing.
- CLI tests pin the valid-policy posture parity with `run_project_qc`, the no-policy/invalid-policy
  `deny` default, `--no-network` ≡ `network=deny`, and the release-command `deny` default.
