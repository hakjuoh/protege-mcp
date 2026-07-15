package io.github.hakjuoh.protege_mcp.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import io.github.hakjuoh.protege_mcp.core.owl.OwlDocumentSemantics;

/**
 * Canonical semantic and live-document fingerprints for an OWLAPI ontology.
 *
 * <p>The semantic digest covers the ontology/version IRIs, direct import declarations, ontology
 * annotations, and active-ontology axioms including axiom annotations. Imported content is deliberately
 * excluded: import coordinates are covered here and an import-lock digest belongs in the document
 * fingerprint. The document digest additionally covers the live document IRI, format key, sorted prefix
 * map, and import-lock digest.
 *
 * <p>Objects are rendered independently using OWL Functional Syntax with one fixed, empty prefix manager,
 * normalized for line endings, and then sorted. Consequently neither ontology collection iteration order
 * nor the user's renderer/prefix configuration participates in the semantic digest. This is version 2 of
 * the algorithm; changing any byte-level rule requires a new version.
 */
public final class OntologyFingerprints {

    public static final int CANONICALIZATION_VERSION = 2;
    private static final String HEADER = "protege-mcp-ontology-fingerprint-v2";
    private static final String ANONYMOUS_WARNING = "Anonymous individuals are present. OWLAPI NodeIDs are "
            + "not stable across parse/restart boundaries, so this digest is a same-session conflict token "
            + "only and must not be used in a strict release manifest.";

    private OntologyFingerprints() {
    }

    /** Fingerprint using the ontology manager's current document metadata and no import-lock digest. */
    public static OntologyFingerprint compute(OWLOntology ontology) {
        return compute(ontology, null);
    }

    /**
     * Fingerprint using the ontology manager's current document metadata. {@code importLockDigest}, when
     * supplied, must already be a canonical {@code sha256:...} digest.
     */
    public static OntologyFingerprint compute(OWLOntology ontology, String importLockDigest) {
        return compute(ontology, importLockDigest, null);
    }

    /**
     * As {@link #compute(OWLOntology, String)} but with the direct import declarations supplied
     * explicitly. Used by isolated QC copies, which deliberately omit import declarations (replaying an
     * unresolved declaration could force network I/O) yet must fingerprint as the real ontology does —
     * imports are semantically load-bearing, so a copy fingerprinted without them would never match any
     * live revision. Pass {@code null} to read declarations from the ontology itself.
     */
    public static OntologyFingerprint compute(OWLOntology ontology, String importLockDigest,
            List<String> importIriOverride) {
        if (ontology == null) {
            throw new IllegalArgumentException("ontology must not be null");
        }
        if (importLockDigest != null) {
            importLockDigest = ContractValues.fingerprint(importLockDigest, "import_lock_digest");
        }

        byte[] semanticBytes = semanticBytes(ontology, importIriOverride);
        String semantic = digest(semanticBytes);
        String document = digest(documentBytes(ontology, semantic, importLockDigest));
        boolean stable = !OwlDocumentSemantics.hasAnonymousIndividuals(ontology);
        return new OntologyFingerprint(CANONICALIZATION_VERSION, semantic, document,
                stable ? "cross_restart" : "session_only", stable,
                stable ? Collections.emptyList() : List.of(ANONYMOUS_WARNING));
    }

    static byte[] semanticBytes(OWLOntology ontology) {
        return semanticBytes(ontology, null);
    }

    static byte[] semanticBytes(OWLOntology ontology, List<String> importIriOverride) {
        CanonicalBytes out = new CanonicalBytes();
        out.add("header", HEADER);
        OWLOntologyID id = ontology.getOntologyID();
        out.add("ontology_iri", optionalIri(id.getOntologyIRI().orNull()));
        out.add("version_iri", optionalIri(id.getVersionIRI().orNull()));

        // Import declarations are a SET: the override must canonicalize to the same bytes as the real
        // declarations regardless of duplicates or order, so dedup before sorting (matches the
        // getImportsDeclarations() set the non-override path reads).
        Set<String> imports = new TreeSet<>();
        if (importIriOverride != null) {
            imports.addAll(importIriOverride);
        } else {
            ontology.getImportsDeclarations().forEach(d -> imports.add(d.getIRI().toString()));
        }
        imports.forEach(v -> out.add("import", v));

        canonicalObjects(ontology, ontology.getAnnotations()).forEach(v -> out.add("ontology_annotation", v));
        // Include the shared artifact-boundary normalization. Built-in entities are intentional here:
        // Manchester materializes rdfs/xsd frames just as RDF/XML materializes project declarations.
        canonicalObjects(ontology, OwlDocumentSemantics.normalizedAxioms(ontology))
                .forEach(v -> out.add("axiom", v));
        return out.bytes();
    }

    private static byte[] documentBytes(OWLOntology ontology, String semantic, String importLockDigest) {
        CanonicalBytes out = new CanonicalBytes();
        out.add("header", HEADER + "-document");
        out.add("semantic_fingerprint", semantic);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        IRI documentIri = manager == null ? null : manager.getOntologyDocumentIRI(ontology);
        OWLDocumentFormat format = manager == null ? null : manager.getOntologyFormat(ontology);
        out.add("document_iri", optionalIri(documentIri));
        out.add("format", format == null ? "" : format.getKey());

        Map<String, String> prefixes = new TreeMap<>();
        if (format != null && format.isPrefixOWLOntologyFormat()) {
            prefixes.putAll(format.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap());
        }
        prefixes.forEach((name, iri) -> out.add("prefix", name + "\u0000" + iri));
        out.add("import_lock_digest", importLockDigest == null ? "" : importLockDigest);
        return out.bytes();
    }

    private static List<String> canonicalObjects(OWLOntology context,
            Iterable<? extends OWLObject> objects) {
        List<String> rendered = new ArrayList<>();
        for (OWLObject object : objects) {
            rendered.add(OwlDocumentSemantics.render(context, object));
        }
        Collections.sort(rendered);
        return rendered;
    }

    private static String optionalIri(IRI iri) {
        return iri == null ? "" : iri.toString();
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Length-delimited UTF-8 components prevent concatenation ambiguity without JSON dependencies. */
    private static final class CanonicalBytes {
        private final StringBuilder value = new StringBuilder();

        void add(String type, String item) {
            String safe = item == null ? "" : item;
            value.append(type.length()).append(':').append(type)
                    .append(':').append(safe.getBytes(StandardCharsets.UTF_8).length).append(':')
                    .append(safe).append('\n');
        }

        byte[] bytes() {
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
