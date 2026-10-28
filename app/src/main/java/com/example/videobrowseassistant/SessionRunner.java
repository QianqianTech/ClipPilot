package com.example.videobrowseassistant;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure bookkeeping for one running multi-platform session: queue position, per-platform quota,
 * completed/skipped counts and content-targeting scan counters.
 *
 * <p>The accessibility service owns timers, gestures and app launches; it feeds events into this
 * class and acts on the returned outcome. Keeping the decisions here makes the state machine
 * testable without a device.</p>
 */
final class SessionRunner {
    /** What the service should do after a swipe gesture completed. */
    enum SwipeOutcome {
        /** Stay on the platform: schedule the next swipe, or the next content analysis. */
        CONTINUE,
        /** Quota reached: advance to the next platform as completed. */
        PLATFORM_COMPLETED,
        /** Content-targeting scan budget exhausted before the quota: advance as skipped. */
        PLATFORM_SCAN_LIMIT
    }

    /** What the service should do after the current page was classified. */
    enum ContentOutcome {
        /** Swipe after the usual random delay; {@link #pendingSwipeCountsAsMatch()} tells whether it counts. */
        SWIPE,
        /** The scan budget is exhausted for this platform. */
        SKIP_SCAN_LIMIT,
        /** Too many consecutive misses for this platform. */
        SKIP_MISS_LIMIT
    }

    private final List<String> queue;
    private final long startedAt;
    private final int quota;
    private final boolean targetingEnabled;
    private final ContentTargeting.Config targetingConfig;
    private final int maxScans;
    private final Set<String> seenFingerprints = new LinkedHashSet<>();

    private int activeIndex;
    private int activeVideos;
    private int completedPlatforms;
    private int skippedPlatforms;
    private int scans;
    private int noMatches;
    private int excluded;
    private int unknown;
    private int consecutiveMisses;
    private int totalScans;

    private boolean decisionHandled;
    private boolean pendingSwipeCountsAsMatch;
    private boolean scanLimitReachedAfterSwipe;
    private ContentTargeting.Decision lastDecision;

    SessionRunner(List<String> queue, long startedAt, int quota, int activeIndex, int activeVideos,
                  int completedPlatforms, int skippedPlatforms, ContentTargeting.Config targeting,
                  int maxScans) {
        this.queue = Collections.unmodifiableList(new ArrayList<>(queue));
        this.startedAt = startedAt;
        this.quota = SessionTaskQueue.clampQuota(quota,
                SessionTaskQueue.MIN_TASK_QUOTA, SessionTaskQueue.MAX_TASK_QUOTA);
        this.activeIndex = activeIndex;
        this.activeVideos = Math.max(0, Math.min(this.quota, activeVideos));
        this.completedPlatforms = Math.max(0, completedPlatforms);
        this.skippedPlatforms = Math.max(0, skippedPlatforms);
        this.targetingConfig = targeting == null ? ContentTargeting.Config.disabled() : targeting;
        this.targetingEnabled = this.targetingConfig.isReady();
        this.maxScans = targetingEnabled ? Math.max(1, maxScans) : 0;
    }

