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

1. `mvn clean package` and copy `target/protege-mcp-{{ site.version }}.jar` into
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
| 16 | `apply_changes` `verify=rollback` with a clean batch (e.g. a `subclass_of` between two satisfiable classes) | `verify.regression=false`, `verify.applied=true`; the edit stays and is one undo entry |
| 17 | `apply_changes` `verify=rollback` with a batch that makes a class unsatisfiable (e.g. subclass of two disjoint classes) | `verify.regression=true`, `verify.rolled_back=true`, `newly_unsatisfiable` names the class; the model is back to its prior state (nothing added) |
| 18 | Repeat #17 with `verify=report` | `regression=true`, `rolled_back=false`; the edit **stays** (undo it manually) |
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

Any step that errors, freezes the UI, or does not appear in Protégé's editor/undo stack is a release
blocker — capture the JSON error and the Protégé log. Steps 16–18 must **never** undo an unrelated edit:
if a GUI edit happens between apply and re-classification, expect `verify.concurrent_change=true` and no
rollback.
