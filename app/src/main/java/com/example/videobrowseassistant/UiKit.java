package com.example.videobrowseassistant;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Small view factory shared by the page builders. Colors come from resources so the night
 * variants in {@code values-night/colors.xml} apply automatically.
 */
final class UiKit {
    private final Activity activity;

    UiKit(Activity activity) {
        this.activity = activity;
    }

    int color(int colorRes) {
        return activity.getColor(colorRes);
    }

    String string(int stringRes, Object... args) {
        return args.length == 0 ? activity.getString(stringRes) : activity.getString(stringRes, args);
    }

    int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    LinearLayout card() {
        LinearLayout card = column();
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setElevation(dp(1));
        return card;
    }

    TextView text(CharSequence value, int sizeSp, int colorRes) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(colorRes));
        view.setIncludeFontPadding(false);
        return view;
    }

    TextView text(int stringRes, int sizeSp, int colorRes) {
        return text(activity.getString(stringRes), sizeSp, colorRes);
    }

    /** Secondary explanatory copy with relaxed line spacing. */
    TextView hint(int stringRes) {
        TextView view = text(stringRes, 12, R.color.text_secondary);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    /** Highlighted status pill; announced politely by screen readers when it changes. */
    TextView badge(int stringRes, int sizeSp) {
        TextView view = text(stringRes, sizeSp, R.color.brand_blue);
        view.setBackgroundResource(R.drawable.bg_badge);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return view;
    }

    TextView sectionTitle(int stringRes) {
        TextView title = text(stringRes, 16, R.color.text_primary);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            title.setAccessibilityHeading(true);
        }
        return title;
    }

    /**
     * Decorative colored dot. The adjacent text already states the status in words, so the dot
     * is hidden from accessibility services instead of relying on color alone.
     */
    TextView statusDot(int colorRes) {
        TextView dot = new TextView(activity);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateStatusDot(dot, colorRes);
        return dot;
    }

    void updateStatusDot(TextView dot, int colorRes) {
        if (dot == null) {
            return;
        }
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color(colorRes));
        dot.setBackground(background);
    }

    Button primaryButton(int stringRes) {
        Button button = baseButton(activity.getString(stringRes));
        button.setTextColor(color(R.color.on_brand));
        button.setBackgroundResource(R.drawable.bg_button_primary);
        return button;
    }

    Button secondaryButton(CharSequence label) {
        Button button = baseButton(label);
        button.setTextColor(color(R.color.brand_blue));
        button.setBackgroundResource(R.drawable.bg_button_secondary);
        return button;
    }

    Button secondaryButton(int stringRes) {
        return secondaryButton(activity.getString(stringRes));
    }

    Button compactButton(int stringRes) {
        Button button = secondaryButton(stringRes);
        button.setTextSize(12);
        button.setMinHeight(dp(40));
        button.setMinWidth(0);
        button.setSingleLine(true);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    Switch toggle(int stringRes, boolean checked) {
        Switch toggle = new Switch(activity);
        toggle.setText(stringRes);
        toggle.setTextSize(15);
        toggle.setTextColor(color(R.color.text_primary));
        toggle.setMinHeight(dp(44));
        toggle.setPadding(0, 0, 0, 0);
        toggle.setChecked(checked);
        return toggle;
    }

    void setEnabledLook(View view, boolean enabled) {
        if (view != null) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.55f);
        }
    }

    LinearLayout.LayoutParams equalActionParams(int rightMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.rightMargin = dp(rightMarginDp);
        return params;
    }

    LinearLayout.LayoutParams weighted() {
        return weighted(0);
    }

    LinearLayout.LayoutParams weighted(int rightMarginDp) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = dp(rightMarginDp);
        return params;
    }

    LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    LinearLayout.LayoutParams matchWrap(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    EditText editText(int hintRes, boolean singleLine) {
        EditText input = new EditText(activity);
        input.setTextSize(15);
        input.setTextColor(color(R.color.text_primary));
        input.setHintTextColor(color(R.color.text_secondary));
        input.setSingleLine(singleLine);
        input.setHint(hintRes);
        return input;
    }

    interface TextChange {
        void onChanged(String value);
    }

    static TextWatcher afterChange(TextChange change) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable value) {
                change.onChanged(value.toString());
            }
        };
    }

    static void setVisible(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private Button baseButton(CharSequence label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setStateListAnimator(null);
        return button;
    }
}
