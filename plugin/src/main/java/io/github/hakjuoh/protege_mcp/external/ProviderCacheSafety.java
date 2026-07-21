package io.github.hakjuoh.protege_mcp.external;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic sink check for credentials, authorization material, and opaque URLs. */
final class ProviderCacheSafety {

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(?:(?:basic|bearer|concealed|digest|dpop|gnap|hawk|hoba|mutual|"
            + "negotiate|ntlm|oauth|privatetoken|scram|scram-sha-1|scram-sha-256|"
            + "vapid|aws4-hmac-sha256)"
            + "\\s+[^\\s,}]+|(?:authorization|proxy-authorization|api[_-]?key|"
            + "access[_-]?token|client[_-]?secret|cookie|set-cookie|password|credential|"
            + "secret|x-amz-signature|x-goog-signature|signature)\\s*[\"']?\\s*[:=]|"
            + "[?&](?:auth|authorization|credential|password|secret|sig|signature|token|"
            + "access_token|api_key|key|x-amz-signature|x-goog-signature)=)");
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
                    (credential != null && value.contains(credential))
                    || (query != null && value.contains(query)) || sensitive(value));
        } catch (java.io.IOException invalidJson) {
            return false;
        }
    }

    private static boolean sensitive(String value) {
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
            if (uri.indexOf('?') >= 0) return true;
        }
        return false;
    }
}
