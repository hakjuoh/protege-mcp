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

curl --fail --silent --show-error --location --head --retry 2 --max-time 30 "$download" >/dev/null
echo "Advertised release asset exists: $download"
