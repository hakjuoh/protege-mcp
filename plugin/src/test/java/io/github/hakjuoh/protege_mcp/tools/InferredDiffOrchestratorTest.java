package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

/**
 * semantic_diff mode=inferred|both orchestration: same-hop capture with a HermiT-backed reasoner
 * selection seam, off-EDT evaluation, and the single result-level compatibility block that fails
 * closed on missing inferred evidence (ADR 0004 decisions 1, 5, 7, 8).
 */
class InferredDiffOrchestratorTest {

    private static final String NS = "http://example.org/orch#";

    @Test
    void captureRefusesInferredModesWithoutASelectedReasoner() throws Exception {
        OWLOntology ontology = ontology("left");
        OWLModelManager mm = FakeModelManager.withNoReasonerSelected(ontology);
        assertThrows(ToolArgException.class,
                () -> InferredDiffOrchestrator.capture(mm, ontology, ontology, false, null));
    }

    @Test
    void captureRefusesAnUnknownRequestedReasonerName() throws Exception {
        OWLOntology ontology = ontology("left");
        OWLModelManager mm = hermitModelManager(ontology);
        ToolArgException error = assertThrows(ToolArgException.class,
                () -> InferredDiffOrchestrator.capture(mm, ontology, ontology, false, "NoSuch"));
        assertTrue(error.getMessage().contains("NoSuch"), error.getMessage());
    }

    @Test
    void captureResolvesAVersionlessReferenceAgainstTheVersionedDisplayName() throws Exception {
        OWLOntology ontology = ontology("versionless");
        OWLModelManager mm = hermitModelManager(ontology, "HermiT 1.4.3.456");
        InferredDiffOrchestrator.Captured captured =
                InferredDiffOrchestrator.capture(mm, ontology, ontology, false, "HermiT");
        assertNotNull(captured, "the version-less convention selects the single versioned install");
    }

    @Test
    void captureRefusesAnAmbiguousReasonerReferenceNamingEveryCandidate() throws Exception {
        OWLOntology ontology = ontology("ambiguous");
        OWLModelManager mm = hermitModelManager(ontology, "HermiT 1.4", "HermiT 2.0");
        ToolArgException error = assertThrows(ToolArgException.class,
                () -> InferredDiffOrchestrator.capture(mm, ontology, ontology, false, "HermiT"));
        assertTrue(error.getMessage().contains("HermiT 1.4"), error.getMessage());
        assertTrue(error.getMessage().contains("HermiT 2.0"), error.getMessage());
    }

    @Test
    void exactFactoryIdCapturesItsOwnFactoryWhenDisplayNamesCollide() throws Exception {
        // Two installed factories share one display name: an exact id reference must capture ITS
        // factory, not whichever factory a display-name-keyed lookup kept.
        OWLOntology ontology = ontology("collide");
        OWLModelManager mm = hermitModelManager(ontology,
                hermitInfo("Pellet", "com.example.pellet.a"),
                hermitInfo("Pellet", "com.example.pellet.b"));

        assertEquals("com.example.pellet.a", InferredDiffOrchestrator
                .capture(mm, ontology, ontology, false, "com.example.pellet.a")
                .spec().reasonerId());
        assertEquals("com.example.pellet.b", InferredDiffOrchestrator
                .capture(mm, ontology, ontology, false, "com.example.pellet.b")
                .spec().reasonerId());

        ToolArgException ambiguous = assertThrows(ToolArgException.class,
                () -> InferredDiffOrchestrator.capture(mm, ontology, ontology, false, "Pellet"));
        assertTrue(ambiguous.getMessage().contains("matches more than one"), ambiguous.getMessage());
        assertTrue(ambiguous.getMessage().contains("Pellet [com.example.pellet.a]"),
                ambiguous.getMessage());
        assertTrue(ambiguous.getMessage().contains("Pellet [com.example.pellet.b]"),
                ambiguous.getMessage());
    }

