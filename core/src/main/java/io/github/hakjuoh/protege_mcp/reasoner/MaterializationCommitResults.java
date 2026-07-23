package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared cross-adapter projection of one materialization commit outcome. */
public final class MaterializationCommitResults {
    private MaterializationCommitResults() {
    }

    public static Map<String, Object> result(MaterializationArtifact artifact,
            boolean committed, int added, int existing, boolean singleUndo,
            String targetDigest) {
        if (artifact == null || added < 0 || existing < 0) {
            throw new IllegalArgumentException("materialization commit result is invalid");
        }
        long asserted = artifact.report().get("asserted_collision_count")
                instanceof Number number ? number.longValue() : 0L;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", committed ? "committed" : "noop");
        result.put("committed", committed);
        result.put("artifact_id", artifact.artifactId());
        result.put("artifact_fingerprint", artifact.artifactFingerprint());
        result.put("artifact_digest", artifact.artifactDigest());
        result.put("materialization_digest", artifact.materializationDigest());
        result.put("destination", artifact.request().destination().toMap());
        result.put("added_axioms", added);
        result.put("existing_axioms", existing);
        result.put("asserted_collision_count", asserted);
        result.put("single_undo", singleUndo);
        if (targetDigest != null) result.put("target_digest", targetDigest);
        return result;
    }
}
