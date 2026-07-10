package io.github.hakjuoh.protege_mcp.prompts;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.PromptArgument;

/**
 * MCP <em>prompts</em>: reusable, guided ontology workflows a user can pick in their MCP client. Each
 * prompt expands to a single user message that tells the model which protege-mcp tools to use and in
 * what (safe) order — get context first, preview destructive edits, confirm before writing, verify
 * with the reasoner. They are pure templates (no model access, no side effects); the actual work
 * happens through the tools.
 */
public final class Prompts {

    private Prompts() {
    }

    public static void register(PromptRegistry prompts) {
        prompts.prompt("audit_ontology",
                "Audit the active ontology for modelling-quality issues and propose fixes.",
                Collections.emptyList(),
                args -> ""
                        + "Audit the ontology currently open in Protégé and propose fixes.\n\n"
                        + "1. Call get_ontology_context to orient yourself (size, roots, reasoner state).\n"
                        + "2. Call validate_ontology to collect modelling-quality findings, and "
                        + "validate_governance for OWL 2 profile conformance and import-layering/"
                        + "ownership issues that validate_ontology does not check.\n"
                        + "3. Call run_reasoner for logical problems. If it reports the ontology "
                        + "INCONSISTENT, call explain_inconsistency to find the contradicting axioms; "
                        + "otherwise call get_unsatisfiable_classes.\n"
                        + "4. For the most important findings, call get_entity_context on the offending "
                        + "terms to understand them before suggesting changes.\n"
                        + "5. Summarise the issues by severity and propose concrete fixes. Use "
                        + "preview_changes to show the exact axioms before applying anything, and DO NOT "
                        + "modify the ontology until I approve. After I approve, apply the batch with "
                        + "apply_changes (the same operations array, ONE undoable transaction) with "
                        + "verify=\"rollback\" so a fix that breaks satisfiability is undone "
                        + "automatically — or verify=\"report\" when repairing an ALREADY-inconsistent "
                        + "ontology, since rollback only detects new regressions (re-run run_reasoner to "
                        + "confirm the repair instead).");

        prompts.prompt("explain_class",
                "Explain a class: its definition, neighbourhood, and what the reasoner infers about it.",
                Collections.singletonList(arg("class", "Class IRI or display name.", true)),
                args -> {
                    String cls = str(args, "class", "the class");
                    return ""
                            + "Explain the class \"" + cls + "\" in the active ontology, in plain language.\n\n"
                            + "1. Call get_entity_context with entity=\"" + cls + "\" for its labels, "
                            + "annotations, asserted parents/children/equivalents and disjoints.\n"
                            + "2. Call get_axioms_for_entity for the exact axioms (include_imports=true if "
                            + "it is an imported term).\n"
                            + "3. Call run_reasoner, then get_inferred_superclasses with entity=\"" + cls
                            + "\" to see what is inferred beyond what is asserted.\n"
                            + "4. Summarise: what the class means, where it sits in the hierarchy, its "
                            + "key restrictions, and anything surprising the reasoner derived.";
                });

        prompts.prompt("add_subclass_safely",
                "Add a subclass relationship safely: check the terms exist, preview, then apply and verify.",
                Arrays.asList(
                        arg("child", "Subclass — IRI, name, or Manchester class expression.", true),
                        arg("parent", "Superclass — IRI, name, or Manchester class expression.", true)),
                args -> {
                    String child = str(args, "child", "the child class");
                    String parent = str(args, "parent", "the parent class");
                    return ""
                            + "Assert that \"" + child + "\" is a subclass of \"" + parent + "\", safely.\n\n"
                            + "1. Confirm both terms with get_entity (or search_entities if a name is "
                            + "ambiguous) — resolve to exact IRIs and avoid creating an unintended new "
                            + "entity from a typo.\n"
                            + "2. Call preview_changes with one operation {op:add, axiom_type:subclass_of, "
                            + "sub:\"" + child + "\", super:\"" + parent + "\"} and show me the diff and any "
                            + "new entities it would introduce.\n"
                            + "3. After I approve, apply the SAME operations array with apply_changes and "
                            + "verify=\"rollback\" — one undoable transaction that re-classifies the "
                            + "reasoner and automatically undoes the edit if it makes any class newly "
                            + "unsatisfiable or the ontology inconsistent. Report the verify verdict.\n"
                            + "4. If no reasoner is selected (verify=\"rollback\" refuses before applying "
                            + "anything), call set_reasoner (list_reasoners shows the choices) and retry "
                            + "step 3. Only if no reasoner is available at all, apply with "
                            + "add_subclass_of and report the edit as UNVERIFIED — the reasoner checks "
                            + "cannot run in that state.";
                });

        prompts.prompt("find_and_fix_unsatisfiable",
                "Find unsatisfiable classes, explain why, and propose minimal fixes.",
                Collections.emptyList(),
                args -> ""
                        + "Diagnose and fix unsatisfiable classes in the active ontology.\n\n"
                        + "1. Call run_reasoner, then get_unsatisfiable_classes. If the ontology is wholly "
                        + "INCONSISTENT the per-class tools refuse to run — call explain_inconsistency "
                        + "instead for a set of contradicting axioms (its 'minimal' flag reports whether "
                        + "the set was fully minimised; undo_change with peek=true shows whether the LAST "
                        + "edit is the likely culprit).\n"
                        + "2. For each unsatisfiable class C, call get_explanations (axiom_type=subclass_of, "
                        + "sub=C, super=\"owl:Nothing\") to get the minimal justifications.\n"
                        + "3. For terms in the justifications, call get_entity_context to understand them.\n"
                        + "4. Propose the smallest set of axiom removals/changes that restores "
                        + "satisfiability. Use preview_changes (op:remove) to show exactly what would be "
                        + "removed, and only after I approve apply the batch with apply_changes (the same "
                        + "operations array — ONE undoable transaction) with verify=\"report\", which "
                        + "re-classifies and flags anything the fix makes newly unsatisfiable or "
                        + "inconsistent. Confirm with get_unsatisfiable_classes; a single undo_change "
                        + "reverts the whole batch if not.");

        prompts.prompt("author_sparql_query",
                "Author a SPARQL query that answers a question: discover the vocabulary, draft, validate, "
                        + "then run it.",
                Collections.singletonList(
                        arg("question", "The question to answer in plain words.", true)),
                args -> {
                    String question = str(args, "question", "the question");
                    return ""
                            + "Write and run a SPARQL query over the ontology open in Protégé to answer: "
                            + question + "\n\n"
                            + "1. Call sparql_schema (pass keyword= a key term from the question to focus "
                            + "it) to get the prefixes and the exact classes, properties (with their "
                            + "domains/ranges) and individuals you can use — note the CURIEs and the "
                            + "example queries.\n"
                            + "2. Draft a SELECT/ASK/CONSTRUCT/DESCRIBE query using ONLY those CURIEs/IRIs "
                            + "(do not invent term names). The ontology's prefixes are auto-prepended, so "
                            + "you can use the CURIEs without PREFIX lines.\n"
                            + "3. Call sparql_validate on the draft. Fix anything in unknown_terms (a typo "
                            + "or wrong vocabulary) and any parse_error until executable=true; set "
                            + "dry_run=true to also run it with a small LIMIT and sanity-check sample "
                            + "results.\n"
                            + "4. Run it with sparql_query. If you need triples the reasoner derives "
                            + "(e.g. inferred types or subclasses), set include_inferred=true (run_reasoner "
                            + "first). Refine the pattern and repeat if the results are empty or too broad.\n"
                            + "5. Summarise the answer, and show the final query you used.";
                });

        prompts.prompt("model_domain",
                "Model a described domain incrementally: propose terms, preview, apply with confirmation.",
                Collections.singletonList(arg("description", "What to model (the domain in plain words).", true)),
                args -> {
                    String desc = str(args, "description", "the described domain");
                    return ""
                            + "Help me model this in the active ontology: " + desc + "\n\n"
                            + "1. Call get_ontology_context to see what already exists (reuse terms, match "
                            + "the naming and IRI style, don't duplicate).\n"
                            + "2. Propose a small set of classes, properties (with domains/ranges) and any "
                            + "individuals. Explain the modelling choices.\n"
                            + "3. Show me the additions BEFORE applying: the new classes as a create_terms "
                            + "batch (name/label/definition/parents per item) and the new properties as a "
                            + "create_properties batch (domain/range/characteristics per item) — each "
                            + "batch is atomic and ONE undoable transaction — plus preview_changes for any "
                            + "remaining axioms (individuals, extra restrictions) as an operations array.\n"
                            + "4. After I approve, apply the batches with strict=true (refuses typo-minted "
                            + "entities) and verify=\"report\", and the remaining operations array with "
                            + "apply_changes. Then call run_qc_suite for a one-call quality gate "
                            + "(reasoner + profile + structural). Work in small, reviewable batches.";
                });

        prompts.prompt("author_competency_question",
                "Turn a plain-language requirement into a stored, executable competency question: "
                        + "discover vocabulary, draft, validate, store, and run it.",
                Arrays.asList(
                        arg("question", "The requirement/competency question in plain words.", true),
                        arg("expected", "Pass condition: nonEmpty (default) | empty | 'count OP N'.", false)),
                args -> {
                    String question = str(args, "question", "the requirement");
                    String expected = str(args, "expected", "");
                    return ""
                            + "Turn this requirement into an executable competency question for the active "
                            + "ontology: " + question + "\n\n"
                            + "1. Call list_competency_questions to see the existing CQ ids and which "
                            + "storage convention the project already uses — follow it (note: "
                            + "ontology-annotations is the fallback when the ontology is unsaved; "
                            + "robot-sparql-dir writes *.rq files for ROBOT/CI interop).\n"
                            + "2. Call sparql_schema (keyword= a key term from the question) to get the "
                            + "exact classes/properties and CURIEs. Use ONLY those — do not invent term "
                            + "names.\n"
                            + "3. Draft a SELECT or ASK query that answers the question, and choose the "
                            + "pass condition: "
                            + (expected.isEmpty()
                                    ? "nonEmpty | empty | 'count OP N' — pick the one under which a "
                                            + "silent regression would FAIL the suite"
                                    : expected)
                            + ".\n"
                            + "4. Call sparql_validate on the draft; fix anything in unknown_terms and any "
                            + "parse_error until executable=true.\n"
                            + "5. Show me the query, the expected condition and the target store. After I "
                            + "approve, call add_competency_question with the query, text=\"" + question
                            + "\" and the expected condition (omit 'convention' to follow the existing "
                            + "store; note include_inferred DEFAULTS TO TRUE — set include_inferred=false "
                            + "unless the check should also hold over reasoner-derived triples).\n"
                            + "6. Call run_competency_questions with ids=[the new id] to confirm it passes "
                            + "NOW (an include_inferred CQ needs a classified reasoner — run_reasoner "
                            + "first). If it fails, either refine the query or report the real ontology "
                            + "gap it exposed.";
                });

        prompts.prompt("author_swrl_rule",
                "Author a SWRL rule from a plain-language description, checking reasoner compatibility "
                        + "(ELK ignores rules; built-ins can fail classification) before and after applying.",
                Collections.singletonList(
                        arg("rule", "The rule to express, in plain words (if X then Y).", true)),
                args -> {
                    String rule = str(args, "rule", "the described rule");
                    return ""
                            + "Author this as a SWRL rule in the active ontology: " + rule + "\n\n"
                            + "1. Resolve every class, property and individual the rule mentions with "
                            + "search_entities / get_entity and collect their exact IRIs — use the IRIs as "
                            + "the atoms' predicate values so a typo cannot mint a new entity.\n"
                            + "2. Call list_rules to match the existing rules' style and avoid duplicating "
                            + "one.\n"
                            + "3. Build the structured body/head atoms for add_rule (atom types: class "
                            + "{predicate, arg1}, object_property {predicate, arg1, arg2}, data_property "
                            + "{predicate, arg1, arg2|value}, same_as/different_from {arg1, arg2}, builtin "
                            + "{builtin, args[]}; an argument starting with '?' is a variable). Show me "
                            + "the atoms plus a readable body -> head rendering, and include an rdfs:label "
                            + "via 'annotations'.\n"
                            + "4. Check reasoner compatibility BEFORE applying (list_reasoners): ELK "
                            + "silently IGNORES SWRL rules (run_reasoner will attach a warning), and "
                            + "swrlb: built-in atoms make some DL reasoners (e.g. HermiT) FAIL "
                            + "classification — run_reasoner surfaces that as an error, not a no-op. If "
                            + "the current reasoner cannot honour the rule, propose set_reasoner to one "
                            + "that can, and get my approval.\n"
                            + "5. After I approve the rule and the reasoner choice, call add_rule with the "
                            + "body/head (and annotations).\n"
                            + "6. Verify the rule fires: run_reasoner (heed any warning/error), then "
                            + "confirm one expected inference with get_inferred_superclasses or "
                            + "sparql_query with include_inferred=true. If nothing is inferred, explain "
                            + "why (rules ignored by the reasoner / no individuals match) instead of "
                            + "reporting success.";
                });

        prompts.prompt("refactor_entity_safely",
                "Rename, deprecate, delete or move a term the safe way: blast-radius first, correct tool "
                        + "choice, preview, confirm, verify.",
                Arrays.asList(
                        arg("entity", "The term to refactor: IRI or display name.", true),
                        arg("goal", "What to achieve: rename | deprecate | delete | move (plain words are fine).", true)),
                args -> {
                    String entity = str(args, "entity", "the term");
                    String goal = str(args, "goal", "the intended change");
                    return ""
                            + "Refactor \"" + entity + "\" in the active ontology. Intended goal: " + goal
                            + ". Choose the SAFE operation and preview before touching anything.\n\n"
                            + "1. Call get_entity_context and get_axioms_for_entity for \"" + entity + "\" "
                            + "to establish the blast radius (how much references it).\n"
                            + "2. Pick the right tool and confirm the semantics with me FIRST:\n"
                            + "   - fix/change an IRI -> rename_entity (rewrites every reference; if "
                            + "new_iri already exists in the signature the two entities MERGE — sometimes "
                            + "intended, often not),\n"
                            + "   - retire a published term -> deprecate_entity (keeps the term and its "
                            + "axioms, adds owl:deprecated plus an optional replaced_by pointer; usages "
                            + "are NOT rewritten),\n"
                            + "   - remove a mistake entirely -> delete_entity (removes its declaration "
                            + "and EVERY axiom that references it),\n"
                            + "   - reparent a class -> move_class (replaces its asserted named parents "
                            + "unless keep_other_parents=true; the subtree follows).\n"
                            + "3. Preview: rename_entity and delete_entity take preview=true — show me the "
                            + "change count, the sample axioms, and (rename) the "
                            + "new_iri_already_in_signature flag. move_class and deprecate_entity have no "
                            + "preview, so show the exact arguments you intend to pass instead.\n"
                            + "4. Apply ONLY after I approve. Each of these is a single undo transaction — "
                            + "undo_change with peek=true shows what an undo would revert.\n"
                            + "5. Verify: run_reasoner and get_unsatisfiable_classes; for a broad change "
                            + "also validate_ontology. Report exactly what changed.";
                });

        prompts.prompt("bootstrap_ontology",
                "Start a new ontology module correctly: create it bound to a file, set prefixes and "
                        + "metadata, add resolved imports, save, and write the catalog.",
                Arrays.asList(
                        arg("ontology_iri", "IRI of the new ontology.", true),
                        arg("path", "File path to bind the ontology to (optional but recommended).", false)),
                args -> {
                    String iri = str(args, "ontology_iri", "the new ontology IRI");
                    String path = str(args, "path", "");
                    return ""
                            + "Set up a new ontology module " + iri + ", ready for authoring.\n\n"
                            + "1. Confirm the plan with me first: ontology IRI (+ version IRI?), the term "
                            + "namespace, the file location"
                            + (path.isEmpty() ? "" : " (" + path + ")")
                            + ", and which upstream ontologies to import.\n"
                            + "2. Call create_ontology with ontology_iri=\"" + iri + "\""
                            + (path.isEmpty()
                                    ? " (pass 'path' too if a file location is agreed — binding a path now"
                                    : " and path=\"" + path + "\" (binding the path now")
                            + " means an argument-less save_ontology works and write_catalog has a "
                            + "folder). It becomes the active edit target; note it is NOT undoable.\n"
                            + "3. Call set_prefix for the term namespace (and each upstream namespace) so "
                            + "CURIEs render/parse and the saved file is readable.\n"
                            + "4. Add metadata with add_ontology_annotation (e.g. dcterms:title, "
                            + "rdfs:comment, owl:versionInfo).\n"
                            + "5. For each upstream ontology call add_import with its IRI, passing "
                            + "'document' (a path/URL) so the import resolves NOW — then check the "
                            + "result's 'resolved' flag: an unresolved import's terms stay INVISIBLE to "
                            + "lookups and reasoning until its document is loaded.\n"
                            + "6. Call save_ontology (pass 'path' here if the ontology is still untitled "
                            + "— an argument-less save has nowhere to write), then write_catalog so the "
                            + "local imports re-open offline in Protégé.\n"
                            + "7. Verify with get_ontology_context (imports resolved, prefixes right), and "
                            + "once the first terms exist run validate_governance with "
                            + "required_namespaces=[the term namespace] to catch IRIs minted outside it.";
                });

        prompts.prompt("release_readiness_check",
                "Run the full quality gate (reasoner, profile, structural checks, competency questions, "
                        + "governance policy) and, on approval, save the release artifacts.",
                Arrays.asList(
                        arg("profile", "OWL 2 profile the project must stay in: DL (default), EL, QL or RL.", false),
                        arg("namespace", "The project's required term namespace, for the governance IRI policy.", false)),
                args -> {
                    String profile = str(args, "profile", "DL");
                    String ns = str(args, "namespace", "");
                    return ""
                            + "Run a release-readiness check on the active ontology and tell me whether it "
                            + "is safe to ship.\n\n"
                            + "1. Call get_ontology_context, and list_ontologies to see which loaded "
                            + "ontologies have unsaved changes.\n"
                            + "2. Call run_reasoner. Treat an error (failed classification) or warning "
                            + "(e.g. ELK ignoring SWRL rules) as a release finding, not a side note.\n"
                            + "3. Call run_qc_suite with stages=[\"reasoner\",\"profile\",\"structural\","
                            + "\"cqs\"] and owl_profile=\"" + profile + "\". A stage skipped for missing "
                            + "backing data (e.g. no competency questions stored) is fine — report the "
                            + "skip reason. If the project has a SHACL shapes file, add the \"shacl\" "
                            + "stage with shacl_shapes_path.\n"
                            + "4. Call validate_governance with owl_profile=\"" + profile + "\", "
                            + "required_annotations=[\"label\",\"definition\"]"
                            + (ns.isEmpty()
                                    ? " and, if the project has a required term namespace, "
                                            + "required_namespaces=[that namespace]"
                                    : " and required_namespaces=[\"" + ns + "\"]")
                            + " — report policy violations per check (profile leaks, wrong-namespace "
                            + "IRIs, missing annotations, import-layering).\n"
                            + "5. If the active ontology has a saved document, call diff_ontologies with "
                            + "right_document= that file to summarise exactly what changed since the last "
                            + "save.\n"
                            + "6. Summarise every gate and finding by severity and give a clear ship / "
                            + "do-not-ship verdict. DO NOT save anything until I approve; after approval, "
                            + "call save_ontology (all=true if several ontologies are dirty) and — when "
                            + "there are locally-loaded imports — write_catalog so the module re-opens "
                            + "offline.";
                });
    }

    // ------------------------------------------------------------------ helpers

    private static PromptArgument arg(String name, String description, boolean required) {
        return new PromptArgument(name, description, required);
    }

    private static String str(Map<String, Object> args, String key, String fallback) {
        if (args == null) {
            return fallback;
        }
        Object v = args.get(key);
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }
}
