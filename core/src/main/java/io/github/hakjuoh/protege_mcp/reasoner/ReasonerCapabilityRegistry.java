package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact-profile registry for the three reasoner builds reviewed for version 0.8.0. */
public final class ReasonerCapabilityRegistry {

    private static final String HERMIT_VERSION = "1.3.8.431";
    private static final String STRUCTURAL_VERSION = "4.5.29";
    private static final String ELK_VERSION = "0.5.0";

    private static final String HERMIT_DEFAULT_SEMANTIC =
            "sha256:264a41ff2fbe8878acc9007aa184a549b08f458a42fc1ed9fe0bcb5c0303ab86";
    private static final String HERMIT_PROTEGE_SEMANTIC =
            "sha256:fb224b07454b5f598f53021ee67636ed812704b0a202323d797863d9339b8adb";
    private static final String SIMPLE_SEMANTIC =
            "sha256:16f84a5f6a35f57cdceae4c163311dfb5809f24196c92b073970dd59bd5d01b0";
    private static final String ELK_SEMANTIC =
            "sha256:ac05fb26ad6b7004873e0746dd30f741aa84888520c14784e349bfbdcc35c28b";
    private static final String HERMIT_BINARY_DIGEST =
            "sha256:26e6119163fd1249797553488fd9a531578380fff442c36c57d930066e186da9";
    private static final String STRUCTURAL_BINARY_DIGEST =
            "sha256:050a3fd71f9263bd41d723bcd4b9774f26ae200a43d9d547f7c7dd2780b03587";
    private static final String ELK_BINARY_DIGEST =
            "sha256:9d010d6d8774da376fb44c279eea4829c25128fd9f4576e7a4d2cc0dfeac56eb";
    private static final String HERMIT_CONFIGURATION_BINARY_DIGEST =
            "sha256:b9fe7e5c8517cc59906e12ddcecd4f03311ef082d49e6cfe6784b4f8f923a7b6";
    private static final String SIMPLE_CONFIGURATION_BINARY_DIGEST =
            "sha256:cff921faad8172a52347ba3f44ef720b53a4a301a729a256e8c8f7474bba7080";
    private static final String ELK_CONFIGURATION_BINARY_DIGEST =
            "sha256:129cd45bfcf8b28c2a20b7601af6ed39196f94abffbbf77b3803002f99021431";
    private static final String HERMIT_RAW_CODE =
            "sha256:41d6c9cdd95485aeee392c6a247e6da22428ed92cad7a5ebbe60953a043f4239";
    private static final String HERMIT_MODULAR_CODE =
            "sha256:e2d7c9deb0b9400f4cd6b493e2fb4a7c4a0b090a432b7ccae7fd0854c9c8d755";
    private static final String HERMIT_SHADED_CODE =
            "sha256:c928f88672398c60bcf6b4445a5c753cf41accb4ec0f993042292ba9a1e6d1c8";
    private static final String STRUCTURAL_CODE =
            "sha256:6b9d0eb05e45400684f6f2802d6873b5557451676d52d6a809c21e85a51bbcf4";
    private static final String ELK_CODE =
            "sha256:e984e8e64e2c42f3b30341f0b2ade123fc268860e85062c01718a742ee8e53ec";
    private static final String ELK_PROTEGE_CODE =
            "sha256:bd8bfedbc0424abce7d10e310a211eb5c544291c93b6101131dc997b8adc5821";
    private static final List<String> HERMIT_CODE_SCOPES = List.of(
            "org/semanticweb/HermiT/**", "rationals/**", "dk/brics/automaton/**",
            "org/apache/axiom/**", "org/semanticweb/owlapi/**");
    private static final List<String> HERMIT_SHADED_CODE_SCOPES = List.of(
            "org/semanticweb/HermiT/**", "rationals/**", "dk/brics/automaton/**",
            "org/apache/axiom/**", "org/semanticweb/owlapi/**", "net/automatalib/**");
    private static final List<String> STRUCTURAL_CODE_SCOPES = List.of(
            "org/semanticweb/owlapi/**");
    private static final List<String> ELK_CODE_SCOPES = List.of(
            "org/semanticweb/elk/**", "org/semanticweb/owlapi/**");

