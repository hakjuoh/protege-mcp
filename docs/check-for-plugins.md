---
title: Install via Check for plugins
parent: Installation
nav_order: 1
---

# Distributing via Protégé's "Check for plugins"

Protégé can discover and install this plugin from **File ▸ Check for plugins** — no manual jar
copying. That menu reads a **plugin registry** (a `.repository` URL list) and, for each listed
**update descriptor** (`update.properties`), offers the plugin when its `id`
(= `Bundle-SymbolicName`, here `io.github.hakjuoh.protege-mcp`) is either not yet installed, or installed at a
lower `version`. Both files are **published from this GitHub repo**, so you can install straight from
the official registry with nothing to fill in:

- [`protege-mcp.repository`](../protege-mcp.repository) — the registry list, served from the `main`
  branch at
  `https://raw.githubusercontent.com/hakjuoh/protege-mcp/main/protege-mcp.repository`. Because
  Protégé's "Plugin registry" is a **single** URL, this file lists our descriptor **and nests
  Protégé's default registry**, so pointing Protégé here shows protege-mcp **alongside** the full
  official catalog (Cellfie, ELK, OntoGraf, OWLViz, …) — you don't lose the standard plugins.
- [`update.properties`](../update.properties) — the update descriptor it points at
  (`id` / `name` / `version` / `download` / `readme` / `author`), served from
  `https://raw.githubusercontent.com/hakjuoh/protege-mcp/main/update.properties`. Its `download` URL
  is the jar attached to the matching [GitHub Release](https://github.com/hakjuoh/protege-mcp/releases/latest)
  (currently `v{{ site.version }}`).

> This is **Distribution Path B** (how the jar is delivered), the counterpart to the manual-copy
> [install](../README.md#install) (Distribution Path A). It is unrelated to **Architecture Approach B**
> (the in-app Claude chat) in [`DESIGN.md`](../DESIGN.md) — that is the plugin's internal shape, not its
> delivery. The same `Bundle-SymbolicName` / version rules used here are described in
> [`DESIGN.md` §7 (Packaging)](../DESIGN.md#7-packaging-and-osgi-strategy).

## Install from the official registry (no setup)

1. **Point Protégé at the registry.** **Settings ▸ Plugins ▸ "Plugin registry"** → paste

   ```
   https://raw.githubusercontent.com/hakjuoh/protege-mcp/main/protege-mcp.repository
   ```

   (The field defaults to the official Protégé registry; "Reset to default registry location"
   restores it.)
2. **Discover it.** **File ▸ Check for plugins** → **"Protege MCP Server"** appears in the list
   (together with the official plugins, since this registry nests Protégé's default one).
3. **Install it.** Select it and confirm. Protégé downloads the jar named in `update.properties`
   (`download=…/releases/download/v<version>/protege-mcp-<version>.jar`, currently `v{{ site.version }}`) straight
   from the GitHub Release and drops it into your plugins directory.
4. **Restart Protégé** on a **Java 17+** JVM (see [Requirements](../README.md#requirements)). On
   reload you get the **MCP Server** view and the **Settings ▸ MCP** tab.

> **How the discovery chain is wired (all on GitHub).** The registry URL you paste serves
> `protege-mcp.repository`; its single line points Protégé at the raw `update.properties` on `main`;
> that descriptor's `download` points at the `protege-mcp-<version>.jar` asset on the matching
> **GitHub Release** (currently the **v{{ site.version }} Release**).
> Registry list → update descriptor → release asset — three hops, all hosted by this repo, nothing
> to host yourself.

> If **Check for plugins** shows an **empty** list, the registry or `download` URL was unreachable:
> an unresolvable URL is logged at `info` level and **silently skipped**, not shown as an error.
> Confirm the registry URL above resolves (open it in a browser) and that the current Release asset
> (e.g. the `v{{ site.version }}` jar) exists.

## Use your own fork

If you fork the project and want to serve the plugin from **your** account instead:

1. **Publish the jar.** Build (`mvn clean package`) and upload `target/protege-mcp-<v>.jar`
   somewhere downloadable — e.g. attach it to a **GitHub Release** on your fork.
2. **Repoint the URLs.** In `update.properties`, set `download=` (and `readme=`) to your fork's URLs;
   in `protege-mcp.repository`, change the raw URL so it resolves to your fork's `update.properties`.
   Commit and push both so their raw URLs are reachable, e.g.
   `https://raw.githubusercontent.com/<you>/protege-mcp/main/protege-mcp.repository`.
   **Point `readme=` at a raw, self-contained simple HTML file** (like the official plugins'
   `readme.html` — here `docs/readme.html`), e.g.
   `raw.githubusercontent.com/<you>/protege-mcp/main/docs/readme.html`. Protégé ignores the server
   `Content-Type` (raw GitHub serves `text/plain`) and instead **sniffs the body for `<html>`**,
   rendering it as HTML in a Swing `JEditorPane` (HTML 3.2) when found, else as plain text — so a raw
   `.html` file renders formatted. **Never** use a `github.com/…/blob/…` page: that returns the
   GitHub web-app HTML, which renders as garbage and whose fetch freezes the dialog. `license=` is
   opened in the external browser, so a `/blob/` URL is fine there.
3. **Point Protégé at your registry** (step 1 above) and run **Check for plugins**.

## Quick local test (no hosting)

Serve the repo over loopback HTTP and point a temporary jar URL at your build output:

```bash
# from the repo root, after `mvn clean package`:
python3 -m http.server 8000        # serves ./protege-mcp.repository, ./update.properties, ./target/...
```

Temporarily set `download=http://127.0.0.1:8000/target/protege-mcp-<v>.jar` in `update.properties`,
then set the Plugin-registry preference to `http://127.0.0.1:8000/protege-mcp.repository` and run
**Check for plugins**.

> The **registry URL** you put in Settings must be **http(s)** — Check for plugins does an
> `HttpURLConnection` HEAD on it first, so a `file://` registry root fails there with an *uncaught*
> `ClassCastException` (the action only catches `IOException`, so it errors out rather than showing
> the normal "could not connect" dialog). The `download=` URL *inside* the descriptor may be `file://`.

## Version rules (so it actually shows up)

`version` is compared as an **OSGi version**. OSGi orders an *empty* qualifier **before** a non-empty
one, so `0.1.0` **<** `0.1.0.SNAPSHOT`. Consequences:

- Installing into a Protégé that **doesn't have the plugin yet** → it appears regardless of `version`.
- Updating over an **already-installed** build → `version` must be **strictly greater** than the
  installed bundle version. Over a `0.1.0.SNAPSHOT` dev build, use e.g. `0.1.1`, not `0.1.0`; to ship
  an update over the released `0.1.0`, bump `update.properties` `version` and the bundle version
  together (e.g. `0.1.1`) and attach the new jar to a matching Release.

## Or get it into the official list

To appear in **everyone's** Protégé (the default registry), open a PR adding your `update.properties`
URL to `update-info/5.0.0/plugins.repository` in
[`protegeproject/autoupdate`](https://github.com/protegeproject/autoupdate) — same descriptor format
as above.
