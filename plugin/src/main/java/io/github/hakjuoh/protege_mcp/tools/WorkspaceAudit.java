package io.github.hakjuoh.protege_mcp.tools;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.hakjuoh.protege_mcp.core.audit.AuditEvent;
import io.github.hakjuoh.protege_mcp.core.audit.AuditExportService;
import io.github.hakjuoh.protege_mcp.core.audit.AuditFacts;
import io.github.hakjuoh.protege_mcp.core.audit.AuditLog;
import io.github.hakjuoh.protege_mcp.core.audit.AuditSettings;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Request-bound attribution for one live Protégé window, backed by owner-only JSONL streams. */
final class WorkspaceAudit {

    private static final long POLICY_REFRESH_NANOS = 5_000_000_000L;

    record Ticket(AuditLog log, String operation, AuditEvent.Actor actor, String targetOntology,
            String targetModule, boolean mutationExpected, List<String> confirmationReferences) {
    }

    record ExportResult(String path, String sha256, long bytes, int eventCount, int sourceCount) {
    }

    private final ToolContext context;
    private final String workspaceId = "ws-" + UUID.randomUUID();
    private Path cachedRoot;
    private AuditSettings cachedSettings;
    private AuditLog cachedLog;
    private Resolved cachedResolved;
    private long cachedRevision = Long.MIN_VALUE;
    private long refreshAfterNanos;

    WorkspaceAudit(ToolContext context) {
        this.context = context;
    }

    Ticket begin(String operation, AuthenticatedPrincipal principal, Map<String, Object> arguments,
            boolean mutationExpected) {
        Resolved resolved = resolve(false);
        AuditEvent.Actor actor = actor(principal);
        List<String> references = AuditFacts.confirmationReferences(arguments);
        Ticket ticket = new Ticket(resolved.log(), operation, actor,
                target(arguments, "ontology_iri", resolved.ontologyIri()),
                target(arguments, "module_iri", null), mutationExpected, references);
        ticket.log().append(event(ticket, AuditEvent.Outcome.STARTED, null, null, Map.of(), null));
        return ticket;
    }

    void denied(String operation, AuthenticatedPrincipal principal, Map<String, Object> arguments,
            Set<String> requiredCapabilities, boolean mutationExpected) {
        Resolved resolved = resolve(false);
        Ticket ticket = new Ticket(resolved.log(), operation, actor(principal),
                target(arguments, "ontology_iri", resolved.ontologyIri()),
                target(arguments, "module_iri", null), mutationExpected,
                AuditFacts.confirmationReferences(arguments));
        resolved.log().append(event(ticket, AuditEvent.Outcome.DENIED,
                mutationExpected ? Boolean.FALSE : null, null,
                Map.of("required_capabilities", requiredCapabilities.stream().sorted().toList()), null));
    }

    void complete(Ticket ticket, CallToolResult result) {
        boolean failed = result != null && Boolean.TRUE.equals(result.isError());
        Object content = result == null ? null : result.structuredContent();
        final Boolean committed;
        if (failed) {
            committed = ticket.mutationExpected() ? Boolean.FALSE : null;
        } else {
            committed = AuditFacts.committed(content, ticket.mutationExpected());
        }
        List<String> references = new ArrayList<>(ticket.confirmationReferences());
        if (Boolean.TRUE.equals(committed) && context.controller() != null
                && context.controller().isConfirmWrites()) {
            references.add("interactive_write_confirmation");
        }
        ticket.log().append(new AuditEvent(ticket.operation(),
                failed ? AuditEvent.Outcome.FAILED : AuditEvent.Outcome.SUCCEEDED,
                ticket.actor(), ticket.targetOntology(), ticket.targetModule(),
                AuditFacts.gate(content), committed, AuditFacts.summary(content), references,
                AuditFacts.releaseManifest(ticket.operation(), content)));
    }

