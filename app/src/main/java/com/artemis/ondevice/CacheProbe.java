package com.artemis.ondevice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Does the task model's service reuse a prompt prefix it has just seen? Sends request A twice
 * (identical) and B once (same size, different from the first character, so nothing can be
 * reused), with an 8-token answer so generation hardly counts; text only and with a screenshot-
 * sized image; three rounds. If A's second send waits clearly less than A's first and than B,
 * the prefix was cached. Started over adb (VoiceTestReceiver "cachetest"); results go to the log.
 */
final class CacheProbe {

    private static final int ROUNDS = 3, LINES = 400, MAX_TOKENS = 8;

    private CacheProbe() {}

    static void runAsync(Context ctx) {
        new Thread(() -> {
            try {
                run(ctx.getApplicationContext());
            } catch (Exception e) {
                AppLog.w(AppLog.MODEL, "cache probe failed", e);
            }
        }, "artemis-cache-probe").start();
    }

    private static void run(Context ctx) throws Exception {
        ProviderStore.Active active = ProviderStore.getActive(ctx);
        ProviderStore.Provider p = active == null ? null : ProviderStore.get(ctx, active.provider);
        if (p == null) throw new IllegalStateException("no task model configured");
        ChatClient client = new ChatClient(p, ProviderStore.apiKey(ctx, p.id)).countAs(ctx, UsageStore.TEST);
        byte[] image = image();
        AppLog.i(AppLog.MODEL, "cache probe start: " + p.id + " / " + active.model + ", " + ROUNDS + " rounds, prompt ~"
                + LINES * 12 + " tokens, image " + image.length / 1024 + " KB");
        List<Long> textCold = new ArrayList<>(), textWarm = new ArrayList<>(), textOther = new ArrayList<>();
        List<Long> imgCold = new ArrayList<>(), imgWarm = new ArrayList<>(), imgOther = new ArrayList<>();
        Random random = new Random();
        for (int round = 1; round <= ROUNDS; round++) {
            String a = prompt(random.nextLong()), b = prompt(random.nextLong());
            textCold.add(send(client, active.model, a, null, "text A first"));
            textWarm.add(send(client, active.model, a, null, "text A again"));
            textOther.add(send(client, active.model, b, null, "text B (control)"));
            String c = prompt(random.nextLong()), d = prompt(random.nextLong());
            imgCold.add(send(client, active.model, c, image, "image A first"));
            imgWarm.add(send(client, active.model, c, image, "image A again"));
            imgOther.add(send(client, active.model, d, image, "image B (control)"));
        }
        AppLog.i(AppLog.MODEL, "cache probe result (median wait): text first " + median(textCold) + " ms, again "
                + median(textWarm) + " ms, control " + median(textOther) + " ms; with image first " + median(imgCold)
                + " ms, again " + median(imgWarm) + " ms, control " + median(imgOther) + " ms; cache hits reported: "
                + client.lastCached);
    }

    private static long send(ChatClient client, String model, String system, byte[] jpeg, String label) throws Exception {
        client.complete(model, system, "Reply with the single word: ok", jpeg, null, MAX_TOKENS);
        AppLog.i(AppLog.MODEL, "cache probe " + label + ": wait " + client.lastWaitMs + " ms, cached " + client.lastCached);
        return client.lastWaitMs;
    }

    /** ~LINES*12 tokens of plain text; the seed changes it from the first character on. */
    private static String prompt(long seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder("Session ").append(Long.toHexString(seed)).append(".\n");
        String[] words = {"screen", "button", "settings", "battery", "network", "window", "list", "label", "value",
                "option", "switch", "page", "search", "result", "message", "contact"};
        for (int i = 0; i < LINES; i++) {
            sb.append("Rule ").append(i).append(':');
            for (int w = 0; w < 9; w++) sb.append(' ').append(words[r.nextInt(words.length)]);
            sb.append(".\n");
        }
        return sb.toString();
    }

    /** A phone-screenshot-sized JPEG (461 x 1024, like the agent sends). */
    private static byte[] image() {
        Bitmap b = Bitmap.createBitmap(461, 1024, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(22);
        for (int y = 40; y < 1024; y += 48) {
            p.setColor(Color.rgb(30, 30, 30));
            c.drawText("Setting item " + y / 48 + "   value " + (y * 7 % 100), 24, y, p);
            p.setColor(Color.rgb(220, 220, 230));
            c.drawRect(16, y + 12, 445, y + 14, p);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, out);
        b.recycle();
        return out.toByteArray();
    }

    private static long median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }
}
