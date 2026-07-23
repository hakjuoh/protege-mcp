package io.github.hakjuoh.protege_mcp.jobs;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Opaque owner-bound stable-anchor cursor codec for newest-first job pages. */
final class JobCursorCodec {
    int start(JobOwner owner, List<JobRecord> rows, String encoded) {
        if (encoded.length() > 512) throw invalidCursor();
        final String value;
        try {
            value = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor();
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4 || !parts[0].equals("v1")
                || !parts[1].equals(owner.ownerFingerprint())) {
            throw invalidCursor();
        }
        long created;
        try {
            created = Long.parseLong(parts[2]);
        } catch (NumberFormatException invalid) {
            throw invalidCursor();
        }
        for (int index = 0; index < rows.size(); index++) {
            JobRecord row = rows.get(index);
            if (row.createdAt.toEpochMilli() == created && row.jobId.equals(parts[3])) {
                return index + 1;
            }
        }
        throw invalidCursor();
    }

    String encode(JobOwner owner, JobRecord anchor) {
        String raw = "v1|" + owner.ownerFingerprint() + "|"
                + anchor.createdAt.toEpochMilli() + "|" + anchor.jobId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static JobException invalidCursor() {
        return JobFailures.effectsPrevented(
                "invalid_job_cursor", "The job list cursor is invalid or stale.", false);
    }
}
