# Changelog

All notable changes to **Protégé MCP** are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/). Each release's section is published verbatim as the body of
its [GitHub release](https://github.com/hakjuoh/protege-mcp/releases) by the release workflow.

## [0.3.2] - 2026-06-30

SPARQL support for the active ontology — author, validate, and run queries. **47 → 50 tools.**

### New tools
- **`sparql_query`** — run a **SPARQL 1.1** query (`SELECT` / `ASK` / `CONSTRUCT` / `DESCRIBE`) over the active ontology and its imports closure, using an embedded Apache Jena ARQ engine. Read-only: `UPDATE` and `SERVICE` are rejected, so a query never edits the model or reaches the network. The ontology's prefixes (plus rdf/rdfs/owl/xsd) are auto-prepended, and `limit` caps the rows/triples returned. By default it sees the **asserted** triples (like Protégé's SPARQL Query tab); set `include_inferred=true` to first materialise the active reasoner's inferences (run `run_reasoner` first).
- **`sparql_schema`** — discover the queryable vocabulary for *writing* a query: the prefix map (plus a ready-to-paste `PREFIX` block), classes, object/data properties (with their domains and ranges), individuals and datatypes — each with a CURIE and full IRI — plus example queries built from the ontology's own terms. Use `keyword` to focus on a sub-topic.
- **`sparql_validate`** — check a draft query *before* running it (parse-only, or `dry_run` for a small sample). Reports whether it parses, the query form and variables, whether `sparql_query` would accept it, and `unknown_terms` — IRIs used in the query (graph patterns, property paths, `VALUES`, the `CONSTRUCT` template, `DESCRIBE` targets) that are not declared in the ontology, i.e. likely typos or terms from another vocabulary.

### New prompt
- **`author_sparql_query`** — guided workflow that chains the above: discover the vocabulary → draft → validate → run → iterate.

### Notes
- Apache Jena ARQ is inlined into the bundle; `sparql_query` / `sparql_validate` snapshot the imports closure into a private throwaway ontology (never mutating the live model) and run off the EDT, so a query can neither edit the ontology nor reach the network.
- Requires a **Java 17+** JVM (unchanged).

Install: download `protege-mcp-0.3.2.jar` below, or use Protégé ▸ File ▸ Check for plugins.

## [0.3.1] - 2026-06-29

**Ontology Assistant attachments.** The in-Protégé chat input now accepts attachments:

- **Long pasted text** is compacted in the transcript as `[Pasted content #N: … chars]` while the full body still reaches the assistant (large bodies are buffered to a temp file and referenced by path, so no paste can overflow the command line).
- **Files & images** via the **Attach** button, drag/drop, or clipboard paste become `[Image #N]` / `[File #N: name]` placeholders. Codex receives images via native `--image`; Claude is granted read access via `--add-dir`.

**Privacy & robustness.** Each attached file/image is copied into its own owner-only temp folder and only that single-file copy is exposed to the CLI — never the rest of its containing folder — and the copies are deleted when the turn finishes. The one-time egress consent is re-versioned and reworded to name attachments/pasted content (shown once more). A placeholder edited away before Send is reported and not sent; the clipboard-image encode runs off the EDT with a generation guard so a reset mid-encode can't inject a stale attachment.

Tool count unchanged (47). Requires Java 17+. Install via **File ▸ Check for plugins**, or drop `protege-mcp-0.3.1.jar` into Protégé's `plugins/` folder.

## [0.3.0] - 2026-06-29

**In-Protégé chat assistant (Architecture Approach B).** A new **Ontology Assistant** tab and view let you converse with an assistant that reads and edits your live ontology — without leaving Protégé.

Rather than calling a model API directly, the chat **drives a coding-agent CLI you already have installed** — Claude Code (`claude`) or OpenAI Codex (`codex`) — pointed back at this plugin's own MCP server. So every edit flows through the same tool layer an external MCP client uses: it appears in the GUI, joins the **undo stack**, and obeys the read-only / confirm-each-write gates. **No API key is stored by Protégé** — each CLI uses your existing login.

