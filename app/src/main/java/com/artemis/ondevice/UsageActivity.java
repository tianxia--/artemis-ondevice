package com.artemis.ondevice;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model usage per period, model and purpose, estimated cost, and the token limits. */
public class UsageActivity extends AppCompatActivity {

    private static final int[] PERIOD_DAYS = {1, 7, 30, 0};
    private static final int[] PERIOD_LABELS = {R.string.usage_today, R.string.usage_7d, R.string.usage_30d, R.string.usage_all};
    private int days = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_usage);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        ChipGroup periods = findViewById(R.id.periods);
        for (int i = 0; i < PERIOD_DAYS.length; i++) {
            final int d = PERIOD_DAYS[i];
            Chip c = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            c.setText(PERIOD_LABELS[i]);
            c.setCheckable(true);
            c.setChecked(d == days);
            c.setOnClickListener(v -> {
                days = d;
                render();
            });
            periods.addView(c);
        }
        limitField(findViewById(R.id.limit_run), AppSettings.runTokenLimit(this), true);
        limitField(findViewById(R.id.limit_daily), AppSettings.dailyTokenLimit(this), false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void limitField(TextInputEditText f, long value, boolean run) {
        f.setText(String.valueOf(value));
        f.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                long v;
                try {
                    v = s.length() == 0 ? 0 : Long.parseLong(s.toString());
                } catch (NumberFormatException e) {
                    return;
                }
                if (run) AppSettings.setRunTokenLimit(UsageActivity.this, v);
                else AppSettings.setDailyTokenLimit(UsageActivity.this, v);
            }
        });
    }

    private void render() {
        List<UsageStore.Row> rows = UsageStore.rows(this, days);
        Map<String, ProviderStore.Provider> providers = new LinkedHashMap<>();
        for (ProviderStore.Provider p : ProviderStore.list(this)) providers.put(p.id, p);

        UsageStore.Row total = new UsageStore.Row();
        Map<String, UsageStore.Row> byModel = new LinkedHashMap<>();
        Map<String, UsageStore.Row> byPurpose = new LinkedHashMap<>();
        for (UsageStore.Row r : rows) {
            total.add(r);
            sum(byModel, r.provider + "|" + r.model, r);
            sum(byPurpose, r.purpose, r);
        }

        // Cost: only when every priced row shares one currency.
        Pricing.Total priced = Pricing.total(this, rows);

        GridLayout grid = findViewById(R.id.totals);
        grid.removeAllViews();
        metric(grid, String.valueOf(total.calls), getString(R.string.usage_calls));
        metric(grid, Format.tokens(total.tokens()), getString(R.string.metric_tokens_total));
        metric(grid, Format.tokens(total.in) + " / " + Format.tokens(total.out), getString(R.string.metric_tokens));
        metric(grid, priced.text().isEmpty() ? "–" : priced.text(), getString(R.string.usage_cost));

        List<String> notes = new ArrayList<>();
        if (days == 1) {
            long limit = AppSettings.dailyTokenLimit(this);
            if (limit > 0) {
                notes.add(getString(R.string.usage_today_of_limit, Format.tokens(total.tokens()), Format.tokens(limit),
                        Math.round(100f * total.tokens() / limit)));
            }
        }
        if (priced.unpriced) notes.add(getString(R.string.usage_unpriced));
        if (priced.mixed) notes.add(getString(R.string.usage_mixed_currency));
        if (priced.fromTable) notes.add(getString(R.string.usage_table_prices, Pricing.tableDate(this)));
        if (total.estimated > 0) notes.add(getString(R.string.usage_estimated, total.estimated));
        ((TextView) findViewById(R.id.usage_note)).setText(String.join("\n", notes));

        LinearLayout models = findViewById(R.id.by_model);
        models.removeAllViews();
        for (UsageStore.Row r : byModel.values()) {
            ProviderStore.Provider p = providers.get(r.provider);
            String c = Format.cost(this, p, r.model, r.in, r.out);
            row(models, r.model + " · " + (p == null || p.name.isEmpty() ? r.provider : p.name),
                    line(r) + (c.isEmpty() ? "" : " · " + c));
        }
        LinearLayout purposes = findViewById(R.id.by_purpose);
        purposes.removeAllViews();
        for (UsageStore.Row r : byPurpose.values()) row(purposes, purposeName(r.purpose), line(r));
        if (rows.isEmpty()) row(models, getString(R.string.usage_none), "");
    }

    private static void sum(Map<String, UsageStore.Row> map, String key, UsageStore.Row r) {
        UsageStore.Row s = map.get(key);
        if (s == null) {
            s = new UsageStore.Row();
            s.provider = r.provider;
            s.model = r.model;
            s.purpose = r.purpose;
            map.put(key, s);
        }
        s.add(r);
    }

    private String line(UsageStore.Row r) {
        return getString(R.string.usage_row, r.calls, Format.tokens(r.in), Format.tokens(r.out));
    }

    private String purposeName(String purpose) {
        switch (purpose) {
            case UsageStore.TASK: return getString(R.string.usage_purpose_task);
            case UsageStore.REVIEW: return getString(R.string.usage_purpose_review);
            case UsageStore.VOICE: return getString(R.string.usage_purpose_voice);
            case UsageStore.TEST: return getString(R.string.usage_purpose_test);
            default: return purpose;
        }
    }


    private void row(LinearLayout parent, String title, String detail) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_skill, parent, false);
        ((TextView) v.findViewById(R.id.skill_name)).setText(title);
        ((TextView) v.findViewById(R.id.skill_meta)).setText(detail);
        v.findViewById(R.id.skill_enabled).setVisibility(View.GONE);
        v.setClickable(false);
        parent.addView(v);
    }

    private void metric(GridLayout grid, String value, String label) {
        View v = LayoutInflater.from(this).inflate(R.layout.view_metric, grid, false);
        ((TextView) v.findViewById(R.id.metric_value)).setText(value);
        ((TextView) v.findViewById(R.id.metric_label)).setText(label);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        int m = Math.round(4 * getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        grid.addView(v, lp);
    }
}