    private static final List<ReviewedVariant> REVIEWED = List.of(
            variant(ProfileKind.HERMIT, "org.semanticweb.hermit.HermiT.reasoner.factory",
                    HERMIT_BINARY_DIGEST, HERMIT_RAW_CODE, HERMIT_CODE_SCOPES, 2_635,
                    "org.semanticweb.HermiT.Configuration", "custom",
                    HERMIT_PROTEGE_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.HERMIT, "HermiT.reasoner.factory",
                    HERMIT_BINARY_DIGEST, HERMIT_RAW_CODE, HERMIT_CODE_SCOPES, 2_635,
                    "org.semanticweb.HermiT.Configuration", "custom",
                    HERMIT_PROTEGE_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.HERMIT, "org.semanticweb.HermiT",
                    HERMIT_BINARY_DIGEST, HERMIT_RAW_CODE, HERMIT_CODE_SCOPES, 2_635,
                    "org.semanticweb.HermiT.Configuration", "factory_default",
                    HERMIT_DEFAULT_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.HERMIT, "org.semanticweb.HermiT.ReasonerFactory",
                    HERMIT_BINARY_DIGEST, HERMIT_RAW_CODE, HERMIT_CODE_SCOPES, 2_635,
                    "org.semanticweb.owlapi.reasoner.SimpleConfiguration", "owlapi_standard",
                    SIMPLE_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.HERMIT, "org.semanticweb.HermiT.ReasonerFactory",
                    HERMIT_BINARY_DIGEST, HERMIT_SHADED_CODE, HERMIT_SHADED_CODE_SCOPES, 3_118,
                    "org.semanticweb.owlapi.reasoner.SimpleConfiguration", "owlapi_standard",
                    SIMPLE_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.HERMIT, "org.semanticweb.HermiT.ReasonerFactory",
                    HERMIT_BINARY_DIGEST, HERMIT_MODULAR_CODE, HERMIT_SHADED_CODE_SCOPES, 2_722,
                    "org.semanticweb.owlapi.reasoner.SimpleConfiguration", "owlapi_standard",
                    SIMPLE_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.STRUCTURAL,
                    "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory",
                    STRUCTURAL_BINARY_DIGEST, STRUCTURAL_CODE, STRUCTURAL_CODE_SCOPES, 1_533,
                    "org.semanticweb.owlapi.reasoner.SimpleConfiguration", "owlapi_standard",
                    SIMPLE_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.ELK, "org.semanticweb.elk.owlapi.ElkReasonerFactory",
                    ELK_BINARY_DIGEST, ELK_CODE, ELK_CODE_SCOPES, 4_848,
                    "org.semanticweb.elk.owlapi.ElkReasonerConfiguration", "factory_default",
                    ELK_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.ELK, "org.semanticweb.elk.owlapi.ElkReasonerFactory",
                    ELK_BINARY_DIGEST, ELK_PROTEGE_CODE, ELK_CODE_SCOPES, 4_871,
                    "org.semanticweb.elk.owlapi.ElkReasonerConfiguration", "factory_default",
                    ELK_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.ELK, "au.csiro.elk.reasoner.factory",
                    ELK_BINARY_DIGEST, ELK_PROTEGE_CODE, ELK_CODE_SCOPES, 4_871,
                    "org.semanticweb.elk.owlapi.ElkReasonerConfiguration", "factory_default",
                    ELK_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.ELK, "org.semanticweb.elk.elk.reasoner.factory",
                    ELK_BINARY_DIGEST, ELK_PROTEGE_CODE, ELK_CODE_SCOPES, 4_871,
                    "org.semanticweb.elk.owlapi.ElkReasonerConfiguration", "factory_default",
                    ELK_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.ELK, "org.semanticweb.elk.owlapi.ElkReasonerFactory",
                    ELK_BINARY_DIGEST, ELK_PROTEGE_CODE, ELK_CODE_SCOPES, 4_871,
                    "org.semanticweb.elk.owlapi.ElkReasonerConfiguration", "factory_default",
                    ELK_SEMANTIC, "NON_BUFFERING"),
            variant(ProfileKind.ELK, "au.csiro.elk.reasoner.factory",
                    ELK_BINARY_DIGEST, ELK_PROTEGE_CODE, ELK_CODE_SCOPES, 4_871,
                    "org.semanticweb.elk.owlapi.ElkReasonerConfiguration", "factory_default",
                    ELK_SEMANTIC, "NON_BUFFERING"),
            variant(ProfileKind.ELK, "org.semanticweb.elk.elk.reasoner.factory",
                    ELK_BINARY_DIGEST, ELK_PROTEGE_CODE, ELK_CODE_SCOPES, 4_871,
                    "org.semanticweb.elk.owlapi.ElkReasonerConfiguration", "factory_default",
                    ELK_SEMANTIC, "NON_BUFFERING"),
            variant(ProfileKind.ELK, "org.semanticweb.elk",
                    ELK_BINARY_DIGEST, ELK_CODE, ELK_CODE_SCOPES, 4_848,
                    "org.semanticweb.elk.owlapi.ElkReasonerConfiguration", "factory_default",
                    ELK_SEMANTIC, "BUFFERING"),
            variant(ProfileKind.ELK, "org.semanticweb.elk.owlapi.ElkReasonerFactory",
                    ELK_BINARY_DIGEST, ELK_CODE, ELK_CODE_SCOPES, 4_848,
                    "org.semanticweb.owlapi.reasoner.SimpleConfiguration", "owlapi_standard",
                    SIMPLE_SEMANTIC, "BUFFERING"));

