package com.artemis.ondevice;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The live run, shared by the loop (single writer, on its own thread) and the screens /
 * floating bubble (readers, on the main thread). Readers subscribe and are called on the
 * main thread whenever the loop publishes a change.
 */
final class RunState {

    /** What the loop is doing right now; shown in the bubble and on the home screen. */
    enum Phase { IDLE, OBSERVING, THINKING, ACTING }

    interface Listener {
        void onRunChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private static volatile RunRecord current;
    private static volatile Phase phase = Phase.IDLE;
    private static volatile String lastFinishedId;
    /**
     * Why the user is holding the run: the bubble panel is open, or one of our own screens is in
     * front. While either holds, the loop waits before its next step or action, so it neither
     * acts on our screens nor races the user's Stop. Set on the main thread only.
     */
    enum Hold { PANEL, APP_SCREEN }
    private static volatile boolean heldByPanel, heldByAppScreen;

    private RunState() {}

    static RunRecord current() { return current; }
    static Phase phase() { return phase; }
    static boolean isRunning() { RunRecord r = current; return r != null && r.isRunning(); }
    static String lastFinishedId() { return lastFinishedId; }
    static boolean paused() { return heldByPanel || heldByAppScreen; }

    static void setHold(Hold source, boolean value) {
        boolean before = paused();
        if (source == Hold.PANEL) heldByPanel = value;
        else heldByAppScreen = value;
        if (paused() != before) publish();
    }

    // ---- writer side (loop thread)

    static void begin(RunRecord r) {
        current = r;
        phase = Phase.OBSERVING;
        publish();
    }

    static void phase(Phase p) {
        phase = p;
        publish();
    }

    static void stepAdded() {
        publish();
    }

    static void end(RunRecord r) {
        phase = Phase.IDLE;
        lastFinishedId = r.id;
        publish();
    }

    // ---- reader side (main thread)

    static void subscribe(Listener l) {
        LISTENERS.add(l);
        l.onRunChanged();
    }

    static void unsubscribe(Listener l) {
        LISTENERS.remove(l);
    }

    private static void publish() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                for (Listener l : LISTENERS) l.onRunChanged();
            }
        });
    }
}
