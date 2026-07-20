---
title: Project policy contracts
nav_order: 7
---

# Project policy contracts
{: .no_toc }

Version 0.7.2 retains the complete headless QC, import-lock, release, audit-export, and bounded stdio
execution introduced in 0.7.1, with cross-surface conformance and the same filesystem, network,
module-governance, and isolated-preflight boundaries. The public surface is 85 tools and 11 prompts.
{: .fs-6 .fw-300 }

For a user-focused setup guide, including a complete crate example and an explanation of the two
fingerprints, see [RO-Crate and RDFC interoperability](interoperability/).
{: .tip }

## Policy v1 authoring format

The authoring file is `.protege-mcp/project.yaml`. Its canonical JSON Schema is
[`project-policy-v1.schema.json`](https://github.com/hakjuoh/protege-mcp/blob/main/core/src/main/resources/schema/project-policy-v1.schema.json).
The schema requires `version: 1`, a project id, an absolute root-ontology IRI, and an
`interoperability` contract. It rejects unknown fields rather than silently assigning them a future meaning.

Start from the example closest to the project:

- [Minimal policy](examples/project-policy/minimal.yaml) — identity, fail-closed import handling,
  explicit filesystem/network defaults, and the core QC stages.
- [General OWL policy](examples/project-policy/general-owl.yaml) — modules, labels/definitions, IRI
  policy, locked imports, persisted invariants/CQs/SHACL, waivers, and release output.
- [OBO-oriented policy](examples/project-policy/obo.yaml) — OBO IRIs and definition properties, HermiT
  with an EL profile, ROBOT-compatible query directories, and OBO release format.

### Standards interoperability contract

Policy v1 separates the portable layer from the execution overlay:

- [RO-Crate](https://www.researchobject.org/ro-crate/) describes the project and root ontology artifact
  for other applications. Versions 1.0, 1.1, 1.2, and 1.3 are supported with their exact contexts,
  specification identifiers, metadata filenames, and profile rules.
- RO-Crate 1.1 is the broad-compatibility default because it is the newest complete version shared by
  the reviewed Java and Python library support matrices. This is not a claim of measured market share.
  Select 1.2 or 1.3 explicitly when the user or target environment requires it; when the field is
  omitted, only a recognized, unambiguous 1.2 or 1.3 crate context is honored — any other signal
  keeps the 1.1 default and fails validation loudly rather than silently adopting a legacy version.
- W3C RDFC-1.0 plus SHA-256 supplies the cross-application root RDF dataset fingerprint.
- `.protege-mcp/project.yaml` remains the Protégé MCP execution/QC overlay; another product does not
  need to parse it to discover or identify the ontology artifact.

The normative profile is [Ontology project RO-Crate profile v1](profiles/project-v1/). RO-Crate 1.0
uses `ro-crate-metadata.jsonld`; versions 1.1–1.3 use `ro-crate-metadata.json`. Formal `Profile`
contextual entities are required for 1.2/1.3, while 1.0/1.1 use `CreativeWork` with the same root
`conformsTo` link. The loader validates the crate offline, rejects duplicate JSON keys, caps metadata at
4 MiB/20,000 entities, and never dereferences contexts, profiles, imports, or arbitrary IRIs.

The RO-Crate version and filename may be omitted together. The loader first checks an existing
`ro-crate-metadata.json` for exactly one supported normative `@context`; a recognized 1.2 or 1.3 crate
therefore retains its version. If there is no valid, unambiguous signal, the effective policy
materializes `ro-crate-1.1` and `ro-crate-metadata.json`. An explicitly selected version always wins
and never silently upgrades.

Schema validation checks data shape, enumerated values, duplicate stages, positive timeouts, explicit
IRI/date syntax patterns, and
cross-field requirements such as a lockfile for `imports.mode: locked`. Network allowlist entries are
RFC 1123 hostnames containing at least one letter (digits, dots, and hyphens allowed — underscore names
are intentionally rejected), strict IPv4 addresses (all-numeric dotted entries are validated as IPv4),
or textual IPv6 addresses including the IPv4-mapped forms.

The runtime loader adds checks JSON Schema cannot perform: referenced-file existence, canonical
project-root containment (including symlink resolution), CURIE prefix resolution, Java-regex compilation,
duplicate module coordinates, each module file's actual ontology IRI, active/root ontology agreement, and
installed required-reasoner availability.
It rejects duplicate YAML keys, trailing YAML documents, inputs over 1 MiB, URL-shaped asset paths, missing
glob matches, and assets escaping `project_root` unless the policy explicitly enables the local-admin
external-path compatibility profile.

Discovery order is:

1. explicit `policy_path`;
2. the nearest `.protege-mcp/project.yaml` found while walking from the active local ontology document
   toward the filesystem root;
3. no policy (`policy_loaded=false`), which uses the local-admin compatibility mode unless the user disables
   **Allow unrestricted local-admin paths when no project policy is loaded** in Settings ▸ MCP.

A policy stored in a directory named `.protege-mcp` anchors the project at that directory's parent —
the directory that contains `.protege-mcp` — so the canonical layout keeps ontology sources beside the
`.protege-mcp` directory. A policy stored anywhere else (including one named by an explicit
`policy_path`) anchors at its own directory. `project_root` (default `.`) resolves against that anchor
and may not escape it. Every relative asset path — module paths, the import lockfile, invariant/SHACL
paths and glob bases, competency-question paths, and the release output directory — resolves against
the effective project root, never against the process working directory. Effective defaults
are materialized before a canonical `policy_digest` is computed, so YAML comments and key order do not
change the digest.

The same containment rule now governs caller-selected and implicit paths used by ontology load/create/
merge/save, SHACL shapes, locality-module output, catalogs, import locks, and CQ sidecars. Reads/writes
require `filesystem:project:read` / `filesystem:project:write`; an outside path additionally requires
both `filesystem.allow_external_paths: true` and `filesystem:external`. Canonical existing ancestors are
resolved before the decision, so an in-project symlink cannot escape the root. With no policy loaded,
targets *derived from the already-open document* — an argument-less save, a catalog or CQ sidecar beside
it — are exempt from the local-admin compatibility opt-in, which governs caller-selected paths only.
A discovered policy that is loaded but invalid refuses every filesystem/network authorization at use
time until it validates, while the diagnostic tools (`get_project_policy`, `validate_project_policy`,
`run_project_qc`, `get_model_revision`) still return their structured invalid-policy results so the
policy can be repaired.

### Local audit retention policy

The optional `audit` block bounds local audit storage without putting any event, client identity, prompt,
token, or ontology content in the committed policy:

```yaml
audit:
  retention_days: 90
  max_file_bytes: 10485760
  max_files: 10
```

When omitted, those same runtime defaults apply without being injected into the effective policy, so adding
the 0.7.1 runtime does not silently change an existing project's `policy_digest`. Each backend workspace
writes a separate owner-only JSON Lines stream below
`~/.protege-mcp/audit/<project-hash>/`; rotation and retention apply per stream. Runtime streams are outside
the project and VCS tree. An explicitly requested, re-redacted review export may be written inside the
project; add `.protege-mcp/audit-export*.jsonl` to ignore rules unless that review artifact is intentionally
committed. POSIX filesystems enforce `0700` directories and `0600` files; on a non-POSIX filesystem the
runtime uses the platform's default owner ACL boundary, which administrators should verify before relying
on the log for confidential attribution. The size/count bounds take precedence over the time window once a
stream fills; `max_files` is at least 2 so the current stream and one rotated predecessor always exist.
If `max_files` is reduced, the next append to each workspace under a loaded, valid policy removes its
excess numbered rotations before writing the new event; a session running on fallback defaults never
prunes. If the policy is invalid or unreadable, destructive operations on that same workspace use the
schema maxima (3650 days, 1 GiB per file, and 100 files), rather than the ordinary defaults, so the
temporary fallback cannot discard history that any valid policy could retain. A valid policy that merely
omits the `audit` block still uses the ordinary defaults shown above.
Active projects apply their own retention setting on append. Since an orphaned path hash cannot reveal its
former policy, root-wide cleanup uses the conservative schema maximum (3650 days) rather than borrowing a
different project's shorter setting.

### Local entity-search policy

The optional `entity_search` block configures the existing local `search_entities` tool without adding a
network provider or a new tool:

```yaml
prefixes:
  rdfs: http://www.w3.org/2000/01/rdf-schema#
  skos: http://www.w3.org/2004/02/skos/core#
entity_search:
  preferred_properties: [rdfs:label, skos:prefLabel]
  synonym_properties: [skos:altLabel]
  preferred_languages: [en, ko]
  fallback_languages: [und, de]
```

Property values are absolute IRIs or declared CURIEs. Preferred and synonym sets must not resolve to the
same property. Language lists are ordered, case-insensitively unique, exactly disjoint, and limited to four
entries each; `und` selects untagged literals. Tags act as BCP 47 ranges and the first match wins, with the
preferred list checked before the fallback list, so a broad `en` intentionally shadows a later `en-GB`.
Preferred languages receive adjustments from +9 to +6, fallbacks from
+4 to +1, and unlisted languages −9. These bounded adjustments preserve exact → prefix → substring → fuzzy
tier order. With no language lists, all languages rank neutrally and remain searchable.

When the block or an individual field is omitted, runtime defaults supply `rdfs:label` and `skos:prefLabel`
as preferred properties, and `skos:altLabel`, OBO `hasExactSynonym`, and OBO `hasRelatedSynonym` as synonym
properties. Omitted runtime defaults are not injected into older effective policies, so upgrading does not
change their `policy_digest`. An invalid `entity_search` block falls back to the standard local defaults;
it never grants filesystem/network authority. Policy bytes are content-hash cached off the model thread,
while ontology lexical indexes are cached by live workspace revision.

## Executable QC

Use `get_project_policy` to inspect discovery/defaults and `validate_project_policy` to require a valid
policy without running ontology checks. `run_project_qc` executes every `validation.required_stages` entry;
`reasoning.required: true` also forces `reasoner` into the effective required-stage set even if the authored
list omitted it. The `interoperability` stage is always required in policy v1, even when an authored stage
list omits it.

`reasoning.reasoner` is a NAME reference resolved against the display names of the reasoners installed
in Protégé (factory ids are accepted by `set_reasoner` and `semantic_diff`, but the policy validator
matches display names only). The **recommended** convention — the one the shipped example policies use —
is the version-less display name (`HermiT`, `ELK`): it matches when its whitespace tokens appear as a
contiguous whole-token, case-insensitive run inside an installed display name, so the policy stays valid
across reasoner-plugin upgrades that only change the version suffix. A full display name (e.g.
`HermiT 1.4.3.456`) matches exactly (case-insensitive) and pins that exact version; partial versions
(`HermiT 1.4`) never match. The reference must resolve to exactly ONE installed reasoner: zero matches is
the validation error `reasoner_unavailable`, two or more is `reasoner_ambiguous`. Use a full display name
that identifies one install; because policy references are name-only, plugins exposing the same display
name cannot be disambiguated by id and all but one must be disabled. `run_project_qc` applies the same resolution rule when comparing
the required reasoner against the one selected at the snapshot boundary, as do `set_reasoner` and
`semantic_diff`'s `reasoner` argument.

`run_project_qc` requires Protégé's selected reasoner to match the named policy reasoner, and distinguishes:

- `pass`: every required stage ran and stayed below `validation.fail_on`;
- `fail`: a required stage completed and found a policy violation;
- `error`: policy/configuration/assets were invalid, or a required stage was missing, skipped, timed out,
  degraded from requested inferred data, or otherwise could not complete.

An execution `error` takes precedence over a separate policy `fail`, preventing a false release pass. The
active ontology and its already-loaded imports are copied once behind the EDT boundary. Every stage then reads
only that private snapshot off the UI thread; a GUI edit made while checks run cannot create a mixed-revision
report or touch the Undo stack. Import declarations are recorded but not replayed into the private manager, so
snapshot construction never dereferences a missing remote import.

The selected reasoner factory, exact plugin configuration object, and recommended buffering mode are captured
at the same boundary. One private reasoner evaluates consistency/unsatisfiable classes and supplies inferred
materialization for invariant/CQ queries over the flattened closure. The live Protégé reasoner is neither
initialized nor queried. `reasoner_configuration` reports the requested and runtime-exposed buffering,
fresh-entity, individual-node, configuration-class, and timeout values; a plugin that ignores an exposed
setting produces an explicit parity caveat. A policy/tool timeout interrupts and discards a private late result.
Review `reasoner_configuration` in the QC result when exact parity matters; it reports both the
requested settings and the values the reasoner exposes at runtime.

Persisted invariant files are ROBOT-compatible `.rq` queries with optional leading metadata:

```sparql
# id: annotation.definition.required
# message: Every owned class needs a definition.
# severity: warning
# include_inferred: false

SELECT ?class WHERE { ... }
```

Policy SHACL `paths` may name multiple files or globs; files are resolved deterministically and their RDF
graphs are unioned locally. CQ policy paths support `robot-sparql-dir`, `sidecar-manifest`, and
`ontology-annotations`.

The governance stage enforces configured label-language/preferred-label cardinality, definition
presence/language/literal datatype/non-placeholder text, lifecycle status/replacement integrity, active
or expired waivers, and `modules[].owned_namespaces` against terms **defined** in each configured module
file. An axiom defines every named entity it constrains: a named `SubClassOf` subject; each named member
of an `EquivalentClasses`, `DisjointClasses`, `DisjointUnion`, property-equivalence/disjointness or
`InverseObjectProperties` axiom; a `HasKey` class; a `DatatypeDefinition` datatype; a property
domain/range/characteristic subject or chain super-property; an annotation-assertion subject; each named
member of a `SameIndividual`/`DifferentIndividuals` axiom; the subject individual of a class or
(negative) property assertion; and a class/property named in a SWRL rule head. A property spelled
`ObjectInverseOf(p)` is unwrapped to `p`, so inverting a foreign property does not evade the check. Bare
supporting declarations of foreign entities — the kind OWLAPI module
extraction adds for referenced terms — are permitted; merely referencing a foreign term (as the
superclass of an owned subject, inside an owned class's expression, or as the class or object of an
assertion about an owned individual) is likewise legal. Ownership matching is boundary-aware: a
namespace ending in a delimiter (`…/ns/`, `…/ns#`, or the OBO-style `…/GO_`) owns every extension,
while a namespace ending alphanumeric owns the exact IRI and continuations across a structural IRI
separator (`/`, `#`, `:`) only — owning `…/ns` captures `…/ns/X` and `…/ns#X` but never the siblings
`…/ns2/…`, `…/ns-ext/…`, `…/ns.ext/…` or `…/ns_ext/…`. Each entity is attributed to its most specific
owned namespace only.
Namespaces may have explicit co-owners; a project that deliberately augments another module's terms
should declare co-ownership or leave that namespace unowned. A module document that cannot be inspected
fails the stage closed with `module_inspection_failed`. Loaded import cycles are warnings — note that a
policy with `fail_on: warning` therefore fails its gate on a cycle; ontology/version/document identity
conflicts are errors. Active waivers remain visible with `waived_count`; expired waivers become
findings.
`write_import_lock`, `verify_import_lock`, and `validate_catalog` provide deterministic no-network dependency
checks. `run_project_qc` now fails closed on an unresolved import closure when the policy sets
`imports.fail_on_missing: true` or `imports.mode: locked`. Locked mode also compares the complete loaded
coordinate set and every local artifact SHA-256 automatically in `run_project_qc` and change-set preflight,
and then attests that those disk bytes are the content the isolated snapshot actually consumed — an
unsaved in-memory edit of a locked import (or a document swapped around the load) turns the gate to
`error` with `imports.loaded_content_divergence`. Relative lockfile/catalog arguments resolve against the
canonical `project_root`; `verify_import_lock` remains available for an explicit standalone check.

`network.default`, `network.allowed_hosts`, and the `imports.network` override govern direct document URLs
and remote import dereference and require `network:access`. A local workspace/catalog mapping may still
satisfy an HTTP ontology IRI while offline only when its resolved document passes the same project
filesystem policy. Direct `file:` imports are checked and pinned to their authorized canonical path;
nested `jar:` sources are refused because they obscure the filesystem/host boundary. When a host allowlist
is active, redirects are disabled because OWLAPI does not expose a policy callback for the redirect target.

The document-loading operations (`load_ontology`, `merge_ontology_document`, `add_import`, and the
`right_document` side of `diff_ontologies`/`semantic_diff`) additionally accept a request-level
`network=deny|allow` argument that composes most-restrictive-wins with everything above: the effective
permission is the conjunction of the request, the policy, the principal's `network:access` capability,
and — with no policy — the local-admin compatibility profile and preference. `deny` always denies that
request's remote fetches (the root document included) with an explicit error; `allow` merely abstains
from denying and never overrides a policy `deny`, an invalid-policy fail-closed posture, a missing
capability, or a restricted no-policy state — under the unrestricted no-policy local-admin profile it is
a no-op affirmation. Every denial is attributed to its actual source (`request network=deny`,
`imports.network=deny`/`network.default=deny`, the host allowlist, the missing capability, the invalid
policy, or the compatibility preference), a non-empty host allowlist still disables redirect following
regardless of the request, and a request-level `deny` engages the same strict folder-catalog presence
gate a confining policy does — with the refusal naming the request when only the request confines the
posture (a policy whose host allowlist already engaged the gate keeps the policy attribution).

The project/release gates (`run_project_qc`, `run_qc_suite` with `policy_path`, `preview_change_set`)
and the loading operations (`load_ontology`, `merge_ontology_document`) also accept
`lock_mode=ignore|verify|required` (default `ignore`). It never weakens policy: with
`imports.mode: locked` the gate verification always runs, whatever the request says. Otherwise a gate
`verify` resolves the lockfile with the released `verify_import_lock` rules (the policy-declared
`imports.lockfile` when exactly one resolves; the beside-active-document `imports.lock.json` only when
none is declared or no policy is loaded; the released refusal states abort the request), runs the same
coordinate/SHA-256 comparison the locked gate uses — including the loaded-content attestation — and
skips cleanly with a reported note when the resolved default file does not exist; `required` turns
exactly that file-absent state into the `imports.lock_missing` error finding. Every request-triggered
verification labels its `lockfile_source` (`policy_declared` or `beside_document`); a beside-document
lockfile is co-located with — and writable by — whoever supplied the ontology folder, so its
verification attests accident-safety, not tamper-evidence, and the result says so. On the loading
operations, `lock_mode=verify|required` verifies the to-be-loaded closure before the workspace is
mutated, resolving the lockfile against the loaded document (beside-document default; the
policy-declared lockfile when the loaded document is the project's resolved root artifact); a mismatch,
or `required` with no lockfile, refuses the load with a structured error and mutates nothing.

The `release.format` (default `turtle`), `release.require_version_iri` (default true),
`release.require_clean_round_trip` (default true), and `release.output_dir` fields are consumed by the
release-bundle workflow: `run_release_gate` reads them to verify the serialization format, version IRI,
and round-trip fidelity, and `prepare_release` writes the bundle into `release.output_dir` (see the
[Release tools](tools/quality.html#run_release_gate)).

When `release.format` names an `obo` format and a term carries more than one single-valued frame tag
(name/comment/definition — which the OBO writer rejects), the release gate surfaces a
`round_trip.obo_compatibility` report and a `round_trip.lossy_format` warning and records a
`release.lossy_format` finding — a gate error while `release.require_clean_round_trip` is true (the
default), otherwise a warning. The RDF-based formats (`turtle`, `rdfxml`, `functional`, `owlxml`) and OBO
content that stays within the frame model are never flagged (OBO preserves general axioms, SWRL, keys, and
datatype definitions via its `owl-axioms:` escape hatch). The `round_trip.round_trip_class` field reports
whether the verified serialization is `byte_for_byte` or `axiom_identical`.

## Common result contracts

The companion
[`ontology-engineering-contracts-v1.schema.json`](https://github.com/hakjuoh/protege-mcp/blob/main/core/src/main/resources/schema/ontology-engineering-contracts-v1.schema.json)
defines the JSON representation of the new surface-neutral Java records:

- project coordinates: `project_id`, `policy_version`, `root_ontology`, and module IRIs;
- a complete model revision envelope: `workspace_id`, monotonic `session_revision`, and independent
  semantic/document SHA-256 fingerprints;
- a versioned ontology-fingerprint result with `cross_restart` versus `session_only` stability;
- findings with `source`, `severity`, focus/rule coordinates, optional waiver, and lossless `details`;
- validation stages, artifact references, and an aggregate gate result.

Gate meanings are deliberately distinct:

- `pass`: every required stage completed and no finding from a required stage reached the configured
  threshold; findings from optional stages remain visible but do not control the gate;
- `fail`: a required check completed and found a policy violation;
- `error`: a required stage was missing, skipped, or could not complete;
- `skipped`: a per-stage status only, for a stage policy made optional or inapplicable.

The pure aggregator and the `GateResult` constructor are fail-closed: an errored required stage takes
precedence over a separate policy failure, and a required legacy-style skip cannot become a vacuous pass.
The JSON Schema enforces structure; Java consumers must also construct records through the canonical
constructor and use `ContractJson.mapper()` so semantic contradictions, unknown fields, and duplicate
JSON keys are rejected.

## RDFC dataset identity versus fingerprint v2

`run_project_qc` returns both identities because they answer different questions:

| Field | Standard and scope | Purpose |
| --- | --- | --- |
| `rdf_dataset_fingerprint` | W3C RDFC-1.0 canonical N-Quads + SHA-256; asserted root ontology, ontology header/annotations, and direct import coordinates | Cross-application RDF dataset comparison; blank-node labels and statement order do not change it. |
| `semantic_fingerprint` (fingerprint v2) | Protégé MCP/OWLAPI canonical OWL contract | Same-session/cross-restart workspace revision, optimistic concurrency, preflight, and editor-aware semantics. |

Imported ontology content is excluded from the RDFC root digest; dependency content belongs in verified
import locks and release manifests. The `interoperability` stage computes RDFC from the same isolated
snapshot used by the other QC stages and fails closed on a timeout or canonicalization error.

### Fingerprint v2

Fingerprint v2 covers ontology/version IRIs, direct import declarations, ontology annotations, and active
ontology axioms including axiom annotations. It normalizes serializer-added unannotated declarations and
sorts fixed-prefix Functional Syntax object encodings before SHA-256. The document fingerprint additionally
covers document IRI, format, sorted live prefixes, and an optional import-lock digest. Imported content is
not silently folded in; direct import coordinates are, and later locked-import manifests carry dependency
content checksums. Version 2 includes built-in properties/datatypes and RDF 1.1 plain-string equivalence, so
Manchester and Turtle/RDF/XML round trips do not change the semantic digest. Version 1 digests are not
cross-version comparable.

OWLAPI anonymous-individual `NodeID`s are parser-local. An ontology containing anonymous individuals gets a
hashed same-session conflict token with `stability=session_only` and `release_stable=false`; raw NodeIDs are
not exposed, and a strict release manifest must reject that token.

## Compatibility baseline

The 0.5.0 public surface remains stored as deterministic test goldens for all 66 original tool registrations and 11
guided prompts. Tool snapshots include descriptions, input schemas, and the return fields captured from the manual;
prompt snapshots include their argument contracts and deterministic rendered messages. Tests prohibit
removing or changing an existing argument, adding a required prompt argument, or dropping a documented
result field. New optional fields remain possible through an explicitly reviewed contract change.

The 0.6.1 surface remains 78 tools + 11 prompts. The 0.6.0 additions within the milestone were
`get_model_revision`, the three change-set tools, verified save options, `semantic_diff`, and the three
import-lock/catalog tools; existing required arguments and interactive defaults remain backward-compatible.

Version 0.7.0 adds five tools for deterministic rebasing, change-impact analysis, policy scaffolding,
release gating, and release preparation, bringing that surface to 83 tools + 11 prompts. Version 0.7.1
adds the explicit redacted audit export, bringing the current surface to 84 tools + 11 prompts. The 0.5.0
snapshots continue to enforce backward compatibility for the original contracts.

Maintainers can generate a new historical baseline with:

```bash
mvn -pl plugin -am -Dtest=PublicContractSnapshotTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprotege.contract.snapshot.update=X.Y.Z test
```

Normal test runs never rewrite the goldens, and the published 0.5.0 baseline cannot be selected as an
update target. Canonical output always uses LF line endings; runtime prompts and tools must each have
exactly one matching documentation section.
