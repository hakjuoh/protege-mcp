package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * End-to-end tests for the registered {@code remove_prefix} handler: a binding is deleted from the
 * document format's prefix map (not the undo stack), unknown prefixes and prefix-less formats error,
 * and the OWLAPI 4.x rebuild keeps every sibling binding — including a second name on the same
 * namespace and the standard rdf/rdfs/owl/xsd prefixes.
 */
class OntologyMetadataPrefixToolsTest {

    private static final String NS = "https://example.org/ns#";

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
    void removesABindingAndKeepsTheStandardPrefixes() throws Exception {
        ToolContext ctx = turtleContext(Map.of("ex:", NS));

        Map<String, Object> result = structured(remove(ctx, "ex"));

        assertEquals(true, result.get("removed"));
        assertEquals("ex:", result.get("prefix"));
        assertEquals(NS, result.get("namespace"));
        @SuppressWarnings("unchecked")
        Map<String, String> after = (Map<String, String>) result.get("prefixes");
        assertFalse(after.containsKey("ex:"), "the removed binding is gone");
        assertTrue(after.containsKey("owl:"), "clear()+rebuild must re-seed the standard prefixes");
        assertTrue(after.containsKey("rdfs:"));
        assertTrue(String.valueOf(result.get("note")).toLowerCase().contains("not undoable"));
    }

    @Test
    void acceptsThePrefixWithOrWithoutATrailingColon() throws Exception {
        assertEquals("ex:", structured(remove(turtleContext(Map.of("ex:", NS)), "ex")).get("prefix"));
        assertEquals("ex:", structured(remove(turtleContext(Map.of("ex:", NS)), "ex:")).get("prefix"));
    }

    @Test
    void removingTheDefaultNamespaceBindingWorks() throws Exception {
        ToolContext ctx = turtleContext(Map.of(":", NS));

        Map<String, Object> result = structured(remove(ctx, ":"));

        assertEquals(true, result.get("removed"));
        assertEquals(":", result.get("prefix"));
        assertEquals(NS, result.get("namespace"));
        @SuppressWarnings("unchecked")
        Map<String, String> after = (Map<String, String>) result.get("prefixes");
        assertFalse(after.containsKey(":"), "the default-namespace binding is gone from the map");
    }

    @Test
    void unknownPrefixIsAnError() throws Exception {
        CallToolResult result = remove(turtleContext(Map.of("ex:", NS)), "nope");

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(text(result).contains("not registered"),
                () -> "expected a not-registered error, got: " + text(result));
    }

    /**
     * Regression: two prefix names bound to the SAME namespace. Removing one must keep the other —
     * a naive {@code unregisterNamespace(namespace)} would delete both because it removes by value.
     */
    @Test
    void removingOneOfTwoSiblingsOnTheSameNamespaceKeepsTheOther() throws Exception {
        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("ex:", NS);
        prefixes.put("ex2:", NS);
        ToolContext ctx = turtleContext(prefixes);

        Map<String, Object> result = structured(remove(ctx, "ex"));

        assertEquals(true, result.get("removed"));
        @SuppressWarnings("unchecked")
        Map<String, String> after = (Map<String, String>) result.get("prefixes");
        assertFalse(after.containsKey("ex:"), "the removed sibling is gone");
        assertEquals(NS, after.get("ex2:"), "the sibling bound to the same namespace survives");
    }

    // ---------------------------------------------------------------- helpers

    private static ToolContext turtleContext(Map<String, String> customPrefixes) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology ont = m.createOntology(IRI.create("https://example.org/prefix-tools"));
        TurtleDocumentFormat format = new TurtleDocumentFormat();
        m.setOntologyFormat(ont, format);
        customPrefixes.forEach((name, ns) -> format.setPrefix(name, ns));
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(ont)),
                new McpServerController(new OntologyAccess(null)));
    }

    private static CallToolResult remove(ToolContext ctx, String prefix) {
        ToolRegistry registry = new ToolRegistry();
        OntologyMetadataTools.register(registry, ctx);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("prefix", prefix);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals("remove_prefix")) {
                return spec.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest("remove_prefix", args));
            }
        }
        return fail("no tool named remove_prefix");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> "unexpected error: " + text(result));
        return (Map<String, Object>) result.structuredContent();
    }

    private static String text(CallToolResult result) {
        return result.content().toString();
    }
}
