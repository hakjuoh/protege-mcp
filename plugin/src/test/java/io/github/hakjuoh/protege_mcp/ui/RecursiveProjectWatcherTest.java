package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class RecursiveProjectWatcherTest {

    @Test
    void observesChangesInNewlyCreatedSubdirectories(@TempDir Path root) throws Exception {
        CountDownLatch directoryChanged = new CountDownLatch(1);
        CountDownLatch nestedFileChanged = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        try (RecursiveProjectWatcher watcher = new RecursiveProjectWatcher(root, () -> {
            if (callbacks.incrementAndGet() == 1) directoryChanged.countDown();
            else nestedFileChanged.countDown();
        })) {
            Path nested = Files.createDirectory(root.resolve("new-directory"));
            assertTrue(directoryChanged.await(10, TimeUnit.SECONDS));
            Files.writeString(nested.resolve("ontology.ttl"), "");
            assertTrue(nestedFileChanged.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void closePreventsLaterCallbacks(@TempDir Path root) throws Exception {
        CountDownLatch initialScan = new CountDownLatch(1);
        CountDownLatch afterClose = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        RecursiveProjectWatcher watcher = new RecursiveProjectWatcher(root, () -> {
            if (callbacks.incrementAndGet() == 1) initialScan.countDown();
            else afterClose.countDown();
        });
        assertTrue(initialScan.await(10, TimeUnit.SECONDS));
        watcher.close();
        Files.writeString(root.resolve("after-close.ttl"), "");
        assertFalse(afterClose.await(750, TimeUnit.MILLISECONDS));
    }

    @Test
    void reportsRegistrationLimit(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("a/nested"));
        Files.createDirectory(root.resolve("b"));
        CountDownLatch registered = new CountDownLatch(1);
        try (RecursiveProjectWatcher watcher = new RecursiveProjectWatcher(root,
                registered::countDown, 2)) {
            assertTrue(registered.await(10, TimeUnit.SECONDS));
            assertTrue(watcher.registrationTruncated());
        }
    }

    @Test
    void overflowRecoveryRequestsAFullRefresh(@TempDir Path root) throws Exception {
        CountDownLatch initial = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        try (RecursiveProjectWatcher watcher = new RecursiveProjectWatcher(root, () -> {
            if (callbacks.incrementAndGet() == 1) initial.countDown();
            else recovered.countDown();
        })) {
            assertTrue(initial.await(10, TimeUnit.SECONDS));
            watcher.recoverFromOverflow();
            assertTrue(recovered.await(10, TimeUnit.SECONDS));
            assertFalse(watcher.registrationTruncated());
        }
    }
}
