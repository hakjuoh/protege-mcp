package io.github.hakjuoh.protege_mcp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The shared reasoner-reference resolution rule: exact name/id equality (case-insensitive,
 * trimmed) or a contiguous whole-token run inside the display name, unique-or-fail.
 */
class ReasonerNamesTest {

    @Test
    void exactNameMatchesCaseInsensitivelyAndTrimmed() {
        ReasonerNames.Resolution resolution = ReasonerNames.resolveNames("  hermit ",
                List.of("HermiT", "ELK 0.6.0"));
        assertTrue(resolution.unique(), () -> resolution.candidateNames().toString());
        assertEquals("HermiT", resolution.match().name());
    }

    @Test
    void exactFactoryIdMatchesCaseInsensitivelyAndTrimmed() {
        ReasonerNames.Resolution resolution = ReasonerNames.resolve(" ORG.semanticweb.HermiT ",
                List.of(new ReasonerNames.Candidate("HermiT 1.4.3.456", "org.semanticweb.HermiT"),
                        new ReasonerNames.Candidate("ELK 0.6.0", "org.semanticweb.elk")));
        assertTrue(resolution.unique(), () -> resolution.candidateNames().toString());
        assertEquals("HermiT 1.4.3.456", resolution.match().name());
    }

    @Test
    void versionlessNameResolvesUniquelyByWholeTokenContainment() {
        ReasonerNames.Resolution resolution = ReasonerNames.resolveNames("HermiT",
                List.of("HermiT 1.4.3.456", "ELK 0.6.0"));
        assertTrue(resolution.unique(), () -> resolution.candidateNames().toString());
        assertEquals("HermiT 1.4.3.456", resolution.match().name());
    }

    @Test
    void partialTokensNeverMatch() {
        List<String> installed = List.of("HermiT 1.4.3.456");
        assertTrue(ReasonerNames.resolveNames("Herm", installed).candidates().isEmpty(),
                "a token prefix must never match");
        assertTrue(ReasonerNames.resolveNames("HermiT 1.4", installed).candidates().isEmpty(),
                "'1.4' is a partial version token, not the installed '1.4.3.456'");
    }

    @Test
    void versionedReferenceMustAgreeTokenForToken() {
        ReasonerNames.Resolution resolution = ReasonerNames.resolveNames("HermiT 9.9",
                List.of("HermiT 1.4.3.456"));
        assertTrue(resolution.candidates().isEmpty());
        assertFalse(resolution.unique());
        assertFalse(resolution.ambiguous());
    }

    @Test
    void aReferenceMatchingSeveralInstalledReasonersIsAmbiguousInInventoryOrder() {
        ReasonerNames.Resolution resolution = ReasonerNames.resolveNames("HermiT",
                List.of("HermiT 1.4", "HermiT 2.0"));
        assertTrue(resolution.ambiguous());
        assertFalse(resolution.unique());
        assertEquals(List.of("HermiT 1.4", "HermiT 2.0"), resolution.candidateNames());
    }

    @Test
    void anExactMatchTakesPrecedenceOverTokenMatches() {
        ReasonerNames.Resolution resolution = ReasonerNames.resolveNames("HermiT",
                List.of("HermiT", "HermiT 1.4.3.456"));
        assertTrue(resolution.unique(), () -> resolution.candidateNames().toString());
        assertEquals("HermiT", resolution.match().name());
    }

    @Test
    void candidateLabelsRetainIdsWhenDisplayNamesCollide() {
        ReasonerNames.Resolution resolution = ReasonerNames.resolve("Pellet",
                List.of(new ReasonerNames.Candidate("Pellet", "com.example.pellet.a"),
                        new ReasonerNames.Candidate("Pellet", "com.example.pellet.b")));
        assertTrue(resolution.ambiguous());
        assertEquals(List.of("Pellet [com.example.pellet.a]",
                "Pellet [com.example.pellet.b]"), resolution.candidateLabels());
    }

    @Test
    void nullAndBlankRequestsMatchNothing() {
        List<String> installed = List.of("HermiT 1.4.3.456", "ELK 0.6.0");
        assertTrue(ReasonerNames.resolveNames(null, installed).candidates().isEmpty());
        assertTrue(ReasonerNames.resolveNames("   ", installed).candidates().isEmpty());
    }

    @Test
    void tokenRunMayStartMidNameNotOnlyAtTheFront() {
        ReasonerNames.Resolution resolution = ReasonerNames.resolveNames("Pellet",
                List.of("Openllet Pellet", "ELK 0.6.0"));
        assertTrue(resolution.unique(), () -> resolution.candidateNames().toString());
        assertEquals("Openllet Pellet", resolution.match().name());
    }
}
