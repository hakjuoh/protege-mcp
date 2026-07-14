package io.github.hakjuoh.protege_mcp.tools;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;

/**
 * An edit-versioned cache of the RDF snapshot {@code sparql_query} runs over, so repeated queries at the
 * same model state skip the expensive rebuild (copy the imports closure → serialise to Turtle → and, for
 * {@code include_inferred}, materialise the reasoner). One instance lives per {@link ToolContext} (i.e.
 * per running server / Protégé window).
 *
 * <h2>Why this is correctness-critical, and how it stays correct</h2>
 * The danger of any such cache is returning results for a stale snapshot. This cache is invalidated by a
 * monotonic {@code version} counter bumped on the EDT by TWO listeners:
 * <ul>
 *   <li>an {@link OWLOntologyChangeListener} on the model manager's ontology manager — fires on every
 *       axiom/import/annotation change to <em>any</em> loaded ontology (so manual GUI edits and MCP
 *       writes alike bump it);</li>
 *   <li>an {@link OWLModelManagerListener} on the model manager — fires on every model event
 *       (active-ontology switch, load/reload, <em>reasoner classification</em>, reasoner change, …), so a
 *       reclassification that changes only the <em>inferred</em> graph, or a switch that changes the
 *       closure, also invalidates the cache.</li>
 * </ul>
 * A cached entry is served only when its stamp equals the current {@code version}. The snapshot is built
 * and stamped inside a single EDT task, and listeners fire on the EDT, so no change can interleave
 * between reading the version and taking the snapshot — the stamped bytes always correspond to exactly
 * the model state at that version. Because MCP writes broadcast their change (bumping the version)
 * synchronously before returning, an edit followed by a query never serves the pre-edit snapshot.
 *
 * <p>Each hit re-parses the cached, immutable Turtle bytes into a <em>fresh</em> Jena model per query, so
 * no mutable Jena graph is ever shared across the multi-threaded transport threads. Entries are held via
 * {@link SoftReference} so the JVM can reclaim large snapshots under memory pressure (a cleared entry is
 * simply a miss). The asserted and inferred snapshots occupy separate slots so alternating query modes
 * do not thrash.
 */
final class SparqlSnapshotCache {

    /** Bumped on the EDT by either listener; read (volatile) off the EDT to validate a cached entry. */
    private final AtomicLong version = new AtomicLong(0);

    private volatile SoftReference<Entry> asserted;
    private volatile SoftReference<Entry> inferred;

    // Listener bookkeeping — touched only on the EDT (install/dispose both run there).
    private boolean installed;
    private OWLModelManager installedOn;
    private OWLOntologyChangeListener changeListener;
    private OWLModelManagerListener modelListener;

    /** An immutable cached snapshot: the model state stamp, the Turtle bytes, prefixes and optional note. */
    static final class Entry {
        final long stamp;
        final byte[] turtle;
        final Map<String, String> prefixes;
        final String note;

        Entry(long stamp, byte[] turtle, Map<String, String> prefixes, String note) {
            this.stamp = stamp;
            this.turtle = turtle;
            this.prefixes = prefixes;
            this.note = note;
        }
    }

    /** The current model-state version. Callers capture this on the EDT before taking a snapshot. */
    long version() {
        return version.get();
    }

    /**
     * Force the next query to rebuild its snapshot. For a state change that alters what a query sees but
     * fires NEITHER listener — notably a {@code set_prefix} edit, which mutates the document format's
     * prefix map directly (no {@code OWLOntologyChange}, no model event). A GUI-side prefix edit (which
     * likewise fires no listener) is instead caught by {@code sparql_query} re-reading the live prefix
     * map on a cache hit and calling this if it changed.
     */
    void invalidate() {
        version.incrementAndGet();
    }

    /**
     * The cached snapshot for {@code inferred}, or {@code null} if there is none or it is stale (its stamp
     * no longer equals the current {@link #version()}). Safe to call off the EDT.
     */
    Entry get(boolean inferred) {
        SoftReference<Entry> ref = inferred ? this.inferred : this.asserted;
        Entry e = ref == null ? null : ref.get();
        return e != null && e.stamp == version.get() ? e : null;
    }

    /** Cache and return the snapshot for {@code inferred} at model-state {@code stamp}. */
    Entry store(boolean inferred, long stamp, byte[] turtle, Map<String, String> prefixes, String note) {
        Entry e = new Entry(stamp, turtle, prefixes, note);
        if (inferred) {
            this.inferred = new SoftReference<>(e);
        } else {
            this.asserted = new SoftReference<>(e);
        }
        return e;
    }

    /**
     * Register the invalidation listeners the first time a query runs. Must be called on the EDT (it adds
     * listeners to the Protégé model). Idempotent for the lifetime of this cache.
     */
    void installIfNeeded(OWLModelManager mm) {
        if (installed) {
            return;
        }
        changeListener = changes -> version.incrementAndGet();
        modelListener = event -> version.incrementAndGet();
        mm.getOWLOntologyManager().addOntologyChangeListener(changeListener);
        mm.addListener(modelListener);
        installedOn = mm;
        installed = true;
    }

    /**
     * Remove the listeners and drop the cached snapshots. Must be called on the EDT (it touches the
     * Protégé model). Best-effort and idempotent; safe to call even if listeners were never installed.
     */
    void dispose() {
        if (installed && installedOn != null) {
            try {
                installedOn.getOWLOntologyManager().removeOntologyChangeListener(changeListener);
            } catch (RuntimeException ignored) {
                // best-effort: a shutting-down model manager may already have torn this down
            }
            try {
                installedOn.removeListener(modelListener);
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        installed = false;
        installedOn = null;
        changeListener = null;
        modelListener = null;
        asserted = null;
        inferred = null;
    }
}
