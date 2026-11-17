package com.artemis.ondevice;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The phone's state, read directly: time, battery, network, radios, volume, screen, storage,
 * device. Lets the agent and voice calls answer "几点了" or "还有多少电" without opening apps.
 * Read-only, and needs no runtime permission (the Wi-Fi network's name would need location, so
 * it is left out).
 */
final class PhoneStatus {

    private PhoneStatus() {}

    static JSONObject read(Context ctx) {
        JSONObject o = new JSONObject();
        put(o, "time", () -> time());
        put(o, "battery", () -> battery(ctx));
        put(o, "network", () -> network(ctx));
        put(o, "settings", () -> settings(ctx));
        put(o, "sound", () -> sound(ctx));
        put(o, "storage", () -> storage());
        put(o, "device", () -> new JSONObject()
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("language", Locale.getDefault().toLanguageTag()));
        return o;
    }

    /** One line for prompts: compact JSON. */
    static String line(Context ctx) {
        return read(ctx).toString();
    }

    private interface Part {
        Object get() throws Exception;
    }

    /** Each part on its own: one that fails (an odd phone) does not lose the others. */
    private static void put(JSONObject o, String key, Part part) {
        try {
            o.put(key, part.get());
        } catch (Exception e) {
            try {
                o.put(key, "unavailable");
            } catch (Exception ignored) {
                // put of a plain string cannot fail
            }
        }
    }

    private static JSONObject time() throws Exception {
        Date now = new Date();
        return new JSONObject()
                .put("local", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(now))
                .put("weekday", new SimpleDateFormat("EEEE", Locale.ENGLISH).format(now))
                .put("timezone", TimeZone.getDefault().getID());
    }

    private static JSONObject battery(Context ctx) throws Exception {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) throw new IllegalStateException("no battery status");
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        JSONObject o = new JSONObject()
                .put("percent", scale > 0 ? Math.round(100f * level / scale) : level)
                .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                .put("plugged", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC" : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                        : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : "no");
        android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
        if (pm != null) o.put("power_saver", pm.isPowerSaveMode());
        return o;
    }

    private static JSONObject network(Context ctx) throws Exception {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        String type = c == null ? "none"
                : c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile data"
                : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                : c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn" : "other";
        return new JSONObject().put("connected_via", type)
                .put("internet", c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                .put("wifi_name", "not readable without location permission; check Settings if needed");
    }

    private static JSONObject settings(Context ctx) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        return new JSONObject()
                .put("wifi_on", Settings.Global.getInt(cr, Settings.Global.WIFI_ON, 0) != 0)
                .put("bluetooth_on", Settings.Global.getInt(cr, Settings.Global.BLUETOOTH_ON, 0) != 0)
                .put("airplane_mode", Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) != 0)
                .put("screen_brightness_percent", Math.round(100f * Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0) / 255f))
                .put("auto_brightness", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1)
                .put("screen_timeout_seconds", Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 0) / 1000)
                .put("auto_rotate", Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1);
    }

    private static JSONObject sound(Context ctx) throws Exception {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        int ringer = am.getRingerMode();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return new JSONObject()
                .put("ringer", ringer == AudioManager.RINGER_MODE_SILENT ? "silent" : ringer == AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "normal")
                .put("do_not_disturb", nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL)
                .put("media_volume_percent", percent(am, AudioManager.STREAM_MUSIC))
                .put("ring_volume_percent", percent(am, AudioManager.STREAM_RING));
    }

    private static int percent(AudioManager am, int stream) {
        int max = am.getStreamMaxVolume(stream);
        return max > 0 ? Math.round(100f * am.getStreamVolume(stream) / max) : 0;
    }

    private static JSONObject storage() throws Exception {
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        return new JSONObject()
                .put("free_gb", Math.round(s.getAvailableBytes() / 1e8) / 10.0)
                .put("total_gb", Math.round(s.getTotalBytes() / 1e8) / 10.0);
    }
}
