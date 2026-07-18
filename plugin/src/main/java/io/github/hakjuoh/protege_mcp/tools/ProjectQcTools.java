package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;
import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Strict policy-backed QC orchestration, shared by run_project_qc and run_qc_suite(policy_path). */
final class ProjectQcTools {

    private ProjectQcTools() {
    }

    static CallToolResult run(ToolContext ctx, Map<String, Object> arguments, boolean dedicatedTool) {
        String configured = Tools.optString(arguments, "policy_path");
        Path explicit = null;
        if (configured != null) {
            try {
                explicit = Path.of(configured);
            } catch (InvalidPathException e) {
                return Tools.ok(configurationError(ctx, null, "policy_path_invalid",
                        "Invalid policy_path '" + configured + "': " + e.getMessage()));
            }
        }

        ProjectPolicyTools.PolicyContext live = ctx.access().compute(ProjectPolicyTools::capture);
        ProjectPolicy policy = ProjectPolicyLoader.load(explicit, live.documentPath(),
                live.activeOntologyIri(), live.installedReasoners());
        if (!policy.loaded()) {
            return Tools.ok(configurationError(ctx, policy, "policy_not_found",
                    "No project policy was discovered; pass policy_path or add "
                            + ProjectPolicyLoader.DEFAULT_RELATIVE_PATH + "."));
        }
        if (live.activeOntologyIri() == null) {
            return Tools.ok(configurationError(ctx, policy, "active_ontology_anonymous",
                    "The active ontology has no ontology IRI, so root_ontology cannot be verified."));
        }
        if (!policy.valid()) {
            return Tools.ok(policyErrors(ctx, policy));
        }

        try {
            QcSuiteTools.RunConfig config = config(policy, live, arguments);
            QcSuiteTools.SuiteExecution execution = QcSuiteTools.execute(ctx, config);
            int version = ((Number) policy.effective().get("version")).intValue();
            Map<String, Object> result = QcSuiteTools.strictResult(execution, config.requiredStages,
                    config.failOn, version, policy.digest(), true);
            enforceImportIntegrity(policy, execution, result);
            result.put("project_id", policy.effective().get("project_id"));
            result.put("policy_path", policy.path().toString());
            result.put("project_root", policy.projectRoot().toString());
            result.put("resolved_assets", assetJson(policy));
            result.put("surface", dedicatedTool ? "run_project_qc" : "run_qc_suite");
            return Tools.ok(result);
        } catch (ToolArgException | IllegalArgumentException e) {
            return Tools.ok(configurationError(ctx, policy, "qc_configuration_invalid", e.getMessage()));
        }
    }

