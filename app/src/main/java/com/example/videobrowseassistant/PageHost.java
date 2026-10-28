package com.example.videobrowseassistant;

import android.content.SharedPreferences;

/** What the page builders need from {@link MainActivity}; keeps pages free of activity internals. */
interface PageHost {
    SharedPreferences preferences();

    UiKit ui();

    /** Re-reads persisted state and refreshes every page. */
    void refreshAll();

    void navigateTo(MainActivity.Page page);

    /** Starts a session or, when one is running, stops it. */
    void performPrimaryAction();

    void stopSession();

    boolean isAccessibilityServiceEnabled();

    void openAccessibilitySettings();

    void openAppDetailsSettings();

    boolean areNotificationsEnabled();

    void requestNotificationAccess();

    void toast(int stringRes, boolean longDuration, Object... args);
}
