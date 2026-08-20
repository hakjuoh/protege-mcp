package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProjectPolicyPatcherTest {

    @Test
    void patchPreservesCrLfAndEveryUnaffectedByte() throws Exception {
        String before = "# lead\r\nversion: 2\r\nproject_id: demo\r\n"
                + "network:\r\n  default: deny\r\n  allowed_hosts: []\r\n"
                + "# untouched\r\naudit:\r\n  retention_days: 90\r\n";

        String after = ProjectPolicyPatcher.apply(before,
                Map.of("network", Map.of("allowed_hosts", List.of("example.org"))));

        assertTrue(after.startsWith("# lead\r\nversion: 2\r\nproject_id: demo\r\n"), after);
        assertTrue(after.endsWith("# untouched\r\naudit:\r\n  retention_days: 90\r\n"), after);
        assertTrue(after.contains("  default: \"deny\"\r\n"), after);
        assertTrue(after.contains("  allowed_hosts:\r\n    - \"example.org\"\r\n"), after);
        assertFalse(after.replace("\r\n", "").contains("\n"), "patch introduced a bare LF");
    }

    @Test
    void patchBeforeFinalSectionPreservesMissingFinalNewline() throws Exception {
        String suffix = "# final comment\naudit:\n  retention_days: 90";
        String before = "version: 2\nnetwork:\n  default: deny\n" + suffix;

        String after = ProjectPolicyPatcher.apply(before,
                Map.of("network", Map.of("default", "allow")));

        assertTrue(after.endsWith(suffix), after);
        assertFalse(after.endsWith("\n"));
    }

    @Test
    void nullRemovesNestedFieldsAndPatchCanAddAnyTopLevelSection() throws Exception {
        String before = "version: 2\naudit:\n  retention_days: 90\n  max_files: 10\n";

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("max_files", null);
        String after = ProjectPolicyPatcher.apply(before, Map.of(
                "audit", audit, "external_terms", Map.of("providers", List.of())));

        assertFalse(after.contains("max_files"), after);
        assertTrue(after.contains("external_terms:"), after);
        assertEquals(1, after.split("external_terms:", -1).length - 1);
    }
}
