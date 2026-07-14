package io.github.hakjuoh.protege_mcp.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;

/**
 * Method-level tests for {@link PromptCatalog}, the thin aggregator that concatenates every prompt
 * provider's registrations into the single list the MCP server registers at startup — the
 * prompt-side mirror of {@code ToolCatalogTest}. Prompts are pure templates, so unlike the tool
 * catalog there is no context to thread through.
 */
class PromptCatalogTest {

    /** The set of prompt names in a built catalog, in insertion order (LinkedHashSet). */
    private static Set<String> names(List<SyncPromptSpecification> specs) {
        Set<String> out = new LinkedHashSet<>();
        for (SyncPromptSpecification spec : specs) {
            out.add(spec.prompt().name());
        }
        return out;
    }

    /** Build one provider's specs the way {@code buildAll} does: register into a fresh registry, then build. */
    private static List<SyncPromptSpecification> specsOf(PromptProvider provider) {
        PromptRegistry registry = new PromptRegistry();
        provider.register(registry);
        return registry.build();
    }

    // ---- buildAll: basic result contract ------------------------------------------------------

    @Test
    void buildAllReturnsNonNullList() {
        assertNotNull(PromptCatalog.buildAll(), "buildAll must never return null");
    }

    @Test
    void buildAllReturnsNonEmptyList() {
        assertFalse(PromptCatalog.buildAll().isEmpty(),
                "the server exposes guided prompts, so the aggregated catalog must be non-empty");
    }

    @Test
    void buildAllContainsNoNullEntries() {
        for (SyncPromptSpecification spec : PromptCatalog.buildAll()) {
            assertNotNull(spec, "no aggregated specification may be null");
            assertNotNull(spec.prompt(), "every specification must carry a Prompt");
            assertNotNull(spec.prompt().name(), "every prompt must have a name");
        }
    }

    @Test
    void buildAllReturnsMutableList() {
        List<SyncPromptSpecification> all = PromptCatalog.buildAll();
        int before = all.size();
        // buildAll's contract is an ArrayList it collects into; verify it is mutable by mutating it.
        all.add(null);
        assertEquals(before + 1, all.size(), "the returned list must be mutable (ArrayList)");
    }

    // ---- buildAll: aggregation of every provider ----------------------------------------------

    @Test
    void buildAllSizeEqualsSumOfAllProviderSpecs() {
        // Sum over the single provider list buildAll itself uses: this verifies buildAll faithfully
        // concatenates every provider with no drop/dupe (the provider list is declared once).
        int expected = 0;
        for (PromptProvider provider : PromptCatalog.PROVIDERS) {
            expected += specsOf(provider).size();
        }
        assertEquals(expected, PromptCatalog.buildAll().size(),
                "the catalog size must be the exact sum of every provider's specs — no drop, no dupe");
    }

    @Test
    void buildAllIsTheUnionOfEveryProvidersSpecs() {
        // Build the expected concatenation from the single source of truth, in declaration order.
        List<SyncPromptSpecification> manual = new ArrayList<>();
        for (PromptProvider provider : PromptCatalog.PROVIDERS) {
            manual.addAll(specsOf(provider));
        }
        assertEquals(names(manual), names(PromptCatalog.buildAll()),
                "buildAll's prompt-name set must equal the union of every provider's prompt names");
    }

    @Test
    void buildAllPreservesProviderDeclarationOrder() {
        List<String> all = new ArrayList<>();
        for (SyncPromptSpecification spec : PromptCatalog.buildAll()) {
            all.add(spec.prompt().name());
        }
        int cursor = 0;
        for (PromptProvider provider : PromptCatalog.PROVIDERS) {
            for (SyncPromptSpecification spec : specsOf(provider)) {
                assertEquals(spec.prompt().name(), all.get(cursor),
                        "provider blocks must be contiguous and in declaration order at offset " + cursor);
                cursor++;
            }
        }
        assertEquals(all.size(), cursor, "every aggregated prompt is accounted for by a provider");
    }

    // ---- buildAll: idempotence / repeatability ------------------------------------------------

    @Test
    void buildAllProducesSameNamesAcrossCalls() {
        assertEquals(names(PromptCatalog.buildAll()), names(PromptCatalog.buildAll()),
                "buildAll must be deterministic: the same prompt-name set on repeated calls");
    }

    @Test
    void buildAllReturnsFreshListInstancePerCall() {
        assertNotSame(PromptCatalog.buildAll(), PromptCatalog.buildAll(),
                "each buildAll call must return a freshly-allocated list (no shared mutable state)");
    }

    @Test
    void mutatingReturnedListDoesNotAffectSubsequentCalls() {
        List<SyncPromptSpecification> first = PromptCatalog.buildAll();
        int baseline = first.size();
        first.clear();
        assertEquals(baseline, PromptCatalog.buildAll().size(),
                "clearing one returned list must not shrink a later catalog");
    }

    @Test
    void allPromptNamesAreUniqueAcrossProviders() {
        List<SyncPromptSpecification> all = PromptCatalog.buildAll();
        Set<String> seen = new LinkedHashSet<>();
        for (SyncPromptSpecification spec : all) {
            String name = spec.prompt().name();
            assertTrue(seen.add(name),
                    "prompt name '" + name + "' must be unique across the aggregated catalog");
        }
        assertEquals(all.size(), seen.size(),
                "no duplicate prompt names may survive aggregation");
    }

    // ---- utility-class shape ------------------------------------------------------------------

    @Test
    void classIsFinalUtility() {
        assertTrue(Modifier.isFinal(PromptCatalog.class.getModifiers()),
                "PromptCatalog is a utility class and must be final");
    }

    @Test
    void privateConstructorIsInaccessibleButInvocableViaReflection() throws Exception {
        Constructor<PromptCatalog> ctor = PromptCatalog.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                "the no-arg constructor must be private (utility-class pattern)");
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance(), "reflective construction still yields an instance");
    }
}
