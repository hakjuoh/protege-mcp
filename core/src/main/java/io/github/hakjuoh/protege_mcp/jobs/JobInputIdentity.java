package io.github.hakjuoh.protege_mcp.jobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

/** Complete immutable identity of every input captured before asynchronous execution. */
public record JobInputIdentity(
        @JsonProperty("model_revision") ModelRevision modelRevision,
        @JsonProperty("closure_fingerprint") String closureFingerprint,
        @JsonProperty("import_lock_digest") String importLockDigest,
        @JsonProperty("mapping_revision") String mappingRevision,
        @JsonProperty("policy_digest") String policyDigest,
        @JsonProperty("preflight_asset_digest") String preflightAssetDigest,
        @JsonProperty("reasoner_digest") String reasonerDigest,
        @JsonProperty("normalized_request_digest") String normalizedRequestDigest,
        @JsonProperty("secondary_inputs") List<SecondaryInput> secondaryInputs,
        @JsonProperty("identity_digest") String identityDigest) {

    public JobInputIdentity(ModelRevision modelRevision, String closureFingerprint,
            String importLockDigest, String mappingRevision, String policyDigest,
            String preflightAssetDigest, String reasonerDigest, String normalizedRequestDigest,
            List<SecondaryInput> secondaryInputs) {
        this(modelRevision, closureFingerprint, importLockDigest, mappingRevision, policyDigest,
                preflightAssetDigest, reasonerDigest, normalizedRequestDigest, secondaryInputs,
                compute(modelRevision, closureFingerprint, importLockDigest, mappingRevision,
                        policyDigest, preflightAssetDigest, reasonerDigest,
                        normalizedRequestDigest, secondaryInputs));
    }

    public JobInputIdentity {
        if (modelRevision == null) throw new IllegalArgumentException("model revision is required");
        closureFingerprint = JobHashes.requireDigest(closureFingerprint, "closure fingerprint");
        importLockDigest = optionalDigest(importLockDigest, "import lock digest");
        mappingRevision = optionalDigest(mappingRevision, "mapping revision");
        policyDigest = JobHashes.requireDigest(policyDigest, "policy digest");
        preflightAssetDigest = optionalDigest(preflightAssetDigest, "preflight asset digest");
        reasonerDigest = optionalDigest(reasonerDigest, "reasoner digest");
        normalizedRequestDigest = JobHashes.requireDigest(
                normalizedRequestDigest, "normalized request digest");
        List<SecondaryInput> copy = new ArrayList<>(
                secondaryInputs == null ? List.of() : secondaryInputs);
        if (copy.size() > 32 || copy.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("secondary input list is invalid");
        }
        copy.sort(Comparator.comparing(SecondaryInput::name));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).name().equals(copy.get(index).name())) {
                throw new IllegalArgumentException("secondary input names must be unique");
            }
        }
        secondaryInputs = List.copyOf(copy);
        identityDigest = JobHashes.requireDigest(identityDigest, "identity digest");
        String expected = compute(modelRevision, closureFingerprint, importLockDigest,
                mappingRevision, policyDigest, preflightAssetDigest, reasonerDigest,
                normalizedRequestDigest, secondaryInputs);
        if (!expected.equals(identityDigest)) {
            throw new IllegalArgumentException("identity digest does not match job inputs");
        }
    }

    private static String compute(ModelRevision revision, String closure, String importLock,
            String mapping, String policy, String assets, String reasoner, String request,
            List<SecondaryInput> inputs) {
        if (revision == null) throw new IllegalArgumentException("model revision is required");
        List<SecondaryInput> sorted = new ArrayList<>(inputs == null ? List.of() : inputs);
        if (sorted.size() > 32 || sorted.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("secondary input list is invalid");
        }
        sorted.sort(Comparator.comparing(SecondaryInput::name));
        List<String> values = new ArrayList<>();
        values.add(revision.workspaceId());
        values.add(Long.toString(revision.sessionRevision()));
        values.add(revision.semanticFingerprint());
        values.add(revision.documentFingerprint());
        values.add(closure);
        values.add(importLock);
        values.add(mapping);
        values.add(policy);
        values.add(assets);
        values.add(reasoner);
        values.add(request);
        for (SecondaryInput input : sorted) {
            values.add(input.name());
            values.add(input.byteDigest());
            values.add(input.provenanceDigest());
        }
        return JobHashes.digest(values.toArray(String[]::new));
    }

    private static String optionalDigest(String value, String field) {
        return value == null ? null : JobHashes.requireDigest(value, field);
    }

    /** One secondary document captured once, identified without exposing its source path. */
    public record SecondaryInput(@JsonProperty("name") String name,
            @JsonProperty("byte_digest") String byteDigest,
            @JsonProperty("provenance_digest") String provenanceDigest) {
        public SecondaryInput {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("secondary input name is invalid");
            }
            byteDigest = JobHashes.requireDigest(byteDigest, "secondary byte digest");
            provenanceDigest = JobHashes.requireDigest(
                    provenanceDigest, "secondary provenance digest");
        }
    }
}
