package io.github.hakjuoh.protege_mcp.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.core.qc.ModuleGovernanceService;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Source-compatible plugin adapter for shared module/import governance. */
final class ModulePolicyGovernance {

    static final String ATTRIBUTION_KEY = ModuleGovernanceService.ATTRIBUTION_KEY;

    private ModulePolicyGovernance() {
    }

    static void drainAttributionIdentities(Map<String, Object> check,
            java.util.Collection<String> identitiesOut) {
        ModuleGovernanceService.drainAttributionIdentities(check, identitiesOut);
    }

    static List<Map<String, Object>> moduleChecks(ProjectPolicy policy, int limit) {
        return ModuleGovernanceService.moduleChecks(policy, limit);
    }

    static boolean ownsEntity(String namespace, String entity) {
        return ModuleGovernanceService.ownsEntity(namespace, entity);
    }

    static String mostSpecificOwnedNamespace(String entity, Set<String> namespaces) {
        return ModuleGovernanceService.mostSpecificOwnedNamespace(entity, namespaces);
    }

    static List<Map<String, Object>> importChecks(ImportTools.ImportReport report, int limit) {
        if (report == null) return List.of();
        return ModuleGovernanceService.importChecks(report.cycles, report.conflicts, limit);
    }
}
