package io.github.hakjuoh.protege_mcp.contracts;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NQuadsDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import com.apicatalog.rdf.api.RdfConsumerException;
import com.apicatalog.rdf.canon.RdfCanon;
import com.apicatalog.rdf.canon.RdfCanonTimeTicker;
import com.apicatalog.rdf.nquads.NQuadsReader;
import com.apicatalog.rdf.nquads.NQuadsReaderException;
import com.apicatalog.rdf.nquads.NQuadsWriter;

/** W3C RDFC-1.0 + SHA-256 identity for the asserted root-ontology RDF dataset. */
public final class RdfDatasetFingerprints {

    public static final String CANONICALIZATION_ALGORITHM = "RDFC-1.0";
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String SCOPE = "root-ontology";
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private RdfDatasetFingerprints() {
    }

    /** Compute the standard digest using the ontology's own direct import declarations. */
    public static RdfDatasetFingerprint compute(OWLOntology ontology) {
        return compute(ontology, null, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Compute the standard digest with explicit direct import coordinates. The override is used for
     * private validation snapshots, which deliberately omit import declarations to prevent dereferencing.
     * Imported axioms are never folded into this root-dataset digest.
     *
     * <p>{@code timeoutMs} bounds the whole computation, checked at every phase boundary
     * (serialization, ingestion, canonicalization, ordering) and continuously inside the
     * canonicalization core; one linear phase can only overrun by its own single pass.
     */
    public static RdfDatasetFingerprint compute(OWLOntology ontology, List<String> importIriOverride,
            long timeoutMs) {
        if (ontology == null) {
            throw new IllegalArgumentException("ontology must not be null");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeout_ms must be positive");
        }
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        String nquads = serializeRootDataset(ontology, importIriOverride);
        verifyAssertionsRendered(ontology, nquads);
        String canonical = canonicalizeNQuads(nquads, remainingMs(deadline, "root dataset serialization"));
        byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
        return new RdfDatasetFingerprint(CANONICALIZATION_ALGORITHM, HASH_ALGORITHM, SCOPE,
                sha256(canonicalBytes), canonicalBytes.length);
    }

    private static String serializeRootDataset(OWLOntology source, List<String> importIriOverride) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try {
            OWLOntology copy = manager.createOntology(source.getOntologyID());
            manager.addAxioms(copy, source.getAxioms());
            source.getAnnotations().forEach(annotation ->
                    manager.applyChange(new AddOntologyAnnotation(copy, annotation)));

            List<String> imports = new ArrayList<>();
            if (importIriOverride == null) {
                source.getImportsDeclarations().stream()
                        .map(OWLImportsDeclaration::getIRI)
                        .map(IRI::toString)
                        .forEach(imports::add);
            } else {
                imports.addAll(importIriOverride);
            }
            Collections.sort(imports);
            String previous = null;
            for (String importIri : imports) {
                if (importIri == null || importIri.equals(previous)) {
                    continue;
                }
                previous = importIri;
                OWLImportsDeclaration declaration = manager.getOWLDataFactory()
                        .getOWLImportsDeclaration(IRI.create(importIri));
                manager.applyChange(new AddImport(copy, declaration));
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            manager.saveOntology(copy, new NQuadsDocumentFormat(), bytes);
            return bytes.toString(StandardCharsets.UTF_8);
        } catch (OWLOntologyCreationException | OWLOntologyStorageException | RuntimeException e) {
            throw new IllegalStateException("Could not render the root ontology as an RDF dataset: "
                    + message(e), e);
        }
    }

    /**
     * OWLAPI's RDF renderer emits anonymous individuals as trees hanging from reachable roots and
     * silently DROPS assertions it cannot anchor: unanchored reference cycles, anonymous
     * inverse-property pairs, and anonymous annotation cycles all vanish from the N-Quads output,
     * so two ontologies differing only in such structures would fingerprint identically. Instead
     * of modelling the renderer's reachability rules, the serialized dataset is verified directly:
     * every object/data/annotation property assertion (and every axiom/ontology annotation) must
     * appear as a triple whose predicate is its simplified property IRI. Rendering loss fails the
     * digest closed, never biases it. Two assertions that map to the SAME triple — e.g. p(x,y)
     * plus inverseOf(p)(y,x) — also fail closed, because a set-of-triples rendering cannot
     * represent both.
     */
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    /** Built-in annotation properties: every triple they emit comes from a counted annotation. */
    private static final Set<String> BUILT_IN_ANNOTATION_PROPERTIES = Set.of(
            RDFS_NS + "label", RDFS_NS + "comment", RDFS_NS + "seeAlso", RDFS_NS + "isDefinedBy",
            OWL_NS + "versionInfo", OWL_NS + "deprecated", OWL_NS + "priorVersion",
            OWL_NS + "backwardCompatibleWith", OWL_NS + "incompatibleWith");

    private static void verifyAssertionsRendered(OWLOntology ontology, String nquads) {
        Map<String, Integer> expected = new HashMap<>();
        for (OWLObjectPropertyAssertionAxiom assertion
                : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
            OWLObjectPropertyAssertionAxiom simplified = assertion.getSimplified();
            expected.merge(verifiableProperty(
                    simplified.getProperty().asOWLObjectProperty().getIRI().toString(), false),
                    1, Integer::sum);
        }
        for (OWLDataPropertyAssertionAxiom assertion
                : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
            expected.merge(verifiableProperty(
                    assertion.getProperty().asOWLDataProperty().getIRI().toString(), false),
                    1, Integer::sum);
        }
        for (OWLAnnotationAssertionAxiom assertion
                : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            expected.merge(verifiableProperty(
                    assertion.getProperty().getIRI().toString(), true), 1, Integer::sum);
        }
        rejectRootlessAnonymousTypeCycles(ontology);
        // SameIndividual(a1..an) emits exactly n-1 chained owl:sameAs triples and a two-element
        // DifferentIndividuals emits one owl:differentFrom triple; nothing else emits either
        // predicate, so dropped sameAs/differentFrom-linked anonymous structures are countable.
        // (An n-ary DifferentIndividuals reifies from a freshly rooted owl:AllDifferent node and
        // is never dropped.) Overlapping axioms that collapse to one triple fail closed, like
        // the inverse-pair collapse above.
        for (OWLSameIndividualAxiom axiom : ontology.getAxioms(AxiomType.SAME_INDIVIDUAL)) {
            expected.merge(OWL_NS + "sameAs", axiom.getIndividuals().size() - 1, Integer::sum);
        }
        for (OWLDifferentIndividualsAxiom axiom
                : ontology.getAxioms(AxiomType.DIFFERENT_INDIVIDUALS)) {
            if (axiom.getIndividuals().size() == 2) {
                expected.merge(OWL_NS + "differentFrom", 1, Integer::sum);
            }
        }
        // Negative assertions reify instead of emitting their property as a predicate; each one
        // emits exactly one owl:sourceIndividual triple and nothing else does (user assertions
        // misusing owl:* vocabulary are rejected above), so that predicate counts them.
        int negatives = ontology.getAxioms(AxiomType.NEGATIVE_OBJECT_PROPERTY_ASSERTION).size()
                + ontology.getAxioms(AxiomType.NEGATIVE_DATA_PROPERTY_ASSERTION).size();
        if (negatives > 0) {
            expected.merge(OWL_NS + "sourceIndividual", negatives, Integer::sum);
        }
        for (OWLAxiom axiom : ontology.getAxioms()) {
            countAnnotations(axiom.getAnnotations(), expected);
        }
        countAnnotations(ontology.getAnnotations(), expected);
        if (expected.isEmpty()) {
            return;
        }

        Map<String, Integer> rendered = new HashMap<>();
        for (String line : nquads.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int subjectEnd = line.indexOf(' ');
            int predicateEnd = subjectEnd < 0 ? -1 : line.indexOf(' ', subjectEnd + 1);
            if (predicateEnd < 0 || line.charAt(subjectEnd + 1) != '<') {
                continue;
            }
            rendered.merge(line.substring(subjectEnd + 2, predicateEnd - 1), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            int emitted = rendered.getOrDefault(entry.getKey(), 0);
            if (emitted < entry.getValue()) {
                throw new IllegalStateException("The OWL RDF rendering dropped "
                        + (entry.getValue() - emitted) + " assertion triple(s) of <"
                        + entry.getKey() + "> (expected " + entry.getValue() + ", rendered "
                        + emitted + "). Rootless anonymous-individual structures cannot be "
                        + "serialized losslessly, so the RDF dataset digest would silently ignore "
                        + "them; name the anonymous individuals or anchor them to a named "
                        + "individual.");
            }
        }
    }

    /**
     * The one drop family the predicate counts cannot see: class assertions typing anonymous
     * individuals with anonymous expressions that reference anonymous individuals emit only
     * rdf:type/owl:* triples, which are unattributable. A rootless component needs every node to
     * carry an incoming edge, i.e. a directed cycle; components mixing in any counted assertion
     * are already covered by the counts, so cycle-detecting the pure class-assertion reference
     * graph closes the gap (e.g. {@code ClassAssertion(ObjectHasValue(p,x), x)} vanishes from the
     * OWLAPI rendering). An externally anchored pure-type cycle would also be rejected — the
     * conservative, fail-closed direction.
     */
    private static void rejectRootlessAnonymousTypeCycles(OWLOntology ontology) {
        Map<OWLAnonymousIndividual, Set<OWLAnonymousIndividual>> references = new HashMap<>();
        for (OWLClassAssertionAxiom assertion : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            if (assertion.getIndividual().isAnonymous()
                    && assertion.getClassExpression().isAnonymous()) {
                Set<OWLAnonymousIndividual> referenced =
                        assertion.getClassExpression().getAnonymousIndividuals();
                if (!referenced.isEmpty()) {
                    references.computeIfAbsent(
                            (OWLAnonymousIndividual) assertion.getIndividual(),
                            k -> new LinkedHashSet<>()).addAll(referenced);
                }
            }
        }
        Set<OWLAnonymousIndividual> done = new HashSet<>();
        Set<OWLAnonymousIndividual> inProgress = new HashSet<>();
        Deque<OWLAnonymousIndividual> stack = new ArrayDeque<>();
        for (OWLAnonymousIndividual start : references.keySet()) {
            stack.push(start);
            while (!stack.isEmpty()) {
                OWLAnonymousIndividual current = stack.peek();
                if (done.contains(current)) {
                    stack.pop();
                    continue;
                }
                if (inProgress.add(current)) {
                    for (OWLAnonymousIndividual next
                            : references.getOrDefault(current, Collections.emptySet())) {
                        if (inProgress.contains(next)) {
                            throw new IllegalStateException("The root ontology types anonymous "
                                    + "individuals with class expressions that reference "
                                    + "anonymous individuals in a cycle; the OWL RDF rendering "
                                    + "drops such rootless structures silently, so the RDF "
                                    + "dataset digest would not cover them. Name at least one "
                                    + "individual in the cycle.");
                        }
                        if (!done.contains(next)) {
                            stack.push(next);
                        }
                    }
                } else {
                    stack.pop();
                    inProgress.remove(current);
                    done.add(current);
                }
            }
        }
    }

    private static void countAnnotations(Set<OWLAnnotation> annotations,
            Map<String, Integer> expected) {
        for (OWLAnnotation annotation : annotations) {
            expected.merge(verifiableProperty(
                    annotation.getProperty().getIRI().toString(), true), 1, Integer::sum);
            countAnnotations(annotation.getAnnotations(), expected);
        }
    }

    /**
     * The count comparison is only attributable when nothing else emits the predicate. Reserved
     * RDF/RDFS/OWL vocabulary is emitted by the OWL-to-RDF mapping itself (declarations alone add
     * rdf:type triples), so an assertion misusing it as a property — forbidden in OWL 2 DL — could
     * mask a dropped anonymous structure behind mapping-emitted triples. Such assertions fail
     * closed. The built-in annotation properties stay verifiable: the mapping never emits them
     * outside counted annotations.
     */
    private static String verifiableProperty(String iri, boolean annotation) {
        if (annotation && BUILT_IN_ANNOTATION_PROPERTIES.contains(iri)) {
            return iri;
        }
        if (iri.startsWith(RDF_NS) || iri.startsWith(RDFS_NS) || iri.startsWith(OWL_NS)) {
            throw new IllegalStateException("Assertions using reserved RDF/RDFS/OWL vocabulary as "
                    + (annotation ? "an annotation property" : "a property") + " cannot be "
                    + "verified against the serialized dataset (OWL 2 DL reserves <" + iri
                    + ">); rename the property.");
        }
        return iri;
    }

    static String canonicalizeNQuads(String nquads, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeout_ms must be positive");
        }
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        try {
            RdfCanon canon = RdfCanon.create(HASH_ALGORITHM, new RdfCanonTimeTicker(timeoutMs));
            new NQuadsReader(new StringReader(nquads)).provide(canon);
            remainingMs(deadline, "N-Quads ingestion");
            StringWriter canonical = new StringWriter();
            canon.provide(new NQuadsWriter(canonical));
            remainingMs(deadline, "canonical serialization");
            String document = canonical.toString().replace("\r\n", "\n").replace('\r', '\n');
            return sortCanonicalQuads(document, deadline);
        } catch (NQuadsReaderException | RdfConsumerException | RuntimeException e) {
            throw new IllegalStateException("RDFC-1.0 canonicalization failed: " + message(e), e);
        }
    }

    /**
     * RDFC-1.0 serializes the canonical dataset in Unicode code point order, which equals UTF-8
     * byte order. Titanium 2.0.0 sorts with {@link String#compareTo}, whose UTF-16 code unit order
     * places supplementary characters (encoded as surrogates below U+E000) before U+E000..U+FFFF
     * characters, so the emitted quads are re-sorted here. Titanium's internal first-degree blank
     * node hashes sort the same way; that residual upstream deviation is only reachable when two
     * quads mentioning the same blank node first differ at a supplementary character.
     */
    private static String sortCanonicalQuads(String document, long deadline) {
        if (document.indexOf('\n') < 0) {
            return document;
        }
        String[] quads = document.split("\n");
        Arrays.sort(quads, RdfDatasetFingerprints::compareCodePoints);
        remainingMs(deadline, "canonical ordering");
        StringBuilder sorted = new StringBuilder(document.length());
        for (String quad : quads) {
            if (!quad.isEmpty()) {
                sorted.append(quad).append('\n');
            }
        }
        return sorted.toString();
    }

    private static int compareCodePoints(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    private static long remainingMs(long deadline, String phase) {
        long remaining = (deadline - System.nanoTime()) / 1_000_000L;
        if (remaining <= 0) {
            throw new IllegalStateException(
                    "RDF dataset canonicalization timed out during " + phase);
        }
        return remaining;
    }

    private static String sha256(byte[] value) {
        try {
            byte[] hash = MessageDigest.getInstance(HASH_ALGORITHM).digest(value);
            StringBuilder out = new StringBuilder("sha256:");
            for (byte b : hash) {
                out.append(Character.forDigit((b >>> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
