package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;

/** Cross-component tests proving QC reasoning uses the same private closure as every other stage. */
class IsolatedReasonerQcTest {

    @Test
    void reasonerSeesAxiomsAcrossTheCapturedLoadedImportClosure() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        IRI importedIri = IRI.create("urn:test:qc-imported");
        OWLOntology active = manager.createOntology(IRI.create("urn:test:qc-active"));
        OWLOntology imported = manager.createOntology(importedIri);
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                manager.getOWLDataFactory().getOWLImportsDeclaration(importedIri)));
        OWLDataFactory df = manager.getOWLDataFactory();
        var a = df.getOWLClass(IRI.create("urn:test#A"));
        var b = df.getOWLClass(IRI.create("urn:test#B"));
        var c = df.getOWLClass(IRI.create("urn:test#C"));
        manager.addAxiom(imported, df.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(imported, df.getOWLDisjointClassesAxiom(b, c));
        manager.addAxiom(active, df.getOWLSubClassOfAxiom(a, c));

        TrackingHermitFactory factory = new TrackingHermitFactory(null);
        QcSuiteTools.SuiteExecution execution = execute(active, factory,
                Map.of("stages", List.of("reasoner", "profile"), "timeout_ms", 30_000));

        QcSuiteTools.StageResult reasoner = execution.results.get(0);
        assertEquals("fail", reasoner.verdict);
        assertEquals(1, reasoner.summary.get("unsatisfiable_count"),
                "the contradiction spans active + imported axioms and must not disappear in isolation");
        assertEquals("isolated", execution.snapshotMode);
        assertEquals(List.of("reasoner", "profile"), execution.snapshotStages);
        assertTrue(execution.sameValidationSnapshot);
        assertSame(factory.configuration, factory.receivedConfiguration);
        assertEquals(1, factory.creations);
    }

    @Test
    void liveMutationAfterCaptureCannotChangeTheIsolatedReasonerVerdict() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("urn:test:qc-mutation"));
        OWLDataFactory df = manager.getOWLDataFactory();
        var left = df.getOWLClass(IRI.create("urn:test#Left"));
        var right = df.getOWLClass(IRI.create("urn:test#Right"));
        manager.addAxiom(active, df.getOWLDisjointClassesAxiom(left, right));
        OWLNamedIndividual victim = df.getOWLNamedIndividual(IRI.create("urn:test#victim"));

        TrackingHermitFactory factory = new TrackingHermitFactory(() -> {
            manager.addAxiom(active, df.getOWLClassAssertionAxiom(left, victim));
            manager.addAxiom(active, df.getOWLClassAssertionAxiom(right, victim));
        });
        QcSuiteTools.SuiteExecution execution = execute(active, factory,
                Map.of("stages", List.of("reasoner", "structural"), "timeout_ms", 30_000));

        assertEquals("pass", execution.results.get(0).verdict,
                "the private reasoner must retain the pre-mutation snapshot");
        assertTrue(active.containsIndividualInSignature(victim.getIRI()),
                "the adversarial edit really did land in the live ontology");
        OWLReasoner liveProbe = new org.semanticweb.HermiT.ReasonerFactory().createReasoner(active);
        try {
            assertFalse(liveProbe.isConsistent(), "the live ontology is now inconsistent");
        } finally {
            liveProbe.dispose();
        }
        assertTrue(execution.sameValidationSnapshot);
    }

    @Test
    void timedOutPrivateReasonerIsInterruptedAndCannotContributeAStaleResult() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("urn:test:qc-timeout"));
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean disposed = new AtomicBoolean();
        TrackingHermitFactory factory = new TrackingHermitFactory(null) {
            @Override
            OWLReasoner decorate(OWLReasoner delegate) {
                return (OWLReasoner) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {OWLReasoner.class}, (proxy, method, args) -> {
                            if ("isConsistent".equals(method.getName())) {
                                while (!interrupted.get()) {
                                    try {
                                        Thread.sleep(100L);
                                    } catch (InterruptedException e) {
                                        interrupted.set(true);
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                throw new RuntimeException("cancelled test reasoner");
                            }
                            if ("interrupt".equals(method.getName())) {
                                interrupted.set(true);
                            }
                            if ("dispose".equals(method.getName())) {
                                disposed.set(true);
                            }
                            try {
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };

        long started = System.nanoTime();
        QcSuiteTools.SuiteExecution execution = execute(active, factory,
                Map.of("stages", List.of("reasoner"), "timeout_ms", 250));
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        QcSuiteTools.StageResult reasoner = execution.results.get(0);
        assertTrue(reasoner.executionError);
        assertTrue(reasoner.reason.contains("timed out"));
        assertTrue(interrupted.get());
        assertTrue(disposed.get(), "timeout transfers cleanup ownership and disposes before returning");
        assertTrue(elapsedMs < 3_000L, "the API wait is bounded even for an uncooperative reasoner");
        assertFalse(execution.snapshotStages.contains("reasoner"),
                "a timed-out result is never claimed as a completed snapshot stage");
    }

    @Test
    void inferredInvariantUsesTheSamePrivateReasonerAndSnapshot() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("urn:test:qc-inferred"));
        OWLDataFactory df = manager.getOWLDataFactory();
        var child = df.getOWLClass(IRI.create("https://example.org/test#Child"));
        var parent = df.getOWLClass(IRI.create("https://example.org/test#Parent"));
        var individual = df.getOWLNamedIndividual(IRI.create("https://example.org/test#instance"));
        manager.addAxiom(active, df.getOWLSubClassOfAxiom(child, parent));
        manager.addAxiom(active, df.getOWLClassAssertionAxiom(child, individual));
        Map<String, Object> invariant = Map.of(
                "id", "no-inferred-parent-instance",
                "severity", "error",
                "include_inferred", true,
                "sparql", "SELECT ?x WHERE { ?x a <https://example.org/test#Parent> }");

        TrackingHermitFactory factory = new TrackingHermitFactory(null);
        QcSuiteTools.SuiteExecution execution = execute(active, factory, Map.of(
                "stages", List.of("reasoner", "invariants"),
                "invariants", List.of(invariant),
                "timeout_ms", 30_000));

        assertEquals("pass", execution.results.get(0).verdict);
        QcSuiteTools.StageResult invariants = execution.results.get(1);
        assertEquals("fail", invariants.verdict);
        assertEquals(1, invariants.summary.get("violations"));
        assertEquals(0, invariants.summary.get("errors"));
        assertEquals(1, factory.creations,
                "reasoner gate and inferred materialization share one private reasoner instance");
        assertEquals(List.of("reasoner", "invariants"), execution.snapshotStages);
    }

    @Test
    void projectModeSwrlGateErrorSurfacesEvenWhenReasonerStageIsNotScheduled() throws Exception {
        // Policy shape: required_stages [invariants] with an include_inferred invariant — the reasoner
        // runs ONLY to materialize inferences. With SWRL rules in the closure and an ELK-named
        // reasoner selected, the inferred snapshot is rule-blind, so the project-mode gate error
        // produced for the inferences-only run must surface and gate even though 'reasoner' is not a
        // configured stage. Discarding it would false-pass the invariant over incomplete inferences.
        OWLOntology active = inferencesOnlyOntology(true);
        OWLModelManager model = modelManager(active, new TrackingHermitFactory(null),
                "ELK", "org.semanticweb.elk.owlapi.ElkReasonerFactory");
        ToolContext context = new ToolContext(HeadlessAccess.over(model), null);

        QcSuiteTools.SuiteExecution execution = QcSuiteTools.execute(context,
                inferredInvariantsProjectConfig());

        QcSuiteTools.StageResult reasoner = execution.results.stream()
                .filter(result -> "reasoner".equals(result.stage)).findFirst().orElseThrow(
                        () -> new AssertionError("the inferences-only SWRL gate error was discarded: "
                                + execution.results.stream().map(r -> r.stage).toList()));
        assertTrue(reasoner.executionError, "the SWRL-ignored verdict must be a stage ERROR");
        Map<String, Object> gated = QcSuiteTools.strictResult(execution, Set.of("invariants"),
                "error", 1, null, true);
        assertEquals("error", gated.get("gate"),
                "include_inferred invariants must not pass over a SWRL-blind inferred snapshot");
    }

    @Test
    void successfulInferencesOnlyRunAddsNoPhantomReasonerStage() throws Exception {
        // Same inferences-only policy shape but with NO SWRL rules in the closure: the isolated run
        // succeeds, so no reasoner stage row may appear and the gate is the invariants' verdict alone.
        OWLOntology active = inferencesOnlyOntology(false);
        OWLModelManager model = modelManager(active, new TrackingHermitFactory(null),
                "ELK", "org.semanticweb.elk.owlapi.ElkReasonerFactory");
        ToolContext context = new ToolContext(HeadlessAccess.over(model), null);

        QcSuiteTools.SuiteExecution execution = QcSuiteTools.execute(context,
                inferredInvariantsProjectConfig());

        assertEquals(List.of("invariants"),
                execution.results.stream().map(result -> result.stage).toList(),
                "a successful inferences-only run must not add a phantom reasoner stage");
        assertEquals("pass", QcSuiteTools.strictResult(execution, Set.of("invariants"),
                "error", 1, null, true).get("gate"));
    }

    private static OWLOntology inferencesOnlyOntology(boolean withSwrlRule) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("urn:test:qc-swrl-inferences-only"));
        OWLDataFactory df = manager.getOWLDataFactory();
        var child = df.getOWLClass(IRI.create("https://example.org/test#Child"));
        var parent = df.getOWLClass(IRI.create("https://example.org/test#Parent"));
        manager.addAxiom(active, df.getOWLSubClassOfAxiom(child, parent));
        if (withSwrlRule) {
            org.semanticweb.owlapi.model.SWRLVariable x = df.getSWRLVariable(IRI.create("urn:swrl#x"));
            manager.addAxiom(active, df.getSWRLRule(
                    Set.of(df.getSWRLClassAtom(child, x)), Set.of(df.getSWRLClassAtom(parent, x))));
        }
        return active;
    }

    /** The config shape ProjectQcTools builds for required_stages [invariants] — reasoner NOT scheduled. */
    private static QcSuiteTools.RunConfig inferredInvariantsProjectConfig() {
        List<Invariants.Invariant> invariants = List.of(new Invariants.Invariant("inferred-clean",
                "no instances of Missing", "error",
                "SELECT ?x WHERE { ?x a <https://example.org/test#Missing> }", true));
        return new QcSuiteTools.RunConfig(Set.of("invariants"), Set.of("invariants"), "error", "DL",
                25, 30_000, invariants, null, null, null, List.of(), null, List.of(), List.of(),
                true, PolicyGovernance.Rules.empty(), Set.of(), Map.of(), true, null, null, null);
    }

    private static QcSuiteTools.SuiteExecution execute(OWLOntology ontology,
            TrackingHermitFactory factory, Map<String, Object> args) {
        OWLModelManager model = modelManager(ontology, factory);
        ToolContext context = new ToolContext(HeadlessAccess.over(model), null);
        return QcSuiteTools.execute(context, QcSuiteTools.RunConfig.legacy(args));
    }

    private static OWLModelManager modelManager(OWLOntology ontology, TrackingHermitFactory factory) {
        return modelManager(ontology, factory, "HermiT", "org.semanticweb.HermiT");
    }

    private static OWLModelManager modelManager(OWLOntology ontology, TrackingHermitFactory factory,
            String reasonerName, String reasonerId) {
        ProtegeOWLReasonerInfo info = reasonerInfo(factory, reasonerName, reasonerId);
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                IsolatedReasonerQcTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                        case "getCurrentReasonerFactory" -> info;
                        case "getCurrentReasonerFactoryId" -> reasonerId;
                        case "getCurrentReasonerName" -> reasonerName;
                        case "getInstalledReasonerFactories" -> Set.of(info);
                        case "getCurrentReasoner", "classifyAsynchronously" ->
                                throw new AssertionError("QC must not consult or classify the live reasoner");
                        case "toString" -> "IsolatedQcReasonerManager";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        OWLModelManager base = FakeModelManager.over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(IsolatedReasonerQcTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return reasoners;
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static ProtegeOWLReasonerInfo reasonerInfo(TrackingHermitFactory factory,
            String reasonerName, String reasonerId) {
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                IsolatedReasonerQcTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getReasonerId" -> reasonerId;
                        case "getReasonerName" -> reasonerName;
                        case "getReasonerFactory" -> factory;
                        case "getRecommendedBuffering" -> BufferingMode.NON_BUFFERING;
                        case "getConfiguration" -> factory.configuration;
                        case "toString" -> reasonerName + " test info";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static class TrackingHermitFactory implements OWLReasonerFactory {
        private final OWLReasonerFactory delegate = new org.semanticweb.HermiT.ReasonerFactory();
        private final Runnable beforeCreate;
        final OWLReasonerConfiguration configuration = new SimpleConfiguration();
        OWLReasonerConfiguration receivedConfiguration;
        int creations;

        TrackingHermitFactory(Runnable beforeCreate) {
            this.beforeCreate = beforeCreate;
        }

        @Override
        public String getReasonerName() {
            return delegate.getReasonerName();
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            throw new AssertionError("unconfigured factory overload");
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            throw new AssertionError("unconfigured factory overload");
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            beforeCreate();
            receivedConfiguration = configuration;
            creations++;
            return decorate(delegate.createReasoner(ontology, configuration));
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            beforeCreate();
            receivedConfiguration = configuration;
            creations++;
            return decorate(delegate.createNonBufferingReasoner(ontology, configuration));
        }

        OWLReasoner decorate(OWLReasoner reasoner) {
            return reasoner;
        }

        private void beforeCreate() {
            if (beforeCreate != null) {
                beforeCreate.run();
            }
        }
    }
}