    static QcSuiteTools.RunConfig config(ProjectPolicy policy,
            ProjectPolicyTools.PolicyContext live, Map<String, Object> arguments) {
        Map<String, Object> root = policy.effective();
        Map<String, Object> validation = object(root, "validation");
        Set<String> required = QcSuiteTools.normalizeOptionalStages(strings(validation.get("required_stages")));
        Map<String, Object> reasoning = object(root, "reasoning");
        if (Boolean.TRUE.equals(reasoning.get("required"))) {
            // Defense in depth: the policy loader canonicalizes this into required_stages, but a
            // reasoning.required contract must never become optional if a policy object is supplied
            // by another validated construction path in the future.
            required.add("reasoner");
        }
        if (required.isEmpty()) {
            throw new ToolArgException("validation.required_stages must not be empty for project QC.");
        }
        Set<String> stages = new LinkedHashSet<>(required);
        String failOn = policyFailOn(string(validation, "fail_on"));
        String profile = GovernanceTools.normalizeProfile(string(reasoning, "owl_profile"));
        int timeout = integer(reasoning.get("timeout_ms"), 120_000);
        int limit = Tools.optInt(arguments, "limit", 25);
        if (limit < 0 || limit > 10_000) {
            throw new ToolArgException("limit must be between 0 and 10000.");
        }

        String configuredReasoner = string(reasoning, "reasoner");
        if (stages.contains("reasoner") && configuredReasoner != null
                && (live.selectedReasoner() == null
                        || !configuredReasoner.equalsIgnoreCase(live.selectedReasoner()))) {
            throw new ToolArgException("Policy requires reasoner '" + configuredReasoner
                    + "' but Protégé currently has '" + (live.selectedReasoner() == null
                            ? "none" : live.selectedReasoner())
                    + "' selected. Select the policy reasoner before running project QC.");
        }

        List<Invariants.Invariant> invariants = stages.contains("invariants")
                ? InvariantFiles.load(policy.assets().getOrDefault("invariants", Collections.emptyList()))
                : Collections.emptyList();
        List<CompetencyQuestion> cqs = stages.contains("cqs") ? loadPolicyCqs(policy) : null;
        List<Path> shacl = stages.contains("shacl")
                ? policy.assets().getOrDefault("shacl", Collections.emptyList()) : Collections.emptyList();

        Map<String, Object> iri = object(root, "iri_policy");
        Pattern iriPattern = QcSuiteTools.compilePattern(string(iri, "pattern"));
        List<String> namespaces = strings(iri.get("required_namespaces"));
        List<String> requiredAnnotations = annotationRequirements(root);
        Map<String, Object> structural = object(validation, "structural");
        Set<String> disabled = new LinkedHashSet<>(strings(structural.get("disabled")));
        Map<String, String> severity = stringMap(object(structural, "severity_overrides"));

        Map<String, Object> interoperability = object(root, "interoperability");
        Map<String, Object> metadata = object(interoperability, "metadata");
        Map<String, Object> canonicalization = object(interoperability, "canonicalization");
        List<Path> manifests = policy.assets().getOrDefault(
                "interoperability_manifest", Collections.emptyList());
        if (manifests.size() != 1) {
            throw new ToolArgException("Exactly one RO-Crate metadata file must resolve.");
        }
        QcSuiteTools.InteroperabilityConfig interopConfig =
                new QcSuiteTools.InteroperabilityConfig(
                        string(interoperability, "profile"),
                        strings(interoperability.get("additional_profiles")),
                        string(metadata, "format"), manifests.get(0).toString(),
                        string(interoperability, "root_artifact"),
                        string(canonicalization, "algorithm"),
                        string(canonicalization, "hash"), string(canonicalization, "scope"),
                        integer(canonicalization.get("timeout_ms"), 120_000));

        return new QcSuiteTools.RunConfig(stages, required, failOn, profile, limit, timeout,
                invariants, cqs, null, null, shacl, iriPattern, namespaces,
                requiredAnnotations, true, governanceRules(root), disabled, severity, true, configuredReasoner,
                string(root, "root_ontology"), interopConfig,
                ModulePolicyGovernance.moduleChecks(policy, limit));
    }

