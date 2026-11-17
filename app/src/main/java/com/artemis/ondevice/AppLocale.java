package com.artemis.ondevice;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * The language picked in Settings, for contexts AppCompat does not localize: services, the
 * floating bubble, the voice call panel, confirmation cards and notifications. Without this they
 * followed the system language (English text on a Chinese app). Stored by us as well, because
 * before Android 13 AppCompat only restores its choice when an activity starts.
 */
final class AppLocale {

    private static final String PREFS = "artemis_settings", KEY = "app_locale";

    private AppLocale() {}

    /** Records the choice ("" = follow the system) and applies it to the app's screens. */
    static void set(Context ctx, String tags) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tags).apply();
        AppLog.i(AppLog.SETTINGS, "language = " + (tags.isEmpty() ? "system" : tags));
        AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(tags));
    }

    /**
     * Called when the home screen opens (AppCompat has restored its choice by then): copies a
     * language picked by an older version, which only AppCompat stored, so services find it after
     * a restart too.
     */
    static void remember(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains(KEY)) return;
        String tags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        p.edit().putString(KEY, tags == null ? "" : tags).apply();
        AppLog.i(AppLog.SETTINGS, "language carried over: " + (tags == null || tags.isEmpty() ? "system" : tags));
    }

    /** The context in the picked language; the context itself when following the system. */
    static Context wrap(Context base) {
        String tags = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        if (tags == null) tags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        if (tags == null || tags.isEmpty()) return base;
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocales(LocaleList.forLanguageTags(tags));
        return base.createConfigurationContext(conf);
    }
}
