package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import io.github.hakjuoh.protege_mcp.server.AccessTokenFilter;

/**
 * The broker's {@code /mcp} auth gate: accepts the static bearer token of <em>any</em> registered
 * Protégé process (two processes can briefly disagree while a token regeneration propagates through
 * the preferences store), and otherwise delegates to the standard {@link AccessTokenFilter} for
 * OAuth access tokens and its 401 challenge.
 */
public final class BrokerTokenFilter implements Filter {

    private static final String PREFIX = "Bearer ";

    private final InstanceRegistry registry;
    private final AccessTokenFilter delegate;

    public BrokerTokenFilter(InstanceRegistry registry, AccessTokenFilter delegate) {
        this.registry = registry;
        this.delegate = delegate;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String header = ((HttpServletRequest) request).getHeader("Authorization");
        String token = header != null && header.startsWith(PREFIX) ? header.substring(PREFIX.length()) : null;
        if (registry.matchesAnyToken(token)) {
            chain.doFilter(request, response);
            return;
        }
        delegate.doFilter(request, response, chain);
    }
}
