package io.github.hakjuoh.protege_mcp.tools;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Read-only inspection of the active ontology's resolved and unresolved import graph. */
public final class ImportTools {

    private ImportTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("inspect_imports",
                (ex, req) -> ctx.access().compute(ImportTools::inspect));
    }

    static CallToolResult inspect(OWLModelManager mm) {
        return analyze(mm.getActiveOntology()).result();
    }

    /** Analyze one already-loaded graph. This never installs mappers or attempts document I/O. */
    static ImportReport analyze(OWLOntology root) {
        OWLOntologyManager manager = root.getOWLOntologyManager();
        Map<OWLOntology, Integer> depths = new LinkedHashMap<>();
        Deque<OWLOntology> queue = new ArrayDeque<>();
        List<Map<String, Object>> imports = new ArrayList<>();
        List<Map<String, Object>> resolved = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        Map<OWLOntology, Set<OWLOntology>> adjacency = new LinkedHashMap<>();

        depths.put(root, 0);
        queue.add(root);
        while (!queue.isEmpty()) {
            OWLOntology source = queue.removeFirst();
            int sourceDepth = depths.get(source);
            adjacency.computeIfAbsent(source, ignored -> new LinkedHashSet<>());
            List<OWLImportsDeclaration> declarations = new ArrayList<>(source.getImportsDeclarations());
            declarations.sort(Comparator.comparing(d -> d.getIRI().toString()));
            for (OWLImportsDeclaration declaration : declarations) {
                OWLOntology target = resolveImportedOntology(manager, declaration);
                Map<String, Object> row = importRow(manager, root, source, sourceDepth,
                        declaration, target);
                imports.add(row);
                if (target == null) {
                    missing.add(new LinkedHashMap<>(row));
                    continue;
                }
                resolved.add(new LinkedHashMap<>(row));
                adjacency.get(source).add(target);
                adjacency.computeIfAbsent(target, ignored -> new LinkedHashSet<>());
                if (!depths.containsKey(target)) {
                    depths.put(target, sourceDepth + 1);
                    queue.addLast(target);
                }
            }
        }

        Comparator<Map<String, Object>> importOrder = Comparator
                .comparing((Map<String, Object> row) -> string(row.get("source_ref")))
                .thenComparing(row -> string(row.get("import_iri")))
                .thenComparing(row -> string(row.get("target_ref")));
        imports.sort(importOrder);
        resolved.sort(importOrder);
        missing.sort(importOrder);

        List<Map<String, Object>> ontologies = new ArrayList<>();
        List<Map.Entry<OWLOntology, Integer>> nodes = new ArrayList<>(depths.entrySet());
        nodes.sort(Comparator.comparing(entry -> ontologyRef(manager, entry.getKey())));
        for (Map.Entry<OWLOntology, Integer> entry : nodes) {
            ontologies.add(ontologyRow(manager, root, entry.getKey(), entry.getValue()));
        }

        List<Map<String, Object>> cycles = cycles(manager, adjacency);
        List<Map<String, Object>> conflicts = conflicts(manager, depths.keySet());
        Map<String, Object> summary = summary(ontologies, imports, resolved, missing, cycles, conflicts);
        return new ImportReport(ontologyRow(manager, root, root, 0), ontologies, imports,
                resolved, missing, cycles, conflicts, summary);
    }

    /**
     * OWLAPI normally records declaration resolution in the manager, but declarations added before
     * their target document is loaded can lack that cached edge. Fall back to an unambiguous exact
     * ontology/version/document IRI match so inspection reflects the graph visible in the workspace.
     */
    private static OWLOntology resolveImportedOntology(OWLOntologyManager manager,
            OWLImportsDeclaration declaration) {
        OWLOntology resolved = manager.getImportedOntology(declaration);
        if (resolved != null) {
            return resolved;
        }
        String imported = declaration.getIRI().toString();
        List<OWLOntology> matches = new ArrayList<>();
        for (OWLOntology ontology : manager.getOntologies()) {
            OWLOntologyID id = ontology.getOntologyID();
            boolean match = imported.equals(ontologyIri(id)) || imported.equals(versionIri(id))
                    || imported.equals(documentIri(manager, ontology));
            if (match) {
                matches.add(ontology);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static Map<String, Object> importRow(OWLOntologyManager manager, OWLOntology root,
            OWLOntology source, int sourceDepth, OWLImportsDeclaration declaration,
            OWLOntology target) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_ref", ontologyRef(manager, source));
        row.put("source_ontology_iri", ontologyIri(source.getOntologyID()));
        row.put("source_version_iri", versionIri(source.getOntologyID()));
        row.put("source_document_iri", documentIri(manager, source));
        row.put("import_iri", declaration.getIRI().toString());
        row.put("direct", source.equals(root));
        row.put("depth", sourceDepth + 1);
        row.put("resolved", target != null);
        if (target != null) {
            String document = documentIri(manager, target);
            row.put("target_ref", ontologyRef(manager, target));
            row.put("target_ontology_iri", ontologyIri(target.getOntologyID()));
            row.put("target_version_iri", versionIri(target.getOntologyID()));
            row.put("target_document_iri", document);
            row.put("target_source_type", sourceType(document));
        }
        return row;
    }

    private static Map<String, Object> ontologyRow(OWLOntologyManager manager, OWLOntology root,
            OWLOntology ontology, int depth) {
        OWLOntologyID id = ontology.getOntologyID();
        String document = documentIri(manager, ontology);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ref", ontologyRef(manager, ontology));
        row.put("ontology_iri", ontologyIri(id));
        row.put("version_iri", versionIri(id));
        row.put("document_iri", document);
        row.put("source_type", sourceType(document));
        row.put("anonymous", id.isAnonymous());
        row.put("depth", depth);
        row.put("role", ontology.equals(root) ? "root" : depth == 1 ? "direct" : "transitive");
        return row;
    }

    private static Map<String, Object> summary(List<Map<String, Object>> ontologies,
            List<Map<String, Object>> imports, List<Map<String, Object>> resolved,
            List<Map<String, Object>> missing, List<Map<String, Object>> cycles,
            List<Map<String, Object>> conflicts) {
        int direct = 0;
        int local = 0;
        int remote = 0;
        for (Map<String, Object> row : imports) {
            if (Boolean.TRUE.equals(row.get("direct"))) {
                direct++;
            }
        }
        for (Map<String, Object> row : ontologies) {
            if ("local".equals(row.get("source_type"))) {
                local++;
            } else if ("remote".equals(row.get("source_type"))) {
                remote++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ontology_count", ontologies.size());
        summary.put("declaration_count", imports.size());
        summary.put("direct_import_count", direct);
        summary.put("transitive_import_count", imports.size() - direct);
        summary.put("resolved_import_count", resolved.size());
        summary.put("missing_import_count", missing.size());
        summary.put("local_document_count", local);
        summary.put("remote_document_count", remote);
        summary.put("cycle_count", cycles.size());
        summary.put("conflict_count", conflicts.size());
        return summary;
    }

    private static List<Map<String, Object>> conflicts(OWLOntologyManager manager,
            Set<OWLOntology> ontologies) {
        Map<String, List<OWLOntology>> byLogical = new TreeMap<>();
        Map<String, List<OWLOntology>> byVersion = new TreeMap<>();
        Map<String, List<OWLOntology>> byDocument = new TreeMap<>();
        for (OWLOntology ontology : ontologies) {
            OWLOntologyID id = ontology.getOntologyID();
            String logical = ontologyIri(id);
            String version = versionIri(id);
            String document = documentIri(manager, ontology);
            if (logical != null) {
                byLogical.computeIfAbsent(logical, ignored -> new ArrayList<>()).add(ontology);
            }
            if (version != null) {
                byVersion.computeIfAbsent(version, ignored -> new ArrayList<>()).add(ontology);
            }
            if (document != null) {
                byDocument.computeIfAbsent(document, ignored -> new ArrayList<>()).add(ontology);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        addConflicts(out, manager, "multiple_versions", "ontology_iri", byLogical);
        addConflicts(out, manager, "version_iri_collision", "version_iri", byVersion);
        addConflicts(out, manager, "document_iri_collision", "document_iri", byDocument);
        out.sort(Comparator.comparing(row -> string(row.get("type")) + "\u0000"
                + string(row.get("value"))));
        return out;
    }

    private static void addConflicts(List<Map<String, Object>> out, OWLOntologyManager manager,
            String type, String field, Map<String, List<OWLOntology>> groups) {
        for (Map.Entry<String, List<OWLOntology>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            List<String> refs = new ArrayList<>();
            for (OWLOntology ontology : entry.getValue()) {
                refs.add(ontologyRef(manager, ontology));
            }
            refs.sort(String::compareTo);
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("type", type);
            conflict.put("field", field);
            conflict.put("value", entry.getKey());
            conflict.put("ontologies", refs);
            out.add(conflict);
        }
    }

    private static List<Map<String, Object>> cycles(OWLOntologyManager manager,
            Map<OWLOntology, Set<OWLOntology>> adjacency) {
        List<Set<OWLOntology>> components = new Tarjan(adjacency).components();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Set<OWLOntology> component : components) {
            OWLOntology only = component.size() == 1 ? component.iterator().next() : null;
            boolean selfLoop = only != null && adjacency.getOrDefault(only, Set.of()).contains(only);
            if (component.size() < 2 && !selfLoop) {
                continue;
            }
            List<String> refs = new ArrayList<>();
            for (OWLOntology ontology : component) {
                refs.add(ontologyRef(manager, ontology));
            }
            refs.sort(String::compareTo);
            Map<String, Object> cycle = new LinkedHashMap<>();
            cycle.put("ontologies", refs);
            cycle.put("size", refs.size());
            cycle.put("self_loop", selfLoop);
            out.add(cycle);
        }
        out.sort(Comparator.comparing(row -> String.join("\u0000", strings(row.get("ontologies")))));
        return out;
    }

    private static String ontologyRef(OWLOntologyManager manager, OWLOntology ontology) {
        OWLOntologyID id = ontology.getOntologyID();
        String version = versionIri(id);
        if (version != null) {
            return version;
        }
        String logical = ontologyIri(id);
        if (logical != null) {
            return logical;
        }
        String document = documentIri(manager, ontology);
        return document == null ? "(anonymous ontology)" : document;
    }

    private static String ontologyIri(OWLOntologyID id) {
        return id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : null;
    }

    private static String versionIri(OWLOntologyID id) {
        return id.getVersionIRI().isPresent() ? id.getVersionIRI().get().toString() : null;
    }

    private static String documentIri(OWLOntologyManager manager, OWLOntology ontology) {
        IRI iri = manager.getOntologyDocumentIRI(ontology);
        return iri == null ? null : iri.toString();
    }

    static String sourceType(String documentIri) {
        if (documentIri == null) {
            return "unknown";
        }
        try {
            String scheme = new URI(documentIri).getScheme();
            if (scheme == null) {
                return "unknown";
            }
            if ("file".equalsIgnoreCase(scheme)) {
                return "local";
            }
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return "remote";
            }
            if ("owlapi".equalsIgnoreCase(scheme)) {
                return "memory";
            }
            return "other";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List ? (List<String>) value : List.of();
    }

    /** Immutable analysis payload shared by inspect_imports and document-loading results. */
    static final class ImportReport {
        final Map<String, Object> root;
        final List<Map<String, Object>> ontologies;
        final List<Map<String, Object>> imports;
        final List<Map<String, Object>> resolvedImports;
        final List<Map<String, Object>> missingImports;
        final List<Map<String, Object>> cycles;
        final List<Map<String, Object>> conflicts;
        final Map<String, Object> summary;

        ImportReport(Map<String, Object> root, List<Map<String, Object>> ontologies,
                List<Map<String, Object>> imports, List<Map<String, Object>> resolvedImports,
                List<Map<String, Object>> missingImports, List<Map<String, Object>> cycles,
                List<Map<String, Object>> conflicts, Map<String, Object> summary) {
            this.root = root;
            this.ontologies = ontologies;
            this.imports = imports;
            this.resolvedImports = resolvedImports;
            this.missingImports = missingImports;
            this.cycles = cycles;
            this.conflicts = conflicts;
            this.summary = summary;
        }

        CallToolResult result() {
            boolean missingClear = missingImports.isEmpty();
            boolean conflictsClear = conflicts.isEmpty();
            return Tools.json()
                    .put("root", root)
                    .put("summary", summary)
                    .put("ontologies", ontologies)
                    .put("imports", imports)
                    .put("resolved_imports", resolvedImports)
                    .put("missing_imports", missingImports)
                    .put("cycles", cycles)
                    .put("conflicts", conflicts)
                    .put("missing_imports_clear", missingClear)
                    .put("conflicts_clear", conflictsClear)
                    .put("import_integrity_ok", missingClear && conflictsClear)
                    .result();
        }
    }

    /** Deterministic strongly-connected-component finder for cycle reporting. */
    private static final class Tarjan {
        private final Map<OWLOntology, Set<OWLOntology>> graph;
        private final Map<OWLOntology, Integer> indexes = new HashMap<>();
        private final Map<OWLOntology, Integer> lowLinks = new HashMap<>();
        private final Deque<OWLOntology> stack = new ArrayDeque<>();
        private final Set<OWLOntology> onStack = new HashSet<>();
        private final List<Set<OWLOntology>> components = new ArrayList<>();
        private int nextIndex;

        Tarjan(Map<OWLOntology, Set<OWLOntology>> graph) {
            this.graph = graph;
        }

        List<Set<OWLOntology>> components() {
            List<OWLOntology> nodes = new ArrayList<>(graph.keySet());
            nodes.sort(Comparator.comparing(Object::toString));
            for (OWLOntology node : nodes) {
                if (!indexes.containsKey(node)) {
                    visit(node);
                }
            }
            return components;
        }

        private void visit(OWLOntology node) {
            indexes.put(node, nextIndex);
            lowLinks.put(node, nextIndex);
            nextIndex++;
            stack.push(node);
            onStack.add(node);

            List<OWLOntology> targets = new ArrayList<>(graph.getOrDefault(node, Set.of()));
            targets.sort(Comparator.comparing(Object::toString));
            for (OWLOntology target : targets) {
                if (!indexes.containsKey(target)) {
                    visit(target);
                    lowLinks.put(node, Math.min(lowLinks.get(node), lowLinks.get(target)));
                } else if (onStack.contains(target)) {
                    lowLinks.put(node, Math.min(lowLinks.get(node), indexes.get(target)));
                }
            }

            if (lowLinks.get(node).equals(indexes.get(node))) {
                Set<OWLOntology> component = new LinkedHashSet<>();
                OWLOntology member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(node));
                components.add(component);
            }
        }
    }
}
