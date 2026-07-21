package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/** Shared real-TLS loopback fixture for the provider executor and adapter integration tests. */
final class ProviderTlsFixture implements AutoCloseable {

    final MockWebServer server = new MockWebServer();
    final PinnedHttpsEngine engine;
    private final String host;

    ProviderTlsFixture() throws IOException {
        this("provider.test");
    }

    ProviderTlsFixture(String host) throws IOException {
        this.host = host;
        HeldCertificate certificate = new HeldCertificate.Builder()
                .commonName(host).addSubjectAlternativeName(host).build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(certificate).build();
        HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate()).build();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.start(InetAddress.getLoopbackAddress(), 0);
        engine = new PinnedHttpsEngine(clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager());
    }

    URI uri(String path) {
        return URI.create("https://" + host + ":" + server.getPort() + path);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
