package io.github.hakjuoh.protege_mcp.tools;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The grounding-aware ranking + mint-prediction behind {@code search_entities} (F2). Given the finder's
 * (unordered) match set for a query, it produces a deterministically ranked result — each hit tagged with
 * a {@code match_kind} (exact / prefix / substring / fuzzy) and a {@code score} — and answers the
 * grounding question: <em>would using this query as a create_* name mint a brand-new entity, or does it
 * resolve to something that already exists?</em> ({@code would_mint} + {@code best_match}).
 *
 * <p>Two design constraints from the plan:
 * <ul>
 *   <li>It builds its <em>own</em> ranked list and never mutates {@link EntityJson#entityList} (16 callers
 *       depend on that shape); ties break on the IRI string so the finder's {@code Set} order can't leak
 *       run-to-run nondeterminism.</li>
 *   <li>{@code would_mint} is keyed on the <em>real</em> resolution ({@link EntityResolver#findEntity} /
 *       an exact match against <em>every</em> {@code rdfs:label} language variant + the local name), not on
 *       the case-insensitive substring finder — so a {@code @de}-only term does not read as mintable under
 *       an {@code @en} rendering config, and a full-IRI / Manchester-expression query is never flagged.</li>
 * </ul>
 *
 * <p>Pure apart from the {@link OWLModelManager} lookups (finder, rendering, active ontology), so it is
 * unit-tested headless via {@code FakeModelManager}. Must run on the EDT (the renderer/finder are EDT-only).
 */
final class EntitySearch {

    private EntitySearch() {
    }

    /** Ranked match tiers, best first. The numeric score is surfaced for clients that sort/threshold. */
    enum MatchKind {
        EXACT(100, "exact"),
        PREFIX(80, "prefix"),
        SUBSTRING(60, "substring"),
        FUZZY(40, "fuzzy");

        final int score;
        final String label;

        MatchKind(int score, String label) {
            this.score = score;
            this.label = label;
        }
    }

    /**
     * Build the enriched {@code search_entities} result over the finder's {@code matches}: {@code count},
     * a ranked capped {@code items} list (each {@code {iri, display, type, score, match_kind}}), an optional
     * {@code truncated}, plus {@code would_mint} and {@code best_match}. The caller adds {@code query}/{@code
     * type}. This front-of-list overload is retained for callers that don't page.
     */
    static Map<String, Object> enrichedSearch(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int limit) {
        return build(mm, query, matches, 0, limit, false, null);
    }

    /**
     * Paginated variant of {@link #enrichedSearch(OWLModelManager, String, Set, int)}: returns
     * {@code count} (total ranked matches), {@code offset}, {@code returned}, a windowed {@code items} list
     * and, when more remain, {@code next_offset} — plus {@code would_mint}/{@code best_match} (computed over
     * the whole match set, independent of the page). The ranking is total (score, display, IRI), so paging
     * never drops or repeats a hit across page boundaries.
     */
    static Map<String, Object> enrichedSearch(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int offset, int limit) {
        return build(mm, query, matches, offset, limit, true, null);
    }

    /**
     * Type-aware paginated variant: {@code typeFilter} is the {@code search_entities} type filter that
     * produced {@code matches} (e.g. "class", "annotation_property", "all"/null). It only constrains the
     * best_match→items reconciliation below — a query that grounds via a label the substring finder missed
     * is surfaced in {@code items}, but only when its entity type matches the filter, so a {@code class}
     * search never shows a property. Ranking and mint-prediction are unchanged.
     */
    static Map<String, Object> enrichedSearch(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int offset, int limit, String typeFilter) {
        return build(mm, query, matches, offset, limit, true, typeFilter);
    }

    private static Map<String, Object> build(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int offset, int limit, boolean paged, String typeFilter) {
        boolean textual = isTextualQuery(query);
        Map<IRI, List<String>> labels = textual ? labelIndex(mm) : Collections.emptyMap();
        String nq = normalize(query);

        List<Scored> scored = new ArrayList<>();
        for (OWLEntity e : matches) {
            MatchKind kind = textual ? classify(mm, e, nq, labels) : MatchKind.FUZZY;
            scored.add(new Scored(e, kind, mm.getRendering(e)));
        }
        // best_match grounds the query to a concrete entity via exact IRI / active rendering / any-language
        // label — a broader resolution than the case-insensitive substring finder that produced `matches`.
        // When it grounds to a term the finder missed (e.g. the spaced query "natural language definition"
        // vs the camelCase rendering), surface that term in `items` too — but only when its type matches the
        // filter — so a non-null best_match is never reported alongside an empty result set (the F2
        // inconsistency), while a class search still never lists a property.
        OWLEntity best = bestMatch(mm, query, matches, labels, textual);
        if (best != null && typeAllows(best, typeFilter)
                && scored.stream().noneMatch(s -> s.entity.getIRI().equals(best.getIRI()))) {
            scored.add(new Scored(best, MatchKind.EXACT, mm.getRendering(best)));
        }
        // Deterministic: score desc, then display asc (case-insensitive), then IRI asc as the stable
        // final tiebreak (the finder returns a Set, so without this the order drifts run-to-run).
        scored.sort(Comparator.comparingInt((Scored s) -> -s.kind.score)
                .thenComparing(s -> s.display.toLowerCase(Locale.ROOT))
                .thenComparing(s -> s.entity.getIRI().toString()));

        int total = scored.size();
        int off = Math.min(Math.max(0, offset), total);
        int max = Math.max(0, limit);
        // long arithmetic so a near-Integer.MAX_VALUE limit cannot overflow off+max into a negative end.
        int end = (int) Math.min((long) total, (long) off + max);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = off; i < end; i++) {
            Scored s = scored.get(i);
            Map<String, Object> item = new LinkedHashMap<>(Tools.entityJson(mm, s.entity));
            item.put("score", s.kind.score);
            item.put("match_kind", s.kind.label);
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", total);
        if (paged) {
            result.put("offset", off);
            result.put("returned", items.size());
        }
        result.put("items", items);
        if (paged) {
            // Only advertise a next page when this page made forward progress (guards limit<=0 / overflow).
            if (!items.isEmpty() && off + items.size() < total) {
                result.put("next_offset", off + items.size());
            }
        } else if (total > items.size()) {
            result.put("truncated", total - items.size());
        }
        result.put("would_mint", best == null && EntityResolver.asIri(query) == null
                && isPlainTerm(query));
        result.put("best_match", best == null ? null : best.getIRI().toString());
        return result;
    }

    /**
     * Whether {@code e}'s entity type satisfies the {@code search_entities} type filter. Mirrors the exact
     * spellings {@code ReadTools.search} switches on (both the underscore and no-underscore/plural forms), so
     * the injected best_match can never disagree with the type-filtered {@code matches}: an unrecognised
     * type (including {@code null}/"all") means the finder returned every type, so any best_match is allowed.
     */
    static boolean typeAllows(OWLEntity e, String typeFilter) {
        if (typeFilter == null) {
            return true;
        }
        switch (typeFilter.trim().toLowerCase(Locale.ROOT)) {
            case "class":
            case "classes":
                return e.isOWLClass();
            case "object_property":
            case "objectproperty":
                return e.isOWLObjectProperty();
            case "data_property":
            case "dataproperty":
                return e.isOWLDataProperty();
            case "annotation_property":
            case "annotationproperty":
                return e.isOWLAnnotationProperty();
            case "individual":
            case "individuals":
                return e.isOWLNamedIndividual();
            case "datatype":
            case "datatypes":
                return e.isOWLDatatype();
            default:
                return true;   // "", "all", or an unrecognised filter → search() returned all types
        }
    }

    // ------------------------------------------------------------------ classification

    /** The best match tier for {@code e} against the normalized query {@code nq}. */
    private static MatchKind classify(OWLModelManager mm, OWLEntity e, String nq,
            Map<IRI, List<String>> labels) {
        MatchKind best = MatchKind.FUZZY;
        for (String candidate : candidates(mm, e, labels)) {
            String c = normalize(candidate);
            if (c.equals(nq)) {
                return MatchKind.EXACT;
            }
            if (c.startsWith(nq) && best.score < MatchKind.PREFIX.score) {
                best = MatchKind.PREFIX;
            } else if (c.contains(nq) && best.score < MatchKind.SUBSTRING.score) {
                best = MatchKind.SUBSTRING;
            }
        }
        return best;
    }

    /** The strings an entity can be matched on: its display rendering, IRI local name, and every label. */
    private static List<String> candidates(OWLModelManager mm, OWLEntity e, Map<IRI, List<String>> labels) {
        List<String> out = new ArrayList<>();
        out.add(mm.getRendering(e));
        String shortForm = e.getIRI().getShortForm();
        if (shortForm != null) {
            out.add(shortForm);
        }
        out.addAll(labels.getOrDefault(e.getIRI(), Collections.emptyList()));
        return out;
    }

    // ------------------------------------------------------------------ mint prediction

    /**
     * The existing entity {@code query} grounds to, or {@code null} if it grounds to nothing: an exact IRI,
     * the active-rendering resolution ({@link EntityResolver#findEntity}), or an exact match against any
     * {@code rdfs:label} (any language) or local name. Only an exact ground counts — a mere substring hit
     * is not a resolution.
     */
    private static OWLEntity bestMatch(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, Map<IRI, List<String>> labels, boolean textual) {
        OWLEntity resolved = EntityResolver.findEntity(mm, query);   // exact IRI or exact active rendering
        if (resolved != null) {
            return resolved;
        }
        if (!textual) {
            return null;
        }
        String nq = normalize(query);
        // An exact label/local-name match among the finder's hits.
        for (OWLEntity e : matches) {
            if (classify(mm, e, nq, labels) == MatchKind.EXACT) {
                return e;
            }
        }
        // A label variant the active-rendering finder didn't surface (e.g. a @de-only label under @en).
        OWLEntityFinder finder = mm.getOWLEntityFinder();
        for (Map.Entry<IRI, List<String>> entry : labels.entrySet()) {
            for (String label : entry.getValue()) {
                if (normalize(label).equals(nq)) {
                    Set<OWLEntity> es = finder.getEntities(entry.getKey());
                    if (es != null && !es.isEmpty()) {
                        return es.iterator().next();
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** An index of {@code IRI → rdfs:label lexical forms} over the active ontology's imports closure. */
    private static Map<IRI, List<String>> labelIndex(OWLModelManager mm) {
        Map<IRI, List<String>> index = new LinkedHashMap<>();
        for (OWLOntology o : mm.getActiveOntology().getImportsClosure()) {
            for (OWLAnnotationAssertionAxiom ax : o.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
                if (!ax.getProperty().isLabel() || !ax.getValue().asLiteral().isPresent()
                        || !(ax.getSubject() instanceof IRI)) {
                    continue;
                }
                OWLLiteral lit = ax.getValue().asLiteral().get();
                index.computeIfAbsent((IRI) ax.getSubject(), k -> new ArrayList<>()).add(lit.getLiteral());
            }
        }
        return index;
    }

    /** A query with real content to match on (not blank, not wildcard-only). */
    static boolean isTextualQuery(String query) {
        String q = query == null ? "" : query.trim();
        return !q.isEmpty() && !q.chars().allMatch(c -> c == '*');
    }

    /**
     * A plain single term that create_* would mint an IRI from: non-blank, no whitespace, no wildcard, not
     * a full IRI, and none of the Manchester structural characters. Multi-word / expression / IRI inputs
     * are deliberately never flagged as mint candidates.
     */
    static boolean isPlainTerm(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || q.indexOf('*') >= 0 || EntityResolver.asIri(q) != null) {
            return false;
        }
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (Character.isWhitespace(c) || c == '(' || c == ')' || c == '<' || c == '>'
                    || c == '{' || c == '}' || c == '[' || c == ']') {
                return false;
            }
        }
        return true;
    }

    /** Case/whitespace/diacritic-folded form used for the exact/prefix/substring tiers. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** One ranked hit. */
    private static final class Scored {
        final OWLEntity entity;
        final MatchKind kind;
        final String display;

        Scored(OWLEntity entity, MatchKind kind, String display) {
            this.entity = entity;
            this.kind = kind;
            this.display = display == null ? "" : display;
        }
    }
}