    /** Rebuilds the runner from the persisted snapshot, or returns {@code null} when it is invalid. */
    static SessionRunner restore(SharedPreferences preferences) {
        if (preferences == null) {
            return null;
        }
        long startedAt = preferences.getLong(SessionKeys.SESSION_STARTED_AT, 0L);
        List<String> queue = SessionTaskQueue.parse(
                preferences.getString(SessionKeys.SESSION_QUEUE, ""));
        int index = preferences.getInt(SessionKeys.ACTIVE_TASK_INDEX, 0);
        if (startedAt <= 0L || queue.isEmpty() || index < 0 || index >= queue.size()) {
            return null;
        }
        int quota = preferences.getInt(SessionKeys.SESSION_TASK_QUOTA,
                SessionKeys.DEFAULT_VIDEOS_PER_APP);
        boolean targeting = ContentTargeting.isSessionEnabled(preferences);
        ContentTargeting.Config config = targeting
                ? ContentTargeting.sessionConfig(preferences) : ContentTargeting.Config.disabled();
        int maxScans = preferences.getInt(ContentTargeting.KEY_SESSION_MAX_SCANS,
                ContentTargeting.maxScansForQuota(quota));
        SessionRunner runner = new SessionRunner(queue, startedAt, quota, index,
                preferences.getInt(SessionKeys.ACTIVE_TASK_VIDEO_COUNT, 0),
                preferences.getInt(SessionKeys.COMPLETED_TASK_COUNT, 0),
                preferences.getInt(SessionKeys.SKIPPED_TASK_COUNT, 0),
                config, maxScans);
        if (runner.targetingEnabled) {
            runner.scans = Math.max(0, preferences.getInt(ContentTargeting.KEY_ACTIVE_SCANS, 0));
            runner.noMatches = Math.max(0,
                    preferences.getInt(ContentTargeting.KEY_ACTIVE_NO_MATCHES, 0));
            runner.excluded = Math.max(0,
                    preferences.getInt(ContentTargeting.KEY_ACTIVE_EXCLUDED, 0));
            runner.unknown = Math.max(0,
                    preferences.getInt(ContentTargeting.KEY_ACTIVE_UNKNOWN, 0));
            runner.consecutiveMisses = Math.max(0,
                    preferences.getInt(ContentTargeting.KEY_ACTIVE_CONSECUTIVE_MISSES, 0));
            runner.totalScans = Math.max(0,
                    preferences.getInt(ContentTargeting.KEY_TOTAL_SCANS, 0));
        }
        return runner;
    }

    // ---- Content targeting -------------------------------------------------------------------

    boolean targetingEnabled() {
        return targetingEnabled;
    }

    ContentTargeting.Config targetingConfig() {
        return targetingConfig;
    }

    /** True when the current video still has to be classified before it may be swiped. */
    boolean needsContentDecision() {
        return targetingEnabled && !decisionHandled;
    }

    /** Forgets the decision for the current video, e.g. after leaving the app or resuming. */
    void resetVideoDecision() {
        decisionHandled = false;
        pendingSwipeCountsAsMatch = false;
        scanLimitReachedAfterSwipe = false;
    }

    boolean pendingSwipeCountsAsMatch() {
        return pendingSwipeCountsAsMatch;
    }

    boolean scanLimitReachedAfterSwipe() {
        return scanLimitReachedAfterSwipe;
    }

    ContentTargeting.Decision lastDecision() {
        return lastDecision;
    }

    /**
     * Classifies the current page text. A page whose fingerprint was already seen in this
     * session is never counted as a target hit.
     */
    ContentOutcome onContentAnalyzed(String visibleText) {
        ContentTargeting.Decision decision = ContentTargeting.decide(visibleText, targetingConfig);
        String fingerprint = ContentTargeting.fingerprint(visibleText);
        if (!fingerprint.isEmpty() && !seenFingerprints.add(fingerprint)) {
            decision = ContentTargeting.Decision.noMatch("重复页面内容，不计入目标");
        }
        return onContentDecision(decision);
    }

    ContentOutcome onContentDecision(ContentTargeting.Decision decision) {
        lastDecision = decision;
        decisionHandled = true;
        scans++;
        totalScans++;
        if (decision.countsAsMatch()) {
            consecutiveMisses = 0;
        } else {
            consecutiveMisses++;
        }
        switch (decision.kind) {
            case EXCLUDED:
                excluded++;
                break;
            case UNKNOWN:
                unknown++;
                break;
            case NO_MATCH:
                noMatches++;
                break;
            case MATCH:
            default:
                break;
        }
        boolean scanLimitReached = scans >= maxScans;
        boolean missLimitReached = consecutiveMisses >= ContentTargeting.MAX_CONSECUTIVE_MISSES;
        if (decision.countsAsMiss() && scanLimitReached) {
            return ContentOutcome.SKIP_SCAN_LIMIT;
        }
        if (decision.countsAsMiss() && missLimitReached) {
            return ContentOutcome.SKIP_MISS_LIMIT;
        }
        pendingSwipeCountsAsMatch = decision.countsAsMatch();
        scanLimitReachedAfterSwipe = scanLimitReached;
        return ContentOutcome.SWIPE;
    }

