package io.github.hakjuoh.protege_mcp.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLIArgument;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;

class RuleValidationServiceTest {

    private static final String NS = "https://example.org/";
    private static final String HERMIT_DIGEST =
            "sha256:5028991751713b34e006eda5871266226c12b148a44b4847e9974a0ccc4645dd";
    private static final String HERMIT_BINARY =
            "sha256:26e6119163fd1249797553488fd9a531578380fff442c36c57d930066e186da9";
    private static final String HERMIT_CONFIGURATION_BINARY =
            "sha256:b9fe7e5c8517cc59906e12ddcecd4f03311ef082d49e6cfe6784b4f8f923a7b6";
    private static final String HERMIT_CODE_DIGEST =
            "sha256:41d6c9cdd95485aeee392c6a247e6da22428ed92cad7a5ebbe60953a043f4239";
    private static final String HERMIT_SEMANTIC =
            "sha256:264a41ff2fbe8878acc9007aa184a549b08f458a42fc1ed9fe0bcb5c0303ab86";
    private static final List<String> HERMIT_CODE_SCOPES = List.of(
            "org/semanticweb/HermiT/**", "rationals/**", "dk/brics/automaton/**",
            "org/apache/axiom/**", "org/semanticweb/owlapi/**");
    private static final String DIGEST = "sha256:" + "1".repeat(64);
    private final ReasonerCapabilityRegistry registry = new ReasonerCapabilityRegistry();

    @Test
    void hermitRuleReportsEngineDlSafetySeparatelyFromBodyVariableSafety() throws Exception {
        Fixture fixture = fixture();
        SWRLVariable x = fixture.variable("x");
        SWRLRule rule = fixture.data.getSWRLRule(
                Set.of(fixture.data.getSWRLClassAtom(fixture.data.getOWLClass(iri("A")), x)),
                Set.of(fixture.data.getSWRLClassAtom(fixture.data.getOWLClass(iri("B")), x)));
        fixture.manager.addAxiom(fixture.ontology, rule);

        Map<String, Object> result = RuleValidationService.validate(
                RuleValidationService.capture(Set.of(fixture.ontology)), hermit(), 0, 10);

        assertEquals(false, result.get("executed_rules"));
        assertEquals(true, result.get("parsed_every_atom"));
        assertEquals(true, result.get("compatible"));
        Map<?, ?> validated = onlyRule(result);
        assertEquals("supported", validated.get("status"));
        assertEquals("supported", validated.get("dl_safety_status"));
        assertEquals(true, validated.get("body_variable_safe"));
        assertTrue(String.valueOf(validated.get("dl_safety_note"))
                .contains("not a syntactic DL-safety proof"));
        assertTrue(ToolSchemaValidator.compile(ReasonerToolSchemas.output("validate_rules"))
                .violations(result).isEmpty());
    }

    @Test
    void builtinAndUnboundVariablesFailClosedAndEveryAtomIsCounted() throws Exception {
        Fixture fixture = fixture();
        SWRLVariable x = fixture.variable("x");
        SWRLVariable y = fixture.variable("y");
        List<SWRLDArgument> arguments = List.of(x, y);
        SWRLAtom builtin = fixture.data.getSWRLBuiltInAtom(
                IRI.create("http://www.w3.org/2003/11/swrlb#add"), arguments);
        SWRLRule rule = fixture.data.getSWRLRule(Set.of(builtin),
                Set.of(fixture.data.getSWRLClassAtom(
                        fixture.data.getOWLClass(iri("Result")), x)));
        fixture.manager.addAxiom(fixture.ontology, rule);

        Map<String, Object> result = RuleValidationService.validate(
                RuleValidationService.capture(Set.of(fixture.ontology)), hermit(), 0, 10);
        Map<?, ?> validated = onlyRule(result);
        assertEquals("unsupported", validated.get("status"));
        assertEquals(false, validated.get("body_variable_safe"));
        assertEquals(2, validated.get("atom_count"));
        assertTrue(validated.toString().contains("body_variable_unbound"));
        assertTrue(validated.toString().contains("atom_unsupported"));
    }

