package io.github.hakjuoh.protege_mcp.policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import org.yaml.snakeyaml.LoaderOptions;

import io.github.hakjuoh.protege_mcp.contracts.ContractRedactor;

/** Secure discovery, version dispatch, normalization, and validation for project policy. */
public final class ProjectPolicyLoader {

    public static final String DEFAULT_RELATIVE_PATH = ".protege-mcp/project.yaml";
    public static final long MAX_POLICY_BYTES = 1_048_576L;
    static final int MAX_ASSET_FILES = 10_000;
    static final int MAX_POLICY_NODES = 10_000;
    public static final int MAX_POLICY_ISSUES = 128;
    static final long MAX_EXPANDED_SCALAR_BYTES = MAX_POLICY_BYTES;
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final ObjectMapper YAML = yamlMapper();
    private static final Map<String, Object> SCHEMA_V1 = loadSchema(1);
    private static final Map<String, Object> SCHEMA_V2 = loadSchema(2);
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDialect(Dialects.getDraft202012());
    private static final Schema VALIDATOR_V1 = compiledSchema(SCHEMA_V1);
    private static final Schema VALIDATOR_V2 = compiledSchema(SCHEMA_V2);

    private ProjectPolicyLoader() {
    }

    /**
     * Discover an explicit policy first, otherwise walk from the ontology document's directory toward
     * the filesystem root. A missing implicit policy is a normal unloaded result; a bad explicit path is
     * a loaded-invalid result so callers cannot silently fall back to another file.
     */
    public static ProjectPolicy load(Path explicitPolicy, Path ontologyDocument,
            String activeOntologyIri, Collection<String> installedReasoners) {
        return load(explicitPolicy, ontologyDocument, activeOntologyIri, installedReasoners, false);
    }

    /**
     * Load a policy with an optional caller-enforced external-path denial. This is used by untrusted
     * CI/headless surfaces: a candidate policy must not grant itself access outside its checkout by
     * setting {@code filesystem.allow_external_paths: true}. The authored policy is left unchanged
     * (and therefore keeps the same digest), but the constrained validation fails closed before any
     * external asset is opened.
     */
    public static ProjectPolicy load(Path explicitPolicy, Path ontologyDocument,
            String activeOntologyIri, Collection<String> installedReasoners,
            boolean forbidExternalPaths) {
        return load(explicitPolicy, ontologyDocument, activeOntologyIri, installedReasoners,
                forbidExternalPaths, () -> { }, () -> { });
    }

    /** Test seam for a deterministic directory-swap immediately after the stable source read. */
    static ProjectPolicy load(Path explicitPolicy, Path ontologyDocument,
            String activeOntologyIri, Collection<String> installedReasoners,
            boolean forbidExternalPaths, ReadInterlock beforeParse) {
        return load(explicitPolicy, ontologyDocument, activeOntologyIri, installedReasoners,
                forbidExternalPaths, beforeParse, () -> { });
    }

    /** Test seam for a deterministic replacement after semantic asset validation. */
    static ProjectPolicy load(Path explicitPolicy, Path ontologyDocument,
            String activeOntologyIri, Collection<String> installedReasoners,
            boolean forbidExternalPaths, ReadInterlock beforeParse,
            ReadInterlock afterSemanticValidation) {
        Discovery discovery = discover(explicitPolicy, ontologyDocument);
        if (discovery.path == null) {
            if (discovery.issue == null) {
                return ProjectPolicy.notFound();
            }
            return invalidDiscovery(discovery);
        }
        return read(discovery, activeOntologyIri, installedReasoners, forbidExternalPaths,
                Objects.requireNonNull(beforeParse, "beforeParse"),
                Objects.requireNonNull(afterSemanticValidation, "afterSemanticValidation"));
    }

    public static ProjectPolicy load(Path explicitPolicy, Path ontologyDocument) {
        return load(explicitPolicy, ontologyDocument, null, null);
    }

    /**
     * Capture bytes together with the discovery-time source and anchor identities. The returned
     * object is the only accepted input to the hardened captured-policy parser, preventing callers
     * from re-anchoring stale or unrelated bytes after capture.
     */
    public static CapturedPolicy captureStablePolicy(PolicySourcePin pin) throws IOException {
        return new CapturedPolicy(pin, captureStablePolicyBytes(pin));
    }

