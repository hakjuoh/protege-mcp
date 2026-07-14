package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

class ChangeSetStoreTest {

    @Test
    void claimIsExclusiveAndReleaseMakesTheEntryAvailableAgain() {
        AtomicLong now = new AtomicLong(1_000);
        ChangeSetStore store = new ChangeSetStore(now::get, false);
        ChangeSetStore.Entry entry = store.put(draft(10), 1_000);

        assertNotNull(store.claim(entry.id).entry());
        assertEquals("change_set_in_progress", store.claim(entry.id).error());
        store.release(entry);
        assertNotNull(store.claim(entry.id).entry());
    }

    @Test
    void expiredEntriesAreSweptAndUnknown() {
        AtomicLong now = new AtomicLong(5_000);
        ChangeSetStore store = new ChangeSetStore(now::get, false);
        ChangeSetStore.Entry entry = store.put(draft(10), 100);
        now.addAndGet(101);
        assertEquals("unknown_change_set", store.claim(entry.id).error());
        assertEquals(0, store.size());
    }

    @Test
    void consumeAndDiscardRemoveOnlyReadyEntries() {
        ChangeSetStore store = new ChangeSetStore(() -> 0L, false);
        ChangeSetStore.Entry consumed = store.put(draft(10), 1_000);
        store.claim(consumed.id);
        store.consume(consumed);
        assertEquals("unknown_change_set", store.claim(consumed.id).error());

        ChangeSetStore.Entry discarded = store.put(draft(10), 1_000);
        assertTrue(store.discard(discarded.id));
        assertFalse(store.discard(discarded.id));
    }

    @Test
    void normalizedChangeCountLimbIsEnforcedAtTheExactBoundary() {
        // The bytes limb is pinned below; this pins the other limb of validateEntryBounds — loosening
        // the *4 multiplier or the comparison direction must fail here.
        int max = ChangePlanner.MAX_OPERATIONS * 4;
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> ChangeSetStore.validateEntryBounds(max, 1L));
        ToolArgException tooLarge = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class, () -> ChangeSetStore.validateEntryBounds(max + 1, 1L));
        org.junit.jupiter.api.Assertions.assertTrue(
                tooLarge.getMessage().contains("normalized change set is too large"),
                tooLarge.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                tooLarge.getMessage().contains(String.valueOf(max)), tooLarge.getMessage());
    }

    @Test
    void oversizedEntryIsRejectedBeforeCaching() {
        ChangeSetStore store = new ChangeSetStore(() -> 0L, false);
        assertThrows(ToolArgException.class,
                () -> store.put(draft(ChangeSetStore.MAX_ENTRY_BYTES + 1), 1_000));
        assertEquals(0, store.size());
    }

    @Test
    void expectedRevisionRejectsPartialExtraAndFloatingCoordinates() {
        Map<String, Object> valid = Map.of(
                "workspace_id", "00000000-0000-4000-8000-000000000001",
                "session_revision", 1L,
                "semantic_fingerprint", "sha256:" + "a".repeat(64),
                "document_fingerprint", "sha256:" + "b".repeat(64));
        assertEquals(1L, ChangeSetTools.expectedRevision(valid).sessionRevision());
        assertThrows(ToolArgException.class, () -> ChangeSetTools.expectedRevision(Map.of()));
        var extra = new java.util.LinkedHashMap<>(valid);
        extra.put("extra", true);
        assertThrows(ToolArgException.class, () -> ChangeSetTools.expectedRevision(extra));
        var floating = new java.util.LinkedHashMap<>(valid);
        floating.put("session_revision", 1.0);
        assertThrows(ToolArgException.class, () -> ChangeSetTools.expectedRevision(floating));
    }

    private static ChangeSetStore.Draft draft(long bytes) {
        ModelRevision revision = new ModelRevision("00000000-0000-4000-8000-000000000001", 0,
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64));
        return new ChangeSetStore.Draft(revision, null, null, null, null, null,
                Collections.emptyList(), List.of(), Map.of(), null, true, List.of(), bytes);
    }
}
