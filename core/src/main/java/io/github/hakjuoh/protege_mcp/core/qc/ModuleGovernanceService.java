package io.github.hakjuoh.protege_mcp.core.qc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.policy.ModuleDocumentInspector;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Policy module ownership and loaded-import graph governance shared by every adapter. */
public final class ModuleGovernanceService {

    public static final String ATTRIBUTION_KEY = PolicyGovernanceService.ATTRIBUTION_KEY;

    private ModuleGovernanceService() {
    }

    /** Inspect policy module documents at their policy-resolved paths. */
    public static List<Map<String, Object>> moduleChecks(ProjectPolicy policy, int limit) {
        List<Path> paths = policy == null ? List.of()
                : policy.assets().getOrDefault("modules", List.of());
        return moduleChecks(policy, paths, limit);
    }

    /** Inspect policy modules using snapshot-captured paths in matching declaration order. */
    public static List<Map<String, Object>> moduleChecks(ProjectPolicy policy,
            List<Path> modulePaths, int limit) {
        if (policy == null || !policy.loaded()) return List.of();
        if (modulePaths == null) {
            throw new IllegalArgumentException("modulePaths must not be null");
        }
        List<Map<String, Object>> modules = objects(policy.effective().get("modules"));
        List<Path> paths = List.copyOf(modulePaths);
        if (modules.isEmpty()) return List.of();
        List<Map<String, Object>> failures = new ArrayList<>();
        if (modules.size() != paths.size()) {
            failures.add(failure(null, null, "policy declares " + modules.size()
                    + " module(s) but " + paths.size() + " module file(s) resolved"));
            return List.of(inspectionFailedCheck(failures, limit));
        }

        List<Module> inspected = new ArrayList<>();
        List<String> moduleIris = new ArrayList<>();
        Map<String, Set<Integer>> owners = new LinkedHashMap<>();
        for (int index = 0; index < modules.size(); index++) {
            Map<String, Object> configured = modules.get(index);
            moduleIris.add(string(configured, "ontology_iri"));
            List<String> namespaces = strings(configured.get("owned_namespaces"));
            for (String namespace : namespaces) {
                owners.computeIfAbsent(namespace, ignored -> new LinkedHashSet<>()).add(index);
            }
            try {
                inspected.add(new Module(index, moduleIris.get(index), paths.get(index), namespaces,
                        ModuleDocumentInspector.inspect(paths.get(index)).definedEntityIris()));
            } catch (IOException error) {
                failures.add(failure(moduleIris.get(index), paths.get(index), error.getMessage()));
            }
        }

        List<Map<String, Object>> violations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Module module : inspected) {
            for (String entity : module.definedEntities) {
                String namespace = mostSpecificOwnedNamespace(entity, owners.keySet());
                if (namespace == null || owners.get(namespace).contains(module.index)) continue;
                String key = module.index + "\u0000" + entity + "\u0000" + namespace;
                if (!seen.add(key)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("entity_iri", entity);
                row.put("declaring_module", module.ontologyIri);
                row.put("declaring_path", module.path.toString());
                row.put("owned_namespace", namespace);
                row.put("owning_modules", owners.get(namespace).stream()
                        .map(moduleIris::get).toList());
                violations.add(row);
            }
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        if (!failures.isEmpty()) checks.add(inspectionFailedCheck(failures, limit));
        if (!violations.isEmpty()) {
            checks.add(check("module_owned_namespace", "error", violations.size(),
                    "Entities defined in a module outside the module(s) owning their namespace",
                    "Move each definition (subject-position axioms) to a module that owns the "
                            + "namespace or correct the reviewed modules[].owned_namespaces "
                            + "declaration.", violations, limit));
        }
        return checks.isEmpty() ? List.of() : List.copyOf(checks);
    }

    /** Convert captured import-graph cycles/conflicts into governance check rows. */
    public static List<Map<String, Object>> importChecks(List<Map<String, Object>> cycles,
            List<Map<String, Object>> conflicts, int limit) {
        if (cycles == null || conflicts == null) {
            throw new IllegalArgumentException("cycles and conflicts must not be null");
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        if (!cycles.isEmpty()) {
            checks.add(check("import_cycle", "warning", cycles.size(),
                    "The loaded import graph contains a cycle",
                    "Remove cyclic owl:imports edges or document why the cycle is required.",
                    cycles, limit));
        }
        if (!conflicts.isEmpty()) {
            checks.add(check("import_identity_conflict", "error", conflicts.size(),
                    "Loaded imports have ontology/version/document identity conflicts",
                    "Pin one unambiguous ontology/version/document coordinate in the catalog and lock.",
                    conflicts, limit));
        }
        return List.copyOf(checks);
    }

    /** Remove the internal identity side channel into a stage-level set. */
    @SuppressWarnings("unchecked")
    public static void drainAttributionIdentities(Map<String, Object> check,
            java.util.Collection<String> identitiesOut) {
        Object identities = check.remove(ATTRIBUTION_KEY);
        if (identities instanceof List<?>) identitiesOut.addAll((List<String>) identities);
    }

    public static boolean ownsEntity(String namespace, String entity) {
        if (namespace.isEmpty() || !entity.startsWith(namespace)) return false;
        if (!Character.isLetterOrDigit(namespace.charAt(namespace.length() - 1))) return true;
        if (entity.length() == namespace.length()) return true;
        char boundary = entity.charAt(namespace.length());
        return boundary == '/' || boundary == '#' || boundary == ':';
    }

    public static String mostSpecificOwnedNamespace(String entity, Set<String> namespaces) {
        String best = null;
        for (String namespace : namespaces) {
            if (ownsEntity(namespace, entity)
                    && (best == null || namespace.length() > best.length())) best = namespace;
        }
        return best;
    }

    private static Map<String, Object> failure(String moduleIri, Path path, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (moduleIri != null) row.put("module", moduleIri);
        if (path != null) row.put("path", path.toString());
        row.put("error", error);
        return row;
    }

    private static Map<String, Object> inspectionFailedCheck(List<Map<String, Object>> failures,
            int limit) {
        return check("module_inspection_failed", "error", failures.size(),
                "Module governance could not inspect every policy-declared module document",
                "Restore or fix the named module file(s); module governance fails closed when a "
                        + "declared module cannot be re-inspected.", failures, limit);
    }

    private static Map<String, Object> check(String id, String severity, int count,
            String title, String suggestion, List<Map<String, Object>> identities, int limit) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("id", id);
        check.put("severity", severity);
        check.put("title", title);
        check.put("count", count);
        List<String> rowIdentities = identities.stream().map(QcFindingIdentity::object).toList();
        check.put("identity_digest", QcFindingIdentity.digest(rowIdentities));
        check.put("suggestion", suggestion);
        check.put("examples", sample(identities, limit));
        check.put(ATTRIBUTION_KEY, rowIdentities.stream()
                .map(identity -> id + "|" + identity).toList());
        return check;
    }

    private static List<Map<String, Object>> sample(List<Map<String, Object>> rows, int limit) {
        int end = Math.min(rows.size(), Math.max(0, limit));
        return List.copyOf(rows.subList(0, end));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value
                : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List<?> ? (List<String>) value : Collections.emptyList();
    }

    private static String string(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item instanceof String ? (String) item : null;
    }

    private record Module(int index, String ontologyIri, Path path, List<String> namespaces,
            Set<String> definedEntities) { }
}
