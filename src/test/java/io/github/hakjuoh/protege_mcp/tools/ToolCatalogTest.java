package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * Method-level tests for {@link ToolCatalog}, the thin aggregator that concatenates every tool
 * provider's {@code specs(ctx)} into the single list the MCP server registers at startup.
 *
 * <p>{@code ToolCatalog.buildAll} only walks the 19 provider {@code specs(ctx)} methods and
 * {@code addAll}s their results; each provider references {@code ctx} exclusively from within its
 * handler lambdas (which run at tool-invocation time), never eagerly at build time. That lets these
 * tests drive the real aggregation with a {@code ToolContext(null, null)} test double — no Protégé
 * runtime, no OntologyAccess, no server — and assert the aggregation contract directly.
 */
class ToolCatalogTest {

    /**
     * A {@link ToolContext} whose collaborators are null. Safe for {@code buildAll} because
     * {@code specs()} only captures {@code ctx} inside handler lambdas; it is never dereferenced
     * while the specifications are being built.
     */
    private static ToolContext nullCtx() {
        return new ToolContext(null, null);
    }

    /** The set of tool names in a built catalog, in insertion order (LinkedHashSet). */
    private static Set<String> names(List<SyncToolSpecification> specs) {
        Set<String> out = new LinkedHashSet<>();
        for (SyncToolSpecification spec : specs) {
            out.add(spec.tool().name());
        }
        return out;
    }

    /** Build one provider's specs the way {@code buildAll} does: register into a fresh registry, then build. */
    private static List<SyncToolSpecification> specsOf(ToolProvider provider, ToolContext ctx) {
        ToolRegistry registry = new ToolRegistry();
        provider.register(registry, ctx);
        return registry.build();
    }

    // ---- buildAll: basic result contract ------------------------------------------------------

    @Test
    void buildAllReturnsNonNullList() {
        List<SyncToolSpecification> all = ToolCatalog.buildAll(nullCtx());
        assertNotNull(all, "buildAll must never return null");
    }

    @Test
    void buildAllReturnsNonEmptyList() {
        List<SyncToolSpecification> all = ToolCatalog.buildAll(nullCtx());
        assertFalse(all.isEmpty(),
                "the server exposes many tools, so the aggregated catalog must be non-empty");
    }

    @Test
    void buildAllContainsNoNullEntries() {
        List<SyncToolSpecification> all = ToolCatalog.buildAll(nullCtx());
        for (SyncToolSpecification spec : all) {
            assertNotNull(spec, "no aggregated specification may be null");
            assertNotNull(spec.tool(), "every specification must carry a Tool");
            assertNotNull(spec.tool().name(), "every tool must have a name");
        }
    }

    @Test
    void buildAllReturnsMutableList() {
        List<SyncToolSpecification> all = ToolCatalog.buildAll(nullCtx());
        int before = all.size();
        // buildAll's contract is an ArrayList it collects into; verify it is mutable by mutating it.
        all.add(null);
        assertEquals(before + 1, all.size(), "the returned list must be mutable (ArrayList)");
    }

    // ---- buildAll: aggregation of every provider ----------------------------------------------

    @Test
    void buildAllSizeEqualsSumOfAllProviderSpecs() {
        ToolContext ctx = nullCtx();
        // Sum over the single provider list buildAll itself uses: this verifies buildAll faithfully
        // concatenates every provider with no drop/dupe (the provider list is now declared once).
        int expected = 0;
        for (var provider : ToolCatalog.PROVIDERS) {
            expected += specsOf(provider, ctx).size();
        }

        assertEquals(expected, ToolCatalog.buildAll(ctx).size(),
                "the catalog size must be the exact sum of every provider's specs — no drop, no dupe");
    }

    @Test
    void buildAllIsTheUnionOfEveryProvidersSpecs() {
        ToolContext ctx = nullCtx();
        // Build the expected concatenation from the single source of truth, in declaration order.
        List<SyncToolSpecification> manual = new ArrayList<>();
        for (var provider : ToolCatalog.PROVIDERS) {
            manual.addAll(specsOf(provider, ctx));
        }

        List<SyncToolSpecification> all = ToolCatalog.buildAll(ctx);
        assertEquals(names(manual), names(all),
                "buildAll's tool-name set must equal the union of every provider's tool names");
    }

    @Test
    void buildAllPreservesDeclarationOrderOfProviders() {
        ToolContext ctx = nullCtx();
        List<String> all = new ArrayList<>();
        for (SyncToolSpecification spec : ToolCatalog.buildAll(ctx)) {
            all.add(spec.tool().name());
        }

        // The first providers are ReadTools then ContextTools; the last is GovernanceTools.
        // Anchor a few boundary providers to prove order is preserved (not merely membership).
        assertProviderBlockContiguousAndOrdered(all, specsOf(ReadTools::register, ctx), "ReadTools");
        assertProviderBlockContiguousAndOrdered(all, specsOf(ContextTools::register, ctx), "ContextTools");
        assertProviderBlockContiguousAndOrdered(all, specsOf(GovernanceTools::register, ctx), "GovernanceTools");

        // ReadTools (first) must precede GovernanceTools (last) in the aggregate.
        List<SyncToolSpecification> read = specsOf(ReadTools::register, ctx);
        List<SyncToolSpecification> gov = specsOf(GovernanceTools::register, ctx);
        if (!read.isEmpty() && !gov.isEmpty()) {
            int firstRead = all.indexOf(read.get(0).tool().name());
            int firstGov = all.indexOf(gov.get(0).tool().name());
            assertTrue(firstRead < firstGov,
                    "ReadTools (first declared) must come before GovernanceTools (last declared)");
        }
    }

