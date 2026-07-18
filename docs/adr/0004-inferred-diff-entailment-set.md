---
title: "ADR 0004: inferred semantic diff entailment set"
nav_exclude: true
---

# ADR 0004: inferred semantic diff entailment set

- Status: accepted
- Date: 2026-07-17
- Roadmap issue: Immediate next issue 8; open design decision §20-3

## Context

`semantic_diff` ships asserted-only and rejects `mode=inferred|both`. The roadmap requires inferred
superclass, equivalence, disjointness, and type changes, newly unsatisfiable classes and inconsistency,
validation-stage result deltas, and module-ownership changes, with a policy-driven breaking-change
classification. The open question is which entailment set is sufficiently useful and tractable.

Full entailment sets cannot be the answer. The set of subsumption entailments over even a modest
signature is infinite whenever it is non-empty, deciding logical difference is undecidable for expressive
description logics, and the OWL 2 RDF-Based Semantics closure is infinite by construction; OWL 2
conformance itself defines tools as entailment *checkers* over supplied candidates, never enumerators.
Every credible prior system therefore makes the same three moves: a fixed finite set of axiom shapes
(OWLAPI's inferred-axiom generators, ContentCVS's five-shape grammar, Ecco's atomic-subsumption diff,
ROBOT's generator list), witnesses restricted to named entities from the told signature (SPARQL 1.1
entailment-regime vocabulary restrictions, the logical-difference literature's signature-relative
definitions), and direct hierarchy edges by default (ROBOT patched OWLAPI's indirect-inclusive class
assertions with a direct-only generator and gates indirect closure behind an opt-in).

The stock OWLAPI 4.5.29 generator framework is unsuitable to adopt as-is: `InferredOntologyGenerator`
swallows per-generator exceptions and can silently drop whole axiom families (ELK, for example, does not
implement disjointness or property-hierarchy queries); the class-assertion generator returns all types,
not direct ones; the disjointness generator triggers per-class complement classification on HermiT and is
tautology-flooded by unsatisfiable classes; and the object-property characteristic generators carry an
inverted simple-property guard. Any adopted grammar must be computed explicitly and fail closed per
category.

The isolated-reasoner machinery from ADR 0002 (same-hop capture of the selected reasoner's exact factory,
configuration, and buffering; daemon-thread execution under an outer timeout with atomic cleanup
ownership; configuration-parity reporting) is the established substrate for private classification. The
structural and profile QC stages — and the governance stage for its intrinsic, no-policy checks —
already expose complete finding-identity sets used for baseline delta attribution; the CQ, invariant,
and SHACL stages, and the governance stage's rule-driven policy/module check rows, compute per-row
identities transiently but today publish only an aggregate digest, from which member-level deltas
cannot be recovered.

## Decision

1. **Semantics.** The inferred diff is defined against OWL 2 Direct Semantics *as computed by one
   explicitly recorded reasoner*: the captured Protégé selection (ADR 0002) evaluates both sides with
   instances built sequentially from the same captured recipe — at most one live instance at a time
   (left fully evaluated and disposed before the right is constructed), because the captured
   configuration object is shared and some factories mutate it in place. Results carry the reasoner
   identity and configuration-parity metadata and are never presented as reasoner-independent OWL
   truth; a sound-but-incomplete reasoner yields a sound-but-incomplete diff and says so.
