package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.core.owl.FormatCompatibility;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import org.protege.editor.owl.model.IOListenerManager;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Serialization-format selection and plain/verified ontology save workflows. */
final class OntologySaveService {

    static final String ON_LOSSY_WARN = "warn";
    static final String ON_LOSSY_FAIL = "fail";

    private static final long SAVE_TIMEOUT_MS = 120_000L;

    private OntologySaveService() {}

    /** Normalize the {@code on_lossy} request arg; null signals an invalid value. */
    static String normalizeOnLossy(String raw) {
        if (raw == null) {
            return ON_LOSSY_WARN;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return (ON_LOSSY_WARN.equals(value) || ON_LOSSY_FAIL.equals(value))
                ? value
                : null;
    }

    /** Attach the format-safeguard result fields (OBO report + lossy warning) when present. */
    private static void attachLossy(
            Tools.Json json,
            FormatCompatibility.OboCompatibility obo,
            FormatCompatibility.LossyWarning lossy) {
        if (obo != null) {
            json.put("obo_compatibility", obo.toJson());
        }
        if (lossy != null) {
            json.put("lossy_format_warning", lossy.toJson());
        }
    }

    /**
     * The saved:false refusal returned when on_lossy=fail and the target format would lose content.
     */
    private static CallToolResult lossyRefusal(
            String format,
            String path,
            FormatCompatibility.OboCompatibility obo,
            FormatCompatibility.LossyWarning lossy) {
        Tools.Json refusal =
                Tools.json()
                        .put("saved", false)
                        .put("error_code", "lossy_format_refused")
                        .put("format", format)
                        .put("path", path)
                        .put("lossy_format_warning", lossy.toJson());
        if (obo != null) {
            refusal.put("obo_compatibility", obo.toJson());
        }
        return refusal.result();
    }

    static List<String> saveTargets(OWLModelManager mm, boolean all) {
        List<String> targets = new ArrayList<>();
        Collection<OWLOntology> ontologies =
                all ? mm.getDirtyOntologies() : List.of(mm.getActiveOntology());
        for (OWLOntology ontology : ontologies) {
            IRI document = mm.getOWLOntologyManager().getOntologyDocumentIRI(ontology);
            if (isFileDocument(document)) {
                targets.add(new File(document.toURI()).getAbsolutePath());
            }
        }
        return targets;
    }

    /** Backwards-compatible entry point: a plain save with the default {@code on_lossy=warn}. */
    static CallToolResult saveOntology(OWLModelManager mm, String path) {
        return saveOntology(mm, path, ON_LOSSY_WARN, null);
    }

    /**
     * The user-visible record of an explicit-path save authorized by the invalid-policy bootstrap
     * (save_ontology {@code policy_bootstrap=true}): which policy was invalid, why the write was
     * still contained, and what to do next.
     */
    static Map<String, Object> bootstrapNote(ProjectPolicy discovered) {
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("used", true);
        note.put("policy_path", discovered.path() == null ? null : discovered.path().toString());
        note.put(
                "policy_errors",
                discovered.issues().stream()
                        .filter(issue -> "error".equals(issue.severity()))
                        .map(issue -> issue.code())
                        .distinct()
                        .toList());
        note.put(
                "contained_root",
                discovered.projectRoot() == null ? null : discovered.projectRoot().toString());
        note.put(
                "note",
                "The discovered project policy is invalid; this save was authorized by the"
                    + " explicit-path bootstrap (capability-checked, confined to the canonical"
                    + " project_root, no policy-granted widening). Re-run validate_project_policy"
                    + " once the referenced assets exist.");
        return note;
    }

    static CallToolResult saveOntology(
            OWLModelManager mm, String path, String onLossy, Map<String, Object> policyBootstrap) {
        OWLOntology ont = mm.getActiveOntology();
        OWLOntologyManager om = mm.getOWLOntologyManager();
        OWLDocumentFormat current = om.getOntologyFormat(ont);

        // Resolve the format that WILL be used FIRST — it can reject the extension, and the
        // lossy/OBO
        // safeguards must run BEFORE any directory is created, the format is
        // rebound, or the target is replaced. A rejected save-as leaves no side effects.
        File file;
        OWLDocumentFormat format;
        if (path != null) {
            file = new File(path).getAbsoluteFile();
            format = formatForPath(path, current);
        } else {
            IRI doc = om.getOntologyDocumentIRI(ont);
            if (!isFileDocument(doc)) {
                return Tools.error(
                        "This ontology has not been saved to a file yet (current document: "
                                + doc
                                + "). Pass 'path' to choose where to write it, e.g. "
                                + "\"/path/to/ontology.ttl\".");
            }
            file = new File(doc.toURI());
            format = current != null ? current : new RDFXMLDocumentFormat();
        }

        boolean isObo = FormatCompatibility.isOboFormat(format);
        FormatCompatibility.OboCompatibility obo =
                isObo ? FormatCompatibility.oboCompatibility(ont) : null;
        FormatCompatibility.LossyWarning lossy = FormatCompatibility.detectLoss(ont, format);
        if (ON_LOSSY_FAIL.equals(onLossy) && lossy != null) {
            return lossyRefusal(format.getClass().getSimpleName(), file.toString(), obo, lossy);
        }

        if (path != null) {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return Tools.error("Cannot create directory: " + dir);
            }
            // A save-as picks a fresh format whose prefix map is empty. Carry the ontology's
            // registered prefixes (custom prefixes + default/base) into it so the save does not
            // drop them on disk AND in memory — replacing the format with an empty one would
            // otherwise break every CURIE resolution afterwards (get_entity_context, sparql, ...).
            if (format != current
                    && format.isPrefixOWLOntologyFormat()
                    && current != null
                    && current.isPrefixOWLOntologyFormat()) {
                format.asPrefixOWLOntologyFormat()
                        .copyPrefixesFrom(current.asPrefixOWLOntologyFormat());
            }
            IRI previousDoc = om.getOntologyDocumentIRI(ont);
            om.setOntologyFormat(ont, format);
            om.setOntologyDocumentIRI(ont, IRI.create(file));
            try {
                saveOrThrow(mm, ont);
            } catch (RuntimeException e) {
                // A failed save-as (realistic with OBO, whose writer validates frame structure —
                // e.g. two rdfs:labels on one entity) must not leave the ontology bound to the
                // target that just failed: every later argument-less save AND the GUI's own
                // File ▸ Save would silently retry the broken format/path. Restore the binding.
                if (current != null) {
                    om.setOntologyFormat(ont, current);
                }
                om.setOntologyDocumentIRI(ont, previousDoc);
                throw e;
            }
            Tools.Json ok =
                    Tools.json()
                            .put("saved", true)
                            .put("path", file.toString())
                            .put("format", format.getClass().getSimpleName())
                            .putIfNotNull("policy_bootstrap", policyBootstrap);
            attachLossy(ok, obo, lossy);
            return ok.result();
        }
        saveOrThrow(mm, ont);
        Tools.Json ok =
                Tools.json()
                        .put("saved", true)
                        .put("path", file.toString())
                        .putIfNotNull("policy_bootstrap", policyBootstrap);
        attachLossy(ok, obo, lossy);
        return ok.result();
    }

