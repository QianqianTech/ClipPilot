package com.example.videobrowseassistant;

import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

/**
 * Non-secret client-side settings for the optional model gateway.
 *
 * <p>The provider credential never belongs here. The Android client stores only a HTTPS gateway
 * origin and a server-defined profile. A device session token is deliberately held in memory by
 * {@link MainActivity}, rather than written to SharedPreferences.</p>
 */
final class ModelGatewayConfig {
    static final String KEY_ENABLED = "model_gateway_enabled";
    static final String KEY_DISCLOSURE_ACCEPTED = "model_gateway_disclosure_accepted";
    static final String KEY_ENDPOINT = "model_gateway_endpoint";
    static final String KEY_PROFILE = "model_gateway_profile";

    static final int MAX_ENDPOINT_CHARS = 180;

    private ModelGatewayConfig() {
    }

    static Config savedConfig(SharedPreferences preferences) {
        if (preferences == null) {
            return Config.disabled();
        }
        String endpoint = normalizeEndpoint(preferences.getString(KEY_ENDPOINT, ""));
        Profile profile = Profile.fromStored(preferences.getString(KEY_PROFILE, ""));
        boolean enabled = preferences.getBoolean(KEY_ENABLED, false)
                && preferences.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
                && !endpoint.isEmpty();
        return new Config(endpoint, profile, enabled);
    }

    static String normalizeEndpoint(String rawEndpoint) {
        String compact = InteractionSession.normalize(rawEndpoint, MAX_ENDPOINT_CHARS);
        if (compact.isEmpty()) {
            return "";
        }
        try {
            URI parsed = new URI(compact);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            int port = parsed.getPort();
            String path = parsed.getRawPath();
            if (scheme == null || !"https".equalsIgnoreCase(scheme)
                    || host == null || host.trim().isEmpty()
                    || parsed.getRawUserInfo() != null
                    || parsed.getRawQuery() != null
                    || parsed.getRawFragment() != null
                    || (path != null && !path.isEmpty() && !"/".equals(path))
                    || port < -1 || port > 65535) {
                return "";
            }
            return new URI("https", null, host.toLowerCase(Locale.ROOT), port,
                    null, null, null).toASCIIString();
        } catch (Exception ignored) {
            return "";
        }
    }

    enum Profile {
        STANDARD("standard", "标准"),
        HIGH_RELEVANCE("high_relevance", "高关联"),
        ECONOMY("economy", "低成本");

        final String storedValue;
        final String label;

        Profile(String storedValue, String label) {
            this.storedValue = storedValue;
            this.label = label;
        }

        static Profile fromStored(String value) {
            for (Profile profile : values()) {
                if (profile.storedValue.equals(value)) {
                    return profile;
                }
            }
            return STANDARD;
        }
    }

    static final class Config {
        final String endpoint;
        final Profile profile;
        final boolean enabled;

        Config(String endpoint, Profile profile, boolean enabled) {
            this.endpoint = normalizeEndpoint(endpoint);
            this.profile = profile == null ? Profile.STANDARD : profile;
            this.enabled = enabled && !this.endpoint.isEmpty();
        }

        static Config disabled() {
            return new Config("", Profile.STANDARD, false);
        }

        boolean hasEndpoint() {
            return !endpoint.isEmpty();
        }

        String generationUrl() {
            return hasEndpoint() ? endpoint + "/v1/comment-candidates" : "";
        }

        String displayHost() {
            try {
                URI uri = new URI(endpoint);
                return uri.getHost() == null ? "" : uri.getHost();
            } catch (Exception ignored) {
                return "";
            }
        }
    }
}
