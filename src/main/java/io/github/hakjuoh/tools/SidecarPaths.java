package io.github.hakjuoh.tools;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Resolves the on-disk location of <em>sidecar</em> files that live next to an ontology document but
 * outside its OWL axiom model — an OASIS import catalog ({@code catalog-v001.xml}), a competency-question
 * manifest, a {@code cqs/} query folder, and so on. Extracted from {@link CatalogTools} (whose
 * {@code resolveCatalogFile}/{@code toFile} were private) so every sidecar-writing tool resolves paths the
 * same way: relative to {@code om.getOntologyDocumentIRI(active)}, and — for an explicit override — scoped
 * to the document's own folder so a caller cannot traverse out of it.
 *
 * <p>Pure and Protégé-free apart from the {@link OWLOntologyManager} lookup, so it is unit-tested directly.
 */
public final class SidecarPaths {

    private SidecarPaths() {
    }

    /** Convert a {@code file:} IRI to a {@link File}; null for a non-file (or null) document IRI. */
    public static File toFile(IRI iri) {
        if (iri == null) {
            return null;
        }
        try {
            URI uri = iri.toURI();
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return new File(uri);
            }
        } catch (RuntimeException ignored) {
            // not a file URI
        }
        return null;
    }

    /**
     * The folder holding the active ontology's saved document, or {@code null} if it has never been
     * saved to a local file (a {@code file:} document IRI is required). Callers that can degrade — e.g.
     * the CQ stores falling back to the ontology-annotations convention — check for null rather than
     * catching an exception; callers that require a folder use {@link #resolveSidecarFile}.
     */
    public static File documentFolder(OWLOntologyManager om, OWLOntology active) {
        File docFile = toFile(om.getOntologyDocumentIRI(active));
        return docFile == null ? null : docFile.getParentFile();
    }

    /** Convenience over {@link #documentFolder(OWLOntologyManager, OWLOntology)} from a model manager. */
    public static File documentFolder(OWLModelManager mm) {
        return documentFolder(mm.getOWLOntologyManager(), mm.getActiveOntology());
    }

    /**
     * Resolve the concrete sidecar file named {@code fileName}. With an explicit {@code path}: a folder
     * (or trailing-separator / already-{@code fileName} path) yields {@code path/fileName}; a path already
     * naming {@code fileName} is used as-is; any other file path is treated as a folder to write into.
     * Without a {@code path}, the file is placed next to the active document. Throws {@link ToolArgException}
     * when there is no {@code path} and the ontology has never been saved (no folder to write in).
     */
    public static File resolveSidecarFile(OWLOntologyManager om, OWLOntology active, String path,
            String fileName) {
        if (path != null) {
            File f = new File(path).getAbsoluteFile();
            if (f.isDirectory() || path.endsWith("/") || path.endsWith(File.separator)) {
                return new File(f, fileName);
            }
            if (f.getName().equalsIgnoreCase(fileName)) {
                return f;
            }
            return new File(f, fileName);
        }
        File folder = documentFolder(om, active);
        if (folder == null) {
            throw new ToolArgException("The active ontology has no saved file document, so there is no "
                    + "folder to write " + fileName + " in. Save it first (save_ontology) or pass 'path'.");
        }
        return new File(folder, fileName);
    }

    /**
     * A filesystem-safe stem derived from a competency-question {@code id} (used to name a {@code .rq}
     * file): keeps letters, digits, {@code -} and {@code _}; every other character (including path
     * separators, {@code .} and whitespace) becomes {@code _}. A blank/degenerate result falls back to
     * {@code "cq"} so the file always has a name and can never be a traversal (no {@code ..}, no {@code /}).
     */
    public static String sanitizeId(String id) {
        if (id == null) {
            return "cq";
        }
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' ? c : '_');
        }
        String s = sb.toString().replaceAll("_+", "_");
        // Trim leading/trailing underscores so ids like " x " don't yield "_x_".
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '_') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '_') {
            end--;
        }
        s = s.substring(start, end);
        return s.isEmpty() ? "cq" : s;
    }

    /**
     * True when {@code candidate} resolves inside {@code base} (canonical-path containment), guarding a
     * caller-supplied path from escaping the document folder via {@code ..} or a symlink. Falls back to a
     * conservative {@code false} if either path cannot be canonicalized.
     */
    public static boolean isWithin(File base, File candidate) {
        try {
            String basePath = base.getCanonicalPath() + File.separator;
            String childPath = candidate.getCanonicalFile().getPath() + File.separator;
            return childPath.startsWith(basePath);
        } catch (IOException e) {
            return false;
        }
    }
}
