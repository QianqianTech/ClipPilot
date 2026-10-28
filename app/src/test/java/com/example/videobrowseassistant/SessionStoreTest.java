package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class SessionStoreTest {
    private static final String DOUYIN = "com.ss.android.ugc.aweme";
    private static final String BILI = "tv.danmaku.bili";

    @Test
    public void startPersistsFrozenSnapshotAndOpensStatsBatch() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        preferences.edit().putBoolean(InteractionSession.KEY_COMMENT_PAUSED, true).apply();

        SessionStore.start(preferences, Arrays.asList(DOUYIN, BILI), 150, 1_000L,
                ContentTargeting.Config.disabled(), "正在启动");

        assertTrue(SessionStore.isRunning(preferences));
        assertEquals(1_000L, SessionStore.startedAt(preferences));
        assertEquals(DOUYIN + "," + BILI, preferences.getString(SessionKeys.SESSION_QUEUE, ""));
        assertEquals(SessionTaskQueue.MAX_TASK_QUOTA, preferences.getInt(SessionKeys.SESSION_TASK_QUOTA, 0));
        assertEquals(DOUYIN, preferences.getString(SessionKeys.TARGET_PACKAGE, ""));
        assertFalse("previous pause state must not leak into a new session",
                InteractionSession.isPaused(preferences));
        assertTrue(preferences.contains("stats_active_started_at"));
    }

    @Test
    public void stopClearsEverySessionKeyAndRecordsTheBatchOnce() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        SessionStore.start(preferences, Arrays.asList(DOUYIN), 3, 1_000L,
                new ContentTargeting.Config(true, "学习", ""), "正在启动");
        preferences.edit().putInt(SessionKeys.ACTIVE_TASK_INDEX, 0)
                .putInt(SessionKeys.ACTIVE_TASK_VIDEO_COUNT, 2)
                .putInt(SessionKeys.COMPLETED_TASK_COUNT, 1)
                .putString(InteractionSession.KEY_COMMENT_DRAFT, "草稿")
                .apply();

        assertTrue(SessionStore.stop(preferences, "已由用户停止", false, 2_000L));

        assertFalse(SessionStore.isRunning(preferences));
        for (String key : new String[]{SessionKeys.SESSION_STARTED_AT, SessionKeys.SESSION_QUEUE,
                SessionKeys.SESSION_TASK_QUOTA, SessionKeys.ACTIVE_TASK_INDEX,
                SessionKeys.ACTIVE_TASK_VIDEO_COUNT, SessionKeys.COMPLETED_TASK_COUNT,
                SessionKeys.SKIPPED_TASK_COUNT, InteractionSession.KEY_COMMENT_DRAFT,
                ContentTargeting.KEY_SESSION_ENABLED}) {
            assertFalse(key + " should be cleared", preferences.contains(key));
        }
        assertEquals("已由用户停止", preferences.getString(SessionKeys.SESSION_SUMMARY, ""));
        assertEquals(1, TaskStatsStore.snapshot(preferences).totalBatches);

        assertFalse("a second stop is a no-op", SessionStore.stop(preferences, "重复", false, 3_000L));
        assertEquals(1, TaskStatsStore.snapshot(preferences).totalBatches);
    }

    @Test
    public void expiryUsesTheThirtyMinuteLimitAndTreatsMissingStartAsExpired() {
        assertEquals(SessionKeys.MAX_SESSION_MS, SessionStore.remainingMs(1_000L, 1_000L));
        assertEquals(0L, SessionStore.remainingMs(1_000L, 1_000L + SessionKeys.MAX_SESSION_MS));
        assertEquals(0L, SessionStore.remainingMs(0L, 5_000L));
        assertEquals(0L, SessionStore.remainingMs(5_000L, 1_000L));
        InMemoryPreferences preferences = new InMemoryPreferences();
        assertTrue(SessionStore.isExpired(preferences, 10_000L));
    }

    @Test
    public void progressIsClampedForTheUi() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        assertTrue(SessionStore.progress(preferences).isEmpty());
        SessionStore.start(preferences, Arrays.asList(DOUYIN, BILI), 4, 1_000L,
                ContentTargeting.Config.disabled(), "");
        preferences.edit().putInt(SessionKeys.ACTIVE_TASK_INDEX, 9)
                .putInt(SessionKeys.ACTIVE_TASK_VIDEO_COUNT, 99).apply();

        SessionStore.Progress progress = SessionStore.progress(preferences);
        assertEquals(1, progress.activeIndex);
        assertEquals(BILI, progress.activePackage());
        assertEquals(4, progress.activeVideos);
        assertEquals(4, progress.quota);
    }
}
