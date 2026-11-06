package com.artemis.ondevice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Task box, provider / model selection, Start/Stop
 * and the live step log. Views are built in code to keep the module resource-free.
 */
public class MainActivity extends Activity implements RunLog.Listener {

    private TextView a11yStatus;
    private Spinner providerSpinner;
    private Spinner modelSpinner;
    private EditText taskInput;
    private EditText maxStepsInput;
    private CheckBox screenshotBox;
    private Button startStop;
    private TextView logView;
    private ScrollView logScroll;
    private boolean suppressSelection;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // targetSdk 35 is edge-to-edge: pad by the system bars so nothing hides under them.
        final int basePad = pad;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                android.graphics.Insets bars = in.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                v.setPadding(basePad, basePad + bars.top, basePad, basePad + bars.bottom);
                return in;
            }
        });

        a11yStatus = new TextView(this);
        Button enableA11y = button("Accessibility settings", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(row(a11yStatus, enableA11y));

        providerSpinner = new Spinner(this);
        modelSpinner = new Spinner(this);
        root.addView(label("Provider"));
        root.addView(providerSpinner);
        root.addView(label("Model"));
        root.addView(modelSpinner);
        root.addView(row(
                button("Add provider", new View.OnClickListener() {
                    @Override public void onClick(View v) { editProvider(null); }
                }),
                button("Edit", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ProviderStore.Provider p = selectedProvider();
                        if (p != null) editProvider(p);
                    }
                }),
                button("Delete", new View.OnClickListener() {
                    @Override public void onClick(View v) { deleteProvider(); }
                })));
        root.addView(row(
                button("Fetch models", new View.OnClickListener() {
                    @Override public void onClick(View v) { fetchModels(); }
                }),
                button("Test", new View.OnClickListener() {
                    @Override public void onClick(View v) { testConnection(); }
                })));

        root.addView(label("Task"));
        taskInput = new EditText(this);
        taskInput.setHint("e.g. Open Settings and tell me the battery level");
        taskInput.setMinLines(2);
        root.addView(taskInput);

        maxStepsInput = new EditText(this);
        maxStepsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxStepsInput.setText("20");
        screenshotBox = new CheckBox(this);
        screenshotBox.setText("Screenshot");
        screenshotBox.setChecked(true);
        startStop = button("Start", new View.OnClickListener() {
            @Override public void onClick(View v) { startOrStop(); }
        });
        TextView stepsLabel = label("Max steps");
        root.addView(row(stepsLabel, maxStepsInput, screenshotBox, startStop));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!suppressSelection) refreshModels(null);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressSelection) return;
                ProviderStore.Provider p = selectedProvider();
                Object m = modelSpinner.getSelectedItem();
                if (p != null && m != null) setActive(p.id, m.toString());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        String task = getIntent().getStringExtra(AgentService.EXTRA_TASK);
        if (task != null) taskInput.setText(task);
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        refreshProviders();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String task = intent.getStringExtra(AgentService.EXTRA_TASK);
        if (task != null) taskInput.setText(task);
    }

    @Override
    protected void onResume() {
        super.onResume();
        a11yStatus.setText(AgentAccessibilityService.get() != null
                ? "Accessibility: enabled" : "Accessibility: OFF — enable \"Artemis On-Device\"");
        RunLog.setListener(this);
    }

    @Override
    protected void onPause() {
        RunLog.setListener(null);
        super.onPause();
    }

    @Override
    public void onLogChanged(String fullText, boolean running) {
        logView.setText(fullText);
        startStop.setText(running ? "Stop" : "Start");
        logScroll.post(new Runnable() {
            @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    // ------------------------------------------------------------------ run

    private void startOrStop() {
        if (RunLog.isRunning()) {
            AgentService.stop(this);
            return;
        }
        String task = taskInput.getText().toString().trim();
        if (task.isEmpty()) { toast("Enter a task"); return; }
        if (AgentAccessibilityService.get() == null) { toast("Enable the Artemis On-Device accessibility service first"); return; }
        if (ProviderStore.getActive(this) == null) { toast("Add a provider and pick a model first"); return; }
        int maxSteps;
        try {
            maxSteps = Math.max(1, Math.min(100, Integer.parseInt(maxStepsInput.getText().toString().trim())));
        } catch (NumberFormatException e) {
            maxSteps = 20;
        }
        AgentService.start(this, task, maxSteps, screenshotBox.isChecked());
    }

    // ------------------------------------------------------------------ providers

    private ProviderStore.Provider selectedProvider() {
        Object p = providerSpinner.getSelectedItem();
        return p instanceof ProviderStore.Provider ? (ProviderStore.Provider) p : null;
    }

    private void refreshProviders() {
        List<ProviderStore.Provider> providers;
        try {
            providers = ProviderStore.list(this);
        } catch (IllegalStateException e) {
            toast(e.getMessage());
            providers = new ArrayList<>();
        }
        ProviderStore.Active active = ProviderStore.getActive(this);
        suppressSelection = true;
        providerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, providers));
        for (int i = 0; i < providers.size(); i++) {
            if (active != null && providers.get(i).id.equals(active.provider)) providerSpinner.setSelection(i);
        }
        suppressSelection = false;
        refreshModels(active);
    }

    private void refreshModels(ProviderStore.Active active) {
        ProviderStore.Provider p = selectedProvider();
        List<String> ids = new ArrayList<>();
        if (p != null) for (ProviderStore.ModelSpec m : p.models) ids.add(m.id);
        if (active == null) active = ProviderStore.getActive(this);
        suppressSelection = true;
        modelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ids));
        int sel = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (active != null && p != null && p.id.equals(active.provider) && ids.get(i).equals(active.model)) sel = i;
        }
        if (!ids.isEmpty()) modelSpinner.setSelection(sel);
        suppressSelection = false;
        if (p != null && !ids.isEmpty()) setActive(p.id, ids.get(sel));
    }

    private void setActive(String provider, String model) {
        try {
            ProviderStore.setActive(this, provider, model);
        } catch (Exception e) {
            toast("Could not save: " + e.getMessage());
        }
    }

    private void editProvider(final ProviderStore.Provider existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), 0);

        final Map<String, ProviderStore.Provider> presets = ProviderStore.presets();
        final List<String> presetLabels = new ArrayList<>();
        presetLabels.add("Custom endpoint");
        presetLabels.addAll(presets.keySet());
        final Spinner presetSpinner = new Spinner(this);
        presetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presetLabels));
        final EditText id = field(form, "Provider id (e.g. my-llm)", false);
        final EditText name = field(form, "Display name", false);
        final EditText url = field(form, "Base URL (e.g. https://example.com/v1)", false);
        final Spinner api = new Spinner(this);
        api.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ProviderStore.API_TYPES));
        final EditText key = field(form, existing != null && ProviderStore.hasApiKey(this, existing.id)
                ? "API key (stored; leave blank to keep)" : "API key", true);
        final EditText models = field(form, "Models, comma-separated (or Fetch later)", false);
        form.addView(api, 3);
        if (existing == null) form.addView(presetSpinner, 0);

        if (existing != null) {
            id.setText(existing.id);
            id.setEnabled(false);
            name.setText(existing.name);
            url.setText(existing.baseUrl);
            api.setSelection(ProviderStore.API_ANTHROPIC.equals(existing.api) ? 1 : 0);
            models.setText(joinIds(existing.models));
        }
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) {
                if (position == 0) return;
                ProviderStore.Provider p = presets.get(presetLabels.get(position));
                id.setText(p.id);
                name.setText(p.name);
                url.setText(p.baseUrl);
                api.setSelection(ProviderStore.API_ANTHROPIC.equals(p.api) ? 1 : 0);
                models.setText(joinIds(p.models));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add provider" : "Edit provider")
                .setView(wrapScroll(form))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            ProviderStore.Provider p = new ProviderStore.Provider();
                            p.id = ProviderStore.normalizeId(id.getText().toString());
                            p.name = name.getText().toString().trim();
                            p.baseUrl = ProviderStore.normalizeBaseUrl(url.getText().toString());
                            p.api = api.getSelectedItem().toString();
                            for (String m : models.getText().toString().split(",")) {
                                String mid = m.trim();
                                if (mid.isEmpty()) continue;
                                ProviderStore.ModelSpec known = existing != null ? existing.model(mid) : null;
                                p.models.add(known != null ? known : new ProviderStore.ModelSpec(mid));
                            }
                            ProviderStore.upsert(MainActivity.this, p, key.getText().toString());
                            if (!p.models.isEmpty() && ProviderStore.getActive(MainActivity.this) == null) {
                                ProviderStore.setActive(MainActivity.this, p.id, p.models.get(0).id);
                            }
                            dialog.dismiss();
                            refreshProviders();
                        } catch (Exception e) {
                            toast(e.getMessage());
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    private void deleteProvider() {
        final ProviderStore.Provider p = selectedProvider();
        if (p == null) return;
        new AlertDialog.Builder(this)
                .setMessage("Delete provider " + p.id + " and its stored API key?")
                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            ProviderStore.remove(MainActivity.this, p.id);
                        } catch (Exception e) {
                            toast(e.getMessage());
                        }
                        refreshProviders();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fetchModels() {
        final ProviderStore.Provider p = selectedProvider();
        if (p == null) { toast("Add a provider first"); return; }
        background("Fetching models…", new Job() {
            @Override public String run() throws Exception {
                List<String> ids = new ChatClient(p, ProviderStore.apiKey(MainActivity.this, p.id)).fetchModels();
                List<ProviderStore.ModelSpec> merged = new ArrayList<>();
                for (String mid : ids) {
                    ProviderStore.ModelSpec known = p.model(mid);
                    merged.add(known != null ? known : new ProviderStore.ModelSpec(mid));
                }
                p.models = merged;
                ProviderStore.upsert(MainActivity.this, p, null);
                return ids.size() + " models";
            }
        });
    }

    private void testConnection() {
        final ProviderStore.Provider p = selectedProvider();
        final Object m = modelSpinner.getSelectedItem();
        if (p == null || m == null) { toast("Pick a provider and model first"); return; }
        background("Testing…", new Job() {
            @Override public String run() throws Exception {
                ChatClient.Result r = new ChatClient(p, ProviderStore.apiKey(MainActivity.this, p.id))
                        .complete(m.toString(), "You are a connectivity probe.",
                                "Reply with the single word: pong", null, null, 64);
                return m + ": " + r.text.trim() + " (" + r.latencyMs + " ms)";
            }
        });
    }

    private interface Job {
        String run() throws Exception;
    }

    private void background(String startMsg, final Job job) {
        toast(startMsg);
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    msg = job.run();
                } catch (Exception e) {
                    msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String out = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        RunLog.line(out);
                        refreshProviders();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ view helpers

    private static String joinIds(List<ProviderStore.ModelSpec> ms) {
        StringBuilder sb = new StringBuilder();
        for (ProviderStore.ModelSpec m : ms) sb.append(sb.length() > 0 ? "," : "").append(m.id);
        return sb.toString();
    }

    private EditText field(LinearLayout parent, String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e);
        return e;
    }

    private ScrollView wrapScroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(6), dp(6), 0);
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
