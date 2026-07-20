package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared constants and a small JSON helper for the competency-question subsystem (F3): the convention
 * ids, the sidecar/dir/annotation naming, the on-disk manifest {@code version}, and the CQ annotation
 * vocabulary. Kept in one place so the stores, the runner and the tools agree on the wire contract.
 */
final class Cq {

    private Cq() {
    }

    // ------------------------------------------------------------------ conventions
    static final String CONV_ROBOT =
            io.github.hakjuoh.protege_mcp.core.qc.ValidationAssetLoader.ROBOT;
    static final String CONV_MANIFEST =
            io.github.hakjuoh.protege_mcp.core.qc.ValidationAssetLoader.MANIFEST;
    static final String CONV_ANNOTATIONS =
            io.github.hakjuoh.protege_mcp.core.qc.ValidationAssetLoader.ANNOTATIONS;

    // ------------------------------------------------------------------ on-disk layout
    /** The default writer's query folder next to the ontology document. */
    static final String DIR = "cqs";
    /** {@code <basename>-cqs.json} is the full-fidelity manifest next to the document. */
    static final String MANIFEST_SUFFIX = "-cqs.json";
    /** The manifest format version — a public contract the moment it is written (see the plan §3.1). */
    static final int MANIFEST_VERSION =
            io.github.hakjuoh.protege_mcp.core.qc.ValidationAssetLoader.MANIFEST_VERSION;

    // ------------------------------------------------------------------ ontology-annotations vocab
    /** The ontology-level annotation property under which a CQ (JSON literal) is stored inside the artifact. */
    static final String ANNOTATION_IRI =
            io.github.hakjuoh.protege_mcp.core.qc.ValidationAssetLoader.CQ_ANNOTATION_IRI;

    // ------------------------------------------------------------------ JSON helper
    static final JsonIo JSON = new JsonIo();

    /** A minimal JSON reader/writer over Jackson (a direct dependency); never leaks a checked exception. */
    static final class JsonIo {
        private final ObjectMapper mapper = new ObjectMapper();

        private JsonIo() {
        }

        /** Parse {@code json} to a generic tree ({@code Map}/{@code List}/scalars), or {@code null} on error. */
        Object readOrNull(String json) {
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            try {
                return mapper.readValue(json, Object.class);
            } catch (RuntimeException | IOException e) {
                return null;
            }
        }

        /** Parse {@code json} to a mutable ordered {@code Map}, or {@code null} on error / non-object. */
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> readMapOrNull(String json) {
            Object parsed = readOrNull(json);
            return parsed instanceof java.util.Map ? (java.util.Map<String, Object>) parsed : null;
        }

        /** Pretty-print {@code value}; throws {@link ToolArgException} on failure (only for real IO paths). */
        String writePretty(Object value) {
            try {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new ToolArgException("Could not serialise competency questions to JSON: "
                        + e.getMessage());
            }
        }

        /** Compact JSON for {@code value}, or {@code ""} on failure (used in {@code .rq} header comments). */
        String toJsonOrEmpty(Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return "";
            }
        }
    }
}
