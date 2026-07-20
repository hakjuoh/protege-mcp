package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

class PrincipalExecutionGateTest {

    @Test
    void clientRevocationWaitsForOlderLeaseThenRejectsQueuedAndFutureWork() throws Exception {
        PrincipalExecutionGate gate = new PrincipalExecutionGate();
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.oauthAdmin(
                "client-a", "A", "grant-a");
        PrincipalExecutionGate.Lease active = gate.acquire(principal);
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch revokeStarted = new CountDownLatch(1);
            var revocation = pool.submit(() -> {
                revokeStarted.countDown();
                return gate.revokeClient("client-a");
            });
            assertTrue(revokeStarted.await(1, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> revocation.get(100, TimeUnit.MILLISECONDS),
                    "revocation must not claim success while an older execution can still commit");

            var queued = pool.submit(() -> {
                try (var ignored = gate.acquire(principal)) {
                    return true;
                } catch (ToolArgException revoked) {
                    return false;
                }
            });
            assertFalse(queued.get(1, TimeUnit.SECONDS),
                    "a request arriving after revocation begins must fail fast, not pin a worker");
            active.close();

            PrincipalExecutionGate.Revocation result = revocation.get(2, TimeUnit.SECONDS);
            assertEquals(1, result.observedActive());
            assertThrows(ToolArgException.class, () -> gate.acquire(principal));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void revokingOneGrantLeavesAnotherGrantAndAnotherClientUsable() {
        PrincipalExecutionGate gate = new PrincipalExecutionGate();
        AuthenticatedPrincipal first = AuthenticatedPrincipal.oauthAdmin("client", "C", "grant-1");
        AuthenticatedPrincipal second = AuthenticatedPrincipal.oauthAdmin("client", "C", "grant-2");
        AuthenticatedPrincipal other = AuthenticatedPrincipal.oauthAdmin("other", "O", "grant-o");

        gate.revokeGrant(first.clientId(), first.grantId());
        assertThrows(ToolArgException.class, () -> gate.acquire(first));
        try (var ignored = gate.acquire(second); var ignoredOther = gate.acquire(other)) {
            assertTrue(true);
        }
    }

    @Test
    void leaseCloseIsIdempotent() {
        PrincipalExecutionGate gate = new PrincipalExecutionGate();
        var lease = gate.acquire(AuthenticatedPrincipal.staticAdmin());
        lease.close();
        lease.close();
        assertEquals(0, gate.revokeClient("static-local-admin").observedActive());
    }
}
