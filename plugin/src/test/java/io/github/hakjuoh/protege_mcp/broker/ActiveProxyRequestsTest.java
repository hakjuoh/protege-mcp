package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

class ActiveProxyRequestsTest {

    @Test
    void revocationClosesAttachedBodiesAndPermanentlyRejectsThatClient() {
        ActiveProxyRequests requests = new ActiveProxyRequests();
        AuthenticatedPrincipal owner = AuthenticatedPrincipal.oauthAdmin("client-a", "A", "grant-a");
        ActiveProxyRequests.Reservation reservation = requests.open(owner, "session-a");
        TrackingInputStream body = new TrackingInputStream();

        assertNotNull(reservation);
        assertTrue(reservation.attach(body));
        assertEquals(1, requests.terminateClient("client-a"));
        assertTrue(body.closed);
        assertNull(requests.open(owner, null),
                "a request authenticated just before token deletion must still fail after revocation");
        reservation.close();
    }

    @Test
    void revocationBetweenReservationAndAttachFailsClosed() {
        ActiveProxyRequests requests = new ActiveProxyRequests();
        AuthenticatedPrincipal owner = AuthenticatedPrincipal.oauthAdmin("client-race", "R", "grant-r");
        ActiveProxyRequests.Reservation reservation = requests.open(owner, null);

        assertEquals(1, requests.terminateClient("client-race"));
        TrackingInputStream lateBody = new TrackingInputStream();
        assertFalse(reservation.attach(lateBody));
        assertTrue(lateBody.closed,
                "the upstream response arriving after revocation must be closed without forwarding");
        reservation.close();
    }

    @Test
    void clientAndSessionTerminationArePreciselyScoped() {
        ActiveProxyRequests requests = new ActiveProxyRequests();
        AuthenticatedPrincipal a = AuthenticatedPrincipal.oauthAdmin("a", "A", "ga");
        AuthenticatedPrincipal b = AuthenticatedPrincipal.oauthAdmin("b", "B", "gb");
        ActiveProxyRequests.Reservation a1 = requests.open(a, "s-a");
        ActiveProxyRequests.Reservation b1 = requests.open(b, "s-b");
        TrackingInputStream aBody = new TrackingInputStream();
        TrackingInputStream bBody = new TrackingInputStream();
        a1.attach(aBody);
        b1.attach(bBody);

        assertEquals(1, requests.terminateSession("s-a"));
        assertTrue(aBody.closed);
        assertFalse(bBody.closed);
        assertNull(requests.open(a, "s-a"));
        assertNotNull(requests.open(a, "fresh-session"),
                "terminating one session must not revoke the whole client");

        requests.closeAll();
        assertTrue(bBody.closed);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
