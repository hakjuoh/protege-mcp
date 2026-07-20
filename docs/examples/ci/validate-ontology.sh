#!/usr/bin/env bash
# Generic, CI-agnostic ontology gate: download the protege-mcp CLI, VERIFY it, then run the
# policy, full-QC, release-preview, and asserted-diff gates. Any CI (GitLab CI, Jenkins, Buildkite, a local
# pre-commit hook) can run this — it depends only on bash, curl, a SHA-256 tool (`sha256sum` or
# `shasum`), and a Java 17+ runtime.
#
# TRUST NOTE: on an untrusted PR, set TRUSTED_PROJECT to a base-reviewed policy/workspace instead of
# allowing the PR's policy and validation assets to judge themselves. The reusable GitHub workflow
# automates that overlay safely; this generic script assumes its caller prepares the trusted workspace.
#
# Requires protege-mcp-cli 0.7.1+ (complete headless QC/release execution).
# Tags in hakjuoh/protege-mcp are occasionally re-cut (moved); pin CLI_VERSION to a release you
# reviewed. Every command's stdout/stderr/exit code is preserved before the script returns non-zero
# for any failed gate, configuration error, or execution error.
set -euo pipefail

CLI_VERSION="${CLI_VERSION:-0.7.2}"
PROJECT="${PROJECT:-.protege-mcp/project.yaml}"
TRUSTED_PROJECT="${TRUSTED_PROJECT:-$PROJECT}"
ONTOLOGY="${ONTOLOGY:-}"      # candidate ontology (optional; enables the diff when BASELINE is set too)
BASELINE="${BASELINE:-}"      # baseline ontology to diff against (e.g. the base branch's copy)
DIFF_CHECK="${DIFF_CHECK:-0}" # set to 1 to make the diff an identity gate (fails on ANY difference)
RELEASE_CHECK="${RELEASE_CHECK:-1}"
RESULTS="${RESULTS:-ontology-ci-results}"

case "$RESULTS" in
  ''|.|..|/)
    echo "RESULTS must name a dedicated output directory." >&2
    exit 2
    ;;
esac
case "$DIFF_CHECK:$RELEASE_CHECK" in
  0:0|0:1|1:0|1:1) ;;
  *) echo "DIFF_CHECK and RELEASE_CHECK must each be 0 or 1." >&2; exit 2 ;;
esac
if { [ -n "$ONTOLOGY" ] && [ -z "$BASELINE" ]; } \
    || { [ -z "$ONTOLOGY" ] && [ -n "$BASELINE" ]; }; then
  echo "ONTOLOGY and BASELINE must be supplied together." >&2
  exit 2
fi

base="https://github.com/hakjuoh/protege-mcp/releases/download/v${CLI_VERSION}"
jar="protege-mcp-cli-${CLI_VERSION}-all.jar"

# 1. Download the published CLI asset and its bare-basename .sha256 sidecar.
#    --retry-all-errors rides the brief 404 window a release re-cut opens.
curl --fail --location --retry 3 --retry-all-errors --max-time 180 -o "$jar" "${base}/${jar}"
curl --fail --location --retry 3 --retry-all-errors --max-time 60 -o "${jar}.sha256" "${base}/${jar}.sha256"

# 2. VERIFY before running. Prefer GNU sha256sum; macOS supplies shasum. Probe the selected command
#    because recent macOS also exposes an incompatible sha256sum spelling through cksum.
HASH_FILE=()
HASH_CHECK=()
if command -v sha256sum >/dev/null 2>&1; then
  probe="$(sha256sum "$0" 2>/dev/null | awk '{print $1}' || true)"
  if [[ "$probe" =~ ^[0-9a-fA-F]{64}$ ]]; then
    HASH_FILE=(sha256sum)
    HASH_CHECK=(sha256sum -c)
  fi
