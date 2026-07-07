package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import uk.ac.manchester.cs.owlapi.modularity.ModuleType;

/**
 * Exercises the Protégé-free locality-extraction core behind {@code extract_module} on a hand-built
 * ontology (no running Protégé), plus the {@code module_type} argument parsing. The assertions match the
 * three module directions: BOT (⊥) pulls what the seed <em>uses</em> (its superclasses/definitions),
 * TOP (⊤) pulls what <em>uses</em> the seed (its subtree), and STAR nests the two into the smallest
 * module (so it includes a defined seed's definition, but is empty for a bare asserted subclass whose
 * ⊤-module is empty).
 */
class ModuleToolsTest {

    private static final String NS = "http://example.org/mod#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    /** Animal; Dog ⊑ Animal; Cat ⊑ Animal; Plant (unrelated). */
    private OWLOntology source() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/mod"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(cls(df, "Animal")));
        m.addAxiom(o, df.getOWLDeclarationAxiom(cls(df, "Plant")));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal")));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(cls(df, "Cat"), cls(df, "Animal")));
        return o;
    }

    private Set<OWLEntity> signature(Set<OWLAxiom> axioms) {
        Set<OWLEntity> sig = new LinkedHashSet<>();
        for (OWLAxiom ax : axioms) {
            sig.addAll(ax.getSignature());
        }
        return sig;
    }

    @Test
    void botModulePullsTheSeedsSuperclassButNotUnrelatedClasses()
            throws OWLOntologyCreationException {
        OWLOntology o = source();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");

        Set<OWLAxiom> module = ModuleTools.extract(o, Collections.singleton(dog), ModuleType.BOT);
        Set<OWLEntity> sig = signature(module);

        assertTrue(module.contains(df.getOWLSubClassOfAxiom(dog, animal)),
                "the ⊥-module of a seed includes the axiom giving its superclass");
        assertTrue(sig.contains(dog) && sig.contains(animal), "seed and its superclass are in the module");
        assertFalse(sig.contains(cls(df, "Cat")), "an unrelated sibling is not pulled in");
        assertFalse(sig.contains(cls(df, "Plant")), "an unrelated class is not pulled in");
    }

    @Test
    void topModulePullsTheSeedsSubtree() throws OWLOntologyCreationException {
        OWLOntology o = source();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = cls(df, "Animal");

        Set<OWLAxiom> module = ModuleTools.extract(o, Collections.singleton(animal), ModuleType.TOP);
        assertTrue(module.contains(df.getOWLSubClassOfAxiom(cls(df, "Dog"), animal)),
                "the ⊤-module of a superclass pulls in its subclasses");
        assertTrue(module.contains(df.getOWLSubClassOfAxiom(cls(df, "Cat"), animal)),
                "both subclasses of the seed are pulled in");
        assertFalse(signature(module).contains(cls(df, "Plant")), "an unrelated class stays out");
    }

    @Test
    void starModuleOfADefinedClassIncludesItsDefinition() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/mod"));
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");
        OWLObjectProperty hasOwner = df.getOWLObjectProperty(IRI.create(NS + "hasOwner"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        m.addAxiom(o, df.getOWLDeclarationAxiom(cls(df, "Plant")));
        m.addAxiom(o, df.getOWLEquivalentClassesAxiom(pet,
                df.getOWLObjectSomeValuesFrom(hasOwner, animal)));

        Set<OWLAxiom> module = ModuleTools.extract(o, Collections.singleton(pet), ModuleType.STAR);
        assertTrue(module.contains(df.getOWLEquivalentClassesAxiom(pet,
                        df.getOWLObjectSomeValuesFrom(hasOwner, animal))),
                "the STAR module of a defined class includes its definition");
        Set<OWLEntity> sig = signature(module);
        assertTrue(sig.contains(pet) && sig.contains(animal) && sig.contains(hasOwner),
                "the definition's whole signature is present");
        assertFalse(sig.contains(cls(df, "Plant")), "an unrelated class stays out");
    }

    // ---------------------------------------------------------------- module_type parsing

    @Test
    void moduleTypeDefaultsToStarAndParsesEachAlias() {
        assertSame(ModuleType.STAR, ModuleTools.moduleType(null), "null defaults to STAR");
        assertSame(ModuleType.STAR, ModuleTools.moduleType(""), "empty defaults to STAR");
        assertSame(ModuleType.STAR, ModuleTools.moduleType("star"));
        assertSame(ModuleType.BOT, ModuleTools.moduleType("BOT"));
        assertSame(ModuleType.BOT, ModuleTools.moduleType(" bottom "));
        assertSame(ModuleType.TOP, ModuleTools.moduleType("Top"));
    }

    @Test
    void unknownModuleTypeIsRejected() {
        assertThrows(ToolArgException.class, () -> ModuleTools.moduleType("BOT_OF_TOP"),
                "an OWLAPI-5 name absent from 4.5's ModuleType must be rejected, not silently accepted");
        assertThrows(ToolArgException.class, () -> ModuleTools.moduleType("nonsense"));
    }

    @Test
    void extractOverEmptySeedYieldsEmptyModule() throws OWLOntologyCreationException {
        Set<OWLAxiom> module = ModuleTools.extract(source(), Collections.<OWLEntity>emptySet(),
                ModuleType.STAR);
        assertEquals(0, signature(module).size(), "an empty seed extracts nothing");
    }
}
