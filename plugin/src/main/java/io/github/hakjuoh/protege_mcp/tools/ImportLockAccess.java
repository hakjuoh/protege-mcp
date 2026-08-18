package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.server.McpSyncServerExchange;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves and authorizes the request-scoped paths shared by lock capture and verification. */
final class ImportLockAccess {
    final DirectAccessPolicy.Rules rules;
    final Path explicitTarget;
    final String reportedPath;
    final Map<String, Object> arguments;

    private ImportLockAccess(
            DirectAccessPolicy.Rules rules,
            Path explicitTarget,
            String reportedPath,
            Map<String, Object> arguments) {
        this.rules = rules;
        this.explicitTarget = explicitTarget;
        this.reportedPath = reportedPath;
        this.arguments = arguments;
    }

    static ImportLockAccess open(
            ToolContext ctx,
            McpSyncServerExchange exchange,
            Map<String, Object> arguments,
            boolean write) {
        String configuredPolicy = Tools.optString(arguments, "policy_path");
        String configuredPath = Tools.optString(arguments, "path");
        DirectAccessPolicy.Rules rules;
        ProjectPolicy discovered;
        try {
            rules = DirectAccessPolicy.resolve(ctx, exchange);
            discovered = rules.policy();
        } catch (ToolArgException refusal) {
            // Only an explicit lock path may bootstrap a loaded-but-invalid discovered policy.
            if (configuredPath == null || configuredPolicy != null) {
                throw refusal;
            }
            rules = null;
            discovered = DirectAccessPolicy.verifiedInvalidDiscovery(ctx, exchange, refusal);
        }

        boolean bootstrap =
                configuredPath != null
                        && configuredPolicy == null
                        && discovered.loaded()
                        && !discovered.valid();
        Path explicitTarget = null;
        String reportedPath = null;
        if (configuredPath != null) {
            explicitTarget =
                    bootstrap
                            ? DirectAccessPolicy.bootstrapExplicitPath(
                                    discovered, exchange, configuredPath, write, "lock")
                            : write
                                    ? rules.writePath(configuredPath)
                                    : rules.readPath(configuredPath);
            Path raw = Path.of(configuredPath);
            reportedPath =
                    raw.isAbsolute()
                            ? raw.toAbsolutePath().normalize().toString()
                            : explicitTarget.toString();
        }
        return new ImportLockAccess(
                rules,
                explicitTarget,
                reportedPath,
                rules == null ? arguments : authorizePolicyArgument(arguments, rules));
    }

    /** Authorizes a policy reference without taking ownership of its validation diagnostics. */
    private static Map<String, Object> authorizePolicyArgument(
            Map<String, Object> arguments, DirectAccessPolicy.Rules rules) {
        String configured = Tools.optString(arguments, "policy_path");
        if (configured == null) {
            return arguments;
        }
        try {
            Path.of(configured);
        } catch (InvalidPathException e) {
            return arguments;
        }
        Path authorized = rules.readPath(configured);
        Map<String, Object> rewritten = new LinkedHashMap<>(arguments);
        rewritten.put("policy_path", authorized.toString());
        return rewritten;
    }
}
