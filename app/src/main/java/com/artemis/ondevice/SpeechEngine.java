package com.artemis.ondevice;

import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.io.IOException;

/**
 * The offline voice models (sherpa-onnx, loaded from assets/voice/, see scripts/fetch-voice.sh):
 * wake word spotting, streaming speech recognition with end-of-utterance detection, and speech
 * synthesis. Not thread-safe: one worker thread (VoiceService) drives it.
 */
final class SpeechEngine {

    static final int SAMPLE_RATE = 16_000;
    private static final String DIR = "voice/";

    private final KeywordSpotter kws;
    // Loaded after the wake word spotter, on another thread (loadRest): wake-up listens within a
    // second instead of after all models (once 85 s on a throttled phone). Use only after awaitReady.
    private OnlineRecognizer asr;
    /** Recognizes a whole finished sentence, more accurately than asr; null until downloaded and loaded. */
    private volatile OfflineRecognizer sentenceAsr;
    private final Object sentenceLock = new Object();
    /** Set by release (under sentenceLock): a late load must not start after it. */
    private boolean released, sentenceLoading;
    private OfflineTts tts;
    private final java.util.concurrent.CountDownLatch rest = new java.util.concurrent.CountDownLatch(1);
    private volatile Exception restError;
    /** Speaker embeddings for the voiceprint; null when the model is not packaged. */
    private com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor speaker;
    /** Speech detector for the recognizer's wake check (VoiceService); null when not packaged. */
    private com.k2fsa.sherpa.onnx.Vad vad;
    private OnlineStream kwsStream;
    /** The wake word in use and its keyword line ("" = the default from keywords.txt). */
    private String wakeWord = WakeWord.DEFAULT, wakeLine = "";
    private boolean wakeHigh;
    private OnlineStream asrStream;
    private final java.util.Map<String, float[]> cache = new java.util.HashMap<>();

    private SpeechEngine(AssetManager am) {
        kws = new KeywordSpotter(am, kwsConfig());
        kwsStream = kws.createStream("");
    }

    /**
     * Loads recognition, synthesis and the voiceprint model and renders the fixed phrases. Runs on
     * its own thread while the wake word is already being listened for.
     */
    void loadRest(AssetManager am, String... phrases) {
        try {
            asr = new OnlineRecognizer(am, asrConfig());
            asrStream = asr.createStream("");
            tts = new OfflineTts(am, ttsConfig());
            speaker = Voiceprint.available(am) ? Voiceprint.extractor(am) : null;
            vad = wakeVad(am);
            prepare(phrases);
        } catch (Exception | Error e) {
            restError = e instanceof Exception ? (Exception) e : new IllegalStateException(e);
        } finally {
            rest.countDown();
        }
    }

    boolean isReady() { return rest.getCount() == 0 && restError == null; }

    /** Waits until loadRest is done; throws if it failed. */
    void awaitReady() throws Exception {
        rest.await();
        if (restError != null) throw restError;
    }

    /** Throws IOException when the models were not packaged (scripts/fetch-voice.sh not run). */
    static SpeechEngine load(AssetManager am) throws IOException {
        for (String f : new String[]{"kws/keywords.txt", "asr/model.onnx", "tts/model.onnx"}) { // all must be packaged
            am.open(DIR + f).close();
        }
        return new SpeechEngine(am);
    }

    // ------------------------------------------------------------------ wake word

    /** Feeds microphone samples; true when the wake word was just heard. */
    boolean wakeWord(float[] samples) {
        kwsStream.acceptWaveform(samples, SAMPLE_RATE);
        boolean hit = false;
        while (kws.isReady(kwsStream)) {
            kws.decode(kwsStream);
            String keyword = kws.getResult(kwsStream).getKeyword();
            if (!keyword.isEmpty()) {
                kws.reset(kwsStream);
                // A custom wake word's stream still holds the default one: only the chosen one wakes.
                if (keyword.equals(wakeWord)) hit = true;
            }
        }
        return hit;
    }

    /** Drops audio heard so far (e.g. our own voice while speaking). */
    void resetWakeWord() {
        kwsStream.release();
        kwsStream = kws.createStream(wakeLine);
    }

    /**
     * Switches the wake word; {@code line} is from {@link WakeWord#keywordLine}. High sensitivity
     * boosts the phrase and lowers its trigger threshold (keywords.txt uses the defaults: score
     * 1.0, threshold 0.25): it wakes more easily, and more often by mistake.
     */
    void setWakeWord(String phrase, String line, boolean high) {
        wakeWord = phrase;
        wakeHigh = high;
        // Every pronunciation gets a boost and a trigger threshold (keywords.txt uses the stock 1.0 / 0.25,
        // which missed the wake word often on a phone).
        wakeLine = line.replace(" @", high ? " :2.0 #0.12 @" : " :1.5 #0.20 @");
        resetWakeWord();
    }

    boolean wakeHigh() { return wakeHigh; }

    /** False until setWakeWord ran once: the stream then still has only keywords.txt's stock settings. */
    boolean wakeConfigured() { return !wakeLine.isEmpty(); }