    /** Assert the given provider's tool names appear as one contiguous, in-order block in the aggregate. */
    private static void assertProviderBlockContiguousAndOrdered(List<String> aggregate,
            List<SyncToolSpecification> providerSpecs, String providerName) {
        if (providerSpecs.isEmpty()) {
            return;
        }
        int start = aggregate.indexOf(providerSpecs.get(0).tool().name());
        assertTrue(start >= 0, providerName + "'s first tool must appear in the aggregate");
        for (int i = 0; i < providerSpecs.size(); i++) {
            assertEquals(providerSpecs.get(i).tool().name(), aggregate.get(start + i),
                    providerName + " block must be contiguous and in provider order at offset " + i);
        }
    }

    // ---- buildAll: idempotence / repeatability ------------------------------------------------

    @Test
    void buildAllProducesSameToolNamesAcrossCalls() {
        assertEquals(names(ToolCatalog.buildAll(nullCtx())),
                names(ToolCatalog.buildAll(nullCtx())),
                "buildAll must be deterministic: the same tool-name set on repeated calls");
    }

    @Test
    void buildAllReturnsFreshListInstancePerCall() {
        List<SyncToolSpecification> first = ToolCatalog.buildAll(nullCtx());
        List<SyncToolSpecification> second = ToolCatalog.buildAll(nullCtx());
        assertNotSame(first, second,
                "each buildAll call must return a freshly-allocated list (no shared mutable state)");
    }

    @Test
    void mutatingReturnedListDoesNotAffectSubsequentCalls() {
        List<SyncToolSpecification> first = ToolCatalog.buildAll(nullCtx());
        int baseline = first.size();
        first.clear();
        assertEquals(baseline, ToolCatalog.buildAll(nullCtx()).size(),
                "clearing one returned list must not shrink a later catalog");
    }

    // ---- buildAll: same ctx threaded through providers ----------------------------------------

    @Test
    void buildAllAcceptsSameContextInstanceForEveryProvider() {
        // buildAll passes the exact same ctx to all 19 providers; a shared ctx must not corrupt
        // the aggregate. Building over one instance must equal building over another with the same
        // (empty) collaborators.
        ToolContext ctxA = nullCtx();
        ToolContext ctxB = nullCtx();
        assertNotSame(ctxA, ctxB, "guard: the two contexts are distinct instances");
        assertEquals(names(ToolCatalog.buildAll(ctxA)), names(ToolCatalog.buildAll(ctxB)),
                "the catalog must be independent of which (empty) ToolContext instance is threaded in");
    }

    @Test
    void toolContextAccessorsRoundTripNulls() {
        // The ctx double used throughout carries null collaborators; confirm the accessors report
        // them faithfully (documenting why buildAll tolerates a null-collaborator context).
        ToolContext ctx = nullCtx();
        assertSame(null, ctx.access(), "access() must return the injected (null) OntologyAccess");
        assertSame(null, ctx.controller(), "controller() must return the injected (null) controller");
    }

    // ---- utility-class shape ------------------------------------------------------------------

    @Test
    void classIsFinalUtility() {
        assertTrue(Modifier.isFinal(ToolCatalog.class.getModifiers()),
                "ToolCatalog is a utility class and must be final");
    }

    @Test
    void privateConstructorIsInaccessibleButInvocableViaReflection() throws Exception {
        Constructor<ToolCatalog> ctor = ToolCatalog.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                "the no-arg constructor must be private (utility-class pattern)");
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance(), "reflective construction still yields an instance");
    }

    // ---- null-ctx defensive behaviour ---------------------------------------------------------

    @Test
    void buildAllWithNullContextStillBuildsSpecifications() {
        // buildAll only captures ctx inside handler lambdas, so even a literally null ToolContext
        // must not NPE at build time — the specs are constructed without dereferencing it.
        List<SyncToolSpecification> all = ToolCatalog.buildAll(null);
        assertNotNull(all, "buildAll(null) must return a list (ctx is only used at handler time)");
        assertFalse(all.isEmpty(), "buildAll(null) must still aggregate the provider specs");
    }

    @Test
    void buildAllWithNullContextMatchesNullCollaboratorContext() {
        assertEquals(names(ToolCatalog.buildAll(null)), names(ToolCatalog.buildAll(nullCtx())),
                "a null ctx and a null-collaborator ctx yield the same tool-name set at build time");
    }

    @Test
    void allToolNamesAreUniqueAcrossProviders() {
        List<SyncToolSpecification> all = ToolCatalog.buildAll(nullCtx());
        Set<String> seen = new LinkedHashSet<>();
        for (SyncToolSpecification spec : all) {
            String name = spec.tool().name();
            assertTrue(seen.add(name),
                    "tool name '" + name + "' must be unique across the aggregated catalog");
        }
        assertEquals(all.size(), seen.size(),
                "no duplicate tool names may survive aggregation of the 19 providers");
    }

    @Test
    void buildAllSizeIsStableAcrossRepeatedCalls() {
        assertEquals(ToolCatalog.buildAll(nullCtx()).size(),
                ToolCatalog.buildAll(nullCtx()).size(),
                "the catalog size must not drift between calls");
    }
}
