package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.external.ExternalProviderGateway;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ProviderInspectRequest;
import io.github.hakjuoh.protege_mcp.external.ProviderResult;
import io.github.hakjuoh.protege_mcp.external.ProviderSearchRequest;
import io.github.hakjuoh.protege_mcp.external.ProviderSessionScope;
import io.github.hakjuoh.protege_mcp.external.ReuseOperation;
import io.github.hakjuoh.protege_mcp.external.ReuseProposal;
import io.github.hakjuoh.protege_mcp.external.ReuseProposalInputIdentity;
import io.github.hakjuoh.protege_mcp.external.ReuseProposalStore;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ReuseAcceptanceToolsTest {

    private static final String ONTOLOGY = "https://example.org/ontology";
    private static final String LOCAL = ONTOLOGY + "#Local";
    private static final String EXISTING = ONTOLOGY + "#Existing";
    private static final String EXTERNAL = "https://example.org/external";
    private static final String MAPPING_SET = "https://example.org/mappings";
    private static final String LICENSE = "https://creativecommons.org/licenses/by/4.0/";

    private Preferences preferences;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void writableServer() {
        preferences = McpConfig.prefs();
        savedReadOnly = preferences.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = preferences.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, false);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restoreServerPreferences() {
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
    }

    @Test
    void reuseReceiptRechecksFingerprintAndConsumesWithoutWriting(@TempDir Path temporary)
            throws Exception {
        Fixture fixture = fixture(temporary.resolve("reuse"), false);
        Issued issued = issue(fixture, new ReuseOperation.ReuseIri(EXTERNAL));
        Path mapping = fixture.project.resolve(".protege-mcp/mappings.sssom.tsv");

        CallToolResult unconfirmed = call(fixture.context, Map.of(
                "proposal_id", issued.id, "proposal_fingerprint", issued.fingerprint));
        assertEquals(Boolean.TRUE, unconfirmed.isError());
        assertEquals("confirmation_required", structured(unconfirmed).get("code"));

        CallToolResult mismatch = call(fixture.context, arguments(
                issued.id, "sha256:" + "0".repeat(64)));
        assertEquals(Boolean.TRUE, mismatch.isError());
        assertEquals("proposal_fingerprint_mismatch", structured(mismatch).get("code"));

        CallToolResult accepted = call(fixture.context,
                arguments(issued.id, issued.fingerprint));
        Map<String, Object> body = structured(accepted);
        assertFalse(Boolean.TRUE.equals(accepted.isError()), body::toString);
        assertEquals("accepted", body.get("status"));
        assertEquals("reuse_iri", body.get("action"));
        assertEquals(false, body.get("committed"));
        assertEquals(false, body.get("interactive_confirmation"));
        assertFalse(Files.exists(mapping));
        assertFalse(fixture.ontology.containsEntityInSignature(IRI.create(EXTERNAL)));

        CallToolResult consumed = call(fixture.context,
                arguments(issued.id, issued.fingerprint));
        assertEquals(Boolean.TRUE, consumed.isError());
        assertEquals("proposal_invalid", structured(consumed).get("code"));
    }

    @Test
    void addMappingUsesOriginalCasAndRejectsModelDrift(@TempDir Path temporary)
            throws Exception {
        Fixture success = fixture(temporary.resolve("add-success"), false);
        Issued acceptedProposal = issue(success,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        CallToolResult accepted = call(success.context,
                mappingArguments(acceptedProposal));
        Map<String, Object> body = structured(accepted);
        assertFalse(Boolean.TRUE.equals(accepted.isError()), body::toString);
        assertEquals("accepted", body.get("status"));
        assertEquals("add_mapping", body.get("action"));
        assertTrue(Files.isRegularFile(success.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));

        Fixture stale = fixture(temporary.resolve("add-stale"), false);
        Issued staleProposal = issue(stale,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        OWLDataFactory data = stale.manager.getOWLDataFactory();
        stale.manager.addAxiom(stale.ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(ONTOLOGY + "#Late"))));

        CallToolResult refused = call(stale.context, mappingArguments(staleProposal));
        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("proposal_input_changed", structured(refused).get("code"));
        assertFalse(Files.exists(stale.project.resolve(".protege-mcp/mappings.sssom.tsv")));

        Fixture mappingStale = fixture(temporary.resolve("mapping-stale"), false);
        Issued mappingStaleProposal = issue(mappingStale,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("expected_mapping_revision", mappingStaleProposal.mappingRevision);
        seed.put("mapping", mapping(EXISTING, ONTOLOGY + "#Other"));
        seed.put("mapping_set_id", MAPPING_SET);
        seed.put("license", LICENSE);
        seed.put("confirm", true);
        CallToolResult seeded = callMapping(mappingStale.context, seed);
        assertFalse(Boolean.TRUE.equals(seeded.isError()), seeded::toString);

        CallToolResult casRefused = call(mappingStale.context,
                mappingArguments(mappingStaleProposal));
        assertEquals(Boolean.TRUE, casRefused.isError());
        assertEquals("proposal_input_changed", structured(casRefused).get("code"));
    }

    @Test
    void mappingSetupAndReadOnlyGatesRunBeforeMint(@TempDir Path temporary) throws Exception {
        Fixture fixture = fixture(temporary.resolve("mint-gates"), false);
        Issued issued = issue(fixture, mintOperation());

        CallToolResult setupMissing = call(fixture.context,
                arguments(issued.id, issued.fingerprint));
        assertEquals(Boolean.TRUE, setupMissing.isError());
        assertEquals("mapping_store_setup_required", structured(setupMissing).get("code"));
        assertFalse(fixture.ontology.containsEntityInSignature(IRI.create(LOCAL)));

        preferences.putBoolean(McpConfig.KEY_READ_ONLY, true);
        CallToolResult readOnly = call(fixture.context, mappingArguments(issued));
        assertEquals(Boolean.TRUE, readOnly.isError());
        assertEquals("read_only", structured(readOnly).get("code"));
        assertFalse(fixture.ontology.containsEntityInSignature(IRI.create(LOCAL)));
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, false);
    }

    @Test
    void partialOntologyApplyIsRevertedAndRequiresAFreshProposal(@TempDir Path temporary)
            throws Exception {
        Path project = temporary.resolve("partial-apply");
        Files.createDirectories(project);
        writePolicy(project, false);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXISTING))));
        manager.setOntologyDocumentIRI(ontology,
                IRI.create(project.resolve("ontology.ttl").toUri()));
        OWLModelManager base = FakeModelManager.over(ontology);
        AtomicBoolean failFirstApply = new AtomicBoolean(true);
        OWLModelManager partial = (OWLModelManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if ("applyChanges".equals(method.getName())
                            && failFirstApply.compareAndSet(true, false)) {
                        @SuppressWarnings("unchecked")
                        List<org.semanticweb.owlapi.model.OWLOntologyChange> changes =
                                (List<org.semanticweb.owlapi.model.OWLOntologyChange>) args[0];
                        manager.applyChange(changes.get(0));
                        throw new IllegalStateException("simulated partial model broadcast");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        OntologyAccess access = HeadlessAccess.over(partial);
        Fixture fixture = new Fixture(project, manager, ontology,
                new ToolContext(access, new McpServerController(access), null,
                        new NoopGateway()));
        Issued first = issue(fixture, mintOperation());

        CallToolResult reverted = call(fixture.context, mappingArguments(first));

        assertEquals(Boolean.TRUE, reverted.isError());
        assertEquals("mint_commit_reverted", structured(reverted).get("code"));
        assertFalse(ontology.containsEntityInSignature(IRI.create(LOCAL)));
        assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));

        CallToolResult invalidated = call(fixture.context, mappingArguments(first));
        assertEquals(Boolean.TRUE, invalidated.isError());
        assertEquals("proposal_invalid", structured(invalidated).get("code"));

        Issued fresh = issue(fixture, mintOperation());
        CallToolResult accepted = call(fixture.context, mappingArguments(fresh));
        assertFalse(Boolean.TRUE.equals(accepted.isError()), accepted::toString);
        assertTrue(ontology.containsEntityInSignature(IRI.create(LOCAL)));
    }

    @Test
    void completeApplyIsRevertedWhenReceiptRenewalFails(@TempDir Path temporary)
            throws Exception {
        RegressingClock clock = new RegressingClock(6);
        ReuseProposalStore proposals = new ReuseProposalStore(clock, Duration.ofMinutes(1),
                8, 16, 256 * 1_024);
        Fixture fixture = fixture(temporary.resolve("receipt-renewal-failure"),
                false, null, proposals);
        Issued issued = issue(fixture, mintOperation());

        CallToolResult reverted = call(fixture.context, mappingArguments(issued));

        assertEquals(Boolean.TRUE, reverted.isError());
        assertEquals("mint_commit_reverted", structured(reverted).get("code"));
        assertEquals(true, cast(structured(reverted).get("details"))
                .get("new_proposal_required"));
        assertFalse(fixture.ontology.containsEntityInSignature(IRI.create(LOCAL)));
        assertFalse(Files.exists(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));
        CallToolResult invalidated = call(fixture.context, mappingArguments(issued));
        assertEquals(Boolean.TRUE, invalidated.isError());
        assertEquals("proposal_invalid", structured(invalidated).get("code"));
    }

    @Test
    void unverifiableRollbackRequiresManualCleanupAndInvalidatesProposal(
            @TempDir Path temporary) throws Exception {
        String previousHome = System.getProperty("user.home");
        Path auditHome = temporary.resolve("audit-home");
        System.setProperty("user.home", auditHome.toString());
        try {
            Path project = temporary.resolve("rollback-unverified");
            Files.createDirectories(project);
            writePolicy(project, false);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
            OWLDataFactory data = manager.getOWLDataFactory();
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                    data.getOWLClass(IRI.create(EXISTING))));
            manager.setOntologyDocumentIRI(ontology,
                    IRI.create(project.resolve("ontology.ttl").toUri()));
            OWLModelManager base = FakeModelManager.over(ontology);
            IRI sideEffect = IRI.create(ONTOLOGY + "#ListenerSideEffect");
            AtomicBoolean failFirstApply = new AtomicBoolean(true);
            OWLModelManager partial = (OWLModelManager) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {OWLModelManager.class},
                    (proxy, method, args) -> {
                        if ("applyChanges".equals(method.getName())
                                && failFirstApply.compareAndSet(true, false)) {
                            @SuppressWarnings("unchecked")
                            List<org.semanticweb.owlapi.model.OWLOntologyChange> changes =
                                    (List<org.semanticweb.owlapi.model.OWLOntologyChange>) args[0];
                            manager.applyChange(changes.get(0));
                            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                                    data.getOWLClass(sideEffect)));
                            throw new IllegalStateException("simulated listener side effect");
                        }
                        try {
                            return method.invoke(base, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            OntologyAccess access = HeadlessAccess.over(partial);
            Fixture fixture = new Fixture(project, manager, ontology,
                    new ToolContext(access, new McpServerController(access), null,
                            new NoopGateway()));
            Issued issued = issue(fixture, mintOperation());

            CallToolResult failed = call(fixture.context, mappingArguments(issued), true);

            Map<String, Object> body = structured(failed);
            assertEquals(Boolean.TRUE, failed.isError());
            assertEquals("mint_commit_incomplete", body.get("code"));
            Map<String, Object> details = cast(body.get("details"));
            assertEquals(true, details.get("manual_cleanup_required"));
            assertEquals(true, details.get("new_proposal_required"));
            assertEquals(true, details.get("outcome_unknown"));
            assertFalse(details.containsKey("effects_prevented"));
            assertFalse(ontology.containsEntityInSignature(IRI.create(LOCAL)));
            assertTrue(ontology.containsEntityInSignature(sideEffect));
            assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));

            CallToolResult invalidated = call(fixture.context, mappingArguments(issued));
            assertEquals(Boolean.TRUE, invalidated.isError());
            assertEquals("proposal_invalid", structured(invalidated).get("code"));

            Path auditRoot = auditHome.resolve(".protege-mcp/audit");
            List<String> events;
            try (var paths = Files.walk(auditRoot)) {
                events = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .flatMap(path -> {
                            try {
                                return Files.readAllLines(path).stream();
                            } catch (java.io.IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        }).toList();
            }
            assertTrue(events.stream().anyMatch(event -> event.contains(
                    "\"operation\":\"accept_reuse_proposal\"")
                    && event.contains("\"outcome\":\"failed\"")
                    && event.contains("\"outcome_unknown\":true")
                    && event.contains("proposal_fingerprint:" + issued.fingerprint)));
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void completeIntendedApplyWithListenerDeltaCannotPublishReceipt(@TempDir Path temporary)
            throws Exception {
        Path project = temporary.resolve("complete-with-side-effect");
        Files.createDirectories(project);
        writePolicy(project, false);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXISTING))));
        manager.setOntologyDocumentIRI(ontology,
                IRI.create(project.resolve("ontology.ttl").toUri()));
        OWLModelManager base = FakeModelManager.over(ontology);
        IRI sideEffect = IRI.create(ONTOLOGY + "#CompleteListenerSideEffect");
        AtomicBoolean failFirstApply = new AtomicBoolean(true);
        OWLModelManager decorated = (OWLModelManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if ("applyChanges".equals(method.getName())
                            && failFirstApply.compareAndSet(true, false)) {
                        @SuppressWarnings("unchecked")
                        List<org.semanticweb.owlapi.model.OWLOntologyChange> changes =
                                (List<org.semanticweb.owlapi.model.OWLOntologyChange>) args[0];
                        manager.applyChanges(changes);
                        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                                data.getOWLClass(sideEffect)));
                        throw new IllegalStateException("simulated complete apply listener failure");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        OntologyAccess access = HeadlessAccess.over(decorated);
        Fixture fixture = new Fixture(project, manager, ontology,
                new ToolContext(access, new McpServerController(access), null,
                        new NoopGateway()));
        Issued issued = issue(fixture, mintOperation());

        CallToolResult failed = call(fixture.context, mappingArguments(issued));

        assertEquals(Boolean.TRUE, failed.isError());
        assertEquals("mint_commit_incomplete", structured(failed).get("code"));
        assertFalse(ontology.containsEntityInSignature(IRI.create(LOCAL)));
        assertTrue(ontology.containsEntityInSignature(sideEffect));
        assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void documentOnlyRollbackMismatchRequiresManualCleanup(@TempDir Path temporary)
            throws Exception {
        Path project = temporary.resolve("document-rollback-mismatch");
        Files.createDirectories(project);
        writePolicy(project, false);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXISTING))));
        Path originalDocument = project.resolve("ontology.ttl");
        IRI changedDocument = IRI.create(project.resolve("moved.ttl").toUri());
        manager.setOntologyDocumentIRI(ontology, IRI.create(originalDocument.toUri()));
        OWLModelManager base = FakeModelManager.over(ontology);
        AtomicBoolean failFirstApply = new AtomicBoolean(true);
        OWLModelManager decorated = (OWLModelManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if ("applyChanges".equals(method.getName())
                            && failFirstApply.compareAndSet(true, false)) {
                        @SuppressWarnings("unchecked")
                        List<org.semanticweb.owlapi.model.OWLOntologyChange> changes =
                                (List<org.semanticweb.owlapi.model.OWLOntologyChange>) args[0];
                        manager.applyChange(changes.get(0));
                        manager.setOntologyDocumentIRI(ontology, changedDocument);
                        throw new IllegalStateException("simulated document listener side effect");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        OntologyAccess access = HeadlessAccess.over(decorated);
        Fixture fixture = new Fixture(project, manager, ontology,
                new ToolContext(access, new McpServerController(access), null,
                        new NoopGateway()));
        Issued issued = issue(fixture, mintOperation());

        CallToolResult failed = call(fixture.context, mappingArguments(issued));

        assertEquals(Boolean.TRUE, failed.isError());
        assertEquals("mint_commit_incomplete", structured(failed).get("code"));
        assertEquals(true, cast(structured(failed).get("details"))
                .get("manual_cleanup_required"));
        assertFalse(ontology.containsEntityInSignature(IRI.create(LOCAL)));
        assertEquals(changedDocument, manager.getOntologyDocumentIRI(ontology));
        assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void mintAndMappingCommitAsOneVisibleSaga(@TempDir Path temporary) throws Exception {
        Fixture fixture = fixture(temporary.resolve("mint-success"), false);
        Issued issued = issue(fixture, mintOperation());

        CallToolResult accepted = call(fixture.context, mappingArguments(issued));
        Map<String, Object> body = structured(accepted);

        assertFalse(Boolean.TRUE.equals(accepted.isError()), body::toString);
        assertEquals("accepted", body.get("status"));
        assertEquals("mint_local_with_mapping", body.get("action"));
        assertEquals(true, body.get("committed"));
        assertTrue(body.containsKey("mint_receipt"));
        assertTrue(body.containsKey("mapping"));
        assertTrue(fixture.ontology.containsEntityInSignature(IRI.create(LOCAL)));
        assertTrue(Files.isRegularFile(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void partialMintPersistsReceiptAndResumesAfterEntityIndexRecovery(
            @TempDir Path temporary) throws Exception {
        Fixture fixture = fixture(temporary.resolve("mint-partial"), true);
        Issued issued = issue(fixture, mintOperation());

        CallToolResult first = call(fixture.context, mappingArguments(issued));
        Map<String, Object> partial = structured(first);
        assertFalse(Boolean.TRUE.equals(first.isError()), partial::toString);
        assertEquals("partial", partial.get("status"));
        assertEquals(true, partial.get("committed"));
        assertTrue(partial.containsKey("mint_receipt"));
        assertTrue(partial.containsKey("continuation"));
        assertEquals("mapping_validation_failed",
                cast(partial.get("mapping_error")).get("code"));
        assertTrue(fixture.ontology.containsEntityInSignature(IRI.create(LOCAL)));
        assertFalse(Files.exists(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));

        Map<String, Object> changedContinuation = new LinkedHashMap<>(mappingArguments(issued));
        changedContinuation.put("license", "https://spdx.org/licenses/CC0-1.0");
        CallToolResult changed = call(fixture.context, changedContinuation);
        assertEquals("partial", structured(changed).get("status"));
        assertEquals("continuation_input_changed",
                cast(structured(changed).get("mapping_error")).get("code"));
        assertFalse(Files.exists(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));

        OWLDataFactory data = fixture.manager.getOWLDataFactory();
        fixture.manager.addAxiom(fixture.ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXTERNAL))));
        CallToolResult resumed = call(fixture.context, mappingArguments(issued));
        Map<String, Object> completed = structured(resumed);
        assertFalse(Boolean.TRUE.equals(resumed.isError()), completed::toString);
        assertEquals("accepted", completed.get("status"));
        assertEquals("mint_local_with_mapping", completed.get("action"));
        assertTrue(Files.isRegularFile(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));

        CallToolResult consumed = call(fixture.context, mappingArguments(issued));
        assertEquals(Boolean.TRUE, consumed.isError());
        assertEquals("proposal_invalid", structured(consumed).get("code"));
    }

    @Test
    void resumePinsVerifiedMintAxiomsThroughMappingCas(@TempDir Path temporary)
            throws Exception {
        Path project = temporary.resolve("resume-drift");
        Files.createDirectories(project);
        writePolicy(project, true);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXISTING))));
        manager.setOntologyDocumentIRI(ontology,
                IRI.create(project.resolve("ontology.ttl").toUri()));
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger armedDispatches = new AtomicInteger();
        var label = data.getOWLAnnotationAssertionAxiom(data.getRDFSLabel(), IRI.create(LOCAL),
                data.getOWLLiteral("Local term", "en"));
        OntologyAccess access = HeadlessAccess.overHookedDispatches(
                FakeModelManager.over(ontology), () -> {
                    if (armed.get() && armedDispatches.incrementAndGet() == 5) {
                        manager.removeAxiom(ontology, label);
                    }
                }, 1, new AtomicInteger());
        McpServerController controller = new McpServerController(access);
        Fixture fixture = new Fixture(project, manager, ontology,
                new ToolContext(access, controller, null, new NoopGateway()));
        Issued issued = issue(fixture, mintOperation());
        assertEquals("partial", structured(call(fixture.context,
                mappingArguments(issued))).get("status"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXTERNAL))));
        armed.set(true);

        Map<String, Object> resumed = structured(call(fixture.context,
                mappingArguments(issued)));

        assertEquals("partial", resumed.get("status"));
        assertEquals("proposal_input_changed",
                cast(resumed.get("mapping_error")).get("code"));
        assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void everyMintedEntityTypeCommitsItsDeclaration(@TempDir Path temporary) throws Exception {
        int index = 0;
        for (ReuseOperation.MintedEntityType type
                : ReuseOperation.MintedEntityType.values()) {
            Fixture fixture = fixture(temporary.resolve("entity-" + index), false);
            String local = ONTOLOGY + "#Local" + index;
            ReuseOperation operation = new ReuseOperation.MintLocalWithMapping(local, type,
                    List.of(new ProviderResult.LocalizedText("Local term " + index, "en")),
                    mapping(local, EXTERNAL));
            Issued issued = issue(fixture, operation);

            CallToolResult accepted = call(fixture.context, mappingArguments(issued));

            assertFalse(Boolean.TRUE.equals(accepted.isError()),
                    () -> structured(accepted).toString());
            assertEquals("accepted", structured(accepted).get("status"));
            OWLEntity entity = entity(fixture.manager.getOWLDataFactory(), type, local);
            assertTrue(fixture.ontology.containsAxiom(
                    fixture.manager.getOWLDataFactory().getOWLDeclarationAxiom(entity)));
            index++;
        }
    }

    private static OWLEntity entity(OWLDataFactory data,
            ReuseOperation.MintedEntityType type, String iri) {
        IRI value = IRI.create(iri);
        return switch (type) {
            case CLASS -> data.getOWLClass(value);
            case OBJECT_PROPERTY -> data.getOWLObjectProperty(value);
            case DATA_PROPERTY -> data.getOWLDataProperty(value);
            case ANNOTATION_PROPERTY -> data.getOWLAnnotationProperty(value);
            case NAMED_INDIVIDUAL -> data.getOWLNamedIndividual(value);
            case DATATYPE -> data.getOWLDatatype(value);
        };
    }

    @Test
    void existingSidecarNeedsNoSetupMetadata(@TempDir Path temporary) throws Exception {
        Fixture fixture = fixture(temporary.resolve("existing-sidecar"), false);
        String seed = ONTOLOGY + "#Seed";
        OWLDataFactory data = fixture.manager.getOWLDataFactory();
        fixture.manager.addAxiom(fixture.ontology,
                data.getOWLDeclarationAxiom(data.getOWLClass(IRI.create(seed))));
        MappingTools.ProposalState empty = MappingTools.proposalState(
                fixture.context, ToolTestExchange.localAdmin(), Map.of());
        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("expected_mapping_revision", empty.mappingRevision());
        setup.put("mapping", mapping(seed, ONTOLOGY + "#SeedExternal"));
        setup.put("mapping_set_id", MAPPING_SET);
        setup.put("license", LICENSE);
        setup.put("confirm", true);
        assertFalse(Boolean.TRUE.equals(callMapping(fixture.context, setup).isError()));

        Issued issued = issue(fixture,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        CallToolResult accepted = call(fixture.context,
                arguments(issued.id, issued.fingerprint));

        assertFalse(Boolean.TRUE.equals(accepted.isError()),
                () -> structured(accepted).toString());
        assertEquals("accepted", structured(accepted).get("status"));
    }

    @Test
    void expiryAfterDurableMappingCasDoesNotTurnSuccessIntoARetry(@TempDir Path temporary)
            throws Exception {
        StepClock clock = new StepClock(4);
        ReuseProposalStore proposals = new ReuseProposalStore(clock, Duration.ofSeconds(1),
                8, 16, 256 * 1_024);
        Fixture fixture = fixture(temporary.resolve("cas-expiry"), false, null, proposals);
        Issued issued = issue(fixture,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));

        CallToolResult accepted = call(fixture.context, mappingArguments(issued));

        assertFalse(Boolean.TRUE.equals(accepted.isError()), accepted::toString);
        assertEquals("accepted", structured(accepted).get("status"));
        assertTrue(Files.isRegularFile(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));
        CallToolResult consumed = call(fixture.context, mappingArguments(issued));
        assertEquals(Boolean.TRUE, consumed.isError());
        assertEquals("proposal_invalid", structured(consumed).get("code"));
    }

    @Test
    void identicalPolicyAtAnotherSourceCannotRetargetAcceptance(@TempDir Path temporary)
            throws Exception {
        Fixture fixture = fixture(temporary.resolve("policy-source"), false);
        Issued issued = issue(fixture,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        Path alternate = fixture.project.resolve(".protege-mcp/alternate-policy.yaml");
        ProjectPolicyFixtures.writePolicy(alternate, policyText(false));
        Map<String, Object> changed = new LinkedHashMap<>(mappingArguments(issued));
        changed.put("policy_path", alternate.toString());

        CallToolResult refused = call(fixture.context, changed);

        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("proposal_input_changed", structured(refused).get("code"));
        assertFalse(Files.exists(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void samePolicySourceContentDriftPreventsAcceptance(@TempDir Path temporary)
            throws Exception {
        Fixture fixture = fixture(temporary.resolve("policy-content"), false);
        Issued issued = issue(fixture,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        Path policy = fixture.project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policy, policyText(false)
                .replace("allowed_sources: []", "allowed_sources: [efo]"));

        CallToolResult refused = call(fixture.context, mappingArguments(issued));

        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("proposal_input_changed", structured(refused).get("code"));
        assertFalse(Files.exists(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void policySourceSwapWhileMintWaitsIsRejectedInsideModelBoundary(@TempDir Path temporary)
            throws Exception {
        Path firstProject = temporary.resolve("mint-source-first");
        Path secondProject = temporary.resolve("mint-source-second");
        Files.createDirectories(firstProject);
        Files.createDirectories(secondProject);
        writePolicy(firstProject, false);
        writePolicy(secondProject, false);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXISTING))));
        manager.setOntologyDocumentIRI(ontology,
                IRI.create(firstProject.resolve("ontology.ttl").toUri()));
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger armedDispatches = new AtomicInteger();
        OntologyAccess access = HeadlessAccess.overHookedDispatches(
                FakeModelManager.over(ontology), () -> {
                    if (armed.get() && armedDispatches.incrementAndGet() == 7) {
                        manager.setOntologyDocumentIRI(ontology,
                                IRI.create(secondProject.resolve("ontology.ttl").toUri()));
                    }
                }, 1, new AtomicInteger());
        Fixture fixture = new Fixture(firstProject, manager, ontology,
                new ToolContext(access, new McpServerController(access), null,
                        new NoopGateway()));
        Issued issued = issue(fixture, mintOperation());
        armed.set(true);

        CallToolResult refused = call(fixture.context, mappingArguments(issued));

        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("proposal_input_changed", structured(refused).get("code"));
        assertTrue(armedDispatches.get() >= 7);
        assertFalse(ontology.containsEntityInSignature(IRI.create(LOCAL)));
    }

    @Test
    void mappingTargetSymlinkDriftCannotRedirectAcceptance(@TempDir Path temporary)
            throws Exception {
        Fixture fixture = fixture(temporary.resolve("mapping-target"), false);
        Issued issued = issue(fixture,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        Path outside = temporary.resolve("outside.tsv");
        Files.writeString(outside, "sentinel");
        Path target = fixture.project.resolve(".protege-mcp/mappings.sssom.tsv");
        try {
            Files.createSymbolicLink(target, outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symbolic links are unavailable for this test");
        }

        CallToolResult refused = call(fixture.context, mappingArguments(issued));

        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("sentinel", Files.readString(outside));
    }

    @Test
    void interactiveConfirmationCanApproveOrDeclineAcceptance(@TempDir Path temporary)
            throws Exception {
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, true);
        AtomicInteger approvals = new AtomicInteger();
        AtomicReference<String> approvalSummary = new AtomicReference<>();
        Fixture approved = fixture(temporary.resolve("confirmation-approved"), false,
                summary -> {
                    approvals.incrementAndGet();
                    approvalSummary.set(summary);
                    return true;
                });
        Issued approvedProposal = issue(approved,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        CallToolResult accepted = call(approved.context, mappingArguments(approvedProposal));
        assertFalse(Boolean.TRUE.equals(accepted.isError()), accepted::toString);
        assertEquals(1, approvals.get());
        assertEquals(true, structured(accepted).get("interactive_confirmation"));
        assertTrue(approvalSummary.get().contains(approvedProposal.fingerprint));
        assertFalse(approvalSummary.get().contains(approvedProposal.id));

        Fixture declined = fixture(temporary.resolve("confirmation-declined"), false,
                summary -> false);
        Issued declinedProposal = issue(declined,
                new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
        CallToolResult refused = call(declined.context, mappingArguments(declinedProposal));
        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("write_declined", structured(refused).get("code"));
        assertFalse(Files.exists(declined.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void manualRecoveryArgumentsCompleteAPartialMint(@TempDir Path temporary)
            throws Exception {
        Fixture fixture = fixture(temporary.resolve("manual-recovery"), true);
        Issued issued = issue(fixture, mintOperation());
        Map<String, Object> partial = structured(call(fixture.context,
                mappingArguments(issued)));
        Map<String, Object> continuation = cast(partial.get("continuation"));
        Map<String, Object> manual = cast(continuation.get("manual_recovery"));
        Map<String, Object> manualArguments = cast(manual.get("arguments"));
        OWLDataFactory data = fixture.manager.getOWLDataFactory();
        fixture.manager.addAxiom(fixture.ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXTERNAL))));

        CallToolResult recovered = callMapping(fixture.context, manualArguments);

        assertFalse(Boolean.TRUE.equals(recovered.isError()), recovered::toString);
        assertTrue(Files.isRegularFile(fixture.project.resolve(
                ".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void auditDistinguishesReusePartialAndPreventedAcceptance(@TempDir Path temporary)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        Path auditHome = temporary.resolve("audit-home");
        System.setProperty("user.home", auditHome.toString());
        try {
            Fixture reuse = fixture(temporary.resolve("audit-reuse"), false);
            Issued reuseProposal = issue(reuse, new ReuseOperation.ReuseIri(EXTERNAL));
            CallToolResult reused = call(reuse.context,
                    arguments(reuseProposal.id, reuseProposal.fingerprint), true);
            assertFalse(Boolean.TRUE.equals(reused.isError()), reused::toString);

            Fixture partial = fixture(temporary.resolve("audit-partial"), true);
            Issued partialProposal = issue(partial, mintOperation());
            CallToolResult partialResult = call(partial.context,
                    mappingArguments(partialProposal), true);
            assertEquals("partial", structured(partialResult).get("status"));

            Fixture prevented = fixture(temporary.resolve("audit-prevented"), false);
            Issued preventedProposal = issue(prevented,
                    new ReuseOperation.AddMapping(mapping(EXISTING, EXTERNAL)));
            preferences.putBoolean(McpConfig.KEY_READ_ONLY, true);
            CallToolResult refused = call(prevented.context,
                    mappingArguments(preventedProposal), true);
            preferences.putBoolean(McpConfig.KEY_READ_ONLY, false);
            assertEquals(Boolean.TRUE, refused.isError());

            Path auditRoot = auditHome.resolve(".protege-mcp/audit");
            List<Path> streams;
            try (var paths = Files.walk(auditRoot)) {
                streams = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .toList();
            }
            assertEquals(3, streams.size());
            List<String> events = streams.stream().flatMap(path -> {
                try {
                    return Files.readAllLines(path).stream();
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }).toList();
            assertTrue(events.stream().anyMatch(event -> event.contains(
                    "\"operation\":\"accept_reuse_proposal\"")
                    && event.contains("\"outcome\":\"succeeded\"")
                    && event.contains("\"committed\":false")));
            assertTrue(events.stream().anyMatch(event -> event.contains(
                    "\"outcome\":\"succeeded\"")
                    && event.contains("\"committed\":true")));
            assertTrue(events.stream().anyMatch(event -> event.contains(
                    "\"outcome\":\"failed\"")
                    && event.contains("\"committed\":false")));
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static Issued issue(Fixture fixture, ReuseOperation operation) throws Exception {
        MappingTools.ProposalState state = MappingTools.proposalState(
                fixture.context, ToolTestExchange.localAdmin(), Map.of());
        String evidenceType = operation instanceof ReuseOperation.MintLocalWithMapping mint
                ? mint.entityType().wire() : "class";
        ProviderResult evidence = ProviderResult.create("fake", "fake", "efo",
                "https://example.org/efo.owl", EXTERNAL, evidenceType,
                List.of(new ProviderResult.LocalizedText("External term", "en")),
                List.of(), List.of("Description"), "CC BY 4.0", "fixture",
                "direct match", 1.0, "1", Instant.parse("2026-07-21T00:00:00Z"),
                URI.create("https://example.org/term"), 0, false, null);
        ReuseProposal proposal = ReuseProposal.create(evidence,
                ReuseProposalInputIdentity.create(evidence, "en", state.modelRevision(),
                        state.mappingRevision(), state.policy().digest(),
                        state.targetIdentity()), operation);
        ProviderSessionScope scope = ExternalTermTools.scope(
                fixture.context, ToolTestExchange.localAdmin());
        return new Issued(fixture.context.reuseProposals().issue(scope, proposal),
                proposal.proposalFingerprint(), state.mappingRevision());
    }

    private static ReuseOperation mintOperation() {
        return new ReuseOperation.MintLocalWithMapping(LOCAL,
                ReuseOperation.MintedEntityType.CLASS,
                List.of(new ProviderResult.LocalizedText("Local term", "en")),
                mapping(LOCAL, EXTERNAL));
    }

    private static Map<String, String> mapping(String subject, String object) {
        return Map.of("subject_id", subject, "predicate_id", "skos:exactMatch",
                "object_id", object,
                "mapping_justification", "semapv:ManualMappingCuration");
    }

    private static Map<String, Object> arguments(String id, String fingerprint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposal_id", id);
        result.put("proposal_fingerprint", fingerprint);
        result.put("confirm", true);
        return result;
    }

    private static Map<String, Object> mappingArguments(Issued issued) {
        Map<String, Object> result = new LinkedHashMap<>(
                arguments(issued.id, issued.fingerprint));
        result.put("mapping_set_id", MAPPING_SET);
        result.put("license", LICENSE);
        return result;
    }

    private static CallToolResult call(ToolContext context, Map<String, Object> arguments) {
        return call(context, arguments, false);
    }

    private static CallToolResult call(ToolContext context, Map<String, Object> arguments,
            boolean audited) {
        ToolRegistry registry = audited ? new ToolRegistry(context.audit()) : new ToolRegistry();
        ExternalTermTools.register(registry, context);
        for (SyncToolSpecification specification : registry.build()) {
            if ("accept_reuse_proposal".equals(specification.tool().name())) {
                return specification.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest("accept_reuse_proposal", arguments));
            }
        }
        throw new AssertionError("accept_reuse_proposal is not registered");
    }

    private static CallToolResult callMapping(ToolContext context,
            Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        MappingTools.register(registry, context);
        for (SyncToolSpecification specification : registry.build()) {
            if ("add_mapping".equals(specification.tool().name())) {
                return specification.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest("add_mapping", arguments));
            }
        }
        throw new AssertionError("add_mapping is not registered");
    }

    private static Fixture fixture(Path project, boolean requireExternalEntity) throws Exception {
        return fixture(project, requireExternalEntity, null);
    }

    private static Fixture fixture(Path project, boolean requireExternalEntity,
            WriteConfirmer confirmer) throws Exception {
        return fixture(project, requireExternalEntity, confirmer, null);
    }

    private static Fixture fixture(Path project, boolean requireExternalEntity,
            WriteConfirmer confirmer, ReuseProposalStore proposals) throws Exception {
        Files.createDirectories(project);
        writePolicy(project, requireExternalEntity);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(EXISTING))));
        manager.setOntologyDocumentIRI(ontology, IRI.create(project.resolve("ontology.ttl").toUri()));
        OntologyAccess access = HeadlessAccess.over(FakeModelManager.over(ontology));
        McpServerController controller = new McpServerController(access);
        ToolContext context = proposals == null
                ? new ToolContext(access, controller, confirmer, new NoopGateway())
                : new ToolContext(access, controller, confirmer, new NoopGateway(), proposals);
        return new Fixture(project, manager, ontology, context);
    }

    private static void writePolicy(Path project, boolean requireExternalEntity) throws Exception {
        ProjectPolicyFixtures.writePolicy(project.resolve(".protege-mcp/project.yaml"),
                policyText(requireExternalEntity));
    }

    private static String policyText(boolean requireExternalEntity) {
        String required = requireExternalEntity ? "[missing_target]" : "[]";
        return ProjectPolicyFixtures.minimalPolicy("reuse-accept", ONTOLOGY)
                .replace("version: 1", "version: 2")
                + "prefixes:\n  skos: http://www.w3.org/2004/02/skos/core#\n"
                + "mappings:\n"
                + "  path: .protege-mcp/mappings.sssom.tsv\n"
                + "  allowed_predicates: [skos:exactMatch]\n"
                + "  allowed_sources: []\n"
                + "  allowed_licenses: [" + LICENSE + "]\n"
                + "  require_license: true\n"
                + "  required_findings: " + required + "\n"
                + "  directional_cycle_policy:\n"
                + "    skos:broadMatch: error\n"
                + "    skos:narrowMatch: error\n"
                + "  many_to_one_rules: []\n"
                + "validation:\n  required_stages: [structural]\n";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private record Issued(String id, String fingerprint, String mappingRevision) { }

    private record Fixture(Path project, OWLOntologyManager manager,
            OWLOntology ontology, ToolContext context) { }

    private static final class StepClock extends Clock {
        private final AtomicInteger reads = new AtomicInteger();
        private final int stableReads;

        private StepClock(int stableReads) {
            this.stableReads = stableReads;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC required");
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.parse("2026-07-22T00:00:00Z")
                    .plusSeconds(reads.incrementAndGet() <= stableReads ? 0 : 2);
        }
    }

    private static final class RegressingClock extends Clock {
        private final AtomicInteger reads = new AtomicInteger();
        private final int stableReads;

        private RegressingClock(int stableReads) {
            this.stableReads = stableReads;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC required");
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.parse("2026-07-22T00:00:00Z")
                    .minusSeconds(reads.incrementAndGet() <= stableReads ? 0 : 1);
        }
    }

    private static final class NoopGateway implements ExternalProviderGateway {
        @Override
        public SearchOutcome search(ProviderSessionScope scope, ProviderSearchRequest initialRequest,
                String cursor, InvocationResolver resolver) throws ProviderFailure {
            throw new ProviderFailure("provider_unavailable", "Provider is unused", false);
        }

        @Override
        public InspectOutcome inspect(ProviderInspectRequest request,
                InvocationResolver resolver) throws ProviderFailure {
            throw new ProviderFailure("provider_unavailable", "Provider is unused", false);
        }

        @Override public int revokeClient(String clientId) { return 0; }
        @Override public int revokeGrant(String clientId, String grantId) { return 0; }
        @Override public int clearWorkspace(String workspaceId) { return 0; }
        @Override public void close() { }
    }
}
