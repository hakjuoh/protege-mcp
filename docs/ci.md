---
title: Ontology CI
nav_order: 10
---

# Ontology CI for your project
{: .no_toc }

Gate pull requests in **your** ontology repository with the reusable workflow published by this
project. It downloads the [headless CLI](cli.html), verifies it, and runs two gates against your
project — without a local Protégé.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What it gates (and what it does not)

The reusable workflow runs the headless CLI, so it gates exactly what the CLI can do headlessly:

- **Policy validation** — `validate --project <policy> --no-network`: your project policy is
  well-formed, its semantic references resolve, and its local assets exist.
- **Asserted semantic diff** — `diff --left <baseline> --right <ontology> --no-network`: the
  asserted-only semantic change between the base branch's ontology and the PR's ontology.

{: .warning }
> This is a **policy-validation + asserted-diff gate, not a full ontology QC gate.** It does **not**
> run the reasoner, OWL 2 profile conformance, or the governance / invariants / competency-question /
> SHACL stages. The headless CLI bundles no reasoner, so those stages need the in-Protégé
> [`run_project_qc`](project-policy.html) tool or a future bundled-reasoner headless runner. See
> [the CLI page](cli.html#not-yet-available-headlessly) for the deferred surface. For OBO/ROBOT
> projects, complement this workflow with a ROBOT job (`robot reason` / `robot report`) for the
> reasoner-dependent checks.

The workflow requires **protege-mcp-cli 0.7.0 or newer** — the first release that ships
`validate --no-network` and `diff --check`. (The current plugin release on this site is
`{{ site.version }}`; the CLI version is selected separately by the `cli_version` input.)

## How to call it

Two files go in your ontology repository's `.github/workflows/`. Ready-to-copy versions live under
[`docs/examples/ci/`](https://github.com/hakjuoh/protege-mcp/tree/main/docs/examples/ci):

| Example | Role |
| --- | --- |
| [`general-owl.yml`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/examples/ci/general-owl.yml) | PR workflow for a general OWL project (pairs with [`general-owl.yaml`](examples/project-policy/general-owl.yaml)). |
| [`obo.yml`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/examples/ci/obo.yml) | PR workflow for an OBO/ROBOT-compatible project (pairs with [`obo.yaml`](examples/project-policy/obo.yaml)). |
| [`annotate.yml`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/examples/ci/annotate.yml) | The trusted `workflow_run` companion that posts the PR comment. |
| [`validate-ontology.sh`](https://github.com/hakjuoh/protege-mcp/blob/main/docs/examples/ci/validate-ontology.sh) | A CI-agnostic shell script for any other CI (GitLab, Jenkins, a pre-commit hook). |

The PR workflow (untrusted) calls the reusable validation workflow:

```yaml
name: Ontology CI
on:
  pull_request:
jobs:
  ontology-ci:
    permissions:
      contents: read
    uses: hakjuoh/protege-mcp/.github/workflows/ontology-ci.yml@v0.7.0
    with:
      project: .protege-mcp/project.yaml
      ontology: ontology.ttl
      cli_version: '0.7.0'
```

The companion (trusted) workflow posts the annotation:

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
      pull-requests: write
    uses: hakjuoh/protege-mcp/.github/workflows/ontology-annotate.yml@v0.7.0
    with:
      run_id: ${{ github.event.workflow_run.id }}
      producer_event: ${{ github.event.workflow_run.event }}
      producer_conclusion: ${{ github.event.workflow_run.conclusion }}
      expected_workflow: Ontology CI
```

Make the **annotate** job's status a required check in your branch protection. It runs from your
default branch (a fork PR cannot alter it), verifies the artifact, posts the comment, **and turns red
when the gate failed** — so requiring it actually blocks a policy-invalid or diff-failed PR from
merging. (The untrusted validation job also fails on a bad gate and a fork cannot alter the
base-pinned reusable workflow, so requiring that check instead is equally sound; whichever you
require, it goes red on failure.)

## Downloading and verifying the CLI

Both the reusable workflow and the shell script download the published asset and verify it before
running anything:

```bash
CLI_VERSION=0.7.0   # pin to a release you reviewed; see the re-cut caveat below
base="https://github.com/hakjuoh/protege-mcp/releases/download/v${CLI_VERSION}"
jar="protege-mcp-cli-${CLI_VERSION}-all.jar"
curl --fail --location --retry 3 --retry-all-errors -o "$jar" "${base}/${jar}"
curl --fail --location --retry 3 --retry-all-errors -o "${jar}.sha256" "${base}/${jar}.sha256"
sha256sum -c "${jar}.sha256"   # the sidecar records the bare basename, so this checks the local jar
```

The release publishes each asset's `.sha256` sidecar with a **bare basename**, so `sha256sum -c`
verifies the jar in the working directory. `--retry-all-errors` rides the brief 404 window a release
re-cut opens (the release is deleted and recreated on a moved tag).

## Fork-safe architecture (and why)

Ontology PRs routinely come from forks. A naive PR gate that had write access — or that used
`pull_request_target` to check out fork code — would hand an attacker's PR the repository's token.
This pipeline follows the two-workflow split from [PLAN §10.4](https://github.com/hakjuoh/protege-mcp/blob/main/PLAN.md):

1. **Untrusted validation** (`ontology-ci.yml`, triggered by your `pull_request` workflow). Runs the
   fork's proposed code, so it gets a **read-only token, no secrets, and `permissions: contents:
   read`** — no PR-write, no security-events. It writes its verdict **only to an uploaded artifact**,
   never to the PR.
2. **Trusted annotation** (`ontology-annotate.yml`, triggered by your `workflow_run` companion).
   GitHub always runs a `workflow_run` workflow from your repository's **default branch**, so a fork
   PR cannot modify it. It is the **only** place `pull-requests: write` lives. It downloads the
   untrusted run's artifact **as data**, verifies **producer, run-id, digest, and size** — plus an
   exact **filename allowlist** — before trusting a byte, then posts the comment and propagates the
   verdict to its own conclusion. It **never executes any string or path taken from the artifact**: it
   parses the JSON as data and reads a verified integer PR number.

Two details make the artifact channel safe against a hostile fork:

- The untrusted job writes every result into a **fresh directory outside the fork checkout**
  (`$RUNNER_TEMP/results`), so a PR that commits tracked files under `results/` can never mix into the
  artifact.
- The trusted job enforces an **exact filename allowlist** on the downloaded artifact and rejects any
  unexpected file. (`sha256sum -c` only checks files *listed* in the manifest, so an unlisted extra —
  e.g. a planted `results.sarif` — would otherwise slip past.) There is deliberately **no SARIF /
  code-scanning upload** here: the headless CLI emits no SARIF, so uploading an artifact-supplied one
  would poison base-repo code scanning with untrusted fork input.

Never trigger the validation on `pull_request_target` to check out fork code; that is the exact
foot-gun this split avoids.

### The base branch judges the PR

The gate that judges a PR is fixed by the **base branch**, not by the PR:

- Consumers pin the reusable workflow by ref (`uses: ...@<sha>`), and the gate flags inside it are
  **fixed** — `--no-network`, no external-path inputs, no credentials. A PR that flips
  `network.default` or `allow_external_paths` in its own policy cannot loosen how it is judged: the
  CLI still runs with `--no-network`, and the policy field change is merely reported.
- The **asserted-diff baseline is the base branch's ontology** (`github.event.pull_request.base.sha`),
  checked out separately, so the PR cannot rewrite what it is compared against.
- Protect the policy file with `CODEOWNERS` and require the trusted annotate check, so a PR cannot
  both weaken the policy and self-approve the change.

## PR defaults

Per [PLAN §10.4](https://github.com/hakjuoh/protege-mcp/blob/main/PLAN.md), the pull-request defaults
are deliberately locked down:

- `--no-network` — the CLI performs no network access; imports are never dereferenced (see
  [ADR 0005](adr/0005-import-network-policy-defaults.html)).
- **Fixed project root** — policy-relative paths resolve below the canonical project root.
- **No external paths** — the workflow passes no external-path inputs.
- **No provider credentials** — the untrusted job has a read-only token and no secrets.

The asserted diff is **report-only by default** (`diff` without `--check`, always exit 0): a PR is
expected to change the ontology, so the comment summarises the change rather than blocking it. Set
`diff_check: true` only for a reproducibility / no-drift gate where the two inputs are expected to be
**identical** (for example, "the committed artifact matches a regeneration").

## Pinning and the re-cut caveat

{: .warning }
> **`@vX.Y.Z` is not immutable in this repo.** Release tags in `hakjuoh/protege-mcp` are occasionally
> **re-cut** (moved to a new commit). The examples show `@v0.7.0` for readability, but for a
> production consumer you should **pin the reusable workflow to a full commit SHA** — for example
> `uses: hakjuoh/protege-mcp/.github/workflows/ontology-ci.yml@<40-hex-sha>` — and pin `cli_version`
> to a specific release you have reviewed. A SHA is the only reference GitHub treats as immutable.

Inside the reusable workflows, third-party and first-party actions are pinned to reviewed commit
**SHAs** (with the version named in a comment), rather than to the floating major tags this
repository's own `ci.yml` uses — a consumer-facing workflow warrants the stricter posture.

## Roadmap: richer annotations

The CLI's `validate` emits `json` or `markdown` only; `--format junit` and `--format sarif` are
deliberately rejected because a policy-only validation cannot honestly project a full QC gate result
with a semantic fingerprint. The annotation is therefore a PR comment summarising the validate JSON
and the diff verdict. There is **no SARIF / code-scanning upload today** — the pipeline never uploads
an artifact-supplied SARIF, because that would feed untrusted fork input straight into base-repo code
scanning. SARIF code-scanning (and richer JUnit annotations) will arrive only when a future **trusted
in-pipeline step** — a bundled-reasoner headless QC runner — produces a real `results.sarif` itself,
never from a downloaded artifact.

## See also

- [Headless CLI](cli.html) — the commands and exit codes this workflow invokes.
- [Project policy & QC](project-policy.html) — the policy the gate validates and the full in-Protégé
  QC gate.
- [Contributing](contributing.html) — this repository's own build CI (`mvn -B clean verify`).