    @Test
    void unlistedSwrlbPredicateIsUnsupportedForAnUnknownReasoner() throws Exception {
        Fixture fixture = fixture();
        SWRLVariable x = fixture.variable("x");
        SWRLRule rule = fixture.data.getSWRLRule(Set.of(
                fixture.data.getSWRLClassAtom(fixture.data.getOWLClass(iri("A")), x),
                fixture.data.getSWRLBuiltInAtom(
                        IRI.create("http://www.w3.org/2003/11/swrlb#readFile"), List.of(x))),
                Set.of(fixture.data.getSWRLClassAtom(fixture.data.getOWLClass(iri("B")), x)));
        fixture.manager.addAxiom(fixture.ontology, rule);
        ReasonerCapabilityReport unknown = registry.report(new ReasonerIdentity(
                "example.Factory", "example.Factory", "unknown", "unknown", List.of(),
                0, "Example", "1.0", "example.Configuration", "unknown",
                "unrecognized", DIGEST, DIGEST,
                0L, "none", "ALLOW", "BY_NAME", "BUFFERING", "test"));

        Map<String, Object> result = RuleValidationService.validate(
                RuleValidationService.capture(Set.of(fixture.ontology)), unknown, 0, 10);
        assertEquals("unsupported", onlyRule(result).get("status"));
        assertEquals(false, result.get("coverage_complete"));
    }

