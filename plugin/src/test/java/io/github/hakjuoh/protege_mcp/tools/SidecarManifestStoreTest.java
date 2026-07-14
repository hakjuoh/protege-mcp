package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Size-cap tests for the sidecar manifest reader: the 8 MiB bound must hold for the bytes actually
 * buffered — a raced pre-check of {@code Files.size} could otherwise let a file that grows between
 * check and read fill the heap. Mirrors the manifest's private cap of 8,388,608 bytes.
 */
class SidecarManifestStoreTest {

    private static final int CAP = 8_388_608;

    @TempDir
    Path temp;

    @Test
    void loadFileSkipsAManifestLargerThanTheCap() throws Exception {
        Path manifest = temp.resolve("onto-cqs.json");
        byte[] oversized = new byte[CAP + 1];
        Arrays.fill(oversized, (byte) ' ');
        Files.write(manifest, oversized);

        CqStore.LoadResult result = SidecarManifestStore.loadFile(manifest.toFile());

        assertTrue(result.ok.isEmpty(), "nothing loads from an over-cap manifest");
        assertEquals(1, result.skipped.size());
        assertTrue(result.skipped.get(0).reason.contains("exceeds"),
                "the skip reason names the size cap, got: " + result.skipped.get(0).reason);
    }

    @Test
    void loadFileStillReadsAManifestExactlyAtTheCap() throws Exception {
        Path manifest = temp.resolve("onto-cqs.json");
        byte[] data = new byte[CAP];
        Arrays.fill(data, (byte) ' ');   // trailing whitespace after the JSON object is legal
        byte[] json = "{\"version\":1,\"questions\":[]}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, data, 0, json.length);
        Files.write(manifest, data);

        CqStore.LoadResult result = SidecarManifestStore.loadFile(manifest.toFile());

        assertTrue(result.skipped.isEmpty(), "a manifest exactly at the cap is readable");
        assertTrue(result.ok.isEmpty(), "the manifest holds no questions");
    }

    @Test
    void upsertIntoAnOverCapManifestFailsClosed() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cq"));
        m.setOntologyDocumentIRI(o, IRI.create(new File(temp.toFile(), "onto.owl").toURI()));
        CqContext ctx = CqContext.of(FakeModelManager.over(o));
        Path manifest = ctx.manifestFile().toPath();
        byte[] oversized = new byte[CAP + 1];
        Arrays.fill(oversized, (byte) ' ');
        Files.write(manifest, oversized);
        byte[] before = Files.readAllBytes(manifest);

        CompetencyQuestion cq = new CompetencyQuestion();
        cq.id = "CQ-1";
        cq.text = "Question CQ-1";
        cq.type = "Validating";
        cq.query = "SELECT ?s WHERE { ?s a ?t }";
        cq.expected = Expectation.nonEmpty();

        ToolArgException e = assertThrows(ToolArgException.class,
                () -> new SidecarManifestStore().upsert(ctx, cq));
        assertTrue(e.getMessage().contains("Could not read"),
                "the mutating path refuses rather than truncating, got: " + e.getMessage());
        assertTrue(Arrays.equals(before, Files.readAllBytes(manifest)),
                "the refused upsert leaves the manifest untouched");
    }
}
