package io.github.hakjuoh.protege_mcp.core.owl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;

class EntityGroundingTest {

    @Test
    void registeredCurieAndAbsoluteIriHaveOneCanonicalResult() {
        Map<String, String> prefixes = Map.of("ex", "https://example.org/conformance#");
        IRI expected = IRI.create("https://example.org/conformance#Term");

        assertEquals(expected, EntityGrounding.iri("ex:Term", prefixes));
        assertEquals(expected, EntityGrounding.iri(expected.toString(), prefixes));
        assertEquals(IRI.create("urn:conformance:item"),
                EntityGrounding.iri("urn:conformance:item", prefixes));
    }

    @Test
    void acceptsOwlapiColonKeysAndRejectsConflictingDeclarations() {
        assertEquals(IRI.create("https://example.org/conformance#Term"),
                EntityGrounding.iri("ex:Term",
                        Map.of("ex:", "https://example.org/conformance#")));

        Map<String, String> ambiguous = new LinkedHashMap<>();
        ambiguous.put("ex", "https://example.org/one#");
        ambiguous.put("ex:", "https://example.org/two#");
        assertThrows(IllegalArgumentException.class,
                () -> EntityGrounding.iri("ex:Term", ambiguous));
        assertThrows(IllegalArgumentException.class,
                () -> EntityGrounding.iri("not an iri", Map.of()));
    }

    @Test
    void preservesDefaultPrefixAndExtendedUnicodeLocalParts() {
        assertEquals(IRI.create("https://example.org/default#Term"),
                EntityGrounding.iri(":Term",
                        Map.of(":", "https://example.org/default#")));
        assertEquals(IRI.create("https://example.org/conformance#path/with~part"),
                EntityGrounding.iri("ex:path/with~part",
                        Map.of("ex", "https://example.org/conformance#")));
        assertEquals(IRI.create("https://example.org/conformance#표현"),
                EntityGrounding.iri("ex:표현",
                        Map.of("ex", "https://example.org/conformance#")));
    }
}