    public ReasonerCapabilityReport report(ReasonerIdentity identity) {
        if (identity == null) throw new IllegalArgumentException("reasoner identity is required");
        return build(identity);
    }

    private static ReasonerCapabilityReport build(ReasonerIdentity identity) {
        Profile profile = profile(identity);
        if (profile == null) return unknown(identity);
        return new ReasonerCapabilityReport(identity, "reviewed", profile.owl,
                profile.rules, profile.atoms, profile.incompatibilities);
    }

    private static Profile profile(ReasonerIdentity identity) {
        return REVIEWED.stream().filter(variant -> variant.matches(identity)).findFirst()
                .map(variant -> switch (variant.kind) {
                    case HERMIT -> hermit();
                    case STRUCTURAL -> structural();
                    case ELK -> elk();
                }).orElse(null);
    }

    private static ReasonerCapabilityReport unknown(ReasonerIdentity identity) {
        String evidence = "No reviewed 0.8 profile exactly matches factory id, factory class, "
                + "reviewed runtime-code evidence, implementation version, semantic "
                + "configuration class bytes/digest, and effective buffering mode as one exact tuple.";
        return new ReasonerCapabilityReport(identity, "unknown",
                statuses(owlVocabulary(), CapabilityStatus.UNKNOWN, evidence),
                statuses(ruleVocabulary(), CapabilityStatus.UNKNOWN, evidence),
                statuses(atomVocabulary(), CapabilityStatus.UNKNOWN, evidence),
                List.of("Unknown profiles are never treated as supporting an absent capability.",
                        "Custom and side-effecting built-ins remain unsupported."));
    }

    private static Profile hermit() {
        String reviewed = "Reviewed HermiT 1.3.8.431 OWLAPI 4.x profile.";
        Map<String, ReasonerCapabilityReport.Entry> owl = statuses(
                owlVocabulary(), CapabilityStatus.SUPPORTED, reviewed);
        put(owl, "incremental_reasoning", CapabilityStatus.UNSUPPORTED,
                "This HermiT build recomputes classification and has no reviewed incremental guarantee.");
        put(owl, "native_explanations", CapabilityStatus.UNSUPPORTED,
                "HermiT exposes no native OWLAPI explanation service in this integration.");
        put(owl, "black_box_explanations", CapabilityStatus.SUPPORTED,
                "Prot\u00e9g\u00e9 MCP uses isolated black-box explanation over this reasoner.");
        put(owl, "bounded_cancellation", CapabilityStatus.UNTESTED,
                "Synchronous disposal exists, but the five-second asynchronous cancellation profile is untested.");

        Map<String, ReasonerCapabilityReport.Entry> rules = statuses(
                ruleVocabulary(), CapabilityStatus.SUPPORTED, reviewed);
        put(rules, "swrl_builtins", CapabilityStatus.UNSUPPORTED,
                "HermiT 1.3.8.431 rejects SWRL built-in atoms during clausification.");

        Map<String, ReasonerCapabilityReport.Entry> atoms = statuses(
                atomVocabulary(), CapabilityStatus.SUPPORTED, reviewed);
        put(atoms, "built_in", CapabilityStatus.UNSUPPORTED,
                "Built-in atoms are outside the reviewed HermiT rule subset.");
        return new Profile(owl, rules, atoms, List.of(
                "SWRL built-in atoms are unsupported and can make reasoner creation fail.",
                "Reasoning is not claimed to be incrementally maintained.",
                "Asynchronous bounded cancellation is untested in this slice."));
    }

