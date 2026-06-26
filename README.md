# protege-mcp

An **MCP (Model Context Protocol) server** for **Protégé Desktop**, packaged as a single OSGi
plugin. It exposes the **live, active ontology** of a running Protégé to MCP clients over a localhost
HTTP endpoint. Reads and edits flow through Protégé's shared `OWLModelManager`, so they appear in the
GUI immediately and join the **undo stack** — exactly like manual edits.

Design and rationale — the shipped in-Protégé MCP server (Architecture Approach A), the planned in-app
Claude chat (Architecture Approach B), and distribution: [`DESIGN.md`](DESIGN.md).

## Requirements

Protégé must run on **Java 17+**. The MCP Java SDK and the embedded Jetty 12 host are Java 17
bytecode, and the OSGi manifest declares `Require-Capability: osgi.ee … JavaSE 17`, so on Java 11 the
bundle will not resolve. (There is no Java-11 build path: every published MCP SDK version is Java 17
bytecode.)

Protégé's bundled launchers may ship a Java 11 runtime. Point Protégé at a 17+ JDK with the
**`PROTEGE_JAVA_HOME`** environment variable, which Protégé's `run.sh` / `run.command` launchers honor:

```bash
export PROTEGE_JAVA_HOME=/path/to/jdk-17
./run.sh        # or run.command
```

