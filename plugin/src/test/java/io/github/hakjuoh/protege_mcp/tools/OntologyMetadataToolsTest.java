package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Method-level tests for the Protégé-free pure helper {@link OntologyMetadataTools#idCollision}.
 *
 * <p>{@code idCollision} is exercised over the shared {@link FakeModelManager} harness: the fake's
 * {@code getOWLOntologyManager()} returns the real in-memory {@link OWLOntologyManager} backing its
 * active ontology, so creating additional ontologies in that same manager lets us drive both the
 * no-collision and true-collision branches headlessly. The {@code label()} formatting (private) is
 * verified indirectly through the collision error message it composes.
 *
 * <p>{@code specs()} and {@code render()} are out of scope: they need the live Protégé runtime
 * (workspace / rendering service) and are excluded by the harness by design.
 */
class OntologyMetadataToolsTest {

    private static final String ACTIVE_IRI = "http://example.org/active";
    private static final String OTHER_IRI = "http://example.org/other";
    private static final String VERSION_IRI = "http://example.org/other/1.0";

    /** An OWLModelManager whose active ontology has {@link #ACTIVE_IRI}, backed by a fresh manager. */
    private OWLModelManager managerOverActive() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create(ACTIVE_IRI));
        return FakeModelManager.over(active);
    }

    private OWLOntology active(OWLModelManager mm) {
        return mm.getActiveOntology();
    }

    // ---------------------------------------------------------------- anonymous newId

    @Test
    void anonymousNewIdReturnsNullNoCollisionPossible() throws OWLOntologyCreationException {
        OWLModelManager mm = managerOverActive();
        OWLOntologyID anon = new OWLOntologyID();
        assertTrue(anon.isAnonymous(), "sanity: no-arg OWLOntologyID is anonymous");
        assertNull(OntologyMetadataTools.idCollision(mm, active(mm), anon),
                "anonymous id can never collide -> null");
    }

    // ---------------------------------------------------------------- newId absent from manager

    @Test
    void newIdNotInManagerReturnsNull() throws OWLOntologyCreationException {
        OWLModelManager mm = managerOverActive();
        OWLOntologyID fresh = new OWLOntologyID(IRI.create("http://example.org/never-loaded"));
        assertNull(OntologyMetadataTools.idCollision(mm, active(mm), fresh),
                "id not loaded in the manager -> no collision");
    }

    @Test
    void newIdWithVersionNotInManagerReturnsNull() throws OWLOntologyCreationException {
        OWLModelManager mm = managerOverActive();
        OWLOntologyID fresh = new OWLOntologyID(
                IRI.create("http://example.org/never-loaded"),
                IRI.create("http://example.org/never-loaded/2.0"));
        assertNull(OntologyMetadataTools.idCollision(mm, active(mm), fresh),
                "versioned id not loaded -> no collision");
    }

    // ---------------------------------------------------------------- newId == active ontology

    @Test
    void newIdEqualToActiveOntologyReturnsNullRenamingSelfIsOk() throws OWLOntologyCreationException {
        OWLModelManager mm = managerOverActive();
        OWLOntology act = active(mm);
        // The active ontology's own id: contained in the manager but bound to `active` itself.
        OWLOntologyID selfId = act.getOntologyID();
        assertNull(OntologyMetadataTools.idCollision(mm, act, selfId),
                "id belongs to the active ontology itself -> not a collision");
    }

    // ---------------------------------------------------------------- newId bound to a DIFFERENT ontology

    @Test
    void newIdOnDifferentLoadedOntologyReturnsFriendlyError() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create(ACTIVE_IRI));
        // A second ontology loaded in the SAME manager occupies OTHER_IRI.
        m.createOntology(IRI.create(OTHER_IRI));
        OWLModelManager mm = FakeModelManager.over(active);

        OWLOntologyID target = new OWLOntologyID(IRI.create(OTHER_IRI));
        String msg = OntologyMetadataTools.idCollision(mm, active, target);
        assertNotNull(msg, "id is bound to another loaded ontology -> collision error");
        assertTrue(msg.contains(OTHER_IRI), "message names the colliding IRI: " + msg);
        assertTrue(msg.contains("another ontology"),
                "message mentions 'another ontology': " + msg);
        assertTrue(msg.contains("Choose a different IRI/version"),
                "message advises choosing a different IRI/version: " + msg);
    }

    @Test
    void collisionMessageWithVersionIriIncludesVersionInLabel() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create(ACTIVE_IRI));
        // Load a versioned ontology in the same manager.
        m.createOntology(new OWLOntologyID(IRI.create(OTHER_IRI), IRI.create(VERSION_IRI)));
        OWLModelManager mm = FakeModelManager.over(active);

        OWLOntologyID target = new OWLOntologyID(IRI.create(OTHER_IRI), IRI.create(VERSION_IRI));
        String msg = OntologyMetadataTools.idCollision(mm, active, target);
        assertNotNull(msg, "versioned id bound to another loaded ontology -> collision error");
        assertTrue(msg.contains(OTHER_IRI), "message includes ontology IRI: " + msg);
        assertTrue(msg.contains(VERSION_IRI),
                "label() appends the version IRI to the collision message: " + msg);
        assertTrue(msg.contains("version "),
                "label() prefixes the version IRI with 'version ': " + msg);
    }

    // ---------------------------------------------------------------- IRI with special chars / long ns

    @Test
    void collisionMessageWithLongNamespaceAppendsFullIri() throws OWLOntologyCreationException {
        String longIri = "https://spec.industrialontologies.org/ontology/core/Core/";
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create(ACTIVE_IRI));
        m.createOntology(IRI.create(longIri));
        OWLModelManager mm = FakeModelManager.over(active);

        OWLOntologyID target = new OWLOntologyID(IRI.create(longIri));
        String msg = OntologyMetadataTools.idCollision(mm, active, target);
        assertNotNull(msg, "long-namespace id bound to another ontology -> collision");
        assertTrue(msg.contains(longIri), "full long IRI appears verbatim in the message: " + msg);
    }

    // ---------------------------------------------------------------- collision vs non-collision contrast

    @Test
    void collisionDetectionIsBoundToTheSpecificLoadedIdOnly() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create(ACTIVE_IRI));
        m.createOntology(IRI.create(OTHER_IRI));
        OWLModelManager mm = FakeModelManager.over(active);

        // The loaded OTHER_IRI collides...
        assertNotNull(OntologyMetadataTools.idCollision(mm, active,
                new OWLOntologyID(IRI.create(OTHER_IRI))), "loaded other IRI collides");
        // ...but a sibling IRI that is not loaded does not.
        assertNull(OntologyMetadataTools.idCollision(mm, active,
                new OWLOntologyID(IRI.create(OTHER_IRI + "-x"))),
                "an unloaded sibling IRI does not collide");
    }
}
