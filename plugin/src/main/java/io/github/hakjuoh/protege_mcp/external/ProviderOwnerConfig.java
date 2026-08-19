package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Owner-controlled exact origins and credential scopes; contains no secret values. */
public final class ProviderOwnerConfig {

    public static final String FILE_NAME = "config.json";
    public static final int MAX_BYTES = 64 * 1_024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(8_192).maxDocumentLength(MAX_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Map<String, OriginBinding> origins;
    private final Map<String, CredentialBinding> credentials;

    private ProviderOwnerConfig(Map<String, OriginBinding> origins,
            Map<String, CredentialBinding> credentials) {
        if (origins == null || credentials == null || origins.size() > 32
                || credentials.size() > 64) {
            throw new IllegalArgumentException("provider configuration exceeds its bounds");
        }
        origins.forEach((key, value) -> {
            if (value == null || !value.alias().equals(key)) {
                throw new IllegalArgumentException("origin map key does not match its binding");
            }
        });
        credentials.forEach((key, value) -> {
            if (value == null || !value.id().equals(key)
                    || !origins.containsKey(value.originAlias())) {
                throw new IllegalArgumentException("credential map does not match its binding");
            }
        });
        this.origins = Collections.unmodifiableMap(new LinkedHashMap<>(origins));
        this.credentials = Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
    }

    public static ProviderOwnerConfig empty() {
        return new ProviderOwnerConfig(Map.of(), Map.of());
    }

    public static ProviderOwnerConfig of(Map<String, OriginBinding> origins,
            Map<String, CredentialBinding> credentials) {
        return new ProviderOwnerConfig(origins == null ? Map.of() : origins,
                credentials == null ? Map.of() : credentials);
    }

    public static OriginBinding bindOrigin(String alias, String profile, URI origin) {
        return new OriginBinding(alias, profile, origin,
                origin != null && literalLoopback(origin.getHost()));
    }

    public static ProviderOwnerConfig loadDefault() throws ProviderFailure {
        return load(ProviderLocalPaths.providers());
    }

    public static void saveDefault(ProviderOwnerConfig config) throws ProviderFailure {
        save(ProviderLocalPaths.providers(), config);
    }

    public static void save(java.nio.file.Path root, ProviderOwnerConfig config) throws ProviderFailure {
        java.nio.file.Path directory = OwnerOnlyFiles.prepareDirectory(root);
        byte[] body = config.toJsonBytes();
        try {
            OwnerOnlyFiles.write(directory, FILE_NAME, body);
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    public byte[] toJsonBytes() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        List<Map<String, Object>> originsList = new ArrayList<>();
        for (OriginBinding origin : origins.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("alias", origin.alias());
            map.put("profile", origin.profile());
            map.put("origin", origin.origin().toASCIIString());
            map.put("test_only_loopback", origin.testOnlyLoopback());
            originsList.add(map);
        }
        doc.put("origins", originsList);
        List<Map<String, Object>> credentialsList = new ArrayList<>();
        for (CredentialBinding credential : credentials.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", credential.id());
            map.put("provider_id", credential.providerId());
            map.put("origin_alias", credential.originAlias());
            map.put("scheme", credential.scheme().name().toLowerCase(Locale.ROOT));
            if (credential.header() != null) map.put("header", credential.header());
            if (credential.parameter() != null) map.put("parameter", credential.parameter());
            if (credential.projectFingerprint() != null && !credential.projectFingerprint().isBlank()) {
                map.put("project_fingerprint", credential.projectFingerprint());
            }
            credentialsList.add(map);
        }
        doc.put("credentials", credentialsList);
        try {
            return JSON.writeValueAsBytes(doc);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize ProviderOwnerConfig", e);
        }
    }

    static ProviderOwnerConfig load(java.nio.file.Path root) throws ProviderFailure {
        java.nio.file.Path directory = OwnerOnlyFiles.prepareDirectory(root);
        if (!OwnerOnlyFiles.exists(directory, FILE_NAME)) return empty();
        byte[] body = OwnerOnlyFiles.read(directory, FILE_NAME, MAX_BYTES);
        try {
            JsonNode document = JSON.readTree(body);
            if (document == null || !document.isObject() || integer(document, "version") != 1) {
                throw invalid();
            }
            rejectUnknown(document, List.of("version", "origins", "credentials"));
            Map<String, OriginBinding> origins = origins(document.path("origins"));
            Map<String, CredentialBinding> credentials = credentials(
                    document.path("credentials"), origins);
            return new ProviderOwnerConfig(origins, credentials);
        } catch (ProviderFailure typed) {
            throw typed;
        } catch (IOException | RuntimeException malformed) {
            throw invalid();
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    public ResolvedProvider resolve(String alias, String providerId, String profile,
            String credentialId, String projectFingerprint) throws ProviderFailure {
        String normalizedAlias = id(alias, "origin alias");
        String normalizedProvider = id(providerId, "provider id");
        String normalizedProfile = id(profile, "provider profile");
        String normalizedProject = fingerprint(projectFingerprint);
        OriginBinding origin = origins.get(normalizedAlias);
        if (origin == null || !origin.profile().equals(normalizedProfile)) {
            throw new ProviderFailure("provider_origin_unbound",
                    "Provider origin alias is not bound to the requested profile", false);
        }
        CredentialBinding credential = null;
        if (credentialId != null) {
            credential = credentials.get(id(credentialId, "credential id"));
            if (credential == null || !credential.providerId().equals(normalizedProvider)
                    || !credential.originAlias().equals(normalizedAlias)
                    || credential.projectFingerprint() != null
                    && !credential.projectFingerprint().equals(normalizedProject)) {
                throw new ProviderFailure("provider_credential_unbound",
                        "Credential is not bound to this provider, origin, and project", false);
            }
        }
        if (ontoPortalProfile(normalizedProfile)
                && (credential == null || !credential.scheme().supportsOntoPortal())) {
            throw new ProviderFailure("provider_credential_unbound",
                    "This provider profile requires an authorized OntoPortal credential", false);
        }
        return new ResolvedProvider(normalizedProvider, normalizedProject, origin, credential);
    }

    public Map<String, OriginBinding> origins() {
        return origins;
    }

    public Map<String, CredentialBinding> credentials() {
        return credentials;
    }

    private static Map<String, OriginBinding> origins(JsonNode values) throws ProviderFailure {
        if (!values.isArray() || values.size() > 32) throw invalid();
        Map<String, OriginBinding> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            if (!value.isObject()) throw invalid();
            rejectUnknown(value, List.of("alias", "profile", "origin", "test_only_loopback"));
            String alias = id(text(value, "alias", 64), "origin alias");
            String profile = id(text(value, "profile", 64), "provider profile");
            URI origin = origin(text(value, "origin", 4_096));
            boolean loopback = bool(value, "test_only_loopback", false);
            if (loopback != literalLoopback(origin.getHost())) throw invalid();
            OriginBinding binding = new OriginBinding(alias, profile, origin, loopback);
            if (result.putIfAbsent(alias, binding) != null) throw invalid();
        }
        return result;
    }

    private static Map<String, CredentialBinding> credentials(JsonNode values,
            Map<String, OriginBinding> origins) throws ProviderFailure {
        if (!values.isArray() || values.size() > 64) throw invalid();
        Map<String, CredentialBinding> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            if (!value.isObject()) throw invalid();
            rejectUnknown(value, List.of("id", "provider_id", "origin_alias", "scheme",
                    "header", "parameter", "project_fingerprint"));
            String id = id(text(value, "id", 64), "credential id");
            String provider = id(text(value, "provider_id", 64), "provider id");
            String alias = id(text(value, "origin_alias", 64), "origin alias");
            if (!origins.containsKey(alias)) throw invalid();
            AuthScheme scheme = AuthScheme.parse(text(value, "scheme", 32));
            String header = optional(value, "header", 64);
            if (header == null) header = scheme.defaultHeader();
            String parameter = optional(value, "parameter", 64);
            if (parameter == null) parameter = scheme.defaultParameter();
            String fingerprint = optional(value, "project_fingerprint", 256);
            CredentialBinding binding = new CredentialBinding(id, provider, alias, scheme,
                    header, parameter, fingerprint);
            if (result.putIfAbsent(id, binding) != null) throw invalid();
        }
        return result;
    }

    private static URI origin(String value) throws ProviderFailure {
        try {
            URI origin = URI.create(value);
            if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.isOpaque()
                    || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !safeOriginPath(origin.getRawPath())
                    || origin.toASCIIString().endsWith("/")) {
                throw invalid();
            }
            return origin;
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static boolean literalLoopback(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.equals("::1")
                || normalized.equals("[::1]")) return true;
        if (!normalized.startsWith("127.")) return false;
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) return false;
        try {
            return java.util.Arrays.stream(octets).allMatch(value -> {
                int octet = Integer.parseInt(value);
                return octet >= 0 && octet <= 255 && value.equals(Integer.toString(octet));
            });
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean forbiddenHeader(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return List.of("host", "accept", "accept-encoding", "user-agent",
                "cookie", "set-cookie", "proxy-authenticate",
                "proxy-authorization", "proxy-connection", "connection", "content-length",
                "keep-alive", "te",
                "trailer", "transfer-encoding", "upgrade").contains(lower);
    }

    private static boolean ontoPortalProfile(String profile) {
        return OntoPortalProvider.PROFILE.equals(profile);
    }

    private static void rejectUnknown(JsonNode object, List<String> allowed)
            throws ProviderFailure {
        var fields = object.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid();
    }

    private static int integer(JsonNode object, String field) throws ProviderFailure {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    private static String text(JsonNode object, String field, int maximum) throws ProviderFailure {
        String value = optional(object, field, maximum);
        if (value == null) throw invalid();
        return value;
    }

    private static String optional(JsonNode object, String field, int maximum)
            throws ProviderFailure {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) throw invalid();
        return value.textValue();
    }

    private static boolean bool(JsonNode object, String field, boolean fallback)
            throws ProviderFailure {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static String id(String value, String field) throws ProviderFailure {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new ProviderFailure("provider_configuration_invalid",
                    "Owner provider " + field + " is invalid", false);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static ProviderFailure invalid() {
        return new ProviderFailure("provider_configuration_invalid",
                "Owner provider configuration is invalid", false);
    }

    private static String fingerprint(String value) throws ProviderFailure {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new ProviderFailure("provider_configuration_invalid",
                    "Project fingerprint is invalid", false);
        }
        return value;
    }

    public enum AuthScheme {
        BEARER("Authorization", null),
        API_KEY("X-Api-Key", null),
        ONTOPORTAL_API_KEY("Authorization", null),
        QUERY_API_KEY(null, "apikey");

        private final String defaultHeader;
        private final String defaultParameter;

        AuthScheme(String defaultHeader, String defaultParameter) {
            this.defaultHeader = defaultHeader;
            this.defaultParameter = defaultParameter;
        }

        public String defaultHeader() {
            return defaultHeader;
        }

        public String defaultParameter() {
            return defaultParameter;
        }

        boolean supportsOntoPortal() {
            return this == BEARER || this == API_KEY || this == ONTOPORTAL_API_KEY
                    || this == QUERY_API_KEY;
        }

        static AuthScheme parse(String value) throws ProviderFailure {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw ProviderOwnerConfig.invalid();
            }
        }
    }

    public record OriginBinding(String alias, String profile, URI origin,
            boolean testOnlyLoopback) {
        public OriginBinding {
            alias = uncheckedId(alias);
            profile = uncheckedId(profile);
            if (!validOrigin(origin) || testOnlyLoopback != literalLoopback(origin.getHost())) {
                throw new IllegalArgumentException("origin binding is invalid");
            }
        }
    }

    public record CredentialBinding(String id, String providerId, String originAlias,
            AuthScheme scheme, String header, String parameter, String projectFingerprint) {
        public CredentialBinding(String id, String providerId, String originAlias,
                AuthScheme scheme, String header, String projectFingerprint) {
            this(id, providerId, originAlias, scheme, header, null, projectFingerprint);
        }

        public CredentialBinding {
            id = uncheckedId(id);
            providerId = uncheckedId(providerId);
            originAlias = uncheckedId(originAlias);
            if (scheme == null || !(validHeaderPlacement(scheme, header, parameter)
                            || validQueryPlacement(scheme, header, parameter))
                    || projectFingerprint != null && (projectFingerprint.isBlank()
                            || projectFingerprint.length() > 256)) {
                throw new IllegalArgumentException("credential binding is invalid");
            }
        }

        private static boolean validHeaderPlacement(AuthScheme scheme, String header,
                String parameter) {
            return header != null && parameter == null
                    && header.matches("[A-Za-z][A-Za-z0-9-]{0,63}")
                    && !forbiddenHeader(header) && scheme != AuthScheme.QUERY_API_KEY
                    && (scheme == AuthScheme.API_KEY
                            ? !header.equalsIgnoreCase("Authorization")
                            : header.equalsIgnoreCase("Authorization"));
        }

        private static boolean validQueryPlacement(AuthScheme scheme, String header,
                String parameter) {
            return header == null && "apikey".equals(parameter)
                    && scheme == AuthScheme.QUERY_API_KEY;
        }
    }

    /** Unforgeable runtime authority issued only after owner configuration resolution. */
    public static final class ResolvedProvider {
        private final String providerId;
        private final String projectFingerprint;
        private final OriginBinding origin;
        private final CredentialBinding credential;

        private ResolvedProvider(String providerId, String projectFingerprint, OriginBinding origin,
                CredentialBinding credential) {
            this.providerId = uncheckedId(providerId);
            if (projectFingerprint == null || projectFingerprint.isBlank()
                    || projectFingerprint.length() > 256) {
                throw new IllegalArgumentException("project fingerprint is invalid");
            }
            this.projectFingerprint = projectFingerprint;
            if (origin == null || credential != null
                    && (!credential.providerId().equals(this.providerId)
                            || !credential.originAlias().equals(origin.alias()))) {
                throw new IllegalArgumentException("resolved provider is invalid");
            }
            this.origin = origin;
            this.credential = credential;
        }

        public String providerId() {
            return providerId;
        }

        public OriginBinding origin() {
            return origin;
        }

        public String projectFingerprint() {
            return projectFingerprint;
        }

        public CredentialBinding credential() {
            return credential;
        }

        /** Cache partition identity covering owner binding plus credential generation/incarnation. */
        public String cacheScopeFingerprint(OwnerCredentialStore.CredentialLease lease)
                throws ProviderFailure {
            List<String> values = new ArrayList<>();
            values.add(providerId);
            values.add(projectFingerprint);
            values.add(origin.alias());
            values.add(origin.profile());
            values.add(origin.origin().toASCIIString());
            values.add(Boolean.toString(origin.testOnlyLoopback()));
            if (credential == null) {
                if (lease != null) throw new ProviderFailure("provider_credential_unbound",
                        "Credential lease is not bound to this provider", false);
                values.add("anonymous");
            } else {
                if (lease == null || !credential.id().equals(lease.id())) {
                    throw new ProviderFailure("provider_credential_unbound",
                            "Credential lease is not bound to this provider", false);
                }
                values.add(credential.id());
                values.add(credential.scheme().name());
                values.add(credential.header() == null ? ""
                        : credential.header().toLowerCase(Locale.ROOT));
                values.add(credential.parameter() == null ? ""
                        : credential.parameter().toLowerCase(Locale.ROOT));
                values.add(credential.projectFingerprint() == null
                        ? "" : credential.projectFingerprint());
                values.add(lease.scopeFingerprint());
            }
            return "sha256:" + digest(values);
        }

        @Override
        public String toString() {
            return "ResolvedProvider[providerId=" + providerId + ", origin=" + origin.alias()
                    + ", credential=" + (credential == null ? "none" : credential.id()) + "]";
        }
    }

    private static String uncheckedId(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("identifier is invalid");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean validOrigin(URI value) {
        if (value == null) return false;
        String path = value.getRawPath();
        return "https".equalsIgnoreCase(value.getScheme()) && !value.isOpaque()
                && value.getHost() != null && value.getUserInfo() == null
                && value.getRawQuery() == null && value.getRawFragment() == null
                && safeOriginPath(path)
                && !value.toASCIIString().endsWith("/");
    }

    private static boolean safeOriginPath(String path) {
        return path != null && (path.isEmpty() || ProviderRequest.safeRelativePath(path));
    }

    private static String digest(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(values.size()).array());
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                int unsigned = value & 0xff;
                result.append(Character.forDigit(unsigned >>> 4, 16));
                result.append(Character.forDigit(unsigned & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
