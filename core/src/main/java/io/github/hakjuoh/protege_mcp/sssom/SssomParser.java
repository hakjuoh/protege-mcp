package io.github.hakjuoh.protege_mcp.sssom;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;

/** Bounded strict UTF-8 parser and deterministic renderer for SSSOM 1.0 TSV. */
public final class SssomParser {

    public static final long MAX_BYTES = 64L * 1024L * 1024L;
    public static final int MAX_ROWS = 100_000;
    public static final int MAX_COLUMNS = 128;
    public static final long MAX_CELLS = 1_000_000;
    public static final int MAX_CELL_BYTES = 64 * 1024;
    public static final int MAX_METADATA_ENTRIES = 256;
    private static final long MAX_METADATA_BYTES =
            (long) MAX_METADATA_ENTRIES * (MAX_CELL_BYTES + 1L);
    static final Map<String, String> BUILTIN_PREFIXES = Map.of(
            "owl", "http://www.w3.org/2002/07/owl#",
            "rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
            "semapv", "https://w3id.org/semapv/vocab/",
            "skos", "http://www.w3.org/2004/02/skos/core#",
            "sssom", "https://w3id.org/sssom/",
            "xsd", "http://www.w3.org/2001/XMLSchema#",
            "linkml", "https://w3id.org/linkml/");

