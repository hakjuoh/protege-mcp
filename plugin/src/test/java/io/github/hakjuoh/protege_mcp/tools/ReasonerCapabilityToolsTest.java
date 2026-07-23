package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.reasoner.RuleValidationService;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

class ReasonerCapabilityToolsTest {

    @Test
    void liveHandlersRejectUnknownCoercedAndFractionalArgumentsBeforeModelAccess() {
        for (Request request : new Request[] {
                new Request("get_reasoner_capabilities", Map.of("extra", true)),
                new Request("validate_rules", Map.of("limit", "10")),
                new Request("validate_rules", Map.of("offset", 1.5)),
                new Request("validate_rules", Map.of("include_imports", "true")),
                new Request("validate_rules", Map.of("snapshot_fingerprint", "bad"))}) {
            var result = call(request.tool, request.arguments);
            assertEquals(Boolean.TRUE, result.isError(), request::toString);
            assertEquals("invalid_request",
                    ((Map<?, ?>) result.structuredContent()).get("code"), request::toString);
        }
    }

    @Test
    void liveContinuationRequiresSnapshotBeforeModelAccess() {
        var result = call("validate_rules", Map.of("offset", 1));
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("rule_validation_snapshot_required",
                ((Map<?, ?>) result.structuredContent()).get("code"));
        assertEquals(false, ((Map<?, ?>) result.structuredContent()).get("retryable"));
    }

    @Test
    void liveSchemasAreTheSharedClosedContracts() {
        ToolRegistry registry = registry();
        for (String name : io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas.NAMES) {
            var spec = registry.build().stream()
                    .filter(item -> name.equals(item.tool().name())).findFirst().orElseThrow();
            assertEquals(io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas.input(name),
                    spec.tool().inputSchema(), name);
            assertEquals(io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas.output(name),
                    io.github.hakjuoh.protege_mcp.catalog.McpCatalog.get()
                            .tool(name).outputSchema(), name);
            assertEquals(io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas.description(name),
                    spec.tool().description(), name);
        }
    }

