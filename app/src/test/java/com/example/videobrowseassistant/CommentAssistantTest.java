package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class CommentAssistantTest {
    @Test
    public void generatesThreeDistinctRelevantCandidatesForCustomKeyword() {
        CommentAssistant.Result result = CommentAssistant.generate(
                "今天分享如何把学习计划拆成每周都能完成的小目标，避免一开始定得太满。",
                new CommentAssistant.Strategy("学习计划", CommentAssistant.Direction.ADD_VALUE));

        assertEquals(3, result.candidates.size());
        Set<String> unique = new HashSet<>();
        for (CommentAssistant.Candidate candidate : result.candidates) {
            assertTrue(candidate.text.contains("学习计划"));
            assertTrue(candidate.text.length() <= InteractionSession.MAX_DRAFT_CHARS);
            assertTrue(unique.add(candidate.text));
        }
    }

    @Test
    public void rejectsUnsafeContextInsteadOfGuessingAComment() {
        CommentAssistant.Result result = CommentAssistant.generate(
                "这段内容包含诈骗话术，请大家提高警惕。",
                CommentAssistant.Strategy.defaults());

        assertTrue(result.candidates.isEmpty());
        assertTrue(result.status.contains("人工处理"));
    }

    @Test
    public void emptyOrGenericTextDoesNotCreateUniversalSpam() {
        CommentAssistant.Result result = CommentAssistant.generate(
                "点赞 评论 分享",
                CommentAssistant.Strategy.defaults());

        assertTrue(result.candidates.isEmpty());
    }

    @Test
    public void publicPackagesCannotUseAutomatedEngagementActions() {
        assertFalse(EngagementPolicy.allowsAutomatedAction(
                "com.tencent.mm", "com.example.videobrowseassistant", EngagementPolicy.Action.LIKE,
                true, 0, 5));
        assertTrue(EngagementPolicy.allowsAutomatedAction(
                "com.example.videobrowseassistant", "com.example.videobrowseassistant",
                EngagementPolicy.Action.FAVORITE, true, 0, 5));
    }
}
