package io.github.hakjuoh.protege_mcp.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;

/** Stable, bounded identity digests for QC findings whose public examples may be truncated. */
final class FindingIdentity {

    private FindingIdentity() {
    }

    static String digest(Collection<String> identities) {
        List<String> sorted = new ArrayList<>(identities);
        sorted.sort(Comparator.naturalOrder());
        StringBuilder canonical = new StringBuilder();
        for (String identity : sorted) {
            String value = identity == null ? "" : identity;
            canonical.append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append(':').append(value).append('\n');
        }
        return RevisionTools.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String entity(OWLEntity entity) {
        return "entity\u0000" + entity.getEntityType().getName() + "\u0000" + entity.getIRI();
    }

    static String axiom(OWLAxiom axiom) {
        return "axiom\u0000" + axiom;
    }

    static String object(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                fields.add(String.valueOf(entry.getKey()) + "\u0000" + object(entry.getValue()));
            }
            fields.sort(Comparator.naturalOrder());
            return "map\u0000" + String.join("\u0001", fields);
        }
        if (value instanceof Collection<?> collection) {
            List<String> items = collection.stream().map(FindingIdentity::object).sorted().toList();
            return "collection\u0000" + String.join("\u0001", items);
        }
        return value.getClass().getName() + "\u0000" + value;
    }
}
