package io.github.hakjuoh.protege_mcp.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;
import io.github.hakjuoh.protege_mcp.core.owl.OwlParsingErrors;
import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

/**
 * Java 17 headless surface proving the core has no Protégé runtime dependency.
 *
 * <p>Exit codes (PLAN §10.2): {@code 0} a gate/command passed; {@code 1} a validation/release gate
 * FAILED (a clean, loaded, but non-passing result); {@code 2} a configuration/usage error; {@code 3}
 * an execution/infrastructure error. Adopting {@code 1} for an invalid-but-loaded policy is a
 * documented change from the {@code 0.6.1} mapping, which returned {@code 2}.
 */
public final class Main {

    public static final String VERSION = "0.7.0";

    /** Windows drive-letter absolute path (e.g. {@code C:\a}); Path.of on POSIX does not flag it. */
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    /**
     * Full project QC (the reasoner-, profile-, structural-, governance-, invariants-, cqs-, and
     * shacl-stage gate) is not reachable from the headless CLI: those stages run against Protégé's
     * OWLModelManager-bound QC executor and a reasoner the CLI does not bundle. This command validates
     * only the project policy (syntax, semantic references, and local assets).
     */
    private static final String QC_DEFERRED_NOTE =
            "Full project QC (reasoner/profile/structural/governance/invariants/cqs/shacl stages) "
                    + "requires the in-Protégé run_project_qc tool or a future headless runner with a "
                    + "bundled reasoner; this command validates only the project policy (syntax, semantic "
                    + "references, and local assets).";

