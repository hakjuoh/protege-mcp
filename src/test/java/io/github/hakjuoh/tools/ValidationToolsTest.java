package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Exercises the pure {@link ValidationTools#analyze} audit on a hand-built ontology seeded with one
 * instance of every modelling-quality smell the checks look for — no Protégé / OWLModelManager needed.
 */
class ValidationToolsTest {

    private static final String NS = "http://example.org/test#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    /** Builds an ontology where each check has at least one known offender. */
    private OWLOntology fixture() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/test"));

        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLClass c = cls(df, "C");          // isolated
        OWLClass d = cls(df, "D");          // self-subclass
        OWLClass p = cls(df, "P");          // cycle
        OWLClass q = cls(df, "Q");          // cycle
        OWLClass u = cls(df, "U");          // undeclared (only used)
        OWLObjectProperty hasX = df.getOWLObjectProperty(IRI.create(NS + "hasX"));

        for (OWLClass cl : new OWLClass[]{a, b, c, d, p, q}) {
            m.addAxiom(o, df.getOWLDeclarationAxiom(cl));
        }
        m.addAxiom(o, df.getOWLDeclarationAxiom(hasX));

        // A ⊑ B, A ⊑ U (U is never declared)
        m.addAxiom(o, df.getOWLSubClassOfAxiom(a, b));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(a, u));
        // D ⊑ D (self), P ⊑ Q ⊑ P (cycle)
        m.addAxiom(o, df.getOWLSubClassOfAxiom(d, d));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(p, q));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(q, p));

        // duplicate label "Foo" on A and B; A also has a second label (multiple labels, same language)
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), a.getIRI(), df.getOWLLiteral("Foo")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), a.getIRI(), df.getOWLLiteral("Foo2")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), b.getIRI(), df.getOWLLiteral("Foo")));

        // B is deprecated but still used (A ⊑ B)
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI()),
                b.getIRI(), df.getOWLLiteral(true)));

        return o;
    }

    private ValidationTools.Finding find(List<ValidationTools.Finding> findings, String id) {
        return findings.stream().filter(f -> f.id.equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + id));
    }

    private boolean hasClass(ValidationTools.Finding f, String name) {
        for (Object e : f.entities) {
            if (((org.semanticweb.owlapi.model.OWLEntity) e).getIRI().toString().equals(NS + name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasIri(ValidationTools.Finding f, IRI iri) {
        for (Object e : f.entities) {
            if (((org.semanticweb.owlapi.model.OWLEntity) e).getIRI().equals(iri)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyCheckFlagsItsSeededOffender() throws OWLOntologyCreationException {
        OWLOntology o = fixture();
        Set<OWLOntology> scope = Collections.singleton(o);
        List<ValidationTools.Finding> findings = ValidationTools.analyze(scope);

        // Every check id is reported (even those with count 0).
        for (String id : ValidationTools.CHECK_IDS) {
            find(findings, id);
        }

        assertTrue(hasClass(find(findings, "missing_label"), "C"), "C has no label");
        assertTrue(hasClass(find(findings, "duplicate_label"), "A"), "A shares label 'Foo'");
        assertTrue(hasClass(find(findings, "duplicate_label"), "B"), "B shares label 'Foo'");
        assertTrue(hasClass(find(findings, "multiple_labels"), "A"), "A has two labels");
        assertTrue(hasClass(find(findings, "deprecated_in_use"), "B"), "B deprecated yet used");
        assertTrue(hasClass(find(findings, "undeclared_entity"), "U"), "U used but not declared");
        assertTrue(hasClass(find(findings, "self_subclass"), "D"), "D ⊑ D");
        assertTrue(hasClass(find(findings, "subclass_cycle"), "P"), "P in P/Q cycle");
        assertTrue(hasClass(find(findings, "subclass_cycle"), "Q"), "Q in P/Q cycle");
        assertTrue(hasClass(find(findings, "isolated_class"), "C"), "C is isolated");
        assertTrue(hasClass(find(findings, "property_missing_domain"), "hasX"), "hasX has no domain");
        assertTrue(hasClass(find(findings, "property_missing_range"), "hasX"), "hasX has no range");

        // A is in the hierarchy and used, so it is not isolated; the cycle members are not self-subclasses.
        assertFalse(hasClass(find(findings, "isolated_class"), "A"), "A is not isolated");
        assertFalse(hasClass(find(findings, "self_subclass"), "P"), "P is a cycle, not a self-subclass");
    }

    @Test
    void cleanOntologyHasNoIssues() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/clean"));
        OWLClass animal = cls(df, "Animal");
        OWLClass dog = cls(df, "Dog");
        OWLObjectProperty likes = df.getOWLObjectProperty(IRI.create(NS + "likes"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dog));
        m.addAxiom(o, df.getOWLDeclarationAxiom(likes));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));
        // A property with a proper domain AND range, so the property checks are genuinely exercised.
        m.addAxiom(o, df.getOWLObjectPropertyDomainAxiom(likes, animal));
        m.addAxiom(o, df.getOWLObjectPropertyRangeAxiom(likes, animal));
        labelAndComment(m, df, o, animal, "Animal", "A living organism.");
        labelAndComment(m, df, o, dog, "Dog", "A domestic dog.");
        labelAndComment(m, df, o, likes, "likes", "Relates an animal to one it likes.");

        List<ValidationTools.Finding> findings = ValidationTools.analyze(Collections.singleton(o));
        for (String id : ValidationTools.CHECK_IDS) {
            assertTrue(find(findings, id).count() == 0, id + " should be clean, got " + find(findings, id).count());
        }
    }

    @Test
    void labelsAreCheckedPerLanguage() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/lang"));
        OWLClass bilingual = cls(df, "Bilingual");   // one label per language — OK
        OWLClass twoEnglish = cls(df, "TwoEnglish"); // two en labels — flagged
        OWLClass shareEn = cls(df, "ShareEn");       // "Bank"@en
        OWLClass shareFr = cls(df, "ShareFr");       // "Bank"@fr — NOT a duplicate of ShareEn
        OWLClass dupA = cls(df, "DupA");             // "Same"@en
        OWLClass dupB = cls(df, "DupB");             // "Same"@en — duplicate of DupA
        for (OWLClass c : new OWLClass[]{bilingual, twoEnglish, shareEn, shareFr, dupA, dupB}) {
            m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        }
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), bilingual.getIRI(), df.getOWLLiteral("Term", "en")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), bilingual.getIRI(), df.getOWLLiteral("Begriff", "de")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), twoEnglish.getIRI(), df.getOWLLiteral("One", "en")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), twoEnglish.getIRI(), df.getOWLLiteral("Two", "en")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), shareEn.getIRI(), df.getOWLLiteral("Bank", "en")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), shareFr.getIRI(), df.getOWLLiteral("Bank", "fr")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), dupA.getIRI(), df.getOWLLiteral("Same", "en")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), dupB.getIRI(), df.getOWLLiteral("Same", "en")));

        List<ValidationTools.Finding> findings = ValidationTools.analyze(Collections.singleton(o));

        // multiple_labels: two en labels fires; one-per-language does not.
        assertTrue(hasClass(find(findings, "multiple_labels"), "TwoEnglish"));
        assertFalse(hasClass(find(findings, "multiple_labels"), "Bilingual"));
        // duplicate_label: same text + same language fires; same text + different language does not.
        assertTrue(hasClass(find(findings, "duplicate_label"), "DupA"));
        assertTrue(hasClass(find(findings, "duplicate_label"), "DupB"));
        assertFalse(hasClass(find(findings, "duplicate_label"), "ShareEn"));
        assertFalse(hasClass(find(findings, "duplicate_label"), "ShareFr"));
    }

    @Test
    void importedTermsAreNotFlaggedAsUndeclared() throws OWLOntologyCreationException {
        // An upstream ontology declares a term; the active ontology imports it and only references it.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI baseIri = IRI.create("http://example.org/base");
        OWLOntology base = m.createOntology(baseIri);
        OWLClass upstream = df.getOWLClass(IRI.create("http://example.org/base#Continuant"));
        m.addAxiom(base, df.getOWLDeclarationAxiom(upstream));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/active"));
        m.applyChange(new AddImport(active, df.getOWLImportsDeclaration(baseIri)));
        OWLClass local = cls(df, "MyThing");
        m.addAxiom(active, df.getOWLDeclarationAxiom(local));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local, upstream)); // references the imported term

        // Audit the active ontology ONLY (include_imports=false). The imported, locally-used term
        // must NOT be reported as undeclared — its declaration lives in the (loaded) import.
        List<ValidationTools.Finding> findings = ValidationTools.analyze(Collections.singleton(active));
        for (Object e : find(findings, "undeclared_entity").entities) {
            assertFalse(((org.semanticweb.owlapi.model.OWLEntity) e).getIRI().equals(upstream.getIRI()),
                    "imported term Continuant must not be flagged undeclared");
        }
        // ...nor flagged for missing label/definition: those belong to its home ontology, which is not
        // in the active-only audit scope. (Continuant carries no label/definition here at all.)
        assertFalse(hasIri(find(findings, "missing_label"), upstream.getIRI()),
                "imported term must not be flagged missing_label in an active-only audit");
        assertFalse(hasIri(find(findings, "missing_definition"), upstream.getIRI()),
                "imported term must not be flagged missing_definition in an active-only audit");
    }

    @Test
    void importedTermsAreAuditedForQualityOnlyWhenImportsIncluded() throws OWLOntologyCreationException {
        // base declares: Continuant (label + comment), partOf (object property with domain + range),
        // and Incomplete (no label, no definition). active imports base and references all three.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI baseIri = IRI.create("http://example.org/base");
        OWLOntology base = m.createOntology(baseIri);
        OWLClass continuant = df.getOWLClass(IRI.create("http://example.org/base#Continuant"));
        OWLClass incomplete = df.getOWLClass(IRI.create("http://example.org/base#Incomplete"));
        OWLObjectProperty partOf = df.getOWLObjectProperty(IRI.create("http://example.org/base#partOf"));
        m.addAxiom(base, df.getOWLDeclarationAxiom(continuant));
        m.addAxiom(base, df.getOWLDeclarationAxiom(incomplete));
        m.addAxiom(base, df.getOWLDeclarationAxiom(partOf));
        labelAndComment(m, df, base, continuant, "Continuant", "A persistent entity.");
        m.addAxiom(base, df.getOWLObjectPropertyDomainAxiom(partOf, continuant));
        m.addAxiom(base, df.getOWLObjectPropertyRangeAxiom(partOf, continuant));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/active"));
        m.applyChange(new AddImport(active, df.getOWLImportsDeclaration(baseIri)));
        OWLClass local = cls(df, "MyThing");                       // local, deliberately unlabelled
        m.addAxiom(active, df.getOWLDeclarationAxiom(local));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local, continuant));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local, incomplete));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local,
                df.getOWLObjectSomeValuesFrom(partOf, continuant)));   // references partOf

        // include_imports=false: imported terms are out of scope for the per-entity quality checks,
        // even Incomplete (which genuinely lacks a label upstream). The local term is still audited.
        List<ValidationTools.Finding> active0 = ValidationTools.analyze(Collections.singleton(active));
        assertFalse(hasIri(find(active0, "missing_label"), continuant.getIRI()), "Continuant is imported");
        assertFalse(hasIri(find(active0, "missing_label"), incomplete.getIRI()),
                "imported term is not audited active-only even when it lacks a label upstream");
        assertFalse(hasIri(find(active0, "property_missing_domain"), partOf.getIRI()),
                "imported property is not audited for domain active-only");
        assertFalse(hasIri(find(active0, "property_missing_range"), partOf.getIRI()),
                "imported property is not audited for range active-only");
        assertTrue(hasClass(find(active0, "missing_label"), "MyThing"),
                "the local term is still audited and flagged");

        // include_imports=true: base enters scope, so its terms become owned and are audited. Continuant
        // and partOf are complete; Incomplete is now correctly flagged for its missing label/definition.
        List<ValidationTools.Finding> closure = ValidationTools.analyze(active.getImportsClosure());
        assertFalse(hasIri(find(closure, "missing_label"), continuant.getIRI()), "Continuant has a label");
        assertFalse(hasIri(find(closure, "property_missing_domain"), partOf.getIRI()), "partOf has a domain");
        assertFalse(hasIri(find(closure, "property_missing_range"), partOf.getIRI()), "partOf has a range");
        assertTrue(hasIri(find(closure, "missing_label"), incomplete.getIRI()),
                "Incomplete lacks a label and is audited once imports are included");
        assertTrue(hasIri(find(closure, "missing_definition"), incomplete.getIRI()),
                "Incomplete lacks a definition and is audited once imports are included");
    }

    @Test
    void labelCollisionsAreCheckedOverLocalAssertionsEvenOnImportedIris()
            throws OWLOntologyCreationException {
        // The collision checks are about label ASSERTIONS present in scope, not about who "owns" the
        // term: a label the active ontology asserts on an imported IRI still collides locally. This
        // guards against the owned-by-scope filter silently dropping such collisions.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI baseIri = IRI.create("http://example.org/base");
        OWLOntology base = m.createOntology(baseIri);
        OWLClass imported = df.getOWLClass(IRI.create("http://example.org/base#Imported"));
        OWLClass dupImported = df.getOWLClass(IRI.create("http://example.org/base#DupImported"));
        m.addAxiom(base, df.getOWLDeclarationAxiom(imported));
        m.addAxiom(base, df.getOWLDeclarationAxiom(dupImported));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/active"));
        m.applyChange(new AddImport(active, df.getOWLImportsDeclaration(baseIri)));
        OWLClass local = cls(df, "Local");
        m.addAxiom(active, df.getOWLDeclarationAxiom(local));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local, imported));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local, dupImported)); // pull both into the signature
        // duplicate_label: a local class and an imported IRI both carry "Shared"@en, asserted IN active.
        m.addAxiom(active, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), local.getIRI(), df.getOWLLiteral("Shared", "en")));
        m.addAxiom(active, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), imported.getIRI(), df.getOWLLiteral("Shared", "en")));
        // multiple_labels: two @en labels asserted locally on an imported IRI.
        m.addAxiom(active, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dupImported.getIRI(), df.getOWLLiteral("One", "en")));
        m.addAxiom(active, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dupImported.getIRI(), df.getOWLLiteral("Two", "en")));

        List<ValidationTools.Finding> findings = ValidationTools.analyze(Collections.singleton(active));
        // The locally-owned class with a real duplicate label is flagged, together with its partner.
        assertTrue(hasClass(find(findings, "duplicate_label"), "Local"),
                "the local class's locally-asserted duplicate label must still be flagged");
        assertTrue(hasIri(find(findings, "duplicate_label"), imported.getIRI()),
                "the imported partner of the local collision is part of the duplicate set");
        // An imported IRI given two same-language labels by LOCAL axioms is a local smell, so flagged.
        assertTrue(hasIri(find(findings, "multiple_labels"), dupImported.getIRI()),
                "two same-language labels asserted locally on an imported IRI must be flagged");
    }

    @Test
    void definitionAnnotationsBeyondCommentCountAsDefined() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/defs"));

        OWLClass withNlDef = cls(df, "WithNlDef");   // iof-av:naturalLanguageDefinition only
        OWLClass withSkos = cls(df, "WithSkos");     // skos:definition only
        OWLClass undefined = cls(df, "Undefined");   // no definition at all
        for (OWLClass c : new OWLClass[]{withNlDef, withSkos, undefined}) {
            m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        }
        OWLAnnotationProperty nlDef = df.getOWLAnnotationProperty(IRI.create(
                "https://spec.industrialontologies.org/ontology/annotation/naturalLanguageDefinition"));
        OWLAnnotationProperty skosDef = df.getOWLAnnotationProperty(
                IRI.create(ValidationTools.SKOS_DEFINITION));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                nlDef, withNlDef.getIRI(), df.getOWLLiteral("a primitive notion", "en")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                skosDef, withSkos.getIRI(), df.getOWLLiteral("the genus-differentia", "en")));

        ValidationTools.Finding missingDef =
                find(ValidationTools.analyze(Collections.singleton(o)), "missing_definition");
        assertFalse(hasClass(missingDef, "WithNlDef"),
                "iof-av:naturalLanguageDefinition counts as a definition");
        assertFalse(hasClass(missingDef, "WithSkos"), "skos:definition counts as a definition");
        assertTrue(hasClass(missingDef, "Undefined"), "no definition annotation is still flagged");
    }

    private static void labelAndComment(OWLOntologyManager m, OWLDataFactory df, OWLOntology o,
            org.semanticweb.owlapi.model.OWLEntity e, String label, String comment) {
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), e.getIRI(), df.getOWLLiteral(label)));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), e.getIRI(), df.getOWLLiteral(comment)));
    }
}
