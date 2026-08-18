package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.core.workspace.OfflineCatalog;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates an offline XML catalog against the effective project import lock. */
final class ImportCatalogValidator {

    private ImportCatalogValidator() {}

    static CallToolResult validateCatalog(ToolContext ctx, Map<String, Object> arguments) {
        return validateCatalog(ctx, arguments, DirectAccessPolicy.resolve(ctx, null));
    }

    static CallToolResult validateCatalog(
            ToolContext ctx, Map<String, Object> arguments, DirectAccessPolicy.Rules rules) {
        String configured = Tools.optString(arguments, "path");
        // Authorize the caller's path from the RAW request string. Pre-absolutizing here would
        // anchor a relative path at the process working directory, while the rules root it at the
        // policy's canonical project_root. Only the derived beside-active default is absolute.
        Path explicit = configured == null ? null : rules.readPath(configured);
        CatalogContext context =
                ctx.access()
                        .compute(
                                mm -> {
                                    java.io.File active =
                                            SidecarPaths.toFile(
                                                    mm.getOWLOntologyManager()
                                                            .getOntologyDocumentIRI(
                                                                    mm.getActiveOntology()));
                                    Path path =
                                            explicit != null
                                                    ? explicit
                                                    : active == null
                                                                    || active.getParentFile()
                                                                            == null
                                                            ? null
                                                            // Derived beside-active default, not a
                                                            // caller-selected path. Authorize it as
                                                            // implicit so policy_required mode does
                                                            // not misname it caller-selected.
                                                            : rules.implicitPath(
                                                                    active.getParentFile()
                                                                            .toPath()
                                                                            .resolve(
                                                                                    "catalog-v001.xml"),
                                                                    false);
                                    Set<String> imports = new LinkedHashSet<>();
                                    mm.getActiveOntology()
                                            .getImportsDeclarations()
                                            .forEach(d -> imports.add(d.getIRI().toString()));
                                    return new CatalogContext(path, imports);
                                });
        if (context.path == null)
            return Tools.error("Active ontology has no local catalog folder; pass path.");
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> nextCatalogs = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        try {
            OfflineCatalog.Document catalog = OfflineCatalog.read(context.path);
            errors.addAll(catalog.errors());
            for (OfflineCatalog.Entry entry : catalog.entries()) {
                String name = entry.name();
                String uri = entry.reference();
                names.add(name);
                String status;
                java.net.URI resolved = entry.resolved();
                if (resolved == null) {
                    status = "invalid_uri";
                } else {
                    try {
                        if (!"file".equalsIgnoreCase(resolved.getScheme())) {
                            status = "remote_forbidden";
                            errors.add(
                                    "remote catalog document is not allowed in no-network"
                                        + " validation: "
                                            + resolved);
                        } else {
                            Path local;
                            try {
                                local = rules.readPath(Path.of(resolved).toString());
                            } catch (ToolArgException refusal) {
                                // Report a refused target per entry without probing or leaking it.
                                local = null;
                            }
                            if (local == null) {
                                status = "policy_refused";
                                errors.add(
                                        "catalog document is not readable under the project policy:"
                                            + " "
                                                + uri);
                            } else {
                                status = Files.isRegularFile(local) ? "local_ok" : "local_missing";
                                if ("local_missing".equals(status)) {
                                    errors.add("missing catalog document: " + local);
                                }
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        status = "invalid_uri";
                        errors.add("catalog uri entry does not resolve to a valid URI: " + uri);
                    }
                }
                entries.add(Map.of("name", name, "uri", uri, "status", status));
            }
            catalog.nextCatalogs().forEach(next -> nextCatalogs.add(next.toString()));
        } catch (Exception e) {
            errors.add(
                    "could not parse catalog: "
                            + (e.getMessage() == null
                                    ? e.getClass().getSimpleName()
                                    : e.getMessage()));
        }
        List<String> unmapped = new ArrayList<>();
        List<String> delegated = new ArrayList<>();
        if (Tools.optBool(arguments, "compare_imports", false)) {
            for (String iri : context.imports) {
                if (names.contains(iri)) continue;
                // With a nextCatalog present the import may be mapped further down the chain. This
                // no-network validator never follows the chain, so it cannot assert a missing
                // mapping —
                // report the import as delegated (unverified) instead of failing the catalog.
                (nextCatalogs.isEmpty() ? unmapped : delegated).add(iri);
            }
        }
        boolean valid = errors.isEmpty() && unmapped.isEmpty();
        return Tools.json()
                .put("valid", valid)
                .put("catalog", context.path.toString())
                .put("entries", entries)
                .put("errors", errors)
                .put("next_catalogs", nextCatalogs)
                .put("unmapped_imports", unmapped)
                .put("delegated_imports", delegated)
                .result();
    }

    /** Catalog path and active ontology import IRIs captured together on the model thread. */
    private record CatalogContext(Path path, Set<String> imports) {}
}
