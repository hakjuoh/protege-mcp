package io.github.hakjuoh.protege_mcp.reasoner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable capability report for one exact reasoner identity. */
public final class ReasonerCapabilityReport {

    public static final String VOCABULARY_VERSION = "reasoner-capabilities-0.8.0";
    public static final List<String> OWL_CAPABILITY_IDS = List.of(
            "owl2_dl", "owl2_el", "owl2_ql", "owl2_rl", "class_hierarchy",
            "equivalent_classes", "class_assertions", "object_property_hierarchy",
            "data_property_hierarchy", "object_property_assertions",
            "data_property_assertions", "property_chains", "inverse_object_properties",
            "nominals", "cardinality_restrictions", "qualified_cardinality_restrictions",
            "datatypes", "consistency", "satisfiability", "incremental_reasoning",
            "native_explanations", "black_box_explanations", "bounded_cancellation");
    public static final List<String> RULE_CAPABILITY_IDS = List.of(
            "swrl_rules", "dl_safe_rules", "swrl_builtins");
    public static final List<String> ATOM_CAPABILITY_IDS = List.of(
            "class", "object_property", "data_property", "data_range",
            "same_individual", "different_individuals", "built_in");
    private static final String SWRLB = "http://www.w3.org/2003/11/swrlb#";
    public static final List<String> PURE_BUILTIN_IRIS = List.of(
            "abs", "add", "booleanNot", "ceiling", "contains", "containsIgnoreCase",
            "divide", "endsWith", "equal", "floor", "greaterThan", "greaterThanOrEqual",
            "integerDivide", "lessThan", "lessThanOrEqual", "lowerCase", "matches", "mod",
            "multiply", "normalizeSpace", "notEqual", "pow", "round", "roundHalfToEven",
            "startsWith", "stringConcat", "stringEqualIgnoreCase", "stringLength", "substring",
            "subtract", "unaryMinus", "unaryPlus", "upperCase").stream()
                    .map(local -> SWRLB + local).toList();
    private static final Set<String> PURE_BUILTINS = Set.copyOf(PURE_BUILTIN_IRIS);

    /** One closed-vocabulary capability with concise reviewed evidence. */
    public record Entry(String id, CapabilityStatus status, String evidence) {
        public Entry {
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("invalid capability id");
            }
            Objects.requireNonNull(status, "status");
            if (evidence == null || evidence.isBlank() || evidence.length() > 1024) {
                throw new IllegalArgumentException("capability evidence must be bounded");
            }
        }

