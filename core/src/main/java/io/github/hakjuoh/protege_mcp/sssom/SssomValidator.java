package io.github.hakjuoh.protege_mcp.sssom;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic structural and project-governance validation for SSSOM mapping rows. */
public final class SssomValidator {

    public static final int MAX_FINDINGS = 2_000;

    private static final Set<String> BASE_PREDICATES = Set.of(
            "skos:exactMatch", "skos:closeMatch", "skos:broadMatch", "skos:narrowMatch",
            "skos:relatedMatch", "owl:equivalentClass", "owl:equivalentProperty", "owl:sameAs",
            "owl:differentFrom", "rdfs:subClassOf", "rdfs:subPropertyOf", "rdfs:seeAlso",
            "rdf:type", "oboInOwl:hasDbXref", "sssom:superClassOf",
            "semapv:crossSpeciesExactMatch", "semapv:crossSpeciesCloseMatch",
            "semapv:crossSpeciesBroadMatch", "semapv:crossSpeciesNarrowMatch");
    private static final Set<String> EXACT = Set.of(
            "skos:exactMatch", "owl:equivalentClass", "owl:equivalentProperty", "owl:sameAs",
            "semapv:crossSpeciesExactMatch");
    private static final Set<String> OPAQUE_IRI_SCHEMES = Set.of(
            "doi", "mailto", "sha256", "tag", "urn");
    private static final Set<String> JUSTIFICATIONS = Set.of(
            "semapv:MappingReview", "semapv:ManualMappingCuration", "semapv:LogicalReasoning",
            "semapv:LexicalMatching", "semapv:CompositeMatching", "semapv:UnspecifiedMatching",
            "semapv:SemanticSimilarityThresholdMatching", "semapv:LexicalSimilarityThresholdMatching",
            "semapv:MappingChaining");
    private static final Set<String> ENTITY_TYPES = Set.of(
            "owl class", "owl object property", "owl data property", "owl annotation property",
            "owl named individual", "skos concept", "rdfs resource", "rdfs class",
            "rdfs literal", "rdfs datatype", "rdf property",
            "owl:Class", "owl:ObjectProperty", "owl:DatatypeProperty", "owl:AnnotationProperty",
            "owl:NamedIndividual", "skos:Concept", "rdfs:Resource", "rdfs:Class",
            "rdfs:Literal", "rdfs:Datatype", "rdf:Property");
    private static final Set<String> CARDINALITIES = Set.of(
            "1:1", "1:n", "n:1", "1:0", "0:1", "n:n");
    private static final String NO_TERM_FOUND = "https://w3id.org/sssom/NoTermFound";
    private static final String LITERAL_KEY = "literal:";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String LINKML = "https://w3id.org/linkml/";
    private static final Set<String> EXTENSION_STRING_TYPES = Set.of(
            XSD + "string", XSD + "normalizedString", XSD + "token");
    private static final Set<String> EXTENSION_INTEGER_TYPES = Set.of(
            XSD + "integer", XSD + "int", XSD + "long", XSD + "short", XSD + "byte");
    private static final Set<String> EXTENSION_DOUBLE_TYPES = Set.of(XSD + "double", XSD + "float");
    private static final Set<String> EXTENSION_IDENTIFIER_TYPES = Set.of(
            LINKML + "Uriorcurie", LINKML + "uriOrCurie");
    private static final Set<String> EXTENSION_DEFINITION_KEYS = Set.of(
            "slot_name", "property", "type_hint");
    private static final Map<String, String> STANDARD_PREFIXES = Map.of(
            "skos", "http://www.w3.org/2004/02/skos/core#",
            "owl", "http://www.w3.org/2002/07/owl#",
            "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
            "rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "oboInOwl", "http://www.geneontology.org/formats/oboInOwl#",
            "sssom", "https://w3id.org/sssom/",
            "semapv", "https://w3id.org/semapv/vocab/");

    private SssomValidator() {
    }

    public static Report validate(SssomDocument document, SssomValidationPolicy policy,
            SssomEntityIndex entities) {
        if (document == null) throw new IllegalArgumentException("SSSOM document is required");
        SssomValidationPolicy rules = policy == null
                ? SssomValidationPolicy.structural() : policy;
        SssomEntityIndex entityIndex = entities == null
                ? SssomEntityIndex.unavailable() : entities;
        SssomDocument canonical = document.canonical(rules.approvedPrefixes());
        Set<String> rowDefinedColumns = canonical.rowDefinedColumns();
        Findings findings = new Findings();
        Map<String, String> prefixes = prefixes(canonical, rules, findings);
        Map<String, List<MappingRecord>> byId = new LinkedHashMap<>();
        List<Edge> directional = new ArrayList<>();

        validateMetadata(canonical, prefixes, findings);
        validateExtensions(canonical, prefixes, findings);
        if (!entityIndex.available() && requiresEntityIndex(rules)) {
            findings.error("entity_index_unavailable", null, null,
                    "Required missing/deprecated entity validation has no captured entity index.");
        }
        for (MappingRecord record : canonical.records()) {
            byId.computeIfAbsent(record.mappingId(), ignored -> new ArrayList<>()).add(record);
            validateRecord(canonical, rowDefinedColumns, record, rules, entityIndex,
                    prefixes, directional, findings);
        }
        validateIdCollisions(byId, findings);
        validateExactConflicts(canonical, rowDefinedColumns, prefixes, findings);
        validateManyToOne(canonical, rowDefinedColumns, rules, prefixes, findings);
        validateCycles(directional, rules, findings);
        List<SssomFinding> ordered = findings.values.stream()
                .sorted(Comparator.comparing(SssomFinding::severity)
                        .thenComparing(SssomFinding::code)
                        .thenComparing(finding -> nullToEmpty(finding.mappingId()))
                        .thenComparing(finding -> nullToEmpty(finding.column()))
                        .thenComparing(SssomFinding::message))
                .toList();
        return new Report(canonical, ordered, findings.truncated);
    }

