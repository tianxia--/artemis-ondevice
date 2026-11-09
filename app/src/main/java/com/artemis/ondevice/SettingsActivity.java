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

        findViewById(R.id.add_provider).setOnClickListener(
                v -> startActivity(new Intent(this, ProviderEditActivity.class)));
        findViewById(R.id.a11y_row).setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.clear_history).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.confirm_clear_history)
                .setPositiveButton(R.string.action_clear, (d, w) -> new Thread(() -> RunStore.clear(this)).start())
                .setNegativeButton(R.string.cancel, null)
                .show());

        providerPicker.setOnItemClickListener((parent, view, position, id) -> {
            ProviderStore.Provider p = providers.get(position);
            String model = p.models.isEmpty() ? null : p.models.get(0).id;
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
        setupMcp();

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
        picker.setOnItemClickListener((parent, view, position, id) -> AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(LANGUAGE_TAGS[position])));
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
        for (ProviderStore.Provider p : providers) names.add(p.toString());
        providerPicker.setSimpleItems(names.toArray(new String[0]));
        ProviderStore.Provider activeP = active == null ? null : ProviderStore.get(this, active.provider);
        providerPicker.setText(activeP == null ? "" : activeP.toString(), false);
        List<String> models = new ArrayList<>();
        if (activeP != null) for (ProviderStore.ModelSpec m : activeP.models) models.add(m.id);
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

        renderSkills();
        renderMcp();

        boolean on = AgentAccessibilityService.get() != null;
        a11yState.setText(on ? R.string.perm_on : R.string.perm_off);
        a11yState.setTextColor(getColor(on ? R.color.success : R.color.danger));
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
}
