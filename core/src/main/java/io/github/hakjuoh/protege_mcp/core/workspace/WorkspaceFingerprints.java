package io.github.hakjuoh.protege_mcp.core.workspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;

/** Deterministic fingerprints spanning every member of a loaded import closure. */
public final class WorkspaceFingerprints {

    private WorkspaceFingerprints() {
    }

    public static String closure(Set<OWLOntology> closure) {
        return closure(closure, null);
    }

    /**
     * Fingerprint a closure whose copied members may need their original direct-import coordinates.
     * Coordinates are keyed by {@link #ontologyKey(OWLOntology)}; a null map reads live declarations.
     */
    public static String closure(Set<OWLOntology> closure,
            Map<String, List<String>> importCoordinates) {
        if (closure == null || closure.isEmpty() || closure.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("closure must contain at least one ontology");
        }
        List<String> members = new ArrayList<>();
        for (OWLOntology ontology : closure) {
            String key = ontologyKey(ontology);
            List<String> imports = importCoordinates == null ? null : importCoordinates.get(key);
            String semantic = OntologyFingerprints.compute(ontology, null, imports)
                    .semanticFingerprint();
            members.add(key.getBytes(StandardCharsets.UTF_8).length + ":" + key + "\u0000" + semantic);
        }
        Collections.sort(members);
        return sha256(String.join("\n", members).getBytes(StandardCharsets.UTF_8));
    }

    public static String ontologyKey(OWLOntology ontology) {
        if (ontology == null) {
            throw new IllegalArgumentException("ontology must not be null");
        }
        return ontologyKey(ontology.getOntologyID());
    }

    private static String ontologyKey(OWLOntologyID id) {
        IRI ontologyIri = id.getOntologyIRI().orNull();
        IRI versionIri = id.getVersionIRI().orNull();
        return (ontologyIri == null ? "" : ontologyIri.toString()) + "\u0000"
                + (versionIri == null ? "" : versionIri.toString()) + "\u0000" + id;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
