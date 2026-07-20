#!/usr/bin/env bash
set -euo pipefail

PROTEGE_VERSION=5.6.6
PROTEGE_ARCHIVE_NAME="Protege-${PROTEGE_VERSION}-platform-independent.zip"
PROTEGE_ARCHIVE_URL="https://github.com/protegeproject/protege-distribution/releases/download/protege-${PROTEGE_VERSION}/${PROTEGE_ARCHIVE_NAME}"
PROTEGE_ARCHIVE_SHA256="7f1b1b68da8af10bcf6da80d299c7c453a513399c8ce31f25e9c4b2c9c19de6d"
TOKEN="protege-mcp-live-harness-static-token"

if [ "$(uname -s)" != "Linux" ]; then
  echo "The live integration harness is Linux-only: non-Linux java.util.prefs backends may ignore the isolated user.home and overwrite real Protégé settings." >&2
  exit 1
fi

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
OUTPUT=${LIVE_INTEGRATION_OUTPUT:-"$ROOT/plugin/target/live-integration"}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/protege-mcp-live.XXXXXX")
HOME_DIR="$WORK/home"
LOG="$WORK/protege.log"
REPORT="$WORK/scenario.json"
APP_PID=""
BROKER_PID=""

mkdir -p "$HOME_DIR" "$OUTPUT"
rm -f "$OUTPUT/protege.log" "$OUTPUT/broker.log" "$OUTPUT/scenario.json"

copy_evidence() {
  if [ -f "$LOG" ]; then
    cp "$LOG" "$OUTPUT/protege.log"
  fi
  if [ -f "$HOME_DIR/.protege-mcp/broker.log" ]; then
    cp "$HOME_DIR/.protege-mcp/broker.log" "$OUTPUT/broker.log"
  fi
  if [ -f "$REPORT" ]; then
    cp "$REPORT" "$OUTPUT/scenario.json"
  fi
}

terminate_pid() {
  local pid=$1
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
    return
  fi
  kill "$pid" 2>/dev/null || true
  local deadline=$((SECONDS + 5))
  while kill -0 "$pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
    sleep 0.25
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  copy_evidence
  terminate_pid "$APP_PID"
  terminate_pid "$BROKER_PID"
  rm -rf "$WORK"
}
trap cleanup EXIT

if [ -n "${PROTEGE_JAVA_HOME:-}" ]; then
  JAVA_HOME_EFFECTIVE="$PROTEGE_JAVA_HOME"
elif [ -n "${JAVA_HOME:-}" ]; then
  JAVA_HOME_EFFECTIVE="$JAVA_HOME"
else
  JAVA_BIN=$(command -v java)
  JAVA_HOME_EFFECTIVE=$(cd "$(dirname "$JAVA_BIN")/.." && pwd)
fi
JAVA="$JAVA_HOME_EFFECTIVE/bin/java"
test -x "$JAVA"

"$JAVA" -Duser.home="$HOME_DIR" "$ROOT/scripts/live-integration/SeedPreferences.java" "$TOKEN"
python3 "$ROOT/scripts/live-integration/test_mcp_client.py"

PLUGIN_JAR=${PLUGIN_JAR:-}
if [ -z "$PLUGIN_JAR" ]; then
  PROJECT_VERSION=$(python3 - "$ROOT/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
print(root.findtext("{http://maven.apache.org/POM/4.0.0}version"))
PY
)
  PLUGIN_JAR="$ROOT/plugin/target/protege-mcp-${PROJECT_VERSION}.jar"
fi
test -s "$PLUGIN_JAR"

ARCHIVE=${PROTEGE_ARCHIVE:-"$WORK/$PROTEGE_ARCHIVE_NAME"}
if [ ! -f "$ARCHIVE" ]; then
  curl --fail --location --retry 3 --output "$ARCHIVE" "$PROTEGE_ARCHIVE_URL"
fi
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_ARCHIVE_SHA256=$(sha256sum "$ARCHIVE" | awk '{print $1}')
else
  ACTUAL_ARCHIVE_SHA256=$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')
fi
if [ "$ACTUAL_ARCHIVE_SHA256" != "$PROTEGE_ARCHIVE_SHA256" ]; then
  echo "Protégé archive checksum mismatch: expected $PROTEGE_ARCHIVE_SHA256, got $ACTUAL_ARCHIVE_SHA256" >&2
  exit 1
fi
unzip -q "$ARCHIVE" -d "$WORK/distribution"
PROTEGE_DIR="$WORK/distribution/Protege-${PROTEGE_VERSION}"
test -x "$PROTEGE_DIR/run.sh"
cp "$PLUGIN_JAR" "$PROTEGE_DIR/plugins/"
cp "$ROOT/scripts/live-integration/fixtures/window-a.ofn" "$WORK/window-a.ofn"
cp "$ROOT/scripts/live-integration/fixtures/window-b.ofn" "$WORK/window-b.ofn"

