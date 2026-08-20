package io.github.hakjuoh.protege_mcp.ui;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;

/** Tracks and initiates active-ontology changes without duplicating Protégé's change event. */
final class ActiveOntologyEvents {

    private OWLOntology observed;

    ActiveOntologyEvents(OWLOntology initial) {
        observed = initial;
    }

    /** Returns true exactly once for each newly observed non-null active ontology. */
    boolean observe(OWLOntology active) {
        if (active == null || active.equals(observed)) return false;
        observed = active;
        return true;
    }

    /**
     * Selects a different ontology. {@link OWLModelManager#setActiveOntology(OWLOntology)} already
     * emits {@code ACTIVE_ONTOLOGY_CHANGED}; firing it again can invoke host UI listeners twice.
     */
    static boolean activate(OWLModelManager manager, OWLOntology target) {
        if (manager == null || target == null || target.equals(manager.getActiveOntology())) {
            return false;
        }
        manager.setActiveOntology(target);
        return true;
    }
}
