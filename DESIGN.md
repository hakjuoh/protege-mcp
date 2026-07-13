# Protégé × MCP Integration — Design

> 📖 **Looking for user documentation?** See the manual at <https://hakjuoh.github.io/protege-mcp/>.
> This document is the architecture/design rationale.

> Design for adding **MCP (Model Context Protocol) support** to Protégé Desktop as a plugin.
> **Adopted: Architecture Approach A — in-Protégé MCP server.** External LLM clients (Claude Code/Desktop,
> Codex CLI, VS Code MCP, other IDE extensions) read and edit the user's **live, open ontology** directly;
> edits appear in the GUI immediately and join the user's **undo** stack. Architecture Approach A is **built and
> shipping**: 66 structured tools + 11 guided prompts (§5) behind one fixed MCP endpoint that a shared broker
> keeps stable across every Protégé window and instance (§4.1 item 11).
>
> **Architecture Approach B — in-Protégé chat assistant — is also built.** A chat panel (and a
> dedicated **Ontology Assistant** tab) drives a locally-installed coding-agent CLI (`claude` / `codex`) that connects back
> to Approach A's own MCP server, so the assistant reads/edits the live ontology with no API key stored by the
> plugin (see §9).
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
- A direct model-API chat client — the in-app chat is **Architecture Approach B** (§9): a chat panel that
  drives a local agent CLI rather than the plugin calling a model API directly.
- Headless / batch file editing — this is **Architecture Approach C** (a separate complement track, §10).
- Multi-user remote access — the server binds **loopback (`127.0.0.1`) by default**
  (`EmbeddedHttpServer`: `connector.setHost(bindAddress)`, default `LOOPBACK = "127.0.0.1"`). The
  bind-address preference can expose another interface, but the design target remains a
  **single local user** — OAuth authorization stays same-machine-only whatever the bind address.

> *"Architecture Approach" describes the plugin's internal shape; it is distinct from the
> README/Check-for-plugins "Distribution Path A/B", which describes how the built jar is delivered (§11).*

---

## 2. Architecture Decision

Three candidates were designed and validated; **Architecture Approach A was adopted and built.**

| | **A. In-Protégé MCP server** ✅ built | **B. In-Protégé chat** ✅ built | C. Standalone MCP server |
|---|---|---|---|
| Form | Protégé plugin (OSGi bundle) | Plugin + chat panel driving a local agent CLI | Separate process (shaded JAR) |
| Edit target | **Live active ontology** | **Live** (via A's MCP server) | Ontology **files** on disk |
| Transport | localhost HTTP (Streamable) | spawns a local CLI → A's loopback MCP | stdio |
| GUI reflect / Undo | ✅ (core value) | ✅ (reuses A's tool layer) | ❌ (file snapshots) |
| External clients | as-is | not required (in-app) | as-is |
| Auth | OAuth 2.1 + static bearer | the CLI's own login (no key stored) | n/a (stdio child) |
| Status | **shipped** | **shipped** | designed (§10, complement) |

**Why A (and then B atop it).** The request is "add MCP to the Protégé app," and the largest value is an LLM
acting on the **model the user already has open** — the shared `OWLModelManager`, renderers, reasoner, and undo
stack. Only Architecture Approach A delivers this cleanly: Architecture Approach C edits stale file snapshots.
Architecture Approach B was originally deferred as an LLM-chat product the request did not ask for; the as-built
chat sidesteps that cost entirely by **driving the user's existing agent CLI** (which reuses A's tool layer over
loopback), so B ships as a thin UI + subprocess driver with no API-key custody (§9).

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
- **`plugin.xml` lives at the JAR root.** As built, it declares the three server extensions: the
  `EditorKitHook` (`McpServerHook`, `editorKitId=OWLEditorKit`) and `ViewComponent` (`McpServerView`, label
  `"MCP Server"`) carry **bare ids**, while the `preferencespanel` carries an **already-fully-qualified id**
  (`io.github.hakjuoh.protege_mcp.preferences` → `McpPreferencesPanel`, label `"MCP"`). For the bare ids the
  framework prepends the bundle namespace at runtime to form the fully-qualified id
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

A single Maven module `protege-mcp` (`packaging=bundle`). `plugin.xml` registers three server extensions
(`McpServerHook`, `McpServerView`, `McpPreferencesPanel`; the Approach B chat extensions are covered in §9). The server is composed of:

1. **`McpServerHook`** (`EditorKitHook`) — per-window lifecycle owner. `initialise()` builds an
   `OntologyAccess` and a `McpServerController`, registers the controller in `McpServerRegistry` keyed by the
   `EditorKit`, and (if auto-start is on) calls `McpBoot.autoStart(...)` (daemon thread
   `protege-mcp-start`) — broker-first (item 11); in the standalone/degraded mode this becomes the
   `McpServerRegistry.startIfNoOwner(...)` election, where a new window **defers** to whichever controller
   already owns the single process-wide port rather than fighting over the bind (a bind failure is
   non-fatal: the server falls back to an ephemeral port, logged and surfaced in the view). It
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
   (async), `OAuthMetadataServlet` at `/.well-known/*`, `OAuthServlet` at `/oauth/*`, and binds the port —
   **falling back to an ephemeral port when the configured one is already in use** (a second Protégé window's
   chat, or a second Protégé instance), so the window still gets a working server; `isPortFallback()` reports
   that state and the view surfaces it. Because two servers can then be live at once, the shared security
   state is isolated: the `OAuthStore` is constructed empty and hydrated from the user-global persisted blob
   only after the bind proves the server holds the configured port (a fallback server never sees grants the
   owner may later revoke) and never persists, and the bearer token is a **live preferences read** so
   regenerating it in any window applies to every live server. It exposes `regenerateToken`, `listClients`,
   `revokeClient`, `getStaticTokenLastSeen`, live `isReadOnly()` / `isConfirmWrites()` reads,
   `getEndpointUrl`, and `getLastError`.

3. **`McpServerRegistry`** — a static `ConcurrentHashMap<EditorKit, McpServerController>` so a window's view and
   preferences panel can reach that window's controller. It also **elects a single owner of the one
   process-wide port** across windows: `startIfNoOwner(...)` lets a new window defer to the current owner, and
   `promoteSuccessor()` hands the port to another open window when the owner stops — so overlapping `EditorKit`
   lifecycles (e.g. Protégé swapping EditorKits to open an ontology) never tear the server down while a window
   is still open. A server running on an ephemeral *fallback* port counts as the owner in neither helper:
   `startIfNoOwner` still starts a newly opened window (which re-claims the configured port if it has freed
   up, else falls back itself), and `promoteSuccessor` promotes an idle window on owner close — while a live
   fallback server is never restarted out from under an active chat. The election logic is factored into
   package-private, unit-testable overloads.

4. **`McpServerManager`** — builds and owns the MCP sync server and the Streamable-HTTP transport
   (`SERVER_NAME=protege-mcp`, `SERVER_VERSION=0.5.1`, endpoint `/mcp`). It constructs the `ObjectMapper`,
   `JacksonMcpJsonMapper`, and `DefaultJsonSchemaValidator` **explicitly** (avoiding a `ServiceLoader` failure
   under OSGi), then:
   ```java
   McpServer.sync(transport)
            .serverInfo(SERVER_NAME, SERVER_VERSION)
            .capabilities(ServerCapabilities.builder().tools(false).prompts(false).build())  // tools + prompts (both listChanged=false); no resources
            .immediateExecution(true)     // run handlers on the transport (HTTP) thread; the plugin marshals to the EDT itself
            .validateToolInputs(false)
            .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))  // sync server still validates tool SCHEMAS even with validateToolInputs(false)
            .instructions(...)
            .tools(allSpecs)              // the §5 tool specs
            .prompts(PromptCatalog.buildAll())  // the §5 guided-workflow prompts
            .build();
   ```
   `close()` calls `closeGracefully()` then `close()`.

5. **`EmbeddedHttpServer`** — a minimal embedded **Jetty 12 ee10** host (`jetty-ee10-servlet`) bound to
   the configured bind address (default `127.0.0.1`; a specific non-IPv4-loopback bind additionally
   aliases the same port on `127.0.0.1`), a single `ServletContextHandler` at context path `/`. Filters are registered with
   `REQUEST` + `ASYNC` dispatch and `asyncSupported` (Streamable-HTTP uses `request.startAsync()` for SSE).
   `start(port)` with port `0` picks a free ephemeral port and returns `connector.getLocalPort()`;
   `startWithFallback(port)` retries on port `0` when the failure chain is a `BindException` (port already in
   use) so a busy configured port degrades to an ephemeral bind instead of a dead server; `stopTimeout` is
   2000ms; `stop()` is best-effort and must not run on the EDT.

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
   "Static fallback token last used" label, and a 1500ms refresh timer. An explicit **Stop latches the
   server off** (`McpServerController.userStopped`): every auto-start path — window open, the chat's lazy
   start, standalone promotion — refuses under the same monitor until the user presses **Start**, which
   clears the latch. While the shared broker is unreachable, the view flips to *"Broker is down — press
   Start to relaunch it"* (Stop disabled, Start relaunches the broker immediately, bypassing the retry
   throttle) and the clients panel attributes clients to the window's direct server, not the dead broker.

