package io.github.hakjuoh.protege_mcp.core.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Pure classifier for trusted private transaction evidence. */
final class WorkspaceRecoveryPlan {
    private WorkspaceRecoveryPlan() {
    }

    static Plan classify(List<Evidence> evidence, boolean targetPresent) {
        List<Path> cleanable = new ArrayList<>();
        List<Path> recoverable = new ArrayList<>();
        List<Path> uncertainToMark = new ArrayList<>();
        List<Path> ambiguous = new ArrayList<>();
        for (Evidence item : evidence) {
            boolean publicationState = item.publicationUncertain()
                    || item.publicationCompleted();
            boolean recoveryState = item.recoveryUncertain()
                    || item.recoveryCompleted();
            if ((publicationState && recoveryState)
                    || (item.completedQuarantine()
                            && (publicationState || recoveryState))) {
                ambiguous.add(item.directory());
                continue;
            }
            if (recoveryState && !item.displaced()) {
                cleanable.add(item.directory());
                continue;
            }
            if (!publicationState && !recoveryState && !item.completedQuarantine()
                    && !item.displaced()) {
                cleanable.add(item.directory());
                continue;
            }
            if (targetPresent) {
                classifyPresent(item, publicationState, recoveryState,
                        cleanable, uncertainToMark, ambiguous);
                continue;
            }
            if (item.completedQuarantine()) continue;
            if (publicationState || recoveryState) {
                ambiguous.add(item.directory());
            } else if (item.displaced()) {
                recoverable.add(item.directory());
            }
        }
        return new Plan(cleanable, recoverable, uncertainToMark, ambiguous);
    }

    private static void classifyPresent(Evidence item, boolean publicationState,
            boolean recoveryState, List<Path> cleanable, List<Path> uncertainToMark,
            List<Path> ambiguous) {
        if (publicationState) {
            if (item.staged() && item.targetLinksStage()) cleanable.add(item.directory());
            else ambiguous.add(item.directory());
        } else if (recoveryState) {
            if (item.displaced() && item.targetLinksDisplaced()) {
                cleanable.add(item.directory());
            } else {
                ambiguous.add(item.directory());
            }
        } else if (!item.completedQuarantine() && item.displaced()) {
            if (item.targetLinksDisplaced()) cleanable.add(item.directory());
            else uncertainToMark.add(item.directory());
        }
    }

    record Evidence(Path directory, boolean staged, boolean displaced,
            boolean publicationUncertain, boolean publicationCompleted,
            boolean recoveryUncertain, boolean recoveryCompleted,
            boolean completedQuarantine, boolean targetLinksStage,
            boolean targetLinksDisplaced) { }

    record Plan(List<Path> cleanable, List<Path> recoverable,
            List<Path> uncertainToMark, List<Path> ambiguous) {
        Plan {
            cleanable = List.copyOf(cleanable);
            recoverable = List.copyOf(recoverable);
            uncertainToMark = List.copyOf(uncertainToMark);
            ambiguous = List.copyOf(ambiguous);
        }

        boolean requiresManualIntervention() {
            return !ambiguous.isEmpty() || recoverable.size() > 1;
        }

        String reason() {
            return recoverable.size() > 1
                    ? "multiple recoverable transaction directories require manual inspection"
                    : "ambiguous transaction evidence prevents automatic recovery";
        }
    }
}
