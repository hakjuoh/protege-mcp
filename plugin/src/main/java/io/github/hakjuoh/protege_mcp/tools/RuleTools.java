package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.SWRLArgument;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLBuiltInAtom;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLDataPropertyAtom;
import org.semanticweb.owlapi.model.SWRLDifferentIndividualsAtom;
import org.semanticweb.owlapi.model.SWRLIArgument;
import org.semanticweb.owlapi.model.SWRLIndividualArgument;
import org.semanticweb.owlapi.model.SWRLLiteralArgument;
import org.semanticweb.owlapi.model.SWRLObjectPropertyAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLSameIndividualAtom;
import org.semanticweb.owlapi.model.SWRLVariable;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Structured read/add/remove for SWRL rules ({@link SWRLRule} axioms). OWL API 4 can load and render
 * SWRL rules but has no standalone rule-text parser, and the structured add_axiom tool has no rule
 * type — so these tools build rules from a structured body/head atom representation rather than from
 * free text. The structured form is the faithful primitive for rule reconstruction: a rule variable
 * is identified by its IRI (e.g. {@code ex:process1}), which the conventional {@code ?x} text
 * syntax cannot preserve, and rule-level annotations (rdfs:label, dcterms:description, …) are carried
 * via the same {@code annotations} operand as the other write tools.
 *
 * <p>An argument string starting with {@code ?} is a variable: {@code ?name} resolves to
 * {@code variable_namespace + name} (default {@value #DEFAULT_VARIABLE_NS}), and {@code ?<absoluteIRI>}
 * uses that exact IRI. {@code list_rules} emits variable arguments as {@code ?<absoluteIRI>}, so a
 * listed rule round-trips through {@code add_rule} with no namespace hint. Any other i-argument is an
 * individual; a d-argument is either a variable or a literal ({@code value}[+{@code lang}|
 * {@code datatype}]).
 */
public final class RuleTools {

    private RuleTools() {
    }

    /**
     * Default namespace for a {@code ?name} rule variable when {@code variable_namespace} is omitted.
     * Must be a scheme-valid IRI: the earlier {@code urn:swrl#} produced variables like
     * {@code urn:swrl#p} that violate the URN syntax ({@code urn:swrl} has no {@code NID:NSS} colon),
     * so the RDF/Turtle writer logged a "Bad IRI … SCHEME_PATTERN_MATCH_FAILED" warning on every
     * serialization (including the SPARQL/SHACL snapshot path). {@code urn:swrl:var#} is a valid URN.
     */
    static final String DEFAULT_VARIABLE_NS = "urn:swrl:var#";

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("list_rules",
                "List the SWRL rules (swrl:Imp axioms) in the active ontology as structured "
                        + "body/head atoms plus any rule annotations and a text rendering. Variable "
                        + "arguments are emitted as ?<absolute IRI> so a listed rule round-trips through "
                        + "add_rule unchanged. Set include_imports=true to include rules from the imports "
                        + "closure. Read-only.",
                Tools.schema()
                        .bool("include_imports", "Include rules from imported ontologies too (default false).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    return ctx.access().compute(mm -> listRules(mm, includeImports));
                }));

        tools.tool("add_rule",
                "Add a SWRL rule (swrl:Imp) to the active ontology from structured body and head atoms. "
                        + "Each atom is {type, ...}: type=class {predicate, arg1}; object_property "
                        + "{predicate, arg1, arg2}; data_property {predicate, arg1, arg2|value[+lang|"
                        + "datatype]}; same_as/different_from {arg1, arg2}; builtin {builtin, args[]}. An "
                        + "argument starting with '?' is a variable (?name → variable_namespace+name, "
                        + "?<IRI> → that IRI); otherwise an i-argument is an individual and a d-argument "
                        + "is a literal. Optional 'annotations' attach rule-level rdfs:label/comment/etc. "
                        + "The rule is implication body → head (an empty body is allowed; head must be "
                        + "non-empty).",
                ruleSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> body = Tools.objList(a, "body");
                    List<Map<String, Object>> head = Tools.objList(a, "head");
                    if (head.isEmpty()) {
                        return Tools.error("A SWRL rule needs at least one head atom (body may be empty).");
                    }
                    String varNs = varNamespace(a);
                    return WriteTools.write(ctx, "add SWRL rule", mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLDataFactory df = mm.getOWLDataFactory();
                        Set<SWRLAtom> bodyAtoms = atoms(mm, df, body, varNs);
                        Set<SWRLAtom> headAtoms = atoms(mm, df, head, varNs);
                        Set<OWLAnnotation> annotations = Tools.annotationSet(mm, a, "annotations");
                        SWRLRule rule = annotations.isEmpty()
                                ? df.getSWRLRule(bodyAtoms, headAtoms)
                                : df.getSWRLRule(bodyAtoms, headAtoms, annotations);
                        boolean already = ont.containsAxiom(rule);
                        mm.applyChange(new org.semanticweb.owlapi.model.AddAxiom(ont, rule));
                        return Tools.json()
                                .put("added", !already)
                                .put("already_present", already)
                                .put("rule", ruleJson(mm, rule, -1))
                                .result();
                    });
                }));

        tools.tool("remove_rule",
                "Remove a SWRL rule from the active ontology. Identify it by 'index' (into the same "
                        + "rendering-sorted order list_rules returns) and/or 'label' (its rdfs:label), or "
                        + "by passing the same structured 'body'/'head'/'annotations' as add_rule to "
                        + "remove that exact rule. An index/label that matches no rule is reported, not an "
                        + "error.",
                removeRuleSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> body = Tools.objList(a, "body");
                    List<Map<String, Object>> head = Tools.objList(a, "head");
                    Integer index = a.containsKey("index") ? Tools.optInt(a, "index", -1) : null;
                    String label = Tools.optString(a, "label");
                    if (body.isEmpty() && head.isEmpty() && index == null && label == null) {
                        return Tools.error("Identify the rule to remove: pass 'index' and/or 'label', or "
                                + "the structured 'body'/'head' of the rule.");
                    }
                    String varNs = varNamespace(a);
                    return WriteTools.write(ctx, "remove SWRL rule", mm -> {
                        OWLOntology ont = mm.getActiveOntology();
                        OWLDataFactory df = mm.getOWLDataFactory();
                        if (!head.isEmpty()) {
                            Set<SWRLAtom> bodyAtoms = atoms(mm, df, body, varNs);
                            Set<SWRLAtom> headAtoms = atoms(mm, df, head, varNs);
                            Set<OWLAnnotation> annotations = Tools.annotationSet(mm, a, "annotations");
                            SWRLRule rule = annotations.isEmpty()
                                    ? df.getSWRLRule(bodyAtoms, headAtoms)
                                    : df.getSWRLRule(bodyAtoms, headAtoms, annotations);
                            if (!ont.containsAxiom(rule)) {
                                return Tools.error("No such rule in the active ontology: "
                                        + renderRule(mm, rule));
                            }
                            mm.applyChange(new RemoveAxiom(ont, rule));
                            return Tools.json().put("removed", 1)
                                    .put("rule", ruleJson(mm, rule, -1)).result();
                        }
                        List<SWRLRule> sorted = sortedRules(mm, ont);
                        List<SWRLRule> targets = new ArrayList<>();
                        if (index != null) {
                            if (index < 0 || index >= sorted.size()) {
                                return Tools.error("index " + index + " is out of range (0.."
                                        + (sorted.size() - 1) + "); see list_rules.");
                            }
                            targets.add(sorted.get(index));
                        }
                        if (label != null) {
                            for (SWRLRule r : sorted) {
                                if (hasLabel(r, label) && !targets.contains(r)) {
                                    targets.add(r);
                                }
                            }
                            if (targets.isEmpty()) {
                                return Tools.error("No rule has rdfs:label '" + label + "'; see list_rules.");
                            }
                        }
                        List<Map<String, Object>> removed = new ArrayList<>();
                        for (SWRLRule r : targets) {
                            mm.applyChange(new RemoveAxiom(ont, r));
                            removed.add(ruleJson(mm, r, -1));
                        }
                        return Tools.json().put("removed", removed.size())
                                .put("rules", removed).result();
                    });
                }));
    }

    // ------------------------------------------------------------------ list

    private static CallToolResult listRules(OWLModelManager mm, boolean includeImports) {
        OWLOntology ont = mm.getActiveOntology();
        Set<SWRLRule> rules = new LinkedHashSet<>(ont.getAxioms(AxiomType.SWRL_RULE));
        if (includeImports) {
            for (OWLOntology o : ont.getImportsClosure()) {
                rules.addAll(o.getAxioms(AxiomType.SWRL_RULE));
            }
        }
        List<SWRLRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(r -> renderRule(mm, r)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            items.add(ruleJson(mm, sorted.get(i), i));
        }
        return Tools.json()
                .put("count", sorted.size())
                .put("rules", items)
                .result();
    }

    /**
     * Rules of an ontology in the SAME deterministic order {@code list_rules} returns — sorted by the
     * rendered string, so an {@code index} a caller saw in list_rules (without include_imports) selects
     * the same rule here. (Previously this used {@code Object::toString} while list_rules used the
     * rendering, so a by-index remove could delete a different rule.)
     */
    private static List<SWRLRule> sortedRules(OWLModelManager mm, OWLOntology ont) {
        List<SWRLRule> sorted = new ArrayList<>(ont.getAxioms(AxiomType.SWRL_RULE));
        sorted.sort(Comparator.comparing(r -> renderRule(mm, r)));
        return sorted;
    }

    // ------------------------------------------------------------------ build atoms (structured → SWRL)

    private static Set<SWRLAtom> atoms(OWLModelManager mm, OWLDataFactory df,
            List<Map<String, Object>> specs, String varNs) {
        Set<SWRLAtom> out = new LinkedHashSet<>();
        for (Map<String, Object> spec : specs) {
            out.add(atom(mm, df, spec, varNs));
        }
        return out;
    }

    private static SWRLAtom atom(OWLModelManager mm, OWLDataFactory df, Map<String, Object> spec,
            String varNs) {
        String type = Tools.reqString(spec, "type").toLowerCase().replace('-', '_');
        switch (type) {
            case "class":
                return df.getSWRLClassAtom(
                        Tools.resolveClassExpression(mm, predicateRef(spec)),
                        iArg(mm, df, Tools.reqString(spec, "arg1"), varNs));
            case "object_property":
                OWLObjectPropertyExpression op =
                        Tools.resolveObjectProperty(mm, predicateRef(spec));
                return df.getSWRLObjectPropertyAtom(op,
                        iArg(mm, df, Tools.reqString(spec, "arg1"), varNs),
                        iArg(mm, df, Tools.reqString(spec, "arg2"), varNs));
            case "data_property":
                return df.getSWRLDataPropertyAtom(
                        Tools.resolveDataProperty(mm, predicateRef(spec)),
                        iArg(mm, df, Tools.reqString(spec, "arg1"), varNs),
                        dArg(mm, df, spec, varNs));
            case "same_as":
            case "same_individual":
                return df.getSWRLSameIndividualAtom(
                        iArg(mm, df, Tools.reqString(spec, "arg1"), varNs),
                        iArg(mm, df, Tools.reqString(spec, "arg2"), varNs));
            case "different_from":
            case "different_individuals":
                return df.getSWRLDifferentIndividualsAtom(
                        iArg(mm, df, Tools.reqString(spec, "arg1"), varNs),
                        iArg(mm, df, Tools.reqString(spec, "arg2"), varNs));
            case "builtin":
                IRI builtin = Tools.iriRef(mm, Tools.reqString(spec, "builtin"));
                List<SWRLDArgument> args = new ArrayList<>();
                for (Object raw : rawList(spec, "args")) {
                    args.add(builtinArg(mm, df, raw, varNs));
                }
                if (args.isEmpty()) {
                    throw new ToolArgException("builtin atom needs a non-empty 'args' list.");
                }
                return df.getSWRLBuiltInAtom(builtin, args);
            default:
                throw new ToolArgException("Unknown atom type '" + type + "'. Use class, "
                        + "object_property, data_property, same_as, different_from or builtin.");
        }
    }

    /**
     * The predicate reference for a class/object/data atom: {@code predicate_iri} (preferred, so
     * list_rules output replays verbatim across ontologies) else the display-name {@code predicate}.
     */
    static String predicateRef(Map<String, Object> spec) {
        String iri = Tools.optString(spec, "predicate_iri");
        return iri != null ? iri : Tools.reqString(spec, "predicate");
    }

    /** An i-argument (variable or individual). Variables: {@code ?name}/{@code ?<IRI>}. */
    private static SWRLIArgument iArg(OWLModelManager mm, OWLDataFactory df, String arg, String varNs) {
        IRI varIri = variableIri(arg, varNs);
        if (varIri != null) {
            return df.getSWRLVariable(varIri);
        }
        return df.getSWRLIndividualArgument(Tools.resolveIndividual(mm, arg));
    }

    /** A d-argument from a data_property atom spec: {@code arg2} variable, else {@code value} literal. */
    private static SWRLDArgument dArg(OWLModelManager mm, OWLDataFactory df, Map<String, Object> spec,
            String varNs) {
        String arg2 = Tools.optString(spec, "arg2");
        if (arg2 != null) {
            return dArgValue(mm, df, arg2, varNs);
        }
        return df.getSWRLLiteralArgument(Axioms.literal(mm, spec));
    }

    /** A d-argument from a bare reference: a {@code ?}-variable, else a plain (xsd:string) literal. */
    private static SWRLDArgument dArgValue(OWLModelManager mm, OWLDataFactory df, String ref,
            String varNs) {
        IRI varIri = variableIri(ref, varNs);
        if (varIri != null) {
            return df.getSWRLVariable(varIri);
        }
        return df.getSWRLLiteralArgument(df.getOWLLiteral(ref));
    }

    /**
     * A builtin d-argument from a raw {@code args} element: a {@code {value, datatype|lang}} object →
     * a typed/lang literal (preserves typing, mirroring what list_rules emits); otherwise a string →
     * a {@code ?}-variable or a plain literal.
     */
    @SuppressWarnings("unchecked")
    private static SWRLDArgument builtinArg(OWLModelManager mm, OWLDataFactory df, Object raw,
            String varNs) {
        if (raw instanceof Map) {
            return df.getSWRLLiteralArgument(Axioms.literal(mm, (Map<String, Object>) raw));
        }
        return dArgValue(mm, df, String.valueOf(raw), varNs);
    }

    /** The raw (un-coerced) list under {@code key} — builtin args may mix strings and literal objects. */
    private static List<Object> rawList(Map<String, Object> spec, String key) {
        Object v = spec.get(key);
        List<Object> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null) {
                    out.add(o);
                }
            }
        } else if (v != null) {
            out.add(v);
        }
        return out;
    }

    /**
     * The variable IRI for a {@code ?}-prefixed argument, or {@code null} when {@code arg} is not a
     * variable. {@code ?<absoluteIRI>} uses that IRI exactly; {@code ?name} expands against
     * {@code varNs}. Package-visible for unit testing of the fidelity-critical variable handling.
     */
    static IRI variableIri(String arg, String varNs) {
        if (arg == null || arg.isEmpty() || arg.charAt(0) != '?') {
            return null;
        }
        String name = arg.substring(1).trim();
        if (name.isEmpty()) {
            throw new ToolArgException("Empty variable name in '" + arg + "'.");
        }
        IRI asIri = Tools.asIri(name);
        if (asIri != null) {
            return asIri;
        }
        String ns = varNs == null ? DEFAULT_VARIABLE_NS : varNs;
        return IRI.create(ns + name);
    }

    private static String varNamespace(Map<String, Object> a) {
        String ns = Tools.optString(a, "variable_namespace");
        return ns == null ? DEFAULT_VARIABLE_NS : ns;
    }

    // ------------------------------------------------------------------ render rules (SWRL → structured)

    static Map<String, Object> ruleJson(OWLModelManager mm, SWRLRule rule, int index) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (index >= 0) {
            m.put("index", index);
        }
        m.put("body", atomsJson(mm, rule.getBody()));
        m.put("head", atomsJson(mm, rule.getHead()));
        List<String> vars = new ArrayList<>();
        for (SWRLVariable v : rule.getVariables()) {
            vars.add(v.getIRI().toString());
        }
        m.put("variables", vars);
        List<Map<String, Object>> anns = new ArrayList<>();
        for (OWLAnnotation ann : rule.getAnnotations()) {
            anns.add(Tools.annotationJson(mm, ann));
        }
        if (!anns.isEmpty()) {
            m.put("annotations", anns);
        }
        m.put("rendering", renderRule(mm, rule));
        return m;
    }

    private static List<Map<String, Object>> atomsJson(OWLModelManager mm, Set<SWRLAtom> atoms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SWRLAtom atom : atoms) {
            out.add(atomJson(mm, atom));
        }
        return out;
    }

    private static Map<String, Object> atomJson(OWLModelManager mm, SWRLAtom atom) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (atom instanceof SWRLClassAtom) {
            SWRLClassAtom a = (SWRLClassAtom) atom;
            m.put("type", "class");
            m.put("predicate", Tools.renderObject(mm, a.getPredicate()));
            if (!a.getPredicate().isAnonymous()) {
                m.put("predicate_iri", a.getPredicate().asOWLClass().getIRI().toString());
            }
            m.put("arg1", argString(a.getArgument()));
        } else if (atom instanceof SWRLObjectPropertyAtom) {
            SWRLObjectPropertyAtom a = (SWRLObjectPropertyAtom) atom;
            m.put("type", "object_property");
            m.put("predicate", Tools.renderObject(mm, a.getPredicate()));
            if (!a.getPredicate().isAnonymous()) {
                m.put("predicate_iri", a.getPredicate().asOWLObjectProperty().getIRI().toString());
            }
            m.put("arg1", argString(a.getFirstArgument()));
            m.put("arg2", argString(a.getSecondArgument()));
        } else if (atom instanceof SWRLDataPropertyAtom) {
            SWRLDataPropertyAtom a = (SWRLDataPropertyAtom) atom;
            m.put("type", "data_property");
            m.put("predicate", Tools.renderObject(mm, a.getPredicate()));
            if (!a.getPredicate().isAnonymous()) {
                m.put("predicate_iri", a.getPredicate().asOWLDataProperty().getIRI().toString());
            }
            m.put("arg1", argString(a.getFirstArgument()));
            putDataArg(m, a.getSecondArgument());
        } else if (atom instanceof SWRLSameIndividualAtom) {
            SWRLSameIndividualAtom a = (SWRLSameIndividualAtom) atom;
            m.put("type", "same_as");
            m.put("arg1", argString(a.getFirstArgument()));
            m.put("arg2", argString(a.getSecondArgument()));
        } else if (atom instanceof SWRLDifferentIndividualsAtom) {
            SWRLDifferentIndividualsAtom a = (SWRLDifferentIndividualsAtom) atom;
            m.put("type", "different_from");
            m.put("arg1", argString(a.getFirstArgument()));
            m.put("arg2", argString(a.getSecondArgument()));
        } else if (atom instanceof SWRLBuiltInAtom) {
            SWRLBuiltInAtom a = (SWRLBuiltInAtom) atom;
            m.put("type", "builtin");
            m.put("builtin", a.getPredicate().toString());
            List<Object> args = new ArrayList<>();
            for (SWRLDArgument d : a.getArguments()) {
                args.add(dArgJson(d));
            }
            m.put("args", args);
        } else {
            m.put("type", "unknown");
            m.put("rendering", atom.toString());
        }
        return m;
    }

    /** A single SWRL argument as a string: a variable as {@code ?<IRI>}, an individual IRI, or a literal. */
    private static String argString(SWRLArgument arg) {
        if (arg instanceof SWRLVariable) {
            return "?" + ((SWRLVariable) arg).getIRI().toString();
        }
        if (arg instanceof SWRLIndividualArgument) {
            return ((SWRLIndividualArgument) arg).getIndividual().toStringID();
        }
        if (arg instanceof SWRLLiteralArgument) {
            OWLLiteral lit = ((SWRLLiteralArgument) arg).getLiteral();
            return lit.getLiteral();
        }
        return arg.toString();
    }

    /**
     * Emit a data-property atom's second argument so it round-trips: a {@code ?}variable as
     * {@code arg2}; a literal as {@code value} plus {@code lang}/{@code datatype} when non-plain (so a
     * typed/lang SWRL literal is not flattened to a plain string). add_rule's {@code dArg} reads either
     * shape back.
     */
    private static void putDataArg(Map<String, Object> m, SWRLDArgument arg) {
        if (arg instanceof SWRLLiteralArgument) {
            OWLLiteral lit = ((SWRLLiteralArgument) arg).getLiteral();
            m.put("value", lit.getLiteral());
            if (lit.hasLang()) {
                m.put("lang", lit.getLang());
            } else if (!lit.getDatatype().isString()) {
                m.put("datatype", lit.getDatatype().getIRI().toString());
            }
        } else {
            m.put("arg2", argString(arg));
        }
    }

    /**
     * A builtin d-argument as JSON: a {@code ?}variable or a plain (xsd:string) literal as a String, a
     * typed/lang literal as {@code {value, datatype|lang}} so it round-trips through add_rule's builtin
     * arg parser ({@link #builtinArg}).
     */
    static Object dArgJson(SWRLDArgument arg) {
        if (arg instanceof SWRLLiteralArgument) {
            OWLLiteral lit = ((SWRLLiteralArgument) arg).getLiteral();
            if (lit.hasLang()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("value", lit.getLiteral());
                o.put("lang", lit.getLang());
                return o;
            }
            if (!lit.getDatatype().isString()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("value", lit.getLiteral());
                o.put("datatype", lit.getDatatype().getIRI().toString());
                return o;
            }
            String lexical = lit.getLiteral();
            if (lexical.startsWith("?")) {
                // A bare string is re-read as a variable when it starts with '?' (see dArgValue), so a
                // plain literal whose value starts with '?' must be wrapped {value: ...} to stay a literal.
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("value", lexical);
                return o;
            }
            return lexical;
        }
        return argString(arg);
    }

    /** The standard SWRL built-ins namespace, rendered as the {@code swrlb:} prefix. */
    private static final String SWRLB_NS = "http://www.w3.org/2003/11/swrlb#";

    /**
     * Render a rule as {@code body -> head} with each atom {@code predicate(args)}. Built from the
     * structured atoms rather than Protégé's axiom renderer because the latter runs a built-in atom's
     * predicate through the entity-name quoting path, mangling {@code swrlb:greaterThan} into
     * {@code '\'<swrlb:greaterThan>\''}. Class/property predicates still use Protégé's rendering (so a
     * multi-word label stays quoted, e.g. {@code 'is party to'}); a built-in predicate renders as a
     * clean {@code swrlb:}CURIE (or its fragment), and variables as {@code ?}shortName.
     */
    static String renderRule(OWLModelManager mm, SWRLRule rule) {
        return renderAtoms(mm, rule.getBody()) + " -> " + renderAtoms(mm, rule.getHead());
    }

    private static String renderAtoms(OWLModelManager mm, Set<SWRLAtom> atoms) {
        StringBuilder sb = new StringBuilder();
        for (SWRLAtom atom : atoms) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(renderAtom(mm, atom));
        }
        return sb.toString();
    }

    private static String renderAtom(OWLModelManager mm, SWRLAtom atom) {
        if (atom instanceof SWRLClassAtom) {
            SWRLClassAtom a = (SWRLClassAtom) atom;
            return Tools.renderObject(mm, a.getPredicate()) + "(" + renderArg(mm, a.getArgument()) + ")";
        }
        if (atom instanceof SWRLObjectPropertyAtom) {
            SWRLObjectPropertyAtom a = (SWRLObjectPropertyAtom) atom;
            return Tools.renderObject(mm, a.getPredicate()) + "(" + renderArg(mm, a.getFirstArgument())
                    + ", " + renderArg(mm, a.getSecondArgument()) + ")";
        }
        if (atom instanceof SWRLDataPropertyAtom) {
            SWRLDataPropertyAtom a = (SWRLDataPropertyAtom) atom;
            return Tools.renderObject(mm, a.getPredicate()) + "(" + renderArg(mm, a.getFirstArgument())
                    + ", " + renderArg(mm, a.getSecondArgument()) + ")";
        }
        if (atom instanceof SWRLSameIndividualAtom) {
            SWRLSameIndividualAtom a = (SWRLSameIndividualAtom) atom;
            return "sameAs(" + renderArg(mm, a.getFirstArgument()) + ", "
                    + renderArg(mm, a.getSecondArgument()) + ")";
        }
        if (atom instanceof SWRLDifferentIndividualsAtom) {
            SWRLDifferentIndividualsAtom a = (SWRLDifferentIndividualsAtom) atom;
            return "differentFrom(" + renderArg(mm, a.getFirstArgument()) + ", "
                    + renderArg(mm, a.getSecondArgument()) + ")";
        }
        if (atom instanceof SWRLBuiltInAtom) {
            SWRLBuiltInAtom a = (SWRLBuiltInAtom) atom;
            StringBuilder sb = new StringBuilder(renderBuiltin(a.getPredicate())).append("(");
            boolean first = true;
            for (SWRLDArgument d : a.getArguments()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(renderArg(mm, d));
                first = false;
            }
            return sb.append(")").toString();
        }
        return atom.toString();
    }

    /** A rule argument: a variable as {@code ?}shortName, an individual via Protégé, a literal lexically. */
    private static String renderArg(OWLModelManager mm, SWRLArgument arg) {
        if (arg instanceof SWRLVariable) {
            return "?" + shortName(((SWRLVariable) arg).getIRI());
        }
        if (arg instanceof SWRLIndividualArgument) {
            return Tools.renderObject(mm, ((SWRLIndividualArgument) arg).getIndividual());
        }
        if (arg instanceof SWRLLiteralArgument) {
            return ((SWRLLiteralArgument) arg).getLiteral().getLiteral();
        }
        return arg.toString();
    }

    /** A built-in predicate as {@code swrlb:}fragment (standard ns), else its fragment, else the full IRI. */
    private static String renderBuiltin(IRI builtin) {
        String s = builtin.toString();
        if (s.startsWith(SWRLB_NS)) {
            return "swrlb:" + s.substring(SWRLB_NS.length());
        }
        return shortName(builtin);
    }

    /** The fragment of an IRI, else its last path segment, else the whole IRI. */
    private static String shortName(IRI iri) {
        String frag = iri.getFragment();
        if (frag != null && !frag.isEmpty()) {
            return frag;
        }
        String s = iri.toString();
        int slash = s.lastIndexOf('/');
        return slash >= 0 && slash < s.length() - 1 ? s.substring(slash + 1) : s;
    }

    private static boolean hasLabel(SWRLRule rule, String label) {
        for (OWLAnnotation ann : rule.getAnnotations()) {
            if (ann.getProperty().isLabel() && ann.getValue().asLiteral().isPresent()
                    && label.equals(ann.getValue().asLiteral().get().getLiteral())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ schemas

    private static Map<String, Object> ruleSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("body", atomArray("Body atoms (the rule's premise; may be empty)."));
        props.put("head", atomArray("Head atoms (the rule's conclusion; at least one)."));
        props.put("variable_namespace", Tools.stringProperty("Namespace for ?name variables "
                + "(default " + DEFAULT_VARIABLE_NS + "). ?<absolute IRI> ignores this."));
        props.put("annotations", annotationArrayProp());
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", Arrays.asList("head"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> removeRuleSchema() {
        Map<String, Object> schema = ruleSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        props.remove("body");
        props.remove("head");
        // re-add body/head as optional (no 'required') for the exact-match removal path
        props.put("body", atomArray("Body atoms of the exact rule to remove (with 'head')."));
        props.put("head", atomArray("Head atoms of the exact rule to remove (with 'body')."));
        props.put("index", intProperty("0-based index into the active ontology's rules in the same "
                + "rendering-sorted order list_rules returns (call list_rules without include_imports)."));
        props.put("label", Tools.stringProperty("Remove rule(s) whose rdfs:label equals this."));
        schema.remove("required");
        return schema;
    }

    private static Map<String, Object> atomArray(String desc) {
        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("type", "array");
        arr.put("items", atomItemSchema());
        arr.put("description", desc);
        return arr;
    }

    private static Map<String, Object> atomItemSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", Tools.stringProperty("class | object_property | data_property | same_as | "
                + "different_from | builtin"));
        props.put("predicate", Tools.stringProperty("class/object_property/data_property: the class or "
                + "property IRI/name (a class may be a Manchester expression)."));
        props.put("predicate_iri", Tools.stringProperty("class/object_property/data_property: full IRI "
                + "of the predicate (alternative to 'predicate', preferred when present — lets "
                + "list_rules output replay verbatim across ontologies)."));
        props.put("arg1", Tools.stringProperty("First argument: ?variable or an individual IRI/name."));
        props.put("arg2", Tools.stringProperty("Second argument: ?variable, an individual (object_"
                + "property/same_as/different_from) or a literal/?variable (data_property)."));
        props.put("value", Tools.stringProperty("data_property: literal value (alternative to a "
                + "variable arg2)."));
        props.put("lang", Tools.stringProperty("data_property value: optional language tag."));
        props.put("datatype", Tools.stringProperty("data_property value: optional datatype IRI/name."));
        props.put("builtin", Tools.stringProperty("builtin: the built-in IRI/CURIE, e.g. "
                + "swrlb:greaterThan."));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", "array");
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("description", "A ?variable, a plain literal value (string), or a typed/lang literal "
                + "object {value, datatype} / {value, lang}.");
        args.put("items", items);
        args.put("description", "builtin: ordered d-arguments (?variables or literal values; typed/lang "
                + "literals as {value, datatype|lang}).");
        props.put("args", args);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", props);
        item.put("additionalProperties", false);
        return item;
    }

    private static Map<String, Object> annotationArrayProp() {
        // Mirror SchemaBuilder.annotationArray's item shape for rule-level annotations.
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("property", Tools.stringProperty("Annotation property: 'rdfs:label', "
                + "'rdfs:comment', or an IRI/name (default rdfs:label)."));
        itemProps.put("value", Tools.stringProperty("Literal text value (omit if value_iri is given)."));
        itemProps.put("value_iri", Tools.stringProperty("IRI-valued annotation (alternative to value)."));
        itemProps.put("lang", Tools.stringProperty("Optional language tag for a literal value."));
        itemProps.put("datatype", Tools.stringProperty("Optional datatype IRI/name for a typed literal."));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("additionalProperties", false);
        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("type", "array");
        arr.put("items", item);
        arr.put("description", "Optional rule-level annotations (rdfs:label, dcterms:description, …).");
        return arr;
    }

    private static Map<String, Object> intProperty(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", desc);
        return p;
    }
}
