<!-- GitHub renders this file on the "Contributing" tab (root beats docs/ in
     GitHub's community-file lookup), so it stays plain GitHub-flavored Markdown.
     Keep it in sync with docs/contributing.md, the Just the Docs version. -->

# Contributing

Thanks for your interest in improving **Protégé MCP**! This guide covers how to build the plugin from
source, run its tests, find your way around the code, and add a tool.

> The full manual — installation, connecting a client, and the complete tool reference — lives at
> **[hakjuoh.github.io/protege-mcp](https://hakjuoh.github.io/protege-mcp/)**.

## Table of contents

- [Build from source](#build-from-source)
- [Run the tests](#run-the-tests)
- [Continuous integration](#continuous-integration)
- [Project layout](#project-layout)
- [How to add a tool](#how-to-add-a-tool)
- [Pull requests](#pull-requests)
- [License](#license)

## Build from source

You need **Git**, **Apache Maven**, and a **JDK 17+** (the build targets `--release 17`):

```bash
git clone https://github.com/hakjuoh/protege-mcp.git
cd protege-mcp
mvn clean package
```

The `target/` directory will contain `protege-mcp-<version>.jar` — a self-contained OSGi bundle that
**inlines** the MCP + Jetty + Jackson + Apache Jena stack. Protégé, the OWL API, Guava, and SLF4J are
`provided` (supplied by the Protégé platform at runtime), so they are *not* bundled.

To try your build, copy the jar into Protégé's `plugins/` folder (replacing any existing
`protege-mcp-*.jar` / `io.github.hakjuoh.protege-mcp-*.jar`) and restart Protégé on a Java 17+ JVM. See the
[Installation guide](https://hakjuoh.github.io/protege-mcp/installation.html).

## Run the tests

```bash
mvn test
```

The suite covers the tool cores in isolation, and **`ToolPipelineTest`** chains them end-to-end
**headlessly** (load → edit → validate → govern → diff → SPARQL) so a cross-tool regression fails in
CI. What the unit tests cannot reach — the OSGi bundle loading in a real Protégé, the HTTP/OAuth
transport, EDT marshalling, and a live reasoner — is covered by a manual
[**Live smoke test**](https://hakjuoh.github.io/protege-mcp/smoke-test.html) checklist you run against
the built jar before a release.

## Continuous integration

[**CI**](https://github.com/hakjuoh/protege-mcp/actions/workflows/ci.yml) runs `mvn -B clean package`
on JDK 17 (Temurin) for every push and pull request to `main`. Keep it green: a PR that fails to build
or test will not be merged.

## Project layout

Everything lives under `src/main/java/io/github/hakjuoh/protege_mcp/`:

| Package | Responsibility |
| --- | --- |
| `server` | The embedded HTTP MCP server: lifecycle (`McpServerManager`, `McpServerController`), Jetty host (`EmbeddedHttpServer`), auth (`AccessTokenFilter`), and `OntologyAccess` (marshals tool work onto the EDT). |
| `oauth` | The embedded OAuth authorization server (dynamic client registration, PKCE, consent, token store). |
| `tools` | The tool implementations. Each `*Tools.java` builds a list of `SyncToolSpecification`s; `ToolCatalog` aggregates them all. `Prompts.java` holds the guided prompts. |
| `chat` | The Ontology Assistant back end: the `ChatProvider` SPI and the Claude / Codex CLI providers + event parsers. |
| `ui` | Swing views/panels: `McpServerView`, `McpPreferencesPanel`, `ChatView`, `ChatTab`, `ChatPreferencesPanel`. |
| `config` | `McpConfig` — the settings snapshot backed by Protégé's preferences store. |

The plugin's extension points (views, tabs, preference panels, the editor-kit hook) are declared in
`src/main/resources/plugin.xml`.

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
   [`ToolCatalog.buildAll`](https://github.com/hakjuoh/protege-mcp/blob/main/src/main/java/io/github/hakjuoh/protege_mcp/tools/ToolCatalog.java).
3. **Test it.** Add a unit test for the core, and extend `ToolPipelineTest` if it participates in the
   end-to-end flow.
4. **Document it.** Add the tool to the matching page under
   [Tools](https://hakjuoh.github.io/protege-mcp/tools/) (arguments + returns), and bump the tool
   count in the README.

> [!TIP]
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
[BSD 2-Clause License](LICENSE).