(For the macOS `.app` / Windows `.exe` launchers, set the JVM in the launcher's config instead.)

## Install

1. Download `protege-mcp-<version>.jar` from the
   [latest release](https://github.com/hakjuoh/protege-mcp/releases/latest).
2. Drop it into Protégé's plugins directory:
   - **macOS:** `~/.Protege/plugins/` (per-user), or the in-bundle
     `Protégé.app/Contents/Java/plugins/`
   - **Windows / Linux:** the `plugins/` folder inside the Protégé install, e.g.
     `C:\Program Files\Protege-5.6.x\plugins\` or `<protege-install>/plugins/`
3. Restart Protégé (on a Java 17+ JVM — see [Requirements](#requirements)).

On load you get a **MCP Server** view (Window ▸ Views ▸ Miscellaneous views, or add it to any tab)
and a **MCP** settings tab (Settings ▸ MCP). The bundle id is `io.github.hakjuoh.protege-mcp` (singleton).

> Prefer automatic discovery and updates from inside Protégé? See
> [docs/check-for-plugins.md](docs/check-for-plugins.md).

## Use

1. **Settings ▸ MCP** — set the port (default `8123`, or `0` for an ephemeral port), auto-start,
   read-only mode, and per-write confirmation.
2. **MCP Server view** — start/stop the server, see the bound URL and bearer token, regenerate the
   token, and copy a ready-to-paste connect command.
3. Connect a client to `http://127.0.0.1:8123/mcp`. The server binds to `127.0.0.1` only and
   **requires authorization** on every request.

Two auth modes are supported in parallel:

- **OAuth (recommended)** — OAuth-capable clients (e.g. Claude Code) get a `401` with discovery
  metadata, register dynamically (RFC 7591), and open a **browser consent page**; click **Allow** and
  you're connected — no token to copy. Registered clients and tokens are persisted to Protégé's
  preferences store, so a client that authorized once keeps working across restarts; access tokens
  expire after 30 days. Revoke a client from the MCP Server view to force re-authorization.
  Endpoints are HTTP on loopback (RFC 8252 exempts loopback from HTTPS).
- **Static bearer token** — read the token from the MCP Server view and send it as
  `Authorization: Bearer <TOKEN>`.

### Claude Code

```bash
# OAuth — just add the URL, then approve in the browser:
claude mcp add --transport http protege-mcp http://127.0.0.1:8123/mcp

# or with a static token:
claude mcp add --transport http protege-mcp http://127.0.0.1:8123/mcp \
  --header "Authorization: Bearer <TOKEN>"
```

### Codex CLI

```bash
# OAuth — add the URL, then approve in the browser:
codex mcp add protege-mcp --url http://127.0.0.1:8123/mcp
codex mcp login protege-mcp

# or with a static token:
export PROTEGE_MCP_TOKEN="<TOKEN>"
codex mcp add protege-mcp --url http://127.0.0.1:8123/mcp \
  --bearer-token-env-var PROTEGE_MCP_TOKEN
```

### VS Code

Open the config from the Command Palette: **MCP: Open Workspace Folder MCP Configuration** for
`.vscode/mcp.json`, or **MCP: Open User Configuration** for your user-level `mcp.json`. You can also
run **MCP: Add Server** for the guided flow.

Without `PROTEGE_MCP_TOKEN`, paste the token directly:

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

With `PROTEGE_MCP_TOKEN`, launch VS Code from an environment where the variable is set, then use:

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

### Claude Desktop — `claude_desktop_config.json`

Open Claude Desktop **Settings ▸ Developer ▸ Edit Config**, edit `claude_desktop_config.json`, then
save and restart Claude Desktop. Claude Desktop is stdio-only, so it connects through the
`mcp-remote` bridge (needs Node/`npx`).

Without `PROTEGE_MCP_TOKEN`, paste the token directly:

```json
{
  "mcpServers": {
    "protege": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:8123/mcp",
        "--header",
        "Authorization:Bearer <TOKEN>"
      ]
    }
  }
}
```

With `PROTEGE_MCP_TOKEN`:

```json
{
  "mcpServers": {
    "protege": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:8123/mcp",
        "--header",
        "Authorization:Bearer ${PROTEGE_MCP_TOKEN}"
      ],
      "env": {
        "PROTEGE_MCP_TOKEN": "<TOKEN>"
      }
    }
  }
}
```

## Tools

Every edit goes through `OWLModelManager.applyChanges`, so it appears in Protégé immediately and
joins the shared **undo stack**. Entities are referenced by **IRI or display name**.

### Explore & search

| Tool | Description |
| --- | --- |
| `list_ontologies` | List loaded ontologies (active + imports closure); marks the active one. |
| `get_active_ontology` | Active ontology details: IRI, axiom counts, and direct imports. |
| `list_classes` | List named classes in the active ontology's signature (rendering + IRI). |
| `search_entities` | Search entities by name/IRI fragment (substring match), filtered by type, with `*` wildcards; an empty or wildcard-only query lists the active ontology's signature. |
| `get_entity` | Look up an entity by IRI or name; returns its type(s), IRI, and rendering. |
| `get_axioms_for_entity` | Axioms that reference an entity; `include_imports` extends the search to the imports closure (e.g. an imported term's domain/range). |

### Edit

| Tool | Description |
| --- | --- |
| `create_class` | Create a named class (optional explicit `iri` and `parent` superclass). |
| `create_entity` | Create a named entity: class, object/data/annotation property, individual, or datatype. |
| `add_subclass_of` | Assert that one class is a subclass of another. |
| `add_annotation` | Add an annotation assertion to an entity (default `rdfs:label`). |
| `add_axiom` | Add a structured axiom (see axiom types below). |
| `remove_axiom` | Remove a structured axiom (same arguments as `add_axiom`). |

### History & persistence

| Tool | Description |
| --- | --- |
| `undo_change` | Undo the last change on Protégé's shared undo stack. |
| `redo_change` | Redo the last undone change. |
| `save_ontology` | Save the active ontology to its document on disk. |

### Reasoning

| Tool | Description |
| --- | --- |
| `run_reasoner` | Run the selected reasoner (classify) and wait; reports status and unsatisfiable-class count. |
| `get_unsatisfiable_classes` | List unsatisfiable classes (equivalent to `owl:Nothing`). |
| `get_inferred_superclasses` | Read inferred relations: superclasses, subclasses, equivalent, types, instances. |
| `explain_entailment` | Check whether a structured axiom is entailed by the active reasoner. |

`add_axiom`, `remove_axiom`, and `explain_entailment` take a structured `axiom_type`: `subclass_of`,
`equivalent_classes`, `disjoint_classes`, `class_assertion`, `object_property_assertion`,
`data_property_assertion`.

## Building from Source

Release JARs can be downloaded from the [Releases page](https://github.com/hakjuoh/protege-mcp/releases)
(see [Install](#install)). To build it yourself you need **Git**, **Apache Maven**, and a **JDK 17+**
(the build targets `--release 17`):

```bash
git clone https://github.com/hakjuoh/protege-mcp.git
cd protege-mcp
mvn clean package
```

The `target` directory will contain a `protege-mcp-<version>.jar` — a self-contained OSGi bundle
that inlines the MCP + Jetty + Jackson stack (Protégé/OWLAPI/Guava/SLF4J stay `provided`).

## License

The software is licensed under the [BSD 2-clause License](LICENSE).