### Highlights
- **Ontology Assistant tab + view** — a streaming chat transcript with Send/Stop, a live token/cost readout, and a server/egress status line. Try a read (*"What classes are in this ontology?"*) or an edit (*"Create a class FooBar under Thing with label 'Foo Bar'."*); **Edit ▸ Undo** reverts any edit.
- **Pick your provider** — **Use Claude** / **Use Codex** (only installed CLIs are shown); the model picker is populated from the active provider and is editable for any model your account supports (blank = the CLI's own default).
- **No API key, no new outbound socket from the plugin** — each CLI uses your existing login (Claude keychain/subscription; `codex login`). A one-time banner discloses that your prompts and the ontology content the assistant reads are sent to your model provider *via the CLI*.
- **Inherited safety** — edits go through the MCP server's gates, so read-only mode and the confirm-each-write modal apply unchanged and the chat cannot escalate past them; a **Confirm each edit** checkbox toggles confirmation live.
- **New Settings ▸ Ontology Assistant** — optional per-provider CLI path overrides (for when a Dock/Finder-launched Protégé lacks your shell `PATH`) and an egress-consent reset.

### Notes
- The **47 MCP tools are unchanged**; the chat reuses them over loopback HTTP. The MCP server starts automatically on the first chat message.
- Requires a **Java 17+** JVM (unchanged), plus at least one installed and logged-in CLI (`claude` or `codex`) to use the chat assistant.

Install: download `protege-mcp-0.3.0.jar` below, or use Protégé ▸ File ▸ Check for plugins.

## [0.2.2] - 2026-06-28

Closes the multi-module reconstruction gaps found by rebuilding the IOF ontology (iofoundry/ontology) through the tools alone. **41 → 47 tools.**

### New tools
- **Structured SWRL rule editing** — `list_rules` / `add_rule` / `remove_rule` read, add, and remove `swrl:Imp` axioms as structured body/head atoms (`class`, `object_property`, `data_property`, `same_as`, `different_from`, `builtin`). A `?`-prefixed argument is a rule variable (`?name` → `variable_namespace` + name, `?<IRI>` → that IRI exactly), so **named variable IRIs** like `iof-var:process1` reconstruct faithfully where a `?x` text syntax would lose them; rule-level annotations (rdfs:label/comment/…) ride the existing `annotations` operand. OWLAPI 4.5.29 ships no standalone SWRL parser, so the structured form is the round-trippable primitive.
- **`create_ontology`** — mint a new empty module in the workspace and make it the active edit target (pairs with `set_ontology_id`), so a multi-module ontology can be built from nothing.
- **`write_catalog`** — generate/refresh an OASIS `catalog-v001.xml` mapping the active ontology's imports (ontology + version IRIs) to their local files, so a reconstructed module re-opens in Protégé with imports resolved offline. Catalog files live outside the OWL axiom model, so no other tool can produce them.
- **`diff_ontologies`** — axiom-level semantic diff / round-trip check between two loaded ontologies, or the active ontology against a freshly-loaded document (without adding it to the workspace); `identical=true` means the reconstruction is axiom-for-axiom faithful.

### Notes
- OWLAPI stays at 4.5.29 (provided by Protégé 5.6.6 and shared with the live `OWLModelManager`); these tools need nothing newer.
- Requires a **Java 17+** JVM (unchanged).

Install: download `protege-mcp-0.2.2.jar` below, or use Protégé ▸ File ▸ Check for plugins.

## [0.2.1] - 2026-06-28

## protege-mcp 0.2.1 — tool-driven construction ergonomics

Driving a real BFO/IOF ontology (IOF Biopharma/Agent) entirely through the tools surfaced the friction points of natural-language-driven authoring. This release closes them. Additive and backward-compatible; **37 → 41 tools**.

- **`set_active_ontology`** — switch which loaded ontology your edits target. `load_ontology keep_active=true` and `add_import document=…` now resolve imports **without** stealing the active ontology (the #1 wall in the reconstruction).
- **`apply_changes`** — apply a previewed `operations[]` batch in **one call** and **one undo entry** (a single `undo_change` reverts the whole batch, like `create_class`). Reports per-operation results, the new entities each add introduces, and a summary. `strict=true` skips any add that would mint a brand-new entity from an unrecognized IRI/name.
- **`set_label`** — upsert an `rdfs:label` (removes the same-language label, adds the new one). **`set_prefix`** — register/update a prefix in the active ontology's format.
- **Silent-minting signal** — every write tool (`add_axiom`, `add_subclass_of`, `add_annotation`, `apply_changes`) now reports the entities a change introduces, with an opt-in `strict` flag that refuses to fabricate one from a typo'd IRI/name.
- **`create_class` / `create_entity`** gain `namespace` (mint the IRI in a shared namespace distinct from the ontology IRI), plus `label` / `label_lang` / `no_label` for language-tagged or suppressed labels — no more stray untagged `xsd:string` labels.
- **Manchester `<IRI>` operands** now resolve inside compound class expressions (e.g. `<…/Identifier> and (…)`).
- **Richer reads & checks** — `validate_ontology with_reasoner=true` adds a consistency / unsatisfiable-class verdict; `get_entity_context` neighbours are structured `{iri, display, type}`; `undo_change` / `redo_change` report the axiom delta.

Requires Java 17. Install via Protégé ▸ File ▸ Check for plugins, or drop `protege-mcp-0.2.1.jar` into the Protégé `plugins/` directory and restart.

## [0.2.0] - 2026-06-27

## protege-mcp 0.2.0 — natural-language layer

- **Structured JSON output** from every tool (mirrored as text) so an LLM client gets machine-readable results instead of prose.
- **Orientation & safety tools**: `get_ontology_context`, `get_entity_context`, `preview_changes` (diff an edit before applying), and `validate_ontology` (modelling-quality audit).
- **Guided MCP prompts**: `audit_ontology`, `explain_class`, `add_subclass_safely`, `find_and_fix_unsatisfiable`, `model_domain`.
- **Import-aware `validate_ontology`**: the per-entity quality checks audit only the terms the active ontology is responsible for, so imported BFO/IOF terms are no longer false-flagged for label/definition/domain/range that lives upstream. Set `include_imports=true` to audit the whole imports closure.

Requires Java 17. Install via Protégé ▸ File ▸ Check for plugins, or drop `protege-mcp-0.2.0.jar` into the Protégé `plugins/` directory and restart.

## [0.1.2] - 2026-06-27

33 tools (was 26 in 0.1.1).

**load_ontology** rewritten to fetch/parse off the UI thread and wire the result in with Protégé's own copy-ontology/activate path (no modal load dialogs; a slow remote fetch no longer freezes Protégé). Adds `connection_timeout_ms`; not undoable.

**New tools**
- `rename_entity` / `delete_entity` — rewrite or remove an entity (and its referencing axioms) across the active ontology; undoable.
- `list_reasoners` / `set_reasoner` — list installed reasoner plugins and choose the active one.
- `execute_dl_query` — Manchester class expression → reasoner equivalent / sub / super / instances (the DL Query workbench).
- `get_explanations` — real justifications (minimal axiom sets) behind an entailment, computed in isolation from the live model.

Install via **File ▸ Check for plugins** (the registry advertises 0.1.2), or download the jar below into `~/.Protege/plugins` and restart Protégé on a Java 17+ JVM.

## [0.1.1] - 2026-06-27

Complete the granular (incremental) authoring surface so a rich document like IOF Core.rdf can be reconstructed by hand, plus merge/read robustness fixes. **26 tools total.**

### Authoring surface (`add_axiom`: 22 → 38 axiom types)
- `declaration`, `annotation_assertion`, `sub_annotation_property_of`, `annotation_property_domain`/`range`, `same_individual`/`different_individuals`, `negative_object`/`data_property_assertion`, `equivalent`/`disjoint` object & data properties, `disjoint_union`, `has_key`, `datatype_definition`
- Optional `annotations` operand on every axiom (reified `owl:Axiom`)
- `add_annotation`: typed and IRI-valued annotation values
- New ontology-header tools: `set_ontology_id`, `add_import`/`remove_import`, `add_ontology_annotation`/`remove_ontology_annotation`
- Data ranges accept Manchester syntax, e.g. `xsd:integer[>= 0]`, `{1, 2, 3}`

### Fixes
- `merge_ontology_document`: ontology-id collision guard, longer apply timeout, Windows path routing, unresolved-import warning, clearer destructive `replace_active` confirmation
- Read tools: clamp negative `limit` and report the true remainder

### Install / update
Drop `protege-mcp-0.1.1.jar` into Protégé's plugins directory, or use **File ▸ Check for plugins** (requires Java 17+).

## [0.1.0] - 2026-06-26

An **MCP (Model Context Protocol) server** for **Protégé Desktop**, packaged as a single OSGi plugin. It exposes the **live, active ontology** of a running Protégé to MCP clients over a localhost HTTP endpoint; reads and edits flow through Protégé's shared `OWLModelManager`, so they appear in the GUI immediately and join the **undo stack**.

### Requirements
Protégé must run on a **Java 17+** JVM — the bundle is Java 17 bytecode and the OSGi manifest declares `Require-Capability: osgi.ee … JavaSE 17`.

### Install
- **Manual (Path A):** download `protege-mcp-0.1.0.jar` below, drop it into Protégé's `plugins/` directory, and restart Protégé on a Java 17+ JVM. See the [README](https://github.com/hakjuoh/protege-mcp/blob/main/README.md#install).
- **Check for plugins (Path B):** in Protégé, set **Settings ▸ Plugins ▸ Plugin registry** to `https://raw.githubusercontent.com/hakjuoh/protege-mcp/main/protege-mcp.repository`, then run **File ▸ Check for plugins** and install **Protege MCP Server**. See [docs/check-for-plugins.md](https://github.com/hakjuoh/protege-mcp/blob/main/docs/check-for-plugins.md).
