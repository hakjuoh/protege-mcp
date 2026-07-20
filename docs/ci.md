---
title: Ontology CI
nav_order: 10
---

# Ontology CI for your project
{: .no_toc }

Gate ontology pull requests with the reusable workflow published by this project. It downloads and
verifies the standalone CLI, runs complete offline project QC and a release preview without Protégé,
and carries the result across a fork-safe trust boundary.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What the gate runs

The untrusted pull-request job produces five kinds of evidence:

- Candidate policy validation. The PR policy is checked as a proposed change with network and external
  paths forbidden, but it never judges the ontology change in that same PR.
- Trusted full project QC. The workflow copies the base branch's policy, validation assets, imports, and
  catalog into a private workspace, replaces only the configured root ontology with candidate bytes, and
  runs every policy-required stage with the bundled HermiT reasoner.
- JSON, JUnit, and SARIF reports from that trusted workspace. The three executions must agree on their
  exit verdict.
- A dry-run release gate with the generated artifact paths, sizes, and SHA-256 checksums. It writes no
  release directory.
- An asserted semantic diff between the base and candidate ontology. It is report-only by default;
  `diff_check: true` turns any difference into a failure.

This workflow requires `protege-mcp-cli` 0.7.1 or newer. A policy used by the standalone runner must name
the bundled `HermiT` baseline. It may still require an OWL 2 EL, QL, RL, or DL profile independently.

Only the configured root ontology is overlaid onto the trusted base workspace. Changes to modules,
catalogs, shapes, queries, or policy files are validated as proposals but do not become trusted gate inputs
until merged. Split such changes from the ontology change when they must take effect together, or add a
repository-specific trusted staging step with an equally strict allowlist.

The base branch must already contain the configured project policy and the root ontology's parent
directory. Bootstrap a new project policy through normal review first, then enable this gate; a candidate
policy is deliberately never trusted to bootstrap its own judge.

## How to call it

