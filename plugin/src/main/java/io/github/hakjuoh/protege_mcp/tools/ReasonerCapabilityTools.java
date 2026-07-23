package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityRegistry;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityReport;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerIdentity;
import io.github.hakjuoh.protege_mcp.reasoner.RuleValidationService;

/** Live adapter for exact reasoner reporting and non-executing SWRL validation. */
public final class ReasonerCapabilityTools {

    private static final ReasonerCapabilityRegistry PROFILES = new ReasonerCapabilityRegistry();

    private ReasonerCapabilityTools() {
    }

    public static void register(ToolRegistry tools, ToolContext context) {
        tools.tool("get_reasoner_capabilities", (exchange, request) -> {
            requireKeys(Tools.args(request), Set.of());
            IsolatedReasonerSpec selected = context.access().compute(
                    manager -> selected(manager.getOWLReasonerManager()));
            ReasonerIdentity identity = selected.capabilityIdentity();
            return Tools.ok(PROFILES.report(identity).toMap());
        });
        tools.tool("validate_rules", (exchange, request) -> {
            Map<String, Object> arguments = Tools.args(request);
            requireKeys(arguments, Set.of("include_imports", "offset", "limit",
                    "snapshot_fingerprint"));
            boolean includeImports = strictBoolean(arguments, "include_imports", true);
            int offset = strictInteger(arguments, "offset", 0, 0,
                    RuleValidationService.MAX_UNIQUE_RULES);
            int limit = strictInteger(arguments, "limit", RuleValidationService.MAX_PAGE,
                    1, RuleValidationService.MAX_PAGE);
            String expectedSnapshot = strictDigest(arguments, "snapshot_fingerprint");
            if (offset > 0 && expectedSnapshot == null) {
                throw new ToolArgException("rule_validation_snapshot_required",
                        "snapshot_fingerprint is required when offset is greater than zero",
                        Map.of("effects_prevented", true), false);
            }
            try {
                Captured captured = context.access().compute(manager -> {
                    IsolatedReasonerSpec selected = selected(manager.getOWLReasonerManager());
                    OWLOntology active = manager.getActiveOntology();
                    Set<OWLOntology> ontologies = includeImports
                            ? RuleValidationService.boundedImportsClosure(active) : Set.of(active);
                    return new Captured(selected,
                            RuleValidationService.snapshot(ontologies, includeImports));
                });
                ReasonerCapabilityReport report = PROFILES.report(
                        captured.selected().capabilityIdentity());
                return Tools.ok(RuleValidationService.validate(
                        RuleValidationService.capture(captured.snapshot()), report,
                        offset, limit, expectedSnapshot));
            } catch (RuleValidationService.BudgetExceededException exceeded) {
                throw new ToolArgException("rule_validation_budget_exceeded",
                        exceeded.getMessage(), Map.of("budget", exceeded.budget(),
                                "maximum", exceeded.maximum(), "observed", exceeded.observed(),
                                "effects_prevented", true), false);
            } catch (RuleValidationService.SnapshotMismatchException changed) {
                throw new ToolArgException("rule_validation_snapshot_changed",
                        changed.getMessage(), Map.of("effects_prevented", true), true);
            } catch (RuleValidationService.SnapshotRequiredException required) {
                throw new ToolArgException("rule_validation_snapshot_required",
                        required.getMessage(), Map.of("effects_prevented", true), false);
            }
        });
    }

    private static IsolatedReasonerSpec selected(
            org.protege.editor.owl.model.inference.OWLReasonerManager manager) {
        IsolatedReasonerSpec selected = IsolatedReasonerSpec.capture(manager);
        if (selected == null) {
            throw new ToolArgException("No reasoner is selected in Protege. Choose one from the "
                    + "Reasoner menu, then retry.");
        }
        return selected;
    }

    private static void requireKeys(Map<String, Object> arguments, Set<String> allowed) {
        Set<String> unknown = new java.util.TreeSet<>(arguments.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new ToolArgException("Unknown argument(s): " + String.join(", ", unknown));
        }
    }

    private static boolean strictBoolean(Map<String, Object> arguments, String key,
            boolean fallback) {
        Object value = arguments.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        throw new ToolArgException("Argument '" + key + "' must be a boolean.");
    }

    private static int strictInteger(Map<String, Object> arguments, String key,
            int fallback, int minimum, int maximum) {
        Object value = arguments.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) {
            throw new ToolArgException("Argument '" + key + "' must be an integer.");
        }
        double decimal = number.doubleValue();
        long whole = number.longValue();
        if (!Double.isFinite(decimal) || decimal != whole
                || whole < minimum || whole > maximum) {
            throw new ToolArgException("Argument '" + key + "' must be an integer between "
                    + minimum + " and " + maximum + ".");
        }
        return Math.toIntExact(whole);
    }

    private static String strictDigest(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) return null;
        if (value instanceof String digest && digest.matches("sha256:[0-9a-f]{64}")) {
            return digest;
        }
        throw new ToolArgException("Argument '" + key + "' must be a SHA-256 fingerprint.");
    }

    private record Captured(IsolatedReasonerSpec selected,
            RuleValidationService.RuleSnapshot snapshot) { }
}
