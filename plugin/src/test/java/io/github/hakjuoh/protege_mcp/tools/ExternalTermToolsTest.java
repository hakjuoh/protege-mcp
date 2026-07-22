package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.external.ExternalProviderGateway;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ProviderInspectRequest;
import io.github.hakjuoh.protege_mcp.external.ProviderPage;
import io.github.hakjuoh.protege_mcp.external.ProviderResult;
import io.github.hakjuoh.protege_mcp.external.ProviderSearchRequest;
import io.github.hakjuoh.protege_mcp.external.ProviderSessionScope;
import io.github.hakjuoh.protege_mcp.external.ReuseAction;
import io.github.hakjuoh.protege_mcp.contracts.ExternalTermToolSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class ExternalTermToolsTest {

    private static final String ONTOLOGY = "https://example.org/ontology";
    private static final String TERM = "https://example.org/term";

    @Test
    void searchUsesPolicyDefaultsAndReturnsBoundedEvidence(@TempDir Path temporary)
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        ToolContext context = context(temporary, gateway, true);

        CallToolResult result = call(context, "search_external_terms", Map.of(
                "provider_id", "OLS", "query", "cell"));

        assertFalse(Boolean.TRUE.equals(result.isError()), result::toString);
        Map<String, Object> body = structured(result);
        assertSchema("search_external_terms", body);
        assertEquals("ols", body.get("provider_id"));
        assertEquals("ols4", body.get("profile"));
        assertEquals(1, body.get("returned"));
        assertEquals(2L, body.get("total"));
        assertEquals("opaque-next", body.get("next_cursor"));
        assertEquals(300, body.get("cursor_expires_in_seconds"));
        assertEquals(List.of("efo"), gateway.searchRequest.ontologies());
        assertEquals("en", gateway.searchRequest.language());
        assertEquals(7, gateway.searchRequest.limit());
        assertEquals(List.of("efo"), gateway.invocation.allowedOntologies());
        assertEquals(List.of("en"), gateway.invocation.allowedLanguages());
        assertEquals(7, gateway.invocation.maxResults());
        assertFalse(gateway.invocation.cacheReadAllowed());
        assertEquals("static", gateway.scope.principalType());
        assertEquals("static-local-admin", gateway.scope.clientId());
        assertEquals("", gateway.scope.grantId());
        assertFalse(gateway.scope.workspaceId().isBlank());

        CallToolResult continued = call(context, "search_external_terms",
                Map.of("cursor", "opaque-next"));
        assertFalse(Boolean.TRUE.equals(continued.isError()), continued::toString);
        assertEquals("opaque-next", gateway.cursor);
        assertEquals(null, gateway.searchRequest);
    }

    @Test
    void opaqueCursorCannotBeMixedWithNewSearchArguments(@TempDir Path temporary)
            throws Exception {
        ToolContext context = context(temporary, new FakeGateway(), true);

        CallToolResult result = call(context, "search_external_terms", Map.of(
                "cursor", "opaque", "query", "changed"));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("provider_cursor_arguments_conflict", structured(result).get("code"));
    }

    @Test
    void inspectUsesPolicyLanguageAndPreservesFingerprint(@TempDir Path temporary)
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        ToolContext context = context(temporary, gateway, true);

        CallToolResult result = call(context, "inspect_external_term", Map.of(
                "provider_id", "ols", "ontology", "efo", "iri", TERM));

        assertFalse(Boolean.TRUE.equals(result.isError()), result::toString);
        assertEquals("en", gateway.inspectRequest.language());
        Map<String, Object> evidence = cast(structured(result).get("result"));
        assertSchema("inspect_external_term", structured(result));
        assertEquals(TERM, evidence.get("entity_iri"));
        assertTrue(String.valueOf(evidence.get("term_fingerprint")).startsWith("sha256:"));
        assertTrue(String.valueOf(evidence.get("result_fingerprint")).startsWith("sha256:"));
        assertEquals(false, structured(result).get("cache_hit"));
    }

    @Test
    void defaultLanguagePreservesPolicyAuthorOrder(@TempDir Path temporary) throws Exception {
        FakeGateway gateway = new FakeGateway();
        ToolContext context = context(temporary, gateway, true, "[fr, de]");

        CallToolResult result = call(context, "search_external_terms", Map.of(
                "provider_id", "ols", "query", "cell"));

        assertFalse(Boolean.TRUE.equals(result.isError()), result::toString);
        assertEquals("fr", gateway.searchRequest.language());
        assertEquals(List.of("fr", "de"), gateway.invocation.allowedLanguages());
    }

    @Test
    void identicalPoliciesAtDifferentCanonicalRootsHaveDifferentProviderIdentity(
            @TempDir Path temporary) throws Exception {
        FakeGateway first = new FakeGateway();
        FakeGateway second = new FakeGateway();
        ToolContext firstContext = context(temporary.resolve("first"), first, true);
        ToolContext secondContext = context(temporary.resolve("second"), second, true);

        call(firstContext, "search_external_terms", Map.of(
                "provider_id", "ols", "query", "cell"));
        call(secondContext, "search_external_terms", Map.of(
                "provider_id", "ols", "query", "cell"));

        assertFalse(first.invocation.projectFingerprint()
                .equals(second.invocation.projectFingerprint()));
    }

    @Test
    void disabledAndOutOfPolicyProvidersFailBeforeGateway(@TempDir Path temporary)
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        ToolContext disabled = context(temporary.resolve("disabled"), gateway, false);

        CallToolResult disabledResult = call(disabled, "search_external_terms", Map.of(
                "provider_id", "ols", "query", "cell"));
        assertEquals(Boolean.TRUE, disabledResult.isError());
        assertEquals("provider_disabled", structured(disabledResult).get("code"));
        assertEquals(null, gateway.searchRequest);

        ToolContext enabled = context(temporary.resolve("enabled"), gateway, true);
        CallToolResult outside = call(enabled, "inspect_external_term", Map.of(
                "provider_id", "ols", "ontology", "go", "iri", TERM));
        assertEquals(Boolean.TRUE, outside.isError());
        assertEquals("provider_ontology_denied", structured(outside).get("code"));
        assertEquals(null, gateway.inspectRequest);
    }

    @Test
    void providerRedactionFailureUsesContentFreePublicError(@TempDir Path temporary)
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.failure = new ProviderFailure("provider_redaction_failed",
                "Provider evidence failed the publication safety check", false);
        ToolContext context = context(temporary, gateway, true);

        CallToolResult result = call(context, "search_external_terms", Map.of(
                "provider_id", "ols", "query", "private query"));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("provider_redaction_failed", structured(result).get("code"));
        assertFalse(String.valueOf(result.structuredContent()).contains("private query"));
    }

    @Test
    void proposalReinspectsAndBindsCurrentProjectIdentityWithoutWriting(@TempDir Path temporary)
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        ToolContext context = context(temporary, gateway, true);
        long before = context.access().compute(mm -> mm.getActiveOntology().getAxiomCount());

        CallToolResult result = call(context, "propose_term_reuse", proposalArgs("reuse_iri"));

        assertFalse(Boolean.TRUE.equals(result.isError()), result::toString);
        Map<String, Object> body = structured(result);
        assertSchema("propose_term_reuse", body);
        assertEquals(900, body.get("expires_in_seconds"));
        Map<String, Object> proposal = cast(body.get("proposal"));
        assertEquals("reuse_iri", proposal.get("action"));
        Map<String, Object> identity = cast(proposal.get("input_identity"));
        assertEquals(evidence().termFingerprint(), identity.get("term_fingerprint"));
        assertEquals(evidence().resultFingerprint(), identity.get("result_fingerprint"));
        assertTrue(String.valueOf(identity.get("mapping_revision")).startsWith("sha256:"));
        assertTrue(String.valueOf(identity.get("policy_digest")).startsWith("sha256:"));
        Map<String, Object> target = cast(identity.get("target_identity"));
        assertTrue(String.valueOf(target.get("project_root_fingerprint"))
                .startsWith("sha256:"));
        assertTrue(String.valueOf(target.get("policy_source_fingerprint"))
                .startsWith("sha256:"));
        assertTrue(String.valueOf(target.get("mapping_target_fingerprint"))
                .startsWith("sha256:"));
        assertEquals(false, target.get("mapping_exists"));
        assertFalse(target.toString().contains(temporary.toString()));
        long after = context.access().compute(mm -> mm.getActiveOntology().getAxiomCount());
        assertEquals(before, after);

        ProviderSessionScope owner = new ProviderSessionScope("static", "static-local-admin", "",
                context.revisions().workspaceId());
        try (var claim = context.reuseProposals().claim(owner,
                String.valueOf(body.get("proposal_id")))) {
            assertEquals(ReuseAction.REUSE_IRI, claim.proposal().action());
        }
    }

    @Test
    void proposalSupportsStrictMappingAndMintShapes(@TempDir Path temporary) throws Exception {
        ToolContext context = context(temporary, new FakeGateway(), true);
        Path mappingSidecar = temporary.resolve(".protege-mcp/mappings.sssom.tsv");
        Map<String, Object> mapping = Map.of(
                "subject_id", "https://example.org/local/Cell",
                "predicate_id", "skos:exactMatch",
                "object_id", TERM,
                "mapping_justification", "semapv:ManualMappingCuration");

        Map<String, Object> add = proposalArgs("add_mapping");
        add.put("mapping", mapping);
        CallToolResult addResult = call(context, "propose_term_reuse", add);
        assertFalse(Boolean.TRUE.equals(addResult.isError()), addResult::toString);
        assertSchema("propose_term_reuse", structured(addResult));

        Map<String, Object> mint = proposalArgs("mint_local_with_mapping");
        mint.put("mapping", mapping);
        mint.put("local_entity", Map.of(
                "iri", "https://example.org/local/Cell",
                "type", "class",
                "labels", List.of(Map.of("value", "Local cell", "language", "en"))));
        CallToolResult mintResult = call(context, "propose_term_reuse", mint);
        assertFalse(Boolean.TRUE.equals(mintResult.isError()), mintResult::toString);
        assertSchema("propose_term_reuse", structured(mintResult));
        assertTrue(java.nio.file.Files.notExists(mappingSidecar),
                "read-only proposal creation must not create the mapping sidecar");
    }

    @Test
    void proposalForcesFreshInspectionUnderCacheEnabledPolicy(@TempDir Path temporary)
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.cachedEvidence = evidence("Cached cell");
        gateway.liveEvidence = evidence("Current cell");
        ToolContext context = context(temporary, gateway, true, "[en]", 900, "cache_ok");

        CallToolResult ordinary = call(context, "inspect_external_term", Map.of(
                "provider_id", "ols", "ontology", "efo", "iri", TERM));
        assertEquals(true, structured(ordinary).get("cache_hit"));
        assertEquals(gateway.cachedEvidence.termFingerprint(),
                cast(structured(ordinary).get("result")).get("term_fingerprint"));

        CallToolResult fresh = call(context, "inspect_external_term", Map.of(
                "provider_id", "ols", "ontology", "efo", "iri", TERM, "fresh", true));
        assertEquals(false, structured(fresh).get("cache_hit"));
        String freshFingerprint = String.valueOf(
                cast(structured(fresh).get("result")).get("term_fingerprint"));
        assertEquals(gateway.liveEvidence.termFingerprint(), freshFingerprint);

        Map<String, Object> proposal = proposalArgs("reuse_iri");
        proposal.put("term_fingerprint", freshFingerprint);
        CallToolResult proposed = call(context, "propose_term_reuse", proposal);

        assertFalse(Boolean.TRUE.equals(proposed.isError()), proposed::toString);
        assertFalse(gateway.invocation.cacheReadAllowed());
        assertEquals(java.time.Duration.ZERO, gateway.invocation.cacheTtl());
        assertEquals(gateway.liveEvidence.resultFingerprint(), cast(cast(
                structured(proposed).get("proposal")).get("provider_result"))
                .get("result_fingerprint"));
    }

    @Test
    void acquisitionTimestampAndRetriesDoNotDestabilizeProposalHandshake(
            @TempDir Path temporary) throws Exception {
        FakeGateway gateway = new FakeGateway();
        ProviderResult first = acquisitionEvidence(evidence(), 0, 0);
        ProviderResult second = acquisitionEvidence(evidence(), 60, 1);
        gateway.inspectionEvidence = List.of(first, second);
        ToolContext context = context(temporary, gateway, true);

        CallToolResult inspected = call(context, "inspect_external_term", Map.of(
                "provider_id", "ols", "ontology", "efo", "iri", TERM, "fresh", true));
        Map<String, Object> inspectedEvidence = cast(structured(inspected).get("result"));
        Map<String, Object> proposal = proposalArgs("reuse_iri");
        proposal.put("term_fingerprint", inspectedEvidence.get("term_fingerprint"));

        CallToolResult proposed = call(context, "propose_term_reuse", proposal);

        assertFalse(Boolean.TRUE.equals(proposed.isError()), proposed::toString);
        Map<String, Object> proposedEvidence = cast(cast(
                structured(proposed).get("proposal")).get("provider_result"));
        assertEquals(inspectedEvidence.get("term_fingerprint"),
                proposedEvidence.get("term_fingerprint"));
        assertFalse(inspectedEvidence.get("result_fingerprint")
                .equals(proposedEvidence.get("result_fingerprint")));
    }

    @Test
    void proposalRequiresInspectLineageAndReturnsTypedCrossEvidenceErrors(
            @TempDir Path temporary) throws Exception {
        FakeGateway gateway = new FakeGateway();
        ToolContext context = context(temporary, gateway, true);
        Map<String, Object> searchLineage = proposalArgs("reuse_iri");
        searchLineage.put("term_fingerprint", searchEvidence().termFingerprint());

        CallToolResult changed = call(context, "propose_term_reuse", searchLineage);
        assertEquals(Boolean.TRUE, changed.isError());
        assertEquals("provider_term_changed", structured(changed).get("code"));

        Map<String, Object> unrelated = proposalArgs("add_mapping");
        unrelated.put("mapping", Map.of(
                "subject_id", "https://example.org/local/Cell",
                "predicate_id", "skos:exactMatch",
                "object_id", "https://example.org/unrelated",
                "mapping_justification", "semapv:ManualMappingCuration"));
        CallToolResult invalid = call(context, "propose_term_reuse", unrelated);
        assertEquals(Boolean.TRUE, invalid.isError());
        assertEquals("reuse_operation_invalid", structured(invalid).get("code"));
    }

    @Test
    void oversizedOrInvalidMappingFailsBeforeProviderEgress(@TempDir Path temporary)
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        ToolContext context = context(temporary, gateway, true);
        Map<String, Object> invalid = proposalArgs("add_mapping");
        invalid.put("mapping", Map.of(
                "subject_id", "https://example.org/local/Cell",
                "predicate_id", "skos:exactMatch",
                "object_id", TERM,
                "invalid column", "x"));

        CallToolResult refused = call(context, "propose_term_reuse", invalid);

        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("reuse_operation_invalid", structured(refused).get("code"));
        assertEquals(0, gateway.inspectCalls);

        Map<String, Object> selfMap = proposalArgs("add_mapping");
        selfMap.put("mapping", Map.of(
                "subject_id", TERM,
                "predicate_id", "skos:exactMatch",
                "object_id", TERM,
                "mapping_justification", "semapv:ManualMappingCuration"));
        CallToolResult selfRefused = call(context, "propose_term_reuse", selfMap);
        assertEquals(Boolean.TRUE, selfRefused.isError());
        assertEquals("reuse_operation_invalid", structured(selfRefused).get("code"));
        assertEquals(0, gateway.inspectCalls);

        Map<String, Object> paddedSelfMap = proposalArgs("add_mapping");
        paddedSelfMap.put("mapping", Map.of(
                "subject_id", TERM,
                "predicate_id", "skos:exactMatch",
                "object_id", "  " + TERM + "  ",
                "mapping_justification", "semapv:ManualMappingCuration"));
        CallToolResult paddedRefused = call(context, "propose_term_reuse", paddedSelfMap);
        assertEquals(Boolean.TRUE, paddedRefused.isError());
        assertEquals("reuse_operation_invalid", structured(paddedRefused).get("code"));
        assertEquals(0, gateway.inspectCalls);

        Map<String, Object> oversizedPadding = proposalArgs("add_mapping");
        oversizedPadding.put("mapping", Map.of(
                "subject_id", " ".repeat(65_537),
                "predicate_id", "skos:exactMatch",
                "object_id", TERM,
                "mapping_justification", "semapv:ManualMappingCuration"));
        CallToolResult oversizedPaddingRefused = call(
                context, "propose_term_reuse", oversizedPadding);
        assertEquals(Boolean.TRUE, oversizedPaddingRefused.isError());
        assertEquals("reuse_operation_invalid",
                structured(oversizedPaddingRefused).get("code"));
        assertEquals(0, gateway.inspectCalls);

        Map<String, Object> literalEndpoint = proposalArgs("add_mapping");
        literalEndpoint.put("mapping", Map.of(
                "subject_id", "https://example.org/local/Cell",
                "predicate_id", "skos:exactMatch",
                "object_id", TERM,
                "object_type", "rdfs:Literal",
                "object_label", "External term",
                "mapping_justification", "semapv:ManualMappingCuration"));
        CallToolResult literalRefused = call(context, "propose_term_reuse", literalEndpoint);
        assertEquals(Boolean.TRUE, literalRefused.isError());
        assertEquals("reuse_operation_invalid", structured(literalRefused).get("code"));
        assertEquals(0, gateway.inspectCalls);

        Map<String, Object> noTermFound = proposalArgs("add_mapping");
        noTermFound.put("mapping", Map.of(
                "subject_id", "https://example.org/local/Cell",
                "predicate_id", "skos:exactMatch",
                "object_id", "sssom:NoTermFound",
                "mapping_justification", "semapv:ManualMappingCuration"));
        CallToolResult noTermFoundRefused = call(context, "propose_term_reuse", noTermFound);
        assertEquals(Boolean.TRUE, noTermFoundRefused.isError());
        assertEquals("reuse_operation_invalid", structured(noTermFoundRefused).get("code"));
        assertEquals(0, gateway.inspectCalls);

        Map<String, Object> unrelated = proposalArgs("add_mapping");
        unrelated.put("mapping", Map.of(
                "subject_id", "https://example.org/local/Cell",
                "predicate_id", "skos:exactMatch",
                "object_id", "https://example.org/unrelated",
                "mapping_justification", "semapv:ManualMappingCuration"));
        CallToolResult unrelatedRefused = call(context, "propose_term_reuse", unrelated);
        assertEquals(Boolean.TRUE, unrelatedRefused.isError());
        assertEquals("reuse_operation_invalid", structured(unrelatedRefused).get("code"));
        assertEquals(0, gateway.inspectCalls);

        Map<String, Object> unrelatedMint = proposalArgs("mint_local_with_mapping");
        unrelatedMint.put("mapping", Map.of(
                "subject_id", "https://example.org/local/Cell",
                "predicate_id", "skos:exactMatch",
                "object_id", "https://example.org/unrelated",
                "mapping_justification", "semapv:ManualMappingCuration"));
        unrelatedMint.put("local_entity", Map.of(
                "iri", "https://example.org/local/Cell",
                "type", "class",
                "labels", List.of(Map.of("value", "Local cell", "language", "en"))));
        CallToolResult unrelatedMintRefused = call(
                context, "propose_term_reuse", unrelatedMint);
        assertEquals(Boolean.TRUE, unrelatedMintRefused.isError());
        assertEquals("reuse_operation_invalid",
                structured(unrelatedMintRefused).get("code"));
        assertEquals(0, gateway.inspectCalls);
    }

    @Test
    void policyChangeAfterInspectionPreventsProposalIssue(@TempDir Path temporary)
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        ToolContext context = context(temporary, gateway, true);
        Path policy = temporary.resolve(".protege-mcp/project.yaml");
        gateway.afterInspect = () -> {
            try {
                String changed = java.nio.file.Files.readString(policy)
                        .replace("max_results: 7", "max_results: 6");
                ProjectPolicyFixtures.writePolicy(policy, changed);
            } catch (java.io.IOException failure) {
                throw new RuntimeException(failure);
            }
        };

        CallToolResult refused = call(context, "propose_term_reuse",
                proposalArgs("reuse_iri"));

        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("proposal_input_changed", structured(refused).get("code"));
    }

    @Test
    void proposalRejectsChangedEvidenceAndRevocationErasesIssuedState(@TempDir Path temporary)
            throws Exception {
        ToolContext context = context(temporary, new FakeGateway(), true);
        Map<String, Object> changed = proposalArgs("reuse_iri");
        changed.put("term_fingerprint", "sha256:" + "0".repeat(64));

        CallToolResult refused = call(context, "propose_term_reuse", changed);
        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("provider_term_changed", structured(refused).get("code"));

        Map<String, Object> issued = structured(call(context, "propose_term_reuse",
                proposalArgs("reuse_iri")));
        assertEquals(1, context.revokeExternalClient("static-local-admin"));
        ProviderSessionScope owner = new ProviderSessionScope("static", "static-local-admin", "",
                context.revisions().workspaceId());
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                () -> context.reuseProposals().claim(owner,
                        String.valueOf(issued.get("proposal_id")))).code());
    }

    @Test
    void catalogRegistersAllToolsWithTypedSchemas() {
        ToolContext context = new ToolContext(null, null, null, new FakeGateway());
        List<SyncToolSpecification> specifications = ToolCatalog.buildAll(context);
        SyncToolSpecification search = specifications.stream()
                .filter(spec -> "search_external_terms".equals(spec.tool().name()))
                .findFirst().orElseThrow();
        SyncToolSpecification inspect = specifications.stream()
                .filter(spec -> "inspect_external_term".equals(spec.tool().name()))
                .findFirst().orElseThrow();
        SyncToolSpecification propose = specifications.stream()
                .filter(spec -> "propose_term_reuse".equals(spec.tool().name()))
                .findFirst().orElseThrow();

        assertNotNull(search.tool().outputSchema());
        assertNotNull(inspect.tool().outputSchema());
        assertNotNull(propose.tool().outputSchema());
        assertTrue(String.valueOf(search.tool().inputSchema()).contains("cursor"));
        assertTrue(String.valueOf(inspect.tool().outputSchema()).contains("result_fingerprint"));
        assertTrue(String.valueOf(propose.tool().inputSchema()).contains("mint_local_with_mapping"));
    }

    private static ToolContext context(Path project, ExternalProviderGateway gateway,
            boolean enabled) throws Exception {
        return context(project, gateway, enabled, "[en]");
    }

    private static ToolContext context(Path project, ExternalProviderGateway gateway,
            boolean enabled, String languages) throws Exception {
        return context(project, gateway, enabled, languages, 0, "fresh_required");
    }

    private static ToolContext context(Path project, ExternalProviderGateway gateway,
            boolean enabled, String languages, int ttlSeconds, String freshness) throws Exception {
        String policy = ProjectPolicyFixtures.minimalPolicy("external-tools", ONTOLOGY)
                .replace("version: 1", "version: 2")
                + "external_terms:\n"
                + "  providers:\n"
                + "    - id: ols\n"
                + "      profile: ols4\n"
                + "      enabled: " + enabled + "\n"
                + "      origin_alias: ebi\n"
                + "      ontologies: [efo]\n"
                + "      languages: " + languages + "\n"
                + "      ttl_seconds: " + ttlSeconds + "\n"
                + "      freshness: " + freshness + "\n"
                + "      max_results: 7\n"
                + "validation:\n"
                + "  required_stages: [structural]\n";
        ProjectPolicyFixtures.writePolicy(project.resolve(".protege-mcp/project.yaml"), policy);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        manager.setOntologyDocumentIRI(ontology, IRI.create(project.resolve("ontology.ttl").toUri()));
        OntologyAccess access = HeadlessAccess.over(FakeModelManager.over(ontology));
        ToolContext context = new ToolContext(access, null, null, gateway);
        var loaded = RevisionTools.resolvePolicy(context, null).policy();
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        return context;
    }

    private static CallToolResult call(ToolContext context, String name,
            Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        ExternalTermTools.register(registry, context);
        return registry.build().stream().filter(spec -> name.equals(spec.tool().name()))
                .findFirst().orElseThrow().callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest(name, arguments));
    }

    private static ProviderResult evidence() {
        return evidence("Cell");
    }

    private static ProviderResult evidence(String label) {
        return ProviderResult.create("ols", "ols4", "efo", ONTOLOGY, TERM, "class",
                List.of(new ProviderResult.LocalizedText(label, "en")), List.of(),
                List.of("A cell description"), "CC-BY-4.0", "fixture",
                "exact_label", 1.0, "4.0", Instant.parse("2026-07-21T00:00:00Z"),
                URI.create("https://example.org/api/terms/cell"), 0, false, null);
    }

    private static ProviderResult searchEvidence() {
        return ProviderResult.create("ols", "ols4", "efo", ONTOLOGY, TERM, "class",
                List.of(new ProviderResult.LocalizedText("Cell", "en")), List.of(),
                List.of("A cell description"), null, "OLS4 search result",
                "exact_label_rank_0", 0.99, null,
                Instant.parse("2026-07-21T00:00:00Z"),
                URI.create("https://example.org/api/search/term"), 0, false, null);
    }

    private static ProviderResult acquisitionEvidence(ProviderResult source,
            long timestampOffsetSeconds, int retries) {
        return ProviderResult.create(source.providerId(), source.profile(),
                source.sourceOntology(), source.sourceOntologyIri(), source.entityIri(),
                source.entityType(), source.labels(), source.synonyms(), source.descriptions(),
                source.license(), source.provenance(), source.matchExplanation(), source.score(),
                source.providerVersion(), source.providerTimestamp().plusSeconds(
                        timestampOffsetSeconds), source.sourceUrl(), retries,
                source.deprecated(), source.replacedBy());
    }

    private static Map<String, Object> proposalArgs(String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", "ols");
        result.put("ontology", "efo");
        result.put("iri", TERM);
        result.put("term_fingerprint", evidence().termFingerprint());
        result.put("action", action);
        return result;
    }

    private static void assertSchema(String name, Map<String, Object> value) {
        var violations = ToolSchemaValidator.compile(ExternalTermToolSchemas.output(name))
                .violations(value);
        assertTrue(violations.isEmpty(), violations::toString);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class FakeGateway implements ExternalProviderGateway {
        private ProviderSearchRequest searchRequest;
        private ProviderInspectRequest inspectRequest;
        private Invocation invocation;
        private ProviderSessionScope scope;
        private String cursor;
        private ProviderFailure failure;
        private ProviderResult cachedEvidence;
        private ProviderResult liveEvidence = evidence();
        private int inspectCalls;
        private Runnable afterInspect;
        private List<ProviderResult> inspectionEvidence = List.of();

        @Override
        public SearchOutcome search(ProviderSessionScope scope,
                ProviderSearchRequest initialRequest, String cursor, InvocationResolver resolver)
                throws ProviderFailure {
            if (failure != null) throw failure;
            this.scope = scope;
            this.cursor = cursor;
            searchRequest = initialRequest;
            invocation = resolver.resolve(initialRequest == null ? "ols" : initialRequest.providerId());
            ProviderPage page = new ProviderPage(List.of(evidence()), 2, null,
                    Instant.parse("2026-07-21T00:00:00Z"), 0);
            return new SearchOutcome("ols", "ols4", page, "opaque-next", false);
        }

        @Override
        public InspectOutcome inspect(ProviderInspectRequest request,
                InvocationResolver resolver) throws ProviderFailure {
            inspectCalls++;
            inspectRequest = request;
            invocation = resolver.resolve(request.providerId());
            boolean cached = invocation.cacheReadAllowed() && cachedEvidence != null;
            ProviderResult live = inspectionEvidence.isEmpty() ? liveEvidence
                    : inspectionEvidence.get(Math.min(inspectCalls - 1,
                            inspectionEvidence.size() - 1));
            InspectOutcome outcome = new InspectOutcome(
                    cached ? cachedEvidence : live, cached);
            if (afterInspect != null) afterInspect.run();
            return outcome;
        }

        @Override
        public int revokeClient(String clientId) {
            return 0;
        }

        @Override
        public int revokeGrant(String clientId, String grantId) {
            return 0;
        }

        @Override
        public int clearWorkspace(String workspaceId) {
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
