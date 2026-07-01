package io.github.hakjuoh.chat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Owner-only scratch directories for chat attachments: each attachment is copied into its own one-file
 * dir (so only that dir is granted to the CLI), tracked for cleanup on New-Chat / view close. Pure file
 * I/O, no Swing — split out of {@code ChatView} so it is headless-testable. {@code ChatView} holds one
 * instance and delegates.
 */
public final class AttachmentFileManager {

    private final List<File> sessionTempDirs = new ArrayList<>();
    private int nextScratchSeq = 1;

    /** A fresh, owner-only scratch subdir holding exactly one attachment file; tracked for later cleanup. */
    public File newScratchDir() throws IOException {
        File root = new File(CliSupport.neutralWorkingDir(), "attachments");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("could not create attachment directory: " + root);
        }
        restrict(root.toPath(), true);
        File dir = new File(root, "att-" + System.currentTimeMillis() + "-" + (nextScratchSeq++));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create attachment directory: " + dir);
        }
        restrict(dir.toPath(), true);
        dir.deleteOnExit();
        sessionTempDirs.add(dir);
        return dir;
    }

    /** Best-effort owner-only permissions on POSIX filesystems (no-op on Windows / non-POSIX). */
    public static void restrict(Path path, boolean directory) {
        Set<PosixFilePermission> perms = directory
                ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE)
                : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem or transient failure — fall back to the platform's default ACLs
        }
    }

    /** Delete one of our scratch dirs (and its single file). Guarded so only tracked dirs are removed. */
    public void deleteScratchDir(File dir) {
        if (dir != null && sessionTempDirs.remove(dir)) {
            deleteRecursively(dir);
        }
    }

    /** Delete the scratch dirs backing the given attachments (no-op for inline pasted text). */
    public void deleteScratchFor(Collection<ChatAttachment> attachments) {
        for (ChatAttachment a : attachments) {
            File f = a.file();
            if (f != null) {
                deleteScratchDir(f.getParentFile());
            }
        }
    }

    /** Remove every scratch dir created this conversation (New Chat / view close). */
    public void deleteAllScratch() {
        for (File dir : new ArrayList<>(sessionTempDirs)) {
            deleteRecursively(dir);
        }
        sessionTempDirs.clear();
    }

    /** New-conversation reset: reclaim all scratch dirs and restart the sequence counter. */
    public void reset() {
        deleteAllScratch();
        nextScratchSeq = 1;
    }

    public static void deleteRecursively(File f) {
        if (f == null) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }
}
