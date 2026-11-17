package com.artemis.ondevice;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/** Model services, the active model, run options, permissions and data. */
public class SettingsActivity extends AppCompatActivity {

    private MaterialAutoCompleteTextView providerPicker, modelPicker;
    private LinearLayout providerList, skillList;
    private TextView a11yState;
    private List<ProviderStore.Provider> providers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        providerPicker = findViewById(R.id.active_provider);
        modelPicker = findViewById(R.id.active_model);
        providerList = findViewById(R.id.providers);
        skillList = findViewById(R.id.skills);
        a11yState = findViewById(R.id.a11y_state);

        findViewById(R.id.open_usage).setOnClickListener(
                v -> startActivity(new Intent(this, UsageActivity.class)));
        findViewById(R.id.add_provider).setOnClickListener(
                v -> startActivity(new Intent(this, ProviderEditActivity.class)));
        findViewById(R.id.a11y_row).setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.export_logs).setOnClickListener(v -> new Thread(() -> {
            try {
                java.io.File zip = LogExport.build(this);
                runOnUiThread(() -> LogExport.share(this, zip));
            } catch (Exception e) {
                AppLog.w(AppLog.APP, "log export failed", e);
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.export_logs_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        }, "artemis-export").start());
        findViewById(R.id.clear_history).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.confirm_clear_history)
                .setPositiveButton(R.string.action_clear, (d, w) -> new Thread(() -> RunStore.clear(this)).start())
                .setNegativeButton(R.string.cancel, null)
                .show());

        providerPicker.setOnItemClickListener((parent, view, position, id) -> {
            ProviderStore.Provider p = chatProviders().get(position);
            List<ProviderStore.ModelSpec> chat = p.models(false);
            String model = chat.isEmpty() ? null : chat.get(0).id;
            setActive(p.id, model);
            render();
        });
        modelPicker.setOnItemClickListener((parent, view, position, id) -> {
            ProviderStore.Active a = ProviderStore.getActive(this);
            if (a != null) setActive(a.provider, String.valueOf(parent.getItemAtPosition(position)));
            render();
        });

        TextInputEditText maxSteps = findViewById(R.id.max_steps);
        maxSteps.setText(String.valueOf(AppSettings.maxSteps(this)));
        maxSteps.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    AppSettings.setMaxSteps(SettingsActivity.this, s.length() == 0 ? 0 : Integer.parseInt(s.toString()));
                } catch (NumberFormatException ignored) {
                    // maxLength=4 + inputType=number: only digits reach here.
                }
            }
        });

        MaterialSwitch shot = findViewById(R.id.switch_screenshot);
        shot.setChecked(AppSettings.screenshot(this));
        shot.setOnCheckedChangeListener((b, on) -> AppSettings.setScreenshot(this, on));

        MaterialSwitch bubble = findViewById(R.id.switch_bubble);
        bubble.setChecked(AppSettings.bubble(this));
        bubble.setOnCheckedChangeListener((b, on) -> {
            AppSettings.setBubble(this, on);
            AgentAccessibilityService s = AgentAccessibilityService.get();
            if (s != null) s.applyBubbleSetting();
        });

        setupLanguage();
        setupWakeWord();
        setupMcp();
        setupLessons();

        MaterialSwitch search = findViewById(R.id.switch_web_search);
        search.setChecked(AppSettings.webSearch(this));
        search.setOnCheckedChangeListener((b, on) -> AppSettings.setWebSearch(this, on));

        MaterialSwitch confirm = findViewById(R.id.switch_confirm);
        confirm.setChecked(AppSettings.confirmSensitive(this));
        confirm.setOnCheckedChangeListener((b, on) -> AppSettings.setConfirmSensitive(this, on));

        MaterialSwitch voice = findViewById(R.id.switch_voice);
        voice.setChecked(VoiceService.canRun(this));
        ActivityResultLauncher<String> micPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        VoiceService.startIfEnabled(this);
                    } else {
                        AppSettings.setVoice(this, false);
                        voice.setChecked(false);
                        Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show();
                    }
                });
        voice.setOnCheckedChangeListener((b, on) -> {
            if (on == VoiceService.canRun(this)) return;
            AppSettings.setVoice(this, on);
            if (!on) {
                VoiceService.stop(this);
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                VoiceService.startIfEnabled(this);
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO);
            }
        });
    }

    // ------------------------------------------------------------------ wake word and voiceprint

    private void setupWakeWord() {
        ((TextView) findViewById(R.id.wake_word_hint)).setText(getString(R.string.wake_word_hint,
                WakeWord.MIN_CHARS, WakeWord.MAX_CHARS));
        TextInputEditText word = findViewById(R.id.wake_word);
        com.google.android.material.textfield.TextInputLayout layout = findViewById(R.id.wake_word_layout);
        word.setText(AppSettings.wakeWord(this));
        word.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            String phrase = String.valueOf(word.getText()).trim();
            try {
                WakeWord.keywordLine(this, phrase); // throws with a message when unusable
                AppSettings.setWakeWord(this, phrase);
                layout.setError(null);
                Toast.makeText(this, getString(R.string.wake_word_saved, phrase), Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) {
                layout.setError(e.getMessage());
            }
            return false; // let the keyboard close
        });

        MaterialSwitch high = findViewById(R.id.switch_wake_high);
        high.setChecked(AppSettings.WAKE_HIGH.equals(AppSettings.wakeSensitivity(this)));
        high.setOnCheckedChangeListener((b, on) -> AppSettings.setWakeSensitivity(this,
                on ? AppSettings.WAKE_HIGH : AppSettings.WAKE_NORMAL));
        findViewById(R.id.background_battery).setOnClickListener(v -> openBatterySettings());
        com.google.android.material.button.MaterialButton wakeTest = findViewById(R.id.wake_test);
        wakeTest.setText(VoiceService.testWanted ? R.string.wake_test_stop : R.string.wake_test_start);
        wakeTest.setOnClickListener(v -> {
            if (!VoiceService.canRun(this)) {
                Toast.makeText(this, R.string.wake_test_needs_voice, Toast.LENGTH_LONG).show();
                return;
            }
            boolean start = !VoiceService.testWanted;
            VoiceService.lastTest = null;
            VoiceService.testWanted = start;
            AppLog.i(AppLog.SETTINGS, "wake test " + (start ? "start" : "stop") + " requested");
            wakeTest.setText(start ? R.string.wake_test_stop : R.string.wake_test_start);
            if (start) {
                Toast.makeText(this, R.string.wake_test_started, Toast.LENGTH_LONG).show();
            } else {
                // The voice service settles the last attempt on its next read.
                wakeTest.postDelayed(this::showWakeTestResult, 1_500);
            }
        });
        findViewById(R.id.background_startup).setOnClickListener(v -> openStartupSettings());

        findViewById(R.id.voiceprint_enroll).setOnClickListener(v -> {
            if (!Voiceprint.available(getAssets())) {
                Toast.makeText(this, R.string.voiceprint_no_model, Toast.LENGTH_LONG).show();
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            } else {
                VoiceprintDialog.show(this, this::renderVoiceprint);
            }
        });
        findViewById(R.id.voiceprint_delete).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.confirm_delete_voiceprint)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    Voiceprint.delete(this);
                    renderVoiceprint();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        renderVoiceprint();
    }

    private void showWakeTestResult() {
        WakeTest t = VoiceService.lastTest;
        if (t == null) {
            Toast.makeText(this, R.string.wake_test_no_result, Toast.LENGTH_LONG).show();
            return;
        }
        int rate = t.attempts == 0 ? 0 : Math.round(100f * t.woken() / t.attempts);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.wake_test_result_title, rate))
                .setMessage(getString(R.string.wake_test_result, t.attempts, t.woken(), t.bySpotter, t.byRecognizer,
                        t.byBoth, t.missedClose + t.missedFar, t.missedClose, t.missedFar, t.spottedWithoutSpeech))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Battery optimization makes the system stop voice wake-up in the background. */
    private void renderBackground() {
        boolean ignoring = getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        TextView state = findViewById(R.id.background_state);
        state.setText(ignoring ? R.string.background_ok : R.string.background_needed);
        state.setTextColor(getColor(ignoring ? R.color.success : R.color.danger));
        findViewById(R.id.background_battery).setVisibility(ignoring ? View.GONE : View.VISIBLE);
    }

    private void openBatterySettings() {
        AppLog.i(AppLog.SETTINGS, "opening battery optimization settings");
        startFirst(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    /** Huawei's and Honor's app launch manager where they exist, otherwise the app's info page. */
    private void openStartupSettings() {
        AppLog.i(AppLog.SETTINGS, "opening background settings (" + Build.MANUFACTURER + ")");
        startFirst(new Intent().setComponent(new android.content.ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                new Intent().setComponent(new android.content.ComponentName("com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())));
    }

    private void startFirst(Intent... intents) {
        for (Intent i : intents) {
            try {
                startActivity(i);
                return;
            } catch (RuntimeException e) {
                AppLog.i(AppLog.SETTINGS, "settings page not available: " + e.getClass().getSimpleName());
            }
        }
    }

    private void renderVoiceprint() {
        boolean on = Voiceprint.enrolled(this);
        ((TextView) findViewById(R.id.voiceprint_state)).setText(on ? R.string.voiceprint_on : R.string.voiceprint_none);
        ((com.google.android.material.button.MaterialButton) findViewById(R.id.voiceprint_enroll))
                .setText(on ? R.string.voiceprint_reenroll : R.string.voiceprint_enroll);
        findViewById(R.id.voiceprint_delete).setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** Language tags in picker order; "" follows the system language. */
    private static final String[] LANGUAGE_TAGS = {"", "zh-CN", "en"};

    private void setupLanguage() {
        String[] labels = {getString(R.string.language_system), getString(R.string.language_zh),
                getString(R.string.language_en)};
        String current = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        int selected = 0;
        for (int i = 1; i < LANGUAGE_TAGS.length; i++) {
            if (current.startsWith(LANGUAGE_TAGS[i].substring(0, 2))) selected = i;
        }
        MaterialAutoCompleteTextView picker = findViewById(R.id.language);
        picker.setSimpleItems(labels);
        picker.setText(labels[selected], false);
        // Recreates the open activities in the new language.
        // Cards and the call panel pick the language up when shown; notifications when services restart.
        picker.setOnItemClickListener((parent, view, position, id) -> AppLocale.set(this, LANGUAGE_TAGS[position]));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void setActive(String provider, String model) {
        try {
            ProviderStore.setActive(this, provider, model);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void render() {
        try {
            providers = ProviderStore.list(this);
        } catch (IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            providers = new ArrayList<>();
        }
        ProviderStore.Active active = ProviderStore.getActive(this);

        List<String> names = new ArrayList<>();
        for (ProviderStore.Provider p : chatProviders()) names.add(p.toString());
        providerPicker.setSimpleItems(names.toArray(new String[0]));
        ProviderStore.Provider activeP = active == null ? null : ProviderStore.get(this, active.provider);
        providerPicker.setText(activeP == null ? "" : activeP.toString(), false);
        List<String> models = new ArrayList<>();
        if (activeP != null) for (ProviderStore.ModelSpec m : activeP.models(false)) models.add(m.id);
        modelPicker.setSimpleItems(models.toArray(new String[0]));
        modelPicker.setText(active == null ? "" : active.model, false);

        providerList.removeAllViews();
        for (ProviderStore.Provider p : providers) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_provider, providerList, false);
            ((TextView) row.findViewById(R.id.provider_name)).setText(p.name.isEmpty() ? p.id : p.name);
            ((TextView) row.findViewById(R.id.provider_meta)).setText(getString(R.string.n_models, p.models.size())
                    + " · " + p.api + " · "
                    + getString(ProviderStore.hasApiKey(this, p.id) ? R.string.key_stored : R.string.key_missing));
            row.findViewById(R.id.provider_active).setVisibility(
                    active != null && active.provider.equals(p.id) ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> startActivity(new Intent(this, ProviderEditActivity.class)
                    .putExtra(ProviderEditActivity.EXTRA_PROVIDER_ID, p.id)));
            providerList.addView(row);
        }

        renderVoiceModel();
        renderBackground();
        renderSkills();
        renderLessons();
        renderMcp();

        boolean on = AgentAccessibilityService.get() != null;
        a11yState.setText(on ? R.string.perm_on : R.string.perm_off);
        a11yState.setTextColor(getColor(on ? R.color.success : R.color.danger));
    }

    /** Services that have at least one model that can run tasks. */
    private List<ProviderStore.Provider> chatProviders() {
        List<ProviderStore.Provider> out = new ArrayList<>();
        for (ProviderStore.Provider p : providers) if (!p.models(false).isEmpty()) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ voice call model

    /** Realtime voice models of all services as "model · service", with "offline voice" first. */
    private void renderVoiceModel() {
        final List<String[]> choices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        choices.add(null);
        labels.add(getString(R.string.voice_model_offline));
        for (ProviderStore.Provider p : providers) {
            for (ProviderStore.ModelSpec m : p.models(true)) {
                choices.add(new String[]{p.id, m.id});
                labels.add(m.id + " · " + (p.name.isEmpty() ? p.id : p.name));
            }
        }
        ProviderStore.Active v = ProviderStore.getVoice(this);
        int selected = 0;
        for (int i = 1; i < choices.size(); i++) {
            if (v != null && choices.get(i)[0].equals(v.provider) && choices.get(i)[1].equals(v.model)) selected = i;
        }
        MaterialAutoCompleteTextView picker = findViewById(R.id.voice_model);
        picker.setSimpleItems(labels.toArray(new String[0]));
        picker.setText(labels.get(selected), false);
        picker.setOnItemClickListener((parent, view, position, id) -> {
            String[] c = choices.get(position);
            try {
                ProviderStore.setVoice(this, c == null ? null : c[0], c == null ? null : c[1]);
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------ skills

    private void renderSkills() {
        List<Skill> skills = SkillStore.list(this);
        skillList.removeAllViews();
        findViewById(R.id.skills_empty).setVisibility(skills.isEmpty() ? View.VISIBLE : View.GONE);
        for (Skill sk : skills) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_skill, skillList, false);
            ((TextView) row.findViewById(R.id.skill_name)).setText(sk.name);
            ((TextView) row.findViewById(R.id.skill_meta)).setText(
                    getString(R.string.skill_meta, sk.actions.size(), sk.used, sk.succeeded));
            MaterialSwitch enabled = row.findViewById(R.id.skill_enabled);
            enabled.setChecked(sk.enabled);
            enabled.setOnCheckedChangeListener((b, on) -> {
                sk.enabled = on;
                updateSkill(sk);
            });
            row.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setTitle(sk.name)
                    .setItems(new CharSequence[]{getString(R.string.skill_rename), getString(R.string.delete)},
                            (d, which) -> {
                                if (which == 0) renameSkill(sk);
                                else confirmDeleteSkill(sk);
                            })
                    .show());
            skillList.addView(row);
        }
    }

    private void renameSkill(Skill sk) {
        EditText input = new EditText(this);
        input.setHint(R.string.skill_name_hint);
        input.setText(sk.name);
        input.setSelection(input.length());
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.skill_rename)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    sk.name = name;
                    updateSkill(sk);
                    renderSkills();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDeleteSkill(Skill sk) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.confirm_delete_skill, sk.name))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    try {
                        SkillStore.remove(this, sk.id);
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    renderSkills();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateSkill(Skill sk) {
        try {
            SkillStore.update(this, sk);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ MCP server

    private void setupMcp() {
        ((TextView) findViewById(R.id.mcp_hint)).setText(getString(R.string.setting_mcp_hint, McpService.PORT));
        MaterialSwitch on = findViewById(R.id.switch_mcp);
        on.setChecked(AppSettings.mcp(this));
        on.setOnCheckedChangeListener((b, checked) -> {
            AppSettings.setMcp(this, checked);
            McpService.apply(this);
            renderMcpSoon();
        });
        MaterialSwitch lan = findViewById(R.id.switch_mcp_lan);
        lan.setChecked(AppSettings.mcpLan(this));
        lan.setOnCheckedChangeListener((b, checked) -> {
            if (checked == AppSettings.mcpLan(this)) return;
            if (!checked) {
                setMcpLan(false);
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.confirm_mcp_lan_title)
                    .setMessage(R.string.confirm_mcp_lan)
                    .setPositiveButton(R.string.action_allow, (d, w) -> setMcpLan(true))
                    .setNegativeButton(R.string.cancel, (d, w) -> lan.setChecked(false))
                    .setOnCancelListener(d -> lan.setChecked(false))
                    .show();
        });
        findViewById(R.id.mcp_copy_token).setOnClickListener(v -> {
            try {
                copySecret(McpService.token(this));
                Toast.makeText(this, R.string.mcp_copied_token, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.mcp_regenerate).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.confirm_mcp_regenerate)
                .setPositiveButton(R.string.mcp_regenerate, (d, w) -> {
                    try {
                        McpService.newToken(this);
                        McpService.apply(this);
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        findViewById(R.id.mcp_copy_command).setOnClickListener(v -> {
            try {
                String url = "http://" + mcpHost() + ":" + McpService.PORT + McpServer.PATH;
                String command = (AppSettings.mcpLan(this) ? "" : "adb forward tcp:" + McpService.PORT + " tcp:" + McpService.PORT + " && ")
                        + "claude mcp add --transport http artemis-phone " + url
                        + " --header \"Authorization: Bearer " + McpService.token(this) + "\"";
                copySecret(command);
                Toast.makeText(this, R.string.mcp_copied_command, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setMcpLan(boolean on) {
        AppSettings.setMcpLan(this, on);
        McpService.apply(this);
        renderMcpSoon();
    }

    private String mcpHost() {
        if (!AppSettings.mcpLan(this)) return "127.0.0.1";
        String ip = McpService.lanAddress();
        return ip == null ? "<phone-ip>" : ip;
    }

    private void renderMcp() {
        boolean on = AppSettings.mcp(this);
        findViewById(R.id.mcp_details).setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        String url = "http://" + mcpHost() + ":" + McpService.PORT + McpServer.PATH;
        TextView address = findViewById(R.id.mcp_address);
        if (AppSettings.mcpLan(this) && McpService.lanAddress() == null) {
            address.setText(R.string.mcp_no_lan);
        } else {
            address.setText(AppSettings.mcpLan(this) ? getString(R.string.mcp_address, url)
                    : getString(R.string.mcp_address_local, url, McpService.PORT));
        }
        TextView error = findViewById(R.id.mcp_error);
        String err = McpService.lastError;
        error.setVisibility(err == null ? View.GONE : View.VISIBLE);
        if (err != null) error.setText(getString(R.string.mcp_not_running, err));
    }

    /** The service starts asynchronously; show its outcome once it had a moment. */
    private void renderMcpSoon() {
        renderMcp();
        findViewById(R.id.mcp_details).postDelayed(this::renderMcp, 1000);
    }

    /** Copies without showing the content in the clipboard preview (Android 13+). */
    private void copySecret(String text) {
        ClipData clip = ClipData.newPlainText("artemis-mcp", text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
    }

    // ------------------------------------------------------------------ experience

    private void setupLessons() {
        ((TextView) findViewById(R.id.reflect_hint)).setText(getString(R.string.setting_reflect_hint, Experience.ACTIVATE_AT));
        MaterialSwitch reflect = findViewById(R.id.switch_reflect);
        reflect.setChecked(AppSettings.reflect(this));
        reflect.setOnCheckedChangeListener((b, on) -> AppSettings.setReflect(this, on));
    }

    private void renderLessons() {
        List<Experience> all = ExperienceStore.list(this);
        LinearLayout list = findViewById(R.id.lessons);
        list.removeAllViews();
        findViewById(R.id.lessons_empty).setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        for (String layer : new String[]{Experience.PRINCIPLE, Experience.PATTERN, Experience.FACT}) {
            for (Experience e : all) {
                if (!layer.equals(e.layer)) continue;
                View row = LayoutInflater.from(this).inflate(R.layout.item_skill, list, false);
                ((TextView) row.findViewById(R.id.skill_name)).setText(e.promptLine());
                ((TextView) row.findViewById(R.id.skill_meta)).setText(lessonMeta(e));
                MaterialSwitch on = row.findViewById(R.id.skill_enabled);
                on.setChecked(!e.off);
                on.setOnCheckedChangeListener((b, checked) -> {
                    e.off = !checked;
                    updateLesson(e);
                    ((TextView) row.findViewById(R.id.skill_meta)).setText(lessonMeta(e));
                });
                row.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                        .setMessage(e.promptLine())
                        .setItems(new CharSequence[]{
                                getString(e.approved ? R.string.lesson_unapprove : R.string.lesson_approve),
                                getString(R.string.delete)}, (d, which) -> {
                            if (which == 0) {
                                e.approved = !e.approved;
                                updateLesson(e);
                                renderLessons();
                            } else {
                                confirmDeleteLesson(e);
                            }
                        })
                        .show());
                list.addView(row);
            }
        }
    }

    private String lessonMeta(Experience e) {
        int layer = Experience.FACT.equals(e.layer) ? R.string.lesson_layer_fact
                : Experience.PATTERN.equals(e.layer) ? R.string.lesson_layer_pattern : R.string.lesson_layer_principle;
        int state = e.off ? R.string.lesson_state_off : e.active() ? R.string.lesson_state_active : R.string.lesson_state_candidate;
        String head = getString(layer) + (e.app.isEmpty() ? "" : " · " + e.app);
        String meta = getString(R.string.lesson_meta, head, getString(state), e.seen);
        return e.uses == 0 ? meta : getString(R.string.lesson_meta_used, meta, e.uses, e.successRate());
    }

    private void confirmDeleteLesson(Experience e) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.confirm_delete_lesson)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    try {
                        ExperienceStore.remove(this, e.id);
                    } catch (Exception ex) {
                        Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    renderLessons();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateLesson(Experience e) {
        try {
            ExperienceStore.update(this, e);
        } catch (Exception ex) {
            Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
