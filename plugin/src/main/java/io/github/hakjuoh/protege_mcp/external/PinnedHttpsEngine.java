package io.github.hakjuoh.protege_mcp.external;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.CookieJar;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** One-request HTTPS engine whose connector can use only the executor-validated DNS answers. */
final class PinnedHttpsEngine implements ProviderNetworkExecutor.HttpEngine {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);
    private final SSLSocketFactory testSocketFactory;
    private final X509TrustManager testTrustManager;

    PinnedHttpsEngine() {
        this.testSocketFactory = null;
        this.testTrustManager = null;
    }

    /** Test-only TLS trust seam for the loopback fake provider. */
    PinnedHttpsEngine(SSLSocketFactory testSocketFactory, X509TrustManager testTrustManager) {
        if (testSocketFactory == null || testTrustManager == null) {
            throw new IllegalArgumentException("test TLS dependencies are invalid");
        }
        this.testSocketFactory = testSocketFactory;
        this.testTrustManager = testTrustManager;
    }

    @Override
    public ProviderNetworkExecutor.RawResponse get(URI target, Map<String, String> headers,
            InetAddress[] addresses) throws IOException {
        if (target == null || !"https".equalsIgnoreCase(target.getScheme())
                || target.getHost() == null || addresses == null || addresses.length == 0) {
            throw new IOException("provider HTTPS engine input is invalid");
        }
        okhttp3.Dns pinned = host -> {
            if (!host.equalsIgnoreCase(target.getHost())) throw new UnknownHostException();
            return List.copyOf(Arrays.asList(addresses.clone()));
        };
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder().dns(pinned)
                .proxy(Proxy.NO_PROXY)
                .proxyAuthenticator(Authenticator.NONE).authenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES).cache(null).eventListener(EventListener.NONE)
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .connectionSpecs(List.of(ConnectionSpec.RESTRICTED_TLS))
                .protocols(List.of(Protocol.HTTP_1_1)).followRedirects(false)
                .followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(CONNECT_TIMEOUT).readTimeout(READ_TIMEOUT)
                .callTimeout(CALL_TIMEOUT);
        if (testSocketFactory != null) {
            clientBuilder.sslSocketFactory(testSocketFactory, testTrustManager);
        }
        OkHttpClient client = clientBuilder.build();
        Request.Builder request = new Request.Builder().url(target.toASCIIString()).get();
        headers.forEach(request::header);
        try (Response response = client.newCall(request.build()).execute()) {
            ResponseBody entity = response.body();
            long length = entity == null ? 0 : entity.contentLength();
            if (length > ProviderResponse.MAX_BODY_BYTES) {
                throw new ProviderNetworkExecutor.InvalidResponseException();
            }
            byte[] body = readBounded(entity == null ? InputStream.nullInputStream()
                    : entity.byteStream());
            List<String> retryValues = response.headers("Retry-After");
            if (retryValues.size() > 1) {
                throw new ProviderNetworkExecutor.InvalidResponseException();
            }
            List<String> locationValues = response.headers("Location");
            if (locationValues.size() > 1) {
                throw new ProviderNetworkExecutor.InvalidResponseException();
            }
            String retryAfter = retryValues.isEmpty() ? null : retryValues.get(0);
            String location = locationValues.isEmpty() ? null : locationValues.get(0);
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            if (retryAfter != null) responseHeaders.put("retry-after", retryAfter);
            if (location != null) responseHeaders.put("location", location);
            try {
                return new ProviderNetworkExecutor.RawResponse(
                        response.code(), responseHeaders, body);
            } catch (IllegalArgumentException invalid) {
                throw new ProviderNetworkExecutor.InvalidResponseException();
            }
        } finally {
            client.connectionPool().evictAll();
        }
    }

    static byte[] readBounded(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            for (int count; (count = input.read(buffer)) >= 0;) {
                total += count;
                if (total > ProviderResponse.MAX_BODY_BYTES) {
                    throw new ProviderNetworkExecutor.InvalidResponseException();
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
