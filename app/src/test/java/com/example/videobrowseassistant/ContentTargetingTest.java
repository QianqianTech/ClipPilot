package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class ContentTargetingTest {
    @Test
    public void exclusionWinsOverAnIncludedTopic() {
        ContentTargeting.Config config = new ContentTargeting.Config(
                true, "新能源汽车，电池", "广告，带货");

        ContentTargeting.Decision decision = ContentTargeting.decide(
                "新能源汽车电池测评 广告合作带货", config);

        assertEquals(ContentTargeting.Decision.Kind.EXCLUDED, decision.kind);
        assertTrue(decision.reason.contains("广告"));
    }

    @Test
    public void matchingUsesDeterministicLocalTerms() {
        ContentTargeting.Config config = new ContentTargeting.Config(
                true, "学习计划,效率", "直播");

        ContentTargeting.Decision match = ContentTargeting.decide(
                "今天分享学习计划如何拆解成可完成的小目标", config);
        ContentTargeting.Decision noMatch = ContentTargeting.decide(
                "城市散步记录和天气分享", config);

        assertEquals(ContentTargeting.Decision.Kind.MATCH, match.kind);
        assertTrue(match.reason.contains("学习计划"));
        assertEquals(ContentTargeting.Decision.Kind.NO_MATCH, noMatch.kind);
    }

    @Test
    public void emptyOrControlOnlyTextStaysUnknown() {
        ContentTargeting.Config config = new ContentTargeting.Config(true, "学习", "广告");

        ContentTargeting.Decision decision = ContentTargeting.decide("点赞 评论 分享", config);

        assertEquals(ContentTargeting.Decision.Kind.UNKNOWN, decision.kind);
    }

    @Test
    public void termSanitizerKeepsOrderDropsControlsAndDuplicates() {
        assertEquals(
                Arrays.asList("AI", "学习计划"),
                ContentTargeting.parseTerms(" AI，点赞，学习计划，AI "));
        assertEquals("AI，学习计划", ContentTargeting.sanitizeTerms("AI,学习计划,AI"));
    }

    @Test
    public void scanBudgetIsBoundedAndAtLeastTheTargetQuota() {
        assertEquals(10, ContentTargeting.maxScansForQuota(2));
        assertEquals(100, ContentTargeting.maxScansForQuota(99));
        assertFalse(ContentTargeting.Config.disabled().isReady());
    }

    @Test
    public void sessionFingerprintIsStableWithoutContainingSourceText() {
        String source = "学习计划如何拆成每周可完成的小目标";
        String fingerprint = ContentTargeting.fingerprint(source);

        assertEquals(fingerprint, ContentTargeting.fingerprint(source));
        assertFalse(fingerprint.contains("学习计划"));
        assertFalse(fingerprint.isEmpty());
    }

    @Test
    public void asciiTermsMatchOnWordBoundariesOnly() {
        ContentTargeting.Config config = new ContentTargeting.Config(true, "AI", "");
        assertEquals(ContentTargeting.Decision.Kind.MATCH,
                ContentTargeting.decide("今天用AI做一份每周学习计划", config).kind);
        assertEquals(ContentTargeting.Decision.Kind.MATCH,
                ContentTargeting.decide("Open AI tools for weekly planning", config).kind);
        assertEquals(ContentTargeting.Decision.Kind.NO_MATCH,
                ContentTargeting.decide("He SAID the email was about travel plans", config).kind);
    }

    @Test
    public void mentionsAndHandlesAreNotTreatedAsTopics() {
        ContentTargeting.Config config = new ContentTargeting.Config(true, "学习", "");
        assertEquals(ContentTargeting.Decision.Kind.NO_MATCH,
                ContentTargeting.decide("@学习博主小王 城市散步记录和天气分享", config).kind);
    }

    @Test
    public void diagnosticLabelsNeverContainTheUsersTerms() {
        ContentTargeting.Config config = new ContentTargeting.Config(true, "露营装备，学习计划", "广告");
        ContentTargeting.Decision match = ContentTargeting.decide("分享学习计划的拆解方式和节奏", config);
        assertEquals(2, match.termIndex);
        assertEquals("MATCH（想看#2）", match.diagnosticLabel());
        ContentTargeting.Decision excluded = ContentTargeting.decide("学习计划课程广告合作推广", config);
        assertEquals("EXCLUDED（不想看#1）", excluded.diagnosticLabel());
        assertFalse(excluded.diagnosticLabel().contains("广告"));
    }

    @Test
    public void fingerprintIsAShortHexDigest() {
        String fingerprint = ContentTargeting.fingerprint("学习计划如何拆成每周可完成的小目标");
        assertEquals(16, fingerprint.length());
        assertTrue(fingerprint.matches("[0-9a-f]+"));
        assertEquals("", ContentTargeting.fingerprint("   "));
    }
}
