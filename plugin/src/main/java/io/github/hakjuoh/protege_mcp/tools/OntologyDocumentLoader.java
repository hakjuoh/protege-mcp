package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.core.owl.OwlParsingErrors;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.UnloadableImportException;
import org.semanticweb.owlapi.model.parameters.OntologyCopy;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads an ontology document off the EDT and attaches its complete import closure to Protégé. */
final class OntologyDocumentLoader {

    private OntologyDocumentLoader() {}

    /**
     * Fetch + parse {@code source} off the EDT, then wire it into the workspace on the EDT. When
     * {@code keepActive} is set the previously-active ontology stays active (the document is loaded
     * only to resolve imports / be available), otherwise the loaded primary becomes active.
     */
    static CallToolResult doLoad(
            ToolContext ctx, String source, int timeoutMs, boolean keepActive) {
        return doLoad(ctx, source, timeoutMs, keepActive, MissingImportsMode.WARN);
    }

    static CallToolResult doLoad(
            ToolContext ctx,
            String source,
            int timeoutMs,
            boolean keepActive,
            MissingImportsMode missingImports) {
        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, null);
        DirectAccessPolicy.Source authorized = rules.authorizeSource(source);
        return doLoad(
                ctx,
                authorized.value(),
                timeoutMs,
                keepActive,
                missingImports,
                rules.importNetworkRule());
    }

    static CallToolResult doLoad(
            ToolContext ctx,
            String source,
            int timeoutMs,
            boolean keepActive,
            MissingImportsMode missingImports,
            DirectAccessPolicy.NetworkRule networkRule) {
        return doLoad(
                ctx,
                source,
                timeoutMs,
                keepActive,
                missingImports,
                networkRule,
                LockMode.IGNORE,
                null);
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
        String normalized = OntologyImportResolver.normalizeSource(source);
        CallToolResult denied =
                WriteTools.checkWriteAllowed(
                        ctx,
                        "load "
                                + normalized
                                + (keepActive ? " into the workspace" : " and make it active"));
        if (denied != null) {
            return denied;
        }
        // Fetch + parse OFF the EDT: network I/O and parsing must not block the Swing UI thread (a
        // slow remote fetch would otherwise freeze Protégé and could trip the bounded compute()
        // wait).
        // This mirrors Protégé's own OntologyLoader, minus its interactive modal dialogs.
        List<OntologyImportMapping> workspaceMappings =
                ctx.access().compute(OntologyDocumentTools::workspaceImportMappings);
        LoadedDocument doc =
                fetch(normalized, timeoutMs, missingImports, workspaceMappings, networkRule);
        // Verify the TO-BE-LOADED closure BEFORE the workspace-mutating attach hop: a lock_mode
        // mismatch (or required with no lockfile) refuses the load while the parsed closure still
        // lives only in the throwaway manager (ADR 0005 decision 4).
        Map<String, Object> lockVerification =
                lockMode != null && lockMode.requested()
                        ? ImportLockTools.verifyClosureBeforeLoad(
                                accessRules,
                                ImportTools.analyze(doc.primary),
                                OntologyImportResolver.localFile(normalized),
                                lockMode)
                        : null;
        // Only cheap model wiring (move the parsed closure in, switch active, refresh) runs on EDT.
        return ctx.access()
                .compute(mm -> attach(mm, doc, keepActive, lockVerification), LOAD_TIMEOUT_MS);
    }

    /**
     * Wait bound for wiring a parsed document into the workspace on the EDT (move + activate + UI
     * refresh).
     */
    private static final long LOAD_TIMEOUT_MS = 120_000L;

    /**
     * Wait bound for applying a whole document's changes on the EDT (see merge_ontology_document).
     */
    private static final long MERGE_TIMEOUT_MS = 120_000L;

    // ------------------------------------------------------------------ load_ontology

    /** Compatibility overload retained for reflection-based callers and tests. */
    private static LoadedDocument fetch(
            String normalized,
            int timeoutMs,
            MissingImportsMode missingImports,
            List<OntologyImportMapping> workspaceMappings) {
        return fetch(
                normalized,
                timeoutMs,
                missingImports,
                workspaceMappings,
                new DirectAccessPolicy.NetworkRule(true, Set.of(), true, true));
    }

    static ToolArgException strictImportFailure(
            String normalized, UnloadableImportException failure) {
        String importIri =
                failure.getImportsDeclaration() == null
                        ? "(unknown)"
                        : failure.getImportsDeclaration().getIRI().toString();
        OWLOntologyCreationException cause = failure.getOntologyCreationException();
        String detail = OwlParsingErrors.conciseMessage(cause == null ? failure : cause);
        return new ToolArgException(
                "Could not load ontology document '"
                        + normalized
                        + "': required import '"
                        + importIri
                        + "' could not be loaded"
                        + (detail == null ? "." : ": " + detail));
    }

    /**
     * Fetch and parse {@code normalized} off the EDT into a throwaway <em>concurrent</em> manager.
     * The manager must be concurrent because {@link #attach} moves the parsed ontologies into
     * Protégé's concurrent manager (a non-concurrent ontology would break Protégé's locking) — this
     * matches what Protégé's own {@code OntologyLoader} does. Missing imports follow the caller's
     * compatibility mode and are recorded when warning/reporting is enabled.
     */
    private static LoadedDocument fetch(
            String normalized,
            int timeoutMs,
            MissingImportsMode missingImports,
            List<OntologyImportMapping> workspaceMappings,
            DirectAccessPolicy.NetworkRule networkRule) {
        OWLOntologyManager manager = OwlManagers.createConcurrent();
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
            OntologyImportResolver.addWorkspaceImportMappers(manager, workspaceMappings, blocker);
            // Register the source-adjacent catalog last. OWLAPI gives the most recently added mapper
            // priority, so version-controlled project resolution overrides stale interactive hints.
            OntologyImportResolver.addFolderCatalogMapper(manager, normalized, blocker);
            OWLOntology primary =
                    manager.loadOntologyFromOntologyDocument(
                            OntologyImportResolver.documentSource(normalized), config);
            OntologyNetworkSupport.finishUnsupportedImports(
                    primary, fallback, unresolvedImports, missingImports, normalized);
            blocker.failIfBlocked(normalized, unresolvedImports);
            return new LoadedDocument(
                    normalized,
                    manager,
                    primary,
                    manager.getOntologyDocumentIRI(primary),
                    unresolvedImports,
                    resolvedImportEdges(manager),
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

    /**
     * Move the parsed document (and its import closure) into Protégé's shared manager and make the
     * primary ontology active. Replicates the success path of Protégé's {@code OntologyLoader}
     * (copyOntology MOVE for each not-already-loaded ontology, then setActiveOntology + fire
     * ONTOLOGY_LOADED), but with no modal UI. Runs on the EDT.
     */
    private static CallToolResult attach(
            OWLModelManager mm, LoadedDocument doc, boolean keepActive) {
        return attach(mm, doc, keepActive, null);
    }

    private static CallToolResult attach(
            OWLModelManager mm,
            LoadedDocument doc,
            boolean keepActive,
            Map<String, Object> lockVerification) {
        OWLOntology prevActive = mm.getActiveOntology();
        OWLOntologyManager target = mm.getOWLOntologyManager();
        OWLOntologyID primaryId = doc.primary.getOntologyID();
        OWLOntology primaryManaged = null;
        int moved = 0;
        int alreadyLoaded = 0;
        for (ResolvedImport edge : doc.resolvedEdges) {
            OWLOntology existing = target.getImportedOntology(edge.declaration);
            if (existing != null && !existing.getOntologyID().equals(edge.targetId)) {
                throw new ToolArgException(
                        "Could not attach the loaded import closure: import '"
                                + edge.declaration.getIRI()
                                + "' already resolves in the workspace to "
                                + OntologyImportResolver.ontologyLabel(existing.getOntologyID())
                                + " instead of "
                                + OntologyImportResolver.ontologyLabel(edge.targetId)
                                + ".");
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
                    throw new ToolArgException(
                            "Could not add the loaded ontology to the workspace: "
                                    + (e.getMessage() == null
                                            ? e.getClass().getSimpleName()
                                            : e.getMessage()));
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
            // Activation has not happened yet. Move only ontologies added by this call back into
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
        // active-ontologies (imports-closure) cache that the entity finder and renderers read. A
        // no-op setActiveOntology to the already-active ontology does NOT recompute it, so merely
        // firing ACTIVE_ONTOLOGY_CHANGED while keeping the active ontology is not enough.
        mm.setActiveOntology(primaryManaged);
        mm.fireEvent(EventType.ONTOLOGY_LOADED);
        // Register the primary logical -> document IRI mapping so later direct loads/save find it.
        if (!primaryId.isAnonymous()
                && doc.documentIri != null
                && primaryId.getDefaultDocumentIRI().isPresent()) {
            addSimpleMapperIfNeeded(
                    target, primaryId.getDefaultDocumentIRI().get(), doc.documentIri);
        }
        // keep_active: switch BACK to the caller's prior edit target. This SECOND real
        // setActiveOntology recomputes the closure for prevActive, now including the just-loaded
        // document, so its
        // imported terms resolve by name (search_entities / Manchester) with no manual round-trip.
        boolean keptActive =
                keepActive
                        && prevActive != null
                        && !prevActive.equals(primaryManaged)
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
                .put(
                        "loaded_ontology",
                        OntologyImportResolver.ontologyLabel(primaryManaged.getOntologyID()))
                .put("loaded_axioms", primaryManaged.getAxiomCount())
                .put(
                        "active_ontology",
                        OntologyImportResolver.ontologyLabel(finalActive.getOntologyID()))
                .put("kept_active", keptActive)
                .putIfNotNull(
                        "document_iri", doc.documentIri == null ? null : doc.documentIri.toString())
                .put("active", active)
                .put("added_ontologies", moved)
                .put("already_loaded", alreadyLoaded)
                .put("workspace_ontologies", mm.getOntologies().size())
                .put("missing_imports_mode", doc.missingImports.value())
                .put("resolved_imports", workspaceImports.resolvedImports)
                .put("unresolved_imports", doc.missingImports.reported(doc.unresolvedImports))
                .putIfNotNull("import_lock_verification", lockVerification)
                .put(
                        "note",
                        keptActive
                                ? "Loaded into the workspace; active ontology unchanged. Loading is"
                                        + " not on the undo stack (undo_change cannot revert it)."
                                : "Loading is not on the undo stack (undo_change cannot revert"
                                        + " it).")
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
        sources.sort(
                (left, right) ->
                        OntologyImportResolver.ontologyLabel(left.getOntologyID())
                                .compareTo(
                                        OntologyImportResolver.ontologyLabel(
                                                right.getOntologyID())));
        for (OWLOntology source : sources) {
            List<OWLImportsDeclaration> declarations =
                    new ArrayList<>(source.getImportsDeclarations());
            declarations.sort(
                    (left, right) -> left.getIRI().toString().compareTo(right.getIRI().toString()));
            for (OWLImportsDeclaration declaration : declarations) {
                OWLOntology imported = manager.getImportedOntology(declaration);
                if (imported != null) {
                    edges.add(
                            new ResolvedImport(
                                    source.getOntologyID(), declaration, imported.getOntologyID()));
                }
            }
        }
        return List.copyOf(edges);
    }

    /** Rebuild the declaration cache that OWLAPI's {@code copyOntology(MOVE)} does not transfer. */
    private static void restoreResolvedImportEdges(
            OWLModelManager mm,
            List<ResolvedImport> edges,
            List<OWLOntologyIRIMapper> addedMappers) {
        OWLOntologyManager manager = mm.getOWLOntologyManager();
        OWLOntologyLoaderConfiguration config =
                new OWLOntologyLoaderConfiguration()
                        .setMissingImportHandlingStrategy(
                                org.semanticweb.owlapi.model.MissingImportHandlingStrategy
                                        .THROW_EXCEPTION)
                        .setFollowRedirects(false);
        for (ResolvedImport edge : edges) {
            OWLOntology expected = managedById(mm, edge.targetId);
            if (expected == null) {
                throw new ToolArgException(
                        "Could not attach the loaded import closure: resolved target "
                                + OntologyImportResolver.ontologyLabel(edge.targetId)
                                + " is absent from the workspace.");
            }
            OWLOntology actual = manager.getImportedOntology(edge.declaration);
            if (actual == null) {
                IRI document = manager.getOntologyDocumentIRI(expected);
                OWLOntologyIRIMapper mapper =
                        addSimpleMapperIfNeeded(manager, edge.declaration.getIRI(), document);
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
                throw new ToolArgException(
                        "Could not attach the loaded import closure: import '"
                                + edge.declaration.getIRI()
                                + "' did not reconnect to "
                                + OntologyImportResolver.ontologyLabel(edge.targetId)
                                + ". Likely cause: an earlier failed load of that import IRI left a"
                                + " stale failed-import marker on this workspace manager, which"
                                + " turns later load requests for the IRI into silent no-ops.");
            }
        }
    }

    /**
     * Clear a stale failed-import marker and retry the reconnect once. In OWLAPI 4.5.29, {@code
     * OWLOntologyManagerImpl.makeLoadImportRequest} is entirely guarded by {@code
     * !importedIRIs.containsKey(iri)}, and a FAILED live-manager load of an import IRI leaves a
     * permanent raw marker under that key (the put precedes {@code loadImports}; failure never
     * removes it, and {@code removeOntology} only clears OntologyID values) — so a later request
     * for the same IRI is a silent no-op. The only change that clears the key is a {@code
     * RemoveImport} (see {@code checkForImportsChange}); re-adding the declaration restores the
     * source ontology unchanged WITHOUT triggering a load (an {@code AddImport} only re-maps
     * against already-managed ontologies), after which the load request can finally run.
     */
    private static OWLOntology retryAfterClearingStaleImportMarker(
            OWLModelManager mm,
            OWLOntologyManager manager,
            ResolvedImport edge,
            OWLOntologyLoaderConfiguration config) {
        OWLOntology sourceOntology = managedById(mm, edge.sourceId);
        if (sourceOntology == null
                || !sourceOntology.getImportsDeclarations().contains(edge.declaration)) {
            return null;
        }
        manager.applyChanges(
                List.of(
                        new RemoveImport(sourceOntology, edge.declaration),
                        new AddImport(sourceOntology, edge.declaration)));
        manager.makeLoadImportRequest(edge.declaration, config);
        return manager.getImportedOntology(edge.declaration);
    }

    /**
     * Add a highest-priority exact mapper unless the currently effective mapping is already exact.
     */
    private static OWLOntologyIRIMapper addSimpleMapperIfNeeded(
            OWLOntologyManager manager, IRI logical, IRI document) {
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

    private record ResolvedImport(
            OWLOntologyID sourceId, OWLImportsDeclaration declaration, OWLOntologyID targetId) {}

    private static final class LoadedDocument {
        final String normalized;
        final OWLOntologyManager manager;
        final OWLOntology primary;
        final IRI documentIri;
        final List<String> unresolvedImports;
        final List<ResolvedImport> resolvedEdges;
        final MissingImportsMode missingImports;

        LoadedDocument(
                String normalized,
                OWLOntologyManager manager,
                OWLOntology primary,
                IRI documentIri,
                List<String> unresolvedImports,
                List<ResolvedImport> resolvedEdges,
                MissingImportsMode missingImports) {
            this.normalized = normalized;
            this.manager = manager;
            this.primary = primary;
            this.documentIri = documentIri;
            this.unresolvedImports = unresolvedImports;
            this.resolvedEdges = resolvedEdges;
            this.missingImports = missingImports;
        }
    }
}
