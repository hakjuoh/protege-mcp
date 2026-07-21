package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProviderAddressPolicyTest {

    @Test
    void onlyGloballyRoutableAddressesAreAllowedForProductionOrigins() throws Exception {
        for (String value : List.of("93.184.216.34", "8.8.8.8",
                "2606:4700:4700::1111", "2001:4860:4860::8888")) {
            assertTrue(ProviderAddressPolicy.allowed(InetAddress.getByName(value), false), value);
        }
        for (String value : List.of("0.0.0.1", "10.0.0.1", "100.64.0.1", "127.0.0.1",
                "169.254.1.1", "172.16.0.1", "192.0.0.1", "192.0.2.1",
                "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.51.100.1",
                "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
                "::", "::1", "::ffff:127.0.0.1", "100::1", "2001::1",
                "2001:db8::1", "2002::1", "3fff::1", "5f00::1", "fc00::1",
                "fe80::1", "ff02::1")) {
            assertFalse(ProviderAddressPolicy.allowed(InetAddress.getByName(value), false), value);
        }
        assertFalse(ProviderAddressPolicy.allowed(null, false));
    }

    @Test
    void testBindingsAcceptLoopbackAndNothingElse() throws Exception {
        assertTrue(ProviderAddressPolicy.allowed(InetAddress.getByName("127.0.0.1"), true));
        assertTrue(ProviderAddressPolicy.allowed(InetAddress.getByName("::1"), true));
        assertFalse(ProviderAddressPolicy.allowed(InetAddress.getByName("93.184.216.34"), true));
        assertFalse(ProviderAddressPolicy.allowed(null, true));
    }
}
