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
 * that must keep the model coherent across several hops — notably change-set-backed verified apply, whose
 * isolated preflight and final revision/policy revalidation precede one live commit — takes this lock so
 * two such calls cannot interleave. One instance is built per server start and threaded to every provider,
 * so it is effectively server-wide. (Interactive GUI edits cannot take the lock; tools additionally detect
 * an intervening change, so the lock is the fast path, not the only guard.)
 */
public final class ToolContext {

    private final OntologyAccess access;
    private final McpServerController controller;
    private final WriteConfirmer confirmer;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final SparqlSnapshotCache sparqlCache = new SparqlSnapshotCache();
    private final WorkspaceRevisionTracker revisions = new WorkspaceRevisionTracker();
    private final ChangeSetStore changeSets = new ChangeSetStore();

    public ToolContext(OntologyAccess access, McpServerController controller) {
        this(access, controller, null);
    }

    public ToolContext(OntologyAccess access, McpServerController controller, WriteConfirmer confirmer) {
        this.access = access;
        this.controller = controller;
        this.confirmer = confirmer;
    }

    public OntologyAccess access() {
        return access;
    }

    public McpServerController controller() {
        return controller;
    }

    /**
     * The injected write-confirmation gate (the Swing dialog at runtime), or {@code null} when none is
     * wired — e.g. in headless tests. With confirmation enabled and no confirmer, writes fail closed.
     */
    public WriteConfirmer confirmer() {
        return confirmer;
    }

    /** The server-level write mutex (see the class doc). */
    public ReentrantLock writeLock() {
        return writeLock;
    }

    /** The edit-versioned SPARQL snapshot cache (one per server), shared by {@code sparql_query}. */
    SparqlSnapshotCache sparqlCache() {
        return sparqlCache;
    }

    /** Per-window revision clock used by previews, commits, verified saves, and revision reads. */
    WorkspaceRevisionTracker revisions() {
        return revisions;
    }

    ChangeSetStore changeSets() {
        return changeSets;
    }

    /**
     * Release server-scoped resources when the owning server stops: removes the SPARQL cache's model
     * listeners (on the EDT) and drops its cached snapshots. Best-effort — a shutting-down or unresponsive
     * EDT never blocks server stop. Idempotent.
     *
     * <p>The bounded {@link OntologyAccess#compute} cancels its queued body on timeout, so a busy EDT
     * would leave the listeners attached (they only die with the model manager on a FULL window close,
     * not on a Stop/restart where the same manager survives). To avoid that leak, on timeout we re-post
     * the (idempotent, EDT-safe) listener removal via a plain, un-cancelled {@code invokeLater} so it
     * still runs once the EDT frees up.
     */
    public void dispose() {
        try {
            access.compute(mm -> {
                sparqlCache.dispose();
                revisions.dispose();
                changeSets.dispose();
                return null;
            }, 5_000L);
        } catch (RuntimeException timedOutOrFailed) {
            try {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    sparqlCache.dispose();
                    revisions.dispose();
                    changeSets.dispose();
                });
            } catch (RuntimeException ignored) {
                // Headless / no EDT (e.g. a full shutdown): the listeners die with the model manager.
            }
        }
    }
}
