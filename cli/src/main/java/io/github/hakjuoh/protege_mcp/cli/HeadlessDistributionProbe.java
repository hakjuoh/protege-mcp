package io.github.hakjuoh.protege_mcp.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatypeRestriction;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLFacet;

import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityRegistry;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityReport;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerIdentity;

/**
 * Build-time probe for the reasoner-bearing standalone distribution.
 *
 * <p>This is deliberately a plain {@code main} class inside the shaded artifact: Maven launches it with
 * that artifact as the <em>only</em> application classpath, so a missing flattened HermiT dependency cannot
 * be masked by the reactor/test classpath. It is not a user-facing CLI command.</p>
 */
public final class HeadlessDistributionProbe {

    private HeadlessDistributionProbe() {
    }

    public static void main(String[] args) throws Exception {
        verifyRuntime();
        if (args.length != 0) {
            if (args.length != 5) {
                throw new IllegalArgumentException(
                        "Expected: SHADED_JAR COMPLIANCE_DIR LICENSE_DIR RELINK_GUIDE HERMIT_VERSION");
            }
            verifyDistribution(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                    args[4]);
            verifyReviewedShadedProfile();
        }
        System.out.println("Headless HermiT distribution probe passed."
                + (args.length == 5 ? " Version: " + args[4] : ""));
    }

    static void verifyRuntime() throws Exception {
        // These are the dependency edges that HermiT's OSGi jar otherwise hides as inert nested jars.
        requireClass("rationals.Automaton");
        requireClass("net.automatalib.automaton.fsa.impl.CompactNFA");
        requireClass("dk.brics.automaton.Automaton");
        requireClass("org.apache.axiom.om.OMNode");
        requireClass("org.apache.commons.logging.LogFactory");
        requireClass("io.modelcontextprotocol.server.transport.StdioServerTransportProvider");
        requireClass("io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper");

        verifySatisfiableAndUnsatisfiable();
        verifyInconsistent();
        verifyRoleChainAutomaton();
        verifyDatatypeCorners();
    }

    private static void verifyReviewedShadedProfile() {
        ReasonerFactory factory = new ReasonerFactory();
        ReasonerIdentity identity = ReasonerIdentity.capture(
                ReasonerFactory.class.getName(), "HermiT", factory,
                new SimpleConfiguration(120_000L), BufferingMode.BUFFERING,
                "headless_distribution_probe");
        ReasonerCapabilityReport report = new ReasonerCapabilityRegistry().report(identity);
        require("reviewed".equals(report.profileStatus()),
                "shaded HermiT runtime did not match the reviewed capability profile");
        require(identity.reviewedCodeClassCount() == 3_118,
                "shaded HermiT reviewed class count changed");
        require(Set.of(
                "sha256:c928f88672398c60bcf6b4445a5c753cf41accb4ec0f993042292ba9a1e6d1c8",
                "sha256:965c59092c1ae92a7f3abd7e4cd545bf2d3e248a491cae279c87deb78f9c3cf7")
                        .contains(identity.reviewedCodeDigest()),
                "shaded HermiT reviewed code digest changed");
    }