10. **`McpPreferencesPanel`** (`PreferencesPanel`, label `"MCP"`) — port spinner (1–65535) + ephemeral checkbox,
    **bind address** (editable combo; presets `127.0.0.1`, `::1`, `0.0.0.0`; a non-loopback choice shows a
    red plain-HTTP warning), shared-broker toggle (default on) + **broker idle linger** spinner (seconds,
    0–3600, default 15), auto-start, read-only mode, confirm-each-write. Port, bind address and broker
    take effect on restart; read-only and confirm apply live, and a linger change reaches a running broker
    with its next heartbeat. Persisted via `McpConfig.prefs()`.

11. **Shared broker (`broker` package)** — a tiny standalone process that owns the configured port across
    every Protégé window AND instance, so external clients keep one fixed URL. On-demand lifecycle: a
    starting Protégé process probes `~/.protege-mcp/broker.json` + `GET /internal/info`; if no live broker
    answers, it spawns one — a cross-process file lock (`~/.protege-mcp/broker.lock`, held for the
    broker's life) keeps the spawn a **singleton** even when the port itself cannot arbitrate (ephemeral
    `0`, or held by a foreign app), and the lock is handed over when its holder is still dying —
    (`java -cp <this plugin's bundle jar + Protégé's slf4j jars> BrokerMain` —
    compile-scope deps are inlined into the bundle so the jar is self-sufficient; only provided-scope slf4j
    is resolved from the running framework's CodeSource). The classpath is **staged**: both jars are copied
    to `~/.protege-mcp/jars/<name>-<sha256/12>.jar` and the broker runs from the copies, never from the
    plugins directory — a JVM holds its classpath jars open, and a broker outliving Protégé (linger/grace)
    would otherwise block deleting/replacing the plugin jar on Windows during an update. Content-hash names
    make a rebuilt same-version jar a fresh copy and concurrent spawns race-free; unused copies are swept
    (age-gated, best-effort) on later spawns; staging failure falls back to the original jars. On spawn
    failure, or when a broker already exists, it simply uses whichever broker is alive. The broker **reference-counts** registered processes (2s
    heartbeats plus a pid/staleness reaper for crashed instances) and **exits itself** once no instance
    references it (configurable idle linger, default 15s, range 0–3600s; 60s boot grace for orphans). `BrokerServer` reuses `EmbeddedHttpServer`,
    `AccessTokenFilter` and the OAuth stack, persisting OAuth state to `~/.protege-mcp/oauth.json`;
    `McpProxyServlet` streams `/mcp` byte-for-byte (flush per chunk → live SSE) to per-window backends,
    pinning each MCP session to the window that created it (`Mcp-Session-Id`) and defaulting new sessions to
    the most recently registered window (the registry already orders by a reported focus timestamp; live
    focus tracking is a follow-up); `GET /instances` lists windows, `/instances/{id}/mcp` targets one. Trust model: clients authenticate to the broker (bearer/OAuth); the broker authenticates to
    a backend with that window's per-start secret (`X-Protege-Mcp-Broker`, stripped from client requests);
    the `/internal` control plane requires the owner-only `~/.protege-mcp/secret`. Windows run as
    broker-managed backends (`startBrokerManaged()`: ephemeral port, no shared-blob OAuth). `McpBoot` is the
    single boot policy — broker-first, degrading to the standalone election + port-fallback mode when no
    broker can be reached or spawned. The chat never crosses the broker (it talks to its own window's server
    directly).

> *Builder/method names verified against [`.recon/01-mcp-server.md`](.recon/01-mcp-server.md),
> [`.recon/02-mcp-transport.md`](.recon/02-mcp-transport.md), and
> [`.recon/03-mcp-schema.md`](.recon/03-mcp-schema.md): `immediateExecution(boolean)`, the inline single-tool
> method is `toolCall(...)` not a builder `addTool`, the transport providers are `jakarta` `HttpServlet`s (not
> `javax`), and `Tool.inputSchema` is a `Map<String,Object>`.*

### 4.2 Data flow

```
window open → OWLEditorKit created (EDT or OSGi framework thread, boot-path dependent)
            → loadEditorKitHooks → McpServerHook.initialise()
            → builds controller, registers, auto-starts via McpBoot — broker-first: ensure/spawn the
              shared broker, register with it, start a broker-managed backend on an ephemeral loopback
              port; degrades to the standalone owner election (+ ephemeral-port fallback) when no
              broker can be reached or spawned
external client → <bind address>:<port>/mcp (the broker — or the standalone owner window)
            → AccessTokenFilter (OAuth access token or static token)
            → broker mode: McpProxyServlet pins the session (Mcp-Session-Id) and streams
              byte-for-byte to that window's backend (per-window X-Protege-Mcp-Broker secret)
            → Streamable-HTTP transport servlet
            → MCP handler runs on a Jetty thread (immediateExecution(true))
            → OntologyAccess marshals to the EDT → OWLModelManager.applyChange(s)
            → ontologiesChanged listener → HistoryManager.logChanges (shared undo) → GUI refresh
            → handler re-queries resulting state (ChangeListMinimizer may drop/merge) → CallToolResult
window close → McpServerHook.dispose() → unregister + stop off-EDT (bounded join); the broker routes new
              sessions to a remaining window (standalone: the port is handed off / promoted)
```

The tool layer is `ToolCatalog` + `ToolSpecs` + `ToolContext` + `ReadTools` / `WriteTools` / `ReasonerTools`.

---

## 5. MCP Tool Catalog (66 tools + 11 prompts)

Sixty-six tools — 7 read, 2 context, 20 edit/curation/history/persistence (incl. `preview_changes`,
`apply_changes`, `set_label`, `create_term`, `create_terms`, `create_property`, `create_properties`, `deprecate_entity`,
`move_class`), 6 ontology-header (incl. `set_prefix`), 5 document (incl. `set_active_ontology`,
`create_ontology`, `write_catalog`), 1 module (`extract_module`), 3 rule
(`list_rules`/`add_rule`/`remove_rule`), 9 reasoner (incl. `explain_inconsistency`), 3 SPARQL
(`sparql_query`/`sparql_schema`/`sparql_validate`), 4 validation (incl. `diff_ontologies`,
`validate_governance`, `shacl_validate`), and 6 safe/testable-authoring tools
(`add_competency_question` / `list_competency_questions` / `remove_competency_question` /
`run_competency_questions`, `verify_ontology`, `run_qc_suite`) — each defined by a `name`, a `description`, and a
JSON-schema `inputSchema` (a `Map<String,Object>`). Entities are referenced by IRI or display name.
**Every tool returns a structured JSON object** (set as MCP `structuredContent` and mirrored as a
serialized JSON text block via the `Tools.json()/ok()/error()` helpers), so clients can compose results
programmatically and a human still sees readable JSON. The server also registers **11 MCP prompts**
(guided workflows) — see the end of this section.

**The natural-language layer.** `get_ontology_context` / `get_entity_context` give a model-friendly
orientation and per-entity cards, `preview_changes` is a non-mutating dry-run / diff, and
`validate_ontology` runs a modelling-quality audit that complements the reasoner (it takes
`with_reasoner`); tool output is uniformly structured JSON, and the MCP prompts sit on top.

**Tool-driven construction ergonomics.** `set_active_ontology` switches the active edit target, and
`load_ontology`/`add_import` options resolve imports without stealing it; `apply_changes` applies a
previewed batch in one call; write tools report minted `new_entities` and take `strict` (refuse to mint
from a typo'd IRI); `create_*` accept `namespace` + language-tagged labels; `set_label` is a relabel
upsert; `set_prefix` manages the prefix map; the Manchester parser accepts full `<IRI>` operands inside
compound expressions; `get_entity_context` returns structured `{iri, …}` neighbours; and `undo`/`redo`
report the axiom-count delta.

**Structured rule and multi-module authoring.** `list_rules` / `add_rule` / `remove_rule` read, add, and
remove `swrl:Imp` axioms as body/head atoms, preserving named rule-variable IRIs (e.g. `ex-var:process1`)
and rule-level annotations that the conventional `?x` text syntax cannot round-trip; `create_ontology`
mints a new empty module in the workspace (paired with `set_ontology_id`); `write_catalog` generates the
OASIS `catalog-v001.xml` that maps a module's imports to their local files (a file outside the OWL axiom
model, so no other tool can produce it); and `diff_ontologies` performs an axiom-level semantic diff /
round-trip check between two loaded ontologies, or the active ontology against a freshly-loaded document.

**SPARQL.** `sparql_query` runs a SPARQL 1.1 query (`SELECT`/`ASK`/`CONSTRUCT`/`DESCRIBE`; read-only,
since `UPDATE` and `SERVICE` are rejected) over the active ontology and its imports closure, using an
embedded Apache Jena ARQ engine. The closure is snapshotted into a private throwaway ontology on the EDT —
preserving the real ontology IRI and ontology-level (header) annotations, not just entity axioms — then
serialised to RDF and queried off the EDT; the active ontology's prefixes (plus rdf/rdfs/owl/xsd) are
auto-prepended and `limit` caps the rows/triples returned. Set `include_inferred=true` to first materialise
the active reasoner's inferences into the snapshot (a quadratic property-assertion generator is skipped, with
a reported note, on a large ABox). Jena is inlined into the bundle; because jena-core and jena-arq ship the
same `META-INF/services/org.apache.jena.sys.JenaSubsystemLifecycle` resource, a hand-merged copy is shipped so
RIOT/ARQ init survives the single-classloader inlining, and `JenaSystem.init()` is warmed once at server start.

