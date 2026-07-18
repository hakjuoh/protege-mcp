package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Locked-gate loaded-content attestation over a self-authored modular ontology. The application
 * document uses a datatype property declared only by its imported vocabulary, so a per-document
 * isolated re-parse types the assertion differently from the loaded closure — the same generic
 * cross-import parse-context drift the gate must handle, without vendoring a third-party ontology.
 * A clean project passes, an unsaved in-memory edit of a locked import fails closed with
 * imports.loaded_content_divergence, and reverting the edit passes again.
 */
class ImportLockLoadedContentAttestationTest {

    private static final String ROOT_IRI = "https://example.org/locked-gate-project";
    private static final String APPLICATION_IRI = "https://example.org/modules/application";
    private static final String VOCABULARY_IRI = "https://example.org/modules/vocabulary";
    private static final String CODE_PROPERTY_IRI = VOCABULARY_IRI + "#hasCode";

    @Test
    void cleanCrossTypedImportClosurePassesTheLockedGate(@TempDir Path tempDir) throws Exception {
        Fixture fixture = Fixture.build(tempDir);

        // Fixture-realism guard: the loaded closure types hasCode as a DATA property (declared
        // only in the imported vocabulary document), while an isolated parse of the same bytes
        // does not — the exact cross-import parse-context drift this gate must not fail on.
        IRI hasCode = IRI.create(CODE_PROPERTY_IRI);
        OWLOntology liveApplication = fixture.manager.getOntology(IRI.create(APPLICATION_IRI));
        assertTrue(containsDataAssertionOn(liveApplication, hasCode),
                "the loaded closure must type hasCode as a data property");
        assertFalse(containsDataAssertionOn(
                        parseInIsolation(fixture.documents.get(APPLICATION_IRI)), hasCode),
                "an isolated parse must exhibit the cross-import typing drift for this fixture "
                        + "to be meaningful");

        Map<String, Object> result = fixture.runProjectQc();
        assertEquals("pass", result.get("gate"), () -> result.toString());
        Map<?, ?> verification = (Map<?, ?>) result.get("import_lock_verification");
        assertEquals(true, verification.get("valid"), verification::toString);
        assertEquals(true, verification.get("loaded_content_verified"),
                "a clean multi-import closure with cross-import typing must attest");
    }

