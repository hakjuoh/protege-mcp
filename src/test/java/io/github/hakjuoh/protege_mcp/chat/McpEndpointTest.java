package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for the {@link McpEndpoint} record: constructor/accessor round-trip,
 * equals/hashCode/toString contract (including null-field handling), and the {@code SERVER_NAME}
 * constant invariant. The record has no logic branches, so tests focus on the compiler-generated
 * canonical members and the immutable field values.
 */
class McpEndpointTest {

    // ---- constructor + accessors --------------------------------------------------------------

    @Test
    void constructorStoresValidNonNullValues() {
        McpEndpoint e = new McpEndpoint("http://127.0.0.1:8123/mcp", "tok");
        assertEquals("http://127.0.0.1:8123/mcp", e.url(), "url() must return the constructor url");
        assertEquals("tok", e.token(), "token() must return the constructor token");
    }

    @Test
    void constructorStoresEmptyStrings() {
        McpEndpoint e = new McpEndpoint("", "");
        assertEquals("", e.url(), "url() must return the empty string it was given");
        assertEquals("", e.token(), "token() must return the empty string it was given");
    }

    @Test
    void constructorStoresWhitespaceOnlyStrings() {
        McpEndpoint e = new McpEndpoint(" ", " ");
        assertEquals(" ", e.url(), "url() must preserve whitespace-only input verbatim");
        assertEquals(" ", e.token(), "token() must preserve whitespace-only input verbatim");
    }

    @Test
    void constructorAcceptsNullUrl() {
        McpEndpoint e = new McpEndpoint(null, "tok");
        assertNull(e.url(), "url() must return null when constructed with null url");
        assertEquals("tok", e.token(), "token() must be unaffected by a null url");
    }

    @Test
    void constructorAcceptsNullToken() {
        McpEndpoint e = new McpEndpoint("http://x", null);
        assertEquals("http://x", e.url(), "url() must be unaffected by a null token");
        assertNull(e.token(), "token() must return null when constructed with null token");
    }

    @Test
    void constructorAcceptsBothNull() {
        McpEndpoint e = new McpEndpoint(null, null);
        assertNull(e.url(), "url() must return null when both fields are null");
        assertNull(e.token(), "token() must return null when both fields are null");
    }

    @Test
    void accessorsReturnDistinctValuesForDistinctInstances() {
        McpEndpoint a = new McpEndpoint("http://a", "ta");
        McpEndpoint b = new McpEndpoint("http://b", "tb");
        assertNotEquals(a.url(), b.url(), "url() must differ across instances with different urls");
        assertNotEquals(a.token(), b.token(),
                "token() must differ across instances with different tokens");
    }

    // ---- equals -------------------------------------------------------------------------------

