package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
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
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.profiles.Profiles;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;

/** Adversarial tests for the one-copy validation boundary used by project QC and future preflight. */
class IsolatedValidationSnapshotTest {

    private static final String ROOT = "https://example.org/root";
    private static final String IMPORTED = "https://example.org/imported";
    private static final String NS = ROOT + "#";

    @Test
    void allSixNonReasonerStagesRemainOnTheCapturedRevisionAfterLiveMutation() throws Exception {
        Fixture fixture = fixture();
        String capturedFingerprint = OntologyFingerprints.compute(fixture.root).semanticFingerprint();
        IsolatedValidationSnapshot snapshot = IsolatedValidationSnapshot.capture(fixture.mm);

        assertNotSame(fixture.root, snapshot.active());
        assertEquals(2, snapshot.closure().size(), "the loaded import is part of the private closure");
        assertTrue(snapshot.importCoordinates().values().stream().flatMap(List::stream)
                .anyMatch(IMPORTED::equals), "import coordinates are retained without replaying them");
        assertTrue(snapshot.active().getImportsDeclarations().isEmpty(),
                "the private manager cannot dereference a replayed import");

        // Attack the isolation boundary after capture: repair labels and remove the EL violation in the live
        // ontology. Every stage below must still report the older captured state.
        fixture.manager.addAxiom(fixture.root, fixture.df.getOWLAnnotationAssertionAxiom(
                fixture.df.getRDFSLabel(), fixture.local.getIRI(), fixture.df.getOWLLiteral("Local")));
        fixture.manager.addAxiom(fixture.imported, fixture.df.getOWLAnnotationAssertionAxiom(
                fixture.df.getRDFSLabel(), fixture.upstream.getIRI(), fixture.df.getOWLLiteral("Upstream")));
        fixture.manager.removeAxiom(fixture.root, fixture.elViolation);
        assertNotEquals(capturedFingerprint,
                OntologyFingerprints.compute(fixture.root).semanticFingerprint(), "the live revision changed");
        assertEquals(capturedFingerprint, snapshot.fingerprint().semanticFingerprint(),
                "the snapshot keeps the pre-mutation revision token");

        Map<String, Object> profile = GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL",
                snapshot.queries().assertedOntology(), snapshot.active().getAxioms(),
                snapshot.active().getAnnotations(), 25);
        assertEquals(false, profile.get("owned_in_profile"), "profile sees the removed-from-live axiom");

        QcStageResult structural = QcSuiteTools.structuralStage(snapshot,
                Collections.emptySet(), Collections.emptyMap(), true);
        assertEquals("warn", structural.verdict, "structural sees the pre-repair missing labels");
        assertEquals(0, checkCount(structural, "undeclared_entity"),
                "the explicit closure prevents an imported declaration false positive");

        QcStageResult governance = QcSuiteTools.governanceStage(snapshot, null,
                Collections.emptyList(), List.of("label"), true, 25);
        assertEquals("warn", governance.verdict);
        assertTrue(checkCount(governance, "required_annotations") > 0);
        assertTrue(checkCount(governance, "import_layering") > 0,
                "module ownership uses the isolated imported declarations");

