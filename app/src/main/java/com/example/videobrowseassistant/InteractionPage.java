package com.example.videobrowseassistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** "互动配置" page: user-triggered comment assistance and the optional model gateway. */
final class InteractionPage {
    private static final String STATE_CANDIDATE_TEXTS = "interaction.candidate_texts";
    private static final String STATE_CANDIDATE_RATIONALES = "interaction.candidate_rationales";
    private static final String STATE_MANUAL_CONTEXT = "interaction.manual_context";
    private static final String STATE_HAD_TOKEN = "interaction.had_token";

    private final Activity activity;
    private final PageHost host;
    private final UiKit ui;
    private final SharedPreferences preferences;
    private final List<CommentAssistant.Candidate> candidates = new ArrayList<>();
    private final Random candidateRandom = new Random();
    private final ExecutorService gatewayExecutor = Executors.newSingleThreadExecutor();
    private Future<?> pendingGatewayRequest;
    private ModelGatewayClient.Call pendingGatewayCall;
    private int gatewayEpoch;

    final LinearLayout commentCard;
    final GatewayCard gateway;
    private Switch commentSwitch;
    private EditText keywordsInput;
    private EditText manualContextInput;
    private Spinner directionSpinner;
    private TextView captureState;
    private TextView generationState;
    private LinearLayout candidatesContainer;
    private Button generateButton;
    private Button useManualContextButton;
    private Button randomPickButton;
    private Button resumeButton;
    private boolean restoringControls;

    InteractionPage(Activity activity, PageHost host) {
        this.activity = activity;
        this.host = host;
        this.ui = host.ui();
        this.preferences = host.preferences();
        commentCard = buildCommentCard();
        gateway = new GatewayCard(activity, host, this::cancelPendingGatewayRequest);
    }

    // ---- Comment card ------------------------------------------------------------------------

    private LinearLayout buildCommentCard() {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(R.string.comment_card_title), ui.matchWrap(0));
        card.addView(ui.hint(R.string.comment_card_intro), ui.matchWrap(7));

