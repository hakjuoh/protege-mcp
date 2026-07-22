package io.github.hakjuoh.protege_mcp.external;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

/** Complete normalized provider and project identity captured before a proposal is issued. */
public record ReuseProposalInputIdentity(String providerId, String profile,
        String sourceOntology, String entityIri, String language, String termFingerprint,
        String resultFingerprint, ModelRevision modelRevision, String mappingRevision,
        String policyDigest, ReuseProposalTargetIdentity targetIdentity,
        String inputFingerprint) {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public ReuseProposalInputIdentity {
        providerId = ProviderSearchRequest.identifier(providerId, "provider_id");
        profile = ProviderSearchRequest.identifier(profile, "profile");
        sourceOntology = ProviderSearchRequest.identifier(sourceOntology, "source_ontology");
        entityIri = ProviderValues.absoluteIri(entityIri, "entity_iri", 4_096);
        language = ProviderSearchRequest.identifier(language, "language");
        termFingerprint = fingerprint(termFingerprint, "term_fingerprint");
        resultFingerprint = fingerprint(resultFingerprint, "result_fingerprint");
        if (modelRevision == null) throw new IllegalArgumentException("model revision is required");
        mappingRevision = fingerprint(mappingRevision, "mapping_revision");
        policyDigest = fingerprint(policyDigest, "policy_digest");
        if (targetIdentity == null) {
            throw new IllegalArgumentException("proposal target identity is required");
        }
        String computed = compute(providerId, profile, sourceOntology, entityIri, language,
                termFingerprint, resultFingerprint, modelRevision, mappingRevision, policyDigest,
                targetIdentity);
        if (inputFingerprint == null) inputFingerprint = computed;
        if (!computed.equals(inputFingerprint)) {
            throw new IllegalArgumentException("input fingerprint does not match its fields");
        }
    }

    public static ReuseProposalInputIdentity create(ProviderResult result, String language,
            ModelRevision modelRevision, String mappingRevision, String policyDigest,
            ReuseProposalTargetIdentity targetIdentity) {
        if (result == null) throw new IllegalArgumentException("provider result is required");
        return new ReuseProposalInputIdentity(result.providerId(), result.profile(),
                result.sourceOntology(), result.entityIri(), language, result.termFingerprint(),
                result.resultFingerprint(), modelRevision, mappingRevision, policyDigest,
                targetIdentity, null);
    }

    public boolean matches(ProviderResult result) {
        return result != null && providerId.equals(result.providerId())
                && profile.equals(result.profile())
                && sourceOntology.equals(result.sourceOntology())
                && entityIri.equals(result.entityIri())
                && termFingerprint.equals(result.termFingerprint())
                && resultFingerprint.equals(result.resultFingerprint());
    }

    public Map<String, Object> toJson() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("workspace_id", modelRevision.workspaceId());
        model.put("session_revision", modelRevision.sessionRevision());
        model.put("semantic_fingerprint", modelRevision.semanticFingerprint());
        model.put("document_fingerprint", modelRevision.documentFingerprint());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", providerId);
        result.put("profile", profile);
        result.put("source_ontology", sourceOntology);
        result.put("entity_iri", entityIri);
        result.put("language", language);
        result.put("term_fingerprint", termFingerprint);
        result.put("result_fingerprint", resultFingerprint);
        result.put("model_revision", Collections.unmodifiableMap(model));
        result.put("mapping_revision", mappingRevision);
        result.put("policy_digest", policyDigest);
        result.put("target_identity", targetIdentity.toJson());
        result.put("input_fingerprint", inputFingerprint);
        return Collections.unmodifiableMap(result);
    }

    @Override
    public String toString() {
        return "ReuseProposalInputIdentity[redacted=true]";
    }

    private static String compute(String providerId, String profile, String sourceOntology,
            String entityIri, String language, String termFingerprint, String resultFingerprint,
            ModelRevision modelRevision, String mappingRevision, String policyDigest,
            ReuseProposalTargetIdentity targetIdentity) {
        return digest(List.of(providerId, profile, sourceOntology, entityIri, language,
                termFingerprint, resultFingerprint, modelRevision.workspaceId(),
                Long.toString(modelRevision.sessionRevision()),
                modelRevision.semanticFingerprint(), modelRevision.documentFingerprint(),
                mappingRevision, policyDigest, targetIdentity.targetFingerprint()));
    }

    private static String digest(List<String> values) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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

    private static String fingerprint(String value, String field) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be sha256 followed by lowercase hex");
        }
        return value;
    }

}
