# Protégé MCP — Current Design

> Architecture snapshot for release 0.7.2. User-facing installation and operation are documented at
> <https://hakjuoh.github.io/protege-mcp/>. Historical feature delivery is recorded in
> [`CHANGELOG.md`](CHANGELOG.md); unimplemented work is tracked in [`PLAN.md`](PLAN.md).

## 1. Purpose and boundaries

Protégé MCP exposes the ontology currently open in Protégé Desktop to MCP clients, while preserving the
editor's model, renderer, reasoner configuration, and Undo history. The distribution also contains an
in-Protégé Ontology Assistant and a headless CLI for reproducible project validation and release work.

The design has three execution surfaces:

- The **live plugin** serves 85 MCP tools and 11 prompts over authenticated Streamable HTTP. Reads and writes
  operate on the active `OWLModelManager`; ontology changes are visible immediately and join Protégé's Undo
  stack, except for the explicit document-format prefix-map operations described in section 7.3.
- The **Ontology Assistant** drives an installed `claude` or `codex` CLI back through the live plugin's MCP
  endpoint. Protégé stores no provider API key.
- The **headless CLI** loads a policy-defined project from disk, runs the shared QC/release services, and can
  expose a deliberately small project-confined MCP subset over stdio. It has no GUI or Undo stack.

This is a local desktop product. It is not a multi-tenant ontology server, general RDF database, remote
collaboration system, or vendor knowledge-graph adapter. Those boundaries are intentional, not incomplete
parts of the current architecture.

## 2. Repository and artifacts

The Java 17 Maven reactor has three modules:

| Module | Artifact | Responsibility |
| --- | --- | --- |
| `core` | `protege-mcp-core-0.7.2.jar` | Protégé-free contracts, policy, authorization metadata, audit primitives, OWL/QC/diff/release services, and headless workspace abstractions |
| `plugin` | `protege-mcp-0.7.2.jar` | OSGi bundle: Protégé lifecycle/UI adapters, live tools/prompts, HTTP/OAuth server, shared broker, and Ontology Assistant |
| `cli` | `protege-mcp-cli-0.7.2-all.jar` | Executable shaded CLI with OWLAPI, HermiT, headless workspace, release commands, and bounded stdio MCP server |

The current bundle version **`0.7.2`** and MCP server identity `SERVER_VERSION=0.7.2` are checked by
`scripts/check-version-consistency.sh` together with the POMs, plugin descriptor, CLI, and documentation.

The dependency direction is:

```text
                        plugin (Protégé / Swing / HTTP)
                       /                              \
external MCP clients ─┘                                └─ local agent CLI
                                  |
                                core
                                  |
                         cli (filesystem / stdio)
```

`core` has no Protégé runtime dependency. The plugin adapts shared contracts to a live editor workspace;
the CLI adapts them to captured filesystem snapshots. Code moves into `core` only when both surfaces can use
the same semantics without importing EDT, Undo, servlet, or vendor-specific concerns.

## 3. Runtime topology

### 3.1 Live MCP path

The normal request path is:

```text
MCP client
  -> shared broker at the configured host/port
  -> session-pinned per-window backend on loopback
  -> MCP transport context with AuthenticatedPrincipal
  -> ToolRegistry authorization / audit / revocation fence
  -> tool handler
  -> OntologyAccess EDT boundary
  -> OWLModelManager
  -> Protégé listeners, renderer, and Undo history
```

The default client endpoint is `http://127.0.0.1:8123/mcp`. The host and port are configurable; port `0`
requests an ephemeral port.

### 3.2 Ontology Assistant path

```text
Ontology Assistant view
  -> Claude Code or Codex CLI subprocess
  -> this window's MCP backend with a short-lived turn principal
  -> the same tool registry and live ontology path as an external client
```

The Assistant does not bypass MCP handlers or call the model API directly. Consequently, read-only mode,
write confirmation, tool capabilities, revision checks, audit, and revocation apply exactly as they do to
external clients.

### 3.3 Headless path

```text
CLI command or stdio MCP client
  -> HeadlessToolService / shared project services
  -> FilesystemProjectWorkspace capture
  -> offline OWLAPI + bundled HermiT execution
  -> checksum-guarded file or release-directory transaction
```

The headless workspace fixes the project at server or command start. Callers cannot replace its policy path,
inject an ontology manager, escape the project root, or enable network import resolution.

## 4. Protégé integration

`plugin/src/main/resources/plugin.xml` registers six extensions:

