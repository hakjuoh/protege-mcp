package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.modelcontextprotocol.server.McpSyncServerExchange;

/**
 * Request-scoped authorization for legacy caller-selected paths and document URLs.
 *
 * <p>The principal is read directly from the MCP exchange; it is never copied into a global or a
 * {@link ThreadLocal}. A null exchange is used only by the headless unit-test seams and retains the
 * historic local-admin test profile. A real exchange with no propagated principal fails closed.
 *
 * <p>When a project policy is loaded, relative direct paths are rooted at the policy's canonical
 * {@code project_root}. Existing path components are resolved through real paths before containment
 * is decided, so a symlink cannot make a lexically in-project path escape. For a new write target the
 * nearest existing ancestor is resolved and the remaining suffix is appended to that real ancestor;
 * callers receive that pinned path rather than reopening the original symlink alias.
 */
final class DirectAccessPolicy {

    static final String PROJECT_READ = "filesystem:project:read";
    static final String PROJECT_WRITE = "filesystem:project:write";
    static final String EXTERNAL = "filesystem:external";
    static final String NETWORK = "network:access";
    static final String LOCAL_ADMIN = "local:admin";

    /**
     * The single most-authoritative source of a network denial, carried on every composed
     * {@link NetworkRule} so blockers and catalog gates attribute a refusal to its actual cause
     * (ADR 0005 decision 3). Administrative sources outrank the request argument: a caller who also
     * passed {@code network=deny} would still be denied after removing it, so the denial names the
     * constraint that actually holds. {@code REQUEST} is stored only when the base posture was
     * unconditionally open (allowed with no host allowlist): over an allowlisted-allow base the
     * policy already confines the request-independent posture, so the composed denial carries
     * {@code ALLOWED_HOSTS} and the catalog/blocker attribution keeps naming the reviewed policy.
     */
    enum DenialSource {
        /** The rule allows network access (a per-host allowlist may still deny individual URIs). */
        NONE,
        /** The caller's own {@code network=deny} request argument over an unconditionally open base. */
        REQUEST,
        /** The effective policy's {@code network.default} / {@code imports.network} deny. */
        POLICY,
        /**
         * The policy's host allowlist confines the posture. Stored when a request-level deny is
         * composed onto an allowed-but-allowlisted base; blockers also use the same attribution
         * when a specific host is refused under an otherwise-allowed rule.
         */
        ALLOWED_HOSTS,
        /** The authenticated principal lacks {@code network:access}. */
        CAPABILITY,
        /** The loaded project policy is invalid; network authorization fails closed. */
        INVALID_POLICY,
        /** No policy is loaded and the local-admin compatibility profile/preference is not active. */
        COMPATIBILITY_PREFERENCE
    }

    private DirectAccessPolicy() {
    }

    static void requireCapability(McpSyncServerExchange exchange, String capability) {
        AuthenticatedPrincipal principal = principal(exchange);
        if (principal == null || (!principal.capabilities().contains(capability)
                && !principal.capabilities().contains(LOCAL_ADMIN))) {
            throw new ToolArgException("Operation requires capability " + capability + ".");
        }
    }

    static Rules resolve(ToolContext ctx, McpSyncServerExchange exchange) {
        return resolve(ctx, exchange, null);
    }

