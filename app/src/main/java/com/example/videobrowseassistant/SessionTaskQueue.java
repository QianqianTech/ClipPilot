package com.example.videobrowseassistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Small, deterministic serializer used for both saved selections and active session queues. */
final class SessionTaskQueue {
    static final int MIN_TASK_QUOTA = 2;
    static final int MAX_TASK_QUOTA = 100;
    private static final String SEPARATOR = ",";

    private SessionTaskQueue() {
    }

    static List<String> parse(String serialized) {
        Set<String> uniquePackages = new LinkedHashSet<>();
        if (serialized != null && !serialized.isEmpty()) {
            for (String item : serialized.split(SEPARATOR)) {
                String packageName = item == null ? "" : item.trim();
                if (!packageName.isEmpty()) {
                    uniquePackages.add(packageName);
                }
            }
        }
        return new ArrayList<>(uniquePackages);
    }

    static String serialize(List<String> packageNames) {
        Set<String> uniquePackages = new LinkedHashSet<>();
        if (packageNames != null) {
            for (String item : packageNames) {
                String packageName = item == null ? "" : item.trim();
                if (!packageName.isEmpty()) {
                    uniquePackages.add(packageName);
                }
            }
        }
        return String.join(SEPARATOR, uniquePackages);
    }

    /**
     * Keeps the user's previously saved selection order, removes unchecked
     * packages, and appends newly checked packages in the UI's stable order.
     */
    static List<String> orderSelected(List<String> savedOrder, List<String> selectedPackages) {
        Set<String> selected = new LinkedHashSet<>();
        if (selectedPackages != null) {
            for (String item : selectedPackages) {
                String packageName = item == null ? "" : item.trim();
                if (!packageName.isEmpty()) {
                    selected.add(packageName);
                }
            }
        }

        List<String> ordered = new ArrayList<>();
        if (savedOrder != null) {
            for (String item : savedOrder) {
                String packageName = item == null ? "" : item.trim();
                if (!packageName.isEmpty() && selected.remove(packageName)) {
                    ordered.add(packageName);
                }
            }
        }
        ordered.addAll(selected);
        return ordered;
    }

    static int clampQuota(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
