package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
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
