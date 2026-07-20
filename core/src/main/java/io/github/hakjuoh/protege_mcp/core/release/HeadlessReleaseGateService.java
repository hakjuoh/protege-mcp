package io.github.hakjuoh.protege_mcp.core.release;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;
import io.github.hakjuoh.protege_mcp.core.owl.FormatCompatibility;
import io.github.hakjuoh.protege_mcp.core.owl.OwlParsingErrors;
import io.github.hakjuoh.protege_mcp.core.owl.VerifiedOntologyRoundTrip;
import io.github.hakjuoh.protege_mcp.core.qc.HeadlessProjectQcService;
import io.github.hakjuoh.protege_mcp.core.qc.ImportGraphAnalysisService;
import io.github.hakjuoh.protege_mcp.core.workspace.ProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceSnapshot;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.ro_crate.RoCrateProjectProfileValidator;

/**
 * Read-only headless release gate and bundle preparation over one captured workspace snapshot.
 * Headless release evaluation is unconditionally offline-strict: there is no network-allow
 * abstention path, even when an interactive plugin session could explicitly permit one.
 */
public final class HeadlessReleaseGateService {

    public static final String PREVIEW = "PREVIEW";
    public static final long MAX_BASELINE_MANIFEST_BYTES = 1_048_576L;
    public static final int MAX_BASELINE_ARTIFACTS = 256;

    private HeadlessReleaseGateService() {
    }

    public record Request(Path baselineManifest, String createdAt, int limit) {
        public Request {
            if (limit < 0 || limit > 10_000) {
                throw new IllegalArgumentException("limit must be between 0 and 10000");
            }
            createdAt = createdAt == null ? PREVIEW : validateTimestamp(createdAt);
        }
    }

    public record SourcePin(Path path, String sha256, long bytes) {
        public SourcePin {
            path = path.toAbsolutePath().normalize();
        }

        public boolean current() {
            try {
                if (Files.isSymbolicLink(path)
                        || !path.toRealPath().equals(path.toAbsolutePath().normalize())) {
                    return false;
                }
                return capturePin(path, Long.MAX_VALUE).equals(this);
            } catch (IOException | RuntimeException changed) {
                return false;
            }
        }
    }

    public record Result(GateResult gate, ReleaseBundleService.Bundle bundle,
            Map<String, Object> details, List<SourcePin> baselineSources) {
        public Result {
            details = immutableMap(details);
            baselineSources = List.copyOf(baselineSources);
        }

        public boolean passed() {
            return gate.gate() == GateStatus.PASS;
        }
    }

