# Protégé MCP

[![CI](https://github.com/hakjuoh/protege-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/hakjuoh/protege-mcp/actions/workflows/ci.yml)
[![Release](https://github.com/hakjuoh/protege-mcp/actions/workflows/release.yml/badge.svg)](https://github.com/hakjuoh/protege-mcp/actions/workflows/release.yml)
[![License: BSD-2-Clause](https://img.shields.io/badge/License-BSD%202--Clause-blue.svg)](LICENSE)

**Protégé MCP** is a plugin for **Protégé Desktop** that runs a local **MCP (Model Context Protocol)
server**. It gives MCP-compatible AI tools — such as **Claude Code** and **Codex** — live access to the
ontology you have open in Protégé, so they can explore it and make edits for you. It also ships an
in-app **Ontology Assistant** chat that drives your own `claude` / `codex` CLI against the same server.

## 📖 Documentation

| Guide | What's inside |
| --- | --- |
| [Installation](https://hakjuoh.github.io/protege-mcp/installation.html) | Requirements (Java 17+), manual install, and *Check for plugins*. |
| [Connecting a client](https://hakjuoh.github.io/protege-mcp/connect/) | The server model (ports, OAuth vs. token) + Claude Code, Codex CLI, VS Code, Claude Desktop recipes. |
| [Ontology Assistant](https://hakjuoh.github.io/protege-mcp/ontology-assistant.html) | The in-Protégé chat: what it is, attachments, privacy, settings. |
| [Tools](https://hakjuoh.github.io/protege-mcp/tools/) | All 66 tools by category, each with **arguments and returns**, plus the axiom-type catalog and guided prompts. |
| [Contributing](https://hakjuoh.github.io/protege-mcp/contributing.html) | Build from source, run the tests, and add a tool. |
| [Versioning & releases](https://hakjuoh.github.io/protege-mcp/versioning.html) · [Changelog](https://hakjuoh.github.io/protege-mcp/changelog.html) | The release scheme and notes for every version. |

## Highlights

- **66 structured tools + 6 guided prompts** over the live, active ontology — explore, edit, curate,
  govern, extract modules, run SWRL rules, SPARQL and SHACL, and reason.
- **Safe, testable authoring** (`0.4.0`) — `apply_changes verify=report|rollback` classifies the reasoner
  and reverts an edit that makes a class unsatisfiable or the ontology inconsistent; a re-runnable
  **competency-question suite** and SPARQL **invariants** (`verify_ontology`) catch regressions; a single
  **`run_qc_suite`** gate rolls up reasoner + profile + structural + invariants + CQ checks; and
  `search_entities` now returns `score`/`match_kind` + `would_mint` so an assistant grounds a term before
  minting a new one.
- **Edits are GUI-visible and undoable** — every edit goes through Protégé's shared `OWLModelManager`,
  so it appears in the editor immediately and joins the **Edit ▸ Undo** stack.
- **Safe by default** — a local, loopback-only server that requires **OAuth** or a **bearer token** on
  every request, with optional **read-only** mode and **confirm-each-write**.
- **In-app Ontology Assistant** — chat that drives your existing `claude` / `codex` CLI back into the
  server; **no API key is stored by Protégé**.

## Requirements

Protégé must run on **Java 17+** — the bundle is Java 17 bytecode and its OSGi manifest requires
`JavaSE 17`, so it will not load on Java 11. If Protégé ships a Java 11 runtime, point it at a 17+ JDK
via the `PROTEGE_JAVA_HOME` environment variable. See
[Installation → Requirements](https://hakjuoh.github.io/protege-mcp/installation.html#requirements).

## Install

1. Download `protege-mcp-<version>.jar` from the
   [latest release](https://github.com/hakjuoh/protege-mcp/releases/latest).
2. Drop it into Protégé's `plugins/` folder (keep only **one** such jar — the bundle is a singleton).
3. Restart Protégé on a Java 17+ JVM.

Prefer automatic discovery and updates? Use Protégé's **File ▸ Check for plugins**. Full instructions,
including per-OS plugin paths, are in the
[Installation guide](https://hakjuoh.github.io/protege-mcp/installation.html).

## Quick start

1. **Settings ▸ MCP** — configure and start the server (default `http://127.0.0.1:8123/mcp`). The **MCP
   Server** view shows the bound URL and bearer token.
2. Either **[connect an MCP client](https://hakjuoh.github.io/protege-mcp/connect/)** (Claude Code,
   Codex CLI, VS Code, Claude Desktop) or open the **Ontology Assistant** tab and type a request —
   *"What classes are in this ontology?"*, then *"Create a class FooBar under Thing."*

## Building from source

Requires **Git**, **Apache Maven**, and a **JDK 17+**:

```bash
git clone https://github.com/hakjuoh/protege-mcp.git
cd protege-mcp
mvn clean package
```

The `target/` directory will contain `protege-mcp-<version>.jar` — a self-contained OSGi bundle that
inlines the MCP + Jetty + Jackson + Jena stack (Protégé/OWLAPI/Guava/SLF4J stay `provided`). See the
[Contributing guide](https://hakjuoh.github.io/protege-mcp/contributing.html) for project layout and how
to add a tool.

## License

The software is licensed under the [BSD 2-Clause License](LICENSE).
