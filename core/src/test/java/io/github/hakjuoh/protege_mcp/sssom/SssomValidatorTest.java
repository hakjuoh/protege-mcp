package io.github.hakjuoh.protege_mcp.sssom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SssomValidatorTest {

    private static final Map<String, String> PREFIXES = Map.of("ex", "https://example.org/");

    @Test
    void structuralValidationAcceptsDeclaredCuriesAndWarnsAboutFormulaCells() {
        MappingRecord record = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("comment", "=HYPERLINK(\"https://example.org\")", "confidence", "0.75",
                        "mapping_date", "2026-07-21"));

        SssomValidator.Report report = SssomValidator.validate(document(record),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertTrue(report.valid(), () -> report.findings().toString());
        assertCode(report, "spreadsheet_formula", "warning");
    }

    @Test
    void structuralValidationAcceptsIsoDatesAndUnambiguousAbsoluteIris() {
        MappingRecord record = record("sha256:0123456789abcdef", "https://example.org/A",
                "skos:exactMatch", "urn:example:B", Map.of("mapping_date", "2026-07-21"));

        SssomValidator.Report report = SssomValidator.validate(document(record),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertTrue(report.valid(), () -> report.findings().toString());
    }

    @Test
    void curiesNeverGuessUndeclaredPrefixes() {
        MappingRecord record = record("urn:m:1", "missing:A", "skos:exactMatch", "ex:B", Map.of());

        SssomValidator.Report report = SssomValidator.validate(document(record),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertCode(report, "subject_id_invalid", "error");
    }

    @Test
    void requiredMissingDeprecatedSourceAndLicenseRulesBlockMutations() {
        MappingRecord record = record("urn:m:1", "ex:Missing", "skos:exactMatch", "ex:Old",
                Map.of("mapping_source", "unapproved", "license", "GPL-3.0"));
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of("approved"),
                Set.of("CC0-1.0"), true,
                Set.of("missing_source", "deprecated_target", "source_not_allowed",
                        "license_not_allowed"), Map.of(), List.of(), PREFIXES);
        SssomEntityIndex entities = new SssomEntityIndex(Set.of("https://example.org/Old"),
                Set.of("https://example.org/Old"));

        SssomValidator.Report report = SssomValidator.validate(document(record), policy, entities);

        assertFalse(report.valid());
        assertCode(report, "missing_source", "error");
        assertCode(report, "deprecated_target", "error");
        assertCode(report, "source_not_allowed", "error");
        assertCode(report, "license_not_allowed", "error");
    }

    @Test
    void nonRequiredEntityGovernanceFindingsRemainWarnings() {
        MappingRecord record = record("urn:m:1", "ex:Missing", "skos:closeMatch", "ex:AlsoMissing",
                Map.of());
        SssomEntityIndex entities = new SssomEntityIndex(Set.of("https://example.org/Other"), Set.of());

        SssomValidator.Report report = SssomValidator.validate(document(record),
                new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false, Set.of(), Map.of(),
                        List.of(), PREFIXES), entities);

        assertTrue(report.valid());
        assertCode(report, "missing_source", "warning");
        assertCode(report, "missing_target", "warning");
    }

    @Test
    void idCollisionsAndConflictingExactComponentsAlwaysFail() {
        MappingRecord first = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("x", "one"));
        MappingRecord collision = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("x", "two"));
        MappingRecord conflict = record("urn:m:2", "ex:A", "skos:exactMatch", "ex:C", Map.of());

        SssomValidator.Report report = SssomValidator.validate(document(first, collision, conflict),
                new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false, Set.of(), Map.of(),
                        List.of(), PREFIXES), SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertCode(report, "mapping_id_conflict", "error");
        assertCode(report, "conflicting_exact_mapping", "error");
    }

    @Test
    void reverseSymmetricDuplicatesAndSelfMapsAreNotCyclesOrExactConflicts() {
        MappingRecord forward = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B", Map.of());
        MappingRecord reverse = record("urn:m:2", "ex:B", "skos:exactMatch", "ex:A", Map.of());
        MappingRecord self = record("urn:m:3", "ex:C", "skos:exactMatch", "ex:C", Map.of());

        SssomValidator.Report report = SssomValidator.validate(document(forward, reverse, self),
                new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false, Set.of(), Map.of(),
                        List.of(), PREFIXES), SssomEntityIndex.unavailable());

        assertTrue(report.valid(), () -> report.findings().toString());
        assertNoCode(report, "mapping_cycle");
        assertNoCode(report, "conflicting_exact_mapping");
    }

    @Test
    void directionalBroadAndNarrowEdgesNormalizeBeforeSccValidation() {
        MappingRecord broad = record("urn:m:1", "ex:A", "skos:broadMatch", "ex:B", Map.of());
        MappingRecord narrow = record("urn:m:2", "ex:A", "skos:narrowMatch", "ex:B", Map.of());
        SssomValidationPolicy error = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of(), Map.of("skos:broadMatch", "error", "skos:narrowMatch", "error"),
                List.of(), PREFIXES);

        SssomValidator.Report rejected = SssomValidator.validate(document(broad, narrow), error,
                SssomEntityIndex.unavailable());
        assertFalse(rejected.valid());
        assertCode(rejected, "mapping_cycle", "error");

        SssomValidationPolicy allowed = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of(), Map.of("skos:broadMatch", "allow", "skos:narrowMatch", "allow"),
                List.of(), PREFIXES);
        SssomValidator.Report accepted = SssomValidator.validate(document(broad, narrow), allowed,
                SssomEntityIndex.unavailable());
        assertTrue(accepted.valid());
        assertNoCode(accepted, "mapping_cycle");
    }

    @Test
    void manyToOneRestrictionsApplyOnlyInsideTheNamedScope() {
        MappingRecord first = record("urn:m:1", "ex:A", "skos:closeMatch", "ex:T",
                Map.of("subject_source", "ex:Source", "object_source", "ex:Target"));
        MappingRecord second = record("urn:m:2", "ex:B", "skos:closeMatch", "ex:T",
                Map.of("subject_source", "ex:Source", "object_source", "ex:Target"));
        MappingRecord outside = record("urn:m:3", "ex:C", "skos:closeMatch", "ex:U",
                Map.of("subject_source", "ex:Other", "object_source", "ex:Target"));
        SssomValidationPolicy.ManyToOneRule rule = new SssomValidationPolicy.ManyToOneRule(
                "skos:closeMatch", Set.of("ex:Source"), Set.of(), Set.of("ex:Target"));
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of(), Map.of(), List.of(rule), PREFIXES);

        SssomValidator.Report report = SssomValidator.validate(document(first, second, outside),
                policy, SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertEquals(1, report.findings().stream()
                .filter(finding -> "many_to_one_mapping".equals(finding.code())).count());
    }

    @Test
    void policyAllowlistExtendsTheBasePredicateVocabulary() {
        MappingRecord custom = record("urn:m:1", "ex:A", "ex:mapsTo", "ex:B", Map.of());

        assertTrue(SssomValidator.validate(document(custom),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable()).valid(),
                "SSSOM structural validation accepts declared arbitrary predicates");

        SssomValidationPolicy accepted = new SssomValidationPolicy(Set.of("ex:mapsTo"), Set.of(),
                Set.of(), false, Set.of(), Map.of(), List.of(), PREFIXES);
        assertTrue(SssomValidator.validate(document(custom), accepted,
                SssomEntityIndex.unavailable()).valid());

        MappingRecord expandedBase = record("urn:m:2", "ex:A",
                "http://www.w3.org/2004/02/skos/core#exactMatch", "ex:B", Map.of());
        SssomValidationPolicy restricted = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(),
                false, Set.of(), Map.of(), List.of(), PREFIXES);
        assertTrue(SssomValidator.validate(document(expandedBase), restricted,
                SssomEntityIndex.unavailable()).valid(),
                "base predicates remain allowed when written as full IRIs");
        MappingRecord expandedJustification = record("urn:m:3", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("mapping_justification",
                        "https://w3id.org/semapv/vocab/ManualMappingCuration"));
        assertTrue(SssomValidator.validate(document(expandedJustification), restricted,
                SssomEntityIndex.unavailable()).valid());

        SssomValidator.Report rejected = SssomValidator.validate(document(custom),
                new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                        Set.of("predicate_not_allowed"), Map.of(), List.of(), PREFIXES),
                SssomEntityIndex.unavailable());
        assertFalse(rejected.valid());
        assertCode(rejected, "predicate_not_allowed", "error");
    }

    @Test
    void builtInPrefixesNeedNoDeclarationAndCannotBeRedefined() {
        MappingRecord builtIns = record("urn:m:1", "owl:Thing", "skos:relatedMatch",
                "rdfs:Resource", Map.of());
        SssomDocument noCurieMap = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/"), Map.of(),
                document(builtIns).columns(), List.of(builtIns));
        assertTrue(SssomValidator.validate(noCurieMap, SssomValidationPolicy.structural(),
                SssomEntityIndex.unavailable()).valid());

        SssomDocument redefined = new SssomDocument(noCurieMap.metadata(),
                Map.of("owl", "https://malicious.example/owl#"), noCurieMap.columns(),
                noCurieMap.records());
        SssomValidator.Report report = SssomValidator.validate(redefined,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertFalse(report.valid());
        assertCode(report, "prefix_conflict", "error");

        MappingRecord oboPredicate = record("", "owl:Thing",
                "oboInOwl:hasDbXref", "rdfs:Resource", Map.of());
        SssomDocument undeclared = new SssomDocument(noCurieMap.metadata(), Map.of(),
                document(oboPredicate).columns(), List.of(oboPredicate));
        SssomValidator.Report missingPrefix = SssomValidator.validate(undeclared,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertFalse(missingPrefix.valid());
        assertCode(missingPrefix, "predicate_id_invalid", "error");

        SssomDocument declared = new SssomDocument(noCurieMap.metadata(),
                Map.of("oboInOwl", "http://www.geneontology.org/formats/oboInOwl#"),
                undeclared.columns(), undeclared.records());
        SssomValidator.Report declaredReport = SssomValidator.validate(declared,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertTrue(declaredReport.valid());

        MappingRecord fullObo = record("", "owl:Thing",
                "http://www.geneontology.org/formats/oboInOwl#hasDbXref",
                "rdfs:Resource", Map.of());
        SssomDocument fullOboDocument = new SssomDocument(noCurieMap.metadata(), Map.of(),
                document(fullObo).columns(), List.of(fullObo));
        SssomValidationPolicy restrictive = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(),
                false, Set.of(), Map.of(), List.of(), Map.of());
        assertTrue(SssomValidator.validate(declared, restrictive,
                SssomEntityIndex.unavailable()).valid());
        SssomValidator.Report fullReport = SssomValidator.validate(fullOboDocument, restrictive,
                SssomEntityIndex.unavailable());
        assertTrue(fullReport.valid());
        assertEquals(declaredReport.document().records().get(0).mappingId(),
                fullReport.document().records().get(0).mappingId());

        SssomDocument spoofed = new SssomDocument(noCurieMap.metadata(),
                Map.of("oboInOwl", "https://malicious.example/predicates/"),
                undeclared.columns(), undeclared.records());
        SssomValidator.Report spoofedReport = SssomValidator.validate(spoofed, restrictive,
                SssomEntityIndex.unavailable());
        assertFalse(spoofedReport.valid());
        assertCode(spoofedReport, "predicate_not_allowed", "error");
    }

    @Test
    void conflictingDocumentAndPolicyPrefixesFailClosed() {
        MappingRecord record = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B", Map.of());
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of(), Map.of(), List.of(), Map.of("ex", "https://other.example/"));

        SssomValidator.Report report = SssomValidator.validate(document(record), policy,
                SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertCode(report, "prefix_conflict", "error");
    }

    @Test
    void literalMappingsUseLabelsAndStableLiteralIdentity() {
        MappingRecord alice = record("", "", "skos:closeMatch", "ex:A",
                Map.of("subject_type", "rdfs literal", "subject_label", "alice"));
        MappingRecord bob = record("", "", "skos:closeMatch", "ex:A",
                Map.of("subject_type", "rdfs literal", "subject_label", "bob"));

        SssomValidator.Report report = SssomValidator.validate(document(alice, bob),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertTrue(report.valid(), () -> report.findings().toString());
        assertFalse(report.document().records().get(0).mappingId()
                .equals(report.document().records().get(1).mappingId()));
    }

    @Test
    void exactManyToOneIsAllowedUnlessPolicyExplicitlyProhibitsIt() {
        MappingRecord first = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:T", Map.of());
        MappingRecord second = record("urn:m:2", "ex:B", "skos:exactMatch", "ex:T", Map.of());

        SssomValidator.Report report = SssomValidator.validate(document(first, second),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertTrue(report.valid(), () -> report.findings().toString());
        assertNoCode(report, "conflicting_exact_mapping");
    }

    @Test
    void explicitLicenseRequirementAndEmptyCapturedEntityIndexFailClosed() {
        MappingRecord record = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B", Map.of());
        SssomDocument noLicense = new SssomDocument(
                Map.of("mapping_set_id", "https://example.org/mappings"), PREFIXES,
                document(record).columns(), List.of(record));
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), true,
                Set.of("missing_source", "missing_target"), Map.of(), List.of(), PREFIXES);

        SssomValidator.Report report = SssomValidator.validate(noLicense, policy,
                new SssomEntityIndex(Set.of(), Set.of()));

        assertFalse(report.valid());
        assertCode(report, "license_not_allowed", "error");
        assertCode(report, "missing_source", "error");
        assertCode(report, "missing_target", "error");
    }

    @Test
    void manyToOneScopesCompareExpandedIrisAndPropagatedMetadata() {
        MappingRecord first = record("urn:m:1", "ex:A", "skos:closeMatch", "ex:T", Map.of());
        MappingRecord second = record("urn:m:2", "ex:B", "skos:closeMatch", "ex:T", Map.of());
        SssomDocument propagated = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/",
                "subject_source", "https://example.org/Source",
                "object_source", "https://example.org/Target",
                "mapping_provider", "https://example.org/Provider"), PREFIXES,
                document(first, second).columns(), List.of(first, second));
        SssomValidationPolicy.ManyToOneRule rule = new SssomValidationPolicy.ManyToOneRule(
                "skos:closeMatch", Set.of("ex:Source"), Set.of("ex:Provider"), Set.of("ex:Target"));
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(),
                Set.of("https://example.org/Provider"),
                Set.of("https://creativecommons.org/licenses/by/4.0/"), true, Set.of(), Map.of(),
                List.of(rule), PREFIXES);

        SssomValidator.Report report = SssomValidator.validate(propagated, policy,
                SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertCode(report, "many_to_one_mapping", "error");
        assertNoCode(report, "source_not_allowed");
        assertNoCode(report, "license_not_allowed");
    }

    @Test
    void rejectsEmptyPredicatesInvalidCurieLocalsAndUnsupportedMetadata() {
        MappingRecord emptyPredicate = record("urn:m:1", "ex:A", "", "ex:B", Map.of());
        MappingRecord invalidLocal = record("urn:m:2", "ex:A B", "skos:exactMatch", "ex:B", Map.of());
        SssomDocument badVersion = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/",
                "sssom_version", "2.0"), PREFIXES,
                document(emptyPredicate, invalidLocal).columns(), List.of(emptyPredicate, invalidLocal));

        SssomValidator.Report report = SssomValidator.validate(badVersion,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertCode(report, "predicate_id_invalid", "error");
        assertCode(report, "subject_id_invalid", "error");
        assertCode(report, "sssom_version_unsupported", "error");
    }

    @Test
    void acceptsAdditionalSssomOnePredicateVocabulary() {
        for (String predicate : List.of(
                "http://www.geneontology.org/formats/oboInOwl#hasDbXref",
                "rdfs:seeAlso", "rdf:type",
                "semapv:crossSpeciesExactMatch", "semapv:crossSpeciesCloseMatch",
                "semapv:crossSpeciesBroadMatch", "semapv:crossSpeciesNarrowMatch")) {
            SssomValidator.Report report = SssomValidator.validate(
                    document(record("urn:m:" + predicate.hashCode(), "ex:A", predicate, "ex:B", Map.of())),
                    SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
            assertTrue(report.valid(), () -> predicate + ": " + report.findings());
        }
    }

    @Test
    void iterativeCycleTraversalHandlesLongChains() {
        List<MappingRecord> records = new ArrayList<>();
        for (int index = 0; index < 20_000; index++) {
            records.add(record("urn:m:" + index, "ex:N" + index, "skos:broadMatch",
                    "ex:N" + (index + 1), Map.of()));
        }

        SssomValidator.Report report = SssomValidator.validate(
                document(records.toArray(MappingRecord[]::new)),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertTrue(report.valid(), () -> report.findings().toString());
    }

    @Test
    void findingTruncationIsDeterministicAcrossInputOrder() {
        List<MappingRecord> forward = new ArrayList<>();
        for (int index = 0; index < 1_010; index++) {
            forward.add(record("urn:m:" + index, "ex:S" + index, "skos:closeMatch",
                    "ex:T" + index, Map.of()));
        }
        List<MappingRecord> reverse = new ArrayList<>(forward);
        java.util.Collections.reverse(reverse);
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of(), Map.of(), List.of(), PREFIXES);

        SssomValidator.Report first = SssomValidator.validate(
                document(forward.toArray(MappingRecord[]::new)), policy,
                new SssomEntityIndex(Set.of(), Set.of()));
        SssomValidator.Report second = SssomValidator.validate(
                document(reverse.toArray(MappingRecord[]::new)), policy,
                new SssomEntityIndex(Set.of(), Set.of()));

        assertTrue(first.findingsTruncated());
        assertEquals(first.findings(), second.findings());
    }

    @Test
    void officialNoTermFoundIsBuiltInAndNotAMissingEntity() throws Exception {
        byte[] bytes;
        try (java.io.InputStream input = getClass()
                .getResourceAsStream("/sssom/v1.0-no-term-found.sssom.tsv")) {
            assertTrue(input != null);
            bytes = input.readAllBytes();
        }
        SssomDocument fixture = SssomParser.parse(bytes);
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of("missing_source", "missing_target"), Map.of(), List.of(), fixture.prefixMap());
        SssomEntityIndex entities = new SssomEntityIndex(Set.of(
                "http://purl.obolibrary.org/obo/HP_0009124",
                "http://purl.obolibrary.org/obo/HP_0000411",
                "http://purl.obolibrary.org/obo/MP_0000003"), Set.of());

        SssomValidator.Report report = SssomValidator.validate(fixture, policy, entities);

        assertTrue(report.valid(), () -> report.findings().toString());
        assertNoCode(report, "missing_target");
    }

    @Test
    void propagatableMetadataAppliesOnlyWhenNoRowDefinesTheColumn() {
        MappingRecord override = record("urn:m:1", "ex:A", "skos:closeMatch", "ex:B",
                Map.of("mapping_provider", "https://other.example/provider"));
        MappingRecord blank = record("urn:m:2", "ex:C", "skos:closeMatch", "ex:D", Map.of());
        SssomDocument mixed = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/",
                "mapping_provider", "https://approved.example/provider"), PREFIXES,
                document(override, blank).columns(), List.of(override, blank));
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(),
                Set.of("https://approved.example/provider"), Set.of(), false,
                Set.of("source_not_allowed"), Map.of(), List.of(), PREFIXES);

        SssomValidator.Report report = SssomValidator.validate(mixed, policy,
                SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertEquals(2, report.findings().stream()
                .filter(finding -> "source_not_allowed".equals(finding.code())).count());
    }

    @Test
    void literalEndpointsParticipateInPolicyCardinality() {
        MappingRecord alice = record("urn:m:1", "", "skos:closeMatch", "ex:T",
                Map.of("subject_type", "rdfs literal", "subject_label", "alice",
                        "subject_source", "ex:Source", "object_source", "ex:Target"));
        MappingRecord bob = record("urn:m:2", "", "skos:closeMatch", "ex:T",
                Map.of("subject_type", "rdfs literal", "subject_label", "bob",
                        "subject_source", "ex:Source", "object_source", "ex:Target"));
        SssomValidationPolicy.ManyToOneRule rule = new SssomValidationPolicy.ManyToOneRule(
                "skos:closeMatch", Set.of("ex:Source"), Set.of(), Set.of("ex:Target"));
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of(), Map.of(), List.of(rule), PREFIXES);

        SssomValidator.Report report = SssomValidator.validate(document(alice, bob), policy,
                SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertCode(report, "many_to_one_mapping", "error");
    }

    @Test
    void validatesStandardEnumsRangesReferencesAndDates() {
        MappingRecord invalid = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B", Map.of(
                "subject_type", "bogus", "predicate_modifier", "Never",
                "mapping_cardinality", "2:7", "similarity_score", "9",
                "license", "not a uri", "mapping_provider", "provider",
                "author_id", "missing:author",
                "mapping_date", "2026-07-21T12:00:00Z"));

        SssomValidator.Report report = SssomValidator.validate(document(invalid),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        for (String code : List.of("entity_type_invalid", "predicate_modifier_invalid",
                "mapping_cardinality_invalid", "similarity_score_invalid", "license_invalid",
                "mapping_provider_invalid", "author_id_invalid", "date_invalid")) {
            assertCode(report, code, "error");
        }
    }

    @Test
    void acceptsAndCanonicalizesCompleteStandardRows() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("subject_label", "Subject");
        fields.put("subject_category", "ex:Category");
        fields.put("subject_type", "owl:Class");
        fields.put("predicate_label", "matches");
        fields.put("object_label", "Object");
        fields.put("object_category", "ex:Category");
        fields.put("object_type", "owl:Class");
        fields.put("confidence", "0.9");
        fields.put("author_id", "ex:Author|ex:Coauthor");
        fields.put("author_label", "A|B");
        fields.put("reviewer_id", "ex:Reviewer");
        fields.put("creator_id", "ex:Creator");
        fields.put("license", "https://example.org/license");
        fields.put("subject_source", "ex:Source");
        fields.put("subject_source_version", "1");
        fields.put("object_source", "ex:Target");
        fields.put("object_source_version", "2");
        fields.put("mapping_provider", "https://example.org/provider");
        fields.put("mapping_source", "ex:MappingSet");
        fields.put("mapping_cardinality", "1:1");
        fields.put("mapping_tool", "tool");
        fields.put("mapping_tool_version", "1.0");
        fields.put("mapping_date", "2026-07-21");
        fields.put("publication_date", "2026-07-22");
        fields.put("curation_rule", "ex:Rule");
        fields.put("curation_rule_text", "reviewed");
        fields.put("subject_match_field", "ex:Label");
        fields.put("object_match_field", "ex:Label");
        fields.put("match_string", "match");
        fields.put("subject_preprocessing", "ex:Normalize");
        fields.put("object_preprocessing", "ex:Normalize");
        fields.put("similarity_score", "0.8");
        fields.put("similarity_measure", "Jaccard");
        fields.put("see_also", "human review note|relative/evidence");
        fields.put("issue_tracker_item", "ex:Issue");
        fields.put("other", "key=value");
        fields.put("comment", "complete");
        MappingRecord complete = record("urn:m:complete", "ex:A", "skos:exactMatch", "ex:B", fields);

        SssomValidator.Report report = SssomValidator.validate(document(complete),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertTrue(report.valid(), () -> report.findings().toString());
        byte[] bytes = SssomParser.render(report.document());
        assertEquals(report.document(), SssomParser.parse(bytes).canonical());
    }

    @Test
    void negatedMappingsDoNotCreatePositiveConflictsCyclesOrCardinality() {
        MappingRecord exact = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B", Map.of());
        MappingRecord negatedExact = record("urn:m:2", "ex:A", "skos:exactMatch", "ex:C",
                Map.of("predicate_modifier", "Not"));
        assertNoCode(SssomValidator.validate(document(exact, negatedExact),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable()),
                "conflicting_exact_mapping");

        MappingRecord broad = record("urn:m:3", "ex:A", "skos:broadMatch", "ex:B", Map.of());
        MappingRecord negatedNarrow = record("urn:m:4", "ex:A", "skos:narrowMatch", "ex:B",
                Map.of("predicate_modifier", "Not"));
        assertNoCode(SssomValidator.validate(document(broad, negatedNarrow),
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable()), "mapping_cycle");

        MappingRecord positive = record("urn:m:5", "ex:A", "skos:closeMatch", "ex:T",
                Map.of("subject_source", "ex:Source"));
        MappingRecord negative = record("urn:m:6", "ex:B", "skos:closeMatch", "ex:T",
                Map.of("subject_source", "ex:Source", "predicate_modifier", "Not"));
        SssomValidationPolicy.ManyToOneRule rule = new SssomValidationPolicy.ManyToOneRule(
                "skos:closeMatch", Set.of("ex:Source"), Set.of(), Set.of());
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of(), Map.of(), List.of(rule), PREFIXES);
        assertNoCode(SssomValidator.validate(document(positive, negative), policy,
                SssomEntityIndex.unavailable()), "many_to_one_mapping");
    }

    @Test
    void deterministicIdsIgnoreDisplayLabelsAndNormalizePropagatedProvider() {
        MappingRecord labelled = record("", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("subject_label", "First label"));
        MappingRecord relabelled = record("", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("subject_label", "Changed label"));
        assertEquals(document(labelled).canonical().records().get(0).mappingId(),
                document(relabelled).canonical().records().get(0).mappingId());

        MappingRecord inherited = record("", "ex:A", "skos:closeMatch", "ex:B", Map.of());
        SssomDocument metadataProvider = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/",
                "mapping_provider", "https://example.org/provider"), PREFIXES,
                document(inherited).columns(), List.of(inherited));
        MappingRecord rowProvider = record("", "ex:A", "skos:closeMatch", "ex:B",
                Map.of("mapping_provider", "https://example.org/provider"));
        assertEquals(metadataProvider.canonical().records().get(0).mappingId(),
                document(rowProvider).canonical().records().get(0).mappingId());

        MappingRecord compact = record("", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("mapping_justification", "semapv:ManualMappingCuration",
                        "author_id", "ex:Author|ex:Coauthor"));
        MappingRecord expanded = record("", "https://example.org/A",
                "http://www.w3.org/2004/02/skos/core#exactMatch", "https://example.org/B",
                Map.of("mapping_justification",
                        "https://w3id.org/semapv/vocab/ManualMappingCuration",
                        "author_id", "https://example.org/Author|https://example.org/Coauthor"));
        assertEquals(document(compact).canonical().records().get(0).mappingId(),
                document(expanded).canonical().records().get(0).mappingId(),
                "deterministic identity must use expanded references, not CURIE spelling");

        SssomDocument policyOnlyCurie = new SssomDocument(document(compact).metadata(), Map.of(),
                document(compact).columns(), List.of(compact));
        SssomDocument policyOnlyExpanded = new SssomDocument(document(expanded).metadata(), Map.of(),
                document(expanded).columns(), List.of(expanded));
        SssomValidationPolicy policyPrefix = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(),
                false, Set.of(), Map.of(), List.of(), PREFIXES, false);
        assertEquals(SssomValidator.validate(policyOnlyCurie, policyPrefix,
                        SssomEntityIndex.unavailable()).document().records().get(0).mappingId(),
                SssomValidator.validate(policyOnlyExpanded, policyPrefix,
                        SssomEntityIndex.unavailable()).document().records().get(0).mappingId(),
                "policy-approved prefixes participate in generated identity normalization");

        MappingRecord positive = record("", "urn:subject", "skos:closeMatch", "ex:B", Map.of());
        MappingRecord negative = record("", "urn:subject", "skos:closeMatch", "ex:B",
                Map.of("predicate_modifier", "Not"));
        assertFalse(document(positive).canonical().records().get(0).mappingId()
                .equals(document(negative).canonical().records().get(0).mappingId()));

        SssomDocument metadataNegation = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/",
                "predicate_modifier", "Not"), PREFIXES,
                document(positive).columns(), List.of(positive));
        assertEquals(document(positive).canonical().records().get(0).mappingId(),
                metadataNegation.canonical().records().get(0).mappingId(),
                "non-propagatable metadata must not alter mapping identity");

        MappingRecord named = record("", "urn:subject", "skos:closeMatch", "ex:B", Map.of());
        MappingRecord literal = record("", "", "skos:closeMatch", "ex:B",
                Map.of("subject_type", "rdfs literal", "subject_label", "urn:subject"));
        assertFalse(document(named).canonical().records().get(0).mappingId()
                .equals(document(literal).canonical().records().get(0).mappingId()));
    }

    @Test
    void nonPropagatableMetadataDoesNotSupplyRequiredRowValues() {
        MappingRecord blankJustification = record("", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("mapping_justification", ""));
        SssomDocument document = new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/",
                "mapping_justification", "semapv:ManualMappingCuration"), PREFIXES,
                document(blankJustification).columns(), List.of(blankJustification));

        SssomValidator.Report report = SssomValidator.validate(document,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertFalse(report.valid());
        assertCode(report, "mapping_justification_invalid", "error");
    }

    @Test
    void malformedOptionalMetadataTypesFailClosed() {
        MappingRecord mapping = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B", Map.of());
        Map<String, Object> badMetadata = new LinkedHashMap<>();
        badMetadata.put("mapping_set_id", "https://example.org/mappings");
        badMetadata.put("license", "https://creativecommons.org/licenses/by/4.0/");
        badMetadata.put("mapping_provider", List.of("https://example.org/provider"));
        badMetadata.put("subject_type", 42);
        badMetadata.put("mapping_date", List.of("2026-07-21"));
        badMetadata.put("mapping_set_version", Map.of("bad", "value"));
        badMetadata.put("mapping_set_title", List.of("title"));
        badMetadata.put("subject_source_version", List.of("1"));
        badMetadata.put("object_source_version", Map.of("bad", "2"));
        badMetadata.put("mapping_tool", List.of("tool"));
        badMetadata.put("mapping_tool_version", 4);
        badMetadata.put("comment", List.of("comment"));
        badMetadata.put("other", Map.of("bad", "value"));
        badMetadata.put("creator_label", List.of("name", 7));
        badMetadata.put("see_also", Map.of("bad", "value"));
        SssomDocument malformed = new SssomDocument(badMetadata, PREFIXES,
                document(mapping).columns(), List.of(mapping));

        SssomValidator.Report report = SssomValidator.validate(malformed,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());

        assertFalse(report.valid());
        assertEquals(13, report.findings().stream()
                .filter(finding -> "metadata_type_invalid".equals(finding.code())).count());
    }

    @Test
    void validatesDefinedExtensionsWhilePreservingUndefinedColumns() {
        MappingRecord valid = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("x_count", "42", "x_reference", "ex:Term", "x_undefined", "raw"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mapping_set_id", "https://example.org/mappings");
        metadata.put("license", "https://creativecommons.org/licenses/by/4.0/");
        metadata.put("x_text", 123);
        metadata.put("extension_definitions", List.of(
                Map.of("slot_name", "x_text", "property", "ex:text",
                        "type_hint", "xsd:string"),
                Map.of("slot_name", "x_count", "property", "ex:count",
                        "type_hint", "xsd:integer"),
                Map.of("slot_name", "x_decimal", "property", "ex:decimal",
                        "type_hint", "xsd:decimal"),
                Map.of("slot_name", "x_reference", "property", "ex:reference",
                        "type_hint", "linkml:Uriorcurie")));
        valid = valid.withCells(Map.of("x_decimal", "4.2"));
        SssomDocument validDocument = new SssomDocument(metadata, PREFIXES,
                valid.cells().keySet().stream().toList(), List.of(valid));
        assertTrue(SssomValidator.validate(validDocument, SssomValidationPolicy.structural(),
                SssomEntityIndex.unavailable()).valid());

        MappingRecord invalid = valid.withCells(Map.of("x_count", "4.2", "x_decimal", "not-a-decimal",
                "x_reference", "undeclared:Term"));
        SssomValidator.Report report = SssomValidator.validate(new SssomDocument(metadata, PREFIXES,
                validDocument.columns(), List.of(invalid)), SssomValidationPolicy.structural(),
                SssomEntityIndex.unavailable());
        assertFalse(report.valid());
        assertEquals(3, report.findings().stream()
                .filter(finding -> "extension_value_invalid".equals(finding.code())).count());
    }

    @Test
    void ignoresDefinitionsWithUnknownKeysAndDisclosesUnknownDatatypes() {
        MappingRecord mapping = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B",
                Map.of("x_ignored", "not-an-integer", "x_custom", "opaque"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mapping_set_id", "https://example.org/mappings");
        metadata.put("license", "https://creativecommons.org/licenses/by/4.0/");
        metadata.put("extension_definitions", List.of(
                Map.of("slot_name", "x_ignored", "property", "ex:ignored",
                        "type_hint", "xsd:integer", "unexpected", 42),
                Map.of("slot_name", "x_custom", "property", "ex:custom",
                        "type_hint", "ex:CustomDatatype")));
        SssomDocument document = new SssomDocument(metadata, PREFIXES,
                mapping.cells().keySet().stream().toList(), List.of(mapping));

        SssomValidator.Report report = SssomValidator.validate(document,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertTrue(report.valid(), () -> report.findings().toString());
        assertCode(report, "extension_definition_ignored", "warning");
        assertCode(report, "extension_type_unsupported", "warning");
        assertNoCode(report, "extension_value_invalid");
    }

    @Test
    void rejectsMalformedOrDuplicateExtensionDefinitions() {
        MappingRecord mapping = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B", Map.of());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mapping_set_id", "https://example.org/mappings");
        metadata.put("license", "https://creativecommons.org/licenses/by/4.0/");
        metadata.put("extension_definitions", List.of(
                Map.of("slot_name", "subject_id", "property", "ex:first"),
                Map.of("slot_name", "x_duplicate", "property", "ex:same"),
                Map.of("slot_name", "x_duplicate", "property", "ex:same")));
        SssomDocument document = new SssomDocument(metadata, PREFIXES,
                mapping.cells().keySet().stream().toList(), List.of(mapping));

        SssomValidator.Report report = SssomValidator.validate(document,
                SssomValidationPolicy.structural(), SssomEntityIndex.unavailable());
        assertFalse(report.valid());
        assertTrue(report.findings().stream()
                .anyMatch(finding -> "extension_definitions_invalid".equals(finding.code())));
    }

    @Test
    void requiredEntityRulesRejectUnavailableIndex() {
        MappingRecord mapping = record("urn:m:1", "ex:A", "skos:exactMatch", "ex:B", Map.of());
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of("missing_source"), Map.of(), List.of(), PREFIXES);
        SssomValidator.Report report = SssomValidator.validate(document(mapping), policy,
                SssomEntityIndex.unavailable());
        assertFalse(report.valid());
        assertCode(report, "entity_index_unavailable", "error");
    }

    @Test
    void maximumPolicyScopesRemainBounded() {
        List<MappingRecord> records = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            records.add(record("urn:m:" + index, "ex:S" + index, "skos:closeMatch",
                    "ex:T" + index, Map.of("subject_source", "ex:Source0")));
        }
        List<SssomValidationPolicy.ManyToOneRule> rules = new ArrayList<>();
        Set<String> scopes = new java.util.LinkedHashSet<>();
        for (int index = 0; index < 128; index++) scopes.add("ex:Source" + index);
        for (int index = 0; index < 64; index++) {
            rules.add(new SssomValidationPolicy.ManyToOneRule("skos:closeMatch",
                    scopes, Set.of(), Set.of()));
        }
        SssomValidationPolicy policy = new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false,
                Set.of(), Map.of(), rules, PREFIXES);

        assertTimeout(Duration.ofSeconds(5), () -> SssomValidator.validate(
                document(records.toArray(MappingRecord[]::new)), policy,
                SssomEntityIndex.unavailable()));
    }

    private static SssomDocument document(MappingRecord... records) {
        List<String> columns = new ArrayList<>(SssomDocument.REQUIRED_COLUMNS);
        for (MappingRecord record : records) {
            for (String column : record.cells().keySet()) if (!columns.contains(column)) columns.add(column);
        }
        return new SssomDocument(Map.of(
                "mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/"), PREFIXES,
                columns, List.of(records));
    }

    private static MappingRecord record(String id, String subject, String predicate, String object,
            Map<String, String> extra) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("mapping_id", id);
        cells.put("subject_id", subject);
        cells.put("predicate_id", predicate);
        cells.put("object_id", object);
        cells.put("mapping_justification", "semapv:ManualMappingCuration");
        cells.putAll(extra);
        return new MappingRecord(cells);
    }

    private static void assertCode(SssomValidator.Report report, String code, String severity) {
        assertTrue(report.findings().stream().anyMatch(finding -> code.equals(finding.code())
                && severity.equals(finding.severity())), () -> report.findings().toString());
    }

    private static void assertNoCode(SssomValidator.Report report, String code) {
        assertTrue(report.findings().stream().noneMatch(finding -> code.equals(finding.code())),
                () -> report.findings().toString());
    }
}
