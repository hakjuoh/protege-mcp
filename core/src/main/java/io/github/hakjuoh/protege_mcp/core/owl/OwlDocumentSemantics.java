package io.github.hakjuoh.protege_mcp.core.owl;

import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.Set;

import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.util.DefaultPrefixManager;
import org.semanticweb.owlapi.util.OWLEntityCollector;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/** Shared artifact-boundary semantics for fingerprints, verified saves, and semantic diffs. */
public final class OwlDocumentSemantics {

    private OwlDocumentSemantics() {
    }

    /**
     * Axioms with serializer-materialized declarations normalized symmetrically. Serializers are free
     * to emit frames/declarations for every entity used by a document, including built-in annotation
     * properties and datatypes, so every document-signature entity receives the same implicit,
     * unannotated declaration on both sides. Explicit annotated declarations remain distinct.
     */
    public static Set<OWLAxiom> normalizedAxioms(OWLOntology ontology) {
        Set<OWLAxiom> normalized = new LinkedHashSet<>(ontology.getAxioms());
        Set<OWLEntity> entities = new LinkedHashSet<>();
        OWLEntityCollector collector = new OWLEntityCollector(entities);
        ontology.getAxioms().forEach(axiom -> axiom.accept(collector));
        ontology.getAnnotations().forEach(annotation -> annotation.accept(collector));
        // OWLAPI 4 represents an RDF 1.1 simple literal as rdf:PlainLiteral when read from some
        // syntaxes and xsd:string when read from others. The literal objects compare as equivalent,
        // but serializer-materialized datatype declarations do not. Close that equivalence class on
        // both sides before generating declarations.
        var dataFactory = ontology.getOWLOntologyManager().getOWLDataFactory();
        var plainLiteral = dataFactory.getRDFPlainLiteral();
        var xsdString = dataFactory.getOWLDatatype(OWL2Datatype.XSD_STRING.getIRI());
        if (entities.contains(plainLiteral) || entities.contains(xsdString)) {
            entities.add(plainLiteral);
            entities.add(xsdString);
        }
        entities.stream()
                .map(dataFactory::getOWLDeclarationAxiom)
                .forEach(normalized::add);
        return normalized;
    }

    /**
     * Hash-based copy of ontology annotations. OWLAPI 4 returns a {@code TreeSet} whose comparator is
     * inconsistent with equality for RDF 1.1-equivalent plain and {@code xsd:string} literals; copying
     * avoids asymmetric/false-negative {@code TreeSet.equals} results.
     */
    public static Set<OWLAnnotation> normalizedAnnotations(OWLOntology ontology) {
        return new LinkedHashSet<>(ontology.getAnnotations());
    }

    /** True when a parser-local blank-node identifier participates in axioms or ontology annotations. */
    public static boolean hasAnonymousIndividuals(OWLOntology ontology) {
        if (!ontology.getAnonymousIndividuals().isEmpty()) {
            return true;
        }
        return ontology.getAnnotations().stream()
                .anyMatch(annotation -> !annotation.getAnonymousIndividuals().isEmpty());
    }

    /** Deterministic, prefix-free Functional Syntax rendering of one OWL object. */
    public static String render(OWLOntology context, OWLObject object) {
        StringWriter writer = new StringWriter();
        FunctionalSyntaxDocumentFormat format = new FunctionalSyntaxDocumentFormat();
        format.clear();
        FunctionalSyntaxObjectRenderer renderer = new FunctionalSyntaxObjectRenderer(context, format, writer);
        DefaultPrefixManager prefixes = new DefaultPrefixManager();
        prefixes.clear();
        renderer.setPrefixManager(prefixes);
        renderer.setAddMissingDeclarations(false);
        object.accept(renderer);
        return writer.toString().replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
