package io.github.hakjuoh.protege_mcp.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;

class ReasonerCapabilityRegistryTest {

    private static final String DIGEST = "sha256:" + "0".repeat(64);
    private static final String HERMIT_DIGEST =
            "sha256:5028991751713b34e006eda5871266226c12b148a44b4847e9974a0ccc4645dd";
    private static final String SIMPLE_DIGEST =
            "sha256:2a61e634371cd8093616f0cf66ede887cf19fe277fab1a0e8a77cd34ebd26d4b";
    private static final String ELK_DIGEST =
            "sha256:1a61037dd02f1f450eef0292e83e3b8411ea0725123112c4db5e5de888155298";
    private static final String HERMIT_BINARY =
            "sha256:26e6119163fd1249797553488fd9a531578380fff442c36c57d930066e186da9";
    private static final String STRUCTURAL_BINARY =
            "sha256:050a3fd71f9263bd41d723bcd4b9774f26ae200a43d9d547f7c7dd2780b03587";
    private static final String ELK_BINARY =
            "sha256:9d010d6d8774da376fb44c279eea4829c25128fd9f4576e7a4d2cc0dfeac56eb";
    private static final String HERMIT_CONFIGURATION_BINARY =
            "sha256:b9fe7e5c8517cc59906e12ddcecd4f03311ef082d49e6cfe6784b4f8f923a7b6";
    private static final String SIMPLE_CONFIGURATION_BINARY =
            "sha256:cff921faad8172a52347ba3f44ef720b53a4a301a729a256e8c8f7474bba7080";
    private static final String ELK_CONFIGURATION_BINARY =
            "sha256:129cd45bfcf8b28c2a20b7601af6ed39196f94abffbbf77b3803002f99021431";
    private static final String HERMIT_CODE_DIGEST =
            "sha256:41d6c9cdd95485aeee392c6a247e6da22428ed92cad7a5ebbe60953a043f4239";
    private static final String STRUCTURAL_CODE_DIGEST =
            "sha256:6b9d0eb05e45400684f6f2802d6873b5557451676d52d6a809c21e85a51bbcf4";
    private static final String ELK_CODE_DIGEST =
            "sha256:e984e8e64e2c42f3b30341f0b2ade123fc268860e85062c01718a742ee8e53ec";
    private static final String HERMIT_SEMANTIC =
            "sha256:264a41ff2fbe8878acc9007aa184a549b08f458a42fc1ed9fe0bcb5c0303ab86";
    private static final String HERMIT_PROTEGE_SEMANTIC =
            "sha256:fb224b07454b5f598f53021ee67636ed812704b0a202323d797863d9339b8adb";
    private static final String SIMPLE_SEMANTIC =
            "sha256:16f84a5f6a35f57cdceae4c163311dfb5809f24196c92b073970dd59bd5d01b0";
    private static final String ELK_SEMANTIC =
            "sha256:ac05fb26ad6b7004873e0746dd30f741aa84888520c14784e349bfbdcc35c28b";
    private static final List<String> HERMIT_CODE_SCOPES = List.of(
            "org/semanticweb/HermiT/**", "rationals/**", "dk/brics/automaton/**",
            "org/apache/axiom/**", "org/semanticweb/owlapi/**");
    private static final List<String> STRUCTURAL_CODE_SCOPES = List.of(
            "org/semanticweb/owlapi/**");
    private static final List<String> ELK_CODE_SCOPES = List.of(
            "org/semanticweb/elk/**", "org/semanticweb/owlapi/**");
    private final ReasonerCapabilityRegistry registry = new ReasonerCapabilityRegistry();

