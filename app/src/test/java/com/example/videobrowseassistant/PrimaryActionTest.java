package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrimaryActionTest {
    @Test
    public void runningSessionAlwaysOffersStop() {
        assertEquals(PrimaryAction.STOP_SESSION, PrimaryAction.resolve(true, false, 0, true, false));
    }

    @Test
    public void prerequisitesAreCheckedInOrder() {
        assertEquals(PrimaryAction.ENABLE_SERVICE, PrimaryAction.resolve(false, false, 3, false, false));
        assertEquals(PrimaryAction.SELECT_PLATFORMS, PrimaryAction.resolve(false, true, 0, false, false));
        assertEquals(PrimaryAction.FILL_TARGETING_TERMS, PrimaryAction.resolve(false, true, 2, true, false));
        assertFalse(PrimaryAction.SELECT_PLATFORMS.enabled);
        assertFalse(PrimaryAction.FILL_TARGETING_TERMS.enabled);
    }

    @Test
    public void readyConfigurationsStartTheMatchingMode() {
        assertEquals(PrimaryAction.START_TARGETED, PrimaryAction.resolve(false, true, 2, true, true));
        assertEquals(PrimaryAction.START_PLAIN, PrimaryAction.resolve(false, true, 2, false, false));
        assertTrue(PrimaryAction.START_PLAIN.enabled);
    }
}