Two companion tools help *author* a query (since `sparql_query` only executes one): `sparql_schema`
returns the queryable vocabulary in one call — the prefix map (and a ready-to-paste `PREFIX` block), classes,
object/data properties with their domains and ranges, individuals and datatypes (each with a CURIE + IRI), plus
example queries grounded in the ontology's own terms — and `sparql_validate` parses a draft (without running it,
unless `dry_run`) with the ontology prefixes auto-prepended, reporting whether `sparql_query` would accept it
(read form, no `SERVICE`) and listing `unknown_terms` (IRIs used in the query — graph patterns, property paths,
`VALUES`, the `CONSTRUCT` template and `DESCRIBE` targets — that are not declared in the ontology, i.e. likely
typos). Both reuse the `sparql_query` snapshot/prefix machinery so they describe and validate exactly what would
run; the `author_sparql_query` prompt chains them (discover → draft → validate → run → iterate).

**Safe, testable LLM-assisted authoring** closes the **propose → ground → verify → confirm** loop, built by
reusing the shipping primitives (the single-undo transactional apply, the embedded reasoner, Jena ARQ,
`OWLEntityFinder`, and the catalog sidecar pattern):

- **`apply_changes` takes `verify=none|report|rollback`.** With `report` or `rollback` the batch is
  applied as one undoable transaction, then the reasoner is classified off the EDT and the result is
  checked for a **regression** — a class that became unsatisfiable, or an ontology that became
  inconsistent, *because of this batch* (`postUnsat \ preUnsat`, or newly-inconsistent). `report` keeps the
  batch and returns the verdict; `rollback` additionally reverts the whole batch in one `undo` when a
  regression is attributable. The whole pre-read → apply → classify → post-read → undo sequence runs under
  a **server-level write mutex** (MCP handlers are multi-threaded; `OntologyAccess.compute` serialises each
  *individual* EDT hop but holds no lock across a sequence), and — because an interactive GUI edit cannot
  take the mutex — an intervening change between apply and re-classification (detected via
  `HistoryManager.getLoggedChanges().size()`) degrades to `report` semantics rather than blind-undoing. The
  post-apply unsatisfiable read uses the manager's current reasoner *after* a completed classification (the
  status-gated verdict stubs out while `OUT_OF_SYNC`). Warm reasoner = 1 classification, cold = 2.
- **`search_entities` is grounding-aware** (additive fields only). Each hit carries a `score` and a
  `match_kind` (exact | prefix | substring | fuzzy — the exact tier considers every `rdfs:label` language
  variant and the IRI local name, case/whitespace/diacritic-folded), and the result adds `best_match` (the
  IRI the query grounds to, or null) and `would_mint` (true when a single-term query grounds to nothing, so
  using it as a `create_*` name would introduce a NEW entity). Ranking is a **separate** builder (it does
  not touch the shared `entityList` shape) with an IRI-string final tiebreak so the finder's `Set` order
  can't leak; `would_mint` keys on the real resolution (`EntityResolver.findEntity` + exact label/local
  name), not the substring finder, and carves out full-IRI / Manchester / multi-word inputs.
- **A re-runnable competency-question suite**: `add_competency_question` / `list_competency_questions`
  / `remove_competency_question` / `run_competency_questions`. A CQ pairs an executable SPARQL query with an
  `Expectation` (the pass condition — `nonEmpty` (default) | `empty` | `count OP N` | `exactRows`); `run`
  re-checks them all against one shared point-in-time snapshot, so an edit that quietly breaks a requirement
  is caught like a failing unit test. CQs are stored via a small **in-package storage SPI** (`CqStore`, not
  a `ServiceLoader` — the same OSGi/TCCL fragility that makes SPARQL reject SERVICE) with three conventions:
  `robot-sparql-dir` (the default writer — a `cqs/` folder of `*.rq` files with header-comment metadata,
  for ROBOT/CI interop), `sidecar-manifest` (a full-fidelity `<basename>-cqs.json` with a `version:1`
  contract, unknown-key preserving, atomic temp→rename), and `ontology-annotations` (CQs stored inside the
  artifact, the fallback when the ontology is unsaved). `list` detects the convention(s), `add`/`remove`
  operate in a chosen one (explicit `convention` wins > single detected > default), and `run` is
  convention-agnostic. Malformed input is isolated (a bad `.rq`/manifest entry is skipped-with-reason, never
  fatal); file/annotation writes go through `checkWriteAllowed`; ids are sanitised so a `.rq` filename can
  never traverse.
