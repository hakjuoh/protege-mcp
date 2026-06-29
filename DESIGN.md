# Protégé × MCP Integration — Design

> Design for adding **MCP (Model Context Protocol) support** to Protégé Desktop as a plugin.
> **Adopted: Architecture Approach A — in-Protégé MCP server.** External LLM clients (Claude Code/Desktop,
> Codex CLI, VS Code MCP, other IDE extensions) read and edit the user's **live, open ontology** directly;
> edits appear in the GUI immediately and join the user's **undo** stack. Architecture Approach A is **built and shipping**
> (first delivered at `0.1.0`; `0.2.0` adds a natural-language layer — JSON tool output, context/validation/preview
> tools, and MCP prompts — see §5).
>
> See also: [`README.md`](README.md) for install and usage, and
> [`docs/check-for-plugins.md`](docs/check-for-plugins.md) for in-Protégé distribution.

> **A note on two axes named "A/B/C".** This document uses **"Architecture Approach A/B/C"** for the
> *internal shape* of the plugin (server vs. in-app chat vs. standalone). That is a different axis from the
> **"Distribution Path A/B"** used in the README and `docs/check-for-plugins.md`, which describes *how the
> built jar reaches the user* (manual copy vs. "Check for plugins"). To avoid confusion, the full noun
> phrase is always written out — a bare "Approach B" or "Path B" never appears.

Analysis source repository: `../protege` = `github.com/protegeproject/protege` (Protégé 5.6.x).
Platform facts: **OSGi (Apache Felix) / Equinox extension registry / Java 17+ runtime / OWLAPI 4.5.29 / Guava 18.0**.
Every load-bearing claim is grounded in real platform source (`file:line` below) and cross-checked against the
recon notes in [`.recon/`](.recon/).

Provided platform libraries (supplied by Protégé, not embedded): `protege-editor-core` 5.6.6,
`protege-editor-owl` 5.6.6, `owlapi-osgidistribution` 4.5.29, `guava` 18.0, `slf4j-api` 1.7.36.

---

## 1. Goals and Non-Goals

**Goals (Architecture Approach A — delivered)**
- An **in-process MCP server** plugin that exposes the active ontology as MCP tools while Protégé is running.
- LLM edits are **reflected in the GUI immediately** and join the **shared undo stack** (treated exactly like
  manual edits).
- Support **multiple MCP clients**: Claude Code, Claude Desktop, Codex CLI, VS Code (MCP), other IDE extensions.
- Ship as a **single OSGi bundle** dropped into `plugins/`, with no changes to Protégé core.

**Non-goals (this track)**
- An in-app LLM chat UI — this is **Architecture Approach B** (in-app Claude chat). It is now a first-class
  design (§9), no longer a one-line future note.
- Headless / batch file editing — this is **Architecture Approach C** (a separate complement track, §10).
- Multi-user remote access — the server binds **`127.0.0.1`** only
  (`EmbeddedHttpServer`: `LOOPBACK = "127.0.0.1"`, `connector.setHost(LOOPBACK)`).

> *"Architecture Approach" describes the plugin's internal shape; it is distinct from the
> README/Check-for-plugins "Distribution Path A/B", which describes how the built jar is delivered (§11).*

---

## 2. Architecture Decision

Three candidates were designed and validated; **Architecture Approach A was adopted and is now built.**

