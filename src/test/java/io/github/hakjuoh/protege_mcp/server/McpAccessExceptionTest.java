package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link McpAccessException}, a thin {@link RuntimeException} wrapper with
 * three delegating constructors. We verify message/cause propagation, the standard JDK behaviour of
 * the single-{@link Throwable} constructor (message derived from cause.toString()), null handling,
 * special-character preservation, and the type hierarchy / throwability contract.
 */
class McpAccessExceptionTest {

    // ---- McpAccessException(String message) -------------------------------------------------

    @Test
    void messageConstructorReturnsExactMessage() {
        McpAccessException ex = new McpAccessException("EDT timed out");
        assertEquals("EDT timed out", ex.getMessage(), "getMessage() must echo the given message");
    }

    @Test
    void messageConstructorHasNullCause() {
        McpAccessException ex = new McpAccessException("boom");
        assertNull(ex.getCause(), "message-only constructor must leave cause null");
    }

    @Test
    void messageConstructorWithNullMessageReturnsNull() {
        McpAccessException ex = new McpAccessException((String) null);
        assertNull(ex.getMessage(), "null message must be preserved as null");
    }

    @Test
    void messageConstructorWithEmptyStringReturnsEmptyString() {
        McpAccessException ex = new McpAccessException("");
        assertEquals("", ex.getMessage(), "empty message must be preserved exactly");
    }

    @Test
    void messageConstructorPreservesSpecialCharacters() {
        String weird = "line1\n\ttab \"quote\" \\back / éü 日本語 \u0000end";
        McpAccessException ex = new McpAccessException(weird);
        assertEquals(weird, ex.getMessage(), "special characters must be preserved verbatim");
    }

    @Test
    void messageConstructorProducesRuntimeException() {
        McpAccessException ex = new McpAccessException("x");
        assertTrue(ex instanceof RuntimeException, "must be a RuntimeException");
    }

    // ---- McpAccessException(Throwable cause) ------------------------------------------------

    @Test
    void causeConstructorReturnsExactCause() {
        Throwable cause = new IllegalStateException("busy");
        McpAccessException ex = new McpAccessException(cause);
        assertSame(cause, ex.getCause(), "getCause() must return the same cause instance");
    }

    @Test
    void causeConstructorWithNullCauseReturnsNull() {
        McpAccessException ex = new McpAccessException((Throwable) null);
        assertNull(ex.getCause(), "null cause must be preserved as null");
    }

    @Test
    void causeConstructorWithNestedExceptionReturnsOuter() {
        Throwable inner = new IllegalArgumentException("inner");
        Throwable outer = new RuntimeException("outer", inner);
        McpAccessException ex = new McpAccessException(outer);
        assertSame(outer, ex.getCause(), "getCause() must return the outer wrapping exception");
        assertSame(inner, ex.getCause().getCause(), "nested cause must remain reachable");
    }

    @Test
    void causeConstructorDerivesMessageFromCauseToString() {
        // Standard RuntimeException(Throwable) behaviour: message == cause.toString().
        Throwable cause = new IllegalStateException("busy");
        McpAccessException ex = new McpAccessException(cause);
        assertEquals(cause.toString(), ex.getMessage(),
                "single-cause constructor derives message from cause.toString()");
    }

    @Test
    void causeConstructorWithNullCauseHasNullMessage() {
        McpAccessException ex = new McpAccessException((Throwable) null);
        assertNull(ex.getMessage(), "null cause yields null derived message");
    }

    @Test
    void causeConstructorProducesRuntimeException() {
        McpAccessException ex = new McpAccessException(new Exception());
        assertTrue(ex instanceof RuntimeException, "must be a RuntimeException");
    }

    // ---- McpAccessException(String message, Throwable cause) --------------------------------

    @Test
    void messageAndCauseConstructorSetsBoth() {
        Throwable cause = new IllegalStateException("busy");
        McpAccessException ex = new McpAccessException("wrapped", cause);
        assertEquals("wrapped", ex.getMessage(), "message must be set independently of cause");
        assertSame(cause, ex.getCause(), "cause must be the same instance");
    }

    @Test
    void messageAndCauseConstructorWithNullMessageKeepsCause() {
        Throwable cause = new IllegalStateException("busy");
        McpAccessException ex = new McpAccessException(null, cause);
        assertNull(ex.getMessage(), "explicit null message must stay null (not derived from cause)");
        assertSame(cause, ex.getCause(), "cause must still be set");
    }

    @Test
    void messageAndCauseConstructorWithNullCauseKeepsMessage() {
        McpAccessException ex = new McpAccessException("only message", null);
        assertEquals("only message", ex.getMessage(), "message must be set");
        assertNull(ex.getCause(), "explicit null cause must stay null");
    }

    @Test
    void messageAndCauseConstructorWithBothNull() {
        McpAccessException ex = new McpAccessException(null, null);
        assertNull(ex.getMessage(), "null message must be null");
        assertNull(ex.getCause(), "null cause must be null");
    }

    @Test
    void messageAndCauseConstructorWithNestedExceptionPreservesBoth() {
        Throwable inner = new IllegalArgumentException("inner");
        Throwable outer = new RuntimeException("outer", inner);
        McpAccessException ex = new McpAccessException("top", outer);
        assertEquals("top", ex.getMessage(), "top-level message preserved");
        assertSame(outer, ex.getCause(), "outer cause preserved");
        assertSame(inner, ex.getCause().getCause(), "nested cause preserved");
    }

    @Test
    void messageAndCauseConstructorProducesRuntimeException() {
        McpAccessException ex = new McpAccessException("m", new Exception());
        assertTrue(ex instanceof RuntimeException, "must be a RuntimeException");
    }

    @Test
    void messageIndependentOfCauseWhenExplicitlyGiven() {
        Throwable cause = new IllegalStateException("cause-text");
        McpAccessException ex = new McpAccessException("distinct", cause);
        assertNotEquals(cause.toString(), ex.getMessage(),
                "explicit message must NOT be replaced by cause.toString()");
    }

    // ---- type hierarchy / throwability ------------------------------------------------------

    @Test
    void isThrowableAndCatchableAsRuntimeException() {
        RuntimeException caught = assertThrows(RuntimeException.class, () -> {
            throw new McpAccessException("thrown");
        }, "must be throwable and catchable as RuntimeException");
        assertTrue(caught instanceof McpAccessException, "caught type must be McpAccessException");
        assertEquals("thrown", caught.getMessage(), "message survives throw/catch");
    }

    @Test
    void isSubclassOfException() {
        McpAccessException ex = new McpAccessException("x");
        assertTrue(ex instanceof Exception, "RuntimeException is an Exception");
        assertTrue(ex instanceof Throwable, "must be a Throwable");
    }
}
