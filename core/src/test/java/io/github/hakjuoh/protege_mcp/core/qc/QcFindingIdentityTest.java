package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;

class QcFindingIdentityTest {

    @Test
    void preservesTheReleasedEntityAndAxiomIdentityShapes() {
        var data = OWLManager.getOWLDataFactory();
        var entity = data.getOWLClass(IRI.create("https://example.org/test#Thing"));
        var axiom = data.getOWLDeclarationAxiom(entity);

        assertEquals("entity\u0000Class\u0000https://example.org/test#Thing",
                QcFindingIdentity.entity(entity));
        assertEquals("axiom\u0000" + axiom, QcFindingIdentity.axiom(axiom));
    }

    @Test
    void digestIsOrderIndependentAndUsesUtf8ByteLengths() {
        String expected = QcFindingIdentity.digest(List.of("é", "plain"));

        assertEquals(expected, QcFindingIdentity.digest(List.of("plain", "é")));
        assertNotEquals(expected, QcFindingIdentity.digest(List.of("e", "plain")));
        assertEquals("sha256:b8267ae9366c3105fcde80fff77b3dbaf44a97b3fdf91c52bf61c7f577439e21",
                expected);
    }

    @Test
    void nestedObjectIdentityIgnoresMapAndCollectionOrder() {
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("b", List.of(2, 1));
        left.put("a", "value");
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("a", "value");
        right.put("b", List.of(1, 2));

        assertEquals(QcFindingIdentity.object(left), QcFindingIdentity.object(right));
    }

    @Test
    void rejectsNullContainersButRetainsSingletonNullCompatibility() {
        assertThrows(IllegalArgumentException.class, () -> QcFindingIdentity.digest(null));
        assertEquals(QcFindingIdentity.digest(List.of("")),
                QcFindingIdentity.digest(java.util.Collections.singletonList(null)));
    }
}