    @Test
    void inferredEvaluationReportsGainedEntailmentsAndParityMetadata() throws Exception {
        OWLOntology left = ontology("shared");
        OWLOntology right = ontology("shared");
        OWLDataFactory df = right.getOWLOntologyManager().getOWLDataFactory();
        declareClasses(left, "Dog", "Animal");
        declareClasses(right, "Dog", "Animal");
        right.getOWLOntologyManager().addAxiom(right, df.getOWLSubClassOfAxiom(
                cls(right, "Dog"), cls(right, "Animal")));

        OWLModelManager mm = hermitModelManager(left);
        InferredDiffOrchestrator.Captured captured =
                InferredDiffOrchestrator.capture(mm, left, right, false, "HermiT");
        Map<String, Object> evaluated = InferredDiffOrchestrator.run(captured, 60_000, 25);

        Map<?, ?> inferred = (Map<?, ?>) evaluated.get("inferred");
        assertEquals("inferred-diff-v1", inferred.get("entailment_set"), inferred::toString);
        Map<?, ?> subsumption = (Map<?, ?>) inferred.get("subsumption");
        Map<?, ?> gained = (Map<?, ?>) subsumption.get("gained");
        assertEquals(1, gained.get("count"), subsumption::toString);
        assertNotNull(evaluated.get("reasoner"), "parity metadata must be disclosed");
        Map<?, ?> parity = (Map<?, ?>) evaluated.get("reasoner");
        assertNotNull(parity.get("reasoner_name"));
    }

    @Test
    void mergedCompatibilityFailsClosedOnErroredInferredEvidence() {
        Map<String, Object> assertedCompatibility = new java.util.LinkedHashMap<>();
        assertedCompatibility.put("classification", "metadata_only");
        assertedCompatibility.put("policy_driven", false);
        assertedCompatibility.put("caveat", "Prototype classification; project-specific "
                + "breaking-change policy was not applied.");
        Map<String, Object> inferred = new java.util.LinkedHashMap<>();
        inferred.put("entailment_set", "inferred-diff-v1");
        inferred.put("error", "the inferred evaluation did not complete within 1 ms");

        Map<String, Object> merged = InferredDiffOrchestrator.mergedCompatibility(
                assertedCompatibility, inferred);

        assertEquals("potentially_breaking", merged.get("classification"), merged::toString);
        String caveat = String.valueOf(merged.get("caveat"));
        assertTrue(caveat.startsWith("Prototype classification;"),
                "the asserted caveat is preserved verbatim, first: " + caveat);
        assertTrue(caveat.contains("Inferred evidence forces potentially_breaking"), caveat);
    }

    @Test
    void mergedCompatibilityFailsClosedOnErroredCategoriesAndNewUnsatisfiability() {
        Map<String, Object> inferred = new java.util.LinkedHashMap<>();
        inferred.put("errored_categories", java.util.List.of("types"));
        Map<String, Object> satisfiability = new java.util.LinkedHashMap<>();
        satisfiability.put("newly_unsatisfiable", Map.of("count", 2, "items", java.util.List.of()));
        inferred.put("satisfiability", satisfiability);

        Map<String, Object> merged = InferredDiffOrchestrator.mergedCompatibility(null, inferred);

        assertEquals("potentially_breaking", merged.get("classification"), merged::toString);
        String caveat = String.valueOf(merged.get("caveat"));
        assertTrue(caveat.contains("types"), caveat);
        assertTrue(caveat.contains("2 class(es) became unsatisfiable"), caveat);
        assertEquals(Boolean.FALSE, merged.get("policy_driven"));
    }

