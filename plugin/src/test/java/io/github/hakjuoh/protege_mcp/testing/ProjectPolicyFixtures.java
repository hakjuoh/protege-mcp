package io.github.hakjuoh.protege_mcp.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Test-only creation of a real policy-v1 attached RO-Crate fixture. */
public final class ProjectPolicyFixtures {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROFILE =
            "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
    private static final String OWL2 = "https://www.w3.org/TR/owl2-overview/";

    private ProjectPolicyFixtures() {
    }

    public static String minimalPolicy(String projectId, String ontologyIri) {
        return "version: 1\n"
                + "project_id: " + projectId + "\n"
                + "root_ontology: " + ontologyIri + "\n"
                + interoperabilityYaml("ontology.ttl", "ro-crate-1.1");
    }

    public static String interoperabilityYaml(String rootArtifact, String format) {
        String metadata = "ro-crate-1.0".equals(format)
                ? "ro-crate-metadata.jsonld" : "ro-crate-metadata.json";
        return "interoperability:\n"
                + "  profile: " + PROFILE + "\n"
                + "  additional_profiles: []\n"
                + "  root_artifact: " + rootArtifact + "\n"
                + "  metadata:\n"
                + "    path: " + metadata + "\n"
                + "    format: " + format + "\n"
                + "  canonicalization:\n"
                + "    algorithm: RDFC-1.0\n"
                + "    hash: SHA-256\n"
                + "    scope: root-ontology\n"
                + "    timeout_ms: 120000\n";
    }

    /** Write YAML and materialize its root artifact and matching RO-Crate when it is a v1 policy. */
    public static void writePolicy(Path policyPath, String yaml) throws IOException {
        Files.createDirectories(policyPath.getParent());
        Files.writeString(policyPath, yaml, StandardCharsets.UTF_8);
        materialize(policyPath, yaml);
    }

    @SuppressWarnings("unchecked")
    public static void materialize(Path policyPath, String yaml) throws IOException {
        Object parsed;
        try {
            parsed = YAML.readValue(yaml, Object.class);
        } catch (IOException | RuntimeException ignored) {
            return;
        }
        if (!(parsed instanceof Map) || !Integer.valueOf(1).equals(((Map<?, ?>) parsed).get("version"))) {
            return;
        }
        Map<String, Object> policy = (Map<String, Object>) parsed;
        Object interopValue = policy.get("interoperability");
        if (!(interopValue instanceof Map)) return;
        Map<String, Object> interop = (Map<String, Object>) interopValue;
        Map<String, Object> metadata = (Map<String, Object>) interop.get("metadata");

        Path policyDir = policyPath.toAbsolutePath().normalize().getParent();
        Path anchor = policyDir.getFileName() != null && ".protege-mcp".equals(
                policyDir.getFileName().toString()) ? policyDir.getParent() : policyDir;
        String configuredRoot = (String) policy.getOrDefault("project_root", ".");
        Path root = anchor.resolve(configuredRoot).normalize();
        Files.createDirectories(root);

        String rootArtifact = (String) interop.get("root_artifact");
        Path artifact = root.resolve(rootArtifact).normalize();
        Files.createDirectories(artifact.getParent());
        if (!Files.exists(artifact)) {
            Files.writeString(artifact,
                    "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<> a owl:Ontology .\n",
                    StandardCharsets.UTF_8);
        }

        String format = (String) metadata.getOrDefault("format", "ro-crate-1.1");
        String version = format.substring("ro-crate-".length());
        boolean formalProfiles = "1.2".equals(version) || "1.3".equals(version);
        String descriptor = (String) metadata.getOrDefault("path",
                "ro-crate-1.0".equals(format)
                        ? "ro-crate-metadata.jsonld" : "ro-crate-metadata.json");
        String ontologyIri = (String) policy.get("root_ontology");
        String projectId = (String) policy.get("project_id");
        String profile = (String) interop.get("profile");
        List<String> profiles = new ArrayList<>();
        profiles.add(profile);
        Object additional = interop.get("additional_profiles");
        if (additional instanceof List) profiles.addAll((List<String>) additional);

        List<Object> graph = new ArrayList<>();
        graph.add(entity(descriptor, "CreativeWork", "about", ref("./"),
                "conformsTo", ref("https://w3id.org/ro/crate/" + version)));
        graph.add(entity("./", "Dataset", "name", "Test ontology project",
                "description", "Materialized policy-v1 test crate.",
                "datePublished", "2026-07-15",
                "license", "https://www.apache.org/licenses/LICENSE-2.0",
                "identifier", projectId, "conformsTo", refs(profiles),
                "mainEntity", ref(rootArtifact), "hasPart", ref(rootArtifact)));
        for (String profileIri : profiles) {
            graph.add(entity(profileIri, formalProfiles ? List.of("CreativeWork", "Profile")
                    : List.of("CreativeWork"), "name", "Test project profile"));
        }
        graph.add(entity(rootArtifact, "File", "encodingFormat", mediaType(rootArtifact),
                "about", ref(ontologyIri)));
        graph.add(entity(ontologyIri, "Dataset", "conformsTo", ref(OWL2)));

        Path manifest = root.resolve(descriptor);
        JSON.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(),
                Map.of("@context", "https://w3id.org/ro/crate/" + version + "/context",
                        "@graph", graph));
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".ttl")) return "text/turtle";
        if (lower.endsWith(".jsonld") || lower.endsWith(".json")) return "application/ld+json";
        return "application/rdf+xml";
    }

    private static Map<String, Object> entity(String id, Object type, Object... values) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@id", id);
        out.put("@type", type);
        for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]);
        return out;
    }

    private static Map<String, String> ref(String id) {
        return Map.of("@id", id);
    }

    private static List<Map<String, String>> refs(List<String> ids) {
        return ids.stream().map(ProjectPolicyFixtures::ref).toList();
    }
}