        Map<String, Object> toMap() {
            return Map.of("id", id, "status", status.value(), "evidence", evidence);
        }
    }

    private final ReasonerIdentity identity;
    private final String profileStatus;
    private final Map<String, Entry> owlCapabilities;
    private final Map<String, Entry> ruleCapabilities;
    private final Map<String, Entry> atomCapabilities;
    private final List<String> knownIncompatibilities;
    private final String capabilityDigest;

    ReasonerCapabilityReport(ReasonerIdentity identity, String profileStatus,
            Map<String, Entry> owlCapabilities, Map<String, Entry> ruleCapabilities,
            Map<String, Entry> atomCapabilities, List<String> knownIncompatibilities) {
        this.identity = Objects.requireNonNull(identity, "identity");
        if (!List.of("reviewed", "unknown").contains(profileStatus)) {
            throw new IllegalArgumentException("invalid profile status");
        }
        this.profileStatus = profileStatus;
        this.owlCapabilities = immutable(owlCapabilities, OWL_CAPABILITY_IDS,
                "owl capabilities");
        this.ruleCapabilities = immutable(ruleCapabilities, RULE_CAPABILITY_IDS,
                "rule capabilities");
        this.atomCapabilities = immutable(atomCapabilities, ATOM_CAPABILITY_IDS,
                "atom capabilities");
        this.knownIncompatibilities = List.copyOf(knownIncompatibilities);
        this.capabilityDigest = computeCapabilityDigest();
    }

    public ReasonerIdentity identity() {
        return identity;
    }

    public String profileStatus() {
        return profileStatus;
    }

    /** Binds pagination to the complete reviewed capability vocabulary and evidence. */
    public String capabilityDigest() {
        return capabilityDigest;
    }

    public CapabilityStatus owlStatus(String id) {
        Entry entry = owlCapabilities.get(id);
        return entry == null ? CapabilityStatus.UNKNOWN : entry.status();
    }

    public CapabilityStatus ruleStatus(String id) {
        Entry entry = ruleCapabilities.get(id);
        return entry == null ? CapabilityStatus.UNKNOWN : entry.status();
    }

    public CapabilityStatus atomStatus(String id) {
        Entry entry = atomCapabilities.get(id);
        return entry == null ? CapabilityStatus.UNKNOWN : entry.status();
    }

    /** Custom built-ins are fail-closed even when the reasoner profile itself is unknown. */
    public CapabilityStatus builtinStatus(String predicateIri) {
        if (predicateIri == null || !PURE_BUILTINS.contains(predicateIri)) {
            return CapabilityStatus.UNSUPPORTED;
        }
        return ruleStatus("swrl_builtins");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vocabulary_version", VOCABULARY_VERSION);
        out.put("profile_status", profileStatus);
        out.put("exact_profile_match", "reviewed".equals(profileStatus));
        out.put("capability_digest", capabilityDigest);
        out.put("identity", identity.toMap());
        out.put("owl_capabilities", maps(owlCapabilities));
        out.put("rule_capabilities", maps(ruleCapabilities));
        out.put("swrl_atom_capabilities", maps(atomCapabilities));
        out.put("swrl_builtin_capabilities", builtinMaps());
        out.put("known_incompatibilities", knownIncompatibilities);
        out.put("absence_means_supported", false);
        return Collections.unmodifiableMap(out);
    }

    private String computeCapabilityDigest() {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        add(digest, VOCABULARY_VERSION);
        add(digest, profileStatus);
        add(digest, identity.profileKey());
        addEntries(digest, owlCapabilities);
        addEntries(digest, ruleCapabilities);
        addEntries(digest, atomCapabilities);
        CapabilityStatus builtinStatus = ruleStatus("swrl_builtins");
        String builtinEvidence = builtinEvidence(builtinStatus);
        PURE_BUILTIN_IRIS.forEach(value -> {
            add(digest, value);
            add(digest, builtinStatus.value());
            add(digest, builtinEvidence);
        });
        knownIncompatibilities.forEach(value -> add(digest, value));
        StringBuilder out = new StringBuilder("sha256:");
        for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static void addEntries(MessageDigest digest, Map<String, Entry> entries) {
        entries.values().forEach(entry -> {
            add(digest, entry.id());
            add(digest, entry.status().value());
            add(digest, entry.evidence());
        });
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static Map<String, Entry> immutable(Map<String, Entry> source,
            List<String> expectedIds, String label) {
        Objects.requireNonNull(source, label);
        if (!source.keySet().equals(Set.copyOf(expectedIds))) {
            throw new IllegalArgumentException(label + " must contain the closed vocabulary exactly");
        }
        Map<String, Entry> copy = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue() == null
                            || !entry.getKey().equals(entry.getValue().id())) {
                        throw new IllegalArgumentException(label + " row id must match its key");
                    }
                    copy.put(entry.getKey(), entry.getValue());
                });
        return Collections.unmodifiableMap(copy);
    }

    private static List<Map<String, Object>> maps(Map<String, Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        entries.values().forEach(entry -> out.add(entry.toMap()));
        return List.copyOf(out);
    }

    private List<Map<String, Object>> builtinMaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        CapabilityStatus status = ruleStatus("swrl_builtins");
        String evidence = builtinEvidence(status);
        for (String iri : PURE_BUILTIN_IRIS) {
            out.add(Map.of("iri", iri, "status", status.value(), "evidence", evidence));
        }
        return List.copyOf(out);
    }

    private static String builtinEvidence(CapabilityStatus status) {
        return status == CapabilityStatus.UNSUPPORTED
                ? "The reviewed reasoner profile does not execute this pure SWRLB built-in."
                : "This side-effect-free SWRLB predicate is on the closed 0.8 allowlist; "
                        + "the exact reasoner profile determines execution support.";
    }
}
