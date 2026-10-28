package com.example.videobrowseassistant;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Home-only content: app header, usage boundary, page navigation and the version footer. */
final class HomeCards {
    private final Activity activity;
    private final PageHost host;
    private final UiKit ui;

    final LinearLayout header;
    final LinearLayout boundaryCard;
    final LinearLayout navigationCard;
    final TextView footer;

    HomeCards(Activity activity, PageHost host) {
        this.activity = activity;
        this.host = host;
        this.ui = host.ui();
        header = buildHeader();
        boundaryCard = buildBoundaryCard();
        navigationCard = buildNavigationCard();
        footer = ui.text(ui.string(R.string.footer_version, installedVersion(),
                SessionTaskQueue.MAX_TASK_QUOTA), 12, R.color.text_secondary);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
    }

    private LinearLayout buildHeader() {
        LinearLayout row = ui.row();
        LinearLayout titleColumn = ui.column();
        TextView title = ui.text(R.string.app_name, 27, R.color.text_primary);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleColumn.addView(title, ui.matchWrap(0));
        titleColumn.addView(ui.text(R.string.app_subtitle, 14, R.color.text_secondary), ui.matchWrap(4));
        row.addView(titleColumn, ui.weighted());
        TextView badge = ui.text(R.string.app_badge, 12, R.color.brand_blue);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setBackgroundResource(R.drawable.bg_badge);
        badge.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        row.addView(badge, ui.wrap());
        return row;
    }

    private LinearLayout buildBoundaryCard() {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(R.string.boundary_card_title), ui.matchWrap(0));
        TextView copy = ui.text(R.string.boundary_card_body, 13, R.color.text_body);
        copy.setLineSpacing(0f, 1.12f);
        card.addView(copy, ui.matchWrap(7));
        return card;
    }

    private LinearLayout buildNavigationCard() {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(R.string.nav_card_title), ui.matchWrap(0));
        card.addView(ui.hint(R.string.nav_card_intro), ui.matchWrap(7));
        LinearLayout firstRow = ui.row();
        firstRow.addView(navigationButton(R.string.nav_task, MainActivity.Page.TASK), ui.weighted(8));
        firstRow.addView(navigationButton(R.string.nav_interaction, MainActivity.Page.INTERACTION),
                ui.weighted(0));
        card.addView(firstRow, ui.matchWrap(10));
        LinearLayout secondRow = ui.row();
        secondRow.addView(navigationButton(R.string.nav_runtime, MainActivity.Page.RUNTIME), ui.weighted(8));
        secondRow.addView(navigationButton(R.string.nav_stats, MainActivity.Page.STATS), ui.weighted(0));
        card.addView(secondRow, ui.matchWrap(8));
        return card;
    }

    private Button navigationButton(int labelRes, MainActivity.Page page) {
        Button button = ui.secondaryButton(labelRes);
        button.setTextSize(13);
        button.setMinHeight(ui.dp(58));
        button.setOnClickListener(v -> host.navigateTo(page));
        return button;
    }

    @SuppressWarnings("deprecation")
    private String installedVersion() {
        try {
            String version = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
            return version == null ? "?" : version;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "?";
        }
    }
}
