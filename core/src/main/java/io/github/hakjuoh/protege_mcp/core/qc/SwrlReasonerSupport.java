package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.Locale;
import java.util.Set;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;

/** Fail-closed advisory for reasoners known to silently ignore SWRL rules. */
public final class SwrlReasonerSupport {

    private SwrlReasonerSupport() {
    }

    public static String ignoredWarning(Set<OWLOntology> closure, String reasonerName,
            String reasonerId) {
        if (closure == null) throw new IllegalArgumentException("closure must not be null");
        int rules = closure.stream().mapToInt(
                ontology -> ontology.getAxiomCount(AxiomType.SWRL_RULE)).sum();
        return ignoredWarning(rules, reasonerName, reasonerId);
    }

    public static String ignoredWarning(int rules, String reasonerName, String reasonerId) {
        if (rules == 0 || (!knownToIgnore(reasonerName) && !knownToIgnore(reasonerId))) return null;
        return ignoredWarning(rules, reasonerName != null ? reasonerName : reasonerId);
    }

    public static String ignoredWarning(int rules, String reasonerName) {
        if (rules < 1) throw new IllegalArgumentException("rules must be positive");
        return "The ontology (with imports) contains " + rules + " SWRL rule"
                + (rules == 1 ? "" : "s") + ", but the selected reasoner (" + reasonerName
                + ") does not support SWRL and silently IGNORES rules — these results include no "
                + "rule-derived inferences. Use a rule-aware reasoner (e.g. Pellet, or HermiT for "
                + "rules without built-in atoms) when the rules matter.";
    }

    private static boolean knownToIgnore(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("elk") || normalized.contains("structural reasoner")
                || normalized.contains("structuralreasoner");
    }
}
