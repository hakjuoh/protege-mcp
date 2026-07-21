package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;
import java.time.Instant;

/** Sanitized successful response returned to a provider adapter by the restricted transport. */
public record ProviderResponse(byte[] body, URI sourceUrl, Instant receivedAt, int retries) {

    public static final int MAX_BODY_BYTES = 4 * 1_024 * 1_024;
    public static final int MAX_RETRIES = 2;

    public ProviderResponse {
        if (body == null || body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("provider response body exceeds its bound");
        }
        body = body.clone();
        if (sourceUrl == null || !"https".equalsIgnoreCase(sourceUrl.getScheme())
                || sourceUrl.isOpaque() || sourceUrl.getHost() == null
                || sourceUrl.getUserInfo() != null || sourceUrl.getRawQuery() != null
                || sourceUrl.getRawFragment() != null
                || sourceUrl.toASCIIString().length() > ProviderRequest.MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("provider source URL must be a sanitized HTTPS URL");
        }
        if (receivedAt == null || retries < 0 || retries > MAX_RETRIES) {
            throw new IllegalArgumentException("provider response metadata is invalid");
        }
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
