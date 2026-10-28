package com.example.videobrowseassistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure-Java local fallback while a server-side model gateway is not configured. */
final class CommentAssistant {
    private static final int MAX_CANDIDATES = 3;
    private static final Set<String> GENERIC_CONTROL_TERMS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("点赞", "评论", "分享", "收藏", "关注", "转发", "搜索", "直播")));

    private CommentAssistant() {
    }

    static Result generate(String videoContext, Strategy strategy) {
        String context = InteractionSession.normalize(videoContext, InteractionSession.MAX_CONTEXT_CHARS);
        Strategy safeStrategy = strategy == null ? Strategy.defaults() : strategy;
        if (context.length() < 8) {
            return Result.empty("当前页面的可用文字不足，请在视频页点击“暂停并读取”后重试。");
        }
        if (SafetyTerms.isBlocked(context) || containsUnsafeTerm(safeStrategy.keywords)) {
            return Result.empty("当前内容或关键词需要人工处理，未生成候选评论。");
        }

        String anchor = extractAnchor(context);
        String topic = firstTopic(safeStrategy.keywords, anchor);
        if (anchor.isEmpty() || topic.isEmpty()) {
            return Result.empty("无法提取足够明确的主题，未生成泛化评论。");
        }

        List<String> drafts = templatesFor(safeStrategy.direction, anchor, topic);
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String draft : drafts) {
            String normalized = InteractionSession.normalize(draft, InteractionSession.MAX_DRAFT_CHARS);
            if (normalized.isEmpty() || containsUnsafeTerm(normalized)) {
                continue;
            }
            String fingerprint = normalized.replaceAll("[\\p{Punct}\\s]", "").toLowerCase(Locale.ROOT);
            if (fingerprint.length() < 6 || !seen.add(fingerprint)) {
                continue;
            }
            candidates.add(new Candidate(normalized, "围绕“" + topic + "”与当前视频文字生成"));
            if (candidates.size() == MAX_CANDIDATES) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            return Result.empty("安全过滤后没有可用候选评论。");
        }
        return new Result(candidates, "本地关联候选（模型网关尚未配置）");
    }

    /**
     * True when text must not be used as a keyword or appear in a candidate. Delegates to the
     * shared {@link SafetyTerms} vocabulary (block or review level).
     */
    static boolean containsUnsafeTerm(String value) {
        return SafetyTerms.isUnsafeForOutput(value);
    }

    private static String extractAnchor(String context) {
        String[] sentences = context.split("[。！？；\\n]");
        for (String sentence : sentences) {
            String clean = InteractionSession.normalize(sentence, 24);
            if (clean.length() >= 4 && !isGenericControlText(clean)) {
                return clean;
            }
        }
        String fallback = InteractionSession.normalize(context, 20);
        return isGenericControlText(fallback) ? "" : fallback;
    }

    private static String firstTopic(String keywords, String anchor) {
        String cleanKeywords = InteractionSession.normalize(keywords, 80);
        if (!cleanKeywords.isEmpty()) {
            for (String item : cleanKeywords.split("[,，、/\\s]+")) {
                String topic = InteractionSession.normalize(item, 14);
                if (topic.length() >= 2 && !containsUnsafeTerm(topic)) {
                    return topic;
                }
            }
        }
        String normalizedAnchor = anchor.replaceAll("[\\p{Punct}\\s]", "");
        return normalizedAnchor.length() >= 4
                ? normalizedAnchor.substring(0, Math.min(8, normalizedAnchor.length()))
                : normalizedAnchor;
    }

    private static boolean isGenericControlText(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        String compact = text.replaceAll("[\\p{Punct}\\s]", "");
        if (compact.length() <= 2) {
            return true;
        }
        for (String term : GENERIC_CONTROL_TERMS) {
            if (compact.equals(term)) {
                return true;
            }
        }
        String remainder = compact;
        for (String term : GENERIC_CONTROL_TERMS) {
            remainder = remainder.replace(term, "");
        }
        return remainder.isEmpty();
    }

    private static List<String> templatesFor(Direction direction, String anchor, String topic) {
        String quoted = "“" + anchor + "”";
        switch (direction) {
            case QUESTION:
                return Arrays.asList(
                        quoted + "很有启发，想请教一下“" + topic + "”接下来会怎么实践？",
                        "看到“" + topic + "”这一点很受启发，期待后续能分享更多具体经验。",
                        quoted + "说得很清楚，关于“" + topic + "”还有没有推荐的入门方法？");
            case PROFESSIONAL:
                return Arrays.asList(
                        quoted + "把“" + topic + "”的重点讲得很清楚，很有参考价值。",
                        "这段对“" + topic + "”的拆解很有条理，值得结合实际慢慢验证。",
                        "“" + topic + "”这个角度很实在，感谢把关键点说得这么明白。 ");
            case LIGHT:
                return Arrays.asList(
                        quoted + "一下点醒我了，“" + topic + "”原来还能这样理解。",
                        "这段关于“" + topic + "”的分享很上头，已经默默记下重点。",
                        "原来“" + topic + "”可以讲得这么好懂，收获到了。 ");
            case ADD_VALUE:
                return Arrays.asList(
                        quoted + "给了很清晰的思路，感觉“" + topic + "”还可以结合自己的情况慢慢验证。",
                        "很认同“" + topic + "”这个方向，实际落地时做好节奏安排会更稳。",
                        "这段关于“" + topic + "”的提醒很及时，感谢分享实用思路。 ");
            case EMPATHY:
            default:
                return Arrays.asList(
                        quoted + "很有共鸣，尤其是“" + topic + "”这一点值得反复看看。",
                        "关于“" + topic + "”的分享很真实，听完很受触动。",
                        "这段说得很到位，“" + topic + "”确实是很多人都会遇到的感受。 ");
        }
    }

    enum Direction {
        EMPATHY("共鸣"),
        QUESTION("提问"),
        ADD_VALUE("补充观点"),
        PROFESSIONAL("专业点评"),
        LIGHT("轻松幽默");

        final String label;

        Direction(String label) {
            this.label = label;
        }

        static Direction fromStored(String stored) {
            for (Direction direction : values()) {
                if (direction.name().equals(stored)) {
                    return direction;
                }
            }
            return EMPATHY;
        }
    }

    static final class Strategy {
        final String keywords;
        final Direction direction;

        Strategy(String keywords, Direction direction) {
            this.keywords = InteractionSession.normalize(keywords, 80);
            this.direction = direction == null ? Direction.EMPATHY : direction;
        }

        static Strategy defaults() {
            return new Strategy("", Direction.EMPATHY);
        }
    }

    static final class Candidate {
        final String text;
        final String rationale;

        Candidate(String text, String rationale) {
            this.text = text;
            this.rationale = rationale;
        }
    }

    static final class Result {
        final List<Candidate> candidates;
        final String status;

        Result(List<Candidate> candidates, String status) {
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.status = status;
        }

        static Result empty(String status) {
            return new Result(Collections.emptyList(), status);
        }
    }
}
