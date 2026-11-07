package com.artemis.ondevice;

import android.content.Context;
import android.content.SharedPreferences;

/** User preferences for runs (non-secret). */
final class AppSettings {

    private static final String PREFS = "artemis_settings";
    private static final String MAX_STEPS = "max_steps";
    private static final String SCREENSHOT = "screenshot";
    private static final String BUBBLE = "bubble";
    private static final String VOICE = "voice";

    private AppSettings() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 0 = no step cap (the default). */
    static int maxSteps(Context c) { return p(c).getInt(MAX_STEPS, 0); }
    static void setMaxSteps(Context c, int v) { p(c).edit().putInt(MAX_STEPS, Math.max(0, v)).apply(); }

    static boolean screenshot(Context c) { return p(c).getBoolean(SCREENSHOT, true); }
    static void setScreenshot(Context c, boolean v) { p(c).edit().putBoolean(SCREENSHOT, v).apply(); }

    static boolean bubble(Context c) { return p(c).getBoolean(BUBBLE, true); }
    static void setBubble(Context c, boolean v) { p(c).edit().putBoolean(BUBBLE, v).apply(); }

    /** Hands-free voice control (VoiceService). Off by default: it keeps the microphone open. */
    static boolean voice(Context c) { return p(c).getBoolean(VOICE, false); }
    static void setVoice(Context c, boolean v) { p(c).edit().putBoolean(VOICE, v).apply(); }
}