    private static byte[] captureStablePolicyBytes(PolicySourcePin pin) throws IOException {
        Objects.requireNonNull(pin, "pin");
        if (!pin.isCurrent()) {
            throw new PolicyChangedDuringReadException(
                    "Project policy source or project anchor changed after secure discovery.");
        }
        Path normalized = pin.source;
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Project policy files must not be symbolic links");
        }
        Path path = normalized.toRealPath();
        if (!path.equals(normalized)) {
            throw new PolicyChangedDuringReadException(
                    "Project policy path changed after secure discovery.");
        }
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Project policy is not a regular file");
        if (before.size() > MAX_POLICY_BYTES) throw new IOException("Project policy is too large");
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        byte[] bytes = readStablePolicyBytes(path, options, () -> { });
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!path.toRealPath().equals(path)
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new PolicyChangedDuringReadException(
                    "Project policy identity changed while it was being read.");
        }
        if (bytes.length > MAX_POLICY_BYTES) throw new IOException("Project policy is too large");
        if (!pin.isCurrent()) {
            throw new PolicyChangedDuringReadException(
                    "Project policy source or project anchor changed while it was being read.");
        }
        return bytes;
    }

    /**
     * Compatibility entry point for callers that captured bytes themselves. It now recaptures the
     * canonical source and accepts the supplied bytes only when they exactly match the current,
     * identity-pinned policy; stale bytes are never interpreted against a replacement project.
     */
    public static ProjectPolicy loadCaptured(Path source, byte[] bytes,
            String activeOntologyIri, Collection<String> installedReasoners,
            boolean forbidExternalPaths) {
        if (source == null || bytes == null) {
            throw new IllegalArgumentException("source and bytes must not be null");
        }
        try {
            Path normalized = source.toAbsolutePath().normalize();
            CapturedPolicy captured = captureStablePolicy(pinCanonicalPolicy(normalized,
                    canonicalProjectAnchor(normalized)));
            if (!java.util.Arrays.equals(bytes, captured.bytes)) {
                return invalidCapturedPolicy(normalized,
                        "Captured policy bytes no longer match the pinned source.");
            }
            return loadCaptured(captured,
                    activeOntologyIri, installedReasoners, forbidExternalPaths);
        } catch (IOException unsafeSource) {
            return invalidCapturedPolicy(source.toAbsolutePath().normalize(),
                    "Captured policy source is not safely pinned: " + message(unsafeSource));
        }
    }

    /** Parse exactly the bytes and identities produced by {@link #captureStablePolicy}. */
    public static ProjectPolicy loadCaptured(CapturedPolicy captured,
            String activeOntologyIri, Collection<String> installedReasoners,
            boolean forbidExternalPaths) {
        if (captured == null) {
            throw new IllegalArgumentException("captured policy must not be null");
        }
        PolicySourcePin pin = captured.pin;
        return parse(new Discovery("explicit", pin.source, null, pin.trustedProjectAnchor, pin),
                pin.source, captured.bytes, activeOntologyIri, installedReasoners,
                forbidExternalPaths, () -> { });
    }

    /** Pin a canonical policy source and its exact project-anchor directory identity. */
    public static PolicySourcePin pinCanonicalPolicy(Path source, Path trustedProjectAnchor)
            throws IOException {
        if (source == null || trustedProjectAnchor == null) {
            throw new IllegalArgumentException("source and trustedProjectAnchor must not be null");
        }
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedRoot = trustedProjectAnchor.toAbsolutePath().normalize();
        Path realSource = normalizedSource.toRealPath();
        Path realRoot = normalizedRoot.toRealPath();
        if (!realSource.equals(normalizedSource) || !realRoot.equals(normalizedRoot)
                || !realSource.startsWith(realRoot)
                || !canonicalProjectAnchor(realSource).equals(realRoot)) {
            throw new IOException("policy source or project anchor is not canonical");
        }
        return new PolicySourcePin(realSource, realRoot,
                PathIdentity.source(realSource), PathIdentity.directory(realRoot));
    }

    /**
     * Return the lexical project anchor for an already-canonical policy source. No filesystem
     * lookup is performed, so callers can carry the pinned anchor across a later stable capture.
     */
    public static Path canonicalProjectAnchor(Path canonicalSource) {
        if (canonicalSource == null) {
            throw new IllegalArgumentException("canonicalSource must not be null");
        }
        Path source = canonicalSource.toAbsolutePath().normalize();
        Path parent = source.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("canonicalSource must have a parent directory");
        }
        return projectRootAnchor(parent).toAbsolutePath().normalize();
    }

    private static ProjectPolicy read(Discovery discovery, String activeOntologyIri,
            Collection<String> installedReasoners, boolean forbidExternalPaths,
            ReadInterlock beforeParse, ReadInterlock afterSemanticValidation) {
        List<PolicyIssue> issues = new PolicyIssues();
        Path path;
        BasicFileAttributes capturedAttributes;
        try {
            if (discovery.sourcePin == null || !discovery.sourcePin.isCurrent()) {
                issues.add(error("policy_changed_during_read", "policy_path",
                        "Project policy source or project anchor changed after discovery."));
                return result(discovery.kind, discovery.path.toAbsolutePath().normalize(), null, null,
                        Collections.emptyMap(), Collections.emptyMap(), issues);
            }
            if (Files.isSymbolicLink(discovery.path)) {
                issues.add(error("policy_symlink_forbidden", "policy_path",
                        "Project policy files must not be symbolic links."));
                return result(discovery.kind, discovery.path.toAbsolutePath().normalize(), null, null,
                        Collections.emptyMap(), Collections.emptyMap(), issues);
            }
            path = discovery.path.toRealPath();
            if (discovery.trustedRoot != null && !path.startsWith(discovery.trustedRoot)) {
                issues.add(error("policy_symlink_escape", "policy_path",
                        "Discovered project policy resolves outside the ontology directory being searched."));
                return result(discovery.kind, discovery.path.toAbsolutePath().normalize(), null, null,
                        Collections.emptyMap(), Collections.emptyMap(), issues);
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path)) {
                issues.add(error("policy_not_file", "policy_path", "Policy is not a regular file: " + path));
                return result(discovery.kind, path, null, null, Collections.emptyMap(),
                        Collections.emptyMap(), issues);
            }
            capturedAttributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            long size = capturedAttributes.size();
            if (size > MAX_POLICY_BYTES) {
                issues.add(error("policy_too_large", "policy_path", "Policy is " + size
                        + " bytes; the maximum is " + MAX_POLICY_BYTES + "."));
                return result(discovery.kind, path, null, null, Collections.emptyMap(),
                        Collections.emptyMap(), issues);
            }
        } catch (IOException e) {
            issues.add(error("policy_unreadable", "policy_path", "Could not resolve/read policy: "
                    + message(e)));
            return result(discovery.kind, discovery.path.toAbsolutePath().normalize(), null, null,
                    Collections.emptyMap(), Collections.emptyMap(), issues);
        }

        byte[] bytes;
        try {
            Set<OpenOption> readOptions = Set.of(StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
            bytes = readStablePolicyBytes(path, readOptions, () -> { });
            if (bytes.length > MAX_POLICY_BYTES) {
                issues.add(error("policy_too_large", "policy_path", "Policy exceeds the maximum of "
                        + MAX_POLICY_BYTES + " bytes."));
                return result(discovery.kind, path, null, null, Collections.emptyMap(),
                        Collections.emptyMap(), issues);
            }
            Path resolvedAfterRead = path.toRealPath();
            BasicFileAttributes afterRead = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!resolvedAfterRead.equals(path)
                    || discovery.trustedRoot != null
                            && !resolvedAfterRead.startsWith(discovery.trustedRoot)
                    || !Objects.equals(capturedAttributes.fileKey(), afterRead.fileKey())
                    || capturedAttributes.size() != afterRead.size()
                    || !capturedAttributes.lastModifiedTime().equals(afterRead.lastModifiedTime())) {
                issues.add(error("policy_changed_during_read", "policy_path",
                        "Project policy identity changed while it was being read."));
                return result(discovery.kind, path, null, null, Collections.emptyMap(),
                        Collections.emptyMap(), issues);
            }
        } catch (PolicyChangedDuringReadException e) {
            issues.add(error("policy_changed_during_read", "policy_path", e.getMessage()));
            return result(discovery.kind, path, null, null, Collections.emptyMap(),
                    Collections.emptyMap(), issues);
        } catch (IOException | RuntimeException e) {
            issues.add(error("policy_unreadable", "policy_path", "Could not read policy: "
                    + message(e)));
            return result(discovery.kind, path, null, null, Collections.emptyMap(),
                    Collections.emptyMap(), issues);
        }

        try {
            beforeParse.run();
        } catch (IOException | RuntimeException changed) {
            issues.add(error("policy_changed_during_read", "policy_path",
                    "Project policy source changed before validation."));
            return result(discovery.kind, path, discovery.trustedRoot, null,
                    Collections.emptyMap(), Collections.emptyMap(), issues);
        }
        return parse(discovery, path, bytes, activeOntologyIri, installedReasoners,
                forbidExternalPaths, afterSemanticValidation);
    }

    private static ProjectPolicy parse(Discovery discovery, Path path, byte[] bytes,
            String activeOntologyIri, Collection<String> installedReasoners,
            boolean forbidExternalPaths, ReadInterlock afterSemanticValidation) {
        List<PolicyIssue> issues = new PolicyIssues();
        if (bytes.length > MAX_POLICY_BYTES) {
            issues.add(error("policy_too_large", "policy_path", "Policy is " + bytes.length
                    + " bytes; the maximum is " + MAX_POLICY_BYTES + "."));
            return result(discovery.kind, path, null, null, Collections.emptyMap(),
                    Collections.emptyMap(), issues);
        }

        Map<String, Object> parsed;
        try {
            rejectYamlAliases(bytes);
            parsed = YAML.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() { });
            if (parsed == null) {
                throw new IOException("document is empty");
            }
        } catch (IOException | RuntimeException e) {
            issues.add(error("yaml_invalid", null, "Policy YAML could not be parsed safely."));
            return result(discovery.kind, path, discovery.trustedRoot, null,
                    Collections.emptyMap(), Collections.emptyMap(), issues);
        }

        Integer version = policyVersion(parsed);
        if (version == null) {
            issues.add(error("schema_invalid", "version",
                    "Policy version must be the integer 1 or 2."));
            return result(discovery.kind, path, discovery.trustedRoot, null,
                    Collections.emptyMap(),
                    Collections.emptyMap(), issues);
        }
        if (version != 1 && version != 2) {
            issues.add(error("unsupported_policy_version", "version",
                    "Unsupported project policy version " + version + "; supported versions are 1 and 2."));
            return result(discovery.kind, path, discovery.trustedRoot, null,
                    Collections.emptyMap(),
                    Collections.emptyMap(), issues);
        }
        if (exceedsExpandedScalarBudget(parsed, MAX_EXPANDED_SCALAR_BYTES)) {
            issues.add(error("policy_scalar_budget_exceeded", null,
                    "Expanded policy scalar content exceeds " + MAX_EXPANDED_SCALAR_BYTES
                            + " UTF-8 bytes."));
            return result(discovery.kind, path, discovery.trustedRoot, null,
                    Collections.emptyMap(), Collections.emptyMap(), issues);
        }
        if (version == 2 && exceedsStructureBudget(parsed, MAX_POLICY_NODES)) {
            issues.add(error("policy_structure_too_large", null,
                    "Policy v2 structure exceeds the maximum of " + MAX_POLICY_NODES
                            + " parsed nodes."));
            return result(discovery.kind, path, discovery.trustedRoot, null,
                    Collections.emptyMap(), Collections.emptyMap(), issues);
        }

        if (!schemaValid(version == 1 ? VALIDATOR_V1 : VALIDATOR_V2, parsed)) {
            issues.add(error("schema_invalid", null,
                    "Policy does not conform to project-policy v" + version + " schema."));
            return result(discovery.kind, path, discovery.trustedRoot, null,
                    Collections.emptyMap(),
                    Collections.emptyMap(), issues);
        }

        boolean authoredRoCrateFormat = object(
                object(parsed, "interoperability"), "metadata").containsKey("format");
        Map<String, Object> effective = version == 1 ? defaults(parsed) : defaultsV2(parsed);
        Path projectRoot = resolveProjectRoot(discovery.trustedRoot,
                string(effective, "project_root"), issues);
        if (!sourceStillPinned(discovery.sourcePin)) {
            issues.add(error("policy_changed_during_validation", "policy_path",
                    "Project policy source or project anchor changed before validation."));
            return result(discovery.kind, path, projectRoot, digest(effective), effective,
                    Collections.emptyMap(), issues);
        }
        Map<String, List<Path>> assets = new LinkedHashMap<>();
        semanticValidation(effective, path, projectRoot, activeOntologyIri, installedReasoners,
                !authoredRoCrateFormat, forbidExternalPaths, assets, issues);
        try {
            afterSemanticValidation.run();
        } catch (IOException | RuntimeException changed) {
            issues.add(error("policy_changed_during_validation", "policy_path",
                    "Project policy source or project anchor changed during validation."));
            assets.clear();
            return result(discovery.kind, path, projectRoot, digest(effective), effective,
                    assets, issues);
        }
        if (!sourceStillPinned(discovery.sourcePin)) {
            issues.add(error("policy_changed_during_validation", "policy_path",
                    "Project policy source or project anchor changed during validation."));
            assets.clear();
        }
        return result(discovery.kind, path, projectRoot, digest(effective), effective, assets, issues);
    }

    private static Discovery discover(Path explicit, Path ontologyDocument) {
        if (explicit != null) {
            try {
                Path normalized = explicit.toAbsolutePath().normalize();
                if (Files.isSymbolicLink(normalized)) {
                    return new Discovery("explicit", null,
                            error("policy_symlink_forbidden", "policy_path",
                                    "Project policy files must not be symbolic links."));
                }
                if (!Files.exists(normalized)) {
                    return new Discovery("explicit", null, error("policy_not_found", "policy_path",
                            "Explicit policy does not exist: " + normalized));
                }
                Path anchor = canonicalProjectAnchor(normalized).toRealPath();
                Path real = normalized.toRealPath();
                if (!real.startsWith(anchor) || !canonicalProjectAnchor(real).equals(anchor)) {
                    return new Discovery("explicit", null,
                            error("policy_symlink_escape", "policy_path",
                                    "Explicit project policy resolves outside its project anchor."));
                }
                PolicySourcePin pin = pinCanonicalPolicy(real, anchor);
                return new Discovery("explicit", real, null, anchor, pin);
            } catch (InvalidPathException e) {
                return new Discovery("explicit", null, error("policy_path_invalid", "policy_path",
                        "Explicit policy path is invalid: " + message(e)));
            } catch (IOException e) {
                return new Discovery("explicit", null,
                        error("policy_path_unresolvable", "policy_path",
                                "Could not resolve the explicit policy anchor."));
            }
        }
        if (ontologyDocument == null) {
            return new Discovery("none", null, null);
        }
        Path start = Files.isDirectory(ontologyDocument) ? ontologyDocument
                : ontologyDocument.toAbsolutePath().normalize().getParent();
        for (Path current = start; current != null; current = current.getParent()) {
            Path candidate = current.resolve(DEFAULT_RELATIVE_PATH);
            if (Files.isSymbolicLink(candidate)) {
                return new Discovery("discovered", null,
                        error("policy_symlink_forbidden", "policy_path",
                                "Discovered project policy must not be a symbolic link."));
            }
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Path root = current.toRealPath();
                    Path source = candidate.toRealPath();
                    if (!source.startsWith(root)
                            || !canonicalProjectAnchor(source).equals(root)) {
                        return new Discovery("discovered", null,
                                error("policy_symlink_escape", "policy_path",
                                        "Discovered project policy resolves outside the ontology directory being searched."));
                    }
                    PolicySourcePin pin = pinCanonicalPolicy(source, root);
                    return new Discovery("discovered", source, null, root, pin);
                } catch (IOException e) {
                    return new Discovery("discovered", null,
                            error("policy_path_unresolvable", "policy_path",
                                    "Could not resolve the policy discovery anchor."));
                }
            }
        }
        return new Discovery("none", null, null);
    }

    private static ProjectPolicy invalidDiscovery(Discovery discovery) {
        return new ProjectPolicy(true, discovery.kind, null, null, null,
                Collections.emptyMap(), Collections.emptyMap(), List.of(discovery.issue), null);
    }

    private static ProjectPolicy invalidCapturedPolicy(Path path, String message) {
        List<PolicyIssue> issues = new PolicyIssues();
        issues.add(error("policy_changed_during_read", "policy_path", message));
        return result("explicit", path, null, null, Collections.emptyMap(),
                Collections.emptyMap(), issues);
    }

    private static ProjectPolicy result(String discovery, Path path, Path projectRoot, String digest,
            Map<String, Object> effective, Map<String, List<Path>> assets, List<PolicyIssue> issues) {
        boolean truncated = issues instanceof PolicyIssues bounded && bounded.truncated;
        boolean hasError = issues instanceof PolicyIssues bounded ? bounded.hasError
                : issues.stream().anyMatch(issue -> "error".equals(issue.severity()));
        boolean valid = !truncated && !hasError;
        List<PolicyIssue> boundedIssues = boundedIssues(issues);
        return new ProjectPolicy(true, discovery, path, projectRoot, digest, effective, assets,
                boundedIssues,
                valid && Integer.valueOf(1).equals(policyVersion(effective))
                        ? PolicyMigrationRecommendation.v1ToV2() : null);
    }

    /**
     * A policy stored in a directory named {@code .protege-mcp} governs the project that CONTAINS
     * that directory (the canonical {@code <project>/.protege-mcp/project.yaml} layout), so its
     * anchor is the parent; a policy anywhere else anchors at its own directory. Relative asset
     * paths and {@code project_root} both resolve against this anchor — otherwise a discovered
     * policy could only reach files inside {@code .protege-mcp/} and the documented layout, where
     * sources sit beside that directory, could never validate.
     */
    private static Path projectRootAnchor(Path policyDir) {
        Path name = policyDir.getFileName();
        Path parent = policyDir.getParent();
        return name != null && parent != null && ".protege-mcp".equals(name.toString())
                ? parent : policyDir;
    }

    private static Path resolveProjectRoot(Path anchor, String configured, List<PolicyIssue> issues) {
        if (anchor == null) {
            issues.add(error("project_root_invalid", "project_root",
                    "Project root anchor is unavailable."));
            return null;
        }
        Path candidate = anchor.resolve(configured == null ? "." : configured).normalize();
        if (!candidate.startsWith(anchor.normalize())) {
            issues.add(error("project_root_escape", "project_root",
                    "project_root must name the project base directory or a descendant."));
            return null;
        }
        try {
            Path realAnchor = anchor.toRealPath();
            Path real = candidate.toRealPath();
            if (!realAnchor.equals(anchor) || !Files.isDirectory(real) || !real.startsWith(anchor)) {
                issues.add(error("project_root_invalid", "project_root",
                        "project_root must resolve to a directory at or below " + anchor
                                + ": " + candidate));
                return null;
            }
            return real;
        } catch (IOException e) {
            issues.add(error("project_root_missing", "project_root",
                    "project_root does not resolve to an existing directory: " + candidate));
            return null;
        }
    }

    private static boolean sourceStillPinned(PolicySourcePin pin) {
        return pin != null && pin.isCurrent();
    }

    private static void semanticValidation(Map<String, Object> policy, Path policyPath, Path projectRoot,
            String activeOntologyIri, Collection<String> installedReasoners,
            boolean inferRoCrateVersion, boolean forbidExternalPaths,
            Map<String, List<Path>> assets,
            List<PolicyIssue> issues) {
        if (projectRoot == null) {
            return;
        }
        boolean authoredAllowExternal = bool(object(policy, "filesystem"),
                "allow_external_paths", false);
        if (forbidExternalPaths && authoredAllowExternal) {
            issues.add(error("external_paths_forbidden", "filesystem.allow_external_paths",
                    "The caller forbids external paths; filesystem.allow_external_paths must be false."));
        }
        boolean allowExternal = authoredAllowExternal && !forbidExternalPaths;
        AssetScanBudget assetBudget = new AssetScanBudget();

        String rootIri = string(policy, "root_ontology");
        if (activeOntologyIri != null && !activeOntologyIri.equals(rootIri)) {
            issues.add(error("root_ontology_mismatch", "root_ontology", "Policy root_ontology " + rootIri
                    + " does not match the active ontology IRI " + activeOntologyIri + "."));
        }

        validateRegex(policy, issues);
        validateTermReferences(policy, issues);
        Path interopManifest = validateInteroperabilityAssets(policy, projectRoot, assets, issues);
        validateModules(policy, projectRoot, allowExternal, assets, issues);
        validateReasoner(policy, installedReasoners, issues);
        validateImports(policy, projectRoot, allowExternal, assets, issues);
        validateValidationAssets(policy, projectRoot, allowExternal, assets, issues, assetBudget);
        validateReleasePath(policy, projectRoot, allowExternal, assets, issues);
        if (Integer.valueOf(2).equals(policyVersion(policy))) {
            validateV2(policy, policyPath, projectRoot, assets, issues);
        }
        if (interopManifest != null) {
            RoCrateProjectManifest.inspect(interopManifest, policy, issues,
                    inferRoCrateVersion);
        }
    }

    private static Path validateInteroperabilityAssets(Map<String, Object> policy, Path projectRoot,
            Map<String, List<Path>> assets, List<PolicyIssue> issues) {
        Map<String, Object> interoperability = object(policy, "interoperability");
        Path rootArtifact = resolveAsset(string(interoperability, "root_artifact"), projectRoot,
                false, true, "interoperability.root_artifact", issues);
        if (rootArtifact != null && requireRegularFile(rootArtifact,
                "interoperability.root_artifact", issues)) {
            assets.put("root_artifact", List.of(rootArtifact));
        }

        Map<String, Object> metadata = object(interoperability, "metadata");
        Path manifest = resolveAsset(string(metadata, "path"), projectRoot,
                false, true, "interoperability.metadata.path", issues);
        if (manifest != null && requireRegularFile(manifest,
                "interoperability.metadata.path", issues)) {
            assets.put("interoperability_manifest", List.of(manifest));
            return manifest;
        }
        return null;
    }

    private static void validateRegex(Map<String, Object> policy, List<PolicyIssue> issues) {
        Map<String, Object> iri = object(policy, "iri_policy");
        String regex = string(iri, "pattern");
        if (regex != null) {
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                issues.add(error("regex_invalid", "iri_policy.pattern", "Invalid Java regex: "
                        + e.getDescription()));
            }
        }
    }

    private static void validateTermReferences(Map<String, Object> policy, List<PolicyIssue> issues) {
        Map<String, Object> prefixes = object(policy, "prefixes");
        Map<String, Object> annotations = object(policy, "annotations");
        List<String> refs = new ArrayList<>();
        refs.addAll(strings(annotations.get("required")));
        Map<String, Object> labels = object(annotations, "labels");
        refs.addAll(strings(labels.get("properties")));
        Map<String, Object> definitions = object(annotations, "definitions");
        refs.addAll(strings(definitions.get("properties")));
        Map<String, Object> lifecycle = object(policy, "lifecycle");
        String statusProperty = string(lifecycle, "status_property");
        if (statusProperty != null) refs.add(statusProperty);
        refs.addAll(strings(lifecycle.get("replaced_by_properties")));
        validateTermReferenceList(refs, prefixes, "annotations", issues);

        Map<String, Object> search = object(policy, "entity_search");
        List<String> preferredProperties = strings(search.get("preferred_properties"));
        List<String> synonymProperties = strings(search.get("synonym_properties"));
        List<String> searchRefs = new ArrayList<>(preferredProperties);
        searchRefs.addAll(synonymProperties);
        validateTermReferenceList(searchRefs, prefixes, "entity_search", issues);
        List<String> effectivePreferred = search.containsKey("preferred_properties")
                ? preferredProperties : EntitySearchPolicy.DEFAULT_PREFERRED_PROPERTIES;
        List<String> effectiveSynonyms = search.containsKey("synonym_properties")
                ? synonymProperties : EntitySearchPolicy.DEFAULT_SYNONYM_PROPERTIES;
        Set<String> preferredIris = effectivePreferred.stream()
                .map(ref -> expandTermReference(ref, prefixes)).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> synonymIris = effectiveSynonyms.stream()
                .map(ref -> expandTermReference(ref, prefixes)).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        preferredIris.retainAll(synonymIris);
        if (!preferredIris.isEmpty()) {
            issues.add(error("search_property_overlap", "entity_search",
                    "A lexical property cannot be both preferred and synonym: "
                            + String.join(", ", preferredIris.stream().sorted().toList())));
        }
        validateLanguageOrder(search, "preferred_languages", issues);
        validateLanguageOrder(search, "fallback_languages", issues);
        Set<String> preferredLanguages = normalizedLanguages(search.get("preferred_languages"));
        Set<String> fallbackLanguages = normalizedLanguages(search.get("fallback_languages"));
        preferredLanguages.retainAll(fallbackLanguages);
        if (!preferredLanguages.isEmpty()) {
            issues.add(error("search_language_overlap", "entity_search",
                    "A language cannot be both preferred and fallback: "
                            + String.join(", ", preferredLanguages.stream().sorted().toList())));
        }
        if ((!strings(search.get("preferred_languages")).isEmpty()
                || !strings(search.get("fallback_languages")).isEmpty())
                && search.containsKey("preferred_properties")
                && search.containsKey("synonym_properties")
                && preferredProperties.isEmpty() && synonymProperties.isEmpty()) {
            issues.add(error("search_properties_required", "entity_search",
                    "Language preferences require at least one preferred or synonym property."));
        }
        if ((!strings(labels.get("required_languages")).isEmpty()
                || bool(labels, "one_preferred_per_language", false))
                && strings(labels.get("properties")).isEmpty()) {
            issues.add(error("label_properties_required", "annotations.labels.properties",
                    "Label language/cardinality rules require at least one label property."));
        }
        if ((bool(definitions, "required", false)
                || !strings(definitions.get("required_languages")).isEmpty())
                && strings(definitions.get("properties")).isEmpty()) {
            issues.add(error("definition_properties_required", "annotations.definitions.properties",
                    "Definition rules require at least one definition property."));
        }
        if (statusProperty != null) {
            Set<String> allowed = strings(lifecycle.get("allowed_values")).stream()
                    .map(v -> v.trim().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            for (String deprecated : strings(lifecycle.get("deprecated_values"))) {
                if (!allowed.contains(deprecated.trim().toLowerCase(Locale.ROOT))) {
                    issues.add(error("lifecycle_deprecated_not_allowed", "lifecycle.deprecated_values",
                            "Deprecated lifecycle value must also appear in allowed_values: " + deprecated));
                }
            }
            if (bool(lifecycle, "require_replacement", true)
                    && strings(lifecycle.get("replaced_by_properties")).isEmpty()) {
                issues.add(error("lifecycle_replacement_properties_required",
                        "lifecycle.replaced_by_properties",
                        "require_replacement=true requires at least one replacement property."));
            }
        }
    }

    private static void validateTermReferenceList(List<String> refs, Map<String, Object> prefixes,
            String path, List<PolicyIssue> issues) {
        for (String ref : refs) {
            int colon = ref.indexOf(':');
            if (colon <= 0 || colon == ref.length() - 1) {
                issues.add(error("term_reference_invalid", path,
                        "Annotation property reference must be an absolute IRI or declared CURIE: " + ref));
                continue;
            }
            String prefix = ref.substring(0, colon);
            boolean explicitAbsolute = Set.of("http", "https", "urn", "file")
                    .contains(prefix.toLowerCase(Locale.ROOT));
            if (!explicitAbsolute && !prefixes.containsKey(prefix)) {
                issues.add(error("prefix_unknown", path, "CURIE uses undeclared prefix '"
                        + prefix + "': " + ref));
            }
        }
    }

    private static String expandTermReference(String ref, Map<String, Object> prefixes) {
        if (ref == null) return null;
        int colon = ref.indexOf(':');
        if (colon <= 0 || colon == ref.length() - 1) return null;
        String prefix = ref.substring(0, colon);
        if (Set.of("http", "https", "urn", "file").contains(prefix.toLowerCase(Locale.ROOT))) {
            return ref;
        }
        Object base = prefixes.get(prefix);
        return base instanceof String ? base + ref.substring(colon + 1) : null;
    }

    private static void validateLanguageOrder(Map<String, Object> search, String key,
            List<PolicyIssue> issues) {
        List<String> languages = strings(search.get(key));
        if (languages.size() > EntitySearchPolicy.MAX_LANGUAGE_PRIORITIES) {
            issues.add(error("search_language_limit", "entity_search." + key,
                    "At most " + EntitySearchPolicy.MAX_LANGUAGE_PRIORITIES
                            + " language priorities are allowed."));
        }
        if (normalizedLanguages(languages).size() != languages.size()) {
            issues.add(error("search_language_duplicate", "entity_search." + key,
                    "Language priority lists are case-insensitively unique."));
        }
    }

    private static Set<String> normalizedLanguages(Object value) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String language : strings(value)) {
            normalized.add(language.toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private static void validateModules(Map<String, Object> policy, Path projectRoot,
            boolean allowExternal, Map<String, List<Path>> assets, List<PolicyIssue> issues) {
        Set<String> iris = new LinkedHashSet<>();
        Set<Path> paths = new LinkedHashSet<>();
        int index = 0;
        for (Map<String, Object> module : objects(policy.get("modules"))) {
            String iri = string(module, "ontology_iri");
            if (!iris.add(iri)) {
                issues.add(error("module_ontology_duplicate", "modules[" + index + "].ontology_iri",
                        "More than one module declares " + iri + "."));
            }
            Path resolved = resolveAsset(string(module, "path"), projectRoot, allowExternal,
                    true, "modules[" + index + "].path", issues);
            if (resolved != null && requireRegularFile(resolved,
                    "modules[" + index + "].path", issues)) {
                if (!paths.add(resolved)) {
                    issues.add(error("module_path_duplicate", "modules[" + index + "].path",
                            "More than one module resolves to " + resolved + "."));
                }
                assets.computeIfAbsent("modules", k -> new ArrayList<>()).add(resolved);
                try {
                    ModuleDocumentInspector.Inspection inspection =
                            ModuleDocumentInspector.inspect(resolved);
                    if (!iri.equals(inspection.ontologyIri())) {
                        issues.add(error("module_ontology_iri_mismatch",
                                "modules[" + index + "].ontology_iri",
                                "Module file " + resolved + " declares ontology IRI "
                                        + (inspection.ontologyIri() == null ? "(anonymous)"
                                                : inspection.ontologyIri())
                                        + " instead of policy value " + iri + "."));
                    }
                } catch (ModuleDocumentInspector.DocumentTooLargeException tooLarge) {
                    issues.add(error("module_document_too_large", "modules[" + index + "].path",
                            "Module file exceeds the bounded policy-inspection size limit of "
                                    + ModuleDocumentInspector.MAX_DOCUMENT_BYTES + " bytes."));
                } catch (IOException e) {
                    issues.add(error("module_document_invalid", "modules[" + index + "].path",
                            "Could not inspect module file " + resolved + ": " + e.getMessage()));
                }
            }
            index++;
        }
    }

    private static void validateReasoner(Map<String, Object> policy, Collection<String> installed,
            List<PolicyIssue> issues) {
        Map<String, Object> reasoning = object(policy, "reasoning");
        boolean requiredByStage = strings(object(policy, "validation").get("required_stages"))
                .contains("reasoner");
        if (!bool(reasoning, "required", false) && !requiredByStage
                && string(reasoning, "reasoner") == null) {
            return;
        }
        String selected = string(reasoning, "reasoner");
        if (selected == null) {
            issues.add(error("reasoner_unspecified", "reasoning.reasoner",
                    "A required reasoner stage must name the reasoner used for reproducible QC."));
            return;
        }
        if (installed == null) {
            // A null registry means a headless caller with no installed-reasoner inventory; the
            // adapter that executes QC checks availability. The pure-syntax reasoner_unspecified
            // check above must still run so headless and plugin validation agree on validity.
            return;
        }
        ReasonerNames.Resolution resolution = ReasonerNames.resolveNames(selected, installed);
        if (resolution.ambiguous()) {
            issues.add(error("reasoner_ambiguous", "reasoning.reasoner", "Reasoner reference '"
                    + selected + "' matches more than one installed reasoner ("
                    + String.join(", ", resolution.candidateNames())
                    + "). Use a full display name that identifies one installed reasoner; if plugins"
                    + " expose the same display name, disable all but one."));
        } else if (!resolution.unique()) {
            issues.add(error("reasoner_unavailable", "reasoning.reasoner", "Required reasoner '"
                    + selected + "' is not installed. Installed reasoners: "
                    + (installed.isEmpty() ? "none" : String.join(", ", installed)) + "."));
        }
    }

    private static void validateImports(Map<String, Object> policy, Path projectRoot,
            boolean allowExternal, Map<String, List<Path>> assets, List<PolicyIssue> issues) {
        Map<String, Object> imports = object(policy, "imports");
        String lock = string(imports, "lockfile");
        if (lock != null) {
            Path resolved = resolveAsset(lock, projectRoot, allowExternal, true,
                    "imports.lockfile", issues);
            if (resolved != null && requireRegularFile(resolved, "imports.lockfile", issues)) {
                assets.put("import_lock", List.of(resolved));
            }
        }
    }

    private static void validateValidationAssets(Map<String, Object> policy,
            Path projectRoot, boolean allowExternal, Map<String, List<Path>> assets,
            List<PolicyIssue> issues, AssetScanBudget assetBudget) {
        Map<String, Object> validation = object(policy, "validation");
        expandPaths(object(validation, "invariants"), "invariants", ".rq", projectRoot,
                allowExternal, assets, issues, assetBudget);
        expandPaths(object(validation, "shacl"), "shacl", null, projectRoot,
                allowExternal, assets, issues, assetBudget);
        Map<String, Object> cqs = object(validation, "competency_questions");
        String cqPath = string(cqs, "path");
        String convention = string(cqs, "convention");
        if (cqPath != null) {
            Path resolved = resolveAsset(cqPath, projectRoot, allowExternal, true,
                    "validation.competency_questions.path", issues);
            if (resolved != null) {
                boolean expectedType = "robot-sparql-dir".equals(convention)
                        ? requireDirectory(resolved, "validation.competency_questions.path", issues)
                        : requireRegularFile(resolved, "validation.competency_questions.path", issues);
                if (expectedType) {
                    assets.put("cqs", List.of(resolved));
                }
            }
        } else if (strings(validation.get("required_stages")).contains("cqs")
                && ("robot-sparql-dir".equals(convention) || "sidecar-manifest".equals(convention))) {
            // Validator/executor parity: the QC executor requires exactly one resolved CQ path for
            // the file-based conventions; only ontology-annotations reads CQs from the ontology
            // itself. Without this check the policy validates but project QC always errors.
            issues.add(error("cq_path_required", "validation.competency_questions.path",
                    "Convention '" + convention + "' reads competency questions from a file path; set "
                            + "validation.competency_questions.path or use the ontology-annotations "
                            + "convention."));
        }
    }

    private static void expandPaths(Map<String, Object> block, String key, String extension,
            Path projectRoot, boolean allowExternal,
            Map<String, List<Path>> assets, List<PolicyIssue> issues,
            AssetScanBudget assetBudget) {
        List<Path> resolved = new ArrayList<>();
        int index = 0;
        for (String configured : strings(block.get("paths"))) {
            String field = "validation." + key + ".paths[" + index + "]";
            if (hasGlob(configured)) {
                resolved.addAll(expandGlob(configured, projectRoot, allowExternal, field, issues,
                        assetBudget));
            } else {
                Path path = resolveAsset(configured, projectRoot, allowExternal, true, field, issues);
                if (path != null && requireRegularFile(path, field, issues)) {
                    resolved.add(path);
                }
            }
            index++;
        }
        resolved.sort(Comparator.comparing(Path::toString));
        resolved = new ArrayList<>(new LinkedHashSet<>(resolved));
        if (extension != null) {
            for (Path path : resolved) {
                if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
                    issues.add(error("asset_extension_invalid", "validation." + key,
                            key + " asset must end in " + extension + ": " + path));
                }
            }
        }
        if (!block.isEmpty() && resolved.isEmpty()) {
            issues.add(error("asset_pattern_empty", "validation." + key + ".paths",
                    "No files matched the configured " + key + " paths."));
        }
        if (!resolved.isEmpty()) {
            assets.put(key, resolved);
        }
    }

    /** Glob metacharacters that mark where a pattern stops being a literal directory path. */
    private static boolean containsGlobMeta(String value) {
        return value.matches(".*[*?\\[\\]{}].*");
    }

    private static List<Path> expandGlob(String configured, Path projectRoot,
            boolean allowExternal, String field, List<PolicyIssue> issues,
            AssetScanBudget assetBudget) {
        if (assetBudget.exhausted) {
            assetBudget.report(field, issues);
            return Collections.emptyList();
        }
        final Path lexical;
        final List<String> segments = new ArrayList<>();
        try {
            if (isPortableAbsolute(configured)) {
                issues.add(error("glob_absolute_unsupported", field,
                        "Glob paths must be project-relative: " + configured));
                return Collections.emptyList();
            }
            lexical = projectRoot.resolve(configured).normalize();
            for (Path name : Path.of(configured)) {
                segments.add(name.toString());
            }
        } catch (InvalidPathException e) {
            issues.add(error("glob_invalid", field, "Invalid glob path '" + configured + "': " + message(e)));
            return Collections.emptyList();
        }
        if (!allowExternal && !lexical.startsWith(projectRoot)) {
            issues.add(error("path_outside_project", field, "Path escapes project_root: " + configured));
            return Collections.emptyList();
        }
        // Walk only the pattern's wildcard-free base directory: walking all of projectRoot made the
        // visited cap fire on any realistic repo (.git alone can exceed it) no matter how narrow the
        // glob was. A configured segment that does not exist yet stays in the pattern, so a glob
        // into a missing directory still reports asset_pattern_empty rather than a scan failure.
        int firstMeta = 0;
        while (firstMeta < segments.size() && !containsGlobMeta(segments.get(firstMeta))) {
            firstMeta++;
        }
        Path base = projectRoot;
        int consumed = 0;
        while (consumed < firstMeta) {
            Path next = base.resolve(segments.get(consumed)).normalize();
            if (!Files.isDirectory(next)) {
                break;
            }
            base = next;
            consumed++;
        }
        // Match RELATIVE to the walk base using only the user-authored pattern remainder, so glob
        // metacharacters in the project's own directory path (e.g. "onto [v2]") never poison the
        // compiled pattern. Authored separators are '/', which the glob syntax also uses.
        String remainder = String.join("/", segments.subList(consumed, segments.size()));
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + remainder);
        } catch (RuntimeException e) {
            issues.add(error("glob_invalid", field, "Invalid glob '" + configured + "': " + message(e)));
            return Collections.emptyList();
        }
        List<Path> matches = new ArrayList<>();
        final Path walkBase;
        try {
            walkBase = base.toRealPath();
            if (!allowExternal && !walkBase.startsWith(projectRoot)) {
                issues.add(error("symlink_escape", field,
                        "Glob base resolves outside project_root: " + base));
                return Collections.emptyList();
            }
        } catch (IOException e) {
            issues.add(error("glob_read_failed", field,
                    "Could not resolve glob base: " + message(e)));
            return Collections.emptyList();
        }
        try {
            Files.walkFileTree(walkBase, Collections.<FileVisitOption>emptySet(),
                    64, new FileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            return !assetBudget.visit() ? FileVisitResult.TERMINATE
                                    : FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            if (!assetBudget.visit()) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (matcher.matches(walkBase.relativize(file)) && attrs.isRegularFile()) {
                                Path real = file.toRealPath();
                                if (allowExternal || real.startsWith(projectRoot)) {
                                    matches.add(real);
                                } else {
                                    issues.add(error("symlink_escape", field,
                                            "Matched file resolves outside project_root: " + file));
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return assetBudget.visit() ? FileVisitResult.CONTINUE
                                    : FileVisitResult.TERMINATE;
                        }
                        @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            issues.add(error("glob_read_failed", field, "Could not scan assets: " + message(e)));
        }
        if (assetBudget.exhausted) assetBudget.report(field, issues);
        return matches;
    }

    private static void validateReleasePath(Map<String, Object> policy, Path projectRoot,
            boolean allowExternal, Map<String, List<Path>> assets, List<PolicyIssue> issues) {
        String output = string(object(policy, "release"), "output_dir");
        if (output == null) {
            return;
        }
        Path resolved = resolveAsset(output, projectRoot, allowExternal, false,
                "release.output_dir", issues);
        if (resolved != null && (!Files.exists(resolved)
                || requireDirectory(resolved, "release.output_dir", issues))) {
            assets.put("release_output", List.of(resolved));
        }
    }

    private static void validateV2(Map<String, Object> policy, Path policyPath, Path projectRoot,
            Map<String, List<Path>> assets, List<PolicyIssue> issues) {
        Set<String> providerIds = new LinkedHashSet<>();
        Set<String> enabledProviderIds = new LinkedHashSet<>();
        for (Map<String, Object> provider : objects(object(policy, "external_terms").get("providers"))) {
            String id = string(provider, "id");
            if (id != null && !providerIds.add(id)) {
                issues.add(error("provider_id_duplicate", "external_terms.providers",
                        "Provider id '" + id + "' is declared more than once."));
            }
            if (id != null && bool(provider, "enabled", false)) enabledProviderIds.add(id);
        }
        int evidenceIndex = 0;
        for (String id : strings(object(object(policy, "validation"),
                "provider_evidence").get("providers"))) {
            if (!providerIds.contains(id)) {
                issues.add(error("provider_id_unknown",
                        "validation.provider_evidence.providers[" + evidenceIndex + "]",
                        "Provider-evidence stage references undeclared provider '" + id + "'."));
            } else if (!enabledProviderIds.contains(id)) {
                issues.add(error("provider_disabled_for_evidence",
                        "validation.provider_evidence.providers[" + evidenceIndex + "]",
                        "Provider-evidence stage references disabled provider '" + id + "'."));
            }
            evidenceIndex++;
        }

        Map<String, Object> mappings = object(policy, "mappings");
        String mappingPath = string(mappings, "path");
        Path resolvedMapping = resolveAsset(mappingPath, projectRoot, false, false,
                "mappings.path", issues);
        if (resolvedMapping != null && conflictsWithReservedPath(resolvedMapping, policyPath, assets)) {
            issues.add(error("mapping_path_collision", "mappings.path",
                    "Mapping output must be a dedicated sidecar and cannot reuse the policy or another "
                            + "declared project asset path."));
            resolvedMapping = null;
        }
        if (resolvedMapping != null && Files.exists(resolvedMapping)
                && requireRegularFile(resolvedMapping, "mappings.path", issues)) {
            assets.put("mapping_store", List.of(resolvedMapping));
        }
        validateTermReferenceList(strings(mappings.get("allowed_predicates")),
                object(policy, "prefixes"), "mappings.allowed_predicates", issues);
        int ruleIndex = 0;
        for (Map<String, Object> rule : objects(mappings.get("many_to_one_rules"))) {
            String path = "mappings.many_to_one_rules[" + ruleIndex++ + "]";
            validateTermReferenceList(List.of(string(rule, "predicate")),
                    object(policy, "prefixes"), path + ".predicate", issues);
            validateTermReferenceList(strings(rule.get("subject_ontologies")),
                    object(policy, "prefixes"), path + ".subject_ontologies", issues);
            validateTermReferenceList(strings(rule.get("target_ontologies")),
                    object(policy, "prefixes"), path + ".target_ontologies", issues);
            if (strings(rule.get("subject_ontologies")).isEmpty()
                    && strings(rule.get("subject_providers")).isEmpty()
                    && strings(rule.get("target_ontologies")).isEmpty()) {
                issues.add(error("mapping_scope_empty", path,
                        "A many-to-one rule must name a non-empty subject ontology/provider or target scope."));
            }
            int providerIndex = 0;
            for (String id : strings(rule.get("subject_providers"))) {
                if (!providerIds.contains(id)) {
                    issues.add(error("provider_id_unknown",
                            path + ".subject_providers[" + providerIndex + "]",
                            "Many-to-one rule references undeclared provider '" + id + "'."));
                }
                providerIndex++;
            }
        }

        Map<String, Object> jobs = object(policy, "jobs");
        int active = ((Number) jobs.get("active_per_principal")).intValue();
        int retainedPrincipal =
                ((Number) jobs.get("retained_per_principal")).intValue();
        int retainedBackend =
                ((Number) jobs.get("retained_per_backend")).intValue();
        if (retainedPrincipal < active) {
            issues.add(error("job_retention_below_active",
                    "jobs.retained_per_principal",
                    "retained_per_principal must be at least active_per_principal."));
        }
        if (retainedBackend < retainedPrincipal) {
            issues.add(error("job_backend_retention_below_principal",
                    "jobs.retained_per_backend",
                    "retained_per_backend must be at least retained_per_principal."));
        }
    }

    private static boolean requireRegularFile(Path path, String field, List<PolicyIssue> issues) {
        if (Files.isRegularFile(path)) {
            return true;
        }
        issues.add(error("asset_not_file", field,
                "Policy asset must resolve to a regular file: " + path));
        return false;
    }

    private static boolean requireDirectory(Path path, String field, List<PolicyIssue> issues) {
        if (Files.isDirectory(path)) {
            return true;
        }
        issues.add(error("asset_not_directory", field,
                "Policy path must resolve to a directory: " + path));
        return false;
    }

    /**
     * Relative asset paths anchor at the effective project root (see {@link #resolveProjectRoot}),
     * so a discovered {@code .protege-mcp/project.yaml} reaches the sources beside that directory.
     */
    private static Path resolveAsset(String configured, Path projectRoot,
            boolean allowExternal, boolean mustExist, String field, List<PolicyIssue> issues) {
        if (configured == null) {
            return null;
        }
        if (!WINDOWS_ABSOLUTE.matcher(configured).matches() && URI_SCHEME.matcher(configured).matches()) {
            issues.add(error("path_scheme_forbidden", field,
                    "Policy asset paths must be local filesystem paths, not URLs: " + configured));
            return null;
        }
        Path path;
        try {
            path = isPortableAbsolute(configured) ? Path.of(configured).normalize()
                    : projectRoot.resolve(configured).normalize();
        } catch (InvalidPathException e) {
            issues.add(error("path_invalid", field, "Invalid path '" + configured + "': " + message(e)));
            return null;
        }
        if (!allowExternal && !path.startsWith(projectRoot)) {
            issues.add(error("path_outside_project", field, "Path escapes project_root: " + configured));
            return null;
        }
        if (mustExist && !Files.exists(path)) {
            issues.add(error("asset_missing", field, "Required policy asset does not exist: " + path));
            return null;
        }
        try {
            if (!projectRoot.equals(projectRoot.toRealPath())) {
                issues.add(error("project_root_changed", field,
                        "project_root changed while policy assets were being resolved."));
                return null;
            }
            if (!mustExist && symbolicComponent(path) != null) {
                issues.add(error("symlink_escape", field,
                        "Writable policy output paths must not contain symbolic links: " + path));
                return null;
            }
            if (!mustExist && Files.isSymbolicLink(path)) {
                issues.add(error("symlink_escape", field,
                        "Writable policy output paths must not be symbolic links: " + path));
                return null;
            }
            if (Files.exists(path)) {
                Path real = path.toRealPath();
                boolean authoredInside = path.startsWith(projectRoot);
                if (authoredInside && !real.startsWith(projectRoot)
                        || !allowExternal && !real.startsWith(projectRoot)) {
                    issues.add(error("symlink_escape", field,
                            "Path resolves outside project_root through a symbolic link: " + path));
                    return null;
                }
                return real;
            }
            Path parent = nearestExistingParent(path);
            if (parent == null) {
                issues.add(error("path_parent_missing", field, "No existing parent for path: " + path));
                return null;
            }
            if (!Files.isDirectory(parent)) {
                issues.add(error("path_parent_not_directory", field,
                        "The nearest existing path ancestor is not a directory: " + parent));
                return null;
            }
            Path realParent = parent.toRealPath();
            if (!allowExternal && !realParent.startsWith(projectRoot)) {
                issues.add(error("symlink_escape", field,
                        "Path parent resolves outside project_root: " + path));
                return null;
            }
            Path suffix = parent.relativize(path.toAbsolutePath().normalize());
            return realParent.resolve(suffix).normalize();
        } catch (IOException e) {
            issues.add(error("path_unresolvable", field, "Could not resolve path " + path + ": " + message(e)));
            return null;
        }
    }

    private static Path nearestExistingParent(Path path) {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
        }
        return null;
    }

    private static Path symbolicComponent(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path name : absolute) {
            current = current == null ? name : current.resolve(name);
            if (Files.isSymbolicLink(current)) return current;
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return null;
        }
        return null;
    }

    private static boolean isPortableAbsolute(String value) {
        return Path.of(value).isAbsolute() || WINDOWS_ABSOLUTE.matcher(value).matches()
                || value.startsWith("\\\\");
    }

    private static boolean hasGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.indexOf('[') >= 0
                || value.indexOf('{') >= 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> defaults(Map<String, Object> parsed) {
        Map<String, Object> out = JSON.convertValue(parsed, new TypeReference<LinkedHashMap<String, Object>>() { });
        out.putIfAbsent("project_root", ".");
        out.putIfAbsent("prefixes", new LinkedHashMap<>());
        out.putIfAbsent("modules", new ArrayList<>());

        Map<String, Object> interoperability = ensureObject(out, "interoperability");
        interoperability.putIfAbsent("additional_profiles", new ArrayList<>());
        Map<String, Object> metadata = ensureObject(interoperability, "metadata");
        metadata.putIfAbsent("format", ProjectInteroperability.DEFAULT_RO_CRATE_FORMAT);
        metadata.putIfAbsent("path", "ro-crate-1.0".equals(metadata.get("format"))
                ? "ro-crate-metadata.jsonld" : "ro-crate-metadata.json");
        Map<String, Object> canonicalization = ensureObject(interoperability, "canonicalization");
        canonicalization.putIfAbsent("timeout_ms", 120_000);

        Map<String, Object> filesystem = ensureObject(out, "filesystem");
        filesystem.putIfAbsent("allow_external_paths", false);
        Map<String, Object> network = ensureObject(out, "network");
        network.putIfAbsent("default", "deny");
        network.putIfAbsent("allowed_hosts", new ArrayList<>());
        Map<String, Object> imports = ensureObject(out, "imports");
        imports.putIfAbsent("mode", "unlocked");
        imports.putIfAbsent("fail_on_missing", false);
        imports.putIfAbsent("network", network.get("default"));
        Map<String, Object> reasoning = ensureObject(out, "reasoning");
        reasoning.putIfAbsent("owl_profile", "DL");
        reasoning.putIfAbsent("required", false);
        reasoning.putIfAbsent("timeout_ms", 120_000);
        Map<String, Object> annotations = ensureObject(out, "annotations");
        annotations.putIfAbsent("required", new ArrayList<>());
        Map<String, Object> labels = ensureObject(annotations, "labels");
        labels.putIfAbsent("properties", new ArrayList<>());
        labels.putIfAbsent("required_languages", new ArrayList<>());
        labels.putIfAbsent("one_preferred_per_language", false);
        Map<String, Object> definitions = ensureObject(annotations, "definitions");
        definitions.putIfAbsent("properties", new ArrayList<>());
        definitions.putIfAbsent("required", false);
        definitions.putIfAbsent("required_languages", new ArrayList<>());
        if (out.get("lifecycle") instanceof Map) {
            Map<String, Object> lifecycle = ensureObject(out, "lifecycle");
            // Defaults must never manufacture a contradiction the author did not write: the
            // OBO-style "deprecated" status is only defaulted when allowed_values can actually
            // carry it, and replacement properties are only demanded by default when there are
            // deprecated values to replace. A schema-minimal {status_property, allowed_values}
            // block therefore stays valid on its own.
            Set<String> allowedValues = strings(lifecycle.get("allowed_values")).stream()
                    .map(v -> v.trim().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            lifecycle.putIfAbsent("deprecated_values", allowedValues.contains("deprecated")
                    ? new ArrayList<>(List.of("deprecated")) : new ArrayList<>());
            lifecycle.putIfAbsent("replaced_by_properties", new ArrayList<>());
            lifecycle.putIfAbsent("require_replacement",
                    !strings(lifecycle.get("deprecated_values")).isEmpty());
        }
        Map<String, Object> validation = ensureObject(out, "validation");
        validation.putIfAbsent("required_stages", new ArrayList<>(
                List.of("interoperability", "reasoner", "profile", "governance", "structural")));
        List<String> configuredStages = strings(validation.get("required_stages"));
        if (!configuredStages.contains("interoperability")) {
            List<String> required = new ArrayList<>();
            required.add("interoperability");
            required.addAll(configuredStages);
            validation.put("required_stages", required);
        }
        if (bool(reasoning, "required", false)) {
            List<String> configured = strings(validation.get("required_stages"));
            if (!configured.contains("reasoner")) {
                List<String> required = new ArrayList<>();
                required.add("reasoner");
                required.addAll(configured);
                validation.put("required_stages", required);
            }
        }
        validation.putIfAbsent("fail_on", "warning");
        validation.putIfAbsent("waivers", new ArrayList<>());
        Map<String, Object> structural = ensureObject(validation, "structural");
        structural.putIfAbsent("disabled", new ArrayList<>());
        structural.putIfAbsent("severity_overrides", new LinkedHashMap<>());
        return out;
    }

    private static Map<String, Object> defaultsV2(Map<String, Object> parsed) {
        Map<String, Object> out = defaults(parsed);

        Map<String, Object> audit = ensureObject(out, "audit");
        audit.putIfAbsent("retention_days", 90);
        audit.putIfAbsent("max_file_bytes", 10_485_760);
        audit.putIfAbsent("max_files", 10);

        Map<String, Object> external = ensureObject(out, "external_terms");
        external.putIfAbsent("providers", new ArrayList<>());
        for (Map<String, Object> provider : objects(external.get("providers"))) {
            provider.putIfAbsent("ontologies", new ArrayList<>());
            provider.putIfAbsent("languages", new ArrayList<>());
            provider.putIfAbsent("ttl_seconds", 900);
            provider.putIfAbsent("freshness", "cache_ok");
            provider.putIfAbsent("required_evidence_for", new ArrayList<>());
            provider.putIfAbsent("max_results", 25);
        }

        Map<String, Object> providerEvidence = object(ensureObject(out, "validation"),
                "provider_evidence");
        if (!providerEvidence.isEmpty()) {
            providerEvidence.putIfAbsent("freshness", "fresh_required");
        }

        Map<String, Object> mappings = ensureObject(out, "mappings");
        mappings.putIfAbsent("path", ".protege-mcp/mappings.sssom.tsv");
        mappings.putIfAbsent("allowed_predicates", new ArrayList<>());
        mappings.putIfAbsent("allowed_sources", new ArrayList<>());
        mappings.putIfAbsent("allowed_licenses", new ArrayList<>());
        mappings.putIfAbsent("require_license", false);
        mappings.putIfAbsent("required_findings", new ArrayList<>());
        Map<String, Object> cycle = ensureObject(mappings, "directional_cycle_policy");
        cycle.putIfAbsent("skos:broadMatch", "error");
        cycle.putIfAbsent("skos:narrowMatch", "error");
        mappings.putIfAbsent("many_to_one_rules", new ArrayList<>());

        Map<String, Object> jobs = ensureObject(out, "jobs");
        jobs.putIfAbsent("allowed_types", new ArrayList<>(List.of(
                "classification", "project_qc", "semantic_diff", "inference_materialization")));
        jobs.putIfAbsent("workers", 2);
        jobs.putIfAbsent("queue_capacity", 32);
        jobs.putIfAbsent("active_per_principal", 8);
        jobs.putIfAbsent("retained_per_principal", 32);
        jobs.putIfAbsent("retained_per_backend", 128);
        jobs.putIfAbsent("retention_seconds", 3_600);

        Map<String, Object> materialization = ensureObject(out, "materialization");
        materialization.putIfAbsent("allowed_reasoners", new ArrayList<>());
        materialization.putIfAbsent("allowed_categories", new ArrayList<>(List.of(
                "subclass_axioms", "equivalent_class_axioms", "class_assertions",
                "property_hierarchy_axioms", "object_property_assertions",
                "data_property_assertions")));
        materialization.putIfAbsent("allowed_destinations",
                new ArrayList<>(List.of("new_ontology", "project_file")));
        materialization.putIfAbsent("allow_source_write", false);
        materialization.putIfAbsent("max_axioms_per_category", 50_000);
        materialization.putIfAbsent("max_axioms_total", 50_000);
        materialization.putIfAbsent("max_bytes", 67_108_864);
        materialization.putIfAbsent("timeout_ms", 120_000);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureObject(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        Map<String, Object> made = new LinkedHashMap<>();
        map.put(key, made);
        return made;
    }

    private static String digest(Map<String, Object> effective) {
        try {
            byte[] canonical = JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(effective);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder out = new StringBuilder("sha256:");
            for (byte b : hash) {
                out.append(Character.forDigit((b >>> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not digest validated policy", e);
        }
    }

    private static ObjectMapper yamlMapper() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setNestingDepthLimit(100);
        loaderOptions.setCodePointLimit((int) MAX_POLICY_BYTES);
        YAMLFactory factory = YAMLFactory.builder()
                .loaderOptions(loaderOptions)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(100).maxStringLength((int) MAX_POLICY_BYTES).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    private static void rejectYamlAliases(byte[] bytes) throws IOException {
        try (JsonParser parser = YAML.getFactory().createParser(bytes)) {
            while (parser.nextToken() != null) {
                if (parser instanceof YAMLParser yaml && yaml.isCurrentAlias()) {
                    throw new IOException("YAML aliases are not supported in project policies");
                }
            }
        }
    }

    private static Map<String, Object> loadSchema(int version) {
        try (InputStream in = ProjectPolicyLoader.class.getResourceAsStream(
                "/schema/project-policy-v" + version + ".schema.json")) {
            if (in == null) {
                throw new IllegalStateException("Packaged project-policy v" + version
                        + " schema is missing");
            }
            return JSON.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Could not load project-policy v" + version + " schema", e);
        }
    }

    private static Schema compiledSchema(Map<String, Object> schema) {
        return SCHEMA_REGISTRY.getSchema(JSON.valueToTree(schema));
    }

    private static boolean schemaValid(Schema schema, Map<String, Object> value) {
        return schema.validate(JSON.valueToTree(value),
                (java.util.function.Consumer<ExecutionContext>) context -> context.setFailFast(true))
                .isEmpty();
    }

    private static Integer policyVersion(Map<String, Object> policy) {
        Object raw = policy == null ? null : policy.get("version");
        if (!(raw instanceof Number number)) return null;
        int value = number.intValue();
        return number.doubleValue() == value ? value : null;
    }

    private static PolicyIssue error(String code, String path, String message) {
        String safe = ContractRedactor.sanitize(message == null ? code : message);
        if (safe.length() > 2_048) safe = safe.substring(0, 2_048);
        return new PolicyIssue("error", code, path, safe);
    }

    private static boolean exceedsStructureBudget(Object root, int maximum) {
        ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(root);
        long nodes = 0;
        while (!pending.isEmpty()) {
            Object value = pending.removeLast();
            if (++nodes > maximum) return true;
            if (value instanceof Map<?, ?> map) {
                nodes += map.size();
                if (nodes > maximum) return true;
                for (Object nested : map.values()) {
                    if (nested == null) {
                        if (++nodes > maximum) return true;
                    } else {
                        pending.add(nested);
                    }
                }
            } else if (value instanceof Collection<?> collection) {
                for (Object nested : collection) {
                    if (nested == null) {
                        if (++nodes > maximum) return true;
                    } else {
                        pending.add(nested);
                    }
                }
            }
        }
        return false;
    }

    private static boolean exceedsExpandedScalarBudget(Object root, long maximum) {
        ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(root);
        long bytes = 0;
        while (!pending.isEmpty()) {
            Object value = pending.removeLast();
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    bytes += utf8Length(String.valueOf(entry.getKey()));
                    if (bytes > maximum) return true;
                    if (entry.getValue() != null) pending.add(entry.getValue());
                }
            } else if (value instanceof Collection<?> collection) {
                collection.stream().filter(Objects::nonNull).forEach(pending::add);
            } else if (value instanceof CharSequence text) {
                bytes += utf8Length(text);
                if (bytes > maximum) return true;
            }
        }
        return false;
    }

    private static long utf8Length(CharSequence value) {
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current <= 0x7f) {
                bytes++;
            } else if (current <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(current) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static List<PolicyIssue> boundedIssues(List<PolicyIssue> issues) {
        boolean truncated = issues instanceof PolicyIssues bounded && bounded.truncated;
        if (!truncated && issues.size() <= MAX_POLICY_ISSUES) return issues;
        List<PolicyIssue> bounded = new ArrayList<>(issues.subList(0, MAX_POLICY_ISSUES - 1));
        bounded.add(error("policy_issues_truncated", null,
                "Policy validation produced more than " + MAX_POLICY_ISSUES
                        + " issues; remaining issues were omitted."));
        return bounded;
    }

    private static boolean conflictsWithReservedPath(Path candidate, Path policyPath,
            Map<String, List<Path>> assets) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (policyPath != null && pathsOverlap(normalized,
                policyPath.toAbsolutePath().normalize())) return true;
        return assets.values().stream().flatMap(Collection::stream)
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(path -> pathsOverlap(normalized, path));
    }

    private static boolean pathsOverlap(Path left, Path right) {
        if (left.equals(right) || left.startsWith(right) || right.startsWith(left)) return true;
        if (!Files.exists(left, LinkOption.NOFOLLOW_LINKS)
                || !Files.exists(right, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            return Files.isSameFile(left, right);
        } catch (IOException cannotProveDistinct) {
            return true;
        }
    }

    static byte[] readStablePolicyBytes(Path path, Set<OpenOption> readOptions,
            ReadInterlock interlock) throws IOException {
        byte[] first = readPolicyBytesOnce(path, readOptions);
        interlock.run();
        byte[] second = readPolicyBytesOnce(path, readOptions);
        if (!Arrays.equals(first, second)) {
            throw new PolicyChangedDuringReadException(
                    "Project policy content changed while it was being read.");
        }
        return first;
    }

    private static byte[] readPolicyBytesOnce(Path path, Set<OpenOption> readOptions)
            throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, readOptions);
                InputStream input = Channels.newInputStream(channel)) {
            return input.readNBytes(Math.toIntExact(MAX_POLICY_BYTES + 1));
        }
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static boolean isAbsoluteIri(String value) {
        try {
            return java.net.URI.create(value).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        if (map == null) {
            return Collections.emptyMap();
        }
        Object value = map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List ? (List<String>) value : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : Collections.emptyList();
    }

    /** Immutable policy bytes inseparably paired with their discovery-time identity pin. */
    public static final class CapturedPolicy {
        private final PolicySourcePin pin;
        private final byte[] bytes;

        private CapturedPolicy(PolicySourcePin pin, byte[] bytes) {
            this.pin = Objects.requireNonNull(pin, "pin");
            this.bytes = bytes.clone();
        }

        public Path source() {
            return pin.source();
        }

        /** A defensive copy suitable for a private workspace snapshot. */
        public byte[] bytes() {
            return bytes.clone();
        }

        /** True only while the source and project anchor still match this capture. */
        public boolean isCurrent() {
            return pin.isCurrent();
        }
    }

    /** Opaque discovery-time identity pin carried through capture and semantic validation. */
    public static final class PolicySourcePin {
        private final Path source;
        private final Path trustedProjectAnchor;
        private final PathIdentity sourceIdentity;
        private final PathIdentity anchorIdentity;

        private PolicySourcePin(Path source, Path trustedProjectAnchor,
                PathIdentity sourceIdentity, PathIdentity anchorIdentity) {
            this.source = source;
            this.trustedProjectAnchor = trustedProjectAnchor;
            this.sourceIdentity = sourceIdentity;
            this.anchorIdentity = anchorIdentity;
        }

        public Path source() {
            return source;
        }

        public Path trustedProjectAnchor() {
            return trustedProjectAnchor;
        }

        /** True only while both lexical paths still name the originally pinned filesystem objects. */
        public boolean isCurrent() {
            try {
                return source.equals(source.toRealPath())
                        && trustedProjectAnchor.equals(trustedProjectAnchor.toRealPath())
                        && source.startsWith(trustedProjectAnchor)
                        && sourceIdentity.matches(source)
                        && anchorIdentity.matches(trustedProjectAnchor);
            } catch (IOException unavailableOrReplaced) {
                return false;
            }
        }
    }

    private record PathIdentity(Object fileKey, long size,
            java.nio.file.attribute.FileTime modifiedTime,
            boolean directory, boolean contentSensitive) {
        static PathIdentity source(Path path) throws IOException {
            return capture(path, false, true);
        }

        static PathIdentity directory(Path path) throws IOException {
            return capture(path, true, false);
        }

        private static PathIdentity capture(Path path, boolean directory,
                boolean contentSensitive) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.fileKey() == null
                    || directory && !attributes.isDirectory()
                    || !directory && !attributes.isRegularFile()) {
                throw new IOException("filesystem identity is unavailable for policy source");
            }
            return new PathIdentity(attributes.fileKey(), attributes.size(),
                    attributes.lastModifiedTime(), directory, contentSensitive);
        }

        boolean matches(Path path) throws IOException {
            BasicFileAttributes current = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return Objects.equals(fileKey, current.fileKey())
                    && (directory ? current.isDirectory() : current.isRegularFile())
                    && (!contentSensitive || size == current.size()
                            && modifiedTime.equals(current.lastModifiedTime()));
        }
    }

    private record Discovery(String kind, Path path, PolicyIssue issue, Path trustedRoot,
            PolicySourcePin sourcePin) {
        private Discovery(String kind, Path path, PolicyIssue issue) {
            this(kind, path, issue, null, null);
        }

        private Discovery(String kind, Path path, PolicyIssue issue, Path trustedRoot) {
            this(kind, path, issue, trustedRoot, null);
        }
    }

    @FunctionalInterface
    interface ReadInterlock {
        void run() throws IOException;
    }

    private static final class PolicyChangedDuringReadException extends IOException {
        private static final long serialVersionUID = 1L;

        private PolicyChangedDuringReadException(String message) {
            super(message);
        }
    }

    private static final class PolicyIssues extends ArrayList<PolicyIssue> {
        private static final long serialVersionUID = 1L;
        private boolean truncated;
        private boolean hasError;

        @Override
        public boolean add(PolicyIssue issue) {
            hasError |= "error".equals(issue.severity());
            if (size() < MAX_POLICY_ISSUES) return super.add(issue);
            truncated = true;
            return false;
        }
    }

    private static final class AssetScanBudget {
        private int visited;
        private boolean exhausted;
        private boolean reported;

        private boolean visit() {
            if (exhausted) return false;
            exhausted = ++visited > MAX_ASSET_FILES;
            return !exhausted;
        }

        private void report(String field, List<PolicyIssue> issues) {
            if (reported) return;
            reported = true;
            issues.add(error("asset_scan_limit", field,
                    "Cumulative asset scan exceeded " + MAX_ASSET_FILES
                            + " files/directories; narrow the configured globs."));
        }
    }
}