    @Test
    void expiredBudgetYieldsAnErroredInferredSectionNotAnException() throws Exception {
        OWLOntology left = ontology("budget");
        OWLOntology right = ontology("budget");
        declareClasses(left, "A");
        declareClasses(right, "A");
        OWLModelManager mm = hermitModelManager(left);
        InferredDiffOrchestrator.Captured captured =
                InferredDiffOrchestrator.capture(mm, left, right, false, "HermiT");

        // A 1 ms budget expires before (or during) evaluation; either the whole section or its
        // categories error — never an exception, never a silently complete result claiming success.
        Map<String, Object> evaluated = InferredDiffOrchestrator.run(captured, 1, 25);
        Map<?, ?> inferred = (Map<?, ?>) evaluated.get("inferred");
        boolean sectionErrored = inferred.containsKey("error");
        boolean categoriesErrored = inferred.get("errored_categories") instanceof java.util.List<?> l
                && !l.isEmpty();
        assertTrue(sectionErrored || categoriesErrored, inferred::toString);
        Map<String, Object> merged = InferredDiffOrchestrator.mergedCompatibility(null,
                castMap(inferred));
        assertEquals("potentially_breaking", merged.get("classification"), merged::toString);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    // ------------------------------------------------------------------ module ownership (ADR 0004 d4)

    @Test
    void moduleOwnershipReportsATermMovingBetweenOwnedNamespaces(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy = twoModulePolicy(temp);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> modules =
                (java.util.List<Map<String, Object>>) policy.effective().get("modules");

        // The term moved from module a's namespace to module b's: no violation on either side
        // (each side defines it inside an owned namespace), but the owner changed.
        Map<String, Object> ownership = InferredDiffOrchestrator.moduleOwnership(
                Set.of("https://example.org/a/Widget", "https://example.org/a/Stable"),
                Set.of("https://example.org/b/Widget", "https://example.org/a/Stable"),
                modules, 25);

        assertEquals(Boolean.TRUE, ownership.get("available"), ownership::toString);
        assertEquals(2, ownership.get("count"), ownership::toString);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> changes =
                (java.util.List<Map<String, Object>>) ownership.get("changes");
        assertEquals("https://example.org/a/Widget", changes.get(0).get("iri"));
        assertEquals("https://example.org/module-a", changes.get(0).get("was_module"));
        assertEquals(null, changes.get(0).get("now_module"));
        assertEquals("https://example.org/b/Widget", changes.get(1).get("iri"));
        assertEquals(null, changes.get(1).get("was_module"));
        assertEquals("https://example.org/module-b", changes.get(1).get("now_module"));
    }

    @Test
    void moduleOwnershipIsUnavailableWithoutOwnedNamespaces() {
        Map<String, Object> ownership = InferredDiffOrchestrator.moduleOwnership(
                Set.of("https://example.org/a/X"), Set.of("https://example.org/b/X"),
                java.util.List.of(Map.of("ontology_iri", "https://example.org/module-a")), 25);
        assertEquals(Boolean.FALSE, ownership.get("available"));
        assertEquals("no project policy with modules[].owned_namespaces was loaded",
                ownership.get("reason"));
    }

    // ------------------------------------------------------------------ policy categories (DiffTools)

    @Test
    void withoutPolicyPathBothPolicyCategoriesArePresentButUnavailable() {
        InferredDiffOrchestrator.Captured captured = new InferredDiffOrchestrator.Captured(
                null, null, new io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService.Sigma(
                        Set.of(), Set.of()), Set.of(), Set.of(), Map.of(), null);
        DiffTools.PolicyCategories categories = DiffTools.policyCategories(null, captured, 25);
        assertEquals(Boolean.FALSE, categories.moduleOwnership().get("available"));
        assertEquals("no project policy with modules[].owned_namespaces was loaded",
                categories.moduleOwnership().get("reason"));
        assertEquals(Boolean.FALSE, categories.stageDeltas().get("available"));
        assertNotNull(categories.stageDeltas().get("reason"));
        assertEquals(null, categories.plan());
    }

    @Test
    void aLoadedButInvalidPolicyErrorsBothCategoriesAndForcesPotentiallyBreaking(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        // Loaded-but-invalid: the policy declares two modules but only one file resolves.
        java.nio.file.Path policyPath = temp.resolve("policy.yaml");
        writeModule(temp.resolve("a.ttl"), "https://example.org/module-a",
                "<https://example.org/a/Widget> a owl:Class .");
        io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.writePolicy(policyPath,
                io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.minimalPolicy(
                        "invalid-modules", "https://example.org/module-a")
                        + "modules:\n"
                        + "  - ontology_iri: https://example.org/module-a\n"
                        + "    path: a.ttl\n"
                        + "    owned_namespaces: ['https://example.org/a/']\n"
                        + "  - ontology_iri: https://example.org/module-b\n"
                        + "    path: missing.ttl\n"
                        + "    owned_namespaces: ['https://example.org/b/']\n"
                        + "validation:\n  required_stages: [governance]\n");
        io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy =
                io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.loaded());
        assertTrue(!policy.valid(), () -> policy.issues().toString());

        InferredDiffOrchestrator.Captured captured = new InferredDiffOrchestrator.Captured(
                null, null, new io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService.Sigma(
                        Set.of(), Set.of()), Set.of(), Set.of(), Map.of(), null);
        DiffTools.PolicyCategories categories = DiffTools.policyCategories(
                new RevisionTools.PolicyState(policy, null, null), captured, 25);

        assertNotNull(categories.moduleOwnership().get("error"), "module_ownership fails closed");
        assertNotNull(categories.stageDeltas().get("error"), "stage_deltas fail closed");
        assertEquals(null, categories.plan());

        Map<String, Object> merged = InferredDiffOrchestrator.mergedCompatibility(null,
                Map.of(), categories.stageDeltas());
        assertEquals("potentially_breaking", merged.get("classification"), merged::toString);
        assertTrue(String.valueOf(merged.get("caveat")).contains("stage deltas errored"),
                String.valueOf(merged.get("caveat")));
    }

