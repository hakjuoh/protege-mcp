package io.github.hakjuoh.protege_mcp.ro_crate;

import java.util.List;

/** Expected portable ontology-project identity, independent of any host application's policy model. */
public record RoCrateProjectProfile(
        String projectId,
        String rootArtifact,
        String ontologyIri,
        String profileIri,
        List<String> additionalProfiles,
        String ontologySpecificationIri) {

    public RoCrateProjectProfile {
        projectId = required(projectId, "projectId");
        rootArtifact = required(rootArtifact, "rootArtifact");
        ontologyIri = required(ontologyIri, "ontologyIri");
        profileIri = required(profileIri, "profileIri");
        ontologySpecificationIri = required(ontologySpecificationIri, "ontologySpecificationIri");
        additionalProfiles = additionalProfiles == null ? List.of() : List.copyOf(additionalProfiles);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