    @Test
    void fingerprintsPreserveBuiltinArgumentOrderWithoutRetainingOwlObjects() throws Exception {
        Fixture fixture = fixture();
        SWRLVariable x = fixture.variable("x");
        SWRLVariable y = fixture.variable("y");
        fixture.manager.addAxiom(fixture.ontology, ruleWithBuiltin(fixture, x, y));
        fixture.manager.addAxiom(fixture.ontology, ruleWithBuiltin(fixture, y, x));

        RuleValidationService.CapturedCorpus corpus =
                RuleValidationService.capture(Set.of(fixture.ontology));
        Map<String, Object> result = RuleValidationService.validate(corpus, hermit(), 0, 10);
        List<?> rules = (List<?>) result.get("rules");
        assertNotEquals(((Map<?, ?>) rules.get(0)).get("rule_id"),
                ((Map<?, ?>) rules.get(1)).get("rule_id"));
        for (var field : corpus.getClass().getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("org.semanticweb.owlapi"));
        }
    }

    @Test
    void captureDeduplicatesRulesAndPaginationIsSnapshotBound() throws Exception {
        Fixture fixture = fixture();
        OWLOntology second = fixture.manager.createOntology(iri("second"));
        SWRLVariable x = fixture.variable("x");
        SWRLRule shared = fixture.data.getSWRLRule(
                Set.of(fixture.data.getSWRLClassAtom(fixture.data.getOWLClass(iri("A")), x)),
                Set.of(fixture.data.getSWRLClassAtom(fixture.data.getOWLClass(iri("B")), x)));
        fixture.manager.addAxiom(fixture.ontology, shared);
        fixture.manager.addAxiom(second, shared);
        for (int index = 0; index < 3; index++) {
            fixture.manager.addAxiom(fixture.ontology, fixture.data.getSWRLRule(
                    Set.of(fixture.data.getSWRLClassAtom(
                            fixture.data.getOWLClass(iri("C" + index)), x)),
                    Set.of(fixture.data.getSWRLClassAtom(
                            fixture.data.getOWLClass(iri("D" + index)), x))));
        }
        RuleValidationService.CapturedCorpus corpus =
                RuleValidationService.capture(Set.of(second, fixture.ontology));
        assertEquals(4, corpus.totalRules());

        Map<String, Object> first = RuleValidationService.validate(corpus, hermit(), 0, 2);
        String snapshot = String.valueOf(first.get("snapshot_fingerprint"));
        Map<String, Object> secondPage =
                RuleValidationService.validate(corpus, hermit(), 2, 2, snapshot);
        assertEquals(2, first.get("next_offset"));
        assertFalse(secondPage.containsKey("next_offset"));
        Set<Object> ids = new LinkedHashSet<>();
        for (Map<String, Object> page : List.of(first, secondPage)) {
            for (Object row : (List<?>) page.get("rules")) {
                ids.add(((Map<?, ?>) row).get("rule_id"));
            }
        }
        assertEquals(4, ids.size());
        assertThrows(IllegalArgumentException.class,
                () -> RuleValidationService.validate(corpus, hermit(), 2, 2, DIGEST));
        assertThrows(RuleValidationService.SnapshotRequiredException.class,
                () -> RuleValidationService.validate(corpus, hermit(), 2, 2));
    }

    @Test
    void semanticRuleFingerprintIgnoresSetIterationOrder() throws Exception {
        Fixture left = fixture();
        Fixture right = fixture();
        SWRLVariable leftX = left.variable("x");
        SWRLVariable rightX = right.variable("x");
        LinkedHashSet<SWRLAtom> leftBody = new LinkedHashSet<>();
        leftBody.add(left.data.getSWRLClassAtom(left.data.getOWLClass(iri("A")), leftX));
        leftBody.add(left.data.getSWRLClassAtom(left.data.getOWLClass(iri("B")), leftX));
        LinkedHashSet<SWRLAtom> rightBody = new LinkedHashSet<>();
        rightBody.add(right.data.getSWRLClassAtom(right.data.getOWLClass(iri("B")), rightX));
        rightBody.add(right.data.getSWRLClassAtom(right.data.getOWLClass(iri("A")), rightX));
        left.manager.addAxiom(left.ontology, left.data.getSWRLRule(leftBody,
                Set.of(left.data.getSWRLClassAtom(left.data.getOWLClass(iri("C")), leftX))));
        right.manager.addAxiom(right.ontology, right.data.getSWRLRule(rightBody,
                Set.of(right.data.getSWRLClassAtom(right.data.getOWLClass(iri("C")), rightX))));

        Object leftId = onlyRule(RuleValidationService.validate(
                RuleValidationService.capture(Set.of(left.ontology)), hermit(), 0, 10))
                .get("rule_id");
        Object rightId = onlyRule(RuleValidationService.validate(
                RuleValidationService.capture(Set.of(right.ontology)), hermit(), 0, 10))
                .get("rule_id");
        assertEquals(leftId, rightId);
    }

    @Test
    void ontologyVersionIriParticipatesInTheSnapshotFingerprint() throws Exception {
        Fixture left = versionedFixture("v1");
        Fixture right = versionedFixture("v2");
        addSimpleRule(left);
        addSimpleRule(right);

        Object leftSnapshot = RuleValidationService.validate(
                RuleValidationService.capture(Set.of(left.ontology)), hermit(), 0, 10)
                .get("snapshot_fingerprint");
        Object rightSnapshot = RuleValidationService.validate(
                RuleValidationService.capture(Set.of(right.ontology)), hermit(), 0, 10)
                .get("snapshot_fingerprint");
        assertNotEquals(leftSnapshot, rightSnapshot);
    }

    @Test
    void sourceScopeParticipatesEvenWhenTheSelectedOntologiesAreTheSame() throws Exception {
        Fixture fixture = fixture();
        addSimpleRule(fixture);
        Set<OWLOntology> sameSources = Set.of(fixture.ontology);
        Object active = RuleValidationService.validate(RuleValidationService.capture(
                        RuleValidationService.snapshot(sameSources, false)),
                hermit(), 0, 10).get("snapshot_fingerprint");
        Object closure = RuleValidationService.validate(RuleValidationService.capture(
                        RuleValidationService.snapshot(sameSources, true)),
                hermit(), 0, 10).get("snapshot_fingerprint");
        assertNotEquals(active, closure);
    }

    @Test
    void combinedBodyAndHeadAtomLimitMatchesTheOutputContract() throws Exception {
        Fixture fixture = fixture();
        SWRLVariable x = fixture.variable("x");
        Set<SWRLAtom> body = classAtoms(fixture, x, "body", 256);
        Set<SWRLAtom> head = classAtoms(fixture, x, "head", 257);
        fixture.manager.addAxiom(fixture.ontology, fixture.data.getSWRLRule(body, head));

        RuleValidationService.BudgetExceededException exceeded = assertThrows(
                RuleValidationService.BudgetExceededException.class,
                () -> RuleValidationService.capture(Set.of(fixture.ontology)));
        assertEquals("atoms_per_rule", exceeded.budget());
        assertEquals(513, exceeded.observed());
    }

    @Test
    void maximumAcceptedRuleStaysInsideSchemaAndTransportBounds() throws Exception {
        Fixture fixture = fixture();
        SWRLVariable x = fixture.variable("x");
        Set<SWRLAtom> body = builtins(fixture, x, "body", 256);
        Set<SWRLAtom> head = builtins(fixture, x, "head", 256);
        fixture.manager.addAxiom(fixture.ontology, fixture.data.getSWRLRule(body, head));

        Map<String, Object> result = RuleValidationService.validate(
                RuleValidationService.capture(Set.of(fixture.ontology)), hermit(), 0, 10);
        Map<?, ?> rule = onlyRule(result);
        assertEquals(512, rule.get("atom_count"));
        assertEquals(513, rule.get("finding_count"));
        assertEquals(true, rule.get("findings_truncated"));
        assertTrue(ToolSchemaValidator.compile(ReasonerToolSchemas.output("validate_rules"))
                .violations(result).isEmpty());
        assertTrue(ContractJson.mapper().writeValueAsBytes(result).length < 8 * 1024 * 1024);
    }

    @Test
    void maximumIncompatibleCorpusSummariesStayInsideTransportBounds() throws Exception {
        Fixture fixture = fixture();
        SWRLVariable x = fixture.variable("x");
        for (int index = 0; index < RuleValidationService.MAX_UNIQUE_RULES; index++) {
            fixture.manager.addAxiom(fixture.ontology, fixture.data.getSWRLRule(
                    Set.of(fixture.data.getSWRLBuiltInAtom(
                            iri("builtin/max/" + index), List.of(x))),
                    Set.of(fixture.data.getSWRLClassAtom(
                            fixture.data.getOWLClass(iri("Result/max/" + index)), x))));
        }

        Map<String, Object> result = RuleValidationService.validate(
                RuleValidationService.capture(Set.of(fixture.ontology)), hermit(), 0, 10);
        assertEquals(RuleValidationService.MAX_UNIQUE_RULES,
                result.get("incompatible_rule_count"));
        assertEquals(RuleValidationService.MAX_UNIQUE_RULES,
                ((List<?>) result.get("incompatible_rule_summaries")).size());
        List<?> summaries = (List<?>) result.get("incompatible_rule_summaries");
        Set<?> summaryIds = summaries.stream().map(item -> ((Map<?, ?>) item).get("rule_id"))
                .collect(java.util.stream.Collectors.toSet());
        Set<?> pageIds = ((List<?>) result.get("rules")).stream()
                .map(item -> ((Map<?, ?>) item).get("rule_id"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(RuleValidationService.MAX_UNIQUE_RULES, summaryIds.size());
        assertTrue(summaryIds.stream().anyMatch(id -> !pageIds.contains(id)));
        assertTrue(ContractJson.mapper().writeValueAsBytes(result).length < 8 * 1024 * 1024);
        assertTrue(ToolSchemaValidator.compile(ReasonerToolSchemas.output("validate_rules"))
                .violations(result).isEmpty());
    }

    @Test
    void injectedLimitsExerciseEveryGlobalCaptureGuard() throws Exception {
        Fixture fixture = fixture();
        addSimpleRule(fixture);
        RuleValidationService.RuleSnapshot snapshot =
                RuleValidationService.snapshot(Set.of(fixture.ontology));
        RuleValidationService.CaptureLimits base = limits(10, 10, 10, 10,
                100_000, 1_000, 100, 100_000, 10_000);

        assertBudget("total_atoms", snapshot,
                limits(10, 10, 1, 10, 100_000, 1_000, 100, 100_000, 10_000),
                System::nanoTime);
        assertBudget("total_arguments", snapshot,
                limits(10, 10, 10, 1, 100_000, 1_000, 100, 100_000, 10_000),
                System::nanoTime);
        assertBudget("canonical_object_characters", snapshot,
                limits(10, 10, 10, 10, 1, 1_000, 100, 100_000, 10_000),
                System::nanoTime);
        assertBudget("canonical_object_nodes", snapshot,
                limits(10, 10, 10, 10, 100_000, 1, 100, 100_000, 10_000),
                System::nanoTime);
        assertBudget("canonical_utf8_bytes", snapshot,
                limits(10, 10, 10, 10, 100_000, 1_000, 100, 1, 10_000),
                System::nanoTime);
        AtomicLong clock = new AtomicLong();
        assertBudget("capture_millis", snapshot, base,
                () -> clock.getAndAdd(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20_000)));

        Fixture twoRules = fixture();
        addSimpleRule(twoRules);
        SWRLVariable y = twoRules.variable("y");
        twoRules.manager.addAxiom(twoRules.ontology, twoRules.data.getSWRLRule(
                Set.of(twoRules.data.getSWRLClassAtom(
                        twoRules.data.getOWLClass(iri("OtherA")), y)),
                Set.of(twoRules.data.getSWRLClassAtom(
                        twoRules.data.getOWLClass(iri("OtherB")), y))));
        assertBudget("unique_rules", RuleValidationService.snapshot(Set.of(twoRules.ontology)),
                limits(1, 10, 10, 10, 100_000, 1_000, 100, 100_000, 10_000),
                System::nanoTime);

        Fixture deep = fixture();
        SWRLVariable deepX = deep.variable("deep");
        org.semanticweb.owlapi.model.OWLClassExpression expression =
                deep.data.getOWLClass(iri("Leaf"));
        var property = deep.data.getOWLObjectProperty(iri("nested"));
        for (int index = 0; index < 4; index++) {
            expression = deep.data.getOWLObjectSomeValuesFrom(property, expression);
        }
        deep.manager.addAxiom(deep.ontology, deep.data.getSWRLRule(
                Set.of(deep.data.getSWRLClassAtom(expression, deepX)),
                Set.of(deep.data.getSWRLClassAtom(deep.data.getOWLClass(iri("Result")), deepX))));
        assertBudget("canonical_object_depth",
                RuleValidationService.snapshot(Set.of(deep.ontology)),
                limits(10, 10, 10, 10, 100_000, 1_000, 1, 100_000, 10_000),
                System::nanoTime);

        Fixture annotated = fixture();
        SWRLVariable annotatedX = annotated.variable("annotated");
        var annotationProperty = annotated.data.getOWLAnnotationProperty(
                iri("nestedAnnotation"));
        org.semanticweb.owlapi.model.OWLAnnotation nestedAnnotation =
                annotated.data.getOWLAnnotation(annotationProperty,
                        annotated.data.getOWLLiteral("leaf"));
        for (int index = 0; index < 4; index++) {
            nestedAnnotation = annotated.data.getOWLAnnotation(annotationProperty,
                    annotated.data.getOWLLiteral("level-" + index),
                    Set.of(nestedAnnotation));
        }
        annotated.manager.addAxiom(annotated.ontology, annotated.data.getSWRLRule(
                Set.of(annotated.data.getSWRLClassAtom(
                        annotated.data.getOWLClass(iri("AnnotatedA")), annotatedX)),
                Set.of(annotated.data.getSWRLClassAtom(
                        annotated.data.getOWLClass(iri("AnnotatedB")), annotatedX)),
                Set.of(nestedAnnotation)));
        assertBudget("canonical_object_depth",
                RuleValidationService.snapshot(Set.of(annotated.ontology)),
                limits(10, 10, 10, 10, 100_000, 1_000, 2, 100_000, 10_000),
                System::nanoTime);

        Fixture dag = fixture();
        SWRLVariable dagX = dag.variable("dag");
        org.semanticweb.owlapi.model.OWLClassExpression shared =
                dag.data.getOWLClass(iri("DagLeaf"));
        var dagProperty = dag.data.getOWLObjectProperty(iri("dagProperty"));
        for (int index = 0; index < 18; index++) {
            var previous = shared;
            shared = dag.data.getOWLObjectIntersectionOf(Set.of(previous,
                    dag.data.getOWLObjectSomeValuesFrom(dagProperty, previous)));
        }
        dag.manager.addAxiom(dag.ontology, dag.data.getSWRLRule(
                Set.of(dag.data.getSWRLClassAtom(shared, dagX)),
                Set.of(dag.data.getSWRLClassAtom(
                        dag.data.getOWLClass(iri("DagResult")), dagX))));
        assertBudget("canonical_object_nodes",
                RuleValidationService.snapshot(Set.of(dag.ontology)),
                limits(10, 10, 10, 10, 100_000, 100, 128, 100_000, 10_000),
                System::nanoTime);
    }

    @Test
    void occurrencePreflightRejectsBeforeRuleAxiomsAreCopied() {
        AtomicBoolean copied = new AtomicBoolean();
        OWLOntology oversized = (OWLOntology) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OWLOntology.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAxiomCount" -> RuleValidationService.MAX_RULE_OCCURRENCES + 1;
                    case "getAxioms" -> {
                        copied.set(true);
                        throw new AssertionError("rule axioms must not be copied after preflight");
                    }
                    case "toString" -> "OversizedRuleOntology";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RuleValidationService.BudgetExceededException exceeded = assertThrows(
                RuleValidationService.BudgetExceededException.class,
                () -> RuleValidationService.snapshot(Set.of(oversized)));
        assertEquals("rule_occurrences", exceeded.budget());
        assertFalse(copied.get());
    }

    @Test
    void ruleAnnotationLimitIsEnforced() throws Exception {
        Fixture fixture = fixture();
        SWRLVariable x = fixture.variable("annotation");
        Set<org.semanticweb.owlapi.model.OWLAnnotation> annotations = new LinkedHashSet<>();
        var property = fixture.data.getOWLAnnotationProperty(iri("annotationProperty"));
        for (int index = 0; index <= RuleValidationService.MAX_RULE_ANNOTATIONS; index++) {
            annotations.add(fixture.data.getOWLAnnotation(property,
                    fixture.data.getOWLLiteral("annotation-" + index)));
        }
        SWRLRule rule = fixture.data.getSWRLRule(
                Set.of(fixture.data.getSWRLClassAtom(
                        fixture.data.getOWLClass(iri("AnnotationA")), x)),
                Set.of(fixture.data.getSWRLClassAtom(
                        fixture.data.getOWLClass(iri("AnnotationB")), x)), annotations);
        fixture.manager.addAxiom(fixture.ontology, rule);
        RuleValidationService.BudgetExceededException exceeded = assertThrows(
                RuleValidationService.BudgetExceededException.class,
                () -> RuleValidationService.capture(Set.of(fixture.ontology)));
        assertEquals("rule_annotations", exceeded.budget());
    }

    @Test
    void hardCorpusAndTransportBoundsFailBeforePartialResults() throws Exception {
        Fixture fixture = fixture();
        List<OWLOntology> tooMany = new ArrayList<>();
        tooMany.add(fixture.ontology);
        for (int index = 0; index < RuleValidationService.MAX_SOURCE_ONTOLOGIES; index++) {
            tooMany.add(fixture.manager.createOntology(iri("source/" + index)));
        }
        RuleValidationService.BudgetExceededException exceeded = assertThrows(
                RuleValidationService.BudgetExceededException.class,
                () -> RuleValidationService.capture(tooMany));
        assertEquals("source_ontologies", exceeded.budget());

        Map<String, Object> empty = RuleValidationService.validate(
                RuleValidationService.capture(Set.of(fixture.ontology)), hermit(), 0, 10);
        assertTrue(ContractJson.mapper().writeValueAsBytes(empty).length < 8 * 1024 * 1024);
        assertEquals(RuleValidationService.MAX_TOTAL_ATOMS,
                ((Map<?, ?>) empty.get("capture_limits")).get("total_atoms"));
        assertEquals(RuleValidationService.MAX_SOURCE_IDENTIFIER_CHARACTERS,
                ((Map<?, ?>) empty.get("capture_limits")).get(
                        "source_identifier_characters"));
    }

    @Test
    void liveImportsTraversalRejectsTheSourceLimitBeforeMaterializingAClosure()
            throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology previous = manager.createOntology(iri("closure/0"));
        OWLOntology root = previous;
        for (int index = 1; index <= RuleValidationService.MAX_SOURCE_ONTOLOGIES; index++) {
            IRI nextIri = iri("closure/" + index);
            manager.createOntology(nextIri);
            manager.applyChange(new AddImport(previous,
                    manager.getOWLDataFactory().getOWLImportsDeclaration(nextIri)));
            previous = manager.getOntology(nextIri);
        }

        RuleValidationService.BudgetExceededException exceeded = assertThrows(
                RuleValidationService.BudgetExceededException.class,
                () -> RuleValidationService.boundedImportsClosure(root));
        assertEquals("source_ontologies", exceeded.budget());
    }

    @Test
    void sourceIdentifierLengthIsBoundedBeforeFingerprinting() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/"
                + "a".repeat(RuleValidationService.MAX_SOURCE_IDENTIFIER_CHARACTERS + 1)));

        RuleValidationService.BudgetExceededException exceeded = assertThrows(
                RuleValidationService.BudgetExceededException.class,
                () -> RuleValidationService.snapshot(Set.of(ontology), false));
        assertEquals("source_identifier_characters", exceeded.budget());
    }

    private ReasonerCapabilityReport hermit() {
        return registry.report(new ReasonerIdentity(
                "org.semanticweb.HermiT", "org.semanticweb.HermiT.ReasonerFactory",
                HERMIT_BINARY, HERMIT_CODE_DIGEST, HERMIT_CODE_SCOPES, 2_635, "HermiT",
                "1.3.8.431", "org.semanticweb.HermiT.Configuration",
                HERMIT_CONFIGURATION_BINARY, "factory_default",
                HERMIT_DIGEST, HERMIT_SEMANTIC, 0L, "none", "ALLOW", "BY_NAME",
                "BUFFERING", "test"));
    }

    private static SWRLRule ruleWithBuiltin(Fixture fixture, SWRLDArgument first,
            SWRLDArgument second) {
        SWRLAtom guard = fixture.data.getSWRLClassAtom(
                fixture.data.getOWLClass(iri("A")), (SWRLIArgument) first);
        SWRLAtom builtin = fixture.data.getSWRLBuiltInAtom(
                IRI.create("http://www.w3.org/2003/11/swrlb#subtract"),
                List.of(first, second));
        return fixture.data.getSWRLRule(Set.of(guard, builtin),
                Set.of(fixture.data.getSWRLClassAtom(
                        fixture.data.getOWLClass(iri("B")), (SWRLIArgument) first)));
    }

    private static void assertBudget(String expected,
            RuleValidationService.RuleSnapshot snapshot,
            RuleValidationService.CaptureLimits limits,
            java.util.function.LongSupplier nanoTime) {
        RuleValidationService.BudgetExceededException exceeded = assertThrows(
                RuleValidationService.BudgetExceededException.class,
                () -> RuleValidationService.capture(snapshot, limits, nanoTime));
        assertEquals(expected, exceeded.budget());
    }

    private static RuleValidationService.CaptureLimits limits(int uniqueRules,
            int atomsPerRule, int totalAtoms, int totalArguments, int characters,
            int nodes, int depth, int bytes, long millis) {
        return new RuleValidationService.CaptureLimits(uniqueRules, atomsPerRule,
                totalAtoms, totalArguments, characters, nodes, depth, bytes, millis);
    }

    private static Set<SWRLAtom> classAtoms(Fixture fixture, SWRLVariable x,
            String prefix, int count) {
        Set<SWRLAtom> atoms = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            atoms.add(fixture.data.getSWRLClassAtom(
                    fixture.data.getOWLClass(iri(prefix + index)), x));
        }
        return atoms;
    }

    private static Set<SWRLAtom> builtins(Fixture fixture, SWRLVariable x,
            String prefix, int count) {
        Set<SWRLAtom> atoms = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            atoms.add(fixture.data.getSWRLBuiltInAtom(
                    iri("builtin/" + prefix + index), List.of(x)));
        }
        return atoms;
    }

    private static void addSimpleRule(Fixture fixture) {
        SWRLVariable x = fixture.variable("x");
        fixture.manager.addAxiom(fixture.ontology, fixture.data.getSWRLRule(
                Set.of(fixture.data.getSWRLClassAtom(fixture.data.getOWLClass(iri("A")), x)),
                Set.of(fixture.data.getSWRLClassAtom(fixture.data.getOWLClass(iri("B")), x))));
    }

    private static Fixture versionedFixture(String version) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntologyID id = new OWLOntologyID(iri("versioned"), iri(version));
        return new Fixture(manager, manager.createOntology(id), manager.getOWLDataFactory());
    }

    private static Map<?, ?> onlyRule(Map<String, Object> result) {
        return (Map<?, ?>) ((List<?>) result.get("rules")).get(0);
    }

    private static Fixture fixture() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        return new Fixture(manager, manager.createOntology(iri("ontology")),
                manager.getOWLDataFactory());
    }

    private static IRI iri(String local) {
        return IRI.create(NS + local);
    }

    private record Fixture(org.semanticweb.owlapi.model.OWLOntologyManager manager,
            OWLOntology ontology, OWLDataFactory data) {
        SWRLVariable variable(String name) {
            return data.getSWRLVariable(iri("var/" + name));
        }
    }
}
