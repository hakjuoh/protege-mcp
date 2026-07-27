---
title: Changelog
nav_order: 13
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

## [0.8.0] - 2026-07-24

**The 0.8.0 release expands Protégé MCP to 104 live tools and 11 prompts with governed ontology-engineering workflows.**
It adds bounded asynchronous jobs, preview-first inference materialization, exact reasoner and rule capability
evidence, project-governed SSSOM mappings, and evidence-bound external-term reuse, while tightening authorization,
policy, filesystem/network boundaries, OAuth revocation, cross-adapter contracts, and release evidence.

### Added
- Added the adapter-neutral core of the 0.8 public job runtime. Closed job/result types, complete immutable
  input identity, exact owner/grant isolation, 15-minute idempotency, bounded admission/retention/artifacts,
  monotonic cancellation, and adapter-held publication leases now form reusable contracts for the live
  plugin. Guard leases span audit intent, the commit CAS, irreversible work, and terminal publication;
  missing leases fail closed, cancellation audit never blocks the tombstone path, and progress audit is
  capped with terminal emitted/suppressed summaries.
- Added the live-only `start_job`, `get_job`, `cancel_job`, `list_jobs`, and
  `export_job_artifact` tools over one bounded two-worker runtime per Protégé window. Classification,
  project QC, asserted semantic diff, and inference-materialization adapters capture immutable inputs before
  admission; rejected, duplicate, cancelled, and shutdown tasks release those captures. Exact owner/grant and
  workspace isolation, revocation cancellation, policy-driven quotas, verified private artifacts, guarded
  atomic export, and publication-time authorization/revision checks apply throughout. Structural
  classification explicitly reports unsupported consistency/satisfiability evidence, and materialization
  fails rather than treating structural traversal as semantic consistency proof.
- Added preview-first `materialize_inferences` and explicit `commit_materialization` to the live and
  headless surfaces. Six closed inference categories require exact supported capability evidence and are
  discarded atomically on enumeration, timeout, count, or byte failure. Private owner-local artifacts bind
  model, closure, import, mapping, policy, reasoner, destination, provenance, and verified content identities
  and expire after 30 minutes. Live commits recheck the complete identity, create new ontologies through the
  Protégé model-manager lifecycle, and apply active-source axiom changes as one Undo unit; headless commits reject project inputs, verify
  serialization, take the project lock, preserve a checksum backup, and publish a project file through
  guarded no-overwrite hard-link creation. Stable provenance IRIs and exact-pair idempotence make repeated
  commits no-ops.
- Added `get_reasoner_capabilities` and `validate_rules` to the live and headless surfaces. Reports
  bind to one exact reviewed tuple of factory id/class SHA-256, every class in explicit runtime package
  scopes (including inner classes, Apache Axiom, and replacement-layout AutomataLib), scope count,
  implementation version, configuration-class SHA-256, semantic configuration digest, and effective
  buffering mode while retaining the complete operational configuration digest; effective class-loader
  resources reject split-package overrides, Felix bundle resources are normalized to bounded local
  artifacts, active multi-release selection is manifest-aware, and ELK worker/evictor values are explicitly captured. Only
  reviewed HermiT 1.3.8.431 (including its official Protege adapter), OWLAPI
  structural 4.5.29, and ELK 0.5.0 identities (including the official Protege bundle) receive
  non-unknown profiles. Rule validation executes nothing, parses every atom and built-in, reports a
  bounded accepted corpus, separates body-variable binding from engine DL-safety evidence, binds pagination
  to a mandatory continuation fingerprint, and fails every built-in outside a closed pure SWRLB allowlist.
  Live capture bounds direct-import traversal and ontology/version identifiers, preflights occurrence count,
  copies a small coherent immutable-axiom snapshot on the model
  thread, and performs runtime-code/configuration identity plus canonicalization off-thread,
  and returns detached DTOs; all incompatible rules remain identifiable beyond the ten-row detail page.
  Plugin project QC, headless project QC, one-shot `validate`/`release`, and headless stdio reuse the same
  automatic rule-validation semantics and transport-sized result contract.
- Added project-governed `search_external_terms` and `inspect_external_term` tools for one exact OLS4
  provider at a time. Owner-only endpoint bindings, strict HTTPS egress, credential-generation and
  project-bound caching, opaque principal/grant/workspace cursors, bounded typed evidence, final
  publication revalidation, and fail-closed secret-like response scanning keep project-authored policy
  from selecting arbitrary endpoints or exposing provider state.
- Added `propose_term_reuse`, which performs a cache-bypassing direct re-inspection of the selected
  external term and creates a 15-minute, principal/grant/workspace-scoped immutable proposal bound to
  provider evidence, model and mapping revisions, policy digest, action, and normalized operations.
  A stable content fingerprint excludes request-time timestamp/retry churn while the proposal still
  binds the complete acquisition evidence. Proposal creation never writes project state.
- Added explicit `accept_reuse_proposal` acceptance. Reuse returns a non-mutating receipt, mapping uses
  the proposal's original SSSOM revision for one CAS, and mint-plus-mapping records the one-broadcast
  ontology commit before attempting the sidecar. A failed second step returns a bounded `partial`
  continuation and manual recovery without reminting or claiming cross-resource atomicity. Opaque
  canonical project, policy-source, mapping-target, and store-existence identities are fingerprint-bound;
  started mint commits retain their authorization/audit lifetime and renew only a receipt continuation.
- Documented the complete anonymous EBI OLS4 owner setup, policy binding, permission requirements,
  cache behavior, credential lifecycle boundary, and stable troubleshooting codes.
- Made OAuth RFC 7009 grant revocation linearizable with refresh and crash-safe in broker mode.
  Owner-only `~/.protege-mcp/revocations.json` is a bounded write-ahead journal: backend fences are
  retried across unavailable/later windows and broker restarts, stale OAuth state is replayed before
  serving, persistence failures fail closed, and a sealed shutdown — the idle one, and equally the one a
  newer plugin's window asks an older broker for, which is the likelier of the two — compacts the journal
  under the registry monitor that excludes registration, having first written down everything the memory it
  is about to end was holding, and does not seal at all if that could not be written — unless
  an endpoint was released on age alone rather than proven stopped, which is a fence still owed and is
  recorded in the journal itself, so the restart that ends this broker's memory of it cannot let a
  successor's own quiet shutdown compact the obligation away — and a successor asked whether that
  credential is fenced everywhere answers that it is not, rather than reporting every window acknowledged
  with no window left to have acknowledged anything. What is recorded is the endpoint itself, named by its
  window id and carrying the OS pid that owned it, and it is recorded whether or not a tombstone happened
  to be pending at that shutdown, because the fence is owed to every revocation that comes after it —
  including ones no broker has made yet. Two incarnations of one window are one record here where the
  generation that watched them go counted two, because what a successor can prove is about the window and
  not about which of its incarnations served. The pid is what lets the record be discharged rather than only
  kept: the obligation is settled when the OS no longer has that process, or when that same window of it is
  a fence target here again — registered live, or still held in quarantine, either of which a fanout
  reaches. A window the journal names and this registry names again — one that came back and was
  released unproven a second time before the pass that would have discharged the record ran — is one
  window owing one fence, counted by the record that outlives this broker rather than by both, since
  a result that counted it twice would report one unfenced window as two. The same overlap one step
  nearer settles the same way: a window the journal names that is a fence target of this very
  revocation is counted once, by the target the fence went to rather than by the record the next
  pass would discharge on exactly the reachability that fence just proved. This generation's own
  aged-out obligations answer to that same proof and are discharged by it: a maintenance pass that
  finds the OS has reaped the process an aged record was owed for stops reporting that record, since
  otherwise one instance that wedged, aged out and later exited would leave every revocation this
  broker was asked for afterwards answering unconfirmed with nothing anywhere left to fence — the
  latch this whole journal exists to avoid. A record that stood for a process rather than a window
  is only one of however many of its windows overflowed onto it, so what is still counted for the
  others stands: over-reporting what is unfenced keeps revocations unconfirmed, which is the safe
  way to be wrong about it. Another window of the same process registering settles nothing, because
  a registration is not a report that the endpoints it does not name have stopped; a pid still
  running somewhere unregistered stays owed. An obligation past the bound on how many windows one
  generation can name travels as the process that owed it — one entry per process, however many of
  its windows overflowed — because the pid is all there is left to say about such a window and also
  the whole of what settles it: what would otherwise be a count no journal could hand on becomes a
  record a successor can both state and discharge, and one that only the process being gone answers,
  since a record that never said which window overflowed cannot recognise that window coming back.
  The journal climbs that same ladder on its own account, and it has to, because its bounds are its
  own: a file carrying what earlier generations left has less room than this generation's naming
  bound implies, so an obligation this file cannot name is folded onto its pid however few this
  broker itself has named. Folding makes the same trade the registry's does, and it is a trade: the
  record keeps the proof that always eventually arrives, that process ending, and gives up the one a
  named record also has, that window becoming a fence target here again — an entry that no longer
  says which window it is about cannot recognise that window coming back. What that costs is time,
  since such an obligation stands until its process does, and a revocation waiting on the later
  proof answers unconfirmed for longer rather than confirmed sooner. An obligation neither bound can
  take refuses the write — and with it the shutdown that asked for it, which leaves the obligation
  in the memory that still fences it rather than ending having dropped it. Past that bound as well —
  every window named and every process entry taken — the endpoint is not released at all: its record
  stays in the revocation-only quarantine, a fence target this broker still sends to and still
  reports unacknowledged, which is what it was before either bound existed and more than any note
  about it could be, since a backend that is still listening actually receives the fence. It is held
  under the same bounded quarantine capacity as every other retired endpoint and leaves it as soon
  as its process is gone, and this broker does not idle-exit while it holds one: retention is a cost
  a bound may impose, and forgetting an endpoint that was never proven stopped is not. One endpoint
  per process is held this way and only one, because the pid being gone is equally the proof for
  every other endpoint of that process: the next window of a process already holding one is released
  and counted like any other overflow, so what is retained grows with the machine's processes rather
  than with its windows and the ordinary quarantine keeps draining around it. Those released windows
  are counted under the pid whose record they deferred to, so the proof that finally releases that
  record settles them as well: they are the one release nothing else here writes down, and a count
  nothing is filed under is a count nothing can ever take back - a broker that reached this state
  once would answer every revocation asked of it afterwards unconfirmed, for the rest of its life,
  with nothing anywhere left to fence. A record nothing could clear would be a latch instead — every
  revocation this machine made again would answer unconfirmed, the journal could never be compacted,
  and it would fill to capacity and start refusing revocations outright. What a record of a whole
  process waits on is that pid, and a pid is the operating system's to reuse: a machine that hands
  the same number to some long-lived process leaves the record standing until that process ends or
  the machine restarts, and every revocation until then answers unconfirmed rather than confirming a
  fence nothing proved. That is the direction such a record has to fail in, and where it ends is a
  refusal too — the journal's own capacity, reached by revocations it never managed to settle,
  refuses the next one rather than dropping what it is holding. It is read as exactly one document:
  a file carrying a second one after it — corruption, or a rewrite truncated onto a longer previous
  version — fails closed instead of loading that prefix and silently dropping every tombstone the
  rest of the file records, and an `unattested` entry that is not a record this broker could act on
  — a bare flag or a count in place of the endpoints, an entry with no window id, or one whose pid
  is missing, fractional, or not a positive number — is not a journal it can read either, since
  coercing one would record an obligation nothing could ever settle; the `unattested_processes` list
  that carries the ones past the naming bound is held to the same reading, a list of positive
  integral pids and nothing else; an empty list of either says what an absent one says.
  Heartbeat-stale, unregistered, removed, or replaced live-process endpoint incarnations remain in a
  bounded revocation-only quarantine until PID death or the end of a bounded retention window, without
  remaining routable; version takeover cannot discard that quarantine, and an endpoint retired again
  after serving again is retained from that later retirement.
- Froze the complete 0.7.2 plugin and headless MCP contracts before expanding the 0.8 surface. Every
  tool now advertises an output schema and a common bounded, recursively sanitized typed-error schema;
  new tools must declare a narrow recursive output contract and both adapters validate and snapshot
  returned structured data before rebuilding its canonical text representation.
- Distinguished model-thread work cancelled before execution from an already-started operation whose
  mutation outcome is unknown, so only the former is marked retryable. Authorization revocation and
  audit failures now retain stable phase/outcome codes without leaking secrets or local paths.
- Added the strict project-policy v2 contract for owner-local OLS4 provider identities, project-confined
  SSSOM mapping governance, bounded public jobs, and inference materialization. Valid regular-file v1
  normalization and digests remain byte-for-byte compatible and report an out-of-band, non-writing migration
  recommendation; rejected-input diagnostics are now bounded/redacted and symlinks fail closed. V2 templates
  are opt-in.
