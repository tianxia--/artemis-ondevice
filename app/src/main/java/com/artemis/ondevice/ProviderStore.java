package com.artemis.ondevice;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * On-device model provider registry:
 *
 * <ul>
 *   <li>{@code providers.json} in app-private storage holds the non-secret part:
 *       {@code {"providers":[{id,name,base_url,api,models:[{id,name,image,...}]}],
 *       "active":{provider,model}}}.</li>
 *   <li>API keys go to {@link SecretStore} under {@code provider:<id>}.</li>
 *   <li>Built-in presets and id / base-URL validation.</li>
 * </ul>
 */
final class ProviderStore {

    static final String API_OPENAI = "openai-completions";
    static final String API_ANTHROPIC = "anthropic-messages";
    static final String[] API_TYPES = {API_OPENAI, API_ANTHROPIC};

    private static final String FILE = "providers.json";
    private static final Pattern ID_RE = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final Set<String> BUILTIN_PROVIDER_IDS = new HashSet<>(Arrays.asList(
            "google", "gemini", "vertexai", "vertex", "openai", "anthropic", "claude",
            "openrouter", "xai", "grok", "ollama", "vllm", "custom", "openai-codex", "claude-code"));

    static final class ModelSpec {
        String id;
        String name = "";
        int contextWindow = 128000;
        int maxTokens = 8192;
        boolean reasoning = false;
        boolean image = false;

        ModelSpec(String id) { this.id = id; }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("name", name)
                    .put("context_window", contextWindow).put("max_tokens", maxTokens)
                    .put("reasoning", reasoning).put("image", image);
        }

        static ModelSpec fromJson(JSONObject o) {
            ModelSpec m = new ModelSpec(o.optString("id"));
            m.name = o.optString("name", "");
            m.contextWindow = o.optInt("context_window", 128000);
            m.maxTokens = o.optInt("max_tokens", 8192);
            m.reasoning = o.optBoolean("reasoning", false);
            m.image = o.optBoolean("image", false);
            return m;
        }