    static CallToolResult verifiedSave(
            ToolContext ctx,
            String summary,
            String path,
            boolean atomic,
            boolean backup,
            String onLossy,
            Map<String, Object> policyBootstrap) {
        CallToolResult denied = WriteAccessPolicy.check(ctx, summary);
        if (denied != null) {
            return denied;
        }
        RevisionTools.PolicyState policy = RevisionTools.resolvePolicy(ctx, null);
        String lockDigest = RevisionTools.digestImportLock(policy.policy());
        // The capture hop copies every axiom into a snapshot and computes two canonical-
        // serialization fingerprints. SAVE_TIMEOUT_MS exists for this big-ontology work, so the
        // default EDT bound must not cut it short like the install hop below.
        VerifiedSaveCapture captured =
                ctx.access()
                        .compute(
                                mm ->
                                        captureVerifiedSave(
                                                ctx,
                                                mm,
                                                path,
                                                lockDigest,
                                                policy.policy().digest()),
                                SAVE_TIMEOUT_MS);
        // Format safeguards: predict serialization loss on the Protégé-free
        // snapshot BEFORE prepare touches the target. Verified save is already fail-closed (a lossy
        // format fails the round-trip comparison), but the OBO report gives an earlier, clearer
        // refusal, and on_lossy=fail turns it into a clean lossy_format_refused.
        final OWLDocumentFormat lossyFormat = captured.format;
        final boolean isObo = FormatCompatibility.isOboFormat(lossyFormat);
        final FormatCompatibility.OboCompatibility obo =
                isObo ? FormatCompatibility.oboCompatibility(captured.snapshot.ontology()) : null;
        final FormatCompatibility.LossyWarning lossy =
                FormatCompatibility.detectLoss(captured.snapshot.ontology(), lossyFormat);
        if (ON_LOSSY_FAIL.equals(onLossy) && lossy != null) {
            return lossyRefusal(
                    lossyFormat.getClass().getSimpleName(), captured.target.toString(), obo, lossy);
        }
        try (VerifiedOntologyWriter.Prepared prepared =
                VerifiedOntologyWriter.prepare(captured.snapshot, captured.target)) {
            ctx.writeLock().lock();
            try {
                return ctx.access()
                        .compute(
                                mm -> {
                                    // Read-only can be switched on while the confirmation dialog is
                                    // open or during a
                                    // long prepare; re-check on the model thread before the disk is
                                    // touched, like
                                    // commit_change_set does after its own confirmation gate.
                                    if (ctx.controller().isReadOnly()) {
                                        return WriteAccessPolicy.readOnlyDenied();
                                    }
                                    var live =
                                            ctx.revisions()
                                                    .current(
                                                            mm,
                                                            lockDigest,
                                                            policy.policy().digest())
                                                    .revision();
                                    if (!captured.revision.equals(live)
                                            || mm.getActiveOntology() != captured.activeIdentity) {
                                        return Tools.json()
                                                .put("saved", false)
                                                .put("error_code", "revision_conflict")
                                                .put(
                                                        "base_revision",
                                                        RevisionTools.revisionJson(
                                                                captured.revision))
                                                .put(
                                                        "current_revision",
                                                        RevisionTools.revisionJson(live))
                                                .put("path", captured.target.toString())
                                                .result();
                                    }
                                    // Mirror OWLModelManagerImpl.save's notification order so
                                    // save-aware listeners
                                    // (workspace modified indicator, VCS presenters, save hooks)
                                    // Treat a verified save exactly like File ▸ Save. IOListeners
                                    // heard before-save in the CAPTURE hop (before the snapshot, so
                                    // listener edits are in the installed artifact); here the
                                    // ontology goes clean, then ONTOLOGY_SAVED and after-save fire.
                                    // The IOListener legs are reachable only through the impl-side
                                    // IOListenerManager interface. A model manager that does not
                                    // implement it simply skips them.
                                    OWLOntology active = mm.getActiveOntology();
                                    VerifiedOntologyWriter.Install install =
                                            prepared.install(atomic, backup);
                                    OWLOntologyManager manager = mm.getOWLOntologyManager();
                                    manager.setOntologyFormat(active, captured.format);
                                    manager.setOntologyDocumentIRI(
                                            active, IRI.create(captured.target.toFile()));
                                    mm.setClean(active);
                                    mm.fireEvent(EventType.ONTOLOGY_SAVED);
                                    if (mm instanceof IOListenerManager) {
                                        ((IOListenerManager) mm)
                                                .fireAfterSaveEvent(
                                                        active.getOntologyID(),
                                                        captured.target.toFile().toURI());
                                    }
                                    ctx.revisions().invalidate();
                                    var revision =
                                            ctx.revisions()
                                                    .current(
                                                            mm,
                                                            lockDigest,
                                                            policy.policy().digest())
                                                    .revision();
                                    Tools.Json result =
                                            Tools.json()
                                                    .put("saved", true)
                                                    .put("verified", true)
                                                    .put("path", captured.target.toString())
                                                    .put(
                                                            "format",
                                                            captured.format
                                                                    .getClass()
                                                                    .getSimpleName())
                                                    .put("bytes", prepared.bytes)
                                                    .put("sha256", prepared.sha256)
                                                    .put(
                                                            "round_trip",
                                                            prepared.verification.toJson())
                                                    .put("atomic", install.atomic())
                                                    .putIfNotNull(
                                                            "backup_path",
                                                            install.backupPath() == null
                                                                    ? null
                                                                    : install.backupPath()
                                                                            .toString())
                                                    .put(
                                                            "revision",
                                                            RevisionTools.revisionJson(revision))
                                                    .putIfNotNull(
                                                            "policy_bootstrap", policyBootstrap);
                                    attachLossy(result, obo, lossy);
                                    return result.result();
                                },
                                SAVE_TIMEOUT_MS);
            } finally {
                ctx.writeLock().unlock();
            }
        }
    }

