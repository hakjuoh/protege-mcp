package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code imports.mode: locked} must gate INSIDE the change-set surfaces, not only
 * {@code run_project_qc}: a tampered locked import makes {@code preview_change_set} uncommittable
 * with the {@code imports.lock_mismatch} finding, and {@code apply_changes verify=rollback} refuses
 * the batch before live mutation with the same gate=error verdict. Deleting the
 * {@code enforceImportIntegrity} call from either surface fails these tests.
 */
class ChangeSetLockedImportGateTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";
    private static final String IMPORTED_IRI = "https://example.org/imported";

    private Preferences prefs;
    private String savedToken;
    private String savedReadOnly;
    private String savedConfirm;

    @BeforeEach
    void pinWritePrefs() {
        prefs = McpConfig.prefs();
        savedToken = prefs.getString(McpConfig.KEY_TOKEN, "");
        savedReadOnly = String.valueOf(prefs.getBoolean(McpConfig.KEY_READ_ONLY, false));
        savedConfirm = String.valueOf(prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restoreWritePrefs() {
        prefs.putString(McpConfig.KEY_TOKEN, savedToken);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, Boolean.parseBoolean(savedReadOnly));
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, Boolean.parseBoolean(savedConfirm));
    }

    @Test
    void tamperedLockedImportMakesThePreviewUncommittable(@TempDir Path temp) throws Exception {
        OWLOntology ontology = lockedProjectOntology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        // Sanity first: while the on-disk bytes match the lock, the same preview is committable —
        // proving the uncommittable verdict below is the lock check, not a broken fixture.
        Map<String, Object> clean = structured(ChangeSetTools.preview(ctx, previewArgs()));
        assertEquals(Boolean.TRUE, clean.get("committable"), clean::toString);

        Files.writeString(temp.resolve("imported.ttl"),
                Files.readString(temp.resolve("imported.ttl")) + "# tampered\n");
        Map<String, Object> rejected = structured(ChangeSetTools.preview(ctx, previewArgs()));

        assertEquals(Boolean.FALSE, rejected.get("committable"), rejected::toString);
        assertTrue(String.valueOf(rejected.get("committable_reasons")).contains("preflight_error"),
                rejected::toString);
        Map<?, ?> preflight = (Map<?, ?>) rejected.get("preflight");
        assertEquals("error", preflight.get("gate"), preflight::toString);
        assertTrue(String.valueOf(preflight.get("findings")).contains("imports.lock_mismatch"),
                "the preview gate must carry the lock-verification finding: " + preflight);
    }

    @Test
    void tamperedLockedImportPreventsARollbackApplyBeforeLiveMutation(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = lockedProjectOntology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        // Sanity first: with the lock intact the same gate lets a rollback batch commit.
        Map<String, Object> clean = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "clean locked apply", List.of(subclassOperation()), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> cleanVerify = (Map<String, Object>) clean.get("verify");
        assertEquals(Boolean.TRUE, cleanVerify.get("applied"),
                "sanity: the locked gate passes while the lock matches: " + cleanVerify);

        Files.writeString(temp.resolve("imported.ttl"),
                Files.readString(temp.resolve("imported.ttl")) + "# tampered\n");
        Map<String, Object> rejected = structured(ChangeSetApplyVerify.apply(ctx, "rollback",
                60_000, "tampered locked apply", List.of(removeFooLabelOperation()), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) rejected.get("verify");
        assertEquals("error", verify.get("gate"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
        assertTrue(String.valueOf(verify.get("preflight")).contains("imports.lock_mismatch"),
                "the rollback prevention must carry the lock-verification finding: " + verify);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        assertTrue(ontology.containsAxiom(df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                        IRI.create(ONTOLOGY_IRI + "#Foo"), df.getOWLLiteral("Foo"))),
                "the prevented batch must not touch the live ontology");
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A discovered locked-imports project: {@code imported.ttl} pinned by
     * {@code imports.lock.json}, both beside the discovered {@code .protege-mcp/project.yaml}, and
     * an active ontology (labelled Animal + Foo) that imports the locked document.
     */
    private static OWLOntology lockedProjectOntology(Path temp) throws Exception {
        Path importedPath = temp.resolve("imported.ttl");
        Files.writeString(importedPath, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + IMPORTED_IRI + "> a owl:Ontology .\n");
        String sha = RevisionTools.sha256File(importedPath).substring("sha256:".length());
        Files.writeString(temp.resolve("imports.lock.json"), "{\"version\":1,\"imports\":[{"
                + "\"ontology_iri\":\"" + IMPORTED_IRI + "\","
                + "\"version_iri\":null,\"document\":\"imported.ttl\","
                + "\"sha256\":\"" + sha + "\",\"direct\":true}]}\n");
        Path dir = Files.createDirectories(temp.resolve(".protege-mcp"));
        ProjectPolicyFixtures.writePolicy(dir.resolve("project.yaml"),
                ProjectPolicyFixtures.minimalPolicy("locked", ONTOLOGY_IRI)
                        + "imports:\n  mode: locked\n  fail_on_missing: true\n"
                        + "  lockfile: imports.lock.json\n  network: deny\n"
                        + "validation:\n  required_stages: [structural]\n  fail_on: error\n");

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology imported = manager.loadOntologyFromOntologyDocument(importedPath.toFile());
        OWLOntology active = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        manager.setOntologyDocumentIRI(active, IRI.create(docDir.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"));
        OWLClass foo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        manager.addAxiom(active, df.getOWLDeclarationAxiom(animal));
        manager.addAxiom(active, df.getOWLDeclarationAxiom(foo));
        manager.addAxiom(active, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                animal.getIRI(), df.getOWLLiteral("Animal")));
        manager.addAxiom(active, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                foo.getIRI(), df.getOWLLiteral("Foo")));
        manager.applyChange(new AddImport(active, df.getOWLImportsDeclaration(
                imported.getOntologyID().getOntologyIRI().get())));
        return active;
    }

    private static Map<String, Object> previewArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(subclassOperation()));
        return args;
    }

    private static Map<String, Object> subclassOperation() {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        return op;
    }

    private static Map<String, Object> removeFooLabelOperation() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "remove");
        operation.put("axiom_type", "annotation_assertion");
        operation.put("subject", ONTOLOGY_IRI + "#Foo");
        operation.put("property", "http://www.w3.org/2000/01/rdf-schema#label");
        operation.put("value", "Foo");
        return operation;
    }

    private static ToolContext context(org.protege.editor.owl.model.OWLModelManager mm) {
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