    /**
     * Fail closed on an incomplete import closure when the policy demands it. {@code imports.mode:
     * locked} and {@code imports.fail_on_missing: true} both promise a complete, resolved closure;
     * without this the flagship "fail-closed project QC" gate would report {@code pass} while reasoning
     * over a truncated closure (an unresolved import silently drops axioms). Uses the unresolved-import
     * list captured in the SAME model-thread hop as the QC snapshot (no separate, race-prone re-read).
     * Mutates {@code result} in place, forcing {@code gate=error} and one finding per unresolved import.
     */
    @SuppressWarnings("unchecked")
    static void enforceImportIntegrity(ProjectPolicy policy,
            QcSuiteTools.SuiteExecution execution, Map<String, Object> result) {
        Map<String, Object> imports = object(policy.effective(), "imports");
        boolean failOnMissing = Boolean.TRUE.equals(imports.get("fail_on_missing"));
        boolean locked = "locked".equals(string(imports, "mode"));
        List<Map<String, Object>> findings = (List<Map<String, Object>>) result.computeIfAbsent(
                "findings", key -> new ArrayList<Map<String, Object>>());
        if ((failOnMissing || locked) && !execution.missingImports.isEmpty()) {
            for (Map<String, Object> row : execution.missingImports) {
                String iri = String.valueOf(row.get("import_iri"));
                findings.add(finding("imports.unresolved", "imports", "error",
                        "Required import could not be resolved: " + iri
                                + " (policy imports." + (locked ? "mode=locked" : "fail_on_missing=true")
                                + " forbids a partial closure).", Map.of("import_iri", iri)));
            }
            result.put("gate", "error");
        }
        if (locked) {
            Map<String, Object> verification = execution.importReport == null
                    ? Map.of("valid", false, "errors",
                            List.of("project QC did not capture the loaded import graph"))
                    : ImportLockTools.verifyForGate(policy, execution.importReport,
                            execution.fingerprint, execution.closureFingerprint);
            result.put("import_lock_verification", verification);
            if (!Boolean.TRUE.equals(verification.get("valid"))) {
                // Coordinates and disk hashes matching while the loaded content is not attested is a
                // distinct failure: the disk files are lock-faithful, but the in-memory closure QC
                // validated diverged from them (e.g. an unsaved edit of an imported ontology).
                boolean contentDivergence =
                        Boolean.FALSE.equals(verification.get("loaded_content_verified"));
                findings.add(contentDivergence
                        ? finding("imports.loaded_content_divergence", "imports", "error",
                                "The loaded import closure content does not provably match the "
                                        + "imports.lockfile-verified on-disk documents.", verification)
                        : finding("imports.lock_mismatch", "imports", "error",
                                "The loaded import closure does not match imports.lockfile content.",
                                verification));
                result.put("gate", "error");
            }
        }
    }

    private static List<CompetencyQuestion> loadPolicyCqs(ProjectPolicy policy) {
        Map<String, Object> cqPolicy = object(object(policy.effective(), "validation"),
                "competency_questions");
        String convention = string(cqPolicy, "convention");
        if (Cq.CONV_ANNOTATIONS.equals(convention)) {
            return null; // QcSuite phase 1 reads ontology annotations from the captured live revision.
        }
        List<Path> resolved = policy.assets().getOrDefault("cqs", Collections.emptyList());
        if (resolved.size() != 1) {
            throw new ToolArgException("Exactly one competency-question path must resolve for convention "
                    + convention + ".");
        }
        Path path = resolved.get(0);
        CqStore.LoadResult loaded;
        if (Cq.CONV_ROBOT.equals(convention)) {
            if (!java.nio.file.Files.isDirectory(path)) {
                throw new ToolArgException("robot-sparql-dir competency question path must be a directory: "
                        + path);
            }
            loaded = RobotSparqlDirStore.loadDirectory(path.toFile());
        } else if (Cq.CONV_MANIFEST.equals(convention)) {
            if (!java.nio.file.Files.isRegularFile(path)) {
                throw new ToolArgException("sidecar-manifest competency question path must be a JSON file: "
                        + path);
            }
            loaded = SidecarManifestStore.loadFile(path.toFile());
        } else {
            throw new ToolArgException("Unsupported policy CQ convention: " + convention);
        }
        if (!loaded.skipped.isEmpty()) {
            List<String> errors = new ArrayList<>();
            for (CqStore.LoadWarning warning : loaded.skipped) {
                errors.add(warning.source + ": " + warning.reason);
            }
            throw new ToolArgException("Competency-question assets contain unreadable entries: "
                    + String.join("; ", errors));
        }
        Set<String> ids = new LinkedHashSet<>();
        for (CompetencyQuestion cq : loaded.ok) {
            if (!ids.add(cq.id)) {
                throw new ToolArgException("Duplicate competency-question id in policy assets: " + cq.id);
            }
        }
        return loaded.ok;
    }

