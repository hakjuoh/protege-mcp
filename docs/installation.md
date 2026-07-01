---
title: Installation
nav_order: 2
has_children: true
---

# Installation
{: .no_toc }

How to install Protégé MCP, the Java requirement it depends on, and how to confirm it loaded.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Requirements

Protégé must run on **Java 17 or newer**.

The MCP Java SDK and the embedded Jetty 12 host are Java 17 bytecode, and the bundle's OSGi manifest
declares `Require-Capability: osgi.ee … JavaSE 17`. On Java 11 the bundle **will not resolve** — there
is no Java-11 build path, because every published MCP SDK version is Java 17 bytecode.

{: .warning }
> Protégé's bundled launchers may ship a Java 11 runtime. If Protégé is on Java 11, the plugin silently
> fails to load (it never appears in the views/menus). Point Protégé at a 17+ JDK first.

Protégé's `run.sh` / `run.command` launchers honour the **`PROTEGE_JAVA_HOME`** environment variable:

```bash
export PROTEGE_JAVA_HOME=/path/to/jdk-17
./run.sh        # or ./run.command
```

For the macOS `.app` / Windows `.exe` launchers, set the JVM in the launcher's own configuration
instead (e.g. the `.app`'s `Info.plist` / bundled `jre`, or the Windows launcher `.l4j.ini`).

## Install the plugin

You can install manually (drop in a jar) or let Protégé discover and update the plugin for you.

### Option A — Manual (drop-in jar)

1. Download `protege-mcp-<version>.jar` from the
   [latest release](https://github.com/hakjuoh/protege-mcp/releases/latest).
2. Drop it into Protégé's plugins directory:
   - **macOS:** `~/.Protege/plugins/` (per-user), or the in-bundle
     `Protégé.app/Contents/Java/plugins/`.
   - **Windows / Linux:** the `plugins/` folder inside the Protégé install, e.g.
     `C:\Program Files\Protege-5.6.x\plugins\` or `<protege-install>/plugins/`.
3. Restart Protégé on a Java 17+ JVM (see [Requirements](#requirements)).

{: .note }
> Keep exactly **one** `protege-mcp-*.jar` (or `io.github.hakjuoh.protege-mcp-*.jar`) in the plugins folder. The
> bundle is a **singleton**; two versions side by side will conflict. Delete the old jar before adding
> a new one.

### Option B — Check for plugins (auto-discovery + updates)

Protégé can discover the plugin and offer future updates from a self-hosted registry. See
[**Install via Check for plugins**](check-for-plugins.html) for the full walk-through. In short:

1. **Settings ▸ Plugins ▸ Plugin registry** →
   `https://raw.githubusercontent.com/hakjuoh/protege-mcp/main/protege-mcp.repository`
2. **File ▸ Check for plugins** → tick **Protege MCP Server** → install → restart.

## Verify it loaded

After restarting, you should see:

- A **MCP Server** view — add it from **Window ▸ Views ▸ Miscellaneous views**, or to any tab. It shows
  the server status, bound URL, and bearer token.
- A **MCP** settings tab under **Settings ▸ MCP** (port, autostart, read-only, confirm-writes).
- An **Ontology Assistant** top-level tab, and an **Ontology Assistant** settings tab under
  **Settings ▸ Ontology Assistant**.

The bundle id is `io.github.hakjuoh.protege-mcp` (singleton).

## Next steps

- [Connect an external MCP client](connect/) (Claude Code, Codex CLI, VS Code, Claude Desktop), or
- Open the [Ontology Assistant](ontology-assistant.html) and start chatting.
