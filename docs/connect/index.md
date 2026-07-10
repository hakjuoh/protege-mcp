---
title: Connecting a client
nav_order: 3
has_children: true
permalink: /connect/
---

# Connecting a client
{: .no_toc }

How the MCP server works, how it is secured, and how to point each kind of client at it.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The server model

The plugin runs an **embedded HTTP MCP server** inside Protégé. It serves the
[Streamable HTTP transport](https://modelcontextprotocol.io/) at:

```
http://127.0.0.1:8123/mcp
```

- It binds to **loopback (`127.0.0.1`) only** — never a public interface.
- It **requires authorization on every request** (see [Authorization](#authorization)).
- It operates on the **active ontology** — whatever you have selected in Protégé — and its imports
  closure. Reads and edits flow through Protégé's shared `OWLModelManager`, so edits appear in the GUI
  and join the **Edit ▸ Undo** stack.

### Start and configure the server

**Settings ▸ MCP** controls the server:

| Setting | Default | Meaning |
| --- | --- | --- |
| **Port** | `8123` | Listen port. Set to `0` to bind an ephemeral (OS-assigned) port. |
| **Auto-start** | on | Start the server automatically when Protégé opens an ontology. |
| **Read-only mode** | off | Reject every mutating tool; only reads/queries succeed. |
| **Confirm each write** | off | Pop a confirmation dialog in Protégé before each edit is applied. |

The **MCP Server** view (Window ▸ Views ▸ Miscellaneous views) lets you **start/stop** the server, see
the **bound URL** and **bearer token**, **regenerate** the token, **revoke** OAuth clients, and copy a
ready-to-paste connect command.

{: .note }
> If you set the port to `0`, read the actual bound URL from the **MCP Server** view before configuring
> a client.

### One endpoint for every window and instance (shared broker)

By default the configured port belongs to a small **shared broker** process rather than to any single
Protégé window: the first Protégé instance starts it automatically, every window registers its own
(ephemeral-port) server behind it, and the broker exits by itself once the last Protégé instance
closes. For you this means `http://127.0.0.1:8123/mcp` **keeps working no matter how many Protégé
windows or instances are open** — no per-window URLs to chase.

- A **new MCP session** is routed to the window most recently connected to the broker (with
  auto-start on, effectively the newest window) and then stays pinned to that window for its
  lifetime.
- `GET /instances` (same auth) lists every window registered with the broker — all open windows
  when auto-start is on; connect a client to `/instances/{id}/mcp` to target a specific one.
- Auth is unchanged (bearer token or OAuth) and everything stays on `127.0.0.1`. The broker keeps its
  OAuth client registrations in `~/.protege-mcp/oauth.json`; the **MCP Server** view's
  Connected-clients table applies to standalone mode (a broker-mode client listing/revocation UI is a
  follow-up — to reset broker clients, delete `oauth.json` while no broker is running).
- The toggle lives in **Settings ▸ MCP** ("Share one MCP endpoint…"). With it off — or when the broker
  cannot be spawned — the plugin falls back to the standalone behavior below.

{: .note }
> **Standalone mode:** if the configured port is already in use when a window's server starts, the
> server binds an **ephemeral port instead of failing**. The **MCP Server** view shows the actual URL.
> The built-in **Ontology Assistant** always talks to its own window's server directly, in both modes.

## Authorization

Two auth modes are supported **in parallel** — pick whichever your client makes easiest.

### OAuth (recommended)

OAuth-capable clients (such as Claude Code) need no token to copy:

1. The client connects and receives a `401` with OAuth **discovery metadata**.
2. It **registers dynamically** ([RFC 7591](https://www.rfc-editor.org/rfc/rfc7591)) and opens a
   **browser consent page**.
3. You click **Allow** — and you're connected.

Registered clients and their tokens are **persisted to Protégé's preferences**, so a client that
authorized once keeps working across restarts. Access tokens **expire after 30 days**. Revoke a client
from the **MCP Server** view to force re-authorization. Endpoints are plain HTTP on loopback
([RFC 8252](https://www.rfc-editor.org/rfc/rfc8252) exempts loopback redirects from HTTPS).

### Static bearer token

Read the token from the **MCP Server** view and send it as an HTTP header on every request:

```
Authorization: Bearer <TOKEN>
```

The token is a URL-safe, 256-bit secret generated on first run. **Regenerate** it from the MCP Server
view at any time (this invalidates the old token).

{: .warning }
> Treat the bearer token like a password. Anyone who can reach `127.0.0.1:8123` **and** has the token
> (or an OAuth grant) can read and edit your ontology. Prefer OAuth where the client supports it.

## Pick your client

| Client | Transport | Guide |
| --- | --- | --- |
| **Claude Code** (`claude`) | HTTP (OAuth or token) | [Claude Code](claude-code.html) |
| **Codex CLI** (`codex`) | HTTP (OAuth or token) | [Codex CLI](codex-cli.html) |
| **VS Code** | HTTP (token or env var) | [VS Code](vs-code.html) |
| **Claude Desktop** | stdio → HTTP via `mcp-remote` | [Claude Desktop](claude-desktop.html) |

{: .tip }
> Prefer to stay inside Protégé entirely? The [**Ontology Assistant**](../ontology-assistant.html) tab
> drives your local `claude` / `codex` CLI back into this same server — no client configuration at all.
