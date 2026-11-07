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
    static final String WAKE_WORD = "你好阿特";
    /** 100 ms of 16 kHz audio per read. */
    private static final int CHUNK = SpeechEngine.SAMPLE_RATE / 10;
    private static final long MAX_UTTERANCE_MS = 20_000;
    private static final int MAX_AMENDS = 2;
    private static final String PROMPT = "我在呢，要我做什么？";
    private static final String OK = "好的。";
    private static final String CANCELLED = "好，不做了。";

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
        // Real-time audio: without this, a backgrounded app on some phones (Huawei) gets so little
        // CPU that loading took minutes and recognition falls behind the microphone.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        SpeechEngine engine = null;
        try {
            long t0 = System.currentTimeMillis();
            engine = SpeechEngine.load(getAssets());
            engine.prepare(PROMPT, OK, CANCELLED, getString(R.string.blocked_app));
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

    /**
     * One exchange after the wake word. Safe tasks (open, look, search) start right away; tasks
     * that cannot be undone are read back for confirmation. A reply that is neither yes nor no
     * is taken as a correction or addition and merged into the task, up to {@link #MAX_AMENDS} times.
     */
    private void converse(SpeechEngine engine) throws InterruptedException {
        beep();
        status(getString(R.string.voice_listening));
        String heard = listen(engine);
        Log.i(AgentService.TAG, "voice: heard " + heard.length() + " chars");
        if (heard.isEmpty()) {
            // Woken but nothing followed: ask, like a person would, and listen once more.
            say(engine, PROMPT);
            status(getString(R.string.voice_listening));
            heard = listen(engine);
            Log.i(AgentService.TAG, "voice: heard " + heard.length() + " chars after prompt");
            if (heard.isEmpty()) return; // still silent: go back to waiting without a word
        }
        for (int amends = 0; ; amends++) {
            status(getString(R.string.voice_correcting));
            long tc = System.currentTimeMillis();
            TaskCorrector.Result r = TaskCorrector.correct(this, heard);
            Log.i(AgentService.TAG, "voice: corrected in " + (System.currentTimeMillis() - tc) + " ms, confirm=" + r.confirm);
            if (FlashLoop.namesBlockedApp(heard) || FlashLoop.namesBlockedApp(r.task)) {
                say(engine, getString(R.string.blocked_app));
                return;
            }
            if (!r.confirm) {
                say(engine, OK); // no read-back: it is quick to undo, so just start
                say(engine, runTask(r.task));
                return;
            }
            say(engine, r.task + "，确认吗？");
            status(getString(R.string.voice_confirming));
            String answer = listen(engine);
            Log.i(AgentService.TAG, "voice: answer " + answer.length() + " chars");
            if (isYes(answer)) {
                say(engine, OK);
                say(engine, runTask(r.task));
                return;
            }
            if (answer.isEmpty() || isNo(answer) || amends >= MAX_AMENDS) {
                say(engine, CANCELLED);
                return;
            }
            heard = heard + "，" + answer;
        }
    }

    /** Recognizes one sentence from now on; empty when nothing was said. */
    private String listen(SpeechEngine engine) throws InterruptedException {
        restartMic(); // drop what the microphone picked up while we were speaking
        engine.startUtterance();
        long t0 = System.currentTimeMillis();
        long until = t0 + MAX_UTTERANCE_MS;
        while (running && System.currentTimeMillis() < until) {
            if (engine.feedUtterance(read())) break;
        }
        Log.i(AgentService.TAG, "voice: listened " + (System.currentTimeMillis() - t0) + " ms");
        return engine.utteranceText();
    }

    /** A short yes ("确认", "好的", "是的"); a longer reply may carry a change and is not a plain yes. */
    private static boolean isYes(String s) {
        if (s.isEmpty() || s.length() > 6 || s.contains("不") || s.contains("别") || s.contains("取消")) return false;
        return s.matches(".*(对|是|好|确认|可以|行|嗯|没错|开始).*");
    }

    /** A plain no with nothing else ("不用", "取消", "算了"); "不对，是…" is a correction instead. */
    private static boolean isNo(String s) {
        return s.matches("^(不|不用|不要|不行|别|取消|算了|不做了)[了吧啊呀。，]*$");
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
        long t0 = System.currentTimeMillis();
        engine.speak(text);
        Log.i(AgentService.TAG, "voice: spoke " + text.length() + " chars in " + (System.currentTimeMillis() - t0) + " ms");
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
