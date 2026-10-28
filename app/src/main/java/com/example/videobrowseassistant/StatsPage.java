package com.example.videobrowseassistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/** "统计与记录" page; its compact summary line is also shown on the home page. */
final class StatsPage {
    private final Activity activity;
    private final PageHost host;
    private final UiKit ui;
    private final SharedPreferences preferences;

    final LinearLayout card;
    private final TextView homeSummary;
    final TextView detailSummary;
    final LinearLayout historyContainer;
    final Button clearButton;

    StatsPage(Activity activity, PageHost host) {
        this.activity = activity;
        this.host = host;
        this.ui = host.ui();
        this.preferences = host.preferences();
        card = ui.card();
        card.addView(ui.sectionTitle(R.string.stats_card_title), ui.matchWrap(0));
        homeSummary = ui.badge(R.string.stats_empty_compact, 13);
        card.addView(homeSummary, ui.matchWrap(10));
        detailSummary = ui.text(R.string.stats_loading, 13, R.color.text_primary);
        detailSummary.setLineSpacing(0f, 1.18f);
        card.addView(detailSummary, ui.matchWrap(10));
        historyContainer = ui.column();
        card.addView(historyContainer, ui.matchWrap(10));
        clearButton = ui.secondaryButton(R.string.action_clear_stats);
        clearButton.setMinHeight(ui.dp(44));
        clearButton.setOnClickListener(v -> confirmClear());
        card.addView(clearButton, ui.matchWrap(10));
    }

    /** Detailed rows are only visible on the stats page; the home page shows the summary. */
    void setDetailed(boolean detailed) {
        UiKit.setVisible(detailSummary, detailed);
        UiKit.setVisible(historyContainer, detailed);
        UiKit.setVisible(clearButton, detailed);
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.stats_clear_title)
                .setMessage(R.string.stats_clear_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_clear, (dialog, which) -> {
                    TaskStatsStore.clearHistory(preferences);
                    refresh();
                    host.toast(R.string.toast_stats_cleared, false);
                })
                .show();
    }

    private String formatTime(long epochMs) {
        return DateFormat.getMediumDateFormat(activity).format(new Date(epochMs)) + " "
                + DateFormat.getTimeFormat(activity).format(new Date(epochMs));
    }

    void refresh() {
        TaskStatsStore.Snapshot snapshot = TaskStatsStore.snapshot(preferences);
        if (snapshot.totalBatches == 0) {
            homeSummary.setText(R.string.stats_empty_compact);
            detailSummary.setText(R.string.stats_empty_detail);
        } else {
            homeSummary.setText(ui.string(R.string.stats_compact, snapshot.completedBatches,
                    snapshot.completedPlatformTasks, snapshot.completedVideoTasks,
                    snapshot.skippedPlatformTasks));
            int completionRate = Math.round(snapshot.completedBatches * 100f
                    / Math.max(1, snapshot.totalBatches));
            String last = snapshot.lastEndedAt <= 0L
                    ? ui.string(R.string.stats_none) : formatTime(snapshot.lastEndedAt);
            detailSummary.setText(ui.string(R.string.stats_detail,
                    snapshot.completedBatches, snapshot.totalBatches, completionRate,
                    snapshot.completedPlatformTasks, snapshot.completedVideoTasks,
                    snapshot.skippedPlatformTasks, last,
                    snapshot.lastReason.isEmpty() ? ui.string(R.string.stats_no_result)
                            : snapshot.lastReason));
        }
        historyContainer.removeAllViews();
        if (snapshot.history.isEmpty()) {
            historyContainer.addView(ui.text(R.string.stats_no_history, 12, R.color.text_secondary),
                    ui.matchWrap(0));
            return;
        }
        for (int index = snapshot.history.size() - 1; index >= 0; index--) {
            TaskStatsStore.HistoryEntry entry = TaskStatsStore.parseHistoryEntry(snapshot.history.get(index));
            if (entry == null) {
                continue;
            }
            TextView row = ui.text(ui.string(R.string.stats_history_row, formatTime(entry.endedAt),
                    entry.completedPlatforms, entry.completedVideos, entry.skippedPlatforms,
                    entry.reason), 12, R.color.text_body);
            row.setLineSpacing(0f, 1.12f);
            row.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
            row.setBackgroundResource(R.drawable.bg_badge);
            historyContainer.addView(row, ui.matchWrap(6));
        }
    }
}
