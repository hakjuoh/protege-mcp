package org.protege.mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.IRIDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.SetOntologyID;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Tools that load complete OWL documents and copy their content into the active ontology. */
public final class OntologyDocumentTools {

    private OntologyDocumentTools() {
    }

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("merge_ontology_document",
                "Load an OWL ontology document from a local path or document IRI/URL and copy its "
                        + "axioms, direct import declarations, and ontology annotations into the "
                        + "active ontology. This preserves axiom annotations and axiom types that "
                        + "are not exposed by the structured add_axiom tool. GitHub blob URLs are "
                        + "converted to raw.githubusercontent.com URLs automatically.",
                Tools.schema()
                        .strReq("source", "Local file path, file: IRI, http(s) document IRI, or GitHub blob URL.")
                        .bool("replace_active", "Remove active ontology axioms/imports/ontology annotations first "
                                + "(default false).")
                        .bool("copy_ontology_id", "Copy source ontology IRI/version to the active ontology. "
                                + "Defaults to replace_active.")
                        .integer("connection_timeout_ms", "Remote document connection timeout (default 15000).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String source = Tools.reqString(a, "source");
                    boolean replaceActive = Tools.optBool(a, "replace_active", false);
                    boolean copyOntologyId = Tools.optBool(a, "copy_ontology_id", replaceActive);
                    int timeoutMs = Tools.optInt(a, "connection_timeout_ms", 15_000);
                    String summary = replaceActive
                            ? "replace active ontology — delete ALL its axioms, imports and ontology "
                                    + "annotations — with " + source
                            : "merge ontology document " + source;
                    CallToolResult denied = WriteTools.checkWriteAllowed(ctx, summary);
                    if (denied != null) {
                        return denied;
                    }
                    LoadedOntology loaded = load(source, timeoutMs);
                    // A whole-document merge applies thousands of changes in one EDT batch, so give it a
                    // longer bound than the default — otherwise a slow-but-succeeding apply is reported
                    // as a timeout while the model keeps mutating.
                    return ctx.access().compute(mm -> apply(mm, loaded, replaceActive, copyOntologyId),
                            MERGE_TIMEOUT_MS);
                })));

        return tools;
    }

    /** Wait bound for applying a whole document's changes on the EDT (see merge_ontology_document). */
    private static final long MERGE_TIMEOUT_MS = 120_000L;

    private static LoadedOntology load(String source, int timeoutMs) {
        String normalized = normalizeSource(source);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        // Missing imports are handled SILENTly so the load still succeeds; record the unresolved IRIs
        // so the merge result can warn about them rather than swallowing them entirely.
        List<String> unresolvedImports = new ArrayList<>();
        manager.addMissingImportListener(event -> unresolvedImports.add(event.getImportedOntologyURI().toString()));
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setFollowRedirects(true)
                .setConnectionTimeout(timeoutMs);
        try {
            OWLOntology sourceOntology = manager.loadOntologyFromOntologyDocument(
                    documentSource(normalized), config);
            return new LoadedOntology(
                    normalized,
                    sourceOntology.getOntologyID(),
                    new LinkedHashSet<>(sourceOntology.getAxioms()),
                    new LinkedHashSet<>(sourceOntology.getImportsDeclarations()),
                    new LinkedHashSet<>(sourceOntology.getAnnotations()),
                    unresolvedImports);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not load ontology document '" + normalized + "': "
                    + e.getMessage());
        }
    }

    private static OWLOntologyDocumentSource documentSource(String source) {
        // An existing local file wins over IRI parsing — this also handles a Windows drive path like
        // "C:/x.rdf", which java.net.URI would otherwise treat as a URI with scheme "c".
        File asFile = new File(source);
        if (asFile.isFile()) {
            return new FileDocumentSource(asFile.getAbsoluteFile());
        }
        IRI iri = looksLikeWindowsPath(source) ? null : Tools.asIri(source);
        if (iri != null) {
            return new IRIDocumentSource(iri);
        }
        throw new ToolArgException("Ontology document is not a readable file and is not an absolute IRI: "
                + asFile.getAbsoluteFile());
    }

    /** A bare Windows drive path such as {@code C:\x} or {@code C:/x} — not a document IRI. */
    private static boolean looksLikeWindowsPath(String source) {
        return source.length() >= 2 && Character.isLetter(source.charAt(0)) && source.charAt(1) == ':';
    }

    private static String normalizeSource(String source) {
        String trimmed = source.trim();
        String prefix = "https://github.com/";
        int blob = trimmed.indexOf("/blob/");
        if (trimmed.startsWith(prefix) && blob > prefix.length()) {
            String repoPart = trimmed.substring(prefix.length(), blob);
            String blobPart = trimmed.substring(blob + "/blob/".length());
            String[] repo = repoPart.split("/", 2);
            String[] branchAndPath = blobPart.split("/", 2);
            if (repo.length == 2 && branchAndPath.length == 2) {
                return "https://raw.githubusercontent.com/" + repo[0] + "/" + repo[1]
                        + "/" + branchAndPath[0] + "/" + branchAndPath[1];
            }
        }
        return trimmed;
    }

    private static CallToolResult apply(OWLModelManager mm, LoadedOntology loaded,
            boolean replaceActive, boolean copyOntologyId) {
        OWLOntology active = mm.getActiveOntology();
        List<OWLOntologyChange> changes = new ArrayList<>();
        int removedAxioms = 0;
        int removedImports = 0;
        int removedAnnotations = 0;

        if (replaceActive) {
            for (OWLAxiom ax : new LinkedHashSet<>(active.getAxioms())) {
                changes.add(new RemoveAxiom(active, ax));
                removedAxioms++;
            }
            for (OWLImportsDeclaration declaration : new LinkedHashSet<>(active.getImportsDeclarations())) {
                changes.add(new RemoveImport(active, declaration));
                removedImports++;
            }
            for (OWLAnnotation annotation : new LinkedHashSet<>(active.getAnnotations())) {
                changes.add(new RemoveOntologyAnnotation(active, annotation));
                removedAnnotations++;
            }
        }

        boolean ontologyIdCopied = false;
        String idCollisionSkip = null;
        if (copyOntologyId && !loaded.ontologyId.isAnonymous()) {
            idCollisionSkip = OntologyMetadataTools.idCollision(mm, active, loaded.ontologyId);
            if (idCollisionSkip == null) {
                changes.add(new SetOntologyID(active, loaded.ontologyId));
                ontologyIdCopied = true;
            }
        }
        for (OWLImportsDeclaration declaration : loaded.importsDeclarations) {
            changes.add(new AddImport(active, declaration));
        }
        for (OWLAnnotation annotation : loaded.annotations) {
            changes.add(new AddOntologyAnnotation(active, annotation));
        }
        for (OWLAxiom ax : loaded.axioms) {
            changes.add(new AddAxiom(active, ax));
        }

        mm.applyChanges(changes);

        StringBuilder sb = new StringBuilder();
        sb.append("Merged ontology document: ").append(loaded.source).append('\n');
        sb.append("Source ontology: ").append(ontologyLabel(loaded.ontologyId)).append('\n');
        if (replaceActive) {
            sb.append("Removed from active ontology: ")
                    .append(removedAxioms).append(" axioms, ")
                    .append(removedImports).append(" imports, ")
                    .append(removedAnnotations).append(" ontology annotations.\n");
        }
        sb.append("Copied: ")
                .append(loaded.axioms.size()).append(" axioms, ")
                .append(loaded.importsDeclarations.size()).append(" imports, ")
                .append(loaded.annotations.size()).append(" ontology annotations");
        sb.append(ontologyIdCopied ? ", ontology ID." : ".");
        sb.append('\n');
        if (idCollisionSkip != null) {
            sb.append("Skipped ontology ID copy: ").append(idCollisionSkip).append('\n');
        }
        if (!loaded.unresolvedImports.isEmpty()) {
            sb.append("Warning: ").append(loaded.unresolvedImports.size())
                    .append(" import(s) could not be resolved (declarations were still copied): ")
                    .append(String.join(", ", loaded.unresolvedImports)).append('\n');
        }
        sb.append("Active ontology now has ")
                .append(active.getAxiomCount()).append(" axioms (logical: ")
                .append(active.getLogicalAxiomCount()).append("), ")
                .append(active.getImportsDeclarations().size()).append(" direct imports, and ")
                .append(active.getAnnotations().size()).append(" ontology annotations.");
        return Tools.text(sb.toString());
    }

    private static String ontologyLabel(OWLOntologyID id) {
        if (id.isAnonymous()) {
            return "(anonymous ontology)";
        }
        StringBuilder sb = new StringBuilder();
        if (id.getOntologyIRI().isPresent()) {
            sb.append(id.getOntologyIRI().get());
        }
        if (id.getVersionIRI().isPresent()) {
            sb.append(" version ").append(id.getVersionIRI().get());
        }
        return sb.length() == 0 ? id.toString() : sb.toString();
    }

    private static final class LoadedOntology {
        final String source;
        final OWLOntologyID ontologyId;
        final Set<OWLAxiom> axioms;
        final Set<OWLImportsDeclaration> importsDeclarations;
        final Set<OWLAnnotation> annotations;
        final List<String> unresolvedImports;

        LoadedOntology(String source, OWLOntologyID ontologyId, Set<OWLAxiom> axioms,
                Set<OWLImportsDeclaration> importsDeclarations, Set<OWLAnnotation> annotations,
                List<String> unresolvedImports) {
            this.source = source;
            this.ontologyId = ontologyId;
            this.axioms = axioms;
            this.importsDeclarations = importsDeclarations;
            this.annotations = annotations;
            this.unresolvedImports = unresolvedImports;
        }
    }
}