    @Test
    void exactReviewedProfilesNeverBorrowAnotherVersionOrConfiguration() {
        ReasonerCapabilityReport hermit = registry.report(identity(
                "org.semanticweb.HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration"));
        assertEquals("reviewed", hermit.profileStatus());
        assertEquals(CapabilityStatus.SUPPORTED, hermit.ruleStatus("swrl_rules"));
        assertEquals(CapabilityStatus.UNSUPPORTED, hermit.ruleStatus("swrl_builtins"));

        ReasonerCapabilityReport wrongVersion = registry.report(identity(
                "org.semanticweb.HermiT", "1.3.8.432",
                "org.semanticweb.HermiT.Configuration"));
        assertEquals("unknown", wrongVersion.profileStatus());
        assertEquals(CapabilityStatus.UNKNOWN, wrongVersion.ruleStatus("swrl_rules"));
        ReasonerCapabilityReport suffixedVersion = registry.report(identity(
                "org.semanticweb.HermiT", "1.3.8.431.hotfix",
                "org.semanticweb.HermiT.Configuration"));
        assertEquals("unknown", suffixedVersion.profileStatus());

        ReasonerCapabilityReport wrongConfiguration = registry.report(identity(
                "org.semanticweb.HermiT", "1.3.8.431", "example.CustomConfiguration"));
        assertEquals("unknown", wrongConfiguration.profileStatus());
        assertFalse((Boolean) wrongConfiguration.toMap().get("exact_profile_match"));

        ReasonerIdentity wrongFactory = new ReasonerIdentity("org.semanticweb.HermiT",
                "example.ImpersonatingFactory", HERMIT_BINARY, HERMIT_CODE_DIGEST,
                HERMIT_CODE_SCOPES, 2_635,
                "HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration", HERMIT_CONFIGURATION_BINARY,
                "factory_default", DIGEST,
                HERMIT_SEMANTIC,
                0L, "none", "ALLOW", "BY_NAME",
                "BUFFERING", "test");
        assertEquals("unknown", registry.report(wrongFactory).profileStatus());

        ReasonerIdentity wrongDigest = new ReasonerIdentity("org.semanticweb.HermiT",
                "org.semanticweb.HermiT.ReasonerFactory", HERMIT_BINARY,
                HERMIT_CODE_DIGEST, HERMIT_CODE_SCOPES, 2_635, "HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration", HERMIT_CONFIGURATION_BINARY,
                "factory_default", DIGEST,
                DIGEST,
                0L, "none", "ALLOW", "BY_NAME",
                "BUFFERING", "test");
        assertEquals("unknown", registry.report(wrongDigest).profileStatus());

        ReasonerIdentity wrongBinary = new ReasonerIdentity("org.semanticweb.HermiT",
                "org.semanticweb.HermiT.ReasonerFactory", DIGEST,
                HERMIT_CODE_DIGEST, HERMIT_CODE_SCOPES, 2_635, "HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration", HERMIT_CONFIGURATION_BINARY,
                "factory_default", HERMIT_DIGEST, HERMIT_SEMANTIC,
                0L, "none", "ALLOW", "BY_NAME",
                "BUFFERING", "test");
        assertEquals("unknown", registry.report(wrongBinary).profileStatus());

        ReasonerIdentity wrongCodeDigest = new ReasonerIdentity("org.semanticweb.HermiT",
                "org.semanticweb.HermiT.ReasonerFactory", HERMIT_BINARY,
                DIGEST, HERMIT_CODE_SCOPES, 2_635, "HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration", HERMIT_CONFIGURATION_BINARY,
                "factory_default", HERMIT_DIGEST, HERMIT_SEMANTIC, 0L, "none", "ALLOW", "BY_NAME",
                "BUFFERING", "test");
        assertEquals("unknown", registry.report(wrongCodeDigest).profileStatus());

        ReasonerIdentity wrongConfigurationBinary = new ReasonerIdentity(
                "org.semanticweb.HermiT", "org.semanticweb.HermiT.ReasonerFactory",
                HERMIT_BINARY, HERMIT_CODE_DIGEST, HERMIT_CODE_SCOPES, 2_635,
                "HermiT", "1.3.8.431", "org.semanticweb.HermiT.Configuration", DIGEST,
                "factory_default", HERMIT_DIGEST, HERMIT_SEMANTIC, 0L, "none",
                "ALLOW", "BY_NAME", "BUFFERING", "test");
        assertEquals("unknown", registry.report(wrongConfigurationBinary).profileStatus());
    }

    @Test
    void structuralAndElkProfilesRemainConservative() {
        ReasonerCapabilityReport structural = registry.report(identity(
                "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory",
                "4.5.29", "org.semanticweb.owlapi.reasoner.SimpleConfiguration"));
        assertEquals("reviewed", structural.profileStatus());
        assertEquals(CapabilityStatus.UNSUPPORTED, structural.ruleStatus("swrl_rules"));
        assertEquals(CapabilityStatus.UNSUPPORTED,
                structural.owlStatus("consistency"));
        assertEquals(CapabilityStatus.UNSUPPORTED,
                structural.owlStatus("satisfiability"));

        ReasonerCapabilityReport elk = registry.report(identity(
                "org.semanticweb.elk.owlapi.ElkReasonerFactory", "0.5.0",
                "org.semanticweb.owlapi.reasoner.SimpleConfiguration"));
        assertEquals("reviewed", elk.profileStatus());
        assertEquals(CapabilityStatus.UNSUPPORTED, elk.atomStatus("class"));
        assertTrue(elk.toMap().toString().contains("OWL 2 EL"));
    }

