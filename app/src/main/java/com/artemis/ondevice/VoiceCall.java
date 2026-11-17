package com.artemis.ondevice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Base64;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * One voice call with a realtime voice model (Qwen-Audio realtime protocol over WebSocket),
 * started after the wake word. What the user says is recognized on the phone (offline ASR, cut
 * into turns by the local VAD) and sent as text; the model answers in streamed speech. The model
 * talks with the user and hands phone work to the agent through tools; the agent's confirmations
 * and questions are read out, and what the user says is matched to an option here, never decided
 * by the model.
 *
 * Why text up, voice down: sending live audio needed a steady ~45 KB/s upload that mobile data
 * often lacks, and the server decided turns 3-6 s late. Text needs almost no bandwidth, turns end
 * when the local VAD hears {@code MIN_SILENCE} of silence, and it costs far less. The call hangs
 * up after {@link #IDLE_MS} without speech, after {@link #MAX_TALK_MS} of talking, or at the daily
 * token limit. Logs hold state and sizes only, never what was said.
 */
final class VoiceCall implements RealtimeSocket.Listener {

    /** The call could not start (no key, refused, unreachable, limit reached); fall back to offline voice. */
    static final class Unavailable extends Exception {
        Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    private static final int IN_RATE = 16_000, OUT_RATE = 24_000;
    private static final int CHUNK = IN_RATE / 10; // 100 ms
    /** Recognizer input kept from before the VAD fired, so the first syllable is not cut. */
    private static final int PRE_ROLL_CHUNKS = 3;
    /** A pause this long ends the user's turn. */
    private static final float MIN_SILENCE = 0.6f;
    /*
     * Background voices, a TV or noise keep the VAD reporting speech, so a turn would never end.
     * The user is close to the microphone: a turn also ends when the level stays well below the
     * user's own level in this turn, when no new words are recognized for a while, or at a cap.
     */
    private static final double QUIET_SHARE = 0.25;
    private static final int QUIET_CHUNKS = 9;          // 0.9 s: voices trail off at the end of a sentence
    /** A task waits this long after the model starts it, so a sentence the user goes on with can still join it. */
    private static final long TASK_GRACE_MS = 1_500;
    /** After this long a running task is no longer amended by restarting it; additions queue instead. */
    private static final long AMEND_WINDOW_MS = 20_000;
    private static final long STALL_MS = 1_200;
    private static final long MAX_UTTERANCE_MS = 15_000;
    private static final long IDLE_MS = 15_000;
    private static final long MAX_TALK_MS = 5 * 60_000;
    private static final long GREET_AFTER_MS = 1_200;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final String NOTICE = "[系统通知] ";

    private static final String INSTRUCTIONS =
            "你是手机助手「小特」，在用户的手机上通过语音和用户对话。说话简短、口语化，一般一两句话；"
                    + "用户用什么语言就用什么语言回答。\n"
                    + "0. 先用最快的办法：常识、计算、翻译直接回答；时间、日期、电量、网络、Wi-Fi/蓝牙/飞行模式开关、音量、勿扰、"
                    + "亮度、屏幕超时、存储、手机型号这类问题，用用户每句话后面附带的「手机状态」直接回答；天气、新闻等实时的公开信息用联网搜索"
                    + "（如果可用）。这些都不要调用 run_phone_task。只有要在应用里做事、信息只在应用里能看到，"
                    + "或者用户明确说要打开或使用某个应用（比如「打开时钟看看几点」），才调用 run_phone_task。\n"
                    + "1. 用户要在手机上做事（打开应用、查看信息、改设置、发消息等）时，只说「好的」或「收到」两三个字，"
                    + "不要复述用户的话，然后马上调用 run_phone_task，task 写成一句清楚完整的指令。"
                    + "只是聊天或问常识时直接回答，不调用工具。\n"
                    + "用户一句话里说了几件事时，只调用一次 run_phone_task，把每一件都按用户说的顺序写进同一个 task，"
                    + "一件都不能漏（比如「先打开设置看电量，再看现在连的是哪个 Wi-Fi」）；手机会依次做完，后面的事能用上前面的结果。"
                    + "正在做任务时用户又要做别的事，就再调用 run_phone_task，新任务会排队。\n"
                    + "任务刚开始后用户补充或更正（比如「对了，再看一下 Wi-Fi」「不对，是蓝牙」），调用 update_task，写出合并后的完整任务。\n"
                    + "2. 用户要停下正在做的事时调用 stop_task；问做到哪了调用 task_status。\n"
                    + "3. 以「" + NOTICE.trim() + "」开头的消息是系统发来的，不是用户说的：任务结果要用一两句话转告用户；"
                    + "需要用户确认或选择时，用一句话问用户。\n"
                    + "4. 你不能替用户确认或选择，也不能说「已确认」「已允许」：用户的回答由系统识别，"
                    + "系统通知里说已选择才算数；没听清就再问一次。\n"
                    + "5. 转账、付款、购买、银行、支付、钱包类的事一律不做，直接告诉用户需要自己操作。\n"
                    + "6. 用户的话是手机上语音识别出的文字，常有同音字、近音字、漏字或断句错误（比如「高的地图」是高德地图）："
                    + "按发音和上下文理解用户真正想说的，写进 task 时用改正后的说法；实在听不懂才用一句话问用户。";

    private final Context ctx;
    private final SpeechEngine engine;
    private final ProviderStore.Provider provider;
    private final String model;
    private final Consumer<String> status;
    private final ExecutorService tools = Executors.newSingleThreadExecutor();
    /** Corrects the caption of what the user said; null without a task model. */
    private CaptionFixer captions;
    private final LinkedBlockingQueue<byte[]> playback = new LinkedBlockingQueue<>();
    /** Everything for the server goes through one sender thread, so a slow network never stalls the microphone. */
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private Thread sender;

    private volatile RealtimeSocket socket;
    /** What opening a connection needs, kept for reconnects when the session setup stalls. */
    private String connectUrl, sessionJson;
    private Map<String, String> connectHeaders;
    private static final int MAX_RECONNECTS = 2;
    private AudioManager audio;
    private int previousMode;
    private AudioRecord mic;
    private AudioTrack speaker;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Vad vad;
    private Thread player;
    private boolean fullDuplex;

    private volatile boolean ended;
    private volatile Exception closeError;
    private volatile boolean dropAudio;
    private volatile long playingUntil;
    private volatile long lastActivity;
    private volatile boolean heardSpeech;
    private volatile String taskBefore; // RunState.lastFinishedId() when our task started; null = none running
    private UserPrompt.Pending announced;
    /** Loudest RMS of the sentence that just ended, for the log (to calibrate a floor for distant voices). */
    private double turnPeak;
    /** A task the model asked for, not started yet (see TASK_GRACE_MS); with the time it was asked. */
    private volatile String pendingTask;
    /** Between leaving pendingTask and taskBefore being set: busy too, or the idle hang-up killed the start. */
    private volatile boolean taskStarting;
    private volatile long pendingAt, taskStartedAt;
    /** The user said goodbye: hang up once the goodbye has been spoken. */
    private volatile boolean hangUpAfterReply;
    private volatile boolean speakingNow;
    /**
     * The user's voiceprint (null: everyone is heard), and the current sentence's audio: for
     * checking it (the first VOICEPRINT_SECONDS) and for recognizing it again once it is finished.
     */
    private float[] voiceprint;
    private static final int VOICEPRINT_SECONDS = 6, SENTENCE_SECONDS = 20;
    private final java.util.ArrayList<float[]> utterance = new java.util.ArrayList<>();
    private int utteranceSamples;
    /** The last voiceprint check of this sentence: how much audio it had and what it decided. */
    private int checkedSamples;
    private boolean checkedUser = true;
    /** The current utterance already cut off the reply. */
    private boolean cutIn;
    /** The user stopped the task by voice and was already told; its "stopped" result needs no second word. */
    private volatile boolean stoppedByVoice;
    /** Tasks the user asked for while another one was running, in order. */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private VoiceCallPanel panel;
    private volatile VoiceCallPanel.State shown;
    private final StringBuilder reply = new StringBuilder();
    private volatile int turns;
    private float receivedSeconds;
    /** Turn timing for the log: when the user stopped (local VAD). */
    private volatile long youStoppedAt;
    private volatile boolean firstAudio;
    private volatile boolean responseActive;
    /** response.created seen for the answer in progress (an answer only requested cannot be cancelled yet). */
    private volatile boolean responseStarted;
    /** The user talked over an answer that had not started yet: cancel it as soon as it starts. */
    private volatile boolean cancelOnStart;
    /** Audio of a cancelled answer that still arrived (reader thread only), for the log. */
    private float droppedSeconds;
    private final java.util.concurrent.CountDownLatch sessionReady = new java.util.concurrent.CountDownLatch(1);
    /** No session confirmation by then: reconnect (seen: a stalled setup confirmed after 80 s, on 4G). */
    private static final long SESSION_WAIT_MS = 6_000;
    /** An answer was asked for while another was still in progress; sent once that one is done. */
    private boolean responseWanted;
    /** Last sign of life of the answer in progress, so a lost one cannot block all later answers. */
    private volatile long responseSeenAt;
    private final Object responseLock = new Object();
    /** The call in progress, for {@link VoiceTestReceiver}; null when there is none. */
    static volatile VoiceCall current;
    /** Sentences injected over adb as if the user had said them (tests without a microphone). */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> injected = new java.util.concurrent.ConcurrentLinkedQueue<>();


    /** {@code engine} is the caller's offline speech engine; only the caller's thread may use it. */
    VoiceCall(Context ctx, SpeechEngine engine, ProviderStore.Active voice, Consumer<String> status) throws Unavailable {
        this.ctx = ctx;
        this.engine = engine;
        this.status = status;
        this.provider = ProviderStore.get(ctx, voice.provider);
        this.model = voice.model;
        if (provider == null || provider.model(model) == null) throw new Unavailable("voice model not found", null);
        if (provider.realtimeUrl.isEmpty()) throw new Unavailable("no realtime voice URL", null);
    }

    /** Runs the call on the caller's thread until it ends. Throws Unavailable if it never started. */
    void run() throws Unavailable, InterruptedException {
        long limit = AppSettings.dailyTokenLimit(ctx);
        if (limit > 0 && UsageStore.todayTokens(ctx) >= limit) throw new Unavailable("daily token limit reached", null);
        String key;
        try {
            key = ProviderStore.apiKey(ctx, provider.id);
        } catch (Exception e) {
            throw new Unavailable("API key not readable", e);
        }
        if (key == null || key.isEmpty()) throw new Unavailable("no API key", null);
        status.accept(ctx.getString(R.string.voice_call_connecting));
        panel = VoiceCallPanel.show(() -> {
            AppLog.i(AppLog.CALL, "hung up by the user");
            ended = true;
        });
        show(VoiceCallPanel.State.CONNECTING);
        long t0 = System.currentTimeMillis();
        try {
            // Connect while the audio devices open (they took ~0.7 s on a phone), not after.
            final Unavailable[] failed = new Unavailable[1];
            Thread connecting = new Thread(() -> {
                try {
                    connect(key);
                } catch (Unavailable e) {
                    failed[0] = e;
                }
            }, "artemis-voice-connect");
            connecting.start();
            try {
                openAudio(); // speech right after the wake word is kept while connecting
            } finally {
                connecting.join();
            }
            if (failed[0] != null) throw failed[0];
        } catch (Unavailable e) {
            closeAudio();
            if (socket != null) socket.close();
            if (panel != null) panel.close();
            throw e;
        }
        AppLog.i(AppLog.CALL, "connected in " + (System.currentTimeMillis() - t0) + " ms, "
                + (fullDuplex ? "full duplex (echo canceller on)" : "half duplex (no echo canceller)"));
        current = this;
        captions = CaptionFixer.create(ctx);
        try {
            talk();
        } finally {
            current = null;
            socket.close();
            if (sender != null) sender.interrupt();
            tools.shutdownNow();
            if (captions != null) captions.close();
            closeAudio();
            if (panel != null) panel.close();
            AppLog.i(AppLog.CALL, String.format(Locale.ROOT, "ended, %d turns, received %.1f s of speech%s",
                    turns, receivedSeconds, closeError == null ? "" : ", " + closeError.getMessage()));
        }
    }

    // ------------------------------------------------------------------ connection

    /** Connects, waits for the session to open and hangs up; returns the time it took. No audio, no cost. */
    static long probe(ProviderStore.Provider p, String model, String key) throws Exception {
        if (key == null || key.isEmpty()) throw new IOException("no API key");
        long t0 = System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + key);
        headers.put("User-Agent", "artemis-ondevice");
        RealtimeSocket s = RealtimeSocket.connect(p.realtimeUrl + (p.realtimeUrl.contains("?") ? "&" : "?")
                + "model=" + model, headers, CONNECT_TIMEOUT_MS);
        final java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
        final String[] problem = new String[1];
        s.start(new RealtimeSocket.Listener() {
            @Override public void onMessage(String text) {
                try {
                    JSONObject e = new JSONObject(text);
                    if ("error".equals(e.optString("type"))) {
                        JSONObject err = e.optJSONObject("error");
                        problem[0] = err == null ? text : err.optString("message", text);
                    }
                } catch (Exception ignored) {
                    // Not JSON: not ours to judge.
                }
                opened.countDown();
            }
            @Override public void onClosed(Exception error) {
                if (problem[0] == null) problem[0] = error == null ? "closed by the server" : error.getMessage();
                opened.countDown();
            }
        });
        try {
            if (!opened.await(CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IOException("no answer from the server");
            }
            if (problem[0] != null) throw new IOException(problem[0]);
            return System.currentTimeMillis() - t0;
        } finally {
            s.close();
        }
    }

    private void connect(String key) throws Unavailable {
        connectUrl = provider.realtimeUrl + (provider.realtimeUrl.contains("?") ? "&" : "?") + "model=" + model;
        connectHeaders = new HashMap<>();
        connectHeaders.put("Authorization", "Bearer " + key);
        connectHeaders.put("User-Agent", "artemis-ondevice");
        try {
            sessionJson = new JSONObject().put("type", "session.update").put("session", new JSONObject()
                    .put("modalities", new JSONArray().put("audio").put("text"))
                    .put("enable_search", AppSettings.webSearch(ctx))
                    .put("instructions", INSTRUCTIONS)
                    .put("output_audio_format", "pcm")
                    .put("turn_detection", JSONObject.NULL) // turns are decided on the phone
                    .put("tools", toolDefinitions())).toString();
            open();
        } catch (IOException e) {
            // The class says what went wrong (UnknownHost = no network, Handshake = refused, …).
            throw new Unavailable(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new Unavailable(e.getMessage(), e);
        }
        sender = new Thread(this::sendLoop, "artemis-voice-send");
        sender.start();
    }

    /**
     * Opens a connection and sends the session setup on it; it becomes the call's socket. Events
     * and the close of a socket that was replaced are ignored.
     */
    private void open() throws IOException {
        final RealtimeSocket s = RealtimeSocket.connect(connectUrl, connectHeaders, CONNECT_TIMEOUT_MS);
        socket = s;
        s.start(new RealtimeSocket.Listener() {
            @Override public void onMessage(String text) {
                if (socket == s) VoiceCall.this.onMessage(text);
            }
            @Override public void onClosed(Exception error) {
                if (socket == s) VoiceCall.this.onClosed(error);
            }
        });
        // Sent before anything else: the sender waits until the server confirms it.
        s.send(sessionJson);
    }

    private static JSONArray toolDefinitions() throws Exception {
        return new JSONArray()
                .put(tool("run_phone_task", "让手机上的智能体执行任务：打开应用、查看信息、修改设置、发消息等",
                        new JSONObject().put("task", new JSONObject().put("type", "string")
                                .put("description", "用户这次要做的全部事情，按顺序写清楚，用户说到的每一件都要包含")), "task"))
                .put(tool("update_task", "用户补充或更正了刚开始执行的任务时调用：停止当前任务，按合并后的完整任务重新开始",
                        new JSONObject().put("task", new JSONObject().put("type", "string")
                                .put("description", "合并了用户补充或更正后的完整任务")), "task"))
                .put(tool("stop_task", "停止手机上正在执行的任务", new JSONObject(), null))
                .put(tool("task_status", "查询手机上正在执行的任务做到哪一步了", new JSONObject(), null));
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String required) throws Exception {
        JSONObject params = new JSONObject().put("type", "object").put("properties", properties);
        if (required != null) params.put("required", new JSONArray().put(required));
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", name).put("description", description).put("parameters", params));
    }

    private void send(JSONObject event) {
        outbox.add(event.toString());
    }

    /**
     * Sends queued events in order, but only once the session is set up: a sentence that arrives
     * first was answered without the instructions and tools (seen on slow networks). Gives up
     * waiting after SESSION_WAIT_MS and sends anyway.
     */
    private void sendLoop() {
        try {
            for (int attempt = 1; !sessionReady.await(SESSION_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS); attempt++) {
                if (ended) return;
                if (attempt > MAX_RECONNECTS) {
                    AppLog.w(AppLog.CALL, "session setup still not confirmed after " + MAX_RECONNECTS + " reconnects; sending anyway");
                    break;
                }
                AppLog.w(AppLog.CALL, "session setup not confirmed after " + SESSION_WAIT_MS + " ms; reconnecting (" + attempt + ")");
                RealtimeSocket old = socket;
                long t0 = System.currentTimeMillis();
                try {
                    open();
                    AppLog.i(AppLog.CALL, "reconnected in " + (System.currentTimeMillis() - t0) + " ms");
                } catch (IOException e) {
                    AppLog.w(AppLog.CALL, "reconnect failed: " + e.getClass().getSimpleName());
                }
                if (old != socket) old.close(); // replaced: its close is ignored
            }
            while (!ended) socket.send(outbox.take());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!ended) AppLog.w(AppLog.CALL, "send failed: " + e.getMessage());
        }
    }

    /** A system notice the model should act on (task result, a question on screen); optionally answered aloud. */
    private void notice(String text, boolean respond) {
        message(NOTICE + text, respond);
    }

    /** Adds a user-side text message to the conversation and, if asked, requests the spoken answer. */
    private void message(String text, boolean respond) {
        try {
            send(new JSONObject().put("type", "conversation.item.create").put("item", new JSONObject()
                    .put("type", "message").put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "input_text").put("text", text)))));
            if (respond) requestResponse();
        } catch (Exception e) {
            AppLog.w(AppLog.CALL, "message not sent: " + e.getMessage());
        }
    }

    private void show(VoiceCallPanel.State state) {
        if (panel == null || shown == state) return;
        shown = state;
        panel.state(state);
    }

    // ------------------------------------------------------------------ main loop (caller thread)

    private void talk() throws InterruptedException {
        voiceprint = Voiceprint.load(ctx);
        status.accept(ctx.getString(R.string.voice_call_talking));
        show(VoiceCallPanel.State.LISTENING);
        long start = System.currentTimeMillis();
        lastActivity = start;
        boolean greeted = false;
        long talkMs = 0, last = start;
        boolean speaking = false;
        // Per turn: the user's peak level, chunks well below it, when the words last changed.
        double peak = 0, lastPeak = 0;
        int quiet = 0;
        String words = "";
        long wordsAt = 0, turnStart = 0;
        boolean waitQuiet = false; // a turn was ended over background sound: wait for quiet or a voice like the user's
        java.util.ArrayDeque<float[]> preRoll = new java.util.ArrayDeque<>();
        short[] pcm = new short[CHUNK];
        while (!ended) {
            if (Thread.interrupted()) throw new InterruptedException();
            int n = mic.read(pcm, 0, CHUNK);
            if (n <= 0) throw new IllegalStateException("microphone read error " + n);
            long now = System.currentTimeMillis();
            if (taskBefore == null) talkMs += now - last;
            last = now;

            float[] samples = toFloats(pcm, n);
            // Without an echo canceller our own voice from the speaker would be heard: ignore it then.
            if (!fullDuplex && now < playingUntil) {
                vad.reset();
                preRoll.clear();
                speaking = false;
            } else {
                vad.acceptWaveform(samples);
                while (!vad.empty()) vad.pop();
                double level = level(pcm, n);
                boolean voice = vad.isSpeechDetected();
                if (waitQuiet) {
                    if (!voice || level >= 0.6 * lastPeak) waitQuiet = false;
                    else voice = false; // still the background that ended the last turn
                }
                String why = null;
                if (voice && speaking) {
                    peak = Math.max(peak, level);
                    quiet = level < QUIET_SHARE * peak ? quiet + 1 : 0;
                    String now_words = engine.utteranceText();
                    if (!now_words.equals(words)) {
                        words = now_words;
                        wordsAt = now;
                    }
                    if (quiet >= QUIET_CHUNKS) why = "quieter than you";
                    else if (!words.isEmpty() && now - wordsAt > STALL_MS) why = "no new words";
                    else if (now - turnStart > MAX_UTTERANCE_MS) why = "too long";
                }
                if (why != null) {
                    AppLog.i(AppLog.CALL, "turn ended over background sound (" + why + ")");
                    speaking = false;
                    waitQuiet = true;
                    lastPeak = peak;
                    turnPeak = peak;
                    endTurn(now);
                } else if (voice) {
                    heardSpeech = true;
                    lastActivity = now;
                    if (panel != null) panel.level(rms(pcm, n));
                    if (!speaking) {
                        speaking = true;
                        peak = level;
                        quiet = 0;
                        words = "";
                        wordsAt = now;
                        turnStart = now;
                        cutIn = false;
                        show(VoiceCallPanel.State.HEARING);
                        AppLog.i(AppLog.CALL, "you started");
                        engine.startUtterance();
                        utterance.clear();
                        utteranceSamples = 0;
                        checkedSamples = 0;
                        checkedUser = true;
                        for (float[] p : preRoll) {
                            engine.feedUtterance(p);
                            keep(p);
                        }
                        preRoll.clear();
                    }
                    engine.feedUtterance(samples);
                    keep(samples);
                    // Talking over the reply cuts it off, but only once words are recognized: a cough,
                    // a door or the TV must not cut off a task result.
                    if (!cutIn && (now < playingUntil || responseActive) && !engine.utteranceText().isEmpty()
                            && isUser(false)) {
                        cutIn = true;
                        AppLog.i(AppLog.CALL, "words over its reply: cancelling it");
                        interruptReply();
                    }
                } else if (speaking) {
                    speaking = false;
                    lastPeak = peak;
                    turnPeak = peak;
                    engine.feedUtterance(samples);
                    endTurn(now);
                } else {
                    preRoll.add(samples);
                    if (preRoll.size() > PRE_ROLL_CHUNKS) preRoll.poll();
                }
            }

            String typed = injected.poll();
            if (typed != null) {
                heardSpeech = true;
                lastActivity = now;
                boolean over = now < playingUntil || responseActive;
                AppLog.i(AppLog.CALL, "injected sentence" + (over ? " over its reply: cancelling it" : ""));
                if (over) interruptReply();
                youStoppedAt = now;
                handleTurn(typed);
            }

            if (!greeted && now - start > GREET_AFTER_MS) {
                greeted = true;
                // Woken without a task: ask, like a person would.
                if (!heardSpeech) notice("用户刚喊了你的名字，还没说要做什么。用一句话简短地问用户要做什么。", true);
            }
            watchPrompt();
            watchTask();
            updatePanel(now);
            if (responseActive && now - responseSeenAt > 30_000) {
                AppLog.w(AppLog.CALL, "answer in progress went silent, releasing it");
                responseEnded();
            }
            speakingNow = speaking;
            String pending = pendingTask;
            // Our own voice from the loudspeaker ("好的") is not the user going on: it must not hold the start.
            boolean userTalking = speaking && now >= playingUntil;
            if (pending != null && !userTalking && now - pendingAt > TASK_GRACE_MS) {
                taskStarting = true;
                pendingTask = null;
                lastActivity = now;
                tools.execute(() -> {
                    try {
                        String refused = startTask(pending);
                        if (refused != null) notice("任务没有开始：" + refused + "。告诉用户。", true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        taskStarting = false;
                    }
                });
            }
            if (hangUpAfterReply && !responseActive && now > playingUntil + 400) {
                AppLog.i(AppLog.CALL, "the user said goodbye");
                break;
            }
            // Waiting for an answer is not idle (a web search can take a while); the watchdog below bounds it.
            boolean busy = taskBefore != null || pendingTask != null || taskStarting || !queued.isEmpty()
                    || UserPrompt.pending() != null || responseActive;
            if (!busy && now - Math.max(lastActivity, playingUntil) > IDLE_MS) break;
            if (talkMs > MAX_TALK_MS && now > playingUntil) break;
        }
    }

    /** Back to listening once the reply has played; hidden while the agent works, unless it asks something. */
    private void updatePanel(long now) {
        if (panel == null) return;
        boolean task = taskBefore != null;
        panel.setVisible(!task || UserPrompt.pending() != null);
        if (task && UserPrompt.pending() == null) show(VoiceCallPanel.State.TASK);
        else if (shown == VoiceCallPanel.State.SPEAKING && now > playingUntil + 300) show(VoiceCallPanel.State.LISTENING);
        else if (shown == VoiceCallPanel.State.TASK) show(VoiceCallPanel.State.LISTENING);
    }

    /** The user finished a sentence: recognize it here and send the text for an answer. */
    private void endTurn(long now) {
        engine.feedUtterance(new float[IN_RATE * 3 / 10]); // trailing silence flushes the recognizer
        String streamed = engine.utteranceText();
        youStoppedAt = now;
        AppLog.i(AppLog.CALL, "you stopped, " + streamed.length() + " chars recognized in "
                + (System.currentTimeMillis() - now) + " ms, loudest " + Math.round(turnPeak));
        // Nothing recognized while it streamed: a noise, not worth the bigger model (which may make up words).
        if (streamed.isEmpty()) {
            show(VoiceCallPanel.State.LISTENING);
            return;
        }
        String said = recognizeSentence(streamed);
        // One recognized character is mostly noise or a word caught from someone nearby ("嗯", "啊").
        // Kept only where one word means something: stopping, or answering the card on screen.
        String bare = said.replaceAll("[\\p{P}\\s]+", "");
        if (bare.length() < 2 && UserPrompt.pending() == null && !bare.equals("停")) {
            AppLog.i(AppLog.CALL, "ignored a 1-character turn (noise or someone nearby)");
            show(VoiceCallPanel.State.LISTENING);
            return;
        }
        if (!isUser(true)) {
            // Say so instead of ignoring the user silently when the voiceprint got them wrong.
            if (panel != null) panel.userSaid(ctx.getString(R.string.voice_call_not_you));
            show(VoiceCallPanel.State.LISTENING);
            return;
        }
        handleTurn(said);
    }

    private void keep(float[] samples) {
        // Bounded memory for long background talk; a turn ends after MAX_UTTERANCE_MS anyway.
        if (utteranceSamples >= SENTENCE_SECONDS * IN_RATE) return;
        utterance.add(samples);
        utteranceSamples += samples.length;
    }

    /** The first {@code max} samples of the sentence's audio as one array. */
    private float[] sentenceAudio(int max) {
        float[] all = new float[Math.min(max, utteranceSamples)];
        int at = 0;
        for (float[] c : utterance) {
            int n = Math.min(c.length, all.length - at);
            if (n <= 0) break;
            System.arraycopy(c, 0, all, at, n);
            at += n;
        }
        return all;
    }

    /**
     * The finished sentence recognized again by the bigger model (the streaming one makes many
     * mistakes); the streaming text when that model is missing or hears nothing.
     */
    private String recognizeSentence(String streamed) {
        if (!engine.hasSentenceRecognizer() || utteranceSamples == 0) return streamed;
        long t0 = System.currentTimeMillis();
        // Trailing silence, like the streaming recognizer gets: the last word is not cut off.
        float[] audio = sentenceAudio(Integer.MAX_VALUE);
        float[] padded = java.util.Arrays.copyOf(audio, audio.length + IN_RATE * 3 / 10);
        String text = engine.recognizeSentence(padded);
        // Never the words, only how much they differ.
        AppLog.i(AppLog.CALL, String.format(Locale.ROOT, "sentence recognizer: %.1f s of audio in %d ms, %d chars (streaming had %d%s)",
                audio.length / (float) IN_RATE, System.currentTimeMillis() - t0, text == null ? 0 : text.length(),
                streamed.length(), text != null && text.equals(streamed) ? ", same" : ", differs"));
        return text == null || text.isEmpty() ? streamed : text;
    }

    /**
     * Whether the sentence so far is the user's, by the voiceprint. True without a voiceprint and
     * for speech too short to tell (a quick "好" or "停"). {@code final_} logs the decision.
     */
    private boolean isUser(boolean final_) {
        // The first seconds are enough to tell, and keep each check quick.
        int samples = Math.min(utteranceSamples, VOICEPRINT_SECONDS * IN_RATE);
        if (voiceprint == null || samples < Voiceprint.MIN_SECONDS * IN_RATE) return true;
        // While someone else talks, do not re-check every 100 ms chunk (~100 ms each): only after
        // another second of speech, or at the end of the sentence.
        if (!final_ && checkedSamples > 0 && samples < checkedSamples + IN_RATE) return checkedUser;
        float[] all = sentenceAudio(samples);
        long t0 = System.currentTimeMillis();
        float score = engine.voiceScore(voiceprint, all);
        boolean user = Float.isNaN(score) || score >= Voiceprint.THRESHOLD;
        boolean changed = checkedSamples == 0 || user != checkedUser;
        checkedSamples = samples;
        checkedUser = user;
        if (final_ || changed) {
            AppLog.i(AppLog.CALL, String.format(Locale.ROOT, "voiceprint %.2f (%s) in %d ms",
                    score, user ? "you" : "someone else, ignored", System.currentTimeMillis() - t0));
        }
        return user;
    }

    /** Handles one sentence of the user's: stop words, the card on screen, else the model's answer. */
    private void handleTurn(String said) {
        turns++;
        if (panel != null) {
            panel.userSaid(said);
            if (captions != null) {
                final int turn = turns;
                String lastReply;
                synchronized (reply) {
                    lastReply = reply.toString();
                }
                // Only while this is still the latest sentence: a late fix must not overwrite the next one.
                captions.fix(said, lastReply, fixed -> {
                    if (turns == turn && panel != null) panel.correctUserSaid(fixed);
                });
            }
        }
        if (stopNow(said)) return;
        if (goodbye(said)) return;
        String picked = answerPrompt(said);
        String held = pendingTask;
        if (held != null && picked == null) {
            // The user went on talking before the task started: let the model fold it in.
            pendingTask = null;
            AppLog.i(AppLog.CALL, "the user added to a task before it started");
            message(said + "\n" + NOTICE + "你刚才准备执行的任务「" + FlashLoop.clip(held, 80) + "」还没有开始，用户又说了上面这句。"
                    + "如果是补充或更正，把它合并进去，重新调用一次 run_phone_task（只调用一次，任务写完整）；"
                    + "如果和任务无关，就照常回答，并重新调用 run_phone_task 执行原来的任务。", true);
            show(VoiceCallPanel.State.THINKING);
            return;
        }
        boolean fresh = taskBefore != null && System.currentTimeMillis() - taskStartedAt < AMEND_WINDOW_MS;
        // The phone's state rides along with every sentence: "几点了" and "还有多少电" need no tool round trip
        // (and with web search on, the service answered tool results only after the next sentence).
        message(said + "\n" + NOTICE + "手机状态（直接读取，不用打开应用）：" + PhoneStatus.line(ctx)
                + (picked == null ? "" : "\n" + NOTICE + picked)
                + (fresh && picked == null ? "\n" + NOTICE + "手机上刚开始执行任务「" + FlashLoop.clip(currentTaskText(), 80)
                        + "」。如果用户这句是在补充或更正它，调用 update_task 写出合并后的完整任务；如果是另一件事，才调用 run_phone_task。" : ""),
                true);
        show(VoiceCallPanel.State.THINKING);
    }

    /** "结束对话", "挂断", "再见": a short goodbye, then the call hangs up (a running task goes on). */
    private boolean goodbye(String said) {
        String s = said.trim().replaceAll("[。，！？,.!?\\s]+", "");
        if (!s.matches(".*(结束对话|结束通话|结束吧|挂断|挂了吧|挂了|再见|拜拜|没事了|就这样吧|就这样|不用了谢谢).*")) return false;
        AppLog.i(AppLog.CALL, "goodbye heard");
        hangUpAfterReply = true;
        interruptReply();
        boolean working = taskBefore != null || pendingTask != null || !queued.isEmpty();
        message(said + "\n" + NOTICE + "用户要结束通话。只用一句很短的话道别" + (working ? "，并说任务会继续做，结果在通知里" : "")
                + "，不要再问问题。", true);
        return true;
    }

    private static String currentTaskText() {
        RunRecord r = RunState.current();
        return r == null ? "" : r.task;
    }

    /**
     * "停下" and the like stop the task here, at once, without waiting for the model to decide: the
     * user must always be able to stop the agent. Returns true when it handled the sentence.
     */
    private boolean stopNow(String said) {
        String s = said.trim().replaceAll("[。，！？,.!?\\s]+", "");
        boolean stopWords = s.matches(".*(停下|停止|停一下|别做了|不要做了|不做了|取消|算了).*") || s.matches("^(停|停停)+了?$");
        if (!stopWords || (!RunState.isRunning() && queued.isEmpty() && taskBefore == null && pendingTask == null)) return false;
        int dropped = queued.size() + (pendingTask == null ? 0 : 1);
        queued.clear();
        pendingTask = null;
        if (RunState.isRunning()) {
            stoppedByVoice = true;
            AgentService.stop(ctx);
        }
        AppLog.i(AppLog.CALL, "stopped by voice" + (dropped > 0 ? ", " + dropped + " queued dropped" : ""));
        interruptReply();
        message(said + "\n" + NOTICE + "系统已按用户的话停止了正在做的任务" + (dropped > 0 ? "，并取消了排队的 " + dropped + " 个任务" : "")
                + "。用一句话告诉用户已经停了，问还要做什么。", true);
        show(VoiceCallPanel.State.THINKING);
        return true;
    }

    /**
     * Asks the model to answer now, or right after the answer in progress ends: the server refuses
     * a new one while another runs ("Cannot create response while another response is in
     * progress"), which left tool results unanswered, the call silent and later tasks never called.
     */
    private void requestResponse() {
        synchronized (responseLock) {
            if (responseActive) {
                responseWanted = true;
                return;
            }
            responseActive = true; // until response.created confirms it; blocks a second request meanwhile
            responseSeenAt = System.currentTimeMillis();
        }
        AppLog.i(AppLog.CALL, "answer requested");
        send(responseCreate());
    }

    /** response.done: the next answer may start; send the one that waited, if any. */
    private void responseEnded() {
        boolean next;
        synchronized (responseLock) {
            next = responseWanted;
            responseWanted = false;
            responseActive = next;
            responseSeenAt = System.currentTimeMillis();
        }
        if (next) {
            AppLog.i(AppLog.CALL, "answer requested (queued)");
            send(responseCreate());
        }
    }

    private static JSONObject responseCreate() {
        try {
            return new JSONObject().put("type", "response.create");
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For {@link VoiceTestReceiver}: the next turn is this sentence, as if the user had said it. */
    void inject(String text) {
        injected.add(text);
    }

    void hangUp() {
        AppLog.i(AppLog.CALL, "hung up over adb");
        ended = true;
    }

    /** The user talks over the reply: stop playing it at once and cancel the rest. */
    private void interruptReply() {
        dropAudio = true;
        playback.clear();
        if (speaker != null) {
            speaker.pause();
            speaker.flush();
            speaker.play();
        }
        playingUntil = 0;
        if (responseActive) {
            if (responseStarted) send(cancel());
            else if (outbox.remove(responseCreate().toString())) {
                // Not even sent yet (the session was still being set up): withdraw it. Sending it and
                // cancelling it on start once kept the next answer waiting 50 s: the server ignored
                // the cancel and finished the old answer first.
                AppLog.i(AppLog.CALL, "answer withdrawn before it was sent");
                responseEnded();
            } else {
                cancelOnStart = true; // requested but not started: there is nothing to cancel yet
            }
        }
    }

    private static JSONObject cancel() {
        try {
            return new JSONObject().put("type", "response.cancel");
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads out a confirmation or question that appeared on screen. */
    private void watchPrompt() {
        UserPrompt.Pending p = UserPrompt.pending();
        if (p == announced) return;
        announced = p;
        if (p == null) return;
        lastActivity = System.currentTimeMillis();
        if (p.confirm) {
            notice("手机上的操作需要用户确认：" + p.message + "。用一句话问用户是否允许，不要替用户决定。", true);
        } else {
            notice("手机上的任务在问用户：" + p.message + "。选项：" + String.join("、", p.options.subList(0, p.options.size() - 1))
                    + "（也可以说停止任务）。用一句话问用户选哪个。", true);
        }
    }

    /** Tells the model how the task it started ended, so it can tell the user. */
    private void watchTask() {
        String before = taskBefore;
        if (before == null) return;
        String last = RunState.lastFinishedId();
        if (last == null || last.equals(before)) return;
        taskBefore = null;
        lastActivity = System.currentTimeMillis();
        status.accept(ctx.getString(R.string.voice_call_talking));
        RunRecord r = RunState.current();
        String outcome = r == null ? "结束了" : RunRecord.COMPLETED.equals(r.status) ? "完成了"
                : RunRecord.STOPPED.equals(r.status) ? "被停止了" : "没有完成";
        if (stoppedByVoice) {
            stoppedByVoice = false; // already said; the queue was cleared with it
            return;
        }
        AppLog.i(AppLog.CALL, "task result handed to the model");
        String answer = r == null ? "" : FlashLoop.clip(r.answer, 300);
        String task = r == null ? "" : "「" + FlashLoop.clip(r.task, 60) + "」";
        final String next = queued.poll();
        notice("手机任务" + task + outcome + "。" + (answer.isEmpty() ? "" : "结果：" + answer + "。")
                + (next == null ? "" : "接下来开始做排队的任务「" + FlashLoop.clip(next, 60) + "」。")
                + "用一两句话告诉用户。", true);
        if (next != null) {
            tools.execute(() -> {
                try {
                    // Let the finished run settle (back to the app, notification) before the next one.
                    Thread.sleep(1_500);
                    String refused = startTask(next);
                    if (refused != null) notice("排队的任务「" + FlashLoop.clip(next, 60) + "」没有开始：" + refused + "。告诉用户。", true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    // ------------------------------------------------------------------ server events (reader thread)

    @Override
    public void onMessage(String text) {
        JSONObject e;
        try {
            e = new JSONObject(text);
        } catch (Exception ex) {
            return;
        }
        String type = e.optString("type");
        if (type.startsWith("response.")) responseSeenAt = System.currentTimeMillis();
        if (!type.endsWith(".delta")) {
            // Event types only (no content), to see how the server takes turns.
            String extra = "";
            if (type.startsWith("session.")) {
                JSONObject session = e.optJSONObject("session");
                extra = session == null ? "" : " turn_detection=" + session.opt("turn_detection");
            } else if (type.equals("error")) {
                extra = " " + FlashLoop.clip(text, 300);
            }
            AppLog.i(AppLog.CALL, "event " + type + extra);
        }
        switch (type) {
            case "session.updated":
                sessionReady.countDown();
                break;
            case "response.created":
                synchronized (responseLock) {
                    responseActive = true;
                }
                responseStarted = true;
                if (cancelOnStart) {
                    // The user already moved on: this answer is to an older sentence.
                    cancelOnStart = false;
                    dropAudio = true;
                    send(cancel());
                    break;
                }
                dropAudio = false;
                firstAudio = true;
                AppLog.i(AppLog.CALL, "response created " + since(youStoppedAt) + " after you stopped");
                synchronized (reply) {
                    reply.setLength(0);
                }
                break;
            case "response.audio_transcript.delta":
                if (panel == null || dropAudio) break;
                synchronized (reply) {
                    reply.append(e.optString("delta"));
                    panel.assistantSays(reply.toString());
                }
                break;
            case "response.audio.delta":
                if (dropAudio) {
                    droppedSeconds += e.optString("delta").length() * 3 / 4 / (2f * OUT_RATE); // base64 -> PCM bytes
                    break;
                }
                if (firstAudio) {
                    firstAudio = false;
                    AppLog.i(AppLog.CALL, "first audio " + since(youStoppedAt) + " after you stopped");
                }
                byte[] pcm = Base64.decode(e.optString("delta"), Base64.DEFAULT);
                float seconds = pcm.length / (2f * OUT_RATE);
                receivedSeconds += seconds;
                long now = System.currentTimeMillis();
                playingUntil = Math.max(playingUntil, now) + (long) (seconds * 1000);
                playback.add(pcm);
                if (taskBefore == null) show(VoiceCallPanel.State.SPEAKING);
                break;
            case "response.function_call_arguments.done":
                final String name = e.optString("name"), callId = e.optString("call_id"), args = e.optString("arguments");
                tools.execute(() -> callTool(name, callId, args));
                break;
            case "response.done":
                responseStarted = false;
                responseEnded();
                JSONObject done = e.optJSONObject("response");
                // A cancelled answer the server still finished: how much of it was thrown away.
                AppLog.i(AppLog.CALL, "response " + (done == null ? "?" : done.optString("status"))
                        + (droppedSeconds > 0 ? String.format(Locale.ROOT, ", %.1f s of its audio dropped", droppedSeconds) : ""));
                droppedSeconds = 0;
                recordUsage(done);
                break;
            case "error":
                JSONObject err = e.optJSONObject("error");
                AppLog.w(AppLog.CALL, "server error " + (err == null ? text : err.optString("code") + " "
                        + FlashLoop.clip(err.optString("message"), 200)));
                break;
            default:
                break;
        }
    }

    @Override
    public void onClosed(Exception error) {
        closeError = error;
        ended = true;
    }

    /**
     * Picks the option the user said on the card on screen. Returns the notice for the model, or
     * null when there is no card or the answer was unclear (the model then asks again).
     */
    private String answerPrompt(String said) {
        UserPrompt.Pending p = UserPrompt.pending();
        if (p == null) return null;
        String s = said.trim().replaceAll("[。，！？,.!?\\s]+$", "");
        int choice = choice(p, s);
        AppLog.i(AppLog.CALL, "answer to the card " + (choice < 0 ? "unclear" : "-> option " + choice));
        if (choice < 0) return null;
        p.answer(choice);
        return "系统已按用户的回答选择了「" + p.options.get(choice) + "」。";
    }

    static int choice(UserPrompt.Pending p, String s) {
        if (s.isEmpty()) return -1;
        if (p.confirm) {
            if (s.matches(".*(这次都|全都|全部|都)(允许|可以).*")) return 1;
            if (VoiceService.isNo(s)) return 2;
            if (VoiceService.isYes(s) || s.matches(".*(允许|同意).*") && !s.contains("不")) return 0;
            return -1;
        }
        int stop = p.options.size() - 1; // the last option stops the task
        if (s.matches(".*(停止|停下|不做了|取消任务|算了).*")) return stop;
        String[] ordinals = {"第一", "第二", "第三", "第四"};
        for (int i = 0; i < Math.min(stop, ordinals.length); i++) if (s.contains(ordinals[i])) return i;
        int best = -1, bestLength = 0;
        String lower = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < stop; i++) {
            String o = p.options.get(i).toLowerCase(Locale.ROOT);
            if ((lower.contains(o) || (lower.length() >= 2 && o.contains(lower))) && o.length() > bestLength) {
                best = i;
                bestLength = o.length();
            }
        }
        return best;
    }

    private void callTool(String name, String callId, String arguments) {
        JSONObject out = new JSONObject();
        try {
            JSONObject args = arguments == null || arguments.isEmpty() ? new JSONObject() : new JSONObject(arguments);
            AppLog.i(AppLog.CALL, "tool " + name);
            switch (name) {
                case "run_phone_task":
                    runTask(args.optString("task").trim(), out);
                    break;
                case "update_task":
                    updateTask(args.optString("task").trim(), out);
                    break;
                case "stop_task":
                    if (!queued.isEmpty()) out.put("queued_cancelled", queued.size());
                    queued.clear();
                    if (RunState.isRunning()) {
                        AgentService.stop(ctx);
                        out.put("status", "stopping");
                    } else {
                        out.put("status", "no_task");
                    }
                    break;
                case "task_status":
                    RunRecord r = RunState.current();
                    boolean running = r != null && r.isRunning();
                    out.put("running", running).put("queued", new JSONArray(queued));
                    if (r != null) out.put("task", r.task).put("steps_done", r.steps.size());
                    break;
                default:
                    out.put("error", "unknown tool " + name);
            }
            send(new JSONObject().put("type", "conversation.item.create").put("item", new JSONObject()
                    .put("type", "function_call_output").put("call_id", callId).put("output", out.toString())));
            // It already said "收到" before starting the task; another sentence now only delays the user.
            String st = out.optString("status");
            if (!"started".equals(st) && !"updated".equals(st)) requestResponse();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            AppLog.w(AppLog.CALL, "tool " + name + " failed: " + e.getMessage());
        }
    }

    private void runTask(String task, JSONObject out) throws Exception {
        if (task.isEmpty()) {
            out.put("status", "error").put("message", "task is empty");
            return;
        }
        if (MoneyGuard.isBlockedTask(ctx, task)) {
            out.put("status", "refused").put("message", "涉及钱的操作不支持，请用户自己操作");
            return;
        }
        if (taskBefore == null && !RunState.isRunning() && queued.isEmpty()) {
            // Wait a moment before starting: the user may still be adding to it.
            pendingTask = task;
            pendingAt = System.currentTimeMillis();
            out.put("status", "started").put("message", "任务已开始，结束后系统会通知结果");
            return;
        }
        if (taskBefore != null || RunState.isRunning() || !queued.isEmpty()) {
            queued.add(task);
            AppLog.i(AppLog.CALL, "task queued, " + queued.size() + " waiting");
            out.put("status", "queued").put("position", queued.size())
                    .put("message", "前面还有任务在做，这个排在第 " + queued.size() + " 个，轮到时自动开始；一句话告诉用户已排队");
            return;
        }
        String refused = startTask(task);
        if (refused != null) {
            out.put("status", "error").put("message", refused);
            return;
        }
        out.put("status", "started").put("message", "任务已开始，结束后系统会通知结果");
    }

    /** Restarts the task that just began with the user's addition or correction folded in. */
    private void updateTask(String task, JSONObject out) throws Exception {
        if (task.isEmpty() || MoneyGuard.isBlockedTask(ctx, task)) {
            out.put("status", "refused").put("message", task.isEmpty() ? "task is empty" : "涉及钱的操作不支持，请用户自己操作");
            return;
        }
        if (pendingTask != null) {
            pendingTask = task;
            pendingAt = System.currentTimeMillis();
        } else if (RunState.isRunning()) {
            stoppedByVoice = true; // its "stopped" result is not news
            AgentService.stop(ctx);
            for (int i = 0; i < 50 && RunState.isRunning(); i++) Thread.sleep(100);
            AppLog.i(AppLog.CALL, "task restarted with the user's addition");
            String refused = startTask(task);
            stoppedByVoice = false;
            if (refused != null) {
                out.put("status", "error").put("message", refused);
                return;
            }
        } else {
            runTask(task, out);
            return;
        }
        out.put("status", "updated").put("message", "已按补充后的任务重新开始");
    }

    /** Starts the agent on the task; returns why it could not, or null. */
    private String startTask(String task) throws InterruptedException {
        AgentAccessibilityService a11y = AgentAccessibilityService.get();
        if (a11y == null) return "无障碍服务没有打开，没法操作手机";
        // The loop holds while our own screens are in front; start from the home screen instead.
        if (ctx.getPackageName().equals(a11y.currentPackage())) {
            Gestures.performGlobalAction(a11y, "home");
            Thread.sleep(600);
        }
        String before = String.valueOf(RunState.lastFinishedId());
        taskBefore = before;
        taskStartedAt = System.currentTimeMillis();
        String id = AgentService.start(ctx, task, false); // the result is spoken: stay where the task ended
        // A lost start would leave the call waiting for a task that never runs.
        long until = System.currentTimeMillis() + 10_000;
        while (true) {
            RunRecord r = RunState.current();
            if ((r != null && id.equals(r.id)) || !before.equals(String.valueOf(RunState.lastFinishedId()))) break;
            if (System.currentTimeMillis() > until) {
                AppLog.w(AppLog.CALL, "the task did not start within 10 s");
                taskBefore = null;
                return "手机上的任务没能启动，请用户再说一次";
            }
            Thread.sleep(200);
        }
        status.accept(ctx.getString(R.string.voice_call_task));
        return null;
    }

    private void recordUsage(JSONObject response) {
        JSONObject u = response == null ? null : response.optJSONObject("usage");
        if (u == null) return;
        int in = u.optInt("input_tokens", 0), out = u.optInt("output_tokens", 0);
        if (in + out == 0) return;
        UsageStore.record(ctx, provider.id, model, UsageStore.VOICE, in, out, false);
        long limit = AppSettings.dailyTokenLimit(ctx);
        if (limit > 0 && UsageStore.todayTokens(ctx) >= limit) {
            AppLog.w(AppLog.CALL, "daily token limit reached");
            ended = true;
        }
    }

    private static String since(long t) {
        return t == 0 ? "?" : (System.currentTimeMillis() - t) + " ms";
    }

    // ------------------------------------------------------------------ audio

    private void openAudio() throws Unavailable {
        audio = ctx.getSystemService(AudioManager.class);
        previousMode = audio.getMode();
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        routeToSpeaker(true);
        try {
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, CHUNK * 2 * 8);
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(mic.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(mic.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
            fullDuplex = aec != null && aec.getEnabled();
            mic.startRecording();

            int min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            speaker = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, OUT_RATE)) // 0.5 s
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            speaker.play();
            player = new Thread(this::playLoop, "artemis-voice-play");
            player.start();

            VadModelConfig config = new VadModelConfig();
            SileroVadModelConfig silero = new SileroVadModelConfig();
            silero.setModel("voice/vad/silero_vad.onnx");
            silero.setThreshold(0.5f);
            silero.setMinSilenceDuration(MIN_SILENCE);
            silero.setMinSpeechDuration(0.25f);
            silero.setWindowSize(512);
            silero.setMaxSpeechDuration(30f);
            config.setSileroVadModelConfig(silero);
            config.setSampleRate(IN_RATE);
            config.setNumThreads(1);
            vad = new Vad(ctx.getAssets(), config);
        } catch (Exception | UnsatisfiedLinkError e) {
            throw new Unavailable("audio: " + e.getMessage(), e);
        }
    }

    private void playLoop() {
        try {
            while (!ended) {
                byte[] pcm = playback.take();
                if (dropAudio) continue;
                if (panel != null) panel.level(rms(pcm));
                speaker.write(pcm, 0, pcm.length);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IllegalStateException e) {
            // The track was released while the call ended.
        }
    }

    private void closeAudio() {
        ended = true;
        if (player != null) player.interrupt();
        if (mic != null) {
            mic.release();
            mic = null;
        }
        if (aec != null) aec.release();
        if (ns != null) ns.release();
        if (player != null) {
            try {
                player.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (speaker != null) {
            speaker.release();
            speaker = null;
        }
        if (vad != null) vad.release();
        if (audio != null) {
            routeToSpeaker(false);
            audio.setMode(previousMode);
        }
    }

    /**
     * Where the call is heard. In communication mode Android does not pick Bluetooth by itself:
     * the app must choose it, or a car's hands-free speaker stays silent and the call goes to the
     * phone (at the low call volume). Order: Bluetooth hands-free / LE headset, then a wired or USB
     * headset, then the phone's loudspeaker (not the earpiece). The chosen device's microphone is
     * used too, so in a car the car's microphone and echo canceller take over.
     */
    private void routeToSpeaker(boolean on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!on) {
                audio.clearCommunicationDevice();
                return;
            }
            AudioDeviceInfo best = null;
            int bestRank = Integer.MAX_VALUE;
            for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                int rank = routeRank(d.getType());
                if (rank < bestRank) {
                    best = d;
                    bestRank = rank;
                }
            }
            if (best != null) {
                boolean ok = audio.setCommunicationDevice(best);
                AppLog.i(AppLog.CALL, "audio to " + routeName(best.getType()) + (ok ? "" : " (refused)"));
            }
        } else {
            boolean bluetooth = false, wired = false;
            for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) bluetooth = true;
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) wired = true;
            }
            if (on && bluetooth) {
                audio.startBluetoothSco();
                audio.setBluetoothScoOn(true);
                AppLog.i(AppLog.CALL, "audio to bluetooth");
            } else if (!on && audio.isBluetoothScoOn()) {
                audio.setBluetoothScoOn(false);
                audio.stopBluetoothSco();
            }
            audio.setSpeakerphoneOn(on && !bluetooth && !wired);
        }
    }

    private static int routeRank(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return 0;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return 1;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return 2;
            default:
                return 9; // earpiece and others: only if nothing else
        }
    }

    private static String routeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "bluetooth";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "bluetooth LE headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired headset";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "loudspeaker";
            default: return "device type " + type;
        }
    }

    /** Loudness 0..1 for the panel; speech sits around 0.3-0.8. */
    private static float rms(short[] pcm, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) pcm[i] * pcm[i];
        return (float) Math.min(1, 4 * Math.sqrt(sum / Math.max(1, n)) / 32768);
    }

    private static float rms(byte[] pcm) {
        int n = pcm.length / 2;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            short v = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
            sum += (double) v * v;
        }
        return (float) Math.min(1, 4 * Math.sqrt(sum / Math.max(1, n)) / 32768);
    }

    /** Plain RMS of a chunk, for comparing loudness within a turn. */
    private static double level(short[] pcm, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / Math.max(1, n));
    }

    private static float[] toFloats(short[] pcm, int n) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = pcm[i] / 32768f;
        return f;
    }
}
