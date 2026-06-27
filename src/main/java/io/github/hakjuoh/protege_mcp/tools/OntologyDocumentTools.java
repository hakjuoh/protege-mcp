package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
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
import org.semanticweb.owlapi.model.parameters.OntologyCopy;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Tools that load complete OWL documents into the workspace: {@code load_ontology} opens a document
 * as its own loaded ontology and makes it active, while {@code merge_ontology_document} copies a
 * document's content into the already-active ontology.
 */
public final class OntologyDocumentTools {

    private OntologyDocumentTools() {
    }

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("load_ontology",
                "Load an OWL ontology document from a local path or document IRI/URL into the "
                        + "current Protégé workspace and make the loaded ontology active. Unlike "
                        + "merge_ontology_document, this keeps the document as its own loaded "
                        + "ontology instead of copying its contents into the previous active "
                        + "ontology. GitHub blob URLs are converted to raw.githubusercontent.com "
                        + "URLs automatically. The document is fetched off the UI thread (so a slow "
                        + "remote fetch does not freeze Protégé) and missing imports are skipped "
                        + "silently. Note: a load is not an applyChange edit, so it does NOT join "
                        + "the shared undo stack — undo_change cannot revert it.",
                Tools.schema()
                        .strReq("source", "Local file path, file: IRI, http(s) document IRI, or GitHub blob URL.")
                        .integer("connection_timeout_ms", "Remote document connection timeout (default 15000).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String source = Tools.reqString(a, "source");
                    String normalized = normalizeSource(source);
                    int timeoutMs = Tools.optInt(a, "connection_timeout_ms", 15_000);
                    CallToolResult denied = WriteTools.checkWriteAllowed(ctx,
                            "load " + normalized + " as a new ontology and make it active");
                    if (denied != null) {
                        return denied;
                    }
                    // Fetch + parse OFF the EDT: network I/O and parsing must not block the Swing UI
                    // thread (a slow remote fetch would otherwise freeze Protégé and could trip the
                    // bounded compute() wait). This mirrors Protégé's own OntologyLoader, minus its
                    // interactive modal dialogs (missing-import resolver, load-error/reload prompt),
                    // which a non-interactive MCP call must not raise.
                    LoadedDocument doc = fetch(normalized, timeoutMs);
                    // Only the cheap model wiring (move the parsed closure into Protégé's manager,
                    // switch the active ontology, refresh the UI) runs on the EDT.
                    return ctx.access().compute(mm -> attach(mm, doc), LOAD_TIMEOUT_MS);
                })));

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

    /** Wait bound for wiring a parsed document into the workspace on the EDT (move + activate + UI refresh). */
    private static final long LOAD_TIMEOUT_MS = 120_000L;

    /** Wait bound for applying a whole document's changes on the EDT (see merge_ontology_document). */
    private static final long MERGE_TIMEOUT_MS = 120_000L;

    // ------------------------------------------------------------------ load_ontology

    /**
     * Fetch and parse {@code normalized} off the EDT into a throwaway <em>concurrent</em> manager.
     * The manager must be concurrent because {@link #attach} moves the parsed ontologies into
     * Protégé's concurrent manager (a non-concurrent ontology would break Protégé's locking) — this
     * matches what Protégé's own {@code OntologyLoader} does. Missing imports are handled SILENTly and
     * recorded so {@link #attach} can warn about them.
     */
    private static LoadedDocument fetch(String normalized, int timeoutMs) {
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        List<String> unresolvedImports = new ArrayList<>();
        manager.addMissingImportListener(event -> unresolvedImports.add(event.getImportedOntologyURI().toString()));
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setFollowRedirects(true)
                .setConnectionTimeout(timeoutMs);
        try {
            OWLOntology primary = manager.loadOntologyFromOntologyDocument(documentSource(normalized), config);
            return new LoadedDocument(normalized, manager, primary,
                    manager.getOntologyDocumentIRI(primary), unresolvedImports);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not load ontology document '" + normalized + "': "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * Move the parsed document (and its import closure) into Protégé's shared manager and make the
     * primary ontology active. Replicates the success path of Protégé's {@code OntologyLoader}
     * (copyOntology MOVE for each not-already-loaded ontology, then setActiveOntology + fire
     * ONTOLOGY_LOADED), but with no modal UI. Runs on the EDT.
     */
    private static CallToolResult attach(OWLModelManager mm, LoadedDocument doc) {
        OWLOntologyManager target = mm.getOWLOntologyManager();
        OWLOntologyID primaryId = doc.primary.getOntologyID();
        OWLOntology primaryManaged = null;
        int moved = 0;
        int alreadyLoaded = 0;
        // Snapshot the closure before moving (MOVE removes ontologies from the loading manager).
        for (OWLOntology parsed : new ArrayList<>(doc.manager.getOntologies())) {
            boolean isPrimary = parsed.getOntologyID().equals(primaryId);
            if (mm.getOntologies().contains(parsed)) {
                // An ontology with this id is already open; keep Protégé's managed instance.
                alreadyLoaded++;
                if (isPrimary) {
                    primaryManaged = managedById(mm, primaryId);
                }
            } else {
                try {
                    target.copyOntology(parsed, OntologyCopy.MOVE);
                } catch (OWLOntologyCreationException e) {
                    throw new ToolArgException("Could not add the loaded ontology to the workspace: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
                moved++;
                if (isPrimary) {
                    primaryManaged = parsed;
                    if (doc.documentIri != null) {
                        target.setOntologyDocumentIRI(parsed, doc.documentIri);
                    }
                }
            }
        }
        if (primaryManaged == null) {
            OWLOntology byId = managedById(mm, primaryId);
            primaryManaged = byId != null ? byId : doc.primary;
        }

        mm.setActiveOntology(primaryManaged);
        mm.fireEvent(EventType.ONTOLOGY_LOADED);
        // Register the logical -> document IRI mapping so re-resolution (and save) finds the document.
        if (!primaryId.isAnonymous() && doc.documentIri != null && primaryId.getDefaultDocumentIRI().isPresent()) {
            target.getIRIMappers().add(new SimpleIRIMapper(primaryId.getDefaultDocumentIRI().get(), doc.documentIri));
        }

        Map<String, Object> active = new LinkedHashMap<>();
        active.put("axioms", primaryManaged.getAxiomCount());
        active.put("logical_axioms", primaryManaged.getLogicalAxiomCount());
        active.put("direct_imports", primaryManaged.getImportsDeclarations().size());
        active.put("ontology_annotations", primaryManaged.getAnnotations().size());
        return Tools.json()
                .put("loaded_document", doc.normalized)
                .put("active_ontology", ontologyLabel(primaryManaged.getOntologyID()))
                .putIfNotNull("document_iri", doc.documentIri == null ? null : doc.documentIri.toString())
                .put("active", active)
                .put("added_ontologies", moved)
                .put("already_loaded", alreadyLoaded)
                .put("workspace_ontologies", mm.getOntologies().size())
                .put("unresolved_imports", new ArrayList<>(doc.unresolvedImports))
                .put("note", "Loading is not on the undo stack (undo_change cannot revert it).")
                .result();
    }

    /** The managed ontology in Protégé with the given id, or null if none is loaded. */
    private static OWLOntology managedById(OWLModelManager mm, OWLOntologyID id) {
        for (OWLOntology o : mm.getOntologies()) {
            if (o.getOntologyID().equals(id)) {
                return o;
            }
        }
        return null;
    }

    private static final class LoadedDocument {
        final String normalized;
        final OWLOntologyManager manager;
        final OWLOntology primary;
        final IRI documentIri;
        final List<String> unresolvedImports;

        LoadedDocument(String normalized, OWLOntologyManager manager, OWLOntology primary,
                IRI documentIri, List<String> unresolvedImports) {
            this.normalized = normalized;
            this.manager = manager;
            this.primary = primary;
            this.documentIri = documentIri;
            this.unresolvedImports = unresolvedImports;
        }
    }

    // ------------------------------------------------------------------ merge_ontology_document

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
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
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

        Map<String, Object> copied = new LinkedHashMap<>();
        copied.put("axioms", loaded.axioms.size());
        copied.put("imports", loaded.importsDeclarations.size());
        copied.put("ontology_annotations", loaded.annotations.size());
        copied.put("ontology_id", ontologyIdCopied);

        Map<String, Object> activeCounts = new LinkedHashMap<>();
        activeCounts.put("axioms", active.getAxiomCount());
        activeCounts.put("logical_axioms", active.getLogicalAxiomCount());
        activeCounts.put("direct_imports", active.getImportsDeclarations().size());
        activeCounts.put("ontology_annotations", active.getAnnotations().size());

        Tools.Json json = Tools.json()
                .put("merged_document", loaded.source)
                .put("source_ontology", ontologyLabel(loaded.ontologyId))
                .put("replace_active", replaceActive);
        if (replaceActive) {
            Map<String, Object> removed = new LinkedHashMap<>();
            removed.put("axioms", removedAxioms);
            removed.put("imports", removedImports);
            removed.put("ontology_annotations", removedAnnotations);
            json.put("removed", removed);
        }
        json.put("copied", copied)
                .putIfNotNull("skipped_ontology_id", idCollisionSkip)
                .put("unresolved_imports", new ArrayList<>(loaded.unresolvedImports))
                .put("active", activeCounts);
        return json.result();
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
