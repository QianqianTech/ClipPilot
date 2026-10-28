package com.example.videobrowseassistant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds the narrow, versioned request body accepted by CommentModelGateway. */
final class ModelGatewayProtocol {
    static final String VERSION = "comment-gateway.v1";
    private static final int MAX_KEYWORDS = 6;
    private static final int MAX_KEYWORD_CHARS = 14;

    private ModelGatewayProtocol() {
    }

    /**
     * Builds the request body with {@link JSONObject} so escaping is handled by the JSON library
     * rather than by hand-written string concatenation.
     */
    static String buildGenerationRequest(String requestId, String videoContext,
                                         CommentAssistant.Strategy strategy) {
        String normalizedRequestId = InteractionSession.normalize(requestId, 64);
        String context = InteractionSession.normalize(videoContext, InteractionSession.MAX_CONTEXT_CHARS);
        CommentAssistant.Strategy safeStrategy = strategy == null
                ? CommentAssistant.Strategy.defaults() : strategy;
        try {
            JSONArray keywords = new JSONArray();
            for (String keyword : keywordsFrom(safeStrategy.keywords)) {
                keywords.put(keyword);
            }
            JSONObject videoContextJson = new JSONObject()
                    .put("source", "accessibility_text")
                    .put("language", "zh-CN")
                    .put("text", context);
            JSONObject strategyJson = new JSONObject()
                    .put("keywords", keywords)
                    .put("direction", safeStrategy.direction.name())
                    .put("max_length", InteractionSession.MAX_DRAFT_CHARS);
            return new JSONObject()
                    .put("protocol_version", VERSION)
                    .put("request_id", normalizedRequestId)
                    .put("client_mode", "manual_comment_assist")
                    .put("video_context", videoContextJson)
                    .put("strategy", strategyJson)
                    .put("candidate_count", 3)
                    .toString();
        } catch (JSONException exception) {
            // Only thrown for non-finite numbers or null keys, neither of which is used here.
            throw new IllegalStateException("无法构建模型网关请求。", exception);
        }
    }

    static List<String> keywordsFrom(String rawKeywords) {
        String compact = InteractionSession.normalize(rawKeywords, 80);
        Set<String> seen = new LinkedHashSet<>();
        List<String> keywords = new ArrayList<>();
        if (!compact.isEmpty()) {
            for (String item : compact.split("[,，、/\\s]+")) {
                String keyword = InteractionSession.normalize(item, MAX_KEYWORD_CHARS);
                String fingerprint = keyword.toLowerCase(Locale.ROOT);
                if (keyword.length() >= 2 && !CommentAssistant.containsUnsafeTerm(keyword)
                        && seen.add(fingerprint)) {
                    keywords.add(keyword);
                    if (keywords.size() >= MAX_KEYWORDS) {
                        break;
                    }
                }
            }
        }
        return keywords;
    }
}
