package io.github.hakjuoh.protege_mcp.tools;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;

/** Compatibility-preserving policy for ontology-document loads with unresolved imports. */
enum MissingImportsMode {
    WARN("warn", MissingImportHandlingStrategy.SILENT),
    ERROR("error", MissingImportHandlingStrategy.THROW_EXCEPTION),
    SILENT("silent", MissingImportHandlingStrategy.SILENT);

    private final String value;
    private final MissingImportHandlingStrategy strategy;

    MissingImportsMode(String value, MissingImportHandlingStrategy strategy) {
        this.value = value;
        this.strategy = strategy;
    }

    static MissingImportsMode parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return WARN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MissingImportsMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new ToolArgException("Invalid missing_imports '" + value
                + "'; expected warn, error, or silent.");
    }

    String value() {
        return value;
    }

    MissingImportHandlingStrategy strategy() {
        return strategy;
    }

    boolean reportsMissing() {
        return this != SILENT;
    }

    List<String> reported(List<String> missingImports) {
        return reportsMissing() ? new ArrayList<>(missingImports) : List.of();
    }
}
