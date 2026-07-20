package io.github.hakjuoh.protege_mcp.core.qc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/** Policy-specific annotation, lifecycle, and waiver governance over one OWLAPI snapshot. */
public final class PolicyGovernanceService {

    /** Reserved internal result key; adapters must remove it before serializing checks. */
    public static final String ATTRIBUTION_KEY = "__attribution_identities";

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^(?:todo|tbd|fixme|placeholder|n/?a|not available|to be (?:defined|determined))$|^\\$\\{.+}$");

    private PolicyGovernanceService() {
    }

    public record Waiver(String ruleId, String focusIri, String reason, String owner,
            LocalDate expires) {

        public boolean active(LocalDate today) {
            return expires == null || !expires.isBefore(today);
        }

        public Map<String, Object> json() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rule_id", ruleId);
            if (focusIri != null) out.put("focus_iri", focusIri);
            out.put("reason", reason);
            out.put("owner", owner);
            if (expires != null) out.put("expires", expires.toString());
            return out;
        }
    }

    public record Rules(List<IRI> labelProperties, Set<String> labelLanguages,
            boolean onePreferredPerLanguage, List<IRI> definitionProperties,
            boolean definitionRequired, Set<String> definitionLanguages, IRI statusProperty,
            Set<String> allowedStatuses, Set<String> deprecatedStatuses,
            List<IRI> replacedByProperties, boolean requireReplacement, List<Waiver> waivers) {

        public Rules {
            labelProperties = List.copyOf(labelProperties);
            labelLanguages = normalizedLanguages(labelLanguages);
            definitionProperties = List.copyOf(definitionProperties);
            definitionLanguages = normalizedLanguages(definitionLanguages);
            allowedStatuses = normalizedValues(allowedStatuses);
            deprecatedStatuses = normalizedValues(deprecatedStatuses);
            replacedByProperties = List.copyOf(replacedByProperties);
            waivers = List.copyOf(waivers);
        }

        public static Rules empty() {
            return new Rules(List.of(), Set.of(), false, List.of(), false, Set.of(), null,
                    Set.of(), Set.of(), List.of(), true, List.of());
        }

        public boolean emptyRules() {
            return labelLanguages.isEmpty() && !onePreferredPerLanguage && !definitionRequired
                    && definitionLanguages.isEmpty() && statusProperty == null && waivers.isEmpty();
        }
    }

    public static List<Map<String, Object>> checks(OWLOntology active,
            Set<OWLOntology> closure, Rules rules, LocalDate today, int limit) {
        if (active == null || closure == null || today == null) {
            throw new IllegalArgumentException("active, closure, and today must not be null");
        }
        if (!closure.contains(active)) {
            throw new IllegalArgumentException("closure must contain the active ontology");
        }
        if (rules == null || rules.emptyRules()) return Collections.emptyList();
        List<OWLEntity> labelTargets = declared(active, false);
        List<OWLEntity> definable = declared(active, true);
        Set<String> closureEntities = new LinkedHashSet<>();
        for (OWLOntology ontology : closure) {
            ontology.getSignature().stream().filter(entity -> !entity.isBuiltIn())
                    .map(entity -> entity.getIRI().toString()).forEach(closureEntities::add);
        }

        List<Finding> findings = new ArrayList<>();
        checkLabels(active, labelTargets, rules, findings);
        checkDefinitions(active, definable, rules, findings);
        checkLifecycle(active, definable, closureEntities, rules, findings);
        addExpiredWaivers(rules, today, findings);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Finding finding : findings) out.add(finding.json(rules.waivers, today, limit));
        return List.copyOf(out);
    }

    private static void checkLabels(OWLOntology active, List<OWLEntity> targets, Rules rules,
            List<Finding> findings) {
        if (rules.labelProperties.isEmpty()
                || (rules.labelLanguages.isEmpty() && !rules.onePreferredPerLanguage)) return;
        Finding coverage = new Finding("annotation.label.language", "warning",
                "Required label language coverage",
                "Add a configured label in every required language.");
        Finding cardinality = new Finding("annotation.label.cardinality", "warning",
                "Exactly one preferred label per language",
                "Keep exactly one value on the first configured (preferred) label property per language.");
        IRI preferred = rules.labelProperties.get(0);
        for (OWLEntity entity : targets) {
            Map<String, Integer> all = languageCounts(annotations(entity, active,
                    rules.labelProperties));
            Set<String> languages = rules.labelLanguages.isEmpty()
                    ? new LinkedHashSet<>(all.keySet()) : rules.labelLanguages;
            for (String language : languages) {
                if (all.getOrDefault(language, 0) == 0) {
                    coverage.add(entity, "missing language '" + printableLanguage(language) + "'");
                }
            }
            if (rules.onePreferredPerLanguage) {
                Map<String, Integer> preferredCounts = languageCounts(
                        annotations(entity, active, List.of(preferred)));
                for (String language : languages) {
                    int count = preferredCounts.getOrDefault(language, 0);
                    if (count != 1) cardinality.add(entity, "language '"
                            + printableLanguage(language) + "' has " + count + " preferred labels");
                }
            }
        }
        findings.add(coverage);
        if (rules.onePreferredPerLanguage) findings.add(cardinality);
    }

    private static void checkDefinitions(OWLOntology active, List<OWLEntity> targets, Rules rules,
            List<Finding> findings) {
        if (rules.definitionProperties.isEmpty()
                || (!rules.definitionRequired && rules.definitionLanguages.isEmpty())) return;
        Finding required = new Finding("annotation.definition.required", "warning",
                "Required definition", "Add a non-empty definition using a configured property.");
        Finding placeholder = new Finding("annotation.definition.placeholder", "warning",
                "Definitions must not be empty or placeholders",
                "Replace placeholder text with a definition.");
        Finding language = new Finding("annotation.definition.language", "warning",
                "Required definition language coverage",
                "Add a definition in every required language.");
        Finding datatype = new Finding("annotation.definition.datatype", "warning",
                "Definitions must be xsd:string or language-tagged literals",
                "Use a plain xsd:string literal or an rdf:langString literal.");
        for (OWLEntity entity : targets) {
            List<OWLAnnotation> values = annotations(entity, active, rules.definitionProperties);
            if (rules.definitionRequired && values.isEmpty()) required.add(entity, "missing definition");
            Map<String, Integer> languages = new LinkedHashMap<>();
            for (OWLAnnotation annotation : values) {
                OWLLiteral literal = annotation.getValue().asLiteral().orNull();
                if (literal == null) {
                    datatype.add(entity, "definition value is not a literal");
                    continue;
                }
                String text = literal.getLiteral().trim();
                if (text.isEmpty() || PLACEHOLDER.matcher(text).matches()) {
                    placeholder.add(entity, "empty/placeholder definition");
                }
                String languageTag = normalizeLanguage(literal.getLang());
                languages.merge(languageTag, 1, Integer::sum);
                IRI datatypeIri = literal.getDatatype().getIRI();
                if (!datatypeIri.equals(OWL2Datatype.XSD_STRING.getIRI())
                        && !datatypeIri.equals(OWLRDFVocabulary.RDF_LANG_STRING.getIRI())) {
                    datatype.add(entity, "unsupported datatype " + datatypeIri);
                }
            }
            for (String requiredLanguage : rules.definitionLanguages) {
                if (languages.getOrDefault(requiredLanguage, 0) == 0) {
                    language.add(entity, "missing language '"
                            + printableLanguage(requiredLanguage) + "'");
                }
            }
        }
        if (rules.definitionRequired) findings.add(required);
        findings.add(placeholder);
        findings.add(datatype);
        if (!rules.definitionLanguages.isEmpty()) findings.add(language);
    }

    private static void checkLifecycle(OWLOntology active, List<OWLEntity> targets,
            Set<String> closureEntities, Rules rules, List<Finding> findings) {
        if (rules.statusProperty == null) return;
        Finding allowed = new Finding("lifecycle.status.allowed", "error",
                "Lifecycle status must be an allowed value",
                "Set exactly one configured lifecycle status.");
        Finding cardinality = new Finding("lifecycle.status.cardinality", "error",
                "Exactly one lifecycle status is required",
                "Set exactly one lifecycle status value.");
        Finding replacementRequired = new Finding("lifecycle.replacement.required", "warning",
                "Deprecated terms require migration guidance", "Add a configured replaced_by link.");
        Finding dangling = new Finding("lifecycle.replacement.dangling", "error",
                "Replacement links must resolve in the loaded closure",
                "Load or create the replacement term.");
        for (OWLEntity entity : targets) {
            List<OWLAnnotation> statuses = annotations(entity, active, List.of(rules.statusProperty));
            if (statuses.size() != 1) cardinality.add(entity, "found " + statuses.size() + " statuses");
            boolean deprecated = false;
            for (OWLAnnotation annotation : statuses) {
                String value = annotationValue(annotation.getValue());
                String normalized = normalizeValue(value);
                if (!rules.allowedStatuses.contains(normalized)) {
                    allowed.add(entity, "unrecognized status '" + value + "'");
                }
                if (rules.deprecatedStatuses.contains(normalized)) deprecated = true;
            }
            List<OWLAnnotation> replacements = annotations(entity, active, rules.replacedByProperties);
            if (deprecated && rules.requireReplacement && replacements.isEmpty()) {
                replacementRequired.add(entity, "deprecated without replacement");
            }
            for (OWLAnnotation replacement : replacements) {
                IRI iri = replacement.getValue().asIRI().orNull();
                if (iri == null || !closureEntities.contains(iri.toString())) {
                    dangling.add(entity, "replacement does not resolve: "
                            + annotationValue(replacement.getValue()));
                }
            }
        }
        findings.add(allowed);
        findings.add(cardinality);
        if (rules.requireReplacement) findings.add(replacementRequired);
        findings.add(dangling);
    }

    private static void addExpiredWaivers(Rules rules, LocalDate today, List<Finding> findings) {
        Finding expired = new Finding("validation.waiver.expired", "warning",
                "Expired governance waivers",
                "Renew with justification or remove the waiver and fix the finding.");
        for (Waiver waiver : rules.waivers) {
            if (!waiver.active(today)) {
                expired.add(waiver.focusIri == null ? "*" : waiver.focusIri,
                        waiver.ruleId + " expired " + waiver.expires);
            }
        }
        findings.add(expired);
    }

    private static List<OWLEntity> declared(OWLOntology active, boolean definableOnly) {
        LinkedHashSet<OWLEntity> out = new LinkedHashSet<>();
        active.getAxioms(AxiomType.DECLARATION).forEach(axiom -> {
            OWLEntity entity = axiom.getEntity();
            if (entity.isBuiltIn() || entity.isOWLDatatype()) return;
            if (!definableOnly || entity.isOWLClass() || entity.isOWLObjectProperty()
                    || entity.isOWLDataProperty() || entity.isOWLAnnotationProperty()) out.add(entity);
        });
        return out.stream().sorted(Comparator.comparing(entity -> entity.getIRI().toString())).toList();
    }

    private static List<OWLAnnotation> annotations(OWLEntity entity, OWLOntology ontology,
            Collection<IRI> properties) {
        if (properties.isEmpty()) return List.of();
        Set<IRI> wanted = new LinkedHashSet<>(properties);
        List<OWLAnnotation> out = new ArrayList<>();
        EntitySearcher.getAnnotations(entity, ontology).forEach(annotation -> {
            if (wanted.contains(annotation.getProperty().getIRI())) out.add(annotation);
        });
        return out;
    }

    private static Map<String, Integer> languageCounts(List<OWLAnnotation> annotations) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (OWLAnnotation annotation : annotations) {
            OWLLiteral literal = annotation.getValue().asLiteral().orNull();
            if (literal != null) counts.merge(normalizeLanguage(literal.getLang()), 1, Integer::sum);
        }
        return counts;
    }

    private static String annotationValue(OWLAnnotationValue value) {
        OWLLiteral literal = value.asLiteral().orNull();
        if (literal != null) return literal.getLiteral();
        IRI iri = value.asIRI().orNull();
        return iri == null ? value.toString() : iri.toString();
    }

    private static String normalizeLanguage(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String printableLanguage(String language) {
        return language.isEmpty() ? "untagged" : language;
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizedLanguages(Collection<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) out.add(normalizeLanguage(value));
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> normalizedValues(Collection<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) out.add(normalizeValue(value));
        return Collections.unmodifiableSet(out);
    }

    private static final class Finding {
        private final String id;
        private final String severity;
        private final String title;
        private final String suggestion;
        private final Map<String, List<String>> offenders = new LinkedHashMap<>();

        Finding(String id, String severity, String title, String suggestion) {
            this.id = id;
            this.severity = severity;
            this.title = title;
            this.suggestion = suggestion;
        }

        void add(OWLEntity entity, String detail) {
            add(entity.getIRI().toString(), detail);
        }

        void add(String focus, String detail) {
            offenders.computeIfAbsent(focus, ignored -> new ArrayList<>()).add(detail);
        }

        Map<String, Object> json(List<Waiver> waivers, LocalDate today, int limit) {
            Map<String, List<String>> active = new LinkedHashMap<>(offenders);
            List<Map<String, Object>> applied = new ArrayList<>();
            int waived = 0;
            if (!id.equals("validation.waiver.expired")) {
                for (Waiver waiver : waivers) {
                    if (!waiver.active(today) || !id.equals(waiver.ruleId)) continue;
                    List<String> removed = new ArrayList<>();
                    if (waiver.focusIri == null) removed.addAll(active.keySet());
                    else if (active.containsKey(waiver.focusIri)) removed.add(waiver.focusIri);
                    if (removed.isEmpty()) continue;
                    removed.forEach(active::remove);
                    waived += removed.size();
                    Map<String, Object> visible = new LinkedHashMap<>(waiver.json());
                    visible.put("applied_count", removed.size());
                    applied.add(visible);
                }
            }
            List<String> focuses = new ArrayList<>(active.keySet());
            List<String> details = new ArrayList<>();
            active.forEach((focus, values) -> values.forEach(
                    value -> details.add(focus + ": " + value)));
            int bounded = Math.max(0, limit);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("severity", severity);
            out.put("title", title);
            out.put("count", focuses.size());
            List<String> identities = new ArrayList<>();
            active.forEach((focus, values) -> values.forEach(
                    value -> identities.add(focus + "\u0000" + value)));
            out.put("identity_digest", QcFindingIdentity.digest(identities));
            if (!identities.isEmpty()) {
                out.put(ATTRIBUTION_KEY, identities.stream()
                        .map(identity -> id + "|" + identity).toList());
            }
            out.put("suggestion", suggestion);
            if (!focuses.isEmpty()) {
                out.put("examples", focuses.subList(0, Math.min(bounded, focuses.size())));
            }
            if (!details.isEmpty()) {
                out.put("details", details.subList(0, Math.min(bounded, details.size())));
            }
            if (waived > 0) {
                out.put("waived_count", waived);
                out.put("waivers", applied);
            }
            return out;
        }
    }
}
