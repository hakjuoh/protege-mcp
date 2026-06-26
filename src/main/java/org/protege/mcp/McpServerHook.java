package org.protege.mcp;

import org.protege.editor.core.editorkit.EditorKit;
import org.protege.editor.core.editorkit.plugin.EditorKitHook;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.mcp.config.McpConfig;
import org.protege.mcp.server.McpServerController;
import org.protege.mcp.server.McpServerRegistry;
import org.protege.mcp.server.OntologyAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EditorKit-scoped lifecycle owner for the embedded MCP server.
 *
 * <p>{@code initialise()} / {@code dispose()} may run on the EDT <em>or</em> on the OSGi framework
 * thread depending on Protégé's boot path, and the workspace is not yet initialised when this runs,
 * so we touch no UI here and start the HTTP server on a background thread. {@code dispose()} stops
 * the server off-EDT with a bounded join so closing a window is never blocked by a stuck server.
 */
public class McpServerHook extends EditorKitHook {

    private static final Logger log = LoggerFactory.getLogger(McpServerHook.class);

    private McpServerController controller;

    @Override
    public void initialise() throws Exception {
        OWLEditorKit editorKit = (OWLEditorKit) getEditorKit();
        OntologyAccess access = new OntologyAccess(editorKit);
        controller = new McpServerController(access);
        McpServerRegistry.register(editorKit, controller);

        if (McpConfig.load().isAutoStart()) {
            final McpServerController toStart = controller;
            Thread starter = new Thread(() -> {
                try {
                    // Defer to a window that already owns the single process-wide port instead of
                    // fighting over the bind; promoteSuccessor() hands the port to us if it later frees.
                    McpServerRegistry.startIfNoOwner(toStart);
                } catch (Exception e) {
                    // non-fatal: a bind failure must not abort EditorKit creation; surface in the view
                    log.error("protege-mcp: MCP server failed to auto-start", e);
                }
            }, "protege-mcp-start");
            starter.setDaemon(true);
            starter.start();
        }
    }

    @Override
    public void dispose() throws Exception {
        EditorKit editorKit = getEditorKit();
        if (editorKit != null) {
            McpServerRegistry.unregister(editorKit);
        }
        final McpServerController toStop = controller;
        controller = null;
        if (toStop != null) {
            Thread stopper = new Thread(() -> {
                boolean wasOwner = toStop.isRunning();
                toStop.stop();
                if (wasOwner) {
                    // This window held the single process-wide port. Hand it to another open window so
                    // swapping EditorKits (e.g. opening a different ontology) doesn't take the server
                    // down. Promote on its own daemon thread so window-close latency stays bounded by
                    // stop() and isn't coupled to the successor's Jetty boot.
                    Thread promoter = new Thread(McpServerRegistry::promoteSuccessor, "protege-mcp-promote");
                    promoter.setDaemon(true);
                    promoter.start();
                }
            }, "protege-mcp-stop");
            stopper.setDaemon(true);
            stopper.start();
            try {
                stopper.join(3_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
