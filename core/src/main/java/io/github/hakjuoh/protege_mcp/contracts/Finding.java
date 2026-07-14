package io.github.hakjuoh.protege_mcp.contracts;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Common validator finding with an escape hatch for lossless validator-specific detail. */
public record Finding(
        @JsonProperty("id") String id,
        @JsonProperty("source") String source,
        @JsonProperty("severity") FindingSeverity severity,
        @JsonProperty("message") String message,
        @JsonProperty("focus_iri") String focusIri,
        @JsonProperty("axiom") String axiom,
        @JsonProperty("path") String path,
        @JsonProperty("rule_id") String ruleId,
        @JsonProperty("waiver") Map<String, Object> waiver,
        @JsonProperty("details") Map<String, Object> details) {

    public Finding {
        id = ContractValues.nonBlank(id, "id");
        source = ContractValues.nonBlank(source, "source");
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        message = ContractValues.nonBlank(message, "message");
        if (focusIri != null) {
            focusIri = ContractValues.absoluteIri(focusIri, "focus_iri");
        }
        if (ruleId != null) {
            ruleId = ContractValues.nonBlank(ruleId, "rule_id");
        }
        waiver = waiver == null ? null : ContractValues.map(waiver);
        details = ContractValues.map(details);
    }
}
