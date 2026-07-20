#!/usr/bin/env bash
# Check source-version mirrors and the independently published plugin descriptor.
set -euo pipefail

cd "$(dirname "$0")/.."

require_registry_current=0
if [[ "${1:-}" == "--require-registry-current" ]]; then
  require_registry_current=1
  shift
fi
if (( $# != 0 )); then
  echo "Usage: $0 [--require-registry-current]" >&2
  exit 2
fi

version="$(python3 - <<'PY'
import xml.etree.ElementTree as ET

root = ET.parse("pom.xml").getroot()
print(root.findtext("{http://maven.apache.org/POM/4.0.0}version") or "")
PY
)"

hermit_version="$(python3 - <<'PY'
import xml.etree.ElementTree as ET

ns = "{http://maven.apache.org/POM/4.0.0}"
root = ET.parse("pom.xml").getroot()
properties = root.find(ns + "properties")
print(properties.findtext(ns + "hermit.version") if properties is not None else "")
PY
)"

automatalib_version="$(python3 - <<'PY'
import xml.etree.ElementTree as ET

ns = "{http://maven.apache.org/POM/4.0.0}"
root = ET.parse("pom.xml").getroot()
properties = root.find(ns + "properties")
print(properties.findtext(ns + "automatalib.version") if properties is not None else "")
PY
)"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "Could not read a release version from pom.xml (got '$version')." >&2
  exit 1
fi
if [[ ! "$hermit_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "Could not read the HermiT version from pom.xml (got '$hermit_version')." >&2
  exit 1
fi
if [[ ! "$automatalib_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "Could not read the AutomataLib version from pom.xml (got '$automatalib_version')." >&2
  exit 1
fi

property() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; found++ } END { if (found != 1) exit 1 }' \
    update.properties
}

if ! registry_version="$(property version)" || ! registry_download="$(property download)"; then
  echo "STALE: update.properties must contain exactly one version and download property." >&2
  exit 1
fi
if [[ ! "$registry_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "STALE: update.properties version '$registry_version' must be a released major.minor.patch." >&2
  exit 1
fi
expected_download="https://github.com/hakjuoh/protege-mcp/releases/download/v${registry_version}/protege-mcp-${registry_version}.jar"
if [[ "$registry_download" != "$expected_download" ]]; then
  echo "STALE: update.properties download must match its advertised version ${registry_version}." >&2
  exit 1
fi

# A descriptor may intentionally lag source while a release candidate is being prepared, but it
# must never advertise a version newer than the source tree. The stricter mode is used only after
# the matching GitHub release asset exists and the descriptor is ready to be published on main.
if ! python3 - "$registry_version" "$version" <<'PY'
import re
import sys

def core(value):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:[.-].*)?", value)
    if not match:
        raise SystemExit(2)
    return tuple(map(int, match.groups()))

raise SystemExit(0 if core(sys.argv[1]) <= core(sys.argv[2]) else 1)
PY
then
  echo "STALE: update.properties advertises ${registry_version}, newer than source ${version}." >&2
  exit 1
fi
if (( require_registry_current != 0 )) && [[ "$registry_version" != "$version" ]]; then
  echo "STALE: published registry is ${registry_version}; expected released source ${version}." >&2
  exit 1
fi

failed=0
expect_line() {
  local file="$1"
  local line="$2"
  if ! grep -qxF "$line" "$file"; then
    echo "STALE: $file must contain: $line" >&2
    failed=1
  fi
}

expect_line plugin/src/main/java/io/github/hakjuoh/protege_mcp/server/McpServerManager.java \
  "    public static final String SERVER_VERSION = \"${version}\";"
# The standalone CLI hard-codes its own version (it has no Protégé/Maven filtering); keep it in the gate
# so a release cannot ship a stale `--version`.
expect_line cli/src/main/java/io/github/hakjuoh/protege_mcp/cli/Main.java \
  "    public static final String VERSION = \"${version}\";"
expect_line docs/_config.yml "version: ${version}"

for file in docs/adr/headless-reasoner-and-workspace-boundary.md \
            docs/headless-relinking.md THIRD_PARTY_NOTICES.md .github/workflows/release.yml; do
  if ! grep -qF "$hermit_version" "$file"; then
    echo "STALE: $file must name HermiT ${hermit_version}." >&2
    failed=1
  fi
done

for file in docs/adr/headless-reasoner-and-workspace-boundary.md \
            docs/headless-relinking.md THIRD_PARTY_NOTICES.md; do
  if ! grep -qF "$automatalib_version" "$file"; then
    echo "STALE: $file must name AutomataLib ${automatalib_version}." >&2
    failed=1
  fi
done

if ! grep -qF "SERVER_VERSION=${version}" DESIGN.md; then
  echo "STALE: DESIGN.md server version must be ${version}." >&2
  failed=1
fi
if ! grep -qF "version **\`${version}\`**" DESIGN.md; then
  echo "STALE: DESIGN.md bundle version must be ${version}." >&2
  failed=1
fi

first_readme_version="$(grep -m1 -oE '<h4>v[^<]+</h4>' docs/readme.html || true)"
if [[ "$first_readme_version" != "<h4>v${version}</h4>" ]]; then
  echo "STALE: docs/readme.html starts with '$first_readme_version'; expected '<h4>v${version}</h4>'." >&2
  failed=1
fi

for changelog in CHANGELOG.md docs/changelog.md; do
  first_release="$(grep -m1 -E '^## \[[0-9]+\.[0-9]+\.[0-9]+[^]]*\]' "$changelog" || true)"
  if [[ "$first_release" != "## [${version}]"* ]]; then
    echo "STALE: $changelog starts with '$first_release'; expected version ${version}." >&2
    failed=1
  fi
done

extract_current_changelog() {
  local file="$1"
  awk -v header="## [${version}]" '
    index($0, header) == 1 { printing = 1 }
    printing && index($0, "## [") == 1 && index($0, header) != 1 { exit }
    printing { print }
  ' "$file"
}
# The trailing "x" sentinel keeps command substitution from stripping trailing newlines, so even
# blank-line drift at the end of the section fails the byte comparison.
root_changelog="$(extract_current_changelog CHANGELOG.md; printf x)"
docs_changelog="$(extract_current_changelog docs/changelog.md; printf x)"
if [[ "$root_changelog" != "$docs_changelog" ]]; then
  echo "STALE: CHANGELOG.md and docs/changelog.md ${version} sections differ." >&2
  failed=1
fi

# This rendered doc must consume docs/_config.yml instead of duplicating a
# literal that is easy to miss on the next release.
if ! grep -qF 'protege-mcp-{{ site.version }}.jar' docs/smoke-test.md; then
  echo "STALE: docs/smoke-test.md must render site.version." >&2
  failed=1
fi

if (( failed != 0 )); then
  echo "Version consistency FAILED against pom.xml version ${version}." >&2
  exit 1
fi

if [[ "$registry_version" == "$version" ]]; then
  echo "Version consistency OK: source and advertised release agree on ${version}."
else
  echo "Version consistency OK: source is ${version}; registry safely remains on released ${registry_version}."
fi
