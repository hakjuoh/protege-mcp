package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.external.ExternalProviderGateway;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ProviderInspectRequest;
import io.github.hakjuoh.protege_mcp.external.ProviderSearchRequest;
import io.github.hakjuoh.protege_mcp.external.ProviderSessionScope;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

class ToolContextLifecycleTest {

    @Test
    void disposeReturnsButDrainsBeforeClosingScopedStores() throws Exception {
        CountDownLatch cleaned = new CountDownLatch(1);
        ToolContext context = new ToolContext(null, null, null, new ClosingGateway(cleaned));
        PrincipalExecutionGate.Lease active = context.executions().acquire(
                AuthenticatedPrincipal.staticAdmin());
        try {
            context.dispose();
            assertFalse(cleaned.await(100, TimeUnit.MILLISECONDS));
            assertThrows(ToolArgException.class, () -> context.executions().acquire(
                    AuthenticatedPrincipal.staticAdmin()));
            active.close();
            assertTrue(cleaned.await(2, TimeUnit.SECONDS));
        } finally {
            active.close();
        }
    }

    @Test
    void disposeOnEdtDefersDrainSoQueuedModelWorkCanFinish() throws Exception {
        CountDownLatch cleaned = new CountDownLatch(1);
        ToolContext context = new ToolContext(null, null, null, new ClosingGateway(cleaned));
        PrincipalExecutionGate.Lease active = context.executions().acquire(
                AuthenticatedPrincipal.staticAdmin());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var invocation = executor.submit(() -> {
                javax.swing.SwingUtilities.invokeAndWait(context::dispose);
                return true;
            });
            assertTrue(invocation.get(2, TimeUnit.SECONDS));
            assertThrows(ToolArgException.class, () -> context.executions().acquire(
                    AuthenticatedPrincipal.staticAdmin()));
            active.close();
            assertTrue(cleaned.await(2, TimeUnit.SECONDS));
        } finally {
            active.close();
            executor.shutdownNow();
        }
    }

    private static final class ClosingGateway implements ExternalProviderGateway {
        private final CountDownLatch closed;

        private ClosingGateway(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public SearchOutcome search(ProviderSessionScope scope,
                ProviderSearchRequest initialRequest, String cursor,
                InvocationResolver resolver) throws ProviderFailure {
            throw new ProviderFailure("provider_unavailable", "Provider is unused", false);
        }

        @Override
        public InspectOutcome inspect(ProviderInspectRequest request,
                InvocationResolver resolver) throws ProviderFailure {
            throw new ProviderFailure("provider_unavailable", "Provider is unused", false);
        }

        @Override public int revokeClient(String clientId) { return 0; }
        @Override public int revokeGrant(String clientId, String grantId) { return 0; }
        @Override public int clearWorkspace(String workspaceId) { return 0; }
        @Override public void close() { closed.countDown(); }
    }
}
