package io.github.hakjuoh.protege_mcp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Content-keyed inspection caching and defined-vs-declared-only subject reporting. */
class ModuleDocumentInspectorTest {

    @Test
    void unchangedContentIsServedFromTheCacheWithoutReparsing(@TempDir Path temp) throws Exception {
        Path module = temp.resolve("module.ttl");
        String iri = uniqueIri();
        Files.writeString(module, turtle(iri, "<" + iri + "/A> a owl:Class .\n"));

        int before = ModuleDocumentInspector.parseCount();
        ModuleDocumentInspector.Inspection first = ModuleDocumentInspector.inspect(module);
        assertEquals(before + 1, ModuleDocumentInspector.parseCount());

        ModuleDocumentInspector.Inspection again = ModuleDocumentInspector.inspect(module);
        assertEquals(before + 1, ModuleDocumentInspector.parseCount());
        assertEquals(first.ontologyIri(), again.ontologyIri());
        assertEquals(first.declaredEntityIris(), again.declaredEntityIris());

        // The cache is keyed by (path, content): identical bytes at ANOTHER path re-parse,
        // because the document path is also the base against which relative IRIs resolve.
        Path copy = temp.resolve("copy.ttl");
        Files.copy(module, copy);
        assertEquals(iri, ModuleDocumentInspector.inspect(copy).ontologyIri());
        assertEquals(before + 2, ModuleDocumentInspector.parseCount());
    }

    @Test
    void relativeIriDocumentsResolveAgainstTheirOwnPathNotACachedPath(@TempDir Path temp)
            throws Exception {
        // No absolute ontology IRI and no @base: every IRI resolves against the document's own
        // location, so byte-identical files at different paths inspect DIFFERENTLY. A pure
        // content-hash cache would serve path A's resolution for path B.
        String document = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<> a owl:Ontology .\n"
                + "<#Widget> a owl:Class .\n"
                + "# " + UUID.randomUUID() + "\n";
        Path first = Files.createDirectories(temp.resolve("a")).resolve("mod.ttl");
        Path second = Files.createDirectories(temp.resolve("b")).resolve("mod.ttl");
        Files.writeString(first, document);
        Files.writeString(second, document);

        assertEquals(first.toUri().toString(),
                ModuleDocumentInspector.inspect(first).ontologyIri());
        ModuleDocumentInspector.Inspection inspection = ModuleDocumentInspector.inspect(second);
        assertEquals(second.toUri().toString(), inspection.ontologyIri());
        assertTrue(inspection.declaredEntityIris().contains(second.toUri() + "#Widget"));
    }

    @Test
    void contentChangeInvalidatesTheCachedInspection(@TempDir Path temp) throws Exception {
        Path module = temp.resolve("module.ttl");
        String iri = uniqueIri();
        Files.writeString(module, turtle(iri, "<" + iri + "/First> a owl:Class .\n"));
        assertTrue(ModuleDocumentInspector.inspect(module)
                .declaredEntityIris().contains(iri + "/First"));

        int before = ModuleDocumentInspector.parseCount();
        Files.writeString(module, turtle(iri, "<" + iri + "/Second> a owl:Class .\n"));
        Set<String> declared = ModuleDocumentInspector.inspect(module).declaredEntityIris();
        assertEquals(before + 1, ModuleDocumentInspector.parseCount());
        assertTrue(declared.contains(iri + "/Second"));
        assertFalse(declared.contains(iri + "/First"));
    }

    @Test
    void unreadableAndUnparsableDocumentsStillFail(@TempDir Path temp) throws Exception {
        assertThrows(IOException.class,
                () -> ModuleDocumentInspector.inspect(temp.resolve("absent.ttl")));
        Path garbage = temp.resolve("garbage.ttl");
        Files.writeString(garbage, "@@@ " + uniqueIri() + " @@@\n");
        assertThrows(IOException.class, () -> ModuleDocumentInspector.inspect(garbage));
        // Failures are never cached: the same bad content fails again, not from the cache.
        assertThrows(IOException.class, () -> ModuleDocumentInspector.inspect(garbage));
    }