    private static VerifiedSaveCapture captureVerifiedSave(
            ToolContext ctx,
            OWLModelManager mm,
            String configuredPath,
            String lockDigest,
            String policyDigest) {
        OWLOntology active = mm.getActiveOntology();
        OWLOntologyManager manager = mm.getOWLOntologyManager();
        File target;
        OWLDocumentFormat current = manager.getOntologyFormat(active);
        OWLDocumentFormat format;
        if (configuredPath != null) {
            target = new File(configuredPath).getAbsoluteFile();
            format = formatForPath(configuredPath, current);
        } else {
            IRI document = manager.getOntologyDocumentIRI(active);
            if (!isFileDocument(document)) {
                throw new ToolArgException(
                        "This ontology has not been saved to a file yet; pass path.");
            }
            target = new File(document.toURI()).getAbsoluteFile();
            format = current != null ? current : formatForPath(target.toString(), null);
        }
        if (format != current
                && format.isPrefixOWLOntologyFormat()
                && current != null
                && current.isPrefixOWLOntologyFormat()) {
            format.asPrefixOWLOntologyFormat()
                    .copyPrefixesFrom(current.asPrefixOWLOntologyFormat());
        }
        // Fire before-save BEFORE the revision fingerprint and the axiom snapshot, mirroring plain
        // save (which fires it before serializing): a beforeSave IOListener that mutates the
        // ontology — e.g. stamps modification metadata — must have its edits snapshotted,
        // serialized, verified, and covered by the final revision check (a listener mutating AGAIN
        // later correctly yields revision_conflict). Consequently beforeSave can fire for a save
        // that later refuses (revision conflict / read-only flip / verify failure) — the same
        // tolerance plain save requires of listeners when the write itself throws.
        if (mm instanceof IOListenerManager) {
            ((IOListenerManager) mm).fireBeforeSaveEvent(active.getOntologyID(), target.toURI());
        }
        var revision = ctx.revisions().current(mm, lockDigest, policyDigest).revision();
        return new VerifiedSaveCapture(
                active,
                target.toPath(),
                format,
                VerifiedOntologyWriter.snapshot(active, format),
                revision);
    }

