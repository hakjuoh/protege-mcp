---
title: Codex CLI
parent: Connecting a client
nav_order: 2
---

# Codex CLI (`codex`)
{: .no_toc }

[OpenAI Codex](https://github.com/openai/codex) connects over HTTP and supports OAuth login.

## OAuth (recommended)

```bash
codex mcp add protege-mcp --url http://127.0.0.1:8123/mcp
codex mcp login protege-mcp
```

`codex mcp login` opens the browser consent flow — approve it and you're connected.

## Static bearer token

Read the token from Protégé's **MCP Server** view, expose it via an environment variable, then add the
server referencing that variable:

```bash
export PROTEGE_MCP_TOKEN="<TOKEN>"
codex mcp add protege-mcp --url http://127.0.0.1:8123/mcp \
  --bearer-token-env-var PROTEGE_MCP_TOKEN
```

## Verify

```bash
codex mcp list           # shows protege-mcp
```

Ask Codex to *"list the classes in the active ontology via protege-mcp"* to confirm tool calls reach
Protégé.

## Troubleshooting

- **`401 Unauthorized`** — run `codex mcp login protege-mcp` (OAuth), or confirm `PROTEGE_MCP_TOKEN`
  matches the current token in Protégé.
- **Connection refused** — start the server from the **MCP Server** view and verify the port.
- **Edits rejected** — check for **read-only** mode or a pending **confirm-each-write** dialog in
  Protégé (Settings ▸ MCP).
