package io.github.hakjuoh.protege_mcp.tools;

import java.util.concurrent.locks.ReentrantLock;

import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;

/**
 * Everything a tool handler needs: the EDT-marshalling {@link OntologyAccess} and the owning
 * {@link McpServerController} (for live read-only / write-confirmation gates).
 *
 * <p>Also carries the server-level {@linkplain #writeLock() write mutex}. MCP handlers run on
 * multi-threaded transport threads ({@code immediateExecution(true)}) and {@link OntologyAccess#compute}
 * only serialises each <em>individual</em> EDT hop, holding no lock across a multi-hop sequence. A tool
 * that must keep the model coherent across several hops — notably {@code apply_changes(verify)}, whose
 * pre-snapshot → apply → classify → post-read → conditional-undo spans multiple hops — takes this lock so
 * two such calls cannot interleave. One instance is built per server start and threaded to every provider,
 * so it is effectively server-wide. (Interactive GUI edits cannot take the lock; tools additionally detect
 * an intervening change, so the lock is the fast path, not the only guard.)
 */
public final class ToolContext {

    private final OntologyAccess access;
    private final McpServerController controller;
    private final ReentrantLock writeLock = new ReentrantLock();

    public ToolContext(OntologyAccess access, McpServerController controller) {
        this.access = access;
        this.controller = controller;
    }

    public OntologyAccess access() {
        return access;
    }

    public McpServerController controller() {
        return controller;
    }

    /** The server-level write mutex (see the class doc). */
    public ReentrantLock writeLock() {
        return writeLock;
    }
}
