package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/** Aggregates every tool specification the server exposes. */
public final class ToolCatalog {

    private ToolCatalog() {
    }

    public static List<SyncToolSpecification> buildAll(ToolContext ctx) {
        List<SyncToolSpecification> all = new ArrayList<>();
        all.addAll(ReadTools.specs(ctx));
        all.addAll(WriteTools.specs(ctx));
        all.addAll(EntityRefactorTools.specs(ctx));
        all.addAll(OntologyMetadataTools.specs(ctx));
        all.addAll(OntologyDocumentTools.specs(ctx));
        all.addAll(ReasonerTools.specs(ctx));
        return all;
    }
}
