package com.artemis.ondevice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the voiceprint: the user reads three sentences, 4 s each. Voice wake-up is paused
 * meanwhile (it holds the microphone) and resumed after. The audio stays in memory; only the
 * averaged embedding is saved ({@link Voiceprint}).
 */
final class VoiceprintDialog {

    private static final int SECONDS = 4;
    /** Below this RMS (of 32768) nothing was said. */
    private static final double MIN_LEVEL = 300;

    private final AppCompatActivity activity;
    private final Runnable done;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<float[]> embeddings = new ArrayList<>();
    private String[] sentences;
    private TextView text;
    private MaterialButton record;
    private AlertDialog dialog;
    private SpeakerEmbeddingExtractor extractor;
    private volatile boolean closed;

    private VoiceprintDialog(AppCompatActivity activity, Runnable done) {
        this.activity = activity;
        this.done = done;
    }

    /** Shows the dialog; {@code done} runs on the main thread when it closes. Needs RECORD_AUDIO. */
    static void show(AppCompatActivity activity, Runnable done) {
        new VoiceprintDialog(activity, done).open();
    }

    private void open() {
        sentences = activity.getResources().getStringArray(R.array.voiceprint_sentences);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        text = new TextView(activity);
        text.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        box.addView(text);
        record = new MaterialButton(activity);
        record.setText(R.string.voiceprint_record);
        record.setOnClickListener(v -> recordNext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(record, lp);
        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.voiceprint_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(d -> close())
                .show();
        VoiceService.stop(activity); // it holds the microphone
        prompt(null);
    }

    private void prompt(String problem) {
        int i = embeddings.size();
        text.setText((problem == null ? "" : problem + "\n\n")
                + activity.getString(R.string.voiceprint_read, i + 1, sentences.length, sentences[i]));
        record.setEnabled(true);
        record.setText(R.string.voiceprint_record);
    }

    private void recordNext() {
        record.setEnabled(false);
        record.setText(activity.getString(R.string.voiceprint_recording, SECONDS));
        new Thread(this::recordAndEmbed, "artemis-voiceprint").start();
    }

    @SuppressLint("MissingPermission") // checked by the caller before showing the dialog
    private void recordAndEmbed() {
        String problem = null;
        try {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("no microphone permission");
            }
            if (extractor == null) extractor = Voiceprint.extractor(activity.getAssets());
            int rate = SpeechEngine.SAMPLE_RATE;
            short[] pcm = new short[rate * SECONDS];
            AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, rate);
            try {
                mic.startRecording();
                for (int at = 0; at < pcm.length && !closed; ) {
                    int n = mic.read(pcm, at, Math.min(rate / 10, pcm.length - at));
                    if (n < 0) throw new IllegalStateException("microphone read error " + n);
                    at += n;
                }
            } finally {
                mic.release();
            }
            if (closed) return;
            double sum = 0;
            float[] f = new float[pcm.length];
            for (int i = 0; i < pcm.length; i++) {
                sum += (double) pcm[i] * pcm[i];
                f[i] = pcm[i] / 32768f;
            }
            if (Math.sqrt(sum / pcm.length) < MIN_LEVEL) {
                problem = activity.getString(R.string.voiceprint_too_quiet);
            } else {
                float[] e = Voiceprint.embed(extractor, f);
                if (e == null) problem = activity.getString(R.string.voiceprint_too_quiet);
                else embeddings.add(e);
            }
        } catch (Exception e) {
            AppLog.w(AppLog.VOICE, "voiceprint: " + e.getMessage());
            problem = activity.getString(R.string.voiceprint_failed, String.valueOf(e.getMessage()));
        }
        final String p = problem;
        main.post(() -> {
            if (closed) return;
            if (embeddings.size() < sentences.length) {
                prompt(p);
                return;
            }
            finish();
        });
    }

    private void finish() {
        try {
            Voiceprint.save(activity, Voiceprint.average(embeddings));
            AppLog.i(AppLog.VOICE, "voiceprint: saved from " + embeddings.size() + " sentences");
            android.widget.Toast.makeText(activity, R.string.voiceprint_saved, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(activity, e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
        dialog.dismiss();
    }

    private void close() {
        if (closed) return;
        closed = true;
        new Thread(() -> {
            // Let a recording in progress stop before releasing the model it may still use.
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (extractor != null) extractor.release();
        }).start();
        VoiceService.startIfEnabled(activity);
        done.run();
    }
}
