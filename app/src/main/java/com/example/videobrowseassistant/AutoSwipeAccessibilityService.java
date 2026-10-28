package com.example.videobrowseassistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;
import java.util.Random;

/**
 * Executes the user-started session: waits for the current target app to be in the foreground,
 * optionally classifies the visible page, swipes after a random 10–30 s delay and switches to the
 * next app when a platform's quota is reached.
 *
 * <p>Bookkeeping lives in {@link SessionRunner}; page reading in {@link ContentScanner}; comment
 * steps in {@link CommentWorkflow}; the notification in {@link SessionNotifications}. Commands
 * arrive through {@link SessionCommands} instead of SharedPreferences change listeners.</p>
 */
public class AutoSwipeAccessibilityService extends AccessibilityService
        implements SessionCommands.Handler, CommentWorkflow.Listener {
    private static final long SERVICE_CONNECT_GRACE_MS = 10L * 1000L;
    private static final long APP_SWITCH_TIMEOUT_MS = 15L * 1000L;
    private static final long CONTENT_ANALYSIS_DELAY_MS = 750L;
    private static final int IDLE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    private static final int RUNNING_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOWS_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random swipeRandom = new Random();
    private SharedPreferences preferences;
    private SessionNotifications notifications;
    private CommentWorkflow commentWorkflow;
    private SessionRunner runner;
    private PowerManager.WakeLock screenWakeLock;
    private String pendingLaunchPackage = "";
    private int appliedEventTypes = -1;
    private boolean targetInForeground;
    private boolean swipeScheduled;
    private boolean contentAnalysisScheduled;
    private boolean gestureInFlight;
    private boolean firstGestureRecorded;
    private boolean gestureCancellationRecorded;
    private boolean gestureDispatchFailureRecorded;
    private int swipeCount;

    private final Runnable swipeRunnable = () -> {
        swipeScheduled = false;
        dispatchSwipeIfAllowed();
    };

    private final Runnable contentAnalysisRunnable = () -> {
        contentAnalysisScheduled = false;
        analyzeCurrentContentIfAllowed();
    };

    private final Runnable appSwitchTimeoutRunnable = () -> {
        String failedPackage = pendingLaunchPackage;
        pendingLaunchPackage = "";
        if (runner != null && !failedPackage.isEmpty()
                && failedPackage.equals(runner.targetPackage())
                && !targetInForeground
                && SessionStore.isRunning(preferences)
                && !InteractionSession.isPaused(preferences)) {
            Diagnostics.error(this, "自动切换超时：" + Platform.labelFor(failedPackage)
                    + " 未在 15 秒内进入前台，已跳过该平台。");
            advanceToNextTask(true);
        }
    };

    /** Deterministic 30-minute limit, independent of whether accessibility events keep arriving. */
    private final Runnable expiryRunnable = () -> {
        if (runner != null && SessionStore.isRunning(preferences)) {
            stopSession(getString(R.string.summary_expired), false, true);
        }
    };

    // ---- Lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ensureInitialized();
        notifications.createChannel();

        long startedAt = SessionStore.startedAt(preferences);
        long elapsed = startedAt <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - startedAt;
        boolean sessionCanSurviveReconnect = SessionStore.isRunning(preferences)
                && elapsed >= 0L
                && elapsed < SERVICE_CONNECT_GRACE_MS;
        if (sessionCanSurviveReconnect && startFromPreferences(true)) {
            Diagnostics.info(this, "无障碍服务已重新连接，多平台任务等待 "
                    + Platform.labelFor(runner.targetPackage()) + " 回到前台。");
        } else {
            SessionStore.stop(preferences, "", false, System.currentTimeMillis());
            teardownLocalState();
            Diagnostics.info(this, "无障碍服务已连接，等待用户启动会话。");
            notifications.publish(preferences, getString(R.string.notification_connected_idle));
        }
        SessionCommands.register(this);
    }

    @Override
    public void onInterrupt() {
        Diagnostics.error(this, "无障碍服务被系统中断。");
        stopSession(getString(R.string.summary_interrupted), false, true);
    }

    @Override
    public void onDestroy() {
        SessionCommands.unregister(this);
        handler.removeCallbacksAndMessages(null);
        if (preferences != null) {
            SessionStore.stop(preferences, getString(R.string.summary_service_disconnected),
                    false, System.currentTimeMillis());
        }
        ModelGatewayClient.cancelActiveCalls();
        Diagnostics.info(this, "无障碍服务已断开。");
        releaseScreenWakeLock();
        super.onDestroy();
    }

    private void ensureInitialized() {
        if (preferences == null) {
            preferences = getSharedPreferences(SessionKeys.PREFS, MODE_PRIVATE);
            notifications = new SessionNotifications(this);
            commentWorkflow = new CommentWorkflow(this, preferences, this);
            Diagnostics.initialize(this);
        }
    }

    // ---- Commands ----------------------------------------------------------------------------

    @Override
    public void onSessionCommand(SessionCommands.Command command) {
        ensureInitialized();
        switch (command) {
            case SESSION_STARTED:
                startFromPreferences(false);
                break;
            case SESSION_STOPPED:
                teardownLocalState();
                String summary = preferences.getString(SessionKeys.SESSION_SUMMARY, "");
                notifications.publish(preferences, summary.isEmpty()
                        ? getString(R.string.summary_stopped) : summary);
                break;
            case PAUSE_AND_CAPTURE:
                if (runner != null) {
                    cancelPendingSwipe();
                    cancelContentAnalysis();
                    cancelAppSwitchTimeout();
                    commentWorkflow.processPending(runner.targetPackage());
                    notifications.publish(preferences, progressMessage(R.string.phase_comment_paused));
                }
                break;
            case RESUME_BROWSING:
                if (runner != null) {
                    cancelPendingSwipe();
                    runner.resetVideoDecision();
                    if (targetInForeground) {
                        scheduleNext();
                    }
                    notifications.publish(preferences, progressMessage(R.string.phase_resumed));
                }
                break;
            case FILL_DRAFT:
                if (runner != null) {
                    commentWorkflow.processPending(runner.targetPackage());
                }
                break;
            case DRAFT_UPDATED:
                if (runner != null) {
                    notifications.publish(preferences, progressMessage(
                            InteractionSession.isPaused(preferences)
                                    ? R.string.phase_comment_paused : R.string.phase_running));
                }
                break;
            case KEEP_SCREEN_CHANGED:
                updateScreenWakeLock();
                break;
            default:
                break;
        }
    }

    @Override
    public void onCommentWorkflowUpdated(String phase) {
        if (runner != null) {
            notifications.publish(preferences, progressMessage(phase));
        }
    }

    /**
     * Builds the runner from the persisted snapshot. Also used as a fallback when a start command
     * was missed (for example because the service was reconnecting at that moment).
     */
    private boolean startFromPreferences(boolean reconnect) {
        SessionRunner restored = SessionRunner.restore(preferences);
        if (restored == null) {
            stopSession(getString(R.string.summary_invalid_queue), false, true);
            return false;
        }
        long remaining = restored.remainingMs(System.currentTimeMillis());
        if (remaining <= 0L) {
            runner = restored;
            stopSession(getString(R.string.summary_expired), false, true);
            return false;
        }
        cancelAllTimers();
        runner = restored;
        swipeCount = 0;
        targetInForeground = false;
        gestureInFlight = false;
        firstGestureRecorded = false;
        gestureCancellationRecorded = false;
        gestureDispatchFailureRecorded = false;
        applyEventTypes(true);
        updateScreenWakeLock();
        handler.postDelayed(expiryRunnable, remaining);
        if (!InteractionSession.isPaused(preferences)) {
            scheduleTargetEntryTimeout(runner.targetPackage());
        }
        notifications.invalidate();
        notifications.publish(preferences, progressMessage(reconnect
                ? R.string.phase_waiting_return : R.string.phase_waiting_foreground));
        if (!reconnect) {
            Diagnostics.info(this, "多平台任务已下发到无障碍服务，等待 "
                    + Platform.labelFor(runner.targetPackage()) + " 进入前台。");
        }
        return true;
    }

    // ---- Accessibility events ----------------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        ensureInitialized();
        if (!SessionStore.isRunning(preferences)) {
            if (runner != null) {
                // A stop command was missed; align local state with the persisted stop.
                teardownLocalState();
            }
            return;
        }
        if (runner == null || SessionStore.startedAt(preferences) != runner.startedAt()) {
            if (!startFromPreferences(false)) {
                return;
            }
        }
        if (runner.remainingMs(System.currentTimeMillis()) <= 0L) {
            stopSession(getString(R.string.summary_expired), false, true);
            return;
        }

        String currentPackage = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String targetPackage = runner.targetPackage();
        if (targetPackage.equals(currentPackage)) {
            onTargetEvent();
        } else if (isForegroundTransitionEvent(event.getEventType())) {
            // A window transition from another package normally means that the user left the
            // target app. Some Android versions report the transition package as the overlay/IME
            // even though the target window is still active, so consult the active root first.
            if (targetInForeground && isTargetWindowActive()) {
                return;
            }
            onTargetLeft();
        }
        // Content-change events from other packages (IME, overlays, other accessibility windows)
        // do not prove that the target left the foreground and are ignored.
    }

    private void onTargetEvent() {
        boolean enteredTarget = !targetInForeground;
        targetInForeground = true;
        cancelAppSwitchTimeout();
        String label = Platform.labelFor(runner.targetPackage());
        if (InteractionSession.isPaused(preferences)) {
            cancelPendingSwipe();
            cancelContentAnalysis();
            commentWorkflow.processPending(runner.targetPackage());
            if (enteredTarget) {
                Diagnostics.info(this, label + " 已回到前台，浏览保持暂停，等待用户完成评论操作。");
            }
            notifications.publish(preferences, progressMessage(R.string.phase_comment_paused));
            return;
        }
        if (enteredTarget) {
            notifications.publish(preferences, progressMessage(R.string.phase_in_foreground));
            if (runner.targetingEnabled()) {
                Diagnostics.info(this, label + " 已在前台，正在等待当前页面文字用于本地内容定向。"
                        + "命中后仍会随机等待 10–30 秒再上滑。");
            }
        }
        if (runner.targetingEnabled()) {
            scheduleContentAnalysis(CONTENT_ANALYSIS_DELAY_MS);
        } else {
            int delaySeconds = scheduleRandomSwipe();
            if (enteredTarget && delaySeconds > 0) {
                Diagnostics.info(this, label + " 已在前台，将随机等待 " + delaySeconds
                        + " 秒后完成当前视频并上滑。");
            }
        }
    }

    private void onTargetLeft() {
        boolean leftTarget = targetInForeground;
        targetInForeground = false;
        cancelPendingSwipe();
        cancelContentAnalysis();
        runner.resetVideoDecision();
        if (leftTarget) {
            notifications.publish(preferences, progressMessage(R.string.phase_waiting_return));
            Diagnostics.info(this, Platform.labelFor(runner.targetPackage())
                    + " 离开前台，任务已暂停并等待返回。");
        }
    }

    /**
     * Returns whether an event can establish that the foreground window changed.
     * TYPE_WINDOW_CONTENT_CHANGED is intentionally excluded because it is emitted continuously by
     * many apps and overlays.
     */
    static boolean isForegroundTransitionEvent(int eventType) {
        return (eventType & (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED)) != 0;
    }

    private boolean isTargetWindowActive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean active = runner != null && CommentWorkflow.isPackageRoot(root, runner.targetPackage());
        ContentScanner.recycle(root);
        return active;
    }

    /**
     * Content-change events are only needed while a session runs; when idle the service listens
     * to window-state changes alone, which keeps it from waking up for every UI update in every
     * app. Package filtering is not used because leaving the target is detected through events
     * from other packages (launcher, other apps).
     */
    private void applyEventTypes(boolean running) {
        int wanted = running ? RUNNING_EVENT_TYPES : IDLE_EVENT_TYPES;
        if (wanted == appliedEventTypes) {
            return;
        }
        // Start from the XML-declared info so canRetrieveWindowContent is not discarded.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            return;
        }
        info.eventTypes = wanted;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 200;
        setServiceInfo(info);
        appliedEventTypes = wanted;
    }

    // ---- Scheduling --------------------------------------------------------------------------

    private boolean canAct() {
        return runner != null
                && targetInForeground
                && !gestureInFlight
                && SessionStore.isRunning(preferences)
                && !InteractionSession.isPaused(preferences);
    }

    /** Selects a fresh, inclusive 10–30 second delay for each video transition. */
    private int scheduleRandomSwipe() {
        if (!canAct() || swipeScheduled || runner.needsContentDecision()) {
            return 0;
        }
        int seconds = SwipeTiming.randomSeconds(swipeRandom);
        swipeScheduled = true;
        handler.postDelayed(swipeRunnable, seconds * 1000L);
        return seconds;
    }

    private void cancelPendingSwipe() {
        handler.removeCallbacks(swipeRunnable);
        swipeScheduled = false;
    }

    private void scheduleContentAnalysis(long delayMs) {
        if (!canAct() || !runner.needsContentDecision() || contentAnalysisScheduled) {
            return;
        }
        contentAnalysisScheduled = true;
        handler.postDelayed(contentAnalysisRunnable, delayMs);
    }

    private void cancelContentAnalysis() {
        handler.removeCallbacks(contentAnalysisRunnable);
        contentAnalysisScheduled = false;
    }

    private void scheduleNext() {
        if (runner == null || !SessionStore.isRunning(preferences)) {
            return;
        }
        if (runner.targetingEnabled()) {
            runner.resetVideoDecision();
            scheduleContentAnalysis(CONTENT_ANALYSIS_DELAY_MS);
        } else {
            scheduleRandomSwipe();
        }
    }

    private void cancelAppSwitchTimeout() {
        handler.removeCallbacks(appSwitchTimeoutRunnable);
        pendingLaunchPackage = "";
    }

    private void scheduleTargetEntryTimeout(String packageName) {
        handler.removeCallbacks(appSwitchTimeoutRunnable);
        pendingLaunchPackage = packageName;
        handler.postDelayed(appSwitchTimeoutRunnable, APP_SWITCH_TIMEOUT_MS);
    }

    private void cancelAllTimers() {
        cancelPendingSwipe();
        cancelContentAnalysis();
        cancelAppSwitchTimeout();
        handler.removeCallbacks(expiryRunnable);
    }

    // ---- Content targeting -------------------------------------------------------------------

    private void analyzeCurrentContentIfAllowed() {
        if (!canAct() || !runner.needsContentDecision()) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        ContentScanner.Result scan = ContentScanner.Result.EMPTY;
        if (CommentWorkflow.isPackageRoot(root, runner.targetPackage())) {
            scan = ContentScanner.collect(root, ContentScanner.Limits.DEFAULT);
        } else {
            ContentScanner.recycle(root);
        }
        SessionRunner.ContentOutcome outcome = runner.onContentAnalyzed(scan.text);
        persistRunner();
        ContentTargeting.Decision decision = runner.lastDecision();
        Diagnostics.info(this, "内容定向判定：" + decision.diagnosticLabel()
                + "；已扫描 " + runner.scans() + "/" + runner.maxScans()
                + "；节点 " + scan.nodesScanned + " · 文本 " + scan.textsCollected + "。");
        String label = Platform.labelFor(runner.targetPackage());
        if (outcome == SessionRunner.ContentOutcome.SKIP_SCAN_LIMIT) {
            Diagnostics.info(this, label + " 内容定向达到扫描上限，已跳过当前平台。");
            advanceToNextTask(true);
            return;
        }
        if (outcome == SessionRunner.ContentOutcome.SKIP_MISS_LIMIT) {
            Diagnostics.info(this, label + " 内容定向连续未命中，已跳过当前平台。");
            advanceToNextTask(true);
            return;
        }
        notifications.publish(preferences, progressMessage(targetingPhase(decision)));
        scheduleRandomSwipe();
    }

    private int targetingPhase(ContentTargeting.Decision decision) {
        switch (decision.kind) {
            case MATCH:
                return R.string.phase_target_match;
            case EXCLUDED:
                return R.string.phase_target_excluded;
            case UNKNOWN:
                return R.string.phase_target_unknown;
            case NO_MATCH:
            default:
                return R.string.phase_target_no_match;
        }
    }

    // ---- Gestures ----------------------------------------------------------------------------

    private void dispatchSwipeIfAllowed() {
        if (runner == null || !SessionStore.isRunning(preferences)) {
            return;
        }
        if (runner.remainingMs(System.currentTimeMillis()) <= 0L) {
            stopSession(getString(R.string.summary_expired), false, true);
            return;
        }
        if (!canAct()) {
            return;
        }
        Rect bounds = swipeBounds();
        if (bounds == null) {
            // The active window is no longer the target app: never swipe someone else's screen.
            onTargetLeft();
            return;
        }
        final boolean countsAsMatch = runner.pendingSwipeCountsAsMatch();
        final boolean scanLimitAfterSwipe = runner.scanLimitReachedAfterSwipe();
        Path path = new Path();
        float x = bounds.exactCenterX();
        path.moveTo(x, bounds.top + bounds.height() * 0.78f);
        path.lineTo(x, bounds.top + bounds.height() * 0.28f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 420L))
                .build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                gestureInFlight = false;
                onSwipeCompleted(countsAsMatch, scanLimitAfterSwipe);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                gestureInFlight = false;
                if (runner == null || !SessionStore.isRunning(preferences)) {
                    return;
                }
                if (!gestureCancellationRecorded) {
                    gestureCancellationRecorded = true;
                    Diagnostics.error(AutoSwipeAccessibilityService.this,
                            "系统取消了上滑手势。可能因目标应用切换、屏幕状态变化或厂商系统限制。");
                }
                scheduleRandomSwipe();
            }
        }, handler);
        if (!dispatched) {
            if (!gestureDispatchFailureRecorded) {
                gestureDispatchFailureRecorded = true;
                Diagnostics.error(this,
                        "系统拒绝发送上滑手势（dispatchGesture=false）。请确认无障碍服务仍处于开启状态。");
            }
            scheduleRandomSwipe();
            return;
        }
        gestureInFlight = true;
        if (!firstGestureRecorded) {
            firstGestureRecorded = true;
            Diagnostics.info(this, "已向系统提交第一条上滑手势。");
        }
        notifications.publish(preferences, progressMessage(R.string.phase_switching_video));
    }

    /**
     * Swipe inside the target window's bounds (split screen, foldable outer display, navigation
     * bars). Returns {@code null} when the active window does not belong to the target app.
     */
    private Rect swipeBounds() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!CommentWorkflow.isPackageRoot(root, runner.targetPackage())) {
            ContentScanner.recycle(root);
            return null;
        }
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        ContentScanner.recycle(root);
        if (bounds.width() < 100 || bounds.height() < 200) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            bounds.set(0, 0, metrics.widthPixels, metrics.heightPixels);
        }
        return bounds;
    }

    private void onSwipeCompleted(boolean countsAsMatch, boolean scanLimitAfterSwipe) {
        if (runner == null
                || !SessionStore.isRunning(preferences)
                || !targetInForeground
                || InteractionSession.isPaused(preferences)) {
            return;
        }
        swipeCount++;
        boolean videoCounted = !runner.targetingEnabled() || countsAsMatch;
        SessionRunner.SwipeOutcome outcome = runner.onSwipeCompleted(countsAsMatch, scanLimitAfterSwipe);
        if (videoCounted) {
            TaskStatsStore.recordVideoCompleted(preferences);
        }
        persistRunner();
        if (swipeCount == 1) {
            Diagnostics.info(this, "第一条上滑手势已被系统完成。");
        }
        switch (outcome) {
            case PLATFORM_COMPLETED:
                advanceToNextTask(false);
                break;
            case PLATFORM_SCAN_LIMIT:
                Diagnostics.info(this, Platform.labelFor(runner.targetPackage())
                        + " 已达到内容定向扫描上限，未完成目标，已跳过该平台。");
                advanceToNextTask(true);
                break;
            case CONTINUE:
            default:
                if (runner.targetingEnabled()) {
                    notifications.publish(preferences, progressMessage(R.string.phase_waiting_next_content));
                    scheduleContentAnalysis(CONTENT_ANALYSIS_DELAY_MS);
                } else {
                    notifications.publish(preferences, progressMessage(R.string.phase_running));
                    scheduleRandomSwipe();
                }
                break;
        }
    }

    // ---- Platform transitions ----------------------------------------------------------------

    private void advanceToNextTask(boolean skipped) {
        cancelPendingSwipe();
        cancelContentAnalysis();
        cancelAppSwitchTimeout();
        targetInForeground = false;
        gestureInFlight = false;
        String finishedLabel = Platform.labelFor(runner.targetPackage());
        if (skipped) {
            TaskStatsStore.recordPlatformSkipped(preferences);
        } else {
            TaskStatsStore.recordPlatformCompleted(preferences);
            Diagnostics.info(this, finishedLabel + " 已完成 " + runner.quota()
                    + (runner.targetingEnabled() ? " 个定向命中视频。" : " 个视频任务。"));
        }
        boolean hasNext = runner.advance(skipped);
        persistRunner();
        if (!hasNext) {
            String result = runner.finishedNormally()
                    ? getString(R.string.summary_all_done, runner.completedPlatforms())
                    : getString(R.string.summary_finished_with_skips,
                    runner.completedPlatforms(), runner.skippedPlatforms());
            stopSession(result, runner.finishedNormally(), false);
            return;
        }
        String nextPackage = runner.targetPackage();
        String switchingMessage = getString(R.string.summary_switching_to, platformName(nextPackage));
        preferences.edit().putString(SessionKeys.SESSION_SUMMARY, switchingMessage).apply();
        notifications.publish(preferences, progressMessage(R.string.phase_switching_app));
        Diagnostics.info(this, "正在切换到 " + Platform.labelFor(nextPackage) + "（任务 "
                + (runner.activeIndex() + 1) + "/" + runner.queue().size() + "）。");
        if (!launchTargetPackage(nextPackage)) {
            Diagnostics.error(this, "自动切换失败：无法打开 " + Platform.labelFor(nextPackage)
                    + "，已跳过该平台。");
            advanceToNextTask(true);
        }
    }

    private boolean launchTargetPackage(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launch);
            scheduleTargetEntryTimeout(packageName);
            return true;
        } catch (ActivityNotFoundException | SecurityException exception) {
            Diagnostics.error(this, "系统拒绝打开 " + Platform.labelFor(packageName) + "："
                    + exception.getClass().getSimpleName() + "。");
            return false;
        }
    }

    private void persistRunner() {
        if (runner != null && preferences != null) {
            SharedPreferences.Editor editor = preferences.edit();
            runner.persist(editor);
            editor.apply();
        }
    }

    // ---- Stop and cleanup --------------------------------------------------------------------

    private void stopSession(String summary, boolean normallyCompleted, boolean error) {
        if (error) {
            Diagnostics.error(this, "任务已停止：" + summary);
        } else {
            Diagnostics.info(this, summary + "。");
        }
        SessionStore.stop(preferences, summary, normallyCompleted, System.currentTimeMillis());
        teardownLocalState();
        notifications.publish(preferences, summary);
    }

    /** Clears in-memory state only; persisted state is owned by {@link SessionStore}. */
    private void teardownLocalState() {
        cancelAllTimers();
        ModelGatewayClient.cancelActiveCalls();
        runner = null;
        targetInForeground = false;
        gestureInFlight = false;
        swipeCount = 0;
        applyEventTypes(false);
        releaseScreenWakeLock();
    }

    // ---- Notification text -------------------------------------------------------------------

    private String progressMessage(int phaseRes) {
        return progressMessage(getString(phaseRes));
    }

    private String progressMessage(String phase) {
        if (runner == null) {
            return phase;
        }
        String platform = platformName(runner.targetPackage());
        if (runner.targetingEnabled()) {
            return getString(R.string.progress_targeting, phase, platform,
                    Math.min(runner.activeVideos(), runner.quota()), runner.quota(),
                    runner.scans(), runner.maxScans(),
                    runner.activeIndex() + 1, runner.queue().size());
        }
        return getString(R.string.progress_plain, phase, platform,
                Math.min(runner.activeVideos(), runner.quota()), runner.quota(),
                runner.activeIndex() + 1, runner.queue().size());
    }

    private String platformName(String packageName) {
        Platform platform = Platform.fromPackage(packageName);
        return platform == null ? packageName.toLowerCase(Locale.ROOT) : getString(platform.labelRes);
    }

    // ---- Screen wake lock --------------------------------------------------------------------

    private void updateScreenWakeLock() {
        boolean shouldHold = runner != null
                && SessionStore.isRunning(preferences)
                && preferences.getBoolean(SessionKeys.KEEP_SCREEN_ON, false);
        if (shouldHold) {
            acquireScreenWakeLock(runner.remainingMs(System.currentTimeMillis()));
        } else {
            releaseScreenWakeLock();
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireScreenWakeLock(long timeoutMs) {
        if (timeoutMs <= 0L) {
            return;
        }
        if (screenWakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager == null) {
                Diagnostics.error(this, "无法保持屏幕常亮：系统电源服务不可用。");
                return;
            }
            // SCREEN_BRIGHT_WAKE_LOCK is deprecated and some vendors ignore it; the runtime page
            // tells users the option may have no effect on their device.
            screenWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    getPackageName() + ":screen-on-session");
            screenWakeLock.setReferenceCounted(false);
        }
        if (!screenWakeLock.isHeld()) {
            screenWakeLock.acquire(timeoutMs);
        }
    }

    private void releaseScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            screenWakeLock.release();
        }
    }
}
