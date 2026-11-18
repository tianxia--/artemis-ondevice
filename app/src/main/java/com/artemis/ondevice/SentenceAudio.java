package com.artemis.ondevice;

import java.util.ArrayList;
import java.util.Locale;

/**
 * One sentence's audio in a call, with the voiceprint checked every {@link #WINDOW_SECONDS}
 * while it is spoken. Someone talking next to the user runs into the user's sentence without a
 * pause; judged as a whole, the mix still sounds like the user. Judged window by window, the
 * other person's stretches can be left out before the sentence is recognized again.
 */
final class SentenceAudio {

    /** How much the speech sounds like the user's voiceprint; NaN when it cannot tell. */
    interface Scorer {
        float score(float[] samples);
    }

    static final float WINDOW_SECONDS = 1.5f;
    /** Bounded memory for long background talk; a turn ends after a while anyway. */
    private static final int MAX_SECONDS = 20;
    /** Silence put between the user's stretches when the others' are cut out. */
    private static final float GAP_SECONDS = 0.2f;

    private final int rate, window;
    /** Null: no voiceprint, everyone is heard. */
    private final Scorer scorer;
    private final ArrayList<float[]> chunks = new ArrayList<>();
    private int samples;
    /** One verdict per scored window, in order, and their scores for the log. */
    private final ArrayList<Boolean> user = new ArrayList<>();
    private final StringBuilder scores = new StringBuilder();

    SentenceAudio(int rate, Scorer scorer) {
        this.rate = rate;
        this.window = Math.round(WINDOW_SECONDS * rate);
        this.scorer = scorer;
    }

    void clear() {
        chunks.clear();
        samples = 0;
        user.clear();
        scores.setLength(0);
    }

    /** Adds speech and scores every window that is complete now (about 0.1 s each). */
    void add(float[] chunk) {
        if (samples >= MAX_SECONDS * rate) return;
        chunks.add(chunk);
        samples += chunk.length;
        if (scorer == null) return;
        while ((user.size() + 1) * window <= samples) scoreWindow(user.size() * window, window);
    }

    int samples() {
        return samples;
    }

    /**
     * Whether the latest window is the user's. Without a voiceprint always; before the first
     * window is complete never, so a voice nearby cannot cut off the reply in its first second.
     */
    boolean latestIsUser() {
        if (scorer == null) return true;
        return !user.isEmpty() && user.get(user.size() - 1);
    }

    /**
     * Ends the sentence: scores the last part when it is long enough to tell, else it goes with
     * the window before it. A sentence too short to tell at all counts as the user's ("好", "停").
     */
    void finish() {
        if (scorer == null) return;
        int start = user.size() * window, rest = samples - start;
        if (rest >= Voiceprint.MIN_SECONDS * rate) scoreWindow(start, rest);
        else if (rest > 0 && !user.isEmpty()) user.add(user.get(user.size() - 1));
    }

    /** True when no window was scored: no voiceprint, or too short to tell. */
    private boolean unchecked() {
        return user.isEmpty();
    }

    /** The share of windows that are the user's, 0..1 (1 when unchecked). */
    float userShare() {
        if (unchecked()) return 1f;
        int n = 0;
        for (boolean u : user) if (u) n++;
        return n / (float) user.size();
    }

    boolean anyUser() {
        return userShare() > 0f;
    }

    /** All the audio, or with a voiceprint only the user's windows, short silences between them. */
    float[] userAudio() {
        float[] all = all();
        if (unchecked() || userShare() == 1f) return all;
        int gap = Math.round(GAP_SECONDS * rate);
        float[] out = new float[all.length + gap * user.size()];
        int at = 0;
        boolean kept = false;
        for (int i = 0; i < user.size(); i++) {
            int from = i * window, to = i == user.size() - 1 ? all.length : Math.min(all.length, from + window);
            if (!user.get(i) || from >= to) continue;
            if (kept && !user.get(i - 1)) at += gap; // a cut: keep the words apart
            System.arraycopy(all, from, out, at, to - from);
            at += to - from;
            kept = true;
        }
        return java.util.Arrays.copyOf(out, at);
    }

    /** For the log: the verdict per window, never the words. */
    String describe() {
        if (unchecked()) return "not checked";
        int n = 0;
        for (boolean u : user) if (u) n++;
        return String.format(Locale.ROOT, "you in %d of %d windows (%s)", n, user.size(), scores.toString().trim());
    }

    private void scoreWindow(int from, int length) {
        float[] part = new float[length];
        int at = 0, skip = from;
        for (float[] c : chunks) {
            if (skip >= c.length) {
                skip -= c.length;
                continue;
            }
            int n = Math.min(c.length - skip, length - at);
            System.arraycopy(c, skip, part, at, n);
            at += n;
            skip = 0;
            if (at == length) break;
        }
        float s = scorer.score(part);
        boolean u = Float.isNaN(s) || s >= Voiceprint.THRESHOLD;
        user.add(u);
        scores.append(String.format(Locale.ROOT, " %.2f", s));
    }

    private float[] all() {
        float[] out = new float[samples];
        int at = 0;
        for (float[] c : chunks) {
            System.arraycopy(c, 0, out, at, c.length);
            at += c.length;
        }
        return out;
    }
}
