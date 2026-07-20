package io.github.hakjuoh.protege_mcp.tools;

import java.util.List;
import java.util.Map;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.core.qc.ImportGraphAnalysisService;
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
        return new ImportReport(ImportGraphAnalysisService.analyze(root));
    }

    static String sourceType(String documentIri) {
        return ImportGraphAnalysisService.sourceType(documentIri);
    }

    /** Immutable adapter retained for existing plugin callers. */
    static final class ImportReport {
        final Map<String, Object> root;
        final List<Map<String, Object>> ontologies;
        final List<Map<String, Object>> imports;
        final List<Map<String, Object>> resolvedImports;
        final List<Map<String, Object>> missingImports;
        final List<Map<String, Object>> cycles;
        final List<Map<String, Object>> conflicts;
        final Map<String, Object> summary;

        ImportReport(ImportGraphAnalysisService.Report report) {
            this(report.root(), report.ontologies(), report.imports(), report.resolvedImports(),
                    report.missingImports(), report.cycles(), report.conflicts(), report.summary());
        }

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
}
