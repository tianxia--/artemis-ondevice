package com.artemis.ondevice;

import java.util.Locale;

/**
 * Wake word test (Settings > Wake test): every burst of speech the speech detector hears is one
 * attempt, and each attempt ends up woken by the spotter, by the recognizer, by both, missed but
 * close (one syllable off), or missed. While it runs a wake-up only counts, it starts no call.
 * Driven by VoiceService's thread; what was said is never recorded.
 */
final class WakeTest {

    /** The spotter can fire a little after the speech ended; a hit this soon still counts for it. */
    private static final long LATE_SPOT_MS = 1_200;
    /** Shorter bursts are noise (a cough, a click), not attempts. */
    private static final long MIN_ATTEMPT_MS = 300;

    private boolean inSpeech, speechSpotted;
    private long speechStart;
    private boolean pending, pendingSpotted, pendingRecognized, pendingClose;
    private long pendingEnd, pendingMs;

    int attempts, bySpotter, byRecognizer, byBoth, missedClose, missedFar, spottedWithoutSpeech;
    final long startedAt = System.currentTimeMillis();

    void spotted(long now) {
        if (inSpeech) speechSpotted = true;
        else if (pending && now - pendingEnd < LATE_SPOT_MS) pendingSpotted = true;
        else {
            spottedWithoutSpeech++;
            AppLog.i(AppLog.VOICE, "wake test: the spotter fired without detected speech");
        }
    }

    void speechStarted(long now) {
        finish();
        inSpeech = true;
        speechSpotted = false;
        speechStart = now;
    }

    /** {@code recognized}: the recognizer took it for the wake word; {@code close}: one syllable off. */
    void speechEnded(long now, boolean recognized, boolean close) {
        if (!inSpeech) return;
        inSpeech = false;
        if (now - speechStart < MIN_ATTEMPT_MS) return;
        pending = true;
        pendingEnd = now;
        pendingMs = now - speechStart;
        pendingSpotted = speechSpotted;
        pendingRecognized = recognized;
        pendingClose = close;
    }

    /** Settles an attempt once the late spotter window has passed; call every chunk. */
    void tick(long now) {
        if (pending && now - pendingEnd >= LATE_SPOT_MS) finish();
    }

    /** Settles the attempt in progress (when the test stops or the next attempt starts). */
    void finish() {
        if (!pending) return;
        pending = false;
        attempts++;
        String result;
        if (pendingSpotted && pendingRecognized) {
            byBoth++;
            result = "woken by both";
        } else if (pendingSpotted) {
            bySpotter++;
            result = "woken by the spotter";
        } else if (pendingRecognized) {
            byRecognizer++;
            result = "woken by the recognizer";
        } else if (pendingClose) {
            missedClose++;
            result = "MISSED, one syllable off";
        } else {
            missedFar++;
            result = "MISSED, did not sound like it";
        }
        AppLog.i(AppLog.VOICE, "wake test attempt " + attempts + " (" + pendingMs + " ms): " + result);
    }

    int woken() { return bySpotter + byRecognizer + byBoth; }

    String summary() {
        int missed = missedClose + missedFar;
        return String.format(Locale.ROOT, "%d attempts, %d woken (spotter %d, recognizer %d, both %d), %d missed "
                        + "(one syllable off %d, other %d); spotter fired without speech %d times; %d s",
                attempts, woken(), bySpotter, byRecognizer, byBoth, missed, missedClose, missedFar,
                spottedWithoutSpeech, (System.currentTimeMillis() - startedAt) / 1000);
    }
}