    private static void validateRecord(SssomDocument document, Set<String> rowDefinedColumns,
            MappingRecord record,
            SssomValidationPolicy rules,
            SssomEntityIndex entities, Map<String, String> prefixes, List<Edge> directional,
            Findings findings) {
        String id = record.mappingId();
        if (id.isBlank() || expand(id, prefixes, true) == null) {
            findings.error("mapping_id_invalid", id, "mapping_id",
                    "mapping_id must be an absolute IRI or a CURIE with a declared prefix.");
        }
        String subject = validateEndpoint(document, rowDefinedColumns, record, true, prefixes, findings);
        String object = validateEndpoint(document, rowDefinedColumns, record, false, prefixes, findings);
        String predicate = predicate(record.predicateId(), prefixes);
        if (record.predicateId().isBlank() || predicate == null) {
            findings.error("predicate_id_invalid", id, "predicate_id",
                    "predicate_id is required and must be an absolute IRI or supported CURIE.");
        } else if (rules.restrictPredicates()
                && !predicateAllowed(record.predicateId(), predicate, rules, prefixes)) {
            findings.error("predicate_not_allowed", id, "predicate_id",
                    "Mapping predicate is not in the SSSOM 1.0 vocabulary or policy allowlist.");
        }
        validateJustification(record, prefixes, findings);
        validateStandardFields(document, rowDefinedColumns, record, prefixes, findings);
        validateConfidence(record, findings);
        validateDate(record, "mapping_date", findings);
        validateDate(record, "publication_date", findings);
        validateFormulaCells(record, findings);
        if (entities.available()) {
            if (!specialEndpoint(subject)) {
                validateEntity(subject, record.subjectId(), true, record, entities, rules, findings);
            }
            if (!specialEndpoint(object)) {
                validateEntity(object, record.objectId(), false, record, entities, rules, findings);
            }
        }
        validateSourceAndLicense(document, rowDefinedColumns, record, rules, prefixes, findings);
        if (subject != null && object != null && !NO_TERM_FOUND.equals(subject)
                && !NO_TERM_FOUND.equals(object) && !negated(record)) {
            String compact = compactPredicate(record.predicateId(), predicate);
            if (("skos:broadMatch".equals(compact)
                    || "semapv:crossSpeciesBroadMatch".equals(compact))
                    && !subject.equals(object)) {
                directional.add(new Edge(subject, object, compact, id));
            } else if (("skos:narrowMatch".equals(compact)
                    || "semapv:crossSpeciesNarrowMatch".equals(compact))
                    && !subject.equals(object)) {
                directional.add(new Edge(object, subject, compact, id));
            }
        }
    }

    private static String validateEndpoint(SssomDocument document, Set<String> rowDefinedColumns,
            MappingRecord record,
            boolean subject, Map<String, String> prefixes, Findings findings) {
        String side = subject ? "subject" : "object";
        String column = side + "_id";
        String type = effective(document, rowDefinedColumns, record, side + "_type");
        if (isLiteralType(type)) {
            if (record.value(side + "_label").isBlank()) {
                findings.error(side + "_label_missing", record.mappingId(), side + "_label",
                        side + "_label is required for an rdfs literal mapping endpoint.");
            }
            return LITERAL_KEY + record.value(side + "_label").trim();
        }
        String raw = record.value(column).trim();
        if (raw.isEmpty()) {
            findings.error(column + "_missing", record.mappingId(), column,
                    column + " is required.");
            return null;
        }
        String expanded = expand(raw, prefixes, false);
        if (expanded == null) {
            findings.error(column + "_invalid", record.mappingId(), column,
                    column + " must be an absolute IRI or use a declared prefix.");
        }
        return expanded;
    }

    private static void validateJustification(MappingRecord record, Map<String, String> prefixes,
            Findings findings) {
        String value = record.value("mapping_justification").trim();
        String expanded = expand(value, prefixes, false);
        boolean accepted = JUSTIFICATIONS.contains(value) || JUSTIFICATIONS.stream()
                .map(justification -> expand(justification, prefixes, false))
                .anyMatch(candidate -> candidate != null && candidate.equals(expanded));
        if (!accepted) {
            findings.error("mapping_justification_invalid", record.mappingId(),
                    "mapping_justification", "mapping_justification must be a SSSOM 1.0 SEMAPV value.");
        }
    }

