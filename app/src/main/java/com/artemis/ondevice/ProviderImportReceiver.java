package com.artemis.ondevice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Adds a provider from adb, the scripted form of "add model service":
 *
 * <pre>
 * adb shell am broadcast -a com.artemis.ondevice.IMPORT_PROVIDER -n com.artemis.ondevice/.ProviderImportReceiver \
 *   --es id my-llm --es name "My LLM" --es base_url https://example.com/v1 --es api openai-completions \
 *   --es models "model-a,model-b" --es active_model model-a [--es api_key ...]
 * </pre>
 *
 * Guarded by WRITE_SECURE_SETTINGS in the manifest, so only adb shell / system can send it.
 * The key is never logged.
 */
public class ProviderImportReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            ProviderStore.Provider p = new ProviderStore.Provider();
            p.id = ProviderStore.normalizeId(intent.getStringExtra("id"));
            p.name = nz(intent.getStringExtra("name"));
            p.baseUrl = ProviderStore.normalizeBaseUrl(intent.getStringExtra("base_url"));
            String api = intent.getStringExtra("api");
            p.api = api == null || api.isEmpty() ? ProviderStore.API_OPENAI : api;
            if (!p.api.equals(ProviderStore.API_OPENAI) && !p.api.equals(ProviderStore.API_ANTHROPIC)) {
                throw new IllegalArgumentException("api must be openai-completions or anthropic-messages");
            }
            for (String m : nz(intent.getStringExtra("models")).split(",")) {
                if (!m.trim().isEmpty()) p.models.add(new ProviderStore.ModelSpec(m.trim()));
            }
            ProviderStore.upsert(ctx, p, intent.getStringExtra("api_key"));
            String active = intent.getStringExtra("active_model");
            if (active != null && !active.isEmpty()) ProviderStore.setActive(ctx, p.id, active);
            Log.i(AgentService.TAG, "Imported provider " + p.id + " (" + p.models.size() + " models)");
            setResultCode(1);
        } catch (Exception e) {
            Log.w(AgentService.TAG, "Provider import failed: " + e.getMessage());
            setResultCode(0);
            setResultData(e.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
