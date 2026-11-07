package com.artemis.ondevice;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fixes homophones and typos in an offline speech-recognition result with one short call to
 * the active model. Any failure or timeout keeps the recognized text as it is.
 */
final class TaskCorrector {

    private static final long TIMEOUT_MS = 3000;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final String SYSTEM =
            "你是语音识别纠错器。输入是手机语音助手离线识别出的一句中文指令，可能有同音字、错别字或断句错误。"
                    + "只修正这些错误，不改变意思，不增删内容，不回答问题，也不执行指令。拿不准就原样返回。"
                    + "只输出修正后的这一句话，不要加引号或解释。";

    private TaskCorrector() {}

    static String correct(Context ctx, final String heard) {
        ProviderStore.Active active = ProviderStore.getActive(ctx);
        ProviderStore.Provider p = active == null ? null : ProviderStore.get(ctx, active.provider);
        if (p == null) return heard;
        final String model = active.model;
        final ChatClient client;
        try {
            client = new ChatClient(p, ProviderStore.apiKey(ctx, p.id));
        } catch (Exception e) {
            Log.w(AgentService.TAG, "correction skipped: " + e.getClass().getSimpleName());
            return heard;
        }
        Future<ChatClient.Result> f = EXEC.submit(() -> client.complete(model, SYSTEM, heard, null, null, 128));
        try {
            String fixed = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).text.trim()
                    .replaceAll("^[\"“”'「」]+|[\"“”'「」]+$", "");
            // A reply that is empty or much longer than what was said is not a correction.
            if (fixed.isEmpty() || fixed.length() > heard.length() * 2 + 10) return heard;
            return fixed;
        } catch (Exception e) {
            client.cancel();
            f.cancel(true);
            Log.w(AgentService.TAG, "correction skipped: " + e.getClass().getSimpleName());
            return heard;
        }
    }
}
