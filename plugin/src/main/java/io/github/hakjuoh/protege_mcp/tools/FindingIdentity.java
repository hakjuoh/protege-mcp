package io.github.hakjuoh.protege_mcp.tools;

import java.util.Collection;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;

import io.github.hakjuoh.protege_mcp.core.qc.QcFindingIdentity;

/** Stable, bounded identity digests for QC findings whose public examples may be truncated. */
final class FindingIdentity {

    private FindingIdentity() {
    }

    static String digest(Collection<String> identities) {
        return QcFindingIdentity.digest(identities);
    }

    static String entity(OWLEntity entity) {
        return QcFindingIdentity.entity(entity);
    }

    static String axiom(OWLAxiom axiom) {
        return QcFindingIdentity.axiom(axiom);
    }

    static String object(Object value) {
        return QcFindingIdentity.object(value);
    }
}
