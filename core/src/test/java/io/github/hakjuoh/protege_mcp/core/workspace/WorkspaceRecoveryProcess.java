package io.github.hakjuoh.protege_mcp.core.workspace;

import java.nio.file.Path;

/** Subprocess seam for real file-lock and abrupt-recovery tests. */
public final class WorkspaceRecoveryProcess {
    private WorkspaceRecoveryProcess() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 3 && "hold-lock".equals(arguments[0])) {
            Path stateRoot = Path.of(arguments[1]);
            Path projectRoot = Path.of(arguments[2]);
            try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                    stateRoot, projectRoot)) {
                System.out.println("LOCKED");
                System.out.flush();
                System.in.read();
            }
            return;
        }
        if (arguments.length == 3 && "try-lock".equals(arguments[0])) {
            try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                    Path.of(arguments[1]), Path.of(arguments[2]))) {
                return;
            } catch (ProjectFileLock.UnavailableException held) {
                Runtime.getRuntime().halt(75);
            }
        }
        if (arguments.length != 4
                || !("recover-and-halt".equals(arguments[0])
                        || "recover-cleanup-halt".equals(arguments[0]))) {
            throw new IllegalArgumentException(
                    "expected hold-lock STATE_ROOT PROJECT_ROOT or "
                            + "recover-and-halt/recover-cleanup-halt "
                            + "STATE_ROOT PROJECT_ROOT TARGET");
        }
        Path stateRoot = Path.of(arguments[1]);
        Path projectRoot = Path.of(arguments[2]);
        Path target = Path.of(arguments[3]);
        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(
                    stateRoot, projectRoot);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(projectRoot, target)) {
            anchor.recoverOrphanedTransactionsUnderLock(lock, lock.lockPath(),
                    () -> {
                        if ("recover-and-halt".equals(arguments[0])) {
                            Runtime.getRuntime().halt(73);
                        }
                    },
                    () -> {
                        if ("recover-cleanup-halt".equals(arguments[0])) {
                            Runtime.getRuntime().halt(74);
                        }
                    });
        }
        throw new IllegalStateException("recovery halt hook did not execute");
    }
}
