#!/usr/bin/env bash
# The public update descriptor must never point Protégé clients at a missing release asset.
set -euo pipefail

cd "$(dirname "$0")/.."

download="$(awk -F= '$1 == "download" { sub(/^[^=]*=/, ""); print; found++ } END { if (found != 1) exit 1 }' \
  update.properties)" || {
  echo "update.properties must contain exactly one download property." >&2
  exit 1
}

case "$download" in
  https://github.com/hakjuoh/protege-mcp/releases/download/*) ;;
  *)
    echo "Refusing to probe unexpected advertised release URL: $download" >&2
    exit 1
    ;;
esac

# --retry-all-errors covers connection resets and DNS blips that --retry alone skips. The outer
# loop additionally rides out the brief 404 window release.yml opens while a re-cut deletes and
# recreates the GitHub release; a genuinely missing asset still fails after ~40s of retrying.
attempt=1
max_attempts=3
until curl --fail --silent --show-error --location --head \
    --retry 2 --retry-all-errors --max-time 30 "$download" >/dev/null; do
  if (( attempt >= max_attempts )); then
    echo "Advertised release asset is unreachable after ${max_attempts} attempts: $download" >&2
    exit 1
  fi
  echo "Advertised-asset probe failed (attempt ${attempt}/${max_attempts}); retrying in 20s..." >&2
  attempt=$((attempt + 1))
  sleep 20
done
echo "Advertised release asset exists: $download"
