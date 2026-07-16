---
title: Project policy contracts
nav_order: 10
---

# Project policy contracts
{: .no_toc }

Version 0.6.0 delivers the executable project-policy workflow plus workspace revision envelopes,
memory-only preflight/commit change sets, verified atomic saves, deterministic import locks, asserted
semantic diff, and a standalone headless CLI prototype. Calls with no policy retain the existing
interactive defaults.
{: .note }

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
- [OBO-oriented policy](examples/project-policy/obo.yaml) — OBO IRIs and definition properties, ELK,
  ROBOT-compatible query directories, and OBO release format.

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
duplicate module coordinates, active/root ontology agreement, and installed required-reasoner availability.
It rejects duplicate YAML keys, trailing YAML documents, inputs over 1 MiB, URL-shaped asset paths, missing
glob matches, and assets escaping `project_root` unless the policy explicitly enables the local-admin
external-path compatibility profile.

Discovery order is:

1. explicit `policy_path`;
2. the nearest `.protege-mcp/project.yaml` found while walking from the active local ontology document
   toward the filesystem root;
3. no policy (`policy_loaded=false`), which preserves the 0.5.x interactive behavior.

A policy stored in a directory named `.protege-mcp` anchors the project at that directory's parent —
the directory that contains `.protege-mcp` — so the canonical layout keeps ontology sources beside the
`.protege-mcp` directory. A policy stored anywhere else (including one named by an explicit
`policy_path`) anchors at its own directory. `project_root` (default `.`) resolves against that anchor
and may not escape it. Every relative asset path — module paths, the import lockfile, invariant/SHACL
paths and glob bases, competency-question paths, and the release output directory — resolves against
the effective project root, never against the process working directory. Effective defaults
are materialized before a canonical `policy_digest` is computed, so YAML comments and key order do not
change the digest.

## Executable QC

Use `get_project_policy` to inspect discovery/defaults and `validate_project_policy` to require a valid
policy without running ontology checks. `run_project_qc` executes every `validation.required_stages` entry;
`reasoning.required: true` also forces `reasoner` into the effective required-stage set even if the authored
list omitted it. The `interoperability` stage is always required in policy v1, even when an authored stage
list omits it.
It requires Protégé's selected reasoner to match the named policy reasoner, and distinguishes:

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
See [ADR 0002](adr/0002-isolated-reasoner-configuration-parity.html).

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
presence/language/literal datatype/non-placeholder text, lifecycle status/replacement integrity, and active
or expired waivers. Active waivers remain visible with `waived_count`; expired waivers become findings.
`write_import_lock`, `verify_import_lock`, and `validate_catalog` provide deterministic no-network dependency
checks. `run_project_qc` now fails closed on an unresolved import closure when the policy sets
`imports.fail_on_missing: true` or `imports.mode: locked`, but it does not yet verify lockfile checksums
automatically (use `verify_import_lock`). Policy capabilities are also not yet applied to every legacy tool's
direct path argument, and module declaration conflicts/import cycles remain inspection findings rather than a
complete release-bundle gate.

Reserved (schema-accepted, not yet enforced): the `network.default`/`network.allowed_hosts` fields, the
`imports.network` override, the `modules[].owned_namespaces` declarations, and the
`release.format`/`release.require_version_iri`/`release.require_clean_round_trip` fields validate and carry
defaults but are not yet consulted at runtime — in particular, namespace ownership is not yet checked
against module contents. They are accepted now so policies can declare intent without a schema break when
enforcement lands.

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
not exposed, and a strict release manifest must reject that token. See
[ADR 0001](https://github.com/hakjuoh/protege-mcp/blob/main/docs/adr/0001-ontology-fingerprint-canonicalization.md).

## Compatibility baseline

The 0.5.0 public surface remains stored as deterministic test goldens for all 66 original tool registrations and 11
guided prompts. Tool snapshots include descriptions, input schemas, and the return fields captured from the manual;
prompt snapshots include their argument contracts and deterministic rendered messages. Tests prohibit
removing or changing an existing argument, adding a required prompt argument, or dropping a documented
result field. New optional fields remain possible through an explicitly reviewed contract change.

The 0.6.0 surface is 78 tools + 11 prompts. Later additions within the milestone are
`get_model_revision`, the three change-set tools, verified save options, `semantic_diff`, and the three
import-lock/catalog tools; existing required arguments and interactive defaults remain backward-compatible.

Maintainers can generate a new historical baseline with:

```bash
mvn -pl plugin -am -Dtest=PublicContractSnapshotTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprotege.contract.snapshot.update=X.Y.Z test
```

Normal test runs never rewrite the goldens, and the published 0.5.0 baseline cannot be selected as an
update target. Canonical output always uses LF line endings; runtime prompts and tools must each have
exactly one matching documentation section.
