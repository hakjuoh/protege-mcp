package io.github.hakjuoh.protege_mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Method-level tests for {@link OAuthStore}. Private members ({@code persist}, {@code load},
 * {@code serializeState}, {@code purgeExpired}, {@code evictLeastRecentlySeenClient},
 * {@code randomId}) are exercised indirectly through the public API together with captured
 * load/save hooks. Time-based cases use short relative deltas / large TTLs (30 days) that cannot
 * flip during a test run, so they stay deterministic.
 */
class OAuthStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Captures every JSON string handed to the save hook. */
    private static final class SaveCapture implements Consumer<String> {
        final List<String> saved = new ArrayList<>();
        volatile String last;

        @Override
        public void accept(String json) {
            saved.add(json);
            last = json;
        }
    }

    private static OAuthStore store(String staticToken) {
        return new OAuthStore(() -> staticToken, () -> null, s -> {});
    }

    private static OAuthStore store(String staticToken, SaveCapture save) {
        return new OAuthStore(() -> staticToken, () -> null, save);
    }

    /** A store on a hand-cranked clock, for the time-based cleanup rules. */
    private static OAuthStore store(java.util.concurrent.atomic.AtomicLong clock, SaveCapture save) {
        return new OAuthStore(() -> "tok", () -> null, save == null ? s -> {} : save, true, clock::get);
    }

    // ------------------------------------------------------------- constructor / load

    @Test
    void constructorWithAllHooksNonNullBuildsEmptyStore() {
        SaveCapture save = new SaveCapture();
        OAuthStore s = new OAuthStore(() -> "tok", () -> null, save);
        assertTrue(s.listClients().isEmpty(), "fresh store has no clients");
        assertEquals(0L, s.getStaticTokenLastSeen(), "static token never seen yet");
    }

    @Test
    void constructorWithLoadStateNullIsNoOp() {
        OAuthStore s = new OAuthStore(() -> "tok", null, x -> {});
        assertTrue(s.listClients().isEmpty(), "null loadState leaves store empty");
    }

    @Test
    void constructorWithSaveStateNullDisablesPersistenceWithoutError() {
        OAuthStore s = new OAuthStore(() -> "tok", () -> null, null);
        // registerClient calls persist(); with a null saveState it must be a silent no-op.
        OAuthStore.Client c = s.registerClient(List.of("http://localhost/cb"), "n");
        assertNotNull(c, "client still registered even with persistence disabled");
    }

    @Test
    void constructorWithStaticTokenNullMeansNoFallback() {
        OAuthStore s = new OAuthStore(() -> null, () -> null, x -> {});
        assertFalse(s.isValidAccessToken("anything"), "no static token configured -> no match");
    }

    @Test
    void loadStateReturningEmptyStringLeavesStoreEmpty() {
        OAuthStore s = new OAuthStore(() -> "tok", () -> "", x -> {});
        assertTrue(s.listClients().isEmpty(), "empty JSON leaves store empty");
    }

    @Test
    void loadStateThrowingIsCaughtAndStoreStartsFresh() {
        Supplier<String> boom = () -> {
            throw new RuntimeException("prefs unavailable");
        };
        OAuthStore s = new OAuthStore(() -> "tok", boom, x -> {});
        assertTrue(s.listClients().isEmpty(), "load exception swallowed, empty store");
    }

    @Test
    void loadWithInvalidJsonIsCaughtAndStoreStartsFresh() {
        OAuthStore s = new OAuthStore(() -> "tok", () -> "{ this is not json", x -> {});
        assertTrue(s.listClients().isEmpty(), "malformed JSON swallowed, empty store");
    }

    @Test
    void loadRestoresPersistedClientsAndAccessTokens() {
        // Build a real persisted blob from one store, then load it into a second.
        SaveCapture save = new SaveCapture();
        OAuthStore first = store("tok", save);
        OAuthStore.Client c = first.registerClient(List.of("http://localhost/cb"), "app");
        OAuthStore.Tokens t = first.issueTokens(c.clientId, "read", "res");
        String blob = save.last;
        assertNotNull(blob, "state was persisted");

        OAuthStore second = new OAuthStore(() -> "tok", () -> blob, x -> {});
        OAuthStore.Client restored = second.client(c.clientId);
        assertNotNull(restored, "client restored from persisted JSON");
        assertEquals("app", restored.clientName, "clientName restored");
        assertTrue(restored.redirectUris.contains("http://localhost/cb"), "redirectUris restored");
        assertTrue(second.isValidAccessToken(t.accessToken), "access token restored and valid");
    }

    @Test
    void refreshPreservesGrantIdSoPinnedSessionsSurvive() {
        // Broker session pins are keyed by grantId; a refresh must CONTINUE the same grant (rotating
        // the tokens) rather than minting a new grantId that would 403 every pinned MCP session on the
        // standard 401 -> refresh -> retry flow.
        OAuthStore s = store("tok", null);
        OAuthStore.Client c = s.registerClient(List.of("http://localhost/cb"), "app");
        OAuthStore.Tokens first = s.issueTokens(c.clientId, "read", "res");
        String grantBefore = s.authenticate(first.accessToken).grantId();

        OAuthStore.Tokens refreshed = s.refresh(first.refreshToken);
        assertNotNull(refreshed, "refresh returns a new token pair");
        assertNotEquals(first.accessToken, refreshed.accessToken, "the access token rotates on refresh");
        String grantAfter = s.authenticate(refreshed.accessToken).grantId();
        assertEquals(grantBefore, grantAfter, "the grant id is preserved across a token refresh");
    }

    @Test
    void deferredLoadStartsEmptyUntilLoadPersistedIsCalled() {
        // The controller constructs the store BEFORE the port is bound and hydrates it only when the
        // bind proves the server holds the configured port: a fallback-port server must never see the
        // persisted grants (revocation on the owner would not reach its memory).
        SaveCapture save = new SaveCapture();
        OAuthStore first = store("tok", save);
        OAuthStore.Client c = first.registerClient(List.of("http://localhost/cb"), "app");
        OAuthStore.Tokens t = first.issueTokens(c.clientId, "read", "res");
        String blob = save.last;

        OAuthStore deferred = new OAuthStore(() -> "tok", () -> blob, x -> {}, false);
        assertTrue(deferred.listClients().isEmpty(),
                "with loadPersistedNow=false the persisted clients must not be visible");
        assertFalse(deferred.isValidAccessToken(t.accessToken),
                "a persisted grant must not authenticate on a store that was never hydrated");

        deferred.loadPersisted();
        assertNotNull(deferred.client(c.clientId), "loadPersisted() hydrates the persisted clients");
        assertTrue(deferred.isValidAccessToken(t.accessToken),
                "…and the persisted grant authenticates after hydration");
    }

    @Test
    void loadDropsExpiredAccessTokensButKeepsClientAndRefresh() throws Exception {
        // Hand-craft JSON with an already-expired access token and a live refresh token.
        String json = "{"
                + "\"clients\":[{\"clientId\":\"mcpc_x\",\"clientName\":\"n\","
                + "\"redirectUris\":[\"http://localhost/cb\"],\"registeredAt\":1,\"lastSeenAt\":0}],"
                + "\"accessTokens\":[{\"token\":\"mcpt_dead\",\"clientId\":\"mcpc_x\","
                + "\"grantId\":\"g\",\"scope\":\"s\",\"resource\":\"r\",\"expiresAt\":1}],"
                + "\"refreshTokens\":[{\"token\":\"mcpr_live\",\"clientId\":\"mcpc_x\","
                + "\"grantId\":\"g\",\"scope\":\"s\",\"resource\":\"r\","
                + "\"expiresAt\":" + Long.MAX_VALUE + "}]}";
        OAuthStore s = new OAuthStore(() -> "tok", () -> json, x -> {});
        assertNotNull(s.client("mcpc_x"), "client survives load");
        assertFalse(s.isValidAccessToken("mcpt_dead"), "expired access token purged on load");
        assertNotNull(s.refresh("mcpr_live"), "refresh token survives (Long.MAX_VALUE expiry)");
    }

    @Test
    void loadHandlesMissingTokenFieldBySkippingGrant() {
        // accessTokens entry with no "token" field must be skipped, not throw.
        String json = "{\"clients\":[],"
                + "\"accessTokens\":[{\"clientId\":\"c\",\"grantId\":\"g\","
                + "\"scope\":\"s\",\"resource\":\"r\",\"expiresAt\":" + Long.MAX_VALUE + "}],"
                + "\"refreshTokens\":[]}";
        OAuthStore s = new OAuthStore(() -> "tok", () -> json, x -> {});
        assertTrue(s.listClients().isEmpty(), "no clients; malformed grant skipped without error");
    }

    // ------------------------------------------------------------- registerClient

    @Test
    void registerClientReturnsClientWithMcpcPrefixAndFields() {
        OAuthStore s = store("tok");
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb", "http://b/cb"), "MyApp");
        assertTrue(c.clientId.startsWith("mcpc_"), "client id has mcpc_ prefix");
        assertEquals("MyApp", c.clientName, "clientName preserved");
        assertTrue(c.redirectUris.contains("http://a/cb"), "first uri present");
        assertTrue(c.redirectUris.contains("http://b/cb"), "second uri present");
        assertEquals(2, c.redirectUris.size(), "both uris stored");
    }

    @Test
    void registerClientPersistsState() {
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        s.registerClient(List.of("http://a/cb"), "app");
        assertFalse(save.saved.isEmpty(), "registerClient triggered a persist");
    }

    @Test
    void registerClientGeneratesDistinctIds() {
        OAuthStore s = store("tok");
        OAuthStore.Client a = s.registerClient(List.of("http://a/cb"), "a");
        OAuthStore.Client b = s.registerClient(List.of("http://b/cb"), "b");
        assertNotEquals(a.clientId, b.clientId, "each registration mints a unique id");
    }

    @Test
    void registerClientAcceptsEmptyRedirectUris() {
        OAuthStore s = store("tok");
        OAuthStore.Client c = s.registerClient(Collections.emptyList(), "app");
        assertTrue(c.redirectUris.isEmpty(), "empty redirect list stored as empty set");
    }

    @Test
    void registerClientAcceptsNullAndEmptyClientName() {
        OAuthStore s = store("tok");
        OAuthStore.Client nullName = s.registerClient(List.of("http://a/cb"), null);
        assertNull(nullName.clientName, "null clientName preserved");
        OAuthStore.Client emptyName = s.registerClient(List.of("http://a/cb"), "");
        assertEquals("", emptyName.clientName, "empty clientName preserved");
    }

    @Test
    void registerClientDeduplicatesRedirectUris() {
        OAuthStore s = store("tok");
        // Backed by a LinkedHashSet, so duplicate URIs collapse to one.
        OAuthStore.Client c = s.registerClient(Arrays.asList("http://a/cb", "http://a/cb"), "app");
        assertEquals(1, c.redirectUris.size(), "duplicate redirect uris collapsed");
    }

    // ------------------------------------------------------------- client()

    @Test
    void clientReturnsRegisteredInstance() {
        OAuthStore s = store("tok");
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        assertSame(c, s.client(c.clientId), "client() returns the exact registered instance");
    }

    @Test
    void clientUnknownIdReturnsNull() {
        OAuthStore s = store("tok");
        assertNull(s.client("mcpc_missing"), "unknown id -> null");
    }

    @Test
    void clientNullIdReturnsNull() {
        OAuthStore s = store("tok");
        // Null-guarded: a null id resolves to no client rather than throwing (matches the "unknown
        // -> null" contract and the ConcurrentHashMap null-key restriction).
        assertNull(s.client(null), "null id -> null");
    }

    // ------------------------------------------------------------- newAuthCode

    @Test
    void newAuthCodeHasMcpaPrefix() {
        OAuthStore s = store("tok");
        String code = s.newAuthCode("cid", "http://a/cb", "chal", "read", "res");
        assertTrue(code.startsWith("mcpa_"), "auth code has mcpa_ prefix");
    }

    @Test
    void newAuthCodeIsUniqueAcrossCalls() {
        OAuthStore s = store("tok");
        String a = s.newAuthCode("cid", "http://a/cb", "chal", "read", "res");
        String b = s.newAuthCode("cid", "http://a/cb", "chal", "read", "res");
        assertNotEquals(a, b, "each auth code is unique");
    }

    @Test
    void newAuthCodeEmbedsAllParametersRetrievableViaConsume() {
        OAuthStore s = store("tok");
        String code = s.newAuthCode("cid", "http://a/cb", "chal", "read", "res");
        OAuthStore.AuthCode a = s.consumeAuthCode(code);
        assertNotNull(a, "code consumable");
        assertEquals("cid", a.clientId, "clientId embedded");
        assertEquals("http://a/cb", a.redirectUri, "redirectUri embedded");
        assertEquals("chal", a.codeChallenge, "codeChallenge embedded");
        assertEquals("read", a.scope, "scope embedded");
        assertEquals("res", a.resource, "resource embedded");
    }

    @Test
    void newAuthCodeAcceptsNullOptionalFields() {
        OAuthStore s = store("tok");
        String code = s.newAuthCode(null, null, null, null, null);
        OAuthStore.AuthCode a = s.consumeAuthCode(code);
        assertNotNull(a, "code with null fields still consumable");
        assertNull(a.clientId, "null clientId preserved");
        assertNull(a.scope, "null scope preserved");
    }

    // ------------------------------------------------------------- consumeAuthCode

    @Test
    void consumeAuthCodeIsSingleUse() {
        OAuthStore s = store("tok");
        String code = s.newAuthCode("cid", "http://a/cb", "chal", "read", "res");
        assertNotNull(s.consumeAuthCode(code), "first consume returns the code");
        assertNull(s.consumeAuthCode(code), "second consume returns null (single-use)");
    }

    @Test
    void consumeAuthCodeUnknownReturnsNull() {
        OAuthStore s = store("tok");
        assertNull(s.consumeAuthCode("mcpa_missing"), "unknown code -> null");
    }

    @Test
    void consumeAuthCodeNullReturnsNull() {
        OAuthStore s = store("tok");
        // Null-guarded so a malformed /oauth/token request (no 'code') yields a clean invalid_grant,
        // not a 500 from ConcurrentHashMap.remove(null).
        assertNull(s.consumeAuthCode(null), "null code -> null");
    }

    // ------------------------------------------------------------- issueTokens

    @Test
    void issueTokensReturnsPrefixedDistinctTokens() {
        OAuthStore s = store("tok");
        OAuthStore.Tokens t = s.issueTokens("cid", "read", "res");
        assertTrue(t.accessToken.startsWith("mcpt_"), "access token has mcpt_ prefix");
        assertTrue(t.refreshToken.startsWith("mcpr_"), "refresh token has mcpr_ prefix");
        assertNotEquals(t.accessToken, t.refreshToken, "access and refresh tokens differ");
    }

    @Test
    void issueTokensExpiresInSecondsIsThirtyDays() {
        OAuthStore s = store("tok");
        OAuthStore.Tokens t = s.issueTokens("cid", "read", "res");
        assertEquals(30L * 24 * 3600, t.expiresInSeconds, "expiresInSeconds is 30 days in seconds");
    }

    @Test
    void issueTokensCarriesScope() {
        OAuthStore s = store("tok");
        OAuthStore.Tokens t = s.issueTokens("cid", "read write", "res");
        assertEquals("read write", t.scope, "scope echoed on Tokens");
    }

    @Test
    void issueTokensAccessTokenIsImmediatelyValid() {
        OAuthStore s = store("tok");
        String cid = s.registerClient(List.of("http://a/cb"), "app").clientId;
        OAuthStore.Tokens t = s.issueTokens(cid, "read", "res");
        assertTrue(s.isValidAccessToken(t.accessToken), "freshly issued access token validates");
    }

    @Test
    void issueTokensDropsPriorGrantForSameClient() {
        OAuthStore s = store("tok");
        String cid = s.registerClient(List.of("http://a/cb"), "app").clientId;
        OAuthStore.Tokens first = s.issueTokens(cid, "read", "res");
        OAuthStore.Tokens second = s.issueTokens(cid, "read", "res");
        assertFalse(s.isValidAccessToken(first.accessToken), "prior access token dropped");
        assertNull(s.refresh(first.refreshToken), "prior refresh token dropped");
        assertTrue(s.isValidAccessToken(second.accessToken), "new access token valid");
    }

    @Test
    void issueTokensKeepsSeparateGrantsForDifferentClients() {
        OAuthStore s = store("tok");
        String clientA = s.registerClient(List.of("http://a/cb"), "A").clientId;
        String clientB = s.registerClient(List.of("http://b/cb"), "B").clientId;
        OAuthStore.Tokens a = s.issueTokens(clientA, "read", "res");
        OAuthStore.Tokens b = s.issueTokens(clientB, "read", "res");
        assertTrue(s.isValidAccessToken(a.accessToken), "client A token unaffected by client B issue");
        assertTrue(s.isValidAccessToken(b.accessToken), "client B token valid");
    }

    @Test
    void issueTokensPersistsState() {
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        s.issueTokens("cid", "read", "res");
        assertFalse(save.saved.isEmpty(), "issueTokens triggered a persist");
    }

    @Test
    void issueTokensAcceptsNullScopeAndResource() {
        OAuthStore s = store("tok");
        String cid = s.registerClient(List.of("http://a/cb"), "app").clientId;
        OAuthStore.Tokens t = s.issueTokens(cid, null, null);
        assertNull(t.scope, "null scope carried through");
        assertTrue(s.isValidAccessToken(t.accessToken), "token valid despite null scope/resource");
    }

    // ------------------------------------------------------------- refresh

    @Test
    void refreshReturnsNewTokensAndRotates() {
        OAuthStore s = store("tok");
        String cid = s.registerClient(List.of("http://a/cb"), "app").clientId;
        OAuthStore.Tokens original = s.issueTokens(cid, "read", "res");
        OAuthStore.Tokens rotated = s.refresh(original.refreshToken);
        assertNotNull(rotated, "refresh returns new token pair");
        assertNotEquals(original.accessToken, rotated.accessToken, "access token rotated");
        assertNotEquals(original.refreshToken, rotated.refreshToken, "refresh token rotated");
    }

    @Test
    void refreshInvalidatesOldGrantPair() {
        OAuthStore s = store("tok");
        String cid = s.registerClient(List.of("http://a/cb"), "app").clientId;
        OAuthStore.Tokens original = s.issueTokens(cid, "read", "res");
        s.refresh(original.refreshToken);
        assertFalse(s.isValidAccessToken(original.accessToken), "old access token invalid after refresh");
        assertNull(s.refresh(original.refreshToken), "old refresh token invalid after refresh");
    }

    @Test
    void refreshPreservesScopeAndResource() {
        OAuthStore s = store("tok");
        String cid = s.registerClient(List.of("http://a/cb"), "app").clientId;
        OAuthStore.Tokens original = s.issueTokens(cid, "read write", "res");
        OAuthStore.Tokens rotated = s.refresh(original.refreshToken);
        assertEquals("read write", rotated.scope, "scope carried across refresh");
    }

    @Test
    void refreshFailsClosedWhenTheClientRecordIsGone() {
        // Fail closed like isValidAccessToken: minting fresh tokens for a client-less grant would
        // trap the client in a refresh-then-401 loop instead of sending it back to re-register.
        OAuthStore s = store("tok");
        OAuthStore.Tokens orphan = s.issueTokens("mcpc_never_registered", "read", "res");
        assertNull(s.refresh(orphan.refreshToken),
                "a refresh grant without a client record must not mint tokens");
    }

    @Test
    void refreshNullReturnsNull() {
        OAuthStore s = store("tok");
        // Null-guarded so a malformed /oauth/token request (no 'refresh_token') yields a clean
        // invalid_grant, not a 500 from ConcurrentHashMap.get(null).
        assertNull(s.refresh(null), "null refresh token -> null");
    }

    @Test
    void refreshUnknownTokenReturnsNull() {
        OAuthStore s = store("tok");
        assertNull(s.refresh("mcpr_missing"), "unknown refresh token -> null");
    }

    // ------------------------------------------------------------- isValidAccessToken

    @Test
    void isValidAccessTokenNullReturnsFalse() {
        OAuthStore s = store("tok");
        assertFalse(s.isValidAccessToken(null), "null token -> false");
    }

    @Test
    void isValidAccessTokenMatchesStaticTokenAndUpdatesLastSeen() {
        OAuthStore s = store("staticSecret");
        assertEquals(0L, s.getStaticTokenLastSeen(), "static last-seen starts at 0");
        assertTrue(s.isValidAccessToken("staticSecret"), "static token accepted");
        assertTrue(s.getStaticTokenLastSeen() > 0L, "static last-seen updated after match");
    }

    @Test
    void isValidAccessTokenStaticSupplierNullNoFallback() {
        OAuthStore s = store(null);
        assertFalse(s.isValidAccessToken("anything"), "null static token supplier -> no static match");
    }

    @Test
    void isValidAccessTokenOAuthTokenTrueWhenClientPresent() {
        OAuthStore s = store("tok");
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        OAuthStore.Tokens t = s.issueTokens(c.clientId, "read", "res");
        assertTrue(s.isValidAccessToken(t.accessToken), "valid OAuth token with live client -> true");
    }

    @Test
    void isValidAccessTokenFalseWhenClientMissing() {
        // Fail-closed: an orphaned-but-unexpired grant (its client record gone, only reachable from
        // corrupted persisted state) is rejected — a token is honoured only while its client exists.
        String json = "{\"clients\":[],"
                + "\"accessTokens\":[{\"token\":\"mcpt_orphan\",\"clientId\":\"gone\","
                + "\"grantId\":\"g\",\"scope\":\"read\",\"resource\":\"res\","
                + "\"expiresAt\":" + Long.MAX_VALUE + "}],\"refreshTokens\":[]}";
        OAuthStore reloaded = new OAuthStore(() -> "tok", () -> json, x -> {});
        assertFalse(reloaded.isValidAccessToken("mcpt_orphan"),
                "an unexpired grant whose client record is gone is rejected (fail-closed)");
    }

    @Test
    void isValidAccessTokenUnknownTokenReturnsFalse() {
        OAuthStore s = store("tok");
        assertFalse(s.isValidAccessToken("mcpt_unknown"), "unknown token -> false");
    }

    @Test
    void isValidAccessTokenExpiredReturnsFalse() {
        // Load a client + already-expired access token so validation must fail.
        String json = "{"
                + "\"clients\":[{\"clientId\":\"mcpc_x\",\"clientName\":\"n\","
                + "\"redirectUris\":[],\"registeredAt\":1,\"lastSeenAt\":0}],"
                + "\"accessTokens\":[{\"token\":\"mcpt_expired\",\"clientId\":\"mcpc_x\","
                + "\"grantId\":\"g\",\"scope\":\"s\",\"resource\":\"r\",\"expiresAt\":1}],"
                + "\"refreshTokens\":[]}";
        OAuthStore s = new OAuthStore(() -> "tok", () -> json, x -> {});
        assertFalse(s.isValidAccessToken("mcpt_expired"), "expired access token -> false");
    }

    @Test
    void isValidAccessTokenUpdatesClientLastSeen() {
        OAuthStore s = store("tok");
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        assertEquals(0L, c.lastSeenAt.get(), "client last-seen starts at 0");
        OAuthStore.Tokens t = s.issueTokens(c.clientId, "read", "res");
        long afterIssue = c.lastSeenAt.get();
        assertTrue(afterIssue > 0L, "issuing tokens counts as client activity");
        sleepAtLeastAMilli();
        assertTrue(s.isValidAccessToken(t.accessToken), "token validated");
        assertTrue(c.lastSeenAt.get() > afterIssue, "client lastSeenAt updated on validation");
    }

    @Test
    void isValidAccessTokenStaticMatchTakesPrecedenceOverOAuthLookup() {
        // A token equal to the static token is accepted even without any OAuth grant.
        OAuthStore s = store("mcpt_looks_like_oauth");
        assertTrue(s.isValidAccessToken("mcpt_looks_like_oauth"),
                "static match short-circuits before OAuth grant lookup");
    }

    // ------------------------------------------------------------- revokeClient

    @Test
    void revokeClientExistingReturnsTrueAndRemovesEverything() {
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        OAuthStore.Tokens t = s.issueTokens(c.clientId, "read", "res");
        String code = s.newAuthCode(c.clientId, "http://a/cb", "chal", "read", "res");

        assertTrue(s.revokeClient(c.clientId), "revoke existing client -> true");
        assertNull(s.client(c.clientId), "client removed");
        assertFalse(s.isValidAccessToken(t.accessToken), "client's access token removed");
        assertNull(s.refresh(t.refreshToken), "client's refresh token removed");
        assertNull(s.consumeAuthCode(code), "client's pending auth code removed");
    }

    @Test
    void revokeClientUnknownReturnsFalse() {
        OAuthStore s = store("tok");
        assertFalse(s.revokeClient("mcpc_missing"), "unknown client -> false");
    }

    @Test
    void revokeClientNullReturnsFalse() {
        OAuthStore s = store("tok");
        assertFalse(s.revokeClient(null), "null client id -> false");
    }

    @Test
    void revokeClientPersistsOnlyWhenClientExisted() {
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        int beforeUnknownRevoke = save.saved.size();
        assertFalse(s.revokeClient("mcpc_absent"), "absent client revoke -> false");
        assertEquals(beforeUnknownRevoke, save.saved.size(),
                "no persist when client did not exist");
        assertTrue(s.revokeClient(c.clientId), "existing client revoke -> true");
        assertTrue(save.saved.size() > beforeUnknownRevoke, "persist happened for real revoke");
    }

    // ------------------------------------------------------------- revokeToken

    @Test
    void revokeTokenViaAccessTokenDropsBothSiblings() {
        OAuthStore s = store("tok");
        OAuthStore.Tokens t = s.issueTokens("cid", "read", "res");
        assertTrue(s.revokeToken(t.accessToken), "revoke by access token -> true");
        assertFalse(s.isValidAccessToken(t.accessToken), "access token dropped");
        assertNull(s.refresh(t.refreshToken), "sibling refresh token dropped");
    }

    @Test
    void revokeTokenViaRefreshTokenDropsBothSiblings() {
        OAuthStore s = store("tok");
        OAuthStore.Tokens t = s.issueTokens("cid", "read", "res");
        assertTrue(s.revokeToken(t.refreshToken), "revoke by refresh token -> true");
        assertFalse(s.isValidAccessToken(t.accessToken), "sibling access token dropped");
        assertNull(s.refresh(t.refreshToken), "refresh token dropped");
    }

    @Test
    void revokeTokenUnknownReturnsFalse() {
        OAuthStore s = store("tok");
        assertFalse(s.revokeToken("mcpt_unknown"), "unknown token -> false");
    }

    @Test
    void revokeTokenNullReturnsFalse() {
        OAuthStore s = store("tok");
        assertFalse(s.revokeToken(null), "null token -> false");
    }

    @Test
    void revokeTokenPersistsWhenTokenExisted() {
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        OAuthStore.Tokens t = s.issueTokens("cid", "read", "res");
        int before = save.saved.size();
        assertTrue(s.revokeToken(t.accessToken), "existing token revoke -> true");
        assertTrue(save.saved.size() > before, "revokeToken triggered a persist");
    }

    // ------------------------------------------------------------- listClients

    @Test
    void listClientsEmptyStoreReturnsEmptyList() {
        OAuthStore s = store("tok");
        assertTrue(s.listClients().isEmpty(), "no clients -> empty list");
    }

    @Test
    void listClientsIncludesAllRegisteredWithFields() {
        OAuthStore s = store("tok");
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        List<OAuthStore.ClientInfo> infos = s.listClients();
        assertEquals(1, infos.size(), "one client listed");
        OAuthStore.ClientInfo info = infos.get(0);
        assertEquals(c.clientId, info.clientId, "clientId in info");
        assertEquals("app", info.clientName, "clientName in info");
        assertEquals(c.registeredAt, info.registeredAt, "registeredAt in info");
        assertEquals(0L, info.lastSeenAt, "never-seen client has lastSeenAt 0");
    }

    @Test
    void listClientsCountsOnlyActiveAccessTokens() {
        OAuthStore s = store("tok");
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        s.issueTokens(c.clientId, "read", "res");
        OAuthStore.ClientInfo info = s.listClients().get(0);
        assertEquals(1, info.activeAccessTokens, "one active access token counted");
        assertTrue(info.latestAccessExpiry > System.currentTimeMillis(),
                "latestAccessExpiry is in the future for a live token");
    }

    @Test
    void listClientsZeroTokensHasZeroCountAndZeroExpiry() {
        OAuthStore s = store("tok");
        s.registerClient(List.of("http://a/cb"), "app");
        OAuthStore.ClientInfo info = s.listClients().get(0);
        assertEquals(0, info.activeAccessTokens, "no tokens -> count 0");
        assertEquals(0L, info.latestAccessExpiry, "no tokens -> latestAccessExpiry 0");
    }

    @Test
    void listClientsSortedByRegisteredAtDescending() {
        OAuthStore s = store("tok");
        OAuthStore.Client first = s.registerClient(List.of("http://a/cb"), "first");
        // Ensure a distinct registeredAt so ordering is deterministic.
        sleepAtLeastAMilli();
        OAuthStore.Client second = s.registerClient(List.of("http://b/cb"), "second");
        List<OAuthStore.ClientInfo> infos = s.listClients();
        assertEquals(2, infos.size(), "both clients listed");
        assertTrue(infos.get(0).registeredAt >= infos.get(1).registeredAt,
                "newest registration first");
        // The newest must be 'second' unless the clock did not advance; assert by id when it did.
        if (second.registeredAt != first.registeredAt) {
            assertEquals(second.clientId, infos.get(0).clientId, "second (newer) is first");
        }
    }

    // ------------------------------------------------------------- getStaticTokenLastSeen

    @Test
    void getStaticTokenLastSeenStartsAtZero() {
        OAuthStore s = store("tok");
        assertEquals(0L, s.getStaticTokenLastSeen(), "no static validation yet -> 0");
    }

    @Test
    void getStaticTokenLastSeenUpdatesAcrossValidations() {
        OAuthStore s = store("tok");
        s.isValidAccessToken("tok");
        long first = s.getStaticTokenLastSeen();
        assertTrue(first > 0L, "updated after first static validation");
        sleepAtLeastAMilli();
        s.isValidAccessToken("tok");
        assertTrue(s.getStaticTokenLastSeen() >= first, "monotonic across validations");
    }

    // ------------------------------------------------------------- persist / serializeState (via JSON)

    @Test
    void persistedJsonContainsClientsAccessAndRefreshKeysButNotCodes() throws Exception {
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        s.issueTokens(c.clientId, "read", "res");
        // A code exists in memory but must not be serialized.
        s.newAuthCode(c.clientId, "http://a/cb", "chal", "read", "res");
        // Trigger another persist so 'last' reflects post-code state (codes aren't persisted anyway).
        s.issueTokens(c.clientId, "read2", "res");

        JsonNode root = MAPPER.readTree(save.last);
        assertTrue(root.has("clients"), "clients key present");
        assertTrue(root.has("accessTokens"), "accessTokens key present");
        assertTrue(root.has("refreshTokens"), "refreshTokens key present");
        assertFalse(root.has("codes"), "codes are never persisted");
    }

    @Test
    void serializedClientCarriesAllFields() throws Exception {
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        s.registerClient(List.of("http://a/cb", "http://b/cb"), "app");
        JsonNode client = MAPPER.readTree(save.last).path("clients").get(0);
        assertEquals("app", client.path("clientName").asText(), "clientName serialized");
        assertTrue(client.path("clientId").asText().startsWith("mcpc_"), "clientId serialized");
        assertEquals(2, client.path("redirectUris").size(), "redirectUris serialized as list");
        assertTrue(client.has("registeredAt"), "registeredAt serialized");
        assertTrue(client.has("lastSeenAt"), "lastSeenAt serialized");
    }

    @Test
    void serializedGrantCarriesAllFields() throws Exception {
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        s.issueTokens("cid", "read", "res");
        JsonNode grant = MAPPER.readTree(save.last).path("accessTokens").get(0);
        assertTrue(grant.path("token").asText().startsWith("mcpt_"), "token serialized");
        assertEquals("cid", grant.path("clientId").asText(), "clientId serialized");
        assertTrue(grant.has("grantId"), "grantId serialized");
        assertEquals("read", grant.path("scope").asText(), "scope serialized");
        assertEquals("res", grant.path("resource").asText(), "resource serialized");
        assertTrue(grant.has("expiresAt"), "expiresAt serialized");
    }

    @Test
    void emptyStorePersistsMinimalValidJson() throws Exception {
        // registerClient then revoke to leave an empty store and a fresh persist.
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        s.revokeClient(c.clientId);
        JsonNode root = MAPPER.readTree(save.last);
        assertEquals(0, root.path("clients").size(), "no clients after revoke");
        assertEquals(0, root.path("accessTokens").size(), "no access tokens");
        assertEquals(0, root.path("refreshTokens").size(), "no refresh tokens");
    }

    // ------------------------------------------------------------- eviction (persist size guard)

    @Test
    void oversizedStateEvictsLeastRecentlySeenClientsToFit() throws Exception {
        // Register enough clients (with long redirect URIs) to blow past the 8000-char cap so the
        // persist() eviction loop must run. Then verify the persisted blob stays within the cap.
        SaveCapture save = new SaveCapture();
        OAuthStore s = store("tok", save);
        String longUri = "http://localhost/callback/" + "x".repeat(400);
        for (int i = 0; i < 40; i++) {
            s.registerClient(List.of(longUri + i), "client-" + i);
        }
        assertNotNull(save.last, "state persisted");
        assertTrue(save.last.length() <= 8000,
                "persisted blob evicted down to <= 8000 chars, got " + save.last.length());
        // Some clients must have been evicted (fewer persisted than registered).
        JsonNode root = MAPPER.readTree(save.last);
        assertTrue(root.path("clients").size() < 40, "eviction dropped some clients");
    }

    @Test
    void uncappedFileStoreKeepsEveryClientPastThePreferencesLimit() throws Exception {
        SaveCapture save = new SaveCapture();
        OAuthStore s = new OAuthStore(() -> "tok", () -> null, save, true, 0);
        String longUri = "http://localhost/callback/" + "x".repeat(400);
        for (int i = 0; i < 40; i++) {
            OAuthStore.Client client = s.registerClient(List.of(longUri + i), "client-" + i);
            s.issueTokens(client.clientId, "mcp", null);
        }

        assertTrue(save.last.length() > 8000, "file-backed state is allowed past the prefs cap");
        assertEquals(40, s.listClients().size(), "no active file-backed client is evicted");
        assertEquals(40, MAPPER.readTree(save.last).path("clients").size());
    }

    @Test
    void evictedClientAccessTokenIsRejected() {
        // The persist() size guard evicts the least-recently-seen client TOGETHER with its tokens, so an
        // evicted client's access token must stop validating — the runtime counterpart to the
        // corrupted-state orphan rejection in isValidAccessTokenFalseWhenClientMissing. Deterministic on
        // a hand-cranked clock: 'victim' is issued its tokens first (issuing bumps lastSeenAt), then every
        // flood client is issued tokens at a strictly later clock value, leaving the victim as the
        // least-recently-seen client and therefore the first evicted.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client victim = s.registerClient(List.of("http://a/cb"), "victim");
        OAuthStore.Tokens t = s.issueTokens(victim.clientId, "read", "res");
        String longUri = "http://localhost/callback/" + "x".repeat(400);
        for (int i = 0; i < 40; i++) {
            clock.addAndGet(1_000);
            OAuthStore.Client flood = s.registerClient(List.of(longUri + i), "client-" + i);
            s.issueTokens(flood.clientId, "read", "res");
        }
        assertNull(s.client(victim.clientId), "least-recently-seen client evicted by the size guard");
        assertFalse(s.isValidAccessToken(t.accessToken),
                "an evicted client's access token no longer validates (dropped with its client)");
    }

    // ------------------------------------------------------------- automatic cleanup

    @Test
    void issuingToAReRegisteredClientSupersedesItsOldSameNameRegistration() {
        // The reconnect case: a client lost its credentials, re-registered under the same name and
        // completed authorization — its previous registration (and tokens) must clean up on its own.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client old = s.registerClient(List.of("http://a/cb"), "Claude Code");
        OAuthStore.Tokens oldTokens = s.issueTokens(old.clientId, "read", "res");
        clock.addAndGet(60_000);
        OAuthStore.Client fresh = s.registerClient(List.of("http://b/cb"), "Claude Code");
        s.issueTokens(fresh.clientId, "read", "res");
        assertNull(s.client(old.clientId), "the superseded registration is dropped");
        assertFalse(s.isValidAccessToken(oldTokens.accessToken),
                "the superseded registration's tokens die with it");
        assertNotNull(s.client(fresh.clientId), "the re-registered client stays");
    }

    @Test
    void supersedeLeavesDifferentlyNamedClientsAlone() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client other = s.registerClient(List.of("http://a/cb"), "mcp-inspector");
        s.issueTokens(other.clientId, "read", "res");
        clock.addAndGet(60_000);
        OAuthStore.Client fresh = s.registerClient(List.of("http://b/cb"), "Claude Code");
        s.issueTokens(fresh.clientId, "read", "res");
        assertNotNull(s.client(other.clientId), "a different client app is not a reconnect victim");
    }

    @Test
    void supersedeKeepsASameNameClientSeenAfterTheSuccessorRegistered() {
        // Two genuinely live same-name clients: the older one authenticated a request AFTER the
        // newcomer registered, proving it is alive — it must not be yanked.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client old = s.registerClient(List.of("http://a/cb"), "Claude Code");
        OAuthStore.Tokens oldTokens = s.issueTokens(old.clientId, "read", "res");
        clock.addAndGet(60_000);
        OAuthStore.Client fresh = s.registerClient(List.of("http://b/cb"), "Claude Code");
        clock.addAndGet(1_000);
        assertTrue(s.isValidAccessToken(oldTokens.accessToken), "old client makes a live request");
        s.issueTokens(fresh.clientId, "read", "res");
        assertNotNull(s.client(old.clientId),
                "a same-name client seen after the successor registered is demonstrably alive");
        assertTrue(s.isValidAccessToken(oldTokens.accessToken), "…and keeps its tokens");
    }

    @Test
    void supersedeNeverYanksAnInFlightAuthorization() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client inFlight = s.registerClient(List.of("http://a/cb"), "Claude Code");
        String code = s.newAuthCode(inFlight.clientId, "http://a/cb", "chal", "read", "res");
        clock.addAndGet(10_000); // well inside the 2-minute code TTL
        OAuthStore.Client fresh = s.registerClient(List.of("http://b/cb"), "Claude Code");
        s.issueTokens(fresh.clientId, "read", "res");
        assertNotNull(s.client(inFlight.clientId),
                "a pending (unexpired) auth code marks an in-flight flow — never superseded");
        assertNotNull(s.consumeAuthCode(code), "the in-flight code still completes");
    }

    @Test
    void supersedeIgnoresAnonymousRegistrations() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client nameless = s.registerClient(List.of("http://a/cb"), null);
        OAuthStore.Client blank = s.registerClient(List.of("http://a/cb"), "");
        clock.addAndGet(60_000);
        OAuthStore.Client fresh = s.registerClient(List.of("http://b/cb"), "");
        s.issueTokens(fresh.clientId, "read", "res");
        assertNotNull(s.client(nameless.clientId), "no name, no supersede match");
        assertNotNull(s.client(blank.clientId), "a blank name never matches anything");
    }

    @Test
    void issueAndRefreshCountAsClientActivity() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        clock.addAndGet(5_000);
        OAuthStore.Tokens t = s.issueTokens(c.clientId, "read", "res");
        assertEquals(clock.get(), c.lastSeenAt.get(), "issuing tokens bumps lastSeenAt");
        clock.addAndGet(5_000);
        s.refresh(t.refreshToken);
        assertEquals(clock.get(), c.lastSeenAt.get(), "refreshing bumps lastSeenAt too");
    }

    @Test
    void sweepDropsAnAbandonedRegistrationOnlyAfterItsGrace() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client abandoned = s.registerClient(List.of("http://a/cb"), "app");
        clock.addAndGet(OAuthStore.ABANDONED_CLIENT_GRACE_MS - 1);
        assertEquals(0, s.sweepInactiveClients(),
                "a token-less registration inside the grace may still be mid-flow");
        assertNotNull(s.client(abandoned.clientId));
        clock.addAndGet(2);
        assertEquals(1, s.sweepInactiveClients(),
                "past the grace, a registration with no tokens and no pending code is dead weight");
        assertNull(s.client(abandoned.clientId));
    }

    @Test
    void sweepKeepsTokenHoldersUntilTheInactivityTtl() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        OAuthStore.Tokens t = s.issueTokens(c.clientId, "read", "res");
        clock.addAndGet(OAuthStore.INACTIVE_CLIENT_TTL_MS - 1);
        assertEquals(0, s.sweepInactiveClients(),
                "a client holding tokens is kept for the full inactivity TTL");
        clock.addAndGet(2);
        assertEquals(1, s.sweepInactiveClients(),
                "a client silent past the TTL is cleaned up, tokens and all");
        assertNull(s.refresh(t.refreshToken), "its refresh token goes with it");
    }

    @Test
    void listClientsAndRegisterClientRunTheSweep() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        s.registerClient(List.of("http://a/cb"), "stale");
        clock.addAndGet(OAuthStore.ABANDONED_CLIENT_GRACE_MS + 1);
        assertTrue(s.listClients().isEmpty(), "the view's listing self-cleans");

        s.registerClient(List.of("http://a/cb"), "stale2");
        clock.addAndGet(OAuthStore.ABANDONED_CLIENT_GRACE_MS + 1);
        OAuthStore.Client kept = s.registerClient(List.of("http://b/cb"), "fresh");
        List<OAuthStore.ClientInfo> listed = s.listClients();
        assertEquals(1, listed.size(), "registering sweeps earlier dead weight");
        assertEquals(kept.clientId, listed.get(0).clientId);
    }

    @Test
    void noteClientActivityShieldsAConsentPageDwell() {
        // The consent phase holds no token and no code; the authorize endpoints report activity so
        // a user parked on the consent page does not read as an abandoned registration.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        clock.addAndGet(OAuthStore.ABANDONED_CLIENT_GRACE_MS - 1);
        s.noteClientActivity(c.clientId); // the consent page renders just before the grace is up
        clock.addAndGet(OAuthStore.ABANDONED_CLIENT_GRACE_MS - 1);
        assertEquals(0, s.sweepInactiveClients(),
                "the grace must run from the last authorize touch, not from registration");
        assertNotNull(s.client(c.clientId));
        s.noteClientActivity("mcpc_missing"); // unknown/null ids are a no-op, not an error
        s.noteClientActivity(null);
    }

    @Test
    void expiredCodeDoesNotShieldASupersededRegistration() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        OAuthStore.Client dead = s.registerClient(List.of("http://a/cb"), "Claude Code");
        s.newAuthCode(dead.clientId, "http://a/cb", "chal", "read", "res");
        clock.addAndGet(130_000); // past the 2-minute code TTL — the flow is dead, not in-flight
        OAuthStore.Client fresh = s.registerClient(List.of("http://b/cb"), "Claude Code");
        s.issueTokens(fresh.clientId, "read", "res");
        assertNull(s.client(dead.clientId),
                "an expired code must not shield a dead registration from the supersede");
    }

    @Test
    void sweepAndSupersedeBoundariesAreExclusive() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        // Exactly AT the abandoned grace: kept (strictly-older-than semantics).
        OAuthStore.Client c = s.registerClient(List.of("http://a/cb"), "app");
        clock.addAndGet(OAuthStore.ABANDONED_CLIENT_GRACE_MS);
        assertEquals(0, s.sweepInactiveClients(), "exactly at the grace boundary is not past it");
        assertNotNull(s.client(c.clientId));

        // Exactly AT the inactivity TTL: kept.
        s.issueTokens(c.clientId, "read", "res"); // bumps lastSeenAt to now
        clock.addAndGet(OAuthStore.INACTIVE_CLIENT_TTL_MS);
        assertEquals(0, s.sweepInactiveClients(), "exactly at the TTL boundary is not past it");
        assertNotNull(s.client(c.clientId));
    }

    @Test
    void supersedeRequiresStrictlyOlderRegistrationAndStaleLastSeen() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        OAuthStore s = store(clock, null);
        // Same-instant registrations (same clock value): neither is strictly older — both kept.
        OAuthStore.Client a = s.registerClient(List.of("http://a/cb"), "app");
        OAuthStore.Client b = s.registerClient(List.of("http://b/cb"), "app");
        s.issueTokens(b.clientId, "read", "res");
        assertNotNull(s.client(a.clientId), "equal registeredAt must not supersede");

        // lastSeenAt exactly equal to the successor's registeredAt: seen "since", kept.
        clock.addAndGet(60_000);
        OAuthStore.Client c2 = s.registerClient(List.of("http://c/cb"), "app");
        a.lastSeenAt.set(c2.registeredAt);
        s.issueTokens(c2.clientId, "read", "res");
        assertNotNull(s.client(a.clientId),
                "a client seen at the successor's registration instant is not strictly stale");
    }

    @Test
    void hydrationAnchorsTheSweepAfterARestart() {
        // Persisted lastSeenAt is best-effort and can badly understate real activity; after a
        // reload the cleanup clocks must restart from hydration, not fire on stale evidence.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(
                10L * OAuthStore.INACTIVE_CLIENT_TTL_MS);
        String ancient = "{"
                + "\"clients\":[{\"clientId\":\"mcpc_old\",\"clientName\":\"app\","
                + "\"redirectUris\":[\"http://a/cb\"],\"registeredAt\":1,\"lastSeenAt\":1}],"
                + "\"accessTokens\":[],"
                + "\"refreshTokens\":[{\"token\":\"mcpr_live\",\"clientId\":\"mcpc_old\","
                + "\"grantId\":\"g\",\"scope\":\"s\",\"resource\":\"r\","
                + "\"expiresAt\":" + Long.MAX_VALUE + "}]}";
        OAuthStore s = new OAuthStore(() -> "tok", () -> ancient, x -> {}, true, clock::get);
        assertEquals(0, s.sweepInactiveClients(),
                "epoch-old persisted timestamps must not be reaped right after hydration");
        assertNotNull(s.client("mcpc_old"));
        clock.addAndGet(OAuthStore.INACTIVE_CLIENT_TTL_MS + 1);
        assertEquals(1, s.sweepInactiveClients(),
                "once the post-hydration TTL elapses with no activity, the client is reaped");
    }

    @Test
    void sweepCollectsOrphanedGrants() throws Exception {
        // Grants whose client record is gone can never validate (fail-closed) but would otherwise
        // sit in the persisted blob forever — the sweep drops them.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        SaveCapture save = new SaveCapture();
        OAuthStore s = store(clock, save);
        s.issueTokens("mcpc_never_registered", "read", "res");
        assertTrue(MAPPER.readTree(save.last).path("refreshTokens").size() > 0,
                "the orphan grant is persisted at issue time");
        s.sweepInactiveClients();
        assertEquals(0, MAPPER.readTree(save.last).path("accessTokens").size(),
                "orphaned access grants are collected");
        assertEquals(0, MAPPER.readTree(save.last).path("refreshTokens").size(),
                "orphaned refresh grants are collected");
    }

    @Test
    void sweepPersistsOnlyWhenSomethingWasRemoved() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        SaveCapture save = new SaveCapture();
        OAuthStore s = store(clock, save);
        s.registerClient(List.of("http://a/cb"), "app");
        int afterRegister = save.saved.size();
        assertEquals(0, s.sweepInactiveClients(), "nothing stale yet");
        assertEquals(afterRegister, save.saved.size(), "a no-op sweep must not rewrite preferences");
        clock.addAndGet(OAuthStore.ABANDONED_CLIENT_GRACE_MS + 1);
        assertEquals(1, s.sweepInactiveClients());
        assertTrue(save.saved.size() > afterRegister, "a sweep that removed something persists");
    }

    // ------------------------------------------------------------- purgeExpired (via newAuthCode)

    @Test
    void purgeExpiredKeepsLiveAuthCodes() {
        OAuthStore s = store("tok");
        String live = s.newAuthCode("cid", "http://a/cb", "chal", "read", "res");
        // A second newAuthCode call triggers purgeExpired(); the live code must survive.
        s.newAuthCode("cid2", "http://a/cb", "chal2", "read", "res");
        assertNotNull(s.consumeAuthCode(live), "live (unexpired) code survives purge");
    }

    // ------------------------------------------------------------- randomId format (via public ids)

    @Test
    void generatedIdsAreUrlSafeBase64OfExpectedLength() {
        OAuthStore s = store("tok");
        String code = s.newAuthCode("cid", "http://a/cb", "chal", "read", "res");
        String body = code.substring("mcpa_".length());
        // 24 random bytes -> 32 chars of unpadded url-safe Base64.
        assertEquals(32, body.length(), "random id body is 32 chars");
        assertTrue(body.matches("[A-Za-z0-9_-]+"), "random id body is url-safe Base64 (no padding)");
    }

    @Test
    void generatedIdsAreUniqueAcrossManyCalls() {
        OAuthStore s = store("tok");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            seen.add(s.newAuthCode("cid", "http://a/cb", "chal", "read", "res"));
        }
        assertEquals(50, seen.size(), "all generated ids are unique");
    }

    // ------------------------------------------------------------- value holders

    @Test
    void clientConstructorStoresFieldsAndAllowsRedirect() {
        OAuthStore.Client c = new OAuthStore.Client("id1", "name1",
                new java.util.LinkedHashSet<>(List.of("http://a/cb")), 123L);
        assertEquals("id1", c.clientId, "clientId stored");
        assertEquals("name1", c.clientName, "clientName stored");
        assertEquals(123L, c.registeredAt, "registeredAt stored");
        assertTrue(c.allowsRedirect("http://a/cb"), "known redirect allowed");
        assertFalse(c.allowsRedirect("http://other/cb"), "unknown redirect rejected");
        assertFalse(c.allowsRedirect(null), "null redirect rejected");
        assertEquals(0L, c.lastSeenAt.get(), "lastSeenAt defaults to 0");
    }

    @Test
    void clientAllowsRedirectFalseWhenNoRedirectUris() {
        OAuthStore.Client c = new OAuthStore.Client("id", "n",
                new java.util.LinkedHashSet<>(), 1L);
        assertFalse(c.allowsRedirect("http://a/cb"), "empty redirect set allows nothing");
    }

    @Test
    void authCodeConstructorStoresFields() {
        OAuthStore.AuthCode a = new OAuthStore.AuthCode("cid", "http://a/cb", "chal",
                "read", "res", 999L);
        assertEquals("cid", a.clientId, "clientId stored");
        assertEquals("http://a/cb", a.redirectUri, "redirectUri stored");
        assertEquals("chal", a.codeChallenge, "codeChallenge stored");
        assertEquals("read", a.scope, "scope stored");
        assertEquals("res", a.resource, "resource stored");
    }

    @Test
    void tokensConstructorStoresFields() {
        OAuthStore.Tokens t = new OAuthStore.Tokens("at", "rt", "read", 42L);
        assertEquals("at", t.accessToken, "accessToken stored");
        assertEquals("rt", t.refreshToken, "refreshToken stored");
        assertEquals("read", t.scope, "scope stored");
        assertEquals(42L, t.expiresInSeconds, "expiresInSeconds stored");
    }

    @Test
    void clientInfoConstructorStoresFields() {
        OAuthStore.ClientInfo info = new OAuthStore.ClientInfo("id", "name",
                100L, 200L, 3, 400L);
        assertEquals("id", info.clientId, "clientId stored");
        assertEquals("name", info.clientName, "clientName stored");
        assertEquals(100L, info.registeredAt, "registeredAt stored");
        assertEquals(200L, info.lastSeenAt, "lastSeenAt stored");
        assertEquals(3, info.activeAccessTokens, "activeAccessTokens stored");
        assertEquals(400L, info.latestAccessExpiry, "latestAccessExpiry stored");
    }

    // ------------------------------------------------------------- hooks invocation

    @Test
    void loadHookInvokedExactlyOnceOnConstruction() {
        AtomicInteger loadCalls = new AtomicInteger();
        new OAuthStore(() -> "tok", () -> {
            loadCalls.incrementAndGet();
            return null;
        }, x -> {});
        assertEquals(1, loadCalls.get(), "load hook consulted once at construction");
    }

    @Test
    void saveHookReceivesJsonPayload() {
        AtomicReference<String> captured = new AtomicReference<>();
        OAuthStore s = new OAuthStore(() -> "tok", () -> null, captured::set);
        s.registerClient(List.of("http://a/cb"), "app");
        assertNotNull(captured.get(), "save hook received a payload");
        assertTrue(captured.get().trim().startsWith("{"), "payload is a JSON object");
    }

    // ------------------------------------------------------------- helpers

    private static void sleepAtLeastAMilli() {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() == start) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
