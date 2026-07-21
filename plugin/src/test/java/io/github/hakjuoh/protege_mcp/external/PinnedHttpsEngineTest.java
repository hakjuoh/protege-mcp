package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.Test;

class PinnedHttpsEngineTest {

    @Test
    void responseStreamIsReadAtTheExactPublicBound() throws Exception {
        byte[] exact = new byte[ProviderResponse.MAX_BODY_BYTES];
        exact[exact.length - 1] = 42;
        assertArrayEquals(exact,
                PinnedHttpsEngine.readBounded(new ByteArrayInputStream(exact)));
    }

    @Test
    void oneByteOverTheResponseBoundIsRefused() {
        byte[] oversized = new byte[ProviderResponse.MAX_BODY_BYTES + 1];
        assertThrows(IOException.class,
                () -> PinnedHttpsEngine.readBounded(new ByteArrayInputStream(oversized)));
    }

    @Test
    void realTlsRequestUsesPinnedDnsHeadersAndBoundedMetadata() throws Exception {
        try (ProviderTlsFixture fixture = new ProviderTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Retry-After", "2").setBody("{\"ok\":true}"));
            ProviderNetworkExecutor.RawResponse response = fixture.engine.get(
                    fixture.uri("/api/search?q=sensitive"),
                    Map.of("Accept", "application/json", "Authorization", "Bearer canary"),
                    new InetAddress[] {InetAddress.getLoopbackAddress()});

            assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), response.body());
            RecordedRequest request = fixture.server.takeRequest();
            org.junit.jupiter.api.Assertions.assertEquals(
                    "/api/search?q=sensitive", request.getPath());
            org.junit.jupiter.api.Assertions.assertEquals(
                    "Bearer canary", request.getHeader("Authorization"));
            org.junit.jupiter.api.Assertions.assertEquals("2", response.headers().get("retry-after"));
        }
    }

    @Test
    void realTlsRedirectIsReturnedWithoutFollowingTheLocation() throws Exception {
        try (ProviderTlsFixture fixture = new ProviderTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", "https://attacker.invalid/token"));
            fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("must not read"));
            ProviderNetworkExecutor.RawResponse response = fixture.engine.get(
                    fixture.uri("/api"), Map.of(),
                    new InetAddress[] {InetAddress.getLoopbackAddress()});

            org.junit.jupiter.api.Assertions.assertEquals(302, response.status());
            assertEquals("https://attacker.invalid/token", response.headers().get("location"));
            org.junit.jupiter.api.Assertions.assertEquals(1, fixture.server.getRequestCount());
        }
    }

    @Test
    void realTlsRejectsOversizedAndAmbiguousResponsesWithoutRetryClassification() throws Exception {
        try (ProviderTlsFixture fixture = new ProviderTlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(429)
                    .addHeader("Retry-After", "1").addHeader("Retry-After", "2"));
            assertThrows(ProviderNetworkExecutor.InvalidResponseException.class,
                    () -> fixture.engine.get(fixture.uri("/ambiguous"), Map.of(),
                            new InetAddress[] {InetAddress.getLoopbackAddress()}));

            fixture.server.enqueue(new MockResponse().setResponseCode(200).setChunkedBody(
                    "x".repeat(ProviderResponse.MAX_BODY_BYTES + 1), 8_192));
            assertThrows(ProviderNetworkExecutor.InvalidResponseException.class,
                    () -> fixture.engine.get(fixture.uri("/oversized"), Map.of(),
                            new InetAddress[] {InetAddress.getLoopbackAddress()}));
        }
    }

    @Test
    void realTlsKeepsHostnameVerificationAndIgnoresAmbientProxyProperties() throws Exception {
        Logger mockServerLog = Logger.getLogger(MockWebServer.class.getName());
        Level oldLevel = mockServerLog.getLevel();
        mockServerLog.setLevel(Level.OFF);
        try (ProviderTlsFixture fixture = new ProviderTlsFixture()) {
            URI wrongHost = URI.create("https://wrong.test:" + fixture.server.getPort() + "/api");
            assertThrows(IOException.class, () -> fixture.engine.get(wrongHost, Map.of(),
                    new InetAddress[] {InetAddress.getLoopbackAddress()}));

            String oldHost = System.getProperty("https.proxyHost");
            String oldPort = System.getProperty("https.proxyPort");
            try {
                System.setProperty("https.proxyHost", "127.0.0.1");
                System.setProperty("https.proxyPort", "1");
                fixture.server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
                assertEquals(200, fixture.engine.get(fixture.uri("/direct"), Map.of(),
                        new InetAddress[] {InetAddress.getLoopbackAddress()}).status());
            } finally {
                restore("https.proxyHost", oldHost);
                restore("https.proxyPort", oldPort);
            }
        } finally {
            mockServerLog.setLevel(oldLevel);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

}
