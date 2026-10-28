package com.example.videobrowseassistant;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects a bounded amount of visible, non-editable text from the current window.
 *
 * <p>Node text ({@code getText()}) is preferred over content descriptions because titles and
 * captions are usually plain text, while descriptions are often icon labels. The traversal is
 * breadth-first with configurable node and text limits so deep Compose / React Native trees are
 * not cut off before the caption is reached. The result reports how much was scanned so device
 * diagnostics can show why a page was classified as "unknown".</p>
 */
final class ContentScanner {
    private static final Set<String> CONTROL_LABELS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("点赞", "评论", "分享", "收藏", "关注", "转发", "搜索", "直播", "更多",
                    "like", "comment", "share", "save", "follow", "search", "more")));

    static final class Limits {
        static final Limits DEFAULT = new Limits(220, 16);

        final int maxNodes;
        final int maxTexts;

        Limits(int maxNodes, int maxTexts) {
            this.maxNodes = Math.max(1, maxNodes);
            this.maxTexts = Math.max(1, maxTexts);
        }
    }

    static final class Result {
        static final Result EMPTY = new Result("", 0, 0);

        final String text;
        final int nodesScanned;
        final int textsCollected;

        Result(String text, int nodesScanned, int textsCollected) {
            this.text = text;
            this.nodesScanned = nodesScanned;
            this.textsCollected = textsCollected;
        }
    }

    private ContentScanner() {
    }

    /** Scans {@code root} and recycles every node it obtained, including {@code root}. */
    static Result collect(AccessibilityNodeInfo root, Limits limits) {
        if (root == null) {
            return Result.EMPTY;
        }
        Limits safeLimits = limits == null ? Limits.DEFAULT : limits;
        Set<String> primary = new LinkedHashSet<>();
        Set<String> secondary = new LinkedHashSet<>();
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        int scanned = 0;
        while (!pending.isEmpty() && scanned < safeLimits.maxNodes
                && primary.size() < safeLimits.maxTexts) {
            AccessibilityNodeInfo node = pending.removeFirst();
            scanned++;
            if (node.isVisibleToUser() && !node.isEditable()) {
                addUseful(primary, node.getText());
                addUseful(secondary, node.getContentDescription());
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    pending.addLast(child);
                }
            }
            recycle(node);
        }
        while (!pending.isEmpty()) {
            recycle(pending.removeFirst());
        }
        List<String> ordered = new ArrayList<>(primary);
        for (String item : secondary) {
            if (!primary.contains(item)) {
                ordered.add(item);
            }
        }
        StringBuilder output = new StringBuilder();
        int used = 0;
        for (String item : ordered) {
            if (used >= safeLimits.maxTexts
                    || output.length() >= InteractionSession.MAX_CONTEXT_CHARS) {
                break;
            }
            if (output.length() > 0) {
                output.append('。');
            }
            output.append(item);
            used++;
        }
        return new Result(
                InteractionSession.normalize(output.toString(), InteractionSession.MAX_CONTEXT_CHARS),
                scanned, used);
    }

    private static void addUseful(Set<String> output, CharSequence value) {
        if (value == null) {
            return;
        }
        String clean = InteractionSession.normalize(value.toString(), 80);
        String compact = clean.replaceAll("[\\p{Punct}\\s]", "");
        if (compact.length() < 3 || isControlLabel(compact)) {
            return;
        }
        output.add(clean);
    }

    static boolean isControlLabel(String compact) {
        return compact != null && CONTROL_LABELS.contains(compact.toLowerCase(java.util.Locale.ROOT));
    }

    @SuppressWarnings("deprecation")
    static void recycle(AccessibilityNodeInfo node) {
        // recycle() is a no-op from API 33 but still required on older releases.
        if (node != null) {
            node.recycle();
        }
    }
}
