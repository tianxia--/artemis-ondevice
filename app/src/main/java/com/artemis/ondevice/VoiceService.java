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

/**
 * Hands-free control, fully on the phone: wait for the wake word, listen to the task, let the
 * active model fix recognition typos ({@link TaskCorrector}), read it back and wait for "对",
 * run it through {@link AgentService}, then speak the result. Half duplex: the microphone is
 * not listened to while speaking, and is released while a run is in progress.
 *
 * With a realtime voice model chosen in Settings, the wake word opens a {@link VoiceCall}
 * instead (full duplex, online); when it cannot connect, this offline flow takes over.
 *
 * Audio stays in memory; logcat gets state changes only, never what was said.
 */
public class VoiceService extends Service implements Runnable {

    /** Notifications, overlays and spoken texts in the language picked in Settings. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(AppLocale.wrap(base));
    }

    private static final String ACTION_STOP = "com.artemis.ondevice.VOICE_STOP";
    private static final String CHANNEL_ID = "artemis_voice";
    private static final int NOTIFICATION_ID = 18892;
    /** 100 ms of 16 kHz audio per read. */
    private static final int CHUNK = SpeechEngine.SAMPLE_RATE / 10;
    private static final long MAX_UTTERANCE_MS = 20_000;
    private static final int MAX_AMENDS = 2;
    private static final String PROMPT = "我在呢，要我做什么？";
    private static final String OK = "好的。";
    private static final String CANCELLED = "好，不做了。";
    private static final String CALL_FAILED = "实时语音连不上，先用离线语音。";

    private volatile boolean running;
    // Second wake path (recognizerWake): a short burst of speech is recognized and compared by sound.
    private final java.util.ArrayDeque<float[]> wakePreRoll = new java.util.ArrayDeque<>();
    private boolean wakeSpeech, wakeGaveUp, wakeMatched;
    private int shownAttempts = -1;
    private int wakeChunks;
    private static final int WAKE_MAX_CHUNKS = 40; // 4 s: the wake word is short; longer talk is a conversation

