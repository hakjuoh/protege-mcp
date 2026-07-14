package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Loads deterministic ROBOT-compatible {@code .rq} invariant files with lightweight metadata headers. */
final class InvariantFiles {

    private static final long MAX_QUERY_BYTES = 1_048_576L;

    private InvariantFiles() {
    }

    static List<Invariants.Invariant> load(List<Path> paths) {
        List<Invariants.Invariant> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Path path : paths) {
            Invariants.Invariant invariant = read(path);
            if (!ids.add(invariant.id)) {
                throw new ToolArgException("Duplicate invariant id '" + invariant.id
                        + "' in policy assets; ids must be unique across every .rq file.");
            }
            out.add(invariant);
        }
        return out;
    }

    static Invariants.Invariant read(Path path) {
        try {
            long size = Files.size(path);
            if (size > MAX_QUERY_BYTES) {
                throw new ToolArgException("Invariant file exceeds " + MAX_QUERY_BYTES + " bytes: " + path);
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String id = stem(path);
            String message = null;
            String severity = "error";
            boolean inferred = false;
            boolean inHeader = true;
            StringBuilder query = new StringBuilder();
            for (String line : content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                String trimmed = line.trim();
                if (inHeader && (trimmed.isEmpty() || trimmed.startsWith("#"))) {
                    if (trimmed.startsWith("#")) {
                        String header = trimmed.substring(1).trim();
                        int colon = header.indexOf(':');
                        if (colon > 0) {
                            String key = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                            String value = header.substring(colon + 1).trim();
                            switch (key) {
                                case "id":
                                    if (!value.isBlank()) {
                                        id = value;
                                    }
                                    break;
                                case "message":
                                    message = value;
                                    break;
                                case "severity":
                                    severity = Invariants.normalizeSeverity(value);
                                    break;
                                case "include_inferred":
                                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                                        throw new ToolArgException("Invalid include_inferred header in "
                                                + path + ": use true or false.");
                                    }
                                    inferred = Boolean.parseBoolean(value);
                                    break;
                                default:
                                    // ROBOT/tool metadata owned by another consumer remains harmless.
                                    break;
                            }
                        }
                    }
                    continue;
                }
                inHeader = false;
                query.append(line).append('\n');
            }
            String sparql = query.toString().trim();
            if (sparql.isEmpty()) {
                throw new ToolArgException("Invariant file contains no SPARQL query: " + path);
            }
            if (id == null || id.isBlank()) {
                throw new ToolArgException("Invariant id is blank in " + path);
            }
            return new Invariants.Invariant(id, message == null || message.isBlank()
                    ? "Invariant '" + id + "' violated." : message, severity, sparql, inferred);
        } catch (IOException e) {
            throw new ToolArgException("Could not read invariant file " + path + ": " + e.getMessage());
        }
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".rq")
                ? name.substring(0, name.length() - 3) : name;
    }
}
