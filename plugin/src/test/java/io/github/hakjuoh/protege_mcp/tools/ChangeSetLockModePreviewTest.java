package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code preview_change_set lock_mode}: even with NO project policy, an explicit request verifies
 * the loaded closure against the beside-document {@code imports.lock.json} inside the preflight
 * gate (proving the no-policy import-graph capture) — a tamper makes the preview uncommittable, a
 * missing lockfile is a clean skip under {@code verify} and an {@code imports.lock_missing} error
 * under {@code required}, and an invalid value is rejected before any QC work.
 */
class ChangeSetLockModePreviewTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";
    private static final String IMPORTED_IRI = "https://example.org/imported";

    private Preferences prefs;
    private String savedReadOnly;
    private String savedConfirm;

    @BeforeEach
    void pinWritePrefs() {
        prefs = McpConfig.prefs();
        savedReadOnly = String.valueOf(prefs.getBoolean(McpConfig.KEY_READ_ONLY, false));
        savedConfirm = String.valueOf(prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restoreWritePrefs() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, Boolean.parseBoolean(savedReadOnly));
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, Boolean.parseBoolean(savedConfirm));
    }

    @Test
    void noPolicyVerifyGatesOnTheBesideDocumentLock(@TempDir Path temp) throws Exception {
        writeImportedAndLock(temp);
        ToolContext ctx = context(temp);

        // Without the argument, no verification runs (released behavior).
        Map<String, Object> released = structured(ChangeSetTools.preview(ctx, args(null), noPolicyReadRules()));
        assertEquals(Boolean.TRUE, released.get("committable"), released::toString);
        assertNull(((Map<?, ?>) released.get("preflight")).get("import_lock_verification"));

        Map<String, Object> verified = structured(ChangeSetTools.preview(ctx, args("verify"), noPolicyReadRules()));
        assertEquals(Boolean.TRUE, verified.get("committable"), verified::toString);
        Map<?, ?> preflight = (Map<?, ?>) verified.get("preflight");
        Map<?, ?> verification = (Map<?, ?>) preflight.get("import_lock_verification");
        assertEquals(true, verification.get("valid"), verification::toString);
        assertEquals(ImportLockTools.SOURCE_BESIDE_DOCUMENT, verification.get("lockfile_source"));
        assertTrue(String.valueOf(verification.get("lockfile_note")).contains("accident-safety"),
                verification::toString);

        Files.writeString(temp.resolve("imported.ttl"),
                Files.readString(temp.resolve("imported.ttl")) + "# tampered\n");
        Map<String, Object> rejected = structured(ChangeSetTools.preview(ctx, args("verify"), noPolicyReadRules()));
        assertEquals(Boolean.FALSE, rejected.get("committable"), rejected::toString);
        assertTrue(String.valueOf(rejected.get("committable_reasons")).contains("preflight_error"),
                rejected::toString);
        assertTrue(String.valueOf(((Map<?, ?>) rejected.get("preflight")).get("findings"))
                .contains("imports.lock_mismatch"), rejected::toString);

        // ... while the released no-argument behavior still ignores the tamper.
        assertEquals(Boolean.TRUE,
                structured(ChangeSetTools.preview(ctx, args(null), noPolicyReadRules())).get("committable"));
    }

    @Test
    void noPolicyAbsentLockSkipsUnderVerifyAndErrorsUnderRequired(@TempDir Path temp)
            throws Exception {
        Files.writeString(temp.resolve("imported.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        ToolContext ctx = context(temp);

        Map<String, Object> skipped = structured(ChangeSetTools.preview(ctx, args("verify"), noPolicyReadRules()));
        assertEquals(Boolean.TRUE, skipped.get("committable"), skipped::toString);
        Map<?, ?> verification = (Map<?, ?>)
                ((Map<?, ?>) skipped.get("preflight")).get("import_lock_verification");
        assertEquals(true, verification.get("skipped"), verification::toString);

        Map<String, Object> required = structured(ChangeSetTools.preview(ctx, args("required"), noPolicyReadRules()));
        assertEquals(Boolean.FALSE, required.get("committable"), required::toString);
        assertTrue(String.valueOf(((Map<?, ?>) required.get("preflight")).get("findings"))
                .contains("imports.lock_missing"), required::toString);
    }

    @Test
    void invalidLockModeIsRejectedBeforeAnyQcWork(@TempDir Path temp) throws Exception {
        writeImportedAndLock(temp);
        ToolContext ctx = context(temp);

        CallToolResult rejected = ChangeSetTools.preview(ctx, args("sometimes"), noPolicyReadRules());
        assertEquals(Boolean.TRUE, rejected.isError(), () -> String.valueOf(rejected.content()));
        assertTrue(String.valueOf(rejected.content()).contains("Invalid lock_mode"),
                () -> String.valueOf(rejected.content()));
    }

    @Test
    void aLockResolutionAbortNeverAttachesAStaleGatePassPreflight(@TempDir Path temp)
            throws Exception {
        // The active ontology has NO local document folder, so the beside-document lockfile cannot
        // resolve — the released refusal state aborts the verification AFTER the QC stages already
        // produced a gate=pass map. That half-built preflight must never be attached next to the
        // preflight_error reason it contradicts.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"));
        OWLClass foo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        for (OWLClass cls : List.of(animal, foo)) {
            manager.addAxiom(active, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(active, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    cls.getIRI(), df.getOWLLiteral(cls.getIRI().getShortForm())));
        }
        ToolContext ctx = new ToolContext(
                HeadlessAccess.over(FakeModelManager.withNoReasonerSelected(active)),
                new McpServerController(new OntologyAccess(null)));

        Map<String, Object> refused = structured(
                ChangeSetTools.preview(ctx, args("verify"), noPolicyReadRules()));
        assertEquals(Boolean.FALSE, refused.get("committable"), refused::toString);
        assertTrue(String.valueOf(refused.get("committable_reasons")).contains("preflight_error"),
                refused::toString);
        assertTrue(String.valueOf(refused.get("committable_reasons"))
                .contains("no local document folder"), refused::toString);
        assertNull(refused.get("preflight"),
                "an aborted lock verification must not attach a contradictory gate=pass preflight");
    }

    /** No-policy request rules for path authorization (project read only, compat mode on). */
    private static DirectAccessPolicy.Rules noPolicyReadRules() {
        return new DirectAccessPolicy.Rules(
                io.github.hakjuoh.protege_mcp.policy.ProjectPolicy.notFound(),
                new io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal(1, "test",
                        "test-client", "Test",
                        java.util.Set.of(DirectAccessPolicy.PROJECT_READ), null));
    }

    // ------------------------------------------------------------------ fixtures

    private static void writeImportedAndLock(Path temp) throws Exception {
        Path importedPath = temp.resolve("imported.ttl");
        Files.writeString(importedPath, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        String sha = RevisionTools.sha256File(importedPath).substring("sha256:".length());
        Files.writeString(temp.resolve("imports.lock.json"), "{\"version\":1,\"imports\":[{"
                + "\"ontology_iri\":\"" + IMPORTED_IRI + "\","
                + "\"version_iri\":null,\"document\":\"imported.ttl\","
                + "\"sha256\":\"" + sha + "\",\"direct\":true}]}\n");
    }

    /** A NO-POLICY workspace: labelled classes, a resolved import, no discoverable project.yaml. */
    private static ToolContext context(Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology imported = manager.loadOntologyFromOntologyDocument(
                temp.resolve("imported.ttl").toFile());
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"));
        OWLClass foo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        for (OWLClass cls : List.of(animal, foo)) {
            manager.addAxiom(active, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(active, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    cls.getIRI(), df.getOWLLiteral(cls.getIRI().getShortForm())));
        }
        manager.applyChange(new AddImport(active, df.getOWLImportsDeclaration(
                imported.getOntologyID().getOntologyIRI().get())));
        return new ToolContext(HeadlessAccess.over(FakeModelManager.withNoReasonerSelected(active)),
                new McpServerController(new OntologyAccess(null)));
    }

    private static Map<String, Object> args(String lockMode) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(op));
        if (lockMode != null) {
            args.put("lock_mode", lockMode);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