    private static Profile structural() {
        String unsupported = "The OWLAPI structural reasoner 4.5.29 does not provide complete "
                + "semantic reasoning for this capability.";
        Map<String, ReasonerCapabilityReport.Entry> owl = statuses(
                owlVocabulary(), CapabilityStatus.UNSUPPORTED, unsupported);
        for (String id : List.of("class_hierarchy", "equivalent_classes", "class_assertions",
                "object_property_hierarchy", "data_property_hierarchy")) {
            put(owl, id, CapabilityStatus.SUPPORTED,
                    "Reviewed structural-only behavior in OWLAPI 4.5.29; this is not OWL 2 DL completeness.");
        }
        put(owl, "consistency", CapabilityStatus.UNSUPPORTED,
                "The structural reasoner does not perform semantic consistency checking.");
        put(owl, "satisfiability", CapabilityStatus.UNSUPPORTED,
                "Structural traversal is not a semantic class-satisfiability decision procedure.");
        put(owl, "incremental_reasoning", CapabilityStatus.UNTESTED,
                "Change buffering exists, but no semantic incremental guarantee is reviewed.");
        put(owl, "native_explanations", CapabilityStatus.UNSUPPORTED,
                "The structural reasoner exposes no native explanation service.");
        put(owl, "black_box_explanations", CapabilityStatus.UNTESTED,
                "Black-box output over structural reasoning is not a complete OWL explanation.");
        put(owl, "bounded_cancellation", CapabilityStatus.SUPPORTED,
                "The in-process structural reasoner disposes without a long-running compute phase.");
        return new Profile(owl,
                statuses(ruleVocabulary(), CapabilityStatus.UNSUPPORTED,
                        "OWLAPI structural reasoner 4.5.29 does not execute SWRL rules."),
                statuses(atomVocabulary(), CapabilityStatus.UNSUPPORTED,
                        "OWLAPI structural reasoner 4.5.29 does not execute SWRL atoms."),
                List.of("SWRL rules are ignored for inference.",
                        "Structural hierarchy output is not complete OWL 2 semantic reasoning."));
    }

    private static Profile elk() {
        String unsupported = "ELK 0.5.0 is an OWL 2 EL reasoner; this construct is outside the "
                + "reviewed EL profile.";
        Map<String, ReasonerCapabilityReport.Entry> owl = statuses(
                owlVocabulary(), CapabilityStatus.UNSUPPORTED, unsupported);
        for (String id : List.of("owl2_el", "class_hierarchy", "equivalent_classes",
                "class_assertions", "object_property_hierarchy", "property_chains",
                "consistency", "satisfiability")) {
            put(owl, id, CapabilityStatus.SUPPORTED,
                    "Reviewed ELK 0.5.0 behavior within the OWL 2 EL profile.");
        }
        put(owl, "incremental_reasoning", CapabilityStatus.SUPPORTED,
                "ELK 0.5.0 has reviewed incremental classification within its supported profile.");
        put(owl, "native_explanations", CapabilityStatus.UNTESTED,
                "No native explanation contract is exposed by this integration.");
        put(owl, "black_box_explanations", CapabilityStatus.UNTESTED,
                "Black-box explanation coverage is not reviewed for ELK 0.5.0.");
        put(owl, "bounded_cancellation", CapabilityStatus.UNTESTED,
                "The five-second asynchronous cancellation profile has not been proven.");
        return new Profile(owl,
                statuses(ruleVocabulary(), CapabilityStatus.UNSUPPORTED,
                        "ELK 0.5.0 does not execute SWRL rules."),
                statuses(atomVocabulary(), CapabilityStatus.UNSUPPORTED,
                        "ELK 0.5.0 does not execute SWRL atoms."),
                List.of("SWRL rules are ignored for inference.",
                        "Axioms outside OWL 2 EL are not covered by the reviewed profile.",
                        "Complex-expression query completeness can differ from DL reasoners."));
    }

