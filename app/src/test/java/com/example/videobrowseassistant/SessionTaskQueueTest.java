package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SessionTaskQueueTest {
    @Test
    public void parseKeepsOrderAndRemovesDuplicatesAndBlanks() {
        assertEquals(
                Arrays.asList("com.tencent.mm", "tv.danmaku.bili"),
                SessionTaskQueue.parse(" com.tencent.mm,,tv.danmaku.bili,com.tencent.mm "));
    }

    @Test
    public void serializeKeepsFirstOccurrenceOrder() {
        assertEquals(
                "com.ss.android.ugc.aweme,com.smile.gifmaker",
                SessionTaskQueue.serialize(Arrays.asList(
                        "com.ss.android.ugc.aweme",
                        "com.smile.gifmaker",
                        "com.ss.android.ugc.aweme")));
        assertEquals("", SessionTaskQueue.serialize(Collections.emptyList()));
    }

    @Test
    public void orderSelectedPreservesSavedOrderAndAppendsNewSelections() {
        assertEquals(
                Arrays.asList("com.smile.gifmaker", "com.tencent.mm", "tv.danmaku.bili"),
                SessionTaskQueue.orderSelected(
                        Arrays.asList("com.smile.gifmaker", "com.tencent.mm"),
                        Arrays.asList("com.tencent.mm", "tv.danmaku.bili", "com.smile.gifmaker")));
    }

    @Test
    public void orderSelectedDropsUncheckedAndUnknownSavedPackages() {
        assertEquals(
                Arrays.asList("com.tencent.mm", "com.ss.android.ugc.aweme"),
                SessionTaskQueue.orderSelected(
                        Arrays.asList("missing.package", "com.tencent.mm", "com.smile.gifmaker"),
                        Arrays.asList("com.ss.android.ugc.aweme", "com.tencent.mm")));
    }

    @Test
    public void quotaIsClampedToUiBounds() {
        assertEquals(SessionTaskQueue.MIN_TASK_QUOTA,
                SessionTaskQueue.clampQuota(-1, SessionTaskQueue.MIN_TASK_QUOTA,
                        SessionTaskQueue.MAX_TASK_QUOTA));
        assertEquals(8, SessionTaskQueue.clampQuota(8, SessionTaskQueue.MIN_TASK_QUOTA,
                SessionTaskQueue.MAX_TASK_QUOTA));
        assertEquals(SessionTaskQueue.MAX_TASK_QUOTA,
                SessionTaskQueue.clampQuota(999, SessionTaskQueue.MIN_TASK_QUOTA,
                        SessionTaskQueue.MAX_TASK_QUOTA));
    }
}
