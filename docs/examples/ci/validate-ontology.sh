#!/usr/bin/env bash
# Generic, CI-agnostic ontology gate: download the protege-mcp CLI, VERIFY it, then run the
# policy-validation and asserted-diff gates. Any CI (GitLab CI, Jenkins, Buildkite, a local
# pre-commit hook) can run this — it depends only on bash, curl, sha256sum, and a Java 17+ runtime.
#
# HONEST SCOPE: this validates the project policy and an asserted semantic diff only. It does NOT run
# the reasoner/profile/governance/invariants/CQ/SHACL project-QC stages — the headless CLI bundles no
# reasoner. Run those from the in-Protégé run_project_qc tool (or ROBOT for OBO projects).
#
# Requires protege-mcp-cli 0.7.0+ (first release with `validate --no-network` and `diff --check`).
# Tags in hakjuoh/protege-mcp are occasionally re-cut (moved); pin CLI_VERSION to a release you
# reviewed. Exit codes propagate from the CLI: 0 passed, 1 gate failed, 2 config/usage, 3 exec error.
set -euo pipefail

CLI_VERSION="${CLI_VERSION:-0.7.0}"
PROJECT="${PROJECT:-.protege-mcp/project.yaml}"
ONTOLOGY="${ONTOLOGY:-}"      # candidate ontology (optional; enables the diff when BASELINE is set too)
BASELINE="${BASELINE:-}"      # baseline ontology to diff against (e.g. the base branch's copy)
DIFF_CHECK="${DIFF_CHECK:-0}" # set to 1 to make the diff an identity gate (fails on ANY difference)

base="https://github.com/hakjuoh/protege-mcp/releases/download/v${CLI_VERSION}"
jar="protege-mcp-cli-${CLI_VERSION}-all.jar"

# 1. Download the published CLI asset and its bare-basename .sha256 sidecar.
#    --retry-all-errors rides the brief 404 window a release re-cut opens.
curl --fail --location --retry 3 --retry-all-errors --max-time 180 -o "$jar" "${base}/${jar}"
curl --fail --location --retry 3 --retry-all-errors --max-time 60 -o "${jar}.sha256" "${base}/${jar}.sha256"

# 2. VERIFY before running: the sidecar records the bare basename, so this checks the local jar.
sha256sum -c "${jar}.sha256"

# 3. Policy gate. --no-network is fixed regardless of policy contents.
java -jar "$jar" validate --project "$PROJECT" --format json --no-network

# 4. Asserted-diff gate/report (only when both a baseline and a candidate are given).
if [ -n "$ONTOLOGY" ] && [ -n "$BASELINE" ]; then
  args=(diff --left "$BASELINE" --right "$ONTOLOGY" --no-network)
  [ "$DIFF_CHECK" = "1" ] && args+=(--check)
  java -jar "$jar" "${args[@]}"
fi
