# Protégé MCP

[![CI](https://github.com/hakjuoh/protege-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/hakjuoh/protege-mcp/actions/workflows/ci.yml)
[![Release](https://github.com/hakjuoh/protege-mcp/actions/workflows/release.yml/badge.svg)](https://github.com/hakjuoh/protege-mcp/actions/workflows/release.yml)
[![License: BSD-2-Clause](https://img.shields.io/badge/License-BSD%202--Clause-blue.svg)](LICENSE)

**Protégé MCP** is a plugin for **Protégé Desktop** that runs a local **MCP (Model Context Protocol)
server**. It gives MCP-compatible AI tools — such as **Claude Code** and **Codex** — live access to the
ontology you have open in Protégé, so they can explore it and make edits for you.

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

## In-Protégé chat (Ontology Assistant)

New in `0.3.0`: a chat assistant **inside Protégé** that edits the live ontology for you. Instead of the
plugin calling a model API directly, it **drives a coding-agent CLI you already have installed** — Claude
Code (`claude`) or OpenAI Codex (`codex`) — and points it back at this plugin's own MCP server. So the
assistant reads/edits through the same tools an external client uses: changes appear in the GUI and join
the **undo stack**, and the read-only / confirm-write gates still apply. **No API key is stored by
Protégé** — each CLI uses your existing login.

**Prerequisites**

- Install and log in to at least one CLI:
  - Claude Code: <https://docs.claude.com/en/docs/claude-code> (then `claude` works in your terminal)
  - Codex: <https://github.com/openai/codex> (`codex login`)
- The MCP server must be running (the chat starts it automatically on the first message).

**Use**

1. Open the **Ontology Assistant** tab (it appears as a top-level tab), or add the **Ontology Assistant** view to any tab via
   Window ▸ Views.
2. Pick a provider — **Use Claude** / **Use Codex** (only installed CLIs are shown) — and optionally a
   model (blank uses the CLI's own default; the field is editable for any model your account supports).
3. Type a request and press **Enter** (Shift+Enter for a newline). Try a read first — *"What classes are
   in this ontology?"* — then an edit — *"Create a class FooBar under Thing with label 'Foo Bar'."*
   Long pasted text is compacted in the input as `[Pasted content #N: … chars]`, and **Attach** or
   drag/drop adds files/images as placeholders such as `[Image #1]`. Watch it stream; **Stop** cancels
   mid-turn; **Edit ▸ Undo** reverts any edit.

**Privacy & cost.** The chat sends your prompts, attachments/pasted content, and the ontology content the
assistant reads to your model provider **via the CLI** (a one-time banner discloses this). Each attached file
or image is copied into its own private temp folder and only that copy is exposed to the CLI — never the rest
of its containing folder — and the temp copies are deleted when the turn finishes. Cost and rate limits are
governed by your CLI's own subscription/account. Edits obey the **MCP** preferences (read-only,
confirm-each-write); a **Confirm each edit** checkbox in the panel toggles confirmation live.

**Settings (Settings ▸ Ontology Assistant).** If Protégé was launched from the macOS Dock/Finder it may not have
your shell `PATH`, so a CLI can fail to resolve — set an explicit path to the `claude` / `codex`
executable there. The panel also shows what was detected and can reset the egress consent prompt.

## Tools

Every tool returns a **structured JSON object** (carried as MCP `structuredContent` and mirrored as a
JSON text block, so every client and a human reading the transcript see the same result). Every model
edit goes through `OWLModelManager.applyChanges`, so it appears in Protégé immediately and joins the
shared **undo stack**; document open/save (`load_ontology`, `save_ontology`, and the file load inside
`merge_ontology_document`) use Protégé's own load/save APIs instead. Entities are referenced by **IRI
or display name**.

A typical natural-language flow: **orient** (`get_ontology_context` / `get_entity_context`) → resolve
names to IRIs (`search_entities` / `get_entity`) → **preview** an edit (`preview_changes`) → apply
(the edit tools) → **verify** (`run_reasoner`, `validate_ontology`). The [MCP prompts](#prompts-guided-workflows)
package these flows.

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

### Context & validation

| Tool | Description |
| --- | --- |
| `get_ontology_context` | One-call orientation: id, signature counts, imports, ontology annotations, asserted root classes, sampled properties, reasoner state, and the prefix map. |
| `get_entity_context` | An "entity card" for one term: type(s), labels/annotations, deprecation, and the asserted neighbourhood (super/sub/equivalent/disjoint classes, domains/ranges, super/sub properties, inverses, characteristics, instances, property assertions). Named neighbours are returned as `{iri, display, type}` (anonymous restriction/intersection supers as `{expression, anonymous:true}`) so they resolve to IRIs without a second lookup. Resolves puns. |
| `validate_ontology` | Modelling-quality audit (not logical consistency): missing/duplicate labels, missing definitions, deprecated-but-used terms, undeclared entities, properties with no domain/range, self-subclassing, subclass cycles, and isolated classes — each with a count, sample offenders, and a fix suggestion. Pass `with_reasoner=true` to also include the reasoner's consistency / unsatisfiable-class verdict (a clean audit is **not** proof of logical consistency). |
| `diff_ontologies` | Axiom-level diff between two ontologies (or the active ontology against a freshly-loaded `right_document`, without adding it to the workspace): reports counts and capped samples of axioms only-in-left / only-in-right, and `identical=true` when the two match — the round-trip safety net for verifying a reconstruction. `include_imports` / `logical_only` scope the comparison; a `right_document` is loaded catalog-aware (sibling `catalog-v001.xml`), so an `include_imports` diff compares like-for-like import closures. |

### Edit

| Tool | Description |
| --- | --- |
| `preview_changes` | Dry-run a batch of axiom add/remove operations **without applying them**: reports, per operation, the rendered axiom, whether it is already present, and the new entities an add would introduce. Apply with `apply_changes` or the single-axiom edit tools once the diff looks right. |
| `apply_changes` | Apply a whole batch of axiom add/remove operations (the same `operations` array as `preview_changes`) in **one call** and one undo entry, with a per-operation result and an aggregated `new_entities`. `strict=true` skips any add that would mint a brand-new entity. |
| `create_class` | Create a named class. Mint the IRI from an explicit `iri`, a `namespace` (IRI = namespace + name, for terms in a shared namespace distinct from the ontology IRI), or `name`. Adds an `rdfs:label` (`label`/`name`, tagged with `label_lang`) unless `no_label`. Optional `parent`. |
| `create_entity` | Create a named entity (class, object/data/annotation property, individual, datatype) with the same `iri`/`namespace`/`label`/`label_lang`/`no_label` options. |
| `add_subclass_of` | Assert that one class is a subclass of another. Reports `new_entities`; `strict=true` fails instead of minting from an unrecognized IRI/name. |
| `add_annotation` | Add an annotation assertion to an entity. Value is a literal (optional `lang`/`datatype`) or an IRI (`value_iri`); optional `annotations` attach axiom annotations. Reports `new_entities`; honours `strict`. |
| `set_label` | Set (upsert) an entity's `rdfs:label`: removes the existing label(s) in the same language and adds the new one — relabel without hand-removing the old axiom (`rename_entity` changes the IRI, not the label). |
| `add_axiom` | Add a structured axiom (see axiom types below). Reports `new_entities`; `strict=true` fails instead of minting from an unrecognized IRI/name. |
| `remove_axiom` | Remove a structured axiom (same arguments as `add_axiom`). |
| `rename_entity` | Change an entity's IRI throughout the active ontology (rewrites every referencing axiom; renames all puns at the IRI). |
| `delete_entity` | Remove an entity and every axiom that references it from the active ontology (optionally narrowed by `entity_type`). |
| `set_ontology_id` | Set the active ontology's IRI and optional version IRI. |
| `set_prefix` | Register/update a prefix in the active ontology's prefix map (e.g. `iof-av` → its namespace) so CURIEs render, parse, and serialize. |
| `add_import` / `remove_import` | Add or remove an `owl:imports` declaration. `add_import` reports `resolved`; pass `document` (path/URL/IRI) to resolve the import by loading that document **without changing the active ontology**. |
| `add_ontology_annotation` / `remove_ontology_annotation` | Add or remove an ontology-level annotation (literal/typed/lang/IRI value, optional nested annotations). |

### Documents

| Tool | Description |
| --- | --- |
| `load_ontology` | Load an OWL document from a local path, a `file:` IRI, or an `http(s)` URL into the workspace as its own ontology and make it active (fetched off the UI thread; not undoable). A sibling `catalog-v001.xml` next to a local-file document (path **or** `file:` IRI) resolves its imports to local files offline, like Protégé's File ▸ Open. An import declaration matching the imported ontology's IRI/version links in-session; a bare document-URL declaration loads the document and is resolved on reopen through the catalog. Pass `keep_active=true` to load it (e.g. to resolve an import) **without** changing the active edit target. |
| `set_active_ontology` | Select which already-loaded ontology is the **active edit target**, by ontology IRI or version IRI (see `list_ontologies`). Loads/fetches nothing — switches focus, e.g. back to your module after `load_ontology` brought in an imported ontology. |
| `merge_ontology_document` | Load an OWL document from a local path or URL (GitHub blob URLs are converted to raw URLs) and copy its axioms, imports, ontology annotations, and optionally ontology ID into the active ontology in one bulk step. |
| `create_ontology` | Create a brand-new empty ontology in the workspace (given an `ontology_iri`, optional `version_iri`, and optional file `path`) and make it the active edit target — start a new module before adding imports/axioms. Not undoable. `set_ontology_id` re-ids an existing ontology; this mints a new one. |
| `write_catalog` | Generate/refresh an OASIS `catalog-v001.xml` next to the active ontology that maps each `owl:imports` **declaration IRI** (what Protégé resolves on reopen — it can differ from the ontology IRI, e.g. a BFO/cache document URL) and the resolved ontology's IRI/version to the local file it loaded from, so the module re-opens with imports resolved offline. `direct_imports_only` maps just the direct imports; unresolved / non-file imports are reported as skipped. Writes a file (not undoable). |

### Rules

| Tool | Description |
| --- | --- |
| `list_rules` | List SWRL rules (`swrl:Imp`) in the active ontology as structured body/head atoms plus rule annotations and a text rendering. Variable arguments are emitted as `?<absolute IRI>` and each named atom predicate carries a `predicate_iri`, so a listed rule round-trips through `add_rule` verbatim across ontologies. `include_imports` includes imported rules. |
| `add_rule` | Add a SWRL rule from structured body/head atoms (`class` / `object_property` / `data_property` / `same_as` / `different_from` / `builtin`). A `?`-prefixed argument is a rule variable (`?name` → `variable_namespace` + name, `?<IRI>` → that IRI exactly), so named variable IRIs (e.g. `iof-var:process1`) are preserved; an atom predicate may be given as a display `predicate` or a full `predicate_iri` (preferred when present). Optional `annotations` attach rule-level `rdfs:label`/comment/etc. The head must be non-empty (body may be empty). |
| `remove_rule` | Remove a SWRL rule by `index` (the rendering-sorted order `list_rules` returns) and/or `label` (its `rdfs:label`), or by passing the same structured `body`/`head`/`annotations` to remove that exact rule. |

### History & persistence

| Tool | Description |
| --- | --- |
| `undo_change` | Undo the last change on Protégé's shared undo stack; reports the net axiom-count change so the caller can see what the undo did. |
| `redo_change` | Redo the last undone change (also reports the net axiom-count change). |
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

## Prompts (guided workflows)

The server also exposes MCP **prompts** — reusable, guided workflows a user can pick in their MCP
client. Each expands to an instruction that drives the tools in a safe order (get context first,
preview before applying, confirm writes, verify with the reasoner).

| Prompt | Arguments | Workflow |
| --- | --- | --- |
| `audit_ontology` | — | Orient → `validate_ontology` → reasoner checks → drill into offenders → propose fixes (preview before applying). |
| `explain_class` | `class` | Entity context → axioms → reasoner-inferred relations → plain-language explanation. |
| `add_subclass_safely` | `child`, `parent` | Resolve both terms → `preview_changes` → apply `add_subclass_of` → re-check satisfiability. |
| `find_and_fix_unsatisfiable` | — | `run_reasoner` → `get_unsatisfiable_classes` → `get_explanations` → propose minimal fixes. |
| `model_domain` | `description` | Survey existing terms → propose classes/properties → preview → apply in small batches → validate. |

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
