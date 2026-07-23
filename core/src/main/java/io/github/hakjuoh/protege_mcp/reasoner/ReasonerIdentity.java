package io.github.hakjuoh.protege_mcp.reasoner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.ReasonerProgressMonitor;

/** Bounded, non-secret identity; reviewed profiles require a complete exact capture. */
public record ReasonerIdentity(
        String factoryId,
        String factoryClass,
        String factoryBinaryDigest,
        String reviewedCodeDigest,
        List<String> reviewedCodeScopes,
        int reviewedCodeClassCount,
        String reasonerName,
        String implementationVersion,
        String configurationClass,
        String configurationBinaryDigest,
        String configurationProfile,
        String configurationDigest,
        String semanticConfigurationDigest,
        long timeoutMillis,
        String progressMonitorClass,
        String freshEntityPolicy,
        String individualNodeSetPolicy,
        String bufferingMode,
        String configurationSource) {

    private static final int MAX_CONFIGURATION_DEPTH = 4;
    private static final int MAX_CONFIGURATION_ITEMS = 256;
    private static final int MAX_CONFIGURATION_NODES = 1_024;
    private static final int MAX_CONFIGURATION_UTF8_BYTES = 1_048_576;
    private static final long MAX_CONFIGURATION_CAPTURE_MILLIS = 1_000L;
    private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;

    public ReasonerIdentity {
        factoryId = required(factoryId, "factoryId");
        factoryClass = required(factoryClass, "factoryClass");
        if (!"unknown".equals(factoryBinaryDigest)
                && (factoryBinaryDigest == null
                || !factoryBinaryDigest.matches("sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("factoryBinaryDigest must be unknown or SHA-256");
        }
        if (!"unknown".equals(reviewedCodeDigest)
                && (reviewedCodeDigest == null
                || !reviewedCodeDigest.matches("sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("reviewedCodeDigest must be unknown or SHA-256");
        }
        reviewedCodeScopes = List.copyOf(Objects.requireNonNull(reviewedCodeScopes,
                "reviewedCodeScopes"));
        if (reviewedCodeScopes.size() > 16
                || Set.copyOf(reviewedCodeScopes).size() != reviewedCodeScopes.size()
                || reviewedCodeScopes.stream().anyMatch(value -> value.isBlank()
                        || value.length() > 4096)) {
            throw new IllegalArgumentException("reviewedCodeScopes must contain at most 16 names");
        }
        if (reviewedCodeClassCount < 0 || reviewedCodeClassCount > 6_000) {
            throw new IllegalArgumentException("reviewedCodeClassCount must be between 0 and 6000");
        }
        if (("unknown".equals(reviewedCodeDigest) || reviewedCodeScopes.isEmpty())
                != (reviewedCodeClassCount == 0)) {
            throw new IllegalArgumentException("reviewed code evidence must be complete or unknown");
        }
        reasonerName = required(reasonerName, "reasonerName");
        implementationVersion = required(implementationVersion, "implementationVersion");
        configurationClass = required(configurationClass, "configurationClass");
        if (!"unknown".equals(configurationBinaryDigest)
                && (configurationBinaryDigest == null
                || !configurationBinaryDigest.matches("sha256:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "configurationBinaryDigest must be unknown or SHA-256");
        }
        if (!List.of("owlapi_standard", "factory_default", "custom", "unrecognized")
                .contains(configurationProfile)) {
            throw new IllegalArgumentException("invalid configurationProfile");
        }
        if (configurationDigest == null
                || !configurationDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("configurationDigest must be a SHA-256 digest");
        }
        if (semanticConfigurationDigest == null
                || !semanticConfigurationDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "semanticConfigurationDigest must be a SHA-256 digest");
        }
        if (timeoutMillis < -2) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be -2, -1, or non-negative");
        }
        progressMonitorClass = required(progressMonitorClass, "progressMonitorClass");
        freshEntityPolicy = required(freshEntityPolicy, "freshEntityPolicy");
        individualNodeSetPolicy = required(individualNodeSetPolicy, "individualNodeSetPolicy");
        bufferingMode = required(bufferingMode, "bufferingMode");
        configurationSource = required(configurationSource, "configurationSource");
    }

    public static ReasonerIdentity capture(String factoryId, String reasonerName,
            OWLReasonerFactory factory, OWLReasonerConfiguration configuration,
            BufferingMode bufferingMode, String configurationSource) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(bufferingMode, "bufferingMode");
        String discoveredName = reasonerName;
        if (discoveredName == null || discoveredName.isBlank()) {
            discoveredName = factory.getClass().getSimpleName();
        }
        if ("org.semanticweb.HermiT.ReasonerFactory".equals(factory.getClass().getName())) {
            discoveredName = "HermiT";
        } else if ("org.semanticweb.elk.owlapi.ElkReasonerFactory"
                .equals(factory.getClass().getName())) {
            discoveredName = "ELK";
        }
        String discoveredId = factoryId;
        if (discoveredId == null || discoveredId.isBlank()) {
            discoveredId = factory.getClass().getName();
        }
        RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.capture(factory.getClass());
        String configurationClass = configuration.getClass().getName();
        if (!reviewableConfigurationClass(configurationClass)) {
            return unrecognized(discoveredId, discoveredName, factory.getClass(), evidence,
                    configuration.getClass(), bufferingMode, configurationSource);
        }
        try {
            ClassLoader factoryLoader = factory.getClass().getClassLoader();
            Class<?> factoryConfigurationType = Class.forName(configurationClass, false,
                    factoryLoader);
            if (factoryConfigurationType != configuration.getClass()) {
                return unrecognized(discoveredId, discoveredName, factory.getClass(), evidence,
                        configuration.getClass(), bufferingMode, configurationSource);
            }
            if (!configurationCaptureComplete(configuration)) {
                return unrecognized(discoveredId, discoveredName, factory.getClass(), evidence,
                        configuration.getClass(), bufferingMode, configurationSource);
            }
            return new ReasonerIdentity(discoveredId, factory.getClass().getName(),
                    classDigest(factory.getClass()), evidence.digest(), evidence.scopes(),
                    evidence.classCount(), discoveredName,
                    implementationVersion(factory.getClass()), configurationClass,
                    classDigest(configuration.getClass()),
                    configurationProfile(configuration),
                    configurationDigest(configuration), semanticConfigurationDigest(configuration),
                    configuration.getTimeOut(),
                    configuration.getProgressMonitor() == null ? "none"
                            : configuration.getProgressMonitor().getClass().getName(),
                    configuration.getFreshEntityPolicy() == null ? "unknown"
                            : configuration.getFreshEntityPolicy().name(),
                    configuration.getIndividualNodeSetPolicy() == null ? "unknown"
                            : configuration.getIndividualNodeSetPolicy().name(),
                    bufferingMode.name(), configurationSource);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return unrecognized(discoveredId, discoveredName, factory.getClass(), evidence,
                    configuration.getClass(), bufferingMode, configurationSource);
        }
    }

    private static ReasonerIdentity unrecognized(String factoryId, String reasonerName,
            Class<?> factoryClass, RuntimeCodeEvidence.Evidence evidence,
            Class<?> configurationType, BufferingMode bufferingMode, String configurationSource) {
        String configurationClass = configurationType.getName();
        String configurationBinaryDigest = classDigest(configurationType);
        String unrecognizedDigest = digest(List.of("unrecognized", configurationClass,
                configurationBinaryDigest));
        return new ReasonerIdentity(factoryId, factoryClass.getName(), classDigest(factoryClass),
                evidence.digest(), evidence.scopes(), evidence.classCount(), reasonerName,
                implementationVersion(factoryClass), configurationClass,
                configurationBinaryDigest, "unrecognized",
                unrecognizedDigest, unrecognizedDigest, -2L, "unrecognized", "unrecognized",
                "unrecognized", bufferingMode.name(), configurationSource);
    }

    private static boolean reviewableConfigurationClass(String className) {
        return Set.of("org.semanticweb.owlapi.reasoner.SimpleConfiguration",
                "org.semanticweb.HermiT.Configuration",
                "org.semanticweb.elk.owlapi.ElkReasonerConfiguration").contains(className);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("factory_id", factoryId);
        out.put("factory_class", factoryClass);
        out.put("factory_binary_digest", factoryBinaryDigest);
        out.put("reviewed_code_digest", reviewedCodeDigest);
        out.put("reviewed_code_scopes", reviewedCodeScopes);
        out.put("reviewed_code_class_count", reviewedCodeClassCount);
        out.put("reasoner_name", reasonerName);
        out.put("implementation_version", implementationVersion);
        out.put("configuration_class", configurationClass);
        out.put("configuration_binary_digest", configurationBinaryDigest);
        out.put("configuration_profile", configurationProfile);
        out.put("configuration_digest", configurationDigest);
        out.put("semantic_configuration_digest", semanticConfigurationDigest);
        out.put("timeout_ms", timeoutMillis);
        out.put("progress_monitor_class", progressMonitorClass);
        out.put("fresh_entity_policy", freshEntityPolicy);
        out.put("individual_node_set_policy", individualNodeSetPolicy);
        out.put("buffering_mode", bufferingMode.toLowerCase(java.util.Locale.ROOT));
        out.put("configuration_source", configurationSource);
        out.put("profile_key", profileKey());
        return out;
    }

    public String profileKey() {
        return digest(List.of(factoryId, factoryClass, factoryBinaryDigest,
                reviewedCodeDigest, String.join("\n", reviewedCodeScopes),
                String.valueOf(reviewedCodeClassCount),
                implementationVersion, configurationClass,
                configurationBinaryDigest,
                configurationDigest, semanticConfigurationDigest, bufferingMode));
    }

    private static String classDigest(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) return "unknown";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = in.read(buffer)) >= 0;) {
                total += read;
                if (total > MAX_CLASS_BYTES) return "unknown";
                digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder("sha256:");
            for (byte value : digest.digest()) {
                out.append(String.format("%02x", value & 0xff));
            }
            return out.toString();
        } catch (IOException | NoSuchAlgorithmException unavailable) {
            return "unknown";
        }
    }


    private static String implementationVersion(Class<?> type) {
        Package pkg = type.getPackage();
        String raw = pkg == null ? null : pkg.getImplementationVersion();
        if (raw == null || raw.isBlank()) {
            raw = pkg == null ? null : pkg.getSpecificationVersion();
        }
        if (raw == null || raw.isBlank()) raw = mavenVersion(type);
        if (raw == null || raw.isBlank() || raw.length() > 128) return "unknown";
        if ("org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory"
                .equals(type.getName())
                && "4.5.29.2024-05-13T12:11:03Z".equals(raw)) {
            return "4.5.29";
        }
        if ("org.semanticweb.HermiT.ReasonerFactory".equals(type.getName())
                && "1.3.8.431.2017-03-27T23:13:37Z".equals(raw)) {
            return "1.3.8.431";
        }
        if ("org.semanticweb.elk.owlapi.ElkReasonerFactory".equals(type.getName())
                && "0.5.0.2020-10-11T02:30:53Z".equals(raw)) {
            return "0.5.0";
        }
        return raw;
    }

    private static String mavenVersion(Class<?> type) {
        String resource = switch (type.getName()) {
            case "org.semanticweb.HermiT.ReasonerFactory" ->
                    "META-INF/maven/net.sourceforge.owlapi/org.semanticweb.hermit/pom.properties";
            case "org.semanticweb.elk.owlapi.ElkReasonerFactory" ->
                    "META-INF/maven/au.csiro/elk-owlapi4/pom.properties";
            default -> null;
        };
        if (resource == null) return null;
        ClassLoader loader = type.getClassLoader();
        try (InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource)) {
            if (in == null) return null;
            byte[] bytes = in.readNBytes(65_537);
            if (bytes.length > 65_536) return null;
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(bytes));
            String version = properties.getProperty("version");
            return version == null || version.length() > 128 ? null : version;
        } catch (IOException unreadable) {
            return null;
        }
    }

    static String configurationDigest(OWLReasonerConfiguration configuration) {
        CaptureBudget budget = new CaptureBudget();
        Map<String, Object> state = new TreeMap<>();
        state.put("configuration_class", configuration.getClass().getName());
        state.put("fresh_entity_policy", enumValue(configuration.getFreshEntityPolicy()));
        state.put("individual_node_set_policy",
                enumValue(configuration.getIndividualNodeSetPolicy()));
        state.put("timeout_ms", configuration.getTimeOut());
        state.put("progress_monitor_class", configuration.getProgressMonitor() == null
                ? null : configuration.getProgressMonitor().getClass().getName());
        state.put("public_state", publicState(configuration, 0, budget));
        state.put("known_configuration_extension",
                knownConfigurationExtension(configuration, budget, false));
        return digest(List.of(canonical(state)));
    }

    static boolean configurationCaptureComplete(OWLReasonerConfiguration configuration) {
        CaptureBudget budget = new CaptureBudget();
        return fullyCaptured(publicState(configuration, 0, budget))
                && fullyCaptured(knownConfigurationExtension(configuration, budget, false));
    }

    static String semanticConfigurationDigest(OWLReasonerConfiguration configuration) {
        CaptureBudget budget = new CaptureBudget();
        Map<String, Object> state = new TreeMap<>();
        state.put("configuration_class", configuration.getClass().getName());
        state.put("fresh_entity_policy", enumValue(configuration.getFreshEntityPolicy()));
        state.put("individual_node_set_policy",
                enumValue(configuration.getIndividualNodeSetPolicy()));
        state.put("semantic_state", semanticPublicState(configuration, budget));
        return digest(List.of(canonical(state)));
    }

    private static String configurationProfile(OWLReasonerConfiguration configuration) {
        if ("org.semanticweb.owlapi.reasoner.SimpleConfiguration"
                .equals(configuration.getClass().getName())) {
            return "owlapi_standard";
        }
        if (!Set.of("org.semanticweb.HermiT.Configuration",
                "org.semanticweb.elk.owlapi.ElkReasonerConfiguration")
                .contains(configuration.getClass().getName())) {
            return "unrecognized";
        }
        Map<String, Object> extension = knownSemanticExtension(configuration);
        if (!fullyCaptured(extension)) return "unrecognized";
        try {
            Object baseline = configuration.getClass().getDeclaredConstructor().newInstance();
            Map<String, Object> current = semanticPublicState(configuration);
            Map<String, Object> defaults = semanticPublicState(baseline);
            if (!fullyCaptured(current) || !fullyCaptured(defaults)) return "unrecognized";
            return canonical(current).equals(canonical(defaults))
                            ? "factory_default" : "custom";
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return "unrecognized";
        }
    }

    private static Map<String, Object> semanticPublicState(Object configuration) {
        return semanticPublicState(configuration, new CaptureBudget());
    }

    private static Map<String, Object> semanticPublicState(Object configuration,
            CaptureBudget budget) {
        Map<String, Object> fields = new TreeMap<>(publicState(configuration, 0, budget));
        for (String operational : List.of("reasonerProgressMonitor", "monitor",
                "individualTaskTimeout")) {
            fields.remove(operational);
        }
        Map<String, Object> state = new TreeMap<>();
        state.put("public_fields", fields);
        state.put("known_semantic_extension",
                knownConfigurationExtension(configuration, budget, true));
        return state;
    }

    private static Map<String, Object> knownSemanticExtension(Object configuration) {
        return knownConfigurationExtension(configuration, new CaptureBudget(), true);
    }

    private static Map<String, Object> knownSemanticExtension(Object configuration,
            CaptureBudget budget) {
        return knownConfigurationExtension(configuration, budget, true);
    }

    private static Map<String, Object> knownConfigurationExtension(Object configuration,
            CaptureBudget budget, boolean semanticOnly) {
        if (!budget.claim()) return Map.of("captured", false, "reason", "node_budget");
        if (!"org.semanticweb.elk.owlapi.ElkReasonerConfiguration"
                .equals(configuration.getClass().getName())) {
            return Map.of();
        }
        try {
            Method elkGetter = configuration.getClass().getMethod("getElkConfiguration");
            Object elk = elkGetter.invoke(configuration);
            Method namesGetter = elk.getClass().getMethod("getParameterNames");
            Method parameterGetter = elk.getClass().getMethod("getParameter", String.class);
            Object rawNames = namesGetter.invoke(elk);
            if (!(rawNames instanceof Collection<?> names)) return Map.of("captured", false);
            if (names.size() > MAX_CONFIGURATION_ITEMS) {
                return Map.of("captured", false, "item_count", names.size());
            }
            Map<String, Object> parameters = new TreeMap<>();
            for (Object rawName : names) {
                if (!budget.withinTime() || !(rawName instanceof String parameterName)) {
                    return Map.of("captured", false, "reason", "unsupported_parameter_name");
                }
                if (parameters.size() >= MAX_CONFIGURATION_ITEMS) break;
                String name = safeKey(parameterName, budget);
                if (name == null || parameters.containsKey(name)) {
                    return Map.of("captured", false, "reason", "unsupported_or_duplicate_key");
                }
                if (semanticOnly && isOperationalElkParameter(name)) continue;
                Object value = parameterGetter.invoke(elk, parameterName);
                parameters.put(name, safeValue(value, 1, budget));
            }
            return Map.of("captured", true, "elk_parameters", parameters);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return Map.of("captured", false);
        }
    }

    private static Map<String, Object> publicState(Object value, int depth,
            CaptureBudget budget) {
        Map<String, Object> out = new TreeMap<>();
        if (!budget.claim()) {
            return Map.of("__capture__", Map.of("captured", false,
                    "reason", "node_budget"));
        }
        if (depth >= MAX_CONFIGURATION_DEPTH) return out;
        List<Field> fields = new ArrayList<>();
        for (Field field : value.getClass().getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) fields.add(field);
        }
        if (fields.size() > MAX_CONFIGURATION_ITEMS) {
            return Map.of("__capture__", Map.of("captured", false,
                    "item_count", fields.size()));
        }
        fields.sort(Comparator.comparing(Field::getName));
        int retained = 0;
        for (Field field : fields) {
            if (retained++ >= MAX_CONFIGURATION_ITEMS) break;
            try {
                out.put(field.getName(), safeValue(field.get(value), depth + 1, budget));
            } catch (IllegalAccessException | RuntimeException inaccessible) {
                out.put(field.getName(), Map.of("type", field.getType().getName(),
                        "captured", false));
            }
        }
        return out;
    }

    private static Object safeValue(Object value, int depth, CaptureBudget budget) {
        if (!budget.claim()) {
            return Map.of("type", value == null ? "null" : value.getClass().getName(),
                    "captured", false, "reason", "node_budget");
        }
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Number number) return safeNumber(number, budget);
        if (value instanceof String string) return safeString(string, budget);
        if (value instanceof CharSequence) {
            return Map.of("type", value.getClass().getName(), "captured", false,
                    "reason", "unsupported_char_sequence");
        }
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        if (value instanceof ReasonerProgressMonitor) {
            return Map.of("type", value.getClass().getName(), "identity_only", true);
        }
        if (isTrustedElkParameterValue(value)) {
            return trustedElkParameterValue(value, budget);
        }
        if (depth >= MAX_CONFIGURATION_DEPTH) {
            return Map.of("type", value.getClass().getName(), "captured", false);
        }
        if (value instanceof Map<?, ?> map) {
            if (!isBootstrapCollection(value)) {
                return Map.of("type", value.getClass().getName(), "captured", false,
                        "reason", "unsupported_map_implementation");
            }
            if (map.size() > MAX_CONFIGURATION_ITEMS) {
                return Map.of("type", "map", "captured", false, "item_count", map.size());
            }
            Map<String, Object> out = new TreeMap<>();
            int retained = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (retained++ >= MAX_CONFIGURATION_ITEMS) {
                    return Map.of("type", "map", "captured", false,
                            "reason", "item_budget");
                }
                String key = safeKey(entry.getKey(), budget);
                if (key == null || out.containsKey(key)) {
                    return Map.of("type", "map", "captured", false,
                            "reason", "unsupported_or_duplicate_key");
                }
                out.put(key, safeValue(entry.getValue(), depth + 1, budget));
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            if (!isBootstrapCollection(value)) {
                return Map.of("type", value.getClass().getName(), "captured", false,
                        "reason", "unsupported_collection_implementation");
            }
            if (collection.size() > MAX_CONFIGURATION_ITEMS) {
                return Map.of("type", "collection", "captured", false,
                        "item_count", collection.size());
            }
            List<Object> out = new ArrayList<>();
            int retained = 0;
            for (Object item : collection) {
                if (retained++ >= MAX_CONFIGURATION_ITEMS) break;
                out.add(safeValue(item, depth + 1, budget));
            }
            if (value instanceof Set<?>) {
                out.sort(Comparator.comparing(ReasonerIdentity::canonical));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            if (Array.getLength(value) > MAX_CONFIGURATION_ITEMS) {
                return Map.of("type", "array", "captured", false,
                        "item_count", Array.getLength(value));
            }
            List<Object> out = new ArrayList<>();
            int length = Math.min(Array.getLength(value), MAX_CONFIGURATION_ITEMS);
            for (int index = 0; index < length; index++) {
                out.add(safeValue(Array.get(value, index), depth + 1, budget));
            }
            return out;
        }
        Map<String, Object> reflected = publicState(value, depth, budget);
        if (!reflected.isEmpty()) {
            return Map.of("type", value.getClass().getName(), "state", reflected);
        }
        return Map.of("type", value.getClass().getName(), "captured", false,
                "reason", "opaque_object");
    }

    private static boolean isOperationalElkParameter(String name) {
        return Set.of("elk.reasoner.number_of_workers", "elk.reasoner.tracing.evictor",
                "elk.reasoner.classexpressionquery.evictor",
                "elk.reasoner.entailmentquery.evictor").contains(name);
    }

    private static boolean isTrustedElkParameterValue(Object value) {
        return Set.of("org.semanticweb.elk.reasoner.config.NumberOfWorkers",
                "org.semanticweb.elk.util.collections.RecencyEvictor$Builder",
                "org.semanticweb.elk.util.collections.CapacityBalancingEvictor$Builder",
                "org.semanticweb.elk.util.collections.CountingEvictor$Builder",
                "org.semanticweb.elk.util.collections.NQEvictor$Builder")
                .contains(value.getClass().getName());
    }

    private static Object trustedElkParameterValue(Object value, CaptureBudget budget) {
        try {
            Map<String, Object> state = new TreeMap<>();
            state.put("type", value.getClass().getName());
            if ("org.semanticweb.elk.reasoner.config.NumberOfWorkers"
                    .equals(value.getClass().getName())) {
                Method getter = value.getClass().getMethod("getNumberOfWorkers");
                Object workers = getter.invoke(value);
                if (workers == null || workers.getClass() != Integer.class) {
                    return Map.of("type", value.getClass().getName(), "captured", false,
                            "reason", "invalid_worker_count");
                }
                state.put("workers", workers);
                return state;
            }
            switch (value.getClass().getName()) {
                case "org.semanticweb.elk.util.collections.RecencyEvictor$Builder" ->
                        addRecencyFields(state, value, budget);
                case "org.semanticweb.elk.util.collections.CapacityBalancingEvictor$Builder" -> {
                    addRecencyFields(state, value, budget);
                    state.put("balance_bits", trustedDoubleBits(value,
                            "org.semanticweb.elk.util.collections."
                                    + "CapacityBalancingEvictor$ProtectedBuilder",
                            "balance_", budget));
                    state.put("balance_after_queries", trustedInteger(value,
                            "org.semanticweb.elk.util.collections."
                                    + "CapacityBalancingEvictor$ProtectedBuilder",
                            "balanceAfterNRepeatedQueries_", budget));
                }
                case "org.semanticweb.elk.util.collections.CountingEvictor$Builder" -> {
                    addRecencyFields(state, value, budget);
                    state.put("evict_before_add_count", trustedInteger(value,
                            "org.semanticweb.elk.util.collections."
                                    + "CountingEvictor$ProtectedBuilder",
                            "evictBeforeAddCount_", budget));
                }
                case "org.semanticweb.elk.util.collections.NQEvictor$Builder" -> {
                    state.put("capacities", trustedNumberList(value,
                            "org.semanticweb.elk.util.collections.NQEvictor$ProtectedBuilder",
                            "capacities", false, budget));
                    state.put("load_factor_bits", trustedNumberList(value,
                            "org.semanticweb.elk.util.collections.NQEvictor$ProtectedBuilder",
                            "loadFactors", true, budget));
                }
                default -> throw new IllegalArgumentException("unreviewed ELK parameter type");
            }
            return state;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return Map.of("type", value.getClass().getName(), "captured", false,
                    "reason", "elk_parameter_unavailable");
        }
    }

    private static void addRecencyFields(Map<String, Object> state, Object value,
            CaptureBudget budget) throws ReflectiveOperationException {
        String owner = "org.semanticweb.elk.util.collections.RecencyEvictor$ProtectedBuilder";
        state.put("capacity", trustedInteger(value, owner, "capacity_", budget));
        state.put("load_factor_bits", trustedDoubleBits(value, owner, "loadFactor_", budget));
    }

    private static int trustedInteger(Object value, String owner, String name,
            CaptureBudget budget) throws ReflectiveOperationException {
        if (!budget.claim()) throw new IllegalArgumentException("configuration node budget");
        Object raw = trustedField(value, owner, name).get(value);
        if (raw == null || raw.getClass() != Integer.class) {
            throw new IllegalArgumentException("invalid trusted integer field");
        }
        return (Integer) raw;
    }

    private static String trustedDoubleBits(Object value, String owner, String name,
            CaptureBudget budget) throws ReflectiveOperationException {
        if (!budget.claim()) throw new IllegalArgumentException("configuration node budget");
        Object raw = trustedField(value, owner, name).get(value);
        if (raw == null || raw.getClass() != Double.class) {
            throw new IllegalArgumentException("invalid trusted double field");
        }
        return Long.toUnsignedString(Double.doubleToRawLongBits((Double) raw), 16);
    }

    private static List<Object> trustedNumberList(Object value, String owner, String name,
            boolean doubles, CaptureBudget budget) throws ReflectiveOperationException {
        Object raw = trustedField(value, owner, name).get(value);
        if (!(raw instanceof List<?> list) || !isBootstrapCollection(raw)
                || list.size() > MAX_CONFIGURATION_ITEMS) {
            throw new IllegalArgumentException("invalid trusted number list");
        }
        List<Object> captured = new ArrayList<>();
        for (Object item : list) {
            if (!budget.claim()) throw new IllegalArgumentException("configuration node budget");
            if (doubles) {
                if (item == null || item.getClass() != Double.class) {
                    throw new IllegalArgumentException("invalid trusted double list");
                }
                captured.add(Long.toUnsignedString(
                        Double.doubleToRawLongBits((Double) item), 16));
            } else {
                if (item == null || item.getClass() != Integer.class) {
                    throw new IllegalArgumentException("invalid trusted integer list");
                }
                captured.add(item);
            }
        }
        return List.copyOf(captured);
    }

    private static Field trustedField(Object value, String owner, String name)
            throws ReflectiveOperationException {
        Class<?> type = value.getClass();
        while (type != null && !owner.equals(type.getName())) type = type.getSuperclass();
        if (type == null) throw new NoSuchFieldException(owner + "." + name);
        Field field = type.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(owner + "." + name);
        return field;
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> appendToken(out, String.valueOf(entry.getKey()),
                            canonical(entry.getValue())));
            return out.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : collection) appendToken(out, "", canonical(item));
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static void appendToken(StringBuilder out, String key, String value) {
        out.append(key.length()).append(':').append(key)
                .append(value.length()).append(':').append(value).append(';');
    }

    private static String enumValue(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String digest(List<String> values) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        StringBuilder out = new StringBuilder("sha256:");
        for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > 4096) {
            throw new IllegalArgumentException(field + " must not exceed 4096 characters");
        }
        return value;
    }

    private static Object safeString(String value, CaptureBudget budget) {
        String digest = streamingStringDigest(value, budget);
        if (digest == null) {
            return Map.of("type", "string", "length", value.length(),
                    "captured", false, "reason", "byte_or_time_budget");
        }
        if (value.length() <= 4096) return value;
        return Map.of("type", "string", "length", value.length(), "digest", digest);
    }

    private static String safeKey(Object raw, CaptureBudget budget) {
        String value;
        if (raw == null) {
            value = "null";
        } else if (raw instanceof String string) {
            value = string;
        } else if (raw instanceof Enum<?> enumValue) {
            value = raw.getClass().getName() + ":" + enumValue.name();
        } else if (raw instanceof Boolean || isPrimitiveWrapperNumber(raw)) {
            value = raw.getClass().getName() + ":" + raw;
        } else {
            return null;
        }
        String digest = streamingStringDigest(value, budget);
        if (digest == null) return null;
        if (value.length() <= 256) return value;
        return "long-key:" + value.length() + ":" + digest;
    }

    private static Object safeNumber(Number number, CaptureBudget budget) {
        if (!isBoundedNumber(number)) {
            return Map.of("type", number.getClass().getName(), "captured", false,
                    "reason", "unsupported_number");
        }
        if (number.getClass() == BigInteger.class
                && ((BigInteger) number).bitLength() > 4096) {
            return Map.of("type", BigInteger.class.getName(), "captured", false,
                    "reason", "number_too_large");
        }
        if (number.getClass() == BigDecimal.class) {
            BigDecimal decimal = (BigDecimal) number;
            if (decimal.precision() > 4096 || Math.abs((long) decimal.scale()) > 4096) {
                return Map.of("type", BigDecimal.class.getName(), "captured", false,
                        "reason", "number_too_large");
            }
        }
        String canonical = number.toString();
        if (streamingStringDigest(canonical, budget) == null) {
            return Map.of("type", number.getClass().getName(), "captured", false,
                    "reason", "byte_or_time_budget");
        }
        return number;
    }

    private static boolean isBoundedNumber(Object value) {
        return isPrimitiveWrapperNumber(value)
                || value != null && (value.getClass() == BigInteger.class
                        || value.getClass() == BigDecimal.class);
    }

    private static boolean isPrimitiveWrapperNumber(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double;
    }

    private static boolean isBootstrapCollection(Object value) {
        if (value.getClass().getClassLoader() != null) return false;
        String name = value.getClass().getName();
        return Set.of("java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
                "java.util.EnumMap", "java.util.ArrayList", "java.util.LinkedList",
                "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
                "java.util.ArrayDeque", "java.util.Vector", "java.util.Arrays$ArrayList",
                "java.util.Collections$EmptyMap", "java.util.Collections$EmptyList",
                "java.util.Collections$EmptySet", "java.util.Collections$SingletonMap",
                "java.util.Collections$SingletonList", "java.util.Collections$SingletonSet")
                .contains(name) || name.startsWith("java.util.ImmutableCollections$");
    }

    private static String streamingStringDigest(String value, CaptureBudget budget) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        int processed = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int width = utf8Width(codePoint);
            if (!budget.claimBytes(width)) return null;
            if (width == 1) {
                digest.update((byte) codePoint);
            } else if (width == 2) {
                digest.update((byte) (0xc0 | codePoint >>> 6));
                digest.update((byte) (0x80 | codePoint & 0x3f));
            } else if (width == 3) {
                digest.update((byte) (0xe0 | codePoint >>> 12));
                digest.update((byte) (0x80 | codePoint >>> 6 & 0x3f));
                digest.update((byte) (0x80 | codePoint & 0x3f));
            } else {
                digest.update((byte) (0xf0 | codePoint >>> 18));
                digest.update((byte) (0x80 | codePoint >>> 12 & 0x3f));
                digest.update((byte) (0x80 | codePoint >>> 6 & 0x3f));
                digest.update((byte) (0x80 | codePoint & 0x3f));
            }
            offset += Character.charCount(codePoint);
            if ((++processed & 1023) == 0 && !budget.withinTime()) return null;
        }
        return hexDigest(digest);
    }

    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }

    private static boolean fullyCaptured(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (Boolean.FALSE.equals(map.get("captured"))) return false;
            return map.values().stream().allMatch(ReasonerIdentity::fullyCaptured);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().allMatch(ReasonerIdentity::fullyCaptured);
        }
        return true;
    }

    private static final class CaptureBudget {
        private int remaining = MAX_CONFIGURATION_NODES;
        private int remainingBytes = MAX_CONFIGURATION_UTF8_BYTES;
        private final long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(MAX_CONFIGURATION_CAPTURE_MILLIS);

        boolean claim() {
            return withinTime() && remaining-- > 0;
        }

        boolean claimBytes(int count) {
            if (count < 0 || !withinTime() || remainingBytes < count) return false;
            remainingBytes -= count;
            return true;
        }

        boolean withinTime() {
            return System.nanoTime() <= deadlineNanos;
        }
    }

    private static String hexDigest(MessageDigest digest) {
        StringBuilder out = new StringBuilder("sha256:");
        for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

}
