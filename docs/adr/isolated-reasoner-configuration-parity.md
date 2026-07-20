---
title: "Isolated reasoner configuration parity"
published: false
nav_exclude: true
---

# Isolated reasoner configuration parity

- Status: accepted
- Date: 2026-07-13

## Context

An OWLAPI `OWLReasonerFactory` alone is not enough to reproduce Protégé classification. Protégé asks
the selected `ProtegeOWLReasonerInfo` for three coupled values: its factory, its
`OWLReasonerConfiguration`, and its recommended buffering mode. Calling a factory's one-argument method
silently replaces plugin/user configuration with factory defaults. This was the behavior in the private
reasoners used by explanation and inconsistency tooling.

Project QC also previously classified the live Protégé reasoner before copying the ontology for the
non-reasoner stages. That required a before/after fingerprint race check and meant the reasoner verdict and
inferred SPARQL data did not inherently share the other stages' private revision.

## Decision

`IsolatedReasonerSpec` captures the selected `ProtegeOWLReasonerInfo` inside the same bounded model-thread
operation that captures the ontology snapshot. It retains:

1. the selected reasoner id and name;
2. the exact factory instance;
3. the exact configuration object returned by `getConfiguration(...)`, including any reasoner-private
   subtype fields not represented by the OWLAPI interface; and
4. the plugin's recommended `BUFFERING` or `NON_BUFFERING` mode.

The configuration is requested with a private silent progress monitor. Reusing Protégé's classification
monitor would let a background private computation mutate live progress UI and is not semantic
configuration.

Project QC flattens the already-loaded imports closure into its no-network asserted snapshot, constructs one
private reasoner over that ontology using the captured recipe, and uses the same instance for consistency,
unsatisfiable classes, and optional inferred-query materialization. Profile, governance, structural,
invariant, CQ, and SHACL stages use that same captured closure. No private reasoner state is copied back to
Protégé.

The plugin configuration's own timeout remains unchanged; rewriting it would discard reasoner-private fields
or require unsafe subtype assumptions. QC independently applies the policy/tool timeout as an outer cap. On
expiry it atomically takes cleanup ownership, interrupts and disposes the private reasoner, marks the stage as
an execution error, and discards any late result. A third-party call that ignores interruption/disposal can
only continue on a daemon thread against private data; it cannot publish a stale gate or mutate the live
workspace.

The OWLAPI explanation algorithm requires a non-buffering reasoner because it removes and restores axioms
during its search. For that operation only, non-buffering overrides a plugin's recommended buffering mode;
the exact captured configuration is still supplied and the result records the buffering caveat.

Results report requested configuration class, timeout, buffering, fresh-entity policy, and individual-node
policy. When OWLAPI exposes the values reported by the created reasoner, they are compared with the request.
Some reasoners accept a configuration object but report a different policy (the StructuralReasoner does this
for fresh entities). That mismatch is surfaced as `configuration_parity=false` plus caveats; it is not
silently relabelled as parity and is not generalized into an OWL semantic claim.

## Consequences

- `run_qc_suite` and `run_project_qc` no longer initialize, classify, or query the live reasoner.
- The reasoner stage now appears in `validation_snapshot.stages` when it completed, and every completed QC
  stage can truthfully claim the same isolated snapshot.
- A selected but not-yet-classified reasoner is sufficient for QC; the user's live reasoner status and cache
  remain unchanged.
- A live edit after capture does not invalidate this read-only report: all stages continue to evaluate the
  captured fingerprint, which is returned with the result. Revision-conflict rejection belongs to the later
  preview/commit workflow; treating a post-capture edit as a mixed snapshot would discard a coherent report.
- Missing selection, capture/configuration failure, timeout, and unsupported execution remain explicit
  skip/error states under the established legacy/strict aggregation rules.
- Reasoner-specific preferences that are only mutable global state and are not represented in the returned
  configuration object cannot be reconstructed. The exact object boundary is the strongest contract
  Protégé 5.6.6 exposes.
- Explanation search and inconsistency probes use the same configuration-preserving construction path.

## Verification

Headless adversarial tests cover exact configuration-object identity, both buffering modes, default-overload
regression, fresh-entity/individual-node parity reporting, malformed plugin metadata, import-spanning
unsatisfiability, live mutation after capture, one-instance inferred materialization, timeout interruption,
and the prohibition on consulting/classifying the live reasoner.
