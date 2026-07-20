package io.github.hakjuoh.protege_mcp.tools;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.core.qc.PolicyGovernanceService;

/** Source-compatible plugin adapter for the shared policy-governance engine. */
final class PolicyGovernance {

    private PolicyGovernance() {
    }

    record Waiver(String ruleId, String focusIri, String reason, String owner, LocalDate expires) {
        boolean active(LocalDate today) {
            return expires == null || !expires.isBefore(today);
        }

        Map<String, Object> json() {
            return shared().json();
        }

        PolicyGovernanceService.Waiver shared() {
            return new PolicyGovernanceService.Waiver(ruleId, focusIri, reason, owner, expires);
        }
    }

    record Rules(List<IRI> labelProperties, Set<String> labelLanguages,
            boolean onePreferredPerLanguage, List<IRI> definitionProperties,
            boolean definitionRequired, Set<String> definitionLanguages, IRI statusProperty,
            Set<String> allowedStatuses, Set<String> deprecatedStatuses,
            List<IRI> replacedByProperties, boolean requireReplacement, List<Waiver> waivers) {

        Rules {
            PolicyGovernanceService.Rules normalized = new PolicyGovernanceService.Rules(
                    labelProperties, labelLanguages, onePreferredPerLanguage,
                    definitionProperties, definitionRequired, definitionLanguages,
                    statusProperty, allowedStatuses, deprecatedStatuses,
                    replacedByProperties, requireReplacement,
                    waivers.stream().map(Waiver::shared).toList());
            labelProperties = normalized.labelProperties();
            labelLanguages = normalized.labelLanguages();
            definitionProperties = normalized.definitionProperties();
            definitionLanguages = normalized.definitionLanguages();
            allowedStatuses = normalized.allowedStatuses();
            deprecatedStatuses = normalized.deprecatedStatuses();
            replacedByProperties = normalized.replacedByProperties();
            waivers = List.copyOf(waivers);
        }

        static Rules empty() {
            return new Rules(List.of(), Set.of(), false, List.of(), false, Set.of(), null,
                    Set.of(), Set.of(), List.of(), true, List.of());
        }

        boolean emptyRules() {
            return shared().emptyRules();
        }

        PolicyGovernanceService.Rules shared() {
            return new PolicyGovernanceService.Rules(labelProperties, labelLanguages,
                    onePreferredPerLanguage, definitionProperties, definitionRequired,
                    definitionLanguages, statusProperty, allowedStatuses, deprecatedStatuses,
                    replacedByProperties, requireReplacement,
                    waivers.stream().map(Waiver::shared).toList());
        }
    }

    static List<Map<String, Object>> checks(OWLOntology active, Set<OWLOntology> closure,
            Rules rules, LocalDate today, int limit) {
        return PolicyGovernanceService.checks(active, closure,
                rules == null ? null : rules.shared(), today, limit);
    }
}
