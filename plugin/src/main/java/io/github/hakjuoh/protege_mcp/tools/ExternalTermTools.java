package io.github.hakjuoh.protege_mcp.tools;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.external.ExternalProviderGateway;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ProviderInspectRequest;
import io.github.hakjuoh.protege_mcp.external.ProviderResult;
import io.github.hakjuoh.protege_mcp.external.ProviderSearchRequest;
import io.github.hakjuoh.protege_mcp.external.ProviderSessionScope;
import io.github.hakjuoh.protege_mcp.external.ReuseAction;
import io.github.hakjuoh.protege_mcp.external.ReuseOperation;
import io.github.hakjuoh.protege_mcp.external.ReuseProposal;
import io.github.hakjuoh.protege_mcp.external.ReuseProposalInputIdentity;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Public project-governed external term discovery and read-only proposal tools. */
public final class ExternalTermTools {

    private ExternalTermTools() {
    }

    public static void register(ToolRegistry tools, ToolContext context) {
        tools.tool("search_external_terms",
                (exchange, request) -> search(context, exchange, request));
        tools.tool("inspect_external_term",
                (exchange, request) -> inspect(context, exchange, request));
        tools.tool("propose_term_reuse",
                (exchange, request) -> propose(context, exchange, request));
        tools.tool("accept_reuse_proposal",
                (exchange, request) -> ReuseAcceptanceTools.accept(
                        context, exchange, request));
    }

    static CallToolResult search(ToolContext context, McpSyncServerExchange exchange,
            CallToolRequest request) {
        Map<String, Object> args = Tools.args(request);
        String cursor = Tools.optString(args, "cursor");
        ProviderSearchRequest initial = null;
        if (cursor == null) {
            String providerId = identifier(Tools.reqString(args, "provider_id"), "provider_id");
            ProviderPolicy provider = resolveProvider(context, exchange, args, providerId);
            List<String> ontologies = normalizeIdentifiers(
                    Tools.stringList(args, "ontologies"), "ontology");
            if (ontologies.isEmpty() && !provider.ontologies().isEmpty()) {
                if (provider.ontologies().size() > 16) {
                    throw new ToolArgException("provider_ontology_filter_required",
                            "Select at most 16 ontologies from the project provider policy.", false);
                }
                ontologies = provider.ontologies();
            }
            String language = Tools.optString(args, "language");
            if (language == null) {
                language = provider.languages().isEmpty() ? "en" : provider.languages().get(0);
            }
            int limit = args.containsKey("limit")
                    ? Tools.optInt(args, "limit", provider.maxResults()) : provider.maxResults();
            initial = request(providerId, Tools.reqString(args, "query"), ontologies,
                    language, limit);
            authorizeRequest(provider, initial);
        } else if (hasAny(args, "provider_id", "query", "ontologies", "language", "limit")) {
            throw new ToolArgException("provider_cursor_arguments_conflict",
                    "An opaque cursor must be used without new search arguments.", false);
        }

        try {
            ExternalProviderGateway.SearchOutcome outcome = context.externalProviders().search(
                    scope(context, exchange), initial, cursor,
                    providerId -> resolveProvider(context, exchange, args, providerId)
                            .invocation(context, exchange, args));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider_id", outcome.providerId());
            result.put("profile", outcome.profile());
            result.put("items", outcome.page().items().stream()
                    .map(item -> item.toJson()).toList());
            result.put("total", outcome.page().total());
            result.put("returned", outcome.page().items().size());
            result.put("fetched_at", outcome.page().fetchedAt().toString());
            result.put("retries", outcome.page().retries());
            result.put("cache_hit", outcome.cacheHit());
            if (outcome.nextCursor() != null) {
                result.put("next_cursor", outcome.nextCursor());
                result.put("cursor_expires_in_seconds", 300);
            }
            return Tools.ok(result);
        } catch (ProviderFailure failure) {
            throw providerFailure(failure);
        }
    }

