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

Every model edit goes through `OWLModelManager.applyChanges`, so it appears in Protégé immediately
and joins the shared **undo stack**; document open/save (`load_ontology`, `save_ontology`, and the
file load inside `merge_ontology_document`) use Protégé's own load/save APIs instead. Entities are
referenced by **IRI or display name**.

### Explore & search

| Tool | Description |
| --- | --- |
| `list_ontologies` | List loaded ontologies (active + imports closure); marks the active one. |
| `get_active_ontology` | Active ontology details: IRI, axiom counts, and direct imports. |
| `summarize_ontology` | Signature counts, ontology annotation/import counts, and axiom-type counts; optionally includes the imports closure. |
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
| `add_annotation` | Add an annotation assertion to an entity. Value is a literal (optional `lang`/`datatype`) or an IRI (`value_iri`); optional `annotations` attach axiom annotations. |
| `add_axiom` | Add a structured axiom (see axiom types below). |
| `remove_axiom` | Remove a structured axiom (same arguments as `add_axiom`). |
| `rename_entity` | Change an entity's IRI throughout the active ontology (rewrites every referencing axiom; renames all puns at the IRI). |
| `delete_entity` | Remove an entity and every axiom that references it from the active ontology (optionally narrowed by `entity_type`). |
| `set_ontology_id` | Set the active ontology's IRI and optional version IRI. |
| `add_import` / `remove_import` | Add or remove an `owl:imports` declaration. |
| `add_ontology_annotation` / `remove_ontology_annotation` | Add or remove an ontology-level annotation (literal/typed/lang/IRI value, optional nested annotations). |

### Documents

| Tool | Description |
| --- | --- |
| `load_ontology` | Load an OWL document from a local path or URL into the workspace as its own ontology and make it active (fetched off the UI thread; not undoable). |
| `merge_ontology_document` | Load an OWL document from a local path or URL (GitHub blob URLs are converted to raw URLs) and copy its axioms, imports, ontology annotations, and optionally ontology ID into the active ontology in one bulk step. |

### History & persistence

| Tool | Description |
| --- | --- |
| `undo_change` | Undo the last change on Protégé's shared undo stack. |
| `redo_change` | Redo the last undone change. |
| `save_ontology` | Save the active ontology to its document on disk. |

### Reasoning

| Tool | Description |
| --- | --- |
| `list_reasoners` / `set_reasoner` | List the installed reasoner plugins (marking the current one) and select which reasoner Protégé uses. |
| `run_reasoner` | Run the selected reasoner (classify) and wait; reports status and unsatisfiable-class count. |
| `get_unsatisfiable_classes` | List unsatisfiable classes (equivalent to `owl:Nothing`). |
| `get_inferred_superclasses` | Read inferred relations: superclasses, subclasses, equivalent, types, instances. |
| `execute_dl_query` | Run a DL Query: given a Manchester class expression, return the reasoner's equivalent classes, subclasses, superclasses, and instances. |
| `explain_entailment` | Check whether a structured axiom is entailed by the active reasoner (true/false). |
| `get_explanations` | Explain *why* a structured axiom is entailed — return minimal justifications (sets of asserted axioms). |

`add_axiom`, `remove_axiom`, `explain_entailment`, and `get_explanations` take a structured `axiom_type`:

| `axiom_type` | Description |
| --- | --- |
| `subclass_of` | Assert `sub` as a subclass of `super`; both may be names, IRIs, or Manchester class expressions. |
| `equivalent_classes` | Assert all class expressions in `classes` as equivalent. |
| `disjoint_classes` | Assert all class expressions in `classes` as mutually disjoint. |
| `disjoint_union` | Assert `class` as the disjoint union of the class expressions in `classes`. |
| `class_assertion` | Assert `individual` as an instance of `class`; `class` may be a Manchester class expression. |
| `object_property_assertion` | Assert an object property relation: `subject` `property` `object`. |
| `data_property_assertion` | Assert a data property value: `subject` `property` `value`, with optional `lang` or `datatype`. |
| `negative_object_property_assertion` | Assert that `subject` `property` `object` does **not** hold. |
| `negative_data_property_assertion` | Assert that `subject` `property` `value` does **not** hold. |
| `same_individual` | Assert all `individuals` as the same individual. |
| `different_individuals` | Assert all `individuals` as pairwise different. |
| `sub_object_property_of` | Assert object `property` as a subproperty of `super_property`. |
| `sub_data_property_of` | Assert data `property` as a subproperty of `super_property`. |
| `sub_property_chain_of` | Assert an ordered object property `chain` as a subproperty of `super_property`. |
| `equivalent_object_properties` / `equivalent_data_properties` | Assert all `properties` as equivalent. |
| `disjoint_object_properties` / `disjoint_data_properties` | Assert all `properties` as mutually disjoint. |
| `inverse_object_properties` | Assert object `property` and `inverse_property` as inverses. |
| `transitive_object_property` | Assert object `property` as transitive. |
| `functional_object_property` | Assert object `property` as functional. |
| `inverse_functional_object_property` | Assert object `property` as inverse-functional. |
| `symmetric_object_property` | Assert object `property` as symmetric. |
| `asymmetric_object_property` | Assert object `property` as asymmetric. |
| `reflexive_object_property` | Assert object `property` as reflexive. |
| `irreflexive_object_property` | Assert object `property` as irreflexive. |
| `functional_data_property` | Assert data `property` as functional. |
| `has_key` | Assert that `properties` are a key for `class`. |
| `object_property_domain` | Set the object `property` domain to `domain`; `domain` may be a Manchester class expression. |
| `object_property_range` | Set the object `property` range to `range`; `range` may be a Manchester class expression. |
| `data_property_domain` | Set the data `property` domain to `domain`; `domain` may be a Manchester class expression. |
| `data_property_range` | Set the data `property` range to `range` — a datatype or a Manchester data range, e.g. `xsd:integer[>= 0]` or `{1, 2, 3}`. |
| `annotation_assertion` | Assert `property` on `subject` with a literal (`value` + optional `lang`/`datatype`) or IRI (`value_iri`) value. |
| `sub_annotation_property_of` | Assert annotation `property` as a subproperty of `super_property`. |
| `annotation_property_domain` / `annotation_property_range` | Set the annotation `property` `domain` / `range` IRI. |
| `declaration` | Declare `entity` of `entity_type` (class, object/data/annotation property, individual, datatype). |
| `datatype_definition` | Define datatype `datatype` as `range` — a datatype or a Manchester data range (e.g. `xsd:integer[>= 0]`). |

Any `axiom_type` may also carry an optional `annotations` operand — an array of
`{property, value | value_iri, lang, datatype}` — which attaches **axiom annotations** (the reified
`owl:Axiom` pattern). Together with `set_ontology_id`, `add_import`, and `add_ontology_annotation`,
the granular tools cover the full OWL 2 surface, so a rich document (ontology header, imports,
typed/IRI annotations, annotated axioms, property chains, characteristics) can be reconstructed
incrementally; `merge_ontology_document` does the same in one bulk, GUI-visible step.

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
