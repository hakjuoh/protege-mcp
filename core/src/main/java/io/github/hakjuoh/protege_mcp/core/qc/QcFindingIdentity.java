package io.github.hakjuoh.protege_mcp.core.qc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/** Stable, presentation-independent identities for bounded QC finding output. */
public final class QcFindingIdentity {

    private QcFindingIdentity() {
    }

    /** Canonical SHA-256 over sorted, UTF-8 byte-length-prefixed identities. */
    public static String digest(Collection<String> identities) {
        if (identities == null) {
            throw new IllegalArgumentException("identities must not be null");
        }
        List<String> sorted = new ArrayList<>(identities);
        sorted.sort(Comparator.naturalOrder());
        StringBuilder canonical = new StringBuilder();
        for (String identity : sorted) {
            String value = identity == null ? "" : identity;
            canonical.append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append(':').append(value).append('\n');
        }
        return ArtifactStore.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String entity(OWLEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
        return "entity\u0000" + entity.getEntityType().getName() + "\u0000" + entity.getIRI();
    }

    public static String axiom(OWLAxiom axiom) {
        if (axiom == null) {
            throw new IllegalArgumentException("axiom must not be null");
        }
        return "axiom\u0000" + axiom;
    }

    /** Canonical identity for nested maps/collections used by project-level governance rows. */
    public static String object(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                fields.add(String.valueOf(entry.getKey()) + "\u0000" + object(entry.getValue()));
            }
            fields.sort(Comparator.naturalOrder());
            return "map\u0000" + String.join("\u0001", fields);
        }
        if (value instanceof Collection<?> collection) {
            List<String> items = collection.stream().map(QcFindingIdentity::object).sorted().toList();
            return "collection\u0000" + String.join("\u0001", items);
        }
        return value.getClass().getName() + "\u0000" + value;
    }
}
