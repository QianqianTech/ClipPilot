package com.example.videobrowseassistant;

/**
 * Decides what the main task button does. Kept free of Android views so the rules are unit
 * tested; the activity only maps the result to a label and a click handler.
 */
enum PrimaryAction {
    STOP_SESSION(true),
    ENABLE_SERVICE(true),
    SELECT_PLATFORMS(false),
    FILL_TARGETING_TERMS(false),
    START_TARGETED(true),
    START_PLAIN(true);

    final boolean enabled;

    PrimaryAction(boolean enabled) {
        this.enabled = enabled;
    }

    static PrimaryAction resolve(boolean running, boolean serviceEnabled, int selectedPlatforms,
                                 boolean targetingEnabled, boolean targetingReady) {
        if (running) {
            return STOP_SESSION;
        }
        if (!serviceEnabled) {
            return ENABLE_SERVICE;
        }
        if (selectedPlatforms <= 0) {
            return SELECT_PLATFORMS;
        }
        if (targetingEnabled && !targetingReady) {
            return FILL_TARGETING_TERMS;
        }
        return targetingEnabled ? START_TARGETED : START_PLAIN;
    }
}
