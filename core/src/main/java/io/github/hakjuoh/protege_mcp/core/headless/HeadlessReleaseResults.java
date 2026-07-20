package io.github.hakjuoh.protege_mcp.core.headless;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.core.release.HeadlessReleaseService;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseBundleService;

/** Stable, bounded JSON projection shared by CLI commands and headless MCP tools. */
public final class HeadlessReleaseResults {

    private static final int MAX_INLINE_CHARS = 8_000;

    private HeadlessReleaseResults() {
    }

    public static Map<String, Object> project(HeadlessReleaseService.Result release) {
        Map<String, Object> result = new LinkedHashMap<>(release.details());
        result.put("prepared", release.prepared());
        result.put("dry_run", release.dryRun());
        result.put("gate", release.gate().gate().json());
        result.put("semantic_fingerprint", release.gate().semanticFingerprint());
        result.put("required_stages", release.gate().requiredStages());
        result.put("stages", release.gate().stages());
        result.put("findings", release.gate().findings());
        if (release.outputDirectory() != null) result.put("output_dir", release.outputDirectory());

        List<Map<String, Object>> artifacts = new ArrayList<>();
        ReleaseBundleService.Bundle bundle = release.bundle();
        if (bundle != null) {
            for (ReleaseBundleService.Artifact artifact : bundle.artifacts()) {
                artifacts.add(Map.of("path", artifact.path(), "sha256", artifact.sha256(),
                        "bytes", artifact.bytes(), "media_type", artifact.mediaType()));
            }
            result.put("manifest", bundle.manifest());
            if (release.dryRun()) {
                Map<String, String> reports = new LinkedHashMap<>();
                for (ReleaseBundleService.Artifact artifact : bundle.artifacts()) {
                    if (artifact.path().startsWith("reports/")) {
                        reports.put(artifact.path(), boundedPreview(artifact.content()));
                    } else if ("ro-crate-metadata.json".equals(artifact.path())) {
                        result.put("ro_crate", boundedPreview(artifact.content()));
                    }
                }
                result.put("reports", reports);
            }
        }
        result.put("artifacts", artifacts);
        if (release.publication() != null) {
            Map<String, Object> publication = new LinkedHashMap<>();
            publication.put("previous_existed", release.publication().previousExisted());
            publication.put("previous_tree_sha256", release.publication().previousTreeSha256());
            publication.put("installed_tree_sha256", release.publication().installedTreeSha256());
            publication.put("installed_bytes", release.publication().installedBytes());
            publication.put("backup_path", release.publication().backupPath());
            result.put("publication", publication);
        }
        return result;
    }

    public static String boundedPreview(byte[] content) {
        String value = new String(content, StandardCharsets.UTF_8);
        if (value.length() <= MAX_INLINE_CHARS) return value;
        int end = MAX_INLINE_CHARS;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "\n...[truncated]";
    }
}
