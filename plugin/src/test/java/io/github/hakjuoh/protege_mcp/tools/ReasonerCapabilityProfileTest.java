package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.reasoner.BufferingMode;

import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityRegistry;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerIdentity;

class ReasonerCapabilityProfileTest {

    private final ReasonerCapabilityRegistry registry = new ReasonerCapabilityRegistry();

    @Test
    void officialProtegeHermitSelectionMatchesItsReviewedSemanticVariant() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var plugin = new org.semanticweb.HermiT.ProtegeReasonerFactory();
        plugin.setup(manager, "org.semanticweb.hermit.HermiT.reasoner.factory", "HermiT");

        IsolatedReasonerSpec selected = IsolatedReasonerSpec.capture(plugin);
        ReasonerIdentity identity = selected.capabilityIdentity();

        assertEquals("org.semanticweb.hermit.HermiT.reasoner.factory", identity.factoryId());
        assertEquals("custom", identity.configurationProfile());
        assertEquals(-1L, identity.timeoutMillis());
        assertEquals(true, ((org.semanticweb.HermiT.Configuration) selected.configuration())
                .ignoreUnsupportedDatatypes);
        assertEquals("reviewed", registry.report(identity).profileStatus(),
                identity.toMap().toString());
        assertEquals(List.of("org/semanticweb/HermiT/**", "rationals/**",
                "dk/brics/automaton/**", "org/apache/axiom/**",
                "org/semanticweb/owlapi/**"),
                identity.reviewedCodeScopes());
        assertEquals(2_635, identity.reviewedCodeClassCount());
    }

    @Test
    void operationalTimeoutDoesNotChangeReviewedSemanticCapabilities() {
        var factory = new org.semanticweb.HermiT.ReasonerFactory();
        ReasonerIdentity first = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory,
                new org.semanticweb.owlapi.reasoner.SimpleConfiguration(120_000L),
                BufferingMode.BUFFERING, "test");
        ReasonerIdentity second = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory,
                new org.semanticweb.owlapi.reasoner.SimpleConfiguration(300_000L),
                BufferingMode.BUFFERING, "test");

        assertNotEquals(first.configurationDigest(), second.configurationDigest());
        assertEquals(first.semanticConfigurationDigest(), second.semanticConfigurationDigest());
        assertEquals("reviewed", registry.report(first).profileStatus());
        assertEquals("reviewed", registry.report(second).profileStatus());
    }

    @Test
    void hermitDefaultSemanticConfigurationIsReviewedButCustomSettingsAreUnknown() {
        var factory = new org.semanticweb.HermiT.ReasonerFactory();
        var standard = new org.semanticweb.HermiT.Configuration();
        ReasonerIdentity reviewed = ReasonerIdentity.capture("org.semanticweb.HermiT", "HermiT",
                factory, standard, BufferingMode.BUFFERING, "test");
        assertEquals("factory_default", reviewed.configurationProfile());
        assertEquals("reviewed", registry.report(reviewed).profileStatus(),
                reviewed.toMap().toString());

        var custom = new org.semanticweb.HermiT.Configuration();
        custom.useDisjunctionLearning = !standard.useDisjunctionLearning;
        ReasonerIdentity changed = ReasonerIdentity.capture("org.semanticweb.HermiT", "HermiT",
                factory, custom, BufferingMode.BUFFERING, "test");
        assertEquals("custom", changed.configurationProfile());
        assertEquals("unknown", registry.report(changed).profileStatus());
        assertNotEquals(reviewed.configurationDigest(), changed.configurationDigest());
    }

    @Test
    void operationalTimeoutChangesIdentityWithoutChangingSemanticCapabilities() {
        var factory = new org.semanticweb.HermiT.ReasonerFactory();
        var first = new org.semanticweb.HermiT.Configuration();
        var second = new org.semanticweb.HermiT.Configuration();
        second.individualTaskTimeout = first.individualTaskTimeout + 1000;
        ReasonerIdentity left = ReasonerIdentity.capture("org.semanticweb.HermiT", "HermiT",
                factory, first, BufferingMode.BUFFERING, "test");
        ReasonerIdentity right = ReasonerIdentity.capture("org.semanticweb.HermiT", "HermiT",
                factory, second, BufferingMode.BUFFERING, "test");
        assertEquals("reviewed", registry.report(left).profileStatus());
        assertEquals("reviewed", registry.report(right).profileStatus());
        assertNotEquals(left.configurationDigest(), right.configurationDigest());
        assertEquals(left.semanticConfigurationDigest(), right.semanticConfigurationDigest());
        assertNotEquals(left.profileKey(), right.profileKey());
    }

    @Test
    void hermitBufferConfigurationMustMatchTheSelectedBufferingMode() {
        var factory = new org.semanticweb.HermiT.ReasonerFactory();
        var configuration = new org.semanticweb.HermiT.Configuration();
        assertTrue(configuration.bufferChanges);
        ReasonerIdentity mismatch = ReasonerIdentity.capture("org.semanticweb.HermiT", "HermiT",
                factory, configuration, BufferingMode.NON_BUFFERING, "test");
        assertEquals("unknown", registry.report(mismatch).profileStatus());
    }

    @Test
    void elkInternalParametersParticipateInConfigurationReviewAndIdentity() throws Exception {
        var factory = new org.semanticweb.elk.owlapi.ElkReasonerFactory();
        var standard = new org.semanticweb.elk.owlapi.ElkReasonerConfiguration();
        ReasonerIdentity reviewed = ReasonerIdentity.capture(
                "org.semanticweb.elk.owlapi.ElkReasonerFactory", "ELK", factory, standard,
                BufferingMode.BUFFERING, "test");
        assertEquals("factory_default", reviewed.configurationProfile());
        assertEquals("reviewed", registry.report(reviewed).profileStatus());

        var custom = new org.semanticweb.elk.owlapi.ElkReasonerConfiguration();
        String key = org.semanticweb.elk.reasoner.config.ReasonerConfiguration
                .INCREMENTAL_MODE_ALLOWED;
        boolean current = custom.getElkConfiguration().getParameterAsBoolean(key);
        custom.getElkConfiguration().setParameter(key, Boolean.toString(!current));
        ReasonerIdentity changed = ReasonerIdentity.capture(
                "org.semanticweb.elk.owlapi.ElkReasonerFactory", "ELK", factory, custom,
                BufferingMode.BUFFERING, "test");
        assertEquals("custom", changed.configurationProfile());
        assertEquals("unknown", registry.report(changed).profileStatus());
        assertNotEquals(reviewed.configurationDigest(), changed.configurationDigest());

        var operational = new org.semanticweb.elk.owlapi.ElkReasonerConfiguration();
        String workersKey = org.semanticweb.elk.reasoner.config.ReasonerConfiguration
                .NUM_OF_WORKING_THREADS;
        int workers = operational.getElkConfiguration().getParameterAsInt(workersKey);
        operational.getElkConfiguration().setParameter(workersKey,
                Integer.toString(workers == 1 ? 2 : 1));
        String evictorKey = org.semanticweb.elk.reasoner.config.ReasonerConfiguration
                .TRACING_EVICTOR;
        String evictor = new org.semanticweb.elk.util.collections.RecencyEvictor.Builder()
                .capacity(64).loadFactor(0.5).toString();
        operational.getElkConfiguration().setParameter(evictorKey, evictor);
        ReasonerIdentity tuned = ReasonerIdentity.capture(
                "org.semanticweb.elk.owlapi.ElkReasonerFactory", "ELK", factory,
                operational, BufferingMode.BUFFERING, "test");
        assertEquals("factory_default", tuned.configurationProfile());
        assertEquals("reviewed", registry.report(tuned).profileStatus());
        assertNotEquals(reviewed.configurationDigest(), tuned.configurationDigest());
        assertEquals(reviewed.semanticConfigurationDigest(),
                tuned.semanticConfigurationDigest());

        var closeLeft = new org.semanticweb.elk.owlapi.ElkReasonerConfiguration();
        var closeRight = new org.semanticweb.elk.owlapi.ElkReasonerConfiguration();
        putElkParameter(closeLeft, evictorKey,
                new org.semanticweb.elk.util.collections.RecencyEvictor.Builder()
                        .capacity(64).loadFactor(0.5000001));
        putElkParameter(closeRight, evictorKey,
                new org.semanticweb.elk.util.collections.RecencyEvictor.Builder()
                        .capacity(64).loadFactor(0.5000002));
        ReasonerIdentity closeLeftIdentity = ReasonerIdentity.capture(
                "org.semanticweb.elk.owlapi.ElkReasonerFactory", "ELK", factory,
                closeLeft, BufferingMode.BUFFERING, "test");
        ReasonerIdentity closeRightIdentity = ReasonerIdentity.capture(
                "org.semanticweb.elk.owlapi.ElkReasonerFactory", "ELK", factory,
                closeRight, BufferingMode.BUFFERING, "test");
        assertNotEquals(closeLeftIdentity.configurationDigest(),
                closeRightIdentity.configurationDigest());

        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            String usDigest = ReasonerIdentity.capture(
                    "org.semanticweb.elk.owlapi.ElkReasonerFactory", "ELK", factory,
                    closeLeft, BufferingMode.BUFFERING, "test").configurationDigest();
            Locale.setDefault(Locale.FRANCE);
            String franceDigest = ReasonerIdentity.capture(
                    "org.semanticweb.elk.owlapi.ElkReasonerFactory", "ELK", factory,
                    closeLeft, BufferingMode.BUFFERING, "test").configurationDigest();
            assertEquals(usDigest, franceDigest);
        } finally {
            Locale.setDefault(previousLocale);
        }

        assertEquals(4_871, reviewed.reviewedCodeClassCount());
    }

    @Test
    void officialProtegeElkSelectionMatchesQualifiedRuntimeIds() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        org.semanticweb.elk.protege.ElkPreferences saved =
                new org.semanticweb.elk.protege.ElkPreferences().load();
        int savedWorkers = saved.numberOfWorkers;
        boolean savedIncremental = saved.incrementalMode;
        boolean savedAutoSync = saved.autoSynchronization;
        boolean savedInline = saved.inlineInferences;
        try {
            org.semanticweb.elk.protege.ElkPreferences controlled =
                    new org.semanticweb.elk.protege.ElkPreferences().load();
            controlled.incrementalMode = true;
            controlled.autoSynchronization = false;
            controlled.save();
            for (String id : List.of("au.csiro.elk.reasoner.factory",
                    "org.semanticweb.elk.elk.reasoner.factory")) {
                IsolatedReasonerSpec selected = officialElk(manager, id);
                assertEquals(BufferingMode.BUFFERING, selected.selectedBuffering());
                ReasonerIdentity identity = selected.capabilityIdentity();
                assertEquals(id, identity.factoryId());
                assertEquals(4_871, identity.reviewedCodeClassCount());
                assertEquals("reviewed", registry.report(identity).profileStatus(),
                        id + " " + identity.toMap());
            }

            controlled.autoSynchronization = true;
            controlled.save();
            IsolatedReasonerSpec nonBuffering = officialElk(manager,
                    "au.csiro.elk.reasoner.factory");
            assertEquals(BufferingMode.NON_BUFFERING, nonBuffering.selectedBuffering());
            assertEquals("reviewed",
                    registry.report(nonBuffering.capabilityIdentity()).profileStatus());
        } finally {
            org.semanticweb.elk.protege.ElkPreferences restore =
                    new org.semanticweb.elk.protege.ElkPreferences();
            restore.numberOfWorkers = savedWorkers;
            restore.incrementalMode = savedIncremental;
            restore.autoSynchronization = savedAutoSync;
            restore.inlineInferences = savedInline;
            restore.save();
        }
    }

    private static IsolatedReasonerSpec officialElk(
            org.semanticweb.owlapi.model.OWLOntologyManager manager, String id) {
        var plugin = new org.semanticweb.elk.protege.ProtegeReasonerFactory();
        plugin.setup(manager, id, "ELK");
        return IsolatedReasonerSpec.capture(plugin);
    }

    @SuppressWarnings("unchecked")
    private static void putElkParameter(
            org.semanticweb.elk.owlapi.ElkReasonerConfiguration configuration,
            String key, Object value) throws Exception {
        Field field = org.semanticweb.elk.config.BaseConfiguration.class
                .getDeclaredField("paramMap");
        assertTrue(field.trySetAccessible());
        Map<String, Object> parameters = (Map<String, Object>) field.get(
                configuration.getElkConfiguration());
        parameters.put(key, value);
    }

    @Test
    void reviewedHermitSwrlAtomSubsetIsAcceptedByThePinnedEngine() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/hermit-rules"));
        var data = manager.getOWLDataFactory();
        var x = data.getSWRLVariable(IRI.create("https://example.org/var/x"));
        var y = data.getSWRLVariable(IRI.create("https://example.org/var/y"));
        var literal = data.getSWRLVariable(IRI.create("https://example.org/var/literal"));
        var a = data.getOWLClass(IRI.create("https://example.org/A"));
        var b = data.getOWLClass(IRI.create("https://example.org/B"));
        var p = data.getOWLObjectProperty(IRI.create("https://example.org/p"));
        var q = data.getOWLDataProperty(IRI.create("https://example.org/q"));

        manager.addAxiom(ontology, data.getSWRLRule(Set.of(
                data.getSWRLClassAtom(a, x), data.getSWRLObjectPropertyAtom(p, x, y)),
                Set.of(data.getSWRLClassAtom(b, y))));
        manager.addAxiom(ontology, data.getSWRLRule(Set.of(
                data.getSWRLDataPropertyAtom(q, x, literal),
                data.getSWRLDataRangeAtom(data.getIntegerOWLDatatype(), literal)),
                Set.of(data.getSWRLClassAtom(b, x))));
        manager.addAxiom(ontology, data.getSWRLRule(Set.of(
                data.getSWRLSameIndividualAtom(x, y)),
                Set.of(data.getSWRLDifferentIndividualsAtom(x, y))));

        var reasoner = new org.semanticweb.HermiT.ReasonerFactory()
                .createReasoner(ontology, new org.semanticweb.HermiT.Configuration());
        try {
            assertEquals(true, reasoner.isConsistent());
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void reviewedHermitRuleProducesTheExpectedNamedIndividualInference() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/hermit-inference"));
        var data = manager.getOWLDataFactory();
        var a = data.getOWLClass(IRI.create("https://example.org/A"));
        var b = data.getOWLClass(IRI.create("https://example.org/B"));
        var individual = data.getOWLNamedIndividual(IRI.create("https://example.org/individual"));
        var x = data.getSWRLVariable(IRI.create("https://example.org/var/x"));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(a, individual));
        manager.addAxiom(ontology, data.getSWRLRule(
                Set.of(data.getSWRLClassAtom(a, x)),
                Set.of(data.getSWRLClassAtom(b, x))));

        var reasoner = new org.semanticweb.HermiT.ReasonerFactory()
                .createReasoner(ontology, new org.semanticweb.HermiT.Configuration());
        try {
            reasoner.precomputeInferences(
                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);
            assertTrue(reasoner.getTypes(individual, false).containsEntity(b));
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void pinnedHermitRejectsBuiltinsAsTheProfileClaims() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/hermit-builtin"));
        var data = manager.getOWLDataFactory();
        var x = data.getSWRLVariable(IRI.create("https://example.org/var/x"));
        var y = data.getSWRLVariable(IRI.create("https://example.org/var/y"));
        manager.addAxiom(ontology, data.getSWRLRule(Set.of(
                data.getSWRLBuiltInAtom(
                        IRI.create("http://www.w3.org/2003/11/swrlb#add"), List.of(x, y))),
                Set.of(data.getSWRLClassAtom(
                        data.getOWLClass(IRI.create("https://example.org/Result")), x))));

        assertThrows(RuntimeException.class, () ->
                new org.semanticweb.HermiT.ReasonerFactory()
                        .createReasoner(ontology, new org.semanticweb.HermiT.Configuration()));
    }

    @Test
    void structuralConsistencyClaimMatchesPinnedEngineBehavior() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/structural"));
        var data = manager.getOWLDataFactory();
        var a = data.getOWLClass(IRI.create("https://example.org/A"));
        var b = data.getOWLClass(IRI.create("https://example.org/B"));
        var individual = data.getOWLNamedIndividual(IRI.create("https://example.org/individual"));
        manager.addAxiom(ontology, data.getOWLDisjointClassesAxiom(a, b));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(a, individual));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(b, individual));

        var factory = new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory();
        var identity = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory,
                new org.semanticweb.owlapi.reasoner.SimpleConfiguration(),
                BufferingMode.BUFFERING, "test");
        assertEquals("reviewed", registry.report(identity).profileStatus());
        assertEquals("unsupported", registry.report(identity).owlStatus("consistency").value());
        var reasoner = factory.createReasoner(ontology);
        try {
            assertEquals(true, reasoner.isConsistent(),
                    "structural reasoner does not detect the semantic contradiction");
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void structuralSupportedRowsHaveBehavioralFixtures() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/structural-matrix"));
        var data = manager.getOWLDataFactory();
        var a = data.getOWLClass(IRI.create("https://example.org/A"));
        var b = data.getOWLClass(IRI.create("https://example.org/B"));
        var c = data.getOWLClass(IRI.create("https://example.org/C"));
        var individual = data.getOWLNamedIndividual(IRI.create("https://example.org/i"));
        var p = data.getOWLObjectProperty(IRI.create("https://example.org/p"));
        var q = data.getOWLObjectProperty(IRI.create("https://example.org/q"));
        var dp = data.getOWLDataProperty(IRI.create("https://example.org/dp"));
        var dq = data.getOWLDataProperty(IRI.create("https://example.org/dq"));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(a, c));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(a, individual));
        manager.addAxiom(ontology, data.getOWLSubObjectPropertyOfAxiom(p, q));
        manager.addAxiom(ontology, data.getOWLSubDataPropertyOfAxiom(dp, dq));

        var reasoner = new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory()
                .createReasoner(ontology);
        try {
            assertTrue(reasoner.getSuperClasses(a, false).containsEntity(b));
            assertTrue(reasoner.getEquivalentClasses(a).contains(c));
            assertTrue(reasoner.getTypes(individual, false).containsEntity(a));
            assertTrue(reasoner.getSuperObjectProperties(p, false).containsEntity(q));
            assertTrue(reasoner.getSuperDataProperties(dp, false).containsEntity(dq));
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void elkSupportedRowsHaveBehavioralAndIncrementalFixtures() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/elk-matrix"));
        var data = manager.getOWLDataFactory();
        var a = data.getOWLClass(IRI.create("https://example.org/A"));
        var b = data.getOWLClass(IRI.create("https://example.org/B"));
        var c = data.getOWLClass(IRI.create("https://example.org/C"));
        var impossible = data.getOWLClass(IRI.create("https://example.org/Impossible"));
        var added = data.getOWLClass(IRI.create("https://example.org/Added"));
        var first = data.getOWLNamedIndividual(IRI.create("https://example.org/first"));
        var middle = data.getOWLNamedIndividual(IRI.create("https://example.org/middle"));
        var last = data.getOWLNamedIndividual(IRI.create("https://example.org/last"));
        var p = data.getOWLObjectProperty(IRI.create("https://example.org/p"));
        var q = data.getOWLObjectProperty(IRI.create("https://example.org/q"));
        var r = data.getOWLObjectProperty(IRI.create("https://example.org/r"));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(b, c));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(a, first));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(impossible,
                data.getOWLNothing()));
        manager.addAxiom(ontology, data.getOWLSubPropertyChainOfAxiom(List.of(p, q), r));
        manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(p, first, middle));
        manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(q, middle, last));

        var reasoner = new org.semanticweb.elk.owlapi.ElkReasonerFactory()
                .createReasoner(ontology);
        try {
            reasoner.precomputeInferences(
                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS,
                    org.semanticweb.owlapi.reasoner.InferenceType.OBJECT_PROPERTY_ASSERTIONS);
            assertTrue(reasoner.isConsistent());
            assertFalse(reasoner.isSatisfiable(impossible));
            assertTrue(reasoner.getSuperClasses(a, false).containsEntity(b));
            assertTrue(reasoner.getEquivalentClasses(b).contains(c));
            assertTrue(reasoner.getTypes(first, false).containsEntity(b));
            assertTrue(reasoner.isEntailed(data.getOWLObjectPropertyAssertionAxiom(
                    r, first, last)));

            manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(a, added));
            reasoner.flush();
            assertTrue(reasoner.getSuperClasses(a, false).containsEntity(added));
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void reviewedElkProfileDoesNotClaimRuleDerivedInferences() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/elk-rules"));
        var data = manager.getOWLDataFactory();
        var a = data.getOWLClass(IRI.create("https://example.org/A"));
        var b = data.getOWLClass(IRI.create("https://example.org/B"));
        var individual = data.getOWLNamedIndividual(IRI.create("https://example.org/individual"));
        var x = data.getSWRLVariable(IRI.create("https://example.org/var/x"));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(a, individual));
        manager.addAxiom(ontology, data.getSWRLRule(
                Set.of(data.getSWRLClassAtom(a, x)),
                Set.of(data.getSWRLClassAtom(b, x))));

        var reasoner = new org.semanticweb.elk.owlapi.ElkReasonerFactory()
                .createReasoner(ontology);
        try {
            reasoner.precomputeInferences(
                    org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);
            assertEquals(false, reasoner.getTypes(individual, false).containsEntity(b));
        } finally {
            reasoner.dispose();
        }
    }
}
