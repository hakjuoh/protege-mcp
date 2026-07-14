package io.github.hakjuoh.protege_mcp.tools;

import java.util.Objects;

import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

/** Manager-independent cached axiom delta; it deliberately retains no live ontology reference. */
record NormalizedChange(Operation operation, OWLAxiom axiom) {

    enum Operation { ADD, REMOVE }

    NormalizedChange {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(axiom, "axiom");
    }

    OWLOntologyChange bind(OWLOntology target) {
        return operation == Operation.ADD ? new AddAxiom(target, axiom) : new RemoveAxiom(target, axiom);
    }
}
