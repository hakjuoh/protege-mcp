package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;

/** Exact owner-bound HTTPS base containment shared by live and cached provider evidence. */
final class ProviderOriginBoundary {

    private ProviderOriginBoundary() { }

    static boolean contains(ProviderOwnerConfig.ResolvedProvider authority, URI target) {
        if (authority == null || target == null || !"https".equalsIgnoreCase(target.getScheme())
                || target.isOpaque() || target.getHost() == null || target.getUserInfo() != null
                || target.getRawFragment() != null) {
            return false;
        }
        URI owner = authority.origin().origin();
        if (!owner.getHost().equalsIgnoreCase(target.getHost())
                || effectivePort(owner) != effectivePort(target)) {
            return false;
        }
        String ownerPath = owner.getRawPath();
        String targetPath = target.getRawPath();
        if (targetPath == null || !safePath(targetPath)) {
            return false;
        }
        return ownerPath.isEmpty() || targetPath.equals(ownerPath)
                || targetPath.startsWith(ownerPath + "/");
    }

    private static boolean safePath(String path) {
        return path.isEmpty() || ProviderRequest.safeRelativePath(path);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }
}
