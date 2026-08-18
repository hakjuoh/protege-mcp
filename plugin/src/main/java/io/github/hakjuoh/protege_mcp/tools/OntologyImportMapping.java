package io.github.hakjuoh.protege_mcp.tools;

import org.semanticweb.owlapi.model.IRI;

/** Immutable logical-to-document IRI mapping shared by document loaders. */
final class OntologyImportMapping {
    final IRI logical;
    final IRI document;

    OntologyImportMapping(IRI logical, IRI document) {
        this.logical = logical;
        this.document = document;
    }
}
