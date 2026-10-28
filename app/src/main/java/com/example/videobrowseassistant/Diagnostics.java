package com.example.videobrowseassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Stores a short local-only operational history for device-specific troubleshooting. */
public final class Diagnostics {
    public static final String PREFS = SessionKeys.PREFS;
    public static final String KEY_ENTRIES = "diagnostic_entries";
    private static final String KEY_ENVIRONMENT = "diagnostic_environment";
    private static final int MAX_ENTRIES = 18;

    private Diagnostics() {
    }

    public static synchronized void initialize(Context context) {
        String environment = "设备：" + safe(Build.MANUFACTURER) + " " + safe(Build.MODEL)
                + " · Android " + safe(Build.VERSION.RELEASE)
                + " · 应用 " + installedVersion(context);
        SharedPreferences preferences = preferences(context);
        if (!environment.equals(preferences.getString(KEY_ENVIRONMENT, ""))) {
            preferences.edit().putString(KEY_ENVIRONMENT, environment).apply();
            append(context, "环境", environment);
        }
    }

    public static synchronized void info(Context context, String message) {
        append(context, "信息", message);
    }

    public static synchronized void error(Context context, String message) {
        append(context, "错误", message);
    }

    public static synchronized String recent(Context context, int maxEntries) {
        String stored = preferences(context).getString(KEY_ENTRIES, "");
        if (TextUtils.isEmpty(stored)) {
            // The UI shows a localized placeholder for an empty log.
            return "";
        }
        String[] entries = stored.split("\\n");
        int start = Math.max(0, entries.length - Math.max(1, maxEntries));
        StringBuilder output = new StringBuilder();
        for (int index = start; index < entries.length; index++) {
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(entries[index]);
        }
        return output.toString();
    }

    public static synchronized String all(Context context) {
        return recent(context, MAX_ENTRIES);
    }

    public static synchronized void clear(Context context) {
        preferences(context).edit().remove(KEY_ENTRIES).apply();
    }

    private static void append(Context context, String level, String message) {
        SharedPreferences preferences = preferences(context);
        String stored = preferences.getString(KEY_ENTRIES, "");
        List<String> entries = new ArrayList<>();
        if (!TextUtils.isEmpty(stored)) {
            for (String entry : stored.split("\\n")) {
                if (!TextUtils.isEmpty(entry)) {
                    entries.add(entry);
                }
            }
        }
        String cleanMessage = message == null ? "未知原因" : message.replace('\n', ' ').trim();
        String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        entries.add(timestamp + " [" + level + "] " + cleanMessage);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        preferences.edit().putString(KEY_ENTRIES, TextUtils.join("\n", entries)).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return TextUtils.isEmpty(value) ? "未知" : value;
    }

    private static String installedVersion(Context context) {
        try {
            return safe(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException ignored) {
            return "未知";
        }
    }
}
