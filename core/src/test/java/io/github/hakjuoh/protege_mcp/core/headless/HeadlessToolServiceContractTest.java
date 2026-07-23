package io.github.hakjuoh.protege_mcp.core.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceTransaction;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class HeadlessToolServiceContractTest {

    @Test
    void surfaceExecutionIsAuditedAndReturnsTheBoundedConfiguration(@TempDir Path temp)
            throws Exception {
        withHome(temp, () -> {
            HeadlessToolService service = service(temp);
            Map<String, Object> result = service.execute(HeadlessToolCatalog.SURFACE_TOOL,
                    Map.of(), HeadlessToolService.DEFAULT_CAPABILITIES, 123, 456);
            assertEquals("stdio", result.get("transport"));
            assertEquals(123, result.get("max_inbound_message_bytes"));
            assertEquals(456, result.get("max_outbound_message_bytes"));
            assertThrows(UnsupportedOperationException.class,
                    () -> result.put("late", true));
            return null;
        });
    }

    @Test
    void headlessPolicyValidationReportsTheSameOutOfBandV1Migration(@TempDir Path temp)
            throws Exception {
        withHome(temp.resolve("home"), () -> {
            Path project = temp.resolve("project");
            Files.createDirectories(project);
            Path policyPath = project.resolve("project.yaml");
            Files.writeString(project.resolve("ontology.ttl"), """
                    @prefix owl: <http://www.w3.org/2002/07/owl#> .
                    <https://example.org/ontology> a owl:Ontology .
                    """);
            Files.writeString(project.resolve("ro-crate-metadata.json"), """
                    {"@context":"https://w3id.org/ro/crate/1.1/context","@graph":[
                      {"@id":"ro-crate-metadata.json","@type":"CreativeWork","about":{"@id":"./"},"conformsTo":{"@id":"https://w3id.org/ro/crate/1.1"}},
                      {"@id":"./","@type":"Dataset","name":"Headless policy test","description":"Headless policy migration contract fixture.","datePublished":"2026-07-20","license":{"@id":"https://www.apache.org/licenses/LICENSE-2.0"},"identifier":"headless-policy","conformsTo":{"@id":"https://hakjuoh.github.io/protege-mcp/profiles/project-v1/"},"mainEntity":{"@id":"ontology.ttl"},"hasPart":{"@id":"ontology.ttl"}},
                      {"@id":"https://hakjuoh.github.io/protege-mcp/profiles/project-v1/","@type":"CreativeWork","name":"Ontology project profile"},
                      {"@id":"ontology.ttl","@type":"File","encodingFormat":"text/turtle","about":{"@id":"https://example.org/ontology"}},
                      {"@id":"https://example.org/ontology","@type":"Dataset","conformsTo":{"@id":"https://www.w3.org/TR/owl2-overview/"}}
                    ]}
                    """);
            Files.writeString(policyPath, """
                    version: 1
                    project_id: headless-policy
                    root_ontology: https://example.org/ontology
                    interoperability:
                      profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                      root_artifact: ontology.ttl
                      metadata:
                        path: ro-crate-metadata.json
                        format: ro-crate-1.1
                      canonicalization:
                        algorithm: RDFC-1.0
                        hash: SHA-256
                        scope: root-ontology
                    validation:
                      required_stages: [interoperability, structural]
                    """);
            String digest = ProjectPolicyLoader.load(policyPath, null).digest();

            Map<String, Object> result = new HeadlessToolService(policyPath,
                    new StructuralReasonerFactory(), Clock.systemUTC()).execute(
                            "validate_project_policy", Map.of(),
                            HeadlessToolService.DEFAULT_CAPABILITIES, 1024, 4096);

            assertEquals(true, result.get("valid"), result::toString);
            assertEquals(1, result.get("schema_version"));
            assertEquals(digest, result.get("policy_digest"));
            @SuppressWarnings("unchecked")
            Map<String, Object> migration = (Map<String, Object>) result.get("migration");
            assertNotNull(migration, result::toString);
            assertEquals(false, migration.get("automatic_write"));
            assertEquals(false, migration.get("diagnostic_affects_digest"));
            return null;
        });
    }

    @Test
    void headlessProjectQcCannotExecuteThroughAPolicySymlink(@TempDir Path temp)
            throws Exception {
        withHome(temp.resolve("home"), () -> {
            Path target = temp.resolve("outside-policy.yaml");
            Files.writeString(target, "version: 1\n");
            Path link = temp.resolve("project/policy.yaml");
            Files.createDirectories(link.getParent());
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | IOException unsupported) {
                Assumptions.abort("symbolic links are unavailable: " + unsupported);
            }
            HeadlessToolService service = new HeadlessToolService(link,
                    new StructuralReasonerFactory(), Clock.systemUTC());

            IOException refusal = assertThrows(IOException.class,
                    () -> service.execute("run_project_qc", Map.of(),
                            HeadlessToolService.DEFAULT_CAPABILITIES, 1024, 4096));

            assertTrue(refusal.getMessage().contains("secure path validation"),
                    refusal::getMessage);
            return null;
        });
    }

    @Test
    void nonCanonicalResultsFailBeforeTheyCanReachSuccessAudit() {
        for (Object value : java.util.List.of(Double.NaN, Double.POSITIVE_INFINITY)) {
            HeadlessExecutionException failure = assertThrows(HeadlessExecutionException.class,
                    () -> HeadlessToolService.canonicalResult("write_import_lock",
                            Map.of("value", value), true));
            assertEquals("result_contract_violation", failure.code());
            assertEquals(true, failure.details().get("outcome_unknown"));
            assertFalse(failure.retryable());
        }
        Map<String, Object> normalized = HeadlessToolService.canonicalResult(
                "write_import_lock", Map.of("value", new AtomicInteger(7)), true);
        assertEquals(7, normalized.get("value"));
        assertThrows(UnsupportedOperationException.class,
                () -> normalized.put("late", true));
    }

    @Test
    void mutationFailureCarriesUnknownOutcomeAndStateCheckEvidence(@TempDir Path temp)
            throws Exception {
        withHome(temp, () -> {
            HeadlessExecutionException failure = assertThrows(HeadlessExecutionException.class,
                    () -> service(temp).execute("write_import_lock", Map.of(),
                            HeadlessToolService.DEFAULT_CAPABILITIES, 123, 456));
            assertEquals("mutation_outcome_unknown", failure.code());
            assertEquals(true, failure.details().get("outcome_unknown"));
            assertEquals(true, failure.details().get("retry_requires_state_check"));
            assertFalse(failure.retryable());
            return null;
        });
    }

    @Test
    void knownPartialMutationReceiptIsNotReclassifiedAsUnknown() {
        HeadlessExecutionException receipt = new HeadlessExecutionException(
                "materialization_backup_published", "known backup side effect",
                Map.of("effects_prevented", false, "outcome_known", true,
                        "backup_verified", true, "target_preserved", true),
                false, null);
        HeadlessExecutionException unknown = new HeadlessExecutionException(
                "materialization_backup_published", "unverified backup side effect",
                Map.of("effects_prevented", false, "outcome_known", false),
                false, null);

        assertTrue(HeadlessToolService.mutationOutcomeKnown(receipt));
        assertFalse(HeadlessToolService.mutationOutcomeKnown(unknown));
    }

    @Test
    void backupSideEffectMappingPreservesVerifiedAndUnknownFacts() {
        WorkspaceTransaction.BackupSideEffect verified =
                new WorkspaceTransaction.BackupSideEffect(
                        Path.of("backup.bak"), true, true, true,
                        "sha256:" + "a".repeat(64), true, true);
        HeadlessExecutionException known = HeadlessMaterializationService.backupApplied(
                new WorkspaceTransaction.BackupAppliedException(
                        verified, new IOException("target move failed")));
        assertEquals("materialization_backup_published", known.code());
        assertEquals(true, known.details().get("outcome_known"));
        assertEquals(true, known.details().get("backup_verified"));
        assertEquals(true, known.details().get("target_preserved"));
        assertTrue(HeadlessToolService.mutationOutcomeKnown(known));

        WorkspaceTransaction.BackupSideEffect detached =
                new WorkspaceTransaction.BackupSideEffect(
                        Path.of("backup.bak"), false, false, false,
                        null, false, false);
        HeadlessExecutionException uncertain = HeadlessMaterializationService.backupApplied(
                new WorkspaceTransaction.BackupAppliedException(
                        detached, new IOException("anchor detached")));
        assertEquals(false, uncertain.details().get("outcome_known"));
        assertFalse(uncertain.details().containsKey("target_preserved"));
        assertFalse(uncertain.details().containsKey("backup_sha256"));
        assertFalse(HeadlessToolService.mutationOutcomeKnown(uncertain));
    }

    @Test
    void guardedReplacementMappingPreservesConcurrentTargetAndRetainedEvidence() {
        String displaced = "sha256:" + "a".repeat(64);
        String concurrent = "sha256:" + "b".repeat(64);
        String intended = "sha256:" + "c".repeat(64);
        WorkspaceTransaction.GuardedReplacementSideEffect receipt =
                new WorkspaceTransaction.GuardedReplacementSideEffect(
                        Path.of("target.ofn"), true, true, true, true, intended, true,
                        Path.of("private/displaced"), true, displaced, true,
                        true, true, concurrent, false, false,
                        Path.of("private/staged"), intended,
                        null, false, false, false, null);

        HeadlessExecutionException mapped = HeadlessMaterializationService.guardedReplacement(
                new WorkspaceTransaction.GuardedReplacementException(
                        receipt, new IOException("concurrent target")));

        assertEquals("materialization_guarded_replacement_incomplete", mapped.code());
        assertEquals(true, mapped.details().get("outcome_known"));
        assertEquals(false, mapped.details().get("effects_prevented"));
        assertEquals(true, mapped.details().get("source_moved"));
        assertEquals(true, mapped.details().get("staged_state_known"));
        assertEquals(true, mapped.details().get("displaced_state_known"));
        assertEquals(displaced, mapped.details().get("displaced_sha256"));
        assertEquals(concurrent, mapped.details().get("target_sha256"));
        assertEquals(intended, mapped.details().get("intended_target_sha256"));
        assertEquals(false, mapped.details().get("publication_applied"));
        assertTrue(HeadlessToolService.mutationOutcomeKnown(mapped));
    }

    @Test
    void workspaceRecoveryMappingNeverClaimsEffectsWerePrevented() {
        String restored = "sha256:" + "d".repeat(64);
        WorkspaceTransaction.OrphanRecoverySideEffect receipt =
                new WorkspaceTransaction.OrphanRecoverySideEffect(
                        true, true, true, true, restored, true, restored, 1);

        HeadlessExecutionException mapped = HeadlessMaterializationService.orphanRecoveryApplied(
                new WorkspaceTransaction.OrphanRecoveryAppliedException(receipt, null));

        assertEquals("materialization_workspace_recovery_applied", mapped.code());
        assertEquals(false, mapped.details().get("effects_prevented"));
        assertEquals(true, mapped.details().get("outcome_known"));
        assertEquals(true, mapped.details().get("retry_requires_state_check"));
        assertEquals(true, mapped.details().get("target_restored"));
        assertEquals(restored, mapped.details().get("target_sha256"));
        assertTrue(HeadlessToolService.mutationOutcomeKnown(mapped));
    }

    @Test
    void ambiguousRecoveryMappingProvidesAnOperatorRunbook() {
        WorkspaceTransaction.AmbiguousRecoverySideEffect receipt =
                new WorkspaceTransaction.AmbiguousRecoverySideEffect(2,
                        List.of(Path.of("private-a"), Path.of("private-b")));

        HeadlessExecutionException mapped = HeadlessMaterializationService.ambiguousRecovery(
                new WorkspaceTransaction.AmbiguousRecoveryException(receipt, null));

        assertEquals("materialization_workspace_recovery_ambiguous", mapped.code());
        assertEquals(true, mapped.details().get("effects_prevented"));
        assertEquals(true, mapped.details().get("manual_intervention_required"));
        assertEquals(2, mapped.details().get("evidence_count"));
        assertTrue(mapped.details().get("resolution").toString().contains("Stop all writers"));
        assertTrue(HeadlessToolService.mutationOutcomeKnown(mapped));
    }

    @Test
    void auditInitializationFailureIsClassifiedBeforeExecution(@TempDir Path temp) throws Exception {
        withHome(temp, () -> {
            System.setProperty("user.home", "");
            HeadlessExecutionException failure = assertThrows(HeadlessExecutionException.class,
                    () -> service(temp).execute(HeadlessToolCatalog.SURFACE_TOOL, Map.of(),
                            HeadlessToolService.DEFAULT_CAPABILITIES, 123, 456));
            assertEquals("audit_failed_before_execution", failure.code());
            assertEquals(true, failure.details().get("effects_prevented"));
            return null;
        });
    }

    @Test
    void mappingToolsCompleteTheGovernedRoundTripAndRefuseUnconfirmedOrStaleWrites(
            @TempDir Path temp) throws Exception {
        withHome(temp.resolve("home"), () -> {
            Path policy = writeMappingProject(temp);
            Path canonical = temp.resolve(".protege-mcp/mappings.sssom.tsv");
            Path exported = temp.resolve("exports/mappings.tsv");
            Files.createDirectories(exported.getParent());
            HeadlessToolService service = new HeadlessToolService(policy,
                    new StructuralReasonerFactory(), Clock.systemUTC());

            Map<String, Object> empty = execute(service, "list_mappings", Map.of());
            assertEquals(false, empty.get("exists"));
            assertEquals(0, empty.get("record_count"));
            String emptyRevision = (String) empty.get("mapping_revision");

            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("subject_id", "https://example.org/ontology#A");
            mapping.put("predicate_id", "skos:exactMatch");
            mapping.put("object_id", "https://example.org/ontology#B");
            mapping.put("mapping_justification", "semapv:ManualMappingCuration");
            Map<String, Object> add = new LinkedHashMap<>();
            add.put("expected_mapping_revision", emptyRevision);
            add.put("mapping", mapping);
            add.put("mapping_set_id", "https://example.org/mappings");
            add.put("license", "https://creativecommons.org/licenses/by/4.0/");

            HeadlessExecutionException unconfirmed = assertThrows(HeadlessExecutionException.class,
                    () -> execute(service, "add_mapping", add));
            assertEquals("confirmation_required", unconfirmed.code());
            assertEquals(true, unconfirmed.details().get("effects_prevented"));
            assertFalse(Files.exists(canonical));

            add.put("confirm", true);
            Map<String, Object> added = execute(service, "add_mapping", add);
            assertEquals(true, added.get("committed"));
            assertEquals(true, added.get("valid"), added::toString);
            assertEquals(1, added.get("record_count"));
            String addedRevision = (String) added.get("mapping_revision");

            HeadlessExecutionException stale = assertThrows(HeadlessExecutionException.class,
                    () -> execute(service, "add_mapping", add));
            assertEquals("mapping_revision_conflict", stale.code());
            assertEquals(true, stale.details().get("effects_prevented"));
            assertEquals(addedRevision,
                    execute(service, "list_mappings", Map.of()).get("mapping_revision"));

            Map<String, Object> listed = execute(service, "list_mappings", Map.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.get("items");
            assertEquals(1, rows.size());
            String mappingId = (String) rows.get(0).get("mapping_id");
            assertTrue(mappingId.startsWith("sha256:"));
            Map<String, Object> validated = execute(service, "validate_mappings", Map.of());
            assertEquals(true, validated.get("valid"), validated::toString);
            assertEquals(0, ((Number) validated.get("error_count")).intValue());

            Map<String, Object> exportedResult = execute(service, "export_sssom", Map.of(
                    "expected_mapping_revision", addedRevision,
                    "destination", "exports/mappings.tsv", "confirm", true));
            assertEquals(true, exportedResult.get("committed"));
            assertEquals(true, exportedResult.get("lossless"));
            assertTrue(Files.isRegularFile(exported));

            Map<String, Object> removed = execute(service, "remove_mapping", Map.of(
                    "expected_mapping_revision", addedRevision,
                    "mapping_id", mappingId, "confirm", true));
            assertEquals(0, removed.get("record_count"));

            Map<String, Object> imported = execute(service, "import_sssom", Map.of(
                    "expected_mapping_revision", removed.get("mapping_revision"),
                    "source", "exports/mappings.tsv", "mode", "replace", "confirm", true));
            assertEquals(1, imported.get("source_records"));
            assertEquals(1, imported.get("record_count"));
            assertEquals(true, imported.get("valid"), imported::toString);

            String longId = "https://example.org/mapping/" + "x".repeat(5_000);
            Map<String, Object> longMapping = new LinkedHashMap<>();
            longMapping.put("mapping_id", longId);
            longMapping.put("subject_id", "https://example.org/ontology#B");
            longMapping.put("predicate_id", "skos:exactMatch");
            longMapping.put("object_id", "https://example.org/ontology#A");
            longMapping.put("mapping_justification", "semapv:ManualMappingCuration");
            Map<String, Object> longAdded = execute(service, "add_mapping", Map.of(
                    "expected_mapping_revision", imported.get("mapping_revision"),
                    "mapping", longMapping, "confirm", true));
            Map<String, Object> longRemoved = execute(service, "remove_mapping", Map.of(
                    "expected_mapping_revision", longAdded.get("mapping_revision"),
                    "mapping_id", longId, "confirm", true));
            assertEquals(1, longRemoved.get("record_count"));
            return null;
        });
    }

    @Test
    void materializationPreviewIsPrivateAndCommitIsVerifiedAtomicAndIdempotent(
            @TempDir Path temp) throws Exception {
        withHome(temp.resolve("home"), () -> {
            Path policy = writeMappingProject(temp);
            Files.writeString(temp.resolve("ontology.ttl"), """
                    @prefix owl: <http://www.w3.org/2002/07/owl#> .
                    @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                    <https://example.org/ontology> a owl:Ontology .
                    <https://example.org/ontology#A> a owl:Class ;
                        rdfs:subClassOf <https://example.org/ontology#B> .
                    <https://example.org/ontology#B> a owl:Class ;
                        rdfs:subClassOf <https://example.org/ontology#C> .
                    <https://example.org/ontology#C> a owl:Class .
                    """);
            Files.createDirectories(temp.resolve("artifacts"));
            HeadlessToolService service = new HeadlessToolService(policy,
                    new org.semanticweb.HermiT.ReasonerFactory(),
                    Clock.fixed(java.time.Instant.parse("2026-07-22T12:00:00Z"),
                            java.time.ZoneOffset.UTC));
            Map<String, Object> request = Map.of(
                    "categories", List.of("subclass_axioms"),
                    "destination", Map.of("kind", "project_file",
                            "identifier", "artifacts/inferred.ofn"),
                    "provenance", Map.of("generator", "protege-mcp-test",
                            "purpose", "headless materialization contract"),
                    "limits", Map.of("max_axioms_per_category", 100,
                            "max_axioms_total", 100, "max_bytes", 1_048_576,
                            "timeout_ms", 10_000));

            Map<String, Object> preview = execute(service, "materialize_inferences", request);
            assertEquals(true, preview.get("preview_only"), preview::toString);
            assertEquals(false, preview.get("live_state_changed"), preview::toString);
            assertFalse(Files.exists(temp.resolve("artifacts/inferred.ofn")));
            @SuppressWarnings("unchecked")
            Map<String, Object> artifact = (Map<String, Object>) preview.get("artifact");
            Map<String, Object> commit = new LinkedHashMap<>();
            commit.put("artifact_id", artifact.get("artifact_id"));
            commit.put("artifact_fingerprint", artifact.get("artifact_fingerprint"));

            HeadlessExecutionException unconfirmed = assertThrows(
                    HeadlessExecutionException.class,
                    () -> execute(service, "commit_materialization", commit));
            assertEquals("confirmation_required", unconfirmed.code());
            assertFalse(Files.exists(temp.resolve("artifacts/inferred.ofn")));

            commit.put("confirm", true);
            Map<String, Object> committed = execute(
                    service, "commit_materialization", commit);
            assertEquals(true, committed.get("committed"), committed::toString);
            assertEquals("committed", committed.get("status"));
            assertTrue(Files.isRegularFile(temp.resolve("artifacts/inferred.ofn")));
            assertTrue(String.valueOf(committed.get("target_digest")).startsWith("sha256:"));

            Map<String, Object> repeated = execute(
                    service, "commit_materialization", commit);
            assertEquals(false, repeated.get("committed"), repeated::toString);
            assertEquals("noop", repeated.get("status"));
            assertEquals(committed.get("target_digest"), repeated.get("target_digest"));

            Map<String, Object> collisionRequest = new LinkedHashMap<>(request);
            collisionRequest.put("destination", Map.of("kind", "project_file",
                    "identifier", "artifacts/collision.ofn"));
            Map<String, Object> collisionPreview = execute(
                    service, "materialize_inferences", collisionRequest);
            @SuppressWarnings("unchecked")
            Map<String, Object> collisionArtifact =
                    (Map<String, Object>) collisionPreview.get("artifact");
            Path collisionFile = temp.resolve("artifacts/collision.ofn");
            Files.writeString(collisionFile, """
                    Ontology(<urn:test:existing>
                      SubClassOf(<https://example.org/ontology#A> <https://example.org/ontology#C>)
                    )
                    """);
            String collisionDigest = io.github.hakjuoh.protege_mcp.core.release.ArtifactStore
                    .sha256(Files.readAllBytes(collisionFile));
            Map<String, Object> collisionCommit = new LinkedHashMap<>();
            collisionCommit.put("artifact_id", collisionArtifact.get("artifact_id"));
            collisionCommit.put("artifact_fingerprint",
                    collisionArtifact.get("artifact_fingerprint"));
            collisionCommit.put("confirm", true);
            collisionCommit.put("overwrite", true);
            collisionCommit.put("expected_target_digest", collisionDigest);
            HeadlessExecutionException collision = assertThrows(
                    HeadlessExecutionException.class,
                    () -> execute(service, "commit_materialization", collisionCommit));
            assertEquals("materialization_provenance_collision", collision.code());
            assertEquals(collisionDigest,
                    io.github.hakjuoh.protege_mcp.core.release.ArtifactStore
                            .sha256(Files.readAllBytes(collisionFile)));

            collisionCommit.put("collision_mode", "merge");
            Map<String, Object> merged = execute(
                    service, "commit_materialization", collisionCommit);
            assertEquals(true, merged.get("committed"), merged::toString);
            assertFalse(collisionDigest.equals(merged.get("target_digest")));

            collisionCommit.put("expected_target_digest", merged.get("target_digest"));
            Map<String, Object> repeatedMerge = execute(
                    service, "commit_materialization", collisionCommit);
            assertEquals(false, repeatedMerge.get("committed"), repeatedMerge::toString);
            assertEquals("noop", repeatedMerge.get("status"));

            Map<String, Object> replaceRequest = new LinkedHashMap<>(request);
            replaceRequest.put("destination", Map.of("kind", "project_file",
                    "identifier", "artifacts/replace.ofn"));
            Map<String, Object> replacePreview = execute(
                    service, "materialize_inferences", replaceRequest);
            @SuppressWarnings("unchecked")
            Map<String, Object> replaceArtifact =
                    (Map<String, Object>) replacePreview.get("artifact");
            Path replaceFile = temp.resolve("artifacts/replace.ofn");
            Files.writeString(replaceFile, """
                    Ontology(<urn:test:replace>
                      Annotation(<http://www.w3.org/2000/01/rdf-schema#comment> "keep")
                      Declaration(Class(<https://example.org/ontology#Unrelated>))
                      SubClassOf(<https://example.org/ontology#A> <https://example.org/ontology#C>)
                    )
                    """);
            String replaceDigest = io.github.hakjuoh.protege_mcp.core.release.ArtifactStore
                    .sha256(Files.readAllBytes(replaceFile));
            Map<String, Object> replaced = execute(service, "commit_materialization", Map.of(
                    "artifact_id", replaceArtifact.get("artifact_id"),
                    "artifact_fingerprint", replaceArtifact.get("artifact_fingerprint"),
                    "confirm", true,
                    "overwrite", true,
                    "expected_target_digest", replaceDigest,
                    "collision_mode", "replace"));
            assertEquals(true, replaced.get("committed"), replaced::toString);
            var loadedManager = org.semanticweb.owlapi.apibinding.OWLManager
                    .createOWLOntologyManager();
            var loaded = loadedManager.loadOntologyFromOntologyDocument(replaceFile.toFile());
            assertEquals("urn:test:replace",
                    loaded.getOntologyID().getOntologyIRI().get().toString());
            assertTrue(loaded.containsAxiom(loadedManager.getOWLDataFactory()
                    .getOWLDeclarationAxiom(loadedManager.getOWLDataFactory().getOWLClass(
                            org.semanticweb.owlapi.model.IRI.create(
                                    "https://example.org/ontology#Unrelated")))));
            assertTrue(loaded.getAnnotations().stream().anyMatch(annotation ->
                    annotation.getValue().asLiteral().isPresent()
                            && "keep".equals(annotation.getValue().asLiteral().get()
                                    .getLiteral())));
            var expectedLogical = loadedManager.getOWLDataFactory().getOWLSubClassOfAxiom(
                    loadedManager.getOWLDataFactory().getOWLClass(
                            org.semanticweb.owlapi.model.IRI.create(
                                    "https://example.org/ontology#A")),
                    loadedManager.getOWLDataFactory().getOWLClass(
                            org.semanticweb.owlapi.model.IRI.create(
                                    "https://example.org/ontology#C")));
            List<org.semanticweb.owlapi.model.OWLAxiom> logicalForms = loaded.getAxioms().stream()
                    .filter(axiom -> axiom.equalsIgnoreAnnotations(expectedLogical)).toList();
            assertEquals(1, logicalForms.size());
            assertTrue(logicalForms.get(0).isAnnotated(), logicalForms::toString);

            Map<String, Object> linkRequest = new LinkedHashMap<>(request);
            linkRequest.put("destination", Map.of("kind", "project_file",
                    "identifier", "artifacts/link.ofn"));
            Map<String, Object> linkPreview = execute(
                    service, "materialize_inferences", linkRequest);
            @SuppressWarnings("unchecked")
            Map<String, Object> linkArtifact =
                    (Map<String, Object>) linkPreview.get("artifact");
            Path link = temp.resolve("artifacts/link.ofn");
            String sourceDigest = io.github.hakjuoh.protege_mcp.core.release.ArtifactStore
                    .sha256(Files.readAllBytes(temp.resolve("ontology.ttl")));
            try {
                Files.createSymbolicLink(link, Path.of("../ontology.ttl"));
            } catch (UnsupportedOperationException | IOException unsupported) {
                Assumptions.abort("symbolic links are unavailable: " + unsupported);
            }
            HeadlessExecutionException unsafeLink = assertThrows(
                    HeadlessExecutionException.class,
                    () -> execute(service, "commit_materialization", Map.of(
                            "artifact_id", linkArtifact.get("artifact_id"),
                            "artifact_fingerprint", linkArtifact.get("artifact_fingerprint"),
                            "confirm", true)));
            assertEquals("materialization_destination_invalid", unsafeLink.code());
            assertEquals(sourceDigest,
                    io.github.hakjuoh.protege_mcp.core.release.ArtifactStore
                            .sha256(Files.readAllBytes(temp.resolve("ontology.ttl"))));

            Map<String, Object> oversizedRequest = new LinkedHashMap<>(request);
            oversizedRequest.put("destination", Map.of("kind", "project_file",
                    "identifier", "artifacts/oversized.ofn"));
            Map<String, Object> oversizedPreview = execute(
                    service, "materialize_inferences", oversizedRequest);
            @SuppressWarnings("unchecked")
            Map<String, Object> oversizedArtifact =
                    (Map<String, Object>) oversizedPreview.get("artifact");
            Path oversizedTarget = temp.resolve("artifacts/oversized.ofn");
            Files.writeString(oversizedTarget, "x".repeat(1_048_577));
            HeadlessExecutionException oversized = assertThrows(
                    HeadlessExecutionException.class,
                    () -> execute(service, "commit_materialization", Map.of(
                            "artifact_id", oversizedArtifact.get("artifact_id"),
                            "artifact_fingerprint", oversizedArtifact.get(
                                    "artifact_fingerprint"),
                            "confirm", true)));
            assertEquals("materialization_bound_exceeded", oversized.code());
            assertEquals(1_048_577L, Files.size(oversizedTarget));

            Map<String, Object> changedRequest = new LinkedHashMap<>(request);
            changedRequest.put("destination", Map.of("kind", "project_file",
                    "identifier", "artifacts/stale.ofn"));
            Map<String, Object> changedPreview = execute(
                    service, "materialize_inferences", changedRequest);
            @SuppressWarnings("unchecked")
            Map<String, Object> changedArtifact =
                    (Map<String, Object>) changedPreview.get("artifact");
            Files.writeString(temp.resolve("ro-crate-metadata.json"), "\n",
                    java.nio.file.StandardOpenOption.APPEND);
            HeadlessExecutionException changed = assertThrows(
                    HeadlessExecutionException.class,
                    () -> execute(service, "commit_materialization", Map.of(
                            "artifact_id", changedArtifact.get("artifact_id"),
                            "artifact_fingerprint", changedArtifact.get("artifact_fingerprint"),
                            "confirm", true)));
            assertEquals("materialization_input_changed", changed.code());
            assertFalse(Files.exists(temp.resolve("artifacts/stale.ofn")));
            return null;
        });
    }

    private static Map<String, Object> execute(HeadlessToolService service, String tool,
            Map<String, Object> arguments) throws IOException {
        return service.execute(tool, arguments, HeadlessToolService.DEFAULT_CAPABILITIES,
                1_048_576, 4_194_304);
    }

    private static Path writeMappingProject(Path root) throws IOException {
        Files.createDirectories(root.resolve(".protege-mcp"));
        Files.writeString(root.resolve("ontology.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/ontology> a owl:Ontology .
                <https://example.org/ontology#A> a owl:Class .
                <https://example.org/ontology#B> a owl:Class .
                """);
        Files.writeString(root.resolve("ro-crate-metadata.json"), """
                {"@context":"https://w3id.org/ro/crate/1.1/context","@graph":[
                  {"@id":"ro-crate-metadata.json","@type":"CreativeWork","about":{"@id":"./"},"conformsTo":{"@id":"https://w3id.org/ro/crate/1.1"}},
                  {"@id":"./","@type":"Dataset","name":"Mapping test","description":"Governed SSSOM round-trip fixture.","datePublished":"2026-07-21","license":{"@id":"https://www.apache.org/licenses/LICENSE-2.0"},"identifier":"mapping-test","conformsTo":{"@id":"https://hakjuoh.github.io/protege-mcp/profiles/project-v1/"},"mainEntity":{"@id":"ontology.ttl"},"hasPart":{"@id":"ontology.ttl"}},
                  {"@id":"https://hakjuoh.github.io/protege-mcp/profiles/project-v1/","@type":"CreativeWork","name":"Ontology project profile"},
                  {"@id":"ontology.ttl","@type":"File","encodingFormat":"text/turtle","about":{"@id":"https://example.org/ontology"}},
                  {"@id":"https://example.org/ontology","@type":"Dataset","conformsTo":{"@id":"https://www.w3.org/TR/owl2-overview/"}}
                ]}
                """);
        Path policy = root.resolve("project.yaml");
        Files.writeString(policy, """
                version: 2
                project_id: mapping-test
                root_ontology: https://example.org/ontology
                project_root: .
                prefixes:
                  skos: http://www.w3.org/2004/02/skos/core#
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  additional_profiles: []
                  root_artifact: ontology.ttl
                  metadata:
                    path: ro-crate-metadata.json
                    format: ro-crate-1.1
                  canonicalization:
                    algorithm: RDFC-1.0
                    hash: SHA-256
                    scope: root-ontology
                    timeout_ms: 120000
                mappings:
                  path: .protege-mcp/mappings.sssom.tsv
                  allowed_predicates: [skos:exactMatch]
                  allowed_sources: []
                  allowed_licenses: [https://creativecommons.org/licenses/by/4.0/]
                  require_license: true
                  required_findings: []
                  directional_cycle_policy:
                    skos:broadMatch: error
                    skos:narrowMatch: error
                  many_to_one_rules: []
                validation:
                  required_stages: [structural]
                """);
        return policy;
    }

    private static HeadlessToolService service(Path temp) {
        return new HeadlessToolService(temp.resolve("missing.yaml"),
                new StructuralReasonerFactory(), Clock.systemUTC());
    }

    private static <T> T withHome(Path home, ThrowingSupplier<T> body) throws Exception {
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            return body.get();
        } finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
