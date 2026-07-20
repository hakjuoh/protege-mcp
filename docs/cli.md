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
{: .fs-6 .fw-300 }

## Commands

```bash
java -jar protege-mcp-cli-{{ site.version }}-all.jar --version
java -jar protege-mcp-cli-{{ site.version }}-all.jar --help
java -jar protege-mcp-cli-{{ site.version }}-all.jar validate-policy --project .protege-mcp/project.yaml [--no-network] [--no-external]
java -jar protege-mcp-cli-{{ site.version }}-all.jar validate --project .protege-mcp/project.yaml [--format json|markdown|junit|sarif] [--no-network] [--no-external]
java -jar protege-mcp-cli-{{ site.version }}-all.jar imports lock --project .protege-mcp/project.yaml [--dry-run] [--output FILE] [--no-network]
java -jar protege-mcp-cli-{{ site.version }}-all.jar release --project .protege-mcp/project.yaml [--dry-run] [--output DIR] [--baseline MANIFEST] [--created-at UTC] [--limit N] [--no-network] [--no-external]
java -jar protege-mcp-cli-{{ site.version }}-all.jar diff --left previous.ttl --right current.ttl [--check] [--no-network]
java -jar protege-mcp-cli-{{ site.version }}-all.jar serve --transport stdio --project .protege-mcp/project.yaml [--capabilities CAP,...]
```

`validate-policy` prints the resolved path/root, canonical policy digest, and structured validation
issues as JSON. It validates syntax, semantic references, and local assets; installed Protégé reasoner
availability is deliberately deferred to the adapter that executes project QC. `--no-external` refuses
even a policy that would otherwise grant external paths, and `--no-network` records the headless runtime's
fixed offline posture in the result.

`validate` captures the policy, root ontology, complete local import closure, and validation assets in an
offline snapshot, then runs the policy's required subset of the same eight ordered project-QC stages used
by the plugin: interoperability, reasoner, profile, governance, structural, invariants, competency
questions, and SHACL. The
shaded executable includes HermiT; its legacy JAutomata runtime is replaced by maintained AutomataLib.
One classification is shared across inference-dependent stages, required missing/malformed assets fail
closed, source drift is rejected, and reports contain project-relative paths. JSON is the default;
Markdown, JUnit, and SARIF are also supported.

The repository continuously executes one versioned ontology project through the plugin and headless
adapters and compares a committed machine-evidence record. It pins stage verdicts, finding identity,
semantic and closure fingerprints, HermiT identity, deterministic full-IRI/CURIE grounding, import-lock
bytes, release identity, and common portable artifact checksums. Surface-specific diagnostics may render
differently, but they cannot change the gate or portable identities.

`imports lock` captures the same offline closure and emits a deterministic version-1 lock candidate for
every resolved local import. `--dry-run` returns the complete candidate without writing. A write uses a
checksum-guarded sibling stage and atomic replacement; replacing a lock preserves a verified backup.
`--output` must remain within an existing directory under the project root. A policy-declared lock that
does not exist may be bootstrapped from local module/catalog mappings. When updating an existing lock,
its paths may be used only to discover local documents; its old checksums are not trusted and are replaced
with hashes of captured bytes. Imports must be beneath the output file's parent because lock entries cannot
contain absolute paths or `..`; moving `--output` into a child directory may therefore be invalid. An
alternate output does not change `imports.lockfile` in the policy, so later `validate` runs continue to use
the declared path. The command never fetches an import.

`release` captures one offline snapshot and runs the complete project-QC and release-only gates through
the shared headless core. It verifies the version IRI, local import provenance, fingerprint stability,
release-format round trip, optional baseline manifest and every artifact referenced by that baseline.
The resulting bundle contains the verified ontology, JSON/Markdown/JUnit/SARIF reports, optional asserted
baseline diff, RO-Crate metadata, and terminal `manifest.json` with checksums for every preceding artifact.

`--dry-run` computes the complete bundle with the deterministic `PREVIEW` timestamp, returns artifact
checksums plus bounded report/crate previews, and writes nothing. A committing run reads the UTC clock once
unless `--created-at` supplies an explicit reproducibility timestamp. `--output` overrides
`release.output_dir`; both are confined beneath the project root and require an existing parent directory.
Publication stages the entire tree in an owner-only sibling directory, rechecks every project and baseline
source plus the previous output identity, then atomically replaces the directory. A replacement preserves a
checksum-named backup whose project-relative path is returned. Gate failure, source drift, concurrent output
change, staged tampering, or a rename failure cannot expose a partially written bundle. `--baseline` may name
a project-relative manifest, including a manifest inside the directory being replaced; all of its artifact
paths, sizes, and hashes are verified before comparison.

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

## Stdio MCP server

`serve --transport stdio` runs the shaded JAR as a standard newline-delimited MCP child process. It exposes
only the project-confined headless operations that share completed core services:

