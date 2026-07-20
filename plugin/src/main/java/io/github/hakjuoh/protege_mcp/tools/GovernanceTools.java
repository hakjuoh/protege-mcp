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
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;
import org.semanticweb.owlapi.profiles.Profiles;
import org.semanticweb.owlapi.profiles.violations.UndeclaredEntityViolation;
import org.semanticweb.owlapi.search.EntitySearcher;

import io.github.hakjuoh.protege_mcp.core.impact.SyntacticImpactService;

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
                (ex, req) -> {
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
                                p1.profileSnapshot, p1.ownedAxioms, p1.ownedAnnotations, limit);
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
                });
    }

    // ================================================================== phase 1 (model thread)

    /** Everything phase 1 gathers: the rendered non-profile findings, the profile snapshot, and notes. */
    private static final class Phase1 {
        /** Non-profile findings, already rendered to JSON on the EDT (their renderer is EDT-only). */
        final List<Map<String, Object>> checks;
        final OWLOntology profileSnapshot;
        /** Axioms the audited scope owns — lets the profile check attribute violations to owned vs imports. */
        final Set<OWLAxiom> ownedAxioms;
        /** The scope's ontology-header annotations — attributes the axiomless header violations. */
        final Set<OWLAnnotation> ownedAnnotations;
        final List<String> notes;

        Phase1(List<Map<String, Object>> checks, OWLOntology profileSnapshot, Set<OWLAxiom> ownedAxioms,
                Set<OWLAnnotation> ownedAnnotations, List<String> notes) {
            this.checks = checks;
            this.profileSnapshot = profileSnapshot;
            this.ownedAxioms = ownedAxioms;
            this.ownedAnnotations = ownedAnnotations;
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
        OWLOntology snapshot = profile != null
                ? flatten(active.getOntologyID(), active.getImportsClosure()) : null;
        Set<OWLAxiom> owned = profile != null ? axiomsOf(scope) : Collections.emptySet();
        Set<OWLAnnotation> ownedAnnotations = profile != null
                ? annotationsOf(scope) : Collections.emptySet();
        return new Phase1(rendered, snapshot, owned, ownedAnnotations, notes);
    }

    /**
     * Policy-QC entry point for the non-profile governance checks. The caller already owns the profile
     * stage, so this returns only IRI, required-annotation, and import-layering rows, fully rendered on the
     * model thread.
     */
    static List<Map<String, Object>> policyChecks(OWLModelManager mm, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            boolean checkOwnership, int limit) {
        return phase1(mm, false, iriPattern, requiredNamespaces, requiredAnnotations,
                checkOwnership, null, limit).checks;
    }

    /** Non-profile governance checks over an explicitly captured, isolated imports closure. */
    static List<Map<String, Object>> policyChecks(OWLModelManager mm, OWLOntology active,
            Set<OWLOntology> closure, Pattern iriPattern, List<String> requiredNamespaces,
            List<String> requiredAnnotations, boolean checkOwnership, int limit) {
        return policyChecks(mm, active, closure, iriPattern, requiredNamespaces, requiredAnnotations,
                checkOwnership, limit, null);
    }

    /**
     * As above but, when {@code gatingIdentityOut} is non-null, also collects each gating finding's
     * STABLE per-offender identities (qualified by check id) for verified-apply attribution. The
     * rendered maps are unchanged, so this never affects the public governance output.
     */
    static List<Map<String, Object>> policyChecks(OWLModelManager mm, OWLOntology active,
            Set<OWLOntology> closure, Pattern iriPattern, List<String> requiredNamespaces,
            List<String> requiredAnnotations, boolean checkOwnership, int limit,
            Set<String> gatingIdentityOut) {
        Set<OWLOntology> scope = Collections.singleton(active);
        ValidationTools.Signature sig = ValidationTools.Signature.of(scope, closure);
        List<GovFinding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (iriPattern != null || !requiredNamespaces.isEmpty()) {
            findings.add(iriPolicy(mm, sig, iriPattern, requiredNamespaces));
        }
        if (!requiredAnnotations.isEmpty()) {
            findings.add(requiredAnnotations(mm, scope, sig, requiredAnnotations, notes));
        }
        if (checkOwnership) {
            findings.add(ownership(mm, active, closure));
        }
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (GovFinding finding : findings) {
            rendered.add(finding.toJson(limit));
            if (gatingIdentityOut != null && finding.gates() && finding.count() > 0) {
                for (String identity : finding.stableIdentities()) {
                    gatingIdentityOut.add(finding.id + "|" + identity);
                }
            }
        }
        return rendered;
    }

    /** The union of the axioms of every ontology in {@code scope} (the audited scope's owned axioms). */
    private static Set<OWLAxiom> axiomsOf(Set<OWLOntology> scope) {
        Set<OWLAxiom> out = new HashSet<>();
        for (OWLOntology o : scope) {
            out.addAll(o.getAxioms());
        }
        return out;
    }

    /** The union of the ontology-header annotations of every ontology in {@code scope}. */
    private static Set<OWLAnnotation> annotationsOf(Set<OWLOntology> scope) {
        Set<OWLAnnotation> out = new HashSet<>();
        for (OWLOntology o : scope) {
            out.addAll(o.getAnnotations());
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
        return ownership(mm, active, active.getImportsClosure());
    }

    private static GovFinding ownership(OWLModelManager mm, OWLOntology active,
            Set<OWLOntology> closure) {
        GovFinding f = new GovFinding(mm, "import_layering", "warning",
                "The active module asserts logical axioms about IMPORTED terms",
                "Keep upstream terms unchanged: assert such axioms in the module that owns the term, or "
                        + "reference the imported term only in the object position (e.g. as a superclass "
                        + "or range) rather than re-axiomatising it.");
        Set<OWLEntity> foreign = foreignDeclaredEntities(active, closure);
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
        return foreignDeclaredEntities(active, active.getImportsClosure());
    }

    static Set<OWLEntity> foreignDeclaredEntities(OWLOntology active, Set<OWLOntology> closure) {
        return SyntacticImpactService.foreignDeclaredEntities(active, closure);
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
        return SyntacticImpactService.subjectEntities(ax);
    }

    // ================================================================== phase 2 (off the EDT)

    /**
     * Run the OWL 2 profile check on {@code snapshot} (a flattened imports closure) and render the
     * violations. Pure OWL API — no Protégé types — so it is safe to run off the model thread and is
     * unit-tested directly.
     */
    static Map<String, Object> profileCheck(Profiles profile, String profileName, OWLOntology snapshot,
            int limit) {
        // Back-compat overload: with no owned sets, treat the whole snapshot as owned — the historical
        // whole-closure behaviour, still exercised by unit tests over import-less ontologies.
        return profileCheck(profile, profileName, snapshot, snapshot.getAxioms(),
                snapshot.getAnnotations(), limit);
    }

    /**
     * Run the OWL 2 profile check on {@code snapshot} (a flattened imports closure) and render the
     * violations, PARTITIONED into those the audited scope OWNS ({@code ownedAxioms} and, for the
     * axiomless ontology-annotation violations, {@code ownedAnnotations}) versus those inherited from
     * imported ontologies. Profile conformance is a closure property, so the check must see the whole
     * closure; but a module author cares whether THEIR axioms leave the profile — an imported non-DL
     * upstream (e.g. BFO's undeclared-annotation-property axioms) must not drown out, or fail, their own
     * status. So {@code count}/{@code examples}/{@code owned_in_profile} report the OWNED violations (the
     * actionable ones), while the inherited ones are surfaced only as {@code imported_violations} context.
     * Pure OWL API — safe off the model thread, unit-tested directly.
     */
    static Map<String, Object> profileCheck(Profiles profile, String profileName, OWLOntology snapshot,
            Set<OWLAxiom> ownedAxioms, Set<OWLAnnotation> ownedAnnotations, int limit) {
        return profileCheck(profile, profileName, snapshot, ownedAxioms, ownedAnnotations, limit, null);
    }

    /**
     * As {@link #profileCheck(Profiles, String, OWLOntology, Set, Set, int)} but, when
     * {@code identityOut} is non-null, also collects the owned-violation identity strings (the same
     * ones behind {@code identity_digest}) so verified-apply attribution can set-diff them. The
     * returned map is byte-for-byte unchanged, so this never affects the standalone profile output.
     */
    static Map<String, Object> profileCheck(Profiles profile, String profileName, OWLOntology snapshot,
            Set<OWLAxiom> ownedAxioms, Set<OWLAnnotation> ownedAnnotations, int limit,
            Set<String> identityOut) {
        OWLProfileReport report = profile.checkOntology(snapshot);
        Set<OWLEntity> ownedHeaderSignature = new HashSet<>();
        for (OWLAnnotation annotation : ownedAnnotations) {
            ownedHeaderSignature.addAll(annotation.getSignature());
        }
        List<OWLProfileViolation> ownedViolations = new ArrayList<>();
        int importedCount = 0;
        for (OWLProfileViolation v : report.getViolations()) {
            OWLAxiom ax = violationAxiomOrNull(v);
            boolean owned = ax != null ? ownedAxioms.contains(ax)
                    : axiomlessIsOwned(v, ownedHeaderSignature);
            if (owned) {
                ownedViolations.add(v);
            } else {
                importedCount++;   // inherited from an import's axioms or its ontology header
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
        m.put("identity_digest", FindingIdentity.digest(ownedViolations.stream()
                .map(violation -> "profile\u0000" + violation.getClass().getName() + "\u0000" + violation)
                .toList()));
        if (identityOut != null) {
            // A consistent per-violation identity for attribution set-diff (need not match the public
            // digest's encoding — only stability across the changed/baseline runs matters).
            for (OWLProfileViolation violation : ownedViolations) {
                identityOut.add(violation.getClass().getName() + "|" + violation);
            }
        }
        if (importedCount > 0) {
            m.put("imported_violations", importedCount); // inherited from imports (context, not gated)
        }
        m.put("suggestion", ownedInProfile
                ? (report.isInProfile() ? "In profile."
                        : "The audited scope's own axioms and header are in profile; the " + importedCount
                                + " remaining violation(s) are inherited from imported ontologies "
                                + "(their axioms or their ontology headers).")
                : "Remove or rewrite the offending owned axioms or header annotations, or target a "
                        + "less restrictive profile.");
        m.put("examples", samples);
        if (ownedViolations.size() > samples.size()) {
            m.put("truncated", ownedViolations.size() - samples.size());
        }
        return m;
    }

    /**
     * OWLAPI 4 annotates the profile-violation axiom as nullable, but its public getter verifies
     * non-null and throws for ontology-level violations — a reserved/relative ontology IRI, or an
     * undeclared entity used inside an ontology-header annotation. Those must be attributed by
     * {@link #axiomlessIsOwned} instead of aborting the QC gate.
     */
    private static OWLAxiom violationAxiomOrNull(OWLProfileViolation violation) {
        try {
            return violation.getAxiom();
        } catch (IllegalStateException noAxiom) {
            return null;
        }
    }

    /**
     * Attribute a violation that carries no axiom. An undeclared-entity violation here comes from an
     * ontology-header annotation; the violation does not expose which header, so it is owned when the
     * audited scope's own header references that entity (per-entity attribution — a shared undeclared
     * term also used by an import's header still charges the scope, which can repair every instance by
     * declaring it). Every other axiomless kind is about the ontology ID, and both snapshot builders
     * carry only the audited root's ID — so it is owned, and unknown future kinds fail closed into the
     * owned gate rather than silently passing as "imported".
     */
    private static boolean axiomlessIsOwned(OWLProfileViolation violation,
            Set<OWLEntity> ownedHeaderSignature) {
        if (violation instanceof UndeclaredEntityViolation undeclared) {
            return ownedHeaderSignature.contains(undeclared.getEntity());
        }
        return true;
    }

    /**
     * Merge an imports closure into a single private ontology (conformance-equivalent, no imports) via
     * the shared QC snapshot builder, keeping the audited root's {@link OWLOntologyID} and every
     * member's ontology-header annotations — an anonymous axiom-only merge would silently drop the
     * ontology-ID and header profile violations from the audit.
     */
    static OWLOntology flatten(OWLOntologyID activeId, Set<OWLOntology> closure) {
        return SparqlTools.buildSnapshotOntology(OwlManagers.create(), activeId, closure);
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

        /** Whether this finding gates (its severity contributes to the stage verdict). */
        boolean gates() {
            return "error".equals(severity) || "warning".equals(severity) || "warn".equals(severity);
        }

        /**
         * The STABLE per-offender identities (entity IRIs and axioms) behind {@code identity_digest},
         * excluding the mutable detail strings. Verified-apply attribution set-diffs these so a batch
         * that removes standing governance violations while adding a fresh one (total count DOWN) is
         * still caught, and a pure removal is not misattributed.
         */
        Set<String> stableIdentities() {
            Set<String> out = new LinkedHashSet<>();
            entities.forEach(entity -> out.add(FindingIdentity.entity(entity)));
            axioms.forEach(axiom -> out.add(FindingIdentity.axiom(axiom)));
            return out;
        }

        Map<String, Object> toJson(int limit) {
            List<String> identities = new ArrayList<>();
            entities.forEach(entity -> identities.add(FindingIdentity.entity(entity)));
            axioms.forEach(axiom -> identities.add(FindingIdentity.axiom(axiom)));
            details.forEach(detail -> identities.add("detail\u0000" + detail));
            Tools.Json json = Tools.json()
                    .put("id", id)
                    .put("severity", severity)
                    .put("title", title)
                    .put("count", count())
                    .put("identity_digest", FindingIdentity.digest(identities))
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