| Extension | Implementation | Role |
| --- | --- | --- |
| `EditorKitHook` | `McpServerHook` | Creates and disposes one controller per `OWLEditorKit` |
| `ViewComponent` | `McpServerView` | Server status, endpoint/token controls, clients, start/stop, and revocation |
| `preferencespanel` | `McpPreferencesPanel` | Port, bind address, broker, read-only, confirmation, and auto-start settings |
| `ViewComponent` | `ChatView` | Droppable Ontology Assistant view |
| `WorkspaceTab` | `ChatTab` | Dedicated Ontology Assistant tab |
| `preferencespanel` | `ChatPreferencesPanel` | CLI paths, provider/model settings, and egress-consent controls |

The bundle symbolic name is `io.github.hakjuoh.protege-mcp;singleton:=true`. `plugin.xml` is at the JAR root,
as required by Protégé's extension registry.

### Lifecycle rule

`McpServerHook.initialise()` and `dispose()` are not assumed to run on the Swing EDT; the exact thread depends
on the Protégé boot path. The hook therefore touches neither the workspace nor Swing directly. It registers
the controller immediately and starts/stops server processes on daemon threads. Disposal performs a bounded
join and can promote another open window when standalone mode loses its port owner.

Each `OWLEditorKit` has its own `McpServerController`, `ToolContext`, revision tracker, change-set store,
search/SPARQL caches, audit workspace id, and backend endpoint.

## 5. HTTP server and shared broker

### 5.1 Per-window server

`McpServerController` owns one server start:

- It snapshots restart-required settings while read-only, confirm-writes, and the static token remain live
  preference reads.
- It pins the thread-context classloader while MCP, JSON Schema, Jena, and Jetty initialize under OSGi.
- It warms Jena before accepting concurrent requests.
- It builds the tool catalog, MCP sync server, OAuth store, authentication filters, and Jetty servlets.
- It binds a broker-managed backend to an ephemeral loopback port, or a standalone server to the configured
  address with ephemeral fallback if the requested port is busy.
- Stop releases MCP, HTTP, OAuth-persistence, model-listener, cache, revision, and change-set resources.

`McpServerManager` uses MCP Java SDK 2.0.0 and a Jakarta Servlet Streamable-HTTP transport at `/mcp`. It
constructs the Jackson mapper and JSON Schema validator explicitly because service discovery is unreliable
across the OSGi bundle boundary. Handlers run on transport threads; live model access is marshalled separately
through `OntologyAccess`.

`EmbeddedHttpServer` is a Jetty 12 ee10 host. The OAuth discovery and authorization endpoints are public as
required by the protocol; `/mcp/*` is always authentication-filtered.

### 5.2 Broker mode

Shared-broker mode is enabled by default. A small process owns the client-facing host/port across Protégé
windows and application instances, while every window serves an ephemeral loopback backend.

The broker:

- Is singleton-guarded by an OS file lock under `~/.protege-mcp/broker.lock`.
- Is discovered through an atomically written state file and an authenticated internal liveness call.
- Registers window backends, consumes heartbeats, removes dead processes, and exits after the configured
  linger once no Protégé instance remains.
- Proxies MCP bytes and streaming responses without interpreting tool payloads.
- Pins an `Mcp-Session-Id` to the backend that created it; new sessions select a live window.
- Authenticates clients itself, then forwards a secret-free `AuthenticatedPrincipal` plus a per-window
  backend secret. Client-supplied internal headers are not trusted.
- Fans client revocation out to backends so pinned sessions and in-flight execution leases can be fenced.

The spawned broker runs from content-addressed staged JAR copies under `~/.protege-mcp/jars/`. This avoids
holding the installed plugin JAR open during updates, particularly on Windows.

### 5.3 Standalone fallback

If broker use is disabled or the broker cannot be reached or spawned, `McpBoot` falls back to per-process
standalone mode. `McpServerRegistry` elects a configured-port owner across windows and promotes a successor
when it closes. A window that must serve the Assistant immediately can bind an ephemeral fallback port rather
than fail because another process owns the configured port.

An explicit Stop in the server view latches that window off. Auto-start, broker recovery, and the Assistant do
not silently override the latch; the user must press Start.

## 6. Authentication, authorization, and audit

### 6.1 Client authentication

The live HTTP surface accepts:

- OAuth authorization-code flow with PKCE, dynamic client registration, access/refresh tokens, discovery,
  and revocation.
- A generated 256-bit static bearer token for local-admin compatibility and manual clients.
- The internal per-window broker credential, accepted only by broker-managed backends.

