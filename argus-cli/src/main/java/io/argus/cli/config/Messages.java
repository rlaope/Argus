package io.argus.cli.config;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Provides i18n message lookup backed by a ResourceBundle.
 */
public final class Messages {

    private final ResourceBundle bundle;

    public Messages(String lang) {
        this.bundle = ResourceBundle.getBundle("messages", new Locale(lang));
    }

    /**
     * Returns the message for the given key, or the key itself if not found.
     */
    public String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     * Returns the formatted message for the given key with the provided arguments.
     * Falls back to the key itself if not found.
     */
    public String get(String key, Object... args) {
        return String.format(get(key), args);
    }
}
