package com.artemis.ondevice;

import android.content.Context;
import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The user's voiceprint: a speaker embedding (3D-Speaker CAM++, assets/voice/spk/) averaged over
 * a few recorded sentences, kept only in the app's private files and never sent anywhere. Voice
 * calls compare each sentence with it and ignore other people's.
 */
final class Voiceprint {

    static final String MODEL = "voice/spk/model.onnx";
    private static final String FILE = "voiceprint.json";
    /**
     * Below this a sentence counts as someone else's (CAM++, cosine of embeddings). 0.45 rejected
     * the user's own 2-3 s sentences on a phone (0.40); other speakers mostly score below 0.25.
     */
    static final float THRESHOLD = 0.30f;
    /** Shorter speech gives an unreliable score: it is not checked. */
    static final float MIN_SECONDS = 1.0f;

    private Voiceprint() {}

    static boolean available(AssetManager am) {
        try {
            am.open(MODEL).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static SpeakerEmbeddingExtractor extractor(AssetManager am) {
        // The config's model path is final in the Kotlin class: build it with the path.
        return new SpeakerEmbeddingExtractor(am, new SpeakerEmbeddingExtractorConfig(MODEL, 2, false, "cpu"));
    }

    /** The embedding of 16 kHz speech, unit length; null when there is too little audio. */
    static float[] embed(SpeakerEmbeddingExtractor extractor, float[] samples) {
        OnlineStream s = extractor.createStream();
        try {
            s.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE);
            s.inputFinished();
            if (!extractor.isReady(s)) return null;
            return normalize(extractor.compute(s));
        } finally {
            s.release();
        }
    }

    static float cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) dot += a[i] * b[i];
        return (float) dot; // both are unit length
    }

    /** The average of the recorded embeddings, unit length. */
    static float[] average(java.util.List<float[]> embeddings) {
        float[] sum = new float[embeddings.get(0).length];
        for (float[] e : embeddings) for (int i = 0; i < sum.length; i++) sum[i] += e[i];
        return normalize(sum);
    }

    private static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / n);
        return out;
    }

    // ------------------------------------------------------------------ storage

    static boolean enrolled(Context ctx) {
        return new File(ctx.getFilesDir(), FILE).exists();
    }

    static float[] load(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            JSONArray a = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getJSONArray("embedding");
            float[] v = new float[a.length()];
            for (int i = 0; i < v.length; i++) v[i] = (float) a.getDouble(i);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Context ctx, float[] embedding) throws Exception {
        JSONArray a = new JSONArray();
        for (float x : embedding) a.put((double) x);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(new JSONObject().put("embedding", a).put("model", "campplus-zh-cn")
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(ctx.getFilesDir(), FILE))) throw new IOException("Cannot save the voiceprint");
    }

    static void delete(Context ctx) {
        new File(ctx.getFilesDir(), FILE).delete();
    }
}