Put two workflows in your ontology repository. Ready-to-copy versions live under
[`docs/examples/ci/`](https://github.com/hakjuoh/protege-mcp/tree/main/docs/examples/ci):

| Example | Role |
| --- | --- |
| [`general-owl.yml`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/examples/ci/general-owl.yml) | Untrusted PR workflow for a general OWL project. |
| [`obo.yml`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/examples/ci/obo.yml) | Untrusted PR workflow for an OBO-compatible project. |
| [`annotate.yml`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/examples/ci/annotate.yml) | Trusted `workflow_run` companion that verifies and annotates. |
| [`validate-ontology.sh`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/examples/ci/validate-ontology.sh) | CI-agnostic shell runner for GitLab, Jenkins, or local use. |

When upgrading a 0.7.0 caller, add the required `ontology` input to the PR workflow and
`expected_release_check` to the annotator at the same time. The latter must match `release_check`.

The PR workflow has read-only repository access:

```yaml
name: Ontology CI
on:
  pull_request:
jobs:
  ontology-ci:
    permissions:
      contents: read
    uses: hakjuoh/protege-mcp/.github/workflows/ontology-ci.yml@v0.7.2
    with:
      project: .protege-mcp/project.yaml
      ontology: ontology.ttl
      cli_version: '0.7.2'
      release_check: true
      diff_check: false
```

The trusted companion runs from the default branch after the PR workflow completes:

```yaml
name: Ontology CI annotate
on:
  workflow_run:
    workflows: [Ontology CI]
    types: [completed]
jobs:
  annotate:
    permissions:
      contents: read
      actions: read
      checks: write
      pull-requests: write
    uses: hakjuoh/protege-mcp/.github/workflows/ontology-annotate.yml@v0.7.2
    with:
      run_id: ${{ github.event.workflow_run.id }}
      producer_event: ${{ github.event.workflow_run.event }}
      producer_conclusion: ${{ github.event.workflow_run.conclusion }}
      producer_head_sha: ${{ github.event.workflow_run.head_sha }}
      expected_workflow: Ontology CI
      expected_workflow_path: .github/workflows/ontology-ci.yml
      expected_project: .protege-mcp/project.yaml
      expected_ontology: ontology.ttl
      expected_diff_check: false
      expected_cli_version: '0.7.2'
      expected_release_check: true
```

Require the fixed check name **`Protégé MCP ontology gate`** in branch protection. The annotator publishes
that check on the authenticated PR-head SHA; a `workflow_run` job's ordinary status belongs to the default
branch SHA and is not an adequate branch-protection target.

{: .warning }
> The examples use a version tag for readability. Tags in this repository can be re-cut. Pin both reusable
> workflows to reviewed 40-character commit SHAs in production, and pin `cli_version` to a reviewed release.

## Fork-safe trust split

Ontology PRs commonly come from forks. The pipeline deliberately separates computation from authority:

1. The untrusted `pull_request` job checks out candidate bytes with `contents: read`, no secrets, no PR
   write scope, and no code-scanning scope. It never uses `pull_request_target`.
2. It also checks out the immutable base SHA, creates a private judge workspace from those trusted bytes,
   and overlays only the confined candidate root ontology. Fixed `--no-network --no-external` flags apply
   regardless of either policy.
3. Results are written to a fresh directory outside the fork checkout. An exact filename allowlist and a
   checksum manifest cover candidate-policy JSON, full-QC JSON/JUnit/SARIF, optional release evidence,
   diff evidence, and provenance metadata.
4. The trusted `workflow_run` job authenticates the producer run through GitHub's API, resolves the open PR
   from its head SHA, and requires the caller workflow file to be byte-identical at the PR head and every
   candidate base SHA.
5. It caps artifact size, rejects links/directories/extras, rehashes every allowlisted file itself, verifies
   run/head/input metadata, and validates JSON, JUnit XML, SARIF, release paths, and checksums as data.
6. Only after verification does it post a comment and rebuild at most 50 bounded check-run annotations from
   SARIF fields. Artifact SARIF is never executed and is not uploaded to code scanning.

The PR cannot grant itself network access, select an external path, relax the base policy, replace shapes or
queries used by the gate, route work to a self-hosted runner, or obtain a write-scoped token.

If the base branch advances after a validation run, the trusted annotator rejects the old evidence instead
of silently comparing it to a different base. Synchronize or update the pull request to trigger a fresh
pull-request run; rerunning the old event is intentionally insufficient.

## Evidence files

The untrusted artifact contains only these file families:

- `candidate-policy.*` — proposed policy result, stderr, and exit code.
- `qc.json*`, `qc.junit.xml*`, `qc.sarif*` — trusted full-QC reports, stderr, and exit codes.
- `release.*` — optional dry-run release result, stderr, and exit code.
- `diff.*` — asserted diff result or the exact `baseline-absent` marker.
- `meta.json`, `pr-number.txt`, and `manifest.sha256` — authenticated inputs and complete file integrity.

The trusted comment reports stage verdicts, finding count, semantic fingerprint, release artifact
checksums, and asserted-diff summary. JUnit remains available to CI systems that ingest test reports; SARIF
supplies bounded PR-head check annotations without broadening token permissions.

## Downloading and verifying the CLI

The reusable workflow and generic shell example verify the published executable before use:

```bash
CLI_VERSION=0.7.2
base="https://github.com/hakjuoh/protege-mcp/releases/download/v${CLI_VERSION}"
jar="protege-mcp-cli-${CLI_VERSION}-all.jar"
curl --fail --location --retry 3 --retry-all-errors -o "$jar" "${base}/${jar}"
curl --fail --location --retry 3 --retry-all-errors -o "${jar}.sha256" "${base}/${jar}.sha256"
sha256sum -c "${jar}.sha256"
```

The sidecar records a bare basename. `--retry-all-errors` tolerates the brief 404 window while a moved tag's
release is deleted and recreated; it does not weaken checksum verification.

## Other CI systems

The generic script emits the same candidate-policy, QC, release, diff, exit-code, stderr, and checksum
file families (GitHub-specific provenance metadata is omitted). It uses either GNU `sha256sum` or the
macOS `shasum`, writes through a fresh staging directory, and preserves the complete evidence set before
returning a failed gate. For trusted branches or local hooks, `TRUSTED_PROJECT` may equal `PROJECT`. For
untrusted merge requests, prepare a base-reviewed workspace first and point `TRUSTED_PROJECT` at its
policy; do not let the candidate policy or validation assets judge themselves.

```bash
CLI_VERSION=0.7.2 \
PROJECT=.protege-mcp/project.yaml \
TRUSTED_PROJECT=/tmp/trusted-project/.protege-mcp/project.yaml \
ONTOLOGY=ontology.ttl \
BASELINE=/tmp/base/ontology.ttl \
RESULTS=ontology-ci-results \
./validate-ontology.sh
```

## See also

- [Headless CLI](cli.html) — commands, report formats, and exit codes.
- [Project policy & QC](project-policy.html) — required stages and validation assets.
- [Contributing](contributing.html) — this repository's own build CI.
