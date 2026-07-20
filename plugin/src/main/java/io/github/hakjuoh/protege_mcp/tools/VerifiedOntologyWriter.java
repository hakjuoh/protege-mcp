package io.github.hakjuoh.protege_mcp.tools;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import io.github.hakjuoh.protege_mcp.core.owl.OwlDocumentSemantics;
import io.github.hakjuoh.protege_mcp.core.owl.OwlParsingErrors;

/** Protégé-free temporary serialization, isolated reload, normalized comparison and atomic install. */
final class VerifiedOntologyWriter {

    /** Longest symlink chain {@link #resolveNewTarget} follows before failing closed. */
    private static final int MAX_LINK_HOPS = 8;

    /**
     * Round-trip reproducibility classes documented by {@code save_ontology}, from strongest to weakest:
     * <ul>
     *   <li>{@link #ROUND_TRIP_BYTE_FOR_BYTE}: re-serializing the reloaded artifact reproduces the
     *       written bytes exactly — the artifact is a canonical fixpoint for its format.</li>
     *   <li>{@link #ROUND_TRIP_AXIOM_IDENTICAL}: the normalized asserted axioms, ontology id, imports
     *       and annotations match, but the bytes are not reproducible (e.g. serializer-materialized
     *       declarations or RDF 1.1 literal typing). This is the comparison verified save has always
     *       enforced.</li>
     *   <li>{@link #ROUND_TRIP_LOGICALLY_EQUIVALENT}: RESERVED for a future reasoner-backed comparison.
     *       It requires classification and is out of scope here, so verified save NEVER emits it — the
     *       value exists only to keep the enum stable.</li>
     * </ul>
     */
    static final String ROUND_TRIP_BYTE_FOR_BYTE = "byte_for_byte";
    static final String ROUND_TRIP_AXIOM_IDENTICAL = "axiom_identical";
    static final String ROUND_TRIP_LOGICALLY_EQUIVALENT = "logically_equivalent";

    private VerifiedOntologyWriter() {
    }

    static Snapshot snapshot(OWLOntology source, OWLDocumentFormat format) {
        OWLOntologyManager manager = OwlManagers.create();
        try {
            OWLOntology copy = manager.createOntology(source.getOntologyID());
            manager.addAxioms(copy, source.getAxioms());
            source.getAnnotations().forEach(annotation ->
                    manager.applyChange(new AddOntologyAnnotation(copy, annotation)));
            source.getImportsDeclarations().forEach(declaration ->
                    manager.applyChange(new AddImport(copy, declaration)));
            manager.setOntologyFormat(copy, format);
            return new Snapshot(copy, format);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not capture ontology for verified save: " + message(e));
        }
    }