    private static List<String> annotationRequirements(Map<String, Object> root) {
        Map<String, Object> annotations = object(root, "annotations");
        List<String> requirements = new ArrayList<>(strings(annotations.get("required")));
        Map<String, Object> labels = object(annotations, "labels");
        if (!strings(labels.get("required_languages")).isEmpty()
                || Boolean.TRUE.equals(labels.get("one_preferred_per_language"))) {
            requirements.addAll(strings(labels.get("properties")));
        }
        Map<String, Object> definitions = object(annotations, "definitions");
        if (Boolean.TRUE.equals(definitions.get("required"))
                || !strings(definitions.get("required_languages")).isEmpty()) {
            requirements.addAll(strings(definitions.get("properties")));
        }
        Map<String, Object> prefixes = object(root, "prefixes");
        List<String> expanded = new ArrayList<>();
        for (String requirement : new LinkedHashSet<>(requirements)) {
            expanded.add(expandReference(requirement, prefixes));
        }
        return expanded;
    }

    private static PolicyGovernance.Rules governanceRules(Map<String, Object> root) {
        Map<String, Object> prefixes = object(root, "prefixes");
        Map<String, Object> annotations = object(root, "annotations");
        Map<String, Object> labels = object(annotations, "labels");
        Map<String, Object> definitions = object(annotations, "definitions");
        Map<String, Object> lifecycle = object(root, "lifecycle");
        Map<String, Object> validation = object(root, "validation");

        List<org.semanticweb.owlapi.model.IRI> labelProperties = iris(strings(labels.get("properties")), prefixes);
        List<org.semanticweb.owlapi.model.IRI> definitionProperties =
                iris(strings(definitions.get("properties")), prefixes);
        String status = string(lifecycle, "status_property");
        List<org.semanticweb.owlapi.model.IRI> replacements =
                iris(strings(lifecycle.get("replaced_by_properties")), prefixes);
        List<PolicyGovernance.Waiver> waivers = new ArrayList<>();
        for (Map<String, Object> waiver : objects(validation.get("waivers"))) {
            String expires = string(waiver, "expires");
            waivers.add(new PolicyGovernance.Waiver(string(waiver, "rule_id"),
                    string(waiver, "focus_iri"), string(waiver, "reason"),
                    string(waiver, "owner"), expires == null ? null : LocalDate.parse(expires)));
        }
        return new PolicyGovernance.Rules(labelProperties,
                new LinkedHashSet<>(strings(labels.get("required_languages"))),
                Boolean.TRUE.equals(labels.get("one_preferred_per_language")), definitionProperties,
                Boolean.TRUE.equals(definitions.get("required")),
                new LinkedHashSet<>(strings(definitions.get("required_languages"))),
                status == null ? null : org.semanticweb.owlapi.model.IRI.create(expandReference(status, prefixes)),
                new LinkedHashSet<>(strings(lifecycle.get("allowed_values"))),
                new LinkedHashSet<>(strings(lifecycle.get("deprecated_values"))), replacements,
                !Boolean.FALSE.equals(lifecycle.get("require_replacement")), waivers);
    }

    private static List<org.semanticweb.owlapi.model.IRI> iris(List<String> refs,
            Map<String, Object> prefixes) {
        List<org.semanticweb.owlapi.model.IRI> out = new ArrayList<>();
        for (String ref : refs) out.add(org.semanticweb.owlapi.model.IRI.create(expandReference(ref, prefixes)));
        return out;
    }

    private static String expandReference(String reference, Map<String, Object> prefixes) {
        int colon = reference.indexOf(':');
        if (colon > 0 && !Set.of("http", "https", "urn", "file")
                .contains(reference.substring(0, colon).toLowerCase(Locale.ROOT))) {
            String namespace = string(prefixes, reference.substring(0, colon));
            return namespace + reference.substring(colon + 1);
        }
        return reference;
    }

    private static Map<String, Object> policyErrors(ToolContext ctx, ProjectPolicy policy) {
        Map<String, Object> result = baseError(ctx, policy);
        List<Map<String, Object>> findings = new ArrayList<>();
        for (PolicyIssue issue : policy.issues()) {
            findings.add(finding("policy." + issue.code(), "policy", issue.severity(), issue.message(),
                    issue.path() == null ? null : Map.of("path", issue.path())));
        }
        result.put("findings", findings);
        result.put("errors", policy.issues().stream().map(PolicyIssue::toJson).toList());
        return result;
    }

