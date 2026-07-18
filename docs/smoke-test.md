---
title: Live smoke test
parent: Contributing
nav_order: 2
---

# Live smoke test (Protégé + MCP)

The unit tests (`mvn test`) cover the tool cores in isolation, and `ToolPipelineTest` chains them
end-to-end **headlessly** (load → edit → validate → govern → diff → SPARQL) so a cross-tool regression
fails in CI. What they cannot cover is the live stack: the OSGi bundle loading in a real Protégé, the
HTTP/OAuth transport, the EDT marshalling, and a real reasoner. This checklist is the manual
counterpart — run it against the built `v{{ site.version }}` jar before publishing a release.

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
| 24 | `run_qc_suite` before `run_reasoner` (fresh load) | the `reasoner` stage is `ran:false` with a "run run_reasoner" reason, **not** an error |
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
| 54 | Try an outside path with `filesystem.allow_external_paths: false`, then set it to `true` and retry | the opt-out call is refused with the documented outside-project message; the opt-in call succeeds. (Every live principal carries the full local-admin capability set, so the `filesystem:external`-denied leg is not reproducible on the real runtime — it is pinned by unit tests with a synthesized restricted principal.) |
| 55 | Set network/import modes and a host allowlist; load a denied remote import, then map the same HTTP ontology IRI to a local catalog file | denied dereference fails explicitly without a request; the local mapping succeeds offline; allowlisted root URLs do not follow redirects outside the allowlist |
| 56 | Write a matching import lock, run project QC, modify one imported file, and rerun QC/change-set preview | the first gate can pass; both later gates return `error`, `imports.lock_mismatch`, and `import_lock_verification.valid=false` |
| 57 | Configure a module with the wrong ontology IRI, then a subject-position axiom **defining** a term under another module's `owned_namespaces` (a bare foreign declaration must NOT trip it); also test an explicit co-owner | the wrong file IRI is a policy error; the foreign definition fails governance while the bare declaration passes; a declared co-owner passes |
| 58 | Load imports with a cycle and with an ontology/version/document identity conflict | governance reports `import_cycle` as warning and `import_identity_conflict` as error |
| 59 | Disable the no-policy local-admin path compatibility preference and retry a caller-selected path without a policy | the path is refused and `get_project_policy.path_mode` reports `policy_required` |

Any unexpected error, UI freeze, or missing committed edit is a release blocker — capture the JSON error
and the Protégé log. Steps 16–18 must never undo an unrelated edit: verified rollback now performs all
checks on an isolated snapshot and either prevents the delta or commits it once after final revalidation.
