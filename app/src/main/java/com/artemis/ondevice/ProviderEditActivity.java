package com.artemis.ondevice;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Add or edit a model service: preset,
 * id, name, base URL, API type, API key (Keystore-encrypted, never shown again) and models.
 */
public class ProviderEditActivity extends AppCompatActivity {

    static final String EXTRA_PROVIDER_ID = "provider_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ProviderStore.Provider existing;
    private final List<ProviderStore.ModelSpec> models = new ArrayList<>();

    private TextInputEditText id, name, url, key, modelInput;
    private MaterialAutoCompleteTextView api;
    private ChipGroup modelChips;
    private TextView testResult;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_provider_edit);
        String editId = getIntent().getStringExtra(EXTRA_PROVIDER_ID);
        existing = editId == null ? null : ProviderStore.get(this, editId);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(existing == null ? R.string.provider_add_title : R.string.provider_edit_title);
        toolbar.setNavigationOnClickListener(v -> finish());

        id = findViewById(R.id.provider_id);
        name = findViewById(R.id.provider_name);
        url = findViewById(R.id.base_url);
        key = findViewById(R.id.api_key);
        api = findViewById(R.id.api_type);
        modelChips = findViewById(R.id.model_chips);
        modelInput = findViewById(R.id.model_input);
        testResult = findViewById(R.id.test_result);
        api.setSimpleItems(ProviderStore.API_TYPES);
        api.setText(ProviderStore.API_OPENAI, false);

        setupPresets();
        if (existing != null) {
            findViewById(R.id.preset_layout).setVisibility(View.GONE);
            id.setText(existing.id);
            id.setEnabled(false);
            name.setText(existing.name);
            url.setText(existing.baseUrl);
            api.setText(existing.api, false);
            models.addAll(existing.models);
            if (ProviderStore.hasApiKey(this, existing.id)) {
                ((TextInputLayout) findViewById(R.id.key_layout)).setHint(R.string.api_key_keep);
            }
            View delete = findViewById(R.id.delete);
            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(v -> confirmDelete());
        }
        renderModels();

        // Some keyboards (Gboard) hide clipboard paste in password fields; read the clipboard directly.
        findViewById(R.id.paste_key).setOnClickListener(v -> {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
            if (text == null || text.toString().trim().isEmpty()) {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            key.setText(text.toString().trim());
            key.setSelection(key.length());
        });
        modelInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            addModel(String.valueOf(modelInput.getText()));
            modelInput.setText("");
            return true;
        });
        findViewById(R.id.fetch_models).setOnClickListener(v -> fetchModels());
        findViewById(R.id.test).setOnClickListener(v -> test());
        findViewById(R.id.save).setOnClickListener(v -> save());
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private void setupPresets() {
        final Map<String, ProviderStore.Provider> presets = new LinkedHashMap<>();
        presets.put(getString(R.string.preset_custom), null);
        presets.putAll(ProviderStore.presets());
        MaterialAutoCompleteTextView preset = findViewById(R.id.preset);
        preset.setSimpleItems(presets.keySet().toArray(new String[0]));
        preset.setText(getString(R.string.preset_custom), false);
        preset.setOnItemClickListener((parent, view, position, rowId) -> {
            ProviderStore.Provider p = presets.get(String.valueOf(parent.getItemAtPosition(position)));
            if (p == null) return;
            id.setText(p.id);
            name.setText(p.name);
            url.setText(p.baseUrl);
            api.setText(p.api, false);
            models.clear();
            models.addAll(p.models);
            renderModels();
        });
    }

    // ------------------------------------------------------------------ models

    private void addModel(String raw) {
        String m = raw.trim();
        if (m.isEmpty()) return;
        for (ProviderStore.ModelSpec s : models) if (s.id.equals(m)) return;
        models.add(new ProviderStore.ModelSpec(m));
        renderModels();
    }

    private void renderModels() {
        modelChips.removeAllViews();
        for (final ProviderStore.ModelSpec m : models) {
            Chip c = new Chip(this);
            c.setText(m.id);
            c.setCloseIconVisible(true);
            c.setOnCloseIconClickListener(v -> {
                models.remove(m);
                renderModels();
            });
            modelChips.addView(c);
        }
    }

    // ------------------------------------------------------------------ form

    /** The provider described by the form; throws IllegalArgumentException with a user-facing message. */
    private ProviderStore.Provider fromForm() {
        ProviderStore.Provider p = new ProviderStore.Provider();
        p.id = ProviderStore.normalizeId(String.valueOf(id.getText()));
        p.name = String.valueOf(name.getText()).trim();
        p.baseUrl = ProviderStore.normalizeBaseUrl(String.valueOf(url.getText()));
        p.api = String.valueOf(api.getText());
        p.models = new ArrayList<>(models);
        return p;
    }

    /** Typed key, else the stored one for an existing provider. */
    private String keyFor(ProviderStore.Provider p) throws Exception {
        String typed = String.valueOf(key.getText()).trim();
        if (!typed.isEmpty()) return typed;
        return existing != null ? ProviderStore.apiKey(this, existing.id) : null;
    }

    private void save() {
        try {
            ProviderStore.Provider p = fromForm();
            if (p.models.isEmpty()) {
                Toast.makeText(this, R.string.need_model, Toast.LENGTH_SHORT).show();
                return;
            }
            ProviderStore.upsert(this, p, String.valueOf(key.getText()));
            ProviderStore.Active a = ProviderStore.getActive(this);
            if (a == null || (a.provider.equals(p.id) && p.model(a.model) == null)) {
                ProviderStore.setActive(this, p.id, p.models.get(0).id);
            }
            finish();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.confirm_delete_provider, existing.id))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    try {
                        ProviderStore.remove(this, existing.id);
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void fetchModels() {
        final ProviderStore.Provider p;
        try {
            p = fromForm();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.fetching, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String msg;
            List<String> ids = null;
            try {
                ids = new ChatClient(p, keyFor(p)).fetchModels();
                msg = getString(R.string.fetched_models, ids.size());
            } catch (Exception e) {
                msg = getString(R.string.test_failed, e.getMessage());
            }
            final List<String> got = ids;
            final String out = msg;
            main.post(() -> {
                if (isDestroyed()) return;
                if (got != null) for (String m : got) addModel(m);
                Toast.makeText(this, out, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void test() {
        final ProviderStore.Provider p;
        try {
            p = fromForm();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (p.models.isEmpty()) {
            Toast.makeText(this, R.string.need_model, Toast.LENGTH_SHORT).show();
            return;
        }
        final String model = p.models.get(0).id;
        testResult.setVisibility(View.VISIBLE);
        testResult.setTextColor(getColor(R.color.text_secondary));
        testResult.setText(R.string.testing);
        io.execute(() -> {
            String msg;
            boolean ok;
            try {
                ChatClient.Result r = new ChatClient(p, keyFor(p)).complete(model, "You are a connectivity probe.",
                        "Reply with the single word: pong", null, null, 64);
                msg = getString(R.string.test_ok, model, (int) r.latencyMs);
                ok = true;
            } catch (Exception e) {
                msg = getString(R.string.test_failed, e.getMessage());
                ok = false;
            }
            final String out = msg;
            final boolean success = ok;
            main.post(() -> {
                if (isDestroyed()) return;
                testResult.setText(out);
                testResult.setTextColor(getColor(success ? R.color.success : R.color.danger));
            });
        });
    }
}
