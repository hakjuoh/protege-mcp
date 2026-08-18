package io.github.hakjuoh.protege_mcp.tools;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.entity.OWLEntityCreationException;
import org.protege.editor.owl.model.entity.OWLEntityCreationSet;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Entity creation, declaration and label policy shared by write tools. */
final class EntityWriteService {

    private EntityWriteService() {}

    /**
     * Queue a Declaration for each minted entity, matching how {@link #createEntity} declares its
     * primary entity. Entities first introduced as an operand side effect — an annotation property
     * referenced by a definition/annotation, an individual named in a class assertion, a class
     * named in a subclass axiom — otherwise stay used-but-undeclared, which leaves the ontology
     * short of OWL 2 DL (undeclared annotation properties are a profile violation) and makes a
     * save/reload round-trip non-identical (the serializer re-adds the type triples as
     * declarations). Skips built-ins and any declaration already present, and appends to the SAME
     * change list so the declarations ride along in one undo unit.
     */
    static void declareMinted(
            OWLDataFactory df,
            OWLOntology ont,
            Set<OWLEntity> minted,
            List<OWLOntologyChange> out) {
        if (minted == null) {
            return;
        }
        for (OWLEntity e : minted) {
            if (e.isBuiltIn()) {
                continue;
            }
            OWLAxiom decl = df.getOWLDeclarationAxiom(e);
            if (!ont.containsAxiom(decl)) {
                out.add(new AddAxiom(ont, decl));
            }
        }
    }

    /** A human label for the write summary: 'name', else the 'iri' local part, else "entity". */
    static String summaryName(Map<String, Object> a) {
        String name = Tools.optString(a, "name");
        if (name != null) {
            return name;
        }
        String iri = Tools.optString(a, "iri");
        return iri != null ? localName(iri) : "entity";
    }