OAuth and static-token identities are normalized to versioned `AuthenticatedPrincipal` values before MCP
dispatch. Existing `mcp` scope grants map to the compatibility local-admin profile; explicit `read` grants
map only to ontology read. Public capability names cover ontology read/curate/admin/release, project
filesystem read/write, network access, and server administration.

### 6.2 Tool boundary

Every built-in tool has a declaration in `ToolCapabilityCatalog`. `ToolRegistry` refuses to build unless the
capability catalog, JSON metadata catalog, and registered handlers have exactly the same names.

Each call crosses one common wrapper in this order:

1. Recover the authenticated principal from the MCP transport context.
2. Check all required capabilities.
3. Acquire a principal execution lease so revocation can fence later commit points.
4. Append a redacted `started` or `denied` audit event.
5. Run the handler.
6. Append the structured success/failure, gate, commit, confirmation, and release references.

Authorization denial never reaches a handler. The global read-only setting remains a hard ceiling even for a
principal that holds mutation capabilities. Confirm-each-write is an additional interactive gate and fails
closed if no Swing confirmer is available.

### 6.3 Audit storage

Runtime audit streams are owner-only JSON Lines files under
`~/.protege-mcp/audit/<project-hash>/`, separated by workspace. Rotation and retention are bounded and may be
configured by valid project policy. Entries contain actor/capabilities, operation, ontology/module target,
outcome, gate/commit summary, confirmation references, and release-manifest reference; they exclude tokens,
prompts, attachments, arbitrary arguments, ontology content, and result bodies.

`export_audit_log` requires server-admin plus project read/write capabilities, a valid project policy, and
explicit write confirmation. It writes a deterministic project-contained review artifact rather than
exposing the owner-only runtime directory directly.

## 7. Threading, consistency, and mutation safety

### 7.1 EDT boundary

All access to the live `OWLModelManager`, renderer, reasoner manager, and related Protégé state passes through
`OntologyAccess.compute`:

- On the EDT, the operation runs inline to avoid self-deadlock.
- Off the EDT, it is queued with `invokeLater` and awaited through a bounded latch (30 seconds by default).
- Timeout or interruption marks the queued operation cancelled so it cannot later mutate the model.
- Exceptions become structured MCP failures through the shared tool guard.

Long-running classification does not block the EDT. `EmbeddedClassificationWaiter` starts Protégé's
asynchronous classification and waits off-EDT for the classification event before reading the final reasoner
status.

### 7.2 Workspace revisions

The live adapter tracks a per-window revision envelope containing the workspace id, monotonic edit version,
and semantic/document fingerprints. Closure changes, policy/import-lock identity, and the applicable
operation determine whether a preview or captured result is still current.

`ToolContext` also owns a server-wide write mutex. It prevents two multi-hop MCP write pipelines from
interleaving with each other; GUI edits cannot take that lock, so every multi-hop guarded write still performs
a final revision and policy recheck immediately before commit.

### 7.3 Preferred write path

For axiom and batch curation changes, the transactional path is:

```text
get_model_revision
  -> preview_change_set (normalize + isolated apply + configured gates)
  -> human/agent review
  -> commit_change_set(change_set_id, complete expected_revision)
  -> final policy/revision/auth/confirmation checks
  -> one live applyChanges call and at most one Undo entry
```

A failed preview does not touch the live ontology. `rebase_change_set` creates a new reviewed candidate;
it does not reuse a stale preview or overwrite an intervening edit. `discard_change_set` removes unused
server-side state.

Direct write tools and `apply_changes` remain supported for compatibility and high-level operations without a
change-set equivalent. They still enforce grounding, read-only, confirmation, authorization, audit, and their
documented validation or rollback behavior.

`set_prefix` and `remove_prefix` are deliberate document-format exceptions to the Undo-backed mutation path.
They update the active ontology's `PrefixDocumentFormat` on the EDT, explicitly invalidate the SPARQL snapshot
cache, and do not produce an `OWLOntologyChange` or Undo entry. Because OWLAPI 4.x has no remove-by-prefix-name
operation, `remove_prefix` rebuilds the map without the requested binding while preserving other aliases and
the standard prefixes. Both operations remain subject to the ordinary write gates.

## 8. Shared ontology-engineering core

The `core` module groups reusable semantics by responsibility:

- `contracts`: immutable revision, fingerprint, finding, stage, gate, artifact, and project-coordinate
  records plus canonical JSON handling.
- `policy`: strict policy-v1 loading, schema/semantic validation, project-root confinement, search policy,
  reasoner naming, module inspection, and RO-Crate settings.
