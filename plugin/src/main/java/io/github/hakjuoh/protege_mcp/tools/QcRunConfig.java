package io.github.hakjuoh.protege_mcp.tools;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable inputs shared by legacy and project-policy QC execution. */
final class QcRunConfig {
    private static final String INVARIANTS = "invariants";
    private static final List<String> ALL_STAGES = Arrays.asList(
            "interoperability", "reasoner", "profile", "governance", "structural",
            INVARIANTS, "cqs", "shacl");
    private static final List<String> DEFAULT_STAGES = Arrays.asList(
            "reasoner", "profile", "structural");

    final Set<String> stages;
    final Set<String> requiredStages;
    final String failOn;
    final String profileName;
    final int limit;
    final int timeout;
    final List<Invariants.Invariant> invariants;
    final List<CompetencyQuestion> policyCqs;
    final String shaclShapes;
    final String shaclShapesPath;
    final List<Path> shaclPaths;
    final Pattern iriPattern;
    final List<String> requiredNamespaces;
    final List<String> requiredAnnotations;
    final boolean checkOwnership;
    final PolicyGovernance.Rules policyGovernance;
    final Set<String> disabledStructural;
    final Map<String, String> structuralSeverity;
    final boolean projectMode;
    final String requiredReasoner;
    final String requiredOntologyIri;
    final QcInteroperabilityConfig interoperability;
    final List<Map<String, Object>> projectGovernanceChecks;
    /**
     * Capture the loaded import graph in the snapshot hop even outside project mode — needed when a
     * request-level {@code lock_mode=verify|required} must verify against the SAME-hop closure a
     * no-policy preview gate evaluated. Project mode always captures it regardless of this flag.
     */
    final boolean captureImportReport;

    QcRunConfig(Set<String> stages, Set<String> requiredStages, String failOn, String profileName,
            int limit, int timeout, List<Invariants.Invariant> invariants,
            List<CompetencyQuestion> policyCqs, String shaclShapes, String shaclShapesPath,
            List<Path> shaclPaths, Pattern iriPattern, List<String> requiredNamespaces,
            List<String> requiredAnnotations, boolean checkOwnership,
            PolicyGovernance.Rules policyGovernance,
            Set<String> disabledStructural, Map<String, String> structuralSeverity,
            boolean projectMode, String requiredReasoner, String requiredOntologyIri,
            QcInteroperabilityConfig interoperability) {
        this(stages, requiredStages, failOn, profileName, limit, timeout, invariants, policyCqs,
                shaclShapes, shaclShapesPath, shaclPaths, iriPattern, requiredNamespaces,
                requiredAnnotations, checkOwnership, policyGovernance, disabledStructural,
                structuralSeverity, projectMode, requiredReasoner, requiredOntologyIri,
                interoperability, List.of());
    }

    QcRunConfig(Set<String> stages, Set<String> requiredStages, String failOn, String profileName,
            int limit, int timeout, List<Invariants.Invariant> invariants,
            List<CompetencyQuestion> policyCqs, String shaclShapes, String shaclShapesPath,
            List<Path> shaclPaths, Pattern iriPattern, List<String> requiredNamespaces,
            List<String> requiredAnnotations, boolean checkOwnership,
            PolicyGovernance.Rules policyGovernance,
            Set<String> disabledStructural, Map<String, String> structuralSeverity,
            boolean projectMode, String requiredReasoner, String requiredOntologyIri,
            QcInteroperabilityConfig interoperability,
            List<Map<String, Object>> projectGovernanceChecks) {
        this.stages = Collections.unmodifiableSet(new LinkedHashSet<>(stages));
        this.requiredStages = Collections.unmodifiableSet(new LinkedHashSet<>(requiredStages));
        this.failOn = failOn;
        this.profileName = profileName;
        this.limit = limit;
        this.timeout = timeout;
        this.invariants = List.copyOf(invariants);
        this.policyCqs = policyCqs == null ? null : List.copyOf(policyCqs);
        this.shaclShapes = shaclShapes;
        this.shaclShapesPath = shaclShapesPath;
        this.shaclPaths = List.copyOf(shaclPaths);
        this.iriPattern = iriPattern;
        this.requiredNamespaces = List.copyOf(requiredNamespaces);
        this.requiredAnnotations = List.copyOf(requiredAnnotations);
        this.checkOwnership = checkOwnership;
        this.policyGovernance = policyGovernance == null
                ? PolicyGovernance.Rules.empty() : policyGovernance;
        this.disabledStructural = Collections.unmodifiableSet(new LinkedHashSet<>(disabledStructural));
        this.structuralSeverity = Collections.unmodifiableMap(new LinkedHashMap<>(structuralSeverity));
        this.projectMode = projectMode;
        this.requiredReasoner = requiredReasoner;
        this.requiredOntologyIri = requiredOntologyIri;
        this.interoperability = interoperability;
        this.projectGovernanceChecks = List.copyOf(projectGovernanceChecks);
        this.captureImportReport = false;
    }

