package com.example.videobrowseassistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** "运行管理" page: execution preferences, notification access and local diagnostics. */
final class RuntimePage {
    private final Activity activity;
    private final PageHost host;
    private final UiKit ui;
    private final SharedPreferences preferences;

    final LinearLayout settingsCard;
    final LinearLayout diagnosticsCard;
    private Switch autoStartSwitch;
    private Switch keepScreenSwitch;
    private TextView notificationState;
    private Button notificationButton;
    private TextView diagnosticState;

    RuntimePage(Activity activity, PageHost host) {
        this.activity = activity;
        this.host = host;
        this.ui = host.ui();
        this.preferences = host.preferences();
        settingsCard = buildSettingsCard();
        diagnosticsCard = buildDiagnosticsCard();
    }

    private LinearLayout buildSettingsCard() {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(R.string.runtime_card_title), ui.matchWrap(0));
        card.addView(ui.hint(R.string.runtime_card_intro), ui.matchWrap(7));

        autoStartSwitch = ui.toggle(R.string.runtime_auto_start,
                preferences.getBoolean(SessionKeys.AUTO_START, false));
        autoStartSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            preferences.edit().putBoolean(SessionKeys.AUTO_START, isChecked).apply();
            if (isChecked) {
                host.toast(R.string.toast_auto_start_enabled, false);
            }
        });
        card.addView(autoStartSwitch, ui.matchWrap(12));
        card.addView(ui.hint(R.string.runtime_auto_start_hint), ui.matchWrap(0));

        keepScreenSwitch = ui.toggle(R.string.runtime_keep_screen,
                preferences.getBoolean(SessionKeys.KEEP_SCREEN_ON, false));
        keepScreenSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            preferences.edit().putBoolean(SessionKeys.KEEP_SCREEN_ON, isChecked).apply();
            SessionCommands.dispatch(SessionCommands.Command.KEEP_SCREEN_CHANGED);
        });
        card.addView(keepScreenSwitch, ui.matchWrap(12));
        card.addView(ui.hint(R.string.runtime_keep_screen_hint), ui.matchWrap(0));

        card.addView(ui.text(R.string.runtime_notifications_label, 15, R.color.text_primary), ui.matchWrap(16));
        notificationState = ui.hint(R.string.status_checking);
        card.addView(notificationState, ui.matchWrap(4));
        notificationButton = ui.secondaryButton(R.string.action_enable_notifications);
        notificationButton.setMinHeight(ui.dp(44));
        notificationButton.setOnClickListener(v -> host.requestNotificationAccess());
        card.addView(notificationButton, ui.matchWrap(8));
        return card;
    }

    private LinearLayout buildDiagnosticsCard() {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(R.string.diagnostics_card_title), ui.matchWrap(0));
        card.addView(ui.hint(R.string.diagnostics_card_intro), ui.matchWrap(7));
        diagnosticState = ui.text(R.string.diagnostics_loading, 12, R.color.text_body);
        diagnosticState.setLineSpacing(0f, 1.15f);
        diagnosticState.setTextIsSelectable(true);
        diagnosticState.setBackgroundColor(ui.color(R.color.surface));
        diagnosticState.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        card.addView(diagnosticState, ui.matchWrap(12));

        LinearLayout actions = ui.row();
        Button copy = ui.secondaryButton(R.string.action_copy_diagnostics);
        copy.setMinHeight(ui.dp(44));
        copy.setOnClickListener(v -> confirmCopyDiagnostics());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, ui.dp(44), 1f);
        copyParams.rightMargin = ui.dp(8);
        actions.addView(copy, copyParams);
        Button clear = ui.secondaryButton(R.string.action_clear_diagnostics);
        clear.setMinHeight(ui.dp(44));
        clear.setOnClickListener(v -> {
            Diagnostics.clear(activity);
            refreshDiagnostics();
            host.toast(R.string.toast_diagnostics_cleared, false);
        });
        actions.addView(clear, new LinearLayout.LayoutParams(0, ui.dp(44), 1f));
        card.addView(actions, ui.matchWrap(12));
        return card;
    }

    /** Tells the user exactly what the copied text contains before it reaches the clipboard. */
    private void confirmCopyDiagnostics() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.diagnostics_copy_title)
                .setMessage(R.string.diagnostics_copy_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_copy, (dialog, which) -> copyDiagnostics())
                .show();
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Diagnostics.error(activity, "无法复制诊断记录：系统剪贴板不可用。");
            host.toast(R.string.toast_clipboard_unavailable, false);
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                activity.getString(R.string.diagnostics_clip_label), Diagnostics.all(activity)));
        Diagnostics.info(activity, "已复制运行诊断到系统剪贴板。");
        host.toast(R.string.toast_diagnostics_copied, false);
    }

    void refresh() {
        boolean autoStart = preferences.getBoolean(SessionKeys.AUTO_START, false);
        if (autoStartSwitch.isChecked() != autoStart) {
            autoStartSwitch.setChecked(autoStart);
        }
        boolean keepScreen = preferences.getBoolean(SessionKeys.KEEP_SCREEN_ON, false);
        if (keepScreenSwitch.isChecked() != keepScreen) {
            keepScreenSwitch.setChecked(keepScreen);
        }
        boolean notificationsEnabled = host.areNotificationsEnabled();
        notificationState.setText(notificationsEnabled
                ? R.string.runtime_notifications_on : R.string.runtime_notifications_off);
        UiKit.setVisible(notificationButton, !notificationsEnabled);
        refreshDiagnostics();
    }

    void refreshDiagnostics() {
        String recent = Diagnostics.recent(activity, 10);
        diagnosticState.setText(recent.isEmpty() ? activity.getString(R.string.diagnostics_empty) : recent);
    }
}
