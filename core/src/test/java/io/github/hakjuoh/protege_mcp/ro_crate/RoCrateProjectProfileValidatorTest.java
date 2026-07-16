package io.github.hakjuoh.protege_mcp.ro_crate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class RoCrateProjectProfileValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROJECT_PROFILE = "https://example.org/profiles/project-v1/";
    private static final String ONTOLOGY = "https://example.org/ontology";
    private static final String OWL2 = "https://www.w3.org/TR/owl2-overview/";

    @Test
    void validatesEveryRecommendationWithItsNormativeFilenameAndProfileModel(@TempDir Path temp)
            throws Exception {
        for (RoCrateVersion version : RoCrateVersion.values()) {
            Path manifest = temp.resolve(version.version()).resolve(version.metadataFile());
            Files.createDirectories(manifest.getParent());
            JSON.writeValue(manifest.toFile(), crate(version, version.metadataFile(), null));

            RoCrateValidationResult result = RoCrateProjectProfileValidator.validate(
                    manifest, version, profile());

            assertTrue(result.valid(), () -> version + ": " + result.issues());
        }
    }

    @Test
    void detectsEverySupportedVersionFromAnExistingCrateContext(@TempDir Path temp)
            throws Exception {
        for (RoCrateVersion version : RoCrateVersion.values()) {
            Path manifest = temp.resolve(version.version()).resolve(version.metadataFile());
            Files.createDirectories(manifest.getParent());
            JSON.writeValue(manifest.toFile(), crate(version, version.metadataFile(), null));

            assertEquals(version,
                    RoCrateProjectProfileValidator.detectVersion(manifest).orElseThrow());
        }
    }

    @Test
    void checkedInCrossVersionFixturesStayConformant(@TempDir Path temp) throws Exception {
        for (RoCrateVersion version : RoCrateVersion.values()) {
            String resource = "/fixtures/" + version.version() + "/" + version.metadataFile();
            Path manifest = temp.resolve(version.version()).resolve(version.metadataFile());
            Files.createDirectories(manifest.getParent());
            try (var input = RoCrateProjectProfileValidatorTest.class.getResourceAsStream(resource)) {
                assertTrue(input != null, "missing fixture " + resource);
                Files.copy(input, manifest);
            }
            RoCrateValidationResult result = RoCrateProjectProfileValidator.validate(
                    manifest, version, profile());
            assertTrue(result.valid(), () -> version + ": " + result.issues());
        }
    }

    @Test
    void enforcesTheVersionSpecificDescriptorFilename(@TempDir Path temp) throws Exception {
        Path manifest = temp.resolve("ro-crate-metadata.json");
        JSON.writeValue(manifest.toFile(), crate(RoCrateVersion.V1_0,
                "ro-crate-metadata.json", null));

        RoCrateValidationResult result = RoCrateProjectProfileValidator.validate(
                manifest, RoCrateVersion.V1_0, profile());

        assertCode(result, "descriptor_missing");
    }

    @Test
    void legacyAndFormalProfileTypesAreNotSilentlyInterchanged(@TempDir Path temp) throws Exception {
        Path legacy = temp.resolve("legacy.json");
        JSON.writeValue(legacy.toFile(), crate(RoCrateVersion.V1_1,
                RoCrateVersion.V1_1.metadataFile(), List.of("Profile")));
        assertCode(RoCrateProjectProfileValidator.validate(
                legacy, RoCrateVersion.V1_1, profile()), "profile_entity_type");

        Path formal = temp.resolve("formal.json");
        JSON.writeValue(formal.toFile(), crate(RoCrateVersion.V1_3,
                RoCrateVersion.V1_3.metadataFile(), List.of("CreativeWork")));
        assertCode(RoCrateProjectProfileValidator.validate(
                formal, RoCrateVersion.V1_3, profile()), "profile_entity_type");
    }

    @Test
    void enforcesTheBaseSpecificationRootEntityMusts(@TempDir Path temp) throws Exception {
        Map<String, Object> crate = crate(RoCrateVersion.V1_1,
                RoCrateVersion.V1_1.metadataFile(), null);
        Map<String, Object> root = rootEntity(crate);
        root.remove("description");
        root.remove("datePublished");
        root.remove("license");
        Path manifest = write(temp, RoCrateVersion.V1_1.metadataFile(), crate);

        RoCrateValidationResult result = RoCrateProjectProfileValidator.validate(
                manifest, RoCrateVersion.V1_1, profile());

        assertCode(result, "root_description");
        assertCode(result, "root_date_published");
        assertCode(result, "root_license");
    }

    @Test
    void rejectsNonIso8601DatePublished(@TempDir Path temp) throws Exception {
        int index = 0;
        for (String invalid : List.of("yesterday-ish", "2026-99-99", "2026-02-31",
                "2026-13-01", "2026-05-01T99:99")) {
            Map<String, Object> crate = crate(RoCrateVersion.V1_1,
                    RoCrateVersion.V1_1.metadataFile(), null);
            rootEntity(crate).put("datePublished", invalid);
            Path manifest = write(temp.resolve("bad-" + index++),
                    RoCrateVersion.V1_1.metadataFile(), crate);

            assertCode(RoCrateProjectProfileValidator.validate(
                    manifest, RoCrateVersion.V1_1, profile()), "root_date_published_invalid");
        }
    }

    @Test
    void datePublishedCardinalityIsCheckedOnTheRawJsonValue(@TempDir Path temp) throws Exception {
        Map<String, Object> mixed = crate(RoCrateVersion.V1_1,
                RoCrateVersion.V1_1.metadataFile(), null);
        rootEntity(mixed).put("datePublished", List.of("2026-07-15", 42));
        assertCode(RoCrateProjectProfileValidator.validate(
                write(temp.resolve("mixed"), RoCrateVersion.V1_1.metadataFile(), mixed),
                RoCrateVersion.V1_1, profile()), "root_date_published_invalid");

        Map<String, Object> single = crate(RoCrateVersion.V1_1,
                RoCrateVersion.V1_1.metadataFile(), null);
        rootEntity(single).put("datePublished", List.of("2026-07-15"));
        RoCrateValidationResult result = RoCrateProjectProfileValidator.validate(
                write(temp.resolve("single"), RoCrateVersion.V1_1.metadataFile(), single),
                RoCrateVersion.V1_1, profile());
        assertTrue(result.valid(), () -> result.issues().toString());
    }

    @Test
    void acceptsCalendarValidIso8601DatePublishedPrecisions(@TempDir Path temp) throws Exception {
        int index = 0;
        for (String valid : List.of("2026", "2026-07", "2026-07-15",
                "2024-05-01T10:00", "2024-05-01T10:00:00+02:00", "2024-12-31T23:59:59.999Z")) {
            Map<String, Object> crate = crate(RoCrateVersion.V1_1,
                    RoCrateVersion.V1_1.metadataFile(), null);
            rootEntity(crate).put("datePublished", valid);
            Path manifest = write(temp.resolve("ok-" + index++),
                    RoCrateVersion.V1_1.metadataFile(), crate);

            RoCrateValidationResult result = RoCrateProjectProfileValidator.validate(
                    manifest, RoCrateVersion.V1_1, profile());
            assertTrue(result.valid(), () -> valid + ": " + result.issues());
        }
    }

    @Test
    void rejectsAmbiguousAndNonTextualContextDeclarations(@TempDir Path temp) throws Exception {
        Map<String, Object> mixed = new LinkedHashMap<>(crate(RoCrateVersion.V1_1,
                RoCrateVersion.V1_1.metadataFile(), null));
        mixed.put("@context", List.of(RoCrateVersion.V1_1.context(),
                RoCrateVersion.V1_3.context()));
        assertCode(RoCrateProjectProfileValidator.validate(
                write(temp, "a-" + RoCrateVersion.V1_1.metadataFile(), mixed),
                RoCrateVersion.V1_1, profile()), "context_ambiguous");

        Map<String, Object> nonTextual = new LinkedHashMap<>(crate(RoCrateVersion.V1_1,
                RoCrateVersion.V1_1.metadataFile(), null));
        nonTextual.put("@context", List.of(RoCrateVersion.V1_1.context(),
                Map.of("Dataset", "https://attacker.example/Redefined")));
        assertCode(RoCrateProjectProfileValidator.validate(
                write(temp, "b-" + RoCrateVersion.V1_1.metadataFile(), nonTextual),
                RoCrateVersion.V1_1, profile()), "context_entry_invalid");
    }

    @Test
    void enforcesTheOnDiskMetadataFilename(@TempDir Path temp) throws Exception {
        Path misnamed = write(temp, "crate.json", crate(RoCrateVersion.V1_1,
                RoCrateVersion.V1_1.metadataFile(), null));

        assertCode(RoCrateProjectProfileValidator.validate(
                misnamed, RoCrateVersion.V1_1, profile()), "metadata_filename");
    }

    @Test
    void rejectsRelativeProfileIris(@TempDir Path temp) throws Exception {
        Path manifest = write(temp, RoCrateVersion.V1_1.metadataFile(),
                crate(RoCrateVersion.V1_1, RoCrateVersion.V1_1.metadataFile(), null));

        RoCrateValidationResult result = RoCrateProjectProfileValidator.validate(manifest,
                RoCrateVersion.V1_1, new RoCrateProjectProfile("example", "ontology.ttl",
                        ONTOLOGY, "profiles/project-v1", List.of(), OWL2));

        assertCode(result, "profile_iri_relative");
    }

    @Test
    void formatLookupIsExplicitAndDefaultsAreOwnedByTheConsumer() {
        assertEquals(RoCrateVersion.V1_3, RoCrateVersion.fromFormat("ro-crate-1.3"));
        try {
            RoCrateVersion.fromFormat("ro-crate-current");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported"));
            return;
        }
        throw new AssertionError("unknown formats must be rejected");
    }

    private static RoCrateProjectProfile profile() {
        return new RoCrateProjectProfile("example", "ontology.ttl", ONTOLOGY,
                PROJECT_PROFILE, List.of(), OWL2);
    }

    private static Map<String, Object> crate(RoCrateVersion version, String descriptorId,
            List<String> profileTypesOverride) {
        List<Object> graph = new ArrayList<>();
        graph.add(entity(descriptorId, "CreativeWork",
                "about", ref("./"), "conformsTo", ref(version.specification())));
        graph.add(entity("./", "Dataset",
                "name", "Example ontology project", "identifier", "example",
                "description", "Example ontology project used by the validator tests.",
                "datePublished", "2026-07-15",
                "license", "https://www.apache.org/licenses/LICENSE-2.0",
                "conformsTo", ref(PROJECT_PROFILE), "mainEntity", ref("ontology.ttl"),
                "hasPart", ref("ontology.ttl")));
        List<String> profileTypes = profileTypesOverride != null ? profileTypesOverride
                : version.formalProfiles() ? List.of("CreativeWork", "Profile")
                : List.of("CreativeWork");
        graph.add(entity(PROJECT_PROFILE, profileTypes,
                "name", "Example project profile"));
        graph.add(entity("ontology.ttl", "File",
                "encodingFormat", "text/turtle", "about", ref(ONTOLOGY)));
        graph.add(entity(ONTOLOGY, "Dataset", "conformsTo", ref(OWL2)));
        return Map.of("@context", version.context(), "@graph", graph);
    }

    private static Map<String, Object> entity(String id, Object type, Object... properties) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("@id", id);
        entity.put("@type", type);
        for (int i = 0; i < properties.length; i += 2) {
            entity.put((String) properties[i], properties[i + 1]);
        }
        return entity;
    }

    private static Map<String, String> ref(String id) {
        return Map.of("@id", id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rootEntity(Map<String, Object> crate) {
        for (Object entity : (List<Object>) crate.get("@graph")) {
            Map<String, Object> candidate = (Map<String, Object>) entity;
            if ("./".equals(candidate.get("@id"))) {
                return candidate;
            }
        }
        throw new AssertionError("crate has no root entity");
    }

    private static Path write(Path temp, String fileName, Map<String, Object> crate)
            throws Exception {
        Path manifest = temp.resolve(fileName);
        Files.createDirectories(manifest.getParent());
        JSON.writeValue(manifest.toFile(), crate);
        return manifest;
    }

    private static void assertCode(RoCrateValidationResult result, String code) {
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> code.equals(issue.code())),
                () -> "missing " + code + " in " + result.issues());
    }
}
