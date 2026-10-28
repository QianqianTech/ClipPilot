package com.example.videobrowseassistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Optional model-gateway settings. Only the HTTPS origin and a server-side profile are
 * persisted; the short-lived device token stays in this card's memory.
 */
final class GatewayCard {
    private final Activity activity;
    private final PageHost host;
    private final UiKit ui;
    private final SharedPreferences preferences;
    /** Invoked when the gateway is disabled or its endpoint changes. */
    private final Runnable onGatewayInvalidated;

    final LinearLayout card;
    private final Switch gatewaySwitch;
    private final EditText endpointInput;
    private final Spinner profileSpinner;
    private final EditText tokenInput;
    private final TextView gatewayState;
    private boolean restoringControls;

    GatewayCard(Activity activity, PageHost host, Runnable onGatewayInvalidated) {
        this.activity = activity;
        this.host = host;
        this.ui = host.ui();
        this.preferences = host.preferences();
        this.onGatewayInvalidated = onGatewayInvalidated;

        card = ui.card();
        card.addView(ui.sectionTitle(R.string.gateway_card_title), ui.matchWrap(0));
        card.addView(ui.hint(R.string.gateway_card_intro), ui.matchWrap(7));
        gatewayState = ui.badge(R.string.gateway_state_checking, 12);

        ModelGatewayConfig.Config saved = ModelGatewayConfig.savedConfig(preferences);
        card.addView(ui.text(R.string.gateway_endpoint_label, 13, R.color.text_secondary), ui.matchWrap(12));
        endpointInput = ui.editText(R.string.gateway_endpoint_hint, true);
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setText(saved.endpoint);
        card.addView(endpointInput, ui.matchWrap(3));

        card.addView(ui.text(R.string.gateway_profile_label, 13, R.color.text_secondary), ui.matchWrap(10));
        profileSpinner = new Spinner(activity);
        profileSpinner.setContentDescription(activity.getString(R.string.gateway_profile_label));
        ModelGatewayConfig.Profile[] profiles = ModelGatewayConfig.Profile.values();
        String[] profileLabels = new String[profiles.length];
        for (int index = 0; index < profiles.length; index++) {
            profileLabels[index] = activity.getString(profileLabel(profiles[index]));
        }
        ArrayAdapter<String> profileAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, profileLabels);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);
        profileSpinner.setSelection(saved.profile.ordinal());
        card.addView(profileSpinner, ui.matchWrap(2));

        Button save = ui.secondaryButton(R.string.action_save_gateway);
        save.setMinHeight(ui.dp(44));
        save.setOnClickListener(v -> saveConfiguration());
        card.addView(save, ui.matchWrap(10));

        card.addView(ui.text(R.string.gateway_token_label, 13, R.color.text_secondary), ui.matchWrap(12));
        tokenInput = ui.editText(R.string.gateway_token_hint, true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // The token lives only in memory: keep autofill services and view-state saving away.
        tokenInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        tokenInput.setSaveEnabled(false);
        tokenInput.addTextChangedListener(UiKit.afterChange(value -> refresh()));
        card.addView(tokenInput, ui.matchWrap(3));

        gatewaySwitch = ui.toggle(R.string.gateway_switch, saved.enabled);
        gatewaySwitch.setOnCheckedChangeListener((button, isChecked) -> onSwitchChanged(isChecked));
        card.addView(gatewaySwitch, ui.matchWrap(12));
        card.addView(gatewayState, ui.matchWrap(10));
    }

    static int profileLabel(ModelGatewayConfig.Profile profile) {
        switch (profile) {
            case HIGH_RELEVANCE:
                return R.string.profile_high_relevance;
            case ECONOMY:
                return R.string.profile_economy;
            case STANDARD:
            default:
                return R.string.profile_standard;
        }
    }

    private void setChecked(boolean checked) {
        restoringControls = true;
        gatewaySwitch.setChecked(checked);
        restoringControls = false;
    }

    private void onSwitchChanged(boolean isChecked) {
        if (restoringControls) {
            return;
        }
        if (isChecked && !ModelGatewayConfig.savedConfig(preferences).hasEndpoint()) {
            setChecked(false);
            host.toast(R.string.toast_gateway_save_first, true);
            return;
        }
        if (isChecked && !preferences.getBoolean(ModelGatewayConfig.KEY_DISCLOSURE_ACCEPTED, false)) {
            setChecked(false);
            showConsentDialog();
            return;
        }
        preferences.edit().putBoolean(ModelGatewayConfig.KEY_ENABLED, isChecked).apply();
        if (!isChecked) {
            onGatewayInvalidated.run();
            clearToken();
        }
        refresh();
    }

    private void saveConfiguration() {
        String endpoint = ModelGatewayConfig.normalizeEndpoint(endpointInput.getText().toString());
        if (endpoint.isEmpty()) {
            host.toast(R.string.toast_gateway_invalid_endpoint, true);
            return;
        }
        ModelGatewayConfig.Profile[] profiles = ModelGatewayConfig.Profile.values();
        int selected = profileSpinner.getSelectedItemPosition();
        ModelGatewayConfig.Profile profile = selected >= 0 && selected < profiles.length
                ? profiles[selected] : ModelGatewayConfig.Profile.STANDARD;
        boolean endpointChanged = !endpoint.equals(ModelGatewayConfig.savedConfig(preferences).endpoint);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(ModelGatewayConfig.KEY_ENDPOINT, endpoint)
                .putString(ModelGatewayConfig.KEY_PROFILE, profile.storedValue);
        if (endpointChanged) {
            // A new destination needs a fresh disclosure before any text is sent there.
            editor.putBoolean(ModelGatewayConfig.KEY_ENABLED, false)
                    .remove(ModelGatewayConfig.KEY_DISCLOSURE_ACCEPTED);
        }
        editor.apply();
        endpointInput.setText(endpoint);
        if (endpointChanged) {
            onGatewayInvalidated.run();
            clearToken();
            host.toast(R.string.toast_gateway_endpoint_saved, true);
        } else {
            host.toast(R.string.toast_gateway_profile_saved, false);
        }
        refresh();
    }

    private void showConsentDialog() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.gateway_consent_title)
                .setMessage(R.string.gateway_consent_message)
                .setNegativeButton(R.string.action_not_now, null)
                .setPositiveButton(R.string.action_accept_and_enable, (dialog, which) -> {
                    if (!ModelGatewayConfig.savedConfig(preferences).hasEndpoint()) {
                        host.toast(R.string.toast_gateway_save_first, true);
                        return;
                    }
                    preferences.edit()
                            .putBoolean(ModelGatewayConfig.KEY_DISCLOSURE_ACCEPTED, true)
                            .putBoolean(ModelGatewayConfig.KEY_ENABLED, true)
                            .apply();
                    setChecked(true);
                    host.toast(R.string.toast_gateway_enabled, true);
                    refresh();
                })
                .show();
    }

    String currentToken() {
        return InteractionSession.normalize(tokenInput.getText().toString(), 512);
    }

    void clearToken() {
        tokenInput.setText("");
    }

    void refresh() {
        ModelGatewayConfig.Config config = ModelGatewayConfig.savedConfig(preferences);
        if (gatewaySwitch.isChecked() != config.enabled) {
            setChecked(config.enabled);
        }
        if (!config.hasEndpoint()) {
            gatewayState.setText(R.string.gateway_state_no_endpoint);
        } else if (!config.enabled) {
            gatewayState.setText(ui.string(R.string.model_gateway_saved, config.displayHost()));
        } else if (currentToken().isEmpty()) {
            gatewayState.setText(R.string.gateway_state_need_token);
        } else {
            gatewayState.setText(ui.string(R.string.model_gateway_enabled, config.displayHost(),
                    activity.getString(profileLabel(config.profile))));
        }
    }
}
