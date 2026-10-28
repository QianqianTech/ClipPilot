package com.example.videobrowseassistant;

import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local, deterministic content targeting for the current visible video page.
 *
 * <p>The class only works on text supplied by the accessibility service in memory. It never
 * persists source text, titles, captions, account names, or page-node trees. The only persisted
 * values are user-selected terms, a frozen session snapshot, and aggregate counters.</p>
 */
final class ContentTargeting {
    static final String KEY_ENABLED = "content_targeting_enabled";
    static final String KEY_DISCLOSURE_ACCEPTED = "content_targeting_disclosure_accepted";
    static final String KEY_INCLUDE_TERMS = "content_targeting_include_terms";
    static final String KEY_EXCLUDE_TERMS = "content_targeting_exclude_terms";

    static final String KEY_SESSION_ENABLED = "content_targeting_session_enabled";
    static final String KEY_SESSION_INCLUDE_TERMS = "content_targeting_session_include_terms";
    static final String KEY_SESSION_EXCLUDE_TERMS = "content_targeting_session_exclude_terms";
    static final String KEY_SESSION_MAX_SCANS = "content_targeting_session_max_scans";
    static final String KEY_ACTIVE_SCANS = "content_targeting_active_scans";
    static final String KEY_ACTIVE_NO_MATCHES = "content_targeting_active_no_matches";
    static final String KEY_ACTIVE_EXCLUDED = "content_targeting_active_excluded";
    static final String KEY_ACTIVE_UNKNOWN = "content_targeting_active_unknown";
    static final String KEY_ACTIVE_CONSECUTIVE_MISSES = "content_targeting_active_consecutive_misses";
    static final String KEY_LAST_DECISION = "content_targeting_last_decision";
    static final String KEY_LAST_REASON = "content_targeting_last_reason";
    static final String KEY_TOTAL_SCANS = "content_targeting_total_scans";

    static final int MAX_TERM_INPUT_CHARS = 120;
    static final int DEFAULT_SCAN_MULTIPLIER = 5;
    static final int MAX_SCANS_PER_PLATFORM = 100;
    static final int MAX_CONSECUTIVE_MISSES = 15;
    static final int MIN_CONTEXT_CHARS = 8;

