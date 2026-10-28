package com.example.videobrowseassistant;

/**
 * Public social-app automation is intentionally denied. This contract is the only path for
 * future, explicitly authorized self-test adapters to request a like or favorite action.
 */
final class EngagementPolicy {
    private EngagementPolicy() {
    }

    enum Action {
        LIKE,
        FAVORITE,
        COMMENT_ASSIST
    }

    static boolean allowsAutomatedAction(String targetPackage, String ownPackage, Action action,
            boolean userEnabled, int actionCount, int maximumCount) {
        if (!userEnabled || action == null || maximumCount <= 0 || actionCount >= maximumCount) {
            return false;
        }
        if (targetPackage == null || ownPackage == null || !targetPackage.equals(ownPackage)) {
            return false;
        }
        return action == Action.LIKE || action == Action.FAVORITE;
    }
}