- `get_headless_capabilities`
- `validate_project_policy`
- `run_project_qc`
- `verify_import_lock`
- `write_import_lock`
- `run_release_gate`
- `prepare_release`
- `export_audit_log`

Call `get_headless_capabilities` to inspect the effective capability profile, protocol limits, the supported
set, and the exact live Protégé tool names that are unavailable in headless mode. GUI state, renderers,
the active reasoner, confirmation dialogs, and Undo are never simulated. Unavailable tools are omitted from
`tools/list` instead of being advertised as working stubs.

The process is always offline and confines every caller-supplied baseline/output path beneath the fixed
project root. `write_import_lock`, `prepare_release`, and `export_audit_log` default to `dry_run=true`; an explicit false value
uses the same checksum/drift/atomic-replacement safeguards as their CLI commands. The server serializes
workspace operations within one session, rejects inbound JSON-RPC lines over 1 MiB and outbound lines over
8 MiB, and writes only protocol messages to stdout. Startup,
configuration, and transport failures go to stderr.

Stdio authentication is the operating system's child-process boundary. The default local profile contains
the exact capabilities needed by the eight supported tools, including `server:admin` for the fixed
`export_audit_log` operation, but it has no network or external-filesystem authority. `--capabilities`
can narrow it further with a comma- or whitespace-separated list of
exact public scopes, for example:

```bash
java -jar protege-mcp-cli-{{ site.version }}-all.jar serve --transport stdio \
  --project .protege-mcp/project.yaml \
  --capabilities ontology:read,filesystem:project:read
```

Capabilities never expand the fixed offline/project confinement. A read profile can validate policy, run QC,
verify a lock, and inspect the surface; lock writes and release operations are rejected before their handlers
run when their declared capabilities are absent.

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

The headless CLI performs no network access — project imports resolve only through project-confined local
module, catalog, and lock mappings, while asserted `diff` never dereferences imports. `--no-network` is
accepted on `validate`, `imports lock`, `release`, and `diff` as a redundant affirmation of `network=deny` and is
recorded in the output.

### `--no-external`

`validate-policy --no-external` applies an untrusted-CI filesystem constraint: external policy assets are
disabled before resolution, and a policy that enables `filesystem.allow_external_paths` receives an
`external_paths_forbidden` validation error. Headless `validate` and `release` are unconditionally
project-confined — their workspace capture enforces the same constraint with or without the flag, so a
policy granting external paths always yields the structured `external_paths_forbidden` result — and their
`--no-external` flags are recorded affirmations of that fixed posture. The authored policy/digest is
not rewritten. The reusable PR workflow always supplies the flag.

## Exit codes

- `0` — the command/gate passed. `--help` (also `-h`/`help`) and `--version` print to **stdout** and
  exit `0`.
- `1` — a validation/release gate failed: a clean, loaded, but non-passing result. `validate-policy`
  and `validate` return `1` for a policy that loaded and was fully evaluated (a canonical
  `policy_digest` was computed) but whose verdict is invalid, and `diff --check` returns `1` when the
  ontologies differ.
- `2` — a configuration or usage error: an unknown command or flag, a missing/unparseable/oversized
  policy that never reached evaluation, or a report format that is not available headlessly.
- `3` — an execution or infrastructure error: a document/workspace that could not be loaded, a required
  release stage that could not execute, source drift, unavailable verified release bytes, or a release
  manifest that failed integrity (path escape, missing artifact, or sha256 mismatch).

**Behavior change:** in `0.6.1` an invalid-but-loaded policy exited `2`. From this release
`validate-policy`/`validate` map that gate-style failure to exit `1`; a policy that could not be loaded
or parsed still exits `2`. Scripts that treated any non-zero code as failure are unaffected; scripts
that branched specifically on `2` for a failed policy should now branch on `1`. Also from this release:
`--help` prints usage to stdout and exits `0` (previously usage went to stderr with exit `2`);
`policy_loaded` in the `validate-policy`/`validate` JSON is `true` only for a policy that actually
reached evaluation (a canonical `policy_digest` was computed) and `false` for the never-evaluated
exit-`2` states (`policy_not_found`, `yaml_invalid`); stderr is now free of the earlier
`No SLF4J providers` warning on every command; and every JSON document ends with a trailing newline.

Release assets include SHA-256 sidecars, the project license, third-party notices, and a headless-compliance
bundle containing the GPL/LGPL texts, HermiT corresponding source JAR, and relinking instructions required
for the reasoner-bearing executable.

## Continuous integration

To gate pull requests in your own ontology repository with these commands, use the reusable
GitHub Actions workflow described on the [Ontology CI](ci.html) page. It downloads and verifies this
CLI with a fork-safe two-workflow architecture, validates the candidate policy, runs trusted full
project QC and a dry-run release preview, publishes the JSON/JUnit/SARIF evidence, reports the
asserted semantic diff, and annotates the PR.