    static Prepared prepare(Snapshot snapshot, Path target) {
        if (OwlDocumentSemantics.hasAnonymousIndividuals(snapshot.ontology)) {
            throw new ToolArgException("Verified save does not support anonymous individuals: OWLAPI "
                    + "blank-node identifiers are session-local and cannot be compared exactly after reload. "
                    + "Use an unverified plain save or replace blank nodes with named individuals.");
        }
        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null) {
            throw new ToolArgException("Save target has no parent directory: " + target);
        }
        try {
            Files.createDirectories(directory);
            // A plain Protégé save writes THROUGH a symlinked target: an existing destination is
            // updated in place and a dangling link's destination is created. Renaming over the link
            // path would instead replace the link with a regular file and leave the linked
            // destination silently stale (or never written). Install over the real file — which
            // also puts the temporary sibling on the same filesystem as that file, so ATOMIC_MOVE
            // stays possible. Callers keep reporting the user-supplied path.
            Path install = Files.exists(absolute) ? absolute.toRealPath() : resolveNewTarget(absolute);
            Path installDirectory = install.getParent();
            if (installDirectory == null) {
                throw new ToolArgException("Save target has no parent directory: " + install);
            }
            // Mirror plain save for a dangling-link destination: open() through the link cannot
            // create intermediate directories, so a destination whose directory does not exist —
            // including one whose lexical path traverses a nonexistent component, which the OS
            // refuses to resolve — fails closed here instead of materializing directories the
            // caller never asked to write into. (The caller-supplied parent above is still created,
            // matching the pre-existing save-as behavior for a non-link target.)
            if (!Files.isDirectory(installDirectory)) {
                throw new ToolArgException("Save target '" + absolute + "' resolves to '" + install
                        + "' but its directory '" + installDirectory + "' does not exist. Create the "
                        + "directory first or point the link at an existing folder.");
            }
            Path temp = Files.createTempFile(installDirectory, "." + install.getFileName() + ".", ".tmp");
            try {
                snapshot.ontology.getOWLOntologyManager().saveOntology(snapshot.ontology, snapshot.format,
                        IRI.create(temp.toFile()));
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                Verification verification = verify(snapshot.ontology, temp, snapshot.format);
                if (!verification.identical()) {
                    throw new ToolArgException("Verified save round trip was not exact: "
                            + verification.mismatches());
                }
                long bytes = Files.size(temp);
                String sha256 = RevisionTools.sha256File(temp);
                return new Prepared(temp, install, bytes, sha256, verification);
            } catch (RuntimeException | OWLOntologyStorageException | IOException e) {
                Files.deleteIfExists(temp);
                if (e instanceof ToolArgException) {
                    throw (ToolArgException) e;
                }
                throw new ToolArgException("Could not prepare verified save: " + message(e));
            }
        } catch (IOException e) {
            throw new ToolArgException("Could not create verified-save temporary file beside '" + absolute
                    + "': " + message(e));
        }
    }

    /**
     * Resolve a target that does not yet exist through any symlink chain it starts with.
     * {@link Path#toRealPath} cannot resolve a DANGLING link, yet a plain save (a stream opened
     * through the link) would create the linked destination — so walk the chain manually: read each
     * link, resolve a relative destination against the link's parent, and repeat while the result is
     * itself a symlink. Capped at {@value #MAX_LINK_HOPS} hops so a link cycle fails closed with an
     * actionable error instead of looping.
     *
     * <p>Deliberately NO lexical normalization: POSIX resolution of {@code missing/../actual.ttl}
     * must traverse the nonexistent {@code missing/} component and fail, exactly like a plain save
     * through the link. Collapsing the dots first would make the save succeed where open() cannot.
     * The unnormalized path is left for the caller's directory-existence check to judge — the OS
     * refuses to resolve a parent containing a nonexistent component, so such a target fails closed.
     */
    private static Path resolveNewTarget(Path absolute) throws IOException {
        Path current = absolute;
        for (int hops = 0; Files.isSymbolicLink(current); hops++) {
            if (hops >= MAX_LINK_HOPS) {
                throw new ToolArgException("Save target '" + absolute + "' is a symbolic-link chain "
                        + "longer than " + MAX_LINK_HOPS + " hops (or a link cycle). Point the link "
                        + "at a regular file location or save to the destination path directly.");
            }
            Path destination = Files.readSymbolicLink(current);
            Path parent = current.getParent();
            current = parent == null ? destination : parent.resolve(destination);
        }
        return current;
    }

    private static Verification verify(OWLOntology expected, Path temp, OWLDocumentFormat format) {
        OWLOntologyManager manager = OwlManagers.create();
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.THROW_EXCEPTION)
                .setFollowRedirects(false);
        // Verification compares this document's ID, header and asserted axioms only. Loading the import
        // closure is both irrelevant and dangerous: a save-as away from its catalog could contact the
        // network, and a release-scale aggregate (or hostile import fan-out) can exhaust memory. Satisfy
        // every declared import from ONE private anonymous empty document, exactly as the asserted-only
        // CLI diff does. The declarations themselves are still parsed and compared exactly below.
        Path placeholder = null;
        try {
            placeholder = Files.createTempFile("protege-mcp-verified-save-imports-", ".ofn");
            Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
            IRI placeholderIri = IRI.create(placeholder.toUri());
            manager.getIRIMappers().add(importIri -> placeholderIri);
            OWLOntology actual = manager.loadOntologyFromOntologyDocument(
                    OntologyDocumentTools.documentSource(temp.toString()), config);
            boolean id = ontologyIdMatches(expected.getOntologyID(), actual.getOntologyID());
            boolean imports = expected.getImportsDeclarations().equals(actual.getImportsDeclarations());
            boolean annotations = OwlDocumentSemantics.normalizedAnnotations(expected)
                    .equals(OwlDocumentSemantics.normalizedAnnotations(actual));
            boolean axioms = normalizedAxioms(expected).equals(normalizedAxioms(actual));
            boolean anonymous = OwlDocumentSemantics.hasAnonymousIndividuals(expected);
            boolean identical = id && imports && annotations && axioms;
            String roundTripClass = roundTripClass(identical, actual, format, temp);
            Map<String, Object> mismatch = new LinkedHashMap<>();
            if (!id) mismatch.put("ontology_id", false);
            if (!imports) mismatch.put("imports", false);
            if (!annotations) mismatch.put("ontology_annotations", false);
            if (!axioms) mismatch.put("axioms_including_annotations", false);
            if (anonymous) mismatch.put("anonymous_individuals", "present_session_ids_not_release_stable");
            return new Verification(id, imports, annotations, axioms, anonymous, roundTripClass, mismatch);
        } catch (IOException e) {
            throw new ToolArgException("Could not prepare isolated import handling for strict reload: "
                    + message(e));
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Strict reload of temporary ontology failed: "
                    + OwlParsingErrors.conciseMessage(e));
        } finally {
            if (placeholder != null) {
                try {
                    Files.deleteIfExists(placeholder);
                } catch (IOException ignored) {
                    // Cleanup of a private empty file must not mask the verification result.
                }
            }
        }
    }

    /**
     * OWL serializers may materialize otherwise implicit, unannotated declarations on save. Compare
     * the same normalized axiom set as the current fingerprint: add one unannotated declaration for
     * every entity used by this document's own axioms or ontology annotations, including built-ins.
     * Annotated
     * declarations remain distinct, and a declaration for an otherwise-unused entity is still
     * load-bearing because it participates in the document signature itself.
     */
    static Set<OWLAxiom> normalizedAxioms(OWLOntology ontology) {
        return OwlDocumentSemantics.normalizedAxioms(ontology);
    }

    /**
     * OWLAPI mints a fresh session-local anonymous id on every load, so raw {@link OWLOntologyID}
     * equality can never hold for a headerless ontology — even an exact round trip would always be
     * reported as an id mismatch. Two anonymous ids therefore count as matching; anonymous vs named
     * (or two different named ids) remains a genuine header change.
     */
    private static boolean ontologyIdMatches(OWLOntologyID expected, OWLOntologyID actual) {
        if (expected.isAnonymous() || actual.isAnonymous()) {
            return expected.isAnonymous() == actual.isAnonymous();
        }
        return expected.equals(actual);
    }

    /**
     * Classify the strength of a verified round trip. Only meaningful when the axiom-level
     * comparison already holds; a non-identical reload throws before this ever reaches a caller.
     * {@code byte_for_byte} is a strict superset signal: re-serialize the reloaded ontology with the
     * SAME format instance that wrote the artifact and compare bytes. {@code logically_equivalent} is
     * reserved (needs a reasoner) and is never returned here.
     */
    static String roundTripClass(boolean identical, OWLOntology reloaded, OWLDocumentFormat format,
            Path artifact) {
        if (identical && reserializeMatches(reloaded, format, artifact)) {
            return ROUND_TRIP_BYTE_FOR_BYTE;
        }
        return ROUND_TRIP_AXIOM_IDENTICAL;
    }

    /** True when re-serializing {@code reloaded} reproduces the artifact byte-for-byte. */
    private static boolean reserializeMatches(OWLOntology reloaded, OWLDocumentFormat format,
            Path artifact) {
        // Compare as the storer emits bytes. The previous ByteArrayOutputStream + readAllBytes
        // implementation retained two full copies of a release-sized ontology and allowed a large
        // verified save to exhaust the Protégé heap merely to classify the round trip.
        try (ComparingOutputStream out = new ComparingOutputStream(
                new BufferedInputStream(Files.newInputStream(artifact)))) {
            reloaded.getOWLOntologyManager().saveOntology(reloaded, format,
                    new StreamDocumentTarget(out));
            return out.matchesExactly();
        } catch (OWLOntologyStorageException | IOException | RuntimeException e) {
            // Cannot prove byte stability → fall back to the weaker axiom-identical class.
            return false;
        }
    }

    /** Fixed-memory byte comparator used as the reserialization target. */
    private static final class ComparingOutputStream extends OutputStream {
        private final InputStream expected;
        private final byte[] expectedBuffer = new byte[8192];
        private boolean mismatch;

        ComparingOutputStream(InputStream expected) {
            this.expected = expected;
        }

        @Override
        public void write(int value) throws IOException {
            if (!mismatch && expected.read() != (value & 0xff)) {
                mismatch = true;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (mismatch) {
                return;
            }
            int compared = 0;
            while (compared < length) {
                int wanted = Math.min(expectedBuffer.length, length - compared);
                int got = expected.readNBytes(expectedBuffer, 0, wanted);
                if (got != wanted) {
                    mismatch = true;
                    return;
                }
                for (int i = 0; i < wanted; i++) {
                    if (bytes[offset + compared + i] != expectedBuffer[i]) {
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

    static final class Prepared implements AutoCloseable {
        private Path temporary;
        final Path target;
        final long bytes;
        final String sha256;
        final Verification verification;

        Prepared(Path temporary, Path target, long bytes, String sha256, Verification verification) {
            this.temporary = temporary;
            this.target = target;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.verification = verification;
        }

        // install() (EDT) and close() (MCP transport thread, on a save timeout) can otherwise race on
        // `temporary`: close() must not delete the temp file out from under an in-flight atomic move.
        // Synchronizing both, and claiming the path once, makes them mutually exclusive and single-shot.
        synchronized Install install(boolean atomic, boolean backup) {
            Path source = temporary;
            if (source == null) {
                throw new IllegalStateException("prepared artifact was already installed or closed");
            }
            Path backupPath = null;
            try {
                if (backup && Files.exists(target)) {
                    backupPath = target.resolveSibling(target.getFileName() + ".bak");
                    Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                preserveTargetPermissions(source);
                if (atomic) {
                    try {
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        throw new ToolArgException("Filesystem does not support atomic replacement for '"
                                + target + "'. The previous artifact was preserved.");
                    }
                } else {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                temporary = null;
                return new Install(atomic, backupPath);
            } catch (IOException e) {
                throw new ToolArgException("Could not install verified ontology artifact: " + message(e));
            }
        }

        /**
         * {@link Files#createTempFile} makes the artifact owner-only (0600); renaming it over the
         * target would silently drop the previous mode (e.g. group/world read on a shared ontology),
         * which a plain save — writing into the existing file — preserves. Mirror the existing
         * target's POSIX permissions onto the artifact; a non-POSIX filesystem (or a target racing
         * away mid-read) keeps the restrictive default rather than failing the save.
         */
        private void preserveTargetPermissions(Path source) {
            try {
                if (Files.exists(target)) {
                    Files.setPosixFilePermissions(source, Files.getPosixFilePermissions(target));
                }
            } catch (UnsupportedOperationException | IOException nonPosixOrRaced) {
                // Keep the temp file's default permissions.
            }
        }

        @Override
        public synchronized void close() {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort; the owner-only OS temp entry is not the target artifact.
                }
                temporary = null;
            }
        }
    }

    record Snapshot(OWLOntology ontology, OWLDocumentFormat format) { }

    record Verification(boolean ontologyId, boolean imports, boolean ontologyAnnotations,
            boolean axiomsIncludingAnnotations, boolean anonymousIndividuals, String roundTripClass,
            Map<String, Object> mismatches) {
        boolean identical() {
            return ontologyId && imports && ontologyAnnotations && axiomsIncludingAnnotations;
        }

        Map<String, Object> toJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("identical", identical());
            result.put("round_trip_class", roundTripClass);
            result.put("ontology_id", ontologyId);
            result.put("imports", imports);
            result.put("ontology_annotations", ontologyAnnotations);
            result.put("axioms_including_annotations", axiomsIncludingAnnotations);
            result.put("anonymous_individuals", anonymousIndividuals);
            result.put("mismatches", mismatches);
            return result;
        }
    }

    record Install(boolean atomic, Path backupPath) { }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
