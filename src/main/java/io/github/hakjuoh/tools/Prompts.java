package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

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

    public static List<SyncPromptSpecification> all() {
        List<SyncPromptSpecification> prompts = new ArrayList<>();

        prompts.add(prompt("audit_ontology",
                "Audit the active ontology for modelling-quality issues and propose fixes.",
                Collections.emptyList(),
                args -> ""
                        + "Audit the ontology currently open in Protégé and propose fixes.\n\n"
                        + "1. Call get_ontology_context to orient yourself (size, roots, reasoner state).\n"
                        + "2. Call validate_ontology to collect modelling-quality findings.\n"
                        + "3. Call run_reasoner, then get_unsatisfiable_classes, for logical problems.\n"
                        + "4. For the most important findings, call get_entity_context on the offending "
                        + "terms to understand them before suggesting changes.\n"
                        + "5. Summarise the issues by severity and propose concrete fixes. Use "
                        + "preview_changes to show the exact axioms before applying anything, and DO NOT "
                        + "modify the ontology until I approve."));

        prompts.add(prompt("explain_class",
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
                }));

        prompts.add(prompt("add_subclass_safely",
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
                            + "3. After I approve, apply it with add_subclass_of.\n"
                            + "4. Call run_reasoner and get_unsatisfiable_classes to confirm the edit did "
                            + "not make any class unsatisfiable.";
                }));

        prompts.add(prompt("find_and_fix_unsatisfiable",
                "Find unsatisfiable classes, explain why, and propose minimal fixes.",
                Collections.emptyList(),
                args -> ""
                        + "Diagnose and fix unsatisfiable classes in the active ontology.\n\n"
                        + "1. Call run_reasoner, then get_unsatisfiable_classes.\n"
                        + "2. For each unsatisfiable class C, call get_explanations (axiom_type=subclass_of, "
                        + "sub=C, super=\"owl:Nothing\") to get the minimal justifications.\n"
                        + "3. For terms in the justifications, call get_entity_context to understand them.\n"
                        + "4. Propose the smallest set of axiom removals/changes that restores "
                        + "satisfiability. Use preview_changes (op:remove) to show exactly what would be "
                        + "removed, and apply with remove_axiom only after I approve. Re-run the reasoner "
                        + "to confirm."));

        prompts.add(prompt("model_domain",
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
                            + "3. Express the additions as preview_changes operations and show me the diff "
                            + "and the new entities before applying.\n"
                            + "4. After I approve, apply with create_class / create_entity / add_axiom / "
                            + "add_subclass_of, then call validate_ontology and run_reasoner to check the "
                            + "result. Work in small, reviewable batches.";
                }));

        return prompts;
    }

    // ------------------------------------------------------------------ helpers

    private interface Template {
        String render(Map<String, Object> args);
    }

    private static SyncPromptSpecification prompt(String name, String description,
            List<PromptArgument> arguments, Template template) {
        Prompt prompt = new Prompt(name, description, arguments);
        return new SyncPromptSpecification(prompt, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String text = template.render(args);
            PromptMessage message = new PromptMessage(Role.USER, new TextContent(text));
            return new GetPromptResult(description, Collections.singletonList(message));
        });
    }

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
