package io.github.hakjuoh.protege_mcp.tools;

import java.util.Collection;
import java.util.Map;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;

import io.github.hakjuoh.protege_mcp.core.qc.GovernanceQcService;

/** Protégé rendering/grounding adapter for the shared governance stage. */
final class GovernancePresentation implements GovernanceQcService.Presentation {

    private final OWLModelManager modelManager;

    GovernancePresentation(OWLModelManager modelManager) {
        this.modelManager = java.util.Objects.requireNonNull(modelManager, "modelManager");
    }

    @Override
    public String renderEntity(OWLEntity entity) {
        return modelManager.getRendering(entity);
    }

    @Override
    public String renderAxiom(OWLAxiom axiom) {
        return Tools.renderAxiom(modelManager, axiom);
    }

    @Override
    public OWLAnnotationProperty resolveAnnotationProperty(String reference) {
        return Tools.resolveAnnotationProperty(modelManager, reference);
    }

    @Override
    public Map<String, Object> entityList(Collection<? extends OWLEntity> entities, int limit) {
        return Tools.entityList(modelManager, entities, limit);
    }

    @Override
    public Map<String, Object> axiomList(Collection<? extends OWLAxiom> axioms, int limit) {
        return Tools.axiomList(modelManager, axioms, limit);
    }
}
