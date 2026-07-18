package io.github.hakjuoh.protege_mcp.policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;

/** Secure discovery and validation for the version 1 project-policy authoring format. */
public final class ProjectPolicyLoader {

    public static final String DEFAULT_RELATIVE_PATH = ".protege-mcp/project.yaml";
    public static final long MAX_POLICY_BYTES = 1_048_576L;
    static final int MAX_ASSET_FILES = 10_000;
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final ObjectMapper YAML = yamlMapper();
    private static final Map<String, Object> SCHEMA = loadSchema();
    private static final DefaultJsonSchemaValidator SCHEMA_VALIDATOR =
            new DefaultJsonSchemaValidator(JSON);

    private ProjectPolicyLoader() {
    }

    /**
     * Discover an explicit policy first, otherwise walk from the ontology document's directory toward
     * the filesystem root. A missing implicit policy is a normal unloaded result; a bad explicit path is
     * a loaded-invalid result so callers cannot silently fall back to another file.
     */
    public static ProjectPolicy load(Path explicitPolicy, Path ontologyDocument,
            String activeOntologyIri, Collection<String> installedReasoners) {
        Discovery discovery = discover(explicitPolicy, ontologyDocument);
        if (discovery.path == null) {
            if (discovery.issue == null) {
                return ProjectPolicy.notFound();
            }
            return invalidDiscovery(discovery);
        }
        return read(discovery, activeOntologyIri, installedReasoners);
    }

    public static ProjectPolicy load(Path explicitPolicy, Path ontologyDocument) {
        return load(explicitPolicy, ontologyDocument, null, null);
    }

    private static ProjectPolicy read(Discovery discovery, String activeOntologyIri,
            Collection<String> installedReasoners) {
        List<PolicyIssue> issues = new ArrayList<>();
        Path path;
        try {
            path = discovery.path.toRealPath();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path)) {
                issues.add(error("policy_not_file", "policy_path", "Policy is not a regular file: " + path));
                return result(discovery.kind, path, null, null, Collections.emptyMap(),
                        Collections.emptyMap(), issues);
            }
            long size = Files.size(path);
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

