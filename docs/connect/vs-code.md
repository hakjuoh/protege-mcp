---
title: VS Code
parent: Connecting a client
nav_order: 3
---

# VS Code
{: .no_toc }

VS Code's built-in MCP support connects over HTTP. Open the configuration from the Command Palette:

- **MCP: Open Workspace Folder MCP Configuration** → edits `.vscode/mcp.json` (per-project), or
- **MCP: Open User Configuration** → edits your user-level `mcp.json`, or
- **MCP: Add Server** for a guided flow.

## With a bearer token pasted directly

Read the token from Protégé's **MCP Server** view:

```json
{
  "servers": {
    "protege": {
      "type": "http",
      "url": "http://127.0.0.1:8123/mcp",
      "headers": {
        "Authorization": "Bearer <TOKEN>"
      }
    }
  }
}
```

## With a token from the environment

Launch VS Code from an environment where `PROTEGE_MCP_TOKEN` is set, then reference it:

```json
{
  "servers": {
    "protege": {
      "type": "http",
      "url": "http://127.0.0.1:8123/mcp",
      "headers": {
        "Authorization": "Bearer ${env:PROTEGE_MCP_TOKEN}"
      }
    }
  }
}
```

## Troubleshooting

- **Server shows as errored** — verify the server is running (MCP Server view) and the token is
  current.
- **`${env:PROTEGE_MCP_TOKEN}` is empty** — VS Code only sees environment variables from the process
  that launched it; set the variable, then start VS Code from that shell (or set it system-wide).
