package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.FreshEntityPolicy;
import org.semanticweb.owlapi.reasoner.IndividualNodeSetPolicy;
import org.semanticweb.owlapi.reasoner.NullReasonerProgressMonitor;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.ReasonerProgressMonitor;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

/** Adversarial configuration-parity tests for private reasoner construction. */
class IsolatedReasonerSpecTest {

    @Test
    void capturePreservesExactPluginConfigurationAndRecommendedBuffering() throws Exception {
        TrackingFactory factory = new TrackingFactory();
        OWLReasonerConfiguration configuration = configuration();
        AtomicReference<ReasonerProgressMonitor> monitor = new AtomicReference<>();
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(info(factory, configuration,
                BufferingMode.BUFFERING, monitor));

        OWLReasoner reasoner = spec.create(ontology());
        try {
            assertEquals(BufferingMode.BUFFERING, factory.lastMode);
            assertSame(configuration, factory.lastConfiguration,
                    "a private reasoner must receive the plugin's exact subtype/object, not a normalized copy");
            assertNotNull(monitor.get(), "capture supplies a private silent progress monitor");
            assertEquals(IndividualNodeSetPolicy.BY_NAME, reasoner.getIndividualNodeSetPolicy());

            Map<String, Object> metadata = spec.metadata(reasoner);
            assertEquals(true, metadata.get("configuration_object_preserved"));
            assertEquals(false, metadata.get("configuration_parity"),
                    "StructuralReasoner exposes that it ignored DISALLOW; the caveat must not be hidden");
            assertEquals(true, metadata.get("buffering_parity"));
            assertEquals(1234L, metadata.get("configuration_timeout_ms"));
            assertFalse(((java.util.List<?>) metadata.get("caveats")).isEmpty());
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void explanationStyleNoConfigFactoryCallStillInjectsCapturedConfiguration() throws Exception {
        TrackingFactory factory = new TrackingFactory();
        OWLReasonerConfiguration configuration = configuration();
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(info(factory, configuration,
                BufferingMode.BUFFERING, new AtomicReference<>()));

        OWLReasoner reasoner = spec.configuredFactory().createNonBufferingReasoner(ontology());
        try {
            assertEquals(BufferingMode.NON_BUFFERING, factory.lastMode,
                    "the explanation algorithm is allowed to require non-buffering");
            assertSame(configuration, factory.lastConfiguration,
                    "its no-config overload must not fall back to factory defaults");
            assertFalse((Boolean) spec.metadata(reasoner.getBufferingMode()).get("buffering_parity"));
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void explicitFactoryConfigurationIsNotSilentlyReplaced() throws Exception {
        TrackingFactory factory = new TrackingFactory();
        OWLReasonerConfiguration captured = configuration();
        OWLReasonerConfiguration explicit = new SimpleConfiguration(FreshEntityPolicy.ALLOW, 99L);
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(info(factory, captured,
                BufferingMode.NON_BUFFERING, new AtomicReference<>()));

        OWLReasoner reasoner = spec.configuredFactory().createNonBufferingReasoner(ontology(), explicit);
        try {
            assertSame(explicit, factory.lastConfiguration);
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void ignoredFreshEntityPolicyIsExposedAsAParityCaveat() throws Exception {
        AtomicBoolean disposed = new AtomicBoolean();
        TrackingFactory factory = new TrackingFactory() {
            @Override
            OWLReasoner decorate(OWLReasoner delegate) {
                return (OWLReasoner) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {OWLReasoner.class}, (proxy, method, args) -> {
                            if ("getFreshEntityPolicy".equals(method.getName())) {
                                return FreshEntityPolicy.ALLOW;
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
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(info(factory, configuration(),
                BufferingMode.NON_BUFFERING, new AtomicReference<>()));

        OWLReasoner reasoner = spec.create(ontology());
        Map<String, Object> metadata = spec.metadata(reasoner);
        assertEquals(false, metadata.get("configuration_parity"));
        assertTrue(String.valueOf(metadata.get("caveats")).contains("fresh_entity_policy"));
        reasoner.dispose();
        assertTrue(disposed.get());
    }

    @Test
    void malformedPluginMetadataFailsBeforeAnyReasonerIsCreated() {
        TrackingFactory factory = new TrackingFactory();
        assertTrue(assertThrows(ToolArgException.class, () -> IsolatedReasonerSpec.capture(
                info(factory, null, BufferingMode.BUFFERING, new AtomicReference<>())))
                .getMessage().contains("no reasoner configuration"));
        assertTrue(assertThrows(ToolArgException.class, () -> IsolatedReasonerSpec.capture(
                info(factory, configuration(), null, new AtomicReference<>())))
                .getMessage().contains("no buffering mode"));
        assertEquals(0, factory.creations);
    }

    private static OWLReasonerConfiguration configuration() {
        return new SimpleConfiguration(new NullReasonerProgressMonitor(), FreshEntityPolicy.DISALLOW, 1234L,
                IndividualNodeSetPolicy.BY_NAME);
    }

    private static OWLOntology ontology() throws Exception {
        return OWLManager.createOWLOntologyManager().createOntology(IRI.create("urn:test:isolated-reasoner"));
    }

    private static ProtegeOWLReasonerInfo info(OWLReasonerFactory factory,
            OWLReasonerConfiguration configuration, BufferingMode buffering,
            AtomicReference<ReasonerProgressMonitor> monitor) {
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                IsolatedReasonerSpecTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getReasonerId" -> "test.reasoner";
                        case "getReasonerName" -> "Test Reasoner";
                        case "getReasonerFactory" -> factory;
                        case "getRecommendedBuffering" -> buffering;
                        case "getConfiguration" -> {
                            monitor.set((ReasonerProgressMonitor) args[0]);
                            yield configuration;
                        }
                        case "toString" -> "TestReasonerInfo";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static class TrackingFactory implements OWLReasonerFactory {
        private final OWLReasonerFactory delegate = new StructuralReasonerFactory();
        OWLReasonerConfiguration lastConfiguration;
        BufferingMode lastMode;
        int creations;

        @Override
        public String getReasonerName() {
            return "Tracking Structural";
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            throw new AssertionError("the unconfigured overload must never be called");
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            throw new AssertionError("the unconfigured overload must never be called");
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            return create(ontology, configuration, BufferingMode.BUFFERING);
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            return create(ontology, configuration, BufferingMode.NON_BUFFERING);
        }

        private OWLReasoner create(OWLOntology ontology, OWLReasonerConfiguration configuration,
                BufferingMode mode) {
            creations++;
            lastConfiguration = configuration;
            lastMode = mode;
            OWLReasoner reasoner = mode == BufferingMode.BUFFERING
                    ? delegate.createReasoner(ontology, configuration)
                    : delegate.createNonBufferingReasoner(ontology, configuration);
            return decorate(reasoner);
        }

        OWLReasoner decorate(OWLReasoner reasoner) {
            return reasoner;
        }
    }
}
