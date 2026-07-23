package io.github.hakjuoh.protege_mcp.reasoner;

/** Closed support vocabulary for reasoner and rule capabilities. */
public enum CapabilityStatus {
    SUPPORTED("supported"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown"),
    UNTESTED("untested");

    private final String value;

    CapabilityStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Conservative aggregation: one unsupported item dominates incomplete coverage. */
    public static CapabilityStatus aggregate(Iterable<CapabilityStatus> statuses) {
        boolean unknown = false;
        boolean untested = false;
        for (CapabilityStatus status : statuses) {
            if (status == UNSUPPORTED) return UNSUPPORTED;
            if (status == UNKNOWN) unknown = true;
            if (status == UNTESTED) untested = true;
        }
        if (unknown) return UNKNOWN;
        if (untested) return UNTESTED;
        return SUPPORTED;
    }
}
