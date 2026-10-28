package com.example.videobrowseassistant;

import android.view.accessibility.AccessibilityEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccessibilityEventPolicyTest {

    @Test
    public void contentChangesDoNotCountAsForegroundTransitions() {
        assertFalse(AutoSwipeAccessibilityService.isForegroundTransitionEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    }

    @Test
    public void windowStateChangesCountAsForegroundTransitions() {
        assertTrue(AutoSwipeAccessibilityService.isForegroundTransitionEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue(AutoSwipeAccessibilityService.isForegroundTransitionEvent(
                AccessibilityEvent.TYPE_WINDOWS_CHANGED));
    }

    @Test
    public void combinedWindowEventsStillCountAsForegroundTransitions() {
        int combined = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        assertTrue(AutoSwipeAccessibilityService.isForegroundTransitionEvent(combined));
    }
}
