package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * Coverage for two 0.4.3 additions in {@link ReasonerTools}:
 *
 * <p>(1) {@link ReasonerTools#findInconsistentSubset} — the {@code explain_inconsistency}
 * contraction search. Its sole oracle is {@code OWLReasoner.isConsistent()}, which the headless
 * {@code StructuralReasoner} answers {@code true} for everything, so these tests use the
 * test-scoped REAL HermiT (the {@link ApplyVerifyRealReasonerTest} pattern) over hand-built
 * inconsistent ontologies: a known 3-axiom contradiction buried in padding must minimize to
 * EXACTLY that core; a zero budget must stop early but never surrender the jointly-inconsistent
 * invariant; two independent contradictions must yield exactly ONE complete core. The
 * {@link ReasonerTools#inconsistencyJson} rendering is exercised over {@link FakeModelManager}.
 *
 * <p>(2) {@link ReasonerTools#swrlIgnoredWarning} — the run_reasoner advisory for reasoners that
 * silently ignore SWRL rules (ELK). The manager-consulting overload needs
 * {@code getOWLReasonerManager()}, which {@link FakeModelManager} deliberately throws on, so those
 * tests build a local {@link Proxy} pair (model manager + reasoner manager) around a real
 * in-memory ontology carrying a SWRL rule; the rules==0 short-circuit is proven precisely BY the
 * throwing fake (a pass means the reasoner manager was never consulted).
 */
class InconsistencyAndSwrlWarningTest {

    private static final String NS = "http://example.org/incons#";
    private static final OWLReasonerFactory REASONER_FACTORY = new org.semanticweb.HermiT.ReasonerFactory();

    private final OWLOntologyManager mgr = OwlManagers.create();
    private final OWLDataFactory df = mgr.getOWLDataFactory();

    private OWLClass cls(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    /** The canonical 3-axiom contradiction: i is asserted into both of two disjoint classes. */
    private Set<OWLAxiom> disjointCore(String c1, String c2, String ind) {
        OWLClass a = cls(c1);
        OWLClass b = cls(c2);
        OWLNamedIndividual i = df.getOWLNamedIndividual(IRI.create(NS + ind));
        return new LinkedHashSet<>(Arrays.asList(
                df.getOWLClassAssertionAxiom(a, i),
                df.getOWLClassAssertionAxiom(b, i),
                df.getOWLDisjointClassesAxiom(a, b)));
    }

    /** {@code n} unrelated SubClassOf axioms over fresh classes — pure minimization noise. */
    private List<OWLAxiom> padding(int n) {
        List<OWLAxiom> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(df.getOWLSubClassOfAxiom(cls("Pad" + i), cls("PadSuper" + i)));
        }
        return out;
    }

    // ------------------------------------------------------- findInconsistentSubset: consistent

    @Test
    void consistentOntologyReportsConsistentWithNoAxioms() throws OWLOntologyCreationException {
        OWLOntology working = mgr.createOntology(IRI.create(NS + "consistent"));
        mgr.addAxiom(working, df.getOWLSubClassOfAxiom(cls("A"), cls("B")));

        ReasonerTools.InconsistencySearch s =
                ReasonerTools.findInconsistentSubset(working, REASONER_FACTORY, 30_000);

        assertTrue(s.consistent, "HermiT should find a plain SubClassOf ontology consistent");
        assertTrue(s.axioms.isEmpty(), "a consistent result carries no justification axioms");
        assertFalse(s.minimal, "minimal is only meaningful for an inconsistent result");
        assertEquals(1, s.checks, "the consistent verdict needs exactly the one initial oracle call");
    }

    // ---------------------------------------------- findInconsistentSubset: buried 3-axiom core

    @Test
    void minimizesBuriedCoreToExactlyTheThreeContradictionAxioms()
            throws OWLOntologyCreationException {
        OWLOntology working = mgr.createOntology(IRI.create(NS + "buried"));
        Set<OWLAxiom> core = disjointCore("A", "B", "i");
        for (OWLAxiom ax : core) {
            mgr.addAxiom(working, ax);
        }
        for (OWLAxiom ax : padding(8)) {
            mgr.addAxiom(working, ax);
        }

        ReasonerTools.InconsistencySearch s =
                ReasonerTools.findInconsistentSubset(working, REASONER_FACTORY, 30_000);

        assertFalse(s.consistent, "the buried disjointness contradiction must be detected");
        assertTrue(s.minimal, "30s is ample to fully minimize 11 tiny axioms");
        assertTrue(s.checks > 0, "minimization must have consulted the consistency oracle");
        assertEquals(core, new LinkedHashSet<>(s.axioms),
                "the minimal set is EXACTLY the 3 core axioms, every padding axiom pruned");
    }

    // ------------------------------------------------- findInconsistentSubset: budget expiry

    @Test
    void zeroBudgetStopsUnminimizedButKeepsAJointlyInconsistentSet()
            throws OWLOntologyCreationException {
        OWLOntology working = mgr.createOntology(IRI.create(NS + "expired"));
        Set<OWLAxiom> core = disjointCore("A", "B", "i");
        for (OWLAxiom ax : core) {
            mgr.addAxiom(working, ax);
        }
        for (OWLAxiom ax : padding(8)) {
            mgr.addAxiom(working, ax);
        }

        ReasonerTools.InconsistencySearch s =
                ReasonerTools.findInconsistentSubset(working, REASONER_FACTORY, 0);

        assertFalse(s.consistent, "expiry must not misreport the ontology as consistent");
        assertFalse(s.minimal, "budget 0 must expire before minimization completes");
        Set<OWLAxiom> got = new LinkedHashSet<>(s.axioms);
        assertTrue(got.containsAll(core),
                "the unminimized result must still contain the contradiction core; got " + got);

        // Re-verify the invariant with a FRESH HermiT over JUST the returned axioms: whatever the
        // search hands back on expiry must itself be jointly inconsistent.
        OWLOntologyManager fresh = OWLManager.createOWLOntologyManager();
        OWLOntology probe = fresh.createOntology(got);
        OWLReasoner r = REASONER_FACTORY.createReasoner(probe);
        try {
            assertFalse(r.isConsistent(),
                    "expiry must never surrender the jointly-inconsistent invariant");
        } finally {
            r.dispose();
        }

        // The expired result renders with the budget-expired note, not the minimal one.
        Map<String, Object> json =
                ReasonerTools.inconsistencyJson(FakeModelManager.over(working), s, "HermiT");
        assertEquals(Boolean.FALSE, json.get("minimal"));
        assertTrue(String.valueOf(json.get("note")).contains("budget"),
                "non-minimal note explains the time budget expired: " + json.get("note"));
    }

    // ------------------------------------------ findInconsistentSubset: two independent cores

    @Test
    void twoIndependentContradictionsYieldExactlyOneCompleteCore()
            throws OWLOntologyCreationException {
        OWLOntology working = mgr.createOntology(IRI.create(NS + "twin"));
        Set<OWLAxiom> core1 = disjointCore("A", "B", "i");
        Set<OWLAxiom> core2 = disjointCore("C", "D", "j");
        for (OWLAxiom ax : core1) {
            mgr.addAxiom(working, ax);
        }
        for (OWLAxiom ax : core2) {
            mgr.addAxiom(working, ax);
        }
        for (OWLAxiom ax : padding(4)) {
            mgr.addAxiom(working, ax);
        }

        ReasonerTools.InconsistencySearch s =
                ReasonerTools.findInconsistentSubset(working, REASONER_FACTORY, 30_000);

        assertFalse(s.consistent);
        assertTrue(s.minimal, "30s is ample to fully minimize 10 tiny axioms");
        Set<OWLAxiom> got = new LinkedHashSet<>(s.axioms);
        assertEquals(3, got.size(), "a minimal core here is one 3-axiom contradiction; got " + got);
        Set<OWLAxiom> union = new LinkedHashSet<>(core1);
        union.addAll(core2);
        assertTrue(union.containsAll(got),
                "the minimal result must draw only from the two known cores; got " + got);
        assertTrue(got.equals(core1) || got.equals(core2),
                "the minimal result is ONE complete core, never a mix of both; got " + got);
    }

    // ------------------------------------------------- findInconsistentSubset: reasoner failure

    @Test
    void initialProbeFailureIsAnErrorNotAConsistentVerdict() throws OWLOntologyCreationException {
        // 0.4.3 review fix: a reasoner that cannot evaluate the FULL axiom set (e.g. HermiT
        // rejecting a SWRL built-in atom) must surface as an error — swallowing it would report
        // "consistent — nothing to explain" about an ontology the reasoner never judged.
        OWLOntology working = mgr.createOntology(IRI.create(NS + "throwing"));
        mgr.addAxiom(working, df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        OWLReasonerFactory throwing = (OWLReasonerFactory) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { OWLReasonerFactory.class },
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("built-in atom not supported");
                });

        ToolArgException e = org.junit.jupiter.api.Assertions.assertThrows(ToolArgException.class,
                () -> ReasonerTools.findInconsistentSubset(working, throwing, 30_000));
        assertTrue(e.getMessage().contains("could not evaluate the ontology"),
                "names the failure: " + e.getMessage());
        assertTrue(e.getMessage().contains("set_reasoner"), "points at the remedy: " + e.getMessage());
    }

    // ----------------------------------------------------- inconsistencyJson render cap (0.4.3)

    @Test
    void inconsistencyJsonCapsTheRenderedJustificationButReportsTheTrueCount()
            throws OWLOntologyCreationException {
        // 0.4.3 review fix: a budget-expired result can still hold thousands of axioms, and
        // axiomList renders every input axiom before windowing — the collection must be capped
        // BEFORE rendering, with the true size in axiom_count.
        OWLOntology working = mgr.createOntology(IRI.create(NS + "capped"));
        ReasonerTools.InconsistencySearch s = new ReasonerTools.InconsistencySearch();
        s.minimal = false;
        s.checks = 7;
        for (OWLAxiom ax : padding(150)) {
            s.axioms.add(ax);
        }

        Map<String, Object> json =
                ReasonerTools.inconsistencyJson(FakeModelManager.over(working), s, "HermiT");

        assertEquals(150, json.get("axiom_count"), "the true set size is always reported");
        @SuppressWarnings("unchecked")
        Map<String, Object> just = (Map<String, Object>) json.get("justification");
        assertEquals(100, ((List<?>) just.get("items")).size(), "rendering capped at 100");
        assertTrue(String.valueOf(json.get("note")).contains("time budget"),
                "budget-expiry note: " + json.get("note"));
    }

    @Test
    void inconsistencyJsonReportsAxiomCountForMinimalSets() throws OWLOntologyCreationException {
        OWLOntology working = mgr.createOntology(IRI.create(NS + "minicount"));
        ReasonerTools.InconsistencySearch s = new ReasonerTools.InconsistencySearch();
        s.minimal = true;
        s.checks = 3;
        s.axioms.addAll(disjointCore("A", "B", "i"));

        Map<String, Object> json =
                ReasonerTools.inconsistencyJson(FakeModelManager.over(working), s, "HermiT");

        assertEquals(3, json.get("axiom_count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> just = (Map<String, Object>) json.get("justification");
        assertEquals(3, just.get("count"), "below the cap the list is complete");
    }

    // -------------------------------------------------------------- inconsistencyJson rendering

    @Test
    void inconsistencyJsonRendersJustificationChecksAndMinimalNote()
            throws OWLOntologyCreationException {
        OWLOntology working = mgr.createOntology(IRI.create(NS + "render"));
        Set<OWLAxiom> core = disjointCore("A", "B", "i");
        for (OWLAxiom ax : core) {
            mgr.addAxiom(working, ax);
        }
        for (OWLAxiom ax : padding(8)) {
            mgr.addAxiom(working, ax);
        }
        ReasonerTools.InconsistencySearch s =
                ReasonerTools.findInconsistentSubset(working, REASONER_FACTORY, 30_000);

        Map<String, Object> json =
                ReasonerTools.inconsistencyJson(FakeModelManager.over(working), s, "HermiT");

        assertEquals(Boolean.TRUE, json.get("inconsistent"));
        assertEquals("HermiT", json.get("reasoner"));
        assertEquals(Boolean.TRUE, json.get("minimal"));
        assertInstanceOf(Integer.class, json.get("consistency_checks"));
        assertTrue((Integer) json.get("consistency_checks") > 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> just = (Map<String, Object>) json.get("justification");
        // axiomList shape: {count, items:[{axiom_type, rendering}...]} — 3 core axioms, uncapped.
        assertEquals(3, just.get("count"));
        List<?> items = (List<?>) just.get("items");
        assertEquals(3, items.size());
        assertFalse(just.containsKey("truncated"), "3 axioms are far below the render cap");
        Set<Object> types = new LinkedHashSet<>();
        for (Object item : items) {
            Map<?, ?> m = (Map<?, ?>) item;
            types.add(m.get("axiom_type"));
            assertInstanceOf(String.class, m.get("rendering"));
        }
        assertEquals(new LinkedHashSet<>(Arrays.asList("ClassAssertion", "DisjointClasses")), types);
        String note = (String) json.get("note");
        assertNotNull(note);
        assertTrue(note.contains("minimal"), "minimal note explains the guarantee: " + note);
    }

    @Test
    void inconsistencyJsonConsistentVariantSaysConsistent() throws OWLOntologyCreationException {
        OWLOntology working = mgr.createOntology(IRI.create(NS + "renderok"));
        mgr.addAxiom(working, df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        ReasonerTools.InconsistencySearch s =
                ReasonerTools.findInconsistentSubset(working, REASONER_FACTORY, 30_000);
        assertTrue(s.consistent);

        Map<String, Object> json =
                ReasonerTools.inconsistencyJson(FakeModelManager.over(working), s, "HermiT");

        assertEquals(Boolean.FALSE, json.get("inconsistent"));
        assertEquals("HermiT", json.get("reasoner"));
        assertTrue(String.valueOf(json.get("note")).contains("consistent"),
                "consistent note names the verdict: " + json.get("note"));
        assertFalse(json.containsKey("justification"), "nothing to justify when consistent");
        assertFalse(json.containsKey("minimal"));
        assertFalse(json.containsKey("consistency_checks"));
    }

    // --------------------------------------------------- swrlIgnoredWarning: pure text overload

    @Test
    void warningTextNamesRuleCountReasonerAndIgnores() {
        String w = ReasonerTools.swrlIgnoredWarning(3, "ELK 0.5.0");
        assertTrue(w.contains("3 SWRL rules"), "plural rule count: " + w);
        assertTrue(w.contains("(ELK 0.5.0)"), "names the offending reasoner: " + w);
        assertTrue(w.contains("IGNORES"), "the silent-ignore is the headline: " + w);
    }

    @Test
    void warningTextUsesSingularForOneRule() {
        String w = ReasonerTools.swrlIgnoredWarning(1, "ELK");
        assertTrue(w.contains("1 SWRL rule,"), "singular form for a single rule: " + w);
    }

    // ------------------------------------------------ swrlIgnoredWarning: manager-consulting

    /** A real in-memory ontology carrying one SWRL rule ({@code A(?x) -> B(?x)}). */
    private OWLOntology ontologyWithRule() throws OWLOntologyCreationException {
        OWLOntology o = mgr.createOntology(IRI.create(NS + "rules"));
        SWRLVariable x = df.getSWRLVariable(IRI.create("urn:swrl:var#x"));
        SWRLRule rule = df.getSWRLRule(
                Collections.singleton(df.getSWRLClassAtom(cls("A"), x)),
                Collections.singleton(df.getSWRLClassAtom(cls("B"), x)));
        mgr.addAxiom(o, rule);
        return o;
    }

    private static ProtegeOWLReasonerInfo infoProxy(String id, String name) {
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                InconsistencyAndSwrlWarningTest.class.getClassLoader(),
                new Class<?>[] { ProtegeOWLReasonerInfo.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getReasonerId":
                            return id;
                        case "getReasonerName":
                            return name;
                        case "toString":
                            return "FakeReasonerInfo[" + id + "]";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "FakeReasonerInfo does not implement " + method.getName());
                    }
                });
    }

    /**
     * A minimal {@link OWLModelManager} Proxy for {@code swrlIgnoredWarning(mm)}: it answers only
     * {@code getActiveOntology} (a real ontology, so the imports-closure SWRL count is genuine) and
     * {@code getOWLReasonerManager} (a Proxy answering the selected-factory id + installed set) —
     * anything else fails loudly, exactly like {@link FakeModelManager}.
     */
    private static OWLModelManager managerWithReasoner(OWLOntology ontology, String currentId,
            Set<ProtegeOWLReasonerInfo> installed) {
        OWLReasonerManager rm = (OWLReasonerManager) Proxy.newProxyInstance(
                InconsistencyAndSwrlWarningTest.class.getClassLoader(),
                new Class<?>[] { OWLReasonerManager.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getCurrentReasonerFactoryId":
                            return currentId;
                        case "getInstalledReasonerFactories":
                            return installed;
                        case "toString":
                            return "FakeReasonerManager[" + currentId + "]";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "FakeReasonerManager does not implement " + method.getName());
                    }
                });
        return (OWLModelManager) Proxy.newProxyInstance(
                InconsistencyAndSwrlWarningTest.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getActiveOntology":
                            return ontology;
                        case "getOWLReasonerManager":
                            return rm;
                        case "toString":
                            return "FakeModelManagerWithReasoner";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "FakeModelManagerWithReasoner does not implement "
                                            + method.getName());
                    }
                });
    }

    @Test
    void elkFactoryNameWithRulesWarnsNamingElk() throws OWLOntologyCreationException {
        OWLOntology o = ontologyWithRule();
        // The id itself contains no 'elk' — only the resolved display NAME does — proving the
        // installed-factory name lookup (not the id) triggered the match.
        OWLModelManager mm = managerWithReasoner(o, "org.example.factory.one",
                Collections.singleton(infoProxy("org.example.factory.one", "ELK 0.5.0")));

        String w = ReasonerTools.swrlIgnoredWarning(mm);

        assertNotNull(w, "ELK + SWRL rules must warn");
        assertTrue(w.contains("ELK 0.5.0"), "warning names the resolved factory name: " + w);
        assertTrue(w.contains("1 SWRL rule"), "warning carries the closure rule count: " + w);
        assertTrue(w.contains("IGNORES"), w);
    }

    @Test
    void hermitFactoryWithRulesDoesNotWarn() throws OWLOntologyCreationException {
        OWLOntology o = ontologyWithRule();
        OWLModelManager mm = managerWithReasoner(o, "org.semanticweb.HermiT.factory",
                Collections.singleton(infoProxy("org.semanticweb.HermiT.factory", "HermiT")));

        // HermiT fails loudly on unsupported built-in atoms (classification_failed reports that);
        // the silent-ignore advisory is ELK-only.
        assertNull(ReasonerTools.swrlIgnoredWarning(mm));
    }

    @Test
    void noRulesShortCircuitsBeforeConsultingTheReasonerManager()
            throws OWLOntologyCreationException {
        OWLOntology o = mgr.createOntology(IRI.create(NS + "norules"));
        mgr.addAxiom(o, df.getOWLSubClassOfAxiom(cls("A"), cls("B")));
        // FakeModelManager THROWS UnsupportedOperationException on getOWLReasonerManager, so a
        // clean null here proves rules==0 returns BEFORE the reasoner manager is ever consulted
        // (even if ELK were selected).
        assertNull(ReasonerTools.swrlIgnoredWarning(FakeModelManager.over(o)));
    }

    @Test
    void elkIdWithNoMatchingInstalledFactoryFallsBackToTheId() throws OWLOntologyCreationException {
        OWLOntology o = ontologyWithRule();
        // No installed factory matches the current id -> name stays null -> the 'elk' id both
        // triggers the match and stands in as the display name.
        OWLModelManager mm = managerWithReasoner(o, "org.semanticweb.elk.elk.reasoner.factory",
                Collections.emptySet());

        String w = ReasonerTools.swrlIgnoredWarning(mm);

        assertNotNull(w, "an 'elk' factory id must warn even without an installed-name match");
        assertTrue(w.contains("org.semanticweb.elk.elk.reasoner.factory"),
                "warning falls back to the factory id as the name: " + w);
    }

    @Test
    void matchedFactoryWithNullNameFallsBackToTheElkId() throws OWLOntologyCreationException {
        OWLOntology o = ontologyWithRule();
        // The installed factory MATCHES the id but reports a null name: no NPE, the id match still
        // fires, and the id is used in the text.
        OWLModelManager mm = managerWithReasoner(o, "org.semanticweb.elk.elk.reasoner.factory",
                Collections.singleton(infoProxy("org.semanticweb.elk.elk.reasoner.factory", null)));

        String w = ReasonerTools.swrlIgnoredWarning(mm);

        assertNotNull(w);
        assertTrue(w.contains("org.semanticweb.elk.elk.reasoner.factory"), w);
    }
}
