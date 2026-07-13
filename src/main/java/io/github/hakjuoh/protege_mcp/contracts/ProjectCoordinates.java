package io.github.hakjuoh.protege_mcp.contracts;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Stable identity of the policy-controlled ontology project being evaluated. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ProjectCoordinates(
        @JsonProperty("project_id") String projectId,
        @JsonProperty("policy_version") int policyVersion,
        @JsonProperty("root_ontology") String rootOntology,
        @JsonProperty("modules") List<String> modules) {

    public ProjectCoordinates {
        projectId = ContractValues.nonBlank(projectId, "project_id");
        if (policyVersion < 1) {
            throw new IllegalArgumentException("policy_version must be at least 1");
        }
        rootOntology = ContractValues.absoluteIri(rootOntology, "root_ontology");
        modules = ContractValues.strings(modules, "modules", true);
        for (String module : modules) {
            ContractValues.absoluteIri(module, "modules entry");
        }
    }
}
