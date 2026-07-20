package io.github.hakjuoh.protege_mcp.tools;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;

import io.github.hakjuoh.protege_mcp.policy.EntitySearchPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

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
 *       an exact match against <em>every</em> language variant of the policy-configured preferred label
 *       properties (default {@code rdfs:label} + {@code skos:prefLabel}) + the local name), not on
 *       the case-insensitive substring finder — so a {@code @de}-only term does not read as mintable under
 *       an {@code @en} rendering config, and a full-IRI / Manchester-expression query is never flagged.</li>
 * </ul>
 *
 * <p>Pure apart from the {@link OWLModelManager} lookups (finder, rendering, active ontology), so it is
 * unit-tested headless via {@code FakeModelManager}. Must run on the EDT (the renderer/finder are EDT-only).
 */
final class EntitySearch {

    private static final IRI OBO_RELATED = IRI.create(
            EntitySearchPolicy.DEFAULT_SYNONYM_PROPERTIES.get(2));
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    static final int MAX_LEXICAL_CANDIDATES = 1_000;

    record Settings(Set<IRI> preferredProperties, Set<IRI> synonymProperties,
            List<String> preferredLanguages, List<String> fallbackLanguages) {
        Settings {
            preferredProperties = Set.copyOf(preferredProperties);
            synonymProperties = Set.copyOf(synonymProperties);
            if (preferredLanguages.size() > EntitySearchPolicy.MAX_LANGUAGE_PRIORITIES
                    || fallbackLanguages.size() > EntitySearchPolicy.MAX_LANGUAGE_PRIORITIES) {
                throw new IllegalArgumentException("entity-search language priority lists are limited to "
                        + EntitySearchPolicy.MAX_LANGUAGE_PRIORITIES + " entries");
            }
            preferredLanguages = normalizeLanguages(preferredLanguages);
            fallbackLanguages = normalizeLanguages(fallbackLanguages);
        }

        Settings(Set<IRI> preferredProperties, Set<IRI> synonymProperties) {
            this(preferredProperties, synonymProperties, List.of(), List.of());
        }

        static Settings defaults() {
            return new Settings(EntitySearchPolicy.DEFAULT_PREFERRED_PROPERTIES.stream()
                    .map(IRI::create).collect(java.util.stream.Collectors.toSet()),
                    EntitySearchPolicy.DEFAULT_SYNONYM_PROPERTIES.stream()
                            .map(IRI::create).collect(java.util.stream.Collectors.toSet()),
                    List.of(), List.of());
        }

        static Settings from(ProjectPolicy policy) {
            Settings defaults = defaults();
            if (policy == null || policy.digest() == null || policy.issues().stream().anyMatch(issue ->
                    "error".equals(issue.severity()) && issue.path() != null
                            && issue.path().startsWith("entity_search"))) return defaults;
            Map<String, Object> block = object(policy.effective().get("entity_search"));
            if (block.isEmpty()) return defaults;
            Map<String, Object> prefixes = object(policy.effective().get("prefixes"));
            Set<IRI> preferred = block.containsKey("preferred_properties")
                    ? propertyIris(block.get("preferred_properties"), prefixes)
                    : defaults.preferredProperties;
            Set<IRI> synonyms = block.containsKey("synonym_properties")
                    ? propertyIris(block.get("synonym_properties"), prefixes)
                    : defaults.synonymProperties;
            List<String> preferredLanguages = block.containsKey("preferred_languages")
                    ? strings(block.get("preferred_languages")) : defaults.preferredLanguages;
            List<String> fallbackLanguages = block.containsKey("fallback_languages")
                    ? strings(block.get("fallback_languages")) : defaults.fallbackLanguages;
            return new Settings(preferred, synonyms, preferredLanguages, fallbackLanguages);
        }

        int languageAdjustment(String language) {
            if (preferredLanguages.isEmpty() && fallbackLanguages.isEmpty()) return 0;
            String normalized = language == null || language.isEmpty()
                    ? "und" : language.toLowerCase(Locale.ROOT);
            int preferred = languageIndex(preferredLanguages, normalized);
            if (preferred >= 0) return 9 - preferred;
            int fallback = languageIndex(fallbackLanguages, normalized);
            if (fallback >= 0) return 4 - fallback;
            return -9;
        }

        Map<String, Object> description() {
            return Map.of("preferred_properties", iriStrings(preferredProperties),
                    "synonym_properties", iriStrings(synonymProperties),
                    "preferred_languages", preferredLanguages,
                    "fallback_languages", fallbackLanguages);
        }

        private static int languageIndex(List<String> priorities, String language) {
            for (int i = 0; i < priorities.size(); i++) {
                String range = priorities.get(i);
                if (language.equals(range) || language.startsWith(range + "-")) return i;
            }
            return -1;
        }

        private static List<String> normalizeLanguages(List<String> values) {
            return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        }

        private static List<String> iriStrings(Set<IRI> values) {
            return values.stream().map(IRI::toString).sorted().toList();
        }

        private static Set<IRI> propertyIris(Object value, Map<String, Object> prefixes) {
            Set<IRI> out = new LinkedHashSet<>();
            for (String ref : strings(value)) {
                int colon = ref.indexOf(':');
                String prefix = colon > 0 ? ref.substring(0, colon) : "";
                boolean absolute = Set.of("http", "https", "urn", "file")
                        .contains(prefix.toLowerCase(Locale.ROOT));
                Object base = absolute ? null : prefixes.get(prefix);
                out.add(IRI.create(base instanceof String
                        ? base + ref.substring(colon + 1) : ref));
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> object(Object value) {
            return value instanceof Map ? (Map<String, Object>) value : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static List<String> strings(Object value) {
            return value instanceof List ? (List<String>) value : List.of();
        }
    }

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
        return enrichedSearch(mm, query, matches, offset, limit, null);
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
        return enrichedSearch(mm, query, matches, offset, limit, typeFilter, Settings.defaults());
    }

    static Map<String, Object> enrichedSearch(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int offset, int limit, String typeFilter,
            Settings settings) {
        return build(mm, query, matches, offset, limit, true, typeFilter, settings, null, -1L);
    }

    static Map<String, Object> enrichedSearch(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int offset, int limit, String typeFilter,
            Settings settings, Cache cache, long revision) {
        return build(mm, query, matches, offset, limit, true, typeFilter, settings, cache, revision);
    }

    private static Map<String, Object> build(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int offset, int limit, boolean paged, String typeFilter) {
        return build(mm, query, matches, offset, limit, paged, typeFilter, Settings.defaults());
    }

    private static Map<String, Object> build(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int offset, int limit, boolean paged, String typeFilter,
            Settings settings) {
        return build(mm, query, matches, offset, limit, paged, typeFilter, settings, null, -1L);
    }

    private static Map<String, Object> build(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, int offset, int limit, boolean paged, String typeFilter,
            Settings settings, Cache cache, long revision) {
        boolean textual = isTextualQuery(query);
        LexicalIndex lexical = textual
                ? cache == null ? lexicalIndex(mm, settings) : cache.get(mm, revision, settings)
                : LexicalIndex.empty();
        Map<IRI, List<String>> labels = lexical.preferredValues();
        String nq = normalize(query);

        List<Scored> scored = new ArrayList<>();
        Set<OWLEntity> candidates = new LinkedHashSet<>(matches);
        LexicalMatches lexicalMatches = LexicalMatches.empty();
        if (textual && query.indexOf('*') < 0) {
            lexicalMatches = lexical.matchingEntities(nq, typeFilter, MAX_LEXICAL_CANDIDATES);
            lexicalMatches.entities.forEach(candidates::add);
        }
        for (OWLEntity e : candidates) {
            String display = mm.getRendering(e);
            Evidence evidence = textual ? evidence(e, display, query, nq, lexical, settings)
                    : Evidence.fuzzy(display);
            scored.add(new Scored(e, evidence, display));
        }
        // best_match grounds the query to a concrete entity via exact IRI / active rendering / any-language
        // label — a broader resolution than the case-insensitive substring finder that produced `matches`.
        // When it grounds to a term the finder missed (e.g. the spaced query "natural language definition"
        // vs the camelCase rendering), surface that term in `items` too — but only when its type matches the
        // filter. The grounding remains whole-workspace context even when the requested type differs, so
        // a class search never lists a property but may still report that property's IRI as best_match.
        // Keep the actual create/reference resolver result authoritative. Lexical collisions are
        // review warnings; a synonym must never turn a real renderer/IRI ground into false minting.
        final Grounding grounding = bestMatch(mm, query, matches, labels, textual);
        final OWLEntity best = grounding.entity;
        if (best != null && typeAllows(best, typeFilter)
                && scored.stream().noneMatch(s -> s.entity.getIRI().equals(best.getIRI()))) {
            String display = mm.getRendering(best);
            scored.add(new Scored(best, evidence(best, display, query, nq, lexical, settings), display));
        }
        // Deterministic: score desc, then display asc (case-insensitive), then IRI asc as the stable
        // final tiebreak (the finder returns a Set, so without this the order drifts run-to-run).
        scored.sort(Comparator.comparingInt((Scored s) -> -s.evidence.score)
                .thenComparing(s -> s.display.toLowerCase(Locale.ROOT))
                .thenComparing(s -> s.entity.getIRI().toString())
                .thenComparing(s -> s.entity.getEntityType().getName()));

        int total = scored.size();
        int off = Math.min(Math.max(0, offset), total);
        int max = Math.max(0, limit);
        // long arithmetic so a near-Integer.MAX_VALUE limit cannot overflow off+max into a negative end.
        int end = (int) Math.min((long) total, (long) off + max);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = off; i < end; i++) {
            Scored s = scored.get(i);
            Map<String, Object> item = new LinkedHashMap<>(Tools.entityJson(mm, s.entity));
            item.put("score", s.evidence.score);
            item.put("match_kind", s.evidence.kind.label);
            item.put("match_source", s.evidence.source);
            item.put("matched_value", s.evidence.value);
            if (s.evidence.property != null) item.put("matched_property", s.evidence.property.toString());
            item.put("language", s.evidence.language.isEmpty() ? null : s.evidence.language);
            boolean iriSource = "iri".equals(s.evidence.source);
            item.put("normalization", Map.of("query", iriSource
                            ? query == null ? "" : query.trim() : nq,
                    "candidate", iriSource ? s.evidence.value : normalize(s.evidence.value),
                    "case_folded", !iriSource, "whitespace_collapsed", !iriSource,
                    "diacritics_folded", !iriSource));
            item.put("score_explanation", Map.of("tier", s.evidence.kind.label,
                    "tier_score", s.evidence.kind.score, "total", s.evidence.score,
                    "source_adjustment", s.evidence.sourceAdjustment,
                    "language_adjustment", s.evidence.languageAdjustment,
                    "total_adjustment", s.evidence.sourceAdjustment
                            + s.evidence.languageAdjustment,
                    "unclamped_total", s.evidence.kind.score + s.evidence.sourceAdjustment
                            + s.evidence.languageAdjustment,
                    "score_floor", MatchKind.FUZZY.score,
                    "score_floor_applied", s.evidence.score
                            != s.evidence.kind.score + s.evidence.sourceAdjustment
                                    + s.evidence.languageAdjustment,
                    "source", s.evidence.source));
            List<String> collisionIris = lexical.collisionIris(normalize(s.evidence.value));
            item.put("collision", collisionIris.size() > 1);
            if (collisionIris.size() > 1) item.put("collision_iris", collisionIris);
            item.put("needs_review", s.evidence.source.contains("synonym")
                    || s.evidence.kind == MatchKind.FUZZY || collisionIris.size() > 1);
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
        if (lexicalMatches.truncated() > 0) {
            result.put("lexical_match_count", lexicalMatches.total);
            result.put("lexical_candidates_truncated", lexicalMatches.truncated());
        }
        result.put("would_mint", textual && best == null && !grounding.exactCollision
                && EntityResolver.asIri(query) == null
                && isPlainTerm(query));
        result.put("best_match", best == null ? null : best.getIRI().toString());
        result.put("mint_blocked_by_collision", grounding.exactCollision);
        result.put("lexical_policy", settings.description());
        result.put("collisions", lexical.collisionsFor(nq));
        Scored review = scored.stream().filter(s -> best == null
                && (s.evidence.source.contains("synonym") || s.evidence.kind != MatchKind.EXACT
                || lexical.collisionIris(normalize(s.evidence.value)).size() > 1))
                .findFirst().orElse(null);
        result.put("reuse_candidate", review == null ? null : Map.of(
                "iri", review.entity.getIRI().toString(),
                "score", review.evidence.score,
                "match_source", review.evidence.source,
                "review_required", true,
                "reason", review.evidence.source.contains("synonym")
                        ? "synonym_match" : "approximate_or_ambiguous_match"));
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

    private static Evidence evidence(OWLEntity entity, String display, String query, String nq,
            LexicalIndex lexical, Settings settings) {
        List<Evidence> candidates = new ArrayList<>();
        candidates.add(Evidence.of(display, "renderer", null, "", 0, query, nq));
        candidates.add(Evidence.of(entity.getIRI().toString(), "iri", null, "", 0, query, nq));
        candidates.add(Evidence.of(entity.getIRI().getShortForm(), "local_name", null, "", 0,
                query, nq));
        for (LexicalForm form : lexical.forms.getOrDefault(entity.getIRI(), List.of())) {
            String source = form.property.equals(OBO_RELATED) ? "related_synonym"
                    : form.synonym ? "synonym" : "preferred_label";
            candidates.add(Evidence.of(form.value, source, form.property, form.language,
                    settings.languageAdjustment(form.language), query, nq));
        }
        return candidates.stream().min(Comparator
                .comparingInt((Evidence e) -> e.score).reversed()
                .thenComparing(Comparator.comparingInt(
                        (Evidence e) -> sourcePriority(e.source)).reversed())
                .thenComparing(e -> e.language)
                .thenComparing(e -> e.value)
                .thenComparing(e -> e.property == null ? "" : e.property.toString()))
                .orElse(Evidence.fuzzy(display));
    }

    private static int sourcePriority(String source) {
        return switch (source) {
            case "iri" -> 6;
            case "preferred_label" -> 5;
            case "local_name" -> 4;
            case "renderer" -> 3;
            case "synonym" -> 2;
            default -> 1;
        };
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
     * value of the configured preferred label properties (default {@code rdfs:label} +
     * {@code skos:prefLabel}, any language) or the local name. Only an exact ground counts — a mere
     * substring hit is not a resolution.
     */
    private static Grounding bestMatch(OWLModelManager mm, String query,
            Set<? extends OWLEntity> matches, Map<IRI, List<String>> labels, boolean textual) {
        OWLEntity resolved = EntityResolver.findEntity(mm, query);   // exact IRI or exact active rendering
        if (resolved != null) {
            return new Grounding(resolved, false);
        }
        if (!textual) {
            return Grounding.none();
        }
        String nq = normalize(query);
        // An exact label/local-name match among the finder's hits.
        Map<IRI, List<OWLEntity>> exact = new LinkedHashMap<>();
        for (OWLEntity e : matches) {
            if (classify(mm, e, nq, labels) == MatchKind.EXACT) {
                exact.computeIfAbsent(e.getIRI(), ignored -> new ArrayList<>()).add(e);
            }
        }
        // A label variant the active-rendering finder didn't surface (e.g. a @de-only label under @en).
        OWLEntityFinder finder = mm.getOWLEntityFinder();
        for (Map.Entry<IRI, List<String>> entry : labels.entrySet()) {
            for (String label : entry.getValue()) {
                if (normalize(label).equals(nq)) {
                    Set<OWLEntity> es = finder.getEntities(entry.getKey());
                    if (es != null && !es.isEmpty()) {
                        exact.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(es);
                    }
                }
            }
        }
        if (exact.size() > 1) {
            return new Grounding(null, true);
        }
        if (exact.isEmpty()) {
            return Grounding.none();
        }
        OWLEntity representative = exact.values().iterator().next().stream()
                .min(Comparator.comparing(e -> e.getEntityType().getName())).orElse(null);
        return new Grounding(representative, false);
    }

    // ------------------------------------------------------------------ helpers

    /** Configured lexical forms plus deterministic entity/collision indexes over the imports closure. */
    private static LexicalIndex lexicalIndex(OWLModelManager mm, Settings settings) {
        Map<IRI, List<LexicalForm>> forms = new LinkedHashMap<>();
        Map<IRI, Set<OWLEntity>> entities = new LinkedHashMap<>();
        for (OWLOntology o : mm.getActiveOntology().getImportsClosure()) {
            for (OWLEntity entity : o.getSignature()) {
                entities.computeIfAbsent(entity.getIRI(), ignored -> new LinkedHashSet<>()).add(entity);
            }
            for (OWLAnnotationAssertionAxiom ax : o.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
                IRI property = ax.getProperty().getIRI();
                boolean synonym = settings.synonymProperties.contains(property);
                if ((!synonym && !settings.preferredProperties.contains(property))
                        || !ax.getValue().asLiteral().isPresent() || !(ax.getSubject() instanceof IRI)) {
                    continue;
                }
                OWLLiteral lit = ax.getValue().asLiteral().get();
                forms.computeIfAbsent((IRI) ax.getSubject(), k -> new ArrayList<>())
                        .add(new LexicalForm(lit.getLiteral(), property,
                                lit.getLang() == null ? "" : lit.getLang().toLowerCase(Locale.ROOT),
                                synonym, normalize(lit.getLiteral())));
            }
        }
        return new LexicalIndex(forms, entities, settings);
    }

    /** A query with real content to match on (not blank, not wildcard-only). */
    static boolean isTextualQuery(String query) {
        String q = query == null ? "" : query.trim();
        return !q.isEmpty() && !q.chars().allMatch(c -> c == '*') && !normalize(q).isEmpty();
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
        String t = COMBINING_MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD))
                .replaceAll("");
        return WHITESPACE.matcher(t.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    /** One ranked hit. */
    private static final class Scored {
        final OWLEntity entity;
        final Evidence evidence;
        final String display;

        Scored(OWLEntity entity, Evidence evidence, String display) {
            this.entity = entity;
            this.evidence = evidence;
            this.display = display == null ? "" : display;
        }
    }

    private record LexicalForm(String value, IRI property, String language, boolean synonym,
            String normalized) { }

    private record Evidence(MatchKind kind, int score, int sourceAdjustment, int languageAdjustment,
            String source, String value, IRI property, String language) {
        static Evidence of(String value, String source, IRI property, String language,
                int languageAdjustment, String query, String nq) {
            String candidate = value == null ? "" : value;
            MatchKind kind;
            if ("iri".equals(source)) {
                String raw = query == null ? "" : query.trim();
                kind = candidate.equals(raw) ? MatchKind.EXACT
                        : (!raw.isEmpty() && candidate.contains(raw)) ? MatchKind.SUBSTRING
                        : MatchKind.FUZZY;
            } else {
                String normalized = normalize(candidate);
                kind = normalized.equals(nq) ? MatchKind.EXACT
                        : normalized.startsWith(nq) ? MatchKind.PREFIX
                        : normalized.contains(nq) ? MatchKind.SUBSTRING : MatchKind.FUZZY;
            }
            int adjustment = "related_synonym".equals(source) ? -30 : 0;
            int effectiveLanguageAdjustment = kind == MatchKind.FUZZY ? 0 : languageAdjustment;
            int score = kind.score + adjustment + effectiveLanguageAdjustment;
            return new Evidence(kind, Math.max(MatchKind.FUZZY.score, score), adjustment,
                    effectiveLanguageAdjustment, source, candidate, property,
                    language == null ? "" : language);
        }

        static Evidence fuzzy(String value) {
            return new Evidence(MatchKind.FUZZY, MatchKind.FUZZY.score, 0, 0, "fuzzy",
                    value == null ? "" : value, null, "");
        }
    }

    private record Grounding(OWLEntity entity, boolean exactCollision) {
        static Grounding none() {
            return new Grounding(null, false);
        }
    }

    private record LexicalCandidate(OWLEntity entity, int score) { }

    private record LexicalMatches(List<OWLEntity> entities, int total) {
        static LexicalMatches empty() {
            return new LexicalMatches(List.of(), 0);
        }

        int truncated() {
            return total - entities.size();
        }
    }

    private static final class LexicalIndex {
        final Map<IRI, List<LexicalForm>> forms;
        final Map<IRI, Set<OWLEntity>> entities;
        final Settings settings;
        final Map<IRI, List<String>> preferredValues = new LinkedHashMap<>();
        final Map<String, Set<String>> collisions = new LinkedHashMap<>();

        LexicalIndex(Map<IRI, List<LexicalForm>> forms, Map<IRI, Set<OWLEntity>> entities,
                Settings settings) {
            this.forms = forms;
            this.entities = entities;
            this.settings = settings;
            entities.forEach((iri, ignored) -> addCollision(iri.getShortForm(), iri));
            forms.forEach((iri, values) -> values.forEach(form -> {
                addCollision(form.normalized, iri, true);
                if (!form.synonym) {
                    preferredValues.computeIfAbsent(iri, ignored -> new ArrayList<>()).add(form.value);
                }
            }));
        }

        static LexicalIndex empty() {
            return new LexicalIndex(Map.of(), Map.of(), Settings.defaults());
        }

        Map<IRI, List<String>> preferredValues() {
            return preferredValues;
        }

        LexicalMatches matchingEntities(String normalizedQuery, String typeFilter, int limit) {
            Comparator<LexicalCandidate> bestFirst = Comparator
                    .comparingInt(LexicalCandidate::score).reversed()
                    .thenComparing(candidate -> candidate.entity.getIRI().toString())
                    .thenComparing(candidate -> candidate.entity.getEntityType().getName());
            TreeSet<LexicalCandidate> kept = new TreeSet<>(bestFirst);
            int[] total = {0};
            forms.forEach((iri, values) -> {
                int lexicalScore = values.stream()
                        .filter(form -> form.normalized.contains(normalizedQuery))
                        .mapToInt(form -> {
                            MatchKind kind = form.normalized.equals(normalizedQuery) ? MatchKind.EXACT
                                    : form.normalized.startsWith(normalizedQuery) ? MatchKind.PREFIX
                                    : MatchKind.SUBSTRING;
                            return Math.max(MatchKind.FUZZY.score, kind.score
                                    + (form.property.equals(OBO_RELATED) ? -30 : 0)
                                    + settings.languageAdjustment(form.language));
                        }).max().orElse(Integer.MIN_VALUE);
                if (lexicalScore != Integer.MIN_VALUE) {
                    entities.getOrDefault(iri, Set.of()).stream().filter(entity -> typeAllows(entity,
                            typeFilter)).forEach(entity -> {
                                total[0]++;
                                kept.add(new LexicalCandidate(entity, lexicalScore));
                                if (kept.size() > limit) {
                                    kept.pollLast();
                                }
                            });
                }
            });
            return new LexicalMatches(kept.stream().map(LexicalCandidate::entity).toList(), total[0]);
        }

        List<String> collisionIris(String normalized) {
            return collisions.getOrDefault(normalized, Set.of()).stream().sorted().toList();
        }

        List<Map<String, Object>> collisionsFor(String normalizedQuery) {
            List<Map<String, Object>> out = new ArrayList<>();
            collisions.entrySet().stream()
                    .filter(entry -> entry.getKey().equals(normalizedQuery) && entry.getValue().size() > 1)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.add(Map.of("normalized", entry.getKey(),
                            "iris", entry.getValue().stream().sorted().toList(),
                            "review_required", true)));
            return out;
        }

        private void addCollision(String value, IRI iri) {
            addCollision(value, iri, false);
        }

        private void addCollision(String value, IRI iri, boolean alreadyNormalized) {
            String normalized = alreadyNormalized ? value : normalize(value);
            if (!normalized.isEmpty()) {
                collisions.computeIfAbsent(normalized, ignored -> new LinkedHashSet<>())
                        .add(iri.toString());
            }
        }
    }

    /** Revision-keyed lexical index cache. All access occurs on the model thread. */
    static final class Cache {
        private OWLModelManager modelManager;
        private OWLOntology activeOntology;
        private long revision = Long.MIN_VALUE;
        private Settings settings;
        private LexicalIndex index;
        private int builds;

        LexicalIndex get(OWLModelManager mm, long currentRevision, Settings currentSettings) {
            OWLOntology active = mm.getActiveOntology();
            if (index == null || modelManager != mm || activeOntology != active
                    || revision != currentRevision || !currentSettings.equals(settings)) {
                index = lexicalIndex(mm, currentSettings);
                modelManager = mm;
                activeOntology = active;
                revision = currentRevision;
                settings = currentSettings;
                builds++;
            }
            return index;
        }

        void clear() {
            modelManager = null;
            activeOntology = null;
            revision = Long.MIN_VALUE;
            settings = null;
            index = null;
        }

        int builds() {
            return builds;
        }
    }
}
