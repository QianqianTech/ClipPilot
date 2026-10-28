package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModelGatewayClientTest {
    @Test
    public void validCandidatesAreFilteredAndDeduplicated() {
        ModelGatewayClient.Result result = ModelGatewayClient.parseResponse("{\"candidates\":["
                + "{\"text\":\"这个学习计划拆得很清楚\",\"evidence_label\":\"学习计划\"},"
                + "{\"text\":\"这个学习计划拆得很清楚！\"},"
                + "{\"text\":\"太短\"},"
                + "{\"text\":\"有需要的私信我领取资料包\"},"
                + "{\"text\":\"期待后续分享更多执行细节\"},"
                + "{\"text\":\"第三条也很有帮助的样子\"},"
                + "{\"text\":\"第四条不应该出现在结果里\"}]}");
        assertEquals(3, result.candidates.size());
        assertEquals("模型依据：学习计划", result.candidates.get(0).rationale);
        assertFalse(result.authorizationFailure);
        for (CommentAssistant.Candidate candidate : result.candidates) {
            assertFalse(candidate.text.contains("私信我"));
        }
    }

    @Test
    public void contextReviewChangesTheStatusOnly() {
        ModelGatewayClient.Result result = ModelGatewayClient.parseResponse(
                "{\"context_review\":true,\"candidates\":[{\"text\":\"镜头语言的讨论很有深度\"}]}");
        assertEquals(1, result.candidates.size());
        assertTrue(result.status.contains("复核"));
    }

    @Test
    public void malformedOrEmptyResponsesFailClosed() {
        assertTrue(ModelGatewayClient.parseResponse("not json").candidates.isEmpty());
        assertTrue(ModelGatewayClient.parseResponse("{}").candidates.isEmpty());
        assertTrue(ModelGatewayClient.parseResponse("{\"candidates\":[{\"text\":\"短\"}]}").candidates.isEmpty());
        assertTrue(ModelGatewayClient.parseResponse(null).candidates.isEmpty());
    }

    @Test
    public void statusCodesMapToUserFacingMessages() {
        assertTrue(ModelGatewayClient.statusFor(401).contains("令牌"));
        assertTrue(ModelGatewayClient.statusFor(429).contains("限额"));
        assertTrue(ModelGatewayClient.statusFor(504).contains("超时"));
        assertTrue(ModelGatewayClient.statusFor(503).contains("配置"));
        assertTrue(ModelGatewayClient.statusFor(500).contains("失败"));
    }
}
