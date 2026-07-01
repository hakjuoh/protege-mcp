package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

/**
 * Exercises the reasoner-injected snapshot seam of {@link SparqlTools} headless: the package-private
 * {@link SparqlTools#snapshot(OWLModelManager, OWLReasoner, boolean)} core (backed by a
 * {@link FakeModelManager} and an OWL API {@code StructuralReasoner}) and the Protégé-free
 * {@link SparqlTools#buildSnapshotOntology(OWLOntologyManager, OWLOntologyID, java.util.Set)} helper.
 *
 * <p>FakeModelManager supports {@code getOWLOntologyManager()}, so {@code prefixMap}'s call to
 * {@code getOntologyFormat(active)} is reachable and the whole {@code snapshot(...)} path runs headless.
 */
class SparqlToolsSeamTest {

    private static final String NS = "http://example.org/sparql-seam#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    /** A StructuralReasoner over the given ontology (bundled with owlapi; runs headless). */
    private OWLReasoner reasonerOver(OWLOntology o) {
        return new StructuralReasonerFactory().createReasoner(o);
    }

    // ---------------------------------------------------------------- snapshot: asserted-only

    @Test
    void snapshotWithoutInferredCopiesAssertedAxiomsAndHasNullNote() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/sparql-seam"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLSubClassOfAxiom bSubA = df.getOWLSubClassOfAxiom(b, a);
        m.addAxiom(o, bSubA);

        OWLModelManager mm = FakeModelManager.over(o);
        SparqlTools.Snapshot snap = SparqlTools.snapshot(mm, reasonerOver(o), false);