- `core.workspace`: captured project snapshots, offline catalog/import-lock resolution, fingerprints, and
  checksum-guarded single-file or release-directory transactions.
- `core.qc`: structural, governance, invariant, CQ, SHACL, import-graph, and complete project-QC services.
- `core.diff` and `core.impact`: asserted/inferred semantic diff and syntactic impact analysis.
- `core.release`: release gates, deterministic bundles/manifests, RO-Crate metadata, artifact storage, and
  JSON/Markdown/JUnit/SARIF reports.
- `core.auth` and `core.audit`: capability declarations/authorization and redacted audit persistence/export.
- `core.headless`: the fixed stdio catalog and orchestration used by the CLI.
- `ro_crate`: host-independent RO-Crate project-profile parsing and validation.

The canonical authoring policy is `<project>/.protege-mcp/project.yaml`; its parent is the project root.
Policy discovery, asset resolution, imports, catalogs, locks, QC, and release outputs use canonical paths and
reject project escapes and unsafe symlink resolution. Release mode and the headless workspace resolve imports
from captured local documents and do not silently fetch mutable network resources.

Fingerprint v2 is the optimistic live/project revision token. W3C RDFC-1.0 + SHA-256 is a separate portable
identity for an asserted RDF dataset. Import artifact checksums are separate again. The design never presents
one identity as interchangeable with another.

## 9. Tool and prompt surface

The plugin catalog contains **85 tools and 11 prompts**. The authoritative metadata is the validated resource:

`plugin/src/main/resources/io/github/hakjuoh/protege_mcp/catalog/mcp-catalog.json`

It supplies every name, description, JSON input schema, and prompt argument. `ToolCatalog` registers handlers
from focused providers; `PromptCatalog` registers the prompt templates. Tests pin the published baseline and
allow only reviewed additive compatibility drift.

Every tool returns a structured JSON object as MCP `structuredContent` and mirrors it as a serialized text
block for clients that only render content. Invalid arguments and expected operational refusals are returned
as structured tool errors; unexpected server failures are logged without leaking secrets.

The complete user-facing inventory is maintained in [`docs/tools/index.md`](docs/tools/index.md) and
[`docs/prompts.md`](docs/prompts.md), rather than duplicated here.

## 10. Ontology Assistant

The Assistant uses the `ChatProvider` SPI with two current implementations:

- `ClaudeCliProvider` invokes the installed `claude` CLI.
- `CodexCliProvider` invokes the installed `codex` CLI.

Provider event parsers tolerate unknown JSONL event types and translate text, reasoning, tool activity,
errors, and usage into a common stream. A worker pumps subprocess output into a thread-safe queue; a Swing
timer batches transcript updates on the EDT. Stop and view disposal terminate the process with bounded
cleanup, and a turn is finalized only after queued output is drained.

Each turn uses a bounded, short-lived principal tied to its window. Read-only Assistant mode receives only
ontology-read capability; write-enabled mode receives the bounded ontology/project capabilities required by
the built-in workflow, still subject to server policy and confirmation. Provider steering directs ordinary
axiom work through preview/commit change sets and forbids bypassing a failed gate or revision conflict.

Prompts, attachments, and ontology content read by the agent may leave the machine through the selected
CLI's model provider. The UI discloses this egress and records consent. Attachments are copied into isolated
owner-only scratch directories, size bounded, exposed without their surrounding folders, and deleted after
the turn. The MCP credential is passed through an owner-only config file or environment variable, not as a
secret command-line argument.

The CLI handles model authentication, conversation state, and context compaction. The plugin stores neither
provider API keys nor a duplicate model conversation engine.

## 11. Headless CLI and stdio MCP

The executable CLI supports:

- `validate-policy`
- Full project `validate` with JSON, Markdown, JUnit, or SARIF output.
- Deterministic `imports lock` generation.
- Verified `release` gate/preparation.
- Asserted ontology or verified release-manifest `diff`.
- `serve --transport stdio --project FILE`.

Exit codes are stable: `0` passed, `1` a loaded validation/gate failed, `2` configuration or usage error, and
`3` execution or infrastructure error.

The stdio surface contains eight tools:

- `get_headless_capabilities`
- `validate_project_policy`
- `run_project_qc`
- `verify_import_lock`
- `write_import_lock`
- `run_release_gate`
- `prepare_release`
- `export_audit_log`

It reports the live-only tools that are unavailable. The policy/project is fixed at startup; requests are
offline and project-confined, writes default to dry-run where applicable, and the transport bounds newline-
delimited inbound messages to 1 MiB and outbound messages to 8 MiB.

