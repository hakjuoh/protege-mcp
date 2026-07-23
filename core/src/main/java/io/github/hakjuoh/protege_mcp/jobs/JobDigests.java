package io.github.hakjuoh.protege_mcp.jobs;

/** Public adapter boundary for the length-delimited SHA-256 identities used by job contracts. */
public final class JobDigests {
    private JobDigests() {
    }

    public static String digest(String... values) {
        return JobHashes.digest(values == null ? new String[] { null } : values);
    }

    public static String digest(byte[] bytes) {
        return JobHashes.digest(bytes);
    }
}
