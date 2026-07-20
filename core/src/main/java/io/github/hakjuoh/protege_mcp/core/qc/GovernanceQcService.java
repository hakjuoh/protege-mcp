package io.github.hakjuoh.protege_mcp.core.qc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.search.EntitySearcher;

import io.github.hakjuoh.protege_mcp.core.impact.SyntacticImpactService;
import io.github.hakjuoh.protege_mcp.core.owl.OwlDocumentSignature;

/** Shared IRI, annotation, import-layering, and policy governance stage. */
public final class GovernanceQcService {

    private static final String SKOS_DEFINITION =
            "http://www.w3.org/2004/02/skos/core#definition";
    private static final String[] RESERVED = {
            "http://www.w3.org/2002/07/owl#",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#",
            "http://www.w3.org/2001/XMLSchema#"
    };
    private static final String[] TOOL_INTERNAL = {
            "https://hakjuoh.github.io/protege-mcp/"
    };
    private static final String[] WELL_KNOWN = {
            "http://purl.org/dc/terms/", "http://purl.org/dc/elements/1.1/",
            "http://www.w3.org/2004/02/skos/core#", "http://xmlns.com/foaf/0.1/",
            "http://www.w3.org/ns/prov#",
            "http://www.geneontology.org/formats/oboInOwl#",
            "http://purl.obolibrary.org/obo/IAO_"
    };

    private GovernanceQcService() {
    }

    public record Result(QcStageExecution execution, Set<String> gatingIdentities) {
        public Result {
            gatingIdentities = Set.copyOf(gatingIdentities);
        }
    }

    /** Adapter-owned presentation and legacy annotation-property grounding. */
    public interface Presentation {
        String renderEntity(OWLEntity entity);
        String renderAxiom(OWLAxiom axiom);
        OWLAnnotationProperty resolveAnnotationProperty(String reference);
        Map<String, Object> entityList(Collection<? extends OWLEntity> entities, int limit);
        Map<String, Object> axiomList(Collection<? extends OWLAxiom> axioms, int limit);

        static Presentation canonical() {
            return CanonicalPresentation.INSTANCE;
        }
    }

