#!/usr/bin/env bash
# Fail when release metadata no longer agrees with pom.xml's project version.
set -euo pipefail

cd "$(dirname "$0")/.."

version="$(python3 - <<'PY'
import xml.etree.ElementTree as ET

root = ET.parse("pom.xml").getroot()
print(root.findtext("{http://maven.apache.org/POM/4.0.0}version") or "")
PY
)"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "Could not read a release version from pom.xml (got '$version')." >&2
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

expect_line update.properties "version=${version}"
expect_line update.properties \
  "download=https://github.com/hakjuoh/protege-mcp/releases/download/v${version}/protege-mcp-${version}.jar"
expect_line src/main/java/io/github/hakjuoh/protege_mcp/server/McpServerManager.java \
  "    public static final String SERVER_VERSION = \"${version}\";"
expect_line docs/_config.yml "version: ${version}"

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

# These rendered docs must consume docs/_config.yml instead of duplicating a
# literal that is easy to miss on the next release.
if ! grep -qF 'v{{ site.version }}' docs/check-for-plugins.md; then
  echo "STALE: docs/check-for-plugins.md must render site.version." >&2
  failed=1
fi
if ! grep -qF 'protege-mcp-{{ site.version }}.jar' docs/smoke-test.md; then
  echo "STALE: docs/smoke-test.md must render site.version." >&2
  failed=1
fi

if (( failed != 0 )); then
  echo "Version consistency FAILED against pom.xml version ${version}." >&2
  exit 1
fi

echo "Version consistency OK: release metadata agrees on ${version}."