    static CallToolResult inspect(ToolContext context, McpSyncServerExchange exchange,
            CallToolRequest request) {
        Map<String, Object> args = Tools.args(request);
        String providerId = identifier(Tools.reqString(args, "provider_id"), "provider_id");
        ProviderPolicy provider = resolveProvider(context, exchange, args, providerId);
        ProviderInspectRequest inspection;
        try {
            String language = Tools.optString(args, "language");
            if (language == null && !provider.languages().isEmpty()) {
                language = provider.languages().get(0);
            }
            inspection = new ProviderInspectRequest(providerId,
                    Tools.reqString(args, "ontology"), Tools.reqString(args, "iri"),
                    language);
        } catch (IllegalArgumentException invalid) {
            throw new ToolArgException("provider_request_invalid", invalid.getMessage(), false);
        }
        authorizeRequest(provider, inspection);
        boolean fresh = Tools.optBool(args, "fresh", false);
        try {
            ExternalProviderGateway.InspectOutcome outcome = context.externalProviders().inspect(
                    inspection, requested -> resolveProvider(context, exchange, args, requested)
                            .invocation(context, exchange, args, fresh));
            return Tools.ok(Map.of("result", outcome.result().toJson(),
                    "cache_hit", outcome.cacheHit()));
        } catch (ProviderFailure failure) {
            throw providerFailure(failure);
        }
    }

    static CallToolResult propose(ToolContext context, McpSyncServerExchange exchange,
            CallToolRequest request) {
        Map<String, Object> args = Tools.args(request);
        String providerId = identifier(Tools.reqString(args, "provider_id"), "provider_id");
        ProviderPolicy provider = resolveProvider(context, exchange, args, providerId);
        String language = Tools.optString(args, "language");
        if (language == null) {
            language = provider.languages().isEmpty() ? "en" : provider.languages().get(0);
        }
        ProviderInspectRequest inspection;
        try {
            inspection = new ProviderInspectRequest(providerId,
                    Tools.reqString(args, "ontology"), Tools.reqString(args, "iri"), language);
        } catch (IllegalArgumentException invalid) {
            throw new ToolArgException("provider_request_invalid", invalid.getMessage(), false);
        }
        authorizeRequest(provider, inspection);
        String expectedTermFingerprint = Tools.reqString(args, "term_fingerprint");
        if (!expectedTermFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new ToolArgException("provider_term_fingerprint_invalid",
                    "term_fingerprint must identify direct inspection content.", false);
        }
        final OperationInput operationInput;
        try {
            operationInput = operationInput(args);
            operationInput.validateRequestedEntityIri(inspection.iri());
        } catch (IllegalArgumentException invalid) {
            throw invalidOperation(invalid);
        }

        ProviderResult evidence;
        try {
            evidence = context.externalProviders().inspect(inspection,
                    requested -> resolveProvider(context, exchange, args, requested)
                            .freshInvocation(context, exchange, args)).result();
        } catch (ProviderFailure failure) {
            throw providerFailure(failure);
        }
        requireEvidenceMatches(inspection, provider, evidence, expectedTermFingerprint);

        MappingTools.ProposalState state = MappingTools.proposalState(
                context, exchange, args);
        ProviderPolicy current = resolveProvider(context, exchange, args, providerId);
        authorizeRequest(current, inspection);
        if (!provider.policyDigest().equals(state.policy().digest())
                || !current.policyDigest().equals(state.policy().digest())
                || !provider.profile().equals(current.profile())) {
            throw new ToolArgException("proposal_input_changed",
                    "Project provider policy changed while the proposal was captured.",
                    Map.of("effects_prevented", true), true);
        }

        final ReuseProposal proposal;
        try {
            ReuseOperation operation = operationInput.bind(evidence);
            proposal = ReuseProposal.create(evidence,
                    ReuseProposalInputIdentity.create(evidence, inspection.language(),
                            state.modelRevision(), state.mappingRevision(),
                            state.policy().digest(), state.targetIdentity()), operation);
        } catch (IllegalArgumentException invalid) {
            throw invalidOperation(invalid);
        }
        try {
            String proposalId = context.reuseProposals().issue(scope(context, exchange), proposal);
            return Tools.ok(Map.of("proposal_id", proposalId,
                    "expires_in_seconds", 900, "proposal", proposal.toJson()));
        } catch (ProviderFailure failure) {
            throw providerFailure(failure);
        }
    }

