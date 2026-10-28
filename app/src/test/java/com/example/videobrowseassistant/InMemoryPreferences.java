package com.example.videobrowseassistant;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SharedPreferences fake with Android's editor semantics: {@code clear()} runs first, then the
 * last put/remove recorded for each key wins.
 */
final class InMemoryPreferences implements SharedPreferences {
    private static final Object REMOVED = new Object();
    private final Map<String, Object> values = new HashMap<>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(String key, String defValue) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object value = values.get(key);
        return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object value = values.get(key);
        return value instanceof Float ? (Float) value : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new MemoryEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    private final class MemoryEditor implements Editor {
        private final Map<String, Object> updates = new LinkedHashMap<>();
        private boolean clear;

        private Editor record(String key, Object value) {
            updates.put(key, value == null ? REMOVED : value);
            return this;
        }

        @Override
        public Editor putString(String key, String value) {
            return record(key, value);
        }

        @Override
        public Editor putStringSet(String key, Set<String> newValues) {
            return record(key, newValues == null ? null : new HashSet<>(newValues));
        }

        @Override
        public Editor putInt(String key, int value) {
            return record(key, value);
        }

        @Override
        public Editor putLong(String key, long value) {
            return record(key, value);
        }

        @Override
        public Editor putFloat(String key, float value) {
            return record(key, value);
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            return record(key, value);
        }

        @Override
        public Editor remove(String key) {
            return record(key, null);
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            if (clear) {
                values.clear();
            }
            for (Map.Entry<String, Object> update : updates.entrySet()) {
                if (update.getValue() == REMOVED) {
                    values.remove(update.getKey());
                } else {
                    values.put(update.getKey(), update.getValue());
                }
            }
        }
    }
}
