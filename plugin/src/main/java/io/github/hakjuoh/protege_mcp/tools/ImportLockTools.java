package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Deterministic local import lock generation/verification and no-network catalog validation. */
public final class ImportLockTools {

    private ImportLockTools() {}

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("write_import_lock", (ex, req) -> write(ctx, Tools.args(req), () -> {}, ex));

        tools.tool("verify_import_lock", (ex, req) -> verify(ctx, Tools.args(req), () -> {}, ex));

        tools.tool(
                "validate_catalog",
                (ex, req) ->
                        validateCatalog(ctx, Tools.args(req), DirectAccessPolicy.resolve(ctx, ex)));
    }

    /**
     * EDT wait bound shared by every lock-tool revision hop: write's capture and install hops AND
     * verify's two coordinate hops. Each hop runs a WorkspaceRevisionTracker fingerprint (two full
     * canonical-serialization renders), which on a large ontology legitimately outlives the default
     * 30 s wait — a default-bound verify would spuriously time out against the very lock write just
     * installed — and the install hop replaces the lock file, so a wait that expires after its body
     * starts would report a failure while the lock still lands. Kept equal to the change-set commit
     * bound, which exists for the same slow-but-succeeding reason.
     */
    static final long WRITE_HOP_TIMEOUT_MS = ImportLockModel.WRITE_HOP_TIMEOUT_MS;

    static CallToolResult write(ToolContext ctx, Map<String, Object> arguments) {
        return ImportLockCaptureCodec.write(ctx, arguments);
    }

    static CallToolResult write(
            ToolContext ctx, Map<String, Object> arguments, Runnable afterCapture) {
        return ImportLockCaptureCodec.write(ctx, arguments, afterCapture);
    }

    private static CallToolResult write(
            ToolContext ctx,
            Map<String, Object> arguments,
            Runnable afterCapture,
            McpSyncServerExchange exchange) {
        return ImportLockCaptureCodec.write(ctx, arguments, afterCapture, exchange);
    }

    static CallToolResult verify(ToolContext ctx, Map<String, Object> arguments) {
        return ImportLockVerifier.verify(ctx, arguments);
    }

    static CallToolResult verify(
            ToolContext ctx, Map<String, Object> arguments, Runnable afterCapture) {
        return ImportLockVerifier.verify(ctx, arguments, afterCapture);
    }

    private static CallToolResult verify(
            ToolContext ctx,
            Map<String, Object> arguments,
            Runnable afterCapture,
            McpSyncServerExchange exchange) {
        return ImportLockVerifier.verify(ctx, arguments, afterCapture, exchange);
    }

    static final String SOURCE_POLICY_DECLARED = ImportLockModel.SOURCE_POLICY_DECLARED;
    static final String SOURCE_BESIDE_DOCUMENT = ImportLockModel.SOURCE_BESIDE_DOCUMENT;
    static final String BESIDE_DOCUMENT_TRUST_NOTE = ImportLockModel.BESIDE_DOCUMENT_TRUST_NOTE;

    static Map<String, Object> verifyForGate(
            ProjectPolicy policy,
            ImportTools.ImportReport report,
            OntologyFingerprint activeFingerprint,
            String closureFingerprint) {
        return ImportLockVerifier.verifyForGate(
                policy, report, activeFingerprint, closureFingerprint);
    }

    static Map<String, Object> verifyForGate(
            Path lockPath,
            ImportTools.ImportReport report,
            OntologyFingerprint activeFingerprint,
            String closureFingerprint) {
        return ImportLockVerifier.verifyForGate(
                lockPath, report, activeFingerprint, closureFingerprint);
    }

    record GateLock(Path path, String source) {}

    static GateLock resolveRequestGateLock(
            ProjectPolicy policy, ImportTools.ImportReport report, DirectAccessPolicy.Rules rules) {
        ImportLockModel.GateLock lock =
                ImportLockVerifier.resolveRequestGateLock(policy, report, rules);
        return lock == null ? null : new GateLock(lock.path(), lock.source());
    }

    static Map<String, Object> verifyClosureBeforeLoad(
            DirectAccessPolicy.Rules rules,
            ImportTools.ImportReport report,
            java.io.File loadedDocument,
            LockMode mode) {
        return ImportLockVerifier.verifyClosureBeforeLoad(rules, report, loadedDocument, mode);
    }

    record ParsedLockedDocument(String memberKey, String memberLine, boolean sessionOnly) {}

    static List<ParsedLockedDocument> parseLockedClosure(
            List<LockEntry> entries, List<Map<String, Object>> resolvedImports) throws IOException {
        List<ImportLockModel.LockEntry> internalEntries =
                entries.stream().map(ImportLockTools::toInternal).toList();
        return ImportLockVerifier.parseLockedClosure(internalEntries, resolvedImports).stream()
                .map(
                        document ->
                                new ParsedLockedDocument(
                                        document.memberKey(),
                                        document.memberLine(),
                                        document.sessionOnly()))
                .toList();
    }

    static CallToolResult validateCatalog(ToolContext ctx, Map<String, Object> arguments) {
        return ImportCatalogValidator.validateCatalog(ctx, arguments);
    }

    private static CallToolResult validateCatalog(
            ToolContext ctx, Map<String, Object> arguments, DirectAccessPolicy.Rules rules) {
        return ImportCatalogValidator.validateCatalog(ctx, arguments, rules);
    }

    static LockEntry parseEntry(Object value, Path base) throws IOException {
        ImportLockModel.LockEntry entry = ImportLockCaptureCodec.parseEntry(value, base);
        return new LockEntry(
                entry.ontologyIri(),
                entry.versionIri(),
                entry.document(),
                entry.sha256(),
                entry.direct(),
                entry.absolute());
    }

    private static ImportLockModel.LockEntry toInternal(LockEntry entry) {
        return new ImportLockModel.LockEntry(
                entry.ontologyIri(),
                entry.versionIri(),
                entry.document(),
                entry.sha256(),
                entry.direct(),
                entry.absolute());
    }

    record LockEntry(
            String ontologyIri,
            String versionIri,
            String document,
            String sha256,
            boolean direct,
            Path absolute) {
        String key() {
            return versionIri == null || versionIri.isBlank()
                    ? ontologyIri
                    : ontologyIri + " @ " + versionIri;
        }
    }
}
