package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;

class MissingImportsModeTest {

    @Test
    void defaultsToCompatibilityWarningMode() {
        assertEquals(MissingImportsMode.WARN, MissingImportsMode.parse(null));
        assertEquals(MissingImportsMode.WARN, MissingImportsMode.parse(""));
        assertEquals(MissingImportHandlingStrategy.SILENT,
                MissingImportsMode.WARN.strategy(), "warn lets parsing finish so the result can report IRIs");
        assertTrue(MissingImportsMode.WARN.reportsMissing());
    }

    @Test
    void acceptsAllDocumentedModesCaseInsensitively() {
        assertEquals(MissingImportsMode.WARN, MissingImportsMode.parse(" WARN "));
        assertEquals(MissingImportsMode.ERROR, MissingImportsMode.parse("error"));
        assertEquals(MissingImportsMode.SILENT, MissingImportsMode.parse("Silent"));
        assertEquals(MissingImportHandlingStrategy.THROW_EXCEPTION,
                MissingImportsMode.ERROR.strategy());
        assertFalse(MissingImportsMode.SILENT.reportsMissing());
        assertEquals(List.of("https://example.org/missing"),
                MissingImportsMode.WARN.reported(List.of("https://example.org/missing")));
        assertEquals(List.of(),
                MissingImportsMode.SILENT.reported(List.of("https://example.org/missing")));
    }

    @Test
    void rejectsUnknownModesInsteadOfFallingBack() {
        ToolArgException error = assertThrows(ToolArgException.class,
                () -> MissingImportsMode.parse("ignore"));
        assertTrue(error.getMessage().contains("warn, error, or silent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicSchemasAdvertiseTheClosedModeVocabulary() {
        ToolRegistry registry = new ToolRegistry();
        OntologyDocumentTools.register(registry, null);
        for (String name : List.of("load_ontology", "merge_ontology_document")) {
            Map<String, Object> schema = registry.build().stream()
                    .filter(spec -> name.equals(spec.tool().name()))
                    .findFirst().orElseThrow().tool().inputSchema();
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            Map<String, Object> missingImports = (Map<String, Object>) properties.get("missing_imports");
            assertEquals(List.of("warn", "error", "silent"), missingImports.get("enum"));
        }
    }
}
