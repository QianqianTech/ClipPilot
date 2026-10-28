package com.example.videobrowseassistant;

/**
 * Single source for the SharedPreferences file name and the session/configuration keys that are
 * shared by the activity, the accessibility service, the notification receiver and the stores.
 *
 * <p>Feature-specific keys (content targeting, comment assist, model gateway, lifetime stats)
 * stay next to their feature classes; this class only owns the keys that cross components.</p>
 */
final class SessionKeys {
    static final String PREFS = "session_preferences";

    // User configuration for the next session.
    static final String SELECTED_PACKAGES = "selected_packages";
    static final String TASK_VIDEO_COUNT = "task_video_count";
    static final String AUTO_START = "auto_start";
    static final String KEEP_SCREEN_ON = "keep_screen_on";
    /** Legacy single-target selection (0.2.x); read once for migration and kept in sync. */
    static final String TARGET_PACKAGE = "target_package";

    // Frozen snapshot and progress of the running session.
    static final String SESSION_RUNNING = "session_running";
    static final String SESSION_STARTED_AT = "session_started_at";
    static final String SESSION_QUEUE = "session_queue";
    static final String SESSION_TASK_QUOTA = "session_task_quota";
    static final String ACTIVE_TASK_INDEX = "active_task_index";
    static final String ACTIVE_TASK_VIDEO_COUNT = "active_task_video_count";
    static final String COMPLETED_TASK_COUNT = "completed_task_count";
    static final String SKIPPED_TASK_COUNT = "skipped_task_count";
    static final String SESSION_SUMMARY = "session_summary";

    static final int DEFAULT_VIDEOS_PER_APP = 5;
    static final long MAX_SESSION_MS = 30L * 60L * 1000L;

    private SessionKeys() {
    }
}
