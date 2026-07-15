package io.github.hakjuoh.protege_mcp.core.owl;

import java.util.LinkedHashSet;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The signature one ontology document derives from its own content: the entities referenced by its own
 * axioms plus its own ontology-header annotations.
 *
 * <p>This exists because OWLAPI 4's convenience accessor cannot bound a single artifact after a real
 * document load: {@code OWLOntology#getSignature()} answers from a cached set that the manager's
 * illegal-punning repair pass ({@code REPAIR_ILLEGAL_PUNNINGS}, on by default) mutates with the whole
 * loaded imports closure while the document is being parsed — so even an {@code Imports.EXCLUDED} query
 * can report imported entities. Every consumer that promises active-document scope (fingerprints,
 * {@code semantic_diff}, summaries, signature audits) must derive the signature from the document's own
 * axioms and annotations instead.
 */
public final class OwlDocumentSignature {

    private OwlDocumentSignature() {
    }

    /** Entities referenced by {@code ontology}'s own axioms and its own ontology annotations. */
    public static Set<OWLEntity> of(OWLOntology ontology) {
        Set<OWLEntity> signature = new LinkedHashSet<>();
        ontology.getAxioms().forEach(axiom -> signature.addAll(axiom.getSignature()));
        ontology.getAnnotations().forEach(annotation -> signature.addAll(annotation.getSignature()));
        return signature;
    }
}
