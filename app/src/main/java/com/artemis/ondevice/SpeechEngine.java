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
    private OfflineTts tts;
    private final java.util.concurrent.CountDownLatch rest = new java.util.concurrent.CountDownLatch(1);
    private volatile Exception restError;
    /** Speaker embeddings for the voiceprint; null when the model is not packaged. */
    private com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor speaker;
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
        if (speaker != null) speaker.release();
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
