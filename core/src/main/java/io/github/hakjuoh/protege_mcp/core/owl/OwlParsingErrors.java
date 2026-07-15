package io.github.hakjuoh.protege_mcp.core.owl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.semanticweb.owlapi.io.OWLParser;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.io.UnparsableOntologyException;

/** Bounded, actionable rendering of OWLAPI's otherwise tens-of-kilobytes parser aggregate. */
public final class OwlParsingErrors {

    private static final int MAX_SAMPLES = 3;
    private static final int MAX_DETAIL = 240;
    private static final int MAX_DOCUMENT = 320;

    private OwlParsingErrors() {
    }

    /** Return a concise parser aggregate, or the ordinary exception message for non-parser failures. */
    public static String conciseMessage(Throwable failure) {
        UnparsableOntologyException unparsable = findUnparsable(failure);
        if (unparsable == null) {
            String message = failure == null ? null : failure.getMessage();
            return message == null || message.isBlank()
                    ? failure == null ? "Unknown error" : failure.getClass().getSimpleName()
                    : message;
        }

        // Preserve the map's insertion order: OWLAPI collects these in a LinkedHashMap in the
        // order parsers were tried, with any format/MIME-matched parser first — so the leading
        // samples are the most actionable diagnostics. That order is deterministic per document.
        List<Map.Entry<OWLParser, OWLParserException>> errors = new ArrayList<>(
                unparsable.getExceptions().entrySet());
        StringBuilder out = new StringBuilder("Could not parse ontology document '")
                .append(shorten(unparsable.getDocumentIRI().toString(), MAX_DOCUMENT))
                .append("': none of ").append(errors.size()).append(" registered parsers accepted it");
        if (!errors.isEmpty()) {
            out.append(". Sample errors: ");
            for (int i = 0; i < Math.min(errors.size(), MAX_SAMPLES); i++) {
                if (i > 0) {
                    out.append("; ");
                }
                Map.Entry<OWLParser, OWLParserException> entry = errors.get(i);
                out.append(entry.getKey().getClass().getSimpleName()).append(": ")
                        .append(shorten(oneLine(entry.getValue().getMessage()), MAX_DETAIL));
            }
        }
        return out.toString();
    }

    private static UnparsableOntologyException findUnparsable(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof UnparsableOntologyException unparsable) {
                return unparsable;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) {
            return "parser rejected the document without a diagnostic";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 1)) + "…";
    }
}