    private static List<String> owlVocabulary() {
        return ReasonerCapabilityReport.OWL_CAPABILITY_IDS;
    }

    private static List<String> ruleVocabulary() {
        return ReasonerCapabilityReport.RULE_CAPABILITY_IDS;
    }

    private static List<String> atomVocabulary() {
        return ReasonerCapabilityReport.ATOM_CAPABILITY_IDS;
    }

    private static Map<String, ReasonerCapabilityReport.Entry> statuses(List<String> ids,
            CapabilityStatus status, String evidence) {
        Map<String, ReasonerCapabilityReport.Entry> out = new LinkedHashMap<>();
        for (String id : ids) put(out, id, status, evidence);
        return out;
    }

    private static void put(Map<String, ReasonerCapabilityReport.Entry> target, String id,
            CapabilityStatus status, String evidence) {
        target.put(id, new ReasonerCapabilityReport.Entry(id, status, evidence));
    }

    private static ReviewedVariant variant(ProfileKind kind, String id, String factoryDigest,
            String codeDigest, List<String> codeScopes, int codeClassCount,
            String configurationClass, String configurationProfile, String semanticDigest,
            String bufferingMode) {
        String factoryClass = switch (kind) {
            case HERMIT -> "org.semanticweb.HermiT.ReasonerFactory";
            case STRUCTURAL ->
                    "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory";
            case ELK -> "org.semanticweb.elk.owlapi.ElkReasonerFactory";
        };
        String version = switch (kind) {
            case HERMIT -> HERMIT_VERSION;
            case STRUCTURAL -> STRUCTURAL_VERSION;
            case ELK -> ELK_VERSION;
        };
        String configurationBinaryDigest = switch (configurationClass) {
            case "org.semanticweb.HermiT.Configuration" ->
                    HERMIT_CONFIGURATION_BINARY_DIGEST;
            case "org.semanticweb.elk.owlapi.ElkReasonerConfiguration" ->
                    ELK_CONFIGURATION_BINARY_DIGEST;
            case "org.semanticweb.owlapi.reasoner.SimpleConfiguration" ->
                    SIMPLE_CONFIGURATION_BINARY_DIGEST;
            default -> throw new IllegalArgumentException(
                    "unreviewed configuration class: " + configurationClass);
        };
        return new ReviewedVariant(kind, id, factoryClass, factoryDigest, codeDigest,
                codeScopes, codeClassCount, version, configurationClass,
                configurationBinaryDigest, configurationProfile, semanticDigest, bufferingMode);
    }

    private enum ProfileKind { HERMIT, STRUCTURAL, ELK }

    private record ReviewedVariant(ProfileKind kind, String factoryId, String factoryClass,
            String factoryDigest, String codeDigest, List<String> codeScopes,
            int codeClassCount, String version, String configurationClass,
            String configurationBinaryDigest, String configurationProfile,
            String semanticDigest, String bufferingMode) {
        ReviewedVariant {
            codeScopes = List.copyOf(codeScopes);
        }

        boolean matches(ReasonerIdentity identity) {
            return factoryId.equals(identity.factoryId())
                    && factoryClass.equals(identity.factoryClass())
                    && factoryDigest.equals(identity.factoryBinaryDigest())
                    && codeDigest.equals(identity.reviewedCodeDigest())
                    && codeScopes.equals(identity.reviewedCodeScopes())
                    && codeClassCount == identity.reviewedCodeClassCount()
                    && version.equals(identity.implementationVersion())
                    && configurationClass.equals(identity.configurationClass())
                    && configurationBinaryDigest.equals(identity.configurationBinaryDigest())
                    && configurationProfile.equals(identity.configurationProfile())
                    && semanticDigest.equals(identity.semanticConfigurationDigest())
                    && bufferingMode.equals(identity.bufferingMode());
        }
    }

    private record Profile(Map<String, ReasonerCapabilityReport.Entry> owl,
            Map<String, ReasonerCapabilityReport.Entry> rules,
            Map<String, ReasonerCapabilityReport.Entry> atoms,
            List<String> incompatibilities) { }
}
