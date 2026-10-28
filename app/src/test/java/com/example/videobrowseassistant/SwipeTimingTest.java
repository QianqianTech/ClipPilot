package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class SwipeTimingTest {
    @Test
    public void randomSecondsStaysWithinInclusiveTenToThirtySecondRange() {
        Random random = new Random(20260929L);
        for (int index = 0; index < 2000; index++) {
            int seconds = SwipeTiming.randomSeconds(random);
            assertTrue(seconds >= SwipeTiming.MIN_SECONDS);
            assertTrue(seconds <= SwipeTiming.MAX_SECONDS);
        }
    }

    @Test
    public void randomSelectionIncludesBothBounds() {
        Random minimum = new FixedRandom(0);
        Random maximum = new FixedRandom(Integer.MAX_VALUE);
        assertEquals(SwipeTiming.MIN_SECONDS, SwipeTiming.randomSeconds(minimum));
        assertEquals(SwipeTiming.MAX_SECONDS, SwipeTiming.randomSeconds(maximum));
    }

    @Test
    public void delayIsSecondsConvertedToMilliseconds() {
        assertEquals(10_000L, SwipeTiming.randomDelayMillis(new FixedRandom(0)));
        assertEquals(30_000L, SwipeTiming.randomDelayMillis(new FixedRandom(Integer.MAX_VALUE)));
    }

    private static final class FixedRandom extends Random {
        private final int value;

        FixedRandom(int value) {
            this.value = value;
        }

        @Override
        public int nextInt(int bound) {
            return value == Integer.MAX_VALUE ? bound - 1 : 0;
        }
    }
}
