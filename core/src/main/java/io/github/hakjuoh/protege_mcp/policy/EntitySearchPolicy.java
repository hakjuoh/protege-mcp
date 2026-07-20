package io.github.hakjuoh.protege_mcp.policy;

import java.util.List;

/** Shared policy-v1 defaults and bounds for local entity discovery. */
public final class EntitySearchPolicy {

    public static final List<String> DEFAULT_PREFERRED_PROPERTIES = List.of(
            "http://www.w3.org/2000/01/rdf-schema#label",
            "http://www.w3.org/2004/02/skos/core#prefLabel");
    public static final List<String> DEFAULT_SYNONYM_PROPERTIES = List.of(
            "http://www.w3.org/2004/02/skos/core#altLabel",
            "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym",
            "http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym");
    public static final int MAX_LANGUAGE_PRIORITIES = 4;

    private EntitySearchPolicy() {
    }
}
