package com.example.videobrowseassistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** "任务配置" page: platform queue, per-platform quota, content targeting and the start button. */
final class TaskPage {
    private static final int MIN_VIDEOS = SessionTaskQueue.MIN_TASK_QUOTA;
    private static final int MAX_VIDEOS = SessionTaskQueue.MAX_TASK_QUOTA;

    private final Activity activity;
    private final PageHost host;
    private final UiKit ui;
    private final SharedPreferences preferences;
    private final Set<String> checkedPackages = new LinkedHashSet<>();

    final LinearLayout settingsCard;
    final LinearLayout targetingCard;
    private Button targetSelectionButton;
    private TextView targetSummary;
    private SeekBar quotaSeekBar;
    private TextView quotaValue;
    private Button primaryActionButton;
    private Switch targetingSwitch;
    private EditText includeTermsInput;
    private EditText excludeTermsInput;
    private TextView targetingState;
    private boolean restoringTargetingControls;

    TaskPage(Activity activity, PageHost host) {
        this.activity = activity;
        this.host = host;
        this.ui = host.ui();
        this.preferences = host.preferences();
        restoreSelection();
        settingsCard = buildSettingsCard();
        targetingCard = buildTargetingCard();
    }

    // ---- Views -------------------------------------------------------------------------------

    private LinearLayout buildSettingsCard() {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(R.string.task_card_title), ui.matchWrap(0));
        card.addView(ui.hint(R.string.task_card_intro), ui.matchWrap(7));
        card.addView(ui.text(R.string.task_platform_label, 13, R.color.text_secondary), ui.matchWrap(14));

        targetSelectionButton = ui.secondaryButton(R.string.action_select_platforms);
        targetSelectionButton.setMinHeight(ui.dp(48));
        targetSelectionButton.setOnClickListener(v -> showSelectionDialog());
        card.addView(targetSelectionButton, ui.matchWrap(8));

        LinearLayout groupActions = ui.row();
        Button domestic = ui.compactButton(R.string.action_select_domestic);
        domestic.setOnClickListener(v -> selectGroup(false));
        groupActions.addView(domestic, ui.equalActionParams(5));
        Button international = ui.compactButton(R.string.action_select_international);
        international.setOnClickListener(v -> selectGroup(true));
        groupActions.addView(international, ui.equalActionParams(5));
        Button clear = ui.compactButton(R.string.action_clear_selection);
        clear.setOnClickListener(v -> clearSelection());
        groupActions.addView(clear, ui.equalActionParams(0));
        card.addView(groupActions, ui.matchWrap(8));

        targetSummary = ui.badge(R.string.task_summary_loading, 12);
        card.addView(targetSummary, ui.matchWrap(8));

        LinearLayout quotaHeader = ui.row();
        quotaHeader.addView(ui.text(R.string.task_quota_label, 13, R.color.text_secondary), ui.weighted());
        quotaValue = ui.text("", 13, R.color.brand_blue);
        quotaValue.setBackgroundResource(R.drawable.bg_badge);
        quotaValue.setPadding(ui.dp(9), ui.dp(5), ui.dp(9), ui.dp(5));
        quotaHeader.addView(quotaValue, ui.wrap());
        card.addView(quotaHeader, ui.matchWrap(18));

