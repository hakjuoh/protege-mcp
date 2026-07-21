package io.github.hakjuoh.protege_mcp.sssom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SssomParserTest {

    @Test
    void canonicalRoundTripPreservesExtensionsUnicodeAndQuotedTsv() throws Exception {
        String authored = "#curie_map:\r\n"
                + "#  ex: https://example.org/\r\n"
                + "#mapping_set_id: https://example.org/mappings\r\n"
                + "#license: https://creativecommons.org/publicdomain/zero/1.0/\r\n"
                + "#creator_id:\r\n#  - urn:orcid:0000-0000\r\n"
                + "subject_id\tpredicate_id\tobject_id\tmapping_justification\tcomment\tx_vendor\r\n"
                + "ex:A\tskos:exactMatch\tex:B\tsemapv:ManualMappingCuration\t\"tab\tand\nnewline\"\t한글\r\n"
                + "ex:C\tskos:closeMatch\tex:D\tsemapv:ManualMappingCuration\tplain\t😀\r\n";
        byte[] body = authored.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xef;
        withBom[1] = (byte) 0xbb;
        withBom[2] = (byte) 0xbf;
        System.arraycopy(body, 0, withBom, 3, body.length);

        SssomDocument parsed = SssomParser.parse(withBom);
        byte[] canonical = SssomParser.render(parsed);
        SssomDocument reparsed = SssomParser.parse(canonical);

        assertEquals(2, parsed.records().size());
        assertEquals("tab\tand\nnewline", parsed.records().get(0).value("comment"));
        assertEquals("한글", parsed.records().get(0).value("x_vendor"));
        assertEquals(List.of("urn:orcid:0000-0000"), parsed.metadata().get("creator_id"));
        assertTrue(parsed.canonical().records().stream().allMatch(record ->
                record.mappingId().matches("sha256:[0-9a-f]{64}")));
        assertFalse(new String(canonical, StandardCharsets.UTF_8).contains("\r"));
        assertFalse(canonical.length >= 3 && canonical[0] == (byte) 0xef);
        assertEquals(parsed.canonical(), reparsed.canonical());
        assertArrayEquals(canonical, SssomParser.render(reparsed));
    }

    @Test
    void canonicalOutputOrdersHeadersRowsMetadataAndPrefixes() throws Exception {
        MappingRecord second = new MappingRecord(Map.of(
                "mapping_id", "urn:mapping:z", "subject_id", "https://x/Z",
                "predicate_id", "skos:closeMatch", "object_id", "https://x/Y",
                "mapping_justification", "semapv:ManualMappingCuration",
                "z_extension", "z", "a_extension", "a"));
        MappingRecord first = new MappingRecord(Map.of(
                "mapping_id", "urn:mapping:a", "subject_id", "https://x/A",
                "predicate_id", "skos:exactMatch", "object_id", "https://x/B",
                "mapping_justification", "semapv:ManualMappingCuration"));
        SssomDocument document = new SssomDocument(
                Map.of("zeta", "last", "alpha", "first"),
                Map.of("z", "https://z/", "a", "https://a/"),
                List.of("z_extension", "object_id", "predicate_id", "subject_id",
                        "mapping_id", "mapping_justification", "a_extension"), List.of(second, first));

        String rendered = new String(SssomParser.render(document), StandardCharsets.UTF_8);

        assertTrue(rendered.indexOf("#alpha") < rendered.indexOf("#zeta"));
        assertTrue(rendered.contains("#curie_map:"));
        assertTrue(rendered.contains("#  a:"), rendered);
        assertTrue(rendered.indexOf("#  a:") < rendered.indexOf("#  z:"));
        assertTrue(rendered.contains("mapping_id\tsubject_id\tpredicate_id\tobject_id\tmapping_justification\ta_extension\tz_extension"));
        assertTrue(rendered.indexOf("urn:mapping:a") < rendered.indexOf("urn:mapping:z"));
    }

    @Test
    void canonicalOutputOmitsRedundantBuiltInPrefixesAndRejectsRedefinitions() throws Exception {
        MappingRecord record = new MappingRecord(Map.of(
                "mapping_id", "urn:mapping:a", "subject_id", "owl:Thing",
                "predicate_id", "skos:relatedMatch", "object_id", "rdfs:Resource",
                "mapping_justification", "semapv:ManualMappingCuration"));
        SssomDocument redundant = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/"),
                Map.of("owl", SssomParser.BUILTIN_PREFIXES.get("owl")),
                record.cells().keySet().stream().toList(), List.of(record));

        String rendered = new String(SssomParser.render(redundant), StandardCharsets.UTF_8);
        assertFalse(rendered.contains("#curie_map:"));
        assertTrue(SssomValidator.validate(SssomParser.parse(rendered.getBytes(StandardCharsets.UTF_8)),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable()).valid());

        SssomDocument redefined = new SssomDocument(redundant.metadata(),
                Map.of("owl", "https://malicious.example/owl#"), redundant.columns(),
                redundant.records());
        assertThrows(IOException.class, () -> SssomParser.render(redefined));
    }

    @Test
    void exactDuplicateRowsAreCanonicalIdempotentButIdCollisionsRemainVisible() throws Exception {
        String duplicate = "mapping_id\tsubject_id\tpredicate_id\tobject_id\tmapping_justification\tx\n"
                + "urn:m:1\thttps://x/A\tskos:exactMatch\thttps://x/B\tsemapv:ManualMappingCuration\tone\n"
                + "urn:m:1\thttps://x/A\tskos:exactMatch\thttps://x/B\tsemapv:ManualMappingCuration\tone\n";
        assertEquals(1, SssomParser.parse(SssomParser.render(
                SssomParser.parse(duplicate.getBytes(StandardCharsets.UTF_8))))
                .records().size());

        String conflict = "mapping_id\tsubject_id\tpredicate_id\tobject_id\tmapping_justification\tx\n"
                + "urn:m:1\thttps://x/A\tskos:exactMatch\thttps://x/B\tsemapv:ManualMappingCuration\tone\n"
                + "urn:m:1\thttps://x/A\tskos:exactMatch\thttps://x/B\tsemapv:ManualMappingCuration\ttwo\n"
                + "urn:m:1\thttps://x/A\tskos:exactMatch\thttps://x/B\tsemapv:ManualMappingCuration\ttwo\n";
        SssomDocument parsed = SssomParser.parse(conflict.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, parsed.canonical().records().size(),
                "different rows sharing an id must remain available to the validator");
    }

    @Test
    void rejectsMalformedUtf8RowsHeadersAndCells() {
        assertThrows(IOException.class, () -> SssomParser.parse(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(IOException.class, () -> SssomParser.parse(
                "subject_id\tmapping_justification\nA\tB\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> SssomParser.parse(
                "subject_id\tsubject_id\tpredicate_id\tmapping_justification\nA\tA\tP\tJ\n"
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> SssomParser.parse(
                "subject_id\tpredicate_id\tmapping_justification\nA\tP\n"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void pathReadsRejectFinalSymlinks(@TempDir Path temp) throws Exception {
        Path target = temp.resolve("mappings.tsv");
        Files.writeString(target, "subject_id\tpredicate_id\tobject_id\tmapping_justification\n"
                + "https://x/A\tskos:exactMatch\thttps://x/B\tsemapv:ManualMappingCuration\n");
        Path link = temp.resolve("linked.tsv");
        try {
            Files.createSymbolicLink(link, target.getFileName());
            IOException symlink = assertThrows(IOException.class, () -> SssomParser.parse(link));
            assertTrue(symlink.getMessage().contains("symbolic"));
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + unsupported);
        }
    }

    @Test
    void pathReadsRejectOversizedSparseFiles(@TempDir Path temp) throws Exception {
        Path oversized = temp.resolve("oversized.tsv");
        try (FileChannel channel = FileChannel.open(oversized,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(SssomParser.MAX_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        IOException tooLarge = assertThrows(IOException.class, () -> SssomParser.parse(oversized));
        assertTrue(tooLarge.getMessage().contains("exceeds"));
    }

    @Test
    void pathReadsDoNotRequireWritePermissionInTheSourceDirectory(@TempDir Path temp)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(temp).supportsFileAttributeView("posix"));
        Path source = temp.resolve("mappings.tsv");
        Files.writeString(source, "subject_id\tpredicate_id\tobject_id\tmapping_justification\n"
                + "https://x/A\tskos:exactMatch\thttps://x/B\tsemapv:ManualMappingCuration\n");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(temp);
        try {
            Files.setPosixFilePermissions(temp, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assertEquals(1, SssomParser.parse(source).records().size());
        } finally {
            Files.setPosixFilePermissions(temp, original);
        }
    }

    @Test
    void pathReadsPinIdentityAcrossAbaContentSwap(@TempDir Path temp) throws Exception {
        String header = "mapping_id\tpredicate_id\tmapping_justification\n";
        Path target = temp.resolve("mappings.tsv");
        Path backup = temp.resolve("original.tsv");
        Path alternate = temp.resolve("alternate.tsv");
        Path consumed = temp.resolve("consumed.tsv");
        Files.writeString(target, header + "urn:m:a\tskos:exactMatch\tsemapv:ManualMappingCuration\n");
        Files.writeString(alternate,
                header + "urn:m:b\tskos:closeMatch\tsemapv:ManualMappingCuration\n");

        SssomDocument parsed = SssomParser.parse(target,
                new SssomParser.ReadInterlock() {
                    @Override
                    public void beforeOpen() throws IOException {
                        Files.move(target, backup);
                        Files.move(alternate, target);
                    }

                    @Override
                    public void afterRead() throws IOException {
                        Files.move(target, consumed);
                        Files.move(backup, target);
                    }
                });

        assertEquals("urn:m:a", parsed.records().get(0).mappingId());
        assertTrue(Files.readString(target).contains("urn:m:a"));
    }

    @Test
    void parsesOfficialStyleYamlMetadataAndLiteralRows() throws Exception {
        String fixture = "#curie_map:\n#  EX: https://example.org/\n"
                + "#mapping_set_id: https://example.org/literals\n"
                + "#license: https://creativecommons.org/licenses/by/4.0/\n"
                + "#creator_id:\n#  - https://orcid.org/0000-0000-0000-0001\n"
                + "subject_label\tsubject_type\tpredicate_id\tobject_id\tmapping_justification\n"
                + "alice\trdfs literal\tskos:closeMatch\tEX:1\tsemapv:ManualMappingCuration\n";

        SssomDocument document = SssomParser.parse(fixture.getBytes(StandardCharsets.UTF_8));

        assertEquals("alice", document.records().get(0).value("subject_label"));
        assertEquals(List.of("https://orcid.org/0000-0000-0000-0001"),
                document.metadata().get("creator_id"));
        assertArrayEquals(SssomParser.render(document),
                SssomParser.render(SssomParser.parse(SssomParser.render(document))));
    }

    @Test
    void limitsIncludeGeneratedIdMetadataNodesAndProgrammaticDocuments() {
        List<String> columns = new java.util.ArrayList<>();
        columns.add("predicate_id");
        columns.add("mapping_justification");
        for (int index = 0; index < SssomParser.MAX_COLUMNS - 2; index++) {
            columns.add("x" + index);
        }
        String header = String.join("\t", columns) + "\n";
        assertThrows(IOException.class,
                () -> SssomParser.parse(header.getBytes(StandardCharsets.UTF_8)));

        List<String> tooMany = new java.util.ArrayList<>(columns);
        tooMany.add("mapping_id");
        SssomDocument oversized = new SssomDocument(Map.of(), Map.of(), tooMany, List.of());
        assertThrows(IOException.class, () -> SssomParser.render(oversized));

        Map<String, String> prefixes = new LinkedHashMap<>();
        for (int index = 0; index <= SssomParser.MAX_METADATA_ENTRIES; index++) {
            prefixes.put("p" + index, "https://example.org/" + index + "/");
        }
        String yaml = "#curie_map: " + new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(prefixes) + "\n"
                + "predicate_id\tmapping_justification\n";
        assertThrows(IOException.class,
                () -> SssomParser.parse(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMaximumSizedMetadataScalarsBeforeYamlMaterialization() {
        byte[] hostile = new byte[Math.toIntExact(SssomParser.MAX_BYTES)];
        java.util.Arrays.fill(hostile, (byte) 'x');
        hostile[0] = '#';
        hostile[1] = 'a';
        hostile[2] = ':';
        hostile[3] = ' ';

        IOException rejected = assertThrows(IOException.class, () -> SssomParser.parse(hostile));
        assertTrue(rejected.getMessage().contains("metadata scalar line"));
    }

    @Test
    void rejectsYamlAliasesAndCanonicalizesCollisionOrderingByCells() throws Exception {
        String aliased = "#mapping_set_id: &id https://example.org/mappings\n"
                + "#license: *id\n"
                + "predicate_id\tmapping_justification\n";
        assertThrows(IOException.class,
                () -> SssomParser.parse(aliased.getBytes(StandardCharsets.UTF_8)));
        String anchored = "#mapping_set_id: &id https://example.org/mappings\n"
                + "#license: https://example.org/license\n"
                + "predicate_id\tmapping_justification\n";
        assertThrows(IOException.class,
                () -> SssomParser.parse(anchored.getBytes(StandardCharsets.UTF_8)));
        String tagged = "#mapping_set_id: !!str https://example.org/mappings\n"
                + "#license: https://example.org/license\n"
                + "predicate_id\tmapping_justification\n";
        assertThrows(IOException.class,
                () -> SssomParser.parse(tagged.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> firstCells = new LinkedHashMap<>();
        firstCells.put("mapping_id", "urn:m:1");
        firstCells.put("predicate_id", "skos:exactMatch");
        firstCells.put("mapping_justification", "semapv:ManualMappingCuration");
        firstCells.put("x", "two");
        Map<String, String> secondCells = new LinkedHashMap<>();
        secondCells.put("x", "one");
        secondCells.put("mapping_justification", "semapv:ManualMappingCuration");
        secondCells.put("predicate_id", "skos:exactMatch");
        secondCells.put("mapping_id", "urn:m:1");
        SssomDocument document = new SssomDocument(Map.of(), Map.of(),
                List.of("mapping_id", "predicate_id", "mapping_justification", "x"),
                List.of(new MappingRecord(firstCells), new MappingRecord(secondCells)));
        SssomDocument reversed = new SssomDocument(Map.of(), Map.of(), document.columns(),
                List.of(new MappingRecord(secondCells), new MappingRecord(firstCells)));
        assertArrayEquals(SssomParser.render(document), SssomParser.render(reversed));

        SssomDocument reserved = new SssomDocument(Map.of("curie_map", Map.of("x", "https://x/")),
                Map.of("y", "https://y/"), document.columns(), document.records());
        assertThrows(IOException.class, () -> SssomParser.render(reserved));
    }

    @Test
    void rejectsActualRowAndCellLimitsDuringStreaming() {
        String oversizedCell = "mapping_id\tpredicate_id\tmapping_justification\tx\n"
                + "urn:m:1\tskos:exactMatch\tsemapv:ManualMappingCuration\t"
                + "x".repeat(SssomParser.MAX_CELL_BYTES + 1) + "\n";
        IOException cellLimit = assertThrows(IOException.class,
                () -> SssomParser.parse(oversizedCell.getBytes(StandardCharsets.UTF_8)));
        assertTrue(cellLimit.getMessage().contains("cell exceeds"));

        StringBuilder rows = new StringBuilder(
                "mapping_id\tpredicate_id\tmapping_justification\n");
        for (int index = 0; index <= SssomParser.MAX_ROWS; index++) {
            rows.append("urn:m:").append(index)
                    .append("\tskos:exactMatch\tsemapv:ManualMappingCuration\n");
        }
        assertThrows(IOException.class,
                () -> SssomParser.parse(rows.toString().getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class,
                () -> SssomParser.requireCellBudget(100_000, 128));
        assertThrows(IOException.class,
                () -> SssomParser.requireCellBudget(100_000, 11),
                "generated mapping_id must count toward the effective column budget");
        assertDoesNotThrow(() -> SssomParser.requireCellBudget(100_000, 4));
    }

    @Test
    void wrapsMalformedQuotedStreamingFailuresAsIoExceptions() {
        String malformed = "mapping_id\tpredicate_id\tmapping_justification\n"
                + "urn:m:1\tskos:exactMatch\t\"unterminated\n";
        assertThrows(IOException.class,
                () -> SssomParser.parse(malformed.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsInteroperableBlankRowsAndSpreadsheetPaddedMetadata() throws Exception {
        String authored = "#curie_map:\t\t\n"
                + "#  ex: https://example.org/\t\t\n"
                + "#mapping_set_id: https://example.org/mappings\t\t\n"
                + "#license: https://creativecommons.org/licenses/by/4.0/\t\t\n"
                + "subject_id\tpredicate_id\tobject_id\tmapping_justification\n"
                + "ex:A\tskos:exactMatch\tex:B\tsemapv:ManualMappingCuration\n"
                + "\n"
                + "ex:C\tskos:closeMatch\tex:D\tsemapv:ManualMappingCuration\n";

        SssomDocument parsed = SssomParser.parse(authored.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, parsed.records().size());
        assertEquals("https://example.org/", parsed.prefixMap().get("ex"));
        String canonical = new String(SssomParser.render(parsed), StandardCharsets.UTF_8);
        assertFalse(canonical.contains("\t\t\n#"));
    }

    @Test
    void preservesNonStandardPrefixMapAsExtensionMetadata() throws Exception {
        String authored = "#curie_map:\n#  ex: https://example.org/\n"
                + "#prefix_map:\n#  legacy: https://legacy.example/\n"
                + "#mapping_set_id: https://example.org/mappings\n"
                + "#license: https://creativecommons.org/licenses/by/4.0/\n"
                + "subject_id\tpredicate_id\tobject_id\tmapping_justification\n"
                + "ex:A\tskos:exactMatch\tex:B\tsemapv:ManualMappingCuration\n";

        SssomDocument parsed = SssomParser.parse(authored.getBytes(StandardCharsets.UTF_8));
        assertEquals(Map.of("legacy", "https://legacy.example/"),
                parsed.metadata().get("prefix_map"));
        assertEquals(Map.of("ex", "https://example.org/"), parsed.prefixMap());
        SssomDocument reparsed = SssomParser.parse(SssomParser.render(parsed));
        assertEquals(parsed.metadata(), reparsed.metadata());
        assertEquals(parsed.prefixMap(), reparsed.prefixMap());
    }

    @Test
    void parsesPinnedOfficialV10Fixtures() throws Exception {
        for (String fixture : List.of("/sssom/v1.0-literals.sssom.tsv",
                "/sssom/v1.0-no-term-found.sssom.tsv")) {
            byte[] bytes;
            try (java.io.InputStream input = getClass().getResourceAsStream(fixture)) {
                assertTrue(input != null, fixture);
                bytes = input.readAllBytes();
            }
            SssomDocument document = SssomParser.parse(bytes);
            assertArrayEquals(SssomParser.render(document),
                    SssomParser.render(SssomParser.parse(SssomParser.render(document))));
        }
    }

    @Test
    void validatesIndependentFullExtensionAndVersionBoundaryFixtures() throws Exception {
        SssomDocument full = fixture("/sssom/v1.0-full.sssom.tsv");
        SssomValidator.Report fullReport = SssomValidator.validate(full,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertTrue(fullReport.valid(), () -> fullReport.findings().toString());
        assertEquals(44, full.records().get(0).cells().size());

        SssomDocument extensions = fixture("/sssom/v1.0-extensions.sssom.tsv");
        SssomValidator.Report extensionReport = SssomValidator.validate(extensions,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertTrue(extensionReport.valid(), () -> extensionReport.findings().toString());
        assertTrue(extensionReport.findings().stream()
                .anyMatch(finding -> "extension_type_unsupported".equals(finding.code())));
        assertEquals("kept exactly", extensions.records().get(0).value("x_undefined"));

        SssomDocument future = fixture("/sssom/v1.1-rejected.sssom.tsv");
        SssomValidator.Report futureReport = SssomValidator.validate(future,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertFalse(futureReport.valid());
        assertTrue(futureReport.findings().stream()
                .anyMatch(finding -> "sssom_version_unsupported".equals(finding.code())));
        assertEquals("owl annotation property", SssomParser.parse(
                SssomParser.render(future)).records().get(0).value("predicate_type"));
    }

    private SssomDocument fixture(String resource) throws Exception {
        try (java.io.InputStream input = getClass().getResourceAsStream(resource)) {
            assertTrue(input != null, resource);
            return SssomParser.parse(input.readAllBytes());
        }
    }

}