    private static void requireEvidenceMatches(ProviderInspectRequest request,
            ProviderPolicy provider, ProviderResult result, String expectedTermFingerprint) {
        if (result == null || !request.providerId().equals(result.providerId())
                || !provider.profile().equals(result.profile())
                || !request.ontology().equals(result.sourceOntology())
                || !request.iri().equals(result.entityIri())) {
            throw new ToolArgException("provider_result_mismatch",
                    "Provider evidence does not match the requested term identity.", false);
        }
        if (!expectedTermFingerprint.matches("sha256:[0-9a-f]{64}")
                || !MessageDigest.isEqual(
                        expectedTermFingerprint.getBytes(StandardCharsets.US_ASCII),
                        result.termFingerprint().getBytes(StandardCharsets.US_ASCII))) {
            throw new ToolArgException("provider_term_changed",
                    "Direct inspection content no longer matches term_fingerprint; call "
                            + "inspect_external_term again before proposing reuse.",
                    Map.of("effects_prevented", true,
                            "current_term_fingerprint", result.termFingerprint()), true);
        }
    }

    private static OperationInput operationInput(Map<String, Object> args) {
        ReuseAction action = ReuseAction.parse(Tools.reqString(args, "action"));
        return switch (action) {
            case REUSE_IRI -> {
                rejectPresent(args, "mapping", "local_entity");
                yield new OperationInput(action, null);
            }
            case ADD_MAPPING -> {
                rejectPresent(args, "local_entity");
                yield new OperationInput(action,
                        new ReuseOperation.AddMapping(stringMap(args.get("mapping"), "mapping")));
            }
            case MINT_LOCAL_WITH_MAPPING -> {
                Map<String, Object> entity = objectArgument(args.get("local_entity"),
                        "local_entity");
                rejectUnexpected(entity, "iri", "type", "labels");
                yield new OperationInput(action, new ReuseOperation.MintLocalWithMapping(
                                operationString(entity, "iri"),
                                ReuseOperation.MintedEntityType.parse(
                                        operationString(entity, "type")),
                                localizedTexts(entity.get("labels")),
                                stringMap(args.get("mapping"), "mapping")));
            }
        };
    }

    private static ToolArgException invalidOperation(IllegalArgumentException invalid) {
        return new ToolArgException("reuse_operation_invalid", invalid.getMessage(),
                Map.of("effects_prevented", true), false);
    }

