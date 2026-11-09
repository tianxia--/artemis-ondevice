package com.artemis.ondevice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Foreground service that runs the {@link McpServer} while "MCP server" is on in Settings.
 * Localhost only by default (reach it with adb forward); LAN access is a separate opt-in.
 * The bearer token is kept in {@link SecretStore} and never logged.
 */
public class McpService extends Service {

    static final int PORT = 8765;
    private static final String TOKEN_REF = "mcp:token";
    private static final String ACTION_STOP = "com.artemis.ondevice.MCP_STOP";
    private static final String ACTION_RESTART = "com.artemis.ondevice.MCP_RESTART";
    private static final String CHANNEL_ID = "artemis_mcp";
    private static final int NOTIFICATION_ID = 18892;

    /** Why the server is not listening although it is on (e.g. the port is taken), or null. */
    static volatile String lastError;
    private McpServer server;

    static void startIfEnabled(Context ctx) {
        if (AppSettings.mcp(ctx)) ctx.startForegroundService(new Intent(ctx, McpService.class));
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, McpService.class));
    }

    /** Applies a settings change (on/off, LAN, new token): the server restarts, or stops when off. */
    static void apply(Context ctx) {
        if (AppSettings.mcp(ctx)) {
            ctx.startForegroundService(new Intent(ctx, McpService.class).setAction(ACTION_RESTART));
        } else {
            stop(ctx);
        }
    }

    static String token(Context ctx) throws Exception {
        if (!SecretStore.has(ctx, TOKEN_REF)) return newToken(ctx);
        return SecretStore.get(ctx, TOKEN_REF);
    }

    static String newToken(Context ctx) throws Exception {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String t = Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        SecretStore.set(ctx, TOKEN_REF, t);
        return t;
    }

    /**
     * The phone's private IPv4 address, Wi-Fi first, or null. Read from the network interfaces,
     * which needs no permission (ConnectivityManager would need ACCESS_NETWORK_STATE).
     */
    static String lanAddress() {
        String other = null;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (nif.getName().startsWith("wlan")) return a.getHostAddress();
                    if (other == null) other = a.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(AgentService.TAG, "network interfaces not readable: " + e.getClass().getSimpleName());
        }
        return other;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, getString(R.string.channel_mcp), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppSettings.setMcp(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESTART.equals(intent.getAction()) && server != null) {
            server.stop();
            server = null;
        }
        if (server == null) {
            try {
                boolean lan = AppSettings.mcpLan(this);
                InetAddress bind = InetAddress.getByName(lan ? "0.0.0.0" : "127.0.0.1");
                String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                McpServer s = new McpServer(new McpTools(this), version, token(this), bind, PORT);
                s.start();
                server = s;
                lastError = null;
                Log.i(AgentService.TAG, "mcp listening on " + (lan ? "all interfaces" : "localhost") + ":" + PORT);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.w(AgentService.TAG, "mcp not started: " + lastError);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        super.onDestroy();
    }

    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, McpService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        String where = AppSettings.mcpLan(this) ? getString(R.string.mcp_notification_lan, PORT)
                : getString(R.string.mcp_notification_local, PORT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.mcp_notification_title))
                .setContentText(where)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
