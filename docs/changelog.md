---
title: Changelog
nav_order: 8
---

# Changelog
{: .no_toc }

Release notes for every version. This page mirrors
[`CHANGELOG.md`](https://github.com/hakjuoh/protege-mcp/blob/main/CHANGELOG.md) (the source of truth);
each section is also published as the body of its
[GitHub release](https://github.com/hakjuoh/protege-mcp/releases). The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project aims to follow
[Semantic Versioning](https://semver.org/) — see [Versioning & releases](versioning.html).

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## [0.5.0] - 2026-07-12

**One MCP endpoint, however many Protégés: a shared broker now owns the configured port and routes
every MCP session to a live window, and the bind failure that silenced a second window's server —
and its Ontology Assistant — is fixed underneath it. The guided prompt set nearly doubles (6 → 11)
on a new prompts registry, the broker's idle linger is a preference, and the MCP client list now
cleans up after reconnects by itself.** Tool count is unchanged at **66**.

### Added
- **Shared MCP broker across Protégé windows AND instances** (default on; Settings ▸ MCP toggle).
  The configured port now belongs to a tiny standalone broker process (service id
  `protege-mcp-broker`; a plain `java` process spawned on demand from the plugin's own jar) that outlives any single window: the first Protégé
  process that finds no live broker starts one; every process registers with it and heartbeats;
  when the last instance unregisters (or dies — the broker health-checks pids), the broker exits by
  itself. Each window's MCP server runs on an ephemeral port behind the broker, so **one fixed URL
  (`http://127.0.0.1:8123/mcp`) always works no matter how many Protégé windows or instances are
  open** — no more per-instance URLs, no owner hand-off. Routing: a new MCP session goes to the
  window most recently connected to the broker (with auto-start on, effectively the newest window)
  and stays **pinned to it for the whole session**; `GET /instances` lists the registered windows
  and `/instances/{id}/mcp` targets one explicitly. The broker terminates auth itself (the static
  bearer token of any registered instance + full OAuth authorization server, persisted to
  `~/.protege-mcp/oauth.json` — the view's Connected-clients table applies to standalone mode; a
  broker-mode listing/revocation UI is a follow-up) and authenticates to each backend with a
  per-window secret; its control plane (`/internal/*`) is guarded by an owner-only file secret, and
  everything stays loopback-only unless the new bind-address preference says otherwise (the
  per-window backends stay on loopback regardless). An idle broker left by a different plugin
  version is retired and replaced automatically; long-lived SSE streams are kept honest with
  keep-alive comments so a vanished client can't pin broker threads. A cross-process file lock
  (`~/.protege-mcp/broker.lock`, held for the broker's life) keeps the broker a **singleton even
  when the configured port is ephemeral (`0`) or held by a foreign app** — shapes where the port
  bind itself can no longer arbitrate a spawn race — and a broker whose bind address turns out
  unbindable (a stale LAN IP, `::1` with IPv6 off) exits with a one-line explanation instead of
  crash-looping under the automatic respawns. If the broker cannot be spawned or reached, the
  plugin degrades to the previous standalone behavior automatically (a half-attached window server
  is stopped again, never left as an unreachable zombie). The in-app Ontology Assistant keeps
  talking to its own window's server directly and is unaffected.
- **The broker runs from a staged copy of the plugin jar**, so upgrading or removing the plugin no
  longer collides with a broker still holding the old jar open. The plugin and MCP jars are copied
  to `~/.protege-mcp/jars/<name>-<sha256/12>.jar` and the broker process is spawned from the copies,
  never from Protégé's plugins directory — a JVM holds its classpath jars open, and a broker
  outliving Protégé (linger/grace) would otherwise block replacing the plugin jar on Windows during
  an update. Content-hash names make a rebuilt same-version jar a fresh copy and keep concurrent
  spawns race-free; unused copies are swept (age-gated, best-effort) on later spawns; if staging
  fails the broker falls back to the original jars and logs a warning.
- **The Ontology Assistant renders replies as Markdown.** Assistant messages — which the CLIs return
  as Markdown — now display styled instead of as raw markup: headings, bold/italic, inline code and
  code blocks, bullet/numbered lists, block quotes, horizontal rules, GFM tables (monospace-aligned
  columns), and links. `http(s)` links are clickable and open in the browser after a confirmation
  dialog that shows the real destination (the link text is model-chosen); nothing else is ever
  clickable. Rendering is live — the in-flight message re-renders as it streams, so a code fence or
  `**` that closes late restyles the text it spans. User, tool, status, and reasoning lines are
  unchanged, and a rendering failure falls back to plain text rather than losing the reply.
- **Copy an assistant reply as its original Markdown.** Styled rendering is lossy — selecting and
  copying transcript text yields plain text — so each finished assistant message now keeps its
  original Markdown source: when a turn ends with assistant text (the usual case), a small copy
  button under that closing reply puts the untouched markup on the clipboard (flipping to a check
  mark as feedback), and a new right-click menu on the transcript offers "Copy" (the selection, as
  displayed) and "Copy message as Markdown" for **any** assistant message under the pointer —
  interim messages between tool calls, replies that a stray trailing tool/error line separated from
  the turn's end, and partial replies of stopped turns included, though none of those get their own
  button.
- **5 new guided prompts — the MCP prompt set grows 6 → 11**: `author_competency_question`,
  `author_swrl_rule`, `refactor_entity_safely`, `bootstrap_ontology`, and `release_readiness_check`;
  the six existing prompts are refreshed against the 0.4.x tool surface (orient with context first,
  preview destructive edits, apply with `verify=rollback`, diagnose with `explain_inconsistency`).
  Internally, prompts moved out of the tools package into a dedicated `prompts` package mirroring
  the tools registry pattern.
- **The competency-question annotation vocabulary now dereferences.** An ontology annotated through
  `add_competency_question`'s in-ontology convention points at
  `https://hakjuoh.github.io/protege-mcp/cq#competencyQuestion`; that namespace now serves a
  vocabulary page (with per-term anchors) plus a machine-readable `cq.ttl` instead of a 404.
- **Bind-address preference** (Settings ▸ MCP; default `127.0.0.1`, presets `::1` and `0.0.0.0`,
  any interface address accepted). The standalone window server and the shared broker bind the
  chosen address; the broker-managed per-window backends always stay on loopback (they are
  internal, reached only through the broker's proxy). Handed-out URLs always name a concrete host:
  the address itself for loopback and specific binds (IPv6 literals are bracketed, and the copied
  `claude mcp add` command quotes them — zsh would otherwise glob `[::1]`), and `127.0.0.1` for a
  wildcard bind — on another machine, replace that host with this machine's address. A specific
  non-IPv4-loopback bind (`::1`, a LAN IP) additionally aliases the same port on `127.0.0.1`, so
  older plugin versions and long-standing loopback client configs keep reaching the same broker.
  Choosing a non-loopback address shows a red warning in Preferences: the endpoint is then plain
  unencrypted HTTP on your network. **OAuth authorization stays same-machine only** whatever the
  bind address — the embedded flow's Allow decision is bound to nothing but reachability, so remote
  peers get a 403 pointing them at the static bearer token instead of a consent-less token mint.
- **The MCP Server view recovers a broker outage with one click.** While a broker-managed window's
  heartbeat cannot reach the shared broker, the view says
  `Broker is down — press Start to relaunch it; this window still serves at <direct URL>`:
  Stop disables, Start enables, and Start relaunches the broker immediately (bypassing the
  automatic retry throttle — if several instances press Start at once, one broker wins the bind
  and the rest reconnect to it). The clients panel stops attributing clients to the dead broker
  during the outage: a client that connects (and OAuth-registers) directly to the window is
  visible and revocable there.
- **The broker's idle linger is configurable** (Settings ▸ MCP, "Broker idle linger (seconds)";
  default 15, range 0–3600). After the last Protégé instance disconnects, the broker keeps running
  this many seconds so a quick restart — or a second instance arriving moments later — reuses the
  live broker and its port instead of paying a respawn. A change reaches a running broker with the
  next heartbeat (a few seconds) while a window is attached to it — no broker restart needed — and
  is also handed to a freshly spawned broker on its command line. `0` makes the broker exit the
  moment the last instance disconnects: every quit-and-relaunch then spawns a fresh broker, MCP
  clients briefly see connection errors during that gap, and a relaunch racing the dying broker's
  lock handover can delay startup by a few seconds.
- **Dead MCP client registrations clean themselves up** — no more revoking rows by hand after a
  client reconnects. An MCP client that lost or discarded its credentials re-registers under the
  same name with a fresh `client_id`; once that new registration completes authorization, the
  registrations it replaced are dropped together with their tokens. A same-name client seen since
  the newcomer registered is demonstrably alive and kept, and one mid-authorization (pending code)
  is never touched. Two background sweeps handle the rest: registrations that never finished
  authorizing disappear after an hour of inactivity (viewing the consent page counts as activity),
  and a client silent for 60 days is removed tokens and all — it would have to re-authorize anyway.
  The cleanup clocks restart on every plugin/broker start, so a stale persisted "last seen" can
  never trigger an early reap. Applies in standalone mode (the Connected-clients table) and inside
  the shared broker (`~/.protege-mcp/oauth.json`) alike. Alongside: refreshing a token whose client
  record is gone now fails closed with `invalid_grant` (the client re-registers instead of looping
  on 401s), and a brand-new registration can no longer evict itself when the persisted client store
  hits its size cap.

### Changed
- **Tool descriptions are ontology-neutral.** The MCP tool and parameter descriptions no longer use
  one ontology family's vocabulary in their examples: `set_prefix` and `sparql_schema` illustrate
  prefixes and CURIEs with neutral `ex:` / `example.org` placeholders, and `create_term`,
  `validate_ontology`, and `deprecate_entity` no longer name a specific family's definition or
  replacement property. `deprecate_entity` now documents its default "term replaced by" property
  (`IAO_0100001`) as the de-facto OBO Foundry obsolescence convention rather than a standard, noting
  `dcterms:isReplacedBy` as a vocabulary-neutral alternative. Descriptions also no longer hard-code the
  current version: `add_competency_question`'s `query_lang` parameter (and its rejection message) states
  the "only `sparql`" constraint version-neutrally instead of naming a release.

### Fixed
- **Provider and model switches no longer silently lose the Ontology Assistant conversation.** The
  transcript stays as one continuous conversation while Claude and Codex retain independent native
  CLI session IDs. Switching back resumes that provider's original session and hands it only the
  user/assistant turns produced while it was inactive (bounded to the newest 64k characters for an
  unusually large handoff); changing models continues the same provider session, and reselecting the
  active model is a no-op. **New chat** clears the shared transcript and every provider session. The
  provider picker reports whether it will resume or join, while the long first-send egress modal has
  been removed; the same privacy details remain non-blocking in Settings and the manual.
- **The broker's file-backed OAuth registry no longer evicts active clients at the standalone
  preference store's 8k limit.** The shared broker persists OAuth state in
  `~/.protege-mcp/oauth.json`, which has no `java.util.prefs` single-value ceiling; it now keeps the
  complete active registry while standalone configured-port servers retain the defensive 8k cap.
- **A failed MCP session DELETE no longer breaks the session's broker route.** Session pins are
  removed only after the backend accepts the close with a 2xx response, so a transient 4xx/5xx can
  be retried against the same Protégé window instead of turning every follow-up into
  `session_window_closed`.
- **"Show reasoning" is fixed for the duration of each turn.** The checkbox already selected the
  CLI reasoning mode only when a message started; transcript filtering now uses that same snapshot,
  so changing the option mid-stream applies to the next message without dropping the current
  reasoning tail.
- **The Ontology Assistant's "Show reasoning" toggle now actually shows reasoning.** Current CLIs
  send no reasoning text unless explicitly asked — the claude CLI ships an empty thinking block in
  its stream output (Claude 5-era models default their thinking display to "omitted"), and codex
  emits no reasoning items at all — so the checkbox filtered a stream that never contained anything.
  With the box ticked, each turn now opts in on the CLI side (claude: `--thinking-display
  summarized`; codex: `model_reasoning_summary="detailed"`), and reasoning streams into the
  transcript in gray italics from the next message. Alongside: reasoning gets its own line instead
  of gluing onto the reply, codex reasoning summaries shaped as a list of parts are read instead of
  silently dropped, the checkbox gained a tooltip, and the manual's description of the toggle
  (previously "Show thinking", listed under Settings) now matches the real name and location.
- **A second Protégé window or instance no longer loses the MCP server — and with it the Ontology
  Assistant — to `Failed to bind to /127.0.0.1:<port>`.** The MCP server is per-window but the
  configured port is process-exclusive, so any window that wasn't the port owner (a second window's
  chat lazily starting its server, or every window of a second Protégé process) died on the bind and
  the chat reported *"Could not start Claude: Failed to bind …"*. The server now **falls back to an
  ephemeral port when the configured port is already in use**: the chat always talks to its own
  window's actual port, the **MCP Server** view shows the actual URL plus a "configured port busy"
  note, and the log records a warning instead of a bind error. The configured port is re-claimed by
  the same Protégé instance once it frees up: on window close an idle window is promoted, and a newly
  opened window no longer defers to a fallback-bound server — while a live fallback server itself is
  never restarted out from under an active chat session. (If the port was held by a second Protégé
  instance that has since quit, the re-claim likewise happens on this instance's next window
  open/close — or immediately via Stop/Start in the **MCP Server** view.) Because two servers can now
  be live at once, their shared security state is isolated: a fallback-port server starts with an
  **empty OAuth client registry** (it never hydrates the user-global persisted blob, so a client
  revoked in the owner window cannot keep authenticating against it) and never persists
  registrations, and the static **bearer token is read live from preferences**, so *Regenerate token*
  in any window immediately invalidates the old token on every live server in the process.
- **A server stopped with the MCP Server view's Stop button stays stopped.** An explicit Stop could
  previously be silently undone by an auto-start — the chat's lazy start, a broker-attach failover
  to a standalone start, or the close-time promotion that re-claims the configured port — so the
  server (and the Ontology Assistant with it) came back on its own. A stop now **latches** until you
  press Start: every auto-start path refuses a latched server (the refusal is enforced under the same
  lock `stop()` takes, so a Stop racing a start wins), and only Start clears the latch. The state
  reads as the plain fact it is — the view says `stopped`, the assistant says
  `The MCP server in this window is stopped. Press Start in the MCP Server view…` — never as an
  error, and never with the stop narrated back at you.
- **Long help texts in Preferences no longer stretch the dialog into horizontal scrolling, and
  field labels stay next to their fields.** The shared-broker note under Preferences ▸ MCP (and the
  CLI and privacy notes under Preferences ▸ Ontology Assistant) rendered as one unwrapped line,
  forcing a horizontal scrollbar on the whole Preferences window; help texts now soft-wrap to the
  width of the controls above them. The labelled rows on the same tabs (`Port:`, the claude/codex
  path fields) put the label and its field at opposite ends of one stretched grid cell, so each
  label floated at the far right edge of the dialog; those rows are now composed left-aligned,
  label first.
- **A replacement shared broker no longer loses its takeover to the broker it just retired.** A
  stopping broker — an idle broker from another plugin version asked to retire, or one exiting on
  its own — stops answering probes and removes `broker.json` before its process actually dies, but
  kept the `broker.lock` file lock to the very end; the replacement tried the lock exactly once,
  found nothing left to defer to, and gave up. The spawning window then fell back to a standalone
  server on the configured port permanently, and every instance launched afterwards put its broker
  on an ephemeral port (`configured port … is held by a foreign process`) — clients of the fixed
  URL were split away from all newer windows. Both ends of the handover are fixed: a booting broker
  now keeps retrying the lock while it polls for a discoverable sibling (a genuinely wedged holder
  still ends in the safe give-up, never a second serving broker), and a stopping broker releases
  its lock the moment it stops serving instead of at process death.
- **Failed Ontology Assistant turns read cleaner.** Transcript error lines drop the literal
  `[error]` prefix (the red error styling already marks them — attachment and link failures
  included), and the generic `claude exited with code 1` line no longer repeats a failure the
  stream already surfaced, such as a provider-side safeguard/policy refusal shown verbatim. The
  exit line still appears when the CLI dies without emitting its own error (an unknown option,
  not logged in), where the exit code and stderr tail are the only diagnostic — including the
  hint that names the "Show reasoning" checkbox on an older CLI. Applies to both the claude and
  codex providers.

## [0.4.3] - 2026-07-08

**Operational-safety and transparency patch on top of the 0.4.2 reliability release: destructive
tools gain dry-runs, saving becomes explicit about formats and unsaved work, a silently-ignored SWRL
rule set is surfaced, and an inconsistent ontology finally gets a diagnosis path.** **65 → 66 tools.**

### New tools
- **`explain_inconsistency`** — explain WHY the ontology is inconsistent: finds a **minimal** set of
  asserted logical axioms that are jointly inconsistent (or a reduced, still-inconsistent set flagged
  `minimal=false` when the `timeout_ms` budget expires first). Runs the **selected** reasoner over a
  private copy of the imports closure, off the UI thread; the live reasoner state is untouched. The
  search uses `isConsistent()` as its only oracle, so it works where the justification generator
  cannot: every existing explanation/query tool throws `InconsistentOntologyException` over an
  inconsistent ontology — those tools now return a pointed error directing here instead of the raw
  exception, and `run_reasoner` / `validate_ontology` INCONSISTENT messages name this entry point.

### Added
- **`save_ontology all=true`** saves **every** ontology with unsaved changes to its own existing
  document in one call, reporting per-ontology results — an ontology without a file (never saved /
  loaded from the web) is reported as `skipped` with a reason instead of being written somewhere
  surprising. **`list_ontologies`** now marks each ontology `dirty` (unsaved changes), reports its
  `document` location, and totals `dirty_count`, so "what have I not saved?" is one read call.
- **Dry-runs for the destructive / high-blast-radius tools.** `rename_entity`, `delete_entity` and
  `merge_ontology_document` take `preview=true` (read-only-safe): the tool computes the exact change
  set the apply would use and reports it — rename: rewrite count, a rendered sample, and whether the
  new IRI already exists (a rename would merge the two entities); delete: every axiom that would be
  removed (count + sample) per deleted pun; merge: what would be copied/removed, how many source
  axioms are `already_present`, and `total_changes` — without touching the ontology.
- **`undo_change peek=true`** inspects the next-undo transaction (change count + a rendered sample,
  non-axiom changes counted) without undoing; `undo_change` / `redo_change` also report the undo-stack
  depth (`undo_depth`). The redo stack has no public accessor in Protégé, so redo stays a boolean.
- **`create_terms` / `create_properties` gain `verify=report|rollback`** — the same post-apply
  reasoner regression check as `apply_changes` (newly unsatisfiable class or newly inconsistent
  ontology; `rollback` reverts the whole batch in its single undo transaction). The verify
  orchestration is shared, so semantics (write mutex, intervening-edit degrade, warm/cold
  classification) match `apply_changes` exactly. `rollback` (on all three tools) now **fails
  closed**: when no pre-apply baseline classification can be established (cold-start
  classification failed or timed out), it refuses up front and applies **nothing**, instead of
  applying a batch that could only be reported as unverifiable.

### Fixed
- **`save_ontology` no longer silently falls back on an unknown extension.** Saving to `pets.obo`
  used to write the ontology's current format (or RDF/XML) under an `.obo` name; `.obo` now maps to
  the real OBO format, and any *unrecognized* extension is an error listing the supported ones
  (`.ttl`/`.turtle`, `.owl`/`.rdf`/`.xml`, `.owx`, `.omn`, `.ofn`/`.fss`, `.obo`). A path with no
  extension still keeps the current format. The same policy applies to `extract_module` `path` (validated up front, before the extraction).
- **A silently rule-blind classification now warns.** ELK has no SWRL support and quietly ignores
  rules, so classification "succeeds" with every rule-derived inference missing. When the ontology
  (with imports) contains SWRL rules and the selected reasoner is ELK, `run_reasoner` attaches a
  `warning` and the `run_qc_suite` reasoner stage carries the same warning in its findings summary
  (surfaced, deliberately not gated) — completing the 0.4.2 axis that surfaced HermiT's loud SWRL
  built-in failure and ELK's incomplete complex-expression DL queries.
- **Reasoner query tools no longer die with a raw exception over an inconsistent ontology.**
  `get_unsatisfiable_classes`, `get_inferred_superclasses`, `execute_dl_query`, `explain_entailment`
  and `get_explanations` returned the bare `InconsistentOntologyException: Inconsistent ontology`
  (and `validate_ontology`'s INCONSISTENT note recommended two of those failing tools as the remedy);
  all now return an actionable error pointing at `explain_inconsistency`.
- **`delete_entity` renders what it deleted with labels.** The `deleted[]` confirmation was rendered
  *after* the axioms (including `rdfs:label`) were removed, so it showed bare IRI fragments; it is
  now rendered from the pre-delete state.

## [0.4.2] - 2026-07-07

**Reliability and authoring-ergonomics fixes surfaced by a full multi-module ontology reconstruction
functional test, plus one new batch tool.** **64 → 65 tools.**

### New tools
- **`create_properties`** — batch object/data property creation: the array form of `create_property`,
  applied as **ONE undoable transaction** and **atomic** (a malformed item aborts the whole batch,
  applying nothing). Top-level `namespace` / `definition_property` / `property_type` act as **defaults**
  for any item that omits its own; a property may reference another in the same batch **by full IRI**.
  Closes the gap where `create_terms` batched classes but properties had to be created one call at a time.

### Fixed
- **`run_reasoner` no longer hides a failed classification.** When the selected reasoner rejects the
  ontology at initialization (e.g. HermiT does not support SWRL **built-in atoms**), Protégé catches the
  exception and silently resets to the Null reasoner; `run_reasoner` used to return a benign-looking
  `{reasoner:"Protégé Null Reasoner", status:REASONER_NOT_INITIALIZED, completed:true}` (the real
  exception went only to the Protégé log) or hang until the timeout. It now detects the reset
  (`classification_failed`) and returns an **error** naming the likely cause and the log; the shared
  "no current results" message across the reasoner-backed tools also now mentions a possible failed classification.
- **`save_ontology` (save-as) preserves the prefix map.** Saving to a new `path` installed a fresh
  document format with an empty prefix map, dropping every registered prefix **on disk and in memory** —
  which silently broke all subsequent CURIE resolution. Save-as now copies the ontology's prefixes into
  the new format.
- **Side-effect entities are declared.** Entities first introduced as an operand side effect — an
  annotation property named by a `definition_property`/annotation, an individual in a `class_assertion`,
  a class in a `subclass_of` — were left used-but-undeclared, which tripped `undeclared_entity` and (for
  annotation properties) left the ontology short of **OWL 2 DL**. `add_axiom`, `apply_changes`, the
  curation macros, `deprecate_entity` and `move_class` now emit a `Declaration` for every entity they
  introduce, within the same undoable change (matching how `create_*` already declares its primary entity).
- **`run_qc_suite` classifies for its reasoner stage.** The stage required a pre-classified, in-sync
  reasoner and was silently skipped after any edit — so an unsatisfiable-class-only defect could
  false-pass the gate. The suite now classifies (off the EDT, bounded by `timeout_ms`) before the stages
  when the reasoner stage is requested, and a still-unusable reasoner surfaces as a **`warn`** stage
  rather than a silent skip (a deliberately unselected reasoner stays a legitimate skip).
- **`apply_changes verify` surfaces a failed classification.** A verify run whose post-apply
  classification reset to the Null reasoner now reports `classification_failed` with a precise note
  instead of the generic "no completed classification".
- **Compound Manchester expressions accept CURIEs and IRI fragments.** A compound `super`/`classes[]`
  operand (e.g. `(ex:A or ex:B) and (ex:p some ex:C)`) previously accepted only
  rdfs:label short-forms or `<full IRI>`; registered-prefix CURIEs are now pre-expanded to full IRIs
  before parsing and bare IRI local names resolve via the signature, matching what single-entity operands
  already accept. Applies to class-expression and data-range operands.
- **`sub_property_chain_of` accepts an inverse link.** A chain element may now be `inverse(P)` /
  `ObjectInverseOf(P)`, so a property chain that needs an inverse property expression (as some real
  temporal property chains do) is expressible.
- **Governance no longer flags standard/tool-internal vocabulary as owned.** `validate_governance` and
  `validate_ontology` exempt well-known metadata vocabularies (dcterms, dc, skos, foaf, prov, oboInOwl,
  IAO — **annotation properties only**, so imported IAO classes are still audited) and the plugin's
  own competency-question annotation property from the owned-entity checks (undeclared / missing-definition
  / IRI-policy / required-annotations); the OWL 2 profile check is unaffected.
- **`create_*` no longer require `name` when a full `iri` is given.** `create_class`, `create_entity`,
  `create_term`, `create_property` and the batch `create_terms` / `create_properties` now derive the
  default name/label from the IRI's local part when `name` is omitted but `iri` is supplied, so an
  IRI-first authoring call need not repeat the fragment; `name` is optional in every create_* schema
  (a call with neither `name` nor `iri` is still rejected).
- **`create_property` / `create_term` echo the label in `created.display`.** The confirmation entity was
  rendered *before* its `rdfs:label` axiom was applied, so `created.display` showed the bare IRI
  fragment; it is now rendered after applying (matching the batch tools). Cosmetic — the label axiom
  itself was always written correctly.
- **Undeclared annotation properties are declared to keep OWL 2 DL.** Extending the side-effect
  declaration above: a definition/annotation property such as `skos:definition`, a `dcterms:*` or a
  project `*-av` property that is *used but declared nowhere in the imports closure* is now declared in
  the active ontology — across `create_*`, `add_annotation`, `add_axiom`, `apply_changes`, the curation
  macros and **`add_ontology_annotation`** (whose property no axiom carries). Keyed on whether the
  property is declared anywhere in the closure (not merely present in its signature), closing the case
  where an import *used* the property without declaring it and the module silently left OWL 2 DL.
- **SWRL built-in atoms render cleanly.** `list_rules` / `add_rule` / `remove_rule` ran a built-in
  predicate through the entity-name quoting path, mangling it to `'\'<swrlb:greaterThan>\''`; the
  rendering is now built from the structured atoms, so a built-in reads as `swrlb:greaterThan(?a, 1000000)`.
  The structured `body`/`head` output was always correct.
- **The default SWRL variable namespace is a valid IRI.** `add_rule`'s default `variable_namespace` was
  `urn:swrl#`, which mints invalid variable IRIs (`urn:swrl#p` violates the URN syntax — no `NID:NSS`
  colon) and made every Turtle / SPARQL-snapshot serialization log a `Bad IRI … SCHEME_PATTERN_MATCH_FAILED`
  warning; the default is now `urn:swrl:var#`. Existing rules round-trip unchanged (`list_rules` emits
  each variable by its full IRI).
- **OSGi ontology-manager creation is quiet.** The `extract_module`, `diff_ontologies`, reasoner-snapshot,
  `sparql_*` and `validate_governance` tools create private OWL API managers; under Protégé's OSGi
  runtime the OWL API injector could not resolve some factory bindings from the plugin's bundle
  classloader and logged a stream of `No instantiation found for Supplier<OWLOntologyLoaderConfiguration>`
  errors (non-fatal — the tools still worked). Managers are now created with the OWL API bundle
  classloader as the thread context classloader, silencing the noise.
- **`execute_dl_query` completes complex-expression sub/superclasses under ELK.** ELK returns an
  INCOMPLETE set of sub/superclasses for a **complex (anonymous)** class expression with `direct=false`,
  omitting the **direct** level (Protégé's own DL Query tab shows the same), so an "all subclasses of an
  expression" query — the core expression-constraint use case — silently lost the most-general, most-
  relevant matches; a named-class query and HermiT are unaffected. The tool now attaches a `warning` when
  that ELK combination is detected (results unchanged — still a faithful mirror of the DL Query tab), and
  a new opt-in **`complete`** flag reconstructs the exhaustive set non-destructively (the reasoner's
  direct results unioned with each direct named class's transitive descent, reliable even under ELK, plus
  the raw non-direct set as a floor), marking the response `completed` with a `note`.

### Notes
- Found by from-scratch **ontology reconstructions** exercising the reasoner, SWRL, SHACL, competency
  questions, SPARQL and governance end to end — a multi-module reconstruction (the first eight fixes and
  `create_properties`), a **FIBO FND reconstruction** (the six `create_*`/SWRL/OSGi fixes above,
  adversarially source-reviewed before folding in), and a **SNOMED CT reconstruction** (OWL 2 EL,
  classified with ELK — the `execute_dl_query` fix). A regression test was added for each fix; suite
  **2095 → 2120**.

## [0.4.1] - 2026-07-07

**Modularization, batch intake, pagination, and a SPARQL snapshot cache.** Raise the tool's ceiling
from an in-workspace operator toward a live-closure engineering companion: extract a locality module,
create a batch of terms in one transaction, page exhaustively through large signatures, and re-query
SPARQL at the same model state without rebuilding the snapshot, and validate the data against SHACL shapes. **61 → 64 tools.**

### New tools
- **`extract_module`** — signature-based **locality module extraction** (the interactive analogue of `robot extract`), using the OWL API's `SyntacticLocalityModuleExtractor`. Give a seed `signature` (entity names or full IRIs; a punned IRI brings every sense) and a `module_type` — **STAR** (default — smallest, both directions), **BOT** (⊥ — what the seeds *use*: their superclasses/definitions), or **TOP** (⊤ — what *uses* the seeds: their subtree) — over `source` = `imports_closure` (default) or `active`. The module is loaded as a new workspace ontology (`iri` names it) or, with `path`, saved to a file (format from the extension). The STAR fixpoint runs off the UI thread (only seed resolution + the closure snapshot are on the model thread, bounded by `timeout_ms`), and the tool is gated like every other write (read-only + confirm-writes, both delivery modes).
- **`create_terms`** — **batch term-request intake**: the array form of `create_term`, applied as **ONE undoable transaction** (one `undo_change` reverts every term). Each item takes the same fields as `create_term`; top-level `namespace` / `definition_property` act as **defaults** for any term that omits its own. The batch is **atomic** — a malformed term (or a duplicate IRI within the batch) aborts the whole batch with an indexed error, applying nothing — and `strict=true` refuses it if any operand would be minted as a new, empty entity. A term may reference another term in the same batch **by full IRI**.
- **`shacl_validate`** — validate the active ontology's imports-closure RDF against a **SHACL shapes graph** (embedded **Apache Jena SHACL**), the constraint-validation counterpart to `verify_ontology`'s SPARQL invariants. Shapes are supplied **inline** as Turtle or from a **local file** (a URL scheme is refused — offline by design, like `sparql_query`); validation runs over the asserted triples by default or the reasoner's inferences (`include_inferred`). Reports `conforms` plus, per result, the focus node, result path, value, severity, constraint component, source shape and message. The `run_qc_suite` **`shacl` stage** (previously reserved) is now wired to it via `shacl_shapes` / `shacl_shapes_path`.

### Improved
- **Read tools are paginated.** `list_classes`, `search_entities` and `get_axioms_for_entity` now take an `offset` alongside `limit` and return `count` / `offset` / `returned` / `items` / **`next_offset`** — pass a returned `next_offset` back as `offset` to page forward and enumerate a signature (or an entity's referencing axioms) larger than one page. The sorts are **totally ordered** (entities by display then IRI; axioms by rendering then the axiom's natural order), so paging never drops or repeats an item across a page boundary.
- **SPARQL queries reuse an edit-versioned snapshot cache.** `sparql_query` previously copied the whole imports closure, serialised it and re-parsed it into Jena on **every** call. It now caches the serialised snapshot, keyed by a monotonic model-state version bumped on any change (edits, imports, load/reload, reasoner classification, active-ontology switch — and a `set_prefix` edit invalidates it explicitly). A repeated query at the same model state skips the rebuild; each query still re-parses the cached **immutable** bytes into a *fresh* Jena model, and the asserted and inferred snapshots are cached separately. No new arguments.

### Fixed
- **CURIE operands resolve.** A registered-prefix CURIE (e.g. `ex:Widget`) passed to any operand or to `get_entity` is now **expanded via the active ontology's prefix map** before being treated as an IRI, resolving to the imported term — instead of silently minting a junk entity whose IRI was the literal string `ex:Widget`. Applies to entity / class-expression / data-range operands, the annotation subject, and `get_entity`.
- **OWL 2 profile check separates owned from imported.** `validate_governance` (and the `run_qc_suite` `profile` stage) now partition profile violations into the audited scope's **own** axioms versus those inherited from imports (`owned_in_profile` / `imported_violations`); the profile QC stage gates on the **owned** conformance, so importing a non-DL upstream ontology no longer swamps or fails a clean module.
- **`apply_changes` reports minted entities in its summary.** The batch `summary.new_entities` aggregate was computed after the changes were applied (when the entities already existed) and read empty; it is now computed pre-apply and lists them, matching the per-operation rows.
- **`search_entities` is self-consistent.** A `best_match` resolved via a label the substring finder missed is now surfaced in `items` too (type-filter-aware), so a non-null `best_match` no longer accompanies an empty result set.
- **`run_qc_suite` annotates a vacuous pass.** When zero stages actually run (every requested stage skipped), the `pass` gate now carries a `note` making the vacuous pass explicit.

### Notes
- New method-level tests for every core: SLME extraction (BOT pulls the seed's superclass, TOP its subtree, STAR a defined seed's definition) + `module_type` parsing; the atomic one-transaction batch-curation apply + defaults merge; the paginated windows (windowing, stable paging across boundaries, offset-past-end, zero/negative and near-`MAX_VALUE` limit edge cases); and the snapshot cache (get/store/staleness, separate slots, invalidation). A six-finding adversarial review was folded in before release: **`extract_module` file export is now gated** (it was bypassing the read-only + confirm-writes gates); a **`set_prefix`** edit now invalidates the SPARQL cache (was serving stale prefixes); the pagination window only advertises `next_offset` on forward progress (a zero/`MAX_VALUE` limit no longer emits an infinite-loop cursor); a **duplicate IRI within a `create_terms` batch** is rejected rather than silently merged; and the SPARQL cache's listeners are re-removed on the EDT if server-stop cleanup times out (no listener leak across restarts). A follow-up Codex pass then caught a **GUI-side prefix edit** (revalidated on a cache hit) and made the paginated entity sort **locale-independent** (`Locale.ROOT`). Test suite **2,044 → 2,095**. Requires a **Java 17+** JVM (unchanged); no new runtime dependency.

## [0.4.0] - 2026-07-01

**Safe, testable LLM-assisted authoring.** Move the assistant from a "confident editor" to a "safe,
testable editor" by closing the **propose → ground → verify → confirm** loop and adding a re-runnable
**requirements (competency-question) suite** — all built by reusing shipping primitives (the single-undo
transactional apply, the embedded reasoner, Jena ARQ, `OWLEntityFinder`, the catalog sidecar pattern).
**55 → 61 tools.**

### New tools
- **`add_competency_question` / `list_competency_questions` / `remove_competency_question` / `run_competency_questions`** — a re-runnable **requirements suite**. A competency question pairs an executable SPARQL query with an expected result — `nonEmpty` (default) / `empty` / `count OP N` / `exactRows` — and `run` re-checks them all against **one shared point-in-time snapshot**, so a curation edit that quietly breaks a requirement is caught like a failing unit test. CQs are stored via a small storage SPI with three conventions: **`robot-sparql-dir`** (default — a `cqs/` folder of `*.rq` files, for ROBOT/CI interop), **`sidecar-manifest`** (a full-fidelity `<basename>-cqs.json`, `version: 1`), and **`ontology-annotations`** (inside the artifact — the fallback when unsaved). Malformed input is isolated, never fatal; caveats (open-world `empty`, truncated results/inferences) are surfaced.
- **`verify_ontology`** — run project-defined SPARQL **invariants** (like ROBOT `verify`): each `queries[]` item is a SELECT/ASK whose **results are violations**, at the item's `error`/`warn`/`info` severity. Violations are reported as raw SPARQL bindings (never rendered through the UI thread); the `gate` fails at `fail_on`, and a check that cannot run fails **fail-closed**.
- **`run_qc_suite`** — one aggregate quality-control gate composing `reasoner` + `profile` + `structural` (default), plus opt-in `invariants`, `cqs`, and a reserved `shacl`, over one shared snapshot. Absent backends are **skipped with a reason, never an error**; the gate is the worst *ran* stage versus `fail_on`.

### Improved
- **`apply_changes` gains `verify=none | report | rollback`** — reasoner-verified apply. The batch is applied as one undoable transaction, the reasoner is classified **off the UI thread**, and a **regression** caused *by this batch* (a newly unsatisfiable class, or a newly inconsistent ontology) is detected. `report` keeps the batch; `rollback` reverts it in one undo. Runs under a server-level write mutex; an intervening GUI edit degrades to `report`.
- **`search_entities` is now grounding-aware**. Each hit carries `score` and `match_kind` (`exact` | `prefix` | `substring` | `fuzzy`), and the result adds `best_match` and `would_mint` so an assistant can decide whether to reuse a term or mint one.

### Behavior change
- **`search_entities` results are now RANKED** (by `score`, then display, then IRI), not just alphabetical. Clients that relied on the old order should sort explicitly. Every other tool's `entityList` ordering is unchanged.

### Notes
- New method-level tests for every core and tool wrapper, driven end-to-end over a headless `OntologyAccess`; three adversarial review rounds were folded in before release (hardening `verify_ontology`'s fail-closed behaviour and `run_qc_suite`'s aggregation). Test count **1,720 → 2,036**.
- The default `robot-sparql-dir` needs **no new serialization dependency** (plain `.rq` + header comments); `sidecar-manifest` uses the already-present `jackson-databind`. Requires a **Java 17+** JVM (unchanged).

### Hardening (folded into the 0.4.0 re-cut)
Post-authoring remediation from a codebase self-assessment — no user-facing tool changes (still **61 tools**). Test count **2,036 → 2,044**.
- **Security:** the **Claude MCP bearer token no longer lands on the process command line** — the `--mcp-config` JSON is written to an **owner-only `0600` temp file** and passed by **path**, then deleted when the turn ends (Codex already used an env var).
- **Testing:** the **reasoner-verified rollback path is now CI-gated** — a test-scoped DL reasoner (HermiT) classifies a genuinely unsatisfiable / inconsistent ontology whose verdict drives `apply_changes(verify)` to **rollback** vs **report**; previously only the manual smoke checklist covered this leg.
- **Build & CI:** **JaCoCo** coverage report + a `check` floor on `tools`/`server`/`oauth`; CI and release run `mvn clean verify`. Added **Dependabot** (Maven + Actions). Aligned all `jackson-*` modules via **`jackson-bom`** (removed the `jackson-dataformat-yaml` skew).
- **Internal / quality:** write-confirmation moved behind an injected `WriteConfirmer` seam (the `tools` layer no longer imports Swing; fails closed); unexpected tool-handler exceptions are now logged server-side; deduplicated helpers; added `SECURITY.md`, a vulnerability-reporting policy, and issue/PR templates.

Install: download `protege-mcp-0.4.0.jar` below, or use Protégé ▸ File ▸ Check for plugins.

## [0.3.3] - 2026-06-30

Ontology-**development** hardening: project-governance validation, high-level curation macros, broader
reasoner explanations, and a headless end-to-end smoke test. **50 → 55 tools.**

### New tools
- **`validate_governance`** — audit the active ontology against **project policy** (complements `validate_ontology`'s generic quality checks and `run_reasoner`'s logic checks). Each rule is opt-in: **OWL 2 profile conformance** (`owl_profile` = DL (default) / EL / QL / RL — reports the axioms that leave the profile), an **IRI policy** (`required_namespaces` / `iri_pattern` — every owned entity's IRI must conform), a **required annotation suite** (`required_annotations`, incl. the specials `label` and `definition` — every owned class/property must carry each), and **module ownership / import layering** (`check_ownership`, default on — the active module must not assert logical axioms about *imported* terms — including via a property chain that re-axiomatises an imported super-property). The expensive profile computation runs **off the UI thread** (on a snapshot taken on it), so conformance-checking a large ontology does not block Protégé for the analysis.
- **`create_term`** — create a class **with its curation suite in one undoable step**: label, a definition (`definition`, default `rdfs:comment`), an arbitrary annotation suite, parent(s) (named or a Manchester restriction such as `hasPart some Cell`), and optional `equivalent_to` class expressions for a defined class.
- **`create_property`** — create an object/data property **with its axioms in one step**: label, definition, `domain`, `range` (a class expression for object; a datatype / Manchester data range for data), `super_properties`, `characteristics` (functional, transitive, symmetric, …), and an `inverse_of`.
- **`deprecate_entity`** — the standard obsolescence pattern in one step: `owl:deprecated true` plus an optional **"term replaced by"** pointer (`IAO_0100001` by default) and any extra curation annotations. Idempotent (re-deprecating is a no-op).
- **`move_class`** — reparent a class (its subtree follows): replace the class's asserted **named** superclasses with a new parent, preserving anonymous restriction superclasses; `keep_other_parents` adds without removing, and omitting `new_parent` detaches the class to a root.

### Improved
- **`get_explanations`** now handles **any** `axiom_type`: for a kind the justification generator cannot minimally explain (e.g. a property-hierarchy or property-characteristic entailment), it falls back to confirming whether the axiom is entailed and returning the asserted axioms that mention the same entities as **structural context** (clearly labelled *not* a minimal justification) instead of rejecting the request.
- **`validate_ontology`** gains a **`timeout_ms`** budget — the structural checks run on the model thread and are not interrupted mid-run, so this bounds how long the *call* waits before returning a timeout error, not the on-thread work itself.
- **`preview_changes`** description now points at **`apply_changes`** (apply the whole batch in one undoable call) alongside the single-axiom edit tools, matching the README workflow.

### Notes
- New: a headless, CI-runnable pipeline smoke test (`ToolPipelineTest`) that drives the tool cores end-to-end — load → edit → validate → govern → diff → SPARQL — plus a manual live-Protégé checklist in [`docs/smoke-test.md`](smoke-test.html) for the GUI/reasoner/transport legs the unit tests cannot reach. Test count **84 → 97**.
- Requires a **Java 17+** JVM (unchanged). The OWL 2 profile checker is the OWL API's own (`org.semanticweb.owlapi.profiles`), already on the Protégé platform.

Install: download `protege-mcp-0.3.3.jar` below, or use Protégé ▸ File ▸ Check for plugins.

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

Closes the multi-module reconstruction gaps found by rebuilding a large multi-module ontology through the tools alone. **41 → 47 tools.**

### New tools
- **Structured SWRL rule editing** — `list_rules` / `add_rule` / `remove_rule` read, add, and remove `swrl:Imp` axioms as structured body/head atoms (`class`, `object_property`, `data_property`, `same_as`, `different_from`, `builtin`). A `?`-prefixed argument is a rule variable (`?name` → `variable_namespace` + name, `?<IRI>` → that IRI exactly), so **named variable IRIs** like `ex-var:process1` reconstruct faithfully where a `?x` text syntax would lose them; rule-level annotations (rdfs:label/comment/…) ride the existing `annotations` operand. OWLAPI 4.5.29 ships no standalone SWRL parser, so the structured form is the round-trippable primitive.
- **`create_ontology`** — mint a new empty module in the workspace and make it the active edit target (pairs with `set_ontology_id`), so a multi-module ontology can be built from nothing.
- **`write_catalog`** — generate/refresh an OASIS `catalog-v001.xml` mapping the active ontology's imports (ontology + version IRIs) to their local files, so a reconstructed module re-opens in Protégé with imports resolved offline. Catalog files live outside the OWL axiom model, so no other tool can produce them.
- **`diff_ontologies`** — axiom-level semantic diff / round-trip check between two loaded ontologies, or the active ontology against a freshly-loaded document (without adding it to the workspace); `identical=true` means the reconstruction is axiom-for-axiom faithful.

### Notes
- OWLAPI stays at 4.5.29 (provided by Protégé 5.6.6 and shared with the live `OWLModelManager`); these tools need nothing newer.
- Requires a **Java 17+** JVM (unchanged).

Install: download `protege-mcp-0.2.2.jar` below, or use Protégé ▸ File ▸ Check for plugins.

## [0.2.1] - 2026-06-28

## protege-mcp 0.2.1 — tool-driven construction ergonomics

Driving a real multi-module ontology entirely through the tools surfaced the friction points of natural-language-driven authoring. This release closes them. Additive and backward-compatible; **37 → 41 tools**.

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
- **Import-aware `validate_ontology`**: the per-entity quality checks audit only the terms the active ontology is responsible for, so imported upstream terms are no longer false-flagged for label/definition/domain/range that lives upstream. Set `include_imports=true` to audit the whole imports closure.

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

Complete the granular (incremental) authoring surface so a rich multi-module ontology document can be reconstructed by hand, plus merge/read robustness fixes. **26 tools total.**

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
