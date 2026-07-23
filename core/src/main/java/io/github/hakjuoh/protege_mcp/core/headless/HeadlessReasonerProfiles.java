package io.github.hakjuoh.protege_mcp.core.headless;

import java.util.Map;

import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityRegistry;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityReport;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerIdentity;

/** Builds the exact policy-governed reasoner profile used by headless operations. */
public final class HeadlessReasonerProfiles {
    private static final ReasonerCapabilityRegistry PROFILES =
            new ReasonerCapabilityRegistry();

    private HeadlessReasonerProfiles() {
    }

    public static ReasonerCapabilityReport report(OWLReasonerFactory factory,
            ProjectPolicy policy) {
        String name;
        try {
            name = factory.getReasonerName();
        } catch (RuntimeException failure) {
            name = factory.getClass().getSimpleName();
        }
        ReasonerIdentity identity = ReasonerIdentity.capture(
                factory.getClass().getName(), name, factory,
                new SimpleConfiguration(timeoutMillis(policy)), BufferingMode.BUFFERING,
                "headless_policy_reasoning_configuration");
        return PROFILES.report(identity);
    }

    public static long timeoutMillis(ProjectPolicy policy) {
        Object reasoning = policy.effective().get("reasoning");
        Object value = reasoning instanceof Map<?, ?> map ? map.get("timeout_ms") : null;
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        return 120_000L;
    }
}
