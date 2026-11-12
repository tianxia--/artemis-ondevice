package com.artemis.ondevice;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal chat client for the two supported provider API types:
 * {@code openai-completions} (POST {base}/chat/completions) and
 * {@code anthropic-messages} (POST {base}/v1/messages).
 *
 * One request = system prompt + one user message (text, optional JPEG) + tool list.
 * Tools are passed in OpenAI function form and translated for Anthropic.
 */
final class ChatClient {

    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 60_000;
    /**
     * Hard cap on one request, end to end. The read timeout only bounds the gap between two
     * reads, so a half-dead connection (e.g. after the device slept) or a trickling server could
     * otherwise block a run forever; the watchdog closes the connection at this deadline.
     */
    static final long REQUEST_DEADLINE_MS = 60_000;
    /**
     * Retries on 429 / 5xx / IO errors (including a deadline hit), waiting longer each time: a
     * phone's connection drops for seconds at a time, and one blip must not end a run that was
     * going well.
     */
    private static final long[] RETRY_WAIT_MS = {2_000, 5_000, 10_000};
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor();

    static final class ToolCall {
        final String name;
        final JSONObject args;
        ToolCall(String name, JSONObject args) { this.name = name; this.args = args; }
    }

    static final class Result {
        String text = "";
        /** The API returned no usage; the token counts are estimates. */
        boolean estimated;
        /** Why generation stopped as the API reported it ("stop", "length", "end_turn", "max_tokens" ...). */
        String finishReason = "";
        final List<ToolCall> toolCalls = new ArrayList<>();
        int promptTokens;
        int completionTokens;
        long latencyMs;
    }

    static final class HttpError extends IOException {
        final int code;
        HttpError(int code, String body) {
            super("HTTP " + code + ": " + (body.length() > 400 ? body.substring(0, 400) : body));
            this.code = code;
        }
    }

    private final ProviderStore.Provider provider;
    private final String apiKey;
    private volatile HttpURLConnection inFlight;
    private volatile boolean cancelled;

    /** Where calls are counted (see {@link UsageStore}); null for calls that are not counted. */
    private android.content.Context usageCtx;
    private String purpose = UsageStore.TASK;

    /** Counts this client's calls under {@code purpose} and holds them to the daily limit. */
    ChatClient countAs(android.content.Context ctx, String purpose) {
        this.usageCtx = ctx.getApplicationContext();
        this.purpose = purpose;
        return this;
    }

    /** Thrown before a call when today's tokens reached the daily limit. Not retried. */
    static final class LimitReached extends IllegalStateException {
        LimitReached(String m) { super(m); }
    }

    ChatClient(ProviderStore.Provider provider, String apiKey) {
        this.provider = provider;
        this.apiKey = apiKey;
    }

    /**
     * Aborts the request in flight and any later one. Stop uses this: a thread interrupt does not
     * wake a blocked socket read, closing the connection does.
     */
    void cancel() {
        cancelled = true;
        HttpURLConnection c = inFlight;
        if (c != null) c.disconnect();
    }

    Result complete(String model, String system, String userText, byte[] jpeg, JSONArray tools,
                    int maxTokens) throws Exception {
        if (usageCtx != null) {
            long limit = AppSettings.dailyTokenLimit(usageCtx);
            long today = UsageStore.todayTokens(usageCtx);
            if (limit > 0 && today >= limit) {
                throw new LimitReached("Today's token limit is reached (" + today + " of " + limit
                        + "); it resets tomorrow, or raise it in Settings > Usage.");
            }
        }
        boolean anthropic = ProviderStore.API_ANTHROPIC.equals(provider.api);
        JSONObject body = anthropic
                ? anthropicBody(model, system, userText, jpeg, tools, maxTokens)
                : openAiBody(model, system, userText, jpeg, tools, maxTokens);
        String path = anthropic ? "/v1/messages" : "/chat/completions";
        long t0 = System.currentTimeMillis();
        JSONObject resp = post(path, body);
        Result r = anthropic ? parseAnthropic(resp) : parseOpenAi(resp);
        r.latencyMs = System.currentTimeMillis() - t0;
        if (r.promptTokens == 0 && r.completionTokens == 0) {
            // Some OpenAI-compatible servers return no usage; count something rather than nothing.
            StringBuilder out = new StringBuilder(r.text);
            for (ToolCall c : r.toolCalls) out.append(c.name).append(c.args);
            r.promptTokens = UsageStore.estimateTokens(system + userText + (tools == null ? "" : tools.toString()), jpeg != null);
            r.completionTokens = UsageStore.estimateTokens(out.toString(), false);
            r.estimated = true;
        }
        if (usageCtx != null) {
            UsageStore.record(usageCtx, provider.id, model, purpose, r.promptTokens, r.completionTokens, r.estimated);
        }
        return r;
    }

