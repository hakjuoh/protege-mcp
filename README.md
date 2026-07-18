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
| [Tools](https://hakjuoh.github.io/protege-mcp/tools/) | All 80 tools by category, including a summary of the 12 tools added in v0.6.0. |
| [Prompts](https://hakjuoh.github.io/protege-mcp/prompts/) | The 11 guided workflows available to MCP clients. |
| [Project policy & QC](https://hakjuoh.github.io/protege-mcp/project-policy.html) | Policy v1 discovery/validation, fingerprints, persisted QC assets, examples, and strict gate semantics. |
| [RO-Crate & RDFC](https://hakjuoh.github.io/protege-mcp/interoperability/) | Package portable project metadata and produce a canonical RDF dataset identity. |
| [Contributing](https://hakjuoh.github.io/protege-mcp/contributing.html) | Build from source, run the tests, and add a tool. |
| [Versioning & releases](https://hakjuoh.github.io/protege-mcp/versioning.html) · [Changelog](https://hakjuoh.github.io/protege-mcp/changelog.html) | The release scheme and notes for every version. |

## Highlights

- **80 structured tools + 11 guided prompts** over the live, active ontology — explore, edit, curate,
  govern, extract modules, run SWRL rules, SPARQL and SHACL, and reason.
- **Enforced project boundaries** (`0.6.1`) — direct paths are confined to the canonical project root
  with request-scoped filesystem capabilities, policy network/import controls block unapproved fetches,
  locked-import checksums are automatic gates, and verified rollback rejects a failing delta before it
  reaches the live ontology.
- **One endpoint, any number of windows** (`0.5.0`) — a shared **broker** owns the configured port
  across every Protégé window and instance and routes each MCP session to a live window, so one fixed
  URL (`http://127.0.0.1:8123/mcp`) always works.
- **Safe, testable authoring** (`0.4.0`, strengthened in `0.6.1`) — `apply_changes
  verify=report|rollback` runs the isolated project/change-set gate before commit; a re-runnable
  **competency-question suite** and SPARQL **invariants** (`verify_ontology`) catch regressions; a single
  **`run_qc_suite`** gate rolls up reasoner + profile + structural + invariants + CQ checks; and
  `search_entities` now returns `score`/`match_kind` + `would_mint` so an assistant grounds a term before
  minting a new one.
- **Reproducible project QC** (`0.6.0`) — discover and validate a checked-in policy, fingerprint the active
  ontology and loaded imports, resolve persisted invariant/CQ/multi-SHACL assets inside a confined project
  root, and run reasoner/profile/structural/governance/invariant/CQ/SHACL checks over one isolated snapshot
  with distinct `pass` / policy `fail` / execution `error` outcomes. The selected reasoner's exact Protégé
  configuration is preserved without classifying or mutating the live reasoner.
- **Portable project identity** (`0.6.0`) — validate RO-Crate 1.0–1.3 metadata and report a W3C
  RDFC-1.0 + SHA-256 identity for the asserted root RDF dataset, separately from the editor revision
  fingerprint and imported-artifact checksums.
- **Import integrity inspection** (`0.6.0`) — inspect deterministic direct/transitive import graphs,
  missing documents, cycles, source locations, and version conflicts; document loads keep the compatible
  warning default and support strict `missing_imports=error` for project/release workflows.
- **Edits are GUI-visible and undoable** — every edit goes through Protégé's shared `OWLModelManager`,
  so it appears in the editor immediately and joins the **Edit ▸ Undo** stack.
- **Safe by default** — a local server, bound to loopback (`127.0.0.1`) unless you change the
  bind-address preference, that requires **OAuth** or a **bearer token** on every request, with
  optional **read-only** mode and **confirm-each-write**.
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

The reactor produces `plugin/target/protege-mcp-<version>.jar` (the self-contained OSGi plugin) and
`cli/target/protege-mcp-cli-<version>-all.jar` (an executable headless Java 17 CLI). The CLI supports
`--version`, `validate-policy --project FILE`, and asserted `diff --left FILE --right FILE` without a
Protégé installation. See the
[Contributing guide](https://hakjuoh.github.io/protege-mcp/contributing.html) for project layout and how
to add a tool.

## License

The software is licensed under the [BSD 2-Clause License](LICENSE).