    static Rules resolve(ToolContext ctx, McpSyncServerExchange exchange,
            String configuredPolicyPath) {
        AuthenticatedPrincipal principal = principal(exchange);
        if (principal == null || (!principal.capabilities().contains(PROJECT_READ)
                && !principal.capabilities().contains(LOCAL_ADMIN))) {
            throw new ToolArgException("Resolving project filesystem/network policy requires capability "
                    + PROJECT_READ + ".");
        }
        boolean compatibility = ctx.controller() == null
                || ctx.controller().isUnrestrictedNoPolicyPathsAllowed();
        // Never let a caller turn an arbitrary local directory into "the project" merely by naming
        // a policy file there. Authorize the explicit policy reference against the currently discovered
        // project first; with no discovered policy, only the documented local-admin compatibility mode
        // can bootstrap a caller-selected policy path.
        RevisionTools.PolicyState baseline = RevisionTools.resolvePolicy(ctx, null);
        RevisionTools.PolicyState state = baseline;
        Path authorizedPolicyPath = null;
        if (configuredPolicyPath != null) {
            try {
                Path.of(configuredPolicyPath);
            } catch (InvalidPathException invalid) {
                // A syntactically invalid policy_path points nowhere to authorize. Return the discovered
                // baseline Rules WITHOUT rewriting the argument, so the tool body reaches its structured
                // policy_path_invalid diagnostic (the released 0.6.0 contract for get_model_revision /
                // run_project_qc / validate/get_project_policy / preview_change_set / run_qc_suite)
                // instead of aborting with a bare gate error from readPath's ToolArgException.
                return new Rules(baseline.policy(), principal, compatibility, null);
            }
            if (baseline.error() != null) {
                throw new ToolArgException(baseline.error());
            }
            ProjectPolicy discovered = baseline.policy();
            Path diagnosticAnchor = null;
            if (discovered.loaded() && !discovered.valid()) {
                // Diagnostic recovery anchor: the discovered project root when it resolved, otherwise the
                // conventional project directory reconstructed from the discovered policy file. This
                // matters when the policy is invalid PRECISELY because its project_root escapes/is
                // missing (projectRoot()==null): a user still needs to pass policy_path to
                // get_project_policy/validate_project_policy/run_project_qc to see the structured
                // diagnostics for the very file they are fixing. Relative policy_path values (e.g. the
                // conventional .protege-mcp/project.yaml) are rooted at project_root, so the anchor must
                // be that project directory — anchoring at the policy file's own folder would double the
                // .protege-mcp segment and miss the discovered file.
                diagnosticAnchor = discovered.projectRoot() != null ? discovered.projectRoot()
                        : conventionalProjectRoot(discovered.path());
            }
            if (diagnosticAnchor != null) {
                // readPath would fail closed on the invalid discovered policy, blocking that diagnostic
                // call. Authorize the explicit path by canonical containment under the anchor WITHOUT
                // trusting the invalid policy to widen access (no allow_external_paths), then let the
                // tool body return structured diagnostics.
                authorizedPolicyPath = containedPolicyPath(diagnosticAnchor, configuredPolicyPath);
            } else {
                authorizedPolicyPath = new Rules(discovered, principal, compatibility)
                        .readPath(configuredPolicyPath);
            }
            state = RevisionTools.resolvePolicy(ctx, authorizedPolicyPath.toString());
        }
        if (state.error() != null) {
            throw new ToolArgException(state.error());
        }
        // A loaded-but-invalid policy is deliberately NOT rejected here: the diagnostic tools
        // (get/validate_project_policy, run_project_qc, get_model_revision, preview_change_set)
        // must return their structured invalid-policy results. Every filesystem/network
        // authorization on the returned Rules re-checks validity and fails closed at use time.
        return new Rules(state.policy(), principal, compatibility, authorizedPolicyPath);
    }