    /** Evaluate without closing the caller-owned snapshot or writing any project file. */
    public static Result evaluate(ProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            OWLReasonerFactory reasonerFactory, Request request, LocalDate today) throws IOException {
        if (workspace == null || snapshot == null || request == null || today == null) {
            throw new IllegalArgumentException("release evaluation arguments must not be null");
        }
        ProjectPolicy policy = snapshot.policy();
        HeadlessProjectQcService.Result qc = HeadlessProjectQcService.runCaptured(
                workspace, snapshot, reasonerFactory, request.limit(), today);
        Map<String, Object> releasePolicy = object(policy.effective().get("release"));
        String formatName = string(releasePolicy.get("format"));
        if (formatName == null) formatName = "turtle";
        FormatSpec format = format(formatName);
        boolean requireVersion = !Boolean.FALSE.equals(releasePolicy.get("require_version_iri"));
        boolean requireClean = !Boolean.FALSE.equals(
                releasePolicy.get("require_clean_round_trip"));
        OWLOntology root = snapshot.root();
        String ontologyIri = root.getOntologyID().getOntologyIRI().isPresent()
                ? root.getOntologyID().getOntologyIRI().get().toString() : null;
        String versionIri = root.getOntologyID().getVersionIRI().isPresent()
                ? root.getOntologyID().getVersionIRI().get().toString() : null;
        Object license = releaseLicense(snapshot);
        List<Finding> releaseFindings = new ArrayList<>();
        if (license == null) {
            releaseFindings.add(finding("release.license_unavailable",
                    "The validated project metadata has no reusable release license.", Map.of()));
        }
        if (requireVersion && versionIri == null) {
            releaseFindings.add(finding("release.version_iri_missing",
                    "The root ontology has no version IRI, but release.require_version_iri is true.",
                    Map.of()));
        }
        boolean releaseStable = snapshot.fingerprint().releaseStable();
        if (!releaseStable) {
            releaseFindings.add(finding("release.fingerprint_unstable",
                    "The ontology fingerprint is session-only and cannot support a release manifest.",
                    Map.of("fingerprint_stability", snapshot.fingerprint().stability())));
        }

        ImportGraphAnalysisService.Report imports = ImportGraphAnalysisService.analyze(root);
        List<Map<String, Object>> provenance = provenance(imports, policy);
        for (Map<String, Object> member : provenance) {
            if (!"local_file".equals(member.get("backed_by"))) {
                releaseFindings.add(finding("imports.remote_backed", "imports",
                        "A release closure member is not backed by a local file.",
                        Map.of("document", value(member.get("document")),
                                "source_type", value(member.get("source_type")))));
            }
        }

        Map<String, Object> roundTrip = new LinkedHashMap<>();
        roundTrip.put("format", formatName);
        VerifiedOntologyRoundTrip.Result serialized = null;
        if (!releaseStable) {
            roundTrip.put("clean", false);
            roundTrip.put("skipped", true);
            roundTrip.put("reason", "fingerprint is not release-stable");
        } else {
            try {
                serialized = VerifiedOntologyRoundTrip.serialize(root, format.format());
                roundTrip.put("clean", true);
                roundTrip.put("round_trip_class", serialized.roundTripClass());
            } catch (IOException | IllegalArgumentException error) {
                roundTrip.put("clean", false);
                roundTrip.put("error", message(error));
                if (requireClean) {
                    releaseFindings.add(finding("release.round_trip_failed",
                            "Verified release serialization failed: " + message(error),
                            Map.of("required", true)));
                }
            }
        }
        if (FormatCompatibility.isOboFormat(format.format())) {
            roundTrip.put("obo_compatibility",
                    FormatCompatibility.oboCompatibility(root).toJson());
        }
        FormatCompatibility.LossyWarning loss =
                FormatCompatibility.detectLoss(root, format.format());
        if (loss != null) {
            roundTrip.put("lossy_format", loss.toJson());
            releaseFindings.add(new Finding("release.lossy_format", "release",
                    requireClean ? FindingSeverity.ERROR : FindingSeverity.WARNING,
                    "The release format cannot represent the ontology without loss: "
                            + String.join("; ", loss.reasons()), null, null, null,
                    "release.lossy_format", null,
                    Map.of("format", formatName, "reasons", loss.reasons())));
        }

        BaselineEvidence baseline = request.baselineManifest() == null
                ? BaselineEvidence.none() : compareBaseline(policy, snapshot,
                        request.baselineManifest(), request.limit());
        if (!"not_compared".equals(baseline.summary().get("status"))
                && !"compared".equals(baseline.summary().get("status"))) {
            releaseFindings.add(finding("release.baseline_invalid",
                    "The requested baseline release could not be verified and compared.",
                    Map.of("status", value(baseline.summary().get("status")))));
        }

        boolean sourcesCurrent = workspace.isCurrent(snapshot)
                && baseline.sources().stream().allMatch(SourcePin::current);
        if (!sourcesCurrent) {
            releaseFindings.add(finding("release.snapshot_changed",
                    "The project or baseline sources changed while the release gate was running.",
                    Map.of()));
        }
        GateResult gate = ReleaseGateService.aggregate(qc.output(), releaseFindings,
                qc.report().gate().json());
        ReleaseBundleService.Bundle bundle = null;
        if (gate.gate() == GateStatus.PASS && serialized != null) {
            bundle = ReleaseBundleService.build(new ReleaseBundleService.Request(
                    string(policy.effective().get("project_id")), ontologyIri, versionIri,
                    request.createdAt(), license, policy.digest(), snapshot.importLockDigest(),
                    snapshot.fingerprint().semanticFingerprint(), true, gate, serialized.content(),
                    "ontology" + format.extension(), format.mediaType(), baseline.diff(),
                    string(baseline.summary().get("version_iri")), request.limit()));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gate", gate.gate().json());
        details.put("network", "deny");
        details.put("version_iri", versionIri);
        details.put("resolved_imports", provenance);
        details.put("round_trip", roundTrip);
        details.put("baseline", baseline.summary());
        details.put("snapshot_consistent", sourcesCurrent);
        details.put("created_at", request.createdAt());
        details.put("artifacts_available", bundle != null);
        return new Result(gate, bundle, details, baseline.sources());
    }

    private static BaselineEvidence compareBaseline(ProjectPolicy policy, WorkspaceSnapshot snapshot,
            Path requested, int limit) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("compared", false);
        List<SourcePin> pins = new ArrayList<>();
        Path projectRoot = policy.projectRoot().toAbsolutePath().normalize();
        try {
            Path root = policy.projectRoot().toRealPath();
            projectRoot = root;
            Path candidate = requested.isAbsolute() ? requested : root.resolve(requested);
            Path manifest = candidate.toRealPath();
            if (Files.isSymbolicLink(candidate) || !manifest.startsWith(root)
                    || !Files.isRegularFile(manifest)) {
                throw new IOException("baseline manifest must be a project-confined regular file");
            }
            byte[] manifestBytes = boundedRead(manifest, MAX_BASELINE_MANIFEST_BYTES);
            pins.add(new SourcePin(manifest, ArtifactStore.sha256(manifestBytes),
                    manifestBytes.length));
            Map<String, Object> parsed = ContractJson.mapper().copy()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                    .readValue(manifestBytes,
                            new TypeReference<LinkedHashMap<String, Object>>() { });
            if (!Integer.valueOf(ReleaseManifest.MANIFEST_VERSION)
                    .equals(parsed.get("manifest_version"))) {
                throw new IOException("unsupported baseline manifest version");
            }
            summary.put("manifest", portable(root, manifest));
            summary.put("version_iri", parsed.get("version_iri"));
            if (!(parsed.get("artifacts") instanceof List<?> artifacts)
                    || artifacts.isEmpty() || artifacts.size() > MAX_BASELINE_ARTIFACTS) {
                throw new IOException("baseline artifact list is empty or exceeds "
                        + MAX_BASELINE_ARTIFACTS);
            }
            List<Map<String, Object>> checks = new ArrayList<>();
            summary.put("artifact_checks", checks);
            Set<Path> seen = new LinkedHashSet<>();
            byte[] primary = null;
            Path primaryPath = null;
            for (int index = 0; index < artifacts.size(); index++) {
                if (!(artifacts.get(index) instanceof Map<?, ?> raw)) {
                    throw new IOException("baseline artifact entry must be an object");
                }
                String relative = string(raw.get("path"));
                String expectedSha = string(raw.get("sha256"));
                long expectedBytes = raw.get("bytes") instanceof Number number
                        ? number.longValue() : -1;
                Path artifact = baselineArtifact(manifest.getParent(), root, relative);
                if (!seen.add(artifact)) throw new IOException("duplicate baseline artifact path");
                long bound = index == 0 ? VerifiedOntologyRoundTrip.MAX_ARTIFACT_BYTES
                        : Long.MAX_VALUE;
                SourcePin pin = capturePin(artifact, bound);
                pins.add(pin);
                boolean valid = expectedBytes == pin.bytes()
                        && pin.sha256().equalsIgnoreCase(expectedSha);
                checks.add(Map.of("path", relative, "sha256_match", valid,
                        "bytes_match", expectedBytes == pin.bytes()));
                if (!valid) throw new IOException("baseline artifact checksum or size mismatch: "
                        + relative);
                if (index == 0) {
                    primary = boundedRead(artifact, VerifiedOntologyRoundTrip.MAX_ARTIFACT_BYTES);
                    if (!ArtifactStore.sha256(primary).equals(pin.sha256())) {
                        throw new IOException("baseline primary changed while being captured");
                    }
                    primaryPath = artifact;
                }
            }
            List<String> unresolved = new ArrayList<>();
            OWLOntology baseline = loadOffline(primary, primaryPath, unresolved);
            try {
                Map<String, Object> diff = SemanticDiffService.diff(
                        baseline, snapshot.root(), false, limit, List.of());
                List<String> distinct = unresolved.stream().distinct().sorted().toList();
                diff.put("left_document_unresolved_imports", distinct);
                summary.put("compared", true);
                summary.put("status", "compared");
                summary.put("mode", "asserted");
                summary.put("unresolved_imports", distinct);
                summary.put("compatibility", diff.get("compatibility"));
                summary.put("identical", diff.get("identical"));
                return new BaselineEvidence(summary, diff, pins);
            } finally {
                removeManager(baseline.getOWLOntologyManager());
            }
        } catch (IOException | RuntimeException error) {
            summary.put("status", "error");
            summary.put("error", portableMessage(projectRoot, message(error)));
            return new BaselineEvidence(summary, null, pins);
        }
    }

