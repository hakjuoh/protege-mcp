package io.github.hakjuoh.protege_mcp.ui;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.tools.WriteTools;

import org.semanticweb.owlapi.model.OWLOntology;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds bounded Project Explorer snapshots without touching Swing state. */
final class ProjectWorkspaceService {

    static final int MAX_ENTRIES = 20_000;
    private static final Set<String> IGNORED_NAMES = Set.of(".DS_Store");
    private static final Set<String> ONTOLOGY_EXTENSIONS = Set.of(
            "owl", "rdf", "ttl", "ofn", "omn", "obo", "jsonld", "nt", "nq", "trig");
    private final int maxEntries;

    ProjectWorkspaceService() { this(MAX_ENTRIES); }

    ProjectWorkspaceService(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
    }

    record LoadedOntology(OWLOntology ontology, String ontologyIri, String documentLocation,
            Path localPath, boolean documentExists, long axiomCount) { }

    ProjectWorkspaceSnapshot build(ProjectPolicy policy, Path fallbackDocument,
            List<LoadedOntology> loaded) {
        return build(policy, fallbackDocument, loaded, null);
    }

    ProjectWorkspaceSnapshot build(ProjectPolicy policy, Path fallbackDocument,
            List<LoadedOntology> loaded, OWLOntology activeOntology) {
        Path root = policy != null && policy.loaded() ? policy.projectRoot() : parent(fallbackDocument);
        Object configuredName = policy != null ? policy.effective().get("project_id") : null;
        String projectName = configuredName instanceof String name && !name.isBlank() ? name
                : root != null && root.getFileName() != null
                        ? root.getFileName().toString() : "No saved project";
        if (root == null || !Files.isDirectory(root)) {
            return new ProjectWorkspaceSnapshot(null, projectName, List.of(), false, false,
                    activeOntology, List.of(), ontologyEntries(loaded, null));
        }

        Set<Path> workspaceMembers = workspaceMemberPaths(policy, root);
        Set<Path> primaryArtifacts = policy == null ? Set.of()
                : new HashSet<>(policy.assets().getOrDefault("root_artifact", List.of()));
        List<LoadedOntology> ontologyBindings = withPolicyBindings(policy, root, loaded);
        Map<Path, OWLOntology> loadedByPath = new HashMap<>();
        Map<String, OWLOntology> loadedByIri = new HashMap<>();
        for (LoadedOntology item : ontologyBindings) {
            if (item.ontologyIri() != null && item.ontology() != null) {
                loadedByIri.putIfAbsent(item.ontologyIri(), item.ontology());
            }
        }
        for (LoadedOntology item : ontologyBindings) {
            if (item.localPath() != null && item.documentExists()) {
                OWLOntology ontology = item.ontology() != null ? item.ontology()
                        : loadedByIri.get(item.ontologyIri());
                if (ontology != null) {
                    Path document = item.localPath().toAbsolutePath().normalize();
                    if (item.ontology() != null) loadedByPath.put(document, ontology);
                    else loadedByPath.putIfAbsent(document, ontology);
                }
            }
        }
        ScanBudget budget = new ScanBudget(maxEntries);
        List<ProjectWorkspaceSnapshot.ProjectFile> files = scanChildren(
                root.toAbsolutePath().normalize(), root.toAbsolutePath().normalize(),
                workspaceMembers, primaryArtifacts, loadedByPath, budget);
        List<ProjectWorkspaceSnapshot.OntologyEntry> ontologyEntries = ontologyEntries(
                ontologyBindings, root);
        List<ProjectWorkspaceSnapshot.OntologyEntry> projectOntologies = ontologyEntries.stream()
                .filter(entry -> entry.location() == ProjectWorkspaceSnapshot.OntologyLocation.PROJECT)
                .toList();
        List<ProjectWorkspaceSnapshot.OntologyEntry> externalOntologies = ontologyEntries.stream()
                .filter(entry -> entry.location() != ProjectWorkspaceSnapshot.OntologyLocation.PROJECT)
                .toList();
        return new ProjectWorkspaceSnapshot(root, projectName, files, budget.truncated(), false,
                activeOntology, projectOntologies, externalOntologies);
    }

