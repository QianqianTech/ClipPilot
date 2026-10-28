package com.example.videobrowseassistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Accessibility-service status and session status cards, shown on the home and runtime pages.
 */
final class StatusCards {
    private final PageHost host;
    private final UiKit ui;
    private final Context context;

    final LinearLayout serviceCard;
    final LinearLayout sessionCard;
    private TextView serviceState;
    private TextView serviceGuide;
    private TextView serviceDot;
    private Button appDetailsButton;
    private TextView sessionState;
    private TextView sessionDot;
    private TextView notificationWarning;
    private Button notificationButton;

    StatusCards(Context context, PageHost host) {
        this.context = context;
        this.host = host;
        this.ui = host.ui();
        serviceCard = buildServiceCard();
        sessionCard = buildSessionCard();
    }

    private LinearLayout buildServiceCard() {
        LinearLayout card = ui.card();
        LinearLayout header = ui.row();
        serviceDot = ui.statusDot(R.color.status_idle);
        header.addView(serviceDot, dotParams());
        LinearLayout copy = ui.column();
        copy.addView(ui.text(R.string.service_card_title, 13, R.color.text_secondary), ui.matchWrap(0));
        serviceState = ui.text(R.string.status_checking, 16, R.color.text_primary);
        serviceState.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        serviceState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        copy.addView(serviceState, ui.matchWrap(3));
        header.addView(copy, ui.weighted());
        Button enableButton = ui.secondaryButton(R.string.action_enable_service);
        enableButton.setMinWidth(ui.dp(88));
        enableButton.setMinHeight(ui.dp(42));
        enableButton.setOnClickListener(v -> host.openAccessibilitySettings());
        header.addView(enableButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(42)));
        card.addView(header, ui.matchWrap(0));
        serviceGuide = ui.text(R.string.service_guide_checking, 12, R.color.text_secondary);
        serviceGuide.setLineSpacing(0f, 1.15f);
        card.addView(serviceGuide, ui.matchWrap(10));
        appDetailsButton = ui.secondaryButton(R.string.action_open_app_details);
        appDetailsButton.setMinHeight(ui.dp(44));
        appDetailsButton.setOnClickListener(v -> host.openAppDetailsSettings());
        card.addView(appDetailsButton, ui.matchWrap(10));
        return card;
    }

    private LinearLayout buildSessionCard() {
        LinearLayout card = ui.card();
        LinearLayout header = ui.row();
        sessionDot = ui.statusDot(R.color.status_idle);
        header.addView(sessionDot, dotParams());
        LinearLayout copy = ui.column();
        copy.addView(ui.text(R.string.session_card_title, 13, R.color.text_secondary), ui.matchWrap(0));
        sessionState = ui.text(R.string.session_idle, 16, R.color.text_primary);
        sessionState.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        sessionState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        copy.addView(sessionState, ui.matchWrap(3));
        header.addView(copy, ui.weighted());
        card.addView(header, ui.matchWrap(0));

        notificationWarning = ui.text(R.string.notification_disabled_warning, 12, R.color.status_error_text);
        notificationWarning.setLineSpacing(0f, 1.12f);
        card.addView(notificationWarning, ui.matchWrap(12));
        notificationButton = ui.secondaryButton(R.string.action_enable_notifications);
        notificationButton.setMinHeight(ui.dp(44));
        notificationButton.setOnClickListener(v -> host.requestNotificationAccess());
        card.addView(notificationButton, ui.matchWrap(8));

        Button stopButton = ui.secondaryButton(R.string.action_stop_session);
        stopButton.setMinHeight(ui.dp(46));
        stopButton.setOnClickListener(v -> host.stopSession());
        card.addView(stopButton, ui.matchWrap(18));
        return card;
    }

    private LinearLayout.LayoutParams dotParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10));
        params.gravity = Gravity.CENTER_VERTICAL;
        params.rightMargin = ui.dp(10);
        return params;
    }

    void refresh(boolean serviceEnabled, boolean running, String sessionText) {
        serviceState.setText(serviceEnabled ? R.string.service_enabled : R.string.service_disabled);
        serviceState.setTextColor(ui.color(serviceEnabled ? R.color.status_ok_text : R.color.status_error_text));
        ui.updateStatusDot(serviceDot, serviceEnabled ? R.color.status_ok : R.color.status_error);
        refreshGuide(serviceEnabled);

        sessionState.setText(sessionText);
        sessionState.setTextColor(ui.color(running ? R.color.status_ok_text : R.color.text_primary));
        ui.updateStatusDot(sessionDot, running ? R.color.status_ok : R.color.status_idle);
        boolean notificationsEnabled = host.areNotificationsEnabled();
        UiKit.setVisible(notificationWarning, !notificationsEnabled);
        UiKit.setVisible(notificationButton, !notificationsEnabled);
    }

    private void refreshGuide(boolean serviceEnabled) {
        if (serviceEnabled) {
            serviceGuide.setText(R.string.service_guide_enabled);
            UiKit.setVisible(appDetailsButton, false);
            return;
        }
        String registrationIssue = registrationIssue(context);
        if (registrationIssue != null) {
            serviceGuide.setText(ui.string(R.string.service_registration_issue, registrationIssue));
        } else {
            serviceGuide.setText(R.string.service_guide_disabled);
        }
        UiKit.setVisible(appDetailsButton, true);
    }

    /** Returns why the bundled service cannot be enabled, or {@code null} when it is declared correctly. */
    static String registrationIssue(Context context) {
        ComponentName component = new ComponentName(context, AutoSwipeAccessibilityService.class);
        try {
            ServiceInfo serviceInfo = serviceInfo(context, component);
            if (!serviceInfo.enabled) {
                return context.getString(R.string.registration_issue_disabled);
            }
            if (!"android.permission.BIND_ACCESSIBILITY_SERVICE".equals(serviceInfo.permission)) {
                return context.getString(R.string.registration_issue_permission);
            }
            if (serviceInfo.metaData == null
                    || !serviceInfo.metaData.containsKey("android.accessibilityservice")) {
                return context.getString(R.string.registration_issue_metadata);
            }
            return null;
        } catch (PackageManager.NameNotFoundException exception) {
            return context.getString(R.string.registration_issue_missing,
                    component.flattenToShortString());
        }
    }

    private static ServiceInfo serviceInfo(Context context, ComponentName component)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.getPackageManager().getServiceInfo(component,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA));
        }
        return context.getPackageManager().getServiceInfo(component, PackageManager.GET_META_DATA);
    }

    /** Session text for the status card. */
    static String sessionSummary(Context context, SharedPreferences preferences) {
        if (!SessionStore.isRunning(preferences)) {
            String summary = preferences.getString(SessionKeys.SESSION_SUMMARY, "");
            return summary.isEmpty()
                    ? context.getString(R.string.session_idle)
                    : context.getString(R.string.session_idle_with_summary, summary);
        }
        SessionStore.Progress progress = SessionStore.progress(preferences);
        if (progress.isEmpty()) {
            return context.getString(R.string.session_preparing);
        }
        String platform = TaskPage.platformName(context, progress.activePackage());
        if (ContentTargeting.isSessionEnabled(preferences)) {
            int scans = Math.max(0, preferences.getInt(ContentTargeting.KEY_ACTIVE_SCANS, 0));
            int maximum = Math.max(1, preferences.getInt(ContentTargeting.KEY_SESSION_MAX_SCANS,
                    ContentTargeting.maxScansForQuota(progress.quota)));
            return context.getString(R.string.session_progress_targeting, platform,
                    progress.activeVideos, progress.quota, scans, maximum,
                    progress.activeIndex + 1, progress.queue.size());
        }
        return context.getString(R.string.session_progress, platform,
                progress.activeVideos, progress.quota,
                progress.activeIndex + 1, progress.queue.size());
    }
}
