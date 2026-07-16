package io.github.hakjuoh.protege_mcp.ro_crate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Bounded, network-free validation of the portable ontology project profile. */
public final class RoCrateProjectProfileValidator {

    public static final long MAX_BYTES = 4L * 1024L * 1024L;
    public static final int MAX_ENTITIES = 20_000;

    private static final String PATH = "metadata";
    private static final Pattern ABSOLUTE_IRI = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:\\S+$");
    /** ISO 8601 calendar date (year precision permitted) with an optional T-separated time. */
    private static final Pattern ISO_8601_DATE = Pattern.compile(
            "^\\d{4}(-\\d{2}(-\\d{2}(T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})?)?)?)?$");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(100).maxStringLength((int) MAX_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private RoCrateProjectProfileValidator() {
    }

    /**
     * Detect one supported Recommendation from the crate's normative {@code @context} without
     * dereferencing it. Invalid, oversized, unknown, or ambiguous metadata has no inferred version;
     * callers can then apply their own default before running normal validation.
     */
    public static Optional<RoCrateVersion> detectVersion(Path manifest) {
        if (manifest == null) throw new IllegalArgumentException("manifest is required");
        try {
            if (Files.size(manifest) > MAX_BYTES) return Optional.empty();
            JsonNode document = JSON.readTree(Files.readAllBytes(manifest));
            if (document == null || !document.isObject()) return Optional.empty();
            Set<String> contexts = new LinkedHashSet<>(textValues(document.get("@context")));
            List<RoCrateVersion> matches = new ArrayList<>();
            for (RoCrateVersion candidate : RoCrateVersion.values()) {
                if (contexts.contains(candidate.context())) matches.add(candidate);
            }
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static RoCrateValidationResult validate(Path manifest, RoCrateVersion version,
            RoCrateProjectProfile profile) {
        if (manifest == null || version == null || profile == null) {
            throw new IllegalArgumentException("manifest, version, and profile are required");
        }
        List<RoCrateValidationIssue> issues = new ArrayList<>();
        byte[] bytes;
        try {
            long size = Files.size(manifest);
            if (size > MAX_BYTES) {
                issue(issues, "manifest_too_large", "RO-Crate metadata is " + size
                        + " bytes; the maximum is " + MAX_BYTES + ".");
                return new RoCrateValidationResult(issues);
            }
            bytes = Files.readAllBytes(manifest);
        } catch (IOException e) {
            issue(issues, "manifest_unreadable", "Could not read RO-Crate metadata: " + message(e));
            return new RoCrateValidationResult(issues);
        }

        JsonNode document;
        try {
            document = JSON.readTree(bytes);
        } catch (IOException | RuntimeException e) {
            issue(issues, "manifest_invalid_json", "RO-Crate metadata is not strict JSON: "
                    + message(e));
            return new RoCrateValidationResult(issues);
        }
        if (document == null || !document.isObject()) {
            issue(issues, "manifest_invalid", "RO-Crate metadata must be one JSON-LD object.");
            return new RoCrateValidationResult(issues);
        }
        Path metadataFileName = manifest.getFileName();
        if (metadataFileName == null
                || !version.metadataFile().equals(metadataFileName.toString())) {
            issue(issues, "metadata_filename", "RO-Crate " + version.version()
                    + " metadata must be stored as " + version.metadataFile() + ".");
        }
        validateContext(document.get("@context"), version, issues);
        JsonNode graph = document.get("@graph");
        if (graph == null || !graph.isArray()) {
            issue(issues, "graph_missing", "RO-Crate metadata must contain a flattened @graph array.");
            return new RoCrateValidationResult(issues);
        }
        if (graph.size() > MAX_ENTITIES) {
            issue(issues, "graph_too_large", "RO-Crate @graph has " + graph.size()
                    + " entities; the maximum is " + MAX_ENTITIES + ".");
            return new RoCrateValidationResult(issues);
        }

        Map<String, JsonNode> entities = index(graph, issues);
        validateDescriptor(entities, version, issues);
        validateRoot(entities, version, profile, issues);
        return new RoCrateValidationResult(issues);
    }

    /**
     * The profile validates the crate offline, so vocabulary meaning must be decidable from the
     * declared contexts alone: every entry must be a string, the selected Recommendation context
     * must be present, and no other supported Recommendation context may appear beside it. A
     * non-string entry (an inline term map could silently redefine core RO-Crate terms) or a
     * second version context (the crate would be ambiguous between Recommendations) is rejected
     * rather than passed on a guess.
     */
    private static void validateContext(JsonNode context, RoCrateVersion version,
            List<RoCrateValidationIssue> issues) {
        int entries = context == null || context.isNull() ? 0
                : context.isArray() ? context.size() : 1;
        List<String> texts = textValues(context);
        if (texts.size() != entries) {
            issue(issues, "context_entry_invalid",
                    "Every RO-Crate @context entry must be a non-empty string.");
        }
        if (!texts.contains(version.context())) {
            issue(issues, "context_missing", "RO-Crate @context must include "
                    + version.context() + ".");
        }
        for (RoCrateVersion other : RoCrateVersion.values()) {
            if (other != version && texts.contains(other.context())) {
                issue(issues, "context_ambiguous", "RO-Crate @context mixes " + version.context()
                        + " with " + other.context() + "; declare exactly one Recommendation.");
            }
        }
    }

    private static Map<String, JsonNode> index(JsonNode graph, List<RoCrateValidationIssue> issues) {
        Map<String, JsonNode> entities = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode entity : graph) {
            String path = PATH + ".@graph[" + index + "]";
            if (!entity.isObject()) {
                issues.add(new RoCrateValidationIssue("entity_invalid", path,
                        "Every RO-Crate @graph entry must be an object."));
                index++;
                continue;
            }
            String id = text(entity.get("@id"));
            if (id == null) {
                issues.add(new RoCrateValidationIssue("entity_id_missing", path,
                        "Every RO-Crate entity must have a non-empty @id."));
            } else if (entities.putIfAbsent(id, entity) != null) {
                issues.add(new RoCrateValidationIssue("entity_id_duplicate", path,
                        "RO-Crate entity @id is duplicated: " + id));
            }
            if (textValues(entity.get("@type")).isEmpty()) {
                issues.add(new RoCrateValidationIssue("entity_type_missing", path,
                        "Every RO-Crate entity must have a non-empty @type."));
            }
            index++;
        }
        return entities;
    }

    private static void validateDescriptor(Map<String, JsonNode> entities, RoCrateVersion version,
            List<RoCrateValidationIssue> issues) {
        JsonNode descriptor = require(entities, version.metadataFile(), "descriptor_missing", issues);
        if (descriptor == null) return;
        requireType(descriptor, "CreativeWork", "descriptor_type", issues);
        requireReference(descriptor.get("about"), "./", "descriptor_about",
                "RO-Crate metadata descriptor about must reference './'.", issues);
        requireReference(descriptor.get("conformsTo"), version.specification(),
                "descriptor_conformance", "RO-Crate metadata descriptor conformsTo must reference "
                        + version.specification() + ".", issues);
    }

    private static void validateRoot(Map<String, JsonNode> entities, RoCrateVersion version,
            RoCrateProjectProfile profile, List<RoCrateValidationIssue> issues) {
        JsonNode root = require(entities, "./", "root_missing", issues);
        if (root == null) return;
        requireType(root, "Dataset", "root_type", issues);
        requireText(root, "name", "root_name", issues);
        // Every supported Recommendation makes description, datePublished, and license root-entity
        // MUSTs; the descriptor asserts spec conformance, so the profile has to enforce them.
        requireText(root, "description", "root_description", issues);
        validateDatePublished(root, issues);
        JsonNode license = root.get("license");
        if (referenceIds(license).isEmpty() && textValues(license).isEmpty()) {
            issue(issues, "root_license", "RO-Crate root must declare license (a contextual entity "
                    + "reference or a textual statement).");
        }
        if (!textValues(root.get("identifier")).contains(profile.projectId())) {
            issue(issues, "project_id_mismatch", "RO-Crate root identifier must equal project id '"
                    + profile.projectId() + "'.");
        }

        Set<String> requiredProfiles = new LinkedHashSet<>();
        requiredProfiles.add(profile.profileIri());
        requiredProfiles.addAll(profile.additionalProfiles());
        Set<String> declaredProfiles = referenceIds(root.get("conformsTo"));
        for (String profileIri : requiredProfiles) {
            if (!ABSOLUTE_IRI.matcher(profileIri).matches()) {
                issue(issues, "profile_iri_relative",
                        "Declared profiles must be absolute IRIs: " + profileIri);
                continue;
            }
            if (!declaredProfiles.contains(profileIri)) {
                issue(issues, "profile_missing", "RO-Crate root conformsTo must reference profile "
                        + profileIri + ".");
                continue;
            }
            JsonNode entity = entities.get(profileIri);
            if (entity == null) {
                issue(issues, "profile_entity_missing",
                        "RO-Crate must describe each declared profile as a contextual entity: "
                                + profileIri);
            } else {
                // RO-Crate 1.2 introduced the W3C Profiles Vocabulary Profile class. Earlier
                // contexts use an ordinary CreativeWork contextual entity around dct:conformsTo.
                requireType(entity, version.formalProfiles() ? "Profile" : "CreativeWork",
                        "profile_entity_type", issues);
                requireText(entity, "name", "profile_entity_name", issues);
            }
        }

        requireReference(root.get("mainEntity"), profile.rootArtifact(), "main_entity",
                "RO-Crate root mainEntity must reference root artifact '"
                        + profile.rootArtifact() + "'.", issues);
        if (!referenceIds(root.get("hasPart")).contains(profile.rootArtifact())) {
            issue(issues, "root_artifact_not_listed", "RO-Crate root hasPart must list root artifact: "
                    + profile.rootArtifact());
        }

        JsonNode artifact = entities.get(profile.rootArtifact());
        if (artifact == null) {
            issue(issues, "root_artifact_entity_missing",
                    "RO-Crate must describe root artifact as a File entity: "
                            + profile.rootArtifact());
        } else {
            requireType(artifact, "File", "root_artifact_type", issues);
            requireText(artifact, "encodingFormat", "root_artifact_format", issues);
            requireReference(artifact.get("about"), profile.ontologyIri(), "root_artifact_about",
                    "RO-Crate root artifact about must reference the ontology IRI.", issues);
        }

        JsonNode ontology = entities.get(profile.ontologyIri());
        if (ontology == null) {
            issue(issues, "ontology_entity_missing",
                    "RO-Crate must describe the ontology IRI as a Dataset entity: "
                            + profile.ontologyIri());
        } else {
            requireType(ontology, "Dataset", "ontology_entity_type", issues);
            requireReference(ontology.get("conformsTo"), profile.ontologySpecificationIri(),
                    "ontology_conformance_missing",
                    "The ontology entity conformsTo must reference the ontology specification.", issues);
        }
    }

    private static void validateDatePublished(JsonNode root, List<RoCrateValidationIssue> issues) {
        JsonNode value = root.get("datePublished");
        List<String> dates = textValues(value);
        if (dates.isEmpty()) {
            issue(issues, "root_date_published",
                    "RO-Crate root must declare datePublished as an ISO 8601 string.");
            return;
        }
        // Raw cardinality, not just the textual survivors: ["2026-07-15", 42] is not one string.
        int entries = value.isArray() ? value.size() : 1;
        if (entries != 1 || dates.size() != 1 || !ISO_8601_DATE.matcher(dates.get(0)).matches()
                || !calendarValid(dates.get(0))) {
            issue(issues, "root_date_published_invalid",
                    "RO-Crate root datePublished must be one calendar-valid ISO 8601 date string: "
                            + dates);
        }
    }

    /** The shape regex admits out-of-range components (2026-99-99); resolve strictly. */
    private static boolean calendarValid(String value) {
        try {
            if (value.length() == 4) {
                Year.parse(value);
            } else if (value.length() == 7) {
                YearMonth.parse(value);
            } else if (value.indexOf('T') < 0) {
                LocalDate.parse(value);
            } else if (value.endsWith("Z") || value.lastIndexOf('+') > 7
                    || value.lastIndexOf('-') > 7) {
                OffsetDateTime.parse(value);
            } else {
                LocalDateTime.parse(value);
            }
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static JsonNode require(Map<String, JsonNode> entities, String id, String code,
            List<RoCrateValidationIssue> issues) {
        JsonNode entity = entities.get(id);
        if (entity == null) issue(issues, code, "RO-Crate @graph is missing required entity: " + id);
        return entity;
    }

    private static void requireType(JsonNode entity, String type, String code,
            List<RoCrateValidationIssue> issues) {
        if (!textValues(entity.get("@type")).contains(type)) {
            issue(issues, code, "RO-Crate entity " + text(entity.get("@id"))
                    + " must include @type " + type + ".");
        }
    }

    private static void requireText(JsonNode entity, String field, String code,
            List<RoCrateValidationIssue> issues) {
        if (textValues(entity.get(field)).isEmpty()) {
            issue(issues, code, "RO-Crate entity " + text(entity.get("@id"))
                    + " must declare " + field + ".");
        }
    }

    private static void requireReference(JsonNode value, String expected, String code,
            String message, List<RoCrateValidationIssue> issues) {
        if (!referenceIds(value).contains(expected)) issue(issues, code, message);
    }

    private static Set<String> referenceIds(JsonNode value) {
        if (value == null || value.isNull()) return Collections.emptySet();
        List<JsonNode> values = new ArrayList<>();
        if (value.isArray()) value.forEach(values::add); else values.add(value);
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode item : values) {
            if (item.isObject()) {
                String id = text(item.get("@id"));
                if (id != null) out.add(id);
            }
        }
        return out;
    }

    private static List<String> textValues(JsonNode value) {
        if (value == null || value.isNull()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> { String found = text(item); if (found != null) out.add(found); });
        } else {
            String found = text(value);
            if (found != null) out.add(found);
        }
        return out;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue() : null;
    }

    private static void issue(List<RoCrateValidationIssue> issues, String code, String message) {
        issues.add(new RoCrateValidationIssue(code, PATH, message));
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }
}
