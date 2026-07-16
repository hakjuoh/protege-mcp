---
title: Contributing
nav_order: 14
has_children: true
---

<!-- This is the Just the Docs version of the contributing guide. Keep it in sync
     with /CONTRIBUTING.md, the plain-Markdown version GitHub shows on the
     "Contributing" tab (root beats docs/ in GitHub's community-file lookup). -->

# Contributing
{: .no_toc }

How to build Protégé MCP from source, run its tests, find your way around the code, and add a tool.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Build from source

You need **Git**, **Apache Maven**, and a **JDK 17+** (the build targets `--release 17`):

```bash
git clone https://github.com/hakjuoh/protege-mcp.git
cd protege-mcp
mvn clean package
```

The reactor produces `plugin/target/protege-mcp-<version>.jar`, the OSGi plugin, and
`cli/target/protege-mcp-cli-<version>-all.jar`, the executable headless Java 17 CLI. The plugin keeps
Protégé/OWLAPI/Guava/SLF4J as platform-provided dependencies; the CLI embeds its OWLAPI runtime and contains
no Protégé editor classes.

To try your build, copy the jar into Protégé's `plugins/` folder (replacing any existing
`protege-mcp-*.jar` / `io.github.hakjuoh.protege-mcp-*.jar`) and restart Protégé on a Java 17+ JVM. See
[Installation](installation.html).

## Run the tests

```bash
mvn test
```

The suite covers the tool cores in isolation, and **`ToolPipelineTest`** chains them end-to-end
**headlessly** (load → edit → validate → govern → diff → SPARQL) so a cross-tool regression fails in
CI. What the unit tests cannot reach — the OSGi bundle loading in a real Protégé, the HTTP/OAuth
transport, EDT marshalling, and a live reasoner — is covered by a manual
[**Live smoke test**](smoke-test.html) checklist you run against the built jar before a release.

## Continuous integration

[**CI**](https://github.com/hakjuoh/protege-mcp/actions/workflows/ci.yml) runs `mvn -B clean verify`
on JDK 17 (Temurin) for every push and pull request to `main`. Keep it green: a PR that fails to build
or test will not be merged.

## Project layout

Runtime plugin sources live under `plugin/src/main/java/io/github/hakjuoh/protege_mcp/`; the Maven reactor has
`core`, `plugin`, and `cli` modules, and the Protégé-free packages (`contracts`, `policy`, `core.diff`)
live in the core module under `core/src/main/java/`:

| Package | Responsibility |
| --- | --- |
| `server` | The embedded HTTP MCP server: lifecycle (`McpServerManager`, `McpServerController`), Jetty host (`EmbeddedHttpServer`), auth (`AccessTokenFilter`), and `OntologyAccess` (marshals tool work onto the EDT). |
| `oauth` | The embedded OAuth authorization server (dynamic client registration, PKCE, consent, token store). |
| `tools` | The tool implementations. Each `*Tools.java` builds a list of `SyncToolSpecification`s; `ToolCatalog` aggregates them all. |
| `prompts` | The guided MCP prompts. `Prompts.java` registers the templates; `PromptCatalog` aggregates the providers (mirrors the `tools` registry pattern). |
| `contracts` (core module) | Versioned project/revision/finding/stage/gate records; matching JSON Schemas are packaged under `core/src/main/resources/schema`. |
| `core` module | Compiles Protégé-free contracts, policy loading, fingerprints, and semantic diff for reuse by adapters; sources under `core/src/main/java/`. |
| `cli` module | Headless executable adapter and shaded-JAR smoke tests. |
| `chat` | The Ontology Assistant back end: the `ChatProvider` SPI and the Claude / Codex CLI providers + event parsers. |
| `ui` | Swing views/panels: `McpServerView`, `McpPreferencesPanel`, `ChatView`, `ChatTab`, `ChatPreferencesPanel`. |
| `config` | `McpConfig` — the settings snapshot backed by Protégé's preferences store. |

The plugin's extension points (views, tabs, preference panels, the editor-kit hook) are declared in
`plugin/src/main/resources/plugin.xml`.

## How to add a tool

1. **Write the spec.** In the appropriate `*Tools.java` (or a new one), build a `SyncToolSpecification`
   with `ToolSpecs.of(name, description, inputSchema, handler)`:
   - `name` — a stable, snake_case tool name.
   - `description` — what it does and when to use it (LLM clients rely on this).
   - `inputSchema` — a JSON Schema `Map` (use the `Tools.schema()` / property helpers).
   - `handler` — read args with `Tools.args(req)`, do the work, and return a structured result via
     `Tools.json().put(...).result()`. For **reads**, run through `ctx.access().compute(...)`; for
     **writes**, gate with the read-only / confirm-write check and apply via `applyChanges` so the edit
     is GUI-visible and undoable.
2. **Register it.** Make sure your `*Tools.specs(ctx)` is added in
   [`ToolCatalog.buildAll`](https://github.com/hakjuoh/protege-mcp/blob/main/plugin/src/main/java/io/github/hakjuoh/protege_mcp/tools/ToolCatalog.java).
3. **Test it.** Add a unit test for the core, and extend `ToolPipelineTest` if it participates in the
   end-to-end flow.
4. **Document it.** Add the tool to the matching page under
   [Tools](tools/) (arguments + returns), and bump the tool count in the README.

{: .tip }
> Keep every tool's output **structured JSON**, reference entities by **IRI or name**, and — for
> create/add tools — report **`new_entities`** and honour **`strict`**, matching the existing tools.

## Pull requests

1. Branch from `main`.
2. Make your change, with tests and docs.
3. Open a PR against `main`; make sure **CI is green**.
4. Keep commits focused; describe the *why* in the PR body.

Do not embed mutable VCS references (commit SHAs) in source or docs — use issue/PR numbers or release
versions instead.

## License

By contributing you agree that your contributions are licensed under the project's
[BSD 2-Clause License](https://github.com/hakjuoh/protege-mcp/blob/main/LICENSE).
