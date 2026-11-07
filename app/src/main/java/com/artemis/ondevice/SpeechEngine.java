package com.artemis.ondevice;

import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
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
    private final OnlineRecognizer asr;
    private final OfflineTts tts;
    private OnlineStream kwsStream;
    private OnlineStream asrStream;

    private SpeechEngine(AssetManager am) {
        kws = new KeywordSpotter(am, kwsConfig());
        asr = new OnlineRecognizer(am, asrConfig());
        tts = new OfflineTts(am, ttsConfig());
        kwsStream = kws.createStream("");
        asrStream = asr.createStream("");
    }

    /** Throws IOException when the models were not packaged (scripts/fetch-voice.sh not run). */
    static SpeechEngine load(AssetManager am) throws IOException {
        for (String f : new String[]{"kws/keywords.txt", "asr/model.onnx", "tts/model.onnx"}) {
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
            if (!kws.getResult(kwsStream).getKeyword().isEmpty()) {
                kws.reset(kwsStream);
                hit = true;
            }
        }
        return hit;
    }

    /** Drops audio heard so far (e.g. our own voice while speaking). */
    void resetWakeWord() {
        kwsStream.release();
        kwsStream = kws.createStream("");
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

    /** Speaks the text and returns when playback has finished. */
    void speak(String text) throws InterruptedException {
        GeneratedAudio audio = tts.generate(text, 0, 1.0f);
        float[] pcm = audio.getSamples();
        if (pcm.length == 0) return;
        int bytes = pcm.length * 4;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(audio.getSampleRate())
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        try {
            track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
            track.play();
            Thread.sleep(pcm.length * 1000L / audio.getSampleRate() + 150);
        } finally {
            track.stop();
            track.release();
        }
    }

    void release() {
        kwsStream.release();
        asrStream.release();
        kws.release();
        asr.release();
        tts.release();
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
        // Sentence ends after 1.2 s of silence; give up after 4 s of silence with nothing said.
        EndpointConfig e = new EndpointConfig();
        e.setRule1(new EndpointRule(false, 4f, 0f));
        e.setRule2(new EndpointRule(true, 1.2f, 0f));
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