    // ------------------------------------------------------------------ stage deltas (ADR 0004 d3)

    @Test
    void anInvariantViolatedOnTheRightSideOnlyEntersTheStageDelta() throws Exception {
        OWLOntology left = ontology("delta-left");
        OWLOntology right = ontology("delta-right");
        OWLDataFactory df = right.getOWLOntologyManager().getOWLDataFactory();
        declareClasses(left, "Dog", "Animal");
        declareClasses(right, "Dog", "Animal");
        right.getOWLOntologyManager().addAxiom(right, df.getOWLSubClassOfAxiom(
                cls(right, "Dog"), cls(right, "Animal")));

        OWLModelManager mm = hermitModelManager(left);
        InferredDiffOrchestrator.Captured captured =
                InferredDiffOrchestrator.capture(mm, left, right, false, "HermiT");
        InferredDiffOrchestrator.StagePlan plan = new InferredDiffOrchestrator.StagePlan(
                java.util.List.of("invariants", "governance"));
        plan.invariants = java.util.List.of(new Invariants.Invariant("no-animal-subs",
                "nothing may specialize Animal", "error",
                "SELECT ?sub WHERE { ?sub rdfs:subClassOf <" + NS + "Animal> }", false));

        Map<String, Object> evaluated = InferredDiffOrchestrator.run(captured, 60_000, 25, plan);

        Map<?, ?> stageDeltas = (Map<?, ?>) evaluated.get("stage_deltas");
        assertNotNull(stageDeltas, evaluated::toString);
        assertEquals(Boolean.TRUE, stageDeltas.get("available"));
        Map<?, ?> invariants = (Map<?, ?>) stageDeltas.get("invariants");
        Map<?, ?> entered = (Map<?, ?>) invariants.get("entered");
        Map<?, ?> departed = (Map<?, ?>) invariants.get("left");
        assertEquals(1, entered.get("count"), invariants::toString);
        assertEquals(0, departed.get("count"), invariants::toString);
        String member = String.valueOf(((java.util.List<?>) entered.get("items")).get(0));
        assertTrue(member.contains("no-animal-subs"), member);
        // The governance delta discloses its reduced scope machine-readably: rule-driven policy
        // checks only, never implying parity with run_project_qc's full governance stage.
        Map<?, ?> governance = (Map<?, ?>) stageDeltas.get("governance");
        assertEquals("policy_rules_only", governance.get("scope"), governance::toString);
        assertEquals(0, ((Map<?, ?>) governance.get("entered")).get("count"));
        assertEquals(0, ((Map<?, ?>) governance.get("left")).get("count"));

        // A member-level delta with no errors does not force the classification by itself.
        Map<String, Object> merged = InferredDiffOrchestrator.mergedCompatibility(null,
                castMap((Map<?, ?>) evaluated.get("inferred")), castMap(stageDeltas));
        assertTrue(String.valueOf(merged.get("caveat")).isEmpty()
                        || !String.valueOf(merged.get("caveat")).contains("stage delta errored"),
                merged::toString);
    }

