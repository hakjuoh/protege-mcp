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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Policy discovery and validation tools; filesystem work always runs off the Protégé model thread. */
public final class ProjectPolicyTools {

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
    }

    /**
     * Generate a commented, schema-valid starter {@code .protege-mcp/project.yaml} for review and
     * commit. This scaffolds a NEW policy file (it never mutates the ontology or an existing policy in
     * place); the write is authorized like every other file-writing tool — read-only mode, the
     * confirm-write gate, the {@code filesystem:project:write} capability, and canonical containment
     * under {@code project_root} (or the local-admin no-policy compatibility path). The generated
     * template names two files the user must still create (the {@code root_artifact} and the RO-Crate
     * metadata), so it is honest about not being valid on its own: it returns a {@code validation_hint}
     * and never claims {@code valid=true}.
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

        String rootOntology = live.activeOntologyIri();
        String projectId = configuredProjectId != null && !configuredProjectId.isBlank()
                ? configuredProjectId.trim()
                : ProjectPolicyTemplate.deriveProjectId(rootOntology);
        ProjectPolicyTemplate.Template template =
                ProjectPolicyTemplate.render(profile, projectId, rootOntology);
        byte[] bytes = template.yaml().getBytes(StandardCharsets.UTF_8);

        try {
            atomicWrite(target, bytes, overwrite);
        } catch (FileAlreadyExistsException exists) {
            return Tools.json()
                    .put("written", false)
                    .put("error_code", "policy_exists")
                    .put("path", target.toString())
                    .put("note", "A file already exists here; pass overwrite=true to replace it.")
                    .result();
        } catch (IOException e) {
            return Tools.error("Could not write project policy template: " + e.getMessage());
        }

        return Tools.json()
                .put("written", true)
                .put("path", target.toString())
                .put("project_id", projectId)
                .put("profile", profile)
                .put("bytes", bytes.length)
                .put("sha256", sha256(bytes))
                .put("validation_hint", ProjectPolicyTemplate.validationHint(template))
                .put("note", "Review and commit this like source code; it is not valid until you "
                        + "complete the items in validation_hint.")
                .result();
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
    private static void atomicWrite(Path target, byte[] bytes, boolean overwrite) throws IOException {
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
        return Tools.ok(toJson(policy, live, requirePolicy, compatibility));
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

    private static Map<String, Object> toJson(ProjectPolicy policy, PolicyContext live,
            boolean requirePolicy, boolean unrestrictedNoPolicyPaths) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("policy_loaded", policy.loaded());
        json.put("valid", policy.valid());
        json.put("discovery", policy.discovery());
        json.put("path_mode", policy.loaded() ? "policy_confined"
                : unrestrictedNoPolicyPaths ? "legacy_local_admin_unrestricted" : "policy_required");
        json.put("active_ontology_iri", live.activeOntologyIri);
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
            Object version = policy.effective().get("version");
            json.put("schema_version", version);
            json.put("policy", policy.effective());
        }
        Map<String, List<String>> assetJson = new LinkedHashMap<>();
        policy.assets().forEach((key, paths) -> assetJson.put(key,
                paths.stream().map(Path::toString).toList()));
        json.put("resolved_assets", assetJson);

        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        for (PolicyIssue issue : policy.issues()) {
            ("error".equals(issue.severity()) ? errors : warnings).add(issue.toJson());
        }
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
        json.put("errors", errors);
        json.put("warnings", warnings);
        return json;
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
