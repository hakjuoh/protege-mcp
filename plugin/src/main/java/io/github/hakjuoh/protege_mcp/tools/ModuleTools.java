package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import com.google.common.base.Optional;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import uk.ac.manchester.cs.owlapi.modularity.ModuleType;
import uk.ac.manchester.cs.owlapi.modularity.SyntacticLocalityModuleExtractor;

/**
 * Signature-based module extraction: {@code extract_module} pulls a self-contained subset of the
 * ontology that entails everything the seed terms entail, using the OWL API's syntactic locality
 * module extractor ({@link SyntacticLocalityModuleExtractor}). This is the interactive analogue of
 * {@code robot extract} — the workhorse for building an import seed, an application ontology, or a
 * shareable slice, run live over the current imports closure instead of a CLI pipeline.
 *
 * <p>The heavy part — the STAR extractor's fixpoint iteration — runs <em>off the EDT</em>, mirroring
 * {@link GovernanceTools}: phase&nbsp;1 (bounded, on the model thread) resolves the seed signature and
 * copies the source closure into a private, Protégé-free ontology; phase&nbsp;2 (off the EDT) extracts
 * the module; phase&nbsp;3 delivers it — either as a new workspace ontology (on the EDT) or saved to a
 * file (off the EDT, via a private manager). {@code timeout_ms} bounds the caller's wait for the
 * on-EDT phases; it cannot interrupt the extraction itself.
 *
 * <p>Producing a module is treated as a write — both delivery modes are gated by the read-only switch
 * and the write-confirmation dialog (a file export truncates whatever is at {@code path}, so it is gated
 * like {@code save_ontology}/{@code write_catalog}, not left ungated). The extractor's own axioms are
 * shared, immutable OWL API objects, so a module built in a private manager can be re-homed into
 * Protégé's manager without copying.
 */
public final class ModuleTools {