    @Test
    void individuallyReviewedComponentsDoNotFormAnUntestedCartesianProduct() {
        ReasonerIdentity crossed = new ReasonerIdentity("HermiT.reasoner.factory",
                "org.semanticweb.HermiT.ReasonerFactory", HERMIT_BINARY,
                HERMIT_CODE_DIGEST, HERMIT_CODE_SCOPES, 2_635, "HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration", HERMIT_CONFIGURATION_BINARY,
                "factory_default", HERMIT_DIGEST,
                HERMIT_SEMANTIC, -1L, "none", "ALLOW", "BY_NAME", "BUFFERING", "test");
        assertEquals("unknown", registry.report(crossed).profileStatus());

        ReasonerIdentity otherCross = new ReasonerIdentity("org.semanticweb.HermiT",
                "org.semanticweb.HermiT.ReasonerFactory", HERMIT_BINARY,
                HERMIT_CODE_DIGEST, HERMIT_CODE_SCOPES, 2_635, "HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration", HERMIT_CONFIGURATION_BINARY,
                "custom", HERMIT_DIGEST,
                HERMIT_PROTEGE_SEMANTIC, -1L, "none", "ALLOW", "BY_NAME", "BUFFERING", "test");
        assertEquals("unknown", registry.report(otherCross).profileStatus());
    }

    @Test
    void customBuiltinsAreUnsupportedEvenForUnknownProfiles() {
        ReasonerCapabilityReport unknown = registry.report(identity(
                "example.UnknownFactory", "9.9.9", "example.Configuration"));
        assertEquals(CapabilityStatus.UNKNOWN,
                unknown.builtinStatus("http://www.w3.org/2003/11/swrlb#add"));
        assertEquals(CapabilityStatus.UNSUPPORTED,
                unknown.builtinStatus("https://example.org/builtin#readFile"));
        assertEquals(CapabilityStatus.UNSUPPORTED,
                unknown.builtinStatus("http://www.w3.org/2003/11/swrlb#readFile"));
    }