    private static void validateStandardFields(SssomDocument document,
            Set<String> rowDefinedColumns, MappingRecord record, Map<String, String> prefixes,
            Findings findings) {
        for (String column : List.of("subject_type", "object_type")) {
            String value = effective(document, rowDefinedColumns, record, column);
            if (!value.isEmpty() && !ENTITY_TYPES.contains(value)) {
                findings.error("entity_type_invalid", record.mappingId(), column,
                        column + " is not a SSSOM 1.0 entity type.");
            }
        }
        String modifier = record.value("predicate_modifier").trim();
        if (!modifier.isEmpty() && !"Not".equals(modifier)) {
            findings.error("predicate_modifier_invalid", record.mappingId(), "predicate_modifier",
                    "predicate_modifier must be empty or Not.");
        }
        String cardinality = record.value("mapping_cardinality").trim();
        if (!cardinality.isEmpty() && !CARDINALITIES.contains(cardinality)) {
            findings.error("mapping_cardinality_invalid", record.mappingId(), "mapping_cardinality",
                    "mapping_cardinality is not a SSSOM 1.0 cardinality value.");
        }
        validateUnitInterval(record, "similarity_score", findings);
        validateAbsoluteField(record, "license", findings);
        validateAbsoluteField(record, "mapping_provider", findings);
        for (String column : List.of("subject_source", "object_source", "mapping_source",
                "issue_tracker_item")) {
            validateReferenceField(record, column, prefixes, false, findings);
        }
        for (String column : List.of("author_id", "reviewer_id", "creator_id", "curation_rule",
                "subject_match_field", "object_match_field", "subject_preprocessing",
                "object_preprocessing")) {
            validateReferenceField(record, column, prefixes, true, findings);
        }
    }

    private static void validateMetadata(SssomDocument document, Map<String, String> prefixes,
            Findings findings) {
        String setId = document.metadataText("mapping_set_id").trim();
        if (setId.isEmpty() || !validAbsoluteIri(setId)) {
            findings.error("mapping_set_id_invalid", null, "mapping_set_id",
                    "mapping_set_id is required and must be an absolute IRI.");
        }
        String license = document.metadataText("license").trim();
        if (license.isEmpty() || !validAbsoluteIri(license)) {
            findings.error("mapping_set_license_invalid", null, "license",
                    "The SSSOM mapping set requires an absolute license IRI.");
        }
        Object version = document.metadata().get("sssom_version");
        if (version != null && !"1.0".equals(String.valueOf(version))) {
            findings.error("sssom_version_unsupported", null, "sssom_version",
                    "Only SSSOM version 1.0 is supported.");
        }
        validateMetadataAbsolute(document, "mapping_provider", findings);
        for (String column : List.of("subject_source", "object_source")) {
            validateMetadataReference(document, column, prefixes, findings);
        }
        for (String column : List.of("subject_type", "object_type")) {
            String value = metadataString(document, column, findings).trim();
            if (!value.isEmpty() && !ENTITY_TYPES.contains(value)) {
                findings.error("entity_type_invalid", null, column,
                        column + " metadata is not a SSSOM 1.0 entity type.");
            }
        }
        for (String column : List.of("creator_id", "subject_match_field", "object_match_field",
                "subject_preprocessing", "object_preprocessing")) {
            validateMetadataReferences(document, column, prefixes, findings);
        }
        validateMetadataUris(document, "mapping_set_source", findings);
        validateMetadataAbsolute(document, "issue_tracker", findings);
        for (String column : List.of("mapping_set_version", "mapping_set_title",
                "mapping_set_description", "subject_source_version", "object_source_version",
                "mapping_tool", "mapping_tool_version", "other", "comment")) {
            metadataString(document, column, findings);
        }
        for (String column : List.of("creator_label", "see_also")) {
            validateMetadataStrings(document, column, findings);
        }
        for (String column : List.of("mapping_date", "publication_date")) {
            String value = metadataString(document, column, findings).trim();
            if (!value.isEmpty() && !validDate(value)) {
                findings.error("date_invalid", null, column,
                        column + " metadata must be an ISO-8601 calendar date.");
            }
        }
    }

