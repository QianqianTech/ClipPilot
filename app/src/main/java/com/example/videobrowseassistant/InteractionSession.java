package com.example.videobrowseassistant;

import android.content.SharedPreferences;

import java.util.UUID;

/**
 * Shared, short-lived state for an explicitly user-triggered comment-assist flow.
 * Raw page text and comment drafts are intentionally treated as ephemeral session data.
 */
final class InteractionSession {
    static final String PREFS = SessionKeys.PREFS;
    static final String KEY_COMMENT_ASSIST_ENABLED = "comment_assist_enabled";
    static final String KEY_COMMENT_DISCLOSURE_ACCEPTED = "comment_disclosure_accepted";
    static final String KEY_COMMENT_PAUSED = "comment_paused";
    static final String KEY_COMMENT_CAPTURE_REQUEST_ID = "comment_capture_request_id";
    static final String KEY_COMMENT_CAPTURE_RESULT = "comment_capture_result";
    static final String KEY_COMMENT_CONTEXT = "comment_context";
    static final String KEY_COMMENT_CONTEXT_CAPTURED_AT = "comment_context_captured_at";
    static final String KEY_COMMENT_KEYWORDS = "comment_keywords";
    static final String KEY_COMMENT_DIRECTION = "comment_direction";
    static final String KEY_COMMENT_DRAFT = "comment_draft";
    static final String KEY_COMMENT_DRAFT_CREATED_AT = "comment_draft_created_at";
    static final String KEY_COMMENT_FILL_REQUEST_ID = "comment_fill_request_id";

    static final long CONTEXT_TTL_MS = 5L * 60L * 1000L;
    static final long DRAFT_TTL_MS = 10L * 60L * 1000L;
    static final int MAX_CONTEXT_CHARS = 360;
    static final int MAX_DRAFT_CHARS = 80;

    private InteractionSession() {
    }

    static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    static boolean isCommentAssistEnabled(SharedPreferences preferences) {
        return preferences != null && preferences.getBoolean(KEY_COMMENT_ASSIST_ENABLED, false);
    }

    static boolean isPaused(SharedPreferences preferences) {
        return preferences != null && preferences.getBoolean(KEY_COMMENT_PAUSED, false);
    }

    static boolean hasFreshContext(SharedPreferences preferences, long nowMs) {
        if (preferences == null) {
            return false;
        }
        String context = preferences.getString(KEY_COMMENT_CONTEXT, "");
        long capturedAt = preferences.getLong(KEY_COMMENT_CONTEXT_CAPTURED_AT, 0L);
        return !normalize(context, MAX_CONTEXT_CHARS).isEmpty()
                && capturedAt > 0L
                && nowMs >= capturedAt
                && nowMs - capturedAt <= CONTEXT_TTL_MS;
    }

    static String freshContext(SharedPreferences preferences, long nowMs) {
        boolean hasStoredContext = !preferences.getString(KEY_COMMENT_CONTEXT, "").isEmpty()
                || preferences.getLong(KEY_COMMENT_CONTEXT_CAPTURED_AT, 0L) > 0L
                || !preferences.getString(KEY_COMMENT_CAPTURE_RESULT, "").isEmpty();
        if (hasStoredContext && !hasFreshContext(preferences, nowMs)) {
            return "";
        }
        return normalize(preferences.getString(KEY_COMMENT_CONTEXT, ""), MAX_CONTEXT_CHARS);
    }

    static boolean hasFreshDraft(SharedPreferences preferences, long nowMs) {
        if (preferences == null) {
            return false;
        }
        String draft = preferences.getString(KEY_COMMENT_DRAFT, "");
        long createdAt = preferences.getLong(KEY_COMMENT_DRAFT_CREATED_AT, 0L);
        return !normalize(draft, MAX_DRAFT_CHARS).isEmpty()
                && createdAt > 0L
                && nowMs >= createdAt
                && nowMs - createdAt <= DRAFT_TTL_MS;
    }

    static String freshDraft(SharedPreferences preferences, long nowMs) {
        boolean hasStoredDraft = !preferences.getString(KEY_COMMENT_DRAFT, "").isEmpty()
                || preferences.getLong(KEY_COMMENT_DRAFT_CREATED_AT, 0L) > 0L
                || !preferences.getString(KEY_COMMENT_FILL_REQUEST_ID, "").isEmpty();
        if (hasStoredDraft && !hasFreshDraft(preferences, nowMs)) {
            return "";
        }
        return normalize(preferences.getString(KEY_COMMENT_DRAFT, ""), MAX_DRAFT_CHARS);
    }

    static void clearExpired(SharedPreferences preferences, long nowMs) {
        if (preferences == null) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        if (!hasFreshContext(preferences, nowMs)) {
            editor.remove(KEY_COMMENT_CONTEXT)
                    .remove(KEY_COMMENT_CONTEXT_CAPTURED_AT)
                    .remove(KEY_COMMENT_CAPTURE_RESULT);
            changed = true;
        }
        if (!hasFreshDraft(preferences, nowMs)) {
            editor.remove(KEY_COMMENT_DRAFT)
                    .remove(KEY_COMMENT_DRAFT_CREATED_AT)
                    .remove(KEY_COMMENT_FILL_REQUEST_ID);
            changed = true;
        }
        if (changed) {
            editor.apply();
        }
    }

    static void clearEphemeral(SharedPreferences.Editor editor) {
        editor.remove(KEY_COMMENT_PAUSED)
                .remove(KEY_COMMENT_CAPTURE_REQUEST_ID)
                .remove(KEY_COMMENT_CAPTURE_RESULT)
                .remove(KEY_COMMENT_CONTEXT)
                .remove(KEY_COMMENT_CONTEXT_CAPTURED_AT)
                .remove(KEY_COMMENT_DRAFT)
                .remove(KEY_COMMENT_DRAFT_CREATED_AT)
                .remove(KEY_COMMENT_FILL_REQUEST_ID);
    }

    static String normalize(String value, int maximumLength) {
        if (value == null || maximumLength <= 0) {
            return "";
        }
        String compact = value.replace('\u0000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return compact.length() <= maximumLength
                ? compact
                : compact.substring(0, maximumLength).trim();
    }
}