    private static Map<String, String> stringMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> values) || values.isEmpty() || values.size() > 128) {
            throw new IllegalArgumentException(field + " must be an object with 1..128 entries");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name)
                    || !(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException(field + " requires string keys and values");
            }
            result.put(name, value);
        }
        return result;
    }

    private static List<ProviderResult.LocalizedText> localizedTexts(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 16) {
            throw new IllegalArgumentException("local_entity.labels must contain 1..16 items");
        }
        List<ProviderResult.LocalizedText> result = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> label = objectArgument(value, "local_entity label");
            rejectUnexpected(label, "value", "language");
            result.add(new ProviderResult.LocalizedText(operationString(label, "value"),
                    operationString(label, "language")));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> objectArgument(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> value)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException(field + " requires string field names");
            }
            result.put(name, entry.getValue());
        }
        return result;
    }

    private static void rejectPresent(Map<String, Object> args, String... fields) {
        for (String field : fields) {
            if (args.containsKey(field)) {
                throw new IllegalArgumentException(field + " is not allowed for this reuse action");
            }
        }
    }

    private static void rejectUnexpected(Map<String, Object> value, String... allowed) {
        List<String> permitted = List.of(allowed);
        for (String field : value.keySet()) {
            if (!permitted.contains(field)) {
                throw new IllegalArgumentException("unexpected operation field " + field);
            }
        }
    }

    private static String operationString(Map<String, Object> value, String field) {
        Object result = value.get(field);
        if (!(result instanceof String text)) {
            throw new IllegalArgumentException("operation field " + field + " must be a string");
        }
        return text;
    }

    private record OperationInput(ReuseAction action, ReuseOperation operation) {
        OperationInput {
            if (action == null || (action == ReuseAction.REUSE_IRI) != (operation == null)
                    || operation != null && operation.action() != action) {
                throw new IllegalArgumentException("reuse operation input is inconsistent");
            }
        }

        ReuseOperation bind(ProviderResult evidence) {
            return action == ReuseAction.REUSE_IRI
                    ? new ReuseOperation.ReuseIri(evidence.entityIri()) : operation;
        }

        void validateRequestedEntityIri(String entityIri) {
            ReuseOperation candidate = action == ReuseAction.REUSE_IRI
                    ? new ReuseOperation.ReuseIri(entityIri) : operation;
            candidate.validateRequestedEntityIri(entityIri);
        }
    }

    private static ProviderSearchRequest request(String providerId, String query,
            List<String> ontologies, String language, int limit) {
        try {
            return new ProviderSearchRequest(providerId, query, ontologies, language, limit, null);
        } catch (IllegalArgumentException invalid) {
            throw new ToolArgException("provider_request_invalid", invalid.getMessage(), false);
        }
    }

    private static ProviderPolicy resolveProvider(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args, String providerId) {
        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(context, exchange,
                Tools.optString(args, "policy_path"))
                .withRequestNetwork(Tools.optString(args, "network"));
        ProjectPolicy policy = rules.policy();
        if (!policy.loaded() || policy.version() != 2) {
            throw new ToolArgException("provider_policy_required",
                    "External providers require a valid project policy version 2.", false);
        }
        if (!policy.valid()) {
            throw new ToolArgException("invalid_project_policy",
                    "Invalid project policy cannot authorize an external provider.", false);
        }
        Map<String, Object> selected = null;
        for (Map<String, Object> candidate : objects(object(
                policy.effective(), "external_terms").get("providers"))) {
            String id = string(candidate, "id");
            if (id != null && identifier(id, "provider id").equals(providerId)) {
                selected = candidate;
                break;
            }
        }
        if (selected == null) {
            throw new ToolArgException("provider_not_declared",
                    "The requested provider is not declared by project policy.", false);
        }
        if (!Boolean.TRUE.equals(selected.get("enabled"))) {
            throw new ToolArgException("provider_disabled",
                    "The requested provider is disabled by project policy.", false);
        }
        String profile = requiredString(selected, "profile");
        if (!"ols4".equals(profile)) {
            throw new ToolArgException("provider_profile_unsupported",
                    "The requested provider profile is not supported by this release.", false);
        }
        return new ProviderPolicy(providerId, profile, requiredString(selected, "origin_alias"),
                string(selected, "credential_id"), normalizeIdentifiers(
                        strings(selected.get("ontologies")), "ontology"),
                normalizeLanguages(strings(selected.get("languages"))),
                integer(selected, "ttl_seconds", 900),
                "cache_ok".equals(string(selected, "freshness")),
                integer(selected, "max_results", 25), policy.digest(),
                projectFingerprint(policy), rules);
    }

    private static void authorizeRequest(ProviderPolicy policy, ProviderSearchRequest request) {
        boolean ontologiesAllowed = policy.ontologies().isEmpty()
                || !request.ontologies().isEmpty()
                && policy.ontologies().containsAll(request.ontologies());
        boolean languageAllowed = policy.languages().isEmpty()
                || policy.languages().contains(request.language());
        if (!ontologiesAllowed) {
            throw new ToolArgException("provider_ontology_denied",
                    "Search ontology filters are outside project policy.", false);
        }
        if (!languageAllowed) {
            throw new ToolArgException("provider_language_denied",
                    "Search language is outside project policy.", false);
        }
        if (request.limit() > policy.maxResults()) {
            throw new ToolArgException("provider_limit_denied",
                    "Search limit exceeds the project provider maximum.", false);
        }
    }

    private static void authorizeRequest(ProviderPolicy policy, ProviderInspectRequest request) {
        if (!policy.ontologies().isEmpty()
                && !policy.ontologies().contains(request.ontology())) {
            throw new ToolArgException("provider_ontology_denied",
                    "Inspection ontology is outside project policy.", false);
        }
        if (!policy.languages().isEmpty()
                && !policy.languages().contains(request.language())) {
            throw new ToolArgException("provider_language_denied",
                    "Inspection language is outside project policy.", false);
        }
    }

    static ProviderSessionScope scope(ToolContext context,
            McpSyncServerExchange exchange) {
        AuthenticatedPrincipal principal = principal(exchange);
        if (principal == null) {
            throw new ToolArgException("authorization_denied",
                    "Authenticated principal is required for provider state.", false);
        }
        return new ProviderSessionScope(principal.type(), principal.clientId(),
                principal.grantId(), context.revisions().workspaceId());
    }

    private static AuthenticatedPrincipal principal(McpSyncServerExchange exchange) {
        if (exchange == null) return AuthenticatedPrincipal.staticAdmin();
        Object value = exchange.transportContext() == null ? null
                : exchange.transportContext().get(AuthenticatedPrincipal.CONTEXT_KEY);
        return value instanceof AuthenticatedPrincipal principal ? principal : null;
    }

    private static ToolArgException providerFailure(ProviderFailure failure) {
        return new ToolArgException(failure.code(), failure.getMessage(), failure.details(),
                failure.retryable());
    }

    private static String identifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new ToolArgException("provider_request_invalid", field + " is invalid.", false);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeIdentifiers(List<String> values, String field) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = identifier(value, field);
            if (!result.contains(normalized)) result.add(normalized);
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private static List<String> normalizeLanguages(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.length() > 64
                    || !value.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) {
                throw new ToolArgException("provider_request_invalid",
                        "provider language is invalid.", false);
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!result.contains(normalized)) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static boolean hasAny(Map<String, Object> args, String... names) {
        for (String name : names) if (args.containsKey(name)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> parent, String field) {
        Object value = parent.get(field);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) if (item instanceof String text) result.add(text);
        return result;
    }

    private static String requiredString(Map<String, Object> value, String field) {
        String result = string(value, field);
        if (result == null) {
            throw new ToolArgException("invalid_project_policy",
                    "Project provider field " + field + " is unavailable.", false);
        }
        return result;
    }

    private static String string(Map<String, Object> value, String field) {
        Object result = value.get(field);
        return result instanceof String text ? text : null;
    }

    private static int integer(Map<String, Object> value, String field, int fallback) {
        Object result = value.get(field);
        return result instanceof Number number ? number.intValue() : fallback;
    }

    private static String projectFingerprint(ProjectPolicy policy) {
        if (policy == null || policy.projectRoot() == null || policy.digest() == null) {
            throw new ToolArgException("invalid_project_policy",
                    "Project identity is unavailable for provider authorization.", false);
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        List<String> values = List.of(policy.projectRoot().toString(), policy.digest());
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(values.size()).array());
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        StringBuilder result = new StringBuilder("sha256:");
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }

    private record ProviderPolicy(String providerId, String profile, String originAlias,
            String credentialId, List<String> ontologies, List<String> languages,
            int ttlSeconds, boolean cacheReadAllowed, int maxResults, String policyDigest,
            String projectFingerprint, DirectAccessPolicy.Rules rules) {

        ExternalProviderGateway.Invocation invocation(ToolContext context,
                McpSyncServerExchange exchange, Map<String, Object> args) {
            return new ExternalProviderGateway.Invocation(providerId, profile, originAlias,
                    credentialId, projectFingerprint, Duration.ofSeconds(ttlSeconds),
                    cacheReadAllowed && ttlSeconds > 0, ontologies, languages, maxResults,
                    authority -> resolveProvider(context, exchange, args, providerId)
                            .projectFingerprint,
                    exactOrigin -> authorizeNetwork(context, exchange, args, exactOrigin));
        }

        ExternalProviderGateway.Invocation invocation(ToolContext context,
                McpSyncServerExchange exchange, Map<String, Object> args, boolean fresh) {
            return fresh ? freshInvocation(context, exchange, args)
                    : invocation(context, exchange, args);
        }

        ExternalProviderGateway.Invocation freshInvocation(ToolContext context,
                McpSyncServerExchange exchange, Map<String, Object> args) {
            return new ExternalProviderGateway.Invocation(providerId, profile, originAlias,
                    credentialId, projectFingerprint, Duration.ZERO, false,
                    ontologies, languages, maxResults,
                    authority -> resolveProvider(context, exchange, args, providerId)
                            .projectFingerprint,
                    exactOrigin -> authorizeNetwork(context, exchange, args, exactOrigin));
        }

        private void authorizeNetwork(ToolContext context, McpSyncServerExchange exchange,
                Map<String, Object> args, URI exactOrigin) {
            resolveProvider(context, exchange, args, providerId).rules
                    .authorizeNetwork(exactOrigin, false);
        }
    }
}