export HOME="$HOME_DIR"
PROTEGE_CLASSPATH="bundles/guava.jar:bundles/logback-classic.jar:bundles/logback-core.jar:bundles/slf4j-api.jar:bundles/glassfish-corba-orb.jar:bundles/org.apache.felix.main.jar:bundles/maven-artifact.jar:bundles/protege-launcher.jar"
(
  cd "$PROTEGE_DIR"
  exec "$JAVA" \
    -DentityExpansionLimit=100000000 \
    -Dlogback.configurationFile=conf/logback.xml \
    -Dfile.encoding=UTF-8 \
    -Duser.home="$HOME_DIR" \
    -Dcommand.line.arg.0="$WORK/window-a.ofn" \
    -Dcommand.line.arg.1="$WORK/window-b.ofn" \
    -XX:CompileCommand=exclude,javax/swing/text/GlyphView,getBreakSpot \
    --add-opens=java.desktop/sun.swing=ALL-UNNAMED \
    -classpath "$PROTEGE_CLASSPATH" \
    org.protege.osgi.framework.Launcher
) >"$LOG" 2>&1 &
APP_PID=$!

# Protégé logs "Plugin: <name> (<version>)" for an ACTIVE bundle and
# "Plugin: <name> (<version>) was not successfully started. ..." for a failed one; a bare
# substring match would treat the failure line as success and misattribute the error to the
# broker-state wait below.
bundle_active() {
  grep -F "Plugin: Protege MCP Server" "$LOG" | grep -Fvq "was not successfully started"
}
bundle_failed() {
  grep -F "Plugin: Protege MCP Server" "$LOG" | grep -Fq "was not successfully started"
}
deadline=$((SECONDS + 90))
while ! bundle_active; do
  if bundle_failed; then
    echo "The MCP bundle was installed but did not start successfully." >&2
    grep -E "failed to start|not successfully started|osgi.ee|Protege MCP" "$LOG" >&2 || true
    exit 1
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "Protégé exited before the MCP bundle became ACTIVE." >&2
    tail -200 "$LOG" >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "The MCP bundle did not reach ACTIVE state before the deadline." >&2
    grep -E "failed to start|not successfully started|osgi.ee|Protege MCP" "$LOG" >&2 || true
    exit 1
  fi
  sleep 0.5
done

STATE="$HOME_DIR/.protege-mcp/broker.json"
deadline=$((SECONDS + 90))
while [ ! -s "$STATE" ]; do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "Protégé exited before the shared broker became live." >&2
    tail -200 "$LOG" >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "The shared broker did not publish its state before the deadline." >&2
    tail -200 "$LOG" >&2
    exit 1
  fi
  sleep 0.5
done

read -r BROKER_PID BASE_URL < <(python3 - "$STATE" <<'PY'
import json
import sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
print(state["pid"], "http://%s:%s" % (state.get("host", "127.0.0.1"), state["port"]))
PY
)

python3 "$ROOT/scripts/live-integration/mcp_client.py" \
  --base-url "$BASE_URL" --token "$TOKEN" --report "$REPORT"

kill "$APP_PID"
deadline=$((SECONDS + 30))
while kill -0 "$APP_PID" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
  sleep 0.5
done
if kill -0 "$APP_PID" 2>/dev/null; then
  echo "Protégé did not shut down within 30 seconds." >&2
  exit 1
fi
wait "$APP_PID" || true
APP_PID=""

deadline=$((SECONDS + 30))
while kill -0 "$BROKER_PID" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
  sleep 0.5
done
if kill -0 "$BROKER_PID" 2>/dev/null; then
  echo "The shared broker remained alive after the final Protégé window closed." >&2
  exit 1
fi
BROKER_PID=""
python3 - "$REPORT" "$PROTEGE_VERSION" "$JAVA" <<'PY'
import json
import subprocess
import sys

path, protege_version, java = sys.argv[1:]
report = json.load(open(path, encoding="utf-8"))
version_line = subprocess.run(
    [java, "-version"], capture_output=True, check=True, text=True
).stderr.splitlines()[0]
report["runtime_assertions"] = {
    "bundle_active_log_observed": True,
    "java": version_line,
    "protege_version": protege_version,
    "application_pid_exit_observed": True,
    "broker_pid_exit_observed": True,
    "source": "harness checks",
}
with open(path, "w", encoding="utf-8") as stream:
    json.dump(report, stream, indent=2, sort_keys=True)
    stream.write("\n")
PY
copy_evidence
echo "Live Protégé integration passed; evidence: $OUTPUT"