    private static final Pattern COLUMN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]{0,127}$");
    private static final ObjectMapper YAML = yamlMapper();
    private static final CSVFormat TSV = CSVFormat.DEFAULT.builder()
            .setDelimiter('\t').setQuote('"').setRecordSeparator("\n")
            .setIgnoreEmptyLines(true).build();

    private SssomParser() {
    }

    public static SssomDocument parse(Path path) throws IOException {
        return readStable(path).document();
    }

    static SssomDocument parse(Path path, ReadInterlock interlock) throws IOException {
        return readStable(path, interlock).document();
    }

    /** Capture the exact verified bytes and parsed document from one hard-link-pinned read. */
    public static StableRead readStable(Path path) throws IOException {
        return readStable(path, ReadInterlock.NOOP);
    }

    static StableRead readStable(Path path, ReadInterlock interlock) throws IOException {
        if (path == null) throw new IllegalArgumentException("SSSOM path is required");
        if (interlock == null) throw new IllegalArgumentException("read interlock is required");
        Path requested = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(requested)) {
            throw new IOException("SSSOM files must not be symbolic links");
        }
        Path real = requested.toRealPath();
        Path parent = real.getParent();
        BasicFileAttributes parentBefore = attributes(parent, false);
        BasicFileAttributes before = attributes(real);
        if (before.size() > MAX_BYTES) throw new IOException("SSSOM file exceeds " + MAX_BYTES + " bytes");
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        byte[] bytes;
        Path snapshotDirectory = createSnapshotDirectory(parent);
        Path snapshot = snapshotDirectory.resolve("source.snapshot");
        try {
            restrictOwnerOnly(snapshotDirectory);
            try {
                Files.createLink(snapshot, real);
            } catch (IOException | UnsupportedOperationException unavailable) {
                throw new IOException("Stable SSSOM reads require a same-filesystem hard-link snapshot",
                        unavailable);
            }
            BasicFileAttributes pinned = attributes(snapshot);
            if (!Objects.equals(before.fileKey(), pinned.fileKey()) || before.size() != pinned.size()
                    || !before.lastModifiedTime().equals(pinned.lastModifiedTime())) {
                throw new IOException("SSSOM hard-link snapshot did not pin the captured source identity");
            }
            try (SeekableByteChannel channel = Files.newByteChannel(snapshot, options)) {
                interlock.beforeOpen();
                bytes = Channels.newInputStream(channel).readNBytes(Math.toIntExact(MAX_BYTES + 1));
            }
            interlock.afterRead();
        } finally {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(snapshotDirectory);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("SSSOM file exceeds " + MAX_BYTES + " bytes");
        BasicFileAttributes after = attributes(real);
        BasicFileAttributes parentAfter = attributes(parent, false);
        if (!requested.toRealPath().equals(real) || !real.toRealPath().equals(real)
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(parentBefore.fileKey(), parentAfter.fileKey())) {
            throw new IOException("SSSOM file identity changed while it was read");
        }
        byte[] expectedDigest = sha256(bytes);
        BasicFileAttributes verifyBefore = attributes(real);
        byte[] verifiedDigest = digestFile(real);
        BasicFileAttributes verifyAfter = attributes(real);
        BasicFileAttributes parentVerifyAfter = attributes(parent, false);
        if (!Objects.equals(before.fileKey(), verifyBefore.fileKey())
                || !Objects.equals(verifyBefore.fileKey(), verifyAfter.fileKey())
                || !Objects.equals(parentBefore.fileKey(), parentVerifyAfter.fileKey())
                || !requested.toRealPath().equals(real)
                || verifyBefore.size() != verifyAfter.size()
                || !verifyBefore.lastModifiedTime().equals(verifyAfter.lastModifiedTime())
                || !Arrays.equals(expectedDigest, verifiedDigest)) {
            throw new IOException("SSSOM file content changed during stable-read verification");
        }
        return new StableRead(parse(bytes), bytes);
    }

    public record StableRead(SssomDocument document, byte[] bytes) {
        public StableRead {
            if (document == null || bytes == null) {
                throw new IllegalArgumentException("stable SSSOM read must be complete");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public static SssomDocument parse(byte[] input) throws IOException {
        if (input == null) throw new IllegalArgumentException("SSSOM bytes are required");
        if (input.length > MAX_BYTES) throw new IOException("SSSOM input exceeds " + MAX_BYTES + " bytes");
        boolean bom = input.length >= 3 && input[0] == (byte) 0xef
                && input[1] == (byte) 0xbb && input[2] == (byte) 0xbf;
        preflightEncodedLimits(input, bom ? 3 : 0);
        byte[] bytes = bom ? Arrays.copyOfRange(input, 3, input.length) : input.clone();
        String text = decode(bytes);
        Preamble preamble = preamble(text);
        List<String> columns = new ArrayList<>();
        List<MappingRecord> records = new ArrayList<>();
        try (CSVParser parser = new CSVParser(new StringReader(preamble.body), TSV)) {
            java.util.Iterator<CSVRecord> rows = parser.iterator();
            if (!rows.hasNext()) throw new IOException("SSSOM TSV is missing its header");
            CSVRecord headerRecord = rows.next();
            if (headerRecord.size() == 0 || headerRecord.size() > MAX_COLUMNS) {
                throw new IOException("SSSOM column count is outside 1.." + MAX_COLUMNS);
            }
            Set<String> unique = new LinkedHashSet<>();
            for (String raw : headerRecord) {
                String column = raw.trim();
                if (!COLUMN.matcher(column).matches()) {
                    throw new IOException("Invalid SSSOM column name: " + column);
                }
                if (!unique.add(column)) throw new IOException("Duplicate SSSOM column: " + column);
                columns.add(column);
            }
            for (String required : List.of("predicate_id", "mapping_justification")) {
                if (!unique.contains(required)) {
                    throw new IOException("SSSOM header is missing " + required);
                }
            }
            int authoredColumns = headerRecord.size();
            int effectiveColumns = authoredColumns;
            if (!unique.contains("mapping_id")) {
                if (authoredColumns == MAX_COLUMNS) {
                    throw new IOException("SSSOM cannot add mapping_id beyond "
                            + MAX_COLUMNS + " columns");
                }
                columns.add(0, "mapping_id");
                effectiveColumns++;
            }
            int index = 0;
            while (rows.hasNext()) {
                CSVRecord row = rows.next();
                index++;
                if (index > MAX_ROWS) {
                    throw new IOException("SSSOM row count exceeds " + MAX_ROWS);
                }
                requireCellBudget(index, effectiveColumns);
                if (row.size() != authoredColumns) {
                    throw new IOException("SSSOM row " + index + " has " + row.size()
                            + " cells; expected " + authoredColumns);
                }
                Map<String, String> cells = new LinkedHashMap<>();
                int offset = columns.size() == authoredColumns ? 0 : 1;
                for (int cell = 0; cell < row.size(); cell++) {
                    String value = row.get(cell);
                    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_CELL_BYTES) {
                        throw new IOException("SSSOM row " + index + " cell exceeds "
                                + MAX_CELL_BYTES + " bytes");
                    }
                    cells.put(columns.get(cell + offset), value);
                }
                records.add(new MappingRecord(cells));
            }
        } catch (IllegalArgumentException | UncheckedIOException invalid) {
            throw new IOException("SSSOM TSV could not be parsed", invalid);
        }
        SssomDocument parsed = new SssomDocument(preamble.metadata, preamble.prefixMap,
                columns, records);
        return parsed;
    }

    public static byte[] render(SssomDocument source) throws IOException {
        if (source == null) throw new IllegalArgumentException("SSSOM document is required");
        SssomDocument document = source.canonical();
        validateDocumentLimits(document);
        if (document.metadata().containsKey("curie_map")) {
            throw new IOException("SSSOM metadata must not duplicate the reserved CURIE map");
        }
        StringBuilder output = new StringBuilder();
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, String> authoredPrefixes = new LinkedHashMap<>(document.prefixMap());
        authoredPrefixes.entrySet().removeIf(entry ->
                entry.getValue().equals(BUILTIN_PREFIXES.get(entry.getKey())));
        if (!authoredPrefixes.isEmpty()) {
            metadata.put("curie_map", authoredPrefixes);
        }
        metadata.putAll(document.metadata());
        if (!metadata.isEmpty()) {
            rejectYamlAliases(YAML.writeValueAsBytes(metadata));
            String yaml = YAML.writeValueAsString(metadata);
            String[] lines = yaml.split("\\R", -1);
            int count = lines.length > 0 && lines[lines.length - 1].isEmpty()
                    ? lines.length - 1 : lines.length;
            for (int index = 0; index < count; index++) {
                output.append('#').append(lines[index]).append('\n');
            }
        }
        StringWriter table = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(table, TSV)) {
            printer.printRecord(document.columns());
            for (MappingRecord record : document.records()) {
                List<String> row = document.columns().stream().map(record::value).toList();
                for (String value : row) {
                    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_CELL_BYTES) {
                        throw new IOException("SSSOM cell exceeds " + MAX_CELL_BYTES + " bytes");
                    }
                }
                printer.printRecord(row);
            }
        }
        output.append(table);
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Canonical SSSOM exceeds " + MAX_BYTES + " bytes");
        return bytes;
    }

    private static Preamble preamble(String text) throws IOException {
        List<String> yamlLines = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            int end = text.indexOf('\n', position);
            if (end < 0) end = text.length();
            String line = text.substring(position, end);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isBlank()) {
                position = end < text.length() ? end + 1 : end;
                continue;
            }
            if (!line.startsWith("#")) break;
            yamlLines.add(stripTrailingTabs(line.substring(1)));
            position = end < text.length() ? end + 1 : end;
        }
        Map<String, Object> metadata = parseMetadata(yamlLines);
        Map<String, String> prefixes = prefixMap(metadata.remove("curie_map"));
        validateMetadataBudget(metadata, prefixes);
        return new Preamble(metadata, prefixes, text.substring(position));
    }

    private static void preflightEncodedLimits(byte[] input, int offset) throws IOException {
        int position = offset;
        long metadataBytes = 0;
        while (position < input.length) {
            int end = position;
            while (end < input.length && input[end] != '\n') end++;
            int contentEnd = end > position && input[end - 1] == '\r' ? end - 1 : end;
            int lineBytes = contentEnd - position;
            boolean blank = lineBytes == 0;
            boolean metadata = !blank && input[position] == '#';
            if (!blank && !metadata) break;
            if (metadata && lineBytes - 1 > MAX_CELL_BYTES) {
                throw new IOException("SSSOM metadata scalar line exceeds "
                        + MAX_CELL_BYTES + " encoded bytes");
            }
            metadataBytes += (long) lineBytes + (end < input.length ? 1 : 0);
            if (metadataBytes > MAX_METADATA_BYTES) {
                throw new IOException("SSSOM metadata preamble exceeds its encoded byte budget");
            }
            position = end < input.length ? end + 1 : end;
        }
        preflightTsvCells(input, position);
    }

    private static void preflightTsvCells(byte[] input, int offset) throws IOException {
        boolean atCellStart = true;
        boolean quoted = false;
        boolean quotedCell = false;
        int encodedBytes = 0;
        for (int index = offset; index < input.length; index++) {
            byte current = input[index];
            if (atCellStart && current == '"') {
                atCellStart = false;
                quoted = true;
                quotedCell = true;
                encodedBytes = 1;
            } else if (quoted) {
                encodedBytes++;
                if (current == '"') {
                    if (index + 1 < input.length && input[index + 1] == '"') {
                        encodedBytes++;
                        index++;
                    } else {
                        quoted = false;
                    }
                }
            } else if (current == '\t' || current == '\n' || current == '\r') {
                atCellStart = true;
                quotedCell = false;
                encodedBytes = 0;
            } else {
                atCellStart = false;
                encodedBytes++;
            }
            int limit = quotedCell ? MAX_CELL_BYTES * 2 + 2 : MAX_CELL_BYTES;
            if (encodedBytes > limit) {
                throw new IOException("SSSOM encoded cell exceeds its safe pre-parse byte budget");
            }
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("SSSOM input is not strict UTF-8", invalid);
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return attributes(path, true);
    }

    private static BasicFileAttributes attributes(Path path, boolean regular) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if ((regular && !attributes.isRegularFile()) || (!regular && !attributes.isDirectory())
                || attributes.fileKey() == null) {
            throw new IOException("SSSOM source identity is unavailable");
        }
        return attributes;
    }

    private static void restrictOwnerOnly(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    private static Path createSnapshotDirectory(Path sourceParent) throws IOException {
        FileStore sourceStore = Files.getFileStore(sourceParent);
        List<Path> candidates = new ArrayList<>();
        for (Path candidate = sourceParent; candidate != null; candidate = candidate.getParent()) {
            candidates.add(candidate);
        }
        String temporary = System.getProperty("java.io.tmpdir");
        if (temporary != null && !temporary.isBlank()) {
            Path systemTemporary = Path.of(temporary).toAbsolutePath().normalize();
            if (!candidates.contains(systemTemporary)) candidates.add(systemTemporary);
        }
        IOException failure = new IOException(
                "No writable same-filesystem directory is available for a stable SSSOM snapshot");
        for (Path candidate : candidates) {
            Path directory = null;
            try {
                if (!sourceStore.equals(Files.getFileStore(candidate))) continue;
                directory = Files.createTempDirectory(candidate, "protege-mcp-sssom-read-");
                restrictOwnerOnly(directory);
                return directory;
            } catch (IOException | SecurityException unavailable) {
                if (directory != null) {
                    try {
                        Files.deleteIfExists(directory);
                    } catch (IOException cleanup) {
                        unavailable.addSuppressed(cleanup);
                    }
                }
                failure.addSuppressed(unavailable);
            }
        }
        throw failure;
    }

    private static Map<String, Object> parseMetadata(List<String> lines) throws IOException {
        if (lines.isEmpty()) return new LinkedHashMap<>();
        int indentation = lines.stream().filter(line -> !line.isBlank())
                .mapToInt(SssomParser::leadingSpaces).min().orElse(0);
        StringBuilder yaml = new StringBuilder();
        for (String line : lines) {
            yaml.append(line.substring(Math.min(indentation, leadingSpaces(line)))).append('\n');
        }
        byte[] bytes = yaml.toString().getBytes(StandardCharsets.UTF_8);
        rejectYamlAliases(bytes);
        Map<String, Object> parsed = YAML.readValue(bytes,
                new TypeReference<LinkedHashMap<String, Object>>() { });
        return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
    }

    private static Map<String, String> prefixMap(Object value) throws IOException {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) throw new IOException("SSSOM curie_map must be a map");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String prefix)
                    || !(entry.getValue() instanceof String iri)) {
                throw new IOException("SSSOM curie_map entries must be strings");
            }
            requirePrefix(prefix, iri);
            if (result.putIfAbsent(prefix, iri) != null) {
                throw new IOException("Duplicate SSSOM prefix: " + prefix);
            }
        }
        return result;
    }

    private static void requirePrefix(String prefix, String iri) throws IOException {
        if (!prefix.matches("^[A-Za-z][A-Za-z0-9._-]{0,63}$") || iri == null || iri.isBlank()
                || iri.length() > 4096) {
            throw new IOException("Invalid SSSOM prefix declaration: " + prefix);
        }
        try {
            if (!java.net.URI.create(iri).isAbsolute()) {
                throw new IOException("SSSOM prefix IRI must be absolute: " + prefix);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid SSSOM prefix IRI: " + prefix, invalid);
        }
    }

    private static void validateDocumentLimits(SssomDocument document) throws IOException {
        if (document.records().size() > MAX_ROWS) throw new IOException("SSSOM row count exceeds " + MAX_ROWS);
        if (document.columns().isEmpty() || document.columns().size() > MAX_COLUMNS) {
            throw new IOException("SSSOM column count is outside 1.." + MAX_COLUMNS);
        }
        if ((long) document.records().size() * document.columns().size() > MAX_CELLS) {
            throw new IOException("SSSOM cell count exceeds " + MAX_CELLS);
        }
        for (String column : document.columns()) {
            if (!COLUMN.matcher(column).matches()) throw new IOException("Invalid SSSOM column name: " + column);
        }
        validateMetadataBudget(document.metadata(), document.prefixMap());
        long conservativeBytes = 2L * metadataScalarBytes(document.metadata())
                + 2L * metadataScalarBytes(document.prefixMap());
        conservativeBytes += (long) (document.records().size() + 1)
                * (document.columns().size() + 2L);
        for (MappingRecord record : document.records()) {
            for (String value : record.cells().values()) {
                int bytes = value.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_CELL_BYTES) throw new IOException("SSSOM cell exceeds " + MAX_CELL_BYTES + " bytes");
                conservativeBytes += 2L * bytes + 2L;
                if (conservativeBytes > MAX_BYTES) {
                    throw new IOException("Canonical SSSOM exceeds " + MAX_BYTES + " bytes");
                }
            }
        }
    }

    static void requireCellBudget(long rows, int columns) throws IOException {
        if (rows < 0 || columns < 0 || rows > MAX_CELLS / Math.max(1, columns)) {
            throw new IOException("SSSOM cell count exceeds " + MAX_CELLS);
        }
    }

    private static void validateMetadataBudget(Map<String, ?> metadata,
            Map<String, String> prefixes) throws IOException {
        long nodes = metadataNodes(metadata) + metadataNodes(prefixes);
        if (nodes > MAX_METADATA_ENTRIES) {
            throw new IOException("SSSOM metadata exceeds " + MAX_METADATA_ENTRIES + " nodes");
        }
        if (metadataScalarBytes(metadata) + metadataScalarBytes(prefixes)
                > (long) MAX_METADATA_ENTRIES * MAX_CELL_BYTES) {
            throw new IOException("SSSOM metadata scalar budget exceeded");
        }
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            if (!COLUMN.matcher(entry.getKey()).matches()) {
                throw new IOException("Invalid SSSOM metadata key: " + entry.getKey());
            }
        }
        for (Map.Entry<String, String> prefix : prefixes.entrySet()) {
            requirePrefix(prefix.getKey(), prefix.getValue());
            String builtin = BUILTIN_PREFIXES.get(prefix.getKey());
            if (builtin != null && !builtin.equals(prefix.getValue())) {
                throw new IOException("SSSOM built-in prefix must not be redefined: " + prefix.getKey());
            }
        }
    }

    private static long metadataNodes(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size() + map.values().stream().mapToLong(SssomParser::metadataNodes).sum();
        }
        if (value instanceof List<?> list) {
            return list.size() + list.stream().mapToLong(SssomParser::metadataNodes).sum();
        }
        return 1;
    }

    private static long metadataScalarBytes(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            long total = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += entry.getKey().toString().getBytes(StandardCharsets.UTF_8).length;
                total += metadataScalarBytes(entry.getValue());
            }
            return total;
        }
        if (value instanceof List<?> list) {
            long total = 0;
            for (Object item : list) total += metadataScalarBytes(item);
            return total;
        }
        int bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CELL_BYTES) throw new IOException("SSSOM metadata scalar exceeds " + MAX_CELL_BYTES + " bytes");
        return bytes;
    }

    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
        return spaces;
    }

    private static String stripTrailingTabs(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == '\t') end--;
        return line.substring(0, end);
    }

    private static ObjectMapper yamlMapper() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setNestingDepthLimit(20);
        loaderOptions.setCodePointLimit((int) MAX_BYTES);
        YAMLFactory factory = YAMLFactory.builder().loaderOptions(loaderOptions)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20)
                        .maxStringLength(MAX_CELL_BYTES).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build();
        return new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    private static void rejectYamlAliases(byte[] bytes) throws IOException {
        rejectYamlNodeFeatures(bytes);
        int tokens = 0;
        try (JsonParser parser = YAML.getFactory().createParser(bytes)) {
            while (parser.nextToken() != null) {
                if (++tokens > MAX_METADATA_ENTRIES * 4 + 16) {
                    throw new IOException("SSSOM metadata token budget exceeded");
                }
                if (parser instanceof YAMLParser yaml) {
                    if (yaml.isCurrentAlias() || yaml.getCurrentAnchor() != null) {
                        throw new IOException("YAML aliases and anchors are not supported in SSSOM metadata");
                    }
                    if (yaml.getTypeId() != null) {
                        throw new IOException("Explicit YAML tags are not supported in SSSOM metadata");
                    }
                }
            }
        }
    }

    private static void rejectYamlNodeFeatures(byte[] bytes) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit((int) MAX_BYTES);
        try {
            ParserImpl parser = new ParserImpl(
                    new StreamReader(new String(bytes, StandardCharsets.UTF_8)), options);
            int events = 0;
            while (!parser.checkEvent(Event.ID.StreamEnd)) {
                Event event = parser.getEvent();
                if (++events > MAX_METADATA_ENTRIES * 4 + 32) {
                    throw new IOException("SSSOM metadata event budget exceeded");
                }
                if (event instanceof AliasEvent
                        || (event instanceof NodeEvent node && node.getAnchor() != null)) {
                    throw new IOException("YAML aliases and anchors are not supported in SSSOM metadata");
                }
                if ((event instanceof ScalarEvent scalar && scalar.getTag() != null)
                        || (event instanceof CollectionStartEvent collection
                                && collection.getTag() != null)) {
                    throw new IOException("Explicit YAML tags are not supported in SSSOM metadata");
                }
            }
        } catch (IOException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new IOException("SSSOM YAML metadata could not be parsed safely", invalid);
        }
    }

    private static byte[] digestFile(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        long count = 0;
        byte[] buffer = new byte[8192];
        try (java.io.InputStream input = Files.newInputStream(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                if (count > MAX_BYTES) throw new IOException("SSSOM file exceeds " + MAX_BYTES + " bytes");
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static byte[] sha256(byte[] bytes) {
        return sha256Digest().digest(bytes);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Preamble(Map<String, Object> metadata, Map<String, String> prefixMap,
            String body) { }

    interface ReadInterlock {
        ReadInterlock NOOP = new ReadInterlock() { };

        default void beforeOpen() throws IOException { }

        default void afterRead() throws IOException { }
    }
}
