package io.github.hakjuoh.protege_mcp.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The single reasoner-reference resolution rule shared by every surface that accepts a reasoner
 * name or id (policy {@code reasoning.reasoner}, {@code set_reasoner}, the diff tools'
 * {@code reasoner} argument, and the project-QC required-reasoner gate).
 *
 * <p>A requested reference matches an installed reasoner when it equals the display name or the
 * factory id (both case-insensitive, whitespace-trimmed), or when its whitespace-separated tokens
 * appear as a contiguous whole-token sequence inside the display name — so the version-less
 * convention {@code HermiT} matches the installed {@code HermiT 1.4.3.456}, while a versioned
 * reference must agree token-for-token ({@code HermiT 1.4} never matches {@code 1.4.3.456}).
 * Exact matches take precedence over token matches. The reference is usable only when it resolves
 * to exactly ONE installed reasoner: zero candidates and two-or-more candidates are both failures,
 * reported distinctly so an ambiguous reference is never silently picked.</p>
 */
public final class ReasonerNames {

    /** One installed reasoner: the Protégé plugin display name plus its factory id (id may be null). */
    public record Candidate(String name, String id) { }

    /**
     * The outcome of resolving one requested reference: the surviving candidates in inventory
     * order. {@link #unique()} is the only success state.
     */
    public record Resolution(String requested, List<Candidate> candidates) {
        public boolean unique() {
            return candidates.size() == 1;
        }

        /** The single resolved reasoner; only meaningful when {@link #unique()}. */
        public Candidate match() {
            return candidates.get(0);
        }

        public boolean ambiguous() {
            return candidates.size() > 1;
        }

        /** Display names of the surviving candidates, for error messages. */
        public List<String> candidateNames() {
            List<String> names = new ArrayList<>();
            for (Candidate c : candidates) {
                names.add(c.name());
            }
            return names;
        }

        /** Display labels that retain factory ids when the resolving surface has them. */
        public List<String> candidateLabels() {
            List<String> labels = new ArrayList<>();
            for (Candidate c : candidates) {
                labels.add(c.id() == null || c.id().isBlank()
                        ? c.name() : c.name() + " [" + c.id() + "]");
            }
            return labels;
        }
    }

    private ReasonerNames() {
    }

    /** Resolve against display names only (the policy loader's installed inventory). */
    public static Resolution resolveNames(String requested, Collection<String> installedNames) {
        List<Candidate> candidates = new ArrayList<>();
        if (installedNames != null) {
            for (String name : installedNames) {
                if (name != null) {
                    candidates.add(new Candidate(name, null));
                }
            }
        }
        return resolve(requested, candidates);
    }

    /** Resolve against full (name, id) candidates. */
    public static Resolution resolve(String requested, Collection<Candidate> installed) {
        String wanted = requested == null ? "" : requested.trim();
        List<Candidate> exact = new ArrayList<>();
        List<Candidate> partial = new ArrayList<>();
        for (Candidate candidate : installed) {
            if (candidate == null || candidate.name() == null) {
                continue;
            }
            if (candidate.name().trim().equalsIgnoreCase(wanted)
                    || (candidate.id() != null && candidate.id().trim().equalsIgnoreCase(wanted))) {
                exact.add(candidate);
            } else if (tokenSequenceMatch(wanted, candidate.name())) {
                partial.add(candidate);
            }
        }
        return new Resolution(wanted, exact.isEmpty() ? partial : exact);
    }

    /**
     * True when {@code requested}'s whitespace tokens appear as a contiguous, case-insensitive
     * whole-token run inside {@code candidate}. Partial tokens never match: {@code "HermiT"}
     * matches {@code "HermiT 1.4.3.456"} but {@code "Herm"} and {@code "HermiT 1.4"} do not.
     */
    static boolean tokenSequenceMatch(String requested, String candidate) {
        String[] want = requested.trim().split("\\s+");
        String[] have = candidate.trim().split("\\s+");
        if (want.length == 0 || want[0].isEmpty() || want.length > have.length) {
            return false;
        }
        for (int start = 0; start + want.length <= have.length; start++) {
            boolean all = true;
            for (int i = 0; i < want.length; i++) {
                if (!want[i].equalsIgnoreCase(have[start + i])) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }
}
