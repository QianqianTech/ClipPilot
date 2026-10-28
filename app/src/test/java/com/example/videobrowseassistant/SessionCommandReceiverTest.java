package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public class SessionCommandReceiverTest {
    private static InMemoryPreferences runningSession(boolean commentAssist) {
        InMemoryPreferences preferences = new InMemoryPreferences();
        SessionStore.start(preferences, Collections.singletonList("com.ss.android.ugc.aweme"), 2,
                1_000L, ContentTargeting.Config.disabled(), "");
        preferences.edit().putBoolean(InteractionSession.KEY_COMMENT_ASSIST_ENABLED, commentAssist).apply();
        return preferences;
    }

    @Test
    public void stopClearsTheSessionEvenWithoutARunningService() {
        InMemoryPreferences preferences = runningSession(false);
        assertEquals(SessionCommandReceiver.Result.STOPPED, SessionCommandReceiver.handle(
                preferences, SessionCommandReceiver.ACTION_STOP, "已通过通知栏停止", 2_000L));
        assertFalse(SessionStore.isRunning(preferences));
        assertEquals("已通过通知栏停止", preferences.getString(SessionKeys.SESSION_SUMMARY, ""));
    }

    @Test
    public void interactionActionsRequireARunningSessionAndCommentAssist() {
        InMemoryPreferences idle = new InMemoryPreferences();
        assertEquals(SessionCommandReceiver.Result.IGNORED_NOT_RUNNING, SessionCommandReceiver.handle(
                idle, SessionCommandReceiver.ACTION_PAUSE_AND_CAPTURE, "", 1L));
        InMemoryPreferences noAssist = runningSession(false);
        assertEquals(SessionCommandReceiver.Result.IGNORED_ASSIST_DISABLED, SessionCommandReceiver.handle(
                noAssist, SessionCommandReceiver.ACTION_PAUSE_AND_CAPTURE, "", 1L));
        assertEquals(SessionCommandReceiver.Result.IGNORED_UNKNOWN, SessionCommandReceiver.handle(
                noAssist, "other.action", "", 1L));
    }

    @Test
    public void pauseRequestsCaptureAndResumeClearsShortLivedData() {
        InMemoryPreferences preferences = runningSession(true);
        assertEquals(SessionCommandReceiver.Result.PAUSED, SessionCommandReceiver.handle(
                preferences, SessionCommandReceiver.ACTION_PAUSE_AND_CAPTURE, "暂停", 2_000L));
        assertTrue(InteractionSession.isPaused(preferences));
        assertFalse(preferences.getString(InteractionSession.KEY_COMMENT_CAPTURE_REQUEST_ID, "").isEmpty());

        assertEquals(SessionCommandReceiver.Result.IGNORED_NO_DRAFT, SessionCommandReceiver.handle(
                preferences, SessionCommandReceiver.ACTION_FILL_COMMENT_DRAFT, "", 2_000L));
        preferences.edit().putString(InteractionSession.KEY_COMMENT_DRAFT, "这段分享很有启发")
                .putLong(InteractionSession.KEY_COMMENT_DRAFT_CREATED_AT, 2_000L).apply();
        assertEquals(SessionCommandReceiver.Result.FILL_REQUESTED, SessionCommandReceiver.handle(
                preferences, SessionCommandReceiver.ACTION_FILL_COMMENT_DRAFT, "等待填入", 3_000L));

        assertEquals(SessionCommandReceiver.Result.RESUMED, SessionCommandReceiver.handle(
                preferences, SessionCommandReceiver.ACTION_RESUME_BROWSING, "已恢复浏览", 4_000L));
        assertFalse(InteractionSession.isPaused(preferences));
        assertFalse(preferences.contains(InteractionSession.KEY_COMMENT_DRAFT));
        assertFalse(preferences.contains(InteractionSession.KEY_COMMENT_FILL_REQUEST_ID));
        assertTrue(SessionStore.isRunning(preferences));
    }
}
