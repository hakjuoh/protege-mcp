package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Explicit, confirmed export of the redacted owner-only audit streams for the active project. */
public final class AuditTools {

    private AuditTools() {
    }

    public static void register(ToolRegistry tools, ToolContext context) {
        tools.tool("export_audit_log", (exchange, request) -> {
            Map<String, Object> arguments = Tools.args(request);
            boolean dryRun = Tools.optBool(arguments, "dry_run", true);
            String output = Tools.optString(arguments, "output");
            if (dryRun) {
                return Tools.json().put("exported", false).put("dry_run", true)
                        .put("path", context.audit().previewExport(output))
                        .result();
            }
            String target = context.audit().previewExport(output);
            CallToolResult denied = WriteTools.checkWriteAllowed(context,
                    "export the redacted project audit log to " + target);
            if (denied != null) return denied;
            // The preference can flip while the confirmation dialog is open. Match prepare_release:
            // re-check immediately before the filesystem mutation instead of trusting the first read.
            if (context.controller().isReadOnly()) return WriteTools.readOnlyDenied();
            WorkspaceAudit.ExportResult result = context.audit().export(output);
            return Tools.json().put("exported", true).put("dry_run", false)
                    .put("path", result.path()).put("sha256", result.sha256())
                    .put("bytes", result.bytes()).put("event_count", result.eventCount())
                    .put("source_count", result.sourceCount()).result();
        });
    }

}