    @Test
    void anInferenceDependentInvariantFailsTheStageDeltaClosed() throws Exception {
        OWLOntology left = ontology("closed-left");
        OWLOntology right = ontology("closed-right");
        declareClasses(left, "A");
        declareClasses(right, "A");
        OWLModelManager mm = hermitModelManager(left);
        InferredDiffOrchestrator.Captured captured =
                InferredDiffOrchestrator.capture(mm, left, right, false, "HermiT");
        InferredDiffOrchestrator.StagePlan plan =
                new InferredDiffOrchestrator.StagePlan(java.util.List.of("invariants"));
        plan.invariants = java.util.List.of(new Invariants.Invariant("inferred-only",
                "needs inferences", "error", "SELECT ?c WHERE { ?c a owl:Class }", true));

        Map<String, Object> evaluated = InferredDiffOrchestrator.run(captured, 60_000, 25, plan);

        Map<?, ?> stageDeltas = (Map<?, ?>) evaluated.get("stage_deltas");
        Map<?, ?> invariants = (Map<?, ?>) stageDeltas.get("invariants");
        assertNotNull(invariants.get("error"), stageDeltas::toString);
        Map<String, Object> merged = InferredDiffOrchestrator.mergedCompatibility(null,
                castMap((Map<?, ?>) evaluated.get("inferred")), castMap(stageDeltas));
        assertEquals("potentially_breaking", merged.get("classification"), merged::toString);
        assertTrue(String.valueOf(merged.get("caveat")).contains("'invariants' stage delta errored"),
                String.valueOf(merged.get("caveat")));
    }

    @Test
    void mergedCompatibilityIsForcedByAnErroredStageDeltaNamingTheStage() {
        Map<String, Object> stageDeltas = new java.util.LinkedHashMap<>();
        stageDeltas.put("available", true);
        stageDeltas.put("cqs", Map.of("error", "right side: 1 competency question(s) could not "
                + "execute with their requested data"));

        Map<String, Object> merged = InferredDiffOrchestrator.mergedCompatibility(null,
                Map.of(), stageDeltas);

        assertEquals("potentially_breaking", merged.get("classification"), merged::toString);
        assertTrue(String.valueOf(merged.get("caveat")).contains("'cqs' stage delta errored"),
                String.valueOf(merged.get("caveat")));
    }

