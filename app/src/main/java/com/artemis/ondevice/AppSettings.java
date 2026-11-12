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
    private static final String MCP = "mcp";
    private static final String REFLECT = "reflect";
    private static final String CONFIRM = "confirm_sensitive";
    private static final String RUN_TOKENS = "run_token_limit";
    private static final String DAY_TOKENS = "daily_token_limit";
    static final int DEFAULT_MAX_STEPS = 50;
    static final long DEFAULT_RUN_TOKENS = 300_000;
    static final long DEFAULT_DAY_TOKENS = 2_000_000;
    private static final String MCP_LAN = "mcp_lan";

    private AppSettings() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Step cap per run; 0 = none. Model turns only: replayed skill steps do not count. */
    static int maxSteps(Context c) { return p(c).getInt(MAX_STEPS, DEFAULT_MAX_STEPS); }
    static void setMaxSteps(Context c, int v) { p(c).edit().putInt(MAX_STEPS, Math.max(0, v)).apply(); }

    static boolean screenshot(Context c) { return p(c).getBoolean(SCREENSHOT, true); }
    static void setScreenshot(Context c, boolean v) { p(c).edit().putBoolean(SCREENSHOT, v).apply(); }

    static boolean bubble(Context c) { return p(c).getBoolean(BUBBLE, true); }
    static void setBubble(Context c, boolean v) { p(c).edit().putBoolean(BUBBLE, v).apply(); }

    /** Hands-free voice control (VoiceService). Off by default: it keeps the microphone open. */
    static boolean voice(Context c) { return p(c).getBoolean(VOICE, false); }
    static void setVoice(Context c, boolean v) { p(c).edit().putBoolean(VOICE, v).apply(); }

    /** Tokens one run may use before it is stopped; 0 = no limit. */
    static long runTokenLimit(Context c) { return p(c).getLong(RUN_TOKENS, DEFAULT_RUN_TOKENS); }
    static void setRunTokenLimit(Context c, long v) { p(c).edit().putLong(RUN_TOKENS, Math.max(0, v)).apply(); }

    /** Tokens all model calls may use per day; 0 = no limit. */
    static long dailyTokenLimit(Context c) { return p(c).getLong(DAY_TOKENS, DEFAULT_DAY_TOKENS); }
    static void setDailyTokenLimit(Context c, long v) { p(c).edit().putLong(DAY_TOKENS, Math.max(0, v)).apply(); }

    /** Ask before taps that send, call, pay, delete or change settings (Sensitive). On by default. */
    static boolean confirmSensitive(Context c) { return p(c).getBoolean(CONFIRM, true); }
    static void setConfirmSensitive(Context c, boolean v) { p(c).edit().putBoolean(CONFIRM, v).apply(); }

    /** Review each finished run and learn lessons (Reflector): one extra model call per run. */
    static boolean reflect(Context c) { return p(c).getBoolean(REFLECT, true); }
    static void setReflect(Context c, boolean v) { p(c).edit().putBoolean(REFLECT, v).apply(); }

    /** MCP server (McpService). Off by default: it opens a port. */
    static boolean mcp(Context c) { return p(c).getBoolean(MCP, false); }
    static void setMcp(Context c, boolean v) { p(c).edit().putBoolean(MCP, v).apply(); }

    /** Listen on the LAN too, not only on localhost. Off by default: plain HTTP on the network. */
    static boolean mcpLan(Context c) { return p(c).getBoolean(MCP_LAN, false); }
    static void setMcpLan(Context c, boolean v) { p(c).edit().putBoolean(MCP_LAN, v).apply(); }
}