- **`verify_ontology`**: run project-defined SPARQL **invariants** (ROBOT `verify` — patterns that must
  never appear; a returned row / ASK true is a *violation*, the inverse of a CQ) over the shared off-EDT
  snapshot, with a `fail_on` severity gate. Invariants must be `SELECT`/`ASK` (a graph-producing
  `CONSTRUCT`/`DESCRIBE` is not a detector and is rejected); a check that **cannot run** — a query error, or
  an `include_inferred` invariant with no classified reasoner — fails **fail-closed** rather than silently
  degrading to the asserted triples and reporting a false pass. Violation rows are reported in the
  `nodeJson` binding shape `sparql_query` already produces — **never** rendered through the EDT-only
  `mm.getRendering`.
- **`run_qc_suite`**: one umbrella gate composing the shipping plain-data cores — `reasoner`,
  `profile`, `structural` (the `validate_ontology` checks), `invariants`, `cqs`, `shacl` (validate against supplied SHACL shapes) — over
  one shared snapshot, collapsed to a single verdict. A stage whose backing data is absent (no classified
  reasoner, no invariants, no CQs, no SHACL shapes) is **skipped with a reason, never an error**; the gate is the
  worst *ran* stage versus `fail_on`.

**Operational safety and transparency.** `save_ontology` maps `.obo` and makes an
*unrecognized* extension a hard error instead of a silent format fallback (extensionless paths still keep
the current format), and takes `all=true` (save every dirty ontology to its own document, file-less ones
reported as skipped); `list_ontologies` reports per-ontology `dirty` + `document` and a `dirty_count`;
`rename_entity` / `delete_entity` / `merge_ontology_document` take `preview=true` (report the exact change
set / blast radius without applying — the same computed change list the apply path uses); `undo_change`
takes `peek=true` (inspect the next-undo transaction via `HistoryManager.getLoggedChanges()`; the redo
stack has no public accessor, so redo stays boolean) and both history tools report `undo_depth`;
`run_reasoner` and the `run_qc_suite` reasoner stage warn when the ontology has SWRL rules the selected
reasoner silently ignores (ELK — surfaced, deliberately not gated); `create_terms` / `create_properties`
take `apply_changes`-style `verify=report|rollback` (the `ApplyVerify` orchestration takes the batch
as an injected applier); and **`explain_inconsistency`** finds a minimal jointly-inconsistent axiom set via
a contraction-only search over a private closure copy — `OWLReasoner.isConsistent()` is the sole oracle
(the one query that does not throw `InconsistentOntologyException`; the clarkparsia black-box generator's
first satisfiability probe throws, and a lenient-config variant seeds only from `owl:Thing` and finds
nothing) — off the EDT and time-bounded (budget expiry returns the still-inconsistent-but-unminimized set,
flagged `minimal=false`). The reasoner-backed query tools now convert `InconsistentOntologyException` into
a pointed error naming `explain_inconsistency`.

| Tool | Mapping / notes |
|---|---|
| `list_ontologies` | `OWLModelManager.getOntologies()` / `getActiveOntologies()` + `getActiveOntology()`; marks the active one; per-ontology `dirty` (`getDirtyOntologies()`) + `document` IRI and a `dirty_count` |
| `get_active_ontology` | `getActiveOntology().getOntologyID()`, axiom/logical-axiom counts, imports. ⚠️ `isActiveOntologyMutable()` is **always true**, so write protection is a plugin-side setting |
| `summarize_ontology` | signature, annotation/import, and axiom-type counts over the active ontology, optionally including imports |
| `list_classes` | `getActiveOntology().getClassesInSignature()` + `OWLModelManager.getRendering(obj)` |
| `search_entities` | `getOWLEntityFinder().getMatchingOWLClasses(...)` and property/individual/datatype variants. A plain fragment is wrapped `*frag*` (the finder otherwise misses substrings); a blank/wildcard-only query falls back to `getActiveOntology().get…InSignature()` (the finder returns nothing for a bare `*`). A separate ranked builder (`EntitySearch`) adds `score`/`match_kind` per hit and top-level `would_mint`/`best_match` (grounding-aware; keyed on `EntityResolver.findEntity` + label/local-name, not the substring finder) |
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
| `undo_change` / `redo_change` | `getHistoryManager().undo()/redo()` (shared with the GUI); both report `undo_depth` (`getLoggedChanges().size()`), and `undo_change peek=true` renders the last logged transaction without undoing |
| `save_ontology` | `OWLModelManager.save()` / `save(ontology)`; save-as picks the format from the extension (unknown extension = error); `all=true` loops `getDirtyOntologies()` (file-less documents skipped-with-reason) |
| `list_reasoners` / `set_reasoner` | `OWLReasonerManager.getInstalledReasonerFactories()` / `setCurrentReasonerFactoryId(id)` |
| `run_reasoner` | `classifyAsynchronously(...)` then `EmbeddedClassificationWaiter` (listener + latch, §4.1 item 7) |
| `get_unsatisfiable_classes` | `getUnsatisfiableClasses()` returns a `Node<OWLClass>`; reported via `getEntitiesMinusBottom()` |
| `get_inferred_superclasses` | superclasses / subclasses / equivalent / types / instances |
| `execute_dl_query` | resolve a Manchester class expression, then `reasoner.getEquivalentClasses/getSubClasses/getSuperClasses/getInstances` (DL Query workbench) |
| `explain_entailment` | `reasoner.isEntailed(axiom)` for a structured axiom |
| `get_explanations` | `com.clarkparsia.owlapi.explanation.DefaultExplanationGenerator.getExplanations(axiom, max)` — minimal justifications, using the selected reasoner's factory; for an axiom type the generator can't convert, falls back to an `isEntailed` check plus the related asserted axioms as structural context (capped) |
| `explain_inconsistency` | contraction-only minimal-inconsistent-subset search over an `isolatedClosure` copy, `OWLReasoner.isConsistent()` as the sole oracle (fresh reasoner per probe, disposed), fast window pass + one-by-one pass, time-bounded off the EDT; renders on the EDT |
| `diff_ontologies` | pure set difference over `getAxioms()` / `getLogicalAxioms()` (optionally imports closures) of two loaded ontologies, or the active ontology vs. a document loaded into a throwaway manager; `identical=true` ⇔ both sides empty (a faithful round-trip) |
| `add_competency_question` / `list_competency_questions` / `remove_competency_question` | upsert / detect+union / remove CQs across the `CqStore` conventions (`robot-sparql-dir` `*.rq`, `sidecar-manifest` JSON, `ontology-annotations`); writes gated by `checkWriteAllowed`, ids sanitised, malformed entries skipped-with-reason |
| `run_competency_questions` | load the matching CQs, build ONE `SuiteSnapshot` (asserted + at most one inferred materialisation), then judge each query off the EDT via `SparqlTools.execute` against its `Expectation` (`nonEmpty`/`empty`/`count`/`exactRows`); per-CQ pass + overall `{passed, failed, gate}`, open-world / truncation caveats surfaced |
| `verify_ontology` | run project SPARQL invariants over the shared off-EDT snapshot — a returned row / ASK true is a violation at the item's severity; violations reported as `nodeJson` bindings (no EDT render); `fail_on` gate |
| `run_qc_suite` | compose the shipping cores (`reasonerVerdict`, `GovernanceTools.profileCheck`, `ValidationTools.analyze`, `Invariants.run`, `CqRunner.run`) over one snapshot; each stage `{ran, verdict, findings_summary}`, absent backends skipped-with-reason, overall `gate` = worst ran stage vs `fail_on` |

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
- The server registers **tools and prompts** (no MCP `resources` capability). The eleven prompts
  (`audit_ontology`, `explain_class`, `add_subclass_safely`, `find_and_fix_unsatisfiable`,
  `author_sparql_query`, `model_domain`, `author_competency_question`, `author_swrl_rule`,
  `refactor_entity_safely`, `bootstrap_ontology`, `release_readiness_check`) are pure templates
  (`prompts/Prompts.java`; the `prompts` package mirrors the `tools` package's registry pattern —
  `PromptRegistry`/`PromptSpecs`/`PromptProvider`/`PromptCatalog`, with `PromptTemplate` as the
  render SAM): each expands to a single user message that drives the tools in a safe order
  (orient → preview → confirm/apply → verify). They touch no model state and run on the transport
  thread.
