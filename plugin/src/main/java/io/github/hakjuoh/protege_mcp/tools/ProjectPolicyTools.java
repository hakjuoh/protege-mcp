package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.external.ProviderConfigurationStore;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ProviderPolicyBindings;
import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.CapturedPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.PolicySourcePin;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Policy discovery and validation tools; filesystem work always runs off the Protégé model thread. */
public final class ProjectPolicyTools {

    private static final Object[] POLICY_WRITE_LOCKS = writeLocks();

    private ProjectPolicyTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("get_project_policy", (ex, req) -> {
                    Map<String, Object> arguments = Tools.args(req);
                    DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex,
                            Tools.optString(arguments, "policy_path"));
                    return run(ctx, rules.authorizedPolicyArguments(arguments), false);
                });
        tools.tool("validate_project_policy", (ex, req) -> {
                    Map<String, Object> arguments = Tools.args(req);
                    DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex,
                            Tools.optString(arguments, "policy_path"));
                    return run(ctx, rules.authorizedPolicyArguments(arguments), true);
                });
        tools.tool("run_project_qc",
                (ex, req) -> {
                    Map<String, Object> arguments = Tools.args(req);
                    DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex,
                            Tools.optString(arguments, "policy_path"));
                    return ProjectQcTools.run(ctx, rules.authorizedPolicyArguments(arguments), true,
                            rules);
                });
        tools.tool("write_project_policy_template",
                (ex, req) -> writeTemplate(ctx, ex, Tools.args(req)));
        tools.tool("write_project_policy",
                (ex, req) -> writePolicy(ctx, ex, Tools.args(req)));
    }

    /**
     * Generate a commented, schema-valid starter {@code .protege-mcp/project.yaml} for review and
     * commit. This scaffolds a NEW policy file (it never mutates the ontology or an existing policy in
     * place); the write is authorized like every other file-writing tool — read-only mode, the
     * confirm-write gate, the {@code filesystem:project:write} capability, and canonical containment
     * under {@code project_root} (or the local-admin no-policy compatibility path). The generated
     * template uses the saved active ontology as {@code root_artifact}, creates matching RO-Crate
     * metadata when absent, and validates the result before returning.
     */
    static CallToolResult writeTemplate(ToolContext ctx, McpSyncServerExchange ex,
            Map<String, Object> arguments) {
        String profile = ProjectPolicyTemplate.normalizeProfile(Tools.optString(arguments, "profile"));
        if (profile == null) {
            return Tools.error("'profile' must be 'general' or 'obo'.");
        }
        boolean overwrite = Tools.optBool(arguments, "overwrite", false);
        String configuredPath = Tools.optString(arguments, "path");
        String configuredProjectId = Tools.optString(arguments, "project_id");
        Object rawVersion = arguments.get("version");
        int version = 3;
        if (rawVersion != null) {
            if (!(rawVersion instanceof Number number)
                    || number.doubleValue() != number.intValue()
                    || number.intValue() < 1 || number.intValue() > 3) {
                return Tools.error("'version' must be the integer 1, 2, or 3.");
            }
            version = number.intValue();
        }
        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex);
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx, "write a project policy template"
                + (configuredPath == null ? "" : " to " + configuredPath));
        if (denied != null) {
            return denied;
        }

        // Only small immutable coordinates come off the live model on the EDT; rendering, containment,
        // and the atomic write all run after compute returns.
        PolicyContext live = ctx.access().compute(ProjectPolicyTools::capture);
        final Path target;
        if (configuredPath != null) {
            target = authorizeTarget(rules, ex, configuredPath, null);
        } else {
            Path defaultTarget = defaultTemplatePath(live);
            if (defaultTarget == null) {
                return Tools.error("The active ontology has no local document folder, so no default "
                        + "policy location can be derived; pass 'path' to choose where to write the "
                        + "template.");
            }
            target = authorizeTarget(rules, ex, null, defaultTarget);
        }

        ProjectPolicyScaffold.Scaffold scaffold = ProjectPolicyScaffold.prepare(target,
                live.documentPath(), live.activeOntologyIri(), live.installedReasoners(),
                live.selectedReasoner(), profile, configuredProjectId, version);
        byte[] bytes = scaffold.policyBytes();
        Path metadata = authorizeDerivedTarget(rules, ex, scaffold.metadataPath());
        synchronized (policyWriteLock(target)) {
            if (!overwrite && Files.exists(target)) {
                return policyExists(target);
            }
            boolean createdMetadata = false;
            ProjectPolicy candidate;
            try {
                if (!Files.exists(metadata)) {
                    createdMetadata = createMetadata(metadata, scaffold.metadataBytes());
                }
                candidate = validateCandidate(target, bytes, live);
            } catch (IOException e) {
                rollbackCreatedMetadata(metadata, createdMetadata, scaffold.metadataBytes());
                return Tools.error("Could not validate project policy template: " + e.getMessage());
            }

            List<Map<String, Object>> errors = new ArrayList<>();
            List<Map<String, Object>> warnings = new ArrayList<>();
            collectIssues(candidate, errors, warnings);
            if (!candidate.valid()) {
                rollbackCreatedMetadata(metadata, createdMetadata, scaffold.metadataBytes());
                return templateWriteResult(false, target, scaffold, bytes,
                        candidate, errors, warnings, metadata, false)
                        .put("error_code", "policy_invalid")
                        .put("note", "The generated candidate was rejected without modifying the "
                                + "existing policy.")
                        .result();
            }
            try {
                atomicWrite(target, bytes, overwrite);
            } catch (FileAlreadyExistsException exists) {
                rollbackCreatedMetadata(metadata, createdMetadata, scaffold.metadataBytes());
                return policyExists(target);
            } catch (IOException e) {
                rollbackCreatedMetadata(metadata, createdMetadata, scaffold.metadataBytes());
                return Tools.error("Could not write project policy template: " + e.getMessage());
            }

            ProjectPolicy policy = ProjectPolicyLoader.load(target, live.documentPath(),
                    live.activeOntologyIri(), live.installedReasoners());
            errors.clear();
            warnings.clear();
            collectIssues(policy, errors, warnings);
            return templateWriteResult(true, target, scaffold, bytes, policy,
                    errors, warnings, metadata, createdMetadata)
                    .put("note", "The generated policy is valid. Review and commit it like source code.")
                    .result();
        }
    }

    private static Tools.Json templateWriteResult(boolean written, Path target,
            ProjectPolicyScaffold.Scaffold scaffold, byte[] bytes, ProjectPolicy policy,
            List<Map<String, Object>> errors, List<Map<String, Object>> warnings,
            Path metadata, boolean metadataCreated) {
        Tools.Json result = Tools.json()
                .put("written", written)
                .put("path", target.toString())
                .put("project_id", scaffold.projectId())
                .put("profile", scaffold.profile())
                .put("schema_version", scaffold.version())
                .put("bytes", bytes.length)
                .put("sha256", sha256(bytes))
                .put("policy_loaded", policy.loaded())
                .put("valid", policy.valid())
                .put("errors", errors)
                .put("warnings", warnings)
                .put("root_artifact", scaffold.rootArtifact())
                .put("metadata_path", metadata.toString())
                .put("metadata_created", metadataCreated)
                .put("validation_hint", ProjectPolicyTemplate.validationHint(scaffold.template()));
        if (policy.digest() != null) {
            result.put("policy_digest", policy.digest());
        }
        return result;
    }

    /**
     * Write or update a .protege-mcp/project.yaml file from authored YAML content or a recursive
     * merge patch. A patch merges objects, replaces arrays/scalars, and removes keys whose patch
     * value is null. Only affected top-level YAML sections are rendered again, preserving comments,
     * order, and bytes elsewhere. Every candidate is validated before the original file is changed.
     */
    static CallToolResult writePolicy(ToolContext ctx, McpSyncServerExchange ex,
            Map<String, Object> arguments) {
        String yaml = Tools.optString(arguments, "yaml");
        Object rawPatch = arguments.get("patch");
        if ((yaml == null) == (rawPatch == null)) {
            return Tools.error("Pass exactly one of 'yaml' or 'patch'. Use patch for a recursive "
                    + "partial update that preserves unaffected policy content.");
        }
        boolean overwrite = Tools.optBool(arguments, "overwrite", true);
        String configuredPath = Tools.optString(arguments, "path");
        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex);
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx, "write project policy"
                + (configuredPath == null ? "" : " to " + configuredPath));
        if (denied != null) {
            return denied;
        }

        PolicyContext live = ctx.access().compute(ProjectPolicyTools::capture);
        final Path target;
        if (configuredPath != null) {
            target = authorizeTarget(rules, ex, configuredPath, null);
        } else {
            Path defaultTarget = defaultTemplatePath(live);
            if (defaultTarget == null) {
                return Tools.error("The active ontology has no local document folder, so no default "
                        + "policy location can be derived; pass 'path' to choose where to write the "
                        + "policy.");
            }
            target = authorizeTarget(rules, ex, null, defaultTarget);
        }

        synchronized (policyWriteLock(target)) {
            return writePolicyLocked(target, yaml, rawPatch, overwrite, live, rules, ex);
        }
    }

    private static CallToolResult writePolicyLocked(Path target, String yaml, Object rawPatch,
            boolean overwrite, PolicyContext live, DirectAccessPolicy.Rules rules,
            McpSyncServerExchange ex) {
        if (!overwrite && Files.exists(target)) {
            return policyExists(target);
        }
        if (rawPatch != null && !Files.exists(target)) {
            return writePatchedScaffold(target, rawPatch, live, rules, ex);
        }
        String updateMode = "replace";
        CapturedPolicy captured = null;
        byte[] sourceBytes = null;
        if (rawPatch != null) {
            if (!Files.isRegularFile(target)) {
                return Tools.error("Patch mode requires an existing project policy; "
                        + "create it first with write_project_policy_template.");
            }
            try {
                PolicySourcePin pin = ProjectPolicyLoader.pinCanonicalPolicy(
                        target.toAbsolutePath().normalize(),
                        ProjectPolicyLoader.canonicalProjectAnchor(target));
                captured = ProjectPolicyLoader.captureStablePolicy(pin);
                sourceBytes = captured.bytes();
                yaml = ProjectPolicyPatcher.apply(new String(sourceBytes, StandardCharsets.UTF_8),
                        rawPatch);
                updateMode = "patch";
            } catch (IOException e) {
                return Tools.error("Could not safely read existing project policy: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                return Tools.error(e.getMessage());
            }
        }
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
        ProjectPolicy candidate;
        try {
            candidate = validateCandidate(target, bytes, live);
        } catch (IOException e) {
            return Tools.error("Could not validate project policy candidate: " + e.getMessage());
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        collectIssues(candidate, errors, warnings);
        if (!candidate.valid()) {
            return policyWriteResult(false, target, bytes, candidate, errors, warnings, updateMode,
                    false)
                    .put("error_code", "policy_invalid")
                    .put("note", "The candidate was rejected without modifying the existing policy.")
                    .result();
        }
        if (captured != null) {
            try {
                if (!captured.isCurrent()) {
                    return policyChanged(target);
                }
                PolicySourcePin currentPin = ProjectPolicyLoader.pinCanonicalPolicy(
                        target.toAbsolutePath().normalize(),
                        ProjectPolicyLoader.canonicalProjectAnchor(target));
                byte[] currentBytes = ProjectPolicyLoader.captureStablePolicy(currentPin).bytes();
                if (!Arrays.equals(sourceBytes, currentBytes)) {
                    return policyChanged(target);
                }
            } catch (IOException e) {
                return policyChanged(target);
            }
        }
        try {
            atomicWrite(target, bytes, overwrite);
        } catch (FileAlreadyExistsException exists) {
            return policyExists(target);
        } catch (IOException e) {
            return Tools.error("Could not write project policy: " + e.getMessage());
        }

        ProjectPolicy policy = ProjectPolicyLoader.load(target, live.documentPath(),
                live.activeOntologyIri(), live.installedReasoners());
        errors.clear();
        warnings.clear();
        collectIssues(policy, errors, warnings);
        return policyWriteResult(true, target, bytes, policy, errors, warnings, updateMode, false)
                .result();
    }

    /** Build a v3 starter in memory, apply the caller's general patch, then publish only if valid. */
    private static CallToolResult writePatchedScaffold(Path target, Object rawPatch,
            PolicyContext live, DirectAccessPolicy.Rules rules, McpSyncServerExchange ex) {
        ProjectPolicyScaffold.Scaffold scaffold = ProjectPolicyScaffold.prepare(target,
                live.documentPath(), live.activeOntologyIri(), live.installedReasoners(),
                live.selectedReasoner(), ProjectPolicyTemplate.GENERAL, null, 3);
        final String yaml;
        try {
            yaml = ProjectPolicyPatcher.apply(
                    new String(scaffold.policyBytes(), StandardCharsets.UTF_8), rawPatch);
        } catch (IOException | IllegalArgumentException e) {
            return Tools.error(e.getMessage());
        }
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
        Path metadata = authorizeDerivedTarget(rules, ex, scaffold.metadataPath());
        boolean createdMetadata = false;
        ProjectPolicy candidate;
        try {
            if (!Files.exists(metadata)) {
                createdMetadata = createMetadata(metadata, scaffold.metadataBytes());
            }
            candidate = validateCandidate(target, bytes, live);
        } catch (IOException e) {
            rollbackCreatedMetadata(metadata, createdMetadata, scaffold.metadataBytes());
            return Tools.error("Could not validate project policy candidate: " + e.getMessage());
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        collectIssues(candidate, errors, warnings);
        if (!candidate.valid()) {
            rollbackCreatedMetadata(metadata, createdMetadata, scaffold.metadataBytes());
            return policyWriteResult(false, target, bytes, candidate, errors, warnings, "patch", true)
                    .put("metadata_path", metadata.toString())
                    .put("metadata_created", false)
                    .put("error_code", "policy_invalid")
                    .put("note", "The scaffolded candidate was rejected without creating a policy.")
                    .result();
        }
        try {
            // A fresh scaffold never replaces a policy that appeared after the initial absence check.
            atomicWrite(target, bytes, false);
        } catch (FileAlreadyExistsException exists) {
            rollbackCreatedMetadata(metadata, createdMetadata, scaffold.metadataBytes());
            return policyChanged(target);
        } catch (IOException e) {
            rollbackCreatedMetadata(metadata, createdMetadata, scaffold.metadataBytes());
            return Tools.error("Could not write project policy: " + e.getMessage());
        }

        ProjectPolicy policy = ProjectPolicyLoader.load(target, live.documentPath(),
                live.activeOntologyIri(), live.installedReasoners());
        errors.clear();
        warnings.clear();
        collectIssues(policy, errors, warnings);
        return policyWriteResult(true, target, bytes, policy, errors, warnings, "patch", true)
                .put("metadata_path", metadata.toString())
                .put("metadata_created", createdMetadata)
                .put("note", "A valid v3 policy was scaffolded and patched in one operation.")
                .result();
    }

    private static Tools.Json policyWriteResult(boolean written, Path target,
            byte[] bytes, ProjectPolicy policy, List<Map<String, Object>> errors,
            List<Map<String, Object>> warnings, String updateMode, boolean createdFromTemplate) {
        Tools.Json result = Tools.json()
                .put("written", written)
                .put("path", target.toString())
                .put("bytes", bytes.length)
                .put("sha256", sha256(bytes))
                .put("policy_loaded", policy.loaded())
                .put("valid", policy.valid())
                .put("schema_version", policy.version())
                .put("errors", errors)
                .put("warnings", warnings)
                .put("update_mode", updateMode)
                .put("preserved_existing_content", "patch".equals(updateMode)
                        && !createdFromTemplate)
                .put("created_from_template", createdFromTemplate);
        if (policy.digest() != null) {
            result.put("policy_digest", policy.digest());
        }
        return result;
    }

    private static void collectIssues(ProjectPolicy policy, List<Map<String, Object>> errors,
            List<Map<String, Object>> warnings) {
        for (PolicyIssue issue : policy.issues()) {
            ("error".equals(issue.severity()) ? errors : warnings).add(issue.toJson());
        }
        capPublicIssues(errors, warnings);
    }

    private static CallToolResult policyExists(Path target) {
        return Tools.json()
                .put("written", false)
                .put("error_code", "policy_exists")
                .put("path", target.toString())
                .put("note", "A file already exists here; pass overwrite=true to replace it.")
                .result();
    }

    private static CallToolResult policyChanged(Path target) {
        return Tools.json()
                .put("written", false)
                .put("error_code", "policy_changed")
                .put("path", target.toString())
                .put("note", "The policy changed while the patch was being prepared; read the "
                        + "current policy and retry the patch.")
                .result();
    }

    private static Object[] writeLocks() {
        Object[] locks = new Object[64];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    static Object policyWriteLock(Path target) {
        int index = Math.floorMod(target.toAbsolutePath().normalize().hashCode(),
                POLICY_WRITE_LOCKS.length);
        return POLICY_WRITE_LOCKS[index];
    }

    static boolean createMetadata(Path metadata, byte[] expected) throws IOException {
        try {
            atomicWrite(metadata, expected, false);
            return true;
        } catch (FileAlreadyExistsException concurrentlyCreated) {
            return false;
        }
    }

    /** Delete only the exact sidecar this call created; never remove a concurrent replacement. */
    static void rollbackCreatedMetadata(Path metadata, boolean created, byte[] expected) {
        if (!created) return;
        try {
            if (!Files.isSymbolicLink(metadata) && Files.isRegularFile(metadata)
                    && Files.size(metadata) == expected.length
                    && Arrays.equals(Files.readAllBytes(metadata), expected)) {
                Files.deleteIfExists(metadata);
            }
        } catch (IOException ignored) {
            // The policy itself was not written. A changed or unavailable sidecar belongs to another
            // actor and must be preserved for explicit inspection.
        }
    }

    static ProjectPolicy validateCandidate(Path target, byte[] bytes, PolicyContext live)
            throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("policy path has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        Path candidate = Files.createTempFile(parent, ".project-candidate.", ".yaml");
        try {
            Files.write(candidate, bytes);
            return ProjectPolicyLoader.load(candidate, live.documentPath(),
                    live.activeOntologyIri(), live.installedReasoners());
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    /** The default beside-document location: {@code <document dir>/.protege-mcp/project.yaml}. */
    private static Path defaultTemplatePath(PolicyContext live) {
        if (live.documentPath() == null) {
            return null;
        }
        Path documentDir = live.documentPath().toAbsolutePath().normalize().getParent();
        if (documentDir == null) {
            return null;
        }
        return documentDir.resolve(ProjectPolicyLoader.DEFAULT_RELATIVE_PATH);
    }

    /**
     * Authorize the template target. An explicit path uses the caller-selected {@code writePath} rules;
     * the derived default uses {@code implicitPath} (exempt from the no-policy compatibility opt-in,
     * like every beside-document target). Because this tool CREATES the policy that would validate a
     * project, a discovered-but-invalid policy must not fail-close the write: in that one state the
     * target is authorized by canonical containment under the (invalid) policy's root without trusting
     * it to widen access — the same bootstrap {@code write_import_lock} uses for its declared lockfile.
     */
    private static Path authorizeTarget(DirectAccessPolicy.Rules rules, McpSyncServerExchange ex,
            String configuredPath, Path defaultTarget) {
        ProjectPolicy discovered = rules.policy();
        boolean invalidDiscovered = discovered.loaded() && !discovered.valid();
        if (configuredPath != null) {
            return invalidDiscovered
                    ? bootstrapContainedPath(discovered, ex, configuredPath)
                    : rules.writePath(configuredPath);
        }
        return invalidDiscovered
                ? bootstrapContainedPath(discovered, ex, defaultTarget.toString())
                : rules.implicitPath(defaultTarget, true);
    }

    /** Authorize a sidecar path derived by this tool rather than supplied by the caller. */
    private static Path authorizeDerivedTarget(DirectAccessPolicy.Rules rules,
            McpSyncServerExchange ex, Path target) {
        ProjectPolicy discovered = rules.policy();
        return discovered.loaded() && !discovered.valid()
                ? bootstrapContainedPath(discovered, ex, target.toString())
                : rules.implicitPath(target, true);
    }

    /**
     * Containment-only write authorization used while the discovered policy is loaded but invalid: the
     * invalid policy is trusted only to name its root, never to widen access ({@code allow_external_paths}
     * is deliberately ignored), so a candidate policy file inside the project can still be written to
     * fix it. Capability and canonical project-root containment are enforced.
     */
    private static Path bootstrapContainedPath(ProjectPolicy policy, McpSyncServerExchange ex,
            String configured) {
        DirectAccessPolicy.requireCapability(ex, DirectAccessPolicy.PROJECT_WRITE);
        Path root = policy.projectRoot() != null ? policy.projectRoot()
                : conventionalProjectRoot(policy.path());
        if (root == null) {
            throw new ToolArgException("The discovered project policy is invalid and names no "
                    + "canonical project_root, so a policy path cannot be contained. Fix the policy "
                    + "first (validate_project_policy).");
        }
        final Path raw;
        try {
            raw = Path.of(configured);
        } catch (InvalidPathException e) {
            throw new ToolArgException("Invalid filesystem path '" + configured + "': " + e.getMessage());
        }
        Path canonicalRoot = DirectAccessPolicy.canonicalCandidate(root.toAbsolutePath().normalize());
        Path canonical = DirectAccessPolicy.canonicalCandidate(
                (raw.isAbsolute() ? raw : canonicalRoot.resolve(raw)).normalize());
        if (!canonical.startsWith(canonicalRoot)) {
            throw new ToolArgException("Path is outside project_root and the invalid project policy "
                    + "cannot authorize external paths: " + canonical);
        }
        return canonical;
    }

    /**
     * The conventional project directory for a discovered policy file whose {@code project_root} is
     * unresolvable: strip {@link ProjectPolicyLoader#DEFAULT_RELATIVE_PATH} (discovery only ever finds a
     * policy at {@code <root>/.protege-mcp/project.yaml}); fall back to the file's own directory.
     */
    private static Path conventionalProjectRoot(Path policyFile) {
        if (policyFile == null) {
            return null;
        }
        Path relative = Path.of(ProjectPolicyLoader.DEFAULT_RELATIVE_PATH);
        if (policyFile.getNameCount() > relative.getNameCount() && policyFile.endsWith(relative)) {
            Path root = policyFile;
            for (int i = 0; i < relative.getNameCount() && root != null; i++) {
                root = root.getParent();
            }
            if (root != null) {
                return root;
            }
        }
        return policyFile.getParent();
    }

    /**
     * Write {@code bytes} to {@code target} via a temp file and an atomic move, so a partial policy
     * never lands. With {@code overwrite} false the move refuses an existing target (no
     * {@code REPLACE_EXISTING}), surfacing {@link FileAlreadyExistsException} for the {@code policy_exists}
     * result; with it true the target is replaced.
     */
    static void atomicWrite(Path target, byte[] bytes, boolean overwrite) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("policy path has no parent directory: " + normalized);
        }
        if (!overwrite && Files.exists(normalized)) {
            throw new FileAlreadyExistsException(normalized.toString());
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".project.", ".yaml.tmp");
        try {
            Files.write(temp, bytes);
            if (overwrite) {
                Files.move(temp, normalized, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, normalized, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            if (overwrite) {
                Files.move(temp, normalized, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, normalized);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : hash) {
                out.append(Character.forDigit((b >>> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }


    static CallToolResult run(ToolContext ctx, Map<String, Object> arguments, boolean requirePolicy) {
        String configured = Tools.optString(arguments, "policy_path");
        final Path explicit;
        if (configured == null) {
            explicit = null;
        } else {
            try {
                explicit = Path.of(configured);
            } catch (InvalidPathException e) {
                return Tools.ok(invalidPathResult(configured, e.getMessage()));
            }
        }

        // Only take small immutable coordinates from the live model while on the EDT. Parsing, schema
        // validation, glob expansion, checksums, and all filesystem reads happen after compute returns.
        PolicyContext live = ctx.access().compute(ProjectPolicyTools::capture);
        ProjectPolicy policy = ProjectPolicyLoader.load(explicit, live.documentPath,
                live.activeOntologyIri, live.installedReasoners);
        boolean compatibility = ctx.controller() == null
                || ctx.controller().isUnrestrictedNoPolicyPathsAllowed();
        Map<String, Object> result = toJson(policy, live, requirePolicy, compatibility);
        appendOwnerProviderWarnings(result, policy);
        return Tools.ok(result);
    }

    @SuppressWarnings("unchecked")
    static void appendOwnerProviderWarnings(Map<String, Object> result, ProjectPolicy policy) {
        if (policy == null || !policy.loaded() || policy.version() < 2
                || providerCount(policy) == 0) {
            return;
        }
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("warnings");
        try {
            appendOwnerProviderWarnings(result, policy, new ProviderConfigurationStore().load());
            return;
        } catch (ProviderFailure failure) {
            warnings.add(new PolicyIssue("warning", "provider_configuration_unavailable",
                    "external_terms.providers",
                    "Owner terminology registry settings could not be read (" + failure.code()
                            + "). Review Preferences ▸ MCP ▸ Externals.").toJson());
        }
        capPublicIssues(errors, warnings);
    }

    @SuppressWarnings("unchecked")
    static void appendOwnerProviderWarnings(Map<String, Object> result, ProjectPolicy policy,
            io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig owner) {
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("warnings");
        ProviderPolicyBindings.warnings(policy, owner).stream()
                .map(PolicyIssue::toJson).forEach(warnings::add);
        capPublicIssues(errors, warnings);
    }

    private static int providerCount(ProjectPolicy policy) {
        Object external = policy.effective().get("external_terms");
        if (!(external instanceof Map<?, ?> map)) return 0;
        Object providers = map.get("providers");
        return providers instanceof List<?> list ? list.size() : 0;
    }

    static PolicyContext capture(org.protege.editor.owl.model.OWLModelManager mm) {
        OWLOntology active = mm.getActiveOntology();
        String ontologyIri = active.getOntologyID().getOntologyIRI().orNull() == null ? null
                : active.getOntologyID().getOntologyIRI().orNull().toString();
        File document = SidecarPaths.toFile(mm.getOWLOntologyManager().getOntologyDocumentIRI(active));
        List<String> reasoners = new ArrayList<>();
        String selectedReasoner = selectedReasoner(mm);
        try {
            for (var info : mm.getOWLReasonerManager().getInstalledReasonerFactories()) {
                if (info.getReasonerName() != null) {
                    reasoners.add(info.getReasonerName());
                }
            }
        } catch (RuntimeException unavailableInHeadlessAdapter) {
            // The live adapter supplies the registry. A partial/headless adapter has no installed plugin
            // registry; required-reasoner policy then correctly validates as unavailable.
        }
        Collections.sort(reasoners, String.CASE_INSENSITIVE_ORDER);
        return new PolicyContext(document == null ? null : document.toPath(), ontologyIri,
                reasoners, selectedReasoner);
    }

    static String selectedReasoner(org.protege.editor.owl.model.OWLModelManager mm) {
        try {
            String selectedId = mm.getOWLReasonerManager().getCurrentReasonerFactoryId();
            if (selectedId != null) {
                for (var info : mm.getOWLReasonerManager().getInstalledReasonerFactories()) {
                    if (selectedId.equals(info.getReasonerId())) {
                        return info.getReasonerName();
                    }
                }
            }
            return mm.getOWLReasonerManager().getCurrentReasonerName();
        } catch (RuntimeException unavailableInHeadlessAdapter) {
            return null;
        }
    }

    static Map<String, Object> toJson(ProjectPolicy policy, PolicyContext live,
            boolean requirePolicy, boolean unrestrictedNoPolicyPaths) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("policy_loaded", policy.loaded());
        json.put("valid", policy.valid());
        json.put("discovery", policy.discovery());
        json.put("path_mode", policy.loaded() ? "policy_confined"
                : unrestrictedNoPolicyPaths ? "legacy_local_admin_unrestricted" : "policy_required");
        json.put("active_ontology_iri", live.activeOntologyIri);
        if (live.documentPath != null) {
            json.put("document_path", live.documentPath.toString());
        }
        if (policy.path() != null) {
            json.put("policy_path", policy.path().toString());
        }
        if (policy.projectRoot() != null) {
            json.put("project_root", policy.projectRoot().toString());
        }
        if (policy.digest() != null) {
            json.put("policy_digest", policy.digest());
        }
        if (!policy.effective().isEmpty()) {
            if (policy.version() != 0) {
                json.put("schema_version", policy.version());
            }
            json.put("policy", policy.effective());
        }
        if (policy.migration() != null) {
            json.put("migration", policy.migration().toJson());
        }
        Map<String, List<String>> assetJson = new LinkedHashMap<>();
        policy.assets().forEach((key, paths) -> assetJson.put(key,
                paths.stream().map(Path::toString).toList()));
        json.put("resolved_assets", assetJson);

        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (requirePolicy && !policy.loaded()) {
            errors.add(new PolicyIssue("error", "policy_not_found", null,
                    "No project policy was discovered; pass policy_path or add "
                            + ProjectPolicyLoader.DEFAULT_RELATIVE_PATH + ".").toJson());
            json.put("valid", false);
        }
        if (policy.loaded() && live.activeOntologyIri == null) {
            errors.add(new PolicyIssue("error", "active_ontology_anonymous", "root_ontology",
                    "The active ontology has no ontology IRI, so policy root_ontology cannot be verified.")
                    .toJson());
            json.put("valid", false);
        }
        for (PolicyIssue issue : policy.issues()) {
            ("error".equals(issue.severity()) ? errors : warnings).add(issue.toJson());
        }
        capPublicIssues(errors, warnings);
        json.put("errors", errors);
        json.put("warnings", warnings);
        return json;
    }

    private static void capPublicIssues(List<Map<String, Object>> errors,
            List<Map<String, Object>> warnings) {
        if (errors.size() + warnings.size() <= ProjectPolicyLoader.MAX_POLICY_ISSUES) return;
        int retained = ProjectPolicyLoader.MAX_POLICY_ISSUES - 1;
        while (errors.size() > retained) errors.remove(errors.size() - 1);
        while (errors.size() + warnings.size() > retained) warnings.remove(warnings.size() - 1);
        errors.add(new PolicyIssue("error", "policy_issues_truncated", null,
                "Policy validation produced more than " + ProjectPolicyLoader.MAX_POLICY_ISSUES
                        + " issues; remaining issues were omitted.").toJson());
    }

    private static Map<String, Object> invalidPathResult(String configured, String message) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("policy_loaded", true);
        json.put("valid", false);
        json.put("discovery", "explicit");
        json.put("path_mode", "policy_confined");
        json.put("resolved_assets", Collections.emptyMap());
        json.put("errors", List.of(new PolicyIssue("error", "policy_path_invalid", "policy_path",
                "Invalid policy_path '" + configured + "': " + message).toJson()));
        json.put("warnings", Collections.emptyList());
        return json;
    }

    static record PolicyContext(Path documentPath, String activeOntologyIri,
            List<String> installedReasoners, String selectedReasoner) { }
}
