package com.example.videobrowseassistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The only place that lists supported target apps. The manifest {@code <queries>} block must list
 * the same packages; {@code PlatformTest} fails when the two drift apart.
 *
 * <p>{@link #label} is a stable Chinese name used in local diagnostics; UI text uses
 * {@link #labelRes} / {@link #hintRes} so it can be localized.</p>
 */
enum Platform {
    WECHAT_CHANNELS("com.tencent.mm", "微信视频号", false,
            R.string.platform_wechat_channels, R.string.platform_hint_wechat_channels),
    DOUYIN("com.ss.android.ugc.aweme", "抖音", false,
            R.string.platform_douyin, R.string.platform_hint_short_video),
    KUAISHOU("com.smile.gifmaker", "快手", false,
            R.string.platform_kuaishou, R.string.platform_hint_short_video),
    BILIBILI("tv.danmaku.bili", "B站", false,
            R.string.platform_bilibili, R.string.platform_hint_bilibili),
    KUAISHOU_LITE("com.kuaishou.nebula", "快手极速版", false,
            R.string.platform_kuaishou_lite, R.string.platform_hint_optional),
    TIKTOK("com.zhiliaoapp.musically", "TikTok", true,
            R.string.platform_tiktok, R.string.platform_hint_tiktok),
    YOUTUBE_SHORTS("com.google.android.youtube", "YouTube Shorts", true,
            R.string.platform_youtube_shorts, R.string.platform_hint_youtube_shorts),
    INSTAGRAM_REELS("com.instagram.android", "Instagram Reels", true,
            R.string.platform_instagram_reels, R.string.platform_hint_instagram_reels),
    KWAI("com.kwai.video", "Kwai", true,
            R.string.platform_kwai, R.string.platform_hint_kwai),
    X("com.twitter.android", "X", true,
            R.string.platform_x, R.string.platform_hint_x),
    REDNOTE("com.xingin.xhs", "REDnote", true,
            R.string.platform_rednote, R.string.platform_hint_rednote);

    final String packageName;
    final String label;
    final boolean international;
    final int labelRes;
    final int hintRes;

    Platform(String packageName, String label, boolean international, int labelRes, int hintRes) {
        this.packageName = packageName;
        this.label = label;
        this.international = international;
        this.labelRes = labelRes;
        this.hintRes = hintRes;
    }

    /** Returns the platform for a package name, or {@code null} when it is not supported. */
    static Platform fromPackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        for (Platform platform : values()) {
            if (platform.packageName.equals(packageName)) {
                return platform;
            }
        }
        return null;
    }

    /** Diagnostic label for any package name, including unsupported ones. */
    static String labelFor(String packageName) {
        Platform platform = fromPackage(packageName);
        return platform == null ? "未知平台" : platform.label;
    }

    static List<String> packageNames() {
        List<String> names = new ArrayList<>();
        for (Platform platform : values()) {
            names.add(platform.packageName);
        }
        return Collections.unmodifiableList(names);
    }
}
