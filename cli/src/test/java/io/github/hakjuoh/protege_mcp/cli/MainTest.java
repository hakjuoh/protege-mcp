package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path temp;

    @Test
    void versionAndUsageExitCodesAreStable() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"--version"}, new PrintStream(out), new PrintStream(err)));
        assertEquals("protege-mcp-cli 0.6.0", out.toString().trim());

        assertEquals(2, Main.run(new String[] {"unknown"}, new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString().contains("Usage:"));
    }

    @Test
    void validatesPolicyAndDiffsOntologyDocumentsWithoutProtege() throws Exception {
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, """
                version: 1
                project_id: cli-test
                root_ontology: https://example.org/root
                validation:
                  required_stages: [profile, governance, structural]
                """, StandardCharsets.UTF_8);
        ByteArrayOutputStream policyOut = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"validate-policy", "--project", policy.toString()},
                new PrintStream(policyOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(policyOut.toString().contains("\"valid\" : true"));

        Path left = temp.resolve("left.ofn");
        Path right = temp.resolve("right.ofn");
        Files.writeString(left, "Ontology(<https://example.org/o> Declaration(Class(<https://example.org/A>)))");
        Files.writeString(right, "Ontology(<https://example.org/o> Declaration(Class(<https://example.org/B>)))");
        ByteArrayOutputStream diffOut = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"diff", "--left", left.toString(), "--right", right.toString()},
                new PrintStream(diffOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(diffOut.toString().contains("\"entities\""));
    }

    @Test
    void unparsableDocumentsReturnABoundedActionableError() throws Exception {
        Path bad = temp.resolve("not-an-ontology.txt");
        Files.writeString(bad, "this is not OWL");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertEquals(3, Main.run(new String[] {"diff", "--left", bad.toString(),
                "--right", bad.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err)));

        String message = err.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("none of") && message.contains("registered parsers"), message);
        assertFalse(message.contains("Detailed logs:"), message);
        assertTrue(message.length() < 2_000, "parser errors must stay bounded, got " + message.length());
    }

    @Test
    void diffToleratesUnresolvableImportsAndReportsThem() throws Exception {
        // The import IRI names a file that is never created, so it cannot resolve — deterministically
        // and without any network access. The asserted-only diff must survive it, not exit 3.
        String missingIri = temp.resolve("missing-import.ofn").toUri().toString();
        Path left = temp.resolve("left-import.ofn");
        Path right = temp.resolve("right-import.ofn");
        Files.writeString(left, "Ontology(<https://example.org/o>\n"
                + "Import(<" + missingIri + ">)\n"
                + "Declaration(Class(<https://example.org/A>))\n)\n", StandardCharsets.UTF_8);
        Files.writeString(right,
                "Ontology(<https://example.org/o> Declaration(Class(<https://example.org/A>)))",
                StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0,
                Main.run(new String[] {"diff", "--left", left.toString(), "--right", right.toString()},
                        new PrintStream(out), new PrintStream(err)),
                () -> "diff must not abort on an unresolvable import: " + err);
        String json = out.toString();
        assertTrue(json.contains("\"left_unresolved_imports\""),
                "the truncated left side is reported under a dedicated key");
        assertTrue(json.contains(missingIri), "the unresolved import IRI is named in the output");
        assertTrue(json.contains("\"right_unresolved_imports\" : [ ]"),
                "the fully resolved right side reports an empty list");
    }

    @Test
    void manchesterDocumentsWithImportsDiffWithoutFetchingThem() throws Exception {
        // OWLAPI's Manchester frame parser dereferences each imported ontology without a null guard,
        // so a blocked import must still RESOLVE (to an empty local placeholder) rather than merely
        // fail under silent missing-import handling — otherwise every .omn document with an Import:
        // line aborts the whole diff with exit 3. The import target exists and is never read.
        Path imported = temp.resolve("vocab.ofn");
        Files.writeString(imported,
                "Ontology(<https://example.org/vocab> Declaration(Class(<https://example.org/V>)))",
                StandardCharsets.UTF_8);
        String importedIri = imported.toUri().toString();
        Path left = temp.resolve("left.omn");
        Path right = temp.resolve("right.omn");
        Files.writeString(left, "Ontology: <https://example.org/o>\n"
                + "Import: <" + importedIri + ">\n"
                + "Class: <https://example.org/A>\n", StandardCharsets.UTF_8);
        Files.writeString(right, "Ontology: <https://example.org/o>\n"
                + "Class: <https://example.org/B>\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0,
                Main.run(new String[] {"diff", "--left", left.toString(), "--right", right.toString()},
                        new PrintStream(out), new PrintStream(err)),
                () -> "Manchester documents with imports must diff, not abort: " + err);
        String json = out.toString();
        assertTrue(json.contains(importedIri), "the skipped import is reported");
        assertFalse(json.contains("https://example.org/V"),
                "axioms of the existing, resolvable import target stay out of the asserted diff");
        assertTrue(json.contains("https://example.org/A") && json.contains("https://example.org/B"),
                "the asserted entity difference is still fully diffed");
    }

    @Test
    void oneSharedPlaceholderSatisfiesEveryImportInADocument() throws Exception {
        // All import IRIs map to ONE placeholder file, so a hostile document declaring arbitrarily
        // many imports cannot amplify temp-file usage. The first import loads the placeholder and
        // the manager must resolve every further import IRI to that same already-loaded anonymous
        // ontology by document IRI — exercised through the Manchester parser, which dereferences
        // each imported ontology mid-parse and would abort on any import that failed to resolve.
        Path left = temp.resolve("left-two-imports.omn");
        Path right = temp.resolve("right-two-imports.omn");
        Files.writeString(left, "Ontology: <https://example.org/o>\n"
                + "Import: <https://example.org/imports/one>\n"
                + "Import: <https://example.org/imports/two>\n"
                + "Class: <https://example.org/A>\n", StandardCharsets.UTF_8);
        Files.writeString(right, "Ontology: <https://example.org/o>\n"
                + "Class: <https://example.org/A>\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0,
                Main.run(new String[] {"diff", "--left", left.toString(), "--right", right.toString()},
                        new PrintStream(out), new PrintStream(err)),
                () -> "every import must resolve to the shared placeholder: " + err);
        assertTrue(out.toString().contains("\"left_unresolved_imports\" : "
                        + "[ \"https://example.org/imports/one\", \"https://example.org/imports/two\" ]"),
                "both declared imports are reported, sorted");
    }

    @Test
    void turtleDocumentsImportingTheirOwnIriStillDiff() throws Exception {
        // Turtle (and RDF/XML) set the root's ontology IRI only at the END of the streaming parse,
        // so a legal self-import resolves the blocked placeholder BEFORE the root claims the IRI.
        // The placeholder must stay anonymous: one that declared the import IRI would collide with
        // the root's trailing SetOntologyID and abort the load with "Ontology already exists".
        Path left = temp.resolve("left-self.ttl");
        Path right = temp.resolve("right-self.ttl");
        Files.writeString(left, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/self> a owl:Ontology ; owl:imports <https://example.org/self> .\n"
                + "<https://example.org/A> a owl:Class .\n", StandardCharsets.UTF_8);
        Files.writeString(right, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/self> a owl:Ontology .\n"
                + "<https://example.org/A> a owl:Class .\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0,
                Main.run(new String[] {"diff", "--left", left.toString(), "--right", right.toString()},
                        new PrintStream(out), new PrintStream(err)),
                () -> "a self-importing Turtle document must diff, not abort: " + err);
        String json = out.toString();
        assertTrue(json.contains("\"left_unresolved_imports\" : [ \"https://example.org/self\" ]"),
                "the self-import is reported like any other declared import");
        assertTrue(json.contains("\"right_unresolved_imports\" : [ ]"));
    }

    @Test
    void assertedDiffNeverContactsAnImportedHttpDocument() throws Exception {
        AtomicBoolean contacted = new AtomicBoolean(false);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread observer = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    contacted.set(true);
                } catch (java.io.IOException closedWithoutAConnection) {
                    // Expected after the diff finishes without contacting the listener.
                }
            }, "cli-diff-import-observer");
            observer.setDaemon(true);
            observer.start();

            String importIri = "http://127.0.0.1:" + server.getLocalPort() + "/private-import.ofn";
            Path left = temp.resolve("left-http-import.ofn");
            Path right = temp.resolve("right-http-import.ofn");
            Files.writeString(left, "Ontology(<https://example.org/o>\n"
                    + "Import(<" + importIri + ">)\n"
                    + "Declaration(Class(<https://example.org/A>))\n)\n", StandardCharsets.UTF_8);
            Files.writeString(right,
                    "Ontology(<https://example.org/o> Declaration(Class(<https://example.org/A>)))",
                    StandardCharsets.UTF_8);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(0,
                    Main.run(new String[] {"diff", "--left", left.toString(), "--right", right.toString()},
                            new PrintStream(out), new PrintStream(err)),
                    () -> "asserted diff failed: " + err);
            server.close();
            observer.join(2_000L);

            assertFalse(contacted.get(), "asserted-only diff must not dereference owl:imports");
            assertTrue(out.toString().contains(importIri),
                    "the blocked import is still reported as unresolved");
        }
    }

    @Test
    void reasonerStageMustNameItsReasonerEvenHeadlessly() throws Exception {
        // Headless validation has no installed-reasoner registry, but a policy that requires the
        // reasoner stage without naming a reasoner is unreproducible and must fail validation.
        Path unnamed = temp.resolve("reasoner-unnamed.yaml");
        Files.writeString(unnamed, """
                version: 1
                project_id: cli-reasoner-unnamed
                root_ontology: https://example.org/root
                validation:
                  required_stages: [reasoner]
                """, StandardCharsets.UTF_8);
        ByteArrayOutputStream unnamedOut = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"validate-policy", "--project", unnamed.toString()},
                new PrintStream(unnamedOut), new PrintStream(new ByteArrayOutputStream())));
        String unnamedJson = unnamedOut.toString();
        assertTrue(unnamedJson.contains("\"valid\" : false"));
        assertTrue(unnamedJson.contains("reasoner_unspecified"),
                "the missing reasoner name is reported as reasoner_unspecified");

        // Naming the reasoner keeps the policy valid headlessly: availability against the installed
        // registry is deliberately deferred to the adapter that executes project QC.
        Path named = temp.resolve("reasoner-named.yaml");
        Files.writeString(named, """
                version: 1
                project_id: cli-reasoner-named
                root_ontology: https://example.org/root
                reasoning:
                  reasoner: ELK
                validation:
                  required_stages: [reasoner]
                """, StandardCharsets.UTF_8);
        ByteArrayOutputStream namedOut = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"validate-policy", "--project", named.toString()},
                new PrintStream(namedOut), new PrintStream(new ByteArrayOutputStream())));
        String namedJson = namedOut.toString();
        assertTrue(namedJson.contains("\"valid\" : true"));
        assertFalse(namedJson.contains("reasoner_unavailable"),
                "no availability check may run without an installed-reasoner registry");
    }
}
