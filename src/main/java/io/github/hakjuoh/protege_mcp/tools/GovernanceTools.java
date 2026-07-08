package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLNaryPropertyAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLUnaryPropertyAxiom;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;
import org.semanticweb.owlapi.profiles.Profiles;
import org.semanticweb.owlapi.search.EntitySearcher;

/**
 * Project-governance validation: the layer above {@code validate_ontology}'s generic modelling-quality
 * checks. Where {@code validate_ontology} asks "is this well-formed?", {@code validate_governance} asks
 * "does this obey <em>this project's</em> conventions?" — a configurable policy rather than universal
 * smells. It checks:
 *
 * <ul>
 *   <li><b>OWL 2 profile conformance</b> (DL / EL / QL / RL) via the OWL API's own profile checker, so a
 *       project that must stay in a decidable/tractable profile catches a stray axiom that leaves it.</li>
 *   <li><b>IRI policy</b> — every entity the ontology owns must live under a required namespace and/or
 *       match a required IRI pattern (guards against terms minted in the wrong namespace).</li>
 *   <li><b>Required annotation suite</b> — every owned class/property must carry a configured set of
 *       annotation properties (e.g. a label AND a definition AND a project curation-status).</li>
 *   <li><b>Module ownership / import layering</b> — the active module must not assert logical (TBox)
 *       axioms whose subject is an imported term, i.e. it must not silently re-axiomatise upstream
 *       BFO/IOF classes it only means to reference.</li>
 * </ul>
 *
 * <p>The expensive profile computation runs <em>off the EDT</em> on a private, flattened
 * imports-closure snapshot (a flat merge is conformance-equivalent to checking the live closure);
 * taking that snapshot copies the closure's axioms and so still runs on the model thread, but the
 * conformance analysis — the costly part — does not. The per-entity checks (rendered to JSON on the
 * model thread, whose renderer is EDT-only) also run there; {@code timeout_ms} bounds the caller's
 * wait (it cannot interrupt an already-running check) and {@code limit} samples the offenders. Every
 * check is a pure function over plain OWL API types, so each is unit-tested on a hand-built ontology.
 */
public final class GovernanceTools {

    private GovernanceTools() {
    }

