package io.github.hakjuoh.tools;

import java.io.File;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The context a {@link CqStore} operates in: the live model, the active ontology, and — for the file
 * conventions — the folder of its saved document plus a basename. Built once per tool call on the model
 * thread (its factory reads {@code getOntologyDocumentIRI}); the stores then read/write against it.
 *
 * <p>{@link #documentFolder} is {@code null} when the ontology has never been saved to a local file, which
 * is exactly when the file conventions cannot resolve a path and {@code add} falls back to the
 * {@code ontology-annotations} convention.
 */
final class CqContext {

    private final OWLModelManager mm;
    private final OWLOntology active;
    private final OWLOntologyManager om;
    private final File documentFolder;   // nullable
    private final String basename;       // nullable — the document file name without its extension

    private CqContext(OWLModelManager mm, OWLOntology active, OWLOntologyManager om, File documentFolder,
            String basename) {
        this.mm = mm;
        this.active = active;
        this.om = om;
        this.documentFolder = documentFolder;
        this.basename = basename;
    }

    /** Build from a live model manager (call on the model thread). */
    static CqContext of(OWLModelManager mm) {
        OWLOntology active = mm.getActiveOntology();
        OWLOntologyManager om = mm.getOWLOntologyManager();
        File docFile = SidecarPaths.toFile(om.getOntologyDocumentIRI(active));
        File folder = docFile == null ? null : docFile.getParentFile();
        String basename = docFile == null ? null : stripExtension(docFile.getName());
        return new CqContext(mm, active, om, folder, basename);
    }

    OWLModelManager mm() {
        return mm;
    }

    OWLOntology active() {
        return active;
    }

    OWLOntologyManager om() {
        return om;
    }

    /** The document folder, or {@code null} if the ontology has no saved local file. */
    File documentFolder() {
        return documentFolder;
    }

    /** The {@code cqs/} query folder next to the document (null when there is no document folder). */
    File cqsDir() {
        return documentFolder == null ? null : new File(documentFolder, Cq.DIR);
    }

    /** The {@code <basename>-cqs.json} manifest file (null when there is no document folder). */
    File manifestFile() {
        return documentFolder == null ? null
                : new File(documentFolder, (basename == null ? "ontology" : basename) + Cq.MANIFEST_SUFFIX);
    }

    /** The CQ annotation property IRI. */
    IRI annotationProperty() {
        return IRI.create(Cq.ANNOTATION_IRI);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
