package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.profiles.Profiles;

/**
 * Supplementary, gap-filling coverage for {@link GovernanceTools} — complements
 * {@code GovernanceToolsTest}. Adds the branches the existing test does not exercise:
 * the full {@code normalizeProfile}/{@code profileFor} matrix (whitespace, dash/space
 * normalisation, error path, defensive fallbacks), {@code profileCheck} shape + limit/truncation,
 * {@code flatten} merge/dedup/empty, the remaining {@code subjectEntities} axiom shapes,
 * {@code foreignDeclaredEntities} edge cases, {@code iriPolicyTargets}, and the package-private
 * {@code GovFinding} render/count logic driven through {@link FakeModelManager}.
 */
class GovernanceToolsCoverageTest {

    private static final String NS = "http://example.org/gov#";

    private static OWLOntologyManager mgr() {
        return OWLManager.createOWLOntologyManager();
    }

    private static OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    // ============================================================ normalizeProfile

    @Test
    void normalizeProfileEmptyStringYieldsDefaultDl() {
        assertEquals("DL", GovernanceTools.normalizeProfile(""),
                "an empty owl_profile defaults to DL");
    }

    @Test
    void normalizeProfileWhitespaceOnlyYieldsDefaultDl() {
        // "   " is non-empty so it passes the null/empty guard, then trims to "" and matches nothing
        // special — but the switch on "" is not DL/EL/QL/RL/NONE/FULL, so it throws. Confirm behaviour.
        assertThrows(ToolArgException.class, () -> GovernanceTools.normalizeProfile("   "),
                "whitespace-only trims to empty which is not a known profile token");
    }

    @Test
    void normalizeProfileMixedCaseYieldsDl() {
        assertEquals("DL", GovernanceTools.normalizeProfile("Dl"));
        assertEquals("DL", GovernanceTools.normalizeProfile("DL"));
        assertEquals("EL", GovernanceTools.normalizeProfile("el"));
    }

    @Test
    void normalizeProfileStripsOwl2Prefixes() {
        assertEquals("DL", GovernanceTools.normalizeProfile("OWL2_DL"));
        assertEquals("EL", GovernanceTools.normalizeProfile("OWL2 EL"));
        assertEquals("QL", GovernanceTools.normalizeProfile("OWL 2 QL"));
    }

    @Test
    void normalizeProfileNormalisesDashesAndSpaces() {
        // Inner dashes and spaces are stripped: " d - l " -> trim -> "D - L" -> "DL".
        assertEquals("DL", GovernanceTools.normalizeProfile(" d - l "),
                "leading/trailing spaces trim, inner dash and spaces are stripped");
        assertEquals("RL", GovernanceTools.normalizeProfile("R-L"),
                "a bare dashed profile token normalises");
    }

    @Test
    void normalizeProfileDashedOwl2PrefixIsNotSpecialCased() {
        // The "OWL2"/"OWL 2 " strip-outs run BEFORE the dash strip, so "OWL-2 RL" never matches the
        // OWL2 prefix rules: it collapses to "OWL2RL", which is unknown. Documents the ordering.
        assertThrows(ToolArgException.class, () -> GovernanceTools.normalizeProfile("OWL-2 RL"),
                "the dashed OWL-2 prefix form is not recognised (dash strip runs after OWL2 strip)");
    }

    @Test
    void normalizeProfileFullAndNoneMapToNone() {
        assertEquals("none", GovernanceTools.normalizeProfile("Full"));
        assertEquals("none", GovernanceTools.normalizeProfile("FULL"));
        assertEquals("none", GovernanceTools.normalizeProfile("none"));
        assertEquals("none", GovernanceTools.normalizeProfile("NONE"));
    }

