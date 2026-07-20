package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;

/** Environment-neutral project snapshot boundary; implementations own all OWLAPI managers. */
public interface ProjectWorkspace {

    String workspaceId();

    WorkspaceSnapshot capture() throws IOException;

    boolean isCurrent(WorkspaceSnapshot snapshot);
}
