package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persisted ROBOT-style invariant parsing and fail-closed metadata tests. */
class InvariantFilesTest {

    @Test
    void parsesHeadersAndRetainsCommentsInsideQuery(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("labelled.rq");
        Files.writeString(path, "# id: required-label\n"
                + "# message: Every class needs a label.\n"
                + "# severity: warning\n"
                + "# include_inferred: true\n\n"
                + "SELECT ?c WHERE {\n"
                + "  # this SPARQL comment is query content, not metadata\n"
                + "  ?c a owl:Class\n"
                + "}\n");
        Invariants.Invariant invariant = InvariantFiles.read(path);
        assertEquals("required-label", invariant.id);
        assertEquals("Every class needs a label.", invariant.message);
        assertEquals("warn", invariant.severity);
        assertTrue(invariant.includeInferred);
        assertTrue(invariant.sparql.contains("this SPARQL comment"));
    }

    @Test
    void defaultsToFilenameErrorAndAUsefulMessage(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("no-orphans.rq");
        Files.writeString(path, "ASK { ?x a owl:Nothing }\n");
        Invariants.Invariant invariant = InvariantFiles.read(path);
        assertEquals("no-orphans", invariant.id);
        assertEquals("error", invariant.severity);
        assertFalse(invariant.includeInferred);
        assertTrue(invariant.message.contains("no-orphans"));
    }

    @Test
    void duplicateIdsAndInvalidBooleanFailBeforeQc(@TempDir Path temp) throws Exception {
        Path one = temp.resolve("one.rq");
        Path two = temp.resolve("two.rq");
        Files.writeString(one, "# id: same\nASK {}\n");
        Files.writeString(two, "# id: same\nSELECT * WHERE {}\n");
        assertThrows(ToolArgException.class, () -> InvariantFiles.load(List.of(one, two)));

        Path invalid = temp.resolve("invalid.rq");
        Files.writeString(invalid, "# include_inferred: perhaps\nASK {}\n");
        assertThrows(ToolArgException.class, () -> InvariantFiles.read(invalid));
    }

    @Test
    void emptyAndOversizedQueriesFailClosed(@TempDir Path temp) throws Exception {
        Path empty = temp.resolve("empty.rq");
        Files.writeString(empty, "# id: empty\n");
        assertThrows(ToolArgException.class, () -> InvariantFiles.read(empty));

        Path huge = temp.resolve("huge.rq");
        Files.write(huge, new byte[1_048_577]);
        assertThrows(ToolArgException.class, () -> InvariantFiles.read(huge));
    }
}