    @Test
    void definedEntitiesAreAxiomSubjectsNotReferencesOrBareDeclarations(@TempDir Path temp)
            throws Exception {
        Path module = temp.resolve("module.ofn");
        String ns = uniqueIri() + "#";
        Files.writeString(module, "Prefix(:=<" + ns + ">)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Ontology(<" + ns.substring(0, ns.length() - 1) + ">\n"
                + "Declaration(Class(:BareDeclared))\n"
                + "SubClassOf(:Child :BareDeclared)\n"
                + "SubClassOf(ObjectSomeValuesFrom(:p :Child) :Gci)\n"
                + "AnnotationAssertion(rdfs:label :Labelled \"labelled\")\n"
                + "ObjectPropertyDomain(:p :Child)\n"
                + "FunctionalObjectProperty(:q)\n"
                + "SubObjectPropertyOf(:r :s)\n"
                + "SubDataPropertyOf(:d :e)\n"
                + "DataPropertyRange(:d xsd:string)\n"
                + "AnnotationPropertyDomain(:a :Child)\n"
                + "SubAnnotationPropertyOf(:b rdfs:label)\n"
                + "EquivalentClasses(:EquivLeft :EquivRight)\n"
                + "DisjointClasses(:DisjointLeft :DisjointRight)\n"
                + ")\n");

        ModuleDocumentInspector.Inspection inspection = ModuleDocumentInspector.inspect(module);

        Set<String> defined = inspection.definedEntityIris();
        assertEquals(Set.of(ns + "Child", ns + "Labelled", ns + "p", ns + "q", ns + "r",
                ns + "d", ns + "a", ns + "b", ns + "EquivLeft", ns + "EquivRight",
                ns + "DisjointLeft", ns + "DisjointRight"), defined);
        // Bare declarations, referenced supertypes (super class/property positions) and GCIs are
        // NOT definitions; every named equivalence/disjointness member IS (each is constrained
        // as strongly as a SubClassOf subject).
        assertTrue(inspection.declaredEntityIris().contains(ns + "BareDeclared"));
        assertFalse(defined.contains(ns + "BareDeclared"));
        assertFalse(defined.contains(ns + "Gci"));
        assertFalse(defined.contains(ns + "s"));
        assertFalse(defined.contains(ns + "e"));
    }

