package io.github.hakjuoh.protege_mcp.ui;

import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable filesystem/ontology view consumed by the Project Explorer. */
final class ProjectWorkspaceSnapshot {

    enum FileKind { DIRECTORY, ONTOLOGY, XML_CATALOG, POLICY, METADATA, SYMLINK, OTHER }

    enum OntologyLocation { PROJECT, EXTERNAL, UNRESOLVED, IN_MEMORY }

    record ProjectFile(Path path, Path relativePath, FileKind kind, boolean workspaceMember,
            boolean primaryArtifact,
            OWLOntology loadedOntology, List<ProjectFile> children) {
        ProjectFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(kind, "kind");
            children = List.copyOf(children);
        }

        boolean directory() {
            return kind == FileKind.DIRECTORY;
        }

        String name() {
            Path name = path.getFileName();
            return name == null ? path.toString() : name.toString();
        }
    }

    record OntologyDocument(String location, Path localPath, boolean exists) { }

    record OntologyEntry(String displayName, String ontologyIri, OntologyLocation location,
            OWLOntology ontology, List<OntologyDocument> documents) {
        OntologyEntry {
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(location, "location");
            documents = List.copyOf(documents);
        }

        boolean hasLocalDocument() {
            return documents.stream().anyMatch(document -> document.localPath() != null
                    && document.exists());
        }

        String localDisplayName() {
            return documents.stream()
                    .filter(document -> document.localPath() != null && document.exists())
                    .map(OntologyDocument::localPath)
                    .map(Path::getFileName)
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .findFirst()
                    .orElse(displayName);
        }
    }

    private final Path projectRoot;
    private final String projectName;
    private final List<ProjectFile> files;
    private final boolean filesTruncated;
    private final boolean watcherTruncated;
    private final OWLOntology activeOntology;
    private final List<OntologyEntry> projectOntologies;
    private final List<OntologyEntry> externalOntologies;

    ProjectWorkspaceSnapshot(Path projectRoot, String projectName, List<ProjectFile> files,
            boolean filesTruncated, boolean watcherTruncated,
            List<OntologyEntry> projectOntologies, List<OntologyEntry> externalOntologies) {
        this(projectRoot, projectName, files, filesTruncated, watcherTruncated, null,
                projectOntologies, externalOntologies);
    }

    ProjectWorkspaceSnapshot(Path projectRoot, String projectName, List<ProjectFile> files,
            boolean filesTruncated, boolean watcherTruncated, OWLOntology activeOntology,
            List<OntologyEntry> projectOntologies, List<OntologyEntry> externalOntologies) {
        this.projectRoot = projectRoot;
        this.projectName = Objects.requireNonNull(projectName, "projectName");
        this.files = List.copyOf(files);
        this.filesTruncated = filesTruncated;
        this.watcherTruncated = watcherTruncated;
        this.activeOntology = activeOntology;
        this.projectOntologies = List.copyOf(projectOntologies);
        this.externalOntologies = List.copyOf(externalOntologies);
    }

    static ProjectWorkspaceSnapshot empty(String projectName) {
        return new ProjectWorkspaceSnapshot(null, projectName, List.of(), false, false,
                List.of(), List.of());
    }

    Path projectRoot() { return projectRoot; }
    String projectName() { return projectName; }
    List<ProjectFile> files() { return files; }
    boolean filesTruncated() { return filesTruncated; }
    boolean watcherTruncated() { return watcherTruncated; }
    OWLOntology activeOntology() { return activeOntology; }
    List<OntologyEntry> projectOntologies() { return projectOntologies; }
    List<OntologyEntry> externalOntologies() { return externalOntologies; }

    ProjectWorkspaceSnapshot withWatcherTruncated(boolean truncated) {
        return new ProjectWorkspaceSnapshot(projectRoot, projectName, files, filesTruncated,
                truncated, activeOntology, projectOntologies, externalOntologies);
    }
}
