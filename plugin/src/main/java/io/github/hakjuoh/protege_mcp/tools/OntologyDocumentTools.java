package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
import org.protege.xmlcatalog.owlapi.XMLCatalogIRIMapper;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.IRIDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.SetOntologyID;
import org.semanticweb.owlapi.model.UnloadableImportException;
import org.semanticweb.owlapi.model.parameters.OntologyCopy;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.github.hakjuoh.protege_mcp.core.owl.OwlParsingErrors;

/**
 * Tools that load complete OWL documents into the workspace: {@code load_ontology} opens a document
 * as its own loaded ontology and makes it active, while {@code merge_ontology_document} copies a
 * document's content into the already-active ontology.
 */
public final class OntologyDocumentTools {

    private OntologyDocumentTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("load_ontology",
                "Load an OWL ontology document from a local path or document IRI/URL into the "
                        + "current Protégé workspace and make the loaded ontology active. Unlike "
                        + "merge_ontology_document, this keeps the document as its own loaded "
                        + "ontology instead of copying its contents into the previous active "
                        + "ontology. GitHub blob URLs are converted to raw.githubusercontent.com "
                        + "URLs automatically. The document is fetched off the UI thread (so a slow "
                        + "remote fetch does not freeze Protégé). Missing imports default to warn "
                        + "(load succeeds and reports them), can fail closed with missing_imports=error, "
                        + "or can be explicitly silenced. A sibling catalog-v001.xml next to a local-file document "
                        + "first resolves its imports to local files (offline), like Protégé's own "
                        + "File ▸ Open. Note: a load is not an applyChange edit, so it does NOT join "
                        + "the shared undo stack — undo_change cannot revert it. Pass keep_active=true "
                        + "to load the document (e.g. to resolve an import) WITHOUT changing your "
                        + "current active edit target.",
                Tools.schema()
                        .strReq("source", "Local file path, file: IRI, http(s) document IRI, or GitHub blob URL.")
                        .bool("keep_active", "Keep the current active ontology instead of switching to the "
                                + "loaded document (default false). Use this to resolve an unresolved "
                                + "import without losing your edit target.")
                        .integer("connection_timeout_ms", "Remote document connection timeout (default 15000).")
                        .strEnum("missing_imports", "Missing-import policy: warn (default; report and continue), "
                                + "error (fail without changing the workspace), or silent (continue without "
                                + "reporting missing IRIs).", "warn", "error", "silent")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String source = Tools.reqString(a, "source");
                    boolean keepActive = Tools.optBool(a, "keep_active", false);
                    int timeoutMs = Tools.optInt(a, "connection_timeout_ms", 15_000);
                    MissingImportsMode missingImports = MissingImportsMode.parse(
                            Tools.optString(a, "missing_imports"));
                    return doLoad(ctx, source, timeoutMs, keepActive, missingImports);
                }));

        tools.tool("merge_ontology_document",
                "Load an OWL ontology document from a local path or document IRI/URL and copy its "
                        + "axioms, direct import declarations, and ontology annotations into the "
                        + "active ontology. This preserves axiom annotations and axiom types that "
                        + "are not exposed by the structured add_axiom tool. GitHub blob URLs are "
                        + "converted to raw.githubusercontent.com URLs automatically. Pass "
                        + "preview=true to fetch and parse the document but only REPORT what the "
                        + "merge would do, without changing anything.",
                Tools.schema()
                        .strReq("source", "Local file path, file: IRI, http(s) document IRI, or GitHub blob URL.")
                        .bool("replace_active", "Remove active ontology axioms/imports/ontology annotations first "
                                + "(default false).")
                        .bool("copy_ontology_id", "Copy source ontology IRI/version to the active ontology. "
                                + "Defaults to replace_active.")
                        .bool("preview", "Dry-run: report what the merge would copy/remove without "
                                + "applying anything (works in read-only mode). Default false.")
                        .integer("connection_timeout_ms", "Remote document connection timeout (default 15000).")
                        .strEnum("missing_imports", "Missing-import policy: warn (default; report and continue), "
                                + "error (fail without changing the active ontology), or silent (continue "
                                + "without reporting missing IRIs).", "warn", "error", "silent")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String source = Tools.reqString(a, "source");
                    boolean replaceActive = Tools.optBool(a, "replace_active", false);
                    boolean copyOntologyId = Tools.optBool(a, "copy_ontology_id", replaceActive);
                    boolean preview = Tools.optBool(a, "preview", false);
                    int timeoutMs = Tools.optInt(a, "connection_timeout_ms", 15_000);
                    MissingImportsMode missingImports = MissingImportsMode.parse(
                            Tools.optString(a, "missing_imports"));
                    String summary = replaceActive
                            ? "replace active ontology — delete ALL its axioms, imports and ontology "
                                    + "annotations — with " + source
                            : "merge ontology document " + source;
                    if (!preview) {
                        CallToolResult denied = WriteTools.checkWriteAllowed(ctx, summary);
                        if (denied != null) {
                            return denied;
                        }
                    }
                    List<ImportMapping> workspaceMappings = ctx.access().compute(
                            OntologyDocumentTools::workspaceImportMappings);
                    LoadedOntology loaded = load(source, timeoutMs, missingImports,
                            workspaceMappings);
                    // A whole-document merge applies thousands of changes in one EDT batch, so give it a
                    // longer bound than the default — otherwise a slow-but-succeeding apply is reported
                    // as a timeout while the model keeps mutating.
                    return ctx.access().compute(
                            mm -> apply(mm, loaded, replaceActive, copyOntologyId, preview),
                            MERGE_TIMEOUT_MS);
                }));

        tools.tool("set_active_ontology",
                "Select which already-loaded ontology is the ACTIVE edit target (every edit tool writes "
                        + "to the active ontology). Resolve by ontology IRI or version IRI (see "
                        + "list_ontologies). Unlike load_ontology this loads/fetches nothing — it just "
                        + "switches focus among ontologies already in the workspace, e.g. back to your "
                        + "module after load_ontology brought an imported ontology into the workspace.",
                Tools.schema()
                        .strReq("ontology_iri", "Ontology IRI or version IRI of a loaded ontology "
                                + "(see list_ontologies).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String ref = Tools.reqString(a, "ontology_iri");
                    return ctx.access().compute(mm -> {
                        OWLOntology target = findLoadedOntology(mm, ref);
                        if (target == null) {
                            return Tools.error("No loaded ontology matches '" + ref + "'. See "
                                    + "list_ontologies for the loaded ontology ids.");
                        }
                        boolean changed = !target.equals(mm.getActiveOntology());
                        if (changed) {
                            mm.setActiveOntology(target);
                            mm.fireEvent(EventType.ACTIVE_ONTOLOGY_CHANGED);
                        }
                        return Tools.json()
                                .put("active_ontology", ontologyLabel(target.getOntologyID()))
                                .put("changed", changed)
                                .put("axioms", target.getAxiomCount())
                                .put("logical_axioms", target.getLogicalAxiomCount())
                                .put("direct_imports", target.getImportsDeclarations().size())
                                .result();
                    });
                }));

        tools.tool("create_ontology",
                "Create a brand-new empty ontology in the workspace and make it the active edit target "
                        + "(so add_axiom/create_* write into it). Give its 'ontology_iri' and optionally a "
                        + "'version_iri'. Pass 'path' to bind it to a file now (so an argument-less "
                        + "save_ontology writes there, and write_catalog has a folder); otherwise it is "
                        + "untitled until you save_ontology with a path. Use this to start a new module "
                        + "before adding imports/axioms; set_ontology_id changes an existing ontology's "
                        + "id, whereas this mints a new one. Creating an ontology is not on the undo stack.",
                Tools.schema()
                        .strReq("ontology_iri", "IRI of the new ontology.")
                        .str("version_iri", "Optional version IRI.")
                        .str("path", "Optional file path to bind the new ontology to (e.g. "
                                + "/tmp/mymodule/MyModule.rdf).")
                        .bool("keep_active", "Keep the current active ontology instead of switching to the "
                                + "new one (default false).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String ontologyIri = Tools.reqString(a, "ontology_iri");
                    String versionIri = Tools.optString(a, "version_iri");
                    String path = Tools.optString(a, "path");
                    boolean keepActive = Tools.optBool(a, "keep_active", false);
                    CallToolResult denied = WriteTools.checkWriteAllowed(ctx,
                            "create new ontology " + ontologyIri);
                    if (denied != null) {
                        return denied;
                    }
                    return ctx.access().compute(mm -> createOntology(mm, ontologyIri, versionIri, path,
                            keepActive));
                }));
    }

    /**
     * Mint a new empty ontology via Protégé's {@link OWLModelManager#createNewOntology} (which sets it
     * active and fires ONTOLOGY_CREATED). When {@code path} is given the ontology is bound to that file
     * document; with {@code keepActive} the previously-active ontology is restored as the edit target.
     */
    private static CallToolResult createOntology(OWLModelManager mm, String ontologyIri, String versionIri,
            String path, boolean keepActive) {
        OWLOntologyID newId = versionIri != null
                ? new OWLOntologyID(IRI.create(ontologyIri), IRI.create(versionIri))
                : new OWLOntologyID(IRI.create(ontologyIri));
        String collision = OntologyMetadataTools.idCollision(mm, mm.getActiveOntology(), newId);
        if (collision != null) {
            return Tools.error(collision);
        }
        OWLOntology prevActive = mm.getActiveOntology();
        URI physicalUri = null;
        if (path != null) {
            File file = new File(path).getAbsoluteFile();
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return Tools.error("Cannot create directory: " + dir);
            }
            physicalUri = file.toURI();
        }
        OWLOntology created;
        try {
            created = mm.createNewOntology(newId, physicalUri);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not create ontology " + ontologyIri + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        boolean keptActive = keepActive && prevActive != null && !prevActive.equals(created)
                && mm.getOntologies().contains(prevActive);
        if (keptActive) {
            mm.setActiveOntology(prevActive);
        }
        OWLOntology active = keptActive ? prevActive : created;
        return Tools.json()
                .put("created_ontology", ontologyLabel(created.getOntologyID()))
                .put("active_ontology", ontologyLabel(active.getOntologyID()))
                .put("kept_active", keptActive)
                .putIfNotNull("document_iri",
                        physicalUri == null ? null : mm.getOWLOntologyManager()
                                .getOntologyDocumentIRI(created).toString())
                .put("workspace_ontologies", mm.getOntologies().size())
                .put("note", "New ontology created; this is not on the undo stack (undo_change cannot "
                        + "revert it).")
                .result();
    }

    /** Find a loaded ontology by ontology IRI or version IRI string (exact match); null matches nothing. */
    static OWLOntology findLoadedOntology(OWLModelManager mm, String ref) {
        if (ref == null) {
            return null;
        }
        for (OWLOntology o : mm.getOntologies()) {
            OWLOntologyID id = o.getOntologyID();
            if (id.getOntologyIRI().isPresent() && id.getOntologyIRI().get().toString().equals(ref)) {
                return o;
            }
            if (id.getVersionIRI().isPresent() && id.getVersionIRI().get().toString().equals(ref)) {
                return o;
            }
        }
        return null;
    }

    /**
     * Fetch + parse {@code source} off the EDT, then wire it into the workspace on the EDT. When
     * {@code keepActive} is set the previously-active ontology stays active (the document is loaded
     * only to resolve imports / be available), otherwise the loaded primary becomes active.
     */
    static CallToolResult doLoad(ToolContext ctx, String source, int timeoutMs, boolean keepActive) {
        return doLoad(ctx, source, timeoutMs, keepActive, MissingImportsMode.WARN);
    }

    static CallToolResult doLoad(ToolContext ctx, String source, int timeoutMs, boolean keepActive,
            MissingImportsMode missingImports) {
        String normalized = normalizeSource(source);
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx,
                "load " + normalized + (keepActive ? " into the workspace" : " and make it active"));
        if (denied != null) {
            return denied;
        }
        // Fetch + parse OFF the EDT: network I/O and parsing must not block the Swing UI thread (a
        // slow remote fetch would otherwise freeze Protégé and could trip the bounded compute() wait).
        // This mirrors Protégé's own OntologyLoader, minus its interactive modal dialogs.
        List<ImportMapping> workspaceMappings = ctx.access().compute(
                OntologyDocumentTools::workspaceImportMappings);
        LoadedDocument doc = fetch(normalized, timeoutMs, missingImports, workspaceMappings);
        // Only the cheap model wiring (move the parsed closure in, switch active, refresh) runs on EDT.
        return ctx.access().compute(mm -> attach(mm, doc, keepActive), LOAD_TIMEOUT_MS);
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
     * matches what Protégé's own {@code OntologyLoader} does. Missing imports follow the caller's
     * compatibility mode and are recorded when warning/reporting is enabled.
     */
    private static LoadedDocument fetch(String normalized, int timeoutMs,
            MissingImportsMode missingImports, List<ImportMapping> workspaceMappings) {
        OWLOntologyManager manager = OwlManagers.createConcurrent();
        List<String> unresolvedImports = new ArrayList<>();
        manager.addMissingImportListener(event -> unresolvedImports.add(event.getImportedOntologyURI().toString()));
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(missingImports.strategy())
                .setFollowRedirects(true)
                .setConnectionTimeout(timeoutMs);
        try (UnsupportedImportFallback fallback = UnsupportedImportFallback.install(manager)) {
            addWorkspaceImportMappers(manager, workspaceMappings);
            // Register the source-adjacent catalog last: OWLAPI gives the most recently-added mapper
            // priority, so version-controlled project resolution overrides stale interactive hints.
            addFolderCatalogMapper(manager, normalized);
            OWLOntology primary = manager.loadOntologyFromOntologyDocument(documentSource(normalized), config);
            finishUnsupportedImports(primary, fallback, unresolvedImports, missingImports, normalized);
            return new LoadedDocument(normalized, manager, primary,
                    manager.getOntologyDocumentIRI(primary), unresolvedImports,
                    resolvedImportEdges(manager), missingImports);
        } catch (UnloadableImportException e) {
            throw strictImportFailure(normalized, e);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not load ontology document '" + normalized + "': "
                    + OwlParsingErrors.conciseMessage(e));
        }
    }

    /**
     * Move the parsed document (and its import closure) into Protégé's shared manager and make the
     * primary ontology active. Replicates the success path of Protégé's {@code OntologyLoader}
     * (copyOntology MOVE for each not-already-loaded ontology, then setActiveOntology + fire
     * ONTOLOGY_LOADED), but with no modal UI. Runs on the EDT.
     */
    private static CallToolResult attach(OWLModelManager mm, LoadedDocument doc, boolean keepActive) {
        OWLOntology prevActive = mm.getActiveOntology();
        OWLOntologyManager target = mm.getOWLOntologyManager();
        OWLOntologyID primaryId = doc.primary.getOntologyID();
        OWLOntology primaryManaged = null;
        int moved = 0;
        int alreadyLoaded = 0;
        for (ResolvedImport edge : doc.resolvedEdges) {
            OWLOntology existing = target.getImportedOntology(edge.declaration);
            if (existing != null && !existing.getOntologyID().equals(edge.targetId)) {
                throw new ToolArgException("Could not attach the loaded import closure: import '"
                        + edge.declaration.getIRI() + "' already resolves in the workspace to "
                        + ontologyLabel(existing.getOntologyID()) + " instead of "
                        + ontologyLabel(edge.targetId) + ".");
            }
        }
        List<OWLOntology> movedOntologies = new ArrayList<>();
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
                movedOntologies.add(parsed);
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

        List<OWLOntologyIRIMapper> attachedMappers = new ArrayList<>();
        try {
            restoreResolvedImportEdges(mm, doc.resolvedEdges, attachedMappers);
        } catch (RuntimeException failure) {
            attachedMappers.forEach(target.getIRIMappers()::remove);
            // Activation has not happened yet. Move only the ontologies added by this call back into
            // their throwaway manager so an attachment failure leaves the live workspace unchanged.
            for (int i = movedOntologies.size() - 1; i >= 0; i--) {
                OWLOntology movedOntology = movedOntologies.get(i);
                OWLOntology managed = managedById(mm, movedOntology.getOntologyID());
                if (managed != null) {
                    try {
                        doc.manager.copyOntology(managed, OntologyCopy.MOVE);
                    } catch (OWLOntologyCreationException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
            }
            throw failure;
        }

        // Activate the loaded primary FIRST: a real setActiveOntology recomputes Protégé's
        // active-ontologies (imports-closure) cache that the entity finder and renderers read. A no-op
        // setActiveOntology to the already-active ontology does NOT recompute it — which is why merely
        // firing ACTIVE_ONTOLOGY_CHANGED while keeping the active ontology is not enough.
        mm.setActiveOntology(primaryManaged);
        mm.fireEvent(EventType.ONTOLOGY_LOADED);
        // Register the primary logical -> document IRI mapping so later direct loads/save find it.
        if (!primaryId.isAnonymous() && doc.documentIri != null && primaryId.getDefaultDocumentIRI().isPresent()) {
            addSimpleMapperIfNeeded(target, primaryId.getDefaultDocumentIRI().get(), doc.documentIri);
        }
        // keep_active: switch BACK to the caller's prior edit target. This SECOND real setActiveOntology
        // recomputes the closure for prevActive — now including the just-loaded document — so its
        // imported terms resolve by name (search_entities / Manchester) with no manual round-trip.
        boolean keptActive = keepActive && prevActive != null && !prevActive.equals(primaryManaged)
                && mm.getOntologies().contains(prevActive);
        OWLOntology finalActive = keptActive ? prevActive : primaryManaged;
        if (keptActive) {
            mm.setActiveOntology(prevActive);
        }
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("axioms", finalActive.getAxiomCount());
        active.put("logical_axioms", finalActive.getLogicalAxiomCount());
        active.put("direct_imports", finalActive.getImportsDeclarations().size());
        active.put("ontology_annotations", finalActive.getAnnotations().size());
        ImportTools.ImportReport workspaceImports = ImportTools.analyze(primaryManaged);
        return Tools.json()
                .put("loaded_document", doc.normalized)
                .put("loaded_ontology", ontologyLabel(primaryManaged.getOntologyID()))
                .put("loaded_axioms", primaryManaged.getAxiomCount())
                .put("active_ontology", ontologyLabel(finalActive.getOntologyID()))
                .put("kept_active", keptActive)
                .putIfNotNull("document_iri", doc.documentIri == null ? null : doc.documentIri.toString())
                .put("active", active)
                .put("added_ontologies", moved)
                .put("already_loaded", alreadyLoaded)
                .put("workspace_ontologies", mm.getOntologies().size())
                .put("missing_imports_mode", doc.missingImports.value())
                .put("resolved_imports", workspaceImports.resolvedImports)
                .put("unresolved_imports", doc.missingImports.reported(doc.unresolvedImports))
                .put("note", keptActive
                        ? "Loaded into the workspace; active ontology unchanged. Loading is not on the "
                                + "undo stack (undo_change cannot revert it)."
                        : "Loading is not on the undo stack (undo_change cannot revert it).")
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

    /** Snapshot the loader manager's declaration-to-target cache before MOVE discards it. */
    private static List<ResolvedImport> resolvedImportEdges(OWLOntologyManager manager) {
        List<ResolvedImport> edges = new ArrayList<>();
        List<OWLOntology> sources = new ArrayList<>(manager.getOntologies());
        sources.sort((left, right) -> ontologyLabel(left.getOntologyID())
                .compareTo(ontologyLabel(right.getOntologyID())));
        for (OWLOntology source : sources) {
            List<OWLImportsDeclaration> declarations = new ArrayList<>(source.getImportsDeclarations());
            declarations.sort((left, right) -> left.getIRI().toString()
                    .compareTo(right.getIRI().toString()));
            for (OWLImportsDeclaration declaration : declarations) {
                OWLOntology imported = manager.getImportedOntology(declaration);
                if (imported != null) {
                    edges.add(new ResolvedImport(source.getOntologyID(), declaration,
                            imported.getOntologyID()));
                }
            }
        }
        return List.copyOf(edges);
    }

    /** Rebuild the declaration cache that OWLAPI's {@code copyOntology(MOVE)} does not transfer. */
    private static void restoreResolvedImportEdges(OWLModelManager mm, List<ResolvedImport> edges,
            List<OWLOntologyIRIMapper> addedMappers) {
        OWLOntologyManager manager = mm.getOWLOntologyManager();
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(
                        org.semanticweb.owlapi.model.MissingImportHandlingStrategy.THROW_EXCEPTION)
                .setFollowRedirects(false);
        for (ResolvedImport edge : edges) {
            OWLOntology expected = managedById(mm, edge.targetId);
            if (expected == null) {
                throw new ToolArgException("Could not attach the loaded import closure: resolved target "
                        + ontologyLabel(edge.targetId) + " is absent from the workspace.");
            }
            OWLOntology actual = manager.getImportedOntology(edge.declaration);
            if (actual == null) {
                IRI document = manager.getOntologyDocumentIRI(expected);
                OWLOntologyIRIMapper mapper = addSimpleMapperIfNeeded(
                        manager, edge.declaration.getIRI(), document);
                if (mapper != null) {
                    addedMappers.add(mapper);
                }
                manager.makeLoadImportRequest(edge.declaration, config);
                actual = manager.getImportedOntology(edge.declaration);
                if (actual == null) {
                    actual = retryAfterClearingStaleImportMarker(mm, manager, edge, config);
                }
            }
            if (actual == null || !actual.getOntologyID().equals(edge.targetId)) {
                throw new ToolArgException("Could not attach the loaded import closure: import '"
                        + edge.declaration.getIRI() + "' did not reconnect to "
                        + ontologyLabel(edge.targetId) + ". Likely cause: an earlier failed load of "
                        + "that import IRI left a stale failed-import marker on this workspace "
                        + "manager, which turns later load requests for the IRI into silent no-ops.");
            }
        }
    }

    /**
     * Clear a stale failed-import marker and retry the reconnect once. In OWLAPI 4.5.29,
     * {@code OWLOntologyManagerImpl.makeLoadImportRequest} is entirely guarded by
     * {@code !importedIRIs.containsKey(iri)}, and a FAILED live-manager load of an import IRI leaves
     * a permanent raw marker under that key (the put precedes {@code loadImports}; failure never
     * removes it, and {@code removeOntology} only clears OntologyID values) — so a later request for
     * the same IRI is a silent no-op. The only change that clears the key is a {@code RemoveImport}
     * (see {@code checkForImportsChange}); re-adding the declaration restores the source ontology
     * unchanged WITHOUT triggering a load (an {@code AddImport} only re-maps against
     * already-managed ontologies), after which the load request can finally run.
     */
    private static OWLOntology retryAfterClearingStaleImportMarker(OWLModelManager mm,
            OWLOntologyManager manager, ResolvedImport edge, OWLOntologyLoaderConfiguration config) {
        OWLOntology sourceOntology = managedById(mm, edge.sourceId);
        if (sourceOntology == null
                || !sourceOntology.getImportsDeclarations().contains(edge.declaration)) {
            return null;
        }
        manager.applyChanges(List.of(
                new RemoveImport(sourceOntology, edge.declaration),
                new AddImport(sourceOntology, edge.declaration)));
        manager.makeLoadImportRequest(edge.declaration, config);
        return manager.getImportedOntology(edge.declaration);
    }

    /** Add a highest-priority exact mapper unless the currently effective mapping is already exact. */
    private static OWLOntologyIRIMapper addSimpleMapperIfNeeded(OWLOntologyManager manager,
            IRI logical, IRI document) {
        for (OWLOntologyIRIMapper existing : manager.getIRIMappers()) {
            IRI mapped;
            try {
                mapped = existing.getDocumentIRI(logical);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (mapped != null) {
                if (document.equals(mapped)) {
                    return null;
                }
                break;
            }
        }
        SimpleIRIMapper mapper = new SimpleIRIMapper(logical, document);
        manager.getIRIMappers().add(mapper);
        return mapper;
    }

    private record ResolvedImport(OWLOntologyID sourceId, OWLImportsDeclaration declaration,
            OWLOntologyID targetId) { }

    private static final class LoadedDocument {
        final String normalized;
        final OWLOntologyManager manager;
        final OWLOntology primary;
        final IRI documentIri;
        final List<String> unresolvedImports;
        final List<ResolvedImport> resolvedEdges;
        final MissingImportsMode missingImports;

        LoadedDocument(String normalized, OWLOntologyManager manager, OWLOntology primary,
                IRI documentIri, List<String> unresolvedImports,
                List<ResolvedImport> resolvedEdges, MissingImportsMode missingImports) {
            this.normalized = normalized;
            this.manager = manager;
            this.primary = primary;
            this.documentIri = documentIri;
            this.unresolvedImports = unresolvedImports;
            this.resolvedEdges = resolvedEdges;
            this.missingImports = missingImports;
        }
    }

    // ------------------------------------------------------------------ merge_ontology_document

    private static LoadedOntology load(String source, int timeoutMs) {
        return load(source, timeoutMs, MissingImportsMode.WARN, List.of());
    }

    private static LoadedOntology load(String source, int timeoutMs,
            MissingImportsMode missingImports, List<ImportMapping> workspaceMappings) {
        String normalized = normalizeSource(source);
        OWLOntologyManager manager = OwlManagers.create();
        // WARN and SILENT both let OWLAPI finish parsing; ERROR makes an unresolved import abort
        // before any change can be applied to the live active ontology.
        List<String> unresolvedImports = new ArrayList<>();
        manager.addMissingImportListener(event -> unresolvedImports.add(event.getImportedOntologyURI().toString()));
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(missingImports.strategy())
                .setFollowRedirects(true)
                .setConnectionTimeout(timeoutMs);
        try (UnsupportedImportFallback fallback = UnsupportedImportFallback.install(manager)) {
            addWorkspaceImportMappers(manager, workspaceMappings);
            // The sibling catalog is the project source of truth and must override workspace hints.
            addFolderCatalogMapper(manager, normalized);
            OWLOntology sourceOntology = manager.loadOntologyFromOntologyDocument(
                    documentSource(normalized), config);
            finishUnsupportedImports(sourceOntology, fallback, unresolvedImports, missingImports, normalized);
            ImportTools.ImportReport imports = ImportTools.analyze(sourceOntology);
            return new LoadedOntology(
                    normalized,
                    sourceOntology.getOntologyID(),
                    new LinkedHashSet<>(sourceOntology.getAxioms()),
                    new LinkedHashSet<>(sourceOntology.getImportsDeclarations()),
                    new LinkedHashSet<>(sourceOntology.getAnnotations()),
                    unresolvedImports, imports.resolvedImports, missingImports);
        } catch (UnloadableImportException e) {
            throw strictImportFailure(normalized, e);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not load ontology document '" + normalized + "': "
                    + OwlParsingErrors.conciseMessage(e));
        }
    }

    /**
     * OWLAPI 4 applies {@link MissingImportHandlingStrategy} to ordinary document failures, but an
     * import IRI with no URL/document handler (for example {@code urn:example:missing}) escapes earlier
     * as an unchecked
     * {@code OWLOntologyFactoryNotFoundException}. Let higher-priority workspace/catalog mappers resolve
     * it first; if none does, this last-resort mapper temporarily satisfies only unsupported schemes from one
     * private empty document. After the root parse completes the placeholder is removed and every such
     * IRI is restored to the caller's unresolved-import list, so warn/silent/error retain their documented
     * semantics and no placeholder can enter the Protégé workspace.
     */
    private static final class UnsupportedImportFallback implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final Path placeholder;
        private final IRI placeholderIri;
        private final Set<String> unresolved = new LinkedHashSet<>();
        private final OWLOntologyIRIMapper mapper;

        private UnsupportedImportFallback(OWLOntologyManager manager, Path placeholder) {
            this.manager = manager;
            this.placeholder = placeholder;
            this.placeholderIri = IRI.create(placeholder.toUri());
            this.mapper = logical -> {
                URI uri;
                try {
                    uri = logical.toURI();
                } catch (RuntimeException invalid) {
                    unresolved.add(logical.toString());
                    return placeholderIri;
                }
                if (hasUrlHandler(uri)) {
                    return null;
                }
                unresolved.add(logical.toString());
                return placeholderIri;
            };
            // Added FIRST. OWLAPI's priority collection consults later workspace/catalog mappers first,
            // so a legitimate local mapping for a URN wins and never reaches this fallback.
            manager.getIRIMappers().add(mapper);
        }

        private static boolean hasUrlHandler(URI uri) {
            try {
                // URI.toURL() resolves the protocol handler but does not open a connection. This
                // matches OWLAPI's supported http/https/file/ftp/jar boundary without network I/O.
                uri.toURL();
                return true;
            } catch (IOException | IllegalArgumentException unsupported) {
                return false;
            }
        }

        static UnsupportedImportFallback install(OWLOntologyManager manager) {
            try {
                Path placeholder = Files.createTempFile("protege-mcp-unsupported-import-", ".ofn");
                Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
                return new UnsupportedImportFallback(manager, placeholder);
            } catch (IOException e) {
                throw new ToolArgException("Could not prepare isolated missing-import handling: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }

        List<String> unresolved() {
            return List.copyOf(unresolved);
        }

        void removePlaceholderOntology() {
            for (OWLOntology ontology : new ArrayList<>(manager.getOntologies())) {
                if (placeholderIri.equals(manager.getOntologyDocumentIRI(ontology))) {
                    manager.removeOntology(ontology);
                }
            }
        }

        @Override
        public void close() {
            manager.getIRIMappers().remove(mapper);
            removePlaceholderOntology();
            try {
                Files.deleteIfExists(placeholder);
            } catch (IOException ignored) {
                // Private empty file only; cleanup must not mask the actual ontology result.
            }
        }
    }

    private static void finishUnsupportedImports(OWLOntology primary,
            UnsupportedImportFallback fallback, List<String> unresolvedImports,
            MissingImportsMode mode, String normalized) {
        fallback.removePlaceholderOntology();
        // Streaming parsers can encounter a URN before assigning the importing ontology's ID, not
        // only for the root but for any closure member. Judge the completed graph after removing the
        // private placeholder so a child self-import or a URN-before-document import is not reported
        // as both resolved and unresolved.
        Set<String> resolved = new LinkedHashSet<>();
        for (Map<String, Object> row : ImportTools.analyze(primary).resolvedImports) {
            Object iri = row.get("import_iri");
            if (iri != null) {
                resolved.add(String.valueOf(iri));
            }
        }
        unresolvedImports.removeIf(resolved::contains);
        List<String> unsupported = fallback.unresolved().stream()
                .filter(iri -> !resolved.contains(iri))
                .toList();
        for (String iri : unsupported) {
            if (!unresolvedImports.contains(iri)) {
                unresolvedImports.add(iri);
            }
        }
        if (mode == MissingImportsMode.ERROR && !unsupported.isEmpty()) {
            throw new ToolArgException("Could not load ontology document '" + normalized
                    + "': required import '" + unsupported.get(0)
                    + "' could not be loaded: no OWL document factory can dereference that IRI.");
        }
    }

    private static ToolArgException strictImportFailure(String normalized,
            UnloadableImportException failure) {
        String importIri = failure.getImportsDeclaration() == null
                ? "(unknown)" : failure.getImportsDeclaration().getIRI().toString();
        OWLOntologyCreationException cause = failure.getOntologyCreationException();
        String detail = OwlParsingErrors.conciseMessage(cause == null ? failure : cause);
        return new ToolArgException("Could not load ontology document '" + normalized
                + "': required import '" + importIri + "' could not be loaded"
                + (detail == null ? "." : ": " + detail));
    }

    /**
     * Snapshot reusable logical/version/declaration IRI mappings from the live workspace. The
     * throwaway loaders receive only immutable IRI pairs, never live ontology objects, so parsing
     * remains off the EDT while already-loaded file/http documents can resolve exactly as they do in
     * the workspace. Untitled {@code owlapi:} documents are deliberately excluded: they are not
     * reproducible document sources and strict loading must not pretend otherwise.
     */
    static List<ImportMapping> workspaceImportMappings(OWLModelManager mm) {
        OWLOntologyManager manager = mm.getOWLOntologyManager();
        Map<String, String> mappings = new TreeMap<>();
        List<OWLOntology> ontologies = new ArrayList<>(manager.getOntologies());
        ontologies.sort((left, right) -> ontologyLabel(left.getOntologyID())
                .compareTo(ontologyLabel(right.getOntologyID())));

        // Preserve the manager's actual declaration resolution first. This also covers catalogs
        // where an owl:imports document IRI differs from the loaded ontology's logical IRI.
        for (OWLOntology source : ontologies) {
            List<OWLImportsDeclaration> declarations = new ArrayList<>(source.getImportsDeclarations());
            declarations.sort((left, right) -> left.getIRI().toString()
                    .compareTo(right.getIRI().toString()));
            for (OWLImportsDeclaration declaration : declarations) {
                OWLOntology target = manager.getImportedOntology(declaration);
                if (target != null) {
                    putReusableMapping(mappings, declaration.getIRI(),
                            manager.getOntologyDocumentIRI(target));
                }
            }
        }
        for (OWLOntology ontology : ontologies) {
            IRI document = manager.getOntologyDocumentIRI(ontology);
            OWLOntologyID id = ontology.getOntologyID();
            if (id.getOntologyIRI().isPresent()) {
                putReusableMapping(mappings, id.getOntologyIRI().get(), document);
            }
            if (id.getVersionIRI().isPresent()) {
                putReusableMapping(mappings, id.getVersionIRI().get(), document);
            }
        }

        List<ImportMapping> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            out.add(new ImportMapping(IRI.create(entry.getKey()), IRI.create(entry.getValue())));
        }
        return out;
    }

    private static void putReusableMapping(Map<String, String> mappings, IRI logical, IRI document) {
        if (logical == null || document == null || !reusableDocument(document)) {
            return;
        }
        mappings.putIfAbsent(logical.toString(), document.toString());
    }

    private static boolean reusableDocument(IRI document) {
        try {
            String scheme = document.toURI().getScheme();
            return "file".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void addWorkspaceImportMappers(OWLOntologyManager manager,
            List<ImportMapping> mappings) {
        for (ImportMapping mapping : mappings) {
            manager.getIRIMappers().add(new SimpleIRIMapper(mapping.logical, mapping.document));
        }
    }

    static final class ImportMapping {
        final IRI logical;
        final IRI document;

        ImportMapping(IRI logical, IRI document) {
            this.logical = logical;
            this.document = document;
        }
    }

    static OWLOntologyDocumentSource documentSource(String source) {
        // An existing local file (plain path or file: URI) wins over IRI parsing — this also handles a
        // Windows drive path like "C:/x.rdf", which java.net.URI would otherwise treat as scheme "c".
        File asFile = localFile(source);
        if (asFile != null) {
            return new FileDocumentSource(asFile.getAbsoluteFile());
        }
        IRI iri = looksLikeWindowsPath(source) ? null : Tools.asIri(source);
        if (iri != null) {
            return new IRIDocumentSource(iri);
        }
        throw new ToolArgException("Ontology document is not a readable file and is not an absolute IRI: "
                + new File(source).getAbsoluteFile());
    }

    /**
     * The local {@link File} a source string denotes — a plain path OR a {@code file:} URI — or
     * {@code null} if it is not an existing local file. Shared so a path and a {@code file:} IRI are
     * treated identically everywhere (document source AND sibling-catalog resolution).
     */
    static File localFile(String source) {
        if (source == null) {
            return null;
        }
        File asPath = new File(source);
        if (asPath.isFile()) {
            return asPath;
        }
        try {
            URI uri = new URI(source);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                File asUri = new File(uri);
                if (asUri.isFile()) {
                    return asUri;
                }
            }
        } catch (Exception ignored) {
            // not a file: URI
        }
        return null;
    }

    /** A bare Windows drive path such as {@code C:\x} or {@code C:/x} — not a document IRI. */
    private static boolean looksLikeWindowsPath(String source) {
        return source.length() >= 2 && Character.isLetter(source.charAt(0)) && source.charAt(1) == ':';
    }

    /**
     * If {@code source} is a local file (plain path or {@code file:} URI) with a sibling Protégé
     * catalog ({@code catalog-v001.xml}) in its folder, register an {@link XMLCatalogIRIMapper} on
     * {@code manager} so the document's owl:imports resolve to the local files the catalog maps them
     * to, like Protégé's File ▸ Open (offline import resolution). An import declaration whose IRI
     * matches the imported ontology's IRI/version links in-memory after load; a bare-document-URL
     * declaration loads the document, and reopening through the catalog resolves it. No-op for a
     * non-file source or when no catalog is present; a malformed catalog is ignored, not fatal.
     */
    static void addFolderCatalogMapper(OWLOntologyManager manager, String source) {
        File asFile = localFile(source);
        if (asFile == null) {
            return;
        }
        File folder = asFile.getAbsoluteFile().getParentFile();
        if (folder == null) {
            return;
        }
        File catalog = new File(folder, "catalog-v001.xml");
        if (!catalog.isFile()) {
            return;
        }
        try {
            manager.getIRIMappers().add(new XMLCatalogIRIMapper(catalog));
        } catch (IOException | RuntimeException ignored) {
            // A missing/malformed catalog must not block loading the document itself.
        }
    }

    static String normalizeSource(String source) {
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
            boolean replaceActive, boolean copyOntologyId, boolean preview) {
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

        // In a plain merge, adds of already-asserted axioms are no-ops — count them so a preview
        // says how much of the document is actually new. Irrelevant under replace_active (the
        // active axioms are all queued for removal first).
        int alreadyPresent = 0;
        if (preview && !replaceActive) {
            for (OWLAxiom ax : loaded.axioms) {
                if (active.containsAxiom(ax)) {
                    alreadyPresent++;
                }
            }
        }

        if (!preview) {
            mm.applyChanges(changes);
        }

        Map<String, Object> copied = new LinkedHashMap<>();
        copied.put("axioms", loaded.axioms.size());
        copied.put("imports", loaded.importsDeclarations.size());
        copied.put("ontology_annotations", loaded.annotations.size());
        copied.put("ontology_id", ontologyIdCopied);

        Tools.Json json = Tools.json();
        if (preview) {
            json.put("preview", true);
        }
        json.put("merged_document", loaded.source)
                .put("source_ontology", ontologyLabel(loaded.ontologyId))
                .put("replace_active", replaceActive);
        if (replaceActive) {
            Map<String, Object> removed = new LinkedHashMap<>();
            removed.put("axioms", removedAxioms);
            removed.put("imports", removedImports);
            removed.put("ontology_annotations", removedAnnotations);
            json.put(preview ? "would_remove" : "removed", removed);
        }
        json.put(preview ? "would_copy" : "copied", copied)
                .putIfNotNull("skipped_ontology_id", idCollisionSkip)
                .put("missing_imports_mode", loaded.missingImports.value())
                .put("resolved_imports", loaded.resolvedImports)
                .put("unresolved_imports", loaded.missingImports.reported(loaded.unresolvedImports));
        if (preview) {
            if (!replaceActive) {
                json.put("already_present_axioms", alreadyPresent);
            }
            return json.put("total_changes", changes.size())
                    .put("note", "Nothing was changed. Re-run without 'preview' to apply the "
                            + "merge as one undoable change.")
                    .result();
        }

        Map<String, Object> activeCounts = new LinkedHashMap<>();
        activeCounts.put("axioms", active.getAxiomCount());
        activeCounts.put("logical_axioms", active.getLogicalAxiomCount());
        activeCounts.put("direct_imports", active.getImportsDeclarations().size());
        activeCounts.put("ontology_annotations", active.getAnnotations().size());
        json.put("active", activeCounts);
        return json.result();
    }

    static String ontologyLabel(OWLOntologyID id) {
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
        final List<Map<String, Object>> resolvedImports;
        final MissingImportsMode missingImports;

        LoadedOntology(String source, OWLOntologyID ontologyId, Set<OWLAxiom> axioms,
                Set<OWLImportsDeclaration> importsDeclarations, Set<OWLAnnotation> annotations,
                List<String> unresolvedImports, List<Map<String, Object>> resolvedImports,
                MissingImportsMode missingImports) {
            this.source = source;
            this.ontologyId = ontologyId;
            this.axioms = axioms;
            this.importsDeclarations = importsDeclarations;
            this.annotations = annotations;
            this.unresolvedImports = unresolvedImports;
            this.resolvedImports = resolvedImports;
            this.missingImports = missingImports;
        }
    }
}