        @Override public String toString() { return id; }
    }

    static final class Provider {
        String id;
        String name = "";
        String baseUrl;
        String api = API_OPENAI;
        List<ModelSpec> models = new ArrayList<>();
        /** Optional prices per million input / output tokens, set by the user (0 = unknown). */
        double priceIn, priceOut;
        String currency = "$";

        boolean hasPrice() { return priceIn > 0 || priceOut > 0; }

        /** Estimated cost of these tokens, or NaN when no price is set. */
        double cost(long in, long out) {
            return hasPrice() ? (in * priceIn + out * priceOut) / 1_000_000d : Double.NaN;
        }

        JSONObject toJson() throws Exception {
            JSONArray ms = new JSONArray();
            for (ModelSpec m : models) ms.put(m.toJson());
            return new JSONObject().put("id", id).put("name", name).put("base_url", baseUrl)
                    .put("api", api).put("models", ms)
                    .put("price_in", priceIn).put("price_out", priceOut).put("currency", currency);
        }

        static Provider fromJson(JSONObject o) {
            Provider p = new Provider();
            p.id = o.optString("id");
            p.name = o.optString("name", "");
            p.baseUrl = o.optString("base_url");
            p.api = o.optString("api", API_OPENAI);
            p.priceIn = o.optDouble("price_in", 0);
            p.priceOut = o.optDouble("price_out", 0);
            p.currency = o.optString("currency", "$");
            JSONArray ms = o.optJSONArray("models");
            if (ms != null) {
                for (int i = 0; i < ms.length(); i++) p.models.add(ModelSpec.fromJson(ms.optJSONObject(i)));
            }
            return p;
        }

        ModelSpec model(String modelId) {
            for (ModelSpec m : models) if (m.id.equals(modelId)) return m;
            return null;
        }

        @Override public String toString() {
            return (name.isEmpty() ? id : name + " (" + id + ")");
        }
    }

    /** Active model: what the agent loop uses. */
    static final class Active {
        final String provider;
        final String model;
        Active(String provider, String model) { this.provider = provider; this.model = model; }
    }

    private ProviderStore() {}

    // ------------------------------------------------------------------
    // Presets (label -> provider template)
    // ------------------------------------------------------------------

    static Map<String, Provider> presets() {
        Map<String, Provider> out = new LinkedHashMap<>();
        List<ModelSpec> glm = Arrays.asList(
                spec("glm-5v-turbo", "GLM-5V-Turbo", true),
                spec("glm-5.1", "GLM-5.1", false),
                spec("glm-5-turbo", "GLM-5-Turbo", false),
                spec("glm-4.7", "GLM-4.7", false));
        out.put("智谱 GLM Coding Plan（国内）", preset("zhipu-coding", "智谱 GLM Coding Plan",
                "https://open.bigmodel.cn/api/coding/paas/v4", API_OPENAI, glm));
        out.put("Z.ai GLM Coding Plan（国际）", preset("zai-coding", "Z.ai GLM Coding Plan",
                "https://api.z.ai/api/coding/paas/v4", API_OPENAI, glm));
        out.put("Anthropic API", preset("anthropic-api", "Anthropic API",
                "https://api.anthropic.com", API_ANTHROPIC, Arrays.asList(
                        spec("claude-sonnet-5-5", "Claude Sonnet 5.5", true),
                        spec("claude-haiku-5-5", "Claude Haiku 5.5", true),
                        spec("claude-opus-5-5", "Claude Opus 5.5", true))));
        out.put("Ollama（本地）", preset("ollama-local", "Ollama",
                "http://localhost:11434/v1", API_OPENAI, new ArrayList<ModelSpec>()));
        return out;
    }

    private static ModelSpec spec(String id, String name, boolean image) {
        ModelSpec m = new ModelSpec(id);
        m.name = name;
        m.image = image;
        m.reasoning = true;
        return m;
    }

    private static Provider preset(String id, String name, String url, String api, List<ModelSpec> models) {
        Provider p = new Provider();
        p.id = id;
        p.name = name;
        p.baseUrl = url;
        p.api = api;
        p.models = new ArrayList<>(models);
        return p;
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    static String normalizeId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase();
        if (!ID_RE.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Provider id must be lowercase letters, digits, '-' or '_' (max 64 chars).");
        }
        if (BUILTIN_PROVIDER_IDS.contains(id)) {
            throw new IllegalArgumentException("'" + id + "' is a built-in provider id; choose another id.");
        }
        return id;
    }

    static String normalizeBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }
        return url;
    }

    // ------------------------------------------------------------------
    // Registry file
    // ------------------------------------------------------------------

    static synchronized List<Provider> list(Context ctx) {
        List<Provider> out = new ArrayList<>();
        JSONArray arr = load(ctx).optJSONArray("providers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) out.add(Provider.fromJson(arr.optJSONObject(i)));
        }
        return out;
    }

    static Provider get(Context ctx, String id) {
        for (Provider p : list(ctx)) if (p.id.equals(id)) return p;
        return null;
    }

    /** Insert or replace by id; stores the API key when one is given (blank keeps the old key). */
    static synchronized void upsert(Context ctx, Provider provider, String apiKey) throws Exception {
        JSONObject data = load(ctx);
        JSONArray kept = new JSONArray();
        for (Provider p : list(ctx)) if (!p.id.equals(provider.id)) kept.put(p.toJson());
        kept.put(provider.toJson());
        data.put("providers", kept);
        save(ctx, data);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            SecretStore.set(ctx, SecretStore.apiKeyRef(provider.id), apiKey.trim());
        }
    }

    static synchronized void remove(Context ctx, String id) throws Exception {
        JSONObject data = load(ctx);
        JSONArray kept = new JSONArray();
        for (Provider p : list(ctx)) if (!p.id.equals(id)) kept.put(p.toJson());
        data.put("providers", kept);
        Active a = getActive(ctx);
        if (a != null && a.provider.equals(id)) data.remove("active");
        save(ctx, data);
        SecretStore.delete(ctx, SecretStore.apiKeyRef(id));
    }

    static synchronized Active getActive(Context ctx) {
        JSONObject a = load(ctx).optJSONObject("active");
        if (a == null) return null;
        return new Active(a.optString("provider"), a.optString("model"));
    }

    static synchronized void setActive(Context ctx, String provider, String model) throws Exception {
        JSONObject data = load(ctx);
        if (provider == null || model == null || model.isEmpty()) {
            data.remove("active");
        } else {
            data.put("active", new JSONObject().put("provider", provider).put("model", model));
        }
        save(ctx, data);
    }

    static String apiKey(Context ctx, String providerId) throws Exception {
        return SecretStore.get(ctx, SecretStore.apiKeyRef(providerId));
    }

    static boolean hasApiKey(Context ctx, String providerId) {
        return SecretStore.has(ctx, SecretStore.apiKeyRef(providerId));
    }

    private static JSONObject load(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return new JSONObject();
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            // A broken file must not silently drop the user's endpoints.
            throw new IllegalStateException("Invalid provider registry " + f + ": " + e.getMessage(), e);
        }
    }

    private static void save(Context ctx, JSONObject data) throws Exception {
        File f = new File(ctx.getFilesDir(), FILE);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("Could not write " + f);
    }
}
