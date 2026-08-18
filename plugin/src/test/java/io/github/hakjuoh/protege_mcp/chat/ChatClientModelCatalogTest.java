package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.protege_mcp.testing.TestPreferences;

class ChatClientModelCatalogTest {

    @Test
    void catalogsAreBoundToOneClientProfile() {
        Preferences preferences = TestPreferences.cleared();
        ChatClientModelCatalog local = new ChatClientModelCatalog(profile("local"));
        ChatClientModelCatalog remote = new ChatClientModelCatalog(profile("remote"));

        local.save(preferences, List.of("llama3.3"));
        remote.save(preferences, List.of("gpt-proxy"));

        assertEquals(List.of("llama3.3"), local.load(preferences));
        assertEquals(List.of("gpt-proxy"), remote.load(preferences));
        assertEquals(List.of("", "llama3.3"), local.pickerModels(preferences));
        assertNotEquals(local.preferenceKey(), remote.preferenceKey());
    }

    @Test
    void profilesCanShareRuntimeDiscoveryWithoutSharingPreferences() {
        Preferences preferences = TestPreferences.cleared();
        ChatClientAdapter adapter = new ChatClientAdapter() {
            @Override public String id() { return "shared"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of("shared-model"); }
        };
        ChatClientProfile first = new ChatClientProfile("first", adapter, "First", "tool");
        ChatClientProfile second = new ChatClientProfile("second", adapter, "Second", "tool");

        assertEquals(List.of("shared-model"),
                ChatModelCatalog.load(preferences, first, Path.of("unused")));
        assertEquals(List.of("shared-model"),
                ChatModelCatalog.load(preferences, second, Path.of("unused")));
        new ChatClientModelCatalog(first).save(preferences, List.of("first-only"));
        assertEquals(List.of("first-only"), new ChatClientModelCatalog(first).load(preferences));
        assertEquals(List.of("shared-model"),
                ChatModelCatalog.load(preferences, second, Path.of("unused")));
    }

    @Test
    void legacyCatalogsGainClientMetadataWithoutChangingTheirStoredModelList() {
        Preferences preferences = TestPreferences.cleared();
        ChatClientModelCatalog catalog = new ChatClientModelCatalog(effortProfile("client"));
        catalog.save(preferences, List.of("model-a", "model-b"));

        assertEquals(List.of(
                new ChatModelDefinition("model-a", List.of("low", "high")),
                new ChatModelDefinition("model-b", List.of("minimal"))),
                catalog.loadDefinitions(preferences));
        assertEquals(List.of("", "low", "high"),
                catalog.reasoningEfforts(preferences, "model-a"));
        assertEquals(List.of("model-a", "model-b"), catalog.load(preferences));
    }

    @Test
    void eachModelAndClientStoresAnIndependentEditableEffortList() {
        Preferences preferences = TestPreferences.cleared();
        ChatClientModelCatalog first = new ChatClientModelCatalog(effortProfile("first"));
        ChatClientModelCatalog second = new ChatClientModelCatalog(effortProfile("second"));

        first.saveDefinitions(preferences, List.of(
                new ChatModelDefinition("model-a", List.of("medium", "high")),
                new ChatModelDefinition("model-b", List.of())));
        second.saveDefinitions(preferences, List.of(
                new ChatModelDefinition("model-a", List.of("max"))));

        assertEquals(List.of("", "medium", "high"),
                first.reasoningEfforts(preferences, "model-a"));
        assertEquals(List.of(""), first.reasoningEfforts(preferences, "model-b"));
        assertEquals(List.of("", "max"), second.reasoningEfforts(preferences, "model-a"));
        assertNotEquals(
                ChatClientPreferences.modelReasoningEffortsPrefKey("first", "model-a"),
                ChatClientPreferences.modelReasoningEffortsPrefKey("second", "model-a"));
        assertNotEquals(
                ChatClientPreferences.modelReasoningEffortsPrefKey("first", "model-a"),
                ChatClientPreferences.modelReasoningEffortsPrefKey("first", "model-b"));
    }

    @Test
    void removingAndReaddingAModelRetainsItsPerModelSettings() {
        Preferences preferences = TestPreferences.cleared();
        ChatClientModelCatalog catalog = new ChatClientModelCatalog(effortProfile("client"));
        catalog.saveDefinitions(preferences, List.of(
                new ChatModelDefinition("model-a", List.of("custom"))));
        catalog.saveDefinitions(preferences, List.of());
        catalog.save(preferences, List.of("model-a"));

        assertEquals(List.of("", "custom"),
                catalog.reasoningEfforts(preferences, "model-a"));
    }

    @Test
    void loadingLegacyRowsUsesOneBatchMetadataLookup() {
        Preferences preferences = TestPreferences.cleared();
        AtomicInteger batchCalls = new AtomicInteger();
        ChatClientProfile profile = new ChatClientProfile("batch", new ChatClientAdapter() {
            @Override public String id() { return "batch-adapter"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of(); }
            @Override public List<String> reasoningEfforts(Path home, String model) {
                throw new AssertionError("legacy catalog loading must use the batch boundary");
            }
            @Override public Map<String, List<String>> reasoningEfforts(
                    Path home, List<String> models) {
                batchCalls.incrementAndGet();
                return Map.of("model-a", List.of("low"), "model-b", List.of("high"));
            }
        }, "Batch", "test");
        ChatClientModelCatalog catalog = new ChatClientModelCatalog(profile);
        catalog.save(preferences, List.of("model-a", "model-b"));

        assertEquals(List.of(
                new ChatModelDefinition("model-a", List.of("low")),
                new ChatModelDefinition("model-b", List.of("high"))),
                catalog.loadDefinitions(preferences));
        assertEquals(1, batchCalls.get());
    }

    private static ChatClientProfile profile(String id) {
        return new ChatClientProfile(id, new ChatClientAdapter() {
            @Override public String id() { return "test-adapter"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of(); }
        }, id, "test");
    }

    private static ChatClientProfile effortProfile(String id) {
        return new ChatClientProfile(id, new ChatClientAdapter() {
            @Override public String id() { return "effort-adapter"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of(); }
            @Override public List<String> reasoningEfforts(Path home, String model) {
                return "model-a".equals(model)
                        ? List.of("low", "high") : List.of("minimal");
            }
        }, id, "test");
    }
}
