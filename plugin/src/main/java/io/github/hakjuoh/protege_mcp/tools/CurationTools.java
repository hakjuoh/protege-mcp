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

    /**
     * Default "term replaced by" annotation property for {@code deprecate_entity} when the caller
     * does not name one via {@code replaced_by_property}. IAO_0100001 is not a W3C standard but the
     * de-facto OBO Foundry convention for the obsolescence pattern.
     */
    static final IRI TERM_REPLACED_BY = IRI.create("http://purl.obolibrary.org/obo/IAO_0100001");

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("create_term",
                (ex, req) -> {
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
                });
        tools.tool("create_terms",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> termSpecs = Tools.objList(a, "terms");
                    if (termSpecs.isEmpty()) {
                        return Tools.error("Provide at least one term in 'terms' (each: 'name' plus "
                                + "optional iri/namespace/label/definition/parents/equivalent_to/...).");
                    }
                    boolean preview = Tools.optBool(a, "preview", false);
                    if (preview && termSpecs.size() > ChangePlanner.MAX_OPERATIONS) {
                        return Tools.error("terms exceeds the preview maximum of "
                                + ChangePlanner.MAX_OPERATIONS + " items.");
                    }
                    boolean strict = Tools.optBool(a, "strict", false);
                    String defaultNamespace = Tools.optString(a, "namespace");
                    String defaultDefProp = Tools.optString(a, "definition_property");
                    String verify = ApplyVerify.normalizeMode(Tools.optString(a, "verify"));
                    if (preview) {
                        if (!ApplyVerify.MODE_NONE.equals(verify)) {
                            return Tools.error("preview=true cannot be combined with verify; isolated "
                                    + "change-set preflight replaces post-apply verification.");
                        }
                        Map<String, Object> replay = new LinkedHashMap<>();
                        replay.put("terms", termSpecs);
                        replay.put("strict", strict);
                        if (defaultNamespace != null) {
                            replay.put("namespace", defaultNamespace);
                        }
                        if (defaultDefProp != null) {
                            replay.put("definition_property", defaultDefProp);
                        }
                        return ChangeSetTools.previewPrepared(ctx, a,
                                mm -> planTerms(mm, termSpecs, strict, defaultNamespace, defaultDefProp),
                                ChangeSetTools.KIND_CREATE_TERMS, replay);
                    }
                    String summary = "create_terms (" + termSpecs.size() + ")"
                            + (ApplyVerify.MODE_NONE.equals(verify) ? "" : " (verify=" + verify + ")");
                    if (ApplyVerify.MODE_NONE.equals(verify)) {
                        return WriteTools.write(ctx, summary, mm -> asResult(
                                createTermsBatch(mm, termSpecs, strict, defaultNamespace, defaultDefProp)));
                    }
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    if (timeout <= 0) {
                        timeout = 60_000;
                    }
                    return ApplyVerify.verifiedApply(ctx, verify, timeout, summary, "create_terms",
                            mm -> createTermsBatch(mm, termSpecs, strict, defaultNamespace, defaultDefProp));
                });
        tools.tool("create_property",
                (ex, req) -> {
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
                });
        tools.tool("create_properties",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    List<Map<String, Object>> specs = Tools.objList(a, "properties");
                    if (specs.isEmpty()) {
                        return Tools.error("Provide at least one property in 'properties'.");
                    }
                    boolean preview = Tools.optBool(a, "preview", false);
                    if (preview && specs.size() > ChangePlanner.MAX_OPERATIONS) {
                        return Tools.error("properties exceeds the preview maximum of "
                                + ChangePlanner.MAX_OPERATIONS + " items.");
                    }
                    boolean strict = Tools.optBool(a, "strict", false);
                    String defNs = Tools.optString(a, "namespace");
                    String defDef = Tools.optString(a, "definition_property");
                    String defType = Tools.optString(a, "property_type");
                    String verify = ApplyVerify.normalizeMode(Tools.optString(a, "verify"));
                    if (preview) {
                        if (!ApplyVerify.MODE_NONE.equals(verify)) {
                            return Tools.error("preview=true cannot be combined with verify; isolated "
                                    + "change-set preflight replaces post-apply verification.");
                        }
                        Map<String, Object> replay = new LinkedHashMap<>();
                        replay.put("properties", specs);
                        replay.put("strict", strict);
                        if (defNs != null) {
                            replay.put("namespace", defNs);
                        }
                        if (defDef != null) {
                            replay.put("definition_property", defDef);
                        }
                        if (defType != null) {
                            replay.put("property_type", defType);
                        }
                        return ChangeSetTools.previewPrepared(ctx, a,
                                mm -> planProperties(mm, specs, strict, defNs, defDef, defType),
                                ChangeSetTools.KIND_CREATE_PROPERTIES, replay);
                    }
                    String summary = "create_properties (" + specs.size() + ")"
                            + (ApplyVerify.MODE_NONE.equals(verify) ? "" : " (verify=" + verify + ")");
                    if (ApplyVerify.MODE_NONE.equals(verify)) {
                        return WriteTools.write(ctx, summary, mm -> asResult(
                                createPropertiesBatch(mm, specs, strict, defNs, defDef, defType)));
                    }
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    if (timeout <= 0) {
                        timeout = 60_000;
                    }
                    return ApplyVerify.verifiedApply(ctx, verify, timeout, summary, "create_properties",
                            mm -> createPropertiesBatch(mm, specs, strict, defNs, defDef, defType));
                });
        tools.tool("deprecate_entity",
                (ex, req) -> {
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
                });
        tools.tool("move_class",
                (ex, req) -> {
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
                });
    }

    // ================================================================== change builders (Protégé-free)

    /**
     * The axioms for a curated term: parent SubClassOf axioms, an EquivalentClasses axiom for each
     * defined-class expression, a definition annotation, and the extra annotation suite. Pure OWL API.
     */
    static List<OWLOntologyChange> termAxioms(OWLDataFactory df, OWLOntology ont, OWLClass cls,
            List<OWLClassExpression> parents, List<OWLClassExpression> equivalents,
            OWLAnnotationProperty defProp, String defText, String defLang, Set<OWLAnnotation> extra) {
        CurationChanges changes = new CurationChanges();
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
        CurationChanges changes = new CurationChanges();
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
        CurationChanges changes = new CurationChanges();
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
        if (changes instanceof CurationChanges recording) {
            recording.addIntended(ont, ax);
            return;
        }
        if (!ont.containsAxiom(ax)) {
            changes.add(new AddAxiom(ont, ax));
        }
    }

    /**
     * Change list that additionally records the INTENDED resolution sequence — every axiom a spec
     * resolved to, BEFORE the already-asserted filter drops it from the change list. A curation
     * rebase compares this sequence, not the state-dependent filtered changes: an axiom that a
     * concurrent commit already asserted must rebase as a no-op (mirroring the operations planner's
     * pre-simulation signature), and injected declarations must not perturb positions. Only the
     * batch builders use this type; the direct create_* paths keep plain lists and are unaffected.
     */
    static final class CurationChanges extends ArrayList<OWLOntologyChange> {
        private static final long serialVersionUID = 1L;
        private final List<String> resolution = new ArrayList<>();

        /** Record the intended axiom, then append the add only when the ontology lacks it. */
        void addIntended(OWLOntology ont, OWLAxiom ax) {
            resolution.add("add" + ChangePlanner.RESOLVED_SEPARATOR + ax);
            if (!ont.containsAxiom(ax)) {
                super.add(new AddAxiom(ont, ax));
            }
        }

        @Override
        public boolean add(OWLOntologyChange change) {
            record(change);
            return super.add(change);
        }

        @Override
        public boolean addAll(java.util.Collection<? extends OWLOntologyChange> other) {
            if (other instanceof CurationChanges nested) {
                // The nested builder already recorded its intended sequence, including entries the
                // filter dropped from its change list — merge the record, copy the changes as-is.
                resolution.addAll(nested.resolution);
                boolean changed = false;
                for (OWLOntologyChange change : other) {
                    changed |= super.add(change);
                }
                return changed;
            }
            boolean changed = false;
            for (OWLOntologyChange change : other) {
                changed |= add(change);
            }
            return changed;
        }

        private void record(OWLOntologyChange change) {
            if (change.isAxiomChange()) {
                resolution.add((change.isAddAxiom() ? "add" : "remove")
                        + ChangePlanner.RESOLVED_SEPARATOR + change.getAxiom());
            }
        }

        List<String> resolution() {
            return List.copyOf(resolution);
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

    /** The batch cores return a payload map, or a decline {@link CallToolResult}; wrap the former. */
    @SuppressWarnings("unchecked")
    private static CallToolResult asResult(Object batchOutcome) {
        return batchOutcome instanceof CallToolResult
                ? (CallToolResult) batchOutcome
                : Tools.ok((Map<String, Object>) batchOutcome);
    }

    /**
     * The create_terms batch body: build every term's changes (atomic — any malformed item aborts
     * the whole batch), then commit as ONE transaction. Returns the payload map, or a decline
     * {@link CallToolResult} (strict refusal). Split out of the handler so the verify=report|rollback
     * path can inject it into {@link ApplyVerify#verifiedApply} unchanged.
     */
    static Object createTermsBatch(OWLModelManager mm, List<Map<String, Object>> termSpecs,
            boolean strict, String defaultNamespace, String defaultDefProp) {
        BuiltBatch batch = buildTermsBatch(mm, termSpecs, defaultNamespace, defaultDefProp);
        return applyBatchCurationData(mm, mm.getActiveOntology(), new ArrayList<>(batch.changes), strict,
                batch.createdSet, batch.created);
    }

    static ChangePlanner.Plan planTerms(OWLModelManager mm, List<Map<String, Object>> termSpecs,
            boolean strict, String defaultNamespace, String defaultDefProp) {
        return planBatchCuration(mm, buildTermsBatch(mm, termSpecs, defaultNamespace, defaultDefProp),
                strict, "terms");
    }

    private static BuiltBatch buildTermsBatch(OWLModelManager mm, List<Map<String, Object>> termSpecs,
            String defaultNamespace, String defaultDefProp) {
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLOntology ont = mm.getActiveOntology();
        CurationChanges changes = new CurationChanges();
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
        return new BuiltBatch(changes, createdSet, created, changes.resolution());
    }

    /** The create_properties batch body; see {@link #createTermsBatch}. */
    static Object createPropertiesBatch(OWLModelManager mm, List<Map<String, Object>> specs,
            boolean strict, String defNs, String defDef, String defType) {
        BuiltBatch batch = buildPropertiesBatch(mm, specs, defNs, defDef, defType);
        return applyBatchCurationData(mm, mm.getActiveOntology(), new ArrayList<>(batch.changes), strict,
                batch.createdSet, batch.created);
    }

    static ChangePlanner.Plan planProperties(OWLModelManager mm, List<Map<String, Object>> specs,
            boolean strict, String defNs, String defDef, String defType) {
        return planBatchCuration(mm, buildPropertiesBatch(mm, specs, defNs, defDef, defType), strict,
                "properties");
    }

    private static BuiltBatch buildPropertiesBatch(OWLModelManager mm, List<Map<String, Object>> specs,
            String defNs, String defDef, String defType) {
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLOntology ont = mm.getActiveOntology();
        CurationChanges changes = new CurationChanges();
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
        return new BuiltBatch(changes, createdSet, created, changes.resolution());
    }

    private static ChangePlanner.Plan planBatchCuration(OWLModelManager mm, BuiltBatch batch,
            boolean strict, String kind) {
        OWLOntology ontology = mm.getActiveOntology();
        List<OWLOntologyChange> changes = new ArrayList<>(batch.changes);
        Set<OWLEntity> minted = new LinkedHashSet<>();
        for (OWLOntologyChange change : changes) {
            if (change instanceof AddAxiom) {
                minted.addAll(PreviewTools.newEntities(ontology.getImportsClosure(),
                        ((AddAxiom) change).getAxiom()));
            }
        }
        minted.removeAll(batch.createdSet);
        if (strict && !minted.isEmpty()) {
            throw new ToolArgException("Refusing to preview (strict): the operand(s) "
                    + EntityRendering.renderMinted(mm, minted)
                    + " would be minted as new empty entities.");
        }
        WriteTools.declareMinted(mm.getOWLDataFactory(), ontology, minted, changes);
        WriteTools.declareUsedAnnotationProperties(mm.getOWLDataFactory(), ontology, changes);
        // The rebase signature comes from the builder's intended-resolution record, NOT this change
        // list: the injected declarations above and the already-asserted filter are ontology-state
        // artifacts, and signing them would conflict a rebase on unrelated concurrent edits.
        return ChangePlanner.prepared(mm, changes, batch.created, kind, batch.resolution);
    }


    private record BuiltBatch(List<OWLOntologyChange> changes, Set<OWLEntity> createdSet,
            List<? extends OWLEntity> created, List<String> resolution) { }

    /**
     * Apply a whole batch of curated terms as ONE transaction. Mirrors {@link #applyCuration} but for many
     * created entities: the minted-operand set is every entity the batch's adds introduce minus the terms
     * the batch itself creates (so a term referencing an earlier same-batch term BY FULL IRI is not a
     * false strict violation). With {@code strict} and a non-empty remainder, nothing is applied.
     */
    static CallToolResult applyBatchCuration(OWLModelManager mm, OWLOntology ont,
            List<OWLOntologyChange> changes, boolean strict, Set<OWLEntity> createdSet,
            List<? extends OWLEntity> created) {
        return asResult(applyBatchCurationData(mm, ont, changes, strict, createdSet, created));
    }

    /**
     * {@link #applyBatchCuration} with the outcome left raw for {@link ApplyVerify}: the payload
     * map on success, or the strict-refusal {@link CallToolResult} (nothing applied) verbatim.
     */
    static Object applyBatchCurationData(OWLModelManager mm, OWLOntology ont,
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
        // The same FINAL-SET simulation the preview planner runs: a change duplicated within the
        // batch, already asserted in the target ontology, or cancelled by a later opposite change
        // is a no-op, and reporting it as applied would overstate the delta (OWL set semantics hide
        // the surplus applies in the ontology itself).
        Set<OWLAxiom> initial = new LinkedHashSet<>(ont.getAxioms());
        Set<OWLAxiom> simulated = new LinkedHashSet<>(initial);
        List<OWLOntologyChange> effective = new ArrayList<>();
        int axiomChanges = 0;
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom) {
                axiomChanges++;
                simulated.add(ch.getAxiom());
            } else if (ch instanceof RemoveAxiom) {
                axiomChanges++;
                simulated.remove(ch.getAxiom());
            } else {
                effective.add(ch);
            }
        }
        Set<OWLAxiom> adds = new LinkedHashSet<>(simulated);
        adds.removeAll(initial);
        Set<OWLAxiom> removes = new LinkedHashSet<>(initial);
        removes.removeAll(simulated);
        for (OWLAxiom ax : removes) {
            effective.add(new RemoveAxiom(ont, ax));
        }
        for (OWLAxiom ax : adds) {
            effective.add(new AddAxiom(ont, ax));
        }
        int noOps = axiomChanges - adds.size() - removes.size();
        // Capture which batch entities pre-date the batch BEFORE the apply mutates the signature.
        Set<OWLEntity> preexisting = new LinkedHashSet<>();
        for (OWLEntity c : created) {
            for (OWLOntology o : closure) {
                if (o.containsEntityInSignature(c)) {
                    preexisting.add(c);
                    break;
                }
            }
        }
        if (!effective.isEmpty()) {
            mm.applyChanges(effective);   // one broadcast → one Protégé undo entry for the whole batch
        }
        List<Map<String, Object>> createdJson = new ArrayList<>();
        for (OWLEntity c : created) {
            Map<String, Object> row = Tools.entityJson(mm, c);
            if (preexisting.contains(c)) {
                row.put("already_existed", true);
            }
            createdJson.add(row);
        }
        Tools.Json result = Tools.json()
                .put("created", createdJson)
                .put("count", created.size())
                .put("applied", effective.size());
        if (noOps > 0) {
            result.put("no_ops", noOps);
        }
        if (!minted.isEmpty()) {
            result.put("new_entities", Tools.entityList(mm, minted, Integer.MAX_VALUE));
        }
        return result.map();
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