    /** Model ids from the endpoint's /models route (/v1/models for Anthropic). */
    List<String> fetchModels() throws Exception {
        boolean anthropic = ProviderStore.API_ANTHROPIC.equals(provider.api);
        JSONObject resp = request("GET", anthropic ? "/v1/models" : "/models", null);
        List<String> ids = new ArrayList<>();
        JSONArray data = resp.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                String id = data.optJSONObject(i).optString("id");
                if (!id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ OpenAI

    private static JSONObject openAiBody(String model, String system, String userText, byte[] jpeg,
                                         JSONArray tools, int maxTokens) throws Exception {
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", userText));
        if (jpeg != null) {
            content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject()
                    .put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))));
        }
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", content));
        JSONObject body = new JSONObject().put("model", model).put("messages", messages)
                .put("max_tokens", maxTokens).put("temperature", 0.2);
        if (tools != null) body.put("tools", tools).put("tool_choice", "auto");
        return body;
    }

    private static Result parseOpenAi(JSONObject resp) throws Exception {
        Result r = new Result();
        JSONObject choice = resp.getJSONArray("choices").getJSONObject(0);
        r.finishReason = choice.optString("finish_reason", "");
        JSONObject msg = choice.getJSONObject("message");
        r.text = msg.isNull("content") ? "" : msg.optString("content", "");
        JSONArray calls = msg.optJSONArray("tool_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject fn = calls.getJSONObject(i).getJSONObject("function");
                String raw = fn.optString("arguments", "{}");
                r.toolCalls.add(new ToolCall(fn.getString("name"),
                        raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw)));
            }
        }
        JSONObject usage = resp.optJSONObject("usage");
        if (usage != null) {
            r.promptTokens = usage.optInt("prompt_tokens");
            r.completionTokens = usage.optInt("completion_tokens");
        }
        return r;
    }

    // ------------------------------------------------------------------ Anthropic

    private static JSONObject anthropicBody(String model, String system, String userText, byte[] jpeg,
                                            JSONArray tools, int maxTokens) throws Exception {
        JSONArray content = new JSONArray();
        if (jpeg != null) {
            content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))));
        }
        content.put(new JSONObject().put("type", "text").put("text", userText));
        // Same low temperature as the OpenAI path: the same task should take the same path.
        JSONObject body = new JSONObject().put("model", model).put("system", system)
                .put("max_tokens", maxTokens).put("temperature", 0.2)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
        if (tools != null) {
            JSONArray out = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject fn = tools.getJSONObject(i).getJSONObject("function");
                out.put(new JSONObject().put("name", fn.getString("name"))
                        .put("description", fn.optString("description"))
                        .put("input_schema", fn.getJSONObject("parameters")));
            }
            body.put("tools", out);
        }
        return body;
    }

    private static Result parseAnthropic(JSONObject resp) throws Exception {
        Result r = new Result();
        JSONArray blocks = resp.getJSONArray("content");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.getJSONObject(i);
            String type = b.optString("type");
            if ("text".equals(type)) text.append(b.optString("text"));
            else if ("tool_use".equals(type)) {
                r.toolCalls.add(new ToolCall(b.getString("name"), b.optJSONObject("input")));
            }
        }
        r.finishReason = resp.optString("stop_reason", "");
        r.text = text.toString();
        JSONObject usage = resp.optJSONObject("usage");
        if (usage != null) {
            r.promptTokens = usage.optInt("input_tokens");
            r.completionTokens = usage.optInt("output_tokens");
        }
        return r;
    }

    // ------------------------------------------------------------------ HTTP

    private JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body);
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt <= RETRY_WAIT_MS.length; attempt++) {
            if (cancelled) throw new IOException("Cancelled");
            try {
                return once(method, path, body);
            } catch (HttpError e) {
                last = e;
                if (e.code != 429 && e.code < 500) throw e;
            } catch (IOException e) {
                if (cancelled) throw new IOException("Cancelled");
                last = e;
            }
            if (attempt < RETRY_WAIT_MS.length) {
                Log.w(AgentService.TAG, "model request failed (" + last.getClass().getSimpleName() + "); retry "
                        + (attempt + 1) + "/" + RETRY_WAIT_MS.length + " in " + RETRY_WAIT_MS[attempt] / 1000 + "s");
                Thread.sleep(RETRY_WAIT_MS[attempt]);
            }
        }
        throw last;
    }

    private JSONObject once(String method, String path, JSONObject body) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(provider.baseUrl + path).openConnection();
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> deadline = WATCHDOG.schedule(new Runnable() {
            @Override public void run() {
                timedOut.set(true);
                conn.disconnect();
            }
        }, REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS);
        inFlight = conn;
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.isEmpty()) {
                if (ProviderStore.API_ANTHROPIC.equals(provider.api)) {
                    conn.setRequestProperty("x-api-key", apiKey);
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
            }
            if (ProviderStore.API_ANTHROPIC.equals(provider.api)) {
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            }
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) throw new HttpError(code, text);
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                throw new IOException("Non-JSON response: " + (text.length() > 200 ? text.substring(0, 200) : text));
            }
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new IOException("No response within " + REQUEST_DEADLINE_MS / 1000 + "s (request aborted)", e);
            }
            if (e instanceof HttpError) throw e;
            throw hideAddress(e, conn.getURL().getHost());
        } finally {
            deadline.cancel(false);
            inFlight = null;
            conn.disconnect();
        }
    }

    /**
     * Network errors name the server ("Unable to resolve host ...", "Failed to connect to /1.2.3.4:443"),
     * and they reach the screen and the run history; the base URL stays hidden there.
     */
    private static IOException hideAddress(IOException e, String host) {
        String msg = e.getMessage();
        if (msg == null) return e;
        String clean = msg.replace(host, "***").replaceAll("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?", "***");
        return clean.equals(msg) ? e : new IOException(e.getClass().getSimpleName() + ": " + clean);
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = s.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        }
    }
}