    // ---- Swipes and platform transitions -----------------------------------------------------

    /**
     * Records a completed swipe. In plain browsing every swipe completes one video; with content
     * targeting only a swipe away from a matching page counts.
     *
     * @param countsAsMatch       value of {@link #pendingSwipeCountsAsMatch()} when dispatched
     * @param scanLimitAfterSwipe value of {@link #scanLimitReachedAfterSwipe()} when dispatched
     */
    SwipeOutcome onSwipeCompleted(boolean countsAsMatch, boolean scanLimitAfterSwipe) {
        if (!targetingEnabled || countsAsMatch) {
            activeVideos++;
        }
        if (targetingEnabled) {
            resetVideoDecision();
        }
        if (activeVideos >= quota) {
            return SwipeOutcome.PLATFORM_COMPLETED;
        }
        if (targetingEnabled && scanLimitAfterSwipe) {
            return SwipeOutcome.PLATFORM_SCAN_LIMIT;
        }
        return SwipeOutcome.CONTINUE;
    }

    /**
     * Finishes the active platform and moves to the next one.
     *
     * @return {@code true} when another platform follows; {@code false} when the batch is over.
     */
    boolean advance(boolean skipped) {
        resetVideoDecision();
        if (skipped) {
            skippedPlatforms++;
        } else {
            completedPlatforms++;
        }
        if (activeIndex + 1 >= queue.size()) {
            return false;
        }
        activeIndex++;
        activeVideos = 0;
        scans = 0;
        noMatches = 0;
        excluded = 0;
        unknown = 0;
        consecutiveMisses = 0;
        seenFingerprints.clear();
        return true;
    }

    /** A batch counts as normally completed only when no platform was skipped. */
    boolean finishedNormally() {
        return skippedPlatforms == 0;
    }

    /** Writes the progress that the UI and a reconnecting service read back. */
    void persist(SharedPreferences.Editor editor) {
        editor.putInt(SessionKeys.ACTIVE_TASK_INDEX, activeIndex)
                .putInt(SessionKeys.ACTIVE_TASK_VIDEO_COUNT, activeVideos)
                .putInt(SessionKeys.COMPLETED_TASK_COUNT, completedPlatforms)
                .putInt(SessionKeys.SKIPPED_TASK_COUNT, skippedPlatforms)
                .putString(SessionKeys.TARGET_PACKAGE, targetPackage());
        if (targetingEnabled) {
            editor.putInt(ContentTargeting.KEY_ACTIVE_SCANS, scans)
                    .putInt(ContentTargeting.KEY_ACTIVE_NO_MATCHES, noMatches)
                    .putInt(ContentTargeting.KEY_ACTIVE_EXCLUDED, excluded)
                    .putInt(ContentTargeting.KEY_ACTIVE_UNKNOWN, unknown)
                    .putInt(ContentTargeting.KEY_ACTIVE_CONSECUTIVE_MISSES, consecutiveMisses)
                    .putInt(ContentTargeting.KEY_TOTAL_SCANS, totalScans);
            if (lastDecision != null && decisionHandled) {
                editor.putString(ContentTargeting.KEY_LAST_DECISION, lastDecision.kind.name())
                        .putString(ContentTargeting.KEY_LAST_REASON, lastDecision.reason);
            } else if (scans == 0) {
                editor.putString(ContentTargeting.KEY_LAST_DECISION, "")
                        .putString(ContentTargeting.KEY_LAST_REASON, "");
            }
        }
    }

    // ---- Read-only views ---------------------------------------------------------------------

    long startedAt() {
        return startedAt;
    }

    long remainingMs(long now) {
        return SessionStore.remainingMs(startedAt, now);
    }

    List<String> queue() {
        return queue;
    }

    String targetPackage() {
        return queue.get(activeIndex);
    }

    int activeIndex() {
        return activeIndex;
    }

    int activeVideos() {
        return activeVideos;
    }

    int quota() {
        return quota;
    }

    int completedPlatforms() {
        return completedPlatforms;
    }

    int skippedPlatforms() {
        return skippedPlatforms;
    }

    int scans() {
        return scans;
    }

    int maxScans() {
        return maxScans;
    }
}
