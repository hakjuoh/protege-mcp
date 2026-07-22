package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.sssom.MappingRecord;
import io.github.hakjuoh.protege_mcp.sssom.SssomDocument;
import io.github.hakjuoh.protege_mcp.sssom.SssomEntityIndex;
import io.github.hakjuoh.protege_mcp.sssom.SssomParser;
import io.github.hakjuoh.protege_mcp.sssom.SssomValidationPolicy;
import io.github.hakjuoh.protege_mcp.sssom.SssomValidator;

class ReuseProposalTest {

    private static final String WORKSPACE = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_WORKSPACE = "223e4567-e89b-12d3-a456-426614174000";
    private static final String MAPPING_REVISION = "sha256:" + "1".repeat(64);
    private static final String POLICY_DIGEST = "sha256:" + "2".repeat(64);
    private static final ReuseProposalTargetIdentity TARGET =
            ReuseProposalTargetIdentity.create("/project", "/project/policy.yaml",
                    "/project/mappings.tsv", false);
    private static final String EXTERNAL = "https://example.org/external";
    private static final String LOCAL = "https://example.org/local";

    @Test
    void typedMappingIsCanonicalImmutableAndProposalFingerprintIsDeterministic() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("predicate_id", "skos:exactMatch");
        first.put("object_id", EXTERNAL);
        first.put("subject_id", LOCAL);
        first.put("mapping_justification", "semapv:ManualMappingCuration");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("subject_id", LOCAL);
        second.put("object_id", EXTERNAL);
        second.put("predicate_id", "skos:exactMatch");
        second.put("mapping_justification", "semapv:ManualMappingCuration");

        ReuseProposal left = proposal(result(EXTERNAL, "External term"), revision(WORKSPACE, 7,
                "a", "b"), MAPPING_REVISION, POLICY_DIGEST, "en",
                new ReuseOperation.AddMapping(first));
        ReuseProposal right = proposal(result(EXTERNAL, "External term"), revision(WORKSPACE, 7,
                "a", "b"), MAPPING_REVISION, POLICY_DIGEST, "en",
                new ReuseOperation.AddMapping(second));