        Invariants.Invariant invariant = new Invariants.Invariant("labels", "classes need labels", "error",
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?label } }", false);
        QcStageResult invariants = QcSuiteTools.invariantsStage(snapshot.queries(),
                List.of(invariant), 1000, 30_000);
        assertEquals("fail", invariants.verdict, "invariant sees the pre-repair graph");

        CompetencyQuestion cq = new CompetencyQuestion();
        cq.id = "no-labels-yet";
        cq.query = "SELECT ?label WHERE { ?s rdfs:label ?label }";
        cq.expected = Expectation.empty();
        cq.includeInferred = false;
        QcStageResult cqs = QcSuiteTools.cqsStage(snapshot.queries(), List.of(cq),
                1000, 30_000);
        assertEquals("pass", cqs.verdict, "CQ does not observe labels added to the live model");

        String shapes = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                + "@prefix ex: <" + NS + "> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "ex:LocalShape a sh:NodeShape ; sh:targetNode ex:Local ;\n"
                + "  sh:property [ sh:path rdfs:label ; sh:minCount 1 ] .\n";
        QcStageResult shacl = QcSuiteTools.shaclStage(snapshot.assertedTurtle(), shapes,
                null, 1000, 30_000);
        assertEquals("fail", shacl.verdict, "SHACL sees the pre-repair graph");
    }

    @Test
    void editsToPrivateActiveOntologyNeverTouchTheLiveOntology() throws Exception {
        Fixture fixture = fixture();
        IsolatedValidationSnapshot snapshot = IsolatedValidationSnapshot.capture(fixture.mm);
        OWLAxiom label = fixture.df.getOWLAnnotationAssertionAxiom(fixture.df.getRDFSLabel(),
                fixture.local.getIRI(), fixture.df.getOWLLiteral("private label"));

        snapshot.active().getOWLOntologyManager().addAxiom(snapshot.active(), label);

        assertTrue(snapshot.active().containsAxiom(label));
        assertFalse(fixture.root.containsAxiom(label), "preflight mutation cannot leak into Protégé");
    }

    @Test
    void closureFingerprintDetectsAnImportedOntologyEdit() throws Exception {
        Fixture fixture = fixture();
        String before = IsolatedValidationSnapshot.closureFingerprint(fixture.root.getImportsClosure());
        fixture.manager.addAxiom(fixture.imported, fixture.df.getOWLDeclarationAxiom(
                fixture.df.getOWLClass(IRI.create(IMPORTED + "#NewUpstreamTerm"))));
        String after = IsolatedValidationSnapshot.closureFingerprint(fixture.root.getImportsClosure());

        assertNotEquals(before, after,
                "an import-only edit must invalidate the classification/snapshot consistency check");
    }

    @Test
    void deltaPreflightFingerprintIncludesImportsAndMatchesTheLivePostCommitRevision() throws Exception {
        Fixture fixture = fixture();
        IsolatedValidationSnapshot snapshot = IsolatedValidationSnapshot.capture(fixture.mm);
        OWLClass fresh = fixture.df.getOWLClass(IRI.create(NS + "Fresh"));
        OWLAxiom declaration = fixture.df.getOWLDeclarationAxiom(fresh);
        IsolatedValidationSnapshot afterDelta = snapshot.withChanges(
                List.of(new NormalizedChange(NormalizedChange.Operation.ADD, declaration)));

        // Applying the same delta to the live, import-bearing ontology must yield the same semantic
        // fingerprint: the private copy carries no import declaration, so this only holds because
        // withChanges fingerprints it with the captured import coordinates.
        fixture.manager.addAxiom(fixture.root, declaration);
        String liveAfter = OntologyFingerprints.compute(fixture.root).semanticFingerprint();
        assertEquals(liveAfter, afterDelta.fingerprint().semanticFingerprint(),
                "the delta preflight fingerprint must include imports to match the live post-commit revision");
    }

    @SuppressWarnings("unchecked")
    private static int checkCount(QcStageResult result, String id) {
        for (Map<String, Object> check : (List<Map<String, Object>>) result.summary.get("checks")) {
            if (id.equals(check.get("id"))) {
                return ((Number) check.get("count")).intValue();
            }
        }
        return 0;
    }

    private static Fixture fixture() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology root = manager.createOntology(IRI.create(ROOT));
        OWLOntology imported = manager.createOntology(IRI.create(IMPORTED));
        OWLImportsDeclaration declaration = df.getOWLImportsDeclaration(IRI.create(IMPORTED));
        manager.applyChange(new AddImport(root, declaration));

        OWLClass local = df.getOWLClass(IRI.create(NS + "Local"));
        OWLClass other = df.getOWLClass(IRI.create(NS + "Other"));
        OWLClass upstream = df.getOWLClass(IRI.create(IMPORTED + "#Upstream"));
        manager.addAxiom(root, df.getOWLDeclarationAxiom(local));
        manager.addAxiom(root, df.getOWLDeclarationAxiom(other));
        manager.addAxiom(imported, df.getOWLDeclarationAxiom(upstream));
        // Re-axiomatising an imported subject is a governance violation.
        manager.addAxiom(root, df.getOWLSubClassOfAxiom(upstream, local));
        OWLAxiom elViolation = df.getOWLSubClassOfAxiom(local, df.getOWLObjectUnionOf(local, other));
        manager.addAxiom(root, elViolation);
        return new Fixture(manager, df, root, imported, local, upstream, elViolation,
                FakeModelManager.over(root));
    }

    private record Fixture(OWLOntologyManager manager, OWLDataFactory df, OWLOntology root,
            OWLOntology imported, OWLClass local, OWLClass upstream, OWLAxiom elViolation,
            OWLModelManager mm) { }
}