    @Test
    void capabilityDigestBindsEveryAdvertisedStatusAndEvidenceRow() throws Exception {
        ReasonerCapabilityReport report = registry.report(identity(
                "org.semanticweb.HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration"));
        Map<String, Object> payload = report.toMap();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        add(digest, String.valueOf(payload.get("vocabulary_version")));
        add(digest, String.valueOf(payload.get("profile_status")));
        add(digest, String.valueOf(((Map<?, ?>) payload.get("identity")).get("profile_key")));
        for (String category : List.of("owl_capabilities", "rule_capabilities",
                "swrl_atom_capabilities")) {
            for (Object raw : (List<?>) payload.get(category)) {
                Map<?, ?> row = (Map<?, ?>) raw;
                add(digest, String.valueOf(row.get("id")));
                add(digest, String.valueOf(row.get("status")));
                add(digest, String.valueOf(row.get("evidence")));
            }
        }
        for (Object raw : (List<?>) payload.get("swrl_builtin_capabilities")) {
            Map<?, ?> row = (Map<?, ?>) raw;
            add(digest, String.valueOf(row.get("iri")));
            add(digest, String.valueOf(row.get("status")));
            add(digest, String.valueOf(row.get("evidence")));
        }
        for (Object value : (List<?>) payload.get("known_incompatibilities")) {
            add(digest, String.valueOf(value));
        }
        StringBuilder expected = new StringBuilder("sha256:");
        for (byte value : digest.digest()) expected.append(String.format("%02x", value & 0xff));
        assertEquals(expected.toString(), report.capabilityDigest());
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    @Test
    void runtimeConfigurationDigestIsDeterministicAndSensitive() {
        StructuralReasonerFactory factory = new StructuralReasonerFactory();
        ReasonerIdentity first = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory, new SimpleConfiguration(1000),
                BufferingMode.BUFFERING, "test");
        ReasonerIdentity same = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory, new SimpleConfiguration(1000),
                BufferingMode.BUFFERING, "test");
        ReasonerIdentity changed = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory, new SimpleConfiguration(2000),
                BufferingMode.BUFFERING, "test");
        assertEquals(first.configurationDigest(), same.configurationDigest());
        assertNotEquals(first.configurationDigest(), changed.configurationDigest());
        assertEquals("4.5.29", first.implementationVersion());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) first.toMap();
        assertEquals(first.profileKey(), map.get("profile_key"));
    }

    @Test
    void capabilityOutputUsesTheClosedTypedVocabulary() {
        ReasonerCapabilityReport report = registry.report(identity(
                "org.semanticweb.HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration"));
        List<String> violations = ToolSchemaValidator.compile(
                ReasonerToolSchemas.output("get_reasoner_capabilities"))
                .violations(report.toMap());
        assertTrue(violations.isEmpty(), violations.toString());
        assertEquals(ReasonerCapabilityReport.PURE_BUILTIN_IRIS.size(),
                ((java.util.List<?>) report.toMap().get("swrl_builtin_capabilities")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void capabilitySchemasRejectDuplicateIdsEvenWhenRowsDiffer() {
        ReasonerCapabilityReport report = registry.report(identity(
                "org.semanticweb.HermiT", "1.3.8.431",
                "org.semanticweb.HermiT.Configuration"));
        Map<String, Object> invalid = new LinkedHashMap<>(report.toMap());
        List<Map<String, Object>> rows = new java.util.ArrayList<>(
                (List<Map<String, Object>>) invalid.get("owl_capabilities"));
        Map<String, Object> duplicate = new LinkedHashMap<>(rows.get(0));
        duplicate.put("evidence", "A different row with the same identifier.");
        rows.set(rows.size() - 1, duplicate);
        invalid.put("owl_capabilities", rows);
        List<String> violations = ToolSchemaValidator.compile(
                ReasonerToolSchemas.output("get_reasoner_capabilities"))
                .violations(invalid);
        assertTrue(violations.stream().anyMatch(value -> value.contains("minContains")),
                violations::toString);

        Map<String, Object> invalidBuiltins = new LinkedHashMap<>(report.toMap());
        List<Map<String, Object>> builtins = new java.util.ArrayList<>(
                (List<Map<String, Object>>) invalidBuiltins.get("swrl_builtin_capabilities"));
        Map<String, Object> duplicateBuiltin = new LinkedHashMap<>(builtins.get(0));
        duplicateBuiltin.put("evidence", "A different row with the same built-in IRI.");
        builtins.set(builtins.size() - 1, duplicateBuiltin);
        invalidBuiltins.put("swrl_builtin_capabilities", builtins);
        List<String> builtinViolations = ToolSchemaValidator.compile(
                ReasonerToolSchemas.output("get_reasoner_capabilities"))
                .violations(invalidBuiltins);
        assertTrue(builtinViolations.stream()
                .anyMatch(value -> value.contains("minContains")), builtinViolations::toString);
    }

    @Test
    void longConfigurationValuesAreHashedWithoutPrefixCollisions() {
        LongStringConfiguration left = new LongStringConfiguration("a".repeat(4096) + "x");
        LongStringConfiguration right = new LongStringConfiguration("a".repeat(4096) + "y");
        assertNotEquals(ReasonerIdentity.configurationDigest(left),
                ReasonerIdentity.configurationDigest(right));
    }

    @Test
    void orderedConfigurationCollectionsRemainOrderSensitive() {
        OrderedConfiguration left = new OrderedConfiguration(List.of("a", "b"));
        OrderedConfiguration right = new OrderedConfiguration(List.of("b", "a"));
        assertNotEquals(ReasonerIdentity.configurationDigest(left),
                ReasonerIdentity.configurationDigest(right));
    }

    @Test
    void oversizedConfigurationStringsFailClosedWithinTheByteBudget() {
        HugeStringConfiguration configuration = new HugeStringConfiguration("x".repeat(2_000_000));
        StructuralReasonerFactory factory = new StructuralReasonerFactory();
        ReasonerIdentity identity = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory, configuration,
                BufferingMode.BUFFERING, "test");
        assertEquals("unrecognized", identity.configurationProfile());
        assertTrue(identity.configurationDigest().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void nestedConfigurationCaptureStopsAtTheGlobalNodeBudget() {
        WithinBudgetConfiguration within = new WithinBudgetConfiguration();
        within.mode = "changed";
        OversizedConfiguration configuration = new OversizedConfiguration();
        String digest = ReasonerIdentity.configurationDigest(configuration);

        assertTrue(digest.matches("sha256:[0-9a-f]{64}"));
        assertEquals(digest, ReasonerIdentity.configurationDigest(configuration));
        StructuralReasonerFactory factory = new StructuralReasonerFactory();
        ReasonerIdentity identity = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory, configuration,
                BufferingMode.BUFFERING, "test");
        assertEquals("unrecognized", identity.configurationProfile());
        ReasonerIdentity retained = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory, within,
                BufferingMode.BUFFERING, "test");
        assertEquals("unrecognized", retained.configurationProfile());
    }

    @Test
    void configurationNodeBudgetIsPinnedAtExactly1024Claims() {
        assertTrue(ReasonerIdentity.configurationCaptureComplete(
                new ExactNodeConfiguration(1_017)));
        assertFalse(ReasonerIdentity.configurationCaptureComplete(
                new ExactNodeConfiguration(1_018)));
    }

    @Test
    void hostileScalarAndMapImplementationsFailClosedWithoutCallingUserCode() {
        assertTrue(ReasonerIdentity.configurationDigest(
                new HostileConfiguration(new HostileNumber()))
                .matches("sha256:[0-9a-f]{64}"));
        assertTrue(ReasonerIdentity.configurationDigest(
                new HostileConfiguration(new HostileMap()))
                .matches("sha256:[0-9a-f]{64}"));
        assertTrue(ReasonerIdentity.configurationDigest(new HostileConfiguration(
                java.util.Collections.unmodifiableMap(new HostileMap())))
                .matches("sha256:[0-9a-f]{64}"));
        assertTrue(ReasonerIdentity.configurationDigest(
                new HostileConfiguration(new HostileBigInteger()))
                .matches("sha256:[0-9a-f]{64}"));
        Map<Object, Object> enumKey = new java.util.HashMap<>();
        enumKey.put(HostileEnum.VALUE, "safe");
        assertTrue(ReasonerIdentity.configurationDigest(new HostileConfiguration(enumKey))
                .matches("sha256:[0-9a-f]{64}"));

        StructuralReasonerFactory factory = new StructuralReasonerFactory();
        ReasonerIdentity unrecognized = ReasonerIdentity.capture(factory.getClass().getName(),
                factory.getReasonerName(), factory, new ThrowingConfiguration(),
                BufferingMode.BUFFERING, "test");
        assertEquals("unrecognized", unrecognized.configurationProfile());
        assertEquals(-2L, unrecognized.timeoutMillis());
        assertEquals("unknown", registry.report(unrecognized).profileStatus());
    }

    private static ReasonerIdentity identity(String id, String version, String configuration) {
        String factoryClass = switch (id) {
            case "org.semanticweb.HermiT" -> "org.semanticweb.HermiT.ReasonerFactory";
            default -> id;
        };
        String digest = switch (configuration) {
            case "org.semanticweb.HermiT.Configuration" -> HERMIT_DIGEST;
            case "org.semanticweb.elk.owlapi.ElkReasonerConfiguration" -> ELK_DIGEST;
            case "org.semanticweb.owlapi.reasoner.SimpleConfiguration" -> SIMPLE_DIGEST;
            default -> DIGEST;
        };
        String binary = switch (factoryClass) {
            case "org.semanticweb.HermiT.ReasonerFactory" -> HERMIT_BINARY;
            case "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory" ->
                    STRUCTURAL_BINARY;
            case "org.semanticweb.elk.owlapi.ElkReasonerFactory" -> ELK_BINARY;
            default -> "unknown";
        };
        String codeDigest = switch (factoryClass) {
            case "org.semanticweb.HermiT.ReasonerFactory" -> HERMIT_CODE_DIGEST;
            case "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory" ->
                    STRUCTURAL_CODE_DIGEST;
            case "org.semanticweb.elk.owlapi.ElkReasonerFactory" -> ELK_CODE_DIGEST;
            default -> "unknown";
        };
        List<String> codeScopes = switch (factoryClass) {
            case "org.semanticweb.HermiT.ReasonerFactory" -> HERMIT_CODE_SCOPES;
            case "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory" ->
                    STRUCTURAL_CODE_SCOPES;
            case "org.semanticweb.elk.owlapi.ElkReasonerFactory" -> ELK_CODE_SCOPES;
            default -> List.of();
        };
        int codeClassCount = switch (factoryClass) {
            case "org.semanticweb.HermiT.ReasonerFactory" -> 2_635;
            case "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory" -> 1_533;
            case "org.semanticweb.elk.owlapi.ElkReasonerFactory" -> 4_848;
            default -> 0;
        };
        String semantic = switch (configuration) {
            case "org.semanticweb.HermiT.Configuration" -> HERMIT_SEMANTIC;
            case "org.semanticweb.elk.owlapi.ElkReasonerConfiguration" -> ELK_SEMANTIC;
            case "org.semanticweb.owlapi.reasoner.SimpleConfiguration" -> SIMPLE_SEMANTIC;
            default -> DIGEST;
        };
        String profile = "org.semanticweb.owlapi.reasoner.SimpleConfiguration"
                .equals(configuration) ? "owlapi_standard" : "factory_default";
        String configurationBinary = switch (configuration) {
            case "org.semanticweb.HermiT.Configuration" -> HERMIT_CONFIGURATION_BINARY;
            case "org.semanticweb.elk.owlapi.ElkReasonerConfiguration" ->
                    ELK_CONFIGURATION_BINARY;
            case "org.semanticweb.owlapi.reasoner.SimpleConfiguration" ->
                    SIMPLE_CONFIGURATION_BINARY;
            default -> "unknown";
        };
        return new ReasonerIdentity(id, factoryClass, binary, codeDigest, codeScopes,
                codeClassCount,
                id, version, configuration,
                configurationBinary, profile, digest, semantic,
                0L, "none", "ALLOW", "BY_NAME",
                "BUFFERING", "test");
    }

    public static final class LongStringConfiguration extends SimpleConfiguration {
        public final String option;

        LongStringConfiguration(String option) {
            this.option = option;
        }
    }

    public static final class OrderedConfiguration extends SimpleConfiguration {
        public final List<String> options;

        OrderedConfiguration(List<String> options) {
            this.options = List.copyOf(options);
        }
    }

    public static final class HugeStringConfiguration extends SimpleConfiguration {
        public String option;

        public HugeStringConfiguration() {
            this("default");
        }

        HugeStringConfiguration(String option) {
            this.option = option;
        }
    }

    public static final class OversizedConfiguration extends SimpleConfiguration {
        public final java.util.List<java.util.List<Integer>> options = IntStream.range(0, 5)
                .mapToObj(outer -> IntStream.range(0, 256).boxed().toList())
                .toList();
    }

    public static final class WithinBudgetConfiguration extends SimpleConfiguration {
        public String mode = "default";
        public final java.util.List<java.util.List<Integer>> options = IntStream.range(0, 3)
                .mapToObj(outer -> IntStream.range(0, 200).boxed().toList())
                .toList();
    }

    public static final class ExactNodeConfiguration extends SimpleConfiguration {
        public final List<List<Integer>> options;

        ExactNodeConfiguration(int values) {
            List<List<Integer>> outer = new java.util.ArrayList<>();
            int remaining = values;
            while (remaining > 0) {
                int size = Math.min(256, remaining);
                outer.add(java.util.stream.IntStream.range(0, size).boxed().toList());
                remaining -= size;
            }
            options = List.copyOf(outer);
        }
    }

    public static final class HostileConfiguration extends SimpleConfiguration {
        public final Object option;

        HostileConfiguration(Object option) {
            this.option = option;
        }
    }

    public static final class HostileNumber extends Number {
        private static final long serialVersionUID = 1L;

        @Override public int intValue() { throw new AssertionError("must not be called"); }
        @Override public long longValue() { throw new AssertionError("must not be called"); }
        @Override public float floatValue() { throw new AssertionError("must not be called"); }
        @Override public double doubleValue() { throw new AssertionError("must not be called"); }
        @Override public String toString() { throw new AssertionError("must not be called"); }
    }

    public static final class HostileMap extends java.util.AbstractMap<Object, Object> {
        @Override
        public Set<Entry<Object, Object>> entrySet() {
            throw new AssertionError("must not be called");
        }
    }

    public static final class ThrowingConfiguration extends SimpleConfiguration {
        @Override
        public long getTimeOut() {
            throw new AssertionError("must not be called");
        }
    }

    public static final class HostileBigInteger extends java.math.BigInteger {
        private static final long serialVersionUID = 1L;

        HostileBigInteger() {
            super("1");
        }

        @Override public int bitLength() { throw new AssertionError("must not be called"); }
        @Override public String toString() { throw new AssertionError("must not be called"); }
    }

    private enum HostileEnum {
        VALUE;

        @Override
        public String toString() {
            throw new AssertionError("must not be called");
        }
    }
}