    private static io.github.hakjuoh.protege_mcp.policy.ProjectPolicy twoModulePolicy(
            java.nio.file.Path temp) throws Exception {
        writeModule(temp.resolve("a.ttl"), "https://example.org/module-a",
                "<https://example.org/a/Widget> a owl:Class .");
        writeModule(temp.resolve("b.ttl"), "https://example.org/module-b",
                "<https://example.org/b/Widget> a owl:Class .");
        java.nio.file.Path policyPath = temp.resolve("policy.yaml");
        io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.writePolicy(policyPath,
                io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.minimalPolicy(
                        "two-modules", "https://example.org/module-a")
                        + "modules:\n"
                        + "  - ontology_iri: https://example.org/module-a\n"
                        + "    path: a.ttl\n"
                        + "    owned_namespaces: ['https://example.org/a/']\n"
                        + "  - ontology_iri: https://example.org/module-b\n"
                        + "    path: b.ttl\n"
                        + "    owned_namespaces: ['https://example.org/b/']\n"
                        + "validation:\n  required_stages: [governance]\n");
        io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy =
                io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return policy;
    }

    private static void writeModule(java.nio.file.Path path, String ontologyIri, String... turtle)
            throws Exception {
        StringBuilder document = new StringBuilder(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                        + "<" + ontologyIri + "> a owl:Ontology .\n");
        for (String line : turtle) {
            document.append(line).append('\n');
        }
        java.nio.file.Files.writeString(path, document.toString());
    }

    // ------------------------------------------------------------------ fixtures

    private static OWLOntology ontology(String name) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.createOntology(IRI.create("http://example.org/orch-" + name));
    }

    private static void declareClasses(OWLOntology ontology, String... names) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (String name : names) {
            ontology.getOWLOntologyManager().addAxiom(ontology,
                    df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + name))));
        }
    }

    private static org.semanticweb.owlapi.model.OWLClass cls(OWLOntology ontology, String name) {
        return ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(IRI.create(NS + name));
    }

    /** FakeModelManager plus a real HermiT-backed reasoner selection (IsolatedReasonerQcTest style). */
    private static OWLModelManager hermitModelManager(OWLOntology ontology) {
        return hermitModelManager(ontology, "HermiT");
    }

    /**
     * Like {@link #hermitModelManager(OWLOntology)}, but each display name becomes one installed
     * HermiT-backed factory (inventory order preserved); the first one is the current selection.
     */
    private static OWLModelManager hermitModelManager(OWLOntology ontology, String... displayNames) {
        ProtegeOWLReasonerInfo[] infos = new ProtegeOWLReasonerInfo[displayNames.length];
        for (int i = 0; i < displayNames.length; i++) {
            infos[i] = hermitInfo(displayNames[i],
                    "org.semanticweb.HermiT." + displayNames[i].replace(' ', '.'));
        }
        return hermitModelManager(ontology, infos);
    }

    /** The same seam over explicit plugin descriptors (for colliding display names). */
    private static OWLModelManager hermitModelManager(OWLOntology ontology,
            ProtegeOWLReasonerInfo... installedInfos) {
        Set<ProtegeOWLReasonerInfo> installed =
                new java.util.LinkedHashSet<>(java.util.List.of(installedInfos));
        ProtegeOWLReasonerInfo current = installed.iterator().next();
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                InferredDiffOrchestratorTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                    case "getCurrentReasonerFactory" -> current;
                    case "getCurrentReasonerFactoryId" -> current.getReasonerId();
                    case "getCurrentReasonerName" -> current.getReasonerName();
                    case "getInstalledReasonerFactories" -> installed;
                    case "toString" -> "HermitTestReasonerManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OWLModelManager base = FakeModelManager.over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                InferredDiffOrchestratorTest.class.getClassLoader(),
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

    private static ProtegeOWLReasonerInfo hermitInfo(String reasonerName, String reasonerId) {
        OWLReasonerConfiguration configuration = new SimpleConfiguration();
        var factory = new org.semanticweb.HermiT.ReasonerFactory();
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                InferredDiffOrchestratorTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getReasonerId" -> reasonerId;
                    case "getReasonerName" -> reasonerName;
                    case "getReasonerFactory" -> factory;
                    case "getRecommendedBuffering" -> BufferingMode.NON_BUFFERING;
                    case "getConfiguration" -> configuration;
                    case "toString" -> reasonerName + " test info";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
