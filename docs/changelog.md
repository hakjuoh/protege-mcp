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
- **CURIE operands resolve.** A registered-prefix CURIE (e.g. `bfo:BFO_0000031`) passed to any operand or to `get_entity` is now **expanded via the active ontology's prefix map** before being treated as an IRI, resolving to the imported term — instead of silently minting a junk entity whose IRI was the literal string `bfo:BFO_0000031`. Applies to entity / class-expression / data-range operands, the annotation subject, and `get_entity`.
- **OWL 2 profile check separates owned from imported.** `validate_governance` (and the `run_qc_suite` `profile` stage) now partition profile violations into the audited scope's **own** axioms versus those inherited from imports (`owned_in_profile` / `imported_violations`); the profile QC stage gates on the **owned** conformance, so importing a non-DL upstream (e.g. BFO) no longer swamps or fails a clean module.
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
