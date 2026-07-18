package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * analyze_change_impact contract over the headless adapter: one input form (a claimed-and-released
 * cached change set, or an asserted pair diff), syntactic-only labelling, exact counts with bounded
 * samples, fail-closed policy-driven categories, and exact present-but-unavailable shapes for the
 * not-yet-shipped categories.
 */
class ImpactToolsTest {

    private static final String ONTOLOGY_IRI = "https://example.org/impact";

    // ------------------------------------------------------------------ change-set input form

    @Test
    void changeSetPathAnalyzesTheStoredDeltaEndToEnd(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        // Bar subClassOf Foo: a workspace axiom referencing the to-be-affected Foo.
        OWLDataFactory df = df(ontology);
        OWLClass bar = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Bar"));
        addAxiom(ontology, df.getOWLDeclarationAxiom(bar));
        addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), bar.getIRI(),
                df.getOWLLiteral("Bar")));
        addAxiom(ontology, df.getOWLSubClassOfAxiom(bar,
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"))));
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        String id = (String) preview.get("change_set_id");

        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null,
                Map.of("change_set_id", id)));

        assertEquals("syntactic", result.get("analysis"));
        assertTrue(String.valueOf(result.get("note")).contains("semantic_diff mode=inferred|both"),
                result::toString);
        assertEquals(id, result.get("change_set_id"));
        assertEquals(preview.get("base_revision"), result.get("base_revision"),
                "the analyzed entry's stored revision envelope must be echoed");
        assertEquals(Map.of("added_axioms", 1, "removed_axioms", 0), result.get("delta"));

        Map<String, Object> directly = section(result, "directly_affected");
        assertEquals(2, directly.get("count"), directly::toString);
        List<Map<String, Object>> items = rows(directly);
        assertEquals(2, items.size());
        Map<String, Map<String, Object>> byIri = new LinkedHashMap<>();
        items.forEach(row -> byIri.put((String) row.get("iri"), row));
        assertEquals(1, byIri.get(ONTOLOGY_IRI + "#Foo").get("added"));
        assertEquals(0, byIri.get(ONTOLOGY_IRI + "#Foo").get("removed"));
        assertEquals(1, byIri.get(ONTOLOGY_IRI + "#Animal").get("added"));

        // Referencing axioms: Bar⊑Foo plus the declarations/labels on Foo and Animal — never the
        // delta axiom itself (not asserted in the workspace here anyway).
        Map<String, Object> referencing = section(result, "referencing_axioms");
        assertTrue((int) referencing.get("count") >= 3, referencing::toString);
        assertTrue(renderings(referencing).stream().anyMatch(r -> r.contains("#Bar")
                        && r.contains("#Foo")),
                "Bar SubClassOf Foo must be reported as a referencing axiom: " + referencing);

        // Downstream: Bar co-occurs with Foo at depth 1; nothing deeper exists, so no truncation.
        Map<String, Object> downstream = section(result, "downstream_terms");
        assertEquals("syntactic", downstream.get("analysis"));
        assertEquals(ImpactTools.DOWNSTREAM_DEPTH_CAP, downstream.get("depth_cap"));
        assertEquals(ImpactTools.DOWNSTREAM_SIZE_CAP, downstream.get("size_cap"));
        assertEquals(1, downstream.get("count"), downstream::toString);
        assertEquals(ONTOLOGY_IRI + "#Bar", rows(downstream).get(0).get("iri"));
        assertEquals(1, rows(downstream).get(0).get("depth"));
        assertNull(downstream.get("search_truncated"), downstream::toString);

        assertEquals(0, section(result, "foreign_reaxiomatization").get("count"));
        assertEquals(0, section(result, "deprecated_terms_in_use").get("count"));

        // Policy-driven categories are present-but-unavailable without policy_path.
        Map<String, Object> modules = section(directly, "modules");
        assertEquals(Boolean.FALSE, modules.get("available"));
        assertTrue(String.valueOf(modules.get("reason")).contains("policy_path"));
        Map<String, Object> validation = section(result, "validation_references");
        assertEquals(Boolean.FALSE, validation.get("available"));
        assertTrue(String.valueOf(validation.get("reason")).contains("policy_path"));

        // The not-yet-shipped categories carry their exact documented shapes.
        assertEquals(Map.of("available", false,
                        "reason", "policy v1 does not yet declare public API terms"),
                result.get("public_api_terms"));
        assertEquals(Map.of("available", false,
                        "reason", "mapping management ships with the M6 milestone"),
                result.get("external_mappings"));

        // Analysis is read-only: the entry survives and stays claimable/discardable.
        assertTrue(ctx.changeSets().discard(id),
                "the analyzed entry must remain cached after the analysis");
    }

    @Test
    void unknownAndInFlightChangeSetsAreRefusedStructurally(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> unknown = structured(ImpactTools.analyze(ctx, null,
                Map.of("change_set_id", "nope")));
        assertEquals(Boolean.FALSE, unknown.get("analyzed"));
        assertEquals("unknown_change_set", unknown.get("error_code"));

        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        String id = (String) preview.get("change_set_id");
        ChangeSetStore.Lookup claimed = ctx.changeSets().claim(id);
        assertNotNull(claimed.entry());
        try {
            Map<String, Object> inFlight = structured(ImpactTools.analyze(ctx, null,
                    Map.of("change_set_id", id)));
            assertEquals(Boolean.FALSE, inFlight.get("analyzed"));
            assertEquals("change_set_in_progress", inFlight.get("error_code"));
        } finally {
            ctx.changeSets().release(claimed.entry());
        }
    }

    @Test
    void exactlyOneInputFormIsRequired(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        CallToolResult both = ImpactTools.analyze(ctx, null,
                Map.of("change_set_id", "x", "right", "y"));
        assertTrue(Boolean.TRUE.equals(both.isError()), "change_set_id plus a pair must be refused");

        CallToolResult neither = ImpactTools.analyze(ctx, null, Map.of());
        assertTrue(Boolean.TRUE.equals(neither.isError()), "no input form must be refused");

        CallToolResult leftOnly = ImpactTools.analyze(ctx, null, Map.of("left", ONTOLOGY_IRI));
        assertTrue(Boolean.TRUE.equals(leftOnly.isError()), "left without a right side is refused");

        CallToolResult doubled = ImpactTools.analyze(ctx, null,
                Map.of("right", "y", "right_document", "z"));
        assertTrue(Boolean.TRUE.equals(doubled.isError()),
                "right and right_document together must be refused");
    }

    // ------------------------------------------------------------------ pair input form

    @Test
    void pairPathComputesTheAssertedDeltaAndExcludesItFromReferencingAxioms(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = df(ontology);
        OWLClass foo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        OWLClass animal = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"));
        // The axiom the right side will REMOVE is asserted in the workspace.
        addAxiom(ontology, df.getOWLSubClassOfAxiom(foo, animal));
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        // Right document: same ontology without Foo⊑Animal but with Foo⊑Plant.
        Path rightDoc = temp.resolve("right.ttl");
        OWLOntologyManager rightManager = OWLManager.createOWLOntologyManager();
        OWLOntology right = rightManager.createOntology(IRI.create(ONTOLOGY_IRI));
        OWLDataFactory rdf = rightManager.getOWLDataFactory();
        OWLClass plant = rdf.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Plant"));
        for (OWLAxiom ax : ontology.getAxioms()) {
            if (!ax.equals(df.getOWLSubClassOfAxiom(foo, animal))) {
                rightManager.addAxiom(right, ax);
            }
        }
        rightManager.addAxiom(right, rdf.getOWLDeclarationAxiom(plant));
        rightManager.addAxiom(right, rdf.getOWLSubClassOfAxiom(foo, plant));
        rightManager.saveOntology(right, new TurtleDocumentFormat(),
                IRI.create(rightDoc.toUri()));

        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null,
                Map.of("right_document", rightDoc.toString())));

        assertEquals("syntactic", result.get("analysis"));
        assertEquals(ONTOLOGY_IRI, result.get("left"));
        assertTrue(String.valueOf(result.get("right")).endsWith("right.ttl"), result::toString);
        assertEquals(List.of(), result.get("right_document_unresolved_imports"));
        assertEquals(Map.of("added_axioms", 2, "removed_axioms", 1), result.get("delta"));

        Map<String, Object> directly = section(result, "directly_affected");
        List<String> iris = rows(directly).stream().map(r -> (String) r.get("iri")).toList();
        assertTrue(iris.containsAll(List.of(ONTOLOGY_IRI + "#Foo", ONTOLOGY_IRI + "#Animal",
                ONTOLOGY_IRI + "#Plant")), directly::toString);

        // Foo⊑Animal is part of the delta AND asserted in the workspace: it must be excluded.
        Map<String, Object> referencing = section(result, "referencing_axioms");
        assertTrue((int) referencing.get("count") > 0);
        assertFalse(renderings(referencing).stream().anyMatch(r -> r.contains("#Foo")
                        && r.contains("#Animal") && r.contains("SubClassOf")),
                "the delta axiom must never be listed as a referencing axiom: " + referencing);
    }

    @Test
    void downstreamSweepDisclosesItsDepthCapTruncation(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = df(ontology);
        // Chain A2⊑A1 … A6⊑A5: from affected A1 the sweep reaches A2..A4 (depth cap 3); the
        // probe expansion then finds A5, so the truncation is disclosed, never guessed.
        OWLClass[] chain = new OWLClass[7];
        for (int i = 1; i <= 6; i++) {
            chain[i] = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#A" + i));
            addAxiom(ontology, df.getOWLDeclarationAxiom(chain[i]));
        }
        for (int i = 1; i <= 5; i++) {
            addAxiom(ontology, df.getOWLSubClassOfAxiom(chain[i + 1], chain[i]));
        }
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Path rightDoc = temp.resolve("right.ttl");
        OWLOntologyManager rightManager = OWLManager.createOWLOntologyManager();
        OWLOntology right = rightManager.createOntology(IRI.create(ONTOLOGY_IRI));
        OWLDataFactory rdf = rightManager.getOWLDataFactory();
        for (OWLAxiom ax : ontology.getAxioms()) {
            rightManager.addAxiom(right, ax);
        }
        OWLClass root = rdf.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Root"));
        rightManager.addAxiom(right, rdf.getOWLDeclarationAxiom(root));
        rightManager.addAxiom(right, rdf.getOWLSubClassOfAxiom(
                rdf.getOWLClass(IRI.create(ONTOLOGY_IRI + "#A1")), root));
        rightManager.saveOntology(right, new TurtleDocumentFormat(),
                IRI.create(rightDoc.toUri()));

        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null,
                Map.of("right_document", rightDoc.toString())));

        Map<String, Object> downstream = section(result, "downstream_terms");
        assertEquals(3, downstream.get("count"), downstream::toString);
        List<Map<String, Object>> items = rows(downstream);
        assertEquals(List.of(ONTOLOGY_IRI + "#A2", ONTOLOGY_IRI + "#A3", ONTOLOGY_IRI + "#A4"),
                items.stream().map(r -> r.get("iri")).toList());
        assertEquals(List.of(1, 2, 3), items.stream().map(r -> r.get("depth")).toList());
        assertEquals(Boolean.TRUE, downstream.get("search_truncated"),
                "terms beyond the depth cap exist, so the sweep must disclose truncation");
        assertTrue(String.valueOf(downstream.get("search_note")).contains("cap"));
    }

    @Test
    void deprecatedTermsReferencedByTheDeltaAreDetected(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = df(ontology);
        OWLClass old = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Old"));
        OWLClass notReally = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#NotReally"));
        addAxiom(ontology, df.getOWLDeclarationAxiom(old));
        addAxiom(ontology, df.getOWLDeclarationAxiom(notReally));
        addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getOWLDeprecated(), old.getIRI(),
                df.getOWLLiteral(true)));
        addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getOWLDeprecated(),
                notReally.getIRI(), df.getOWLLiteral(false)));
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Path rightDoc = temp.resolve("right.ttl");
        OWLOntologyManager rightManager = OWLManager.createOWLOntologyManager();
        OWLOntology right = rightManager.createOntology(IRI.create(ONTOLOGY_IRI));
        OWLDataFactory rdf = rightManager.getOWLDataFactory();
        for (OWLAxiom ax : ontology.getAxioms()) {
            rightManager.addAxiom(right, ax);
        }
        OWLClass foo = rdf.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        rightManager.addAxiom(right, rdf.getOWLSubClassOfAxiom(foo, old));
        rightManager.addAxiom(right, rdf.getOWLSubClassOfAxiom(foo,
                rdf.getOWLClass(notReally.getIRI())));
        rightManager.saveOntology(right, new TurtleDocumentFormat(),
                IRI.create(rightDoc.toUri()));

        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null,
                Map.of("right_document", rightDoc.toString())));

        Map<String, Object> deprecated = section(result, "deprecated_terms_in_use");
        assertEquals(1, deprecated.get("count"), deprecated::toString);
        assertEquals(ONTOLOGY_IRI + "#Old", rows(deprecated).get(0).get("iri"),
                "owl:deprecated=false must not count as deprecated");
    }

    @Test
    void foreignReaxiomatizationFlagsUpstreamSubjectsOnly(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = df(ontology);
        String upstreamIri = "https://example.org/upstream";
        OWLClass ext = df.getOWLClass(IRI.create(upstreamIri + "#Ext"));
        OWLOntology upstream = manager.createOntology(IRI.create(upstreamIri));
        manager.addAxiom(upstream, df.getOWLDeclarationAxiom(ext));
        manager.applyChange(new AddImport(ontology,
                df.getOWLImportsDeclaration(IRI.create(upstreamIri))));
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        // The delta re-axiomatizes the imported Ext (subject position) and also references it in
        // the object position of a locally-owned axiom — only the former is a layering violation.
        Path rightDoc = temp.resolve("right.ttl");
        OWLOntologyManager rightManager = OWLManager.createOWLOntologyManager();
        OWLOntology right = rightManager.createOntology(IRI.create(ONTOLOGY_IRI));
        OWLDataFactory rdf = rightManager.getOWLDataFactory();
        for (OWLAxiom ax : ontology.getAxioms()) {
            rightManager.addAxiom(right, ax);
        }
        OWLClass local = rdf.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        rightManager.addAxiom(right, rdf.getOWLSubClassOfAxiom(rdf.getOWLClass(ext.getIRI()),
                local));
        rightManager.addAxiom(right, rdf.getOWLSubClassOfAxiom(local,
                rdf.getOWLClass(ext.getIRI())));
        rightManager.saveOntology(right, new TurtleDocumentFormat(),
                IRI.create(rightDoc.toUri()));

        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null,
                Map.of("right_document", rightDoc.toString())));

        Map<String, Object> foreign = section(result, "foreign_reaxiomatization");
        assertEquals(1, foreign.get("count"), foreign::toString);
        Map<String, Object> row = rows(foreign).get(0);
        assertEquals("add", row.get("operation"));
        assertEquals(upstreamIri + "#Ext", row.get("subject"));
        assertNotNull(row.get("rendering"));
    }

    @Test
    void annotationValueReferencesFeedReferencingAxiomsAndDeprecatedTerms(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = df(ontology);
        // Y is deprecated and annotates X in the VALUE position (rdfs:seeAlso Y -> X). X itself is
        // never declared: it exists only as an annotation value, invisible to axiom signatures.
        OWLClass y = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Y"));
        IRI x = IRI.create(ONTOLOGY_IRI + "#X");
        addAxiom(ontology, df.getOWLDeclarationAxiom(y));
        addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getOWLDeprecated(), y.getIRI(),
                df.getOWLLiteral(true)));
        addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSSeeAlso(), y.getIRI(), x));
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        // The delta affects X purely as an annotation VALUE, through a DIFFERENT property than the
        // workspace's seeAlso — so the seeAlso axiom is reachable only via the value-position scan.
        Path rightDoc = temp.resolve("right.ttl");
        OWLOntologyManager rightManager = OWLManager.createOWLOntologyManager();
        OWLOntology right = rightManager.createOntology(IRI.create(ONTOLOGY_IRI));
        OWLDataFactory rdf = rightManager.getOWLDataFactory();
        for (OWLAxiom ax : ontology.getAxioms()) {
            rightManager.addAxiom(right, ax);
        }
        rightManager.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSIsDefinedBy(),
                IRI.create(ONTOLOGY_IRI + "#Foo"), x));
        rightManager.saveOntology(right, new TurtleDocumentFormat(),
                IRI.create(rightDoc.toUri()));

        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null,
                Map.of("right_document", rightDoc.toString())));

        List<String> affected = rows(section(result, "directly_affected")).stream()
                .map(r -> (String) r.get("iri")).toList();
        assertTrue(affected.contains(ONTOLOGY_IRI + "#X"),
                "the delta's annotation VALUE must be directly affected: " + affected);
        Map<String, Object> referencing = section(result, "referencing_axioms");
        assertTrue(renderings(referencing).stream().anyMatch(r -> r.contains("#Y")
                        && r.contains("#X")),
                "an annotation assertion whose VALUE is an affected IRI must be reported: "
                        + referencing);
        Map<String, Object> deprecated = section(result, "deprecated_terms_in_use");
        assertEquals(1, deprecated.get("count"), deprecated::toString);
        assertEquals(ONTOLOGY_IRI + "#Y", rows(deprecated).get(0).get("iri"),
                "the deprecated subject of a value-position reference must be flagged");
    }

    @Test
    void annotationAssertionDeltaPromotesItsSubjectIntoDirectlyAffected(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Path rightDoc = temp.resolve("right.ttl");
        OWLOntologyManager rightManager = OWLManager.createOWLOntologyManager();
        OWLOntology right = rightManager.createOntology(IRI.create(ONTOLOGY_IRI));
        OWLDataFactory rdf = rightManager.getOWLDataFactory();
        for (OWLAxiom ax : ontology.getAxioms()) {
            rightManager.addAxiom(right, ax);
        }
        rightManager.addAxiom(right, rdf.getOWLAnnotationAssertionAxiom(rdf.getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Foo"), rdf.getOWLLiteral("a note")));
        rightManager.saveOntology(right, new TurtleDocumentFormat(),
                IRI.create(rightDoc.toUri()));

        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null,
                Map.of("right_document", rightDoc.toString())));

        Map<String, Object> directly = section(result, "directly_affected");
        Map<String, Object> foo = rows(directly).stream()
                .filter(r -> (ONTOLOGY_IRI + "#Foo").equals(r.get("iri")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the annotation assertion's SUBJECT must be directly affected: " + directly));
        assertEquals(1, foo.get("added"));
        assertEquals(0, foo.get("removed"));
    }

    @Test
    void limitOneBoundsSamplesAndDisclosesTruncation(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Path rightDoc = temp.resolve("right.ttl");
        OWLOntologyManager rightManager = OWLManager.createOWLOntologyManager();
        OWLOntology right = rightManager.createOntology(IRI.create(ONTOLOGY_IRI));
        OWLDataFactory rdf = rightManager.getOWLDataFactory();
        for (OWLAxiom ax : ontology.getAxioms()) {
            rightManager.addAxiom(right, ax);
        }
        rightManager.addAxiom(right, rdf.getOWLSubClassOfAxiom(
                rdf.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo")),
                rdf.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"))));
        rightManager.saveOntology(right, new TurtleDocumentFormat(),
                IRI.create(rightDoc.toUri()));

        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null,
                Map.of("right_document", rightDoc.toString(), "limit", 1)));

        Map<String, Object> directly = section(result, "directly_affected");
        assertEquals(2, directly.get("count"), directly::toString);
        assertEquals(1, rows(directly).size());
        assertEquals(1, directly.get("truncated"), "omitted-count disclosure is required");

        Map<String, Object> referencing = section(result, "referencing_axioms");
        int referencingCount = (int) referencing.get("count");
        assertTrue(referencingCount > 1, referencing::toString);
        assertEquals(1, rows(referencing).size());
        assertEquals(referencingCount - 1, referencing.get("truncated"), referencing::toString);
    }

    @Test
    void unresolvableRightImportUnderIncludeImportsAddsTheCaveat(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Path rightDoc = temp.resolve("right-with-import.ttl");
        Files.writeString(rightDoc, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <%s> a owl:Ontology ;
                    owl:imports <urn:example:missing> .
                """.formatted(ONTOLOGY_IRI));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("right_document", rightDoc.toString());
        args.put("include_imports", true);
        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null, args));

        assertEquals(List.of("urn:example:missing"),
                result.get("right_document_unresolved_imports"));
        assertTrue(String.valueOf(result.get("caveat")).contains("truncated"),
                "a truncated right closure must carry the top-level caveat: " + result);
    }

    @Test
    void schemaInvalidPolicyFailsThePolicyDrivenCategoriesClosed(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        // Syntactically valid YAML that LOADS but fails schema validation (the declared module
        // file does not exist), unlike the unresolvable-policy case above.
        Path policy = temp.resolve("policy.yaml");
        Files.writeString(policy, "version: 1\n"
                + "project_id: invalid-impact\n"
                + "root_ontology: " + ONTOLOGY_IRI + "\n"
                + "modules:\n"
                + "  - ontology_iri: https://example.org/owner\n"
                + "    path: missing-module.ttl\n"
                + "    owned_namespaces: ['" + ONTOLOGY_IRI + "#']\n");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("change_set_id", preview.get("change_set_id"));
        args.put("policy_path", policy.toString());
        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null, args));

        Map<String, Object> modules = section(section(result, "directly_affected"), "modules");
        assertTrue(String.valueOf(modules.get("error")).contains("invalid"),
                "a loaded-but-invalid policy must fail the attribution closed: " + modules);
        assertNull(modules.get("available"));
        Map<String, Object> validation = section(result, "validation_references");
        assertTrue(String.valueOf(validation.get("error")).contains("invalid"),
                validation::toString);
        assertNull(validation.get("available"));
    }

    // ------------------------------------------------------------------ policy-driven categories

    @Test
    void policyDrivesModuleAttributionAndTextualValidationReferences(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        // A valid policy owning the ontology namespace, with an invariant naming #Foo.
        writeModuleDocument(temp.resolve("owner.ttl"), "https://example.org/owner");
        Files.createDirectories(temp.resolve("quality"));
        Files.writeString(temp.resolve("quality/foo-invariant.rq"),
                "# id: foo-invariant\n# severity: error\n"
                        + "ASK { <" + ONTOLOGY_IRI + "#Foo> a <http://www.w3.org/2002/07/owl#Class> }\n");
        Files.writeString(temp.resolve("quality/unrelated.rq"),
                "# id: unrelated\n# severity: error\nASK { ?c a <http://example.org/other#Thing> }\n");
        // An in-workspace CQ manifest naming #Animal.
        Files.writeString(temp.resolve("ontology-cqs.json"),
                "{\"version\":1,\"questions\":[{\"id\":\"CQ-1\",\"text\":\"Are animals classified?\","
                        + "\"query\":\"ASK { <" + ONTOLOGY_IRI + "#Animal> a ?t }\"}]}");
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("impact", ONTOLOGY_IRI)
                        + "modules:\n"
                        + "  - ontology_iri: https://example.org/owner\n"
                        + "    path: owner.ttl\n"
                        + "    owned_namespaces: ['" + ONTOLOGY_IRI + "#']\n"
                        + "validation:\n"
                        + "  required_stages: [structural]\n"
                        + "  fail_on: error\n"
                        + "  invariants:\n    paths: [quality/*.rq]\n");

        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("change_set_id", preview.get("change_set_id"));
        args.put("policy_path", policy.toString());
        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null, args));

        Map<String, Object> modules = section(section(result, "directly_affected"), "modules");
        assertEquals(Boolean.TRUE, modules.get("available"), modules::toString);
        assertEquals(1, modules.get("count"));
        Map<String, Object> moduleRow = rows(modules).get(0);
        assertEquals("https://example.org/owner", moduleRow.get("module"));
        Map<String, Object> terms = section(moduleRow, "terms");
        assertEquals(2, terms.get("count"), "both affected IRIs live in the owned namespace");
        assertEquals(List.of(ONTOLOGY_IRI + "#Animal", ONTOLOGY_IRI + "#Foo"), terms.get("items"));
        assertEquals(0, section(modules, "unowned").get("count"));

        Map<String, Object> validation = section(result, "validation_references");
        assertEquals(Boolean.TRUE, validation.get("available"), validation::toString);
        assertEquals("textual", validation.get("match"));
        assertEquals(2, validation.get("count"), validation::toString);
        List<Map<String, Object>> refs = rows(validation);
        Map<String, Object> invariantRow = refs.stream()
                .filter(r -> "invariants".equals(r.get("source"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(invariantRow.get("ref")).endsWith("foo-invariant.rq"),
                "only the invariant naming an affected IRI matches: " + refs);
        assertEquals(List.of(ONTOLOGY_IRI + "#Foo"),
                section(invariantRow, "iris").get("items"));
        Map<String, Object> cqRow = refs.stream()
                .filter(r -> "workspace_cq".equals(r.get("source"))).findFirst().orElseThrow();
        assertEquals("CQ-1", cqRow.get("ref"));
        assertEquals(List.of(ONTOLOGY_IRI + "#Animal"), section(cqRow, "iris").get("items"));
        assertEquals(2, section(validation, "searched_iris").get("count"));
        assertTrue((int) validation.get("files_scanned") >= 2, validation::toString);
    }

    @Test
    void unresolvablePolicyFailsThePolicyDrivenCategoriesClosed(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("change_set_id", preview.get("change_set_id"));
        args.put("policy_path", temp.resolve("missing.yaml").toString());
        Map<String, Object> result = structured(ImpactTools.analyze(ctx, null, args));

        Map<String, Object> modules = section(section(result, "directly_affected"), "modules");
        assertNotNull(modules.get("error"), "an unresolvable policy must error, not vanish: "
                + modules);
        Map<String, Object> validation = section(result, "validation_references");
        assertNotNull(validation.get("error"), validation::toString);
        assertNull(validation.get("available"));
    }

    // ------------------------------------------------------------------ fixtures

    private static ToolContext context(OWLModelManager mm) {
        return new ToolContext(HeadlessAccess.over(mm), null);
    }

    private static OWLOntology ontology(Path documentDir) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        Files.createDirectories(documentDir);
        manager.setOntologyDocumentIRI(ontology,
                IRI.create(documentDir.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"));
        OWLClass foo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(animal));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(foo));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                animal.getIRI(), df.getOWLLiteral("Animal")));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                foo.getIRI(), df.getOWLLiteral("Foo")));
        return ontology;
    }

    private static void writeModuleDocument(Path path, String ontologyIri) throws Exception {
        Files.writeString(path, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + ontologyIri + "> a owl:Ontology .\n");
    }

    private static OWLDataFactory df(OWLOntology ontology) {
        return ontology.getOWLOntologyManager().getOWLDataFactory();
    }

    private static void addAxiom(OWLOntology ontology, OWLAxiom axiom) {
        ontology.getOWLOntologyManager().addAxiom(ontology, axiom);
    }

    private static Map<String, Object> subclassByIri() {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        return op;
    }

    private static Map<String, Object> committablePreview(ToolContext ctx, Map<String, Object> op) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(op));
        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx, args));
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        return preview;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> result, String key) {
        Object value = result.get(key);
        assertTrue(value instanceof Map, () -> key + " must be an object: " + result);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> section) {
        Object value = section.get("items");
        assertTrue(value instanceof List, () -> "items must be a list: " + section);
        return (List<Map<String, Object>>) value;
    }

    private static List<String> renderings(Map<String, Object> axiomSection) {
        return rows(axiomSection).stream().map(r -> String.valueOf(r.get("rendering"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
