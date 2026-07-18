package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed access helpers for the change-set QC payload consumed during verified apply. */
final class ChangeSetPreflightView {

    private ChangeSetPreflightView() {
    }

    static Map<String, Object> stageDetails(Map<String, Object> preflight, String stage) {
        Object stages = preflight.get("stages");
        if (!(stages instanceof List<?>)) return null;
        for (Object value : (List<?>) stages) {
            if (value instanceof Map<?, ?> row && stage.equals(row.get("stage"))
                    && row.get("details") instanceof Map<?, ?> details) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : details.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out.isEmpty() ? null : out;
            }
        }
        return null;
    }

    static String reasonerStatus(Map<String, Object> preflight) {
        Object stages = preflight.get("stages");
        if (!(stages instanceof List<?>)) return null;
        for (Object value : (List<?>) stages) {
            if (value instanceof Map<?, ?> row && "reasoner".equals(row.get("stage"))) {
                return row.get("status") == null ? null : row.get("status").toString();
            }
        }
        return null;
    }

    static String reasonerName(Map<String, Object> preflight) {
        Map<String, Object> details = stageDetails(preflight, "reasoner");
        if (details == null) return null;
        Object configuration = details.get("reasoner_configuration");
        if (configuration instanceof Map<?, ?> map && map.get("reasoner_name") != null) {
            return map.get("reasoner_name").toString();
        }
        return null;
    }

    static boolean reasonerInconsistent(Map<String, Object> preflight) {
        Map<String, Object> details = stageDetails(preflight, "reasoner");
        return details != null && Boolean.FALSE.equals(details.get("consistent"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> unsatItems(Map<String, Object> reasonerDetails) {
        Object classes = reasonerDetails.get("unsatisfiable_classes");
        if (classes instanceof Map<?, ?> map && map.get("items") instanceof List<?> items) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?>) out.add((Map<String, Object>) item);
            }
            return out;
        }
        return List.of();
    }

    static int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