    @Test
    void normalizeProfileUnknownThrowsWithHelpfulMessage() {
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> GovernanceTools.normalizeProfile("SROIQ"));
        assertTrue(ex.getMessage().contains("SROIQ"),
                "the message echoes the offending value");
        assertTrue(ex.getMessage().contains("DL"),
                "the message lists the valid profiles");
    }

    // ============================================================ profileFor

    @Test
    void profileForNullReturnsNull() {
        assertNull(GovernanceTools.profileFor(null), "null normalized name => skip (null)");
    }

    @Test
    void profileForKnownNamesMapToEnum() {
        assertSame(Profiles.OWL2_DL, GovernanceTools.profileFor("DL"));
        assertSame(Profiles.OWL2_EL, GovernanceTools.profileFor("EL"));
        assertSame(Profiles.OWL2_QL, GovernanceTools.profileFor("QL"));
        assertSame(Profiles.OWL2_RL, GovernanceTools.profileFor("RL"));
    }

    @Test
    void profileForNoneAndUnrecognisedReturnNull() {
        assertNull(GovernanceTools.profileFor("none"), "'none' => skip");
        assertNull(GovernanceTools.profileFor("Full"), "unrecognised => defensive null fallback");
        assertNull(GovernanceTools.profileFor("garbage"), "unrecognised => defensive null fallback");
    }

    // ============================================================ profileCheck

    @Test
    void profileCheckInProfileReportsZeroCountAndEmptyExamples() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/prof-clean"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        m.addAxiom(o, df.getOWLDeclarationAxiom(a));
        m.addAxiom(o, df.getOWLDeclarationAxiom(b));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(a, b)); // trivially EL-clean

        OWLOntology flat = GovernanceTools.flatten(o.getImportsClosure());
        Map<String, Object> check = GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat, 25);

        assertTrue((boolean) check.get("in_profile"), "a plain subclass axiom is in EL");
        assertEquals(0, check.get("count"), "no violations");
        assertTrue(((List<?>) check.get("examples")).isEmpty(), "no example violations");
        assertFalse(check.containsKey("truncated"), "nothing truncated when in profile");
    }

    @Test
    void profileCheckResultCarriesFullShape() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/prof-shape"));
        OWLClass a = cls(df, "A");
        m.addAxiom(o, df.getOWLDeclarationAxiom(a));
        OWLOntology flat = GovernanceTools.flatten(o.getImportsClosure());

        Map<String, Object> check = GovernanceTools.profileCheck(Profiles.OWL2_DL, "DL", flat, 25);
        assertEquals("owl_profile", check.get("id"));
        assertEquals("error", check.get("severity"));
        assertEquals("Conformance to OWL 2 DL", check.get("title"));
        assertTrue(check.containsKey("in_profile"));
        assertTrue(check.containsKey("count"));
        assertTrue(check.containsKey("suggestion"));
        assertTrue(check.containsKey("examples"));
        assertEquals("In profile.", check.get("suggestion"),
                "an in-profile ontology gets the reassuring suggestion");
    }

    @Test
    void profileCheckLimitZeroYieldsNoExamplesButKeepsCount() throws OWLOntologyCreationException {
        OWLOntology flat = ontologyWithElViolations(3);
        Map<String, Object> check = GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat, 0);

        assertFalse((boolean) check.get("in_profile"), "unions leave EL");
        assertTrue((int) check.get("count") > 0, "violations are still counted");
        assertTrue(((List<?>) check.get("examples")).isEmpty(), "limit 0 => no sample examples");
        assertTrue(check.containsKey("truncated"), "everything is truncated at limit 0");
        assertEquals(check.get("count"), check.get("truncated"),
                "with zero samples the truncated count equals the total");
        assertFalse("In profile.".equals(check.get("suggestion")),
                "out-of-profile gets the remediation suggestion, not the reassurance");
    }

    @Test
    void profileCheckNegativeLimitBehavesLikeZero() throws OWLOntologyCreationException {
        OWLOntology flat = ontologyWithElViolations(2);
        Map<String, Object> check = GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat, -5);
        assertTrue(((List<?>) check.get("examples")).isEmpty(),
                "Math.max(0, limit) clamps a negative limit to zero examples");
    }

    @Test
    void profileCheckTruncatesExamplesBeyondLimit() throws OWLOntologyCreationException {
        OWLOntology flat = ontologyWithElViolations(4);
        int total = (int) GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat, 1000).get("count");
        // Only proceed if we genuinely produced more violations than our small limit.
        Map<String, Object> check = GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat, 1);
        List<?> examples = (List<?>) check.get("examples");
        assertEquals(1, examples.size(), "examples are capped at the limit");
        if (total > 1) {
            assertEquals(total - 1, check.get("truncated"),
                    "the truncated field records the omitted violations");
        }
    }

    /** An ontology whose flattened closure has at least {@code n} EL-violating union subclass axioms. */
    private static OWLOntology ontologyWithElViolations(int n) throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/prof-viol-" + n));
        for (int i = 0; i < n; i++) {
            OWLClass a = cls(df, "A" + i);
            OWLClass b = cls(df, "B" + i);
            OWLClass c = cls(df, "C" + i);
            m.addAxiom(o, df.getOWLDeclarationAxiom(a));
            m.addAxiom(o, df.getOWLDeclarationAxiom(b));
            m.addAxiom(o, df.getOWLDeclarationAxiom(c));
            m.addAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLObjectUnionOf(b, c)));
        }
        return GovernanceTools.flatten(o.getImportsClosure());
    }

    // ============================================================ flatten

    @Test
    void flattenSingleOntologyCopiesAllAxioms() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/flat-one"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        m.addAxiom(o, df.getOWLDeclarationAxiom(a));
        m.addAxiom(o, df.getOWLDeclarationAxiom(b));
        OWLAxiom sub = df.getOWLSubClassOfAxiom(a, b);
        m.addAxiom(o, sub);

        OWLOntology flat = GovernanceTools.flatten(Collections.singleton(o));
        assertTrue(flat.containsAxiom(sub), "the subclass axiom survives the flatten");
        assertEquals(o.getAxiomCount(), flat.getAxiomCount(), "same number of axioms");
        assertTrue(flat.getImportsDeclarations().isEmpty(), "the flat copy has no imports");
    }

    @Test
    void flattenMergesMultipleOntologiesAndDeduplicates() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o1 = m.createOntology(IRI.create("http://example.org/flat-a"));
        OWLOntology o2 = m.createOntology(IRI.create("http://example.org/flat-b"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLAxiom shared = df.getOWLDeclarationAxiom(a); // present in BOTH ontologies
        m.addAxiom(o1, shared);
        m.addAxiom(o2, shared);
        OWLAxiom onlyB = df.getOWLDeclarationAxiom(b);
        m.addAxiom(o2, onlyB);

        Set<OWLOntology> closure = new LinkedHashSet<>(Arrays.asList(o1, o2));
        OWLOntology flat = GovernanceTools.flatten(closure);
        assertTrue(flat.containsAxiom(shared), "the shared axiom is present");
        assertTrue(flat.containsAxiom(onlyB), "the axiom only in o2 is merged in");
        assertEquals(2, flat.getAxiomCount(),
                "the duplicate declaration is collapsed (HashSet dedup): 2 distinct axioms");
    }

    @Test
    void flattenEmptyClosureYieldsEmptyOntology() {
        OWLOntology flat = GovernanceTools.flatten(Collections.emptySet());
        assertEquals(0, flat.getAxiomCount(), "no source axioms => empty flattened ontology");
    }

    // ============================================================ subjectEntities (remaining shapes)

    @Test
    void subjectEntitiesEquivalentClassesTreatsEveryNamedOperandAsSubject() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass x = cls(df, "X");
        OWLClass y = cls(df, "Y");
        Set<OWLEntity> s = GovernanceTools.subjectEntities(df.getOWLEquivalentClassesAxiom(x, y));
        assertTrue(s.contains(x) && s.contains(y),
                "both operands of an equivalent-classes axiom are subjects");
    }

    @Test
    void subjectEntitiesDisjointClassesTreatsEveryNamedOperandAsSubject() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass x = cls(df, "X");
        OWLClass y = cls(df, "Y");
        Set<OWLEntity> s = GovernanceTools.subjectEntities(df.getOWLDisjointClassesAxiom(x, y));
        assertTrue(s.contains(x) && s.contains(y),
                "both operands of a disjoint-classes axiom are subjects");
    }

    @Test
    void subjectEntitiesDisjointUnionSubjectIsTheDefinedClass() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass whole = cls(df, "Whole");
        OWLClass p1 = cls(df, "Part1");
        OWLClass p2 = cls(df, "Part2");
        Set<OWLEntity> s = GovernanceTools.subjectEntities(
                df.getOWLDisjointUnionAxiom(whole, new LinkedHashSet<>(Arrays.asList(p1, p2))));
        assertTrue(s.contains(whole), "the defined class is the subject of a disjoint union");
    }

    @Test
    void subjectEntitiesHasKeySubjectIsTheKeyedClass() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass k = cls(df, "Keyed");
        OWLDataProperty dp = df.getOWLDataProperty(IRI.create(NS + "dp"));
        Set<OWLEntity> s = GovernanceTools.subjectEntities(
                df.getOWLHasKeyAxiom(k, Collections.singleton(dp)));
        assertTrue(s.contains(k), "the class expression is the subject of a HasKey axiom");
    }

    @Test
    void subjectEntitiesPropertyRangeSubjectIsTheProperty() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLClass rangeClass = cls(df, "R");
        Set<OWLEntity> s = GovernanceTools.subjectEntities(
                df.getOWLObjectPropertyRangeAxiom(p, rangeClass));
        assertTrue(s.contains(p), "the property is the subject of its range axiom");
        assertFalse(s.contains(rangeClass), "the range class is only referenced, not a subject");
    }

    @Test
    void subjectEntitiesUnaryPropertyCharacteristicSubjectIsTheProperty() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        Set<OWLEntity> s = GovernanceTools.subjectEntities(
                df.getOWLFunctionalObjectPropertyAxiom(p));
        assertTrue(s.contains(p), "a functional-property characteristic's subject is the property");
    }

    @Test
    void subjectEntitiesSubObjectPropertySubjectIsTheSubProperty() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty sub = df.getOWLObjectProperty(IRI.create(NS + "subP"));
        OWLObjectProperty sup = df.getOWLObjectProperty(IRI.create(NS + "supP"));
        Set<OWLEntity> s = GovernanceTools.subjectEntities(df.getOWLSubObjectPropertyOfAxiom(sub, sup));
        assertTrue(s.contains(sub), "the sub-property is the subject");
        assertFalse(s.contains(sup), "the referenced super-property is not the subject");
    }

    @Test
    void subjectEntitiesSubDataPropertySubjectIsTheSubProperty() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLDataProperty sub = df.getOWLDataProperty(IRI.create(NS + "subD"));
        OWLDataProperty sup = df.getOWLDataProperty(IRI.create(NS + "supD"));
        Set<OWLEntity> s = GovernanceTools.subjectEntities(df.getOWLSubDataPropertyOfAxiom(sub, sup));
        assertTrue(s.contains(sub), "the sub-data-property is the subject");
        assertFalse(s.contains(sup), "the referenced super-data-property is not the subject");
    }

    @Test
    void subjectEntitiesEquivalentObjectPropertiesTreatsAllAsSubjects() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLObjectProperty q = df.getOWLObjectProperty(IRI.create(NS + "q"));
        Set<OWLEntity> s = GovernanceTools.subjectEntities(
                df.getOWLEquivalentObjectPropertiesAxiom(p, q));
        assertTrue(s.contains(p) && s.contains(q),
                "every named operand of an equivalent-properties axiom is a subject");
    }

    @Test
    void subjectEntitiesInverseObjectPropertiesTreatsBothAsSubjects() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLObjectProperty q = df.getOWLObjectProperty(IRI.create(NS + "q"));
        Set<OWLEntity> s = GovernanceTools.subjectEntities(
                df.getOWLInverseObjectPropertiesAxiom(p, q));
        assertTrue(s.contains(p) && s.contains(q),
                "both members of an inverse-properties axiom are subjects");
    }

    @Test
    void subjectEntitiesAnonymousSubclassContributesNoSubject() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass filler = cls(df, "Filler");
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        OWLClass sup = cls(df, "Sup");
        // (p some Filler) SubClassOf Sup: the anonymous restriction is filtered by addNamedClass.
        Set<OWLEntity> s = GovernanceTools.subjectEntities(
                df.getOWLSubClassOfAxiom(df.getOWLObjectSomeValuesFrom(p, filler), sup));
        assertFalse(s.contains(sup), "the (object-position) named superclass is not a subject");
        assertTrue(s.isEmpty(),
                "an anonymous subclass expression yields no named subject");
    }

    @Test
    void subjectEntitiesUnenumeratedAxiomShapeYieldsNoSubjects() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLNamedIndividual i = df.getOWLNamedIndividual(IRI.create(NS + "i"));
        OWLNamedIndividual j = df.getOWLNamedIndividual(IRI.create(NS + "j"));
        // SameIndividual is an ABox axiom deliberately not enumerated -> conservatively no subjects.
        assertTrue(GovernanceTools.subjectEntities(df.getOWLSameIndividualAxiom(i, j)).isEmpty(),
                "ABox same-individual axioms contribute no TBox subject");
    }

    // ============================================================ foreignDeclaredEntities

    @Test
    void foreignDeclaredEntitiesNoImportsIsEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/lone"));
        m.addAxiom(active, df.getOWLDeclarationAxiom(cls(df, "Local")));
        assertTrue(GovernanceTools.foreignDeclaredEntities(active).isEmpty(),
                "with no imports there are no foreign declarations");
    }

    @Test
    void foreignDeclaredEntitiesExcludesLocallyRedeclaredTerms() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI baseIri = IRI.create("http://example.org/base");
        OWLOntology base = m.createOntology(baseIri);
        OWLClass shared = df.getOWLClass(IRI.create("http://example.org/base#Shared"));
        m.addAxiom(base, df.getOWLDeclarationAxiom(shared));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/active-redecl"));
        m.applyChange(new AddImport(active, df.getOWLImportsDeclaration(baseIri)));
        m.addAxiom(active, df.getOWLDeclarationAxiom(shared)); // redeclared locally

        assertFalse(GovernanceTools.foreignDeclaredEntities(active).contains(shared),
                "a term redeclared in the active ontology is not foreign");
    }

    @Test
    void foreignDeclaredEntitiesMergesAcrossMultipleImports() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI base1Iri = IRI.create("http://example.org/base1");
        IRI base2Iri = IRI.create("http://example.org/base2");
        OWLOntology base1 = m.createOntology(base1Iri);
        OWLOntology base2 = m.createOntology(base2Iri);
        OWLClass up1 = df.getOWLClass(IRI.create("http://example.org/base1#Up1"));
        OWLClass up2 = df.getOWLClass(IRI.create("http://example.org/base2#Up2"));
        m.addAxiom(base1, df.getOWLDeclarationAxiom(up1));
        m.addAxiom(base2, df.getOWLDeclarationAxiom(up2));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/active-multi"));
        m.applyChange(new AddImport(active, df.getOWLImportsDeclaration(base1Iri)));
        m.applyChange(new AddImport(active, df.getOWLImportsDeclaration(base2Iri)));

        Set<OWLEntity> foreign = GovernanceTools.foreignDeclaredEntities(active);
        assertTrue(foreign.contains(up1) && foreign.contains(up2),
                "foreign declarations from all imports are collected");
    }

    // ============================================================ iriPolicyTargets

    @Test
    void iriPolicyTargetsEmptySignatureIsEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/empty-sig"));
        ValidationTools.Signature sig = ValidationTools.Signature.of(Collections.singleton(o));
        assertTrue(GovernanceTools.iriPolicyTargets(sig).isEmpty(),
                "an empty ontology has no policy targets");
    }

    @Test
    void iriPolicyTargetsUnionsLabelableAndDefinable() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/targets"));
        OWLClass c = cls(df, "C");
        OWLObjectProperty op = df.getOWLObjectProperty(IRI.create(NS + "op"));
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(NS + "ind"));
        // an annotation property is definable-only (not labelable) — exercises the definable branch
        org.semanticweb.owlapi.model.OWLAnnotationProperty ap =
                df.getOWLAnnotationProperty(IRI.create(NS + "ap"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLDeclarationAxiom(op));
        m.addAxiom(o, df.getOWLDeclarationAxiom(ind));
        m.addAxiom(o, df.getOWLDeclarationAxiom(ap));

        ValidationTools.Signature sig = ValidationTools.Signature.of(Collections.singleton(o));
        Set<OWLEntity> targets = GovernanceTools.iriPolicyTargets(sig);
        assertTrue(targets.contains(c), "a class is a policy target");
        assertTrue(targets.contains(op), "an object property is a policy target");
        assertTrue(targets.contains(ind), "a named individual is a policy target");
        assertTrue(targets.contains(ap),
                "an annotation property (definable-only) is a policy target via the union");
    }

    // ============================================================ GovFinding (via FakeModelManager)

    @Test
    void govFindingCountSumsEntitiesAndAxioms() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/finding-count"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        m.addAxiom(o, df.getOWLDeclarationAxiom(a));
        m.addAxiom(o, df.getOWLDeclarationAxiom(b));
        OWLModelManager mm = FakeModelManager.over(o);

        GovernanceTools.GovFinding f = new GovernanceTools.GovFinding(
                mm, "iri_policy", "warning", "rule text", "fix text");
        assertEquals(0, f.count(), "a fresh finding counts nothing");
        f.entities.add(a);
        f.entities.add(b);
        f.axioms.add(df.getOWLSubClassOfAxiom(a, b));
        assertEquals(3, f.count(), "count is entities + axioms");
    }

    @Test
    void govFindingToJsonRendersEntitiesAxiomsAndDetails() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/finding-json"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        m.addAxiom(o, df.getOWLDeclarationAxiom(a));
        m.addAxiom(o, df.getOWLDeclarationAxiom(b));
        OWLModelManager mm = FakeModelManager.over(o);

        GovernanceTools.GovFinding f = new GovernanceTools.GovFinding(
                mm, "import_layering", "warning", "title text", "suggest text");
        f.entities.add(a);
        f.axioms.add(df.getOWLSubClassOfAxiom(a, b));
        f.details.add("A missing label");

        Map<String, Object> json = f.toJson(25);
        assertEquals("import_layering", json.get("id"));
        assertEquals("warning", json.get("severity"));
        assertEquals("title text", json.get("title"));
        assertEquals("suggest text", json.get("suggestion"));
        assertEquals(2, json.get("count"), "one entity + one axiom => count 2");
        assertTrue(json.containsKey("examples"), "entities render under 'examples'");
        assertTrue(json.containsKey("axioms"), "axioms render under 'axioms'");
        assertEquals(Collections.singletonList("A missing label"), json.get("details"),
                "details are carried through verbatim");
    }

    @Test
    void govFindingToJsonOmitsEmptySections() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/finding-empty"));
        OWLModelManager mm = FakeModelManager.over(o);

        GovernanceTools.GovFinding f = new GovernanceTools.GovFinding(
                mm, "iri_policy", "warning", "t", "s");
        Map<String, Object> json = f.toJson(25);
        assertEquals(0, json.get("count"));
        assertFalse(json.containsKey("examples"), "no entities => no examples key");
        assertFalse(json.containsKey("axioms"), "no axioms => no axioms key");
        assertFalse(json.containsKey("details"), "no details => no details key");
    }

    @Test
    void govFindingToJsonTruncatesDetailsBeyondLimit() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/finding-trunc"));
        OWLModelManager mm = FakeModelManager.over(o);

        GovernanceTools.GovFinding f = new GovernanceTools.GovFinding(
                mm, "required_annotations", "warning", "t", "s");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            f.details.add("detail " + i);
            if (i < 2) {
                expected.add("detail " + i);
            }
        }
        Map<String, Object> json = f.toJson(2);
        assertEquals(expected, json.get("details"),
                "details are truncated to the limit (first 2 kept)");
    }
}
