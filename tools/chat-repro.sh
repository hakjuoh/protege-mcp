#!/bin/bash
# Reproduce the protege-mcp "Ontology Assistant" panel's spawn from a terminal — for fast iteration without
# restarting Protégé. It drives `claude` headlessly through a LOGIN SHELL (so it inherits your
# profile auth, exactly like the fixed ChatView does) and attaches Protégé's running MCP server, so
# the assistant reads/edits your LIVE ontology (changes appear in Protégé and can be undone).
#
# Usage:
#   tools/chat-repro.sh <bearer-token> [prompt] [url]
#
#   <bearer-token>  the token shown in Protégé's "MCP Server" view (Token / manual fallback)
#   [prompt]        defaults to a read-only question
#   [url]           defaults to http://127.0.0.1:8123/mcp
#
# Example:
#   tools/chat-repro.sh "$(pbpaste)" "Create a class FooBar under Thing with label 'Foo Bar'"
set -euo pipefail

TOKEN="${1:?Usage: $0 <bearer-token> [prompt] [url]   (token = MCP Server view bearer token)}"
PROMPT="${2:-What classes are in this ontology?}"
URL="${3:-http://127.0.0.1:8123/mcp}"

MCP_JSON="{\"mcpServers\":{\"protege\":{\"type\":\"http\",\"url\":\"${URL}\",\"headers\":{\"Authorization\":\"Bearer ${TOKEN}\"}}}}"

# `$SHELL -lc 'exec …'` mirrors CliSupport.loginShellWrap; positional args avoid quoting pitfalls.
exec "${SHELL:-/bin/bash}" -lc '
  exec claude -p --output-format stream-json --include-partial-messages --verbose \
    --strict-mcp-config --mcp-config "$1" --allowedTools mcp__protege -- "$2"
' _ "$MCP_JSON" "$PROMPT"
