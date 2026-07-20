package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Pins the live-adapter half of the shared plugin/CLI conformance fixture. */
class CrossSurfaceConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIXTURE_FILES = List.of(
            "project.yaml", "ontology.ttl", "module.ttl", "catalog-v001.xml",
            "ro-crate-metadata.json");

    @Test
    void pluginSurfaceMatchesTheCommittedMachineEvidence(@TempDir Path temp) throws Exception {
        Path policy = copyFixture(temp);
        ToolContext context = context(temp);
        OWLModelManager exposed = context.access().compute(value -> value);
        assertEquals(1, exposed.getOWLReasonerManager().getInstalledReasonerFactories().size());
        ProtegeOWLReasonerInfo exposedInfo = exposed.getOWLReasonerManager()
                .getInstalledReasonerFactories().iterator().next();
        assertEquals("HermiT", exposedInfo.getReasonerName());
        assertEquals(org.semanticweb.HermiT.ReasonerFactory.class.getName(),
                exposedInfo.getReasonerId());
        ProjectPolicyTools.PolicyContext live = context.access().compute(ProjectPolicyTools::capture);
        assertEquals(List.of("HermiT"), live.installedReasoners(), live::toString);
        assertEquals("HermiT", live.selectedReasoner(), live::toString);

        Map<String, Object> qc = structured(ProjectQcTools.run(context,
                Map.of("policy_path", policy.toString()), true));
        Map<String, Object> release = structured(callRelease(context, Map.of(
                "policy_path", policy.toString(), "dry_run", true)));
        Map<String, Object> lock = structured(ImportLockTools.write(context,
                Map.of("path", temp.resolve("imports.lock.json").toString())));

        Map<String, Object> actual = evidence(qc, release, lock);
        OWLModelManager model = context.access().compute(value -> value);
        actual.put("grounding", Map.of(
                "curie", EntityResolver.iriFor(model, "ex:Term").toString(),
                "iri", EntityResolver.iriFor(model,
                        "https://example.org/conformance#Term").toString()));
        Map<String, Object> interoperability = list(qc.get("stages")).stream()
                .map(CrossSurfaceConformanceTest::map)
                .filter(stage -> "interoperability".equals(stage.get("stage")))
                .findFirst().orElseThrow();
        assertEquals("ro-crate-metadata.json",
                map(interoperability.get("details")).get("manifest_path"),
                "release evidence must not contain a checkout-specific absolute path");
        assertEquals(expected(), actual, () -> pretty(actual));
    }

    private static Map<String, Object> evidence(Map<String, Object> qc,
            Map<String, Object> release, Map<String, Object> lock) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("qc", qcEvidence(qc));
        result.put("release", releaseEvidence(release));
        result.put("lock", lockEvidence(lock, "lock"));
        return result;
    }

    private static Map<String, Object> qcEvidence(Map<String, Object> qc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gate", qc.get("gate"));
        out.put("semantic_fingerprint", qc.get("semantic_fingerprint"));
        out.put("closure_fingerprint", map(qc.get("validation_snapshot"))
                .get("closure_fingerprint"));
        out.put("reasoner", qc.get("reasoner"));
        List<Map<String, Object>> stages = new ArrayList<>();
        String reasonerFactory = null;
        for (Object value : list(qc.get("stages"))) {
            Map<String, Object> row = map(value);
            stages.add(Map.of("stage", row.get("stage"), "status", row.get("status")));
            if ("reasoner".equals(row.get("stage"))) {
                reasonerFactory = String.valueOf(map(map(row.get("details"))
                        .get("reasoner_configuration")).get("factory_class"));
            }
        }
        out.put("stages", stages);
        out.put("reasoner_factory", reasonerFactory);
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Object value : list(qc.get("findings"))) {
            Map<String, Object> row = map(value);
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("id", row.get("id"));
            finding.put("source", row.get("source"));
            finding.put("severity", row.get("severity"));
            finding.put("focus_iri", row.get("focus_iri"));
            findings.add(finding);
        }
        findings.sort(Comparator.comparing((Map<String, Object> value) ->
                String.valueOf(value.get("id")))
                .thenComparing(value -> String.valueOf(value.get("focus_iri"))));
        out.put("findings", findings);
        return out;
    }

    private static Map<String, Object> releaseEvidence(Map<String, Object> release) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gate", release.get("gate"));
        out.put("version_iri", release.get("version_iri"));
        Map<String, Object> artifacts = new LinkedHashMap<>();
        List<String> artifactPaths = new ArrayList<>();
        for (Object value : list(release.get("artifacts"))) {
            Map<String, Object> row = map(value);
            String path = String.valueOf(row.get("path"));
            artifactPaths.add(path);
            if (List.of("ontology.ttl", "reports/qc.md", "reports/qc.xml",
                    "reports/qc.sarif").contains(path)) {
                artifacts.put(path, row.get("sha256"));
            }
        }
        artifactPaths.sort(String::compareTo);
        out.put("artifact_count", artifactPaths.size());
        out.put("artifact_paths", artifactPaths);
        out.put("artifacts", artifacts);
        Map<String, Object> manifest = map(release.get("manifest"));
        out.put("manifest_semantic_fingerprint", manifest.get("semantic_fingerprint"));
        out.put("manifest_version_iri", manifest.get("version_iri"));
        return out;
    }

    private static Map<String, Object> lockEvidence(Map<String, Object> lock, String contentKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sha256", lock.get("sha256"));
        out.put("entry_count", lock.get("entry_count"));
        out.put("content", lock.get(contentKey));
        return out;
    }

    private static ToolContext context(Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.loadOntologyFromOntologyDocument(temp.resolve("module.ttl").toFile());
        OWLOntology active = manager.loadOntologyFromOntologyDocument(
                temp.resolve("ontology.ttl").toFile());
        OWLReasonerFactory factory = new org.semanticweb.HermiT.ReasonerFactory();
        String name = "HermiT";
        String id = factory.getClass().getName();
        ProtegeOWLReasonerInfo info = (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                CrossSurfaceConformanceTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) -> switch (
                        method.getName()) {
                    case "getReasonerId" -> id;
                    case "getReasonerName" -> name;
                    case "getReasonerFactory" -> factory;
                    case "getRecommendedBuffering" -> BufferingMode.NON_BUFFERING;
                    case "getConfiguration" -> new SimpleConfiguration(30_000L);
                    case "toString" -> name + " conformance info";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                CrossSurfaceConformanceTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class}, (proxy, method, args) -> switch (
                        method.getName()) {
                    case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                    case "getCurrentReasonerFactory" -> info;
                    case "getCurrentReasonerFactoryId" -> id;
                    case "getCurrentReasonerName" -> name;
                    case "getInstalledReasonerFactories" -> Set.of(info);
                    case "toString" -> "ConformanceReasonerManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OWLModelManager base = FakeModelManager.over(active);
        OWLModelManager model = (OWLModelManager) Proxy.newProxyInstance(
                CrossSurfaceConformanceTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) return reasoners;
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        return new ToolContext(HeadlessAccess.over(model),
                new McpServerController(new OntologyAccess(null)));
    }

    private static CallToolResult callRelease(ToolContext context, Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        ReleaseTools.register(registry, context);
        for (SyncToolSpecification spec : registry.build()) {
            if ("prepare_release".equals(spec.tool().name())) {
                return spec.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest("prepare_release", arguments));
            }
        }
        throw new AssertionError("prepare_release was not registered");
    }

    private static Path copyFixture(Path temp) throws IOException {
        ClassLoader loader = CrossSurfaceConformanceTest.class.getClassLoader();
        for (String name : FIXTURE_FILES) {
            try (InputStream input = loader.getResourceAsStream("conformance/v1/" + name)) {
                if (input == null) throw new IOException("missing conformance fixture " + name);
                Files.copy(input, temp.resolve(name));
            }
        }
        return temp.resolve("project.yaml");
    }

    private static Map<String, Object> expected() throws IOException {
        try (InputStream input = CrossSurfaceConformanceTest.class.getClassLoader()
                .getResourceAsStream("conformance/v1/expected.json")) {
            if (input == null) throw new IOException("missing conformance expected.json");
            return JSON.readValue(input,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static String pretty(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException impossible) {
            return String.valueOf(value);
        }
    }
}