    /**
     * OWL 2 DL requires a Declaration for every non-built-in annotation property used in an
     * annotation. The create_* and annotation write paths inject annotation properties (a
     * definition property such as skos:definition, dcterms:*, a project av:* property) that are
     * commonly used-but-never-declared across the imports closure — e.g. an imported ontology uses
     * skos:definition without declaring it — so {@link PreviewTools#newEntities} / {@link
     * #declareMinted} (which treat an entity already present in the closure signature as "known")
     * never declare them and the active ontology silently leaves OWL 2 DL. Declare, in the active
     * ontology, each annotation property referenced by the AddAxioms in {@code changes} that is not
     * OWL-built-in and that NO ontology in the imports closure declares. Keyed on isDeclared (not
     * containsEntityInSignature), so a used-but-undeclared upstream annotation property is still
     * declared locally. Appends to the SAME change list (one undo unit).
     */
    static void declareUsedAnnotationProperties(
            OWLDataFactory df, OWLOntology ont, List<OWLOntologyChange> changes) {
        Set<OWLAnnotationProperty> used = new LinkedHashSet<>();
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom) {
                used.addAll(((AddAxiom) ch).getAxiom().getAnnotationPropertiesInSignature());
            }
        }
        declareAnnotationProperties(df, ont, used, changes);
    }

    /**
     * Declare, in {@code ont}, each annotation property in {@code used} that is not OWL-built-in
     * and is not declared anywhere in the imports closure (appending to {@code changes}). Shared by
     * the axiom-path {@link #declareUsedAnnotationProperties} and the ontology-annotation write
     * path (whose property is not carried by any axiom).
     */
    static void declareAnnotationProperties(
            OWLDataFactory df,
            OWLOntology ont,
            Set<OWLAnnotationProperty> used,
            List<OWLOntologyChange> changes) {
        if (used.isEmpty()) {
            return;
        }
        Set<OWLOntology> closure = ont.getImportsClosure();
        for (OWLAnnotationProperty ap : used) {
            if (ap.isBuiltIn() || isDeclaredInClosure(closure, ap)) {
                continue;
            }
            OWLAxiom decl = df.getOWLDeclarationAxiom(ap);
            if (!ont.containsAxiom(decl) && !isAlreadyAdded(changes, decl)) {
                changes.add(new AddAxiom(ont, decl));
            }
        }
    }

    private static boolean isDeclaredInClosure(Set<OWLOntology> closure, OWLEntity e) {
        for (OWLOntology o : closure) {
            if (o.isDeclared(e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAlreadyAdded(List<OWLOntologyChange> changes, OWLAxiom decl) {
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom && ((AddAxiom) ch).getAxiom().equals(decl)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience over {@link #declareMinted}: compute the entities the AddAxioms in {@code
     * changes} introduce into the closure (minus {@code exclude}, typically an entity already
     * declared by its own create step) and queue their declarations into the SAME {@code changes}
     * list. Used by the direct-apply curation macros (deprecate_entity, move_class) that don't go
     * through applyCuration.
     */
    static void declareMintedFromChanges(
            OWLModelManager mm,
            OWLOntology ont,
            List<OWLOntologyChange> changes,
            OWLEntity exclude) {
        Set<OWLOntology> closure = ont.getImportsClosure();
        Set<OWLEntity> minted = new LinkedHashSet<>();
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom) {
                minted.addAll(PreviewTools.newEntities(closure, ((AddAxiom) ch).getAxiom()));
            }
        }
        if (exclude != null) {
            minted.remove(exclude);
        }
        declareMinted(mm.getOWLDataFactory(), ont, minted, changes);
        declareUsedAnnotationProperties(mm.getOWLDataFactory(), ont, changes);
    }

    /**
     * Create an entity of {@code type} from the create_* arguments {@code a} (name, iri, namespace,
     * label, label_lang, no_label). With an explicit {@code iri} (or {@code namespace} + name) the
     * entity is declared at that exact IRI; otherwise Protégé's entity factory mints the IRI from
     * {@code name}. Unless {@code no_label}, an rdfs:label ('label' or 'name', tagged with {@code
     * label_lang}) is added. Accumulates the resulting changes into {@code changes}.
     */
    static OWLEntity createEntity(
            OWLModelManager mm,
            String type,
            Map<String, Object> a,
            List<OWLOntologyChange> changes) {
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLOntology ont = mm.getActiveOntology();
        String t = type.toLowerCase();
        String iri = Tools.optString(a, "iri");
        String namespace = Tools.optString(a, "namespace");
        String name = Tools.optString(a, "name");
        if (name == null) {
            // 'name' is optional when a full 'iri' pins the entity: derive its local part as the
            // default label so create_* need not repeat the fragment. A 'namespace' still needs a
            // 'name' (it supplies only the local part), and with neither we cannot mint an IRI.
            if (iri != null) {
                name = localName(iri);
            } else {
                throw new ToolArgException(
                        "Provide 'name' (the IRI local part and default label), "
                                + "or a full 'iri'.");
            }
        }
        if (iri == null && namespace != null) {
            iri = joinNamespace(namespace, name);
        }
        boolean noLabel = Tools.optBool(a, "no_label", false);
        String labelText = Tools.optString(a, "label");
        String labelLang = Tools.optString(a, "label_lang");
        if (iri != null) {
            OWLEntity e = entityAtIri(df, t, IRI.create(iri));
            changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(e)));
            if (!noLabel) {
                changes.add(
                        new AddAxiom(
                                ont,
                                df.getOWLAnnotationAssertionAxiom(
                                        df.getRDFSLabel(),
                                        e.getIRI(),
                                        label(
                                                df,
                                                labelText != null ? labelText : name,
                                                labelLang))));
            }
            return e;
        }
        try {
            OWLEntityCreationSet<? extends OWLEntity> set = createViaFactory(mm, t, name);
            OWLEntity e = set.getOWLEntity();
            boolean customLabel = labelText != null || labelLang != null;
            for (OWLOntologyChange change : set.getOntologyChanges()) {
                if ((noLabel || customLabel) && isLabelChangeFor(change, e)) {
                    continue;
                }
                changes.add(change);
            }
            if (!noLabel && customLabel) {
                changes.add(
                        new AddAxiom(
                                ont,
                                df.getOWLAnnotationAssertionAxiom(
                                        df.getRDFSLabel(),
                                        e.getIRI(),
                                        label(
                                                df,
                                                labelText != null ? labelText : name,
                                                labelLang))));
            }
            return e;
        } catch (OWLEntityCreationException e) {
            throw new ToolArgException("Could not create " + t + ": " + e.getMessage());
        }
    }

    /** True for Protégé entity-factory auto-label changes for the entity being created. */
    static boolean isLabelChangeFor(OWLOntologyChange change, OWLEntity e) {
        if (!(change instanceof AddAxiom)) {
            return false;
        }
        OWLAxiom ax = ((AddAxiom) change).getAxiom();
        if (!(ax instanceof OWLAnnotationAssertionAxiom)) {
            return false;
        }
        OWLAnnotationAssertionAxiom ann = (OWLAnnotationAssertionAxiom) ax;
        return ann.getProperty().isLabel() && ann.getSubject().equals(e.getIRI());
    }

    static OWLLiteral label(OWLDataFactory df, String text, String lang) {
        return lang != null ? df.getOWLLiteral(text, lang) : df.getOWLLiteral(text);
    }

    /**
     * Join a namespace and a local name (insert '/' unless the namespace already ends in /, # or
     * :).
     */
    static String joinNamespace(String namespace, String name) {
        String local = name.trim().replace(" ", "");
        if (namespace.isEmpty()) {
            return local;
        }
        char last = namespace.charAt(namespace.length() - 1);
        return (last == '/' || last == '#' || last == ':')
                ? namespace + local
                : namespace + "/" + local;
    }

    /**
     * The local part of an IRI, for use as a default name/label: the fragment after '#', else the
     * segment after the last '/', else (for an opaque IRI such as {@code urn:example:Dog}) the part
     * after the last ':'. A trailing separator is dropped first so a namespace IRI ({@code
     * http://ex/vocab#}) still yields its last segment rather than the whole IRI; falls back to the
     * full IRI only when nothing else remains.
     */
    static String localName(String iri) {
        String s = iri;
        while (s.endsWith("#") || s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int cut = Math.max(s.lastIndexOf('#'), s.lastIndexOf('/'));
        if (cut < 0) {
            cut = s.lastIndexOf(':');
        }
        String local = (cut >= 0 && cut < s.length() - 1) ? s.substring(cut + 1) : s;
        return local.isEmpty() ? iri : local;
    }

    static OWLEntity entityAtIri(OWLDataFactory df, String type, IRI iri) {
        switch (type) {
            case "class":
                return df.getOWLClass(iri);
            case "object_property":
                return df.getOWLObjectProperty(iri);
            case "data_property":
                return df.getOWLDataProperty(iri);
            case "annotation_property":
                return df.getOWLAnnotationProperty(iri);
            case "individual":
                return df.getOWLNamedIndividual(iri);
            case "datatype":
                return df.getOWLDatatype(iri);
            default:
                throw new ToolArgException("Unknown entity_type '" + type + "'.");
        }
    }

    private static OWLEntityCreationSet<? extends OWLEntity> createViaFactory(
            OWLModelManager mm, String type, String name) throws OWLEntityCreationException {
        org.protege.editor.owl.model.entity.OWLEntityFactory f = mm.getOWLEntityFactory();
        switch (type) {
            case "class":
                return f.createOWLClass(name, null);
            case "object_property":
                return f.createOWLObjectProperty(name, null);
            case "data_property":
                return f.createOWLDataProperty(name, null);
            case "annotation_property":
                return f.createOWLAnnotationProperty(name, null);
            case "individual":
                return f.createOWLIndividual(name, null);
            case "datatype":
                return f.createOWLDatatype(name, null);
            default:
                throw new ToolArgException(
                        "Unknown entity_type '"
                                + type
                                + "'. Use class, object_property, data_property,"
                                + " annotation_property, individual or datatype.");
        }
    }
}