    // Self-check while waiting for the wake word (see selfCheck): loudest sample since the last check,
    // reads since then, whether the microphone looked unusable, wake-ups since the last heartbeat.
    private int peak, reads, wakes;
    private boolean micBroken;
    private long lastHeartbeat;
    private static final int CHECK_READS = 300;            // 30 s
    private static final long HEARTBEAT_MS = 10 * 60_000;
    /** Set by {@link VoiceTestReceiver}: start a voice call as if the wake word was heard. */
    static volatile boolean callRequested;
    /** Wake test (Settings): wanted on or off, and the last finished test's summary. */
    static volatile boolean testWanted;
    static volatile String lastTestSummary;
    static volatile WakeTest lastTest;
    private WakeTest test;
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
            AppLog.i(AppLog.VOICE, "service stop requested");
            shutdown();
            return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_voice), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification(getString(R.string.voice_loading)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        if (!canRun(this)) {
            AppLog.w(AppLog.VOICE, "service not started: voice wake-up " + (AppSettings.voice(this) ? "on" : "off")
                    + ", microphone permission " + (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED ? "granted" : "missing"));
            shutdown();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            AppLog.i(AppLog.VOICE, "service start" + (intent == null ? " (restarted by the system)" : ""));
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
        AppLog.i(AppLog.VOICE, "service destroyed");
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
            AppLog.i(AppLog.VOICE, "wake word model loaded in " + (System.currentTimeMillis() - t0) + " ms");
            final SpeechEngine e0 = engine;
            final String blocked = getString(R.string.blocked_app);
            Thread loader = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                long t1 = System.currentTimeMillis();
                e0.loadRest(getAssets(), PROMPT, OK, CANCELLED, CALL_FAILED, blocked);
                if (e0.isReady()) AppLog.i(AppLog.VOICE, "other voice models loaded in " + (System.currentTimeMillis() - t1) + " ms");
                else AppLog.w(AppLog.VOICE, "voice models failed to load");
            }, "artemis-voice-load");
            loader.start();
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SpeechEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, CHUNK * 2 * 4);
            mic.startRecording();
            applyWakeWord(engine);
            status(getString(R.string.voice_waiting, engine.wakeWord())); // listening from here on
            while (running) {
                applyWakeWord(engine);
                boolean requested = callRequested;
                if (!requested) {
                    float[] samples = read();
                    long now = System.currentTimeMillis();
                    updateTest(engine);
                    boolean spotted = engine.wakeWord(samples);
                    if (test != null && spotted) test.spotted(now);
                    boolean recognized = !spotted || test != null
                            ? engine.isReady() && recognizerWake(engine, samples) : false;
                    if (++reads >= CHECK_READS) selfCheck(engine);
                    if (test != null) {
                        // Testing: count, beep, no call.
                        test.tick(now);
                        if (spotted || recognized) beep();
                        continue;
                    }
                    if (spotted) AppLog.i(AppLog.VOICE, "wake word heard by the spotter");
                    else if (recognized) AppLog.i(AppLog.VOICE, "wake word heard by the recognizer (the spotter missed it)");
                    else continue;
                }
                callRequested = false;
                wakes++;
                wakeSpeech = false; // a call or offline exchange follows: the burst in progress is over
                if (requested) AppLog.i(AppLog.VOICE, "call requested over adb");
                if (!call(engine)) converse(engine);
                restartMic();
                engine.resetWakeWord();
                status(getString(R.string.voice_waiting, engine.wakeWord()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            AppLog.w(AppLog.VOICE, "voice stopped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            status(getString(R.string.voice_failed, String.valueOf(e.getMessage())));
        } finally {
            if (mic != null) {
                mic.release();
                mic = null;
            }
            if (engine != null) engine.release();
        }
    }

    /** Woken while recognition and synthesis are still loading (right after a start): wait for them. */
    private void awaitModels(SpeechEngine engine) throws InterruptedException {
        if (engine.isReady()) return;
        AppLog.i(AppLog.VOICE, "woken before the other voice models were ready; waiting");
        status(getString(R.string.voice_loading));
        long t0 = System.currentTimeMillis();
        try {
            engine.awaitReady();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("voice models failed to load: " + e.getMessage(), e);
        }
        AppLog.i(AppLog.VOICE, "waited " + (System.currentTimeMillis() - t0) + " ms for the voice models");
    }

    /** Picks up a wake word changed in Settings (checked there; a bad one keeps the old). */
    private void applyWakeWord(SpeechEngine engine) {
        String phrase = AppSettings.wakeWord(this);
        boolean high = AppSettings.WAKE_HIGH.equals(AppSettings.wakeSensitivity(this));
        if (engine.wakeConfigured() && phrase.equals(engine.wakeWord()) && high == engine.wakeHigh()) return;
        try {
            engine.setWakeWord(phrase, WakeWord.keywordLine(this, phrase), high);
            AppLog.i(AppLog.VOICE, "wake word set (" + phrase.length() + " chars), sensitivity " + (high ? "high" : "normal"));
            status(getString(R.string.voice_waiting, phrase));
        } catch (IllegalArgumentException e) {
            AppLog.w(AppLog.VOICE, "wake word not usable, keeping the old one");
            AppSettings.setWakeWord(this, engine.wakeWord());
        }
    }

    /**
     * A realtime voice call, when a realtime voice model is chosen. Returns false when there is
     * none or it could not connect, so the offline flow handles this wake-up.
     */
    private boolean call(SpeechEngine engine) throws InterruptedException {
        ProviderStore.Active voice = ProviderStore.getVoice(this);
        if (voice == null) return false;
        beep();
        awaitModels(engine);
        mic.stop(); // the call opens its own microphone, with echo cancellation
        try {
            new VoiceCall(this, engine, voice, this::status).run();
            return true;
        } catch (VoiceCall.Unavailable e) {
            AppLog.w(AppLog.VOICE, "voice call unavailable: " + e.getMessage());
            restartMic();
            say(engine, CALL_FAILED);
            return false;
        }
    }

    /**
     * One exchange after the wake word. Safe tasks (open, look, search) start right away; tasks
     * that cannot be undone are read back for confirmation. A reply that is neither yes nor no
     * is taken as a correction or addition and merged into the task, up to {@link #MAX_AMENDS} times.
     */
    private void converse(SpeechEngine engine) throws InterruptedException {
        beep();
        awaitModels(engine);
        status(getString(R.string.voice_listening));
        String heard = listen(engine);
        AppLog.i(AppLog.VOICE, "heard " + heard.length() + " chars");
        if (heard.isEmpty()) {
            // Woken but nothing followed: ask, like a person would, and listen once more.
            say(engine, PROMPT);
            status(getString(R.string.voice_listening));
            heard = listen(engine);
            AppLog.i(AppLog.VOICE, "heard " + heard.length() + " chars after prompt");
            if (heard.isEmpty()) return; // still silent: go back to waiting without a word
        }
        for (int amends = 0; ; amends++) {
            status(getString(R.string.voice_correcting));
            long tc = System.currentTimeMillis();
            TaskCorrector.Result r = TaskCorrector.correct(this, heard);
            AppLog.i(AppLog.VOICE, "corrected in " + (System.currentTimeMillis() - tc) + " ms, confirm=" + r.confirm);
            if (MoneyGuard.isBlockedTask(this, heard) || MoneyGuard.isBlockedTask(this, r.task)) {
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
            AppLog.i(AppLog.VOICE, "answer " + answer.length() + " chars");
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
        AppLog.i(AppLog.VOICE, "listened " + (System.currentTimeMillis() - t0) + " ms");
        return engine.utteranceText();
    }

    /** A short yes ("确认", "好的", "是的"); a longer reply may carry a change and is not a plain yes. */
    static boolean isYes(String s) {
        if (s.isEmpty() || s.length() > 6 || s.contains("不") || s.contains("别") || s.contains("取消")) return false;
        return s.matches(".*(对|是|好|确认|可以|行|嗯|没错|开始).*");
    }

    /** A plain no with nothing else ("不用", "取消", "算了"); "不对，是…" is a correction instead. */
    static boolean isNo(String s) {
        return s.matches("^(不|不用|不要|不行|别|取消|算了|不做了)[了吧啊呀。，]*$");
    }

    /** Runs the task with the agent and returns the sentence to speak when it ends. */
    private static final long START_WAIT_MS = 10_000;

    /** Run {@code id} has begun (it is the current run), or a run already finished since {@code before}. */
    private static boolean startedOrDone(String id, String before) {
        RunRecord r = RunState.current();
        String last = RunState.lastFinishedId();
        return (r != null && id.equals(r.id)) || (last != null && !last.equals(before));
    }

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
        String id = AgentService.start(this, task, false); // the result is spoken: stay where the task ended
        // The start can be lost (seen once: no run began, and this waited for its end for good, deaf
        // to the wake word): give up when it has not begun in time.
        long until = System.currentTimeMillis() + START_WAIT_MS;
        while (running && !startedOrDone(id, before)) {
            if (System.currentTimeMillis() > until) {
                AppLog.w(AppLog.VOICE, "the task did not start within " + START_WAIT_MS + " ms");
                return "任务没有开始，请再说一次。";
            }
            Thread.sleep(200);
        }
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
        for (int i = 0; i < n; i++) {
            f[i] = pcm[i] / 32768f;
            int a = Math.abs(pcm[i]);
            if (a > peak) peak = a;
        }
        return f;
    }

    /**
     * The small wake word spotter misses the wake word often; the speech recognizer (already loaded)
     * is far more accurate. While someone speaks, up to 4 s are recognized and compared with the
     * wake word by sound ({@link WakeWord#soundsLike}). What was said is not logged.
     */
    private boolean recognizerWake(SpeechEngine engine, float[] samples) {
        boolean speech = engine.speaking(samples);
        String phrase = engine.wakeWord();
        long now = System.currentTimeMillis();
        if (speech) {
            if (!wakeSpeech) {
                wakeSpeech = true;
                wakeGaveUp = false;
                wakeMatched = false;
                wakeChunks = 0;
                engine.startUtterance();
                for (float[] p : wakePreRoll) engine.feedUtterance(p);
                wakePreRoll.clear();
                if (test != null) test.speechStarted(now);
            }
            if (wakeGaveUp || wakeMatched) return false;
            engine.feedUtterance(samples);
            if (++wakeChunks >= WAKE_MAX_CHUNKS) wakeGaveUp = true;
            if (wakeChunks % 5 == 0 && WakeWord.soundsLike(this, engine.utteranceText(), phrase)) {
                wakeMatched = true;
                if (test == null) {
                    wakeSpeech = false;
                    return true;
                }
                return true; // testing: counted when the speech ends
            }
            return false;
        }
        wakePreRoll.add(samples);
        // 0.6 s before the speech detector fires: it reacts 0.2-0.3 s late, and 0.3 s of pre-roll
        // left tries recognized as 0-1 syllables (the first one cut off).
        if (wakePreRoll.size() > 6) wakePreRoll.poll();
        if (!wakeSpeech) return false;
        wakeSpeech = false;
        if (wakeMatched) {
            if (test != null) test.speechEnded(now, true, true);
            return false; // already reported when it matched
        }
        boolean matched = false, close = false;
        if (!wakeGaveUp) {
            engine.feedUtterance(new float[SpeechEngine.SAMPLE_RATE * 3 / 10]); // flush the last syllable
            String heard = engine.utteranceText();
            matched = WakeWord.soundsLike(this, heard, phrase);
            if (!matched) {
                // A short burst that was not the wake word: how close it came, never what was said.
                close = WakeWord.bestMatch(this, heard, phrase) >= WakeWord.toneless(this, phrase).size() - 1;
                AppLog.i(AppLog.VOICE, "speech " + wakeChunks * 100 + " ms, not the wake word: " + WakeWord.closeness(this, heard, phrase));
            }
        }
        if (test != null) test.speechEnded(now, matched, close);
        return matched;
    }

    /** Starts or stops the wake test when Settings asks to. */
    private void updateTest(SpeechEngine engine) {
        if (testWanted && test == null) {
            test = new WakeTest();
            AppLog.i(AppLog.VOICE, "wake test started");
            status(getString(R.string.wake_test_running, 0, 0));
        } else if (!testWanted && test != null) {
            test.finish();
            lastTestSummary = test.summary();
            lastTest = test;
            AppLog.i(AppLog.VOICE, "wake test result: " + lastTestSummary);
            test = null;
            status(getString(R.string.voice_waiting, engine.wakeWord()));
            return;
        }
        if (test != null && test.attempts != shownAttempts) {
            shownAttempts = test.attempts;
            status(getString(R.string.wake_test_running, test.attempts, test.woken()));
        }
    }

    /**
     * Every 30 s while waiting for the wake word: is the microphone really delivering sound? Android
     * silences an app's recording when another app takes the microphone (a call, WeChat voice) or
     * when the service was restarted from the background, and the stream is then all zeros, with no
     * error. Says so in the notification and the log instead of failing silently; logs a heartbeat
     * every 10 minutes.
     */
    private void selfCheck(SpeechEngine engine) {
        boolean silenced = false;
        try {
            for (android.media.AudioRecordingConfiguration c : getSystemService(AudioManager.class)
                    .getActiveRecordingConfigurations()) {
                if (c.getClientAudioSessionId() == mic.getAudioSessionId() && c.isClientSilenced()) silenced = true;
            }
        } catch (RuntimeException e) {
            AppLog.w(AppLog.VOICE, "recording state not readable: " + e.getMessage());
        }
        boolean broken = silenced || peak == 0;
        if (broken != micBroken) {
            micBroken = broken;
            if (broken) {
                AppLog.w(AppLog.VOICE, "microphone unusable: " + (silenced ? "silenced by the system (another app is "
                        + "recording, or the service was started from the background)" : "only silence for 30 s"));
                status(getString(R.string.voice_mic_paused));
                restartMic(); // a fresh recording sometimes gets the microphone back
            } else {
                AppLog.i(AppLog.VOICE, "microphone works again");
                status(getString(R.string.voice_waiting, engine.wakeWord()));
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat > HEARTBEAT_MS) {
            AppLog.i(AppLog.VOICE, "listening: " + wakes + " wake-ups " + (lastHeartbeat == 0 ? "since start"
                    : "in " + (now - lastHeartbeat) / 60_000 + " min") + ", loudest sample " + peak + "/32768, microphone "
                    + (broken ? "unusable" : "ok") + ", sensitivity " + AppSettings.wakeSensitivity(this));
            lastHeartbeat = now;
            wakes = 0;
        }
        peak = 0;
        reads = 0;
    }

    private void restartMic() {
        if (mic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) mic.stop();
        mic.startRecording();
    }

    private void say(SpeechEngine engine, String text) throws InterruptedException {
        status(getString(R.string.voice_speaking));
        long t0 = System.currentTimeMillis();
        engine.speak(text);
        AppLog.i(AppLog.VOICE, "spoke " + text.length() + " chars in " + (System.currentTimeMillis() - t0) + " ms");
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
