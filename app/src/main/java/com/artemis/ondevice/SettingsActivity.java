package com.artemis.ondevice;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
    private LinearLayout providerList;
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
            ((TextView) row.findViewById(R.id.provider_url)).setText(p.baseUrl);
            ((TextView) row.findViewById(R.id.provider_meta)).setText(getString(R.string.n_models, p.models.size())
                    + " · " + p.api + " · "
                    + getString(ProviderStore.hasApiKey(this, p.id) ? R.string.key_stored : R.string.key_missing));
            row.findViewById(R.id.provider_active).setVisibility(
                    active != null && active.provider.equals(p.id) ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> startActivity(new Intent(this, ProviderEditActivity.class)
                    .putExtra(ProviderEditActivity.EXTRA_PROVIDER_ID, p.id)));
            providerList.addView(row);
        }

        boolean on = AgentAccessibilityService.get() != null;
        a11yState.setText(on ? R.string.perm_on : R.string.perm_off);
        a11yState.setTextColor(getColor(on ? R.color.success : R.color.danger));
    }
}
