package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.Map;

/** Final-hop guards for a merge prepared before off-thread document loading. */
class OntologyDocumentMergeGuardTest {
    private Preferences preferences;
    private boolean savedReadOnly;

    @BeforeEach
    void allowWrites() {
        preferences = McpConfig.prefs();
        savedReadOnly = preferences.getBoolean(McpConfig.KEY_READ_ONLY, false);
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, false);
    }

    @AfterEach
    void restoreReadOnly() {
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
    }

    @Test
    void refusesWhenReadOnlyIsEnabledAfterCapture() throws Exception {
        Fixture fixture = fixture();
        OntologyDocumentTools.MergeCoordinates captured =
                OntologyDocumentTools.captureMergeCoordinates(fixture.context(), fixture.model());

        preferences.putBoolean(McpConfig.KEY_READ_ONLY, true);
        CallToolResult refused =
                OntologyDocumentTools.validateMergeCoordinates(
                        fixture.context(), fixture.model(), captured);

        assertNotNull(refused);
        assertEquals(Boolean.TRUE, refused.isError());
        assertTrue(String.valueOf(refused.content()).contains("read-only"));
    }

    @Test
    void refusesWhenTheActiveOntologyChangesAfterCapture() throws Exception {
        Fixture fixture = fixture();
        OntologyDocumentTools.MergeCoordinates captured =
                OntologyDocumentTools.captureMergeCoordinates(fixture.context(), fixture.model());

        assertRevisionConflict(
                OntologyDocumentTools.validateMergeCoordinates(
                        fixture.context(), fixture.otherModel(), captured));
    }

    @Test
    void refusesWhenTheCapturedOntologyIsEditedAfterCapture() throws Exception {
        Fixture fixture = fixture();
        OntologyDocumentTools.MergeCoordinates captured =
                OntologyDocumentTools.captureMergeCoordinates(fixture.context(), fixture.model());
        OWLAxiom edit =
                fixture
                        .manager()
                        .getOWLDataFactory()
                        .getOWLDeclarationAxiom(
                                fixture
                                        .manager()
                                        .getOWLDataFactory()
                                        .getOWLClass(IRI.create("https://example.org/Changed")));
        fixture.manager().addAxiom(fixture.active(), edit);

        assertRevisionConflict(
                OntologyDocumentTools.validateMergeCoordinates(
                        fixture.context(), fixture.model(), captured));
    }

    @SuppressWarnings("unchecked")
    private static void assertRevisionConflict(CallToolResult result) {
        assertNotNull(result);
        Map<String, Object> body = (Map<String, Object>) result.structuredContent();
        assertEquals(false, body.get("merged"), body::toString);
        assertEquals("revision_conflict", body.get("error_code"), body::toString);
        assertTrue(body.containsKey("base_revision"), body::toString);
        assertTrue(body.containsKey("current_revision"), body::toString);
    }

    private static Fixture fixture() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("https://example.org/Active"));
        OWLOntology other = manager.createOntology(IRI.create("https://example.org/Other"));
        OWLModelManager model = FakeModelManager.over(active);
        OWLModelManager otherModel = FakeModelManager.over(other);
        ToolContext context =
                new ToolContext(
                        HeadlessAccess.over(model),
                        new McpServerController(new OntologyAccess(null)));
        return new Fixture(manager, active, otherModel, model, context);
    }

    private record Fixture(
            OWLOntologyManager manager,
            OWLOntology active,
            OWLModelManager otherModel,
            OWLModelManager model,
            ToolContext context) {}
}
