package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * End-to-end tests for the registered {@code set_reasoner} handler: the shared unique-or-fail
 * reference resolution (exact name/id, version-less token match, ambiguity refused) over a
 * recording reasoner-manager seam.
 */
class SetReasonerToolTest {

    private Preferences prefs;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void savePreferences() {
        prefs = McpConfig.prefs();
        savedReadOnly = prefs.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restorePreferences() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
    }

    @Test
    void versionlessReferenceSelectsTheSingleVersionedInstall() throws Exception {
        AtomicReference<String> selectedId = new AtomicReference<>();
        ToolContext ctx = ctx(selectedId, info("HermiT 1.4.3.456", "org.semanticweb.HermiT"));

        CallToolResult result = call(ctx, Map.of("reasoner", "HermiT"));

        assertNotEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        Map<?, ?> selected = (Map<?, ?>) structured(result).get("selected");
        assertEquals("HermiT 1.4.3.456", selected.get("name"));
        assertEquals("org.semanticweb.HermiT", selected.get("id"));
        assertEquals("org.semanticweb.HermiT", selectedId.get(),
                "the resolved factory id is what Protégé is told to select");
    }

    @Test
    void unknownReferenceIsRefusedWithoutSelectingAnything() throws Exception {
        AtomicReference<String> selectedId = new AtomicReference<>();
        ToolContext ctx = ctx(selectedId, info("HermiT 1.4.3.456", "org.semanticweb.HermiT"));

        CallToolResult result = call(ctx, Map.of("reasoner", "HermiT 9.9"));

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        assertTrue(String.valueOf(structured(result).get("error")).contains("No reasoner matches"),
                () -> String.valueOf(structured(result)));
        assertNull(selectedId.get(), "a refused reference must not change the selection");
    }

    @Test
    void ambiguousReferenceIsRefusedNamingEveryCandidate() throws Exception {
        AtomicReference<String> selectedId = new AtomicReference<>();
        ToolContext ctx = ctx(selectedId,
                info("HermiT 1.4", "org.semanticweb.HermiT.14"),
                info("HermiT 2.0", "org.semanticweb.HermiT.20"));

        CallToolResult result = call(ctx, Map.of("reasoner", "HermiT"));

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        String error = String.valueOf(structured(result).get("error"));
        assertTrue(error.contains("matches more than one"), error);
        assertTrue(error.contains("HermiT 1.4"), error);
        assertTrue(error.contains("HermiT 2.0"), error);
        assertNull(selectedId.get(), "an ambiguous reference must never be silently picked");
    }

    @Test
    void exactFactoryIdSelectsItsOwnFactoryWhenDisplayNamesCollide() throws Exception {
        // Two installed factories share one display name; only the exact id may pick between
        // them, and it must select ITS factory — not whichever factory a name-keyed map kept.
        AtomicReference<String> selectedId = new AtomicReference<>();
        ToolContext ctx = ctx(selectedId,
                info("Pellet", "com.example.pellet.a"),
                info("Pellet", "com.example.pellet.b"));

        CallToolResult second = call(ctx, Map.of("reasoner", "com.example.pellet.b"));
        assertNotEquals(Boolean.TRUE, second.isError(),
                () -> String.valueOf(second.structuredContent()));
        assertEquals("com.example.pellet.b",
                ((Map<?, ?>) structured(second).get("selected")).get("id"));
        assertEquals("com.example.pellet.b", selectedId.get(),
                "the second same-named factory's id must select the second factory");

        CallToolResult first = call(ctx, Map.of("reasoner", "com.example.pellet.a"));
        assertNotEquals(Boolean.TRUE, first.isError(),
                () -> String.valueOf(first.structuredContent()));
        assertEquals("com.example.pellet.a",
                ((Map<?, ?>) structured(first).get("selected")).get("id"));
        assertEquals("com.example.pellet.a", selectedId.get());
    }

    @Test
    void aSharedDisplayNameCanOnlyBeSelectedByFactoryId() throws Exception {
        AtomicReference<String> selectedId = new AtomicReference<>();
        ToolContext ctx = ctx(selectedId,
                info("Pellet", "com.example.pellet.a"),
                info("Pellet", "com.example.pellet.b"));

        CallToolResult result = call(ctx, Map.of("reasoner", "Pellet"));

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        String error = String.valueOf(structured(result).get("error"));
        assertTrue(error.contains("matches more than one"), error);
        assertTrue(error.contains("Pellet [com.example.pellet.a]"), error);
        assertTrue(error.contains("Pellet [com.example.pellet.b]"), error);
        assertNull(selectedId.get(), "a name shared by two factories must never be silently picked");
    }

    @Test
    void listReasonersPreservesFactoriesThatShareADisplayName() throws Exception {
        AtomicReference<String> selectedId = new AtomicReference<>("com.example.pellet.b");
        ToolContext ctx = ctx(selectedId,
                info("Pellet", "com.example.pellet.b"),
                info("Pellet", "com.example.pellet.a"));

        Map<String, Object> result = structured(call(ctx, "list_reasoners", Map.of()));

        assertEquals(2, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasoners =
                (List<Map<String, Object>>) result.get("reasoners");
        assertEquals(List.of("com.example.pellet.a", "com.example.pellet.b"),
                reasoners.stream().map(row -> row.get("id")).toList());
        assertEquals(List.of(false, true),
                reasoners.stream().map(row -> row.get("current")).toList());
    }

    // ------------------------------------------------------------------ helpers

    private static ToolContext ctx(AtomicReference<String> selectedId,
            ProtegeOWLReasonerInfo... installed) throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/set-reasoner"));
        Set<ProtegeOWLReasonerInfo> factories = new LinkedHashSet<>(java.util.List.of(installed));
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                SetReasonerToolTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getInstalledReasonerFactories" -> factories;
                    case "getCurrentReasonerFactoryId" -> selectedId.get();
                    case "setCurrentReasonerFactoryId" -> {
                        selectedId.set((String) args[0]);
                        yield null;
                    }
                    case "toString" -> "RecordingReasonerManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OWLModelManager base = FakeModelManager.over(ontology);
        OWLModelManager mm = (OWLModelManager) Proxy.newProxyInstance(
                SetReasonerToolTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return reasoners;
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    private static ProtegeOWLReasonerInfo info(String name, String id) {
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                SetReasonerToolTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getReasonerId" -> id;
                    case "getReasonerName" -> name;
                    case "toString" -> name + " test info";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CallToolResult call(ToolContext ctx, Map<String, Object> args) {
        return call(ctx, "set_reasoner", args);
    }

    private static CallToolResult call(ToolContext ctx, String tool, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        ReasonerTools.register(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals(tool)) {
                return spec.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest(tool, args));
            }
        }
        return fail("no tool named " + tool);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }
}
