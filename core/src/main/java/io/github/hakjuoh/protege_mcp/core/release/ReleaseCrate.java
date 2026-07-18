package io.github.hakjuoh.protege_mcp.core.release;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;

/**
 * RO-Crate 1.1 {@code ro-crate-metadata.json} for a release bundle. There is no core RO-Crate
 * checksum property, so file digests use the precedented Workflow-Run-Crate term
 * {@code https://w3id.org/ro/terms/workflow-run#sha256}, declared in the crate's local
 * {@code @context}. Provenance is one schema.org {@code CreateAction} (inputs as {@code object}, the
 * tool as {@code instrument}, artifacts as {@code result}) — the RO-Crate idiom, not PROV terms. The
 * metadata descriptor's {@code conformsTo} names the base spec plus a self-describing protege-mcp
 * release profile, so a standard RO-Crate reader accepts the crate and a protege-mcp reader can key
 * off the profile.
 */
public final class ReleaseCrate {

    public static final String RO_CRATE_CONTEXT = "https://w3id.org/ro/crate/1.1/context";
    public static final String RO_CRATE_SPEC = "https://w3id.org/ro/crate/1.1";
    public static final String RELEASE_PROFILE =
            "https://hakjuoh.github.io/protege-mcp/crate/release/1.0";
    public static final String SHA256_TERM = "https://w3id.org/ro/terms/workflow-run#sha256";
    private static final String TOOL_ID = "https://github.com/hakjuoh/protege-mcp";

    private ReleaseCrate() {
    }

    /** One bundled file: its crate-relative path, media type, digest, and byte length. */
    public record CrateFile(String path, String encodingFormat, String sha256, long bytes) { }

    /**
     * Build the crate metadata graph. {@code rootArtifactPath} is the primary ontology artifact
     * (the crate's {@code mainEntity}); {@code createdAt} is the CreateAction end time. Deterministic
     * except for that caller-supplied timestamp.
     */
    public static Map<String, Object> build(String projectId, String description, String versionIri,
            String createdAt, String rootArtifactPath, List<CrateFile> files) {
        List<Map<String, Object>> graph = new ArrayList<>();

        // Metadata descriptor (conformsTo the base spec + the release profile).
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("@id", "ro-crate-metadata.json");
        descriptor.put("@type", "CreativeWork");
        descriptor.put("conformsTo", List.of(idRef(RO_CRATE_SPEC), idRef(RELEASE_PROFILE)));
        descriptor.put("about", idRef("./"));
        graph.add(descriptor);

        // Root data entity.
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("@id", "./");
        root.put("@type", "Dataset");
        root.put("name", projectId + " release");
        root.put("description", description == null
                ? "protege-mcp verified ontology release" : description);
        root.put("datePublished", createdAt);
        root.put("license", idRef("https://spdx.org/licenses/CC-BY-4.0"));
        root.put("conformsTo", idRef(RELEASE_PROFILE));
        if (versionIri != null) {
            root.put("version", versionIri);
        }
        List<Map<String, Object>> hasPart = new ArrayList<>();
        for (CrateFile file : files) {
            hasPart.add(idRef(file.path()));
        }
        root.put("hasPart", hasPart);
        if (rootArtifactPath != null) {
            root.put("mainEntity", idRef(rootArtifactPath));
        }
        root.put("mentions", idRef("#release-action"));
        graph.add(root);

        // File entities with contentSize + wfrun#sha256.
        for (CrateFile file : files) {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("@id", file.path());
            entity.put("@type", "File");
            entity.put("name", file.path());
            if (file.encodingFormat() != null) {
                entity.put("encodingFormat", file.encodingFormat());
            }
            entity.put("contentSize", Long.toString(file.bytes()));
            entity.put("sha256", stripPrefix(file.sha256()));
            graph.add(entity);
        }

        // Provenance: one schema.org CreateAction.
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("@id", "#release-action");
        action.put("@type", "CreateAction");
        action.put("name", "protege-mcp release preparation");
        action.put("endTime", createdAt);
        action.put("instrument", idRef(TOOL_ID));
        List<Map<String, Object>> results = new ArrayList<>();
        for (CrateFile file : files) {
            results.add(idRef(file.path()));
        }
        action.put("result", results);
        graph.add(action);

        // The tool as a SoftwareApplication.
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("@id", TOOL_ID);
        tool.put("@type", "SoftwareApplication");
        tool.put("name", "protege-mcp");
        graph.add(tool);

        // The self-describing release profile entity.
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("@id", RELEASE_PROFILE);
        profile.put("@type", List.of("CreativeWork", "Profile"));
        profile.put("name", "protege-mcp release crate profile");
        profile.put("version", "1.0");
        graph.add(profile);

        // String base context plus a local term map for the checksum property.
        Map<String, Object> crate = new LinkedHashMap<>();
        crate.put("@context", List.of(RO_CRATE_CONTEXT, Map.of("sha256", SHA256_TERM)));
        crate.put("@graph", graph);
        return crate;
    }

    /** Canonical pretty JSON of a crate map. */
    public static String toJson(Map<String, Object> crate) {
        try {
            return ContractJson.mapper().copy()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(crate);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render release crate", e);
        }
    }

    private static Map<String, Object> idRef(String id) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("@id", id);
        return ref;
    }

    private static String stripPrefix(String sha256) {
        return sha256 != null && sha256.startsWith("sha256:") ? sha256.substring(7) : sha256;
    }
}
