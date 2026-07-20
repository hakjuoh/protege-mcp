---
title: Live smoke test
parent: Contributing
nav_order: 2
---

# Live smoke test (Protégé + MCP)

The unit tests (`mvn test`) cover the tool cores in isolation, and `ToolPipelineTest` chains them
end-to-end **headlessly** (load → edit → validate → govern → diff → SPARQL) so a cross-tool regression
fails in CI. The weekly and release workflows also run `scripts/live-integration/run.sh` under Xvfb
against a checksum-pinned Protégé 5.6.6 distribution and an explicit Java 17+ runtime. That harness
requires the built OSGi bundle to become active before it tests the static-token endpoint, two live
windows and session pinning, an EDT-backed write with exactly one Undo transaction, HermiT
classification and explanation, and final application/broker shutdown. Its JSON result and
runtime logs are uploaded as workflow artifacts.

The longer checklist below remains useful for platform packaging, visual responsiveness, OAuth consent,
and workflows that are too broad or interactive for the bounded Linux harness. Run the applicable parts
against the built `v{{ site.version }}` jar before publishing a release.

## Automated Linux harness

After building the bundle, run the same gate locally on a Linux host with Xvfb, Python 3, `curl`, and
`unzip`:

```bash
mvn -B -pl plugin -am -DskipTests package
xvfb-run --auto-servernum bash scripts/live-integration/run.sh
```

The Protégé download URL and SHA-256 are fixed in the script. Set `PROTEGE_ARCHIVE` to reuse a local
copy; checksum verification still applies. Evidence is written to `plugin/target/live-integration/`.

## Platform packaging check

For each release candidate on macOS and Windows:

1. Install the native Protégé 5.6.6 package, replace any older copy of the plugin jar, and confirm the
   operating system's signature, quarantine, or SmartScreen flow does not hide or duplicate the jar.
2. Launch with `PROTEGE_JAVA_HOME` pointing to Java 17+, confirm **MCP Server** becomes live, and perform
   one authenticated read, one GUI-visible edit/Undo, and one HermiT classification.
3. Open a second window and instance, confirm session routing stays on its original ontology, then quit
   every instance and confirm the broker and configured port are released.

## Setup

1. `mvn clean package` and copy `plugin/target/protege-mcp-{{ site.version }}.jar` into
   `/Applications/Protégé.app/Contents/plugins/` (replace the older versioned jar — keep exactly one).
2. Launch Protégé on a **Java 17+** JVM (`PROTEGE_JAVA_HOME`), open the **MCP Server** view, and
   **Start** the server. Note the bound URL and bearer token.
3. Point an MCP client at it (e.g. `tools/chat-repro.sh "$(pbpaste)" "…" "http://127.0.0.1:8123/mcp"`,
   or the in-app **Ontology Assistant** tab).

## Flow (each step should return structured JSON, no error, and be visible in the GUI)

| # | Tool call | Expect |
| --- | --- | --- |
| 1 | `load_ontology` a real ontology (e.g. a multi-module ontology) | becomes the active ontology; imports resolved |
| 2 | `get_ontology_context` | signature counts, imports, prefixes, reasoner state |
| 3 | `create_term` name=`SmokeTestThing`, parent=`owl:Thing`, definition=`…` | one entity + label + definition + parent, **one** undo entry |
| 4 | `create_property` name=`smokeRelatesTo`, domain/range/characteristics | one property with its axioms, one undo entry |
| 5 | `move_class` the new term under a different parent | named parent replaced; subtree intact |
| 6 | `validate_ontology` | modelling-quality report; new term clean |
| 7 | `validate_governance` owl_profile=`DL`, required_annotations=`[label, definition]` | profile verdict + governance findings; **UI stays responsive** on a large closure (profile check runs off-EDT) |
| 8 | `run_reasoner` then `get_inferred_superclasses` | classifies; inferred relations returned |
| 9 | `get_explanations` for a **property-hierarchy** entailment | falls back to entailed=true + `related_axioms` (structural context), not an error |
| 10 | `sparql_query` a `SELECT` touching the new term | the new term appears in the results |
| 11 | `save_ontology` then `diff_ontologies` against the saved document | `identical=true` |
| 12 | `undo_change` ×N / `redo_change` | each macro reverts as a single step |
| 13 | `deprecate_entity` the new term, `replaced_by` another | `owl:deprecated true` + replaced-by pointer in the GUI |

