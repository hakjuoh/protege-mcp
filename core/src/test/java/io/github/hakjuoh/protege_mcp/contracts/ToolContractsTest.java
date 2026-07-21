package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

class ToolContractsTest {

    @Test
    void errorPreservesLegacyFieldAndStableTypedFields() {
        ToolError error = ToolError.of("invalid_request", "Bad input.",
                Map.of("field", "query"), false);
        assertEquals("Bad input.", error.error());
        assertEquals("Bad input.", error.message());
        assertEquals(Map.of("field", "query"), error.details());
        assertEquals(error.toJson().get("error"), error.toJson().get("message"));
        assertEquals(false, error.toJson().get("retryable"));
    }

    @Test
    void errorsRejectUnstableCodesOversizedMessagesAndDetails() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolError.of("Invalid-Request", "bad", false));
        assertEquals(ToolError.MAX_MESSAGE_LENGTH,
                ToolError.of("invalid_request", "x".repeat(2_049), false).message().length());
        Map<String, Object> details = new LinkedHashMap<>();
        for (int index = 0; index < 33; index++) details.put("k" + index, index);
        ToolError bounded = ToolError.of("invalid_request", "bad", details, false);
        assertEquals(ToolError.MAX_DETAILS, bounded.details().size());
        assertEquals(true, bounded.details().get("_truncated"));
    }

    @Test
    void contractsAreImmutableAndErrorSchemaKeepsTheLegacyAlias() {
        assertThrows(UnsupportedOperationException.class,
                () -> ToolContractSchemas.errorSchema().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ToolContractSchemas.legacySuccessSchema().put("x", "y"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
                ToolContractSchemas.errorSchema().get("properties");
        assertTrue(properties.containsKey("error"));
        assertTrue(properties.containsKey("code"));
        assertFalse((Boolean) ToolContractSchemas.errorSchema().get("additionalProperties"));
        assertEquals(ToolContractSchemas.errorSchema(), ToolContractSchemas.errorSchemaMeta()
                .get(ToolContractSchemas.ERROR_SCHEMA_META_KEY));
        Map<String, Object> wire = ToolContractSchemas.wireOutputSchema(
                Map.of("type", "object", "properties",
                        Map.of("ok", Map.of("type", "boolean")),
                        "required", List.of("ok"), "additionalProperties", false));
        ToolSchemaValidator.Compiled contract = ToolSchemaValidator.compile(wire);
        assertTrue(contract.violations(Map.of("ok", true)).isEmpty());
        assertTrue(contract.violations(ToolError.of(
                "invalid_request", "bad", false).toJson()).isEmpty());
    }

    @Test
    void publicErrorsAreDeeplyImmutableBoundedAndRedacted() {
        Map<String, Object> mutable = new LinkedHashMap<>();
        mutable.put("password", "hunter2");
        mutable.put("nested", new java.util.ArrayList<>(List.of(
                Map.of("authorization", "Bearer canary-token"))));
        ToolError error = ToolError.of("provider_failed",
                "Bearer canary-token failed at /private/project.owl?api_key=shh", mutable, false);

        assertFalse(error.message().contains("canary-token"));
        assertFalse(error.message().contains("/private"));
        assertFalse(error.message().contains("shh"));
        assertEquals(ContractRedactor.REDACTED, error.details().get("password"));
        assertThrows(UnsupportedOperationException.class,
                () -> error.details().put("x", "y"));
        @SuppressWarnings("unchecked")
        List<Object> nested = (List<Object>) error.details().get("nested");
        assertThrows(UnsupportedOperationException.class, () -> nested.add("x"));

        mutable.put("late", "mutation");
        assertFalse(error.details().containsKey("late"));
        assertFalse(ToolError.of("provider_failed", "failed at file:/Users/private/project.owl",
                false).message().contains("/Users"));
    }

    @Test
    void requestScopedCanariesAreRemovedFromArbitraryText() {
        assertEquals("prefix [REDACTED] suffix",
                ContractRedactor.sanitize("prefix opaque-canary suffix",
                        List.of("opaque-canary")));
        ToolError boundary = ToolError.of("provider_failed",
                "provider echoed opaque-canary", Map.of("response", "opaque-canary"), false,
                List.of("opaque-canary"));
        assertFalse(boundary.toJson().toString().contains("opaque-canary"));
    }

    @Test
    void directErrorConstructionCannotBypassRedaction() {
        ToolError error = new ToolError("Bearer raw-token", "provider_failed",
                "Bearer raw-token", Map.of(), false);
        assertFalse(error.message().contains("raw-token"));
        assertEquals(error.message(), error.error());
    }

    @Test
    void wireSchemaAndNestedListsAreDeeplyImmutable() {
        Map<String, Object> mutableBranch = new LinkedHashMap<>();
        mutableBranch.put("type", "string");
        Map<String, Object> mutableSuccess = new LinkedHashMap<>();
        mutableSuccess.put("type", "object");
        mutableSuccess.put("properties", Map.of("value",
                Map.of("anyOf", new ArrayList<>(List.of(mutableBranch)))));
        mutableSuccess.put("additionalProperties", false);

        Map<String, Object> wire = ToolContractSchemas.wireOutputSchema(mutableSuccess);
        mutableBranch.put("type", "integer");
        @SuppressWarnings("unchecked")
        List<Object> choices = (List<Object>) wire.get("anyOf");
        assertThrows(UnsupportedOperationException.class, () -> choices.add(Map.of()));
        @SuppressWarnings("unchecked")
        Map<String, Object> success = (Map<String, Object>) choices.get(0);
        assertEquals("string", ((Map<?, ?>) ((List<?>) ((Map<?, ?>)
                ((Map<?, ?>) success.get("properties")).get("value")).get("anyOf")).get(0))
                .get("type"));
    }

    @Test
    void redactorCoversHeaderKeyQuotedUserInfoCycleAndGlobalBudgetAttacks() {
        Map<String, Object> secrets = new LinkedHashMap<>();
        secrets.put("API_KEY", "top-secret");
        secrets.put("X-Api-Key", "other-secret");
        secrets.put("/api_key", "path-key-secret");
        secrets.put("unterminated", "token=\"unterminated secret words");
        secrets.put("message", "token=\"opaque value\" client_secret: 'client value' "
                + "token=\"abc\\\"remaining secret\" "
                + "https://user:p@ss@example.org/path");
        String redacted = ContractRedactor.redact(secrets).toString();
        for (String leaked : List.of("top-secret", "other-secret", "opaque value",
                "path-key-secret", "unterminated secret words", "client value",
                "remaining secret", "user:p@ss")) {
            assertFalse(redacted.contains(leaked), redacted);
        }

        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertTrue(ContractRedactor.redact(cycle).toString().contains("REPEATED_REFERENCE"));

        List<Object> huge = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            huge.add(List.of("x".repeat(1_000), "y".repeat(1_000)));
        }
        String bounded = ContractJson.mapper().valueToTree(ContractRedactor.redact(huge)).toString();
        assertTrue(bounded.length() < 40_000, "global output budget must bound expansion");

        java.util.concurrent.atomic.AtomicInteger mutable =
                new java.util.concurrent.atomic.AtomicInteger(7);
        Object normalized = ContractRedactor.redact(Map.of("value", mutable));
        mutable.set(9);
        assertEquals("{value=7}", normalized.toString());

        Object hugeNumber = ContractRedactor.redact(Map.of("value",
                new java.math.BigInteger("9".repeat(100_000))));
        assertTrue(hugeNumber.toString().contains("NUMBER_LIMIT"));
        assertTrue(hugeNumber.toString().length() < 1_000);
        for (Number nonFinite : List.of(Double.NaN, Double.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ImmutableJson.resultMap(Map.of("value", nonFinite)));
            assertThrows(IllegalArgumentException.class,
                    () -> ImmutableJson.map(Map.of("const", nonFinite)));
        }
    }
}