    private static List<ProjectWorkspaceSnapshot.ProjectFile> scanChildren(Path root, Path directory,
            Set<Path> workspaceMembers, Set<Path> primaryArtifacts,
            Map<Path, OWLOntology> loadedByPath, ScanBudget budget) {
        List<ProjectWorkspaceSnapshot.ProjectFile> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (budget.exhausted()) {
                    budget.markTruncated();
                    break;
                }
                if (IGNORED_NAMES.contains(fileName(child))) continue;
                budget.consume();
                Path normalized = child.toAbsolutePath().normalize();
                boolean symlink = Files.isSymbolicLink(normalized);
                boolean directoryEntry = Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                        && !symlink;
                List<ProjectWorkspaceSnapshot.ProjectFile> children = directoryEntry
                        ? scanChildren(root, normalized, workspaceMembers,
                                primaryArtifacts, loadedByPath, budget) : List.of();
                entries.add(new ProjectWorkspaceSnapshot.ProjectFile(normalized,
                        root.relativize(normalized), kind(normalized, directoryEntry, symlink),
                        workspaceMembers.contains(normalized),
                        primaryArtifacts.contains(normalized),
                        symlink ? null : loadedByPath.get(normalized), children));
            }
        } catch (IOException | SecurityException ignored) {
            // A directory can disappear between a watcher event and this bounded snapshot.
        }
        entries.sort(FILE_ORDER);
        return List.copyOf(entries);
    }

    private static final Comparator<ProjectWorkspaceSnapshot.ProjectFile> FILE_ORDER =
            Comparator.comparingInt(ProjectWorkspaceService::sortGroup)
                    .thenComparing(ProjectWorkspaceSnapshot.ProjectFile::name,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ProjectWorkspaceSnapshot.ProjectFile::name);

    private static int sortGroup(ProjectWorkspaceSnapshot.ProjectFile entry) {
        if (entry.directory() && entry.name().startsWith(".")) return 0;
        if (entry.directory()) return 1;
        return 2;
    }

    private static ProjectWorkspaceSnapshot.FileKind kind(Path path, boolean directory,
            boolean symlink) {
        if (directory) return ProjectWorkspaceSnapshot.FileKind.DIRECTORY;
        if (symlink) return ProjectWorkspaceSnapshot.FileKind.SYMLINK;
        String name = fileName(path).toLowerCase(Locale.ROOT);
        if ("project.yaml".equals(name) && path.getParent() != null
                && ".protege-mcp".equals(fileName(path.getParent()))) {
            return ProjectWorkspaceSnapshot.FileKind.POLICY;
        }
        if ("catalog-v001.xml".equals(name) || name.endsWith("catalog.xml")) {
            return ProjectWorkspaceSnapshot.FileKind.XML_CATALOG;
        }
        if (name.equals("ro-crate-metadata.json") || name.equals("ro-crate-metadata.jsonld")) {
            return ProjectWorkspaceSnapshot.FileKind.METADATA;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1) : "";
        return ONTOLOGY_EXTENSIONS.contains(extension)
                ? ProjectWorkspaceSnapshot.FileKind.ONTOLOGY
                : ProjectWorkspaceSnapshot.FileKind.OTHER;
    }

    private static Set<Path> workspaceMemberPaths(ProjectPolicy policy, Path root) {
        Set<Path> paths = new HashSet<>();
        if (policy == null || !(policy.effective().get("workspace") instanceof Map<?, ?> workspace)
                || !(workspace.get("files") instanceof List<?> files)) return paths;
        for (Object value : files) {
            if (value instanceof String relative) {
                paths.add(root.resolve(relative).toAbsolutePath().normalize());
            }
        }
        return paths;
    }

    private static List<LoadedOntology> withPolicyBindings(ProjectPolicy policy, Path root,
            List<LoadedOntology> loaded) {
        List<LoadedOntology> result = new ArrayList<>(loaded);
        if (policy == null || !(policy.effective().get("workspace") instanceof Map<?, ?> workspace)
                || !(workspace.get("ontologies") instanceof List<?> ontologies)) {
            return result;
        }
        for (Object raw : ontologies) {
            if (!(raw instanceof Map<?, ?> binding) || !(binding.get("iri") instanceof String iri)
                    || !(binding.get("documents") instanceof List<?> documents)) continue;
            for (Object value : documents) {
                if (!(value instanceof String relative)) continue;
                Path path = root.resolve(relative).toAbsolutePath().normalize();
                boolean alreadyPresent = result.stream().anyMatch(item -> iri.equals(item.ontologyIri())
                        && path.equals(item.localPath()));
                if (!alreadyPresent) {
                    result.add(new LoadedOntology(null, iri, path.toUri().toString(), path,
                            Files.isRegularFile(path), 0));
                }
            }
        }
        return result;
    }

    private static List<ProjectWorkspaceSnapshot.OntologyEntry> ontologyEntries(
            List<LoadedOntology> loaded, Path root) {
        Map<String, MutableOntology> grouped = new LinkedHashMap<>();
        for (LoadedOntology item : loaded) {
            String key = item.ontologyIri() != null ? item.ontologyIri()
                    : "anonymous:" + System.identityHashCode(item.ontology());
            MutableOntology aggregate = grouped.computeIfAbsent(key,
                    ignored -> new MutableOntology(item));
            aggregate.add(item);
        }
        List<ProjectWorkspaceSnapshot.OntologyEntry> result = new ArrayList<>();
        grouped.values().forEach(value -> result.add(value.freeze(root)));
        result.sort(Comparator.comparing(ProjectWorkspaceSnapshot.OntologyEntry::displayName,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private static final class MutableOntology {
        private final LoadedOntology first;
        private final List<LoadedOntology> sources = new ArrayList<>();
        private final List<ProjectWorkspaceSnapshot.OntologyDocument> documents = new ArrayList<>();

        private MutableOntology(LoadedOntology first) { this.first = first; }

        private void add(LoadedOntology item) {
            sources.add(item);
            String location = item.documentLocation();
            if (location == null || location.isBlank()) location = "(in memory)";
            documents.add(new ProjectWorkspaceSnapshot.OntologyDocument(
                    location, item.localPath(), item.documentExists()));
        }

        private ProjectWorkspaceSnapshot.OntologyEntry freeze(Path root) {
            ProjectWorkspaceSnapshot.OntologyLocation location;
            boolean projectDocument = sources.stream().anyMatch(item ->
                    isInsideProject(item, root));
            boolean externalDocument = sources.stream().anyMatch(item -> item.localPath() != null
                    && item.documentExists() && !isInsideProject(item, root));
            boolean resolvedRemote = sources.stream().anyMatch(item -> item.localPath() == null
                    && item.ontology() != null && item.axiomCount() > 0);
            boolean inMemory = sources.stream().anyMatch(item -> item.ontology() != null
                    && (item.documentLocation() == null || item.documentLocation().isBlank()));
            if (projectDocument) {
                location = ProjectWorkspaceSnapshot.OntologyLocation.PROJECT;
            } else if (externalDocument || resolvedRemote) {
                location = ProjectWorkspaceSnapshot.OntologyLocation.EXTERNAL;
            } else if (inMemory) {
                location = ProjectWorkspaceSnapshot.OntologyLocation.IN_MEMORY;
            } else {
                location = ProjectWorkspaceSnapshot.OntologyLocation.UNRESOLVED;
            }
            String name = first.ontologyIri() == null ? "Anonymous Ontology"
                    : WriteTools.localName(first.ontologyIri());
            if (name == null || name.isBlank()) name = first.ontologyIri();
            OWLOntology ontology = sources.stream().map(LoadedOntology::ontology)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return new ProjectWorkspaceSnapshot.OntologyEntry(name, first.ontologyIri(), location,
                    ontology, documents);
        }
    }

    private static boolean isInsideProject(LoadedOntology item, Path root) {
        if (item.localPath() == null || !item.documentExists() || root == null) return false;
        try {
            return item.localPath().toRealPath().startsWith(root.toRealPath());
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    private static Path parent(Path path) {
        if (path == null) return null;
        Path absolute = path.toAbsolutePath().normalize();
        return Files.isDirectory(absolute) ? absolute : absolute.getParent();
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static final class ScanBudget {
        private final int maximum;
        private int count;
        private boolean truncated;

        private ScanBudget(int maximum) { this.maximum = maximum; }
        boolean exhausted() { return count >= maximum; }
        void consume() { count++; }
        void markTruncated() { truncated = true; }
        boolean truncated() { return truncated; }
    }
}
