package io.github.hakjuoh.protege_mcp.core.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportLockFileTest {

    @TempDir
    Path temp;

    @Test
    void readsAndRendersDeterministically() throws Exception {
        ImportLockFile.Entry b = entry("https://example.org/b", null, "imports/b.ttl", false);
        ImportLockFile.Entry a = entry("https://example.org/a", "https://example.org/a/1",
                "imports/a.ttl", true);
        byte[] rendered = ImportLockFile.render(List.of(b, a));
        ImportLockFile.Document parsed = ImportLockFile.parse(rendered,
                temp.resolve("imports.lock.json"));

        assertEquals(List.of(a.key(), b.key()), parsed.entries().stream()
                .map(ImportLockFile.Entry::key).toList());
        assertTrue(parsed.sha256().startsWith("sha256:"));
        assertArrayEquals(rendered, ImportLockFile.render(parsed.entries()));
    }

    @Test
    void rejectsDuplicateJsonFieldsUnknownFieldsAndDuplicateIdentities() {
        assertParseFails("{\"version\":1,\"version\":1,\"imports\":[]}", "Duplicate field");
        assertParseFails("{\"version\":1,\"imports\":[],\"extra\":true}", "unknown top-level");
        String row = "{\"ontology_iri\":\"https://example.org/a\",\"version_iri\":null,"
                + "\"document\":\"a.ttl\",\"sha256\":\"" + "a".repeat(64)
                + "\",\"direct\":true}";
        assertParseFails("{\"version\":1,\"imports\":[" + row + "," + row + "]}",
                "duplicate lock entry");
    }

    @Test
    void rejectsPortableAbsoluteTraversalAndPlatformDependentPaths() {
        for (String path : List.of("../secret.owl", "a/../secret.owl", "/etc/hosts",
                "C:/Windows/system.ini", "C:\\Windows\\system.ini", "imports\\a.ttl")) {
            IOException error = assertThrows(IOException.class,
                    () -> ImportLockFile.parseEntry(row(path), temp));
            assertTrue(error.getMessage().contains("path") || error.getMessage().contains("document"),
                    () -> path + ": " + error.getMessage());
        }
    }

    @Test
    void rejectsMalformedCoordinatesHashesAndFlags() {
        Map<String, Object> relativeIri = row("a.ttl");
        relativeIri.put("ontology_iri", "relative");
        assertThrows(IOException.class, () -> ImportLockFile.parseEntry(relativeIri, temp));
        Map<String, Object> hash = row("a.ttl");
        hash.put("sha256", "A".repeat(64));
        assertThrows(IOException.class, () -> ImportLockFile.parseEntry(hash, temp));
        Map<String, Object> flag = row("a.ttl");
        flag.put("direct", "true");
        assertThrows(IOException.class, () -> ImportLockFile.parseEntry(flag, temp));
    }

    @Test
    void boundedReadRejectsOversizedInput() throws Exception {
        Path lock = temp.resolve("large.lock.json");
        try (var output = Files.newOutputStream(lock)) {
            output.write(new byte[(int) ImportLockFile.MAX_BYTES + 1]);
        }
        assertThrows(IOException.class, () -> ImportLockFile.read(lock));
    }

    @Test
    void failedReadStillAttestsTheRejectedBytes() throws Exception {
        Path lock = temp.resolve("invalid.lock.json");
        Files.writeString(lock, "{\"version\":1,\"imports\":[],\"extra\":true}\n");

        ImportLockFile.InvalidLockException error = assertThrows(
                ImportLockFile.InvalidLockException.class, () -> ImportLockFile.read(lock));

        assertTrue(error.sha256().matches("sha256:[0-9a-f]{64}"));
        assertTrue(error.getMessage().contains("unknown top-level"));
    }

    private ImportLockFile.Entry entry(String ontologyIri, String versionIri, String document,
            boolean direct) {
        return new ImportLockFile.Entry(ontologyIri, versionIri, document, "a".repeat(64), direct,
                temp.resolve(document));
    }

    private static Map<String, Object> row(String document) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ontology_iri", "https://example.org/a");
        row.put("version_iri", null);
        row.put("document", document);
        row.put("sha256", "a".repeat(64));
        row.put("direct", true);
        return row;
    }

    private void assertParseFails(String json, String message) {
        IOException error = assertThrows(IOException.class, () -> ImportLockFile.parse(
                json.getBytes(StandardCharsets.UTF_8), temp.resolve("imports.lock.json")));
        assertTrue(error.getMessage().contains(message), error::getMessage);
    }
}
