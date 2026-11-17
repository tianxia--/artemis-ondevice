package com.artemis.ondevice;

import android.content.Context;
import android.net.ConnectivityManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The sentence recognizer of voice calls (Paraformer small, 78 MB) is not in the APK: it is
 * downloaded from this project's GitHub release "voice-models-1" when needed, checked against a
 * pinned SHA-256 and kept in the app's private files. Without it calls use the streaming
 * recognizer's text. Downloads resume where they stopped. Mobile data is only used with the
 * user's go-ahead: a download started without it pauses when the phone leaves Wi-Fi.
 */
final class SentenceModel {

    private static final String BASE = "https://github.com/tianxia--/artemis-ondevice/releases/download/voice-models-1/";
    private static final String NAME = "paraformer-zh-small-2024-03-09";
    /** Remote file, local file, SHA-256, size in bytes. */
    private static final String[][] FILES = {
            {NAME + ".tokens.txt", "tokens.txt", "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa", "75352"},
            {NAME + ".int8.onnx", "model.onnx", "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec", "81828675"},
    };
    static final long TOTAL_BYTES = 81_828_675L + 75_352L;

    enum State { MISSING, DOWNLOADING, PAUSED, READY, FAILED }

    private static volatile State state;
    private static volatile long doneBytes;
    private static volatile String error = "";
    private static volatile boolean allowMetered;

    /** Thrown when the phone leaves Wi-Fi during a download that may not use mobile data. */
    private static final class PausedException extends IOException {
        PausedException() { super("left Wi-Fi"); }
    }

    private SentenceModel() {}

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "voice/sentence");
    }

    static File model(Context ctx) { return new File(dir(ctx), "model.onnx"); }

    static File tokens(Context ctx) { return new File(dir(ctx), "tokens.txt"); }

    /** Both files are in place (they are only renamed in once checked). */
    static boolean installed(Context ctx) {
        return model(ctx).isFile() && tokens(ctx).isFile();
    }

    static State state(Context ctx) {
        State s = state;
        if (s == State.DOWNLOADING || s == State.PAUSED || s == State.FAILED) return s;
        return installed(ctx) ? State.READY : State.MISSING;
    }

    /** 0..100 while downloading. */
    static int percent() {
        return (int) Math.min(100, doneBytes * 100 / TOTAL_BYTES);
    }

    static String error() { return error; }

    /** Bytes still to download, counting what earlier attempts left in the .part files. */
    static long remainingBytes(Context ctx) {
        long have = 0;
        for (String[] f : FILES) {
            File part = new File(dir(ctx), f[1] + ".part");
            if (part.isFile()) have += part.length();
        }
        return Math.max(0, TOTAL_BYTES - have);
    }

    /** No network at all. */
    static boolean offline(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        return cm == null || cm.getActiveNetwork() == null;
    }

    /** On mobile data, a metered hotspot, or no network: anything but Wi-Fi-like unmetered. */
    static boolean metered(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        return cm == null || cm.getActiveNetwork() == null || cm.isActiveNetworkMetered();
    }

    /**
     * Starts a download in the background unless installed or already downloading. Without
     * {@code mobileData} it pauses as soon as the network becomes metered.
     */
    static synchronized void download(Context ctx, boolean mobileData) {
        if (state == State.DOWNLOADING || installed(ctx)) return;
        state = State.DOWNLOADING;
        allowMetered = mobileData;
        doneBytes = TOTAL_BYTES - remainingBytes(ctx);
        error = "";
        Context app = ctx.getApplicationContext();
        new Thread(() -> run(app), "artemis-model-download").start();
    }

    /**
     * Downloads by itself only on a network that is not metered (Wi-Fi): 78 MB over mobile data
     * needs the user's go-ahead, from Settings.
     */
    static void downloadIfUnmetered(Context ctx) {
        if (installed(ctx) || state == State.DOWNLOADING || metered(ctx)) return;
        AppLog.i(AppLog.VOICE, "sentence model missing: downloading it on an unmetered network");
        download(ctx, false);
    }

    static synchronized void delete(Context ctx) {
        if (state == State.DOWNLOADING) return;
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) f.delete();
        state = null;
        AppLog.i(AppLog.VOICE, "sentence model deleted");
    }

    private static void run(Context ctx) {
        long t0 = System.currentTimeMillis();
        AppLog.i(AppLog.VOICE, "sentence model download start");
        try {
            File dir = dir(ctx);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir.getName());
            long before = 0;
            for (String[] f : FILES) {
                File part = new File(dir, f[1] + ".part");
                fetch(ctx, BASE + f[0], part, before);
                String sha = sha256(part);
                if (!sha.equals(f[2])) {
                    part.delete();
                    throw new IllegalStateException("checksum mismatch for " + f[1]);
                }
                before += Long.parseLong(f[3]);
            }
            // Only now, both checked: rename them in (the model last, as installed() looks for it).
            for (int i = 0; i < FILES.length; i++) {
                File part = new File(dir, FILES[i][1] + ".part"), done = new File(dir, FILES[i][1]);
                if (!part.renameTo(done)) throw new IllegalStateException("cannot rename " + done.getName());
            }
            state = State.READY;
            AppLog.i(AppLog.VOICE, "sentence model downloaded in " + (System.currentTimeMillis() - t0) / 1000 + " s");
        } catch (Exception e) {
            // Leaving Wi-Fi usually drops the connection before the per-MB check sees it.
            if (e instanceof PausedException || (!allowMetered && e instanceof IOException && metered(ctx))) {
                state = State.PAUSED;
                AppLog.i(AppLog.VOICE, "sentence model download paused: left Wi-Fi at " + percent() + "%");
                return;
            }
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            state = State.FAILED;
            AppLog.w(AppLog.VOICE, "sentence model download failed after " + (System.currentTimeMillis() - t0) / 1000
                    + " s: " + error);
        }
    }

    /** Downloads into {@code part}, resuming what is already there; follows GitHub's redirect. */
    private static void fetch(Context ctx, String url, File part, long before) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = c.getResponseCode();
            boolean append = code == 206;
            if (code == 416) return; // already complete; the checksum decides
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            if (!append) have = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[64 * 1024];
                long checked = have;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    have += n;
                    doneBytes = before + have;
                    if (!allowMetered && have - checked >= 1 << 20) { // check the network every MB
                        checked = have;
                        if (metered(ctx)) throw new PausedException();
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
