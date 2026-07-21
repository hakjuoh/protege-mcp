package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;

/** Shared canonical network-origin projection for policy checks and cached source evidence. */
final class ProviderNetworkUris {

    private ProviderNetworkUris() { }

    static URI origin(URI target) {
        if (target == null || !"https".equalsIgnoreCase(target.getScheme())
                || target.getHost() == null || target.getUserInfo() != null) {
            throw new IllegalArgumentException("provider HTTPS origin is invalid");
        }
        return URI.create("https://" + target.getRawAuthority());
    }
}
