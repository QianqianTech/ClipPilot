package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class TaskStatsStoreTest {
    @Test
    public void freshSnapshotIsEmptyAndLegacySessionCountersAreNotLifetimeStats() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        preferences.edit().putInt("completed_task_count", 7).putInt("skipped_task_count", 2).apply();

        TaskStatsStore.Snapshot snapshot = TaskStatsStore.snapshot(preferences);

        assertEquals(0, snapshot.completedBatches);
        assertEquals(0, snapshot.totalBatches);
        assertEquals(0, snapshot.completedPlatformTasks);
        assertEquals(0, snapshot.completedVideoTasks);
        assertEquals(0, snapshot.skippedPlatformTasks);
        assertTrue(snapshot.history.isEmpty());
    }

    @Test
    public void finishIsIdempotentAndKeepsPartialBatchEvidence() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        TaskStatsStore.startBatch(preferences, 1_000L, 2);
        TaskStatsStore.recordVideoCompleted(preferences);
        TaskStatsStore.recordVideoCompleted(preferences);
        TaskStatsStore.recordPlatformCompleted(preferences);
        TaskStatsStore.recordPlatformSkipped(preferences);
        preferences.edit().putInt("completed_task_count", 1).putInt("skipped_task_count", 1).apply();

        assertTrue(TaskStatsStore.finishActiveBatch(preferences, false, "已由用户停止", 2_000L));
        assertFalse(TaskStatsStore.finishActiveBatch(preferences, false, "重复清理", 3_000L));

        TaskStatsStore.Snapshot snapshot = TaskStatsStore.snapshot(preferences);
        assertEquals(0, snapshot.completedBatches);
        assertEquals(1, snapshot.totalBatches);
        assertEquals(1, snapshot.completedPlatformTasks);
        assertEquals(2, snapshot.completedVideoTasks);
        assertEquals(1, snapshot.skippedPlatformTasks);
        assertEquals(1, snapshot.history.size());
        assertTrue(TaskStatsStore.formatHistoryEntry(snapshot.history.get(0)).contains("已由用户停止"));
    }

    @Test
    public void normalCompletionAddsOneCompletedBatchAndClearPreservesConfiguration() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        preferences.edit().putString("selected_packages", "com.example.app").apply();
        TaskStatsStore.startBatch(preferences, 4_000L, 1);
        TaskStatsStore.recordVideoCompleted(preferences);
        TaskStatsStore.recordPlatformCompleted(preferences);
        preferences.edit().putInt("completed_task_count", 1).putInt("skipped_task_count", 0).apply();

        assertTrue(TaskStatsStore.finishActiveBatch(preferences, true, "全部 1 个平台完成", 5_000L));

        TaskStatsStore.Snapshot snapshot = TaskStatsStore.snapshot(preferences);
        assertEquals(1, snapshot.completedBatches);
        assertEquals(1, snapshot.totalBatches);
        assertEquals(1, snapshot.completedPlatformTasks);
        assertEquals(1, snapshot.completedVideoTasks);
        assertEquals("全部 1 个平台完成", snapshot.lastReason);

        TaskStatsStore.clearHistory(preferences);
        TaskStatsStore.Snapshot cleared = TaskStatsStore.snapshot(preferences);
        assertEquals(0, cleared.totalBatches);
        assertTrue(cleared.history.isEmpty());
        assertEquals("com.example.app", preferences.getString("selected_packages", ""));
    }

    @Test
    public void historySerializationCleansSeparatorsAndParsesBlankEntries() {
        String encoded = TaskStatsStore.encodeHistory(
                6_000L, 2, 1, 4, 1, "用户停止\n下一行|其他");

        assertEquals("6000|2|1|4|1|用户停止 下一行/其他", encoded);
        List<String> parsed = TaskStatsStore.parseHistory("\n" + encoded + "\n\n");
        assertEquals(1, parsed.size());
        assertEquals(encoded, parsed.get(0));
    }

    @Test
    public void historyEntriesParseIntoFieldsAndRejectMalformedLines() {
        TaskStatsStore.HistoryEntry entry = TaskStatsStore.parseHistoryEntry(
                TaskStatsStore.encodeHistory(7_000L, 3, 2, 9, 1, "任务结束"));
        assertEquals(7_000L, entry.endedAt);
        assertEquals(3, entry.queueSize);
        assertEquals(2, entry.completedPlatforms);
        assertEquals(9, entry.completedVideos);
        assertEquals(1, entry.skippedPlatforms);
        assertEquals("任务结束", entry.reason);
        assertNull(TaskStatsStore.parseHistoryEntry("broken"));
        assertNull(TaskStatsStore.parseHistoryEntry("x|1|2|3|4|reason"));
    }
}