    /**
     * The conventional project directory for a discovered policy file whose declared {@code project_root}
     * is unresolvable: the ancestor reached by stripping {@link ProjectPolicyLoader#DEFAULT_RELATIVE_PATH}
     * (discovery only ever finds a policy at {@code <root>/.protege-mcp/project.yaml}). Relative
     * policy_path values resolve against this root; falls back to the file's own directory if the layout
     * is unexpected, and null when no path is known.
     */
    private static Path conventionalProjectRoot(Path policyFile) {
        if (policyFile == null) {
            return null;
        }
        Path relative = Path.of(io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader
                .DEFAULT_RELATIVE_PATH);
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
     * Authorize an explicit {@code policy_path} by canonical containment under an already-discovered
     * project root while that discovered policy is loaded-but-invalid. The invalid policy is trusted
     * only for its root (never to widen access), so a candidate-fix file inside the project can be
     * read for diagnostics; an outside path is refused. The caller already required PROJECT_READ.
     */
    private static Path containedPolicyPath(Path projectRoot, String configured) {
        if (configured == null || configured.isBlank()) {
            throw new ToolArgException("Filesystem path is required.");
        }
        final Path raw;
        try {
            raw = Path.of(configured);
        } catch (InvalidPathException e) {
            throw new ToolArgException("Invalid filesystem path '" + configured + "': " + e.getMessage());
        }
        Path root = canonicalCandidate(projectRoot.toAbsolutePath().normalize());
        Path candidate = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
        Path canonical = canonicalCandidate(candidate);
        if (!canonical.startsWith(root)) {
            throw new ToolArgException("policy_path is outside project_root: " + canonical
                    + " (the discovered project policy is invalid, so only paths inside the project "
                    + "root can be authorized for diagnostics).");
        }
        return canonical;
    }

    private static AuthenticatedPrincipal principal(McpSyncServerExchange exchange) {
        if (exchange == null) {
            // Direct method-level tests predate transport contexts. Production MCP calls always have
            // a non-null exchange; an empty production context therefore does NOT inherit this profile.
            return AuthenticatedPrincipal.staticAdmin();
        }
        Object value = exchange.transportContext() == null ? null
                : exchange.transportContext().get(AuthenticatedPrincipal.CONTEXT_KEY);
        return value instanceof AuthenticatedPrincipal ? (AuthenticatedPrincipal) value : null;
    }

    /**
     * Parse the optional request-level {@code network} argument. Strict by contract: only
     * {@code deny} and {@code allow} (case-insensitively) are accepted; an absent/blank argument is
     * the released behavior. Returns true exactly when the request denies network access.
     */
    static boolean requestNetworkDenies(String requested) {
        if (requested == null || requested.trim().isEmpty()) {
            return false;
        }
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        if ("deny".equals(normalized)) {
            return true;
        }
        if ("allow".equals(normalized)) {
            // ADR 0005 decision 3: allow merely abstains from denying. It never overrides a policy
            // deny, an invalid-policy fail-closed posture, a missing capability, or a restricted
            // no-policy state; under an unrestricted no-policy profile it is a no-op affirmation.
            return false;
        }
        throw new ToolArgException("Invalid network '" + requested + "'; expected deny or allow.");
    }

    static final class Rules {
        private final ProjectPolicy policy;
        private final AuthenticatedPrincipal principal;
        private final boolean unrestrictedNoPolicyPaths;
        private final Path authorizedPolicyPath;
        private final boolean requestNetworkDeny;

        Rules(ProjectPolicy policy, AuthenticatedPrincipal principal) {
            this(policy, principal, true);
        }

        Rules(ProjectPolicy policy, AuthenticatedPrincipal principal,
                boolean unrestrictedNoPolicyPaths) {
            this(policy, principal, unrestrictedNoPolicyPaths, null);
        }

        private Rules(ProjectPolicy policy, AuthenticatedPrincipal principal,
                boolean unrestrictedNoPolicyPaths, Path authorizedPolicyPath) {
            this(policy, principal, unrestrictedNoPolicyPaths, authorizedPolicyPath, false);
        }

        private Rules(ProjectPolicy policy, AuthenticatedPrincipal principal,
                boolean unrestrictedNoPolicyPaths, Path authorizedPolicyPath,
                boolean requestNetworkDeny) {
            this.policy = policy;
            this.principal = principal;
            this.unrestrictedNoPolicyPaths = unrestrictedNoPolicyPaths;
            this.authorizedPolicyPath = authorizedPolicyPath;
            this.requestNetworkDeny = requestNetworkDeny;
        }

        /**
         * Compose the optional request-level {@code network=deny|allow} argument into these rules
         * (ADR 0005 decision 3, most-restrictive-wins). {@code deny} denies every network fetch this
         * request would make; {@code allow} (and an absent argument) changes nothing.
         */
        Rules withRequestNetwork(String requested) {
            if (!requestNetworkDenies(requested)) {
                return this;
            }
            return new Rules(policy, principal, unrestrictedNoPolicyPaths, authorizedPolicyPath, true);
        }

        ProjectPolicy policy() {
            return policy;
        }

        /**
         * Whether this policy confines the filesystem to the project — a valid loaded policy with a
         * canonical project_root and {@code allow_external_paths} not enabled. When true, out-of-project
         * reads are refused (independent of the network posture), so the folder-catalog gate must run
         * even when the network is open.
         */
        boolean confinesFilesystem() {
            return policy.loaded() && policy.valid() && policy.projectRoot() != null
                    && !Boolean.TRUE.equals(object(policy.effective(), "filesystem")
                            .get("allow_external_paths"));
        }

        /** Replace a caller's policy_path with the exact canonical path that was authorized. */
        Map<String, Object> authorizedPolicyArguments(Map<String, Object> arguments) {
            if (authorizedPolicyPath == null || arguments == null
                    || !arguments.containsKey("policy_path")) {
                return arguments;
            }
            Map<String, Object> rewritten = new LinkedHashMap<>(arguments);
            rewritten.put("policy_path", authorizedPolicyPath.toString());
            return rewritten;
        }

        Path readPath(String configured) {
            return path(configured, false, false);
        }

        Path writePath(String configured) {
            return path(configured, true, false);
        }

        /**
         * Authorize an implicit path already derived from the open document (a save target, catalog,
         * or CQ sidecar). Derived targets confer no caller-selected authority, so with no policy they
         * are exempt from the local-admin compatibility opt-in; policy containment still applies.
         */
        Path implicitPath(Path path, boolean write) {
            if (path == null) {
                throw new ToolArgException("No local filesystem path is available for this operation.");
            }
            return path(path.toString(), write, true);
        }

        Source authorizeSource(String configured) {
            if (configured == null || configured.isBlank()) {
                throw new ToolArgException("Document source is required.");
            }
            requireValidPolicy();
            String normalized = OntologyDocumentTools.normalizeSource(configured);
            File local = OntologyDocumentTools.localFile(normalized);
            if (local != null) {
                return new Source(readPath(local.toPath().toString()).toString(), false);
            }
            URI uri = uri(normalized);
            if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                try {
                    return new Source(readPath(Path.of(uri).toString()).toString(), false);
                } catch (IllegalArgumentException e) {
                    throw new ToolArgException("Invalid local file document IRI: " + configured);
                }
            }
            if (uri != null && "jar".equalsIgnoreCase(uri.getScheme())) {
                // jar:file: would turn network authority into arbitrary local-file authority, while
                // jar:https: would hide the real redirect/host boundary inside a nested URI. Treat
                // archive extraction as an explicit project-filesystem operation instead.
                throw new ToolArgException("Nested jar: document sources are not supported by the "
                        + "direct-access policy; extract the ontology under project_root first.");
            }
            if (uri != null && isNetworkScheme(uri.getScheme())) {
                authorizeNetwork(uri, false);
                return new Source(normalized, true);
            }
            // A bare/nonexistent path is still a filesystem request. Absolute non-network IRIs such
            // as urn: remain document IRIs and will be rejected by OWLAPI if no factory supports them.
            if (uri == null || uri.getScheme() == null || looksLikeWindowsPath(normalized)) {
                return new Source(readPath(normalized).toString(), false);
            }
            return new Source(normalized, false);
        }

        /** Whether import resolution may dereference a remote document. */
        NetworkRule importNetworkRule() {
            return networkRule(true);
        }

        /** Authorize one remote URI against the effective default/import mode and host allowlist. */
        void authorizeNetwork(URI uri, boolean importFetch) {
            requireValidPolicy();
            if (uri != null && "jar".equalsIgnoreCase(uri.getScheme())) {
                throw new ToolArgException("Nested jar: network locations are not supported by the "
                        + "direct-access policy.");
            }
            NetworkRule rule = networkRule(importFetch);
            if (!rule.capabilityAllowed()) {
                throw new ToolArgException("Network document access requires capability " + NETWORK + ".");
            }
            if (!rule.allowed()) {
                if (rule.denialSource() == DenialSource.REQUEST) {
                    // The explicit error PLAN §8.4 requires for the root document: the caller's own
                    // request denies the fetch, never a silent partial load — and never a message
                    // that tells the caller to loosen the reviewed policy.
                    throw new ToolArgException("Network access is denied by the request argument "
                            + "network=deny.");
                }
                if (rule.denialSource() == DenialSource.ALLOWED_HOSTS) {
                    // A request deny composed over an allowlisted-allow policy: keep the released
                    // per-host verdict for a host the allowlist would refuse anyway; a host inside
                    // the allowlist is denied only by the caller's own request.
                    String host = normalizedHost(uri);
                    if (host == null || !rule.allowedHosts().contains(host)) {
                        throw new ToolArgException("Network host '" + (host == null ? "(none)" : host)
                                + "' is not present in network.allowed_hosts.");
                    }
                    throw new ToolArgException("Network access is denied by the request argument "
                            + "network=deny.");
                }
                if (!policy.loaded()) {
                    // No policy exists, so there is no network.default/imports.network to blame. The
                    // no-policy fallback allows a remote document only for a local-admin principal with
                    // the compatibility preference enabled; the denial can be either cause, so attribute
                    // it to both requirements rather than asserting which one is missing.
                    throw new ToolArgException("Network document access is disabled while no project "
                            + "policy is loaded: it requires both the local-admin profile and the "
                            + "'Allow unrestricted local-admin paths when no project policy is loaded' "
                            + "compatibility preference (Protégé ▸ Preferences ▸ MCP). Load a project "
                            + "policy that permits this host instead.");
                }
                throw new ToolArgException("Network access is denied by the effective project policy"
                        + (importFetch ? " (imports.network=deny)." : " (network.default=deny)."));
            }
            String host = normalizedHost(uri);
            if (!rule.allowedHosts().isEmpty()
                    && (host == null || !rule.allowedHosts().contains(host))) {
                throw new ToolArgException("Network host '" + (host == null ? "(none)" : host)
                        + "' is not present in network.allowed_hosts.");
            }
        }

        private NetworkRule networkRule(boolean importFetch) {
            NetworkRule base = baseNetworkRule(importFetch);
            if (!requestNetworkDeny || !base.allowed()) {
                // Either no request-level deny, or an administrative source already denies — the
                // administrative denial is the one that holds even without the request argument, so
                // its attribution stands (most-authoritative denial, ADR 0005 decision 3).
                return base;
            }
            // REQUEST is attributed only when the base posture was unconditionally open. Over an
            // allowlisted-allow base the policy already restricted the posture (the catalog
            // presence gate was engaged with or without the request), so the composed denial keeps
            // the allowlist attribution — advice to drop the request argument would be wrong there.
            // Redirect coupling deliberately unchanged: a non-empty allowlist already disabled
            // redirects, and with the rule denied no fetch happens either way.
            return new NetworkRule(false, base.allowedHosts(), base.followRedirects(),
                    base.capabilityAllowed(), this,
                    base.allowedHosts().isEmpty() ? DenialSource.REQUEST
                            : DenialSource.ALLOWED_HOSTS);
        }

        private NetworkRule baseNetworkRule(boolean importFetch) {
            boolean capabilityAllowed = has(NETWORK);
            if (policy.loaded() && !policy.valid()) {
                // Import blockers consult this rule non-exceptionally; fail closed without throwing.
                // The denial is the invalid policy itself — capabilityAllowed=false only mirrors the
                // released fail-closed shape and must not be attributed as a missing capability.
                return new NetworkRule(false, Collections.emptySet(), false, false, this,
                        DenialSource.INVALID_POLICY);
            }
            if (!policy.loaded()) {
                boolean compatibility = unrestrictedNoPolicyPaths && has(LOCAL_ADMIN);
                boolean allowed = compatibility && capabilityAllowed;
                return new NetworkRule(allowed, Collections.emptySet(), true, capabilityAllowed, this,
                        allowed ? DenialSource.NONE
                                : !capabilityAllowed ? DenialSource.CAPABILITY
                                : DenialSource.COMPATIBILITY_PREFERENCE);
            }
            Map<String, Object> network = object(policy.effective(), "network");
            Map<String, Object> imports = object(policy.effective(), "imports");
            String mode = importFetch ? string(imports, "network") : string(network, "default");
            boolean allowed = "allow".equalsIgnoreCase(mode);
            Set<String> hosts = new LinkedHashSet<>();
            for (String host : strings(network.get("allowed_hosts"))) {
                hosts.add(stripTrailingDot(host.toLowerCase(Locale.ROOT)));
            }
            boolean effectiveAllowed = allowed && capabilityAllowed;
            // OWLAPI does not expose a redirect target callback. When a host allowlist is active,
            // following redirects could leave the allowlist, so callers must disable redirects.
            return new NetworkRule(effectiveAllowed,
                    Collections.unmodifiableSet(hosts), hosts.isEmpty(), capabilityAllowed, this,
                    effectiveAllowed ? DenialSource.NONE
                            : !capabilityAllowed ? DenialSource.CAPABILITY : DenialSource.POLICY);
        }

        private Path path(String configured, boolean write, boolean derivedFromOpenDocument) {
            if (configured == null || configured.isBlank()) {
                throw new ToolArgException("Filesystem path is required.");
            }
            require(write ? PROJECT_WRITE : PROJECT_READ,
                    (write ? "Filesystem writes require capability " : "Filesystem reads require capability ")
                            + (write ? PROJECT_WRITE : PROJECT_READ) + ".");
            requireValidPolicy();
            final Path raw;
            try {
                raw = Path.of(configured);
            } catch (InvalidPathException e) {
                throw new ToolArgException("Invalid filesystem path '" + configured + "': "
                        + e.getMessage());
            }

            if (!policy.loaded()) {
                if (!derivedFromOpenDocument) {
                    if (!unrestrictedNoPolicyPaths) {
                        throw new ToolArgException("Caller-selected filesystem paths are disabled "
                                + "until a project policy is loaded (Preferences ▸ MCP compatibility "
                                + "setting).");
                    }
                    require(LOCAL_ADMIN, "With no project policy, caller-selected filesystem paths "
                            + "are retained only for the local-admin compatibility profile.");
                }
                return canonicalCandidate(raw.toAbsolutePath().normalize());
            }

            Path root = policy.projectRoot();
            if (root == null) {
                throw new ToolArgException("The effective project policy has no canonical project_root.");
            }
            Path candidate = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
            Path canonical = canonicalCandidate(candidate);
            if (canonical.startsWith(root)) {
                return canonical;
            }
            boolean allowExternal = Boolean.TRUE.equals(
                    object(policy.effective(), "filesystem").get("allow_external_paths"));
            if (!allowExternal) {
                throw new ToolArgException("Path is outside project_root and "
                        + "filesystem.allow_external_paths is false: " + canonical);
            }
            require(EXTERNAL, "Outside-project paths require capability " + EXTERNAL + ".");
            return canonical;
        }

        private void require(String capability, String message) {
            if (!has(capability)) {
                throw new ToolArgException(message);
            }
        }

        /** Use-time counterpart of the former resolve()-time rejection: same message, same closure. */
        private void requireValidPolicy() {
            if (!policy.loaded() || policy.valid()) {
                return;
            }
            String codes = policy.issues().stream()
                    .filter(issue -> "error".equals(issue.severity()))
                    .map(issue -> issue.code()).distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new ToolArgException("The effective project policy is invalid (" + codes
                    + "); direct filesystem/network access is refused until it validates.");
        }

        private boolean has(String capability) {
            return principal != null && (principal.capabilities().contains(capability)
                    || principal.capabilities().contains(LOCAL_ADMIN));
        }
    }

