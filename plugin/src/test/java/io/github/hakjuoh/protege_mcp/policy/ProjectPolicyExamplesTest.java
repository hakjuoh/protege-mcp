package io.github.hakjuoh.protege_mcp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

/** The shipped example policies must stay loadable end-to-end, not merely schema-shaped. */
class ProjectPolicyExamplesTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void everyShippedExampleValidatesWithMaterializedAssets(@TempDir Path temp) throws Exception {
        Path examples = Path.of("docs", "examples", "project-policy");
        assertTrue(Files.isDirectory(examples), "surefire must run from the repository root");
        List<Path> shipped;
        try (var files = Files.list(examples)) {
            shipped = files.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted().toList();
        }
        assertEquals(3, shipped.size(), () -> "unexpected example set: " + shipped);
        for (Path example : shipped) {
            String name = example.getFileName().toString().replace(".yaml", "");
            Path project = temp.resolve(name);
            Path policyPath = project.resolve(".protege-mcp").resolve("project.yaml");
            String yaml = Files.readString(example);
            ProjectPolicyFixtures.writePolicy(policyPath, yaml);
            materializeCompanions(project, yaml);

            ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

            assertTrue(policy.valid(), () -> name + ": " + policy.issues());
        }
    }

    @SuppressWarnings("unchecked")
    private static void materializeCompanions(Path project, String yaml) throws Exception {
        Map<String, Object> policy = YAML.readValue(yaml, Map.class);
        for (Object module : list(policy.get("modules"))) {
            touch(project, (String) map(module).get("path"),
                    "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<> a owl:Ontology .\n");
        }
        touch(project, (String) map(policy.get("imports")).get("lockfile"), "{}\n");
        Map<String, Object> validation = map(policy.get("validation"));
        for (Object configured : list(map(validation.get("invariants")).get("paths"))) {
            touchGlob(project, (String) configured, "ASK { ?s ?p ?o }\n");
        }
        for (Object configured : list(map(validation.get("shacl")).get("paths"))) {
            touchGlob(project, (String) configured, "@prefix sh: <http://www.w3.org/ns/shacl#> .\n");
        }
        Map<String, Object> cqs = map(validation.get("competency_questions"));
        String cqPath = (String) cqs.get("path");
        if (cqPath != null) {
            if ("robot-sparql-dir".equals(cqs.get("convention"))) {
                Files.createDirectories(project.resolve(cqPath));
                touch(project, cqPath + "/cq-001.rq", "ASK { ?s ?p ?o }\n");
            } else {
                touch(project, cqPath, "# competency questions\n");
            }
        }
    }

    private static void touchGlob(Path project, String configured, String content)
            throws Exception {
        if (configured != null) {
            touch(project, configured.replace("*", "sample"), content);
        }
    }

    private static void touch(Path project, String relative, String content) throws Exception {
        if (relative == null) {
            return;
        }
        Path target = project.resolve(relative);
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) {
            Files.writeString(target, content);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }
}