    /** ADR 0005 decision 6: the headless CLI is already network=deny, so --no-network is redundant. */
    private static final String NO_NETWORK_NOTE =
            "accepted redundant affirmation: the headless CLI performs no network access, so --no-network "
                    + "(network=deny) already holds (ADR 0005 decision 6).";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            // writeValue(out, ...) must not close System.out: the trailing out.println() that
            // terminates the JSON document with a newline runs after it.
            .disable(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    private Main() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0) {
                err.println(usage());
                return 2;
            }
            switch (args[0]) {
                case "--version":
                case "version":
                    if (args.length == 1) {
                        out.println("protege-mcp-cli " + VERSION);
                        return 0;
                    }
                    break;
                case "--help":
                case "-h":
                case "help":
                    if (args.length == 1) {
                        out.println(usage());
                        return 0;
                    }
                    break;
                case "validate-policy":
                    return validatePolicy(args, out);
                case "validate":
                    return validate(args, out);
                case "diff":
                    return diff(args, out);
                case "release":
                case "imports":
                case "serve":
                    return deferred(args[0], err);
                default:
                    break;
            }
            err.println(usage());
            return 2;
        } catch (IllegalArgumentException e) {
            err.println("configuration error: " + message(e));
            return 2;
        } catch (Exception e) {
            err.println("execution error: " + message(e));
            return 3;
        }
    }

    // ===================================================================== validate-policy / validate

    private static int validatePolicy(String[] args, PrintStream out) throws Exception {
        Options opts = parse(args, Set.of("--project"), Set.of());
        // Headless policy syntax/asset validation has no installed Protégé reasoner registry.
        // Runtime reasoner availability is checked by the adapter that executes project QC.
        ProjectPolicy policy = ProjectPolicyLoader.load(Path.of(required(opts, "--project")), null);
        JSON.writeValue(out, policyResult(policy));
        out.println();
        return exitFor(policy);
    }

    /**
     * Headless policy validation with report formats. Renders the same policy result as
     * {@code validate-policy}, tagged with {@code scope: policy_validation} and a note that the full
     * reasoner-dependent QC gate is not run here. {@code --format junit|sarif} is deliberately
     * unsupported: those emitters project a QC {@link io.github.hakjuoh.protege_mcp.contracts.GateResult}
     * whose mandatory semantic fingerprint a policy-only validation cannot honestly produce without a
     * bundled reasoner, so forcing that shape would fabricate a gate result.
     */
    private static int validate(String[] args, PrintStream out) throws Exception {
        Options opts = parse(args, Set.of("--project", "--format"),
                Set.of("--no-network", "--no-external"));
        String format = opts.values.getOrDefault("--format", "json");
        boolean noExternal = opts.flags.contains("--no-external");
        ProjectPolicy policy = ProjectPolicyLoader.load(Path.of(required(opts, "--project")), null,
                null, null, noExternal);
        Map<String, Object> result = policyResult(policy);
        result.put("scope", "policy_validation");
        result.put("note", QC_DEFERRED_NOTE);
        recordNoNetwork(opts, result);
        if (noExternal) {
            result.put("no_external_paths", true);
        }
        switch (format) {
            case "json":
                JSON.writeValue(out, result);
                out.println();
                break;
            case "markdown":
                out.println(policyMarkdown(policy, opts.flags.contains("--no-network"), noExternal));
                break;
            case "junit":
            case "sarif":
                throw new IllegalArgumentException("--format '" + format + "' is not available for "
                        + "policy-only validation: it projects a full QC gate result (with a semantic "
                        + "fingerprint) that the headless CLI cannot produce without a bundled reasoner. "
                        + "Use --format json or markdown, or run the in-Protégé run_project_qc gate.");
            default:
                throw new IllegalArgumentException("unknown --format '" + format
                        + "'; expected json or markdown");
        }
        return exitFor(policy);
    }

    /**
     * The shared policy-validation JSON body (0.6.1 shape). One honesty refinement:
     * {@code policy_loaded} reports whether the policy actually reached evaluation (a digest
     * exists), so a missing or unparseable file — which the loader deliberately models as a
     * loaded-invalid result so plugin callers cannot silently fall back — no longer prints the
     * contradictory {@code policy_loaded:true} next to {@code policy_not_found}/{@code yaml_invalid}.
     * This aligns with the exit-code contract, where those states are {@code 2} (never evaluated).
     */
    /** Whether the policy actually reached evaluation (see {@link #policyResult}). */
    private static boolean reportedLoaded(ProjectPolicy policy) {
        return policy.loaded() && policy.digest() != null;
    }

    private static Map<String, Object> policyResult(ProjectPolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy_loaded", reportedLoaded(policy));
        result.put("valid", policy.valid());
        result.put("policy_path", policy.path() == null ? null : policy.path().toString());
        result.put("project_root", policy.projectRoot() == null ? null : policy.projectRoot().toString());
        result.put("policy_digest", policy.digest());
        result.put("errors", policy.issues().stream().filter(issue -> "error".equals(issue.severity()))
                .map(issue -> issue.toJson()).toList());
        result.put("warnings", policy.issues().stream().filter(issue -> !"error".equals(issue.severity()))
                .map(issue -> issue.toJson()).toList());
        return result;
    }

    private static String policyMarkdown(ProjectPolicy policy, boolean noNetwork, boolean noExternal) {
        StringBuilder md = new StringBuilder();
        md.append("# Policy validation: ").append(policy.valid() ? "valid" : "invalid").append("\n\n");
        md.append("- Scope: `policy_validation`\n");
        md.append("- Policy loaded: ").append(reportedLoaded(policy)).append("\n");
        md.append("- Policy path: ").append(policy.path() == null ? "_none_" : "`" + policy.path() + "`")
                .append("\n");
        md.append("- Project root: ")
                .append(policy.projectRoot() == null ? "_none_" : "`" + policy.projectRoot() + "`")
                .append("\n");
        md.append("- Policy digest: ")
                .append(policy.digest() == null ? "_none_" : "`" + policy.digest() + "`").append("\n");
        if (noNetwork) {
            md.append("- Network: `deny` (--no-network is a redundant affirmation; ADR 0005)\n");
        }
        if (noExternal) {
            md.append("- External paths: `deny` (--no-external caller constraint)\n");
        }
        md.append("\n> ").append(QC_DEFERRED_NOTE).append("\n\n");
        appendIssueList(md, policy, "error", "Errors");
        appendIssueList(md, policy, "warning", "Warnings");
        return md.toString();
    }

    private static void appendIssueList(StringBuilder md, ProjectPolicy policy, String severity,
            String heading) {
        List<io.github.hakjuoh.protege_mcp.policy.PolicyIssue> matching = policy.issues().stream()
                .filter(issue -> severity.equals(issue.severity())).toList();
        md.append("## ").append(heading).append(" (").append(matching.size()).append(")\n\n");
        if (matching.isEmpty()) {
            md.append("_None._\n\n");
            return;
        }
        for (io.github.hakjuoh.protege_mcp.policy.PolicyIssue issue : matching) {
            md.append("- **").append(issue.code()).append("**");
            if (issue.path() != null) {
                md.append(" `").append(issue.path()).append('`');
            }
            md.append(" — ").append(issue.message().replace("\n", " ")).append('\n');
        }
        md.append('\n');
    }

    /**
     * A valid policy passes (exit 0). An invalid policy that was still fully evaluated — parsed,
     * schema-valid, semantic checks run, so a canonical {@code policy_digest} was computed — is a
     * gate-style failure (exit 1). A policy that could not be brought to an evaluable form (missing,
     * unparseable, schema-invalid, oversized, unreadable) never gets a digest and is a configuration
     * error (exit 2).
     */
    private static int exitFor(ProjectPolicy policy) {
        if (policy.valid()) {
            return 0;
        }
        return policy.digest() != null ? 1 : 2;
    }

    // ===================================================================== diff

    private static int diff(String[] args, PrintStream out) throws Exception {
        Options opts = parse(args, Set.of("--left", "--right"), Set.of("--check", "--no-network"));
        ResolvedOperand left = resolveOperand(Path.of(required(opts, "--left")));
        ResolvedOperand right = resolveOperand(Path.of(required(opts, "--right")));

        // The comparison is asserted-only (imports are never diffed), so fetching owl:imports would
        // perform needless and surprising I/O. Load each root with a mapper that satisfies every import
        // from a private empty placeholder, then report every declared import so the deliberately
        // omitted closure stays visible.
        List<String> leftUnresolved = new ArrayList<>();
        List<String> rightUnresolved = new ArrayList<>();
        OWLOntology leftOntology = loadDocument(left, leftUnresolved);
        OWLOntology rightOntology = loadDocument(right, rightUnresolved);
        Map<String, Object> result = SemanticDiffService.diff(leftOntology, rightOntology, false, 100,
                rightUnresolved);
        result.put("left_unresolved_imports", List.copyOf(leftUnresolved));
        result.put("right_unresolved_imports", List.copyOf(rightUnresolved));
        if (left.manifest() != null) {
            result.put("left_manifest", left.manifest());
        }
        if (right.manifest() != null) {
            result.put("right_manifest", right.manifest());
        }
        recordNoNetwork(opts, result);
        JSON.writeValue(out, result);
        out.println();
        // Without --check, diff stays exit 0 regardless of the outcome (0.6.1 behavior preserved). With
        // --check it is a CI gate: exit 1 when the two ontologies are not identical.
        if (opts.flags.contains("--check") && !Boolean.TRUE.equals(result.get("identical"))) {
            return 1;
        }
        return 0;
    }

    /**
     * A diff operand: the ontology document to load, plus optional resolved-from-manifest metadata.
     * For a manifest operand, {@code verifiedBytes} carries the exact byte array the sha256/length
     * verification consumed, and the load parses those bytes — re-reading the path would let a
     * concurrent writer swap the content between verification and diff.
     */
    private record ResolvedOperand(Path document, Map<String, Object> manifest, byte[] verifiedBytes) {
    }

    /**
     * A {@code --left}/{@code --right} operand is a plain ontology document unless it is a {@code .json}
     * file that parses as a release manifest ({@code manifest_version} present). A manifest is resolved
     * to its PRIMARY ontology artifact ({@code artifacts[0]} — the release manifest always lists the
     * ontology artifact first), whose recorded sha256 is verified against the file on disk and whose
     * path is re-validated for containment before any diff. A non-manifest {@code .json} (e.g. a
     * JSON-LD ontology) is loaded unchanged.
     */
    private static ResolvedOperand resolveOperand(Path path) throws IOException {
        if (!isJsonName(path)) {
            return new ResolvedOperand(path, null, null);
        }
        Map<String, Object> manifest = readManifestOrNull(path);
        if (manifest == null) {
            return new ResolvedOperand(path, null, null);
        }
        return resolveManifest(path, manifest);
    }

    private static boolean isJsonName(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /**
     * A release manifest is a small metadata file; a multi-gigabyte {@code .json} operand is never a
     * manifest. Cap the pre-read so an untrusted CI operand cannot exhaust the heap through the
     * whole-file buffer this manifest probe would otherwise create — matching the loader's
     * {@code MAX_POLICY_BYTES} discipline. Oversized {@code .json} operands are simply not treated as
     * manifests and fall through to OWLAPI's streaming document loader.
     */
    static final long MAX_MANIFEST_BYTES = 1_048_576L;

    /** The manifest-verified primary artifact is diffed from ONE in-memory array (JVM array bound). */
    static final long MAX_VERIFIED_ARTIFACT_BYTES = Integer.MAX_VALUE - 8L;

    private static Map<String, Object> readManifestOrNull(Path path) {
        Map<String, Object> parsed;
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            // ONE bounded read: a size-then-read pair would let a concurrently growing .json bypass
            // the manifest-probe cap between the two calls.
            byte[] data;
            try (var in = Files.newInputStream(path)) {
                data = in.readNBytes((int) MAX_MANIFEST_BYTES + 1);
            }
            if (data.length > MAX_MANIFEST_BYTES) {
                return null;
            }
            parsed = ContractJson.mapper().readValue(data,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException | RuntimeException notAManifest) {
            // A .json that is not parseable JSON is left to the OWLAPI document loader (e.g. RDF/JSON),
            // which reports its own bounded parse error if it too cannot read it.
            return null;
        }
        return parsed != null && parsed.containsKey("manifest_version") ? parsed : null;
    }

    private static ResolvedOperand resolveManifest(Path manifestPath, Map<String, Object> manifest)
            throws IOException {
        if (!(manifest.get("manifest_version") instanceof Integer version) || version != 1) {
            throw new IllegalStateException(
                    "release manifest_version must be the supported integer value 1: "
                            + manifestPath);
        }
        Path manifestDir = manifestPath.toAbsolutePath().normalize().getParent();
        if (manifestDir == null) {
            throw new IllegalStateException("release manifest has no parent directory: " + manifestPath);
        }
        Object artifactsValue = manifest.get("artifacts");
        if (!(artifactsValue instanceof List<?> artifacts) || artifacts.isEmpty()) {
            throw new IllegalStateException(
                    "release manifest lists no artifact to diff against: " + manifestPath);
        }
        if (!(artifacts.get(0) instanceof Map<?, ?> primary)) {
            throw new IllegalStateException("release manifest artifact entry is malformed: " + manifestPath);
        }
        String artifactPath = str(primary.get("path"));
        String recordedSha = str(primary.get("sha256"));
        Object recordedBytes = primary.get("bytes");
        long recorded = recordedBytes instanceof Integer || recordedBytes instanceof Long
                ? ((Number) recordedBytes).longValue() : -1L;
        if (recorded < 0 || recorded > MAX_VERIFIED_ARTIFACT_BYTES) {
            throw new IllegalStateException("release manifest byte-length mismatch for '"
                    + artifactPath + "': the recorded length (" + recordedBytes
                    + ") is missing, not an integer, or exceeds the in-memory verification bound.");
        }
        Path resolved = authorizeArtifact(manifestDir, artifactPath);
        // ONE bounded read backs the sha256, the byte-length check, AND the diff parse below —
        // hashing the path and re-reading it later would attest a hash for content the diff never
        // saw; one extra byte proves a longer file without buffering it.
        byte[] verifiedBytes;
        try (var in = Files.newInputStream(resolved)) {
            verifiedBytes = in.readNBytes((int) recorded + 1);
        }
        if (verifiedBytes.length != recorded) {
            throw new IllegalStateException("release manifest byte-length mismatch for '"
                    + artifactPath + "': the manifest records " + recorded
                    + " bytes but the file does not match.");
        }
        String actualSha = ArtifactStore.sha256(verifiedBytes);
        if (recordedSha == null || !actualSha.equalsIgnoreCase(recordedSha)) {
            throw new IllegalStateException("release manifest sha256 mismatch for '" + artifactPath
                    + "': the manifest records " + recordedSha + " but the file hashes to " + actualSha
                    + " (a tampered or moved artifact must not be silently diffed).");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("version_iri", manifest.get("version_iri"));
        metadata.put("artifact_path", artifactPath);
        metadata.put("sha256_verified", true);
        metadata.put("bytes_verified", true);
        return new ResolvedOperand(resolved, metadata, verifiedBytes);
    }

    /**
     * Re-validate an untrusted, manifest-supplied artifact path (the same class of bug fixed in
     * ReleaseGate.compareBaseline): reject an absolute path outright, resolve it against the manifest
     * directory, and require the resolved and real paths to stay strictly beneath the manifest
     * directory's real path. A {@code ..} escape, an absolute path, or a symlink pointing outside is
     * refused before the artifact is diffed.
     */
    private static Path authorizeArtifact(Path manifestDir, String artifactPath) throws IOException {
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new IllegalStateException("release manifest primary artifact has no path");
        }
        if (WINDOWS_ABSOLUTE.matcher(artifactPath).matches() || artifactPath.startsWith("\\\\")) {
            throw new IllegalStateException("release manifest artifact path must be relative to the "
                    + "manifest directory: " + artifactPath);
        }
        Path relative;
        try {
            relative = Path.of(artifactPath);
        } catch (InvalidPathException e) {
            throw new IllegalStateException("release manifest artifact path is invalid: " + artifactPath);
        }
        if (relative.isAbsolute()) {
            throw new IllegalStateException("release manifest artifact path must be relative to the "
                    + "manifest directory: " + artifactPath);
        }
        Path realManifestDir = manifestDir.toRealPath();
        Path resolved = realManifestDir.resolve(relative).normalize();
        if (!resolved.startsWith(realManifestDir)) {
            throw new IllegalStateException("release manifest artifact path escapes the manifest "
                    + "directory: " + artifactPath);
        }
        // Resolve symlinks BEFORE any existence branch and collapse the not-found and
        // symlink-escape outcomes into ONE indistinguishable message: probing isRegularFile first
        // would follow a link to an out-of-tree target and leak, through two distinct errors,
        // whether that target exists on the victim's filesystem.
        Path real;
        try {
            real = resolved.toRealPath();
        } catch (IOException notFound) {
            throw new IllegalStateException(
                    "release manifest artifact could not be resolved beside the manifest: "
                            + artifactPath);
        }
        if (!real.startsWith(realManifestDir) || !Files.isRegularFile(real)) {
            throw new IllegalStateException(
                    "release manifest artifact could not be resolved beside the manifest: "
                            + artifactPath);
        }
        return real;
    }

    private static OWLOntology loadDocument(ResolvedOperand operand, List<String> unresolvedOut)
            throws Exception {
        Path path = operand.document();
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        // Map every import IRI to ONE private empty placeholder document, so an untrusted checkout
        // cannot make this asserted-only CLI contact localhost, cloud metadata, the public network,
        // or any local file it names — and cannot amplify temp usage either: however many imports a
        // hostile document declares, the cost is exactly this placeholder file. Each import must
        // genuinely RESOLVE rather than merely fail under SILENT handling (OWLAPI's Manchester frame
        // parser dereferences each imported ontology without a null guard), and the placeholder must
        // stay ANONYMOUS (Turtle/RDF-XML set a root's own ontology IRI only at the END of the
        // streaming parse — a placeholder claiming an import IRI would collide with a self-importing
        // root's trailing SetOntologyID). The first import loads the placeholder; the manager
        // resolves every further import IRI to that already-loaded ontology by document IRI
        // (loadImports requests allowExists=true). The primary document is a FileDocumentSource and
        // does not consult this mapper.
        Path placeholder = Files.createTempFile("protege-mcp-cli-blocked-imports-", ".ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
            IRI placeholderIri = IRI.create(placeholder.toUri());
            manager.getIRIMappers().add(importIri -> placeholderIri);
            OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setFollowRedirects(false);
            // A manifest-verified operand parses the EXACT bytes its sha256 covered; a plain
            // document operand streams from the file as before.
            org.semanticweb.owlapi.io.OWLOntologyDocumentSource source =
                    operand.verifiedBytes() != null
                            ? new org.semanticweb.owlapi.io.StreamDocumentSource(
                                    new java.io.ByteArrayInputStream(operand.verifiedBytes()),
                                    IRI.create(path.toUri()))
                            : new FileDocumentSource(path.toFile());
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(source, config);
            // Every declared import was deliberately satisfied WITHOUT fetching its axioms; report
            // them all, sorted, so the omitted closure stays visible in the output.
            ontology.getImportsDeclarations().stream()
                    .map(declaration -> declaration.getIRI().toString())
                    .sorted()
                    .forEach(unresolvedOut::add);
            return ontology;
        } finally {
            try {
                Files.deleteIfExists(placeholder);
            } catch (IOException bestEffortTempCleanup) {
                // Cleanup of the private placeholder must neither fail a successful diff nor mask
                // the real load error.
            }
        }
    }

    // ===================================================================== deferred commands

    /**
     * The roadmap commands that a headless CLI cannot honestly run yet: {@code release} and
     * {@code imports lock} need atomic headless mutation plus a bundled license-reviewed reasoner for
     * the gate stages, and {@code serve --transport stdio} needs a headless workspace adapter and the
     * plugin's tool catalog moved to core. Rather than ship a command that looks half-done, print a
     * clear not-available message and exit 2.
     */
    private static int deferred(String command, PrintStream err) {
        err.println(command + ": not yet available in the headless CLI (planned; PLAN §10.2/§10.3).");
        err.println("It requires a bundled license-reviewed baseline reasoner and a headless workspace "
                + "adapter; run project QC and releases from the in-Protégé MCP tools for now.");
        return 2;
    }

    // ===================================================================== shared helpers

    private static void recordNoNetwork(Options opts, Map<String, Object> result) {
        if (opts.flags.contains("--no-network")) {
            result.put("no_network", NO_NETWORK_NOTE);
        }
    }

    private static String usage() {
        return String.join("\n",
                "Usage:",
                "  protege-mcp-cli --version",
                "  protege-mcp-cli --help",
                "  protege-mcp-cli validate-policy --project FILE",
                "  protege-mcp-cli validate --project FILE [--format json|markdown] [--no-network] [--no-external]",
                "  protege-mcp-cli diff --left LEFT --right RIGHT [--check] [--no-network]",
                "      LEFT/RIGHT is an ontology document or a release manifest.json (resolved to its",
                "      primary ontology artifact after sha256 + containment verification).",
                "",
                "Exit codes: 0 passed; 1 validation/gate failed; 2 configuration/usage error; "
                        + "3 execution/infrastructure error.",
                "",
                "Planned, not yet available headlessly (require a bundled license-reviewed reasoner and a",
                "headless workspace adapter): release [--dry-run], imports lock, validate full-QC stages,",
                "serve --transport stdio. Run these from the in-Protégé MCP tools.");
    }

    /** Parse {@code --key value} options and boolean flags after the command word (args[0]). */
    private static Options parse(String[] args, Set<String> valueKeys, Set<String> boolFlags) {
        Options opts = new Options();
        int i = 1;
        while (i < args.length) {
            String arg = args[i];
            if (boolFlags.contains(arg)) {
                if (!opts.flags.add(arg)) {
                    throw new IllegalArgumentException("repeated flag " + arg);
                }
                i++;
            } else if (valueKeys.contains(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + arg);
                }
                if (opts.values.put(arg, args[i + 1]) != null) {
                    throw new IllegalArgumentException("repeated option " + arg);
                }
                i += 2;
            } else {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
        }
        return opts;
    }

    private static String required(Options opts, String key) {
        String value = opts.values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required option " + key);
        }
        return value;
    }

    private static String str(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String message(Throwable error) {
        return OwlParsingErrors.conciseMessage(error);
    }

    private static final class Options {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Set<String> flags = new LinkedHashSet<>();
    }
}
