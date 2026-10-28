package com.example.videobrowseassistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** HTTPS client for the server-owned CommentModelGateway. It never carries a provider API key. */
final class ModelGatewayClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_RESPONSE_CHARS = 16_000;
    private static final Set<Call> ACTIVE_CALLS = Collections.synchronizedSet(new HashSet<>());

    private ModelGatewayClient() {
    }

    static final class Call {
        private volatile HttpURLConnection connection;
        private volatile boolean cancelled;

        Result generate(ModelGatewayConfig.Config config, String deviceSessionToken,
                        String videoContext, CommentAssistant.Strategy strategy) {
            if (cancelled) {
                return Result.failure("已取消模型请求。", false);
            }
            if (config == null || !config.enabled || !config.hasEndpoint()) {
                return Result.failure("模型网关尚未启用或地址无效。", false);
            }
            String token = InteractionSession.normalize(deviceSessionToken, 512);
            if (token.length() < 16) {
                return Result.failure("请粘贴后台签发的短期设备令牌；它不会保存到本机。", false);
            }
            ACTIVE_CALLS.add(this);
            try {
                HttpURLConnection opened = (HttpURLConnection) new URL(config.generationUrl())
                        .openConnection();
                connection = opened;
                opened.setRequestMethod("POST");
                opened.setConnectTimeout(CONNECT_TIMEOUT_MS);
                opened.setReadTimeout(READ_TIMEOUT_MS);
                opened.setDoOutput(true);
                opened.setUseCaches(false);
                opened.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                opened.setRequestProperty("Accept", "application/json");
                opened.setRequestProperty("Authorization", "Bearer " + token);
                opened.setRequestProperty("X-Model-Profile", config.profile.storedValue);
                // The body request_id is the single correlation id; the gateway echoes it back
                // in the x-request-id response header.
                String requestId = InteractionSession.newRequestId();
                byte[] body = ModelGatewayProtocol.buildGenerationRequest(requestId, videoContext, strategy)
                        .getBytes(StandardCharsets.UTF_8);
                opened.setFixedLengthStreamingMode(body.length);
                opened.getOutputStream().write(body);
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    return Result.failure("已取消模型请求。", false);
                }
                int statusCode = opened.getResponseCode();
                String response = readLimited(statusCode >= 200 && statusCode < 300
                        ? opened.getInputStream() : opened.getErrorStream());
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    return Result.failure("已取消模型请求。", false);
                }
                if (statusCode < 200 || statusCode >= 300) {
                    return Result.failure(statusFor(statusCode), statusCode == 401 || statusCode == 403);
                }
                return parseResponse(response);
            } catch (Exception exception) {
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    return Result.failure("已取消模型请求。", false);
                }
                return Result.failure("模型网关暂时不可用；未自动重试，可改用本地候选。", false);
            } finally {
                HttpURLConnection opened = connection;
                connection = null;
                if (opened != null) {
                    opened.disconnect();
                }
                ACTIVE_CALLS.remove(this);
            }
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection opened = connection;
            if (opened != null) {
                opened.disconnect();
            }
        }
    }

    static void cancelActiveCalls() {
        Call[] snapshot;
        synchronized (ACTIVE_CALLS) {
            snapshot = ACTIVE_CALLS.toArray(new Call[0]);
        }
        for (Call call : snapshot) {
            call.cancel();
        }
    }

    /** Package-visible for unit tests. */
    static Result parseResponse(String response) {
        try {
            JSONObject body = new JSONObject(response == null ? "" : response);
            JSONArray rawCandidates = body.optJSONArray("candidates");
            if (rawCandidates == null) {
                return Result.failure("模型网关未返回可用候选；未自动改写或发布。", false);
            }
            List<CommentAssistant.Candidate> candidates = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int index = 0; index < rawCandidates.length() && candidates.size() < 3; index++) {
                JSONObject item = rawCandidates.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String text = InteractionSession.normalize(item.optString("text", ""),
                        InteractionSession.MAX_DRAFT_CHARS);
                String evidence = InteractionSession.normalize(item.optString("evidence_label", ""), 48);
                String fingerprint = text.replaceAll("[\\p{Punct}\\s]", "")
                        .toLowerCase(Locale.ROOT);
                if (text.length() < 6 || CommentAssistant.containsUnsafeTerm(text)
                        || fingerprint.length() < 6 || !seen.add(fingerprint)) {
                    continue;
                }
                String rationale = evidence.isEmpty()
                        ? "模型网关返回的关联候选"
                        : "模型依据：" + evidence;
                candidates.add(new CommentAssistant.Candidate(text, rationale));
            }
            if (candidates.isEmpty()) {
                return Result.failure("模型返回内容未通过本机安全与去重检查。", false);
            }
            if (body.optBoolean("context_review", false)) {
                return new Result(candidates,
                        "页面文字含需复核的内容，请逐条检查候选后再决定是否使用；仍需用户手动发送。",
                        false);
            }
            return Result.success(candidates);
        } catch (Exception ignored) {
            return Result.failure("模型网关返回格式无效；未使用该结果。", false);
        }
    }

    private static String readLimited(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
                StandardCharsets.UTF_8))) {
            char[] buffer = new char[512];
            int read;
            while ((read = reader.read(buffer)) != -1 && result.length() < MAX_RESPONSE_CHARS) {
                int remaining = MAX_RESPONSE_CHARS - result.length();
                result.append(buffer, 0, Math.min(read, remaining));
            }
        }
        return result.toString();
    }

    static String statusFor(int statusCode) {
        switch (statusCode) {
            case 400:
                return "模型请求未通过网关校验，请检查关键词和内容方向。";
            case 401:
            case 403:
                return "设备令牌无效或已过期，请从后台重新获取短期令牌。";
            case 408:
            case 504:
                return "模型网关请求超时；未自动重试，可改用本地候选。";
            case 429:
                return "模型网关已达到限额，请稍后再试或改用本地候选。";
            case 503:
                return "模型网关尚未完成后台模型配置。";
            default:
                return "模型网关请求失败；未自动重试，可改用本地候选。";
        }
    }

    static final class Result {
        final List<CommentAssistant.Candidate> candidates;
        final String status;
        final boolean authorizationFailure;

        Result(List<CommentAssistant.Candidate> candidates, String status,
                       boolean authorizationFailure) {
            this.candidates = candidates;
            this.status = status;
            this.authorizationFailure = authorizationFailure;
        }

        static Result success(List<CommentAssistant.Candidate> candidates) {
            return new Result(candidates, "模型网关已生成关联候选；仍需用户选择并手动发送。", false);
        }

        static Result failure(String status, boolean authorizationFailure) {
            return new Result(new ArrayList<>(), status, authorizationFailure);
        }
    }
}