        assertEquals(left.proposalFingerprint(), right.proposalFingerprint());
        ReuseOperation.AddMapping operation = (ReuseOperation.AddMapping) left.operation();
        assertEquals(List.of("mapping_justification", "object_id", "predicate_id", "subject_id"),
                new ArrayList<>(operation.mappingCells().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> operation.mappingCells().put("extra", "value"));
        assertEquals(left.inputIdentity().inputFingerprint(),
                ((Map<?, ?>) left.toJson().get("input_identity")).get("input_fingerprint"));
        SssomDocument document = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/proposal-test",
                "license", "https://spdx.org/licenses/CC0-1.0"), Map.of(),
                List.copyOf(operation.mappingCells().keySet()),
                List.of(new MappingRecord(operation.mappingCells())));
        assertTrue(SssomValidator.validate(document, SssomValidationPolicy.structural(),
                SssomEntityIndex.unavailable()).valid());
        assertFalse(left.toString().contains(EXTERNAL));
        assertFalse(left.inputIdentity().toString().contains(EXTERNAL));
    }

    @Test
    void everyProviderModelPolicyMappingLanguageAndOperationFieldBindsIdentity() {
        ProviderResult baseResult = result(EXTERNAL, "External term");
        ModelRevision baseRevision = revision(WORKSPACE, 7, "a", "b");
        ReuseProposal baseline = proposal(baseResult, baseRevision, MAPPING_REVISION,
                POLICY_DIGEST, "en", new ReuseOperation.ReuseIri(EXTERNAL));

        List<ReuseProposal> changedIdentity = List.of(
                proposal(result("https://example.org/other", "Other term"), baseRevision,
                        MAPPING_REVISION, POLICY_DIGEST, "en",
                        new ReuseOperation.ReuseIri("https://example.org/other")),
                proposal(baseResult, revision(OTHER_WORKSPACE, 7, "a", "b"),
                        MAPPING_REVISION, POLICY_DIGEST, "en",
                        new ReuseOperation.ReuseIri(EXTERNAL)),
                proposal(baseResult, revision(WORKSPACE, 8, "a", "b"),
                        MAPPING_REVISION, POLICY_DIGEST, "en",
                        new ReuseOperation.ReuseIri(EXTERNAL)),
                proposal(baseResult, revision(WORKSPACE, 7, "c", "b"),
                        MAPPING_REVISION, POLICY_DIGEST, "en",
                        new ReuseOperation.ReuseIri(EXTERNAL)),
                proposal(baseResult, revision(WORKSPACE, 7, "a", "d"),
                        MAPPING_REVISION, POLICY_DIGEST, "en",
                        new ReuseOperation.ReuseIri(EXTERNAL)),
                proposal(baseResult, baseRevision, "sha256:" + "3".repeat(64),
                        POLICY_DIGEST, "en", new ReuseOperation.ReuseIri(EXTERNAL)),
                proposal(baseResult, baseRevision, MAPPING_REVISION,
                        "sha256:" + "4".repeat(64), "en",
                        new ReuseOperation.ReuseIri(EXTERNAL)),
                proposal(baseResult, baseRevision, MAPPING_REVISION, POLICY_DIGEST, "fr",
                        new ReuseOperation.ReuseIri(EXTERNAL)));

        for (ReuseProposal variant : changedIdentity) {
            assertNotEquals(baseline.inputIdentity().inputFingerprint(),
                    variant.inputIdentity().inputFingerprint());
            assertNotEquals(baseline.proposalFingerprint(), variant.proposalFingerprint());
        }
        ReuseProposal changedOperation = proposal(baseResult, baseRevision, MAPPING_REVISION,
                POLICY_DIGEST, "en", new ReuseOperation.AddMapping(mapping(LOCAL, EXTERNAL)));
        assertEquals(baseline.inputIdentity().inputFingerprint(),
                changedOperation.inputIdentity().inputFingerprint());
        assertNotEquals(baseline.proposalFingerprint(), changedOperation.proposalFingerprint());
    }

    @Test
    void standaloneInputIdentityDirectlyBindsProviderProfileAndSourceFields() {
        ReuseProposalInputIdentity baseline = ReuseProposalInputIdentity.create(
                result(EXTERNAL, "External term"), "en", revision(WORKSPACE, 7, "a", "b"),
                MAPPING_REVISION, POLICY_DIGEST, TARGET);
        List<ReuseProposalInputIdentity> changed = List.of(
                new ReuseProposalInputIdentity("other", baseline.profile(),
                        baseline.sourceOntology(), baseline.entityIri(), baseline.language(),
                        baseline.termFingerprint(), baseline.resultFingerprint(), baseline.modelRevision(),
                        baseline.mappingRevision(), baseline.policyDigest(),
                        baseline.targetIdentity(), null),
                new ReuseProposalInputIdentity(baseline.providerId(), "other",
                        baseline.sourceOntology(), baseline.entityIri(), baseline.language(),
                        baseline.termFingerprint(), baseline.resultFingerprint(), baseline.modelRevision(),
                        baseline.mappingRevision(), baseline.policyDigest(),
                        baseline.targetIdentity(), null),
                new ReuseProposalInputIdentity(baseline.providerId(), baseline.profile(),
                        "go", baseline.entityIri(), baseline.language(),
                        baseline.termFingerprint(), baseline.resultFingerprint(), baseline.modelRevision(),
                        baseline.mappingRevision(), baseline.policyDigest(),
                        baseline.targetIdentity(), null),
                new ReuseProposalInputIdentity(baseline.providerId(), baseline.profile(),
                        baseline.sourceOntology(), baseline.entityIri(), baseline.language(),
                        "sha256:" + "9".repeat(64), baseline.resultFingerprint(),
                        baseline.modelRevision(), baseline.mappingRevision(),
                        baseline.policyDigest(), baseline.targetIdentity(), null),
                new ReuseProposalInputIdentity(baseline.providerId(), baseline.profile(),
                        baseline.sourceOntology(), baseline.entityIri(), baseline.language(),
                        baseline.termFingerprint(), baseline.resultFingerprint(),
                        baseline.modelRevision(), baseline.mappingRevision(),
                        baseline.policyDigest(), ReuseProposalTargetIdentity.create(
                                "/project", "/project/policy.yaml",
                                "/project/other-mappings.tsv", false), null));
        for (ReuseProposalInputIdentity identity : changed) {
            assertNotEquals(baseline.inputFingerprint(), identity.inputFingerprint());
        }
        assertThrows(IllegalArgumentException.class,
                () -> ReuseProposal.create(result(EXTERNAL, "External term"), changed.get(0),
                        new ReuseOperation.ReuseIri(EXTERNAL)));
        assertThrows(IllegalArgumentException.class,
                () -> ReuseProposal.create(result(EXTERNAL, "External term"), changed.get(3),
                        new ReuseOperation.ReuseIri(EXTERNAL)));
    }

    @Test
    void targetIdentityBindsCanonicalProjectPolicyMappingAndExistenceWithoutPathDisclosure() {
        ReuseProposalTargetIdentity baseline = ReuseProposalTargetIdentity.create(
                "/project", "/project/policy.yaml", "/project/mappings.tsv", false);
        List<ReuseProposalTargetIdentity> changed = List.of(
                ReuseProposalTargetIdentity.create("/other", "/project/policy.yaml",
                        "/project/mappings.tsv", false),
                ReuseProposalTargetIdentity.create("/project", "/project/other-policy.yaml",
                        "/project/mappings.tsv", false),
                ReuseProposalTargetIdentity.create("/project", "/project/policy.yaml",
                        "/project/other-mappings.tsv", false),
                ReuseProposalTargetIdentity.create("/project", "/project/policy.yaml",
                        "/project/mappings.tsv", true));
        for (ReuseProposalTargetIdentity variant : changed) {
            assertNotEquals(baseline.targetFingerprint(), variant.targetFingerprint());
        }
        assertFalse(baseline.toJson().toString().contains("/project"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseProposalTargetIdentity(baseline.projectRootFingerprint(),
                        baseline.policySourceFingerprint(), baseline.mappingTargetFingerprint(),
                        baseline.mappingExists(), "sha256:" + "0".repeat(64)));
    }

    @Test
    void eachActionHasAnExecutableStrictlyValidatedShape() {
        ProviderResult result = result(EXTERNAL, "External term");
        ReuseProposal reuse = proposal(result, revision(WORKSPACE, 7, "a", "b"),
                MAPPING_REVISION, POLICY_DIGEST, "en", new ReuseOperation.ReuseIri(EXTERNAL));
        ReuseProposal add = proposal(result, revision(WORKSPACE, 7, "a", "b"),
                MAPPING_REVISION, POLICY_DIGEST, "en",
                new ReuseOperation.AddMapping(mapping(LOCAL, EXTERNAL)));
        ReuseProposal mint = proposal(result, revision(WORKSPACE, 7, "a", "b"),
                MAPPING_REVISION, POLICY_DIGEST, "en",
                new ReuseOperation.MintLocalWithMapping(LOCAL,
                        ReuseOperation.MintedEntityType.CLASS,
                        List.of(new ProviderResult.LocalizedText("Local term", "en")),
                        mapping(LOCAL, EXTERNAL)));

        assertEquals(ReuseAction.REUSE_IRI, reuse.action());
        assertEquals(ReuseAction.ADD_MAPPING, add.action());
        assertEquals(ReuseAction.MINT_LOCAL_WITH_MAPPING, mint.action());
        assertEquals(ReuseAction.MINT_LOCAL_WITH_MAPPING,
                ReuseAction.parse("mint_local_with_mapping"));
        assertEquals(ReuseOperation.MintedEntityType.NAMED_INDIVIDUAL,
                ReuseOperation.MintedEntityType.parse("named_individual"));

        assertThrows(IllegalArgumentException.class, () -> ReuseAction.parse("import"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.AddMapping(Map.of("note", "missing fields")));
        Map<String, String> invalidJustification = new LinkedHashMap<>(mapping(LOCAL, EXTERNAL));
        invalidJustification.put("mapping_justification", "invalid");
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.AddMapping(invalidJustification));
        assertThrows(IllegalArgumentException.class,
                () -> proposal(result, revision(WORKSPACE, 7, "a", "b"), MAPPING_REVISION,
                        POLICY_DIGEST, "en", new ReuseOperation.AddMapping(
                                mapping(EXTERNAL, EXTERNAL))));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.AddMapping(mapping("skos:Concept",
                        "http://www.w3.org/2004/02/skos/core#Concept")));
        assertThrows(IllegalArgumentException.class,
                () -> proposal(result, revision(WORKSPACE, 7, "a", "b"), MAPPING_REVISION,
                        POLICY_DIGEST, "en", new ReuseOperation.ReuseIri(LOCAL)));
        assertThrows(IllegalArgumentException.class,
                () -> proposal(result, revision(WORKSPACE, 7, "a", "b"), MAPPING_REVISION,
                        POLICY_DIGEST, "en", new ReuseOperation.AddMapping(
                                mapping(LOCAL, "https://example.org/unrelated"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.MintLocalWithMapping(LOCAL,
                        ReuseOperation.MintedEntityType.CLASS, List.of(), mapping(LOCAL, EXTERNAL)));
        assertThrows(IllegalArgumentException.class,
                () -> proposal(result, revision(WORKSPACE, 7, "a", "b"), MAPPING_REVISION,
                        POLICY_DIGEST, "en", new ReuseOperation.MintLocalWithMapping(LOCAL,
                                ReuseOperation.MintedEntityType.DATA_PROPERTY,
                                List.of(new ProviderResult.LocalizedText("Local", "en")),
                                mapping(LOCAL, EXTERNAL))));
    }

    @Test
    void hostileStringsAndTamperedFingerprintsFailBeforeSerializationOrStorage() {
        Map<String, String> oversized = new LinkedHashMap<>(mapping(LOCAL, EXTERNAL));
        oversized.put("comment", "x".repeat(SssomParser.MAX_CELL_BYTES + 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.AddMapping(oversized));

        Map<String, String> padded = new LinkedHashMap<>(mapping(LOCAL, EXTERNAL));
        padded.put("subject_id", " ".repeat(SssomParser.MAX_CELL_BYTES + 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.AddMapping(padded));

        Map<String, String> literalEndpoint = new LinkedHashMap<>(mapping(LOCAL, EXTERNAL));
        literalEndpoint.put("object_type", "rdfs:Literal");
        literalEndpoint.put("object_label", "External term");
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.AddMapping(literalEndpoint));

        Map<String, String> noTermFound = new LinkedHashMap<>(mapping(
                LOCAL, "sssom:NoTermFound"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.AddMapping(noTermFound));

        Map<String, String> curieEndpoint = new LinkedHashMap<>(mapping(LOCAL,
                "http://www.w3.org/2004/02/skos/core#Concept"));
        ReuseOperation.AddMapping curieMapping = new ReuseOperation.AddMapping(curieEndpoint);
        curieMapping.validateAgainst(result("skos:Concept", "Concept"));

        Map<String, String> malformedUnicode = new LinkedHashMap<>(mapping(LOCAL, EXTERNAL));
        malformedUnicode.put("comment", String.valueOf((char) 0xd800));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.AddMapping(malformedUnicode));

        List<ProviderResult.LocalizedText> largeLabels = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            largeLabels.add(new ProviderResult.LocalizedText(
                    String.valueOf((char) 0x0800).repeat(4_095) + (char) ('A' + index), "en"));
        }
        Map<String, String> largeMintMapping = new LinkedHashMap<>(mapping(LOCAL, EXTERNAL));
        largeMintMapping.put("extension_one", "x".repeat(40_000));
        largeMintMapping.put("extension_two", "x".repeat(40_000));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseOperation.MintLocalWithMapping(LOCAL,
                        ReuseOperation.MintedEntityType.CLASS, largeLabels, largeMintMapping));

        ReuseProposal valid = proposal(result(EXTERNAL, "External term"),
                revision(WORKSPACE, 7, "a", "b"), MAPPING_REVISION, POLICY_DIGEST, "en",
                new ReuseOperation.ReuseIri(EXTERNAL));
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseProposal(valid.providerResult(), valid.inputIdentity(),
                        valid.operation(), "sha256:" + "0".repeat(64)));
        ReuseProposalInputIdentity identity = valid.inputIdentity();
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseProposalInputIdentity(identity.providerId(), identity.profile(),
                        identity.sourceOntology(), identity.entityIri(), identity.language(),
                        identity.termFingerprint(), identity.resultFingerprint(), identity.modelRevision(),
                        identity.mappingRevision(), identity.policyDigest(), identity.targetIdentity(),
                        "sha256:" + "0".repeat(64)));
    }

    @Test
    void mintReceiptBindsProposalEntityAndBothModelRevisions() {
        ModelRevision base = revision(WORKSPACE, 7, "a", "b");
        ModelRevision minted = revision(WORKSPACE, 8, "c", "d");
        ReuseProposal proposal = proposal(result(EXTERNAL, "External term"), base,
                MAPPING_REVISION, POLICY_DIGEST, "en",
                new ReuseOperation.MintLocalWithMapping(LOCAL,
                        ReuseOperation.MintedEntityType.CLASS,
                        List.of(new ProviderResult.LocalizedText("Local term", "en")),
                        mapping(LOCAL, EXTERNAL)));

        ReuseMintReceipt receipt = ReuseMintReceipt.create(
                proposal.proposalFingerprint(), LOCAL, base, minted,
                "https://example.org/mappings",
                "https://spdx.org/licenses/CC0-1.0", null);

        assertEquals(LOCAL, receipt.toJson().get("entity_iri"));
        assertTrue(receipt.receiptFingerprint().startsWith("sha256:"));
        assertNotEquals(receipt.receiptFingerprint(), ReuseMintReceipt.create(
                proposal.proposalFingerprint(), LOCAL, base,
                revision(WORKSPACE, 9, "c", "d"),
                "https://example.org/mappings",
                "https://spdx.org/licenses/CC0-1.0", null).receiptFingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> new ReuseMintReceipt(proposal.proposalFingerprint(), LOCAL,
                        base, minted, "https://example.org/mappings",
                        "https://spdx.org/licenses/CC0-1.0", null,
                        "sha256:" + "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> ReuseMintReceipt.create(proposal.proposalFingerprint(), LOCAL,
                        base, revision(OTHER_WORKSPACE, 8, "c", "d"), null, null, null));
    }

    private static ReuseProposal proposal(ProviderResult result, ModelRevision revision,
            String mappingRevision, String policyDigest, String language,
            ReuseOperation operation) {
        return ReuseProposal.create(result, ReuseProposalInputIdentity.create(result, language,
                revision, mappingRevision, policyDigest, TARGET), operation);
    }

    private static ModelRevision revision(String workspace, long session,
            String semanticDigit, String documentDigit) {
        return new ModelRevision(workspace, session, "sha256:" + semanticDigit.repeat(64),
                "sha256:" + documentDigit.repeat(64));
    }

    private static Map<String, String> mapping(String subject, String object) {
        return Map.of("subject_id", subject, "predicate_id", "skos:exactMatch",
                "object_id", object,
                "mapping_justification", "semapv:ManualMappingCuration");
    }

    private static ProviderResult result(String entityIri, String label) {
        return ProviderResult.create("fake", "fake", "efo", "https://example.org/efo.owl",
                entityIri, "class",
                List.of(new ProviderResult.LocalizedText(label, "en")),
                List.of(), List.of("Description"), "CC0", "fixture",
                "direct match", 1.0, "1", Instant.parse("2026-07-21T00:00:00Z"),
                URI.create("https://example.org/term"), 0, false, null);
    }
}
