package io.github.hakjuoh.protege_mcp.core.audit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Extracts a deliberately small, content-free audit projection from public tool envelopes. */
public final class AuditFacts {

    private static final Set<String> SCALARS = Set.of(
            "applied", "bytes", "change_count", "change_set_id", "closure_fingerprint",
            "committed", "created", "deleted", "discarded", "dry_run", "entry_count",
            "error_code", "event_count", "exported", "gate", "import_lock_digest", "output_dir", "path",
            "policy_digest", "prepared", "removed", "saved", "semantic_fingerprint", "sha256",
            "source_count", "valid", "verified", "written");
    private static final Set<String> REVISION_FIELDS = Set.of(
            "closure_fingerprint", "document_fingerprint", "import_lock_digest",
            "policy_digest", "semantic_fingerprint", "session_revision", "workspace_fingerprint");
    private static final Set<String> COMMIT_INDICATORS = Set.of(
            "committed", "written", "prepared", "applied", "saved", "created", "deleted",
            "removed", "exported");
    private static final Set<String> GATES = Set.of("pass", "fail", "error", "not_applicable");
    private static final Pattern SAFE_REFERENCE = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$");
    private static final Pattern SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private AuditFacts() {
    }

    /** Allowlisted result facts only; nested ontology content and arbitrary messages never pass. */
    public static Map<String, Object> summary(Object structuredContent) {
        if (!(structuredContent instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : SCALARS) {
            Object value = source.get(key);
            if (safeScalar(value)) summary.put(key, value);
        }
        copyRevision(source, summary, "revision");
        copyRevision(source, summary, "new_revision");
        copyArtifacts(source, summary);
        return summary;
    }

    public static String gate(Object structuredContent) {
        if (!(structuredContent instanceof Map<?, ?> source)) return null;
        Object value = source.get("gate");
        return value instanceof String gate && GATES.contains(gate) ? gate : null;
    }

    /** Whether a successful envelope says a state or artifact mutation actually landed. */
    public static Boolean committed(Object structuredContent, boolean mutationExpected) {
        if (!(structuredContent instanceof Map<?, ?> source)) return null;
        boolean found = false;
        boolean positive = false;
        for (String key : COMMIT_INDICATORS) {
            if (!source.containsKey(key)) continue;
            found = true;
            Object value = source.get(key);
            positive |= Boolean.TRUE.equals(value)
                    || value instanceof Number number && number.doubleValue() > 0;
        }
        if (found) return positive;
        if (mutationExpected
                && ("fail".equals(source.get("gate")) || "error".equals(source.get("gate")))) {
            return false;
        }
        return null;
    }

    /** Stable references that prove which preview/policy the caller explicitly confirmed. */
    public static List<String> confirmationReferences(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return List.of();
        List<String> references = new ArrayList<>();
        addReference(references, "change_set", arguments.get("change_set_id"), SAFE_REFERENCE);
        addReference(references, "policy_digest", arguments.get("confirm_policy_digest"), SHA256);
        return List.copyOf(references);
    }

    /** Link the committed release to its manifest without retaining the manifest body. */
    public static String releaseManifest(String operation, Object structuredContent) {
        if (!"prepare_release".equals(operation) || !(structuredContent instanceof Map<?, ?> source)
                || !Boolean.TRUE.equals(source.get("prepared"))) return null;
        Object output = source.get("output_dir");
        if (!(output instanceof String directory) || directory.isBlank()) return null;
        return Path.of(directory).resolve("manifest.json").normalize().toString();
    }

    private static void copyRevision(Map<?, ?> source, Map<String, Object> summary, String key) {
        if (!(source.get(key) instanceof Map<?, ?> revision)) return;
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String field : REVISION_FIELDS) {
            Object value = revision.get(field);
            if (safeScalar(value)) safe.put(field, value);
        }
        if (!safe.isEmpty()) summary.put(key, safe);
    }

    private static void copyArtifacts(Map<?, ?> source, Map<String, Object> summary) {
        if (!(source.get("artifacts") instanceof List<?> artifacts)) return;
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Object value : artifacts) {
            if (safe.size() >= 20 || !(value instanceof Map<?, ?> artifact)) break;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : List.of("path", "sha256", "bytes")) {
                Object item = artifact.get(field);
                if (safeScalar(item)) row.put(field, item);
            }
            if (!row.isEmpty()) safe.add(row);
        }
        if (!safe.isEmpty()) summary.put("artifacts", safe);
    }

    private static void addReference(List<String> references, String label, Object value,
            Pattern accepted) {
        if (value instanceof String text && accepted.matcher(text).matches()) {
            references.add(label + ":" + text);
        }
    }

    private static boolean safeScalar(Object value) {
        return value instanceof Boolean || value instanceof Number
                || value instanceof String text && text.length() <= 1024;
    }
}
