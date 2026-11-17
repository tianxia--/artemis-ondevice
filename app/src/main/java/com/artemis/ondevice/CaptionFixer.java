package com.artemis.ondevice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fixes the caption of what the user said in a voice call: on-phone recognition writes homophones
 * and near-sounding words ("高的" for 高德). The sentence goes to the voice model at once, unfixed
 * (it understands it anyway, and waiting would slow every answer); in parallel the task model
 * corrects it here, with the installed apps' names and the last reply as context, and the panel
 * shows the corrected text when it arrives. Any failure keeps the recognized text.
 */
final class CaptionFixer {

    private static final int MAX_APPS = 200;
    private static final String SYSTEM =
            "你是语音识别纠错器。输入是手机离线语音识别出的一句中文，可能有同音字、近音字、错别字、多字漏字或断句错误。"
                    + "按发音和上下文，改成用户最可能说的原话。只改识别错误：不改写、不润色、不补充用户没说的内容，"
                    + "不回答，也不执行。拿不准就保留原文。提到的应用尽量对应到已安装应用的名字。"
                    + "只输出改正后的那一句，不要解释，不要引号。";

    private final Context ctx;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ChatClient client;
    private final String model;
    private String apps;

    private CaptionFixer(Context ctx, ChatClient client, String model) {
        this.ctx = ctx;
        this.client = client;
        this.model = model;
    }

    /** Null when no task model is configured: captions then stay as recognized. */
    static CaptionFixer create(Context ctx) {
        ProviderStore.Active active = ProviderStore.getActive(ctx);
        ProviderStore.Provider p = active == null ? null : ProviderStore.get(ctx, active.provider);
        if (p == null) return null;
        try {
            CaptionFixer f = new CaptionFixer(ctx, new ChatClient(p, ProviderStore.apiKey(ctx, p.id)).countAs(ctx, UsageStore.VOICE).noThinking(true),
                    active.model);
            // App names took ~3 s to read on a phone: read them now, before the first sentence.
            f.exec.execute(() -> {
                long t0 = System.currentTimeMillis();
                f.apps();
                AppLog.i(AppLog.CALL, "caption fixing: app names read in " + (System.currentTimeMillis() - t0) + " ms");
            });
            return f;
        } catch (Exception e) {
            AppLog.w(AppLog.CALL, "caption fixing off: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Corrects {@code heard} in the background; {@code done} gets the corrected text, only when it
     * differs. {@code lastReply} is what the assistant said just before (may be empty).
     */
    void fix(String heard, String lastReply, Consumer<String> done) {
        exec.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                String user = "已安装的应用：" + apps() + "\n"
                        + (lastReply.isEmpty() ? "" : "助手上一句：" + FlashLoop.clip(lastReply, 120) + "\n")
                        + "识别结果：" + heard;
                String fixed = clean(complete(user));
                // Empty or much longer than what was said is not a correction.
                boolean usable = !fixed.isEmpty() && fixed.length() <= heard.length() * 2 + 10;
                boolean changed = usable && !fixed.equals(heard);
                // Never the words, only whether and how fast.
                AppLog.i(AppLog.CALL, "caption " + (changed ? "corrected" : usable ? "unchanged" : "correction unusable")
                        + " in " + (System.currentTimeMillis() - t0) + " ms");
                if (changed) done.accept(fixed);
            } catch (Exception e) {
                AppLog.w(AppLog.CALL, "caption correction failed: " + e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Without thinking: with it, thinking used up all 400 tokens on 6 of 18 sentences (no answer)
     * and 90% of them on the rest ("/no_think" in the prompt did not stop it). A service that
     * rejects the switch gets the request again without it, for the rest of the call.
     */
    private String complete(String user) throws Exception {
        try {
            return client.complete(model, SYSTEM, user, null, null, 400).text;
        } catch (ChatClient.HttpError e) {
            if (e.code != 400 || !client.noThinking()) throw e;
            AppLog.i(AppLog.CALL, "caption fixing: the service rejects the no-thinking switch, retrying without it");
            client.noThinking(false);
            return client.complete(model, SYSTEM, user, null, null, 400).text;
        }
    }

    void close() {
        client.cancel();
        exec.shutdownNow();
    }

    /** Reasoning models may put their thinking in <think> tags; quotes and labels are dropped too. */
    private static String clean(String text) {
        String t = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        int nl = t.lastIndexOf('\n');
        if (nl >= 0) t = t.substring(nl + 1).trim();
        t = t.replaceFirst("^(改正后|纠正后|识别结果)[:：]\\s*", "");
        return t.replaceAll("^[\"“「]+|[\"”」]+$", "").trim();
    }

    /** The launchable apps' names, once per call (the same for every sentence, so the prefix caches). */
    private String apps() {
        if (apps != null) return apps;
        PackageManager pm = ctx.getPackageManager();
        Set<String> names = new LinkedHashSet<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            if (names.size() >= MAX_APPS) break;
            names.add(String.valueOf(ri.loadLabel(pm)).trim());
        }
        apps = String.join("、", names);
        return apps;
    }
}