| | **A. In-Protégé MCP server** ✅ built | B. In-Protégé chat (MCP client) | C. Standalone MCP server |
|---|---|---|---|
| Form | Protégé plugin (OSGi bundle) | Plugin + LLM chat panel | Separate process (shaded JAR) |
| Edit target | **Live active ontology** | Live + optional external MCP | Ontology **files** on disk |
| Transport | localhost HTTP (Streamable) | outbound only (to the LLM) | stdio |
| GUI reflect / Undo | ✅ (core value) | ✅ (reuses A's tool layer) | ❌ (file snapshots) |
| External clients | as-is | not required (in-app) | as-is |
| Auth | OAuth 2.1 + static bearer | LLM API key (outbound) | n/a (stdio child) |
| Status | **shipped** | designed (§9, v2) | designed (§10, complement) |

**Why A.** The request is "add MCP to the Protégé app," and the largest value is an LLM acting on the
**model the user already has open** — the shared `OWLModelManager`, renderers, reasoner, and undo stack. Only
Architecture Approach A delivers this cleanly: Architecture Approach C edits stale file snapshots; Architecture Approach B carries an
entire LLM-chat product the original request did not ask for (it is now planned as a v2 that *reuses* A's tool
layer, §9).

**Feasibility verdict: built.** Three real costs were confronted and solved, addressed head-on in this document —
(1) OSGi / Jackson packaging (§7), (2) servlet hosting (§6), (3) Swing EDT threading (§8).

---

## 3. Protégé Plugin Structure (evidence)

- **Plugin = OSGi bundle JAR.** Built with `maven-bundle-plugin` (`org.apache.felix:maven-bundle-plugin:5.1.9`,
  `extensions=true`), `packaging=bundle`, dropped into `<protege>/plugins` or `~/.Protege/plugins`.
- At runtime `ProtegeApplication.isPlugin` treats a bundle as a plugin **only when its install path string
  contains `"plugin"`** (`protege-editor-core/.../ProtegeApplication.java:211-241`).
- **`Bundle-SymbolicName ...;singleton:=true` is mandatory** — otherwise the Equinox registry **silently ignores
  `plugin.xml`** (`pluginSanityCheck` only warns) (`ProtegeApplication.java:211-241`).
- **`plugin.xml` lives at the JAR root.** As built, it declares three extensions with bare ids:
  `EditorKitHook` (`McpServerHook`, `editorKitId=OWLEditorKit`), `ViewComponent` (`McpServerView`, label
  `"MCP Server"`), and `preferencespanel` (`org.protege.mcp.preferences` → `McpPreferencesPanel`, label `"MCP"`).
  The framework prepends the bundle namespace at runtime to form fully-qualified ids
  (`protege-editor-core/src/main/resources/plugin.xml:4-34`).
- Extension points used: `EditorKitHook` (per-EditorKit lifecycle: `initialise`/`dispose`),
  `ViewComponent` (Swing view), `preferencespanel` (settings panel).
- **Lifecycle driver.** `Initializers.loadEditorKitHooks(editorKit)` instantiates each hook
  (`newInstance()` → `initialise()`) and registers it under a unique id; cleanup walks
  `AbstractEditorKit.dispose()` over the registered values. A unique non-null id avoids key collisions that
  would orphan a hook and skip its `dispose()` (`Initializers.java:18-31`, `AbstractEditorKit.java:36,50-60`).
- ⚠️ **Do not assume `initialise()`/`dispose()` run on the EDT.** `loadEditorKitHooks` is called from the
  `OWLEditorKit` constructor (`OWLEditorKit.java:94`). Only the **command-line-URI boot path** wraps it in
  `SwingUtilities.invokeLater` (`ProtegeApplication.java:521`). The ordinary no-file boot path runs
  `createAndSetupDefaultEditorKit()` (`ProtegeApplication.java:480-495`) directly at `:487` with no
  `invokeLater`, so `initialise()` executes **synchronously on the OSGi framework thread**
  (`reallyStart():108`, `startApplication():431`). The hook must marshal to the EDT itself when it needs Swing,
  and must **not** touch model/UI in `initialise()` — the workspace is not yet initialised at `:94`
  (`workspace.initialise()` is `:109`).
- **Multi-window:** each window has its own `EditorKit` and therefore its own hook instance.

> *Verified against [`.recon/04-editorkit.md`](.recon/04-editorkit.md). The recon note records `initialise`/
> `dispose` on the EDT; the sharper finding above — that the no-arg boot path runs them on the OSGi framework
> thread — comes from the `ProtegeApplication` boot path and is the one the design relies on.*

---

## 4. MCP Server Design

### 4.1 Components (as built)

A single Maven module `protege-mcp` (`packaging=bundle`). `plugin.xml` registers three extensions
(`McpServerHook`, `McpServerView`, `McpPreferencesPanel`). The server is composed of:

1. **`McpServerHook`** (`EditorKitHook`) — per-window lifecycle owner. `initialise()` builds an
   `OntologyAccess` and a `McpServerController`, registers the controller in `McpServerRegistry` keyed by the
   `EditorKit`, and (if auto-start is on) calls `McpServerRegistry.startIfNoOwner(...)` (daemon thread
   `protege-mcp-start`) — a new window **defers** to whichever controller already owns the single process-wide
   port rather than fighting over the bind (a bind failure is non-fatal: logged and surfaced in the view). It
   touches no UI/model (the workspace is not yet initialised; it may run on the EDT or the OSGi framework
   thread). `dispose()` unregisters and stops the controller **off-EDT** on a daemon thread with a bounded 3s
   join (so closing a window never blocks); if this window held the port, it then hands it to another open
   window via `McpServerRegistry.promoteSuccessor()`.

2. **`McpServerController`** — owns lifecycle and runtime state for one window. `start()/stop()/restart()` are
   `synchronized` and **must run off-EDT**. `start()` pins the thread-context classloader to the bundle
   classloader (needed for the MCP SDK `ServiceLoader`, the networknt json-schema-validator meta-schema
   resources, and Jetty), loads a fresh `McpConfig` snapshot, builds a `ToolContext` + `ToolCatalog`, builds a
   `McpServerManager`, constructs the `OAuthStore` (wired to `McpConfig` load/save hooks), then on the
   `EmbeddedHttpServer` registers: `AccessTokenFilter` at `/mcp/*`, the MCP transport servlet at `/mcp/*`
   (async), `OAuthMetadataServlet` at `/.well-known/*`, `OAuthServlet` at `/oauth/*`, and binds the port. It
   exposes `regenerateToken`, `listClients`, `revokeClient`, `getStaticTokenLastSeen`, live `isReadOnly()` /
   `isConfirmWrites()` reads, `getEndpointUrl`, and `getLastError`.

3. **`McpServerRegistry`** — a static `ConcurrentHashMap<EditorKit, McpServerController>` so a window's view and
   preferences panel can reach that window's controller. It also **elects a single owner of the one
   process-wide port** across windows: `startIfNoOwner(...)` lets a new window defer to the current owner, and
   `promoteSuccessor()` hands the port to another open window when the owner stops — so overlapping `EditorKit`
   lifecycles (e.g. Protégé swapping EditorKits to open an ontology) never tear the server down while a window
   is still open. The election logic is factored into package-private, unit-testable overloads.

4. **`McpServerManager`** — builds and owns the MCP sync server and the Streamable-HTTP transport
   (`SERVER_NAME=protege-mcp`, `SERVER_VERSION=0.2.0`, endpoint `/mcp`). It constructs the `ObjectMapper`,
   `JacksonMcpJsonMapper`, and `DefaultJsonSchemaValidator` **explicitly** (avoiding a `ServiceLoader` failure
   under OSGi), then:
   ```java
   McpServer.sync(transport)
            .serverInfo("protege-mcp", "0.2.0")
            .capabilities(ServerCapabilities.builder().tools(false).prompts(false).build())  // tools + prompts (both listChanged=false); no resources
            .immediateExecution(true)     // run handlers on the transport (HTTP) thread; the plugin marshals to the EDT itself
            .validateToolInputs(false)
            .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))  // sync server still validates tool SCHEMAS even with validateToolInputs(false)
            .instructions(...)
            .tools(allSpecs)              // the §5 tool specs
            .prompts(Prompts.all())       // the §5 guided-workflow prompts
            .build();
   ```
   `close()` calls `closeGracefully()` then `close()`.

5. **`EmbeddedHttpServer`** — a minimal embedded **Jetty 12 ee10** host (`jetty-ee10-servlet`) bound to
   `127.0.0.1` only, a single `ServletContextHandler` at context path `/`. Filters are registered with
   `REQUEST` + `ASYNC` dispatch and `asyncSupported` (Streamable-HTTP uses `request.startAsync()` for SSE).
   `start(port)` with port `0` picks a free ephemeral port and returns `connector.getLocalPort()`;
   `stopTimeout` is 2000ms; `stop()` is best-effort and must not run on the EDT.

6. **`OntologyAccess`** (the EDT choke point) — **every** model read/write passes through one method:
   ```text
   compute(Function<OWLModelManager,T>):
     if SwingUtilities.isEventDispatchThread()  -> run the function inline      // guards against invokeAndWait self-deadlock
     else                                       -> invokeLater + CountDownLatch (30s bounded wait)
   ```
   A `cancelled` flag stops a queued task from mutating the model after the caller has timed out; a timeout or
   interrupt surfaces as `McpAccessException` (which tool handlers turn into an MCP error result). OWLAPI
   objects and Protégé caches/renderers are not thread-safe, so MCP edits are serialized with UI edits.

7. **`EmbeddedClassificationWaiter`** — drives `classifyAsynchronously(EnumSet.allOf(InferenceType))` (which
   returns immediately) and blocks **off-EDT** on a `CountDownLatch` counted down by the EDT-delivered
   `ONTOLOGY_CLASSIFIED` event, reading `getReasonerStatus()` **once** after the latch resolves. It never blocks
   the EDT, and guards against a missing reasoner via `ReasonerStatus`.

8. **OAuth components** — `AccessTokenFilter`, `OAuthServlet`, `OAuthMetadataServlet`, `OAuthStore`,
   `OAuthSupport`, `PkceUtil` (detailed in §8 Security).

9. **`McpServerView`** (`AbstractOWLViewComponent`, label `"MCP Server"`) — shows running status / last error,
   the endpoint URL, the masked/visible static token, the mode (read-only / confirm), Start/Stop (off-EDT
   worker threads), Regenerate token, Copy URL, and **Copy connect command**
   (`claude mcp add --transport http protege <url>`). It hosts the **Connected-clients** `JTable`
   (Client, Client ID, Registered, Last seen, Active tokens, Expires) with single-selection **Revoke**, a
   "Static fallback token last used" label, and a 1500ms refresh timer.

10. **`McpPreferencesPanel`** (`PreferencesPanel`, label `"MCP"`) — port spinner (1–65535) + ephemeral checkbox,
    auto-start, read-only mode, confirm-each-write. Port and auto-start take effect on restart; read-only and
    confirm apply live. Persisted via `McpConfig.prefs()`.

> *Builder/method names verified against [`.recon/01-mcp-server.md`](.recon/01-mcp-server.md),
> [`.recon/02-mcp-transport.md`](.recon/02-mcp-transport.md), and
> [`.recon/03-mcp-schema.md`](.recon/03-mcp-schema.md): `immediateExecution(boolean)`, the inline single-tool
> method is `toolCall(...)` not a builder `addTool`, the transport providers are `jakarta` `HttpServlet`s (not
> `javax`), and `Tool.inputSchema` is a `Map<String,Object>`.*

### 4.2 Data flow

```
window open → OWLEditorKit created (EDT or OSGi framework thread, boot-path dependent)
            → loadEditorKitHooks → McpServerHook.initialise()
            → builds controller, registers, auto-starts via owner election (defers to the current port owner)
            → EmbeddedHttpServer bound on 127.0.0.1:<port>
external client → 127.0.0.1:<port>/mcp → AccessTokenFilter (OAuth access token or static token)
            → Streamable-HTTP transport servlet
            → MCP handler runs on a Jetty thread (immediateExecution(true))
            → OntologyAccess marshals to the EDT → OWLModelManager.applyChange(s)
            → ontologiesChanged listener → HistoryManager.logChanges (shared undo) → GUI refresh
            → handler re-queries resulting state (ChangeListMinimizer may drop/merge) → CallToolResult
window close → McpServerHook.dispose() → unregister + stop off-EDT (bounded join); port handed off to another window
```

The tool layer is `ToolCatalog` + `ToolSpecs` + `ToolContext` + `ReadTools` / `WriteTools` / `ReasonerTools`.

---

## 5. MCP Tool Catalog (47 tools + 5 prompts)

Forty-seven tools — 7 read, 2 context, 14 edit/history/persistence (incl. `preview_changes`,
`apply_changes`, `set_label`), 6 ontology-header (incl. `set_prefix`), 5 document (incl.
`set_active_ontology`, `create_ontology`, `write_catalog`), 3 rule (`list_rules`/`add_rule`/`remove_rule`),
8 reasoner, and 2 validation (incl. `diff_ontologies`) — each defined by a `name`, a `description`, and a
JSON-schema `inputSchema` (a `Map<String,Object>`). Entities are referenced by IRI or display name.
**Every tool returns a structured JSON object** (set as MCP `structuredContent` and mirrored as a
serialized JSON text block via the `Tools.json()/ok()/error()` helpers), so clients can compose results
programmatically and a human still sees readable JSON. The server also registers **5 MCP prompts**
(guided workflows) — see the end of this section.

New in `0.2.0` (the natural-language layer): `get_ontology_context` / `get_entity_context`
(model-friendly orientation + entity cards), `preview_changes` (a non-mutating dry-run / diff),
`validate_ontology` (a modelling-quality audit complementing the reasoner), the full JSON-output
migration, and the MCP prompts.

New in `0.2.1` (tool-driven-construction ergonomics, found by reconstructing IOF Biopharma through the
tools — see [`docs/biopharma-recon-0.2.1.md`](docs/biopharma-recon-0.2.1.md)): `set_active_ontology`
(switch the active edit target) and `load_ontology`/`add_import` options that resolve imports without
stealing it; `apply_changes` (apply a previewed batch in one call); write tools now report minted
`new_entities` and take `strict` (refuse to mint from a typo'd IRI); `create_*` gain `namespace` +
language-tagged labels; `set_label` (relabel upsert); `set_prefix` (prefix-map management); the
Manchester parser accepts full `<IRI>` operands inside compound expressions; `get_entity_context`
returns structured `{iri, …}` neighbours; `validate_ontology` takes `with_reasoner`; and `undo`/`redo`
report the axiom-count delta.

New in `0.2.2` (closing the multi-module reconstruction gaps found by rebuilding the IOF ontology
through the tools): structured SWRL rule editing — `list_rules` / `add_rule` / `remove_rule` read,
add, and remove `swrl:Imp` axioms as body/head atoms, preserving named rule-variable IRIs (e.g.
`iof-var:process1`) and rule-level annotations that the conventional `?x` text syntax cannot
round-trip; `create_ontology` mints a new empty module in the workspace (paired with the existing
`set_ontology_id`); `write_catalog` generates the OASIS `catalog-v001.xml` that maps a module's
imports to their local files (a file outside the OWL axiom model, so no other tool can produce it);
and `diff_ontologies` performs an axiom-level semantic diff / round-trip check between two loaded
ontologies, or the active ontology against a freshly-loaded document.

| Tool | Mapping / notes |
|---|---|
| `list_ontologies` | `OWLModelManager.getOntologies()` / `getActiveOntologies()` + `getActiveOntology()`; marks the active one |
| `get_active_ontology` | `getActiveOntology().getOntologyID()`, axiom/logical-axiom counts, imports. ⚠️ `isActiveOntologyMutable()` is **always true**, so write protection is a plugin-side setting |
| `summarize_ontology` | signature, annotation/import, and axiom-type counts over the active ontology, optionally including imports |
| `list_classes` | `getActiveOntology().getClassesInSignature()` + `OWLModelManager.getRendering(obj)` |
| `search_entities` | `getOWLEntityFinder().getMatchingOWLClasses(...)` and property/individual/datatype variants. A plain fragment is wrapped `*frag*` (the finder otherwise misses substrings); a blank/wildcard-only query falls back to `getActiveOntology().get…InSignature()` (the finder returns nothing for a bare `*`) |
| `get_entity` | `OWLEntityFinder.getEntities(IRI)` / `getOWLEntity(...)`; returns type(s), IRI, rendering |
| `get_axioms_for_entity` | `getReferencingAxioms(entity)` over the active ontology, or the whole `getImportsClosure()` when `include_imports=true` (reads imported terms' domain/range/restrictions) + rendering |
| `create_class` | `getOWLEntityFactory().createOWLClass(short, baseIRI)` → `OWLEntityCreationSet` → `applyChanges(set.getOntologyChanges())` |
| `create_entity` | `OWLEntityFactory.createOWLEntity(type, short, baseIRI)` → creation set → `applyChanges` |
| `add_subclass_of` | `getOWLDataFactory().getOWLSubClassOfAxiom(child, parent)` → `new AddAxiom(...)` → `applyChange` |
| `add_annotation` | `getOWLAnnotationAssertionAxiom(prop, subject, value)` → `AddAxiom` → `applyChange` |
| `add_axiom` | structured axiom builder (`Axioms`) → `AddAxiom` → re-query after apply |
| `remove_axiom` | `new RemoveAxiom(activeOntology, axiom)` → `applyChange` |
| `rename_entity` | `OWLEntityRenamer.changeIRI(oldIRI, newIRI)` over the active ontology → `applyChanges` (undoable; renames all puns) |
| `delete_entity` | `OWLEntityRemover` collects `RemoveAxiom`s for the entity (and puns) over the active ontology → `applyChanges` |
| `set_ontology_id` | `SetOntologyID` with collision guard against already-loaded ontologies |
| `add_import` / `remove_import` | `AddImport` / `RemoveImport` over the active ontology |
| `add_ontology_annotation` / `remove_ontology_annotation` | `AddOntologyAnnotation` / `RemoveOntologyAnnotation` over the active ontology |
| `load_ontology` | parse off-EDT into a throwaway **concurrent** manager (explicit connection timeout, SILENT imports; for a local-file source — plain path **or** `file:` IRI, via the shared `localFile` helper — a sibling `catalog-v001.xml` is registered as an `XMLCatalogIRIMapper` so imports resolve to local files offline; the same `normalizeSource`/`addFolderCatalogMapper`/`documentSource` helpers back `merge_ontology_document` and `diff_ontologies`' `right_document`), then on the EDT `copyOntology(MOVE)` the closure into Protégé's manager + `setActiveOntology` + fire `ONTOLOGY_LOADED` (replicates `OntologyLoader` minus its modal UI; not undoable) |
| `merge_ontology_document` | load with a temporary OWLAPI manager, then copy axioms/imports/ontology annotations into the active ontology |
| `create_ontology` | `OWLModelManager.createNewOntology(id, physicalURI)` (collision guard; sets active + fires `ONTOLOGY_CREATED`; optional `keep_active`; not undoable) |
| `write_catalog` | build an `org.protege.xmlcatalog.XMLCatalog` of `UriEntry`s mapping each `owl:imports` declaration IRI (and the resolved ontology's IRI/version) to its local file (relativized via `CatalogUtilities.relativize`), then `CatalogUtilities.save(catalog, catalog-v001.xml)` — a filesystem write, not undoable |
| `list_rules` / `add_rule` / `remove_rule` | `getAxioms(AxiomType.SWRL_RULE)`; build `SWRLRule`s from structured body/head atoms via `getSWRL*` factory methods (`?name`/`?<IRI>` → `getSWRLVariable(IRI)` preserves named variable IRIs); rule annotations via `getSWRLRule(body, head, annotations)`; remove via `RemoveAxiom` |
| `undo_change` / `redo_change` | `getHistoryManager().undo()/redo()` (shared with the GUI) |
| `save_ontology` | `OWLModelManager.save()` |
| `list_reasoners` / `set_reasoner` | `OWLReasonerManager.getInstalledReasonerFactories()` / `setCurrentReasonerFactoryId(id)` |
| `run_reasoner` | `classifyAsynchronously(...)` then `EmbeddedClassificationWaiter` (listener + latch, §4.1 item 7) |
| `get_unsatisfiable_classes` | `getUnsatisfiableClasses()` returns a `Node<OWLClass>`; reported via `getEntitiesMinusBottom()` |
| `get_inferred_superclasses` | superclasses / subclasses / equivalent / types / instances |
| `execute_dl_query` | resolve a Manchester class expression, then `reasoner.getEquivalentClasses/getSubClasses/getSuperClasses/getInstances` (DL Query workbench) |
| `explain_entailment` | `reasoner.isEntailed(axiom)` for a structured axiom |
| `get_explanations` | `com.clarkparsia.owlapi.explanation.DefaultExplanationGenerator.getExplanations(axiom, max)` — minimal justifications, using the selected reasoner's factory |
| `diff_ontologies` | pure set difference over `getAxioms()` / `getLogicalAxioms()` (optionally imports closures) of two loaded ontologies, or the active ontology vs. a document loaded into a throwaway manager; `identical=true` ⇔ both sides empty (a faithful round-trip) |

- **Ontology edits go through `OWLModelManager.applyChange` (singular, the majority) and `applyChanges`
  (plural, for `create_class` / `create_entity` / `rename_entity` / `delete_entity` / document merge).** Both
  delegate to the same path, so the GUI and undo outcome are identical. Mutating tools are gated by the **live
  read-only switch** and an optional **modal write-confirmation dialog** (the confirm runs on the EDT **outside**
  the bounded `OntologyAccess.compute`, so a slow human click cannot trip the 30s timeout), and edit tools
  re-query the resulting state because `ChangeListMinimizer` may drop/merge changes. Document load/save use
  Protégé's own load/save APIs under the same gates: `merge_ontology_document` and `load_ontology` fetch the
  document **off the EDT** (explicit connection timeout, SILENT missing-import handling) and only do the cheap
  model wiring on the EDT, so a slow remote fetch never freezes the UI and no Protégé load dialog is raised by a
  non-interactive MCP call. A `load_ontology` is not an `applyChange`, so it is **not** on the undo stack.
- **`Axioms`** (used by `add_axiom` / `remove_axiom` / `explain_entailment` / `get_explanations`) supports the
  full structured `axiom_type` surface (see the README catalog).
- The server registers **tools and prompts** (no MCP `resources` capability). The five prompts
  (`audit_ontology`, `explain_class`, `add_subclass_safely`, `find_and_fix_unsatisfiable`,
  `model_domain`) are pure templates (`tools/Prompts.java`): each expands to a single user message that
  drives the tools in a safe order (orient → preview → confirm/apply → verify). They touch no model
  state and run on the transport thread.
- **New context/validation/preview tools (`0.2.0`).** `get_ontology_context` (active-ontology
  orientation: counts, asserted roots, sampled properties, reasoner state, prefixes) and
  `get_entity_context` (a one-call "entity card": annotations, deprecation, and the asserted
  neighbourhood — super/sub/equivalent/disjoint, domain/range, super/sub properties, inverses,
  characteristics, instances, property assertions) live in `tools/ContextTools.java`.
  `tools/ValidationTools.java` runs a modelling-quality audit (missing/duplicate labels, missing
  definitions, deprecated-but-used, undeclared entities, no domain/range, self-subclassing, subclass
  cycles via iterative Tarjan SCC, isolated classes); its `analyze(Set<OWLOntology>)` core is a pure
  function unit-tested on a hand-built ontology. `tools/PreviewTools.java` (`preview_changes`) is a
  non-mutating dry-run that reuses the `Axioms` builder to report each operation's rendering,
  already-present flag, and the entities an add would introduce — without applying anything.

> *OWL-API mappings verified against [`.recon/05-modelmanager.md`](.recon/05-modelmanager.md); reasoner
> behavior against [`.recon/07-reasoner.md`](.recon/07-reasoner.md) (`classifyAsynchronously(Set<InferenceType>)`
> returns immediately; `getUnsatisfiableClasses()` returns a `Node<OWLClass>`, not a `NodeSet`).*

---

## 6. Transport and Client Connection

### 6.1 Why localhost HTTP, not stdio
stdio assumes the client spawns the server as a child process, which cannot apply to an **already-running GUI**.
The server must **listen** on a port and let clients connect independently → **Streamable HTTP**
(`HttpServletStreamableServerTransportProvider`, endpoint `/mcp`) bound to `127.0.0.1`. No legacy `/sse`
transport is registered.

### 6.2 Servlet hosting (decided)
The MCP transport is a `jakarta.servlet` `HttpServlet`, but the platform ships no servlet container. The
decision was made and implemented: **embedded Jetty 12.0.18 ee10** (`org.eclipse.jetty.ee10:jetty-ee10-servlet`,
Jakarta Servlet 6), with `jakarta.servlet-api:6.1.0` forced and embedded. A `com.sun.net.httpserver` +
hand-written `jakarta.servlet` adapter was **considered and rejected** for Jetty 12 ee10. `EmbeddedHttpServer`
is a concrete `Server` / `ServerConnector` / `ServletContextHandler`.

### 6.3 Per-client connection (multiple clients)
The server is standard MCP-over-HTTP, so any MCP client that supports HTTP can connect; stdio-only clients use
the **`npx mcp-remote`** bridge (which needs Node/`npx`). **OAuth is the recommended auth mode** — the 401
challenge on `/mcp` launches the browser flow; the **static bearer token** remains a manual fallback. The
`McpServerView` shows the bound URL/port/token for copy-paste, and a ready-to-paste connect command.

- **Claude Code** (HTTP native, OAuth):
  ```bash
  claude mcp add --transport http protege http://127.0.0.1:8123/mcp
  # or, with the static token instead of OAuth:
  claude mcp add --transport http protege http://127.0.0.1:8123/mcp \
    --header "Authorization: Bearer <TOKEN>"
  ```
- **VS Code (MCP)** — `.vscode/mcp.json`:
  ```json
  { "servers": { "protege": { "type": "http", "url": "http://127.0.0.1:8123/mcp",
    "headers": { "Authorization": "Bearer <TOKEN>" } } } }
  ```
- **Codex CLI** — HTTP directly, or the `mcp-remote` bridge (stdio):
  ```toml
  [mcp_servers.protege]
  command = "npx"
  args = ["mcp-remote", "http://127.0.0.1:8123/mcp", "--header", "Authorization:Bearer ${PROTEGE_MCP_TOKEN}"]
  ```
- **Claude Desktop** (stdio-only → `mcp-remote` bridge) — `claude_desktop_config.json`:
  ```json
  { "mcpServers": { "protege": {
    "command": "npx",
    "args": ["mcp-remote", "http://127.0.0.1:8123/mcp", "--header", "Authorization:Bearer ${PROTEGE_MCP_TOKEN}"],
    "env": { "PROTEGE_MCP_TOKEN": "<TOKEN>" } } } }
  ```

The full auth/connection surface (including the OAuth flow) is documented in [`README.md`](README.md).

> *Verified against [`.recon/02-mcp-transport.md`](.recon/02-mcp-transport.md) (jakarta servlets; the Streamable
> provider has no public `DEFAULT_MCP_ENDPOINT`; build the `McpJsonMapper` explicitly under OSGi).*

---

## 7. Packaging and OSGi Strategy

Follows the `protege-editor-owl` bundle pattern, but **embeds the entire MCP + Jetty stack** (the platform
provides none of it).

**As built**
- `packaging=bundle` via `maven-bundle-plugin:5.1.9` (`extensions=true`); `groupId org.protege`,
  `artifactId protege-mcp`, version **`0.2.0`**.
- `Bundle-SymbolicName org.protege.mcp;singleton:=true`; `Bundle-Name "Protege MCP Server"`.
- **Java 17 required:** `maven.compiler.release=17`, and the manifest carries
  `Require-Capability: osgi.ee=JavaSE 17`. The MCP SDK 2.0.0 public types are `record`s (needing
  `java.lang.Record`) and the embedded MCP/Jetty bytecode is Java 17 (class-file major version 61), so Protégé
  must launch on a **Java 17+ JVM**. (There is no Java-11 build path.)
- **Embedding is wholesale:** `Embed-Dependency *;scope=compile;inline=true` + `Embed-Transitive true` +
  `Multi-Release true`. The entire MCP + Jetty + Jackson + Reactor + networknt stack is inlined into the single
  bundle classloader, which avoids runtime `ClassNotFound` from missed reflective/late-bound dependencies
  (Reactor schedulers, json-schema-validator format loaders).
- `Export-Package` is **only** `org.protege.mcp;version=${project.version}`.
- `Import-Package` excludes every inlined library
  (`!com.fasterxml.jackson.*`, `!reactor.*`, `!org.reactivestreams.*`, `!io.modelcontextprotocol.*`,
  `!com.networknt.*`, `!com.ethlo.time.*`, `!org.yaml.snakeyaml.*`, `!org.eclipse.jetty.*`,
  `!jakarta.servlet.*`) and imports `protege/owlapi`, `slf4j[1.7,2)`, `com.google.common.*;[18.0,19)`,
  `javax.swing.*`, `java.awt.*`, the Eclipse registry, and a trailing `*;resolution:=optional` so the large
  inlined transitive surface does not block OSGi resolution.

**Embedded versions:** MCP SDK `mcp-core` + `mcp-json-jackson2` 2.0.0 (the convenience `mcp` artifact is
avoided because it pulls Jackson 3); **Jackson 2.20.1** (annotations 2.20); `reactor-core` 3.7.0; networknt
`json-schema-validator` 2.0.0 (pulls `jackson-dataformat-yaml` + `snakeyaml` 2.3 + `itu` transitively);
`jetty-ee10-servlet` 12.0.18; `jakarta.servlet-api` 6.1.0.

**Jackson skew — resolved by embedding.** The platform inlines Jackson **2.9.8** into `protege-editor-owl`
(not exported via `Import-Package`); the MCP stack needs **2.20.1**. The fix is to embed 2.20.1 in the bundle
and exclude `com.fasterxml.jackson.*` from `Import-Package`. MCP I/O is self-contained JSON, so Jackson objects
never cross the bundle boundary and there is no conflict.

**Constraints**
- **Guava is import-constrained to `[18.0,19)`** — the platform bundles import `com.google.common.*` at the
  pinned 18.0 (Protégé does not export Guava). The plugin keeps within this range or avoids Guava in plugin code.
- **slf4j stays provided** (platform 1.7.36 vs. the SDK's 2.0.16) — `slf4j-api` is not embedded.
- Discovery foot-guns: `plugin.xml` at JAR root; `singleton:=true`; a unique non-null `EditorKitHook` id;
  loaded only from `plugins/`.

> *Verified against [`.recon/08-packaging.md`](.recon/08-packaging.md): the MCP SDK 2.0.0 and Jetty 12 are
> Java 17 bytecode (major 61), so the running JVM must be Java 17+ — this corrected the earlier "Java 11"
> framing; the README already requires Java 17+.*

---

## 8. Threading, Undo, and Security

**Threading** (the hardest area; validated against the platform boot paths)
- `initialise()`/`dispose()` run on the **EDT or the OSGi framework thread** (boot-path dependent) and touch no
  UI/model; do no heavy work and no `invokeAndWait` there. Server start/stop run **off-EDT** on daemon threads
  with bounded joins.
- `McpServerController.start()/stop()/restart()` are `synchronized` and pin the thread-context classloader to
  the bundle classloader for the duration of build + Jetty start (for `ServiceLoader`, the
  json-schema-validator meta-schema, and Jetty), restoring it in `finally`.
- MCP handlers run on **Jetty/transport threads** (`immediateExecution(true)`, `validateToolInputs(false)`); the
  plugin marshals to the EDT itself rather than relying on the SDK executor.
- `OntologyAccess` is the single choke point: inline on the EDT, else `invokeLater` + latch with a 30s bounded
  wait, a `cancelled` flag, and `McpAccessException` on timeout/interrupt. The `isEventDispatchThread` guard
  prevents an `invokeAndWait` self-deadlock.
- Write-confirmation dialogs run on the EDT **outside** the bounded `compute`.
- `EmbeddedClassificationWaiter` blocks off-EDT on a latch counted down by the EDT-delivered
  `ONTOLOGY_CLASSIFIED` event — never blocking the EDT.
- Jetty async is enabled because Streamable HTTP uses `request.startAsync()` for SSE.

**Undo**
- Every change goes through `applyChange(s)` → `ontologiesChanged` listener callback →
  `HistoryManager.logChanges` on the single per-window `HistoryManager` → the **shared GUI undo stack** (LLM
  edits are undoable exactly like manual edits). Because LLM edits interleave with a person's undo history, the
  plugin documents this, shows status in the view, and provides a write-confirmation gate.
  (`OWLModelManagerImpl.java:716-734` `applyChanges`; `:736-756` `ontologiesChanged`, esp. `:741` `logChanges`;
  `:157` listener self-registration; `:169` the single `HistoryManager`.)

**Security — embedded OAuth 2.1 authorization server + static bearer (in parallel)**
- **`AccessTokenFilter`** guards `/mcp/*` only. It accepts `Authorization: Bearer <token>` where the token is a
  live OAuth access token **or** the static bearer token. On a missing/invalid token it returns **401** with
  `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource", error="invalid_token"`
  (RFC 9728) plus a JSON body, which makes OAuth-capable clients begin the browser flow.
- **`OAuthMetadataServlet`** at `/.well-known/*` serves RFC 9728 protected-resource metadata
  (`resource={base}/mcp`, `authorization_servers`, `scopes_supported=[mcp]`, `bearer_methods_supported=[header]`)
  and RFC 8414 authorization-server metadata (also at `/.well-known/openid-configuration`): issuer / authorize /
  token / register / revoke endpoints, `response_types=[code]`, `grant_types=[authorization_code,
  refresh_token]`, `code_challenge_methods=[S256]`, `token_endpoint_auth_methods=[none]`. The issuer/base is
  derived from the request `Host`.
- **`OAuthServlet`** at `/oauth/*`: `POST /oauth/register` (RFC 7591 dynamic client registration, public client
  `token_endpoint_auth_method=none`, `mcpc_` ids); `GET /oauth/authorize` renders an HTML consent page
  (requires `response_type=code`, `code_challenge` + `code_challenge_method=S256`, and a pre-registered
  `redirect_uri`) and `POST /oauth/authorize` records allow/deny and redirects to the client loopback callback
  with `code`+`state`; `POST /oauth/token` handles the `authorization_code` grant (PKCE S256 verification +
  client/redirect match) and the `refresh_token` grant; `POST /oauth/revoke` (RFC 7009) always returns 200.
- **`OAuthStore`** holds: dynamically-registered clients (`mcpc_`), one-time PKCE-bound authorization codes
  (`mcpa_`, 2-minute TTL, **not** persisted), access tokens (`mcpt_`, 30-day TTL), and refresh tokens
  (`mcpr_`, never expire). Access and refresh tokens share a grant id, so revoking either drops both (RFC 7009);
  `issueTokens` keeps at most one grant pair per client. The static token is accepted via a constant-time
  compare (`PkceUtil.constantTimeEquals`), and the store tracks `staticTokenLastSeen` and per-client `lastSeenAt`
  in memory.
- **Loopback-HTTPS note:** the server serves **plain HTTP on `127.0.0.1`** (`getEndpointUrl` returns
  `http://127.0.0.1:…`); there is **no TLS code in the plugin**. The "loopback exemption" is the MCP client's
  own willingness to run OAuth over `http` for a loopback host (RFC 8252), not server-side logic.
- The platform has no read-only guard (`isActiveOntologyMutable()` is always true), so **read-only mode,
  confirm-each-write, and status display are the plugin's responsibility.**

**Persistence across restarts.** Settings live in the Protégé Preferences store
(`PreferencesManager` set `org.protege.mcp`, group `server`). Persisted: `bearerToken` (generated on first run),
`port`/`autoStart`/`readOnly`/`confirmWrites`, and the OAuth state blob (`KEY_OAUTH_STATE = "oauthState"`) —
registered clients plus access/refresh tokens, as a single user-global JSON string. On restart, `stop()` drops
the in-memory `OAuthStore` (the view shows no clients while stopped) and the next `start()` rehydrates clients +
tokens from `KEY_OAUTH_STATE`, so previously-connected clients stay authorized. The persisted OAuth state is
bounded to the preference-value size limit: `java.util.prefs` caps a single value at 8192 chars and throws
above it, so `persist()` writes under a `MAX_PERSIST_CHARS = 8000` margin — it first purges expired entries,
then evicts the least-recently-seen client (tie-break: oldest registration) until the JSON fits, logging a
warning; evicted clients must re-authorize.

**Multi-window.** Each window has its own controller, but `McpServerRegistry` elects a **single owner of the one
process-wide port** (default 8123). A newly opened window **defers** to whichever controller already owns the
port — it stays idle but registered (`startIfNoOwner`) rather than fighting over the bind — and when the owning
window closes, the port is **handed off** to another open window (`promoteSuccessor`), so swapping EditorKits
(e.g. opening a different ontology into an empty window) never tears the server down while a window is still
open. A bind failure is non-fatal: logged and surfaced via the view, not thrown on the EDT. The OAuth state is a
single user-global preference key, effectively shared by whichever window currently holds the port.

> *UI/preferences verified against [`.recon/06-ui-prefs.md`](.recon/06-ui-prefs.md); undo mechanics against
> [`.recon/05-modelmanager.md`](.recon/05-modelmanager.md).*

---

## 9. Architecture Approach B — In-Protégé Claude Chat (v2)

> *This "B" is the **architecture** axis (the plugin's internal shape). It is unrelated to the README/docs
> **"Distribution Path B"** (Check for plugins, §11), which describes how the jar is delivered. They are
> different things.*

**Status: designed, not yet built.** Architecture Approach B is a new Swing chat panel inside Protégé where the user
converses with Claude, and Claude reads/edits the open ontology by calling **Architecture Approach A's existing
tool layer**. B sits **cleanly atop A**: everything below the tool boundary already exists — the §5 tools in
`org.protege.mcp.tools`, the `OntologyAccess` EDT choke point, the live read-only/confirm-write gates on
`McpServerController`, the shared GUI undo stack, and `McpConfig` preference storage. The genuinely new code is
exactly three things: (1) a `ChatView` `ViewComponent`, (2) an `AnthropicAgentLoop` (a client-side, manual
`/v1/messages` loop), and (3) a small consent/cost policy layer.

### 9.1 Tool boundary — in-process reuse as the single schema source

**Decision: call A's in-process Java tool layer directly. Do _not_ stand up an in-process MCP client, and do
_not_ use Claude's server-side MCP connector.**

`ChatView extends AbstractOWLViewComponent` (exactly like `McpServerView`), reaches the live window via
`getOWLEditorKit()`, builds `OntologyAccess(editorKit)`, and borrows the window's `McpServerController` from
`McpServerRegistry` for the live gate flags (`isReadOnly()` / `isConfirmWrites()`). If no controller is
registered for that window (the MCP server was never started there), it falls back to a fresh `McpConfig.load()`
snapshot — so the chat works even with the MCP server stopped. It builds `ToolContext(access, controller)` and
calls `ToolCatalog.buildAll(ctx)` to obtain the same `SyncToolSpecification`s the MCP server exposes.

Each spec's `McpSchema.Tool` carries `name()`, `description()`, and `inputSchema()` — a `Map<String,Object>`
that is already valid JSON Schema (built with a `LinkedHashMap`, so key order is stable). A single
`AnthropicToolBridge` lifts each into an Anthropic tool definition (`name` + `description` + `input_schema`).
**The MCP schemas are the single source of truth** — they feed both the MCP server and the chat, so the model
always sees exactly what external clients see, and schemas are never duplicated. Index the specs by
`tool().name()` for O(1) dispatch.

On a `tool_use` block, dispatch synthesizes a `CallToolRequest(name, inputMap)` (where `inputMap` is the parsed
`tool_use.input`) and invokes the matching `BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult>`
handler. Every handler is written `(ex, req) -> …` and never reads `ex`, so a stub/null exchange suffices; the
result's `isError()` + text map straight back to an Anthropic `tool_result` block with `is_error` mirrored.

Because dispatch runs `WriteTools.write()` unchanged, **gates, EDT marshalling, and undo come for free**: the
live read-only switch and the modal confirm-each-write dialog apply, edits go through
`OntologyAccess.compute → OWLModelManager.applyChange(s) → the shared HistoryManager`, and post-`ChangeListMinimizer`
state is re-queried. Chat edits are indistinguishable from MCP-client edits and join the **same** undo stack.

- **Why not an in-process local MCP client (`HttpClient → 127.0.0.1/mcp`):** it would force the chat through
  `AccessTokenFilter` (needing the bearer/OAuth token), the async servlet, and a second JSON round-trip inside
  one JVM for zero benefit, and it requires the server to be started, bound, and authenticated. The direct
  in-process path skips all of it.
- **Why not the server-side connector (load-bearing):** A binds Jetty to `127.0.0.1` only, and
  `api.anthropic.com` cannot route to a user's loopback endpoint. Claude's server-side MCP connector
  (beta `mcp-client-2025-11-20`) has Anthropic's servers dial the MCP server, so it can never reach a loopback
  endpoint. **The tool-use loop must therefore run client-side.**
- **Guardrail:** "handlers ignore `ex`" is an internal contract of the current handlers, not a guarantee. Add a
  test asserting all tool handlers run with a stub exchange (or have `ToolCatalog` expose an exchange-free dispatch
  entry point), so a future tool that reads `ex` fails a test rather than the live panel.

### 9.2 A thin provider seam (no overbuilding)

A deliberately minimal internal SPI — one interface and one implementation, **not** a plugin registry:

```
ChatProvider { stream(ChatRequest, ToolRuntime, TokenSink) }
ProviderModel, ProviderUsage, ToolDef, ToolRuntime
```

The only initial implementation is `AnthropicChatProvider`. The seam earns its place because (a) it keeps
model/provider knobs out of the loop and UI code; (b) the cost/consent controls (caching, effort, model picker,
per-write confirm, egress disclosure, key custody) become provider-agnostic **policy objects**; and (c)
`ToolRuntime` ("given a `tool_use`, run it and return a result block") is the seam that later lets the same loop
dispatch to in-process Protégé tools **and** — optionally — a local MCP client for user-configured **external**
MCP servers (filesystem, web, other domain servers). External servers are internet-reachable, so a local MCP
client is viable for them; Protégé's own loopback tools stay in-process. That external-MCP feature is phased
last, behind a flag. Do not ship a second provider, and do not promise one.

### 9.3 LLM transport — a Phase-0 decision (recommend raw HTTP for this bundle)

This is presented as an explicit Phase-0 tradeoff rather than a silent default, because the idiomatic preference
is the official SDK whenever one exists.

- **The idiomatic default** is `com.anthropic:anthropic-java:2.34.0`:
  `AnthropicOkHttpClient.builder().apiKey(key).build()`, `client.messages().createStreaming(params)` returning
  `StreamResponse<RawMessageStreamEvent>`, typed `ThinkingConfigAdaptive`, `OutputConfig.Effort`,
  `CacheControlEphemeral`, `Tool.InputSchema`, plus automatic retry/backoff and SSE handling. For a greenfield
  service this is the right call.
- **For this bundle, the OSGi-embedding cost overrides that default.** The pom inlines its entire compile
  classpath wholesale and pins Jackson to exactly 2.20.1; the SDK transitively pulls OkHttp + Okio +
  Kotlin-stdlib + its own Jackson. Embedding it would inline Kotlin-stdlib (~1.5–1.7 MB) + OkHttp/Okio (~1 MB)
  on top of the existing mass, and re-open the §7 Jackson-skew problem (the SDK's Jackson overlapping the pinned
  2.20.1). Mitigating that means excluding the SDK's transitive Jackson, growing the `Import-Package` `!`-list
  (`okhttp3.*`, `okio.*`, `kotlin.*`, `org.jetbrains.annotations.*`), and build-verifying single-classloader
  resolution — real, ongoing manifest and supply-chain risk for the life of the bundle.
- **Recommendation: `java.net.http.HttpClient` (JDK 17, already required via `Require-Capability JavaSE 17`) +
  the already-embedded Jackson 2.20.1** for request/response JSON. This adds **zero new embedded jars and zero
  new `Import-Package` entries** — `java.net.http` and `javax.net.ssl` come from the OSGi system bundle and are
  never imported (the manifest already imports `javax.swing`/`java.awt` and deliberately omits `java.net`).
  Net manifest delta: effectively nil. The honest cost is hand-rolling (1) **SSE parsing** — read the
  `InputStream` line-by-line, split on `\n\n` boundaries, parse `data:` lines as JSON with the embedded
  `ObjectMapper`, accumulate `input_json_delta` partial JSON for `tool_use` blocks — and (2) **retry/backoff** —
  honor `429` `retry-after` and exponential backoff on 5xx, which the SDK would give for free. Keep this code
  behind the `ChatProvider` boundary and unit-test the line parser hard.
- **Keep the SDK as a documented Phase-7 alternative** behind the same SPI; if the embed size and Jackson skew
  prove acceptable at build, `AnthropicChatProvider` can be swapped for an SDK-backed implementation without
  touching the loop, UI, or tool bridge. A separate **loopback-dogfood** configuration (SDK + a real MCP client
  to `127.0.0.1/mcp`) remains legitimately buildable for anyone who wants the chat to exercise the same
  transport/auth/schema path an external client uses; its honest cost (a redundant localhost hop, a second auth
  handshake, a heavier jar) is why it is not the default.

**Model and request shape (Claude API facts):**
- Default model **`claude-opus-4-8`** (1M context / 128K output). Alternates **`claude-sonnet-4-6`** (1M/64K)
  and **`claude-haiku-4-5`** (200K/64K) in the panel picker. **`claude-fable-5`** only on explicit user request —
  and if added, ship the server-side `fallbacks` parameter (`betas:["server-side-fallback-2026-06-01"]`,
  `fallbacks:[{"model":"claude-opus-4-8"}]`) plus `stop_reason == "refusal"` handling (Fable refusals are
  HTTP 200), and note Fable's 30-day data-retention requirement. **Use the exact id strings with the full
  `claude-` prefix and no date suffixes — the bare forms `sonnet-4-6` / `haiku-4-5` / `fable-5` are not valid
  ids.**
- **Adaptive thinking only:** `thinking: {type: "adaptive"}`. `budget_tokens` is removed on Opus 4.8 (400).
  Set it explicitly — omitting `thinking` means thinking is off. `display` defaults to `"omitted"`; set
  `"summarized"` when the panel renders reasoning (otherwise the user sees a long pause before tokens).
- **Effort** via `output_config: {effort: …}`, default `"high"`; expose `low | medium | high | xhigh | max`.
- **Stream** (`max_tokens` default ~64000 for streaming, where timeouts are not a concern).

### 9.4 The agentic loop (client-side, manual)

A hand-written state machine over the SSE stream, run entirely on a daemon worker thread in the panel's JVM. Per
turn, send `{model, max_tokens, thinking, output_config, system, tools (all §5, converted), messages, stream:true}`
in the API-mandated render order **tools → system → messages**. Loop rules:

- **Loop until `stop_reason == end_turn`.**
- Each turn, **append the full assistant `response.content` unchanged** to `messages` — text blocks, **thinking
  blocks (echoed back verbatim on the same model, or the API rejects the turn)**, and every `tool_use` block.
- When `stop_reason == tool_use`, execute **each** `tool_use` block via `ToolRuntime` (in-process dispatch,
  §9.1), gather **all** `tool_result` blocks into **one** user message, each carrying the matching `tool_use_id`,
  with `is_error: true` for failures (mapped from `CallToolResult.isError()`; `McpAccessException` / timeouts
  also become `is_error` results — never drop a block).
- Handle `stop_reason == max_tokens` (offer to continue). Guard `stop_reason == refusal` before indexing
  `content` (only reachable if `claude-fable-5` is selected).
- Bound the loop with a max-tool-round cap (e.g. 12–25) to prevent runaway loops, surfacing a "reached step
  limit" note.

Write tools are intercepted by the consent layer (§9.6) **before** dispatch. Mid-stream, push text deltas to the
UI; thinking display defaults to omitted, with a panel "show reasoning" toggle that sets `display: "summarized"`.

### 9.5 Threading and EDT safety

Mirror `McpServerView`'s proven pattern (a daemon `Thread` for blocking work; `SwingUtilities.invokeLater` for
UI — see `McpServerView.java:274-288`). Three strictly-separated domains:

1. **EDT — Swing only.** `ChatView.initialiseOWLView()/disposeOWLView()` build the transcript pane, input box,
   model/effort selectors, and Stop button.
2. **Agent worker** — a dedicated daemon thread (`protege-chat-agent`). All `HttpClient`/SSE I/O and the whole
   loop run here, **never on the EDT** (network on the EDT freezes Protégé). Token deltas marshal to the
   transcript via `SwingUtilities.invokeLater` (fire-and-forget, **not** `invokeAndWait`), **coalesced** (batch
   deltas every ~16–60 ms or N characters, `SwingWorker` `publish`/`process`-style) so thousands of SSE events
   do not flood the EDT.
3. **Tool execution** — the worker invokes tool handlers **off-EDT**, so `OntologyAccess.compute` does its own
   EDT hop and the modal confirm runs on the EDT **without** freezing the loop's thread. This is the critical
   nuance: if the loop ran a handler on the EDT, `compute()` would run inline but a confirm-write dialog would
   block the EDT and freeze Protégé. The write-confirmation dialog runs on the EDT **outside** the bounded
   `compute()` (reused unchanged from A).

**Cancel/Stop:** a volatile flag the worker checks between turns, plus closing the in-flight `HttpClient`
response stream. `disposeOWLView()` signals the worker to stop and closes any open stream, with a bounded join
(the same discipline as `McpServerHook.dispose()`).

### 9.6 Security and egress

- **New external transmission — disclose it.** Outbound HTTPS to `api.anthropic.com` only. This is a
  transmission the read-only MCP server never made: the chat sends ontology content (entity IRIs, labels,
  axioms returned by tools, the system-prompt ontology snapshot, and the user's prompts) to a third party.
  Surface a one-time consent banner + a persistent egress indicator in the panel, and document it in the README.
  The chat opens **no inbound socket** — no new attack surface, unlike A's loopback Jetty.
- **API-key custody.** Store under a new `KEY_ANTHROPIC_API_KEY` in the **same** Preferences set as the bearer
  token (`McpConfig.prefs()` = `getPreferencesForSet("org.protege.mcp", "server")`), read/write via
  `getString`/`putString` exactly like `KEY_TOKEN`. A ~108-char key is far under the 8192-char per-value limit,
  so no chunking. **Not encrypted at rest** — `java.util.prefs` is plaintext (a plist on macOS, XML on Linux,
  the registry on Windows), identical to how the bearer/OAuth tokens already sit; there is no keychain or
  encryption layer anywhere in Protégé. The panel must say so plainly, and offer reading the key from the
  `ANTHROPIC_API_KEY` environment variable as a no-storage alternative. **Never log the key.** Keep the two
  secrets — the inbound MCP bearer token vs. the outbound Anthropic API key — in clearly-separated, clearly-labeled
  fields.
- **Consent reuse.** Chat writes go through `WriteTools.write()`, so A's read-only switch and confirm-each-write
  modal apply unchanged, and the chat **cannot** escalate past read-only or confirm-writes. Because an LLM can
  emit many edits autonomously, default the chat to honor `confirmWrites`, consider a chat-scoped "confirm all
  model edits" toggle (default on) plus a running edit-log, disable write tools at request-build time in
  read-only mode (don't even offer them to the model), and always confirm destructive tools (`remove_axiom`,
  `save_ontology`).
- **Proxy.** Protégé exposes no proxy UI; `HttpClient` honors the JVM `https.proxyHost`/`https.proxyPort` system
  properties if set — document this, and optionally add a panel field. Disclose provider lock-in and per-token
  cost (the explicit Architecture Approach B downsides).

### 9.7 Cost handling

Architecture Approach B carries the ~1.5–2.5× cost premium that the architecture comparison (§2) flags. Blunt it with
**prompt caching** in the render order tools → system → messages:

- Put a `CacheControlEphemeral` breakpoint on the **last system block** so the tool definitions + the system
  prompt cache together as one prefix. The Opus-4.8 minimum cacheable prefix is **4096 tokens** — the tool schemas
  + a substantial system prompt should clear it; a trimmed subset under 4096 silently won't cache.
- **Keep the system prompt frozen.** Do **not** interpolate the active-ontology IRI / axiom counts / timestamps
  / session ids into `system` — that invalidates the whole prefix every turn. Inject volatile ontology context
  **after** the cached prefix: as a tool result, or as a mid-conversation `{"role": "system", …}` message
  (supported on Opus 4.8, no beta header; must follow a user message and be last or be followed by an assistant
  turn).
- Serialize the tool list **deterministically** (`ToolCatalog` order is stable; the schema builder uses
  `LinkedHashMap`) so the tools-prefix bytes don't drift.
- **Multi-turn:** also place a breakpoint on the last content block of the latest turn so the growing
  conversation prefix is reused (max 4 breakpoints per request).
- **Verify** via `usage.cache_read_input_tokens` — if it stays zero across turns, a silent invalidator is at work
  (e.g. ontology state leaked into `system`); the premium balloons with no error otherwise.

Cost levers in the panel: default `claude-opus-4-8` at effort `high`; expose `claude-haiku-4-5` / effort
`low`/`medium` for cheap sessions; adaptive thinking lets Claude self-moderate depth; a per-turn token/cost
readout (input / cached / output from `ProviderUsage`) keeps spend visible; the max-tool-round bound caps
worst-case spend. Caches are per-model, so switching models mid-conversation cold-writes the new model's cache —
start a new chat to change model. Optionally pre-warm the tools+system cache with a `max_tokens: 0` request on
panel open (worth it only for interactive latency).

### 9.8 Packaging (Architecture Approach B)

Lowest-risk variant by design. `ChatView` is one more extension in `src/main/resources/plugin.xml` against
`org.protege.editor.core.application.ViewComponent` (sibling to the `McpServerView` block), `<label>MCP Chat</label>`,
`<class>org.protege.mcp.ui.ChatView</class>`, user-creatable by default (droppable into any tab via
Window ▸ Views; optionally its own `WorkspaceTab` + viewconfig). A new `preferencespanel` (or new keys on the
existing MCP panel) carries the API key + model/effort defaults + chat-scoped always-confirm + optional proxy.
New Java lives under bundle-internal `org.protege.mcp.*` (`Export-Package` stays `org.protege.mcp` only); the
agent loop reuses the already-inlined `McpSchema.Tool` / `CallToolRequest` / `CallToolResult` types, so no new
types cross the bundle boundary. With the recommended raw-HTTP transport, **net manifest delta is effectively
nil** — no `Embed-Dependency`/`Multi-Release` change, no new `Import-Package` entry, no Jackson-skew. Java 17
stays required (already enforced). The SDK alternative (§9.3) raises packaging cost materially — which is the
whole reason raw HTTP is the default for this bundle.

### 9.9 Phasing (Architecture Approach B)

- **Phase 0 — Provider/transport decision + spike.** Confirm `java.net.http.HttpClient` + embedded Jackson
  2.20.1 over the SDK for this bundle (record the tradeoff); define the `ChatProvider` SPI. Spike one streaming
  `POST /v1/messages` with adaptive thinking + effort, parse SSE with the embedded `ObjectMapper`, print deltas;
  decide `max_tokens` (~64000) and the model menu (`claude-opus-4-8` default).
- **Phase 1 — Tool bridge.** `AnthropicToolBridge` maps the §5 specs → Anthropic tool defs, indexes handlers by
  name, dispatches `tool_use` → stub-exchange `CallToolRequest` → `CallToolResult` → `tool_result`. Add the
  stub-exchange test over all tool handlers; unit-test a read tool and a gated write tool through a stub controller.
- **Phase 2 — Agent loop.** `AnthropicAgentLoop` on the daemon worker: stream → accumulate text + thinking +
  `tool_use` → execute all tools off-EDT → one `tool_results` user message → repeat to `end_turn`. Handle
  `max_tokens`, errors → `is_error`, Stop/Cancel, and the iteration cap.
- **Phase 3 — ChatView.** `AbstractOWLViewComponent`; build `OntologyAccess(getOWLEditorKit())` + borrow the
  `McpServerController` (fallback to `McpConfig.load()` flags). Swing UI: a streaming transcript `JTextPane`
  (coalesced `invokeLater`), input field, model + effort selectors, Stop button, edit-log/undo hint, persistent
  egress indicator. Register in `plugin.xml`.
- **Phase 4 — Secrets + settings.** `KEY_ANTHROPIC_API_KEY` (+ model/effort defaults, optional proxy,
  chat-scoped always-confirm, env-var key alternative); a masked field; never-log; disclose plaintext-at-rest +
  external HTTPS egress + cost + provider lock-in in the panel and README.
- **Phase 5 — Caching + cost hardening.** `CacheControlEphemeral` on the last system block; a frozen system
  prompt with ontology context injected after the prefix; deterministic tool serialization; verify
  `cache_read_input_tokens`; per-turn token/cost readout; optional `max_tokens: 0` pre-warm; expose haiku/effort
  knobs.
- **Phase 6 — Robustness + tests.** SSE edge cases (partial `tool_use` JSON, heartbeats, mid-stream errors), 429
  `retry-after` + 5xx backoff, EDT-timeout → `is_error` recovery, `disposeOWLView` cancellation, multi-window
  controller-absent fallback.
- **Phase 7 (optional, last) — alternatives behind the SPI.** An SDK-backed `AnthropicChatProvider`; the
  loopback-dogfood configuration; `claude-fable-5` + server-side fallbacks / refusal handling; and the
  **external MCP servers** feature (a local MCP client via `ToolRuntime`, so the same loop exposes "Protégé tools
  + others"), each behind a flag with its own consent/egress disclosure. Compaction / context-editing for very
  long 1M-context sessions also lands here.

---

## 10. Architecture Approach C — Standalone MCP Server (complement)

**Status: designed, not built.** An OSGi-free shaded JAR + stdio that edits ontology **files** on disk
(headless / CI / batch), a ~2–3 day MVP. Shipping it alongside Architecture Approach A would cover both
"interactive live editing (A)" and "automation (C)." It uses `owlapi-distribution` (non-OSGi); the
`CustomOWLEntityFactory` IRI/prefix conventions must be reimplemented. There is **no GUI and no undo** — it edits
file snapshots, not the live model.

---

## 11. Distribution

Two delivery paths for the built jar. These are the **distribution** axis (how the jar reaches the user), **not**
the architecture axis (§§2, 9, 10).

- **Distribution Path A — manual install.** Drop the bundle jar into `<protege>/plugins` (or
  `~/.Protege/plugins`) and restart on a Java 17+ JVM. See [`README.md`](README.md#install).
- **Distribution Path B — "Check for plugins".** A self-hosted Protégé update registry. The repo ships
  [`update.properties`](update.properties) (the update descriptor) and
  [`protege-mcp.repository`](protege-mcp.repository) (the registry list). See
  [`docs/check-for-plugins.md`](docs/check-for-plugins.md).

> *"Distribution Path B" (Check for plugins) is unrelated to "Architecture Approach B" (in-app chat, §9). One is
> how the jar is delivered; the other is the plugin's internal shape.*

The same `Bundle-SymbolicName org.protege.mcp;singleton:=true` and the OSGi/version rules in §7 govern both
paths; `docs/check-for-plugins.md` covers the version-comparison rules that decide whether "Check for plugins"
offers an update.

---

## 12. Roadmap

**Architecture Approach A — delivered (`0.1.0`).**

| Phase | Scope | Status |
|---|---|---|
| **0. Spike / packaging** | `protege-mcp` bundle; embed the MCP stack + Jetty 12 ee10; clean load, `plugin.xml` recognized, zero Jackson/Guava conflict | ✅ done |
| **1. Lifecycle / transport** | `McpServerHook` start/stop (EDT-cheap), `/mcp` reachable | ✅ done |
| **2. Read tools / EDT bridge** | `OntologyAccess` + 6 read tools | ✅ done |
| **3. Write tools / Undo** | 9 write tools via `applyChange(s)`; GUI round-trip + undo | ✅ done |
| **4. Reasoner tools** | 4 reasoner tools + `EmbeddedClassificationWaiter` | ✅ done |
| **5. Security / settings** | **Expanded beyond the original MVP:** embedded OAuth 2.1 AS + static bearer, persistence across restarts, preferences | ✅ done |
| **6. Status UI / multi-window / docs** | `McpServerView` with the connected-clients table + per-client revocation; per-window controllers | ✅ done |
| **7. Hardening** | deadlock/perf/OSGi regression coverage across all tool paths | ⏳ ongoing |

**Next tracks:** Architecture Approach B phasing (§9.9) and Architecture Approach C (§10).

---

## 13. Open Risks

- **Java 17 runtime requirement** — the embedded MCP SDK 2.0.0 + Jetty 12 are Java 17 bytecode (major 61), so
  the running JVM must be Java 17+; the Guava `[18.0,19)` import pin still applies. (See
  [`.recon/08-packaging.md`](.recon/08-packaging.md).)
- **OSGi / Jackson skew** — platform inline 2.9.8 vs. embedded **2.20.1**; resolved by wholesale embed +
  `!`-Import exclusions + keeping Jackson objects off the bundle boundary. Monitor on platform upgrades.
- **EDT consistency** — `initialise`/`dispose` thread is boot-path dependent (no EDT assumption; the hook
  marshals); handler-side marshalling needs the timeout + `isEventDispatchThread` guard (self-deadlock
  prevention).
- **Async reasoner race** — classify-then-wait via listener + latch; a reasoner-status guard runs before reading
  inferred results.
- **Undo interleaving** — LLM edits mix into the human undo history, and `ChangeListMinimizer` may drop/merge —
  hence re-query after apply.
- **Multi-window port/state** — a single process-wide port is shared across windows via owner election +
  hand-off (`McpServerRegistry.startIfNoOwner` / `promoteSuccessor`); a bind failure is logged and surfaced.
  The residual caveat is the **single user-global OAuth blob** shared by whichever window currently holds the port.
- **OAuth state size bound** — the `java.util.prefs` 8192-char cap; persistence stays under
  `MAX_PERSIST_CHARS = 8000` via purge-expired + least-recently-seen client eviction; evicted clients must
  re-authorize; per-client `lastSeenAt` / `staticTokenLastSeen` can read stale right after a restart.
- **Loopback OAuth over plain HTTP** — no TLS in the plugin; relies on the client's loopback-http exemption.
- **`mcp-remote` dependency** — stdio-only clients (e.g. Claude Desktop) need Node/`npx`.
- **Architecture Approach B forward risks (when built)** — new outbound egress of ontology content to `api.anthropic.com`;
  the API key plaintext-at-rest; per-token cost premium; provider lock-in; autonomous multi-edit volume
  (mitigated by the confirm-writes default + edit-log + max-round cap).

---

## Appendix A. Core Evidence Files

| Fact | Platform location (`../protege`) | Verified against |
|---|---|---|
| maven-bundle-plugin 5.1.9 / bundle config | `pom.xml` (build plugin) | `.recon/08` |
| plugin / `singleton` check | `protege-editor-core/.../ProtegeApplication.java:211-241` | `.recon/04` |
| extension-point declarations | `protege-editor-core/src/main/resources/plugin.xml:4-34` | `.recon/04`, `.recon/06` |
| `ViewComponent` / `preferencespanel` schema | `protege-editor-core/schema/ViewComponent.exsd`, `preferencespanel.exsd` | `.recon/06` |
| `EditorKitHook` definition / id | `protege-editor-core/.../editorkit/plugin/EditorKitHook.java`, `EditorKitHookPlugin.java` | `.recon/04` |
| hook lifecycle registration | `protege-editor-core/.../editorkit/Initializers.java:18-31` | `.recon/04` |
| hook call site (workspace not yet initialised) | `protege-editor-owl/.../OWLEditorKit.java:94,109` | `.recon/04` |
| EditorKit creation: URI boot path EDT, no-arg path framework thread | `protege-editor-core/.../ProtegeApplication.java:431,480-495,487,521` | `.recon/04` |
| boot delegation (`com.sun.*`, system packages) | `protege-desktop/.../felix/conf/config.xml` | `.recon/08` |
| `isActiveOntologyMutable` always true; `applyChanges`; Undo via listener callback | `OWLModelManagerImpl.java:655-667,716-734,736-756 (741 logChanges),157,169` | `.recon/05` |
| async classification; reasoner status; unsatisfiable as `Node` | `protege-editor-owl/.../OWLReasonerManagerImpl` + OWL-API reasoner | `.recon/07` |
| MCP SDK 2.0.0 sync server / transport / schema | MCP SDK `mcp-core` 2.0.0 sources | `.recon/01`, `.recon/02`, `.recon/03` |

### Appendix B. As-built plugin source (`org.protege.mcp.*`)

| Class | Role |
|---|---|
| `McpServerHook` | `EditorKitHook` — per-window lifecycle owner; registers in `McpServerRegistry`; auto-starts off-EDT |
| `server/McpServerController` | lifecycle + runtime state; `start/stop/restart` off-EDT; wires filter/transport/OAuth servlets onto Jetty |
| `server/McpServerRegistry` | static `EditorKit → controller` map; elects the single port owner across windows (`startIfNoOwner` / `promoteSuccessor`) |
| `server/ManagedServer` | minimal interface (`isRunning`/`start`) implemented by `McpServerController`; lets the registry elect the single port owner and hand it off (a fake substitutes in tests) |
| `server/McpServerManager` | builds the MCP sync server + Streamable-HTTP transport; explicit JSON mapper + validator |
| `server/EmbeddedHttpServer` | embedded Jetty 12 ee10, `127.0.0.1` only |
| `server/OntologyAccess` | the single EDT choke point (inline on EDT, else `invokeLater` + bounded latch) |
| `server/EmbeddedClassificationWaiter` | off-EDT classification wait on the `ONTOLOGY_CLASSIFIED` latch |
| `server/AccessTokenFilter` | `/mcp/*` guard; OAuth access token or static token; RFC 9728 401 challenge |
| `server/McpAccessException` | EDT marshalling/timeout failure → MCP error |
| `oauth/OAuthServlet` | RFC 7591 registration, PKCE consent, `authorization_code`/`refresh_token`, RFC 7009 revoke |
| `oauth/OAuthMetadataServlet` | RFC 9728 + RFC 8414 discovery documents |
| `oauth/OAuthStore` | clients/auth-codes/access/refresh tokens; persisted, size-bounded |
| `oauth/OAuthSupport`, `oauth/PkceUtil` | shared helpers; PKCE S256 + constant-time compare |
| `tools/ToolCatalog`, `ToolSpecs`, `ToolContext` | tool aggregation, spec factory, handler context |
| `tools/ReadTools`, `WriteTools`, `EntityRefactorTools`, `ReasonerTools`, `Axioms` | the §5 tools + structured-axiom support |
| `tools/Tools`, `tools/ToolArgException` | shared OWLAPI/finder/render helpers; an invalid-argument signal turned into a non-fatal MCP error result |
| `config/McpConfig` | preferences snapshot (`org.protege.mcp` / `server`); token + OAuth state keys |
| `ui/McpServerView`, `ui/McpPreferencesPanel` | status view (connected-clients table) + settings panel |
