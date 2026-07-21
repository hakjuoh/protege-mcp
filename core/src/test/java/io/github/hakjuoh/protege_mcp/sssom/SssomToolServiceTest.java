package io.github.hakjuoh.protege_mcp.sssom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.contracts.SssomToolSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;

class SssomToolServiceTest {

    @TempDir
    Path temporary;

    @Test
    void listCursorIsDeterministicAndRevisionBound() throws Exception {
        SssomMappingStore store = store("mappings.tsv");
        String empty = store.read(policy(), entities()).mappingRevision();
        Map<String, Object> first = SssomToolService.add(store, empty, row("A", "B"),
                metadata(), Map.of(), policy(), entities(), SssomMappingStore.MutationGuard.none());
        Map<String, Object> second = SssomToolService.add(store,
                (String) first.get("mapping_revision"), row("C", "D"), Map.of(), Map.of(),
                policy(), entities(), SssomMappingStore.MutationGuard.none());

        Map<String, Object> page = SssomToolService.list(store, policy(), entities(), 1, null);
        assertEquals(1, page.get("returned"));
        String cursor = (String) page.get("next_cursor");
        assertTrue(cursor.length() < 512);
        Map<String, Object> remainder = SssomToolService.list(store, policy(), entities(), 1, cursor);
        assertEquals(1, remainder.get("returned"));
        assertFalse(remainder.containsKey("next_cursor"));

        SssomToolService.add(store, (String) second.get("mapping_revision"), row("E", "F"),
                Map.of(), Map.of(), policy(), entities(), SssomMappingStore.MutationGuard.none());
        SssomStoreException stale = assertThrows(SssomStoreException.class,
                () -> SssomToolService.list(store, policy(), entities(), 1, cursor));
        assertEquals("cursor_revision_conflict", stale.code());
    }

    @Test
    void validationFindingsAndMutationResultsSatisfyPublishedSchemas() throws Exception {
        SssomMappingStore store = store("schema.tsv");
        Map<String, Object> added = SssomToolService.add(store,
                store.read(policy(), entities()).mappingRevision(), row("A", "B"),
                metadata(), Map.of(), policy(), entities(), SssomMappingStore.MutationGuard.none());
        assertSchema("add_mapping", added);
        Map<String, Object> listed = SssomToolService.list(store, policy(), entities(), 50, null);
        assertSchema("list_mappings", listed);
        Map<String, Object> validated = SssomToolService.validate(
                store, policy(), entities(), 50, null);
        assertSchema("validate_mappings", validated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) listed.get("items");
        Map<String, Object> removed = SssomToolService.remove(store,
                (String) added.get("mapping_revision"),
                (String) items.get(0).get("mapping_id"), policy(), entities(),
                SssomMappingStore.MutationGuard.none());
        assertSchema("remove_mapping", removed);
    }

    @Test
    void validationCursorIsBoundToEntityDependentFindingSet() throws Exception {
        SssomMappingStore store = store("finding-cursor.tsv");
        Map<String, Object> first = SssomToolService.add(store,
                store.read(policy(), entities()).mappingRevision(), row("A", "B"),
                metadata(), Map.of(), policy(), entities(), SssomMappingStore.MutationGuard.none());
        SssomToolService.add(store, (String) first.get("mapping_revision"), row("C", "D"),
                Map.of(), Map.of(), policy(), entities(), SssomMappingStore.MutationGuard.none());
        SssomEntityIndex missing = new SssomEntityIndex(java.util.Set.of(), java.util.Set.of());
        Map<String, Object> page = SssomToolService.validate(
                store, policy(), missing, 1, null);
        String cursor = (String) page.get("next_cursor");
        assertTrue(cursor != null && !cursor.isBlank());

        SssomEntityIndex nowPresent = new SssomEntityIndex(java.util.Set.of(
                "https://example.org/A", "https://example.org/B",
                "https://example.org/C", "https://example.org/D"), java.util.Set.of());
        SssomStoreException stale = assertThrows(SssomStoreException.class,
                () -> SssomToolService.validate(store, policy(), nowPresent, 1, cursor));

        assertEquals("cursor_revision_conflict", stale.code());
    }

    @Test
    void invalidRowsWithLongIdsStillSatisfyListInspectionSchema() throws Exception {
        Path target = temporary.resolve("invalid-inspection.tsv");
        Map<String, String> invalid = row("A", "B");
        invalid.put("mapping_id", "urn:" + "x".repeat(5_000));
        invalid.put("predicate_id", "");
        Files.write(target, SssomParser.render(new SssomDocument(metadata(), Map.of(),
                List.of("mapping_id", "subject_id", "predicate_id", "object_id",
                        "mapping_justification"), List.of(new MappingRecord(invalid)))));

        Map<String, Object> listed = SssomToolService.list(
                store("invalid-inspection.tsv"), policy(), entities(), 50, null);

        assertEquals(false, listed.get("valid"));
        assertSchema("list_mappings", listed);
    }

