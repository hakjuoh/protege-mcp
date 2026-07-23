package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.List;

/** Closed 0.8.0 inference categories and the capabilities required to enumerate them. */
public enum MaterializationCategory {
    SUBCLASS_AXIOMS("subclass_axioms", List.of("class_hierarchy")),
    EQUIVALENT_CLASS_AXIOMS("equivalent_class_axioms", List.of("equivalent_classes")),
    CLASS_ASSERTIONS("class_assertions", List.of("class_assertions")),
    PROPERTY_HIERARCHY_AXIOMS("property_hierarchy_axioms",
            List.of("object_property_hierarchy", "data_property_hierarchy")),
    OBJECT_PROPERTY_ASSERTIONS("object_property_assertions",
            List.of("object_property_assertions")),
    DATA_PROPERTY_ASSERTIONS("data_property_assertions",
            List.of("data_property_assertions"));

    private final String value;
    private final List<String> capabilityIds;

    MaterializationCategory(String value, List<String> capabilityIds) {
        this.value = value;
        this.capabilityIds = capabilityIds;
    }

    public String value() {
        return value;
    }

    public List<String> capabilityIds() {
        return capabilityIds;
    }

    public static MaterializationCategory fromValue(String value) {
        for (MaterializationCategory category : values()) {
            if (category.value.equals(value)) return category;
        }
        throw new IllegalArgumentException("unknown materialization category: " + value);
    }
}