    @Test
    void constrainingAxiomFamiliesDefineEveryNamedMemberTheyConstrain(@TempDir Path temp)
            throws Exception {
        Path module = temp.resolve("module.ofn");
        String ns = uniqueIri() + "#";
        Files.writeString(module, "Prefix(:=<" + ns + ">)\n"
                + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Ontology(<" + ns.substring(0, ns.length() - 1) + ">\n"
                // A named member of an equivalence is defined; the terms inside the anonymous
                // member and the expression's property are references.
                + "EquivalentClasses(:Defined ObjectSomeValuesFrom(:refP :RefOnly))\n"
                + "DisjointUnion(:Union :M1 :M2)\n"
                + "HasKey(:Keyed (:keyP) ())\n"
                + "DatatypeDefinition(:CustomType xsd:string)\n"
                + "EquivalentObjectProperties(:eop1 :eop2)\n"
                + "EquivalentDataProperties(:edp1 :edp2)\n"
                + "DisjointObjectProperties(:xop1 :xop2)\n"
                + "InverseObjectProperties(:iop1 :iop2)\n"
                + "SubObjectPropertyOf(ObjectPropertyChain(:link1 :link2) :chainSuper)\n"
                + "SameIndividual(:same1 :same2)\n"
                + "DifferentIndividuals(:diff1 :diff2)\n"
                + "ClassAssertion(:RefOnly :typed)\n"
                + "ObjectPropertyAssertion(:refP :subject :object)\n"
                + "DataPropertyAssertion(:refD :dataSubject \"v\")\n"
                + "NegativeObjectPropertyAssertion(:refP :negSubject :object)\n"
                + "NegativeDataPropertyAssertion(:refD :negDataSubject \"v\")\n"
                + ")\n");

        Set<String> defined = ModuleDocumentInspector.inspect(module).definedEntityIris();

        assertEquals(Set.of(ns + "Defined", ns + "Union", ns + "M1", ns + "M2", ns + "Keyed",
                ns + "CustomType", ns + "eop1", ns + "eop2", ns + "edp1", ns + "edp2",
                ns + "xop1", ns + "xop2", ns + "iop1", ns + "iop2", ns + "chainSuper",
                ns + "same1", ns + "same2", ns + "diff1", ns + "diff2", ns + "typed",
                ns + "subject", ns + "dataSubject", ns + "negSubject", ns + "negDataSubject"),
                defined);
        // References stay references: expression fillers, key/chain/assertion properties, the
        // asserted class, and the OBJECT individual of a property assertion.
        assertFalse(defined.contains(ns + "RefOnly"));
        assertFalse(defined.contains(ns + "refP"));
        assertFalse(defined.contains(ns + "refD"));
        assertFalse(defined.contains(ns + "keyP"));
        assertFalse(defined.contains(ns + "link1"));
        assertFalse(defined.contains(ns + "object"));
    }

    @Test
    void inverseWrappedPropertySubjectsAndSwrlHeadsAreDefined(@TempDir Path temp) throws Exception {
        Path module = temp.resolve("module.ofn");
        String ns = uniqueIri() + "#";
        Files.writeString(module, "Prefix(:=<" + ns + ">)\n"
                + "Ontology(<" + ns.substring(0, ns.length() - 1) + ">\n"
                // ObjectInverseOf must not hide the constrained property (the natural Turtle
                // owl:inverseOf authoring form): each of these re-axiomatises invP.
                + "EquivalentObjectProperties(:ownP ObjectInverseOf(:invEq))\n"
                + "TransitiveObjectProperty(ObjectInverseOf(:invChar))\n"
                + "ObjectPropertyDomain(ObjectInverseOf(:invDom) :C)\n"
                + "SubObjectPropertyOf(ObjectInverseOf(:invSub) :ownSuper)\n"
                + "SubObjectPropertyOf(ObjectPropertyChain(:l1 :l2) ObjectInverseOf(:invChainSuper))\n"
                // A rule HEAD asserting a foreign class/property membership re-axiomatises it; the
                // body is the antecedent (references only).
                + "DLSafeRule(Body(ClassAtom(:BodyOnly Variable(:x))) "
                + "Head(ClassAtom(:HeadClass Variable(:x))))\n"
                + "DLSafeRule(Body(ClassAtom(:BodyOnly Variable(:x))) "
                + "Head(ObjectPropertyAtom(:headProp Variable(:x) Variable(:x))))\n"
                + ")\n");

        Set<String> defined = ModuleDocumentInspector.inspect(module).definedEntityIris();

        assertTrue(defined.contains(ns + "invEq"), defined::toString);
        assertTrue(defined.contains(ns + "invChar"), defined::toString);
        assertTrue(defined.contains(ns + "invDom"), defined::toString);
        assertTrue(defined.contains(ns + "invSub"), defined::toString);
        assertTrue(defined.contains(ns + "invChainSuper"), defined::toString);
        assertTrue(defined.contains(ns + "HeadClass"), defined::toString);
        assertTrue(defined.contains(ns + "headProp"), defined::toString);
        // The owned super-property and the rule body predicate stay references.
        assertFalse(defined.contains(ns + "BodyOnly"), defined::toString);
        assertFalse(defined.contains(ns + "l1"), defined::toString);
    }

    @Test
    void swrlHeadNamedIndividualArgumentsAreDefined(@TempDir Path temp) throws Exception {
        Path module = temp.resolve("module.ofn");
        String ns = uniqueIri() + "#";
        Files.writeString(module, "Prefix(:=<" + ns + ">)\n"
                + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                + "Ontology(<" + ns.substring(0, ns.length() - 1) + ">\n"
                // A rule HEAD re-axiomatises the NAMED individuals whose facts/identity it constrains, but
                // only in SUBJECT position — a property-atom OBJECT is a reference, exactly like a direct
                // OWLPropertyAssertionAxiom. Same/DifferentIndividual atoms constrain BOTH members.
                + "DLSafeRule(Body(ClassAtom(:BodyOnly Variable(:x))) "
                + "Head(ClassAtom(:HeadClass :classIndiv)))\n"
                + "DLSafeRule(Body(ClassAtom(:BodyOnly Variable(:x))) "
                + "Head(ObjectPropertyAtom(:headProp :propSubject :propObject)))\n"
                + "DLSafeRule(Body(ClassAtom(:BodyOnly Variable(:x))) "
                + "Head(SameIndividualAtom(:sameLeft :sameRight)))\n"
                + "DLSafeRule(Body(ClassAtom(:BodyOnly Variable(:x))) "
                + "Head(DifferentIndividualsAtom(:diffLeft :diffRight)))\n"
                // A head data-range atom over a foreign NAMED datatype re-axiomatises it; a built-in
                // datatype (xsd:string) is owned by no module and must not be collected.
                + "DLSafeRule(Body(DataPropertyAtom(:val Variable(:x) Variable(:v))) "
                + "Head(DataRangeAtom(:ForeignDatatype Variable(:v))))\n"
                + "DLSafeRule(Body(DataPropertyAtom(:val Variable(:x) Variable(:v))) "
                + "Head(DataRangeAtom(xsd:string Variable(:v))))\n"
                + ")\n");

        Set<String> defined = ModuleDocumentInspector.inspect(module).definedEntityIris();

        assertTrue(defined.contains(ns + "classIndiv"), defined::toString);
        assertTrue(defined.contains(ns + "ForeignDatatype"), defined::toString);
        assertFalse(defined.contains("http://www.w3.org/2001/XMLSchema#string"), defined::toString);
        assertTrue(defined.contains(ns + "propSubject"), defined::toString);
        assertTrue(defined.contains(ns + "sameLeft"), defined::toString);
        assertTrue(defined.contains(ns + "sameRight"), defined::toString);
        assertTrue(defined.contains(ns + "diffLeft"), defined::toString);
        assertTrue(defined.contains(ns + "diffRight"), defined::toString);
        // Predicates are still collected; the rule body predicate stays a reference.
        assertTrue(defined.contains(ns + "HeadClass"), defined::toString);
        assertTrue(defined.contains(ns + "headProp"), defined::toString);
        assertFalse(defined.contains(ns + "BodyOnly"), defined::toString);
        // The property-atom OBJECT position is a reference, NOT a definition — a module referencing a
        // foreign individual there must not be flagged as re-axiomatising it.
        assertFalse(defined.contains(ns + "propObject"), defined::toString);
    }

    /** Unique per-invocation IRIs keep the content-addressed cache from cross-test collisions. */
    private static String uniqueIri() {
        return "https://example.org/inspector/" + UUID.randomUUID();
    }

    private static String turtle(String ontologyIri, String body) {
        return "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + ontologyIri + "> a owl:Ontology .\n" + body;
    }
}