        commentSwitch = ui.toggle(R.string.comment_switch,
                InteractionSession.isCommentAssistEnabled(preferences));
        commentSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            if (restoringControls) {
                return;
            }
            if (isChecked && !preferences.getBoolean(
                    InteractionSession.KEY_COMMENT_DISCLOSURE_ACCEPTED, false)) {
                setChecked(commentSwitch, false);
                showCommentConsentDialog();
                return;
            }
            SharedPreferences.Editor editor = preferences.edit()
                    .putBoolean(InteractionSession.KEY_COMMENT_ASSIST_ENABLED, isChecked);
            if (!isChecked) {
                cancelPendingGatewayRequest();
                InteractionSession.clearEphemeral(editor);
                clearTransientUi();
            }
            editor.apply();
            if (isChecked) {
                host.toast(R.string.toast_comment_enabled, true);
            }
            SessionCommands.dispatch(SessionCommands.Command.DRAFT_UPDATED);
            refresh();
        });
        card.addView(commentSwitch, ui.matchWrap(12));

        card.addView(ui.text(R.string.comment_keywords_label, 13, R.color.text_secondary), ui.matchWrap(8));
        keywordsInput = ui.editText(R.string.comment_keywords_hint, true);
        keywordsInput.setText(preferences.getString(InteractionSession.KEY_COMMENT_KEYWORDS, ""));
        keywordsInput.addTextChangedListener(UiKit.afterChange(value -> preferences.edit()
                .putString(InteractionSession.KEY_COMMENT_KEYWORDS,
                        InteractionSession.normalize(value, 80))
                .apply()));
        card.addView(keywordsInput, ui.matchWrap(3));

        card.addView(ui.text(R.string.comment_direction_label, 13, R.color.text_secondary), ui.matchWrap(10));
        directionSpinner = new Spinner(activity);
        directionSpinner.setContentDescription(activity.getString(R.string.comment_direction_label));
        CommentAssistant.Direction[] directions = CommentAssistant.Direction.values();
        String[] directionLabels = new String[directions.length];
        for (int index = 0; index < directions.length; index++) {
            directionLabels[index] = activity.getString(directionLabel(directions[index]));
        }
        ArrayAdapter<String> directionAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, directionLabels);
        directionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        directionSpinner.setAdapter(directionAdapter);
        directionSpinner.setSelection(CommentAssistant.Direction.fromStored(
                preferences.getString(InteractionSession.KEY_COMMENT_DIRECTION, "")).ordinal());
        directionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < directions.length) {
                    preferences.edit().putString(InteractionSession.KEY_COMMENT_DIRECTION,
                            directions[position].name()).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        card.addView(directionSpinner, ui.matchWrap(2));

        captureState = ui.badge(R.string.comment_capture_none, 12);
        card.addView(captureState, ui.matchWrap(12));

        card.addView(ui.text(R.string.comment_manual_label, 13, R.color.text_secondary), ui.matchWrap(12));
        manualContextInput = ui.editText(R.string.comment_manual_hint, false);
        manualContextInput.setGravity(Gravity.TOP | Gravity.START);
        manualContextInput.setMinLines(2);
        manualContextInput.setMaxLines(4);
        manualContextInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        manualContextInput.addTextChangedListener(UiKit.afterChange(value -> refresh()));
        card.addView(manualContextInput, ui.matchWrap(3));

        useManualContextButton = ui.secondaryButton(R.string.action_use_manual_context);
        useManualContextButton.setMinHeight(ui.dp(44));
        useManualContextButton.setOnClickListener(v -> useManualContext());
        card.addView(useManualContextButton, ui.matchWrap(8));

        generateButton = ui.primaryButton(R.string.action_generate_candidates);
        generateButton.setMinHeight(ui.dp(48));
        generateButton.setOnClickListener(v -> generateCandidates());
        card.addView(generateButton, ui.matchWrap(12));

        randomPickButton = ui.secondaryButton(R.string.action_random_candidate);
        randomPickButton.setMinHeight(ui.dp(44));
        randomPickButton.setOnClickListener(v -> pickRandomCandidate());
        card.addView(randomPickButton, ui.matchWrap(8));

        resumeButton = ui.secondaryButton(R.string.action_resume_browsing);
        resumeButton.setMinHeight(ui.dp(44));
        resumeButton.setOnClickListener(v -> resumeBrowsing());
        card.addView(resumeButton, ui.matchWrap(8));

        generationState = ui.hint(R.string.comment_generation_local_only);
        generationState.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(generationState, ui.matchWrap(10));
        candidatesContainer = ui.column();
        card.addView(candidatesContainer, ui.matchWrap(4));
        card.addView(ui.hint(R.string.comment_engagement_hint), ui.matchWrap(12));
        return card;
    }

    private static int directionLabel(CommentAssistant.Direction direction) {
        switch (direction) {
            case QUESTION:
                return R.string.direction_question;
            case ADD_VALUE:
                return R.string.direction_add_value;
            case PROFESSIONAL:
                return R.string.direction_professional;
            case LIGHT:
                return R.string.direction_light;
            case EMPATHY:
            default:
                return R.string.direction_empathy;
        }
    }

    private void setChecked(Switch toggle, boolean checked) {
        restoringControls = true;
        toggle.setChecked(checked);
        restoringControls = false;
    }

    // ---- Comment actions ---------------------------------------------------------------------

    private void showCommentConsentDialog() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.comment_consent_title)
                .setMessage(R.string.comment_consent_message)
                .setNegativeButton(R.string.action_not_now, null)
                .setPositiveButton(R.string.action_accept_and_enable, (dialog, which) -> {
                    preferences.edit()
                            .putBoolean(InteractionSession.KEY_COMMENT_DISCLOSURE_ACCEPTED, true)
                            .putBoolean(InteractionSession.KEY_COMMENT_ASSIST_ENABLED, true)
                            .apply();
                    setChecked(commentSwitch, true);
                    host.toast(R.string.toast_comment_enabled, true);
                    SessionCommands.dispatch(SessionCommands.Command.DRAFT_UPDATED);
                    refresh();
                })
                .show();
    }

    private boolean requirePausedAssist() {
        if (!InteractionSession.isCommentAssistEnabled(preferences)) {
            host.toast(R.string.toast_enable_comment_first, false);
            return false;
        }
        if (!InteractionSession.isPaused(preferences)) {
            host.toast(R.string.toast_pause_first, true);
            return false;
        }
        return true;
    }

    private void useManualContext() {
        if (!requirePausedAssist()) {
            return;
        }
        String context = InteractionSession.normalize(manualContextInput.getText().toString(),
                InteractionSession.MAX_CONTEXT_CHARS);
        if (context.length() < 8) {
            host.toast(R.string.toast_manual_too_short, false);
            return;
        }
        if (SafetyTerms.isBlocked(context)) {
            host.toast(R.string.toast_manual_unsafe, true);
            return;
        }
        preferences.edit()
                .remove(InteractionSession.KEY_COMMENT_CAPTURE_REQUEST_ID)
                .putString(InteractionSession.KEY_COMMENT_CONTEXT, context)
                .putLong(InteractionSession.KEY_COMMENT_CONTEXT_CAPTURED_AT, System.currentTimeMillis())
                .putString(InteractionSession.KEY_COMMENT_CAPTURE_RESULT,
                        ui.string(R.string.comment_manual_result, context.length()))
                .putString(SessionKeys.SESSION_SUMMARY, ui.string(R.string.summary_manual_context))
                .apply();
        Diagnostics.info(activity, "用户手动补充了视频要点，用于评论候选生成；原文不会写入诊断。");
        host.toast(R.string.toast_manual_used, false);
        refresh();
    }

    private void generateCandidates() {
        if (!requirePausedAssist()) {
            return;
        }
        String context = InteractionSession.freshContext(preferences, System.currentTimeMillis());
        if (context.isEmpty()) {
            host.toast(R.string.toast_no_context, true);
            return;
        }
        int selected = directionSpinner.getSelectedItemPosition();
        CommentAssistant.Direction[] directions = CommentAssistant.Direction.values();
        CommentAssistant.Direction direction = selected >= 0 && selected < directions.length
                ? directions[selected] : CommentAssistant.Direction.EMPATHY;
        CommentAssistant.Strategy strategy = new CommentAssistant.Strategy(
                keywordsInput.getText().toString(), direction);
        ModelGatewayConfig.Config gatewayConfig = ModelGatewayConfig.savedConfig(preferences);
        if (!gatewayConfig.enabled) {
            CommentAssistant.Result result = CommentAssistant.generate(context, strategy);
            showCandidates(result.candidates, result.status);
            Diagnostics.info(activity, result.candidates.isEmpty()
                    ? "本地评论候选未生成。"
                    : "已生成 " + result.candidates.size() + " 条本地关联候选；用户仍需选择、填入并手动发送。");
            return;
        }
        String token = gateway.currentToken();
        if (token.length() < 16) {
            host.toast(R.string.toast_token_required, true);
            gateway.refresh();
            return;
        }
        requestGatewayCandidates(context, strategy, gatewayConfig, token);
    }

    private void requestGatewayCandidates(String context, CommentAssistant.Strategy strategy,
                                          ModelGatewayConfig.Config config, String token) {
        cancelPendingGatewayRequest();
        final int epoch = ++gatewayEpoch;
        final ModelGatewayClient.Call call = new ModelGatewayClient.Call();
        pendingGatewayCall = call;
        showCandidates(new ArrayList<>(), ui.string(R.string.comment_gateway_requesting));
        ui.setEnabledLook(generateButton, false);
        pendingGatewayRequest = gatewayExecutor.submit(() -> {
            ModelGatewayClient.Result result = call.generate(config, token, context, strategy);
            activity.runOnUiThread(() -> {
                boolean stillCurrent = epoch == gatewayEpoch
                        && pendingGatewayCall == call
                        && InteractionSession.isCommentAssistEnabled(preferences)
                        && InteractionSession.isPaused(preferences)
                        && ModelGatewayConfig.savedConfig(preferences).enabled;
                if (!stillCurrent || activity.isFinishing()) {
                    return;
                }
                pendingGatewayCall = null;
                pendingGatewayRequest = null;
                showCandidates(result.candidates, result.status);
                if (result.authorizationFailure) {
                    gateway.clearToken();
                }
                Diagnostics.info(activity, result.candidates.isEmpty()
                        ? "模型网关未生成可用候选。"
                        : "模型网关返回 " + result.candidates.size() + " 条候选；用户仍需选择、填入并手动发送。");
                refresh();
            });
        });
    }

    private void showCandidates(List<CommentAssistant.Candidate> next, String status) {
        candidates.clear();
        candidates.addAll(next);
        generationState.setText(status);
        renderCandidates();
    }

    private void renderCandidates() {
        candidatesContainer.removeAllViews();
        for (int index = 0; index < candidates.size(); index++) {
            CommentAssistant.Candidate candidate = candidates.get(index);
            LinearLayout candidateCard = ui.column();
            candidateCard.setBackgroundResource(R.drawable.bg_badge);
            candidateCard.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            TextView text = ui.text(ui.string(R.string.comment_candidate_row, index + 1, candidate.text),
                    14, R.color.text_primary);
            text.setLineSpacing(0f, 1.12f);
            candidateCard.addView(text, ui.matchWrap(0));
            candidateCard.addView(ui.text(candidate.rationale, 11, R.color.text_secondary), ui.matchWrap(6));
            Button select = ui.secondaryButton(R.string.action_select_candidate);
            select.setMinHeight(ui.dp(42));
            select.setContentDescription(ui.string(R.string.comment_candidate_select_description, index + 1));
            select.setOnClickListener(v -> prepareDraft(candidate));
            candidateCard.addView(select, ui.matchWrap(8));
            candidatesContainer.addView(candidateCard, ui.matchWrap(8));
        }
    }

    private void prepareDraft(CommentAssistant.Candidate candidate) {
        if (candidate == null || !InteractionSession.isPaused(preferences)) {
            host.toast(R.string.toast_not_paused_for_draft, false);
            return;
        }
        String draft = InteractionSession.normalize(candidate.text, InteractionSession.MAX_DRAFT_CHARS);
        if (draft.isEmpty()) {
            host.toast(R.string.toast_invalid_candidate, false);
            return;
        }
        preferences.edit()
                .putString(InteractionSession.KEY_COMMENT_DRAFT, draft)
                .putLong(InteractionSession.KEY_COMMENT_DRAFT_CREATED_AT, System.currentTimeMillis())
                .putString(SessionKeys.SESSION_SUMMARY, ui.string(R.string.summary_draft_ready))
                .apply();
        SessionCommands.dispatch(SessionCommands.Command.DRAFT_UPDATED);
        generationState.setText(R.string.comment_draft_ready_hint);
        Diagnostics.info(activity, "用户已选择评论草稿，等待在已聚焦输入框中填入；不会自动发送。");
        host.toast(R.string.toast_draft_ready, true);
        refresh();
    }

    private void pickRandomCandidate() {
        if (candidates.isEmpty()) {
            host.toast(R.string.toast_generate_first, false);
            return;
        }
        prepareDraft(candidates.get(candidateRandom.nextInt(candidates.size())));
    }

    private void resumeBrowsing() {
        if (!InteractionSession.isPaused(preferences)) {
            host.toast(R.string.toast_no_paused_comment, false);
            return;
        }
        cancelPendingGatewayRequest();
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(InteractionSession.KEY_COMMENT_PAUSED, false)
                .putString(SessionKeys.SESSION_SUMMARY, ui.string(R.string.summary_resumed));
        InteractionSession.clearEphemeral(editor);
        editor.apply();
        SessionCommands.dispatch(SessionCommands.Command.RESUME_BROWSING);
        clearTransientUi();
        Diagnostics.info(activity, "用户恢复浏览，已清除短时页面文字与评论草稿。");
        host.toast(R.string.toast_resumed, false);
        host.refreshAll();
    }

    /** Drops candidates and the manual summary; called on stop, resume and when assist is disabled. */
    void clearTransientUi() {
        manualContextInput.setText("");
        candidates.clear();
        renderCandidates();
    }

    void cancelPendingGatewayRequest() {
        gatewayEpoch++;
        if (pendingGatewayCall != null) {
            pendingGatewayCall.cancel();
            pendingGatewayCall = null;
        }
        if (pendingGatewayRequest != null) {
            pendingGatewayRequest.cancel(true);
            pendingGatewayRequest = null;
        }
        ModelGatewayClient.cancelActiveCalls();
    }

    void shutdown() {
        cancelPendingGatewayRequest();
        gatewayExecutor.shutdownNow();
    }

    // ---- State -------------------------------------------------------------------------------

    void saveState(Bundle outState) {
        ArrayList<String> texts = new ArrayList<>();
        ArrayList<String> rationales = new ArrayList<>();
        for (CommentAssistant.Candidate candidate : candidates) {
            texts.add(candidate.text);
            rationales.add(candidate.rationale);
        }
        outState.putStringArrayList(STATE_CANDIDATE_TEXTS, texts);
        outState.putStringArrayList(STATE_CANDIDATE_RATIONALES, rationales);
        outState.putString(STATE_MANUAL_CONTEXT, manualContextInput.getText().toString());
        // Only whether a token existed is saved, never the token itself.
        outState.putBoolean(STATE_HAD_TOKEN, !gateway.currentToken().isEmpty());
    }

    void restoreState(Bundle savedState) {
        if (savedState == null) {
            return;
        }
        ArrayList<String> texts = savedState.getStringArrayList(STATE_CANDIDATE_TEXTS);
        ArrayList<String> rationales = savedState.getStringArrayList(STATE_CANDIDATE_RATIONALES);
        if (texts != null && rationales != null && texts.size() == rationales.size()
                && InteractionSession.isPaused(preferences)) {
            List<CommentAssistant.Candidate> restored = new ArrayList<>();
            for (int index = 0; index < texts.size(); index++) {
                restored.add(new CommentAssistant.Candidate(texts.get(index), rationales.get(index)));
            }
            candidates.clear();
            candidates.addAll(restored);
            renderCandidates();
        }
        String manual = savedState.getString(STATE_MANUAL_CONTEXT, "");
        if (manual != null && !manual.isEmpty()) {
            manualContextInput.setText(manual);
        }
        if (savedState.getBoolean(STATE_HAD_TOKEN, false)) {
            host.toast(R.string.toast_token_cleared_after_recreate, true);
        }
    }

    // ---- Refresh -----------------------------------------------------------------------------

    void refresh() {
        refreshComment();
        gateway.refresh();
    }

    private void refreshComment() {
        long now = System.currentTimeMillis();
        InteractionSession.clearExpired(preferences, now);
        boolean enabled = InteractionSession.isCommentAssistEnabled(preferences);
        boolean paused = InteractionSession.isPaused(preferences);
        boolean hasContext = InteractionSession.hasFreshContext(preferences, now);
        if (commentSwitch.isChecked() != enabled) {
            setChecked(commentSwitch, enabled);
        }
        if (hasContext) {
            captureState.setText(ui.string(R.string.comment_context_ready,
                    InteractionSession.freshContext(preferences, now).length()));
        } else {
            String result = preferences.getString(InteractionSession.KEY_COMMENT_CAPTURE_RESULT, "");
            captureState.setText(result.isEmpty() ? ui.string(R.string.comment_capture_none) : result);
        }
        boolean requestInFlight = pendingGatewayCall != null;
        ui.setEnabledLook(generateButton, enabled && paused && hasContext && !requestInFlight);
        ui.setEnabledLook(useManualContextButton, enabled && paused);
        UiKit.setVisible(resumeButton, paused);
        ui.setEnabledLook(randomPickButton, enabled && paused && !candidates.isEmpty());
        if (!enabled) {
            generationState.setText(R.string.comment_state_disabled);
        } else if (!paused) {
            generationState.setText(R.string.comment_state_browsing);
        } else if (!hasContext) {
            generationState.setText(R.string.comment_state_waiting_context);
        }
    }
}