        quotaSeekBar = new SeekBar(activity);
        quotaSeekBar.setMax(MAX_VIDEOS - MIN_VIDEOS);
        quotaSeekBar.setContentDescription(activity.getString(R.string.task_quota_label));
        int savedQuota = SessionTaskQueue.clampQuota(
                preferences.getInt(SessionKeys.TASK_VIDEO_COUNT, SessionKeys.DEFAULT_VIDEOS_PER_APP),
                MIN_VIDEOS, MAX_VIDEOS);
        quotaSeekBar.setProgress(savedQuota - MIN_VIDEOS);
        quotaValue.setText(ui.string(R.string.videos_per_app, savedQuota));
        quotaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int videos = MIN_VIDEOS + progress;
                quotaValue.setText(ui.string(R.string.videos_per_app, videos));
                if (fromUser) {
                    preferences.edit().putInt(SessionKeys.TASK_VIDEO_COUNT, videos).apply();
                    refresh();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        card.addView(quotaSeekBar, ui.matchWrap(3));
        card.addView(ui.text(R.string.task_quota_range, 12, R.color.text_secondary), ui.matchWrap(2));

        LinearLayout intervalHeader = ui.row();
        intervalHeader.addView(ui.text(R.string.task_interval_label, 13, R.color.text_secondary), ui.weighted());
        TextView intervalValue = ui.text(R.string.task_interval_value, 13, R.color.brand_blue);
        intervalValue.setBackgroundResource(R.drawable.bg_badge);
        intervalValue.setPadding(ui.dp(9), ui.dp(5), ui.dp(9), ui.dp(5));
        intervalHeader.addView(intervalValue, ui.wrap());
        card.addView(intervalHeader, ui.matchWrap(18));
        card.addView(ui.hint(R.string.task_interval_hint), ui.matchWrap(3));

        primaryActionButton = ui.primaryButton(R.string.primary_enable_service);
        primaryActionButton.setMinHeight(ui.dp(54));
        primaryActionButton.setOnClickListener(v -> host.performPrimaryAction());
        card.addView(primaryActionButton, ui.matchWrap(14));
        card.addView(ui.hint(R.string.task_start_hint), ui.matchWrap(7));

        Button openFirst = ui.secondaryButton(R.string.action_open_first_platform);
        openFirst.setMinHeight(ui.dp(46));
        openFirst.setOnClickListener(v -> openFirstSelected());
        card.addView(openFirst, ui.matchWrap(10));
        return card;
    }

    private LinearLayout buildTargetingCard() {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(R.string.targeting_card_title), ui.matchWrap(0));
        card.addView(ui.hint(R.string.targeting_card_intro), ui.matchWrap(7));

