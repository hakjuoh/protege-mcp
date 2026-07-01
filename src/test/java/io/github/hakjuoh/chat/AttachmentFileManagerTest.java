package io.github.hakjuoh.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link AttachmentFileManager}, the pure scratch-dir lifecycle extracted from
 * {@code ChatView}. No Swing; POSIX-permission assertions are guarded with {@link Assumptions}.
 */
class AttachmentFileManagerTest {

    @Test
    void newScratchDirCreatesDistinctTrackedDirs() throws IOException {
        AttachmentFileManager m = new AttachmentFileManager();
        File d1 = m.newScratchDir();
        try {
            assertTrue(d1.isDirectory(), "scratch dir is created on disk");
            File d2 = m.newScratchDir();
            try {
                assertNotEquals(d1, d2, "each call yields a distinct scratch dir");
            } finally {
                m.deleteScratchDir(d2);
            }
        } finally {
            m.deleteScratchDir(d1);
        }
        assertFalse(d1.exists(), "deleteScratchDir removed the tracked dir");
    }

    @Test
    void deleteScratchDirOnlyRemovesTrackedDirs(@TempDir Path tmp) throws IOException {
        AttachmentFileManager m = new AttachmentFileManager();
        File untracked = tmp.resolve("outside").toFile();
        assertTrue(untracked.mkdir());
        m.deleteScratchDir(untracked);
        assertTrue(untracked.exists(), "an untracked dir is never deleted (guard against reclaiming real folders)");
    }

    @Test
    void deleteScratchDirNullIsNoOp() {
        new AttachmentFileManager().deleteScratchDir(null); // must not throw
    }

    @Test
    void deleteAllScratchRemovesEveryTrackedDirAndClearsTracking() throws IOException {
        AttachmentFileManager m = new AttachmentFileManager();
        File d1 = m.newScratchDir();
        File d2 = m.newScratchDir();
        m.deleteAllScratch();
        assertFalse(d1.exists(), "first tracked dir removed");
        assertFalse(d2.exists(), "second tracked dir removed");
        m.deleteAllScratch(); // tracking cleared -> a second sweep is a harmless no-op
    }

    @Test
    void deleteScratchForDeletesFileBackedButSkipsInlineAttachments() throws IOException {
        AttachmentFileManager m = new AttachmentFileManager();
        File dir = m.newScratchDir();
        File f = new File(dir, "a.txt");
        Files.writeString(f.toPath(), "x");
        ChatAttachment fileAtt = ChatAttachment.file("[File #1]", f, "text/plain");
        ChatAttachment inlineAtt = ChatAttachment.pastedText("[Pasted content #1]", "hello");
        m.deleteScratchFor(List.of(fileAtt, inlineAtt)); // inline has file()==null -> skipped, no NPE
        assertFalse(dir.exists(), "the file-backed attachment's scratch dir was reclaimed");
    }

    @Test
    void resetReclaimsDirsAndStillWorksAfterwards() throws IOException {
        AttachmentFileManager m = new AttachmentFileManager();
        File d = m.newScratchDir();
        m.reset();
        assertFalse(d.exists(), "reset reclaimed the scratch dir");
        File after = m.newScratchDir();
        try {
            assertTrue(after.isDirectory(), "newScratchDir still works after reset");
        } finally {
            m.deleteScratchDir(after);
        }
    }

    @Test
    void deleteRecursivelyNullIsNoOp() {
        AttachmentFileManager.deleteRecursively(null); // must not throw
    }

    @Test
    void deleteRecursivelyRemovesLeafFile(@TempDir Path tmp) throws IOException {
        File f = tmp.resolve("leaf.txt").toFile();
        Files.writeString(f.toPath(), "x");
        assertTrue(f.exists());
        AttachmentFileManager.deleteRecursively(f);
        assertFalse(f.exists());
    }

    @Test
    void deleteRecursivelyRemovesDirectoryTree(@TempDir Path tmp) throws IOException {
        File root = tmp.resolve("tree").toFile();
        File sub = new File(root, "sub");
        assertTrue(sub.mkdirs());
        Files.writeString(new File(sub, "f.txt").toPath(), "x");
        AttachmentFileManager.deleteRecursively(root);
        assertFalse(root.exists(), "the whole directory tree was removed");
    }

    @Test
    void restrictAppliesOwnerOnlyPermissionsForFile(@TempDir Path tmp) throws IOException {
        File f = tmp.resolve("secret.txt").toFile();
        Files.writeString(f.toPath(), "x");
        Assumptions.assumeTrue(supportsPosix(f.toPath()), "POSIX filesystem required");
        AttachmentFileManager.restrict(f.toPath(), false);
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(f.toPath());
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                "a file gets owner read+write only");
    }

    @Test
    void restrictAddsOwnerExecuteForDirectory(@TempDir Path tmp) throws IOException {
        File d = tmp.resolve("secretdir").toFile();
        assertTrue(d.mkdir());
        Assumptions.assumeTrue(supportsPosix(d.toPath()), "POSIX filesystem required");
        AttachmentFileManager.restrict(d.toPath(), true);
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(d.toPath());
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), perms, "a directory additionally gets owner execute");
    }

    @Test
    void restrictSwallowsErrorsForMissingPath(@TempDir Path tmp) {
        // setPosixFilePermissions on a missing path throws IOException (or UnsupportedOperationException on a
        // non-POSIX FS); restrict must swallow both rather than propagate.
        AttachmentFileManager.restrict(tmp.resolve("does-not-exist"), false);
    }

    private static boolean supportsPosix(Path p) {
        try {
            Files.getPosixFilePermissions(p);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }
}
