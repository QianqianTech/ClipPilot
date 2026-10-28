package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class ModelGatewayProtocolTest {
    @Test
    public void requestIsBoundedAndCarriesOnlyManualCommentFields() {
        String request = ModelGatewayProtocol.buildGenerationRequest(
                "req-123",
                "今天分享如何把学习计划拆成每周都能完成的小目标。",
                new CommentAssistant.Strategy("学习计划，效率，学习计划", CommentAssistant.Direction.ADD_VALUE));

        assertTrue(request.contains("\"protocol_version\":\"comment-gateway.v1\""));
        assertTrue(request.contains("\"client_mode\":\"manual_comment_assist\""));
        assertTrue(request.contains("\"source\":\"accessibility_text\""));
        assertTrue(request.contains("\"keywords\":[\"学习计划\",\"效率\"]"));
        assertTrue(request.contains("\"direction\":\"ADD_VALUE\""));
        assertFalse(request.contains("api_key"));
        assertFalse(request.contains("account"));
    }

    @Test
    public void unsafeAndDuplicateKeywordsAreRemovedBeforeNetworkRequest() {
        assertEquals(Arrays.asList("AI", "学习计划"), ModelGatewayProtocol.keywordsFrom(
                "AI，诈骗，学习计划，AI，暴力"));
    }

    @Test
    public void controlCharactersAndQuotesCannotBreakTheRequestEnvelope() throws Exception {
        String context = "一行\n\"文本\"\\结束，补充一些足够长的文字";
        String request = ModelGatewayProtocol.buildGenerationRequest("req-456", context,
                CommentAssistant.Strategy.defaults());
        JSONObject parsed = new JSONObject(request);
        JSONObject videoContext = parsed.optJSONObject("video_context");
        assertEquals("req-456", parsed.optString("request_id", ""));
        assertEquals(InteractionSession.normalize(context, InteractionSession.MAX_CONTEXT_CHARS),
                videoContext.optString("text", ""));
    }
}