2. **The entailment set (`inferred-diff-v1`).** The hierarchy, equivalence, type, and disjointness
   categories are computed over the shared named signature Σ — named entities present on both sides —
   so entity additions and removals remain the asserted sections' business; the consistency and
   class-satisfiability categories are exempt from Σ and evaluated over each side's full named
   signature, because a class added by the change and unsatisfiable at birth is exactly what "newly
   unsatisfiable" must report (matching the shipped verified-apply attribution semantics). "Full named
   signature" always means the closure signature the classification saw, regardless of scope, matching
   the closure-wide attribution semantics. Σ membership follows the requested scope: with
   `include_imports=false`, Σ is drawn from the two active-ontology signatures (classification still
   sees each side's whole closure); with `include_imports=true`, from the closure signatures. Classes in the bottom node (unsatisfiable) are excluded from the hierarchy
   categories and reported in the satisfiability category instead. The categories:
   - *Consistency.* The per-side consistency verdict. If a side is inconsistent, the axiom-level
     categories are suppressed for the comparison and the result reports the inconsistency transition
     with an explicit caveat — an inconsistent ontology entails everything, so member-level deltas would
     be noise.
   - *Class satisfiability.* The per-side sets of unsatisfiable named classes over each side's full
     named signature; the delta, taken over the union signature, names newly and no-longer
     unsatisfiable classes — including classes that exist on only one side.
   - *Named-class subsumption.* The entailed strict subsumption relation between satisfiable named
     classes in Σ, obtained as the reachability closure of each side's direct taxonomy
     (`getSuperClasses(direct=true)` edges; the closure is computed in memory, adding no reasoner
     calls). Comparing closures — not direct edges — makes the delta invariant under inserting or
     removing intermediate named classes; each reported delta member is labeled `direct` or `indirect`
     per side for display. The delta is presented transitively reduced. Structural tautologies are
     excluded (`X ⊑ owl:Thing`, `owl:Nothing ⊑ X`, `X ⊑ X`), following ROBOT's structural
     tautology list.
   - *Named-class equivalence.* The equivalence partition (reasoner nodes) restricted to satisfiable
     classes in Σ; the delta reports pairs that entered or left an equivalence class.
   - *Named individual types.* Entailed named types of named individuals in Σ, derived from direct
     types (`getTypes(direct=true)`) closed over the same taxonomy reachability, with per-side
     `direct`/`indirect` labels; `owl:Thing` types are excluded as tautological.
   - *Disjointness (candidate-bounded).* Disjointness is verified, not enumerated: the candidate pairs
     are the named class pairs mentioned together in an asserted `DisjointClasses`/`DisjointUnion`
     axiom on either side, intersected with Σ before any check — both classes must be named on both
     sides, which also keeps fresh-entity policy out of the verdict — and each surviving pair is
     checked on both sides by satisfiability of the intersection. Pairs involving an unsatisfiable
     class are excluded as trivial. Because one wide n-ary disjointness axiom expands quadratically,
     the candidate set is capped; exceeding the cap truncates deterministically and is disclosed in
     the scope label. The result labels this scope (`disjointness_scope: asserted_candidates`, plus
     the truncation disclosure when capped); full pairwise enumeration is rejected — it is quadratic
     complement-classification work on tableau reasoners and unimplemented on ELK.
   - *Exclusions.* Property hierarchies, property characteristics, and property assertions are not in
     `inferred-diff-v1` (the roadmap does not require them; the stock characteristic generators are
     defective and ELK lacks the queries). The exclusion is machine-readable in the result's
     entailment-set descriptor, never implicit.
3. **Stage deltas are not entailment-grammar members.** CQ, invariant, governance, and SHACL result
   deltas are computed from complete per-stage finding-identity sets, not from axioms. The structural
   and profile stages, and the governance stage's intrinsic checks, already record that side channel;
   the CQ, invariant, and SHACL stages — and the governance stage's rule-driven policy and module check
   rows — must first be extended to publish the per-row identity sets they already compute
   transiently. That extension is in issue 8's scope, and count/digest heuristics are not an acceptable
   substitute for member-level deltas.
4. **Module ownership changes are policy deltas, not entailments.** When a loaded policy declares
   `modules[].owned_namespaces`, each side's term→owning-module map is computed with the released
   boundary-aware, most-specific-namespace matcher, and the diff reports terms whose owning module
   differs between sides. Ownership *violations* remain governance findings (and so also surface via
   the stage deltas); the ownership-change category exists because a term legitimately moving between
   two policy-conformant modules produces no violation on either side. Without a policy, the category
   is absent and says so.
5. **Fail closed per category.** Each category is computed explicitly; a category the reasoner cannot
   answer becomes an errored category naming the unsupported operation — never a silently empty one
   (the stock `fillOntology` fail-open behavior is explicitly rejected). `mode=inferred|both` requires
   a selected reasoner; a told-only reasoner (StructuralReasoner-like) is permitted but its parity
   metadata discloses that the "inferred" view is the told hierarchy. The request timeout is a total
   budget covering *all* reasoner interaction — construction, classification, taxonomy and type
   queries, and candidate satisfiability checks — under the ADR 0002 daemon-thread/cleanup-ownership
   pattern. Disjointness runs last, so a candidate-matrix overrun errors only its own category; on
   overall expiry, `mode=both` still returns the asserted sections with an errored inferred section.
6. **Determinism.** Category members are canonically rendered and sorted; sample lists obey the shared
   `limit` semantics (bounded samples, exact counts). The result names its grammar
   (`entailment_set: inferred-diff-v1`); changing the grammar's membership or semantics requires a new
   identifier.
7. **`mode=both`** emits the asserted diff sections byte-identically to `mode=asserted` — excluding the
   result-level compatibility block, which is emitted once per result — and adds the inferred sections.
   The asserted fail-closed caveat strings (truncated closure, blank-node churn) are preserved verbatim
   inside that single compatibility block; in `mode=inferred|both` the block's classification
   additionally incorporates the inferred inputs of decision 8.
8. **Breaking-change classification stays policy-driven and fails closed on missing evidence.** The
   inferred categories feed the classification as inputs — a newly unsatisfiable class or an
   inconsistency transition is `potentially_breaking` by default. When any inferred category is errored
   in `mode=inferred|both`, the classification is forced to `potentially_breaking` with a caveat naming
   the missing categories, mirroring the released truncated-closure rule: a verdict is never computed
   over evidence that failed to materialize. Any new policy vocabulary for project-specific
   classification is introduced without unauthored default materialization: an absent policy block
   contributes nothing to the effective policy map, so existing policy digests — which pin change-set
   previews, lock verification coordinates, and revision envelopes — are unchanged by the upgrade.
   `policy_driven` flips to `true` only when a loaded policy actually configured the classification.
9. **Deferred inputs.** Release-manifest left/right inputs and the optional full-report file output
   (with bounded in-result summaries) are deferred to the release-workflow issue that defines the
   manifest and report formats; `reasoner`, `timeout_ms`, `policy_path`, scope, and result limits are
   in issue 8's scope as decided above. The `reasoner` argument selects among the installed Protégé
   reasoner factories and is still captured through the ADR 0002 recipe; it defaults to the current
   selection, so decision 1's capture discipline holds for every choice.

## Consequences

- The diff is sound with respect to the recorded reasoner and deliberately not complete for OWL 2
  semantics; that boundary is disclosed in every result rather than discovered by users. This matches
  the project's standing non-goal of guaranteeing reasoner-complete construct coverage.
- Closure comparison detects every gained or lost named subsumption entailment over Σ — including
  changes a direct-edge diff would miss or over-report when intermediate classes appear or disappear —
  while transitive reduction keeps reports readable.
- Disjointness completeness is candidate-relative: a semantic disjointness gained with no asserted
  disjointness axiom naming the pair on either side is not reported. Projects that need disjointness
  surveillance must assert their disjointness policy; the scope label makes the boundary visible.
- Costs are bounded and predictable: two classifications, one satisfiability check per surviving
  candidate disjointness pair per side under the candidate cap, one direct-type query per named
  individual, and in-memory graph closure. The quadratic property-assertion materialization that the
  SPARQL snapshot path caps is never entered, and a wide n-ary disjointness axiom cannot starve the
  other categories because disjointness runs last under the shared budget.
- The grammar computation consumes only the generic `OWLReasoner` interface, so the future headless CLI
  reuse (roadmap issue 12) is a packaging decision, not a redesign.
- Anonymous-individual instability cannot leak in: every category member references named entities
  only, and the asserted sections keep their existing blank-node churn discipline.

## Verification

- Consistency-transition suppression: an inconsistent right side suppresses member-level categories and
  reports the transition with the caveat.
- Satisfiability deltas: newly and no-longer unsatisfiable classes, including import-closure-spanning
  unsatisfiability and a right-only class that is unsatisfiable at birth (present despite being outside
  Σ).
- Subsumption closure: a lost indirect entailment is detected when every direct edge around it
  survives; an inserted intermediate named class changes labels but not closure membership; tautology
  and bottom-node exclusions hold; the reported delta is transitively reduced and deterministically
  ordered across runs.
- Equivalence partition deltas, including merges and splits of equivalence nodes.
- Type deltas with direct/indirect labels; `owl:Thing` exclusion.
- Disjointness candidate matrix: candidates from either side intersected with Σ, per-side verification,
  unsatisfiable-pair exclusion, scope label presence, and deterministic disclosed truncation at the
  candidate cap.
- Module ownership changes: a term moving between two policy-conformant modules is reported; the
  category is absent without a policy and says so.
- Stage-delta substrate: the CQ, invariant, and SHACL stages publish complete per-row identity sets,
  and member-level stage deltas are computed from them (not from count/digest heuristics).
- Fail-closed categories: a reasoner throwing on a category yields an errored category naming the
  operation; `mode=both` under a timeout returns asserted sections plus an errored inferred section,
  and the classification is forced to `potentially_breaking` with a caveat naming the missing
  categories.
- `mode=both` asserted-section byte parity with `mode=asserted` on identical inputs, excluding the
  single result-level compatibility block, whose asserted caveat strings are preserved verbatim.
- Parity disclosure: configuration-parity metadata and the told-only disclosure appear in results.
- Sequential instance discipline: the right side's reasoner is constructed only after the left side's
  is disposed, and both report the same captured configuration identity.
- Policy-digest stability: loading an existing `0.6.1` policy file under the `0.7.0` loader yields an
  unchanged `policy_digest`.
