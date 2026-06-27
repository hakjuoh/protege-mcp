package org.protege.mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.util.OWLEntityRemover;
import org.semanticweb.owlapi.util.OWLEntityRenamer;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * High-level entity edits that span many axioms: renaming an entity's IRI and deleting an entity.
 * Both compute their {@link OWLOntologyChange}s with the OWL API utilities and apply them via
 * {@link OWLModelManager#applyChanges}, so they are GUI-visible and join the shared undo stack like
 * every other write tool. They operate on the <em>active</em> ontology only (imported ontologies are
 * not edited). Gated by the same read-only/confirmation switches as the other write tools.
 */
public final class EntityRefactorTools {

    private EntityRefactorTools() {
    }

    public static List<SyncToolSpecification> specs(ToolContext ctx) {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(ToolSpecs.of("rename_entity",
                "Change an entity's IRI throughout the active ontology (every axiom and annotation "
                        + "that references the old IRI is rewritten to the new one). If the IRI is "
                        + "punned across several entity types, all of them are renamed. The new IRI "
                        + "must be a full absolute IRI.",
                Tools.schema()
                        .strReq("entity", "Entity to rename: an IRI or display name.")
                        .strReq("new_iri", "New full IRI for the entity.")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    String newIriRef = Tools.reqString(a, "new_iri");
                    return WriteTools.write(ctx, "rename " + entityRef + " to " + newIriRef, mm -> {
                        OWLEntity entity = Tools.findEntity(mm, entityRef);
                        if (entity == null) {
                            return Tools.error("Entity not found: '" + entityRef + "'.");
                        }
                        IRI newIri = Tools.asIri(newIriRef);
                        if (newIri == null) {
                            return Tools.error("new_iri must be a full absolute IRI: '" + newIriRef + "'.");
                        }
                        IRI oldIri = entity.getIRI();
                        if (oldIri.equals(newIri)) {
                            return Tools.error("The entity already has IRI <" + newIri + ">.");
                        }
                        OWLOntology active = mm.getActiveOntology();
                        OWLEntityRenamer renamer = new OWLEntityRenamer(
                                mm.getOWLOntologyManager(), Collections.singleton(active));
                        List<OWLOntologyChange> changes = renamer.changeIRI(oldIri, newIri);
                        if (changes.isEmpty()) {
                            return Tools.error("Nothing to rename: <" + oldIri + "> is not referenced "
                                    + "in the active ontology.");
                        }
                        mm.applyChanges(changes);
                        return Tools.text("Renamed <" + oldIri + "> to <" + newIri + "> ("
                                + changes.size() + " change(s) in the active ontology).");
                    });
                })));

        tools.add(ToolSpecs.of("delete_entity",
                "Delete an entity from the active ontology: removes its declaration and every axiom "
                        + "that references it. If the IRI is punned across several entity types, all "
                        + "are removed unless 'entity_type' narrows it to one.",
                Tools.schema()
                        .strReq("entity", "Entity to delete: an IRI or display name.")
                        .str("entity_type", "Optional: class | object_property | data_property | "
                                + "annotation_property | individual | datatype (narrows a punned IRI).")
                        .build(),
                (ex, req) -> Tools.guard(() -> {
                    Map<String, Object> a = Tools.args(req);
                    String entityRef = Tools.reqString(a, "entity");
                    String entityType = Tools.optString(a, "entity_type");
                    return WriteTools.write(ctx, "delete entity " + entityRef
                            + (entityType != null ? " (" + entityType + ")" : ""), mm -> {
                        Set<OWLEntity> targets = resolveTargets(mm, entityRef, entityType);
                        if (targets.isEmpty()) {
                            return Tools.error("Entity not found: '" + entityRef + "'"
                                    + (entityType != null ? " of type " + entityType : "") + ".");
                        }
                        OWLOntology active = mm.getActiveOntology();
                        OWLEntityRemover remover = new OWLEntityRemover(Collections.singleton(active));
                        for (OWLEntity e : targets) {
                            e.accept(remover);
                        }
                        List<RemoveAxiom> changes = remover.getChanges();
                        if (changes.isEmpty()) {
                            return Tools.error("Nothing to delete: " + describe(mm, targets)
                                    + " is not referenced in the active ontology.");
                        }
                        mm.applyChanges(new ArrayList<OWLOntologyChange>(changes));
                        return Tools.text("Deleted " + describe(mm, targets) + " — removed "
                                + changes.size() + " axiom(s) from the active ontology.");
                    });
                })));

        return tools;
    }

    /** Resolve the entities to delete: all puns at the IRI, or just the one matching {@code entityType}. */
    private static Set<OWLEntity> resolveTargets(OWLModelManager mm, String entityRef, String entityType) {
        Set<OWLEntity> all = Tools.findEntities(mm, entityRef);
        if (entityType == null) {
            return all;
        }
        String want = entityType.toLowerCase();
        Set<OWLEntity> filtered = new TreeSet<>(byIri());
        for (OWLEntity e : all) {
            if (typeKey(e).equals(want)) {
                filtered.add(e);
            }
        }
        return filtered;
    }

    private static String typeKey(OWLEntity e) {
        if (e.isOWLClass()) {
            return "class";
        }
        if (e.isOWLObjectProperty()) {
            return "object_property";
        }
        if (e.isOWLDataProperty()) {
            return "data_property";
        }
        if (e.isOWLAnnotationProperty()) {
            return "annotation_property";
        }
        if (e.isOWLNamedIndividual()) {
            return "individual";
        }
        if (e.isOWLDatatype()) {
            return "datatype";
        }
        return e.getEntityType().getName().toLowerCase();
    }

    private static java.util.Comparator<OWLEntity> byIri() {
        return java.util.Comparator.comparing(e -> e.getEntityType().getName() + " " + e.getIRI());
    }

    private static String describe(OWLModelManager mm, Set<OWLEntity> targets) {
        if (targets.size() == 1) {
            OWLEntity e = targets.iterator().next();
            return e.getEntityType().getName() + " " + Tools.renderEntity(mm, e);
        }
        StringBuilder sb = new StringBuilder(targets.size() + " entities at <");
        sb.append(targets.iterator().next().getIRI()).append(">");
        return sb.toString();
    }
}
