package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * {@link BrokerState} serialization, with the {@code host} field's two compatibility duties: absent
 * in {@code broker.json} written by older plugin versions (which always bound loopback), and
 * carrying a URL-ready (bracketed) IPv6 host when the bind preference asks for one.
 */
class BrokerStateTest {

    @Test
    void hostRoundTripsThroughJson() {
        BrokerState state = new BrokerState(42, "[::1]", 8123, "1.0", 7);
        Optional<BrokerState> read = BrokerState.fromJson(state.toJson());
        assertTrue(read.isPresent());
        assertEquals("[::1]", read.get().host);
        assertEquals("http://[::1]:8123", read.get().baseUrl());
    }

    @Test
    void legacyJsonWithoutHostDefaultsToLoopback() {
        // broker.json from a pre-bind-preference broker: no "host" — those brokers always bound
        // 127.0.0.1, so the default must reconstruct exactly that.
        Optional<BrokerState> read = BrokerState.fromJson(
                "{\"pid\":7,\"port\":8123,\"version\":\"0.5.0\",\"startedAt\":1}");
        assertTrue(read.isPresent());
        assertEquals("127.0.0.1", read.get().host);
        assertEquals("http://127.0.0.1:8123", read.get().baseUrl());
    }

    @Test
    void loopbackConstructorKeepsTheOldShape() {
        BrokerState state = new BrokerState(42, 8123, "1.0", 7);
        assertEquals("http://127.0.0.1:8123", state.baseUrl());
    }

    @Test
    void blankHostFallsBackToLoopback() {
        assertEquals("http://127.0.0.1:9000",
                new BrokerState(1, "", 9000, "x", 0).baseUrl());
        assertEquals("http://127.0.0.1:9000",
                new BrokerState(1, null, 9000, "x", 0).baseUrl());
    }

    @Test
    void invalidPortStaysUndiscoverable() {
        assertTrue(BrokerState.fromJson("{\"pid\":7,\"host\":\"127.0.0.1\",\"port\":0}").isEmpty());
        assertTrue(BrokerState.fromJson("not json").isEmpty());
    }
}
