package io.github.hakjuoh.protege_mcp.core.audit;

import java.util.Map;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/**
 * Bounded rotation and retention settings for one project's local audit streams.
 *
 * <p>{@code policyDerived} records whether the retention value speaks for the project (a loaded,
 * valid policy — including one that omits the audit block and so accepts the defaults) or is only
 * this session's fallback because no valid policy was available. Only policy-derived retention may
 * sweep sibling workspaces' streams: a fallback of {@value #DEFAULT_RETENTION_DAYS} days must never
 * delete history that the authored (but momentarily unreadable) policy retains for longer. While
 * settings are fallback-derived, destructive retention and rotation therefore use the schema
 * maxima; valid policies that omit the audit block still use the ordinary defaults.
 */
public record AuditSettings(int retentionDays, long maxFileBytes, int maxFiles,
        boolean policyDerived) {

    public static final int DEFAULT_RETENTION_DAYS = 90;
    public static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024L * 1024L;
    public static final int DEFAULT_MAX_FILES = 10;
    public static final int MAX_RETENTION_DAYS = 3650;
    public static final long MAX_FILE_BYTES = 1024L * 1024L * 1024L;
    public static final int MAX_FILES = 100;

    public AuditSettings {
        if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("audit retention_days must be between 1 and 3650");
        }
        if (maxFileBytes < 1024 || maxFileBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "audit max_file_bytes must be between 1024 and 1073741824");
        }
        if (maxFiles < 2 || maxFiles > MAX_FILES) {
            throw new IllegalArgumentException("audit max_files must be between 2 and 100");
        }
    }

    /** Explicit settings act as project-authoritative (policy-derived). */
    public AuditSettings(int retentionDays, long maxFileBytes, int maxFiles) {
        this(retentionDays, maxFileBytes, maxFiles, true);
    }

    public static AuditSettings defaults() {
        return new AuditSettings(DEFAULT_RETENTION_DAYS, DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_FILES, false);
    }

    /** Read authored settings without changing the canonical effective-policy digest when omitted. */
    public static AuditSettings from(ProjectPolicy policy) {
        if (policy == null) return defaults();
        if (!(policy.effective().get("audit") instanceof Map<?, ?> raw)) {
            // A valid policy without an audit block deliberately accepts the defaults.
            return new AuditSettings(DEFAULT_RETENTION_DAYS, DEFAULT_MAX_FILE_BYTES,
                    DEFAULT_MAX_FILES);
        }
        return new AuditSettings(integer(raw, "retention_days", DEFAULT_RETENTION_DAYS),
                longInteger(raw, "max_file_bytes", DEFAULT_MAX_FILE_BYTES),
                integer(raw, "max_files", DEFAULT_MAX_FILES));
    }

    private static int integer(Map<?, ?> map, String key, int fallback) {
        long value = longInteger(map, key, fallback);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " is too large");
        return (int) value;
    }

    private static long longInteger(Map<?, ?> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException("audit " + key + " must be an integer");
        }
        return number.longValue();
    }
}
