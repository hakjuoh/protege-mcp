package io.github.hakjuoh.protege_mcp.external;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Reconciles portable project provider declarations with owner-local registry bindings. */
public final class ProviderPolicyBindings {

    public static final int MAX_POLICY_PROVIDERS = 16;

    private ProviderPolicyBindings() {
    }

    /**
     * Reports environment-specific warnings without changing policy validity or its portable digest.
     */
    public static List<PolicyIssue> warnings(ProjectPolicy policy, ProviderOwnerConfig owner) {
        if (policy == null || !policy.loaded() || policy.version() < 2 || owner == null) {
            return List.of();
        }
        List<PolicyIssue> result = new ArrayList<>();
        List<Map<String, Object>> providers = providers(policy);
        String fingerprint = projectFingerprint(policy);
        for (int index = 0; index < providers.size(); index++) {
            Map<String, Object> provider = providers.get(index);
            if (!Boolean.TRUE.equals(provider.get("enabled"))) continue;
            String id = text(provider, "id");
            String profile = text(provider, "profile");
            String alias = text(provider, "origin_alias");
            String credential = text(provider, "credential_id");
            if (id == null || profile == null || alias == null || fingerprint == null) continue;
            try {
                owner.resolve(alias, id, profile, credential, fingerprint);
            } catch (ProviderFailure failure) {
                result.add(new PolicyIssue("warning", failure.code(),
                        "external_terms.providers[" + index + "]",
                        bindingMessage(id, alias, failure.code())));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Builds the provider rows represented by Preferences. Existing project restrictions are retained
     * when a generated provider id already exists; identity and credential fields follow Preferences.
     */
    public static Synthesis synthesize(ProjectPolicy policy, ProviderOwnerConfig owner) {
        java.util.Objects.requireNonNull(owner, "owner");
        Map<String, Map<String, Object>> current = new LinkedHashMap<>();
        for (Map<String, Object> provider : providers(policy)) {
            String id = text(provider, "id");
            if (id != null) current.putIfAbsent(id, provider);
        }

        String fingerprint = projectFingerprint(policy);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ProviderOwnerConfig.OriginBinding origin : owner.origins().values()) {
            int omittedBefore = omitted.size();
            List<ProviderOwnerConfig.CredentialBinding> credentials = credentialsFor(
                    owner, origin.alias(), fingerprint, current, omitted);
            if (credentials.isEmpty()) {
                boolean hasCredentials = owner.credentials().values().stream()
                        .anyMatch(value -> value.originAlias().equals(origin.alias()));
                if (hasCredentials) {
                    if (omitted.size() == omittedBefore) {
                        omitted.add(origin.alias()
                                + " (no credential binding applies to this project)");
                    }
                    continue;
                }
                if ("ontoportal".equals(origin.profile())) {
                    omitted.add(origin.alias() + " (OntoPortal requires a credential binding)");
                    continue;
                }
                addRow(rows, ids, current, origin, origin.alias(), null);
                continue;
            }
            for (ProviderOwnerConfig.CredentialBinding credential : credentials) {
                requireUnique(ids, credential.providerId());
                rows.add(row(current.get(credential.providerId()), origin,
                        credential.providerId(), credential.id()));
            }
        }
        if (rows.size() > MAX_POLICY_PROVIDERS) {
            throw new IllegalArgumentException("Preferences resolve to " + rows.size()
                    + " project providers, exceeding the policy limit of "
                    + MAX_POLICY_PROVIDERS + ". Remove or consolidate registry bindings first.");
        }
        return new Synthesis(List.copyOf(rows), List.copyOf(omitted));
    }

    public static String projectFingerprint(ProjectPolicy policy) {
        if (policy == null || policy.projectRoot() == null || policy.digest() == null) return null;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        List<String> values = List.of(policy.projectRoot().toString(), policy.digest());
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(values.size()).array());
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        StringBuilder result = new StringBuilder("sha256:");
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }

    private static void addRow(List<Map<String, Object>> rows, Set<String> ids,
            Map<String, Map<String, Object>> current,
            ProviderOwnerConfig.OriginBinding origin, String id, String credentialId) {
        requireUnique(ids, id);
        rows.add(row(current.get(id), origin, id, credentialId));
    }

    private static List<ProviderOwnerConfig.CredentialBinding> credentialsFor(
            ProviderOwnerConfig owner, String alias, String fingerprint,
            Map<String, Map<String, Object>> current, List<String> omitted) {
        Map<String, List<ProviderOwnerConfig.CredentialBinding>> applicable =
                new LinkedHashMap<>();
        for (ProviderOwnerConfig.CredentialBinding candidate : owner.credentials().values()) {
            if (!candidate.originAlias().equals(alias)
                    || candidate.projectFingerprint() != null
                    && !candidate.projectFingerprint().equals(fingerprint)) {
                continue;
            }
            applicable.computeIfAbsent(candidate.providerId(), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        List<ProviderOwnerConfig.CredentialBinding> selected = new ArrayList<>();
        for (Map.Entry<String, List<ProviderOwnerConfig.CredentialBinding>> entry
                : applicable.entrySet()) {
            Map<String, Object> existing = current.get(entry.getKey());
            String existingCredential = existing == null
                    ? null : text(existing, "credential_id");
            ProviderOwnerConfig.CredentialBinding preserved = entry.getValue().stream()
                    .filter(value -> value.id().equals(existingCredential))
                    .findFirst().orElse(null);
            if (preserved != null) {
                selected.add(preserved);
                continue;
            }
            List<ProviderOwnerConfig.CredentialBinding> unscoped = entry.getValue().stream()
                    .filter(value -> value.projectFingerprint() == null).toList();
            if (unscoped.size() > 1) {
                throw new IllegalArgumentException("Provider id '" + entry.getKey()
                        + "' has multiple all-project credentials in Preferences. Keep one or "
                        + "preserve an explicitly selected credential before applying registries.");
            }
            if (unscoped.size() == 1) {
                selected.add(unscoped.get(0));
            } else {
                omitted.add(alias + "/" + entry.getKey()
                        + " (project-scoped credential is not already referenced by this policy)");
            }
        }
        return List.copyOf(selected);
    }

    private static void requireUnique(Set<String> ids, String id) {
        if (!ids.add(id)) {
            throw new IllegalArgumentException("Provider id '" + id
                    + "' resolves to more than one registry origin. Use unique provider IDs in "
                    + "Preferences before applying them to policy.");
        }
    }

    private static Map<String, Object> row(Map<String, Object> existing,
            ProviderOwnerConfig.OriginBinding origin, String id, String credentialId) {
        Map<String, Object> result = existing == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        result.put("id", id);
        result.put("profile", origin.profile());
        result.put("enabled", true);
        result.put("origin_alias", origin.alias());
        if (credentialId == null) {
            result.remove("credential_id");
        } else {
            result.put("credential_id", credentialId);
        }
        return result;
    }

    private static String bindingMessage(String id, String alias, String code) {
        if ("provider_credential_unbound".equals(code)) {
            return "Provider '" + id + "' has no matching owner credential for origin alias '"
                    + alias + "'. Review Preferences ▸ MCP ▸ Externals or apply the saved "
                    + "registry bindings to this policy.";
        }
        return "Provider '" + id + "' references origin alias '" + alias
                + "', but that alias and profile are not present in Preferences ▸ MCP ▸ "
                + "Externals. Apply the saved registry bindings or restore the missing origin.";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> providers(ProjectPolicy policy) {
        if (policy == null) return List.of();
        Object external = policy.effective().get("external_terms");
        if (!(external instanceof Map<?, ?> map)) return List.of();
        Object providers = map.get("providers");
        if (!(providers instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof Map<?, ?> row) result.add((Map<String, Object>) row);
        }
        return result;
    }

    private static String text(Map<String, Object> value, String field) {
        Object result = value.get(field);
        return result instanceof String text ? text : null;
    }

    public record Synthesis(List<Map<String, Object>> providers, List<String> omitted) {
        public Synthesis {
            providers = providers.stream()
                    .map(value -> Collections.unmodifiableMap(new LinkedHashMap<>(value)))
                    .toList();
            omitted = List.copyOf(omitted);
        }
    }
}
