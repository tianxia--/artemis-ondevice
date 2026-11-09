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
import android.util.Log;

/**
 * Foreground service that owns one run at a time. It builds the {@link RunRecord}, runs
 * {@link FlashLoop} on a worker thread, publishes progress through {@link RunState} and
 * saves the record to {@link RunStore} at the start and the end.
 */
public class AgentService extends Service implements RunState.Listener {

    static final String TAG = "ArtemisAgent";
    static final String ACTION_START = "com.artemis.ondevice.START";
    static final String ACTION_STOP = "com.artemis.ondevice.STOP";
    static final String EXTRA_TASK = "task";

    private static final String CHANNEL_ID = "artemis_agent_run";
    private static final String DONE_CHANNEL_ID = "artemis_agent_done";
    private static final int NOTIFICATION_ID = 18890;
    private static final int DONE_NOTIFICATION_ID = 18891;
    private static final long START_SETTLE_MS = 600L;

    private volatile boolean stopRequested;
    private volatile ChatClient client;
    private Thread worker;

    static void start(Context ctx, String task) {
        ctx.startForegroundService(new Intent(ctx, AgentService.class).setAction(ACTION_START)
                .putExtra(EXTRA_TASK, task));
    }

    static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, AgentService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, getString(R.string.channel_running),
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(DONE_CHANNEL_ID, getString(R.string.channel_done),
                NotificationManager.IMPORTANCE_DEFAULT));
        RunState.subscribe(this);
    }

    @Override
    public void onDestroy() {
        RunState.unsubscribe(this);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, progressNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            ChatClient c = client;
            if (c != null) c.cancel();
            if (worker != null) worker.interrupt();
            if (worker == null || !worker.isAlive()) finish();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (worker != null && worker.isAlive()) return START_NOT_STICKY;
            begin(intent.getStringExtra(EXTRA_TASK));
        }
        return START_NOT_STICKY;
    }

    private void begin(final String task) {
        stopRequested = false;
        final Context ctx = this;
        ProviderStore.Active active = ProviderStore.getActive(ctx);
        final ProviderStore.Provider provider = active == null ? null : ProviderStore.get(ctx, active.provider);
        final RunRecord run = RunRecord.start(task, provider == null ? "" : provider.id,
                active == null ? "" : active.model, AppSettings.maxSteps(ctx), AppSettings.screenshot(ctx));
        RunState.begin(run);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "artemis:agent");
                lock.acquire(2 * 60 * 60 * 1000L);
                try {
                    save(run);
                    if (provider == null) {
                        run.status = RunRecord.ERROR;
                        run.answer = getString(R.string.error_no_model);
                        run.endedAt = System.currentTimeMillis();
                        return;
                    }
                    String key = ProviderStore.apiKey(ctx, provider.id);
                    // The screen that started the run is still animating away; don't capture it.
                    Thread.sleep(START_SETTLE_MS);
                    client = new ChatClient(provider, key);
                    new FlashLoop(ctx, client, run, new FlashLoop.StopSignal() {
                        @Override public boolean stopped() { return stopRequested; }
                    }, SkillStore.find(ctx, task)).run();
                } catch (InterruptedException e) {
                    run.status = RunRecord.STOPPED;
                    run.answer = getString(R.string.stopped_by_you);
                    run.endedAt = System.currentTimeMillis();
                } catch (Exception e) {
                    run.status = RunRecord.ERROR;
                    run.answer = e.getClass().getSimpleName() + ": " + e.getMessage();
                    run.endedAt = System.currentTimeMillis();
                } finally {
                    client = null;
                    save(run);
                    RunStore.prune(ctx);
                    lock.release();
                    RunState.end(run);
                    notifyDone(run);
                    returnToApp(run);
                    finish();
                }
            }
        }, "artemis-flash");
        worker.start();
    }

    private void save(RunRecord run) {
        try {
            RunStore.save(this, run);
        } catch (Exception e) {
            Log.w(TAG, "run not saved: " + e.getMessage());
        }
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onRunChanged() {
        if (RunState.isRunning()) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, progressNotification());
        }
    }

    private Notification progressNotification() {
        RunRecord r = RunState.current();
        String text = r == null ? getString(R.string.status_starting)
                : getString(R.string.status_progress, r.steps.size() + 1, Format.phase(this, RunState.phase()));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AgentService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(r == null ? getString(R.string.app_name) : FlashLoop.clip(r.task, 60))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentIntent(openRun(r == null ? null : r.id))
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void notifyDone(RunRecord r) {
        Notification n = new Notification.Builder(this, DONE_CHANNEL_ID)
                .setContentTitle(Format.status(this, r.status) + " · " + Format.duration(r.durationMs())
                        + " · " + getString(R.string.n_steps, r.steps.size()))
                .setContentText(r.answer.isEmpty() ? FlashLoop.clip(r.task, 80) : FlashLoop.clip(r.answer, 120))
                .setStyle(new Notification.BigTextStyle().bigText(r.answer.isEmpty() ? r.task : r.answer))
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentIntent(openRun(r.id))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(DONE_NOTIFICATION_ID, n);
    }

    /**
     * When the run ended on its own (not by the user's Stop), bring its summary to the front so
     * the user lands back in the app. Started from the accessibility service's context: a bound
     * accessibility service may start activities from the background, a foreground service may not.
     */
    private void returnToApp(RunRecord r) {
        if (RunRecord.STOPPED.equals(r.status)) return;
        Context from = AgentAccessibilityService.get();
        if (from == null) from = this;
        try {
            from.startActivity(new Intent(from, RunDetailActivity.class)
                    .putExtra(RunDetailActivity.EXTRA_RUN_ID, r.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not bring the run summary to front: " + e.getMessage());
        }
    }

    private PendingIntent openRun(String runId) {
        Intent i = runId == null ? new Intent(this, MainActivity.class)
                : new Intent(this, RunDetailActivity.class).putExtra(RunDetailActivity.EXTRA_RUN_ID, runId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, runId == null ? 0 : runId.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
