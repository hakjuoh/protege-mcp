package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;

/**
 * Method-level tests for {@link ToolContext}, an immutable value holder.
 *
 * <p>ToolContext merely stores an {@link OntologyAccess} and a {@link McpServerController} and hands
 * them back through accessors; it invokes no methods on either collaborator. That lets us construct
 * real {@code OntologyAccess} instances with a {@code null} editor kit (its constructor does not
 * dereference the kit) as identity witnesses, and use {@code null} for the controller — we never
 * construct a real {@code McpServerController} because its constructor reaches into
 * {@code McpConfig.load()} → Protégé's {@code PreferencesManager}, which is off-limits headless.
 */
class ToolContextTest {

    private static OntologyAccess newAccess() {
        // The OntologyAccess constructor stores the (nullable) editor kit without dereferencing it.
        return new OntologyAccess(null);
    }

    @Test
    void accessReturnsSameInstancePassedToConstructor() {
        OntologyAccess access = newAccess();
        ToolContext ctx = new ToolContext(access, null);
        assertSame(access, ctx.access(),
                "access() must return the exact OntologyAccess reference given to the constructor");
    }

    @Test
    void controllerReturnsSameInstancePassedToConstructor() {
        // A null controller is a valid stand-in: ToolContext stores it verbatim and never calls it.
        McpServerController controller = null;
        ToolContext ctx = new ToolContext(newAccess(), controller);
        assertSame(controller, ctx.controller(),
                "controller() must return the exact reference given to the constructor");
    }

    @Test
    void constructorAcceptsNullOntologyAccessWithoutThrowing() {
        ToolContext ctx = new ToolContext(null, null);
        assertNull(ctx.access(), "access() must return null when null was passed in");
    }

    @Test
    void constructorAcceptsNullControllerWithoutThrowing() {
        ToolContext ctx = new ToolContext(newAccess(), null);
        assertNull(ctx.controller(), "controller() must return null when null was passed in");
    }

    @Test
    void constructorAcceptsBothNullWithoutThrowing() {
        ToolContext ctx = new ToolContext(null, null);
        assertNull(ctx.access(), "access() must be null");
        assertNull(ctx.controller(), "controller() must be null");
    }

    @Test
    void accessReturnsNullWhenNullPassed() {
        ToolContext ctx = new ToolContext(null, null);
        assertNull(ctx.access(), "access() must return null if null was passed to the constructor");
    }

    @Test
    void controllerReturnsNullWhenNullPassed() {
        ToolContext ctx = new ToolContext(newAccess(), null);
        assertNull(ctx.controller(),
                "controller() must return null if null was passed to the constructor");
    }

    @Test
    void accessAccessorIsStableAcrossRepeatedCalls() {
        OntologyAccess access = newAccess();
        ToolContext ctx = new ToolContext(access, null);
        assertSame(ctx.access(), ctx.access(),
                "repeated access() calls must return the identical instance (no copying)");
        assertSame(access, ctx.access(),
                "access() must keep returning the original instance across calls");
    }

    @Test
    void distinctContextsDoNotShareStoredAccess() {
        OntologyAccess a1 = newAccess();
        OntologyAccess a2 = newAccess();
        ToolContext ctx1 = new ToolContext(a1, null);
        ToolContext ctx2 = new ToolContext(a2, null);
        assertSame(a1, ctx1.access(), "ctx1 must expose its own access instance");
        assertSame(a2, ctx2.access(), "ctx2 must expose its own access instance");
        assertNotSame(ctx1.access(), ctx2.access(),
                "two ToolContexts built with different access instances must not alias each other");
    }

    @Test
    void constructorDoesNotMutateOrReplaceTheGivenAccess() {
        OntologyAccess access = newAccess();
        ToolContext ctx = new ToolContext(access, null);
        // The stored reference must be identity-equal to the argument, proving no defensive copy.
        assertSame(access, ctx.access(),
                "ToolContext must store the access reference as-is, without copying or wrapping it");
    }
}
