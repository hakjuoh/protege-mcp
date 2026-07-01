# Live smoke test (Protégé + MCP)

The unit tests (`mvn test`) cover the tool cores in isolation, and `ToolPipelineTest` chains them
end-to-end **headlessly** (load → edit → validate → govern → diff → SPARQL) so a cross-tool regression
fails in CI. What they cannot cover is the live stack: the OSGi bundle loading in a real Protégé, the
HTTP/OAuth transport, the EDT marshalling, and a real reasoner. This checklist is the manual
counterpart — run it against the built `v0.3.3` jar before publishing a release.

## Setup

1. `mvn clean package` and copy `target/protege-mcp-0.3.3.jar` into
   `/Applications/Protégé.app/Contents/plugins/` (replace the older versioned jar — keep exactly one).
2. Launch Protégé on a **Java 17+** JVM (`PROTEGE_JAVA_HOME`), open the **MCP Server** view, and
   **Start** the server. Note the bound URL and bearer token.
3. Point an MCP client at it (e.g. `tools/chat-repro.sh "$(pbpaste)" "…" "http://127.0.0.1:8123/mcp"`,
   or the in-app **Ontology Assistant** tab).

## Flow (each step should return structured JSON, no error, and be visible in the GUI)

| # | Tool call | Expect |
| --- | --- | --- |
| 1 | `load_ontology` a real ontology (e.g. an IOF module) | becomes the active ontology; imports resolved |
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

Any step that errors, freezes the UI, or does not appear in Protégé's editor/undo stack is a release
blocker — capture the JSON error and the Protégé log.
