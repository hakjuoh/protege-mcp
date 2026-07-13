---
title: Project policy contracts
nav_order: 10
---

# Project policy contracts
{: .no_toc }

Version 0.5.1 establishes the versioned data contracts needed for reproducible project QC. It does
**not** yet discover or execute a policy in Protégé: `get_project_policy`, `run_project_qc`, import
locking, change sets, and release gates remain roadmap work. Existing tools and their interactive
defaults are unchanged.
{: .warning }

## Policy v1 authoring format

The proposed authoring file is `.protege-mcp/project.yaml`. Its canonical JSON Schema is
[`project-policy-v1.schema.json`](https://github.com/hakjuoh/protege-mcp/blob/main/src/main/resources/schema/project-policy-v1.schema.json).
The schema requires `version: 1`, a project id, and an absolute root-ontology IRI. It rejects unknown
fields rather than silently assigning them a future meaning.

Start from the example closest to the project:

- [Minimal policy](examples/project-policy/minimal.yaml) — identity, fail-closed import handling,
  explicit filesystem/network defaults, and the core QC stages.
- [General OWL policy](examples/project-policy/general-owl.yaml) — modules, labels/definitions, IRI
  policy, locked imports, persisted invariants/CQs/SHACL, waivers, and release output.
- [OBO-oriented policy](examples/project-policy/obo.yaml) — OBO IRIs and definition properties, ELK,
  ROBOT-compatible query directories, and OBO release format.

Schema validation checks data shape, enumerated values, duplicate stages, positive timeouts, and
cross-field requirements such as a lockfile for `imports.mode: locked`. Context-dependent checks still
belong to the future policy loader: path containment/capabilities, referenced-file existence, CURIE
prefix resolution, Java-regex compilation, selected-reasoner availability, and contradictory runtime
settings. A policy is therefore not a release attestation merely because it validates against the
schema.

## Common result contracts

The companion
[`ontology-engineering-contracts-v1.schema.json`](https://github.com/hakjuoh/protege-mcp/blob/main/src/main/resources/schema/ontology-engineering-contracts-v1.schema.json)
defines the JSON representation of the new surface-neutral Java records:

- project coordinates: `project_id`, `policy_version`, `root_ontology`, and module IRIs;
- a complete model revision envelope: `workspace_id`, monotonic `session_revision`, and independent
  semantic/document SHA-256 fingerprints;
- findings with `source`, `severity`, focus/rule coordinates, optional waiver, and lossless `details`;
- validation stages, artifact references, and an aggregate gate result.

Gate meanings are deliberately distinct:

- `pass`: every required stage completed and no finding reached the configured threshold;
- `fail`: a required check completed and found a policy violation;
- `error`: a required stage was missing, skipped, or could not complete;
- `skipped`: a per-stage status only, for a stage policy made optional or inapplicable.

The pure aggregator is fail-closed: an errored required stage takes precedence over a separate policy
failure, and a required legacy-style skip cannot become a vacuous pass.

## Compatibility baseline

The 0.5.0 public surface is stored as deterministic test goldens for all 66 tool registrations and 11
guided prompts. Tool snapshots include input schemas and the return fields captured from the manual;
prompt snapshots include their argument contracts and deterministic rendered messages. Tests prohibit
removing or changing an existing argument, adding a required prompt argument, or dropping a documented
result field. New optional fields remain possible through an explicitly reviewed contract change.

Maintainers can generate a new historical baseline with:

```bash
mvn -Dtest=PublicContractSnapshotTest \
  -Dprotege.contract.snapshot.update=X.Y.Z test
```

Normal test runs never rewrite the goldens.
