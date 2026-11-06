package com.artemis.ondevice;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One run: outcome and answer, metrics, per-step timing chart and the step-by-step record. */
public class RunDetailActivity extends AppCompatActivity implements RunState.Listener {

    static final String EXTRA_RUN_ID = "run_id";
    private static final int THUMB_PX = 256;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Adapter adapter = new Adapter();
    private String runId;
    private RunRecord run;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_run_detail);
        runId = getIntent().getStringExtra(EXTRA_RUN_ID);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (run == null) return true;
            int id = item.getItemId();
            if (id == R.id.menu_rerun) {
                if (RunState.isRunning()) {
                    Toast.makeText(this, R.string.toast_running, Toast.LENGTH_SHORT).show();
                } else {
                    AgentService.start(this, run.task);
                    moveTaskToBack(true);
                }
            } else if (id == R.id.menu_copy) {
                getSystemService(ClipboardManager.class).setPrimaryClip(
                        ClipData.newPlainText("answer", run.answer));
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
            } else if (id == R.id.menu_delete) {
                new MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.confirm_delete_run)
                        .setPositiveButton(R.string.delete, (d, w) -> io.execute(() -> {
                            RunStore.delete(this, run.id);
                            main.post(this::finish);
                        }))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
            return true;
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RunState.subscribe(this);
    }

    @Override
    protected void onPause() {
        RunState.unsubscribe(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    /** Live run: render the in-memory record; otherwise load it from disk. */
    @Override
    public void onRunChanged() {
        RunRecord live = RunState.current();
        if (live != null && live.id.equals(runId)) {
            show(live);
            return;
        }
        if (run != null) return;
        io.execute(() -> {
            RunRecord r = RunStore.load(this, runId);
            main.post(() -> {
                if (isDestroyed()) return;
                if (r == null) {
                    Toast.makeText(this, R.string.run_not_found, Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    show(r);
                }
            });
        });
    }

    private void show(RunRecord r) {
        run = r;
        adapter.setSteps(r.steps);
    }

    // ------------------------------------------------------------------ header

    private void bindHeader(View h) {
        RunRecord r = run;
        if (r == null) return;
        Format.badge(h.findViewById(R.id.badge), h.findViewById(R.id.badge_icon), r.status);
        TextView status = h.findViewById(R.id.status);
        status.setText(r.isRunning()
                ? getString(R.string.status_progress, r.steps.size() + 1, Format.phase(this, RunState.phase()))
                : Format.status(this, r.status));
        status.setTextColor(Format.statusColor(this, r.status));
        ((TextView) h.findViewById(R.id.when)).setText(Format.when(this, r.startedAt));
        ((TextView) h.findViewById(R.id.task)).setText(r.task);
        String limit = r.maxSteps > 0 ? " · max " + r.maxSteps : "";
        ((TextView) h.findViewById(R.id.model)).setText(r.model + " · " + r.providerId + limit
                + (r.screenshot ? "" : " · text-only"));
        h.findViewById(R.id.answer_card).setVisibility(r.isRunning() ? View.GONE : View.VISIBLE);
        ((TextView) h.findViewById(R.id.answer)).setText(r.answer.isEmpty() ? getString(R.string.no_answer) : r.answer);

        GridLayout grid = h.findViewById(R.id.metrics);
        grid.removeAllViews();
        long llmShare = r.durationMs() == 0 ? 0 : Math.round(100.0 * r.totalLlmMs() / r.durationMs());
        addMetric(grid, Format.duration(r.durationMs()), R.string.metric_total);
        addMetric(grid, String.valueOf(r.steps.size()), R.string.metric_steps);
        addMetric(grid, Format.seconds(r.avgStepMs()), R.string.metric_avg_step);
        addMetric(grid, Format.seconds(r.medianStepMs()), R.string.metric_median_step);
        addMetric(grid, Format.seconds(r.medianLlmMs()) + " / " + Format.seconds(r.p90LlmMs()), R.string.metric_llm);
        addMetric(grid, llmShare + "%", R.string.metric_llm_share);
        addMetric(grid, r.avgObsMs() + " ms", R.string.metric_obs);
        addMetric(grid, r.avgActMs() + " ms", R.string.metric_act);
        addMetric(grid, Format.tokens(r.promptTokens()) + " / " + Format.tokens(r.completionTokens()), R.string.metric_tokens);
        addMetric(grid, Format.tokens(r.promptTokens() + r.completionTokens()), R.string.metric_tokens_total);
        addMetric(grid, String.valueOf(r.failedActions()), R.string.metric_failed_actions);
        addMetric(grid, Format.seconds(r.maxLlmMs()), R.string.metric_llm_max);

        ((StepChart) h.findViewById(R.id.chart)).setSteps(r.steps);
        legend(h.findViewById(R.id.legend_obs), R.color.bar_obs);
        legend(h.findViewById(R.id.legend_llm), R.color.bar_llm);
        legend(h.findViewById(R.id.legend_act), R.color.bar_act);
    }

    private void addMetric(GridLayout grid, String value, int label) {
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

    private void legend(TextView t, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(getColor(color));
        int s = Math.round(10 * getResources().getDisplayMetrics().density);
        dot.setSize(s, s);
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
    }

    // ------------------------------------------------------------------ list

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int HEADER = 0, STEP = 1;
        private final List<RunRecord.Step> steps = new ArrayList<>();

        void setSteps(List<RunRecord.Step> s) {
            steps.clear();
            steps.addAll(s);
            notifyDataSetChanged();
        }

        @Override public int getItemViewType(int position) { return position == 0 ? HEADER : STEP; }
        @Override public int getItemCount() { return run == null ? 0 : steps.size() + 1; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(
                    type == HEADER ? R.layout.view_run_header : R.layout.item_step, parent, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == 0) {
                bindHeader(holder.itemView);
                return;
            }
            RunRecord.Step s = steps.get(position - 1);
            View v = holder.itemView;
            long sec = s.offsetMs / 1000;
            ((TextView) v.findViewById(R.id.step_title)).setText(getString(R.string.step_title, s.index,
                    String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)));
            ((TextView) v.findViewById(R.id.step_actions)).setText(
                    s.actions.isEmpty() ? "(no tool call)" : TextUtils.join("\n", s.actions));
            TextView reasoning = v.findViewById(R.id.step_reasoning);
            reasoning.setText(s.reasoning);
            reasoning.setVisibility(s.reasoning.isEmpty() ? View.GONE : View.VISIBLE);
            reasoning.setMaxLines(3);
            reasoning.setOnClickListener(t -> reasoning.setMaxLines(reasoning.getMaxLines() == 3 ? Integer.MAX_VALUE : 3));
            ((TextView) v.findViewById(R.id.step_timing)).setText(getString(R.string.step_timing,
                    s.obsMs + "ms", Format.seconds(s.llmMs), s.actMs + "ms",
                    Format.tokens(s.promptTokens), Format.tokens(s.completionTokens)));
            ImageView thumb = v.findViewById(R.id.step_thumb);
            thumb.setImageDrawable(null);
            thumb.setVisibility(s.hasScreenshot ? View.VISIBLE : View.GONE);
            thumb.setTag(s.index);
            if (s.hasScreenshot) {
                final String id = run.id;
                final int idx = s.index;
                io.execute(() -> {
                    Bitmap b = RunStore.loadScreenshot(RunDetailActivity.this, id, idx, THUMB_PX);
                    main.post(() -> {
                        if (Integer.valueOf(idx).equals(thumb.getTag())) thumb.setImageBitmap(b);
                    });
                });
                thumb.setOnClickListener(t -> showScreenshot(id, idx));
            }
        }
    }

    private void showScreenshot(String id, int step) {
        io.execute(() -> {
            Bitmap b = RunStore.loadScreenshot(this, id, step, 1024);
            main.post(() -> {
                if (b == null || isDestroyed()) return;
                ImageView iv = new ImageView(this);
                iv.setAdjustViewBounds(true);
                iv.setImageBitmap(b);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.step_short, step))
                        .setView(iv)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }
}