    /** Field-for-field copy with a different import-report capture flag. */
    private QcRunConfig(QcRunConfig base, boolean captureImportReport) {
        this.stages = base.stages;
        this.requiredStages = base.requiredStages;
        this.failOn = base.failOn;
        this.profileName = base.profileName;
        this.limit = base.limit;
        this.timeout = base.timeout;
        this.invariants = base.invariants;
        this.policyCqs = base.policyCqs;
        this.shaclShapes = base.shaclShapes;
        this.shaclShapesPath = base.shaclShapesPath;
        this.shaclPaths = base.shaclPaths;
        this.iriPattern = base.iriPattern;
        this.requiredNamespaces = base.requiredNamespaces;
        this.requiredAnnotations = base.requiredAnnotations;
        this.checkOwnership = base.checkOwnership;
        this.policyGovernance = base.policyGovernance;
        this.disabledStructural = base.disabledStructural;
        this.structuralSeverity = base.structuralSeverity;
        this.projectMode = base.projectMode;
        this.requiredReasoner = base.requiredReasoner;
        this.requiredOntologyIri = base.requiredOntologyIri;
        this.interoperability = base.interoperability;
        this.projectGovernanceChecks = base.projectGovernanceChecks;
        this.captureImportReport = captureImportReport;
    }

    /** Same configuration with a different stage time budget. */
    QcRunConfig withTimeout(int timeoutMs) {
        QcRunConfig config = new QcRunConfig(stages, requiredStages, failOn, profileName, limit,
                timeoutMs, invariants, policyCqs, shaclShapes, shaclShapesPath, shaclPaths, iriPattern,
                requiredNamespaces, requiredAnnotations, checkOwnership, policyGovernance,
                disabledStructural, structuralSeverity, projectMode, requiredReasoner,
                requiredOntologyIri, interoperability, projectGovernanceChecks);
        return captureImportReport ? new QcRunConfig(config, true) : config;
    }

    /** Same configuration, also capturing the loaded import graph in the snapshot hop. */
    QcRunConfig withImportReportCapture() {
        return captureImportReport ? this : new QcRunConfig(this, true);
    }

    static QcRunConfig legacy(Map<String, Object> arguments) {
        Set<String> stages = normalizeStages(Tools.stringList(arguments, "stages"));
        Set<String> required = normalizeOptionalStages(
                Tools.stringList(arguments, "required_stages"));
        stages.addAll(required);
        String profile = GovernanceTools.normalizeProfile(
                Tools.optString(arguments, "owl_profile"));
        int limit = Tools.optInt(arguments, "limit", 25);
        if (limit < 0 || limit > 10_000) {
            throw new ToolArgException("limit must be between 0 and 10000.");
        }
        int timeout = Tools.optInt(arguments, "timeout_ms", 120_000);
        timeout = timeout <= 0 ? 120_000 : timeout;
        List<Invariants.Invariant> invariants = stages.contains(INVARIANTS)
                ? Invariants.parse(Tools.objList(arguments, "invariants")) : Collections.emptyList();
        Pattern iriPattern = compilePattern(Tools.optString(arguments, "iri_pattern"));
        return new QcRunConfig(stages, required,
                normalizeFailOn(Tools.optString(arguments, "fail_on")),
                profile, limit, timeout, invariants, null,
                Tools.optString(arguments, "shacl_shapes"),
                Tools.optString(arguments, "shacl_shapes_path"),
                Collections.emptyList(), iriPattern,
                Tools.stringList(arguments, "required_namespaces"),
                Tools.stringList(arguments, "required_annotations"),
                Tools.optBool(arguments, "check_ownership", true), PolicyGovernance.Rules.empty(),
                Collections.emptySet(), Collections.emptyMap(), false, null, null, null, List.of());
    }

    static Set<String> normalizeStages(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return new LinkedHashSet<>(DEFAULT_STAGES);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String stage : requested) {
            String normalized = stage.trim().toLowerCase(Locale.ROOT);
            if (!ALL_STAGES.contains(normalized)) {
                throw new ToolArgException("Unknown stage '" + stage + "'. Use any of: "
                        + String.join(", ", ALL_STAGES) + ".");
            }
            out.add(normalized);
        }
        return out;
    }

    static Set<String> normalizeOptionalStages(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String stage : requested) {
            String normalized = stage.trim().toLowerCase(Locale.ROOT);
            if (!ALL_STAGES.contains(normalized)) {
                throw new ToolArgException("Unknown required stage '" + stage + "'. Use any of: "
                        + String.join(", ", ALL_STAGES) + ".");
            }
            out.add(normalized);
        }
        return out;
    }

    static String normalizeFailOn(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "error";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "warn", "error" -> normalized;
            default -> throw new ToolArgException(
                    "fail_on must be none, warn, or error (not '" + raw + "').");
        };
    }

    static Pattern compilePattern(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Pattern.compile(raw);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new ToolArgException(
                    "iri_pattern is not a valid regular expression: " + e.getMessage());
        }
    }
}
