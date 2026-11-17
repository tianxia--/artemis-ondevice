package com.artemis.ondevice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

/**
 * The app's log: every line goes to logcat (tag ArtemisAgent) and to a daily file in the app's
 * private storage (files/logs/YYYY-MM-DD.log), kept 7 days, so problems can be traced afterwards
 * and exported from Settings ({@link LogExport}). See docs/logging.md.
 *
 * Never log what the user said or typed, screen text, answers, keys or service addresses: log
 * states, timings, counts and names. As a safety net, keys, URLs, e-mail addresses and long
 * numbers are masked before anything is written.
 */
final class AppLog {

    // Areas, one per part of the app.
    static final String APP = "app", AGENT = "agent", MODEL = "model", VOICE = "voice", CALL = "call",
            MCP = "mcp", USAGE = "usage", SETTINGS = "settings";

    static final String DIR = "logs";
    private static final int KEEP_DAYS = 7;
    private static final long MAX_DAY_BYTES = 5L << 20, MAX_TOTAL_BYTES = 20L << 20;

    private static final Pattern[] SECRETS = {
            Pattern.compile("(?i)bearer\\s+\\S+"),
            Pattern.compile("\\b(sk|st|ak)-[A-Za-z0-9_-]{8,}"),
            Pattern.compile("(?i)\\b(api[_-]?key|token|password|secret)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?i)\\b(https?|wss?)://\\S+"),
            // Bare host names and IP addresses (e.g. in "failed to connect to host/1.2.3.4"): service
            // addresses stay hidden. Package names (com.android.settings) end in no such domain.
            Pattern.compile("(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|cn|net|org|io|ai|dev|cloud|app|tech|top|xyz|co|me|info|site|local|internal|lan)\\b(?![.\\w])"),
            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),
            // Mainland mobile numbers, and 15-19 digit card or ID numbers; run ids (13 digits + "-") stay.
            Pattern.compile("(?<![\\d-])1[3-9]\\d{9}(?![\\d-])"),
            Pattern.compile("(?<![\\d-])\\d{15,19}(?![\\d-])"),
    };
    private static final String[] MASKS = {"Bearer <hidden>", "<key>", "$1=<hidden>", "<url>", "<host>", "<ip>", "<email>",
            "<phone>", "<number>"};

    private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<>();
    private static final Object WRITE_LOCK = new Object();
    private static volatile File dir;
    private static String day = "";
    private static Writer out;
    private static long dayBytes;
    private static boolean capped;

    private AppLog() {}

    /** Starts writing files; before this, lines only reach logcat. Called once from {@link ArtemisApp}. */
    static synchronized void init(Context ctx) {
        if (dir != null) return;
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.isDirectory() && !d.mkdirs()) {
            Log.w(AgentService.TAG, "log directory not writable");
            return;
        }
        dir = d;
        prune();
        Thread writer = new Thread(AppLog::writeLoop, "artemis-log");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
    }

    static void i(String area, String message) { log('I', area, message, null); }
    static void w(String area, String message) { log('W', area, message, null); }
    static void w(String area, String message, Throwable t) { log('W', area, message, t); }
    static void e(String area, String message, Throwable t) { log('E', area, message, t); }

    private static void log(char level, String area, String message, Throwable t) {
        String text = redact(message) + (t == null ? "" : " | " + describe(t));
        String tagged = area + ": " + text;
        if (level == 'E') Log.e(AgentService.TAG, tagged);
        else if (level == 'W') Log.w(AgentService.TAG, tagged);
        else Log.i(AgentService.TAG, tagged);
        if (dir == null) return;
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date())
                + " " + level + " " + String.format(Locale.ROOT, "%-8s", area)
                + " [" + Thread.currentThread().getName() + "] " + text;
        QUEUE.add(level + line);
    }

    /** Exception class and message (redacted) plus the app's own stack frames, for crash reports. */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getName()).append(": ").append(redact(String.valueOf(t.getMessage())));
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c != t) sb.append(" <- ").append(c.getClass().getSimpleName()).append(": ").append(redact(String.valueOf(c.getMessage())));
            int shown = 0;
            for (StackTraceElement e : c.getStackTrace()) {
                if (shown >= 12) break;
                sb.append("\n    at ").append(e);
                shown++;
            }
        }
        return sb.toString();
    }

    static String redact(String s) {
        if (s == null) return "null";
        String r = s;
        for (int i = 0; i < SECRETS.length; i++) r = SECRETS[i].matcher(r).replaceAll(MASKS[i]);
        return r;
    }

    /** Writes everything queued now, on the caller's thread (crashes, export). */
    static void flush() {
        synchronized (WRITE_LOCK) {
            for (String l; (l = QUEUE.poll()) != null; ) write(l);
            try {
                if (out != null) out.flush();
            } catch (IOException ignored) {
                // Nothing to report it to.
            }
        }
    }

    private static void writeLoop() {
        try {
            while (true) {
                String first = QUEUE.take();
                synchronized (WRITE_LOCK) {
                    write(first);
                    for (String l; (l = QUEUE.poll()) != null; ) write(l);
                    if (out != null) out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.w(AgentService.TAG, "log file not writable: " + e.getMessage());
        }
    }

    /** Caller holds WRITE_LOCK. The first character of the queued line is its level. */
    private static void write(String queued) {
        char level = queued.charAt(0);
        String line = queued.substring(1);
        try {
            String today = line.substring(0, 10);
            if (!today.equals(day) || out == null) {
                if (out != null) out.close();
                File f = new File(dir, today + ".log");
                out = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
                day = today;
                dayBytes = f.length();
                capped = false;
                prune();
            }
            if (dayBytes > MAX_DAY_BYTES && level == 'I') {
                if (!capped) {
                    capped = true;
                    line = line.substring(0, 24) + "W app      [artemis-log] daily log limit reached: info lines dropped until tomorrow";
                } else {
                    return;
                }
            }
            out.write(line);
            out.write('\n');
            dayBytes += line.length() + 1;
        } catch (IOException e) {
            Log.w(AgentService.TAG, "log line not written: " + e.getMessage());
        }
    }

    /** Deletes files older than KEEP_DAYS, then the oldest while over MAX_TOTAL_BYTES. */
    private static void prune() {
        File d = dir;
        File[] files = d == null ? null : d.listFiles((x, name) -> name.endsWith(".log"));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        String oldest = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(new Date(System.currentTimeMillis() - KEEP_DAYS * 86_400_000L));
        long total = 0;
        for (File f : files) total += f.length();
        for (File f : files) {
            boolean old = f.getName().compareTo(oldest) < 0;
            if ((old || total > MAX_TOTAL_BYTES) && !f.getName().startsWith(day)) {
                total -= f.length();
                f.delete();
            }
        }
    }
}
