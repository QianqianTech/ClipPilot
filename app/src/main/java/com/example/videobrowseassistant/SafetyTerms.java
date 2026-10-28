package com.example.videobrowseassistant;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Local mirror of {@code gateway/config/safety-terms.json}, the source of truth shared with the
 * model gateway. {@code SafetyTermsTest} reads that file and fails when this copy drifts.
 *
 * <p>Algorithm (identical to {@code gateway/src/safety-terms.mjs}): lowercase the text, remove
 * every allow phrase in order, then any block term means {@link Level#BLOCK}, else any review
 * term means {@link Level#REVIEW}, else {@link Level#OK}.</p>
 */
final class SafetyTerms {
    static final String VERSION = "2026-10-08.1";

    static final List<String> BLOCK = Collections.unmodifiableList(Arrays.asList(
            "仇恨", "自杀", "自残", "色情", "赌博", "诈骗", "极端主义", "恐怖袭击", "毒品", "枪支",
            "约炮", "裸聊"));
    static final List<String> REVIEW = Collections.unmodifiableList(Arrays.asList(
            "暴力", "血腥", "歧视", "辱骂", "人身攻击", "加微信", "加v", "私信我", "刷单", "返利",
            "外链"));
    static final List<String> ALLOW_PHRASES = Collections.unmodifiableList(Arrays.asList(
            "反诈骗", "防诈骗", "谨防诈骗", "识别诈骗", "暴力美学", "远离赌博", "禁毒宣传"));

    enum Level {
        OK,
        REVIEW,
        BLOCK
    }

    private SafetyTerms() {
    }

    static Level classify(String value) {
        if (value == null || value.isEmpty()) {
            return Level.OK;
        }
        String text = value.toLowerCase(Locale.ROOT);
        for (String phrase : ALLOW_PHRASES) {
            text = text.replace(phrase.toLowerCase(Locale.ROOT), "");
        }
        for (String term : BLOCK) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) {
                return Level.BLOCK;
            }
        }
        for (String term : REVIEW) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) {
                return Level.REVIEW;
            }
        }
        return Level.OK;
    }

    /** Context that must not be processed at all. */
    static boolean isBlocked(String value) {
        return classify(value) == Level.BLOCK;
    }

    /** Text that must not appear in a keyword or an outgoing comment candidate. */
    static boolean isUnsafeForOutput(String value) {
        return classify(value) != Level.OK;
    }
}
