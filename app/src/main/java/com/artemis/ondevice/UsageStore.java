package com.artemis.ondevice;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every model call's token count, summed per day, model and purpose in app-private
 * {@code usage.json} (numbers only, never prompts or replies). Feeds the usage screen and the
 * daily limit.
 */
final class UsageStore {

    static final String TASK = "task";
    static final String REVIEW = "review";
    static final String VOICE = "voice";
    static final String TEST = "test";

    private static final int KEEP_DAYS = 400;

    /** One sum: calls and tokens, and how many of the calls had no usage from the API (estimated). */
    static final class Row {
        String provider = "", model = "", purpose = "";
        long calls, in, out, estimated;

        long tokens() { return in + out; }

        void add(Row o) {
            calls += o.calls;
            in += o.in;
            out += o.out;
            estimated += o.estimated;
        }
    }

    private UsageStore() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "usage.json");
    }

    static String day(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    private static JSONObject read(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return new JSONObject();
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(AgentService.TAG, "usage not readable: " + e.getMessage());
            return new JSONObject();
        }
    }

    static synchronized void record(Context ctx, String provider, String model, String purpose,
                                    int in, int out, boolean estimated) {
        try {
            JSONObject all = read(ctx);
            String today = day(System.currentTimeMillis());
            JSONObject d = all.optJSONObject(today);
            if (d == null) all.put(today, d = new JSONObject());
            String key = provider + "|" + model + "|" + purpose;
            JSONObject e = d.optJSONObject(key);
            if (e == null) d.put(key, e = new JSONObject());
            e.put("calls", e.optLong("calls") + 1).put("in", e.optLong("in") + in).put("out", e.optLong("out") + out)
                    .put("estimated", e.optLong("estimated") + (estimated ? 1 : 0));
            // Drop days older than KEEP_DAYS (keys sort as dates).
            List<String> days = new ArrayList<>();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) days.add(it.next());
            java.util.Collections.sort(days);
            for (int i = 0; i < days.size() - KEEP_DAYS; i++) all.remove(days.get(i));
            File tmp = new File(ctx.getFilesDir(), "usage.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(all.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file(ctx))) throw new IllegalStateException("Cannot write usage");
        } catch (Exception e) {
            Log.w(AgentService.TAG, "usage not saved: " + e.getMessage());
        }
    }

    /** Rows of the last {@code days} days (1 = today; 0 = all), one per provider, model and purpose. */
    static synchronized List<Row> rows(Context ctx, int days) {
        JSONObject all = read(ctx);
        String from = days <= 0 ? "" : day(System.currentTimeMillis() - (days - 1) * 86_400_000L);
        Map<String, Row> sums = new LinkedHashMap<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String date = it.next();
            if (date.compareTo(from) < 0) continue;
            JSONObject d = all.optJSONObject(date);
            if (d == null) continue;
            for (Iterator<String> k = d.keys(); k.hasNext(); ) {
                String key = k.next();
                JSONObject e = d.optJSONObject(key);
                String[] p = key.split("\\|", 3);
                if (e == null || p.length < 3) continue;
                Row r = sums.get(key);
                if (r == null) {
                    r = new Row();
                    r.provider = p[0];
                    r.model = p[1];
                    r.purpose = p[2];
                    sums.put(key, r);
                }
                r.calls += e.optLong("calls");
                r.in += e.optLong("in");
                r.out += e.optLong("out");
                r.estimated += e.optLong("estimated");
            }
        }
        return new ArrayList<>(sums.values());
    }

    /** Tokens used today, all models and purposes. */
    static long todayTokens(Context ctx) {
        long t = 0;
        for (Row r : rows(ctx, 1)) t += r.tokens();
        return t;
    }

    /**
     * Rough token count for calls whose API returned no usage: about 4 characters per token for
     * ASCII, 1.5 per token for other scripts, and a flat amount per image.
     */
    static int estimateTokens(String text, boolean image) {
        if (text == null) text = "";
        int ascii = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) ascii++;
            else other++;
        }
        return Math.round(ascii / 4f + other / 1.5f) + (image ? 1100 : 0);
    }
}
