package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, surface-neutral execution result supplied to {@link ProjectQcService}. */
public record QcStageExecution(
        String stage,
        QcStageVerdict verdict,
        String message,
        Map<String, Object> details) {

    public QcStageExecution {
        if (stage == null || stage.trim().isEmpty()) {
            throw new IllegalArgumentException("stage must not be blank");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("verdict must not be null");
        }
        if ((verdict == QcStageVerdict.SKIPPED || verdict == QcStageVerdict.ERROR)
                && (message == null || message.trim().isEmpty())) {
            throw new IllegalArgumentException(verdict.name().toLowerCase()
                    + " stage requires a message");
        }
        details = details == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static QcStageExecution skipped(String stage, String message) {
        return new QcStageExecution(stage, QcStageVerdict.SKIPPED, message, null);
    }

    public static QcStageExecution error(String stage, String message, Map<String, Object> details) {
        return new QcStageExecution(stage, QcStageVerdict.ERROR, message, details);
    }
}
