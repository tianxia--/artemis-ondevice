package com.artemis.ondevice;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.IBinder;
import android.util.Log;

/**
 * Hands-free control, fully on the phone: wait for the wake word, listen to the task, let the
 * active model fix recognition typos ({@link TaskCorrector}), read it back and wait for "对",
 * run it through {@link AgentService}, then speak the result. Half duplex: the microphone is
 * not listened to while speaking, and is released while a run is in progress.
 *
 * Audio stays in memory; logcat gets state changes only, never what was said.
 */
public class VoiceService extends Service implements Runnable {

    private static final String ACTION_STOP = "com.artemis.ondevice.VOICE_STOP";
    private static final String CHANNEL_ID = "artemis_voice";
    private static final int NOTIFICATION_ID = 18892;
    static final String WAKE_WORD = "小艾小艾";
    /** 100 ms of 16 kHz audio per read. */
    private static final int CHUNK = SpeechEngine.SAMPLE_RATE / 10;
    private static final long MAX_UTTERANCE_MS = 20_000;

    private volatile boolean running;
    private Thread worker;
    private AudioRecord mic;

    static boolean canRun(Context ctx) {
        return AppSettings.voice(ctx)
                && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts listening if the user turned voice on. Call from a visible activity (Android 14 rule). */
    static void startIfEnabled(Context ctx) {
        if (canRun(ctx)) ctx.startForegroundService(new Intent(ctx, VoiceService.class));
    }

    static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, VoiceService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_voice), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification(getString(R.string.voice_waiting, WAKE_WORD)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        if (!canRun(this)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this, "artemis-voice");
            worker.start();
        }
        return START_STICKY;
    }

    private void shutdown() {
        running = false;
        if (worker != null) worker.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ worker

    @Override
    public void run() {
        SpeechEngine engine = null;
        try {
            long t0 = System.currentTimeMillis();
            engine = SpeechEngine.load(getAssets());
            Log.i(AgentService.TAG, "voice models loaded in " + (System.currentTimeMillis() - t0) + " ms");
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SpeechEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, CHUNK * 2 * 4);
            mic.startRecording();
            while (running) {
                if (!engine.wakeWord(read())) continue;
                Log.i(AgentService.TAG, "voice: wake word");
                converse(engine);
                restartMic();
                engine.resetWakeWord();
                status(getString(R.string.voice_waiting, WAKE_WORD));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(AgentService.TAG, "voice stopped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            status(getString(R.string.voice_failed, String.valueOf(e.getMessage())));
        } finally {
            if (mic != null) {
                mic.release();
                mic = null;
            }
            if (engine != null) engine.release();
        }
    }

    /** One exchange after the wake word: task, confirmation, run, result. */
    private void converse(SpeechEngine engine) throws InterruptedException {
        beep();
        status(getString(R.string.voice_listening));
        String heard = listen(engine);
        if (heard.isEmpty()) {
            say(engine, "没听清，请再叫我一次。");
            return;
        }
        status(getString(R.string.voice_correcting));
        String task = TaskCorrector.correct(this, heard);
        // Ask for a two-syllable answer: a lone "对" is often misheard, "确认" / "好的" are not.
        say(engine, "你要我：" + task + "。说确认开始，说取消放弃。");
        status(getString(R.string.voice_confirming));
        String answer = listen(engine);
        if (!isYes(answer)) {
            say(engine, "好的，已取消。");
            return;
        }
        say(engine, "好的，开始执行。");
        String result = runTask(task);
        say(engine, result);
    }

    /** Recognizes one sentence from now on; empty when nothing was said. */
    private String listen(SpeechEngine engine) throws InterruptedException {
        restartMic(); // drop what the microphone picked up while we were speaking
        engine.startUtterance();
        long until = System.currentTimeMillis() + MAX_UTTERANCE_MS;
        while (running && System.currentTimeMillis() < until) {
            if (engine.feedUtterance(read())) break;
        }
        return engine.utteranceText();
    }

    private static boolean isYes(String s) {
        if (s.isEmpty() || s.contains("不") || s.contains("别") || s.contains("取消")) return false;
        return s.matches(".*(对|是|好|确认|可以|行|嗯|没错|开始).*");
    }

    /** Runs the task with the agent and returns the sentence to speak when it ends. */
    private String runTask(String task) throws InterruptedException {
        AgentAccessibilityService a11y = AgentAccessibilityService.get();
        if (a11y == null) return "无障碍服务没有打开，没法操作手机。";
        // The loop holds while our own screens are in front; start from the home screen instead.
        if (getPackageName().equals(a11y.currentPackage())) {
            Gestures.performGlobalAction(a11y, "home");
            Thread.sleep(600);
        }
        mic.stop(); // release the microphone for the apps the agent drives
        status(getString(R.string.voice_running));
        String before = RunState.lastFinishedId();
        AgentService.start(this, task);
        while (running) {
            String last = RunState.lastFinishedId();
            if (last != null && !last.equals(before)) break;
            Thread.sleep(300);
        }
        RunRecord r = RunState.current();
        if (r == null) return "任务结束了。";
        String answer = FlashLoop.clip(r.answer, 200);
        switch (r.status) {
            case RunRecord.COMPLETED: return answer.isEmpty() ? "完成了。" : "完成了。" + answer;
            case RunRecord.STOPPED: return "任务已停止。";
            default: return "没有完成。" + answer;
        }
    }

    // ------------------------------------------------------------------ audio helpers

    private float[] read() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        short[] pcm = new short[CHUNK];
        int n = mic.read(pcm, 0, CHUNK);
        if (n < 0) throw new IllegalStateException("microphone read error " + n);
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = pcm[i] / 32768f;
        return f;
    }

    private void restartMic() {
        if (mic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) mic.stop();
        mic.startRecording();
    }

    private void say(SpeechEngine engine, String text) throws InterruptedException {
        status(getString(R.string.voice_speaking));
        engine.speak(text);
    }

    private static void beep() {
        ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tone.release();
        }
    }

    // ------------------------------------------------------------------ notification

    private void status(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, VoiceService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.voice_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
