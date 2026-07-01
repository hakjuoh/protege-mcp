---
title: Claude Code
parent: Connecting a client
nav_order: 1
---

# Claude Code (`claude`)
{: .no_toc }

[Claude Code](https://docs.claude.com/en/docs/claude-code) connects over HTTP and supports OAuth, so
you can connect with nothing to copy.

## OAuth (recommended)

```bash
claude mcp add --transport http protege-mcp http://127.0.0.1:8123/mcp
```

The first time a tool from `protege-mcp` is used, Claude Code opens a browser consent page — click
**Allow** in Protégé's flow and you're connected.

## Static bearer token

Read the token from Protégé's **MCP Server** view, then:

```bash
claude mcp add --transport http protege-mcp http://127.0.0.1:8123/mcp \
  --header "Authorization: Bearer <TOKEN>"
```

## Verify

```bash
claude mcp list          # shows protege-mcp and its status
```

Then, in a Claude Code session, ask something like *"Using protege-mcp, what classes are in the active
ontology?"* — it should call `list_classes` / `get_ontology_context`.

## Troubleshooting

- **`401 Unauthorized`** — the server needs auth. Complete the OAuth consent, or check the bearer token
  is correct and current (regenerating the token in Protégé invalidates the old one).
- **Connection refused** — the MCP server is not running, or the port differs. Start it from the **MCP
  Server** view and confirm the URL/port there.
- **Edits rejected** — Protégé is in **read-only** mode (Settings ▸ MCP), or a **confirm-each-write**
  dialog is waiting in Protégé.
