package com.example.videobrowseassistant;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The explicitly user-triggered comment-assist steps that need window access: reading the paused
 * page's text and writing a prepared draft into the already focused input field. It never sends,
 * clicks or focuses anything by itself.
 */
final class CommentWorkflow {
    interface Listener {
        /** Called after a request finished so the service can refresh its notification. */
        void onCommentWorkflowUpdated(String phase);
    }

    private final AccessibilityService service;
    private final SharedPreferences preferences;
    private final Listener listener;

    CommentWorkflow(AccessibilityService service, SharedPreferences preferences, Listener listener) {
        this.service = service;
        this.preferences = preferences;
        this.listener = listener;
    }

    /** Handles pending capture/fill requests for {@code targetPackage}; safe to call repeatedly. */
    void processPending(String targetPackage) {
        if (preferences == null
                || !SessionStore.isRunning(preferences)
                || !InteractionSession.isPaused(preferences)
                || targetPackage == null || targetPackage.isEmpty()) {
            return;
        }
        captureIfRequested(targetPackage);
        fillIfRequested(targetPackage);
    }

    private void captureIfRequested(String targetPackage) {
        String requestId = preferences.getString(InteractionSession.KEY_COMMENT_CAPTURE_REQUEST_ID, "");
        if (requestId.isEmpty()) {
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!isPackageRoot(root, targetPackage)) {
            // Not in the target app yet; the request stays pending until it returns to the front.
            ContentScanner.recycle(root);
            return;
        }
        ContentScanner.Result scan = ContentScanner.collect(root, ContentScanner.Limits.DEFAULT);
        String visibleText = scan.text;
        if (visibleText.length() < 8) {
            finishCapture(requestId, service.getString(R.string.comment_capture_insufficient), false);
            return;
        }
        long now = System.currentTimeMillis();
        preferences.edit()
                .remove(InteractionSession.KEY_COMMENT_CAPTURE_REQUEST_ID)
                .putString(InteractionSession.KEY_COMMENT_CONTEXT, visibleText)
                .putLong(InteractionSession.KEY_COMMENT_CONTEXT_CAPTURED_AT, now)
                .putString(InteractionSession.KEY_COMMENT_CAPTURE_RESULT,
                        service.getString(R.string.comment_capture_result, visibleText.length()))
                .putString(SessionKeys.SESSION_SUMMARY, service.getString(R.string.summary_captured))
                .apply();
        Diagnostics.info(service, "已在用户明确请求后读取当前页面 " + visibleText.length()
                + " 个字符（扫描节点 " + scan.nodesScanned + " · 文本 " + scan.textsCollected
                + "），用于评论候选；未记录原文到诊断。");
        listener.onCommentWorkflowUpdated(service.getString(R.string.phase_captured));
    }

    private void finishCapture(String requestId, String message, boolean success) {
        String pending = preferences.getString(InteractionSession.KEY_COMMENT_CAPTURE_REQUEST_ID, "");
        if (!requestId.equals(pending)) {
            return;
        }
        preferences.edit()
                .remove(InteractionSession.KEY_COMMENT_CAPTURE_REQUEST_ID)
                .putString(InteractionSession.KEY_COMMENT_CAPTURE_RESULT, message)
                .putString(SessionKeys.SESSION_SUMMARY, message)
                .apply();
        if (success) {
            Diagnostics.info(service, "评论文字读取完成。");
        } else {
            Diagnostics.error(service, "评论文字读取未完成：可访问文字不足。");
        }
        listener.onCommentWorkflowUpdated(service.getString(R.string.phase_comment_paused));
    }

    private void fillIfRequested(String targetPackage) {
        String requestId = preferences.getString(InteractionSession.KEY_COMMENT_FILL_REQUEST_ID, "");
        if (requestId.isEmpty()) {
            return;
        }
        String draft = InteractionSession.freshDraft(preferences, System.currentTimeMillis());
        if (draft.isEmpty()) {
            finishFill(requestId, service.getString(R.string.comment_fill_expired), false);
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!isPackageRoot(root, targetPackage)) {
            ContentScanner.recycle(root);
            return;
        }
        if (setTextOnFocusedEditableNode(root, draft)) {
            finishFill(requestId, service.getString(R.string.comment_fill_done), true);
        } else {
            finishFill(requestId, service.getString(R.string.comment_fill_no_focus), false);
        }
    }

    private void finishFill(String requestId, String message, boolean success) {
        String pending = preferences.getString(InteractionSession.KEY_COMMENT_FILL_REQUEST_ID, "");
        if (!requestId.equals(pending)) {
            return;
        }
        preferences.edit()
                .remove(InteractionSession.KEY_COMMENT_FILL_REQUEST_ID)
                .putString(SessionKeys.SESSION_SUMMARY, message)
                .apply();
        if (success) {
            Diagnostics.info(service, "评论草稿已填入已聚焦输入框；发送动作仍由用户完成。");
        } else {
            Diagnostics.error(service, "评论草稿未填入：草稿已过期或没有已聚焦的输入框。");
        }
        listener.onCommentWorkflowUpdated(service.getString(R.string.phase_comment_paused));
    }

    static boolean isPackageRoot(AccessibilityNodeInfo root, String packageName) {
        return root != null && root.getPackageName() != null
                && packageName.equals(root.getPackageName().toString());
    }

    /** Writes into the focused, visible, editable node only; recycles every node it touched. */
    private static boolean setTextOnFocusedEditableNode(AccessibilityNodeInfo root, String draft) {
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        boolean applied = false;
        boolean found = false;
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (!found && node.isVisibleToUser() && node.isEditable() && node.isFocused()) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft);
                applied = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                found = true;
            } else if (!found) {
                for (int index = 0; index < node.getChildCount(); index++) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) {
                        pending.addLast(child);
                    }
                }
            }
            ContentScanner.recycle(node);
        }
        return applied;
    }
}