    void failed(Ticket ticket, RuntimeException failure) {
        ticket.log().append(event(ticket, AuditEvent.Outcome.FAILED,
                ticket.mutationExpected() ? Boolean.FALSE : null, null,
                Map.of("failure_type", failure.getClass().getSimpleName()), null));
    }

    ExportResult export(String output) {
        Resolved resolved = resolve(true);
        AuditExportService.Result result = AuditExportService.export(
                resolved.log().projectAuditDirectory(), resolved.projectRoot(), output);
        return new ExportResult(result.path(), result.sha256(), result.bytes(), result.eventCount(),
                result.sourceCount());
    }

    String previewExport(String output) {
        Resolved resolved = resolve(true);
        return AuditExportService.previewOutput(resolved.projectRoot(), output);
    }

    private AuditEvent event(Ticket ticket, AuditEvent.Outcome outcome, Boolean committed,
            String gate, Map<String, Object> summary, String releaseManifest) {
        return new AuditEvent(ticket.operation(), outcome, ticket.actor(), ticket.targetOntology(),
                ticket.targetModule(), gate, committed, summary, ticket.confirmationReferences(),
                releaseManifest);
    }

    private synchronized Resolved resolve(boolean requireValidPolicy) {
        if (context.access() == null) {
            throw new IllegalStateException("audit attribution requires an ontology workspace");
        }
        long revision = context.revisions().version();
        long now = System.nanoTime();
        if (!requireValidPolicy && cachedResolved != null && revision == cachedRevision
                && now - refreshAfterNanos < 0) {
            return cachedResolved;
        }
        ProjectPolicyTools.PolicyContext live = context.access().compute(mm -> {
            context.revisions().installIfNeeded(mm);
            return ProjectPolicyTools.capture(mm);
        });
        ProjectPolicy policy = ProjectPolicyLoader.load(null, live.documentPath(),
                live.activeOntologyIri(), live.installedReasoners());
        if (requireValidPolicy && (!policy.loaded() || !policy.valid() || policy.projectRoot() == null)) {
            throw new ToolArgException("A valid discovered project policy is required to export the "
                    + "project audit log.");
        }
        Path projectRoot = projectRoot(policy, live.documentPath());
        AuditSettings settings = policy.valid() ? AuditSettings.from(policy) : AuditSettings.defaults();
        if (cachedLog == null || !projectRoot.equals(cachedRoot) || !settings.equals(cachedSettings)) {
            cachedLog = AuditLog.create(projectRoot, workspaceId, settings);
            cachedRoot = projectRoot;
            cachedSettings = settings;
        }
        cachedResolved = new Resolved(cachedLog, projectRoot, live.activeOntologyIri());
        // If the workspace changed during capture/load, retain the pre-capture revision so the next
        // request refreshes again instead of treating potentially mixed coordinates as current.
        cachedRevision = revision;
        refreshAfterNanos = now + POLICY_REFRESH_NANOS;
        return cachedResolved;
    }

    private static Path projectRoot(ProjectPolicy policy, Path documentPath) {
        if (policy.projectRoot() != null && Files.exists(policy.projectRoot())) {
            return policy.projectRoot().toAbsolutePath().normalize();
        }
        Path candidate = documentPath == null ? null : documentPath.toAbsolutePath().getParent();
        while (candidate != null && !Files.exists(candidate)) candidate = candidate.getParent();
        if (candidate != null) return candidate.normalize();
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home is required for audit attribution");
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    private static AuditEvent.Actor actor(AuthenticatedPrincipal principal) {
        if (principal == null) {
            return new AuditEvent.Actor("unauthenticated", "Unauthenticated request", "none", Set.of());
        }
        return new AuditEvent.Actor(principal.clientId(), principal.displayName(), principal.type(),
                principal.capabilities());
    }

    private static String target(Map<String, Object> arguments, String key, String fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 2048) return fallback;
        try {
            URI iri = URI.create(text);
            return iri.isAbsolute() ? text : fallback;
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    private record Resolved(AuditLog log, Path projectRoot, String ontologyIri) {
    }
}
