package com.artemis.ondevice;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One short call to the active model after offline speech recognition: fix homophones and
 * typos, and say whether the task does something that cannot be undone (then the user is
 * asked to confirm). Any failure or timeout keeps the recognized text and asks to confirm.
 */
final class TaskCorrector {

    static final class Result {
        final String task;
        final boolean confirm;
        Result(String task, boolean confirm) { this.task = task; this.confirm = confirm; }
    }

    private static final long TIMEOUT_MS = 3000;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final String SYSTEM =
            "你是手机语音助手的识别纠错器。输入是离线语音识别出的中文指令，可能有同音字、错别字或断句错误，"
                    + "也可能是几段话拼在一起（后一段是对前一段的补充或修改，请合并成一条完整指令）。"
                    + "只修正这些问题，不改变意思，不添加用户没说的内容，不回答问题，也不执行指令。拿不准就保留原文。\n"
                    + "只输出一行 JSON：{\"task\": \"修正后的指令\", \"confirm\": true 或 false}。"
                    + "confirm 为 true 表示执行后无法撤回，需要用户先确认：发送消息或邮件、打电话、付款或转账、购买下单、"
                    + "删除、发布或评论、修改账号或系统设置。只是打开、查看、搜索、阅读的指令为 false。";

    private TaskCorrector() {}

    static Result correct(Context ctx, final String heard) {
        Result fallback = new Result(heard, true);
        ProviderStore.Active active = ProviderStore.getActive(ctx);
        ProviderStore.Provider p = active == null ? null : ProviderStore.get(ctx, active.provider);
        if (p == null) return fallback;
        final String model = active.model;
        final ChatClient client;
        try {
            client = new ChatClient(p, ProviderStore.apiKey(ctx, p.id));
        } catch (Exception e) {
            Log.w(AgentService.TAG, "correction skipped: " + e.getClass().getSimpleName());
            return fallback;
        }
        Future<ChatClient.Result> f = EXEC.submit(() -> client.complete(model, SYSTEM, heard, null, null, 160));
        try {
            String text = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).text.trim();
            JSONObject o = new JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
            String task = o.optString("task", "").trim();
            // A reply that is empty or much longer than what was said is not a correction.
            if (task.isEmpty() || task.length() > heard.length() * 2 + 10) return fallback;
            return new Result(task, o.optBoolean("confirm", true));
        } catch (Exception e) {
            client.cancel();
            f.cancel(true);
            Log.w(AgentService.TAG, "correction skipped: " + e.getClass().getSimpleName());
            return fallback;
        }
    }
}