    public static Result evaluate(OWLOntology active, Set<OWLOntology> closure,
            Pattern iriPattern, List<String> requiredNamespaces,
            List<String> requiredAnnotations, boolean checkOwnership,
            PolicyGovernanceService.Rules policyRules, LocalDate today,
            List<Map<String, Object>> projectChecks, int limit, Presentation presentation) {
        if (active == null || closure == null || requiredNamespaces == null
                || requiredAnnotations == null || today == null || projectChecks == null
                || presentation == null) {
            throw new IllegalArgumentException("governance inputs must not be null");
        }
        if (!closure.contains(active)) {
            throw new IllegalArgumentException("closure must contain the active ontology");
        }
        Set<String> identities = new LinkedHashSet<>();
        List<Map<String, Object>> checks = intrinsicChecks(active, closure, iriPattern,
                requiredNamespaces, requiredAnnotations, checkOwnership, limit,
                presentation, identities);
        checks.addAll(PolicyGovernanceService.checks(active, closure,
                policyRules == null ? PolicyGovernanceService.Rules.empty() : policyRules,
                today, limit));
        checks.addAll(projectChecks);

        List<Map<String, Object>> sanitized = new ArrayList<>();
        int warnings = 0;
        int errors = 0;
        for (Map<String, Object> check : checks) {
            Map<String, Object> copy = new LinkedHashMap<>(check);
            ModuleGovernanceService.drainAttributionIdentities(copy, identities);
            int count = number(copy.get("count"));
            String severity = String.valueOf(copy.get("severity"));
            if ("error".equals(severity)) errors += count;
            else if ("warning".equals(severity) || "warn".equals(severity)) warnings += count;
            sanitized.add(copy);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("violations", warnings + errors);
        summary.put("warnings", warnings);
        summary.put("errors", errors);
        summary.put("checks", sanitized);
        QcStageVerdict verdict = errors > 0 ? QcStageVerdict.FAIL
                : warnings > 0 ? QcStageVerdict.WARNING : QcStageVerdict.PASS;
        return new Result(new QcStageExecution("governance", verdict, null, summary), identities);
    }

    private static List<Map<String, Object>> intrinsicChecks(OWLOntology active,
            Set<OWLOntology> closure, Pattern iriPattern, List<String> requiredNamespaces,
            List<String> requiredAnnotations, boolean checkOwnership, int limit,
            Presentation presentation, Set<String> gatingIdentities) {
        Set<OWLOntology> scope = Set.of(active);
        Signature signature = Signature.of(scope, closure);
        List<GovFinding> findings = new ArrayList<>();
        if (iriPattern != null || !requiredNamespaces.isEmpty()) {
            findings.add(iriPolicy(signature, iriPattern, requiredNamespaces, presentation));
        }
        if (!requiredAnnotations.isEmpty()) {
            findings.add(requiredAnnotations(scope, signature, requiredAnnotations, presentation));
        }
        if (checkOwnership) findings.add(ownership(active, closure, presentation));
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (GovFinding finding : findings) {
            rendered.add(finding.toJson(limit));
            if (finding.gates() && finding.count() > 0) {
                for (String identity : finding.stableIdentities()) {
                    gatingIdentities.add(finding.id + "|" + identity);
                }
            }
        }
        return rendered;
    }

    private static GovFinding iriPolicy(Signature signature, Pattern iriPattern,
            List<String> requiredNamespaces, Presentation presentation) {
        StringBuilder rule = new StringBuilder("Owned entity IRIs must ");
        if (!requiredNamespaces.isEmpty()) {
            rule.append("start with one of: ").append(String.join(", ", requiredNamespaces));
        }
        if (iriPattern != null) {
            rule.append(requiredNamespaces.isEmpty() ? "" : " and ")
                    .append("match /").append(iriPattern.pattern()).append('/');
        }
        GovFinding finding = new GovFinding(presentation, "iri_policy", "warning",
                rule.toString(),
                "Mint the term in the project namespace (create_class/create_entity with 'namespace'), "
                        + "or rename_entity to a conforming IRI.");
        Set<OWLEntity> targets = new LinkedHashSet<>(signature.labelable);
        targets.addAll(signature.definable);
        for (OWLEntity entity : targets) {
            String iri = entity.getIRI().toString();
            boolean namespaceOk = requiredNamespaces.isEmpty()
                    || startsWithAny(iri, requiredNamespaces);
            boolean patternOk = iriPattern == null || iriPattern.matcher(iri).matches();
            if (!namespaceOk || !patternOk) finding.entities.add(entity);
        }
        return finding;
    }

    private static boolean startsWithAny(String iri, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && iri.startsWith(prefix)) return true;
        }
        return false;
    }

    private static GovFinding requiredAnnotations(Set<OWLOntology> scope, Signature signature,
            List<String> references, Presentation presentation) {
        List<Requirement> requirements = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String reference : references) {
            Requirement requirement = requirement(presentation, reference);
            if (requirement != null) {
                requirements.add(requirement);
                labels.add(requirement.label);
            }
        }
        GovFinding finding = new GovFinding(presentation, "required_annotations", "warning",
                "Every owned class/property must carry: " + String.join(", ", labels),
                "Add the missing annotation(s) with add_annotation / create_term (definition), "
                        + "or narrow the requirement.");
        if (requirements.isEmpty()) return finding;
        for (OWLEntity entity : signature.definable) {
            List<OWLAnnotationProperty> present = annotationPropertiesOf(entity, scope);
            List<String> missing = new ArrayList<>();
            for (Requirement requirement : requirements) {
                if (!requirement.satisfiedBy(present)) missing.add(requirement.label);
            }
            if (!missing.isEmpty()) {
                finding.entities.add(entity);
                finding.details.add(presentation.renderEntity(entity) + " missing "
                        + String.join(", ", missing));
            }
        }
        return finding;
    }

    private static List<OWLAnnotationProperty> annotationPropertiesOf(OWLEntity entity,
            Set<OWLOntology> scope) {
        List<OWLAnnotationProperty> out = new ArrayList<>();
        for (OWLOntology ontology : scope) {
            for (OWLAnnotation annotation : EntitySearcher.getAnnotations(entity, ontology)) {
                out.add(annotation.getProperty());
            }
        }
        return out;
    }

    private static Requirement requirement(Presentation presentation, String reference) {
        String lower = reference.toLowerCase(Locale.ROOT);
        if (lower.equals("label") || lower.equals("rdfs:label")) {
            return new Requirement("label (rdfs:label)", null, true, false);
        }
        if (lower.equals("definition")) {
            return new Requirement("definition", null, false, true);
        }
        try {
            OWLAnnotationProperty property = presentation.resolveAnnotationProperty(reference);
            return new Requirement(presentation.renderEntity(property), property, false, false);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static GovFinding ownership(OWLOntology active, Set<OWLOntology> closure,
            Presentation presentation) {
        GovFinding finding = new GovFinding(presentation, "import_layering", "warning",
                "The active module asserts logical axioms about IMPORTED terms",
                "Keep upstream terms unchanged: assert such axioms in the module that owns the term, or "
                        + "reference the imported term only in the object position (e.g. as a superclass "
                        + "or range) rather than re-axiomatising it.");
        Set<OWLEntity> foreign = SyntacticImpactService.foreignDeclaredEntities(active, closure);
        for (OWLAxiom axiom : active.getLogicalAxioms()) {
            for (OWLEntity subject : SyntacticImpactService.subjectEntities(axiom)) {
                if (foreign.contains(subject)) {
                    finding.axioms.add(axiom);
                    finding.details.add(presentation.renderEntity(subject) + " ← "
                            + presentation.renderAxiom(axiom));
                    break;
                }
            }
        }
        return finding;
    }

    private static boolean definitionProperty(OWLAnnotationProperty property) {
        if (property.isComment()) return true;
        if (property.getIRI().toString().equals(SKOS_DEFINITION)) return true;
        String shortForm = property.getIRI().getShortForm();
        return shortForm != null && shortForm.toLowerCase(Locale.ROOT).endsWith("definition");
    }

    private static boolean reserved(OWLEntity entity) {
        if (entity.isBuiltIn()) return true;
        String iri = entity.getIRI().toString();
        for (String prefix : RESERVED) if (iri.startsWith(prefix)) return true;
        for (String prefix : TOOL_INTERNAL) if (iri.startsWith(prefix)) return true;
        if (entity.isOWLAnnotationProperty()) {
            for (String prefix : WELL_KNOWN) if (iri.startsWith(prefix)) return true;
        }
        return false;
    }

    private static int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private record Requirement(String label, OWLAnnotationProperty exact,
            boolean labelFamily, boolean definitionFamily) {
        boolean satisfiedBy(List<OWLAnnotationProperty> present) {
            for (OWLAnnotationProperty property : present) {
                if (labelFamily && property.isLabel()) return true;
                if (definitionFamily && definitionProperty(property)) return true;
                if (exact != null && property.equals(exact)) return true;
            }
            return false;
        }
    }

    private static final class GovFinding {
        private final Presentation presentation;
        private final String id;
        private final String severity;
        private final String title;
        private final String suggestion;
        private final Set<OWLEntity> entities = new LinkedHashSet<>();
        private final Set<OWLAxiom> axioms = new LinkedHashSet<>();
        private final List<String> details = new ArrayList<>();

        GovFinding(Presentation presentation, String id, String severity, String title,
                String suggestion) {
            this.presentation = presentation;
            this.id = id;
            this.severity = severity;
            this.title = title;
            this.suggestion = suggestion;
        }

        int count() {
            return entities.size() + axioms.size();
        }

        boolean gates() {
            return "error".equals(severity) || "warning".equals(severity)
                    || "warn".equals(severity);
        }

        Set<String> stableIdentities() {
            Set<String> out = new LinkedHashSet<>();
            entities.forEach(entity -> out.add(QcFindingIdentity.entity(entity)));
            axioms.forEach(axiom -> out.add(QcFindingIdentity.axiom(axiom)));
            return out;
        }

        Map<String, Object> toJson(int limit) {
            List<String> identities = new ArrayList<>();
            entities.forEach(entity -> identities.add(QcFindingIdentity.entity(entity)));
            axioms.forEach(axiom -> identities.add(QcFindingIdentity.axiom(axiom)));
            details.forEach(detail -> identities.add("detail\u0000" + detail));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("severity", severity);
            out.put("title", title);
            out.put("count", count());
            out.put("identity_digest", QcFindingIdentity.digest(identities));
            out.put("suggestion", suggestion);
            if (!entities.isEmpty()) out.put("examples", presentation.entityList(entities, limit));
            if (!axioms.isEmpty()) out.put("axioms", presentation.axiomList(axioms, limit));
            if (!details.isEmpty()) {
                out.put("details", details.size() > limit
                        ? new ArrayList<>(details.subList(0, limit)) : details);
            }
            return out;
        }
    }

    private static final class Signature {
        private final Set<OWLEntity> all = new LinkedHashSet<>();
        private final Set<OWLEntity> labelable = new LinkedHashSet<>();
        private final Set<OWLEntity> definable = new LinkedHashSet<>();

        static Signature of(Set<OWLOntology> scope, Set<OWLOntology> closure) {
            Signature signature = new Signature();
            scope.forEach(ontology -> signature.all.addAll(OwlDocumentSignature.of(ontology)));
            Set<OWLEntity> declaredInScope = declarations(scope);
            Set<OWLEntity> declaredInClosure = declarations(closure);
            for (OWLEntity entity : signature.all) {
                if (reserved(entity)
                        || (!declaredInScope.contains(entity)
                                && declaredInClosure.contains(entity))) continue;
                if (entity.isOWLClass()) {
                    signature.labelable.add(entity);
                    signature.definable.add(entity);
                } else if (entity.isOWLObjectProperty() || entity.isOWLDataProperty()) {
                    signature.labelable.add(entity);
                    signature.definable.add(entity);
                } else if (entity.isOWLNamedIndividual()) {
                    signature.labelable.add(entity);
                } else if (entity.isOWLAnnotationProperty()) {
                    signature.definable.add(entity);
                }
            }
            return signature;
        }

        private static Set<OWLEntity> declarations(Set<OWLOntology> ontologies) {
            Set<OWLEntity> declared = new LinkedHashSet<>();
            for (OWLOntology ontology : ontologies) {
                for (OWLDeclarationAxiom axiom : ontology.getAxioms(AxiomType.DECLARATION)) {
                    declared.add(axiom.getEntity());
                }
            }
            return declared;
        }
    }

    private enum CanonicalPresentation implements Presentation {
        INSTANCE;

        @Override
        public String renderEntity(OWLEntity entity) {
            return entity.getIRI().toString();
        }

        @Override
        public String renderAxiom(OWLAxiom axiom) {
            return axiom.toString();
        }

        @Override
        public OWLAnnotationProperty resolveAnnotationProperty(String reference) {
            return OWLManager.getOWLDataFactory().getOWLAnnotationProperty(IRI.create(reference));
        }

        @Override
        public Map<String, Object> entityList(Collection<? extends OWLEntity> entities, int limit) {
            List<OWLEntity> sorted = new ArrayList<>(entities);
            sorted.sort(Comparator.comparing((OWLEntity entity) ->
                            renderEntity(entity).toLowerCase(Locale.ROOT))
                    .thenComparing(entity -> entity.getIRI().toString()));
            int max = Math.max(0, limit);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = 0; index < sorted.size() && index < max; index++) {
                OWLEntity entity = sorted.get(index);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("iri", entity.getIRI().toString());
                item.put("display", renderEntity(entity));
                item.put("type", entity.getEntityType().getName());
                items.add(item);
            }
            return boundedList(entities.size(), items);
        }

        @Override
        public Map<String, Object> axiomList(Collection<? extends OWLAxiom> axioms, int limit) {
            List<OWLAxiom> sorted = new ArrayList<>(axioms);
            Map<OWLAxiom, String> rendering = new HashMap<>();
            sorted.forEach(axiom -> rendering.put(axiom, renderAxiom(axiom)));
            sorted.sort(Comparator.comparing((OWLAxiom axiom) -> rendering.get(axiom))
                    .thenComparing(Comparator.naturalOrder()));
            int max = Math.max(0, limit);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = 0; index < sorted.size() && index < max; index++) {
                OWLAxiom axiom = sorted.get(index);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("axiom_type", axiom.getAxiomType().getName());
                item.put("rendering", rendering.get(axiom));
                items.add(item);
            }
            return boundedList(axioms.size(), items);
        }

        private static Map<String, Object> boundedList(int count, List<Map<String, Object>> items) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", count);
            out.put("items", items);
            if (count > items.size()) out.put("truncated", count - items.size());
            return out;
        }
    }
}