        Map<String, Object> parsed;
        try {
            byte[] bytes = Files.readAllBytes(path);
            parsed = YAML.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() { });
            if (parsed == null) {
                throw new IOException("document is empty");
            }
        } catch (IOException | RuntimeException e) {
            issues.add(error("yaml_invalid", null, "Could not parse policy YAML: " + message(e)));
            return result(discovery.kind, path, projectRootAnchor(path.getParent()), null,
                    Collections.emptyMap(), Collections.emptyMap(), issues);
        }

        ValidationResponse schema = SCHEMA_VALIDATOR.validate(SCHEMA, parsed);
        if (!schema.valid()) {
            issues.add(error("schema_invalid", null, schema.errorMessage()));
            return result(discovery.kind, path, projectRootAnchor(path.getParent()), null, parsed,
                    Collections.emptyMap(), issues);
        }

        boolean authoredRoCrateFormat = object(
                object(parsed, "interoperability"), "metadata").containsKey("format");
        Map<String, Object> effective = defaults(parsed);
        Path projectRoot = resolveProjectRoot(path.getParent(), string(effective, "project_root"), issues);
        Map<String, List<Path>> assets = new LinkedHashMap<>();
        semanticValidation(effective, projectRoot, activeOntologyIri, installedReasoners,
                !authoredRoCrateFormat, assets, issues);
        return result(discovery.kind, path, projectRoot, digest(effective), effective, assets, issues);
    }

    private static Discovery discover(Path explicit, Path ontologyDocument) {
        if (explicit != null) {
            try {
                Path normalized = explicit.toAbsolutePath().normalize();
                if (!Files.exists(normalized)) {
                    return new Discovery("explicit", null, error("policy_not_found", "policy_path",
                            "Explicit policy does not exist: " + normalized));
                }
                return new Discovery("explicit", normalized, null);
            } catch (InvalidPathException e) {
                return new Discovery("explicit", null, error("policy_path_invalid", "policy_path",
                        "Explicit policy path is invalid: " + message(e)));
            }
        }
        if (ontologyDocument == null) {
            return new Discovery("none", null, null);
        }
        Path start = Files.isDirectory(ontologyDocument) ? ontologyDocument
                : ontologyDocument.toAbsolutePath().normalize().getParent();
        for (Path current = start; current != null; current = current.getParent()) {
            Path candidate = current.resolve(DEFAULT_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return new Discovery("discovered", candidate, null);
            }
        }
        return new Discovery("none", null, null);
    }

    private static ProjectPolicy invalidDiscovery(Discovery discovery) {
        return new ProjectPolicy(true, discovery.kind, null, null, null,
                Collections.emptyMap(), Collections.emptyMap(), List.of(discovery.issue));
    }

    private static ProjectPolicy result(String discovery, Path path, Path projectRoot, String digest,
            Map<String, Object> effective, Map<String, List<Path>> assets, List<PolicyIssue> issues) {
        return new ProjectPolicy(true, discovery, path, projectRoot, digest, effective, assets, issues);
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

    private static Path resolveProjectRoot(Path policyDir, String configured, List<PolicyIssue> issues) {
        Path anchor = projectRootAnchor(policyDir);
        Path candidate = anchor.resolve(configured == null ? "." : configured).normalize();
        if (!candidate.startsWith(anchor.normalize())) {
            issues.add(error("project_root_escape", "project_root",
                    "project_root must name the project base directory or a descendant."));
            return null;
        }
        try {
            Path realAnchor = anchor.toRealPath();
            Path real = candidate.toRealPath();
            if (!Files.isDirectory(real) || !real.startsWith(realAnchor)) {
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

    private static void semanticValidation(Map<String, Object> policy, Path projectRoot,
            String activeOntologyIri, Collection<String> installedReasoners,
            boolean inferRoCrateVersion, Map<String, List<Path>> assets,
            List<PolicyIssue> issues) {
        if (projectRoot == null) {
            return;
        }
        boolean allowExternal = bool(object(policy, "filesystem"), "allow_external_paths", false);

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
        validateValidationAssets(policy, projectRoot, allowExternal, assets, issues);
        validateReleasePath(policy, projectRoot, allowExternal, assets, issues);
        if (interopManifest != null) {
            if (inferRoCrateVersion) {
                RoCrateProjectManifest.inferVersion(interopManifest, policy);
            }
            RoCrateProjectManifest.validate(interopManifest, policy, issues);
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
        for (String ref : refs) {
            int colon = ref.indexOf(':');
            if (colon <= 0 || colon == ref.length() - 1) {
                issues.add(error("term_reference_invalid", "annotations",
                        "Annotation property reference must be an absolute IRI or declared CURIE: " + ref));
                continue;
            }
            String prefix = ref.substring(0, colon);
            boolean explicitAbsolute = Set.of("http", "https", "urn", "file")
                    .contains(prefix.toLowerCase(Locale.ROOT));
            if (!explicitAbsolute && !prefixes.containsKey(prefix)) {
                issues.add(error("prefix_unknown", "annotations", "CURIE uses undeclared prefix '"
                        + prefix + "': " + ref));
            }
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
        boolean found = installed.stream().filter(v -> v != null)
                .anyMatch(v -> v.equalsIgnoreCase(selected));
        if (!found) {
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
            List<PolicyIssue> issues) {
        Map<String, Object> validation = object(policy, "validation");
        expandPaths(object(validation, "invariants"), "invariants", ".rq", projectRoot,
                allowExternal, assets, issues);
        expandPaths(object(validation, "shacl"), "shacl", null, projectRoot,
                allowExternal, assets, issues);
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
            Map<String, List<Path>> assets, List<PolicyIssue> issues) {
        List<Path> resolved = new ArrayList<>();
        int index = 0;
        for (String configured : strings(block.get("paths"))) {
            String field = "validation." + key + ".paths[" + index + "]";
            if (hasGlob(configured)) {
                resolved.addAll(expandGlob(configured, projectRoot, allowExternal, field, issues));
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
            boolean allowExternal, String field, List<PolicyIssue> issues) {
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
        final int[] visited = {0};
        final Path walkBase = base;
        try {
            Files.walkFileTree(walkBase, Collections.<FileVisitOption>emptySet(),
                    64, new FileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            return ++visited[0] > MAX_ASSET_FILES ? FileVisitResult.TERMINATE
                                    : FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            if (++visited[0] > MAX_ASSET_FILES) {
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
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            issues.add(error("glob_read_failed", field, "Could not scan assets: " + message(e)));
        }
        if (visited[0] > MAX_ASSET_FILES) {
            issues.add(error("asset_scan_limit", field, "Asset scan exceeded " + MAX_ASSET_FILES
                    + " files/directories; narrow the configured glob."));
        }
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
            if (Files.exists(path)) {
                Path real = path.toRealPath();
                if (!allowExternal && !real.startsWith(projectRoot)) {
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
            Path realParent = parent.toRealPath();
            if (!allowExternal && !realParent.startsWith(projectRoot)) {
                issues.add(error("symlink_escape", field,
                        "Path parent resolves outside project_root: " + path));
                return null;
            }
            return path.toAbsolutePath().normalize();
        } catch (IOException e) {
            issues.add(error("path_unresolvable", field, "Could not resolve path " + path + ": " + message(e)));
            return null;
        }
    }

    private static Path nearestExistingParent(Path path) {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.exists(current)) {
                return current;
            }
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
        YAMLFactory factory = YAMLFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(100).maxStringLength((int) MAX_POLICY_BYTES).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    private static Map<String, Object> loadSchema() {
        try (InputStream in = ProjectPolicyLoader.class.getResourceAsStream(
                "/schema/project-policy-v1.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("Packaged project-policy v1 schema is missing");
            }
            return JSON.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Could not load project-policy v1 schema", e);
        }
    }

    private static PolicyIssue error(String code, String path, String message) {
        return new PolicyIssue("error", code, path, message == null ? code : message);
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

    private record Discovery(String kind, Path path, PolicyIssue issue) { }
}