Step 11's `identical=true` holds only for ontologies without anonymous individuals: with blank nodes the
reloaded document mints fresh NodeIDs, and the diff reports exactly that added/removed pair
(`save_ontology verify_round_trip=true` refuses such ontologies for the same reason).

A policy placed at a module's own directory confines every explicit path AND catalog-mapped sibling
import to that root — for a multi-module layout with sibling catalogs, put `.protege-mcp/project.yaml`
at the repository root so the project root spans all modules.

### 0.4.0 — safe, testable authoring (needs a reasoner selected + a saved ontology)

| # | Tool call | Expect |
| --- | --- | --- |
| 14 | `search_entities` query=an existing class **name** | ranked `items` with `score`/`match_kind`; `would_mint=false`, `best_match`=its IRI |
| 15 | `search_entities` query=a novel single word | `would_mint=true`, `best_match=null` |
| 16 | `apply_changes` `verify=rollback` with a clean batch (e.g. a `subclass_of` between two satisfiable classes) | `verification_path=change_set_preflight`, `gate=pass`, `verify.applied=true`; the edit stays and is one undo entry |
| 17 | `apply_changes` `verify=rollback` with a batch that fails the effective gate (e.g. makes a class unsatisfiable or violates required governance) | `gate=fail`, `regression=true`, `prevented_before_apply=true`, `applied=false`; the live model/Undo history never received the delta |
| 18 | Repeat #17 with `verify=report` | the same failing `preflight` is returned with `applied=true`; the edit **stays** (undo it manually) |
| 19 | `add_competency_question` text=`…`, query=a `SELECT`, expected=`nonEmpty` | echoes `convention` + `target` (a `cqs/…​.rq` file appears next to the document) |
| 20 | `list_competency_questions` | the CQ appears, tagged with its `convention` |
| 21 | `run_competency_questions` | per-CQ `pass`, overall `{passed, failed, gate}` |
| 22 | `verify_ontology` with an invariant that matches nothing, then one that matches | first `gate=pass`; second reports the violation rows (as bindings) and `gate=fail` at `fail_on=error` |
| 23 | `run_qc_suite` (default stages) | `reasoner`/`profile`/`structural` each `ran` with a verdict; overall `gate` |
| 24 | `run_qc_suite` before `run_reasoner` (fresh load, with a reasoner selected) | the `reasoner` stage is `ran:true` with an isolated verdict while the live reasoner remains uninitialized; selecting `None` instead yields `ran:false` with a skip reason, **not** an error |
| 25 | `remove_competency_question` the CQ | `removed=true`; the `.rq` file is gone |

### 0.4.3 — previews, save-all, inconsistency triage

| # | Tool call | Expect |
| --- | --- | --- |
| 26 | Edit two loaded ontologies, `list_ontologies`, then `save_ontology` `all=true` | edited rows show `dirty:true` + top-level `dirty_count`; save-all returns `saved:[{ontology,path}]`, a file-less (never-saved) ontology lands in `skipped:[{ontology,reason}]`, and the dirty flags clear |
| 27 | `save_ontology` path=`…/smoke.foo`, then path=`…/smoke.obo` | `.foo` is an error naming the supported extensions (no silent RDF/XML fallback); `.obo` saves as OBO |
| 28 | `rename_entity` / `delete_entity` with `preview=true` (try in read-only mode too) | rename: `old_iri`/`new_iri`, `changes`, `new_iri_already_in_signature`, `rewritten_axioms_sample`; delete: `would_delete`, `removed_axioms`, `removed_axioms_sample`; **nothing** changes in the GUI or undo stack |
| 29 | `merge_ontology_document` `preview=true` against a local document | `would_copy` / `total_changes` (+ `already_present_axioms` on a plain merge); the active ontology is untouched |
| 30 | `undo_change` `peek=true` after a macro (e.g. `create_term`) | `next_undo.changes` + `sample` of `{op, axiom}` rows and `undo_depth`; nothing is undone (repeat the peek — same answer) |
| 31 | `run_reasoner` with **ELK** selected and a SWRL rule present | classification succeeds but the result carries `warning` (ELK silently ignores rules); `run_qc_suite`'s `reasoner` stage `findings_summary` shows the same `warning` without gating |
| 32 | `explain_inconsistency` on a deliberately inconsistent scratch ontology (e.g. an individual asserted into two disjoint classes) | `inconsistent=true`, a small `justification` with `minimal=true`; **UI stays responsive** (search runs off-EDT over a private copy); `get_explanations`/`execute_dl_query` etc. over the same ontology return a pointed error naming `explain_inconsistency` |
| 33 | `create_terms` `verify=rollback` with a term whose `parents` are two disjoint classes | `verify.regression=true`, `verify.rolled_back=true`, `newly_unsatisfiable` names the term; no terms remain (one undo entry reverted) |