- **Context / validation / preview tools.** `get_ontology_context` (active-ontology
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
(`HttpServletStreamableServerTransportProvider`, endpoint `/mcp`) bound to the configured bind address
(default `127.0.0.1`). No legacy `/sse` transport is registered.

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
- `packaging=bundle` via `maven-bundle-plugin:5.1.9` (`extensions=true`); `groupId io.github.hakjuoh`,
  `artifactId protege-mcp`, version **`0.5.1`**.
- `Bundle-SymbolicName io.github.hakjuoh.protege-mcp;singleton:=true`; `Bundle-Name "Protege MCP Server"`.
- **Java 17 required:** `maven.compiler.release=17`, and the manifest carries
  `Require-Capability: osgi.ee=JavaSE 17`. The MCP SDK 2.0.0 public types are `record`s (needing
  `java.lang.Record`) and the embedded MCP/Jetty bytecode is Java 17 (class-file major version 61), so Protégé
  must launch on a **Java 17+ JVM**. (There is no Java-11 build path.)
- **Embedding is wholesale:** `Embed-Dependency *;scope=compile;inline=true` + `Embed-Transitive true` +
  `Multi-Release true`. The entire MCP + Jetty + Jackson + Reactor + networknt stack is inlined into the single
  bundle classloader, which avoids runtime `ClassNotFound` from missed reflective/late-bound dependencies
  (Reactor schedulers, json-schema-validator format loaders).
- `Export-Package` is **only** `io.github.hakjuoh.protege_mcp;version=${project.version}`.
- `Import-Package` excludes every inlined library
  (`!com.fasterxml.jackson.*`, `!reactor.*`, `!org.reactivestreams.*`, `!io.modelcontextprotocol.*`,
  `!com.networknt.*`, `!com.ethlo.time.*`, `!org.yaml.snakeyaml.*`, `!org.eclipse.jetty.*`,
  `!jakarta.servlet.*`) and imports `protege/owlapi`, `slf4j[1.7,2)`, `com.google.common.*;[18.0,19)`,
  `javax.swing.*`, `java.awt.*`, the Eclipse registry, and a trailing `*;resolution:=optional` so the large
  inlined transitive surface does not block OSGi resolution.

**Embedded versions:** MCP SDK `mcp-core` + `mcp-json-jackson2` 2.0.0 (the convenience `mcp` artifact is
avoided because it pulls Jackson 3); **Jackson 2.20.1** — every `jackson-*` module is aligned to one release
train by importing **`jackson-bom`** in `<dependencyManagement>` (annotations 2.20), so the transitive
`jackson-dataformat-yaml` tracks it (2.20.1) instead of lagging; `reactor-core` 3.7.0; networknt
`json-schema-validator` 2.0.0 (pulls `jackson-dataformat-yaml` 2.20.1 + `snakeyaml` 2.4 + `itu` transitively);
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
  in memory. The client list is **self-cleaning** (`0.5.0`): when a same-name re-registration completes
  authorization it supersedes the registrations it replaced (a same-name client seen since the newcomer
  registered is kept; one mid-authorization is never touched), a registration that never finishes
  authorizing is swept after an hour of inactivity, and a client silent for 60 days is removed tokens and
  all — the sweep clocks restart on every plugin/broker start, so a stale persisted `lastSeenAt` cannot
  trigger an early reap. Refreshing a token whose client record is gone fails closed with `invalid_grant`.
  This applies to the standalone store and the broker's `~/.protege-mcp/oauth.json` alike.
- **Loopback-HTTPS note:** the server serves **plain HTTP** (loopback by default; `getEndpointUrl` names
  the concrete bind host, `127.0.0.1` for a wildcard bind); there is **no TLS code in the plugin**. The
  "loopback exemption" is the MCP client's own willingness to run OAuth over `http` for a loopback host
  (RFC 8252), not server-side logic — a non-loopback bind is plain HTTP on the network (explicit opt-in,
  red warning in Preferences), and OAuth **authorization** rejects remote peers (403) regardless.
- The platform has no read-only guard (`isActiveOntologyMutable()` is always true), so **read-only mode,
  confirm-each-write, and status display are the plugin's responsibility.**

**Persistence across restarts.** Settings live in the Protégé Preferences store
(`PreferencesManager` set `io.github.hakjuoh.protege_mcp`, group `server`). Persisted: `bearerToken` (generated on first run),
`port`/`autoStart`/`readOnly`/`confirmWrites`, and the OAuth state blob (`KEY_OAUTH_STATE = "oauthState"`) —
registered clients plus access/refresh tokens, as a single user-global JSON string. On restart, `stop()` drops
the in-memory `OAuthStore` (the view shows no clients while stopped) and the next `start()` rehydrates clients +
tokens from `KEY_OAUTH_STATE`, so previously-connected clients stay authorized. The persisted OAuth state is
bounded to the preference-value size limit: `java.util.prefs` caps a single value at 8192 chars and throws
above it, so `persist()` writes under a `MAX_PERSIST_CHARS = 8000` margin — it first purges expired entries,
then evicts the least-recently-seen client (tie-break: oldest registration) until the JSON fits, logging a
warning; evicted clients must re-authorize — a brand-new registration is never chosen to evict itself.

**Multi-window.** With the shared broker on (the default), each window runs a broker-managed backend and the
**broker** owns the configured port across windows and instances (§4.1 item 11); the election below is the
standalone/degraded mode. Each window has its own controller, but `McpServerRegistry` elects a **single owner of the one
process-wide port** (default 8123). A newly opened window **defers** to whichever controller already owns the
port — it stays idle but registered (`startIfNoOwner`) rather than fighting over the bind — and when the owning
window closes, the port is **handed off** to another open window (`promoteSuccessor`), so swapping EditorKits
(e.g. opening a different ontology into an empty window) never tears the server down while a window is still
open. A bind failure is non-fatal: logged and surfaced via the view, not thrown on the EDT. The OAuth state is a
single user-global preference key, effectively shared by whichever window currently holds the port.

> *UI/preferences verified against [`.recon/06-ui-prefs.md`](.recon/06-ui-prefs.md); undo mechanics against
> [`.recon/05-modelmanager.md`](.recon/05-modelmanager.md).*

---

## 9. Architecture Approach B — In-Protégé chat assistant (built)

> *This "B" is the **architecture** axis (the plugin's internal shape). It is unrelated to the README/docs
> **"Distribution Path B"** (Check for plugins, §11), which describes how the jar is delivered. They are
> different things.*

**Status: built.** Approach B is a Swing chat panel — and a dedicated **Ontology Assistant** workspace tab —
inside Protégé where the user converses with an assistant that reads and edits the open ontology.

**As-built decision.** Rather than the
plugin itself calling `api.anthropic.com`, the chat **drives a locally-installed coding-agent CLI** — Claude Code
(`claude`) or OpenAI Codex (`codex`) — as a subprocess, one per user turn, configured to connect **back to this
plugin's own MCP server** (Approach A). Those CLIs are themselves MCP clients, so the agent loop and provider
authentication live inside the CLI; the plugin is a chat UI + subprocess driver. Consequences:

- **Reuses Approach A wholesale.** Edits flow through the same MCP-over-HTTP path into the §5 tools, the
  `OntologyAccess` EDT choke point, the read-only/confirm-write gates, and the shared undo stack — so chat edits
  are indistinguishable from any other MCP client's, appear in the GUI, and undo normally.
- **No API-key custody and no new outbound socket from the plugin.** Each CLI uses the user's *existing* login
  (Claude keychain/subscription; Codex `codex login`). The plugin opens no connection to a model provider and
  stores no provider secret. (The egress is the CLI's, disclosed to the user.)
- **Requires the Approach A server running.** The CLI reaches the tools over loopback HTTP, so `ChatView` starts
  the window's controller (off-EDT) before the first turn.

Why this over the original in-process-bridge + raw-HTTP plan: it eliminates API-key custody, the hand-written
`/v1/messages` SSE loop, the Anthropic tool-bridge, prompt-cache bookkeeping, and provider lock-in, in exchange
for subprocess management — and it lets the user pick whichever agent CLI (and subscription) they already have.
The original design remains a viable direct-API alternative behind the `ChatProvider` SPI (§9.9).

### 9.1 Components (as built)

`io.github.hakjuoh.protege_mcp.chat`:
- **`ChatProvider`** (SPI): `id` / `displayName` / `isAvailable` / `listModels` / `defaultModel` /
  `startTurn(ChatRequest, ChatListener) → ChatProcess`. **`Providers`** enumerates the known providers and which
  are installed.
- **`CliSupport`** — resolves the CLI's absolute path (`$PATH` + well-known install dirs + a per-provider override
  pref, because a Finder/Dock-launched Protégé has a minimal `PATH`), probes `--version`, and `spawn(...)`s the
  process with stdout pumped line-by-line on a daemon worker, stderr drained on a second daemon thread (so a full
  stderr pipe cannot deadlock the stdout reader), stdin closed, and exactly one `completionHandler(exit, stderr)`.
- **`ClaudeCliProvider` / `CodexCliProvider`** — build the headless invocation (§9.3) and parse the CLI's JSONL
  with `ClaudeEventParser` / `CodexEventParser`.
- Records/callbacks: `ChatRequest {model, prompt, sessionId, McpEndpoint, attachments}`,
  `ChatAttachment`, `McpEndpoint {url, token}`, `ChatUsage`, `ChatListener`, `ChatProcess` (cancel handle).

`io.github.hakjuoh.protege_mcp.ui`:
- **`ChatView extends AbstractOWLViewComponent`** (sibling of `McpServerView`) — a streaming transcript
  `JTextPane`, an input box, Send/Attach/Stop, paste/drop attachment handling (long pasted text becomes
  `[Pasted content #N: … chars]`; files/images become placeholders such as `[Image #1]`), a **provider
  drop-down** (`JComboBox`; only installed providers appear), a **model picker** repopulated from
  the active provider, a **"Confirm each edit"** box bound live to the server's `confirmWrites`, a **"Show
  reasoning"** toggle, a per-turn token readout (dollar cost is intentionally not surfaced), and a
  server/egress status line. Borrows the window's
  `McpServerController` via
  `McpServerRegistry.get(getOWLEditorKit())` and starts it off-EDT on the first turn (unless the user's
  explicit Stop has latched the server off — §4.1 item 9). Assistant replies render as **Markdown**
  (`0.5.0`): **`ChatMarkdown`** (commonmark-java, embedded) restyles the in-flight message live as it
  streams — headings, emphasis, code, lists, quotes, GFM tables, links (`http(s)` links open in the
  browser after a confirmation dialog naming the real destination; nothing else is clickable) — and a
  render failure falls back to plain text. Each reply keeps its original Markdown source
  (**`AssistantSegment`**): a per-reply **copy button** under a turn's closing reply and a right-click
  **Copy / Copy message as Markdown** transcript menu put the untouched markup on the clipboard.
- **`ChatPreferencesPanel`** — per-provider CLI path overrides + a reset for the one-time egress consent. No
  API-key field.

### 9.2 The provider seam

`ChatProvider` is the SPI the design always anticipated, now with **CLI-backed** implementations rather than a
single Anthropic-API one. The UI and the subprocess driver are provider-agnostic; a future direct-API provider,
or user-configured external MCP servers, can slot in behind it without touching either.

### 9.3 CLI invocation contracts (locked from `--help` + a captured run)

**Claude** (reuses the existing keychain/OAuth login — `--bare` is deliberately *not* used, as it would disable
keychain/OAuth):
```
claude -p --output-format stream-json --include-partial-messages --verbose \
  --strict-mcp-config \
  --mcp-config <path-to-owner-only-config-file> \
  [--thinking-display summarized] \
  --allowedTools mcp__protege  [--add-dir <attachment-dir>...]  [--model <alias>]  [--resume <session-id>]  -- "<prompt>"
```
`--strict-mcp-config` means the run sees *exactly* Protégé's server; `--allowedTools mcp__protege` pre-approves
the whole server so the non-interactive run never blocks on a permission prompt (server-side gates still apply).
The `--mcp-config` value is the **path** to a `0600` temp file holding the
`{"mcpServers":{"protege":{"type":"http","url":"<endpointUrl>","headers":{"Authorization":"Bearer <token>"}}}}`
JSON — written per turn and deleted when the process exits — so the bearer token is **not** placed on the argv
(where `ps` could read it); Codex passes its token by env var for the same reason.

**Codex** (reuses `codex login`; the bearer token travels via env var, never argv):
```
PROTEGE_MCP_TOKEN=<token>  codex \
  -c approval_policy="never" -c sandbox_mode="read-only" \
  -c mcp_servers.protege.url="<endpointUrl>" \
  -c mcp_servers.protege.bearer_token_env_var="PROTEGE_MCP_TOKEN" \
  -c mcp_servers.protege.default_tools_approval_mode="approve" \
  [-c model_reasoning_summary="detailed"] \
  exec [resume <session-id>] --json --skip-git-repo-check  [-m <model>]  [--image <image-path>]...  -- "<prompt>"
```
`codex mcp add --help` confirms `--url` + `--bearer-token-env-var` for streamable-HTTP servers. `-s/--sandbox`
is unavailable on `exec resume`, so sandbox/approval are set via global `-c` overrides (valid for both a fresh
`exec` and `exec resume`); `read-only` keeps the local filesystem read-only while MCP tool calls still run (all
editing is via the MCP server). MCP tool approval is a **separate** gate from `approval_policy` (which governs
only shell/exec): the per-server default is `required`, so a headless run with no one to approve would
auto-cancel every tool call ("user cancelled MCP tool call") — hence the required
`-c mcp_servers.protege.default_tools_approval_mode="approve"` override (server-side read-only / confirm-write
gates still apply to actual edits).

The bracketed reasoning flag on each provider (`--thinking-display summarized` / `-c
model_reasoning_summary="detailed"`) is added **only while "Show reasoning" is ticked** — current CLIs
stream no reasoning text unless explicitly asked, so the toggle opts in per turn on the CLI side (an
older Claude CLI that does not know the flag fails the turn with "unknown option"; unticking sends
without it).

The prompt is passed as a single argv element after `--` (no shell), so its content cannot break the command
line. When attachments are present, the transcript keeps compact placeholders while `ChatRequest.providerPrompt()`
appends the pasted text and local file paths for the CLI. Each attached file/image is **copied into its own
owner-only scratch subdir** (under the neutral working dir) and referenced there, so granting read access never
exposes the user's real folder: Codex image attachments are passed through its native `--image` flag; Claude
receives each attachment's isolated scratch dir via `--add-dir` (one file per dir). A **large pasted body** is
written to a scratch `.txt` and referenced by path rather than inlined, so no paste can overflow the single-argv
command line. Scratch dirs are reclaimed when the turn completes (and on New Chat / view close). The model picker
offers Claude aliases (opus/sonnet/haiku/fable) and common Codex ids and is **non-editable** (users pick from
the provider's fixed list — a macOS Aqua editable combo rendered taller and misaligned against the
non-editable provider picker); `""` (shown as `(default)`) means "the CLI's own
default model". A non-blank session id resumes the conversation (Claude `--resume`; Codex `exec resume <thread-id>`).

### 9.4 Event parsing (captured schemas)

Both CLIs stream line-delimited JSON; each parser maps a line to `ChatListener` callbacks and ignores unknown
event/item types so a newer CLI degrades gracefully.
- **Claude** (`stream-json`): `system`/`init` → session id; `stream_event` `content_block_delta`
  `text_delta`/`thinking_delta` → streamed text / reasoning; `content_block_start` with a `tool_use` block → tool
  activity (the `mcp__protege__<tool>` prefix is stripped); `result` → session id + `usage` + `total_cost_usd`.
  Text is taken **only** from the streamed deltas (the roll-up `assistant` event is ignored), so it is never
  double-counted.
- **Codex** (`--json`): `thread.started` → session id (the thread id used to resume); `item.started` /
  `item.updated` / `item.completed` items of type `agent_message` (text — emitted incrementally by tracking the
  per-item already-emitted length, so it works whether updates stream or arrive only on completion), `reasoning`,
  `mcp_tool_call`, `command_execution`, `file_change`, `web_search`, `error` (each non-text item reported as tool
  activity); `turn.completed` → `usage` (Codex reports no dollar cost); `turn.failed` → error. Unknown event/item
  types are ignored.

### 9.5 Threading and EDT safety

Mirrors `McpServerView`'s proven pattern. `initialiseOWLView`/`disposeOWLView` and all Swing work run on the EDT.
Each turn spawns a launcher daemon thread that starts the server if needed (off-EDT) and spawns the CLI;
`CliSupport` pumps stdout on the `protege-chat-agent` worker. Streamed callbacks (on the worker) enqueue styled
chunks onto a thread-safe deque; a 40 ms Swing `Timer` drains it onto the EDT, **batching** inserts so a burst of
events cannot flood the EDT. `onComplete` sets a volatile exit code; the drain finalizes the turn (re-enable
Send, render usage) only **after** the queue empties, so the transcript tail is never dropped. Stop and
`disposeOWLView` cancel the process (`destroy` → `destroyForcibly`) with a bounded join.

### 9.6 Security and egress

- **Egress is the CLI's, disclosed once.** The chat sends the user's prompts, attachments/pasted content, and
  the ontology content the assistant reads to the user's model provider *via the CLI*. A one-time consent banner
  (`KEY_CHAT_CONSENTED_V2` — re-versioned when the disclosure grew to name attachments, so prior users
  acknowledge the wider scope once) plus a persistent status-line note cover it (README documents it). The plugin
  opens **no** new outbound socket and stores **no** provider API key.
- **Attachments are isolated, not folder-wide.** Each attached file/image is copied into its own owner-only
  scratch dir and only that dir is exposed (Claude `--add-dir`) or the copy passed (Codex `--image`), so the
  provider never receives the user's surrounding folder. Files over a size cap are refused; scratch dirs are
  deleted when the turn ends and on New Chat / view close.
- **The MCP bearer token never lands on a command line.** Claude receives it via a `0600` `--mcp-config`
  **file** (passed by path, deleted when the turn ends); Codex receives only the env-var *name* on argv and the
  value via the `PROTEGE_MCP_TOKEN` environment variable. Neither exposes the token to `ps` / other local users.
- **Gates inherited, not re-implemented.** Edits go through the MCP server → `WriteTools` gates, so the read-only
  switch and the confirm-each-write modal apply unchanged and the chat **cannot** escalate past them. The panel's
  "Confirm each edit" checkbox toggles the server's `confirmWrites` live.
- **macOS PATH.** A Finder/Dock-launched Protégé often lacks the user's shell `PATH`;
  `CliSupport.resolveExecutable` searches `PATH` + well-known install dirs, and the preferences panel exposes a
  per-provider path override. The CLI's own login authenticates to the provider — a first run may surface a
  keychain prompt.

### 9.7 UI surface

A droppable **Ontology Assistant** `ViewComponent` *and* a dedicated **Ontology Assistant** `WorkspaceTab` (reusing
`org.protege.editor.owl.ui.OWLWorkspaceViewsTab` + `viewconfig-ontologyassistanttab.xml`, which references the view by its
fully-qualified id `io.github.hakjuoh.protege-mcp.ChatView`). Provider selection is a drop-down (`JComboBox`) in the composer;
the model picker repopulates from the chosen provider; the input box supports long-paste compaction plus
file/image paste, drag/drop, and Attach; a per-turn token readout (dollar cost is intentionally not surfaced)
and the server/egress status line keep state visible.

### 9.8 Packaging (Architecture Approach B)

**No new embedded jars and no new mandatory `Import-Package`.** The driver uses `java.lang.ProcessBuilder` +
`java.io`, and JSONL parsing reuses the already-embedded Jackson `ObjectMapper`. The new code stays under
bundle-internal `io.github.hakjuoh.protege_mcp.*` (`Export-Package` stays `io.github.hakjuoh.protege_mcp` only); `plugin.xml` gains a
`ViewComponent`, a `WorkspaceTab`, and a `preferencespanel`. Java 17 stays required. This is markedly lower
packaging risk than the original raw-HTTP plan (which would still have been fine) and far lower than the
SDK-embedding alternative.

### 9.9 Deferred (behind the `ChatProvider` SPI)

A **direct-API** provider (`api.anthropic.com` with an in-process tool-bridge, a hand-written `/v1/messages` SSE
loop, API-key custody, and prompt caching) — the original design, now superseded by the CLI-driven approach and
kept only as a viable **fallback** behind the SPI: building it would re-introduce the API-key custody and
provider lock-in that the CLI-driven approach deliberately removed, so it is a fallback, not a roadmap goal. And
user-configured **external** MCP servers exposed to the chat — a config + egress-consent feature (the single
loopback `protege` server is currently baked into each provider's per-turn config, and today's one-time consent
banner covers only that server), not a one-line config change.

*Not deferred (resolved):* the `fable` model alias already ships in the picker (`ClaudeCliProvider.listModels`
returns `opus`/`sonnet`/`haiku`/`fable`); and long-context compaction is **owned by the CLI's own agent loop** —
the plugin forwards only a prompt + session id and the CLI reconstitutes and auto-compacts the conversation — so
it is out of scope for the plugin rather than a deferred plugin task.

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

The same `Bundle-SymbolicName io.github.hakjuoh.protege-mcp;singleton:=true` and the OSGi/version rules in §7 govern both
paths; `docs/check-for-plugins.md` covers the version-comparison rules that decide whether "Check for plugins"
offers an update.

---

## 12. Roadmap

**Architecture Approach A — delivered.**

| Phase | Scope | Status |
|---|---|---|
| **0. Spike / packaging** | `protege-mcp` bundle; embed the MCP stack + Jetty 12 ee10; clean load, `plugin.xml` recognized, zero Jackson/Guava conflict | ✅ done |
| **1. Lifecycle / transport** | `McpServerHook` start/stop (EDT-cheap), `/mcp` reachable | ✅ done |
| **2. Read tools / EDT bridge** | `OntologyAccess` + 6 read tools | ✅ done |
| **3. Write tools / Undo** | 9 write tools via `applyChange(s)`; GUI round-trip + undo | ✅ done |
| **4. Reasoner tools** | 4 reasoner tools + `EmbeddedClassificationWaiter` | ✅ done |
| **5. Security / settings** | **Expanded beyond the original MVP:** embedded OAuth 2.1 AS + static bearer, persistence across restarts, preferences | ✅ done |
| **6. Status UI / multi-window / docs** | `McpServerView` with the connected-clients table + per-client revocation; per-window controllers | ✅ done |
| **7. Shared broker** | one fixed MCP endpoint across windows and instances (§4.1 item 11); singleton lock, staged jar copies, refcount self-exit, session-pinned proxy | ✅ done |
| **M0 contract subset** | 0.5.0 public-contract goldens; policy v1/schema examples; surface-neutral project, revision, finding, stage, artifact, and strict gate contracts. Workspace APIs and order-stable fingerprints remain in PLAN §6.3–6.4. | ◐ foundations delivered (0.5.1); M0 incomplete |
| **8. Hardening** | deadlock/perf/OSGi regression coverage across all tool paths | ⏳ ongoing |

**Architecture Approach B — delivered.** The in-Protégé chat assistant (§9): a `ChatView` + dedicated
**Ontology Assistant** tab, the `io.github.hakjuoh.protege_mcp.chat` provider/driver layer (Claude Code and Codex CLIs), and the
event-stream parsers — built atop A's tool layer with no new embedded jars and no API-key custody.

The M0 records and schemas are intentionally not wired into public tools yet. Policy discovery/execution,
fingerprinting, preflight change sets, locked imports, verified release artifacts, and the headless adapter
remain in [`PLAN.md`](PLAN.md); see the [policy-contract status page](docs/project-policy.md) for the precise
0.5.1 boundary.

**Next tracks:** the §9.9 items behind the `ChatProvider` SPI (a direct-API provider — kept only as a fallback,
not a roadmap goal — and user-configured external MCP servers) and Architecture Approach C (§10). (The `fable`
model alias already ships, and long-context compaction is owned by the CLI's agent loop — see §9.9.)

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
- **Multi-window / multi-instance endpoint** — the shared broker owns one fixed port across windows and
  instances (§4.1 item 11), singleton-guarded by `~/.protege-mcp/broker.lock` and self-exiting on refcount
  zero; a broker outage flips the view to a one-click relaunch and each window keeps serving directly. The
  standalone/degraded fallback shares a single process-wide port via owner election + hand-off
  (`McpServerRegistry.startIfNoOwner` / `promoteSuccessor`, ephemeral-port fallback on a busy port). The
  residual caveat is that broker OAuth state (`~/.protege-mcp/oauth.json`) has no in-view revocation UI yet,
  and the standalone store keeps a single user-global OAuth blob shared by whichever window holds the port.
- **OAuth state size bound** — the `java.util.prefs` 8192-char cap; standalone persistence stays under
  `MAX_PERSIST_CHARS = 8000` via purge-expired + least-recently-seen client eviction (a brand-new
  registration is never chosen to evict itself); evicted clients must re-authorize; per-client `lastSeenAt`
  / `staticTokenLastSeen` can read stale right after a restart (the self-cleaning sweep clocks restart on
  every start, so a stale timestamp cannot trigger an early reap).
- **Loopback OAuth over plain HTTP** — no TLS in the plugin; relies on the client's loopback-http exemption.
  A non-loopback bind (the bind-address preference) is plain unencrypted HTTP on the network — deliberate,
  warned-about opt-in; OAuth authorization stays same-machine-only to keep consent local.
- **`mcp-remote` dependency** — stdio-only clients (e.g. Claude Desktop) need Node/`npx`.
- **Architecture Approach B risks (as built)** — egress of ontology content + prompts to the user's model
  provider happens **via the local CLI** (disclosed by a one-time consent banner; the plugin itself stores no API
  key and opens no new socket); the chat **requires** a working `claude`/`codex` install + login and a running
  Approach A server (the panel starts it, unless the user has latched it off with Stop); a Finder/Dock-launched Protégé may not have the CLI on `PATH`
  (mitigated by well-known-dir search + a path-override pref); and the CLIs' JSONL event schemas can drift across
  versions (mitigated by tolerant parsers that ignore unknown event types). Autonomous multi-edit volume is
  bounded by the inherited read-only / confirm-each-write gates.

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

### Appendix B. As-built plugin source (`io.github.hakjuoh.protege_mcp.*`)

| Class | Role |
|---|---|
| `McpServerHook` | `EditorKitHook` — per-window lifecycle owner; registers in `McpServerRegistry`; auto-starts off-EDT |
| `server/McpServerController` | lifecycle + runtime state; `start/stop/restart` off-EDT; wires filter/transport/OAuth servlets onto Jetty |
| `server/McpServerRegistry` | static `EditorKit → controller` map; elects the single port owner across windows (`startIfNoOwner` / `promoteSuccessor`) |
| `server/ManagedServer` | minimal interface (`isRunning`/`start`) implemented by `McpServerController`; lets the registry elect the single port owner and hand it off (a fake substitutes in tests) |
| `server/McpServerManager` | builds the MCP sync server + Streamable-HTTP transport; explicit JSON mapper + validator |
| `server/EmbeddedHttpServer` | embedded Jetty 12 ee10, configured bind address (default `127.0.0.1`) + loopback alias |
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
| `prompts/PromptCatalog`, `PromptSpecs`, `PromptProvider`, `PromptRegistry`, `PromptTemplate` | prompt aggregation, spec factory, provider SAM, fluent sink, render SAM (mirrors the tools registry pattern) |
| `prompts/Prompts` | the guided-workflow prompt templates (§5) |
| `contracts/*` | versioned project/revision/finding/stage/artifact/gate records and fail-closed pure aggregation; JSON schemas live under `src/main/resources/schema` |
| `config/McpConfig` | preferences snapshot (`io.github.hakjuoh.protege_mcp` / `server`); token + OAuth state keys |
| `ui/McpServerView`, `ui/McpPreferencesPanel` | status view (connected-clients table) + settings panel |
| `broker/McpBoot` | the single boot policy — broker-first, degrading to the standalone election + port fallback (§4.1 item 11) |
| `broker/BrokerMain`, `broker/BrokerServer` | the standalone broker process entry point + its HTTP host (reuses `EmbeddedHttpServer`, `AccessTokenFilter`, the OAuth stack; persists to `~/.protege-mcp/oauth.json`) |
| `broker/BrokerSpawner`, `broker/BrokerHome` | probe/spawn (staged jar copies under `~/.protege-mcp/jars/`, `broker.lock` singleton arbitration) + the `~/.protege-mcp` (`0700`) layout |
| `broker/McpProxyServlet` | byte-for-byte `/mcp` streaming proxy; session pinning (`Mcp-Session-Id`); routes new sessions to the most recent window |
| `broker/InstanceRegistry`, `broker/InternalApiServlet` | registered-window bookkeeping (heartbeats, pid reaper, refcount exit) + the owner-secret `/internal/*` control plane |
| `broker/BrokerClient`, `broker/BrokerLink`, `broker/BrokerState`, `broker/BrokerTokenFilter` | plugin-side broker RPC, a window's registration/heartbeat loop, on-disk broker descriptor, per-window backend auth |
