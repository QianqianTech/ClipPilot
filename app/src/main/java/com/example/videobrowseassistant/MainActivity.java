package com.example.videobrowseassistant;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.List;

/**
 * Assembles the pages, owns navigation, lifecycle, permissions and session start/stop. Page
 * content lives in {@link TaskPage}, {@link InteractionPage}, {@link RuntimePage},
 * {@link StatsPage} and {@link StatusCards}.
 */
public class MainActivity extends Activity implements PageHost {
    enum Page {
        HOME(R.string.page_home),
        TASK(R.string.page_task),
        INTERACTION(R.string.page_interaction),
        RUNTIME(R.string.page_runtime),
        STATS(R.string.page_stats);

        final int titleRes;

        Page(int titleRes) {
            this.titleRes = titleRes;
        }
    }

    private static final String STATE_PAGE = "main.page";
    /** Literal instead of Manifest.permission.POST_NOTIFICATIONS (API 33) to avoid InlinedApi. */
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 41;
    private static final long AUTO_START_DELAY_MS = 1200L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private UiKit ui;
    private Page currentPage = Page.HOME;
    private TextView pageTitle;
    private Button backButton;
    private LinearLayout boundaryCard;
    private LinearLayout navigationCard;
    private StatusCards statusCards;
    private TaskPage taskPage;
    private InteractionPage interactionPage;
    private RuntimePage runtimePage;
    private StatsPage statsPage;
    private Boolean lastKnownServiceEnabled;
    private boolean autoStartAttempted;
    private boolean autoStartScheduled;
    private boolean startPendingNotificationPermission;
    private Object backCallback;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (sharedPreferences, key) -> {
                // UI refresh only; the service is driven by SessionCommands, not by this listener.
                if (Diagnostics.KEY_ENTRIES.equals(key)) {
                    runOnUiThread(() -> runtimePage.refreshDiagnostics());
                } else if (key != null && isUiKey(key)) {
                    runOnUiThread(this::refreshAll);
                }
            };