    @Test
    void checkedInLiveCatalogMirrorsTheGeneratedReasonerContract() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream(
                "/io/github/hakjuoh/protege_mcp/catalog/mcp-catalog.json")) {
            assertNotNull(in);
            var root = json.readTree(in);
            for (String name : io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas.NAMES) {
                var node = java.util.stream.StreamSupport.stream(
                                root.path("tools").spliterator(), false)
                        .filter(candidate -> name.equals(candidate.path("name").asText()))
                        .findFirst().orElseThrow();
                assertEquals(
                        io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas.description(name),
                        node.path("description").asText(), name);
                assertEquals(json.readTree(json.writeValueAsBytes(
                        io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas.input(name))),
                        node.path("input_schema"), name);
            }
        }
    }

    @Test
    void liveHandlersAcceptTheOfficialProtegeHermitSelection() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/live-reasoner"));
        var data = manager.getOWLDataFactory();
        var x = data.getSWRLVariable(IRI.create("https://example.org/var/x"));
        manager.addAxiom(ontology, data.getSWRLRule(
                Set.of(data.getSWRLClassAtom(data.getOWLClass(
                        IRI.create("https://example.org/A")), x)),
                Set.of(data.getSWRLClassAtom(data.getOWLClass(
                        IRI.create("https://example.org/B")), x))));
        ToolRegistry registry = liveRegistry(ontology);

        var capabilities = call(registry, "get_reasoner_capabilities", Map.of());
        assertEquals(Boolean.FALSE, capabilities.isError(), capabilities::toString);
        assertEquals("reviewed", ((Map<?, ?>) capabilities.structuredContent())
                .get("profile_status"));

        var validation = call(registry, "validate_rules", Map.of());
        assertEquals(Boolean.FALSE, validation.isError(), validation::toString);
        assertEquals(1, ((Map<?, ?>) validation.structuredContent()).get("total_rules"));
        assertEquals(true, ((Map<?, ?>) validation.structuredContent()).get("compatible"));
    }

    @Test
    void liveSnapshotMismatchUsesTheSharedRetryableError() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/live-snapshot"));
        var data = manager.getOWLDataFactory();
        var x = data.getSWRLVariable(IRI.create("https://example.org/var/x"));
        for (int index = 0; index < 2; index++) {
            manager.addAxiom(ontology, data.getSWRLRule(
                    Set.of(data.getSWRLClassAtom(data.getOWLClass(
                            IRI.create("https://example.org/A" + index)), x)),
                    Set.of(data.getSWRLClassAtom(data.getOWLClass(
                            IRI.create("https://example.org/B" + index)), x))));
        }
        ToolRegistry registry = liveRegistry(ontology);
        var first = call(registry, "validate_rules", Map.of("limit", 1));
        String snapshot = String.valueOf(((Map<?, ?>) first.structuredContent())
                .get("snapshot_fingerprint"));
        manager.addAxiom(ontology, data.getSWRLRule(
                Set.of(data.getSWRLClassAtom(data.getOWLClass(
                        IRI.create("https://example.org/ChangedA")), x)),
                Set.of(data.getSWRLClassAtom(data.getOWLClass(
                        IRI.create("https://example.org/ChangedB")), x))));

        var changed = call(registry, "validate_rules", Map.of(
                "offset", 1, "limit", 1, "snapshot_fingerprint", snapshot));
        assertEquals(Boolean.TRUE, changed.isError());
        assertEquals("rule_validation_snapshot_changed",
                ((Map<?, ?>) changed.structuredContent()).get("code"));
        assertEquals(true, ((Map<?, ?>) changed.structuredContent()).get("retryable"));
    }

    @Test
    void liveOccurrenceBudgetUsesTheSharedTypedError() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/live-budget"));
        var data = manager.getOWLDataFactory();
        var x = data.getSWRLVariable(IRI.create("https://example.org/var/x"));
        for (int index = 0; index <= RuleValidationService.MAX_RULE_OCCURRENCES; index++) {
            manager.addAxiom(ontology, data.getSWRLRule(
                    Set.of(data.getSWRLClassAtom(data.getOWLClass(
                            IRI.create("https://example.org/BudgetA" + index)), x)),
                    Set.of(data.getSWRLClassAtom(data.getOWLClass(
                            IRI.create("https://example.org/BudgetB" + index)), x))));
        }
        var exceeded = call(liveRegistry(ontology), "validate_rules", Map.of());
        assertEquals(Boolean.TRUE, exceeded.isError());
        assertEquals("rule_validation_budget_exceeded",
                ((Map<?, ?>) exceeded.structuredContent()).get("code"));
        assertEquals(false, ((Map<?, ?>) exceeded.structuredContent()).get("retryable"));
        assertEquals("rule_occurrences", ((Map<?, ?>) ((Map<?, ?>) exceeded.structuredContent())
                .get("details")).get("budget"));
    }

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult call(
            String tool, Map<String, Object> arguments) {
        return call(registry(), tool, arguments);
    }

    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult call(
            ToolRegistry registry, String tool, Map<String, Object> arguments) {
        var spec = registry.build().stream()
                .filter(item -> tool.equals(item.tool().name())).findFirst().orElseThrow();
        return spec.callHandler().apply(ToolTestExchange.localAdmin(),
                new CallToolRequest(tool, arguments));
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        ReasonerCapabilityTools.register(registry, new ToolContext(null, null));
        MaterializationTools.register(registry, new ToolContext(null, null));
        return registry;
    }

    private static ToolRegistry liveRegistry(org.semanticweb.owlapi.model.OWLOntology ontology) {
        var info = new org.semanticweb.HermiT.ProtegeReasonerFactory();
        info.setup(ontology.getOWLOntologyManager(), "HermiT.reasoner.factory", "HermiT");
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                ReasonerCapabilityToolsTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class}, (proxy, method, args) -> switch (
                        method.getName()) {
                    case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                    case "getCurrentReasonerFactory" -> info;
                    case "getCurrentReasonerFactoryId" -> info.getReasonerId();
                    case "getCurrentReasonerName" -> info.getReasonerName();
                    case "getInstalledReasonerFactories" -> Set.of(info);
                    case "toString" -> "OfficialHermitReasonerManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OWLModelManager base = FakeModelManager.over(ontology);
        OWLModelManager model = (OWLModelManager) Proxy.newProxyInstance(
                ReasonerCapabilityToolsTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) return reasoners;
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        ToolRegistry registry = new ToolRegistry();
        ReasonerCapabilityTools.register(registry,
                new ToolContext(HeadlessAccess.over(model), null));
        return registry;
    }

    private record Request(String tool, Map<String, Object> arguments) { }
}
