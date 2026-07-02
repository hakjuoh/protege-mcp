package io.github.hakjuoh.tools;

import java.util.ArrayList;
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
                        .strReq("name", "Short name — the IRI local part when minting, and the default label.")
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
                    String name = Tools.reqString(a, "name");
                    boolean strict = Tools.optBool(a, "strict", false);
                    return WriteTools.write(ctx, "create_term " + name, mm -> {
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
                        return applyCuration(mm, ont, changes, strict, cls,
                                Tools.json().put("created", Tools.entityJson(mm, cls)));
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
                        .strReq("name", "Short name — the IRI local part when minting, and the default label.")
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
                    String name = Tools.reqString(a, "name");
                    boolean strict = Tools.optBool(a, "strict", false);
                    String type = propertyType(a);
                    return WriteTools.write(ctx, "create_property " + type + " " + name, mm -> {
                        OWLDataFactory df = mm.getOWLDataFactory();
                        OWLOntology ont = mm.getActiveOntology();
                        List<OWLOntologyChange> changes = new ArrayList<>();
                        OWLEntity prop = WriteTools.createEntity(mm, type + "_property", a, changes);
                        changes.addAll("data".equals(type)
                                ? dataPropertyAxioms(mm, df, ont, (OWLDataProperty) prop, a)
                                : objectPropertyAxioms(mm, df, ont, (OWLObjectProperty) prop, a));
                        return applyCuration(mm, ont, changes, strict, prop,
                                Tools.json().put("created", Tools.entityJson(mm, prop)));
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
        mm.applyChanges(changes);
        if (!minted.isEmpty()) {
            result.put("new_entities", Tools.entityList(mm, minted, Integer.MAX_VALUE));
        }
        return result.put("applied", changes.size()).result();
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
