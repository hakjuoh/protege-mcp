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

- It binds to **loopback (`127.0.0.1`) by default**; the **Bind address** preference can expose
  another interface (see the table below), and the broker's per-window backends stay on loopback
  regardless.
- It **requires authorization on every request** (see [Authorization](#authorization)).
- It operates on the **active ontology** — whatever you have selected in Protégé — and its imports
  closure. Reads and edits flow through Protégé's shared `OWLModelManager`, so edits appear in the GUI
  and join the **Edit ▸ Undo** stack.

### Start and configure the server

**Settings ▸ MCP** controls the server:

| Setting | Default | Meaning |
| --- | --- | --- |
| **Port** | `8123` | Listen port. Set to `0` to bind an ephemeral (OS-assigned) port. |
| **Bind address** | `127.0.0.1` | Interface the endpoint binds — presets `::1` and `0.0.0.0`, any interface address accepted. Anything but loopback is plain unencrypted HTTP on your network (Preferences shows a red warning), and OAuth authorization stays **same-machine only** regardless. |
| **Share one MCP endpoint…** | on | Keep the configured port on a shared **broker** so one URL serves every window and instance — see [below](#one-endpoint-for-every-window-and-instance-shared-broker). |
| **Broker idle linger (seconds)** | `15` | How long the broker outlives the last Protégé instance (`0`–`3600`), so a quick restart reuses the live broker and its port instead of respawning one. |
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
closes (after the configurable **idle linger** — 15 seconds by default). For you this means
`http://127.0.0.1:8123/mcp` **keeps working no matter how many Protégé windows or instances are
open** — no per-window URLs to chase.

- A **new MCP session** is routed to the window most recently connected to the broker (with
  auto-start on, effectively the newest window) and then stays pinned to that window for its
  lifetime.
- `GET /instances` (same auth) lists every window registered with the broker — all open windows
  when auto-start is on; connect a client to `/instances/{id}/mcp` to target a specific one.
- Auth is unchanged (bearer token or OAuth) and the endpoint stays on `127.0.0.1` unless the
  **Bind address** preference says otherwise. The broker keeps its OAuth client registrations in
  `~/.protege-mcp/oauth.json`. Same-OS-user internal management APIs — `GET /internal/clients`,
  `POST /internal/revoke-client` with `client_id`, and `POST /internal/terminate-session` with
  `session_id` — back the **MCP Server** view's Connected-clients table when the broker owns the
  endpoint. They require the private directory-secret header, are not MCP client endpoints, and never
  return tokens. Revocation invalidates tokens, drops the client's pinned sessions, terminates its
  in-flight and queued proxied requests at the broker (the response reports
  `in_flight_termination=true` and `terminated_in_flight_requests` when any were cut), and confirms
  a commit fence across every registered window so no revoked work can commit once the fence is
  confirmed.
- The broker resolves every accepted bearer token to a versioned authenticated principal and forwards that
  principal only alongside the unguessable per-window broker secret. Client-supplied principal headers are
  stripped. Backend MCP transport context receives the verified identity/capabilities, and session pins reject
  replay from a different OAuth client or grant.
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

The consent page shows the requested OAuth scope. The compatibility scope `mcp` (and a scope omitted by
older clients) keeps the full local-admin profile. A client that explicitly requests `read` receives only
`ontology:read`: it can call ordinary ontology/query tools but cannot curate, administer, release, or read
caller-selected project files. Advanced clients may request one or more exact capability scopes:

| Scope | Authority |
| --- | --- |
| `ontology:read` | Read/query the live ontology. Alias: `read`. |
| `ontology:curate` | Apply or manage curation/change-set operations. |
| `ontology:admin` | Change ontology identity/imports/workspace/reasoner administration. |
| `ontology:release` | Run release gates and preparation. |
| `filesystem:project:read` / `filesystem:project:write` | Read/write project-confined files; required in addition to the tool's ontology capability. |
| `filesystem:external` | Permit an outside-project path only when project policy also opts in. |
| `network:access` | Permit a network-capable request only when request and project policy also allow it. |
| `server:admin` | Server administration operations. |

Capabilities compose literally: requesting `ontology:read filesystem:project:read` grants both; one does
not silently imply another. Every one of the 84 tools declares its required capabilities centrally and is
checked before its handler runs, identically through the shared broker and a standalone window. The global
Protégé read-only/confirm-write settings and project path/network policy remain additional hard ceilings.
Unknown scopes are rejected before consent and token issuance.

This parity requires the current broker protocol. During a mixed-version restart, a broker older than
`0.6.0` cannot forward scoped principal metadata, so its already-authenticated requests temporarily use the
legacy local-admin compatibility profile. Restart or upgrade that broker before relying on a restricted scope.

Registered clients and their tokens are **persisted** — to Protégé's preferences in standalone mode,
to `~/.protege-mcp/oauth.json` when the broker owns the endpoint — so a client that authorized once
keeps working across restarts. Access tokens **expire after 30 days**, and the client list **cleans
itself up**: when a client that re-registered under the same name completes authorization, its old
registrations are dropped; a registration that never finishes authorizing disappears after an hour;
and a client silent for 60 days is removed, tokens and all. Revoke a client from the **MCP Server**
view — in standalone mode and, since 0.7.1, when the shared broker owns the endpoint — to force
re-authorization at any time; broker-mode revocation also drops the client's pinned sessions, cuts
its in-flight proxied requests, and fences every window against further revoked work. The
**browser consent step works from
this machine only** — a remote peer gets a `403` pointing at the static bearer token — whatever the
bind address. Endpoints are plain HTTP on loopback
([RFC 8252](https://www.rfc-editor.org/rfc/rfc8252) exempts loopback redirects from HTTPS).

### Static bearer token

Read the token from the **MCP Server** view and send it as an HTTP header on every request:

```
Authorization: Bearer <TOKEN>
```

The token is a URL-safe, 256-bit secret generated on first run. **Regenerate** it from the MCP Server
view at any time (this invalidates the old token).

{: .warning }
> Treat the bearer token like a password. The static token and compatibility `mcp` OAuth scope carry the
> full local-admin profile. A deliberately read-scoped OAuth grant is least-privilege, but is still
> sensitive. A non-loopback **Bind address** makes the endpoint reachable from your network. Prefer OAuth
> where the client supports it.

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