    @Test
    void importAndExportAreProjectedWithoutLosingTheirModeOrSafetyFlags() throws Exception {
        SssomMappingStore store = store("canonical.tsv");
        Path source = temporary.resolve("source.tsv");
        Files.write(source, SssomParser.render(new SssomDocument(metadata(), Map.of(),
                List.of("mapping_id", "subject_id", "predicate_id", "object_id",
                        "mapping_justification"), List.of(new MappingRecord(row("A", "B"))))));
        Map<String, Object> imported = SssomToolService.importSssom(store,
                store.read(policy(), entities()).mappingRevision(), source,
                SssomMappingStore.ImportMode.REPLACE, policy(), entities(),
                SssomMappingStore.MutationGuard.none());
        assertEquals("replace", imported.get("mode"));
        assertSchema("import_sssom", imported);

        Path exportedPath = temporary.resolve("export.tsv");
        String mappingRevision = (String) imported.get("mapping_revision");
        Map<String, Object> exported = SssomToolService.exportSssom(store, mappingRevision,
                exportedPath,
                false, null, false, policy(), entities(),
                SssomMappingStore.MutationGuard.none());
        assertEquals(true, exported.get("lossless"));
        assertEquals(mappingRevision, exported.get("mapping_revision"));
        assertSchema("export_sssom", exported);
    }

    @Test
    void listPagesByCountAndBoundedJsonSize() throws Exception {
        SssomMappingStore store = store("bounded-list.tsv");
        Map<String, String> first = new LinkedHashMap<>(row("A", "B"));
        Map<String, String> second = new LinkedHashMap<>(row("C", "D"));
        for (int index = 0; index < 16; index++) {
            first.put("x_large_" + index, "a".repeat(65_000));
            second.put("x_large_" + index, "b".repeat(65_000));
        }
        SssomDocument large = new SssomDocument(metadata(), Map.of(),
                new java.util.ArrayList<>(first.keySet()),
                List.of(new MappingRecord(first), new MappingRecord(second)));
        store.importDocument(store.read(policy(), entities()).mappingRevision(), large,
                SssomMappingStore.ImportMode.REPLACE, policy(), entities(),
                SssomMappingStore.MutationGuard.none());

        Map<String, Object> pageOne = SssomToolService.list(store, policy(), entities(), 2, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) pageOne.get("items");
        assertEquals(1, firstItems.size());
        assertEquals(1, pageOne.get("returned"));
        int pageOneBytes = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(pageOne).length;
        assertTrue(pageOneBytes <= SssomToolService.MAX_PAGE_JSON_BYTES);
        assertTrue(pageOneBytes > SssomToolService.MAX_PAGE_JSON_BYTES - 10_000,
                () -> "fixture must exercise the page boundary, got " + pageOneBytes);
        String cursor = String.valueOf(pageOne.get("next_cursor"));

        Map<String, Object> pageTwo = SssomToolService.list(store, policy(), entities(), 2, cursor);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondItems = (List<Map<String, Object>>) pageTwo.get("items");
        assertEquals(1, secondItems.size());
        assertEquals(1, pageTwo.get("returned"));
    }

    @Test
    void allMappingSchemasAreStrictAndTyped() {
        for (String name : SssomToolSchemas.NAMES) {
            ToolSchemaValidator.validateInput(SssomToolSchemas.input(name, true), name + " input");
            ToolSchemaValidator.validateTypedOutput(SssomToolSchemas.output(name), name + " output");
        }
    }

    private void assertSchema(String name, Map<String, Object> result) {
        List<String> violations = ToolSchemaValidator.compile(SssomToolSchemas.output(name))
                .violations(result);
        assertTrue(violations.isEmpty(), () -> name + ": " + violations + " result=" + result);
    }

    private SssomMappingStore store(String name) throws Exception {
        return new SssomMappingStore(temporary, temporary.resolve(name), temporary.resolve("locks"));
    }

    private static Map<String, String> row(String subject, String object) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("mapping_id", "");
        cells.put("subject_id", "https://example.org/" + subject);
        cells.put("predicate_id", "skos:exactMatch");
        cells.put("object_id", "https://example.org/" + object);
        cells.put("mapping_justification", "semapv:ManualMappingCuration");
        return cells;
    }

    private static Map<String, Object> metadata() {
        return Map.of("mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/");
    }

    private static SssomValidationPolicy policy() {
        return SssomValidationPolicy.structural();
    }

    private static SssomEntityIndex entities() {
        return SssomEntityIndex.unavailable();
    }
}
