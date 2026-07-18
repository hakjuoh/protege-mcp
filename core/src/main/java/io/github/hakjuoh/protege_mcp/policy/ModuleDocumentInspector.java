package io.github.hakjuoh.protege_mcp.policy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDatatypeDefinitionAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLNaryIndividualAxiom;
import org.semanticweb.owlapi.model.OWLNaryPropertyAxiom;
import org.semanticweb.owlapi.model.OWLObjectInverseOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLSubAnnotationPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLUnaryPropertyAxiom;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLClassAtom;
import org.semanticweb.owlapi.model.SWRLDataPropertyAtom;
import org.semanticweb.owlapi.model.SWRLDataRangeAtom;
import org.semanticweb.owlapi.model.SWRLDifferentIndividualsAtom;
import org.semanticweb.owlapi.model.SWRLIArgument;
import org.semanticweb.owlapi.model.SWRLIndividualArgument;
import org.semanticweb.owlapi.model.SWRLObjectPropertyAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLSameIndividualAtom;
import org.semanticweb.owlapi.apibinding.OWLManager;

/** Bounded-to-one-document, no-network inspection of a policy-declared module file. */
public final class ModuleDocumentInspector {

    /**
     * LRU of parsed inspections keyed by (document URI, SHA-256 of the document bytes). Policy
     * loads and QC re-inspect the same unchanged module files several times per tool call, and
     * the OWLAPI parse dominates the cost; reading and hashing the bytes each call is cheap.
     * The document URI must be part of the key because it is also the parse base: a document
     * using relative IRIs (no absolute ontology IRI or {@code @base}) resolves them against its
     * own location, so byte-identical files at different paths can legitimately inspect
     * differently. Failed parses are never cached.
     */
    private static final int MAX_CACHED_INSPECTIONS = 128;
    private static final Map<String, Inspection> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHED_INSPECTIONS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Inspection> eldest) {
                    return size() > MAX_CACHED_INSPECTIONS;
                }
            });
    private static final AtomicInteger PARSES = new AtomicInteger();

    private ModuleDocumentInspector() {
    }

    public static Inspection inspect(Path document) throws IOException {
        if (document == null || !Files.isRegularFile(document)) {
            throw new IOException("module is not a regular file: " + document);
        }
        byte[] bytes = Files.readAllBytes(document);
        // The URI below is byte-for-byte the parse base used in parse(), so a cached inspection
        // can never serve another path's relative-IRI resolution for the same content.
        String key = document.toUri() + "\n" + sha256(bytes);
        Inspection cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Inspection inspection = parse(document, bytes);
        String supersededPrefix = document.toUri() + "\n";
        synchronized (CACHE) {
            // A document is only ever re-inspected at its CURRENT bytes, so prior (uri, oldHash)
            // revisions of this same path are dead weight that the plain 128-entry LRU would otherwise
            // retain until enough distinct files pushed them out. Drop them on each new revision, which
            // bounds retention to one revision per document plus the cross-document LRU cap.
            CACHE.keySet().removeIf(existing ->
                    existing.startsWith(supersededPrefix) && !existing.equals(key));
            CACHE.put(key, inspection);
        }
        return inspection;
    }

    /** Visible for tests: proves cache hits skip the OWLAPI parse without timing assertions. */
    static int parseCount() {
        return PARSES.get();
    }

    private static Inspection parse(Path document, byte[] bytes) throws IOException {
        PARSES.incrementAndGet();
        Path placeholder = Files.createTempFile("protege-mcp-module-import-", ".ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            IRI placeholderIri = IRI.create(placeholder.toUri());
            OWLOntologyIRIMapper noNetworkImports = ignored -> placeholderIri;
            manager.getIRIMappers().add(noNetworkImports);
            OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setFollowRedirects(false);
            // Parse the exact bytes that were hashed for the cache key, so the cached result can
            // never describe a different file revision than the one it is keyed by.
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                    new StreamDocumentSource(new ByteArrayInputStream(bytes),
                            IRI.create(document.toUri())),
                    configuration);
            String ontologyIri = ontology.getOntologyID().getOntologyIRI().isPresent()
                    ? ontology.getOntologyID().getOntologyIRI().get().toString() : null;
            Set<String> declared = new TreeSet<>();
            ontology.getAxioms(AxiomType.DECLARATION).forEach(
                    axiom -> declared.add(axiom.getEntity().getIRI().toString()));
            Set<String> defined = new TreeSet<>();
            for (OWLAxiom axiom : ontology.getAxioms()) {
                for (IRI subject : subjectsOf(axiom)) {
                    defined.add(subject.toString());
                }
            }
            return new Inspection(ontologyIri, Collections.unmodifiableSet(declared),
                    Collections.unmodifiableSet(defined));
        } catch (OWLOntologyCreationException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException("could not parse module ontology: " + message, e);
        } finally {
            Files.deleteIfExists(placeholder);
        }
    }

    /**
     * The named terms an axiom DEFINES — constrains or states something about — or an empty
     * collection when the axiom only references terms. The line: an axiom that CONSTRAINS a named
     * entity (a named {@code SubClassOf} subject; every named member of an equivalence,
     * disjointness, {@code DisjointUnion}, {@code SameIndividual} or {@code DifferentIndividuals}
     * axiom; a {@code HasKey} class; a defined datatype; a property equivalence/disjointness/
     * inverse member, chain super-property, domain/range/characteristic subject; an annotation
     * subject; the subject individual of a class or (negative) property assertion) defines it.
     * Merely REFERENCING a foreign term — as the superclass of an owned subject, inside an owned
     * class's expression, as the class or object of an assertion about an owned individual — is
     * legal module practice, and a bare supporting Declaration (which OWLAPI renderers add for
     * undeclared referenced entities) states nothing, so none of those yield a subject here.
     */
    private static Collection<IRI> subjectsOf(OWLAxiom axiom) {
        if (axiom instanceof OWLSubClassOfAxiom subClass) {
            return namedClass(subClass.getSubClass());
        }
        if (axiom instanceof OWLNaryClassAxiom nary) {
            // EquivalentClasses / DisjointClasses constrain every named member exactly as
            // strongly as a SubClassOf axiom constrains its subject.
            List<IRI> subjects = new ArrayList<>();
            for (OWLClassExpression member : nary.getClassExpressions()) {
                subjects.addAll(namedClass(member));
            }
            return subjects;
        }
        if (axiom instanceof OWLDisjointUnionAxiom disjointUnion) {
            List<IRI> subjects = new ArrayList<>();
            subjects.add(disjointUnion.getOWLClass().getIRI());
            for (OWLClassExpression member : disjointUnion.getClassExpressions()) {
                subjects.addAll(namedClass(member));
            }
            return subjects;
        }
        if (axiom instanceof OWLHasKeyAxiom hasKey) {
            return namedClass(hasKey.getClassExpression());
        }
        if (axiom instanceof OWLDatatypeDefinitionAxiom datatypeDefinition) {
            return List.of(datatypeDefinition.getDatatype().getIRI());
        }
        if (axiom instanceof OWLSubPropertyAxiom<?> subProperty) {
            return namedProperty(subProperty.getSubProperty());
        }
        if (axiom instanceof OWLSubPropertyChainOfAxiom chain) {
            // The chain re-axiomatises its SUPER property; the chain members are references.
            return namedProperty(chain.getSuperProperty());
        }
        if (axiom instanceof OWLSubAnnotationPropertyOfAxiom subAnnotation) {
            return List.of(subAnnotation.getSubProperty().getIRI());
        }
        if (axiom instanceof OWLNaryPropertyAxiom<?> nary) {
            // Equivalent/disjoint object|data properties and InverseObjectProperties constrain
            // every named member.
            List<IRI> subjects = new ArrayList<>();
            for (Object property : nary.getProperties()) {
                subjects.addAll(namedProperty(property));
            }
            return subjects;
        }
        if (axiom instanceof OWLUnaryPropertyAxiom<?> unary) {
            // Object/data property domain, range and characteristic axioms.
            return namedProperty(unary.getProperty());
        }
        if (axiom instanceof OWLAnnotationPropertyDomainAxiom domain) {
            return List.of(domain.getProperty().getIRI());
        }
        if (axiom instanceof OWLAnnotationPropertyRangeAxiom range) {
            return List.of(range.getProperty().getIRI());
        }
        if (axiom instanceof OWLAnnotationAssertionAxiom assertion) {
            return assertion.getSubject() instanceof IRI iri ? List.of(iri) : List.of();
        }
        if (axiom instanceof OWLNaryIndividualAxiom nary) {
            // SameIndividual merges — and DifferentIndividuals constrains — every named
            // individual's identity.
            List<IRI> subjects = new ArrayList<>();
            for (OWLIndividual individual : nary.getIndividuals()) {
                if (individual instanceof OWLEntity named) {
                    subjects.add(named.getIRI());
                }
            }
            return subjects;
        }
        if (axiom instanceof OWLClassAssertionAxiom assertion) {
            // The asserted class merely describes the subject individual: referencing.
            return assertion.getIndividual() instanceof OWLEntity named
                    ? List.of(named.getIRI()) : List.of();
        }
        if (axiom instanceof OWLPropertyAssertionAxiom<?, ?> assertion) {
            // Object/data property assertions and their negative variants attach a fact to the
            // SUBJECT individual; the object position is a reference.
            return assertion.getSubject() instanceof OWLEntity named
                    ? List.of(named.getIRI()) : List.of();
        }
        if (axiom instanceof SWRLRule rule) {
            // A rule whose HEAD asserts a foreign class/property membership re-axiomatises that
            // term (the body is the antecedent — references only), just like a SubClassOf subject.
            // The head also re-axiomatises the NAMED individuals whose facts/identity it constrains.
            // Follow the direct-axiom semantics above: only SUBJECT-position individuals are defined —
            // a property-atom OBJECT is a reference (like OWLPropertyAssertionAxiom), while
            // Same/DifferentIndividual atoms constrain BOTH members' identity.
            List<IRI> subjects = new ArrayList<>();
            for (SWRLAtom atom : rule.getHead()) {
                if (atom instanceof SWRLClassAtom classAtom) {
                    subjects.addAll(namedClass(classAtom.getPredicate()));
                    addNamedIndividual(subjects, classAtom.getArgument());
                } else if (atom instanceof SWRLObjectPropertyAtom objectAtom) {
                    subjects.addAll(namedProperty(objectAtom.getPredicate()));
                    addNamedIndividual(subjects, objectAtom.getFirstArgument());
                } else if (atom instanceof SWRLDataPropertyAtom dataAtom) {
                    subjects.addAll(namedProperty(dataAtom.getPredicate()));
                    addNamedIndividual(subjects, dataAtom.getFirstArgument());
                } else if (atom instanceof SWRLDataRangeAtom dataRangeAtom) {
                    // A head data-range atom applying a foreign NAMED datatype re-axiomatises it, just
                    // like a ClassAtom over a foreign class; built-in datatypes are owned by no module.
                    var range = dataRangeAtom.getPredicate();
                    if (range.isDatatype() && !range.asOWLDatatype().isBuiltIn()) {
                        subjects.add(range.asOWLDatatype().getIRI());
                    }
                } else if (atom instanceof SWRLSameIndividualAtom sameAtom) {
                    addNamedIndividual(subjects, sameAtom.getFirstArgument());
                    addNamedIndividual(subjects, sameAtom.getSecondArgument());
                } else if (atom instanceof SWRLDifferentIndividualsAtom differentAtom) {
                    addNamedIndividual(subjects, differentAtom.getFirstArgument());
                    addNamedIndividual(subjects, differentAtom.getSecondArgument());
                }
            }
            return subjects;
        }
        return List.of();
    }

    /** Add the named individual an {@code SWRLIArgument} carries, ignoring variables and anon nodes. */
    private static void addNamedIndividual(List<IRI> out, SWRLIArgument argument) {
        if (argument instanceof SWRLIndividualArgument individualArgument
                && individualArgument.getIndividual() instanceof OWLEntity named) {
            out.add(named.getIRI());
        }
    }

    private static Collection<IRI> namedClass(OWLClassExpression expression) {
        return expression.isAnonymous() ? List.of() : List.of(expression.asOWLClass().getIRI());
    }

    /**
     * The named property an expression constrains, unwrapping {@code ObjectInverseOf} — inverting a
     * foreign property re-axiomatises it exactly as strongly as naming it directly, and the inverse
     * spelling is the natural Turtle authoring form (e.g. {@code [ owl:inverseOf foreign:p ]}).
     */
    private static Collection<IRI> namedProperty(Object expression) {
        if (expression instanceof OWLObjectInverseOf inverse) {
            return namedProperty(inverse.getInverse());
        }
        return expression instanceof OWLEntity named ? List.of(named.getIRI()) : List.of();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >>> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param ontologyIri the document's declared ontology IRI, or null when anonymous
     * @param declaredEntityIris every explicitly declared entity (includes the bare supporting
     *        declarations OWLAPI renderers emit for referenced foreign entities)
     * @param definedEntityIris entities that at least one axiom in the document defines or
     *        constrains (see {@link #subjectsOf}); declared-only entities are
     *        {@code declaredEntityIris} minus this set
     */
    public record Inspection(String ontologyIri, Set<String> declaredEntityIris,
            Set<String> definedEntityIris) { }
}
