package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

class ModulePolicyGovernanceTest {

    @Test
    void definingATermInAForeignOwnedNamespaceBecomesAnErrorCheck(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        // Declaration PLUS a subject-position axiom: the foreign module defines the term.
        writeModule(temp.resolve("foreign.ttl"), "https://example.org/foreign",
                "<https://example.org/ns/WrongModule> a owl:Class .",
                "<https://example.org/ns/WrongModule> rdfs:label \"hijacked\" .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns/"),
                module("foreign.ttl", "https://example.org/foreign", null));

        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(policy, 25);

        assertEquals(1, checks.size());
        assertEquals("module_owned_namespace", checks.get(0).get("id"));
        assertEquals("error", checks.get(0).get("severity"));
        assertEquals(1, checks.get(0).get("count"));
        assertTrue(String.valueOf(checks.get(0)).contains("WrongModule"));
    }

    @Test
    void namespaceCoOwnersMayDefineTermsInEitherModule(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        writeModule(temp.resolve("foreign.ttl"), "https://example.org/foreign",
                "<https://example.org/ns/AlsoOwned> a owl:Class .",
                "<https://example.org/ns/AlsoOwned> rdfs:label \"co-owned\" .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns/"),
                module("foreign.ttl", "https://example.org/foreign", "https://example.org/ns/"));
        assertTrue(ModulePolicyGovernance.moduleChecks(policy, 25).isEmpty());
    }

    @Test
    void bareForeignDeclarationsArePermittedSupportingDeclarations(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Organization> a owl:Class .",
                "<https://example.org/ns/Organization> rdfs:label \"org\" .");
        // The OWLAPI module-extraction reality: a standalone module referencing a foreign term
        // carries a serializer-added bare Declaration for it. Referencing is legal; the foreign
        // term is never the SUBJECT of an axiom here, so nothing is defined outside its owner.
        writeModule(temp.resolve("extension.ttl"), "https://example.org/extension",
                "<https://example.org/ns/Organization> a owl:Class .",
                "<https://example.org/ext/Bank> a owl:Class .",
                "<https://example.org/ext/Bank> rdfs:subClassOf <https://example.org/ns/Organization> .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns/"),
                module("extension.ttl", "https://example.org/extension", "https://example.org/ext/"));
        assertTrue(ModulePolicyGovernance.moduleChecks(policy, 25).isEmpty());
    }

    @Test
    void definingAForeignTermWithoutDeclaringItIsStillAViolation(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        // No rdf:type triple, so no Declaration axiom: the foreign owned term is silently
        // re-defined through a subject-position subclass axiom alone.
        writeModule(temp.resolve("foreign.ttl"), "https://example.org/foreign",
                "<https://example.org/own/Y> a owl:Class .",
                "<https://example.org/ns/Hijacked> rdfs:subClassOf <https://example.org/own/Y> .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns/"),
                module("foreign.ttl", "https://example.org/foreign", "https://example.org/own/"));

        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(policy, 25);

        assertEquals(1, checks.size());
        assertEquals("module_owned_namespace", checks.get(0).get("id"));
        assertTrue(String.valueOf(checks.get(0)).contains("Hijacked"));
    }

    @Test
    void equivalentClassesRedefiningAForeignOwnedClassIsAViolation(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        // The canonical defined-class hijack: there is no owned subject anywhere — the
        // equivalence constrains the foreign class as strongly as SubClassOf(foreign, X) would.
        writeModule(temp.resolve("foreign.ttl"), "https://example.org/foreign",
                "<https://example.org/own/Thing> a owl:Class .",
                "<https://example.org/own/p> a owl:ObjectProperty .",
                "<https://example.org/ns/Owned> a owl:Class .",
                "<https://example.org/ns/Owned> owl:equivalentClass [ a owl:Restriction ;"
                        + " owl:onProperty <https://example.org/own/p> ;"
                        + " owl:someValuesFrom <https://example.org/own/Thing> ] .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns/"),
                module("foreign.ttl", "https://example.org/foreign", "https://example.org/own/"));

        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(policy, 25);

        assertEquals(1, checks.size());
        assertEquals("module_owned_namespace", checks.get(0).get("id"));
        assertEquals("error", checks.get(0).get("severity"));
        assertTrue(String.valueOf(checks.get(0)).contains("Owned"));
    }

    @Test
    void sameIndividualMergeAndForeignSubjectAssertionsAreViolations(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/canonical> a owl:NamedIndividual .");
        writeModule(temp.resolve("foreign.ttl"), "https://example.org/foreign",
                "<https://example.org/own/LocalClass> a owl:Class .",
                "<https://example.org/own/p> a owl:ObjectProperty .",
                "<https://example.org/own/alias> a owl:NamedIndividual .",
                // Merging a foreign individual's identity...
                "<https://example.org/ns/canonical> owl:sameAs <https://example.org/own/alias> .",
                // ...and attaching facts to foreign-owned SUBJECT individuals.
                "<https://example.org/ns/typed> a <https://example.org/own/LocalClass> .",
                "<https://example.org/ns/linked> <https://example.org/own/p> "
                        + "<https://example.org/own/alias> .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns/"),
                module("foreign.ttl", "https://example.org/foreign", "https://example.org/own/"));

        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(policy, 25);

        assertEquals(1, checks.size());
        assertEquals("module_owned_namespace", checks.get(0).get("id"));
        assertEquals(3, checks.get(0).get("count"));
        String rendered = String.valueOf(checks.get(0));
        assertTrue(rendered.contains("canonical"));
        assertTrue(rendered.contains("typed"));
        assertTrue(rendered.contains("linked"));
    }

    @Test
    void assertionsAboutOwnedIndividualsMayReferenceForeignTerms(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Status> a owl:Class .",
                "<https://example.org/ns/reference> a owl:NamedIndividual .");
        // The foreign class and individual sit in the CLASS and OBJECT positions of facts about
        // an OWN individual: referencing, not defining.
        writeModule(temp.resolve("extension.ttl"), "https://example.org/extension",
                "<https://example.org/own/p> a owl:ObjectProperty .",
                "<https://example.org/own/mine> a <https://example.org/ns/Status> .",
                "<https://example.org/own/mine> <https://example.org/own/p> "
                        + "<https://example.org/ns/reference> .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns/"),
                module("extension.ttl", "https://example.org/extension", "https://example.org/own/"));
        assertTrue(ModulePolicyGovernance.moduleChecks(policy, 25).isEmpty());
    }

    @Test
    void delimiterNamedSiblingNamespacesAreNeverCaptured(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns#Owned> a owl:Class .");
        // '-', '.' and '_' are namespace-forming characters, not IRI separators: …/ns-ext is a
        // SIBLING of …/ns (the prov vs prov-o pattern), never a continuation inside it.
        writeModule(temp.resolve("sibling.ttl"), "https://example.org/sibling",
                "<https://example.org/ns-ext/X> a owl:Class .",
                "<https://example.org/ns-ext/X> rdfs:label \"hyphen sibling\" .",
                "<https://example.org/ns.ext/Y> a owl:Class .",
                "<https://example.org/ns.ext/Y> rdfs:label \"dot sibling\" .",
                "<https://example.org/ns_ext/Z> a owl:Class .",
                "<https://example.org/ns_ext/Z> rdfs:label \"underscore sibling\" .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns"),
                module("sibling.ttl", "https://example.org/sibling", null));
        assertTrue(ModulePolicyGovernance.moduleChecks(policy, 25).isEmpty());

        // Structural-separator continuations stay inside the boundary.
        writeModule(temp.resolve("sibling.ttl"), "https://example.org/sibling",
                "<https://example.org/ns/Slash> a owl:Class .",
                "<https://example.org/ns/Slash> rdfs:label \"inside via /\" .");
        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(reload(temp), 25);
        assertEquals(1, checks.size());
        assertEquals("module_owned_namespace", checks.get(0).get("id"));
        assertTrue(String.valueOf(checks.get(0)).contains("Slash"));
    }

    @Test
    void syntheticModulesOwningTheirOwnNamespacesPass(@TempDir Path temp) throws Exception {
        // Self-authored modules exercise the same realistic legal positions without vendoring an
        // external ontology: own-property equivalence/inverse axioms, disjointness with an unowned
        // upstream class, assertions about own individuals, restrictions referencing foreign terms,
        // and a bare supporting declaration for a referenced foreign class.
        String base = "https://example.org/modular/";
        writeModule(temp.resolve("people.ttl"), base + "people",
                "<" + base + "people/Person> a owl:Class .",
                "<" + base + "upstream/Agent> a owl:Class .",
                "<" + base + "people/Person> rdfs:subClassOf <" + base + "upstream/Agent> .",
                "<" + base + "people/alice> a owl:NamedIndividual, <" + base
                        + "people/Person> .");
        writeModule(temp.resolve("relations.ttl"), base + "relations",
                "<" + base + "relations/knows> a owl:ObjectProperty .",
                "<" + base + "relations/isKnownBy> a owl:ObjectProperty .",
                "<" + base + "relations/knows> owl:equivalentProperty <" + base
                        + "relations/isKnownBy> .",
                "<" + base + "relations/knows> owl:inverseOf <" + base
                        + "relations/isKnownBy> .");
        writeModule(temp.resolve("places.ttl"), base + "places",
                "<" + base + "places/Address> a owl:Class .",
                "<" + base + "upstream/Region> a owl:Class .",
                "<" + base + "places/Address> owl:disjointWith <" + base
                        + "upstream/Region> .");
        writeModule(temp.resolve("dates.ttl"), base + "dates",
                "<" + base + "dates/EventDate> a owl:Class .",
                "<" + base + "upstream/TemporalThing> a owl:Class .",
                "<" + base + "dates/hasPart> a owl:ObjectProperty .",
                "<" + base + "dates/EventDate> owl:equivalentClass [ a owl:Restriction ;"
                        + " owl:onProperty <" + base + "dates/hasPart> ;"
                        + " owl:someValuesFrom <" + base + "upstream/TemporalThing> ] .");
        ProjectPolicy policy = writePolicy(temp,
                module("people.ttl", base + "people", base + "people/"),
                module("relations.ttl", base + "relations", base + "relations/"),
                module("places.ttl", base + "places", base + "places/"),
                module("dates.ttl", base + "dates", base + "dates/"));

        assertTrue(ModulePolicyGovernance.moduleChecks(policy, 25).isEmpty());
    }

    @Test
    void alphanumericNamespaceEndOwnsOnlyDelimiterBoundedContinuations(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns#Owned> a owl:Class .");
        // https://example.org/ns owns .../ns and delimiter continuations (.../ns#X, .../ns/X)
        // but NOT the sibling namespace .../ns2/.
        writeModule(temp.resolve("sibling.ttl"), "https://example.org/sibling",
                "<https://example.org/ns2/X> a owl:Class .",
                "<https://example.org/ns2/X> rdfs:label \"sibling term\" .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns"),
                module("sibling.ttl", "https://example.org/sibling", "https://example.org/ns2/"));
        assertTrue(ModulePolicyGovernance.moduleChecks(policy, 25).isEmpty());

        writeModule(temp.resolve("sibling.ttl"), "https://example.org/sibling",
                "<https://example.org/ns#Trespass> a owl:Class .",
                "<https://example.org/ns#Trespass> rdfs:label \"inside the boundary\" .");
        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(
                reload(temp), 25);
        assertEquals(1, checks.size());
        assertEquals("module_owned_namespace", checks.get(0).get("id"));
        assertTrue(String.valueOf(checks.get(0)).contains("Trespass"));
    }

    @Test
    void mostSpecificOwnedNamespaceAloneDecidesOwnership(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("umbrella.ttl"), "https://example.org/umbrella",
                "<https://example.org/onto/Top> a owl:Class .",
                "<https://example.org/onto/Top> rdfs:label \"top\" .");
        writeModule(temp.resolve("sub.ttl"), "https://example.org/sub",
                "<https://example.org/onto/sub/Leaf> a owl:Class .",
                "<https://example.org/onto/sub/Leaf> rdfs:label \"leaf\" .");
        ProjectPolicy policy = writePolicy(temp,
                module("umbrella.ttl", "https://example.org/umbrella", "https://example.org/onto/"),
                module("sub.ttl", "https://example.org/sub", "https://example.org/onto/sub/"));
        // The submodule's term matches both namespaces; the longest match is the submodule's own.
        assertTrue(ModulePolicyGovernance.moduleChecks(policy, 25).isEmpty());

        // A trespasser into the submodule namespace is attributed to the MOST SPECIFIC owner only.
        writeModule(temp.resolve("umbrella.ttl"), "https://example.org/umbrella",
                "<https://example.org/onto/sub/Stolen> a owl:Class .",
                "<https://example.org/onto/sub/Stolen> rdfs:label \"stolen\" .");
        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(reload(temp), 25);
        assertEquals(1, checks.size());
        Map<String, Object> example = example(checks.get(0));
        assertEquals("https://example.org/onto/sub/", example.get("owned_namespace"));
        assertEquals(List.of("https://example.org/sub"), example.get("owning_modules"));
    }

    @Test
    void unreadableModuleDocumentFailsClosed(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        writeModule(temp.resolve("foreign.ttl"), "https://example.org/foreign",
                "<https://example.org/own/Fine> a owl:Class .");
        ProjectPolicy policy = writePolicy(temp,
                module("owner.ttl", "https://example.org/owner", "https://example.org/ns/"),
                module("foreign.ttl", "https://example.org/foreign", "https://example.org/own/"));
        Files.writeString(temp.resolve("foreign.ttl"), "@@@ not an ontology @@@\n");

        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(policy, 25);

        assertEquals(1, checks.size());
        assertEquals("module_inspection_failed", checks.get(0).get("id"));
        assertEquals("error", checks.get(0).get("severity"));
        assertTrue(String.valueOf(checks.get(0)).contains("foreign.ttl"));
    }

    @Test
    void moduleAndResolvedFileCountMismatchFailsClosed(@TempDir Path temp) throws Exception {
        writeModule(temp.resolve("owner.ttl"), "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        // The second module file never exists, so the loader resolves one asset for two
        // configured modules (the policy is loaded but invalid).
        Path policyPath = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("modules", "https://example.org/owner")
                        + "modules:\n"
                        + module("owner.ttl", "https://example.org/owner", "https://example.org/ns/")
                        + module("missing.ttl", "https://example.org/missing", null)
                        + "validation:\n  required_stages: [governance]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.loaded());

        List<Map<String, Object>> checks = ModulePolicyGovernance.moduleChecks(policy, 25);

        assertEquals(1, checks.size());
        assertEquals("module_inspection_failed", checks.get(0).get("id"));
        assertEquals("error", checks.get(0).get("severity"));
        assertTrue(String.valueOf(checks.get(0)).contains("2 module(s)"));
    }

    @Test
    void importCyclesBecomeWarnings() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology a = manager.createOntology(IRI.create("https://example.org/a"));
        OWLOntology b = manager.createOntology(IRI.create("https://example.org/b"));
        imports(manager, a, "https://example.org/b");
        imports(manager, b, "https://example.org/a");

        List<Map<String, Object>> checks = ModulePolicyGovernance.importChecks(
                ImportTools.analyze(a), 25);

        assertEquals(1, checks.size());
        assertEquals("import_cycle", checks.get(0).get("id"));
        assertEquals("warning", checks.get(0).get("severity"));
    }

    @Test
    void loadedVersionIdentityConflictsBecomeErrors() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/root"));
        String logical = "https://example.org/upstream";
        String v1 = logical + "/1";
        String v2 = logical + "/2";
        manager.createOntology(new OWLOntologyID(IRI.create(logical), IRI.create(v1)));
        manager.createOntology(new OWLOntologyID(IRI.create(logical), IRI.create(v2)));
        imports(manager, root, v1);
        imports(manager, root, v2);

        List<Map<String, Object>> checks = ModulePolicyGovernance.importChecks(
                ImportTools.analyze(root), 25);

        assertEquals(1, checks.size());
        assertEquals("import_identity_conflict", checks.get(0).get("id"));
        assertEquals("error", checks.get(0).get("severity"));
    }

    private static void imports(OWLOntologyManager manager, OWLOntology source, String target) {
        manager.applyChange(new AddImport(source, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(IRI.create(target))));
    }

    private static String module(String path, String ontologyIri, String ownedNamespace) {
        return "  - ontology_iri: " + ontologyIri + "\n"
                + "    path: " + path + "\n"
                + (ownedNamespace == null ? ""
                        : "    owned_namespaces: ['" + ownedNamespace + "']\n");
    }

    private static ProjectPolicy writePolicy(Path root, String... modules) throws Exception {
        Path policyPath = root.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("modules", "https://example.org/owner")
                        + "modules:\n" + String.join("", modules)
                        + "validation:\n  required_stages: [governance]\n");
        return reload(root);
    }

    private static ProjectPolicy reload(Path root) {
        ProjectPolicy policy = ProjectPolicyLoader.load(root.resolve("policy.yaml"), null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return policy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> example(Map<String, Object> check) {
        List<Map<String, Object>> examples = (List<Map<String, Object>>) check.get("examples");
        assertEquals(1, examples.size());
        return examples.get(0);
    }

    private static void writeModule(Path path, String ontology, String... turtle) throws Exception {
        StringBuilder document = new StringBuilder(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                        + "<" + ontology + "> a owl:Ontology .\n");
        for (String line : turtle) {
            document.append(line).append('\n');
        }
        Files.writeString(path, document.toString());
    }
}