    private static Map<String, Object> configurationError(ToolContext ctx, ProjectPolicy policy,
            String code, String message) {
        Map<String, Object> result = baseError(ctx, policy);
        result.put("findings", List.of(finding(code, "policy", "error", message, null)));
        result.put("errors", List.of(new PolicyIssue("error", code, null, message).toJson()));
        return result;
    }

    private static Map<String, Object> baseError(ToolContext ctx, ProjectPolicy policy) {
        var fingerprint = ctx.access().compute(mm ->
                OntologyFingerprints.compute(mm.getActiveOntology()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate", "error");
        result.put("policy_loaded", policy != null && policy.loaded());
        List<String> requiredStages = Collections.emptyList();
        String failOn = "error";
        if (policy != null && policy.digest() != null) {
            result.put("policy_digest", policy.digest());
        }
        if (policy != null && !policy.effective().isEmpty()) {
            Object version = policy.effective().get("version");
            if (version instanceof Number) {
                result.put("policy_version", ((Number) version).intValue());
            }
            Map<String, Object> validation = object(policy.effective(), "validation");
            requiredStages = strings(validation.get("required_stages"));
            if (string(validation, "fail_on") != null) {
                failOn = policyFailOn(string(validation, "fail_on"));
            }
        }
        result.put("semantic_fingerprint", fingerprint.semanticFingerprint());
        result.put("fingerprint_stability", fingerprint.stability());
        result.put("release_stable", fingerprint.releaseStable());
        result.put("fingerprint_warnings", fingerprint.warnings());
        result.put("required_stages", requiredStages);
        result.put("stages_ran", 0);
        result.put("stages_skipped", 0);
        result.put("stages", Collections.emptyList());
        result.put("artifacts", Collections.emptyList());
        result.put("snapshot_consistent", true);
        Map<String, Object> validationSnapshot = new LinkedHashMap<>();
        validationSnapshot.put("mode", "none");
        validationSnapshot.put("same_snapshot", false);
        validationSnapshot.put("semantic_fingerprint", fingerprint.semanticFingerprint());
        validationSnapshot.put("closure_fingerprint", null);
        validationSnapshot.put("stages", Collections.emptyList());
        result.put("validation_snapshot", validationSnapshot);
        result.put("fail_on", failOn);
        result.put("details", Map.of("fail_on", "warn".equals(failOn) ? "warning" : failOn));
        return result;
    }

    private static Map<String, Object> finding(String id, String source, String severity,
            String message, Map<String, Object> details) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", id);
        finding.put("source", source);
        finding.put("severity", severity);
        finding.put("message", message);
        finding.put("focus_iri", null);
        finding.put("axiom", null);
        finding.put("path", null);
        finding.put("rule_id", id);
        finding.put("waiver", null);
        finding.put("details", details == null ? Collections.emptyMap() : details);
        return finding;
    }

    private static Map<String, List<String>> assetJson(ProjectPolicy policy) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        policy.assets().forEach((key, paths) -> out.put(key,
                paths.stream().map(Path::toString).toList()));
        return out;
    }

    private static String policyFailOn(String value) {
        if (value == null || "error".equals(value)) {
            return "error";
        }
        if ("warning".equals(value) || "warn".equals(value)) {
            return "warn";
        }
        if ("info".equals(value) || "none".equals(value)) {
            return value;
        }
        throw new ToolArgException("Unknown policy fail_on: " + value);
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        if (map == null) {
            return Collections.emptyMap();
        }
        Object value = map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String ? (String) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List ? (List<String>) value : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : Collections.emptyList();
    }

    private static Map<String, String> stringMap(Map<String, Object> value) {
        Map<String, String> out = new LinkedHashMap<>();
        value.forEach((key, item) -> out.put(key, String.valueOf(item)));
        return out;
    }
}
