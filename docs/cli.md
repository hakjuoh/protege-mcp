---
title: Headless CLI
nav_order: 9
---

# Headless CLI
{: .no_toc }

Version {{ site.version }} ships a separately tested Java 17 artifact,
`protege-mcp-cli-<version>-all.jar`. It embeds OWLAPI, contains no Protégé editor classes, and does not
need a local Maven repository after download. The existing `protege-mcp-<version>.jar` remains the
Protégé OSGi plugin and is not a standalone launcher.

## Commands

```bash
java -jar protege-mcp-cli-{{ site.version }}-all.jar --version
java -jar protege-mcp-cli-{{ site.version }}-all.jar validate-policy --project .protege-mcp/project.yaml
java -jar protege-mcp-cli-{{ site.version }}-all.jar validate --project .protege-mcp/project.yaml [--format json|markdown] [--no-network]
java -jar protege-mcp-cli-{{ site.version }}-all.jar diff --left previous.ttl --right current.ttl [--check] [--no-network]
```

`validate-policy` prints the resolved path/root, canonical policy digest, and structured validation
issues as JSON. It validates syntax, semantic references, and local assets; installed Protégé reasoner
availability is deliberately deferred to the adapter that executes project QC.

`validate` runs the same headless policy validation and adds a top-level `scope: "policy_validation"`
and a `note` recording that the full, reasoner-dependent project QC gate
(reasoner/profile/structural/governance/invariants/cqs/shacl stages) is **not** run here — that gate
needs the in-Protégé `run_project_qc` tool or a future headless runner with a bundled reasoner.
`--format json` (the default) prints the policy result; `--format markdown` prints a human-readable
summary. `--format junit` and `--format sarif` are intentionally rejected as configuration errors:
those emitters project a full QC gate result (with a semantic fingerprint) that policy-only validation
cannot honestly produce without a reasoner, so the CLI refuses to fabricate one.

`diff` privately loads both ontology documents and prints the same asserted semantic categories used by
the plugin's `semantic_diff` core: headers/imports, entities, rename candidates, annotation changes,
asserted axiom groups, and conservative compatibility classification. The comparison is asserted-only,
so the CLI never fetches an `owl:imports` target while loading either document: every declared import is
satisfied by one shared private empty placeholder instead (a single temporary file per document, however
many imports are declared — some OWLAPI parsers cannot tolerate a wholly missing import), its axioms are
never loaded, and it is reported in the top-level `left_unresolved_imports` and
`right_unresolved_imports` arrays. This keeps the command deterministic, prevents network access, and
makes the deliberately omitted imports closure visible in its output.

`--check` turns `diff` into a CI gate: it exits `1` when the two ontologies are not identical
(`identical: false`) and `0` otherwise. Without `--check`, `diff` always exits `0` regardless of the
outcome, preserving the released behavior.

### Diffing against a release manifest

Either `diff` operand may be a release `manifest.json` (a file whose JSON contains `manifest_version`)
instead of an ontology document:

```bash
java -jar protege-mcp-cli-{{ site.version }}-all.jar diff --left releases/1.3.0/manifest.json --right ontology.ttl
```

A manifest is resolved to its **primary ontology artifact** — `artifacts[0]`, which a release manifest
always lists first — resolved relative to the manifest's own directory. Before any diff, the CLI:

- re-validates the artifact path for containment: an absolute path, a `..` escape above the manifest
  directory, or a symlink pointing outside it is refused; and
- verifies the artifact's `sha256` against the file on disk.

A path escape, a missing artifact, or a sha256 mismatch is reported as an execution error (exit `3`) so
a tampered or relocated release input is never silently diffed. On success the result gains a
`left_manifest`/`right_manifest` block with `version_iri`, `artifact_path`, and `sha256_verified`. A
plain ontology-file operand is unchanged.

### `--no-network`

The headless CLI already performs no network access — `owl:imports` are never dereferenced, and policy
validation reads only local files. `--no-network` is accepted on `validate` and `diff` as a redundant
affirmation of `network=deny` (never an error) and is recorded in the output, matching
[ADR 0005](adr/0005-import-network-policy-defaults.html) decision 6.

## Exit codes

- `0` — the command/gate passed.
- `1` — a validation/release gate failed: a clean, loaded, but non-passing result. `validate-policy`
  and `validate` return `1` for a policy that loaded and was fully evaluated (a canonical
  `policy_digest` was computed) but whose verdict is invalid, and `diff --check` returns `1` when the
  ontologies differ.
- `2` — a configuration or usage error: an unknown command or flag, a missing/unparseable/oversized
  policy that never reached evaluation, or a report format that is not available headlessly.
- `3` — an execution or infrastructure error: a document that could not be loaded, or a release
  manifest that failed integrity (path escape, missing artifact, or sha256 mismatch).

**Behavior change:** in `0.6.1` an invalid-but-loaded policy exited `2`. From this release
`validate-policy`/`validate` map that gate-style failure to exit `1`; a policy that could not be loaded
or parsed still exits `2`. Scripts that treated any non-zero code as failure are unaffected; scripts
that branched specifically on `2` for a failed policy should now branch on `1`.

Release assets include SHA-256 sidecars, the project license, and third-party notices.

## Continuous integration

To gate pull requests in your own ontology repository with these commands, use the reusable
GitHub Actions workflow described on the [Ontology CI](ci.html) page. It downloads and verifies this
CLI, runs the policy-validation and asserted-diff gates with a fork-safe two-workflow architecture,
and annotates the PR — honestly scoped to what the headless CLI can do (no reasoner QC).

## Not yet available headlessly

The following roadmap commands are **not** shipped as CLI commands — invoking them prints a clear
"not yet available in the headless CLI" message and exits `2` rather than pretending to run:

- `release [--dry-run]` and `imports lock` — full project QC and release orchestration require a
  bundled, license-reviewed baseline reasoner (a distribution decision gated by
  [PLAN](https://github.com/hakjuoh/protege-mcp/blob/main/PLAN.md) §10.2) and a headless workspace
  adapter with atomic mutation (§10.3), neither of which the current CLI has.
- `validate`'s full QC stages (reasoner/profile/structural/governance/invariants/cqs/shacl) — same
  reasoner and workspace-adapter prerequisites; `validate` today validates only the project policy.
- `serve --transport stdio` — needs a headless workspace adapter and the plugin's tool catalog moved
  to core.

Run these from the in-Protégé MCP tools (for example `run_project_qc` and the release tools) for now.
The CLI surface is a deliberately bounded prototype: roadmap work stays visibly deferred rather than
hidden or half-implemented.
