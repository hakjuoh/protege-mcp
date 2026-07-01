---
title: Claude Desktop
parent: Connecting a client
nav_order: 4
---

# Claude Desktop
{: .no_toc }

Claude Desktop speaks **stdio only**, so it connects to this HTTP server through the
[`mcp-remote`](https://www.npmjs.com/package/mcp-remote) bridge, which needs **Node / `npx`**.

Open **Settings ▸ Developer ▸ Edit Config**, edit `claude_desktop_config.json`, save, and **restart**
Claude Desktop.

## With a bearer token pasted directly

Read the token from Protégé's **MCP Server** view:

```json
{
  "mcpServers": {
    "protege": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:8123/mcp",
        "--header",
        "Authorization:Bearer <TOKEN>"
      ]
    }
  }
}
```

## With a token from the environment

```json
{
  "mcpServers": {
    "protege": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:8123/mcp",
        "--header",
        "Authorization:Bearer ${PROTEGE_MCP_TOKEN}"
      ],
      "env": {
        "PROTEGE_MCP_TOKEN": "<TOKEN>"
      }
    }
  }
}
```

{: .note }
> `mcp-remote` also supports OAuth against the server; the bearer-token setup above is the simplest
> reliable path for a desktop app.

## Troubleshooting

- **`npx` not found** — install Node.js so `npx` is on `PATH`, then restart Claude Desktop.
- **Server not appearing** — check for JSON syntax errors in `claude_desktop_config.json`, then fully
  restart Claude Desktop.
- **`401` / connection refused** — the token is wrong/stale, or the Protégé server is not running.
