package com.artemis.ondevice;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * In-process live log shared by the loop (writer) and the activity (reader).
 * Every line also goes to logcat under {@link AgentService#TAG}; no line ever carries a secret.
 */
final class RunLog {

    interface Listener {
        void onLogChanged(String fullText, boolean running);
    }

    private static final int MAX_CHARS = 60_000;
    private static final StringBuilder TEXT = new StringBuilder();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;
    private static Listener listener;

    private RunLog() {}

    static synchronized void clear() {
        TEXT.setLength(0);
        notifyListener();
    }

    static synchronized void line(String s) {
        Log.i(AgentService.TAG, s);
        TEXT.append(s).append('\n');
        if (TEXT.length() > MAX_CHARS) TEXT.delete(0, TEXT.length() - MAX_CHARS);
        notifyListener();
    }

    static synchronized void setRunning(boolean value) {
        running = value;
        notifyListener();
    }

    static boolean isRunning() {
        return running;
    }

    static synchronized void setListener(Listener l) {
        listener = l;
        notifyListener();
    }

    private static void notifyListener() {
        final Listener l = listener;
        if (l == null) return;
        final String text = TEXT.toString();
        final boolean r = running;
        MAIN.post(new Runnable() {
            @Override public void run() { l.onLogChanged(text, r); }
        });
    }
}
