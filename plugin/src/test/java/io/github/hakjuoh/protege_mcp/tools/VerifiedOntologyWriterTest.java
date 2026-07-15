package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

class VerifiedOntologyWriterTest {

    @TempDir
    Path temp;

    @Test
    void prepareDoesNotReplaceTargetAndInstallReturnsVerifiedArtifactMetadata() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/save"));
        var df = manager.getOWLDataFactory();
        var a = df.getOWLClass(IRI.create("http://example.org/save#A"));
        var b = df.getOWLClass(IRI.create("http://example.org/save#B"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(a));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(b));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(a, b, Set.of(
                df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("curated")))));
        manager.applyChange(new AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("Ontology", "en"))));
        Path target = temp.resolve("ontology.ttl");
        Files.writeString(target, "sentinel");

        try (var prepared = VerifiedOntologyWriter.prepare(
                VerifiedOntologyWriter.snapshot(ontology, new TurtleDocumentFormat()), target)) {
            assertEquals("sentinel", Files.readString(target),
                    "temporary verification must not touch the existing target");
            assertTrue(prepared.verification.identical());
            assertTrue(prepared.bytes > 0);
            assertTrue(prepared.sha256.matches("sha256:[0-9a-f]{64}"));
            var installed = prepared.install(false, true);
            assertFalse(installed.atomic());
            assertEquals("sentinel", Files.readString(installed.backupPath()));
        }

        assertTrue(Files.readString(target).contains("http://example.org/save"));
        var reloaded = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(target.toFile());
        assertEquals(ontology.getAxioms(), reloaded.getAxioms());
        assertEquals(ontology.getAnnotations(), reloaded.getAnnotations());
    }

    @Test
    void verificationDoesNotDereferenceUnavailableImportsAndCleansTemporaryFile() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/missing-import"));
        var firstMissing = manager.getOWLDataFactory().getOWLImportsDeclaration(
                IRI.create(temp.resolve("does-not-exist.ttl").toUri()));
        var secondMissing = manager.getOWLDataFactory().getOWLImportsDeclaration(
                IRI.create("https://127.0.0.1:1/must-not-be-contacted"));
        manager.applyChange(new AddImport(ontology, firstMissing));
        manager.applyChange(new AddImport(ontology, secondMissing));
        Path target = temp.resolve("locked.ttl");
        Files.writeString(target, "previous-release");

        try (var prepared = VerifiedOntologyWriter.prepare(
                VerifiedOntologyWriter.snapshot(ontology, new TurtleDocumentFormat()), target)) {
            assertTrue(prepared.verification.identical(),
                    "all direct import declarations are compared without loading their documents");
            assertEquals("previous-release", Files.readString(target),
                    "prepare still must not replace the target before install");
        }

        assertEquals("previous-release", Files.readString(target));
        try (var files = Files.list(temp)) {
            assertEquals(Set.of("locked.ttl"),
                    files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void rdfXmlSerializerAddedHeaderPropertyDeclarationsDoNotFalseFail() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("urn:example:fibo-header"));
        var df = manager.getOWLDataFactory();
        var copyright = df.getOWLAnnotationProperty(
                IRI.create("https://www.omg.org/spec/Commons/AnnotationVocabulary/copyright"));
        var modified = df.getOWLAnnotationProperty(IRI.create("http://purl.org/dc/terms/modified"));
        manager.applyChange(new AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(copyright, df.getOWLLiteral("Copyright EDM Council"))));
        manager.applyChange(new AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(modified, df.getOWLLiteral("2026-07-15"))));
        assertTrue(ontology.getAxioms().isEmpty(),
                "the FIBO-shaped fixture deliberately has header annotations but no declarations");

        Path target = temp.resolve("fibo-header.rdf");
        try (var prepared = VerifiedOntologyWriter.prepare(
                VerifiedOntologyWriter.snapshot(ontology, new RDFXMLDocumentFormat()), target)) {
            assertTrue(prepared.verification.identical(),
                    "serializer-added unannotated declarations are normalized on both sides");
            assertTrue(prepared.verification.axiomsIncludingAnnotations());
        }
    }

    @Test
    void installPreservesTheExistingTargetsPosixPermissions() throws Exception {
        Assumptions.assumeTrue(supportsPosix(temp), "POSIX filesystem required");
        Path target = temp.resolve("shared.ttl");
        Files.writeString(target, "previous-release");
        Set<PosixFilePermission> groupReadable = PosixFilePermissions.fromString("rw-rw-r--");
        Files.setPosixFilePermissions(target, groupReadable);

        try (var prepared = VerifiedOntologyWriter.prepare(
                VerifiedOntologyWriter.snapshot(classOntology(), new TurtleDocumentFormat()), target)) {
            prepared.install(true, false);
        }

        assertEquals(groupReadable, Files.getPosixFilePermissions(target),
                "a verified save must not silently reset the target to the temp file's owner-only mode");
        assertTrue(Files.readString(target).contains("http://example.org/save"),
                "the new bytes were installed");
    }

    @Test
    void installIntoAFreshPathStillWorksWhenNoTargetExists() throws Exception {
        Path target = temp.resolve("brand-new.ttl");

        try (var prepared = VerifiedOntologyWriter.prepare(
                VerifiedOntologyWriter.snapshot(classOntology(), new TurtleDocumentFormat()), target)) {
            var installed = prepared.install(false, false);
            assertNull(installed.backupPath(), "nothing existed to back up");
        }

        assertTrue(Files.readString(target).contains("http://example.org/save"));
    }

    @Test
    void installWritesThroughASymlinkedTargetKeepingTheLink() throws Exception {
        Assumptions.assumeTrue(supportsPosix(temp), "POSIX filesystem required");
        Assumptions.assumeTrue(canSymlink(temp), "symlink creation required");
        Path real = temp.resolve("real.ttl");
        Files.writeString(real, "previous-release");
        Set<PosixFilePermission> groupReadable = PosixFilePermissions.fromString("rw-rw-r--");
        Files.setPosixFilePermissions(real, groupReadable);
        Path link = temp.resolve("link.ttl");
        Files.createSymbolicLink(link, real.getFileName());

        try (var prepared = VerifiedOntologyWriter.prepare(
                VerifiedOntologyWriter.snapshot(classOntology(), new TurtleDocumentFormat()), link)) {
            var installed = prepared.install(true, false);
            assertTrue(installed.atomic(), "the temp sibling shares the real target's filesystem");
        }

        assertTrue(Files.isSymbolicLink(link), "the symlink itself must survive a verified save");
        assertEquals(real.getFileName(), Files.readSymbolicLink(link), "link destination unchanged");
        assertTrue(Files.readString(real).contains("http://example.org/save"),
                "the linked real destination received the new bytes");
        assertEquals(groupReadable, Files.getPosixFilePermissions(real),
                "permissions are read from and preserved on the real target");
        try (var files = Files.list(temp)) {
            assertEquals(Set.of("real.ttl", "link.ttl"),
                    files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
                    "no temporary artifact leaks beside either path");
        }
    }

    @Test
    void installThroughADanglingSymlinkCreatesTheLinkedDestination() throws Exception {
        Assumptions.assumeTrue(canSymlink(temp), "symlink creation required");
        Path destination = temp.resolve("not-yet-written.ttl");
        Path link = temp.resolve("dangling.ttl");
        Files.createSymbolicLink(link, destination.getFileName());

        try (var prepared = VerifiedOntologyWriter.prepare(
                VerifiedOntologyWriter.snapshot(classOntology(), new TurtleDocumentFormat()), link)) {
            prepared.install(false, false);
        }

        assertTrue(Files.isSymbolicLink(link), "the dangling link must survive, not be replaced");
        assertEquals(destination.getFileName(), Files.readSymbolicLink(link),
                "link destination unchanged");
        assertTrue(Files.readString(destination).contains("http://example.org/save"),
                "the linked destination was created with the new bytes");
        assertTrue(Files.readString(link).contains("http://example.org/save"),
                "reading through the link now sees the installed artifact");
    }

    @Test
    void danglingSymlinkBehindAMissingDirectoryFailsClosed() throws Exception {
        Assumptions.assumeTrue(canSymlink(temp), "symlink creation required");
        Path link = temp.resolve("into-missing-dir.ttl");
        Files.createSymbolicLink(link, Path.of("newdir", "actual.ttl"));
        var snapshot = VerifiedOntologyWriter.snapshot(classOntology(), new TurtleDocumentFormat());

        ToolArgException e = assertThrows(ToolArgException.class,
                () -> VerifiedOntologyWriter.prepare(snapshot, link));

        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        assertTrue(e.getMessage().contains("newdir"), "the error names the missing directory: "
                + e.getMessage());
        assertTrue(Files.isSymbolicLink(link), "the dangling link is untouched");
        assertFalse(Files.exists(temp.resolve("newdir")),
                "plain save cannot create intermediate directories, so neither may verified save");
        try (var files = Files.list(temp)) {
            assertEquals(Set.of("into-missing-dir.ttl"),
                    files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
                    "nothing was materialized for the refused target");
        }
    }

    @Test
    void danglingSymlinkThroughANonexistentDotDotComponentFailsClosed() throws Exception {
        Assumptions.assumeTrue(canSymlink(temp), "symlink creation required");
        // POSIX resolution of missing/../actual.ttl must traverse the nonexistent missing/ and fail;
        // a lexical collapse to actual.ttl would make verified save succeed where plain save cannot.
        Path link = temp.resolve("through-missing.ttl");
        Files.createSymbolicLink(link, Path.of("missing", "..", "actual.ttl"));
        var snapshot = VerifiedOntologyWriter.snapshot(classOntology(), new TurtleDocumentFormat());

        ToolArgException e = assertThrows(ToolArgException.class,
                () -> VerifiedOntologyWriter.prepare(snapshot, link));

        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        assertTrue(Files.isSymbolicLink(link), "the dangling link is untouched");
        assertFalse(Files.exists(temp.resolve("actual.ttl")),
                "the lexically collapsed destination must not be written");
        try (var files = Files.list(temp)) {
            assertEquals(Set.of("through-missing.ttl"),
                    files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
                    "nothing was materialized for the refused target");
        }
    }

    @Test
    void symlinkCycleTargetFailsClosedWithoutTouchingTheLinks() throws Exception {
        Assumptions.assumeTrue(canSymlink(temp), "symlink creation required");
        Path a = temp.resolve("a.ttl");
        Path b = temp.resolve("b.ttl");
        Files.createSymbolicLink(a, b.getFileName());
        Files.createSymbolicLink(b, a.getFileName());
        var snapshot = VerifiedOntologyWriter.snapshot(classOntology(), new TurtleDocumentFormat());

        ToolArgException e = assertThrows(ToolArgException.class,
                () -> VerifiedOntologyWriter.prepare(snapshot, a));

        assertTrue(e.getMessage().contains("symbolic-link"), e.getMessage());
        assertTrue(Files.isSymbolicLink(a), "the cyclic link a is untouched");
        assertTrue(Files.isSymbolicLink(b), "the cyclic link b is untouched");
        try (var files = Files.list(temp)) {
            assertEquals(Set.of("a.ttl", "b.ttl"),
                    files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
                    "no temporary artifact was created for the refused target");
        }
    }

    @Test
    void anonymousOntologyRoundTripVerifies() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology();   // headerless: OWLAPI assigns a session-local id
        var df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/anon#A"))));
        Path target = temp.resolve("anonymous.ttl");

        try (var prepared = VerifiedOntologyWriter.prepare(
                VerifiedOntologyWriter.snapshot(ontology, new TurtleDocumentFormat()), target)) {
            assertTrue(prepared.verification.ontologyId(),
                    "two anonymous ids are the same headerless ontology, not an id mismatch");
            assertTrue(prepared.verification.identical());
            prepared.install(false, false);
        }

        assertTrue(Files.readString(target).contains("http://example.org/anon#A"));
    }

    /** A small named ontology (one declared class in {@code http://example.org/save#}). */
    private static OWLOntology classOntology() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/save"));
        var df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/save#A"))));
        return ontology;
    }

    private static boolean supportsPosix(Path p) {
        try {
            Files.getPosixFilePermissions(p);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    /** Whether this filesystem/user can create symlinks (e.g. Windows needs a privilege). */
    private static boolean canSymlink(Path dir) {
        Path probe = dir.resolve("probe-link");
        try {
            Files.createSymbolicLink(probe, Path.of("probe-target"));
            Files.delete(probe);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }
}
