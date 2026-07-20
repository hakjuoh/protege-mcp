package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessDistributionProbeTest {

    private static final String VERSION = "1.3.8.431";
    private static final List<String> RUNTIME_ENTRIES = List.of(
            "org/semanticweb/HermiT/ReasonerFactory.class",
            "rationals/Automaton.class",
            "net/automatalib/automaton/fsa/impl/CompactNFA.class",
            "dk/brics/automaton/Automaton.class",
            "org/apache/axiom/om/OMNode.class",
            "org/semanticweb/owlapi/model/OWLOntology.class",
            "io/modelcontextprotocol/server/transport/StdioServerTransportProvider.class",
            "io/modelcontextprotocol/json/jackson2/JacksonMcpJsonMapper.class",
            "io/github/hakjuoh/protege_mcp/cli/HeadlessStdioServer.class");
    private static final List<String> LICENSES = List.of(
            "GPL-2.0.txt", "LGPL-2.1.txt", "GPL-3.0.txt", "LGPL-3.0.txt",
            "Apache-2.0.txt", "dk.brics.automaton-BSD.txt", "Jaxen-BSD.txt", "Stax2-BSD.txt");

    @TempDir
    Path temp;

    @Test
    void acceptsCompleteDistributionEvidence() throws Exception {
        Fixture fixture = fixture(null, true);
        assertDoesNotThrow(() -> fixture.verify());
    }

    @Test
    void rejectsInertNestedHermitDependency() throws Exception {
        Fixture fixture = fixture("lib/automaton-1.11-8.jar", true);
        assertThrows(IllegalStateException.class, fixture::verify);
    }

    @Test
    void rejectsLegacyJautomataRuntime() throws Exception {
        Fixture fixture = fixture("rationals/converters/toAscii.class", true);
        assertThrows(IllegalStateException.class, fixture::verify);
    }

    @Test
    void rejectsMissingLicenseText() throws Exception {
        Fixture fixture = fixture(null, true);
        Files.delete(fixture.licenses.resolve("LGPL-2.1.txt"));
        assertThrows(IllegalStateException.class, fixture::verify);
    }

    @Test
    void rejectsIncompleteHermitSourceArchive() throws Exception {
        Fixture fixture = fixture(null, false);
        assertThrows(IllegalStateException.class, fixture::verify);
    }

    private Fixture fixture(String nestedJar, boolean includeJautomataSource) throws IOException {
        Path shaded = temp.resolve("cli-" + System.nanoTime() + ".jar");
        writeJar(shaded, RUNTIME_ENTRIES, nestedJar, 1_100_000);

        Path compliance = Files.createDirectories(temp.resolve("compliance-" + System.nanoTime()));
        Path sources = compliance.resolve("org.semanticweb.hermit-" + VERSION + "-sources.jar");
        List<String> sourceEntries = includeJautomataSource
                ? List.of("org/semanticweb/HermiT/Reasoner.java", "rationals/Automaton.java")
                : List.of("org/semanticweb/HermiT/Reasoner.java");
        writeJar(sources, sourceEntries, null, 120_000);

        Path licenses = Files.createDirectories(temp.resolve("licenses-" + System.nanoTime()));
        for (String license : LICENSES) {
            Files.writeString(licenses.resolve(license), "license evidence\n".repeat(2_500));
        }

        Path docs = Files.createDirectories(temp.resolve("docs-" + System.nanoTime()));
        Path evidence = Files.createDirectories(docs.resolve("evidence"));
        Files.writeString(evidence.resolve("hermit-upstream-readme-65d3890.txt"),
                "upstream evidence\n".repeat(400));
        Path guide = docs.resolve("headless-relinking.md");
        Files.writeString(guide,
                ("HermiT 1.3.8.431\n-Dhermit.version=1.3.8.431-local\n").repeat(20));
        return new Fixture(shaded, compliance, licenses, guide);
    }

    private static void writeJar(Path path, List<String> entries, String extraEntry, int paddingBytes)
            throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (String entry : entries) {
                put(out, entry, new byte[] {0});
            }
            if (extraEntry != null) {
                put(out, extraEntry, new byte[] {1});
            }
            byte[] padding = new byte[paddingBytes];
            new Random(7).nextBytes(padding);
            put(out, "padding.bin", padding);
        }
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private record Fixture(Path shaded, Path compliance, Path licenses, Path guide) {
        void verify() throws IOException {
            HeadlessDistributionProbe.verifyDistribution(shaded, compliance, licenses, guide, VERSION);
        }
    }
}
