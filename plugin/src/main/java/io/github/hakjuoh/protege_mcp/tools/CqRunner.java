package io.github.hakjuoh.protege_mcp.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.core.qc.CompetencyQuestionService;

/** Source-compatible plugin adapter for the shared competency-question runner. */
final class CqRunner {

    static final String FAIL_ON_NONE = CompetencyQuestionService.FAIL_ON_NONE;
    static final String FAIL_ON_ANY = CompetencyQuestionService.FAIL_ON_ANY;

    private CqRunner() {
    }

    static Map<String, Object> run(SuiteSnapshot snapshot, List<CompetencyQuestion> questions,
            int limit, long timeoutMs, String failOn) {
        return CompetencyQuestionService.run(snapshot.shared(),
                questions.stream().map(CqRunner::shared).toList(), limit, timeoutMs, failOn);
    }

    static Map<String, Object> judge(SuiteSnapshot snapshot, CompetencyQuestion question,
            int limit, long timeoutMs) {
        return CompetencyQuestionService.judge(snapshot.shared(), shared(question), limit, timeoutMs);
    }

    static Judged judgeExec(Expectation expected, Map<String, Object> execution, int limit) {
        return new Judged(CompetencyQuestionService.judgeExecution(
                shared(expected), execution, limit));
    }

    static Set<String> canonicalizeRows(List<Map<String, Object>> rows) {
        return CompetencyQuestionService.canonicalizeRows(rows);
    }

    static String normalizeFailOn(String raw) {
        try {
            return CompetencyQuestionService.normalizeFailOn(raw);
        } catch (IllegalArgumentException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    static String gate(String failOn, int failed) {
        return CompetencyQuestionService.gate(failOn, failed);
    }

    static CompetencyQuestionService.Question shared(CompetencyQuestion question) {
        return new CompetencyQuestionService.Question(question.id, question.text,
                shared(question.expected), question.convention, question.query,
                question.includeInferred);
    }

    private static CompetencyQuestionService.Expectation shared(Expectation expected) {
        return new CompetencyQuestionService.Expectation(
                CompetencyQuestionService.ExpectationKind.valueOf(expected.kind.name()),
                expected.op, expected.value, expected.rows);
    }

    static CompetencyQuestion plugin(CompetencyQuestionService.Question question) {
        CompetencyQuestion out = new CompetencyQuestion();
        out.id = question.id();
        out.text = question.text();
        out.query = question.query();
        out.includeInferred = question.includeInferred();
        out.convention = question.convention();
        CompetencyQuestionService.Expectation expected = question.expected();
        out.expected = switch (expected.kind()) {
            case NON_EMPTY -> Expectation.nonEmpty();
            case EMPTY -> Expectation.empty();
            case COUNT -> Expectation.count(expected.op(), expected.value());
            case EXACT_ROWS -> Expectation.exactRows(expected.rows());
        };
        return out;
    }

    static final class Judged {
        final boolean pass;
        final String summary;
        final List<String> caveats;
        final String error;

        private Judged(CompetencyQuestionService.Judged shared) {
            this.pass = shared.pass();
            this.summary = shared.summary();
            this.caveats = shared.caveats();
            this.error = shared.error();
        }
    }
}