### 0.5.0 — shared broker / multi-window / multi-instance

| # | Step | Expect |
| --- | --- | --- |
| 34 | Start the server, then open a **second window** and a **second Protégé instance** (macOS: `open -n -a Protégé`) | no `Failed to bind` in any window's log; a single broker `java` process is running (`pgrep -f broker.BrokerMain`; its log is `~/.protege-mcp/broker.log`) and every window registers with it |
| 35 | Point an MCP client at `http://127.0.0.1:8123/mcp` (any registered window's bearer token works) | tools respond; the whole MCP session stays **pinned to one window** (`get_active_ontology` is stable across calls while other windows change ontologies) |
| 36 | `GET http://127.0.0.1:8123/instances` (authenticated) | every registered window/instance is listed; `/instances/{id}/mcp` reaches the chosen one |
| 37 | Quit the Protégé instances one by one, then check again after the **last** one exits | `pgrep -f broker.BrokerMain` finds nothing within ~20 s (the broker lingers briefly before self-exit) and port 8123 is free |
| 38 | Kill the broker process while Protégé runs, then **Stop/Start** in the MCP Server view | a fresh broker is spawned (or the server degrades to standalone with the view showing the actual URL); the in-app Ontology Assistant keeps working throughout |

### 0.5.1 — compatibility and packaged contracts

| # | Step | Expect |
| --- | --- | --- |
| 39 | Connect a fresh MCP client and inspect initialize/list responses | server version is `0.5.1`; exactly 66 tools and 11 prompts are advertised, with the same required arguments as 0.5.0 |
| 40 | Open a project containing a `.protege-mcp/project.yaml`, then use the existing context/QC tools | existing interactive behavior is unchanged; 0.5.1 does **not** auto-discover or execute the proposed policy and advertises no unfinished project-policy tools |
| 41 | Inspect the built jar with `jar tf plugin/target/protege-mcp-{{ site.version }}.jar` | both `schema/project-policy-v1.schema.json` and `schema/ontology-engineering-contracts-v1.schema.json` are packaged |

### 0.6.0 — executable project policy and strict QC

| # | Step | Expect |
| --- | --- | --- |
| 42 | Connect a fresh MCP client and inspect initialize/list responses | server version is `0.6.1`; exactly 78 tools and 11 prompts are advertised; the 0.5.0 required arguments remain compatible |
| 43 | Save a valid `.protege-mcp/project.yaml` above the active ontology and call `get_project_policy`, then remove it and repeat | the first call reports the discovered path, deterministic defaults/digest, and `valid=true`; the second reports `policy_loaded=false` without changing legacy QC behavior |
| 44 | Call `validate_project_policy` with a missing required asset, a traversal/URL path, and a symlink escaping the project root | every case is rejected before QC with a path-specific validation issue; no external file is read |
| 45 | Run `run_project_qc` once with a clean required invariant, once with a matching violation, and once with a malformed or missing required invariant | the gates are respectively `pass`, `fail`, and `error`; malformed/missing execution never becomes a policy failure or vacuous pass |
| 46 | Configure a required reasoner different from the selected reasoner; then select the required reasoner without classifying it and run QC while making a GUI edit | mismatch returns `gate=error`; the matching run uses the captured private reasoner, leaves the live reasoner status unchanged, reports `reasoner_configuration`, and consistently evaluates the pre-edit snapshot |
| 47 | Run project QC over an ontology containing an anonymous individual | the result exposes `fingerprint_stability=session_only`, `release_stable=false`, and a warning without leaking a raw blank-node identifier |
| 48 | Configure persisted invariant/CQ directories and SHACL files in more than one supported RDF format | every resolved asset is listed deterministically and evaluated; an inferred-required query that cannot obtain inferred data yields `gate=error` |
| 49 | Inspect `plugin/target/protege-mcp-{{ site.version }}.jar` | both versioned schemas are packaged and the OSGi/MCP versions report `0.6.1` |
| 50 | Run project QC with reasoner + profile + structural + governance + invariant + CQ + a deliberately slow SHACL stage, then make a GUI label edit after validation starts | the GUI remains responsive; `validation_snapshot.mode=isolated`, `same_snapshot=true`, and its `stages` list names all seven stages; the result reflects the pre-edit snapshot while the GUI edit remains live, never a mixture |
| 51 | Set a short project reasoner timeout and run a deliberately slow/private test reasoner (or controlled slow fixture) | reasoner stage is `error`, the late private result is never accepted, the live ontology/reasoner/Undo history is unchanged, and a subsequent QC run is unaffected |
| 52 | On the **real OSGi runtime** (not a headless test), call `preview_change_set` with any operation and `run_qc_suite stages=[structural]` | the preflight/isolated snapshot runs to a real `gate`, **never** `preflight_error: … referenced from a method is not visible from class loader …`. The isolated snapshot builds a JDK dynamic `Proxy` of `OWLModelManager`; under Felix that proxy must link every package in the interface's whole super-interface/signature closure, which bnd's `org.protege.editor.owl.*` wildcard only imports when statically referenced — so a Protégé upgrade adding a signature-only package silently reappears here and only here (flat-classpath unit tests always pass). The `Import-Package` force-imports in `plugin/pom.xml` list that closure; re-derive it if this fails |

### 0.6.1 — enforced project boundaries

| # | Step | Expect |
| --- | --- | --- |
| 53 | With a valid policy, pass a relative direct path and then a symlink that escapes `project_root` to a document/SHACL/module/catalog tool | the relative path resolves below the canonical root; the symlink escape is refused before I/O |
| 54 | With a read-scoped OAuth client, try a project file and an outside path; then authorize exact filesystem scopes and repeat with `filesystem.allow_external_paths` false/true | the read-only grant is denied before either tool handler runs; project access additionally needs `filesystem:project:read`/`write`; outside access additionally needs `filesystem:external` and policy opt-in. Broker and standalone endpoints return the same decisions. |
| 55 | Set network/import modes and a host allowlist; load a denied remote import, then map the same HTTP ontology IRI to a local catalog file | denied dereference fails explicitly without a request; the local mapping succeeds offline; allowlisted root URLs do not follow redirects outside the allowlist |
| 56 | Write a matching import lock, run project QC, modify one imported file, and rerun QC/change-set preview | the first gate can pass; both later gates return `error`, `imports.lock_mismatch`, and `import_lock_verification.valid=false` |
| 57 | Configure a module with the wrong ontology IRI, then a subject-position axiom **defining** a term under another module's `owned_namespaces` (a bare foreign declaration must NOT trip it); also test an explicit co-owner | the wrong file IRI is a policy error; the foreign definition fails governance while the bare declaration passes; a declared co-owner passes |
| 58 | Load imports with a cycle and with an ontology/version/document identity conflict | governance reports `import_cycle` as warning and `import_identity_conflict` as error |
| 59 | Disable the no-policy local-admin path compatibility preference and retry a caller-selected path without a policy | the path is refused and `get_project_policy.path_mode` reports `policy_required` |

### 0.7.0 — release pipeline, deterministic previews, unified reasoner references

| # | Step | Expect |
| --- | --- | --- |
| 60 | Connect a fresh MCP client and inspect initialize/list responses | server version is `0.7.0`; exactly 83 tools and 11 prompts are advertised; the OSGi bundle, `--version`, and docs report the same version |
| 61 | `preview_change_set` any operation on an ontology carrying a standing structural warning, with no policy loaded; try to commit; then repeat the preview with explicit `gates=["reasoner"]` | the default gate (reasoner when selected + profile + governance + structural, `fail_on=warn`) returns `committable=false` with the warning in `preflight`; `commit_change_set` of that id refuses with `error_code=change_set_not_committable`; the `gates=["reasoner"]` preview returns `committable=true` |
| 62 | Commit a preview with a stale `expected_revision` (make a live edit between preview and commit), then `rebase_change_set` and commit the rebased preview | the stale commit returns `error_code=revision_conflict` with both `base_revision` and `current_revision` envelopes; the rebase creates a NEW preview at the current revision replaying the ORIGINAL gate contract, with `resolution_verified=true`; committing it succeeds with exactly one Undo entry and `single_broadcast=true` |
| 63 | `semantic_diff` `mode=both` against a previously saved document after an annotation edit and a subsumption-changing edit | the asserted delta is categorized (`annotation_changes` with lifecycle/replacement categories); the `inferred` subsumption category names the exact changed edge; the `reasoner` block records the classifying reasoner; blank-node churn is honestly caveated via `anonymous_individual_churn`, never reported as certain real change |
| 64 | `analyze_change_impact` once with a diff pair (`left`/`right_document`) and once with a `change_set_id` from `preview_change_set` | both forms return exact counts with bounded samples; the downstream sweep discloses its depth/size caps via `search_truncated` when hit; a category that could not be computed carries a reason instead of being silently absent |
| 65 | `write_project_policy_template` on a fresh project, then `validate_project_policy` | a schema-valid starter policy is written; its version-less `reasoning.reasoner` name (e.g. `HermiT`) resolves uniquely against the installed reasoner, so the policy validates once the referenced assets exist without editing the reasoner line; `validation_hint` lists the remaining steps (create the `root_artifact` via `save_ontology policy_bootstrap=true`, the RO-Crate metadata file, …) |
| 66 | `save_ontology` `path=<root_artifact>` while the starter policy is still invalid, then retry with `policy_bootstrap=true` | the plain save is refused fail-closed with a hint naming `policy_bootstrap=true`; the retry saves inside the project root and the result carries `policy_bootstrap.used=true` with `policy_errors` and `contained_root` |
| 67 | `run_project_qc` on a policy-valid project; separately, on an ontology using reserved RDF/RDFS/OWL vocabulary as an annotation property | all required stages run against ONE isolated snapshot (`validation_snapshot.same_snapshot=true`); the interoperability stage errors fail-closed naming the reserved property IRI, never a vacuous pass |
| 68 | `run_release_gate` with a remote-backed import under the default `network=deny`; also on an ontology containing an anonymous individual | the release stage raises `imports.remote_backed`; the anonymous individual degrades the fingerprint to `session_only` and raises `release.fingerprint_unstable`; `manifest_preview` reports `manifest_available=false` with a `reason` |
| 69 | `prepare_release` while the gate fails, then `dry_run=false` on a clean project | the failing call returns `prepared=false` and writes NOTHING; the clean call atomically writes `manifest.json`, `reports/qc.{json,md,xml,sarif}`, and the RO-Crate into the output directory, and each on-disk artifact's sha256 equals its manifest entry |
| 70 | `save_ontology` `verify_round_trip=true` on an ontology carrying an anonymous individual, then on a clean ontology | the blank-node save is refused with the documented anonymous-individual message before any temporary or target artifact is written (no partial write); the clean save verifies with `round_trip.round_trip_class=byte_for_byte` |
| 71 | `write_import_lock` on a closure with a remote-backed or outside-lock-directory import, then on a fully local closure; tamper with one locked file (or add an extra entry) and `verify_import_lock`; finally `load_ontology` `lock_mode=required` with no lockfile | the first write returns `written=false` with per-import reasons in `errors`; the local closure writes a deterministic lock; verification of the tampered/extra entry reports `valid=false` naming it in `mismatched_entries`/`extra_entries`; the lockfile-less required load is refused naming `write_import_lock` |
| 72 | Headless CLI: `--help`; `validate-policy --project <policy>` on the valid project; `diff --left <release>/manifest.json --right <artifact> --check` | `--help` prints usage to stdout and exits `0`; validate-policy exits `0` with the SAME `policy_digest` as `get_project_policy`; the manifest operand is sha256-verified before diffing and the identical diff exits `0`; stderr stays free of SLF4J noise on every command |

### 0.7.1 — headless parity, attribution, local reuse, and hardening

| # | Step | Expect |
| --- | --- | --- |
| 73 | Connect a fresh MCP client, inspect initialize/list responses, and run the CLI with `--version` | server, bundle, CLI, and docs report `0.7.1`; exactly 84 tools and 11 prompts are advertised |
| 74 | Run plugin `run_project_qc` and CLI `validate` against the conformance fixture | both report the same stage/finding identities, fingerprints, HermiT identity, import-lock bytes, and portable release evidence; the fixture policy's five required stages (interoperability, reasoner, profile, governance, structural) all run |
| 75 | Run CLI `imports lock` and `release` first as dry runs, then as writes; modify a source immediately before each commit | previews write nothing; clean writes install complete checksum-verified output with backups; either source drift refuses the commit without a partial lock or release directory |
| 76 | Start `serve --transport stdio --project <policy>`, initialize it, and list/call tools | stdout contains only bounded JSON-RPC; the eight supported project tools work offline and every GUI-only tool is listed explicitly as unavailable |
| 77 | With an OAuth `read` grant, attempt ontology mutation, release, project-file, external-file, network, and server-admin tools; then retry with the exact scopes | every under-scoped call is rejected before its handler; exact grants authorize only their declared operation; static/legacy local-admin compatibility remains explicit |
| 78 | Send one Ontology Assistant read turn and one allowed write turn, then inspect/export audit | each turn uses a distinct short-lived Assistant principal; audit identifies the principal, operation, target, gate/change summary, and release link without tokens, prompts, attachments, or ontology content; export is dry-run by default and project-confined when confirmed |
| 79 | Search a preferred label, configured synonym, diacritic/whitespace variant, exact-name collision, and fuzzy-only candidate | results explain source/property/language/normalization/score; collisions choose no winner; fuzzy/synonym-only reuse remains review-only and never silently changes `would_mint` |
| 80 | Queue a mutating tool behind a blocked EDT, let it time out or interrupt it, then release the EDT; separately time out after the body has started | queued work is cancelled and never mutates later; started work reports honestly that effects may still complete rather than claiming cancellation |
| 81 | Run inferred QC/SPARQL over an adversarially large signature/property cross-product | expensive inference categories are omitted independently before enumeration and named in the result note; ordinary large class hierarchies still materialize within the documented estimates |
| 82 | Inspect the scheduled/release workflow artifacts from `scripts/live-integration/run.sh` | JSON records bundle activation, Java 17+, Protégé 5.6.6, token rejection/acceptance, two-window session pinning, one-step Undo, HermiT justification, and application/broker PID exit |

### 0.7.2 — evidence-backed prefix and QC hardening

| # | Step | Expect |
| --- | --- | --- |
| 83 | Connect a fresh MCP client, inspect initialize/list responses, and run the CLI with `--version` | server, bundle, CLI, and docs report `0.7.2`; exactly 85 tools and 11 prompts are advertised |
| 84 | Run asset-backed `run_project_qc` on a valid project, then violate one invariant, malform its query file, and reference a missing required asset | the pristine project runs all eight stages and passes; the violation fails; malformed and missing assets error before any stage and never become a vacuous pass |
| 85 | Bind two prefix names to one namespace, call `remove_prefix` for one name, and query using the survivor | only the requested name disappears; the same-namespace sibling and standard `rdf`/`rdfs`/`owl`/`xsd` prefixes remain usable |
| 86 | Commit one multi-operation change set and call `undo_change` once | every committed operation is reverted together; an anomalous multi-entry history surfaces `undo_log_warning` |

Any unexpected error, UI freeze, or missing committed edit is a release blocker — capture the JSON error
and the Protégé log. Steps 16–18 must never undo an unrelated edit: verified rollback now performs all
checks on an isolated snapshot and either prevents the delta or commits it once after final revalidation.