    private static OWLOntology loadOffline(byte[] bytes, Path path, List<String> unresolved)
            throws IOException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        Path placeholder = Files.createTempFile("protege-mcp-baseline-imports-", ".ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n");
            IRI placeholderIri = IRI.create(placeholder.toUri());
            manager.getIRIMappers().add(ignored -> placeholderIri);
            OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.THROW_EXCEPTION)
                    .setFollowRedirects(false);
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                    new StreamDocumentSource(new ByteArrayInputStream(bytes), IRI.create(path.toUri())),
                    config);
            ontology.getImportsDeclarations().forEach(declaration ->
                    unresolved.add(declaration.getIRI().toString()));
            return ontology;
        } catch (OWLOntologyCreationException error) {
            removeManager(manager);
            throw new IOException("could not parse verified baseline ontology: "
                    + OwlParsingErrors.conciseMessage(error), error);
        } catch (RuntimeException error) {
            removeManager(manager);
            throw new IOException("could not parse verified baseline ontology: "
                    + message(error), error);
        } finally {
            Files.deleteIfExists(placeholder);
        }
    }

    private static Path baselineArtifact(Path base, Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\\') >= 0
                || relative.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IOException("baseline artifact path must be portable and relative");
        }
        final Path value;
        try {
            value = Path.of(relative);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid baseline artifact path", invalid);
        }
        if (value.isAbsolute() || !value.normalize().equals(value)) {
            throw new IOException("baseline artifact path escapes its manifest directory");
        }
        for (Path segment : value) {
            if ("..".equals(segment.toString())) {
                throw new IOException("baseline artifact path escapes its manifest directory");
            }
        }
        Path realBase = base.toRealPath();
        Path lexical = realBase.resolve(value).toAbsolutePath().normalize();
        Path resolved = lexical.toRealPath();
        if (!lexical.equals(resolved) || !resolved.startsWith(realBase) || !resolved.startsWith(root)
                || !Files.isRegularFile(resolved)) {
            throw new IOException("baseline artifact is not a project-confined regular file");
        }
        return resolved;
    }

    private static byte[] boundedRead(Path path, long maximum) throws IOException {
        if (maximum > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("boundedRead maximum exceeds the JVM byte-array limit");
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes((int) maximum + 1);
            if (bytes.length > maximum) throw new IOException("file exceeds " + maximum + " bytes");
            return bytes;
        }
    }

    private static SourcePin capturePin(Path path, long maximum) throws IOException {
        MessageDigest digest = digest();
        long bytes = 0;
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes += read;
                if (bytes > maximum) throw new IOException("baseline artifact exceeds size bound");
            }
        }
        return new SourcePin(path.toRealPath(), "sha256:" + hex(digest.digest()), bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private static List<Map<String, Object>> provenance(ImportGraphAnalysisService.Report report,
            ProjectPolicy policy) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> ontology : report.ontologies()) {
            if ("root".equals(ontology.get("role"))) continue;
            String source = string(ontology.get("source_type"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ontology_iri", ontology.get("ontology_iri"));
            row.put("version_iri", ontology.get("version_iri"));
            row.put("document", portableDocument(policy, string(ontology.get("document_iri"))));
            row.put("source_type", source);
            row.put("backed_by", "local".equals(source) ? "local_file" : source);
            result.add(row);
        }
        return result;
    }

    private static Object releaseLicense(WorkspaceSnapshot snapshot) {
        List<Path> manifests = snapshot.capturedAssets()
                .getOrDefault("interoperability_manifest", List.of());
        if (manifests.size() != 1) return null;
        try {
            Path manifest = manifests.get(0);
            if (!Files.isRegularFile(manifest)
                    || Files.size(manifest) > RoCrateProjectProfileValidator.MAX_BYTES) return null;
            var crate = ContractJson.mapper().readTree(manifest.toFile());
            var graph = crate == null ? null : crate.get("@graph");
            if (graph == null || !graph.isArray()) return null;
            for (var entity : graph) {
                if ("./".equals(entity.path("@id").asText())) {
                    var license = entity.get("license");
                    return license == null || license.isNull() ? null
                            : ContractJson.mapper().convertValue(license, Object.class);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static Finding finding(String id, String message, Map<String, Object> details) {
        return finding(id, "release", message, details);
    }

    private static Finding finding(String id, String source, String message,
            Map<String, Object> details) {
        return new Finding(id, source, FindingSeverity.ERROR, message,
                null, null, null, id, null, details);
    }

    private record FormatSpec(OWLDocumentFormat format, String extension, String mediaType) {
    }

    private static FormatSpec format(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "turtle" -> new FormatSpec(new TurtleDocumentFormat(), ".ttl", "text/turtle");
            case "rdfxml" -> new FormatSpec(new RDFXMLDocumentFormat(), ".owl",
                    "application/rdf+xml");
            case "functional" -> new FormatSpec(new FunctionalSyntaxDocumentFormat(), ".ofn",
                    "application/owl-functional");
            case "owlxml" -> new FormatSpec(new OWLXMLDocumentFormat(), ".owx",
                    "application/owl+xml");
            case "obo" -> new FormatSpec(new OBODocumentFormat(), ".obo", "text/obo");
            default -> throw new IllegalArgumentException("unsupported release format: " + value);
        };
    }

    private record BaselineEvidence(Map<String, Object> summary, Map<String, Object> diff,
            List<SourcePin> sources) {
        static BaselineEvidence none() {
            return new BaselineEvidence(Map.of("compared", false, "status", "not_compared"),
                    null, List.of());
        }
    }

    private static String validateTimestamp(String value) {
        if (PREVIEW.equals(value)) return value;
        if (!value.endsWith("Z")) {
            throw new IllegalArgumentException("createdAt must be an ISO-8601 UTC instant");
        }
        try {
            Instant.parse(value);
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("createdAt must be an ISO-8601 UTC instant", invalid);
        }
    }

    private static void removeManager(OWLOntologyManager manager) {
        for (OWLOntology ontology : new ArrayList<>(manager.getOntologies())) {
            manager.removeOntology(ontology);
        }
    }

    private static String portable(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static Object portableDocument(ProjectPolicy policy, String document) {
        if (document == null) return null;
        try {
            URI uri = URI.create(document);
            if (!"file".equalsIgnoreCase(uri.getScheme())) return document;
            Path root = policy.projectRoot().toRealPath();
            Path path = Path.of(uri).toRealPath();
            return path.startsWith(root) ? portable(root, path) : path.getFileName().toString();
        } catch (IOException | RuntimeException invalid) {
            return "local ontology document";
        }
    }

    private static String portableMessage(Path root, String message) {
        String result = message;
        Path normalized = root.toAbsolutePath().normalize();
        result = result.replace(normalized.toUri().toString(), "")
                .replace(normalized.toString() + java.io.File.separator, "")
                .replace(normalized.toString(), ".");
        return result;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static Object value(Object value) {
        return value == null ? "" : value;
    }

    private static String string(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
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
            return List.copyOf(list.stream().map(HeadlessReleaseGateService::immutable).toList());
        }
        return value;
    }
}