    /** Default OWL 2 profile checked when the caller does not name one. */
    static final String DEFAULT_PROFILE = "DL";

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("validate_governance",
                "Audit the active ontology against PROJECT GOVERNANCE rules (complements "
                        + "validate_ontology's generic quality checks and run_reasoner's logic checks). "
                        + "Checks: (1) owl_profile — OWL 2 profile conformance (default DL; or EL/QL/RL; "
                        + "'none'/'Full' skips), reporting the axioms that leave the profile; (4) "
                        + "check_ownership (default true) — the active module must not assert logical "
                        + "axioms about IMPORTED terms (import-layering / module ownership) — both run BY "
                        + "DEFAULT; (2) required_namespaces / iri_pattern — every owned entity's IRI must "
                        + "start with one of the namespaces and/or match the regex; and (3) "
                        + "required_annotations — every owned class/property must carry each listed "
                        + "annotation property (use 'label' and 'definition' for rdfs:label / any "
                        + "definition property) — are opt-in. Owned = declared in the audited scope, not "
                        + "purely imported. Set include_imports=true to audit the whole imports closure "
                        + "as owned.",
                Tools.schema()
                        .bool("include_imports", "Treat the whole imports closure as owned and audit it "
                                + "too (default false: only the active ontology's own terms).")
                        .str("owl_profile", "OWL 2 profile to check conformance against: DL (default), "
                                + "EL, QL, RL. Pass 'none' or 'Full' to skip the profile check.")
                        .strArray("required_namespaces", "Owned entity IRIs must start with one of these "
                                + "namespace prefixes (optional; e.g. your ontology's term namespace).")
                        .str("iri_pattern", "Owned entity IRIs must match this Java regular expression "
                                + "(optional; applied to the full IRI).")
                        .strArray("required_annotations", "Annotation properties every owned "
                                + "class/property must carry: an IRI/CURIE/name, or the specials 'label' "
                                + "(rdfs:label) and 'definition' (rdfs:comment / skos:definition / any "
                                + "*definition property). Optional.")
                        .bool("check_ownership", "Flag logical axioms in the active ontology whose "
                                + "subject is an imported (upstream) term — an import-layering violation. "
                                + "Default true.")
                        .integer("limit", "Max sample offenders/details per check (default 25).")
                        .integer("timeout_ms", "Time budget in ms before the call returns a timeout "
                                + "error (default 60000). The checks run on the model thread and are not "
                                + "interrupted mid-run, so this bounds the caller's wait, not the work.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    boolean includeImports = Tools.optBool(a, "include_imports", false);
                    boolean checkOwnership = Tools.optBool(a, "check_ownership", true);
                    List<String> requiredNamespaces = Tools.stringList(a, "required_namespaces");
                    List<String> requiredAnnotations = Tools.stringList(a, "required_annotations");
                    String iriPatternRaw = Tools.optString(a, "iri_pattern");
                    int limit = Tools.optInt(a, "limit", 25);
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    if (timeout <= 0) {
                        timeout = 60_000;
                    }

                    String profileName = normalizeProfile(Tools.optString(a, "owl_profile"));
                    Profiles profile = profileFor(profileName);

                    final Pattern iriPattern;
                    if (iriPatternRaw != null) {
                        try {
                            iriPattern = Pattern.compile(iriPatternRaw);
                        } catch (PatternSyntaxException e) {
                            return Tools.error("iri_pattern is not a valid regular expression: "
                                    + e.getMessage());
                        }
                    } else {
                        iriPattern = null;
                    }

                    final int timeoutMs = timeout;
                    // Phase 1 (model thread, bounded): the per-entity/axiom checks (they need Protégé
                    // rendering) plus a flat imports-closure snapshot for the off-EDT profile check.
                    Phase1 p1 = ctx.access().compute(mm -> phase1(mm, includeImports, iriPattern,
                            requiredNamespaces, requiredAnnotations, checkOwnership, profile, limit), timeoutMs);

                    List<Map<String, Object>> checks = new ArrayList<>();
                    int totalViolations = 0;
                    // Phase 2 (off the EDT): the heavy OWL 2 profile check on the private snapshot.
                    if (profile != null && p1.profileSnapshot != null) {
                        Map<String, Object> profileCheck = profileCheck(profile, profileName,
                                p1.profileSnapshot, p1.ownedAxioms, limit);
                        totalViolations += (int) profileCheck.get("count");
                        checks.add(profileCheck);
                    }
                    // The per-entity/axiom findings were already rendered on the EDT in phase 1 (they
                    // need Protégé's renderer, which is only safe there) — just collect them here.
                    for (Map<String, Object> check : p1.checks) {
                        totalViolations += ((Number) check.get("count")).intValue();
                        checks.add(check);
                    }

                    Tools.Json json = Tools.json()
                            .put("scope", includeImports ? "imports_closure" : "active")
                            .put("profile", profile == null ? (profileName == null ? "none" : profileName)
                                    : profileName)
                            .put("total_violations", totalViolations)
                            .put("checks", checks);
                    if (!p1.notes.isEmpty()) {
                        json.put("notes", p1.notes);
                    }
                    return json
                            .put("note", "Governance rules are project policy, not logical consistency: "
                                    + "run run_reasoner for satisfiability and validate_ontology for "
                                    + "generic modelling quality.")
                            .result();
                }));
    }

    // ================================================================== phase 1 (model thread)

    /** Everything phase 1 gathers: the rendered non-profile findings, the profile snapshot, and notes. */
    private static final class Phase1 {
        /** Non-profile findings, already rendered to JSON on the EDT (their renderer is EDT-only). */
        final List<Map<String, Object>> checks;
        final OWLOntology profileSnapshot;
        /** Axioms the audited scope owns — lets the profile check attribute violations to owned vs imports. */
        final Set<OWLAxiom> ownedAxioms;
        final List<String> notes;

        Phase1(List<Map<String, Object>> checks, OWLOntology profileSnapshot, Set<OWLAxiom> ownedAxioms,
                List<String> notes) {
            this.checks = checks;
            this.profileSnapshot = profileSnapshot;
            this.ownedAxioms = ownedAxioms;
            this.notes = notes;
        }
    }

    private static Phase1 phase1(OWLModelManager mm, boolean includeImports, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations, boolean checkOwnership,
            Profiles profile, int limit) {
        OWLOntology active = mm.getActiveOntology();
        Set<OWLOntology> scope = includeImports
                ? active.getImportsClosure()
                : Collections.singleton(active);
        ValidationTools.Signature sig = ValidationTools.Signature.of(scope);
        List<GovFinding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (iriPattern != null || !requiredNamespaces.isEmpty()) {
            findings.add(iriPolicy(mm, sig, iriPattern, requiredNamespaces));
        }
        if (!requiredAnnotations.isEmpty()) {
            findings.add(requiredAnnotations(mm, scope, sig, requiredAnnotations, notes));
        }
        if (checkOwnership) {
            findings.add(ownership(mm, active));
        }

        // Render each finding to JSON HERE, on the EDT — toJson calls mm.getRendering, which reads
        // Protégé's non-thread-safe renderer/short-form caches and must not run once compute() returns.
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (GovFinding f : findings) {
            rendered.add(f.toJson(limit));
        }
        OWLOntology snapshot = profile != null ? flatten(active.getImportsClosure()) : null;
        Set<OWLAxiom> owned = profile != null ? axiomsOf(scope) : Collections.emptySet();
        return new Phase1(rendered, snapshot, owned, notes);
    }

    /** The union of the axioms of every ontology in {@code scope} (the audited scope's owned axioms). */
    private static Set<OWLAxiom> axiomsOf(Set<OWLOntology> scope) {
        Set<OWLAxiom> out = new HashSet<>();
        for (OWLOntology o : scope) {
            out.addAll(o.getAxioms());
        }
        return out;
    }

    // ---------------------------------------------------------------- IRI policy

    /** Owned entities whose IRI is outside every required namespace or fails the required pattern. */
    private static GovFinding iriPolicy(OWLModelManager mm, ValidationTools.Signature sig,
            Pattern iriPattern, List<String> requiredNamespaces) {
        StringBuilder rule = new StringBuilder("Owned entity IRIs must ");
        if (!requiredNamespaces.isEmpty()) {
            rule.append("start with one of: ").append(String.join(", ", requiredNamespaces));
        }
        if (iriPattern != null) {
            rule.append(requiredNamespaces.isEmpty() ? "" : " and ")
                    .append("match /").append(iriPattern.pattern()).append('/');
        }
        GovFinding f = new GovFinding(mm, "iri_policy", "warning", rule.toString(),
                "Mint the term in the project namespace (create_class/create_entity with 'namespace'), "
                        + "or rename_entity to a conforming IRI.");
        for (OWLEntity e : iriPolicyTargets(sig)) {
            String iri = e.getIRI().toString();
            boolean nsOk = requiredNamespaces.isEmpty() || startsWithAny(iri, requiredNamespaces);
            boolean patternOk = iriPattern == null || iriPattern.matcher(iri).matches();
            if (!nsOk || !patternOk) {
                f.entities.add(e);
            }
        }
        return f;
    }

    /** Owned named entities an IRI policy applies to: classes, properties, individuals. */
    static Set<OWLEntity> iriPolicyTargets(ValidationTools.Signature sig) {
        Set<OWLEntity> out = new LinkedHashSet<>();
        out.addAll(sig.labelable);   // owned classes, object/data properties, individuals
        out.addAll(sig.definable);   // + owned annotation properties
        return out;
    }

    private static boolean startsWithAny(String iri, List<String> prefixes) {
        for (String p : prefixes) {
            if (p != null && !p.isEmpty() && iri.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- required annotation suite

    /** Owned classes/properties missing one or more of the required annotation properties. */
    private static GovFinding requiredAnnotations(OWLModelManager mm, Set<OWLOntology> scope,
            ValidationTools.Signature sig, List<String> refs, List<String> notes) {
        // Resolve each requested annotation to a concrete predicate (or a special matcher), collecting
        // config problems as notes rather than failing the whole audit.
        List<Requirement> requirements = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String ref : refs) {
            Requirement r = requirement(mm, ref, notes);
            if (r != null) {
                requirements.add(r);
                labels.add(r.label);
            }
        }
        GovFinding f = new GovFinding(mm, "required_annotations", "warning",
                "Every owned class/property must carry: " + String.join(", ", labels),
                "Add the missing annotation(s) with add_annotation / create_term (definition), or "
                        + "narrow the requirement.");
        if (requirements.isEmpty()) {
            return f;
        }
        for (OWLEntity e : sig.definable) {
            List<OWLAnnotationProperty> present = annotationPropertiesOf(e, scope);
            List<String> missing = new ArrayList<>();
            for (Requirement r : requirements) {
                if (!r.satisfiedBy(present)) {
                    missing.add(r.label);
                }
            }
            if (!missing.isEmpty()) {
                f.entities.add(e);
                f.details.add(mm.getRendering(e) + " missing " + String.join(", ", missing));
            }
        }
        return f;
    }

    /** The annotation properties actually asserted on {@code e} within {@code scope}. */
    private static List<OWLAnnotationProperty> annotationPropertiesOf(OWLEntity e, Set<OWLOntology> scope) {
        List<OWLAnnotationProperty> out = new ArrayList<>();
        for (OWLOntology o : scope) {
            for (OWLAnnotation ann : EntitySearcher.getAnnotations(e, o)) {
                out.add(ann.getProperty());
            }
        }
        return out;
    }

    /** A resolved annotation requirement: an exact property, or a 'label'/'definition' family matcher. */
    private static final class Requirement {
        final String label;
        final OWLAnnotationProperty exact;   // null for the special matchers
        final boolean labelFamily;
        final boolean definitionFamily;

        private Requirement(String label, OWLAnnotationProperty exact, boolean labelFamily,
                boolean definitionFamily) {
            this.label = label;
            this.exact = exact;
            this.labelFamily = labelFamily;
            this.definitionFamily = definitionFamily;
        }

        boolean satisfiedBy(List<OWLAnnotationProperty> present) {
            for (OWLAnnotationProperty p : present) {
                if (labelFamily && p.isLabel()) {
                    return true;
                }
                if (definitionFamily && ValidationTools.isDefinitionProperty(p)) {
                    return true;
                }
                if (exact != null && p.equals(exact)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Requirement requirement(OWLModelManager mm, String ref, List<String> notes) {
        String lower = ref.toLowerCase(Locale.ROOT);
        if (lower.equals("label") || lower.equals("rdfs:label")) {
            return new Requirement("label (rdfs:label)", null, true, false);
        }
        if (lower.equals("definition")) {
            return new Requirement("definition", null, false, true);
        }
        try {
            OWLAnnotationProperty p = Tools.resolveAnnotationProperty(mm, ref);
            return new Requirement(mm.getRendering(p), p, false, false);
        } catch (RuntimeException e) {
            notes.add("required_annotations: could not resolve '" + ref + "' to an annotation property "
                    + "— skipped (pass a full IRI, a known CURIE, or 'label'/'definition').");
            return null;
        }
    }

    // ---------------------------------------------------------------- module ownership / import layering

    /** Logical axioms asserted in the active ontology whose subject is an imported (upstream) term. */
    private static GovFinding ownership(OWLModelManager mm, OWLOntology active) {
        GovFinding f = new GovFinding(mm, "import_layering", "warning",
                "The active module asserts logical axioms about IMPORTED terms",
                "Keep upstream terms unchanged: assert such axioms in the module that owns the term, or "
                        + "reference the imported term only in the object position (e.g. as a superclass "
                        + "or range) rather than re-axiomatising it.");
        Set<OWLEntity> foreign = foreignDeclaredEntities(active);
        if (foreign.isEmpty()) {
            return f;
        }
        for (OWLAxiom ax : active.getLogicalAxioms()) {
            for (OWLEntity subject : subjectEntities(ax)) {
                if (foreign.contains(subject)) {
                    f.axioms.add(ax);
                    f.details.add(mm.getRendering(subject) + " ← " + Tools.renderAxiom(mm, ax));
                    break;
                }
            }
        }
        return f;
    }

    /** Entities declared in an imported ontology but NOT declared in {@code active} (upstream-owned). */
    static Set<OWLEntity> foreignDeclaredEntities(OWLOntology active) {
        Set<OWLEntity> activeDeclared = new HashSet<>();
        for (OWLDeclarationAxiom ax : active.getAxioms(AxiomType.DECLARATION)) {
            activeDeclared.add(ax.getEntity());
        }
        Set<OWLEntity> foreign = new LinkedHashSet<>();
        for (OWLOntology o : active.getImportsClosure()) {
            if (o.equals(active)) {
                continue;
            }
            for (OWLDeclarationAxiom ax : o.getAxioms(AxiomType.DECLARATION)) {
                if (!activeDeclared.contains(ax.getEntity())) {
                    foreign.add(ax.getEntity());
                }
            }
        }
        return foreign;
    }

    /**
     * The named entities a logical axiom "characterises" (its subject position) for import-layering
     * purposes: the subclass of a SubClassOf, the members of an equivalent/disjoint-classes axiom, the
     * property of a domain/range/characteristic/sub-property axiom, etc. ABox axioms (class/property
     * assertions, same/different individuals) are deliberately excluded — asserting facts about local
     * individuals that merely reference an upstream class is normal, not a layering violation. Axiom
     * shapes not enumerated here contribute no subjects (conservative: no false positives).
     */
    static Set<OWLEntity> subjectEntities(OWLAxiom ax) {
        Set<OWLEntity> out = new LinkedHashSet<>();
        if (ax instanceof OWLSubClassOfAxiom) {
            addNamedClass(out, ((OWLSubClassOfAxiom) ax).getSubClass());
        } else if (ax instanceof OWLNaryClassAxiom) {
            // equivalent_classes / disjoint_classes: every named operand is a subject.
            for (OWLClassExpression ce : ((OWLNaryClassAxiom) ax).getClassExpressions()) {
                addNamedClass(out, ce);
            }
        } else if (ax instanceof OWLDisjointUnionAxiom) {
            out.add(((OWLDisjointUnionAxiom) ax).getOWLClass());
        } else if (ax instanceof OWLHasKeyAxiom) {
            addNamedClass(out, ((OWLHasKeyAxiom) ax).getClassExpression());
        } else if (ax instanceof OWLPropertyDomainAxiom) {
            addNamedProperty(out, ((OWLPropertyDomainAxiom<?>) ax).getProperty());
        } else if (ax instanceof OWLPropertyRangeAxiom) {
            addNamedProperty(out, ((OWLPropertyRangeAxiom<?, ?>) ax).getProperty());
        } else if (ax instanceof OWLUnaryPropertyAxiom) {
            // property characteristics: functional / transitive / symmetric / ...
            addNamedProperty(out, ((OWLUnaryPropertyAxiom<?>) ax).getProperty());
        } else if (ax instanceof OWLSubPropertyChainOfAxiom) {
            // sub_property_chain_of: the axiom characterises (re-axiomatises) the SUPER property.
            addNamedProperty(out, ((OWLSubPropertyChainOfAxiom) ax).getSuperProperty());
        } else if (ax instanceof OWLSubObjectPropertyOfAxiom) {
            addNamedProperty(out, ((OWLSubObjectPropertyOfAxiom) ax).getSubProperty());
        } else if (ax instanceof OWLSubPropertyAxiom) {
            // sub_data_property_of (sub_annotation_property_of is a non-logical annotation axiom).
            addNamedProperty(out, ((OWLSubPropertyAxiom<?>) ax).getSubProperty());
        } else if (ax instanceof OWLNaryPropertyAxiom) {
            // equivalent/disjoint object|data properties.
            for (Object p : ((OWLNaryPropertyAxiom<?>) ax).getProperties()) {
                addNamedProperty(out, p);
            }
        } else if (ax instanceof OWLInverseObjectPropertiesAxiom) {
            OWLInverseObjectPropertiesAxiom inv = (OWLInverseObjectPropertiesAxiom) ax;
            addNamedProperty(out, inv.getFirstProperty());
            addNamedProperty(out, inv.getSecondProperty());
        }
        return out;
    }

    private static void addNamedClass(Set<OWLEntity> out, OWLClassExpression ce) {
        if (ce != null && !ce.isAnonymous()) {
            out.add(ce.asOWLClass());
        }
    }

    private static void addNamedProperty(Set<OWLEntity> out, Object p) {
        if (p instanceof OWLObjectPropertyExpression) {
            OWLObjectPropertyExpression ope = (OWLObjectPropertyExpression) p;
            if (!ope.isAnonymous()) {
                out.add(ope.asOWLObjectProperty());
            }
        } else if (p instanceof OWLDataPropertyExpression) {
            OWLDataPropertyExpression dpe = (OWLDataPropertyExpression) p;
            if (!dpe.isAnonymous()) {
                out.add(dpe.asOWLDataProperty());
            }
        }
    }

    // ================================================================== phase 2 (off the EDT)

    /**
     * Run the OWL 2 profile check on {@code snapshot} (a flattened imports closure) and render the
     * violations. Pure OWL API — no Protégé types — so it is safe to run off the model thread and is
     * unit-tested directly.
     */
    static Map<String, Object> profileCheck(Profiles profile, String profileName, OWLOntology snapshot,
            int limit) {
        // Back-compat overload: with no owned-axiom set, treat every snapshot axiom as owned — the historical
        // whole-closure behaviour, still exercised by unit tests over import-less ontologies.
        return profileCheck(profile, profileName, snapshot, snapshot.getAxioms(), limit);
    }

    /**
     * Run the OWL 2 profile check on {@code snapshot} (a flattened imports closure) and render the
     * violations, PARTITIONED into those the audited scope OWNS ({@code ownedAxioms}) versus those inherited
     * from imported ontologies. Profile conformance is a closure property, so the check must see the whole
     * closure; but a module author cares whether THEIR axioms leave the profile — an imported non-DL
     * upstream (e.g. BFO's undeclared-annotation-property axioms) must not drown out, or fail, their own
     * status. So {@code count}/{@code examples}/{@code owned_in_profile} report the OWNED violations (the
     * actionable ones), while the inherited ones are surfaced only as {@code imported_violations} context.
     * A violation with no attributable axiom is counted as inherited (never fails the owned gate). Pure OWL
     * API — safe off the model thread, unit-tested directly.
     */
    static Map<String, Object> profileCheck(Profiles profile, String profileName, OWLOntology snapshot,
            Set<OWLAxiom> ownedAxioms, int limit) {
        OWLProfileReport report = profile.checkOntology(snapshot);
        List<OWLProfileViolation> ownedViolations = new ArrayList<>();
        int importedCount = 0;
        for (OWLProfileViolation v : report.getViolations()) {
            OWLAxiom ax = v.getAxiom();
            if (ax != null && ownedAxioms.contains(ax)) {
                ownedViolations.add(v);
            } else {
                importedCount++;   // inherited from an import, or not attributable to a single axiom
            }
        }
        List<String> samples = new ArrayList<>();
        for (OWLProfileViolation v : ownedViolations) {
            if (samples.size() >= Math.max(0, limit)) {
                break;
            }
            samples.add(String.valueOf(v));
        }
        boolean ownedInProfile = ownedViolations.isEmpty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "owl_profile");
        m.put("severity", "error");
        m.put("title", "Conformance to OWL 2 " + profileName);
        m.put("in_profile", report.isInProfile());     // the whole audited closure conforms
        m.put("owned_in_profile", ownedInProfile);      // the scope's OWN axioms conform — the actionable bit
        m.put("count", ownedViolations.size());         // owned violations (respects the audited scope)
        if (importedCount > 0) {
            m.put("imported_violations", importedCount); // inherited from imports (context, not gated)
        }
        m.put("suggestion", ownedInProfile
                ? (report.isInProfile() ? "In profile."
                        : "The audited scope's own axioms are in profile; the " + importedCount
                                + " remaining violation(s) are inherited from imported ontologies.")
                : "Remove or rewrite the offending owned axioms, or target a less restrictive profile.");
        m.put("examples", samples);
        if (ownedViolations.size() > samples.size()) {
            m.put("truncated", ownedViolations.size() - samples.size());
        }
        return m;
    }

    /** Merge an imports closure into a single private ontology (conformance-equivalent, no imports). */
    static OWLOntology flatten(Set<OWLOntology> closure) {
        Set<OWLAxiom> axioms = new HashSet<>();
        for (OWLOntology o : closure) {
            axioms.addAll(o.getAxioms());
        }
        OWLOntologyManager priv = OwlManagers.create();
        try {
            return priv.createOntology(axioms);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not prepare a governance workspace: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    // ================================================================== profile name mapping

    /** Normalise an owl_profile argument to DL/EL/QL/RL/Full, defaulting to {@link #DEFAULT_PROFILE}. */
    static String normalizeProfile(String raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_PROFILE;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replace("OWL2_", "").replace("OWL2", "")
                .replace("OWL 2 ", "").replace("-", "").replace(" ", "");
        switch (s) {
            case "DL":
                return "DL";
            case "EL":
                return "EL";
            case "QL":
                return "QL";
            case "RL":
                return "RL";
            case "NONE":
            case "FULL":
                return "none";
            default:
                throw new ToolArgException("Unknown owl_profile '" + raw + "'. Use DL, EL, QL, RL, or "
                        + "'none' to skip.");
        }
    }

    /** Map a normalised profile name to the OWL API {@link Profiles} enum ({@code null} = skip). */
    static Profiles profileFor(String normalized) {
        if (normalized == null) {
            return null;
        }
        switch (normalized) {
            case "DL":
                return Profiles.OWL2_DL;
            case "EL":
                return Profiles.OWL2_EL;
            case "QL":
                return Profiles.OWL2_QL;
            case "RL":
                return Profiles.OWL2_RL;
            default:
                return null;
        }
    }

    // ================================================================== finding type

    /**
     * One governance check's result: offending entities and/or axioms plus human-readable detail lines,
     * rendered to JSON with Protégé's renderer. Carries {@code mm} so the entity/axiom renderings match
     * the rest of the tool output; the profile check renders itself separately (off the EDT).
     */
    static final class GovFinding {
        private final OWLModelManager mm;
        final String id;
        final String severity;
        final String title;
        final String suggestion;
        final Set<OWLEntity> entities = new LinkedHashSet<>();
        final Set<OWLAxiom> axioms = new LinkedHashSet<>();
        final List<String> details = new ArrayList<>();

        GovFinding(OWLModelManager mm, String id, String severity, String title, String suggestion) {
            this.mm = mm;
            this.id = id;
            this.severity = severity;
            this.title = title;
            this.suggestion = suggestion;
        }

        int count() {
            return entities.size() + axioms.size();
        }

        Map<String, Object> toJson(int limit) {
            Tools.Json json = Tools.json()
                    .put("id", id)
                    .put("severity", severity)
                    .put("title", title)
                    .put("count", count())
                    .put("suggestion", suggestion);
            if (!entities.isEmpty()) {
                json.put("examples", Tools.entityList(mm, entities, limit));
            }
            if (!axioms.isEmpty()) {
                json.put("axioms", Tools.axiomList(mm, axioms, limit));
            }
            if (!details.isEmpty()) {
                json.put("details", details.size() > limit
                        ? new ArrayList<>(details.subList(0, limit)) : details);
            }
            return json.map();
        }
    }
}
