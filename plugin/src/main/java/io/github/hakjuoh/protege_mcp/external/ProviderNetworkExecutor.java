package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Central HTTPS-only provider transport with pinned DNS, bounded retries, and secret redaction. */
public final class ProviderNetworkExecutor implements ProviderTransport {

    public static final int MAX_RETRIES = ProviderResponse.MAX_RETRIES;
    static final int MAX_REDIRECTS = 2;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(2);
    private static final Duration BASE_RETRY_DELAY = Duration.ofMillis(250);
    private final ProviderOwnerConfig.ResolvedProvider authority;
    private final OwnerCredentialStore credentialStore;
    private final OwnerProviderCache.Acquisition acquisition;
    private final NetworkGate networkGate;
    private final AddressResolver resolver;
    private final HttpEngine engine;
    private final Clock clock;
    private final Sleeper sleeper;

    public ProviderNetworkExecutor(ProviderOwnerConfig.ResolvedProvider authority,
            OwnerCredentialStore credentialStore, NetworkGate networkGate) {
        this(authority, credentialStore, networkGate, null);
    }

    /** Create a restricted transport whose successful evidence may use the supplied cache authority. */
    public ProviderNetworkExecutor(ProviderOwnerConfig.ResolvedProvider authority,
            OwnerCredentialStore credentialStore, NetworkGate networkGate,
            OwnerProviderCache.Acquisition acquisition) {
        this(authority, credentialStore, networkGate, InetAddress::getAllByName,
                new PinnedHttpsEngine(), Clock.systemUTC(),
                duration -> Thread.sleep(duration.toMillis()), acquisition);
    }

    ProviderNetworkExecutor(ProviderOwnerConfig.ResolvedProvider authority,
            OwnerCredentialStore credentialStore, NetworkGate networkGate,
            AddressResolver resolver, HttpEngine engine, Clock clock, Sleeper sleeper) {
        this(authority, credentialStore, networkGate, resolver, engine, clock, sleeper, null);
    }

    ProviderNetworkExecutor(ProviderOwnerConfig.ResolvedProvider authority,
            OwnerCredentialStore credentialStore, NetworkGate networkGate,
            AddressResolver resolver, HttpEngine engine, Clock clock, Sleeper sleeper,
            OwnerProviderCache.Acquisition acquisition) {
        if (authority == null || networkGate == null || resolver == null || engine == null
                || clock == null || sleeper == null
                || authority.credential() != null && credentialStore == null) {
            throw new IllegalArgumentException("provider executor dependencies are invalid");
        }
        this.authority = authority;
        this.credentialStore = credentialStore;
        this.acquisition = acquisition;
        this.networkGate = networkGate;
        this.resolver = resolver;
        this.engine = engine;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public ProviderResponse get(ProviderRequest request) throws ProviderFailure {
        URI initialTarget = target(request);
        List<byte[]> requestCanaries = new ArrayList<>(MAX_RETRIES + 1);
        try {
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                final Exchange exchange;
                try {
                    exchange = executeChain(initialTarget, requestCanaries);
                } catch (InvalidResponseException invalid) {
                    throw new ProviderFailure("provider_response_invalid",
                            "Provider returned an invalid bounded response", false);
                } catch (ProviderFailure failure) {
                    if (!failure.retryable() || attempt == MAX_RETRIES) {
                        if (failure.code().equals("provider_dns_failed")) {
                            throw new ProviderFailure(failure.code(), failure.getMessage(), true,
                                    Map.of("attempts", attempt + 1), null);
                        }
                        throw failure;
                    }
                    sleep(backoff(attempt));
                    continue;
                } catch (IOException | RuntimeException failure) {
                    if (attempt == MAX_RETRIES) {
                        throw new ProviderFailure("provider_transport_failed",
                                "Provider HTTPS request failed", true,
                                Map.of("attempts", attempt + 1), null);
                    }
                    sleep(backoff(attempt));
                    continue;
                }
                RawResponse response = exchange.response();
                int status = response.status();
                if (status == 200) {
                    if (acquisition != null) {
                        acquisition.recordSuccess(authority, exchange.scope(), exchange.target());
                    }
                    return new ProviderResponse(response.body(), sanitized(exchange.target()),
                            clock.instant(), attempt);
                }
                if (status >= 300 && status < 400) {
                    throw redirectRefused(status);
                }
                if ((status == 429 || status >= 500) && attempt < MAX_RETRIES) {
                    Duration requested = status == 429
                            ? retryAfter(response.headers()) : Duration.ZERO;
                    sleep(requested.isZero() ? backoff(attempt) : requested);
                    continue;
                }
                throw statusFailure(status, attempt + 1);
            }
            throw new IllegalStateException("unreachable provider retry state");
        } finally {
            requestCanaries.forEach(value -> Arrays.fill(value, (byte) 0));
            requestCanaries.clear();
        }
    }

