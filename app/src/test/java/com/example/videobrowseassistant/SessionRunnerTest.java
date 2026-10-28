package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SessionRunnerTest {
    private static final List<String> QUEUE = Arrays.asList("com.ss.android.ugc.aweme", "tv.danmaku.bili");

    private static SessionRunner plain(int quota) {
        return new SessionRunner(QUEUE, 1_000L, quota, 0, 0, 0, 0,
                ContentTargeting.Config.disabled(), 0);
    }

    private static SessionRunner targeted(int quota, int maxScans) {
        return new SessionRunner(QUEUE, 1_000L, quota, 0, 0, 0, 0,
                new ContentTargeting.Config(true, "学习计划", "广告"), maxScans);
    }

    @Test
    public void plainBrowsingCompletesEachPlatformAndFinishesNormally() {
        SessionRunner runner = plain(2);
        assertFalse(runner.needsContentDecision());
        assertEquals(SessionRunner.SwipeOutcome.CONTINUE, runner.onSwipeCompleted(false, false));
        assertEquals(SessionRunner.SwipeOutcome.PLATFORM_COMPLETED, runner.onSwipeCompleted(false, false));
        assertTrue(runner.advance(false));
        assertEquals("tv.danmaku.bili", runner.targetPackage());
        assertEquals(0, runner.activeVideos());
        runner.onSwipeCompleted(false, false);
        assertEquals(SessionRunner.SwipeOutcome.PLATFORM_COMPLETED, runner.onSwipeCompleted(false, false));
        assertFalse(runner.advance(false));
        assertTrue(runner.finishedNormally());
        assertEquals(2, runner.completedPlatforms());
    }

    @Test
    public void skippedPlatformMeansTheBatchIsNotNormallyCompleted() {
        SessionRunner runner = plain(2);
        assertTrue(runner.advance(true));
        assertFalse(runner.advance(false));
        assertFalse(runner.finishedNormally());
        assertEquals(1, runner.skippedPlatforms());
        assertEquals(1, runner.completedPlatforms());
    }

    @Test
    public void targetingCountsOnlyMatchesAndNeedsADecisionPerVideo() {
        SessionRunner runner = targeted(2, 10);
        assertTrue(runner.needsContentDecision());

        assertEquals(SessionRunner.ContentOutcome.SWIPE,
                runner.onContentAnalyzed("这条视频聊城市散步和天气记录"));
        assertFalse(runner.pendingSwipeCountsAsMatch());
        assertFalse(runner.needsContentDecision());
        assertEquals(SessionRunner.SwipeOutcome.CONTINUE, runner.onSwipeCompleted(false, false));
        assertEquals("a miss does not count toward the quota", 0, runner.activeVideos());
        assertTrue("after a swipe the next video needs a new decision", runner.needsContentDecision());

        runner.onContentAnalyzed("今天分享学习计划如何拆成小目标");
        assertTrue(runner.pendingSwipeCountsAsMatch());
        runner.onSwipeCompleted(true, false);
        assertEquals(1, runner.activeVideos());
        assertEquals(2, runner.scans());
    }

    @Test
    public void duplicatePagesNeverCountAsMatches() {
        SessionRunner runner = targeted(3, 10);
        String page = "今天分享学习计划如何拆成小目标";
        runner.onContentAnalyzed(page);
        assertTrue(runner.pendingSwipeCountsAsMatch());
        runner.onSwipeCompleted(true, false);
        runner.onContentAnalyzed(page);
        assertFalse(runner.pendingSwipeCountsAsMatch());
        assertEquals(ContentTargeting.Decision.Kind.NO_MATCH, runner.lastDecision().kind);
    }

    @Test
    public void scanBudgetSkipsThePlatformOnAMissAndAfterAFinalMatch() {
        SessionRunner missing = targeted(2, 2);
        missing.onContentAnalyzed("城市散步记录和天气分享第一条");
        missing.onSwipeCompleted(false, false);
        assertEquals(SessionRunner.ContentOutcome.SKIP_SCAN_LIMIT,
                missing.onContentAnalyzed("城市散步记录和天气分享第二条"));

        SessionRunner matching = targeted(2, 1);
        assertEquals(SessionRunner.ContentOutcome.SWIPE,
                matching.onContentAnalyzed("今天分享学习计划如何拆成小目标"));
        assertTrue(matching.scanLimitReachedAfterSwipe());
        assertEquals(SessionRunner.SwipeOutcome.PLATFORM_SCAN_LIMIT,
                matching.onSwipeCompleted(true, true));
    }

    @Test
    public void consecutiveMissesSkipThePlatform() {
        SessionRunner runner = targeted(2, 100);
        SessionRunner.ContentOutcome outcome = null;
        for (int index = 0; index < ContentTargeting.MAX_CONSECUTIVE_MISSES; index++) {
            outcome = runner.onContentAnalyzed("无关的城市散步记录和天气分享第" + index + "条");
            if (outcome == SessionRunner.ContentOutcome.SWIPE) {
                runner.onSwipeCompleted(false, false);
            }
        }
        assertEquals(SessionRunner.ContentOutcome.SKIP_MISS_LIMIT, outcome);
    }

    @Test
    public void advancingResetsPerPlatformTargetingCounters() {
        SessionRunner runner = targeted(2, 10);
        runner.onContentAnalyzed("城市散步记录和天气分享");
        assertEquals(1, runner.scans());
        assertTrue(runner.advance(true));
        assertEquals(0, runner.scans());
        assertTrue(runner.needsContentDecision());
    }

    @Test
    public void persistAndRestoreRoundTrip() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        SessionStore.start(preferences, QUEUE, 3, 5_000L,
                new ContentTargeting.Config(true, "学习计划", ""), "");
        SessionRunner runner = SessionRunner.restore(preferences);
        assertNotNull(runner);
        assertTrue(runner.targetingEnabled());
        runner.onContentAnalyzed("今天分享学习计划如何拆成小目标");
        runner.onSwipeCompleted(true, false);
        runner.advance(false);
        SharedPreferencesEditorApplier.persist(runner, preferences);

        SessionRunner restored = SessionRunner.restore(preferences);
        assertNotNull(restored);
        assertEquals(1, restored.activeIndex());
        assertEquals("tv.danmaku.bili", restored.targetPackage());
        assertEquals(1, restored.completedPlatforms());
        assertEquals(5_000L, restored.startedAt());
        assertEquals("tv.danmaku.bili", preferences.getString(SessionKeys.TARGET_PACKAGE, ""));
    }

    @Test
    public void restoreRejectsInvalidSnapshots() {
        InMemoryPreferences preferences = new InMemoryPreferences();
        assertNull(SessionRunner.restore(preferences));
        preferences.edit().putLong(SessionKeys.SESSION_STARTED_AT, 1L)
                .putString(SessionKeys.SESSION_QUEUE, "a.b")
                .putInt(SessionKeys.ACTIVE_TASK_INDEX, 3).apply();
        assertNull(SessionRunner.restore(preferences));
    }

    /** Keeps the test focused on the runner: persist through one editor, like the service. */
    private static final class SharedPreferencesEditorApplier {
        static void persist(SessionRunner runner, InMemoryPreferences preferences) {
            android.content.SharedPreferences.Editor editor = preferences.edit();
            runner.persist(editor);
            editor.apply();
        }
    }
}
