package io.github.hakjuoh.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.chat.claude.ClaudeCliProvider;
import io.github.hakjuoh.chat.codex.CodexCliProvider;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link Providers}.
 *
 * <p>{@code Providers.all()} hard-codes the two shipped {@link ChatProvider} implementations, so the
 * search space cannot be swapped for test doubles. Tests therefore assert the real registry's
 * contents and contracts, the pure lookup behaviour of {@code byId}, and the environment-independent
 * invariants of {@code available} (it is a filtered subset of {@code all}, where every element's
 * {@code isAvailable()} is true). We never assert a specific outcome of {@code available()} because
 * that depends on whether the CLIs are installed on the test machine.
 */
class ProvidersTest {

    // ---------------------------------------------------------------------------------------------
    // all()
    // ---------------------------------------------------------------------------------------------

    @Test
    void allReturnsExactlyTwoProvidersInDisplayOrder() {
        List<ChatProvider> providers = Providers.all();
        assertEquals(2, providers.size(), "all() should expose exactly the two shipped providers");
        assertTrue(providers.get(0) instanceof ClaudeCliProvider,
                "first provider should be the Claude CLI provider");
        assertTrue(providers.get(1) instanceof CodexCliProvider,
                "second provider should be the Codex CLI provider");
    }

    @Test
    void allProvidersHaveExpectedIds() {
        List<ChatProvider> providers = Providers.all();
        assertEquals("claude", providers.get(0).id(), "first provider id");
        assertEquals("codex", providers.get(1).id(), "second provider id");
    }

    @Test
    void allContainsNoNulls() {
        for (ChatProvider p : Providers.all()) {
            assertNotNull(p, "all() must not contain null providers");
        }
    }

    @Test
    void allReturnsImmutableList() {
        List<ChatProvider> providers = Providers.all();
        assertThrows(UnsupportedOperationException.class,
                () -> providers.add(new ClaudeCliProvider()),
                "all() is built via List.of and must be immutable");
    }

    @Test
    void allReturnsDistinctObjectsAcrossCalls() {
        List<ChatProvider> first = Providers.all();
        List<ChatProvider> second = Providers.all();
        assertNotSame(first, second, "all() should not cache a singleton list");
        // The freshly-minted providers are also distinct instances (no static caching of providers).
        assertNotSame(first.get(0), second.get(0), "each all() call should mint fresh providers");
    }

    // ---------------------------------------------------------------------------------------------
    // available()
    // ---------------------------------------------------------------------------------------------

    @Test
    void availableIsSubsetOfAllAndOnlyContainsAvailableProviders() {
        List<ChatProvider> available = Providers.available();
        List<ChatProvider> all = Providers.all();
        assertTrue(available.size() <= all.size(),
                "available() cannot contain more providers than all()");
        for (ChatProvider p : available) {
            assertTrue(p.isAvailable(),
                    "every provider in available() must report isAvailable()==true");
        }
    }

    @Test
    void availablePreservesDisplayOrderOfAll() {
        List<ChatProvider> available = Providers.available();
        // Every element's id must appear in all()'s id sequence, and in the same relative order.
        List<String> allIds = Providers.all().stream().map(ChatProvider::id).toList();
        int lastIndex = -1;
        for (ChatProvider p : available) {
            int idx = allIds.indexOf(p.id());
            assertTrue(idx >= 0, "available provider must be one of the known providers: " + p.id());
            assertTrue(idx > lastIndex,
                    "available() must preserve the display order established by all()");
            lastIndex = idx;
        }
    }

    @Test
    void availableContainsNoNulls() {
        for (ChatProvider p : Providers.available()) {
            assertNotNull(p, "available() must not contain null providers");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // byId(String)
    // ---------------------------------------------------------------------------------------------

    @Test
    void byIdClaudeReturnsClaudeProvider() {
        ChatProvider p = Providers.byId("claude");
        assertNotNull(p, "byId(\"claude\") should resolve a provider");
        assertTrue(p instanceof ClaudeCliProvider, "byId(\"claude\") should return the Claude provider");
        assertEquals("claude", p.id(), "resolved provider's id should match the query");
    }

    @Test
    void byIdCodexReturnsCodexProvider() {
        ChatProvider p = Providers.byId("codex");
        assertNotNull(p, "byId(\"codex\") should resolve a provider");
        assertTrue(p instanceof CodexCliProvider, "byId(\"codex\") should return the Codex provider");
        assertEquals("codex", p.id(), "resolved provider's id should match the query");
    }

    @Test
    void byIdNullReturnsNull() {
        assertNull(Providers.byId(null), "byId(null) should return null via the early guard");
    }

    @Test
    void byIdUnknownReturnsNull() {
        assertNull(Providers.byId("openai"), "byId with an unknown id should return null");
    }

    @Test
    void byIdEmptyStringReturnsNull() {
        assertNull(Providers.byId(""), "no provider has an empty id, so byId(\"\") should return null");
    }

    @Test
    void byIdIsCaseSensitive() {
        assertNull(Providers.byId("Claude"), "byId should be case-sensitive: 'Claude' != 'claude'");
        assertNull(Providers.byId("CLAUDE"), "byId should be case-sensitive: 'CLAUDE' != 'claude'");
        assertNull(Providers.byId("CODEX"), "byId should be case-sensitive: 'CODEX' != 'codex'");
    }

    @Test
    void byIdWhitespacePaddedIdReturnsNull() {
        assertNull(Providers.byId(" claude "),
                "byId does not trim input, so a padded id should not match");
        assertNull(Providers.byId("claude "),
                "trailing whitespace must not match the exact id");
    }

    @Test
    void byIdResolvesFreshInstanceEachCall() {
        ChatProvider first = Providers.byId("claude");
        ChatProvider second = Providers.byId("claude");
        assertNotNull(first, "first lookup should resolve");
        assertNotNull(second, "second lookup should resolve");
        assertNotSame(first, second,
                "byId walks a fresh all() list, so each call mints a new provider instance");
        assertEquals(first.id(), second.id(), "both lookups should resolve the same logical provider");
    }

    @Test
    void byIdMatchesEveryProviderInAll() {
        for (ChatProvider known : Providers.all()) {
            ChatProvider resolved = Providers.byId(known.id());
            assertNotNull(resolved, "byId should resolve every id present in all(): " + known.id());
            assertSame(known.getClass(), resolved.getClass(),
                    "byId should resolve the same provider type for id " + known.id());
        }
    }

    @Test
    void byIdRejectsIdWithExtraSuffix() {
        assertFalse(Providers.byId("claudex") instanceof ClaudeCliProvider,
                "byId uses equals(), not prefix matching");
        assertNull(Providers.byId("claudex"), "a superset id should not resolve to the claude provider");
    }
}
