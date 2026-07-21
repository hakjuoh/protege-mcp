package io.github.hakjuoh.protege_mcp.external;

import java.net.InetAddress;

/** Rejects special-use network destinations before a provider socket is opened. */
final class ProviderAddressPolicy {

    private ProviderAddressPolicy() { }

    static boolean allowed(InetAddress address, boolean testOnlyLoopback) {
        if (address == null) return false;
        if (testOnlyLoopback) return address.isLoopbackAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? publicIpv4(bytes)
                : bytes.length == 16 && publicIpv6(bytes);
    }

    private static boolean publicIpv4(byte[] value) {
        int first = value[0] & 0xff;
        int second = value[1] & 0xff;
        int third = value[2] & 0xff;
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && second == 168) return false;
        if (first == 198 && (second == 18 || second == 19)) return false;
        return !(first == 192 && second == 0 && (third == 0 || third == 2))
                && !(first == 192 && second == 88 && third == 99)
                && !(first == 198 && second == 51 && third == 100)
                && !(first == 203 && second == 0 && third == 113);
    }

    private static boolean publicIpv6(byte[] value) {
        int first = value[0] & 0xff;
        int second = value[1] & 0xff;
        if (first == 0 || first == 0xff || (first & 0xfe) == 0xfc
                || first == 0xfe && (second & 0xc0) == 0x80) return false;
        // IETF protocol assignments, documentation, 6to4, and current special-use ranges.
        if (first == 0x20 && second == 0x01) {
            int third = value[2] & 0xff;
            int fourth = value[3] & 0xff;
            if (third <= 0x01 || third == 0x0d && fourth == 0xb8) return false;
        }
        boolean discardOnly = first == 0x01 && second == 0x00
                && zero(value, 2, 8);
        return !discardOnly && !(first == 0x20 && second == 0x02)
                && !(first == 0x3f && second == 0xff && (value[2] & 0xf0) == 0)
                && !(first == 0x5f && second == 0x00);
    }

    private static boolean zero(byte[] value, int from, int toExclusive) {
        for (int index = from; index < toExclusive; index++) {
            if (value[index] != 0) return false;
        }
        return true;
    }
}
