package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * High-level curation macros: the multi-axiom modelling moves that are otherwise several low-level
 * {@code add_axiom} / {@code apply_changes} calls. Each tool applies its whole edit as ONE undoable
 * transaction (via {@link OWLModelManager#applyChanges}), so it appears in the GUI and reverts in a
 * single {@code undo_change}, like {@code create_class}.
 *
 * <ul>
 *   <li>{@code create_term} — a class with a label, a definition, an arbitrary annotation suite,
 *       parent(s) (named or a Manchester restriction), and optional equivalent-class (defined-class)
 *       expressions, in one step.</li>
 *   <li>{@code create_terms} — a batch of {@code create_term}s applied as ONE undoable transaction
 *       (term-request intake), with optional shared {@code namespace}/{@code definition_property}
 *       defaults; the whole batch is atomic (all terms or none).</li>
 *   <li>{@code create_property} — an object/data property with a label, definition, domain, range,
 *       super-properties, characteristics (functional, transitive, …) and an inverse, in one step.</li>
 *   <li>{@code deprecate_entity} — the standard obsolescence pattern: {@code owl:deprecated true} plus
 *       an optional "term replaced by" pointer and any extra curation annotations.</li>
 *   <li>{@code move_class} — reparent a class (its subtree follows), replacing its asserted named
 *       superclasses with a new parent.</li>
 * </ul>
 *
 * <p>The change-computation is factored into static, Protégé-free helpers so it is unit-tested on a
 * hand-built ontology; the tool handlers only resolve name/IRI/Manchester operands (which needs the
 * live Protégé entity finder) before delegating to them.
 */
public final class CurationTools {

    private CurationTools() {
    }

    /** Default annotation property for a definition when the caller does not name one. */
    static final IRI TERM_REPLACED_BY = IRI.create("http://purl.obolibrary.org/obo/IAO_0100001");

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("create_term",
                "Create a class WITH its curation suite in one undoable step (vs. create_class + several "
                        + "add_axiom calls): mints the class (same 'iri'/'namespace'/'label'/'label_lang'/"
                        + "'no_label' options as create_class), adds a definition ('definition', default "
                        + "property rdfs:comment — set 'definition_property' e.g. skos:definition), any "
                        + "extra 'annotations', one or more 'parents' (each a class name, IRI or "
                        + "Manchester restriction such as 'hasPart some Cell'), and optional "
                        + "'equivalent_to' class expressions for a defined class. Reports the created "
                        + "term and any 'new_entities' the operands introduced; strict=true refuses to "
                        + "mint an unrecognised operand.",
                Tools.schema()
                        .str("name", "Short name — the IRI local part when minting, and the default label. "
                                + "Optional when a full 'iri' is given (its local part becomes the name).")
                        .str("iri", "Full IRI to use (optional; overrides 'namespace').")
                        .str("namespace", "Namespace to mint the IRI in: IRI = namespace + name (optional).")
                        .str("label", "rdfs:label text (default: 'name').")
                        .str("label_lang", "Language tag for the rdfs:label, e.g. 'en' (default: none).")
                        .bool("no_label", "Do not add any rdfs:label (default false).")
                        .str("definition", "Definition text (optional).")
                        .str("definition_property", "Annotation property for the definition (default "
                                + "rdfs:comment; e.g. skos:definition or an IOF *Definition property).")
                        .str("definition_lang", "Language tag for the definition literal (optional).")
                        .strArray("parents", "Superclasses: each a class name, IRI or Manchester class "
                                + "expression (optional).")
                        .strArray("equivalent_to", "Equivalent class expressions for a defined class "
                                + "(each a name, IRI or Manchester class expression; optional).")
                        .annotationArray("annotations", "Extra annotations for the term (array of "
                                + "{property, value | value_iri, lang, datatype}).")
                        .bool("strict", WriteTools.STRICT_DESC)
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean strict = Tools.optBool(a, "strict", false);
                    return WriteTools.write(ctx, "create_term " + WriteTools.summaryName(a), mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        OWLClass cls = (OWLClass) WriteTools.createEntity(mm, "class", a, changes);
                        List<OWLClassExpression> parents = classExprs(mm, a, "parents", "parent");
                        List<OWLClassExpression> equivalents = classExprs(mm, a, "equivalent_to", null);
                        OWLAnnotationProperty defProp = definitionProperty(mm, a);
                        String defText = Tools.optString(a, "definition");
                        String defLang = Tools.optString(a, "definition_lang");
                        Set<OWLAnnotation> extra = Tools.annotationSet(mm, a, "annotations");
                        changes.addAll(termAxioms(df, ont, cls, parents, equivalents,
                                defProp, defText, defLang, extra));
                        return applyCuration(mm, ont, changes, strict, cls, Tools.json());
                    });
                }));

        tools.tool("create_terms",
                "Create MANY classes in one undoable transaction (batch term-request intake) — the array "
                        + "form of create_term. 'terms' is an array; each item takes the same fields as "
                        + "create_term ('name', optional when a full 'iri' is given, plus "
                        + "'iri'/'namespace'/'label'/'label_lang'/"
                        + "'no_label'/'definition'/'definition_property'/'definition_lang'/'parents'/"
                        + "'equivalent_to'/'annotations'). Top-level 'namespace' and 'definition_property' "
                        + "are DEFAULTS applied to any term that omits its own (mint IRIs in a shared "
                        + "namespace, set one definition property for all). The whole batch is applied as a "
                        + "SINGLE undoable change (one undo_change reverts every term) and is ATOMIC: if any "
                        + "term is malformed, nothing is applied. strict=true refuses the whole batch if any "
                        + "operand would be minted as a new, empty entity (a typo guard). Note: to make one "
                        + "term reference ANOTHER term in the SAME batch (e.g. as a parent), give that other "
                        + "term an explicit 'iri'/'namespace' and reference it by its full IRI — nothing is "
                        + "in the ontology until the batch commits, so a bare name for a same-batch term "
                        + "won't resolve.",
                createTermsSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> termSpecs = Tools.objList(a, "terms");
                    if (termSpecs.isEmpty()) {
                        return Tools.error("Provide at least one term in 'terms' (each: 'name' plus "
                                + "optional iri/namespace/label/definition/parents/equivalent_to/...).");
                    }
                    boolean strict = Tools.optBool(a, "strict", false);
                    String defaultNamespace = Tools.optString(a, "namespace");
                    String defaultDefProp = Tools.optString(a, "definition_property");
                    return WriteTools.write(ctx, "create_terms (" + termSpecs.size() + ")", mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        List<OWLClass> created = new ArrayList<>();
                        Set<OWLEntity> createdSet = new LinkedHashSet<>();
                        for (int i = 0; i < termSpecs.size(); i++) {
                            Map<String, Object> spec =
                                    withDefaults(termSpecs.get(i), defaultNamespace, defaultDefProp);
                            try {
                                OWLClass cls = (OWLClass) WriteTools.createEntity(mm, "class", spec, changes);
                                List<OWLClassExpression> parents = classExprs(mm, spec, "parents", "parent");
                                List<OWLClassExpression> equivalents =
                                        classExprs(mm, spec, "equivalent_to", null);
                                OWLAnnotationProperty defProp = definitionProperty(mm, spec);
                                if (!createdSet.add(cls)) {
                                    // Two specs resolving to the same IRI would otherwise silently merge
                                    // into one class (accumulating both labels/definitions) yet be
                                    // reported as two created terms. Reject the collision instead.
                                    throw new ToolArgException("duplicates an earlier term in this batch "
                                            + "(IRI <" + cls.getIRI() + ">) — each term must mint a "
                                            + "distinct IRI.");
                                }
                                changes.addAll(termAxioms(df, ont, cls, parents, equivalents, defProp,
                                        Tools.optString(spec, "definition"),
                                        Tools.optString(spec, "definition_lang"),
                                        Tools.annotationSet(mm, spec, "annotations")));
                                created.add(cls);
                            } catch (ToolArgException e) {
                                throw new ToolArgException("terms[" + i + "]" + termLabel(spec) + ": "
                                        + e.getMessage() + " Nothing was applied.");
                            }
                        }
                        return applyBatchCuration(mm, ont, changes, strict, createdSet, created);
                    });
                }));

        tools.tool("create_property",
                "Create an object or data property WITH its axioms in one undoable step: mints the "
                        + "property ('property_type' object|data, default object; same "
                        + "'iri'/'namespace'/'label'/'label_lang'/'no_label' options), and optionally "
                        + "adds a 'definition', extra 'annotations', a 'domain' (class expression), a "
                        + "'range' (class expression for object; datatype / Manchester data range for "
                        + "data), 'super_properties', 'characteristics' (object: functional, "
                        + "inverse_functional, transitive, symmetric, asymmetric, reflexive, "
                        + "irreflexive; data: functional) and an 'inverse_of' (object only). Reports "
                        + "'new_entities'; strict=true refuses to mint an unrecognised operand.",
                Tools.schema()
                        .str("name", "Short name — the IRI local part when minting, and the default label. "
                                + "Optional when a full 'iri' is given (its local part becomes the name).")
                        .str("property_type", "object (default) | data.")
                        .str("iri", "Full IRI to use (optional; overrides 'namespace').")
                        .str("namespace", "Namespace to mint the IRI in: IRI = namespace + name (optional).")
                        .str("label", "rdfs:label text (default: 'name').")
                        .str("label_lang", "Language tag for the rdfs:label (optional).")
                        .bool("no_label", "Do not add any rdfs:label (default false).")
                        .str("definition", "Definition text (optional).")
                        .str("definition_property", "Annotation property for the definition (default "
                                + "rdfs:comment).")
                        .str("definition_lang", "Language tag for the definition literal (optional).")
                        .str("domain", "Domain class expression (name, IRI or Manchester; optional).")
                        .str("range", "Range: a class expression for an object property, or a datatype / "
                                + "Manchester data range (e.g. xsd:integer[>= 0]) for a data property.")
                        .strArray("super_properties", "Super-properties this is a subproperty of (optional).")
                        .strArray("characteristics", "Property characteristics to assert (optional).")
                        .str("inverse_of", "Inverse object property (object properties only; optional).")
                        .annotationArray("annotations", "Extra annotations (array of {property, value | "
                                + "value_iri, lang, datatype}).")
                        .bool("strict", WriteTools.STRICT_DESC)
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean strict = Tools.optBool(a, "strict", false);
                    String type = propertyType(a);
                    return WriteTools.write(ctx, "create_property " + type + " " + WriteTools.summaryName(a), mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        OWLEntity prop = WriteTools.createEntity(mm, type + "_property", a, changes);
                        changes.addAll("data".equals(type)
                                ? dataPropertyAxioms(mm, df, ont, (OWLDataProperty) prop, a)
                                : objectPropertyAxioms(mm, df, ont, (OWLObjectProperty) prop, a));
                        return applyCuration(mm, ont, changes, strict, prop, Tools.json());
                    });
                }));

        tools.tool("create_properties",
                "Create MANY object/data properties in ONE undoable transaction — the array form of "
                        + "create_property. 'properties' is an array; each item takes the same fields as "
                        + "create_property ('name', optional when a full 'iri' is given, plus "
                        + "property_type/iri/namespace/label/"
                        + "no_label/definition/definition_property/domain/range/super_properties/"
                        + "characteristics/inverse_of/annotations). Top-level 'namespace', "
                        + "'definition_property' and 'property_type' are DEFAULTS applied to any item that "
                        + "omits its own. The whole batch is a SINGLE undoable change (one undo_change "
                        + "reverts every property) and ATOMIC: if any item is malformed, nothing is "
                        + "applied. strict=true refuses the whole batch if any operand would be minted as a "
                        + "new, empty entity. To reference another property in THIS batch (e.g. as "
                        + "inverse_of / super_properties), give it an explicit iri/namespace and reference "
                        + "its full IRI — nothing is in the ontology until the batch commits.",
                createPropertiesSchema(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> specs = Tools.objList(a, "properties");
                    if (specs.isEmpty()) {
                        return Tools.error("Provide at least one property in 'properties'.");
                    }
                    boolean strict = Tools.optBool(a, "strict", false);
                    String defNs = Tools.optString(a, "namespace");
                    String defDef = Tools.optString(a, "definition_property");
                    String defType = Tools.optString(a, "property_type");
                    return WriteTools.write(ctx, "create_properties (" + specs.size() + ")", mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        List<OWLEntity> created = new ArrayList<>();
                        Set<OWLEntity> createdSet = new LinkedHashSet<>();
                        for (int i = 0; i < specs.size(); i++) {
                            Map<String, Object> spec = withDefaults(specs.get(i), defNs, defDef, defType);
                            try {
                                String type = propertyType(spec);
                                OWLEntity prop = WriteTools.createEntity(mm, type + "_property", spec, changes);
                                if (!createdSet.add(prop)) {
                                    throw new ToolArgException("duplicates an earlier property in this "
                                            + "batch (IRI <" + prop.getIRI() + ">) — each must mint a "
                                            + "distinct IRI.");
                                }
                                changes.addAll("data".equals(type)
                                        ? dataPropertyAxioms(mm, df, ont, (OWLDataProperty) prop, spec)
                                        : objectPropertyAxioms(mm, df, ont, (OWLObjectProperty) prop, spec));
                                created.add(prop);
                            } catch (ToolArgException e) {
                                throw new ToolArgException("properties[" + i + "]" + termLabel(spec) + ": "
                                        + e.getMessage() + " Nothing was applied.");
                            }
                        }
                        return applyBatchCuration(mm, ont, changes, strict, createdSet, created);
                    });
                }));

        tools.tool("deprecate_entity",
                "Deprecate a term (the standard obsolescence pattern) in one undoable step: asserts "
                        + "owl:deprecated true, and — when 'replaced_by' is given — a 'term replaced by' "
                        + "pointer (IAO_0100001 by default; override with 'replaced_by_property') to the "
                        + "replacement term, plus any extra 'annotations' (e.g. a skos:changeNote). The "
                        + "term and its axioms are kept (existing usages are NOT rewritten — repoint them "
                        + "with rename_entity or edit each axiom if a full merge is intended).",
                Tools.schema()
                        .strReq("entity", "Term to deprecate: an IRI or display name.")
                        .str("replaced_by", "Replacement term: an IRI or display name (optional).")
                        .str("replaced_by_property", "Annotation property for the replacement pointer "
                                + "(default IAO_0100001 'term replaced by').")
                        .annotationArray("annotations", "Extra annotations to add (e.g. a change note; "
                                + "array of {property, value | value_iri, lang, datatype}).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    return WriteTools.write(ctx, "deprecate " + entityRef, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        OWLEntity target = Tools.findEntity(mm, entityRef);
                        if (target == null) {
                            return Tools.error("Entity not found: '" + entityRef + "'.");
                        }
                        IRI replacedBy = null;
                        String replacedByRef = Tools.optString(a, "replaced_by");
                        if (replacedByRef != null) {
                            replacedBy = Tools.iriRef(mm, replacedByRef);
                        }
                        String rbpRef = Tools.optString(a, "replaced_by_property");
                        OWLAnnotationProperty replacedByProp = rbpRef != null
                                ? Tools.resolveAnnotationProperty(mm, rbpRef)
                                : df.getOWLAnnotationProperty(TERM_REPLACED_BY);
                        Set<OWLAnnotation> extra = Tools.annotationSet(mm, a, "annotations");
                        List<OWLOntologyChange> changes = deprecationChanges(df, ont, target, replacedBy,
                                replacedByProp, extra);
                        if (changes.isEmpty()) {
                            return Tools.json()
                                    .put("deprecated", Tools.entityJson(mm, target))
                                    .put("applied", 0)
                                    .put("note", "Already deprecated with the requested annotations — "
                                            + "nothing to change.")
                                    .result();
                        }
                        WriteTools.declareMintedFromChanges(mm, ont, changes, target);
                        mm.applyChanges(changes);
                        return Tools.json()
                                .put("deprecated", Tools.entityJson(mm, target))
                                .putIfNotNull("replaced_by", replacedBy == null ? null : replacedBy.toString())
                                .put("applied", changes.size())
                                .result();
                    });
                }));

        tools.tool("move_class",
                "Reparent a class (its subtree follows automatically, since subclasses point at it): "
                        + "removes the class's asserted NAMED superclass axioms and asserts "
                        + "SubClassOf(class, new_parent). Anonymous restriction superclasses are left "
                        + "untouched. Pass keep_other_parents=true to ADD the new parent without removing "
                        + "the existing ones; omit new_parent to detach the class to a root. One undoable "
                        + "change.",
                Tools.schema()
                        .strReq("entity", "Class to move: an IRI or display name.")
                        .str("new_parent", "New superclass: a class name, IRI or Manchester class "
                                + "expression (omit to detach to a root).")
                        .bool("keep_other_parents", "Add new_parent without removing existing named "
                                + "superclasses (default false: replace them).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    String newParentRef = Tools.optString(a, "new_parent");
                    boolean keepOthers = Tools.optBool(a, "keep_other_parents", false);
                    return WriteTools.write(ctx, "move_class " + entityRef, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        OWLClass cls = Tools.resolveClass(mm, entityRef);
                        OWLClassExpression newParent = newParentRef == null
                                ? null : Tools.resolveClassExpression(mm, newParentRef);
                        if (newParent == null && keepOthers) {
                            return Tools.error("Nothing to do: provide new_parent, or omit "
                                    + "keep_other_parents to detach the class to a root.");
                        }
                        List<OWLOntologyChange> changes = moveClassChanges(df, ont, cls, newParent, keepOthers);
                        int removed = 0;
                        for (OWLOntologyChange ch : changes) {
                            if (ch instanceof RemoveAxiom) {
                                removed++;
                            }
                        }
                        if (changes.isEmpty()) {
                            return Tools.json().put("moved", Tools.entityJson(mm, cls))
                                    .put("removed_parents", 0).put("applied", 0)
                                    .put("note", "No change — the class already has exactly this parent.")
                                    .result();
                        }
                        WriteTools.declareMintedFromChanges(mm, ont, changes, cls);
                        mm.applyChanges(changes);
                        return Tools.json()
                                .put("moved", Tools.entityJson(mm, cls))
                                .putIfNotNull("new_parent", newParent == null ? null
                                        : Tools.renderObject(mm, newParent))
                                .put("removed_parents", removed)
                                .put("applied", changes.size())
                                .result();
                    });
                }));
    }

    // ================================================================== change builders (Protégé-free)

    /**
     * The axioms for a curated term: parent SubClassOf axioms, an EquivalentClasses axiom for each
     * defined-class expression, a definition annotation, and the extra annotation suite. Pure OWL API.
     */
    static List<OWLOntologyChange> termAxioms(OWLDataFactory df, OWLOntology ont, OWLClass cls,
            List<OWLClassExpression> parents, List<OWLClassExpression> equivalents,
            OWLAnnotationProperty defProp, String defText, String defLang, Set<OWLAnnotation> extra) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLClassExpression parent : parents) {
            add(changes, ont, df.getOWLSubClassOfAxiom(cls, parent));
        }
        for (OWLClassExpression equiv : equivalents) {
            add(changes, ont, df.getOWLEquivalentClassesAxiom(cls, equiv));
        }
        addAnnotationAxioms(changes, df, ont, cls, defProp, defText, defLang, extra);
        return changes;
    }

    /** Object-property axioms from the create_property operands (resolution needs the model manager). */
    private static List<OWLOntologyChange> objectPropertyAxioms(OWLModelManager mm, OWLDataFactory df,
            OWLOntology ont, OWLObjectProperty prop, Map<String, Object> a) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        String domain = Tools.optString(a, "domain");
        if (domain != null) {
            add(changes, ont, df.getOWLObjectPropertyDomainAxiom(prop,
                    Tools.resolveClassExpression(mm, domain)));
        }
        String range = Tools.optString(a, "range");
        if (range != null) {
            add(changes, ont, df.getOWLObjectPropertyRangeAxiom(prop,
                    Tools.resolveClassExpression(mm, range)));
        }
        for (String sup : Tools.stringList(a, "super_properties")) {
            add(changes, ont, df.getOWLSubObjectPropertyOfAxiom(prop,
                    Tools.resolveObjectProperty(mm, sup)));
        }
        for (String ch : Tools.stringList(a, "characteristics")) {
            add(changes, ont, objectCharacteristicAxiom(df, ch, prop));
        }
        String inverse = Tools.optString(a, "inverse_of");
        if (inverse != null) {
            add(changes, ont, df.getOWLInverseObjectPropertiesAxiom(prop,
                    Tools.resolveObjectProperty(mm, inverse)));
        }
        addAnnotationAxioms(changes, df, ont, prop, definitionProperty(mm, a),
                Tools.optString(a, "definition"), Tools.optString(a, "definition_lang"),
                Tools.annotationSet(mm, a, "annotations"));
        return changes;
    }

    /** Data-property axioms from the create_property operands (resolution needs the model manager). */
    private static List<OWLOntologyChange> dataPropertyAxioms(OWLModelManager mm, OWLDataFactory df,
            OWLOntology ont, OWLDataProperty prop, Map<String, Object> a) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        String domain = Tools.optString(a, "domain");
        if (domain != null) {
            add(changes, ont, df.getOWLDataPropertyDomainAxiom(prop,
                    Tools.resolveClassExpression(mm, domain)));
        }
        String range = Tools.optString(a, "range");
        if (range != null) {
            OWLDataRange dr = Tools.resolveDataRange(mm, range);
            add(changes, ont, df.getOWLDataPropertyRangeAxiom(prop, dr));
        }
        for (String sup : Tools.stringList(a, "super_properties")) {
            add(changes, ont, df.getOWLSubDataPropertyOfAxiom(prop,
                    Tools.resolveDataProperty(mm, sup)));
        }
        for (String ch : Tools.stringList(a, "characteristics")) {
            add(changes, ont, dataCharacteristicAxiom(df, ch, prop));
        }
        if (Tools.optString(a, "inverse_of") != null) {
            throw new ToolArgException("inverse_of applies to object properties only; a data property "
                    + "has no inverse.");
        }
        addAnnotationAxioms(changes, df, ont, prop, definitionProperty(mm, a),
                Tools.optString(a, "definition"), Tools.optString(a, "definition_lang"),
                Tools.annotationSet(mm, a, "annotations"));
        return changes;
    }

    /**
     * The obsolescence changes for {@code target}: {@code owl:deprecated true}, an optional
     * "term replaced by" pointer, and any extra annotations — each added only if not already present, so
     * re-deprecating is a no-op. Pure OWL API.
     */
    static List<OWLOntologyChange> deprecationChanges(OWLDataFactory df, OWLOntology ont,
            OWLEntity target, IRI replacedBy, OWLAnnotationProperty replacedByProp,
            Set<OWLAnnotation> extra) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        OWLAnnotationProperty deprecated =
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI());
        add(changes, ont, df.getOWLAnnotationAssertionAxiom(deprecated, target.getIRI(),
                df.getOWLLiteral(true)));
        if (replacedBy != null) {
            add(changes, ont, df.getOWLAnnotationAssertionAxiom(replacedByProp, target.getIRI(),
                    replacedBy));
        }
        for (OWLAnnotation ann : extra) {
            add(changes, ont, df.getOWLAnnotationAssertionAxiom(ann.getProperty(), target.getIRI(),
                    ann.getValue()));
        }
        return changes;
    }

    /**
     * Reparent {@code cls}: remove its asserted NAMED-superclass axioms (unless {@code keepOthers}) and,
     * when {@code newParent} is given, assert {@code SubClassOf(cls, newParent)}. Anonymous (restriction)
     * superclasses are preserved, and an existing named parent equal to {@code newParent} is kept. Pure
     * OWL API.
     */
    static List<OWLOntologyChange> moveClassChanges(OWLDataFactory df, OWLOntology ont, OWLClass cls,
            OWLClassExpression newParent, boolean keepOthers) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        if (!keepOthers) {
            for (OWLSubClassOfAxiom ax : ont.getSubClassAxiomsForSubClass(cls)) {
                OWLClassExpression sup = ax.getSuperClass();
                if (sup.isAnonymous() || sup.equals(newParent)) {
                    continue;   // keep restriction supers, and the target parent if already present
                }
                changes.add(new RemoveAxiom(ont, ax));
            }
        }
        if (newParent != null) {
            add(changes, ont, df.getOWLSubClassOfAxiom(cls, newParent));
        }
        return changes;
    }

    // ================================================================== shared helpers

    /** Add an {@link AddAxiom} for {@code ax} unless the ontology already asserts it. */
    private static void add(List<OWLOntologyChange> changes, OWLOntology ont, OWLAxiom ax) {
        if (!ont.containsAxiom(ax)) {
            changes.add(new AddAxiom(ont, ax));
        }
    }

    /** Append a definition annotation (if {@code defText} present) and the extra annotation suite. */
    private static void addAnnotationAxioms(List<OWLOntologyChange> changes, OWLDataFactory df,
            OWLOntology ont, OWLEntity subject, OWLAnnotationProperty defProp, String defText,
            String defLang, Set<OWLAnnotation> extra) {
        if (defText != null) {
            OWLLiteral lit = defLang != null ? df.getOWLLiteral(defText, defLang) : df.getOWLLiteral(defText);
            add(changes, ont, df.getOWLAnnotationAssertionAxiom(defProp, subject.getIRI(), lit));
        }
        for (OWLAnnotation ann : extra) {
            OWLAnnotationValue value = ann.getValue();
            add(changes, ont, df.getOWLAnnotationAssertionAxiom(ann.getProperty(), subject.getIRI(), value));
        }
    }

    /**
     * Apply a curation batch as one transaction. Computes the entities the operands would mint (against
     * the imports closure, before applying) minus {@code created} itself; with {@code strict} and a
     * non-empty set, nothing is applied. Otherwise the batch is committed and the result reports the
     * created entity and any {@code new_entities}.
     */
    private static CallToolResult applyCuration(OWLModelManager mm, OWLOntology ont,
            List<OWLOntologyChange> changes, boolean strict, OWLEntity created, Tools.Json result) {
        Set<OWLOntology> closure = ont.getImportsClosure();
        Set<OWLEntity> minted = new LinkedHashSet<>();
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom) {
                minted.addAll(PreviewTools.newEntities(closure, ((AddAxiom) ch).getAxiom()));
            }
        }
        minted.remove(created);
        if (strict && !minted.isEmpty()) {
            return Tools.error("Refusing to apply (strict): the operand(s) "
                    + EntityRendering.renderMinted(mm, minted) + " are not declared anywhere in the "
                    + "imports closure and would be created as new, empty entities — likely a typo. Fix "
                    + "the reference, create it first, or set strict=false.");
        }
        WriteTools.declareMinted(mm.getOWLDataFactory(), ont, minted, changes);  // declare side-effect operands
        WriteTools.declareUsedAnnotationProperties(mm.getOWLDataFactory(), ont, changes);
        mm.applyChanges(changes);
        // Render the created entity AFTER applying so its rdfs:label is already in the ontology and
        // 'created.display' shows the label, not the bare IRI fragment — matching the batch path.
        result.put("created", Tools.entityJson(mm, created));
        if (!minted.isEmpty()) {
            result.put("new_entities", Tools.entityList(mm, minted, Integer.MAX_VALUE));
        }
        return result.put("applied", changes.size()).result();
    }

    // ================================================================== create_terms (batch)

    /**
     * Schema for {@code create_terms}: a required {@code terms} array whose items mirror {@code
     * create_term}'s fields, plus top-level {@code namespace} / {@code definition_property} defaults and a
     * {@code strict} flag. Hand-assembled (rather than via {@link Tools.SchemaBuilder}) because the item is
     * itself an object schema — the same shape {@code create_term} builds.
     */
    private static Map<String, Object> createTermsSchema() {
        Map<String, Object> item = Tools.schema()
                .str("name", "Short name — the IRI local part when minting, and the default label. "
                        + "Optional when a full 'iri' is given (its local part becomes the name).")
                .str("iri", "Full IRI to use (optional; overrides 'namespace').")
                .str("namespace", "Namespace to mint the IRI in: IRI = namespace + name (optional; "
                        + "falls back to the top-level 'namespace' default).")
                .str("label", "rdfs:label text (default: 'name').")
                .str("label_lang", "Language tag for the rdfs:label, e.g. 'en' (default: none).")
                .bool("no_label", "Do not add any rdfs:label (default false).")
                .str("definition", "Definition text (optional).")
                .str("definition_property", "Annotation property for the definition (optional; falls back "
                        + "to the top-level 'definition_property' default, else rdfs:comment).")
                .str("definition_lang", "Language tag for the definition literal (optional).")
                .strArray("parents", "Superclasses: each a class name, IRI or Manchester class expression. "
                        + "To reference another term in THIS batch, use its full IRI.")
                .strArray("equivalent_to", "Equivalent class expressions for a defined class (optional).")
                .annotationArray("annotations", "Extra annotations (array of {property, value | value_iri, "
                        + "lang, datatype}).")
                .build();

        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("type", "array");
        terms.put("items", item);
        terms.put("description", "The classes to create; each item is a create_term field set (only "
                + "'name' or a full 'iri' is required).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("terms", terms);
        properties.put("namespace", Tools.stringProperty("Default namespace for any term that gives "
                + "neither its own 'iri' nor 'namespace' (IRI = namespace + name)."));
        properties.put("definition_property", Tools.stringProperty("Default definition annotation "
                + "property for any term that omits its own 'definition_property' (e.g. skos:definition)."));
        properties.put("strict", Tools.boolProperty(WriteTools.STRICT_DESC));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.Collections.singletonList("terms"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Schema for {@code create_properties}: a required {@code properties} array whose items mirror {@code
     * create_property}'s fields, plus top-level {@code namespace} / {@code definition_property} /
     * {@code property_type} defaults and a {@code strict} flag. The array form of {@code create_property},
     * mirroring how {@link #createTermsSchema()} is the array form of {@code create_term}.
     */
    private static Map<String, Object> createPropertiesSchema() {
        Map<String, Object> item = Tools.schema()
                .str("name", "Short name — the IRI local part when minting, and the default label. "
                        + "Optional when a full 'iri' is given (its local part becomes the name).")
                .str("property_type", "object (default) | data.")
                .str("iri", "Full IRI to use (optional; overrides 'namespace').")
                .str("namespace", "Namespace to mint the IRI in: IRI = namespace + name (optional; "
                        + "falls back to the top-level 'namespace' default).")
                .str("label", "rdfs:label text (default: 'name').")
                .str("label_lang", "Language tag for the rdfs:label (optional).")
                .bool("no_label", "Do not add any rdfs:label (default false).")
                .str("definition", "Definition text (optional).")
                .str("definition_property", "Annotation property for the definition (optional; falls back "
                        + "to the top-level 'definition_property' default, else rdfs:comment).")
                .str("definition_lang", "Language tag for the definition literal (optional).")
                .str("domain", "Domain class expression (name, IRI or Manchester; optional).")
                .str("range", "Range: a class expression for an object property, or a datatype / Manchester "
                        + "data range (e.g. xsd:integer[>= 0]) for a data property.")
                .strArray("super_properties", "Super-properties this is a subproperty of (optional).")
                .strArray("characteristics", "Property characteristics to assert (optional).")
                .str("inverse_of", "Inverse object property (object properties only; optional).")
                .annotationArray("annotations", "Extra annotations (array of {property, value | value_iri, "
                        + "lang, datatype}).")
                .build();

        Map<String, Object> propertiesArray = new LinkedHashMap<>();
        propertiesArray.put("type", "array");
        propertiesArray.put("items", item);
        propertiesArray.put("description", "The properties to create; each item is a create_property "
                + "field set (only 'name' or a full 'iri' is required).");

        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        schemaProperties.put("properties", propertiesArray);
        schemaProperties.put("namespace", Tools.stringProperty("Default namespace for any item that gives "
                + "neither its own 'iri' nor 'namespace' (IRI = namespace + name)."));
        schemaProperties.put("definition_property", Tools.stringProperty("Default definition annotation "
                + "property for any item that omits its own 'definition_property'."));
        schemaProperties.put("property_type", Tools.stringProperty("Default property_type (object|data) "
                + "for any item that omits its own (else object)."));
        schemaProperties.put("strict", Tools.boolProperty(WriteTools.STRICT_DESC));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", schemaProperties);
        schema.put("required", java.util.Collections.singletonList("properties"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * A copy of {@code spec} with the batch-level defaults filled in: {@code namespace} only when the term
     * gives neither its own {@code namespace} nor an explicit {@code iri} (so an explicit IRI is never
     * overridden), and {@code definition_property} only when absent. Never mutates the caller's map.
     */
    static Map<String, Object> withDefaults(Map<String, Object> spec, String defaultNamespace,
            String defaultDefProp) {
        Map<String, Object> merged = new LinkedHashMap<>(spec);
        if (defaultNamespace != null && Tools.optString(merged, "namespace") == null
                && Tools.optString(merged, "iri") == null) {
            merged.put("namespace", defaultNamespace);
        }
        if (defaultDefProp != null && Tools.optString(merged, "definition_property") == null) {
            merged.put("definition_property", defaultDefProp);
        }
        return merged;
    }

    /**
     * As {@link #withDefaults(Map, String, String)} plus a {@code property_type} default (for
     * {@code create_properties}), applied only when the item omits its own. The 3-arg form is kept
     * unchanged for {@code create_terms}.
     */
    static Map<String, Object> withDefaults(Map<String, Object> spec, String defaultNamespace,
            String defaultDefProp, String defaultPropertyType) {
        Map<String, Object> merged = withDefaults(spec, defaultNamespace, defaultDefProp);
        if (defaultPropertyType != null && Tools.optString(merged, "property_type") == null) {
            merged.put("property_type", defaultPropertyType);
        }
        return merged;
    }

    /** " ('name')" for error context, or "" when the term has no name to report. */
    private static String termLabel(Map<String, Object> spec) {
        String name = Tools.optString(spec, "name");
        return name == null ? "" : " ('" + name + "')";
    }

    /**
     * Apply a whole batch of curated terms as ONE transaction. Mirrors {@link #applyCuration} but for many
     * created entities: the minted-operand set is every entity the batch's adds introduce minus the terms
     * the batch itself creates (so a term referencing an earlier same-batch term BY FULL IRI is not a
     * false strict violation). With {@code strict} and a non-empty remainder, nothing is applied.
     */
    static CallToolResult applyBatchCuration(OWLModelManager mm, OWLOntology ont,
            List<OWLOntologyChange> changes, boolean strict, Set<OWLEntity> createdSet,
            List<? extends OWLEntity> created) {
        Set<OWLOntology> closure = ont.getImportsClosure();
        Set<OWLEntity> minted = new LinkedHashSet<>();
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom) {
                minted.addAll(PreviewTools.newEntities(closure, ((AddAxiom) ch).getAxiom()));
            }
        }
        minted.removeAll(createdSet);
        if (strict && !minted.isEmpty()) {
            return Tools.error("Refusing to apply (strict): the operand(s) "
                    + EntityRendering.renderMinted(mm, minted) + " are not declared anywhere in the "
                    + "imports closure and would be created as new, empty entities — likely a typo. Fix "
                    + "the reference, create it first (or reference a same-batch term by its full IRI), or "
                    + "set strict=false. Nothing was applied.");
        }
        WriteTools.declareMinted(mm.getOWLDataFactory(), ont, minted, changes);  // declare side-effect operands
        WriteTools.declareUsedAnnotationProperties(mm.getOWLDataFactory(), ont, changes);
        mm.applyChanges(changes);   // one broadcast → one Protégé undo entry for the whole batch
        List<Map<String, Object>> createdJson = new ArrayList<>();
        for (OWLEntity c : created) {
            createdJson.add(Tools.entityJson(mm, c));
        }
        Tools.Json result = Tools.json()
                .put("created", createdJson)
                .put("count", created.size())
                .put("applied", changes.size());
        if (!minted.isEmpty()) {
            result.put("new_entities", Tools.entityList(mm, minted, Integer.MAX_VALUE));
        }
        return result.result();
    }

    /** Resolve the class expressions named by {@code key} (and an optional singular {@code singularKey}). */
    private static List<OWLClassExpression> classExprs(OWLModelManager mm, Map<String, Object> a,
            String key, String singularKey) {
        List<String> refs = new ArrayList<>(Tools.stringList(a, key));
        if (singularKey != null) {
            String single = Tools.optString(a, singularKey);
            if (single != null) {
                refs.add(single);
            }
        }
        List<OWLClassExpression> out = new ArrayList<>();
        for (String ref : refs) {
            out.add(Tools.resolveClassExpression(mm, ref));
        }
        return out;
    }

    /** The definition annotation property: the named 'definition_property', or rdfs:comment by default. */
    private static OWLAnnotationProperty definitionProperty(OWLModelManager mm, Map<String, Object> a) {
        String ref = Tools.optString(a, "definition_property");
        return ref == null ? mm.getOWLDataFactory().getRDFSComment() : Tools.annotationProperty(mm, ref);
    }

    private static String propertyType(Map<String, Object> a) {
        String raw = Tools.optString(a, "property_type");
        if (raw == null) {
            return "object";
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.equals("object") || t.equals("data")) {
            return t;
        }
        throw new ToolArgException("property_type must be 'object' or 'data', not '" + raw + "'.");
    }

    static OWLAxiom objectCharacteristicAxiom(OWLDataFactory df, String name,
            OWLObjectProperty p) {
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "functional":
                return df.getOWLFunctionalObjectPropertyAxiom(p);
            case "inverse_functional":
                return df.getOWLInverseFunctionalObjectPropertyAxiom(p);
            case "transitive":
                return df.getOWLTransitiveObjectPropertyAxiom(p);
            case "symmetric":
                return df.getOWLSymmetricObjectPropertyAxiom(p);
            case "asymmetric":
                return df.getOWLAsymmetricObjectPropertyAxiom(p);
            case "reflexive":
                return df.getOWLReflexiveObjectPropertyAxiom(p);
            case "irreflexive":
                return df.getOWLIrreflexiveObjectPropertyAxiom(p);
            default:
                throw new ToolArgException("Unknown object property characteristic '" + name
                        + "'. Use functional, inverse_functional, transitive, symmetric, asymmetric, "
                        + "reflexive or irreflexive.");
        }
    }

    static OWLAxiom dataCharacteristicAxiom(OWLDataFactory df, String name, OWLDataProperty p) {
        if ("functional".equals(name.trim().toLowerCase(Locale.ROOT))) {
            return df.getOWLFunctionalDataPropertyAxiom(p);
        }
        throw new ToolArgException("Unknown data property characteristic '" + name
                + "'. A data property can only be 'functional'.");
    }
}
