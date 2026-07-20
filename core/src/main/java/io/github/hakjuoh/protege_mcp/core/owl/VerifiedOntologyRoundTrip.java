package io.github.hakjuoh.protege_mcp.core.owl;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/** Protégé-free serialization, isolated no-network reload, and normalized equality verification. */
public final class VerifiedOntologyRoundTrip {

    public static final String BYTE_FOR_BYTE = "byte_for_byte";
    public static final String AXIOM_IDENTICAL = "axiom_identical";
    public static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;

    private VerifiedOntologyRoundTrip() {
    }

    public record Verification(boolean ontologyId, boolean imports, boolean ontologyAnnotations,
            boolean axiomsIncludingAnnotations, String roundTripClass,
            Map<String, Object> mismatches) {
        public Verification {
            mismatches = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mismatches));
        }

        public boolean identical() {
            return ontologyId && imports && ontologyAnnotations && axiomsIncludingAnnotations;
        }
    }

    public record Result(byte[] content, String sha256, long bytes,
            Verification verification) {
        public Result {
            content = content.clone();
            if (!verification.identical()) {
                throw new IllegalArgumentException("round-trip result must be identical");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public String roundTripClass() {
            return verification.roundTripClass();
        }
    }

    /** Serialize one asserted ontology document and prove its isolated reload is normalized-identical. */
    public static Result serialize(OWLOntology source, OWLDocumentFormat format) throws IOException {
        if (source == null || format == null) {
            throw new IllegalArgumentException("source and format must not be null");
        }
        if (OwlDocumentSemantics.hasAnonymousIndividuals(source)) {
            throw new IllegalArgumentException("verified serialization does not support anonymous "
                    + "individuals because their identifiers are session-local");
        }
        Path directory = Files.createTempDirectory("protege-mcp-round-trip-").toRealPath();
        OWLOntologyManager snapshotManager = null;
        try {
            snapshotManager = createManager();
            OWLOntology snapshot = copy(source, format, snapshotManager);
            Path artifact = directory.resolve("ontology.bin");
            try {
                snapshotManager.saveOntology(snapshot, format, IRI.create(artifact.toUri()));
            } catch (OWLOntologyStorageException | RuntimeException error) {
                throw new IOException("could not serialize ontology: "
                        + privatePathMessage(message(error), directory, artifact), error);
            }
            Verification verification = verify(snapshot, artifact, format, directory);
            if (!verification.identical()) {
                throw new IOException("verified serialization round trip was not exact: "
                        + verification.mismatches());
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(artifact)) {
                bytes = input.readNBytes(Math.toIntExact(MAX_ARTIFACT_BYTES + 1));
            }
            if (bytes.length > MAX_ARTIFACT_BYTES) {
                throw new IOException("serialized ontology exceeds " + MAX_ARTIFACT_BYTES + " bytes");
            }
            return new Result(bytes, ArtifactStore.sha256(bytes), bytes.length, verification);
        } finally {
            if (snapshotManager != null) removeAll(snapshotManager);
            deleteTree(directory);
        }
    }

    private static OWLOntology copy(OWLOntology source, OWLDocumentFormat format,
            OWLOntologyManager manager) throws IOException {
        try {
            OWLOntology copy = manager.createOntology(source.getOntologyID());
            manager.addAxioms(copy, source.getAxioms());
            source.getAnnotations().forEach(annotation ->
                    manager.applyChange(new AddOntologyAnnotation(copy, annotation)));
            source.getImportsDeclarations().forEach(declaration ->
                    manager.applyChange(new AddImport(copy, declaration)));
            if (format.isPrefixOWLOntologyFormat()) {
                OWLDocumentFormat current = source.getOWLOntologyManager().getOntologyFormat(source);
                if (current != null && current.isPrefixOWLOntologyFormat()) {
                    format.asPrefixOWLOntologyFormat().copyPrefixesFrom(
                            current.asPrefixOWLOntologyFormat());
                }
            }
            manager.setOntologyFormat(copy, format);
            return copy;
        } catch (OWLOntologyCreationException | RuntimeException error) {
            throw new IOException("could not capture ontology for verified serialization: "
                    + message(error), error);
        }
    }

    private static Verification verify(OWLOntology expected, Path artifact,
            OWLDocumentFormat format, Path directory) throws IOException {
        OWLOntologyManager manager = createManager();
        Path placeholder = directory.resolve("imports.ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
            IRI placeholderIri = IRI.create(placeholder.toUri());
            manager.getIRIMappers().add(ignored -> placeholderIri);
            OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.THROW_EXCEPTION)
                    .setFollowRedirects(false);
            OWLOntology actual = manager.loadOntologyFromOntologyDocument(
                    new FileDocumentSource(artifact.toFile()), config);
            boolean id = ontologyIdMatches(expected.getOntologyID(), actual.getOntologyID());
            boolean imports = expected.getImportsDeclarations().equals(actual.getImportsDeclarations());
            boolean annotations = OwlDocumentSemantics.normalizedAnnotations(expected)
                    .equals(OwlDocumentSemantics.normalizedAnnotations(actual));
            boolean axioms = OwlDocumentSemantics.normalizedAxioms(expected)
                    .equals(OwlDocumentSemantics.normalizedAxioms(actual));
            boolean identical = id && imports && annotations && axioms;
            String roundTripClass = identical && reserializeMatches(actual, format, artifact)
                    ? BYTE_FOR_BYTE : AXIOM_IDENTICAL;
            Map<String, Object> mismatches = new LinkedHashMap<>();
            if (!id) mismatches.put("ontology_id", false);
            if (!imports) mismatches.put("imports", false);
            if (!annotations) mismatches.put("ontology_annotations", false);
            if (!axioms) mismatches.put("axioms_including_annotations", false);
            return new Verification(id, imports, annotations, axioms,
                    roundTripClass, mismatches);
        } catch (OWLOntologyCreationException error) {
            throw new IOException("strict reload of serialized ontology failed: "
                    + privatePathMessage(OwlParsingErrors.conciseMessage(error),
                            directory, artifact), error);
        } catch (RuntimeException error) {
            throw new IOException("strict reload of serialized ontology failed: "
                    + privatePathMessage(message(error), directory, artifact), error);
        } finally {
            removeAll(manager);
        }
    }

    private static boolean ontologyIdMatches(OWLOntologyID expected, OWLOntologyID actual) {
        if (expected.isAnonymous() || actual.isAnonymous()) {
            return expected.isAnonymous() == actual.isAnonymous();
        }
        return expected.equals(actual);
    }

    private static boolean reserializeMatches(OWLOntology reloaded, OWLDocumentFormat format,
            Path artifact) {
        try (ComparingOutputStream output = new ComparingOutputStream(
                new BufferedInputStream(Files.newInputStream(artifact)))) {
            reloaded.getOWLOntologyManager().saveOntology(reloaded, format,
                    new StreamDocumentTarget(output));
            return output.matchesExactly();
        } catch (OWLOntologyStorageException | IOException | RuntimeException error) {
            return false;
        }
    }

    private static OWLOntologyManager createManager() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(OWLManager.class.getClassLoader());
            return OWLManager.createOWLOntologyManager();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void removeAll(OWLOntologyManager manager) {
        for (OWLOntology ontology : new ArrayList<>(manager.getOntologies())) {
            manager.removeOntology(ontology);
        }
    }

    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort for a private temporary tree.
                }
            });
        } catch (IOException ignored) {
            // Already absent or best-effort cleanup failed.
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String privatePathMessage(String message, Path directory, Path artifact) {
        return message.replace(artifact.toUri().toString(), "ontology artifact")
                .replace(artifact.toString(), "ontology artifact")
                .replace(directory.toUri().toString(), "private temporary directory/")
                .replace(directory.toString(), "private temporary directory");
    }

    private static final class ComparingOutputStream extends OutputStream {
        private final InputStream expected;
        private final byte[] buffer = new byte[8192];
        private boolean mismatch;

        ComparingOutputStream(InputStream expected) {
            this.expected = expected;
        }

        @Override
        public void write(int value) throws IOException {
            if (!mismatch && expected.read() != (value & 0xff)) mismatch = true;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (mismatch) return;
            int compared = 0;
            while (compared < length) {
                int wanted = Math.min(buffer.length, length - compared);
                int read = expected.readNBytes(buffer, 0, wanted);
                if (read != wanted) {
                    mismatch = true;
                    return;
                }
                for (int index = 0; index < wanted; index++) {
                    if (bytes[offset + compared + index] != buffer[index]) {
                        mismatch = true;
                        return;
                    }
                }
                compared += wanted;
            }
        }

        boolean matchesExactly() throws IOException {
            return !mismatch && expected.read() == -1;
        }

        @Override
        public void close() throws IOException {
            expected.close();
        }
    }
}
