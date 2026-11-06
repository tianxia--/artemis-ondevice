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
import android.os.PowerManager;

/** Foreground service that owns one Flash run at a time. */
public class AgentService extends Service {

    static final String TAG = "ArtemisAgent";
    static final String ACTION_START = "com.artemis.ondevice.START";
    static final String ACTION_STOP = "com.artemis.ondevice.STOP";
    static final String EXTRA_TASK = "task";
    static final String EXTRA_MAX_STEPS = "max_steps";
    static final String EXTRA_SCREENSHOT = "screenshot";

    private static final String CHANNEL_ID = "artemis_agent_run";
    private static final int NOTIFICATION_ID = 18890;

    private volatile boolean stopRequested;
    private Thread worker;

    static void start(Context ctx, String task, int maxSteps, boolean screenshot) {
        ctx.startForegroundService(new Intent(ctx, AgentService.class).setAction(ACTION_START)
                .putExtra(EXTRA_TASK, task).putExtra(EXTRA_MAX_STEPS, maxSteps)
                .putExtra(EXTRA_SCREENSHOT, screenshot));
    }

    static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, AgentService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Running task…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            if (worker != null) worker.interrupt();
            if (worker == null || !worker.isAlive()) finish();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (worker != null && worker.isAlive()) {
                RunLog.line("A task is already running; stop it first.");
                return START_NOT_STICKY;
            }
            begin(intent.getStringExtra(EXTRA_TASK), intent.getIntExtra(EXTRA_MAX_STEPS, 20),
                    intent.getBooleanExtra(EXTRA_SCREENSHOT, true));
        }
        return START_NOT_STICKY;
    }

    private void begin(final String task, final int maxSteps, final boolean screenshot) {
        stopRequested = false;
        RunLog.clear();
        RunLog.setRunning(true);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "artemis:agent");
                lock.acquire(30 * 60 * 1000L);
                try {
                    ProviderStore.Active active = ProviderStore.getActive(AgentService.this);
                    ProviderStore.Provider provider = active == null ? null : ProviderStore.get(AgentService.this, active.provider);
                    if (provider == null) {
                        RunLog.line("No active model. Add a provider and pick a model first.");
                        return;
                    }
                    String key = ProviderStore.apiKey(AgentService.this, provider.id);
                    RunLog.line("Task: " + task);
                    RunLog.line("Model: " + provider.id + " / " + active.model + " (" + provider.api + ")"
                            + ", max " + maxSteps + " steps, screenshot " + (screenshot ? "on" : "off"));
                    new FlashLoop(new ChatClient(provider, key), active.model, task, maxSteps, screenshot,
                            new FlashLoop.StopSignal() {
                                @Override public boolean stopped() { return stopRequested; }
                            }).run();
                } catch (Exception e) {
                    RunLog.line("Run failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    lock.release();
                    RunLog.setRunning(false);
                    finish();
                }
            }
        }, "artemis-flash");
        worker.start();
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Artemis agent run",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AgentService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Artemis agent")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
