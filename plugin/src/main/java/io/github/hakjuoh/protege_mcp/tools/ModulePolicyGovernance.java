package io.github.hakjuoh.protege_mcp.tools;

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

/** Policy-v1 module namespace and loaded-import governance checks. */
final class ModulePolicyGovernance {

    private ModulePolicyGovernance() {
    }

    static List<Map<String, Object>> moduleChecks(ProjectPolicy policy, int limit) {
        if (policy == null || !policy.loaded()) return List.of();
        List<Map<String, Object>> modules = objects(policy.effective().get("modules"));
        List<Path> paths = policy.assets().getOrDefault("modules", List.of());
        if (modules.isEmpty()) return List.of();
        List<Map<String, Object>> failures = new ArrayList<>();
        if (modules.size() != paths.size()) {
            // Fail CLOSED: modules[i] can no longer be paired with its resolved file, so ownership
            // was not checked for ANY module. (A policy the loader validated resolves one file per
            // module; this arises when a module asset failed to resolve on a loaded-invalid policy.)
            failures.add(failure(null, null, "policy declares " + modules.size()
                    + " module(s) but " + paths.size() + " module file(s) resolved"));
            return List.of(inspectionFailedCheck(failures, limit));
        }

        List<Module> inspected = new ArrayList<>();
        List<String> moduleIris = new ArrayList<>();
        Map<String, Set<Integer>> owners = new LinkedHashMap<>();
        for (int i = 0; i < modules.size(); i++) {
            Map<String, Object> configured = modules.get(i);
            moduleIris.add(string(configured, "ontology_iri"));
            List<String> namespaces = strings(configured.get("owned_namespaces"));
            for (String namespace : namespaces) {
                owners.computeIfAbsent(namespace, ignored -> new LinkedHashSet<>()).add(i);
            }
            try {
                inspected.add(new Module(i, moduleIris.get(i), paths.get(i), namespaces,
                        ModuleDocumentInspector.inspect(paths.get(i)).definedEntityIris()));
            } catch (IOException e) {
                // Fail CLOSED: a module document that cannot be re-inspected here (deleted,
                // unreadable, or replaced mid-save since the policy load) means its ownership was
                // never checked; the loader treats the same condition as a policy error.
                failures.add(failure(moduleIris.get(i), paths.get(i), e.getMessage()));
            }
        }

        List<Map<String, Object>> violations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Module module : inspected) {
            for (String entity : module.definedEntities) {
                String namespace = mostSpecificOwnedNamespace(entity, owners.keySet());
                if (namespace == null || owners.get(namespace).contains(module.index)) {
                    continue;
                }
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
        if (!failures.isEmpty()) {
            checks.add(inspectionFailedCheck(failures, limit));
        }
        if (!violations.isEmpty()) {
            checks.add(check("module_owned_namespace", "error", violations.size(),
                    "Entities defined in a module outside the module(s) owning their namespace",
                    "Move each definition (subject-position axioms) to a module that owns the "
                            + "namespace or correct the reviewed modules[].owned_namespaces "
                            + "declaration.", violations, limit));
        }
        return checks.isEmpty() ? List.of() : List.copyOf(checks);
    }

    /**
     * Boundary-aware ownership match. A namespace ending in an explicit delimiter (any
     * non-alphanumeric character, e.g. '/', '#', ':', '_') states its own boundary and owns every
     * plain string extension — the OBO {@code …/obo/GO_} convention. A namespace ending
     * alphanumeric owns the exact IRI and continuations across a structural IRI separator
     * ('/', '#' or ':') only, so {@code https://ex.org/ns} owns {@code https://ex.org/ns#X} and
     * {@code https://ex.org/ns/X} but never the siblings {@code https://ex.org/ns2/X},
     * {@code https://ex.org/ns-ext/X}, {@code https://ex.org/ns.ext/X} or
     * {@code https://ex.org/ns_ext/X}.
     */
    private static boolean ownsEntity(String namespace, String entity) {
        if (namespace.isEmpty() || !entity.startsWith(namespace)) return false;
        if (!Character.isLetterOrDigit(namespace.charAt(namespace.length() - 1))) return true;
        if (entity.length() == namespace.length()) return true;
        char boundary = entity.charAt(namespace.length());
        return boundary == '/' || boundary == '#' || boundary == ':';
    }

    /**
     * Longest owning namespace wins: when an umbrella namespace and a more specific one both
     * match, ownership is attributed to the most specific declaration alone, so an umbrella
     * owner never claims a submodule's terms.
     */
    private static String mostSpecificOwnedNamespace(String entity, Set<String> namespaces) {
        String best = null;
        for (String namespace : namespaces) {
            if (ownsEntity(namespace, entity)
                    && (best == null || namespace.length() > best.length())) {
                best = namespace;
            }
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

    static List<Map<String, Object>> importChecks(ImportTools.ImportReport report, int limit) {
        if (report == null) return List.of();
        List<Map<String, Object>> checks = new ArrayList<>();
        if (!report.cycles.isEmpty()) {
            checks.add(check("import_cycle", "warning", report.cycles.size(),
                    "The loaded import graph contains a cycle",
                    "Remove cyclic owl:imports edges or document why the cycle is required.",
                    report.cycles, limit));
        }
        if (!report.conflicts.isEmpty()) {
            checks.add(check("import_identity_conflict", "error", report.conflicts.size(),
                    "Loaded imports have ontology/version/document identity conflicts",
                    "Pin one unambiguous ontology/version/document coordinate in the catalog and lock.",
                    report.conflicts, limit));
        }
        return checks;
    }

    private static Map<String, Object> check(String id, String severity, int count,
            String title, String suggestion, List<Map<String, Object>> identities, int limit) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("id", id);
        check.put("severity", severity);
        check.put("title", title);
        check.put("count", count);
        check.put("identity_digest", FindingIdentity.digest(
                identities.stream().map(FindingIdentity::object).toList()));
        check.put("suggestion", suggestion);
        check.put("examples", sample(identities, limit));
        return check;
    }

    private static List<Map<String, Object>> sample(List<Map<String, Object>> rows, int limit) {
        int end = Math.min(rows.size(), Math.max(0, limit));
        return List.copyOf(rows.subList(0, end));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : Collections.emptyList();
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
