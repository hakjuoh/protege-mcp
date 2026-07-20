package io.github.hakjuoh.protege_mcp.core.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;

/** Deterministic construction of the complete verified release artifact graph. */
public final class ReleaseBundleService {

    private ReleaseBundleService() {
    }

    public record Request(String projectId, String ontologyIri, String versionIri,
            String createdAt, Object license, String policySha256, String importLockSha256,
            String semanticFingerprint, boolean releaseStable, GateResult gate,
            byte[] ontologyBytes, String ontologyArtifactPath, String ontologyMediaType,
            Map<String, Object> baselineDiff, String baselineVersionIri, int findingLimit) {
        public Request {
            if (projectId == null || projectId.isBlank() || createdAt == null || createdAt.isBlank()
                    || semanticFingerprint == null || gate == null || ontologyBytes == null
                    || ontologyArtifactPath == null || ontologyMediaType == null) {
                throw new IllegalArgumentException("required release bundle inputs must not be blank");
            }
            if (findingLimit < 0 || findingLimit > 10_000) {
                throw new IllegalArgumentException("findingLimit must be between 0 and 10000");
            }
            requireOntologyArtifactPath(ontologyArtifactPath);
            if (!semanticFingerprint.equals(gate.semanticFingerprint())) {
                throw new IllegalArgumentException(
                        "semanticFingerprint must match the release gate");
            }
            ontologyBytes = ontologyBytes.clone();
            baselineDiff = baselineDiff == null ? null
                    : immutableMap(baselineDiff);
        }

        @Override
        public byte[] ontologyBytes() {
            return ontologyBytes.clone();
        }
    }

    public record Artifact(String path, String mediaType, byte[] content,
            String sha256, long bytes) {
        public Artifact {
            if (path == null || path.isBlank() || mediaType == null || content == null
                    || sha256 == null || bytes != content.length) {
                throw new IllegalArgumentException("invalid release artifact");
            }
            content = content.clone();
            if (!ArtifactStore.sha256(content).equals(sha256)) {
                throw new IllegalArgumentException("release artifact digest does not match content");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record Bundle(List<Artifact> artifacts, Map<String, Object> manifest) {
        public Bundle {
            artifacts = List.copyOf(artifacts);
            manifest = immutableMap(manifest);
        }
    }

    /** Build ontology, reports, optional diff, RO-Crate metadata, and the terminal manifest. */
    public static Bundle build(Request request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        List<Artifact> files = new ArrayList<>();
        files.add(artifact(request.ontologyArtifactPath(), request.ontologyMediaType(),
                request.ontologyBytes()));
        files.add(text("reports/qc.json", "application/json",
                ReleaseReports.json(request.gate())));
        files.add(text("reports/qc.md", "text/markdown",
                ReleaseReports.markdown(request.gate(), request.versionIri(),
                        request.findingLimit())));
        files.add(text("reports/qc.xml", "application/xml",
                ReleaseReports.junit(request.gate(), request.createdAt())));
        files.add(text("reports/qc.sarif", "application/sarif+json",
                ReleaseReports.sarifJson(request.gate(), request.ontologyArtifactPath())));
        if (request.baselineDiff() != null) {
            files.add(text("reports/diff.json", "application/json",
                    prettyJson(request.baselineDiff())));
        }

        List<ReleaseCrate.CrateFile> crateFiles = new ArrayList<>();
        for (Artifact file : files) {
            crateFiles.add(new ReleaseCrate.CrateFile(file.path(), file.mediaType(),
                    file.sha256(), file.bytes()));
        }
        String crate = ReleaseCrate.toJson(ReleaseCrate.build(request.projectId(), null,
                request.versionIri(), request.createdAt(), request.license(),
                request.ontologyArtifactPath(), crateFiles));
        files.add(text("ro-crate-metadata.json", "application/ld+json", crate));

        List<ReleaseManifest.Artifact> manifestArtifacts = new ArrayList<>();
        for (Artifact file : files) {
            manifestArtifacts.add(new ReleaseManifest.Artifact(
                    file.path(), file.sha256(), file.bytes()));
        }
        ReleaseManifest.Baseline baseline = request.baselineDiff() == null ? null
                : new ReleaseManifest.Baseline(request.baselineVersionIri(), "reports/diff.json");
        Map<String, Object> manifest = ReleaseManifest.build(request.projectId(),
                request.ontologyIri(), request.versionIri(), request.createdAt(),
                request.policySha256(), request.importLockSha256(), request.semanticFingerprint(),
                request.releaseStable(), manifestArtifacts, request.gate().gate().json(),
                "reports/qc.json", baseline);
        files.add(text("manifest.json", "application/json", ReleaseManifest.toJson(manifest)));
        return new Bundle(files, manifest);
    }

    private static Artifact artifact(String path, String mediaType, byte[] content) {
        return new Artifact(path, mediaType, content, ArtifactStore.sha256(content), content.length);
    }

    private static void requireOntologyArtifactPath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0
                || value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("ontologyArtifactPath must be a portable file name");
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("ontologyArtifactPath is invalid", invalid);
        }
        if (path.isAbsolute() || path.getNameCount() != 1 || !path.normalize().equals(path)
                || ".".equals(value) || "..".equals(value)
                || value.endsWith(".") || value.endsWith(" ")
                || "manifest.json".equalsIgnoreCase(value)
                || "ro-crate-metadata.json".equalsIgnoreCase(value)
                || "reports".equalsIgnoreCase(value) || windowsDeviceName(value)) {
            throw new IllegalArgumentException(
                    "ontologyArtifactPath must be a non-reserved release-root file name");
        }
    }

    private static boolean windowsDeviceName(String value) {
        String stem = value;
        int dot = stem.indexOf('.');
        if (dot >= 0) stem = stem.substring(0, dot);
        String upper = stem.toUpperCase(java.util.Locale.ROOT);
        return upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX")
                || upper.equals("NUL") || upper.equals("CLOCK$")
                || upper.matches("COM[1-9]") || upper.matches("LPT[1-9]");
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutable(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), immutable(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list.stream().map(ReleaseBundleService::immutable).toList());
        }
        return value;
    }

    private static Artifact text(String path, String mediaType, String content) {
        return artifact(path, mediaType, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String prettyJson(Map<String, Object> map) {
        try {
            return ContractJson.mapper().copy()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(map);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("could not render release diff report", error);
        }
    }
}
