package io.github.hakjuoh.protege_mcp.tools;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.core.qc.ShaclValidationService;

/** Tool registration and legacy exception adapter for shared offline SHACL validation. */
public final class ShaclTools {

    private ShaclTools() {
    }

    public static void register(ToolRegistry tools, ToolContext context) {
        tools.tool("shacl_validate", (executor, request) -> {
            Map<String, Object> arguments = Tools.args(request);
            String shapesText = Tools.optString(arguments, "shapes");
            String shapesPath = Tools.optString(arguments, "shapes_path");
            if (!isBlank(shapesPath)) {
                shapesPath = DirectAccessPolicy.resolve(context, executor)
                        .readPath(shapesPath).toString();
            }
            boolean includeInferred = Tools.optBool(arguments, "include_inferred", false);
            int limit = Tools.optInt(arguments, "limit", 1000);
            int timeout = Tools.optInt(arguments, "timeout_ms", 120_000);
            if (timeout <= 0) timeout = 120_000;
            if (isBlank(shapesText) && isBlank(shapesPath)) {
                return Tools.error("Provide a SHACL shapes graph: pass 'shapes' (inline Turtle) or "
                        + "'shapes_path' (a local file).");
            }
            final int snapshotTimeout = timeout;
            byte[] dataTurtle = context.access().compute(modelManager -> SparqlTools.toTurtleBytes(
                    SparqlTools.snapshot(modelManager, includeInferred).ontology()), snapshotTimeout);
            return Tools.ok(validate(dataTurtle, shapesText, shapesPath, limit, snapshotTimeout));
        });
    }

    static Map<String, Object> validate(byte[] dataTurtle, String shapesText,
            String shapesPath, int limit) {
        return validate(dataTurtle, shapesText, shapesPath, limit, 0L);
    }

    static Map<String, Object> validate(byte[] dataTurtle, String shapesText,
            String shapesPath, int limit, long timeoutMs) {
        return validate(dataTurtle, shapesText, shapesPath, limit, timeoutMs, null);
    }

    static Map<String, Object> validate(byte[] dataTurtle, String shapesText,
            String shapesPath, int limit, long timeoutMs, Collection<String> identitiesOut) {
        try {
            ShaclValidationService.Validation validation = ShaclValidationService.validate(
                    dataTurtle, shapesText, shapesPath, limit, timeoutMs);
            if (identitiesOut != null) identitiesOut.addAll(validation.gatingIdentities());
            return validation.report();
        } catch (ShaclValidationService.ValidationException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    static Map<String, Object> validate(byte[] dataTurtle, List<Path> shapesPaths,
            int limit, long timeoutMs) {
        return validate(dataTurtle, shapesPaths, limit, timeoutMs, null);
    }

    static Map<String, Object> validate(byte[] dataTurtle, List<Path> shapesPaths,
            int limit, long timeoutMs, Collection<String> identitiesOut) {
        try {
            ShaclValidationService.Validation validation = ShaclValidationService.validate(
                    dataTurtle, shapesPaths, limit, timeoutMs);
            if (identitiesOut != null) identitiesOut.addAll(validation.gatingIdentities());
            return validation.report();
        } catch (ShaclValidationService.ValidationException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