    @Test
    void unsavedInMemoryEditOfALockedImportFailsClosedAndRevertRecovers(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = Fixture.build(tempDir);
        assertEquals("pass", fixture.runProjectQc().get("gate"));

        OWLOntology vocabulary = fixture.manager.getOntology(IRI.create(VOCABULARY_IRI));
        OWLDataFactory df = fixture.manager.getOWLDataFactory();
        OWLAxiom unsaved = df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create(VOCABULARY_IRI + "#UnsavedGateEdit")));
        fixture.manager.addAxiom(vocabulary, unsaved);
        Map<String, Object> rejected = fixture.runProjectQc();
        assertEquals("error", rejected.get("gate"),
                "coordinates and disk hashes still match; only loaded-content attestation can "
                        + "catch the unsaved in-memory edit");
        assertTrue(String.valueOf(rejected.get("findings"))
                .contains("imports.loaded_content_divergence"), () -> rejected.toString());
        Map<?, ?> verification = (Map<?, ?>) rejected.get("import_lock_verification");
        assertEquals(false, verification.get("loaded_content_verified"));
        assertEquals(List.of(), verification.get("mismatched_entries"),
                "the on-disk bytes still match the lock; the divergence is in the loaded content");

        fixture.manager.removeAxiom(vocabulary, unsaved);
        Map<String, Object> recovered = fixture.runProjectQc();
        assertEquals("pass", recovered.get("gate"), () -> recovered.toString());
    }

    @Test
    void attestationHashesTheExactBytesItParses(@TempDir Path tempDir) throws Exception {
        Path temp = tempDir.toRealPath();
        Path document = temp.resolve("imported.ttl");
        String contentA = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/imported> a owl:Ontology .\n";
        Files.writeString(document, contentA);
        String shaA = RevisionTools.sha256File(document).substring("sha256:".length());
        ImportLockTools.LockEntry pinnedToA = new ImportLockTools.LockEntry(
                "https://example.org/imported", "", "imported.ttl", shaA, true, document);
        List<ImportLockTools.ParsedLockedDocument> members =
                ImportLockTools.parseLockedClosure(List.of(pinnedToA), List.of());
        assertEquals(1, members.size());

        // The attestation cache is content-addressed: after an on-disk swap, the verified hash can
        // only ever serve the members parsed from the bytes carrying that exact hash.
        Files.writeString(document, "not an ontology at all");
        assertEquals(members, ImportLockTools.parseLockedClosure(List.of(pinnedToA), List.of()));

        // The single read is hashed BEFORE parsing: bytes that do not match the verified hash must
        // fail closed as a swap, never surface as a parse failure of bytes nobody hashed.
        Path swappedDocument = temp.resolve("swapped.ttl");
        Files.writeString(swappedDocument, "not an ontology at all");
        ImportLockTools.LockEntry staleClaim = new ImportLockTools.LockEntry(
                "https://example.org/imported", "", "swapped.ttl", shaA, true, swappedDocument);
        IOException swapped = assertThrows(IOException.class,
                () -> ImportLockTools.parseLockedClosure(List.of(staleClaim), List.of()));
        assertTrue(swapped.getMessage().contains("changed while the gate was attesting"),
                swapped::getMessage);

        // Unparseable bytes under their OWN correct hash are a parse failure of verified bytes.
        String shaGarbage = RevisionTools.sha256File(swappedDocument)
                .substring("sha256:".length());
        ImportLockTools.LockEntry pinnedToGarbage = new ImportLockTools.LockEntry(
                "https://example.org/imported", "", "swapped.ttl", shaGarbage, true,
                swappedDocument);
        IOException unparseable = assertThrows(IOException.class,
                () -> ImportLockTools.parseLockedClosure(List.of(pinnedToGarbage), List.of()));
        assertTrue(unparseable.getMessage().contains("could not attest locked import document"),
                unparseable::getMessage);

        // The parse cache is keyed by exact content: changed bytes under their own hash reparse
        // instead of serving the stale earlier member.
        String contentB = contentA
                + "<https://example.org/imported#B> a owl:Class .\n";
        Files.writeString(document, contentB);
        String shaB = RevisionTools.sha256File(document).substring("sha256:".length());
        ImportLockTools.LockEntry pinnedToB = new ImportLockTools.LockEntry(
                "https://example.org/imported", "", "imported.ttl", shaB, true, document);
        List<ImportLockTools.ParsedLockedDocument> reparsed =
                ImportLockTools.parseLockedClosure(List.of(pinnedToB), List.of());
        assertNotEquals(members.get(0).memberLine(), reparsed.get(0).memberLine());
    }

    private static boolean containsDataAssertionOn(OWLOntology ontology, IRI property) {
        return ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION).stream()
                .anyMatch(axiom -> !axiom.getProperty().isAnonymous()
                        && axiom.getProperty().asOWLDataProperty().getIRI().equals(property));
    }

    /** The closure-blind parse shape the pre-fix gate used: every import maps to one placeholder. */
    private static OWLOntology parseInIsolation(Path document) throws Exception {
        Path placeholder = Files.createTempFile("attestation-test-placeholder-", ".ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            IRI placeholderIri = IRI.create(placeholder.toUri());
            manager.getIRIMappers().add(importIri -> placeholderIri);
            return manager.loadOntologyFromOntologyDocument(
                    new org.semanticweb.owlapi.io.FileDocumentSource(document.toFile()),
                    new OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                            .setFollowRedirects(false));
        } finally {
            Files.deleteIfExists(placeholder);
        }
    }

    /** A locked synthetic project: live manager, policy, lock, and IRI-to-document table. */
    private record Fixture(OWLOntologyManager manager, OWLOntology active, Path policy,
            Map<String, Path> documents, Path temp) {

        Map<String, Object> runProjectQc() {
            ToolContext context = new ToolContext(
                    HeadlessAccess.over(FakeModelManager.over(active)), null);
            CallToolResult result = ProjectQcTools.run(context,
                    Map.of("policy_path", policy.toString()), true);
            assertFalse(Boolean.TRUE.equals(result.isError()),
                    () -> String.valueOf(result.structuredContent()));
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            return structured;
        }

        static Fixture build(Path tempDir) throws Exception {
            Path temp = tempDir.toRealPath();
            Map<String, Path> documents = new LinkedHashMap<>();
            Path modules = Files.createDirectories(temp.resolve("modules"));
            Path vocabulary = modules.resolve("vocabulary.ttl");
            Files.writeString(vocabulary,
                    "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                            + "<" + VOCABULARY_IRI + "> a owl:Ontology .\n"
                            + "<" + CODE_PROPERTY_IRI + "> a owl:DatatypeProperty .\n");
            documents.put(VOCABULARY_IRI, vocabulary);
            Path application = modules.resolve("application.ttl");
            Files.writeString(application,
                    "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                            + "<" + APPLICATION_IRI + "> a owl:Ontology ;\n"
                            + "  owl:imports <" + VOCABULARY_IRI + "> .\n"
                            + "<" + APPLICATION_IRI + "#item> <" + CODE_PROPERTY_IRI
                            + "> \"007\" .\n");
            documents.put(APPLICATION_IRI, application);

            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            IRI unmapped = IRI.create(temp.resolve("unmapped-import-denied").toUri());
            manager.getIRIMappers().add(importIri -> {
                Path mapped = documents.get(importIri.toString());
                // Any unmapped import resolves to a missing local file: the fixture must never
                // fetch over the network, and an incomplete mapping must fail the load loudly.
                return mapped == null ? unmapped : IRI.create(mapped.toUri());
            });
            manager.loadOntologyFromOntologyDocument(
                    new org.semanticweb.owlapi.io.FileDocumentSource(
                            documents.get(APPLICATION_IRI).toFile()));
            for (OWLOntology loaded : manager.getOntologies()) {
                assertTrue(Path.of(manager.getOntologyDocumentIRI(loaded).toURI())
                                .startsWith(temp),
                        "every closure member must have loaded from the local fixture");
            }

            OWLOntology active = manager.createOntology(IRI.create(ROOT_IRI));
            manager.setOntologyFormat(active, new TurtleDocumentFormat());
            manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
            manager.applyChange(new AddImport(active, manager.getOWLDataFactory()
                    .getOWLImportsDeclaration(IRI.create(APPLICATION_IRI))));

            writeLock(temp, manager, active);
            Path policy = temp.resolve("policy.yaml");
            ProjectPolicyFixtures.writePolicy(policy,
                    ProjectPolicyFixtures.minimalPolicy("locked-cross-typing", ROOT_IRI)
                            + "validation:\n  required_stages: [structural]\n  fail_on: error\n"
                            + "imports:\n  mode: locked\n  fail_on_missing: true\n"
                            + "  lockfile: imports.lock.json\n  network: deny\n");
            return new Fixture(manager, active, policy, documents, temp);
        }

        private static void writeLock(Path temp, OWLOntologyManager manager, OWLOntology active)
                throws IOException {
            List<String> rows = new ArrayList<>();
            for (OWLOntology loaded : manager.getOntologies()) {
                if (loaded.equals(active)) continue;
                Path document = Path.of(manager.getOntologyDocumentIRI(loaded).toURI());
                String ontologyIri = loaded.getOntologyID().getOntologyIRI().get().toString();
                String versionIri = loaded.getOntologyID().getVersionIRI().isPresent()
                        ? "\"" + loaded.getOntologyID().getVersionIRI().get() + "\"" : "null";
                boolean direct = active.getDirectImports().contains(loaded);
                rows.add("{\"ontology_iri\":\"" + ontologyIri + "\",\"version_iri\":" + versionIri
                        + ",\"document\":\""
                        + temp.relativize(document).toString().replace('\\', '/')
                        + "\",\"sha256\":\""
                        + RevisionTools.sha256File(document).substring("sha256:".length())
                        + "\",\"direct\":" + direct + "}");
            }
            Files.writeString(temp.resolve("imports.lock.json"),
                    "{\"version\":1,\"imports\":[" + String.join(",", rows) + "]}\n");
        }
    }
}
