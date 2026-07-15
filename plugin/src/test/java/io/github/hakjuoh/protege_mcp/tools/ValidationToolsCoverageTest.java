package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Supplementary, method-level coverage for {@link ValidationTools} that fills the gaps left by
 * {@link ValidationToolsTest} (which exercises {@link ValidationTools#analyze} end-to-end). This file
 * drives the pure static helpers directly: {@link ValidationTools#labels},
 * {@link ValidationTools#isDefinitionProperty}, the {@link ValidationTools.Finding} constructor and
 * {@code count()}, {@link ValidationTools.Signature#of}, and {@link ValidationTools.Tarjan}. No
 * Protégé runtime, no Swing, no reasoner — everything runs over hand-built in-memory ontologies.
 */
class ValidationToolsCoverageTest {

    private static final String NS = "http://example.org/cov#";

    private OWLOntologyManager mgr() {
        return OWLManager.createOWLOntologyManager();
    }

    private OWLOntology ontology(OWLOntologyManager m, String iri) throws OWLOntologyCreationException {
        return m.createOntology(IRI.create(iri));
    }

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    // ============================================================ labels(OWLEntity, Set<OWLOntology>)

    @Test
    void labelsReturnsEmptyListForEntityWithNoLabels() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/lbl-none");
        OWLClass c = cls(df, "Unlabelled");
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));

        List<String> got = ValidationTools.labels(c, Collections.singleton(o));
        assertNotNull(got, "labels never returns null");
        assertTrue(got.isEmpty(), "an entity with no rdfs:label yields an empty list");
    }

    @Test
    void labelsReturnsTheSingleAssertedLabel() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/lbl-one");
        OWLClass c = cls(df, "One");
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral("The Label")));

        List<String> got = ValidationTools.labels(c, Collections.singleton(o));
        assertEquals(Collections.singletonList("The Label"), got, "single label returned verbatim");
    }

    @Test
    void labelsReturnsAllLabelsRegardlessOfLanguage() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/lbl-multi");
        OWLClass c = cls(df, "Multi");
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral("Plain")));                                  // no language
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral("English", "en")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral("Deutsch", "de")));

        List<String> got = ValidationTools.labels(c, Collections.singleton(o));
        assertEquals(3, got.size(), "labels() collects the lexical form of every rdfs:label literal");
        assertTrue(got.containsAll(Arrays.asList("Plain", "English", "Deutsch")),
                "every language variant's lexical form is present");
    }

    @Test
    void labelsIgnoresNonLiteralLabelValues() throws OWLOntologyCreationException {
        // An rdfs:label whose value is an IRI (not a literal) must be skipped, not crash.
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/lbl-iri");
        OWLClass c = cls(df, "IriLabel");
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                IRI.create("http://example.org/not-a-literal")));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral("Real")));

        List<String> got = ValidationTools.labels(c, Collections.singleton(o));
        assertEquals(Collections.singletonList("Real"), got,
                "only the literal-valued label is returned; the IRI-valued one is ignored");
    }

    @Test
    void labelsIgnoresNonLabelAnnotations() throws OWLOntologyCreationException {
        // rdfs:comment is not rdfs:label, so it must not appear in labels().
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/lbl-comment");
        OWLClass c = cls(df, "Commented");
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), c.getIRI(),
                df.getOWLLiteral("a comment, not a label")));

        assertTrue(ValidationTools.labels(c, Collections.singleton(o)).isEmpty(),
                "rdfs:comment is not counted as a label");
    }

    @Test
    void labelsAggregatesAcrossMultipleOntologiesInScope() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o1 = ontology(m, "http://example.org/lbl-scope1");
        OWLOntology o2 = ontology(m, "http://example.org/lbl-scope2");
        OWLClass c = cls(df, "Shared");
        m.addAxiom(o1, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o1, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral("FromO1")));
        m.addAxiom(o2, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral("FromO2")));

        Set<OWLOntology> scope = new HashSet<>(Arrays.asList(o1, o2));
        List<String> got = ValidationTools.labels(c, scope);
        assertEquals(2, got.size(), "labels are gathered from every ontology in scope");
        assertTrue(got.containsAll(Arrays.asList("FromO1", "FromO2")));
    }

    @Test
    void labelsOverEmptyScopeIsEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass c = cls(df, "Anything");
        assertTrue(ValidationTools.labels(c, Collections.<OWLOntology>emptySet()).isEmpty(),
                "no ontologies in scope means no labels");
    }

    // ============================================================ isDefinitionProperty

    @Test
    void isDefinitionPropertyTrueForRdfsComment() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        assertTrue(ValidationTools.isDefinitionProperty(df.getRDFSComment()),
                "rdfs:comment counts as a definition property");
    }

    @Test
    void isDefinitionPropertyTrueForSkosDefinition() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLAnnotationProperty skos =
                df.getOWLAnnotationProperty(IRI.create(ValidationTools.SKOS_DEFINITION));
        assertTrue(ValidationTools.isDefinitionProperty(skos),
                "skos:definition counts as a definition property");
    }

    @Test
    void isDefinitionPropertyTrueForShortFormEndingDefinition() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLAnnotationProperty p = df.getOWLAnnotationProperty(
                IRI.create("http://example.org/av#naturalLanguageDefinition"));
        assertTrue(ValidationTools.isDefinitionProperty(p),
                "any property whose short form ends in 'definition' is a definition property");
    }

    @Test
    void isDefinitionPropertyIsCaseInsensitiveOnSuffix() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLAnnotationProperty upper = df.getOWLAnnotationProperty(
                IRI.create("http://example.org/av#logicDEFINITION"));
        OWLAnnotationProperty mixed = df.getOWLAnnotationProperty(
                IRI.create("http://example.org/av#semiFormalNaturalLanguageDefinition"));
        assertTrue(ValidationTools.isDefinitionProperty(upper),
                "suffix match ignores case (…DEFINITION)");
        assertTrue(ValidationTools.isDefinitionProperty(mixed),
                "…Definition suffix matches");
    }

    @Test
    void isDefinitionPropertyFalseForUnrelatedProperty() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        assertFalse(ValidationTools.isDefinitionProperty(df.getRDFSLabel()),
                "rdfs:label is not a definition property");
        OWLAnnotationProperty other = df.getOWLAnnotationProperty(
                IRI.create("http://example.org/av#seeAlso"));
        assertFalse(ValidationTools.isDefinitionProperty(other),
                "a property whose short form does not end in 'definition' is not a definition property");
    }

    @Test
    void isDefinitionPropertyFalseWhenDefinitionIsNotASuffix() {
        // 'definitional' contains 'definition' but does not END with it.
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLAnnotationProperty p = df.getOWLAnnotationProperty(
                IRI.create("http://example.org/av#definitionalNote"));
        assertFalse(ValidationTools.isDefinitionProperty(p),
                "the rule is a suffix match, not a substring match");
    }

    // ============================================================ Finding constructor + count()

    @Test
    void findingConstructorPopulatesFieldsAndStartsEmpty() {
        ValidationTools.Finding f = new ValidationTools.Finding(
                "my_id", "warning", "My Title", "My suggestion.");
        assertEquals("my_id", f.id, "id is stored");
        assertEquals("warning", f.severity, "severity is stored");
        assertEquals("My Title", f.title, "title is stored");
        assertEquals("My suggestion.", f.suggestion, "suggestion is stored");
        assertNotNull(f.entities, "entities set is initialised");
        assertNotNull(f.details, "details list is initialised");
        assertTrue(f.entities.isEmpty(), "entities starts empty");
        assertTrue(f.details.isEmpty(), "details starts empty");
    }

    @Test
    void findingCountIsZeroWhenNoEntities() {
        ValidationTools.Finding f = new ValidationTools.Finding("x", "info", "t", "s");
        assertEquals(0, f.count(), "empty finding counts zero");
    }

    @Test
    void findingCountReflectsUniqueEntities() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        ValidationTools.Finding f = new ValidationTools.Finding("x", "info", "t", "s");
        f.entities.add(a);
        f.entities.add(b);
        assertEquals(2, f.count(), "count matches the number of distinct entities");
    }

    @Test
    void findingCountDeduplicatesRepeatedEntities() {
        // entities is a Set, so re-adding the same entity does not grow the count.
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = cls(df, "A");
        ValidationTools.Finding f = new ValidationTools.Finding("x", "info", "t", "s");
        f.entities.add(a);
        f.entities.add(a);
        f.entities.add(df.getOWLClass(IRI.create(NS + "A"))); // equal by IRI
        assertEquals(1, f.count(), "the entities set deduplicates equal entities");
    }

    // ============================================================ Signature.of

    @Test
    void signatureOfActiveScopeExcludesLoadedImportEntities(@TempDir java.nio.file.Path temp)
            throws Exception {
        // Load from an actual document: OWLAPI 4's loader pollutes the root's cached getSignature()
        // with the imported entities, and an active-only audit signature must not inherit them —
        // otherwise undeclared_entity flags an import's used-but-undeclared term against this scope.
        String ns = "http://example.org/sig-leak#";
        IRI rootIri = IRI.create(ns + "root");
        IRI importedIri = IRI.create(ns + "imported");
        java.nio.file.Path importedDocument = temp.resolve("imported.ofn");
        java.nio.file.Path rootDocument = temp.resolve("root.ofn");
        java.nio.file.Files.writeString(importedDocument, "Ontology(<" + importedIri
                + "> SubClassOf(<" + ns + "ImpUsedOnly> owl:Thing))");
        java.nio.file.Files.writeString(rootDocument, "Ontology(<" + rootIri + "> Import(<"
                + importedIri + ">) Declaration(Class(<" + ns + "RootOnly>)))");
        OWLOntologyManager m = mgr();
        m.getIRIMappers().add(new org.semanticweb.owlapi.util.SimpleIRIMapper(importedIri,
                IRI.create(importedDocument.toUri())));
        OWLOntology root = m.loadOntologyFromOntologyDocument(rootDocument.toFile());
        OWLDataFactory df = m.getOWLDataFactory();

        ValidationTools.Signature s = ValidationTools.Signature.of(Collections.singleton(root));

        assertTrue(s.all.contains(df.getOWLClass(IRI.create(ns + "RootOnly"))),
                "the root's own class is in the audit signature");
        assertFalse(s.all.contains(df.getOWLClass(IRI.create(ns + "ImpUsedOnly"))),
                "the import's undeclared-but-used class must not enter the active-only signature");
    }

    @Test
    void signatureOfEmptyScopeHasAllEmptyPartitions() {
        ValidationTools.Signature s =
                ValidationTools.Signature.of(Collections.<OWLOntology>emptySet());
        assertTrue(s.all.isEmpty(), "all empty");
        assertTrue(s.classes.isEmpty(), "classes empty");
        assertTrue(s.objectProperties.isEmpty(), "objectProperties empty");
        assertTrue(s.dataProperties.isEmpty(), "dataProperties empty");
        assertTrue(s.labelBearing.isEmpty(), "labelBearing empty");
        assertTrue(s.labelable.isEmpty(), "labelable empty");
        assertTrue(s.definable.isEmpty(), "definable empty");
    }

    @Test
    void signatureOfClassifiesEntityKinds() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/sig-kinds");
        OWLClass c = cls(df, "C");
        OWLObjectProperty op = df.getOWLObjectProperty(IRI.create(NS + "op"));
        OWLDataProperty dp = df.getOWLDataProperty(IRI.create(NS + "dp"));
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(NS + "i"));
        OWLAnnotationProperty ap = df.getOWLAnnotationProperty(IRI.create(NS + "ap"));
        for (OWLEntity e : new OWLEntity[]{c, op, dp, ind, ap}) {
            m.addAxiom(o, df.getOWLDeclarationAxiom(e));
        }

        ValidationTools.Signature s = ValidationTools.Signature.of(Collections.singleton(o));

        assertTrue(s.classes.contains(c), "the class lands in classes");
        assertTrue(s.objectProperties.contains(op), "the object property lands in objectProperties");
        assertTrue(s.dataProperties.contains(dp), "the data property lands in dataProperties");

        // labelBearing: classes + object/data props + named individuals (NOT annotation properties).
        assertTrue(s.labelBearing.contains(c), "class is label-bearing");
        assertTrue(s.labelBearing.contains(op), "object property is label-bearing");
        assertTrue(s.labelBearing.contains(dp), "data property is label-bearing");
        assertTrue(s.labelBearing.contains(ind), "named individual is label-bearing");
        assertFalse(s.labelBearing.contains(ap), "annotation properties are not label-bearing");

        // labelable: classes, object/data props, named individuals — not annotation properties.
        assertTrue(s.labelable.contains(c), "class is labelable");
        assertTrue(s.labelable.contains(op), "object property is labelable");
        assertTrue(s.labelable.contains(dp), "data property is labelable");
        assertTrue(s.labelable.contains(ind), "named individual is labelable");
        assertFalse(s.labelable.contains(ap), "annotation property is not labelable");

        // definable: classes, object/data props, AND annotation properties — not named individuals.
        assertTrue(s.definable.contains(c), "class is definable");
        assertTrue(s.definable.contains(op), "object property is definable");
        assertTrue(s.definable.contains(dp), "data property is definable");
        assertTrue(s.definable.contains(ap), "annotation property is definable");
        assertFalse(s.definable.contains(ind), "named individual is not definable");
    }

    @Test
    void signatureOfDropsReservedVocabularyEntities() throws OWLOntologyCreationException {
        // owl:Thing (built-in) and an XSD-namespaced entity must be filtered from every partition.
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/sig-reserved");
        OWLClass local = cls(df, "Local");
        m.addAxiom(o, df.getOWLDeclarationAxiom(local));
        // Force owl:Thing into the signature via a subclass axiom.
        m.addAxiom(o, df.getOWLSubClassOfAxiom(local, df.getOWLThing()));
        // An entity in the XSD reserved namespace.
        OWLClass xsdish = df.getOWLClass(IRI.create("http://www.w3.org/2001/XMLSchema#weird"));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(local, xsdish));

        ValidationTools.Signature s = ValidationTools.Signature.of(Collections.singleton(o));
        assertTrue(s.classes.contains(local), "the local class survives");
        assertFalse(s.classes.contains(df.getOWLThing()), "owl:Thing is reserved and dropped");
        assertFalse(s.classes.contains(xsdish), "an XSD-namespaced class is reserved and dropped");
        assertFalse(s.labelable.contains(df.getOWLThing()), "owl:Thing is not labelable");
    }

    @Test
    void signatureOfExemptsToolInternalAndWellKnownAnnotationVocab() throws OWLOntologyCreationException {
        // GAP #8/#9: the tool-internal CQ annotation property and a well-known-vocab annotation property
        // (dcterms:title) must NOT be audited as owned — but a well-known-NAMESPACE CLASS still is (the
        // well-known skip is scoped to annotation properties, so imported IAO/BFO classes stay audited).
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/sig-exempt");
        OWLClass local = cls(df, "Local");
        m.addAxiom(o, df.getOWLDeclarationAxiom(local));
        OWLAnnotationProperty dcTitle =
                df.getOWLAnnotationProperty(IRI.create("http://purl.org/dc/terms/title"));
        m.applyChange(new AddOntologyAnnotation(o, df.getOWLAnnotation(dcTitle, df.getOWLLiteral("t"))));
        OWLAnnotationProperty cqProp = df.getOWLAnnotationProperty(
                IRI.create("https://hakjuoh.github.io/protege-mcp/cq#competencyQuestion"));
        m.applyChange(new AddOntologyAnnotation(o, df.getOWLAnnotation(cqProp, df.getOWLLiteral("{}"))));
        OWLClass foafPerson = df.getOWLClass(IRI.create("http://xmlns.com/foaf/0.1/Person"));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(local, foafPerson));

        ValidationTools.Signature s = ValidationTools.Signature.of(Collections.singleton(o));
        // The owned 'definable' partition — which required_annotations / missing_definition / iri_policy
        // iterate — must drop both exempted annotation properties.
        assertFalse(s.definable.contains(dcTitle), "a well-known-vocab annotation property is not owned");
        assertFalse(s.definable.contains(cqProp), "the tool-internal CQ annotation property is not owned");
        assertTrue(s.classes.contains(foafPerson),
                "a well-known-NAMESPACE class is still audited (exemption is annotation-property-scoped)");
    }

    @Test
    void signatureOfTreatsLocalUndeclaredTermAsOwned() throws OWLOntologyCreationException {
        // A term used but never declared anywhere is still owned (audited), so it is in labelable.
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/sig-undeclared");
        OWLClass declared = cls(df, "Declared");
        OWLClass used = cls(df, "UsedOnly");
        m.addAxiom(o, df.getOWLDeclarationAxiom(declared));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(declared, used)); // 'used' referenced, never declared

        ValidationTools.Signature s = ValidationTools.Signature.of(Collections.singleton(o));
        assertTrue(s.all.contains(used), "the undeclared-but-used term is in the referenced signature");
        assertTrue(s.labelable.contains(used),
                "a declaration-by-usage local term is owned and therefore audited");
    }

    @Test
    void signatureOfExcludesPurelyImportedTermsFromOwnedPartitions()
            throws OWLOntologyCreationException {
        // A term declared upstream but only referenced in the audited scope is NOT owned: it should
        // appear in labelBearing/all/classes (referenced signature) but not labelable/definable.
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI baseIri = IRI.create("http://example.org/sig-base");
        OWLOntology base = m.createOntology(baseIri);
        OWLClass upstream = df.getOWLClass(IRI.create("http://example.org/sig-base#Upstream"));
        m.addAxiom(base, df.getOWLDeclarationAxiom(upstream));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/sig-active"));
        m.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(baseIri)));
        OWLClass local = cls(df, "LocalThing");
        m.addAxiom(active, df.getOWLDeclarationAxiom(local));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local, upstream));

        // Audit ACTIVE only: 'upstream' is declared only in the imports closure, so not owned.
        ValidationTools.Signature s = ValidationTools.Signature.of(Collections.singleton(active));
        assertTrue(s.all.contains(upstream), "imported term is in the referenced signature");
        assertTrue(s.classes.contains(upstream), "imported class is in classes (structural checks)");
        assertTrue(s.labelBearing.contains(upstream),
                "imported term is label-bearing (collision checks are ownership-agnostic)");
        assertFalse(s.labelable.contains(upstream),
                "a purely-imported term is not owned, so not in the labelable quality partition");
        assertFalse(s.definable.contains(upstream),
                "a purely-imported term is not in the definable quality partition");
        assertTrue(s.labelable.contains(local), "the locally-declared term is owned and labelable");
    }

    @Test
    void signatureOfImportsClosureMakesUpstreamTermsOwned() throws OWLOntologyCreationException {
        // When the scope is the whole imports closure, the upstream ontology is in scope, so its
        // declared terms become owned.
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI baseIri = IRI.create("http://example.org/sig2-base");
        OWLOntology base = m.createOntology(baseIri);
        OWLClass upstream = df.getOWLClass(IRI.create("http://example.org/sig2-base#Upstream"));
        m.addAxiom(base, df.getOWLDeclarationAxiom(upstream));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/sig2-active"));
        m.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(baseIri)));
        OWLClass local = cls(df, "LocalThing");
        m.addAxiom(active, df.getOWLDeclarationAxiom(local));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local, upstream));

        ValidationTools.Signature s = ValidationTools.Signature.of(active.getImportsClosure());
        assertTrue(s.labelable.contains(upstream),
                "with the imports closure in scope, the upstream term is owned and labelable");
        assertTrue(s.classes.contains(upstream), "upstream class present in classes");
    }

    // ============================================================ Tarjan.stronglyConnected

    private OWLClass tc(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create("http://example.org/tarjan#" + name));
    }

    /** True if some SCC exactly equals the given set of classes. */
    private boolean hasComponent(List<List<OWLClass>> sccs, OWLClass... members) {
        Set<OWLClass> want = new HashSet<>(Arrays.asList(members));
        for (List<OWLClass> comp : sccs) {
            if (new HashSet<>(comp).equals(want)) {
                return true;
            }
        }
        return false;
    }

    private int totalNodes(List<List<OWLClass>> sccs) {
        int n = 0;
        for (List<OWLClass> comp : sccs) {
            n += comp.size();
        }
        return n;
    }

    @Test
    void tarjanEmptyGraphReturnsEmpty() {
        List<List<OWLClass>> sccs =
                ValidationTools.Tarjan.stronglyConnected(new LinkedHashMap<OWLClass, List<OWLClass>>());
        assertTrue(sccs.isEmpty(), "an empty graph has no strongly-connected components");
    }

    @Test
    void tarjanAcyclicGraphGivesSingletonComponents() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = tc(df, "A");
        OWLClass b = tc(df, "B");
        OWLClass c = tc(df, "C");
        Map<OWLClass, List<OWLClass>> g = new LinkedHashMap<>();
        g.put(a, Arrays.asList(b));      // A -> B
        g.put(b, Arrays.asList(c));      // B -> C
        g.put(c, new ArrayList<OWLClass>()); // C -> (nothing)

        List<List<OWLClass>> sccs = ValidationTools.Tarjan.stronglyConnected(g);
        assertEquals(3, sccs.size(), "a DAG has one SCC per node");
        assertEquals(3, totalNodes(sccs), "every node is accounted for");
        for (List<OWLClass> comp : sccs) {
            assertEquals(1, comp.size(), "each SCC is a singleton in an acyclic graph");
        }
    }

    @Test
    void tarjanSelfLoopIsASingletonComponent() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = tc(df, "Self");
        Map<OWLClass, List<OWLClass>> g = new LinkedHashMap<>();
        g.put(a, Arrays.asList(a)); // A -> A

        List<List<OWLClass>> sccs = ValidationTools.Tarjan.stronglyConnected(g);
        assertEquals(1, sccs.size(), "a lone self-loop yields exactly one component");
        assertEquals(1, sccs.get(0).size(),
                "the self-loop component has size 1 (Tarjan groups by membership, not edge count)");
        assertSame(a, sccs.get(0).get(0), "the component holds the self-looping node");
    }

    @Test
    void tarjanTwoNodeCycleIsOneComponentOfTwo() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = tc(df, "A2");
        OWLClass b = tc(df, "B2");
        Map<OWLClass, List<OWLClass>> g = new LinkedHashMap<>();
        g.put(a, Arrays.asList(b)); // A -> B
        g.put(b, Arrays.asList(a)); // B -> A

        List<List<OWLClass>> sccs = ValidationTools.Tarjan.stronglyConnected(g);
        assertEquals(1, sccs.size(), "the cycle collapses to a single component");
        assertTrue(hasComponent(sccs, a, b), "both nodes are in the same component");
    }

    @Test
    void tarjanThreeNodeCycleIsOneComponentOfThree() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = tc(df, "A3");
        OWLClass b = tc(df, "B3");
        OWLClass c = tc(df, "C3");
        Map<OWLClass, List<OWLClass>> g = new LinkedHashMap<>();
        g.put(a, Arrays.asList(b)); // A -> B -> C -> A
        g.put(b, Arrays.asList(c));
        g.put(c, Arrays.asList(a));

        List<List<OWLClass>> sccs = ValidationTools.Tarjan.stronglyConnected(g);
        assertEquals(1, sccs.size(), "a 3-cycle is one component");
        assertTrue(hasComponent(sccs, a, b, c), "all three nodes are in one component");
    }

    @Test
    void tarjanSeparatesDisjointCycles() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = tc(df, "Da");
        OWLClass b = tc(df, "Db");
        OWLClass x = tc(df, "Dx");
        OWLClass y = tc(df, "Dy");
        Map<OWLClass, List<OWLClass>> g = new LinkedHashMap<>();
        g.put(a, Arrays.asList(b)); // cycle 1: A <-> B
        g.put(b, Arrays.asList(a));
        g.put(x, Arrays.asList(y)); // cycle 2: X <-> Y
        g.put(y, Arrays.asList(x));

        List<List<OWLClass>> sccs = ValidationTools.Tarjan.stronglyConnected(g);
        assertEquals(2, sccs.size(), "two disjoint cycles are two separate components");
        assertTrue(hasComponent(sccs, a, b), "first cycle is one component");
        assertTrue(hasComponent(sccs, x, y), "second cycle is another component");
    }

    @Test
    void tarjanMixesIsolatedNodesWithACycle() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = tc(df, "Ma");
        OWLClass b = tc(df, "Mb");
        OWLClass iso = tc(df, "Miso");
        Map<OWLClass, List<OWLClass>> g = new LinkedHashMap<>();
        g.put(a, Arrays.asList(b)); // A <-> B is a cycle
        g.put(b, Arrays.asList(a));
        g.put(iso, new ArrayList<OWLClass>()); // isolated

        List<List<OWLClass>> sccs = ValidationTools.Tarjan.stronglyConnected(g);
        assertEquals(2, sccs.size(), "one 2-cycle plus one isolated node = two components");
        assertTrue(hasComponent(sccs, a, b), "the cycle is a component of two");
        assertTrue(hasComponent(sccs, iso), "the isolated node is its own singleton component");
    }

    @Test
    void tarjanNestedSubCycleWithinLargerStructure() {
        // A -> B -> C -> B (B and C form the only cycle); A and the tail D are singletons.
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = tc(df, "Na");
        OWLClass b = tc(df, "Nb");
        OWLClass c = tc(df, "Nc");
        OWLClass d = tc(df, "Nd");
        Map<OWLClass, List<OWLClass>> g = new LinkedHashMap<>();
        g.put(a, Arrays.asList(b));       // A -> B
        g.put(b, Arrays.asList(c));       // B -> C
        g.put(c, Arrays.asList(b, d));    // C -> B (cycle) and C -> D
        g.put(d, new ArrayList<OWLClass>());

        List<List<OWLClass>> sccs = ValidationTools.Tarjan.stronglyConnected(g);
        assertEquals(4, totalNodes(sccs), "all four nodes are covered exactly once");
        assertTrue(hasComponent(sccs, b, c), "B and C form the only multi-node component");
        assertTrue(hasComponent(sccs, a), "A is a singleton");
        assertTrue(hasComponent(sccs, d), "D is a singleton");
    }

    @Test
    void tarjanHandlesEdgeToNodeMissingFromKeySet() {
        // 'b' is referenced as a successor of 'a' but is not itself a key in the graph map. The
        // iterative DFS uses getOrDefault(...) for successors, so this must not throw and 'b' still
        // becomes its own component.
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLClass a = tc(df, "Ea");
        OWLClass b = tc(df, "Eb"); // no entry in the map
        Map<OWLClass, List<OWLClass>> g = new LinkedHashMap<>();
        g.put(a, Arrays.asList(b));

        List<List<OWLClass>> sccs = ValidationTools.Tarjan.stronglyConnected(g);
        assertEquals(2, totalNodes(sccs), "both the keyed node and its dangling successor are discovered");
        assertTrue(hasComponent(sccs, a), "A is a singleton");
        assertTrue(hasComponent(sccs, b), "the map-absent successor is discovered as its own component");
    }

    // ============================================================ analyze() edge case: empty scope

    @Test
    void analyzeOverEmptyScopeReturnsAllChecksWithZeroCounts() {
        List<ValidationTools.Finding> findings =
                ValidationTools.analyze(Collections.<OWLOntology>emptySet());
        assertEquals(ValidationTools.CHECK_IDS.size(), findings.size(),
                "analyze always returns one finding per check id");
        List<String> ids = new ArrayList<>();
        for (ValidationTools.Finding f : findings) {
            ids.add(f.id);
            assertEquals(0, f.count(), f.id + " has no offenders over an empty scope");
        }
        assertEquals(ValidationTools.CHECK_IDS, ids,
                "findings are emitted in the canonical CHECK_IDS order");
    }
}
