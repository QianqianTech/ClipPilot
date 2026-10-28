package com.example.videobrowseassistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;

/** Builds and publishes the single, low-importance session notification. */
final class SessionNotifications {
    static final String CHANNEL_ID = "video_browse_session";
    static final int NOTIFICATION_ID = 1001;

    private final Context context;
    private String lastSignature = "";

    SessionNotifications(Context context) {
        this.context = context;
    }

    void createChannel() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        manager.createNotificationChannel(channel);
    }

    /** Publishes {@code message}; identical consecutive states are skipped to avoid churn. */
    void publish(SharedPreferences preferences, String message) {
        boolean running = SessionStore.isRunning(preferences);
        boolean commentAssistEnabled = InteractionSession.isCommentAssistEnabled(preferences);
        boolean commentPaused = InteractionSession.isPaused(preferences);
        boolean hasDraft = InteractionSession.hasFreshDraft(preferences, System.currentTimeMillis());
        String signature = message + "|" + running + "|" + commentAssistEnabled + "|"
                + commentPaused + "|" + hasDraft;
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(openAppIntent())
                .setOngoing(running)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (running) {
            if (commentAssistEnabled && !commentPaused) {
                builder.addAction(action(R.string.notification_action_pause_capture,
                        SessionCommandReceiver.ACTION_PAUSE_AND_CAPTURE, NOTIFICATION_ID + 1));
            }
            if (commentAssistEnabled && commentPaused && hasDraft) {
                builder.addAction(action(R.string.notification_action_fill_draft,
                        SessionCommandReceiver.ACTION_FILL_COMMENT_DRAFT, NOTIFICATION_ID + 2));
            }
            if (commentAssistEnabled && commentPaused) {
                builder.addAction(action(R.string.notification_action_resume,
                        SessionCommandReceiver.ACTION_RESUME_BROWSING, NOTIFICATION_ID + 3));
            }
            builder.addAction(action(R.string.notification_action_stop,
                    SessionCommandReceiver.ACTION_STOP, NOTIFICATION_ID));
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }

    /** Forces the next {@link #publish} to post even if the text did not change. */
    void invalidate() {
        lastSignature = "";
    }

    static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, NOTIFICATION_ID + 10, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Action action(int labelRes, String action, int requestCode) {
        Intent intent = new Intent(context, SessionCommandReceiver.class).setAction(action);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_stat_session),
                context.getString(labelRes),
                pendingIntent).build();
    }
}