    record Source(String value, boolean network) { }

    record NetworkRule(boolean allowed, Set<String> allowedHosts, boolean followRedirects,
            boolean capabilityAllowed, Rules filesystemRules, DenialSource denialSource) {
        NetworkRule(boolean allowed, Set<String> allowedHosts, boolean followRedirects,
                boolean capabilityAllowed) {
            this(allowed, allowedHosts, followRedirects, capabilityAllowed, null);
        }

        NetworkRule(boolean allowed, Set<String> allowedHosts, boolean followRedirects,
                boolean capabilityAllowed, Rules filesystemRules) {
            // Compatibility constructors predate the denial-source discriminator; derive the source
            // exactly the way the released blocker attribution did (capability first, then policy),
            // so legacy-constructed denied rules keep their released attribution strings.
            this(allowed, allowedHosts, followRedirects, capabilityAllowed, filesystemRules,
                    allowed ? DenialSource.NONE
                            : !capabilityAllowed ? DenialSource.CAPABILITY : DenialSource.POLICY);
        }

        boolean permits(URI uri) {
            if (!allowed || uri == null || "jar".equalsIgnoreCase(uri.getScheme())
                    || !isNetworkScheme(uri.getScheme())) {
                return false;
            }
            String host = normalizedHost(uri);
            return allowedHosts.isEmpty() || (host != null && allowedHosts.contains(host));
        }

        /** Null means allowed; otherwise returns the exact project-filesystem denial. */
        String fileImportDenial(URI uri) {
            if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) return null;
            try {
                authorizeFileImport(uri);
                return null;
            } catch (IllegalArgumentException | ToolArgException e) {
                return e.getMessage() == null ? "the file import is not authorized" : e.getMessage();
            }
        }