    private ModuleTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("extract_module",
                "Extract a locality-based MODULE — a self-contained subset of the ontology that "
                        + "preserves everything the seed terms entail — using syntactic locality (the "
                        + "same technique as robot extract). 'signature' is the seed set: entity names or "
                        + "full IRIs (classes, properties, individuals; punned IRIs bring all senses). "
                        + "'module_type' is STAR (default — smallest, both-directions), BOT (⊥, "
                        + "subclasses/uses of the seeds) or TOP (⊤, superclasses/context). 'source' is "
                        + "imports_closure (default — extract from the active ontology + its imports) or "
                        + "active (the active ontology only). By default the module is loaded as a NEW "
                        + "ontology in the Protégé workspace (give 'iri' to name it); pass 'path' to save "
                        + "it to a file instead (format from the extension: .ttl/.turtle, "
                        + ".owl/.rdf/.xml, .omn, .ofn/.fss, .owx, .obo — anything else is an error; no "
                        + "extension writes RDF/XML). "
                        + "Reports the module's axiom/entity counts and which seeds resolved.",
                Tools.schema()
                        .strArrayReq("signature", "Seed entities: names or full IRIs (classes, "
                                + "properties, individuals). A punned IRI contributes every sense.")
                        .str("module_type", "STAR (default) | BOT | TOP.")
                        .str("source", "imports_closure (default) | active — which axioms to extract "
                                + "from.")
                        .str("iri", "IRI for the new module ontology (optional; anonymous if omitted). "
                                + "Ignored when 'path' is given and the file format has no ontology IRI.")
                        .str("path", "File to save the module to (save instead of loading into the "
                                + "workspace); format inferred from the extension (.ttl/.turtle, "
                                + ".owl/.rdf/.xml, .omn, .ofn/.fss, .owx, .obo — anything else is an "
                                + "error; no extension writes RDF/XML).")
                        .integer("timeout_ms", "Time budget in ms for the on-EDT phases (seed resolution "
                                + "+ closure snapshot, and the workspace load); default 60000. It does "
                                + "not interrupt the extraction itself, which runs off the UI thread.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    List<String> seeds = Tools.stringList(a, "signature");
                    if (seeds.isEmpty()) {
                        return Tools.error("Provide at least one seed entity in 'signature' (a name or "
                                + "full IRI of a class, property or individual to build the module around).");
                    }
                    ModuleType moduleType = moduleType(Tools.optString(a, "module_type"));
                    boolean fromClosure = fromClosure(Tools.optString(a, "source"));
                    String iriStr = Tools.optString(a, "iri");
                    String path = Tools.optString(a, "path");
                    if (path != null) {
                        // Fail fast on an unrecognized extension — BEFORE the confirmation dialog,
                        // the closure snapshot and the (potentially minutes-long) extraction, not
                        // after them in saveModuleToFile.
                        WriteTools.formatForPath(path, null);
                    }
                    int timeout = Tools.optInt(a, "timeout_ms", 60_000);
                    if (timeout <= 0) {
                        timeout = 60_000;
                    }
                    boolean loadIntoWorkspace = path == null;

                    // extract_module produces state (a new workspace ontology, or a written file) — gate
                    // it like every other write tool: read-only mode blocks it and the write-confirmation
                    // dialog applies. A file export truncates whatever is at 'path', so it must be gated
                    // too (not just the workspace-load branch).
                    CallToolResult denied = WriteTools.checkWriteAllowed(ctx, loadIntoWorkspace
                            ? "extract_module into a new workspace ontology"
                            : "extract_module export to " + path);
                    if (denied != null) {
                        return denied;
                    }

                    // Phase 1 (model thread, bounded): resolve seeds + snapshot the source axioms.
                    final int boundMs = timeout;
                    Prep prep = ctx.access().compute(mm -> prepare(mm, seeds, fromClosure), boundMs);
                    if (prep.signature.isEmpty()) {
                        return Tools.error("None of the seed(s) " + seeds + " resolved to an entity in "
                                + "the " + (fromClosure ? "imports closure" : "active ontology")
                                + ". Pass entity display names or full IRIs that exist there.");
                    }

                    // Phase 2 (off the EDT): the locality extraction (STAR's fixpoint is the heavy part).
                    Set<OWLAxiom> moduleAxioms = extract(prep.source, prep.signature, moduleType);
                    IRI moduleIri = iriStr == null ? null : IRI.create(iriStr);

                    if (!loadIntoWorkspace) {
                        return saveModuleToFile(moduleAxioms, moduleIri, path, seeds, prep, moduleType,
                                fromClosure);
                    }
                    return ctx.access().compute(mm -> loadModule(mm, moduleAxioms, moduleIri, seeds, prep,
                            moduleType, fromClosure), boundMs);
                }));
    }

    // ================================================================== phase 1 (model thread)

    /** The private source ontology to extract from, the resolved seed signature, and unresolved seeds. */
    private static final class Prep {
        final OWLOntology source;
        final Set<OWLEntity> signature;
        final List<String> unresolved;

        Prep(OWLOntology source, Set<OWLEntity> signature, List<String> unresolved) {
            this.source = source;
            this.signature = signature;
            this.unresolved = unresolved;
        }
    }

    /**
     * Resolve the seed signature (finder is EDT-only) and copy the source scope's axioms into a private,
     * Protégé-free ontology the off-EDT extractor can read safely. A seed that resolves to nothing is
     * recorded as unresolved rather than minted (extraction seeds must already exist).
     */
    private static Prep prepare(OWLModelManager mm, List<String> seeds, boolean fromClosure) {
        OWLOntology active = mm.getActiveOntology();
        Set<OWLOntology> scope = fromClosure
                ? active.getImportsClosure()
                : Collections.singleton(active);
        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        for (OWLOntology o : scope) {
            axioms.addAll(o.getAxioms());
        }
        OWLOntologyManager priv = OwlManagers.create();
        OWLOntology source;
        try {
            source = priv.createOntology(axioms);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not prepare a module-extraction workspace: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        Set<OWLEntity> signature = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        for (String ref : seeds) {
            Set<OWLEntity> es = Tools.findEntities(mm, ref);
            if (es.isEmpty()) {
                unresolved.add(ref);
            } else {
                signature.addAll(es);
            }
        }
        return new Prep(source, signature, unresolved);
    }

    // ================================================================== phase 2 (off the EDT)

    /**
     * Run the syntactic-locality extractor over {@code source} for {@code signature}. Pure OWL API — no
     * Protégé types — so it runs safely off the model thread and is unit-tested directly.
     */
    static Set<OWLAxiom> extract(OWLOntology source, Set<OWLEntity> signature, ModuleType moduleType) {
        SyntacticLocalityModuleExtractor extractor = new SyntacticLocalityModuleExtractor(
                source.getOWLOntologyManager(), source, moduleType);
        return extractor.extract(signature);
    }

    // ================================================================== phase 3 (deliver)

    /** Save the extracted module to {@code path} via a private manager (Protégé-free, off the EDT). */
    private static CallToolResult saveModuleToFile(Set<OWLAxiom> moduleAxioms, IRI moduleIri, String path,
            List<String> seeds, Prep prep, ModuleType moduleType, boolean fromClosure) {
        OWLOntologyManager modMgr = OwlManagers.create();
        OWLOntology module;
        try {
            module = moduleIri != null
                    ? modMgr.createOntology(moduleAxioms, moduleIri)
                    : modMgr.createOntology(moduleAxioms);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not assemble the module ontology: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        File file = new File(path).getAbsoluteFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return Tools.error("Cannot create directory: " + dir);
        }
        OWLDocumentFormat format = WriteTools.formatForPath(path, null);
        try {
            modMgr.saveOntology(module, format, IRI.create(file));
        } catch (OWLOntologyStorageException e) {
            throw new ToolArgException("Could not save the module: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        return result(moduleAxioms, moduleIri, seeds, prep, moduleType, fromClosure)
                .put("saved", true)
                .put("path", file.toString())
                .put("format", format.getClass().getSimpleName())
                .result();
    }

    /**
     * Create the module as a new ontology in Protégé's workspace (on the EDT). Uses
     * {@link OWLModelManager#createNewOntology} so the ontology is registered with Protégé (it fires
     * {@code ONTOLOGY_CREATED} and becomes active), then injects the extractor's axioms via the model
     * manager's own {@link OWLOntologyManager} — the axioms are shared immutable objects, safe to add to
     * a different manager's ontology.
     */
    private static CallToolResult loadModule(OWLModelManager mm, Set<OWLAxiom> moduleAxioms, IRI moduleIri,
            List<String> seeds, Prep prep, ModuleType moduleType, boolean fromClosure) {
        OWLOntologyID id = moduleIri != null
                ? new OWLOntologyID(Optional.of(moduleIri), Optional.<IRI>absent())
                : new OWLOntologyID();
        OWLOntology module;
        try {
            module = mm.createNewOntology(id, null);
        } catch (OWLOntologyCreationException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (moduleIri != null) {
                throw new ToolArgException("Could not create the module ontology '" + moduleIri
                        + "' (an ontology with that IRI may already be loaded — pass a different 'iri', "
                        + "or omit it for an anonymous module): " + msg);
            }
            throw new ToolArgException("Could not create the module ontology: " + msg);
        }
        mm.getOWLOntologyManager().addAxioms(module, moduleAxioms);
        return result(moduleAxioms, moduleIri, seeds, prep, moduleType, fromClosure)
                .put("loaded", "workspace")
                .put("note", "The module was loaded as a new, now-active ontology in the workspace. "
                        + "Save it with save_ontology (pass 'path'), or export directly next time with "
                        + "extract_module's 'path'.")
                .result();
    }

    /** The shared result skeleton: module coordinates, seed resolution, and axiom/entity counts. */
    private static Tools.Json result(Set<OWLAxiom> moduleAxioms, IRI moduleIri, List<String> seeds,
            Prep prep, ModuleType moduleType, boolean fromClosure) {
        Set<OWLEntity> signature = new LinkedHashSet<>();
        for (OWLAxiom ax : moduleAxioms) {
            signature.addAll(ax.getSignature());
        }
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("iri", moduleIri == null ? null : moduleIri.toString());
        module.put("anonymous", moduleIri == null);

        Map<String, Object> seedReport = new LinkedHashMap<>();
        seedReport.put("requested", seeds.size());
        seedReport.put("resolved", prep.signature.size());
        seedReport.put("unresolved", prep.unresolved);

        return Tools.json()
                .put("module", module)
                .put("module_type", moduleType.name())
                .put("source", fromClosure ? "imports_closure" : "active")
                .put("seeds", seedReport)
                .put("axioms", moduleAxioms.size())
                .put("entities", signature.size());
    }

    // ================================================================== argument parsing

    static ModuleType moduleType(String raw) {
        if (raw == null || raw.isEmpty()) {
            return ModuleType.STAR;
        }
        switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "STAR":
                return ModuleType.STAR;
            case "BOT":
            case "BOTTOM":
                return ModuleType.BOT;
            case "TOP":
                return ModuleType.TOP;
            default:
                throw new ToolArgException("Unknown module_type '" + raw + "'. Use STAR (default), BOT, "
                        + "or TOP.");
        }
    }

    private static boolean fromClosure(String raw) {
        if (raw == null || raw.isEmpty()) {
            return true;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("active")) {
            return false;
        }
        if (s.equals("imports_closure") || s.equals("closure") || s.equals("imports")) {
            return true;
        }
        throw new ToolArgException("Unknown source '" + raw + "'. Use imports_closure (default) or "
                + "active.");
    }
}