`FilesystemProjectWorkspace` captures policy, root ontology, modules, catalogs, imports, locks, and validation
assets into a private temporary workspace. It verifies source identity before and after capture and before
publication. File writes use checksum-guarded atomic replacement with optional backup; release publication
uses a guarded whole-directory transaction.

The bundled headless reasoner is HermiT 1.3.8.431, aligned with OWLAPI 4.5.29. Its configuration and known
parity boundary are documented in
[`docs/adr/headless-reasoner-and-workspace-boundary.md`](docs/adr/headless-reasoner-and-workspace-boundary.md).

## 12. Packaging and dependency isolation

The plugin is a single OSGi bundle built with `maven-bundle-plugin` 5.1.9. Protégé supplies
`protege-editor-core`/`protege-editor-owl` 5.6.6, OWLAPI 4.5.29, Guava 18, and SLF4J 1.7. The bundle embeds and
keeps private its MCP, Jackson, JSON Schema, Jetty/Jakarta Servlet, Reactor, Jena, RDFC, YAML, and CommonMark
runtime dependencies.

This isolation is deliberate: Protégé includes older library versions and a flat or partially imported class
space would produce linkage and `ServiceLoader` failures. Jackson values and other embedded-library types do
not cross the exported bundle boundary. Jena service entries are merged explicitly because inlining multiple
JARs would otherwise discard subsystem registrations.

The runtime and all artifacts require Java 17. The CLI shades its dependencies and service metadata into one
executable JAR. The plugin JAR remains directly installable in Protégé's `plugins/` directory and through the
repository descriptors used by Check for plugins.

## 13. Persistent state

| Location | Contents |
| --- | --- |
| Protégé preferences (`io.github.hakjuoh.protege_mcp/server`) | Server/broker settings, static token, standalone OAuth state, Assistant provider/model/path preferences |
| `~/.protege-mcp/` | Owner secret, broker state/lock/log, broker OAuth state, staged JARs, and owner-only audit streams |
| `<project>/` | `.protege-mcp/project.yaml`, project-contained governance/release assets, and optional explicit audit export |
| Private temporary directories | Captured headless workspaces, Assistant attachment copies, and transaction staging |

Owner-only permissions are applied where the platform supports them. Persistent files are atomically replaced
where torn reads would break security or reproducibility. Secrets are not written into ontology documents,
project policy, release manifests, or audit exports.

## 14. Verification strategy

The ordinary `mvn clean verify` path is offline-capable and covers all three modules. The test architecture
includes:

- Contract snapshots for tool/prompt metadata and result-field compatibility.
- Policy and contract schema adversarial tests.
- Pure core tests for fingerprints, QC, diff, impact, release, audit, and filesystem transactions.
- Plugin tests using real in-memory OWL ontologies plus narrow fakes for `OWLModelManager` and extracted EDT,
  OAuth, broker, CLI, and UI decision seams.
- Cross-surface conformance fixtures for plugin/core/CLI identity, counts, gates, artifacts, and reports.
- Real HermiT cases for consistency, unsatisfiability, inference, and explanation boundaries.
- Performance fixtures with versioned baselines and scheduled/release regression budgets.
- A Linux/Xvfb live harness that installs the built bundle into Protégé and exercises authentication,
  multi-window routing, EDT write/Undo, HermiT classification/explanation, release behavior, and shutdown.

See [`TESTING.md`](TESTING.md), [`docs/performance.md`](docs/performance.md), and
[`docs/smoke-test.md`](docs/smoke-test.md) for commands and evidence boundaries.

## 15. Current intentional limitations

- The live plugin performs long operations synchronously with bounds and cancellation fences; there is no
  public asynchronous job/progress API.
- SWRL rules can be listed and edited, but the product has no general rule-capability negotiation or
  inference-materialization workflow.
- Confirmation, capabilities, and audit are implemented; named-role and two-person approval are not.
- Local entity search is policy-aware, but no external terminology provider, SSSOM mapping manager, or
  lifecycle state machine is built in.
- Releases can be exported and verified, but there is no direct publishing adapter for commercial ontology or
  knowledge-graph platforms.
- The headless stdio server intentionally exposes only the eight project/release tools above, not the live
  85-tool editor surface.
- A non-loopback bind is explicit plain-HTTP opt-in and is not a supported multi-user deployment model.

Future work on these boundaries belongs in [`PLAN.md`](PLAN.md); completed behavior should update this design
and the user manual rather than accumulating another historical roadmap here.
