package com.example.videobrowseassistant;

import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Local-only lifetime counters and a short batch history. Session counters
 * remain separate: this store records what happened across app launches.
 */
public final class TaskStatsStore {
    public static final String KEY_COMPLETED_BATCHES = "stats_completed_batches";
    public static final String KEY_TOTAL_BATCHES = "stats_total_batches";
    public static final String KEY_COMPLETED_PLATFORM_TASKS = "stats_completed_platform_tasks";
    public static final String KEY_COMPLETED_VIDEO_TASKS = "stats_completed_video_tasks";
    public static final String KEY_SKIPPED_PLATFORM_TASKS = "stats_skipped_platform_tasks";
    public static final String KEY_LAST_ENDED_AT = "stats_last_ended_at";
    public static final String KEY_LAST_REASON = "stats_last_reason";
    public static final String KEY_HISTORY = "stats_history";

    private static final String KEY_ACTIVE_STARTED_AT = "stats_active_started_at";
    private static final String KEY_ACTIVE_QUEUE_SIZE = "stats_active_queue_size";
    private static final String KEY_ACTIVE_VIDEO_BASE = "stats_active_video_base";
    private static final int MAX_HISTORY = 20;
    private static final String HISTORY_SEPARATOR = "\n";
    private static final String FIELD_SEPARATOR = "|";

    private TaskStatsStore() {
    }

    public static void startBatch(SharedPreferences preferences, long startedAt, int queueSize) {
        if (preferences == null || startedAt <= 0L) {
            return;
        }
        preferences.edit()
                .putLong(KEY_ACTIVE_STARTED_AT, startedAt)
                .putInt(KEY_ACTIVE_QUEUE_SIZE, Math.max(0, queueSize))
                .putInt(KEY_ACTIVE_VIDEO_BASE,
                        Math.max(0, preferences.getInt(KEY_COMPLETED_VIDEO_TASKS, 0)))
                .apply();
    }

    public static void recordVideoCompleted(SharedPreferences preferences) {
        increment(preferences, KEY_COMPLETED_VIDEO_TASKS);
    }

    public static void recordPlatformCompleted(SharedPreferences preferences) {
        increment(preferences, KEY_COMPLETED_PLATFORM_TASKS);
    }

    public static void recordPlatformSkipped(SharedPreferences preferences) {
        increment(preferences, KEY_SKIPPED_PLATFORM_TASKS);
    }

    /**
     * Ends the active batch once. The active marker makes repeated cleanup
     * paths (service destroy, notification stop, and activity stop) harmless.
     */
    public static boolean finishActiveBatch(SharedPreferences preferences,
                                            boolean normallyCompleted,
                                            String reason,
                                            long endedAt) {
        if (preferences == null) {
            return false;
        }
        long startedAt = preferences.getLong(KEY_ACTIVE_STARTED_AT, 0L);
        if (startedAt <= 0L) {
            return false;
        }
        long safeEndedAt = endedAt > 0L ? endedAt : System.currentTimeMillis();
        int totalBatches = Math.max(0, preferences.getInt(KEY_TOTAL_BATCHES, 0)) + 1;
        int completedBatches = Math.max(0,
                preferences.getInt(KEY_COMPLETED_BATCHES, 0));
        if (normallyCompleted) {
            completedBatches++;
        }
        int queueSize = Math.max(0, preferences.getInt(KEY_ACTIVE_QUEUE_SIZE, 0));
        int completedPlatforms = Math.max(0,
                preferences.getInt(SessionKeys.COMPLETED_TASK_COUNT, 0));
        int skippedPlatforms = Math.max(0,
                preferences.getInt(SessionKeys.SKIPPED_TASK_COUNT, 0));
        int totalVideos = Math.max(0,
                preferences.getInt(KEY_COMPLETED_VIDEO_TASKS, 0));
        int videoBase = Math.max(0,
                preferences.getInt(KEY_ACTIVE_VIDEO_BASE, totalVideos));
        int completedVideos = Math.max(0, totalVideos - videoBase);
        String safeReason = clean(reason);
        String historyLine = encodeHistory(safeEndedAt, queueSize, completedPlatforms,
                completedVideos, skippedPlatforms, safeReason);
        List<String> history = parseHistory(preferences.getString(KEY_HISTORY, ""));
        history.add(historyLine);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        preferences.edit()
                .putInt(KEY_TOTAL_BATCHES, totalBatches)
                .putInt(KEY_COMPLETED_BATCHES, completedBatches)
                .putLong(KEY_LAST_ENDED_AT, safeEndedAt)
                .putString(KEY_LAST_REASON, safeReason)
                .putString(KEY_HISTORY, join(HISTORY_SEPARATOR, history))
                .remove(KEY_ACTIVE_STARTED_AT)
                .remove(KEY_ACTIVE_QUEUE_SIZE)
                .remove(KEY_ACTIVE_VIDEO_BASE)
                .apply();
        return true;
    }