        assertNull(snap.note(), "asserted-only snapshot carries no note");
        assertNotNull(snap.ontology(), "a snapshot ontology is produced");
        assertTrue(snap.ontology().containsAxiom(bSubA),
                "the asserted SubClassOf(B,A) is copied into the snapshot ontology");
    }

    @Test
    void snapshotIsAPrivateCopyNotTheActiveOntology() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/sparql-seam"));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(cls(df, "B"), cls(df, "A")));

        OWLModelManager mm = FakeModelManager.over(o);
        SparqlTools.Snapshot snap = SparqlTools.snapshot(mm, reasonerOver(o), false);

        // The snapshot lives in a fresh private manager, so it must not be the live active ontology.
        assertFalse(snap.ontology() == o,
                "the snapshot is a throwaway copy, never the live active ontology instance");
    }

    @Test
    void snapshotPrefixesAlwaysIncludeTheFourStandardPrefixes() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/sparql-seam"));
        OWLModelManager mm = FakeModelManager.over(o);

        SparqlTools.Snapshot snap = SparqlTools.snapshot(mm, reasonerOver(o), false);

        assertNotNull(snap.prefixes(), "prefixes are captured");
        assertTrue(snap.prefixes().containsKey("rdf:"), "standard rdf: prefix is exposed");
        assertTrue(snap.prefixes().containsKey("rdfs:"), "standard rdfs: prefix is exposed");
        assertTrue(snap.prefixes().containsKey("owl:"), "standard owl: prefix is exposed");
        assertTrue(snap.prefixes().containsKey("xsd:"), "standard xsd: prefix is exposed");
    }

    // ---------------------------------------------------------------- snapshot: inferred

    @Test
    void snapshotWithInferredMaterialisesInheritedClassAssertion() throws OWLOntologyCreationException {
        // A<-B<-C hierarchy with an individual x asserted to be a B. The reasoner entails that x is
        // also an A (an inherited type), which InferredClassAssertionAxiomGenerator materialises. The
        // StructuralReasoner's InferredSubClassAxiomGenerator only emits DIRECT subclass axioms, so
        // the inherited *type* assertion is the inferred axiom that is genuinely not asserted.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/sparql-seam"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLClass c = cls(df, "C");
        OWLNamedIndividual x = df.getOWLNamedIndividual(IRI.create(NS + "x"));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(c, b));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(b, a));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(b, x));
        OWLClassAssertionAxiom inferredType = df.getOWLClassAssertionAxiom(a, x);

        // Guard the premise: the inferred axiom is genuinely absent from the asserted set.
        assertFalse(o.containsAxiom(inferredType),
                "ClassAssertion(A,x) is inferred (x is a B, B<-A), not asserted, in the source");

        OWLModelManager mm = FakeModelManager.over(o);
        SparqlTools.Snapshot snap = SparqlTools.snapshot(mm, reasonerOver(o), true);

        assertTrue(snap.ontology().containsAxiom(inferredType),
                "include_inferred materialises the inherited ClassAssertion(A,x) into the snapshot");
    }

    @Test
    void snapshotWithoutInferredOmitsTheInferredClassAssertion() throws OWLOntologyCreationException {
        // The same hierarchy, but with include_inferred=false the inherited type must NOT appear —
        // the asserted-only snapshot mirrors Protégé's default SPARQL tab behaviour.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/sparql-seam"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLNamedIndividual x = df.getOWLNamedIndividual(IRI.create(NS + "x"));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(b, a));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(b, x));

        OWLModelManager mm = FakeModelManager.over(o);
        SparqlTools.Snapshot snap = SparqlTools.snapshot(mm, reasonerOver(o), false);

        assertFalse(snap.ontology().containsAxiom(df.getOWLClassAssertionAxiom(a, x)),
                "asserted-only snapshot does not include the inherited ClassAssertion(A,x)");
    }

    @Test
    void snapshotWithInferredStillKeepsAssertedAxioms() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/sparql-seam"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLSubClassOfAxiom bSubA = df.getOWLSubClassOfAxiom(b, a);
        m.addAxiom(o, bSubA);

        OWLModelManager mm = FakeModelManager.over(o);
        SparqlTools.Snapshot snap = SparqlTools.snapshot(mm, reasonerOver(o), true);

        assertTrue(snap.ontology().containsAxiom(bSubA),
                "the asserted axiom survives alongside the inferred additions");
    }

    @Test
    void smallAboxSnapshotWithInferredHasNoSkipNote() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/sparql-seam"));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(cls(df, "B"), cls(df, "A")));

        OWLModelManager mm = FakeModelManager.over(o);
        SparqlTools.Snapshot snap = SparqlTools.snapshot(mm, reasonerOver(o), true);

        // individuals * properties (0) is well under MAX_INFERRED_PROPERTY_PRODUCT, so nothing is skipped.
        assertNull(snap.note(),
                "a small ABox does not trip the quadratic-property-assertion skip note");
    }

    // ---------------------------------------------------------------- buildSnapshotOntology (Protégé-free)

    @Test
    void buildSnapshotCopiesAxioms() throws OWLOntologyCreationException {
        OWLOntologyManager src = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = src.getOWLDataFactory();
        OWLOntology o = src.createOntology(IRI.create("http://example.org/build"));
        OWLSubClassOfAxiom bSubA = df.getOWLSubClassOfAxiom(cls(df, "B"), cls(df, "A"));
        src.addAxiom(o, bSubA);

        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, o.getOntologyID(),
                o.getImportsClosure());

        assertTrue(iso.containsAxiom(bSubA), "closure axioms are copied into the snapshot");
    }

    @Test
    void buildSnapshotCopiesOntologyLevelAnnotations() throws OWLOntologyCreationException {
        OWLOntologyManager src = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = src.getOWLDataFactory();
        OWLOntology o = src.createOntology(IRI.create("http://example.org/build"));
        OWLAnnotation versionInfo = df.getOWLAnnotation(
                df.getOWLVersionInfo(), df.getOWLLiteral("v1.2.3"));
        src.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(o, versionInfo));

        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, o.getOntologyID(),
                o.getImportsClosure());

        assertTrue(iso.getAnnotations().contains(versionInfo),
                "ontology-level annotations (getAxioms omits these) are carried into the snapshot");
    }

    @Test
    void buildSnapshotKeepsNonAnonymousActiveId() throws OWLOntologyCreationException {
        OWLOntologyManager src = OWLManager.createOWLOntologyManager();
        IRI iri = IRI.create("http://example.org/build");
        OWLOntology o = src.createOntology(iri);

        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, o.getOntologyID(),
                o.getImportsClosure());

        assertFalse(iso.getOntologyID().isAnonymous(), "the snapshot keeps a named ontology id");
        assertTrue(iso.getOntologyID().getOntologyIRI().isPresent(), "the id carries an IRI");
        assertTrue(iri.equals(iso.getOntologyID().getOntologyIRI().get()),
                "the snapshot preserves the active ontology's real IRI, not a synthetic one");
    }

    @Test
    void buildSnapshotUsesFreshAnonymousIdWhenActiveIdIsAnonymous() throws OWLOntologyCreationException {
        // An anonymous active id must not be reused verbatim; buildSnapshotOntology falls back to a
        // fresh OWLOntologyID, so the snapshot is simply anonymous.
        OWLOntologyManager src = OWLManager.createOWLOntologyManager();
        OWLOntology anon = src.createOntology(); // no IRI -> anonymous id
        assertTrue(anon.getOntologyID().isAnonymous(), "premise: the source id is anonymous");

        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, anon.getOntologyID(),
                anon.getImportsClosure());

        assertTrue(iso.getOntologyID().isAnonymous(),
                "an anonymous active id yields an anonymous snapshot id");
    }

    @Test
    void buildSnapshotWithNullActiveIdProducesAnonymousSnapshot() throws OWLOntologyCreationException {
        OWLOntologyManager src = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = src.getOWLDataFactory();
        OWLOntology o = src.createOntology(IRI.create("http://example.org/build"));
        OWLSubClassOfAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "B"), cls(df, "A"));
        src.addAxiom(o, ax);

        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        // activeId == null exercises the null branch of the ternary.
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, null, o.getImportsClosure());

        assertTrue(iso.getOntologyID().isAnonymous(), "a null active id yields an anonymous snapshot id");
        assertTrue(iso.containsAxiom(ax), "axioms are still copied when the active id is null");
    }

    @Test
    void buildSnapshotOverEmptyClosureIsEmptyButValid() throws OWLOntologyCreationException {
        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv,
                new OWLOntologyID(IRI.create("http://example.org/x")),
                Collections.emptySet());

        assertTrue(iso.getAxioms().isEmpty(), "an empty closure yields an axiom-free snapshot");
        assertFalse(iso.getOntologyID().isAnonymous(), "the provided named id is still honoured");
    }
}
