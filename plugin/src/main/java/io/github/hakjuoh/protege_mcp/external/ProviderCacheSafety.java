package io.github.hakjuoh.protege_mcp.external;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic sink check for credentials, authorization material, and opaque URLs. */
final class ProviderCacheSafety {

    private static final int MAX_PERCENT_DECODE_ROUNDS = 4;
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(?:(?:basic|bearer|concealed|digest|dpop|gnap|hawk|hoba|mutual|"
            + "negotiate|ntlm|oauth|privatetoken|scram|scram-sha-1|scram-sha-256|"
            + "vapid|aws4-hmac-sha256)"
            + "\\s+[^\\s,}]+|(?:authorization|proxy-authorization|api[_-]?key|"
            + "access[_-]?token|client[_-]?secret|cookie|set-cookie|password|credential|"
            + "secret|x-amz-signature|x-goog-signature|signature)\\s*[\"']?\\s*[:=]|"
            + "(?:^|[?&#;/\\s])(?:auth|authorization|credential|password|secret|sig|signature|"
            + "token|access_token|api_key|key|x-amz-signature|x-goog-signature)"
            + "\\s*[\"']?\\s*=)");
    private static final Pattern HIERARCHICAL_URI = Pattern.compile(
            "(?i)(?:(?:[a-z][a-z0-9+.-]*:)?//[^\\s\"'<>]+|"
            + "[a-z][a-z0-9+.-]*:/[^/\\s\"'<>][^\\s\"'<>]*)");

    private ProviderCacheSafety() { }

    static boolean safe(byte[] payload, byte[] credentialCanary, byte[] queryCanary) {
        String credential = credentialCanary == null ? null
                : new String(credentialCanary, StandardCharsets.UTF_8);
        String query = queryCanary == null ? null
                : new String(queryCanary, StandardCharsets.UTF_8);
        try {
            return !ProviderJsonCanary.anyTextMatches(payload, value ->
                    containsCanary(value, credential) || containsCanary(value, query)
                    || sensitive(value));
        } catch (java.io.IOException invalidJson) {
            return false;
        }
    }

    private static boolean sensitive(String value) {
        String candidate = value;
        for (int round = 0; round <= MAX_PERCENT_DECODE_ROUNDS; round++) {
            if (sensitiveDecoded(candidate)) return true;
            String decoded = percentDecode(candidate);
            if (decoded.equals(candidate)) return false;
            if (round == MAX_PERCENT_DECODE_ROUNDS) return true;
            candidate = decoded;
        }
        return false;
    }

    private static boolean sensitiveDecoded(String value) {
        if (AUTHORIZATION.matcher(value).find()
                || value.toLowerCase(Locale.ROOT).contains("set-cookie")) return true;
        Matcher candidates = HIERARCHICAL_URI.matcher(value);
        while (candidates.find()) {
            String uri = candidates.group();
            int separator = uri.indexOf("//");
            if (separator >= 0) {
                int authorityStart = separator + 2;
                int authorityEnd = uri.length();
                for (char delimiter : new char[] {'/', '?', '#'}) {
                    int index = uri.indexOf(delimiter, authorityStart);
                    if (index >= 0) authorityEnd = Math.min(authorityEnd, index);
                }
                if (uri.substring(authorityStart, authorityEnd).contains("@")) return true;
            }
            if (AUTHORIZATION.matcher(uri).find()) return true;
        }
        return false;
    }

    static boolean containsCanary(String value, String canary) {
        if (value == null || canary == null || canary.isEmpty()) return false;
        String candidate = value;
        for (int round = 0; round <= MAX_PERCENT_DECODE_ROUNDS; round++) {
            if (candidate.contains(canary)) return true;
            String decoded = percentDecode(candidate);
            if (decoded.equals(candidate)) return false;
            if (round == MAX_PERCENT_DECODE_ROUNDS) return true;
            candidate = decoded;
        }
        return false;
    }

    /** Decode valid percent-byte runs only; malformed escapes remain literal and cannot hide peers. */
    private static String percentDecode(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) != '%' || index + 2 >= value.length()
                    || hex(value.charAt(index + 1)) < 0 || hex(value.charAt(index + 2)) < 0) {
                decoded.append(value.charAt(index++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index + 2 < value.length() && value.charAt(index) == '%') {
                int high = hex(value.charAt(index + 1));
                int low = hex(value.charAt(index + 2));
                if (high < 0 || low < 0) break;
                bytes.write((high << 4) | low);
                index += 3;
            }
            decoded.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return decoded.toString();
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }
}
