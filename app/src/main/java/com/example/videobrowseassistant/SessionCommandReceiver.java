package com.example.videobrowseassistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Handles the visible session controls attached to the persistent notification. It persists the
 * resulting state and then notifies the running service through {@link SessionCommands}.
 */
public final class SessionCommandReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP = "com.example.videobrowseassistant.action.STOP_SESSION";
    public static final String ACTION_PAUSE_AND_CAPTURE =
            "com.example.videobrowseassistant.action.PAUSE_AND_CAPTURE";
    public static final String ACTION_RESUME_BROWSING =
            "com.example.videobrowseassistant.action.RESUME_BROWSING";
    public static final String ACTION_FILL_COMMENT_DRAFT =
            "com.example.videobrowseassistant.action.FILL_COMMENT_DRAFT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(SessionKeys.PREFS,
                Context.MODE_PRIVATE);
        Result result = handle(preferences, action, context.getString(summaryFor(action)),
                System.currentTimeMillis());
        switch (result) {
            case STOPPED:
                ModelGatewayClient.cancelActiveCalls();
                Diagnostics.info(context, "用户通过通知栏停止了多平台任务。");
                if (!SessionCommands.dispatch(SessionCommands.Command.SESSION_STOPPED)) {
                    SessionNotifications.cancel(context);
                }
                break;
            case PAUSED:
                Diagnostics.info(context, "用户从通知栏暂停浏览并请求读取当前页面文字。");
                SessionCommands.dispatch(SessionCommands.Command.PAUSE_AND_CAPTURE);
                break;
            case FILL_REQUESTED:
                Diagnostics.info(context, "用户请求把已选草稿填入当前评论输入框；不会发送。");
                SessionCommands.dispatch(SessionCommands.Command.FILL_DRAFT);
                break;
            case RESUMED:
                ModelGatewayClient.cancelActiveCalls();
                Diagnostics.info(context, "用户从通知栏恢复浏览，已清除短时评论内容。");
                SessionCommands.dispatch(SessionCommands.Command.RESUME_BROWSING);
                break;
            case IGNORED_NOT_RUNNING:
                Diagnostics.error(context, "互动操作已忽略：当前没有运行中的会话。");
                break;
            case IGNORED_ASSIST_DISABLED:
                Diagnostics.error(context, "互动操作已忽略：请先在助手中开启智能评论辅助。");
                break;
            case IGNORED_NO_DRAFT:
                Diagnostics.error(context, "填入草稿已忽略：没有可用的暂停会话或草稿已过期。");
                break;
            case IGNORED_UNKNOWN:
            default:
                break;
        }
    }

    enum Result {
        STOPPED,
        PAUSED,
        FILL_REQUESTED,
        RESUMED,
        IGNORED_NOT_RUNNING,
        IGNORED_ASSIST_DISABLED,
        IGNORED_NO_DRAFT,
        IGNORED_UNKNOWN
    }

    private static int summaryFor(String action) {
        switch (action) {
            case ACTION_STOP:
                return R.string.summary_stopped_from_notification;
            case ACTION_PAUSE_AND_CAPTURE:
                return R.string.summary_pausing_for_capture;
            case ACTION_FILL_COMMENT_DRAFT:
                return R.string.summary_waiting_for_fill;
            case ACTION_RESUME_BROWSING:
            default:
                return R.string.summary_resumed;
        }
    }

    /** State transition for one notification action; free of Android framework calls for tests. */
    static Result handle(SharedPreferences preferences, String action, String summary, long now) {
        if (ACTION_STOP.equals(action)) {
            SessionStore.stop(preferences, summary, false, now);
            return Result.STOPPED;
        }
        if (!ACTION_PAUSE_AND_CAPTURE.equals(action)
                && !ACTION_FILL_COMMENT_DRAFT.equals(action)
                && !ACTION_RESUME_BROWSING.equals(action)) {
            return Result.IGNORED_UNKNOWN;
        }
        if (!SessionStore.isRunning(preferences)) {
            return Result.IGNORED_NOT_RUNNING;
        }
        if (ACTION_PAUSE_AND_CAPTURE.equals(action)) {
            if (!InteractionSession.isCommentAssistEnabled(preferences)) {
                return Result.IGNORED_ASSIST_DISABLED;
            }
            preferences.edit()
                    .putBoolean(InteractionSession.KEY_COMMENT_PAUSED, true)
                    .putString(InteractionSession.KEY_COMMENT_CAPTURE_REQUEST_ID,
                            InteractionSession.newRequestId())
                    .remove(InteractionSession.KEY_COMMENT_CAPTURE_RESULT)
                    .remove(InteractionSession.KEY_COMMENT_CONTEXT)
                    .remove(InteractionSession.KEY_COMMENT_CONTEXT_CAPTURED_AT)
                    .putString(SessionKeys.SESSION_SUMMARY, summary)
                    .apply();
            return Result.PAUSED;
        }
        if (ACTION_FILL_COMMENT_DRAFT.equals(action)) {
            if (!InteractionSession.isPaused(preferences)
                    || !InteractionSession.hasFreshDraft(preferences, now)) {
                return Result.IGNORED_NO_DRAFT;
            }
            preferences.edit()
                    .putString(InteractionSession.KEY_COMMENT_FILL_REQUEST_ID,
                            InteractionSession.newRequestId())
                    .putString(SessionKeys.SESSION_SUMMARY, summary)
                    .apply();
            return Result.FILL_REQUESTED;
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(InteractionSession.KEY_COMMENT_PAUSED, false)
                .putString(SessionKeys.SESSION_SUMMARY, summary);
        InteractionSession.clearEphemeral(editor);
        editor.apply();
        return Result.RESUMED;
    }
}
