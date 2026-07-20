package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Point-in-time, lifecycle-owned headless ontology snapshot with explicit drift detection. */
public final class WorkspaceSnapshot implements AutoCloseable {

    private final ProjectPolicy policy;
    private final OWLOntologyManager manager;
    private final OWLOntology root;
    private final Set<OWLOntology> closure;
    private final List<WorkspaceSource> sources;
    private final Map<String, List<Path>> capturedAssets;
    private final ModelRevision revision;
    private final String closureFingerprint;
    private final String importLockDigest;
    private final Path temporaryRoot;
    private final AtomicBoolean closed = new AtomicBoolean();

    WorkspaceSnapshot(ProjectPolicy policy, OWLOntologyManager manager, OWLOntology root,
            List<WorkspaceSource> sources, Map<String, List<Path>> capturedAssets,
            ModelRevision revision, String closureFingerprint, String importLockDigest,
            Path temporaryRoot) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.manager = java.util.Objects.requireNonNull(manager, "manager");
        this.root = java.util.Objects.requireNonNull(root, "root");
        this.closure = java.util.Collections.unmodifiableSet(
                new LinkedHashSet<>(root.getImportsClosure()));
        this.sources = List.copyOf(sources);
        Map<String, List<Path>> assetCopy = new LinkedHashMap<>();
        capturedAssets.forEach((key, paths) -> assetCopy.put(key, List.copyOf(paths)));
        this.capturedAssets = java.util.Collections.unmodifiableMap(assetCopy);
        this.revision = java.util.Objects.requireNonNull(revision, "revision");
        this.closureFingerprint = java.util.Objects.requireNonNull(
                closureFingerprint, "closureFingerprint");
        this.importLockDigest = importLockDigest;
        this.temporaryRoot = temporaryRoot.toAbsolutePath().normalize();
    }

    public ProjectPolicy policy() {
        return policy;
    }

    public OWLOntology root() {
        ensureOpen();
        return root;
    }

    public Set<OWLOntology> closure() {
        ensureOpen();
        return closure;
    }

    public List<WorkspaceSource> sources() {
        return sources;
    }

    /** Policy assets remapped to captured files/directories; release_output is intentionally absent. */
    public Map<String, List<Path>> capturedAssets() {
        ensureOpen();
        return capturedAssets;
    }

    public ModelRevision revision() {
        return revision;
    }

    public String closureFingerprint() {
        return closureFingerprint;
    }

    /** Reconstruct the complete ontology/document fingerprint pinned by this captured snapshot. */
    public OntologyFingerprint fingerprint() {
        ensureOpen();
        return OntologyFingerprints.compute(root, importLockDigest);
    }

    public String importLockDigest() {
        return importLockDigest;
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (OWLOntology ontology : new ArrayList<>(manager.getOntologies())) {
            manager.removeOntology(ontology);
        }
        try (var walk = Files.walk(temporaryRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: the owner-only temp tree is not a committed artifact.
                }
            });
        } catch (IOException ignored) {
            // Already absent or best-effort cleanup failed.
        }
    }

    private void ensureOpen() {
        if (closed()) {
            throw new IllegalStateException("workspace snapshot is closed");
        }
    }
}