    @Test
    void equalsTrueForSameUrlAndToken() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        McpEndpoint b = new McpEndpoint("http://x", "tok");
        assertEquals(a, b, "instances with identical url and token must be equal");
    }

    @Test
    void equalsFalseForDifferentUrlSameToken() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        McpEndpoint b = new McpEndpoint("http://y", "tok");
        assertNotEquals(a, b, "differing url must break equality");
    }

    @Test
    void equalsFalseForSameUrlDifferentToken() {
        McpEndpoint a = new McpEndpoint("http://x", "t1");
        McpEndpoint b = new McpEndpoint("http://x", "t2");
        assertNotEquals(a, b, "differing token must break equality");
    }

    @Test
    void equalsFalseForBothFieldsDifferent() {
        McpEndpoint a = new McpEndpoint("http://x", "t1");
        McpEndpoint b = new McpEndpoint("http://y", "t2");
        assertNotEquals(a, b, "differing url and token must break equality");
    }

    @Test
    void equalsReflexive() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        assertEquals(a, a, "an instance must equal itself");
    }

    @Test
    void equalsNullReturnsFalse() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        assertNotEquals(null, a, "equals(null) must be false");
    }

    @Test
    void equalsDifferentClassReturnsFalse() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        assertNotEquals("http://x", a, "equals against a foreign type must be false");
    }

    @Test
    void equalsTrueForNullUrlSameToken() {
        McpEndpoint a = new McpEndpoint(null, "tok");
        McpEndpoint b = new McpEndpoint(null, "tok");
        assertEquals(a, b, "both null url with same token must be equal");
    }

    @Test
    void equalsTrueForSameUrlNullToken() {
        McpEndpoint a = new McpEndpoint("http://x", null);
        McpEndpoint b = new McpEndpoint("http://x", null);
        assertEquals(a, b, "same url with both null token must be equal");
    }

    @Test
    void equalsTrueForBothFieldsNull() {
        McpEndpoint a = new McpEndpoint(null, null);
        McpEndpoint b = new McpEndpoint(null, null);
        assertEquals(a, b, "two all-null instances must be equal");
    }

    @Test
    void equalsFalseWhenOneUrlNullOtherNot() {
        McpEndpoint a = new McpEndpoint(null, "tok");
        McpEndpoint b = new McpEndpoint("http://x", "tok");
        assertNotEquals(a, b, "null url must not equal a non-null url");
    }

    // ---- hashCode -----------------------------------------------------------------------------

    @Test
    void hashCodeEqualForEqualInstances() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        McpEndpoint b = new McpEndpoint("http://x", "tok");
        assertEquals(a.hashCode(), b.hashCode(), "equal instances must share a hashCode");
    }

    @Test
    void hashCodeConsistentAcrossCalls() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        int first = a.hashCode();
        assertEquals(first, a.hashCode(), "hashCode must be stable across repeated calls");
    }

    @Test
    void hashCodeHandlesNullUrl() {
        McpEndpoint a = new McpEndpoint(null, "tok");
        McpEndpoint b = new McpEndpoint(null, "tok");
        assertEquals(a.hashCode(), b.hashCode(),
                "hashCode must not throw and must match for equal null-url instances");
    }

    @Test
    void hashCodeHandlesNullToken() {
        McpEndpoint a = new McpEndpoint("http://x", null);
        McpEndpoint b = new McpEndpoint("http://x", null);
        assertEquals(a.hashCode(), b.hashCode(),
                "hashCode must not throw and must match for equal null-token instances");
    }

    @Test
    void hashCodeHandlesBothNull() {
        McpEndpoint a = new McpEndpoint(null, null);
        McpEndpoint b = new McpEndpoint(null, null);
        assertEquals(a.hashCode(), b.hashCode(),
                "hashCode must not throw and must match for equal all-null instances");
    }

    // ---- toString -----------------------------------------------------------------------------

    @Test
    void toStringContainsRecordName() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        assertTrue(a.toString().contains("McpEndpoint"),
                "toString must name the record type: " + a);
    }

    @Test
    void toStringContainsFieldNames() {
        McpEndpoint a = new McpEndpoint("http://x", "tok");
        String s = a.toString();
        assertTrue(s.contains("url"), "toString must mention the url field: " + s);
        assertTrue(s.contains("token"), "toString must mention the token field: " + s);
    }

    @Test
    void toStringContainsFieldValues() {
        McpEndpoint a = new McpEndpoint("http://value-url", "value-token");
        String s = a.toString();
        assertTrue(s.contains("http://value-url"), "toString must include the url value: " + s);
        assertTrue(s.contains("value-token"), "toString must include the token value: " + s);
    }

    @Test
    void toStringRendersNullFields() {
        McpEndpoint a = new McpEndpoint(null, null);
        String s = a.toString();
        assertTrue(s.contains("null"), "toString must render null fields as 'null': " + s);
    }

    // ---- SERVER_NAME constant -----------------------------------------------------------------

    @Test
    void serverNameConstantValue() {
        assertEquals("protege", McpEndpoint.SERVER_NAME,
                "SERVER_NAME must be the fixed MCP server name 'protege'");
    }

    @Test
    void serverNameNotNull() {
        assertNotNull(McpEndpoint.SERVER_NAME, "SERVER_NAME must not be null");
    }
}