- Policy capture now inseparably pairs bytes with the canonical source and filesystem identities of its
  project anchor, rechecks both after semantic asset validation and before snapshot publication, and retains
  the anchor pin for snapshot currency plus single-file/bundle transaction creation, commit, and recovery
  across parser, cache, and headless boundaries. Bounded RO-Crate
  capture pins its source and parent directory and shares one byte snapshot between version inference and
  validation; bounded module reads likewise prevent declared assets from exhausting memory. SSSOM sidecars
  use their own revision instead of perturbing ordinary ontology preflight.
- The Ontology Assistant gained a **reasoning-effort picker** beside its model picker and a user-managed
  **model catalog** under **Settings ▸ Ontology Assistant**. The effort is remembered per provider and
  applied per turn (`claude --effort`, `codex -c model_reasoning_effort`); Codex narrows the offered
  levels to those the selected model advertises in the local CLI metadata — including at `(default)`,
  where the top-level `model` of `~/.codex/config.toml` is the one that will run, in every spelling Codex
  itself runs it that fits on the assignment's own line: the key bare or quoted, the value in any of TOML's
  four string syntaxes, and a basic string's escapes resolved, because `model = "gpt-\u0035"` runs gpt-5 —
  while a literal `'…'` value takes no escapes at all, and a value whose `"""` or `'''` body carries on to the
  next line, legal TOML though it is, narrows nothing: every level is offered, as for a profile, rather than
  a guess at what the continuation holds. A config whose
  model lives under a profile table, or that selects a profile at all — bare key or quoted, since TOML
  makes `"profile"` the same key, and whatever its value is spelled like, a `"""…"""` one written over two
  lines included, because assigning the key at all is what hands the decision away — offers every level
  instead of guessing which table wins, and only genuine top-level keys are read (a `model` line inside a
  multi-line string value is text, an escaped `\"""` inside such a value is more of its body rather than
  its end — while a literal `'''` value, which takes no escapes, does end at the first delimiter —
  a `'''` or `"""` inside a comment opens nothing, a bracketed array
  element is not a table header even when an array written over several lines gives it a line of its own,
  such an array is over once its `]` is read even where that bracket shares the line that ended a
  string element of it — so a top-level `model` written under it still narrows —
  a `]` inside a quoted table name does not stop that header from being
  one, and a byte-order mark is not part of a key or of a table header wherever an editor left it) — while models
  the metadata marks as
  unlisted or unavailable over the API contribute none, where marking it means the field carrying the kind of
  value it is supposed to carry: a visibility that is not a string, or an availability flag that is not a
  boolean, is a cache written to a schema this build does not know and has said nothing about that model, so
  it is offered rather than hidden by a coerced reading no error message could ever explain — as does an
  entry whose id, or a level whose
  name, is not a string in that metadata at all, rather than the number it holds being offered as one, and
  as does a level whose name is not a name at all: an effort is one bare config token the CLI parses as a
  word, so a sentence, a value carrying a newline, an over-long one, or one written outside plain ASCII
  letters and digits is not offered, and the offered levels are bounded in number exactly as the models
  are, since a picker is a list a user chooses from and not a rendering of whatever a metadata file holds —
  and `(default)` sends no flag
  so the CLI's own configuration decides. A model no local metadata describes is offered every level
  current Codex releases accept, since a level missing from the picker is one no error message would
  explain. Which levels a model actually supports remains the model's
  business, so a refusal is reported as such: a turn that fails on the exact effort value it asked for,
  and a run that completed after warning it dropped the option, both add a transcript note
  naming the effort picker, because neither the API error nor the CLI warning mentions it. A diagnostic
  that spells the setting out in prose is one of those however it words the refusal — "does not support
  reasoning effort 'max'" as much as "invalid reasoning effort" — since the note is about the control that
  sent the value and not about the sentence that came back, while a text that merely mentions reasoning
  effort without refusing anything is not a complaint about it — the override key itself echoed back into a
  log or config line names the setting exactly as prose does and is held to the same test, since a CLI
  printing the option it was given has refused nothing, and the wording of refusal has to sit on the line
  that names the setting, a diagnostic being a stream's errors and a CLI's stderr arriving as lines rather
  than as one sentence: an echoed override beside an unrelated line that refuses a mistyped model id is not
  this picker's failure, and neither is one that lands on that very line: a text that names the model as
  what it refuses has not refused the effort, however much of the effort's own configuration it echoed
  alongside, while a text that refuses both is still the user's to act on here. And the ways a
  refusal words itself are read
  broadly, a value called unknown, unrecognized, out of range, unavailable, not available or not enabled for
  the model or the account, not permitted, or simply rejected among them, because a
  wording the list misses is a note the user never gets. The note is worded for
  what happened, since a turn that answered anyway was not refused whichever channel carried the
  complaint, the reply being what decides that and not the diagnostic — a reply of nothing but whitespace
  is not one, and a turn that said nothing is never told it ran on the CLI's own effort or answered
  anyway, whatever its exit code and whether or not its stream said why — a clean exit that reported
  nothing and produced nothing is the one turn this note is the whole account of, so it reports the reply
  as missing rather than promising one — and without naming who refused the
  value or promising the diagnostic lists what to pick instead, since the same diagnostic covers a Codex
  release whose own parser does not know the setting at all: it names no value, accepts none, and the way
  out it always leaves is the picker's own `(default)`. A failure
  about anything else — a mistyped model id, say — is never blamed on the picker, nor is a warning about
  some other option whose name merely begins the way this turn's does, since an option name ends where the
  CLI's own does; and a diagnostic that
  refuses the one value both the model and the effort picker are set to is reported as exactly that, since
  nothing in such a text decides between them, so the note names both controls and says to change one at a
  time rather than sending the user to one they may never have touched. The catalog is an
  ordered, duplicate-free list per provider
  with add/update/delete/reorder editing, bootstrapped until first saved from the model already selected
  plus that CLI's own local metadata; a saved empty list deliberately means "send no model argument", and
  metadata that is missing or oversized contributes nothing, as does a JSON file that cannot be parsed,
  leaving just the model that was already selected — `(default)` alone only when nothing was selected
  either. The Codex
  configuration that seeding reads is read as configuration and not as prose, on the same terms as the
  narrowing above: a `model` line inside a comment or inside a multi-line string body seeds no model, and
  an escaped quote inside a value is a character of the id rather than the end of it, so a half-read
  value is never offered as an id of its own. It has no all-or-nothing step of its own, being read line by
  line rather than parsed: a value left unterminated seeds nothing itself while the assignments around it
  are still read, so no stray bracket empties the picker. A profile's model is seeded whichever way
  TOML spells it, `profiles.work.model = "…"` and the inline `profiles.work = { model = "…" }`
  exactly like the table form under `[profiles.work]`, and a model id used as a key seeds itself in
  every spelling TOML gives that one entry: a key under a `[models]` table, quoted or bare and whether it
  is assigned or dotted into, a `[models."gpt-…"]` header, and the top-level `models."gpt-…" = { … }` and
  `models = { "gpt-…" = … }` forms. The key form is read only where model configuration lives, under
  `models` itself, since a table keyed by ids for some other purpose is not offering them:
  `[tui.model_availability_nux]` keys real ids to count how often each was mentioned, `[[models]]` is an
  array of tables this release configures nothing in, and the keys under `[models."gpt-…"]` itself are
  that one model's settings rather than further ids. A key that stops short of the id it was meant to name
  seeds nothing rather than the part before the dot, an unquoted `gpt-5.5-codex` being two keys; and a
  table keyed by something that is not a model, a project path that happens to contain `gpt-`
  included, seeds nothing either way. The file is read once through its lines, so no run of blank
  lines in it can hold up the window while a picker is filled. Editing is keyboard-complete
  (**Enter** applies the field as **Add**/**Update**, while an empty field leaves **Enter** to the
  dialog's own OK button), every staged edit says it is not stored until **OK**, and an Ontology
  Assistant that is already open rebuilds its pickers when the catalog is saved instead of waiting
  for the view to be reopened — every open view, since one that fails to rebuild is logged and
  skipped rather than stopping the refresh of the views after it.

### Changed
- The Ontology Assistant's model pickers no longer offer ids hard-coded by the plugin
  (`opus`/`sonnet`/`haiku`/`fable`, `gpt-5.5`/`gpt-5.4`/`o3`); they offer the per-provider catalog above.
  An upgrade keeps the model you had selected by seeding the catalog with it, so no turn silently moves
  to a different model; a model the catalog no longer contains falls back to `(default)` instead of
  being sent to the CLI.
- The Assistant view now selects the first installed CLI when the remembered provider is no longer
  available, instead of opening with no provider and an empty model picker.
- The credential store's extended-ACL reading on macOS, which costs a process because this JVM has no ACL
  view there, is now asked for far less often: an answer is kept against the moment the kernel last changed
  that inode, a file's directory is read in the same pass as the file whenever it is not already known, and
  a path this store just created inside a directory it has already read as clean is not stripped, since
  macOS gives a new file an ACL only by inheritance. What is refused is unchanged. An answer is only ever
  reused for the very inode it was about while that inode's status-change time still matches, which every
  change to an ACL moves; a pass covering more than one path holds every line of the answer to exactly the
  rule a path read on its own is held to, and so comes back clean for all of them or as no verdict at all,
  in which case the path being checked is read again by itself; and a store directory that did inherit
  entries from a parent this store does not own is stripped and then read again, which is what refuses one
  that could not be.

### Tests
- Added deterministic core job tests for every legal job type and terminal path, type/result mismatches,
  idempotency and quotas, owner/revocation isolation, cancellation at guard/audit/commit barriers,
  null and exceptional-release leases, scheduler rejection, progress suppression, artifact cleanup,
  JSON round trips, and shutdown/late-output fencing.
- Added exact-version/configuration/runtime-code-tuple mutants, official Protege HermiT/ELK capture, real HermiT inference
  and built-in rejection, structural and ELK hierarchy/assertion/property-chain/consistency/satisfiability/
  incremental fixtures, closed vocabulary/allowlist schemas, every global corpus/render budget, worst-case
  output, order-independent rule fingerprints, ontology-version and mandatory snapshot pagination, strict
  cross-adapter arguments/errors, multi-page one-shot findings, an expanded digest-pinned 0.8 feature contract,
  manifest-aware multi-release selection, an actual Felix nested-bundle resource, and shaded headless
  HermiT evidence.
- Added immutable 0.7.2 plugin/headless goldens, recursive schema-dialect attacks, result-validation
  mutants, redaction/canary/immutability cases, and execution/audit outcome tests.
- Added deterministic refresh/revoke concurrency, write-ahead failure, corrupt/oversized/capacity
  journal, owner-only permission, broker restart replay, later-window retry, late-session-pin, and
  bounded in-memory tombstone tests.
- Pinned both policy-schema hashes and a fixed v1 normalized digest; added v2 schema/default/semantic,
  mutation, template, immutability, public migration-result, input-amplification, symlink-escape,
  symlink, same-path source/ordinary-directory replacement before and after asset validation, transaction
  creation/recovery before and during a hardlinked project replacement (including rollback/cleanup), parent-pinned
  RO-Crate capture, mutation-sensitive post-size-check RO-Crate growth, oversized
  sparse-module, output-collision, rejected-secret non-reflection, and
  shaded-distribution smoke coverage.
- Added headless Assistant coverage for model-catalog trimming, de-duplication, saved order, the
  explicit empty catalog, the upgrade seed, catalog bounds, control-character and trailing-garbage
  metadata rejection, TOML literal strings, every spelling of a `model` key Codex accepts (quoted key,
  one-line `"""…"""` and `'''…'''` values, a basic string's escapes resolved against a literal string's
  that are not, and a value whose body carries on to the next line, which narrows nothing), and discovery
  from a temporary metadata root; for the
  reasoning-effort argv of both CLIs and the flag-gated older-CLI rejection message; and for the
  preferences editor's Add/Update, per-row delete, reorder, and non-primary-click semantics.
- Added Assistant coverage for the Enter-key application path (including the empty field that must leave
  Enter to the dialog's OK button), the staged-until-OK feedback wording, per-model Codex effort narrowing
  from the configured model and from listed/API-available metadata entries, the catalog notification an
  open view follows and must stop following on teardown, and both refused-effort diagnostics — the failing
  Codex turn and the warning-only `claude` run whose note is held back until the reply is closed, and for
  two events flushed onto one line, which are both delivered, against a malformed remainder that must not
  retract the event before it.
- Pinned the broker registry's recoverable-gap semantics: a session pinned to a quarantined window
  survives a stale reap while its process lives, is forgotten once the quarantine is evicted, and
  same-pid re-registration neither exhausts process capacity nor discards retired endpoints; a pid-less
  registration is rejected end to end over the internal API. A quarantine is held for its whole retention
  window and released after it even while the liveness probe keeps answering "alive"; an endpoint retired
  a second time is retained from that retirement rather than the first, and a full quarantine of expired
  endpoints is drained by the very window close its capacity bound was refusing — unless what fills it are
  the records nothing can record an obligation for, which are held rather than aged out, and then that
  window close, heartbeat or registration is answered as this API's own "service unavailable" instead: a
  state that only the exit of the processes holding those records ends — a reap releases what a pid it reads
  gone was holding, and no reap before that one releases anything, because ageing is the whole of what a
  held record is being kept from — and one whose cost is a retry, where granting the call would have cost
  an endpoint that was never proven stopped. Nothing the registry cannot take
  right now — no room in the quarantine, no room in the process table, a registry sealed for a shutdown a
  successor will follow — reaches the caller as a container-rendered server error any more. An endpoint released on
  age alone stays owed for the life of the broker — named in every later fanout, unconfirmed, and still
  counted once the bound on remembered ids stops naming them — unless the fanout that lost it out from
  under it already held its acknowledgement, which is counted once, as fenced, and still owed to the next
  credential. An endpoint that comes back and is released again is that same single obligation — one that
  registers again after its record aged out included, which is one window rather than that live
  window plus a nameless aged-out obligation — a result cannot report a fence as confirmed while
  still counting a window as owed it, a journal that records an obligation from a generation now
  gone still counts it — one window, owed and unconfirmable, against the same journal without that
  record which confirms with none. The record is written by a broker that had no tombstone pending
  at all, the obligation being the endpoint's rather than some credential's, and a credential minted
  afterwards is unconfirmed on it; it names the window and carries the pid that can settle it; two
  incarnations of that one window are one record where the generation that watched them go counted
  two; and it is settled two ways and only those — the OS no longer having that pid, and that same
  window being a fence target here again, live or quarantined — while another window of that process
  registering leaves it owed, as does one still running unregistered, after which the quiet shutdown
  may compact the tombstone at last and the next credential is fenced everywhere. A record whose
  window is a fence target of the very revocation carrying it is that second settlement arriving in
  the same pass, and is counted once — the one window the fence went to, not that window and a
  record about it as well. A journal whose record of what is owed is a bare flag, a count, an entry
  with no window id, or one whose pid is missing, zero, negative, fractional or a string fails
  closed, as does a list longer than a generation could have named, while an empty list loads
  exactly as an absent one does. A journal with a second document appended fails closed rather than
  loading the prefix, and a window that registers after a revocation has already returned is fenced
  by the durable retry that follows it. A registration or
  heartbeat that would collapse a duplicate window id, that carries more windows than the bound, or that
  names an endpoint no request can be addressed to — an impossible port, a secret no HTTP header can carry —
  is refused whole: this payload is a process's entire window set, so keeping the entries that did parse
  would retire a window that is still serving on the word of one that could not. A payload that names no
  window list at all — the field absent, or holding something that is not a list — is refused on those same
  terms rather than read as a process reporting no windows: an empty list is a set the broker was told, a
  missing one is a set it was told nothing about, and retiring every window on it would silently unregister
  a process that asked for nothing of the kind. An explicit empty list still means exactly that and is
  accepted.
  It is a bad request over the internal API rather than a server error, and leaves the window set
  already reported untouched, as is a window id another live process already holds, while that same id from
  the process that owns it is that process reporting its own window and is accepted, while an acknowledgement held for an endpoint released on age alone survives
  every later retry of that same tombstone.
- Added the negative oracles for the Assistant's notes and the catalog: a warning about an option the turn
  never passed, a failed turn that must not also be told it ran on the CLI default, a rejected model id
  that must not be blamed on the effort picker, a refused value matched whole rather than as a prefix
  (`'high.foo'`, `highest`, and a dotted setting name are not a refusal of `high`), an option name likewise
  matched at its own boundary so a warning about `--effortless` is not one about `--effort` while all four
  spellings of the real option still are, the override key echoed back in a config or debug line that names
  the setting without refusing it and must produce no note, that same echoed key beside an unrelated line
  refusing a mistyped model id — either order, and across the two channels a turn reports through — which
  must produce none either while a refusal on the line that names the setting still does, that key on the
  very line that refuses the model — either order again — which is that model's failure and not this
  picker's, against the line that refuses both and the one that refuses the effort for a named model, which
  are this picker's, an effort the API calls unavailable, not available, or not enabled for the account,
  which must be read as refused as surely as an invalid one is, a cache entry whose
  visibility is a number or whose API-availability flag is not a boolean, which must stay offered and narrow
  its levels like any other rather than being hidden by a coerced reading, against the entry that does say
  hidden or unavailable and still is, a level whose name is prose, a newline, an
  over-long or a non-ASCII string that must reach no picker and a metadata file of two hundred levels
  bounded to the offered few, a turn that answered
  and must be told its effort was ignored rather than refused — including one that reported the rejection
  in the event stream and answered anyway, against a reply from a turn that then died and is a refusal
  still, and against one whose stream failed and answered nothing, which must be told nothing about an
  effort it never ran at, and against one that exited cleanly, reported nothing anywhere and said nothing
  either, which must be told its reply is missing rather than that it answered at the CLI's own setting —
  both ways a reply reaches the transcript, that same silent turn with no warning to quote either, which
  must still be given a plain account of itself in both providers, against the ones the stream's own error
  and a non-zero exit already account for, which must not be told twice, and reasoning, an error, and a reply
  of nothing but whitespace that are not one, a
  model under an inactive profile table that must not narrow the effort list, the same under a quoted
  `"profile"` key, the same again where that profile's value is a `"""…"""` one and where it is written
  over two lines, a `model` line inside a
  multi-line TOML string, an escaped delimiter inside such a value and one on its opening line that must
  not be read as its end, an array whose `]` shares the line that closed a string element of it that must
  still end the array so the key under it is read, both of those readings again for the ids the catalog
  offers — where a `model`
  written inside a string body must not be offered as a model, one written in a body reopened on the very
  line that closed the previous body must not either, and an escaped quote must not offer the part
  of the value before it as an id of its own — a cache entry whose id is a number and a level whose
  name is one, neither of which may reach a picker, a literal `'''` value that ends at every
  delimiter because it takes no escapes, a bracketed array element, and a multi-line delimiter
  inside a comment that must not be read as configuration, a table header whose quoted name contains
  `]` that must still end the top-level keys, a multi-line array whose element lines must not end
  them — and the header after it that must — a bracket inside a string value that must not swallow
  the keys after it, a model id keyed under a table that is not `[models]` — one under the table
  Codex keys by real ids to count how often each was mentioned, and one under a project path,
  neither of which may be offered while the same key under `[models]` is — and a `[models]` table
  that comes back after another table, whose keys must be read again, every spelling of one of that
  table's entries — a bare key, a quoted key dotted into, a bare-keyed header, and the top-level dotted
  and inline forms, all of which must be offered — against what only looks like one, a `[[models]]`
  element's keys, an unquoted `gpt-5.5-codex` that is two keys, and a `models."gpt-…"` under another
  table, none of which may be, a byte-order-marked config and
  cache that must still be read while a mark before a later table header or `profile` key must not
  hide either of them, a note that must not name who refused the value nor promise it lists one to
  pick, the complete effort fallback list, a duplicate failure line
  reported once while two failing items sharing a message are both reported, a runaway error loop elided
  once, the newest failure still classifiable past both bounds — including a reprinted preamble, at and
  under the remembered bound, whose decisive tail must not be treated as already kept — one huge failure
  truncated at the kept-text bound, a turn with no failure at all that must classify nothing, a stream
  failure that answered nothing on a turn that still exited 0 that must not become conversation history
  while one that recovered and answered must, a turn that answered nothing and reported nothing either,
  which must leave its question owed to the next handoff rather than marked accepted, a credential whose renewal fails that must still fence its
  turn's grant, an aged-out endpoint that must still block confirmation
  when a live endpoint carries the same window id, more endpoints owed a fence than the broker can name
  that must still be counted whole — and a broker that sends only their names, whose list is then all
  there is to count — an obligation past that bound that must travel to a successor as the process that
  owed it, one entry per process however many of its windows overflowed, still owed while that pid lives
  and while other windows of it register, settled by its death, and carried in a pid list held to the same
  fail-closed reading as the named records, one past that second bound as well, which nothing can record
  and must therefore keep its endpoint — a fence still sent to it, its window still reported
  unacknowledged, this broker still refusing to idle out or shut down while it holds one, and the record
  released only by that process dying — against the process already recorded, whose next unnamable window
  must fold onto the entry already there rather than pin an endpoint over it, and against the process
  already holding such a record, whose next one must be released and counted instead of pinning a second
  endpoint over it, two of its windows ageing out in the same pass included, so what is held grows with
  processes and not with windows; a registration, a heartbeat that retires a window and a window close that
  the quarantine has no room for, each of which must be answered as this API's own "service unavailable"
  rather than as a server error and must leave the registration it refused exactly as it was, against those
  same three calls while there is room; a shutdown asked for over that API rather than timed out, which must
  write this generation's obligations down before it acknowledges the exit, leaving a successor unable to
  report that endpoint fenced, against the journal whose own bounds are full, where the obligation must
  travel as its process while a pid entry can still be written and the write and the seal must both be
  refused when neither can — the registry left unsealed, the journal left exactly as it was, and the fence
  still owed here; and a broken open view that must not stop the refresh of the next one.
- The plugin's test JVM now runs against an in-memory `java.util.prefs` store rather than the real user
  preference tree, so a full `mvn verify` can no longer read or write the settings a live Protégé owns.
- Added macOS extended-ACL tests, which run only there: an entry granted on a store file, or on the store
  directory, after that path was read clean is still refused on the next reading of it and still refuses the
  write; a store directory and a file inside one, each carrying an entry before either was ever read, are
  refused by the reading that is about them rather than by the pass that covered them together; and a store
  created under a directory carrying inheritable entries is stripped, read, and usable, with nothing left
  on the file written into it.

### Fixed
- OkHttp 5's Kotlin runtime is now declared as an explicit embedded dependency and its root package
  cannot become a mandatory OSGi import, so the Protégé 5.6.6 bundle starts correctly on Java 17.
- A shared-broker instance whose heartbeats merely stalled no longer loses its clients' sessions. A stale
  reap of a process that is still alive quarantines its endpoints so it can come back, and session pins
  are now kept for exactly that long, instead of being dropped in the same pass and turning a recoverable
  gap into a permanent `session_window_closed`. Pins are also dropped when a quarantine is finally
  evicted, so nothing survives a window that is gone for good.
- Re-registering the same Protégé process now replaces that process's own earlier registration instead of
  accumulating one per registration, so a window that reconnects repeatedly can no longer exhaust the
  broker's process capacity and lock out other instances. A registration without an OS pid is refused
  outright (`invalid_pid`) rather than being stored as an unidentifiable entry, and every reap is logged
  with its cause, since it is invisible from the instance side. A payload whose window list could only be
  held incompletely is refused the same way (`invalid_windows`) instead of being absorbed: a second entry
  reusing a window id used to overwrite the first and a list past the per-process bound was truncated, either
  way leaving a live endpoint the broker could neither route to nor fence while a revocation still reported
  every window it knew about acknowledged. A payload carrying no window list at all is refused on the same
  terms rather than read as an empty set, which would have retired every window the sending process still
  had serving — the same silent unregistration, reached by saying nothing instead of by saying something
  unusable. A window id another live process already holds is refused on
  the same terms: an id names one endpoint for the whole broker, so a collision would leave routing — and
  every session pinned by that id — deciding between two endpoints on map order, and would advertise both
  under one path. Protégé windows carry random ids, so nothing honest collides; a broker spoken to by
  something else cannot quietly take a live session over. The list is indexed before anything is retired or replaced, so a
  refused register or heartbeat leaves the window set that instance last reported intact.
- A quarantined endpoint is now also released after 30 minutes, not only when its OS process is observed
  to have died. An operating system reuses process ids, so a quarantine whose pid had been handed to some
  other program stayed "alive" forever: the broker kept the retired endpoint, kept refusing to idle-exit,
  and held a slot against `MAX_PROCESSES` for as long as the machine was up. Retention runs from the
  latest retirement of that endpoint, so a window that comes back and closes again keeps its full
  revocation fence, and an exhausted quarantine releases what has expired rather than refusing the
  registrations and window closes whose own release pass would have drained it — except for records
  expiry is deliberately not allowed to release, which keep their slots and make that refusal the
  answer until their processes are gone. An endpoint let go on
  age alone was never observed to stop, so every later revocation still reports it unacknowledged and the
  internal API answers 503 instead of `revoked`: the bound is about the broker's memory, lifetime, and
  routing, and changes nothing about what a commit fence is allowed to claim. A record can also age out
  while the fence request to it is in flight, and the acknowledgement that arrives after it still counts —
  proof does not expire with the record it came through, so that window is reported once, as fenced, rather
  than counted twice and left unconfirmable for good, while the obligation stays owed for every other
  credential, none of which was ever fenced there. A process that dies while its own fence is in flight
  reads the other way round: a pid the OS reports dead is this registry's one proof that a backend
  stopped, so its endpoints are dropped rather than retired and the fanout that was mid-conversation
  with them drops them too — a window whose process the kernel has already reaped is not owed a fence
  that has nowhere left to land, where the call that watched it die used to report it unacknowledged
  and answer 503 for it. The pass that reads a pid dead settles that before anything ages out, so a
  retirement crossing the 30-minute bound in the very tick that proves its process gone is released as the
  death it is, rather than minted into a nameable obligation the broker would then report unacknowledged
  for the rest of its life. That proof also outlives the record across the retries
  that follow: a tombstone is re-sent for as long as it lives, and forgetting the acknowledgement when the
  endpoint's record was finally released turned a fence that demonstrably landed into an obligation no later
  retry could ever meet. One endpoint is one obligation however often it comes
  back and is released again, so a window that reconnects and stalls repeatedly is owed a fence once rather
  than once per release — including across the release itself: equal credentials are the same live endpoint
  by construction, so an endpoint whose record aged out and then registered again is counted once while the
  broker can still name it, as the live target it now is, instead of as that target plus a nameless
  aged-out obligation that no fence could ever discharge; past the bound on remembered names a repeat is
  counted again, which overstates what is unfenced rather than understating it. How many windows are owed is
  counted rather than measured off the ids the broker can still name, so past that bound the number the
  management view reports is the whole obligation and not the part of it that still has a name; a broker
  from an earlier release, which sends only the names, is read the way it always was. A fence counts as
  confirmed only when every counted window acknowledged it, not merely when no unacknowledged window could
  still be named, so an obligation past that bound can never be reported as a clean revocation, and only
  the boolean the fence contract defines counts as one: a reply answering with the string `"true"`, with
  `1`, or with any other shape is a reply this broker does not recognise, which is not a backend that
  installed a fence, and is read exactly as the malformed ones already were. An obligation like that also
  outlives the broker's own idle exit — the durable tombstones are kept rather than compacted away while
  one stands, because an empty registry says nothing is registered now and not that everything it once
  held has stopped, so a backend that comes back is fenced by the next broker instead of meeting a journal
  that was cleared on its behalf. An endpoint a fence request cannot even be built for is refused at
  registration on the terms every unreachable entry already met — a port past the highest one a URL can
  name, or an id or secret carrying characters no HTTP header can — and one somehow still held is reported
  as the unacknowledged window it is, instead of failing the whole fanout on an unhandled error that,
  the tombstone being durable already, would repeat on every retry and take the rest of that broker
  maintenance pass with it.
- A Codex turn that fails repeatedly no longer grows the error text the transcript keeps past its 4,000
  character bound, and the same failure line arriving many times in one turn is reported once instead of
  being repeated per event — keyed to the failing item, so two things going wrong with the same generic
  message are still both reported. A turn in a retry loop reports its failures up to a bound and then one
  line stating that the rest are not shown, and the newest failure stays available to the exit path that
  decides what the turn failed on, however much noise preceded it. One long enough to need cutting is kept
  from both ends: a Codex release refusing the setting outright says so in its first words, while an API
  refusing the value says it behind a status line and a JSON envelope.
- Two Assistant CLI events flushed onto a single line are now both delivered. Only the first JSON value on
  a line was read, so anything sharing it was dropped without trace — a `result` or `turn.completed` there
  took the turn's usage and its ending with it, and a text delta took part of the reply. A malformed
  remainder still leaves the values before it delivered rather than discarding the whole line.
- An Assistant turn that answered nothing is no longer recorded as the conversation, however cleanly it
  ended. Recording one marked that provider as having seen the turn, so the question its own session may
  never have accepted was dropped from the handoff for good — and a turn can end with no reply and nothing
  filed against it either: a CLI that complains about a refused reasoning effort on stderr and exits 0
  leaves a note and no reply, which every other test read as a completed turn. A turn becomes history when
  it completed, was not stopped, and answered. A reply is the whole of the evidence that the question was
  taken, and it counts even on a turn that reported a failure first, because Codex surfaces the failures of
  a retry loop as it goes and a turn can report one, recover, and still answer. That reading is cautious on
  purpose, and being cautious means a provider can be handed a question its own resumed session already
  holds — a stopped turn is left unsynced by the same rule, and the session it was killed in is not
  readable from here. So the handoff now says the overlap is possible and what to do with it: anything the
  provider recognises is the same turn rather than a new one and is not to be answered again, rather than
  the transcript arriving under a claim that the session missed all of it. Being wrong the other way is the
  one that cannot be undone — a question dropped from the handoff because a session this side cannot read
  was assumed to have taken it is gone from the conversation, where one that arrives twice is context.
- An Assistant turn that ended without an answer and without saying why now says so. A CLI that exits 0,
  reports nothing in its stream, prints no warning worth quoting and produces no reply left a blank
  exchange with no account of it anywhere — and, being a clean turn by every test, was filed as the
  conversation's reply, so the question went to the other provider as one already answered. Both CLIs
  now report it, as an error rather than a note, because a question that went unanswered is what it is.
  A turn that has an account already — its stream's own error, a non-zero exit, or the note naming the
  effort picker — is not told a second time.
- An Assistant credential whose lease lapses mid-turn is now revoked the way every other end of a turn
  revokes it, instead of the view dropping its reference. A tool invocation issued under that grant can
  still be inside a commit fence, and the credential carries the principal that expiry cleanup takes away
  with the token — so dropping it left that execution to finish on a turn the view had given up on.
- The stderr used to describe a failed turn is now read under the same lock its reader thread appends
  with. The wait for that reader can time out — a grandchild process inheriting stderr keeps the stream
  open — and reading the buffer mid-append can throw, which skipped the completion handler entirely and
  left the turn spinning with the input disabled until Protégé was restarted.

## [0.7.2] - 2026-07-20

**Prefix maintenance and executable release evidence complete the 0.7.2 hardening pass.** This patch
grows the public surface from 84 to **85 tools**, keeps **11 prompts**, and closes the remaining 0.6.0
evaluation checklist. The clean release-candidate reactor contains **3,459 JUnit tests** (3,125 plugin,
278 core, 56 CLI): zero failures/errors and one intentionally skipped opt-in performance test.

### Added
- `remove_prefix` deletes a single prefix binding from the active ontology's prefix map, so a mistyped
  prefix that `set_prefix` could only overwrite can now be removed. Like `set_prefix` it edits the
  document format directly (no `OWLOntologyChange`, not on the undo stack) and invalidates the SPARQL
  snapshot cache; it errors on an unregistered prefix or a format with no prefix map, and preserves every
  other binding including the standard `rdf`/`rdfs`/`owl`/`xsd` prefixes. This grows the public surface
  from 84 to **85 tools** (still **11 prompts**).

### Tests
- Pinned the `commit_change_set` → `undo_change` contract over a real Protégé `HistoryManager`: a committed
  multi-operation change set is reverted by exactly one undo, and the anomalous multi-entry case surfaces
  its `undo_log_warning` instead of silently under-reverting.
- Added an end-to-end HTTP test proving the backend rejects a co-resident process's forged principal
  envelope without the per-start broker secret and trusts it only behind that secret.
- Closed the remaining 0.6.0 evaluation checklist with explicit `run_qc_suite` request-control and
  `verify_import_lock` tamper/drift matrices; existing executable tests already pin waiver scope/expiry,
  all 11 prompt handlers, and import cycle/version-conflict reporting.

### Documentation
- Published and updated the commercial-platform interoperability guide for the complete 0.7.2
  headless QC/release boundary, and made `TESTING.md`'s tested source version part of the release
  consistency gate.
- Recorded the regression postmortem rules: fixes need pre-fix-red or mutation evidence, replacement
  behavior needs an explicit contract matrix, and tagging requires two independent clean methods.

## [0.7.1] - 2026-07-20

**Headless parity, least-privilege attribution, local reuse, and release hardening complete the 0.7 line.**
This patch grows the public surface from 83 to **84 tools** (adding the explicit `export_audit_log`)
and keeps **11 prompts**, while making the full project/release workflow available headlessly and
gating the supported live Protégé runtime. The clean release-candidate
reactor contains **3,446 JUnit tests** (3,112 plugin, 278 core, 56 CLI): zero failures/errors and one
intentionally skipped opt-in performance test.

### Added
- A checksum-pinned Protégé 5.6.6/Xvfb harness now gates releases and runs weekly with an explicit
  Java 17+ runtime. It proves the built OSGi bundle becomes active, rejects unauthenticated access,
  connects through the shared broker, keeps MCP sessions pinned across two live windows, observes an
  EDT-backed edit and removes it with exactly one Undo, obtains a real HermiT classification and
  explanation, and verifies application/broker shutdown. JSON evidence and runtime logs are
  retained as workflow artifacts; macOS/Windows packaging remains a short manual check.
- Versioned small/medium/large generated ontology fixtures now benchmark snapshot capture, HermiT reasoning,
  SPARQL cache construction, SHACL, semantic diff, and verified serialization. A reference-environment
  regression gate writes machine-readable measurements and runs in weekly and release CI without slowing
  the normal pull-request suite.
- `search_entities` now discovers standard and policy-configured preferred labels/synonyms, applies bounded
  preferred/fallback language ranking, explains every match and collision, and separates review-only
  `reuse_candidate` results from guaranteed exact grounds. Punned IRIs remain one ground, exact preferred-name
  ambiguity blocks minting without choosing a winner, and revision-cached lexical expansion reports its cap.
- Ontology Assistant turns now use non-persisted, non-refreshable, short-lived principals attributed to
  the provider and an opaque per-window chat/turn identity instead of the static local-admin token. A
  separate Assistant access setting can restrict chat to reads; the bounded write profile excludes
  server administration, external files, network, and unrestricted local-admin compatibility, and every
  launch/finish/Stop/dispose path revokes the credential.
- The MCP Server view's Connected-clients panel now also lists and revokes OAuth clients when the shared
  broker owns the endpoint. Revoking a client invalidates its tokens, drops its pinned sessions, terminates
  its in-flight and queued proxied requests at the broker, and confirms a commit fence across every
  registered window so no revoked work can commit once the fence is confirmed.
- Every plugin and headless tool call now records its authenticated/static principal, effective
  capabilities, operation, target, gate/change summary, confirmation references, and release-manifest
  link in a rotated owner-only per-workspace stream. Secret-bearing fields, prompts, attachments, and
  ontology content are excluded and defensively redacted. The new `export_audit_log` tool performs a
  bounded deterministic merge into an explicitly confirmed project-contained artifact; dry-run remains
  the default. Reducing `audit.max_files` prunes that workspace's excess numbered rotations on its next
  append under a loaded, valid policy — fallback defaults never prune — without adopting similarly named
  sibling streams. A temporarily invalid policy uses the schema's conservative maximum retention and
  rotation bounds, so it cannot erase same-workspace history that the last valid policy retained.
- A versioned offline plugin/CLI conformance fixture now pins QC stage and finding identity,
  semantic/closure fingerprints, HermiT identity, deterministic IRI/CURIE grounding, import-lock bytes,
  and portable release evidence across both execution surfaces.
- Reusable ontology CI now validates a PR policy only as a proposal, applies the base branch's trusted
  policy/assets to the confined candidate root ontology, and preserves full-QC JSON/JUnit/SARIF, dry-run
  release checksums, and asserted diff through a provenance-checked two-workflow artifact boundary.
- The standalone CLI distribution now embeds the HermiT `1.3.8.431` baseline with shaded-artifact
  satisfiability, inconsistency, role-chain, and rare-datatype probes. The executable excludes HermiT's
  unmaintained JAutomata implementation and supplies its narrow binary API through maintained Apache-2.0
  AutomataLib `0.12.1`. GPL/LGPL texts, corresponding HermiT source, pinned license evidence, third-party
  notices, and relinking instructions are assembled into a checksummed release compliance bundle; missing,
  legacy, or duplicate/inert reasoner dependencies fail the build or release before publication.
- CLI `validate` now captures one offline project snapshot and runs the policy-required subset of the
  eight QC stages with the bundled HermiT, emitting portable JSON, Markdown, JUnit, or SARIF evidence
  with strict gate exit codes.
- CLI `imports lock` previews or guardedly installs deterministic local-import locks, rejects source drift
  and project escapes, preserves replacement backups, and never trusts an old lock checksum while updating.
- CLI `release` now runs the shared full-QC/release gate offline, verifies optional baseline bundles, and
  either previews every checksummed artifact or publishes the complete release directory through guarded
  atomic replacement with a verified backup. Concurrent source/output changes and failed commits cannot
  expose a partial bundle; command results contain only project-relative paths.
- CLI `serve --transport stdio --project FILE` now exposes an eighteen-tool project-confined headless MCP
  surface for policy validation, reasoner/rule inspection, inference materialization, SSSOM mappings, full
  QC, import-lock verification/generation, release gate/preparation, and audit export. It shares the plugin's
  99-tool capability declaration, lists every unavailable live tool explicitly,
  defaults mutations to dry-run, stays offline, caps inbound/outbound JSON-RPC lines, and keeps stdout clean.
- Public MCP descriptions are validated to reject internal roadmap/decision identifiers, keeping tool
  documentation focused on supported behavior rather than repository planning codes.
- Every MCP tool now declares its required capabilities once at registration and rejects a request before
  handler execution when the propagated broker/standalone principal lacks them. Explicit OAuth `read`
  grants are ontology-read-only; exact ontology/release/filesystem/network/server scopes compose without
  implication. Existing `mcp` grants, omitted legacy scopes, and the static token retain local-admin
  compatibility, while unknown scopes fail before consent/token issuance.

### Changed
- Interrupted or timed-out callers now cancel queued Protégé model-thread work through one atomic state
  transition, so a failed request cannot mutate later when the UI queue drains. If the body had already
  started, the error explicitly warns that its effects may still complete instead of claiming cancellation.
- Inferred SPARQL/QC snapshots now bound each inference category independently. Subclass/equivalent-class,
  type, property-hierarchy, and property-assertion enumeration retain conservative per-category query
  admission limits and now also stop at a fixed actual-result budget. An over-budget category is dropped
  atomically instead of leaving a partial graph; an enumeration the active reasoner cannot answer is
  contained per generator — axioms the reasoner did answer are kept, matching 0.7.0 — and every omission
  is named in the result note. Ordinary ABoxes whose property assertions 0.7.0 admitted remain unaffected.
- Reusable ontology-CI callers must now provide the candidate `ontology` path, and trusted annotator
  callers must provide `expected_release_check`; update the paired workflows together when moving from
  the 0.7.0 interface.
- CLI `validate-policy` now accepts `--no-network` and `--no-external`, allowing CI to record its fixed
  offline posture and refuse a proposed external-path grant without running project QC.
- Plugin interoperability reports now use a project-relative manifest path, so release evidence no longer
  embeds a checkout-specific absolute path.

## [0.7.0] - 2026-07-18

**Release preparation, deterministic change review, and entailment-aware comparison are now first-class workflows.**
The public surface grows from **78 to 83 tools** and remains at **11 prompts**.

### Added
- `rebase_change_set` re-resolves an existing preview against the current workspace while preserving
  its original gate contract and producing a new, independently committable preview.
- `semantic_diff mode=inferred|both` classifies both sides with one captured reasoner configuration and
  reports bounded entailment deltas; `analyze_change_impact` reports exact direct effects plus bounded
  downstream dependency and module-ownership projections.
- `run_release_gate` and `prepare_release` validate one isolated policy/QC snapshot, enforce import and
  fingerprint stability, verify serialization, and produce deterministic manifests, RO-Crate metadata,
  and JSON, Markdown, JUnit, and SARIF reports through atomic project-confined writes.
- `write_project_policy_template` scaffolds reviewed general-OWL or OBO starter policies with actionable
  validation hints. A reusable fork-safe GitHub Actions workflow and examples provide headless policy and
  asserted-diff gates without executing pull-request-controlled artifacts.

### Changed
- Document load, project QC, and change-set preview accept explicit `network=deny|allow` and
  `lock_mode=ignore|verify|required` controls with most-restrictive-wins policy composition.
- The headless CLI adds `validate`, manifest-backed `diff --check`, distinct 0/1/2/3 exit codes,
  `--help`/`-h`/`help`, clean stderr, and newline-terminated JSON. Policy validation reports
  `policy_loaded=false` when the policy never reached evaluation.
- Reasoner references now share one unique-or-fail rule across policy validation, selection, semantic
  diff, and project QC: full names (and ids on surfaces that accept them) match exactly while a
  version-less whole-token name such as `HermiT` may resolve to exactly one installed version.
- Serialization detects format loss before writing; verified saves reject unsupported anonymous-individual
  round trips, while release evidence records incomplete baselines and import provenance explicitly.

### Fixed
- Invalid starter policies can create their declared root artifact only through the explicit
  `save_ontology policy_bootstrap=true` path, capability-checked and confined to the canonical project
  root without trusting policy-granted external-path widening.
- Reasoner ambiguity no longer silently selects a factory, including when factories share a display name;
  project-QC pre-gates and snapshot comparisons use the same reference semantics.
- Release, diff, impact, and CI paths fail closed on reasoner errors, snapshot drift, unsafe report or
  manifest paths, unverified bytes, network/lock violations, and untrusted workflow artifacts.

## [0.6.1] - 2026-07-17

**Project-policy intent is now enforced at every remaining direct I/O and change-set boundary.**
This patch release keeps the public surface at **78 tools and 11 prompts** while closing the
filesystem/network, locked-import, module-governance, and legacy verified-apply gaps left by 0.6.0.

### Added
- Request-scoped filesystem capabilities for project reads/writes and external paths. Policy-relative
  paths resolve below the canonical project root, symlink escapes fail closed, and external paths need
  both `filesystem.allow_external_paths: true` and the `filesystem:external` capability.
- Runtime enforcement of `network.default`, `network.allowed_hosts`, and the `imports.network`
  override. A denied remote import is stopped before dereference; authorized local workspace/catalog
  mappings still satisfy HTTP ontology IRIs offline, while direct `file:` imports and mapping targets
  are confined to the project filesystem. Nested `jar:` sources are refused, and host allowlists
  disable unchecked redirects.
- Module document checks for declared ontology-IRI mismatches, `modules[].owned_namespaces`
  governance (including explicit co-ownership), import-cycle warnings, and import identity/version/
  document conflict errors. Ownership violations mean *defining* a foreign term — any subject-position
  or constraining axiom (SubClassOf subject, class equivalence/disjointness/keys, property
  domain/range/characteristics/equivalence/inverse/chains, datatype definitions, individual identity
  and subject assertions, SWRL rule heads; ObjectInverseOf is unwrapped to the named property); bare supporting declarations and pure references (a foreign superclass or
  range) — the kind OWLAPI module extraction adds — are permitted. Matching is boundary-aware: a
  namespace ending alphanumeric owns the exact IRI or continuations across a structural separator
  (`/`, `#`, `:`) only, so owning `…/ns` never captures `…/ns2/…`, `…/ns-ext/…`, `…/ns.ext/…`, or
  `…/ns_ext/…`, and each entity is attributed to its most specific owned namespace. An uninspectable
  module document fails governance closed with `module_inspection_failed`, and module inspections are
  cached by document path and content hash so repeated policy loads stop re-parsing unchanged files.
- A compatibility preference that can disable unrestricted local-admin paths when no project policy
  is loaded.

### Changed
- `imports.mode: locked` now verifies the complete lock content automatically in
  `run_project_qc` and change-set preflight, including coordinates and SHA-256 content, instead of
  checking only that imports resolved and a lockfile existed. The gate additionally attests that the
  disk bytes it hashed are the content the isolated QC snapshot actually consumed: an unsaved
  in-memory edit of a locked import, or a document swapped around the load, fails closed with a
  distinct `imports.loaded_content_divergence` error. The attestation parses the whole locked
  closure once (leaf-first, no network) so legitimate cross-import typing does not false-fail, and
  it hashes the exact bytes it parses in a single read so a swap-around-parse cannot attest bytes
  nobody hashed. Relative lockfile/catalog paths resolve against the canonical `project_root` (never
  the process working directory), the documented explicit-path lockfile bootstrap works again while
  a discovered policy is still invalid (capability-checked, confined to the canonical project root),
  and beside-document lock/catalog defaults stay authorized under the no-policy compatibility opt-out.
- `apply_changes verify=report|rollback` now uses the same isolated policy/change-set gate as
  `preview_change_set`. Rollback mode rejects a failing delta before live mutation; report mode
  surfaces the same verdict and commits once after revision, policy, preflight-asset, and lock
  revalidation. With no policy loaded, `regression` keeps its released batch-attributed meaning: a
  `gate=fail` verdict is re-checked against the unchanged baseline, so a legacy unsatisfiable class
  or standing warning reports `baseline_gate` instead of blocking every batch, and
  `newly_unsatisfiable` names the classes this batch broke. Complete finding-identity sets supplement
  counts, so replacing a standing offender with a different one — even while the total count drops — is
  still a regression, while a pure removal is not. A policy gate stays absolute; a `gate=error` verdict
  cannot be computed, so `verify=rollback` fails closed (prevents the batch) while `verify=report`
  commits and reports `regression=false` with `gate=error`. Rollback with no policy and no selected reasoner is refused up
  front, as before 0.6.1. The committed delta is exactly the normalized delta the gate evaluated,
  the documented `timeout_ms` budget is honored (tighten-only under a policy), verify batches are
  not capped at the preview store's 2,000-operation bound, and the commit hop uses the same 120 s
  budget and timeout honesty as `commit_change_set`. Existing arguments and top-level
  operation/summary fields are unchanged — and on a prevented rollback or pre-commit conflict they
  now truthfully report that nothing landed.

### Fixed
- Caller-selected paths on save/load/merge/create, SHACL, module extraction, catalogs, import locks,
  CQ sidecars, and related policy/QC surfaces can no longer bypass project containment or request
  capabilities.
- `apply_changes` verified apply no longer relies on shared live undo history and therefore cannot
  undo an unrelated GUI edit. A rejected rollback reports `prevented_before_apply=true`.
  (`create_terms`/`create_properties` keep their documented reasoner-verified apply-then-rollback
  path; use their `preview=true` mode for gated, apply-nothing-on-failure intake.)
- `get_project_policy`, `validate_project_policy`, `run_project_qc`, and `get_model_revision` return
  their structured diagnostics (`valid=false` issues, `gate=error`, the revision envelope) for a
  loaded-but-invalid policy again; filesystem/network authorization still refuses at use time until
  the policy validates, and `verify=report` commits under the same invalid policy that
  `verify=none` accepts instead of misreporting `policy_conflict`.
- Argument-less `save_ontology` (and sidecar/catalog targets derived from the already-open document)
  no longer require the no-policy local-admin compatibility profile — the preference governs
  caller-selected paths, exactly as documented.
- Module policies can no longer name a file whose actual ontology IRI differs from the configured
  module IRI.
- `validate_catalog` reports a per-entry `policy_refused` status for a catalog entry the project
  policy will not read (outside `project_root` with external paths disabled) and keeps scanning,
  instead of aborting the whole catalog as a false "could not parse catalog" failure that dropped
  every other entry, the `nextCatalog` chain, and fabricated `unmapped_imports`.
- With no policy loaded, `apply_changes verify=rollback` attribution now diffs the complete
  unsatisfiable-class set, not the display-capped 25-item window, so an ontology with more than 25
  pre-existing unsatisfiable classes no longer has every clean batch conservatively prevented (and a
  genuinely new unsatisfiability is still named beyond the window).
- `apply_changes verify=report` no longer labels a batch a regression when the gate merely *errored*
  (for example an isolated classification timeout): it reports `regression=false` with `gate=error`
  so automation does not undo a good batch, while `verify=rollback` still fails closed on the same
  unverifiable gate. The baseline re-run is bracketed against concurrent workspace edits so a
  mid-attribution GUI change is flagged (`concurrent_change`) rather than misattributed.
- `diff_ontologies` with a `right_document` reports `right_document_unresolved_imports` (plus an
  explicit truncation caveat when `include_imports=true`), mirroring `semantic_diff`, so an
  unloadable import can no longer silently truncate the right closure and flip the verdict.
- Module ownership attribution now covers SWRL rule-head named-individual arguments, including
  `SameIndividual`/`DifferentIndividuals` head atoms, so a foreign module cannot assert identity on
  another module's individual without a violation.
- A malformed `policy_path` (invalid platform path) returns the tool's structured
  `policy_path_invalid` envelope again instead of a bare error, explicit-`policy_path` diagnostics
  work even when a discovered invalid policy has no resolvable `project_root`, and a no-policy network
  denial attributes the compatibility preference rather than a non-existent `network.default`.
- Under a confining project policy — network-restricted (`network.default: deny` or a host allowlist)
  or filesystem-confined (`allow_external_paths: false`) — a folder `catalog-v001.xml` can no longer
  dereference anything over the network, or read a file outside the project, before authorization. The
  catalog's own XML resolver follows delegations (`<nextCatalog>`/`<delegateURI>`) and a DOCTYPE's
  external DTD/entities during resolution (a pre-authorization SSRF, and — with a confined filesystem —
  an arbitrary out-of-project/XXE file read), before any mapping is authorized. The folder-catalog
  resolver is therefore installed only for a catalog that is in-project, is not a symlink, parses
  cleanly, and contains no DOCTYPE and no delegation element; otherwise the resolver is not installed,
  and a load whose closure needs any import resolution fails with a clear reason (inline the mappings
  into `catalog-v001.xml` to resolve them offline) — whether an import was left unresolved or had to
  resolve through a lower-priority channel (a workspace mapping, a direct file read, or a permitted
  network fetch) that the refused catalog could pin to different reviewed content. An unsafe sibling
  catalog does not block a document that needs no external import resolution (a vacuous self-import
  stays permitted: OWLAPI links it in memory before any mapper could run). A delegation-free catalog's `<uri>`/`<rewrite*>` targets
  are still resolved locally and re-gated by the existing mapping authorization. When both axes are
  fully open (or no policy is loaded), the full catalog resolver is unchanged.
- `apply_changes verify=rollback` now attributes non-reasoner stages (profile, structural, governance)
  by diffing the complete stable gating-finding identity set, so a batch that removes standing
  violations while introducing a fresh one — lowering the total count — is still prevented, while a
  pure removal (or shrinking a standing group) is correctly treated as an improvement and allowed.

### Testing
- Added adversarial coverage for symlink escapes, external-path dual authorization, no-policy
  compatibility denial, host allowlists, import-network overrides, pre-dereference remote-import
  blocking, direct/catalog-mapped local-import escapes, nested `jar:file:` sources, module namespace
  co-ownership/violations, module IRI mismatches, tampered locked imports, partial-error batch parity,
  and rollback/report parity on the isolated change-set gate.


## [0.6.0] - 2026-07-15

**Project policy is now an executable runtime contract: Protégé MCP discovers and validates checked-in
policy, fingerprints the live ontology, resolves persisted validation assets, and produces a strict,
reproducible project-QC gate.** The completed milestone now spans workspace-safe transactions, verified
artifacts, semantic release diff, import locks, and headless reuse. The public surface grows from **66 to 78
tools**; guided prompts remain 11.

### Added
- **Three project-policy tools:** `get_project_policy`, `validate_project_policy`, and `run_project_qc`.
  Policy discovery walks upward from the active ontology (or accepts an explicit path), applies deterministic
  defaults, validates YAML/schema/semantic constraints, and reports a canonical policy digest. Path resolution
  rejects URLs, traversal, and symlink escapes before reading validation assets; glob results are sorted,
  deduplicated, and bounded.
- **Standards interoperability in project-policy v1:** every policy carries an attached ontology-project
  RO-Crate and a required `interoperability` QC stage. Recommendations 1.0, 1.1, 1.2, and 1.3 are validated
  with their exact contexts, on-disk metadata filenames, version-appropriate profile entities, the base
  specifications' root-entity requirements (`description`, ISO 8601 `datePublished`, `license`), and
  string-only unambiguous `@context` declarations; 1.1 is the broad-compatibility default, while 1.2/1.3
  can be selected explicitly or inferred from an existing crate's unambiguous normative context (legacy
  contexts are never silently adopted). W3C RDFC-1.0 + SHA-256 supplies a serializer/blank-node-independent
  root RDF dataset fingerprint distinct from fingerprint v2, pinned to the official W3C rdf-canon vectors
  with code-point-ordered canonical quads and a timeout that bounds the whole computation. The serialized
  dataset is verified assertion-by-assertion before hashing: rootless anonymous-individual structures the
  OWL RDF rendering drops silently (unanchored reference cycles, anonymous inverse-property pairs, negative
  assertions among unanchored anonymous individuals, self-referential anonymous type expressions,
  sameAs/differentFrom-linked anonymous cycles) fail
  the digest closed, because distinct datasets would otherwise fingerprint identically, while faithfully
  rendered shapes (anchored cycles, self-loops, trees) keep fingerprinting.
- **Extractable RO-Crate validation package:** bounded, offline project-profile validation lives behind
  public library-owned types in core's dependency-clean `ro_crate` package (JDK + Jackson only — no
  Protégé, OWLAPI, or MCP imports, pinned by a seam test), preserving a clean future split into its
  own Git project. Titanium RDFC stays on the Java-17-compatible
  2.0.0 line because 3.0.0 requires Java 21.
- **Canonical ontology fingerprint v2**, exposed by project QC. Its semantic digest is
  independent of axiom insertion order, prefixes, document location, and serialization-added declarations;
  the separate document digest covers document coordinates, format, prefixes, and import-lock content.
  Anonymous individuals are explicitly marked `session_only` and `release_stable=false` without exposing raw
  blank-node identifiers.
- **Policy-driven persisted QC assets:** ROBOT-style invariant files with strict headers, persisted competency
  questions, and multi-file SHACL data across supported RDF formats. Required inferred checks fail closed when
  inferred data cannot be produced. Project governance, reasoner/profile/structural checks, invariants,
  competency questions, and SHACL all feed one `pass` / policy `fail` / execution `error` aggregate.
- **One isolated validation snapshot, including reasoning:** every QC stage consumes one private copy of the
  active ontology and its loaded imports. The selected reasoner's exact Protégé plugin configuration and
  recommended buffering mode are captured with it; one private instance supplies consistency, unsatisfiable
  classes, and inferred SPARQL materialization without classifying/querying the live reasoner. Import coordinates
  remain no-network, timeout discards stale results, and configuration/runtime policy mismatches are explicit.
- **Reasoner configuration parity and reusable construction path:** explanation and inconsistency tools now
  pass the selected plugin's exact configuration rather than factory defaults. The explanation engine's required
  non-buffering override is reported, and timed-out hidden reasoners are interrupted and confined to private data.
- **Import integrity inspection and strict loading:** `inspect_imports` returns a deterministic direct/transitive
  graph with resolved documents, local/remote source types, missing imports, strongly connected cycles, and
  loaded identity/version/document conflicts. `load_ontology` and `merge_ontology_document` add
  `missing_imports=warn|error|silent` and list the resolved imports actually used.
- **Workspace revision and transactional editing:** `get_model_revision` exposes the complete workspace/session/
  semantic/document envelope. `preview_change_set`, `commit_change_set`, and `discard_change_set` provide bounded,
  memory-only isolated preflight, exact expected-revision commits, policy/asset revalidation, post-confirmation
  read-only checks, and one-broadcast Undo logging. `create_terms` and `create_properties` can use the same path.
- **Verified artifacts and semantic release evidence:** `save_ontology` adds strict temporary serialize/reload,
  exact annotated-axiom/header comparison, guarded no-overwrite publication, backups, checksums, and final
  live-revision checks.
  `semantic_diff` classifies asserted header/entity/rename/annotation/lifecycle/axiom changes and conservative
  compatibility without pretending inferred comparison ran.
- **Deterministic import dependency controls:** `write_import_lock`, `verify_import_lock`, and `validate_catalog`
  operate without network access, confine relative lock paths, reject duplicate/malformed content, hash local
  artifacts, and report catalog/import disagreement.
- **Reusable core and standalone CLI:** a Maven parent now enforces `core`, `plugin`, and `cli`
  boundaries. The
  existing OSGi filename remains stable, while `protege-mcp-cli-0.6.0-all.jar` runs policy validation and asserted
  semantic diff on Java 17 without Protégé. Release automation publishes both artifacts, SHA-256 sidecars, license,
  and third-party notices and smoke-tests the CLI from a clean temporary directory.
- **Trusted broker principal prototype:** broker-authenticated requests propagate a versioned, secret-free principal
  only behind the per-window broker secret; spoofed client headers are stripped, sessions are pinned to client/grant,
  and authenticated admin endpoints list/revoke clients and invalidate pinned sessions.
- Adversarial coverage for policy discovery/defaulting, malformed and duplicate YAML, schema/semantic errors,
  path and symlink confinement, oversized assets, deterministic fingerprints, save/reload stability,
  anonymous individuals, required-stage degradation, concurrent edits, reasoner changes, and multi-format
  SHACL unions, import graph ordering/cycles/conflicts, strict missing-import failure, workspace import reuse,
  catalog precedence, live/import mutation after isolated capture, exact reasoner configuration identity,
  buffering/fresh-entity policy mismatches, import-spanning unsatisfiability, inferred-data parity, and timeout
  interruption. The clean release build contains
  **2,832 tests** with zero failures, errors, or skips.

### Changed
- `run_qc_suite` accepts additive governance/required-stage controls and can return common strict-gate details;
  its legacy arguments, default stages, and optional-stage behavior remain unchanged.
- `audit_ontology`, `model_domain`, and `release_readiness_check` now prefer validated project policy and
  `run_project_qc`, while retaining the existing interactive workflow when no policy exists.
- Project-policy defaults gate at `warning`, so governance and structural warnings cannot silently pass. A
  policy requiring a reasoner must name it explicitly; selection/configuration is captured atomically with the
  ontology snapshot.
- Policy governance now enforces configured label-language/preferred cardinality, definition
  presence/language/string datatype/non-placeholder content, lifecycle status/replacement integrity, and visible
  focus/global waivers; expired waivers become findings.
- The `audit_ontology`, `add_subclass_safely`, `model_domain`, and `refactor_entity_safely` prompts prefer
  revision → preview change set → commit, retaining the existing direct-edit workflow as a compatibility
  fallback; `find_and_fix_unsatisfiable` deliberately stays on `apply_changes verify=report`, because
  mid-repair commits on an already-broken ontology would fail the result-state preview gate.
  `add_subclass_safely` now distinguishes a missing no-policy selection, a required-stage
  error, and a policy that intentionally omits reasoning; the last case requests a policy decision or explicit
  approval of the reviewed change set as unverified instead of looping through ineffective reasoner selection.
  Existing required arguments and default direct behavior remain unchanged.
- The Ontology Assistant now steers its CLI onto the same transactional path: each Claude invocation
  appends a write-workflow system prompt, and each new Codex thread opens with the same preamble (a
  resumed thread already carries it in its history). The steering directs axiom edits through
  `preview_change_set`/batch `preview=true` → `commit_change_set` with the complete previewed revision as
  `expected_revision`, keeps high-level operations without a change-set equivalent
  (rename/move/deprecate/delete, document operations) on their own preview options, confines the direct
  axiom-edit fallback to servers that lack the change-set tools — observed as unknown-tool failures,
  never claimed by message content — or to an explicitly user-directed edit, always with an explicit
  disclosure, and forbids workarounds of read-only mode, write confirmation, or a failed gate. The
  steering grants no bypass and no new capability — the read-only/confirm-write gates apply to every
  write tool unchanged; the isolated QC and revision re-checks are what the change-set path adds.
- Document loading retains the existing continue-and-report behavior as the `warn` default. Strict `error`
  aborts before workspace attachment or active-ontology mutation; `silent` requires an explicit request.

### Fixed
- Project QC no longer has a live-classification/snapshot gap: a concurrent active/import edit after capture
  cannot alter the private reasoner or any other stage, and no before/after race comparison is needed.
- Missing, malformed, skipped, timed-out, or inference-degraded required stages now remain execution errors;
  they cannot be collapsed into violations or successful empty results.
- Prefix-only GUI edits, edits during preflight, confirmation-time changes, changed policy assets/import locks, and
  cross-principal broker session replay now fail closed without applying a partial ontology delta.
- The headless asserted diff now satisfies every declared import from one shared private empty placeholder
  (a single temporary file per document, however many imports are declared) instead of fetching it or relying
  on silent missing-import handling, so an untrusted ontology cannot trigger HTTP, localhost, or
  metadata-service access — and Manchester-syntax documents with imports (whose OWLAPI parser cannot
  tolerate a missing import) still diff instead of aborting.
- Import-lock generation now rechecks the live model revision, read-only setting, effective policy, and target path
  immediately before atomic installation; stale off-thread captures fail closed without replacing either path. Both
  hops share the long slow-but-succeeding EDT bound, and a timed-out install says the lock may still land.
- `semantic_diff` now applies its shared output `limit` to rename-candidate samples as documented.
- Live-smoke fix (OSGi-only, invisible to the flat-classpath test suite): the isolated validation
  snapshot builds a JDK dynamic `Proxy` of `OWLModelManager`, whose generation links every package in
  that interface's whole super-interface/signature closure. bnd's `org.protege.editor.owl.*` wildcard
  imports only statically-referenced packages, so seven signature-only packages
  (`org.protege.editor.core`, `…owl.model.hierarchy`, `…io`, `…library`, `…selection.ontologies`,
  `…ui.error`, `…ui.explanation`, `…ui.renderer`) were absent from the bundle `Import-Package` and
  `preview_change_set`/`run_qc_suite` structural failed under Felix with
  "referenced from a method is not visible from class loader". They are now force-imported in the
  plugin manifest. See smoke-test step 52.
- The alternating adversarial review's follow-up round: `preview_change_set`'s summary `no_ops` now uses
  the same final-set semantics as the batch paths (both halves of a cancelled add→remove pair count);
  batch previews reject an oversized normalized change list before the set simulation runs; `ttl_seconds`
  is validated before any QC work; cache-capacity eviction never evicts an entry that is mid-commit (the
  new preview is refused instead of corrupting the in-flight commit); and the size-limit refusals name
  the offending count and the limit.
- The no-policy `preview_change_set` gate could pass an edit that made a class unsatisfiable, because the
  default stages excluded the reasoner. The default gate now includes the reasoner stage whenever a
  reasoner is selected — inconsistency or any unsatisfiable class in the changed snapshot refuses the
  commit — and every preview shape (invalidated ones included) reports `satisfiability_checked`, with an
  explicit note when no reasoner verdict gated it. A selected reasoner whose capture fails, or one
  deselected between the preview's probe and its QC snapshot, fails the gate closed instead of silently
  previewing without the verdict; a reasoner selected only after the probe still
  gates, because the stage is always scheduled and evaluates whatever selection the QC snapshot captures. A
  runtime failure to obtain the reasoner manager now fails closed too (only a successful lookup with no current
  factory means Protégé's None selection), and the unchecked-preview note distinguishes missing/omitted
  reasoning from a failed reasoner execution.
- Batch change-set previews (`create_terms`/`create_properties` with `preview=true`) now normalize with
  the same set simulation as operation previews: a duplicated axiom within the batch or an axiom already
  asserted in the active ontology becomes a reported no-op instead of inflating
  `normalized_changes`/`effective_changes` beyond the axioms that actually land. The direct (non-preview)
  batch path reports the same honesty — `applied` counts only axioms that landed, `no_ops` appears when
  any were skipped, and a re-created entity carries `already_existed` — the batch preview accepts the
  same `gates` override as `preview_change_set`, and preview size accounting now includes every retained
  structure (operation rows, reasons, and policy paths), so a mostly-no-op preview or a giant
  caller-supplied path cannot bypass the per-entry memory limit. Add/remove cancellation now reports the same
  final-set `no_ops` count in preview and direct batch paths. Known retained-size/change-count violations are
  rejected before QC, and curation previews cap their new batch input at 2,000 items, so an inevitably refused
  request cannot first consume an unbounded validation pass.
- Live IOF Biopharma validation exposed three OWLAPI-boundary defects: ontology-level profile violations
  with no backing axiom no longer abort `run_project_qc`; the fingerprint derives implicit declarations only
  from the active document's own axioms/ontology annotations, so loaded import content cannot change its
  semantic digest; and `semantic_diff include_imports=false` now keeps imported entities out of entity,
  rename, annotation, and compatibility classification. `get_model_revision` and `undo_change` also state
  Protégé's saved-state dirty semantics explicitly: Undo restores content but Protégé keeps the dirty flag
  until the next save.
- The independent verification of that round hardened the same OWLAPI boundary further. Profile violations
  with no backing axiom are now attributed instead of silently counted as imported: one on the audited
  ontology's own header (an undeclared annotation property used in its header annotations, a reserved or
  relative ontology IRI) fails the owned profile gate fail-closed, while an import-header violation stays
  `imported_violations` context. `run_governance_audit`'s profile snapshot now keeps the audited root's
  ontology ID and every closure member's header annotations (previously an anonymous axiom-only merge, so
  ontology-ID and header violations were silently invisible to that tool while `run_project_qc` reported
  them). And the remaining cached-signature surfaces derive from the document's own content through one
  shared core helper: `summarize_ontology` and match-all `search_entities` no longer list loaded-import
  entities for the active scope, and `validate_ontology`'s audit signature cannot flag an import's
  used-but-undeclared term against the active ontology.
- Import IRIs with unsupported URL schemes (including opaque `urn:` / `tag:` IRIs) now obey
  `load_ontology` / `merge_ontology_document`'s documented
  `missing_imports=warn|error|silent` contract instead of escaping OWLAPI as an unchecked
  `OWLOntologyFactoryNotFoundException`; workspace and sibling-catalog mappings still take precedence,
  and a streaming-parser fallback does not misreport a valid URN self-import as missing.
- Catalog aliases whose import IRI differs from the imported ontology/version IRI now survive the loader's
  temporary-manager-to-workspace MOVE boundary. The declaration-to-target edges are restored and verified
  before activation, so strict loading, `inspect_imports`, and the live closure agree instead of silently
  dropping imported axioms. Edge restoration also clears a stale failed-import marker left on the workspace
  manager by an earlier failed load, instead of refusing the project for the rest of the session.
  Resolved non-primary URNs are also removed from fallback missing reports, and
  malformed IRIs containing URI-illegal characters stay inside the `warn|error|silent` contract.
- Verified save reload now blocks the import closure behind one private empty document, so verification cannot
  contact the network or exhaust memory by loading a release-scale closure. It still compares every direct import
  declaration exactly, and it normalizes only serializer-materialized, unannotated declarations implied by the
  document's own signature, avoiding false RDF/XML mismatches without hiding unrelated declaration changes.
- Verified save and fingerprint v2 now normalize Manchester's materialized built-in frames and OWLAPI 4's
  RDF 1.1-equivalent plain / `xsd:string` datatype declarations. Manchester and Turtle artifacts therefore no
  longer false-fail or change their semantic digest. Verified/atomic/backup save explicitly rejects anonymous
  individuals before writing because parser-local blank-node ids cannot support exact round-trip comparison.
- `semantic_diff` uses the same declaration normalization without reporting synthetic declarations as changes,
  renders annotation assertions canonically instead of through identity hash strings, applies `limit=0` to
  annotation changes, and uses locale-independent entity/type keys. A verified artifact now self-diffs as
  identical without declaration noise.
- QC fail-closed behavior now covers indeterminate truncated `COUNT` / `EXACT_ROWS` competency-question
  results — an exact graph count or an already-satisfied lower bound still passes, with a caveat — plus
  malformed or duplicate ontology-annotation CQs, strict required-stage selection, and legacy limit bounds.
  Import-lock verification rejects unknown root fields and concurrent workspace drift, and runs its revision
  pins under the same extended wait bound as writes. Parser aggregates are bounded to actionable samples in
  parser try order instead of forwarding every parser stack trace.
- Release workflow-dispatch tags are passed through environment variables and syntax-validated before any shell
  use, preventing expression-injected shell commands.

### Compatibility
- Existing tools and prompts keep their required arguments and legacy defaults. The new policy surface and
  strict outputs are additive; projects without `.protege-mcp/project.yaml` continue to use the established
  interactive tools. The public plugin descriptor intentionally remains on the published 0.5.1 asset until
  the 0.6.0 release is uploaded.

## [0.5.1] - 2026-07-13

**A compatibility-and-contract hardening release: the 0.5.0 MCP surface is now guarded by machine-readable
goldens, and the first versioned project-policy/QC contracts are defined and adversarially tested without
changing interactive runtime behavior.** Tool and guided-prompt counts remain **66 + 11**.

### Added
- **0.5.0 public-contract snapshots and compatibility tests** for all 66 tool registrations and 11 guided
  prompts. Tool goldens capture names, descriptions, complete input schemas, and the manual's documented
  result fields; prompt goldens capture argument contracts and deterministic rendered messages. The harness
  rejects removed/changed arguments, new required prompt arguments, dropped documented result fields,
  unreviewed tool-description or prompt-text drift, and duplicate or undocumented registrations while
  permitting explicitly reviewed additive optional surface. Published baselines are immutable, prompt
  documentation is checked one-for-one, and canonical snapshots use LF on every platform.
- **Project-policy v1 JSON Schema plus three validated YAML examples**: minimal, general OWL, and OBO-oriented.
  The schema rejects unknown/future fields and malformed core types, constrains stage/profile/severity
  vocabularies and timeouts, and requires a lockfile or validation asset block when the selected mode/stage
  needs it. Filesystem and network defaults are explicit in every example.
- **Surface-neutral ontology-engineering contracts** for project coordinates, full workspace/session/
  semantic/document revision envelopes, findings, validator stages, checksum artifacts, and aggregate gates.
  Their JSON shape is published as a packaged versioned schema. Both direct/Jackson construction and the
  pure gate aggregator distinguish a policy `fail` from an execution `error`; a required
  missing/skipped/errored stage fails closed and can never become a vacuous pass. A supplied strict mapper
  rejects unknown and duplicate JSON fields.
- Documentation for the policy/contract boundary, schema limitations, examples, gate semantics, and golden
  regeneration workflow.

### Fixed
- Surefire now resolves the aligned embedded Jackson 2.20.1 stack before Protégé's provided OSGi jars.
  Protégé privately contains an old `JsonFactory`; OSGi isolates it in the application, but the flat test
  classpath could combine it with new databind/networknt classes when the real JSON Schema validator ran.
- Policy/contract schemas now enforce explicit IRI, date, canonical UUID, safe drive-path, DNS/IP host, and
  gate-detail patterns even when a validator treats JSON Schema `format` as annotation-only.
- Release preparation no longer advertises a nonexistent future jar. The public plugin descriptor remains on
  the last published asset until the tag workflow succeeds; CI checks the advertised URL on `develop` and
  `main`, requires the newest release section to stay byte-identical across both changelog mirrors, and runs
  the strict post-publication registry gate on `main`, so a stale or future descriptor turns `main` red
  immediately.
- The testing guide now records the verified counts: 2,488 tests shipped at `v0.5.0` despite that tag retaining
  a stale 2,044 label, and the current adversarial suite contains 2,523 tests.

### Compatibility
- Existing MCP tools, guided prompts, arguments, and interactive defaults are unchanged. A policy file is not
  discovered or executed in 0.5.1; `get_project_policy`, strict project QC, fingerprints/change sets, import
  locking, and release gates remain subsequent roadmap work.

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
- **Release metadata is now checked automatically.** CI and the tag workflow validate the Maven, OSGi,
  server, design, documentation, readme, download-URL, and changelog version mirrors before a release can
  publish, preventing a stale or internally inconsistent release bundle.
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
- **`extract_module`** — signature-based **locality module extraction** (the interactive analogue of `robot extract`), using the OWL API's `SyntacticLocalityModuleExtractor`. Give a seed `signature` (entity names or full IRIs; punned IRIs bring every sense) and a `module_type` — **STAR** (default — smallest, both directions), **BOT** (⊥ — what the seeds *use*: their superclasses/definitions), or **TOP** (⊤ — what *uses* the seeds: their subtree) — over `source` = `imports_closure` (default) or `active`. The module is loaded as a new workspace ontology (`iri` names it) or, with `path`, saved to a file (format from the extension). The **STAR fixpoint runs off the UI thread** (only seed resolution + the closure snapshot are on the model thread, bounded by `timeout_ms`), the workhorse dependency ships inside the bundled OWL API (no new dependency), and the tool is gated like every other write (read-only + write-confirmation, both delivery modes).
- **`create_terms`** — **batch term-request intake**: the array form of `create_term`, applied as **ONE undoable transaction** (one `undo_change` reverts every term). Each item takes the same fields as `create_term`; top-level `namespace` / `definition_property` act as **defaults** for any term that omits its own. The batch is **atomic** — a malformed term (or a duplicate IRI within the batch) aborts the whole batch with an indexed error, applying nothing — and `strict=true` refuses it if any operand would be minted as a new, empty entity. A term may reference another term in the same batch **by full IRI** (nothing is in the ontology until the batch commits).
- **`shacl_validate`** — validate the active ontology's imports-closure RDF against a **SHACL shapes graph** (embedded **Apache Jena SHACL**), the constraint-validation counterpart to `verify_ontology`'s SPARQL invariants. Shapes are supplied **inline** as Turtle or from a **local file** (a URL scheme is refused — offline by design, like `sparql_query`); validation runs over the asserted triples by default or the reasoner's inferences (`include_inferred`). Reports `conforms` plus, per result, the focus node, result path, value, severity, constraint component, source shape and message. The `run_qc_suite` **`shacl` stage** (previously reserved) is now wired to it via `shacl_shapes` / `shacl_shapes_path`.

### Improved
- **Read tools are paginated.** `list_classes`, `search_entities` and `get_axioms_for_entity` now take an `offset` alongside `limit` and return `count` / `offset` / `returned` / `items` / **`next_offset`** — pass a returned `next_offset` back as `offset` to page forward and enumerate a signature (or an entity's referencing axioms) larger than one page, instead of getting a single truncated blob. The sorts are **totally ordered** (entities by display then IRI; axioms by rendering then the axiom's own natural order), so paging never drops or repeats an item across a page boundary. The non-paginated `entityList`/`axiomList` shape used by every other tool is unchanged.
- **SPARQL queries reuse an edit-versioned snapshot cache.** `sparql_query` previously copied the whole imports closure, serialised it to RDF and re-parsed it into Jena on **every** call. It now caches the serialised snapshot, keyed by a monotonic model-state version bumped by an ontology-change listener and a model-manager listener (edits, imports, load/reload, **reasoner classification**, active-ontology switch — and a `set_prefix` edit invalidates it explicitly, while a GUI-side prefix edit is caught by revalidating the live prefix map on a cache hit). A repeated query at the same model state skips the rebuild; a query after any change transparently rebuilds. Each query still re-parses the cached **immutable** bytes into a *fresh* Jena model, so nothing mutable is shared across the multi-threaded transport. Separate slots keep the asserted and inferred (`include_inferred`) snapshots from thrashing; the cache is released (its listeners removed) when the server stops.

### Fixed
- **CURIE operands resolve.** A registered-prefix CURIE (e.g. `ex:Widget`) passed to any operand or to `get_entity` is now **expanded via the active ontology's prefix map** before being treated as an IRI, resolving to the imported term — instead of silently minting a junk entity whose IRI was the literal string `ex:Widget`. Applies to entity / class-expression / data-range operands, the annotation subject, and `get_entity`.
- **OWL 2 profile check separates owned from imported.** `validate_governance` (and the `run_qc_suite` `profile` stage) now partition profile violations into the audited scope's **own** axioms versus those inherited from imports (`owned_in_profile` / `imported_violations`); the profile QC stage gates on the **owned** conformance, so importing a non-DL upstream ontology no longer swamps or fails a clean module.
- **`apply_changes` reports minted entities in its summary.** The batch `summary.new_entities` aggregate was computed after the changes were applied (when the entities already existed) and read empty; it is now computed pre-apply and lists them, matching the per-operation rows.
- **`search_entities` is self-consistent.** A `best_match` resolved via a label the substring finder missed is now surfaced in `items` too (type-filter-aware), so a non-null `best_match` no longer accompanies an empty result set.
- **`run_qc_suite` annotates a vacuous pass.** When zero stages actually run (every requested stage skipped), the `pass` gate now carries a `note` making the vacuous pass explicit.

### Notes
- New method-level tests for every core: SLME extraction (BOT pulls the seed's superclass, TOP pulls its subtree, STAR includes a defined seed's definition) + `module_type` parsing; the batch-curation apply (atomic one-transaction, strict refusal, minted-operand reporting) + defaults merge; the paginated windows (windowing, stable paging across boundaries, offset-past-end, **zero/negative limit and near-`MAX_VALUE` limit** edge cases); and the snapshot cache (get/store/staleness, separate slots, `invalidate`, and ontology-change invalidation via a headless model-manager double). A six-finding adversarial review was folded in before release: **`extract_module` file export is now gated** (it was bypassing the read-only + confirm-writes gates and could overwrite an arbitrary path); a **`set_prefix`** edit now invalidates the SPARQL cache (was serving stale prefixes); the pagination window is computed in long arithmetic and only advertises `next_offset` on forward progress (a zero/`MAX_VALUE` limit no longer emits a self-referential, infinite-loop cursor); a **duplicate IRI within a `create_terms` batch** is rejected rather than silently merged; and the SPARQL cache's listeners are re-removed on the EDT if server-stop's bounded cleanup times out (no listener leak across restarts). A follow-up Codex pass then hardened two more: a **GUI-side prefix edit** (Active ontology ▸ Prefixes, which fires no listener) is now caught by revalidating the live prefix map on a cache hit, and the paginated entity sort is **locale-independent** (`Locale.ROOT`, matching the search ranking) so a non-English JVM locale cannot reorder pages. Test suite **2044 → 2095**. Requires a **Java 17+** JVM (unchanged); no new runtime dependency.

## [0.4.0] - 2026-07-01

**Safe, testable LLM-assisted authoring.** Move the assistant from a "confident editor" to a "safe,
testable editor" by closing the **propose → ground → verify → confirm** loop and adding a re-runnable
**requirements (competency-question) suite** — all built by reusing shipping primitives (the single-undo
transactional apply, the embedded reasoner, Jena ARQ, `OWLEntityFinder`, the catalog sidecar pattern).
**55 → 61 tools.**

### New tools
- **`add_competency_question` / `list_competency_questions` / `remove_competency_question` / `run_competency_questions`** — a re-runnable **requirements suite**. A competency question pairs an executable SPARQL query with an expected result — `nonEmpty` (default) / `empty` / `count OP N` (`OP` ∈ `>=,<=,==,>,<`) / `exactRows` — and `run` re-checks them all against **one shared point-in-time snapshot**, so a curation edit that quietly breaks a requirement is caught like a failing unit test. CQs are stored via a small storage SPI with three conventions: **`robot-sparql-dir`** (the default — a `cqs/` folder of `*.rq` files with header-comment metadata, for ROBOT/CI interop), **`sidecar-manifest`** (a full-fidelity `<basename>-cqs.json` with a `version: 1` contract, unknown-key-preserving, written atomically), and **`ontology-annotations`** (CQs stored inside the artifact — the fallback when the ontology is unsaved). `list` detects the convention(s), `add`/`remove` operate in a chosen one (explicit `convention` wins > single detected > default), and `run` is convention-agnostic. Malformed input is isolated (a bad `.rq`/manifest entry is skipped-with-reason, never fatal); mandatory caveats (open-world `empty`, truncated results/inferences) are surfaced, never silent.
- **`verify_ontology`** — run project-defined SPARQL **invariants** (like ROBOT `verify`): each `queries[]` item is a **SELECT or ASK** whose **results are violations** (a returned row / ASK true flags it, at the item's `error`/`warn`/`info` severity) — a graph-producing `CONSTRUCT`/`DESCRIBE` is *not* a detector and is rejected (use `sparql_query` for those). Runs over a shared off-EDT snapshot (`UPDATE`/`SERVICE` rejected); violations are reported as raw SPARQL bindings — never rendered through the UI thread. The overall `gate` fails when a violation reaches `fail_on` (default `error`); a check that **cannot run** — a query that errors, an `include_inferred` invariant with no classified reasoner, or a rejected non-SELECT/ASK form — fails **fail-closed** (it never silently degrades to the asserted triples and reports a false pass).
- **`run_qc_suite`** — one aggregate quality-control gate. Composable stages (default `reasoner` + `profile` + `structural`), plus opt-in `invariants`, `cqs`, and a reserved `shacl` — all evaluated against one shared snapshot and collapsed to a single verdict. A stage whose backing data is absent (no classified reasoner, no invariants, no CQs, no SHACL) is **skipped with a reason, never an error**; the gate is the worst *ran* stage versus `fail_on`.

### Improved
- **`apply_changes` gains `verify=none | report | rollback`** — reasoner-verified apply. With `report` or `rollback`, the batch is applied as one undoable transaction, the reasoner is classified **off the UI thread**, and the result is checked for a **regression** caused *by this batch* — a class that became unsatisfiable (`postUnsat \ preUnsat`) or an ontology that became inconsistent. `report` keeps the batch and returns the verdict; `rollback` additionally reverts the whole batch in one undo when a regression is attributable. The pre-read → apply → classify → post-read → undo sequence runs under a **server-level write mutex** (MCP handlers are multi-threaded), and an intervening GUI edit between apply and re-classification degrades to `report` semantics rather than blind-undoing. Warm reasoner = 1 classification, cold = 2; a `timeout_ms` bounds each.
- **`search_entities` is now grounding-aware** (additive fields — **note the ordering change below**). Each hit carries a **`score`** and a **`match_kind`** (`exact` | `prefix` | `substring` | `fuzzy` — the exact tier considers every `rdfs:label` language variant and the IRI local name, case/whitespace/diacritic-folded), and the result adds **`best_match`** (the IRI the query grounds to, or null) and **`would_mint`** (true when a single-term query grounds to nothing, so using it as a `create_*` name would introduce a NEW entity — a full-IRI / Manchester / multi-word query is never flagged). This lets an assistant decide whether to reuse a term or mint one.

### Behavior change
- **`search_entities` results are now RANKED**, not just display-sorted: the top-level `items[]` are ordered by `score` (exact → prefix → substring → fuzzy), then display, then IRI (a stable tiebreak so the finder's `Set` order can't leak). Clients that relied on the previous purely alphabetical order should sort explicitly. The `count`/`items`/`truncated` shape is otherwise unchanged, and every other tool's `entityList` ordering is untouched.

### Notes
- New method-level tests for every core (F1 regression decision, F2 ranking + mint prediction incl. multi-language-label and diacritic cases, F3 expectation judging + `exactRows` set/bnode handling + each store's load/upsert/remove round-trip incl. malformed-skip + selection precedence, F4 violation detection + fail-closed gate, F5 stage aggregation + no-reasoner skip) **and for the tool wrappers** (`verify_ontology` / the four competency-question tools — schema, arg parsing, store selection/aggregation, and the run/remove branches, driven end-to-end over a headless `OntologyAccess`), plus a headless CQ add → run → remove pipeline. Three adversarial review rounds were folded in before release: an eight-finding first round; a second round that hardened `verify_ontology` — an `include_inferred` invariant with no reasoner now **fails closed** instead of silently degrading to the asserted triples, and a `CONSTRUCT`/`DESCRIBE` invariant is now **rejected** (SELECT/ASK only); and a third round that fixed `run_qc_suite`'s aggregation — a **warn/info-severity** invariant that *cannot run* now surfaces as `WARN` (so `fail_on=warn` trips it) instead of being swallowed to `PASS`, and the `cqs` stage now surfaces per-CQ degradation caveats. Test count **1720 → 2036**.
- The default `robot-sparql-dir` needs **no new serialization dependency** (plain `.rq` + header comments); `sidecar-manifest` uses JSON (`jackson-databind`, already a direct dependency). Requires a **Java 17+** JVM (unchanged).

### Hardening (folded into the 0.4.0 re-cut)
Post-authoring remediation from a codebase self-assessment — no user-facing tool changes (still **61 tools**); safety-net, security, build, and hygiene only. Test count **2,036 → 2,044**.
- **Security — the Claude MCP bearer token no longer lands on the process command line.** `ClaudeCliProvider` writes the `--mcp-config` JSON (which carries the token) to an **owner-only `0600` temp file** and passes its **path** on the argv, deleting it when the turn's process exits — so a local co-tenant can no longer read it via `ps` / `/proc/<pid>/cmdline`. (Codex already passed its token by env var.)
- **Testing — the reasoner-verified rollback path is now CI-gated**, not only manually smoke-tested. A test-scoped DL reasoner (HermiT, OWLAPI-4 build) classifies a genuinely unsatisfiable class / inconsistent ontology, whose verdict flows through the production `ApplyVerify.unsatIris(OWLReasoner)` iteration into `decide` → **rollback** (undone) vs **report** (kept + flagged). The live EDT/`HistoryManager.undo()` leg stays in `docs/smoke-test.md`.
- **Build & CI — coverage is measured and gated.** Added **JaCoCo** (`report` + a `check` floor on the `tools`/`server`/`oauth` layers); CI and release now run `mvn -B clean verify`. Added **Dependabot** (Maven + GitHub Actions). Aligned all `jackson-*` modules via **`jackson-bom`** — the transitive `jackson-dataformat-yaml` no longer lags (`2.18.3` → `2.20.1`), removing the version skew.
- **Internal / quality.** The write-confirmation dialog moved behind an injected `WriteConfirmer` seam, so the `tools` layer no longer imports Swing (fails **closed** when confirmation is required but none is wired); unexpected tool-handler exceptions are now logged server-side; deduplicated the `renderMinted` / reserved-vocabulary helpers; narrowed an overly broad `catch` in `EntityResolver.asIri`; fixed a dangling `{@link}`; removed stray `.class` cruft. Added `SECURITY.md`, a vulnerability-reporting policy, and issue/PR templates.

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
- New: a headless, CI-runnable pipeline smoke test (`ToolPipelineTest`) that drives the tool cores end-to-end — load → edit → validate → govern → diff → SPARQL — plus a manual live-Protégé checklist in [`docs/smoke-test.md`](docs/smoke-test.md) for the GUI/reasoner/transport legs the unit tests cannot reach. Test count **84 → 97**.
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
