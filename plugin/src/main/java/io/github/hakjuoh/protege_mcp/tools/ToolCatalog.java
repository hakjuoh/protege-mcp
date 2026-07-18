package io.github.hakjuoh.protege_mcp.tools;

import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/** Aggregates every tool specification the server exposes. */
public final class ToolCatalog {

    private ToolCatalog() {
    }

    /**
     * Every tool provider, in registration order — the single source of truth. A provider is declared
     * exactly once here; {@link #buildAll} lets each contribute into one shared {@link ToolRegistry}.
     * Package-private so the aggregation test iterates this same list rather than re-listing the
     * providers (which previously duplicated this order in two places and could silently drift).
     */
    static final List<ToolProvider> PROVIDERS = List.of(
            ReadTools::register,
            ContextTools::register,
            RevisionTools::register,
            WriteTools::register,
            PreviewTools::register,
            ChangeSetTools::register,
            CurationTools::register,
            EntityRefactorTools::register,
            OntologyMetadataTools::register,
            OntologyDocumentTools::register,
            ModuleTools::register,
            RuleTools::register,
            ImportTools::register,
            ImportLockTools::register,
            CatalogTools::register,
            DiffTools::register,
            ImpactTools::register,
            ReasonerTools::register,
            SparqlTools::register,
            SparqlAuthoringTools::register,
            ValidationTools::register,
            GovernanceTools::register,
            CompetencyQuestionTools::register,
            VerifyOntologyTools::register,
            ShaclTools::register,
            ProjectPolicyTools::register,
            QcSuiteTools::register,
            ReleaseTools::register);

    public static List<SyncToolSpecification> buildAll(ToolContext ctx) {
        ToolRegistry registry = new ToolRegistry();
        for (ToolProvider provider : PROVIDERS) {
            provider.register(registry, ctx);
        }
        return registry.build();
    }
}
