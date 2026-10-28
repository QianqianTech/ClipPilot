package com.example.videobrowseassistant;

import java.util.Random;

/**
 * Keeps the user-visible browsing cadence in one place.  A new delay is
 * selected for every attempted video transition, rather than persisting a
 * fixed value in the session snapshot.
 */
final class SwipeTiming {
    static final int MIN_SECONDS = 10;
    static final int MAX_SECONDS = 30;

    private SwipeTiming() {
    }

    static int randomSeconds(Random random) {
        Random source = random == null ? new Random() : random;
        return MIN_SECONDS + source.nextInt(MAX_SECONDS - MIN_SECONDS + 1);
    }

    static long randomDelayMillis(Random random) {
        return randomSeconds(random) * 1000L;
    }
}
