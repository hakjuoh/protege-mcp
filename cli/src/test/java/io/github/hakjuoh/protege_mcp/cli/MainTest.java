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

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

class MainTest {

    @TempDir
    Path temp;

    @Test
    void versionAndUsageExitCodesAreStable() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"--version"}, new PrintStream(out), new PrintStream(err)));
        assertEquals("protege-mcp-cli 0.7.0", out.toString().trim());

        assertEquals(2, Main.run(new String[] {"unknown"}, new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString().contains("Usage:"));
    }

    @Test
    void helpPrintsUsageOnStdoutAndExitsZero() {
        for (String alias : new String[] {"--help", "-h", "help"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(0, Main.run(new String[] {alias}, new PrintStream(out), new PrintStream(err)),
                    () -> alias + " is an explicit help request and must exit 0");
            assertTrue(out.toString().contains("Usage:"),
                    () -> alias + " prints the usage text on STDOUT");
            assertEquals("", err.toString(), () -> alias + " must leave stderr empty");
        }

        // With an extra argument it is no longer an exact help request: it falls through to the
        // usage-on-stderr configuration error, like any other malformed invocation.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"--help", "extra"},
                new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString().contains("Usage:"), "usage goes to stderr for the malformed form");
    }

    @Test
    void markdownPolicyLoadedMatchesTheJsonHonestyRefinement() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Path missing = temp.resolve("missing-md.yaml");
        assertEquals(2, Main.run(new String[] {"validate", "--project", missing.toString(),
                "--format", "markdown"}, new PrintStream(out), new PrintStream(err)));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Policy loaded: false"),
                "a never-evaluated policy must not read as loaded in the markdown report either");
    }

    @Test
    void validatesPolicyAndDiffsOntologyDocumentsWithoutProtege() throws Exception {
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, """
                version: 1
                project_id: cli-test
                root_ontology: https://example.org/root
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                validation:
                  required_stages: [profile, governance, structural]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(policy, "cli-test");
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
    void validatePolicyJsonDocumentEndsWithATrailingNewline() throws Exception {
        // The shared mapper disables AUTO_CLOSE_TARGET, so JSON.writeValue must not close the output
        // stream: the out.println() that terminates the JSON document with a newline runs after it
        // and must actually land in the stream.
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, """
                version: 1
                project_id: cli-newline
                root_ontology: https://example.org/root
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                validation:
                  required_stages: [profile, governance, structural]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(policy, "cli-newline");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"validate-policy", "--project", policy.toString()},
                new PrintStream(out), new PrintStream(new ByteArrayOutputStream())));
        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.endsWith("}\n"),
                "the JSON document must terminate with its closing brace and a trailing newline");
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
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                validation:
                  required_stages: [reasoner]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(unnamed, "cli-reasoner-unnamed");
        ByteArrayOutputStream unnamedOut = new ByteArrayOutputStream();
        // PLAN §10.2: a loaded-and-evaluated but invalid policy is a gate-style failure (exit 1), not a
        // configuration error (exit 2). This is the documented change from the 0.6.1 mapping.
        assertEquals(1, Main.run(new String[] {"validate-policy", "--project", unnamed.toString()},
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
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                reasoning:
                  reasoner: ELK
                validation:
                  required_stages: [reasoner]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(named, "cli-reasoner-named");
        ByteArrayOutputStream namedOut = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"validate-policy", "--project", named.toString()},
                new PrintStream(namedOut), new PrintStream(new ByteArrayOutputStream())));
        String namedJson = namedOut.toString();
        assertTrue(namedJson.contains("\"valid\" : true"));
        assertFalse(namedJson.contains("reasoner_unavailable"),
                "no availability check may run without an installed-reasoner registry");
    }

    @Test
    void loadedButInvalidPolicyExitsOneAndUnloadablePolicyExitsTwo() throws Exception {
        // A schema-valid policy whose semantic checks fail is loaded AND evaluated (a canonical digest
        // was computed): that is a gate-style failure, exit 1.
        Path invalid = temp.resolve("invalid.yaml");
        Files.writeString(invalid, """
                version: 1
                project_id: cli-invalid
                root_ontology: https://example.org/root
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                validation:
                  required_stages: [reasoner]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(invalid, "cli-invalid");
        ByteArrayOutputStream invalidOut = new ByteArrayOutputStream();
        assertEquals(1, Main.run(new String[] {"validate-policy", "--project", invalid.toString()},
                new PrintStream(invalidOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(invalidOut.toString().contains("\"valid\" : false"));
        assertTrue(invalidOut.toString().contains("\"policy_loaded\" : true"),
                "a fully evaluated policy (digest present) still reports policy_loaded true");

        // A policy that never parses cleanly has no digest and is a configuration error, exit 2 —
        // and policy_loaded honestly reports false, since the policy never reached evaluation.
        Path missing = temp.resolve("does-not-exist.yaml");
        ByteArrayOutputStream missingOut = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"validate-policy", "--project", missing.toString()},
                new PrintStream(missingOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(missingOut.toString().contains("\"policy_loaded\" : false"), missingOut::toString);

        Path unparseable = temp.resolve("broken.yaml");
        Files.writeString(unparseable, "this: is: not: valid: yaml: {[}\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream unparseableOut = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"validate-policy", "--project", unparseable.toString()},
                new PrintStream(unparseableOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(unparseableOut.toString().contains("\"policy_loaded\" : false"),
                unparseableOut::toString);
    }

    @Test
    void validateCanForbidCandidatePolicyExternalPaths() throws Exception {
        Path outside = Files.createTempFile("cli-external-", ".rq");
        try {
            Path policy = temp.resolve("external.yaml");
            Files.writeString(policy, """
                    version: 1
                    project_id: cli-external
                    root_ontology: https://example.org/root
                    filesystem:
                      allow_external_paths: true
                    interoperability:
                      profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                      root_artifact: ontology.ttl
                      metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                      canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                    validation:
                      required_stages: [invariants]
                      invariants:
                        paths: ['%s']
                    """.formatted(outside.toString().replace("'", "''")), StandardCharsets.UTF_8);
            materializeRoCrate(policy, "cli-external");
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            assertEquals(1, Main.run(new String[] {"validate", "--project", policy.toString(),
                    "--no-external"}, new PrintStream(out),
                    new PrintStream(new ByteArrayOutputStream())));

            String json = out.toString(StandardCharsets.UTF_8);
            assertTrue(json.contains("external_paths_forbidden"), json);
            assertTrue(json.contains("\"no_external_paths\" : true"), json);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void diffCheckGatesOnIdentity() throws Exception {
        Path a = temp.resolve("a.ofn");
        Path b = temp.resolve("b.ofn");
        Files.writeString(a, "Ontology(<https://example.org/o> Declaration(Class(<https://example.org/A>)))");
        Files.writeString(b, "Ontology(<https://example.org/o> Declaration(Class(<https://example.org/B>)))");

        // Without --check, a differing diff still exits 0 (released behavior preserved).
        assertEquals(0, Main.run(new String[] {"diff", "--left", a.toString(), "--right", b.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));

        // With --check, differing ontologies exit 1.
        assertEquals(1, Main.run(
                new String[] {"diff", "--left", a.toString(), "--right", b.toString(), "--check"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));

        // With --check, identical ontologies exit 0.
        Path aCopy = temp.resolve("a-copy.ofn");
        Files.writeString(aCopy, Files.readString(a));
        assertEquals(0, Main.run(
                new String[] {"diff", "--left", a.toString(), "--right", aCopy.toString(), "--check"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
    }

    @Test
    void manifestDiffResolvesPrimaryArtifactAndVerifiesSha256() throws Exception {
        Path ontology = temp.resolve("ontology.ttl");
        Files.writeString(ontology, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n"
                + "<https://example.org/A> a owl:Class .\n", StandardCharsets.UTF_8);
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, """
                {
                  "manifest_version": 1,
                  "project_id": "cli-manifest",
                  "version_iri": "https://example.org/o/1.0.0",
                  "artifacts": [
                    {"path": "ontology.ttl", "sha256": "%s", "bytes": %d}
                  ]
                }
                """.formatted(ArtifactStore.sha256(ontology), Files.size(ontology)),
                StandardCharsets.UTF_8);
        Path right = temp.resolve("right.ttl");
        Files.writeString(right, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n"
                + "<https://example.org/B> a owl:Class .\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0,
                Main.run(new String[] {"diff", "--left", manifest.toString(), "--right", right.toString()},
                        new PrintStream(out), new PrintStream(err)),
                () -> "manifest diff must resolve + verify + diff: " + err);
        String json = out.toString();
        assertTrue(json.contains("\"left_manifest\""), json);
        assertTrue(json.contains("\"sha256_verified\" : true"), json);
        assertTrue(json.contains("\"artifact_path\" : \"ontology.ttl\""), json);
        assertTrue(json.contains("https://example.org/o/1.0.0"), "the manifest version IRI is reported");
        assertTrue(json.contains("\"entities\""), "the resolved artifact was actually diffed");
    }

    @Test
    void manifestDiffRejectsSha256MismatchWithoutDiffing() throws Exception {
        Path ontology = temp.resolve("ontology.ttl");
        Files.writeString(ontology, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n", StandardCharsets.UTF_8);
        Path manifest = temp.resolve("manifest.json");
        // The recorded byte length is CORRECT so this pins the sha256 check specifically; the
        // bounded read verifies the length first.
        Files.writeString(manifest, """
                {
                  "manifest_version": 1,
                  "version_iri": "https://example.org/o/1.0.0",
                  "artifacts": [
                    {"path": "ontology.ttl", "sha256": "sha256:%s", "bytes": %d}
                  ]
                }
                """.formatted("0".repeat(64), Files.size(ontology)), StandardCharsets.UTF_8);
        Path right = temp.resolve("right.ttl");
        Files.writeString(right, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(3,
                Main.run(new String[] {"diff", "--left", manifest.toString(), "--right", right.toString()},
                        new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString().contains("sha256 mismatch"), err.toString());
        assertFalse(out.toString().contains("\"entities\""), "a tampered artifact must not be diffed");
    }

    @Test
    void manifestDiffRejectsUnsupportedOrNonIntegerManifestVersion() throws Exception {
        Path ontology = temp.resolve("ontology.ttl");
        Files.writeString(ontology, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n", StandardCharsets.UTF_8);
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, """
                {"manifest_version": 1.5, "artifacts": [
                  {"path":"ontology.ttl", "sha256":"%s", "bytes":%d}
                ]}
                """.formatted(ArtifactStore.sha256(ontology), Files.size(ontology)));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(3, Main.run(new String[] {"diff", "--left", manifest.toString(),
                "--right", ontology.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err)));
        assertTrue(err.toString().contains("manifest_version"), err::toString);
    }

    @Test
    void manifestDiffRejectsByteLengthMismatchEvenWhenShaMatches() throws Exception {
        Path ontology = temp.resolve("ontology.ttl");
        Files.writeString(ontology, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n", StandardCharsets.UTF_8);
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, """
                {"manifest_version": 1, "artifacts": [
                  {"path":"ontology.ttl", "sha256":"%s", "bytes":1}
                ]}
                """.formatted(ArtifactStore.sha256(ontology)));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(3, Main.run(new String[] {"diff", "--left", manifest.toString(),
                "--right", ontology.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err)));
        assertTrue(err.toString().contains("byte-length mismatch"), err::toString);
    }

    @Test
    void manifestDiffRejectsArtifactPathEscapingTheManifestDirectory() throws Exception {
        Path subdir = temp.resolve("release");
        Files.createDirectories(subdir);
        // A file outside the manifest directory that a `..` path would reach.
        Files.writeString(temp.resolve("escape.ttl"), "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n", StandardCharsets.UTF_8);
        Path manifest = subdir.resolve("manifest.json");
        Files.writeString(manifest, """
                {
                  "manifest_version": 1,
                  "artifacts": [
                    {"path": "../escape.ttl", "sha256": "sha256:%s", "bytes": 1}
                  ]
                }
                """.formatted("0".repeat(64)), StandardCharsets.UTF_8);
        Path right = temp.resolve("right.ttl");
        Files.writeString(right, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(3,
                Main.run(new String[] {"diff", "--left", manifest.toString(), "--right", right.toString()},
                        new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString().contains("escapes the manifest directory"), err.toString());
        assertFalse(out.toString().contains("\"entities\""), "an escaping artifact must not be diffed");
    }

    @Test
    void manifestDiffRejectsAMissingArtifactWithoutDiffing() throws Exception {
        // The artifact path is well-formed and contained, but the file is absent → exit 3, no diff.
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, """
                {
                  "manifest_version": 1,
                  "artifacts": [
                    {"path": "ontology.ttl", "sha256": "sha256:%s", "bytes": 1}
                  ]
                }
                """.formatted("0".repeat(64)), StandardCharsets.UTF_8);
        Path right = temp.resolve("right.ttl");
        Files.writeString(right, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/o> a owl:Ontology .\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(3,
                Main.run(new String[] {"diff", "--left", manifest.toString(), "--right", right.toString()},
                        new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString().contains("could not be resolved beside the manifest"), err.toString());
        assertFalse(out.toString().contains("\"entities\""), "a missing artifact must not be diffed");
    }

    @Test
    void anOversizedJsonOperandIsNotBufferedAsAManifest() throws Exception {
        // A multi-megabyte .json operand must not be whole-file-buffered by the manifest probe; it
        // falls through to the streaming document loader (which reports its own bounded parse error).
        Path big = temp.resolve("big.json");
        byte[] filler = new byte[(int) Main.MAX_MANIFEST_BYTES + 4096];
        java.util.Arrays.fill(filler, (byte) ' ');
        Files.write(big, filler);
        Path right = temp.resolve("right.ofn");
        Files.writeString(right, "Ontology(<https://example.org/o>)");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // The oversized json is NOT whole-file-buffered by the manifest probe (no OOM) and is not
        // treated as a manifest — it falls through to the streaming OWLAPI loader. The parse outcome
        // is OWLAPI's business; what this pins is that the run COMPLETES (returns a code, never an
        // uncaught OutOfMemoryError) and never reaches the sha256/containment manifest path.
        int code = Main.run(new String[] {"diff", "--left", big.toString(), "--right", right.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertTrue(code == 0 || code == 3, "diff completed without crashing: exit " + code);
        assertFalse(err.toString().contains("sha256"), "an oversized json is not treated as a manifest");
    }

    @Test
    void validateExitsOneForAnInvalidPolicyAndTwoForAnUnloadableOne() throws Exception {
        // The `validate` command has its own exitFor return site (json AND markdown) — pin its
        // non-zero verdicts directly, not only transitively through validate-policy.
        Path invalid = temp.resolve("invalid.yaml");
        Files.writeString(invalid, """
                version: 1
                project_id: cli-validate-invalid
                root_ontology: https://example.org/root
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                validation:
                  required_stages: [reasoner]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(invalid, "cli-validate-invalid");

        for (String format : new String[] {"json", "markdown"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(1, Main.run(
                    new String[] {"validate", "--project", invalid.toString(), "--format", format},
                    new PrintStream(out), new PrintStream(new ByteArrayOutputStream())),
                    () -> "a loaded-but-invalid policy is a gate failure (exit 1) in " + format);
            if ("json".equals(format)) {
                assertTrue(out.toString().contains("\"policy_loaded\" : true"),
                        "a fully evaluated policy (digest present) still reports policy_loaded true");
            }
        }

        // Unloadable policies (missing or unparseable) never reach evaluation: exit 2 with an honest
        // "policy_loaded" : false, exactly like the validate-policy twin.
        Path missing = temp.resolve("nope.yaml");
        ByteArrayOutputStream missingOut = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"validate", "--project", missing.toString()},
                new PrintStream(missingOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(missingOut.toString().contains("\"policy_loaded\" : false"), missingOut::toString);

        Path unparseable = temp.resolve("broken-validate.yaml");
        Files.writeString(unparseable, "this: is: not: valid: yaml: {[}\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream unparseableOut = new ByteArrayOutputStream();
        assertEquals(2, Main.run(new String[] {"validate", "--project", unparseable.toString()},
                new PrintStream(unparseableOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(unparseableOut.toString().contains("\"policy_loaded\" : false"),
                unparseableOut::toString);
    }

    @Test
    void validateReportsPolicyValidationScopeAndDeferredQcNote() throws Exception {
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, """
                version: 1
                project_id: cli-validate
                root_ontology: https://example.org/root
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                validation:
                  required_stages: [profile, governance, structural]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(policy, "cli-validate");

        ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"validate", "--project", policy.toString()},
                new PrintStream(jsonOut), new PrintStream(new ByteArrayOutputStream())));
        String json = jsonOut.toString();
        assertTrue(json.contains("\"scope\" : \"policy_validation\""), json);
        assertTrue(json.contains("Full project QC"), "the QC-deferred note is present");
        assertTrue(json.contains("\"valid\" : true"), json);

        ByteArrayOutputStream mdOut = new ByteArrayOutputStream();
        assertEquals(0, Main.run(
                new String[] {"validate", "--project", policy.toString(), "--format", "markdown"},
                new PrintStream(mdOut), new PrintStream(new ByteArrayOutputStream())));
        String md = mdOut.toString();
        assertTrue(md.contains("# Policy validation: valid"), md);
        assertTrue(md.contains("Full project QC"), "the QC-deferred note is present in markdown");
    }

    @Test
    void validateRejectsJunitAndSarifFormatsAsConfigurationErrors() throws Exception {
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, """
                version: 1
                project_id: cli-fmt
                root_ontology: https://example.org/root
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                validation:
                  required_stages: [profile, governance, structural]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(policy, "cli-fmt");
        for (String format : new String[] {"junit", "sarif", "bogus"}) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(2, Main.run(
                    new String[] {"validate", "--project", policy.toString(), "--format", format},
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(err)),
                    () -> "format " + format + " must be a configuration error");
            assertTrue(err.toString().contains("configuration error:"), err.toString());
        }
    }

    @Test
    void noNetworkIsAcceptedAsRedundantOnValidateAndDiff() throws Exception {
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, """
                version: 1
                project_id: cli-nonet
                root_ontology: https://example.org/root
                interoperability:
                  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/
                  root_artifact: ontology.ttl
                  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.3}
                  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}
                validation:
                  required_stages: [profile, governance, structural]
                """, StandardCharsets.UTF_8);
        materializeRoCrate(policy, "cli-nonet");
        ByteArrayOutputStream validateOut = new ByteArrayOutputStream();
        assertEquals(0, Main.run(
                new String[] {"validate", "--project", policy.toString(), "--no-network"},
                new PrintStream(validateOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(validateOut.toString().contains("\"no_network\""), validateOut.toString());

        Path left = temp.resolve("left.ofn");
        Path right = temp.resolve("right.ofn");
        Files.writeString(left, "Ontology(<https://example.org/o> Declaration(Class(<https://example.org/A>)))");
        Files.writeString(right, "Ontology(<https://example.org/o> Declaration(Class(<https://example.org/B>)))");
        ByteArrayOutputStream diffOut = new ByteArrayOutputStream();
        assertEquals(0, Main.run(
                new String[] {"diff", "--left", left.toString(), "--right", right.toString(), "--no-network"},
                new PrintStream(diffOut), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(diffOut.toString().contains("\"no_network\""), diffOut.toString());
    }

    @Test
    void deferredCommandsPrintANotAvailableMessageAndExitTwo() {
        for (String command : new String[] {"release", "imports", "serve"}) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(2, Main.run(new String[] {command, "--transport", "stdio"},
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(err)));
            assertTrue(err.toString().contains("not yet available in the headless CLI"),
                    () -> command + " message: " + err);
        }
    }

    private static void materializeRoCrate(Path policy, String projectId) throws Exception {
        Path root = policy.getParent();
        Path ontology = root.resolve("ontology.ttl");
        if (!Files.exists(ontology)) {
            Files.writeString(ontology,
                    "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<> a owl:Ontology .\n");
        }
        Files.writeString(root.resolve("ro-crate-metadata.json"), """
                {
                  "@context": "https://w3id.org/ro/crate/1.3/context",
                  "@graph": [
                    {"@id":"ro-crate-metadata.json","@type":"CreativeWork",
                     "about":{"@id":"./"},"conformsTo":{"@id":"https://w3id.org/ro/crate/1.3"}},
                    {"@id":"./","@type":"Dataset","name":"CLI test project",
                     "description":"Headless CLI validate-policy test crate.",
                     "datePublished":"2026-07-15",
                     "license":"https://www.apache.org/licenses/LICENSE-2.0",
                     "identifier":"%s",
                     "conformsTo":{"@id":"https://hakjuoh.github.io/protege-mcp/profiles/project-v1/"},
                     "mainEntity":{"@id":"ontology.ttl"},"hasPart":{"@id":"ontology.ttl"}},
                    {"@id":"https://hakjuoh.github.io/protege-mcp/profiles/project-v1/",
                     "@type":["CreativeWork","Profile"],"name":"Protégé MCP project profile"},
                    {"@id":"ontology.ttl","@type":"File","encodingFormat":"text/turtle",
                     "about":{"@id":"https://example.org/root"}},
                    {"@id":"https://example.org/root","@type":"Dataset",
                     "conformsTo":{"@id":"https://www.w3.org/TR/owl2-overview/"}}
                  ]
                }
                """.formatted(projectId), StandardCharsets.UTF_8);
    }
}
