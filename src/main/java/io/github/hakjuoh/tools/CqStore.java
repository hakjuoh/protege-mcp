package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The storage SPI for competency questions (F3). CQs may already live under different conventions and
 * there is no single industry-standard file format, so {@code list} detects the convention(s),
 * {@code add}/{@code remove} operate in a chosen one, and {@code run} is convention-agnostic (it sees only
 * the normalised {@link CompetencyQuestion} model). The 0.4.0 providers are a fixed in-package list
 * ({@link CqStores}), not a {@code ServiceLoader} — the same OSGi/TCCL fragility that makes SPARQL reject
 * SERVICE argues against classpath discovery here.
 *
 * <p>Malformed input is <em>isolated</em>: {@link #load} returns a {@link LoadResult} whose {@code skipped}
 * channel records each unreadable entry with a reason, and never aborts the batch. Validation errors on
 * {@code upsert} are surfaced as {@link ToolArgException} (validate-at-write).
 */
interface CqStore {

    /** A stable identifier for this convention (echoed on every mutating call). */
    String conventionId();

    /** Whether this convention can write in {@code ctx} (a file convention needs a document folder). */
    boolean isWritable(CqContext ctx);

    /** Whether CQs stored in this convention are present in {@code ctx}. */
    boolean detect(CqContext ctx);

    /** Load every CQ from this convention, isolating malformed entries into {@link LoadResult#skipped}. */
    LoadResult load(CqContext ctx);

    /** Insert or replace (by id) a CQ. May mutate the ontology (annotations) or the filesystem (files). */
    void upsert(CqContext ctx, CompetencyQuestion cq);

    /** Remove the CQ with {@code id}; returns {@code true} if one was present and removed. */
    boolean remove(CqContext ctx, String id);

    /** The outcome of a {@link #load}: the CQs that parsed, plus the entries that were skipped-with-reason. */
    final class LoadResult {
        final List<CompetencyQuestion> ok = new ArrayList<>();
        final List<LoadWarning> skipped = new ArrayList<>();

        void add(CompetencyQuestion cq) {
            ok.add(cq);
        }

        void skip(String source, String reason) {
            skipped.add(new LoadWarning(source, reason));
        }
    }

    /** A single skipped entry: where it came from and why it could not be loaded. */
    final class LoadWarning {
        final String source;
        final String reason;

        LoadWarning(String source, String reason) {
            this.source = source;
            this.reason = reason;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", source);
            m.put("reason", reason);
            return m;
        }
    }
}