    private static void validateExtensions(SssomDocument document, Map<String, String> prefixes,
            Findings findings) {
        Object authored = document.metadata().get("extension_definitions");
        if (authored == null) return;
        if (!(authored instanceof List<?> definitions)) {
            findings.error("extension_definitions_invalid", null, "extension_definitions",
                    "extension_definitions must be a list of definition maps.");
            return;
        }
        Map<String, ExtensionDefinition> bySlot = new LinkedHashMap<>();
        Set<String> properties = new LinkedHashSet<>();
        for (Object item : definitions) {
            if (!(item instanceof Map<?, ?> definition)) {
                findings.error("extension_definitions_invalid", null, "extension_definitions",
                        "Each extension definition must be a map.");
                continue;
            }
            if (definition.keySet().stream().anyMatch(key -> !(key instanceof String))) {
                findings.error("extension_definitions_invalid", null, "extension_definitions",
                        "Extension definition keys must be strings.");
                continue;
            }
            if (!EXTENSION_DEFINITION_KEYS.containsAll(definition.keySet())) {
                findings.warning("extension_definition_ignored", null, "extension_definitions",
                        "Extension definition contains unsupported keys and was ignored.");
                continue;
            }
            if (definition.values().stream().anyMatch(value -> !(value instanceof String))) {
                findings.error("extension_definitions_invalid", null, "extension_definitions",
                        "Supported extension definition values must be strings.");
                continue;
            }
            String slot = stringEntry(definition, "slot_name");
            String property = stringEntry(definition, "property");
            String typeHint = firstNonBlank(stringEntry(definition, "type_hint"), "xsd:string");
            String expandedProperty = expand(property, prefixes, false);
            String expandedType = expand(typeHint, prefixes, false);
            if (slot == null || !slot.matches("^[A-Za-z][A-Za-z0-9._-]{0,127}$")
                    || SssomDocument.STANDARD_COLUMNS.contains(slot)
                    || expandedProperty == null || expandedType == null) {
                findings.error("extension_definitions_invalid", null, "extension_definitions",
                        "Extension definitions require a unique non-standard slot_name and valid "
                                + "property/type_hint identifiers.");
                continue;
            }
            ExtensionDefinition parsed = new ExtensionDefinition(slot, expandedProperty, expandedType);
            if (bySlot.putIfAbsent(slot, parsed) != null || !properties.add(expandedProperty)) {
                findings.error("extension_definitions_invalid", null, "extension_definitions",
                        "Extension slot names and properties must be unique.");
            } else if (!supportedExtensionType(expandedType)) {
                findings.warning("extension_type_unsupported", null, slot,
                        "Extension type_hint is preserved but cannot be lexically validated.");
            }
        }
        for (ExtensionDefinition definition : bySlot.values()) {
            Object metadataValue = document.metadata().get(definition.slot);
            if (metadataValue != null && !extensionValueValid(metadataValue, definition.type, prefixes)) {
                findings.error("extension_value_invalid", null, definition.slot,
                        "Extension metadata does not match its declared type_hint.");
            }
            for (MappingRecord record : document.records()) {
                String value = record.value(definition.slot);
                if (!value.isEmpty() && !extensionValueValid(value, definition.type, prefixes)) {
                    findings.error("extension_value_invalid", record.mappingId(), definition.slot,
                            "Extension cell does not match its declared type_hint.");
                }
            }
        }
    }

    private static String stringEntry(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static boolean extensionValueValid(Object value, String type,
            Map<String, String> prefixes) {
        if (EXTENSION_STRING_TYPES.contains(type)) {
            return !(value instanceof Map<?, ?>) && !(value instanceof List<?>);
        }
        String text = value instanceof String string ? string.trim() : String.valueOf(value);
        try {
            if (EXTENSION_INTEGER_TYPES.contains(type)) {
                new BigInteger(text);
                return true;
            }
            if ((XSD + "decimal").equals(type)) {
                new BigDecimal(text);
                return true;
            }
            if (EXTENSION_DOUBLE_TYPES.contains(type)) {
                return "INF".equals(text) || "-INF".equals(text) || "NaN".equals(text)
                        || Double.isFinite(Double.parseDouble(text));
            }
            if ((XSD + "boolean").equals(type)) {
                return value instanceof Boolean || Set.of("true", "false", "1", "0").contains(text);
            }
            if ((XSD + "date").equals(type)) {
                LocalDate.parse(text);
                return true;
            }
            if ((XSD + "dateTime").equals(type) || (XSD + "datetime").equals(type)) {
                DateTimeFormatter.ISO_DATE_TIME.parse(text);
                return true;
            }
            if (EXTENSION_IDENTIFIER_TYPES.contains(type)) return expand(text, prefixes, false) != null;
            if ((XSD + "anyURI").equals(type)) {
                URI uri = URI.create(text);
                return !uri.toString().isBlank();
            }
            return true;
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            return false;
        }
    }

    private static boolean supportedExtensionType(String type) {
        return EXTENSION_STRING_TYPES.contains(type) || EXTENSION_INTEGER_TYPES.contains(type)
                || EXTENSION_DOUBLE_TYPES.contains(type) || EXTENSION_IDENTIFIER_TYPES.contains(type)
                || Set.of(XSD + "decimal", XSD + "boolean", XSD + "date", XSD + "dateTime",
                        XSD + "datetime", XSD + "anyURI").contains(type);
    }

    private static void validateConfidence(MappingRecord record, Findings findings) {
        validateUnitInterval(record, "confidence", findings);
    }

    private static void validateUnitInterval(MappingRecord record, String column,
            Findings findings) {
        String value = record.value(column).trim();
        if (value.isEmpty()) return;
        try {
            BigDecimal confidence = new BigDecimal(value);
            if (confidence.compareTo(BigDecimal.ZERO) < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException invalid) {
            findings.error(column + "_invalid", record.mappingId(), column,
                    column + " must be a decimal between 0 and 1.");
        }
    }

    private static void validateDate(MappingRecord record, String column, Findings findings) {
        String value = record.value(column).trim();
        if (value.isEmpty()) return;
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            findings.error("date_invalid", record.mappingId(), column,
                    column + " must be an ISO-8601 calendar date.");
        }
    }

    private static boolean validDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    private static void validateMetadataAbsolute(SssomDocument document, String column,
            Findings findings) {
        String value = metadataString(document, column, findings).trim();
        if (!value.isEmpty() && !validAbsoluteIri(value)) {
            findings.error(column + "_invalid", null, column,
                    column + " metadata must be an absolute IRI.");
        }
    }

    private static void validateMetadataReference(SssomDocument document, String column,
            Map<String, String> prefixes, Findings findings) {
        String value = metadataString(document, column, findings).trim();
        if (!value.isEmpty() && expand(value, prefixes, false) == null) {
            findings.error(column + "_invalid", null, column,
                    column + " metadata must be an absolute IRI or declared CURIE.");
        }
    }

    private static void validateMetadataReferences(SssomDocument document, String column,
            Map<String, String> prefixes, Findings findings) {
        Object authored = document.metadata().get(column);
        if (authored == null) return;
        List<?> values = authored instanceof List<?> list ? list : List.of(authored);
        for (Object value : values) {
            if (!(value instanceof String text) || expand(text, prefixes, false) == null) {
                findings.error(column + "_invalid", null, column,
                        column + " metadata must contain absolute IRIs or declared CURIEs.");
                return;
            }
        }
    }

    private static void validateMetadataUris(SssomDocument document, String column,
            Findings findings) {
        Object authored = document.metadata().get(column);
        if (authored == null) return;
        List<?> values = authored instanceof List<?> list ? list : List.of(authored);
        for (Object value : values) {
            if (!(value instanceof String text) || !validAbsoluteIri(text)) {
                findings.error(column + "_invalid", null, column,
                        column + " metadata must contain absolute IRIs.");
                return;
            }
        }
    }

    private static void validateMetadataStrings(SssomDocument document, String column,
            Findings findings) {
        Object authored = document.metadata().get(column);
        if (authored == null) return;
        List<?> values = authored instanceof List<?> list ? list : List.of(authored);
        if (values.stream().anyMatch(value -> !(value instanceof String))) {
            findings.error("metadata_type_invalid", null, column,
                    column + " metadata must be a string or list of strings.");
        }
    }

    private static String metadataString(SssomDocument document, String column,
            Findings findings) {
        Object value = document.metadata().get(column);
        if (value == null) return "";
        if (value instanceof String text) return text;
        findings.error("metadata_type_invalid", null, column,
                column + " metadata must be a scalar string.");
        return "";
    }

    private static void validateAbsoluteField(MappingRecord record, String column,
            Findings findings) {
        String value = record.value(column).trim();
        if (!value.isEmpty() && !validAbsoluteIri(value)) {
            findings.error(column + "_invalid", record.mappingId(), column,
                    column + " must be an absolute IRI.");
        }
    }

    private static void validateReferenceField(MappingRecord record, String column,
            Map<String, String> prefixes, boolean multi, Findings findings) {
        String value = record.value(column).trim();
        if (value.isEmpty()) return;
        List<String> values = multi ? SssomListValues.decode(value) : List.of(value);
        for (String item : values) {
            if (expand(item.trim(), prefixes, false) == null) {
                findings.error(column + "_invalid", record.mappingId(), column,
                        column + " must contain absolute IRIs or CURIEs with declared prefixes.");
                return;
            }
        }
    }

    private static void validateFormulaCells(MappingRecord record, Findings findings) {
        record.cells().forEach((column, value) -> {
            String trimmed = value.stripLeading();
            if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
                findings.warning("spreadsheet_formula", record.mappingId(), column,
                        "Cell begins with a spreadsheet formula marker; standards-preserving output "
                                + "keeps it unchanged.");
            }
        });
    }