        Path authorizeFileImport(URI uri) {
            Path requested = Path.of(uri);
            // Compatibility-only overloads used by legacy reflection tests predate request rules.
            return filesystemRules == null ? requested.toAbsolutePath().normalize()
                    : filesystemRules.readPath(requested.toString());
        }
    }

    /** Resolve every existing prefix without following a not-yet-created suffix. */
    static Path canonicalCandidate(Path absolute) {
        Path current = absolute;
        java.util.ArrayDeque<Path> suffix = new java.util.ArrayDeque<>();
        while (current != null && !Files.exists(current)) {
            Path name = current.getFileName();
            if (name != null) {
                suffix.push(name);
            }
            current = current.getParent();
        }
        if (current == null) {
            return absolute;
        }
        try {
            Path resolved = current.toRealPath();
            while (!suffix.isEmpty()) {
                resolved = resolved.resolve(suffix.pop());
            }
            return resolved.normalize();
        } catch (IOException e) {
            throw new ToolArgException("Could not resolve filesystem path '" + absolute + "': "
                    + e.getMessage());
        }
    }

    /**
     * Re-derive the loaded-but-invalid discovered policy behind a resolve()-time refusal, or
     * rethrow. Shared by the explicit-path bootstrap surfaces (the import-lock tools and
     * save_ontology policy_bootstrap): a capability or policy-resolution refusal is never
     * downgraded into a bootstrap.
     */
    static ProjectPolicy verifiedInvalidDiscovery(ToolContext ctx, McpSyncServerExchange exchange,
            ToolArgException refusal) {
        requireCapability(exchange, PROJECT_READ);
        RevisionTools.PolicyState state = RevisionTools.resolvePolicy(ctx, null);
        if (state.error() != null || !state.policy().loaded() || state.policy().valid()) {
            throw refusal;
        }
        return state.policy();
    }

    /**
     * Containment-only authorization for an explicit-path bootstrap while the discovered policy is
     * loaded but invalid, so a policy-referenced artifact (the declared lockfile, the root
     * artifact) can be created at all. Capability and canonical project_root containment are still
     * enforced; policy-granted widening ({@code filesystem.allow_external_paths}) is deliberately
     * NOT honored — an invalid policy cannot be trusted to widen access, only to name its root.
     * {@code pathNoun} names the requesting surface in error messages (e.g. "lock", "save").
     */
    static Path bootstrapExplicitPath(ProjectPolicy policy, McpSyncServerExchange exchange,
            String configured, boolean write, String pathNoun) {
        requireCapability(exchange, write ? PROJECT_WRITE : PROJECT_READ);
        Path root = policy.projectRoot();
        if (root == null) {
            throw new ToolArgException("The discovered project policy is invalid and names no "
                    + "canonical project_root, so an explicit " + pathNoun + " path cannot be "
                    + "contained. Fix the policy first (see validate_project_policy).");
        }
        final Path raw;
        try {
            raw = Path.of(configured);
        } catch (InvalidPathException e) {
            throw new ToolArgException("Invalid filesystem path '" + configured + "': "
                    + e.getMessage());
        }
        Path canonical = canonicalCandidate(
                (raw.isAbsolute() ? raw : root.resolve(raw)).normalize());
        if (!canonical.startsWith(root)) {
            throw new ToolArgException("Path is outside project_root and the invalid project policy "
                    + "cannot authorize external paths: " + canonical);
        }
        return canonical;
    }

    private static boolean looksLikeWindowsPath(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }

    private static URI uri(String value) {
        if (looksLikeWindowsPath(value)) return null;
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isNetworkScheme(String scheme) {
        if (scheme == null) return false;
        return Set.of("http", "https", "ftp", "jar")
                .contains(scheme.toLowerCase(Locale.ROOT));
    }

    private static String normalizedHost(URI uri) {
        String host = uri == null ? null : uri.getHost();
        return host == null ? null : stripTrailingDot(host.toLowerCase(Locale.ROOT));
    }

    private static String stripTrailingDot(String value) {
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String ? (String) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List<?> ? (List<String>) value : Collections.emptyList();
    }
}
