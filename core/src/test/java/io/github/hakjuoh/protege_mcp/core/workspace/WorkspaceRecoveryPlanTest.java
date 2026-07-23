package io.github.hakjuoh.protege_mcp.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class WorkspaceRecoveryPlanTest {
    @Test
    void classifiesEveryTerminalAndAmbiguousStateExplicitly() {
        Path stageOnly = Path.of("stage-only");
        Path recoverable = Path.of("recoverable");
        Path terminal = Path.of("terminal");
        WorkspaceRecoveryPlan.Plan absent = WorkspaceRecoveryPlan.classify(List.of(
                evidence(stageOnly, true, false, false, false, false, false, false,
                        false, false),
                evidence(terminal, false, true, false, false, false, false, true,
                        false, false)), false);
        assertEquals(List.of(stageOnly), absent.cleanable());
        assertTrue(absent.recoverable().isEmpty());
        assertFalse(absent.requiresManualIntervention());

        WorkspaceRecoveryPlan.Plan single = WorkspaceRecoveryPlan.classify(List.of(
                evidence(recoverable, true, true, false, false, false, false, false,
                        false, false)), false);
        assertEquals(List.of(recoverable), single.recoverable());

        WorkspaceRecoveryPlan.Plan mixed = WorkspaceRecoveryPlan.classify(List.of(
                evidence(Path.of("mixed"), true, true, true, false, true, false, false,
                        false, false)), false);
        assertTrue(mixed.requiresManualIntervention());
    }

    @Test
    void targetIdentitySeparatesCleanupFromConcurrentContent() {
        Path published = Path.of("published");
        Path empty = Path.of("empty");
        Path uncertain = Path.of("uncertain");
        Path raced = Path.of("raced");
        WorkspaceRecoveryPlan.Plan plan = WorkspaceRecoveryPlan.classify(List.of(
                evidence(empty, false, false, false, false, false, false, false,
                        false, false),
                evidence(published, true, true, true, false, false, false, false,
                        true, false),
                evidence(uncertain, true, true, false, false, false, false, false,
                        false, false),
                evidence(raced, true, true, false, false, true, false, false,
                        false, false)), true);

        assertEquals(List.of(empty, published), plan.cleanable());
        assertEquals(List.of(uncertain), plan.uncertainToMark());
        assertEquals(List.of(raced), plan.ambiguous());
        assertTrue(plan.requiresManualIntervention());
    }

    private static WorkspaceRecoveryPlan.Evidence evidence(Path path,
            boolean staged, boolean displaced,
            boolean publicationUncertain, boolean publicationCompleted,
            boolean recoveryUncertain, boolean recoveryCompleted,
            boolean completedQuarantine, boolean targetLinksStage,
            boolean targetLinksDisplaced) {
        return new WorkspaceRecoveryPlan.Evidence(path, staged, displaced,
                publicationUncertain, publicationCompleted,
                recoveryUncertain, recoveryCompleted, completedQuarantine,
                targetLinksStage, targetLinksDisplaced);
    }
}
