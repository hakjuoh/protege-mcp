package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.xmlcatalog.CatalogUtilities;
import org.protege.xmlcatalog.Prefer;
import org.protege.xmlcatalog.XMLCatalog;
import org.protege.xmlcatalog.entry.UriEntry;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Generate/refresh an OASIS XML catalog ({@code catalog-v001.xml}) for the active ontology's imports,
 * mapping each imported ontology IRI to the local file it loaded from (relative to the catalog folder).
 * This is the piece that lets a tool-reconstructed multi-module ontology be re-opened in Protégé with
 * its imports resolving offline — catalog files live outside the OWL axiom model, so no other tool can
 * produce them. Writing the catalog is a filesystem write (not an {@code applyChange}); it is gated by
 * the same read-only/confirmation switch as the other write tools but is NOT on the undo stack.
 */
public final class CatalogTools {

    private CatalogTools() {
    }

    /** Protégé's catalog file name (matches OntologyCatalogManager.CATALOG_NAME). */
    private static final String CATALOG_NAME = "catalog-v001.xml";

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("write_catalog",
                (ex, req) -> {
                    Map<String, Object> a = Tools.args(req);
                    String configuredPath = Tools.optString(a, "path");
                    boolean directOnly = Tools.optBool(a, "direct_imports_only", false);
                    DirectAccessPolicy.Rules accessRules = DirectAccessPolicy.resolve(ctx, ex);
                    final String path;
                    if (configuredPath != null) {
                        path = accessRules.writePath(configuredPath).toString();
                    } else {
                        Path target = ctx.access().compute(mm -> {
                            OWLOntology active = mm.getActiveOntology();
                            return SidecarPaths.resolveSidecarFile(mm.getOWLOntologyManager(), active,
                                    null, CATALOG_NAME).toPath();
                        });
                        accessRules.implicitPath(target, true);
                        path = null;
                    }
                    CallToolResult denied = WriteTools.checkWriteAllowed(ctx,
                            "write catalog-v001.xml for the active ontology's imports");
                    if (denied != null) {
                        return denied;
                    }
                    return ctx.access().compute(mm -> writeCatalog(mm, path, directOnly));
                });
    }

    private static CallToolResult writeCatalog(OWLModelManager mm, String path, boolean directOnly) {
        OWLOntology active = mm.getActiveOntology();
        OWLOntologyManager om = mm.getOWLOntologyManager();

        File catalogFile = SidecarPaths.resolveSidecarFile(om, active, path, CATALOG_NAME);
        File folder = catalogFile.getParentFile();
        if (folder == null) {
            return Tools.error("Cannot determine a catalog folder from path '" + path + "'.");
        }
        if (!folder.isDirectory() && !folder.mkdirs()) {
            return Tools.error("Catalog folder does not exist and could not be created: " + folder);
        }

        XMLCatalog catalog = new XMLCatalog(folder.toURI());
        catalog.setPrefer(Prefer.PUBLIC);

        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();   // dedup catalog entry names

        // Map by IMPORT DECLARATION, not just by imported-ontology IRI: the IRI written in owl:imports
        // is what Protégé resolves on reopen, and it can differ from the imported ontology's own IRI
        // (e.g. a BFO / cache document URL whose ontology IRI is different). For each declaration in
        // scope we map the declaration IRI AND the resolved ontology's IRI/version, all → the local
        // file it loaded from, so the catalog resolves the import however it is referenced.
        Set<OWLImportsDeclaration> declarations = new LinkedHashSet<>();
        if (directOnly) {
            declarations.addAll(active.getImportsDeclarations());
        } else {
            for (OWLOntology o : active.getImportsClosure()) {
                declarations.addAll(o.getImportsDeclarations());
            }
        }

        for (OWLImportsDeclaration decl : declarations) {
            String declIri = decl.getIRI().toString();
            OWLOntology imported = om.getImportedOntology(decl);
            if (imported == null) {
                skipped.add(skip(declIri, "unresolved import (not loaded in the workspace)"));
                continue;
            }
            IRI docIri = om.getOntologyDocumentIRI(imported);
            File docFile = SidecarPaths.toFile(docIri);
            if (docFile == null || !docFile.isFile()) {
                skipped.add(skip(declIri, "no local file document (document IRI: " + docIri + ")"));
                continue;
            }
            URI relative = CatalogUtilities.relativize(docFile.toURI(), catalog);
            addEntry(catalog, entries, seenNames, declIri, relative);
            OWLOntologyID id = imported.getOntologyID();
            if (id.getOntologyIRI().isPresent()) {
                addEntry(catalog, entries, seenNames, id.getOntologyIRI().get().toString(), relative);
            }
            if (id.getVersionIRI().isPresent()) {
                addEntry(catalog, entries, seenNames, id.getVersionIRI().get().toString(), relative);
            }
        }

        try {
            CatalogUtilities.save(catalog, catalogFile);
        } catch (IOException e) {
            return Tools.error("Failed to write catalog: " + e.getMessage());
        }

        return Tools.json()
                .put("catalog", catalogFile.toString())
                .put("entries", entries)
                .put("entry_count", entries.size())
                .put("skipped", skipped)
                .result();
    }

    private static void addEntry(XMLCatalog catalog, List<Map<String, Object>> entries,
            Set<String> seenNames, String name, URI uri) {
        if (!seenNames.add(name)) {
            return;   // a name can be reached via both a declaration IRI and an ontology IRI — map once
        }
        catalog.addEntry(new UriEntry("User Entered Import Resolution", catalog, name, uri, null));
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", name);
        e.put("uri", uri.toString());
        entries.add(e);
    }

    private static Map<String, Object> skip(String importIri, String reason) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("import", importIri);
        s.put("reason", reason);
        return s;
    }
}