    private record VerifiedSaveCapture(
            OWLOntology activeIdentity,
            java.nio.file.Path target,
            OWLDocumentFormat format,
            VerifiedOntologyWriter.Snapshot snapshot,
            io.github.hakjuoh.protege_mcp.contracts.ModelRevision revision) {}

    /**
     * Save every dirty (modified since its last save) ontology to its existing document. An
     * ontology without a file document (never saved, or loaded straight from the web) has nowhere
     * safe to be written implicitly, so it is reported under 'skipped' instead — make it active and
     * use save_ontology with 'path'. One failed save does not abort the rest.
     */
    static CallToolResult saveAllDirty(OWLModelManager mm) {
        List<OWLOntology> dirty = new ArrayList<>(mm.getDirtyOntologies());
        dirty.sort(Comparator.comparing(ReadTools::ontologyLabel));
        OWLOntologyManager om = mm.getOWLOntologyManager();
        List<Map<String, Object>> saved = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (OWLOntology o : dirty) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ontology", ReadTools.ontologyLabel(o));
            IRI doc = om.getOntologyDocumentIRI(o);
            if (!isFileDocument(doc)) {
                row.put(
                        "reason",
                        "no file document to save to (current document: "
                                + doc
                                + ") — set it active and use save_ontology with 'path'");
                skipped.add(row);
                continue;
            }
            try {
                mm.save(o);
                row.put("path", new File(doc.toURI()).toString());
                saved.add(row);
            } catch (OWLOntologyStorageException | RuntimeException e) {
                // RuntimeException too: a storer's unchecked IO wrapper must not abort the rest
                row.put("reason", "save failed: " + e.getMessage());
                skipped.add(row);
            }
        }
        return Tools.json()
                .put("saved", saved)
                .put("skipped", skipped)
                .put(
                        "message",
                        dirty.isEmpty()
                                ? "Nothing to save — no ontology has unsaved changes."
                                : saved.size()
                                        + " of "
                                        + dirty.size()
                                        + " modified "
                                        + (dirty.size() == 1 ? "ontology" : "ontologies")
                                        + " saved.")
                .result();
    }

    private static void saveOrThrow(OWLModelManager mm, OWLOntology ont) {
        try {
            mm.save(ont);
        } catch (OWLOntologyStorageException e) {
            throw new ToolArgException("Save failed: " + e.getMessage());
        }
    }

    static boolean isFileDocument(IRI iri) {
        if (iri == null) {
            return false;
        }
        try {
            return "file".equalsIgnoreCase(iri.toURI().getScheme());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Pick a serialization format from the file extension. A path with no extension keeps the
     * ontology's current format (or RDF/XML when there is none, e.g. a fresh module); an extension
     * we do not recognize is an error rather than a silent fallback — writing pets.obo as RDF/XML
     * because .obo was unmapped surprises the caller far more than a hard stop.
     */
    static OWLDocumentFormat formatForPath(String path, OWLDocumentFormat current) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".ttl") || p.endsWith(".turtle")) {
            return new TurtleDocumentFormat();
        }
        if (p.endsWith(".omn")) {
            return new ManchesterSyntaxDocumentFormat();
        }
        if (p.endsWith(".ofn") || p.endsWith(".fss")) {
            return new FunctionalSyntaxDocumentFormat();
        }
        if (p.endsWith(".owx")) {
            return new OWLXMLDocumentFormat();
        }
        if (p.endsWith(".obo")) {
            return new OBODocumentFormat();
        }
        if (p.endsWith(".owl") || p.endsWith(".rdf") || p.endsWith(".xml")) {
            return new RDFXMLDocumentFormat();
        }
        String ext = extension(p);
        if (ext != null) {
            throw new ToolArgException(
                    "Unrecognized ontology file extension '."
                            + ext
                            + "'. Supported: .ttl/.turtle (Turtle), .owl/.rdf/.xml (RDF/XML), .owx"
                            + " (OWL/XML), .omn (Manchester), .ofn/.fss (Functional), .obo (OBO)."
                            + " Use one of these, or a path without an extension to keep the"
                            + " current format.");
        }
        return current != null ? current : new RDFXMLDocumentFormat();
    }

    /** The extension of the path's file name, or null if there is none (dotfiles do not count). */
    private static String extension(String lowerPath) {
        int slash = Math.max(lowerPath.lastIndexOf('/'), lowerPath.lastIndexOf('\\'));
        String name = lowerPath.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : null;
    }
}