    private static void validateEntity(String expanded, String raw, boolean source,
            MappingRecord record, SssomEntityIndex entities, SssomValidationPolicy rules,
            Findings findings) {
        if (expanded == null) return;
        String missing = source ? "missing_source" : "missing_target";
        String deprecated = source ? "deprecated_source" : "deprecated_target";
        String column = source ? "subject_id" : "object_id";
        if (!entities.present().contains(expanded) && !entities.deprecated().contains(expanded)) {
            governed(findings, rules, missing, record.mappingId(), column,
                    "Mapped entity is not present in the captured ontology closure: " + raw);
        } else if (entities.deprecated().contains(expanded)) {
            governed(findings, rules, deprecated, record.mappingId(), column,
                    "Mapped entity is deprecated: " + raw);
        }
    }

    private static void validateSourceAndLicense(SssomDocument document,
            Set<String> rowDefinedColumns, MappingRecord record,
            SssomValidationPolicy rules, Map<String, String> prefixes, Findings findings) {
        String source = firstNonBlank(effective(document, rowDefinedColumns, record, "mapping_source"),
                effective(document, rowDefinedColumns, record, "mapping_provider"));
        if (!rules.allowedSources().isEmpty()
                && (source == null || !matchesAny(rules.allowedSources(), source, prefixes))) {
            governed(findings, rules, "source_not_allowed", record.mappingId(), "mapping_source",
                    "Mapping source is not in the project allowlist.");
        }
        String license = firstNonBlank(record.value("license"), document.metadataText("license"));
        if (license == null) license = "";
        if (rules.requireLicense() && license.isEmpty()) {
            findings.error("license_not_allowed", record.mappingId(), "license",
                    "Mapping license is required by project policy.");
        } else if (!license.isEmpty() && !rules.allowedLicenses().isEmpty()
                && !matchesAny(rules.allowedLicenses(), license, prefixes)) {
            governed(findings, rules, "license_not_allowed", record.mappingId(), "license",
                    "Mapping license is absent or not in the project allowlist.");
        }
    }

    private static void validateIdCollisions(Map<String, List<MappingRecord>> byId,
            Findings findings) {
        byId.forEach((id, records) -> {
            MappingRecord first = records.get(0);
            if (records.stream().skip(1).anyMatch(record -> !record.equals(first))) {
                findings.error("mapping_id_conflict", id, "mapping_id",
                        "The same mapping_id names records with different content.");
            }
        });
    }

