package io.github.hakjuoh.protege_mcp.sssom;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/** Build an immutable mapping-validation entity index from a captured ontology closure. */
public final class SssomEntityIndexes {

    private SssomEntityIndexes() {
    }

    public static SssomEntityIndex fromOntologies(Collection<OWLOntology> ontologies) {
        if (ontologies == null) {
            throw new IllegalArgumentException("ontology collection is required");
        }
        Set<String> present = new LinkedHashSet<>();
        Set<String> deprecated = new LinkedHashSet<>();
        IRI marker = OWLRDFVocabulary.OWL_DEPRECATED.getIRI();
        for (OWLOntology ontology : ontologies) {
            if (ontology == null) {
                throw new IllegalArgumentException("ontology collection must not contain null");
            }
            ontology.getSignature().stream().map(entity -> entity.getIRI().toString())
                    .forEach(present::add);
            ontology.getAxioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)
                    .stream().filter(axiom -> marker.equals(axiom.getProperty().getIRI()))
                    .filter(SssomEntityIndexes::isTrue).map(OWLAnnotationAssertionAxiom::getSubject)
                    .filter(IRI.class::isInstance).map(IRI.class::cast)
                    .map(IRI::toString).forEach(deprecated::add);
        }
        return new SssomEntityIndex(present, deprecated);
    }

    private static boolean isTrue(OWLAnnotationAssertionAxiom axiom) {
        if (!(axiom.getValue() instanceof OWLLiteral literal)) return false;
        return "true".equalsIgnoreCase(literal.getLiteral()) || "1".equals(literal.getLiteral());
    }
}
