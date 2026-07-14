package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class WorkspaceRevisionTrackerTest {

    @Test
    void ontologyChangesAdvanceTheSessionRevision() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/revision"));
        OWLModelManager model = FakeModelManager.over(ontology);
        WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker(
                "00000000-0000-4000-8000-000000000001");

        var before = tracker.current(model, null, null).revision();
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/revision#A"))));
        var after = tracker.current(model, null, null).revision();

        assertTrue(after.sessionRevision() > before.sessionRevision());
        assertNotEquals(before.semanticFingerprint(), after.semanticFingerprint());
        assertNotEquals(before.documentFingerprint(), after.documentFingerprint());
    }

    @Test
    void prefixOnlyChangeIsObservedEvenWithoutAModelEvent() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/prefix"));
        TurtleDocumentFormat format = new TurtleDocumentFormat();
        format.setPrefix("ex:", "http://example.org/one#");
        manager.setOntologyFormat(ontology, format);
        OWLModelManager model = FakeModelManager.over(ontology);
        WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker(
                "00000000-0000-4000-8000-000000000002");

        var before = tracker.current(model, null, null).revision();
        format.setPrefix("ex:", "http://example.org/two#");
        var after = tracker.current(model, null, null).revision();

        assertEquals(before.semanticFingerprint(), after.semanticFingerprint());
        assertNotEquals(before.documentFingerprint(), after.documentFingerprint());
        assertEquals(before.sessionRevision() + 1, after.sessionRevision());
    }

    @Test
    void callerSuppliedDigestsAreReflectedInTheRevisionButDoNotBumpTheSessionCounter() throws Exception {
        // Interleaved calls legitimately pass different policy/import-lock digests (explicit vs discovered
        // vs no policy). Those differences must NOT advance the shared session counter — that would
        // manufacture spurious revision_conflicts between differently-configured MCP sessions on one
        // window. The import-lock digest still surfaces in the returned document fingerprint (so a real
        // on-disk lock change is caught by revision equality), and policy drift is checked separately at
        // commit time (policy_digest_conflict).
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/policy"));
        OWLModelManager model = FakeModelManager.over(ontology);
        WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker(
                "00000000-0000-4000-8000-000000000003");

        var first = tracker.current(model, "sha256:" + "1".repeat(64),
                "sha256:" + "a".repeat(64)).revision();
        var policyChanged = tracker.current(model, "sha256:" + "1".repeat(64),
                "sha256:" + "b".repeat(64)).revision();
        var lockChanged = tracker.current(model, "sha256:" + "2".repeat(64),
                "sha256:" + "b".repeat(64)).revision();

        // A different policy digest alone changes neither the document fingerprint nor the counter.
        assertEquals(first.documentFingerprint(), policyChanged.documentFingerprint());
        assertEquals(first.sessionRevision(), policyChanged.sessionRevision());
        // A different import-lock digest changes the returned document fingerprint (it is folded in) but
        // still does not spuriously bump the session counter.
        assertNotEquals(policyChanged.documentFingerprint(), lockChanged.documentFingerprint());
        assertEquals(policyChanged.sessionRevision(), lockChanged.sessionRevision());
    }

    @Test
    void workspaceIdsAreBackendScopedAndDisposeRemovesTheChangeListener() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/dispose"));
        OWLModelManager model = FakeModelManager.over(ontology);
        WorkspaceRevisionTracker first = new WorkspaceRevisionTracker();
        WorkspaceRevisionTracker second = new WorkspaceRevisionTracker();
        assertNotEquals(first.workspaceId(), second.workspaceId());

        first.current(model, null, null);
        first.dispose();
        long disposedAt = first.version();
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(IRI.create("http://example.org/dispose#A"))));
        assertEquals(disposedAt, first.version());
    }
}