    private static void validateExactConflicts(SssomDocument document,
            Set<String> rowDefinedColumns, Map<String, String> prefixes, Findings findings) {
        Map<String, Set<String>> objectsBySubject = new LinkedHashMap<>();
        Map<String, String> firstId = new LinkedHashMap<>();
        for (MappingRecord record : document.records()) {
            if (negated(record)) continue;
            String predicate = compactPredicate(record.predicateId(),
                    predicate(record.predicateId(), prefixes));
            if (!EXACT.contains(predicate)) continue;
            String subject = endpointKey(document, rowDefinedColumns, record, true, prefixes);
            String object = endpointKey(document, rowDefinedColumns, record, false, prefixes);
            if (subject == null || object == null || NO_TERM_FOUND.equals(subject)
                    || NO_TERM_FOUND.equals(object) || subject.equals(object)) continue;
            objectsBySubject.computeIfAbsent(subject, ignored -> new LinkedHashSet<>()).add(object);
            firstId.putIfAbsent(subject, record.mappingId());
        }
        objectsBySubject.forEach((term, mapped) -> {
            if (mapped.size() > 1) {
                findings.error("conflicting_exact_mapping", firstId.get(term),
                        "predicate_id", "Exact mapping component assigns one term to multiple terms.");
            }
        });
    }

    private static void validateManyToOne(SssomDocument document, Set<String> rowDefinedColumns,
            SssomValidationPolicy rules, Map<String, String> prefixes, Findings findings) {
        for (PreparedRule prepared : prepare(rules.manyToOneRules(), prefixes)) {
            Map<String, Set<String>> subjectsByObject = new LinkedHashMap<>();
            Map<String, String> firstId = new LinkedHashMap<>();
            for (MappingRecord record : document.records()) {
                if (negated(record)) continue;
                String predicate = compactPredicate(record.predicateId(),
                        predicate(record.predicateId(), prefixes));
                if (!predicateEquivalent(prepared.predicate, record.predicateId(), predicate, prefixes)
                        || !scopeMatches(document, rowDefinedColumns, record, prepared, prefixes)) {
                    continue;
                }
                String subject = endpointKey(document, rowDefinedColumns, record, true, prefixes);
                String object = endpointKey(document, rowDefinedColumns, record, false, prefixes);
                if (subject == null || object == null || NO_TERM_FOUND.equals(subject)
                        || NO_TERM_FOUND.equals(object)) continue;
                subjectsByObject.computeIfAbsent(object, ignored -> new LinkedHashSet<>()).add(subject);
                firstId.putIfAbsent(object, record.mappingId());
            }
            subjectsByObject.forEach((object, subjects) -> {
                if (subjects.size() > 1) {
                    findings.error("many_to_one_mapping", firstId.get(object), "object_id",
                            "Policy-scoped mapping rule prohibits multiple subjects for one target.");
                }
            });
        }
    }

    private static boolean scopeMatches(SssomDocument document, Set<String> rowDefinedColumns,
            MappingRecord record, PreparedRule rule, Map<String, String> prefixes) {
        return matchesPrepared(rule.subjectOntologies,
                    effective(document, rowDefinedColumns, record, "subject_source"), prefixes)
                && matchesPrepared(rule.subjectProviders,
                    effective(document, rowDefinedColumns, record, "mapping_provider"), prefixes)
                && matchesPrepared(rule.targetOntologies,
                    effective(document, rowDefinedColumns, record, "object_source"), prefixes);
    }

