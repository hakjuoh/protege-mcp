package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.core.owl.OwlParsingErrors;
import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
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
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.SetOntologyID;
import org.semanticweb.owlapi.model.UnloadableImportException;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tools that load complete OWL documents into the workspace: {@code load_ontology} opens a document
 * as its own loaded ontology and makes it active, while {@code merge_ontology_document} copies a
 * document's content into the already-active ontology.
 */
public final class OntologyDocumentTools {

    private OntologyDocumentTools() {}

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool(
                "load_ontology",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String source = Tools.reqString(a, "source");
                    boolean keepActive = Tools.optBool(a, "keep_active", false);
                    int timeoutMs = Tools.optInt(a, "connection_timeout_ms", 15_000);
                    MissingImportsMode missingImports =
                            MissingImportsMode.parse(Tools.optString(a, "missing_imports"));
                    LockMode lockMode = LockMode.parse(Tools.optString(a, "lock_mode"));
                    DirectAccessPolicy.Rules accessRules =
                            DirectAccessPolicy.resolve(ctx, ex)
                                    .withRequestNetwork(Tools.optString(a, "network"));
                    DirectAccessPolicy.Source authorized = accessRules.authorizeSource(source);
                    return doLoad(
                            ctx,
                            authorized.value(),
                            timeoutMs,
                            keepActive,
                            missingImports,
                            accessRules.importNetworkRule(),
                            lockMode,
                            accessRules);
                });
        tools.tool(
                "merge_ontology_document",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String source = Tools.reqString(a, "source");
                    boolean replaceActive = Tools.optBool(a, "replace_active", false);
                    boolean copyOntologyId = Tools.optBool(a, "copy_ontology_id", replaceActive);
                    boolean preview = Tools.optBool(a, "preview", false);
                    int timeoutMs = Tools.optInt(a, "connection_timeout_ms", 15_000);
                    MissingImportsMode missingImports =
                            MissingImportsMode.parse(Tools.optString(a, "missing_imports"));
                    LockMode lockMode = LockMode.parse(Tools.optString(a, "lock_mode"));
                    DirectAccessPolicy.Rules accessRules =
                            DirectAccessPolicy.resolve(ctx, ex)
                                    .withRequestNetwork(Tools.optString(a, "network"));
                    DirectAccessPolicy.Source authorized = accessRules.authorizeSource(source);
                    source = authorized.value();
                    String summary =
                            replaceActive
                                    ? "replace active ontology — delete ALL its axioms, imports and"
                                          + " ontology annotations — with "
                                            + source
                                    : "merge ontology document " + source;
                    if (!preview) {
                        CallToolResult denied = WriteTools.checkWriteAllowed(ctx, summary);
                        if (denied != null) {
                            return denied;
                        }
                    }
                    MergePreparation preparation =
                            ctx.access()
                                    .compute(
                                            mm ->
                                                    new MergePreparation(
                                                            workspaceImportMappings(mm),
                                                            preview
                                                                    ? null
                                                                    : captureMergeCoordinates(
                                                                            ctx, mm)));
                    LoadedOntology loaded =
                            load(
                                    source,
                                    timeoutMs,
                                    missingImports,
                                    preparation.workspaceMappings(),
                                    accessRules.importNetworkRule());
                    // Verify the parsed closure BEFORE the workspace-mutating hop. A lock_mode
                    // mismatch (or required with no lockfile) refuses the merge before anything is
                    // applied (ADR 0005 decision 4).
                    Map<String, Object> lockVerification =
                            lockMode.requested()
                                    ? ImportLockTools.verifyClosureBeforeLoad(
                                            accessRules,
                                            loaded.importReport,
                                            localFile(source),
                                            lockMode)
                                    : null;
                    // A whole-document merge applies thousands of changes in one EDT batch, so give
                    // it a longer bound than the default. Otherwise a slow but successful apply is
                    // reported as a timeout while the model keeps mutating.
                    return ctx.access()
                            .compute(
                                    mm ->
                                            apply(
                                                    ctx,
                                                    mm,
                                                    loaded,
                                                    replaceActive,
                                                    copyOntologyId,
                                                    preview,
                                                    lockVerification,
                                                    preparation.coordinates()),
                                    MERGE_TIMEOUT_MS);
                });
        tools.tool(
                "set_active_ontology",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String ref = Tools.reqString(a, "ontology_iri");
                    return ctx.access()
                            .compute(
                                    mm -> {
                                        OWLOntology target = findLoadedOntology(mm, ref);
                                        if (target == null) {
                                            return Tools.error(
                                                    "No loaded ontology matches '"
                                                            + ref
                                                            + "'. See list_ontologies for the"
                                                            + " loaded ontology ids.");
                                        }
                                        boolean changed = !target.equals(mm.getActiveOntology());
                                        if (changed) {
                                            mm.setActiveOntology(target);
                                            mm.fireEvent(EventType.ACTIVE_ONTOLOGY_CHANGED);
                                        }
                                        return Tools.json()
                                                .put(
                                                        "active_ontology",
                                                        ontologyLabel(target.getOntologyID()))
                                                .put("changed", changed)
                                                .put("axioms", target.getAxiomCount())
                                                .put(
                                                        "logical_axioms",
                                                        target.getLogicalAxiomCount())
                                                .put(
                                                        "direct_imports",
                                                        target.getImportsDeclarations().size())
                                                .result();
                                    });
                });
        tools.tool(
                "create_ontology",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String ontologyIri = Tools.reqString(a, "ontology_iri");
                    String versionIri = Tools.optString(a, "version_iri");
                    String configuredPath = Tools.optString(a, "path");
                    final String path =
                            configuredPath == null
                                    ? null
                                    : DirectAccessPolicy.resolve(ctx, ex)
                                            .writePath(configuredPath)
                                            .toString();
                    boolean keepActive = Tools.optBool(a, "keep_active", false);
                    CallToolResult denied =
                            WriteTools.checkWriteAllowed(ctx, "create new ontology " + ontologyIri);
                    if (denied != null) {
                        return denied;
                    }
                    return ctx.access()
                            .compute(
                                    mm ->
                                            createOntology(
                                                    mm, ontologyIri, versionIri, path, keepActive));
                });
    }

    /**
     * Mint a new empty ontology via Protégé's {@link OWLModelManager#createNewOntology} (which sets
     * it active and fires ONTOLOGY_CREATED). When {@code path} is given the ontology is bound to
     * that file document; with {@code keepActive} the previously-active ontology is restored as the
     * edit target.
     */
    private static CallToolResult createOntology(
            OWLModelManager mm,
            String ontologyIri,
            String versionIri,
            String path,
            boolean keepActive) {
        OWLOntologyID newId =
                versionIri != null
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
            throw new ToolArgException(
                    "Could not create ontology "
                            + ontologyIri
                            + ": "
                            + (e.getMessage() == null
                                    ? e.getClass().getSimpleName()
                                    : e.getMessage()));
        }
        boolean keptActive =
                keepActive
                        && prevActive != null
                        && !prevActive.equals(created)
                        && mm.getOntologies().contains(prevActive);
        if (keptActive) {
            mm.setActiveOntology(prevActive);
        }
        OWLOntology active = keptActive ? prevActive : created;
        return Tools.json()
                .put("created_ontology", ontologyLabel(created.getOntologyID()))
                .put("active_ontology", ontologyLabel(active.getOntologyID()))
                .put("kept_active", keptActive)
                .putIfNotNull(
                        "document_iri",
                        physicalUri == null
                                ? null
                                : mm.getOWLOntologyManager()
                                        .getOntologyDocumentIRI(created)
                                        .toString())
                .put("workspace_ontologies", mm.getOntologies().size())
                .put(
                        "note",
                        "New ontology created; this is not on the undo stack (undo_change cannot "
                                + "revert it).")
                .result();
    }

    /**
     * Find a loaded ontology by ontology IRI or version IRI string (exact match); null matches
     * nothing.
     */
    static OWLOntology findLoadedOntology(OWLModelManager mm, String ref) {
        if (ref == null) {
            return null;
        }
        for (OWLOntology o : mm.getOntologies()) {
            OWLOntologyID id = o.getOntologyID();
            if (id.getOntologyIRI().isPresent()
                    && id.getOntologyIRI().get().toString().equals(ref)) {
                return o;
            }
            if (id.getVersionIRI().isPresent() && id.getVersionIRI().get().toString().equals(ref)) {
                return o;
            }
        }
        return null;
    }

    static CallToolResult doLoad(
            ToolContext ctx, String source, int timeoutMs, boolean keepActive) {
        return OntologyDocumentLoader.doLoad(ctx, source, timeoutMs, keepActive);
    }

    static CallToolResult doLoad(
            ToolContext ctx,
            String source,
            int timeoutMs,
            boolean keepActive,
            MissingImportsMode missingImports) {
        return OntologyDocumentLoader.doLoad(ctx, source, timeoutMs, keepActive, missingImports);
    }

    static CallToolResult doLoad(
            ToolContext ctx,
            String source,
            int timeoutMs,
            boolean keepActive,
            MissingImportsMode missingImports,
            DirectAccessPolicy.NetworkRule networkRule) {
        return OntologyDocumentLoader.doLoad(
                ctx, source, timeoutMs, keepActive, missingImports, networkRule);
    }

    static CallToolResult doLoad(
            ToolContext ctx,
            String source,
            int timeoutMs,
            boolean keepActive,
            MissingImportsMode missingImports,
            DirectAccessPolicy.NetworkRule networkRule,
            LockMode lockMode,
            DirectAccessPolicy.Rules accessRules) {
        return OntologyDocumentLoader.doLoad(
                ctx,
                source,
                timeoutMs,
                keepActive,
                missingImports,
                networkRule,
                lockMode,
                accessRules);
    }

    private static final long MERGE_TIMEOUT_MS = 120_000L;

    // ------------------------------------------------------------------ merge_ontology_document

    private static LoadedOntology load(String source, int timeoutMs) {
        return load(
                source,
                timeoutMs,
                MissingImportsMode.WARN,
                List.of(),
                new DirectAccessPolicy.NetworkRule(true, Set.of(), true, true));
    }

    /** Compatibility overload retained for reflection-based callers and tests. */
    private static LoadedOntology load(
            String source,
            int timeoutMs,
            MissingImportsMode missingImports,
            List<OntologyImportMapping> workspaceMappings) {
        return load(
                source,
                timeoutMs,
                missingImports,
                workspaceMappings,
                new DirectAccessPolicy.NetworkRule(true, Set.of(), true, true));
    }

    private static LoadedOntology load(
            String source,
            int timeoutMs,
            MissingImportsMode missingImports,
            List<OntologyImportMapping> workspaceMappings,
            DirectAccessPolicy.NetworkRule networkRule) {
        String normalized = normalizeSource(source);
        OWLOntologyManager manager = OwlManagers.create();
        // WARN and SILENT both let OWLAPI finish parsing; ERROR makes an unresolved import abort
        // before any change can be applied to the live active ontology.
        List<String> unresolvedImports = new ArrayList<>();
        manager.addMissingImportListener(
                event -> unresolvedImports.add(event.getImportedOntologyURI().toString()));
        OWLOntologyLoaderConfiguration config =
                new OWLOntologyLoaderConfiguration()
                        .setMissingImportHandlingStrategy(missingImports.strategy())
                        .setFollowRedirects(networkRule.followRedirects())
                        .setConnectionTimeout(timeoutMs);
        try (OntologyNetworkSupport.NetworkImportBlocker blocker =
                        OntologyNetworkSupport.NetworkImportBlocker.install(manager, networkRule);
                OntologyNetworkSupport.UnsupportedImportFallback fallback =
                        OntologyNetworkSupport.UnsupportedImportFallback.install(manager)) {
            addWorkspaceImportMappers(manager, workspaceMappings, blocker);
            // The sibling catalog is the project source of truth and must override workspace hints.
            addFolderCatalogMapper(manager, normalized, blocker);
            OWLOntology sourceOntology =
                    manager.loadOntologyFromOntologyDocument(documentSource(normalized), config);
            OntologyNetworkSupport.finishUnsupportedImports(
                    sourceOntology, fallback, unresolvedImports, missingImports, normalized);
            blocker.failIfBlocked(normalized, unresolvedImports);
            ImportTools.ImportReport imports = ImportTools.analyze(sourceOntology);
            return new LoadedOntology(
                    normalized,
                    sourceOntology.getOntologyID(),
                    new LinkedHashSet<>(sourceOntology.getAxioms()),
                    new LinkedHashSet<>(sourceOntology.getImportsDeclarations()),
                    new LinkedHashSet<>(sourceOntology.getAnnotations()),
                    unresolvedImports,
                    imports,
                    missingImports);
        } catch (UnloadableImportException e) {
            throw strictImportFailure(normalized, e);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException(
                    "Could not load ontology document '"
                            + normalized
                            + "': "
                            + OwlParsingErrors.conciseMessage(e));
        }
    }

    static ToolArgException strictImportFailure(
            String normalized, UnloadableImportException failure) {
        return OntologyDocumentLoader.strictImportFailure(normalized, failure);
    }

    /**
     * Snapshot reusable logical/version/declaration IRI mappings from the live workspace. The
     * throwaway loaders receive only immutable IRI pairs, never live ontology objects, so parsing
     * remains off the EDT while already-loaded file/http documents can resolve exactly as they do
     * in the workspace. Untitled {@code owlapi:} documents are deliberately excluded: they are not
     * reproducible document sources and strict loading must not pretend otherwise.
     */
    static List<OntologyImportMapping> workspaceImportMappings(OWLModelManager mm) {
        return OntologyImportResolver.workspaceImportMappings(mm);
    }

    static void addWorkspaceImportMappers(
            OWLOntologyManager manager,
            List<OntologyImportMapping> mappings,
            OntologyNetworkSupport.NetworkImportBlocker blocker) {
        OntologyImportResolver.addWorkspaceImportMappers(manager, mappings, blocker);
    }

    static IRI authorizedMappedDocument(IRI document, DirectAccessPolicy.NetworkRule networkRule) {
        return OntologyImportResolver.authorizedMappedDocument(document, networkRule);
    }

    static OWLOntologyDocumentSource documentSource(String source) {
        return OntologyImportResolver.documentSource(source);
    }

    static File localFile(String source) {
        return OntologyImportResolver.localFile(source);
    }

    static void addFolderCatalogMapper(OWLOntologyManager manager, String source) {
        OntologyImportResolver.addFolderCatalogMapper(manager, source);
    }

    static void addFolderCatalogMapper(
            OWLOntologyManager manager,
            String source,
            OntologyNetworkSupport.NetworkImportBlocker blocker) {
        OntologyImportResolver.addFolderCatalogMapper(manager, source, blocker);
    }

    static String normalizeSource(String source) {
        return OntologyImportResolver.normalizeSource(source);
    }

    private static CallToolResult apply(
            OWLModelManager mm,
            LoadedOntology loaded,
            boolean replaceActive,
            boolean copyOntologyId,
            boolean preview) {
        return apply(mm, loaded, replaceActive, copyOntologyId, preview, null);
    }

    private static CallToolResult apply(
            ToolContext ctx,
            OWLModelManager mm,
            LoadedOntology loaded,
            boolean replaceActive,
            boolean copyOntologyId,
            boolean preview,
            Map<String, Object> lockVerification,
            MergeCoordinates coordinates) {
        if (!preview) {
            CallToolResult refused = validateMergeCoordinates(ctx, mm, coordinates);
            if (refused != null) {
                return refused;
            }
        }
        return apply(mm, loaded, replaceActive, copyOntologyId, preview, lockVerification);
    }

    static MergeCoordinates captureMergeCoordinates(ToolContext ctx, OWLModelManager mm) {
        return new MergeCoordinates(
                mm.getActiveOntology(), ctx.revisions().current(mm, null, null).revision());
    }

    /** Revalidate the mutable workspace immediately before a previously prepared merge applies. */
    static CallToolResult validateMergeCoordinates(
            ToolContext ctx, OWLModelManager mm, MergeCoordinates expected) {
        if (ctx.controller().isReadOnly()) {
            return WriteTools.readOnlyDenied();
        }
        ModelRevision current = ctx.revisions().current(mm, null, null).revision();
        if (expected == null
                || mm.getActiveOntology() != expected.activeIdentity()
                || !expected.revision().equals(current)) {
            return Tools.json()
                    .put("merged", false)
                    .put("error_code", "revision_conflict")
                    .putIfNotNull(
                            "base_revision",
                            expected == null
                                    ? null
                                    : RevisionTools.revisionJson(expected.revision()))
                    .put("current_revision", RevisionTools.revisionJson(current))
                    .result();
        }
        return null;
    }

    private static CallToolResult apply(
            OWLModelManager mm,
            LoadedOntology loaded,
            boolean replaceActive,
            boolean copyOntologyId,
            boolean preview,
            Map<String, Object> lockVerification) {
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
            for (OWLImportsDeclaration declaration :
                    new LinkedHashSet<>(active.getImportsDeclarations())) {
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
                .put("unresolved_imports", loaded.missingImports.reported(loaded.unresolvedImports))
                .putIfNotNull("import_lock_verification", lockVerification);
        if (preview) {
            if (!replaceActive) {
                json.put("already_present_axioms", alreadyPresent);
            }
            return json.put("total_changes", changes.size())
                    .put(
                            "note",
                            "Nothing was changed. Re-run without 'preview' to apply the "
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
        return OntologyImportResolver.ontologyLabel(id);
    }

    record MergeCoordinates(OWLOntology activeIdentity, ModelRevision revision) {}

    private record MergePreparation(
            List<OntologyImportMapping> workspaceMappings, MergeCoordinates coordinates) {}

    private static final class LoadedOntology {
        final String source;
        final OWLOntologyID ontologyId;
        final Set<OWLAxiom> axioms;
        final Set<OWLImportsDeclaration> importsDeclarations;
        final Set<OWLAnnotation> annotations;
        final List<String> unresolvedImports;

        /** Full parsed-closure analysis, retained so lock_mode can verify before the apply hop. */
        final ImportTools.ImportReport importReport;

        final List<Map<String, Object>> resolvedImports;
        final MissingImportsMode missingImports;

        LoadedOntology(
                String source,
                OWLOntologyID ontologyId,
                Set<OWLAxiom> axioms,
                Set<OWLImportsDeclaration> importsDeclarations,
                Set<OWLAnnotation> annotations,
                List<String> unresolvedImports,
                ImportTools.ImportReport importReport,
                MissingImportsMode missingImports) {
            this.source = source;
            this.ontologyId = ontologyId;
            this.axioms = axioms;
            this.importsDeclarations = importsDeclarations;
            this.annotations = annotations;
            this.unresolvedImports = unresolvedImports;
            this.importReport = importReport;
            this.resolvedImports = importReport.resolvedImports;
            this.missingImports = missingImports;
        }

        /**
         * Compatibility constructor retained for reflection-based tests; carries no full report.
         */
        LoadedOntology(
                String source,
                OWLOntologyID ontologyId,
                Set<OWLAxiom> axioms,
                Set<OWLImportsDeclaration> importsDeclarations,
                Set<OWLAnnotation> annotations,
                List<String> unresolvedImports,
                List<Map<String, Object>> resolvedImports,
                MissingImportsMode missingImports) {
            this.source = source;
            this.ontologyId = ontologyId;
            this.axioms = axioms;
            this.importsDeclarations = importsDeclarations;
            this.annotations = annotations;
            this.unresolvedImports = unresolvedImports;
            this.importReport = null;
            this.resolvedImports = resolvedImports;
            this.missingImports = missingImports;
        }
    }
}