    private static final Pattern MENTION = Pattern.compile("@[^\\s@，。,.!！？?；;]+");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");
    private static final Set<String> CONTROL_LABELS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList(
                    "点赞", "评论", "分享", "收藏", "关注", "转发", "搜索", "直播", "更多")));

    private ContentTargeting() {
    }

    static Config savedConfig(SharedPreferences preferences) {
        if (preferences == null) {
            return Config.disabled();
        }
        return new Config(
                preferences.getBoolean(KEY_ENABLED, false),
                preferences.getString(KEY_INCLUDE_TERMS, ""),
                preferences.getString(KEY_EXCLUDE_TERMS, ""));
    }

    static Config sessionConfig(SharedPreferences preferences) {
        if (preferences == null || !preferences.getBoolean(KEY_SESSION_ENABLED, false)) {
            return Config.disabled();
        }
        return new Config(
                true,
                preferences.getString(KEY_SESSION_INCLUDE_TERMS, ""),
                preferences.getString(KEY_SESSION_EXCLUDE_TERMS, ""));
    }

    static boolean isSessionEnabled(SharedPreferences preferences) {
        return preferences != null
                && preferences.getBoolean(KEY_SESSION_ENABLED, false)
                && sessionConfig(preferences).hasIncludeTerms();
    }

    static int maxScansForQuota(int quota) {
        int safeQuota = Math.max(1, quota);
        return Math.min(MAX_SCANS_PER_PLATFORM,
                Math.max(safeQuota, safeQuota * DEFAULT_SCAN_MULTIPLIER));
    }

    static void snapshotSession(SharedPreferences.Editor editor, Config config, int targetQuota) {
        clearSession(editor);
        if (editor == null || config == null || !config.isReady()) {
            return;
        }
        editor.putBoolean(KEY_SESSION_ENABLED, true)
                .putString(KEY_SESSION_INCLUDE_TERMS, config.includeTermsText)
                .putString(KEY_SESSION_EXCLUDE_TERMS, config.excludeTermsText)
                .putInt(KEY_SESSION_MAX_SCANS, maxScansForQuota(targetQuota))
                .putInt(KEY_ACTIVE_SCANS, 0)
                .putInt(KEY_ACTIVE_NO_MATCHES, 0)
                .putInt(KEY_ACTIVE_EXCLUDED, 0)
                .putInt(KEY_ACTIVE_UNKNOWN, 0)
                .putInt(KEY_ACTIVE_CONSECUTIVE_MISSES, 0)
                .putInt(KEY_TOTAL_SCANS, 0)
                .putString(KEY_LAST_DECISION, "")
                .putString(KEY_LAST_REASON, "");
    }

    static void resetPlatformCounters(SharedPreferences.Editor editor) {
        if (editor == null) {
            return;
        }
        editor.putInt(KEY_ACTIVE_SCANS, 0)
                .putInt(KEY_ACTIVE_NO_MATCHES, 0)
                .putInt(KEY_ACTIVE_EXCLUDED, 0)
                .putInt(KEY_ACTIVE_UNKNOWN, 0)
                .putInt(KEY_ACTIVE_CONSECUTIVE_MISSES, 0)
                .putString(KEY_LAST_DECISION, "")
                .putString(KEY_LAST_REASON, "");
    }

    static void clearSession(SharedPreferences.Editor editor) {
        if (editor == null) {
            return;
        }
        editor.remove(KEY_SESSION_ENABLED)
                .remove(KEY_SESSION_INCLUDE_TERMS)
                .remove(KEY_SESSION_EXCLUDE_TERMS)
                .remove(KEY_SESSION_MAX_SCANS)
                .remove(KEY_ACTIVE_SCANS)
                .remove(KEY_ACTIVE_NO_MATCHES)
                .remove(KEY_ACTIVE_EXCLUDED)
                .remove(KEY_ACTIVE_UNKNOWN)
                .remove(KEY_ACTIVE_CONSECUTIVE_MISSES)
                .remove(KEY_LAST_DECISION)
                .remove(KEY_LAST_REASON)
                .remove(KEY_TOTAL_SCANS);
    }

    static Decision decide(String visibleText, Config config) {
        if (config == null || !config.isReady()) {
            return Decision.unknown("未配置可用的内容方向");
        }
        // Author handles and @-mentions are not part of the video topic; a mention such as
        // "@学习博主" must not make the page look like it is about "学习".
        String withoutMentions = MENTION.matcher(
                InteractionSession.normalize(visibleText, MAX_TERM_INPUT_CHARS * 4)).replaceAll(" ");
        String searchText = compactForSearch(withoutMentions);
        if (searchText.length() < MIN_CONTEXT_CHARS) {
            return Decision.unknown("当前页面可用文字不足");
        }
        String loweredText = withoutMentions.toLowerCase(Locale.ROOT);
        for (int index = 0; index < config.excludeTerms.size(); index++) {
            String term = config.excludeTerms.get(index);
            if (matchesTerm(loweredText, searchText, term)) {
                return Decision.excluded(term, index + 1);
            }
        }
        for (int index = 0; index < config.includeTerms.size(); index++) {
            String term = config.includeTerms.get(index);
            if (matchesTerm(loweredText, searchText, term)) {
                return Decision.match(term, index + 1);
            }
        }
        return Decision.noMatch("未命中所选方向");
    }

    /**
     * ASCII words such as "AI" must match on token boundaries ("用AI做" yes, "SAID" no). Other
     * terms (CJK or mixed) are matched against the punctuation-free compact text.
     */
    static boolean matchesTerm(String loweredText, String compactText, String term) {
        String compactTerm = compactForSearch(term);
        if (compactTerm.isEmpty()) {
            return false;
        }
        if (ASCII_WORD.matcher(compactTerm).matches()) {
            String loweredTerm = InteractionSession.normalize(term, 24).toLowerCase(Locale.ROOT);
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(loweredTerm) + "(?![a-z0-9])")
                    .matcher(loweredText).find();
        }
        return compactText.contains(compactTerm);
    }

    /**
     * A compact, session-memory-only fingerprint used to suppress duplicate cards. SHA-256 keeps
     * collisions negligible and does not reveal the source text.
     */
    static String fingerprint(String visibleText) {
        String compact = compactForSearch(visibleText);
        if (compact.isEmpty()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(compact.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandatory on every Java and Android runtime.
            throw new IllegalStateException(exception);
        }
    }

    static String sanitizeTerms(String value) {
        List<String> terms = parseTerms(value);
        return String.join("，", terms);
    }

    static List<String> parseTerms(String value) {
        String normalized = InteractionSession.normalize(value, MAX_TERM_INPUT_CHARS);
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String item : normalized.split("[,，、;；/\\n\\r]+")) {
            String term = InteractionSession.normalize(item, 24);
            String compact = compactForSearch(term);
            if (compact.length() >= 2 && !CONTROL_LABELS.contains(compact)) {
                unique.add(term);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    private static String compactForSearch(String value) {
        String normalized = InteractionSession.normalize(value, MAX_TERM_INPUT_CHARS * 4)
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\p{Punct}\\s，。！？；：、“”‘’（）【】《》]+", "");
    }

    static final class Config {
        final boolean enabled;
        final String includeTermsText;
        final String excludeTermsText;
        final List<String> includeTerms;
        final List<String> excludeTerms;

        Config(boolean enabled, String includeTermsText, String excludeTermsText) {
            this.enabled = enabled;
            this.includeTerms = parseTerms(includeTermsText);
            this.excludeTerms = parseTerms(excludeTermsText);
            this.includeTermsText = String.join("，", this.includeTerms);
            this.excludeTermsText = String.join("，", this.excludeTerms);
        }

        static Config disabled() {
            return new Config(false, "", "");
        }

        boolean hasIncludeTerms() {
            return !includeTerms.isEmpty();
        }

        boolean isReady() {
            return enabled && hasIncludeTerms();
        }
    }

    static final class Decision {
        enum Kind {
            MATCH,
            NO_MATCH,
            EXCLUDED,
            UNKNOWN
        }

        final Kind kind;
        /** User-facing reason; may contain the user's own term and is never written to diagnostics. */
        final String reason;
        /** 1-based position of the matching term in its list, or 0 when no term matched. */
        final int termIndex;

        private Decision(Kind kind, String reason, int termIndex) {
            this.kind = kind;
            this.reason = InteractionSession.normalize(reason, 40);
            this.termIndex = Math.max(0, termIndex);
        }

        static Decision match(String term, int termIndex) {
            return new Decision(Kind.MATCH, "命中「" + term + "」", termIndex);
        }

        static Decision excluded(String term, int termIndex) {
            return new Decision(Kind.EXCLUDED, "排除「" + term + "」", termIndex);
        }

        static Decision noMatch(String reason) {
            return new Decision(Kind.NO_MATCH, reason, 0);
        }

        static Decision unknown(String reason) {
            return new Decision(Kind.UNKNOWN, reason, 0);
        }

        boolean countsAsMatch() {
            return kind == Kind.MATCH;
        }

        boolean countsAsMiss() {
            return kind != Kind.MATCH;
        }

        /**
         * Desensitized label for local diagnostics: the decision kind plus the term position
         * ("想看#2" / "不想看#1"), never the term itself.
         */
        String diagnosticLabel() {
            switch (kind) {
                case MATCH:
                    return "MATCH（想看#" + termIndex + "）";
                case EXCLUDED:
                    return "EXCLUDED（不想看#" + termIndex + "）";
                case UNKNOWN:
                    return "UNKNOWN";
                case NO_MATCH:
                default:
                    return "NO_MATCH";
            }
        }
    }
}