    String wakeWord() { return wakeWord; }

    /** Whether someone is speaking in these samples (after loadRest; false without a VAD model). */
    boolean speaking(float[] samples) {
        if (vad == null) return false;
        vad.acceptWaveform(samples);
        while (!vad.empty()) vad.pop();
        return vad.isSpeechDetected();
    }

    private static com.k2fsa.sherpa.onnx.Vad wakeVad(AssetManager am) {
        try {
            am.open(DIR + "vad/silero_vad.onnx").close();
        } catch (IOException e) {
            return null;
        }
        com.k2fsa.sherpa.onnx.SileroVadModelConfig silero = new com.k2fsa.sherpa.onnx.SileroVadModelConfig();
        silero.setModel(DIR + "vad/silero_vad.onnx");
        silero.setThreshold(0.5f);
        silero.setMinSilenceDuration(0.3f);
        silero.setMinSpeechDuration(0.2f);
        silero.setWindowSize(512);
        silero.setMaxSpeechDuration(20f);
        com.k2fsa.sherpa.onnx.VadModelConfig config = new com.k2fsa.sherpa.onnx.VadModelConfig();
        config.setSileroVadModelConfig(silero);
        config.setSampleRate(SAMPLE_RATE);
        config.setNumThreads(1);
        return new com.k2fsa.sherpa.onnx.Vad(am, config);
    }

    // ------------------------------------------------------------------ voiceprint

    /**
     * How much the speech sounds like the voiceprint (cosine, about -1..1); NaN when it cannot
     * tell: no model, no voiceprint, or too little speech.
     */
    float voiceScore(float[] voiceprint, float[] samples) {
        if (speaker == null || voiceprint == null || samples.length < Voiceprint.MIN_SECONDS * SAMPLE_RATE) return Float.NaN;
        float[] e = Voiceprint.embed(speaker, samples);
        return e == null ? Float.NaN : Voiceprint.cosine(voiceprint, e);
    }

    // ------------------------------------------------------------------ recognition

    void startUtterance() {
        asrStream.release();
        asrStream = asr.createStream("");
    }

    /** Feeds samples; true once the speaker has finished a sentence (trailing silence). */
    boolean feedUtterance(float[] samples) {
        asrStream.acceptWaveform(samples, SAMPLE_RATE);
        while (asr.isReady(asrStream)) asr.decode(asrStream);
        return asr.isEndpoint(asrStream);
    }

    String utteranceText() {
        return asr.getResult(asrStream).getText().trim();
    }

    boolean hasSentenceRecognizer() { return sentenceAsr != null; }

    /**
     * Loads the downloaded sentence recognizer ({@link SentenceModel}) if it is there and not
     * loaded yet; takes a few seconds, so call it off the audio thread. Safe to call repeatedly.
     */
    void loadSentenceRecognizer(java.io.File model, java.io.File tokens) {
        synchronized (sentenceLock) {
            if (sentenceAsr != null || released || sentenceLoading || !model.isFile() || !tokens.isFile()) return;
            sentenceLoading = true;
        }
        // Built outside the lock: a call's audio thread must not wait seconds for it.
        long t0 = System.currentTimeMillis();
        OfflineRecognizer r = null;
        try {
            r = new OfflineRecognizer(null, sentenceConfig(model.getPath(), tokens.getPath()));
            AppLog.i(AppLog.VOICE, "sentence recognizer loaded in " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Exception | Error e) {
            AppLog.w(AppLog.VOICE, "sentence recognizer failed to load: " + e.getClass().getSimpleName());
        }
        synchronized (sentenceLock) {
            sentenceLoading = false;
            if (released) {
                if (r != null) r.release();
            } else {
                sentenceAsr = r;
            }
        }
    }

    /**
     * Recognizes a finished sentence (16 kHz) with the bigger offline model; null without it.
     * One caller at a time (the call's audio thread); each call has its own stream.
     */
    String recognizeSentence(float[] samples) {
        if (sentenceAsr == null) return null; // not loaded (yet): do not wait for the lock
        synchronized (sentenceLock) {
            OfflineRecognizer r = sentenceAsr;
            if (r == null) return null;
            OfflineStream s = r.createStream();
            try {
                s.acceptWaveform(samples, SAMPLE_RATE);
                r.decode(s);
                return r.getResult(s).getText().trim();
            } finally {
                s.release();
            }
        }
    }

    // ------------------------------------------------------------------ synthesis

    /** Synthesizes fixed phrases once so they play without delay later. */
    void prepare(String... phrases) {
        for (String p : phrases) cache.put(p, tts.generate(p, 0, 1.0f).getSamples());
    }

    /**
     * Speaks the text and returns when playback has finished. Prepared phrases play at once;
     * other text streams: the first sentence plays while the next ones are still being synthesized.
     */
    void speak(String text) throws InterruptedException {
        int rate = tts.sampleRate();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(rate * 4) // 1 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        final long[] written = {0};
        final long t0 = System.currentTimeMillis();
        try {
            track.play();
            float[] cached = cache.get(text);
            if (cached != null) {
                track.write(cached, 0, cached.length, AudioTrack.WRITE_BLOCKING);
                written[0] = cached.length;
            } else {
                // Not a lambda: the JNI side looks up invoke([F)Ljava/lang/Integer; by exact signature,
                // and a lambda only has the erased invoke(Object) (NoSuchMethodError → abort).
                tts.generateWithCallback(text, 0, 1.0f, new kotlin.jvm.functions.Function1<float[], Integer>() {
                    @Override
                    public Integer invoke(float[] samples) {
                        if (written[0] == 0) {
                            AppLog.i(AppLog.VOICE, "first audio after "
                                    + (System.currentTimeMillis() - t0) + " ms");
                        }
                        track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
                        written[0] += samples.length;
                        return 1; // keep going
                    }
                });
            }
            // write() returns once the last chunk is queued; wait for it to be heard.
            while (track.getPlaybackHeadPosition() < written[0]) {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) break;
                Thread.sleep(50);
            }
        } finally {
            track.stop();
            track.release();
        }
    }

