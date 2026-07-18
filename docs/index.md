---
title: Home
layout: home
nav_order: 1
description: "Protégé MCP — a Protégé Desktop plugin that runs a local MCP server and an in-app Ontology Assistant."
permalink: /
---

# Protégé MCP
{: .fs-9 }

Give AI tools live, gated access to the ontology you have open in **Protégé Desktop** — and chat with
an assistant that edits it for you, without ever leaving Protégé.
{: .fs-6 .fw-300 }

[Get started](installation.html){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/hakjuoh/protege-mcp){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## What is Protégé MCP?

**Protégé MCP** is a plugin for [Protégé Desktop](https://protege.stanford.edu/) that runs a local
**MCP ([Model Context Protocol](https://modelcontextprotocol.io/)) server** inside Protégé. It exposes
the **live, active ontology** — the one you have open in the editor — to any MCP-compatible AI client,
so the client can *explore it and make edits for you* through **80 structured tools** and **11 guided
prompts**.

Two ways to use it:

<div class="code-example" markdown="1">

**1. As an MCP server for an external client.**
Point [Claude Code](connect/claude-code.html), [Codex CLI](connect/codex-cli.html),
[VS Code](connect/vs-code.html), or [Claude Desktop](connect/claude-desktop.html) at
`http://127.0.0.1:8123/mcp` and it can read and edit your ontology.

**2. As an in-app assistant.**
The built-in [**Ontology Assistant**](ontology-assistant.html) chat tab drives a coding-agent CLI you
already have (Claude Code or Codex) back into the same MCP server — so you get a conversational editor
*inside Protégé*, with no separate client to run.

</div>

Everything an AI client does flows through Protégé's own model manager, so:

- **Edits appear in the GUI immediately** and join the shared **Edit ▸ Undo** stack.
- **Safety gates apply to every write** — a **read-only** mode and a **confirm-each-write** prompt you
  control from **Settings ▸ MCP**.
- **The server is local and authorized** — it binds to `127.0.0.1` unless you deliberately pick
  another interface, and requires an OAuth authorization or a bearer token on every request.

## Why use it?

- **Author faster in natural language.** "Create a class *Widget* under *Product* with a
  definition and label," or "find every class with no definition and propose fixes" — the assistant
  turns that into concrete, previewable axiom changes.
- **Stay in control.** Preview edits before they apply, gate or confirm every write, and undo anything
  with one keystroke. The AI can never escalate past the gates you set.
- **Reason and query.** Run the reasoner, explain entailments, find unsatisfiable classes, and run
  **SPARQL 1.1** over the active ontology and its imports — all through tools.
- **Govern your project.** Audit modelling quality and **project-governance policy** (OWL 2 profile
  conformance, IRI policy, required annotations, module ownership / import layering), load persisted
  invariants/CQs/SHACL from a checked-in policy, and distinguish a validation failure from a check that
  could not run.
- **Enforce project boundaries.** Policy-relative paths stay below the canonical project root, remote
  documents/imports require explicit network policy and capability, and locked import content is checked
  automatically before a project gate or change-set commit can pass.
- **Exchange a portable project identity.** Validate RO-Crate metadata and compute a W3C RDFC-1.0
  identity for the asserted root RDF dataset, while keeping dependency checksums in an import lock.
- **No API key stored by Protégé.** The Ontology Assistant reuses your existing CLI login; nothing is
  kept in the plugin.

## Quick start

1. [**Install**](installation.html) the plugin (drop the jar into Protégé's `plugins/` folder, or use
   *File ▸ Check for plugins*). Protégé must run on **Java 17+**.
2. Open the **MCP Server** view, start the server, and note the URL (`http://127.0.0.1:8123/mcp`).
3. Either [**connect an external client**](connect/) or open the [**Ontology Assistant**](ontology-assistant.html)
   tab and type a request.

## Documentation map

| Section | What's inside |
| --- | --- |
| [Installation](installation.html) | Requirements (Java 17+), manual install, and *Check for plugins*. |
| [Connecting a client](connect/) | The server model (ports, OAuth vs. bearer token) and per-environment recipes for Claude Code, Codex CLI, VS Code, and Claude Desktop. |
| [Ontology Assistant](ontology-assistant.html) | The in-Protégé chat: what it is, how it works, attachments, privacy, and settings. |
| [Tools](tools/) | All 80 tools by category, including a summary of the 12 tools added in v0.6.0. |
| [Prompts](prompts/) | The 11 guided workflows available to MCP clients. |
| [Headless CLI](cli.html) | Run policy validation and asserted semantic diff without Protégé. |
| [Project policy & QC](project-policy.html) | Policy discovery/validation, fingerprints, persisted QC assets, examples, and strict gate semantics. |
| [RO-Crate & RDFC](interoperability/) | Package portable project metadata and produce a canonical RDF dataset identity. |
| [Contributing](contributing.html) | Build from source, run the tests, project layout, and how to add a tool. |
| [Versioning & releases](versioning.html) | The SemVer + Keep-a-Changelog scheme and how releases are cut. |
| [Changelog](changelog.html) | Release notes for every version. |

---

*Protégé MCP is licensed under the [BSD 2-Clause License](https://github.com/hakjuoh/protege-mcp/blob/main/LICENSE).*
