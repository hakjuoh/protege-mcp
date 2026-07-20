package io.github.hakjuoh.protege_mcp.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineCatalogTest {

    @TempDir
    Path temp;

    @Test
    void resolvesNestedXmlBasesAndNextCatalogWithoutDereferencing() throws Exception {
        String xml = "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\" "
                + "xml:base=\"a/\"><group xml:base=\"b/\">"
                + "<uri name=\"https://example.org/local\" uri=\"doc.owl\"/>"
                + "<uri name=\"https://example.org/remote\" uri=\"https://invalid.example/x.owl\"/>"
                + "<nextCatalog catalog=\"next/catalog.xml\"/>"
                + "</group></catalog>";
        OfflineCatalog.Document catalog = OfflineCatalog.parse(xml.getBytes(StandardCharsets.UTF_8),
                temp.resolve("catalog-v001.xml"));

        assertEquals(temp.resolve("a/b/doc.owl").toUri(), catalog.entries().get(0).resolved());
        assertEquals("https", catalog.entries().get(1).resolved().getScheme());
        assertTrue(catalog.nextCatalogs().get(0).toString().endsWith("a/b/next/catalog.xml"));
        assertEquals(0, catalog.errors().size());
    }

    @Test
    void retainsInvalidAndDuplicateEntriesAsDiagnostics() throws Exception {
        String xml = "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                + "<uri name=\"https://example.org/a\" uri=\"http://[bad\"/>"
                + "<uri name=\"https://example.org/a\" uri=\"a.owl\"/>"
                + "<uri name=\"\" uri=\"missing-name.owl\"/>"
                + "<nextCatalog catalog=\"\"/>"
                + "</catalog>";
        OfflineCatalog.Document catalog = OfflineCatalog.parse(xml.getBytes(StandardCharsets.UTF_8),
                temp.resolve("catalog.xml"));

        assertEquals(2, catalog.entries().size());
        assertNull(catalog.entries().get(0).resolved());
        assertTrue(catalog.errors().stream().anyMatch(error -> error.contains("valid URI")));
        assertTrue(catalog.errors().stream().anyMatch(error -> error.contains("duplicate")));
        assertTrue(catalog.errors().stream().anyMatch(error -> error.contains("missing name/uri")));
        assertTrue(catalog.errors().stream().anyMatch(error -> error.contains("missing catalog")));
    }

    @Test
    void refusesDoctypeAndOversizedDocuments() {
        String xxe = "<!DOCTYPE catalog [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">&xxe;</catalog>";
        assertThrows(IOException.class, () -> OfflineCatalog.parse(
                xxe.getBytes(StandardCharsets.UTF_8), temp.resolve("catalog.xml")));
        assertThrows(IOException.class, () -> OfflineCatalog.parse(
                new byte[OfflineCatalog.MAX_BYTES + 1], temp.resolve("catalog.xml")));
    }

    @Test
    void boundedReadRejectsOversizedCatalog() throws Exception {
        Path path = temp.resolve("catalog.xml");
        try (var output = Files.newOutputStream(path)) {
            output.write(new byte[OfflineCatalog.MAX_BYTES + 1]);
        }
        assertThrows(IOException.class, () -> OfflineCatalog.read(path));
    }
}