    private static void verifySatisfiableAndUnsatisfiable() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:probe:satisfiability"));
        OWLClass left = df.getOWLClass(IRI.create("urn:probe:Left"));
        OWLClass right = df.getOWLClass(IRI.create("urn:probe:Right"));
        OWLClass impossible = df.getOWLClass(IRI.create("urn:probe:Impossible"));
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(left, right));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(impossible, left));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(impossible, right));

        OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology);
        try {
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
            require(reasoner.isConsistent(), "satisfiable probe ontology was reported inconsistent");
            require(reasoner.getUnsatisfiableClasses().contains(impossible),
                    "HermiT did not report the deliberately unsatisfiable class");
        } finally {
            reasoner.dispose();
        }
    }

    private static void verifyInconsistent() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:probe:inconsistent"));
        OWLClass left = df.getOWLClass(IRI.create("urn:probe:InconsistentLeft"));
        OWLClass right = df.getOWLClass(IRI.create("urn:probe:InconsistentRight"));
        OWLNamedIndividual individual = df.getOWLNamedIndividual(IRI.create("urn:probe:individual"));
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(left, right));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(left, individual));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(right, individual));

        OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology);
        try {
            require(!reasoner.isConsistent(), "HermiT did not reject the deliberately inconsistent ontology");
        } finally {
            reasoner.dispose();
        }
    }

    private static void verifyDatatypeCorners() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:probe:datatypes"));
        OWLDataProperty value = df.getOWLDataProperty(IRI.create("urn:probe:value"));

        OWLDatatypeRestriction anyUri = df.getOWLDatatypeRestriction(
                OWL2Datatype.XSD_ANY_URI.getDatatype(df), OWLFacet.PATTERN,
                df.getOWLLiteral("https://example[.]org/.*"));
        OWLDatatypeRestriction plainPattern = df.getOWLDatatypeRestriction(
                OWL2Datatype.RDF_PLAIN_LITERAL.getDatatype(df),
                df.getOWLFacetRestriction(OWLFacet.PATTERN, df.getOWLLiteral("[A-Za-z ]+")),
                df.getOWLFacetRestriction(OWLFacet.MIN_LENGTH, 1));
        OWLClass uriCarrier = df.getOWLClass(IRI.create("urn:probe:UriCarrier"));
        OWLClass textCarrier = df.getOWLClass(IRI.create("urn:probe:TextCarrier"));
        manager.addAxiom(ontology,
                df.getOWLSubClassOfAxiom(uriCarrier, df.getOWLDataSomeValuesFrom(value, anyUri)));
        manager.addAxiom(ontology,
                df.getOWLSubClassOfAxiom(textCarrier, df.getOWLDataSomeValuesFrom(value, plainPattern)));
        OWLNamedIndividual xml = df.getOWLNamedIndividual(IRI.create("urn:probe:xml"));
        manager.addAxiom(ontology, df.getOWLDataPropertyAssertionAxiom(value, xml,
                df.getOWLLiteral("<root xmlns=\"urn:probe\"/>", OWL2Datatype.RDF_XML_LITERAL)));

        OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology);
        try {
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
            require(reasoner.isConsistent(), "datatype dependency probe ontology was reported inconsistent");
        } finally {
            reasoner.dispose();
        }
    }

    private static void verifyRoleChainAutomaton() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:probe:role-chain"));
        OWLObjectProperty first = df.getOWLObjectProperty(IRI.create("urn:probe:first"));
        OWLObjectProperty second = df.getOWLObjectProperty(IRI.create("urn:probe:second"));
        OWLObjectProperty composed = df.getOWLObjectProperty(IRI.create("urn:probe:composed"));
        OWLNamedIndividual start = df.getOWLNamedIndividual(IRI.create("urn:probe:start"));
        OWLNamedIndividual middle = df.getOWLNamedIndividual(IRI.create("urn:probe:middle"));
        OWLNamedIndividual end = df.getOWLNamedIndividual(IRI.create("urn:probe:end"));
        manager.addAxiom(ontology,
                df.getOWLSubPropertyChainOfAxiom(List.of(first, second), composed));
        manager.addAxiom(ontology,
                df.getOWLObjectPropertyAssertionAxiom(first, start, middle));
        manager.addAxiom(ontology,
                df.getOWLObjectPropertyAssertionAxiom(second, middle, end));

        OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology);
        try {
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
            require(reasoner.isConsistent(),
                    "role-chain automaton probe ontology was reported inconsistent");
            require(reasoner.getObjectPropertyValues(start, composed).containsEntity(end),
                    "HermiT did not infer the property-chain assertion");
        } finally {
            reasoner.dispose();
        }
    }

    static void verifyDistribution(Path shadedJar, Path complianceDir, Path licenseDir, Path relinkGuide,
            String hermitVersion) throws IOException {
        requireFile(shadedJar, 1_000_000, "shaded CLI");
        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            Set<String> required = Set.of(
                    "org/semanticweb/HermiT/ReasonerFactory.class",
                    "rationals/Automaton.class",
                    "net/automatalib/automaton/fsa/impl/CompactNFA.class",
                    "dk/brics/automaton/Automaton.class",
                    "org/apache/axiom/om/OMNode.class",
                    "org/semanticweb/owlapi/model/OWLOntology.class",
                    "io/modelcontextprotocol/server/transport/StdioServerTransportProvider.class",
                    "io/modelcontextprotocol/json/jackson2/JacksonMcpJsonMapper.class",
                    "io/github/hakjuoh/protege_mcp/cli/HeadlessStdioServer.class");
            for (String entry : required) {
                require(jar.getJarEntry(entry) != null, "shaded CLI is missing " + entry);
            }
            jar.stream().map(e -> e.getName()).filter(name -> name.endsWith(".jar")).forEach(name -> {
                throw new IllegalStateException("inert nested dependency survived shading: " + name);
            });
            require(jar.getJarEntry("org/protege/editor/owl/OWLEditorKit.class") == null,
                    "standalone CLI unexpectedly contains Protégé editor classes");
            require(jar.getJarEntry("rationals/converters/toAscii.class") == null,
                    "legacy JAutomata implementation survived replacement");
        }

        Path sourceJar = complianceDir.resolve("org.semanticweb.hermit-" + hermitVersion + "-sources.jar");
        requireFile(sourceJar, 100_000, "HermiT corresponding source");
        try (JarFile sources = new JarFile(sourceJar.toFile())) {
            require(sources.getJarEntry("org/semanticweb/HermiT/Reasoner.java") != null,
                    "HermiT source jar lacks Reasoner.java");
            require(sources.getJarEntry("rationals/Automaton.java") != null,
                    "HermiT source jar lacks the JAutomata/rationals source");
        }

        for (String license : List.of("GPL-2.0.txt", "LGPL-2.1.txt", "GPL-3.0.txt", "LGPL-3.0.txt",
                "Apache-2.0.txt", "dk.brics.automaton-BSD.txt", "Jaxen-BSD.txt", "Stax2-BSD.txt")) {
            long minimum = switch (license) {
                case "GPL-2.0.txt" -> 15_000;
                case "LGPL-2.1.txt" -> 20_000;
                case "GPL-3.0.txt" -> 30_000;
                case "LGPL-3.0.txt" -> 7_000;
                case "Apache-2.0.txt" -> 10_000;
                case "dk.brics.automaton-BSD.txt", "Jaxen-BSD.txt", "Stax2-BSD.txt" -> 1_000;
                default -> throw new IllegalStateException("unreviewed license input: " + license);
            };
            requireFile(licenseDir.resolve(license), minimum, license);
        }
        requireFile(relinkGuide, 500, "headless relinking guide");
        requireFile(relinkGuide.getParent().resolve("evidence/hermit-upstream-readme-65d3890.txt"), 5_000,
                "archived HermiT license evidence");
        String guide = Files.readString(relinkGuide);
        require(guide.contains("-Dhermit.version=") && guide.contains(hermitVersion),
                "relinking guide does not pin HermiT and the override command");
    }

    private static void requireClass(String name) throws ClassNotFoundException {
        Class.forName(name, false, HeadlessDistributionProbe.class.getClassLoader());
    }

    private static void requireFile(Path path, long minimumBytes, String label) throws IOException {
        require(Files.isRegularFile(path), label + " is missing: " + path);
        require(Files.size(path) >= minimumBytes, label + " is unexpectedly short: " + path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
