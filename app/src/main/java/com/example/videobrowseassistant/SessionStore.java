package com.example.videobrowseassistant;

import android.content.SharedPreferences;

import java.util.List;

/**
 * The only writer for session lifecycle state. Every start and every stop path (activity button,
 * notification action, service completion/timeout/interruption, stale-session normalization)
 * goes through {@link #start} and {@link #stop}, so the set of keys that is written or cleared
 * can no longer drift between components.
 */
final class SessionStore {
    private SessionStore() {
    }

    /** Persists the next-session configuration and the frozen snapshot, then marks it running. */
    static void start(SharedPreferences preferences, List<String> queue, int quota, long startedAt,
                      ContentTargeting.Config targeting, String summary) {
        if (preferences == null || queue == null || queue.isEmpty() || startedAt <= 0L) {
            return;
        }
        int safeQuota = SessionTaskQueue.clampQuota(quota,
                SessionTaskQueue.MIN_TASK_QUOTA, SessionTaskQueue.MAX_TASK_QUOTA);
        String serializedQueue = SessionTaskQueue.serialize(queue);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(SessionKeys.SELECTED_PACKAGES, serializedQueue)
                .putString(SessionKeys.SESSION_QUEUE, serializedQueue)
                .putString(SessionKeys.TARGET_PACKAGE, queue.get(0))
                .putInt(SessionKeys.TASK_VIDEO_COUNT, safeQuota)
                .putInt(SessionKeys.SESSION_TASK_QUOTA, safeQuota)
                .putInt(SessionKeys.ACTIVE_TASK_INDEX, 0)
                .putInt(SessionKeys.ACTIVE_TASK_VIDEO_COUNT, 0)
                .putInt(SessionKeys.COMPLETED_TASK_COUNT, 0)
                .putInt(SessionKeys.SKIPPED_TASK_COUNT, 0)
                .putString(SessionKeys.SESSION_SUMMARY, summary == null ? "" : summary)
                .putLong(SessionKeys.SESSION_STARTED_AT, startedAt)
                .putBoolean(SessionKeys.SESSION_RUNNING, true);
        InteractionSession.clearEphemeral(editor);
        ContentTargeting.snapshotSession(editor, targeting, safeQuota);
        editor.apply();
        TaskStatsStore.startBatch(preferences, startedAt, queue.size());
    }

    /**
     * Ends the current session once and records it in the lifetime statistics.
     *
     * @return {@code true} when a running session or an unfinished batch was actually closed.
     */
    static boolean stop(SharedPreferences preferences, String summary, boolean normallyCompleted,
                        long now) {
        if (preferences == null) {
            return false;
        }
        boolean wasRunning = preferences.getBoolean(SessionKeys.SESSION_RUNNING, false);
        String safeSummary = summary == null ? "" : summary;
        // Statistics read the per-session counters, so they must be finished before clearing.
        boolean batchFinished = TaskStatsStore.finishActiveBatch(preferences, normallyCompleted,
                safeSummary, now);
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(SessionKeys.SESSION_RUNNING, false)
                .remove(SessionKeys.SESSION_STARTED_AT)
                .remove(SessionKeys.SESSION_QUEUE)
                .remove(SessionKeys.SESSION_TASK_QUOTA)
                .remove(SessionKeys.ACTIVE_TASK_INDEX)
                .remove(SessionKeys.ACTIVE_TASK_VIDEO_COUNT)
                .remove(SessionKeys.COMPLETED_TASK_COUNT)
                .remove(SessionKeys.SKIPPED_TASK_COUNT);
        InteractionSession.clearEphemeral(editor);
        ContentTargeting.clearSession(editor);
        if (!safeSummary.isEmpty()) {
            editor.putString(SessionKeys.SESSION_SUMMARY, safeSummary);
        }
        editor.apply();
        return wasRunning || batchFinished;
    }

    static boolean isRunning(SharedPreferences preferences) {
        return preferences != null && preferences.getBoolean(SessionKeys.SESSION_RUNNING, false);
    }

    static long startedAt(SharedPreferences preferences) {
        return preferences == null ? 0L : preferences.getLong(SessionKeys.SESSION_STARTED_AT, 0L);
    }

    /** A running session without a valid start time is treated as expired. */
    static boolean isExpired(SharedPreferences preferences, long now) {
        return remainingMs(startedAt(preferences), now) <= 0L;
    }

    static long remainingMs(long startedAt, long now) {
        if (startedAt <= 0L || now < startedAt) {
            return 0L;
        }
        return Math.max(0L, SessionKeys.MAX_SESSION_MS - (now - startedAt));
    }

    /** Read-only view of the running session for UI summaries. */
    static Progress progress(SharedPreferences preferences) {
        if (preferences == null) {
            return Progress.EMPTY;
        }
        List<String> queue = SessionTaskQueue.parse(
                preferences.getString(SessionKeys.SESSION_QUEUE, ""));
        if (queue.isEmpty()) {
            return Progress.EMPTY;
        }
        int index = Math.max(0, Math.min(queue.size() - 1,
                preferences.getInt(SessionKeys.ACTIVE_TASK_INDEX, 0)));
        int quota = Math.max(1, preferences.getInt(SessionKeys.SESSION_TASK_QUOTA,
                SessionKeys.DEFAULT_VIDEOS_PER_APP));
        int videos = Math.max(0, Math.min(quota,
                preferences.getInt(SessionKeys.ACTIVE_TASK_VIDEO_COUNT, 0)));
        return new Progress(queue, index, videos, quota);
    }

    static final class Progress {
        static final Progress EMPTY = new Progress(java.util.Collections.emptyList(), 0, 0, 0);

        final List<String> queue;
        final int activeIndex;
        final int activeVideos;
        final int quota;

        Progress(List<String> queue, int activeIndex, int activeVideos, int quota) {
            this.queue = queue;
            this.activeIndex = activeIndex;
            this.activeVideos = activeVideos;
            this.quota = quota;
        }

        boolean isEmpty() {
            return queue.isEmpty();
        }

        String activePackage() {
            return queue.isEmpty() ? "" : queue.get(activeIndex);
        }
    }
}