    void release() {
        try {
            rest.await(); // the loader may still be using the models
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Whatever loaded: a failed loadRest can leave some models null.
        kwsStream.release();
        kws.release();
        if (asrStream != null) asrStream.release();
        if (asr != null) asr.release();
        synchronized (sentenceLock) {
            released = true;
            if (sentenceAsr != null) sentenceAsr.release();
            sentenceAsr = null;
        }
        if (speaker != null) speaker.release();
        if (vad != null) vad.release();
        if (tts != null) tts.release();
    }

    // ------------------------------------------------------------------ configs

    private static FeatureConfig features() {
        FeatureConfig f = new FeatureConfig();
        f.setSampleRate(SAMPLE_RATE);
        f.setFeatureDim(80);
        return f;
    }

    private static KeywordSpotterConfig kwsConfig() {
        OnlineTransducerModelConfig t = new OnlineTransducerModelConfig();
        t.setEncoder(DIR + "kws/encoder.onnx");
        t.setDecoder(DIR + "kws/decoder.onnx");
        t.setJoiner(DIR + "kws/joiner.onnx");
        OnlineModelConfig m = new OnlineModelConfig();
        m.setTransducer(t);
        m.setTokens(DIR + "kws/tokens.txt");
        m.setModelType("zipformer2");
        m.setNumThreads(1);
        KeywordSpotterConfig c = new KeywordSpotterConfig();
        c.setFeatConfig(features());
        c.setModelConfig(m);
        c.setKeywordsFile(DIR + "kws/keywords.txt");
        return c;
    }

    private static OnlineRecognizerConfig asrConfig() {
        OnlineZipformer2CtcModelConfig ctc = new OnlineZipformer2CtcModelConfig();
        ctc.setModel(DIR + "asr/model.onnx");
        OnlineModelConfig m = new OnlineModelConfig();
        m.setZipformer2Ctc(ctc);
        m.setTokens(DIR + "asr/tokens.txt");
        m.setNumThreads(2);
        // Sentence ends after 1.6 s of silence (1.2 s cut people off mid-task while they thought);
        // give up after 4 s of silence with nothing said.
        EndpointConfig e = new EndpointConfig();
        e.setRule1(new EndpointRule(false, 4f, 0f));
        e.setRule2(new EndpointRule(true, 1.6f, 0f));
        e.setRule3(new EndpointRule(false, 0f, 20f));
        OnlineRecognizerConfig c = new OnlineRecognizerConfig();
        c.setFeatConfig(features());
        c.setModelConfig(m);
        c.setEndpointConfig(e);
        c.setEnableEndpoint(true);
        return c;
    }

    /** Paraformer small (Chinese): non-streaming, used once a sentence is finished. Paths are files, not assets. */
    private static OfflineRecognizerConfig sentenceConfig(String model, String tokens) {
        OfflineParaformerModelConfig p = new OfflineParaformerModelConfig();
        p.setModel(model);
        OfflineModelConfig m = new OfflineModelConfig();
        m.setParaformer(p);
        m.setTokens(tokens);
        m.setModelType("paraformer");
        m.setNumThreads(2);
        OfflineRecognizerConfig c = new OfflineRecognizerConfig();
        c.setFeatConfig(features());
        c.setModelConfig(m);
        return c;
    }

    private static OfflineTtsConfig ttsConfig() {
        OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
        v.setModel(DIR + "tts/model.onnx");
        v.setLexicon(DIR + "tts/lexicon.txt");
        v.setTokens(DIR + "tts/tokens.txt");
        OfflineTtsModelConfig m = new OfflineTtsModelConfig();
        m.setVits(v);
        m.setNumThreads(2);
        OfflineTtsConfig c = new OfflineTtsConfig();
        c.setModel(m);
        c.setRuleFsts(DIR + "tts/phone.fst," + DIR + "tts/date.fst," + DIR + "tts/number.fst");
        return c;
    }
}