    private static void validateCycles(List<Edge> edges, SssomValidationPolicy rules,
            Findings findings) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        for (Edge edge : edges) {
            graph.computeIfAbsent(edge.from, ignored -> new LinkedHashSet<>()).add(edge.to);
            graph.computeIfAbsent(edge.to, ignored -> new LinkedHashSet<>());
            reverse.computeIfAbsent(edge.to, ignored -> new LinkedHashSet<>()).add(edge.from);
            reverse.computeIfAbsent(edge.from, ignored -> new LinkedHashSet<>());
        }
        Set<String> visited = new HashSet<>();
        Deque<String> order = new ArrayDeque<>();
        for (String node : graph.keySet()) finishIterative(node, graph, visited, order);
        visited.clear();
        Map<String, Integer> componentByNode = new LinkedHashMap<>();
        List<Integer> componentSizes = new ArrayList<>();
        while (!order.isEmpty()) {
            Set<String> component = new LinkedHashSet<>();
            collectIterative(order.pop(), reverse, visited, component);
            int index = componentSizes.size();
            componentSizes.add(component.size());
            component.forEach(node -> componentByNode.put(node, index));
        }
        Map<Integer, List<Edge>> internalEdges = new LinkedHashMap<>();
        for (Edge edge : edges) {
            Integer from = componentByNode.get(edge.from);
            Integer to = componentByNode.get(edge.to);
            if (from != null && from.equals(to) && componentSizes.get(from) >= 2) {
                internalEdges.computeIfAbsent(from, ignored -> new ArrayList<>()).add(edge);
            }
        }
        for (Map.Entry<Integer, List<Edge>> entry : internalEdges.entrySet()) {
            String severity = cycleSeverity(entry.getValue(), rules);
            if (severity != null) {
                String id = entry.getValue().stream().map(Edge::mappingId)
                        .sorted().findFirst().orElse(null);
                findings.add(severity, "mapping_cycle", id, "predicate_id",
                        "Directional broad/narrow mappings contain a cycle across "
                                + componentSizes.get(entry.getKey()) + " distinct terms.");
            }
        }
    }

    private static String cycleSeverity(List<Edge> edges, SssomValidationPolicy rules) {
        boolean warning = false;
        for (Edge edge : edges) {
            String configured = rules.directionalCyclePolicy()
                    .getOrDefault(edge.predicate, "error");
            if ("error".equals(configured)) return "error";
            if ("warning".equals(configured)) warning = true;
        }
        return warning ? "warning" : null;
    }

    private static void finishIterative(String node, Map<String, Set<String>> graph,
            Set<String> visited,
            Deque<String> order) {
        if (!visited.add(node)) return;
        Deque<Traversal> stack = new ArrayDeque<>();
        stack.push(new Traversal(node, graph.getOrDefault(node, Set.of()).iterator()));
        while (!stack.isEmpty()) {
            Traversal current = stack.peek();
            if (current.next.hasNext()) {
                String next = current.next.next();
                if (visited.add(next)) {
                    stack.push(new Traversal(next,
                            graph.getOrDefault(next, Set.of()).iterator()));
                }
            } else {
                stack.pop();
                order.push(current.node);
            }
        }
    }

    private static void collectIterative(String node, Map<String, Set<String>> graph,
            Set<String> visited,
            Set<String> component) {
        if (!visited.add(node)) return;
        Deque<String> stack = new ArrayDeque<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            component.add(current);
            for (String next : graph.getOrDefault(current, Set.of())) {
                if (visited.add(next)) stack.push(next);
            }
        }
    }

    private static Map<String, String> prefixes(SssomDocument document,
            SssomValidationPolicy rules, Findings findings) {
        Map<String, String> result = new LinkedHashMap<>(SssomParser.BUILTIN_PREFIXES);
        rules.approvedPrefixes().forEach((prefix, iri) -> mergePrefix(
                result, prefix, iri, "project-approved", findings));
        document.prefixMap().forEach((prefix, iri) -> {
            mergePrefix(result, prefix, iri, "SSSOM", findings);
        });
        return Collections.unmodifiableMap(result);
    }

    private static void mergePrefix(Map<String, String> prefixes, String prefix, String iri,
            String source, Findings findings) {
        String previous = prefixes.putIfAbsent(prefix, iri);
        if (previous != null && !previous.equals(iri)) {
            findings.error("prefix_conflict", null, null,
                    source + " prefix conflicts with an existing or built-in prefix: " + prefix);
        }
    }

    private static String expand(String value, Map<String, String> prefixes, boolean mappingId) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if ("sssom:NoTermFound".equals(trimmed)) return NO_TERM_FOUND;
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) return null;
        String scheme = trimmed.substring(0, colon);
        String base = prefixes.get(scheme);
        if (base != null) {
            String expanded = base + trimmed.substring(colon + 1);
            return validAbsoluteIri(expanded) ? expanded : null;
        }

        // CURIEs and absolute IRIs share the same surface syntax. Fail closed for an
        // undeclared CURIE-like scheme instead of silently treating it as an IRI.
        boolean unambiguousAbsolute = trimmed.regionMatches(true, colon + 1, "//", 0, 2)
                || OPAQUE_IRI_SCHEMES.contains(scheme.toLowerCase(java.util.Locale.ROOT));
        if (!unambiguousAbsolute) return null;
        try {
            URI uri = URI.create(trimmed);
            if (uri.isAbsolute() && (!mappingId || uri.getScheme() != null)
                    && validAbsoluteIri(trimmed)) return trimmed;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private static String predicate(String raw, Map<String, String> prefixes) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        return expand(trimmed, prefixes, false);
    }

    private static boolean predicateAllowed(String raw, String expanded,
            SssomValidationPolicy rules, Map<String, String> prefixes) {
        String trimmed = raw.trim();
        for (String base : BASE_PREDICATES) {
            int colon = base.indexOf(':');
            String namespace = colon > 0 ? STANDARD_PREFIXES.get(base.substring(0, colon)) : null;
            if (namespace != null && expanded.equals(namespace + base.substring(colon + 1))) return true;
        }
        for (String allowed : rules.allowedPredicates()) {
            if (allowed.equals(trimmed) || allowed.equals(expanded)
                    || expanded.equals(expand(allowed, prefixes, false))) return true;
        }
        return false;
    }

    private static String compactPredicate(String raw, String expanded) {
        if (raw != null && BASE_PREDICATES.contains(raw.trim())) return raw.trim();
        if (expanded == null) return raw;
        for (Map.Entry<String, String> prefix : STANDARD_PREFIXES.entrySet()) {
            if (expanded.startsWith(prefix.getValue())) {
                return prefix.getKey() + ":" + expanded.substring(prefix.getValue().length());
            }
        }
        return expanded;
    }

    private static void governed(Findings findings, SssomValidationPolicy rules, String code,
            String mappingId, String column, String message) {
        findings.add(rules.requiredFindings().contains(code) ? "error" : "warning",
                code, mappingId, column, message);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String effective(SssomDocument document, Set<String> rowDefinedColumns,
            MappingRecord record, String column) {
        String row = record.value(column).trim();
        if (!row.isEmpty()) return row;
        return !SssomDocument.PROPAGATABLE_COLUMNS.contains(column)
                || rowDefinedColumns.contains(column)
                ? "" : document.metadataText(column).trim();
    }

    private static boolean matchesAny(Set<String> expected, String actual,
            Map<String, String> prefixes) {
        if (expected.isEmpty()) return true;
        for (String candidate : expected) {
            if (referenceEquals(candidate, actual, prefixes)) return true;
        }
        return false;
    }

    private static boolean referenceEquals(String first, String second,
            Map<String, String> prefixes) {
        if (first == null || second == null) return false;
        String left = first.trim();
        String right = second.trim();
        if (left.equals(right)) return true;
        String leftExpanded = expand(left, prefixes, false);
        String rightExpanded = expand(right, prefixes, false);
        return leftExpanded != null && leftExpanded.equals(rightExpanded);
    }

    private static boolean predicateEquivalent(String expected, String raw, String compact,
            Map<String, String> prefixes) {
        if (expected.equals(raw) || expected.equals(compact)) return true;
        String expectedPredicate = compactPredicate(expected, predicate(expected, prefixes));
        return expectedPredicate != null && expectedPredicate.equals(compact);
    }

    private static List<PreparedRule> prepare(
            List<SssomValidationPolicy.ManyToOneRule> rules, Map<String, String> prefixes) {
        List<PreparedRule> prepared = new ArrayList<>();
        for (SssomValidationPolicy.ManyToOneRule rule : rules) {
            prepared.add(new PreparedRule(normalize(rule.predicate(), prefixes),
                    normalize(rule.subjectOntologies(), prefixes),
                    normalize(rule.subjectProviders(), prefixes),
                    normalize(rule.targetOntologies(), prefixes)));
        }
        return prepared;
    }

    private static Set<String> normalize(Set<String> values, Map<String, String> prefixes) {
        Set<String> normalized = new HashSet<>();
        for (String value : values) normalized.add(normalize(value, prefixes));
        return normalized;
    }

    private static String normalize(String value, Map<String, String> prefixes) {
        String expanded = expand(value, prefixes, false);
        return expanded == null ? value.trim() : expanded;
    }

    private static boolean matchesPrepared(Set<String> expected, String actual,
            Map<String, String> prefixes) {
        return expected.isEmpty() || expected.contains(normalize(actual, prefixes));
    }

    private static String endpointKey(SssomDocument document, Set<String> rowDefinedColumns,
            MappingRecord record, boolean subject, Map<String, String> prefixes) {
        String side = subject ? "subject" : "object";
        String type = effective(document, rowDefinedColumns, record, side + "_type");
        if (isLiteralType(type)) return LITERAL_KEY + record.value(side + "_label").trim();
        return expand(record.value(side + "_id"), prefixes, false);
    }

    private static boolean isLiteralType(String type) {
        return "rdfs literal".equalsIgnoreCase(type) || "rdfs:Literal".equals(type);
    }

    private static boolean specialEndpoint(String key) {
        return key == null || key.startsWith(LITERAL_KEY) || NO_TERM_FOUND.equals(key);
    }

    private static boolean negated(MappingRecord record) {
        return "Not".equals(record.value("predicate_modifier").trim());
    }

    private static boolean requiresEntityIndex(SssomValidationPolicy policy) {
        return policy.requiredFindings().stream().anyMatch(code -> code.startsWith("missing_")
                || code.startsWith("deprecated_"));
    }

    private static boolean validAbsoluteIri(String value) {
        if (value == null || value.isBlank()) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) return false;
        }
        try {
            return URI.create(value).isAbsolute();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public record Report(SssomDocument document, List<SssomFinding> findings,
            boolean findingsTruncated) {
        public Report {
            findings = List.copyOf(findings);
        }

        public boolean valid() {
            return !findingsTruncated
                    && findings.stream().noneMatch(finding -> "error".equals(finding.severity()));
        }

        public long errorCount() {
            return findings.stream().filter(finding -> "error".equals(finding.severity())).count();
        }

        public long warningCount() {
            return findings.stream().filter(finding -> "warning".equals(finding.severity())).count();
        }
    }

    private record Edge(String from, String to, String predicate, String mappingId) { }

    private record PreparedRule(String predicate, Set<String> subjectOntologies, Set<String> subjectProviders,
            Set<String> targetOntologies) { }

    private record ExtensionDefinition(String slot, String property, String type) { }

    private static final class Traversal {
        final String node;
        final Iterator<String> next;

        Traversal(String node, Iterator<String> next) {
            this.node = node;
            this.next = next;
        }
    }

    private static final class Findings {
        final List<SssomFinding> values = new ArrayList<>();
        boolean truncated;

        void error(String code, String id, String column, String message) {
            add("error", code, id, column, message);
        }

        void warning(String code, String id, String column, String message) {
            add("warning", code, id, column, message);
        }

        void add(String severity, String code, String id, String column, String message) {
            if (values.size() >= MAX_FINDINGS) {
                truncated = true;
                return;
            }
            values.add(new SssomFinding(severity, code, id, column, message));
        }
    }
}
