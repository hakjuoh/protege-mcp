package io.github.hakjuoh.protege_mcp.policy;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.ro_crate.RoCrateProjectProfile;
import io.github.hakjuoh.protege_mcp.ro_crate.RoCrateProjectProfileValidator;
import io.github.hakjuoh.protege_mcp.ro_crate.RoCrateValidationIssue;
import io.github.hakjuoh.protege_mcp.ro_crate.RoCrateValidationResult;
import io.github.hakjuoh.protege_mcp.ro_crate.RoCrateVersion;

/** Maps project-policy v1 into the standalone RO-Crate validation API. */
final class RoCrateProjectManifest {

    private RoCrateProjectManifest() {
    }

    /**
     * The profile permits inferring only 1.2/1.3 from an unauthored format: those versions carry
     * the formal Profiles model a consumer must not misread, while 1.0/1.1-context crates stay on
     * the documented 1.1 default (and then fail validation loudly instead of being silently
     * adopted as a legacy version the schema's format/path pairing would have rejected).
     */
    static void inferVersion(Path manifest, Map<String, Object> policy) {
        RoCrateProjectProfileValidator.detectVersion(manifest)
                .filter(RoCrateVersion::formalProfiles)
                .ifPresent(version -> object(object(policy, "interoperability"), "metadata")
                        .put("format", version.format()));
    }

    static void validate(Path manifest, Map<String, Object> policy, List<PolicyIssue> issues) {
        Map<String, Object> interoperability = object(policy, "interoperability");
        String format = string(object(interoperability, "metadata"), "format");
        final RoCrateVersion version;
        try {
            version = ProjectInteroperability.roCrateVersion(format);
        } catch (IllegalArgumentException e) {
            issues.add(error("interop_format_unsupported", "interoperability.metadata.format",
                    e.getMessage()));
            return;
        }

        RoCrateProjectProfile profile = new RoCrateProjectProfile(
                string(policy, "project_id"),
                string(interoperability, "root_artifact"),
                string(policy, "root_ontology"),
                string(interoperability, "profile"),
                strings(interoperability.get("additional_profiles")),
                ProjectInteroperability.OWL2_SPEC);
        RoCrateValidationResult result = RoCrateProjectProfileValidator.validate(
                manifest, version, profile);
        for (RoCrateValidationIssue issue : result.issues()) {
            String path = "metadata".equals(issue.path())
                    ? "interoperability.metadata.path"
                    : "interoperability." + issue.path();
            issues.add(error("interop_" + issue.code(), path, issue.message()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> value, String key) {
        Object nested = value.get(key);
        return nested instanceof Map ? (Map<String, Object>) nested : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List ? (List<String>) value : Collections.emptyList();
    }

    private static String string(Map<String, Object> value, String key) {
        Object nested = value.get(key);
        return nested instanceof String ? (String) nested : null;
    }

    private static PolicyIssue error(String code, String path, String message) {
        return new PolicyIssue("error", code, path, message);
    }
}
