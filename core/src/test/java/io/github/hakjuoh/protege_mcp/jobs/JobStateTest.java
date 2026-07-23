package io.github.hakjuoh.protege_mcp.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class JobStateTest {
    @Test
    void transitionTableIsClosedAndTerminalStatesAreImmutable() {
        assertEquals(Set.of(JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED),
                JobState.terminalStates());
        assertTransitions(JobState.QUEUED,
                Set.of(JobState.RUNNING, JobState.CANCELLED, JobState.FAILED));
        assertTransitions(JobState.RUNNING,
                Set.of(JobState.SUCCEEDED, JobState.FAILED,
                        JobState.CANCEL_PENDING, JobState.CANCELLED));
        assertTransitions(JobState.CANCEL_PENDING, Set.of(JobState.CANCELLED));
        assertTransitions(JobState.SUCCEEDED, Set.of());
        assertTransitions(JobState.FAILED, Set.of());
        assertTransitions(JobState.CANCELLED, Set.of());
    }

    private static void assertTransitions(JobState source, Set<JobState> expected) {
        for (JobState target : JobState.values()) {
            assertEquals(expected.contains(target), source.canTransitionTo(target),
                    () -> source + " -> " + target);
        }
        assertFalse(source.canTransitionTo(null));
        assertEquals(source == JobState.SUCCEEDED || source == JobState.FAILED
                || source == JobState.CANCELLED, source.terminal());
    }
}
