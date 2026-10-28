package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

/**
 * Framework-level checks that need real Android classes. Pinned to SDK 34 because the
 * Robolectric release in use does not yet ship an SDK 36 runtime.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityRobolectricTest {
    @Test
    @SuppressWarnings("deprecation")
    public void subPagesReturnToTheOverviewOnBack() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(MainActivity.Page.HOME, activity.currentPage());
        activity.navigateTo(MainActivity.Page.TASK);
        assertEquals(MainActivity.Page.TASK, activity.currentPage());
        activity.onBackPressed();
        assertEquals(MainActivity.Page.HOME, activity.currentPage());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void startingWithoutTheAccessibilityServiceDoesNotCreateASession() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        activity.performPrimaryAction();
        assertFalse(SessionStore.isRunning(activity.preferences()));
    }

    @Test
    public void notificationStopClearsAPersistedSession() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences(SessionKeys.PREFS,
                Context.MODE_PRIVATE);
        SessionStore.start(preferences, Collections.singletonList("tv.danmaku.bili"), 2,
                System.currentTimeMillis(), ContentTargeting.Config.disabled(), "");
        assertTrue(SessionStore.isRunning(preferences));

        new SessionCommandReceiver().onReceive(context,
                new Intent(context, SessionCommandReceiver.class)
                        .setAction(SessionCommandReceiver.ACTION_STOP));

        assertFalse(SessionStore.isRunning(preferences));
        assertEquals(context.getString(R.string.summary_stopped_from_notification),
                preferences.getString(SessionKeys.SESSION_SUMMARY, ""));
    }
}