        targetingSwitch = ui.toggle(R.string.targeting_switch,
                preferences.getBoolean(ContentTargeting.KEY_ENABLED, false));
        targetingSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            if (restoringTargetingControls) {
                return;
            }
            if (isChecked && !preferences.getBoolean(ContentTargeting.KEY_DISCLOSURE_ACCEPTED, false)) {
                setTargetingSwitch(false);
                showConsentDialog();
                return;
            }
            preferences.edit().putBoolean(ContentTargeting.KEY_ENABLED, isChecked).apply();
            if (isChecked) {
                host.toast(R.string.toast_targeting_enabled, true);
            }
            refresh();
        });
        card.addView(targetingSwitch, ui.matchWrap(12));

        card.addView(ui.text(R.string.targeting_include_label, 13, R.color.text_secondary), ui.matchWrap(8));
        includeTermsInput = termsInput(R.string.targeting_include_hint,
                ContentTargeting.KEY_INCLUDE_TERMS);
        card.addView(includeTermsInput, ui.matchWrap(3));

        card.addView(ui.text(R.string.targeting_exclude_label, 13, R.color.text_secondary), ui.matchWrap(10));
        excludeTermsInput = termsInput(R.string.targeting_exclude_hint,
                ContentTargeting.KEY_EXCLUDE_TERMS);
        card.addView(excludeTermsInput, ui.matchWrap(3));

        targetingState = ui.badge(R.string.content_targeting_disabled, 12);
        card.addView(targetingState, ui.matchWrap(12));
        return card;
    }

    private EditText termsInput(int hintRes, String preferenceKey) {
        EditText input = ui.editText(hintRes, true);
        input.setText(preferences.getString(preferenceKey, ""));
        input.addTextChangedListener(UiKit.afterChange(value -> {
            preferences.edit().putString(preferenceKey, ContentTargeting.sanitizeTerms(value)).apply();
            refresh();
        }));
        return input;
    }

    private void setTargetingSwitch(boolean checked) {
        restoringTargetingControls = true;
        targetingSwitch.setChecked(checked);
        restoringTargetingControls = false;
    }

    private void showConsentDialog() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.targeting_consent_title)
                .setMessage(R.string.targeting_consent_message)
                .setNegativeButton(R.string.action_not_now, null)
                .setPositiveButton(R.string.action_accept_and_enable, (dialog, which) -> {
                    preferences.edit()
                            .putBoolean(ContentTargeting.KEY_DISCLOSURE_ACCEPTED, true)
                            .putBoolean(ContentTargeting.KEY_ENABLED, true)
                            .apply();
                    setTargetingSwitch(true);
                    host.toast(R.string.toast_targeting_consented, true);
                    refresh();
                })
                .show();
    }

    // ---- Platform selection ------------------------------------------------------------------

    static boolean isInstalled(Context context, Platform platform) {
        return context.getPackageManager().getLaunchIntentForPackage(platform.packageName) != null;
    }

    static String platformName(Context context, String packageName) {
        Platform platform = Platform.fromPackage(packageName);
        return platform == null ? context.getString(R.string.platform_unknown)
                : context.getString(platform.labelRes);
    }

    private String dialogLabel(Platform platform, boolean installed) {
        String name = activity.getString(platform.labelRes);
        if (!installed) {
            return activity.getString(R.string.platform_not_installed, name);
        }
        try {
            PackageManager packageManager = activity.getPackageManager();
            CharSequence actual = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(platform.packageName, 0));
            return activity.getString(R.string.platform_dialog_row, name, actual,
                    activity.getString(platform.hintRes));
        } catch (PackageManager.NameNotFoundException ignored) {
            return name;
        }
    }

    /** Restores the saved queue order, migrating the 0.2.x single-target key on first run. */
    private void restoreSelection() {
        List<String> saved = SessionTaskQueue.parse(
                preferences.getString(SessionKeys.SELECTED_PACKAGES, ""));
        if (saved.isEmpty()) {
            String legacyTarget = preferences.getString(SessionKeys.TARGET_PACKAGE, "");
            if (!legacyTarget.isEmpty()) {
                saved.add(legacyTarget);
            } else {
                // First run keeps domestic platforms selected; international ones are opt-in.
                for (Platform platform : Platform.values()) {
                    if (!platform.international && isInstalled(activity, platform)) {
                        saved.add(platform.packageName);
                    }
                }
            }
        }
        checkedPackages.clear();
        for (String packageName : saved) {
            Platform platform = Platform.fromPackage(packageName);
            if (platform != null && isInstalled(activity, platform)) {
                checkedPackages.add(packageName);
            }
        }
        persistSelection();
    }

    private void persistSelection() {
        preferences.edit()
                .putString(SessionKeys.SELECTED_PACKAGES,
                        SessionTaskQueue.serialize(orderedSelectedPackages()))
                .apply();
    }

    /** Installed, checked packages in the user's saved order (new picks appended). */
    List<String> orderedSelectedPackages() {
        List<String> savedOrder = SessionTaskQueue.parse(
                preferences.getString(SessionKeys.SELECTED_PACKAGES, ""));
        List<String> checked = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            if (checkedPackages.contains(platform.packageName) && isInstalled(activity, platform)) {
                checked.add(platform.packageName);
            }
        }
        return SessionTaskQueue.orderSelected(savedOrder, checked);
    }

    private void applySelection(Set<String> packages) {
        checkedPackages.clear();
        checkedPackages.addAll(packages);
        persistSelection();
        refresh();
    }

    private void selectGroup(boolean international) {
        Set<String> next = new LinkedHashSet<>(checkedPackages);
        for (Platform platform : Platform.values()) {
            if (platform.international == international && isInstalled(activity, platform)) {
                next.add(platform.packageName);
            }
        }
        applySelection(next);
        host.toast(international ? R.string.toast_selected_international
                : R.string.toast_selected_domestic, false);
    }

    private void clearSelection() {
        applySelection(new LinkedHashSet<>());
        host.toast(R.string.toast_selection_cleared, false);
    }

    private void showSelectionDialog() {
        Platform[] platforms = Platform.values();
        String[] labels = new String[platforms.length];
        boolean[] checked = new boolean[platforms.length];
        boolean[] installed = new boolean[platforms.length];
        for (int index = 0; index < platforms.length; index++) {
            installed[index] = isInstalled(activity, platforms[index]);
            labels[index] = dialogLabel(platforms[index], installed[index]);
            checked[index] = installed[index] && checkedPackages.contains(platforms[index].packageName);
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.action_select_platforms)
                .setMultiChoiceItems(labels, checked, (selectionDialog, which, isChecked) -> {
                    if (!installed[which]) {
                        ((AlertDialog) selectionDialog).getListView().setItemChecked(which, false);
                        host.toast(R.string.toast_platform_not_installed, false);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply_selection, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Set<String> next = new LinkedHashSet<>();
                    for (int index = 0; index < platforms.length; index++) {
                        if (installed[index] && dialog.getListView().isItemChecked(index)) {
                            next.add(platforms[index].packageName);
                        }
                    }
                    applySelection(next);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void openFirstSelected() {
        List<String> selected = orderedSelectedPackages();
        if (selected.isEmpty()) {
            Diagnostics.error(activity, "无法打开平台：没有勾选已安装的平台。");
            host.toast(R.string.toast_select_platform_first, false);
            return;
        }
        MainActivity.launchPlatform(activity, selected.get(0));
    }

    int videosPerApp() {
        return MIN_VIDEOS + quotaSeekBar.getProgress();
    }

    // ---- Refresh -----------------------------------------------------------------------------

    void refresh() {
        refreshSummary();
        refreshTargeting();
        refreshPrimaryAction();
    }

    private void refreshSummary() {
        List<String> selected = orderedSelectedPackages();
        if (selected.isEmpty()) {
            targetSelectionButton.setText(R.string.action_select_platforms);
            targetSummary.setText(R.string.task_summary_empty);
            return;
        }
        int domestic = 0;
        int international = 0;
        StringBuilder order = new StringBuilder();
        for (String packageName : selected) {
            Platform platform = Platform.fromPackage(packageName);
            if (platform != null && platform.international) {
                international++;
            } else {
                domestic++;
            }
            if (order.length() > 0) {
                order.append(" → ");
            }
            order.append(platformName(activity, packageName));
        }
        targetSelectionButton.setText(ui.string(R.string.task_selected_count, selected.size()));
        int videos = videosPerApp();
        String summary = ui.string(R.string.task_queue_summary, selected.size(),
                selected.size() * videos, domestic, international, order.toString());
        if (ContentTargeting.savedConfig(preferences).isReady()) {
            summary = ui.string(R.string.task_queue_summary_targeting, summary,
                    ContentTargeting.maxScansForQuota(videos));
        }
        targetSummary.setText(summary);
    }

    private void refreshTargeting() {
        ContentTargeting.Config saved = ContentTargeting.savedConfig(preferences);
        if (targetingSwitch.isChecked() != saved.enabled) {
            setTargetingSwitch(saved.enabled);
        }
        boolean sessionTargeting = ContentTargeting.isSessionEnabled(preferences)
                && SessionStore.isRunning(preferences);
        if (!saved.enabled) {
            targetingState.setText(R.string.content_targeting_disabled);
        } else if (!saved.hasIncludeTerms()) {
            targetingState.setText(R.string.content_targeting_missing_terms);
        } else if (!sessionTargeting) {
            targetingState.setText(ui.string(R.string.content_targeting_ready,
                    ContentTargeting.maxScansForQuota(videosPerApp())));
        } else {
            int scans = Math.max(0, preferences.getInt(ContentTargeting.KEY_ACTIVE_SCANS, 0));
            int maximum = Math.max(1, preferences.getInt(ContentTargeting.KEY_SESSION_MAX_SCANS,
                    ContentTargeting.maxScansForQuota(videosPerApp())));
            String decision = preferences.getString(ContentTargeting.KEY_LAST_DECISION, "");
            String reason = preferences.getString(ContentTargeting.KEY_LAST_REASON, "");
            String suffix = decision.isEmpty()
                    ? ui.string(R.string.content_targeting_waiting_text) : reason;
            targetingState.setText(ui.string(R.string.content_targeting_running, scans, maximum, suffix));
        }
    }

    private void refreshPrimaryAction() {
        ContentTargeting.Config targeting = ContentTargeting.savedConfig(preferences);
        int selectedCount = orderedSelectedPackages().size();
        PrimaryAction action = PrimaryAction.resolve(SessionStore.isRunning(preferences),
                host.isAccessibilityServiceEnabled(), selectedCount,
                targeting.enabled, targeting.isReady());
        ui.setEnabledLook(primaryActionButton, action.enabled);
        switch (action) {
            case STOP_SESSION:
                primaryActionButton.setText(R.string.primary_stop);
                break;
            case ENABLE_SERVICE:
                primaryActionButton.setText(R.string.primary_enable_service);
                break;
            case SELECT_PLATFORMS:
                primaryActionButton.setText(R.string.primary_select_platforms);
                break;
            case FILL_TARGETING_TERMS:
                primaryActionButton.setText(R.string.primary_fill_terms);
                break;
            case START_TARGETED:
                primaryActionButton.setText(ui.string(R.string.primary_start_targeted, selectedCount));
                break;
            case START_PLAIN:
            default:
                primaryActionButton.setText(ui.string(R.string.primary_start_plain, selectedCount));
                break;
        }
    }
}
