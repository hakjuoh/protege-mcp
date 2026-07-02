package io.github.hakjuoh.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Free JSON-schema fragment builders for hand-assembled tool input schemas. Split out of {@link Tools}
 * as a focused unit; the fluent {@code Tools.schema()} builder and its {@code Tools.SchemaBuilder} type
 * stay nested in {@link Tools} (the type is referenced across tests). {@code Tools} keeps delegators.
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    public static Map<String, Object> emptySchema() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("additionalProperties", false);
        return m;
    }

    /** A bare {@code {type:string, description}} property map (for hand-assembled schemas). */
    public static Map<String, Object> stringProperty(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        if (desc != null) {
            p.put("description", desc);
        }
        return p;
    }

    /** A bare {@code {type:boolean, description}} property map (for hand-assembled schemas). */
    public static Map<String, Object> boolProperty(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "boolean");
        if (desc != null) {
            p.put("description", desc);
        }
        return p;
    }

    /** A bare {@code {type:integer, description}} property map (for hand-assembled schemas). */
    public static Map<String, Object> integerProperty(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        if (desc != null) {
            p.put("description", desc);
        }
        return p;
    }
}