fi
if [ "${#HASH_FILE[@]}" -eq 0 ] && command -v shasum >/dev/null 2>&1; then
  HASH_FILE=(shasum -a 256)
  HASH_CHECK=(shasum -a 256 -c)
fi
if [ "${#HASH_FILE[@]}" -eq 0 ]; then
  echo "A compatible sha256sum or shasum command is required." >&2
  exit 2
fi
"${HASH_CHECK[@]}" "${jar}.sha256"

# 3. Use a fresh staging directory, so a disabled release/diff cannot preserve a stale result from a
#    previous run. Publish the complete evidence directory only after all commands have run.
RESULTS_TMP="$(mktemp -d "${TMPDIR:-/tmp}/protege-mcp-ci.XXXXXX")"
trap 'rm -rf -- "$RESULTS_TMP"' EXIT

run_cli() {
  output="$1"
  error="$2"
  exit_file="$3"
  shift 3
  set +e
  java -jar "$jar" "$@" > "$RESULTS_TMP/$output" 2> "$RESULTS_TMP/$error"
  code=$?
  set -e
  printf '%s' "$code" > "$RESULTS_TMP/$exit_file"
}

# 4. Validate the candidate policy as a proposal, then run the trusted full-QC gate in every machine format.
run_cli candidate-policy.json candidate-policy.err candidate-policy.exit \
  validate-policy --project "$PROJECT" --no-network --no-external
run_cli qc.json qc.json.err qc.json.exit \
  validate --project "$TRUSTED_PROJECT" --format json --no-network --no-external
run_cli qc.junit.xml qc.junit.xml.err qc.junit.xml.exit \
  validate --project "$TRUSTED_PROJECT" --format junit --no-network --no-external
run_cli qc.sarif qc.sarif.err qc.sarif.exit \
  validate --project "$TRUSTED_PROJECT" --format sarif --no-network --no-external

# 5. A release preview computes checksums without publishing anything.
if [ "$RELEASE_CHECK" = "1" ]; then
  run_cli release.json release.err release.exit \
    release --project "$TRUSTED_PROJECT" --dry-run --no-network --no-external
fi

# 6. Asserted-diff gate/report (only when both a baseline and a candidate are given).
if [ -n "$ONTOLOGY" ] && [ -n "$BASELINE" ]; then
  args=(diff --left "$BASELINE" --right "$ONTOLOGY" --no-network)
  [ "$DIFF_CHECK" = "1" ] && args+=(--check)
  run_cli diff.json diff.err diff.exit "${args[@]}"
fi

# 7. Portable, bare-basename integrity manifest (no GNU find/sort extensions required).
manifest_tmp="$(mktemp "${TMPDIR:-/tmp}/protege-mcp-manifest.XXXXXX")"
for path in "$RESULTS_TMP"/*; do
  [ -f "$path" ] || continue
  name="$(basename "$path")"
  digest="$("${HASH_FILE[@]}" "$path" | awk '{print $1}')"
  printf '%s  %s\n' "$digest" "$name" >> "$manifest_tmp"
done
LC_ALL=C sort "$manifest_tmp" > "$RESULTS_TMP/manifest.sha256"
rm -f -- "$manifest_tmp"

mkdir -p -- "$(dirname -- "$RESULTS")"
rm -rf -- "$RESULTS"
mv -- "$RESULTS_TMP" "$RESULTS"
trap - EXIT

# 8. Enforce only after preserving the complete evidence set.
fail=0
for exit_file in candidate-policy.exit qc.json.exit qc.junit.xml.exit qc.sarif.exit; do
  [ "$(cat "$RESULTS/$exit_file")" = "0" ] || fail=1
done
if [ "$RELEASE_CHECK" = "1" ] && [ "$(cat "$RESULTS/release.exit")" != "0" ]; then
  fail=1
fi
if [ -f "$RESULTS/diff.exit" ] && [ "$(cat "$RESULTS/diff.exit")" != "0" ]; then
  fail=1
fi
exit "$fail"