    private Exchange executeChain(URI initialTarget, List<byte[]> requestCanaries)
            throws ProviderFailure, IOException {
        URI current = initialTarget;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (redirects == 0) authorizeInitial(current);
            else authorizeRedirect(current);
            InetAddress[] addresses = addresses(current.getHost());
            Attempt attempt = requestOnce(current, addresses, requestCanaries);
            RawResponse response = attempt.response();
            if (!redirectStatus(response.status())) {
                return new Exchange(current, response, attempt.scope());
            }
            if (authority.credential() != null || redirects == MAX_REDIRECTS) {
                throw redirectRefused(response.status());
            }
            current = redirectTarget(current, response);
        }
        throw new IllegalStateException("unreachable provider redirect state");
    }

    private Attempt requestOnce(URI target, InetAddress[] addresses,
            List<byte[]> requestCanaries)
            throws ProviderFailure, IOException {
        Map<String, String> headers = baseHeaders();
        OwnerCredentialStore.CredentialLease lease = null;
        byte[] secret = null;
        try {
            if (authority.credential() != null) {
                // Reopen on every retry so deletion/rotation cannot retransmit a stale generation.
                lease = credentialStore.open(authority.credential().id());
                secret = lease.copySecret();
                requestCanaries.add(secret);
                String value = new String(secret, StandardCharsets.US_ASCII);
                if (authority.credential().scheme() == ProviderOwnerConfig.AuthScheme.BEARER) {
                    value = "Bearer " + value;
                }
                headers.put(authority.credential().header(), value);
            }
            String requestScope = authority.cacheScopeFingerprint(lease);
            if (acquisition != null) acquisition.authorizeAttempt(authority, requestScope, target);
            RawResponse response = engine.get(target,
                    Collections.unmodifiableMap(headers), addresses);
            if (secret != null) {
                byte[] body = response.body();
                for (byte[] canary : requestCanaries) {
                    if (containsCanary(body, canary)) {
                        throw new ProviderFailure("provider_redaction_failed",
                                "Provider response failed secret redaction", false);
                    }
                }
            }
            return new Attempt(response, requestScope);
        } finally {
            if (lease != null) lease.close();
            headers.clear();
        }
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Accept-Encoding", "identity");
        headers.put("User-Agent", "protege-mcp-provider/0.8");
        return headers;
    }

    private void authorizeInitial(URI target) throws ProviderFailure {
        URI origin = authority.origin().origin();
        String ownerPath = origin.getRawPath();
        String targetPath = target.getRawPath();
        boolean withinOwnerPath = targetPath.equals(ownerPath)
                || targetPath.startsWith(ownerPath + "/");
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null
                || !sameOrigin(origin, target) || !withinOwnerPath) {
            throw new ProviderFailure("provider_origin_unbound",
                    "Provider request escaped its exact owner origin", false);
        }
        authorizeGate(ProviderNetworkUris.origin(origin));
    }

    private void authorizeRedirect(URI target) throws ProviderFailure {
        if (!validHttpsTarget(target)) throw redirectRefused(302);
        authorizeGate(ProviderNetworkUris.origin(target));
    }

    private void authorizeGate(URI target) throws ProviderFailure {
        try {
            networkGate.authorize(target);
        } catch (ProviderFailure | RuntimeException denied) {
            throw new ProviderFailure("provider_network_denied",
                    "Provider network authority denied the request", false);
        }
    }

    private InetAddress[] addresses(String host) throws ProviderFailure {
        final InetAddress[] values;
        try {
            values = resolver.resolve(host);
        } catch (IOException | RuntimeException failure) {
            throw new ProviderFailure("provider_dns_failed",
                    "Provider DNS resolution failed", true);
        }
        if (values == null || values.length < 1 || values.length > 16) {
            throw new ProviderFailure("provider_address_refused",
                    "Provider address set is invalid", false);
        }
        InetAddress[] copy = values.clone();
        for (InetAddress address : copy) {
            if (!ProviderAddressPolicy.allowed(address,
                    authority.origin().testOnlyLoopback())) {
                throw new ProviderFailure("provider_address_refused",
                        "Provider address is outside the authorized network class", false);
            }
        }
        return copy;
    }

    private URI target(ProviderRequest request) throws ProviderFailure {
        try {
            URI origin = authority.origin().origin();
            StringBuilder value = new StringBuilder(origin.toASCIIString())
                    .append(request.relativePath());
            if (!request.query().isEmpty()) {
                value.append('?');
                boolean first = true;
                for (Map.Entry<String, String> entry : request.query().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    if (!first) value.append('&');
                    first = false;
                    value.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                }
            }
            URI target = URI.create(value.toString());
            if (target.toASCIIString().length() > 32_768
                    || sanitized(target).toASCIIString().length()
                            > ProviderRequest.MAX_PATH_LENGTH) {
                throw new IllegalArgumentException();
            }
            return target;
        } catch (RuntimeException invalid) {
            throw new ProviderFailure("provider_request_invalid",
                    "Provider request URL is invalid", false);
        }
    }

    private URI redirectTarget(URI current, RawResponse response) throws ProviderFailure {
        String location = response.headers().get("location");
        if (location == null || location.isBlank()) throw redirectRefused(response.status());
        try {
            URI target = current.resolve(URI.create(location));
            if (!validHttpsTarget(target)) throw new IllegalArgumentException();
            return target;
        } catch (RuntimeException invalid) {
            throw redirectRefused(response.status());
        }
    }

    private static boolean validHttpsTarget(URI target) {
        if (target == null || !"https".equalsIgnoreCase(target.getScheme()) || target.isOpaque()
                || target.getHost() == null || target.getUserInfo() != null
                || target.getRawFragment() != null) return false;
        try {
            return target.toASCIIString().length() <= 32_768
                    && sanitized(target).toASCIIString().length()
                            <= ProviderRequest.MAX_PATH_LENGTH;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean redirectStatus(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    private static ProviderFailure redirectRefused(int status) {
        return new ProviderFailure("provider_redirect_refused",
                "Provider redirect is not authorized", false,
                Map.of("status", status), null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static URI sanitized(URI target) {
        String value = target.toASCIIString();
        int query = value.indexOf('?');
        return URI.create(query < 0 ? value : value.substring(0, query));
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private static ProviderFailure statusFailure(int status, int attempts) {
        if (status == 404) return new ProviderFailure("provider_term_not_found",
                "Provider term was not found", false, Map.of("status", status), null);
        if (status == 401 || status == 403) return new ProviderFailure(
                "provider_authorization_failed", "Provider authorization failed", false,
                Map.of("status", status), null);
        return new ProviderFailure("provider_http_error", "Provider returned an HTTP error",
                status == 429 || status >= 500,
                Map.of("status", status, "attempts", attempts), null);
    }

    private Duration retryAfter(Map<String, String> headers) {
        String value = headers.get("retry-after");
        if (value == null) return Duration.ZERO;
        try {
            long seconds = Long.parseLong(value.trim());
            return cap(Duration.ofSeconds(Math.max(0, seconds)));
        } catch (RuntimeException notSeconds) {
            try {
                Instant time = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration delay = Duration.between(clock.instant(), time);
                return cap(delay.isNegative() ? Duration.ZERO : delay);
            } catch (RuntimeException invalid) {
                return Duration.ZERO;
            }
        }
    }

    private static Duration cap(Duration value) {
        return value.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : value;
    }

    private static Duration backoff(int attempt) {
        return BASE_RETRY_DELAY.multipliedBy(attempt + 1L);
    }

    private void sleep(Duration delay) throws ProviderFailure {
        if (delay.isZero()) return;
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderFailure("provider_retry_interrupted",
                    "Provider retry was interrupted", true);
        } catch (RuntimeException failure) {
            throw new ProviderFailure("provider_retry_failed",
                    "Provider retry scheduling failed", true);
        }
    }

    static boolean containsCanary(byte[] body, byte[] secret) {
        if (containsBytes(body, secret)) return true;
        byte[] escaped = jsonEscaped(secret);
        if (escaped != null) {
            try {
                if (containsBytes(body, escaped)) return true;
            } finally {
                Arrays.fill(escaped, (byte) 0);
            }
        }
        try {
            String expected = new String(secret, StandardCharsets.UTF_8);
            return ProviderJsonCanary.anyTextMatches(body, value -> value.contains(expected));
        } catch (ProviderJsonCanary.ScanLimitException constrained) {
            return true;
        } catch (IOException invalidJson) {
            return false;
        }
    }

    private static boolean containsBytes(byte[] body, byte[] secret) {
        if (secret.length == 0) return true;
        if (secret.length > body.length) return false;
        int[] prefix = new int[secret.length];
        for (int index = 1, matched = 0; index < secret.length;) {
            if (secret[index] == secret[matched]) prefix[index++] = ++matched;
            else if (matched > 0) matched = prefix[matched - 1];
            else prefix[index++] = 0;
        }
        for (int index = 0, matched = 0; index < body.length;) {
            if (body[index] == secret[matched]) {
                index++;
                if (++matched == secret.length) return true;
            } else if (matched > 0) {
                matched = prefix[matched - 1];
            } else {
                index++;
            }
        }
        return false;
    }

    private static byte[] jsonEscaped(byte[] secret) {
        int escapes = 0;
        for (byte value : secret) if (value == '"' || value == '\\') escapes++;
        if (escapes == 0) return null;
        byte[] result = new byte[secret.length + escapes];
        int output = 0;
        for (byte value : secret) {
            if (value == '"' || value == '\\') result[output++] = '\\';
            result[output++] = value;
        }
        return result;
    }

    @FunctionalInterface
    public interface NetworkGate {
        void authorize(URI exactOrigin) throws ProviderFailure;
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    interface HttpEngine {
        RawResponse get(URI target, Map<String, String> headers, InetAddress[] addresses)
                throws IOException;
    }

    static final class InvalidResponseException extends IOException {
        private static final long serialVersionUID = 1L;

        InvalidResponseException() {
            super("provider response violates its transport bounds");
        }
    }

    private record Attempt(RawResponse response, String scope) { }
    private record Exchange(URI target, RawResponse response, String scope) { }

    record RawResponse(int status, Map<String, String> headers, byte[] body) {
        RawResponse {
            if (status < 100 || status > 599 || body == null
                    || body.length > ProviderResponse.MAX_BODY_BYTES || headers == null
                    || headers.size() > 64) throw new IllegalArgumentException("raw response is invalid");
            Map<String, String> copy = new LinkedHashMap<>();
            headers.forEach((key, value) -> {
                if (key == null || value == null || key.length() > 128 || value.length() > 4_096
                        || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("raw response header is invalid");
                }
                if (copy.putIfAbsent(key.toLowerCase(Locale.ROOT), value) != null) {
                    throw new IllegalArgumentException("raw response header is ambiguous");
                }
            });
            headers = Collections.unmodifiableMap(copy);
            body = body.clone();
        }

        @Override public byte[] body() { return body.clone(); }
    }

}
