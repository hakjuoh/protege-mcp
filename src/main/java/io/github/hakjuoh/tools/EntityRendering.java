package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObject;

/**
 * Renders OWL entities/axioms/objects to display strings via Protégé's active renderer, falling back to
 * {@code toString()} for renderers that only handle entities. Split out of {@link Tools} as a focused
 * unit; {@code Tools} keeps thin delegators for source compatibility.
 */
public final class EntityRendering {

    private EntityRendering() {
    }

    public static String renderEntity(OWLModelManager mm, OWLEntity e) {
        return mm.getRendering(e) + "  <" + e.getIRI() + ">";
    }

    public static String renderAxiom(OWLModelManager mm, OWLAxiom ax) {
        String r = null;
        try {
            r = mm.getRendering(ax);
        } catch (RuntimeException ignored) {
            // some renderers only handle entities; fall back to toString
        }
        if (r == null || r.isEmpty()) {
            r = ax.toString();
        }
        return r;
    }

    /**
     * Render a set of would-be-minted entities as a comma-separated {@code "rendering <IRI>"} list, for
     * the strict-mode error that refuses to silently create new entities from a typo'd reference. Shared
     * by the write and curation tools so the message stays identical in both.
     */
    public static String renderMinted(OWLModelManager mm, Set<OWLEntity> minted) {
        List<String> parts = new ArrayList<>();
        for (OWLEntity e : minted) {
            parts.add(mm.getRendering(e) + " <" + e.getIRI() + ">");
        }
        return String.join(", ", parts);
    }

    /** Render any OWL object (class expression, axiom, ...) via Protégé, falling back to toString. */
    public static String renderObject(OWLModelManager mm, OWLObject o) {
        try {
            String r = mm.getRendering(o);
            if (r != null && !r.isEmpty()) {
                return r;
            }
        } catch (RuntimeException ignored) {
            // some renderers only handle entities; fall back to toString
        }
        return o.toString();
    }
}
