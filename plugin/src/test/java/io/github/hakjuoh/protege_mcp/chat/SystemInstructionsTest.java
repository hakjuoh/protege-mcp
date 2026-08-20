package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SystemInstructionsTest {

    @Test
    void loadsGlobalSystemInstructionSuccessfully() {
        String instruction = SystemInstructions.get();
        assertNotNull(instruction, "global system instruction must be loaded");
        assertFalse(instruction.isBlank(), "global system instruction must not be empty");
    }

    @Test
    void containsKeyOntologyEngineeringPhasesAndToolReferences() {
        String text = SystemInstructions.get();
        assertTrue(text.contains("Prioritize Protégé MCP Tools"));
        assertTrue(text.contains("Phase 1: Specification & Scope"));
        assertTrue(text.contains("list_competency_questions"));
        assertTrue(text.contains("add_competency_question"));
        assertTrue(text.contains("run_competency_questions"));
        assertTrue(text.contains("search_entities"));
        assertTrue(text.contains("run_reasoner"));
        assertTrue(text.contains("run_project_qc"));
        assertTrue(text.contains("SSSOM"));
    }

    @Test
    void systemInstructionMentionsEveryCatalogTool() {
        String instruction = SystemInstructions.get();
        java.util.Set<String> tools = io.github.hakjuoh.protege_mcp.catalog.McpCatalog.get().toolNames();
        java.util.List<String> missing = tools.stream()
                .filter(tool -> !instruction.contains(tool))
                .sorted()
                .toList();
        assertTrue(missing.isEmpty(), () -> "system-instruction.md is missing tools: " + missing);
    }

    @Test
    void eachToolHasDedicatedDocumentationSectionWithWhenAndRelated() {
        String instruction = SystemInstructions.get();
        java.util.Set<String> tools = io.github.hakjuoh.protege_mcp.catalog.McpCatalog.get().toolNames();
        for (String tool : tools) {
            String heading = "#### `" + tool + "`";
            int index = instruction.indexOf(heading);
            assertTrue(index >= 0, () -> "missing heading " + heading + " in system-instruction.md");

            int nextHeading = instruction.indexOf("#### `", index + heading.length());
            String section = nextHeading >= 0
                    ? instruction.substring(index, nextHeading)
                    : instruction.substring(index);

            assertFalse(section.contains("- **Purpose:**"), () -> tool + " should not have redundant Purpose (present in MCP catalog description)");
            assertTrue(section.contains("- **When to Use:**"), () -> tool + " is missing When to Use in system-instruction.md");
            assertTrue(section.contains("- **Related Tools:**"), () -> tool + " is missing Related Tools in system-instruction.md");
        }
    }

    @Test
    void loadThrowsWhenStreamIsNull() {
        assertThrows(IllegalStateException.class, () -> SystemInstructions.load(null));
    }

    @Test
    void loadReadsValidStreamContent() {
        InputStream stream = new ByteArrayInputStream("custom instruction".getBytes(StandardCharsets.UTF_8));
        String result = SystemInstructions.load(stream);
        assertTrue(result.equals("custom instruction"));
    }

    @Test
    void loadThrowsWhenStreamFails() {
        InputStream failingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated read error");
            }
        };
        assertThrows(IllegalStateException.class, () -> SystemInstructions.load(failingStream));
    }
}