    public static Snapshot snapshot(SharedPreferences preferences) {
        if (preferences == null) {
            return Snapshot.empty();
        }
        return new Snapshot(
                Math.max(0, preferences.getInt(KEY_COMPLETED_BATCHES, 0)),
                Math.max(0, preferences.getInt(KEY_TOTAL_BATCHES, 0)),
                Math.max(0, preferences.getInt(KEY_COMPLETED_PLATFORM_TASKS, 0)),
                Math.max(0, preferences.getInt(KEY_COMPLETED_VIDEO_TASKS, 0)),
                Math.max(0, preferences.getInt(KEY_SKIPPED_PLATFORM_TASKS, 0)),
                Math.max(0L, preferences.getLong(KEY_LAST_ENDED_AT, 0L)),
                clean(preferences.getString(KEY_LAST_REASON, "")),
                parseHistory(preferences.getString(KEY_HISTORY, "")));
    }

    public static void clearHistory(SharedPreferences preferences) {
        if (preferences == null) {
            return;
        }
        preferences.edit()
                .remove(KEY_COMPLETED_BATCHES)
                .remove(KEY_TOTAL_BATCHES)
                .remove(KEY_COMPLETED_PLATFORM_TASKS)
                .remove(KEY_COMPLETED_VIDEO_TASKS)
                .remove(KEY_SKIPPED_PLATFORM_TASKS)
                .remove(KEY_LAST_ENDED_AT)
                .remove(KEY_LAST_REASON)
                .remove(KEY_HISTORY)
                .apply();
    }

    static List<String> parseHistory(String serialized) {
        List<String> entries = new ArrayList<>();
        if (isEmpty(serialized)) {
            return entries;
        }
        for (String entry : serialized.split(HISTORY_SEPARATOR)) {
            // The field separator is part of the encoded record. Only trim
            // the line here; replacing '|' would make history unreadable.
            String cleanEntry = entry.trim();
            if (!cleanEntry.isEmpty()) {
                entries.add(cleanEntry);
            }
        }
        return entries;
    }

    static String encodeHistory(long endedAt, int queueSize, int completedPlatforms,
                                int completedVideos, int skippedPlatforms, String reason) {
        return endedAt + FIELD_SEPARATOR + Math.max(0, queueSize)
                + FIELD_SEPARATOR + Math.max(0, completedPlatforms)
                + FIELD_SEPARATOR + Math.max(0, completedVideos)
                + FIELD_SEPARATOR + Math.max(0, skippedPlatforms)
                + FIELD_SEPARATOR + clean(reason);
    }

    static String formatHistoryEntry(String encoded) {
        if (isEmpty(encoded)) {
            return "";
        }
        String[] fields = encoded.split("\\|", 6);
        if (fields.length < 6) {
            return encoded;
        }
        try {
            long endedAt = Long.parseLong(fields[0]);
            return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    .format(new Date(endedAt))
                    + " · 平台 " + fields[2]
                    + " 完成 · 视频 " + fields[3]
                    + " · 跳过 " + fields[4]
                    + " · " + fields[5];
        } catch (NumberFormatException ignored) {
            return encoded;
        }
    }

    /** Parses one encoded history line, or returns {@code null} when it is malformed. */
    static HistoryEntry parseHistoryEntry(String encoded) {
        if (isEmpty(encoded)) {
            return null;
        }
        String[] fields = encoded.split("\\|", 6);
        if (fields.length < 6) {
            return null;
        }
        try {
            return new HistoryEntry(Long.parseLong(fields[0]), Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]), fields[5]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static final class HistoryEntry {
        final long endedAt;
        final int queueSize;
        final int completedPlatforms;
        final int completedVideos;
        final int skippedPlatforms;
        final String reason;

        HistoryEntry(long endedAt, int queueSize, int completedPlatforms, int completedVideos,
                     int skippedPlatforms, String reason) {
            this.endedAt = endedAt;
            this.queueSize = queueSize;
            this.completedPlatforms = completedPlatforms;
            this.completedVideos = completedVideos;
            this.skippedPlatforms = skippedPlatforms;
            this.reason = reason;
        }
    }

    private static void increment(SharedPreferences preferences, String key) {
        if (preferences == null) {
            return;
        }
        preferences.edit()
                .putInt(key, Math.max(0, preferences.getInt(key, 0)) + 1)
                .apply();
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('|', '/').trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String join(String separator, List<String> values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                result.append(separator);
            }
            result.append(values.get(index));
        }
        return result.toString();
    }

    public static final class Snapshot {
        public final int completedBatches;
        public final int totalBatches;
        public final int completedPlatformTasks;
        public final int completedVideoTasks;
        public final int skippedPlatformTasks;
        public final long lastEndedAt;
        public final String lastReason;
        public final List<String> history;

        private Snapshot(int completedBatches, int totalBatches, int completedPlatformTasks,
                         int completedVideoTasks, int skippedPlatformTasks, long lastEndedAt,
                         String lastReason, List<String> history) {
            this.completedBatches = completedBatches;
            this.totalBatches = totalBatches;
            this.completedPlatformTasks = completedPlatformTasks;
            this.completedVideoTasks = completedVideoTasks;
            this.skippedPlatformTasks = skippedPlatformTasks;
            this.lastEndedAt = lastEndedAt;
            this.lastReason = lastReason;
            this.history = new ArrayList<>(history);
        }

        private static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0L, "", new ArrayList<>());
        }
    }
}
