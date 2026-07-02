package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;

/**
 * The travels-inside-the-artifact competency-question convention: each CQ is stored as one
 * ontology-level annotation (a JSON literal under the {@link Cq#ANNOTATION_IRI} property), written with
 * {@code AddOntologyAnnotation}. Unlike the file conventions this write <em>is</em> an undoable ontology
 * change on the shared {@code HistoryManager} (one transaction), so — like every other write tool — it is
 * gated by {@code checkWriteAllowed} (done by the tool) and appears on the GUI undo stack.
 *
 * <p>This is the fallback writer when the ontology has no saved {@code file:} document: the file
 * conventions cannot resolve a path, so {@code add} lands here instead of refusing.
 */
final class OntologyAnnotationsStore implements CqStore {

    @Override
    public String conventionId() {
        return Cq.CONV_ANNOTATIONS;
    }

    @Override
    public boolean isWritable(CqContext ctx) {
        return true;   // writes into the ontology, so no document folder is needed
    }

    @Override
    public boolean detect(CqContext ctx) {
        return !cqAnnotations(ctx.active(), ctx.annotationProperty().toString()).isEmpty();
    }

    @Override
    public LoadResult load(CqContext ctx) {
        LoadResult result = new LoadResult();
        String prop = ctx.annotationProperty().toString();
        int i = 0;
        for (OWLAnnotation ann : cqAnnotations(ctx.active(), prop)) {
            String source = "ontology-annotation#" + (i++);
            String json = literal(ann);
            if (json == null) {
                result.skip(source, "CQ annotation value is not a literal");
                continue;
            }
            Map<String, Object> parsed = Cq.JSON.readMapOrNull(json);
            if (parsed == null) {
                result.skip(source, "CQ annotation value is not a JSON object");
                continue;
            }
            try {
                CompetencyQuestion cq = CompetencyQuestion.fromStorageJson(parsed);
                cq.convention = conventionId();
                result.add(cq);
            } catch (RuntimeException e) {
                result.skip(source, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return result;
    }

    @Override
    public void upsert(CqContext ctx, CompetencyQuestion cq) {
        OWLModelManager mm = ctx.mm();
        OWLOntology active = ctx.active();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAnnotationProperty prop = df.getOWLAnnotationProperty(ctx.annotationProperty());
        OWLAnnotation existing = findById(active, prop.getIRI().toString(), cq.id);
        OWLAnnotation replacement = df.getOWLAnnotation(prop,
                df.getOWLLiteral(Cq.JSON.toJsonOrEmpty(cq.toStorageJson())));
        List<OWLOntologyChange> changes = new ArrayList<>();
        if (existing != null) {
            changes.add(new RemoveOntologyAnnotation(active, existing));
        }
        changes.add(new AddOntologyAnnotation(active, replacement));
        mm.applyChanges(changes);   // one broadcast → one undo entry
    }

    @Override
    public boolean remove(CqContext ctx, String id) {
        OWLModelManager mm = ctx.mm();
        OWLOntology active = ctx.active();
        OWLAnnotation existing = findById(active, ctx.annotationProperty().toString(), id);
        if (existing == null) {
            return false;
        }
        mm.applyChange(new RemoveOntologyAnnotation(active, existing));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static OWLAnnotation findById(OWLOntology active, String propIri, String id) {
        for (OWLAnnotation ann : cqAnnotations(active, propIri)) {
            String json = literal(ann);
            Map<String, Object> parsed = json == null ? null : Cq.JSON.readMapOrNull(json);
            if (parsed != null && id.equals(String.valueOf(parsed.get("id")))) {
                return ann;
            }
        }
        return null;
    }

    private static List<OWLAnnotation> cqAnnotations(OWLOntology active, String propIri) {
        List<OWLAnnotation> out = new ArrayList<>();
        for (OWLAnnotation ann : active.getAnnotations()) {
            if (ann.getProperty().getIRI().toString().equals(propIri)) {
                out.add(ann);
            }
        }
        return out;
    }

    private static String literal(OWLAnnotation ann) {
        if (ann.getValue().asLiteral().isPresent()) {
            OWLLiteral lit = ann.getValue().asLiteral().get();
            return lit.getLiteral();
        }
        return null;
    }
}