    private static boolean isUiKey(String key) {
        return SessionKeys.SESSION_RUNNING.equals(key)
                || SessionKeys.ACTIVE_TASK_INDEX.equals(key)
                || SessionKeys.ACTIVE_TASK_VIDEO_COUNT.equals(key)
                || SessionKeys.COMPLETED_TASK_COUNT.equals(key)
                || SessionKeys.SESSION_SUMMARY.equals(key)
                || key.startsWith("stats_")
                || key.startsWith("content_targeting_active")
                || key.startsWith("content_targeting_last")
                || key.startsWith("comment_capture")
                || key.startsWith("comment_context")
                || key.equals(InteractionSession.KEY_COMMENT_PAUSED);
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(SessionKeys.PREFS, MODE_PRIVATE);
        ui = new UiKit(this);
        Diagnostics.initialize(this);
        if (savedInstanceState != null) {
            currentPage = pageFrom(savedInstanceState.getString(STATE_PAGE));
        }
        setContentView(buildContent());
        interactionPage.restoreState(savedInstanceState);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        normalizeSessionState();
        applyPageVisibility();
        refreshAll();
        scheduleAutoStartIfArmed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        normalizeSessionState();
        refreshAll();
        scheduleAutoStartIfArmed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PAGE, currentPage.name());
        interactionPage.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        interactionPage.shutdown();
        unregisterBackCallback();
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        super.onDestroy();
    }

    /** Pre-Android 13 back handling; API 33+ uses {@link OnBackInvokedDispatcher}. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (currentPage != Page.HOME) {
            navigateTo(Page.HOME);
            return;
        }
        super.onBackPressed();
    }

    /** Visible for Robolectric tests. */
    Page currentPage() {
        return currentPage;
    }

    private static Page pageFrom(String name) {
        if (name != null) {
            for (Page page : Page.values()) {
                if (page.name().equals(name)) {
                    return page;
                }
            }
        }
        return Page.HOME;
    }

    // ---- Layout ------------------------------------------------------------------------------

    private ScrollView buildContent() {
        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.color(R.color.surface));
        int horizontal = ui.dp(20);
        int top = ui.dp(18);
        int bottom = ui.dp(28);
        root.setPadding(horizontal, top, horizontal, bottom);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(ui.color(R.color.surface));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            applyEdgeToEdge(scrollView, root, horizontal, top, bottom);
        }

        HomeCards home = new HomeCards(this, this);
        root.addView(home.header, ui.matchWrap(0));

        LinearLayout pageHeader = ui.row();
        pageTitle = ui.sectionTitle(currentPage.titleRes);
        pageHeader.addView(pageTitle, ui.weighted());
        backButton = ui.secondaryButton(R.string.action_back_home);
        backButton.setMinHeight(ui.dp(40));
        backButton.setOnClickListener(v -> navigateTo(Page.HOME));
        pageHeader.addView(backButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        root.addView(pageHeader, ui.matchWrap(16));

        boundaryCard = home.boundaryCard;
        root.addView(boundaryCard, ui.matchWrap(18));
        navigationCard = home.navigationCard;
        root.addView(navigationCard, ui.matchWrap(12));

        statusCards = new StatusCards(this, this);
        taskPage = new TaskPage(this, this);
        interactionPage = new InteractionPage(this, this);
        runtimePage = new RuntimePage(this, this);
        statsPage = new StatsPage(this, this);

        root.addView(statusCards.serviceCard, ui.matchWrap(12));
        root.addView(runtimePage.settingsCard, ui.matchWrap(12));
        root.addView(taskPage.settingsCard, ui.matchWrap(12));
        root.addView(taskPage.targetingCard, ui.matchWrap(12));
        root.addView(interactionPage.commentCard, ui.matchWrap(12));
        root.addView(interactionPage.gateway.card, ui.matchWrap(12));
        root.addView(statusCards.sessionCard, ui.matchWrap(12));
        root.addView(statsPage.card, ui.matchWrap(12));
        root.addView(runtimePage.diagnosticsCard, ui.matchWrap(12));

        root.addView(home.footer, ui.matchWrap(20));
        return scrollView;
    }

    /**
     * Android 15+ always draws apps edge-to-edge when targeting SDK 35+. Opt in from Android 11
     * for consistent behavior and pad the content by the system bar and cutout insets so the
     * header and the last buttons are never covered.
     */
    @TargetApi(Build.VERSION_CODES.R)
    @SuppressWarnings("deprecation")
    private void applyEdgeToEdge(ScrollView scrollView, LinearLayout root,
                                 int horizontal, int top, int bottom) {
        getWindow().setDecorFitsSystemWindows(false);
        scrollView.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            root.setPadding(horizontal + bars.left, top + bars.top,
                    horizontal + bars.right, bottom + bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    // ---- Navigation --------------------------------------------------------------------------

    @Override
    public void navigateTo(Page page) {
        currentPage = page == null ? Page.HOME : page;
        applyPageVisibility();
        refreshAll();
    }

    private void applyPageVisibility() {
        pageTitle.setText(currentPage.titleRes);
        boolean home = currentPage == Page.HOME;
        UiKit.setVisible(backButton, !home);
        UiKit.setVisible(boundaryCard, home);
        UiKit.setVisible(navigationCard, home);
        UiKit.setVisible(statusCards.serviceCard, home || currentPage == Page.RUNTIME);
        UiKit.setVisible(statusCards.sessionCard, home || currentPage == Page.RUNTIME);
        UiKit.setVisible(runtimePage.settingsCard, currentPage == Page.RUNTIME);
        UiKit.setVisible(runtimePage.diagnosticsCard, currentPage == Page.RUNTIME);
        UiKit.setVisible(taskPage.settingsCard, currentPage == Page.TASK);
        UiKit.setVisible(taskPage.targetingCard, currentPage == Page.TASK);
        UiKit.setVisible(interactionPage.commentCard, currentPage == Page.INTERACTION);
        UiKit.setVisible(interactionPage.gateway.card, currentPage == Page.INTERACTION);
        UiKit.setVisible(statsPage.card, home || currentPage == Page.STATS);
        statsPage.setDetailed(currentPage == Page.STATS);
        updateBackCallback();
    }

    /** On API 33+ intercept back only while a sub-page is shown, so home still exits normally. */
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (currentPage == Page.HOME) {
            unregisterBackCallback();
        } else if (backCallback == null) {
            registerBackCallback();
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void registerBackCallback() {
        OnBackInvokedCallback callback = () -> navigateTo(Page.HOME);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        backCallback = callback;
    }

    private void unregisterBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            unregisterBackCallbackApi33();
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void unregisterBackCallbackApi33() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
        backCallback = null;
    }

    // ---- PageHost ----------------------------------------------------------------------------

    @Override
    public SharedPreferences preferences() {
        return preferences;
    }

    @Override
    public UiKit ui() {
        return ui;
    }

    @Override
    public void toast(int stringRes, boolean longDuration, Object... args) {
        Toast.makeText(this, ui.string(stringRes, args),
                longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    @Override
    public void refreshAll() {
        if (statusCards == null) {
            return;
        }
        boolean serviceEnabled = isAccessibilityServiceEnabled();
        if (lastKnownServiceEnabled == null || lastKnownServiceEnabled != serviceEnabled) {
            Diagnostics.info(this, serviceEnabled
                    ? "无障碍服务状态：已开启。"
                    : "无障碍服务状态：未开启。自动上滑无法执行，需在系统设置中手动开启服务。");
            lastKnownServiceEnabled = serviceEnabled;
        }
        InteractionSession.clearExpired(preferences, System.currentTimeMillis());
        statusCards.refresh(serviceEnabled, SessionStore.isRunning(preferences),
                StatusCards.sessionSummary(this, preferences));
        taskPage.refresh();
        interactionPage.refresh();
        runtimePage.refresh();
        statsPage.refresh();
    }

    @Override
    public boolean isAccessibilityServiceEnabled() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null) {
            return false;
        }
        ComponentName expected = new ComponentName(this, AutoSwipeAccessibilityService.class);
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() == null) {
                continue;
            }
            ServiceInfo serviceInfo = info.getResolveInfo().serviceInfo;
            if (serviceInfo != null && expected.equals(
                    new ComponentName(serviceInfo.packageName, serviceInfo.name))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void openAccessibilitySettings() {
        String registrationIssue = StatusCards.registrationIssue(this);
        if (registrationIssue != null) {
            Diagnostics.error(this, "无法引导开启无障碍服务：" + registrationIssue);
            toast(R.string.toast_service_config_failed, true);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Diagnostics.info(this, "已打开系统“无障碍”总页面。请向下找到“已下载的应用”或“已安装的服务”，选择“视频浏览助手无障碍服务”并开启。");
            toast(R.string.toast_find_service, true);
        } catch (ActivityNotFoundException exception) {
            Diagnostics.error(this, "无法打开系统无障碍设置：" + exception.getClass().getSimpleName() + "。");
            toast(R.string.toast_open_accessibility_failed, true);
        }
    }

    @Override
    public void openAppDetailsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
            Diagnostics.info(this, "已打开本应用详情。若服务未显示或开关不可点，请在右上角更多菜单中查找“允许受限设置”，完成后返回无障碍页面重试。");
            toast(R.string.toast_restricted_settings, true);
        } catch (ActivityNotFoundException exception) {
            Diagnostics.error(this, "无法打开本应用详情：" + exception.getClass().getSimpleName() + "。");
            toast(R.string.toast_open_details_failed, true);
        }
    }

    @Override
    public boolean areNotificationsEnabled() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    @Override
    public void requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && shouldAskNotificationPermission()) {
            requestPermissions(new String[]{POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        // Permission was denied permanently or notifications are blocked in settings.
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (ActivityNotFoundException exception) {
            openAppDetailsSettings();
        }
    }

    /** The system dialog can be shown before the first answer and after a single denial. */
    private boolean shouldAskNotificationPermission() {
        String key = "notification_permission_requested";
        boolean askedBefore = preferences.getBoolean(key, false);
        preferences.edit().putBoolean(key, true).apply();
        return !askedBefore || shouldShowRequestPermissionRationale(POST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Diagnostics.info(this, granted ? "通知权限已授予。" : "通知权限未授予；通知栏的停止和评论入口不可用。");
        if (!granted) {
            toast(R.string.toast_notifications_denied, true);
        }
        if (startPendingNotificationPermission) {
            startPendingNotificationPermission = false;
            // Start regardless of the answer; the session works without the notification.
            startSession(false);
        }
        refreshAll();
    }

    // ---- Session -----------------------------------------------------------------------------

    @Override
    public void performPrimaryAction() {
        if (SessionStore.isRunning(preferences)) {
            stopSession();
        } else {
            startSession(true);
        }
    }

    /**
     * @param allowPermissionPrompt {@code false} when called back from the permission result, so
     *                              a denial never leads to a second prompt for the same start.
     */
    private void startSession(boolean allowPermissionPrompt) {
        if (!isAccessibilityServiceEnabled()) {
            Diagnostics.error(this, "无法启动会话：无障碍服务未开启。");
            toast(R.string.toast_enable_service_first, true);
            openAccessibilitySettings();
            return;
        }
        List<String> queue = taskPage.orderedSelectedPackages();
        if (queue.isEmpty()) {
            Diagnostics.error(this, "无法启动会话：没有勾选可用的平台。");
            toast(R.string.toast_select_platform_first, true);
            return;
        }
        ContentTargeting.Config targeting = ContentTargeting.savedConfig(preferences);
        if (targeting.enabled && !preferences.getBoolean(ContentTargeting.KEY_DISCLOSURE_ACCEPTED, false)) {
            Diagnostics.error(this, "无法启动内容定向：尚未完成内容读取披露确认。");
            toast(R.string.toast_targeting_consent_required, true);
            return;
        }
        if (targeting.enabled && !targeting.isReady()) {
            Diagnostics.error(this, "无法启动内容定向：未填写可用的想看方向。");
            toast(R.string.toast_targeting_terms_required, true);
            return;
        }
        // Ask for notification permission before launching the target app; otherwise the
        // permission dialog and the target app compete for the foreground and the user loses
        // the notification controls for the first session.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && allowPermissionPrompt
                && shouldAskNotificationPermission()) {
            startPendingNotificationPermission = true;
            Diagnostics.info(this, "启动前请求通知权限，授权结果返回后再启动会话。");
            requestPermissions(new String[]{POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        autoStartAttempted = true;
        interactionPage.cancelPendingGatewayRequest();
        int quota = taskPage.videosPerApp();
        long startedAt = System.currentTimeMillis();
        SessionStore.start(preferences, queue, quota, startedAt, targeting,
                getString(targeting.isReady() ? R.string.summary_starting_targeted
                        : R.string.summary_starting));
        SessionCommands.dispatch(SessionCommands.Command.SESSION_STARTED);
        String first = queue.get(0);
        Diagnostics.info(this, "多平台会话已创建：" + queue.size() + " 个平台，每个平台 " + quota
                + " 个视频，上滑时间随机 10–30 秒。"
                + (targeting.isReady() ? "内容定向已冻结，最多扫描 "
                + ContentTargeting.maxScansForQuota(quota) + " 条。" : "随机节奏模式。")
                + "正在打开 " + Platform.labelFor(first) + "。");
        if (!launchPlatform(this, first)) {
            SessionStore.stop(preferences, getString(R.string.summary_start_failed), false,
                    System.currentTimeMillis());
            SessionCommands.dispatch(SessionCommands.Command.SESSION_STOPPED);
            Diagnostics.error(this, "会话已取消：第一个平台无法打开。请确认应用未被停用或受系统限制。");
            toast(R.string.toast_first_platform_failed, true);
            refreshAll();
            return;
        }
        toast(R.string.toast_session_started, false);
        navigateTo(Page.HOME);
    }

    @Override
    public void stopSession() {
        autoStartAttempted = true;
        interactionPage.cancelPendingGatewayRequest();
        SessionStore.stop(preferences, getString(R.string.summary_stopped_by_user), false,
                System.currentTimeMillis());
        SessionCommands.dispatch(SessionCommands.Command.SESSION_STOPPED);
        interactionPage.clearTransientUi();
        Diagnostics.info(this, "用户在应用内停止了多平台任务。");
        toast(R.string.toast_session_stopped, false);
        refreshAll();
    }

    /** Launches a supported platform's main activity; returns {@code false} when it cannot. */
    static boolean launchPlatform(Context context, String packageName) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Diagnostics.error(context, "无法打开 " + Platform.labelFor(packageName) + "：系统没有提供启动入口。");
            Toast.makeText(context, R.string.toast_no_launch_entry, Toast.LENGTH_SHORT).show();
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
            Diagnostics.info(context, "已请求打开平台：" + Platform.labelFor(packageName) + "。");
            return true;
        } catch (ActivityNotFoundException | SecurityException exception) {
            Diagnostics.error(context, "无法打开 " + Platform.labelFor(packageName) + "："
                    + exception.getClass().getSimpleName() + "。");
            Toast.makeText(context, R.string.toast_open_platform_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Stops a persisted session that can no longer be running (expired or service disabled). */
    private void normalizeSessionState() {
        if (!SessionStore.isRunning(preferences)) {
            return;
        }
        boolean expired = SessionStore.isExpired(preferences, System.currentTimeMillis());
        if (!expired && isAccessibilityServiceEnabled()) {
            return;
        }
        Diagnostics.error(this, expired
                ? "会话已停止：会话时间无效或已达到 30 分钟上限。"
                : "会话已停止：无障碍服务未开启或已被系统关闭。");
        SessionStore.stop(preferences, getString(expired ? R.string.summary_expired
                : R.string.summary_service_off), false, System.currentTimeMillis());
        SessionCommands.dispatch(SessionCommands.Command.SESSION_STOPPED);
        interactionPage.clearTransientUi();
    }

    private void scheduleAutoStartIfArmed() {
        if (autoStartAttempted || autoStartScheduled
                || !preferences.getBoolean(SessionKeys.AUTO_START, false)
                || SessionStore.isRunning(preferences)
                || !isAccessibilityServiceEnabled()) {
            return;
        }
        autoStartScheduled = true;
        mainHandler.postDelayed(() -> {
            autoStartScheduled = false;
            if (isFinishing() || autoStartAttempted) {
                return;
            }
            autoStartAttempted = true;
            if (taskPage.orderedSelectedPackages().isEmpty()) {
                Diagnostics.error(this, "自动执行失败：保存的多平台任务中没有可用应用。");
                toast(R.string.toast_saved_platforms_unavailable, true);
                return;
            }
            startSession(true);
        }, AUTO_START_DELAY_MS);
    }
}
