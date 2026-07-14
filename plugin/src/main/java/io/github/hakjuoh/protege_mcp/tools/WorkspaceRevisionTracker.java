package io.github.hakjuoh.protege_mcp.tools;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;

/**
 * Per-backend optimistic-concurrency clock for the live Protégé workspace.
 *
 * <p>The random workspace id is minted with the {@link ToolContext}, so a restart or another window
 * can never accept a token from this backend. Ontology and model-manager listeners advance the fast
 * session counter for edits, imports, active-ontology/load events, reasoner changes and classification.
 * Prefix edits have no reliable Protégé event, so every read also recomputes the document fingerprint
 * and advances the counter when the bytes changed without a corresponding event. That defensive
 * observation covers only unannounced ONTOLOGY/document changes: the baseline it hashes is
 * deliberately digest-independent, so an import-lock or project-policy file edited on disk never
 * advances the session counter by itself — such changes surface solely through the caller-supplied
 * digests folded into the RETURNED revision's document fingerprint and through the explicit
 * preflight/import-lock digest re-checks at commit time.
 *
 * <p>Installation, reads and disposal are performed on the model thread. The counter is atomic only so
 * diagnostic reads remain safe while listeners are being delivered.
 */
final class WorkspaceRevisionTracker {

    private final String workspaceId;
    private final AtomicLong sessionRevision = new AtomicLong();

    private boolean installed;
    private boolean disposed;
    private OWLModelManager installedOn;
    private OWLOntologyChangeListener ontologyListener;
    private OWLModelManagerListener modelListener;

    private long lastObservedCounter = -1;
    private String lastSemanticFingerprint;
    private String lastDocumentFingerprint;

    WorkspaceRevisionTracker() {
        this(UUID.randomUUID().toString());
    }

    WorkspaceRevisionTracker(String workspaceId) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    String workspaceId() {
        return workspaceId;
    }

    long version() {
        return sessionRevision.get();
    }

    RevisionSnapshot current(OWLModelManager mm, String importLockDigest, String policyDigest) {
        installIfNeeded(mm);
        // Change detection must not depend on the caller's digests. Callers legitimately differ
        // (explicit vs discovered vs no policy → different importLockDigest/policyDigest), and folding
        // those into the comparison would flip a workspace edit signal on every interleaved call from a
        // differently-configured session, spuriously bumping the shared counter and manufacturing
        // revision_conflict. Detect only true unannounced workspace edits (e.g. a prefix change with no
        // Protégé event) via the digest-independent fingerprint; a changed import-lock/policy is already
        // reflected in the RETURNED revision below (and re-checked explicitly at commit time).
        OntologyFingerprint baseline = OntologyFingerprints.compute(mm.getActiveOntology());
        long observed = sessionRevision.get();
        boolean unannouncedChange = lastObservedCounter == observed
                && (different(lastSemanticFingerprint, baseline.semanticFingerprint())
                        || different(lastDocumentFingerprint, baseline.documentFingerprint()));
        if (unannouncedChange) {
            observed = sessionRevision.incrementAndGet();
        }
        lastObservedCounter = observed;
        lastSemanticFingerprint = baseline.semanticFingerprint();
        lastDocumentFingerprint = baseline.documentFingerprint();
        OntologyFingerprint fingerprint = OntologyFingerprints.compute(
                mm.getActiveOntology(), importLockDigest);
        return new RevisionSnapshot(new ModelRevision(workspaceId, observed,
                fingerprint.semanticFingerprint(), fingerprint.documentFingerprint()), fingerprint);
    }

    private static boolean different(String left, String right) {
        return !Objects.equals(left, right);
    }

    void installIfNeeded(OWLModelManager mm) {
        if (disposed) {
            // The ToolContext was disposed (server stop). A late read must not re-register listeners
            // onto the still-live model manager — that would leak them until the window closes.
            return;
        }
        if (installed && installedOn == mm) {
            return;
        }
        if (installed) {
            removeListeners();
        }
        ontologyListener = changes -> sessionRevision.incrementAndGet();
        modelListener = event -> sessionRevision.incrementAndGet();
        mm.getOWLOntologyManager().addOntologyChangeListener(ontologyListener);
        mm.addListener(modelListener);
        installedOn = mm;
        installed = true;
    }

    void invalidate() {
        sessionRevision.incrementAndGet();
    }

    void dispose() {
        disposed = true;
        removeListeners();
    }

    private void removeListeners() {
        if (installed && installedOn != null) {
            try {
                installedOn.getOWLOntologyManager().removeOntologyChangeListener(ontologyListener);
            } catch (RuntimeException ignored) {
                // Best effort during model/window shutdown.
            }
            try {
                installedOn.removeListener(modelListener);
            } catch (RuntimeException ignored) {
                // Best effort during model/window shutdown.
            }
        }
        installed = false;
        installedOn = null;
        ontologyListener = null;
        modelListener = null;
    }

    record RevisionSnapshot(ModelRevision revision, OntologyFingerprint fingerprint) { }
}
